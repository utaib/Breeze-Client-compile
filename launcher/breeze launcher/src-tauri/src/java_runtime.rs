//! Finding and installing the Java runtime a Minecraft version needs.
//!
//! Every version JSON names the runtime it was built for
//! (`javaVersion.component`, e.g. `java-runtime-delta`, and
//! `javaVersion.majorVersion`, e.g. 21). Mojang publishes those runtimes for
//! each platform in one manifest, file by file, with a SHA-1 per file, an
//! `executable` flag, and symlinks listed as `link` entries. Installing from
//! that manifest is what the vanilla launcher does, and it is the only source
//! that works the same way on Windows, macOS (Intel and Apple silicon) and
//! Linux without an archive format per OS.
//!
//! Before this module Breeze downloaded a Temurin 21 zip on Windows only. On
//! macOS and Linux it spawned whatever `java` was on PATH, which from a Dock or
//! desktop launch is usually Apple's stub or nothing at all, and every version
//! ran on 21 even though 26.1 needs 25.

use serde::{Deserialize, Serialize};
use serde_json::Value;
use sha1::{Digest, Sha1};
use std::{
    fs,
    path::{Path, PathBuf},
    process::Stdio,
    time::{Duration, Instant},
};

/// Mojang's index of every runtime component for every platform.
pub const RUNTIME_INDEX_URL: &str =
    "https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json";

/// Marker written into an installed runtime once every file is in place.
const MARKER_FILE: &str = "breeze-runtime.json";

/// The runtime a version asks for.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct JavaRequirement {
    pub major: u32,
    pub component: String,
}

impl JavaRequirement {
    /// What Breeze installs when no version has been chosen yet (Settings).
    pub fn default_modern() -> Self {
        Self { major: 21, component: "java-runtime-delta".into() }
    }
}

/// Where a usable Java came from, reported to the UI.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum JavaSource {
    /// Installed by Breeze from Mojang's manifest.
    Managed,
    /// The Temurin runtime older Windows builds put in `~/.breezeclient/jre`.
    Bundled,
    /// `JAVA_HOME`.
    Env,
    /// Found on PATH, or through `/usr/libexec/java_home` on macOS.
    System,
}

impl JavaSource {
    pub fn as_str(self) -> &'static str {
        match self {
            JavaSource::Managed => "managed",
            JavaSource::Bundled => "bundled",
            JavaSource::Env => "env",
            JavaSource::System => "system",
        }
    }
}

#[derive(Debug, Clone)]
pub struct JavaInstall {
    pub java_path: PathBuf,
    pub java_home: PathBuf,
    pub major: u32,
    pub source: JavaSource,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct RuntimeMarker {
    component: String,
    major: u32,
    version: String,
    platform: String,
    /// The java home relative to the runtime folder. On macOS this is
    /// `jre.bundle/Contents/Home`.
    java_home: String,
}

pub fn java_binary_name() -> &'static str {
    if cfg!(target_os = "windows") { "java.exe" } else { "java" }
}

/// Read the runtime a version JSON declares.
///
/// Versions from before `javaVersion` existed (1.16.5 and older) were all built
/// for Java 8, which is also what the vanilla launcher assumes for them.
pub fn requirement_from_version_json(version: &Value) -> JavaRequirement {
    let java = version.get("javaVersion");
    let major = java
        .and_then(|j| j.get("majorVersion"))
        .and_then(Value::as_u64)
        .map(|m| m as u32)
        .unwrap_or(8);
    let component = java
        .and_then(|j| j.get("component"))
        .and_then(Value::as_str)
        .filter(|c| is_safe_component(c))
        .map(str::to_string)
        .unwrap_or_else(|| component_for_major(major).to_string());
    JavaRequirement { major, component }
}

/// Mojang's component name for a major version, for JSONs that omit it.
pub fn component_for_major(major: u32) -> &'static str {
    match major {
        0..=8 => "jre-legacy",
        16 => "java-runtime-alpha",
        17..=20 => "java-runtime-gamma",
        21..=24 => "java-runtime-delta",
        _ => "java-runtime-epsilon",
    }
}

/// A component name is used as a folder name, so it must not be able to name a path.
fn is_safe_component(component: &str) -> bool {
    !component.is_empty()
        && component.len() <= 64
        && component.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
}

