//! Breeze recordings (1.2.3): what the Recordings panel lists, and ffmpeg.
//!
//! A recording (`<instance>/recordings/*.breezerec`) keeps the game, not a
//! video; the mod watches and exports it. The panel lists recordings (length,
//! world, Minecraft version, size, date, a thumbnail the mod saved when the
//! recording began) and the videos exported from them
//! (`<instance>/recordings/videos/*.mp4`, a frame from each as thumbnail).
//!
//! ffmpeg makes the videos. The mod looks for it in `<breeze home>/ffmpeg/`;
//! when it is missing the mod writes `<breeze home>/ffmpeg/wanted`, and the
//! launcher, which watches for that file, downloads the build for this
//! system, checks its SHA-256 and puts it there. The panel can fetch it too.

use std::fs;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{Duration, UNIX_EPOCH};

use base64::Engine;
use sha2::{Digest, Sha256};

/// ffmpeg 6.1.1 static builds (github.com/eugeneware/ffmpeg-static, release
/// b6.1.1), one gzipped binary per system, with the SHA-256 of each download.
const FFMPEG_RELEASE: &str = "https://github.com/eugeneware/ffmpeg-static/releases/download/b6.1.1/";
const FFMPEG_BUILDS: &[(&str, &str, &str, &str)] = &[
    // (os, arch, asset, sha256 of the .gz)
    ("windows", "x86_64", "ffmpeg-win32-x64.gz", "8883a3dffbd0a16cf4ef95206ea05283f78908dbfb118f73c83f4951dcc06d77"),
    ("macos", "x86_64", "ffmpeg-darwin-x64.gz", "929b375c1182d956c51f7ac25e0b2b0411fb01f6f407aa15c9758efeb4242106"),
    ("macos", "aarch64", "ffmpeg-darwin-arm64.gz", "8923876afa8db5585022d7860ec7e589af192f441c56793971276d450ed3bbfa"),
    ("linux", "x86_64", "ffmpeg-linux-x64.gz", "bfe8a8fc511530457b528c48d77b5737527b504a3797a9bc4866aeca69c2dffa"),
    ("linux", "aarch64", "ffmpeg-linux-arm64.gz", "754a678672298bc68156adff58aa7385a592c2b30b1d0ae8750c45c915c4bac0"),
];

const REC_EXT: &str = "breezerec";
const MAGIC: &[u8] = b"BRZREC";
const HEADER_ROOM: usize = 4096;

#[derive(serde::Serialize, Clone, Debug, PartialEq)]
pub struct RecordingEntry {
    pub name: String,
    /// "recording" or "video".
    pub kind: String,
    pub size_bytes: u64,
    pub modified_unix: u64,
    /// Length in ms (0 when unknown: a recording cut short, or a video without ffmpeg).
    pub duration_ms: u64,
    pub world: String,
    pub minecraft: String,
    pub finished: bool,
    /// A small JPEG as a data: URL, when there is one.
    pub thumbnail: Option<String>,
}

#[derive(serde::Serialize)]
pub struct RecordingsList {
    pub path: String,
    pub videos_path: String,
    pub items: Vec<RecordingEntry>,
    pub ffmpeg: bool,
}

pub fn ffmpeg_dir(home: &Path) -> PathBuf {
    home.join("ffmpeg")
}

pub fn ffmpeg_path(home: &Path) -> PathBuf {
    ffmpeg_dir(home).join(if cfg!(windows) { "ffmpeg.exe" } else { "ffmpeg" })
}

pub fn ffmpeg_installed(home: &Path) -> bool {
    ffmpeg_path(home).is_file()
}

/// The build for this system, if Breeze has one.
fn ffmpeg_build() -> Option<(&'static str, &'static str)> {
    let os = if cfg!(target_os = "windows") { "windows" } else if cfg!(target_os = "macos") { "macos" } else { "linux" };
    let arch = std::env::consts::ARCH;
    FFMPEG_BUILDS.iter().find(|(o, a, _, _)| *o == os && *a == arch).map(|(_, _, asset, sha)| (*asset, *sha))
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut h = Sha256::new();
    h.update(bytes);
    h.finalize().iter().map(|b| format!("{b:02x}")).collect()
}

/// Downloads ffmpeg for this system into `<home>/ffmpeg/` unless it is there.
/// The download must match its SHA-256, or nothing is kept.
pub async fn ensure_ffmpeg(client: &reqwest::Client, home: &Path) -> Result<PathBuf, String> {
    let target = ffmpeg_path(home);
    if target.is_file() {
        return Ok(target);
    }
    let (asset, sha) = ffmpeg_build().ok_or_else(|| "Breeze has no ffmpeg build for this system".to_string())?;
    let url = format!("{FFMPEG_RELEASE}{asset}");
    let resp = client.get(&url).send().await.map_err(|e| format!("ffmpeg download failed: {e}"))?;
    if !resp.status().is_success() {
        return Err(format!("ffmpeg download failed: HTTP {}", resp.status()));
    }
    let gz = resp.bytes().await.map_err(|e| format!("ffmpeg download failed: {e}"))?;
    install_ffmpeg(home, &gz, sha)
}

