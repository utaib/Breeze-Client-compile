//! What the launcher does for frame rate (launcher 1.2.1).
//!
//! Four things, each of which changes what the game can draw per second and
//! none of which is a claim about a number:
//!
//! - **The fast graphics card.** A laptop with two graphics cards starts
//!   Java on the slow one unless told otherwise. On Windows the launcher
//!   records the preference Windows itself offers in Settings, Display,
//!   Graphics, for the Java it starts the game with; on Linux it sets the
//!   variables the drivers read. A choice the player already made in Windows
//!   is never replaced.
//! - **Priority.** The game is started above normal priority on Windows, so
//!   a browser or a chat program in the background takes less of its time.
//! - **Video presets.** Max FPS, Balanced and Quality are sets of Minecraft's
//!   own video options, written to the version's `options.txt` in that
//!   version's own format, shown before they are applied and undoable.
//! - **Boost mods.** Which performance mods a version starts with, by loader
//!   (`boost_roles`): the launcher installs the first of each role that has a
//!   build for the version.
//!
//! No build machine has a graphics card, so nothing here was measured as
//! frames per second; what is tested is that each setting reaches the game.

use serde::{Deserialize, Serialize};
use std::path::Path;

// ───────────────────────────── Minecraft versions ─────────────────────────────

/// "1.21.11" as (1, 21, 11), "26.1" as (26, 1, 0). A snapshot or anything
/// else that is not numbers reads as None, and the callers then leave the
/// version-dependent options out.
pub fn version_parts(game_version: &str) -> Option<(u32, u32, u32)> {
    let mut parts = game_version.trim().split('.');
    let major = parts.next()?.parse().ok()?;
    let minor = parts.next()?.parse().ok()?;
    let patch = match parts.next() {
        Some(p) => p.parse().ok()?,
        None => 0,
    };
    if parts.next().is_some() {
        return None;
    }
    Some((major, minor, patch))
}

fn at_least(game_version: &str, wanted: (u32, u32, u32)) -> bool {
    version_parts(game_version).map_or(false, |v| v >= wanted)
}

// ───────────────────────────── the fast graphics card ─────────────────────────────

/// What Windows keeps per program under
/// `HKCU\Software\Microsoft\DirectX\UserGpuPreferences`: the value named by
/// the program's full path. `GpuPreference=2` is "High performance",
/// 1 "Power saving", 0 "Let Windows decide".
pub const GPU_FAST: &str = "GpuPreference=2;";

/// What to do about the preference Windows holds for Breeze's Java.
#[derive(Debug, PartialEq, Eq)]
pub enum GpuPlan {
    /// Nothing recorded, or "Let Windows decide": write this value.
    Write(String),
    /// Already on the fast card.
    AlreadyFast,
    /// The player chose "Power saving" for this program in Windows; that is
    /// theirs to change.
    PlayersChoice,
}

/// Decides from the value Windows holds now. Other settings in the same value
/// (Windows keeps window and HDR choices there too) are kept.
pub fn gpu_plan(existing: Option<&str>) -> GpuPlan {
    let existing = existing.map(str::trim).unwrap_or("");
    if existing.is_empty() {
        return GpuPlan::Write(GPU_FAST.to_string());
    }
    let mut rest: Vec<&str> = Vec::new();
    let mut preference: Option<&str> = None;
    for part in existing.split(';').map(str::trim).filter(|p| !p.is_empty()) {
        match part.strip_prefix("GpuPreference=") {
            Some(value) => preference = Some(value.trim()),
            None => rest.push(part),
        }
    }
    match preference {
        Some("2") => GpuPlan::AlreadyFast,
        Some("1") => GpuPlan::PlayersChoice,
        _ => {
            let mut value = String::from(GPU_FAST);
            for part in rest {
                value.push_str(part);
                value.push(';');
            }
            GpuPlan::Write(value)
        }
    }
}

/// The value to put back when the setting is turned off: what was there
/// without Breeze's choice, or None when the whole value was Breeze's.
pub fn gpu_value_without_preference(existing: &str) -> Option<String> {
    let rest: Vec<&str> = existing
        .split(';')
        .map(str::trim)
        .filter(|p| !p.is_empty() && !p.starts_with("GpuPreference="))
        .collect();
    if rest.is_empty() {
        None
    } else {
        Some(rest.iter().map(|p| format!("{p};")).collect())
    }
}

/// The programs whose graphics preference Breeze set, kept in
/// `<breeze home>/gpu-preference.json`, so turning the setting off removes
/// exactly those and nothing the player set.
#[derive(Serialize, Deserialize, Default, Debug, PartialEq, Eq)]
#[serde(default)]
pub struct GpuRecord {
    pub programs: Vec<String>,
}

pub fn read_gpu_record(breeze_home: &Path) -> GpuRecord {
    std::fs::read_to_string(breeze_home.join("gpu-preference.json"))
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or_default()
}

pub fn write_gpu_record(breeze_home: &Path, record: &GpuRecord) {
    let path = breeze_home.join("gpu-preference.json");
    if record.programs.is_empty() {
        let _ = std::fs::remove_file(path);
    } else if let Ok(text) = serde_json::to_string_pretty(record) {
        let _ = std::fs::write(path, text);
    }
}

#[cfg(windows)]
pub mod windows_gpu {
    //! The registry value itself. Current user only; nothing here needs
    //! administrator rights.
    use windows_sys::Win32::Foundation::ERROR_SUCCESS;
    use windows_sys::Win32::System::Registry::{
        RegCloseKey, RegCreateKeyExW, RegDeleteValueW, RegQueryValueExW, RegSetValueExW, HKEY, HKEY_CURRENT_USER,
        KEY_QUERY_VALUE, KEY_SET_VALUE, REG_OPTION_NON_VOLATILE, REG_SZ,
    };

    pub const KEY: &str = r"Software\Microsoft\DirectX\UserGpuPreferences";

    fn wide(text: &str) -> Vec<u16> {
        text.encode_utf16().chain(std::iter::once(0)).collect()
    }

    fn open(key: &str) -> Result<HKEY, String> {
        let mut handle: HKEY = std::ptr::null_mut();
        let status = unsafe {
            RegCreateKeyExW(
                HKEY_CURRENT_USER,
                wide(key).as_ptr(),
                0,
                std::ptr::null(),
                REG_OPTION_NON_VOLATILE,
                KEY_QUERY_VALUE | KEY_SET_VALUE,
                std::ptr::null(),
                &mut handle,
                std::ptr::null_mut(),
            )
        };
        if status == ERROR_SUCCESS {
            Ok(handle)
        } else {
            Err(format!("Windows would not open its graphics preferences (error {status})"))
        }
    }

    pub fn read_in(key: &str, program: &str) -> Option<String> {
        let handle = open(key).ok()?;
        let name = wide(program);
        let mut bytes: u32 = 0;
        let mut kind: u32 = 0;
        let first = unsafe { RegQueryValueExW(handle, name.as_ptr(), std::ptr::null(), &mut kind, std::ptr::null_mut(), &mut bytes) };
        let mut out = None;
        if first == ERROR_SUCCESS && kind == REG_SZ && bytes > 0 && bytes < 8192 {
            let mut buffer = vec![0u16; (bytes as usize + 1) / 2 + 1];
            let mut size = (buffer.len() * 2) as u32;
            let second = unsafe {
                RegQueryValueExW(handle, name.as_ptr(), std::ptr::null(), &mut kind, buffer.as_mut_ptr() as *mut u8, &mut size)
            };
            if second == ERROR_SUCCESS {
                let len = buffer.iter().position(|&c| c == 0).unwrap_or(buffer.len());
                out = Some(String::from_utf16_lossy(&buffer[..len]));
            }
        }
        unsafe { RegCloseKey(handle) };
        out
    }

