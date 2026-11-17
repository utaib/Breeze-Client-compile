//! The launcher's side of an instance's real mods folder: what the Mods page
//! lists, removing a duplicate, switching a mod off in a way Fabric honours,
//! opening the instance's folders, and putting a downloaded jar in place
//! without ever leaving two copies of one mod behind.
//!
//! The mod files themselves are the truth. `mods-state.json` (the launcher's
//! record of what it installed) adds what only the launcher knows, such as the
//! Modrinth project a jar came from, but a jar the launcher has no record of is
//! still listed, checked and managed.

use crate::loaders::Loader;
use crate::mods_local::{self, DuplicateGroup, Environment, Issue, LocalJar, SemVer};
use crate::{
    breeze_home_dir, profile_manifest_path, profile_mods_dir, profile_root_dir, read_mod_manifest,
    write_mod_manifest, ManagedModRecord, BREEZE_MOD_ID, LEGACY_BREEZE_MOD_NAME,
};
use serde::Serialize;
use serde_json::Value;
use std::{
    collections::HashMap,
    fs,
    path::{Path, PathBuf},
    time::SystemTime,
};
use tauri::AppHandle;

/// Where the launcher stages a download before it is checked and moved in.
pub const STAGING_DIR: &str = ".breeze/staging";
/// What Breeze 2.12 and later write at startup: the mods Fabric really loaded.
pub const RUNTIME_REPORT: &str = ".breeze/runtime-mods.json";
/// The key the Mods page uses for a jar the launcher has no record of.
pub const FILE_KEY_PREFIX: &str = "file:";

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct ModEntry {
    /// What the Mods page passes back to act on this mod: the record's id for
    /// a mod the launcher installed, `file:<jar>` for any other jar.
    pub key: String,
    pub file_name: String,
    pub jar_name: String,
    pub enabled: bool,
    pub size: u64,
    pub sha1: String,
    pub sha512: String,
    pub mod_id: Option<String>,
    pub name: String,
    pub version: String,
    pub description: String,
    /// Installed by the launcher (it has a record of where the jar came from).
    pub managed: bool,
    pub project_id: Option<String>,
    pub icon_url: Option<String>,
    pub installed_version_id: Option<String>,
    /// The Breeze mod itself, which the launcher installs and updates.
    pub breeze: bool,
    /// Why the jar's metadata could not be read, when it could not.
    pub problem: Option<String>,
    pub issues: Vec<Issue>,
    pub duplicate: bool,
    /// Whether Fabric loaded it the last time the game started with Breeze
    /// 2.12 or later; None when that is not known.
    pub loaded_last_run: Option<bool>,
}

#[derive(Serialize, Clone, Debug)]
#[serde(rename_all = "camelCase")]
pub struct InstanceModsReport {
    pub instance_dir: String,
    pub mods_dir: String,
    pub minecraft: String,
    /// The loader's build this instance runs (Fabric Loader's version, Forge's,
    /// ...), empty when it has not been set up yet.
    pub loader: String,
    /// Which loader that is: fabric, quilt, forge, neoforge or vanilla.
    pub loader_kind: String,
    pub java: Option<u32>,
    pub mods: Vec<ModEntry>,
    pub duplicates: Vec<DuplicateGroup>,
    /// Records of mods the launcher installed whose jar is gone.
    pub missing_files: Vec<String>,
    /// What Breeze reported from inside the game the last time it started.
    pub last_run: Option<Value>,
    /// Fabric's own explanation, from the last log, when the last start failed
    /// on the mod set (missing dependency, duplicate, wrong version).
    pub last_launch_problem: Option<String>,
}

pub fn problem_text(p: &mods_local::MetaProblem) -> String {
    match p {
        mods_local::MetaProblem::NotAJar(d) => format!("Not a valid jar: {d}."),
        mods_local::MetaProblem::NotFabric(d) => format!("This is {d}, which Fabric does not load."),
        mods_local::MetaProblem::BadMetadata(d) => format!("Its Fabric metadata is broken: {d}."),
        mods_local::MetaProblem::Unreadable(d) => format!("The file cannot be read: {d}."),
    }
}

/// The newest Fabric Loader set up for this Minecraft version, from the
/// launcher's own versions folder.
pub fn installed_loader_version(minecraft: &str) -> Option<String> {
    let dir = breeze_home_dir().ok()?.join("versions");
    let suffix = format!("-{minecraft}");
    let mut best: Option<(SemVer, String)> = None;
    for entry in fs::read_dir(dir).ok()?.flatten() {
        let name = entry.file_name().to_string_lossy().to_string();
        let Some(rest) = name.strip_prefix("fabric-loader-") else { continue };
        let Some(loader) = rest.strip_suffix(&suffix) else { continue };
        let Some(v) = SemVer::parse(loader) else { continue };
        if best.as_ref().map_or(true, |(b, _)| v.cmp(b).is_gt()) {
            best = Some((v, loader.to_string()));
        }
    }
    best.map(|(_, s)| s)
}

/// The Java major version Mojang lists for this Minecraft version.
pub fn java_major_for(minecraft: &str) -> Option<u32> {
    let path = breeze_home_dir().ok()?.join("versions").join(minecraft).join(format!("{minecraft}.json"));
    let value: Value = serde_json::from_str(&fs::read_to_string(path).ok()?).ok()?;
    value.pointer("/javaVersion/majorVersion")?.as_u64().map(|v| v as u32)
}

fn modified(path: &Path) -> Option<SystemTime> {
    fs::metadata(path).and_then(|m| m.modified()).ok()
}