/// Checks a downloaded build against its hash and unpacks it (also used by the tests).
pub fn install_ffmpeg(home: &Path, gz: &[u8], sha: &str) -> Result<PathBuf, String> {
    let got = sha256_hex(gz);
    if got != sha {
        return Err(format!("the ffmpeg download did not match its checksum ({got})"));
    }
    let mut bin = Vec::new();
    flate2::read::GzDecoder::new(gz).read_to_end(&mut bin).map_err(|e| format!("ffmpeg could not be unpacked: {e}"))?;
    let dir = ffmpeg_dir(home);
    fs::create_dir_all(&dir).map_err(|e| format!("create {}: {e}", dir.display()))?;
    let target = ffmpeg_path(home);
    let part = dir.join("ffmpeg.part");
    fs::write(&part, &bin).map_err(|e| format!("write ffmpeg: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        fs::set_permissions(&part, fs::Permissions::from_mode(0o755)).map_err(|e| format!("make ffmpeg runnable: {e}"))?;
    }
    fs::rename(&part, &target).map_err(|e| format!("place ffmpeg: {e}"))?;
    let _ = fs::remove_file(dir.join("wanted"));
    Ok(target)
}

/// Watches for the mod's request (`<home>/ffmpeg/wanted`) and fetches ffmpeg
/// when it appears. Runs for the launcher's whole life; a stat every 3 s.
pub fn spawn_ffmpeg_watcher(home: PathBuf) {
    tauri::async_runtime::spawn(async move {
        let client = match reqwest::Client::builder().timeout(Duration::from_secs(600)).build() {
            Ok(c) => c,
            Err(_) => return,
        };
        let mut failures = 0u32;
        loop {
            tokio::time::sleep(Duration::from_secs(3)).await;
            let wanted = ffmpeg_dir(&home).join("wanted");
            if !wanted.exists() {
                continue;
            }
            if ffmpeg_installed(&home) {
                let _ = fs::remove_file(&wanted);
                continue;
            }
            match ensure_ffmpeg(&client, &home).await {
                Ok(_) => failures = 0,
                Err(e) => {
                    eprintln!("[breeze] {e}");
                    failures += 1;
                    // Try again later, not every 3 s.
                    tokio::time::sleep(Duration::from_secs(30 * failures.min(10) as u64)).await;
                }
            }
        }
    });
}

/// The header's JSON of a recording, or None when it is not one.
pub fn read_recording_meta(path: &Path) -> Option<serde_json::Value> {
    let mut f = fs::File::open(path).ok()?;
    let mut head = [0u8; 12];
    f.read_exact(&mut head).ok()?;
    if &head[..6] != MAGIC {
        return None;
    }
    let len = u32::from_be_bytes([head[8], head[9], head[10], head[11]]) as usize;
    if len > HEADER_ROOM {
        return None;
    }
    let mut json = vec![0u8; len];
    f.read_exact(&mut json).ok()?;
    serde_json::from_slice(&json).ok()
}

fn modified(meta: &fs::Metadata) -> u64 {
    meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0)
}

fn data_url(jpg: &Path) -> Option<String> {
    let b = fs::read(jpg).ok()?;
    if b.is_empty() || b.len() > 512 * 1024 {
        return None;
    }
    Some(format!("data:image/jpeg;base64,{}", base64::engine::general_purpose::STANDARD.encode(b)))
}

/// A frame from a video as a small JPEG, made once with ffmpeg and kept in `.thumbs`.
fn video_thumbnail(ffmpeg: Option<&Path>, video: &Path, thumbs: &Path) -> Option<String> {
    let name = video.file_name()?.to_string_lossy().to_string();
    let jpg = thumbs.join(format!("{name}.jpg"));
    if !jpg.is_file() {
        let ffmpeg = ffmpeg?;
        fs::create_dir_all(thumbs).ok()?;
        let mut cmd = Command::new(ffmpeg);
        cmd.args(["-hide_banner", "-loglevel", "error", "-y", "-ss", "1", "-i"]).arg(video)
            .args(["-frames:v", "1", "-vf", "scale=320:-2", "-q:v", "5"]).arg(&jpg);
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            cmd.creation_flags(0x0800_0000); // CREATE_NO_WINDOW
        }
        let ok = cmd.status().map(|s| s.success()).unwrap_or(false);
        if !ok {
            return None;
        }
    }
    data_url(&jpg)
}

