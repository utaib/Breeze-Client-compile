//! Mod loaders besides Fabric (launcher 1.2.0): Vanilla, Quilt, Forge and
//! NeoForge.
//!
//! Fabric stays where it was (`ensure_launch_version` in lib.rs). This file
//! adds what the others need:
//!
//! - **Which instance.** A loader other than Fabric gets its own instance
//!   folder, `<version>-<loader>` (`1.20.1-forge`), so Forge mods never sit in
//!   a Fabric mods folder. Fabric keeps the bare version, so nothing that
//!   exists moves. The id says both things, and every profile command can
//!   read the Minecraft version and the loader back from it.
//! - **Which build.** Quilt's and NeoForge's lists and Forge's promotions.
//! - **Installing Forge and NeoForge.** Both ship an installer jar made for
//!   Mojang's launcher: a `version.json` to merge onto the game's own, a list
//!   of libraries (some inside the jar under `maven/`), and "processors",
//!   small Java programs that build the patched game from the player's own
//!   copy of Minecraft. This does what the installer's client install does,
//!   in the same order, with Breeze's folders (the steps were written from
//!   the installer's published source, `ClientInstall` and `PostProcessors`).
//!   Nothing of Minecraft is downloaded from anywhere but Mojang.
//!
//! The network cannot be reached from where this was written, so the real
//! installs are proven by the live tests in lib.rs (`live_loader_`), which
//! the build workflow runs.

use serde_json::Value;
use sha1::{Digest, Sha1};
use std::{
    collections::HashMap,
    fs,
    io::Read,
    path::{Path, PathBuf},
    process::Stdio,
    sync::Arc,
};

const QUILT_META: &str = "https://meta.quiltmc.org/v3/versions/loader";
const FORGE_PROMOTIONS: &str = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json";
const FORGE_MAVEN: &str = "https://maven.minecraftforge.net";
const NEOFORGE_MAVEN: &str = "https://maven.neoforged.net/releases";
const NEOFORGE_VERSIONS: &str = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge";
/// NeoForge for 1.20.1 was still published as a fork of Forge, under this name.
const NEOFORGE_LEGACY_VERSIONS: &str = "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/forge";

/// An installer larger than this is not one (the real ones are 5 to 12 MB).
const MAX_INSTALLER_BYTES: u64 = 64 * 1024 * 1024;
/// One file taken out of an installer is never near this.
const MAX_ENTRY_BYTES: u64 = 256 * 1024 * 1024;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Loader {
    Vanilla,
    Fabric,
    Quilt,
    Forge,
    NeoForge,
}

impl Loader {
    pub const ALL: [Loader; 5] = [Loader::Fabric, Loader::Quilt, Loader::Forge, Loader::NeoForge, Loader::Vanilla];

    pub fn parse(raw: &str) -> Option<Loader> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "vanilla" => Some(Loader::Vanilla),
            "fabric" => Some(Loader::Fabric),
            "quilt" => Some(Loader::Quilt),
            "forge" => Some(Loader::Forge),
            "neoforge" => Some(Loader::NeoForge),
            _ => None,
        }
    }

    /// The word used in instance ids, requests and Modrinth's and CurseForge's filters.
    pub fn id(self) -> &'static str {
        match self {
            Loader::Vanilla => "vanilla",
            Loader::Fabric => "fabric",
            Loader::Quilt => "quilt",
            Loader::Forge => "forge",
            Loader::NeoForge => "neoforge",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Loader::Vanilla => "Vanilla",
            Loader::Fabric => "Fabric",
            Loader::Quilt => "Quilt",
            Loader::Forge => "Forge",
            Loader::NeoForge => "NeoForge",
        }
    }

    /// Whether mod jars mean anything to it.
    pub fn has_mods(self) -> bool {
        self != Loader::Vanilla
    }
}

/// The instance folder for a version and a loader. Fabric keeps the bare
/// version, as before loaders existed.
pub fn instance_id(version: &str, loader: Loader) -> String {
    match loader {
        Loader::Fabric => version.trim().to_string(),
        other => format!("{}-{}", version.trim(), other.id()),
    }
}

/// The Minecraft version and loader an instance id stands for. An id with no
/// loader at its end is a Fabric instance; no Minecraft version ends in one of
/// these words.
pub fn split_instance_id(id: &str) -> (&str, Loader) {
    let id = id.trim();
    for loader in [Loader::NeoForge, Loader::Forge, Loader::Quilt, Loader::Vanilla] {
        if let Some(version) = id.strip_suffix(loader.id()).and_then(|rest| rest.strip_suffix('-')) {
            if !version.is_empty() {
                return (version, loader);
            }
        }
    }
    (id, Loader::Fabric)
}

// ── Which build ─────────────────────────────────────────────────────────────

/// Quilt's newest loader for a version: the first one that is not a beta, or
/// the first at all. `list` is Quilt's answer for the version, newest first.
pub fn pick_quilt(list: &Value) -> Option<String> {
    let versions: Vec<&str> = list
        .as_array()?
        .iter()
        .filter_map(|entry| entry.get("loader")?.get("version")?.as_str())
        .collect();
    versions
        .iter()
        .find(|v| !v.contains('-'))
        .or_else(|| versions.first())
        .map(|v| v.to_string())
}

/// Forge's build for a version from its promotions: the recommended one, or
/// the latest where none is recommended yet.
pub fn pick_forge(promotions: &Value, minecraft: &str) -> Option<String> {
    let promos = promotions.get("promos")?;
    ["recommended", "latest"]
        .iter()
        .find_map(|kind| promos.get(format!("{minecraft}-{kind}"))?.as_str())
        .filter(|v| safe_version(v))
        .map(str::to_string)
}

/// Whether a NeoForge version is for this Minecraft version.
///
/// NeoForge drops Minecraft's leading "1.": 21.1.77 is for 1.21.1, 21.0.x for
/// 1.21, 20.4.x for 1.20.4. For Minecraft's year numbers (26.1) either the
/// version carries the whole Minecraft version first (26.1.0.x for 26.1,
/// 26.1.1.x for 26.1.1) or it has three parts (26.1.x); both are accepted and
/// never mixed up.
fn neoforge_is_for(version: &str, minecraft: &str) -> bool {
    let core = version.split(['-', '+']).next().unwrap_or(version);
    let parts: Vec<&str> = core.split('.').collect();
    let mc: Vec<&str> = minecraft.split('.').collect();
    if mc.first() == Some(&"1") {
        let minor = mc.get(1).copied().unwrap_or("");
        let patch = mc.get(2).copied().unwrap_or("0");
        return parts.len() >= 3 && parts[0] == minor && parts[1] == patch;
    }
    match mc.len() {
        2 => {
            parts.len() >= 3
                && parts[0] == mc[0]
                && parts[1] == mc[1]
                && (parts.len() == 3 || parts[2] == "0")
        }
        3 => parts.len() >= 4 && parts[0] == mc[0] && parts[1] == mc[1] && parts[2] == mc[2],
        _ => false,
    }
}

/// The numbers of a version, for telling which of two builds is newer.
fn numbers(version: &str) -> Vec<u64> {
    version
        .split(['-', '+'])
        .next()
        .unwrap_or(version)
        .split('.')
        .map(|part| part.parse().unwrap_or(0))
        .collect()
}

