//! What is really in an instance's mods folder.
//!
//! Every jar is read, not just the ones the launcher installed: its Fabric
//! metadata (`fabric.mod.json`), the mods bundled inside it (`META-INF/jars`),
//! its hashes, and whether it is switched on (`.jar`) or off (`.jar.disabled`,
//! which Fabric does not load). From that come the answers the Mods page and
//! the launch need: which mods are installed, which are installed twice (Fabric
//! refuses to start), and which need something that is missing or the wrong
//! version.
//!
//! Version ranges are read the way Fabric Loader reads them (its
//! `VersionPredicate` and `SemanticVersion`), so a mod is only called
//! incompatible when Fabric would say so too. A range that is not understood
//! is reported as unknown, never as a failure.
//!
//! Files are never deleted here. A jar that has to go is moved into the
//! instance's `.breeze/mod-backups` folder, from where it can be put back.

use serde::Serialize;
use serde_json::Value;
use sha1::Sha1;
use sha2::{Digest, Sha512};
use std::{
    collections::{BTreeMap, HashMap},
    fs,
    io::{Cursor, Read},
    path::{Path, PathBuf},
    time::{SystemTime, UNIX_EPOCH},
};
use zip::ZipArchive;

/// Files larger than this are not a Fabric mod anyone ships.
const MAX_JAR_BYTES: u64 = 512 * 1024 * 1024;
/// fabric.mod.json files are a few kilobytes.
const MAX_METADATA_BYTES: u64 = 1024 * 1024;
/// Bundled jars inside bundled jars, as deep as Fabric API goes.
const MAX_NESTING: usize = 3;
pub const DISABLED_SUFFIX: &str = ".disabled";
pub const BACKUP_DIR: &str = ".breeze/mod-backups";

// ── Fabric metadata ─────────────────────────────────────────────────────────

/// One entry of `depends`, `breaks` and the like: a mod id and the versions it
/// accepts. Several alternatives (a JSON array) are OR-ed, as in Fabric.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Dependency {
    pub id: String,
    pub any_of: Vec<String>,
}

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FabricMeta {
    pub id: String,
    pub version: String,
    pub name: String,
    pub description: String,
    pub provides: Vec<String>,
    pub depends: Vec<Dependency>,
    pub recommends: Vec<Dependency>,
    pub breaks: Vec<Dependency>,
    pub conflicts: Vec<Dependency>,
    pub environment: String,
    /// Mods bundled inside this jar (and inside those), as id and version.
    pub bundled: Vec<(String, String)>,
}

/// Why a jar's metadata could not be read.
#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(tag = "kind", content = "detail", rename_all = "camelCase")]
pub enum MetaProblem {
    /// Not a zip at all: a broken or half-finished download.
    NotAJar(String),
    /// A zip without fabric.mod.json; the detail names what it is instead, when known.
    NotFabric(String),
    /// fabric.mod.json that cannot be parsed.
    BadMetadata(String),
    Unreadable(String),
}

fn deps(value: Option<&Value>) -> Vec<Dependency> {
    let Some(map) = value.and_then(Value::as_object) else { return Vec::new() };
    let mut out: Vec<Dependency> = map
        .iter()
        .map(|(id, spec)| Dependency {
            id: id.clone(),
            any_of: match spec {
                Value::String(s) => vec![s.clone()],
                Value::Array(items) => items.iter().filter_map(Value::as_str).map(str::to_string).collect(),
                _ => vec!["*".to_string()],
            },
        })
        .collect();
    out.sort_by(|a, b| a.id.cmp(&b.id));
    out
}

/// Parses fabric.mod.json leniently, as Fabric's own reader is: a raw line
/// break inside a description, or a comment, does not stop it.
fn parse_json_lenient(text: &str) -> Result<Value, String> {
    let text = text.trim_start_matches('\u{feff}');
    match serde_json::from_str::<Value>(text) {
        Ok(v) => Ok(v),
        Err(first) => {
            let mut cleaned = String::with_capacity(text.len());
            let mut in_string = false;
            let mut escaped = false;
            let mut chars = text.chars().peekable();
            while let Some(c) = chars.next() {
                if in_string {
                    if escaped {
                        escaped = false;
                        cleaned.push(c);
                    } else if c == '\\' {
                        escaped = true;
                        cleaned.push(c);
                    } else if c == '"' {
                        in_string = false;
                        cleaned.push(c);
                    } else if c == '\n' || c == '\r' || c == '\t' {
                        cleaned.push(' ');
                    } else {
                        cleaned.push(c);
                    }
                } else if c == '"' {
                    in_string = true;
                    cleaned.push(c);
                } else if c == '/' && chars.peek() == Some(&'/') {
                    for d in chars.by_ref() {
                        if d == '\n' {
                            cleaned.push('\n');
                            break;
                        }
                    }
                } else {
                    cleaned.push(c);
                }
            }
            serde_json::from_str::<Value>(&cleaned).map_err(|_| first.to_string())
        }
    }
}

fn meta_from_value(value: &Value) -> Result<FabricMeta, MetaProblem> {
    let id = value
        .get("id")
        .and_then(Value::as_str)
        .filter(|s| !s.trim().is_empty())
        .ok_or_else(|| MetaProblem::BadMetadata("fabric.mod.json has no mod id".into()))?
        .to_string();
    let text = |key: &str| value.get(key).and_then(Value::as_str).unwrap_or_default().to_string();
    Ok(FabricMeta {
        version: text("version"),
        name: {
            let n = text("name");
            if n.is_empty() { id.clone() } else { n }
        },
        description: text("description"),
        provides: value
            .get("provides")
            .and_then(Value::as_array)
            .map(|a| a.iter().filter_map(Value::as_str).map(str::to_string).collect())
            .unwrap_or_default(),
        depends: deps(value.get("depends")),
        recommends: deps(value.get("recommends")),
        breaks: deps(value.get("breaks")),
        conflicts: deps(value.get("conflicts")),
        environment: value.get("environment").and_then(Value::as_str).unwrap_or("*").to_string(),
        bundled: Vec::new(),
        id,
    })
}

