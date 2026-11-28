//! Bedrock Edition on Windows (1.3.0, docs/PLAN_1.3.0.md).
//!
//! Finds the Bedrock installs a Windows user has, imports `.mcpack`,
//! `.mcaddon` and `.mcworld` files into them safely, lists, backs up and
//! removes packs, turns resource packs on for every world (the game's
//! global resource packs file), and lists, backs up and restores worlds.
//!
//! Breeze never touches the game's own files or its version: only the
//! creator folders under `com.mojang` that the game reads packs and worlds
//! from. Every path comes from detection here, never from the interface;
//! the interface names an install by id ("release", "preview", "uwp"), a
//! pack by its folder and a world by its key, and each is looked up again.
//!
//! Folders (Microsoft Learn, "GDK Migration on Windows", 2025-10-24):
//! - GDK (1.21.120 and later): `%APPDATA%\Minecraft Bedrock\Users\Shared\games\com.mojang`
//!   for packs; worlds sit under the per-user `Users\<id>\games\com.mojang`
//!   (UNVERIFIED on a real PC: the page names only the shared pack folders,
//!   so both are searched).
//! - Preview: the same under `%APPDATA%\Minecraft Bedrock Preview`.
//! - UWP (before 1.21.120): `%LOCALAPPDATA%\Packages\Microsoft.MinecraftUWP_8wekyb3d8bbwe\LocalState\games\com.mojang`.
//!
//! The code is the same on every system so its tests run on the build
//! repository's Windows, macOS and Linux runners; only Windows has installs.

use serde::Serialize;
use serde_json::{json, Value};
use std::collections::HashSet;
use std::fs;
use std::io::{Read, Seek, Write};
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// The Breeze add-on's pack UUIDs (Breeze-Bedrock/tools/uuids.lock.json).
pub const BREEZE_UI_UUID: &str = "70a8a544-7979-4b4c-baeb-f6409f84a62c";
pub const BREEZE_HUD_UUID: &str = "09dbb2ad-de5a-4f0b-ad34-dec38a39abf6";

/// Limits on what an import may make the launcher read or write. Real packs
/// and worlds stay far below them; past them nothing is written.
pub const IMPORT_FILE_MAX: u64 = 1024 * 1024 * 1024;
pub const IMPORT_TOTAL_MAX: u64 = 4 * 1024 * 1024 * 1024;
pub const IMPORT_ENTRY_MAX: u64 = 1024 * 1024 * 1024;
pub const IMPORT_NESTED_MAX: u64 = 512 * 1024 * 1024;
pub const IMPORT_ENTRIES_MAX: usize = 200_000;

const UWP_PACKAGE: &str = "Microsoft.MinecraftUWP_8wekyb3d8bbwe";

/// Where to look: the two Windows folders the installs live under.
#[derive(Debug, Clone, Default)]
pub struct Roots {
    pub appdata: Option<PathBuf>,
    pub localappdata: Option<PathBuf>,
}

impl Roots {
    pub fn from_env() -> Self {
        Roots {
            appdata: std::env::var_os("APPDATA").map(PathBuf::from),
            localappdata: std::env::var_os("LOCALAPPDATA").map(PathBuf::from),
        }
    }
}

/// One Bedrock install found on this computer.
#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Install {
    /// "release", "preview" or "uwp".
    pub id: String,
    pub label: String,
    /// "GDK" or "UWP".
    pub build: String,
    /// The `com.mojang` folder packs are installed into.
    #[serde(skip)]
    pub packs_root: PathBuf,
    /// Every `com.mojang` folder that may hold worlds or the global packs
    /// file: the shared one and one per signed-in user (GDK).
    #[serde(skip)]
    pub user_roots: Vec<PathBuf>,
}

/// A child folder whose name matches `name` ignoring case (Windows folders
/// are case-insensitive; the tests run on case-sensitive systems too).
fn child_ci(dir: &Path, name: &str) -> Option<PathBuf> {
    let exact = dir.join(name);
    if exact.is_dir() {
        return Some(exact);
    }
    let want = name.to_ascii_lowercase();
    fs::read_dir(dir).ok()?.flatten().find_map(|e| {
        let p = e.path();
        (p.is_dir() && e.file_name().to_string_lossy().to_ascii_lowercase() == want).then_some(p)
    })
}

fn gdk_install(base: &Path, id: &str, label: &str) -> Option<Install> {
    let users = child_ci(base, "Users")?;
    let shared = child_ci(&users, "Shared")
        .and_then(|s| child_ci(&s, "games"))
        .and_then(|g| child_ci(&g, "com.mojang"))?;
    let mut user_roots = vec![shared.clone()];
    let mut others: Vec<PathBuf> = fs::read_dir(&users)
        .ok()?
        .flatten()
        .filter(|e| !e.file_name().to_string_lossy().eq_ignore_ascii_case("shared"))
        .filter_map(|e| child_ci(&e.path(), "games").and_then(|g| child_ci(&g, "com.mojang")))
        .collect();
    others.sort();
    user_roots.extend(others);
    Some(Install { id: id.into(), label: label.into(), build: "GDK".into(), packs_root: shared, user_roots })
}

/// Every install found under these roots, release first.
pub fn detect(roots: &Roots) -> Vec<Install> {
    let mut out = Vec::new();
    if let Some(appdata) = &roots.appdata {
        if let Some(i) = child_ci(appdata, "Minecraft Bedrock").and_then(|b| gdk_install(&b, "release", "Minecraft Bedrock")) {
            out.push(i);
        }
        if let Some(i) = child_ci(appdata, "Minecraft Bedrock Preview")
            .and_then(|b| gdk_install(&b, "preview", "Minecraft Bedrock Preview"))
        {
            out.push(i);
        }
    }
    if let Some(local) = &roots.localappdata {
        let root = local.join("Packages").join(UWP_PACKAGE).join("LocalState").join("games").join("com.mojang");
        if root.is_dir() {
            out.push(Install {
                id: "uwp".into(),
                label: "Minecraft (older Microsoft Store build)".into(),
                build: "UWP".into(),
                packs_root: root.clone(),
                user_roots: vec![root],
            });
        }
    }
    out
}

pub fn find_install(roots: &Roots, id: &str) -> Result<Install, String> {
    detect(roots)
        .into_iter()
        .find(|i| i.id == id)
        .ok_or_else(|| "That Bedrock install was not found on this PC.".to_string())
}

