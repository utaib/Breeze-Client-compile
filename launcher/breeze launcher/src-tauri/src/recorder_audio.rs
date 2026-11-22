//! The Breeze recorder's sound (1.2.3): the game's own sound and the
//! microphone, each into its own WAV file, while the game records its screen.
//!
//! The launcher started the game and runs while it plays, and it knows the
//! game's process, so it records the sound: on Windows 10 2004 (build 19041)
//! and later, WASAPI process loopback of the game's process tree gives only
//! Minecraft's sound (not Discord or a music player), and the chosen
//! microphone through WASAPI. Other systems report sound as unavailable, with
//! a plain reason the game shows in its preferences.
//!
//! The game and the launcher talk through files in the instance's
//! `recordings/.audio/` folder, as they already do for ffmpeg: no port is
//! opened, and the launcher only ever acts on the instance it launched.
//!
//! - `status.json` (launcher, every 2 s while the game runs): whether game
//!   sound and microphone can be recorded, why not, and the microphones.
//! - `start.json` / `stop.json` (game): a recording's id (a plain file name),
//!   which tracks to record and which microphone.
//! - `<id>.json` (launcher): each track's file (in the same folder) and the
//!   wall-clock moment of its first sample, then `done` once the files are
//!   complete. The game joins them to its video in step with its first frame.

use std::fs;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde_json::{json, Value};

/// A microphone the player can choose.
#[derive(Clone, Debug, PartialEq)]
pub struct Device {
    pub id: String,
    pub name: String,
}

/// What this computer can record.
#[derive(Clone, Debug, PartialEq)]
pub struct Abilities {
    pub game: bool,
    pub mic: bool,
    pub reason: String,
    pub devices: Vec<Device>,
}

pub fn audio_dir(game_dir: &Path) -> PathBuf {
    game_dir.join("recordings").join(".audio")
}

fn now_ms() -> f64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs_f64() * 1000.0).unwrap_or(0.0)
}

/// A recording id is a plain file name: letters, digits, dot, dash, underscore.
pub fn safe_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 120
        && !id.contains("..")
        && id.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '-' || c == '_')
}

fn write_json(path: &Path, v: &Value) {
    if let Some(dir) = path.parent() {
        let _ = fs::create_dir_all(dir);
    }
    let tmp = path.with_extension("json.tmp");
    if fs::write(&tmp, v.to_string()).is_ok() && fs::rename(&tmp, path).is_err() {
        let _ = fs::write(path, v.to_string());
        let _ = fs::remove_file(&tmp);
    }
}

fn read_json(path: &Path) -> Option<Value> {
    let text = fs::read_to_string(path).ok()?;
    serde_json::from_str(&text).ok()
}

/// The request the game wrote, once: the file is removed as it is read.
fn take(path: &Path) -> Option<Value> {
    let v = read_json(path);
    let _ = fs::remove_file(path);
    v
}

pub fn status_json(a: &Abilities) -> Value {
    json!({
        "version": 1,
        "updated": now_ms() as u64,
        "game": a.game,
        "mic": a.mic,
        "reason": a.reason,
        "devices": a.devices.iter().map(|d| json!({"id": d.id, "name": d.name})).collect::<Vec<_>>(),
    })
}

/// A start request, checked: (id, game, mic, microphone id).
pub fn parse_start(v: &Value) -> Option<(String, bool, bool, String)> {
    let id = v.get("id")?.as_str()?.to_string();
    if !safe_id(&id) {
        return None;
    }
    let game = v.get("game").and_then(Value::as_bool).unwrap_or(false);
    let mic = v.get("mic").and_then(Value::as_bool).unwrap_or(false);
    let device = v.get("micDevice").and_then(Value::as_str).unwrap_or("").chars().take(512).collect::<String>();
    Some((id, game, mic, device))
}

/// One track's result for `<id>.json`.
#[derive(Clone, Debug, Default)]
pub struct TrackResult {
    pub file: Option<String>,
    pub start_epoch_ms: f64,
    pub error: Option<String>,
}