fn read_entry<R: Read + std::io::Seek>(archive: &mut ZipArchive<R>, name: &str, max: u64) -> Option<Vec<u8>> {
    let mut entry = archive.by_name(name).ok()?;
    if entry.size() > max {
        return None;
    }
    let mut bytes = Vec::with_capacity(entry.size() as usize);
    entry.read_to_end(&mut bytes).ok()?;
    Some(bytes)
}

fn bundled_of<R: Read + std::io::Seek>(archive: &mut ZipArchive<R>, value: &Value, depth: usize, out: &mut Vec<(String, String)>) {
    if depth >= MAX_NESTING {
        return;
    }
    let Some(jars) = value.get("jars").and_then(Value::as_array) else { return };
    for file in jars.iter().filter_map(|j| j.get("file").and_then(Value::as_str)) {
        let Some(bytes) = read_entry(archive, file, MAX_JAR_BYTES) else { continue };
        let Ok(mut inner) = ZipArchive::new(Cursor::new(bytes)) else { continue };
        let Some(json) = read_entry(&mut inner, "fabric.mod.json", MAX_METADATA_BYTES) else { continue };
        let Ok(v) = parse_json_lenient(&String::from_utf8_lossy(&json)) else { continue };
        if let Ok(m) = meta_from_value(&v) {
            out.push((m.id.clone(), m.version.clone()));
            for p in &m.provides {
                out.push((p.clone(), m.version.clone()));
            }
        }
        bundled_of(&mut inner, &v, depth + 1, out);
    }
}

/// Reads a jar's Fabric metadata, with the mods bundled inside it.
pub fn read_meta_bytes(bytes: &[u8]) -> Result<FabricMeta, MetaProblem> {
    if bytes.len() < 4 || bytes[0] != 0x50 || bytes[1] != 0x4B {
        return Err(MetaProblem::NotAJar("the file is not a jar (a zip); the download may be broken".into()));
    }
    let mut archive = ZipArchive::new(Cursor::new(bytes))
        .map_err(|e| MetaProblem::NotAJar(format!("the jar cannot be opened: {e}")))?;
    let Some(json) = read_entry(&mut archive, "fabric.mod.json", MAX_METADATA_BYTES) else {
        let what = if archive.by_name("quilt.mod.json").is_ok() {
            "a Quilt mod"
        } else if archive.by_name("META-INF/neoforge.mods.toml").is_ok() {
            "a NeoForge mod"
        } else if archive.by_name("META-INF/mods.toml").is_ok() {
            "a Forge mod"
        } else {
            "not a Fabric mod"
        };
        return Err(MetaProblem::NotFabric(what.into()));
    };
    let value = parse_json_lenient(&String::from_utf8_lossy(&json))
        .map_err(|e| MetaProblem::BadMetadata(format!("fabric.mod.json cannot be read: {e}")))?;
    let mut meta = meta_from_value(&value)?;
    let mut bundled = Vec::new();
    bundled_of(&mut archive, &value, 0, &mut bundled);
    meta.bundled = bundled;
    Ok(meta)
}

// ── Versions, as Fabric compares them ───────────────────────────────────────

/// A version Fabric would read as semantic: numbers, an optional pre-release
/// after `-`, build metadata after `+` (ignored when comparing).
#[derive(Clone, Debug, PartialEq)]
pub struct SemVer {
    parts: Vec<u64>,
    pre: Option<String>,
}

impl SemVer {
    pub fn parse(text: &str) -> Option<SemVer> {
        let text = text.trim();
        let core_and_pre = text.split('+').next()?;
        let (core, pre) = match core_and_pre.split_once('-') {
            Some((c, p)) => (c, Some(p.to_string())),
            None => (core_and_pre, None),
        };
        if core.is_empty() {
            return None;
        }
        let mut parts = Vec::new();
        for p in core.split('.') {
            if p.is_empty() || !p.chars().all(|c| c.is_ascii_digit()) {
                return None;
            }
            parts.push(p.parse().ok()?);
        }
        Some(SemVer { parts, pre })
    }

    fn part(&self, i: usize) -> u64 {
        self.parts.get(i).copied().unwrap_or(0)
    }

    pub fn cmp(&self, other: &SemVer) -> std::cmp::Ordering {
        use std::cmp::Ordering;
        let n = self.parts.len().max(other.parts.len());
        for i in 0..n {
            match self.part(i).cmp(&other.part(i)) {
                Ordering::Equal => {}
                o => return o,
            }
        }
        match (&self.pre, &other.pre) {
            (None, None) => Ordering::Equal,
            (None, Some(_)) => Ordering::Greater,
            (Some(_), None) => Ordering::Less,
            (Some(a), Some(b)) => cmp_pre(a, b),
        }
    }
}

fn cmp_pre(a: &str, b: &str) -> std::cmp::Ordering {
    use std::cmp::Ordering;
    let (xs, ys): (Vec<&str>, Vec<&str>) = (a.split('.').collect(), b.split('.').collect());
    for i in 0..xs.len().max(ys.len()) {
        let (Some(x), Some(y)) = (xs.get(i), ys.get(i)) else {
            return xs.len().cmp(&ys.len());
        };
        let o = match (x.parse::<u64>(), y.parse::<u64>()) {
            (Ok(p), Ok(q)) => p.cmp(&q),
            (Ok(_), Err(_)) => Ordering::Less,
            (Err(_), Ok(_)) => Ordering::Greater,
            (Err(_), Err(_)) => x.cmp(y),
        };
        if o != Ordering::Equal {
            return o;
        }
    }
    Ordering::Equal
}

