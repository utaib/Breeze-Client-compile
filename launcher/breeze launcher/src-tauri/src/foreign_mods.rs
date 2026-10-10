//! Mods from other launchers, brought over as Breeze installs.
//!
//! Every launcher keeps mods somewhere else, and none of them documents it in
//! a way that stays true: Lunar keeps user Fabric mods per profile and version
//! under ~/.lunarclient, Feather under its own data folder per version,
//! CurseForge and Prism per instance. So instead of trusting one path per
//! launcher, the import walks the launcher's whole data folder (skipping game
//! assets, runtimes, caches, worlds and packs) and keeps every jar that is a
//! real Fabric mod, read from its own fabric.mod.json.
//!
//! A jar is not copied as it is: it was built for whatever Minecraft version
//! that launcher ran, which is rarely the one being imported into. Each mod is
//! identified on Modrinth instead, by the exact file hash first and by its mod
//! id second, and the build of that project for the chosen version is
//! installed with its dependencies, the same way the Mods page installs one.
//! A mod that is not on Modrinth is copied only when its own metadata says it
//! runs on the chosen version.

use super::*;

/// Folder names under another launcher's data that never hold the player's
/// mods. Compared without case.
const SKIP_DIRS: &[&str] = &[
    "assets", "libraries", "jre", "jdk", "java", "runtime", "runtimes", "natives", "logs",
    "crash-reports", "cache", "caches", ".cache", "gpucache", "code cache", "webcache",
    "blob_storage", "local storage", "session storage", "screenshots", "saves", "resourcepacks",
    "shaderpacks", "texturepacks", "versions", "offline", "licenses", "textures", "backups",
    "meta", "icons", "node_modules", ".git", "temp", "tmp", "downloads", "replay_recordings",
    "schematics", "bin", "launcher", "updater", "installer", "jvm", ".fabric",
];

/// How deep and how wide one walk may go, so a launcher folder that happens to
/// sit next to something huge cannot stall the import.
const MAX_DEPTH: usize = 8;
const MAX_DIRS: usize = 20_000;
const MAX_JARS: usize = 3_000;

/// Mod ids that are never imported: the loader, the game, Fabric API (the
/// launcher installs its own), Breeze and its browser, and the other clients'
/// own internals.
pub(crate) fn is_skipped_mod_id(id: &str) -> bool {
    let id = id.to_ascii_lowercase();
    matches!(
        id.as_str(),
        "minecraft" | "java" | "fabricloader" | "fabric-loader" | "quilt_loader" | "fabric-api" | "fabric"
            | "mcef" | "rinku" | "breeze" | "ichor" | "optifabric" | "optifine"
    ) || id.starts_with("fabric-")
        || id.starts_with("lunar")
        || id.starts_with("feather")
        || id.starts_with("badlion")
}

/// One Fabric mod found in another launcher's folders.
#[derive(Clone, Debug)]
pub(crate) struct ForeignJar {
    pub path: PathBuf,
    pub jar: mods_local::LocalJar,
}

impl ForeignJar {
    fn id(&self) -> &str {
        self.jar.mod_id().unwrap_or_default()
    }
    fn name(&self) -> String {
        self.jar.meta.as_ref().map(|m| m.name.clone()).filter(|n| !n.trim().is_empty()).unwrap_or_else(|| self.id().to_string())
    }
    /// Whether the jar's own metadata accepts this Minecraft version: Some(true)
    /// or Some(false) when it names one, None when it does not say.
    fn runs_on(&self, game_version: &str) -> Option<bool> {
        let meta = self.jar.meta.as_ref()?;
        let dep = meta.depends.iter().find(|d| d.id == "minecraft")?;
        mods_local::satisfies_any(game_version, &dep.any_of)
    }
}

/// What the walk found: Fabric mods, and how many jars were something else
/// (Forge mods, a client's own files, broken downloads).
#[derive(Default, Debug)]
pub(crate) struct Found {
    pub mods: Vec<ForeignJar>,
    pub other_jars: usize,
}

