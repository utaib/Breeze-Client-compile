//! Minecraft's desktop wallpapers for the Breeze mod's title menu.
//!
//! The pictures live in the repository's `assets/wallpapers/` (1920x1080,
//! 16:9 only) and travel inside the launcher. Before each launch they are
//! copied to `<Breeze home>/wallpapers`, where the mod looks for them
//! (`-Dbreeze.home`); a file already there with the same size is left
//! alone, so this writes about 6 MB once and nothing after. Other pictures a
//! player puts in that folder are never touched or removed.

use std::fs;
use std::path::Path;

macro_rules! wallpapers {
    ($($name:literal),* $(,)?) => {
        /// Every wallpaper's file name and bytes.
        pub const WALLPAPERS: &[(&str, &[u8])] = &[
            $(($name, include_bytes!(concat!("../../../assets/wallpapers/", $name)))),*
        ];
    };
}

wallpapers!(
    "bedrock_edition.jpg",
    "bundles_of_bravery.jpg",
    "buzzy_bees.jpg",
    "cats_and_pandas.jpg",
    "caves_and_cliffs_1.jpg",
    "caves_and_cliffs_2.jpg",
    "chaos_cubed.jpg",
    "chase_the_skies.jpg",
    "island.jpg",
    "mineshaft.jpg",
    "mounts_of_mayhem.jpg",
    "nether_update.jpg",
    "ocean_monument.jpg",
    "spring_to_life.jpg",
    "the_copper_age.jpg",
    "the_garden_awakens.jpg",
    "tiny_takeover.jpg",
    "trails_and_tales.jpg",
    "tricky_trials.jpg",
    "update_aquatic.jpg",
    "village_and_pillage.jpg",
    "wilderness_bound.jpg",
    "winter_celebration.jpg",
);

/// Writes any wallpaper that is missing or differs in size into
/// `<breeze_home>/wallpapers`. Returns how many files were written.
pub fn ensure(breeze_home: &Path) -> Result<usize, String> {
    let dir = breeze_home.join("wallpapers");
    fs::create_dir_all(&dir).map_err(|e| format!("could not create the wallpapers folder: {e}"))?;
    let mut written = 0;
    for (name, bytes) in WALLPAPERS {
        let path = dir.join(name);
        let same = fs::metadata(&path).map(|m| m.is_file() && m.len() == bytes.len() as u64).unwrap_or(false);
        if same {
            continue;
        }
        // Written beside it first, so the mod never reads half a picture.
        let part = dir.join(format!("{name}.part"));
        fs::write(&part, bytes).map_err(|e| format!("could not write wallpaper {name}: {e}"))?;
        fs::rename(&part, &path).map_err(|e| format!("could not place wallpaper {name}: {e}"))?;
        written += 1;
    }
    Ok(written)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeSet;

    fn temp_home(tag: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!("breeze-wallpapers-{tag}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        dir
    }

    #[test]
    fn the_list_matches_the_assets_folder() {
        let folder = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../assets/wallpapers");
        let on_disk: BTreeSet<String> = fs::read_dir(&folder)
            .expect("assets/wallpapers")
            .filter_map(|e| e.ok())
            .map(|e| e.file_name().to_string_lossy().into_owned())
            .filter(|n| n.ends_with(".jpg"))
            .collect();
        let listed: BTreeSet<String> = WALLPAPERS.iter().map(|(n, _)| n.to_string()).collect();
        assert_eq!(on_disk, listed);
        assert!(WALLPAPERS.iter().all(|(_, b)| b.starts_with(&[0xFF, 0xD8, 0xFF])), "every wallpaper is a JPEG");
    }

    #[test]
    fn writes_once_and_keeps_a_players_own_pictures() {
        let home = temp_home("once");
        let dir = home.join("wallpapers");
        fs::create_dir_all(&dir).unwrap();
        fs::write(dir.join("mine.png"), b"not ours").unwrap();
        // A truncated copy (an interrupted earlier write) is replaced.
        let (first, bytes) = WALLPAPERS[0];
        fs::write(dir.join(first), &bytes[..10]).unwrap();

        assert_eq!(ensure(&home).unwrap(), WALLPAPERS.len());
        assert_eq!(fs::read(dir.join(first)).unwrap(), bytes);
        assert_eq!(ensure(&home).unwrap(), 0);
        assert_eq!(fs::read(dir.join("mine.png")).unwrap(), b"not ours");
        assert!(fs::read_dir(&dir).unwrap().all(|e| !e.unwrap().file_name().to_string_lossy().ends_with(".part")));
        let _ = fs::remove_dir_all(&home);
    }
}