pub fn state_json(id: &str, done: bool, game: &TrackResult, mic: &TrackResult) -> Value {
    let track = |t: &TrackResult| match &t.file {
        Some(f) => json!({"file": f, "startEpochMs": t.start_epoch_ms}),
        None => Value::Null,
    };
    let errors: Vec<String> = [&game.error, &mic.error].iter().filter_map(|e| e.as_ref().cloned()).collect();
    json!({
        "id": id,
        "done": done,
        "game": track(game),
        "mic": track(mic),
        "error": errors.join("; "),
    })
}

/// Sets the flag false when dropped: the launcher's game thread holds one, so
/// the watcher ends whichever way that thread does.
pub struct StopOnDrop(pub Arc<AtomicBool>);

impl Drop for StopOnDrop {
    fn drop(&mut self) {
        self.0.store(false, Ordering::Relaxed);
    }
}

/// Watches the instance's `recordings/.audio/` while the game runs; stops any
/// recording and removes the status when the game ends (`running` false).
pub fn spawn_for_game(pid: u32, game_dir: PathBuf, running: Arc<AtomicBool>) {
    thread::Builder::new()
        .name("breeze-recorder-audio".into())
        .spawn(move || watch(pid, game_dir, running))
        .ok();
}

fn watch(pid: u32, game_dir: PathBuf, running: Arc<AtomicBool>) {
    let dir = audio_dir(&game_dir);
    let _ = fs::create_dir_all(&dir);
    // Requests left from an earlier game are not for this one.
    let _ = fs::remove_file(dir.join("start.json"));
    let _ = fs::remove_file(dir.join("stop.json"));
    let abilities = platform::abilities();
    let mut active: Option<(String, platform::Session)> = None;
    let mut ticks = 0u32;
    while running.load(Ordering::Relaxed) {
        if ticks % 8 == 0 {
            // Microphones come and go: listed again every 10 s.
            let a = if ticks % 40 == 0 { platform::abilities() } else { abilities.clone() };
            write_json(&dir.join("status.json"), &status_json(&a));
        }
        ticks = ticks.wrapping_add(1);
        if let Some(v) = take(&dir.join("start.json")) {
            if let Some((old, s)) = active.take() {
                finish(&dir, &old, s);
            }
            if let Some((id, game, mic, device)) = parse_start(&v) {
                let s = platform::Session::start(pid, &dir, &id, game, mic, &device);
                write_json(&dir.join(format!("{id}.json")), &state_json(&id, false, &s.game_result(), &s.mic_result()));
                active = Some((id, s));
            }
        }
        if let Some(v) = take(&dir.join("stop.json")) {
            let id = v.get("id").and_then(Value::as_str).unwrap_or("");
            if active.as_ref().map(|(a, _)| a == id).unwrap_or(false) {
                let (id, s) = active.take().unwrap();
                finish(&dir, &id, s);
            }
        }
        thread::sleep(Duration::from_millis(250));
    }
    if let Some((id, s)) = active.take() {
        finish(&dir, &id, s);
    }
    let _ = fs::remove_file(dir.join("status.json"));
}

fn finish(dir: &Path, id: &str, s: platform::Session) {
    let (game, mic) = s.stop();
    write_json(&dir.join(format!("{id}.json")), &state_json(id, true, &game, &mic));
}

// ── WAV files ───────────────────────────────────────────────────────────────

/// A 16-bit PCM WAV file written as samples arrive; the sizes are filled in at the end.
pub struct WavWriter {
    file: std::io::BufWriter<fs::File>,
    data_bytes: u64,
    channels: u16,
}

impl WavWriter {
    pub fn create(path: &Path, rate: u32, channels: u16) -> std::io::Result<Self> {
        use std::io::Write;
        let mut file = std::io::BufWriter::new(fs::File::create(path)?);
        let block = channels as u32 * 2;
        file.write_all(b"RIFF")?;
        file.write_all(&0u32.to_le_bytes())?;
        file.write_all(b"WAVEfmt ")?;
        file.write_all(&16u32.to_le_bytes())?;
        file.write_all(&1u16.to_le_bytes())?;
        file.write_all(&channels.to_le_bytes())?;
        file.write_all(&rate.to_le_bytes())?;
        file.write_all(&(rate * block).to_le_bytes())?;
        file.write_all(&(block as u16).to_le_bytes())?;
        file.write_all(&16u16.to_le_bytes())?;
        file.write_all(b"data")?;
        file.write_all(&0u32.to_le_bytes())?;
        Ok(WavWriter { file, data_bytes: 0, channels })
    }