/// The URI that opens this install through Windows (UNVERIFIED with the GDK build).
#[cfg_attr(not(target_os = "windows"), allow(dead_code))]
pub fn launch_uri(install_id: &str) -> &'static str {
    if install_id == "preview" {
        "minecraft-preview:"
    } else {
        "minecraft:"
    }
}

// ---------------------------------------------------------------- manifests

/// Bedrock manifests often carry `//` and `/* */` comments and trailing
/// commas, which the game accepts. Removes them outside strings.
pub fn lenient_json(text: &str) -> Result<Value, String> {
    let text = text.trim_start_matches('\u{feff}');
    let mut out = String::with_capacity(text.len());
    let b: Vec<char> = text.chars().collect();
    let (mut i, mut in_str) = (0, false);
    while i < b.len() {
        let c = b[i];
        if in_str {
            out.push(c);
            if c == '\\' && i + 1 < b.len() {
                out.push(b[i + 1]);
                i += 1;
            } else if c == '"' {
                in_str = false;
            }
        } else if c == '"' {
            in_str = true;
            out.push(c);
        } else if c == '/' && b.get(i + 1) == Some(&'/') {
            while i < b.len() && b[i] != '\n' {
                i += 1;
            }
            continue;
        } else if c == '/' && b.get(i + 1) == Some(&'*') {
            i += 2;
            while i + 1 < b.len() && !(b[i] == '*' && b[i + 1] == '/') {
                i += 1;
            }
            i += 2;
            continue;
        } else if c == ',' {
            let mut j = i + 1;
            while j < b.len() && b[j].is_whitespace() {
                j += 1;
            }
            if matches!(b.get(j), Some('}') | Some(']')) {
                i += 1;
                continue;
            }
            out.push(c);
        } else {
            out.push(c);
        }
        i += 1;
    }
    serde_json::from_str(&out).map_err(|e| format!("manifest.json is not valid JSON ({e})"))
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PackKind {
    Resource,
    Behavior,
}

impl PackKind {
    pub fn folder(&self) -> &'static str {
        match self {
            PackKind::Resource => "resource_packs",
            PackKind::Behavior => "behavior_packs",
        }
    }
    pub fn parse(s: &str) -> Result<Self, String> {
        match s {
            "resource" => Ok(PackKind::Resource),
            "behavior" => Ok(PackKind::Behavior),
            _ => Err("Unknown pack kind.".into()),
        }
    }
}

#[derive(Debug, Clone)]
pub struct Manifest {
    pub kind: PackKind,
    pub uuid: String,
    pub name: String,
    pub description: String,
    pub version: Vec<u64>,
    pub min_engine: Option<String>,
}

pub fn is_uuid(s: &str) -> bool {
    let parts: Vec<&str> = s.split('-').collect();
    parts.len() == 5
        && [8, 4, 4, 4, 12].iter().zip(&parts).all(|(n, p)| p.len() == *n && p.chars().all(|c| c.is_ascii_hexdigit()))
}

/// [1, 2, 3] or "1.2.3" (manifest format 3) as numbers.
fn version_of(v: Option<&Value>) -> Option<Vec<u64>> {
    match v? {
        Value::Array(a) => a.iter().map(Value::as_u64).collect::<Option<Vec<_>>>().filter(|v| !v.is_empty()),
        Value::String(s) => {
            let core = s.split(['-', '+']).next().unwrap_or("");
            core.split('.').map(|p| p.parse().ok()).collect::<Option<Vec<_>>>().filter(|v| !v.is_empty())
        }
        _ => None,
    }
}

pub fn version_string(v: &[u64]) -> String {
    v.iter().map(u64::to_string).collect::<Vec<_>>().join(".")
}

/// Reads a pack manifest. `lang` resolves names like "pack.name" from the
/// pack's own `texts/en_US.lang` when given.
pub fn parse_manifest(text: &str, lang: Option<&str>) -> Result<Manifest, String> {
    let v = lenient_json(text)?;
    let header = v.get("header").ok_or("manifest.json has no header.")?;
    let uuid = header.get("uuid").and_then(Value::as_str).unwrap_or("").to_ascii_lowercase();
    if !is_uuid(&uuid) {
        return Err("manifest.json has no valid UUID.".into());
    }
    let version = version_of(header.get("version")).ok_or("manifest.json has no valid version.")?;
    let modules = v.get("modules").and_then(Value::as_array).ok_or("manifest.json lists no modules.")?;
    let types: Vec<&str> = modules.iter().filter_map(|m| m.get("type").and_then(Value::as_str)).collect();
    let kind = if types.iter().any(|t| *t == "resources") {
        PackKind::Resource
    } else if types.iter().any(|t| matches!(*t, "data" | "script" | "client_data" | "javascript")) {
        PackKind::Behavior
    } else if types.iter().any(|t| *t == "world_template") {
        return Err("World templates are not supported here. Open them in the game.".into());
    } else if types.iter().any(|t| *t == "skin_pack") {
        return Err("Skin packs are not supported here. Open them in the game.".into());
    } else {
        return Err("This pack has no resource or behaviour module.".into());
    };
    let resolve = |key: &str| -> String {
        let raw = header.get(key).and_then(Value::as_str).unwrap_or("").to_string();
        lang.and_then(|l| lang_value(l, &raw)).unwrap_or(raw)
    };
    let name = resolve("name");
    Ok(Manifest {
        kind,
        uuid,
        name: if name.trim().is_empty() { "Unnamed pack".into() } else { name },
        description: resolve("description"),
        version,
        min_engine: version_of(header.get("min_engine_version")).map(|v| version_string(&v)),
    })
}

fn lang_value(lang: &str, key: &str) -> Option<String> {
    if key.is_empty() || key.contains(' ') {
        return None;
    }
    lang.lines().find_map(|l| {
        let l = l.trim_start_matches('\u{feff}');
        let (k, v) = l.split_once('=')?;
        (k.trim() == key).then(|| v.split('#').next().unwrap_or("").trim().replace('\t', " "))
    })
}

fn read_pack_folder(dir: &Path) -> Option<Manifest> {
    let text = fs::read_to_string(dir.join("manifest.json")).ok()?;
    let lang = fs::read_to_string(dir.join("texts").join("en_US.lang")).ok();
    parse_manifest(&text, lang.as_deref()).ok()
}

// --------------------------------------------------------- global packs file

