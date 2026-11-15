//! Choosing the version of an installed mod, and going back to the old one.
//!
//! A change happens in two steps the player sees. Staging downloads the chosen
//! file into the instance's staging folder, checks Modrinth's hash, reads its
//! fabric.mod.json and works out what the change would do to every other mod
//! (`mods_local::impact`). The mods folder is untouched until then. Applying
//! moves every current file of that mod id into the backups and renames the
//! staged jar in. The backup folder records the change, so the Mods page can
//! put the old version back.
//!
//! Mods are matched by their Fabric mod id, never by file name.

use crate::mods_commands::{self, InstanceModsReport, ModEntry, MovedFile, Placed, STAGING_DIR};
use crate::mods_local::{self, Environment, Impact, LocalJar};
use crate::{
    fetch_json, fetch_modrinth_project, fetch_project_versions, http_client, profile_manifest_path, profile_mods_dir,
    profile_root_dir, read_mod_manifest, record_matches_key, sanitize_filename, upsert_managed_mod, write_mod_manifest,
    ManagedModRecord, ModrinthVersion, MODRINTH_API_BASE_URL,
};
use serde::{Deserialize, Serialize};
use std::{
    fs,
    path::{Path, PathBuf},
    time::{Duration, SystemTime, UNIX_EPOCH},
};
use tauri::AppHandle;

const STAGED_FILE: &str = "staged.json";
const CHANGE_FILE: &str = "change.json";
/// Staged downloads nobody applied are cleared after a day.
const STAGED_MAX_AGE: Duration = Duration::from_secs(24 * 60 * 60);

// ── What can be chosen ──────────────────────────────────────────────────────

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct VersionChoice {
    pub id: String,
    pub version_number: String,
    pub name: String,
    /// release, beta or alpha, as the author published it.
    pub version_type: String,
    pub date_published: String,
    pub featured: bool,
    pub game_versions: Vec<String>,
    pub file_name: String,
    pub size: u64,
    /// The file installed now is this version's file.
    pub installed: bool,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ModVersions {
    pub key: String,
    pub mod_id: String,
    pub name: String,
    pub installed_version: String,
    pub file_name: String,
    pub project_id: Option<String>,
    /// How the Modrinth project was found: "record" (the launcher installed
    /// it) or "hash" (Modrinth recognised the jar's SHA-1).
    pub identified_by: Option<String>,
    pub minecraft: String,
    pub loader: String,
    /// Versions Modrinth publishes for this Minecraft version with Fabric,
    /// newest first. Whether one really fits is checked when it is staged.
    pub versions: Vec<VersionChoice>,
    /// Why nothing is offered, when nothing is.
    pub note: Option<String>,
}

/// The mod a key from the Mods page names.
fn find_entry(report: &InstanceModsReport, manifest: &[ManagedModRecord], key: &str) -> Result<ModEntry, String> {
    let key = key.trim();
    if let Some(entry) = report.mods.iter().find(|m| m.key == key) {
        return Ok(entry.clone());
    }
    let jar = match key.strip_prefix(mods_commands::FILE_KEY_PREFIX) {
        Some(name) => Some(name.to_string()),
        None => manifest.iter().find(|r| record_matches_key(r, key)).map(|r| r.file_name.trim().to_string()),
    };
    jar.and_then(|j| report.mods.iter().find(|m| m.jar_name.eq_ignore_ascii_case(&j)).cloned())
        .ok_or_else(|| "That mod is no longer in the mods folder.".to_string())
}

fn mod_id_of(entry: &ModEntry) -> Result<String, String> {
    if entry.breeze {
        return Err("Breeze is installed and updated by the launcher itself.".into());
    }
    entry.mod_id.clone().ok_or_else(|| {
        entry
            .problem
            .clone()
            .unwrap_or_else(|| format!("{} has no Fabric metadata.", entry.file_name))
    })
}

/// The Modrinth project an installed jar belongs to.
async fn identify(client: &reqwest::Client, api: &str, entry: &ModEntry) -> Result<Option<(String, &'static str)>, String> {
    if let Some(project) = entry.project_id.clone().filter(|p| !p.trim().is_empty()) {
        return Ok(Some((project, "record")));
    }
    if entry.sha1.is_empty() {
        return Ok(None);
    }
    let response = client
        .get(format!("{api}/version_file/{}", entry.sha1))
        .query(&[("algorithm", "sha1")])
        .send()
        .await
        .map_err(|e| format!("Could not reach Modrinth: {e}"))?;
    if response.status() == reqwest::StatusCode::NOT_FOUND {
        return Ok(None);
    }
    if !response.status().is_success() {
        return Err(format!("Modrinth answered {} when asked about {}.", response.status(), entry.file_name));
    }
    let version: ModrinthVersion = response.json().await.map_err(|e| format!("Could not read Modrinth's answer: {e}"))?;
    Ok(Some((version.project_id, "hash")).filter(|(p, _)| !p.is_empty()))
}

fn primary_file(version: &ModrinthVersion) -> Option<&crate::ModrinthVersionFile> {
    version.files.iter().find(|f| f.primary).or_else(|| version.files.first())
}

fn choice(version: &ModrinthVersion, entry: &ModEntry) -> Option<VersionChoice> {
    let file = primary_file(version)?;
    let installed = entry.installed_version_id.as_deref() == Some(version.id.as_str())
        || file.hashes.get("sha1").map_or(false, |h| !entry.sha1.is_empty() && h.eq_ignore_ascii_case(&entry.sha1));
    Some(VersionChoice {
        id: version.id.clone(),
        version_number: version.version_number.clone(),
        name: version.name.clone(),
        version_type: version.version_type.clone(),
        date_published: version.date_published.clone(),
        featured: version.featured,
        game_versions: version.game_versions.clone(),
        file_name: file.filename.clone(),
        size: file.size,
        installed,
    })
}

/// The versions of one installed mod Modrinth publishes for this Minecraft
/// version with Fabric.
pub async fn list_versions(app: &AppHandle, profile_id: &str, minecraft: &str, key: &str) -> Result<ModVersions, String> {
    let report = mods_commands::instance_report(app, profile_id, minecraft)?;
    let manifest = read_mod_manifest(&profile_manifest_path(app, profile_id)?)?;
    list_versions_with(&http_client()?, MODRINTH_API_BASE_URL, &report, &manifest, key).await
}

pub async fn list_versions_with(
    client: &reqwest::Client,
    api: &str,
    report: &InstanceModsReport,
    manifest: &[ManagedModRecord],
    key: &str,
) -> Result<ModVersions, String> {
    let minecraft = report.minecraft.as_str();
    let entry = find_entry(report, manifest, key)?;
    let mod_id = mod_id_of(&entry)?;
    let mut out = ModVersions {
        key: entry.key.clone(),
        mod_id,
        name: entry.name.clone(),
        installed_version: entry.version.clone(),
        file_name: entry.file_name.clone(),
        project_id: None,
        identified_by: None,
        minecraft: minecraft.to_string(),
        loader: report.loader.clone(),
        versions: Vec::new(),
        note: None,
    };
    let Some((project, how)) = identify(client, api, &entry).await? else {
        out.note = Some(
            "Modrinth does not know this file, so the launcher cannot offer other versions of it. \
             It may come from another site or a private build."
                .into(),
        );
        return Ok(out);
    };
    let versions = project_versions(client, api, &project, minecraft).await?;
    out.versions = versions.iter().filter_map(|v| choice(v, &entry)).collect();
    if out.versions.is_empty() {
        out.note = Some(format!("Modrinth lists no Fabric version of this mod for Minecraft {minecraft}."));
    }
    out.project_id = Some(project);
    out.identified_by = Some(how.to_string());
    Ok(out)
}

/// A project's versions for one Minecraft version with Fabric, newest first.
async fn project_versions(client: &reqwest::Client, api: &str, project: &str, minecraft: &str) -> Result<Vec<ModrinthVersion>, String> {
    if api == MODRINTH_API_BASE_URL {
        return fetch_project_versions(client, project, minecraft, "fabric").await;
    }
    let query = [
        ("game_versions", serde_json::to_string(&[minecraft]).unwrap_or_default()),
        ("loaders", "[\"fabric\"]".to_string()),
    ];
    let response = client
        .get(format!("{api}/project/{project}/version"))
        .query(&query)
        .send()
        .await
        .map_err(|e| format!("Could not reach Modrinth: {e}"))?;
    if !response.status().is_success() {
        return Err(format!("Modrinth answered {} for the versions of {project}.", response.status()));
    }
    response.json().await.map_err(|e| format!("Could not read Modrinth's versions: {e}"))
}

// ── Staging a change ────────────────────────────────────────────────────────

/// Where a staged file came from, kept so the launcher's record can be
/// written when the change is applied.
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Source {
    pub project_id: String,
    pub project_slug: String,
    pub title: String,
    pub summary: Option<String>,
    pub icon_url: Option<String>,
    pub version_id: String,
    pub version_name: String,
    pub game_version: String,
}

#[derive(Serialize, Deserialize, Debug, Clone, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct FileVersion {
    pub file_name: String,
    pub version: String,
    pub enabled: bool,
}

