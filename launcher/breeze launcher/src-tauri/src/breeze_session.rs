//! The Breeze jar's life on Forge and NeoForge (launcher 1.2.2).
//!
//! Fabric is handed Breeze from `<instance>/.breeze` with `-Dfabric.addMods`,
//! so nothing of it is ever in the mods folder. Forge and NeoForge only load
//! mods from the mods folder, so there the jar has to sit in it while the game
//! runs. This module keeps the player from finding it there:
//!
//! - while the game runs the file is hidden from the file manager (Windows:
//!   hidden and system, which Explorer does not show even with hidden files
//!   on; macOS: the Finder's hidden flag; Linux: the folder's `.hidden` list,
//!   which GNOME, KDE and Xfce read). Forge 1.17 to 1.21.5 and NeoForge list
//!   every `.jar` in the folder whatever its attributes, so the game still
//!   loads it;
//! - when the game exits or crashes the launcher deletes it (retrying while
//!   Windows or a virus scanner still holds it for a moment);
//! - a copy left behind because the launcher itself was closed or stopped
//!   first is deleted the next time the launcher starts, unless the game that
//!   uses it is still running (`<instance>/.breeze/game.pid`).

use std::fs;
use std::path::{Path, PathBuf};
use std::thread;
use std::time::Duration;

/// The running game's process id, written when a Forge or NeoForge game with
/// Breeze in its mods folder starts and removed when it ends.
pub const GAME_PID_FILE: &str = "game.pid";

/// The Linux file managers' list of names to hide in a folder.
const HIDDEN_LIST: &str = ".hidden";

/// Hides `path` from the file manager. False when the system refused, which
/// costs only the hiding: the file still works.
pub fn hide(path: &Path) -> bool {
    #[cfg(windows)]
    {
        windows_attributes(path, true)
    }
    #[cfg(target_os = "macos")]
    {
        std::process::Command::new("chflags")
            .arg("hidden")
            .arg(path)
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map(|status| status.success())
            .unwrap_or(false)
    }
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        add_to_hidden_list(path)
    }
}

/// Makes a hidden file an ordinary one again, which Windows needs before the
/// file can be replaced: it refuses to overwrite a hidden or system file.
pub fn unhide(path: &Path) {
    #[cfg(windows)]
    {
        if path.exists() {
            windows_attributes(path, false);
        }
    }
    #[cfg(not(windows))]
    {
        let _ = path;
    }
}

/// Forgets a deleted file: on Linux its line in the folder's `.hidden` list
/// goes (and the list with it once empty).
pub fn forget(path: &Path) {
    #[cfg(not(any(windows, target_os = "macos")))]
    {
        remove_from_hidden_list(path);
    }
    #[cfg(any(windows, target_os = "macos"))]
    {
        let _ = path;
    }
}

#[cfg(windows)]
fn windows_attributes(path: &Path, hidden: bool) -> bool {
    use std::os::windows::ffi::OsStrExt;
    use windows_sys::Win32::Storage::FileSystem::{
        SetFileAttributesW, FILE_ATTRIBUTE_HIDDEN, FILE_ATTRIBUTE_NORMAL, FILE_ATTRIBUTE_NOT_CONTENT_INDEXED,
        FILE_ATTRIBUTE_SYSTEM,
    };
    let wide: Vec<u16> = path.as_os_str().encode_wide().chain(std::iter::once(0)).collect();
    let attributes = if hidden {
        FILE_ATTRIBUTE_HIDDEN | FILE_ATTRIBUTE_SYSTEM | FILE_ATTRIBUTE_NOT_CONTENT_INDEXED
    } else {
        FILE_ATTRIBUTE_NORMAL
    };
    // SAFETY: `wide` is a NUL-terminated UTF-16 path that outlives the call.
    unsafe { SetFileAttributesW(wide.as_ptr(), attributes) != 0 }
}

#[cfg_attr(any(windows, target_os = "macos"), allow(dead_code))]
fn add_to_hidden_list(path: &Path) -> bool {
    let (Some(dir), Some(name)) = (path.parent(), path.file_name().and_then(|n| n.to_str())) else {
        return false;
    };
    let list = dir.join(HIDDEN_LIST);
    let text = fs::read_to_string(&list).unwrap_or_default();
    if text.lines().any(|line| line == name) {
        return true;
    }
    let mut out = text;
    if !out.is_empty() && !out.ends_with('\n') {
        out.push('\n');
    }
    out.push_str(name);
    out.push('\n');
    fs::write(&list, out).is_ok()
}