    pub fn write_in(key: &str, program: &str, value: &str) -> Result<(), String> {
        let handle = open(key)?;
        let data = wide(value);
        let status = unsafe {
            RegSetValueExW(handle, wide(program).as_ptr(), 0, REG_SZ, data.as_ptr() as *const u8, (data.len() * 2) as u32)
        };
        unsafe { RegCloseKey(handle) };
        if status == ERROR_SUCCESS {
            Ok(())
        } else {
            Err(format!("Windows would not save the graphics preference (error {status})"))
        }
    }

    pub fn delete_in(key: &str, program: &str) {
        if let Ok(handle) = open(key) {
            unsafe {
                RegDeleteValueW(handle, wide(program).as_ptr());
                RegCloseKey(handle);
            }
        }
    }

    pub fn read(program: &str) -> Option<String> {
        read_in(KEY, program)
    }
    pub fn write(program: &str, value: &str) -> Result<(), String> {
        write_in(KEY, program, value)
    }
    pub fn delete(program: &str) {
        delete_in(KEY, program)
    }
}

/// What happened to the graphics preference at a launch, as a line for the log.
#[derive(Debug, PartialEq, Eq)]
#[cfg_attr(not(windows), allow(dead_code))]
pub enum GpuOutcome {
    Set,
    AlreadyFast,
    PlayersChoice,
    Removed(usize),
    Nothing,
    Failed(String),
}

impl GpuOutcome {
    pub fn log_line(&self) -> Option<String> {
        match self {
            GpuOutcome::Set => Some("[Performance] Windows now starts Breeze's Java on the high performance graphics card".into()),
            GpuOutcome::AlreadyFast => Some("[Performance] Breeze's Java is set to the high performance graphics card".into()),
            GpuOutcome::PlayersChoice => Some(
                "[Performance] Windows is set to the power saving graphics card for Breeze's Java \
                 (Settings, Display, Graphics); Breeze left that choice alone"
                    .into(),
            ),
            GpuOutcome::Removed(n) if *n > 0 => Some(format!("[Performance] Graphics card preference removed for {n} Java install(s)")),
            GpuOutcome::Removed(_) | GpuOutcome::Nothing => None,
            GpuOutcome::Failed(error) => Some(format!("[Performance] Graphics card preference not set: {error}")),
        }
    }
}

/// Applies the "fast graphics card" setting on Windows for the Java at
/// `java_path`. With the setting off, takes back what Breeze set earlier.
#[cfg(windows)]
pub fn apply_gpu_preference(breeze_home: &Path, java_path: &Path, wanted: bool) -> GpuOutcome {
    let program = java_path.to_string_lossy().trim_start_matches(r"\\?\").to_string();
    let mut record = read_gpu_record(breeze_home);
    if !wanted {
        let mut removed = 0;
        for old in record.programs.drain(..) {
            if let Some(existing) = windows_gpu::read(&old) {
                if existing.contains("GpuPreference=2") {
                    match gpu_value_without_preference(&existing) {
                        Some(rest) => {
                            let _ = windows_gpu::write(&old, &rest);
                        }
                        None => windows_gpu::delete(&old),
                    }
                    removed += 1;
                }
            }
        }
        write_gpu_record(breeze_home, &record);
        return GpuOutcome::Removed(removed);
    }
    match gpu_plan(windows_gpu::read(&program).as_deref()) {
        GpuPlan::AlreadyFast => GpuOutcome::AlreadyFast,
        GpuPlan::PlayersChoice => GpuOutcome::PlayersChoice,
        GpuPlan::Write(value) => match windows_gpu::write(&program, &value) {
            Ok(()) => {
                if !record.programs.iter().any(|p| p.eq_ignore_ascii_case(&program)) {
                    record.programs.push(program);
                    write_gpu_record(breeze_home, &record);
                }
                GpuOutcome::Set
            }
            Err(error) => GpuOutcome::Failed(error),
        },
    }
}

#[cfg(not(windows))]
pub fn apply_gpu_preference(_breeze_home: &Path, _java_path: &Path, _wanted: bool) -> GpuOutcome {
    GpuOutcome::Nothing
}

/// A graphics card as Linux lists it under `/sys/class/drm`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DrmGpu {
    /// PCI vendor: 0x8086 Intel, 0x1002 AMD, 0x10de NVIDIA.
    pub vendor: u32,
    /// The card the firmware started the screen on.
    pub boot: bool,
}

pub const VENDOR_INTEL: u32 = 0x8086;
pub const VENDOR_AMD: u32 = 0x1002;
pub const VENDOR_NVIDIA: u32 = 0x10de;

/// Reads the cards from a `/sys/class/drm` folder (`card0`, `card1`, ...;
/// the `card0-HDMI-A-1` entries are outputs, not cards).
pub fn read_drm_gpus(drm: &Path) -> Vec<DrmGpu> {
    let mut names: Vec<String> = std::fs::read_dir(drm)
        .into_iter()
        .flatten()
        .flatten()
        .map(|entry| entry.file_name().to_string_lossy().to_string())
        .filter(|name| name.strip_prefix("card").map_or(false, |n| !n.is_empty() && n.chars().all(|c| c.is_ascii_digit())))
        .collect();
    names.sort();
    names
        .iter()
        .filter_map(|name| {
            let device = drm.join(name).join("device");
            let vendor = std::fs::read_to_string(device.join("vendor")).ok()?;
            let vendor = u32::from_str_radix(vendor.trim().trim_start_matches("0x"), 16).ok()?;
            let boot = std::fs::read_to_string(device.join("boot_vga")).map_or(false, |b| b.trim() == "1");
            Some(DrmGpu { vendor, boot })
        })
        .collect()
}

/// The variables that send the game to the second, faster card on a Linux
/// laptop with two. Only in the cases that are certain: the screen runs on an
/// Intel or AMD card and there is an NVIDIA card with its own driver loaded
/// (the driver's render offload), or the screen runs on Intel and there is an
/// AMD card (Mesa's `DRI_PRIME`). Two AMD cards, or a desktop whose screen is
/// already on the fast card, get nothing: the wrong guess there would make
/// the game slower.
pub fn linux_gpu_env(gpus: &[DrmGpu], nvidia_driver_loaded: bool) -> Vec<(&'static str, &'static str)> {
    let Some(boot) = gpus.iter().find(|g| g.boot) else { return Vec::new() };
    let others: Vec<&DrmGpu> = gpus.iter().filter(|g| !g.boot).collect();
    if others.is_empty() || boot.vendor == VENDOR_NVIDIA {
        return Vec::new();
    }
    if others.iter().any(|g| g.vendor == VENDOR_NVIDIA) {
        if nvidia_driver_loaded {
            return vec![
                ("__NV_PRIME_RENDER_OFFLOAD", "1"),
                ("__GLX_VENDOR_LIBRARY_NAME", "nvidia"),
                ("__VK_LAYER_NV_optimus", "NVIDIA_only"),
            ];
        }
        // The open driver (nouveau) is a Mesa driver.
        return vec![("DRI_PRIME", "1")];
    }
    if boot.vendor == VENDOR_INTEL && others.iter().any(|g| g.vendor == VENDOR_AMD) {
        return vec![("DRI_PRIME", "1")];
    }
    Vec::new()
}

/// The variables for this computer, leaving out any the player already set.
#[cfg(target_os = "linux")]
pub fn linux_gpu_env_here() -> Vec<(&'static str, &'static str)> {
    let gpus = read_drm_gpus(Path::new("/sys/class/drm"));
    let nvidia = Path::new("/proc/driver/nvidia/version").exists();
    linux_gpu_env(&gpus, nvidia).into_iter().filter(|(name, _)| std::env::var_os(name).is_none()).collect()
}

// ───────────────────────────── priority ─────────────────────────────

/// Windows' ABOVE_NORMAL_PRIORITY_CLASS, a process creation flag: the game
/// gets the processor before programs at normal priority, and still yields to
/// sound and input, which run higher.
#[cfg_attr(not(windows), allow(dead_code))]
pub const WIN_ABOVE_NORMAL_PRIORITY: u32 = 0x0000_8000;