/// Fabric's explanation of a refused mod set, from the newest log, when the
/// log is newer than Breeze's last report from a game that did start.
pub fn last_launch_problem(instance_dir: &Path) -> Option<String> {
    let log = instance_dir.join("logs").join("latest.log");
    let text = fs::read_to_string(&log).ok()?;
    if let (Some(log_at), Some(run_at)) = (modified(&log), modified(&instance_dir.join(RUNTIME_REPORT))) {
        if run_at >= log_at {
            return None;
        }
    }
    let markers = [
        "Incompatible mods found!",
        "Incompatible mod set!",
        "Mod resolution failed",
        "Mod resolution encountered an incompatible mod set!",
        "Found duplicate mods",
        "duplicate mod",
    ];
    let lines: Vec<&str> = text.lines().collect();
    // Fabric first logs its solver's view ("Mod resolution failed",
    // "Immediate reason: [HARD_DEP_NO_CANDIDATE ...]"), then the explanation
    // for people under "Incompatible mods found!". Start there when it is.
    let start = lines
        .iter()
        .position(|l| l.contains("Incompatible mods found!"))
        .or_else(|| lines.iter().position(|l| markers.iter().any(|m| l.contains(m))))?;
    let mut out: Vec<String> = Vec::new();
    for line in lines.iter().skip(start).take(40) {
        // Fabric's block ends at the stack trace.
        if line.trim_start().starts_with("at ") || line.contains("Exception in thread") {
            break;
        }
        // The log's own prefix ("[12:00:00] [main/ERROR]: ") is noise here,
        // and so is the exception's class name.
        let cleaned = match line.find("]: ") {
            Some(i) if line.starts_with('[') => &line[i + 3..],
            _ => line,
        };
        let cleaned = cleaned.strip_prefix("net.fabricmc.loader.impl.FormattedException: ").unwrap_or(cleaned);
        if !cleaned.trim().is_empty() {
            out.push(cleaned.trim_end().to_string());
        }
    }
    if out.is_empty() { None } else { Some(out.join("\n")) }
}

fn record_for<'a>(manifest: &'a [ManagedModRecord], jar_name: &str) -> Option<&'a ManagedModRecord> {
    manifest.iter().find(|r| r.file_name.trim().eq_ignore_ascii_case(jar_name))
}

fn is_breeze_jar(jar: &LocalJar) -> bool {
    jar.mod_id() == Some(BREEZE_MOD_ID) || jar.jar_name.eq_ignore_ascii_case(LEGACY_BREEZE_MOD_NAME)
}

/// Everything in the instance's mods folder, as the Mods page shows it.
pub fn instance_report(app: &AppHandle, profile_id: &str, minecraft: &str) -> Result<InstanceModsReport, String> {
    let env = Environment {
        minecraft: minecraft.to_string(),
        loader: installed_loader_version(minecraft).unwrap_or_default(),
        java: java_major_for(minecraft),
    };
    let mods_dir = profile_mods_dir(app, profile_id)?;
    let mut report = instance_report_at(
        &profile_root_dir(app, profile_id)?,
        &mods_dir,
        &profile_manifest_path(app, profile_id)?,
        &env,
    )?;
    let kind = crate::loaders::split_instance_id(profile_id).1;
    if kind != Loader::Fabric {
        let build = breeze_home_dir().ok().and_then(|home| crate::loaders::installed_build(&home, kind, minecraft));
        report_for_loader(&mut report, kind, &mods_local::scan(&mods_dir), build.unwrap_or_default());
    }
    Ok(report)
}

/// The loader an instance folder is for, from its name.
pub fn loader_of_instance(instance_dir: &Path) -> Loader {
    instance_dir
        .file_name()
        .map(|name| crate::loaders::split_instance_id(&name.to_string_lossy()).1)
        .unwrap_or(Loader::Fabric)
}

/// Whether a loader loads this jar: Fabric and Quilt take Fabric's mods,
/// Quilt its own too, Forge only Forge's, and NeoForge Forge's as well (its
/// builds up to 1.20.4 use Forge's file).
pub fn loader_loads(kind: Loader, jar: &LocalJar) -> bool {
    let other = jar.other.as_ref().map(|o| o.kind.as_str());
    match kind {
        Loader::Fabric => jar.meta.is_some(),
        Loader::Quilt => jar.meta.is_some() || other == Some("quilt"),
        Loader::Forge => other == Some("forge"),
        Loader::NeoForge => matches!(other, Some("forge") | Some("neoforge")),
        Loader::Vanilla => false,
    }
}

/// In words, what a jar is.
fn kind_of(jar: &LocalJar) -> &'static str {
    match (jar.meta.is_some(), jar.other.as_ref().map(|o| o.kind.as_str())) {
        (true, _) => "a Fabric mod",
        (_, Some("quilt")) => "a Quilt mod",
        (_, Some("neoforge")) => "a NeoForge mod",
        (_, Some("forge")) => "a Forge mod",
        _ => "not a mod this loader knows",
    }
}

/// The report was made as if Fabric ran the instance. For another loader:
/// its own mods are not a problem, Fabric's are, and Fabric's dependency
/// rules are not applied to anything.
pub fn report_for_loader(report: &mut InstanceModsReport, kind: Loader, jars: &[LocalJar], build: String) {
    report.loader_kind = kind.id().to_string();
    if kind == Loader::Fabric {
        return;
    }
    report.loader = build;
    for entry in &mut report.mods {
        let Some(jar) = jars.iter().find(|j| j.file_name == entry.file_name) else { continue };
        if matches!(jar.problem, Some(mods_local::MetaProblem::NotAJar(_)) | Some(mods_local::MetaProblem::Unreadable(_)) | Some(mods_local::MetaProblem::BadMetadata(_))) {
            continue;
        }
        if kind != Loader::Quilt || jar.meta.is_none() {
            entry.issues.clear();
            entry.duplicate = false;
            entry.loaded_last_run = None;
        }
        if loader_loads(kind, jar) {
            if let Some(other) = &jar.other {
                entry.problem = None;
                entry.mod_id = Some(other.id.clone());
                if !entry.managed {
                    entry.name = other.name.clone();
                }
                entry.version = other.version.clone();
            }
        } else if kind == Loader::Vanilla {
            entry.problem = Some("This version runs the game alone, which loads no mods.".into());
        } else {
            entry.problem = Some(format!("This is {}, which {} does not load.", kind_of(jar), kind.label()));
        }
    }
    if kind != Loader::Quilt {
        // Two switched-on copies of one mod stop Forge and NeoForge as well.
        let mut by_id: std::collections::BTreeMap<String, Vec<&LocalJar>> = std::collections::BTreeMap::new();
        for jar in jars.iter().filter(|j| j.enabled && loader_loads(kind, j)) {
            if let Some(other) = &jar.other {
                by_id.entry(other.id.clone()).or_default().push(jar);
            }
        }
        report.duplicates = by_id
            .into_iter()
            .filter(|(_, files)| files.len() > 1)
            .map(|(id, files)| mods_local::DuplicateGroup {
                name: files[0].other.as_ref().map(|o| o.name.clone()).unwrap_or_else(|| id.clone()),
                mod_id: id,
                files: files
                    .iter()
                    .map(|j| mods_local::DuplicateFile {
                        file_name: j.file_name.clone(),
                        version: j.other.as_ref().map(|o| o.version.clone()).unwrap_or_default(),
                    })
                    .collect(),
            })
            .collect();
        for entry in &mut report.mods {
            entry.duplicate = report.duplicates.iter().any(|g| g.files.iter().any(|f| f.file_name == entry.file_name));
        }
        report.last_launch_problem = None;
    }
}