/// NeoForge's newest build for a version: the newest that is not a beta, or
/// the newest beta where there is nothing else yet.
pub fn pick_neoforge(versions: &[String], minecraft: &str) -> Option<String> {
    let mut mine: Vec<&String> = versions
        .iter()
        .filter(|v| safe_version(v) && neoforge_is_for(v, minecraft))
        .collect();
    mine.sort_by_key(|v| numbers(v));
    mine.iter()
        .rev()
        .find(|v| !v.contains('-'))
        .or_else(|| mine.last())
        .map(|v| v.to_string())
}

/// The same for the builds published as `net.neoforged:forge` (1.20.1 only),
/// which are written `1.20.1-47.1.106`.
pub fn pick_neoforge_legacy(versions: &[String], minecraft: &str) -> Option<String> {
    let prefix = format!("{minecraft}-");
    let mut mine: Vec<&str> = versions
        .iter()
        .filter(|v| safe_version(v))
        .filter_map(|v| v.strip_prefix(&prefix))
        .filter(|build| !build.contains('-'))
        .collect();
    mine.sort_by_key(|v| numbers(v));
    mine.last().map(|v| v.to_string())
}

/// A version that may be put in an address or a file name.
fn safe_version(version: &str) -> bool {
    !version.is_empty()
        && version.len() <= 64
        && !version.starts_with('.')
        && !version.contains("..")
        && version.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+'))
}

fn versions_of(doc: &Value) -> Vec<String> {
    doc.get("versions")
        .and_then(Value::as_array)
        .map(|list| list.iter().filter_map(Value::as_str).map(str::to_string).collect())
        .unwrap_or_default()
}

/// The build of a loader Breeze would install for a Minecraft version, or None
/// when the loader has none for it. Vanilla and Fabric are not asked here.
pub async fn latest_build(client: &reqwest::Client, loader: Loader, minecraft: &str) -> Result<Option<String>, String> {
    if !safe_version(minecraft) {
        return Err("That is not a Minecraft version.".into());
    }
    match loader {
        Loader::Vanilla | Loader::Fabric => Ok(None),
        Loader::Quilt => {
            let list: Value = crate::fetch_json(client, &format!("{QUILT_META}/{minecraft}")).await?;
            Ok(pick_quilt(&list))
        }
        Loader::Forge => {
            let promotions: Value = crate::fetch_json(client, FORGE_PROMOTIONS).await?;
            Ok(pick_forge(&promotions, minecraft))
        }
        Loader::NeoForge => {
            if minecraft == "1.20.1" {
                let doc: Value = crate::fetch_json(client, NEOFORGE_LEGACY_VERSIONS).await?;
                return Ok(pick_neoforge_legacy(&versions_of(&doc), minecraft));
            }
            let doc: Value = crate::fetch_json(client, NEOFORGE_VERSIONS).await?;
            Ok(pick_neoforge(&versions_of(&doc), minecraft))
        }
    }
}

/// Where a build's installer is published.
fn installer_url(loader: Loader, minecraft: &str, build: &str) -> Option<String> {
    match loader {
        Loader::Forge => Some(format!(
            "{FORGE_MAVEN}/net/minecraftforge/forge/{minecraft}-{build}/forge-{minecraft}-{build}-installer.jar"
        )),
        Loader::NeoForge if minecraft == "1.20.1" => Some(format!(
            "{NEOFORGE_MAVEN}/net/neoforged/forge/{minecraft}-{build}/forge-{minecraft}-{build}-installer.jar"
        )),
        Loader::NeoForge => Some(format!(
            "{NEOFORGE_MAVEN}/net/neoforged/neoforge/{build}/neoforge-{build}-installer.jar"
        )),
        _ => None,
    }
}

// ── Quilt ───────────────────────────────────────────────────────────────────

/// Quilt's launch profile for a version, kept beside the game's own like
/// Fabric's. Returns the profile and the loader version used.
pub async fn ensure_quilt(
    client: &reqwest::Client,
    home: &Path,
    minecraft: &str,
    wanted: Option<&str>,
) -> Result<(Value, String), String> {
    let build = match wanted.map(str::trim).filter(|v| !v.is_empty()) {
        Some(v) if safe_version(v) => v.to_string(),
        Some(_) => return Err("That is not a Quilt Loader version.".into()),
        None => match installed_build(home, Loader::Quilt, minecraft) {
            Some(v) => v,
            None => latest_build(client, Loader::Quilt, minecraft)
                .await?
                .ok_or_else(|| format!("Quilt has no loader for Minecraft {minecraft}."))?,
        },
    };
    let name = format!("quilt-loader-{build}-{minecraft}");
    let path = home.join("versions").join(&name).join(format!("{name}.json"));
    let profile = if path.is_file() {
        crate::read_json_value(&path)?
    } else {
        let value: Value = crate::fetch_json(client, &format!("{QUILT_META}/{minecraft}/{build}/profile/json")).await?;
        if value.get("mainClass").and_then(Value::as_str).is_none() {
            return Err(format!("Quilt's profile for Minecraft {minecraft} is not a launch profile."));
        }
        crate::write_json_value(&path, &value)?;
        value
    };
    remember_build(home, Loader::Quilt, minecraft, &build);
    Ok((profile, build))
}

// ── What is installed ───────────────────────────────────────────────────────

fn record_path(home: &Path, loader: Loader, minecraft: &str) -> PathBuf {
    home.join("loaders").join(format!("{}-{minecraft}.json", loader.id()))
}

/// The build of a loader this computer already has for a version. A launch
/// uses it rather than asking for the newest each time, so the game still
/// starts without the network and a new build never changes a player's game
/// behind their back.
pub fn installed_build(home: &Path, loader: Loader, minecraft: &str) -> Option<String> {
    let doc = crate::read_json_value(&record_path(home, loader, minecraft)).ok()?;
    doc.get("build").and_then(Value::as_str).filter(|v| safe_version(v)).map(str::to_string)
}

fn remember_build(home: &Path, loader: Loader, minecraft: &str, build: &str) {
    let _ = crate::write_json_value(
        &record_path(home, loader, minecraft),
        &serde_json::json!({ "loader": loader.id(), "minecraft": minecraft, "build": build }),
    );
}

/// Forget which build is installed, so the next launch asks for the newest.
/// The files stay: another version may share them.
pub fn forget_build(home: &Path, loader: Loader, minecraft: &str) {
    let _ = fs::remove_file(record_path(home, loader, minecraft));
}

// ── Maven names ─────────────────────────────────────────────────────────────

/// `group:artifact:version[:classifier][@ext]` as a path under a libraries
/// folder, the way Forge's installer reads it. None for anything that is not
/// such a name or would leave the folder.
pub fn artifact_path(descriptor: &str) -> Option<String> {
    let (coords, ext) = match descriptor.split_once('@') {
        Some((coords, ext)) => (coords, ext),
        None => (descriptor, "jar"),
    };
    let parts: Vec<&str> = coords.split(':').collect();
    if parts.len() < 3 || parts.len() > 4 {
        return None;
    }
    let clean = |part: &str| {
        !part.is_empty()
            && !part.starts_with('.')
            && !part.contains("..")
            && part.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+'))
    };
    if !parts.iter().all(|p| clean(p)) || !clean(ext) || ext.contains('.') && ext != "tar.gz" {
        return None;
    }
    let (group, name, version) = (parts[0], parts[1], parts[2]);
    let mut file = format!("{name}-{version}");
    if let Some(classifier) = parts.get(3) {
        file.push('-');
        file.push_str(classifier);
    }
    Some(format!("{}/{name}/{version}/{file}.{ext}", group.replace('.', "/")))
}