#[cfg_attr(any(windows, target_os = "macos"), allow(dead_code))]
fn remove_from_hidden_list(path: &Path) {
    let (Some(dir), Some(name)) = (path.parent(), path.file_name().and_then(|n| n.to_str())) else {
        return;
    };
    let list = dir.join(HIDDEN_LIST);
    let Ok(text) = fs::read_to_string(&list) else { return };
    if !text.lines().any(|line| line == name) {
        return;
    }
    let kept: Vec<&str> = text.lines().filter(|line| *line != name).collect();
    if kept.iter().all(|line| line.trim().is_empty()) {
        let _ = fs::remove_file(&list);
    } else {
        let _ = fs::write(&list, format!("{}\n", kept.join("\n")));
    }
}

/// Deletes `path`, trying again for a moment: a game that has just exited can
/// still hold its files for a fraction of a second on Windows, and a virus
/// scanner can open a new file right after it is written. True when the file
/// is gone (or was never there).
pub fn remove_with_retries(path: &Path, attempts: u32, wait: Duration) -> bool {
    for attempt in 0..attempts.max(1) {
        if !path.exists() {
            return true;
        }
        if fs::remove_file(path).is_ok() {
            return true;
        }
        if attempt + 1 < attempts {
            thread::sleep(wait);
        }
    }
    !path.exists()
}

/// Whether a process with this id is running.
pub fn process_alive(pid: u32) -> bool {
    if pid == 0 {
        return false;
    }
    #[cfg(windows)]
    {
        use windows_sys::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ACCESS_DENIED, STILL_ACTIVE};
        use windows_sys::Win32::System::Threading::{GetExitCodeProcess, OpenProcess, PROCESS_QUERY_LIMITED_INFORMATION};
        // SAFETY: plain Win32 calls; the handle is closed before returning.
        unsafe {
            let handle = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, 0, pid);
            if handle.is_null() {
                // A process of another user cannot be opened, but it exists.
                return GetLastError() == ERROR_ACCESS_DENIED;
            }
            let mut code: u32 = 0;
            let ok = GetExitCodeProcess(handle, &mut code) != 0;
            CloseHandle(handle);
            ok && code == STILL_ACTIVE as u32
        }
    }
    #[cfg(target_os = "linux")]
    {
        Path::new("/proc").join(pid.to_string()).exists()
    }
    #[cfg(all(unix, not(target_os = "linux")))]
    {
        std::process::Command::new("ps")
            .args(["-p", &pid.to_string()])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .map(|status| status.success())
            .unwrap_or(false)
    }
}

fn pid_file(instance: &Path) -> PathBuf {
    instance.join(crate::BREEZE_RUNTIME_DIR).join(GAME_PID_FILE)
}

/// Notes that the game with this process id uses the instance's Breeze jar.
pub fn record_game(instance: &Path, pid: u32) {
    let file = pid_file(instance);
    if let Some(dir) = file.parent() {
        let _ = fs::create_dir_all(dir);
    }
    let _ = fs::write(file, pid.to_string());
}

/// The game has ended.
pub fn clear_game(instance: &Path) {
    let _ = fs::remove_file(pid_file(instance));
}

/// Whether the game a launch recorded for this instance is still running.
pub fn game_running(instance: &Path) -> bool {
    fs::read_to_string(pid_file(instance))
        .ok()
        .and_then(|text| text.trim().parse::<u32>().ok())
        .map_or(false, process_alive)
}

/// Whether a jar is Breeze: its Fabric id, or the mod id of its Forge or
/// NeoForge metadata. Nothing else is ever deleted by the sweep.
pub fn declares_breeze(jar: &Path) -> bool {
    if crate::fabric_mod_id(jar).as_deref() == Some(crate::BREEZE_MOD_ID) {
        return true;
    }
    // The Breeze jars for Forge and NeoForge are a few megabytes; anything far
    // larger is not one of them and is not read.
    match fs::metadata(jar) {
        Ok(meta) if meta.len() <= 256 * 1024 * 1024 => {}
        _ => return false,
    }
    fs::read(jar)
        .ok()
        .and_then(|bytes| crate::mods_local::read_other_meta_bytes(&bytes))
        .map_or(false, |meta| meta.id == crate::BREEZE_MOD_ID && meta.kind != "quilt")
}