// ───────────────────────────── video presets ─────────────────────────────

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum VideoPreset {
    MaxFps,
    Balanced,
    Quality,
}

impl VideoPreset {
    pub const ALL: [VideoPreset; 3] = [VideoPreset::MaxFps, VideoPreset::Balanced, VideoPreset::Quality];

    pub fn parse(raw: &str) -> Option<VideoPreset> {
        match raw.trim().to_ascii_lowercase().as_str() {
            "max-fps" | "maxfps" => Some(VideoPreset::MaxFps),
            "balanced" => Some(VideoPreset::Balanced),
            "quality" => Some(VideoPreset::Quality),
            _ => None,
        }
    }

    pub fn id(self) -> &'static str {
        match self {
            VideoPreset::MaxFps => "max-fps",
            VideoPreset::Balanced => "balanced",
            VideoPreset::Quality => "quality",
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            VideoPreset::MaxFps => "Max FPS",
            VideoPreset::Balanced => "Balanced",
            VideoPreset::Quality => "Quality",
        }
    }
}

/// The preset a new version starts with when the setting says "auto": Max FPS
/// on a computer with little memory or few processor threads, Balanced
/// otherwise.
pub fn auto_preset(total_ram_mb: u64, cpus: usize) -> VideoPreset {
    if total_ram_mb <= 8192 || cpus <= 4 {
        VideoPreset::MaxFps
    } else {
        VideoPreset::Balanced
    }
}

/// What the "video settings for new versions" setting means for a new
/// instance: a preset, or None for Minecraft's own defaults.
pub fn preset_for_new_instance(setting: &str, total_ram_mb: u64, cpus: usize) -> Option<VideoPreset> {
    match setting.trim().to_ascii_lowercase().as_str() {
        "minecraft" | "none" | "off" => None,
        "auto" | "" => Some(auto_preset(total_ram_mb, cpus)),
        other => VideoPreset::parse(other).or_else(|| Some(auto_preset(total_ram_mb, cpus))),
    }
}

/// One option of a preset: Minecraft's key in `options.txt`, the value in the
/// form this version writes it, and the words for the player.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PresetOption {
    pub key: &'static str,
    pub value: String,
    pub label: &'static str,
    pub shown: String,
}

fn option(key: &'static str, value: impl Into<String>, label: &'static str, shown: impl Into<String>) -> PresetOption {
    PresetOption { key, value: value.into(), label, shown: shown.into() }
}

/// The options a preset sets on this Minecraft version, in the form that
/// version writes them itself. Options a version does not have are left out,
/// and so is everything version-dependent when the version is not a release
/// number.
///
/// The forms were read from the `options.txt` each of the 34 supported
/// versions wrote in the build repository's run 43790021681
/// (`testdata/options-facts.txt`): clouds are written without quotes up to
/// 1.18.2 and with them from 1.19; simulation distance came with 1.18; the
/// menu blur with 1.20.5, as a fraction, and as 0 to 10 from 1.21; and from
/// 1.21.11 the one Graphics option became separate ones (leaves, weather,
/// vignette) under a "custom" preset.
///
/// `game_written` says whose file the options go into. A file the game has
/// saved carries its `version:` line and is read as it stands. A file
/// without one (the few lines a new instance starts with) is read as an old
/// version's and upgraded, and from 1.21.11 that upgrade makes the separate
/// graphics options out of the old `graphicsMode`, overwriting any that are
/// there: `cutoutLeaves:false` in a new file came back as `true` (build
/// repository run 43837399268). So a new file gets `graphicsMode`, which the
/// upgrade turns into the separate options, and a saved file gets those.
///
/// Smooth lighting, mipmaps and the field of view are never touched: they
/// change how the game looks far more than how fast it draws.
pub fn preset_options(preset: VideoPreset, game_version: &str, game_written: bool) -> Vec<PresetOption> {
    use VideoPreset::*;
    let known = version_parts(game_version).is_some();
    let mut out = Vec::new();

    let render = match preset { MaxFps => 6, Balanced => 12, Quality => 16 };
    out.push(option("renderDistance", render.to_string(), "Render distance", format!("{render} chunks")));

    if at_least(game_version, (1, 18, 0)) {
        let simulation = match preset { MaxFps => 5, Balanced => 8, Quality => 12 };
        out.push(option("simulationDistance", simulation.to_string(), "Simulation distance", format!("{simulation} chunks")));
    }

    if at_least(game_version, (1, 21, 11)) && game_written {
        out.push(option("graphicsPreset", "\"custom\"", "Graphics preset", "Custom"));
        let fast = preset == MaxFps;
        out.push(option("cutoutLeaves", (!fast).to_string(), "Leaves", if fast { "Fast" } else { "Fancy" }));
        let weather = if fast { 5 } else { 10 };
        out.push(option("weatherRadius", weather.to_string(), "Rain and snow", format!("{weather} blocks around you")));
        out.push(option("vignette", (!fast).to_string(), "Vignette", if fast { "Off" } else { "On" }));
        if fast {
            out.push(option("improvedTransparency", "false", "Improved transparency", "Off"));
        }
    } else if known {
        let (graphics, graphics_shown) = match preset { MaxFps => ("0", "Fast"), _ => ("1", "Fancy") };
        out.push(option("graphicsMode", graphics, "Graphics", graphics_shown));
    }

    if known {
        let (clouds, clouds_shown) = match preset { MaxFps => ("false", "Off"), Balanced => ("fast", "Fast"), Quality => ("true", "Fancy") };
        let clouds = if at_least(game_version, (1, 19, 0)) { format!("\"{clouds}\"") } else { clouds.to_string() };
        out.push(option("renderClouds", clouds, "Clouds", clouds_shown));
    }

    let (particles, particles_shown) = match preset { MaxFps => ("2", "Minimal"), Balanced => ("1", "Decreased"), Quality => ("0", "All") };
    out.push(option("particles", particles, "Particles", particles_shown));

    let shadows = preset != MaxFps;
    out.push(option("entityShadows", shadows.to_string(), "Entity shadows", if shadows { "On" } else { "Off" }));

    let (blend, blend_shown) = match preset { MaxFps => ("0", "Off"), Balanced => ("1", "3x3"), Quality => ("2", "5x5") };
    out.push(option("biomeBlendRadius", blend, "Biome blend", blend_shown));

    let (entities, entities_shown) = match preset { MaxFps => ("0.75", "75%"), _ => ("1.0", "100%") };
    out.push(option("entityDistanceScaling", entities, "Entity distance", entities_shown));

    if preset == MaxFps && at_least(game_version, (1, 20, 5)) {
        let off = if at_least(game_version, (1, 21, 0)) { "0" } else { "0.0" };
        out.push(option("menuBackgroundBlurriness", off, "Menu background blur", "Off"));
    }

    // 260 is how every version from 1.13 writes "Unlimited".
    out.push(option("maxFps", "260", "Max framerate", "Unlimited"));
    let vsync = preset == Quality;
    out.push(option("enableVsync", vsync.to_string(), "VSync", if vsync { "On" } else { "Off" }));
    out
}

/// One line a preset changed, for the player to read and for the undo.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OptionChange {
    pub key: String,
    pub label: String,
    /// The value before, as it stood in the file; None when the key was not there.
    pub before: Option<String>,
    pub after: String,
    pub shown: String,
}