/// The global resource packs files of an install: those that exist, or,
/// when none does yet, where the game keeps them (`minecraftpe/` of each
/// root that has one, else the shared root). UNVERIFIED on a real PC: no
/// Microsoft page documents this file.
pub fn global_packs_files(install: &Install) -> Vec<PathBuf> {
    let existing: Vec<PathBuf> = install
        .user_roots
        .iter()
        .map(|r| r.join("minecraftpe").join("global_resource_packs.json"))
        .filter(|p| p.is_file())
        .collect();
    if !existing.is_empty() {
        return existing;
    }
    let with_pe: Vec<PathBuf> = install
        .user_roots
        .iter()
        .filter(|r| r.join("minecraftpe").is_dir())
        .map(|r| r.join("minecraftpe").join("global_resource_packs.json"))
        .collect();
    if !with_pe.is_empty() {
        return with_pe;
    }
    vec![install.packs_root.join("minecraftpe").join("global_resource_packs.json")]
}

fn read_global(path: &Path) -> Vec<Value> {
    fs::read_to_string(path)
        .ok()
        .and_then(|t| lenient_json(&t).ok())
        .and_then(|v| v.as_array().cloned())
        .unwrap_or_default()
}

/// UUIDs turned on globally in any of the install's files.
pub fn global_uuids(install: &Install) -> HashSet<String> {
    global_packs_files(install)
        .iter()
        .flat_map(|p| read_global(p))
        .filter_map(|e| e.get("pack_id").and_then(Value::as_str).map(str::to_ascii_lowercase))
        .collect()
}

/// Adds the pack at the top of the list (the game applies the first entry
/// over the others) or removes it. Other entries and their fields are kept.
pub fn set_global(install: &Install, uuid: &str, version: &[u64], on: bool) -> Result<usize, String> {
    let uuid = uuid.to_ascii_lowercase();
    let files = global_packs_files(install);
    for path in &files {
        let mut list: Vec<Value> = read_global(path)
            .into_iter()
            .filter(|e| e.get("pack_id").and_then(Value::as_str).map(str::to_ascii_lowercase).as_deref() != Some(uuid.as_str()))
            .collect();
        if on {
            list.insert(0, json!({ "pack_id": uuid, "version": version }));
        }
        if let Some(parent) = path.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Could not create the global packs folder: {e}"))?;
        }
        write_atomic(path, serde_json::to_string_pretty(&Value::Array(list)).unwrap_or_default().as_bytes())?;
    }
    Ok(files.len())
}

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let tmp = path.with_extension("breeze-tmp");
    fs::write(&tmp, bytes).map_err(|e| format!("Could not write {}: {e}", path.display()))?;
    fs::rename(&tmp, path).map_err(|e| format!("Could not replace {}: {e}", path.display()))
}

// --------------------------------------------------------------------- packs

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PackInfo {
    pub folder: String,
    pub kind: PackKind,
    pub name: String,
    pub description: String,
    pub uuid: String,
    pub version: String,
    pub min_engine: Option<String>,
    pub global: bool,
    pub breeze: bool,
}

pub fn list_packs(install: &Install) -> Vec<PackInfo> {
    let globals = global_uuids(install);
    let mut out = Vec::new();
    for kind in [PackKind::Resource, PackKind::Behavior] {
        let Ok(entries) = fs::read_dir(install.packs_root.join(kind.folder())) else { continue };
        for e in entries.flatten() {
            let dir = e.path();
            if !dir.is_dir() {
                continue;
            }
            let Some(m) = read_pack_folder(&dir) else { continue };
            out.push(PackInfo {
                folder: e.file_name().to_string_lossy().into_owned(),
                kind: kind.clone(),
                global: kind == PackKind::Resource && globals.contains(&m.uuid),
                breeze: m.uuid == BREEZE_UI_UUID || m.uuid == BREEZE_HUD_UUID,
                name: m.name,
                description: m.description,
                uuid: m.uuid,
                version: version_string(&m.version),
                min_engine: m.min_engine,
            });
        }
    }
    out.sort_by(|a, b| (!a.breeze, a.name.to_lowercase()).cmp(&(!b.breeze, b.name.to_lowercase())));
    out
}

/// A folder name from the interface: one plain path segment, nothing else.
pub fn safe_segment(name: &str) -> Result<&str, String> {
    let ok = !name.is_empty()
        && name.len() <= 200
        && name != "."
        && name != ".."
        && !name.contains(['/', '\\', ':', '\0'])
        && !name.starts_with('.');
    if ok {
        Ok(name)
    } else {
        Err("That name is not allowed.".into())
    }
}