/// A downloaded, checked version waiting for the player's go-ahead.
#[derive(Serialize, Deserialize, Debug, Clone)]
#[serde(rename_all = "camelCase")]
pub struct StagedChange {
    pub token: String,
    pub mod_id: String,
    pub name: String,
    /// Every file of this mod in the folder now; all of them go to the backups.
    pub from: Vec<FileVersion>,
    pub to_version: String,
    pub to_file: String,
    pub size: u64,
    pub sha512: String,
    /// Whether the new jar will load (a switched-off mod stays off).
    pub enabled: bool,
    pub impact: Impact,
    /// Fabric would refuse the folder afterwards; applying needs a second yes.
    pub blocking: bool,
    pub source: Source,
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

fn sha512_hex(bytes: &[u8]) -> String {
    use sha2::Digest;
    hex(&sha2::Sha512::digest(bytes))
}

fn runtime_jars(instance_dir: &Path) -> Vec<LocalJar> {
    mods_local::read_jar(&instance_dir.join(".breeze").join("runtime.jar")).into_iter().collect()
}

fn of_mod<'a>(jars: &'a [LocalJar], mod_id: &str) -> Vec<&'a LocalJar> {
    jars.iter().filter(|j| j.mod_id() == Some(mod_id)).collect()
}

/// A staging token: lowercase hex only, so it can never name another folder.
fn staging_dir(instance_dir: &Path, token: &str) -> Result<PathBuf, String> {
    if token.is_empty() || token.len() > 32 || !token.chars().all(|c| c.is_ascii_hexdigit() && !c.is_ascii_uppercase()) {
        return Err("That staged change does not exist.".into());
    }
    Ok(instance_dir.join(STAGING_DIR).join(token))
}

/// Removes staged changes older than a day.
fn clear_old_staging(instance_dir: &Path) {
    let Ok(entries) = fs::read_dir(instance_dir.join(STAGING_DIR)) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        let old = entry
            .metadata()
            .and_then(|m| m.modified())
            .ok()
            .and_then(|t| t.elapsed().ok())
            .map_or(false, |age| age > STAGED_MAX_AGE);
        if path.is_dir() && old && path.join(STAGED_FILE).is_file() {
            let _ = fs::remove_dir_all(&path);
        }
    }
}