    pub fn write(&mut self, samples: &[u8]) -> std::io::Result<()> {
        use std::io::Write;
        self.file.write_all(samples)?;
        self.data_bytes += samples.len() as u64;
        Ok(())
    }

    /// Silence for this many frames (a gap the device reported, or a silent packet).
    pub fn silence(&mut self, frames: u64) -> std::io::Result<()> {
        let zeros = [0u8; 4096];
        let mut left = frames * self.channels as u64 * 2;
        while left > 0 {
            let n = left.min(zeros.len() as u64) as usize;
            self.write(&zeros[..n])?;
            left -= n as u64;
        }
        Ok(())
    }

    pub fn bytes(&self) -> u64 {
        self.data_bytes
    }

    pub fn finish(mut self) -> std::io::Result<()> {
        use std::io::{Seek, SeekFrom, Write};
        self.file.flush()?;
        let data = self.data_bytes.min(u32::MAX as u64 - 36) as u32;
        let f = self.file.get_mut();
        f.seek(SeekFrom::Start(4))?;
        f.write_all(&(36 + data).to_le_bytes())?;
        f.seek(SeekFrom::Start(40))?;
        f.write_all(&data.to_le_bytes())?;
        f.flush()
    }
}

// ── Windows: WASAPI ─────────────────────────────────────────────────────────

#[cfg(windows)]
mod platform {
    use super::{Abilities, Device, TrackResult, WavWriter};
    use std::path::Path;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{mpsc, Arc, Mutex};
    use std::thread::{self, JoinHandle};
    use std::time::Duration;

    use windows::core::{implement, Interface, PCWSTR};
    use windows::Win32::Devices::FunctionDiscovery::PKEY_Device_FriendlyName;
    use windows::Win32::Media::Audio::*;
    use windows::Win32::System::Com::StructuredStorage::{PropVariantClear, PROPVARIANT};
    use windows::Win32::System::Com::{CoCreateInstance, CoInitializeEx, CoTaskMemFree, CoUninitialize, BLOB, CLSCTX_ALL, COINIT_MULTITHREADED, STGM_READ};
    use windows::Win32::System::Performance::{QueryPerformanceCounter, QueryPerformanceFrequency};
    use windows::Win32::System::Variant::VT_BLOB;

    const RATE: u32 = 48_000;
    const CHANNELS: u16 = 2;

    /// Windows 10 2004 (build 19041) brought process loopback.
    fn build() -> u32 {
        use windows::Win32::System::Registry::{RegGetValueW, HKEY_LOCAL_MACHINE, RRF_RT_REG_SZ};
        let mut buf = [0u16; 32];
        let mut size = (buf.len() * 2) as u32;
        let key = windows::core::w!("SOFTWARE\\Microsoft\\Windows NT\\CurrentVersion");
        let value = windows::core::w!("CurrentBuildNumber");
        let ok = unsafe { RegGetValueW(HKEY_LOCAL_MACHINE, key, value, RRF_RT_REG_SZ, None, Some(buf.as_mut_ptr() as *mut _), Some(&mut size)) };
        if ok.is_err() {
            return 0;
        }
        let len = buf.iter().position(|&c| c == 0).unwrap_or(buf.len());
        String::from_utf16_lossy(&buf[..len]).trim().parse().unwrap_or(0)
    }

    struct Com;
    impl Com {
        fn new() -> Com {
            unsafe {
                let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
            }
            Com
        }
    }
    impl Drop for Com {
        fn drop(&mut self) {
            unsafe { CoUninitialize() };
        }
    }