/// Every jar file (.jar, not .jar.disabled) under the given folders, minus the
/// folders in SKIP_DIRS, within the depth and size limits.
fn walk_jars(roots: &[PathBuf]) -> Vec<PathBuf> {
    let mut jars = Vec::new();
    let mut seen_dirs: HashSet<PathBuf> = HashSet::new();
    let mut visited = 0usize;
    let mut stack: Vec<(PathBuf, usize)> = roots.iter().filter(|r| r.is_dir()).map(|r| (r.clone(), 0)).collect();
    while let Some((dir, depth)) = stack.pop() {
        visited += 1;
        if visited > MAX_DIRS || jars.len() >= MAX_JARS {
            break;
        }
        let key = fs::canonicalize(&dir).unwrap_or_else(|_| dir.clone());
        if !seen_dirs.insert(key) {
            continue;
        }
        let Ok(entries) = fs::read_dir(&dir) else { continue };
        for entry in entries.flatten() {
            let path = entry.path();
            let Ok(kind) = entry.file_type() else { continue };
            if kind.is_symlink() {
                continue;
            }
            if kind.is_dir() {
                let name = entry.file_name().to_string_lossy().to_ascii_lowercase();
                if depth + 1 <= MAX_DEPTH && !SKIP_DIRS.contains(&name.as_str()) {
                    stack.push((path, depth + 1));
                }
            } else if entry.file_name().to_string_lossy().to_ascii_lowercase().ends_with(".jar") {
                jars.push(path);
            }
        }
    }
    jars
}

/// How many jar files an import of these folders would look at.
pub(crate) fn count_jar_files(roots: &[PathBuf]) -> usize {
    walk_jars(roots).len()
}

/// Walks the given folders for Fabric mod jars. Switched-off jars
/// (.jar.disabled) are left out: the player turned them off there.
pub(crate) fn find_mod_jars(roots: &[PathBuf]) -> Found {
    let mut found = Found::default();
    for path in walk_jars(roots) {
        match mods_local::read_jar(&path) {
            Some(jar) if jar.meta.is_some() => {
                if !is_skipped_mod_id(jar.mod_id().unwrap_or_default()) {
                    found.mods.push(ForeignJar { path, jar });
                }
            }
            _ => found.other_jars += 1,
        }
    }
    found.mods.sort_by(|a, b| a.id().cmp(b.id()).then_with(|| a.path.cmp(&b.path)));
    found
}

/// One mod id, with every jar of it that was found (several instances often
/// hold several versions of the same mod).
pub(crate) fn group_by_id(mods: Vec<ForeignJar>) -> Vec<(String, Vec<ForeignJar>)> {
    let mut groups: Vec<(String, Vec<ForeignJar>)> = Vec::new();
    for jar in mods {
        let id = jar.id().to_string();
        match groups.iter_mut().find(|(g, _)| *g == id) {
            Some((_, list)) => list.push(jar),
            None => groups.push((id, vec![jar])),
        }
    }
    groups
}