/// Whether `version` meets one Fabric version predicate, such as ">=1.21",
/// "~0.16.9", "1.21.x", ">=0.5 <0.7" or "*". None when the predicate or the
/// version is not something Fabric would compare semantically and the strings
/// are not simply equal: unknown, not a failure.
pub fn satisfies(version: &str, predicate: &str) -> Option<bool> {
    let predicate = predicate.trim();
    if predicate.is_empty() || predicate == "*" {
        return Some(true);
    }
    let mut all = true;
    for term in predicate.split_whitespace() {
        match satisfies_term(version, term) {
            Some(true) => {}
            Some(false) => all = false,
            None => return if version.trim() == predicate { Some(true) } else { None },
        }
    }
    Some(all)
}

fn satisfies_term(version: &str, term: &str) -> Option<bool> {
    use std::cmp::Ordering::*;
    let (op, rest) = ["<=", ">=", "<", ">", "=", "~", "^"]
        .iter()
        .find_map(|op| term.strip_prefix(op).map(|r| (*op, r)))
        .unwrap_or(("", term));
    let v = SemVer::parse(version)?;
    // Wildcards: "1.21.x" or "1.x", only without another operator.
    if op.is_empty() || op == "=" {
        let pieces: Vec<&str> = rest.split('.').collect();
        if let Some(w) = pieces.iter().position(|p| *p == "x" || *p == "X" || *p == "*") {
            let fixed: Vec<u64> = pieces[..w].iter().map(|p| p.parse().ok()).collect::<Option<_>>()?;
            return Some(fixed.iter().enumerate().all(|(i, n)| v.part(i) == *n));
        }
    }
    let p = SemVer::parse(rest)?;
    Some(match op {
        "" | "=" => v.cmp(&p) == Equal,
        ">=" => v.cmp(&p) != Less,
        "<=" => v.cmp(&p) != Greater,
        ">" => v.cmp(&p) == Greater,
        "<" => v.cmp(&p) == Less,
        "~" => {
            // Same major and minor, at least this version.
            v.cmp(&p) != Less && v.part(0) == p.part(0) && (p.parts.len() < 2 || v.part(1) == p.part(1))
        }
        "^" => v.cmp(&p) != Less && v.part(0) == p.part(0),
        _ => return None,
    })
}

/// Any of a dependency's alternatives met. None when none is met and at least
/// one could not be judged.
pub fn satisfies_any(version: &str, any_of: &[String]) -> Option<bool> {
    if any_of.is_empty() {
        return Some(true);
    }
    let mut unknown = false;
    for p in any_of {
        match satisfies(version, p) {
            Some(true) => return Some(true),
            Some(false) => {}
            None => unknown = true,
        }
    }
    if unknown { None } else { Some(false) }
}

// ── The mods folder ─────────────────────────────────────────────────────────

#[derive(Clone, Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct LocalJar {
    /// The name the file has now (with .disabled when switched off).
    pub file_name: String,
    /// The name it has when switched on.
    pub jar_name: String,
    pub enabled: bool,
    pub size: u64,
    pub sha1: String,
    pub sha512: String,
    pub meta: Option<FabricMeta>,
    pub problem: Option<MetaProblem>,
}

impl LocalJar {
    pub fn mod_id(&self) -> Option<&str> {
        self.meta.as_ref().map(|m| m.id.as_str())
    }
}

fn hex(bytes: &[u8]) -> String {
    bytes.iter().map(|b| format!("{b:02x}")).collect()
}

/// Jars already read, by path, size and modification time: the Mods page
/// reads the folder again after every change, and hashing every jar each
/// time would cost a fraction of a second per hundred megabytes.
static READ_CACHE: std::sync::Mutex<Option<HashMap<PathBuf, (u64, SystemTime, LocalJar)>>> = std::sync::Mutex::new(None);

/// Reads one file of the mods folder.
pub fn read_jar(path: &Path) -> Option<LocalJar> {
    let stamp = fs::metadata(path).ok().and_then(|m| Some((m.len(), m.modified().ok()?)));
    if let Some((len, modified)) = stamp {
        if let Ok(cache) = READ_CACHE.lock() {
            if let Some((l, m, jar)) = cache.as_ref().and_then(|c| c.get(path)) {
                if *l == len && *m == modified {
                    return Some(jar.clone());
                }
            }
        }
    }
    let jar = read_jar_uncached(path)?;
    if let (Some((len, modified)), Ok(mut cache)) = (stamp, READ_CACHE.lock()) {
        let map = cache.get_or_insert_with(HashMap::new);
        // A folder of a few hundred jars at most; start over rather than grow.
        if map.len() > 2048 {
            map.clear();
        }
        map.insert(path.to_path_buf(), (len, modified, jar.clone()));
    }
    Some(jar)
}