/// The same, for an instance folder given directly.
pub fn instance_report_at(instance_dir: &Path, mods_dir: &Path, manifest_path: &Path, env: &Environment) -> Result<InstanceModsReport, String> {
    let instance_dir = instance_dir.to_path_buf();
    let mods_dir = mods_dir.to_path_buf();
    let manifest = read_mod_manifest(manifest_path)?;
    let jars = mods_local::scan(&mods_dir);

    // The Breeze runtime Fabric is handed besides the folder counts for
    // duplicates and dependencies too.
    let runtime: Vec<LocalJar> = mods_local::read_jar(&instance_dir.join(".breeze").join("runtime.jar"))
        .into_iter()
        .collect();

    let minecraft = env.minecraft.as_str();
    let loader = env.loader.clone();
    let java = env.java;
    let provided = mods_local::provided_versions(&jars, &runtime);
    let duplicates = mods_local::duplicates(&jars, &runtime);

    let last_run: Option<Value> = fs::read_to_string(instance_dir.join(RUNTIME_REPORT))
        .ok()
        .and_then(|t| serde_json::from_str(&t).ok());
    let loaded: Option<Vec<String>> = last_run.as_ref().and_then(|v| {
        v.get("mods")?.as_array().map(|a| {
            a.iter().filter_map(|m| m.get("id").and_then(Value::as_str).map(str::to_string)).collect()
        })
    });

    let mods = jars
        .iter()
        .map(|jar| {
            let record = record_for(&manifest, &jar.jar_name);
            let meta = jar.meta.as_ref();
            let mut issues = match (meta, jar.enabled) {
                (Some(m), true) => mods_local::issues_for(m, env, &provided),
                _ => Vec::new(),
            };
            let dup = meta.and_then(|m| {
                duplicates.iter().find(|g| g.mod_id == m.id && g.files.iter().any(|f| f.file_name == jar.file_name))
            });
            if let Some(g) = dup {
                issues.insert(0, Issue::Duplicate { files: g.files.iter().map(|f| f.file_name.clone()).collect() });
            }
            ModEntry {
                key: record
                    .map(|r| crate::effective_canonical_id(r))
                    .unwrap_or_else(|| format!("{FILE_KEY_PREFIX}{}", jar.jar_name)),
                file_name: jar.file_name.clone(),
                jar_name: jar.jar_name.clone(),
                enabled: jar.enabled,
                size: jar.size,
                sha1: jar.sha1.clone(),
                sha512: jar.sha512.clone(),
                mod_id: meta.map(|m| m.id.clone()),
                name: meta
                    .map(|m| m.name.clone())
                    .or_else(|| record.map(|r| r.title.clone()))
                    .unwrap_or_else(|| jar.jar_name.trim_end_matches(".jar").to_string()),
                version: meta.map(|m| m.version.clone()).unwrap_or_default(),
                description: meta.map(|m| m.description.clone()).unwrap_or_default(),
                managed: record.is_some(),
                project_id: record.map(|r| r.project_id.clone()).filter(|s| !s.is_empty() && !s.starts_with("custom:")),
                icon_url: record.and_then(|r| r.icon_url.clone()),
                installed_version_id: record.map(|r| r.installed_version_id.clone()).filter(|s| !s.is_empty()),
                breeze: is_breeze_jar(jar),
                problem: jar.problem.as_ref().map(problem_text),
                issues,
                duplicate: dup.is_some(),
                loaded_last_run: match (&loaded, meta, jar.enabled) {
                    (Some(ids), Some(m), true) => Some(ids.iter().any(|i| i == &m.id)),
                    _ => None,
                },
            }
        })
        .collect();

    let missing_files = manifest
        .iter()
        .filter(|r| !r.file_name.trim().is_empty())
        .filter(|r| {
            !jars.iter().any(|j| j.jar_name.eq_ignore_ascii_case(r.file_name.trim()))
        })
        .map(|r| r.file_name.clone())
        .collect();

    Ok(InstanceModsReport {
        instance_dir: instance_dir.to_string_lossy().to_string(),
        mods_dir: mods_dir.to_string_lossy().to_string(),
        minecraft: minecraft.to_string(),
        loader,
        loader_kind: Loader::Fabric.id().to_string(),
        java,
        mods,
        duplicates,
        missing_files,
        last_launch_problem: last_launch_problem(&instance_dir),
        last_run,
    })
}

/// Records for the jars the launcher has no record of, so the Mods page lists
/// every mod Fabric would load, not only what the launcher installed.
pub fn unrecorded_as_records(app: &AppHandle, profile_id: &str, game_version: &str) -> Result<Vec<Value>, String> {
    let report = instance_report(app, profile_id, game_version)?;
    Ok(report
        .mods
        .into_iter()
        .filter(|m| !m.managed && !m.breeze)
        .map(|m| {
            serde_json::json!({
                "projectId": m.key,
                "projectSlug": "",
                "canonicalId": m.key,
                "title": m.name,
                "summary": if m.description.is_empty() { Value::Null } else { Value::String(m.description.clone()) },
                "iconUrl": Value::Null,
                "gameVersion": game_version,
                "loader": "fabric",
                "installedVersionId": "",
                "installedVersionName": m.version,
                "fileName": m.jar_name,
                "enabled": m.enabled,
                "managed": false,
                "modId": m.mod_id,
                "duplicate": m.duplicate,
                "issues": m.issues,
                "problem": m.problem,
            })
        })
        .collect())
}