/// A path from a version file's `downloads.artifact.path`, refused if it could
/// leave the libraries folder.
fn safe_relative(path: &str) -> Option<&str> {
    let ok = !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|part| !part.is_empty() && part != "." && part != "..");
    ok.then_some(path)
}

// ── Forge's own token language ──────────────────────────────────────────────

/// `{KEY}` replaced from the data, `'text'` taken as it is, `\` escaping the
/// next character: Forge's `Util.replaceTokens`.
pub fn replace_tokens(tokens: &HashMap<String, String>, value: &str) -> Result<String, String> {
    let chars: Vec<char> = value.chars().collect();
    let mut out = String::new();
    let mut x = 0;
    while x < chars.len() {
        let c = chars[x];
        if c == '\\' {
            x += 1;
            out.push(*chars.get(x).ok_or_else(|| format!("Bad escape in installer value: {value}"))?);
        } else if c == '{' || c == '\'' {
            let close = if c == '{' { '}' } else { '\'' };
            let mut key = String::new();
            let mut y = x + 1;
            loop {
                let d = *chars.get(y).ok_or_else(|| format!("Unclosed {c} in installer value: {value}"))?;
                if d == '\\' {
                    y += 1;
                    key.push(*chars.get(y).ok_or_else(|| format!("Bad escape in installer value: {value}"))?);
                } else if d == close {
                    break;
                } else {
                    key.push(d);
                }
                y += 1;
            }
            x = y;
            if c == '\'' {
                out.push_str(&key);
            } else {
                out.push_str(tokens.get(&key).ok_or_else(|| format!("The installer names a value it does not define: {key}"))?);
            }
        } else {
            out.push(c);
        }
        x += 1;
    }
    Ok(out)
}

fn bracketed(value: &str) -> Option<&str> {
    value.strip_prefix('[').and_then(|rest| rest.strip_suffix(']'))
}

fn library_file(libraries: &Path, descriptor: &str) -> Result<PathBuf, String> {
    artifact_path(descriptor)
        .map(|path| libraries.join(path))
        .ok_or_else(|| format!("The installer names a library Breeze cannot place: {descriptor}"))
}

fn text(path: &Path) -> String {
    path.to_string_lossy().to_string()
}

// ── The installer jar ───────────────────────────────────────────────────────

fn open_installer(path: &Path) -> Result<zip::ZipArchive<fs::File>, String> {
    let file = fs::File::open(path).map_err(|e| format!("Could not open the installer: {e}"))?;
    zip::ZipArchive::new(file).map_err(|e| format!("The installer is not a jar: {e}"))
}