fn read_jar_uncached(path: &Path) -> Option<LocalJar> {
    let file_name = path.file_name()?.to_string_lossy().to_string();
    let lower = file_name.to_ascii_lowercase();
    let enabled = lower.ends_with(".jar");
    if !enabled && !lower.ends_with(".jar.disabled") {
        return None;
    }
    let jar_name = if enabled { file_name.clone() } else { file_name[..file_name.len() - DISABLED_SUFFIX.len()].to_string() };
    let size = fs::metadata(path).map(|m| m.len()).unwrap_or(0);
    let (sha1, sha512, meta, problem) = if size > MAX_JAR_BYTES {
        (String::new(), String::new(), None, Some(MetaProblem::NotAJar("the file is too large to be a mod".into())))
    } else {
        match fs::read(path) {
            Ok(bytes) => {
                let sha1 = hex(&Sha1::digest(&bytes));
                let sha512 = hex(&Sha512::digest(&bytes));
                match read_meta_bytes(&bytes) {
                    Ok(m) => (sha1, sha512, Some(m), None),
                    Err(p) => (sha1, sha512, None, Some(p)),
                }
            }
            // A jar Minecraft holds open on Windows can still be read; anything
            // else (permissions) is reported, not skipped.
            Err(e) => (String::new(), String::new(), None, Some(MetaProblem::Unreadable(e.to_string()))),
        }
    };
    Some(LocalJar { file_name, jar_name, enabled, size, sha1, sha512, meta, problem })
}

/// Every mod file in the folder, switched on or off, sorted by name. A missing
/// folder is an empty one.
pub fn scan(mods_dir: &Path) -> Vec<LocalJar> {
    let mut jars: Vec<LocalJar> = fs::read_dir(mods_dir)
        .map(|entries| {
            entries
                .flatten()
                .map(|e| e.path())
                .filter(|p| p.is_file())
                .filter_map(|p| read_jar(&p))
                .collect()
        })
        .unwrap_or_default();
    jars.sort_by(|a, b| a.file_name.to_ascii_lowercase().cmp(&b.file_name.to_ascii_lowercase()));
    jars
}

#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DuplicateFile {
    pub file_name: String,
    pub version: String,
}

#[derive(Clone, Debug, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct DuplicateGroup {
    pub mod_id: String,
    pub name: String,
    pub files: Vec<DuplicateFile>,
}

/// Mod ids that more than one switched-on jar declares (as its id or in
/// `provides`), each with the files involved, newest version first. Fabric
/// refuses to start with any of these. `extra` is a jar Fabric is handed
/// besides the folder (the Breeze runtime).
pub fn duplicates(jars: &[LocalJar], extra: &[LocalJar]) -> Vec<DuplicateGroup> {
    let mut by_id: BTreeMap<String, Vec<(&LocalJar, &FabricMeta)>> = BTreeMap::new();
    for jar in jars.iter().filter(|j| j.enabled).chain(extra.iter()) {
        let Some(meta) = &jar.meta else { continue };
        let mut ids: Vec<&str> = vec![meta.id.as_str()];
        ids.extend(meta.provides.iter().map(String::as_str));
        ids.sort();
        ids.dedup();
        for id in ids {
            by_id.entry(id.to_string()).or_default().push((jar, meta));
        }
    }
    by_id
        .into_iter()
        .filter(|(_, v)| v.len() > 1)
        .map(|(id, mut v)| {
            v.sort_by(|a, b| match (SemVer::parse(&b.1.version), SemVer::parse(&a.1.version)) {
                (Some(x), Some(y)) => x.cmp(&y),
                _ => b.1.version.cmp(&a.1.version),
            });
            let name = v.iter().find(|(_, m)| m.id == id).map(|(_, m)| m.name.clone()).unwrap_or_else(|| id.clone());
            DuplicateGroup {
                mod_id: id,
                name,
                files: v
                    .into_iter()
                    .map(|(j, m)| DuplicateFile { file_name: j.file_name.clone(), version: m.version.clone() })
                    .collect(),
            }
        })
        .collect()
}

// ── Does each mod have what it needs ────────────────────────────────────────

/// The game a set of mods would run in.
#[derive(Clone, Debug)]
pub struct Environment {
    pub minecraft: String,
    pub loader: String,
    /// Java's major version, when known.
    pub java: Option<u32>,
}

#[derive(Clone, Debug, Serialize, serde::Deserialize, PartialEq)]
#[serde(tag = "kind", rename_all = "camelCase")]
pub enum Issue {
    /// Needs a mod that is not installed (or is switched off).
    MissingDependency { dependency: String, wants: Vec<String> },
    /// Needs a different version of something installed, or of Minecraft,
    /// Fabric Loader or Java.
    WrongVersion { dependency: String, wants: Vec<String>, found: String },
    /// Declares that it does not work with an installed mod at that version.
    Breaks { other: String, version: String },
    /// Declares a conflict: it may misbehave together with this mod.
    Conflicts { other: String, version: String },
    /// Two or more switched-on jars are this mod.
    Duplicate { files: Vec<String> },
}

/// Ids every Fabric game provides without a jar in the folder.
fn builtin(id: &str, env: &Environment) -> Option<String> {
    match id {
        "minecraft" => Some(env.minecraft.clone()),
        "fabricloader" | "fabric-loader" => Some(env.loader.clone()),
        "java" => env.java.map(|j| j.to_string()),
        // Bundled with Fabric Loader since 0.15.
        "mixinextras" if SemVer::parse(&env.loader).map_or(false, |v| v.cmp(&SemVer::parse("0.15.0").unwrap()).is_ge()) => {
            Some("0.3.5".into())
        }
        _ => None,
    }
}

/// The versions every switched-on mod (and everything bundled in one) answers to.
pub fn provided_versions(jars: &[LocalJar], extra: &[LocalJar]) -> HashMap<String, Vec<String>> {
    let mut out: HashMap<String, Vec<String>> = HashMap::new();
    for jar in jars.iter().filter(|j| j.enabled).chain(extra.iter()) {
        let Some(meta) = &jar.meta else { continue };
        out.entry(meta.id.clone()).or_default().push(meta.version.clone());
        for p in &meta.provides {
            out.entry(p.clone()).or_default().push(meta.version.clone());
        }
        for (id, version) in &meta.bundled {
            out.entry(id.clone()).or_default().push(version.clone());
        }
    }
    out
}