/// Folders to search for one launcher, those that exist. Each launcher gets
/// every place it is known to use on this system, including Flatpak installs.
pub(crate) fn client_roots(client_id: &str) -> Vec<PathBuf> {
    let home = home_dir().unwrap_or_default();
    let appdata = std::env::var_os("APPDATA").map(PathBuf::from);
    let mac_support = home.join("Library").join("Application Support");
    let xdg_data = std::env::var_os("XDG_DATA_HOME").map(PathBuf::from).unwrap_or_else(|| home.join(".local").join("share"));
    let mut roots: Vec<PathBuf> = Vec::new();
    match client_id {
        "vanilla" => {
            if let Ok(mc) = minecraft_home_dir() {
                roots.push(mc.join("mods"));
            }
        }
        "lunar" => {
            roots.push(home.join(".lunarclient"));
            roots.push(home.join(".var").join("app").join("com.lunarclient.LunarClient").join(".lunarclient"));
            if let Some(a) = &appdata {
                roots.push(a.join(".lunarclient"));
            }
            roots.push(mac_support.join("lunarclient"));
        }
        "feather" => {
            if let Some(a) = &appdata {
                roots.push(a.join(".feather"));
            }
            roots.push(home.join(".feather"));
            roots.push(mac_support.join(".feather"));
            roots.push(mac_support.join("feather"));
        }
        "badlion" => {
            if let Ok(mc) = minecraft_home_dir() {
                roots.push(mc.join("BadlionClient"));
            }
            if let Some(a) = &appdata {
                roots.push(a.join("Badlion Client"));
            }
            roots.push(mac_support.join("Badlion Client"));
            roots.push(home.join(".config").join("Badlion Client"));
        }
        "modrinth" => {
            if let Ok(root) = modrinth_app_root_dir() {
                roots.push(root.join("profiles"));
            }
        }
        "curseforge" => {
            roots.push(home.join("curseforge").join("minecraft").join("Instances"));
            roots.push(home.join("Documents").join("curseforge").join("minecraft").join("Instances"));
        }
        "prism" => {
            if let Some(a) = &appdata {
                roots.push(a.join("PrismLauncher").join("instances"));
            }
            roots.push(mac_support.join("PrismLauncher").join("instances"));
            roots.push(xdg_data.join("PrismLauncher").join("instances"));
            roots.push(home.join(".var").join("app").join("org.prismlauncher.PrismLauncher").join("data").join("PrismLauncher").join("instances"));
        }
        _ => {}
    }
    let mut unique: Vec<PathBuf> = Vec::new();
    for root in roots {
        if root.is_dir() && !unique.contains(&root) {
            unique.push(root);
        }
    }
    unique
}

/// What an import did, for the settings screen.
#[derive(Serialize, Default, Debug)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ForeignModImport {
    pub client: String,
    pub client_found: bool,
    pub target_version: String,
    pub folders_searched: usize,
    pub mods_found: usize,
    pub other_jars: usize,
    /// Installed from Modrinth, as "Title version".
    pub installed: Vec<String>,
    pub already_installed: Vec<String>,
    /// Not on Modrinth, and their own jar runs on the chosen version.
    pub copied: Vec<String>,
    /// Neither a Modrinth build nor their own jar fits the chosen version.
    pub unavailable: Vec<String>,
    pub warnings: Vec<String>,
}

#[derive(Deserialize)]
struct VersionFile {
    project_id: String,
}

/// Modrinth's projects for these SHA-1 hashes. Hashes Modrinth does not know
/// are simply absent. Network trouble is an error, so it is not mistaken for
/// "not on Modrinth".
async fn projects_by_hash(client: &reqwest::Client, hashes: &[String]) -> Result<HashMap<String, String>, String> {
    let mut out = HashMap::new();
    for chunk in hashes.chunks(100) {
        let response = client
            .post(format!("{MODRINTH_API_BASE_URL}/version_files"))
            .json(&json!({ "hashes": chunk, "algorithm": "sha1" }))
            .send()
            .await
            .map_err(|error| format!("Could not reach Modrinth: {error}"))?;
        if !response.status().is_success() {
            return Err(format!("Modrinth answered {} to a file lookup", response.status()));
        }
        let found: HashMap<String, VersionFile> = response.json().await.map_err(|error| format!("Could not read Modrinth's answer: {error}"))?;
        for (hash, version) in found {
            out.insert(hash, version.project_id);
        }
    }
    Ok(out)
}