/// Whether an installed major version can run a version that asks for `required`.
///
/// Exact matches always work. Versions built for 16 or 17 also run on 21, which
/// is what Breeze has always given them on Windows, so an existing 21 is reused
/// rather than downloading a second runtime. Java 8 versions and anything newer
/// than 21 need their own major: 8-era LWJGL does not start on modern Java, and
/// 26.1 passes JVM options that 21 rejects.
pub fn java_satisfies(installed: u32, required: u32) -> bool {
    installed == required || ((16..=20).contains(&required) && installed == 21)
}

/// Mojang's platform key for this build, or None where Mojang ships no runtimes.
pub fn mojang_platform_key() -> Option<&'static str> {
    if cfg!(target_os = "windows") {
        if cfg!(target_arch = "aarch64") {
            Some("windows-arm64")
        } else if cfg!(target_arch = "x86") {
            Some("windows-x86")
        } else {
            Some("windows-x64")
        }
    } else if cfg!(target_os = "macos") {
        if cfg!(target_arch = "aarch64") { Some("mac-os-arm64") } else { Some("mac-os") }
    } else if cfg!(target_os = "linux") {
        if cfg!(target_arch = "x86_64") {
            Some("linux")
        } else if cfg!(target_arch = "x86") {
            Some("linux-i386")
        } else {
            None
        }
    } else {
        None
    }
}

/// Parse a Java version string into its major version.
///
/// Accepts `21.0.5`, `21`, `25-ea`, `1.8.0_402` and `1.8`.
pub fn parse_java_major(version: &str) -> Option<u32> {
    let version = version.trim().trim_matches('"');
    let mut parts = version.split(|c: char| !c.is_ascii_digit());
    let first: u32 = parts.next()?.parse().ok()?;
    if first == 1 {
        return parts.next()?.parse().ok();
    }
    Some(first)
}

/// Major version from a runtime's `release` file, without starting a process.
pub fn read_release_major(java_home: &Path) -> Option<u32> {
    let text = fs::read_to_string(java_home.join("release")).ok()?;
    text.lines()
        .find_map(|line| line.strip_prefix("JAVA_VERSION="))
        .and_then(parse_java_major)
}

/// Run a Java binary and read its specification version.
///
/// A timeout keeps a hung or interactive binary (macOS's stub can open an
/// install dialog) from blocking a launch forever.
pub fn probe_java(java_path: &Path) -> Option<u32> {
    if !java_path.is_file() {
        return None;
    }
    let mut child = super::windowed_command(java_path)
        .args(["-XshowSettings:properties", "-version"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .ok()?;
    let mut stderr = child.stderr.take()?;
    let reader = std::thread::spawn(move || {
        let mut text = String::new();
        let _ = std::io::Read::read_to_string(&mut stderr, &mut text);
        text
    });
    let deadline = Instant::now() + Duration::from_secs(15);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    return None;
                }
                break;
            }
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(50)),
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    }
    let text = reader.join().ok()?;
    text.lines()
        .find_map(|line| {
            let line = line.trim();
            line.strip_prefix("java.specification.version = ")
        })
        .and_then(parse_java_major)
}

fn runtimes_root(breeze_home: &Path) -> PathBuf {
    breeze_home.join("runtimes")
}

/// The java home inside a runtime folder. An empty relative path means the
/// folder itself, which `contained_join` would otherwise refuse.
fn runtime_home(dir: &Path, relative: &str) -> Result<PathBuf, String> {
    if relative.is_empty() {
        Ok(dir.to_path_buf())
    } else {
        super::contained_join(dir, relative)
    }
}

/// Runtimes Breeze installed, newest marker first per component.
fn managed_installs(breeze_home: &Path) -> Vec<(String, JavaInstall)> {
    let mut found = Vec::new();
    let Ok(entries) = fs::read_dir(runtimes_root(breeze_home)) else { return found };
    for entry in entries.flatten() {
        let dir = entry.path();
        let Some(name) = dir.file_name().and_then(|n| n.to_str()).map(str::to_string) else { continue };
        if name.ends_with(".staging") {
            continue;
        }
        let Ok(text) = fs::read_to_string(dir.join(MARKER_FILE)) else { continue };
        let Ok(marker) = serde_json::from_str::<RuntimeMarker>(&text) else { continue };
        let Ok(java_home) = runtime_home(&dir, &marker.java_home) else { continue };
        let java_path = java_home.join("bin").join(java_binary_name());
        if java_path.is_file() {
            found.push((
                marker.component,
                JavaInstall { java_path, java_home, major: marker.major, source: JavaSource::Managed },
            ));
        }
    }
    found
}