/// What is wrong for one mod in this folder, as Fabric would see it. Only
/// what can be judged from the files: a range that cannot be compared is left
/// out rather than reported.
pub fn issues_for(meta: &FabricMeta, env: &Environment, provided: &HashMap<String, Vec<String>>) -> Vec<Issue> {
    let mut issues = Vec::new();
    for dep in &meta.depends {
        let found: Vec<String> = match builtin(&dep.id, env) {
            Some(v) => vec![v],
            None => provided.get(&dep.id).cloned().unwrap_or_default(),
        };
        if found.is_empty() {
            // Java is not always known; anything else missing is missing.
            if dep.id != "java" {
                issues.push(Issue::MissingDependency { dependency: dep.id.clone(), wants: dep.any_of.clone() });
            }
            continue;
        }
        let ok = found.iter().map(|v| satisfies_any(v, &dep.any_of)).collect::<Vec<_>>();
        if ok.iter().any(|r| *r == Some(true)) || ok.iter().any(Option::is_none) {
            continue;
        }
        issues.push(Issue::WrongVersion { dependency: dep.id.clone(), wants: dep.any_of.clone(), found: found.join(", ") });
    }
    for (list, breaks) in [(&meta.breaks, true), (&meta.conflicts, false)] {
        for dep in list.iter() {
            let found: Vec<String> = match builtin(&dep.id, env) {
                Some(v) => vec![v],
                None => provided.get(&dep.id).cloned().unwrap_or_default(),
            };
            for v in found {
                if satisfies_any(&v, &dep.any_of) == Some(true) {
                    let issue = if breaks {
                        Issue::Breaks { other: dep.id.clone(), version: v }
                    } else {
                        Issue::Conflicts { other: dep.id.clone(), version: v }
                    };
                    issues.push(issue);
                }
            }
        }
    }
    issues
}

/// One other mod's problem that a change brings or takes away.
#[derive(Clone, Debug, Serialize, serde::Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct OtherIssue {
    pub mod_id: String,
    pub name: String,
    pub file_name: String,
    pub issue: Issue,
}

/// What putting `new_meta` in place of every jar of its mod id would do to
/// the folder, judged from the files as Fabric would judge them.
#[derive(Clone, Debug, Serialize, serde::Deserialize, PartialEq, Default)]
#[serde(rename_all = "camelCase")]
pub struct Impact {
    /// The new jar's own problems in the folder after the change.
    pub own: Vec<Issue>,
    /// Problems other mods would have afterwards that they do not have now.
    pub breaks: Vec<OtherIssue>,
    /// Problems other mods have now that the change takes away.
    pub fixes: Vec<OtherIssue>,
}

impl Impact {
    /// Whether Fabric would refuse the folder after the change where it would
    /// not before: a dependency the new jar lacks, or another mod it breaks.
    /// A declared conflict is a warning, as in Fabric.
    pub fn blocking(&self) -> bool {
        let hard = |i: &Issue| !matches!(i, Issue::Conflicts { .. });
        self.own.iter().any(hard) || self.breaks.iter().any(|o| hard(&o.issue))
    }
}

/// The change's effect on the folder. `enabled` is whether the new jar will
/// load (a switched-off mod stays off after a change, so it affects no one).
pub fn impact(jars: &[LocalJar], extra: &[LocalJar], new_meta: &FabricMeta, enabled: bool, env: &Environment) -> Impact {
    let same = |j: &LocalJar| j.meta.as_ref().map_or(false, |m| m.id == new_meta.id);
    let before = provided_versions(jars, extra);
    let mut after_jars: Vec<LocalJar> = jars.iter().filter(|j| !same(j)).cloned().collect();
    after_jars.push(LocalJar {
        file_name: String::new(),
        jar_name: String::new(),
        enabled,
        size: 0,
        sha1: String::new(),
        sha512: String::new(),
        meta: Some(new_meta.clone()),
        problem: None,
    });
    let after = provided_versions(&after_jars, extra);

    let mut out = Impact { own: issues_for(new_meta, env, &after), ..Impact::default() };
    if !enabled {
        return out;
    }
    for jar in jars.iter().filter(|j| j.enabled && !same(j)).chain(extra.iter()) {
        let Some(meta) = &jar.meta else { continue };
        let was = issues_for(meta, env, &before);
        let will = issues_for(meta, env, &after);
        let other = |issue: &Issue| OtherIssue {
            mod_id: meta.id.clone(),
            name: meta.name.clone(),
            file_name: jar.file_name.clone(),
            issue: issue.clone(),
        };
        out.breaks.extend(will.iter().filter(|i| !was.iter().any(|w| same_problem(w, i))).map(other));
        out.fixes.extend(was.iter().filter(|i| !will.iter().any(|w| same_problem(w, i))).map(other));
    }
    out
}

/// The same problem about the same mod, whatever version was found: a range
/// still unmet by a different wrong version is not news.
fn same_problem(a: &Issue, b: &Issue) -> bool {
    match (a, b) {
        (Issue::MissingDependency { dependency: x, .. }, Issue::MissingDependency { dependency: y, .. }) => x == y,
        (Issue::WrongVersion { dependency: x, .. }, Issue::WrongVersion { dependency: y, .. }) => x == y,
        (Issue::Breaks { other: x, .. }, Issue::Breaks { other: y, .. }) => x == y,
        (Issue::Conflicts { other: x, .. }, Issue::Conflicts { other: y, .. }) => x == y,
        (Issue::Duplicate { .. }, Issue::Duplicate { .. }) => true,
        _ => false,
    }
}

// ── Moving files safely ─────────────────────────────────────────────────────