/// A Fabric mod project on Modrinth whose slug is this mod id, or None.
async fn project_by_mod_id(client: &reqwest::Client, mod_id: &str) -> Option<String> {
    let mut candidates = vec![mod_id.to_ascii_lowercase()];
    let dashed = mod_id.to_ascii_lowercase().replace('_', "-");
    if !candidates.contains(&dashed) {
        candidates.push(dashed);
    }
    for slug in candidates {
        if slug.is_empty() || !slug.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_' || c == '.') {
            continue;
        }
        let Ok(project) = fetch_json::<Value>(client, &format!("{MODRINTH_API_BASE_URL}/project/{slug}")).await else { continue };
        let is_mod = project.get("project_type").and_then(Value::as_str) == Some("mod");
        let fabric = project
            .get("loaders")
            .and_then(Value::as_array)
            .map(|l| l.iter().filter_map(Value::as_str).any(|x| x == "fabric" || x == "quilt"))
            .unwrap_or(false);
        if is_mod && fabric {
            if let Some(id) = project.get("id").and_then(Value::as_str) {
                return Some(id.to_string());
            }
        }
    }
    None
}

/// Finds the mods of one launcher and installs them for `target_version`.
pub(crate) async fn import_client_mods(app: &AppHandle, client_id: &str, target_version: &str) -> Result<ForeignModImport, String> {
    let target_version = target_version.trim();
    if target_version.is_empty() {
        return Err("Pick the Minecraft version to import into first.".into());
    }
    let roots = client_roots(client_id);
    let mut report = ForeignModImport {
        client: client_id.to_string(),
        client_found: !roots.is_empty(),
        target_version: target_version.to_string(),
        folders_searched: roots.len(),
        ..Default::default()
    };
    if roots.is_empty() {
        return Ok(report);
    }
    let found = find_mod_jars(&roots);
    report.other_jars = found.other_jars;
    let groups = group_by_id(found.mods);
    report.mods_found = groups.len();
    if groups.is_empty() {
        return Ok(report);
    }

    ensure_instance_structure(app, target_version)?;
    let mods_dir = profile_mods_dir(app, target_version)?;
    fs::create_dir_all(&mods_dir).map_err(|error| format!("Could not create the mods folder: {error}"))?;
    let manifest_path = profile_manifest_path(app, target_version)?;
    let client = http_client()?;

    let hashes: Vec<String> = groups.iter().flat_map(|(_, jars)| jars.iter().map(|j| j.jar.sha1.clone())).filter(|h| !h.is_empty()).collect();
    let by_hash = projects_by_hash(&client, &hashes).await?;

    // Fabric API is put in place by the launch itself; keeping it out of the
    // dependency walk avoids a second copy of it.
    let mut seen: HashSet<String> = HashSet::new();
    if mods_folder_has_mod(&mods_dir, "fabric-api") || mods_folder_has_mod(&mods_dir, "fabric") {
        seen.insert(FABRIC_API_PROJECT_ID.to_string());
    }
    let mut done_projects: HashSet<String> = HashSet::new();

    for (mod_id, jars) in groups {
        let title = jars[0].name();
        if mods_folder_has_mod(&mods_dir, &mod_id) {
            report.already_installed.push(title);
            continue;
        }
        let exact = jars.iter().find_map(|j| by_hash.get(&j.jar.sha1).cloned());
        let project = match exact {
            Some(p) => Some(p),
            None => project_by_mod_id(&client, &mod_id).await,
        };
        let by_slug_only = project.is_some() && jars.iter().all(|j| !by_hash.contains_key(&j.jar.sha1));

        if let Some(project_id) = project {
            if !done_projects.insert(project_id.clone()) {
                continue;
            }
            let request = InstallModRequest {
                profile_id: target_version.to_string(),
                game_version: target_version.to_string(),
                loader: "fabric".into(),
                project_id: project_id.clone(),
                project_slug: String::new(),
                title: title.clone(),
                summary: None,
                icon_url: None,
            };
            match install_modrinth_project_recursive(&client, app, target_version, target_version, "fabric", request, &mut seen).await {
                Ok(record) => {
                    // Found by slug alone, the project could be a different mod
                    // that shares the name: the installed jar must carry the id.
                    if by_slug_only && !installed_jar_is(&mods_dir, &record.file_name, &mod_id) {
                        let _ = fs::remove_file(mods_dir.join(&record.file_name));
                        if let Ok(mut manifest) = read_mod_manifest(&manifest_path) {
                            manifest.retain(|r| r.file_name != record.file_name);
                            let _ = write_mod_manifest(&manifest_path, &manifest);
                        }
                    } else {
                        report.installed.push(format!("{} {}", record.title, record.installed_version_name));
                        continue;
                    }
                }
                Err(error) => {
                    let lower = error.to_ascii_lowercase();
                    if lower.contains("could not reach") || lower.contains("timed out") {
                        report.warnings.push(format!("{title}: {error}"));
                        continue;
                    }
                }
            }
        }

        // Not on Modrinth, or Modrinth has no build for this version: the jar
        // itself, if it says it runs here.
        match jars.iter().find(|j| j.runs_on(target_version) == Some(true)) {
            Some(jar) => match copy_jar(&mods_dir, &manifest_path, jar, target_version) {
                Ok(()) => report.copied.push(title),
                Err(error) => report.warnings.push(format!("{title}: {error}")),
            },
            None => report.unavailable.push(title),
        }
    }
    Ok(report)
}