fn bundled_install(breeze_home: &Path) -> Option<JavaInstall> {
    let home = breeze_home.join("jre");
    let java_path = home.join("bin").join(java_binary_name());
    if !java_path.is_file() {
        return None;
    }
    let major = read_release_major(&home).or_else(|| probe_java(&java_path))?;
    Some(JavaInstall { java_path, java_home: home, major, source: JavaSource::Bundled })
}

fn env_install() -> Option<JavaInstall> {
    let home = PathBuf::from(std::env::var_os("JAVA_HOME")?);
    let java_path = home.join("bin").join(java_binary_name());
    let major = probe_java(&java_path)?;
    Some(JavaInstall { java_path, java_home: home, major, source: JavaSource::Env })
}

/// A system Java with the required major.
///
/// macOS always has `/usr/bin/java`, even with no JDK installed; running that
/// stub fails and can pop an install dialog. So on macOS only real JDKs
/// registered with `java_home` are considered, never PATH.
fn system_install(required: u32) -> Option<JavaInstall> {
    if cfg!(target_os = "macos") {
        let output = std::process::Command::new("/usr/libexec/java_home")
            .args(["-v", &required.to_string()])
            .stdin(Stdio::null())
            .stderr(Stdio::null())
            .output()
            .ok()?;
        if !output.status.success() {
            return None;
        }
        let home = PathBuf::from(String::from_utf8_lossy(&output.stdout).trim());
        let java_path = home.join("bin").join(java_binary_name());
        let major = probe_java(&java_path)?;
        return Some(JavaInstall { java_path, java_home: home, major, source: JavaSource::System });
    }
    let path_var = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path_var) {
        let java_path = dir.join(java_binary_name());
        if !java_path.is_file() {
            continue;
        }
        let Some(major) = probe_java(&java_path) else { continue };
        let java_home = java_path
            .parent()
            .and_then(Path::parent)
            .map(Path::to_path_buf)
            .unwrap_or_else(|| dir.clone());
        return Some(JavaInstall { java_path, java_home, major, source: JavaSource::System });
    }
    None
}

/// The best Java already on this machine for a requirement, without installing.
///
/// Order: the exact runtime Breeze installed for this component, any runtime
/// Breeze installed that satisfies it, the older bundled runtime, JAVA_HOME,
/// then the system. Breeze's own runtimes come first so a system-wide Java
/// change cannot silently switch the game's runtime.
pub fn find_installed(breeze_home: &Path, requirement: &JavaRequirement) -> Option<JavaInstall> {
    let managed = managed_installs(breeze_home);
    if let Some((_, install)) = managed
        .iter()
        .find(|(component, install)| *component == requirement.component && install.major == requirement.major)
    {
        return Some(install.clone());
    }
    if let Some((_, install)) = managed.iter().find(|(_, install)| java_satisfies(install.major, requirement.major)) {
        return Some(install.clone());
    }
    if let Some(install) = bundled_install(breeze_home).filter(|i| java_satisfies(i.major, requirement.major)) {
        return Some(install);
    }
    if let Some(install) = env_install().filter(|i| java_satisfies(i.major, requirement.major)) {
        return Some(install);
    }
    system_install(requirement.major).filter(|i| java_satisfies(i.major, requirement.major))
}

/// One entry from a runtime component manifest, checked and ready to write.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuntimeEntry {
    Directory { path: String },
    File { path: String, url: String, sha1: String, size: u64, executable: bool },
    Link { path: String, target: String },
}