/// Checks downloaded bytes and puts them in a staging folder of their own,
/// with what applying them would do. The mods folder is not touched.
pub fn stage_bytes(
    instance_dir: &Path,
    mods_dir: &Path,
    env: &Environment,
    mod_id: &str,
    bytes: &[u8],
    file_name: &str,
    source: Source,
) -> Result<StagedChange, String> {
    if file_name.contains('/') || file_name.contains('\\') || file_name.starts_with('.') || !file_name.to_ascii_lowercase().ends_with(".jar") {
        return Err(format!("{file_name} is not a jar file name."));
    }
    let meta = mods_local::read_meta_bytes(bytes).map_err(|p| format!("{file_name}: {}", mods_commands::problem_text(&p)))?;
    if meta.id != mod_id {
        return Err(format!("{file_name} is the mod \"{}\", not \"{mod_id}\", so it was not offered as a version of it.", meta.id));
    }
    let jars = mods_local::scan(mods_dir);
    let current = of_mod(&jars, mod_id);
    if current.is_empty() {
        return Err(format!("{mod_id} is no longer in the mods folder."));
    }
    let sha512 = sha512_hex(bytes);
    if current.iter().any(|j| j.sha512.eq_ignore_ascii_case(&sha512)) {
        return Err(format!("{} {} is the version installed already.", meta.name, meta.version));
    }
    let enabled = current.iter().any(|j| j.enabled);
    let impact = mods_local::impact(&jars, &runtime_jars(instance_dir), &meta, enabled, env);

    clear_old_staging(instance_dir);
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_nanos()).unwrap_or(0);
    let token = format!("{nanos:x}");
    let dir = staging_dir(instance_dir, &token)?;
    fs::create_dir_all(&dir).map_err(|e| format!("Could not create the staging folder: {e}"))?;
    let staged = StagedChange {
        token,
        mod_id: mod_id.to_string(),
        name: meta.name.clone(),
        from: current
            .iter()
            .map(|j| FileVersion {
                file_name: j.file_name.clone(),
                version: j.meta.as_ref().map(|m| m.version.clone()).unwrap_or_default(),
                enabled: j.enabled,
            })
            .collect(),
        to_version: meta.version.clone(),
        to_file: file_name.to_string(),
        size: bytes.len() as u64,
        sha512,
        enabled,
        blocking: impact.blocking(),
        impact,
        source,
    };
    let write = || -> std::io::Result<()> {
        fs::write(dir.join(file_name), bytes)?;
        fs::write(dir.join(STAGED_FILE), serde_json::to_vec_pretty(&staged).unwrap_or_default())
    };
    if let Err(e) = write() {
        let _ = fs::remove_dir_all(&dir);
        return Err(format!("Could not write the download to the staging folder: {e}"));
    }
    Ok(staged)
}

fn environment(report: &InstanceModsReport) -> Environment {
    Environment { minecraft: report.minecraft.clone(), loader: report.loader.clone(), java: report.java }
}

/// Downloads one Modrinth version of an installed mod and stages it.
pub async fn stage(app: &AppHandle, profile_id: &str, minecraft: &str, key: &str, version_id: &str) -> Result<StagedChange, String> {
    let report = mods_commands::instance_report(app, profile_id, minecraft)?;
    let manifest = read_mod_manifest(&profile_manifest_path(app, profile_id)?)?;
    stage_with(&http_client()?, MODRINTH_API_BASE_URL, &report, &manifest, key, version_id).await
}

pub async fn stage_with(
    client: &reqwest::Client,
    api: &str,
    report: &InstanceModsReport,
    manifest: &[ManagedModRecord],
    key: &str,
    version_id: &str,
) -> Result<StagedChange, String> {
    let minecraft = report.minecraft.as_str();
    let version_id = version_id.trim();
    if version_id.is_empty() || !version_id.chars().all(|c| c.is_ascii_alphanumeric()) {
        return Err("That is not a Modrinth version.".into());
    }
    let entry = find_entry(report, manifest, key)?;
    let mod_id = mod_id_of(&entry)?;

    let version: ModrinthVersion = fetch_json(client, &format!("{api}/version/{version_id}")).await?;
    let label = if version.version_number.is_empty() { version.name.clone() } else { version.version_number.clone() };
    if !version.loaders.iter().any(|l| l == "fabric") || !version.game_versions.iter().any(|g| g == minecraft) {
        return Err(format!("Modrinth does not list {label} for Minecraft {minecraft} with Fabric."));
    }
    let file = primary_file(&version).ok_or_else(|| "Modrinth lists no file for that version.".to_string())?.clone();
    let response = client
        .get(&file.url)
        .send()
        .await
        .map_err(|e| format!("Could not download {}: {e}", file.filename))?;
    if !response.status().is_success() {
        return Err(format!("Could not download {}: Modrinth answered {}.", file.filename, response.status()));
    }
    let bytes = response.bytes().await.map_err(|e| format!("The download of {} stopped: {e}", file.filename))?;
    mods_commands::verify_hashes(&bytes, file.hashes.get("sha512").map(String::as_str), file.hashes.get("sha1").map(String::as_str))?;

    let project: Option<crate::ModrinthProjectDetails> = if api == MODRINTH_API_BASE_URL {
        fetch_modrinth_project(client, &version.project_id).await.ok()
    } else {
        fetch_json(client, &format!("{api}/project/{}", version.project_id)).await.ok()
    };
    let record = manifest.iter().find(|r| r.file_name.trim().eq_ignore_ascii_case(&entry.jar_name));
    let source = Source {
        project_id: project.as_ref().map(|p| p.id.clone()).unwrap_or_else(|| version.project_id.clone()),
        project_slug: project.as_ref().map(|p| p.slug.clone()).unwrap_or_default(),
        title: record
            .map(|r| r.title.clone())
            .or_else(|| project.as_ref().map(|p| p.title.clone()))
            .unwrap_or_else(|| entry.name.clone()),
        summary: record.and_then(|r| r.summary.clone()).or_else(|| project.as_ref().map(|p| p.description.clone())),
        icon_url: record.and_then(|r| r.icon_url.clone()).or_else(|| project.as_ref().and_then(|p| p.icon_url.clone())),
        version_id: version.id.clone(),
        version_name: label,
        game_version: minecraft.to_string(),
    };
    let instance_dir = PathBuf::from(&report.instance_dir);
    let mods_dir = PathBuf::from(&report.mods_dir);
    stage_bytes(&instance_dir, &mods_dir, &environment(report), &mod_id, &bytes, &sanitize_filename(&file.filename), source)
}

// ── Applying, discarding, going back ────────────────────────────────────────

