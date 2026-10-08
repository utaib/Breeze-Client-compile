mod discord;
mod java_runtime;
mod mods_commands;
mod mods_local;
mod mods_versions;

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{
    collections::{HashMap, HashSet, VecDeque},
    fs,
    io::{BufRead, BufReader, Cursor},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        mpsc, Arc, Mutex, OnceLock,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};
use tauri::{AppHandle, Emitter, Manager, WebviewUrl, WebviewWindowBuilder};
use url::Url;
use zip::ZipArchive;


const BREEZE_CLIENT_ID: &str = "00000000402b5328";
const MICROSOFT_AUTHORIZE_URL: &str = "https://login.live.com/oauth20_authorize.srf";
const MICROSOFT_TOKEN_URL: &str = "https://login.live.com/oauth20_token.srf";
const MICROSOFT_REDIRECT_URI: &str = "https://login.live.com/oauth20_desktop.srf";
const MICROSOFT_SCOPE: &str = "XboxLive.signin offline_access";
const XBOX_AUTH_URL: &str = "https://user.auth.xboxlive.com/user/authenticate";
const XSTS_AUTH_URL: &str = "https://xsts.auth.xboxlive.com/xsts/authorize";
const MINECRAFT_XBOX_LOGIN_URL: &str = "https://api.minecraftservices.com/authentication/login_with_xbox";
const MINECRAFT_PROFILE_URL: &str = "https://api.minecraftservices.com/minecraft/profile";
const MODRINTH_API_BASE_URL: &str = "https://api.modrinth.com/v2";
const MOJANG_VERSION_MANIFEST_URL: &str = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const FABRIC_LOADER_LIST_URL: &str = "https://meta.fabricmc.net/v2/versions/loader";
const FABRIC_PROFILE_URL: &str = "https://meta.fabricmc.net/v2/versions/loader";
const AUTH_LOG_EVENT: &str = "breeze://auth-log";
const LAUNCH_STAGE_EVENT: &str = "breeze://launch-stage";
const LAUNCH_LOG_EVENT: &str = "breeze://launch-log";
const LAUNCH_COMPLETE_EVENT: &str = "breeze://launch-complete";
const LAUNCH_ERROR_EVENT: &str = "breeze://launch-error";
const JAVA_RUNTIME_EVENT: &str = "breeze://java-runtime";
const SESSION_KEYRING_SERVICE: &str = "com.breeze.client";
const SESSION_KEYRING_ACCOUNT: &str = "minecraft-session";
const FABRIC_API_PROJECT_ID: &str = "P7dR8mSH";
/// MCEF (embedded Chromium for Minecraft) on Modrinth. The Breeze menu runs in
/// it, and Breeze jars built with the web menu stop the game at start without
/// it, so it is installed before each launch the way Fabric API is.
const MCEF_PROJECT_SLUG: &str = "mcef";
/// The Fabric mod id MCEF's jars declare.
const MCEF_MOD_ID: &str = "mcef";

/// Where the Breeze runtime jar lives inside an instance.
///
/// Deliberately not the mods folder. Anything sitting there is the user's, and
/// the launcher used to clear out every `breeze*.jar` it found before each
/// launch, which quietly deleted user mods whose names began with "breeze".
const BREEZE_RUNTIME_DIR: &str = ".breeze";
const BREEZE_RUNTIME_JAR: &str = "runtime.jar";
/// The Fabric mod id the Breeze mod declares.
const BREEZE_MOD_ID: &str = "breeze";
const BREEZE_RUNTIME_SIDECAR: &str = "runtime.json";
/// Legacy name the launcher used to drop into the mods folder.
const LEGACY_BREEZE_MOD_NAME: &str = "breezemod.jar";
/// First Fabric loader that understands -Dfabric.addMods. Below this the jar has
/// to go into the mods folder, because addMods is simply ignored.
const FABRIC_ADD_MODS_MIN_LOADER: &str = "0.12.0";
/// Header the Breeze API sends with the runtime jar.
const BREEZE_SHA256_HEADER: &str = "X-Breeze-Sha256";


#[cfg(target_os = "windows")]
const WIN_CREATE_NO_WINDOW: u32 = 0x0800_0000;


fn is_valid_jar(path: &Path) -> bool {
    if !path.exists() { return false; }
    let lower = path.to_string_lossy().to_ascii_lowercase();
    
    if lower.ends_with(".tmp") || lower.ends_with(".part")
        || lower.ends_with(".crdownload") || lower.contains(".~tmp") {
        return false;
    }
    
    match fs::read(path) {
        Ok(bytes) => bytes.len() >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B,
        Err(_)    => false,
    }
}

fn sha256_hex(bytes: &[u8]) -> String {
    let mut hasher = Sha256::new();
    hasher.update(bytes);
    format!("{:x}", hasher.finalize())
}

/// Compare a digest against what the server said it should be.
///
/// Case and surrounding whitespace vary between the header, the launch request
/// and the sidecar, so they are normalised rather than trusted to match.
fn sha256_matches(bytes: &[u8], expected: &str) -> bool {
    let expected = expected.trim();
    if expected.is_empty() {
        return false;
    }
    sha256_hex(bytes).eq_ignore_ascii_case(expected)
}

/// Whether a cached jar can be reused without downloading it again.
///
/// Existence alone is not enough for the Breeze runtime jar. The developer's
/// cache held a July build while production had been serving a September one
/// since, because the old code returned early the moment the path existed.
fn cached_jar_is_current(path: &Path, expected_sha256: Option<&str>) -> bool {
    let Some(expected) = expected_sha256.map(str::trim).filter(|value| !value.is_empty()) else {
        // Nothing to compare against, so a structurally valid jar is all we can
        // ask for.
        return is_valid_jar(path);
    };
    match fs::read(path) {
        Ok(bytes) => {
            bytes.len() >= 4 && bytes[0] == 0x50 && bytes[1] == 0x4B && sha256_matches(&bytes, expected)
        }
        Err(_) => false,
    }
}

/// Whether `candidate` really sits inside `dir`.
///
/// Both sides are canonicalised where possible so a `..` segment or a symlink
/// cannot walk a delete out of the mods folder.
fn is_inside_dir(dir: &Path, candidate: &Path) -> bool {
    let dir = fs::canonicalize(dir).unwrap_or_else(|_| dir.to_path_buf());
    let resolved = match fs::canonicalize(candidate) {
        Ok(path) => path,
        Err(_) => {
            // The file may not exist; fall back to the lexical parent, which is
            // still enough to reject an absolute path elsewhere on disk.
            let parent = candidate.parent().unwrap_or(Path::new(""));
            let parent = fs::canonicalize(parent).unwrap_or_else(|_| parent.to_path_buf());
            return parent == dir;
        }
    };
    resolved.starts_with(&dir) && resolved != dir
}

/// Write `contents` through a sibling `.tmp` file, then rename it into place.
///
/// `fs::write` truncates first, so a crash or a power cut between truncate and
/// write leaves a zero-byte manifest and the user's whole mod list is gone. The
/// rename is atomic on every platform Breeze ships to, so the target is either
/// the old file or the new one.
fn write_file_atomic(path: &Path, contents: &str) -> Result<(), String> {
    use std::io::Write as _;

    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("Could not create {}: {error}", parent.display()))?;
    }
    let temp_path = path.with_extension(format!(
        "{}tmp",
        path.extension()
            .map(|ext| format!("{}.", ext.to_string_lossy()))
            .unwrap_or_default()
    ));

    {
        let mut file = fs::File::create(&temp_path)
            .map_err(|error| format!("Could not create {}: {error}", temp_path.display()))?;
        file.write_all(contents.as_bytes())
            .map_err(|error| format!("Could not write {}: {error}", temp_path.display()))?;
        file.flush()
            .map_err(|error| format!("Could not flush {}: {error}", temp_path.display()))?;
        // Without this the rename can land before the bytes do.
        file.sync_all()
            .map_err(|error| format!("Could not sync {}: {error}", temp_path.display()))?;
    }

    fs::rename(&temp_path, path).map_err(|error| {
        let _ = fs::remove_file(&temp_path);
        format!("Could not replace {}: {error}", path.display())
    })
}

/// An ISO-8601 UTC timestamp, so the recorded time reads the same on every
/// machine. Written by hand because the launcher carries no date crate.
fn utc_timestamp() -> String {
    let seconds = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|value| value.as_secs())
        .unwrap_or_default();
    let days = (seconds / 86_400) as i64;
    let time_of_day = seconds % 86_400;

    // Civil date from a day count, Howard Hinnant's days_from_civil inverted.
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if month <= 2 { year + 1 } else { year };

    format!(
        "{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z",
        year,
        month,
        day,
        time_of_day / 3_600,
        (time_of_day % 3_600) / 60,
        time_of_day % 60
    )
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LauncherVersion { id: String, minecraft: String, loader: String, loader_version: String, channel: String, recommended: bool }

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct OptimizationMod { id: String, name: String, summary: String, status: String }

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LauncherManifest { versions: Vec<LauncherVersion>, optimization_mods: Vec<OptimizationMod> }

#[derive(Deserialize, Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct AuthenticatedAccount {
    uuid: String,
    username: String,
    access_token: String,
    refresh_token: Option<String>,
    xuid: Option<String>,
    breeze_token: Option<String>,
    /// Set when Microsoft explicitly rejected this account's refresh token, so
    /// the switcher can say "sign in again" instead of promising a silent
    /// switch it cannot deliver. `default` keeps pre-1.0.5 accounts.json files
    /// parsable, without it the whole store would be discarded on upgrade.
    #[serde(default)]
    needs_reauth: bool,
}

/// Every account the user has signed into, each with its own Microsoft refresh
/// token, plus which one is currently active. Persisted to accounts.json.
#[derive(Deserialize, Serialize, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
struct AccountStore { active_uuid: Option<String>, accounts: Vec<AuthenticatedAccount> }

/// Token-free view of a stored account, for rendering the account switcher.
#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct SavedAccountSummary { uuid: String, username: String, active: bool, can_resume: bool }

/// One installed mod.
///
/// `project_id` is whatever the call site happened to hold, which for the same
/// mod is sometimes a Modrinth slug and sometimes its hex id. Keying the
/// manifest on it is what produced 75 records for 61 jars on the developer's
/// live profile: fourteen mods present twice, once under each spelling, which
/// is why toggles reverted and mods read as "not installed". `canonical_id` is
/// the one identity every code path agrees on. `default` on the container keeps
/// pre-1.0.22 mods-state.json files parsable, they simply have no canonical id
/// yet and get one on the next write.
#[derive(Serialize, Deserialize, Clone, Default, Debug)]
#[serde(rename_all = "camelCase", default)]
struct ManagedModRecord { project_id: String, project_slug: String, title: String, summary: Option<String>, icon_url: Option<String>, game_version: String, loader: String, installed_version_id: String, installed_version_name: String, file_name: String, enabled: bool, canonical_id: String }

// Either identifier may be missing. Some callers only know a Modrinth slug, and
// sending the slug again as the project id is what used to record the same mod
// twice under two keys. The installer resolves whichever is present to
// Modrinth's own id, so neither field is required on its own.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct InstallModRequest {
    profile_id: String,
    game_version: String,
    loader: String,
    #[serde(default)]
    project_id: String,
    #[serde(default)]
    project_slug: String,
    title: String,
    summary: Option<String>,
    icon_url: Option<String>,
}

impl InstallModRequest {
    /// The key to ask Modrinth about: the project id when there is one, else the
    /// slug. Modrinth's project endpoint accepts either.
    fn lookup_key(&self) -> String {
        let id = self.project_id.trim();
        if id.is_empty() { self.project_slug.trim().to_string() } else { id.to_string() }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImportCustomModRequest {
    profile_id: String,
    game_version: String,
    loader: String,
    file_name: String,
    /// The jar's bytes, for the file picker, which reads the file in the webview.
    #[serde(default)]
    bytes: Vec<u8>,
    /// A path instead, for a jar dropped onto the window: the OS hands over a
    /// path, and sending a 30MB jar back through IPC as a JSON array of numbers
    /// is what made large imports fail.
    #[serde(default)]
    source_path: Option<String>,
}

/// One jar being copied out of the version it is installed in into another.
///
/// Only for mods the launcher cannot fetch again. A locally imported jar has no
/// Modrinth project behind it, so copying the file is the only way to move it.
/// Anything that came from Modrinth is reinstalled for the target version
/// instead, because a jar built for one Minecraft version usually will not load
/// on another.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CopyLocalModRequest {
    from_profile_id: String,
    to_profile_id: String,
    project_id: String,
    game_version: String,
    loader: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalServerPrepareRequest {
    id: String,
    name: String,
    loader: String,
    version: String,
    ram_mb: u32,
    port: u16,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LocalServerPrepareResult {
    id: String,
    path: String,
    server_jar_path: Option<String>,
    connection_host: String,
    connection_port: u16,
    logs: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalServerFileImportRequest {
    server_path: String,
    file_name: String,
    file_type: String,
    bytes: Vec<u8>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LocalServerFileImportResult {
    name: String,
    path: String,
    file_type: String,
    size: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LocalServerProcessRequest {
    id: String,
    server_path: String,
    #[serde(default)]
    ram_mb: u32,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LocalServerProcessResult {
    id: String,
    status: String,
    log_path: Option<String>,
    logs: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct LaunchRequest {
    version_id: String,
    loader_version: Option<String>,
    max_ram_mb: Option<u32>,
    account: Option<AuthenticatedAccount>,
    breeze_mod_url: Option<String>,
    breeze_mod_sha256: Option<String>,
    /// The signed-in Breeze session. Used for the authorized mod download and
    /// to mint the game's short-lived token; it never reaches the game itself.
    #[serde(default)]
    breeze_token: Option<String>,
    #[serde(default)]
    api_base_url: Option<String>,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase", default)]
struct LauncherSettings {
    allocated_ram_mb: u32,
    theme: String,
    include_snapshots: bool,
    performance_profile: String,
    prewarm_enabled: bool,
    selected_version: Option<String>,
    mod_auto_update: bool,
    graphics_performance: bool,
    browser_accel: String,
    after_launch: String,
    minecraft_path: String,
    resolution_width: Option<u32>,
    resolution_height: Option<u32>,
    imported_from_feather: bool,
    // Custom background adjustments. The image itself lives on disk (a data URL
    // in localStorage blew the ~5MB quota for any real photo, which is why
    // backgrounds silently failed to persist). `default` on the struct keeps
    // pre-1.0.8 settings files loadable.
    bg_dim: u8,
    bg_blur: u8,
    bg_brightness: u8,
    bg_opacity: u8,
    bg_scale: u8,
    bg_position: String,
    // What actually launched last, as opposed to what a settings screen or an
    // import happened to select. Written only after Java has been spawned, so a
    // version that failed preflight never becomes the remembered default.
    last_launched_version: Option<String>,
    last_launched_at: Option<String>,
}

impl Default for LauncherSettings {
    fn default() -> Self {
        Self {
            allocated_ram_mb: 4096,
            theme: "glass".into(),
            include_snapshots: false,
            performance_profile: "Performance".into(),
            prewarm_enabled: true,
            selected_version: None,
            mod_auto_update: true,
            graphics_performance: true,
            browser_accel: "auto".into(),
            after_launch: "keep-open".into(),
            
            minecraft_path: breeze_home_dir()
                .map(|p| p.to_string_lossy().to_string())
                .unwrap_or_default(),
            resolution_width: None,
            resolution_height: None,
            imported_from_feather: false,
            // Neutral defaults: image shown as-is, lightly dimmed so UI text
            // stays readable over a bright photo.
            bg_dim: 35,
            bg_blur: 0,
            bg_brightness: 100,
            bg_opacity: 100,
            bg_scale: 100,
            bg_position: "center".into(),
            last_launched_version: None,
            last_launched_at: None,
        }
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct FeatherImportRequest {
    include_all_versions: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct FeatherImportSummary {
    imported_versions: Vec<String>,
    imported_custom_mods: usize,
    imported_modrinth_mods: usize,
    copied_config_directories: usize,
    selected_version: Option<String>,
    allocated_ram_mb: u32,
    warnings: Vec<String>,
}


#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ThirdPartyImportSummary {
    client_detected: bool,
    imported_custom_mods: usize,
    imported_modrinth_mods: usize,
    copied_config_directories: usize,
    selected_version: Option<String>,
    warnings: Vec<String>,
}


#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct DetectedClient {
    id: String,
    name: String,
    detected: bool,
    root_path: Option<String>,
    active_version: Option<String>,
    profile_count: usize,
    mod_count: usize,
    has_options: bool,
    has_optifine_options: bool,
    has_resourcepacks: bool,
    has_shaderpacks: bool,
    has_saves: bool,
    has_config: bool,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct DetectionReport {
    clients: Vec<DetectedClient>,
    breeze_home: String,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct VanillaImportRequest {
    target_version: Option<String>,
    include_resourcepacks: Option<bool>,
    include_shaderpacks: Option<bool>,
    include_saves: Option<bool>,
    include_options: Option<bool>,
    include_mods: Option<bool>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct VanillaImportSummary {
    detected: bool,
    profile_id: Option<String>,
    active_version: Option<String>,
    imported_custom_mods: usize,
    imported_resourcepacks: usize,
    imported_shaderpacks: usize,
    copied_options: bool,
    copied_optifine_options: bool,
    copied_saves: usize,
    copied_config_directories: usize,
    warnings: Vec<String>,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct ModrinthAppImportRequest {
    include_all_profiles: Option<bool>,
    include_resourcepacks: Option<bool>,
    include_shaderpacks: Option<bool>,
    include_saves: Option<bool>,
    include_options: Option<bool>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ModrinthAppImportSummary {
    detected: bool,
    root_path: Option<String>,
    imported_profiles: Vec<String>,
    imported_mods: usize,
    imported_resourcepacks: usize,
    imported_shaderpacks: usize,
    copied_config_directories: usize,
    copied_options: usize,
    selected_version: Option<String>,
    warnings: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MrpackImportRequest {
    path: Option<String>,
    bytes: Option<Vec<u8>>,
    target_profile_id: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MrpackImportSummary {
    pack_name: String,
    pack_version: Option<String>,
    game_version: String,
    loader: String,
    profile_id: String,
    downloaded_mods: usize,
    copied_overrides: usize,
    warnings: Vec<String>,
}

#[derive(Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
struct ImportEverythingRequest {
    clients: Option<Vec<String>>,
    include_resourcepacks: Option<bool>,
    include_shaderpacks: Option<bool>,
    include_saves: Option<bool>,
    include_options: Option<bool>,
    include_mods: Option<bool>,
    create_backup: Option<bool>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ImportFailure {
    client: String,
    error: String,
}


#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct JavaRuntimeStatus {
    installed: bool,
    java_path: String,
    source: String,
    root: Option<String>,
    major: Option<u32>,
}

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct JavaRuntimeEvent {
    stage: String,
    message: String,
    progress: Option<f32>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct ImportEverythingSummary {
    attempted: Vec<String>,
    succeeded: Vec<String>,
    failed: Vec<ImportFailure>,
    total_mods: usize,
    total_resourcepacks: usize,
    total_shaderpacks: usize,
    total_configs: usize,
    total_options: usize,
    backup_path: Option<String>,
    selected_version: Option<String>,
    warnings: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ApplyPerformanceProfileRequest {
    profile_id: String,
    game_version: String,
    loader: String,
    profile_name: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PerformanceProfileResult {
    profile_name: String,
    recommended_ram_mb: u32,
    installed_count: usize,
    installed_titles: Vec<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct PrewarmRequest {
    version_id: String,
    loader_version: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct PrewarmResult {
    version_id: String,
    loader_version: Option<String>,
    asset_index_name: String,
    library_count: usize,
    cached_mod_count: usize,
    game_directory: String,
    status: String,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SystemMemoryInfo {
    total_ram_mb: u64,
    safe_max_ram_mb: u64,
    recommended_ram_mb: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LaunchPlan { version_id: String, profile: String, game_directory: String, status: String, preflight: Vec<String> }

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct LaunchStageEvent { stage: String, message: String, progress: Option<f32> }

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct LaunchMessageEvent { message: String }

/// The launch lifecycle the UI is allowed to see. "running" deliberately means
/// "the process survived startup", not "the process was created": reporting
/// success at spawn time is what let a launch that died two seconds later still
/// read as a successful one.
const LAUNCH_STATUS_STARTING: &str = "starting";
const LAUNCH_STATUS_RUNNING: &str = "running";
const LAUNCH_STATUS_EXITED: &str = "exited";
const LAUNCH_STATUS_CRASHED: &str = "crashed";
const LAUNCH_STATUS_STARTUP_FAILED: &str = "startup_failed";

/// How long the process must stay up, absent a startup banner, before the
/// launch counts as running.
const LAUNCH_GRACE_PERIOD: Duration = Duration::from_secs(8);

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct LaunchCompleteEvent { status: String, pid: Option<u32>, version_id: String, game_directory: String, message: String, detail: Option<String>, report_path: Option<String> }

#[derive(Deserialize, Debug)]
struct MicrosoftTokenResponse { access_token: String, refresh_token: Option<String> }

#[derive(Serialize, Clone)]
#[serde(rename_all = "camelCase")]
struct AuthLogEvent { stage: String, message: String }

#[derive(Deserialize)]
#[serde(rename_all = "PascalCase")]
struct XboxAuthResponse { token: String, display_claims: XboxDisplayClaims }

#[derive(Deserialize)]
struct XboxDisplayClaims { xui: Vec<XboxUserClaim> }

#[derive(Deserialize, Clone)]
struct XboxUserClaim { uhs: String, xid: Option<String> }

#[derive(Deserialize)]
struct MinecraftLoginResponse {
    #[serde(rename = "access_token")]
    access_token: String,
}

#[derive(Deserialize)]
struct MinecraftProfileResponse { id: String, name: String }

#[derive(Deserialize)]
#[derive(Clone)]
struct ModrinthVersion {
    id: String,
    name: String,
    version_number: String,
    featured: bool,
    #[serde(default)]
    project_id: String,
    /// release, beta or alpha.
    #[serde(default)]
    version_type: String,
    #[serde(default)]
    date_published: String,
    #[serde(default)]
    loaders: Vec<String>,
    #[serde(default)]
    game_versions: Vec<String>,
    files: Vec<ModrinthVersionFile>,
    dependencies: Vec<ModrinthDependency>,
}

#[derive(Deserialize, Clone)]
struct ModrinthVersionFile {
    url: String,
    filename: String,
    primary: bool,
    #[serde(default)]
    hashes: HashMap<String, String>,
    #[serde(default)]
    size: u64,
}

#[derive(Deserialize, Clone)]
struct ModrinthDependency {
    project_id: Option<String>,
    /// The exact version the dependent was built against, when its author says.
    #[serde(default)]
    version_id: Option<String>,
    dependency_type: String,
}

#[derive(Deserialize)]
struct ModrinthProjectDetails {
    id: String,
    slug: String,
    title: String,
    description: String,
    icon_url: Option<String>,
}

#[derive(Clone)]
struct ResolvedLaunchVersion { effective: Value, version_name: String, client_jar_path: PathBuf, loader_version: String }

#[derive(Clone)]
struct ResolvedLibrary { path: String, url: String, native: bool }

#[tauri::command]
fn launcher_manifest() -> LauncherManifest {
    LauncherManifest {
        versions: vec![
            LauncherVersion { id: "fabric-1.21.8".into(), minecraft: "1.21.8".into(), loader: "Fabric".into(), loader_version: "0.19.x".into(), channel: "Recommended".into(), recommended: true },
            LauncherVersion { id: "fabric-1.21.5".into(), minecraft: "1.21.5".into(), loader: "Fabric".into(), loader_version: "0.18.x".into(), channel: "Stable".into(), recommended: false },
            LauncherVersion { id: "fabric-1.20.6".into(), minecraft: "1.20.6".into(), loader: "Fabric".into(), loader_version: "0.16.x".into(), channel: "Legacy".into(), recommended: false },
        ],
        optimization_mods: vec![],
    }
}

/// Every mod in the version's mods folder: what the launcher installed (with
/// where it came from), and every other jar Fabric would load too. Listing
/// only the launcher's own records is what showed "No mods installed" over a
/// folder full of jars.
#[tauri::command]
fn list_installed_mods(app: AppHandle, profile_id: String) -> Result<Vec<Value>, String> {
    let manifest = read_mod_manifest(&profile_manifest_path(&app, &profile_id)?)?;
    let mods_dir = profile_mods_dir(&app, &profile_id)?;
    let mut out: Vec<Value> = manifest
        .iter()
        .filter(|record| record.project_id != "breeze:core")
        .map(|record| {
            let mut value = serde_json::to_value(record).unwrap_or(Value::Null);
            if let Some(object) = value.as_object_mut() {
                let name = record.file_name.trim();
                let on = !name.is_empty() && mods_dir.join(name).is_file();
                let off = !name.is_empty() && mods_dir.join(format!("{name}{}", mods_local::DISABLED_SUFFIX)).is_file();
                object.insert("managed".into(), Value::Bool(true));
                object.insert("fileMissing".into(), Value::Bool(!on && !off));
            }
            value
        })
        .collect();
    match mods_commands::unrecorded_as_records(&app, &profile_id, &profile_id) {
        Ok(extra) => out.extend(extra),
        Err(error) => emit_launch_log(&app, &format!("[Mods] Could not read the mods folder: {error}")),
    }
    Ok(out)
}

/// The version's mods folder as it really is: every jar, its Fabric metadata,
/// duplicates, missing or wrong-version dependencies, and what the game
/// reported the last time it started.
#[tauri::command]
fn scan_instance_mods(app: AppHandle, profile_id: String, game_version: Option<String>) -> Result<mods_commands::InstanceModsReport, String> {
    let minecraft = profile_minecraft(&profile_id, game_version);
    mods_commands::instance_report(&app, &profile_id, &minecraft)
}

/// Moves one jar (a duplicate, or a mod the launcher did not install) out of
/// the mods folder into the instance's backups. The page asks first.
#[tauri::command]
fn remove_mod_file(app: AppHandle, profile_id: String, file_name: String) -> Result<mods_commands::MovedFile, String> {
    mods_commands::remove_file(&app, &profile_id, file_name.trim(), "removed")
}

/// Opens one of the version's folders (instance, mods, logs, config,
/// crash-reports, backups) in the system's file manager.
#[tauri::command]
fn open_instance_folder(app: AppHandle, profile_id: String, which: String) -> Result<String, String> {
    use tauri_plugin_opener::OpenerExt;
    let path = mods_commands::instance_folder(&app, &profile_id, which.trim())?;
    let shown = path.to_string_lossy().to_string();
    app.opener()
        .open_path(shown.clone(), None::<&str>)
        .map_err(|error| format!("Could not open {shown}: {error}"))?;
    Ok(shown)
}

/// The Minecraft version a profile runs: the one the page sends, or the
/// profile id, which is the version id for every Breeze profile.
fn profile_minecraft(profile_id: &str, game_version: Option<String>) -> String {
    game_version.filter(|v| !v.trim().is_empty()).unwrap_or_else(|| profile_id.to_string())
}

/// The versions Modrinth publishes of one installed mod for this Minecraft
/// version with Fabric, and which one is installed. A jar the launcher did not
/// install is recognised by its hash.
#[tauri::command]
async fn list_mod_versions(app: AppHandle, profile_id: String, game_version: Option<String>, key: String) -> Result<mods_versions::ModVersions, String> {
    let minecraft = profile_minecraft(&profile_id, game_version);
    mods_versions::list_versions(&app, &profile_id, &minecraft, &key).await
}

/// Downloads a chosen version into the instance's staging folder, checks it,
/// and says what it would change. The mods folder is not touched yet.
#[tauri::command]
async fn stage_mod_version(
    app: AppHandle,
    profile_id: String,
    game_version: Option<String>,
    key: String,
    version_id: String,
) -> Result<mods_versions::StagedChange, String> {
    let minecraft = profile_minecraft(&profile_id, game_version);
    mods_versions::stage(&app, &profile_id, &minecraft, &key, &version_id).await
}

/// Puts a staged version in place; the files it replaces go to the backups.
/// `accept_issues` is the player's second yes when Fabric would refuse a mod
/// afterwards.
#[tauri::command]
fn apply_mod_change(
    app: AppHandle,
    profile_id: String,
    game_version: Option<String>,
    token: String,
    accept_issues: Option<bool>,
) -> Result<mods_versions::AppliedChange, String> {
    let minecraft = profile_minecraft(&profile_id, game_version);
    let paths = mods_versions::paths(&app, &profile_id)?;
    let result = mods_versions::apply_staged(
        &paths.instance_dir,
        &paths.mods_dir,
        &paths.manifest,
        &mods_versions::environment_for(&minecraft),
        token.trim(),
        accept_issues.unwrap_or(false),
    )?;
    for moved in &result.replaced {
        emit_launch_log(&app, &format!("[Mods] {} moved to the backups; {} is in place.", moved.file_name, result.file_name));
    }
    Ok(result)
}

#[tauri::command]
fn discard_mod_change(app: AppHandle, profile_id: String, token: String) -> Result<(), String> {
    mods_versions::discard_staged(&profile_root_dir(&app, &profile_id)?, token.trim())
}

/// The files the launcher moved out of this version's mods folder, newest first.
#[tauri::command]
fn list_mod_backups(app: AppHandle, profile_id: String) -> Result<Vec<mods_versions::Backup>, String> {
    Ok(mods_versions::list_backups(&profile_root_dir(&app, &profile_id)?))
}

/// Puts a backed-up file back; what is installed of that mod now goes to the
/// backups in its place.
#[tauri::command]
fn restore_mod_backup(app: AppHandle, profile_id: String, backup_id: String, file_name: String) -> Result<mods_versions::AppliedChange, String> {
    let paths = mods_versions::paths(&app, &profile_id)?;
    mods_versions::restore_backup(&paths.instance_dir, &paths.mods_dir, &paths.manifest, backup_id.trim(), file_name.trim())
}

#[tauri::command]
async fn install_modrinth_mod(app: AppHandle, request: InstallModRequest) -> Result<ManagedModRecord, String> {
    let client = http_client()?;
    let mut seen = HashSet::new();
    let profile_id = request.profile_id.clone();
    let game_version = request.game_version.clone();
    let loader = request.loader.clone();
    install_modrinth_project_recursive(
        &client,
        &app,
        &profile_id,
        &game_version,
        &loader,
        request,
        &mut seen,
    )
    .await
}

#[tauri::command]
fn import_custom_mod(app: AppHandle, request: ImportCustomModRequest) -> Result<ManagedModRecord, String> {
    let mods_dir = profile_mods_dir(&app, &request.profile_id)?;
    fs::create_dir_all(&mods_dir).map_err(|e| format!("Could not create Breeze mods directory: {e}"))?;

    let file_name = sanitize_filename(&request.file_name);
    if !file_name.to_ascii_lowercase().ends_with(".jar") {
        return Err("Only .jar files can be imported as custom mods.".into());
    }

        // Either the bytes the picker read, or a file the user dropped on the
    // window. A dropped path is read here rather than in the webview, so the
    // jar never crosses IPC.
    let bytes = if !request.bytes.is_empty() {
        request.bytes
    } else {
        let source = request
            .source_path
            .as_deref()
            .map(str::trim)
            .filter(|path| !path.is_empty())
            .ok_or_else(|| "No file was provided to import.".to_string())?;
        let source = PathBuf::from(source);
        if !source.is_file() {
            return Err("That file no longer exists.".into());
        }
        if !source.extension().map_or(false, |ext| ext.eq_ignore_ascii_case("jar")) {
            return Err("Only .jar files can be imported as custom mods.".into());
        }
        let size = source.metadata().map(|meta| meta.len()).unwrap_or(0);
        if size > 512 * 1024 * 1024 {
            return Err("That file is too large to be a Fabric mod.".into());
        }
        fs::read(&source).map_err(|e| format!("Could not read the dropped file: {e}"))?
    };

    if bytes.len() < 4 || bytes[0] != 0x50 || bytes[1] != 0x4B {
        return Err("The selected file does not appear to be a valid JAR (missing ZIP magic bytes). \
                    Make sure the download is complete before importing.".into());
    }

    fs::write(mods_dir.join(&file_name), bytes)
        .map_err(|e| format!("Could not write custom mod file: {e}"))?;

    let manifest_path = profile_manifest_path(&app, &request.profile_id)?;
    let mut manifest = read_mod_manifest(&manifest_path)?;
    let custom_id = format!(
        "custom:{}",
        file_name
            .trim_end_matches(".jar")
            .replace(' ', "-")
            .to_ascii_lowercase()
    );
    // Goes through the shared upsert so a re-import collapses onto the existing
    // record instead of leaving a second one keyed on the same jar.
    let record = upsert_managed_mod(
        &mut manifest,
        ManagedModRecord {
            project_id: custom_id.clone(),
            project_slug: custom_id.clone(),
            title: file_name.trim_end_matches(".jar").to_string(),
            summary: Some("Local Breeze import".into()),
            icon_url: None,
            game_version: request.game_version,
            loader: request.loader,
            installed_version_id: "local-import".into(),
            installed_version_name: "Local import".into(),
            file_name,
            enabled: true,
            canonical_id: custom_id,
        },
    );
    write_mod_manifest(&manifest_path, &manifest)?;
    Ok(record)
}

#[tauri::command]
fn set_mod_enabled(app: AppHandle, profile_id: String, project_id: String, enabled: bool) -> Result<ManagedModRecord, String> {
    let manifest_path = profile_manifest_path(&app, &profile_id)?;
    let mods_dir = profile_mods_dir(&app, &profile_id)?;
    // A jar the launcher has no record of is switched by its file alone.
    if let Some(jar_name) = project_id.strip_prefix(mods_commands::FILE_KEY_PREFIX) {
        mods_local::set_enabled(&mods_dir, jar_name, enabled)?;
        return Ok(ManagedModRecord {
            project_id: project_id.clone(),
            canonical_id: project_id.clone(),
            title: jar_name.trim_end_matches(".jar").to_string(),
            file_name: jar_name.to_string(),
            enabled,
            ..Default::default()
        });
    }
    let mut manifest = read_mod_manifest(&manifest_path)?;
    let target = manifest
        .iter()
        .find(|item| record_matches_key(item, &project_id))
        .cloned()
        .ok_or_else(|| "Mod is not installed.".to_string())?;

    // Apply the choice to every record that describes this mod. Updating only the
    // first match left a duplicate behind with the old value, and because a jar
    // only loads when every record naming it is enabled, switching a mod back on
    // silently did nothing: the "toggles revert" report.
    apply_enabled_to_same_mod(&mut manifest, &target, enabled);
    // The file is what Fabric goes by: a switched-off mod is renamed to
    // .jar.disabled, or it would still load from the folder.
    let file_name = target.file_name.trim().to_string();
    if !file_name.is_empty() {
        mods_local::set_enabled(&mods_dir, &file_name, enabled)?;
    }
    write_mod_manifest(&manifest_path, &manifest)?;

    Ok(manifest
        .iter()
        .find(|item| mods_are_same(item, &target))
        .cloned()
        .unwrap_or(ManagedModRecord { enabled, ..target }))
}

#[tauri::command]
fn remove_installed_mod(app: AppHandle, profile_id: String, project_id: String) -> Result<(), String> {
    if let Some(jar_name) = project_id.strip_prefix(mods_commands::FILE_KEY_PREFIX) {
        let mods_dir = profile_mods_dir(&app, &profile_id)?;
        let file = if mods_dir.join(jar_name).is_file() {
            jar_name.to_string()
        } else {
            format!("{jar_name}{}", mods_local::DISABLED_SUFFIX)
        };
        mods_commands::remove_file(&app, &profile_id, &file, "removed")?;
        return Ok(());
    }
    let manifest_path = profile_manifest_path(&app, &profile_id)?;
    let mods_dir = profile_mods_dir(&app, &profile_id)?;
    let mut manifest = read_mod_manifest(&manifest_path)?;
    let initial_len = manifest.len();
    let target = manifest
        .iter()
        .find(|item| record_matches_key(item, &project_id))
        .cloned()
        .ok_or_else(|| "Mod is not installed.".to_string())?;
    // Every record for this mod goes, not just the one whose id the UI sent.
    // Removing only that one left a twin pointing at the jar that was about to be
    // deleted, which then showed up as a mod that had vanished on its own.
    let removed_files: Vec<String> = manifest
        .iter()
        .filter(|item| mods_are_same(item, &target))
        .map(|item| item.file_name.clone())
        .collect();
    manifest.retain(|item| !mods_are_same(item, &target));
    if manifest.len() == initial_len {
        return Err("Mod is not installed.".into());
    }

    // Anything the launcher put in the mods folder itself, which the user never
    // asked to remove.
    let protected = breeze_managed_mod_file_names(&app, &profile_id);

    for file_name in orphaned_mod_files(&manifest, &removed_files) {
        if protected.iter().any(|name| name.eq_ignore_ascii_case(&file_name)) {
            continue;
        }
        let mod_path = mods_dir.join(&file_name);
        if !mod_path.exists() {
            continue;
        }
        // A file name out of the manifest is not trusted to stay inside the
        // folder it claims to be in.
        if !is_inside_dir(&mods_dir, &mod_path) {
            return Err(format!(
                "Refusing to delete {}, which is outside the profile mods folder.",
                mod_path.display()
            ));
        }
        // Into the instance's backups rather than deleted, so a removal can
        // be undone from the file manager.
        let instance_dir = profile_root_dir(&app, &profile_id)?;
        mods_local::move_to_backup(&instance_dir, &mods_dir, &file_name, "removed")?;
    }
    // The same mod switched off is the same mod.
    for file_name in orphaned_mod_files(&manifest, &removed_files) {
        let disabled = format!("{file_name}{}", mods_local::DISABLED_SUFFIX);
        if mods_dir.join(&disabled).is_file() && !protected.iter().any(|name| name.eq_ignore_ascii_case(&file_name)) {
            let instance_dir = profile_root_dir(&app, &profile_id)?;
            mods_local::move_to_backup(&instance_dir, &mods_dir, &disabled, "removed")?;
        }
    }

    write_mod_manifest(&manifest_path, &manifest)?;
    Ok(())
}

/// Copy one installed jar into another version's profile.
///
/// This is the half of "Transfer" that Rust has to do. A locally imported mod
/// exists only as a file on disk, with no project to reinstall from, so the jar
/// itself is copied. Mods that came from Modrinth are reinstalled by the caller
/// against the target version, which is the only way to end up with a build
/// that will actually load.
#[tauri::command]
fn copy_local_mod_to_profile(app: AppHandle, request: CopyLocalModRequest) -> Result<ManagedModRecord, String> {
    let from_profile = request.from_profile_id.trim();
    let to_profile = request.to_profile_id.trim();
    if from_profile.is_empty() || to_profile.is_empty() {
        return Err("A mod needs a version to come from and a version to go to.".into());
    }
    if from_profile.eq_ignore_ascii_case(to_profile) {
        return Err("That mod is already installed in this version.".into());
    }

    copy_mod_between_profiles(
        CopyModPaths {
            source_dir: &profile_mods_dir(&app, from_profile)?,
            source_manifest: &profile_manifest_path(&app, from_profile)?,
            target_dir: &profile_mods_dir(&app, to_profile)?,
            target_manifest: &profile_manifest_path(&app, to_profile)?,
        },
        &request.project_id,
        request.game_version.trim(),
        request.loader.trim(),
        from_profile,
    )
}

/// Where the two profiles keep their jars and their record of them.
struct CopyModPaths<'a> {
    source_dir: &'a Path,
    source_manifest: &'a Path,
    target_dir: &'a Path,
    target_manifest: &'a Path,
}

/// The part of the transfer that touches files, separated from the AppHandle so
/// it can be tested against real directories.
fn copy_mod_between_profiles(
    paths: CopyModPaths<'_>,
    project_id: &str,
    game_version: &str,
    loader: &str,
    from_label: &str,
) -> Result<ManagedModRecord, String> {
    let source_manifest = read_mod_manifest(paths.source_manifest)?;
    let record = source_manifest
        .iter()
        .find(|item| record_matches_key(item, project_id))
        .cloned()
        .ok_or_else(|| "That mod is not installed in the version it would be copied from.".to_string())?;

    // The file name comes out of a manifest on disk, which is editable by hand,
    // so it is sanitized and then checked to still land inside both mods folders.
    // "../../" in a file name would otherwise read and write wherever it pointed.
    let file_name = sanitize_filename(&record.file_name);
    if file_name.trim().is_empty() {
        return Err(format!("{} has no file recorded, so there is nothing to copy.", record.title));
    }
    fs::create_dir_all(paths.target_dir)
        .map_err(|error| format!("Could not create the target version's mods folder: {error}"))?;
    let source_file = paths.source_dir.join(&file_name);
    let target_file = paths.target_dir.join(&file_name);
    if !is_inside_dir(paths.source_dir, &source_file) || !is_inside_dir(paths.target_dir, &target_file) {
        return Err("Refusing to copy a mod whose file name points outside the mods folder.".into());
    }
    if !source_file.is_file() {
        return Err(format!("The jar for {} is no longer on disk.", record.title));
    }

    fs::copy(&source_file, &target_file)
        .map_err(|error| format!("Could not copy {file_name}: {error}"))?;

    let mut target_manifest = read_mod_manifest(paths.target_manifest)?;
    // The copy keeps the record's identity, so a later toggle or removal still
    // finds it, but it says where it came from: the jar was built for a
    // different version and My Mods should not hide that.
    let copied = upsert_managed_mod(
        &mut target_manifest,
        ManagedModRecord {
            game_version: game_version.to_string(),
            loader: loader.to_string(),
            installed_version_name: format!("{} · copied from {}", record.installed_version_name, from_label),
            ..record
        },
    );
    write_mod_manifest(paths.target_manifest, &target_manifest)?;
    Ok(copied)
}

// ---------------------------------------------------------------------------
// Resource packs, shader packs and datapacks (Section 12)
//
// Packs use a four state lifecycle rather than the mods' simple enabled flag:
//
//   Downloaded -> the file is on disk in the staging folder only. Minecraft
//                 cannot see it. This is where a fresh download lands.
//   Installed  -> the file has been moved into the folder Minecraft reads
//                 (resourcepacks / shaderpacks / datapacks).
//   Enabled    -> installed *and* listed in options.txt, so the game actually
//                 applies it. This is the only state the game acts on.
//   Disabled   -> installed but deliberately left out of options.txt.
//
// Installed and Disabled are the same thing on disk. They are kept distinct
// because "I have not decided yet" and "I decided no" are different to a user,
// and only Disabled survives a bulk enable-all.
// ---------------------------------------------------------------------------

const PACK_STATE_DOWNLOADED: &str = "downloaded";
const PACK_STATE_INSTALLED: &str = "installed";
const PACK_STATE_ENABLED: &str = "enabled";
const PACK_STATE_DISABLED: &str = "disabled";

#[derive(Serialize, Deserialize, Clone)]
#[serde(rename_all = "camelCase")]
struct ManagedPackRecord {
    project_id: String,
    project_slug: String,
    title: String,
    summary: Option<String>,
    icon_url: Option<String>,
    /// "resourcepack" | "shaderpack" | "datapack"
    pack_type: String,
    game_version: String,
    installed_version_id: String,
    installed_version_name: String,
    file_name: String,
    state: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct DownloadPackRequest {
    profile_id: String,
    game_version: String,
    pack_type: String,
    project_id: String,
    project_slug: String,
    title: String,
    summary: Option<String>,
    icon_url: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct ImportCustomPackRequest {
    profile_id: String,
    game_version: String,
    pack_type: String,
    file_name: String,
    /// Path produced by `stage_import_chunk`.
    ///
    /// Packs used to arrive as a `Vec<u8>` over IPC, which serialises every
    /// byte as a JSON number. That is the same failure the modpack importer
    /// already hit: past roughly 100MB the payload is dropped and the command
    /// reports receiving nothing. High resolution texture packs are routinely
    /// larger than that, so the naive path was never going to work for the
    /// files users actually import.
    staged_path: String,
}

fn pack_manifest_path(app: &AppHandle, profile_id: &str) -> Result<PathBuf, String> {
    Ok(profile_root_dir(app, profile_id)?.join("packs-state.json"))
}

/// Where a not-yet-installed pack file waits. Dot-prefixed so Minecraft's own
/// The one copy of a pack file, shared by every version.
///
/// Packs used to live inside each instance, so a 300MB texture pack installed
/// for four versions was 1.2GB on disk and four downloads. The file now lives
/// here once and each instance links to it.
fn pack_library_dir(pack_type: &str) -> Result<PathBuf, String> {
    let dir = breeze_home_dir()?.join("packs").join(normalize_pack_type(pack_type));
    fs::create_dir_all(&dir).map_err(|e| format!("Could not create the pack library: {e}"))?;
    Ok(dir)
}

/// Streaming SHA-1 of a file, to tell whether two packs are the same bytes.
fn file_sha1(path: &Path) -> Option<String> {
    use sha1::{Digest, Sha1};
    use std::io::Read;
    let mut file = fs::File::open(path).ok()?;
    let mut hasher = Sha1::new();
    let mut buf = vec![0u8; 64 * 1024];
    loop {
        let n = file.read(&mut buf).ok()?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    Some(format!("{:x}", hasher.finalize()))
}

/// The library name for content with this hash, and whether it is already there.
///
/// A library file is never overwritten. Every version that links it keeps the
/// exact bytes it installed, and writing into a hard-linked file would change
/// all of them at once, including under a running game. So the same name with
/// the same content is reused, and the same name with different content (two
/// packs both called pack.zip, or a new build of a pack under its old name)
/// gets a name of its own with the content's hash in it.
fn library_name_for(library: &Path, preferred: &str, sha1: &str) -> (String, bool) {
    let preferred_path = library.join(preferred);
    if !preferred_path.exists() {
        return (preferred.to_string(), false);
    }
    if file_sha1(&preferred_path).as_deref() == Some(sha1) {
        return (preferred.to_string(), true);
    }
    let (stem, ext) = match preferred.rsplit_once('.') {
        Some((stem, ext)) => (stem.to_string(), format!(".{ext}")),
        None => (preferred.to_string(), String::new()),
    };
    let short = &sha1[..sha1.len().min(8)];
    for attempt in 0..100 {
        let name = if attempt == 0 {
            format!("{stem}-{short}{ext}")
        } else {
            format!("{stem}-{short}-{attempt}{ext}")
        };
        let path = library.join(&name);
        if !path.exists() {
            return (name, false);
        }
        if file_sha1(&path).as_deref() == Some(sha1) {
            return (name, true);
        }
    }
    (format!("{stem}-{sha1}{ext}"), false)
}

/// Put downloaded bytes into the library without touching an existing file.
/// Returns the name they are stored under.
fn store_bytes_in_library(library: &Path, preferred: &str, bytes: &[u8]) -> Result<String, String> {
    let sha1 = java_runtime::sha1_hex(bytes);
    let (name, present) = library_name_for(library, preferred, &sha1);
    if present {
        return Ok(name);
    }
    let target = library.join(&name);
    let partial = library.join(format!(".{name}.part"));
    fs::write(&partial, bytes).map_err(|e| format!("Could not write pack file: {e}"))?;
    fs::rename(&partial, &target).map_err(|e| {
        let _ = fs::remove_file(&partial);
        format!("Could not store the pack file: {e}")
    })?;
    Ok(name)
}

/// Move a file into the library without touching an existing file. The source
/// is consumed either way. Returns the name it is stored under.
fn store_file_in_library(library: &Path, preferred: &str, source: &Path) -> Result<String, String> {
    let sha1 = file_sha1(source).ok_or_else(|| "Could not read the pack file.".to_string())?;
    let (name, present) = library_name_for(library, preferred, &sha1);
    if present {
        let _ = fs::remove_file(source);
        return Ok(name);
    }
    let target = library.join(&name);
    if fs::rename(source, &target).is_err() {
        // A move across volumes: copy to a temporary name, then rename onto a
        // name that does not exist yet, so nothing existing is ever truncated.
        let partial = library.join(format!(".{name}.part"));
        fs::copy(source, &partial).map_err(|e| format!("Could not store the pack file: {e}"))?;
        fs::rename(&partial, &target).map_err(|e| {
            let _ = fs::remove_file(&partial);
            format!("Could not store the pack file: {e}")
        })?;
        let _ = fs::remove_file(source);
    }
    Ok(name)
}

/// Put a second name on the same bytes.
///
/// A hard link costs nothing and needs no privilege on Windows, unlike a
/// symlink. If the filesystem refuses (a different volume, or a filesystem
/// without links) the file is copied, which is still correct, just larger.
fn link_or_copy(source: &Path, target: &Path) -> Result<(), String> {
    if target.exists() {
        let _ = fs::remove_file(target);
    }
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Could not create {}: {e}", parent.display()))?;
    }
    if fs::hard_link(source, target).is_ok() {
        return Ok(());
    }
    fs::copy(source, target)
        .map(|_| ())
        .map_err(|e| format!("Could not install {}: {e}", target.display()))
}

/// Whether any record other than the one being removed still lists this pack
/// file, so the library copy has to stay.
///
/// Other records in the same version count too. And anything that cannot be
/// read counts as "still used": deleting a pack because one version's state
/// file was unreadable would lose it for that version for good.
fn pack_file_referenced(app: &AppHandle, file_name: &str, removing_profile: &str, removing_project: &str) -> bool {
    let Ok(instances) = breeze_home_dir().map(|home| home.join("instances")) else { return true };
    let Ok(entries) = fs::read_dir(instances) else { return true };
    let mut manifests = Vec::new();
    for entry in entries.flatten() {
        let Some(profile) = entry.file_name().to_str().map(str::to_string) else { continue };
        let records = pack_manifest_path(app, &profile).and_then(|path| read_pack_manifest(&path));
        manifests.push((profile, records));
    }
    pack_listed(&manifests, file_name, removing_profile, removing_project)
}

/// The decision behind `pack_file_referenced`, kept free of the filesystem so it
/// can be tested.
fn pack_listed(
    manifests: &[(String, Result<Vec<ManagedPackRecord>, String>)],
    file_name: &str,
    removing_profile: &str,
    removing_project: &str,
) -> bool {
    for (profile, records) in manifests {
        let Ok(records) = records else { return true };
        let listed = records.iter().any(|record| {
            record.file_name.eq_ignore_ascii_case(file_name)
                && !(profile == removing_profile && record.project_id == removing_project)
        });
        if listed {
            return true;
        }
    }
    false
}

/// Move a profile's own copies into the shared library.
///
/// Runs when a profile's packs are listed, so an install made by an older
/// build stops being a second copy of the same file without the user doing
/// anything. A file already in the library is not overwritten; the duplicate
/// is simply dropped.
fn migrate_packs_to_library(app: &AppHandle, profile_id: &str) -> Result<(), String> {
    let manifest_path = pack_manifest_path(app, profile_id)?;
    let mut records = read_pack_manifest(&manifest_path)?;
    let mut renamed = false;
    for record in records.iter_mut() {
        let library_dir = pack_library_dir(&record.pack_type)?;

        // A pack an older build left in this instance's own staging folder.
        let staged = profile_root_dir(app, profile_id)?
            .join(".breeze-packs")
            .join(normalize_pack_type(&record.pack_type))
            .join(&record.file_name);
        if staged.is_file() {
            let name = store_file_in_library(&library_dir, &record.file_name, &staged)?;
            if name != record.file_name {
                record.file_name = name;
                renamed = true;
            }
            continue;
        }

        // A pack an older build installed straight into the game folder. It
        // stays where Minecraft expects it and the library gets a link to the
        // same bytes. If another pack already owns that name in the library,
        // this one takes a name of its own, in both places.
        let installed_dir = pack_install_dir(app, profile_id, &record.pack_type)?;
        let installed = installed_dir.join(&record.file_name);
        if !installed.is_file() {
            continue;
        }
        let Some(sha1) = file_sha1(&installed) else { continue };
        let (name, present) = library_name_for(&library_dir, &record.file_name, &sha1);
        if !present {
            link_or_copy(&installed, &library_dir.join(&name))?;
        }
        if name != record.file_name {
            fs::rename(&installed, installed_dir.join(&name))
                .map_err(|e| format!("Could not rename {}: {e}", record.file_name))?;
            record.file_name = name;
            renamed = true;
        }
    }
    if renamed {
        write_pack_manifest(&manifest_path, &records)?;
        sync_resource_pack_options(app, profile_id)?;
    }
    Ok(())
}

/// The folder Minecraft itself reads for this pack type.
fn pack_install_dir(app: &AppHandle, profile_id: &str, pack_type: &str) -> Result<PathBuf, String> {
    let sub = match normalize_pack_type(pack_type).as_str() {
        "shaderpack" => "shaderpacks",
        "datapack" => "datapacks",
        _ => "resourcepacks",
    };
    Ok(profile_root_dir(app, profile_id)?.join(sub))
}

fn normalize_pack_type(pack_type: &str) -> String {
    match pack_type.trim().to_ascii_lowercase().as_str() {
        "shader" | "shaderpack" | "shaderpacks" => "shaderpack".to_string(),
        "datapack" | "datapacks" | "data" => "datapack".to_string(),
        _ => "resourcepack".to_string(),
    }
}

fn read_pack_manifest(path: &Path) -> Result<Vec<ManagedPackRecord>, String> {
    if !path.exists() {
        return Ok(Vec::new());
    }
    let content = fs::read_to_string(path).map_err(|e| format!("Could not read pack state: {e}"))?;
    serde_json::from_str(&content).map_err(|e| format!("Could not parse pack state: {e}"))
}

fn write_pack_manifest(path: &Path, manifest: &[ManagedPackRecord]) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Could not create Breeze profile state: {e}"))?;
    }
    let content = serde_json::to_string_pretty(manifest)
        .map_err(|e| format!("Could not serialize pack state: {e}"))?;
    write_file_atomic(path, &content).map_err(|e| format!("Could not write pack state: {e}"))
}

/// Rewrite the `resourcePacks` line in options.txt to exactly the enabled set.
///
/// Minecraft stores this as a JSON array of strings, newest-applied last, and
/// always keeps "vanilla" first. Every other key in the file is preserved
/// untouched, because the user's video and control settings live there too.
fn sync_resource_pack_options(app: &AppHandle, profile_id: &str) -> Result<(), String> {
    let manifest = read_pack_manifest(&pack_manifest_path(app, profile_id)?)?;
    let enabled: Vec<String> = manifest
        .iter()
        .filter(|p| p.state == PACK_STATE_ENABLED && normalize_pack_type(&p.pack_type) == "resourcepack")
        .map(|p| format!("file/{}", p.file_name))
        .collect();

    let options_path = profile_root_dir(app, profile_id)?.join("options.txt");
    let existing = if options_path.exists() {
        fs::read_to_string(&options_path).map_err(|e| format!("Could not read options.txt: {e}"))?
    } else {
        "lang:en_us\ntutorialStep:none\n".to_string()
    };

    let updated = rewrite_resource_pack_options(&existing, &enabled)?;
    fs::write(&options_path, updated).map_err(|e| format!("Could not write options.txt: {e}"))
}

/// The pure half of the options.txt rewrite, split out so it can be tested
/// without a profile on disk. Every key other than the two resource pack lines
/// is passed through byte for byte.
fn rewrite_resource_pack_options(existing: &str, enabled: &[String]) -> Result<String, String> {
    let mut entries = vec!["vanilla".to_string()];
    entries.extend_from_slice(enabled);
    let serialized = serde_json::to_string(&entries)
        .map_err(|e| format!("Could not serialize resource pack list: {e}"))?;

    let mut lines: Vec<String> = Vec::new();
    let mut replaced = false;
    for line in existing.lines() {
        if line.starts_with("resourcePacks:") {
            lines.push(format!("resourcePacks:{serialized}"));
            replaced = true;
        } else if line.starts_with("incompatibleResourcePacks:") {
            // Stale entries here make the game show a scary warning for packs
            // the user just enabled on purpose.
            lines.push("incompatibleResourcePacks:[]".to_string());
        } else {
            lines.push(line.to_string());
        }
    }
    if !replaced {
        lines.push(format!("resourcePacks:{serialized}"));
    }
    Ok(format!("{}\n", lines.join("\n")))
}

/// JVM options that are experimental, and therefore only accepted after
/// `-XX:+UnlockExperimentalVMOptions` has already appeared on the command line.
/// The JVM refuses to start otherwise, with "The unlock option must precede
/// <flag>", so ordering is not cosmetic.
const EXPERIMENTAL_VM_OPTIONS: &[&str] = &[
    "G1NewSizePercent",
    "G1MaxNewSizePercent",
    "G1MixedGCLiveThresholdPercent",
    "ZGenerational",
];

/// Which collector profile suits a given machine.
///
/// One 12GB cliff was too blunt. A 4-core laptop with 8GB and an integrated GPU
/// and a 12-core desktop with 64GB want genuinely different collectors, not the
/// same flags with a different heap number.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum GcProfile {
    /// Small heap, few cores, memory is scarce. Footprint matters more than
    /// pause length, and GC threads must not compete with the render thread.
    Constrained,
    /// The common case. G1 tuned for a game workload.
    Balanced,
    /// Large heap where G1's pauses start to grow but ZGC's overhead is still
    /// not worth paying.
    LargeHeap,
    /// Heap big enough that G1 pause times become visible. Generational ZGC's
    /// concurrent collection pays for its footprint and CPU cost here.
    Throughput,
}

/// Pick a collector profile from the actual machine, not just the heap size.
///
/// `total_ram_mb` matters independently of heap: on a machine with integrated
/// graphics the GPU takes its VRAM from the same pool, so a large heap on a
/// small system is worse than the heap number alone suggests.
///
/// `cpus` matters because every concurrent collector runs GC work on threads
/// that compete with Minecraft's render and chunk-build threads. On 4 logical
/// cores that competition costs more frames than the pause reduction saves.
fn select_gc_profile(heap_mb: u32, total_ram_mb: u64, cpus: usize) -> GcProfile {
    // Under 3GB of heap, or on a machine that simply has little memory, the
    // priority is staying small. ZGC and heavily concurrent G1 both lose here.
    if heap_mb < 3072 || total_ram_mb <= 8192 {
        return GcProfile::Constrained;
    }
    // Generational ZGC needs both a large heap and enough cores to run its
    // concurrent phases without starving rendering. Requiring 8 logical cores
    // prevents a 4-core machine with lots of RAM from picking it and losing
    // frames to GC threads.
    if heap_mb >= 12288 && cpus >= 8 {
        return GcProfile::Throughput;
    }
    if heap_mb >= 8192 {
        return GcProfile::LargeHeap;
    }
    GcProfile::Balanced
}

/// Garbage collector flags.
///
/// Rewritten 2026-08-02 against researched sources rather than the widely
/// copied Minecraft flag lists. Several flags that were here before are gone,
/// and the reasoning is recorded so they do not creep back:
///
///   -XX:+ParallelRefProcEnabled   removed. Default for G1 since JDK 11
///                                 (JDK-8205043). Emitting it does nothing.
///   -XX:+UseStringDeduplication   removed. It targets heaps full of duplicate
///                                 Strings (servers parsing JSON or SQL). A
///                                 client heap is chunk, mesh and primitive
///                                 data, so the dedup thread burns CPU for no
///                                 return.
///   -XX:G1NewSizePercent=30       removed. These are Aikar's SERVER values.
///   -XX:G1MaxNewSizePercent=40    They pin a large young generation, which
///                                 effectively overrides MaxGCPauseMillis and
///                                 produces exactly the long young pauses that
///                                 show up as stutter on a client.
///   -XX:ParallelGCThreads cap     removed. Wrong lever: those threads run
///                                 while the world is ALREADY stopped, so they
///                                 cannot steal frames, and capping them makes
///                                 each pause LONGER. ConcGCThreads is the
///                                 count that competes with rendering.
///
/// Never add: -XX:+DisableExplicitGC (LWJGL's DirectByteBuffers are reclaimed
/// only via System.gc(); disabling it leaks native memory),
/// -XX:+AlwaysPreTouch (commits all of -Xmx to RSS at startup, which on a
/// shared-memory iGPU denies that RAM to the GPU), -XX:+UseCompactObjectHeaders
/// (JDK 24+; the JVM refuses to start on 21), -XX:TieredStopAtLevel=1 (caps JIT
/// at C1: fine for a CLI tool, ruinous for a multi-hour game session),
/// -XX:+UseLargePages (needs a privilege Windows Home cannot grant),
/// -XX:+UseNUMA (no-op on single-socket consumer hardware),
/// -XX:+UseShenandoah (a build-time opt-in, absent from some JDK builds).
fn gc_jvm_args_for(profile: GcProfile, cpus: usize, max_ram_mb: u32) -> Vec<String> {
    let mut args: Vec<String> = Vec::new();

    // G1 for every client profile.
    //
    // ZGC was previously used for large heaps. That choice came from SERVER
    // benchmarks. On a client it is the wrong trade for three reasons: it does
    // not support compressed object pointers, so every reference in a
    // pointer-dense Minecraft heap becomes 64-bit and the live set grows; its
    // load barrier executes on every reference read, including on the render
    // thread; and its concurrent threads compete for cores the renderer needs.
    // The one published client-side benchmark reports a significant FPS
    // regression from ZGC. A server has spare cores and no render thread, so
    // those numbers do not transfer.
    //
    // This also removes a subtle version hazard: on Java 21 a bare
    // -XX:+UseZGC selects the NON-generational collector, which re-traces the
    // whole live set every cycle. It needed -XX:+ZGenerational, a flag that is
    // deprecated in 23 and removed in 24, so it would have started warning and
    // then failing as JDKs advance.
    args.push("-XX:+UseG1GC".into());

    // Decouples safepoint duration from disk I/O in the JVM's temp directory.
    // Costs a launcher nothing (it only disables the hsperfdata file that jps
    // reads) and targets tail latency, which is what 1% lows are.
    args.push("-XX:+PerfDisableSharedMem".into());

    // Pause target.
    //
    // G1's default is 200ms, a throughput-oriented default: 200ms is thirteen
    // dropped frames at 60fps, and dropped frames are what a player perceives
    // as stutter. Lower is better for frame pacing only up to a point, because
    // G1 chases the target by shrinking the young generation, and too small a
    // nursery collects so often that fixed per-collection overhead dominates.
    // 50ms is a deliberate middle: under a noticeable hitch, well above the
    // thrash threshold.
    //
    // The previous code had this backwards, giving the WEAKEST hardware the
    // LONGEST target (200ms) on the theory that fewer collections cost less.
    // That optimises total GC time, which is a server metric, not frame pacing.
    args.push("-XX:MaxGCPauseMillis=50".into());

    // Concurrent marking threads. This is the count that genuinely competes
    // with the render thread, so it is capped hard on small machines: on four
    // cores, one GC thread is a quarter of the machine.
    let conc = match profile {
        GcProfile::Constrained => 1,
        GcProfile::Balanced => (cpus / 4).clamp(1, 2),
        GcProfile::LargeHeap | GcProfile::Throughput => (cpus / 4).clamp(2, 4),
    };
    args.push(format!("-XX:ConcGCThreads={conc}"));

    // Start concurrent marking with enough headroom that a chunk-generation
    // allocation spike cannot outrun the marking cycle and force a full
    // stop-the-world collection.
    args.push("-XX:InitiatingHeapOccupancyPercent=20".into());

    // Tolerate more garbage in old gen before running mixed collections. The
    // server value of 5% forces frequent mixed GCs to reclaim memory a server
    // needs; a client would rather keep the frames and spend the RAM.
    args.push("-XX:G1HeapWastePercent=20".into());
    args.push("-XX:G1MixedGCCountTarget=3".into());

    // Region size.
    //
    // Minecraft allocates large mesh and texture-atlas buffers. Any allocation
    // over half a region is "humongous" and goes straight to old gen, where it
    // is expensive to reclaim. With 4MB regions the threshold is only 2MB,
    // which those buffers cross routinely, so a small heap wants LARGER
    // regions, not smaller. The previous code had this inverted.
    let region_mb = if max_ram_mb >= 12288 {
        32
    } else if max_ram_mb >= 6144 {
        16
    } else {
        8
    };
    args.push(format!("-XX:G1HeapRegionSize={region_mb}M"));

    // Bound LWJGL and Sodium's off-heap growth. Left unset this defaults to the
    // full heap size, so a native-memory leak presents as unexplained system
    // RAM exhaustion instead of a clear OutOfMemoryError naming direct buffers.
    args.push(format!("-XX:MaxDirectMemorySize={}M", (max_ram_mb / 4).max(512)));

    args
}

#[cfg(test)]
mod gc_args_tests {
    use super::*;

    /// The flags that research showed to be wrong for a CLIENT must never come
    /// back. Each one is here because it was actually present and was removed
    /// for a specific reason, not because it merely sounds bad.
    #[test]
    fn cargo_cult_flags_are_absent() {
        let banned = [
            // No-op: G1 default since JDK 11.
            "ParallelRefProcEnabled",
            // Wrong workload: a client heap is not full of duplicate Strings.
            "UseStringDeduplication",
            // Aikar's SERVER young-gen values; they override the pause target.
            "G1NewSizePercent",
            "G1MaxNewSizePercent",
            // LWJGL direct buffers are only reclaimed via System.gc().
            "DisableExplicitGC",
            // Commits all of -Xmx to RSS, starving a shared-memory iGPU.
            "AlwaysPreTouch",
            // JDK 24+. The JVM refuses to start on 21.
            "UseCompactObjectHeaders",
            // Caps JIT at C1: ruinous for a multi-hour session.
            "TieredStopAtLevel",
            // Needs a privilege Windows Home cannot grant.
            "UseLargePages",
            // No-op on single-socket consumer hardware.
            "UseNUMA",
            // Build-time opt-in; absent from some JDK builds.
            "UseShenandoah",
        ];
        for p in [GcProfile::Constrained, GcProfile::Balanced, GcProfile::LargeHeap, GcProfile::Throughput] {
            for ram in [2048u32, 4096, 8192, 16384, 32768] {
                let args = gc_jvm_args_for(p, 8, ram);
                for b in banned {
                    assert!(
                        !args.iter().any(|a| a.contains(b)),
                        "{p:?} at {ram}MB emitted banned flag containing {b}: {args:?}",
                    );
                }
            }
        }
    }

    /// ZGC was removed for clients. Its load barrier taxes every reference read
    /// on the render thread, it has no compressed oops, and on Java 21 a bare
    /// -XX:+UseZGC silently selects the non-generational collector.
    #[test]
    fn no_profile_selects_zgc() {
        for p in [GcProfile::Constrained, GcProfile::Balanced, GcProfile::LargeHeap, GcProfile::Throughput] {
            let args = gc_jvm_args_for(p, 16, 16384);
            assert!(!args.iter().any(|a| a.contains("ZGC") || a.contains("ZGenerational")),
                "{p:?} still selects ZGC: {args:?}");
            assert!(args.iter().any(|a| a == "-XX:+UseG1GC"), "{p:?} must use G1");
        }
    }

    /// Every emitted flag must be one the JVM will accept without
    /// -XX:+UnlockExperimentalVMOptions. Nothing here is experimental any more,
    /// so that unlock flag is no longer emitted at all.
    #[test]
    fn no_experimental_flags_and_no_unlock_needed() {
        for p in [GcProfile::Constrained, GcProfile::Balanced, GcProfile::LargeHeap, GcProfile::Throughput] {
            let args = gc_jvm_args_for(p, 8, 8192);
            assert!(!args.iter().any(|a| a.contains("UnlockExperimentalVMOptions")),
                "{p:?} emits the unlock flag but nothing needs it: {args:?}");
            for e in EXPERIMENTAL_VM_OPTIONS {
                assert!(!args.iter().any(|a| a.contains(e)),
                    "{p:?} emits experimental option {e} with no unlock flag: {args:?}");
            }
        }
    }

    /// The pause target must be short enough to matter for frame pacing. G1's
    /// 200ms default is thirteen dropped frames at 60fps; the old code made
    /// this worse by giving the weakest hardware the longest target.
    #[test]
    fn pause_target_suits_frame_pacing_on_every_tier() {
        for p in [GcProfile::Constrained, GcProfile::Balanced, GcProfile::LargeHeap, GcProfile::Throughput] {
            let args = gc_jvm_args_for(p, 4, 4096);
            let pause: u32 = args.iter()
                .find(|a| a.starts_with("-XX:MaxGCPauseMillis="))
                .expect("a pause target must be set")
                .trim_start_matches("-XX:MaxGCPauseMillis=")
                .parse().unwrap();
            assert!(pause <= 100, "{p:?} pause target {pause}ms is too long for frame pacing");
            // Below ~20ms G1 shrinks the nursery so far that per-collection
            // overhead dominates and total GC work rises.
            assert!(pause >= 20, "{p:?} pause target {pause}ms will thrash the young gen");
        }
    }

    /// ConcGCThreads is the count that competes with the render thread, so it
    /// must be capped on small machines. ParallelGCThreads must NOT be capped:
    /// those run in an already-stopped world, so capping only lengthens pauses.
    #[test]
    fn concurrent_threads_capped_but_parallel_threads_untouched() {
        let low = gc_jvm_args_for(GcProfile::Constrained, 4, 4096);
        let conc: usize = low.iter()
            .find(|a| a.starts_with("-XX:ConcGCThreads="))
            .expect("ConcGCThreads must be set")
            .trim_start_matches("-XX:ConcGCThreads=")
            .parse().unwrap();
        assert_eq!(conc, 1, "a 4-core machine must run exactly one concurrent GC thread");

        for p in [GcProfile::Constrained, GcProfile::Balanced, GcProfile::LargeHeap, GcProfile::Throughput] {
            let args = gc_jvm_args_for(p, 4, 8192);
            assert!(!args.iter().any(|a| a.starts_with("-XX:ParallelGCThreads=")),
                "{p:?} caps ParallelGCThreads, which only makes pauses longer: {args:?}");
        }
    }

    /// Small heaps need LARGER regions, not smaller. Minecraft's mesh and atlas
    /// buffers exceed half of a 4MB region, so they are treated as humongous
    /// and promoted straight to old gen where they are costly to reclaim.
    #[test]
    fn small_heaps_get_larger_regions_not_smaller() {
        let region = |ram| -> u32 {
            gc_jvm_args_for(GcProfile::Balanced, 8, ram).into_iter()
                .find(|a| a.starts_with("-XX:G1HeapRegionSize="))
                .unwrap()
                .trim_start_matches("-XX:G1HeapRegionSize=")
                .trim_end_matches('M')
                .parse().unwrap()
        };
        assert!(region(2048) >= 8, "a 2GB heap must not use tiny regions");
        assert!(region(8192) >= 16);
        assert!(region(16384) >= 32);
        // Monotonic: more heap never means smaller regions.
        assert!(region(2048) <= region(8192) && region(8192) <= region(16384));
    }

    /// Off-heap growth must be bounded, or a native leak looks like unexplained
    /// system RAM exhaustion instead of a clear OutOfMemoryError.
    #[test]
    fn direct_memory_is_bounded_and_never_trivially_small() {
        for ram in [1024u32, 2048, 4096, 16384] {
            let args = gc_jvm_args_for(GcProfile::Balanced, 8, ram);
            let mb: u32 = args.iter()
                .find(|a| a.starts_with("-XX:MaxDirectMemorySize="))
                .expect("MaxDirectMemorySize must be set")
                .trim_start_matches("-XX:MaxDirectMemorySize=")
                .trim_end_matches('M')
                .parse().unwrap();
            assert!(mb >= 512, "at {ram}MB heap, direct memory {mb}M is too small for LWJGL");
            assert!(mb <= ram, "direct memory {mb}M should not exceed the heap");
        }
    }

    #[test]
    fn profile_selection_matches_the_machine() {
        // The user's dev machine: i5-1135G7, 8 threads, 8GB, Iris Xe.
        assert_eq!(select_gc_profile(4096, 8192, 8), GcProfile::Constrained);
        assert_eq!(select_gc_profile(2048, 8192, 8), GcProfile::Constrained);
        // A small heap on a big machine is still constrained by the heap.
        assert_eq!(select_gc_profile(2048, 65536, 16), GcProfile::Constrained);
        assert_eq!(select_gc_profile(6144, 16384, 8), GcProfile::Balanced);
        assert_eq!(select_gc_profile(8192, 16384, 8), GcProfile::LargeHeap);
        assert_eq!(select_gc_profile(16384, 32768, 16), GcProfile::Throughput);
        // Big heap but few cores must not get the high-thread profile.
        assert_eq!(select_gc_profile(16384, 32768, 4), GcProfile::LargeHeap);
    }
}
#[cfg(test)]
mod pack_options_tests {
    use super::*;

    #[test]
    fn keeps_unrelated_settings_untouched() {
        let before = "lang:en_us\nfov:0.75\nguiScale:2\ntutorialStep:none\n";
        let after = rewrite_resource_pack_options(before, &[]).unwrap();
        for key in ["lang:en_us", "fov:0.75", "guiScale:2", "tutorialStep:none"] {
            assert!(after.contains(key), "lost {key}");
        }
    }

    #[test]
    fn appends_the_line_when_absent() {
        let after = rewrite_resource_pack_options("fov:0.75\n", &["file/a.zip".into()]).unwrap();
        assert!(after.contains(r#"resourcePacks:["vanilla","file/a.zip"]"#));
    }

    #[test]
    fn replaces_rather_than_duplicates() {
        let before = "resourcePacks:[\"vanilla\",\"file/old.zip\"]\nfov:0.75\n";
        let after = rewrite_resource_pack_options(before, &["file/new.zip".into()]).unwrap();
        assert_eq!(after.matches("resourcePacks:").count(), 1);
        assert!(after.contains("file/new.zip"));
        assert!(!after.contains("file/old.zip"));
    }

    #[test]
    fn vanilla_stays_first_and_order_is_kept() {
        let packs = vec!["file/a.zip".to_string(), "file/b.zip".to_string()];
        let after = rewrite_resource_pack_options("", &packs).unwrap();
        assert!(after.contains(r#"resourcePacks:["vanilla","file/a.zip","file/b.zip"]"#));
    }

    #[test]
    fn clears_stale_incompatible_list() {
        let before = "incompatibleResourcePacks:[\"file/old.zip\"]\n";
        let after = rewrite_resource_pack_options(before, &[]).unwrap();
        assert!(after.contains("incompatibleResourcePacks:[]"));
        assert!(!after.contains("file/old.zip"));
    }

    #[test]
    fn empty_set_leaves_only_vanilla() {
        let after = rewrite_resource_pack_options("fov:0.75\n", &[]).unwrap();
        assert!(after.contains(r#"resourcePacks:["vanilla"]"#));
    }

    #[test]
    fn names_with_quotes_are_escaped_not_injected() {
        let after = rewrite_resource_pack_options("", &[r#"file/we"ird.zip"#.into()]).unwrap();
        let line = after.lines().find(|l| l.starts_with("resourcePacks:")).unwrap();
        let parsed: Vec<String> =
            serde_json::from_str(line.trim_start_matches("resourcePacks:")).unwrap();
        assert_eq!(parsed, vec!["vanilla".to_string(), r#"file/we"ird.zip"#.to_string()]);
    }

    #[test]
    fn pack_type_normalization_covers_the_aliases() {
        for alias in ["shader", "shaderpack", "SHADERPACKS"] {
            assert_eq!(normalize_pack_type(alias), "shaderpack");
        }
        for alias in ["datapack", "data", "Datapacks"] {
            assert_eq!(normalize_pack_type(alias), "datapack");
        }
        // Anything unrecognised falls back to the common case rather than erroring.
        for alias in ["resourcepack", "texture", "", "nonsense"] {
            assert_eq!(normalize_pack_type(alias), "resourcepack");
        }
    }
}

/// Make the profile's pack folder match `state`.
///
/// The file itself stays in the shared library. Installing puts a link to it in
/// the folder Minecraft reads; uninstalling removes that link only, so every
/// other version that uses the same pack keeps working and the bytes are not
/// downloaded again.
fn move_pack_file(
    app: &AppHandle,
    profile_id: &str,
    record: &ManagedPackRecord,
    to_installed: bool,
) -> Result<(), String> {
    let library = pack_library_dir(&record.pack_type)?.join(&record.file_name);
    let installed = pack_install_dir(app, profile_id, &record.pack_type)?.join(&record.file_name);

    if to_installed {
        if !library.is_file() {
            // Nothing to link. An older build may have left the only copy in
            // the profile, in which case it is already where it belongs.
            return if installed.is_file() {
                Ok(())
            } else {
                Err(format!("{} is missing from the pack library. Download it again.", record.file_name))
            };
        }
        link_or_copy(&library, &installed)
    } else {
        if installed.is_file() {
            fs::remove_file(&installed).map_err(|e| format!("Could not remove the pack from this version: {e}"))?;
        }
        Ok(())
    }
}

#[tauri::command]
fn list_installed_packs(app: AppHandle, profile_id: String) -> Result<Vec<ManagedPackRecord>, String> {
    // Packs installed by an older build sit inside the instance; moving them
    // into the shared library here means the first visit to the page reclaims
    // the duplicates.
    let _ = migrate_packs_to_library(&app, &profile_id);
    read_pack_manifest(&pack_manifest_path(&app, &profile_id)?)
}

#[tauri::command]
async fn download_modrinth_pack(
    app: AppHandle,
    request: DownloadPackRequest,
) -> Result<ManagedPackRecord, String> {
    let client = http_client()?;
    let pack_type = normalize_pack_type(&request.pack_type);

    // Packs have no mod loader, so the loader-aware fallback used for mods
    // would filter every version out. Query by game version only.
    let versions = fetch_pack_versions(&client, &request.project_id, &request.game_version).await?;
    let version = versions
        .into_iter()
        .max_by_key(|v| v.featured as u8)
        .ok_or_else(|| format!("No {} release found for Minecraft {}.", pack_type, request.game_version))?;

    let file = version
        .files
        .iter()
        .find(|f| f.primary)
        .cloned()
        .or_else(|| version.files.first().cloned())
        .ok_or_else(|| "Modrinth did not return a downloadable file.".to_string())?;

    let library = pack_library_dir(&pack_type)?;

    let bytes = client
        .get(&file.url)
        .send()
        .await
        .map_err(|e| format!("Could not download pack: {e}"))?
        .bytes()
        .await
        .map_err(|e| format!("Could not read pack bytes: {e}"))?;

    let file_name = store_bytes_in_library(&library, &sanitize_filename(&file.filename), &bytes)?;

    let manifest_path = pack_manifest_path(&app, &request.profile_id)?;
    let mut manifest = read_pack_manifest(&manifest_path)?;
    manifest.retain(|p| p.project_id != request.project_id);

    let record = ManagedPackRecord {
        project_id: request.project_id,
        project_slug: request.project_slug,
        title: request.title,
        summary: request.summary,
        icon_url: request.icon_url,
        pack_type,
        game_version: request.game_version,
        installed_version_id: version.id,
        installed_version_name: if version.version_number.is_empty() {
            version.name
        } else {
            version.version_number
        },
        file_name,
        state: PACK_STATE_DOWNLOADED.into(),
    };
    manifest.push(record.clone());
    write_pack_manifest(&manifest_path, &manifest)?;
    Ok(record)
}

#[tauri::command]
fn import_custom_pack(
    app: AppHandle,
    request: ImportCustomPackRequest,
) -> Result<ManagedPackRecord, String> {
    let pack_type = normalize_pack_type(&request.pack_type);
    let file_name = sanitize_filename(&request.file_name);
    let lower = file_name.to_ascii_lowercase();
    if !lower.ends_with(".zip") {
        return Err("Packs must be .zip files.".into());
    }

    let source = PathBuf::from(&request.staged_path);
    if !source.exists() {
        return Err("The staged pack file is missing. Try importing it again.".into());
    }

    // Check the zip magic by reading four bytes rather than loading the whole
    // archive, which for a large texture pack would mean holding hundreds of
    // megabytes in memory just to validate a header.
    {
        use std::io::Read;
        let mut head = [0u8; 4];
        let mut f = fs::File::open(&source)
            .map_err(|e| format!("Could not open the staged pack: {e}"))?;
        let read = f.read(&mut head).map_err(|e| format!("Could not read the staged pack: {e}"))?;
        if read < 2 || head[0] != 0x50 || head[1] != 0x4B {
            let _ = fs::remove_file(&source);
            return Err("That file is not a valid zip archive. Make sure the download finished before importing.".into());
        }
    }

    let library = pack_library_dir(&pack_type)?;
    let file_name = store_file_in_library(&library, &file_name, &source)?;

    let manifest_path = pack_manifest_path(&app, &request.profile_id)?;
    let mut manifest = read_pack_manifest(&manifest_path)?;
    let custom_id = format!(
        "custom:{}",
        file_name.trim_end_matches(".zip").replace(' ', "-").to_ascii_lowercase()
    );
    manifest.retain(|p| p.project_id != custom_id);

    let record = ManagedPackRecord {
        project_id: custom_id.clone(),
        project_slug: custom_id,
        title: file_name.trim_end_matches(".zip").to_string(),
        summary: Some("Local Breeze import".into()),
        icon_url: None,
        pack_type,
        game_version: request.game_version,
        installed_version_id: "local-import".into(),
        installed_version_name: "Local import".into(),
        file_name,
        state: PACK_STATE_DOWNLOADED.into(),
    };
    manifest.push(record.clone());
    write_pack_manifest(&manifest_path, &manifest)?;
    Ok(record)
}

#[tauri::command]
fn set_pack_state(
    app: AppHandle,
    profile_id: String,
    project_id: String,
    state: String,
) -> Result<ManagedPackRecord, String> {
    let next = state.trim().to_ascii_lowercase();
    if ![PACK_STATE_DOWNLOADED, PACK_STATE_INSTALLED, PACK_STATE_ENABLED, PACK_STATE_DISABLED]
        .contains(&next.as_str())
    {
        return Err(format!("Unknown pack state \"{state}\"."));
    }

    let manifest_path = pack_manifest_path(&app, &profile_id)?;
    let mut manifest = read_pack_manifest(&manifest_path)?;
    let record = manifest
        .iter()
        .find(|p| p.project_id == project_id)
        .cloned()
        .ok_or_else(|| "That pack is not in this profile.".to_string())?;

    // Only "downloaded" lives in staging; the other three all live in the
    // folder Minecraft reads.
    let was_installed = record.state != PACK_STATE_DOWNLOADED;
    let will_be_installed = next != PACK_STATE_DOWNLOADED;
    if was_installed != will_be_installed {
        move_pack_file(&app, &profile_id, &record, will_be_installed)?;
    }

    let updated = {
        let slot = manifest
            .iter_mut()
            .find(|p| p.project_id == project_id)
            .ok_or_else(|| "That pack is not in this profile.".to_string())?;
        slot.state = next;
        slot.clone()
    };
    write_pack_manifest(&manifest_path, &manifest)?;
    // Written after the manifest so options.txt can never claim a pack the
    // manifest does not have.
    sync_resource_pack_options(&app, &profile_id)?;
    Ok(updated)
}

#[tauri::command]
fn remove_installed_pack(app: AppHandle, profile_id: String, project_id: String) -> Result<(), String> {
    let manifest_path = pack_manifest_path(&app, &profile_id)?;
    let mut manifest = read_pack_manifest(&manifest_path)?;
    let record = manifest
        .iter()
        .find(|p| p.project_id == project_id)
        .cloned()
        .ok_or_else(|| "That pack is not in this profile.".to_string())?;

    let installed = pack_install_dir(&app, &profile_id, &record.pack_type)?.join(&record.file_name);
    if installed.exists() {
        fs::remove_file(&installed).map_err(|e| format!("Could not remove pack file: {e}"))?;
    }
    // The shared copy goes only when nothing else points at it, so removing a
    // pack from one version never takes it away from another.
    if !pack_file_referenced(&app, &record.file_name, &profile_id, &record.project_id) {
        let library = pack_library_dir(&record.pack_type)?.join(&record.file_name);
        if library.exists() {
            let _ = fs::remove_file(&library);
        }
    }

    manifest.retain(|p| p.project_id != project_id);
    write_pack_manifest(&manifest_path, &manifest)?;
    sync_resource_pack_options(&app, &profile_id)?;
    Ok(())
}

/// Modrinth version lookup for packs. Unlike mods there is no loader facet, and
/// a pack that lists no game versions at all is still usable, so it is kept.
async fn fetch_pack_versions(
    client: &reqwest::Client,
    project_id: &str,
    game_version: &str,
) -> Result<Vec<ModrinthVersion>, String> {
    let url = format!("https://api.modrinth.com/v2/project/{project_id}/version");
    let versions: Vec<ModrinthVersion> = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("Could not reach Modrinth: {e}"))?
        .json()
        .await
        .map_err(|e| format!("Could not read Modrinth versions: {e}"))?;

    let matching: Vec<ModrinthVersion> = versions
        .iter()
        .filter(|v| v.game_versions.is_empty() || v.game_versions.iter().any(|g| g == game_version))
        .cloned()
        .collect();

    // Packs are usually forward compatible, so falling back to the newest
    // release beats telling the user nothing exists.
    Ok(if matching.is_empty() { versions } else { matching })
}

#[tauri::command]
async fn prepare_local_server(_app: AppHandle, request: LocalServerPrepareRequest) -> Result<LocalServerPrepareResult, String> {
    let client = http_client()?;
    let safe_id = sanitize_filename(&request.id);
    let safe_name = sanitize_filename(&request.name).trim().to_string();
    let folder_name = if safe_name.is_empty() {
        safe_id.clone()
    } else {
        format!("{}-{}", safe_id, safe_name.replace(' ', "-"))
    };
    let server_dir = breeze_home_dir()?.join("local-servers").join(folder_name);
    fs::create_dir_all(server_dir.join("mods")).map_err(|e| format!("Could not create server mods directory: {e}"))?;
    fs::create_dir_all(server_dir.join("plugins")).map_err(|e| format!("Could not create server plugins directory: {e}"))?;
    fs::create_dir_all(server_dir.join("config")).map_err(|e| format!("Could not create server config directory: {e}"))?;
    fs::create_dir_all(server_dir.join("world")).map_err(|e| format!("Could not create server world directory: {e}"))?;
    fs::create_dir_all(server_dir.join("logs")).map_err(|e| format!("Could not create server logs directory: {e}"))?;

    let mut logs = vec![
        format!("Workspace prepared at {}", server_dir.display()),
        format!("Local connection: localhost:{} or 127.0.0.1:{}", request.port, request.port),
        "EULA accepted, the server can start on its first run.".to_string(),
    ];

    let properties = format!(
        "motd={}\nserver-port={}\nonline-mode=true\nmax-players=20\nview-distance=8\nsimulation-distance=6\nenable-query=false\nenable-rcon=false\n",
        request.name.replace('\n', " "),
        request.port
    );
    fs::write(server_dir.join("server.properties"), properties)
        .map_err(|e| format!("Could not write server.properties: {e}"))?;
    // Creating a server through Breeze counts as accepting the Minecraft EULA
    // (https://aka.ms/MinecraftEULA), written as accepted so the server can
    // start on its first run without a manual file edit.
    fs::write(
        server_dir.join("eula.txt"),
        "# By creating this server through Breeze you agreed to the Minecraft EULA: https://aka.ms/MinecraftEULA\neula=true\n",
    )
    .map_err(|e| format!("Could not write eula.txt: {e}"))?;
    fs::write(
        server_dir.join("README.txt"),
        format!(
            "Breeze local server profile\nLoader: {}\nMinecraft: {}\nRAM: {} MB\n\nThe Minecraft EULA (https://aka.ms/MinecraftEULA) was accepted when this\nserver was created through the Breeze launcher.\n",
            request.loader, request.version, request.ram_mb
        ),
    )
    .map_err(|e| format!("Could not write server README: {e}"))?;

    let jar_path = server_dir.join("server.jar");
    // Only resolved from Paper's and Mojang's own APIs. The request used to be
    // able to name any URL, whose download was then started with Java: a
    // program download and launch driven entirely by the page.
    let jar_url = resolve_server_jar_url(&client, &request.loader, &request.version).await?;

    let server_jar_path = if let Some(url) = jar_url {
        ensure_download(&client, &jar_path, &url).await?;
        logs.push(format!("server.jar ready from {}", url));
        Some(jar_path.to_string_lossy().to_string())
    } else {
        fs::write(
            server_dir.join("SERVER_JAR_REQUIRED.txt"),
            "Breeze can download server.jar for Paper and Vanilla. For other loaders, place the server jar in this folder as server.jar.\n",
        )
        .map_err(|e| format!("Could not write server jar note: {e}"))?;
        logs.push(format!("{} server.jar resolver is not configured yet. Workspace is ready for a manual/provider jar.", request.loader));
        None
    };

    Ok(LocalServerPrepareResult {
        id: request.id,
        path: server_dir.to_string_lossy().to_string(),
        server_jar_path,
        connection_host: "localhost".into(),
        connection_port: request.port,
        logs,
    })
}

fn local_server_processes() -> &'static Mutex<HashMap<String, Child>> {
    static PROCESSES: OnceLock<Mutex<HashMap<String, Child>>> = OnceLock::new();
    PROCESSES.get_or_init(|| Mutex::new(HashMap::new()))
}

fn verified_local_server_dir(server_path: &str) -> Result<PathBuf, String> {
    let root = breeze_home_dir()?.join("local-servers");
    fs::create_dir_all(&root).map_err(|e| format!("Could not create local server root: {e}"))?;
    let root = root
        .canonicalize()
        .map_err(|e| format!("Could not resolve local server root: {e}"))?;
    let dir = PathBuf::from(server_path)
        .canonicalize()
        .map_err(|e| format!("Could not resolve local server path: {e}"))?;
    if !dir.starts_with(&root) {
        return Err("Server path is outside the Breeze local server directory.".into());
    }
    Ok(dir)
}

#[tauri::command]
fn import_local_server_file(request: LocalServerFileImportRequest) -> Result<LocalServerFileImportResult, String> {
    let server_dir = verified_local_server_dir(&request.server_path)?;
    if request.bytes.is_empty() {
        return Err("Selected file was empty.".into());
    }
    const MAX_SERVER_UPLOAD_BYTES: usize = 512 * 1024 * 1024;
    if request.bytes.len() > MAX_SERVER_UPLOAD_BYTES {
        return Err("Selected file is larger than the 512 MB local server import limit.".into());
    }

    let file_name = sanitize_filename(&request.file_name);
    if file_name.trim().is_empty() {
        return Err("Selected file did not include a valid name.".into());
    }

    let file_type = match request.file_type.as_str() {
        "mods" | "plugins" | "config" | "logs" | "root" => request.file_type.clone(),
        _ => "files".into(),
    };
    let target_dir = match file_type.as_str() {
        "mods" => server_dir.join("mods"),
        "plugins" => server_dir.join("plugins"),
        "config" => server_dir.join("config"),
        "logs" => server_dir.join("logs"),
        // "root" is used for files the server reads from its working directory,
        // e.g. server.properties and eula.txt.
        _ => server_dir.clone(),
    };
    fs::create_dir_all(&target_dir).map_err(|e| format!("Could not create server import directory: {e}"))?;
    let target = target_dir.join(&file_name);
    fs::write(&target, &request.bytes).map_err(|e| format!("Could not write server file: {e}"))?;

    Ok(LocalServerFileImportResult {
        name: file_name,
        path: target.to_string_lossy().to_string(),
        file_type,
        size: request.bytes.len() as u64,
    })
}

#[tauri::command]
fn start_local_server(request: LocalServerProcessRequest) -> Result<LocalServerProcessResult, String> {
    let server_dir = verified_local_server_dir(&request.server_path)?;
    let jar_path = server_dir.join("server.jar");
    if !jar_path.exists() {
        return Err("server.jar is missing. Prepare or upload a server jar before starting.".into());
    }

    let mut processes = local_server_processes()
        .lock()
        .map_err(|_| "Local server process registry is unavailable.".to_string())?;
    if let Some(child) = processes.get_mut(&request.id) {
        match child.try_wait() {
            Ok(Some(_)) => {
                processes.remove(&request.id);
            }
            Ok(None) => {
                return Ok(LocalServerProcessResult {
                    id: request.id,
                    status: "running".into(),
                    log_path: None,
                    logs: vec!["Server process is already running.".into()],
                });
            }
            Err(error) => return Err(format!("Could not check server process: {error}")),
        }
    }

    let logs_dir = server_dir.join("logs");
    fs::create_dir_all(&logs_dir).map_err(|e| format!("Could not create server logs directory: {e}"))?;
    let log_path = logs_dir.join("breeze-latest.log");
    // Fresh log per run so the hosting console shows the current session only.
    let stdout = fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&log_path)
        .map_err(|e| format!("Could not open server log file: {e}"))?;
    let stderr = stdout
        .try_clone()
        .map_err(|e| format!("Could not clone server log file handle: {e}"))?;

    let ram_mb = request.ram_mb.clamp(1024, 32768);
    let mut command = Command::new("java");
    command
        .current_dir(&server_dir)
        .arg("-Xms512M")
        .arg(format!("-Xmx{}M", ram_mb))
        .arg("-jar")
        .arg(&jar_path)
        .arg("nogui")
        .stdin(Stdio::null())
        .stdout(Stdio::from(stdout))
        .stderr(Stdio::from(stderr));

    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(WIN_CREATE_NO_WINDOW);
    }

    let child = command
        .spawn()
        .map_err(|e| format!("Could not start server.jar. Make sure Java is installed and available in PATH: {e}"))?;
    processes.insert(request.id.clone(), child);

    Ok(LocalServerProcessResult {
        id: request.id,
        status: "running".into(),
        log_path: Some(log_path.to_string_lossy().to_string()),
        logs: vec![
            format!("Started server.jar with {} MB RAM.", ram_mb),
            format!("Console output is being written to {}", log_path.display()),
        ],
    })
}

#[tauri::command]
fn stop_local_server(request: LocalServerProcessRequest) -> Result<LocalServerProcessResult, String> {
    let mut processes = local_server_processes()
        .lock()
        .map_err(|_| "Local server process registry is unavailable.".to_string())?;

    let Some(mut child) = processes.remove(&request.id) else {
        return Ok(LocalServerProcessResult {
            id: request.id,
            status: "stopped".into(),
            log_path: None,
            logs: vec!["Server process was not running.".into()],
        });
    };

    let _ = child.kill();
    let _ = child.wait();
    Ok(LocalServerProcessResult {
        id: request.id,
        status: "stopped".into(),
        log_path: None,
        logs: vec!["Server process stopped.".into()],
    })
}

/// Tail the live server log so the hosting console can show real output.
/// Also reports whether the tracked process is still alive, so a crashed
/// server flips back to "stopped" in the UI instead of appearing to run.
#[tauri::command]
fn read_local_server_log(request: LocalServerProcessRequest) -> Result<LocalServerProcessResult, String> {
    let server_dir = verified_local_server_dir(&request.server_path)?;
    let log_path = server_dir.join("logs").join("breeze-latest.log");

    let mut status = "stopped".to_string();
    {
        let mut processes = local_server_processes()
            .lock()
            .map_err(|_| "Local server process registry is unavailable.".to_string())?;
        if let Some(child) = processes.get_mut(&request.id) {
            match child.try_wait() {
                Ok(None) => status = "running".into(),
                Ok(Some(_)) => {
                    processes.remove(&request.id);
                }
                Err(_) => {}
            }
        }
    }

    const MAX_CONSOLE_LINES: usize = 250;
    let logs = if log_path.exists() {
        let content = fs::read_to_string(&log_path)
            .map_err(|e| format!("Could not read server log: {e}"))?;
        let lines: Vec<&str> = content.lines().collect();
        let start = lines.len().saturating_sub(MAX_CONSOLE_LINES);
        lines[start..].iter().map(|line| line.to_string()).collect()
    } else {
        Vec::new()
    };

    Ok(LocalServerProcessResult {
        id: request.id,
        status,
        log_path: log_path.exists().then(|| log_path.to_string_lossy().to_string()),
        logs,
    })
}

// ─── Custom launcher background ──────────────────────────────────────────────
// The image is stored as a plain file in the Breeze home directory, not as a
// data URL in localStorage. localStorage caps out around 5MB, so any real photo
// silently failed to save and the background vanished on restart.

fn custom_bg_path(ext: &str) -> Result<PathBuf, String> {
    Ok(breeze_home_dir()?.join(format!("background.{}", sanitize_filename(ext))))
}

/// Delete any previously stored background, whatever its extension.
fn clear_stored_backgrounds() -> Result<(), String> {
    let home = breeze_home_dir()?;
    if let Ok(entries) = fs::read_dir(&home) {
        for entry in entries.flatten() {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with("background.") {
                let _ = fs::remove_file(entry.path());
            }
        }
    }
    Ok(())
}

/// Persist a chosen background and hand back a data URL the webview can render.
#[tauri::command]
fn save_custom_background_staged(staged_path: String, ext: String) -> Result<String, String> {
    // Read from a path staged in chunks rather than taking the whole image over
    // IPC. A byte array crosses as JSON numbers, roughly tripling the payload,
    // so a large photo was silently dropped and the background simply never
    // changed. This is the same fix the pack and modpack importers needed.
    //
    // The path comes from the webview. It used to be read and then deleted
    // wherever it pointed, so any caller could read and remove an arbitrary
    // file. Staged files only ever live in the import staging folder, so
    // anything outside it is refused before it is touched.
    let staging = import_staging_dir()?;
    let source = PathBuf::from(&staged_path);
    let staging_real = staging.canonicalize().unwrap_or(staging);
    let inside = source
        .canonicalize()
        .map(|real| real.starts_with(&staging_real) && real != staging_real)
        .unwrap_or(false);
    if !inside {
        return Err("The staged image is not in the import folder.".into());
    }
    let bytes = fs::read(&source).map_err(|e| format!("Could not read the chosen image: {e}"))?;
    let _ = fs::remove_file(&source);
    save_custom_background(bytes, ext)
}

#[tauri::command]
fn save_custom_background(bytes: Vec<u8>, ext: String) -> Result<String, String> {
    if bytes.is_empty() {
        return Err("That image file is empty.".into());
    }
    // 64MB is far beyond any sensible wallpaper and stops a mistaken video file
    // from being loaded into memory.
    if bytes.len() > 64 * 1024 * 1024 {
        return Err("That image is too large (limit 64 MB).".into());
    }
    // The extension becomes part of a file name inside ~/.breezeclient, a folder
    // the launcher is allowed to open with the system shell. An unchecked
    // extension let a caller write an .exe or .bat there.
    const BACKGROUND_EXTS: &[&str] = &["png", "jpg", "jpeg", "webp", "gif", "avif"];
    let requested = ext.trim().to_ascii_lowercase();
    let ext = if requested.is_empty() {
        "png".to_string()
    } else if BACKGROUND_EXTS.contains(&requested.as_str()) {
        requested
    } else {
        return Err("Backgrounds must be PNG, JPEG, WebP, GIF or AVIF images.".into());
    };
    if !looks_like_image(&bytes) {
        return Err("That file is not an image.".into());
    }
    clear_stored_backgrounds()?;
    let path = custom_bg_path(&ext)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Could not create Breeze directory: {e}"))?;
    }
    fs::write(&path, &bytes).map_err(|e| format!("Could not save background: {e}"))?;
    // The path, not the bytes. Returning a base64 data URL here meant a large
    // wallpaper crossed IPC twice: once as the staged file and again as an
    // encoded string a third larger than the original.
    Ok(path.to_string_lossy().to_string())
}

/// Where the stored background lives on disk, for the webview to load directly.
///
/// This used to return the whole image as a base64 data URL. Two costs, and the
/// first is the one users felt: the encoded string is about a third larger than
/// the file and had to cross the IPC boundary as JSON on every single launch, so
/// a large wallpaper was the difference between a background that came back
/// after a restart and one that silently did not. The second is that decoding
/// tens of megabytes of base64 happened on the critical path to first paint.
///
/// The webview now reads the file itself through Tauri's asset protocol, which
/// streams it and supports range requests. `.breezeclient` is the only directory
/// in the protocol's scope, so this cannot be used to read anything else.
#[tauri::command]
fn load_custom_background() -> Result<Option<String>, String> {
    let home = breeze_home_dir()?;
    let Ok(entries) = fs::read_dir(&home) else { return Ok(None) };
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        if !name.starts_with("background.") { continue; }
        // A zero-length file is left over from an interrupted write; skip it
        // rather than handing back a path that renders as a broken image.
        match entry.metadata() {
            Ok(meta) if meta.len() > 0 => {
                return Ok(Some(entry.path().to_string_lossy().to_string()));
            }
            _ => continue,
        }
    }
    Ok(None)
}

#[tauri::command]
fn clear_custom_background() -> Result<(), String> {
    clear_stored_backgrounds()
}

#[tauri::command]
fn get_launcher_settings(app: AppHandle) -> Result<LauncherSettings, String> {
    read_launcher_settings(&app)
}

#[tauri::command]
fn update_launcher_settings(app: AppHandle, settings: LauncherSettings) -> Result<LauncherSettings, String> {
    write_launcher_settings(&app, &settings)?;
    Ok(settings)
}

#[tauri::command]
async fn import_feather_preferences(
    app: AppHandle,
    request: FeatherImportRequest,
) -> Result<FeatherImportSummary, String> {
    let feather_root = feather_root_dir()?;
    let feather_settings = read_json_value(&feather_root.join("settings.json"))?;
    let feather_mods = read_json_value(&feather_root.join("mods").join("feather-mods.json"))?;

    let client_preferences = minecraft_home_dir()?
        .join("feather")
        .join("client_preferences.json");
    let client_preferences_value = if client_preferences.exists() {
        Some(read_json_value(&client_preferences)?)
    } else {
        None
    };

    let mut launcher_settings = read_launcher_settings(&app)?;
    // Another launcher's heap figure says what that install was set to, not what
    // this machine can give, so it is bounded the same way everything else is.
    let feather_heap_ceiling = detect_total_memory_mb().map(safe_heap_ceiling_mb).unwrap_or(32768);
    launcher_settings.allocated_ram_mb = feather_settings
        .get("allocatedRamMb")
        .and_then(Value::as_u64)
        .map(|value| value.clamp(2048, feather_heap_ceiling) as u32)
        .unwrap_or(launcher_settings.allocated_ram_mb);
    launcher_settings.mod_auto_update = feather_settings
        .get("modAutoUpdate")
        .and_then(Value::as_bool)
        .unwrap_or(launcher_settings.mod_auto_update);
    launcher_settings.graphics_performance = feather_settings
        .get("graphicsPerformance")
        .and_then(Value::as_bool)
        .unwrap_or(launcher_settings.graphics_performance);
    launcher_settings.browser_accel = feather_settings
        .get("browserAccel")
        .and_then(Value::as_str)
        .unwrap_or(&launcher_settings.browser_accel)
        .to_string();
    launcher_settings.after_launch = feather_settings
        .get("afterLaunch")
        .and_then(Value::as_str)
        .unwrap_or(&launcher_settings.after_launch)
        .to_string();
    
    
    
    
    if let Some(resolution) = feather_settings.get("resolution").and_then(Value::as_array) {
        launcher_settings.resolution_width = resolution.first().and_then(Value::as_u64).map(|v| v as u32);
        launcher_settings.resolution_height = resolution.get(1).and_then(Value::as_u64).map(|v| v as u32);
    }

    if let Some(preferences) = client_preferences_value.as_ref() {
        if preferences
            .get("updateClientPreferences")
            .and_then(|value| value.get("compact"))
            .and_then(Value::as_bool)
            == Some(true)
        {
            launcher_settings.theme = "Dark".into();
        }
    }

    let selected_key = feather_mods
        .get("selectedVersion")
        .and_then(Value::as_str)
        .map(str::to_string)
        .or_else(|| {
            feather_settings
                .get("versionToLaunch")
                .and_then(Value::as_str)
                .map(|value| format!("{value}-fabric"))
        });

    let mut version_keys = Vec::new();
    if request.include_all_versions {
        if let Some(object) = feather_mods.get("profiles").and_then(Value::as_object) {
            version_keys.extend(object.keys().cloned());
        }
    } else if let Some(selected) = selected_key.clone() {
        version_keys.push(selected);
    }
    version_keys.sort();
    version_keys.dedup();

    let client = http_client()?;
    let mut imported_versions = Vec::new();
    let mut imported_custom_mods = 0usize;
    let mut imported_modrinth_mods = 0usize;
    let mut copied_config_directories = 0usize;
    let mut warnings = Vec::new();

    for version_key in version_keys {
        let breeze_profile = normalize_feather_profile_id(&version_key);
        imported_versions.push(breeze_profile.clone());

        let manifest_path = profile_manifest_path(&app, &breeze_profile)?;
        let mods_dir = profile_mods_dir(&app, &breeze_profile)?;
        let profile_dir = profile_root_dir(&app, &breeze_profile)?;
        fs::create_dir_all(&mods_dir)
            .map_err(|error| format!("Could not create Breeze mods directory: {error}"))?;

        let mut manifest = read_mod_manifest(&manifest_path)?;

        let selected_profile_name = feather_mods
            .get("profiles")
            .and_then(|value| value.get(&version_key))
            .and_then(|value| value.get("selectedProfile"))
            .and_then(Value::as_str)
            .unwrap_or("Default");
        let active_profile = feather_mods
            .get("profiles")
            .and_then(|value| value.get(&version_key))
            .and_then(|value| value.get("profileStateByName"))
            .and_then(|value| value.get(selected_profile_name));

        let enabled_custom_mods = active_profile
            .and_then(|value| value.get("customEnabledMods"))
            .and_then(Value::as_array)
            .map(|values| {
                values
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::to_string)
                    .collect::<HashSet<_>>()
            })
            .unwrap_or_default();
        let enabled_modrinth_mods = active_profile
            .and_then(|value| value.get("modrinthEnabledMods"))
            .and_then(Value::as_array)
            .map(|values| {
                values
                    .iter()
                    .filter_map(Value::as_str)
                    .map(str::to_string)
                    .collect::<HashSet<_>>()
            })
            .unwrap_or_default();

        let custom_mod_names = feather_mods
            .get("customAddedMods")
            .and_then(|value| value.get(&version_key))
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let feather_user_mods_dir = feather_root.join("user-mods").join(&version_key);
        for mod_name in custom_mod_names.iter().filter_map(Value::as_str) {
            let source_path = feather_user_mods_dir.join(mod_name);
            if !source_path.exists() {
                warnings.push(format!("Missing Feather custom mod: {}", source_path.display()));
                continue;
            }
            if !mod_name.to_ascii_lowercase().ends_with(".jar") {
                continue;
            }
            
            
            
            
            if !is_valid_jar(&source_path) {
                warnings.push(format!(
                    "Skipped invalid or incomplete Feather mod: {} (not a valid JAR)",
                    mod_name
                ));
                continue;
            }

            let file_name = sanitize_filename(mod_name);
            fs::copy(&source_path, mods_dir.join(&file_name)).map_err(|error| {
                format!(
                    "Could not copy Feather mod {} into Breeze: {error}",
                    source_path.display()
                )
            })?;
            upsert_managed_mod(
                &mut manifest,
                ManagedModRecord {
                    project_id: format!(
                        "custom:{}",
                        file_name
                            .trim_end_matches(".jar")
                            .replace(' ', "-")
                            .to_ascii_lowercase()
                    ),
                    project_slug: format!(
                        "custom:{}",
                        file_name
                            .trim_end_matches(".jar")
                            .replace(' ', "-")
                            .to_ascii_lowercase()
                    ),
                    title: file_name.trim_end_matches(".jar").to_string(),
                    summary: Some("Imported from Feather".into()),
                    icon_url: None,
                    game_version: breeze_profile.clone(),
                    loader: "fabric".into(),
                    installed_version_id: "feather-import".into(),
                    installed_version_name: "Feather import".into(),
                    file_name,
                    enabled: enabled_custom_mods.contains(mod_name),
                    // Left blank so the upsert derives it from project_id, which
                    // is already the canonical "custom:" key for a local jar.
                    canonical_id: String::new(),
                },
            );
            imported_custom_mods += 1;
        }

        let imported_mods = feather_mods
            .get("modrinthAddedMods")
            .and_then(|value| value.get(&version_key))
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        for mod_entry in imported_mods {
            let Some(project_id) = mod_entry.get("projectID").and_then(Value::as_str) else {
                continue;
            };
            let download_url = mod_entry
                .get("downloadUrl")
                .and_then(Value::as_str)
                .unwrap_or_default();
            if download_url.is_empty() {
                warnings.push(format!("Missing download URL for Feather mod {project_id}"));
                continue;
            }

            let file_name = derive_download_file_name(download_url);
            let target_path = mods_dir.join(&file_name);
            if !target_path.exists() {
                match client.get(download_url).send().await {
                    Ok(response) if response.status().is_success() => {
                        let bytes = response.bytes().await.map_err(|error| {
                            format!("Could not read imported Feather mod bytes: {error}")
                        })?;
                        fs::write(&target_path, bytes).map_err(|error| {
                            format!(
                                "Could not write imported Feather mod {}: {error}",
                                target_path.display()
                            )
                        })?;
                    }
                    Ok(response) => {
                        warnings.push(format!(
                            "Could not import Feather mod {project_id}: {}",
                            response.status()
                        ));
                        continue;
                    }
                    Err(error) => {
                        warnings.push(format!("Could not download Feather mod {project_id}: {error}"));
                        continue;
                    }
                }
            }

            let project_slug = mod_entry
                .get("slug")
                .and_then(Value::as_str)
                .unwrap_or(project_id)
                .to_string();
            let title = mod_entry
                .get("name")
                .and_then(Value::as_str)
                .unwrap_or(project_id)
                .to_string();
            let summary = mod_entry
                .get("categories")
                .and_then(Value::as_array)
                .map(|categories| {
                    categories
                        .iter()
                        .filter_map(Value::as_str)
                        .collect::<Vec<_>>()
                        .join(", ")
                })
                .filter(|value| !value.is_empty());
            let version_name = mod_entry
                .get("versionNumber")
                .and_then(Value::as_str)
                .unwrap_or("Imported")
                .to_string();
            let version_id = mod_entry
                .get("versionID")
                .and_then(Value::as_str)
                .unwrap_or("feather-import")
                .to_string();

            upsert_managed_mod(
                &mut manifest,
                ManagedModRecord {
                    project_id: project_id.to_string(),
                    project_slug,
                    title,
                    summary,
                    icon_url: mod_entry
                        .get("icon")
                        .and_then(Value::as_str)
                        .map(str::to_string),
                    game_version: breeze_profile.clone(),
                    loader: "fabric".into(),
                    installed_version_id: version_id,
                    installed_version_name: version_name,
                    file_name,
                    enabled: enabled_modrinth_mods.contains(project_id),
                    canonical_id: project_id.to_string(),
                },
            );
            imported_modrinth_mods += 1;
        }

        let feather_config_dir = feather_user_mods_dir.join("overrides").join("config");
        if feather_config_dir.exists() {
            let target_config_dir = profile_dir.join("config");
            copy_dir_recursive(&feather_config_dir, &target_config_dir)?;
            copied_config_directories += 1;
        }

        write_mod_manifest(&manifest_path, &manifest)?;
    }

    let selected_version = selected_key.map(|key| normalize_feather_profile_id(&key));
    launcher_settings.selected_version = selected_version.clone();
    launcher_settings.imported_from_feather = true;
    write_launcher_settings(&app, &launcher_settings)?;

    Ok(FeatherImportSummary {
        imported_versions,
        imported_custom_mods,
        imported_modrinth_mods,
        copied_config_directories,
        selected_version,
        allocated_ram_mb: launcher_settings.allocated_ram_mb,
        warnings,
    })
}

#[tauri::command]
async fn apply_performance_profile(
    app: AppHandle,
    request: ApplyPerformanceProfileRequest,
) -> Result<PerformanceProfileResult, String> {
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    let recommendations = match request.profile_name.as_str() {
        "Quality" => (
            
            8192u32,
            vec![
                "AANobbMI", 
                "gvQqBUqZ", 
                "uXXizFIs", 
                "NNAgCjsB", 
                "5ZwdcRci", 
                "Bh37bMuy", 
                "PtjYWJkn", 
                "M08ruV16", 
                "mOgUt4GM", 
            ],
        ),
        "Balanced" => (
            
            6144u32,
            vec![
                "AANobbMI", 
                "gvQqBUqZ", 
                "uXXizFIs", 
                "NNAgCjsB", 
                "5ZwdcRci", 
                "Bh37bMuy", 
                "PtjYWJkn", 
                "M08ruV16", 
                "mOgUt4GM", 
            ],
        ),
        _ => (
            
            4096u32,
            vec![
                "AANobbMI", 
                "gvQqBUqZ", 
                "uXXizFIs", 
                "NNAgCjsB", 
                "5ZwdcRci", 
                "Bh37bMuy", 
                "PtjYWJkn", 
                "M08ruV16", 
                "mOgUt4GM", 
            ],
        ),
    };

    let client = http_client()?;
    let mut installed_titles = Vec::new();
    let mut seen = HashSet::new();
    for project_id in recommendations.1 {
        let project = fetch_modrinth_project(&client, project_id).await?;
        let record = install_modrinth_project_recursive(
            &client,
            &app,
            &request.profile_id,
            &request.game_version,
            &request.loader,
            InstallModRequest {
                profile_id: request.profile_id.clone(),
                game_version: request.game_version.clone(),
                loader: request.loader.clone(),
                project_id: project.id,
                project_slug: project.slug,
                title: project.title,
                summary: Some(project.description),
                icon_url: project.icon_url,
            },
            &mut seen,
        )
        .await?;
        installed_titles.push(record.title);
    }

    let mut launcher_settings = read_launcher_settings(&app)?;
    launcher_settings.performance_profile = request.profile_name.clone();
    // The profile's number is a preference, not a promise the hardware can keep.
    // "Quality" is 8192, so on an 8GB machine this button used to write a heap
    // larger than the launcher's own ceiling, bypassing the bound the settings
    // slider already respects.
    launcher_settings.allocated_ram_mb = match detect_total_memory_mb() {
        Ok(total) => recommendations.0.min(safe_heap_ceiling_mb(total) as u32),
        Err(_) => recommendations.0,
    };
    // Applying a performance profile says nothing about which version the user
    // wants to play, and silently reselecting one is how they ended up launching
    // a version they had not chosen.
    write_launcher_settings(&app, &launcher_settings)?;

    Ok(PerformanceProfileResult {
        profile_name: request.profile_name,
        recommended_ram_mb: recommendations.0,
        installed_count: installed_titles.len(),
        installed_titles,
    })
}

#[tauri::command]
async fn prewarm_version(app: AppHandle, request: PrewarmRequest) -> Result<PrewarmResult, String> {
    let client = http_client()?;
    let breeze_home = breeze_home_dir()?;
    let game_directory = ensure_instance_structure(&app, &request.version_id)?;
    let assets_root = breeze_home.join("assets");
    let libraries_root = breeze_home.join("libraries");

    emit_launch_stage(&app, "prewarm", &format!("Prewarming {}", request.version_id), Some(0.08));
    emit_launch_log(&app, &format!("[Prewarm] Instance: {}", game_directory.display()));

    let resolved = ensure_launch_version(
        &client,
        &app,
        &breeze_home,
        &request.version_id,
        request.loader_version.as_deref(),
    )
    .await?;
    let asset_index_name = ensure_assets(&client, &app, &assets_root, &resolved.effective).await?;
    let (classpath, _) = ensure_libraries(&client, &app, &libraries_root, &resolved.effective).await?;
    let _ = ensure_logging_config(&client, &app, &assets_root, &resolved.effective).await?;
    let cached_mod_count = read_mod_manifest(&profile_manifest_path(&app, &request.version_id)?)?
        .into_iter()
        .filter(|item| item.enabled)
        .count();

    emit_launch_stage(
        &app,
        "prewarm",
        &format!("{} is warmed and ready", request.version_id),
        Some(1.0),
    );

    Ok(PrewarmResult {
        version_id: request.version_id.clone(),
        loader_version: request.loader_version,
        asset_index_name,
        library_count: classpath.len(),
        cached_mod_count,
        game_directory: game_directory.to_string_lossy().to_string(),
        status: "Ready".into(),
    })
}

#[tauri::command]
fn get_system_memory_info() -> Result<SystemMemoryInfo, String> {
    let total_ram_mb = detect_total_memory_mb()?;

    
    let safe_max_ram_mb = safe_heap_ceiling_mb(total_ram_mb);

    
    
    
    
    
    Ok(SystemMemoryInfo {
        total_ram_mb,
        safe_max_ram_mb,
        recommended_ram_mb: recommended_heap_mb(total_ram_mb),
    })
}


#[tauri::command]
async fn begin_microsoft_auth(app: AppHandle) -> Result<AuthenticatedAccount, String> {
    emit_auth_log(&app, "authorize", "Building Microsoft sign-in window");

    let auth_url = Url::parse_with_params(
        MICROSOFT_AUTHORIZE_URL,
        &[
            ("client_id", BREEZE_CLIENT_ID),
            ("response_type", "code"),
            ("redirect_uri", MICROSOFT_REDIRECT_URI),
            ("scope", MICROSOFT_SCOPE),
            ("prompt", "select_account"),
        ],
    )
    .map_err(|error| format!("Could not build Microsoft sign-in URL: {error}"))?;

    let label = "microsoft-auth";
    if let Some(existing) = app.get_webview_window(label) {
        let _ = existing.close();
    }

    let (tx, rx) = mpsc::channel::<Result<String, String>>();
    let app_for_nav = app.clone();

    WebviewWindowBuilder::new(&app, label, WebviewUrl::External(auth_url))
        .title("Sign In to Breeze")
        .inner_size(520.0, 760.0)
        .resizable(true)
        .focused(true)
        .center()
        .on_navigation(move |url| {
            if url.as_str().starts_with(MICROSOFT_REDIRECT_URI) {
                let code = url
                    .query_pairs()
                    .find_map(|(key, value)| (key == "code").then(|| value.into_owned()));
                let error = url
                    .query_pairs()
                    .find_map(|(key, value)| (key == "error").then(|| value.into_owned()));
                let description = url.query_pairs().find_map(|(key, value)| {
                    (key == "error_description").then(|| value.into_owned())
                });

                let send_result = match (code, error) {
                    (Some(code), _) => tx.send(Ok(code)),
                    (_, Some(error)) => {
                        let message = description.unwrap_or(error);
                        tx.send(Err(format!("Microsoft authorization failed: {message}")))
                    }
                    _ => tx.send(Err(
                        "Microsoft returned to Breeze without an authorization code.".into(),
                    )),
                };
                let _ = send_result;

                if let Some(window) = app_for_nav.get_webview_window(label) {
                    let _ = window.close();
                }
                return false;
            }
            true
        })
        .build()
        .map_err(|error| format!("Could not open Microsoft sign-in window: {error}"))?;

    emit_auth_log(&app, "authorize", "Microsoft sign-in window opened");

    let auth_code = tauri::async_runtime::spawn_blocking(move || {
        rx.recv_timeout(Duration::from_secs(300))
            .map_err(|_| "Microsoft sign-in timed out before Breeze received the authorization code.".to_string())?
    })
    .await
    .map_err(|error| format!("Could not wait for Microsoft sign-in: {error}"))??;

    emit_auth_log(&app, "token", "Authorization code received");

    let client = http_client()?;
    let token = exchange_microsoft_authorization_code(&client, &auth_code, &app).await?;
    let account = authenticate_minecraft(&client, &token.access_token, token.refresh_token, Some(&app)).await?;
    if let Err(error) = persist_session(&app, &account) {
        emit_auth_log(&app, "session-storage", &format!("Signed in, but the session could not be saved: {error}"));
    }
    emit_auth_log(&app, "complete", &format!("Signed in as {}", account.username));
    Ok(account)
}

async fn exchange_microsoft_authorization_code(client: &reqwest::Client, auth_code: &str, app: &AppHandle) -> Result<MicrosoftTokenResponse, String> {
    emit_auth_log(app, "token", "Exchanging Microsoft authorization code");
    let response = client
        .post(MICROSOFT_TOKEN_URL)
        .form(&[
            ("client_id", BREEZE_CLIENT_ID),
            ("code", auth_code),
            ("grant_type", "authorization_code"),
            ("redirect_uri", MICROSOFT_REDIRECT_URI),
            ("scope", MICROSOFT_SCOPE),
        ])
        .send()
        .await
        .map_err(|error| format!("Microsoft token exchange network error: {error}"))?;

    if !response.status().is_success() {
        let err_text = response.text().await.unwrap_or_default();
        return Err(format!("Microsoft rejected the authorization code exchange: {err_text}"));
    }

    let token = response
        .json::<MicrosoftTokenResponse>()
        .await
        .map_err(|error| format!("Could not parse Microsoft token payload: {error}"))?;
    emit_auth_log(app, "token", "Microsoft token received");
    Ok(token)
}

async fn authenticate_minecraft(client: &reqwest::Client, microsoft_access_token: &str, refresh_token: Option<String>, app: Option<&AppHandle>) -> Result<AuthenticatedAccount, String> {
    if let Some(app) = app { emit_auth_log(app, "xbox", "Starting Xbox Live authentication"); }
    let xbox_auth = client.post(XBOX_AUTH_URL).json(&json!({ "Properties": { "AuthMethod": "RPS", "SiteName": "user.auth.xboxlive.com", "RpsTicket": format!("d={}", microsoft_access_token) }, "RelyingParty": "http://auth.xboxlive.com", "TokenType": "JWT" })).send().await.map_err(|error| format!("Xbox Live authentication network error: {error}"))?;
    if !xbox_auth.status().is_success() { let err_text = xbox_auth.text().await.unwrap_or_default(); return Err(format!("Xbox Live rejected Microsoft login: {}", err_text)); }
    let xbox = xbox_auth.json::<XboxAuthResponse>().await.map_err(|error| format!("Could not parse Xbox login payload: {error}"))?;
    let user_claim = xbox.display_claims.xui.first().cloned().ok_or_else(|| "Xbox login did not return a user hash (UHS).".to_string())?;

    if let Some(app) = app { emit_auth_log(app, "xsts", "Requesting XSTS token"); }
    let xsts_auth = client.post(XSTS_AUTH_URL).json(&json!({ "Properties": { "SandboxId": "RETAIL", "UserTokens": [xbox.token.clone()] }, "RelyingParty": "rp://api.minecraftservices.com/", "TokenType": "JWT" })).send().await.map_err(|error| format!("XSTS authentication network error: {error}"))?;
    if !xsts_auth.status().is_success() { let err_text = xsts_auth.text().await.unwrap_or_default(); return Err(format!("XSTS rejected Xbox login. Check if account owns MC: {}", err_text)); }
    let xsts = xsts_auth.json::<XboxAuthResponse>().await.map_err(|error| format!("Could not parse XSTS login payload: {error}"))?;
    let xsts_claim = xsts.display_claims.xui.first().cloned().unwrap_or(user_claim.clone());

    if let Some(app) = app { emit_auth_log(app, "minecraft", "Logging into Minecraft services"); }
    let minecraft_login = client.post(MINECRAFT_XBOX_LOGIN_URL).json(&json!({ "identityToken": format!("XBL3.0 x={};{}", xsts_claim.uhs, xsts.token) })).send().await.map_err(|error| format!("Minecraft services network error: {error}"))?;
    let minecraft_status = minecraft_login.status();
    let minecraft_body = minecraft_login.text().await.map_err(|error| format!("Could not read Minecraft login response body: {error}"))?;
    if !minecraft_status.is_success() {
        return Err(format!("Minecraft services rejected XSTS token: {}", minecraft_body));
    }
    let minecraft = serde_json::from_str::<MinecraftLoginResponse>(&minecraft_body)
        .map_err(|error| format!("Could not parse Minecraft access token: {error}. Body: {minecraft_body}"))?;

    if let Some(app) = app { emit_auth_log(app, "profile", "Retrieving Minecraft profile"); }
    let profile = client.get(MINECRAFT_PROFILE_URL).bearer_auth(&minecraft.access_token).send().await.map_err(|error| format!("Minecraft profile lookup network error: {error}"))?;
    if !profile.status().is_success() { let err_text = profile.text().await.unwrap_or_default(); return Err(format!("Failed to retrieve Minecraft profile: {}", err_text)); }
    let profile = profile.json::<MinecraftProfileResponse>().await.map_err(|error| format!("Could not parse Minecraft profile JSON: {error}"))?;

    // Reaching here means the full Microsoft → Xbox → XSTS → Minecraft chain
    // succeeded, so this session is definitively live.
    Ok(AuthenticatedAccount { uuid: profile.id, username: profile.name, access_token: minecraft.access_token, refresh_token, xuid: xsts_claim.xid, breeze_token: None, needs_reauth: false })
}

#[tauri::command]
async fn restore_saved_session(app: AppHandle) -> Result<AuthenticatedAccount, String> {
    // Only an explicit rejection from Microsoft invalidates the session.
    // Network hiccups at startup must not drop a saved login, the stored
    // Minecraft token can stay valid for up to 24 hours.
    let saved = read_persisted_session(&app)?;
    let first_error = match revive_account(&app, saved).await {
        Ok(account) => return Ok(account),
        Err(error) => error,
    };

    // The active account was revoked between sessions (password change, or
    // "sign out everywhere" on the Microsoft side). Fall through to any other
    // stored account that can still resume rather than stranding the user at
    // the login gate with valid accounts sitting in the store.
    let store = read_account_store(&app).unwrap_or_default();
    let candidates: Vec<AuthenticatedAccount> = store
        .accounts
        .iter()
        .filter(|item| item.refresh_token.is_some() && !item.needs_reauth)
        .cloned()
        .collect();
    for candidate in candidates {
        if let Ok(account) = revive_account(&app, candidate).await {
            let mut fresh = read_account_store(&app).unwrap_or_default();
            fresh.upsert(account.clone());
            let _ = write_account_store(&app, &fresh);
            return Ok(account);
        }
    }
    Err(first_error)
}

/// Every stored account, without leaking tokens into the web layer.
#[tauri::command]
fn list_saved_accounts(app: AppHandle) -> Result<Vec<SavedAccountSummary>, String> {
    let store = read_account_store(&app).unwrap_or_default();
    let active = store.active_uuid.clone();
    Ok(store
        .accounts
        .iter()
        .map(|account| SavedAccountSummary {
            uuid: account.uuid.clone(),
            username: account.username.clone(),
            active: active.as_deref() == Some(account.uuid.as_str()),
            // Only a refresh token Microsoft has not rejected can actually
            // resume. Anything weaker would promise an instant switch and then
            // drop the user into re-auth, the exact experience this replaces.
            can_resume: account.refresh_token.is_some() && !account.needs_reauth,
        })
        .collect())
}

/// Switch to an already-signed-in account. Refreshes that account's Microsoft
/// tokens in place; the user only has to sign in again if Microsoft explicitly
/// rejects the stored refresh token (expired or revoked).
#[tauri::command]
async fn switch_account(app: AppHandle, uuid: String) -> Result<AuthenticatedAccount, String> {
    let store = read_account_store(&app).unwrap_or_default();
    let Some(stored) = store.accounts.iter().find(|item| item.uuid == uuid).cloned() else {
        return Err("That account is not signed in on this device.".into());
    };
    // active_uuid is deliberately NOT moved yet. If Microsoft rejects this
    // account, pointing the store at it would strand the user at the login
    // gate on next launch even though other valid accounts are still stored.
    let account = revive_account(&app, stored).await?;
    // Re-read rather than reusing the snapshot above: revive_account may have
    // already written fresh tokens for this account.
    let mut fresh = read_account_store(&app).unwrap_or_default();
    fresh.upsert(account.clone()); // upsert also promotes it to active
    write_account_store(&app, &fresh)?;
    Ok(account)
}

/// Forget one account without touching the others.
#[tauri::command]
fn remove_saved_account(app: AppHandle, uuid: String) -> Result<Vec<SavedAccountSummary>, String> {
    let mut store = read_account_store(&app).unwrap_or_default();
    store.accounts.retain(|item| item.uuid != uuid);
    if store.active_uuid.as_deref() == Some(uuid.as_str()) {
        store.active_uuid = store.accounts.first().map(|item| item.uuid.clone());
    }
    write_account_store(&app, &store)?;
    list_saved_accounts(app)
}

/// Sign out of the active account. Any other signed-in account stays stored,
/// and the next one becomes active so the user lands straight back in.
#[tauri::command]
fn clear_saved_session(app: AppHandle) -> Result<Option<AuthenticatedAccount>, String> {
    let mut store = read_account_store(&app).unwrap_or_default();
    if let Some(active) = store.active_uuid.clone() {
        store.accounts.retain(|item| item.uuid != active);
    }
    store.active_uuid = store.accounts.first().map(|item| item.uuid.clone());
    let next = store.active_account().cloned();
    write_account_store(&app, &store)?;
    if store.accounts.is_empty() {
        let path = session_file_path(&app)?;
        if path.exists() { fs::remove_file(path).map_err(|e| format!("Could not clear saved Breeze session: {e}"))?; }
        let _ = keyring::Entry::new(SESSION_KEYRING_SERVICE, SESSION_KEYRING_ACCOUNT)
            .and_then(|entry| entry.delete_credential());
    }
    Ok(next)
}

/// Forget every account on this device.
#[tauri::command]
fn sign_out_all_accounts(app: AppHandle) -> Result<(), String> {
    let _ = write_account_store(&app, &AccountStore::default());
    let path = accounts_file_path(&app)?;
    if path.exists() { let _ = fs::remove_file(path); }
    let path = session_file_path(&app)?;
    if path.exists() { fs::remove_file(path).map_err(|e| format!("Could not clear saved Breeze session: {e}"))?; }
    let _ = keyring::Entry::new(SESSION_KEYRING_SERVICE, SESSION_KEYRING_ACCOUNT)
        .and_then(|entry| entry.delete_credential());
    Ok(())
}

#[tauri::command]
fn prepare_launch(request: LaunchRequest) -> Result<LaunchPlan, String> {
    let Some(account) = request.account else { return Err("Microsoft account required before launching Minecraft.".into()); };
    if account.uuid.trim().is_empty() || account.username.trim().is_empty() || account.access_token.trim().is_empty() { return Err("Authenticated Minecraft profile is incomplete.".into()); }
    Ok(LaunchPlan { version_id: request.version_id.clone(), profile: account.username, game_directory: format!("breeze://instances/{}", request.version_id), status: "Preflight ready".into(), preflight: vec!["Microsoft account verified".into(), "Fabric profile selected".into(), "Optimization pack resolved".into(), "Launch arguments can be generated".into()] })
}

#[tauri::command]
async fn launch_minecraft(app: AppHandle, request: LaunchRequest) -> Result<LaunchCompleteEvent, String> {
    let version_id = request.version_id.clone();
    let result = launch_minecraft_inner(app.clone(), request).await;
    if let Err(error) = &result {
        emit_launch_error(&app, error);
        // A launch that stops after the runtime jar was put in place (a failed
        // preflight, duplicate mods) would otherwise leave it on disk until the
        // next successful game exit.
        remove_breeze_runtime_for(&app, &version_id);
    }
    result
}

/// Remove this version's Breeze runtime jar and its download cache. Only the
/// files the launcher itself manages: the runtime folder copy, a mods-folder
/// copy the runtime record names and that declares the Breeze mod id, and the
/// cache.
fn remove_breeze_runtime_for(app: &AppHandle, version_id: &str) {
    let mut files = Vec::new();
    if let Ok(profile_root) = profile_root_dir(app, version_id) {
        files.push(breeze_runtime_jar_path(&profile_root));
        if let Some(sidecar) = read_breeze_runtime_sidecar(&profile_root) {
            if !sidecar.file_name.eq_ignore_ascii_case(BREEZE_RUNTIME_JAR) {
                let copy = profile_root.join("mods").join(sanitize_filename(&sidecar.file_name));
                if fabric_mod_id(&copy).as_deref() == Some(BREEZE_MOD_ID) {
                    files.push(copy);
                }
            }
        }
    }
    if let Ok(home) = breeze_home_dir() {
        files.push(home.join("versions").join("mods").join(sanitize_filename(&format!("{version_id}.jar"))));
    }
    remove_runtime_files(&files);
}

async fn launch_minecraft_inner(app: AppHandle, request: LaunchRequest) -> Result<LaunchCompleteEvent, String> {
    let Some(account) = request.account else { return Err("Microsoft account required before launching Minecraft.".into()); };
    // The account object held in JavaScript can be hours old, the launcher may
    // have sat open past the token's lifetime, or refreshed it during a switch.
    // The credential store is updated on every restore/switch/refresh, so prefer
    // its copy for this uuid and only fall back to what JS handed us.
    let account = match read_account_store(&app) {
        Ok(store) => store
            .accounts
            .iter()
            .find(|item| item.uuid == account.uuid && !item.access_token.trim().is_empty())
            .cloned()
            .unwrap_or(account),
        Err(_) => account,
    };
    if account.access_token.trim().is_empty() { return Err("Minecraft access token is missing.".into()); }

    
    
    
    let client = http_client()?;

    
    let breeze_home      = breeze_home_dir()?;
    let game_directory   = ensure_instance_structure(&app, &request.version_id)?;
    let mods_directory   = game_directory.join("mods");
    let natives_directory = game_directory.join("natives");
    let assets_root      = breeze_home.join("assets");
    let libraries_root   = breeze_home.join("libraries");

    emit_launch_stage(&app, "prepare", "Preparing Breeze instance…", Some(0.03));
    emit_launch_log(&app, &format!("[Launch] Instance root: {}", game_directory.display()));
    emit_launch_log(&app, &format!("[Launch] Breeze home:   {}", breeze_home.display()));

    
    
    
    
    emit_launch_stage(&app, "prepare", "Injecting Breeze runtime mod…", Some(0.05));
    // The account store never holds a Breeze token, so the one the UI signed in
    // with is the only credential there is. It is sent to Breeze hosts only.
    let breeze_token = request
        .breeze_token
        .as_deref()
        .or(account.breeze_token.as_deref())
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(str::to_string);
    let api_base = request.api_base_url.as_deref().and_then(trusted_api_base);
    if request.api_base_url.is_some() && api_base.is_none() {
        emit_launch_log(&app, "[Breeze] ignoring an API address that is not a Breeze server");
    }

    let remote_breeze_mod = ensure_remote_breezemod(
        &client,
        &app,
        request.breeze_mod_url.as_deref(),
        request.breeze_mod_sha256.as_deref(),
        &request.version_id,
        breeze_token.as_deref(),
    ).await;

    // Say which of the three outcomes it was, so the UI can offer "launching
    // without Breeze features" rather than implying everything is fine.
    let remote_breeze_path = match &remote_breeze_mod {
        BreezeModOutcome::Injected(path) => {
            emit_launch_stage(&app, "breeze-mod", "Breeze runtime mod ready", Some(0.06));
            Some(path.clone())
        }
        BreezeModOutcome::NotPublished => {
            emit_launch_stage(
                &app,
                "breeze-mod",
                "No Breeze build for this Minecraft version yet, launching without Breeze features",
                Some(0.06),
            );
            None
        }
        BreezeModOutcome::DownloadFailed(reason) => {
            emit_launch_stage(
                &app,
                "breeze-mod",
                &format!("Breeze runtime mod unavailable, launching without Breeze features: {reason}"),
                Some(0.06),
            );
            None
        }
    };


    let resolved = ensure_launch_version(&client, &app, &breeze_home, &request.version_id, request.loader_version.as_deref()).await?;

    // The runtime comes from the version itself: 26.1 needs Java 25, 1.20.1 was
    // built for 17, 1.16 for 8. It is settled before the long library download,
    // so a machine without a usable Java fails early with the reason.
    let java_requirement = java_runtime::requirement_from_version_json(&resolved.effective);
    let java = ensure_java_for(&app, &breeze_home, &java_requirement).await?;
    emit_launch_log(&app, &format!(
        "[Java] Using Java {} ({}); Minecraft {} asks for Java {}",
        java.major, java.source.as_str(), resolved.version_name, java_requirement.major
    ));


    pre_launch_ensure_dependencies(&client, &app, &request.version_id, &request.version_id, "fabric").await?;

    // Installed after the dependency pass, so the manifest the cleanup consults
    // already lists anything that pass installed.
    let breeze_manifest = read_mod_manifest(&profile_manifest_path(&app, &request.version_id)?)?;
    let breeze_runtime = ensure_breezemod_injected(
        &app,
        &game_directory,
        &mods_directory,
        &breeze_manifest,
        &request.version_id,
        &resolved.loader_version,
        remote_breeze_path.as_deref(),
    )?;

    // The game gets a token that only works for in-game calls and expires, in a
    // file it deletes on read, instead of the account token on its command line.
    let session_file = game_session_file(&game_directory);
    let _ = fs::remove_file(&session_file);
    let game_session_ready = match (api_base.as_deref(), breeze_token.as_deref()) {
        (Some(base), Some(token)) => match issue_game_session(&client, base, token, &session_file).await {
            Ok(()) => {
                emit_launch_log(&app, "[Breeze] game session issued");
                true
            }
            Err(reason) => {
                emit_launch_log(&app, &format!("[Breeze] social features will be signed out in game: {reason}"));
                false
            }
        },
        (_, None) => {
            emit_launch_log(&app, "[Breeze] not signed in to Breeze, social features will be signed out in game");
            false
        }
        (None, Some(_)) => false,
    };


    // The runtime jar is an authorized download for this session, not a file
    // that stays installed: both the instance copy and the download cache are
    // removed when the game exits. Nothing the user installed is in this list.
    let runtime_files: Vec<PathBuf> = match &breeze_runtime {
        BreezeRuntimeInjection::AddMods(path) | BreezeRuntimeInjection::ModsFolder(path) => {
            let mut files = vec![path.clone()];
            files.extend(remote_breeze_path.iter().cloned());
            files
        }
        BreezeRuntimeInjection::None => remote_breeze_path.iter().cloned().collect(),
    };

    let asset_index_name = ensure_assets(&client, &app, &assets_root, &resolved.effective).await?;
    let (mut classpath, native_jars) = ensure_libraries(&client, &app, &libraries_root, &resolved.effective).await?;

    // Only the mods-folder copy belongs on the classpath. The addMods jar is
    // handed to Fabric separately, and listing it twice makes the loader abort
    // over a duplicate mod.
    let classpath_runtime_jar = match &breeze_runtime {
        BreezeRuntimeInjection::ModsFolder(path) => Some(path.clone()),
        BreezeRuntimeInjection::AddMods(_) | BreezeRuntimeInjection::None => None,
    };
    // A switched-off mod is renamed to .jar.disabled so Fabric does not load
    // it from the folder (before 1.0.28 the switch only left it off the
    // classpath, and Fabric loaded it anyway).
    for note in mods_commands::sync_switches(&app, &request.version_id) {
        emit_launch_log(&app, &format!("[Mods] {note}"));
    }
    let (enabled_mod_jars, missing_mod_files) =
        collect_enabled_mod_jars(&app, &request.version_id, classpath_runtime_jar.as_deref())?;
    // A mod listed as enabled but missing from disk used to be dropped in
    // silence, which reads to the user as "the launcher reset my mods".
    for file_name in &missing_mod_files {
        emit_launch_log(
            &app,
            &format!("[Mods] Enabled mod file is missing and was skipped: {file_name}"),
        );
    }
    // Two jars of one mod id: Fabric Loader 0.19 loads one of them (the newest
    // in every case tested: Fabric API and Mod Menu, the same file twice and
    // two versions, mod repository CI runs 37130892379 and 37130894041) and
    // ignores the rest, so the player would not be playing the copy they may
    // think. The launcher stops and lets them choose, rather than leaving the
    // choice to the loader.
    let added_mods: Vec<PathBuf> = match &breeze_runtime {
        BreezeRuntimeInjection::AddMods(path) => vec![path.clone()],
        _ => Vec::new(),
    };
    let duplicates = duplicate_fabric_mods(&mods_directory, &added_mods);
    if !duplicates.is_empty() {
        let detail = duplicates
            .iter()
            .map(|(id, files)| format!("{id} ({})", files.join(", ")))
            .collect::<Vec<_>>()
            .join("; ");
        return Err(format!(
            "Some mods are installed more than once: {detail}. Fabric would load only one copy and ignore the others. Open Mods for this version and choose which copy to keep; the other is moved to the instance's backups, not deleted. Then press Play again."
        ));
    }

    classpath.push(resolved.client_jar_path.clone());
    classpath.extend(enabled_mod_jars.iter().cloned());

    
    prepare_natives_directory(&natives_directory)?;
    for native_jar in native_jars { extract_native_jar(&native_jar, &natives_directory)?; }

    let logging_path   = ensure_logging_config(&client, &app, &assets_root, &resolved.effective).await?;
    let classpath_value = join_classpath(&classpath)?;
    
    
    
    let max_ram_mb      = request.max_ram_mb.unwrap_or(4980).clamp(2048, 32768);

    
    let mut substitutions = HashMap::from([
        ("auth_player_name".into(),  account.username.clone()),
        ("version_name".into(),      resolved.version_name.clone()),
        
        ("game_directory".into(),    game_directory.to_string_lossy().to_string()),
        ("assets_root".into(),       assets_root.to_string_lossy().to_string()),
        ("assets_index_name".into(), asset_index_name),
        ("auth_uuid".into(),         account.uuid.clone()),
        ("auth_access_token".into(), account.access_token.clone()),
        ("auth_session".into(),      account.access_token.clone()),
        ("clientid".into(),          BREEZE_CLIENT_ID.to_string()),
        ("auth_xuid".into(),         account.xuid.clone().unwrap_or_default()),
        ("user_type".into(),         "msa".into()),
        ("version_type".into(),      json_string(&resolved.effective, &["type"]).unwrap_or_else(|| "release".into())),
        ("natives_directory".into(), natives_directory.to_string_lossy().to_string()),
        ("launcher_name".into(),     "Breeze".into()),
        ("launcher_version".into(),  env!("CARGO_PKG_VERSION").to_string()),
        ("classpath".into(),         classpath_value),
        ("library_directory".into(), libraries_root.to_string_lossy().to_string()),
        ("resolution_width".into(),  "1728".into()),
        ("resolution_height".into(), "972".into()),
        ("quickPlayPath".into(),      game_directory.join("quickPlay").to_string_lossy().to_string()),
        ("quickPlaySingleplayer".into(), String::new()),
        ("quickPlayMultiplayer".into(),  String::new()),
        ("quickPlayRealms".into(),       String::new()),
        ("game_assets".into(),       assets_root.to_string_lossy().to_string()),
        ("user_properties".into(),   "{}".into()),
    ]);
    if let Some(path) = logging_path {
        substitutions.insert("path".into(), path.to_string_lossy().to_string());
    }

    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    
    

    let native_path_value = natives_directory.to_string_lossy().to_string();
    let error_file_path = game_directory.join("java_error.log").to_string_lossy().to_string();

    // ─── JVM tuning ─────────────────────────────────────────────────────────
    // Minecraft's allocation profile is a very high rate of short-lived objects
    // (chunk meshes, particles, vertex buffers) on a modest heap. What matters
    // for a game is FRAME PACING, meaning consistent short pauses, not raw
    // throughput or the lowest possible worst-case pause.
    //
    // G1 is the right default here. ZGC has far lower worst-case pauses, but it
    // achieves that with concurrent work that competes with the render thread
    // for CPU, and it carries a significantly larger memory footprint. Those
    // costs only pay off on large heaps, so ZGC is reserved for 12GB+ where
    // G1's pauses genuinely start to grow.
    let cpus = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(4);
    let total_ram_mb = detect_total_memory_mb().unwrap_or(u64::from(max_ram_mb) * 2);
    // Last line of defence on the heap figure. It can arrive from saved settings
    // written by an older build, from a performance profile, or from an imported
    // Feather config, and an -Xmx larger than the machine either refuses to start
    // the VM ("Could not reserve enough space for object heap", which reaches the
    // user as a bare exit code) or gets backed by the pagefile and stutters for
    // the whole session. Clamping here covers every one of those paths at once.
    let requested_ram_mb = max_ram_mb;
    let max_ram_mb = max_ram_mb.min(safe_heap_ceiling_mb(total_ram_mb) as u32).max(1024);
    if max_ram_mb != requested_ram_mb {
        emit_launch_log(
            &app,
            &format!(
                "Heap reduced from {requested_ram_mb}MB to {max_ram_mb}MB: this machine has {total_ram_mb}MB of RAM and the rest is needed by Windows, the GPU driver and the JVM itself."
            ),
        );
    }
    let gc_profile = select_gc_profile(max_ram_mb, total_ram_mb, cpus);
    let use_zgc = gc_profile == GcProfile::Throughput;

    let mut jvm_args: Vec<String> = vec![
        format!("-Djava.library.path={}", native_path_value),
        "--add-opens=java.desktop/java.awt.event=ALL-UNNAMED".into(),
        "--add-opens=java.desktop/java.awt.color=ALL-UNNAMED".into(),
        "--add-opens=java.desktop/java.awt=ALL-UNNAMED".into(),
        "--add-opens=java.base/java.lang=ALL-UNNAMED".into(),
        "--add-opens=java.base/java.util=ALL-UNNAMED".into(),
        // Xms == Xmx: the heap never resizes, so the stop-the-world pauses that
        // accompany growing and shrinking it never happen.
        format!("-Xms{}M", max_ram_mb),
        format!("-Xmx{}M", max_ram_mb),
        // Blocks debugger attach. Small startup and security win, no runtime cost.
        "-XX:+DisableAttachMechanism".into(),
        // Keeps the JVM from writing perf data to /tmp (or the temp dir) every
        // few milliseconds, which shows up as periodic disk I/O stalls.
        "-XX:+PerfDisableSharedMem".into(),
        // Log4Shell mitigation. Mandatory, not an optimisation.
        "-Dlog4j2.formatMsgNoLookups=true".into(),
        format!("-XX:ErrorFile={}", error_file_path),
        "-Djavax.accessibility.assistive_technologies=".into(),
        "-DFabricMcEmu= net.minecraft.client.main.Main ".into(),
        format!("-Dbreeze.player.uuid={}", account.uuid),
        format!("-Dbreeze.username={}", account.username),
        format!("-Dbreeze.instance.dir={}", game_directory.to_string_lossy()),
        format!("-Dbreeze.home={}", breeze_home.to_string_lossy()),
    ];

    // The runtime jar lives in <instance>/.breeze, not in the mods folder, so
    // Fabric has to be told about it explicitly. Keeping it out of the mods
    // folder is what stops the launcher having to police that folder at all.
    if let BreezeRuntimeInjection::AddMods(path) = &breeze_runtime {
        jvm_args.push(format!("-Dfabric.addMods={}", path.to_string_lossy()));
    }
    if let Some(base) = &api_base {
        jvm_args.push(format!("-Dbreeze.api.url={base}"));
    }
    if game_session_ready {
        jvm_args.push(format!("-Dbreeze.session.file={}", session_file.to_string_lossy()));
    }

    jvm_args.extend(gc_jvm_args_for(gc_profile, cpus, max_ram_mb));

    // --add-opens only exists from Java 9. Java 8 refuses to start with it, and
    // versions up to 1.16 run on Java 8.
    if java.major < 9 {
        jvm_args.retain(|arg| !arg.starts_with("--add-opens"));
    }

    // Deliberately NOT used, recorded so they don't get "helpfully" re-added:
    //   -XX:TieredStopAtLevel=1  cuts startup time but caps JIT at C1, which
    //                            badly hurts sustained FPS. Wrong for a game.
    //   -Xss / -XX:+UseLargePages  no measurable Minecraft benefit; large pages
    //                            need OS privileges most users don't have.
    //   -XX:+AlwaysActAsServerClassMachine  a no-op on modern 64-bit JVMs,
    //                            which already detect server class.
    if cfg!(target_os = "windows") {
        jvm_args.push("-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT".into());
    }
    jvm_args.extend(resolve_arguments(json_array(&resolved.effective, &["arguments", "jvm"]), &substitutions));
    // Versions before 1.13 have no arguments.jvm, so nothing above put the
    // classpath on the command line and the game would not find its classes.
    if !jvm_args.iter().any(|arg| arg == "-cp" || arg == "-classpath") {
        if let Some(classpath) = substitutions.get("classpath") {
            jvm_args.push("-cp".into());
            jvm_args.push(classpath.clone());
        }
    }

    
    let mut game_args = resolve_arguments(json_array(&resolved.effective, &["arguments", "game"]), &substitutions);
    if game_args.is_empty() {
        game_args.extend(resolve_legacy_game_arguments(
            json_string(&resolved.effective, &["minecraftArguments"]).as_deref(),
            &substitutions,
        ));
    }
    
    
    if !game_args.iter().any(|a| a == "--gameDir") {
        game_args.push("--gameDir".into());
        game_args.push(game_directory.to_string_lossy().to_string());
    }

    let main_class = json_string(&resolved.effective, &["mainClass"])
        .ok_or_else(|| "Minecraft metadata did not provide a main class.".to_string())?;

    emit_launch_stage(&app, "launch",
        &format!("Launching {} ({} mods + breezemod)", resolved.version_name, enabled_mod_jars.len()),
        Some(0.92));
    emit_launch_log(&app, &format!("[Launch] Main class: {}", main_class));
    emit_launch_log(&app, &format!(
        "[Launch] JVM: {} | -Xmx{}M | DisableAttach | AlwaysServerClass",
        if use_zgc { "Generational ZGC" } else { "G1GC low-memory profile" },
        max_ram_mb
    ));

    
    
    
    
    
    // Pre-launch validation. Every one of these produced a bare "exit code 1"
    // before, because the JVM was spawned first and only then discovered the
    // problem. Failing here gives the user something actionable instead.
    let java_path = java.java_path.clone();
    {
        if !java_path.is_file() {
            return Err(format!(
                "The Java {} runtime disappeared before launch. Launch again to reinstall it.",
                java.major
            ));
        }
        if !game_directory.exists() {
            return Err(format!(
                "The instance folder for {} is missing. Reinstall this version and try again.",
                resolved.version_name
            ));
        }
        if main_class.trim().is_empty() {
            return Err("This version has no main class recorded, so its metadata is incomplete. Reinstall the version.".into());
        }
        // An empty classpath means library resolution produced nothing, which
        // the JVM reports only as a NoClassDefFoundError after startup.
        let cp_empty = jvm_args
            .iter()
            .position(|a| a == "-cp" || a == "-classpath")
            .and_then(|i| jvm_args.get(i + 1))
            .map(|cp| cp.trim().is_empty())
            .unwrap_or(false);
        if cp_empty {
            return Err("No game libraries were resolved for this version, so Minecraft cannot start. Reinstall the version to repair its libraries.".into());
        }
        emit_launch_log(&app, "[Launch] ✓ Preflight passed: Java, instance, main class and classpath all present");
    }

    let mut command = windowed_command(java_path);
    command
        .current_dir(&game_directory)
        .args(&jvm_args)
        .arg(main_class)
        .args(&game_args)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    // Anything already in crash-reports belongs to an earlier session, so the
    // cut-off is taken before the process exists.
    let launch_started_at = SystemTime::now();

    let mut child = command.spawn().map_err(|e| {
        let _ = fs::remove_file(&session_file);
        remove_runtime_files(&runtime_files);
        format!("Could not start Java: {e}")
    })?;
    let pid = child.id();
    emit_launch_log(&app, &format!("[Launch] Minecraft PID: {}", pid));

    // Keep the tail of stderr so a non-zero exit can report the actual reason.
    // Previously the failure surfaced only as "exited with code 1", which is a
    // generic process status and says nothing about what went wrong: the real
    // stack trace went to the log stream and was then thrown away.
    let stderr_tail: Arc<Mutex<VecDeque<String>>> = Arc::new(Mutex::new(VecDeque::new()));
    // Set as soon as the game prints a line that only a started game prints, so
    // a fast machine is not made to sit out the whole grace period.
    let startup_banner_seen = Arc::new(AtomicBool::new(false));

    if let Some(stdout) = child.stdout.take() {
        let handle = app.clone();
        let banner = Arc::clone(&startup_banner_seen);
        thread::spawn(move || {
            BufReader::new(stdout).lines().map_while(Result::ok)
                .for_each(|line| {
                    if !banner.load(Ordering::Relaxed) && line_is_startup_banner(&line) {
                        banner.store(true, Ordering::Relaxed);
                    }
                    emit_launch_log(&handle, &line);
                });
        });
    }
    if let Some(stderr) = child.stderr.take() {
        let handle = app.clone();
        let tail = Arc::clone(&stderr_tail);
        thread::spawn(move || {
            BufReader::new(stderr).lines().map_while(Result::ok).for_each(|line| {
                if let Ok(mut buf) = tail.lock() {
                    // Bounded: a crash loop must not grow memory without limit.
                    if buf.len() == 40 { buf.pop_front(); }
                    buf.push_back(sanitize_log_line(&line));
                }
                emit_launch_log(&handle, &line);
            });
        });
    }


    let handle = app.clone();
    let version_name = resolved.version_name.clone();
    let game_dir_str  = game_directory.to_string_lossy().to_string();
    let watched_game_dir = game_directory.clone();
    let watched_session_file = session_file.clone();
    let watched_runtime_files = runtime_files.clone();
    let launched_version_id = request.version_id.clone();
    let banner = Arc::clone(&startup_banner_seen);
    thread::spawn(move || {
        // The old code called this a successful launch the instant the process
        // was created, so a JVM that died two seconds later still read as
        // "running". "running" now means the process outlived its startup.
        let deadline = Instant::now() + LAUNCH_GRACE_PERIOD;
        let mut announced_running = false;
        let mut startup_exit: Option<std::process::ExitStatus> = None;

        loop {
            match child.try_wait() {
                Ok(Some(status)) => { startup_exit = Some(status); break; }
                Ok(None) => {
                    if banner.load(Ordering::Relaxed) || Instant::now() >= deadline {
                        announced_running = true;
                        // Remembered only once the game is really running, so a
                        // version that crashes at startup does not become the
                        // version the launcher opens on next time.
                        if let Ok(mut settings) = read_launcher_settings(&handle) {
                            settings.last_launched_version = Some(launched_version_id.clone());
                            settings.last_launched_at = Some(utc_timestamp());
                            let _ = write_launcher_settings(&handle, &settings);
                        }
                        let _ = handle.emit(LAUNCH_COMPLETE_EVENT, LaunchCompleteEvent {
                            status: LAUNCH_STATUS_RUNNING.into(), pid: Some(pid),
                            version_id: version_name.clone(), game_directory: game_dir_str.clone(),
                            message: "Minecraft is running.".into(),
                            detail: None, report_path: None,
                        });
                        break;
                    }
                    thread::sleep(Duration::from_millis(200));
                }
                Err(e) => {
                    emit_launch_error(&handle, &format!("Could not wait for Minecraft: {e}"));
                    return;
                }
            }
        }

        let status = match startup_exit {
            Some(status) => Ok(status),
            None => child.wait(),
        };

        // The mod deletes it on read; this covers a game that never got that far.
        let _ = fs::remove_file(&watched_session_file);
        let left = remove_runtime_files(&watched_runtime_files);
        if !watched_runtime_files.is_empty() {
            emit_launch_log(&handle, &if left == 0 {
                "[Breeze Mod] runtime jar removed after the game closed".to_string()
            } else {
                format!("[Breeze Mod] {left} runtime file(s) still in use; they are replaced on the next launch")
            });
        }

        // A crash report or a JVM error file written since launch is a far
        // better explanation than an exit code, and Minecraft exits 0 on plenty
        // of crashes.
        let crash = find_crash_evidence(&watched_game_dir, launch_started_at);

        match status {
            Ok(status) if status.success() || crash.is_some() => {
                if let Some((report_path, headline)) = crash {
                    let _ = handle.emit(LAUNCH_COMPLETE_EVENT, LaunchCompleteEvent {
                        status: LAUNCH_STATUS_CRASHED.into(), pid: None,
                        version_id: version_name, game_directory: game_dir_str,
                        message: "Minecraft crashed.".into(),
                        detail: Some(sanitize_log_line(&headline)),
                        report_path: Some(report_path.to_string_lossy().to_string()),
                    });
                    emit_launch_error(&handle, &format!("Minecraft crashed: {}", sanitize_log_line(&headline)));
                    return;
                }
                if !announced_running {
                    // Died inside the grace period with nothing to show for it.
                    let _ = handle.emit(LAUNCH_COMPLETE_EVENT, LaunchCompleteEvent {
                        status: LAUNCH_STATUS_STARTUP_FAILED.into(), pid: None,
                        version_id: version_name, game_directory: game_dir_str,
                        message: "Minecraft closed before it finished starting.".into(),
                        detail: None, report_path: None,
                    });
                    return;
                }
                let _ = handle.emit(LAUNCH_COMPLETE_EVENT, LaunchCompleteEvent {
                    status: LAUNCH_STATUS_EXITED.into(), pid: None,
                    version_id: version_name, game_directory: game_dir_str,
                    message: "Minecraft closed.".into(),
                    detail: None, report_path: None,
                });
            }
            Ok(status) => {
                // Surface the real cause. Prefer lines that actually explain a
                // JVM failure over incidental warnings, so the message the user
                // sees is the exception, not the last thing printed.
                let tail: Vec<String> = stderr_tail
                    .lock()
                    .map(|b| b.iter().cloned().collect())
                    .unwrap_or_default();
                let detail = stderr_failure_detail(&tail);
                let code = status.code().map(|c| c.to_string()).unwrap_or_else(|| "signal".into());
                if detail.is_empty() {
                    emit_launch_error(&handle, &format!(
                        "Minecraft exited with code {code} and printed no error. Open the Console tab for the full launch log."
                    ));
                } else {
                    emit_launch_error(&handle, &format!("Minecraft exited with code {code}: {detail}"));
                }
                // Dying inside the grace period is a startup failure, not a
                // session that ended, and the UI treats the two differently.
                let status_name = if announced_running {
                    LAUNCH_STATUS_CRASHED
                } else {
                    LAUNCH_STATUS_STARTUP_FAILED
                };
                let _ = handle.emit(LAUNCH_COMPLETE_EVENT, LaunchCompleteEvent {
                    status: status_name.into(), pid: None,
                    version_id: version_name, game_directory: game_dir_str,
                    message: format!("Minecraft exited with code {code}."),
                    detail: (!detail.is_empty()).then_some(detail),
                    report_path: None,
                });
            }
            Err(e)     => emit_launch_error(&handle, &format!("Could not wait for Minecraft: {e}")),
        }
    });

    let complete = LaunchCompleteEvent {
        status: LAUNCH_STATUS_STARTING.into(),
        pid: Some(pid),
        version_id: resolved.version_name,
        game_directory: game_directory.to_string_lossy().to_string(),
        message: "Minecraft is starting…".into(),
        detail: None,
        report_path: None,
    };
    let _ = app.emit(LAUNCH_COMPLETE_EVENT, complete.clone());
    Ok(complete)
}

/// Whether a stdout line proves the game itself has started.
///
/// Used to shorten the startup grace period. Deliberately narrow: a line that
/// any JVM could print would let a failing launch claim it is running.
fn line_is_startup_banner(line: &str) -> bool {
    let lower = line.to_ascii_lowercase();
    lower.contains("loading minecraft")
        || lower.contains("loading for game minecraft")
        || lower.contains("setting user:")
        || lower.contains("backend library: lwjgl")
        || lower.contains("openal initialized")
        || lower.contains("narrator library")
}

/// The first line of a crash report worth showing the user.
///
/// Minecraft puts a one-line "Description:" near the top; a JVM error file has
/// no such line, so the first exception line stands in for it.
fn crash_report_headline(text: &str) -> Option<String> {
    let mut exception: Option<String> = None;
    for line in text.lines().take(200) {
        let trimmed = line.trim();
        if let Some(description) = trimmed.strip_prefix("Description:") {
            let description = description.trim();
            if !description.is_empty() {
                return Some(description.to_string());
            }
        }
        if exception.is_none()
            && !trimmed.is_empty()
            && (trimmed.contains("Exception") || trimmed.contains("Error:") || trimmed.starts_with("# "))
        {
            exception = Some(trimmed.to_string());
        }
    }
    exception
}

/// A crash report or JVM error file written since `since`, with its headline.
///
/// Minecraft exits 0 after plenty of crashes, so an exit status on its own is
/// not enough to tell the user their session ended normally.
fn find_crash_evidence(game_dir: &Path, since: SystemTime) -> Option<(PathBuf, String)> {
    let written_since = |path: &Path| -> bool {
        fs::metadata(path)
            .and_then(|meta| meta.modified())
            .map(|modified| modified >= since)
            .unwrap_or(false)
    };

    let mut newest: Option<(SystemTime, PathBuf)> = None;
    if let Ok(entries) = fs::read_dir(game_dir.join("crash-reports")) {
        for entry in entries.flatten() {
            let path = entry.path();
            if !path.is_file() || !written_since(&path) {
                continue;
            }
            let modified = entry.metadata().and_then(|meta| meta.modified()).unwrap_or(since);
            if newest.as_ref().map(|(seen, _)| modified > *seen).unwrap_or(true) {
                newest = Some((modified, path));
            }
        }
    }

    let candidate = newest.map(|(_, path)| path).or_else(|| {
        let error_file = game_dir.join("java_error.log");
        written_since(&error_file).then_some(error_file)
    })?;

    let headline = fs::read_to_string(&candidate)
        .ok()
        .and_then(|text| crash_report_headline(&text))
        .unwrap_or_else(|| "See the crash report for details.".to_string());
    Some((candidate, headline))
}

async fn ensure_launch_version(client: &reqwest::Client, app: &AppHandle, minecraft_home: &Path, version_id: &str, loader_version: Option<&str>) -> Result<ResolvedLaunchVersion, String> {
    emit_launch_stage(app, "metadata", &format!("Resolving Minecraft {}", version_id), Some(0.08));

    let version_dir = minecraft_home.join("versions").join(version_id);
    let version_json_path = version_dir.join(format!("{version_id}.json"));

    
    
    
    let vanilla = if version_json_path.exists() {
        
        read_json_value(&version_json_path)?
    } else {
        
        let manifest: Value = fetch_json(client, MOJANG_VERSION_MANIFEST_URL).await?;
        let version_url = json_array(&manifest, &["versions"])
            .and_then(|versions| {
                versions.iter().find_map(|entry| {
                    if entry.get("id").and_then(Value::as_str) == Some(version_id) {
                        entry.get("url").and_then(Value::as_str).map(str::to_string)
                    } else {
                        None
                    }
                })
            })
            .ok_or_else(|| format!("Minecraft version {} is not in Mojang manifest.", version_id))?;

        fs::create_dir_all(&version_dir)
            .map_err(|e| format!("Could not create version directory: {e}"))?;
        let value: Value = fetch_json(client, &version_url).await?;
        write_json_value(&version_json_path, &value)?;
        value
    };

    
    let client_url = json_string(&vanilla, &["downloads", "client", "url"])
        .ok_or_else(|| "Minecraft client download metadata is missing.".to_string())?;
    let client_jar_path = version_dir.join(format!("{version_id}.jar"));
    // Mojang publishes the jar's sha1 beside its URL, so there is no reason to
    // take a corrupt client jar on trust and then fail at launch instead.
    let client_sha1 = json_string(&vanilla, &["downloads", "client", "sha1"]);
    ensure_download_checked(client, &client_jar_path, &client_url, client_sha1.as_deref()).await?;

    
    let chosen_loader_version = match loader_version {
        Some(version) if !version.trim().is_empty() => version.to_string(),
        _ => fetch_latest_fabric_loader(client, version_id).await?,
    };
    emit_launch_stage(app, "fabric", &format!("Resolving Fabric {}", chosen_loader_version), Some(0.12));

    let fabric_version_name = format!("fabric-loader-{}-{}", chosen_loader_version, version_id);
    let fabric_dir = minecraft_home.join("versions").join(&fabric_version_name);
    let fabric_json_path = fabric_dir.join(format!("{fabric_version_name}.json"));

    let fabric = if fabric_json_path.exists() {
        
        read_json_value(&fabric_json_path)?
    } else {
        let url = format!("{FABRIC_PROFILE_URL}/{}/{}/profile/json", version_id, chosen_loader_version);
        let value: Value = fetch_json(client, &url).await?;
        write_json_value(&fabric_json_path, &value)?;
        value
    };

    Ok(ResolvedLaunchVersion {
        effective: merge_version_values(vanilla.clone(), fabric.clone()),
        version_name: json_string(&fabric, &["id"]).unwrap_or(fabric_version_name),
        client_jar_path,
        loader_version: chosen_loader_version,
    })
}


async fn pre_launch_ensure_dependencies(
    client: &reqwest::Client,
    app: &AppHandle,
    profile_id: &str,
    game_version: &str,
    loader: &str,
) -> Result<(), String> {
    emit_launch_stage(app, "dependencies", "Checking mod dependencies…", Some(0.05));

    let manifest_path = profile_manifest_path(app, profile_id)?;
    let mods_dir = profile_mods_dir(app, profile_id)?;
    let mut manifest = read_mod_manifest(&manifest_path)?;

    // A manifest record is not evidence that Fabric API will load. The live
    // profile held a record whose jar had been deleted, so the launcher decided
    // Fabric API was present and every mod that needed it silently failed.
    let has_fabric_api = manifest.iter().any(|record| {
        is_fabric_api_record(record) && is_valid_jar(&mods_dir.join(&record.file_name))
    })
        // A Fabric API the player put in the folder themselves is just as
        // installed. Checking only the records is what added a second copy
        // beside it, which Fabric refuses to start with.
        || mods_folder_has_mod(&mods_dir, "fabric-api");
    // Switched off by the player: neither a stale record nor missing, and a
    // second copy beside it would be a duplicate once it is switched on.
    let fabric_api_off = !has_fabric_api
        && mods_local::scan(&mods_dir).iter().any(|jar| !jar.enabled && jar.mod_id() == Some("fabric-api"));
    if fabric_api_off {
        emit_launch_log(
            app,
            "[Dependencies] Fabric API is switched off for this version, so a second copy is not installed. Switch it on in Mods if your mods need it.",
        );
    }
    let has_fabric_api = has_fabric_api || fabric_api_off;

    if !has_fabric_api {
        // Drop the stale records first, or the reinstall would be treated as a
        // duplicate of a record pointing at a file that no longer exists.
        let before = manifest.len();
        manifest.retain(|record| !is_fabric_api_record(record));
        if manifest.len() != before {
            emit_launch_log(
                app,
                "[Dependencies] Fabric API was recorded as installed but its jar is gone, reinstalling it.",
            );
            write_mod_manifest(&manifest_path, &manifest)?;
        }
    }

    let fabric_api_required = fabric_api_is_required(&manifest);

    if !has_fabric_api {
        emit_launch_log(app, "[Dependencies] Fabric API not detected, auto-installing for this version…");
        emit_launch_stage(app, "dependencies", "Installing Fabric API…", Some(0.06));

        let mut seen: HashSet<String> = HashSet::new();
        match install_modrinth_project_recursive(
            client,
            app,
            profile_id,
            game_version,
            loader,
            InstallModRequest {
                profile_id: profile_id.to_string(),
                game_version: game_version.to_string(),
                loader: loader.to_string(),
                project_id: FABRIC_API_PROJECT_ID.to_string(),
                project_slug: "fabric-api".to_string(),
                title: "Fabric API".to_string(),
                summary: Some("Required core library for Fabric mods".to_string()),
                icon_url: None,
            },
            &mut seen,
        )
        .await
        {
            Ok(record) => {
                emit_launch_log(
                    app,
                    &format!(
                        "[Dependencies] ✓ Fabric API {} installed automatically for Minecraft {}",
                        record.installed_version_name, game_version
                    ),
                );
            }
            Err(e) => {
                if fabric_api_required {
                    // Continuing here launches a game where nothing loads, then
                    // blames the mods. Stopping now says what actually happened.
                    emit_launch_log(
                        app,
                        &format!("[Dependencies] ✗ Fabric API could not be installed: {e}"),
                    );
                    return Err(format!(
                        "Fabric API is required by the mods you have enabled, and it could not be installed: {e}. \
                         Check your connection and launch again, or disable those mods."
                    ));
                }
                emit_launch_log(
                    app,
                    &format!(
                        "[Dependencies] Warning: Could not auto-install Fabric API: {}. \
                         No mods are enabled, so the launch continues.",
                        e
                    ),
                );
            }
        }
    } else {
        emit_launch_log(app, "[Dependencies] ✓ Fabric API present");
    }

    ensure_mcef(client, app, profile_id, game_version, loader, &mods_dir, &manifest_path).await;


    let manifest = read_mod_manifest(&manifest_path)?; 
    let mut incompatible: Vec<String> = Vec::new();
    let mut outdated: Vec<String> = Vec::new();

    for record in &manifest {
        if !record.enabled { continue; }
        if is_custom_mod_record(record) { continue; }
        if is_fabric_api_record(record) { continue; }

        if !record.game_version.is_empty() && record.game_version != game_version {
            
            if record.loader.to_ascii_lowercase() == loader.to_ascii_lowercase() {
                outdated.push(format!("{} (installed for {})", record.title, record.game_version));
            } else {
                incompatible.push(format!(
                    "{} (installed for {} / {})",
                    record.title, record.game_version, record.loader
                ));
            }
        }
    }

    if !outdated.is_empty() {
        emit_launch_log(
            app,
            &format!(
                "[Dependencies] ⚠ {} mod(s) were installed for a different Minecraft version and may cause crashes: {}. \
                 Consider reinstalling them via the Mods tab for {}.",
                outdated.len(),
                outdated.join(", "),
                game_version
            ),
        );
    }

    if !incompatible.is_empty() {
        emit_launch_log(
            app,
            &format!(
                "[Dependencies] ✗ {} mod(s) are incompatible with the current loader and will be skipped: {}",
                incompatible.len(),
                incompatible.join(", ")
            ),
        );
    }

    if outdated.is_empty() && incompatible.is_empty() {
        emit_launch_log(app, "[Dependencies] ✓ All installed mods are version-compatible");
    }

    emit_launch_stage(app, "dependencies", "Dependencies validated", Some(0.08));
    Ok(())
}

/// Installs MCEF from Modrinth when this version's mods folder has no jar that
/// declares its mod id. MCEF has one build per Minecraft version; where it has
/// none, nothing is installed and Breeze keeps its native menus. Never fatal:
/// a launch without MCEF still goes ahead, and the reason is in the log.
///
/// A jar is what counts, not a manifest record (the same lesson as Fabric API:
/// a record whose jar was deleted said "installed" while nothing loaded), and
/// any jar with the mod id counts, whoever put it there, because two MCEF jars
/// stop Fabric at start. An MCEF the user switched off in the Mods tab stays
/// off.
async fn ensure_mcef(
    client: &reqwest::Client,
    app: &AppHandle,
    profile_id: &str,
    game_version: &str,
    loader: &str,
    mods_dir: &Path,
    manifest_path: &Path,
) {
    if !loader.eq_ignore_ascii_case("fabric") {
        return;
    }
    let mut manifest = match read_mod_manifest(manifest_path) {
        Ok(manifest) => manifest,
        Err(error) => {
            emit_launch_log(app, &format!("[Dependencies] MCEF not checked: {error}"));
            return;
        }
    };
    if manifest.iter().any(|record| is_mcef_record(record) && !record.enabled) {
        emit_launch_log(app, "[Dependencies] MCEF is switched off for this version, so it is not installed");
        return;
    }
    if mods_folder_has_mod(mods_dir, MCEF_MOD_ID) {
        emit_launch_log(app, "[Dependencies] ✓ MCEF present");
        return;
    }

    // Records of an MCEF whose jar is gone would make the reinstall look like a
    // duplicate of them.
    let before = manifest.len();
    manifest.retain(|record| !is_mcef_record(record));
    if manifest.len() != before {
        if let Err(error) = write_mod_manifest(manifest_path, &manifest) {
            emit_launch_log(app, &format!("[Dependencies] MCEF not installed: {error}"));
            return;
        }
    }

    emit_launch_log(app, "[Dependencies] MCEF not found, installing it for this version…");
    emit_launch_stage(app, "dependencies", "Installing MCEF (Breeze menu browser)…", Some(0.07));
    // Fabric API is already in place by now. Marking it seen keeps it from
    // being downloaded a second time as one of MCEF's dependencies, which
    // could leave two Fabric API jars and stop Fabric at start.
    let mut seen: HashSet<String> = HashSet::new();
    // Fabric API's mod id is "fabric-api", and "fabric" on older versions.
    if manifest.iter().any(is_fabric_api_record)
        || mods_folder_has_mod(mods_dir, "fabric-api")
        || mods_folder_has_mod(mods_dir, "fabric")
    {
        seen.insert(FABRIC_API_PROJECT_ID.to_string());
    }
    let request = InstallModRequest {
        profile_id: profile_id.to_string(),
        game_version: game_version.to_string(),
        loader: loader.to_string(),
        project_id: String::new(),
        project_slug: MCEF_PROJECT_SLUG.to_string(),
        title: "MCEF".to_string(),
        summary: Some("Embedded browser for the Breeze menu".to_string()),
        icon_url: None,
    };
    match install_modrinth_project_recursive(client, app, profile_id, game_version, loader, request, &mut seen).await {
        Ok(record) => emit_launch_log(
            app,
            &format!(
                "[Dependencies] ✓ MCEF {} installed for Minecraft {} (it downloads its browser files the first time the game starts)",
                record.installed_version_name, game_version
            ),
        ),
        Err(error) => emit_launch_log(
            app,
            &format!("[Dependencies] MCEF not installed for Minecraft {game_version}: {error} Breeze uses its native menus."),
        ),
    }
}

fn is_mcef_record(record: &ManagedModRecord) -> bool {
    record.project_slug.eq_ignore_ascii_case(MCEF_PROJECT_SLUG)
        || record.project_id.eq_ignore_ascii_case(MCEF_PROJECT_SLUG)
}

/// Whether any jar directly in the mods folder declares this Fabric mod id.
fn mods_folder_has_mod(mods_dir: &Path, mod_id: &str) -> bool {
    let Ok(entries) = fs::read_dir(mods_dir) else { return false };
    entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.is_file() && path.extension().map_or(false, |ext| ext.eq_ignore_ascii_case("jar")))
        .any(|path| fabric_mod_id(&path).as_deref() == Some(mod_id))
}


/// What the launcher knows about the Breeze runtime jar it installed.
///
/// Recorded so nothing downstream has to guess from a filename. Guessing is
/// what made the launcher delete user mods and load stale builds.
#[derive(Serialize, Deserialize, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
struct BreezeRuntimeSidecar {
    file_name: String,
    sha256: String,
    mc_version: String,
    installed_at: String,
}

fn breeze_runtime_dir(profile_root: &Path) -> PathBuf {
    profile_root.join(BREEZE_RUNTIME_DIR)
}

fn breeze_runtime_jar_path(profile_root: &Path) -> PathBuf {
    breeze_runtime_dir(profile_root).join(BREEZE_RUNTIME_JAR)
}

fn breeze_runtime_sidecar_path(profile_root: &Path) -> PathBuf {
    breeze_runtime_dir(profile_root).join(BREEZE_RUNTIME_SIDECAR)
}

fn read_breeze_runtime_sidecar(profile_root: &Path) -> Option<BreezeRuntimeSidecar> {
    let path = breeze_runtime_sidecar_path(profile_root);
    let content = fs::read_to_string(path).ok()?;
    serde_json::from_str(&content).ok()
}

fn write_breeze_runtime_sidecar(profile_root: &Path, sidecar: &BreezeRuntimeSidecar) -> Result<(), String> {
    let content = serde_json::to_string_pretty(sidecar)
        .map_err(|error| format!("Could not serialize the Breeze runtime record: {error}"))?;
    write_file_atomic(&breeze_runtime_sidecar_path(profile_root), &content)
}

/// Mods-folder file names that belong to the launcher rather than to the user.
fn breeze_managed_mod_file_names(app: &AppHandle, profile_id: &str) -> Vec<String> {
    let mut names = vec![LEGACY_BREEZE_MOD_NAME.to_string()];
    if let Ok(profile_root) = profile_root_dir(app, profile_id) {
        if let Some(sidecar) = read_breeze_runtime_sidecar(&profile_root) {
            let recorded = sidecar.file_name.trim();
            if !recorded.is_empty() {
                names.push(recorded.to_string());
            }
        }
    }
    names
}

/// Files in the mods folder the launcher is allowed to delete when it refreshes
/// the runtime jar.
///
/// The old code deleted every `breeze*.jar` it found on every launch, which
/// destroyed any user mod whose name happened to start with "breeze". Only two
/// files can be launcher leftovers: the fixed legacy name, and whatever the
/// sidecar says was installed last. Neither is touched when the manifest claims
/// it, because then it is a mod the user installed.
fn runtime_cleanup_targets(
    manifest_file_names: &[String],
    sidecar_file_name: Option<&str>,
    keep_file_name: Option<&str>,
) -> Vec<String> {
    let referenced: HashSet<String> = manifest_file_names
        .iter()
        .map(|name| name.trim().to_ascii_lowercase())
        .filter(|name| !name.is_empty())
        .collect();
    let keep = keep_file_name.map(|name| name.trim().to_ascii_lowercase());

    let mut seen: HashSet<String> = HashSet::new();
    let mut targets: Vec<String> = Vec::new();
    for candidate in [Some(LEGACY_BREEZE_MOD_NAME), sidecar_file_name].into_iter().flatten() {
        let candidate = candidate.trim();
        let key = candidate.to_ascii_lowercase();
        // The addMods copy lives outside the mods folder, so it is never a
        // cleanup target here.
        if key.is_empty() || key == BREEZE_RUNTIME_JAR {
            continue;
        }
        if referenced.contains(&key) || keep.as_deref() == Some(key.as_str()) {
            continue;
        }
        if seen.insert(key) {
            targets.push(candidate.to_string());
        }
    }
    targets
}

/// The API root behind a legacy `<api>/versions/mod/<mc>.jar` URL.
fn breeze_api_base_from_mod_url(url: &str) -> Option<String> {
    let marker = "/versions/mod/";
    url.find(marker).map(|index| url[..index].trim_end_matches('/').to_string())
}

/// What happened when the launcher went looking for the Breeze runtime jar.
///
/// An `Option<PathBuf>` could not tell "no build is published for this Minecraft
/// version" apart from "the download failed", so the UI said the same vague
/// thing for both and the user could not tell whether to wait or to retry.
enum BreezeModOutcome {
    Injected(PathBuf),
    NotPublished,
    DownloadFailed(String),
}

async fn ensure_remote_breezemod(
    client: &reqwest::Client,
    app: &AppHandle,
    url: Option<&str>,
    expected_sha256: Option<&str>,
    mc_version: &str,
    breeze_token: Option<&str>,
) -> BreezeModOutcome {
    let Some(legacy_url) = url.map(str::trim).filter(|value| !value.is_empty()) else {
        return BreezeModOutcome::NotPublished;
    };

    // The authorized endpoint first, the old public path only as a fallback for
    // an API that has not been updated yet.
    let mut candidates: Vec<(String, bool)> = Vec::new();
    if let Some(base) = breeze_api_base_from_mod_url(legacy_url).as_deref().and_then(trusted_api_base) {
        candidates.push((format!("{base}/mod/runtime/{mc_version}"), true));
    }
    candidates.push((legacy_url.to_string(), false));

    let cache_path = match breeze_home_dir() {
        Ok(home) => home
            .join("versions")
            .join("mods")
            .join(sanitize_filename(&format!("{mc_version}.jar"))),
        Err(error) => return BreezeModOutcome::DownloadFailed(error),
    };

    // A .part file here is a download an earlier launch never finished.
    let _ = fs::remove_file(cache_path.with_extension("jar.part"));

    emit_launch_log(
        app,
        &format!("[Breeze Mod] downloading compatible build for Minecraft {mc_version}"),
    );

    let token = breeze_token.map(str::trim).filter(|value| !value.is_empty());
    if token.is_none() {
        emit_launch_log(app, "[Breeze Mod] not signed in to Breeze, the authorized download needs a Breeze session");
    }
    let mut last_error: Option<String> = None;
    let mut not_published = false;

    for (candidate, authorized) in candidates {
        emit_launch_log(app, &format!("[Breeze Mod] source: {candidate}"));

        let mut pending = client.get(&candidate);
        if authorized {
            if let Some(token) = token {
                pending = pending.bearer_auth(token);
            }
        }

        let response = match pending.send().await {
            Ok(response) => response,
            Err(error) => {
                last_error = Some(format!("{candidate} could not be reached: {error}"));
                continue;
            }
        };

        let status = response.status();
        if status == reqwest::StatusCode::NOT_FOUND {
            // No build for this Minecraft version on this endpoint; the older
            // path may still have one.
            not_published = true;
            last_error = Some(format!("{candidate} returned 404"));
            continue;
        }
        if !status.is_success() {
            last_error = Some(format!("{candidate} returned {status}"));
            continue;
        }

        let header_sha256 = response
            .headers()
            .get(BREEZE_SHA256_HEADER)
            .and_then(|value| value.to_str().ok())
            .map(str::to_string);

        let bytes = match response.bytes().await {
            Ok(bytes) => bytes,
            Err(error) => {
                last_error = Some(format!("{candidate} could not be read: {error}"));
                continue;
            }
        };

        // A 404 page or a maintenance banner is not a jar, and writing one to
        // the cache is how a stale "mod" ended up being loaded.
        if bytes.len() < 4 || bytes[0] != 0x50 || bytes[1] != 0x4B {
            not_published = true;
            last_error = Some(format!("{candidate} did not return a jar"));
            continue;
        }

        // Every digest the server or the launch request gave us has to agree.
        // A mismatch is never recoverable by trying the next URL, so it fails
        // the whole lookup rather than silently falling back.
        let mut verified = false;
        for expected in [header_sha256.as_deref(), expected_sha256].into_iter().flatten() {
            if !sha256_matches(&bytes, expected) {
                emit_launch_log(
                    app,
                    "[Breeze Mod] refusing the download: the jar does not match the sha256 the API published",
                );
                return BreezeModOutcome::DownloadFailed(
                    "The Breeze runtime jar failed its sha256 check.".into(),
                );
            }
            verified = true;
        }

        if let Some(parent) = cache_path.parent() {
            if let Err(error) = fs::create_dir_all(parent) {
                return BreezeModOutcome::DownloadFailed(format!(
                    "Could not create the Breeze mod cache directory: {error}"
                ));
            }
        }
        // Through a .part file, so a crash or a full disk mid-write never
        // leaves a truncated jar where the next launch would look for one.
        let partial = cache_path.with_extension("jar.part");
        if let Err(error) = fs::write(&partial, &bytes).and_then(|_| fs::rename(&partial, &cache_path)) {
            let _ = fs::remove_file(&partial);
            return BreezeModOutcome::DownloadFailed(format!(
                "Could not cache the Breeze runtime jar: {error}"
            ));
        }

        if verified {
            emit_launch_log(
                app,
                &format!("[Breeze Mod] downloaded successfully ({} bytes, sha256 verified)", bytes.len()),
            );
        } else {
            // Said plainly rather than implied, because an unverified jar is a
            // weaker guarantee than the line above.
            emit_launch_log(
                app,
                &format!(
                    "[Breeze Mod] downloaded successfully ({} bytes, no sha256 published by the API)",
                    bytes.len()
                ),
            );
        }
        emit_launch_log(app, &format!("[Breeze Mod] cached at {}", cache_path.display()));
        return BreezeModOutcome::Injected(cache_path);
    }

    // Nothing downloaded. A jar already in the cache still launches, as long as
    // it matches the digest we were told to expect.
    if cached_jar_is_current(&cache_path, expected_sha256) {
        emit_launch_log(app, &format!("[Breeze Mod] cached at {}", cache_path.display()));
        return BreezeModOutcome::Injected(cache_path);
    }

    if not_published {
        return BreezeModOutcome::NotPublished;
    }
    BreezeModOutcome::DownloadFailed(
        last_error.unwrap_or_else(|| "The Breeze runtime jar could not be downloaded.".into()),
    )
}

/// Warnings the JVM or its libraries print on healthy starts. Quoting one of
/// these as the reason a game stopped sends the player after the wrong thing:
/// a force-closed 1.20.1 was reported as "Unsupported JNI version detected",
/// which LWJGL 3.3.1 prints on every Java 21 start.
const BENIGN_STDERR: &[&str] = &[
    "Unsupported JNI version detected",
    "SLF4J:",
    "WARNING: A restricted method",
    "WARNING: A terminally deprecated method",
    "WARNING: sun.misc.Unsafe",
    "WARNING: Use --enable-native-access",
    "WARNING: Please consider reporting this",
    "will be removed in a future release",
];

/// The lines of a game's stderr that explain why it stopped, or an empty string
/// when it printed nothing that does. An empty answer is reported as "printed
/// no error", which is honest; a guess from the last few lines is not.
fn stderr_failure_detail(tail: &[String]) -> String {
    let lines: Vec<&str> = tail
        .iter()
        .map(|line| line.trim())
        .filter(|line| !line.is_empty() && !BENIGN_STDERR.iter().any(|benign| line.contains(benign)))
        .filter(|line| {
            line.contains("Exception")
                || line.contains("Error")
                || line.contains("error:")
                || line.contains("Caused by")
                || line.contains("Could not")
                || line.contains("Unsupported")
                || line.contains("ClassNotFound")
                || line.contains("UnsatisfiedLink")
                || line.starts_with("Unrecognized")
                || line.contains("java.lang")
        })
        .collect();
    lines.iter().rev().take(4).rev().copied().collect::<Vec<_>>().join(" | ")
}

/// Delete the session's runtime jar copies. Returns how many could not be
/// removed, which on Windows means another running game still holds them.
fn remove_runtime_files(files: &[PathBuf]) -> usize {
    files
        .iter()
        .filter(|path| path.exists() && fs::remove_file(path).is_err())
        .count()
}

/// Breeze jars in the mods folder that the launcher put there and no longer
/// manages: they declare the Breeze mod id and no user-installed record names
/// them. `exclude` is the jar this launch is about to use from that folder.
fn legacy_breeze_jars(mods_dir: &Path, manifest: &[ManagedModRecord], exclude: Option<&Path>) -> Vec<PathBuf> {
    let user_files: HashSet<String> = manifest
        .iter()
        .filter(|record| record.project_id != "breeze:core")
        .map(|record| record.file_name.to_ascii_lowercase())
        .collect();
    let Ok(entries) = fs::read_dir(mods_dir) else { return Vec::new() };
    entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.is_file() && path.extension().map_or(false, |ext| ext.eq_ignore_ascii_case("jar")))
        .filter(|path| exclude.map_or(true, |excluded| path != excluded))
        .filter(|path| {
            let name = path.file_name().map(|n| n.to_string_lossy().to_ascii_lowercase()).unwrap_or_default();
            !user_files.contains(&name)
        })
        .filter(|path| fabric_mod_id(path).as_deref() == Some(BREEZE_MOD_ID))
        .collect()
}

/// The Fabric mod id a jar declares, or None for anything that is not a Fabric mod.
fn fabric_mod_id(jar: &Path) -> Option<String> {
    let file = fs::File::open(jar).ok()?;
    let mut archive = ZipArchive::new(std::io::BufReader::new(file)).ok()?;
    let mut entry = archive.by_name("fabric.mod.json").ok()?;
    if entry.size() > 1024 * 1024 {
        return None;
    }
    let mut text = String::new();
    std::io::Read::read_to_string(&mut entry, &mut text).ok()?;
    let value: Value = serde_json::from_str(text.trim_start_matches('\u{feff}')).ok()?;
    value.get("id")?.as_str().map(str::to_string)
}

/// Mod ids declared by more than one jar in the mods folder (plus jars handed
/// to Fabric separately), each with the file names involved, sorted by id.
fn duplicate_fabric_mods(mods_dir: &Path, extra: &[PathBuf]) -> Vec<(String, Vec<String>)> {
    let mut jars: Vec<PathBuf> = fs::read_dir(mods_dir)
        .map(|entries| {
            entries
                .flatten()
                .map(|entry| entry.path())
                .filter(|path| {
                    path.is_file()
                        && path.extension().map_or(false, |ext| ext.eq_ignore_ascii_case("jar"))
                })
                .collect()
        })
        .unwrap_or_default();
    jars.extend(extra.iter().filter(|path| path.is_file()).cloned());

    let mut by_id: HashMap<String, Vec<String>> = HashMap::new();
    for jar in &jars {
        if let Some(id) = fabric_mod_id(jar) {
            let name = jar.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
            by_id.entry(id).or_default().push(name);
        }
    }
    let mut duplicates: Vec<(String, Vec<String>)> = by_id
        .into_iter()
        .filter(|(_, files)| files.len() > 1)
        .map(|(id, mut files)| {
            files.sort();
            (id, files)
        })
        .collect();
    duplicates.sort();
    duplicates
}

/// How the Breeze runtime jar reaches the game.
enum BreezeRuntimeInjection {
    /// Outside the mods folder, handed to Fabric with -Dfabric.addMods.
    AddMods(PathBuf),
    /// Copied into the mods folder, for loaders that predate addMods.
    ModsFolder(PathBuf),
    None,
}

fn ensure_breezemod_injected(
    app: &AppHandle,
    profile_root: &Path,
    mods_dir: &Path,
    manifest: &[ManagedModRecord],
    mc_version: &str,
    loader_version: &str,
    remote_source: Option<&Path>,
) -> Result<BreezeRuntimeInjection, String> {
    fs::create_dir_all(mods_dir)
        .map_err(|e| format!("Could not create mods directory: {e}"))?;

    // The manifest is read before anything is deleted, so no file the user
    // installed can be mistaken for a launcher leftover.
    let manifest_file_names: Vec<String> =
        manifest.iter().map(|record| record.file_name.clone()).collect();
    let previous = read_breeze_runtime_sidecar(profile_root);

    // Below 0.12.0 the loader ignores -Dfabric.addMods entirely, so the jar has
    // to go back into the mods folder or it simply never loads.
    let add_mods_supported = !version_is_newer(FABRIC_ADD_MODS_MIN_LOADER, loader_version);

    let (dest, recorded_name) = if add_mods_supported {
        let runtime_dir = breeze_runtime_dir(profile_root);
        fs::create_dir_all(&runtime_dir)
            .map_err(|e| format!("Could not create the Breeze runtime directory: {e}"))?;
        (breeze_runtime_jar_path(profile_root), BREEZE_RUNTIME_JAR.to_string())
    } else {
        // Neutral, version-based filename (e.g. 1.20.1.jar), so the mods folder
        // shows no obvious branding.
        let neutral = format!("{}.jar", sanitize_filename(mc_version));
        (mods_dir.join(&neutral), neutral)
    };

    let installed = match remote_source.filter(|path| is_valid_jar(path)) {
        Some(src) => {
            fs::copy(src, &dest)
                .map_err(|e| format!("Could not install the Breeze runtime jar from {}: {e}", src.display()))?;
            true
        }
        // No fresh download; a valid jar from a previous launch still runs.
        None => is_valid_jar(&dest),
    };

    // Clean up only what the launcher itself can have left behind.
    for name in runtime_cleanup_targets(
        &manifest_file_names,
        previous.as_ref().map(|sidecar| sidecar.file_name.as_str()),
        Some(&recorded_name),
    ) {
        let stale = mods_dir.join(&name);
        if stale.exists() && is_inside_dir(mods_dir, &stale) {
            let _ = fs::remove_file(&stale);
        }
    }

    // Launchers before 1.0.22 copied the Breeze jar into the mods folder under
    // a neutral name (1.21.11.jar) and recorded it nowhere. Beside the runtime
    // jar that is a second "breeze" mod and Fabric refuses to start, which is
    // what the first real launch of 1.0.22 on an upgraded install ran into. The
    // old jar is moved into the runtime folder rather than deleted, in case it
    // was placed there by hand.
    let exclude = if add_mods_supported { None } else { Some(dest.as_path()) };
    for stale in legacy_breeze_jars(mods_dir, manifest, exclude) {
        let name = stale.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        let legacy_dir = breeze_runtime_dir(profile_root).join("legacy");
        let target = legacy_dir.join(&name);
        let moved = fs::create_dir_all(&legacy_dir)
            .and_then(|_| {
                let _ = fs::remove_file(&target);
                fs::rename(&stale, &target)
            })
            .is_ok();
        emit_launch_log(
            app,
            &if moved {
                format!("[Breeze Mod] moved an older Breeze jar out of the mods folder: {name}")
            } else {
                format!("[Breeze Mod] an older Breeze jar in the mods folder could not be moved: {name}")
            },
        );
    }

    if !installed {
        emit_launch_log(
            app,
            "[Breeze Mod] no build is published for this Minecraft version yet, launching without Breeze features.",
        );
        return Ok(BreezeRuntimeInjection::None);
    }

    let sha256 = fs::read(&dest).map(|bytes| sha256_hex(&bytes)).unwrap_or_default();
    write_breeze_runtime_sidecar(
        profile_root,
        &BreezeRuntimeSidecar {
            file_name: recorded_name,
            sha256,
            mc_version: mc_version.to_string(),
            installed_at: utc_timestamp(),
        },
    )?;

    if add_mods_supported {
        emit_launch_log(
            app,
            &format!("[Breeze Mod] loading via -Dfabric.addMods from {}", dest.display()),
        );
        Ok(BreezeRuntimeInjection::AddMods(dest))
    } else {
        emit_launch_log(
            app,
            &format!(
                "[Breeze Mod] Fabric loader {loader_version} predates -Dfabric.addMods, copied into the mods folder as {}",
                dest.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default()
            ),
        );
        Ok(BreezeRuntimeInjection::ModsFolder(dest))
    }
}


/// Hosts the launcher sends a Breeze account token to.
const BREEZE_API_HOSTS: &[&str] = &["api.breezeclient.net"];

/// The API base URL, if it is a Breeze server, with no trailing slash.
///
/// The account token is attached to requests against this base, so an address
/// the launcher does not recognise gets no token at all. A debug build also
/// accepts loopback, for testing against a local API.
fn trusted_api_base(raw: &str) -> Option<String> {
    trusted_api_base_with(raw, cfg!(debug_assertions))
}

fn trusted_api_base_with(raw: &str, allow_loopback: bool) -> Option<String> {
    let parsed = Url::parse(raw.trim()).ok()?;
    if !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.query().is_some()
        || parsed.fragment().is_some()
    {
        return None;
    }
    let host = parsed.host_str()?.to_ascii_lowercase();
    let breeze_host = parsed.scheme() == "https" && BREEZE_API_HOSTS.contains(&host.as_str());
    let loopback = allow_loopback
        && matches!(parsed.scheme(), "http" | "https")
        && matches!(host.as_str(), "localhost" | "127.0.0.1" | "[::1]");
    if !breeze_host && !loopback {
        return None;
    }
    Some(parsed.as_str().trim_end_matches('/').to_string())
}

fn game_session_file(game_directory: &Path) -> PathBuf {
    game_directory.join(".breeze").join("session.json")
}

/// Ask the API for a game token and write it where the mod looks for it.
///
/// Errors never include the response body or the token.
async fn issue_game_session(
    client: &reqwest::Client,
    api_base: &str,
    breeze_token: &str,
    session_file: &Path,
) -> Result<(), String> {
    let response = client
        .post(format!("{api_base}/auth/game-session"))
        .bearer_auth(breeze_token)
        .timeout(Duration::from_secs(15))
        .send()
        .await
        .map_err(|error| format!("the API could not be reached ({})", error.without_url()))?;

    let status = response.status();
    if status == reqwest::StatusCode::UNAUTHORIZED || status == reqwest::StatusCode::FORBIDDEN {
        return Err("the Breeze session was refused, sign in again".into());
    }
    if !status.is_success() {
        return Err(format!("the API answered {status}"));
    }
    let body: Value = response
        .json()
        .await
        .map_err(|_| "the API sent an unreadable game session".to_string())?;
    let token = body
        .get("token")
        .and_then(Value::as_str)
        .filter(|token| looks_like_jwt(token))
        .ok_or_else(|| "the API sent no game token".to_string())?;
    let expires_at = body.get("expiresAt").cloned().unwrap_or(Value::Null);

    let contents = serde_json::json!({ "token": token, "expiresAt": expires_at }).to_string();
    write_private_file(session_file, &contents)
}

fn looks_like_jwt(value: &str) -> bool {
    let parts: Vec<&str> = value.split('.').collect();
    parts.len() == 3
        && parts.iter().all(|part| {
            !part.is_empty()
                && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_')
        })
}

/// Write a file only the current user can read, replacing any existing one.
///
/// The file is created with owner-only permissions on Unix, so there is no
/// moment where a token sits in a world-readable file, and it replaces the old
/// file by rename, so an older world-readable copy does not keep its mode. On
/// Windows the folders under the user profile carry a per-user ACL already.
fn write_private_file(path: &Path, contents: &str) -> Result<(), String> {
    use std::io::Write as _;

    let name = path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let parent = path
        .parent()
        .ok_or_else(|| format!("{name} has no parent folder"))?;
    fs::create_dir_all(parent).map_err(|error| format!("could not create {}: {error}", parent.display()))?;
    let temp_path = parent.join(format!("{name}.tmp"));
    let _ = fs::remove_file(&temp_path);

    let mut options = fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let written = (|| -> std::io::Result<()> {
        let mut file = options.open(&temp_path)?;
        file.write_all(contents.as_bytes())?;
        file.sync_all()
    })();
    if let Err(error) = written {
        let _ = fs::remove_file(&temp_path);
        return Err(format!("could not write {name}: {error}"));
    }
    fs::rename(&temp_path, path).map_err(|error| {
        let _ = fs::remove_file(&temp_path);
        format!("could not write {name}: {error}")
    })
}

async fn fetch_latest_fabric_loader(client: &reqwest::Client, version_id: &str) -> Result<String, String> {
    let url = format!("{FABRIC_LOADER_LIST_URL}/{version_id}"); let values = fetch_json::<Value>(client, &url).await?; let loaders = values.as_array().ok_or_else(|| "Fabric loader response is not an array.".to_string())?;
    let stable = loaders.iter().find_map(|entry| { if entry.get("loader").and_then(|loader| loader.get("stable")).and_then(Value::as_bool) == Some(true) { entry.get("loader").and_then(|loader| loader.get("version")).and_then(Value::as_str).map(str::to_string) } else { None } });
    stable.or_else(|| { loaders.first().and_then(|entry| { entry.get("loader").and_then(|loader| loader.get("version")).and_then(Value::as_str).map(str::to_string) }) }).ok_or_else(|| format!("Fabric does not have a loader for {}.", version_id))
}

async fn ensure_assets(client: &reqwest::Client, app: &AppHandle, assets_root: &Path, metadata: &Value) -> Result<String, String> {
    let asset_id = json_string(metadata, &["assetIndex", "id"]).ok_or_else(|| "Minecraft metadata did not provide an asset index.".to_string())?; let asset_url = json_string(metadata, &["assetIndex", "url"]).ok_or_else(|| "Minecraft metadata did not provide an asset index URL.".to_string())?;
    fs::create_dir_all(assets_root.join("indexes")).map_err(|e| format!("Could not create asset index directory: {e}"))?; fs::create_dir_all(assets_root.join("objects")).map_err(|e| format!("Could not create asset object directory: {e}"))?;
    let index_path = assets_root.join("indexes").join(format!("{asset_id}.json")); ensure_download(client, &index_path, &asset_url).await?; let index = read_json_value(&index_path)?;
    let objects = index.get("objects").and_then(Value::as_object).ok_or_else(|| "Asset index did not include objects.".to_string())?;
    let missing: Vec<_> = objects.values().filter_map(|object| object.get("hash").and_then(Value::as_str)).filter(|hash| !asset_object_path(assets_root, hash).exists()).map(str::to_string).collect();
    if !missing.is_empty() { emit_launch_log(&app, &format!("Downloading {} missing assets", missing.len())); }
    for (index, hash) in missing.iter().enumerate() { if index % 150 == 0 { let progress = 0.18 + (index as f32 / missing.len().max(1) as f32) * 0.12; emit_launch_stage(app, "assets", &format!("Downloading asset batch {}", index + 1), Some(progress.min(0.3))); } let path = asset_object_path(assets_root, hash); let url = format!("https://resources.download.minecraft.net/{}/{}", &hash[0..2], hash); ensure_download_checked(client, &path, &url, Some(hash)).await?; }
    Ok(asset_id)
}

async fn ensure_libraries(client: &reqwest::Client, app: &AppHandle, libraries_root: &Path, metadata: &Value) -> Result<(Vec<PathBuf>, Vec<PathBuf>), String> {
    fs::create_dir_all(libraries_root)
        .map_err(|e| format!("Could not create libraries directory: {e}"))?;

    let libraries = json_array(metadata, &["libraries"]).cloned().unwrap_or_default();
    let total = libraries.len().max(1);

    // Minecraft's metadata can contain multiple versions of the same Maven
    // module.  Fabric's launcher classpath cannot contain two copies of the
    // same classes (for example ASM 9.6 and ASM 9.10.1), so resolve conflicts by
    // Maven coordinate and keep the newest version for the runtime classpath.
    let mut selected: HashMap<String, ResolvedLibrary> = HashMap::new();

    for (index, library) in libraries.iter().enumerate() {
        if !library_allowed(library) {
            continue;
        }

        if index % 20 == 0 {
            let progress = 0.36 + (index as f32 / total as f32) * 0.16;
            emit_launch_stage(
                app,
                "libraries",
                &format!("Resolving library {} of {}", index + 1, total),
                Some(progress.min(0.52)),
            );
        }

        // Before 1.19 a library's natives are a classifier of the same entry
        // rather than a library of their own, so they need a second lookup.
        if let Some(native) = legacy_native_library(library) {
            ensure_download(client, &libraries_root.join(&native.path), &native.url).await?;
            selected.insert(format!("native:{}", native.path), native);
        }

        let Some(resolved) = resolve_library(library)? else {
            continue;
        };

        let path = libraries_root.join(&resolved.path);

        // Natives are extracted separately and are not part of the duplicate
        // Java-class problem, so retain them independently.
        if resolved.native {
            ensure_download(client, &path, &resolved.url).await?;
            let key = format!("native:{}", resolved.path);
            selected.insert(key, resolved);
            continue;
        }

        let key = maven_coordinate_from_path(&resolved.path);
        match selected.get(&key) {
            None => {
                selected.insert(key, resolved);
            }
            Some(existing) => {
                let existing_version = maven_version_from_path(&existing.path);
                let new_version = maven_version_from_path(&resolved.path);
                if version_is_newer(new_version.as_deref().unwrap_or(""), existing_version.as_deref().unwrap_or("")) {
                    emit_launch_log(
                        app,
                        &format!(
                            "[Libraries] Resolving duplicate {}: {} → {}",
                            key,
                            existing_version.as_deref().unwrap_or("unknown"),
                            new_version.as_deref().unwrap_or("unknown")
                        ),
                    );
                    selected.insert(key, resolved);
                }
            }
        }
    }

    let mut classpath = Vec::new();
    let mut natives = Vec::new();

    for (_, resolved) in selected {
        let path = libraries_root.join(&resolved.path);
        ensure_download(client, &path, &resolved.url).await?;
        if resolved.native {
            natives.push(path);
        } else {
            classpath.push(path);
        }
    }

    Ok((classpath, natives))
}

fn maven_coordinate_from_path(path: &str) -> String {
    let normalized = path.replace('\\', "/");
    let parts: Vec<&str> = normalized.split('/').collect();
    if parts.len() < 4 {
        return path.to_string();
    }

    // group/artifact/version/artifact-version[-classifier].jar
    let artifact = parts[parts.len() - 3];
    let version = parts[parts.len() - 2];
    let group = parts[..parts.len() - 3].join(".");
    // The classifier is part of the identity. Netty ships one native transport
    // jar per architecture under the same group and artifact; treating them as
    // duplicates kept whichever came first, often the other architecture's.
    let file_name = parts[parts.len() - 1];
    let classifier = file_name
        .strip_prefix(&format!("{artifact}-{version}"))
        .and_then(|rest| rest.strip_suffix(".jar"))
        .map(|rest| rest.trim_start_matches('-'))
        .unwrap_or("");
    if classifier.is_empty() {
        format!("{group}:{artifact}")
    } else {
        format!("{group}:{artifact}:{classifier}")
    }
}

/// The native jar for this OS in the pre-1.19 library layout, where a library
/// carries a `natives` map (`{"linux": "natives-linux", "windows":
/// "natives-windows-${arch}"}`) and the jars under `downloads.classifiers`.
/// Without this, 1.18.2 and older started with no LWJGL natives at all.
fn legacy_native_library(library: &Value) -> Option<ResolvedLibrary> {
    let os_key = if cfg!(target_os = "windows") {
        "windows"
    } else if cfg!(target_os = "macos") {
        "osx"
    } else {
        "linux"
    };
    let bits = if cfg!(target_pointer_width = "64") { "64" } else { "32" };
    let classifier = library.get("natives")?.get(os_key)?.as_str()?.replace("${arch}", bits);
    let download = library.get("downloads")?.get("classifiers")?.get(&classifier)?;
    let url = download.get("url")?.as_str()?.to_string();
    let name = library.get("name")?.as_str()?;
    let path = download
        .get("path")
        .and_then(Value::as_str)
        .map(str::to_string)
        .unwrap_or_else(|| maven_path(&format!("{name}:{classifier}")));
    Some(ResolvedLibrary { path, url, native: true })
}

fn maven_version_from_path(path: &str) -> Option<String> {
    let normalized = path.replace('\\', "/");
    let parts: Vec<&str> = normalized.split('/').collect();
    if parts.len() < 4 {
        return None;
    }
    Some(parts[parts.len() - 2].to_string())
}

fn version_is_newer(a: &str, b: &str) -> bool {
    fn parse(value: &str) -> Vec<u64> {
        value
            .split(|c: char| !c.is_ascii_digit())
            .filter(|part| !part.is_empty())
            .map(|part| part.parse::<u64>().unwrap_or(0))
            .collect()
    }

    let a = parse(a);
    let b = parse(b);
    let len = a.len().max(b.len());

    for index in 0..len {
        let av = *a.get(index).unwrap_or(&0);
        let bv = *b.get(index).unwrap_or(&0);
        if av != bv {
            return av > bv;
        }
    }
    false
}

async fn ensure_logging_config(client: &reqwest::Client, app: &AppHandle, assets_root: &Path, metadata: &Value) -> Result<Option<PathBuf>, String> {
    let Some(log_id) = json_string(metadata, &["logging", "client", "file", "id"]) else { return Ok(None); }; let Some(log_url) = json_string(metadata, &["logging", "client", "file", "url"]) else { return Ok(None); };
    let log_dir = assets_root.join("log_configs"); fs::create_dir_all(&log_dir).map_err(|e| format!("Could not create log config directory: {e}"))?; let log_path = log_dir.join(log_id); emit_launch_log(app, "Resolving Minecraft logging config"); ensure_download(client, &log_path, &log_url).await?;
    Ok(Some(log_path))
}

/// The runtime jar the classpath should carry, which is only ever the legacy
/// mods-folder copy.
///
/// When the loader supports -Dfabric.addMods the jar is passed that way instead,
/// and putting it on the classpath as well makes Fabric discover the same mod
/// twice. The sidecar is the only thing consulted: the old code globbed the mods
/// folder for `breeze*.jar` and picked up whatever the user had named that way.
fn sidecar_classpath_runtime_jar(profile_root: &Path, mods_dir: &Path) -> Option<PathBuf> {
    let sidecar = read_breeze_runtime_sidecar(profile_root)?;
    let file_name = sidecar.file_name.trim();
    if file_name.is_empty() || file_name.eq_ignore_ascii_case(BREEZE_RUNTIME_JAR) {
        return None;
    }
    let path = mods_dir.join(file_name);
    is_valid_jar(&path).then_some(path)
}

/// Jars for the classpath, plus the enabled mods whose files are not on disk.
///
/// The missing list is returned rather than only logged, because "your mods did
/// not load" is a launch outcome the caller has to be able to act on, not a line
/// that scrolls past in the console.
fn collect_enabled_mod_jars(
    app: &AppHandle,
    profile_id: &str,
    classpath_runtime_jar: Option<&Path>,
) -> Result<(Vec<PathBuf>, Vec<String>), String> {
    let profile_root = profile_root_dir(app, profile_id)?;
    let mods_dir = profile_mods_dir(app, profile_id)?;
    let manifest  = read_mod_manifest(&profile_manifest_path(app, profile_id)?)?;

    // Filename of the injected Breeze runtime jar, so it is never double-counted
    // from the user manifest.
    let breeze_name = classpath_runtime_jar
        .and_then(|p| p.file_name())
        .map(|n| n.to_string_lossy().to_ascii_lowercase());

    // A legacy "breeze:core" record describes the runtime jar, which is
    // installed by the block below rather than by the user manifest.
    let manifest: Vec<ManagedModRecord> = manifest
        .into_iter()
        .filter(|record| record.project_id != "breeze:core")
        .collect();

    let wanted: Vec<PathBuf> = enabled_mod_file_names(&manifest)
        .into_iter()
        .filter(|file_name| {
            let lower = file_name.to_ascii_lowercase();
            breeze_name.as_deref() != Some(lower.as_str()) && lower != BREEZE_RUNTIME_JAR
        })
        .map(|file_name| mods_dir.join(file_name))
        .collect();

    let (jars, missing_paths): (Vec<PathBuf>, Vec<PathBuf>) =
        wanted.into_iter().partition(|path| path.exists());

    // One jar can be named by more than one record, so the paths are collapsed
    // before they reach the classpath.
    let mut seen: HashSet<String> = HashSet::new();
    let mut jars: Vec<PathBuf> = jars
        .into_iter()
        .filter(|path| seen.insert(path.to_string_lossy().to_ascii_lowercase()))
        .collect();

    let missing: Vec<String> = missing_paths
        .iter()
        .map(|path| path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default())
        .collect();

    // Inject the Breeze runtime jar first, and only from a path the sidecar
    // actually recorded.
    let core_jar = classpath_runtime_jar
        .map(Path::to_path_buf)
        .filter(|path| is_valid_jar(path))
        .or_else(|| sidecar_classpath_runtime_jar(&profile_root, &mods_dir));
    if let Some(core) = core_jar {
        jars.insert(0, core);
    }

    Ok((jars, missing))
}
fn prepare_natives_directory(path: &Path) -> Result<(), String> { if path.exists() { fs::remove_dir_all(path).map_err(|e| format!("Could not reset Breeze natives directory: {e}"))?; } fs::create_dir_all(path).map_err(|e| format!("Could not create Breeze natives directory: {e}")) }
fn extract_native_jar(path: &Path, output_dir: &Path) -> Result<(), String> {
    let bytes = fs::read(path).map_err(|e| format!("Could not read native jar {}: {e}", path.display()))?;
    let mut archive = ZipArchive::new(Cursor::new(bytes))
        .map_err(|e| format!("Could not open native jar {}: {e}", path.display()))?;
    for index in 0..archive.len() {
        let mut entry = archive
            .by_index(index)
            .map_err(|e| format!("Could not read native archive entry: {e}"))?;
        let name = entry.name().replace('\\', "/");
        if entry.is_dir() || name.starts_with("META-INF/") {
            continue;
        }
        // This used to join the raw entry name, so a tampered library jar in the
        // cache could write outside the natives folder. The JRE extractor already
        // used enclosed_name(); this now does the same, and checks containment
        // on top in case the name normalises into something unexpected.
        if entry.enclosed_name().is_none() {
            return Err(format!("Native jar {} contains an unsafe path: {name}", path.display()));
        }
        let output_path = contained_join(output_dir, &name)?;
        if let Some(parent) = output_path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Could not create native output directory: {e}"))?;
        }
        let mut file = fs::File::create(&output_path)
            .map_err(|e| format!("Could not create native output file: {e}"))?;
        std::io::copy(&mut entry, &mut file).map_err(|e| format!("Could not extract native library: {e}"))?;
    }
    Ok(())
}
fn join_classpath(entries: &[PathBuf]) -> Result<String, String> {
    let mut seen = HashSet::new();
    let ordered: Vec<_> = entries.iter().filter_map(|path| {
        let rendered = path.to_string_lossy().to_string();
        if seen.insert(rendered.clone()) { Some(rendered) } else { None }
    }).collect();
    if ordered.is_empty() { return Err("Minecraft classpath is empty.".into()); }
    
    let separator = if cfg!(target_os = "windows") { ";" } else { ":" };
    Ok(ordered.join(separator))
}
fn resolve_arguments(entries: Option<&Vec<Value>>, substitutions: &HashMap<String, String>) -> Vec<String> { let mut resolved = Vec::new(); for entry in entries.into_iter().flatten() { if let Some(text) = entry.as_str() { resolved.push(substitute_placeholders(text, substitutions)); continue; } if let Some(object) = entry.as_object() { if !rules_allow(object.get("rules").and_then(Value::as_array)) { continue; } if let Some(value) = object.get("value") { if let Some(text) = value.as_str() { resolved.push(substitute_placeholders(text, substitutions)); } else if let Some(values) = value.as_array() { for value in values.iter().filter_map(Value::as_str) { resolved.push(substitute_placeholders(value, substitutions)); } } } } } resolved }
fn resolve_legacy_game_arguments(arguments: Option<&str>, substitutions: &HashMap<String, String>) -> Vec<String> { arguments.unwrap_or_default().split_whitespace().map(|part| substitute_placeholders(part, substitutions)).collect() }
fn substitute_placeholders(value: &str, substitutions: &HashMap<String, String>) -> String { let mut rendered = value.to_string(); for (key, replacement) in substitutions { rendered = rendered.replace(&format!("${{{}}}", key), replacement); } rendered }
fn rules_allow(rules: Option<&Vec<Value>>) -> bool { let Some(rules) = rules else { return true; }; let mut allowed = false; for rule in rules { if !rule_matches(rule) { continue; } allowed = rule.get("action").and_then(Value::as_str) == Some("allow"); } allowed }
fn rule_matches(rule: &Value) -> bool { if let Some(os_name) = rule.get("os").and_then(|os| os.get("name")).and_then(Value::as_str) { let current = if cfg!(target_os = "windows") { "windows" } else if cfg!(target_os = "macos") { "osx" } else { "linux" }; if os_name != current { return false; } } if let Some(os_arch) = rule.get("os").and_then(|os| os.get("arch")).and_then(Value::as_str) { let current_arch = std::env::consts::ARCH; let matches = match os_arch { "x86" => current_arch.contains("86") && !current_arch.contains("64"), "x86_64" => current_arch == "x86_64", "arm64" | "aarch64" => current_arch.contains("arm64") || current_arch.contains("aarch64"), other => current_arch == other, }; if !matches { return false; } } if let Some(features) = rule.get("features").and_then(Value::as_object) { let known = HashMap::from([ ("is_demo_user", false), ("has_custom_resolution", false), ("has_quick_plays_support", false), ("is_quick_play_singleplayer", false), ("is_quick_play_multiplayer", false), ("is_quick_play_realms", false), ]); for (feature, expected) in features { if known.get(feature.as_str()).copied().unwrap_or(false) != expected.as_bool().unwrap_or(false) { return false; } } } true }
fn library_allowed(library: &Value) -> bool { rules_allow(library.get("rules").and_then(Value::as_array)) }
fn resolve_library(library: &Value) -> Result<Option<ResolvedLibrary>, String> { let name = library.get("name").and_then(Value::as_str).ok_or_else(|| "Library did not include a name.".to_string())?; if let Some(artifact) = library.get("downloads").and_then(|downloads| downloads.get("artifact")) { let url = artifact.get("url").and_then(Value::as_str).ok_or_else(|| format!("Library {} did not include a download URL.", name))?; let path = artifact.get("path").and_then(Value::as_str).map(str::to_string).unwrap_or_else(|| maven_path(name)); return Ok(Some(ResolvedLibrary { native: is_native_library_name(name), path, url: url.to_string(), })); } if let Some(url) = library.get("url").and_then(Value::as_str) { return Ok(Some(ResolvedLibrary { native: is_native_library_name(name), path: maven_path(name), url: format!("{}{path}", ensure_trailing_slash(url), path = maven_path(name)), })); } Ok(None) }
fn is_native_library_name(name: &str) -> bool { name.split(':').nth(3).map(|classifier| classifier.contains("natives")).unwrap_or(false) }
fn maven_path(name: &str) -> String { let parts: Vec<_> = name.split(':').collect(); let group = parts.first().copied().unwrap_or_default().replace('.', "/"); let artifact = parts.get(1).copied().unwrap_or_default(); let version = parts.get(2).copied().unwrap_or_default(); let classifier = parts.get(3).copied(); let file_name = match classifier { Some(classifier) => format!("{artifact}-{version}-{classifier}.jar"), None => format!("{artifact}-{version}.jar"), }; format!("{group}/{artifact}/{version}/{file_name}") }
fn merge_version_values(parent: Value, child: Value) -> Value { let mut merged = parent; if let Some(parent_obj) = merged.as_object_mut() { if let Some(child_obj) = child.as_object() { for (key, value) in child_obj { match key.as_str() { "libraries" => { let parent_libs = parent_obj.entry(key.clone()).or_insert_with(|| Value::Array(Vec::new())); if let Some(parent_arr) = parent_libs.as_array_mut() { parent_arr.extend(value.as_array().cloned().unwrap_or_default()); } } "arguments" => { let target = parent_obj.entry(key.clone()).or_insert_with(|| json!({})); if let Some(target_obj) = target.as_object_mut() { for section in ["game", "jvm"] { let mut combined = target_obj.get(section).and_then(Value::as_array).cloned().unwrap_or_default(); combined.extend(value.get(section).and_then(Value::as_array).cloned().unwrap_or_default()); target_obj.insert(section.to_string(), Value::Array(combined)); } } } _ => { parent_obj.insert(key.clone(), value.clone()); } } } } } merged }

async fn refresh_microsoft_account(client: &reqwest::Client, refresh_token: &str) -> Result<AuthenticatedAccount, String> {
    let response = client
        .post(MICROSOFT_TOKEN_URL)
        .form(&[
            ("client_id", BREEZE_CLIENT_ID),
            ("scope", MICROSOFT_SCOPE),
            ("grant_type", "refresh_token"),
            ("refresh_token", refresh_token),
            ("redirect_uri", MICROSOFT_REDIRECT_URI),
        ])
        .send()
        .await
        .map_err(|error| format!("Could not refresh Microsoft login: {error}"))?;
    if !response.status().is_success() {
        let err_text = response.text().await.unwrap_or_default();
        return Err(format!("Microsoft refresh failed: {err_text}"));
    }
    let token = response
        .json::<MicrosoftTokenResponse>()
        .await
        .map_err(|error| format!("Could not parse refreshed Microsoft token: {error}"))?;
    authenticate_minecraft(
        client,
        &token.access_token,
        token.refresh_token.or_else(|| Some(refresh_token.to_string())),
        None,
    )
    .await
}

async fn fetch_project_versions(client: &reqwest::Client, project_id: &str, game_version: &str, loader: &str) -> Result<Vec<ModrinthVersion>, String> { let query = [("game_versions", serde_json::to_string(&vec![game_version]).unwrap_or_default()), ("loaders", serde_json::to_string(&vec![loader]).unwrap_or_default()), ("include_changelog", "false".to_string())]; let response = client.get(format!("{MODRINTH_API_BASE_URL}/project/{project_id}/version")).query(&query).send().await.map_err(|error| format!("Could not reach Modrinth version API: {error}"))?; if !response.status().is_success() { return Err(format!("Modrinth version lookup failed with status {}", response.status())); } response.json::<Vec<ModrinthVersion>>().await.map_err(|error| format!("Could not parse Modrinth versions: {error}")) }

async fn fetch_modrinth_project(client: &reqwest::Client, project_id: &str) -> Result<ModrinthProjectDetails, String> {
    let response = client
        .get(format!("{MODRINTH_API_BASE_URL}/project/{project_id}"))
        .send()
        .await
        .map_err(|error| format!("Could not reach Modrinth project API: {error}"))?;
    if !response.status().is_success() {
        return Err(format!("Modrinth project lookup failed with status {}", response.status()));
    }
    response
        .json::<ModrinthProjectDetails>()
        .await
        .map_err(|error| format!("Could not parse Modrinth project details: {error}"))
}

/// The order versions are tried in: releases before betas before alphas, the
/// author's featured one first within each, then Modrinth's own order (newest
/// first). Featured alone used to decide, which picked Sodium
/// 0.8.15-beta.1 over 0.8.14 for 1.21.11, and Reese's Sodium Options 2.2.4
/// accepts only 0.8.14: Fabric then refused to start (CI run 37130047814).
fn order_modrinth_versions(versions: Vec<ModrinthVersion>) -> Vec<ModrinthVersion> {
    let rank = |v: &ModrinthVersion| match v.version_type.as_str() {
        "release" | "" => 0,
        "beta" => 1,
        _ => 2,
    };
    let mut ordered = versions;
    ordered.sort_by_key(|v| (rank(v), !v.featured));
    ordered
}

/// The identity a record should be stored under: the Modrinth hex id when the
/// installer knew it, otherwise whatever the record was keyed on, otherwise the
/// slug, and as a last resort the jar itself.
fn effective_canonical_id(record: &ManagedModRecord) -> String {
    for candidate in [&record.canonical_id, &record.project_id, &record.project_slug] {
        let trimmed = candidate.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }
    format!("custom:{}", record.file_name.trim().to_ascii_lowercase())
}

/// Whether two records describe the same installed mod.
///
/// Matching on `project_id` alone is what allowed a slug-keyed record and a
/// hex-keyed record for one jar to coexist, so the file name and the slug count
/// as identity too. The file name is decisive because two records naming one jar
/// can never be two different mods.
fn records_are_same_mod(existing: &ManagedModRecord, incoming: &ManagedModRecord) -> bool {
    if effective_canonical_id(existing).eq_ignore_ascii_case(&effective_canonical_id(incoming)) {
        return true;
    }
    let file_name = incoming.file_name.trim();
    if !file_name.is_empty() && existing.file_name.trim().eq_ignore_ascii_case(file_name) {
        return true;
    }
    let slug = incoming.project_slug.trim();
    if slug.is_empty() {
        return false;
    }
    // The stale half of a duplicate pair carries the slug in project_id, so the
    // incoming slug has to be compared against both spellings.
    existing.project_slug.trim().eq_ignore_ascii_case(slug)
        || existing.project_id.trim().eq_ignore_ascii_case(slug)
}

/// Insert `record`, replacing every record that already describes the same mod.
///
/// The displaced records carry the user's decision about whether the mod should
/// load. Forcing `enabled: true` here is what made a disabled mod come back on
/// its own after a dependency install touched it.
fn upsert_managed_mod(manifest: &mut Vec<ManagedModRecord>, record: ManagedModRecord) -> ManagedModRecord {
    let mut record = record;
    if record.canonical_id.trim().is_empty() {
        record.canonical_id = effective_canonical_id(&record);
    }

    let displaced: Vec<bool> = manifest
        .iter()
        .filter(|item| records_are_same_mod(item, &record))
        .map(|item| item.enabled)
        .collect();
    if !displaced.is_empty() {
        // A single "off" among the duplicates wins: re-enabling a mod the user
        // switched off is the failure being fixed, leaving one off is not.
        record.enabled = displaced.iter().all(|enabled| *enabled);
    }

    manifest.retain(|item| !records_are_same_mod(item, &record));
    manifest.push(record.clone());
    record
}

/// `records_are_same_mod` compares the incoming record's file name and slug
/// against the existing one, so it is not symmetric. For "are these one mod?"
/// in either direction, ask both ways.
fn mods_are_same(a: &ManagedModRecord, b: &ManagedModRecord) -> bool {
    records_are_same_mod(a, b) || records_are_same_mod(b, a)
}

/// Whether a record answers to an identifier the UI sent.
///
/// The UI may hold the canonical Modrinth id, the id an older install wrote, or
/// the slug, depending on which screen installed the mod.
fn record_matches_key(record: &ManagedModRecord, key: &str) -> bool {
    let key = key.trim();
    !key.is_empty()
        && [&record.canonical_id, &record.project_id, &record.project_slug]
            .iter()
            .any(|value| value.trim().eq_ignore_ascii_case(key))
}

fn apply_enabled_to_same_mod(manifest: &mut [ManagedModRecord], target: &ManagedModRecord, enabled: bool) {
    for item in manifest.iter_mut() {
        if mods_are_same(item, target) {
            item.enabled = enabled;
        }
    }
}

/// The version to compare two records of one mod by: the installed version name
/// when it is known, otherwise the jar file name, which usually carries it.
fn mod_version_label(record: &ManagedModRecord) -> String {
    let name = record.installed_version_name.trim();
    if name.is_empty() { record.file_name.trim().to_string() } else { name.to_string() }
}

/// True when a record carries a Modrinth project id rather than only a slug.
fn has_modrinth_project_id(record: &ManagedModRecord) -> bool {
    let id = record.project_id.trim();
    !id.is_empty() && !id.contains(':') && !id.eq_ignore_ascii_case(record.project_slug.trim())
}

/// Fold every set of records that describe the same mod into one.
///
/// Profiles written before 1.0.22 hold duplicate pairs: the live 1.21.11 profile
/// had 75 records for 61 jars. New installs no longer create them, but existing
/// files have to be repaired as well, or the old duplicates keep reverting
/// toggles and reappearing after removal. This runs on every read, and the
/// caller writes the result back only when something changed.
///
/// When folding, the user's "off" wins, and the record that carries a real
/// Modrinth id is kept over the one keyed on a slug.
fn heal_mod_manifest(records: Vec<ManagedModRecord>) -> (Vec<ManagedModRecord>, bool) {
    let mut changed = false;
    let mut healed: Vec<ManagedModRecord> = Vec::with_capacity(records.len());

    for mut record in records {
        if record.canonical_id.trim().is_empty() {
            record.canonical_id = effective_canonical_id(&record);
            changed = true;
        }

        match healed.iter_mut().find(|existing| mods_are_same(existing, &record)) {
            Some(existing) => {
                changed = true;
                let enabled = existing.enabled && record.enabled;
                let slug = if existing.project_slug.trim().is_empty() {
                    record.project_slug.clone()
                } else {
                    existing.project_slug.clone()
                };
                let different_jars = !existing.file_name.trim().eq_ignore_ascii_case(record.file_name.trim());
                let keep_incoming = if different_jars {
                    // Two versions of one mod, both recorded. The live profile had
                    // Entity Culling 1.10.1 and 1.10.2 side by side. Keep the newer
                    // build deliberately, not whichever record happened to carry
                    // the Modrinth id.
                    version_is_newer(&mod_version_label(&record), &mod_version_label(existing))
                } else {
                    has_modrinth_project_id(&record) && !has_modrinth_project_id(existing)
                };
                if keep_incoming {
                    *existing = record;
                }
                existing.enabled = enabled;
                if existing.project_slug.trim().is_empty() {
                    existing.project_slug = slug;
                }
            }
            None => healed.push(record),
        }
    }

    (healed, changed)
}

/// Jar file names the classpath should carry, in manifest order.
///
/// Grouped by file name rather than by record, because a jar named by several
/// records only belongs on the classpath when all of them agree it is enabled.
/// Taking the first record's flag instead let a stale duplicate switch a mod
/// back on behind the user.
fn enabled_mod_file_names(manifest: &[ManagedModRecord]) -> Vec<String> {
    let mut order: Vec<String> = Vec::new();
    let mut names: HashMap<String, (String, bool)> = HashMap::new();
    for record in manifest {
        let file_name = record.file_name.trim();
        if file_name.is_empty() {
            continue;
        }
        let key = file_name.to_ascii_lowercase();
        match names.get_mut(&key) {
            Some(entry) => entry.1 = entry.1 && record.enabled,
            None => {
                order.push(key.clone());
                names.insert(key, (file_name.to_string(), record.enabled));
            }
        }
    }
    order
        .into_iter()
        .filter_map(|key| names.get(&key).filter(|entry| entry.1).map(|entry| entry.0.clone()))
        .collect()
}

/// Of `removed_files`, the ones no surviving record still needs.
///
/// Deleting every file the removed records named would take the jar out from
/// under a duplicate record that is still in the manifest, which is how a mod
/// could vanish from disk while the UI still listed it.
fn orphaned_mod_files(surviving: &[ManagedModRecord], removed_files: &[String]) -> Vec<String> {
    let still_referenced: HashSet<String> = surviving
        .iter()
        .map(|item| item.file_name.trim().to_ascii_lowercase())
        .filter(|name| !name.is_empty())
        .collect();

    let mut seen: HashSet<String> = HashSet::new();
    removed_files
        .iter()
        .filter(|name| !name.trim().is_empty())
        .filter(|name| {
            let key = name.trim().to_ascii_lowercase();
            !still_referenced.contains(&key) && seen.insert(key)
        })
        .cloned()
        .collect()
}

/// Whether at least one real mod needs Fabric API to load.
///
/// Custom imports are excluded because a hand-dropped jar may be a standalone
/// loader plugin, and Fabric API itself does not count as a reason to install
/// Fabric API.
fn fabric_api_is_required(manifest: &[ManagedModRecord]) -> bool {
    manifest
        .iter()
        .any(|record| record.enabled && !is_custom_mod_record(record) && !is_fabric_api_record(record))
}

fn is_custom_mod_record(record: &ManagedModRecord) -> bool {
    record.project_id.starts_with("custom:") || record.canonical_id.starts_with("custom:")
}

fn is_fabric_api_record(record: &ManagedModRecord) -> bool {
    record.project_id == FABRIC_API_PROJECT_ID
        || record.canonical_id == FABRIC_API_PROJECT_ID
        || record.project_slug.eq_ignore_ascii_case("fabric-api")
        || record.title.to_ascii_lowercase().contains("fabric api")
}


async fn fetch_project_versions_with_fallback(
    client: &reqwest::Client,
    project_id: &str,
    game_version: &str,
    loader: &str,
) -> Result<Vec<ModrinthVersion>, String> {
    
    let exact = fetch_project_versions(client, project_id, game_version, loader).await?;
    if !exact.is_empty() {
        return Ok(exact);
    }

    
    
    let query = [
        ("loaders", serde_json::to_string(&vec![loader]).unwrap_or_default()),
        ("include_changelog", "false".to_string()),
    ];
    let response = client
        .get(format!("{MODRINTH_API_BASE_URL}/project/{project_id}/version"))
        .query(&query)
        .send()
        .await
        .map_err(|e| format!("Could not reach Modrinth version API: {e}"))?;

    if !response.status().is_success() {
        return Err(format!(
            "Mod '{}' does not have any versions compatible with Fabric. It may only support Forge or Quilt.",
            project_id
        ));
    }

    let all_versions: Vec<ModrinthVersion> = response
        .json()
        .await
        .map_err(|e| format!("Could not parse Modrinth versions: {e}"))?;

    
    let compatible: Vec<ModrinthVersion> = all_versions
        .iter()
        .filter(|v| v.game_versions.iter().any(|gv| gv == game_version))
        .cloned()
        .collect();

    if !compatible.is_empty() {
        return Ok(compatible);
    }

    
    Err(format!(
        "No compatible version of '{}' found for Minecraft {} with {}. \
         The mod may not yet support this Minecraft version. \
         Try a different Minecraft version or check the mod's Modrinth page.",
        project_id, game_version, loader
    ))
}

async fn install_modrinth_project_recursive(
    client: &reqwest::Client,
    app: &AppHandle,
    profile_id: &str,
    game_version: &str,
    loader: &str,
    request: InstallModRequest,
    seen: &mut HashSet<String>,
) -> Result<ManagedModRecord, String> {
    let requested_key = request.lookup_key();
    if requested_key.is_empty() {
        return Err("A mod install needs a Modrinth project id or slug.".into());
    }
    let mut queue = vec![request];
    let mut primary_record: Option<ManagedModRecord> = None;
    let mut primary_canonical: Option<String> = None;
    // The version ranges the mods in the folder and the ones installed so far
    // ask for: a dependency already there is kept only if they can use it, and
    // a version is downloaded only if they can.
    let mut wants: HashMap<String, Vec<Vec<String>>> = HashMap::new();
    if let Ok(dir) = profile_mods_dir(app, profile_id) {
        for jar in mods_local::scan(&dir).into_iter().filter(|j| j.enabled) {
            for dependency in jar.meta.map(|m| m.depends).unwrap_or_default() {
                wants.entry(dependency.id).or_default().push(dependency.any_of);
            }
        }
    }
    // Project id to the exact version a dependent asked for.
    let mut pins: HashMap<String, String> = HashMap::new();
    let env = mods_versions::environment_for(game_version);

    while let Some(request) = queue.pop() {
        let lookup = request.lookup_key();
        if lookup.is_empty() {
            continue;
        }
        // Resolve the request to Modrinth's own hex id before anything else.
        // A mod reached once by slug and once by hex id used to be installed
        // twice, under two keys, and the two records then disagreed about
        // whether it was enabled. Resolution failure is not fatal: the version
        // API below still works from a slug, so the old key is kept instead of
        // failing an install that used to succeed.
        let project = fetch_modrinth_project(client, &lookup).await.ok();
        let canonical_id = project
            .as_ref()
            .map(|details| details.id.clone())
            .unwrap_or_else(|| lookup.clone());
        let project_slug = project
            .as_ref()
            .map(|details| details.slug.clone())
            .unwrap_or_else(|| request.project_slug.clone());

        if !seen.insert(canonical_id.clone()) {
            continue;
        }


        // The first request popped is the one the caller asked for; everything
        // after it is a dependency. Remember its canonical id so the record we
        // hand back is that mod and not whichever dependency installed last.
        if primary_canonical.is_none() {
            primary_canonical = Some(canonical_id.clone());
        }

        let versions = fetch_project_versions_with_fallback(client, &lookup, game_version, loader).await?;
        let mut candidates = order_modrinth_versions(versions);
        // A dependent that names the exact version it was built against gets it.
        let pin = pins.get(&canonical_id).or_else(|| pins.get(&lookup)).cloned();
        if let Some(pin) = pin {
            if let Some(i) = candidates.iter().position(|v| v.id == pin) {
                let pinned = candidates.remove(i);
                candidates.insert(0, pinned);
            }
        }
        if candidates.is_empty() {
            return Err(format!("No compatible {loader} version found for this Minecraft version."));
        }

        let mods_dir = profile_mods_dir(app, profile_id)?;
        let instance_dir = profile_root_dir(app, profile_id)?;
        fs::create_dir_all(&mods_dir).map_err(|error| format!("Could not create Breeze mods directory: {error}"))?;

        // The first version (in the order above) whose jar the installed mods
        // and the ones being installed can all use. A few are tried; if none
        // fits, the first is installed and the Mods page shows what is unmet.
        let mut chosen: Option<(ModrinthVersion, ModrinthVersionFile, Vec<u8>)> = None;
        let mut first: Option<(ModrinthVersion, ModrinthVersionFile, Vec<u8>)> = None;
        for version in candidates.into_iter().take(6) {
            let Some(file) = version.files.iter().find(|file| file.primary).cloned().or_else(|| version.files.first().cloned()) else {
                continue;
            };
            let response = client
                .get(&file.url)
                .send()
                .await
                .map_err(|error| format!("Could not download mod: {error}"))?;
            if !response.status().is_success() {
                return Err(format!("Could not download {}: Modrinth answered {}", file.filename, response.status()));
            }
            let bytes = response
                .bytes()
                .await
                .map_err(|error| format!("Could not read mod bytes: {error}"))?
                .to_vec();
            mods_commands::verify_hashes(
                &bytes,
                file.hashes.get("sha512").map(String::as_str),
                file.hashes.get("sha1").map(String::as_str),
            )?;
            let fits = match mods_local::read_meta_bytes(&bytes) {
                Ok(meta) => {
                    // What the mods here ask of this one...
                    let wanted = wants.get(&meta.id).map_or(true, |lists| {
                        lists.iter().all(|any| any.is_empty() || mods_local::satisfies_any(&meta.version, any) != Some(false))
                    });
                    // ...and what this one asks of the game and the mods here.
                    // Modrinth lists versions by the author's tags: YACL
                    // 3.8.2+1.21.10 is listed for 1.21.9 but needs a Fabric API
                    // 1.21.9 never had (CI run 37130049215). A dependency that
                    // is missing is fine: it is installed next.
                    let provided = mods_local::provided_versions(&mods_local::scan(&mods_dir), &[]);
                    let own = mods_local::issues_for(&meta, &env, &provided)
                        .into_iter()
                        .all(|issue| matches!(issue, mods_local::Issue::MissingDependency { .. } | mods_local::Issue::Conflicts { .. }));
                    wanted && own
                }
                Err(_) => true,
            };
            if fits {
                chosen = Some((version, file, bytes));
                break;
            }
            emit_launch_log(app, &format!("[Mods] {} does not fit this game or the installed mods; trying the next version.", file.filename));
            if first.is_none() {
                first = Some((version, file, bytes));
            }
        }
        let Some((version, file, bytes)) = chosen.or(first) else {
            return Err("Modrinth did not return a downloadable file.".into());
        };

        for dependency in version
            .dependencies
            .iter()
            .rev()
            .filter(|dependency| dependency.dependency_type == "required")
        {
            if let Some(project_id) = dependency.project_id.as_deref() {
                if seen.contains(project_id) {
                    continue;
                }
                if let Some(pinned) = dependency.version_id.clone() {
                    pins.insert(project_id.to_string(), pinned);
                }

                let dependency_project = fetch_modrinth_project(client, project_id).await?;
                queue.push(InstallModRequest {
                    profile_id: profile_id.to_string(),
                    game_version: game_version.to_string(),
                    loader: loader.to_string(),
                    project_id: dependency_project.id,
                    project_slug: dependency_project.slug,
                    title: dependency_project.title,
                    summary: Some(dependency_project.description),
                    icon_url: dependency_project.icon_url,
                });
            }
        }

        let file_name = sanitize_filename(&file.filename);
        // One copy of each mod: the new jar replaces every other file of the
        // same mod id (kept in the instance's backups). A dependency that is
        // already installed, however its file is named, is kept as it is: the
        // player may have chosen that version on purpose. Writing the new
        // file beside the old one is what filled tester folders with two
        // Fabric APIs, two Sodiums and two Mod Menus.
        let is_dependency = primary_canonical.as_deref() != Some(canonical_id.as_str());
        let keep_if = is_dependency.then(|| mods_commands::KeepIf { wants: &wants, env: &env });
        let placed = mods_commands::place_jar(&instance_dir, &mods_dir, &bytes, &file_name, keep_if, "replaced")?;
        if let Ok(meta) = mods_local::read_meta_bytes(&bytes) {
            for dependency in meta.depends {
                wants.entry(dependency.id).or_default().push(dependency.any_of);
            }
        }
        let manifest_path = profile_manifest_path(app, profile_id)?;
        let mut manifest = read_mod_manifest(&manifest_path)?;
        match &placed {
            mods_commands::Placed::KeptExisting { file_name: existing, mod_id, version } => {
                let fits = wants.get(mod_id).map_or(true, |lists| {
                    lists.iter().all(|any| any.is_empty() || mods_local::satisfies_any(version, any) != Some(false))
                });
                emit_launch_log(
                    app,
                    &if fits {
                        format!("[Mods] {mod_id} {version} is already installed ({existing}), so it was kept rather than replaced.")
                    } else {
                        format!(
                            "[Mods] {mod_id} {version} ({existing}) is older or newer than the new mod asks for, but another \
                             installed mod needs it, so it was kept. The Mods page shows what each mod needs."
                        )
                    },
                );
                continue;
            }
            mods_commands::Placed::Installed { replaced, mod_id, version, .. } => {
                emit_launch_log(app, &format!("[Mods] Installed {mod_id} {version} ({file_name})."));
                for (old, backup) in replaced {
                    emit_launch_log(
                        app,
                        &format!("[Mods] Replaced {old}; the old file is kept in {}", backup.display()),
                    );
                    let old_jar = old.strip_suffix(mods_local::DISABLED_SUFFIX).unwrap_or(old);
                    if !old_jar.eq_ignore_ascii_case(&file_name) {
                        manifest.retain(|r| !r.file_name.trim().eq_ignore_ascii_case(old_jar));
                    }
                }
            }
        }
        let record = upsert_managed_mod(
            &mut manifest,
            ManagedModRecord {
                project_id: canonical_id.clone(),
                project_slug: project_slug.clone(),
                title: request.title,
                summary: request.summary,
                icon_url: request.icon_url,
                game_version: game_version.to_string(),
                loader: loader.to_string(),
                installed_version_id: version.id,
                installed_version_name: if version.version_number.is_empty() {
                    version.name
                } else {
                    version.version_number
                },
                file_name,
                // upsert_managed_mod carries the displaced record's value over
                // this one, so a disabled mod stays disabled through a reinstall.
                enabled: true,
                canonical_id: canonical_id.clone(),
            },
        );
        write_mod_manifest(&manifest_path, &manifest)?;

        // The caller may have asked by slug or by hex id; both name this record.
        if primary_canonical.as_deref() == Some(canonical_id.as_str())
            || canonical_id.eq_ignore_ascii_case(&requested_key)
            || project_slug.eq_ignore_ascii_case(&requested_key)
        {
            primary_record = Some(record);
        }
    }

    primary_record.ok_or_else(|| "Could not determine the installed Modrinth record.".into())
}

// The account store file is the primary credential store. The OS keyring is
// unreliable for this payload: without platform features the keyring crate
// silently uses an in-memory mock (lost on every restart), and Windows
// Credential Manager caps blobs at 2560 bytes, which the session JSON exceeds.
//
// Every signed-in account keeps its own Microsoft refresh token here, which is
// what makes account switching instant, switching refreshes that account's
// tokens in the background instead of forcing a new device-code sign-in.
fn accounts_file_path(_app: &AppHandle) -> Result<PathBuf, String> {
    Ok(breeze_home_dir()?.join("accounts.json"))
}

fn write_account_store(app: &AppHandle, store: &AccountStore) -> Result<(), String> {
    let content = serde_json::to_string(store)
        .map_err(|error| format!("Could not serialize Breeze accounts: {error}"))?;
    let path = accounts_file_path(app)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("Could not create Breeze account directory: {error}"))?;
    }
    // Refresh and access tokens live in this file, so on Linux and macOS it is
    // created readable by this user only rather than with the default 0644.
    write_private_file(&path, &content)
        .map_err(|error| format!("Could not write Breeze account store: {error}"))?;

    // Keep the legacy single-session file in sync so a downgrade to an older
    // build still finds the active login instead of dumping the user to sign-in.
    if let Some(active) = store.active_account() {
        if let Ok(legacy) = serde_json::to_string(active) {
            let _ = write_private_file(&session_file_path(app)?, &legacy);
        }
    } else {
        let _ = fs::remove_file(session_file_path(app)?);
    }
    Ok(())
}

fn read_account_store(app: &AppHandle) -> Result<AccountStore, String> {
    let path = accounts_file_path(app)?;
    if path.exists() {
        let content = fs::read_to_string(&path)
            .map_err(|error| format!("Could not read Breeze account store: {error}"))?;
        if let Ok(store) = serde_json::from_str::<AccountStore>(&content) {
            return Ok(store);
        }
        // A corrupt store must not lock the user out, fall through to the
        // legacy single-session migration below and rebuild from there.
    }

    // Migrate from the pre-multi-account layout: a single session.json, or an
    // even older build that stashed the session in the OS keyring.
    let mut migrated: Option<AuthenticatedAccount> = None;
    let legacy_path = session_file_path(app)?;
    if legacy_path.exists() {
        if let Ok(content) = fs::read_to_string(&legacy_path) {
            migrated = serde_json::from_str::<AuthenticatedAccount>(&content).ok();
        }
    }
    if migrated.is_none() {
        if let Ok(entry) = keyring::Entry::new(SESSION_KEYRING_SERVICE, SESSION_KEYRING_ACCOUNT) {
            if let Ok(content) = entry.get_password() {
                migrated = serde_json::from_str::<AuthenticatedAccount>(&content).ok();
            }
        }
    }

    let mut store = AccountStore::default();
    if let Some(account) = migrated {
        store.active_uuid = Some(account.uuid.clone());
        store.accounts.push(account);
        let _ = write_account_store(app, &store);
    }
    Ok(store)
}

impl AccountStore {
    fn active_account(&self) -> Option<&AuthenticatedAccount> {
        let uuid = self.active_uuid.as_ref()?;
        self.accounts.iter().find(|item| item.uuid == *uuid)
    }

    /// Add or replace an account by uuid and make it the active one. Refresh
    /// tokens are preserved when the new payload doesn't carry one (Microsoft
    /// omits it on some refresh responses).
    fn upsert(&mut self, account: AuthenticatedAccount) {
        let mut account = account;
        if account.refresh_token.is_none() {
            if let Some(existing) = self.accounts.iter().find(|item| item.uuid == account.uuid) {
                account.refresh_token = existing.refresh_token.clone();
            }
        }
        self.active_uuid = Some(account.uuid.clone());
        match self.accounts.iter_mut().find(|item| item.uuid == account.uuid) {
            Some(slot) => *slot = account,
            None => self.accounts.push(account),
        }
    }
}

fn persist_session(app: &AppHandle, account: &AuthenticatedAccount) -> Result<(), String> {
    let mut store = read_account_store(app).unwrap_or_default();
    store.upsert(account.clone());
    write_account_store(app, &store)
}

fn read_persisted_session(app: &AppHandle) -> Result<AuthenticatedAccount, String> {
    let store = read_account_store(app)?;
    store
        .active_account()
        .cloned()
        .ok_or_else(|| "No saved Breeze session found.".into())
}

/// Refresh one stored account's Microsoft tokens. Returns the refreshed
/// account, or the stored one untouched when the failure was transient
/// (offline, DNS, timeout), only an explicit Microsoft rejection means the
/// user genuinely has to sign in again.
async fn revive_account(
    app: &AppHandle,
    stored: AuthenticatedAccount,
) -> Result<AuthenticatedAccount, String> {
    let client = http_client()?;
    // No refresh token: the Minecraft token may still be inside its ~24h
    // window. Validate it rather than either trusting it blindly (which fails
    // later, at launch) or forcing a sign-in the user may not need.
    let Some(refresh_token) = stored.refresh_token.clone() else {
        if stored.access_token.trim().is_empty() {
            mark_needs_reauth(app, &stored.uuid);
            return Err("This account needs to sign in again.".into());
        }
        return match client.get(MINECRAFT_PROFILE_URL).bearer_auth(&stored.access_token).send().await {
            Ok(response) if response.status().is_success() => Ok(stored),
            Ok(_) => {
                mark_needs_reauth(app, &stored.uuid);
                Err("This account needs to sign in again.".into())
            }
            // Offline: don't invalidate a session we couldn't check.
            Err(_) => Ok(stored),
        };
    };
    match refresh_microsoft_account(&client, &refresh_token).await {
        Ok(mut refreshed) => {
            refreshed.needs_reauth = false;
            let _ = persist_session(app, &refreshed);
            Ok(refreshed)
        }
        Err(error) if error.contains("Microsoft refresh failed") => {
            // Explicitly revoked or expired. Record it so the switcher stops
            // offering this account as instantly resumable.
            mark_needs_reauth(app, &stored.uuid);
            Err(error)
        }
        // Transient (offline, DNS, timeout): keep the stored session. The
        // Minecraft token can stay valid for up to 24 hours.
        Err(_) => Ok(stored),
    }
}

/// Flag one stored account as needing a fresh Microsoft sign-in.
fn mark_needs_reauth(app: &AppHandle, uuid: &str) {
    let Ok(mut store) = read_account_store(app) else { return };
    if let Some(slot) = store.accounts.iter_mut().find(|item| item.uuid == uuid) {
        slot.needs_reauth = true;
        slot.refresh_token = None;
        slot.access_token.clear();
    }
    let _ = write_account_store(app, &store);
}
fn profile_root_dir(_app: &AppHandle, profile_id: &str) -> Result<PathBuf, String> {
    // The single chokepoint every profile-scoped command passes through, so one
    // check here covers all of them.
    let id = instance_id(profile_id)?;
    Ok(breeze_home_dir()?.join("instances").join(id))
}
fn profile_mods_dir(app: &AppHandle, profile_id: &str) -> Result<PathBuf, String> { Ok(profile_root_dir(app, profile_id)?.join("mods")) }
fn profile_manifest_path(app: &AppHandle, profile_id: &str) -> Result<PathBuf, String> { Ok(profile_root_dir(app, profile_id)?.join("mods-state.json")) }
fn session_file_path(_app: &AppHandle) -> Result<PathBuf, String> { Ok(breeze_home_dir()?.join("session.json")) }
fn settings_file_path(_app: &AppHandle) -> Result<PathBuf, String> { Ok(breeze_home_dir()?.join("launcher-settings.json")) }


fn ensure_instance_structure(app: &AppHandle, profile_id: &str) -> Result<PathBuf, String> {
    let home = breeze_home_dir()?;
    let instance = profile_root_dir(app, profile_id)?;

    
    for subdir in &["assets/indexes", "assets/objects", "assets/log_configs", "libraries", "versions", "runtime"] {
        fs::create_dir_all(home.join(subdir))
            .map_err(|e| format!("Could not create .breezeclient/{}: {}", subdir, e))?;
    }

    
    
    
    for subdir in &["mods", "config", "saves", "screenshots", "resourcepacks", "shaderpacks", "logs", "natives", "recordings"] {
        fs::create_dir_all(instance.join(subdir))
            .map_err(|e| format!("Could not create instance/{}: {}", subdir, e))?;
    }

    
    let options_path = instance.join("options.txt");
    if !options_path.exists() {
        let options_content = "lang:en_us\ntutorialStep:none\n";
        fs::write(&options_path, options_content)
            .map_err(|e| format!("Could not write default options.txt: {}", e))?;
    }

    Ok(instance)
}


#[tauri::command]
async fn import_lunar_preferences(app: AppHandle) -> Result<ThirdPartyImportSummary, String> {
    let lunar_root = lunar_root_dir()?;
    let mut warnings = Vec::new();
    let mut imported_custom_mods = 0usize;
    let mut copied_config_dirs = 0usize;
    let mut selected_version: Option<String> = None;

    if !lunar_root.exists() {
        return Ok(ThirdPartyImportSummary {
            client_detected: false,
            imported_custom_mods: 0,
            imported_modrinth_mods: 0,
            copied_config_directories: 0,
            selected_version: None,
            warnings: vec!["Lunar Client installation not found. Expected a .lunarclient folder in your home folder.".into()],
        });
    }

    
    let settings_path = lunar_root.join("settings").join("game").join("settings.json");
    if settings_path.exists() {
        if let Ok(settings) = read_json_value(&settings_path) {
            if let Some(ver) = settings.get("version").and_then(Value::as_str) {
                
                selected_version = Some(ver.to_string());
            }
        }
    }

    
    if let Some(ref ver) = selected_version {
        let lunar_mods_dir = lunar_root.join("offline").join("multiver").join("mods").join(ver);
        if lunar_mods_dir.exists() {
            let breeze_mods_dir = profile_mods_dir(&app, ver)?;
            fs::create_dir_all(&breeze_mods_dir)
                .map_err(|e| format!("Could not create Breeze mods dir: {e}"))?;
            let manifest_path = profile_manifest_path(&app, ver)?;
            let mut manifest = read_mod_manifest(&manifest_path)?;

            if let Ok(entries) = fs::read_dir(&lunar_mods_dir) {
                for entry in entries.flatten() {
                    let src = entry.path();
                    let name = entry.file_name().to_string_lossy().to_string();
                    if !name.to_ascii_lowercase().ends_with(".jar") { continue; }
                    let dest = breeze_mods_dir.join(&name);
                    match fs::copy(&src, &dest) {
                        Ok(_) => {
                            let clean_name = name.trim_end_matches(".jar").replace(' ', "-").to_ascii_lowercase();
                            upsert_managed_mod(&mut manifest, ManagedModRecord {
                                project_id: format!("custom:{clean_name}"),
                                project_slug: format!("custom:{clean_name}"),
                                title: name.trim_end_matches(".jar").to_string(),
                                summary: Some("Imported from Lunar Client".into()),
                                icon_url: None,
                                game_version: ver.clone(),
                                loader: "fabric".into(),
                                installed_version_id: "lunar-import".into(),
                                installed_version_name: "Lunar import".into(),
                                file_name: name,
                                enabled: true,
                                canonical_id: format!("custom:{clean_name}"),
                            });
                            imported_custom_mods += 1;
                        }
                        Err(e) => warnings.push(format!("Could not copy {}: {e}", src.display())),
                    }
                }
            }
            write_mod_manifest(&manifest_path, &manifest)?;
        }

        
        let lunar_config_dir = lunar_root.join("offline").join("multiver").join("overrides").join("config");
        if lunar_config_dir.exists() {
            let target = profile_root_dir(&app, ver)?.join("config");
            copy_dir_recursive(&lunar_config_dir, &target)?;
            copied_config_dirs += 1;
        }
    }

    Ok(ThirdPartyImportSummary {
        client_detected: true,
        imported_custom_mods,
        imported_modrinth_mods: 0,
        copied_config_directories: copied_config_dirs,
        selected_version,
        warnings,
    })
}


#[tauri::command]
async fn import_badlion_preferences(app: AppHandle) -> Result<ThirdPartyImportSummary, String> {
    let badlion_root = badlion_root_dir()?;
    let mut warnings = Vec::new();
    let mut imported_custom_mods = 0usize;
    let mut copied_config_dirs = 0usize;
    let mut selected_version: Option<String> = None;

    if !badlion_root.exists() {
        return Ok(ThirdPartyImportSummary {
            client_detected: false,
            imported_custom_mods: 0,
            imported_modrinth_mods: 0,
            copied_config_directories: 0,
            selected_version: None,
            warnings: vec!["Badlion Client installation not found. Expected at ~/.minecraft/BadlionClient (Linux/macOS) or %APPDATA%\\.minecraft\\BadlionClient (Windows)".into()],
        });
    }

    
    let bl_settings_path = badlion_root.join("settings.json");
    if bl_settings_path.exists() {
        if let Ok(settings) = read_json_value(&bl_settings_path) {
            if let Some(ver) = settings.get("minecraftVersion").and_then(Value::as_str)
                .or_else(|| settings.get("version").and_then(Value::as_str)) {
                selected_version = Some(ver.to_string());
            }
        }
    }

    
    if selected_version.is_none() {
        if let Ok(mc_home) = minecraft_home_dir() {
            let versions_dir = mc_home.join("versions");
            if versions_dir.exists() {
                if let Ok(entries) = fs::read_dir(&versions_dir) {
                    
                    let mut release_vers: Vec<String> = entries.flatten()
                        .filter_map(|e| {
                            let name = e.file_name().to_string_lossy().to_string();
                            
                            if name.starts_with("1.") && !name.contains('-') { Some(name) } else { None }
                        })
                        .collect();
                    release_vers.sort();
                    selected_version = release_vers.last().cloned();
                }
            }
        }
    }

    
    if let Some(ref ver) = selected_version {
        let bl_mods_dir = badlion_root.join("mods").join(ver);
        if bl_mods_dir.exists() {
            let breeze_mods_dir = profile_mods_dir(&app, ver)?;
            fs::create_dir_all(&breeze_mods_dir)
                .map_err(|e| format!("Could not create Breeze mods dir: {e}"))?;
            let manifest_path = profile_manifest_path(&app, ver)?;
            let mut manifest = read_mod_manifest(&manifest_path)?;

            if let Ok(entries) = fs::read_dir(&bl_mods_dir) {
                for entry in entries.flatten() {
                    let src = entry.path();
                    let name = entry.file_name().to_string_lossy().to_string();
                    if !name.to_ascii_lowercase().ends_with(".jar") { continue; }
                    let dest = breeze_mods_dir.join(&name);
                    match fs::copy(&src, &dest) {
                        Ok(_) => {
                            let clean_name = name.trim_end_matches(".jar").replace(' ', "-").to_ascii_lowercase();
                            upsert_managed_mod(&mut manifest, ManagedModRecord {
                                project_id: format!("custom:{clean_name}"),
                                project_slug: format!("custom:{clean_name}"),
                                title: name.trim_end_matches(".jar").to_string(),
                                summary: Some("Imported from Badlion Client".into()),
                                icon_url: None,
                                game_version: ver.clone(),
                                loader: "fabric".into(),
                                installed_version_id: "badlion-import".into(),
                                installed_version_name: "Badlion import".into(),
                                file_name: name,
                                enabled: true,
                                canonical_id: format!("custom:{clean_name}"),
                            });
                            imported_custom_mods += 1;
                        }
                        Err(e) => warnings.push(format!("Could not copy {}: {e}", src.display())),
                    }
                }
            }
            write_mod_manifest(&manifest_path, &manifest)?;
        }

        
        let bl_config_dir = badlion_root.join("config");
        if bl_config_dir.exists() {
            let target = profile_root_dir(&app, ver)?.join("config");
            copy_dir_recursive(&bl_config_dir, &target)?;
            copied_config_dirs += 1;
        }
    }

    Ok(ThirdPartyImportSummary {
        client_detected: true,
        imported_custom_mods,
        imported_modrinth_mods: 0,
        copied_config_directories: copied_config_dirs,
        selected_version,
        warnings,
    })
}


#[tauri::command]
fn detect_importable_clients() -> Result<DetectionReport, String> {
    let mut clients = Vec::new();
    clients.push(detect_vanilla_client());
    clients.push(detect_feather_client());
    clients.push(detect_lunar_client());
    clients.push(detect_badlion_client());
    clients.push(detect_modrinth_app_client());
    let breeze_home = breeze_home_dir()?.to_string_lossy().to_string();
    Ok(DetectionReport { clients, breeze_home })
}

fn detect_vanilla_client() -> DetectedClient {
    let root = minecraft_home_dir().ok();
    let (detected, root_path) = match &root {
        Some(p) if p.exists() => (true, Some(p.to_string_lossy().to_string())),
        _ => (false, None),
    };
    let active = if detected {
        vanilla_active_version().or_else(fallback_vanilla_version_from_versions_dir)
    } else {
        None
    };
    let root = root.unwrap_or_default();
    DetectedClient {
        id: "vanilla".into(),
        name: "Vanilla Minecraft".into(),
        detected,
        root_path,
        active_version: active,
        profile_count: 0,
        mod_count: if detected { count_jars_in_dir(&root.join("mods")) } else { 0 },
        has_options: detected && root.join("options.txt").exists(),
        has_optifine_options: detected && root.join("optionsof.txt").exists(),
        has_resourcepacks: detected && root.join("resourcepacks").exists(),
        has_shaderpacks: detected && root.join("shaderpacks").exists(),
        has_saves: detected && root.join("saves").exists(),
        has_config: detected && root.join("config").exists(),
    }
}

fn detect_feather_client() -> DetectedClient {
    let root = feather_root_dir().ok();
    let (detected, root_path) = match &root {
        Some(p) if p.exists() => (true, Some(p.to_string_lossy().to_string())),
        _ => (false, None),
    };
    let root = root.unwrap_or_default();
    let settings_path = root.join("settings.json");
    let active = if detected {
        read_json_value(&settings_path)
            .ok()
            .and_then(|v| v.get("versionToLaunch").and_then(Value::as_str).map(str::to_string))
    } else {
        None
    };
    let mods_file = root.join("mods").join("feather-mods.json");
    let profile_count = read_json_value(&mods_file)
        .ok()
        .and_then(|v| v.get("profiles").and_then(Value::as_object).map(|o| o.len()))
        .unwrap_or(0);
    DetectedClient {
        id: "feather".into(),
        name: "Feather Client".into(),
        detected,
        root_path,
        active_version: active,
        profile_count,
        mod_count: 0,
        has_options: false,
        has_optifine_options: false,
        has_resourcepacks: false,
        has_shaderpacks: false,
        has_saves: false,
        has_config: detected && root.join("mods").exists(),
    }
}

fn detect_lunar_client() -> DetectedClient {
    let root = lunar_root_dir().ok();
    let (detected, root_path) = match &root {
        Some(p) if p.exists() => (true, Some(p.to_string_lossy().to_string())),
        _ => (false, None),
    };
    let root = root.unwrap_or_default();
    let settings_path = root.join("settings").join("game").join("settings.json");
    let active = if detected {
        read_json_value(&settings_path)
            .ok()
            .and_then(|v| v.get("version").and_then(Value::as_str).map(str::to_string))
    } else {
        None
    };
    let mods_dir = active
        .as_ref()
        .map(|v| root.join("offline").join("multiver").join("mods").join(v));
    let mod_count = mods_dir.as_deref().map(count_jars_in_dir).unwrap_or(0);
    DetectedClient {
        id: "lunar".into(),
        name: "Lunar Client".into(),
        detected,
        root_path,
        active_version: active,
        profile_count: 0,
        mod_count,
        has_options: false,
        has_optifine_options: false,
        has_resourcepacks: false,
        has_shaderpacks: false,
        has_saves: false,
        has_config: detected && root.join("offline").join("multiver").join("overrides").join("config").exists(),
    }
}

fn detect_badlion_client() -> DetectedClient {
    let root = badlion_root_dir().ok();
    let (detected, root_path) = match &root {
        Some(p) if p.exists() => (true, Some(p.to_string_lossy().to_string())),
        _ => (false, None),
    };
    let root = root.unwrap_or_default();
    let active = if detected {
        read_json_value(&root.join("settings.json"))
            .ok()
            .and_then(|v| {
                v.get("minecraftVersion")
                    .and_then(Value::as_str)
                    .or_else(|| v.get("version").and_then(Value::as_str))
                    .map(str::to_string)
            })
    } else {
        None
    };
    DetectedClient {
        id: "badlion".into(),
        name: "Badlion Client".into(),
        detected,
        root_path,
        active_version: active,
        profile_count: 0,
        mod_count: 0,
        has_options: false,
        has_optifine_options: false,
        has_resourcepacks: false,
        has_shaderpacks: false,
        has_saves: false,
        has_config: detected && root.join("config").exists(),
    }
}

fn detect_modrinth_app_client() -> DetectedClient {
    let root = modrinth_app_root_dir().ok();
    let (detected, root_path) = match &root {
        Some(p) if p.exists() => (true, Some(p.to_string_lossy().to_string())),
        _ => (false, None),
    };
    let root = root.unwrap_or_default();

    let profiles = if detected {
        enumerate_modrinth_profiles(&root)
    } else {
        Vec::new()
    };

    let total_mods = profiles
        .iter()
        .map(|(_, path)| count_jars_in_dir(&path.join("mods")))
        .sum::<usize>();
    let active = profiles.first().map(|(name, _)| name.clone());
    let has_resource = profiles.iter().any(|(_, p)| p.join("resourcepacks").exists());
    let has_shader = profiles.iter().any(|(_, p)| p.join("shaderpacks").exists());
    let has_saves = profiles.iter().any(|(_, p)| p.join("saves").exists());
    let has_options = profiles.iter().any(|(_, p)| p.join("options.txt").exists());
    let has_optifine = profiles.iter().any(|(_, p)| p.join("optionsof.txt").exists());
    let has_config = profiles.iter().any(|(_, p)| p.join("config").exists());

    DetectedClient {
        id: "modrinth".into(),
        name: "Modrinth App".into(),
        detected,
        root_path,
        active_version: active,
        profile_count: profiles.len(),
        mod_count: total_mods,
        has_options,
        has_optifine_options: has_optifine,
        has_resourcepacks: has_resource,
        has_shaderpacks: has_shader,
        has_saves,
        has_config,
    }
}


fn enumerate_modrinth_profiles(root: &Path) -> Vec<(String, PathBuf)> {
    let mut results = Vec::new();
    let profiles_dir = root.join("profiles");
    let Ok(entries) = fs::read_dir(&profiles_dir) else { return results };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_dir() { continue; }
        let name = entry.file_name().to_string_lossy().to_string();
        results.push((name, path));
    }
    results
}


#[tauri::command]
async fn import_vanilla_preferences(
    app: AppHandle,
    request: Option<VanillaImportRequest>,
) -> Result<VanillaImportSummary, String> {
    let request = request.unwrap_or_default();
    let root = minecraft_home_dir()?;
    if !root.exists() {
        return Ok(VanillaImportSummary {
            detected: false,
            profile_id: None,
            active_version: None,
            imported_custom_mods: 0,
            imported_resourcepacks: 0,
            imported_shaderpacks: 0,
            copied_options: false,
            copied_optifine_options: false,
            copied_saves: 0,
            copied_config_directories: 0,
            warnings: vec![format!(
                "Vanilla Minecraft installation not found at {}",
                root.display()
            )],
        });
    }

    let active = request
        .target_version
        .clone()
        .or_else(vanilla_active_version)
        .or_else(fallback_vanilla_version_from_versions_dir);
    let Some(version) = active else {
        return Ok(VanillaImportSummary {
            detected: true,
            profile_id: None,
            active_version: None,
            imported_custom_mods: 0,
            imported_resourcepacks: 0,
            imported_shaderpacks: 0,
            copied_options: false,
            copied_optifine_options: false,
            copied_saves: 0,
            copied_config_directories: 0,
            warnings: vec![
                "Could not determine the active Minecraft version from your vanilla install. \
                 Launch the official launcher once or set a target version explicitly.".into(),
            ],
        });
    };
    let profile_id = normalize_vanilla_version_id(&version);

    let mut warnings = Vec::new();
    ensure_instance_structure(&app, &profile_id)?;
    let common = copy_common_minecraft_assets(
        &app,
        &root,
        &profile_id,
        "Vanilla Minecraft",
        request.include_mods.unwrap_or(true),
        request.include_resourcepacks.unwrap_or(true),
        request.include_shaderpacks.unwrap_or(true),
        request.include_saves.unwrap_or(false),
        request.include_options.unwrap_or(true),
        &mut warnings,
    )?;

    let mut settings = read_launcher_settings(&app)?;
    settings.selected_version = Some(profile_id.clone());
    write_launcher_settings(&app, &settings)?;

    Ok(VanillaImportSummary {
        detected: true,
        profile_id: Some(profile_id.clone()),
        active_version: Some(profile_id),
        imported_custom_mods: common.imported_custom_mods,
        imported_resourcepacks: common.imported_resourcepacks,
        imported_shaderpacks: common.imported_shaderpacks,
        copied_options: common.copied_options,
        copied_optifine_options: common.copied_optifine_options,
        copied_saves: common.copied_saves,
        copied_config_directories: common.copied_config_directories,
        warnings,
    })
}


#[tauri::command]
async fn import_modrinth_app_preferences(
    app: AppHandle,
    request: Option<ModrinthAppImportRequest>,
) -> Result<ModrinthAppImportSummary, String> {
    let request = request.unwrap_or_default();
    let root = modrinth_app_root_dir()?;
    if !root.exists() {
        return Ok(ModrinthAppImportSummary {
            detected: false,
            root_path: None,
            imported_profiles: Vec::new(),
            imported_mods: 0,
            imported_resourcepacks: 0,
            imported_shaderpacks: 0,
            copied_config_directories: 0,
            copied_options: 0,
            selected_version: None,
            warnings: vec![format!(
                "Modrinth App not found. Searched cross-platform paths starting at {}.",
                root.display()
            )],
        });
    }

    let profiles = enumerate_modrinth_profiles(&root);
    if profiles.is_empty() {
        return Ok(ModrinthAppImportSummary {
            detected: true,
            root_path: Some(root.to_string_lossy().to_string()),
            imported_profiles: Vec::new(),
            imported_mods: 0,
            imported_resourcepacks: 0,
            imported_shaderpacks: 0,
            copied_config_directories: 0,
            copied_options: 0,
            selected_version: None,
            warnings: vec!["Modrinth App is installed but has no profiles to import.".into()],
        });
    }

    let include_all = request.include_all_profiles.unwrap_or(false);
    let include_resource = request.include_resourcepacks.unwrap_or(true);
    let include_shader = request.include_shaderpacks.unwrap_or(true);
    let include_saves = request.include_saves.unwrap_or(false);
    let include_options = request.include_options.unwrap_or(true);

    let targets: Vec<(String, PathBuf)> = if include_all {
        profiles
    } else {
        profiles.into_iter().take(1).collect()
    };

    let mut warnings = Vec::new();
    let mut imported_profiles = Vec::new();
    let mut total_mods = 0usize;
    let mut total_resource = 0usize;
    let mut total_shader = 0usize;
    let mut total_configs = 0usize;
    let mut total_options = 0usize;
    let mut selected: Option<String> = None;

    for (profile_name, profile_path) in targets {
        let profile_json = read_json_value(&profile_path.join("profile.json")).ok();
        let game_version = profile_json
            .as_ref()
            .and_then(|v| json_string(v, &["metadata", "game_version"]))
            .or_else(|| {
                profile_json
                    .as_ref()
                    .and_then(|v| json_string(v, &["game_version"]))
            })
            .unwrap_or_else(|| profile_name.clone());
        let profile_id = normalize_vanilla_version_id(&game_version);

        ensure_instance_structure(&app, &profile_id)?;
        let common = copy_common_minecraft_assets(
            &app,
            &profile_path,
            &profile_id,
            "Modrinth App",
            true,
            include_resource,
            include_shader,
            include_saves,
            include_options,
            &mut warnings,
        )?;

        imported_profiles.push(profile_name.clone());
        total_mods += common.imported_custom_mods;
        total_resource += common.imported_resourcepacks;
        total_shader += common.imported_shaderpacks;
        total_configs += common.copied_config_directories;
        if common.copied_options { total_options += 1; }
        if selected.is_none() {
            selected = Some(profile_id.clone());
        }
    }

    if let Some(ref version) = selected {
        let mut settings = read_launcher_settings(&app)?;
        settings.selected_version = Some(version.clone());
        write_launcher_settings(&app, &settings)?;
    }

    Ok(ModrinthAppImportSummary {
        detected: true,
        root_path: Some(root.to_string_lossy().to_string()),
        imported_profiles,
        imported_mods: total_mods,
        imported_resourcepacks: total_resource,
        imported_shaderpacks: total_shader,
        copied_config_directories: total_configs,
        copied_options: total_options,
        selected_version: selected,
        warnings,
    })
}


// ─── Staged file import ──────────────────────────────────────────────────────
// Sending a whole modpack as `bytes` means serialising millions of numbers as
// JSON across the IPC boundary. Past roughly a hundred megabytes that payload is
// dropped or exhausts memory, and the Rust side then sees neither `path` nor
// `bytes`, which is what produced the "must import either path or bytes" report.
//
// The frontend now slices the file and appends it to a temp file a chunk at a
// time, then imports by path. No single large IPC message, constant memory, and
// the loop gives the UI real progress to show.

/// Where staged imports are written.
///
/// It lives in the user's Breeze folder, not the system temp directory. On
/// Linux `/tmp` is shared by every user, so a fixed `/tmp/breeze-imports` could
/// already belong to someone else, who could then read or swap a pack between
/// staging and import.
fn import_staging_dir() -> Result<PathBuf, String> {
    let dir = breeze_home_dir()?.join("imports");
    fs::create_dir_all(&dir).map_err(|e| format!("Could not create import staging directory: {e}"))?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = fs::set_permissions(&dir, fs::Permissions::from_mode(0o700));
    }
    Ok(dir)
}

/**
 * Append one chunk of an incoming file to its staging path.
 *
 * `first` truncates any previous attempt so a retry never appends onto a partial
 * file. Returns the absolute staged path so the caller can pass it to
 * import_mrpack once the last chunk is written.
 */
#[tauri::command]
fn stage_import_chunk(file_name: String, chunk: Vec<u8>, first: bool) -> Result<String, String> {
    use std::io::Write;
    let safe = sanitize_filename(&file_name);
    if safe.trim().is_empty() {
        return Err("Import file name is empty.".into());
    }
    let path = import_staging_dir()?.join(&safe);
    let mut file = fs::OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(first)
        .append(!first)
        .open(&path)
        .map_err(|e| format!("Could not open staging file: {e}"))?;
    file.write_all(&chunk)
        .map_err(|e| format!("Could not write staged chunk: {e}"))?;
    Ok(path.to_string_lossy().to_string())
}

/// Remove a staged file once the import has finished (or failed).
#[tauri::command]
fn clear_staged_import(file_name: String) -> Result<(), String> {
    let safe = sanitize_filename(&file_name);
    if safe.trim().is_empty() { return Ok(()); }
    let path = import_staging_dir()?.join(safe);
    if path.exists() {
        let _ = fs::remove_file(path);
    }
    Ok(())
}

#[tauri::command]
async fn import_mrpack(
    app: AppHandle,
    request: MrpackImportRequest,
) -> Result<MrpackImportSummary, String> {
    let bytes = if let Some(bytes) = request.bytes {
        bytes
    } else if let Some(path_str) = request.path.as_ref() {
        let path = PathBuf::from(path_str);
        if !path.exists() {
            return Err(format!("Pack file not found: {}", path.display()));
        }
        fs::read(&path).map_err(|error| format!("Could not read pack file: {error}"))?
    } else {
        // Reaching here almost always means a large pack was sent as `bytes`
        // over IPC and the payload was dropped in transit, not that the caller
        // genuinely passed nothing. Say so, because the old wording sent people
        // looking for a missing file.
        return Err(
            "The pack file never arrived. Large packs should be staged to disk with \
             stage_import_chunk and imported by path instead of sent as bytes."
                .into(),
        );
    };
    let mut archive = ZipArchive::new(Cursor::new(bytes))
        .map_err(|error| format!("Could not open .mrpack as ZIP: {error}"))?;

    let index_bytes = {
        let mut file = archive
            .by_name("modrinth.index.json")
            .map_err(|_| "modrinth.index.json missing from pack, not a valid .mrpack".to_string())?;
        let mut buf = Vec::new();
        std::io::copy(&mut file, &mut buf)
            .map_err(|error| format!("Could not read modrinth.index.json: {error}"))?;
        buf
    };
    let index: Value = serde_json::from_slice(&index_bytes)
        .map_err(|error| format!("Could not parse modrinth.index.json: {error}"))?;

    let pack_name = index
        .get("name")
        .and_then(Value::as_str)
        .unwrap_or("Unnamed Pack")
        .to_string();
    let pack_version = index
        .get("versionId")
        .and_then(Value::as_str)
        .map(str::to_string);
    let dependencies = index
        .get("dependencies")
        .and_then(Value::as_object)
        .cloned()
        .unwrap_or_default();
    let game_version = dependencies
        .get("minecraft")
        .and_then(Value::as_str)
        .ok_or_else(|| "Pack does not declare a Minecraft version.".to_string())?
        .to_string();
    let loader = if dependencies.contains_key("fabric-loader") {
        "fabric"
    } else if dependencies.contains_key("quilt-loader") {
        "quilt"
    } else if dependencies.contains_key("forge") {
        "forge"
    } else if dependencies.contains_key("neoforge") {
        "neoforge"
    } else {
        "fabric"
    }.to_string();

    let profile_id = request
        .target_profile_id
        .unwrap_or_else(|| normalize_vanilla_version_id(&game_version));

    ensure_instance_structure(&app, &profile_id)?;
    let profile_dir = profile_root_dir(&app, &profile_id)?;
    let mods_dir = profile_mods_dir(&app, &profile_id)?;
    fs::create_dir_all(&mods_dir)
        .map_err(|error| format!("Could not create mods dir: {error}"))?;
    let manifest_path = profile_manifest_path(&app, &profile_id)?;
    let mut manifest = read_mod_manifest(&manifest_path)?;

    let mut warnings = Vec::new();
    let mut downloaded_mods = 0usize;
    let client = http_client()?;
    let files = index.get("files").and_then(Value::as_array).cloned().unwrap_or_default();
    for file_entry in files {
        let rel_path = file_entry
            .get("path")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        if rel_path.is_empty() { continue; }

        let env_client = json_string(&file_entry, &["env", "client"]).unwrap_or_else(|| "required".into());
        if env_client == "unsupported" { continue; }

        let downloads = file_entry.get("downloads").and_then(Value::as_array).cloned().unwrap_or_default();
        let Some(url) = downloads.iter().filter_map(Value::as_str).next() else {
            warnings.push(format!("No download URL for {rel_path}"));
            continue;
        };

        // The path and the URL both come from the pack author. Keep the file
        // inside the instance, and only fetch from hosts the format allows.
        let target = match contained_join(&profile_dir, &rel_path) {
            Ok(path) => path,
            Err(reason) => {
                warnings.push(format!("Skipped {rel_path}: {reason}"));
                continue;
            }
        };
        if !mrpack_download_allowed(url) {
            warnings.push(format!("Skipped {rel_path}: its download is not on an allowed host"));
            continue;
        }
        if let Some(parent) = target.parent() {
            fs::create_dir_all(parent)
                .map_err(|error| format!("Could not create {}: {error}", parent.display()))?;
        }
        match client.get(url).send().await {
            Ok(response) if response.status().is_success() => {
                let body = response
                    .bytes()
                    .await
                    .map_err(|error| format!("Could not read {rel_path}: {error}"))?;
                fs::write(&target, body)
                    .map_err(|error| format!("Could not write {}: {error}", target.display()))?;
                if rel_path.starts_with("mods/") {
                    let file_name = target
                        .file_name()
                        .map(|value| value.to_string_lossy().to_string())
                        .unwrap_or_default();
                    let clean = file_name
                        .trim_end_matches(".jar")
                        .replace(' ', "-")
                        .to_ascii_lowercase();
                    upsert_managed_mod(
                        &mut manifest,
                        ManagedModRecord {
                            project_id: format!("mrpack:{clean}"),
                            project_slug: format!("mrpack:{clean}"),
                            title: file_name.trim_end_matches(".jar").to_string(),
                            summary: Some(format!("From pack: {pack_name}")),
                            icon_url: None,
                            game_version: profile_id.clone(),
                            loader: loader.clone(),
                            installed_version_id: pack_version.clone().unwrap_or_else(|| "mrpack".into()),
                            installed_version_name: pack_version.clone().unwrap_or_else(|| pack_name.clone()),
                            file_name,
                            enabled: true,
                            canonical_id: format!("mrpack:{clean}"),
                        },
                    );
                    downloaded_mods += 1;
                }
            }
            Ok(response) => warnings.push(format!("Skipped {rel_path}: HTTP {}", response.status())),
            Err(error) => warnings.push(format!("Skipped {rel_path}: {error}")),
        }
    }

    
    let mut copied_overrides = 0usize;
    for override_name in &["overrides", "client-overrides"] {
        let prefix = format!("{override_name}/");
        let mut override_entries = Vec::new();
        for index in 0..archive.len() {
            let file = archive
                .by_index(index)
                .map_err(|error| format!("Could not read override entry: {error}"))?;
            let name = file.name().to_string();
            if name.starts_with(&prefix) && !name.ends_with('/') {
                override_entries.push(name);
            }
        }
        for entry_name in override_entries {
            let mut file = archive
                .by_name(&entry_name)
                .map_err(|error| format!("Could not open {entry_name}: {error}"))?;
            let relative = entry_name.trim_start_matches(&prefix);
            // Zip entry names are chosen by the pack author. This used to be a
            // plain join, so "overrides/../../x" wrote outside the instance.
            let target = match contained_join(&profile_dir, relative) {
                Ok(path) => path,
                Err(reason) => {
                    warnings.push(format!("Skipped {entry_name}: {reason}"));
                    continue;
                }
            };
            if let Some(parent) = target.parent() {
                fs::create_dir_all(parent)
                    .map_err(|error| format!("Could not create {}: {error}", parent.display()))?;
            }
            let mut buf = Vec::new();
            std::io::copy(&mut file, &mut buf)
                .map_err(|error| format!("Could not extract {entry_name}: {error}"))?;
            fs::write(&target, buf)
                .map_err(|error| format!("Could not write {}: {error}", target.display()))?;
            copied_overrides += 1;
        }
    }

    write_mod_manifest(&manifest_path, &manifest)?;
    // The imported profile is returned to the caller, which lets the user decide
    // whether to switch to it. Switching for them silently changed what the
    // Play button would launch.

    Ok(MrpackImportSummary {
        pack_name,
        pack_version,
        game_version,
        loader,
        profile_id,
        downloaded_mods,
        copied_overrides,
        warnings,
    })
}


#[tauri::command]
async fn import_everything(
    app: AppHandle,
    request: Option<ImportEverythingRequest>,
) -> Result<ImportEverythingSummary, String> {
    let request = request.unwrap_or_default();
    let create_backup = request.create_backup.unwrap_or(true);
    let include_resource = request.include_resourcepacks.unwrap_or(true);
    let include_shader = request.include_shaderpacks.unwrap_or(true);
    let include_saves = request.include_saves.unwrap_or(false);
    let include_options = request.include_options.unwrap_or(true);
    let include_mods = request.include_mods.unwrap_or(true);

    let detected = detect_importable_clients()?;
    let allowed: Option<HashSet<String>> = request
        .clients
        .clone()
        .map(|clients| clients.into_iter().collect());

    let targets: Vec<DetectedClient> = detected
        .clients
        .iter()
        .filter(|client| client.detected)
        .filter(|client| allowed.as_ref().map(|set| set.contains(&client.id)).unwrap_or(true))
        .cloned()
        .collect();

    let mut attempted = Vec::new();
    let mut succeeded = Vec::new();
    let mut failed = Vec::new();
    let mut warnings = Vec::new();
    let mut total_mods = 0usize;
    let mut total_resource = 0usize;
    let mut total_shader = 0usize;
    let mut total_configs = 0usize;
    let mut total_options = 0usize;
    let mut selected: Option<String> = None;
    let mut backup_path: Option<String> = None;
    let mut backed_up: HashSet<String> = HashSet::new();

    for client in &targets {
        attempted.push(client.id.clone());

        
        if create_backup {
            if let Some(version) = client.active_version.as_ref() {
                let profile_id = normalize_vanilla_version_id(version);
                if backed_up.insert(profile_id.clone()) {
                    match backup_profile_if_exists(&app, &profile_id) {
                        Ok(Some(path)) => {
                            if backup_path.is_none() {
                                backup_path = Some(path.to_string_lossy().to_string());
                            }
                        }
                        Ok(None) => {}
                        Err(error) => warnings.push(format!(
                            "Backup skipped for {}: {error}",
                            profile_id
                        )),
                    }
                }
            }
        }

        let outcome: Result<(), String> = match client.id.as_str() {
            "vanilla" => {
                let summary = import_vanilla_preferences(
                    app.clone(),
                    Some(VanillaImportRequest {
                        target_version: None,
                        include_resourcepacks: Some(include_resource),
                        include_shaderpacks: Some(include_shader),
                        include_saves: Some(include_saves),
                        include_options: Some(include_options),
                        include_mods: Some(include_mods),
                    }),
                )
                .await?;
                total_mods += summary.imported_custom_mods;
                total_resource += summary.imported_resourcepacks;
                total_shader += summary.imported_shaderpacks;
                total_configs += summary.copied_config_directories;
                if summary.copied_options { total_options += 1; }
                if selected.is_none() { selected = summary.active_version.clone(); }
                warnings.extend(summary.warnings);
                Ok(())
            }
            "feather" => {
                let summary = import_feather_preferences(
                    app.clone(),
                    FeatherImportRequest { include_all_versions: false },
                )
                .await?;
                total_mods += summary.imported_custom_mods + summary.imported_modrinth_mods;
                total_configs += summary.copied_config_directories;
                if selected.is_none() { selected = summary.selected_version.clone(); }
                warnings.extend(summary.warnings);
                Ok(())
            }
            "lunar" => {
                let summary = import_lunar_preferences(app.clone()).await?;
                total_mods += summary.imported_custom_mods + summary.imported_modrinth_mods;
                total_configs += summary.copied_config_directories;
                if selected.is_none() { selected = summary.selected_version.clone(); }
                warnings.extend(summary.warnings);
                Ok(())
            }
            "badlion" => {
                let summary = import_badlion_preferences(app.clone()).await?;
                total_mods += summary.imported_custom_mods + summary.imported_modrinth_mods;
                total_configs += summary.copied_config_directories;
                if selected.is_none() { selected = summary.selected_version.clone(); }
                warnings.extend(summary.warnings);
                Ok(())
            }
            "modrinth" => {
                let summary = import_modrinth_app_preferences(
                    app.clone(),
                    Some(ModrinthAppImportRequest {
                        include_all_profiles: Some(false),
                        include_resourcepacks: Some(include_resource),
                        include_shaderpacks: Some(include_shader),
                        include_saves: Some(include_saves),
                        include_options: Some(include_options),
                    }),
                )
                .await?;
                total_mods += summary.imported_mods;
                total_resource += summary.imported_resourcepacks;
                total_shader += summary.imported_shaderpacks;
                total_configs += summary.copied_config_directories;
                total_options += summary.copied_options;
                if selected.is_none() { selected = summary.selected_version.clone(); }
                warnings.extend(summary.warnings);
                Ok(())
            }
            other => Err(format!("Unsupported client id: {other}")),
        };

        match outcome {
            Ok(()) => succeeded.push(client.id.clone()),
            Err(error) => failed.push(ImportFailure {
                client: client.id.clone(),
                error,
            }),
        }
    }

    Ok(ImportEverythingSummary {
        attempted,
        succeeded,
        failed,
        total_mods,
        total_resourcepacks: total_resource,
        total_shaderpacks: total_shader,
        total_configs,
        total_options,
        backup_path,
        selected_version: selected,
        warnings,
    })
}

fn read_mod_manifest(path: &Path) -> Result<Vec<ManagedModRecord>, String> {
    if !path.exists() {
        return Ok(Vec::new());
    }
    let content = fs::read_to_string(path).map_err(|error| format!("Could not read mod state: {error}"))?;
    let records: Vec<ManagedModRecord> =
        serde_json::from_str(&content).map_err(|error| format!("Could not parse mod state: {error}"))?;
    let (healed, changed) = heal_mod_manifest(records);
    if changed {
        // Persist the repair so every later reader sees one record per mod. A
        // failed write is not fatal: the healed list is still what this caller
        // uses, and the next read tries again.
        let _ = write_mod_manifest(path, &healed);
    }
    Ok(healed)
}
fn write_mod_manifest(path: &Path, manifest: &[ManagedModRecord]) -> Result<(), String> { if let Some(parent) = path.parent() { fs::create_dir_all(parent).map_err(|error| format!("Could not create Breeze profile state: {error}"))?; } let content = serde_json::to_string_pretty(manifest).map_err(|error| format!("Could not serialize mod state: {error}"))?; write_file_atomic(path, &content).map_err(|error| format!("Could not write mod state: {error}")) }
fn read_launcher_settings(app: &AppHandle) -> Result<LauncherSettings, String> {
    let path = settings_file_path(app)?;
    if !path.exists() {
        let settings = LauncherSettings::default();
        write_launcher_settings(app, &settings)?;
        return Ok(settings);
    }
    let content = fs::read_to_string(&path)
        .map_err(|error| format!("Could not read Breeze launcher settings: {error}"))?;
    serde_json::from_str(&content)
        .map_err(|error| format!("Could not parse Breeze launcher settings: {error}"))
}
fn write_launcher_settings(app: &AppHandle, settings: &LauncherSettings) -> Result<(), String> {
    let path = settings_file_path(app)?;
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("Could not create Breeze settings directory: {error}"))?;
    }
    let content = serde_json::to_string_pretty(settings)
        .map_err(|error| format!("Could not serialize Breeze launcher settings: {error}"))?;
    write_file_atomic(&path, &content)
        .map_err(|error| format!("Could not write Breeze launcher settings: {error}"))
}

/// The most heap we will ever hand the JVM on a machine this size.
///
/// A quarter of RAM, or 2GB, whichever is larger, is left for everything that is
/// not the Java heap: Windows itself, the GPU driver, the JVM's own non-heap
/// memory, and on the integrated graphics most Breeze users have, the VRAM the
/// iGPU carves out of the same physical RAM.
fn safe_heap_ceiling_mb(total_ram_mb: u64) -> u64 {
    let headroom_mb = (total_ram_mb / 4).max(2048);
    total_ram_mb.saturating_sub(headroom_mb).clamp(1024, 32768)
}

/// What to actually suggest, which is not "as much as will fit".
///
/// The old table recommended 6GB on an 8GB machine and 10GB on a 16GB one: its
/// own ceiling, on the theory that more heap is more performance. For Minecraft
/// it is the opposite past a point. Sodium and friends work in roughly 2-4GB;
/// beyond that the extra heap buys nothing, and it costs: G1 has a larger live
/// set to mark and evacuate so mixed collections get longer and frame times get
/// worse, and because -Xms is pinned to -Xmx the whole figure is committed at
/// startup, taking RAM from the page cache and the GPU driver before the game
/// has drawn a frame.
///
/// So this tops out well below the ceiling. The settings slider still goes up to
/// safe_max_ram_mb for anyone running a 300-mod pack who knows they need it.
///
/// The thresholds are deliberately below the round numbers they name. What we
/// read is memory available to the OS, not memory installed: firmware reserves a
/// slice first, so an 8GB machine reports 7991MB and never satisfies `>= 8192`.
/// The previous table compared against exact powers of two, so every machine was
/// silently graded one tier below its size and the top arms were unreachable.
/// Grading at ~95% of nominal makes the tier a machine lands in a decision rather
/// than an accident of how much its firmware happened to reserve.
fn recommended_heap_mb(total_ram_mb: u64) -> u64 {
    let want = if total_ram_mb >= 31_000 {
        8192
    } else if total_ram_mb >= 15_500 {
        6144
    } else if total_ram_mb >= 7_800 {
        4096
    } else if total_ram_mb >= 5_800 {
        3072
    } else {
        (total_ram_mb * 85 / 100).clamp(2048, 3072)
    };
    want.min(safe_heap_ceiling_mb(total_ram_mb))
}

fn detect_total_memory_mb() -> Result<u64, String> {
    let bytes = if cfg!(target_os = "windows") {
        parse_first_u64_from_output(
            windowed_command("powershell")
                .args([
                    "-NoProfile",
                    "-Command",
                    "(Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory",
                ])
                .output()
                .map_err(|error| format!("Could not query system memory: {error}"))?,
        )?
    } else if cfg!(target_os = "macos") {
        parse_first_u64_from_output(
            Command::new("sysctl")
                .args(["-n", "hw.memsize"])
                .output()
                .map_err(|error| format!("Could not query system memory: {error}"))?,
        )?
    } else {
        let meminfo = fs::read_to_string("/proc/meminfo")
            .map_err(|error| format!("Could not read /proc/meminfo: {error}"))?;
        let kb = meminfo
            .lines()
            .find_map(|line| {
                if line.starts_with("MemTotal:") {
                    line.split_whitespace().nth(1)?.parse::<u64>().ok()
                } else {
                    None
                }
            })
            .ok_or_else(|| "Could not parse total memory from /proc/meminfo.".to_string())?;
        kb.saturating_mul(1024)
    };

    Ok((bytes / 1024 / 1024).max(2048))
}

fn parse_first_u64_from_output(output: std::process::Output) -> Result<u64, String> {
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(format!("System memory query failed: {}", stderr.trim()));
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    stdout
        .split(|character: char| !character.is_ascii_digit())
        .find(|token| !token.is_empty())
        .ok_or_else(|| "Could not parse system memory output.".to_string())?
        .parse::<u64>()
        .map_err(|error| format!("Could not parse system memory output: {error}"))
}


fn home_dir() -> Result<PathBuf, String> {
    // USERPROFILE first on Windows. Git Bash, WSL shims and some IDEs export
    // their own HOME, and when the launcher is started from one of those it
    // resolves a different .breezeclient: every account, instance and mod list
    // looks like it has been wiped, when in fact it is simply somewhere else.
    if cfg!(target_os = "windows") {
        if let Some(profile) = std::env::var_os("USERPROFILE") {
            return Ok(PathBuf::from(profile));
        }
    }
    if let Some(home) = std::env::var_os("HOME") {
        return Ok(PathBuf::from(home));
    }
    if let Some(profile) = std::env::var_os("USERPROFILE") {
        return Ok(PathBuf::from(profile));
    }
    Err("Could not resolve the user home directory (HOME and USERPROFILE are unset).".into())
}


fn breeze_home_dir() -> Result<PathBuf, String> {
    Ok(home_dir()?.join(".breezeclient"))
}

/// Print the resolved Breeze home once, so a support log always says which
/// directory this run is actually reading.
fn log_breeze_home_once() {
    static LOGGED: OnceLock<()> = OnceLock::new();
    LOGGED.get_or_init(|| {
        match breeze_home_dir() {
            Ok(path) => println!("[Breeze] home directory: {}", path.display()),
            Err(error) => println!("[Breeze] home directory could not be resolved: {error}"),
        }
    });
}


fn minecraft_home_dir() -> Result<PathBuf, String> {
    if cfg!(target_os = "macos") {
        return Ok(home_dir()?.join("Library").join("Application Support").join("minecraft"));
    }
    
    if cfg!(target_os = "windows") {
        if let Some(appdata) = std::env::var_os("APPDATA") {
            return Ok(PathBuf::from(appdata).join(".minecraft"));
        }
    }
    
    Ok(home_dir()?.join(".minecraft"))
}


fn feather_root_dir() -> Result<PathBuf, String> {
    if cfg!(target_os = "windows") {
        if let Some(appdata) = std::env::var_os("APPDATA") {
            return Ok(PathBuf::from(appdata).join(".feather"));
        }
    }
    
    Ok(home_dir()?.join(".feather"))
}


fn lunar_root_dir() -> Result<PathBuf, String> {
    // Lunar keeps settings, profiles and mods in ~/.lunarclient on every OS
    // (C:\Users\<name>\.lunarclient on Windows). The other two folders only
    // hold its Electron launcher, and they were all this used to look at on
    // Windows and macOS, so importing from Lunar found nothing there.
    let home = home_dir()?;
    let mut candidates = vec![home.join(".lunarclient")];
    if cfg!(target_os = "windows") {
        if let Some(appdata) = std::env::var_os("APPDATA") {
            candidates.push(PathBuf::from(appdata).join(".lunarclient"));
        }
    }
    if cfg!(target_os = "macos") {
        candidates.push(home.join("Library").join("Application Support").join("lunarclient"));
    }
    Ok(first_existing_or_first(candidates))
}

/// The first folder that exists, or the preferred one for the "not found" message.
fn first_existing_or_first(candidates: Vec<PathBuf>) -> PathBuf {
    candidates
        .iter()
        .find(|candidate| candidate.exists())
        .cloned()
        .unwrap_or_else(|| candidates.into_iter().next().unwrap_or_default())
}


fn badlion_root_dir() -> Result<PathBuf, String> {
    Ok(minecraft_home_dir()?.join("BadlionClient"))
}


fn modrinth_app_root_dir() -> Result<PathBuf, String> {
    let candidates: Vec<PathBuf> = if cfg!(target_os = "macos") {
        let base = home_dir()?.join("Library").join("Application Support");
        vec![
            base.join("ModrinthApp"),
            base.join("com.modrinth.theseus"),
            base.join("theseus"),
        ]
    } else if cfg!(target_os = "windows") {
        let appdata = std::env::var_os("APPDATA")
            .map(PathBuf::from)
            .ok_or_else(|| "APPDATA is not set.".to_string())?;
        vec![
            appdata.join("ModrinthApp"),
            appdata.join("com.modrinth.theseus"),
            appdata.join("theseus"),
        ]
    } else {
        // The Modrinth App is a Tauri app and keeps its data in the XDG data
        // folder (~/.local/share/ModrinthApp). Older builds used the config
        // folder, so that stays as a fallback.
        let home = home_dir().unwrap_or_else(|_| PathBuf::from("."));
        let data = std::env::var_os("XDG_DATA_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".local").join("share"));
        let config = std::env::var_os("XDG_CONFIG_HOME")
            .map(PathBuf::from)
            .unwrap_or_else(|| home.join(".config"));
        vec![
            data.join("ModrinthApp"),
            data.join("com.modrinth.theseus"),
            config.join("ModrinthApp"),
            config.join("com.modrinth.theseus"),
            config.join("theseus"),
        ]
    };

    for candidate in &candidates {
        if candidate.exists() {
            return Ok(candidate.clone());
        }
    }
    
    
    Ok(candidates.into_iter().next().unwrap())
}


fn vanilla_active_version() -> Option<String> {
    let path = minecraft_home_dir().ok()?.join("launcher_profiles.json");
    let value = read_json_value(&path).ok()?;
    let selected = value.get("selectedUser")
        .and_then(|_| value.get("selectedProfile").and_then(Value::as_str))
        .or_else(|| value.get("selectedProfile").and_then(Value::as_str));
    let profiles = value.get("profiles").and_then(Value::as_object)?;

    if let Some(key) = selected {
        if let Some(profile) = profiles.get(key) {
            if let Some(version) = profile.get("lastVersionId").and_then(Value::as_str) {
                return Some(normalize_vanilla_version_id(version));
            }
        }
    }
    
    let mut best: Option<(String, String)> = None;
    for (_id, profile) in profiles.iter() {
        let version = match profile.get("lastVersionId").and_then(Value::as_str) {
            Some(value) => value.to_string(),
            None => continue,
        };
        let used = profile.get("lastUsed").and_then(Value::as_str).unwrap_or("").to_string();
        best = match best {
            Some((_, ref prev_used)) if prev_used.as_str() >= used.as_str() => best,
            _ => Some((version, used)),
        };
    }
    best.map(|(v, _)| normalize_vanilla_version_id(&v))
}


fn normalize_vanilla_version_id(raw: &str) -> String {
    for token in raw.split(|c: char| c == '-' || c == '_' || c == ' ') {
        
        if token.starts_with("1.") && token.chars().skip(2).all(|c| c.is_ascii_digit() || c == '.') {
            return token.to_string();
        }
    }
    raw.to_string()
}


fn fallback_vanilla_version_from_versions_dir() -> Option<String> {
    let versions_dir = minecraft_home_dir().ok()?.join("versions");
    let entries = fs::read_dir(&versions_dir).ok()?;
    let mut names: Vec<String> = entries
        .flatten()
        .filter_map(|entry| {
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with("1.") && !name.contains("snapshot") {
                Some(name)
            } else {
                None
            }
        })
        .collect();
    names.sort_by(|a, b| compare_version_strings(a, b));
    names.last().cloned().map(|v| normalize_vanilla_version_id(&v))
}

fn compare_version_strings(a: &str, b: &str) -> std::cmp::Ordering {
    let parse = |s: &str| -> Vec<u32> {
        s.split('.').filter_map(|part| part.parse::<u32>().ok()).collect()
    };
    parse(a).cmp(&parse(b))
}


fn copy_dir_contents_if_exists(source: &Path, target: &Path) -> Result<usize, String> {
    if !source.exists() {
        return Ok(0);
    }
    fs::create_dir_all(target)
        .map_err(|error| format!("Could not create {}: {error}", target.display()))?;
    let entries = fs::read_dir(source)
        .map_err(|error| format!("Could not read {}: {error}", source.display()))?;
    let mut count = 0usize;
    for entry in entries.flatten() {
        let source_path = entry.path();
        let target_path = target.join(entry.file_name());
        let Ok(file_type) = entry.file_type() else { continue };
        if file_type.is_dir() {
            copy_dir_recursive(&source_path, &target_path)?;
        } else if file_type.is_file() {
            fs::copy(&source_path, &target_path).map_err(|error| {
                format!(
                    "Could not copy {} to {}: {error}",
                    source_path.display(),
                    target_path.display()
                )
            })?;
        } else {
            continue;
        }
        count += 1;
    }
    Ok(count)
}


fn copy_file_if_exists(source: &Path, target: &Path) -> Result<bool, String> {
    if !source.exists() {
        return Ok(false);
    }
    if let Some(parent) = target.parent() {
        fs::create_dir_all(parent)
            .map_err(|error| format!("Could not create {}: {error}", parent.display()))?;
    }
    fs::copy(source, target).map_err(|error| {
        format!(
            "Could not copy {} to {}: {error}",
            source.display(),
            target.display()
        )
    })?;
    Ok(true)
}


fn backup_profile_if_exists(
    app: &AppHandle,
    profile_id: &str,
) -> Result<Option<PathBuf>, String> {
    let profile_dir = profile_root_dir(app, profile_id)?;
    if !profile_dir.exists() {
        return Ok(None);
    }
    let timestamp = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let backups_root = breeze_home_dir()?.join("backups");
    fs::create_dir_all(&backups_root)
        .map_err(|error| format!("Could not create backup directory: {error}"))?;
    let target = backups_root.join(format!("{profile_id}-{timestamp}"));
    copy_dir_recursive(&profile_dir, &target)?;
    Ok(Some(target))
}


struct CommonAssetResult {
    imported_custom_mods: usize,
    imported_resourcepacks: usize,
    imported_shaderpacks: usize,
    copied_options: bool,
    copied_optifine_options: bool,
    copied_saves: usize,
    copied_config_directories: usize,
}

fn copy_common_minecraft_assets(
    app: &AppHandle,
    source_root: &Path,
    profile_id: &str,
    origin_label: &str,
    include_mods: bool,
    include_resourcepacks: bool,
    include_shaderpacks: bool,
    include_saves: bool,
    include_options: bool,
    warnings: &mut Vec<String>,
) -> Result<CommonAssetResult, String> {
    let profile_dir = profile_root_dir(app, profile_id)?;
    let mods_dir = profile_mods_dir(app, profile_id)?;
    fs::create_dir_all(&profile_dir)
        .map_err(|error| format!("Could not create profile directory: {error}"))?;
    fs::create_dir_all(&mods_dir)
        .map_err(|error| format!("Could not create mods directory: {error}"))?;

    let manifest_path = profile_manifest_path(app, profile_id)?;
    let mut manifest = read_mod_manifest(&manifest_path)?;

    let mut result = CommonAssetResult {
        imported_custom_mods: 0,
        imported_resourcepacks: 0,
        imported_shaderpacks: 0,
        copied_options: false,
        copied_optifine_options: false,
        copied_saves: 0,
        copied_config_directories: 0,
    };

    if include_mods {
        let source_mods = source_root.join("mods");
        if let Ok(entries) = fs::read_dir(&source_mods) {
            for entry in entries.flatten() {
                let path = entry.path();
                let name = entry.file_name().to_string_lossy().to_string();
                if !name.to_ascii_lowercase().ends_with(".jar") {
                    continue;
                }
                let file_name = sanitize_filename(&name);
                let dest = mods_dir.join(&file_name);
                if let Err(error) = fs::copy(&path, &dest) {
                    warnings.push(format!("Could not copy {}: {error}", path.display()));
                    continue;
                }
                let clean_name = file_name
                    .trim_end_matches(".jar")
                    .replace(' ', "-")
                    .to_ascii_lowercase();
                upsert_managed_mod(
                    &mut manifest,
                    ManagedModRecord {
                        project_id: format!("custom:{clean_name}"),
                        project_slug: format!("custom:{clean_name}"),
                        title: file_name.trim_end_matches(".jar").to_string(),
                        summary: Some(format!("Imported from {origin_label}")),
                        icon_url: None,
                        game_version: profile_id.to_string(),
                        loader: "fabric".into(),
                        installed_version_id: format!(
                            "{}-import",
                            origin_label.to_ascii_lowercase().replace(' ', "-")
                        ),
                        installed_version_name: format!("{origin_label} import"),
                        file_name,
                        enabled: true,
                        canonical_id: format!("custom:{clean_name}"),
                    },
                );
                result.imported_custom_mods += 1;
            }
        }
    }

    if include_options {
        result.copied_options = copy_file_if_exists(
            &source_root.join("options.txt"),
            &profile_dir.join("options.txt"),
        )?;
        result.copied_optifine_options = copy_file_if_exists(
            &source_root.join("optionsof.txt"),
            &profile_dir.join("optionsof.txt"),
        )?;
        let _ = copy_file_if_exists(
            &source_root.join("optionsshaders.txt"),
            &profile_dir.join("optionsshaders.txt"),
        );
        let _ = copy_file_if_exists(
            &source_root.join("servers.dat"),
            &profile_dir.join("servers.dat"),
        );
    }

    if include_resourcepacks {
        result.imported_resourcepacks = copy_dir_contents_if_exists(
            &source_root.join("resourcepacks"),
            &profile_dir.join("resourcepacks"),
        )?;
    }
    if include_shaderpacks {
        result.imported_shaderpacks = copy_dir_contents_if_exists(
            &source_root.join("shaderpacks"),
            &profile_dir.join("shaderpacks"),
        )?;
    }
    if include_saves {
        result.copied_saves = copy_dir_contents_if_exists(
            &source_root.join("saves"),
            &profile_dir.join("saves"),
        )?;
    }

    let source_config = source_root.join("config");
    if source_config.exists() {
        copy_dir_recursive(&source_config, &profile_dir.join("config"))?;
        result.copied_config_directories = 1;
    }

    write_mod_manifest(&manifest_path, &manifest)?;
    Ok(result)
}

fn count_jars_in_dir(path: &Path) -> usize {
    fs::read_dir(path)
        .map(|entries| {
            entries
                .flatten()
                .filter(|entry| {
                    entry
                        .file_name()
                        .to_string_lossy()
                        .to_ascii_lowercase()
                        .ends_with(".jar")
                })
                .count()
        })
        .unwrap_or(0)
}


fn windowed_command<S: AsRef<std::ffi::OsStr>>(program: S) -> Command {
    let mut command = Command::new(program);
    #[cfg(target_os = "windows")]
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(WIN_CREATE_NO_WINDOW);
    }
    command
}

/// Byte count for a progress message. Whole MB above a megabyte, because a
/// figure that changes in the third decimal every frame reads as noise.
fn human_bytes(n: u64) -> String {
    const MB: u64 = 1024 * 1024;
    const KB: u64 = 1024;
    if n == 0 {
        "?".into()
    } else if n >= MB {
        format!("{:.1} MB", n as f64 / MB as f64)
    } else {
        format!("{} KB", n / KB)
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct UpdateInstallRequest {
    url: String,
    file_name: Option<String>,
    version: Option<String>,
    /// Published sha256 of the installer, from /versions/check. Required: an
    /// update without one is refused before anything is downloaded.
    sha256: Option<String>,
}

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
struct UpdaterEvent {
    stage: String,
    message: String,
    progress: Option<f32>,
}

fn emit_updater_event(app: &AppHandle, stage: &str, message: &str, progress: Option<f32>) {
    let _ = app.emit(
        "breeze://updater",
        UpdaterEvent { stage: stage.into(), message: message.into(), progress },
    );
}

/// Download an update and prove it is safe to hand to the OS.
///
/// Every step that decides whether a file may be run lives here, apart from
/// the platform-specific install, so the whole chain can be tested against a
/// real API and a real installer without installing anything:
/// https Breeze host (loopback only in debug builds), a published SHA-256, a
/// streamed download to a .part file, a size check, the hash, and the installer
/// format for this OS. Only a file that passes all of them is renamed into place.
async fn download_verified_update<F>(
    url: &str,
    sha256: Option<&str>,
    file_name: Option<&str>,
    version: Option<&str>,
    updates_dir: &Path,
    mut progress: F,
) -> Result<PathBuf, String>
where
    F: FnMut(&str, &str, f32),
{
    let url = url.trim().to_string();
    // The updater downloads a program and runs it, so where it comes from and
    // what it hashes to are not optional. It used to accept any http(s) URL and
    // only check a hash when one happened to be supplied, which the main update
    // path never did.
    validate_update_url(&url)?;
    let expected_sha256 = sha256
        .map(|value| value.trim().to_ascii_lowercase())
        .filter(|value| value.len() == 64 && value.chars().all(|c| c.is_ascii_hexdigit()))
        .ok_or_else(|| {
            "This update has no published checksum, so it cannot be verified and was not downloaded. \
             Download Breeze from the website instead."
                .to_string()
        })?;
    let file_name = file_name
        .map(str::to_string)
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| {
            url.rsplit('/')
                .next()
                .map(|part| part.split('?').next().unwrap_or(part).to_string())
                .filter(|part| !part.is_empty())
                .unwrap_or_else(|| "BreezeClientSetup.exe".to_string())
        });

    fs::create_dir_all(updates_dir).map_err(|e| format!("Could not create update directory: {e}"))?;
    let target = updates_dir.join(sanitize_filename(&file_name));

    progress("download", &format!("Downloading {}", version.unwrap_or("update")), 0.05);

    // A dedicated client, NOT the shared http_client(). That one sets
    // .timeout(60s), and in reqwest that budget covers the whole request
    // including reading the body, so any connection that cannot pull the entire
    // installer inside a minute aborted partway through. Bound the transfer by
    // stalls instead of by total time.
    let client = reqwest::Client::builder()
        .user_agent("BreezeLauncher")
        .use_rustls_tls()
        .connect_timeout(Duration::from_secs(20))
        .read_timeout(Duration::from_secs(45))
        .build()
        .map_err(|e| format!("Could not initialize the downloader: {e}"))?;

    let response = client
        .get(&url)
        .send()
        .await
        .map_err(|e| format!("Update download failed: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("Update server responded with {}", response.status()));
    }
    let total = response.content_length().unwrap_or(0);

    // Streamed to disk rather than buffered whole, with progress, so a large
    // installer neither sits in RAM nor looks like a hang.
    use futures_util::StreamExt;
    use std::io::Write;

    // Written to a .part file and only renamed once size, hash and file type
    // have all been checked, so an interrupted or rejected download never
    // leaves something named like an installer on disk.
    let partial = target.with_extension("part");
    let mut file = fs::File::create(&partial).map_err(|e| format!("Could not save update: {e}"))?;
    let mut hasher = Sha256::new();
    let mut downloaded: u64 = 0;
    let mut last_emit = 0u64;
    let mut stream = response.bytes_stream();

    while let Some(chunk) = stream.next().await {
        let chunk = match chunk {
            Ok(chunk) => chunk,
            Err(e) => {
                drop(file);
                let _ = fs::remove_file(&partial);
                return Err(format!("Update download was interrupted: {e}"));
            }
        };
        hasher.update(&chunk);
        if let Err(e) = file.write_all(&chunk) {
            drop(file);
            let _ = fs::remove_file(&partial);
            return Err(format!("Could not write the update: {e}"));
        }
        downloaded += chunk.len() as u64;

        // At most every 512KB. One event per chunk would flood the webview.
        if downloaded - last_emit >= 512 * 1024 {
            last_emit = downloaded;
            let frac = if total > 0 {
                0.05 + 0.85 * (downloaded as f32 / total as f32)
            } else {
                0.5
            };
            progress(
                "download",
                &format!("Downloading {} of {}", human_bytes(downloaded), human_bytes(total)),
                frac.min(0.90),
            );
        }
    }
    file.flush().map_err(|e| format!("Could not finish writing the update: {e}"))?;
    drop(file);

    if downloaded < 1024 * 512 {
        let _ = fs::remove_file(&partial);
        return Err("Downloaded update looks incomplete, try again or download from the website.".into());
    }
    if total > 0 && downloaded < total {
        let _ = fs::remove_file(&partial);
        return Err("Update download was cut short, try again.".into());
    }

    // The bytes about to be executed must be the bytes that were published. The
    // API computes this hash from the artifact itself.
    let actual = format!("{:x}", hasher.finalize());
    if actual != expected_sha256 {
        let _ = fs::remove_file(&partial);
        return Err(
            "The downloaded update did not match its published checksum, so it was discarded. \
             Download Breeze from the website instead."
                .into(),
        );
    }

    // A matching hash proves the file is what was published, not that what was
    // published is an installer for this platform. A web page or the wrong
    // platform's build fails here instead of being handed to the system to run.
    if let Err(reason) = check_installer_format(&partial, &file_name) {
        let _ = fs::remove_file(&partial);
        return Err(reason);
    }

    let _ = fs::remove_file(&target);
    fs::rename(&partial, &target).map_err(|e| {
        let _ = fs::remove_file(&partial);
        format!("Could not finish saving the update: {e}")
    })?;
    Ok(target)
}

/// Self-update: download the new installer from the Breeze API, launch it,
/// and exit so the installer can replace this build. The NSIS installer
/// carries its own UI, so user interaction stays minimal.
#[tauri::command]
async fn download_and_install_update(app: AppHandle, request: UpdateInstallRequest) -> Result<(), String> {
    // A Linux build can only replace itself when it runs as an AppImage. From a
    // .deb or .rpm there is no file of ours to swap, so say so before
    // downloading anything.
    #[cfg(target_os = "linux")]
    if running_appimage().is_none() {
        return Err(
            "This copy of Breeze was installed from a package, so it cannot update itself. \
             Install the new version from breezeclient.net."
                .into(),
        );
    }

    // Windows and macOS download an installer that copies the app elsewhere, so
    // a temp file is fine. On Linux the download is copied over the AppImage
    // afterwards, so it is kept in the user's own folder rather than /tmp.
    let updates_dir = if cfg!(target_os = "linux") {
        breeze_home_dir()?.join("bin")
    } else {
        std::env::temp_dir().join("breeze-updates")
    };
    let progress_app = app.clone();
    let target = download_verified_update(
        &request.url,
        request.sha256.as_deref(),
        request.file_name.as_deref(),
        request.version.as_deref(),
        &updates_dir,
        move |stage, message, progress| emit_updater_event(&progress_app, stage, message, Some(progress)),
    )
    .await?;

    emit_updater_event(&app, "install", "Starting installer…", Some(0.95));

    // Hand off to the platform's native install mechanism.
    #[cfg(target_os = "windows")]
    {
        // Do not start NSIS while this process is still shutting down.  On some
        // Windows systems NSIS immediately detects Breeze as running and reports
        // an installation error.  Hand the installer to a tiny detached cmd
        // helper which waits for Breeze to disappear before launching it.
        use std::os::windows::process::CommandExt;

        let script = std::env::temp_dir().join(format!(
            "breeze-update-{}.cmd",
            std::process::id()
        ));
        let installer = target.to_string_lossy().replace('"', "\"");
     let script_body = format!(
    "@echo off\r\n\
timeout /t 3 /nobreak >nul\r\n\
start \"\" \"{}\"\r\n\
del \"%~f0\"\r\n",
    installer
);
        fs::write(&script, script_body)
            .map_err(|e| format!("Could not prepare the Windows updater: {e}"))?;

        let mut command = Command::new("cmd.exe");
        command
            .arg("/C")
            .arg(&script)
            .creation_flags(0x0000_0008 | 0x0800_0000); // DETACHED_PROCESS | CREATE_NO_WINDOW
        command
            .spawn()
            .map_err(|e| format!("Could not start the Windows updater: {e}"))?;
    }
    #[cfg(target_os = "macos")]
    {
        // Open the .dmg, the user drags Breeze into Applications (standard macOS flow).
        Command::new("open")
            .arg(&target)
            .spawn()
            .map_err(|e| format!("Could not open the installer: {e}"))?;
    }
    #[cfg(target_os = "linux")]
    {
        // The AppImage the user runs is the application. Replacing that file is
        // the update; saving a second copy elsewhere and running it once left
        // every shortcut opening the old version. The running process keeps its
        // own mount of the old file, so swapping it underneath is safe.
        use std::os::unix::fs::PermissionsExt;
        let installed = running_appimage()
            .ok_or_else(|| "Breeze is no longer running from an AppImage, so it cannot update itself.".to_string())?;
        let name = installed
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "Breeze-Client.AppImage".into());
        let staged = installed.with_file_name(format!(".{name}.update"));
        let replaced = (|| -> Result<(), String> {
            fs::copy(&target, &staged)
                .map_err(|e| format!("Could not write the update next to {}: {e}", installed.display()))?;
            fs::set_permissions(&staged, fs::Permissions::from_mode(0o755))
                .map_err(|e| format!("Could not make the update executable: {e}"))?;
            fs::rename(&staged, &installed)
                .map_err(|e| format!("Could not replace {}: {e}", installed.display()))
        })();
        let _ = fs::remove_file(&target);
        if let Err(reason) = replaced {
            let _ = fs::remove_file(&staged);
            return Err(reason);
        }
        Command::new(&installed)
            .spawn()
            .map_err(|e| format!("The update is installed but Breeze could not restart: {e}"))?;
    }

    emit_updater_event(&app, "restart", "Update ready, Breeze will close now.", Some(1.0));
    // Give the event a moment to reach the frontend, then hand over.
    std::thread::spawn(move || {
        std::thread::sleep(Duration::from_millis(600));
        app.exit(0);
    });
    Ok(())
}

/// The AppImage this process was started from, as named by the AppImage runtime.
#[cfg(target_os = "linux")]
fn running_appimage() -> Option<PathBuf> {
    std::env::var_os("APPIMAGE").map(PathBuf::from).filter(|path| path.is_file())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct CompatibilityRequest {
    version_id: String,
    #[serde(default)]
    api_base_url: Option<String>,
}

#[derive(Serialize, Clone, Debug, PartialEq)]
#[serde(rename_all = "camelCase")]
struct CompatibilityStep {
    key: String,
    label: String,
    /// ok | warn | error | unknown
    status: String,
    detail: String,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
struct VersionCompatibility {
    version_id: String,
    /// supported | without-breeze | unsupported | unknown
    overall: String,
    summary: String,
    steps: Vec<CompatibilityStep>,
    java_major: Option<u32>,
    fabric_loader: Option<String>,
    breeze_mod_version: Option<String>,
}

fn compat_step(key: &str, label: &str, status: &str, detail: impl Into<String>) -> CompatibilityStep {
    CompatibilityStep { key: key.into(), label: label.into(), status: status.into(), detail: detail.into() }
}

/// Whether a loader version meets a Fabric dependency range such as ">=0.16.10".
/// Only the ">=" and exact forms are judged; anything else is left unknown
/// rather than guessed.
fn loader_meets_range(loader: &str, range: &str) -> Option<bool> {
    let range = range.trim();
    if range.is_empty() || range == "*" {
        return Some(true);
    }
    if let Some(min) = range.strip_prefix(">=") {
        let min = min.trim();
        return Some(loader == min || version_is_newer(loader, min));
    }
    if range.chars().all(|c| c.is_ascii_digit() || c == '.') {
        return Some(loader == range);
    }
    None
}

/// Combine the individual answers into what the Play page shows.
fn summarize_compatibility(steps: &[CompatibilityStep], breeze_mod_version: Option<&str>) -> (String, String) {
    let status_of = |key: &str| steps.iter().find(|s| s.key == key).map(|s| s.status.as_str()).unwrap_or("unknown");
    if status_of("minecraft") == "error" {
        return ("unsupported".into(), "This version could not be found".into());
    }
    if status_of("fabric") == "error" {
        return ("unsupported".into(), "Fabric does not support this version".into());
    }
    if status_of("java") == "error" {
        return ("unsupported".into(), "No Java runtime is available for this version on this computer".into());
    }
    match (status_of("breeze"), status_of("loader")) {
        ("ok", "error") => ("without-breeze".into(), "Launches without Breeze: its build needs a newer Fabric Loader".into()),
        ("ok", _) => (
            "supported".into(),
            match breeze_mod_version {
                Some(version) => format!("Breeze {version} supports this version"),
                None => "Breeze supports this version".into(),
            },
        ),
        ("warn", _) => ("without-breeze".into(), "Launches without Breeze features".into()),
        _ => ("unknown".into(), "Breeze support could not be checked".into()),
    }
}

/// The compatibility chain for one Minecraft version: Minecraft, Java, Fabric
/// Loader, the Breeze build and Fabric API, each with its own status.
///
/// The Breeze step comes from what the published jar declares (the API reads
/// its fabric.mod.json), not from a file merely existing, so a jar that exists
/// but does not support the version is reported as such.
#[tauri::command]
async fn get_version_compatibility(
    app: AppHandle,
    request: CompatibilityRequest,
) -> Result<VersionCompatibility, String> {
    let version_id = request.version_id.trim().to_string();
    if version_id.is_empty() || version_id.len() > 64 {
        return Err("Choose a Minecraft version first.".into());
    }
    let client = http_client()?;
    let breeze_home = breeze_home_dir()?;
    let mut steps = Vec::new();

    // Minecraft: the version JSON, cached once a version has been launched.
    let json_path = breeze_home.join("versions").join(&version_id).join(format!("{version_id}.json"));
    let version_json: Option<Value> = if json_path.exists() {
        read_json_value(&json_path).ok()
    } else {
        match fetch_json::<Value>(&client, MOJANG_VERSION_MANIFEST_URL).await {
            Ok(manifest) => {
                let url = json_array(&manifest, &["versions"]).and_then(|versions| {
                    versions.iter().find_map(|entry| {
                        (entry.get("id").and_then(Value::as_str) == Some(version_id.as_str()))
                            .then(|| entry.get("url").and_then(Value::as_str).map(str::to_string))
                            .flatten()
                    })
                });
                match url {
                    Some(url) => fetch_json::<Value>(&client, &url).await.ok(),
                    None => None,
                }
            }
            Err(_) => None,
        }
    };
    match &version_json {
        Some(json) => {
            let kind = json.get("type").and_then(Value::as_str).unwrap_or("release");
            steps.push(compat_step("minecraft", "Minecraft", "ok", format!("{version_id} ({kind})")));
        }
        None => steps.push(compat_step("minecraft", "Minecraft", "error", "Not found in Mojang's version list, or offline")),
    }

    // Java: what the version declares, and whether this machine already has it.
    let mut java_major = None;
    if let Some(json) = &version_json {
        let requirement = java_runtime::requirement_from_version_json(json);
        java_major = Some(requirement.major);
        let home = breeze_home.clone();
        let wanted = requirement.clone();
        let found = tauri::async_runtime::spawn_blocking(move || java_runtime::find_installed(&home, &wanted))
            .await
            .ok()
            .flatten();
        let step = match found {
            Some(install) => compat_step(
                "java",
                "Java",
                "ok",
                format!("Needs Java {}, using Java {} ({})", requirement.major, install.major, install.source.as_str()),
            ),
            None if java_runtime::mojang_platform_key().is_some() => compat_step(
                "java",
                "Java",
                "ok",
                format!("Needs Java {}, downloaded from Mojang on first launch", requirement.major),
            ),
            None => compat_step(
                "java",
                "Java",
                "error",
                format!("Needs Java {}; install it and set JAVA_HOME", requirement.major),
            ),
        };
        steps.push(step);
    }

    // Fabric Loader: the stable loader the launch will use.
    let loader = fetch_latest_fabric_loader(&client, &version_id).await.ok();
    match &loader {
        Some(version) => steps.push(compat_step("fabric", "Fabric Loader", "ok", version.clone())),
        None => steps.push(compat_step("fabric", "Fabric Loader", "error", "No Fabric Loader for this version")),
    }

    // Breeze: what the published jar declares for this version.
    let mut breeze_mod_version = None;
    let api_base = request
        .api_base_url
        .as_deref()
        .and_then(trusted_api_base)
        .unwrap_or_else(|| format!("https://{}", BREEZE_API_HOSTS[0]));
    let resolve_url = format!("{api_base}/versions/mod/resolve?mc={}", url::form_urlencoded::byte_serialize(version_id.as_bytes()).collect::<String>());
    match fetch_json::<Value>(&client, &resolve_url).await {
        Ok(body) => {
            breeze_mod_version = body.get("modVersion").and_then(Value::as_str).map(str::to_string);
            let range = body.get("minecraftRange").and_then(Value::as_str).unwrap_or_default().to_string();
            match body.get("supportStatus").and_then(Value::as_str).unwrap_or("unknown") {
                "supported" => steps.push(compat_step(
                    "breeze",
                    "Breeze",
                    "ok",
                    breeze_mod_version.as_deref().map(|v| format!("Build {v}")).unwrap_or_else(|| "Build available".into()),
                )),
                "incompatible" => steps.push(compat_step(
                    "breeze",
                    "Breeze",
                    "warn",
                    format!("The published build declares Minecraft {range}, so it is not loaded"),
                )),
                "unavailable" => steps.push(compat_step("breeze", "Breeze", "warn", "No build for this version yet")),
                _ => steps.push(compat_step("breeze", "Breeze", "unknown", "The build does not say which versions it supports")),
            }

            // Breeze's own minimum loader, when the jar states one.
            if let (Some(min), Some(loader)) = (body.get("minLoader").and_then(Value::as_str), loader.as_deref()) {
                match loader_meets_range(loader, min) {
                    Some(true) => steps.push(compat_step("loader", "Loader requirement", "ok", format!("Breeze needs {min}"))),
                    Some(false) => steps.push(compat_step(
                        "loader",
                        "Loader requirement",
                        "error",
                        format!("Breeze needs Fabric Loader {min}; Fabric offers {loader}"),
                    )),
                    None => {}
                }
            }

            if body.get("requiresFabricApi").and_then(Value::as_bool) == Some(true) {
                let manifest = profile_manifest_path(&app, &version_id)
                    .ok()
                    .and_then(|path| read_mod_manifest(&path).ok())
                    .unwrap_or_default();
                let installed = manifest.iter().any(|record| is_fabric_api_record(record) && record.enabled);
                steps.push(if installed {
                    compat_step("fabric-api", "Fabric API", "ok", "Installed")
                } else {
                    compat_step("fabric-api", "Fabric API", "ok", "Installed automatically on launch")
                });
            }
        }
        Err(_) => steps.push(compat_step("breeze", "Breeze", "unknown", "The Breeze API could not be reached")),
    }

    let (overall, summary) = summarize_compatibility(&steps, breeze_mod_version.as_deref());
    Ok(VersionCompatibility {
        version_id,
        overall,
        summary,
        steps,
        java_major,
        fabric_loader: loader,
        breeze_mod_version,
    })
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct MrpackExportRequest {
    profile_id: String,
    /// Pack name written into the index. Defaults to the profile id.
    #[serde(default)]
    name: Option<String>,
    /// Include the instance's `config` folder. On by default: a pack without
    /// the mods' settings is not the setup the player had.
    #[serde(default = "default_true")]
    include_config: bool,
    /// Resource packs can be hundreds of megabytes, so they are opt in.
    #[serde(default)]
    include_resourcepacks: bool,
}

fn default_true() -> bool {
    true
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct MrpackExportSummary {
    path: String,
    /// Mods listed for Modrinth to download, with the hashes Modrinth published.
    linked_mods: usize,
    /// Mods carried inside the pack because they are not on Modrinth.
    bundled_mods: usize,
    /// Files copied into `overrides/` (bundled mods, config, resource packs).
    overrides: usize,
    warnings: Vec<String>,
}

/// Write the profile out as a Modrinth pack (`.mrpack`).
///
/// A `.mrpack` is a zip holding `modrinth.index.json` plus an `overrides/`
/// folder. Mods that came from Modrinth are listed in the index by their
/// published download URL and hashes, so the pack stays small and Modrinth's
/// own client can install it; anything else (a hand-imported jar, the config)
/// is copied into `overrides/`.
///
/// What never goes in: the Breeze runtime jar and its folder, the mod manifest,
/// the session file, logs, saves and screenshots. A pack is shared with other
/// people, so it carries the setup and nothing personal.
#[tauri::command]
async fn export_mrpack(app: AppHandle, request: MrpackExportRequest) -> Result<MrpackExportSummary, String> {
    let profile_id = request.profile_id.trim().to_string();
    let profile_dir = profile_root_dir(&app, &profile_id)?;
    let mods_dir = profile_mods_dir(&app, &profile_id)?;
    let manifest = read_mod_manifest(&profile_manifest_path(&app, &profile_id)?)?;
    let enabled: Vec<String> = enabled_mod_file_names(&manifest);
    if enabled.is_empty() {
        return Err("This version has no enabled mods to export.".into());
    }

    let client = http_client()?;
    let mut warnings: Vec<String> = Vec::new();

    // Modrinth needs both hashes for a linked file, and the download URL has to
    // be one it serves, so the authoritative answer comes from Modrinth itself
    // rather than from anything recorded locally.
    struct Linked {
        path: String,
        url: String,
        sha1: String,
        sha512: String,
        size: u64,
    }
    let mut linked: Vec<Linked> = Vec::new();
    let mut bundled: Vec<PathBuf> = Vec::new();

    for file_name in &enabled {
        let jar = mods_dir.join(file_name);
        if !jar.is_file() {
            warnings.push(format!("{file_name} is listed but missing from the mods folder, so it was left out."));
            continue;
        }
        if file_name.eq_ignore_ascii_case(BREEZE_RUNTIME_JAR) || fabric_mod_id(&jar).as_deref() == Some(BREEZE_MOD_ID) {
            // Breeze itself is installed by the launcher, from an authorized
            // download. It is not something to hand to someone else.
            continue;
        }
        let record = manifest.iter().find(|r| r.file_name.eq_ignore_ascii_case(file_name));
        let version_id = record
            .map(|r| r.installed_version_id.trim().to_string())
            .filter(|id| !id.is_empty() && id.chars().all(|c| c.is_ascii_alphanumeric()));
        let mut resolved = None;
        if let Some(version_id) = version_id {
            match fetch_json::<Value>(&client, &format!("{MODRINTH_API_BASE_URL}/version/{version_id}")).await {
                Ok(version) => {
                    resolved = version
                        .get("files")
                        .and_then(Value::as_array)
                        .and_then(|files| {
                            files
                                .iter()
                                .find(|f| f.get("primary").and_then(Value::as_bool) == Some(true))
                                .or_else(|| files.first())
                                .cloned()
                        })
                        .and_then(|file| {
                            let url = file.get("url").and_then(Value::as_str)?.to_string();
                            let sha1 = file.pointer("/hashes/sha1").and_then(Value::as_str)?.to_string();
                            let sha512 = file.pointer("/hashes/sha512").and_then(Value::as_str)?.to_string();
                            let size = file.get("size").and_then(Value::as_u64).unwrap_or(0);
                            mrpack_download_allowed(&url).then_some(Linked {
                                path: format!("mods/{}", file.get("filename").and_then(Value::as_str).unwrap_or(file_name)),
                                url,
                                sha1,
                                sha512,
                                size,
                            })
                        });
                }
                Err(error) => warnings.push(format!("Modrinth did not answer for {file_name}, so it is bundled instead ({error}).")),
            }
        }
        match resolved {
            Some(link) => linked.push(link),
            None => bundled.push(jar),
        }
    }

    let game_version = normalize_vanilla_version_id(&profile_id);
    // The loader the pack should ask for: the stable one for this Minecraft
    // version, which is what a launch installs. Offline, the pack simply does
    // not name a loader rather than naming a wrong one.
    let loader_version = fetch_latest_fabric_loader(&client, &game_version).await.unwrap_or_default();
    let pack_name = request
        .name
        .map(|name| name.trim().to_string())
        .filter(|name| !name.is_empty())
        .unwrap_or_else(|| format!("Breeze {profile_id}"));

    let mut index_files: Vec<Value> = Vec::new();
    for link in &linked {
        index_files.push(json!({
            "path": link.path,
            "hashes": { "sha1": link.sha1, "sha512": link.sha512 },
            "env": { "client": "required", "server": "unsupported" },
            "downloads": [link.url],
            "fileSize": link.size,
        }));
    }

    let mut dependencies = serde_json::Map::new();
    dependencies.insert("minecraft".into(), Value::String(game_version.clone()));
    if !loader_version.is_empty() {
        dependencies.insert("fabric-loader".into(), Value::String(loader_version));
    }
    let index = json!({
        "formatVersion": 1,
        "game": "minecraft",
        "versionId": env!("CARGO_PKG_VERSION"),
        "name": pack_name,
        "summary": "Exported from Breeze Client",
        "files": index_files,
        "dependencies": Value::Object(dependencies),
    });

    let exports_dir = breeze_home_dir()?.join("exports");
    fs::create_dir_all(&exports_dir).map_err(|e| format!("Could not create the exports folder: {e}"))?;
    let out_path = exports_dir.join(sanitize_filename(&format!("{pack_name}.mrpack")));
    let partial = out_path.with_extension("mrpack.part");
    let _ = fs::remove_file(&partial);

    let mut folders: Vec<(PathBuf, String)> = Vec::new();
    if request.include_config {
        folders.push((profile_dir.join("config"), "overrides/config".into()));
    }
    if request.include_resourcepacks {
        folders.push((profile_dir.join("resourcepacks"), "overrides/resourcepacks".into()));
    }
    let overrides = write_mrpack(&partial, &index, &bundled, &folders)?;

    let _ = fs::remove_file(&out_path);
    fs::rename(&partial, &out_path).map_err(|e| {
        let _ = fs::remove_file(&partial);
        format!("Could not save the pack: {e}")
    })?;

    Ok(MrpackExportSummary {
        path: out_path.to_string_lossy().to_string(),
        linked_mods: linked.len(),
        bundled_mods: bundled.len(),
        overrides,
        warnings,
    })
}

/// Write the pack: the index, the jars that are not on Modrinth, and the
/// folders that travel with it. Returns how many files landed in `overrides/`.
fn write_mrpack(
    out: &Path,
    index: &Value,
    bundled: &[PathBuf],
    folders: &[(PathBuf, String)],
) -> Result<usize, String> {
    use std::io::Write as _;

    let file = fs::File::create(out).map_err(|e| format!("Could not write the pack: {e}"))?;
    let mut zip = zip::ZipWriter::new(file);
    let options = zip::write::SimpleFileOptions::default();
    let mut overrides = 0usize;

    zip.start_file("modrinth.index.json", options)
        .map_err(|e| format!("Could not write the pack index: {e}"))?;
    zip.write_all(serde_json::to_string_pretty(index).unwrap_or_default().as_bytes())
        .map_err(|e| format!("Could not write the pack index: {e}"))?;

    for jar in bundled {
        let name = jar.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        let bytes = fs::read(jar).map_err(|e| format!("Could not read {name}: {e}"))?;
        zip.start_file(format!("overrides/mods/{name}"), options)
            .map_err(|e| format!("Could not add {name} to the pack: {e}"))?;
        zip.write_all(&bytes).map_err(|e| format!("Could not add {name} to the pack: {e}"))?;
        overrides += 1;
    }

    for (source, prefix) in folders {
        if !source.is_dir() {
            continue;
        }
        overrides += add_folder_to_zip(&mut zip, source, prefix, options)?;
    }

    zip.finish().map_err(|e| format!("Could not finish the pack: {e}"))?;
    Ok(overrides)
}

/// Copy a folder into the zip under `prefix`. Returns how many files were added.
/// Nothing outside the folder is followed, and launcher-private files never
/// reach a pack that gets shared.
fn add_folder_to_zip<W: std::io::Write + std::io::Seek>(
    zip: &mut zip::ZipWriter<W>,
    source: &Path,
    prefix: &str,
    options: zip::write::SimpleFileOptions,
) -> Result<usize, String> {
    use std::io::Write as _;

    let mut added = 0usize;
    let mut stack = vec![(source.to_path_buf(), prefix.to_string())];
    while let Some((dir, zip_dir)) = stack.pop() {
        let entries = match fs::read_dir(&dir) {
            Ok(entries) => entries,
            Err(_) => continue,
        };
        for entry in entries.flatten() {
            let path = entry.path();
            let name = entry.file_name().to_string_lossy().to_string();
            if name.starts_with('.') || name.eq_ignore_ascii_case(BREEZE_RUNTIME_DIR) {
                continue;
            }
            // Breeze's own config is per-player state (friends, the tag, seen
            // users), not a mod setting a pack should carry to someone else.
            if name.to_ascii_lowercase().starts_with("breeze") {
                continue;
            }
            // Symlinks are not followed: a link could otherwise pull in a file
            // from anywhere on the machine.
            let meta = match entry.metadata() {
                Ok(meta) => meta,
                Err(_) => continue,
            };
            if meta.is_symlink() {
                continue;
            }
            if meta.is_dir() {
                stack.push((path, format!("{zip_dir}/{name}")));
                continue;
            }
            if !meta.is_file() {
                continue;
            }
            let bytes = match fs::read(&path) {
                Ok(bytes) => bytes,
                Err(_) => continue,
            };
            zip.start_file(format!("{zip_dir}/{name}"), options)
                .map_err(|e| format!("Could not add {name} to the pack: {e}"))?;
            zip.write_all(&bytes).map_err(|e| format!("Could not add {name} to the pack: {e}"))?;
            added += 1;
        }
    }
    Ok(added)
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct LibraryPack {
    file_name: String,
    pack_type: String,
    size: u64,
    /// True when this version already has a record for the file.
    in_this_version: bool,
}

/// Packs already downloaded for any version.
///
/// The file lives once in the shared library, so a pack downloaded for 1.21.11
/// can be turned on for 1.20.1 without downloading it again or storing it
/// twice.
#[tauri::command]
fn list_pack_library(app: AppHandle, profile_id: String, pack_type: String) -> Result<Vec<LibraryPack>, String> {
    let pack_type = normalize_pack_type(&pack_type);
    let dir = pack_library_dir(&pack_type)?;
    let here: Vec<String> = read_pack_manifest(&pack_manifest_path(&app, &profile_id)?)
        .unwrap_or_default()
        .into_iter()
        .map(|record| record.file_name.to_ascii_lowercase())
        .collect();

    let mut packs: Vec<LibraryPack> = fs::read_dir(&dir)
        .map_err(|e| format!("Could not read the pack library: {e}"))?
        .flatten()
        .filter_map(|entry| {
            let path = entry.path();
            if !path.is_file() {
                return None;
            }
            let file_name = path.file_name()?.to_string_lossy().to_string();
            if !file_name.to_ascii_lowercase().ends_with(".zip") {
                return None;
            }
            Some(LibraryPack {
                in_this_version: here.contains(&file_name.to_ascii_lowercase()),
                size: entry.metadata().map(|meta| meta.len()).unwrap_or(0),
                pack_type: pack_type.clone(),
                file_name,
            })
        })
        .collect();
    packs.sort_by(|a, b| a.file_name.to_ascii_lowercase().cmp(&b.file_name.to_ascii_lowercase()));
    Ok(packs)
}

/// Turn a pack that is already in the library on for this version.
///
/// No download and no second copy: the version's pack folder gets a link to
/// the same bytes.
#[tauri::command]
fn use_library_pack(app: AppHandle, profile_id: String, pack_type: String, file_name: String) -> Result<ManagedPackRecord, String> {
    let pack_type = normalize_pack_type(&pack_type);
    let file_name = sanitize_filename(&file_name);
    let library = pack_library_dir(&pack_type)?.join(&file_name);
    if !library.is_file() {
        return Err("That pack is no longer in your library.".into());
    }

    let manifest_path = pack_manifest_path(&app, &profile_id)?;
    let mut manifest = read_pack_manifest(&manifest_path)?;
    if let Some(existing) = manifest.iter().find(|record| record.file_name.eq_ignore_ascii_case(&file_name)).cloned() {
        // Already known here: just make sure the file is in place.
        move_pack_file(&app, &profile_id, &existing, existing.state != PACK_STATE_DOWNLOADED)?;
        return Ok(existing);
    }

    let title = file_name.trim_end_matches(".zip").to_string();
    let record = ManagedPackRecord {
        // The exact file name, not a slug of it: two library packs whose names
        // differ only by a space and a hyphen are still two packs.
        project_id: format!("library:{}", file_name.to_ascii_lowercase()),
        project_slug: file_name.to_ascii_lowercase(),
        title,
        summary: Some("From your pack library".into()),
        icon_url: None,
        pack_type: pack_type.clone(),
        game_version: String::new(),
        installed_version_id: "library".into(),
        installed_version_name: "Library".into(),
        file_name: file_name.clone(),
        state: PACK_STATE_ENABLED.to_string(),
    };
    move_pack_file(&app, &profile_id, &record, true)?;
    manifest.retain(|item| item.project_id != record.project_id);
    manifest.push(record.clone());
    write_pack_manifest(&manifest_path, &manifest)?;
    sync_resource_pack_options(&app, &profile_id)?;
    Ok(record)
}

/// The OS this launcher build is running on, so the updater can request the
/// matching installer from the API (windows | macos | linux).
#[tauri::command]
fn get_platform() -> String {
    if cfg!(target_os = "windows") {
        "windows".to_string()
    } else if cfg!(target_os = "macos") {
        "macos".to_string()
    } else {
        "linux".to_string()
    }
}

fn java_status_from(found: Option<java_runtime::JavaInstall>, breeze_home: &Path) -> JavaRuntimeStatus {
    match found {
        Some(install) => JavaRuntimeStatus {
            installed: true,
            java_path: install.java_path.to_string_lossy().to_string(),
            source: install.source.as_str().to_string(),
            root: Some(install.java_home.to_string_lossy().to_string()),
            major: Some(install.major),
        },
        None => JavaRuntimeStatus {
            installed: false,
            java_path: String::new(),
            source: "missing".to_string(),
            root: Some(breeze_home.join("runtimes").to_string_lossy().to_string()),
            major: None,
        },
    }
}

/// Whether Java 21, the runtime current Minecraft versions use, is available.
/// Each launch still picks the exact runtime its version needs.
#[tauri::command]
async fn get_java_runtime_status() -> Result<JavaRuntimeStatus, String> {
    let breeze_home = breeze_home_dir()?;
    let home = breeze_home.clone();
    // Checking a system Java starts a process, so it stays off the async thread.
    let found = tauri::async_runtime::spawn_blocking(move || {
        java_runtime::find_installed(&home, &java_runtime::JavaRequirement::default_modern())
    })
    .await
    .map_err(|error| format!("Could not check Java: {error}"))?;
    Ok(java_status_from(found, &breeze_home))
}

/// Install Java 21 ahead of time from Settings.
#[tauri::command]
async fn ensure_java_runtime(app: AppHandle) -> Result<JavaRuntimeStatus, String> {
    let breeze_home = breeze_home_dir()?;
    emit_java_event(&app, "preparing", "Checking Java runtime…", Some(0.01));
    let install = ensure_java_for(&app, &breeze_home, &java_runtime::JavaRequirement::default_modern()).await?;
    emit_java_event(&app, "ready", &format!("Java {} is ready.", install.major), Some(1.0));
    Ok(java_status_from(Some(install), &breeze_home))
}

/// The Java a version needs: one already on this machine if it fits, otherwise
/// Mojang's runtime for that version, downloaded and verified file by file.
async fn ensure_java_for(
    app: &AppHandle,
    breeze_home: &Path,
    requirement: &java_runtime::JavaRequirement,
) -> Result<java_runtime::JavaInstall, String> {
    let home = breeze_home.to_path_buf();
    let wanted = requirement.clone();
    let found = tauri::async_runtime::spawn_blocking(move || java_runtime::find_installed(&home, &wanted))
        .await
        .map_err(|error| format!("Could not check Java: {error}"))?;
    if let Some(install) = found {
        return Ok(install);
    }

    emit_launch_stage(app, "java", &format!("Installing Java {}…", requirement.major), Some(0.07));
    // No overall timeout: the runtime's module image alone is tens of MB. A
    // stalled connection is still caught by the read timeout.
    let client = reqwest::Client::builder()
        .use_rustls_tls()
        .connect_timeout(Duration::from_secs(20))
        .read_timeout(Duration::from_secs(60))
        .build()
        .map_err(|error| format!("Could not initialize HTTP client: {error}"))?;
    let progress_app = app.clone();
    let mut last_percent: i32 = -1;
    java_runtime::install_runtime(&client, breeze_home, requirement, move |message, fraction| {
        let percent = (fraction * 100.0) as i32;
        if percent == last_percent {
            return;
        }
        last_percent = percent;
        let stage = if fraction >= 1.0 { "ready" } else { "downloading" };
        emit_java_event(&progress_app, stage, message, Some(fraction));
        emit_launch_stage(&progress_app, "java", &format!("{message} {percent}%"), Some(0.07));
    })
    .await
}

fn emit_java_event(app: &AppHandle, stage: &str, message: &str, progress: Option<f32>) {
    let _ = app.emit(
        JAVA_RUNTIME_EVENT,
        JavaRuntimeEvent {
            stage: stage.to_string(),
            message: message.to_string(),
            progress,
        },
    );
}

fn http_client() -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .user_agent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
        .use_rustls_tls()
        .connect_timeout(Duration::from_secs(15))
        .timeout(Duration::from_secs(60))
        .pool_idle_timeout(Duration::from_secs(30))
        .pool_max_idle_per_host(12)
        .tcp_nodelay(true)
        .build()
        .map_err(|error| format!("Could not initialize HTTP client: {error}"))
}

async fn fetch_json<T>(client: &reqwest::Client, url: &str) -> Result<T, String> where T: for<'de> Deserialize<'de>, { let response = client.get(url).send().await.map_err(|error| format!("Could not reach {}: {error}", url))?; if !response.status().is_success() { return Err(format!("{} returned {}", url, response.status())); } response.json::<T>().await.map_err(|error| format!("Could not parse {}: {error}", url)) }
/// Fetch `url` to `path` unless it is already there and correct.
///
/// This used to be: if the file exists, trust it; otherwise GET the URL and
/// write whatever came back. Every part of that could poison a version's cache
/// permanently.
///
/// It never looked at the HTTP status, so a 404 or a 503 wrote its HTML error
/// page to disk under the name of a jar. It verified nothing, so a truncated or
/// corrupted download was indistinguishable from a good one. It wrote with a
/// plain `fs::write`, so losing power or having antivirus lock the file midway
/// left a partial file. And because the next launch only asked whether the path
/// existed, all of those were then trusted forever: the game died with a
/// NoClassDefFoundError or a corrupt-zip trace that relaunching could never fix,
/// and the only repair was deleting files under ~/.breezeclient by hand.
///
/// `expected_sha1` is Mojang's own hash from the version JSON or asset index.
/// When it is present the file on disk is checked against it, so a bad one is
/// replaced rather than reused, and the fresh download is checked before it is
/// allowed to take the real name.
async fn ensure_download_checked(
    client: &reqwest::Client,
    path: &Path,
    url: &str,
    expected_sha1: Option<&str>,
) -> Result<(), String> {
    if path.is_file() {
        match expected_sha1 {
            // Nothing to check it against, so the old behaviour stands: assume a
            // file that is there is the file we wanted.
            None => return Ok(()),
            Some(want) => {
                if file_sha1(path).as_deref() == Some(want) {
                    return Ok(());
                }
                // Wrong content under the right name. Removing it is what makes
                // a poisoned cache self-healing instead of permanent.
                let _ = fs::remove_file(path);
            }
        }
    }
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|error| format!("Could not create download directory: {error}"))?;
    }

    // A partial write must never be visible under the real name, so the bytes
    // land beside it and are renamed once they are known to be complete. Rename
    // within a directory is atomic on every platform Breeze ships to.
    let part = path.with_extension(format!(
        "{}part",
        path.extension().and_then(|e| e.to_str()).map(|e| format!("{e}.")).unwrap_or_default()
    ));
    let mut last = String::new();
    for attempt in 1..=3u32 {
        if attempt > 1 {
            tokio::time::sleep(std::time::Duration::from_millis(400 * u64::from(attempt - 1))).await;
        }
        match download_once(client, url, &part, expected_sha1).await {
            Ok(()) => {
                let _ = fs::remove_file(path);
                return fs::rename(&part, path)
                    .map_err(|error| format!("Could not move {} into place: {error}", path.display()));
            }
            Err(error) => {
                let _ = fs::remove_file(&part);
                last = error;
            }
        }
    }
    Err(format!("Could not download {url} after 3 attempts: {last}"))
}

/// One attempt, written to `part` and verified before the caller renames it.
async fn download_once(
    client: &reqwest::Client,
    url: &str,
    part: &Path,
    expected_sha1: Option<&str>,
) -> Result<(), String> {
    let response = client
        .get(url)
        .send()
        .await
        .map_err(|error| format!("{error}"))?;
    // Without this a 404 page is written to disk as though it were the file.
    let status = response.status();
    if !status.is_success() {
        return Err(format!("server answered {status}"));
    }
    let bytes = response
        .bytes()
        .await
        .map_err(|error| format!("could not read the body: {error}"))?;
    if bytes.is_empty() {
        return Err("the server sent an empty body".into());
    }
    fs::write(part, &bytes).map_err(|error| format!("could not write {}: {error}", part.display()))?;
    if let Some(want) = expected_sha1 {
        let got = file_sha1(part).unwrap_or_default();
        if got != want {
            return Err(format!("checksum mismatch: expected {want}, got {got}"));
        }
    }
    Ok(())
}

/// The unverified form, for the callers that have no hash to check against.
async fn ensure_download(client: &reqwest::Client, path: &Path, url: &str) -> Result<(), String> {
    ensure_download_checked(client, path, url, None).await
}
async fn resolve_server_jar_url(client: &reqwest::Client, loader: &str, version: &str) -> Result<Option<String>, String> {
    let loader_key = loader.trim().to_ascii_lowercase();
    if loader_key == "paper" {
        let meta: Value = fetch_json(client, &format!("https://api.papermc.io/v2/projects/paper/versions/{version}/builds")).await?;
        let build = meta.get("builds").and_then(Value::as_array).and_then(|items| items.iter().filter_map(Value::as_u64).max()).ok_or_else(|| format!("No Paper builds found for Minecraft {version}"))?;
        return Ok(Some(format!("https://api.papermc.io/v2/projects/paper/versions/{version}/builds/{build}/downloads/paper-{version}-{build}.jar")));
    }
    if loader_key == "vanilla" {
        let manifest: Value = fetch_json(client, MOJANG_VERSION_MANIFEST_URL).await?;
        let version_url = manifest.get("versions").and_then(Value::as_array).and_then(|items| items.iter().find(|item| item.get("id").and_then(Value::as_str) == Some(version))).and_then(|item| item.get("url").and_then(Value::as_str)).ok_or_else(|| format!("No Mojang server metadata found for Minecraft {version}"))?;
        let metadata: Value = fetch_json(client, version_url).await?;
        let url = metadata.get("downloads").and_then(|downloads| downloads.get("server")).and_then(|server| server.get("url")).and_then(Value::as_str).ok_or_else(|| format!("No vanilla server jar found for Minecraft {version}"))?;
        return Ok(Some(url.to_string()));
    }
    Ok(None)
}
fn read_json_value(path: &Path) -> Result<Value, String> { let content = fs::read_to_string(path).map_err(|error| format!("Could not read {}: {error}", path.display()))?; serde_json::from_str(&content).map_err(|error| format!("Could not parse {}: {error}", path.display())) }
fn write_json_value(path: &Path, value: &Value) -> Result<(), String> { if let Some(parent) = path.parent() { fs::create_dir_all(parent).map_err(|error| format!("Could not create {}: {error}", parent.display()))?; } let content = serde_json::to_string_pretty(value).map_err(|error| format!( "Could not serialize {}: {error}", path.display()))?; fs::write(path, content).map_err(|error| format!("Could not write {}: {error}", path.display())) }
fn json_array<'a>(value: &'a Value, path: &[&str]) -> Option<&'a Vec<Value>> { json_value(value, path)?.as_array() }
fn json_string(value: &Value, path: &[&str]) -> Option<String> { json_value(value, path)?.as_str().map(str::to_string) }
fn json_value<'a>(value: &'a Value, path: &[&str]) -> Option<&'a Value> { path.iter().try_fold(value, |current, key| current.get(*key)) }
fn asset_object_path(assets_root: &Path, hash: &str) -> PathBuf { assets_root.join("objects").join(&hash[0..2]).join(hash) }
fn ensure_trailing_slash(value: &str) -> String { if value.ends_with('/') { value.to_string() } else { format!("{value}/") } }



#[allow(dead_code)]
fn num_cpus_logical() -> usize {
    std::thread::available_parallelism()
        .map(|n| n.get())
        .unwrap_or(4)
}

fn sanitize_filename(file_name: &str) -> String { file_name.chars().map(|ch| match ch { '<' | '>' | ':' | '"' | '/' | '\\' | '|' | '?' | '*' => '_', _ => ch, }).collect() }

/// Only the launcher's own window may run launcher commands.
///
/// Breeze's commands are not covered by Tauri's ACL: the app ships no permission
/// manifest, so Tauri only enforces capabilities for core and plugin commands.
/// Every command therefore answered any webview that could reach the IPC,
/// including the Microsoft sign-in window, which loads a remote page. Those
/// commands include downloading and running an installer, so a remote page
/// being able to call them is a code-execution path. The sign-in window is
/// driven from Rust and never needs a command, so refusing everything that is
/// not the main window costs nothing.
fn guard_to_main_window<R, F>(handler: F) -> impl Fn(tauri::ipc::Invoke<R>) -> bool + Send + Sync + 'static
where
    R: tauri::Runtime,
    F: Fn(tauri::ipc::Invoke<R>) -> bool + Send + Sync + 'static,
{
    move |invoke| {
        let label = invoke.message.webview().label().to_string();
        if label != "main" {
            invoke
                .resolver
                .reject("Breeze commands are only available to the launcher window.");
            return true;
        }
        handler(invoke)
    }
}

/// Join `rel` under `base` and prove the result stays inside it.
///
/// Archive entry names and paths declared inside a modpack are chosen by whoever
/// built the file. Joining them unchecked is a zip-slip: an entry named
/// `overrides/../../AppData/Roaming/Microsoft/Windows/Start Menu/Programs/Startup/x.bat`
/// writes wherever the user account can write. This rejects absolute paths,
/// drive letters, UNC prefixes, `..` and Windows alternate data streams up front,
/// then resolves the deepest ancestor that already exists so a symlink planted
/// inside the folder cannot redirect the write after the textual check.
fn contained_join(base: &Path, rel: &str) -> Result<PathBuf, String> {
    use std::path::Component;

    let normalised = rel.replace('\\', "/");
    // Archive and modpack paths are relative by definition. An absolute one is
    // malformed or malicious, so it is refused rather than quietly reinterpreted
    // as relative. That covers "/etc/x", UNC "//server/share", and Windows drive
    // paths such as "C:/Windows", which have no leading slash.
    if normalised.starts_with('/') || normalised.len() >= 2 && normalised.as_bytes()[1] == b':' {
        return Err(format!("Path escapes its folder: {rel}"));
    }
    let trimmed = normalised.as_str();
    let rel_path = Path::new(trimmed);
    if rel_path.as_os_str().is_empty() {
        return Err("An archive entry has an empty path.".into());
    }
    let mut has_normal = false;
    for component in rel_path.components() {
        match component {
            Component::Normal(part) => {
                if part.to_string_lossy().contains(':') {
                    return Err(format!("Unsafe path component in {rel}"));
                }
                has_normal = true;
            }
            Component::CurDir => {}
            Component::ParentDir | Component::RootDir | Component::Prefix(_) => {
                return Err(format!("Path escapes its folder: {rel}"));
            }
        }
    }
    if !has_normal {
        return Err(format!("Path does not name a file: {rel}"));
    }

    let target = base.join(rel_path);
    if let Ok(base_real) = base.canonicalize() {
        let mut probe = target.as_path();
        let existing = loop {
            if probe.exists() {
                break probe.canonicalize().ok();
            }
            match probe.parent() {
                Some(parent) => probe = parent,
                None => break None,
            }
        };
        if let Some(existing) = existing {
            if !existing.starts_with(&base_real) {
                return Err(format!("Path escapes its folder: {rel}"));
            }
        }
    }
    Ok(target)
}

/// Validate a profile, version or instance id before it becomes a folder name.
///
/// Every command that takes a profile id joins it under ~/.breezeclient/instances.
/// Without this, an id of "../../Documents" reached any folder the user can
/// write to, from any caller that can invoke a command.
fn instance_id(raw: &str) -> Result<&str, String> {
    let id = raw.trim();
    let valid = !id.is_empty()
        && id.len() <= 96
        && !id.starts_with('.')
        && id.chars().all(|c| c.is_ascii_alphanumeric() || matches!(c, '.' | '-' | '_' | '+'));
    if valid {
        Ok(id)
    } else {
        Err(format!("Invalid profile id: {raw}"))
    }
}

/// Whether bytes start like one of the image formats a background may be.
fn looks_like_image(bytes: &[u8]) -> bool {
    bytes.starts_with(&[0x89, b'P', b'N', b'G'])
        || bytes.starts_with(&[0xFF, 0xD8, 0xFF])
        || bytes.starts_with(b"GIF87a")
        || bytes.starts_with(b"GIF89a")
        || (bytes.len() >= 12 && &bytes[0..4] == b"RIFF" && &bytes[8..12] == b"WEBP")
        || (bytes.len() >= 12 && &bytes[4..8] == b"ftyp" && (&bytes[8..12] == b"avif" || &bytes[8..12] == b"avis"))
}

/// Hosts the updater may download an installer from.
const UPDATE_HOSTS: &[&str] = &["api.breezeclient.net"];

/// Refuse any update URL that is not https on a Breeze host.
///
/// A debug build may also use a loopback address, so the update chain can be
/// tested end to end against a local API with a real generated installer.
fn validate_update_url(url: &str) -> Result<(), String> {
    let parsed = Url::parse(url).map_err(|_| "Update URL is not valid.".to_string())?;
    let host = parsed.host_str().unwrap_or_default().to_ascii_lowercase();
    let loopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]";
    if cfg!(debug_assertions) && loopback {
        return Ok(());
    }
    if parsed.scheme() != "https" {
        return Err("Updates must be downloaded over HTTPS.".into());
    }
    if !UPDATE_HOSTS.iter().any(|allowed| host == *allowed) {
        return Err(format!("Updates can only come from Breeze servers, not {host}."));
    }
    Ok(())
}

/// Check that a downloaded file is an installer of the kind this platform runs.
fn check_installer_format(path: &Path, file_name: &str) -> Result<(), String> {
    let mut head = [0u8; 4];
    {
        use std::io::Read;
        let mut file = fs::File::open(path).map_err(|e| format!("Could not read the update: {e}"))?;
        file.read_exact(&mut head).map_err(|_| "The downloaded update is too small to be an installer.".to_string())?;
    }
    let lower = file_name.to_ascii_lowercase();
    let is_exe = head.starts_with(b"MZ");
    let is_elf = head == [0x7F, b'E', b'L', b'F'];
    // A DMG keeps its "koly" trailer 512 bytes from the end of the file.
    let is_dmg = || -> bool {
        use std::io::{Read, Seek, SeekFrom};
        let Ok(mut file) = fs::File::open(path) else { return false };
        let mut trailer = [0u8; 4];
        file.seek(SeekFrom::End(-512)).is_ok() && file.read_exact(&mut trailer).is_ok() && &trailer == b"koly"
    };

    let ok = if cfg!(target_os = "windows") {
        lower.ends_with(".exe") && is_exe
    } else if cfg!(target_os = "macos") {
        lower.ends_with(".dmg") && is_dmg()
    } else {
        lower.ends_with(".appimage") && is_elf
    };
    if ok {
        Ok(())
    } else {
        Err("The downloaded update is not an installer for this system, so it was discarded.".into())
    }
}

/// Hosts a Modrinth modpack may download from, per the .mrpack format.
///
/// Without an allowlist a pack is a list of arbitrary URLs the launcher fetches
/// and drops into the instance, including into the mods folder the game loads.
const MRPACK_DOWNLOAD_HOSTS: &[&str] = &[
    "cdn.modrinth.com",
    "github.com",
    "raw.githubusercontent.com",
    "gitlab.com",
];

fn mrpack_download_allowed(url: &str) -> bool {
    match Url::parse(url) {
        Ok(parsed) => {
            parsed.scheme() == "https"
                && parsed
                    .host_str()
                    .is_some_and(|host| MRPACK_DOWNLOAD_HOSTS.iter().any(|allowed| host.eq_ignore_ascii_case(allowed)))
        }
        Err(_) => false,
    }
}
fn normalize_feather_profile_id(profile_id: &str) -> String {
    profile_id
        .strip_suffix("-fabric")
        .unwrap_or(profile_id)
        .to_string()
}
fn derive_download_file_name(download_url: &str) -> String {
    Url::parse(download_url)
        .ok()
        .and_then(|url| url.path_segments().and_then(|segments| segments.last().map(str::to_string)))
        .map(|value| sanitize_filename(&value.replace("%2B", "+").replace("%20", " ")))
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "imported-mod.jar".into())
}
fn copy_dir_recursive(source: &Path, target: &Path) -> Result<(), String> {
    fs::create_dir_all(target)
        .map_err(|error| format!("Could not create {}: {error}", target.display()))?;
    for entry in fs::read_dir(source)
        .map_err(|error| format!("Could not read {}: {error}", source.display()))?
    {
        let entry = entry.map_err(|error| format!("Could not read directory entry: {error}"))?;
        let source_path = entry.path();
        let target_path = target.join(entry.file_name());
        let file_type = entry
            .file_type()
            .map_err(|error| format!("Could not inspect {}: {error}", source_path.display()))?;
        if file_type.is_dir() {
            copy_dir_recursive(&source_path, &target_path)?;
        } else if file_type.is_file() {
            fs::copy(&source_path, &target_path).map_err(|error| {
                format!(
                    "Could not copy {} to {}: {error}",
                    source_path.display(),
                    target_path.display()
                )
            })?;
        }
    }
    Ok(())
}

fn emit_auth_log(app: &AppHandle, stage: &str, message: &str) {
    let _ = app.emit(
        AUTH_LOG_EVENT,
        AuthLogEvent { stage: stage.to_string(), message: message.to_string() },
    );
}
fn emit_launch_stage(app: &AppHandle, stage: &str, message: &str, progress: Option<f32>) { let _ = app.emit(LAUNCH_STAGE_EVENT, LaunchStageEvent { stage: stage.to_string(), message: message.to_string(), progress, }, ); }


fn is_jvm_arg_noise(line: &str) -> bool {
    let trimmed = line.trim();
    if trimmed.is_empty() { return false; }
    
    
    
    
    if !trimmed.starts_with('-') { return false; }
    if trimmed.contains(' ') { return false; }
    trimmed.starts_with("-X")
        || trimmed.starts_with("-D")
        || trimmed.starts_with("-agentlib")
        || trimmed.starts_with("-javaagent")
        || trimmed.starts_with("-cp")
        || trimmed.starts_with("-classpath")
}

/// Longest run of hex/base64url characters we treat as opaque secret material.
const SECRET_MIN_LEN: usize = 24;

/**
 * Redact anything sensitive from a launch log line.
 *
 * Applied inside `emit_launch_log`, which is the single point every launcher and
 * JVM line passes through, so nothing sensitive can reach the frontend even if a
 * new log call is added elsewhere later. Filtering in the UI would be too late:
 * the data would already have crossed IPC and be observable by other means.
 *
 * Section 3.3 asks for an allow-list. A strict allow-list is applied to the
 * launcher's own structured lines, but raw Minecraft/Fabric stdout cannot be
 * allow-listed without discarding the very output that makes a console useful
 * for diagnosing a failed launch. Those lines are therefore redacted instead,
 * aggressively and by pattern. Flagging that deviation explicitly per 1.3.
 */
fn sanitize_log_line(line: &str) -> String {
    let mut out = line.to_string();

    // 1. Home directory and the OS username inside any path.
    if let Ok(home) = home_dir() {
        let home_str = home.to_string_lossy().to_string();
        if !home_str.is_empty() {
            out = out.replace(&home_str, "~");
            // Windows paths appear with both separator styles.
            out = out.replace(&home_str.replace('\\', "/"), "~");
        }
        if let Some(user) = home.file_name().and_then(|s| s.to_str()) {
            if user.len() >= 3 {
                out = out.replace(user, "<user>");
            }
        }
    }

    // 2. Explicit credential-bearing game arguments.
    for flag in ["--accessToken", "--session", "--uuid", "--xuid", "--clientId"] {
        if let Some(idx) = out.find(flag) {
            let after = idx + flag.len();
            let rest = &out[after..];
            // Value may follow as " value" or "=value".
            let trimmed = rest.trim_start_matches([' ', '=']);
            let skipped = rest.len() - trimmed.len();
            let end = trimmed
                .find(char::is_whitespace)
                .map(|e| after + skipped + e)
                .unwrap_or(out.len());
            out.replace_range(after..end, "=<redacted>");
        }
    }

    // 3. IPv4 literals (server addresses, local interfaces).
    let mut result = String::with_capacity(out.len());
    let mut token = String::new();
    let flush = |token: &mut String, result: &mut String| {
        let looks_ipv4 = {
            let parts: Vec<&str> = token.split('.').collect();
            parts.len() == 4
                && parts.iter().all(|p| !p.is_empty() && p.len() <= 3 && p.chars().all(|c| c.is_ascii_digit()))
        };
        // Long opaque hex / base64url runs: tokens, session ids, raw UUIDs.
        let looks_secret = token.len() >= SECRET_MIN_LEN
            && token.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.')
            && token.chars().any(|c| c.is_ascii_digit())
            && token.chars().filter(|c| c.is_ascii_alphanumeric()).count() >= SECRET_MIN_LEN;
        if looks_ipv4 {
            result.push_str("<ip>");
        } else if looks_secret {
            result.push_str("<redacted>");
        } else {
            result.push_str(token);
        }
        token.clear();
    };
    for ch in out.chars() {
        if ch.is_ascii_alphanumeric() || ch == '.' || ch == '-' || ch == '_' {
            token.push(ch);
        } else {
            flush(&mut token, &mut result);
            result.push(ch);
        }
    }
    flush(&mut token, &mut result);

    result
}

fn emit_launch_log(app: &AppHandle, message: &str) {
    if is_jvm_arg_noise(message) { return; }
    let _ = app.emit(
        LAUNCH_LOG_EVENT,
        LaunchMessageEvent { message: sanitize_log_line(message) },
    );
}

/// The "mods reset after launching" bug, pinned down.
///
/// The fixtures mirror the developer's live 1.21.11 profile, which held 75
/// records for 61 jars: each duplicate pair was the same file recorded once
/// under a Modrinth slug and once under the hex project id.
#[cfg(test)]
mod mod_transfer_tests {
    use super::*;

    /// Two profile folders, each with a mods directory and a manifest path.
    struct Profiles {
        root: PathBuf,
    }

    impl Profiles {
        fn new(name: &str) -> Self {
            let root = std::env::temp_dir()
                .join(format!("breeze-transfer-{name}-{}", std::process::id()));
            let _ = fs::remove_dir_all(&root);
            fs::create_dir_all(root.join("from/mods")).unwrap();
            fs::create_dir_all(root.join("to/mods")).unwrap();
            Self { root }
        }

        fn dir(&self, side: &str) -> PathBuf { self.root.join(side).join("mods") }
        fn manifest(&self, side: &str) -> PathBuf { self.root.join(side).join("mods-state.json") }

        fn paths(&self) -> (PathBuf, PathBuf, PathBuf, PathBuf) {
            (self.dir("from"), self.manifest("from"), self.dir("to"), self.manifest("to"))
        }

        /// Put one record and its jar in the source profile.
        fn install(&self, record: &ManagedModRecord, bytes: &[u8]) {
            fs::write(self.dir("from").join(&record.file_name), bytes).unwrap();
            write_mod_manifest(&self.manifest("from"), std::slice::from_ref(record)).unwrap();
        }
    }

    impl Drop for Profiles {
        fn drop(&mut self) { let _ = fs::remove_dir_all(&self.root); }
    }

    fn local_record(jar: &str) -> ManagedModRecord {
        ManagedModRecord {
            project_id: "custom:my-mod".into(),
            project_slug: "custom:my-mod".into(),
            canonical_id: "custom:my-mod".into(),
            title: "My Mod".into(),
            file_name: jar.into(),
            game_version: "1.20.1".into(),
            loader: "fabric".into(),
            installed_version_name: "Local import".into(),
            enabled: true,
            ..Default::default()
        }
    }

    fn transfer(p: &Profiles, project_id: &str, to_version: &str) -> Result<ManagedModRecord, String> {
        let (source_dir, source_manifest, target_dir, target_manifest) = p.paths();
        copy_mod_between_profiles(
            CopyModPaths {
                source_dir: &source_dir,
                source_manifest: &source_manifest,
                target_dir: &target_dir,
                target_manifest: &target_manifest,
            },
            project_id,
            to_version,
            "fabric",
            "1.20.1",
        )
    }

    #[test]
    fn a_local_jar_moves_to_the_other_version_and_says_where_it_came_from() {
        let p = Profiles::new("moves");
        p.install(&local_record("my-mod.jar"), b"PK jar bytes");

        let copied = transfer(&p, "custom:my-mod", "1.21.8").unwrap();

        assert_eq!(
            fs::read(p.dir("to").join("my-mod.jar")).unwrap(),
            b"PK jar bytes",
            "the jar itself has to arrive, not just a record of it",
        );
        assert_eq!(copied.game_version, "1.21.8", "the copy belongs to the version it was sent to");
        assert!(
            copied.installed_version_name.contains("copied from 1.20.1"),
            "a jar built for another version must say so: {}",
            copied.installed_version_name,
        );
        assert!(
            p.dir("from").join("my-mod.jar").is_file(),
            "transfer copies, it does not take the mod away from the version it was on",
        );
        let target = read_mod_manifest(&p.manifest("to")).unwrap();
        assert_eq!(target.len(), 1);
        assert!(record_matches_key(&target[0], "custom:my-mod"), "the copy keeps the id the UI knows it by");
    }

    #[test]
    fn transferring_the_same_mod_twice_leaves_one_record() {
        let p = Profiles::new("twice");
        p.install(&local_record("my-mod.jar"), b"PK jar");

        transfer(&p, "custom:my-mod", "1.21.8").unwrap();
        transfer(&p, "custom:my-mod", "1.21.8").unwrap();

        // Two records naming one jar is the state that made a mod look
        // uninstalled and get installed again under the other key.
        assert_eq!(read_mod_manifest(&p.manifest("to")).unwrap().len(), 1);
    }

    #[test]
    fn a_disabled_mod_arrives_disabled() {
        let p = Profiles::new("disabled");
        p.install(&ManagedModRecord { enabled: false, ..local_record("my-mod.jar") }, b"PK jar");

        // Switching a mod back on by moving it elsewhere would be the launcher
        // overriding a choice the user made.
        assert!(!transfer(&p, "custom:my-mod", "1.21.8").unwrap().enabled);
    }

    #[test]
    fn a_mod_whose_jar_has_gone_is_refused_and_writes_nothing() {
        let p = Profiles::new("missing");
        let record = local_record("my-mod.jar");
        p.install(&record, b"PK jar");
        fs::remove_file(p.dir("from").join("my-mod.jar")).unwrap();

        let error = transfer(&p, "custom:my-mod", "1.21.8").unwrap_err();
        assert!(error.contains("no longer on disk"), "unexpected error: {error}");
        assert!(
            read_mod_manifest(&p.manifest("to")).unwrap().is_empty(),
            "a record for a jar that never arrived is a mod the user cannot remove",
        );
    }

    #[test]
    fn a_mod_that_is_not_installed_cannot_be_transferred() {
        let p = Profiles::new("absent");
        p.install(&local_record("my-mod.jar"), b"PK jar");

        let error = transfer(&p, "sodium", "1.21.8").unwrap_err();
        assert!(error.contains("not installed"), "unexpected error: {error}");
    }

    #[test]
    fn a_manifest_file_name_cannot_reach_outside_the_mods_folder() {
        let p = Profiles::new("escape");
        // A mods-state.json is an ordinary file on disk that anyone can edit.
        let record = local_record("../../../stolen.jar");
        fs::create_dir_all(p.dir("from")).unwrap();
        write_mod_manifest(&p.manifest("from"), std::slice::from_ref(&record)).unwrap();
        let outside = p.root.join("stolen.jar");
        fs::write(&outside, b"do not touch").unwrap();

        assert!(transfer(&p, "custom:my-mod", "1.21.8").is_err());
        assert_eq!(
            fs::read(&outside).unwrap(),
            b"do not touch",
            "a file name out of a manifest must never be able to write outside the profile",
        );
        assert!(
            !p.dir("to").join("stolen.jar").exists(),
            "and it must not land in the target folder under a smuggled name either",
        );
    }
}

#[cfg(test)]
mod mod_manifest_tests {
    use super::*;

    #[test]
    fn a_release_is_installed_before_a_featured_beta() {
        // Sodium for 1.21.11 on 2026-10-03: the featured 0.8.15-beta.1 was
        // picked, and Reese's Sodium Options 2.2.4 accepts only 0.8.14.
        let v = |id: &str, kind: &str, featured: bool| -> ModrinthVersion {
            serde_json::from_value(json!({
                "id": id, "name": id, "version_number": id, "featured": featured,
                "version_type": kind, "files": [], "dependencies": [{"project_id": "x", "dependency_type": "required"}]
            }))
            .unwrap()
        };
        let ordered = order_modrinth_versions(vec![
            v("0.8.15-beta.1", "beta", true),
            v("0.8.14", "release", false),
            v("0.8.13", "release", true),
            v("0.8.16-alpha", "alpha", true),
        ]);
        let ids: Vec<&str> = ordered.iter().map(|v| v.id.as_str()).collect();
        assert_eq!(ids, vec!["0.8.13", "0.8.14", "0.8.15-beta.1", "0.8.16-alpha"], "featured wins only within a kind");
        assert!(ordered[0].dependencies[0].version_id.is_none());
    }

    fn record(project_id: &str, slug: &str, file: &str, enabled: bool) -> ManagedModRecord {
        ManagedModRecord {
            project_id: project_id.to_string(),
            project_slug: slug.to_string(),
            title: slug.to_string(),
            file_name: file.to_string(),
            enabled,
            ..Default::default()
        }
    }

    #[test]
    fn a_slug_and_hex_pair_for_one_jar_heals_to_one_record() {
        let manifest = vec![
            record("sodium", "sodium", "sodium-fabric-0.6.jar", true),
            record("AANobbMI", "sodium", "sodium-fabric-0.6.jar", true),
            record("gvQqBUqZ", "lithium", "lithium-fabric-0.14.jar", true),
        ];
        let (healed, changed) = heal_mod_manifest(manifest);
        assert!(changed);
        assert_eq!(healed.len(), 2, "one record per jar");
        let sodium = healed.iter().find(|r| r.file_name.starts_with("sodium")).unwrap();
        assert_eq!(sodium.project_id, "AANobbMI", "the real Modrinth id is kept over the slug");
        assert_eq!(sodium.canonical_id, "AANobbMI");
        assert_eq!(sodium.project_slug, "sodium");
    }

    #[test]
    fn healing_keeps_the_users_off_switch() {
        // malilib was disabled under its hex id and still enabled under its slug
        // in the live profile, which is why it kept loading.
        let manifest = vec![
            record("GcWjdA9I", "malilib", "malilib-0.21.jar", false),
            record("malilib", "malilib", "malilib-0.21.jar", true),
        ];
        let (healed, _) = heal_mod_manifest(manifest);
        assert_eq!(healed.len(), 1);
        assert!(!healed[0].enabled, "a disabled duplicate must not be re-enabled by its twin");
    }

    #[test]
    fn two_versions_of_one_mod_heal_to_the_newer_build_whatever_the_order() {
        let mut older = record("NNAgCjsB", "entityculling", "entityculling-fabric-1.10.1-mc1.21.11.jar", true);
        older.installed_version_name = "1.10.1".into();
        let mut newer = record("entityculling", "entityculling", "entityculling-fabric-1.10.2-mc1.21.11.jar", true);
        newer.installed_version_name = "1.10.2".into();

        for manifest in [vec![older.clone(), newer.clone()], vec![newer.clone(), older.clone()]] {
            let (healed, _) = heal_mod_manifest(manifest);
            assert_eq!(healed.len(), 1);
            assert!(healed[0].file_name.contains("1.10.2"), "kept {} instead of the newer build", healed[0].file_name);
        }
    }

    #[test]
    fn a_healed_manifest_is_stable() {
        let (once, _) = heal_mod_manifest(vec![
            record("sodium", "sodium", "sodium.jar", true),
            record("AANobbMI", "sodium", "sodium.jar", true),
        ]);
        let (twice, changed) = heal_mod_manifest(once.clone());
        assert!(!changed, "healing twice must not rewrite the file again");
        assert_eq!(twice.len(), once.len());
    }

    #[test]
    fn a_jar_only_loads_when_every_record_naming_it_is_enabled() {
        let manifest = vec![
            record("AANobbMI", "sodium", "sodium.jar", true),
            record("sodium", "sodium", "sodium.jar", false),
            record("gvQqBUqZ", "lithium", "lithium.jar", true),
        ];
        let names = enabled_mod_file_names(&manifest);
        assert_eq!(names, vec!["lithium.jar".to_string()]);
    }

    #[test]
    fn turning_a_duplicated_mod_back_on_turns_every_copy_on() {
        let mut manifest = vec![
            record("AANobbMI", "sodium", "sodium.jar", false),
            record("sodium", "sodium", "sodium.jar", false),
        ];
        let target = manifest.iter().find(|r| record_matches_key(r, "sodium")).cloned().unwrap();
        apply_enabled_to_same_mod(&mut manifest, &target, true);
        assert!(manifest.iter().all(|r| r.enabled));
        assert_eq!(enabled_mod_file_names(&manifest), vec!["sodium.jar".to_string()]);
    }

    #[test]
    fn a_reinstall_does_not_switch_a_disabled_mod_back_on() {
        let mut manifest = vec![record("AANobbMI", "sodium", "sodium.jar", false)];
        let incoming = ManagedModRecord { canonical_id: "AANobbMI".into(), ..record("AANobbMI", "sodium", "sodium.jar", true) };
        let stored = upsert_managed_mod(&mut manifest, incoming);
        assert_eq!(manifest.len(), 1);
        assert!(!stored.enabled);
    }

    #[test]
    fn the_ui_can_address_a_mod_by_any_of_its_identifiers() {
        let mut r = record("AANobbMI", "sodium", "sodium.jar", true);
        r.canonical_id = "AANobbMI".into();
        assert!(record_matches_key(&r, "AANobbMI"));
        assert!(record_matches_key(&r, "sodium"));
        assert!(record_matches_key(&r, "  aanobbmi "));
        assert!(!record_matches_key(&r, ""));
        assert!(!record_matches_key(&r, "lithium"));
    }

    #[test]
    fn runtime_cleanup_never_touches_a_mod_the_user_installed() {
        // A user mod whose name merely starts with "breeze" used to be deleted
        // on every launch by a filename-prefix sweep.
        let manifest_files = vec!["breeze-utilities-1.2.jar".to_string(), "breezemod.jar".to_string()];
        let targets = runtime_cleanup_targets(&manifest_files, Some("breeze-utilities-1.2.jar"), None);
        assert!(targets.is_empty(), "files referenced by the manifest are never cleanup targets: {targets:?}");

        let targets = runtime_cleanup_targets(&[], Some(BREEZE_RUNTIME_JAR), None);
        assert!(targets.iter().all(|t| !t.eq_ignore_ascii_case(BREEZE_RUNTIME_JAR)));
    }

    #[test]
    fn removing_a_mod_keeps_a_jar_another_record_still_uses() {
        let surviving = vec![record("gvQqBUqZ", "lithium", "shared.jar", true)];
        let orphans = orphaned_mod_files(&surviving, &["shared.jar".to_string(), "sodium.jar".to_string()]);
        assert_eq!(orphans, vec!["sodium.jar".to_string()]);
    }

    #[test]
    fn a_cached_jar_with_the_wrong_hash_is_not_reused() {
        let dir = std::env::temp_dir().join(format!("breeze-cache-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let jar = dir.join("runtime.jar");
        let bytes = b"PK\x03\x04 pretend jar contents";
        fs::write(&jar, bytes).unwrap();

        let right = sha256_hex(bytes);
        assert!(cached_jar_is_current(&jar, Some(&right)));
        assert!(cached_jar_is_current(&jar, Some(&right.to_uppercase())), "hash comparison ignores case");
        assert!(!cached_jar_is_current(&jar, Some(&"0".repeat(64))), "a stale build must be downloaded again");

        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn manifest_writes_are_atomic_and_leave_no_temp_file() {
        let dir = std::env::temp_dir().join(format!("breeze-atomic-test-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        let path = dir.join("mods-state.json");
        write_file_atomic(&path, "[]").unwrap();
        write_file_atomic(&path, "[{\"projectId\":\"x\"}]").unwrap();
        assert_eq!(fs::read_to_string(&path).unwrap(), "[{\"projectId\":\"x\"}]");
        let leftovers: Vec<_> = fs::read_dir(&dir).unwrap().flatten().filter(|e| e.file_name().to_string_lossy().ends_with("tmp")).collect();
        assert!(leftovers.is_empty(), "no .tmp file may be left behind");
        let _ = fs::remove_dir_all(&dir);
    }

    /// Heals a real mods-state.json supplied by path, without touching it.
    ///
    /// Ignored by default because it needs a real profile. Run with:
    ///   BREEZE_TEST_MANIFEST=<copy of mods-state.json> cargo test heals_a_real_profile -- --ignored --nocapture
    #[test]
    #[ignore]
    fn heals_a_real_profile_copy() {
        let Ok(path) = std::env::var("BREEZE_TEST_MANIFEST") else {
            return;
        };
        let content = fs::read_to_string(&path).expect("readable manifest copy");
        let records: Vec<ManagedModRecord> = serde_json::from_str(&content).expect("parsable manifest");
        let before = records.len();
        let distinct_files: HashSet<String> =
            records.iter().map(|r| r.file_name.trim().to_ascii_lowercase()).collect();

        let (healed, changed) = heal_mod_manifest(records);
        println!("records before: {before}, jars: {}, records after: {}", distinct_files.len(), healed.len());
        assert!(changed || before == distinct_files.len());
        // One record per MOD, not per jar: a profile can hold two versions of one
        // mod, which heal into a single record for the newer build.
        for (i, a) in healed.iter().enumerate() {
            for b in healed.iter().skip(i + 1) {
                assert!(!mods_are_same(a, b), "{} and {} still describe the same mod", a.file_name, b.file_name);
            }
            assert!(!a.canonical_id.trim().is_empty(), "{} has no canonical id", a.file_name);
            assert!(distinct_files.contains(&a.file_name.trim().to_ascii_lowercase()), "{} was invented", a.file_name);
            println!("  kept {:<48} enabled={}", a.file_name, a.enabled);
        }
    }

    #[test]
    fn an_install_request_without_a_project_id_resolves_from_the_slug() {
        // The recommended-mods panel only knows slugs and now sends no project id.
        let request: InstallModRequest = serde_json::from_str(
            r#"{"profileId":"1.21.11","gameVersion":"1.21.11","loader":"fabric","projectSlug":"sodium","title":"Sodium"}"#,
        )
        .expect("a request with only a slug must deserialize");
        assert_eq!(request.lookup_key(), "sodium");

        let with_id: InstallModRequest = serde_json::from_str(
            r#"{"profileId":"p","gameVersion":"1.21.11","loader":"fabric","projectId":"AANobbMI","projectSlug":"sodium","title":"Sodium"}"#,
        )
        .unwrap();
        assert_eq!(with_id.lookup_key(), "AANobbMI");
    }
}

/// Path and input validation for everything the webview or a downloaded file
/// can name. Each rejected case is a real way a crafted modpack, profile id or
/// command argument could previously write or delete outside its folder.
#[cfg(test)]
mod path_safety_tests {
    use super::*;

    fn temp_base(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("breeze-path-test-{name}-{}", std::process::id()));
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn contained_join_refuses_every_escape() {
        let base = temp_base("escape");
        for evil in [
            "../x",
            "a/../../x",
            "a/b/../../../x",
            "/etc/passwd",
            "C:/Windows/System32/x.dll",
            "c:\\Windows\\x",
            "\\\\server\\share\\x",
            "//server/share/x",
            "mods/evil.jar:hidden",
            "",
            ".",
            "./",
            "..\\..\\AppData\\Roaming\\Microsoft\\Windows\\Start Menu\\Programs\\Startup\\x.bat",
        ] {
            assert!(contained_join(&base, evil).is_err(), "accepted an escaping path: {evil:?}");
        }
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn contained_join_accepts_normal_pack_paths() {
        let base = temp_base("normal");
        for good in ["mods/a.jar", "./config/x.toml", "overrides/config/sodium.json", "resourcepacks/Faithful 32x.zip"] {
            let joined = contained_join(&base, good).unwrap_or_else(|e| panic!("rejected {good}: {e}"));
            assert!(joined.starts_with(&base), "{good} left the base folder");
        }
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn profile_ids_cannot_name_a_path() {
        for evil in ["..", "../../x", "", "a/b", "a\\b", ".hidden", "C:", &"x".repeat(200)] {
            assert!(instance_id(evil).is_err(), "accepted profile id {evil:?}");
        }
        for good in ["1.21.11", "fabric-1.21.8", "26.1.2", "1.20.1", "my_profile+2"] {
            assert!(instance_id(good).is_ok(), "rejected profile id {good:?}");
        }
    }

    #[test]
    fn modpack_downloads_are_limited_to_the_format_hosts() {
        assert!(mrpack_download_allowed("https://cdn.modrinth.com/data/AANobbMI/versions/x/sodium.jar"));
        assert!(mrpack_download_allowed("https://github.com/owner/repo/releases/download/v1/mod.jar"));
        assert!(!mrpack_download_allowed("http://cdn.modrinth.com/data/x.jar"), "plain http must be refused");
        assert!(!mrpack_download_allowed("https://evil.example/mod.jar"));
        assert!(!mrpack_download_allowed("https://cdn.modrinth.com.evil.example/x.jar"));
        assert!(!mrpack_download_allowed("file:///C:/Windows/x.dll"));
    }

    #[test]
    fn native_jars_with_traversal_entries_are_refused() {
        use std::io::Write;
        let base = temp_base("natives");
        let jar = base.join("evil-natives.jar");
        {
            let file = fs::File::create(&jar).unwrap();
            let mut zip = zip::ZipWriter::new(file);
            let options = zip::write::SimpleFileOptions::default();
            zip.start_file("../escaped.dll", options).unwrap();
            zip.write_all(b"not a library").unwrap();
            zip.finish().unwrap();
        }
        let out = base.join("natives");
        fs::create_dir_all(&out).unwrap();
        assert!(extract_native_jar(&jar, &out).is_err(), "a traversal entry must stop extraction");
        assert!(!base.join("escaped.dll").exists(), "nothing may be written outside the natives folder");
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn updates_only_come_from_breeze_over_https() {
        assert!(validate_update_url("https://api.breezeclient.net/versions/launcher/windows/Breeze-Client-1.0.22.exe").is_ok());
        assert!(validate_update_url("http://api.breezeclient.net/versions/launcher/windows/x.exe").is_err(), "plain http");
        assert!(validate_update_url("https://evil.example/Breeze-Client-1.0.22.exe").is_err(), "foreign host");
        assert!(validate_update_url("https://api.breezeclient.net.evil.example/x.exe").is_err(), "lookalike host");
        assert!(validate_update_url("https://breezeclient.net/downloads/latest").is_err(), "the website page is not an update");
        assert!(validate_update_url("file:///C:/Windows/System32/cmd.exe").is_err());
        assert!(validate_update_url("not a url").is_err());
    }

    #[test]
    fn an_update_must_be_an_installer_for_this_system() {
        let base = temp_base("installer");
        let html = base.join("latest.exe");
        fs::write(&html, b"<!doctype html><html>not an installer</html>").unwrap();
        assert!(check_installer_format(&html, "Breeze-Client-1.0.22.exe").is_err(), "an HTML page must be refused");

        #[cfg(target_os = "windows")]
        {
            let exe = base.join("Breeze-Client-1.0.22.exe");
            let mut bytes = b"MZ".to_vec();
            bytes.resize(4096, 0);
            fs::write(&exe, &bytes).unwrap();
            assert!(check_installer_format(&exe, "Breeze-Client-1.0.22.exe").is_ok());
            assert!(check_installer_format(&exe, "Breeze-Client-1.0.22.dmg").is_err(), "wrong platform name");
        }
        let _ = fs::remove_dir_all(&base);
    }

    #[test]
    fn only_real_images_become_backgrounds() {
        assert!(looks_like_image(&[0x89, b'P', b'N', b'G', 0x0D, 0x0A]));
        assert!(looks_like_image(&[0xFF, 0xD8, 0xFF, 0xE0]));
        assert!(looks_like_image(b"GIF89a...."));
        assert!(!looks_like_image(b"MZ\x90\x00 an executable"));
        assert!(!looks_like_image(b"@echo off"));
    }
}

/// Keeps the command ACL honest.
///
/// A command registered in the handler but missing from build.rs or the
/// capability is denied at runtime, and that launcher feature quietly stops
/// working. A command granted but not registered is dead config. Either way the
/// three lists must agree, and this is checked on every test run rather than
/// discovered by a user.
#[cfg(test)]
mod compatibility_tests {
    use super::*;

    fn step(key: &str, status: &str) -> CompatibilityStep {
        compat_step(key, key, status, "")
    }

    #[test]
    fn loader_ranges_are_judged_only_when_understood() {
        assert_eq!(loader_meets_range("0.17.3", ">=0.16.10"), Some(true));
        assert_eq!(loader_meets_range("0.16.10", ">=0.16.10"), Some(true));
        assert_eq!(loader_meets_range("0.15.11", ">=0.16.10"), Some(false));
        assert_eq!(loader_meets_range("0.17.3", "*"), Some(true));
        assert_eq!(loader_meets_range("0.17.3", "~0.17"), None);
    }

    #[test]
    fn the_overall_status_follows_the_weakest_link() {
        let supported = [step("minecraft", "ok"), step("java", "ok"), step("fabric", "ok"), step("breeze", "ok")];
        assert_eq!(summarize_compatibility(&supported, Some("2.1.0")).0, "supported");

        let no_build = [step("minecraft", "ok"), step("java", "ok"), step("fabric", "ok"), step("breeze", "warn")];
        assert_eq!(summarize_compatibility(&no_build, None).0, "without-breeze");

        let old_loader = [step("minecraft", "ok"), step("fabric", "ok"), step("breeze", "ok"), step("loader", "error")];
        assert_eq!(summarize_compatibility(&old_loader, None).0, "without-breeze");

        let no_fabric = [step("minecraft", "ok"), step("fabric", "error"), step("breeze", "ok")];
        assert_eq!(summarize_compatibility(&no_fabric, None).0, "unsupported");

        let offline = [step("minecraft", "ok"), step("fabric", "ok"), step("breeze", "unknown")];
        assert_eq!(summarize_compatibility(&offline, None).0, "unknown");
    }
}

#[cfg(test)]
mod pack_library_tests {
    use super::*;

    fn library_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("breeze-packlib-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn a_library_file_is_never_overwritten() {
        let lib = library_dir("immutable");
        let first = store_bytes_in_library(&lib, "pack.zip", b"PK first pack").unwrap();
        assert_eq!(first, "pack.zip");

        // The same bytes again reuse the entry.
        assert_eq!(store_bytes_in_library(&lib, "pack.zip", b"PK first pack").unwrap(), "pack.zip");

        // A different pack with the same name gets its own entry, and the
        // first one, which other versions may be linked to, is untouched.
        let second = store_bytes_in_library(&lib, "pack.zip", b"PK a different pack").unwrap();
        assert_ne!(second, "pack.zip");
        assert!(second.starts_with("pack-") && second.ends_with(".zip"), "{second}");
        assert_eq!(fs::read(lib.join("pack.zip")).unwrap(), b"PK first pack");
        assert_eq!(fs::read(lib.join(&second)).unwrap(), b"PK a different pack");

        // Storing that second pack again finds it under its own name.
        assert_eq!(store_bytes_in_library(&lib, "pack.zip", b"PK a different pack").unwrap(), second);
        let _ = fs::remove_dir_all(&lib);
    }

    #[test]
    fn an_imported_file_is_consumed_and_never_replaces_another_pack() {
        let lib = library_dir("import");
        let staged = lib.join("incoming.tmp");
        fs::write(lib.join("pack.zip"), b"PK already here").unwrap();
        fs::write(&staged, b"PK new import").unwrap();

        let name = store_file_in_library(&lib, "pack.zip", &staged).unwrap();
        assert_ne!(name, "pack.zip");
        assert!(!staged.exists(), "the staged file is consumed");
        assert_eq!(fs::read(lib.join("pack.zip")).unwrap(), b"PK already here");
        assert_eq!(fs::read(lib.join(&name)).unwrap(), b"PK new import");
        let _ = fs::remove_dir_all(&lib);
    }

    fn record(project: &str, file: &str) -> ManagedPackRecord {
        ManagedPackRecord {
            project_id: project.into(),
            project_slug: project.into(),
            title: project.into(),
            summary: None,
            icon_url: None,
            pack_type: "resourcepack".into(),
            game_version: String::new(),
            installed_version_id: String::new(),
            installed_version_name: String::new(),
            file_name: file.into(),
            state: PACK_STATE_ENABLED.into(),
        }
    }

    #[test]
    fn a_shared_copy_stays_while_anything_still_lists_it() {
        let only_mine = vec![("1.21.11".to_string(), Ok(vec![record("a", "pack.zip")]))];
        assert!(!pack_listed(&only_mine, "pack.zip", "1.21.11", "a"), "the last user may remove it");

        let other_version = vec![
            ("1.21.11".to_string(), Ok(vec![record("a", "pack.zip")])),
            ("1.20.1".to_string(), Ok(vec![record("b", "pack.zip")])),
        ];
        assert!(pack_listed(&other_version, "pack.zip", "1.21.11", "a"));

        // A second record in the same version counts too.
        let same_version = vec![("1.21.11".to_string(), Ok(vec![record("a", "pack.zip"), record("library:pack.zip", "pack.zip")]))];
        assert!(pack_listed(&same_version, "pack.zip", "1.21.11", "a"));

        // An unreadable state file means "still used", never "delete it".
        let unreadable = vec![
            ("1.21.11".to_string(), Ok(vec![record("a", "pack.zip")])),
            ("1.20.1".to_string(), Err("Could not parse pack state".to_string())),
        ];
        assert!(pack_listed(&unreadable, "pack.zip", "1.21.11", "a"));
    }

    #[test]
    fn a_linked_pack_is_one_copy_that_survives_removing_the_other_name() {
        let dir = std::env::temp_dir().join(format!("breeze-packlink-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(dir.join("library")).unwrap();
        fs::create_dir_all(dir.join("instance")).unwrap();
        let library = dir.join("library").join("faithful.zip");
        let installed = dir.join("instance").join("faithful.zip");
        fs::write(&library, b"PK pack bytes").unwrap();

        link_or_copy(&library, &installed).unwrap();
        assert_eq!(fs::read(&installed).unwrap(), b"PK pack bytes");

        // Turning the pack off for one version must not touch the shared copy.
        fs::remove_file(&installed).unwrap();
        assert!(library.is_file(), "the library keeps the pack for other versions");

        // Linking again over an existing file replaces it rather than failing.
        link_or_copy(&library, &installed).unwrap();
        link_or_copy(&library, &installed).unwrap();
        assert!(installed.is_file());
        let _ = fs::remove_dir_all(&dir);
    }
}

#[cfg(test)]
mod mrpack_export_tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("breeze-mrpack-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn entries(pack: &Path) -> Vec<String> {
        let file = fs::File::open(pack).unwrap();
        let mut zip = ZipArchive::new(file).unwrap();
        (0..zip.len()).map(|i| zip.by_index(i).unwrap().name().to_string()).collect()
    }

    #[test]
    fn a_pack_carries_the_index_the_local_jars_and_the_config() {
        let dir = scratch("shape");
        let mods = dir.join("mods");
        let config = dir.join("config");
        fs::create_dir_all(mods.join("nested")).unwrap();
        fs::create_dir_all(config.join("sodium")).unwrap();
        fs::write(mods.join("hand-made.jar"), b"PK\x03\x04 jar").unwrap();
        fs::write(config.join("breeze.json"), b"{}").unwrap();
        fs::write(config.join("breeze_friends.json"), b"[]").unwrap();
        fs::write(config.join("sodium").join("options.json"), b"{}").unwrap();
        // Launcher-private and hidden files never travel in a shared pack.
        fs::write(config.join(".secret"), b"no").unwrap();
        fs::create_dir_all(config.join(BREEZE_RUNTIME_DIR)).unwrap();
        fs::write(config.join(BREEZE_RUNTIME_DIR).join("session.json"), b"token").unwrap();

        let index = json!({
            "formatVersion": 1,
            "game": "minecraft",
            "name": "Breeze 1.21.11",
            "files": [{ "path": "mods/sodium.jar", "downloads": ["https://cdn.modrinth.com/x/sodium.jar"] }],
            "dependencies": { "minecraft": "1.21.11", "fabric-loader": "0.19.5" },
        });
        let pack = dir.join("out.mrpack");
        let overrides = write_mrpack(
            &pack,
            &index,
            &[mods.join("hand-made.jar")],
            &[(config.clone(), "overrides/config".to_string())],
        )
        .unwrap();

        let names = entries(&pack);
        assert!(names.contains(&"modrinth.index.json".to_string()));
        assert!(names.contains(&"overrides/mods/hand-made.jar".to_string()), "{names:?}");
        assert!(names.contains(&"overrides/config/sodium/options.json".to_string()), "{names:?}");
        assert!(!names.iter().any(|n| n.contains(".secret")), "hidden files must not travel: {names:?}");
        assert!(!names.iter().any(|n| n.contains("session.json")), "the session token must never be in a pack: {names:?}");
        assert!(!names.iter().any(|n| n.contains("breeze")), "Breeze's own per-player config must not travel: {names:?}");
        assert_eq!(overrides, 2, "one jar plus the one config file that may travel");

        // The index is the real thing Modrinth reads, so it has to parse.
        let file = fs::File::open(&pack).unwrap();
        let mut zip = ZipArchive::new(file).unwrap();
        let mut text = String::new();
        std::io::Read::read_to_string(&mut zip.by_name("modrinth.index.json").unwrap(), &mut text).unwrap();
        let parsed: Value = serde_json::from_str(&text).unwrap();
        assert_eq!(parsed["formatVersion"], 1);
        assert_eq!(parsed["dependencies"]["minecraft"], "1.21.11");
        assert_eq!(parsed["files"][0]["path"], "mods/sodium.jar");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_missing_folder_is_not_an_error_and_writes_nothing_extra() {
        let dir = scratch("empty");
        let pack = dir.join("out.mrpack");
        let overrides = write_mrpack(&pack, &json!({ "formatVersion": 1 }), &[], &[(dir.join("nope"), "overrides/config".to_string())]).unwrap();
        assert_eq!(overrides, 0);
        assert_eq!(entries(&pack), vec!["modrinth.index.json".to_string()]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn only_modrinth_hosts_may_be_linked_for_download() {
        // The index lists download URLs, and Modrinth refuses a pack that points
        // anywhere else; the same allowlist guards imports.
        assert!(mrpack_download_allowed("https://cdn.modrinth.com/data/x/versions/1/sodium.jar"));
        assert!(!mrpack_download_allowed("https://evil.example/sodium.jar"));
        assert!(!mrpack_download_allowed("http://cdn.modrinth.com/x.jar"));
    }

    #[test]
    fn a_pack_writer_leaves_no_half_file_when_a_jar_disappears() {
        let dir = scratch("gone");
        let pack = dir.join("out.mrpack");
        let missing = dir.join("mods").join("vanished.jar");
        let error = write_mrpack(&pack, &json!({ "formatVersion": 1 }), &[missing], &[]).unwrap_err();
        assert!(error.contains("vanished.jar"), "{error}");
        let _ = fs::remove_dir_all(&dir);
    }
}

#[cfg(test)]
mod runtime_lifecycle_tests {
    use super::*;
    use std::io::Write as _;

    fn fabric_jar(path: &Path, id: &str) {
        let file = fs::File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(format!("{{\"schemaVersion\": 1, \"id\": \"{id}\", \"version\": \"1\"}}").as_bytes()).unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn duplicate_mod_ids_are_found_before_fabric_finds_them() {
        let dir = std::env::temp_dir().join(format!("breeze-dupes-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mods = dir.join("mods");
        fs::create_dir_all(&mods).unwrap();
        fabric_jar(&mods.join("entityculling-1.10.1.jar"), "entityculling");
        fabric_jar(&mods.join("entityculling-1.10.2.jar"), "entityculling");
        fabric_jar(&mods.join("sodium.jar"), "sodium");
        fs::write(mods.join("notes.txt"), "not a jar").unwrap();
        fs::write(mods.join("broken.jar"), "not a zip").unwrap();

        let dupes = duplicate_fabric_mods(&mods, &[]);
        assert_eq!(dupes, vec![(
            "entityculling".to_string(),
            vec!["entityculling-1.10.1.jar".to_string(), "entityculling-1.10.2.jar".to_string()],
        )]);

        // The Breeze runtime handed over with addMods counts too.
        let runtime = dir.join("runtime.jar");
        fabric_jar(&runtime, "sodium");
        let dupes = duplicate_fabric_mods(&mods, &[runtime]);
        assert!(dupes.iter().any(|(id, files)| id == "sodium" && files.len() == 2));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn mcef_counts_as_present_by_its_mod_id_whatever_the_file_is_called() {
        let dir = std::env::temp_dir().join(format!("breeze-mcef-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mods = dir.join("mods");
        fs::create_dir_all(&mods).unwrap();
        assert!(!mods_folder_has_mod(&mods, MCEF_MOD_ID), "an empty folder has no MCEF");
        assert!(!mods_folder_has_mod(&dir.join("missing"), MCEF_MOD_ID), "a missing folder is not an error");

        fabric_jar(&mods.join("sodium.jar"), "sodium");
        fs::write(mods.join("mcef.jar"), "not a zip").unwrap();
        fabric_jar(&mods.join("mcef.jar.disabled"), "mcef");
        assert!(!mods_folder_has_mod(&mods, MCEF_MOD_ID), "a broken jar and a non-jar named after MCEF are not MCEF");

        // Installed by hand under any name: still MCEF, so no second copy.
        fabric_jar(&mods.join("browser-2.1.6.jar"), "mcef");
        assert!(mods_folder_has_mod(&mods, MCEF_MOD_ID));
        assert!(mods_folder_has_mod(&mods, "sodium"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn mcef_records_are_known_by_slug_whatever_their_id() {
        let from_modrinth = ManagedModRecord {
            project_id: "hEx1d000".into(),
            project_slug: "mcef".into(),
            ..Default::default()
        };
        let by_slug_only = ManagedModRecord { project_id: "MCEF".into(), ..Default::default() };
        let other = ManagedModRecord {
            project_id: "P7dR8mSH".into(),
            project_slug: "fabric-api".into(),
            title: "MCEF companion".into(),
            ..Default::default()
        };
        assert!(is_mcef_record(&from_modrinth));
        assert!(is_mcef_record(&by_slug_only));
        assert!(!is_mcef_record(&other), "a title mentioning MCEF does not make a mod MCEF");
    }

    #[test]
    fn older_launcher_breeze_jars_are_found_but_user_mods_are_not() {
        let dir = std::env::temp_dir().join(format!("breeze-legacy-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let mods = dir.join("mods");
        fs::create_dir_all(&mods).unwrap();
        fabric_jar(&mods.join("1.21.11.jar"), "breeze");
        fabric_jar(&mods.join("sodium.jar"), "sodium");
        fabric_jar(&mods.join("my-breeze-build.jar"), "breeze");

        let user_record = ManagedModRecord {
            file_name: "my-breeze-build.jar".into(),
            project_id: "local:my-breeze-build".into(),
            ..Default::default()
        };
        let found = legacy_breeze_jars(&mods, &[user_record], None);
        assert_eq!(found, vec![mods.join("1.21.11.jar")], "only the unrecorded Breeze jar is the launcher's leftover");

        // The jar this launch copies into the folder itself is not a leftover.
        assert!(legacy_breeze_jars(&mods, &[], Some(&mods.join("1.21.11.jar"))).iter().all(|p| !p.ends_with("1.21.11.jar")));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_benign_startup_warning_is_never_reported_as_the_cause() {
        let tail = vec![
            "[LWJGL] [ThreadLocalUtil] Unsupported JNI version detected, this may result in a crash. Please inform LWJGL developers.".to_string(),
            "SLF4J: No SLF4J providers were found.".to_string(),
        ];
        assert_eq!(stderr_failure_detail(&tail), "", "a killed game with only warnings printed no error");

        let crash = vec![
            "[LWJGL] [ThreadLocalUtil] Unsupported JNI version detected, this may result in a crash.".to_string(),
            "Exception in thread \"main\" java.lang.NoClassDefFoundError: com/cinemamod/mcef/MCEF".to_string(),
            "Caused by: java.lang.ClassNotFoundException: com.cinemamod.mcef.MCEF".to_string(),
        ];
        let detail = stderr_failure_detail(&crash);
        assert!(detail.contains("NoClassDefFoundError") && detail.contains("Caused by"), "{detail}");
        assert!(!detail.contains("JNI"), "{detail}");
    }

    #[test]
    fn runtime_files_are_removed_and_missing_ones_are_not_errors() {
        let dir = std::env::temp_dir().join(format!("breeze-runtime-rm-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        let jar = dir.join("runtime.jar");
        let cache = dir.join("1.21.11.jar");
        fs::write(&jar, "x").unwrap();
        fs::write(&cache, "x").unwrap();
        assert_eq!(remove_runtime_files(&[jar.clone(), cache.clone(), dir.join("gone.jar")]), 0);
        assert!(!jar.exists() && !cache.exists());
        let _ = fs::remove_dir_all(&dir);
    }
}

#[cfg(test)]
mod library_resolution_tests {
    use super::*;

    #[test]
    fn classifiers_keep_per_architecture_jars_apart() {
        let x64 = "io/netty/netty-transport-native-epoll/4.1.97.Final/netty-transport-native-epoll-4.1.97.Final-linux-x86_64.jar";
        let arm = "io/netty/netty-transport-native-epoll/4.1.97.Final/netty-transport-native-epoll-4.1.97.Final-linux-aarch_64.jar";
        assert_ne!(maven_coordinate_from_path(x64), maven_coordinate_from_path(arm));
        assert_eq!(maven_coordinate_from_path(x64), "io.netty:netty-transport-native-epoll:linux-x86_64");

        // Two versions of the same plain jar must still collide, so the newer wins.
        let asm_old = "org/ow2/asm/asm/9.6/asm-9.6.jar";
        let asm_new = "org/ow2/asm/asm/9.9/asm-9.9.jar";
        assert_eq!(maven_coordinate_from_path(asm_old), maven_coordinate_from_path(asm_new));
        assert_eq!(maven_coordinate_from_path(asm_new), "org.ow2.asm:asm");
    }

    #[test]
    fn pre_1_19_natives_are_found_for_this_os() {
        let library = json!({
            "name": "org.lwjgl:lwjgl:3.2.2",
            "natives": { "linux": "natives-linux", "osx": "natives-macos", "windows": "natives-windows" },
            "downloads": {
                "artifact": { "path": "org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2.jar", "url": "https://libraries.minecraft.net/a.jar" },
                "classifiers": {
                    "natives-linux": { "path": "org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-linux.jar", "url": "https://libraries.minecraft.net/linux.jar" },
                    "natives-macos": { "path": "org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-macos.jar", "url": "https://libraries.minecraft.net/macos.jar" },
                    "natives-windows": { "path": "org/lwjgl/lwjgl/3.2.2/lwjgl-3.2.2-natives-windows.jar", "url": "https://libraries.minecraft.net/windows.jar" }
                }
            }
        });
        let native = legacy_native_library(&library).expect("a native jar for this OS");
        assert!(native.native);
        let expected = if cfg!(target_os = "windows") { "windows" } else if cfg!(target_os = "macos") { "macos" } else { "linux" };
        assert!(native.url.ends_with(&format!("{expected}.jar")), "{}", native.url);

        let arch_templated = json!({
            "name": "tv.twitch:twitch-platform:5.16",
            "natives": { "linux": "natives-linux", "osx": "natives-osx", "windows": "natives-windows-${arch}" },
            "downloads": { "classifiers": {
                "natives-windows-64": { "path": "w64.jar", "url": "https://libraries.minecraft.net/w64.jar" },
                "natives-windows-32": { "path": "w32.jar", "url": "https://libraries.minecraft.net/w32.jar" },
                "natives-linux": { "path": "l.jar", "url": "https://libraries.minecraft.net/l.jar" },
                "natives-osx": { "path": "o.jar", "url": "https://libraries.minecraft.net/o.jar" }
            }}
        });
        let native = legacy_native_library(&arch_templated).expect("a native jar for this OS");
        if cfg!(all(target_os = "windows", target_pointer_width = "64")) {
            assert_eq!(native.path, "w64.jar");
        }

        let modern = json!({ "name": "org.lwjgl:lwjgl:3.3.3:natives-linux", "downloads": { "artifact": { "path": "x.jar", "url": "https://libraries.minecraft.net/x.jar" } } });
        assert!(legacy_native_library(&modern).is_none(), "1.19+ natives are libraries of their own");
    }

    #[test]
    fn the_x86_64_rule_does_not_match_arm() {
        let rule = json!({ "action": "allow", "os": { "arch": "x86_64" } });
        assert_eq!(rule_matches(&rule), std::env::consts::ARCH == "x86_64");
    }
}

#[cfg(test)]
mod game_session_tests {
    use super::*;

    #[test]
    fn only_breeze_servers_receive_the_account_token() {
        assert_eq!(
            trusted_api_base_with("https://api.breezeclient.net", false).as_deref(),
            Some("https://api.breezeclient.net")
        );
        assert_eq!(
            trusted_api_base_with("https://API.breezeclient.net/", false).as_deref(),
            Some("https://api.breezeclient.net")
        );
        for refused in [
            "http://api.breezeclient.net",
            "https://api.breezeclient.net.evil.example",
            "https://evil.example/api.breezeclient.net",
            "https://user:pass@api.breezeclient.net",
            "https://api.breezeclient.net/?next=https://evil.example",
            "https://api.breezeclient.net/#x",
            "http://127.0.0.1:3000",
            "file:///C:/Windows",
            "not a url",
            "",
        ] {
            assert!(trusted_api_base_with(refused, false).is_none(), "{refused} must not be trusted");
        }
    }

    #[test]
    fn loopback_is_only_trusted_when_allowed() {
        assert_eq!(
            trusted_api_base_with("http://127.0.0.1:3000/", true).as_deref(),
            Some("http://127.0.0.1:3000")
        );
        assert!(trusted_api_base_with("http://localhost:3000", true).is_some());
        assert!(trusted_api_base_with("http://localhost:3000", false).is_none());
        assert!(trusted_api_base_with("http://192.168.1.5:3000", true).is_none());
    }

    #[test]
    fn game_tokens_must_look_like_jwts() {
        assert!(looks_like_jwt("eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJicmVlemUtZ2FtZSJ9.c2ln-_"));
        for bad in ["", "a.b", "a..c", "a.b.c.d", "a.b.c d", "a.b.c\n", "<html>.x.y"] {
            assert!(!looks_like_jwt(bad), "{bad:?} is not a token");
        }
    }

    #[test]
    fn session_file_is_written_whole_and_replaced() {
        let dir = std::env::temp_dir().join(format!("breeze-session-test-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        let file = game_session_file(&dir);
        assert!(file.ends_with(Path::new(".breeze").join("session.json")));

        write_private_file(&file, r#"{"token":"a.b.c","expiresAt":1}"#).unwrap();
        write_private_file(&file, r#"{"token":"d.e.f","expiresAt":2}"#).unwrap();
        assert_eq!(fs::read_to_string(&file).unwrap(), r#"{"token":"d.e.f","expiresAt":2}"#);
        assert!(!file.with_extension("json.tmp").exists(), "no temp file is left behind");

        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = fs::metadata(&file).unwrap().permissions().mode() & 0o777;
            assert_eq!(mode, 0o600, "only the user can read the token");
        }
        let _ = fs::remove_dir_all(&dir);
    }
}

#[cfg(test)]
mod updater_chain_tests {
    use super::*;
    use std::io::{Read as _, Write as _};
    use std::net::TcpListener;
    use std::sync::atomic::{AtomicUsize, Ordering as AtomicOrdering};
    use std::sync::Arc;

    /// A tiny HTTP server on loopback. Each route is (path, body, declared length).
    /// A declared length above the body length simulates a dropped connection.
    fn serve(routes: Vec<(&'static str, Vec<u8>, usize)>) -> (String, Arc<AtomicUsize>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let base = format!("http://127.0.0.1:{}", listener.local_addr().unwrap().port());
        let hits = Arc::new(AtomicUsize::new(0));
        let counter = Arc::clone(&hits);
        std::thread::spawn(move || {
            for stream in listener.incoming() {
                let Ok(mut stream) = stream else { continue };
                counter.fetch_add(1, AtomicOrdering::SeqCst);
                let mut request = [0u8; 4096];
                let read = stream.read(&mut request).unwrap_or(0);
                let head = String::from_utf8_lossy(&request[..read]).to_string();
                let path = head.split_whitespace().nth(1).unwrap_or("/").to_string();
                match routes.iter().find(|(route, _, _)| *route == path) {
                    Some((_, body, declared)) => {
                        let _ = write!(stream, "HTTP/1.1 200 OK\r\nContent-Length: {declared}\r\nConnection: close\r\n\r\n");
                        let _ = stream.write_all(body);
                    }
                    None => {
                        let _ = stream.write_all(b"HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n");
                    }
                }
            }
        });
        (base, hits)
    }

    /// Bytes that pass this platform's installer format check.
    fn installer_bytes() -> (Vec<u8>, &'static str) {
        let mut bytes = vec![0u8; 700 * 1024];
        if cfg!(target_os = "windows") {
            bytes[0] = b'M';
            bytes[1] = b'Z';
            (bytes, "Breeze-Client-9.9.9.exe")
        } else if cfg!(target_os = "macos") {
            let at = bytes.len() - 512;
            bytes[at..at + 4].copy_from_slice(b"koly");
            (bytes, "Breeze-Client-9.9.9.dmg")
        } else {
            bytes[..4].copy_from_slice(&[0x7F, b'E', b'L', b'F']);
            (bytes, "Breeze-Client-9.9.9.AppImage")
        }
    }

    fn sha256_hex(bytes: &[u8]) -> String {
        format!("{:x}", Sha256::digest(bytes))
    }

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("breeze-updater-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    fn run(
        url: &str,
        sha256: Option<&str>,
        file_name: &str,
        dir: &Path,
    ) -> Result<PathBuf, String> {
        tauri::async_runtime::block_on(download_verified_update(url, sha256, Some(file_name), Some("9.9.9"), dir, |_, _, _| {}))
    }

    fn nothing_left_behind(dir: &Path) -> bool {
        fs::read_dir(dir).map(|entries| entries.count() == 0).unwrap_or(true)
    }

    #[test]
    fn a_published_installer_with_its_hash_is_accepted() {
        let (bytes, name) = installer_bytes();
        let hash = sha256_hex(&bytes);
        let (base, _) = serve(vec![("/installer", bytes.clone(), bytes.len())]);
        let dir = scratch("ok");

        let saved = run(&format!("{base}/installer"), Some(&hash), name, &dir).expect("a valid update is accepted");
        assert_eq!(fs::read(&saved).unwrap(), bytes, "the saved file is exactly what was published");
        assert!(!saved.with_extension("part").exists(), "the partial file is gone");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_web_page_is_never_treated_as_an_installer_even_with_a_matching_hash() {
        // What a misconfigured server or a CDN fallback returns: a large HTML
        // page with status 200. Its hash can be "right" if the manifest was
        // generated from the wrong file, which has happened.
        let mut page = b"<!DOCTYPE html><html><body>Download Breeze</body></html>".to_vec();
        page.resize(700 * 1024, b' ');
        let hash = sha256_hex(&page);
        let (_, name) = installer_bytes();
        let (base, _) = serve(vec![("/page", page.clone(), page.len())]);
        let dir = scratch("html");

        let error = run(&format!("{base}/page"), Some(&hash), name, &dir).unwrap_err();
        assert!(!error.is_empty());
        assert!(nothing_left_behind(&dir), "a rejected download must not stay on disk");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_hash_mismatch_is_rejected_and_discarded() {
        let (bytes, name) = installer_bytes();
        let (base, _) = serve(vec![("/installer", bytes.clone(), bytes.len())]);
        let dir = scratch("hash");

        let error = run(&format!("{base}/installer"), Some(&"0".repeat(64)), name, &dir).unwrap_err();
        assert!(error.contains("checksum"), "{error}");
        assert!(nothing_left_behind(&dir));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_dropped_connection_is_not_an_update() {
        let (bytes, name) = installer_bytes();
        let hash = sha256_hex(&bytes);
        // Declares the full length, sends half, then closes.
        let half = bytes[..bytes.len() / 2].to_vec();
        let (base, _) = serve(vec![("/installer", half, bytes.len())]);
        let dir = scratch("cut");

        assert!(run(&format!("{base}/installer"), Some(&hash), name, &dir).is_err());
        assert!(nothing_left_behind(&dir));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn nothing_is_downloaded_without_a_published_hash_or_from_a_foreign_host() {
        let (bytes, name) = installer_bytes();
        let (base, hits) = serve(vec![("/installer", bytes.clone(), bytes.len())]);
        let dir = scratch("refuse");

        assert!(run(&format!("{base}/installer"), None, name, &dir).is_err());
        assert!(run(&format!("{base}/installer"), Some("not-a-hash"), name, &dir).is_err());
        assert!(run("http://example.com/Breeze-Client-9.9.9.exe", Some(&"a".repeat(64)), name, &dir).is_err());
        assert!(run("https://evil.example/Breeze-Client-9.9.9.exe", Some(&"a".repeat(64)), name, &dir).is_err());
        assert_eq!(hits.load(AtomicOrdering::SeqCst), 0, "no request may be made for a refused update");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_missing_file_is_an_error() {
        let (_, name) = installer_bytes();
        let (base, _) = serve(vec![]);
        let dir = scratch("404");
        let error = run(&format!("{base}/gone"), Some(&"a".repeat(64)), name, &dir).unwrap_err();
        assert!(error.contains("404"), "{error}");
        let _ = fs::remove_dir_all(&dir);
    }

    /// The full chain against a real API and a real installer. Driven by
    /// breeze-api/breeze-api/scripts/updater-e2e.cjs, which starts the API with
    /// a generated installer published, asks /versions/check for the update the
    /// way the launcher does, and passes the answer in here.
    #[test]
    #[ignore]
    fn live_update_chain_from_the_api() {
        let url = std::env::var("BREEZE_UPDATE_URL").expect("BREEZE_UPDATE_URL");
        let sha256 = std::env::var("BREEZE_UPDATE_SHA256").expect("BREEZE_UPDATE_SHA256");
        let file_name = std::env::var("BREEZE_UPDATE_FILE_NAME").expect("BREEZE_UPDATE_FILE_NAME");
        let original = PathBuf::from(std::env::var("BREEZE_UPDATE_ORIGINAL").expect("BREEZE_UPDATE_ORIGINAL"));
        let dir = scratch("live");

        let saved = tauri::async_runtime::block_on(download_verified_update(
            &url,
            Some(&sha256),
            Some(&file_name),
            None,
            &dir,
            |stage, message, fraction| println!("[{stage}] {message} ({:.0}%)", fraction * 100.0),
        ))
        .expect("the published update downloads and verifies");
        let saved_bytes = fs::read(&saved).unwrap();
        assert_eq!(sha256_hex(&saved_bytes), sha256_hex(&fs::read(&original).unwrap()));
        println!("verified {} ({} bytes, sha256 {})", saved.display(), saved_bytes.len(), sha256);
        let _ = fs::remove_dir_all(&dir);
    }
}

#[cfg(test)]
mod acl_sync_tests {
    use std::collections::BTreeSet;

    fn handler_commands() -> BTreeSet<String> {
        let source = include_str!("lib.rs");
        // Built from two pieces so this test does not match its own source.
        let needle = concat!("guard_to_main_window(tauri::generate_", "handler![");
        let start = source.find(needle).expect("the handler list") + needle.len();
        let end = start + source[start..].find("])").expect("the end of the handler list");
        source[start..end]
            .split(',')
            .map(|name| name.trim())
            .filter(|name| !name.is_empty())
            .map(|name| name.rsplit("::").next().unwrap().to_string())
            .collect()
    }

    fn build_commands() -> BTreeSet<String> {
        let source = include_str!("../build.rs");
        let start = source.find("const COMMANDS").expect("the COMMANDS list");
        let end = start + source[start..].find("];").expect("the end of COMMANDS");
        source[start..end]
            .split('"')
            .skip(1)
            .step_by(2)
            .map(str::to_string)
            .collect()
    }

    fn capability_grants() -> BTreeSet<String> {
        let capability: serde_json::Value =
            serde_json::from_str(include_str!("../capabilities/default.json")).expect("valid capability JSON");
        capability["permissions"]
            .as_array()
            .expect("a permissions array")
            .iter()
            .filter_map(|p| p.as_str())
            .filter(|p| p.starts_with("allow-"))
            .map(|p| p.trim_start_matches("allow-").replace('-', "_"))
            .collect()
    }

    #[test]
    fn every_registered_command_is_declared_and_granted() {
        let handler = handler_commands();
        assert!(handler.len() > 40, "suspiciously few commands parsed: {}", handler.len());
        assert_eq!(handler, build_commands(), "build.rs COMMANDS must match generate_handler!");
        assert_eq!(handler, capability_grants(), "capabilities/default.json must grant exactly the registered commands");
    }

    #[test]
    fn the_capability_is_limited_to_the_launcher_window() {
        let capability: serde_json::Value =
            serde_json::from_str(include_str!("../capabilities/default.json")).unwrap();
        assert_eq!(capability["windows"], serde_json::json!(["main"]));
        // A "remote" block would grant these commands to web pages. It must never
        // be added to this capability.
        assert!(capability.get("remote").is_none(), "the launcher capability must not grant remote origins");
    }
}

#[cfg(test)]
mod log_sanitizer_tests {
    use super::sanitize_log_line;

    #[test]
    fn redacts_access_token_argument() {
        let out = sanitize_log_line("--accessToken eyJhbGciOiJIUzI1NiJ9.abcdefghijklmnop --version 1.20.1");
        assert!(!out.contains("eyJhbGciOiJIUzI1NiJ9"), "token leaked: {out}");
        assert!(out.contains("<redacted>"), "no redaction marker: {out}");
        assert!(out.contains("--version"), "dropped following args: {out}");
    }

    #[test]
    fn redacts_ipv4_addresses() {
        let out = sanitize_log_line("Connecting to 192.168.1.44:25565");
        assert!(!out.contains("192.168.1.44"), "ip leaked: {out}");
        assert!(out.contains("<ip>"), "no ip marker: {out}");
    }

    #[test]
    fn redacts_long_opaque_identifiers() {
        let out = sanitize_log_line("session 66c4977a64934e288e76f9a32649e71b established");
        assert!(!out.contains("66c4977a64934e288e76f9a32649e71b"), "uuid leaked: {out}");
    }

    #[test]
    fn keeps_ordinary_log_lines_readable() {
        let out = sanitize_log_line("[Launch] Loading mod sodium 0.5.8");
        assert!(out.contains("sodium"), "useful content lost: {out}");
        assert!(out.contains("[Launch]"), "prefix lost: {out}");
    }

    #[test]
    fn keeps_version_numbers_and_short_ids() {
        let out = sanitize_log_line("Minecraft 1.20.1 with Fabric 0.15.11");
        assert!(out.contains("1.20.1"), "version mangled: {out}");
        assert!(out.contains("0.15.11"), "loader version mangled: {out}");
    }
}

#[derive(serde::Serialize)]
struct RecordingsInfo { path: String, files: Vec<RecordingFile> }
#[derive(serde::Serialize)]
struct RecordingFile { name: String, size_bytes: u64, modified_unix: u64 }


#[tauri::command]
fn get_recordings_dir(app: AppHandle, version_id: String) -> Result<RecordingsInfo, String> {
    let instance = profile_root_dir(&app, &version_id)?;
    let dir = instance.join("recordings");
    fs::create_dir_all(&dir).map_err(|e| format!("create recordings dir: {}", e))?;

    let mut files = Vec::new();
    if let Ok(it) = fs::read_dir(&dir) {
        for entry in it.flatten() {
            let path = entry.path();
            if !path.is_file() { continue; }
            let name = match path.file_name().and_then(|n| n.to_str()) {
                Some(n) => n.to_string(),
                None => continue,
            };
            let meta = match entry.metadata() { Ok(m) => m, Err(_) => continue };
            let modified_unix = meta.modified().ok()
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);
            files.push(RecordingFile { name, size_bytes: meta.len(), modified_unix });
        }
    }
    files.sort_by(|a, b| b.modified_unix.cmp(&a.modified_unix));

    Ok(RecordingsInfo { path: dir.to_string_lossy().to_string(), files })
}
fn emit_launch_error(app: &AppHandle, message: &str) { let _ = app.emit(LAUNCH_ERROR_EVENT, LaunchMessageEvent { message: message.to_string(), }, ); }

/// Debug builds only: launch a Minecraft version with the stored active account
/// as soon as the app starts, and print every launch event to stdout.
///
/// This verifies the real launch pipeline end to end (Java, Fabric, the Breeze
/// runtime, JVM, process watching, exit handling) without driving the UI by
/// hand. Set `BREEZE_DEBUG_AUTOLAUNCH=<version>`; with
/// `BREEZE_DEBUG_EXIT_WHEN_DONE=1` the launcher quits once the game has ended.
/// Release builds do not contain this code.
#[cfg(debug_assertions)]
fn start_debug_autolaunch(app: &AppHandle) {
    use tauri::Listener;

    // Debug builds only: list a profile's packs, which migrates any per-instance
    // copies into the shared library, and print where each file ended up.
    if let Ok(profile) = std::env::var("BREEZE_DEBUG_PACKS") {
        let handle = app.clone();
        tauri::async_runtime::spawn(async move {
            if let Ok(file_name) = std::env::var("BREEZE_DEBUG_USE_PACK") {
                match use_library_pack(handle.clone(), profile.clone(), "resourcepack".into(), file_name) {
                    Ok(record) => println!("[packs] added {} as {}", record.file_name, record.project_id),
                    Err(error) => println!("[packs] add failed: {error}"),
                }
            }
            match list_installed_packs(handle.clone(), profile.clone()) {
                Ok(records) => {
                    for record in &records {
                        let library = pack_library_dir(&record.pack_type).map(|d| d.join(&record.file_name));
                        let installed = pack_install_dir(&handle, &profile, &record.pack_type).map(|d| d.join(&record.file_name));
                        println!(
                            "[packs] {} state={} library={} installed={}",
                            record.file_name,
                            record.state,
                            library.map(|p| p.is_file()).unwrap_or(false),
                            installed.map(|p| p.is_file()).unwrap_or(false),
                        );
                    }
                    println!("[packs] {} record(s)", records.len());
                }
                Err(error) => println!("[packs] failed: {error}"),
            }
            if std::env::var_os("BREEZE_DEBUG_EXIT_WHEN_DONE").is_some() {
                handle.exit(0);
            }
        });
    }

    // Debug builds only: export the profile as a pack and print the summary,
    // so the exporter can be run against a real instance without the UI.
    if let Ok(profile) = std::env::var("BREEZE_DEBUG_EXPORT") {
        let handle = app.clone();
        tauri::async_runtime::spawn(async move {
            let request = MrpackExportRequest {
                profile_id: profile,
                name: None,
                include_config: true,
                include_resourcepacks: false,
            };
            match export_mrpack(handle.clone(), request).await {
                Ok(summary) => println!("[export] {}", serde_json::to_string(&summary).unwrap_or_default()),
                Err(error) => println!("[export] failed: {error}"),
            }
            if std::env::var_os("BREEZE_DEBUG_EXIT_WHEN_DONE").is_some() {
                handle.exit(0);
            }
        });
    }

    let Ok(version) = std::env::var("BREEZE_DEBUG_AUTOLAUNCH") else { return };
    let exit_when_done = std::env::var_os("BREEZE_DEBUG_EXIT_WHEN_DONE").is_some();

    for event in [LAUNCH_STAGE_EVENT, LAUNCH_LOG_EVENT, LAUNCH_ERROR_EVENT, JAVA_RUNTIME_EVENT] {
        app.listen(event, move |e| println!("[autolaunch] {event} {}", e.payload()));
    }
    let exit_app = app.clone();
    app.listen(LAUNCH_COMPLETE_EVENT, move |e| {
        let payload = e.payload().to_string();
        println!("[autolaunch] {LAUNCH_COMPLETE_EVENT} {payload}");
        let finished = [LAUNCH_STATUS_EXITED, LAUNCH_STATUS_CRASHED, LAUNCH_STATUS_STARTUP_FAILED]
            .iter()
            .any(|status| payload.contains(&format!("\"status\":\"{status}\"")));
        if exit_when_done && finished {
            exit_app.exit(0);
        }
    });

    let app = app.clone();
    tauri::async_runtime::spawn(async move {
        let account = match read_account_store(&app).map(|store| store.active_account().cloned()) {
            Ok(Some(account)) => account,
            Ok(None) => {
                println!("[autolaunch] no active account");
                return;
            }
            Err(error) => {
                println!("[autolaunch] could not read accounts: {error}");
                return;
            }
        };
        let api = format!("https://{}", BREEZE_API_HOSTS[0]);
        let request = LaunchRequest {
            version_id: version.clone(),
            loader_version: None,
            max_ram_mb: read_launcher_settings(&app).ok().map(|settings| settings.allocated_ram_mb),
            account: Some(account),
            breeze_mod_url: Some(format!("{api}/versions/mod/{version}.jar")),
            breeze_mod_sha256: None,
            breeze_token: None,
            api_base_url: Some(api),
        };
        match launch_minecraft(app.clone(), request).await {
            Ok(event) => println!("[autolaunch] launch_minecraft returned status={}", event.status),
            Err(error) => {
                println!("[autolaunch] launch_minecraft failed: {error}");
                if exit_when_done {
                    app.exit(1);
                }
            }
        }
    });
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    dotenvy::from_path("../.env").ok();
    dotenvy::dotenv().ok();
    log_breeze_home_once();

    // WebKitGTK's DMA-BUF renderer shows a blank white window on NVIDIA's
    // proprietary driver. It has to be switched off before the webview starts,
    // and only there: other drivers keep hardware compositing. A value the user
    // set themselves is left alone.
    #[cfg(target_os = "linux")]
    if std::env::var_os("WEBKIT_DISABLE_DMABUF_RENDERER").is_none()
        && Path::new("/proc/driver/nvidia/version").exists()
    {
        std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
    }

    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .setup(|app| {
            #[cfg(debug_assertions)]
            start_debug_autolaunch(app.handle());
            #[cfg(not(debug_assertions))]
            let _ = app;
            Ok(())
        })
        // Drop the Rich Presence as the window goes away. The frontend also
        // clears on unmount, but that does not run if the process is killed or
        // the window is closed by the OS, and a stale activity can otherwise sit
        // on someone's profile for minutes after Breeze has quit.
        .on_window_event(|_window, event| {
            if matches!(event, tauri::WindowEvent::Destroyed | tauri::WindowEvent::CloseRequested { .. }) {
                discord::clear();
            }
        })
        .invoke_handler(guard_to_main_window(tauri::generate_handler![
            launcher_manifest,
            list_installed_mods,
            scan_instance_mods,
            remove_mod_file,
            open_instance_folder,
            list_mod_versions,
            stage_mod_version,
            apply_mod_change,
            discard_mod_change,
            list_mod_backups,
            restore_mod_backup,
            install_modrinth_mod,
            import_custom_mod,
            set_mod_enabled,
            remove_installed_mod,
            copy_local_mod_to_profile,
            list_installed_packs,
            list_pack_library,
            use_library_pack,
            download_modrinth_pack,
            import_custom_pack,
            set_pack_state,
            remove_installed_pack,
            prepare_local_server,
            import_local_server_file,
            start_local_server,
            stop_local_server,
            read_local_server_log,
            get_launcher_settings,
            update_launcher_settings,
            import_feather_preferences,
            import_lunar_preferences,
            import_badlion_preferences,
            import_vanilla_preferences,
            import_modrinth_app_preferences,
            import_mrpack,
            export_mrpack,
            stage_import_chunk,
            clear_staged_import,
            detect_importable_clients,
            import_everything,
            apply_performance_profile,
            prewarm_version,
            get_system_memory_info,
            begin_microsoft_auth,
            restore_saved_session,
            clear_saved_session,
            save_custom_background,
            save_custom_background_staged,
            load_custom_background,
            discord::discord_set_presence,
            discord::discord_clear_presence,
            clear_custom_background,
            list_saved_accounts,
            switch_account,
            remove_saved_account,
            sign_out_all_accounts,
            prepare_launch,
            launch_minecraft,
            ensure_java_runtime,
            get_java_runtime_status,
            download_and_install_update,
            get_platform,
            get_version_compatibility,
            get_recordings_dir
        ]))
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}

#[cfg(test)]
mod heap_sizing_tests {
    use super::*;

    /// The recommendation used to be the ceiling itself: 6GB of committed heap on
    /// an 8GB laptop whose iGPU takes its VRAM from that same 8GB, and 10GB on a
    /// 16GB machine. Minecraft cannot use that, and -Xms is pinned to -Xmx, so it
    /// was taken from the page cache and the GPU driver before the first frame.
    #[test]
    fn the_recommendation_is_what_minecraft_can_use_not_what_fits() {
        assert_eq!(recommended_heap_mb(8192), 4096, "8GB machine");
        assert_eq!(recommended_heap_mb(16384), 6144, "16GB machine");
        assert_eq!(recommended_heap_mb(32768), 8192, "32GB machine");
        assert_eq!(recommended_heap_mb(6144), 3072, "6GB machine");
    }

    /// What detect_total_memory_mb actually returns is memory available to the
    /// OS, not memory installed. Measured on an 8GB Windows laptop,
    /// Win32_ComputerSystem.TotalPhysicalMemory is 8379490304 bytes = 7991MB,
    /// while Win32_PhysicalMemory sums to 8192MB. The old table compared against
    /// exact powers of two, so that machine failed `>= 8192` and was graded as a
    /// 6GB one. Every size had the same problem and the top arm was unreachable.
    #[test]
    fn a_machine_is_graded_by_its_size_not_by_what_firmware_left_over() {
        // Real measurement, not a round number.
        assert_eq!(recommended_heap_mb(7991), 4096, "an 8GB laptop reporting 7991MB");
        // Typical reported values for the other common sizes.
        assert_eq!(recommended_heap_mb(16_184), 6144, "a 16GB machine reporting 16184MB");
        assert_eq!(recommended_heap_mb(32_600), 8192, "a 32GB machine reporting 32600MB");
        // A machine and the same machine minus its firmware reservation must not
        // land in different tiers.
        for nominal in [8192u64, 16384, 32768] {
            let reported = nominal - (nominal / 100).max(64);
            assert_eq!(
                recommended_heap_mb(nominal),
                recommended_heap_mb(reported),
                "{nominal}MB installed and {reported}MB reported must grade the same",
            );
        }
    }

    #[test]
    fn the_recommendation_never_reaches_the_ceiling() {
        for total in [4096u64, 6144, 8192, 12288, 16384, 24576, 32768, 65536] {
            let rec = recommended_heap_mb(total);
            let ceiling = safe_heap_ceiling_mb(total);
            assert!(rec <= ceiling, "{total}MB: recommended {rec} exceeds ceiling {ceiling}");
            assert!(
                rec < total,
                "{total}MB: recommended {rec} leaves the machine nothing",
            );
        }
    }

    /// A quarter of RAM, or 2GB, whichever is larger, stays with the system.
    #[test]
    fn the_ceiling_always_leaves_the_system_room() {
        assert_eq!(safe_heap_ceiling_mb(8192), 6144);
        assert_eq!(safe_heap_ceiling_mb(16384), 12288);
        assert_eq!(safe_heap_ceiling_mb(32768), 24576);
        for total in [2048u64, 4096, 8192, 16384, 32768, 131072] {
            assert!(safe_heap_ceiling_mb(total) <= total, "{total}MB: ceiling exceeds RAM");
        }
    }

    /// "Quality" is 8192 whatever the machine, and the Feather import copies a
    /// number out of another launcher's config. Both are bounded by the same
    /// ceiling the settings slider respects, and the launch site clamps again in
    /// case a figure was saved by an older build.
    #[test]
    fn a_profile_figure_is_bounded_by_the_machine() {
        let on_8gb = safe_heap_ceiling_mb(8192) as u32;
        assert_eq!(8192u32.min(on_8gb), 6144, "Quality on an 8GB machine");
        assert_eq!(6144u32.min(on_8gb), 6144, "Balanced on an 8GB machine");
        // And the launch-site clamp, which is the one every path passes through.
        let clamped = 16384u32.min(safe_heap_ceiling_mb(8192) as u32).max(1024);
        assert_eq!(clamped, 6144, "a stale 16GB setting on an 8GB machine");
    }
}

#[cfg(test)]
mod download_integrity_tests {
    use super::*;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("breeze-dl-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// sha1 of "good bytes", so the test states the expectation rather than
    /// computing it with the same helper it is checking.
    const GOOD: &[u8] = b"good bytes";
    const GOOD_SHA1: &str = "ac75c2cfa1cd6a3e5bfc17e3130b6e77c9fcbeca";

    #[test]
    fn the_hash_the_test_relies_on_is_the_one_the_launcher_computes() {
        let dir = scratch("hash");
        let f = dir.join("a.bin");
        fs::write(&f, GOOD).unwrap();
        assert_eq!(file_sha1(&f).as_deref(), Some(GOOD_SHA1));
        let _ = fs::remove_dir_all(&dir);
    }

    /// A correct file must satisfy the check without a request. The URL here is
    /// unroutable, so if it ever reached the network this would fail.
    #[tokio::test]
    async fn a_file_that_already_matches_is_not_downloaded_again() {
        let dir = scratch("hit");
        let f = dir.join("client.jar");
        fs::write(&f, GOOD).unwrap();
        let client = reqwest::Client::new();
        let res = ensure_download_checked(
            &client,
            &f,
            "http://127.0.0.1:1/never-reachable",
            Some(GOOD_SHA1),
        )
        .await;
        assert!(res.is_ok(), "a matching file should short-circuit: {res:?}");
        assert_eq!(fs::read(&f).unwrap(), GOOD, "and be left alone");
        let _ = fs::remove_dir_all(&dir);
    }

    /// The property that makes a poisoned cache self-healing. Before this, a
    /// truncated or corrupted file was trusted forever because the only question
    /// asked was whether the path existed, so the game failed every launch and
    /// the only repair was deleting files by hand.
    #[tokio::test]
    async fn a_file_with_the_wrong_contents_is_removed_rather_than_reused() {
        let dir = scratch("poison");
        let f = dir.join("client.jar");
        fs::write(&f, b"truncated garbage").unwrap();
        let client = reqwest::Client::new();
        let res = ensure_download_checked(
            &client,
            &f,
            "http://127.0.0.1:1/never-reachable",
            Some(GOOD_SHA1),
        )
        .await;
        assert!(res.is_err(), "it must not accept the bad file");
        assert!(
            !f.exists(),
            "the bad file must be gone, so the next attempt is not poisoned by it",
        );
        let _ = fs::remove_dir_all(&dir);
    }

    /// A failed download must leave nothing behind under the real name.
    #[tokio::test]
    async fn a_failed_download_leaves_no_partial_file() {
        let dir = scratch("partial");
        let f = dir.join("library.jar");
        let client = reqwest::Client::new();
        let res = ensure_download_checked(&client, &f, "http://127.0.0.1:1/nope", None).await;
        assert!(res.is_err());
        assert!(!f.exists(), "no file under the real name");
        let leftovers: Vec<_> = fs::read_dir(&dir)
            .unwrap()
            .filter_map(|e| e.ok().map(|e| e.file_name().to_string_lossy().into_owned()))
            .collect();
        assert!(leftovers.is_empty(), "no .part left behind either: {leftovers:?}");
        let _ = fs::remove_dir_all(&dir);
    }
}