    fn microphones() -> Vec<Device> {
        let _com = Com::new();
        let mut out = Vec::new();
        unsafe {
            let Ok(en) = CoCreateInstance::<_, IMMDeviceEnumerator>(&MMDeviceEnumerator, None, CLSCTX_ALL) else { return out };
            let Ok(list) = en.EnumAudioEndpoints(eCapture, DEVICE_STATE_ACTIVE) else { return out };
            let n = list.GetCount().unwrap_or(0);
            for i in 0..n {
                let Ok(dev) = list.Item(i) else { continue };
                let Ok(idp) = dev.GetId() else { continue };
                let id = idp.to_string().unwrap_or_default();
                CoTaskMemFree(Some(idp.0 as *const _));
                let mut name = id.clone();
                if let Ok(store) = dev.OpenPropertyStore(STGM_READ) {
                    if let Ok(v) = store.GetValue(&PKEY_Device_FriendlyName) {
                        let s = v.to_string();
                        if !s.is_empty() {
                            name = s;
                        }
                    }
                }
                out.push(Device { id, name });
            }
        }
        out
    }

    pub fn abilities() -> Abilities {
        let b = build();
        let devices = microphones();
        let game = b >= 19041;
        let mic = !devices.is_empty();
        let reason = if !game && b > 0 {
            "Game sound needs Windows 10 version 2004 or later.".to_string()
        } else if !mic {
            "No microphone was found.".to_string()
        } else {
            String::new()
        };
        Abilities { game, mic, reason, devices }
    }

    fn format() -> WAVEFORMATEX {
        WAVEFORMATEX {
            wFormatTag: WAVE_FORMAT_PCM as u16,
            nChannels: CHANNELS,
            nSamplesPerSec: RATE,
            nAvgBytesPerSec: RATE * CHANNELS as u32 * 2,
            nBlockAlign: CHANNELS * 2,
            wBitsPerSample: 16,
            cbSize: 0,
        }
    }

    #[implement(IActivateAudioInterfaceCompletionHandler)]
    struct Done {
        tx: Mutex<Option<mpsc::Sender<()>>>,
    }

    impl IActivateAudioInterfaceCompletionHandler_Impl for Done_Impl {
        fn ActivateCompleted(&self, _op: windows::core::Ref<'_, IActivateAudioInterfaceAsyncOperation>) -> windows::core::Result<()> {
            if let Some(tx) = self.tx.lock().ok().and_then(|mut t| t.take()) {
                let _ = tx.send(());
            }
            Ok(())
        }
    }