/// Validate a component manifest and turn it into the entries to install.
///
/// Returns the entries plus the java home relative to the runtime folder.
/// Paths that could escape the folder are refused outright rather than skipped:
/// a manifest that tries that is not one to install.
pub fn plan_runtime(manifest: &Value) -> Result<(Vec<RuntimeEntry>, String), String> {
    let files = manifest
        .get("files")
        .and_then(Value::as_object)
        .ok_or_else(|| "The Java runtime manifest lists no files.".to_string())?;

    let mut entries = Vec::with_capacity(files.len());
    let mut java_home: Option<String> = None;
    let binary_suffix = format!("bin/{}", java_binary_name());

    for (path, info) in files {
        if !is_relative_manifest_path(path) {
            return Err(format!("The Java runtime manifest has an unsafe path: {path}"));
        }
        match info.get("type").and_then(Value::as_str) {
            Some("directory") => entries.push(RuntimeEntry::Directory { path: path.clone() }),
            Some("file") => {
                let raw = info
                    .get("downloads")
                    .and_then(|d| d.get("raw"))
                    .ok_or_else(|| format!("{path} has no download in the Java runtime manifest."))?;
                let url = raw.get("url").and_then(Value::as_str).unwrap_or_default().to_string();
                if !is_mojang_url(&url) {
                    return Err(format!("{path} would be downloaded from outside Mojang."));
                }
                let sha1 = raw.get("sha1").and_then(Value::as_str).unwrap_or_default().to_ascii_lowercase();
                if sha1.len() != 40 || !sha1.bytes().all(|b| b.is_ascii_hexdigit()) {
                    return Err(format!("{path} has no valid SHA-1 in the Java runtime manifest."));
                }
                let size = raw.get("size").and_then(Value::as_u64).unwrap_or(0);
                let executable = info.get("executable").and_then(Value::as_bool).unwrap_or(false);
                if path == &binary_suffix || path.ends_with(&format!("/{binary_suffix}")) {
                    let home = path[..path.len() - binary_suffix.len()].trim_end_matches('/');
                    // The shortest home wins: a runtime can carry extra java
                    // binaries deeper inside, such as in a nested jre.
                    if java_home.as_ref().map_or(true, |current| home.len() < current.len()) {
                        java_home = Some(home.to_string());
                    }
                }
                entries.push(RuntimeEntry::File { path: path.clone(), url, sha1, size, executable });
            }
            Some("link") => {
                let target = info.get("target").and_then(Value::as_str).unwrap_or_default();
                if !link_stays_inside(path, target) {
                    return Err(format!("{path} links outside the Java runtime."));
                }
                entries.push(RuntimeEntry::Link { path: path.clone(), target: target.to_string() });
            }
            _ => return Err(format!("{path} has an unknown type in the Java runtime manifest.")),
        }
    }

    let java_home = java_home
        .ok_or_else(|| format!("The Java runtime manifest has no {binary_suffix}."))?;
    // Directories first, then files, then links, so every parent exists and a
    // link's target is already written when it is created.
    entries.sort_by_key(|entry| match entry {
        RuntimeEntry::Directory { .. } => 0,
        RuntimeEntry::File { .. } => 1,
        RuntimeEntry::Link { .. } => 2,
    });
    Ok((entries, java_home))
}

fn is_relative_manifest_path(path: &str) -> bool {
    !path.is_empty()
        && !path.starts_with('/')
        && !path.contains('\\')
        && !path.contains(':')
        && path.split('/').all(|part| !part.is_empty() && part != "." && part != "..")
}

fn is_mojang_url(url: &str) -> bool {
    match url::Url::parse(url) {
        Ok(parsed) => {
            let host = parsed.host_str().unwrap_or_default().to_ascii_lowercase();
            parsed.scheme() == "https" && (host == "mojang.com" || host.ends_with(".mojang.com"))
        }
        Err(_) => false,
    }
}

/// A link target is resolved against the link's own folder and must not climb
/// out of the runtime.
fn link_stays_inside(link_path: &str, target: &str) -> bool {
    if target.is_empty() || target.starts_with('/') || target.contains('\\') || target.contains(':') {
        return false;
    }
    let mut depth: Vec<&str> = link_path.split('/').collect();
    depth.pop();
    for part in target.split('/') {
        match part {
            "" | "." => {}
            ".." => {
                if depth.pop().is_none() {
                    return false;
                }
            }
            other => depth.push(other),
        }
    }
    true
}

pub fn sha1_hex(bytes: &[u8]) -> String {
    let digest = Sha1::digest(bytes);
    digest.iter().map(|b| format!("{b:02x}")).collect()
}