/// What the backup folder of a change records, so it can be undone.
#[derive(Serialize, Deserialize, Debug, Clone, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct ChangeRecord {
    /// "version" (a version was chosen) or "rollback" (one was put back).
    pub kind: String,
    pub mod_id: String,
    pub name: String,
    pub from: Vec<FileVersion>,
    pub to_version: String,
    pub to_file: String,
    /// The launcher's records of the files that were moved here.
    pub records: Vec<ManagedModRecord>,
    pub at: u64,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct AppliedChange {
    pub file_name: String,
    pub enabled: bool,
    pub replaced: Vec<MovedFile>,
    /// The backup folder that holds what was replaced.
    pub backup_id: Option<String>,
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn describe(impact: &Impact) -> String {
    let mut lines: Vec<String> = Vec::new();
    for issue in &impact.own {
        lines.push(format!("this version {}", mods_local::issue_text(issue)));
    }
    for other in &impact.breaks {
        lines.push(format!("{} {}", other.name, mods_local::issue_text(&other.issue)));
    }
    lines.join("; ")
}

/// The records of jars that left the folder, removed from the manifest and returned.
fn take_records(manifest: &mut Vec<ManagedModRecord>, jar_names: &[String]) -> Vec<ManagedModRecord> {
    let matches = |r: &ManagedModRecord| jar_names.iter().any(|n| r.file_name.trim().eq_ignore_ascii_case(n));
    let taken: Vec<ManagedModRecord> = manifest.iter().filter(|r| matches(r)).cloned().collect();
    manifest.retain(|r| !matches(r));
    taken
}

fn jar_names(replaced: &[(String, PathBuf)]) -> Vec<String> {
    replaced
        .iter()
        .map(|(name, _)| name.strip_suffix(mods_local::DISABLED_SUFFIX).unwrap_or(name).to_string())
        .collect()
}

fn backup_id_of(replaced: &[(String, PathBuf)]) -> Option<String> {
    replaced
        .first()
        .and_then(|(_, p)| p.parent())
        .and_then(|d| d.file_name())
        .map(|n| n.to_string_lossy().to_string())
}

fn write_change(instance_dir: &Path, backup_id: &Option<String>, change: &ChangeRecord) {
    if let Some(id) = backup_id {
        let path = instance_dir.join(mods_local::BACKUP_DIR).join(id).join(CHANGE_FILE);
        let _ = fs::write(path, serde_json::to_vec_pretty(change).unwrap_or_default());
    }
}

/// Puts a staged version in place. With `accept_issues`, also when the folder
/// would then hold mods Fabric refuses (the player said yes to that).
pub fn apply_staged(
    instance_dir: &Path,
    mods_dir: &Path,
    manifest_path: &Path,
    env: &Environment,
    token: &str,
    accept_issues: bool,
) -> Result<AppliedChange, String> {
    let dir = staging_dir(instance_dir, token)?;
    let staged: StagedChange = fs::read(dir.join(STAGED_FILE))
        .ok()
        .and_then(|b| serde_json::from_slice(&b).ok())
        .ok_or_else(|| "That staged change does not exist any more. Choose the version again.".to_string())?;
    let bytes = fs::read(dir.join(&staged.to_file)).map_err(|e| format!("Could not read the staged download: {e}"))?;
    if !sha512_hex(&bytes).eq_ignore_ascii_case(&staged.sha512) {
        return Err("The staged download changed on disk. Choose the version again.".into());
    }
    let meta = mods_local::read_meta_bytes(&bytes).map_err(|p| mods_commands::problem_text(&p))?;

    // The folder may have changed since the change was staged.
    let jars = mods_local::scan(mods_dir);
    let current = of_mod(&jars, &staged.mod_id);
    if current.is_empty() {
        return Err(format!("{} is no longer in the mods folder.", staged.name));
    }
    let enabled = current.iter().any(|j| j.enabled);
    let impact = mods_local::impact(&jars, &runtime_jars(instance_dir), &meta, enabled, env);
    if impact.blocking() && !accept_issues {
        return Err(format!(
            "After this change Fabric would refuse to start: {}. Nothing was changed.",
            describe(&impact)
        ));
    }

    let mut manifest = read_mod_manifest(manifest_path)?;
    let placed = mods_commands::place_jar(instance_dir, mods_dir, &bytes, &staged.to_file, None, "replaced")?;
    let Placed::Installed { file_name, replaced, enabled, .. } = placed else {
        return Err("The staged version was not put in place.".into());
    };
    let records = take_records(&mut manifest, &jar_names(&replaced));
    if !staged.source.project_id.is_empty() {
        let before = records.first();
        let mut record = ManagedModRecord {
            project_id: staged.source.project_id.clone(),
            project_slug: staged.source.project_slug.clone(),
            title: staged.source.title.clone(),
            summary: staged.source.summary.clone(),
            icon_url: staged.source.icon_url.clone(),
            game_version: staged.source.game_version.clone(),
            loader: before.map(|r| r.loader.clone()).unwrap_or_else(|| "fabric".into()),
            installed_version_id: staged.source.version_id.clone(),
            installed_version_name: staged.source.version_name.clone(),
            file_name: file_name.clone(),
            enabled,
            canonical_id: staged.source.project_id.clone(),
        };
        record.enabled = enabled;
        upsert_managed_mod(&mut manifest, record);
    }
    write_mod_manifest(manifest_path, &manifest)?;

    let backup_id = backup_id_of(&replaced);
    write_change(
        instance_dir,
        &backup_id,
        &ChangeRecord {
            kind: "version".into(),
            mod_id: staged.mod_id.clone(),
            name: staged.name.clone(),
            from: staged.from.clone(),
            to_version: staged.to_version.clone(),
            to_file: file_name.clone(),
            records,
            at: now_secs(),
        },
    );
    let _ = fs::remove_dir_all(&dir);
    Ok(AppliedChange {
        file_name,
        enabled,
        replaced: replaced
            .into_iter()
            .map(|(name, path)| MovedFile { file_name: name, backup_path: path.to_string_lossy().to_string() })
            .collect(),
        backup_id,
    })
}

pub fn discard_staged(instance_dir: &Path, token: &str) -> Result<(), String> {
    let dir = staging_dir(instance_dir, token)?;
    if dir.is_dir() {
        fs::remove_dir_all(&dir).map_err(|e| format!("Could not remove the staged download: {e}"))?;
    }
    Ok(())
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct BackupFile {
    pub file_name: String,
    pub mod_id: Option<String>,
    pub name: String,
    pub version: String,
    pub enabled: bool,
    pub size: u64,
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct Backup {
    pub id: String,
    /// Seconds since 1970 when the files were moved here.
    pub at: u64,
    /// replaced, removed, rollback.
    pub reason: String,
    pub files: Vec<BackupFile>,
    pub change: Option<ChangeRecord>,
}

/// Every backup folder of the instance, newest first.
pub fn list_backups(instance_dir: &Path) -> Vec<Backup> {
    let root = instance_dir.join(mods_local::BACKUP_DIR);
    let mut out: Vec<Backup> = fs::read_dir(&root)
        .map(|entries| {
            entries
                .flatten()
                .filter(|e| e.path().is_dir())
                .filter_map(|e| {
                    let id = e.file_name().to_string_lossy().to_string();
                    let (at, reason) = id.split_once('-')?;
                    let at: u64 = at.parse().ok()?;
                    // "replaced-2" is the second folder of that second.
                    let reason = match reason.rsplit_once('-') {
                        Some((r, n)) if !n.is_empty() && n.chars().all(|c| c.is_ascii_digit()) => r,
                        _ => reason,
                    };
                    let files: Vec<BackupFile> = mods_local::scan(&e.path())
                        .into_iter()
                        .map(|j| BackupFile {
                            name: j.meta.as_ref().map(|m| m.name.clone()).unwrap_or_else(|| j.jar_name.clone()),
                            version: j.meta.as_ref().map(|m| m.version.clone()).unwrap_or_default(),
                            mod_id: j.meta.as_ref().map(|m| m.id.clone()),
                            enabled: j.enabled,
                            size: j.size,
                            file_name: j.file_name,
                        })
                        .collect();
                    if files.is_empty() {
                        return None;
                    }
                    let change = fs::read(e.path().join(CHANGE_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok());
                    Some(Backup { id: id.clone(), at, reason: reason.to_string(), files, change })
                })
                .collect()
        })
        .unwrap_or_default();
    out.sort_by(|a, b| b.at.cmp(&a.at).then(b.id.cmp(&a.id)));
    out
}

fn plain_name(name: &str) -> bool {
    !name.is_empty() && !name.contains('/') && !name.contains('\\') && !name.starts_with('.') && name != ".."
}

/// Puts one file from a backup back into the mods folder. Whatever is there
/// now for the same mod id goes to a new backup first, so a rollback can be
/// undone the same way.
pub fn restore_backup(
    instance_dir: &Path,
    mods_dir: &Path,
    manifest_path: &Path,
    backup_id: &str,
    file_name: &str,
) -> Result<AppliedChange, String> {
    if !plain_name(backup_id) || !plain_name(file_name) {
        return Err("That backup does not exist.".into());
    }
    let folder = instance_dir.join(mods_local::BACKUP_DIR).join(backup_id);
    let source = folder.join(file_name);
    if !source.is_file() {
        return Err(format!("{file_name} is no longer in that backup."));
    }
    let bytes = fs::read(&source).map_err(|e| format!("Could not read {file_name}: {e}"))?;
    let meta = mods_local::read_meta_bytes(&bytes)
        .map_err(|p| format!("{file_name} is not put back: {}", mods_commands::problem_text(&p)))?;
    let was_off = file_name.ends_with(mods_local::DISABLED_SUFFIX);
    let jar_name = file_name.strip_suffix(mods_local::DISABLED_SUFFIX).unwrap_or(file_name).to_string();
    let earlier: Option<ChangeRecord> = fs::read(folder.join(CHANGE_FILE)).ok().and_then(|b| serde_json::from_slice(&b).ok());
    let from: Vec<FileVersion> = of_mod(&mods_local::scan(mods_dir), &meta.id)
        .iter()
        .map(|j| FileVersion {
            file_name: j.file_name.clone(),
            version: j.meta.as_ref().map(|m| m.version.clone()).unwrap_or_default(),
            enabled: j.enabled,
        })
        .collect();

    let mut manifest = read_mod_manifest(manifest_path)?;
    let placed = mods_commands::place_jar(instance_dir, mods_dir, &bytes, &jar_name, None, "rollback")?;
    let Placed::Installed { file_name: placed_name, replaced, mut enabled, .. } = placed else {
        return Err(format!("{file_name} was not put back."));
    };
    // With nothing of this mod left to take the switch from, the file keeps the
    // one it had when it was backed up.
    if replaced.is_empty() && was_off && enabled {
        mods_local::set_enabled(mods_dir, &placed_name, false)?;
        enabled = false;
    }
    let _ = fs::remove_file(&source);
    if mods_local::scan(&folder).is_empty() {
        let _ = fs::remove_dir_all(&folder);
    }

    let records = take_records(&mut manifest, &jar_names(&replaced));
    for mut record in earlier
        .map(|c| c.records)
        .unwrap_or_default()
        .into_iter()
        .filter(|r| r.file_name.trim().eq_ignore_ascii_case(&jar_name))
    {
        record.enabled = enabled;
        upsert_managed_mod(&mut manifest, record);
    }
    write_mod_manifest(manifest_path, &manifest)?;

    let backup_id = backup_id_of(&replaced);
    write_change(
        instance_dir,
        &backup_id,
        &ChangeRecord {
            kind: "rollback".into(),
            mod_id: meta.id.clone(),
            name: meta.name.clone(),
            from,
            to_version: meta.version.clone(),
            to_file: placed_name.clone(),
            records,
            at: now_secs(),
        },
    );
    Ok(AppliedChange {
        file_name: placed_name,
        enabled,
        replaced: replaced
            .into_iter()
            .map(|(name, path)| MovedFile { file_name: name, backup_path: path.to_string_lossy().to_string() })
            .collect(),
        backup_id,
    })
}

// ── The app's view ──────────────────────────────────────────────────────────

pub struct Paths {
    pub instance_dir: PathBuf,
    pub mods_dir: PathBuf,
    pub manifest: PathBuf,
}

pub fn paths(app: &AppHandle, profile_id: &str) -> Result<Paths, String> {
    Ok(Paths {
        instance_dir: profile_root_dir(app, profile_id)?,
        mods_dir: profile_mods_dir(app, profile_id)?,
        manifest: profile_manifest_path(app, profile_id)?,
    })
}

pub fn environment_for(minecraft: &str) -> Environment {
    Environment {
        minecraft: minecraft.to_string(),
        loader: mods_commands::installed_loader_version(minecraft).unwrap_or_default(),
        java: mods_commands::java_major_for(minecraft),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Write};

    fn jar(json: &str) -> Vec<u8> {
        let mut buf = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut buf);
            zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
            zip.write_all(json.as_bytes()).unwrap();
            zip.finish().unwrap();
        }
        buf.into_inner()
    }

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!(
            "breeze-mods-versions-{name}-{}",
            SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos()
        ));
        fs::create_dir_all(d.join("mods")).unwrap();
        d
    }

    fn env() -> Environment {
        Environment { minecraft: "1.21.11".into(), loader: "0.19.5".into(), java: Some(21) }
    }

    fn record(file: &str, version: &str) -> ManagedModRecord {
        ManagedModRecord {
            project_id: "AANobbMI".into(),
            canonical_id: "AANobbMI".into(),
            project_slug: "sodium".into(),
            title: "Sodium".into(),
            game_version: "1.21.11".into(),
            loader: "fabric".into(),
            installed_version_id: format!("v{version}"),
            installed_version_name: version.into(),
            file_name: file.into(),
            enabled: true,
            ..Default::default()
        }
    }

    fn source(version: &str) -> Source {
        Source {
            project_id: "AANobbMI".into(),
            project_slug: "sodium".into(),
            title: "Sodium".into(),
            version_id: format!("v{version}"),
            version_name: version.into(),
            game_version: "1.21.11".into(),
            ..Default::default()
        }
    }

    #[test]
    fn staging_changes_nothing_and_applying_keeps_the_old_version_to_roll_back_to() {
        let inst = temp("apply");
        let mods = inst.join("mods");
        let manifest = inst.join("mods-state.json");
        fs::write(mods.join("sodium-0.6.13.jar"), jar(r#"{"id":"sodium","version":"0.6.13","name":"Sodium"}"#)).unwrap();
        write_mod_manifest(&manifest, &[record("sodium-0.6.13.jar", "0.6.13")]).unwrap();

        let staged = stage_bytes(&inst, &mods, &env(), "sodium", &jar(r#"{"id":"sodium","version":"0.7.0","name":"Sodium"}"#), "sodium-0.7.0.jar", source("0.7.0")).unwrap();
        assert_eq!(staged.from[0].version, "0.6.13");
        assert_eq!(staged.to_version, "0.7.0");
        assert!(!staged.blocking);
        let names: Vec<_> = mods_local::scan(&mods).into_iter().map(|j| j.file_name).collect();
        assert_eq!(names, vec!["sodium-0.6.13.jar"], "staging leaves the mods folder alone");

        let applied = apply_staged(&inst, &mods, &manifest, &env(), &staged.token, false).unwrap();
        assert_eq!(applied.file_name, "sodium-0.7.0.jar");
        let names: Vec<_> = mods_local::scan(&mods).into_iter().map(|j| j.file_name).collect();
        assert_eq!(names, vec!["sodium-0.7.0.jar"], "exactly one copy, no (2)");
        let records = read_mod_manifest(&manifest).unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].installed_version_name, "0.7.0");
        assert_eq!(records[0].file_name, "sodium-0.7.0.jar");
        assert!(!staging_dir(&inst, &staged.token).unwrap().exists(), "the staged copy is gone once applied");

        let backups = list_backups(&inst);
        assert_eq!(backups.len(), 1);
        let change = backups[0].change.as_ref().unwrap();
        assert_eq!(change.kind, "version");
        assert_eq!(change.records[0].installed_version_name, "0.6.13");

        let back = restore_backup(&inst, &mods, &manifest, &backups[0].id, "sodium-0.6.13.jar").unwrap();
        assert_eq!(back.file_name, "sodium-0.6.13.jar");
        let names: Vec<_> = mods_local::scan(&mods).into_iter().map(|j| j.file_name).collect();
        assert_eq!(names, vec!["sodium-0.6.13.jar"]);
        let records = read_mod_manifest(&manifest).unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].installed_version_name, "0.6.13", "the launcher's record comes back with the file");
        let backups = list_backups(&inst);
        assert_eq!(backups.len(), 1, "the emptied backup is gone, the rollback has its own");
        assert_eq!(backups[0].change.as_ref().unwrap().kind, "rollback");
        assert_eq!(backups[0].files[0].version, "0.7.0");
    }

    #[test]
    fn a_change_that_breaks_another_mod_needs_a_second_yes() {
        let inst = temp("block");
        let mods = inst.join("mods");
        let manifest = inst.join("mods-state.json");
        fs::write(mods.join("sodium.jar"), jar(r#"{"id":"sodium","version":"0.6.13"}"#)).unwrap();
        fs::write(mods.join("rso.jar"), jar(r#"{"id":"reeses-sodium-options","version":"1.8.4","name":"Reese's Sodium Options","depends":{"sodium":"<0.7.0"}}"#)).unwrap();
        let staged = stage_bytes(&inst, &mods, &env(), "sodium", &jar(r#"{"id":"sodium","version":"0.7.0"}"#), "sodium-0.7.0.jar", source("0.7.0")).unwrap();
        assert!(staged.blocking);
        assert_eq!(staged.impact.breaks[0].name, "Reese's Sodium Options");
        let refused = apply_staged(&inst, &mods, &manifest, &env(), &staged.token, false).unwrap_err();
        assert!(refused.contains("Nothing was changed"), "{refused}");
        assert!(mods.join("sodium.jar").is_file());
        assert!(apply_staged(&inst, &mods, &manifest, &env(), &staged.token, true).is_ok());
        assert!(mods.join("sodium-0.7.0.jar").is_file() && !mods.join("sodium.jar").exists());
    }

    #[test]
    fn the_wrong_mod_the_same_file_or_a_bad_token_is_refused() {
        let inst = temp("refuse");
        let mods = inst.join("mods");
        let current = jar(r#"{"id":"zoomify","version":"2.14"}"#);
        fs::write(mods.join("zoomify.jar"), &current).unwrap();
        let err = stage_bytes(&inst, &mods, &env(), "zoomify", &jar(r#"{"id":"sodium","version":"1"}"#), "zoomify-2.15.jar", Source::default()).unwrap_err();
        assert!(err.contains("not \"zoomify\""), "{err}");
        assert!(stage_bytes(&inst, &mods, &env(), "zoomify", &current, "zoomify-2.14.jar", Source::default()).is_err());
        assert!(stage_bytes(&inst, &mods, &env(), "zoomify", b"<html>", "zoomify-2.15.jar", Source::default()).is_err());
        assert!(stage_bytes(&inst, &mods, &env(), "zoomify", &jar(r#"{"id":"zoomify","version":"2.15"}"#), "../x.jar", Source::default()).is_err());
        assert!(apply_staged(&inst, &mods, &inst.join("m.json"), &env(), "../../etc", false).is_err());
        assert!(restore_backup(&inst, &mods, &inst.join("m.json"), "..", "zoomify.jar").is_err());
        assert!(discard_staged(&inst, "ABC/..").is_err());
    }

    // A stand-in for api.modrinth.com and its CDN runs on 127.0.0.1 in the
    // test below, answering with the shapes Modrinth's v2 API documents:
    // Modrinth itself cannot be reached from the test machine.

    fn sha1_hex(bytes: &[u8]) -> String {
        use sha1::Digest;
        hex(&sha1::Sha1::digest(bytes))
    }

    fn version_json(base: &str, id: &str, number: &str, file: &str, bytes: &[u8], games: &[&str]) -> serde_json::Value {
        serde_json::json!({
            "id": id, "project_id": "AANobbMI", "author_id": "x", "featured": false,
            "name": format!("Sodium {number}"), "version_number": number, "changelog": null,
            "date_published": "2026-09-01T00:00:00Z", "downloads": 1, "version_type": "release", "status": "listed",
            "files": [{ "hashes": { "sha512": sha512_hex(bytes), "sha1": sha1_hex(bytes) }, "url": format!("{base}/files/{file}"),
                        "filename": file, "primary": true, "size": bytes.len(), "file_type": null }],
            "dependencies": [], "game_versions": games, "loaders": ["fabric", "quilt"]
        })
    }

    #[test]
    fn a_jar_the_launcher_did_not_install_is_found_on_modrinth_by_its_hash_and_changed_safely() {
        let inst = temp("modrinth");
        let mods = inst.join("mods");
        let manifest = inst.join("mods-state.json");
        let old = jar(r#"{"id":"sodium","version":"0.6.13+mc1.21.11","name":"Sodium"}"#);
        let new = jar(r#"{"id":"sodium","version":"0.7.0+mc1.21.11","name":"Sodium","depends":{"minecraft":"~1.21.11"}}"#);
        let tampered = jar(r#"{"id":"sodium","version":"0.7.1"}"#);
        fs::write(mods.join("sodium-fabric-0.6.13+mc1.21.11.jar"), &old).unwrap();
        fs::write(mods.join("rso.jar"), jar(r#"{"id":"reeses-sodium-options","version":"1.8.4","name":"Reese's Sodium Options","depends":{"sodium":"<0.7.0"}}"#)).unwrap();
        fs::write(mods.join("modmenu-15.0.0.jar"), jar(r#"{"id":"modmenu","version":"15.0.0","name":"Mod Menu"}"#)).unwrap();
        fs::write(mods.join("modmenu-15.0.0 (1).jar"), jar(r#"{"id":"modmenu","version":"15.0.0","name":"Mod Menu"}"#)).unwrap();

        // The routes need the base URL, and the base URL comes from the bound port.
        let routes = std::sync::Arc::new(std::sync::Mutex::new(Vec::<(String, u16, Vec<u8>)>::new()));
        let shared = routes.clone();
        let listener_base = {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let base = format!("http://{}", listener.local_addr().unwrap());
            std::thread::spawn(move || {
                use std::io::{BufRead, BufReader};
                for stream in listener.incoming().flatten() {
                    let mut reader = BufReader::new(stream.try_clone().unwrap());
                    let mut first = String::new();
                    let _ = reader.read_line(&mut first);
                    loop {
                        let mut line = String::new();
                        if reader.read_line(&mut line).unwrap_or(0) == 0 || line == "\r\n" {
                            break;
                        }
                    }
                    let path = first.split_whitespace().nth(1).unwrap_or("").split('?').next().unwrap_or("").to_string();
                    let (status, body) = shared
                        .lock()
                        .unwrap()
                        .iter()
                        .find(|(p, _, _)| *p == path)
                        .map(|(_, s, b)| (*s, b.clone()))
                        .unwrap_or((404, br#"{"error":"not_found"}"#.to_vec()));
                    let mut out = stream;
                    let _ = write!(out, "HTTP/1.1 {status} X\r\nContent-Length: {}\r\nConnection: close\r\n\r\n", body.len());
                    let _ = out.write_all(&body);
                }
            });
            base
        };
        let base = listener_base;
        let v_old = version_json(&base, "OLDver01", "mc1.21.11-0.6.13-fabric", "sodium-fabric-0.6.13+mc1.21.11.jar", &old, &["1.21.11"]);
        let v_new = version_json(&base, "NEWver02", "mc1.21.11-0.7.0-fabric", "sodium-fabric-0.7.0+mc1.21.11.jar", &new, &["1.21.11"]);
        let mut v_bad = version_json(&base, "BADver03", "mc1.21.11-0.7.1-fabric", "sodium-fabric-0.7.1+mc1.21.11.jar", &new, &["1.21.11"]);
        v_bad["files"][0]["url"] = serde_json::json!(format!("{base}/files/tampered.jar"));
        let v_other = version_json(&base, "OTHver04", "mc1.21.4-0.6.13-fabric", "sodium-fabric-0.6.13+mc1.21.4.jar", &old, &["1.21.4"]);
        {
            let mut r = routes.lock().unwrap();
            let j = |v: &serde_json::Value| serde_json::to_vec(v).unwrap();
            r.push((format!("/version_file/{}", sha1_hex(&old)), 200, j(&v_old)));
            r.push(("/project/AANobbMI/version".into(), 200, j(&serde_json::json!([v_bad, v_new, v_old]))));
            r.push(("/version/OLDver01".into(), 200, j(&v_old)));
            r.push(("/version/NEWver02".into(), 200, j(&v_new)));
            r.push(("/version/BADver03".into(), 200, j(&v_bad)));
            r.push(("/version/OTHver04".into(), 200, j(&v_other)));
            r.push(("/project/AANobbMI".into(), 200, j(&serde_json::json!({"id":"AANobbMI","slug":"sodium","title":"Sodium","description":"Fast","icon_url":null}))));
            r.push(("/files/sodium-fabric-0.7.0+mc1.21.11.jar".into(), 200, new.clone()));
            r.push(("/files/tampered.jar".into(), 200, tampered.clone()));
        }
        let client = reqwest::Client::builder().no_proxy().build().unwrap();
        let report = || crate::mods_commands::instance_report_at(&inst, &mods, &manifest, &env()).unwrap();
        let records = || read_mod_manifest(&manifest).unwrap();

        // Every jar is listed and the two Mod Menus are one duplicate group.
        let r = report();
        assert_eq!(r.mods.len(), 4);
        assert_eq!(r.duplicates.len(), 1);
        assert_eq!(r.duplicates[0].mod_id, "modmenu");

        tauri::async_runtime::block_on(async {
            let listed = list_versions_with(&client, &base, &r, &records(), "file:sodium-fabric-0.6.13+mc1.21.11.jar").await.unwrap();
            assert_eq!(listed.identified_by.as_deref(), Some("hash"));
            assert_eq!(listed.project_id.as_deref(), Some("AANobbMI"));
            assert_eq!(listed.versions.len(), 3);
            assert!(listed.versions.iter().find(|v| v.id == "OLDver01").unwrap().installed);
            assert!(!listed.versions.iter().find(|v| v.id == "NEWver02").unwrap().installed);

            // Not on Modrinth: said so, nothing invented.
            let unknown = list_versions_with(&client, &base, &r, &records(), "file:rso.jar").await.unwrap();
            assert!(unknown.versions.is_empty() && unknown.note.is_some());

            // A download that does not match Modrinth's hash, or a version for another
            // Minecraft, is refused before anything is staged.
            let bad = stage_with(&client, &base, &r, &records(), "file:sodium-fabric-0.6.13+mc1.21.11.jar", "BADver03").await.unwrap_err();
            assert!(bad.contains("SHA-512"), "{bad}");
            let other = stage_with(&client, &base, &r, &records(), "file:sodium-fabric-0.6.13+mc1.21.11.jar", "OTHver04").await.unwrap_err();
            assert!(other.contains("1.21.11"), "{other}");
            assert!(fs::read_dir(inst.join(STAGING_DIR)).map_or(true, |d| d.count() == 0));

            let staged = stage_with(&client, &base, &r, &records(), "file:sodium-fabric-0.6.13+mc1.21.11.jar", "NEWver02").await.unwrap();
            assert_eq!(staged.from[0].version, "0.6.13+mc1.21.11");
            assert_eq!(staged.to_version, "0.7.0+mc1.21.11");
            assert!(staged.blocking, "Reese's Sodium Options needs Sodium below 0.7");
            assert_eq!(staged.impact.breaks[0].name, "Reese's Sodium Options");
            assert!(apply_staged(&inst, &mods, &manifest, &env(), &staged.token, false).is_err());
            assert!(mods.join("sodium-fabric-0.6.13+mc1.21.11.jar").is_file(), "refused: nothing moved");

            let applied = apply_staged(&inst, &mods, &manifest, &env(), &staged.token, true).unwrap();
            assert_eq!(applied.file_name, "sodium-fabric-0.7.0+mc1.21.11.jar");
            let rec = records();
            assert_eq!(rec.len(), 1, "now the launcher knows where Sodium came from");
            assert_eq!(rec[0].project_id, "AANobbMI");
            assert_eq!(rec[0].installed_version_id, "NEWver02");

            // Its versions now come from the record, and the new one is installed.
            let after = list_versions_with(&client, &base, &report(), &rec, "AANobbMI").await.unwrap();
            assert_eq!(after.identified_by.as_deref(), Some("record"));
            assert!(after.versions.iter().find(|v| v.id == "NEWver02").unwrap().installed);

            // And back.
            let backup = list_backups(&inst).into_iter().find(|b| b.reason == "replaced").unwrap();
            restore_backup(&inst, &mods, &manifest, &backup.id, "sodium-fabric-0.6.13+mc1.21.11.jar").unwrap();
            let r = report();
            let sodium: Vec<_> = r.mods.iter().filter(|m| m.mod_id.as_deref() == Some("sodium")).collect();
            assert_eq!(sodium.len(), 1);
            assert_eq!(sodium[0].version, "0.6.13+mc1.21.11");
            assert!(r.mods.iter().find(|m| m.mod_id.as_deref() == Some("reeses-sodium-options")).unwrap().issues.is_empty());
        });
    }

    #[test]
    fn a_switched_off_mod_stays_off_through_a_change_and_discard_removes_the_download() {
        let inst = temp("off");
        let mods = inst.join("mods");
        let manifest = inst.join("mods-state.json");
        fs::write(mods.join("zoomify-2.14.jar.disabled"), jar(r#"{"id":"zoomify","version":"2.14"}"#)).unwrap();
        let one = stage_bytes(&inst, &mods, &env(), "zoomify", &jar(r#"{"id":"zoomify","version":"2.15"}"#), "zoomify-2.15.jar", Source::default()).unwrap();
        assert!(!one.enabled);
        discard_staged(&inst, &one.token).unwrap();
        assert!(!staging_dir(&inst, &one.token).unwrap().exists());
        assert!(apply_staged(&inst, &mods, &manifest, &env(), &one.token, false).is_err());

        let two = stage_bytes(&inst, &mods, &env(), "zoomify", &jar(r#"{"id":"zoomify","version":"2.15"}"#), "zoomify-2.15.jar", Source::default()).unwrap();
        let applied = apply_staged(&inst, &mods, &manifest, &env(), &two.token, false).unwrap();
        assert!(!applied.enabled);
        assert!(mods.join("zoomify-2.15.jar.disabled").is_file());
        assert!(read_mod_manifest(&manifest).unwrap().is_empty(), "no Modrinth source: the jar is listed from the folder");
    }
}