/// Puts the preset's options into the text of an `options.txt`, leaving every
/// other line as it is. Returns the new text and the lines that changed (an
/// option already at the preset's value is not a change).
pub fn apply_options(existing: &str, options: &[PresetOption]) -> (String, Vec<OptionChange>) {
    let mut lines: Vec<String> = existing.lines().map(str::to_string).collect();
    let mut changes = Vec::new();
    for wanted in options {
        let prefix = format!("{}:", wanted.key);
        match lines.iter().position(|line| line.starts_with(&prefix)) {
            Some(index) => {
                let before = lines[index][prefix.len()..].to_string();
                if before != wanted.value {
                    lines[index] = format!("{prefix}{}", wanted.value);
                    changes.push(OptionChange {
                        key: wanted.key.to_string(),
                        label: wanted.label.to_string(),
                        before: Some(before),
                        after: wanted.value.clone(),
                        shown: wanted.shown.clone(),
                    });
                }
            }
            None => {
                lines.push(format!("{prefix}{}", wanted.value));
                changes.push(OptionChange {
                    key: wanted.key.to_string(),
                    label: wanted.label.to_string(),
                    before: None,
                    after: wanted.value.clone(),
                    shown: wanted.shown.clone(),
                });
            }
        }
    }
    let mut text = lines.join("\n");
    text.push('\n');
    (text, changes)
}

/// Takes a preset back: every changed line gets its earlier value, and a line
/// the preset added is removed. A line the player changed again since (it no
/// longer holds the preset's value) is left as the player has it.
pub fn undo_options(existing: &str, changes: &[OptionChange]) -> (String, usize) {
    let mut lines: Vec<String> = existing.lines().map(str::to_string).collect();
    let mut undone = 0;
    for change in changes {
        let prefix = format!("{}:", change.key);
        let Some(index) = lines.iter().position(|line| line.starts_with(&prefix)) else { continue };
        if lines[index][prefix.len()..] != change.after {
            continue;
        }
        match &change.before {
            Some(before) => lines[index] = format!("{prefix}{before}"),
            None => {
                lines.remove(index);
            }
        }
        undone += 1;
    }
    let mut text = lines.join("\n");
    text.push('\n');
    (text, undone)
}

/// What the instance keeps so the last preset can be taken back.
#[derive(Debug, Serialize, Deserialize, Default, PartialEq, Eq)]
#[serde(rename_all = "camelCase", default)]
pub struct PresetUndo {
    pub preset: String,
    pub changes: Vec<OptionChange>,
}

pub fn undo_path(instance: &Path) -> std::path::PathBuf {
    instance.join(".breeze").join("video-preset-undo.json")
}

/// Applies a preset to an instance's `options.txt` and keeps the undo. With
/// `dry_run` nothing is written: the changes are only listed.
/// Whether the text of an `options.txt` was saved by the game (it starts its
/// file with the data version it wrote it in).
pub fn game_written(options_text: &str) -> bool {
    options_text.lines().any(|line| line.starts_with("version:"))
}

pub fn apply_preset_to_instance(
    instance: &Path,
    preset: VideoPreset,
    game_version: &str,
    dry_run: bool,
) -> Result<Vec<OptionChange>, String> {
    let options_path = instance.join("options.txt");
    let existing = std::fs::read_to_string(&options_path).unwrap_or_default();
    let (text, changes) = apply_options(&existing, &preset_options(preset, game_version, game_written(&existing)));
    if dry_run || changes.is_empty() {
        return Ok(changes);
    }
    std::fs::create_dir_all(instance.join(".breeze")).map_err(|e| format!("Could not prepare the instance folder: {e}"))?;
    std::fs::write(&options_path, text).map_err(|e| format!("Could not write the video options: {e}"))?;
    let undo = PresetUndo { preset: preset.id().to_string(), changes: changes.clone() };
    if let Ok(json) = serde_json::to_string_pretty(&undo) {
        let _ = std::fs::write(undo_path(instance), json);
    }
    Ok(changes)
}

/// Takes the last preset back. Returns how many options went back.
pub fn undo_preset_in_instance(instance: &Path) -> Result<usize, String> {
    let path = undo_path(instance);
    let undo: PresetUndo = std::fs::read_to_string(&path)
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .ok_or_else(|| "There is no preset to take back for this version.".to_string())?;
    let options_path = instance.join("options.txt");
    let existing = std::fs::read_to_string(&options_path).map_err(|e| format!("Could not read the video options: {e}"))?;
    let (text, undone) = undo_options(&existing, &undo.changes);
    std::fs::write(&options_path, text).map_err(|e| format!("Could not write the video options: {e}"))?;
    let _ = std::fs::remove_file(path);
    Ok(undone)
}

/// The first `options.txt` of a new instance: the two lines every Breeze
/// instance starts with, and the preset's options when there is one.
pub fn first_options(preset: Option<VideoPreset>, game_version: &str) -> String {
    let base = "lang:en_us\ntutorialStep:none\n";
    match preset {
        Some(preset) => apply_options(base, &preset_options(preset, game_version, false)).0,
        None => base.to_string(),
    }
}

// ───────────────────────────── boost mods ─────────────────────────────

/// A performance mod the launcher can add: its Modrinth slug, the mod ids its
/// jars declare (how one already in the folder is recognised), and its title.
#[derive(Debug, PartialEq, Eq)]
pub struct BoostMod {
    pub slug: &'static str,
    pub mod_ids: &'static [&'static str],
    pub title: &'static str,
}

/// One job a performance mod does, with the mods that do it in order of
/// preference: the launcher installs the first that has a build for the
/// version and loader, and none when the folder already holds any of them.
#[derive(Debug, PartialEq, Eq)]
pub struct BoostRole {
    pub role: &'static str,
    /// What the role does for the player, as the Mods page says it.
    pub description: &'static str,
    /// Draws the world or the screen: left out next to OptiFine.
    pub rendering: bool,
    /// The set this role came with (`BOOST_SET_VERSION`): an instance whose
    /// first pass was an earlier set gets only the roles added since, so a
    /// mod the player removed from the earlier set is not put back.
    pub since: u32,
    pub mods: &'static [BoostMod],
}

/// 1: Sodium, Lithium, FerriteCore, EntityCulling, ImmediatelyFast (Fabric,
/// launcher 1.0.x). 2: Forge and NeoForge too, and BadOptimizations and
/// ModernFix (launcher 1.2.1).
pub const BOOST_SET_VERSION: u32 = 2;

const SODIUM: BoostMod = BoostMod { slug: "sodium", mod_ids: &["sodium"], title: "Sodium" };
const EMBEDDIUM: BoostMod = BoostMod { slug: "embeddium", mod_ids: &["embeddium"], title: "Embeddium" };
const RUBIDIUM: BoostMod = BoostMod { slug: "rubidium", mod_ids: &["rubidium"], title: "Rubidium" };
const LITHIUM: BoostMod = BoostMod { slug: "lithium", mod_ids: &["lithium"], title: "Lithium" };
const CANARY: BoostMod = BoostMod { slug: "canary", mod_ids: &["canary"], title: "Canary" };
const FERRITE: BoostMod = BoostMod { slug: "ferrite-core", mod_ids: &["ferritecore"], title: "FerriteCore" };
const ENTITY_CULLING: BoostMod = BoostMod { slug: "entityculling", mod_ids: &["entityculling"], title: "EntityCulling" };
const IMMEDIATELY_FAST: BoostMod = BoostMod { slug: "immediatelyfast", mod_ids: &["immediatelyfast"], title: "ImmediatelyFast" };
const BAD_OPTIMIZATIONS: BoostMod = BoostMod { slug: "badoptimizations", mod_ids: &["badoptimizations"], title: "BadOptimizations" };
const MODERNFIX: BoostMod = BoostMod { slug: "modernfix", mod_ids: &["modernfix"], title: "ModernFix" };
/// The continuation of ModernFix for the versions the original stopped before.
const MODERNFIX_MVUS: BoostMod = BoostMod { slug: "modernfix-mvus", mod_ids: &["modernfix"], title: "ModernFix-mVUS" };

/// Any of these in the folder means the world is already drawn by a Sodium
/// family mod (or OptiFine), and a second one would stop the game.
pub const WORLD_RENDERERS: &[&str] = &["sodium", "embeddium", "rubidium", "magnesium", "xenon", "optifine", "optifabric"];