/// One file out of the installer, by its exact name. None when it has none.
fn installer_entry(installer: &Path, name: &str) -> Result<Option<Vec<u8>>, String> {
    let mut archive = open_installer(installer)?;
    let mut entry = match archive.by_name(name) {
        Ok(entry) => entry,
        Err(zip::result::ZipError::FileNotFound) => return Ok(None),
        Err(e) => return Err(format!("Could not read {name} from the installer: {e}")),
    };
    if entry.size() > MAX_ENTRY_BYTES {
        return Err(format!("{name} in the installer is too large."));
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry
        .by_ref()
        .take(MAX_ENTRY_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|e| format!("Could not read {name} from the installer: {e}"))?;
    Ok(Some(bytes))
}

fn installer_json(installer: &Path, name: &str) -> Result<Value, String> {
    let bytes = installer_entry(installer, name)?.ok_or_else(|| format!("The installer has no {name}."))?;
    serde_json::from_slice(&bytes).map_err(|e| format!("The installer's {name} could not be read: {e}"))
}

pub fn sha1_of(path: &Path) -> Option<String> {
    let mut file = fs::File::open(path).ok()?;
    let mut hasher = Sha1::new();
    let mut buffer = [0u8; 64 * 1024];
    loop {
        let read = file.read(&mut buffer).ok()?;
        if read == 0 {
            break;
        }
        hasher.update(&buffer[..read]);
    }
    Some(hasher.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

fn write_atomically(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Could not create {}: {e}", parent.display()))?;
    }
    let part = path.with_extension("breeze-part");
    fs::write(&part, bytes).map_err(|e| format!("Could not write {}: {e}", part.display()))?;
    let _ = fs::remove_file(path);
    fs::rename(&part, path).map_err(|e| format!("Could not move {} into place: {e}", path.display()))
}

// ── Libraries ───────────────────────────────────────────────────────────────

/// One library of an installer: where it goes, where it comes from, its hash.
#[derive(Clone, Debug, PartialEq)]
pub struct InstallerLibrary {
    pub path: String,
    pub url: String,
    pub sha1: Option<String>,
}

/// The libraries a version file or an install profile lists, in order, each
/// once. A library with no address is still listed: it is inside the installer
/// or made by a processor.
pub fn installer_libraries(documents: &[&Value]) -> Vec<InstallerLibrary> {
    let mut seen = std::collections::HashSet::new();
    let mut out = Vec::new();
    for doc in documents {
        for library in doc.get("libraries").and_then(Value::as_array).into_iter().flatten() {
            let artifact = library.get("downloads").and_then(|d| d.get("artifact"));
            let path = artifact
                .and_then(|a| a.get("path"))
                .and_then(Value::as_str)
                .and_then(safe_relative)
                .map(str::to_string)
                .or_else(|| library.get("name").and_then(Value::as_str).and_then(artifact_path));
            let Some(path) = path else { continue };
            if !seen.insert(path.clone()) {
                continue;
            }
            let url = artifact.and_then(|a| a.get("url")).and_then(Value::as_str).unwrap_or("").to_string();
            let sha1 = artifact
                .and_then(|a| a.get("sha1"))
                .and_then(Value::as_str)
                .filter(|h| h.len() == 40 && h.bytes().all(|b| b.is_ascii_hexdigit()))
                .map(|h| h.to_ascii_lowercase());
            out.push(InstallerLibrary { path, url, sha1 });
        }
    }
    out
}

/// Put every library in place: one already there with the right hash is
/// kept, one inside the installer is taken from it, the rest are downloaded
/// and checked. A library with no address that is not inside the installer is
/// left for the processors to make.
async fn place_libraries(
    client: &reqwest::Client,
    installer: &Path,
    libraries_root: &Path,
    libraries: &[InstallerLibrary],
    log: Arc<dyn Fn(&str) + Send + Sync>,
) -> Result<(), String> {
    // Owned, so the downloads below borrow nothing from the list.
    let mut to_download: Vec<(PathBuf, String, Option<String>)> = Vec::new();
    for library in libraries {
        let target = libraries_root.join(&library.path);
        if target.is_file() {
            match &library.sha1 {
                None => continue,
                Some(want) if sha1_of(&target).as_deref() == Some(want.as_str()) => continue,
                Some(_) => {
                    let _ = fs::remove_file(&target);
                }
            }
        }
        if let Some(bytes) = installer_entry(installer, &format!("maven/{}", library.path))? {
            if let Some(want) = &library.sha1 {
                let got: String = Sha1::digest(&bytes).iter().map(|b| format!("{b:02x}")).collect();
                if &got != want {
                    return Err(format!("{} inside the installer does not match its checksum.", library.path));
                }
            }
            write_atomically(&target, &bytes)?;
            continue;
        }
        if library.url.is_empty() {
            continue;
        }
        if !library.url.starts_with("https://") {
            return Err(format!("The installer asks for a library over an insecure address: {}", library.path));
        }
        to_download.push((target, library.url.clone(), library.sha1.clone()));
    }
    if !to_download.is_empty() {
        log(&format!("Downloading {} loader libraries", to_download.len()));
    }
    drop(log);
    use futures_util::stream::{self, StreamExt};
    let results: Vec<Result<(), String>> = stream::iter(to_download)
        .map(|(path, url, sha1): (PathBuf, String, Option<String>)| async move {
            crate::ensure_download_checked(client, &path, &url, sha1.as_deref()).await
        })
        .buffer_unordered(8)
        .collect()
        .await;
    results.into_iter().collect()
}

// ── Processors ──────────────────────────────────────────────────────────────

/// One processor, ready to run: the jar whose main class is run, its class
/// path, its arguments, and the files it must leave behind with their hashes.
#[derive(Debug, Clone, PartialEq)]
pub struct ProcessorRun {
    pub jar: PathBuf,
    pub classpath: Vec<PathBuf>,
    pub args: Vec<String>,
    pub outputs: Vec<(PathBuf, String)>,
}

/// The install profile's data for the client, with what the installer itself
/// adds. A value in brackets is a library, one in quotes is text, anything
/// else is a file inside the installer, which is listed in `extract` (name in
/// the jar, where to put it) for the caller to take out.
pub fn client_data(
    profile: &Value,
    libraries: &Path,
    scratch: &Path,
    minecraft_jar: &Path,
    minecraft: &str,
    root: &Path,
    installer: &Path,
) -> Result<(HashMap<String, String>, Vec<(String, PathBuf)>), String> {
    let mut data = HashMap::new();
    let mut extract = Vec::new();
    for (key, sides) in profile.get("data").and_then(Value::as_object).into_iter().flatten() {
        let Some(value) = sides.get("client").and_then(Value::as_str) else { continue };
        let resolved = if let Some(descriptor) = bracketed(value) {
            text(&library_file(libraries, descriptor)?)
        } else if value.len() >= 2 && value.starts_with('\'') && value.ends_with('\'') {
            value[1..value.len() - 1].to_string()
        } else {
            let inside = value.trim_start_matches('/');
            let relative = safe_relative(inside).ok_or_else(|| format!("The installer names a file Breeze cannot place: {value}"))?;
            let target = scratch.join(relative);
            extract.push((inside.to_string(), target.clone()));
            text(&target)
        };
        data.insert(key.clone(), resolved);
    }
    data.insert("SIDE".into(), "client".into());
    data.insert("MINECRAFT_JAR".into(), text(minecraft_jar));
    data.insert("MINECRAFT_VERSION".into(), minecraft.to_string());
    data.insert("ROOT".into(), text(root));
    data.insert("INSTALLER".into(), text(installer));
    data.insert("LIBRARY_DIR".into(), text(libraries));
    Ok((data, extract))
}

/// The client's processors in order, with every name resolved.
pub fn client_processors(profile: &Value, libraries: &Path, data: &HashMap<String, String>) -> Result<Vec<ProcessorRun>, String> {
    let mut runs = Vec::new();
    for processor in profile.get("processors").and_then(Value::as_array).into_iter().flatten() {
        if let Some(sides) = processor.get("sides").and_then(Value::as_array) {
            if !sides.iter().any(|side| side.as_str() == Some("client")) {
                continue;
            }
        }
        let jar_name = processor.get("jar").and_then(Value::as_str).ok_or("A processor in the installer has no jar.")?;
        let jar = library_file(libraries, jar_name)?;
        let mut classpath = vec![jar.clone()];
        for entry in processor.get("classpath").and_then(Value::as_array).into_iter().flatten() {
            classpath.push(library_file(libraries, entry.as_str().unwrap_or_default())?);
        }
        let mut args = Vec::new();
        for arg in processor.get("args").and_then(Value::as_array).into_iter().flatten() {
            let arg = arg.as_str().unwrap_or_default();
            args.push(match bracketed(arg) {
                Some(descriptor) => text(&library_file(libraries, descriptor)?),
                None => replace_tokens(data, arg)?,
            });
        }
        let mut outputs = Vec::new();
        for (key, value) in processor.get("outputs").and_then(Value::as_object).into_iter().flatten() {
            let file = match bracketed(key) {
                Some(descriptor) => library_file(libraries, descriptor)?,
                None => PathBuf::from(replace_tokens(data, key)?),
            };
            let hash = replace_tokens(data, value.as_str().unwrap_or_default())?;
            outputs.push((file, hash.to_ascii_lowercase()));
        }
        runs.push(ProcessorRun { jar, classpath, args, outputs });
    }
    Ok(runs)
}

/// Whether every file a processor makes is already there with its hash, so it
/// need not run again. A processor that names no outputs always runs.
fn outputs_in_place(run: &ProcessorRun) -> bool {
    !run.outputs.is_empty()
        && run
            .outputs
            .iter()
            .all(|(file, hash)| file.is_file() && sha1_of(file).as_deref() == Some(hash.as_str()))
}

/// `Main-Class` from a jar's manifest (a long value continues on lines that
/// start with a space).
pub fn main_class_of(jar: &Path) -> Result<String, String> {
    let file = fs::File::open(jar).map_err(|e| format!("Could not open {}: {e}", jar.display()))?;
    let mut archive = zip::ZipArchive::new(file).map_err(|e| format!("{} is not a jar: {e}", jar.display()))?;
    let mut manifest = String::new();
    archive
        .by_name("META-INF/MANIFEST.MF")
        .map_err(|_| format!("{} has no manifest.", jar.display()))?
        .take(1024 * 1024)
        .read_to_string(&mut manifest)
        .map_err(|e| format!("Could not read the manifest of {}: {e}", jar.display()))?;
    main_class_in(&manifest).ok_or_else(|| format!("{} has no main class.", jar.display()))
}

fn main_class_in(manifest: &str) -> Option<String> {
    let unfolded = manifest.replace("\r\n", "\n").replace("\n ", "");
    unfolded
        .lines()
        .find_map(|line| line.strip_prefix("Main-Class:"))
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty() && value.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '_' | '$')))
}

/// Run the processors that still have work to do. Each is a Java program from
/// the loader's own libraries (placed and checked just before), run with the
/// game's Java; what it must produce is checked against the installer's
/// hashes afterwards.
fn run_processors(java: &Path, runs: &[ProcessorRun], work_dir: &Path, log: &(dyn Fn(&str) + Send + Sync)) -> Result<(), String> {
    let separator = if cfg!(target_os = "windows") { ";" } else { ":" };
    let total = runs.len();
    for (index, run) in runs.iter().enumerate() {
        if outputs_in_place(run) {
            continue;
        }
        for (file, _) in &run.outputs {
            let _ = fs::remove_file(file);
            if let Some(parent) = file.parent() {
                let _ = fs::create_dir_all(parent);
            }
        }
        if let Some(missing) = run.classpath.iter().find(|entry| !entry.is_file()) {
            return Err(format!("A file the loader's installer needs is missing: {}", missing.display()));
        }
        let main_class = main_class_of(&run.jar)?;
        log(&format!("Preparing the loader, step {} of {total}", index + 1));
        let classpath = run.classpath.iter().map(|p| text(p)).collect::<Vec<_>>().join(separator);
        let output = crate::windowed_command(java)
            .current_dir(work_dir)
            .arg("-cp")
            .arg(&classpath)
            .arg(&main_class)
            .args(&run.args)
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .output()
            .map_err(|e| format!("Could not start Java for the loader's installer: {e}"))?;
        if !output.status.success() {
            let mut said = String::from_utf8_lossy(&output.stderr).to_string();
            if said.trim().is_empty() {
                said = String::from_utf8_lossy(&output.stdout).to_string();
            }
            let tail: Vec<&str> = said.lines().rev().filter(|l| !l.trim().is_empty()).take(6).collect();
            let tail: Vec<&str> = tail.into_iter().rev().collect();
            return Err(format!(
                "The loader's installer stopped at step {} of {total} ({main_class}): {}",
                index + 1,
                crate::sanitize_log_line(&tail.join(" | "))
            ));
        }
        for (file, hash) in &run.outputs {
            match sha1_of(file) {
                Some(got) if &got == hash => {}
                Some(_) => {
                    let _ = fs::remove_file(file);
                    return Err(format!("Step {} of the loader's installer made a file that does not match its checksum: {}", index + 1, file.display()));
                }
                None => return Err(format!("Step {} of the loader's installer did not make {}", index + 1, file.display())),
            }
        }
    }
    Ok(())
}