    /// An audio client for the game's own sound: process loopback of its process tree.
    unsafe fn loopback_client(pid: u32) -> windows::core::Result<IAudioClient> {
        let params = AUDIOCLIENT_ACTIVATION_PARAMS {
            ActivationType: AUDIOCLIENT_ACTIVATION_TYPE_PROCESS_LOOPBACK,
            Anonymous: AUDIOCLIENT_ACTIVATION_PARAMS_0 {
                ProcessLoopbackParams: AUDIOCLIENT_PROCESS_LOOPBACK_PARAMS {
                    TargetProcessId: pid,
                    ProcessLoopbackMode: PROCESS_LOOPBACK_MODE_INCLUDE_TARGET_PROCESS_TREE,
                },
            },
        };
        let mut pv = PROPVARIANT::default();
        {
            let inner = &mut *pv.Anonymous.Anonymous;
            inner.vt = VT_BLOB;
            inner.Anonymous.blob = BLOB { cbSize: std::mem::size_of::<AUDIOCLIENT_ACTIVATION_PARAMS>() as u32, pBlobData: &params as *const _ as *mut u8 };
        }
        let (tx, rx) = mpsc::channel();
        let handler: IActivateAudioInterfaceCompletionHandler = Done { tx: Mutex::new(Some(tx)) }.into();
        let op = ActivateAudioInterfaceAsync(VIRTUAL_AUDIO_DEVICE_PROCESS_LOOPBACK, &IAudioClient::IID, Some(&pv), &handler)?;
        // The blob points at `params` on this stack: it must not be freed by PropVariantClear.
        std::mem::forget(pv);
        rx.recv_timeout(Duration::from_secs(5)).map_err(|_| windows::core::Error::from(windows::Win32::Foundation::E_FAIL))?;
        let mut hr = windows::core::HRESULT(0);
        let mut unk: Option<windows::core::IUnknown> = None;
        op.GetActivateResult(&mut hr, &mut unk)?;
        hr.ok()?;
        let client: IAudioClient = unk.ok_or_else(|| windows::core::Error::from(windows::Win32::Foundation::E_FAIL))?.cast()?;
        let fmt = format();
        client.Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
            2_000_000,
            0,
            &fmt,
            None,
        )?;
        Ok(client)
    }

    /// An audio client for a microphone (the system's default when id is empty), converted to 48 kHz 16-bit stereo.
    unsafe fn mic_client(id: &str) -> windows::core::Result<IAudioClient> {
        let en: IMMDeviceEnumerator = CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?;
        let dev = if id.is_empty() {
            en.GetDefaultAudioEndpoint(eCapture, eConsole)?
        } else {
            let wide: Vec<u16> = id.encode_utf16().chain(std::iter::once(0)).collect();
            match en.GetDevice(PCWSTR(wide.as_ptr())) {
                Ok(d) => d,
                Err(_) => en.GetDefaultAudioEndpoint(eCapture, eConsole)?,
            }
        };
        let client: IAudioClient = dev.Activate(CLSCTX_ALL, None)?;
        let fmt = format();
        client.Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_AUTOCONVERTPCM | AUDCLNT_STREAMFLAGS_SRC_DEFAULT_QUALITY,
            2_000_000,
            0,
            &fmt,
            None,
        )?;
        Ok(client)
    }

    fn epoch_of_qpc(qpc_100ns: u64) -> f64 {
        unsafe {
            let mut now = 0i64;
            let mut freq = 0i64;
            let _ = QueryPerformanceCounter(&mut now);
            let _ = QueryPerformanceFrequency(&mut freq);
            let now_100ns = if freq > 0 { (now as i128 * 10_000_000 / freq as i128) as i64 } else { 0 };
            let ago_ms = (now_100ns - qpc_100ns as i64) as f64 / 10_000.0;
            super::now_ms() - ago_ms.max(0.0)
        }
    }

    struct Track {
        stop: Arc<AtomicBool>,
        handle: Option<JoinHandle<TrackResult>>,
        started: Arc<Mutex<TrackResult>>,
    }

    impl Track {
        fn start(kind: &'static str, path: std::path::PathBuf, file_name: String, open: impl FnOnce() -> windows::core::Result<IAudioClient> + Send + 'static) -> Track {
            let stop = Arc::new(AtomicBool::new(false));
            let started = Arc::new(Mutex::new(TrackResult { file: Some(file_name.clone()), ..Default::default() }));
            let s2 = stop.clone();
            let st2 = started.clone();
            let handle = thread::Builder::new()
                .name(format!("breeze-audio-{kind}"))
                .spawn(move || {
                    let _com = Com::new();
                    let mut result = TrackResult { file: Some(file_name), ..Default::default() };
                    if let Err(e) = unsafe { capture(open, &path, &s2, &mut result, &st2) } {
                        result.error = Some(format!("{kind}: {}", e.message()));
                    }
                    if result.error.is_some() || result.start_epoch_ms <= 0.0 {
                        if result.start_epoch_ms <= 0.0 && result.error.is_none() {
                            result.error = Some(format!("{kind}: no sound was heard"));
                        }
                        let _ = std::fs::remove_file(&path);
                        result.file = None;
                    }
                    result
                })
                .ok();
            Track { stop, handle, started }
        }

        fn result_now(&self) -> TrackResult {
            self.started.lock().map(|r| r.clone()).unwrap_or_default()
        }

        fn stop(mut self) -> TrackResult {
            self.stop.store(true, Ordering::Relaxed);
            self.handle.take().and_then(|h| h.join().ok()).unwrap_or_default()
        }
    }

    unsafe fn capture(
        open: impl FnOnce() -> windows::core::Result<IAudioClient>,
        path: &Path,
        stop: &AtomicBool,
        result: &mut TrackResult,
        shared: &Mutex<TrackResult>,
    ) -> windows::core::Result<()> {
        let client = open()?;
        let cap: IAudioCaptureClient = client.GetService()?;
        let mut wav = WavWriter::create(path, RATE, CHANNELS).map_err(|_| windows::core::Error::from(windows::Win32::Foundation::E_FAIL))?;
        client.Start()?;
        let block = CHANNELS as usize * 2;
        let mut first_pos: Option<u64> = None;
        let mut frames: u64 = 0;
        while !stop.load(Ordering::Relaxed) {
            thread::sleep(Duration::from_millis(10));
            loop {
                let next = cap.GetNextPacketSize()?;
                if next == 0 {
                    break;
                }
                let mut data: *mut u8 = std::ptr::null_mut();
                let mut n: u32 = 0;
                let mut flags: u32 = 0;
                let mut pos: u64 = 0;
                let mut qpc: u64 = 0;
                cap.GetBuffer(&mut data, &mut n, &mut flags, Some(&mut pos), Some(&mut qpc))?;
                if first_pos.is_none() {
                    first_pos = Some(pos);
                    result.start_epoch_ms = epoch_of_qpc(qpc);
                    if let Ok(mut s) = shared.lock() {
                        s.start_epoch_ms = result.start_epoch_ms;
                    }
                }
                // A gap the device reports (nothing played for a while) is written as silence, so the track stays in time.
                let expected = pos.saturating_sub(first_pos.unwrap_or(pos));
                if expected > frames && expected - frames < RATE as u64 * 600 {
                    let _ = wav.silence(expected - frames);
                    frames = expected;
                }
                if flags & (AUDCLNT_BUFFERFLAGS_SILENT.0 as u32) != 0 || data.is_null() {
                    let _ = wav.silence(n as u64);
                } else {
                    let bytes = std::slice::from_raw_parts(data, n as usize * block);
                    let _ = wav.write(bytes);
                }
                frames += n as u64;
                cap.ReleaseBuffer(n)?;
            }
        }
        let _ = client.Stop();
        let _ = wav.finish();
        Ok(())
    }

    pub struct Session {
        game: Option<Track>,
        mic: Option<Track>,
        errors: Vec<String>,
    }

    impl Session {
        pub fn start(pid: u32, dir: &Path, id: &str, game: bool, mic: bool, device: &str) -> Session {
            let mut s = Session { game: None, mic: None, errors: Vec::new() };
            if game {
                let name = format!("{id}.game.wav");
                s.game = Some(Track::start("game sound", dir.join(&name), name, move || unsafe { loopback_client(pid) }));
            }
            if mic {
                let name = format!("{id}.mic.wav");
                let device = device.to_string();
                s.mic = Some(Track::start("microphone", dir.join(&name), name, move || unsafe { mic_client(&device) }));
            }
            s
        }

        pub fn game_result(&self) -> TrackResult {
            self.game.as_ref().map(Track::result_now).unwrap_or_default()
        }

        pub fn mic_result(&self) -> TrackResult {
            self.mic.as_ref().map(Track::result_now).unwrap_or_default()
        }

        pub fn stop(self) -> (TrackResult, TrackResult) {
            let _ = &self.errors;
            let g = self.game.map(Track::stop).unwrap_or_default();
            let m = self.mic.map(Track::stop).unwrap_or_default();
            (g, m)
        }
    }

    #[allow(dead_code)]
    fn _unused() {
        let _ = PropVariantClear;
    }
}