// Which builds exist was read from Modrinth on 2026-11-16 (the build
// repository's facts job, run 43790332908): Sodium for Fabric on all 34
// versions and for NeoForge from 1.21.1; Embeddium for Forge 1.18.2, 1.19.2,
// 1.20.1, 1.20.2 and NeoForge 1.20.1 to 1.21.1; Rubidium for Forge 1.18.2 to
// 1.20.1; Lithium for NeoForge from 1.21; Canary (Lithium's Forge port) for
// 1.18.2 and 1.19.2 to 1.20.1 and 1.20.4. A version with no build of a mod
// simply goes without that role.
//
// Every set here was started in the game before it was put here: Fabric's
// with Breeze through the whole in-game self-test on the 34 versions (build
// repository run 43837503420 and the run after it), Forge's and NeoForge's
// with Breeze to the loaded game on the 43 jars (run 43837399268 and the
// run after it). What those runs took out:
// - More Culling, entirely. Its builds are tied to exact Sodium versions (it
//   patches Sodium's own patches): on 1.20 it crashed with the Sodium there,
//   on 1.21 and 1.21.1 it refused every Sodium up to 0.6.13, on 26.1 it
//   wanted a Fabric API that version does not have, and on NeoForge 1.21 its
//   mixin failed. A mod the launcher adds by itself must not be one that
//   stops the game when another mod updates.
// - Quilt, entirely: see boost_roles.
const FABRIC_ROLES: &[BoostRole] = &[
    BoostRole { role: "world drawing", description: "Draws the world much faster", rendering: true, since: 1, mods: &[SODIUM] },
    BoostRole { role: "game logic", description: "Speeds up the game's own work: mobs, physics, chunks", rendering: false, since: 1, mods: &[LITHIUM] },
    BoostRole { role: "memory", description: "Uses less memory", rendering: false, since: 1, mods: &[FERRITE] },
    BoostRole { role: "hidden entities", description: "Skips mobs and chests you cannot see", rendering: true, since: 1, mods: &[ENTITY_CULLING] },
    BoostRole { role: "screen drawing", description: "Faster HUD, text and menus", rendering: true, since: 1, mods: &[IMMEDIATELY_FAST] },
    BoostRole { role: "small costs of every frame", description: "Removes small costs from every frame", rendering: true, since: 2, mods: &[BAD_OPTIMIZATIONS] },
    BoostRole { role: "loading and memory", description: "Faster start and less memory", rendering: false, since: 2, mods: &[MODERNFIX, MODERNFIX_MVUS] },
];

const NEOFORGE_ROLES: &[BoostRole] = &[
    BoostRole { role: "world drawing", description: "Draws the world much faster", rendering: true, since: 2, mods: &[SODIUM, EMBEDDIUM] },
    BoostRole { role: "game logic", description: "Speeds up the game's own work: mobs, physics, chunks", rendering: false, since: 2, mods: &[LITHIUM] },
    BoostRole { role: "memory", description: "Uses less memory", rendering: false, since: 2, mods: &[FERRITE] },
    BoostRole { role: "hidden entities", description: "Skips mobs and chests you cannot see", rendering: true, since: 2, mods: &[ENTITY_CULLING] },
    BoostRole { role: "screen drawing", description: "Faster HUD, text and menus", rendering: true, since: 2, mods: &[IMMEDIATELY_FAST] },
    BoostRole { role: "small costs of every frame", description: "Removes small costs from every frame", rendering: true, since: 2, mods: &[BAD_OPTIMIZATIONS] },
    BoostRole { role: "loading and memory", description: "Faster start and less memory", rendering: false, since: 2, mods: &[MODERNFIX, MODERNFIX_MVUS] },
];

const FORGE_ROLES: &[BoostRole] = &[
    BoostRole { role: "world drawing", description: "Draws the world much faster", rendering: true, since: 2, mods: &[EMBEDDIUM, RUBIDIUM] },
    BoostRole { role: "game logic", description: "Speeds up the game's own work: mobs, physics, chunks", rendering: false, since: 2, mods: &[CANARY] },
    BoostRole { role: "memory", description: "Uses less memory", rendering: false, since: 2, mods: &[FERRITE] },
    BoostRole { role: "hidden entities", description: "Skips mobs and chests you cannot see", rendering: true, since: 2, mods: &[ENTITY_CULLING] },
    BoostRole { role: "screen drawing", description: "Faster HUD, text and menus", rendering: true, since: 2, mods: &[IMMEDIATELY_FAST] },
    BoostRole { role: "small costs of every frame", description: "Removes small costs from every frame", rendering: true, since: 2, mods: &[BAD_OPTIMIZATIONS] },
    BoostRole { role: "loading and memory", description: "Faster start and less memory", rendering: false, since: 2, mods: &[MODERNFIX] },
];

/// The roles for a loader. Minecraft alone loads no mods. Quilt gets none:
/// from 1.20.1 on, today's Fabric builds of these mods ask for a newer Fabric
/// Loader than Quilt Loader stands in for, and Quilt then stops at a "mods
/// require fabricloader" window instead of starting (1.20.1, 1.20.4, 1.21.1,
/// 1.21.4 and 1.21.8 in run 43837399268; only 1.18.2 and 1.19.2 started). A
/// player on Quilt adds what works there from the Mods page.
pub fn boost_roles(loader: &str) -> &'static [BoostRole] {
    match loader.trim().to_ascii_lowercase().as_str() {
        "fabric" => FABRIC_ROLES,
        "neoforge" => NEOFORGE_ROLES,
        "forge" => FORGE_ROLES,
        _ => &[],
    }
}

/// The set an instance's marker file records: 0 when there is none, 1 for
/// the marker older launchers wrote.
pub fn marker_version(text: Option<&str>) -> u32 {
    match text {
        None => 0,
        Some(text) => serde_json::from_str::<serde_json::Value>(text)
            .ok()
            .and_then(|v| v.get("version").and_then(|n| n.as_u64()))
            .map_or(1, |n| n as u32),
    }
}

/// Mods left out on a version because they stop the game there, each from a
/// run that showed it: (Modrinth slug, loader, Minecraft version).
/// - ImmediatelyFast, Fabric 1.19: the build listed for 1.19 crashes at start
///   on 1.19 itself (its font mixin finds no target; run 38039466377), and
///   works from 1.19.1.
/// - ImmediatelyFast, Forge 1.19 and 1.19.1: the build listed for them needs
///   Forge 43, which is 1.19.2's ("needs language provider javafml:43 or
///   above"; run 43837399268).
/// - BadOptimizations, Forge 1.20.3: its world renderer mixin does not apply
///   (run 43837399268).
pub const BOOST_EXCLUSIONS: &[(&str, &str, &str)] = &[
    ("immediatelyfast", "fabric", "1.19"),
    ("immediatelyfast", "forge", "1.19"),
    ("immediatelyfast", "forge", "1.19.1"),
    ("badoptimizations", "forge", "1.20.3"),
];

/// Whether the newest version Modrinth lists should replace the installed
/// one: it is a release, it is not the one installed, and the installed one
/// is not something the player put there on purpose ahead of it (a beta or
/// alpha, or a version published later). `installed` is the installed
/// version's kind and date when Modrinth still lists it for this game.
pub fn newer_release_than_installed(
    newest_kind: &str,
    newest_date: &str,
    newest_is_installed: bool,
    installed: Option<(&str, &str)>,
) -> bool {
    if newest_is_installed || !(newest_kind == "release" || newest_kind.is_empty()) {
        return false;
    }
    match installed {
        // No longer listed for this game: the listed release is the one to have.
        None => true,
        Some((kind, date)) => (kind == "release" || kind.is_empty()) && date < newest_date,
    }
}