// ── Forge and NeoForge ──────────────────────────────────────────────────────

/// What a Forge or NeoForge install leaves for the launch.
pub struct ForgeInstall {
    /// The loader's version file, to merge onto Minecraft's own.
    pub profile: Value,
    /// Its id, which is also the name of the jar on the class path.
    pub version_name: String,
    pub build: String,
    /// A copy of the game's jar under the loader's name. The loader's launch
    /// arguments ignore the game's jar by that name, as Mojang's launcher has it.
    pub client_jar: PathBuf,
}

fn marker_path(home: &Path, id: &str) -> PathBuf {
    home.join("versions").join(id).join("breeze-loader.json")
}

/// Whether an earlier install is still whole: its version file, its jar, and
/// every library that only the installer could have put there.
fn install_is_whole(home: &Path, id: &str) -> Option<Value> {
    let dir = home.join("versions").join(id);
    let marker = crate::read_json_value(&marker_path(home, id)).ok()?;
    let profile = crate::read_json_value(&dir.join(format!("{id}.json"))).ok()?;
    if !dir.join(format!("{id}.jar")).is_file() {
        return None;
    }
    let libraries = home.join("libraries");
    let made_here = marker.get("made").and_then(Value::as_array)?;
    made_here
        .iter()
        .filter_map(Value::as_str)
        .all(|path| safe_relative(path).is_some_and(|p| libraries.join(p).is_file()))
        .then_some(profile)
}