// ── Other systems: no sound yet ─────────────────────────────────────────────

#[cfg(not(windows))]
mod platform {
    use super::{Abilities, TrackResult};
    use std::path::Path;

    pub fn abilities() -> Abilities {
        let system = if cfg!(target_os = "macos") { "macOS" } else { "Linux" };
        Abilities {
            game: false,
            mic: false,
            reason: format!("Recording sound works on Windows 10 (version 2004) and later. On {system} the video has no sound."),
            devices: Vec::new(),
        }
    }

    pub struct Session;

    impl Session {
        pub fn start(_pid: u32, _dir: &Path, _id: &str, _game: bool, _mic: bool, _device: &str) -> Session {
            Session
        }
        pub fn game_result(&self) -> TrackResult {
            TrackResult::default()
        }
        pub fn mic_result(&self) -> TrackResult {
            TrackResult::default()
        }
        pub fn stop(self) -> (TrackResult, TrackResult) {
            (TrackResult::default(), TrackResult::default())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ids_are_plain_file_names() {
        assert!(safe_id("2026-11-22_10.00.00"));
        assert!(safe_id("2026-11-22_10.00.00_1"));
        assert!(!safe_id("../x"));
        assert!(!safe_id("a/b"));
        assert!(!safe_id("a\\b"));
        assert!(!safe_id(""));
        assert!(!safe_id("C:x"));
    }

    #[test]
    fn start_requests_are_checked() {
        let ok = json!({"id": "2026-11-22_10.00.00", "game": true, "mic": false, "micDevice": "{0.0.1.00000000}.{abc}"});
        assert_eq!(parse_start(&ok), Some(("2026-11-22_10.00.00".into(), true, false, "{0.0.1.00000000}.{abc}".into())));
        assert_eq!(parse_start(&json!({"id": "../../evil", "game": true})), None);
        assert_eq!(parse_start(&json!({"game": true})), None);
    }

    #[test]
    fn state_names_files_only() {
        let g = TrackResult { file: Some("a.game.wav".into()), start_epoch_ms: 12.5, error: None };
        let m = TrackResult { file: None, start_epoch_ms: 0.0, error: Some("microphone: no sound was heard".into()) };
        let v = state_json("a", true, &g, &m);
        assert_eq!(v["game"]["file"], "a.game.wav");
        assert_eq!(v["game"]["startEpochMs"], 12.5);
        assert!(v["mic"].is_null());
        assert_eq!(v["done"], true);
        assert!(v["error"].as_str().unwrap().contains("microphone"));
    }

    #[test]
    fn wav_header_is_completed() {
        let dir = std::env::temp_dir().join(format!("breeze-wav-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let p = dir.join("t.wav");
        let mut w = WavWriter::create(&p, 48_000, 2).unwrap();
        w.write(&[1, 0, 2, 0, 3, 0, 4, 0]).unwrap();
        w.silence(10).unwrap();
        assert_eq!(w.bytes(), 8 + 40);
        w.finish().unwrap();
        let b = fs::read(&p).unwrap();
        assert_eq!(&b[0..4], b"RIFF");
        assert_eq!(u32::from_le_bytes([b[4], b[5], b[6], b[7]]), 36 + 48);
        assert_eq!(u32::from_le_bytes([b[40], b[41], b[42], b[43]]), 48);
        assert_eq!(b.len(), 44 + 48);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_channel_round_trip() {
        let dir = std::env::temp_dir().join(format!("breeze-audio-{}", std::process::id()));
        let game = dir.join("game");
        let running = Arc::new(AtomicBool::new(true));
        spawn_for_game(std::process::id(), game.clone(), running.clone());
        let ad = audio_dir(&game);
        let mut status = None;
        for _ in 0..40 {
            thread::sleep(Duration::from_millis(100));
            status = read_json(&ad.join("status.json"));
            if status.is_some() {
                break;
            }
        }
        let status = status.expect("status.json written");
        assert!(status["updated"].as_u64().unwrap() > 0);
        write_json(&ad.join("start.json"), &json!({"id": "rt", "game": true, "mic": true, "micDevice": ""}));
        thread::sleep(Duration::from_millis(800));
        write_json(&ad.join("stop.json"), &json!({"id": "rt"}));
        let mut state = None;
        for _ in 0..40 {
            thread::sleep(Duration::from_millis(100));
            state = read_json(&ad.join("rt.json")).filter(|v| v["done"] == true);
            if state.is_some() {
                break;
            }
        }
        assert!(state.is_some(), "rt.json done");
        running.store(false, Ordering::Relaxed);
        thread::sleep(Duration::from_millis(500));
        assert!(!ad.join("status.json").exists());
        let _ = fs::remove_dir_all(&dir);
    }
}