/// A video's length in ms, read by ffmpeg from its header (0 when unknown).
fn video_duration_ms(ffmpeg: Option<&Path>, video: &Path) -> u64 {
    let Some(ffmpeg) = ffmpeg else { return 0 };
    let mut cmd = Command::new(ffmpeg);
    cmd.arg("-hide_banner").arg("-i").arg(video);
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000);
    }
    let Ok(out) = cmd.output() else { return 0 };
    parse_ffmpeg_duration(&String::from_utf8_lossy(&out.stderr))
}

/// "Duration: 00:01:02.50," in ffmpeg's report, as ms.
pub fn parse_ffmpeg_duration(text: &str) -> u64 {
    let Some(i) = text.find("Duration: ") else { return 0 };
    let t = &text[i + 10..];
    let t = t.split(',').next().unwrap_or("");
    let parts: Vec<&str> = t.trim().split(':').collect();
    if parts.len() != 3 {
        return 0;
    }
    let h: f64 = parts[0].parse().unwrap_or(0.0);
    let m: f64 = parts[1].parse().unwrap_or(0.0);
    let s: f64 = parts[2].parse().unwrap_or(0.0);
    ((h * 3600.0 + m * 60.0 + s) * 1000.0).round() as u64
}

/// Everything in an instance's recordings folder, newest first.
pub fn list(instance: &Path, home: &Path) -> Result<RecordingsList, String> {
    let dir = instance.join("recordings");
    let videos = dir.join("videos");
    let thumbs = dir.join(".thumbs");
    fs::create_dir_all(&dir).map_err(|e| format!("create recordings dir: {e}"))?;
    let ffmpeg = ffmpeg_path(home);
    let ff = if ffmpeg.is_file() { Some(ffmpeg.as_path()) } else { None };
    let mut items = Vec::new();
    if let Ok(it) = fs::read_dir(&dir) {
        for e in it.flatten() {
            let p = e.path();
            if !p.is_file() || p.extension().and_then(|x| x.to_str()) != Some(REC_EXT) {
                continue;
            }
            let Ok(meta) = e.metadata() else { continue };
            let name = e.file_name().to_string_lossy().to_string();
            let m = read_recording_meta(&p).unwrap_or(serde_json::Value::Null);
            let s = |k: &str| m.get(k).and_then(|v| v.as_str()).unwrap_or("").to_string();
            items.push(RecordingEntry {
                kind: "recording".into(),
                size_bytes: meta.len(),
                modified_unix: modified(&meta),
                duration_ms: m.get("durationMs").and_then(|v| v.as_u64()).unwrap_or(0),
                world: s("world"),
                minecraft: s("minecraft"),
                finished: m.get("finished").and_then(|v| v.as_bool()).unwrap_or(false),
                thumbnail: data_url(&thumbs.join(format!("{name}.jpg"))),
                name,
            });
        }
    }
    if let Ok(it) = fs::read_dir(&videos) {
        for e in it.flatten() {
            let p = e.path();
            if !p.is_file() || p.extension().and_then(|x| x.to_str()).map(|x| x.to_ascii_lowercase()) != Some("mp4".into()) {
                continue;
            }
            let Ok(meta) = e.metadata() else { continue };
            items.push(RecordingEntry {
                name: e.file_name().to_string_lossy().to_string(),
                kind: "video".into(),
                size_bytes: meta.len(),
                modified_unix: modified(&meta),
                duration_ms: video_duration_ms(ff, &p),
                world: String::new(),
                minecraft: String::new(),
                finished: true,
                thumbnail: video_thumbnail(ff, &p, &thumbs),
            });
        }
    }
    items.sort_by(|a, b| b.modified_unix.cmp(&a.modified_unix));
    Ok(RecordingsList {
        path: dir.to_string_lossy().to_string(),
        videos_path: videos.to_string_lossy().to_string(),
        items,
        ffmpeg: ff.is_some(),
    })
}

/// The file a panel entry names, only inside the recordings folder (or its
/// videos folder): a name with a path in it is refused.
pub fn resolve(instance: &Path, kind: &str, name: &str) -> Result<PathBuf, String> {
    if name.is_empty() || name.contains('/') || name.contains('\\') || name.contains("..") || name.starts_with('.') {
        return Err("not a recording name".into());
    }
    let dir = instance.join("recordings");
    let p = match kind {
        "recording" if name.ends_with(".breezerec") => dir.join(name),
        "video" if name.to_ascii_lowercase().ends_with(".mp4") => dir.join("videos").join(name),
        _ => return Err("not a recording or video".into()),
    };
    if !p.is_file() {
        return Err(format!("{name} is no longer there"));
    }
    Ok(p)
}