fn now_stamp() -> String {
    let secs = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0);
    secs.to_string()
}

/// Explains a failed rename the way a player can act on.
pub fn explain_io(action: &str, path: &Path, error: &std::io::Error) -> String {
    let locked = matches!(error.raw_os_error(), Some(32) | Some(33)) || error.kind() == std::io::ErrorKind::PermissionDenied;
    if locked {
        format!(
            "Could not {action} {}: the file is in use or protected. Close Minecraft (and anything else that has the mods folder open), then try again.",
            path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default()
        )
    } else {
        format!("Could not {action} {}: {error}", path.display())
    }
}

/// Renames, or copies and removes when the two places are on different disks.
fn move_file(from: &Path, to: &Path) -> std::io::Result<()> {
    match fs::rename(from, to) {
        Ok(()) => Ok(()),
        Err(e) if e.raw_os_error() == Some(18) || e.raw_os_error() == Some(17) => {
            fs::copy(from, to)?;
            fs::remove_file(from)
        }
        Err(e) => Err(e),
    }
}

/// A new folder in the instance's backups for one operation, named by the
/// time and the reason (`1767225600-replaced`, then `-2` if that exists).
pub fn new_backup_dir(instance_dir: &Path, reason: &str) -> Result<PathBuf, String> {
    let root = instance_dir.join(BACKUP_DIR);
    let base = format!("{}-{}", now_stamp(), reason);
    let mut dir = root.join(&base);
    let mut n = 2;
    while dir.exists() {
        dir = root.join(format!("{base}-{n}"));
        n += 1;
    }
    fs::create_dir_all(&dir).map_err(|e| format!("Could not create the backup folder: {e}"))?;
    Ok(dir)
}

fn mods_file(mods_dir: &Path, file_name: &str) -> Result<PathBuf, String> {
    if file_name.is_empty() || file_name.contains('/') || file_name.contains('\\') || file_name.starts_with('.') {
        return Err(format!("{file_name} is not a file in the mods folder."));
    }
    let source = mods_dir.join(file_name);
    if !source.is_file() {
        return Err(format!("{file_name} is no longer in the mods folder."));
    }
    Ok(source)
}

/// Moves a file out of the mods folder into a backup folder made by
/// `new_backup_dir`, and says where it went.
pub fn move_into_backup(dir: &Path, mods_dir: &Path, file_name: &str) -> Result<PathBuf, String> {
    let source = mods_file(mods_dir, file_name)?;
    let mut target = dir.join(file_name);
    let mut n = 2;
    while target.exists() {
        target = dir.join(format!("{n}-{file_name}"));
        n += 1;
    }
    move_file(&source, &target).map_err(|e| explain_io("move", &source, &e))?;
    Ok(target)
}

/// Moves one file out of the mods folder into a backup folder of its own.
pub fn move_to_backup(instance_dir: &Path, mods_dir: &Path, file_name: &str, reason: &str) -> Result<PathBuf, String> {
    mods_file(mods_dir, file_name)?;
    let dir = new_backup_dir(instance_dir, reason)?;
    move_into_backup(&dir, mods_dir, file_name).map_err(|e| {
        let _ = fs::remove_dir(&dir);
        e
    })
}

/// A mod id as a player knows it, for the few the game itself provides.
fn shown_id(id: &str) -> String {
    match id {
        "minecraft" => "Minecraft".into(),
        "fabricloader" | "fabric-loader" => "Fabric Loader".into(),
        "java" => "Java".into(),
        "fabric-api" | "fabric" => "Fabric API".into(),
        other => other.to_string(),
    }
}

/// One problem in a sentence (without the mod it is about).
pub fn issue_text(issue: &Issue) -> String {
    let wants = |w: &[String]| {
        let w: Vec<&str> = w.iter().map(String::as_str).filter(|s| *s != "*").collect();
        if w.is_empty() { String::new() } else { format!(" {}", w.join(" or ")) }
    };
    match issue {
        Issue::MissingDependency { dependency, wants: w } => {
            format!("needs {}{}, which is not installed or is switched off", shown_id(dependency), wants(w))
        }
        Issue::WrongVersion { dependency, wants: w, found } => {
            format!("needs {}{}, but {found} is installed", shown_id(dependency), wants(w))
        }
        Issue::Breaks { other, version } => format!("does not work with {} {version}", shown_id(other)),
        Issue::Conflicts { other, version } => format!("may not work well with {} {version}", shown_id(other)),
        Issue::Duplicate { files } => format!("is installed more than once: {}", files.join(", ")),
    }
}