/// Makes every recorded mod's file match its switch: a mod switched off is
/// renamed to `.jar.disabled`, which Fabric does not load. Launchers before
/// 1.0.28 only left a switched-off mod off the classpath, while Fabric still
/// loaded it from the folder, so the switch did nothing.
pub fn sync_switches(app: &AppHandle, profile_id: &str) -> Vec<String> {
    let mut notes = Vec::new();
    let (Ok(mods_dir), Ok(manifest_path)) = (profile_mods_dir(app, profile_id), profile_manifest_path(app, profile_id)) else {
        return notes;
    };
    let Ok(manifest) = read_mod_manifest(&manifest_path) else { return notes };
    // A jar several records name is on only when all of them say so.
    let enabled: Vec<String> = crate::enabled_mod_file_names(&manifest).iter().map(|s| s.to_ascii_lowercase()).collect();
    let mut seen = std::collections::HashSet::new();
    for record in &manifest {
        let name = record.file_name.trim();
        if name.is_empty() || !seen.insert(name.to_ascii_lowercase()) {
            continue;
        }
        let want = enabled.contains(&name.to_ascii_lowercase());
        let on = mods_dir.join(name).is_file();
        let off = mods_dir.join(format!("{name}{}", mods_local::DISABLED_SUFFIX)).is_file();
        if (want && !on && off) || (!want && on) {
            match mods_local::set_enabled(&mods_dir, name, want) {
                Ok(_) => notes.push(format!("{name} switched {}", if want { "on" } else { "off" })),
                Err(e) => notes.push(e),
            }
        }
    }
    notes
}

#[derive(Serialize, Debug)]
#[serde(rename_all = "camelCase")]
pub struct MovedFile {
    pub file_name: String,
    pub backup_path: String,
}

/// Moves one jar out of the mods folder into the instance's backups, and drops
/// the launcher's records of it. Used for "Remove duplicate" and for removing
/// a mod the launcher did not install. The player confirms first.
pub fn remove_file(app: &AppHandle, profile_id: &str, file_name: &str, reason: &str) -> Result<MovedFile, String> {
    let instance_dir = profile_root_dir(app, profile_id)?;
    let mods_dir = profile_mods_dir(app, profile_id)?;
    let backup = mods_local::move_to_backup(&instance_dir, &mods_dir, file_name, reason)?;
    let jar_name = file_name.strip_suffix(mods_local::DISABLED_SUFFIX).unwrap_or(file_name);
    let manifest_path = profile_manifest_path(app, profile_id)?;
    let mut manifest = read_mod_manifest(&manifest_path)?;
    let before = manifest.len();
    // Only when no copy of that jar is left: another file of the same name
    // (switched off) would still be the record's.
    let still_there = mods_dir.join(jar_name).is_file()
        || mods_dir.join(format!("{jar_name}{}", mods_local::DISABLED_SUFFIX)).is_file();
    if !still_there {
        manifest.retain(|r| !r.file_name.trim().eq_ignore_ascii_case(jar_name));
    }
    if manifest.len() != before {
        write_mod_manifest(&manifest_path, &manifest)?;
    }
    Ok(MovedFile { file_name: file_name.to_string(), backup_path: backup.to_string_lossy().to_string() })
}

/// What happened when a downloaded jar was put in the mods folder.
#[derive(Debug)]
pub enum Placed {
    /// The jar is in place; the files it replaced went to the backups.
    Installed { file_name: String, mod_id: String, version: String, replaced: Vec<(String, PathBuf)>, enabled: bool },
    /// A dependency that was already installed (whatever its file is called)
    /// was kept instead of being replaced by the download.
    KeptExisting { file_name: String, mod_id: String, version: String },
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Checks a download against the hashes Modrinth publishes for it.
pub fn verify_hashes(bytes: &[u8], sha512: Option<&str>, sha1: Option<&str>) -> Result<(), String> {
    use sha2::Digest;
    if let Some(expected) = sha512.map(str::trim).filter(|s| !s.is_empty()) {
        if !hex(&sha2::Sha512::digest(bytes)).eq_ignore_ascii_case(expected) {
            return Err("The download does not match the file Modrinth published (SHA-512), so it was not installed.".into());
        }
        return Ok(());
    }
    if let Some(expected) = sha1.map(str::trim).filter(|s| !s.is_empty()) {
        if !hex(&sha1::Sha1::digest(bytes)).eq_ignore_ascii_case(expected) {
            return Err("The download does not match the file Modrinth published (SHA-1), so it was not installed.".into());
        }
    }
    Ok(())
}

/// When a dependency download may leave an installed copy in place.
pub struct KeepIf<'a> {
    /// The version ranges the mods being installed ask for, per mod id: each
    /// inner list is one mod's alternatives, and every mod must be satisfied.
    pub wants: &'a HashMap<String, Vec<Vec<String>>>,
    pub env: &'a Environment,
}

/// Puts a downloaded, verified jar into the mods folder so that exactly one
/// copy of its mod is there afterwards.
///
/// The jar is written to the staging folder first and moved in with a rename.
/// Every other file of the same mod id (switched on or off) goes to one new
/// backup folder before that; if the final move fails they are put back.
///
/// With `keep_if` (a dependency), a copy already installed is kept and the
/// download dropped, unless it is a version the new mods cannot use and
/// replacing it breaks no other mod. A dependency that fits nobody stays as it
/// is: the Mods page then shows the new mod's unmet range, rather than an
/// install quietly breaking a mod that worked.
pub fn place_jar(
    instance_dir: &Path,
    mods_dir: &Path,
    bytes: &[u8],
    file_name: &str,
    keep_if: Option<KeepIf>,
    reason: &str,
) -> Result<Placed, String> {
    let meta = mods_local::read_meta_bytes(bytes).map_err(|p| format!("{file_name}: {}", problem_text(&p)))?;
    fs::create_dir_all(mods_dir).map_err(|e| format!("Could not create the mods folder: {e}"))?;
    let all = mods_local::scan(mods_dir);
    let same: Vec<LocalJar> = all
        .iter()
        .filter(|j| {
            j.meta.as_ref().map_or(false, |m| m.id == meta.id || m.provides.contains(&meta.id))
                || j.jar_name.eq_ignore_ascii_case(file_name)
        })
        .cloned()
        .collect();

    if let Some(keep) = keep_if {
        if let Some(existing) = same.iter().find(|j| j.enabled && j.meta.as_ref().map_or(false, |m| m.id == meta.id)) {
            let version = existing.meta.as_ref().map(|m| m.version.clone()).unwrap_or_default();
            let fits = keep.wants.get(&meta.id).map_or(true, |lists| {
                lists.iter().all(|any| any.is_empty() || mods_local::satisfies_any(&version, any) != Some(false))
            });
            let replacing_breaks_others = || {
                let extra: Vec<LocalJar> = mods_local::read_jar(&instance_dir.join(".breeze").join("runtime.jar")).into_iter().collect();
                !mods_local::impact(&all, &extra, &meta, true, keep.env).breaks.is_empty()
            };
            if fits || replacing_breaks_others() {
                return Ok(Placed::KeptExisting { file_name: existing.file_name.clone(), mod_id: meta.id.clone(), version });
            }
        }
    }

    let staging = instance_dir.join(STAGING_DIR);
    fs::create_dir_all(&staging).map_err(|e| format!("Could not create the staging folder: {e}"))?;
    let staged = staging.join(format!("{file_name}.part"));
    fs::write(&staged, bytes).map_err(|e| format!("Could not write the download: {e}"))?;
    // The jar on disk is read back and checked again, so a short write or a
    // full disk cannot leave a broken jar in the mods folder.
    match fs::read(&staged).map(|b| mods_local::read_meta_bytes(&b)) {
        Ok(Ok(m)) if m.id == meta.id => {}
        _ => {
            let _ = fs::remove_file(&staged);
            return Err(format!("{file_name} could not be written completely; the mods folder was not changed."));
        }
    }

    // A mod the player had switched off stays off after an update.
    let enabled = same.is_empty() || same.iter().any(|j| j.enabled);
    let mut replaced: Vec<(String, PathBuf)> = Vec::new();
    let backup_dir = if same.is_empty() {
        None
    } else {
        match mods_local::new_backup_dir(instance_dir, reason) {
            Ok(dir) => Some(dir),
            Err(e) => {
                let _ = fs::remove_file(&staged);
                return Err(e);
            }
        }
    };
    for jar in &same {
        let dir = backup_dir.as_deref().expect("made above when anything is replaced");
        match mods_local::move_into_backup(dir, mods_dir, &jar.file_name) {
            Ok(path) => replaced.push((jar.file_name.clone(), path)),
            Err(e) => {
                restore(mods_dir, &replaced);
                let _ = fs::remove_file(&staged);
                let _ = fs::remove_dir(dir);
                return Err(e);
            }
        }
    }
    let final_name = if enabled { file_name.to_string() } else { format!("{file_name}{}", mods_local::DISABLED_SUFFIX) };
    if let Err(e) = fs::rename(&staged, mods_dir.join(&final_name)) {
        restore(mods_dir, &replaced);
        let _ = fs::remove_file(&staged);
        return Err(mods_local::explain_io("install", &mods_dir.join(&final_name), &e));
    }
    Ok(Placed::Installed { file_name: file_name.to_string(), mod_id: meta.id, version: meta.version, replaced, enabled })
}