/// Write one planned file into the staging folder.
pub fn write_runtime_file(staging: &Path, path: &str, bytes: &[u8], sha1: &str, executable: bool) -> Result<(), String> {
    if sha1_hex(bytes) != sha1 {
        return Err(format!("{path} failed its SHA-1 check."));
    }
    let target = super::contained_join(staging, path)?;
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Could not create {}: {e}", parent.display()))?;
    }
    fs::write(&target, bytes).map_err(|e| format!("Could not write {}: {e}", target.display()))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mode = if executable { 0o755 } else { 0o644 };
        fs::set_permissions(&target, fs::Permissions::from_mode(mode))
            .map_err(|e| format!("Could not set permissions on {}: {e}", target.display()))?;
    }
    #[cfg(not(unix))]
    let _ = executable;
    Ok(())
}

/// Create one planned symlink. Windows runtimes have none; if one ever
/// appears there it is skipped, because creating symlinks needs a privilege.
pub fn write_runtime_link(staging: &Path, path: &str, target: &str) -> Result<(), String> {
    let link = super::contained_join(staging, path)?;
    if let Some(parent) = link.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Could not create {}: {e}", parent.display()))?;
    }
    #[cfg(unix)]
    {
        let _ = fs::remove_file(&link);
        std::os::unix::fs::symlink(target, &link)
            .map_err(|e| format!("Could not link {}: {e}", link.display()))?;
    }
    #[cfg(not(unix))]
    let _ = target;
    Ok(())
}