/// Deletes a recording or video and its thumbnail.
pub fn delete(instance: &Path, kind: &str, name: &str) -> Result<(), String> {
    let p = resolve(instance, kind, name)?;
    fs::remove_file(&p).map_err(|e| format!("{name} could not be deleted: {e}"))?;
    let _ = fs::remove_file(instance.join("recordings").join(".thumbs").join(format!("{name}.jpg")));
    Ok(())
}

/// Asks the game to open a recording once it reaches its title screen: the
/// mod reads and deletes `recordings/.open`. The panel then launches the
/// instance the normal way.
pub fn request_open(instance: &Path, name: &str) -> Result<(), String> {
    resolve(instance, "recording", name)?;
    fs::write(instance.join("recordings").join(".open"), name).map_err(|e| format!("could not ask the game to open it: {e}"))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("breeze-rec-test-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn write_rec(path: &Path, json: &str) {
        let mut b = Vec::new();
        b.extend_from_slice(MAGIC);
        b.extend_from_slice(&1u16.to_be_bytes());
        b.extend_from_slice(&(json.len() as u32).to_be_bytes());
        b.extend_from_slice(json.as_bytes());
        b.resize(6 + 2 + 4 + HEADER_ROOM, b' ');
        fs::write(path, b).unwrap();
    }

    #[test]
    fn lists_recordings_with_their_header() {
        let inst = tmp("list");
        let home = tmp("home");
        let rec = inst.join("recordings");
        fs::create_dir_all(rec.join("videos")).unwrap();
        write_rec(&rec.join("a.breezerec"), r#"{"minecraft":"1.21.11","world":"New World","durationMs":8070,"finished":true}"#);
        fs::write(rec.join("notes.txt"), "x").unwrap();
        fs::write(rec.join("videos").join("a.mp4"), "not really a video").unwrap();
        let l = list(&inst, &home).unwrap();
        assert_eq!(l.items.len(), 2);
        let r = l.items.iter().find(|i| i.kind == "recording").unwrap();
        assert_eq!(r.duration_ms, 8070);
        assert_eq!(r.world, "New World");
        assert_eq!(r.minecraft, "1.21.11");
        assert!(r.finished);
        assert!(!l.ffmpeg);
    }

    #[test]
    fn refuses_names_outside_the_folder() {
        let inst = tmp("resolve");
        fs::create_dir_all(inst.join("recordings")).unwrap();
        write_rec(&inst.join("recordings").join("b.breezerec"), "{}");
        assert!(resolve(&inst, "recording", "b.breezerec").is_ok());
        assert!(resolve(&inst, "recording", "../b.breezerec").is_err());
        assert!(resolve(&inst, "recording", "x/b.breezerec").is_err());
        assert!(resolve(&inst, "video", "b.breezerec").is_err());
        assert!(resolve(&inst, "recording", ".open").is_err());
        request_open(&inst, "b.breezerec").unwrap();
        assert_eq!(fs::read_to_string(inst.join("recordings").join(".open")).unwrap(), "b.breezerec");
        delete(&inst, "recording", "b.breezerec").unwrap();
        assert!(!inst.join("recordings").join("b.breezerec").exists());
    }

    #[test]
    fn ffmpeg_install_checks_the_hash() {
        let home = tmp("ff");
        let mut gz = Vec::new();
        {
            use std::io::Write;
            let mut e = flate2::write::GzEncoder::new(&mut gz, flate2::Compression::fast());
            e.write_all(b"#!/bin/sh\necho ffmpeg\n").unwrap();
            e.finish().unwrap();
        }
        assert!(install_ffmpeg(&home, &gz, "00").is_err());
        assert!(!ffmpeg_installed(&home));
        fs::create_dir_all(ffmpeg_dir(&home)).unwrap();
        fs::write(ffmpeg_dir(&home).join("wanted"), "").unwrap();
        let sha = sha256_hex(&gz);
        let p = install_ffmpeg(&home, &gz, &sha).unwrap();
        assert!(p.is_file());
        assert!(!ffmpeg_dir(&home).join("wanted").exists());
    }

    #[test]
    fn every_build_has_a_hash() {
        for (_, _, asset, sha) in FFMPEG_BUILDS {
            assert!(asset.ends_with(".gz"));
            assert_eq!(sha.len(), 64);
        }
    }

    #[test]
    fn reads_ffmpeg_durations() {
        assert_eq!(parse_ffmpeg_duration("  Duration: 00:01:02.50, start: 0.000000"), 62_500);
        assert_eq!(parse_ffmpeg_duration("no duration"), 0);
    }
}