/// Switches a jar on or off: `name.jar` and `name.jar.disabled`, which
/// Fabric does not load. Returns the file's new name.
pub fn set_enabled(mods_dir: &Path, jar_name: &str, enabled: bool) -> Result<String, String> {
    let on = mods_dir.join(jar_name);
    let off = mods_dir.join(format!("{jar_name}{DISABLED_SUFFIX}"));
    let (from, to, name) = if enabled {
        (off, on, jar_name.to_string())
    } else {
        (on, off, format!("{jar_name}{DISABLED_SUFFIX}"))
    };
    if to.exists() && !from.exists() {
        return Ok(name);
    }
    if !from.exists() {
        return Err(format!("{jar_name} is not in the mods folder."));
    }
    if to.exists() {
        return Err(format!("Both {} and {} exist; remove one first.", jar_name, format!("{jar_name}{DISABLED_SUFFIX}")));
    }
    move_file(&from, &to).map_err(|e| explain_io(if enabled { "switch on" } else { "switch off" }, &from, &e))?;
    Ok(name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar_bytes(json: &str, nested: &[(&str, Vec<u8>)]) -> Vec<u8> {
        let mut buf = Cursor::new(Vec::new());
        {
            let mut zip = zip::ZipWriter::new(&mut buf);
            let opts = zip::write::SimpleFileOptions::default();
            zip.start_file("fabric.mod.json", opts).unwrap();
            zip.write_all(json.as_bytes()).unwrap();
            for (name, bytes) in nested {
                zip.start_file(*name, opts).unwrap();
                zip.write_all(bytes).unwrap();
            }
            zip.finish().unwrap();
        }
        buf.into_inner()
    }

    fn write_jar(dir: &Path, name: &str, json: &str) {
        fs::write(dir.join(name), jar_bytes(json, &[])).unwrap();
    }

    fn temp(name: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("breeze-mods-local-{name}-{}", now_stamp()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        d
    }

    fn env() -> Environment {
        Environment { minecraft: "1.21.11".into(), loader: "0.19.5".into(), java: Some(21) }
    }

    #[test]
    fn versions_compare_the_way_fabric_compares_them() {
        assert_eq!(satisfies("1.21.11", ">=1.21.10"), Some(true));
        assert_eq!(satisfies("1.21.11", "~1.21.10"), Some(true));
        assert_eq!(satisfies("1.22", "~1.21.10"), Some(false));
        assert_eq!(satisfies("1.21.11", "1.21.x"), Some(true));
        assert_eq!(satisfies("1.21.4", ">=1.21 <1.21.5"), Some(true));
        assert_eq!(satisfies("1.21.5", ">=1.21 <1.21.5"), Some(false));
        assert_eq!(satisfies("0.19.5", ">=0.16.10"), Some(true));
        assert_eq!(satisfies("0.134.1+1.21.9", ">=0.135.0"), Some(false));
        assert_eq!(satisfies("0.138.4+1.21.11", ">=0.135.0"), Some(true));
        assert_eq!(satisfies("1.0.0-beta.2", ">=1.0.0"), Some(false));
        assert_eq!(satisfies("2.0.0", "^1.4"), Some(false));
        assert_eq!(satisfies("anything", "*"), Some(true));
        assert_eq!(satisfies("mc1.21-0.6.3", ">=0.6"), None, "not semantic: unknown, not a failure");
        assert_eq!(satisfies("special", "special"), Some(true));
        assert_eq!(satisfies_any("1.21.11", &[">=1.22".into(), "1.21.11".into()]), Some(true));
    }

    #[test]
    fn every_jar_in_the_folder_is_found_whoever_put_it_there() {
        let dir = temp("scan");
        write_jar(&dir, "modmenu-15.0.0.jar", r#"{"id":"modmenu","version":"15.0.0","name":"Mod Menu","depends":{"minecraft":">=1.21.11","fabric-api":"*"}}"#);
        write_jar(&dir, "zoomify.jar.disabled", r#"{"id":"zoomify","version":"2.14.2+1.21.11"}"#);
        fs::write(dir.join("broken.jar"), b"PK\x03\x04 not really").unwrap();
        fs::write(dir.join("readme.txt"), b"not a mod").unwrap();
        let jars = scan(&dir);
        assert_eq!(jars.len(), 3, "the text file is not a mod file");
        let menu = jars.iter().find(|j| j.mod_id() == Some("modmenu")).unwrap();
        assert!(menu.enabled);
        assert_eq!(menu.sha1.len(), 40);
        assert_eq!(menu.sha512.len(), 128);
        let zoom = jars.iter().find(|j| j.mod_id() == Some("zoomify")).unwrap();
        assert!(!zoom.enabled);
        assert_eq!(zoom.jar_name, "zoomify.jar");
        let broken = jars.iter().find(|j| j.file_name == "broken.jar").unwrap();
        assert!(matches!(broken.problem, Some(MetaProblem::NotAJar(_))));
    }

    #[test]
    fn two_switched_on_copies_of_one_mod_are_duplicates_by_id_not_by_name() {
        let dir = temp("dupes");
        write_jar(&dir, "fabric-api-0.138.0+1.21.11.jar", r#"{"id":"fabric-api","version":"0.138.0+1.21.11","provides":["fabric"]}"#);
        write_jar(&dir, "fabric-api-0.139.1+1.21.11.jar", r#"{"id":"fabric-api","version":"0.139.1+1.21.11","provides":["fabric"]}"#);
        write_jar(&dir, "sodium-old.jar.disabled", r#"{"id":"sodium","version":"0.6.0"}"#);
        write_jar(&dir, "sodium-new.jar", r#"{"id":"sodium","version":"0.7.0"}"#);
        // Similar names, different mods: not duplicates.
        write_jar(&dir, "reeses-sodium-options.jar", r#"{"id":"reeses-sodium-options","version":"1.8.4"}"#);
        let groups = duplicates(&scan(&dir), &[]);
        let ids: Vec<&str> = groups.iter().map(|g| g.mod_id.as_str()).collect();
        assert_eq!(ids, vec!["fabric", "fabric-api"], "a switched-off copy does not count");
        let api = groups.iter().find(|g| g.mod_id == "fabric-api").unwrap();
        assert_eq!(api.files[0].version, "0.139.1+1.21.11", "newest first");
    }

    #[test]
    fn dependencies_count_bundled_mods_and_loader_builtins() {
        let dir = temp("deps");
        let inner = jar_bytes(r#"{"id":"fabric-resource-loader-v0","version":"3.1.0"}"#, &[]);
        fs::write(
            dir.join("fabric-api.jar"),
            jar_bytes(
                r#"{"id":"fabric-api","version":"0.139.1+1.21.11","jars":[{"file":"META-INF/jars/rl.jar"}]}"#,
                &[("META-INF/jars/rl.jar", inner)],
            ),
        )
        .unwrap();
        write_jar(
            &dir,
            "zoomify.jar",
            r#"{"id":"zoomify","version":"2.14","depends":{"minecraft":"~1.21.11","fabricloader":">=0.16","fabric-resource-loader-v0":"*","yet_another_config_lib_v3":">=3.6","java":">=21","mixinextras":"*"},"breaks":{"optifabric":"*"}}"#,
        );
        write_jar(&dir, "old.jar", r#"{"id":"old","version":"1","depends":{"minecraft":"1.20.1","fabric-api":">=0.200"}}"#);
        let jars = scan(&dir);
        let provided = provided_versions(&jars, &[]);
        let zoom = jars.iter().find(|j| j.mod_id() == Some("zoomify")).unwrap().meta.as_ref().unwrap();
        assert_eq!(
            issues_for(zoom, &env(), &provided),
            vec![Issue::MissingDependency { dependency: "yet_another_config_lib_v3".into(), wants: vec![">=3.6".into()] }],
            "bundled Fabric API modules, the loader, Java and MixinExtras count as present"
        );
        let old = jars.iter().find(|j| j.mod_id() == Some("old")).unwrap().meta.as_ref().unwrap();
        let issues = issues_for(old, &env(), &provided);
        assert!(issues.contains(&Issue::WrongVersion { dependency: "minecraft".into(), wants: vec!["1.20.1".into()], found: "1.21.11".into() }));
        assert!(issues.contains(&Issue::WrongVersion { dependency: "fabric-api".into(), wants: vec![">=0.200".into()], found: "0.139.1+1.21.11".into() }));
    }

    #[test]
    fn a_duplicate_moves_to_the_backup_folder_and_can_be_switched_off_and_on() {
        let instance = temp("backup");
        let mods = instance.join("mods");
        fs::create_dir_all(&mods).unwrap();
        write_jar(&mods, "a.jar", r#"{"id":"a","version":"1"}"#);
        let moved = move_to_backup(&instance, &mods, "a.jar", "duplicate").unwrap();
        assert!(moved.is_file());
        assert!(!mods.join("a.jar").exists());
        assert!(moved.starts_with(instance.join(BACKUP_DIR)));
        assert!(move_to_backup(&instance, &mods, "../outside.jar", "x").is_err());

        write_jar(&mods, "b.jar", r#"{"id":"b","version":"1"}"#);
        assert_eq!(set_enabled(&mods, "b.jar", false).unwrap(), "b.jar.disabled");
        assert!(mods.join("b.jar.disabled").is_file() && !mods.join("b.jar").exists());
        assert_eq!(set_enabled(&mods, "b.jar", false).unwrap(), "b.jar.disabled", "already off");
        assert_eq!(set_enabled(&mods, "b.jar", true).unwrap(), "b.jar");
        assert!(mods.join("b.jar").is_file());
    }

    #[test]
    fn a_version_change_shows_which_other_mods_it_breaks_or_fixes() {
        let dir = temp("impact");
        write_jar(&dir, "sodium.jar", r#"{"id":"sodium","version":"0.6.13+mc1.21.11"}"#);
        write_jar(&dir, "rso.jar", r#"{"id":"reeses-sodium-options","version":"1.8.4","depends":{"sodium":">=0.6.0 <0.7.0"}}"#);
        write_jar(&dir, "old-addon.jar", r#"{"id":"addon","version":"1","depends":{"sodium":">=0.7.0"}}"#);
        let jars = scan(&dir);
        let meta = |json: &str| read_meta_bytes(&jar_bytes(json, &[])).unwrap();

        // Sodium 0.7 suits the addon and leaves Reese's Sodium Options without a fitting Sodium.
        let up = impact(&jars, &[], &meta(r#"{"id":"sodium","version":"0.7.0+mc1.21.11","depends":{"minecraft":"~1.21.11"}}"#), true, &env());
        assert!(up.own.is_empty());
        assert_eq!(up.breaks.len(), 1);
        assert_eq!(up.breaks[0].mod_id, "reeses-sodium-options");
        assert!(matches!(up.breaks[0].issue, Issue::WrongVersion { ref dependency, .. } if dependency == "sodium"));
        assert_eq!(up.fixes.len(), 1);
        assert_eq!(up.fixes[0].mod_id, "addon");
        assert!(up.blocking());

        // A version for another Minecraft is the new jar's own problem.
        let wrong = impact(&jars, &[], &meta(r#"{"id":"sodium","version":"0.6.0","depends":{"minecraft":"1.21.4"}}"#), true, &env());
        assert!(matches!(wrong.own[0], Issue::WrongVersion { ref dependency, .. } if dependency == "minecraft"));
        assert!(wrong.breaks.is_empty(), "the addon was already broken; still broken is not news");

        // A switched-off mod stays off, so it affects nobody else.
        let off = impact(&jars, &[], &meta(r#"{"id":"sodium","version":"0.7.0"}"#), false, &env());
        assert!(off.breaks.is_empty() && off.fixes.is_empty());

        // A declared conflict is a warning, not a refusal.
        let warn = Impact {
            own: vec![Issue::Conflicts { other: "optifabric".into(), version: "1".into() }],
            ..Impact::default()
        };
        assert!(!warn.blocking());
    }

    #[test]
    fn metadata_with_a_raw_line_break_or_a_comment_still_reads() {
        let m = read_meta_bytes(&jar_bytes("{\"id\":\"x\",\"version\":\"1\",\n// note\n\"description\":\"two\nlines\"}", &[])).unwrap();
        assert_eq!(m.id, "x");
        assert_eq!(m.description, "two lines");
        assert!(matches!(read_meta_bytes(b"nope"), Err(MetaProblem::NotAJar(_))));
    }
}