/// Download and install a runtime component. `progress` gets a message and a
/// fraction from 0 to 1.
pub async fn install_runtime<F>(
    client: &reqwest::Client,
    breeze_home: &Path,
    requirement: &JavaRequirement,
    mut progress: F,
) -> Result<JavaInstall, String>
where
    F: FnMut(&str, f32),
{
    use futures_util::StreamExt;

    let platform = mojang_platform_key().ok_or_else(|| {
        format!(
            "Mojang does not publish Java {} for this platform. Install Java {} and set JAVA_HOME.",
            requirement.major, requirement.major
        )
    })?;
    if !is_safe_component(&requirement.component) {
        return Err("The version asks for an invalid Java runtime name.".into());
    }

    progress(&format!("Looking up Java {}…", requirement.major), 0.01);
    let index: Value = get_json(client, RUNTIME_INDEX_URL).await?;
    let release = index
        .get(platform)
        .and_then(|p| p.get(&requirement.component))
        .and_then(Value::as_array)
        .and_then(|list| list.first())
        .ok_or_else(|| {
            format!(
                "Mojang does not publish Java {} ({}) for {platform}. Install Java {} and set JAVA_HOME.",
                requirement.major, requirement.component, requirement.major
            )
        })?;
    let manifest_url = release
        .pointer("/manifest/url")
        .and_then(Value::as_str)
        .filter(|u| is_mojang_url(u))
        .ok_or_else(|| "The Java runtime index has no manifest address.".to_string())?;
    let manifest_sha1 = release.pointer("/manifest/sha1").and_then(Value::as_str).unwrap_or_default();
    let version_name = release
        .pointer("/version/name")
        .and_then(Value::as_str)
        .unwrap_or_default()
        .to_string();

    let manifest_bytes = get_bytes(client, manifest_url).await?;
    if sha1_hex(&manifest_bytes) != manifest_sha1.to_ascii_lowercase() {
        return Err("The Java runtime manifest failed its SHA-1 check.".into());
    }
    let manifest: Value = serde_json::from_slice(&manifest_bytes)
        .map_err(|_| "The Java runtime manifest could not be read.".to_string())?;
    let (entries, java_home_rel) = plan_runtime(&manifest)?;

    let root = runtimes_root(breeze_home);
    let final_dir = root.join(&requirement.component);
    let staging = root.join(format!("{}.staging", requirement.component));
    if staging.exists() {
        fs::remove_dir_all(&staging).map_err(|e| format!("Could not clear an interrupted Java install: {e}"))?;
    }
    fs::create_dir_all(&staging).map_err(|e| format!("Could not create {}: {e}", staging.display()))?;

    let total_bytes: u64 = entries
        .iter()
        .map(|e| if let RuntimeEntry::File { size, .. } = e { *size } else { 0 })
        .sum::<u64>()
        .max(1);
    progress(
        &format!("Downloading Java {} ({} MB)…", requirement.major, total_bytes / 1_048_576),
        0.03,
    );

    let result: Result<(), String> = async {
        for entry in &entries {
            if let RuntimeEntry::Directory { path } = entry {
                let dir = super::contained_join(&staging, path)?;
                fs::create_dir_all(&dir).map_err(|e| format!("Could not create {}: {e}", dir.display()))?;
            }
        }

        // Each download owns what it needs. Futures that borrow the manifest or
        // the client are not Send for every lifetime, which a Tauri command's
        // future has to be.
        let files: Vec<(String, String, String, u64, bool)> = entries
            .iter()
            .filter_map(|entry| match entry {
                RuntimeEntry::File { path, url, sha1, size, executable } => {
                    Some((path.clone(), url.clone(), sha1.clone(), *size, *executable))
                }
                _ => None,
            })
            .collect();
        let mut downloads = futures_util::stream::iter(files.into_iter().map(|(path, url, sha1, size, executable)| {
            let staging = staging.clone();
            let client = client.clone();
            async move {
                let bytes = get_bytes(&client, &url).await?;
                write_runtime_file(&staging, &path, &bytes, &sha1, executable)?;
                Ok::<u64, String>(size)
            }
        }))
        .buffer_unordered(8);

        let mut done: u64 = 0;
        while let Some(result) = downloads.next().await {
            done += result?;
            progress(
                &format!("Downloading Java {}…", requirement.major),
                0.03 + 0.92 * (done as f32 / total_bytes as f32),
            );
        }

        for entry in &entries {
            if let RuntimeEntry::Link { path, target } = entry {
                write_runtime_link(&staging, path, target)?;
            }
        }

        let java_home = runtime_home(&staging, &java_home_rel)?;
        let java_path = java_home.join("bin").join(java_binary_name());
        let major = probe_java(&java_path).ok_or_else(|| {
            format!("The downloaded Java {} runtime does not start on this machine.", requirement.major)
        })?;
        if major != requirement.major {
            return Err(format!("Mojang's {} runtime is Java {major}, not {}.", requirement.component, requirement.major));
        }

        let marker = RuntimeMarker {
            component: requirement.component.clone(),
            major,
            version: version_name.clone(),
            platform: platform.to_string(),
            java_home: java_home_rel.clone(),
        };
        let marker_text = serde_json::to_string_pretty(&marker).map_err(|e| e.to_string())?;
        fs::write(staging.join(MARKER_FILE), marker_text)
            .map_err(|e| format!("Could not record the Java runtime: {e}"))?;
        Ok::<(), String>(())
    }
    .await;

    if let Err(error) = result {
        let _ = fs::remove_dir_all(&staging);
        return Err(error);
    }

    progress("Finishing Java install…", 0.97);
    if final_dir.exists() {
        fs::remove_dir_all(&final_dir).map_err(|e| format!("Could not replace the old Java runtime: {e}"))?;
    }
    // A virus scanner that is still reading the new files can hold the folder
    // for a moment on Windows, so a failed rename is retried before giving up.
    let mut attempt = 0;
    loop {
        match fs::rename(&staging, &final_dir) {
            Ok(()) => break,
            Err(_) if attempt < 5 => {
                attempt += 1;
                std::thread::sleep(Duration::from_millis(400));
            }
            Err(e) => {
                let _ = fs::remove_dir_all(&staging);
                return Err(format!("Could not finish the Java install: {e}"));
            }
        }
    }

    let java_home = runtime_home(&final_dir, &java_home_rel)?;
    let java_path = java_home.join("bin").join(java_binary_name());
    progress(&format!("Java {} is ready.", requirement.major), 1.0);
    Ok(JavaInstall { java_path, java_home, major: requirement.major, source: JavaSource::Managed })
}

async fn get_bytes(client: &reqwest::Client, url: &str) -> Result<Vec<u8>, String> {
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|e| format!("Could not reach Mojang ({}).", e.without_url()))?;
    if !response.status().is_success() {
        return Err(format!("Mojang answered {} for a Java runtime file.", response.status()));
    }
    response
        .bytes()
        .await
        .map(|b| b.to_vec())
        .map_err(|e| format!("A Java runtime download was interrupted ({}).", e.without_url()))
}