fn installed_jar_is(mods_dir: &Path, file_name: &str, mod_id: &str) -> bool {
    mods_local::read_jar(&mods_dir.join(file_name))
        .and_then(|j| j.meta)
        .map(|m| m.id == mod_id || m.provides.iter().any(|p| p == mod_id))
        .unwrap_or(false)
}

fn copy_jar(mods_dir: &Path, manifest_path: &Path, jar: &ForeignJar, game_version: &str) -> Result<(), String> {
    let file_name = jar.jar.jar_name.clone();
    let dest = mods_dir.join(&file_name);
    if dest.exists() {
        return Err("a different file with the same name is already in the mods folder".into());
    }
    fs::copy(&jar.path, &dest).map_err(|error| format!("could not copy it: {error}"))?;
    let clean = jar.id().to_string();
    let mut manifest = read_mod_manifest(manifest_path)?;
    upsert_managed_mod(
        &mut manifest,
        ManagedModRecord {
            project_id: format!("custom:{clean}"),
            project_slug: format!("custom:{clean}"),
            title: jar.name(),
            summary: Some("Imported from another launcher".into()),
            icon_url: None,
            game_version: game_version.to_string(),
            loader: "fabric".into(),
            installed_version_id: "imported".into(),
            installed_version_name: jar.jar.meta.as_ref().map(|m| m.version.clone()).unwrap_or_default(),
            file_name,
            enabled: true,
            canonical_id: format!("custom:{clean}"),
        },
    );
    write_mod_manifest(manifest_path, &manifest)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn jar(path: &Path, id: &str, minecraft: Option<&str>) {
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let file = fs::File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        zip.start_file("fabric.mod.json", zip::write::SimpleFileOptions::default()).unwrap();
        let depends = minecraft.map(|m| format!(", \"depends\": {{\"minecraft\": \"{m}\"}}")).unwrap_or_default();
        zip.write_all(format!("{{\"schemaVersion\": 1, \"id\": \"{id}\", \"version\": \"1.0\", \"name\": \"{id} mod\"{depends}}}").as_bytes()).unwrap();
        zip.finish().unwrap();
    }

    #[test]
    fn finds_fabric_mods_where_each_launcher_keeps_them_and_nothing_else() {
        let root = std::env::temp_dir().join(format!("breeze-foreign-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        // Lunar: profiles/<profile>/<major>/mods/fabric-<version>/
        jar(&root.join("lunar/profiles/lunar/1.21/mods/fabric-1.21.4/sodium-0.6.jar"), "sodium", Some(">=1.21.4"));
        // Lunar's own client files live under offline/ and are skipped.
        jar(&root.join("lunar/offline/multiver/lunar-prod.jar"), "sodium", None);
        // Feather: user-mods/<version>-fabric/
        jar(&root.join("feather/user-mods/1.20.1-fabric/zoomify.jar"), "zoomify", Some("~1.20.1"));
        jar(&root.join("feather/user-mods/1.20.1-fabric/feather-core.jar"), "feather", None);
        // Prism: instances/<name>/.minecraft/mods/
        jar(&root.join("prism/instances/Pack/.minecraft/mods/lithium.jar"), "lithium", Some("1.21.1"));
        jar(&root.join("prism/instances/Pack/.minecraft/mods/fabric-api.jar"), "fabric-api", None);
        // A switched-off mod, a Forge jar and a world's datapack jar.
        jar(&root.join("prism/instances/Pack/.minecraft/mods/iris.jar.disabled"), "iris", None);
        fs::write(root.join("prism/instances/Pack/.minecraft/mods/forge-only.jar"), "not a fabric mod").unwrap();
        jar(&root.join("prism/instances/Pack/.minecraft/saves/World/datapacks/thing.jar"), "thing", None);

        let found = find_mod_jars(&[root.join("lunar"), root.join("feather"), root.join("prism"), root.join("missing")]);
        let ids: Vec<&str> = found.mods.iter().map(|m| m.id()).collect();
        assert_eq!(ids, vec!["lithium", "sodium", "zoomify"]);
        assert_eq!(found.other_jars, 1, "the Forge jar is counted, not imported");

        let groups = group_by_id(found.mods);
        let zoomify = &groups.iter().find(|(id, _)| id == "zoomify").unwrap().1[0];
        assert_eq!(zoomify.runs_on("1.20.1"), Some(true));
        assert_eq!(zoomify.runs_on("1.21.4"), Some(false));
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn the_same_mod_from_two_places_is_one_mod() {
        let root = std::env::temp_dir().join(format!("breeze-foreign-dup-{}", std::process::id()));
        let _ = fs::remove_dir_all(&root);
        jar(&root.join("a/mods/sodium-1.jar"), "sodium", Some("1.20.1"));
        jar(&root.join("b/mods/sodium-2.jar"), "sodium", Some("1.21.4"));
        let groups = group_by_id(find_mod_jars(&[root.clone()]).mods);
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].1.len(), 2);
        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn other_clients_internals_and_the_loader_are_never_imported() {
        for id in ["fabricloader", "minecraft", "fabric-api", "fabric-rendering-v1", "lunar-client", "feather", "badlion-core", "mcef", "breeze", "optifabric"] {
            assert!(is_skipped_mod_id(id), "{id}");
        }
        for id in ["sodium", "iris", "modmenu", "essential", "zoomify"] {
            assert!(!is_skipped_mod_id(id), "{id}");
        }
    }

    /// Needs the network: run with `cargo test -- --ignored live_`.
    #[test]
    #[ignore]
    fn live_a_jar_is_found_on_modrinth_by_its_hash_and_by_its_id() {
        tauri::async_runtime::block_on(async {
            let client = http_client().unwrap();
            // A real Sodium file: its SHA-1 as Modrinth lists it must lead back
            // to the Sodium project.
            let versions = fetch_json::<Value>(&client, &format!("{MODRINTH_API_BASE_URL}/project/sodium/version?loaders=%5B%22fabric%22%5D&game_versions=%5B%221.21.4%22%5D")).await.unwrap();
            let sha1 = versions[0]["files"][0]["hashes"]["sha1"].as_str().unwrap().to_string();
            let found = projects_by_hash(&client, &[sha1.clone(), "0".repeat(40)]).await.unwrap();
            assert_eq!(found.get(&sha1).map(String::as_str), Some("AANobbMI"));
            assert_eq!(found.len(), 1, "an unknown hash is simply absent");
            assert_eq!(project_by_mod_id(&client, "sodium").await.as_deref(), Some("AANobbMI"));
            assert_eq!(project_by_mod_id(&client, "lithium").await.as_deref(), Some("gvQqBUqZ"));
            assert_eq!(project_by_mod_id(&client, "no_such_mod_breeze_test").await, None);
        });
    }
}