/// Install Forge or NeoForge for a version, or find the install already made.
///
/// `minecraft_jar` is the game's own client jar (already downloaded from
/// Mojang and checked), `java` the Java the game will run on.
pub async fn ensure_forge_like(
    client: &reqwest::Client,
    home: &Path,
    loader: Loader,
    minecraft: &str,
    wanted: Option<&str>,
    minecraft_jar: &Path,
    java: &Path,
    log: Arc<dyn Fn(&str) + Send + Sync>,
) -> Result<ForgeInstall, String> {
    if !matches!(loader, Loader::Forge | Loader::NeoForge) {
        return Err("Only Forge and NeoForge are installed this way.".into());
    }
    if !safe_version(minecraft) {
        return Err("That is not a Minecraft version.".into());
    }
    let build = match wanted.map(str::trim).filter(|v| !v.is_empty()) {
        Some(v) if safe_version(v) => v.to_string(),
        Some(_) => return Err(format!("That is not a {} version.", loader.label())),
        None => match installed_build(home, loader, minecraft) {
            Some(v) => v,
            None => latest_build(client, loader, minecraft)
                .await?
                .ok_or_else(|| format!("{} has no build for Minecraft {minecraft}.", loader.label()))?,
        },
    };
    let url = installer_url(loader, minecraft, &build).ok_or("No installer for this loader.")?;
    let installer = home
        .join("loaders")
        .join(loader.id())
        .join(format!("{minecraft}-{build}-installer.jar"));

    // An install made before is used as it is, with no network at all.
    if installer.is_file() {
        if let Ok(profile) = installer_json(&installer, "install_profile.json") {
            if let Some(id) = profile.get("version").and_then(Value::as_str).filter(|id| crate::instance_id(id).is_ok()) {
                if let Some(version_file) = install_is_whole(home, id) {
                    remember_build(home, loader, minecraft, &build);
                    return Ok(ForgeInstall {
                        profile: version_file,
                        version_name: id.to_string(),
                        build,
                        client_jar: home.join("versions").join(id).join(format!("{id}.jar")),
                    });
                }
            }
        }
    }

    log(&format!("Downloading {} {build}", loader.label()));
    // The repository publishes the installer's SHA-1 beside it.
    let published = match client.get(format!("{url}.sha1")).send().await {
        Ok(response) if response.status().is_success() => response
            .text()
            .await
            .ok()
            .map(|t| t.trim().to_ascii_lowercase())
            .filter(|h| h.len() == 40 && h.bytes().all(|b| b.is_ascii_hexdigit())),
        _ => None,
    };
    crate::ensure_download_checked(client, &installer, &url, published.as_deref()).await?;
    if fs::metadata(&installer).map(|m| m.len()).unwrap_or(0) > MAX_INSTALLER_BYTES {
        let _ = fs::remove_file(&installer);
        return Err(format!("What {} published as its installer is too large to be one.", loader.label()));
    }

    let profile = installer_json(&installer, "install_profile.json")?;
    if profile.get("processors").and_then(Value::as_array).is_none() {
        return Err(format!(
            "{} {build} uses an installer from before Minecraft 1.13, which Breeze does not run.",
            loader.label()
        ));
    }
    if profile.get("minecraft").and_then(Value::as_str) != Some(minecraft) {
        return Err(format!("{} {build} is not for Minecraft {minecraft}.", loader.label()));
    }
    let id = profile
        .get("version")
        .and_then(Value::as_str)
        .filter(|id| crate::instance_id(id).is_ok())
        .ok_or("The installer does not name its version.")?
        .to_string();
    let json_name = profile.get("json").and_then(Value::as_str).unwrap_or("/version.json").trim_start_matches('/').to_string();
    let version_file = installer_json(&installer, &json_name)?;
    if version_file.get("mainClass").and_then(Value::as_str).is_none() {
        return Err("The installer's version file is not a launch profile.".into());
    }

    let libraries_root = home.join("libraries");
    let libraries = installer_libraries(&[&version_file, &profile]);
    place_libraries(client, &installer, &libraries_root, &libraries, Arc::clone(&log)).await?;

    // The processors are plain blocking work (a minute or so of Java), so they
    // run off the async threads.
    let scratch = home.join("loaders").join(loader.id()).join(format!("work-{minecraft}-{build}"));
    let (data, extract) = client_data(&profile, &libraries_root, &scratch, minecraft_jar, minecraft, home, &installer)?;
    let runs = client_processors(&profile, &libraries_root, &data)?;
    let job = {
        let (installer, scratch, java, home, log) = (installer.clone(), scratch.clone(), java.to_path_buf(), home.to_path_buf(), Arc::clone(&log));
        move || -> Result<(), String> {
            let _ = fs::remove_dir_all(&scratch);
            for (inside, target) in &extract {
                let bytes = installer_entry(&installer, inside)?.ok_or_else(|| format!("The installer has no {inside}."))?;
                write_atomically(target, &bytes)?;
            }
            let result = run_processors(&java, &runs, &home, log.as_ref());
            let _ = fs::remove_dir_all(&scratch);
            result
        }
    };
    tauri::async_runtime::spawn_blocking(job)
        .await
        .map_err(|e| format!("The loader's installer could not be run: {e}"))??;

    // Everything with no address must exist now: it was in the installer or a
    // processor made it.
    let made: Vec<&InstallerLibrary> = libraries.iter().filter(|l| l.url.is_empty()).collect();
    if let Some(missing) = made.iter().find(|l| !libraries_root.join(&l.path).is_file()) {
        return Err(format!("{} {build} did not finish installing: {} is missing.", loader.label(), missing.path));
    }

    let version_dir = home.join("versions").join(&id);
    crate::write_json_value(&version_dir.join(format!("{id}.json")), &version_file)?;
    let client_jar = version_dir.join(format!("{id}.jar"));
    let same_size = |a: &Path, b: &Path| matches!((fs::metadata(a), fs::metadata(b)), (Ok(x), Ok(y)) if x.len() == y.len());
    if !same_size(&client_jar, minecraft_jar) {
        let _ = fs::remove_file(&client_jar);
        // A hard link costs no space; a copy where links are not possible.
        if fs::hard_link(minecraft_jar, &client_jar).is_err() {
            fs::copy(minecraft_jar, &client_jar).map_err(|e| format!("Could not place the game's jar for {}: {e}", loader.label()))?;
        }
    }
    crate::write_json_value(
        &marker_path(home, &id),
        &serde_json::json!({
            "loader": loader.id(),
            "minecraft": minecraft,
            "build": build,
            "made": made.iter().map(|l| l.path.clone()).collect::<Vec<_>>(),
        }),
    )?;
    remember_build(home, loader, minecraft, &build);
    log(&format!("{} {build} is ready", loader.label()));
    Ok(ForgeInstall { profile: version_file, version_name: id, build, client_jar })
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::io::Write;

    fn temp(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("breeze-loaders-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_fabric_instance_keeps_the_bare_version_and_the_others_say_their_loader() {
        assert_eq!(instance_id("1.21.4", Loader::Fabric), "1.21.4");
        assert_eq!(instance_id("1.20.1", Loader::Forge), "1.20.1-forge");
        assert_eq!(instance_id("1.21.1", Loader::NeoForge), "1.21.1-neoforge");
        assert_eq!(instance_id("26.1", Loader::Quilt), "26.1-quilt");
        assert_eq!(instance_id("1.21.4", Loader::Vanilla), "1.21.4-vanilla");
        for loader in Loader::ALL {
            for version in ["1.21.4", "26.1", "1.21-pre1", "26.1-snapshot-2", "24w14potato"] {
                assert_eq!(split_instance_id(&instance_id(version, loader)), (version, loader), "{version} {loader:?}");
            }
            assert_eq!(Loader::parse(loader.id()), Some(loader));
            assert_eq!(Loader::parse(&loader.label().to_uppercase()), Some(loader));
        }
        // NeoForge is not read as Forge, and a bare loader word is not an instance of nothing.
        assert_eq!(split_instance_id("1.21.1-neoforge"), ("1.21.1", Loader::NeoForge));
        assert_eq!(split_instance_id("-forge"), ("-forge", Loader::Fabric));
        assert_eq!(Loader::parse("liteloader"), None);
        // Every such id passes the launcher's own check for instance names.
        assert!(crate::instance_id(&instance_id("26.1-snapshot-2", Loader::NeoForge)).is_ok());
    }

    #[test]
    fn forge_takes_the_recommended_build_and_the_latest_where_there_is_none() {
        let promos = json!({ "homepage": "x", "promos": {
            "1.20.1-latest": "47.4.10", "1.20.1-recommended": "47.4.0",
            "1.21.4-latest": "54.1.6",
            "1.19.2-recommended": "../evil",
        } });
        assert_eq!(pick_forge(&promos, "1.20.1").as_deref(), Some("47.4.0"));
        assert_eq!(pick_forge(&promos, "1.21.4").as_deref(), Some("54.1.6"));
        assert_eq!(pick_forge(&promos, "1.21.11"), None);
        assert_eq!(pick_forge(&promos, "1.19.2"), None, "a build that is not a version is refused");
        assert_eq!(pick_forge(&json!({}), "1.20.1"), None);
    }

    #[test]
    fn neoforge_builds_are_matched_to_their_minecraft_version() {
        let versions: Vec<String> = [
            "20.2.88", "20.4.237", "20.4.80-beta", "21.0.167", "21.1.9", "21.1.77", "21.1.210", "21.1.211-beta",
            "21.4.50-beta", "21.10.3", "21.11.0-beta", "26.1.0.4-beta", "26.1.0.12", "26.1.1.2-beta", "26.2.5",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        assert_eq!(pick_neoforge(&versions, "1.21.1").as_deref(), Some("21.1.210"), "newest by number, betas last");
        assert_eq!(pick_neoforge(&versions, "1.21").as_deref(), Some("21.0.167"));
        assert_eq!(pick_neoforge(&versions, "1.20.4").as_deref(), Some("20.4.237"));
        assert_eq!(pick_neoforge(&versions, "1.21.4").as_deref(), Some("21.4.50-beta"), "a beta where there is nothing else");
        assert_eq!(pick_neoforge(&versions, "1.21.10").as_deref(), Some("21.10.3"), "21.1 is not 21.10");
        assert_eq!(pick_neoforge(&versions, "1.21.11").as_deref(), Some("21.11.0-beta"));
        assert_eq!(pick_neoforge(&versions, "1.20.1"), None);
        // Year numbers: the whole Minecraft version first, or three parts.
        assert_eq!(pick_neoforge(&versions, "26.1").as_deref(), Some("26.1.0.12"));
        assert_eq!(pick_neoforge(&versions, "26.1.1").as_deref(), Some("26.1.1.2-beta"));
        assert_eq!(pick_neoforge(&versions, "26.2").as_deref(), Some("26.2.5"));
        assert_eq!(pick_neoforge(&versions, "26.3"), None);

        let legacy: Vec<String> = ["1.20.1-47.1.3", "1.20.1-47.1.106", "1.20.1-47.1.99", "1.20.2-48.0.1"].iter().map(|s| s.to_string()).collect();
        assert_eq!(pick_neoforge_legacy(&legacy, "1.20.1").as_deref(), Some("47.1.106"));
        assert_eq!(pick_neoforge_legacy(&legacy, "1.19.2"), None);
    }

    #[test]
    fn quilt_takes_the_first_loader_that_is_not_a_beta() {
        let list = json!([
            { "loader": { "version": "0.30.0-beta.3" } },
            { "loader": { "version": "0.29.1" } },
            { "loader": { "version": "0.29.0" } },
        ]);
        assert_eq!(pick_quilt(&list).as_deref(), Some("0.29.1"));
        assert_eq!(pick_quilt(&json!([{ "loader": { "version": "0.30.0-beta.3" } }])).as_deref(), Some("0.30.0-beta.3"));
        assert_eq!(pick_quilt(&json!([])), None);
    }

    #[test]
    fn installer_addresses_follow_each_repository() {
        assert_eq!(
            installer_url(Loader::Forge, "1.20.1", "47.4.0").unwrap(),
            "https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-installer.jar"
        );
        assert_eq!(
            installer_url(Loader::NeoForge, "1.21.1", "21.1.210").unwrap(),
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.210/neoforge-21.1.210-installer.jar"
        );
        assert_eq!(
            installer_url(Loader::NeoForge, "1.20.1", "47.1.106").unwrap(),
            "https://maven.neoforged.net/releases/net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar"
        );
        assert_eq!(installer_url(Loader::Quilt, "1.21.1", "0.29.1"), None);
    }

    #[test]
    fn maven_names_become_paths_and_bad_ones_are_refused() {
        assert_eq!(artifact_path("net.minecraftforge:forge:1.20.1-47.4.0:client").unwrap(), "net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-client.jar");
        assert_eq!(artifact_path("de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412@zip").unwrap(), "de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip");
        assert_eq!(artifact_path("net.minecraft:client:1.20.1-20230612.114412:mappings@txt").unwrap(), "net/minecraft/client/1.20.1-20230612.114412/client-1.20.1-20230612.114412-mappings.txt");
        assert_eq!(artifact_path("org.ow2.asm:asm:9.5").unwrap(), "org/ow2/asm/asm/9.5/asm-9.5.jar");
        for bad in ["", "a:b", "a:b:c:d:e", "a:../b:1", "a:b:..", "a:b:1@../x", "a/b:c:1", "a:b:1:c@e/f", ".a:b:1"] {
            assert_eq!(artifact_path(bad), None, "{bad}");
        }
        assert_eq!(safe_relative("a/b/c.jar"), Some("a/b/c.jar"));
        for bad in ["/a/b", "a/../b", "a//b", "C:/x", "a\\b", ""] {
            assert_eq!(safe_relative(bad), None, "{bad}");
        }
    }

    #[test]
    fn tokens_are_replaced_the_way_forge_does_it() {
        let data: HashMap<String, String> = [("SIDE", "client"), ("MC_SLIM", "/lib/slim.jar")].iter().map(|(k, v)| (k.to_string(), v.to_string())).collect();
        assert_eq!(replace_tokens(&data, "{MC_SLIM}").unwrap(), "/lib/slim.jar");
        assert_eq!(replace_tokens(&data, "--side={SIDE},x").unwrap(), "--side=client,x");
        assert_eq!(replace_tokens(&data, "'quoted {SIDE}'").unwrap(), "quoted {SIDE}");
        assert_eq!(replace_tokens(&data, "a\\{b").unwrap(), "a{b");
        assert_eq!(replace_tokens(&data, "plain").unwrap(), "plain");
        assert!(replace_tokens(&data, "{MISSING}").unwrap_err().contains("MISSING"));
        assert!(replace_tokens(&data, "{SIDE").is_err());
        assert!(replace_tokens(&data, "end\\").is_err());
    }

    fn sample_profile() -> Value {
        json!({
            "spec": 1, "version": "1.20.1-forge-47.4.0", "minecraft": "1.20.1", "json": "/version.json",
            "data": {
                "MAPPINGS": { "client": "[de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412:mappings@txt]", "server": "[x:y:1]" },
                "BINPATCH": { "client": "/data/client.lzma", "server": "/data/server.lzma" },
                "MC_SLIM_SHA": { "client": "'ABCDEF0123456789abcdef0123456789abcdef01'", "server": "'00'" },
                "PATCHED": { "client": "[net.minecraftforge:forge:1.20.1-47.4.0:client]", "server": "[x:y:1]" },
                "SERVER_ONLY": { "server": "'x'" }
            },
            "processors": [
                { "sides": ["server"], "jar": "net.minecraftforge:installertools:1.4.1", "classpath": [], "args": ["--task", "EXTRACT_FILES"] },
                { "jar": "net.minecraftforge:installertools:1.4.1", "classpath": ["net.sf.jopt-simple:jopt-simple:5.0.4"],
                  "args": ["--task", "MCP_DATA", "--input", "[de.oceanlabs.mcp:mcp_config:1.20.1-20230612.114412@zip]", "--output", "{MAPPINGS}", "--key", "mappings"] },
                { "sides": ["client"], "jar": "net.minecraftforge:binarypatcher:1.1.1", "classpath": [],
                  "args": ["--clean", "{MINECRAFT_JAR}", "--output", "{PATCHED}", "--apply", "{BINPATCH}", "--side", "{SIDE}", "--version", "{MINECRAFT_VERSION}"],
                  "outputs": { "{PATCHED}": "{MC_SLIM_SHA}" } }
            ],
            "libraries": [
                { "name": "net.minecraftforge:installertools:1.4.1", "downloads": { "artifact": { "path": "net/minecraftforge/installertools/1.4.1/installertools-1.4.1.jar", "url": "https://maven.minecraftforge.net/net/minecraftforge/installertools/1.4.1/installertools-1.4.1.jar", "sha1": "792cc8d4cba4bd5b0a3e0a4d5e3a4ec0a2a1b3c4", "size": 1 } } },
                { "name": "net.minecraftforge:forge:1.20.1-47.4.0:universal", "downloads": { "artifact": { "path": "net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-universal.jar", "url": "", "sha1": "NOT-A-HASH", "size": 1 } } }
            ]
        })
    }

    #[test]
    fn the_client_gets_its_own_data_and_only_its_own_processors() {
        let libs = Path::new("/home/libs");
        let profile = sample_profile();
        let (data, extract) = client_data(&profile, libs, Path::new("/home/scratch"), Path::new("/home/versions/1.20.1/1.20.1.jar"), "1.20.1", Path::new("/home"), Path::new("/home/installer.jar")).unwrap();
        assert_eq!(data["MAPPINGS"], text(&libs.join("de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412-mappings.txt")));
        assert_eq!(data["BINPATCH"], text(Path::new("/home/scratch/data/client.lzma")));
        assert_eq!(extract, vec![("data/client.lzma".to_string(), PathBuf::from("/home/scratch/data/client.lzma"))]);
        assert_eq!(data["MC_SLIM_SHA"], "ABCDEF0123456789abcdef0123456789abcdef01");
        assert_eq!(data["SIDE"], "client");
        assert_eq!(data["MINECRAFT_VERSION"], "1.20.1");
        assert_eq!(data["ROOT"], "/home");
        assert_eq!(data["LIBRARY_DIR"], "/home/libs");
        assert_eq!(data["INSTALLER"], "/home/installer.jar");
        assert!(!data.contains_key("SERVER_ONLY"));

        let runs = client_processors(&profile, libs, &data).unwrap();
        assert_eq!(runs.len(), 2, "the server's processor is left out");
        assert_eq!(runs[0].classpath, vec![
            libs.join("net/minecraftforge/installertools/1.4.1/installertools-1.4.1.jar"),
            libs.join("net/sf/jopt-simple/jopt-simple/5.0.4/jopt-simple-5.0.4.jar"),
        ]);
        assert_eq!(runs[0].args[3], text(&libs.join("de/oceanlabs/mcp/mcp_config/1.20.1-20230612.114412/mcp_config-1.20.1-20230612.114412.zip")));
        assert_eq!(runs[0].args[5], data["MAPPINGS"]);
        assert!(runs[0].outputs.is_empty());
        let patched = libs.join("net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-client.jar");
        assert_eq!(runs[1].args, vec!["--clean", "/home/versions/1.20.1/1.20.1.jar", "--output", &text(&patched), "--apply", "/home/scratch/data/client.lzma", "--side", "client", "--version", "1.20.1"]);
        assert_eq!(runs[1].outputs, vec![(patched, "abcdef0123456789abcdef0123456789abcdef01".to_string())]);

        // A file in the installer that would land outside the scratch folder is refused.
        let mut evil = sample_profile();
        evil["data"]["BINPATCH"]["client"] = json!("/../../etc/passwd");
        assert!(client_data(&evil, libs, Path::new("/s"), Path::new("/j"), "1.20.1", Path::new("/h"), Path::new("/i")).is_err());
    }

    #[test]
    fn libraries_are_listed_once_with_their_hash_only_when_it_is_one() {
        let profile = sample_profile();
        let version = json!({ "libraries": [
            { "name": "net.minecraftforge:installertools:1.4.1", "downloads": { "artifact": { "path": "net/minecraftforge/installertools/1.4.1/installertools-1.4.1.jar", "url": "https://elsewhere/x.jar" } } },
            { "name": "org.ow2.asm:asm:9.5" },
            { "name": "evil:lib:1", "downloads": { "artifact": { "path": "../../outside.jar", "url": "https://x/y.jar" } } }
        ] });
        let libraries = installer_libraries(&[&version, &profile]);
        let paths: Vec<&str> = libraries.iter().map(|l| l.path.as_str()).collect();
        assert_eq!(paths, vec![
            "net/minecraftforge/installertools/1.4.1/installertools-1.4.1.jar",
            "org/ow2/asm/asm/9.5/asm-9.5.jar",
            "evil/lib/1/lib-1.jar",
            "net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-universal.jar",
        ], "a path that would leave the folder falls back to the library's name");
        assert_eq!(libraries[0].url, "https://elsewhere/x.jar", "the first listing wins");
        assert_eq!(libraries[3].url, "");
        assert_eq!(libraries[3].sha1, None);
    }

    fn jar_with(path: &Path, entries: &[(&str, &[u8])]) {
        let file = fs::File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        for (name, bytes) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(bytes).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn a_jar_says_its_main_class_even_on_a_folded_line() {
        let dir = temp("manifest");
        let jar = dir.join("tool.jar");
        jar_with(&jar, &[("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\r\nMain-Class: net.minecraftforge.installertools.Conso\r\n leTool\r\nX: y\r\n")]);
        assert_eq!(main_class_of(&jar).unwrap(), "net.minecraftforge.installertools.ConsoleTool");
        assert_eq!(main_class_in("Main-Class: a.b.C\n").as_deref(), Some("a.b.C"));
        assert_eq!(main_class_in("Main-Class: a b; rm -rf\n"), None, "only a class name is run");
        assert_eq!(main_class_in("Manifest-Version: 1.0\n"), None);
        let none = dir.join("none.jar");
        jar_with(&none, &[("x.txt", b"x")]);
        assert!(main_class_of(&none).is_err());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_processor_whose_files_are_already_right_does_not_run_again() {
        let dir = temp("outputs");
        let made = dir.join("made.jar");
        fs::write(&made, b"patched").unwrap();
        let hash = sha1_of(&made).unwrap();
        let mut run = ProcessorRun { jar: dir.join("tool.jar"), classpath: vec![], args: vec![], outputs: vec![(made.clone(), hash)] };
        assert!(outputs_in_place(&run));
        run.outputs[0].1 = "0".repeat(40);
        assert!(!outputs_in_place(&run));
        run.outputs.clear();
        assert!(!outputs_in_place(&run), "one that names nothing always runs");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn an_install_is_whole_only_with_everything_its_installer_made() {
        let home = temp("whole");
        let id = "1.20.1-forge-47.4.0";
        let dir = home.join("versions").join(id);
        fs::create_dir_all(&dir).unwrap();
        assert!(install_is_whole(&home, id).is_none());
        crate::write_json_value(&dir.join(format!("{id}.json")), &json!({ "id": id, "mainClass": "x" })).unwrap();
        fs::write(dir.join(format!("{id}.jar")), b"jar").unwrap();
        let made = "net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-client.jar";
        crate::write_json_value(&marker_path(&home, id), &json!({ "loader": "forge", "minecraft": "1.20.1", "build": "47.4.0", "made": [made] })).unwrap();
        assert!(install_is_whole(&home, id).is_none(), "the patched game is missing");
        let lib = home.join("libraries").join(made);
        fs::create_dir_all(lib.parent().unwrap()).unwrap();
        fs::write(&lib, b"patched").unwrap();
        assert_eq!(install_is_whole(&home, id).unwrap()["id"], id);
        fs::remove_file(dir.join(format!("{id}.jar"))).unwrap();
        assert!(install_is_whole(&home, id).is_none());

        // The build in use is remembered per loader and version, and can be forgotten.
        assert_eq!(installed_build(&home, Loader::Forge, "1.20.1"), None);
        remember_build(&home, Loader::Forge, "1.20.1", "47.4.0");
        assert_eq!(installed_build(&home, Loader::Forge, "1.20.1").as_deref(), Some("47.4.0"));
        assert_eq!(installed_build(&home, Loader::NeoForge, "1.20.1"), None);
        forget_build(&home, Loader::Forge, "1.20.1");
        assert_eq!(installed_build(&home, Loader::Forge, "1.20.1"), None);
        let _ = fs::remove_dir_all(home);
    }

    #[test]
    fn a_library_inside_the_installer_is_taken_from_it_and_checked() {
        let home = temp("maven");
        let installer = home.join("installer.jar");
        let inside = "net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-universal.jar";
        jar_with(&installer, &[(&format!("maven/{inside}"), b"universal"), ("install_profile.json", b"{}")]);
        let good: String = Sha1::digest(b"universal").iter().map(|b| format!("{b:02x}")).collect();
        let libs = home.join("libraries");
        let client = reqwest::Client::new();
        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
        let quiet: Arc<dyn Fn(&str) + Send + Sync> = Arc::new(|_: &str| {});

        let listed = vec![
            InstallerLibrary { path: inside.into(), url: String::new(), sha1: Some(good.clone()) },
            // Made later by a processor: no address, not in the installer. Left alone.
            InstallerLibrary { path: "net/minecraftforge/forge/1.20.1-47.4.0/forge-1.20.1-47.4.0-client.jar".into(), url: String::new(), sha1: None },
        ];
        rt.block_on(place_libraries(&client, &installer, &libs, &listed, Arc::clone(&quiet))).unwrap();
        assert_eq!(fs::read(libs.join(inside)).unwrap(), b"universal");
        assert!(!libs.join(&listed[1].path).exists());

        // A wrong copy already on disk is replaced from the installer.
        fs::write(libs.join(inside), b"tampered").unwrap();
        rt.block_on(place_libraries(&client, &installer, &libs, &listed, Arc::clone(&quiet))).unwrap();
        assert_eq!(fs::read(libs.join(inside)).unwrap(), b"universal");

        // A file in the installer that does not match its checksum stops the install.
        let bad = vec![InstallerLibrary { path: inside.into(), url: String::new(), sha1: Some("0".repeat(40)) }];
        fs::remove_file(libs.join(inside)).unwrap();
        assert!(rt.block_on(place_libraries(&client, &installer, &libs, &bad, Arc::clone(&quiet))).unwrap_err().contains("checksum"));
        // So does a library asked for over plain http.
        let plain = vec![InstallerLibrary { path: "a/b/1/b-1.jar".into(), url: "http://example.com/b-1.jar".into(), sha1: None }];
        assert!(rt.block_on(place_libraries(&client, &installer, &libs, &plain, Arc::clone(&quiet))).unwrap_err().contains("insecure"));
        let _ = fs::remove_dir_all(home);
    }
}