async fn get_json(client: &reqwest::Client, url: &str) -> Result<Value, String> {
    let bytes = get_bytes(client, url).await?;
    serde_json::from_slice(&bytes).map_err(|_| "Mojang's Java runtime index could not be read.".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn requirement_comes_from_the_version_json() {
        let modern = json!({ "javaVersion": { "component": "java-runtime-delta", "majorVersion": 21 } });
        assert_eq!(requirement_from_version_json(&modern), JavaRequirement { major: 21, component: "java-runtime-delta".into() });

        let old = json!({ "id": "1.12.2" });
        assert_eq!(requirement_from_version_json(&old), JavaRequirement { major: 8, component: "jre-legacy".into() });

        let no_component = json!({ "javaVersion": { "majorVersion": 25 } });
        assert_eq!(requirement_from_version_json(&no_component).component, "java-runtime-epsilon");

        let hostile = json!({ "javaVersion": { "component": "../../etc", "majorVersion": 17 } });
        assert_eq!(requirement_from_version_json(&hostile).component, "java-runtime-gamma");
    }

    #[test]
    fn java_versions_parse() {
        assert_eq!(parse_java_major("21.0.5"), Some(21));
        assert_eq!(parse_java_major("\"17.0.13\""), Some(17));
        assert_eq!(parse_java_major("1.8.0_402"), Some(8));
        assert_eq!(parse_java_major("1.8"), Some(8));
        assert_eq!(parse_java_major("25-ea"), Some(25));
        assert_eq!(parse_java_major("21"), Some(21));
        assert_eq!(parse_java_major(""), None);
        assert_eq!(parse_java_major("banana"), None);
    }

    #[test]
    fn release_file_gives_the_major_without_a_process() {
        let dir = std::env::temp_dir().join(format!("breeze-java-release-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("release"), "IMPLEMENTOR=\"Eclipse Adoptium\"\nJAVA_VERSION=\"21.0.5\"\n").unwrap();
        assert_eq!(read_release_major(&dir), Some(21));
        fs::write(dir.join("release"), "JAVA_VERSION=\"1.8.0_402\"\n").unwrap();
        assert_eq!(read_release_major(&dir), Some(8));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn which_installed_runtimes_are_good_enough() {
        assert!(java_satisfies(21, 21));
        assert!(java_satisfies(21, 17), "17-era versions keep running on the 21 Breeze already has");
        assert!(java_satisfies(21, 16));
        assert!(java_satisfies(17, 17));
        assert!(!java_satisfies(21, 25), "26.1 passes JVM options 21 rejects");
        assert!(!java_satisfies(25, 21), "a newer runtime is not assumed to work for older versions");
        assert!(!java_satisfies(21, 8), "8-era versions need Java 8");
        assert!(!java_satisfies(17, 21));
    }

    fn file_entry(url: &str, sha1: &str) -> Value {
        json!({ "type": "file", "executable": true, "downloads": { "raw": { "url": url, "sha1": sha1, "size": 10 } } })
    }

    #[test]
    fn a_runtime_plan_finds_the_java_home_on_every_layout() {
        let sha = "a".repeat(40);
        let url = "https://piston-data.mojang.com/v1/objects/x/java";
        let bin = java_binary_name();

        let flat = json!({ "files": {
            "bin": { "type": "directory" },
            format!("bin/{bin}"): file_entry(url, &sha),
        }});
        let (entries, home) = plan_runtime(&flat).unwrap();
        assert_eq!(home, "");
        assert!(matches!(entries[0], RuntimeEntry::Directory { .. }), "directories are created first");

        let mac = json!({ "files": {
            format!("jre.bundle/Contents/Home/bin/{bin}"): file_entry(url, &sha),
            format!("jre.bundle/Contents/Home/lib/jli/bin/{bin}"): file_entry(url, &sha),
            "jre.bundle/Contents/MacOS/libjli.dylib": { "type": "link", "target": "../Home/lib/libjli.dylib" },
        }});
        let (entries, home) = plan_runtime(&mac).unwrap();
        assert_eq!(home, "jre.bundle/Contents/Home", "the shallowest java wins");
        assert!(matches!(entries.last().unwrap(), RuntimeEntry::Link { .. }), "links come last");
    }

    #[test]
    fn a_runtime_plan_refuses_unsafe_manifests() {
        let sha = "b".repeat(40);
        let good = "https://piston-data.mojang.com/v1/objects/x/java";
        let bin = java_binary_name();
        let cases = [
            json!({ "files": { "../escape": file_entry(good, &sha), format!("bin/{bin}"): file_entry(good, &sha) } }),
            json!({ "files": { "/etc/passwd": file_entry(good, &sha), format!("bin/{bin}"): file_entry(good, &sha) } }),
            json!({ "files": { "C:/x": file_entry(good, &sha), format!("bin/{bin}"): file_entry(good, &sha) } }),
            json!({ "files": { format!("bin/{bin}"): file_entry("http://piston-data.mojang.com/x", &sha) } }),
            json!({ "files": { format!("bin/{bin}"): file_entry("https://evil.example/x", &sha) } }),
            json!({ "files": { format!("bin/{bin}"): file_entry("https://mojang.com.evil.example/x", &sha) } }),
            json!({ "files": { format!("bin/{bin}"): file_entry(good, "not-a-hash") } }),
            json!({ "files": { format!("bin/{bin}"): file_entry(good, &sha), "lib/x": { "type": "link", "target": "../../../etc/passwd" } } }),
            json!({ "files": { format!("bin/{bin}"): file_entry(good, &sha), "lib/x": { "type": "link", "target": "/etc/passwd" } } }),
            json!({ "files": { "lib/only": file_entry(good, &sha) } }),
        ];
        for (i, manifest) in cases.iter().enumerate() {
            assert!(plan_runtime(manifest).is_err(), "case {i} must be refused: {manifest}");
        }
    }

    #[test]
    fn runtime_files_are_verified_and_keep_their_exec_bit() {
        let staging = std::env::temp_dir().join(format!("breeze-java-stage-{}", std::process::id()));
        let _ = fs::remove_dir_all(&staging);
        fs::create_dir_all(&staging).unwrap();

        let body = b"#!/bin/sh\necho java\n";
        let sha = sha1_hex(body);
        assert!(write_runtime_file(&staging, "bin/java", body, &"0".repeat(40), true).is_err(), "a wrong hash is refused");
        write_runtime_file(&staging, "bin/java", body, &sha, true).unwrap();
        assert_eq!(fs::read(staging.join("bin").join("java")).unwrap(), body);
        assert!(write_runtime_file(&staging, "../outside", body, &sha, false).is_err());

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(staging.join("bin/java")).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o755);
            write_runtime_link(&staging, "lib/java-link", "../bin/java").unwrap();
            assert_eq!(fs::read(staging.join("lib/java-link")).unwrap(), body, "the link resolves inside the runtime");
        }
        let _ = fs::remove_dir_all(&staging);
    }

    #[test]
    fn sha1_matches_known_vectors() {
        assert_eq!(sha1_hex(b""), "da39a3ee5e6b4b0d3255bfef95601890afd80709");
        assert_eq!(sha1_hex(b"abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    /// Downloads the real Java 21 runtime for this platform from Mojang and
    /// starts it. Ignored by default because it fetches over 100 MB; CI runs it
    /// on each OS so macOS and Linux installs are verified for real.
    #[test]
    #[ignore]
    fn live_install_of_java_21_starts() {
        let home = std::env::temp_dir().join(format!("breeze-java-live-{}", std::process::id()));
        let _ = fs::remove_dir_all(&home);
        fs::create_dir_all(&home).unwrap();
        let client = reqwest::Client::builder()
            .connect_timeout(Duration::from_secs(20))
            .read_timeout(Duration::from_secs(60))
            .build()
            .unwrap();
        let requirement = JavaRequirement::default_modern();
        let install = tauri::async_runtime::block_on(install_runtime(&client, &home, &requirement, |message, fraction| {
                if fraction >= 1.0 || message.starts_with("Downloading Java 21 (") {
                    println!("{message}");
                }
            }))
        .expect("the runtime installs");
        assert_eq!(install.major, 21);
        assert_eq!(probe_java(&install.java_path), Some(21));

        let found = find_installed(&home, &requirement).expect("the installed runtime is found again");
        assert_eq!(found.source, JavaSource::Managed);
        assert_eq!(found.java_path, install.java_path);
        let _ = fs::remove_dir_all(&home);
    }
}