/// The copy of Breeze a Forge or NeoForge launch puts in this instance's mods
/// folder: `<instance>/mods/<instance id>.jar`.
pub fn loader_copy(instance: &Path) -> Option<PathBuf> {
    let id = instance.file_name()?.to_str()?;
    Some(instance.join("mods").join(format!("{}.jar", crate::sanitize_filename(id))))
}

/// Ends a session: deletes its runtime files, forgets them in the folder's
/// hidden list and the game's process id. Returns how many could not be
/// deleted.
pub fn finish(instance: &Path, files: &[PathBuf]) -> usize {
    let mut left = 0;
    for file in files {
        if remove_with_retries(file, 8, Duration::from_millis(400)) {
            forget(file);
        } else {
            left += 1;
        }
    }
    clear_game(instance);
    left
}

/// Deletes the Breeze copies that Forge and NeoForge launches left in mods
/// folders under `instances` (the launcher was closed or stopped before the
/// game ended), except where the game that uses one still runs. Returns the
/// files deleted.
pub fn sweep(instances: &Path) -> Vec<PathBuf> {
    let mut removed = Vec::new();
    let Ok(entries) = fs::read_dir(instances) else { return removed };
    for entry in entries.flatten() {
        let instance = entry.path();
        if !instance.is_dir() {
            continue;
        }
        let Some(id) = instance.file_name().and_then(|n| n.to_str()) else { continue };
        if !matches!(crate::loaders::split_instance_id(id).1, crate::loaders::Loader::Forge | crate::loaders::Loader::NeoForge) {
            continue;
        }
        let Some(copy) = loader_copy(&instance) else { continue };
        if !copy.is_file() || game_running(&instance) || !declares_breeze(&copy) {
            continue;
        }
        unhide(&copy);
        if remove_with_retries(&copy, 2, Duration::from_millis(200)) {
            forget(&copy);
            clear_game(&instance);
            removed.push(copy);
        } else {
            // Still held by a game this launcher did not record: hidden again
            // until the next start.
            hide(&copy);
        }
    }
    removed
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Write;

    fn scratch(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("breeze-session-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// A jar holding only the given metadata file.
    fn jar_with(path: &Path, entry: &str, text: &str) {
        let file = fs::File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        zip.start_file(entry, zip::write::SimpleFileOptions::default()).unwrap();
        zip.write_all(text.as_bytes()).unwrap();
        zip.finish().unwrap();
    }

    const FORGE_BREEZE: &str = "modLoader=\"javafml\"\nloaderVersion=\"[47,)\"\n[[mods]]\nmodId=\"breeze\"\nversion=\"2.29.0\"\ndisplayName=\"Breeze\"\n";

    #[test]
    fn forge_and_neoforge_breeze_jars_are_recognised_and_other_mods_are_not() {
        let dir = scratch("declares");
        let forge = dir.join("forge.jar");
        jar_with(&forge, "META-INF/mods.toml", FORGE_BREEZE);
        let neo = dir.join("neo.jar");
        jar_with(&neo, "META-INF/neoforge.mods.toml", FORGE_BREEZE);
        let fabric = dir.join("fabric.jar");
        jar_with(&fabric, "fabric.mod.json", r#"{"schemaVersion":1,"id":"breeze","version":"2.29.0"}"#);
        let other = dir.join("other.jar");
        jar_with(&other, "META-INF/mods.toml", &FORGE_BREEZE.replace("\"breeze\"", "\"embeddium\""));
        let text = dir.join("notes.jar");
        fs::write(&text, "not a jar").unwrap();
        assert!(declares_breeze(&forge));
        assert!(declares_breeze(&neo));
        assert!(declares_breeze(&fabric));
        assert!(!declares_breeze(&other));
        assert!(!declares_breeze(&text));
        assert!(!declares_breeze(&dir.join("missing.jar")));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_hidden_copy_is_gone_after_the_session_and_leaves_nothing_behind() {
        let dir = scratch("finish");
        let instance = dir.join("1.20.1-forge");
        let mods = instance.join("mods");
        fs::create_dir_all(&mods).unwrap();
        // The player's own hidden list and mods stay as they were.
        #[cfg(not(any(windows, target_os = "macos")))]
        fs::write(mods.join(HIDDEN_LIST), "mine.txt\n").unwrap();
        fs::write(mods.join("embeddium.jar"), b"PK").unwrap();
        let copy = loader_copy(&instance).unwrap();
        assert_eq!(copy, mods.join("1.20.1-forge.jar"));
        jar_with(&copy, "META-INF/mods.toml", FORGE_BREEZE);
        assert!(hide(&copy), "the system hid the copy");
        #[cfg(not(any(windows, target_os = "macos")))]
        assert_eq!(fs::read_to_string(mods.join(HIDDEN_LIST)).unwrap(), "mine.txt\n1.20.1-forge.jar\n");
        #[cfg(windows)]
        {
            use std::os::windows::fs::MetadataExt;
            let attributes = fs::metadata(&copy).unwrap().file_attributes();
            assert_eq!(attributes & 0x6, 0x6, "hidden and system");
        }
        record_game(&instance, std::process::id());
        assert!(game_running(&instance));
        assert_eq!(finish(&instance, &[copy.clone()]), 0);
        assert!(!copy.exists());
        assert!(!game_running(&instance));
        assert!(mods.join("embeddium.jar").is_file());
        #[cfg(not(any(windows, target_os = "macos")))]
        assert_eq!(fs::read_to_string(mods.join(HIDDEN_LIST)).unwrap(), "mine.txt\n");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_hidden_list_goes_when_breeze_was_all_it_hid() {
        let dir = scratch("list");
        let copy = dir.join("1.21.1-neoforge.jar");
        fs::write(&copy, b"PK").unwrap();
        assert!(add_to_hidden_list(&copy));
        assert!(add_to_hidden_list(&copy), "a second time adds no second line");
        assert_eq!(fs::read_to_string(dir.join(HIDDEN_LIST)).unwrap(), "1.21.1-neoforge.jar\n");
        remove_from_hidden_list(&copy);
        assert!(!dir.join(HIDDEN_LIST).exists());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_hidden_copy_can_be_replaced() {
        let dir = scratch("replace");
        let copy = dir.join("1.20.1-forge.jar");
        fs::write(&copy, b"old").unwrap();
        hide(&copy);
        unhide(&copy);
        assert!(remove_with_retries(&copy, 2, Duration::from_millis(10)));
        fs::write(&copy, b"new").unwrap();
        assert_eq!(fs::read(&copy).unwrap(), b"new");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn the_sweep_deletes_left_copies_but_not_a_running_games_or_anything_else() {
        let dir = scratch("sweep");
        let make = |id: &str| {
            let instance = dir.join(id);
            fs::create_dir_all(instance.join("mods")).unwrap();
            let copy = loader_copy(&instance).unwrap();
            jar_with(&copy, "META-INF/mods.toml", FORGE_BREEZE);
            hide(&copy);
            (instance, copy)
        };
        let (_, left) = make("1.20.1-forge");
        let (stale, stale_copy) = make("1.21.1-neoforge");
        // A process id that is not running: the copy goes.
        record_game(&stale, u32::MAX - 7);
        let (running, running_copy) = make("1.19.2-forge");
        record_game(&running, std::process::id());
        // Fabric keeps Breeze outside the mods folder; a jar of that name
        // there is the player's and stays.
        let fabric = dir.join("1.21.4");
        fs::create_dir_all(fabric.join("mods")).unwrap();
        let fabric_jar = fabric.join("mods").join("1.21.4.jar");
        jar_with(&fabric_jar, "fabric.mod.json", r#"{"schemaVersion":1,"id":"breeze","version":"1"}"#);
        // A Forge instance whose jar of that name is not Breeze stays too.
        let other = dir.join("1.18.2-forge");
        fs::create_dir_all(other.join("mods")).unwrap();
        let other_jar = loader_copy(&other).unwrap();
        jar_with(&other_jar, "META-INF/mods.toml", &FORGE_BREEZE.replace("\"breeze\"", "\"jei\""));

        let mut removed = sweep(&dir);
        removed.sort();
        let mut expected = vec![left.clone(), stale_copy.clone()];
        expected.sort();
        assert_eq!(removed, expected);
        assert!(!left.exists() && !stale_copy.exists());
        assert!(!stale.join(crate::BREEZE_RUNTIME_DIR).join(GAME_PID_FILE).exists());
        assert!(running_copy.is_file(), "the running game keeps its copy");
        assert!(fabric_jar.is_file());
        assert!(other_jar.is_file());
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn this_process_is_alive_and_an_unused_id_is_not() {
        assert!(process_alive(std::process::id()));
        assert!(!process_alive(0));
        assert!(!process_alive(u32::MAX - 7));
    }
}