/// `place_jar` for whatever loader runs the instance (its folder's name says
/// which). A Fabric mod goes the Fabric way; a Forge, NeoForge or Quilt mod is
/// put in place with the same care (staged, the copies it replaces backed up,
/// a switched-off mod left off), without Fabric's dependency rules.
pub fn place_jar_in(
    instance_dir: &Path,
    mods_dir: &Path,
    bytes: &[u8],
    file_name: &str,
    keep_if: Option<KeepIf>,
    reason: &str,
) -> Result<Placed, String> {
    place_jar_on(loader_of_instance(instance_dir), instance_dir, mods_dir, bytes, file_name, keep_if, reason)
}

pub fn place_jar_on(
    kind: Loader,
    instance_dir: &Path,
    mods_dir: &Path,
    bytes: &[u8],
    file_name: &str,
    keep_if: Option<KeepIf>,
    reason: &str,
) -> Result<Placed, String> {
    let fabric = mods_local::read_meta_bytes(bytes);
    let other = if fabric.is_ok() { None } else { mods_local::read_other_meta_bytes(bytes) };
    let as_jar = LocalJar {
        file_name: file_name.to_string(),
        jar_name: file_name.to_string(),
        enabled: true,
        size: bytes.len() as u64,
        sha1: String::new(),
        sha512: String::new(),
        meta: fabric.as_ref().ok().cloned(),
        problem: None,
        other: other.clone(),
    };
    if kind == Loader::Fabric || (kind == Loader::Quilt && fabric.is_ok()) {
        return place_jar(instance_dir, mods_dir, bytes, file_name, keep_if, reason);
    }
    if let Err(problem @ (mods_local::MetaProblem::NotAJar(_) | mods_local::MetaProblem::Unreadable(_))) = &fabric {
        return Err(format!("{file_name}: {}", problem_text(problem)));
    }
    if kind == Loader::Vanilla {
        return Err(format!("{file_name}: this version runs the game alone, which loads no mods."));
    }
    let Some(meta) = other.filter(|_| loader_loads(kind, &as_jar)) else {
        return Err(format!("{file_name}: This is {}, which {} does not load.", kind_of(&as_jar), kind.label()));
    };

    fs::create_dir_all(mods_dir).map_err(|e| format!("Could not create the mods folder: {e}"))?;
    let same: Vec<LocalJar> = mods_local::scan(mods_dir)
        .into_iter()
        .filter(|j| j.other.as_ref().is_some_and(|o| o.id == meta.id) || j.jar_name.eq_ignore_ascii_case(file_name))
        .collect();
    let staging = instance_dir.join(STAGING_DIR);
    fs::create_dir_all(&staging).map_err(|e| format!("Could not create the staging folder: {e}"))?;
    let staged = staging.join(format!("{file_name}.part"));
    fs::write(&staged, bytes).map_err(|e| format!("Could not write the download: {e}"))?;
    match fs::read(&staged).map(|b| mods_local::read_other_meta_bytes(&b)) {
        Ok(Some(m)) if m.id == meta.id => {}
        _ => {
            let _ = fs::remove_file(&staged);
            return Err(format!("{file_name} could not be written completely; the mods folder was not changed."));
        }
    }
    let enabled = same.is_empty() || same.iter().any(|j| j.enabled);
    let mut replaced: Vec<(String, PathBuf)> = Vec::new();
    let backup_dir = if same.is_empty() {
        None
    } else {
        match mods_local::new_backup_dir(instance_dir, reason) {
            Ok(dir) => Some(dir),
            Err(e) => {
                let _ = fs::remove_file(&staged);
                return Err(e);
            }
        }
    };
    for jar in &same {
        let dir = backup_dir.as_deref().expect("made above when anything is replaced");
        match mods_local::move_into_backup(dir, mods_dir, &jar.file_name) {
            Ok(path) => replaced.push((jar.file_name.clone(), path)),
            Err(e) => {
                restore(mods_dir, &replaced);
                let _ = fs::remove_file(&staged);
                let _ = fs::remove_dir(dir);
                return Err(e);
            }
        }
    }
    let final_name = if enabled { file_name.to_string() } else { format!("{file_name}{}", mods_local::DISABLED_SUFFIX) };
    if let Err(e) = fs::rename(&staged, mods_dir.join(&final_name)) {
        restore(mods_dir, &replaced);
        let _ = fs::remove_file(&staged);
        return Err(mods_local::explain_io("install", &mods_dir.join(&final_name), &e));
    }
    Ok(Placed::Installed { file_name: file_name.to_string(), mod_id: meta.id, version: meta.version, replaced, enabled })
}