pub fn boost_excluded(slug: &str, loader: &str, game_version: &str) -> bool {
    BOOST_EXCLUSIONS
        .iter()
        .any(|(s, l, v)| *s == slug && l.eq_ignore_ascii_case(loader.trim()) && *v == game_version.trim())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn versions_read_as_numbers() {
        assert_eq!(version_parts("1.21.11"), Some((1, 21, 11)));
        assert_eq!(version_parts("1.17"), Some((1, 17, 0)));
        assert_eq!(version_parts("26.1"), Some((26, 1, 0)));
        assert_eq!(version_parts("26.1.2"), Some((26, 1, 2)));
        assert_eq!(version_parts("24w14a"), None);
        assert_eq!(version_parts("1.21.1.1"), None);
        assert!(at_least("26.1", (1, 20, 5)));
        assert!(at_least("1.20.5", (1, 20, 5)));
        assert!(!at_least("1.20.4", (1, 20, 5)));
        assert!(!at_least("24w14a", (1, 18, 0)));
    }

    #[test]
    fn gpu_plan_writes_only_where_the_player_has_not_chosen() {
        assert_eq!(gpu_plan(None), GpuPlan::Write("GpuPreference=2;".into()));
        assert_eq!(gpu_plan(Some("")), GpuPlan::Write("GpuPreference=2;".into()));
        assert_eq!(gpu_plan(Some("GpuPreference=2;")), GpuPlan::AlreadyFast);
        assert_eq!(gpu_plan(Some("GpuPreference=1;")), GpuPlan::PlayersChoice);
        // "Let Windows decide" is no choice of a card.
        assert_eq!(gpu_plan(Some("GpuPreference=0;")), GpuPlan::Write("GpuPreference=2;".into()));
        // What else Windows keeps in the value stays.
        assert_eq!(
            gpu_plan(Some("SwapEffectUpgradeEnable=1;GpuPreference=0;")),
            GpuPlan::Write("GpuPreference=2;SwapEffectUpgradeEnable=1;".into())
        );
        assert_eq!(gpu_plan(Some("SwapEffectUpgradeEnable=1;")), GpuPlan::Write("GpuPreference=2;SwapEffectUpgradeEnable=1;".into()));
        assert_eq!(gpu_plan(Some("AutoHDREnable=1;GpuPreference=2;")), GpuPlan::AlreadyFast);
    }

    #[test]
    fn turning_the_setting_off_keeps_what_was_not_breezes() {
        assert_eq!(gpu_value_without_preference("GpuPreference=2;"), None);
        assert_eq!(gpu_value_without_preference("GpuPreference=2;SwapEffectUpgradeEnable=1;"), Some("SwapEffectUpgradeEnable=1;".into()));
    }

    #[test]
    fn gpu_record_round_trips_and_goes_when_empty() {
        let dir = std::env::temp_dir().join(format!("breeze-gpu-record-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        assert_eq!(read_gpu_record(&dir), GpuRecord::default());
        let record = GpuRecord { programs: vec![r"C:\Users\a\.breezeclient\runtime\java-21\bin\java.exe".into()] };
        write_gpu_record(&dir, &record);
        assert_eq!(read_gpu_record(&dir), record);
        write_gpu_record(&dir, &GpuRecord::default());
        assert!(!dir.join("gpu-preference.json").exists());
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// The real registry, under a key of the test's own, on a Windows build
    /// machine: written, read back, changed and removed.
    #[cfg(windows)]
    #[test]
    fn windows_registry_value_is_written_read_and_removed() {
        let key = format!(r"Software\BreezeClientTest\GpuPreferences-{}", std::process::id());
        let program = r"C:\Program Files\Breeze Test\bin\java.exe";
        assert_eq!(windows_gpu::read_in(&key, program), None);
        windows_gpu::write_in(&key, program, GPU_FAST).unwrap();
        assert_eq!(windows_gpu::read_in(&key, program).as_deref(), Some(GPU_FAST));
        assert_eq!(gpu_plan(windows_gpu::read_in(&key, program).as_deref()), GpuPlan::AlreadyFast);
        windows_gpu::write_in(&key, program, "GpuPreference=1;").unwrap();
        assert_eq!(gpu_plan(windows_gpu::read_in(&key, program).as_deref()), GpuPlan::PlayersChoice);
        windows_gpu::delete_in(&key, program);
        assert_eq!(windows_gpu::read_in(&key, program), None);
        // The test's own key goes too.
        use windows_sys::Win32::System::Registry::{RegDeleteKeyW, HKEY_CURRENT_USER};
        let wide: Vec<u16> = key.encode_utf16().chain(std::iter::once(0)).collect();
        unsafe { RegDeleteKeyW(HKEY_CURRENT_USER, wide.as_ptr()) };
    }

    fn gpu(vendor: u32, boot: bool) -> DrmGpu {
        DrmGpu { vendor, boot }
    }

    #[test]
    fn linux_offload_only_where_it_is_certain() {
        let nvidia = vec![("__NV_PRIME_RENDER_OFFLOAD", "1"), ("__GLX_VENDOR_LIBRARY_NAME", "nvidia"), ("__VK_LAYER_NV_optimus", "NVIDIA_only")];
        let prime = vec![("DRI_PRIME", "1")];
        // A laptop: Intel runs the screen, NVIDIA beside it.
        assert_eq!(linux_gpu_env(&[gpu(VENDOR_INTEL, true), gpu(VENDOR_NVIDIA, false)], true), nvidia);
        assert_eq!(linux_gpu_env(&[gpu(VENDOR_INTEL, true), gpu(VENDOR_NVIDIA, false)], false), prime);
        assert_eq!(linux_gpu_env(&[gpu(VENDOR_AMD, true), gpu(VENDOR_NVIDIA, false)], true), nvidia);
        assert_eq!(linux_gpu_env(&[gpu(VENDOR_INTEL, true), gpu(VENDOR_AMD, false)], false), prime);
        // One card, or the screen already on the fast card: nothing.
        assert!(linux_gpu_env(&[gpu(VENDOR_INTEL, true)], false).is_empty());
        assert!(linux_gpu_env(&[gpu(VENDOR_NVIDIA, true), gpu(VENDOR_INTEL, false)], true).is_empty());
        // Two AMD cards: which is the fast one is not known here.
        assert!(linux_gpu_env(&[gpu(VENDOR_AMD, true), gpu(VENDOR_AMD, false)], false).is_empty());
        // A desktop with the screen on its AMD card and an unused Intel one.
        assert!(linux_gpu_env(&[gpu(VENDOR_AMD, true), gpu(VENDOR_INTEL, false)], false).is_empty());
        // No card named as the screen's: nothing.
        assert!(linux_gpu_env(&[gpu(VENDOR_INTEL, false), gpu(VENDOR_NVIDIA, false)], true).is_empty());
        assert!(linux_gpu_env(&[], true).is_empty());
    }

    #[test]
    fn drm_cards_are_read_and_outputs_are_not_cards() {
        let dir = std::env::temp_dir().join(format!("breeze-drm-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        for (name, vendor, boot) in [("card0", "0x8086\n", "1\n"), ("card1", "0x10de\n", "0\n")] {
            let device = dir.join(name).join("device");
            std::fs::create_dir_all(&device).unwrap();
            std::fs::write(device.join("vendor"), vendor).unwrap();
            std::fs::write(device.join("boot_vga"), boot).unwrap();
        }
        std::fs::create_dir_all(dir.join("card0-eDP-1")).unwrap();
        std::fs::create_dir_all(dir.join("renderD128").join("device")).unwrap();
        std::fs::create_dir_all(dir.join("card2")).unwrap(); // no device files: skipped
        assert_eq!(read_drm_gpus(&dir), vec![gpu(VENDOR_INTEL, true), gpu(VENDOR_NVIDIA, false)]);
        assert!(read_drm_gpus(&dir.join("missing")).is_empty());
        let _ = std::fs::remove_dir_all(&dir);
    }

    fn value<'a>(options: &'a [PresetOption], key: &str) -> Option<&'a str> {
        options.iter().find(|o| o.key == key).map(|o| o.value.as_str())
    }

    #[test]
    fn presets_differ_where_it_costs_frames() {
        let max = preset_options(VideoPreset::MaxFps, "1.21.4", true);
        let balanced = preset_options(VideoPreset::Balanced, "1.21.4", true);
        let quality = preset_options(VideoPreset::Quality, "1.21.4", true);
        assert_eq!(value(&max, "renderDistance"), Some("6"));
        assert_eq!(value(&balanced, "renderDistance"), Some("12"));
        assert_eq!(value(&quality, "renderDistance"), Some("16"));
        assert_eq!(value(&max, "graphicsMode"), Some("0"));
        assert_eq!(value(&quality, "graphicsMode"), Some("1"));
        assert_eq!(value(&max, "renderClouds"), Some("\"false\""));
        assert_eq!(value(&balanced, "renderClouds"), Some("\"fast\""));
        assert_eq!(value(&quality, "renderClouds"), Some("\"true\""));
        // No preset keeps the 120 cap; only Quality waits for the screen.
        for options in [&max, &balanced, &quality] {
            assert_eq!(value(options, "maxFps"), Some("260"));
        }
        assert_eq!(value(&max, "enableVsync"), Some("false"));
        assert_eq!(value(&balanced, "enableVsync"), Some("false"));
        assert_eq!(value(&quality, "enableVsync"), Some("true"));
        // Never the look-defining options.
        for options in [&max, &balanced, &quality] {
            for untouched in ["ao", "mipmapLevels", "fov", "guiScale", "gamma", "fullscreen"] {
                assert_eq!(value(options, untouched), None, "{untouched}");
            }
        }
    }

    #[test]
    fn options_a_version_lacks_are_left_out() {
        let old = preset_options(VideoPreset::MaxFps, "1.17.1", true);
        assert_eq!(value(&old, "simulationDistance"), None);
        assert_eq!(value(&old, "menuBackgroundBlurriness"), None);
        assert_eq!(value(&preset_options(VideoPreset::MaxFps, "1.18", true), "simulationDistance"), Some("5"));
        assert_eq!(value(&preset_options(VideoPreset::MaxFps, "1.20.4", true), "menuBackgroundBlurriness"), None);
        assert_eq!(value(&preset_options(VideoPreset::MaxFps, "1.20.5", true), "menuBackgroundBlurriness"), Some("0.0"));
        assert_eq!(value(&preset_options(VideoPreset::Balanced, "1.21.4", true), "menuBackgroundBlurriness"), None);
        // A snapshot name: only what every version writes the same way.
        let snapshot = preset_options(VideoPreset::MaxFps, "25w14craftmine", true);
        assert_eq!(value(&snapshot, "simulationDistance"), None);
        assert_eq!(value(&snapshot, "graphicsMode"), None);
        assert_eq!(value(&snapshot, "renderClouds"), None);
        assert_eq!(value(&snapshot, "renderDistance"), Some("6"));
    }

    #[test]
    fn each_era_gets_its_own_form() {
        // Clouds: bare words up to 1.18.2, quoted from 1.19.
        assert_eq!(value(&preset_options(VideoPreset::Balanced, "1.18.2", true), "renderClouds"), Some("fast"));
        assert_eq!(value(&preset_options(VideoPreset::Balanced, "1.19", true), "renderClouds"), Some("\"fast\""));
        // The menu blur: a fraction in 1.20.5 and 1.20.6, 0 to 10 from 1.21.
        assert_eq!(value(&preset_options(VideoPreset::MaxFps, "1.20.6", true), "menuBackgroundBlurriness"), Some("0.0"));
        assert_eq!(value(&preset_options(VideoPreset::MaxFps, "1.21", true), "menuBackgroundBlurriness"), Some("0"));
        // One Graphics option up to 1.21.10, separate ones from 1.21.11.
        let before = preset_options(VideoPreset::MaxFps, "1.21.10", true);
        assert_eq!(value(&before, "graphicsMode"), Some("0"));
        assert_eq!(value(&before, "cutoutLeaves"), None);
        for version in ["1.21.11", "26.1", "26.3"] {
            // A new file is upgraded by the game from the old key; a file the
            // game saved takes the separate ones.
            let fresh = preset_options(VideoPreset::MaxFps, version, false);
            assert_eq!(value(&fresh, "graphicsMode"), Some("0"), "{version}");
            assert_eq!(value(&fresh, "cutoutLeaves"), None);
            assert_eq!(value(&fresh, "graphicsPreset"), None);
            let after = preset_options(VideoPreset::MaxFps, version, true);
            assert_eq!(value(&after, "graphicsMode"), None, "{version}");
            assert_eq!(value(&after, "graphicsPreset"), Some("\"custom\""));
            assert_eq!(value(&after, "cutoutLeaves"), Some("false"));
            assert_eq!(value(&after, "weatherRadius"), Some("5"));
            assert_eq!(value(&after, "vignette"), Some("false"));
            let fancy = preset_options(VideoPreset::Quality, version, true);
            assert_eq!(value(&fancy, "cutoutLeaves"), Some("true"));
            assert_eq!(value(&fancy, "weatherRadius"), Some("10"));
            assert_eq!(value(&fancy, "improvedTransparency"), None);
        }
    }

    /// What kind of value a line holds, as far as the game's reader cares.
    fn shape(value: &str) -> &'static str {
        if value.starts_with('"') && value.ends_with('"') && value.len() >= 2 {
            "quoted"
        } else if value == "true" || value == "false" {
            "bool"
        } else if value.parse::<i64>().is_ok() {
            "int"
        } else if value.parse::<f64>().is_ok() {
            "number"
        } else {
            "word"
        }
    }

    /// Every option of every preset, against the file each of the 34 versions
    /// wrote itself: the key exists there, and the value has the same form.
    #[test]
    fn every_option_matches_what_each_version_writes() {
        let facts = include_str!("testdata/options-facts.txt");
        let mut versions = 0;
        for row in facts.lines().filter(|l| !l.trim().is_empty()) {
            let (version, line) = row.split_once('\t').expect("version, tab, options");
            let written: std::collections::HashMap<&str, &str> =
                line.split(' ').filter_map(|pair| pair.split_once(':')).collect();
            for preset in VideoPreset::ALL {
                for wanted in preset_options(preset, version, true) {
                    let theirs = written
                        .get(wanted.key)
                        .unwrap_or_else(|| panic!("Minecraft {version} writes no {} ({} preset)", wanted.key, preset.label()));
                    let (ours, game) = (shape(&wanted.value), shape(theirs));
                    // The game writes clouds as the word true where the preset says fast or false.
                    let same = ours == game || (wanted.key == "renderClouds" && ours == "word" && game == "bool");
                    assert!(same, "Minecraft {version} writes {}:{theirs}, the {} preset would write {}", wanted.key, preset.label(), wanted.value);
                }
            }
            versions += 1;
        }
        assert_eq!(versions, 34);
    }

    #[test]
    fn applying_changes_only_the_presets_lines_and_undo_puts_them_back() {
        let before = "version:3465\nrenderDistance:12\nfov:0.5\nenableVsync:true\nmaxFps:120\nkey_key.attack:key.mouse.left\nrenderClouds:\"true\"\n";
        let options = preset_options(VideoPreset::MaxFps, "1.20.1", true);
        let (after, changes) = apply_options(before, &options);
        assert!(after.contains("\nrenderDistance:6\n"));
        assert!(after.contains("\nfov:0.5\n"));
        assert!(after.contains("\nkey_key.attack:key.mouse.left\n"));
        assert!(after.contains("\nenableVsync:false\n"));
        assert!(after.contains("\nmaxFps:260\n"));
        assert!(after.contains("\nrenderClouds:\"false\"\n"));
        assert!(after.contains("\nsimulationDistance:5\n"), "a missing option is added");
        assert!(after.starts_with("version:3465\n"));
        let render = changes.iter().find(|c| c.key == "renderDistance").unwrap();
        assert_eq!((render.before.as_deref(), render.after.as_str()), (Some("12"), "6"));
        assert_eq!(changes.iter().find(|c| c.key == "simulationDistance").unwrap().before, None);

        // Applying the same preset again changes nothing.
        let (again, none) = apply_options(&after, &options);
        assert_eq!(again, after);
        assert!(none.is_empty());

        // The player raises the render distance afterwards; undo leaves that.
        let touched = after.replace("renderDistance:6", "renderDistance:10");
        let (undone, count) = undo_options(&touched, &changes);
        assert!(undone.contains("\nrenderDistance:10\n"));
        assert!(undone.contains("\nenableVsync:true\n"));
        assert!(undone.contains("\nmaxFps:120\n"));
        assert!(!undone.contains("simulationDistance"), "an added line goes again");
        assert_eq!(count, changes.len() - 1);

        let (clean, all) = undo_options(&after, &changes);
        assert_eq!(clean, before);
        assert_eq!(all, changes.len());
    }

    #[test]
    fn a_preset_is_applied_to_an_instance_and_taken_back() {
        let dir = std::env::temp_dir().join(format!("breeze-preset-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let original = "renderDistance:12\nenableVsync:true\nfov:0.0\n";
        std::fs::write(dir.join("options.txt"), original).unwrap();

        let listed = apply_preset_to_instance(&dir, VideoPreset::Balanced, "1.21.4", true).unwrap();
        assert!(!listed.is_empty());
        assert_eq!(std::fs::read_to_string(dir.join("options.txt")).unwrap(), original, "a dry run writes nothing");
        assert!(!undo_path(&dir).exists());

        let changes = apply_preset_to_instance(&dir, VideoPreset::Balanced, "1.21.4", false).unwrap();
        assert_eq!(changes, listed);
        let text = std::fs::read_to_string(dir.join("options.txt")).unwrap();
        assert!(text.contains("enableVsync:false") && text.contains("fov:0.0"));
        assert!(undo_path(&dir).exists());

        assert_eq!(undo_preset_in_instance(&dir).unwrap(), changes.len());
        assert_eq!(std::fs::read_to_string(dir.join("options.txt")).unwrap(), original);
        assert!(undo_preset_in_instance(&dir).is_err(), "nothing left to take back");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn new_instances_start_from_the_setting() {
        assert_eq!(auto_preset(4096, 4), VideoPreset::MaxFps);
        assert_eq!(auto_preset(8192, 8), VideoPreset::MaxFps);
        assert_eq!(auto_preset(16384, 4), VideoPreset::MaxFps);
        assert_eq!(auto_preset(16384, 8), VideoPreset::Balanced);
        assert_eq!(preset_for_new_instance("auto", 16384, 12), Some(VideoPreset::Balanced));
        assert_eq!(preset_for_new_instance("", 4096, 2), Some(VideoPreset::MaxFps));
        assert_eq!(preset_for_new_instance("quality", 4096, 2), Some(VideoPreset::Quality));
        assert_eq!(preset_for_new_instance("minecraft", 4096, 2), None);

        assert!(game_written("version:4189\nfov:0.0\n"));
        assert!(!game_written("lang:en_us\ntutorialStep:none\n"));
        // From 1.21.11 a new instance's file names the old key, which the game upgrades.
        let new_file = first_options(Some(VideoPreset::MaxFps), "26.1");
        assert!(new_file.contains("graphicsMode:0\n") && !new_file.contains("cutoutLeaves"), "{new_file}");
        assert_eq!(first_options(None, "1.21.4"), "lang:en_us\ntutorialStep:none\n");
        let first = first_options(Some(VideoPreset::MaxFps), "1.21.4");
        assert!(first.starts_with("lang:en_us\ntutorialStep:none\n"));
        assert!(first.contains("renderDistance:6\n") && first.contains("enableVsync:false\n"));
    }

    #[test]
    fn only_a_newer_release_replaces_what_is_installed() {
        let old = Some(("release", "2024-06-29T10:00:00Z"));
        // Sodium 0.5.11 installed, 0.8.13 the newest release.
        assert!(newer_release_than_installed("release", "2026-08-28T10:00:00Z", false, old));
        // Already the newest.
        assert!(!newer_release_than_installed("release", "2026-08-28T10:00:00Z", true, Some(("release", "2026-08-28T10:00:00Z"))));
        // The newest listed is only a beta: nothing is changed for it.
        assert!(!newer_release_than_installed("beta", "2026-09-20T10:00:00Z", false, old));
        // The player runs a beta or a later version on purpose.
        assert!(!newer_release_than_installed("release", "2026-08-28T10:00:00Z", false, Some(("beta", "2026-09-20T10:00:00Z"))));
        assert!(!newer_release_than_installed("release", "2026-08-28T10:00:00Z", false, Some(("release", "2026-09-01T10:00:00Z"))));
        // The installed version is no longer listed for this game.
        assert!(newer_release_than_installed("release", "2026-08-28T10:00:00Z", false, None));
    }

    #[test]
    fn an_older_marker_leaves_only_the_newer_roles() {
        assert_eq!(marker_version(None), 0);
        assert_eq!(marker_version(Some("{\"version\":1}\n")), 1);
        assert_eq!(marker_version(Some("{\"version\":2}\n")), 2);
        assert_eq!(marker_version(Some("not json")), 1, "any marker means a pass was made");
        let after_first_set: Vec<&str> = boost_roles("fabric").iter().filter(|r| r.since > 1).map(|r| r.role).collect();
        assert_eq!(after_first_set, ["small costs of every frame", "loading and memory"]);
        assert!(boost_roles("fabric").iter().all(|r| r.since <= BOOST_SET_VERSION));
        assert!(boost_roles("forge").iter().chain(boost_roles("neoforge")).all(|r| r.since == 2));
    }

    #[test]
    fn every_loader_with_mods_has_a_set() {
        assert_eq!(boost_roles("fabric").len(), 7);
        // Quilt stops at a "mods require fabricloader" window with today's builds.
        assert!(boost_roles("Quilt").is_empty());
        assert!(boost_roles("forge").iter().any(|r| r.mods.iter().any(|m| m.slug == "embeddium")));
        assert!(boost_roles("neoforge").iter().any(|r| r.mods.first().map(|m| m.slug) == Some("sodium")));
        assert!(boost_roles("vanilla").is_empty());
        // Sodium itself has no Forge build; it must never be asked for there.
        assert!(!boost_roles("forge").iter().any(|r| r.mods.iter().any(|m| m.slug == "sodium" || m.slug == "lithium")));
        // One world renderer per role list, and every one of them is known as such.
        for loader in ["fabric", "forge", "neoforge"] {
            let world = &boost_roles(loader)[0];
            assert!(world.rendering);
            for m in world.mods {
                assert!(m.mod_ids.iter().all(|id| WORLD_RENDERERS.contains(id)), "{}", m.slug);
            }
        }
        assert!(boost_excluded("immediatelyfast", "fabric", "1.19"));
        assert!(!boost_excluded("immediatelyfast", "fabric", "1.19.1"));
        assert!(boost_excluded("immediatelyfast", "forge", "1.19.1"));
        assert!(!boost_excluded("immediatelyfast", "forge", "1.19.2"));
        assert!(boost_excluded("badoptimizations", "Forge", "1.20.3"));
        assert!(!boost_excluded("badoptimizations", "neoforge", "1.20.3"));
        assert!(!boost_excluded("sodium", "fabric", "1.19"));
        // A mod tied to exact versions of another is never added by itself.
        for loader in ["fabric", "forge", "neoforge"] {
            assert!(!boost_roles(loader).iter().any(|r| r.mods.iter().any(|m| m.slug == "moreculling")));
        }
    }
}