pub fn pack_dir(install: &Install, kind: &PackKind, folder: &str) -> Result<PathBuf, String> {
    let dir = install.packs_root.join(kind.folder()).join(safe_segment(folder)?);
    if dir.join("manifest.json").is_file() {
        Ok(dir)
    } else {
        Err("That pack was not found.".into())
    }
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// `<dir>/<stem>-<time>.<ext>`, with -2, -3 ... when that name is taken, so a
/// backup never replaces an earlier one.
fn backup_path(dir: &Path, stem: &str, ext: &str) -> PathBuf {
    let base = format!("{stem}-{}", now_secs());
    let mut p = dir.join(format!("{base}.{ext}"));
    let mut n = 1;
    while p.exists() {
        n += 1;
        p = dir.join(format!("{base}-{n}.{ext}"));
    }
    p
}

/// Zips a folder (its contents at the archive's root) to `dest`.
pub fn zip_dir(src: &Path, dest: &Path) -> Result<(), String> {
    if let Some(parent) = dest.parent() {
        fs::create_dir_all(parent).map_err(|e| format!("Could not create the backup folder: {e}"))?;
    }
    let tmp = dest.with_extension("breeze-tmp");
    let file = fs::File::create(&tmp).map_err(|e| format!("Could not create the backup: {e}"))?;
    let mut zip = zip::ZipWriter::new(file);
    let opts = zip::write::SimpleFileOptions::default()
        .compression_method(zip::CompressionMethod::Deflated)
        .large_file(true);
    let mut stack = vec![src.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let mut entries: Vec<_> = fs::read_dir(&dir).map_err(|e| format!("Could not read {}: {e}", dir.display()))?.flatten().collect();
        entries.sort_by_key(|e| e.file_name());
        for e in entries {
            let p = e.path();
            let ft = e.file_type().map_err(|e| e.to_string())?;
            if ft.is_symlink() {
                continue;
            }
            let rel = p.strip_prefix(src).map_err(|e| e.to_string())?.to_string_lossy().replace('\\', "/");
            if ft.is_dir() {
                zip.add_directory(format!("{rel}/"), opts).map_err(|e| e.to_string())?;
                stack.push(p);
            } else {
                zip.start_file(rel, opts).map_err(|e| e.to_string())?;
                let mut f = fs::File::open(&p).map_err(|e| e.to_string())?;
                std::io::copy(&mut f, &mut zip).map_err(|e| format!("Could not back up {}: {e}", p.display()))?;
            }
        }
    }
    zip.finish().map_err(|e| format!("Could not finish the backup: {e}"))?;
    fs::rename(&tmp, dest).map_err(|e| format!("Could not save the backup: {e}"))
}

/// Backs a pack up to `<backups>/packs/<folder>-<time>.mcpack`.
pub fn backup_pack(install: &Install, kind: &PackKind, folder: &str, backups: &Path) -> Result<PathBuf, String> {
    let dir = pack_dir(install, kind, folder)?;
    let dest = backup_path(&backups.join("packs"), folder, "mcpack");
    zip_dir(&dir, &dest)?;
    Ok(dest)
}

/// Removes a pack after backing it up, and takes it out of the global list.
pub fn remove_pack(install: &Install, kind: &PackKind, folder: &str, backups: &Path) -> Result<PathBuf, String> {
    let dir = pack_dir(install, kind, folder)?;
    let m = read_pack_folder(&dir);
    let backup = backup_pack(install, kind, folder, backups)?;
    fs::remove_dir_all(&dir).map_err(|e| format!("Could not remove the pack: {e}"))?;
    if let (Some(m), PackKind::Resource) = (m, kind) {
        if global_uuids(install).contains(&m.uuid) {
            set_global(install, &m.uuid, &m.version, false)?;
        }
    }
    Ok(backup)
}

// -------------------------------------------------------------------- import

#[derive(Debug, Clone, Serialize, Default)]
#[serde(rename_all = "camelCase")]
pub struct ImportReport {
    pub packs: Vec<ImportedPack>,
    pub worlds: Vec<String>,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ImportedPack {
    pub name: String,
    pub kind: PackKind,
    pub uuid: String,
    pub version: String,
    pub folder: String,
    /// The version it replaced, when an older copy was there.
    pub replaced: Option<String>,
}

/// The entries of an archive, checked: safe names, no links, sizes in
/// bounds. Directory entries are left out.
struct Entries {
    names: Vec<(usize, String, u64)>,
}

fn check_entries<R: Read + Seek>(zip: &mut zip::ZipArchive<R>) -> Result<Entries, String> {
    if zip.len() > IMPORT_ENTRIES_MAX {
        return Err("That file has too many entries.".into());
    }
    let mut names = Vec::new();
    let mut total = 0u64;
    for i in 0..zip.len() {
        let e = zip.by_index(i).map_err(|e| format!("That file is damaged ({e})."))?;
        let raw = e.name().replace('\\', "/");
        if e.enclosed_name().is_none() || raw.starts_with('/') || raw.split('/').any(|s| s == "..") || raw.contains(':') {
            return Err(format!("That file contains an unsafe path: {raw}"));
        }
        if e.unix_mode().map(|m| m & 0o170000 == 0o120000).unwrap_or(false) {
            return Err(format!("That file contains a link, which is not allowed: {raw}"));
        }
        if e.is_dir() {
            continue;
        }
        if e.size() > IMPORT_ENTRY_MAX {
            return Err(format!("A file inside is too large: {raw}"));
        }
        total = total.saturating_add(e.size());
        if total > IMPORT_TOTAL_MAX {
            return Err("That file unpacks to more than Breeze allows (4 GB).".into());
        }
        names.push((i, raw, e.size()));
    }
    Ok(Entries { names })
}

fn read_entry<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, index: usize, max: u64) -> Result<Vec<u8>, String> {
    let e = zip.by_index(index).map_err(|e| e.to_string())?;
    let mut buf = Vec::new();
    e.take(max + 1).read_to_end(&mut buf).map_err(|e| format!("Could not read the file ({e})."))?;
    if buf.len() as u64 > max {
        return Err("A file inside is too large.".into());
    }
    Ok(buf)
}

/// Writes the entries under `prefix` into `dest` (created fresh), checking
/// every path stays inside it and stopping at the declared sizes.
fn extract_prefix<R: Read + Seek>(zip: &mut zip::ZipArchive<R>, entries: &Entries, prefix: &str, dest: &Path) -> Result<(), String> {
    fs::create_dir_all(dest).map_err(|e| format!("Could not create {}: {e}", dest.display()))?;
    for (i, name, size) in &entries.names {
        let Some(rel) = name.strip_prefix(prefix) else { continue };
        if rel.is_empty() {
            continue;
        }
        let out = dest.join(rel);
        if !out.starts_with(dest) {
            return Err(format!("That file contains an unsafe path: {name}"));
        }
        if let Some(parent) = out.parent() {
            fs::create_dir_all(parent).map_err(|e| format!("Could not create a folder: {e}"))?;
        }
        let e = zip.by_index(*i).map_err(|e| e.to_string())?;
        let mut f = fs::File::create(&out).map_err(|e| format!("Could not write {rel}: {e}"))?;
        let copied = std::io::copy(&mut e.take(*size), &mut f).map_err(|e| format!("Could not write {rel}: {e}"))?;
        if copied != *size {
            return Err(format!("{rel} is shorter than the archive says."));
        }
        f.flush().map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Folder name for a new pack: its name, made safe, and the start of its UUID.
fn new_pack_folder(m: &Manifest) -> String {
    let clean: String = m
        .name
        .chars()
        .map(|c| if c.is_ascii_alphanumeric() || c == '-' || c == '_' { c } else { '_' })
        .collect::<String>()
        .trim_matches('_')
        .chars()
        .take(48)
        .collect();
    let base = if clean.is_empty() { "pack".to_string() } else { clean };
    format!("{base}_{}", &m.uuid[..8])
}

fn find_pack_by_uuid(install: &Install, kind: &PackKind, uuid: &str) -> Option<(String, Manifest)> {
    let dir = install.packs_root.join(kind.folder());
    fs::read_dir(dir).ok()?.flatten().find_map(|e| {
        let m = read_pack_folder(&e.path())?;
        (m.uuid == uuid).then(|| (e.file_name().to_string_lossy().into_owned(), m))
    })
}

/// Where the archive's manifests are: at the root or one folder down.
fn manifest_prefixes(entries: &Entries) -> Vec<String> {
    let mut out: Vec<String> = entries
        .names
        .iter()
        .filter_map(|(_, n, _)| {
            let prefix = n.strip_suffix("manifest.json")?;
            let depth = prefix.matches('/').count();
            (prefix.is_empty() || (depth == 1 && prefix.ends_with('/'))).then(|| prefix.to_string())
        })
        .collect();
    out.sort();
    out.dedup();
    // A manifest at the root makes the whole archive one pack.
    if out.iter().any(|p| p.is_empty()) {
        return vec![String::new()];
    }
    out
}

fn world_prefixes(entries: &Entries) -> Vec<String> {
    let mut out: Vec<String> = entries
        .names
        .iter()
        .filter_map(|(_, n, _)| {
            let prefix = n.strip_suffix("level.dat")?;
            (prefix.is_empty() || (prefix.matches('/').count() == 1 && prefix.ends_with('/'))).then(|| prefix.to_string())
        })
        .collect();
    out.sort();
    out.dedup();
    if out.iter().any(|p| p.is_empty()) {
        return vec![String::new()];
    }
    out
}

fn install_pack_from<R: Read + Seek>(
    install: &Install,
    zip: &mut zip::ZipArchive<R>,
    entries: &Entries,
    prefix: &str,
    backups: &Path,
) -> Result<ImportedPack, String> {
    let find = |name: &str| entries.names.iter().find(|(_, n, _)| n == &format!("{prefix}{name}")).map(|(i, _, _)| *i);
    let mi = find("manifest.json").ok_or("No manifest.json in the pack.")?;
    let text = String::from_utf8_lossy(&read_entry(zip, mi, 1024 * 1024)?).into_owned();
    let lang = match find("texts/en_US.lang") {
        Some(i) => Some(String::from_utf8_lossy(&read_entry(zip, i, 1024 * 1024)?).into_owned()),
        None => None,
    };
    let m = parse_manifest(&text, lang.as_deref())?;
    let kind_dir = install.packs_root.join(m.kind.folder());
    fs::create_dir_all(&kind_dir).map_err(|e| format!("Could not create {}: {e}", kind_dir.display()))?;
    let existing = find_pack_by_uuid(install, &m.kind, &m.uuid);
    let folder = existing.as_ref().map(|(f, _)| f.clone()).unwrap_or_else(|| new_pack_folder(&m));
    let dest = kind_dir.join(&folder);
    let staging = kind_dir.join(format!(".{folder}.breeze-import"));
    let _ = fs::remove_dir_all(&staging);
    if let Err(e) = extract_prefix(zip, entries, prefix, &staging) {
        let _ = fs::remove_dir_all(&staging);
        return Err(e);
    }
    let mut replaced = None;
    if let Some((old_folder, old)) = &existing {
        backup_pack(install, &m.kind, old_folder, backups)?;
        fs::remove_dir_all(&dest).map_err(|e| format!("Could not replace the older copy: {e}"))?;
        replaced = Some(version_string(&old.version));
    } else if dest.exists() {
        let _ = fs::remove_dir_all(&staging);
        return Err("A different pack already uses that folder.".into());
    }
    fs::rename(&staging, &dest).map_err(|e| format!("Could not move the pack into place: {e}"))?;
    // A global pack that was replaced points at the new version.
    if m.kind == PackKind::Resource && global_uuids(install).contains(&m.uuid) {
        set_global(install, &m.uuid, &m.version, true)?;
    }
    Ok(ImportedPack { name: m.name, kind: m.kind, uuid: m.uuid, version: version_string(&m.version), folder, replaced })
}

/// The `com.mojang` folder new worlds go to: the user folder whose worlds
/// were changed last (GDK), else the shared one.
pub fn worlds_target(install: &Install) -> PathBuf {
    let modified = |p: &Path| fs::metadata(p).and_then(|m| m.modified()).ok();
    install
        .user_roots
        .iter()
        .filter(|r| r.join("minecraftWorlds").is_dir())
        .max_by_key(|r| modified(&r.join("minecraftWorlds")))
        .cloned()
        .unwrap_or_else(|| install.packs_root.clone())
        .join("minecraftWorlds")
}

fn install_world_from<R: Read + Seek>(
    install: &Install,
    zip: &mut zip::ZipArchive<R>,
    entries: &Entries,
    prefix: &str,
) -> Result<String, String> {
    let worlds = worlds_target(install);
    let base = format!("breeze{}", now_secs());
    let mut id = base.clone();
    let mut n = 1;
    while worlds.join(&id).exists() {
        n += 1;
        id = format!("{base}-{n}");
    }
    let staging = worlds.join(format!(".{id}.breeze-import"));
    let _ = fs::remove_dir_all(&staging);
    if let Err(e) = extract_prefix(zip, entries, prefix, &staging) {
        let _ = fs::remove_dir_all(&staging);
        return Err(e);
    }
    fs::rename(&staging, worlds.join(&id)).map_err(|e| format!("Could not move the world into place: {e}"))?;
    Ok(fs::read_to_string(worlds.join(&id).join("levelname.txt")).map(|s| s.trim().to_string()).unwrap_or(id))
}

fn import_zip<R: Read + Seek>(
    install: &Install,
    zip: &mut zip::ZipArchive<R>,
    ext: &str,
    backups: &Path,
    depth: u8,
    report: &mut ImportReport,
) -> Result<(), String> {
    let entries = check_entries(zip)?;
    match ext {
        "mcworld" => {
            let prefixes = world_prefixes(&entries);
            if prefixes.len() != 1 {
                return Err("That .mcworld has no world in it (level.dat was not found).".into());
            }
            report.worlds.push(install_world_from(install, zip, &entries, &prefixes[0])?);
        }
        "mcpack" => {
            let prefixes = manifest_prefixes(&entries);
            if prefixes.is_empty() {
                return Err("That .mcpack has no manifest.json.".into());
            }
            for p in prefixes {
                report.packs.push(install_pack_from(install, zip, &entries, &p, backups)?);
            }
        }
        "mcaddon" => {
            // Packs as folders, and packs or worlds as files inside.
            for p in manifest_prefixes(&entries) {
                report.packs.push(install_pack_from(install, zip, &entries, &p, backups)?);
            }
            if depth == 0 {
                let nested: Vec<(usize, String)> = entries
                    .names
                    .iter()
                    .filter(|(_, n, _)| !n.contains('/'))
                    .filter_map(|(i, n, _)| {
                        let lower = n.to_ascii_lowercase();
                        ["mcpack", "mcworld"]
                            .iter()
                            .find(|x| lower.ends_with(&format!(".{x}")))
                            .map(|x| (*i, x.to_string()))
                    })
                    .collect();
                for (i, inner_ext) in nested {
                    let bytes = read_entry(zip, i, IMPORT_NESTED_MAX)?;
                    let mut inner = zip::ZipArchive::new(std::io::Cursor::new(bytes))
                        .map_err(|e| format!("A pack inside is damaged ({e})."))?;
                    import_zip(install, &mut inner, &inner_ext, backups, depth + 1, report)?;
                }
            }
            if report.packs.is_empty() && report.worlds.is_empty() {
                return Err("That .mcaddon has no packs in it.".into());
            }
        }
        _ => return Err("Bedrock imports are .mcpack, .mcaddon or .mcworld files.".into()),
    }
    Ok(())
}

/// Imports a `.mcpack`, `.mcaddon` or `.mcworld` file into an install.
/// Packs with the same UUID are replaced (the older copy is backed up
/// first); worlds always arrive as new worlds.
pub fn import_file(install: &Install, file: &Path, file_name: &str, backups: &Path) -> Result<ImportReport, String> {
    let ext = Path::new(file_name)
        .extension()
        .map(|e| e.to_string_lossy().to_ascii_lowercase())
        .unwrap_or_default();
    if !matches!(ext.as_str(), "mcpack" | "mcaddon" | "mcworld") {
        return Err("Bedrock imports are .mcpack, .mcaddon or .mcworld files.".into());
    }
    let len = fs::metadata(file).map_err(|_| "The file to import is missing. Try again.".to_string())?.len();
    if len > IMPORT_FILE_MAX {
        return Err("That file is larger than Breeze allows (1 GB).".into());
    }
    let f = fs::File::open(file).map_err(|e| format!("Could not open the file: {e}"))?;
    let mut zip = zip::ZipArchive::new(std::io::BufReader::new(f))
        .map_err(|_| "That file is not a valid pack or world (it is not a zip archive).".to_string())?;
    let mut report = ImportReport::default();
    import_zip(install, &mut zip, &ext, backups, 0, &mut report)?;
    Ok(report)
}

// -------------------------------------------------------------------- worlds

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct WorldInfo {
    /// "<root index>:<folder>", looked up again for every action.
    pub key: String,
    pub name: String,
    pub folder: String,
    pub size_bytes: u64,
    pub modified_secs: u64,
}

fn dir_size(p: &Path) -> u64 {
    let mut total = 0;
    let mut stack = vec![p.to_path_buf()];
    while let Some(d) = stack.pop() {
        if let Ok(rd) = fs::read_dir(&d) {
            for e in rd.flatten() {
                match e.file_type() {
                    Ok(t) if t.is_dir() => stack.push(e.path()),
                    Ok(t) if t.is_file() => total += e.metadata().map(|m| m.len()).unwrap_or(0),
                    _ => {}
                }
            }
        }
    }
    total
}

pub fn list_worlds(install: &Install) -> Vec<WorldInfo> {
    let mut out = Vec::new();
    for (ri, root) in install.user_roots.iter().enumerate() {
        let Ok(rd) = fs::read_dir(root.join("minecraftWorlds")) else { continue };
        for e in rd.flatten() {
            let dir = e.path();
            let folder = e.file_name().to_string_lossy().into_owned();
            if folder.starts_with('.') || !dir.join("level.dat").is_file() {
                continue;
            }
            let name = fs::read_to_string(dir.join("levelname.txt")).map(|s| s.trim().to_string()).unwrap_or_default();
            let modified_secs = fs::metadata(dir.join("level.dat"))
                .and_then(|m| m.modified())
                .ok()
                .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                .map(|d| d.as_secs())
                .unwrap_or(0);
            out.push(WorldInfo {
                key: format!("{ri}:{folder}"),
                name: if name.is_empty() { folder.clone() } else { name },
                folder,
                size_bytes: dir_size(&dir),
                modified_secs,
            });
        }
    }
    out.sort_by(|a, b| b.modified_secs.cmp(&a.modified_secs));
    out
}

pub fn world_dir(install: &Install, key: &str) -> Result<PathBuf, String> {
    let (ri, folder) = key.split_once(':').ok_or("That world was not found.")?;
    let ri: usize = ri.parse().map_err(|_| "That world was not found.")?;
    let root = install.user_roots.get(ri).ok_or("That world was not found.")?;
    let dir = root.join("minecraftWorlds").join(safe_segment(folder)?);
    if dir.join("level.dat").is_file() {
        Ok(dir)
    } else {
        Err("That world was not found.".into())
    }
}

/// Backs a world up as `<backups>/worlds/<folder>-<time>.mcworld`, a file
/// the game itself can also open.
pub fn backup_world(install: &Install, key: &str, backups: &Path) -> Result<PathBuf, String> {
    let dir = world_dir(install, key)?;
    let folder = dir.file_name().map(|f| f.to_string_lossy().into_owned()).unwrap_or_default();
    let dest = backup_path(&backups.join("worlds"), &folder, "mcworld");
    zip_dir(&dir, &dest)?;
    Ok(dest)
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct BackupInfo {
    pub file: String,
    /// "worlds" or "packs".
    pub kind: String,
    pub size_bytes: u64,
    pub created_secs: u64,
}

pub fn list_backups(backups: &Path) -> Vec<BackupInfo> {
    let mut out = Vec::new();
    for kind in ["worlds", "packs"] {
        let Ok(rd) = fs::read_dir(backups.join(kind)) else { continue };
        for e in rd.flatten() {
            let file = e.file_name().to_string_lossy().into_owned();
            if !(file.ends_with(".mcworld") || file.ends_with(".mcpack")) {
                continue;
            }
            let meta = e.metadata().ok();
            out.push(BackupInfo {
                created_secs: meta
                    .as_ref()
                    .and_then(|m| m.modified().ok())
                    .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
                    .map(|d| d.as_secs())
                    .unwrap_or(0),
                size_bytes: meta.map(|m| m.len()).unwrap_or(0),
                kind: kind.into(),
                file,
            });
        }
    }
    out.sort_by(|a, b| b.created_secs.cmp(&a.created_secs));
    out
}

/// Restores a backup: a world comes back as a new world next to the
/// others (nothing is overwritten); a pack replaces the copy with its UUID.
pub fn restore_backup(install: &Install, kind: &str, file: &str, backups: &Path) -> Result<ImportReport, String> {
    let sub = match kind {
        "worlds" => "worlds",
        "packs" => "packs",
        _ => return Err("Unknown backup kind.".into()),
    };
    let path = backups.join(sub).join(safe_segment(file)?);
    if !path.is_file() {
        return Err("That backup was not found.".into());
    }
    import_file(install, &path, file, backups)
}

// --------------------------------------------------------------------- tests

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("breeze-bedrock-{name}-{}-{}", std::process::id(), now_secs()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn manifest(uuid: &str, module: &str, version: &str, name: &str) -> String {
        format!(
            r#"{{
  // comments and trailing commas, as real packs have them
  "format_version": 2,
  "header": {{ "name": "{name}", "description": "d", "uuid": "{uuid}", "version": {version}, "min_engine_version": [1, 21, 0], }},
  "modules": [ {{ "type": "{module}", "uuid": "00000000-0000-0000-0000-00000000000{}", "version": [1, 0, 0] }} ]
}}"#,
            module.len() % 10
        )
    }

    fn zip_of(files: &[(&str, &[u8])]) -> Vec<u8> {
        let mut buf = Cursor::new(Vec::new());
        {
            let mut z = zip::ZipWriter::new(&mut buf);
            for (n, b) in files {
                z.start_file(*n, zip::write::SimpleFileOptions::default()).unwrap();
                z.write_all(b).unwrap();
            }
            z.finish().unwrap();
        }
        buf.into_inner()
    }

    /// A GDK install (release) with two user folders, as fixtures.
    fn gdk_fixture(name: &str) -> (PathBuf, Roots) {
        let base = temp(name);
        let appdata = base.join("Roaming");
        let shared = appdata.join("Minecraft Bedrock/Users/Shared/games/com.mojang");
        fs::create_dir_all(shared.join("resource_packs")).unwrap();
        fs::create_dir_all(shared.join("behavior_packs")).unwrap();
        fs::create_dir_all(appdata.join("Minecraft Bedrock/Users/1234567890/games/com.mojang/minecraftWorlds")).unwrap();
        fs::create_dir_all(appdata.join("Minecraft Bedrock/Users/1234567890/games/com.mojang/minecraftpe")).unwrap();
        (base.clone(), Roots { appdata: Some(appdata), localappdata: Some(base.join("Local")) })
    }

    #[test]
    fn detects_gdk_preview_and_uwp_and_nothing_else() {
        let (base, roots) = gdk_fixture("detect");
        fs::create_dir_all(base.join("Roaming/Minecraft Bedrock Preview/users/shared/games/com.mojang")).unwrap();
        fs::create_dir_all(base.join("Local/Packages").join(UWP_PACKAGE).join("LocalState/games/com.mojang")).unwrap();
        let found = detect(&roots);
        let ids: Vec<&str> = found.iter().map(|i| i.id.as_str()).collect();
        assert_eq!(ids, ["release", "preview", "uwp"]);
        assert_eq!(found[0].user_roots.len(), 2, "shared and one user");
        assert!(found[0].packs_root.ends_with("Shared/games/com.mojang"));
        assert_eq!(found[1].build, "GDK");
        assert_eq!(found[2].build, "UWP");
        assert!(detect(&Roots::default()).is_empty());
        let empty = temp("detect-empty");
        fs::create_dir_all(empty.join("Minecraft Bedrock/Users")).unwrap();
        assert!(detect(&Roots { appdata: Some(empty), localappdata: None }).is_empty(), "no Shared folder: not installed");
        assert_eq!(launch_uri("preview"), "minecraft-preview:");
        assert_eq!(launch_uri("release"), "minecraft:");
    }

    #[test]
    fn manifests_with_comments_names_from_lang_and_kinds() {
        let m = parse_manifest(&manifest(BREEZE_UI_UUID, "resources", "[1, 0, 0]", "pack.name"), Some("pack.name=Breeze UI\n")).unwrap();
        assert_eq!(m.kind, PackKind::Resource);
        assert_eq!(m.name, "Breeze UI");
        assert_eq!(m.min_engine.as_deref(), Some("1.21.0"));
        let b = parse_manifest(&manifest(BREEZE_HUD_UUID, "script", "\"2.1.0\"", "HUD"), None).unwrap();
        assert_eq!(b.kind, PackKind::Behavior);
        assert_eq!(b.version, vec![2, 1, 0]);
        assert!(parse_manifest(&manifest(BREEZE_UI_UUID, "world_template", "[1,0,0]", "x"), None).is_err());
        assert!(parse_manifest(&manifest("not-a-uuid", "resources", "[1,0,0]", "x"), None).is_err());
        assert!(parse_manifest("{", None).is_err());
        assert_eq!(lenient_json(r#"{"a": "// not a comment", "b": [1,2,],}"#).unwrap(), json!({"a": "// not a comment", "b": [1, 2]}));
    }

    #[test]
    fn imports_a_pack_then_replaces_it_with_a_backup() {
        let (base, roots) = gdk_fixture("import");
        let install = find_install(&roots, "release").unwrap();
        let backups = base.join("backups");
        let v1 = zip_of(&[
            ("manifest.json", manifest(BREEZE_UI_UUID, "resources", "[1, 0, 0]", "Breeze UI").as_bytes()),
            ("textures/ui/a.png", b"png"),
        ]);
        let f = base.join("Breeze-UI.mcpack");
        fs::write(&f, &v1).unwrap();
        let r = import_file(&install, &f, "Breeze-UI.mcpack", &backups).unwrap();
        assert_eq!(r.packs.len(), 1);
        assert_eq!(r.packs[0].folder, "Breeze_UI_70a8a544");
        assert!(install.packs_root.join("resource_packs/Breeze_UI_70a8a544/textures/ui/a.png").is_file());
        // Turn it on globally, then import 1.1.0: same folder, backed up, still global at the new version.
        set_global(&install, BREEZE_UI_UUID, &[1, 0, 0], true).unwrap();
        let v2 = zip_of(&[("Breeze UI/manifest.json", manifest(BREEZE_UI_UUID, "resources", "[1, 1, 0]", "Breeze UI").as_bytes())]);
        fs::write(&f, &v2).unwrap();
        let r = import_file(&install, &f, "Breeze-UI.mcpack", &backups).unwrap();
        assert_eq!(r.packs[0].replaced.as_deref(), Some("1.0.0"));
        assert!(!install.packs_root.join("resource_packs/Breeze_UI_70a8a544/textures").exists(), "old files gone");
        assert_eq!(list_backups(&backups).len(), 1);
        let packs = list_packs(&install);
        assert_eq!(packs.len(), 1);
        assert!(packs[0].global && packs[0].breeze);
        assert_eq!(packs[0].version, "1.1.0");
        let file = &global_packs_files(&install)[0];
        assert!(file.ends_with("1234567890/games/com.mojang/minecraftpe/global_resource_packs.json"));
        assert_eq!(read_global(file), vec![json!({"pack_id": BREEZE_UI_UUID, "version": [1, 1, 0]})]);
        // Remove: backed up, gone, out of the global list.
        remove_pack(&install, &PackKind::Resource, "Breeze_UI_70a8a544", &backups).unwrap();
        assert!(list_packs(&install).is_empty());
        assert!(global_uuids(&install).is_empty());
        assert_eq!(list_backups(&backups).len(), 2);
    }

    #[test]
    fn global_list_keeps_other_entries_and_puts_ours_first() {
        let (_base, roots) = gdk_fixture("global");
        let install = find_install(&roots, "release").unwrap();
        let file = global_packs_files(&install)[0].clone();
        fs::write(&file, r#"[{"pack_id":"11111111-2222-3333-4444-555555555555","subpack":"hd","version":[3,0,0]}]"#).unwrap();
        set_global(&install, BREEZE_UI_UUID, &[1, 0, 0], true).unwrap();
        let list = read_global(&file);
        assert_eq!(list[0]["pack_id"], BREEZE_UI_UUID);
        assert_eq!(list[1]["subpack"], "hd", "other fields kept");
        set_global(&install, BREEZE_UI_UUID, &[1, 0, 0], false).unwrap();
        assert_eq!(read_global(&file).len(), 1);
    }

    #[test]
    fn mcaddon_with_nested_packs_and_folders() {
        let (base, roots) = gdk_fixture("addon");
        let install = find_install(&roots, "release").unwrap();
        let ui = zip_of(&[("manifest.json", manifest(BREEZE_UI_UUID, "resources", "[1, 0, 0]", "Breeze UI").as_bytes())]);
        let hud = zip_of(&[("manifest.json", manifest(BREEZE_HUD_UUID, "script", "[1, 0, 0]", "Breeze HUD").as_bytes())]);
        let addon = zip_of(&[("Breeze-UI-1.0.0.mcpack", &ui), ("Breeze-HUD-1.0.0.mcpack", &hud)]);
        let f = base.join("Breeze-1.0.0.mcaddon");
        fs::write(&f, &addon).unwrap();
        let r = import_file(&install, &f, "Breeze-1.0.0.mcaddon", &base.join("b")).unwrap();
        assert_eq!(r.packs.len(), 2);
        assert!(install.packs_root.join("behavior_packs/Breeze_HUD_09dbb2ad/manifest.json").is_file());
        // Folders inside an .mcaddon work too.
        let other = "abcdef01-2345-6789-abcd-ef0123456789";
        let addon2 = zip_of(&[("RP/manifest.json", manifest(other, "resources", "[1, 0, 0]", "Other").as_bytes())]);
        fs::write(&f, &addon2).unwrap();
        assert_eq!(import_file(&install, &f, "x.mcaddon", &base.join("b")).unwrap().packs[0].name, "Other");
    }

    #[test]
    fn refuses_unsafe_archives_without_writing_anything() {
        let (base, roots) = gdk_fixture("unsafe");
        let install = find_install(&roots, "release").unwrap();
        let m = manifest(BREEZE_UI_UUID, "resources", "[1, 0, 0]", "Evil");
        let cases: Vec<(&str, Vec<u8>)> = vec![
            ("traversal.mcpack", zip_of(&[("manifest.json", m.as_bytes()), ("../../evil.txt", b"x")])),
            ("absolute.mcpack", zip_of(&[("manifest.json", m.as_bytes()), ("/etc/evil", b"x")])),
            ("drive.mcpack", zip_of(&[("manifest.json", m.as_bytes()), ("C:/evil", b"x")])),
            ("nomanifest.mcpack", zip_of(&[("deep/er/manifest.json", m.as_bytes())])),
            ("notzip.mcpack", b"hello".to_vec()),
            ("template.mcpack", zip_of(&[("manifest.json", manifest(BREEZE_UI_UUID, "world_template", "[1,0,0]", "t").as_bytes())])),
            ("noworld.mcworld", zip_of(&[("db/x", b"x")])),
            ("wrong.zip", zip_of(&[("manifest.json", m.as_bytes())])),
        ];
        for (name, bytes) in cases {
            let f = base.join(name);
            fs::write(&f, &bytes).unwrap();
            assert!(import_file(&install, &f, name, &base.join("b")).is_err(), "{name} was accepted");
        }
        assert!(list_packs(&install).is_empty());
        assert_eq!(fs::read_dir(install.packs_root.join("resource_packs")).unwrap().count(), 0, "nothing left behind");
        assert!(!base.join("evil.txt").exists());
        for bad in ["", ".", "..", "../x", "a/b", "a\\b", "C:", ".hidden"] {
            assert!(safe_segment(bad).is_err(), "{bad:?}");
        }
        assert!(pack_dir(&install, &PackKind::Resource, "../../Users").is_err());
        assert!(world_dir(&install, "0:../../x").is_err());
        assert!(world_dir(&install, "9:world").is_err());
    }

    #[test]
    fn worlds_import_list_backup_and_restore_as_new() {
        let (base, roots) = gdk_fixture("worlds");
        let install = find_install(&roots, "release").unwrap();
        let backups = base.join("backups");
        let world = zip_of(&[("level.dat", b"dat"), ("levelname.txt", b"My World\n"), ("db/000001.ldb", b"chunks")]);
        let f = base.join("My World.mcworld");
        fs::write(&f, &world).unwrap();
        let r = import_file(&install, &f, "My World.mcworld", &backups).unwrap();
        assert_eq!(r.worlds, ["My World"]);
        let worlds = list_worlds(&install);
        assert_eq!(worlds.len(), 1);
        assert_eq!(worlds[0].name, "My World");
        assert!(worlds[0].key.starts_with("1:"), "into the user folder that has worlds");
        let b = backup_world(&install, &worlds[0].key, &backups).unwrap();
        assert!(b.extension().unwrap() == "mcworld");
        let listed = list_backups(&backups);
        assert_eq!(listed.len(), 1);
        restore_backup(&install, "worlds", &listed[0].file, &backups).unwrap();
        assert_eq!(list_worlds(&install).len(), 2, "restored next to the original, not over it");
        assert!(restore_backup(&install, "worlds", "../../etc", &backups).is_err());
        assert!(restore_backup(&install, "other", &listed[0].file, &backups).is_err());
    }
}