/// Puts files back from the backups after a failed replacement.
fn restore(mods_dir: &Path, moved: &[(String, PathBuf)]) {
    for (name, path) in moved {
        let _ = fs::rename(path, mods_dir.join(name));
    }
}

/// Which folder of an instance to open.
pub fn instance_folder(app: &AppHandle, profile_id: &str, which: &str) -> Result<PathBuf, String> {
    let root = profile_root_dir(app, profile_id)?;
    let path = match which {
        "instance" => root.clone(),
        "mods" => root.join("mods"),
        "logs" => root.join("logs"),
        "config" => root.join("config"),
        "resourcepacks" => root.join("resourcepacks"),
        "shaderpacks" => root.join("shaderpacks"),
        "crash-reports" => root.join("crash-reports"),
        "backups" => root.join(mods_local::BACKUP_DIR),
        other => return Err(format!("Unknown folder: {other}")),
    };
    fs::create_dir_all(&path).map_err(|e| format!("Could not create {}: {e}", path.display()))?;
    Ok(path)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn other_jar(entries: &[(&str, &str)]) -> Vec<u8> {
        use std::io::Write;
        let mut bytes = Vec::new();
        {
            let mut zip = zip::ZipWriter::new(std::io::Cursor::new(&mut bytes));
            for (name, text) in entries {
                zip.start_file(*name, zip::write::SimpleFileOptions::default()).unwrap();
                zip.write_all(text.as_bytes()).unwrap();
            }
            zip.finish().unwrap();
        }
        bytes
    }

    fn forge_jar(id: &str, version: &str) -> Vec<u8> {
        other_jar(&[("META-INF/mods.toml", &format!("modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId = \"{id}\"\nversion=\"{version}\"\ndisplayName='Just {id}'\n[[dependencies.{id}]]\nmodId=\"forge\"\n"))])
    }

    #[test]
    fn a_forge_or_quilt_mod_says_what_it_is() {
        let forge = mods_local::read_other_meta_bytes(&forge_jar("jei", "15.2.0")).unwrap();
        assert_eq!((forge.kind.as_str(), forge.id.as_str(), forge.version.as_str(), forge.name.as_str()), ("forge", "jei", "15.2.0", "Just jei"));

        // The version left to the jar's manifest, as most mods have it.
        let from_manifest = other_jar(&[
            ("META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"sodium\"\nversion=\"${file.jarVersion}\"\n"),
            ("META-INF/MANIFEST.MF", "Manifest-Version: 1.0\r\nImplementation-Version: 0.6.5\r\n"),
        ]);
        let neo = mods_local::read_other_meta_bytes(&from_manifest).unwrap();
        assert_eq!((neo.kind.as_str(), neo.id.as_str(), neo.version.as_str(), neo.name.as_str()), ("neoforge", "sodium", "0.6.5", "sodium"));

        let quilt = other_jar(&[("quilt.mod.json", r#"{"schema_version":1,"quilt_loader":{"group":"x","id":"ok_zoomer","version":"9.0.0","metadata":{"name":"Ok Zoomer"}}}"#)]);
        let q = mods_local::read_other_meta_bytes(&quilt).unwrap();
        assert_eq!((q.kind.as_str(), q.id.as_str(), q.version.as_str(), q.name.as_str()), ("quilt", "ok_zoomer", "9.0.0", "Ok Zoomer"));

        // A Fabric mod, a plain zip, and a mod id that is not one, say nothing here.
        assert!(mods_local::read_other_meta_bytes(&jar(r#"{"id":"sodium","version":"0.7.0"}"#)).is_none());
        assert!(mods_local::read_other_meta_bytes(&other_jar(&[("readme.txt", "hi")])).is_none());
        assert!(mods_local::read_other_meta_bytes(&other_jar(&[("META-INF/mods.toml", "[[mods]]\nmodId=\"../x\"\n")])).is_none());
        assert!(mods_local::read_other_meta_bytes(b"<html>").is_none());
    }

    #[test]
    fn a_forge_mod_goes_into_a_forge_instance_and_replaces_its_older_copy() {
        let inst = temp("forge-place");
        let mods = inst.join("mods");
        let root = inst.clone();
        let placed = place_jar_on(Loader::Forge, &inst, &mods, &forge_jar("jei", "15.2.0"), "jei-1.20.1-forge-15.2.0.jar", None, "replaced").unwrap();
        assert!(matches!(&placed, Placed::Installed { mod_id, version, replaced, enabled: true, .. } if mod_id == "jei" && version == "15.2.0" && replaced.is_empty()));
        // A newer file of the same mod under another name: the old one goes to the backups.
        let placed = place_jar_on(Loader::Forge, &inst, &mods, &forge_jar("jei", "15.3.0"), "jei-1.20.1-forge-15.3.0.jar", None, "replaced").unwrap();
        assert!(matches!(&placed, Placed::Installed { replaced, .. } if replaced.len() == 1 && replaced[0].0 == "jei-1.20.1-forge-15.2.0.jar"));
        let names: Vec<String> = mods_local::scan(&mods).into_iter().map(|j| j.file_name).collect();
        assert_eq!(names, vec!["jei-1.20.1-forge-15.3.0.jar"]);

        // What the instance's loader does not load is refused, and nothing changes.
        let fabric = jar(r#"{"id":"sodium","version":"0.7.0"}"#);
        let refused = place_jar_on(Loader::Forge, &inst, &mods, &fabric, "sodium.jar", None, "replaced").unwrap_err();
        assert!(refused.contains("a Fabric mod") && refused.contains("Forge does not load"), "{refused}");
        let neo_only = other_jar(&[("META-INF/neoforge.mods.toml", "[[mods]]\nmodId=\"x\"\nversion=\"1\"\n")]);
        assert!(place_jar_on(Loader::Forge, &inst, &mods, &neo_only, "x.jar", None, "replaced").unwrap_err().contains("a NeoForge mod"));
        assert!(place_jar_on(Loader::NeoForge, &inst, &mods, &neo_only, "x.jar", None, "replaced").is_ok(), "NeoForge takes its own");
        assert!(place_jar_on(Loader::NeoForge, &inst, &mods, &forge_jar("y", "1"), "y.jar", None, "replaced").is_ok(), "and Forge's file, which it used up to 1.20.4");
        assert!(place_jar_on(Loader::Vanilla, &inst, &mods, &fabric, "sodium.jar", None, "replaced").unwrap_err().contains("loads no mods"));
        assert!(place_jar_on(Loader::Fabric, &inst, &mods, &forge_jar("z", "1"), "z.jar", None, "replaced").is_err(), "Fabric stays as strict as it was");
        assert!(place_jar_on(Loader::Forge, &inst, &mods, b"<html>error page</html>", "bad.jar", None, "replaced").unwrap_err().contains("Not a valid jar"));
        // Quilt takes Fabric's mods (the Fabric way) and its own.
        assert!(place_jar_on(Loader::Quilt, &inst, &mods, &fabric, "sodium.jar", None, "replaced").is_ok());

        // The loader is read from the instance folder's name.
        assert_eq!(loader_of_instance(Path::new("/home/x/.breezeclient/instances/1.20.1-forge")), Loader::Forge);
        assert_eq!(loader_of_instance(Path::new("/home/x/.breezeclient/instances/1.21.1-neoforge")), Loader::NeoForge);
        assert_eq!(loader_of_instance(Path::new("/home/x/.breezeclient/instances/1.21.4")), Loader::Fabric);
        let _ = fs::remove_dir_all(root);
    }

    #[test]
    fn the_report_of_a_forge_instance_judges_mods_by_forge() {
        let inst = temp("forge-report");
        let mods = inst.join("mods");
        let root = inst.clone();
        fs::write(mods.join("jei-a.jar"), forge_jar("jei", "15.2.0")).unwrap();
        fs::write(mods.join("jei-b.jar"), forge_jar("jei", "15.3.0")).unwrap();
        fs::write(mods.join("create.jar"), forge_jar("create", "0.5.1")).unwrap();
        fs::write(mods.join("sodium.jar"), jar(r#"{"id":"sodium","version":"0.7.0","depends":{"fabric-api":"*"}}"#)).unwrap();
        let mut report = instance_report_at(&inst, &mods, &inst.join("mods-state.json"), &env()).unwrap();
        assert_eq!(report.loader_kind, "fabric");
        assert!(report.mods.iter().find(|m| m.file_name == "create.jar").unwrap().problem.as_deref().unwrap().contains("Fabric does not load"));

        report_for_loader(&mut report, Loader::Forge, &mods_local::scan(&mods), "47.4.28".into());
        assert_eq!((report.loader_kind.as_str(), report.loader.as_str()), ("forge", "47.4.28"));
        let create = report.mods.iter().find(|m| m.file_name == "create.jar").unwrap();
        assert_eq!((create.problem.clone(), create.mod_id.as_deref(), create.name.as_str(), create.version.as_str(), create.duplicate), (None, Some("create"), "Just create", "0.5.1", false));
        let sodium = report.mods.iter().find(|m| m.file_name == "sodium.jar").unwrap();
        assert_eq!(sodium.problem.as_deref(), Some("This is a Fabric mod, which Forge does not load."));
        assert!(sodium.issues.is_empty(), "Fabric's missing-dependency note means nothing here");
        assert_eq!(report.duplicates.len(), 1);
        assert_eq!(report.duplicates[0].mod_id, "jei");
        assert_eq!(report.duplicates[0].files.len(), 2);
        assert!(report.mods.iter().filter(|m| m.duplicate).all(|m| m.file_name.starts_with("jei-")));

        // The game alone loads none of them.
        report_for_loader(&mut report, Loader::Vanilla, &mods_local::scan(&mods), String::new());
        assert!(report.mods.iter().all(|m| m.problem.as_deref() == Some("This version runs the game alone, which loads no mods.")));
        let _ = fs::remove_dir_all(root);
    }
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

    fn env() -> Environment {
        Environment { minecraft: "1.21.11".into(), loader: "0.19.5".into(), java: Some(21) }
    }

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!(
            "breeze-mods-commands-{name}-{}",
            SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()
        ));
        fs::create_dir_all(d.join("mods")).unwrap();
        d
    }

    #[test]
    fn installing_a_mod_leaves_exactly_one_copy_and_keeps_the_old_one_in_backups() {
        let inst = temp("replace");
        let mods = inst.join("mods");
        fs::write(mods.join("sodium-fabric-0.6.0+mc1.21.11.jar"), jar(r#"{"id":"sodium","version":"0.6.0"}"#)).unwrap();
        fs::write(mods.join("sodium (2).jar"), jar(r#"{"id":"sodium","version":"0.6.1"}"#)).unwrap();
        let placed = place_jar(&inst, &mods, &jar(r#"{"id":"sodium","version":"0.7.0"}"#), "sodium-fabric-0.7.0+mc1.21.11.jar", None, "replaced").unwrap();
        let Placed::Installed { replaced, enabled, .. } = placed else { panic!("not installed") };
        assert!(enabled);
        assert_eq!(replaced.len(), 2);
        let left: Vec<_> = mods_local::scan(&mods).into_iter().map(|j| j.file_name).collect();
        assert_eq!(left, vec!["sodium-fabric-0.7.0+mc1.21.11.jar".to_string()]);
        assert!(replaced.iter().all(|(_, p)| p.is_file()));
        assert!(!inst.join(STAGING_DIR).join("sodium-fabric-0.7.0+mc1.21.11.jar.part").exists());
    }

    #[test]
    fn a_dependency_already_installed_is_kept_whatever_its_file_is_called() {
        let inst = temp("keep");
        let mods = inst.join("mods");
        fs::write(mods.join("my-fabric-api.jar"), jar(r#"{"id":"fabric-api","version":"0.139.0+1.21.11"}"#)).unwrap();
        let wants = HashMap::new();
        let e = env();
        let keep = || Some(KeepIf { wants: &wants, env: &e });
        let placed = place_jar(&inst, &mods, &jar(r#"{"id":"fabric-api","version":"0.140.0+1.21.11"}"#), "fabric-api-0.140.0+1.21.11.jar", keep(), "replaced").unwrap();
        assert!(matches!(placed, Placed::KeptExisting { ref file_name, .. } if file_name == "my-fabric-api.jar"));
        assert_eq!(mods_local::scan(&mods).len(), 1);
    }

    #[test]
    fn a_dependency_too_old_for_the_new_mod_is_replaced_unless_another_mod_needs_it() {
        let inst = temp("deps");
        let mods = inst.join("mods");
        fs::write(mods.join("fabric-api-old.jar"), jar(r#"{"id":"fabric-api","version":"0.130.0+1.21.11"}"#)).unwrap();
        let e = env();
        let mut wants: HashMap<String, Vec<Vec<String>>> = HashMap::new();
        wants.insert("fabric-api".into(), vec![vec![">=0.139.0".into()]]);
        let new = jar(r#"{"id":"fabric-api","version":"0.140.0+1.21.11"}"#);

        // Another mod is pinned to the old one: it stays, and nothing breaks quietly.
        fs::write(mods.join("pinned.jar"), jar(r#"{"id":"pinned","version":"1","depends":{"fabric-api":"<0.135.0"}}"#)).unwrap();
        let kept = place_jar(&inst, &mods, &new, "fabric-api-0.140.0+1.21.11.jar", Some(KeepIf { wants: &wants, env: &e }), "replaced").unwrap();
        assert!(matches!(kept, Placed::KeptExisting { .. }));

        // Nobody else needs the old one: the new mod gets the version it asks for.
        fs::remove_file(mods.join("pinned.jar")).unwrap();
        let placed = place_jar(&inst, &mods, &new, "fabric-api-0.140.0+1.21.11.jar", Some(KeepIf { wants: &wants, env: &e }), "replaced").unwrap();
        let Placed::Installed { replaced, .. } = placed else { panic!("not replaced") };
        assert_eq!(replaced[0].0, "fabric-api-old.jar");
        assert!(mods.join("fabric-api-0.140.0+1.21.11.jar").is_file());
    }

    #[test]
    fn a_switched_off_mod_stays_off_when_it_is_updated() {
        let inst = temp("off");
        let mods = inst.join("mods");
        fs::write(mods.join("zoomify-2.13.jar.disabled"), jar(r#"{"id":"zoomify","version":"2.13"}"#)).unwrap();
        let placed = place_jar(&inst, &mods, &jar(r#"{"id":"zoomify","version":"2.14"}"#), "zoomify-2.14.jar", None, "replaced").unwrap();
        assert!(matches!(placed, Placed::Installed { enabled: false, .. }));
        assert!(mods.join("zoomify-2.14.jar.disabled").is_file());
    }

    #[test]
    fn a_download_that_is_not_a_fabric_mod_or_does_not_match_its_hash_changes_nothing() {
        let inst = temp("bad");
        let mods = inst.join("mods");
        fs::write(mods.join("keep.jar"), jar(r#"{"id":"keep","version":"1"}"#)).unwrap();
        assert!(place_jar(&inst, &mods, b"<html>error page</html>", "keep.jar", None, "replaced").is_err());
        assert!(verify_hashes(b"abc", Some("00"), None).is_err());
        assert!(verify_hashes(b"abc", None, Some("a9993e364706816aba3e25717850c26c9cd0d89d")).is_ok());
        assert_eq!(mods_local::scan(&mods).len(), 1);
    }

    #[test]
    fn fabrics_real_refusal_reads_as_its_explanation() {
        // Fabric Loader 0.19.5 on Minecraft 1.21.11, CI run 37130047814.
        let inst = temp("real-log");
        fs::create_dir_all(inst.join("logs")).unwrap();
        fs::write(
            inst.join("logs/latest.log"),
            "[14:36:54] [main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.19.5\n\
             [14:36:55] [main/WARN]: Mod resolution failed\n\
             [14:36:55] [main/INFO]: Immediate reason: [HARD_DEP_NO_CANDIDATE reeses-sodium-options 2.2.4+mc1.21.11 {depends sodium @ [=0.8.14+mc1.21.11]}, ROOT_FORCELOAD_SINGLE reeses-sodium-options 2.2.4+mc1.21.11]\n\
             [14:36:55] [main/INFO]: Reason: [HARD_DEP reeses-sodium-options 2.2.4+mc1.21.11 {depends sodium @ [=0.8.14+mc1.21.11]}]\n\
             [14:36:55] [main/INFO]: Fix: add [], remove [], replace [[sodium 0.8.15-beta.1+mc1.21.11] -> add:sodium 0.8.14+mc1.21.11 ([[0.8.14+mc1.21.11,0.8.14+mc1.21.11]])]\n\
             [14:36:55] [main/ERROR]: Incompatible mods found!\n\
             net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!\n\
             A potential solution has been determined, this may resolve your problem:\n\
             \t - Replace mod 'Sodium' (sodium) 0.8.15-beta.1+mc1.21.11 with version 0.8.14+mc1.21.11.\n\
             More details:\n\
             \t - Mod 'Reese's Sodium Options' (reeses-sodium-options) 2.2.4+mc1.21.11 requires version 0.8.14+mc1.21.11 of mod 'Sodium' (sodium), but only the wrong version is present: 0.8.15-beta.1+mc1.21.11!\n\
             \tat net.fabricmc.loader.impl.FormattedException.ofLocalized(FormattedException.java:51)\n",
        )
        .unwrap();
        let p = last_launch_problem(&inst).unwrap();
        assert!(p.starts_with("Incompatible mods found!\nSome of your mods are incompatible"), "{p}");
        assert!(p.contains("Replace mod 'Sodium' (sodium) 0.8.15-beta.1+mc1.21.11 with version 0.8.14+mc1.21.11."));
        assert!(!p.contains("HARD_DEP"), "the solver's own notes are left out");
        assert!(!p.contains("FormattedException"));
    }

    #[test]
    fn fabrics_refusal_is_read_from_the_log() {
        let inst = temp("log");
        fs::create_dir_all(inst.join("logs")).unwrap();
        fs::write(
            inst.join("logs/latest.log"),
            "[12:00:00] [main/INFO]: Loading Minecraft 1.21.11 with Fabric Loader 0.19.5\n\
             [12:00:01] [main/ERROR]: Incompatible mods found!\n\
             net.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!\n\
             A potential solution has been determined, this may resolve your problem:\n\
             \t - Install fabric-api, any version.\n\
             \tat net.fabricmc.loader.impl.FabricLoaderImpl.load(FabricLoaderImpl.java:1)\n",
        )
        .unwrap();
        let p = last_launch_problem(&inst).unwrap();
        assert!(p.starts_with("Incompatible mods found!"));
        assert!(p.contains("Install fabric-api"));
        assert!(!p.contains("FabricLoaderImpl"));
    }
}
