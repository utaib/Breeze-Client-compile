'use strict';

// ─────────────────────────────────────────────────────────────────────────────
// Breeze Client, update manifest (single source of truth for the updater)
//
// Cross-platform: each launcher release has a build per OS. Drop the built
// installer in versions/launcher/<os>/ and keep the matching entry below in
// sync (or override with env vars). The launcher detects its own OS, asks the
// API for this manifest, compares versions, and downloads the right file.
//
// Publishing a release = upload the new installer(s) + bump the version here.
// No application code changes are ever required. See versions/launcher/README.txt.
// ─────────────────────────────────────────────────────────────────────────────

const V = process.env.LATEST_LAUNCHER_VERSION || '1.0.12';
const RELEASED_AT = process.env.LATEST_LAUNCHER_DATE || '2026-08-02';
const CHANGELOG =
    process.env.LATEST_LAUNCHER_CHANGELOG ||
    'Capes now render correctly: HD capes are smooth instead of shimmering, standard capes stay sharp, and animated capes play again. Bigger, roomier store purchase panel. Custom window controls replace the mismatched Windows title bar.';

module.exports = {
    launcher: {
        latest: V,
        releasedAt: RELEASED_AT,
        mandatory: process.env.LATEST_LAUNCHER_MANDATORY === 'true',
        changelog: CHANGELOG,
        notesUrl: process.env.LATEST_LAUNCHER_NOTES_URL || null,
        channel: 'stable',

        // Per-OS installers. `file` is relative to versions/launcher/.
        // Only Windows ships pre-built here; Linux/macOS builds are produced on
        // their own OSes (Tauri can't cross-compile) and dropped in later, the
        // manifest already points at where they'll live.
        platforms: {
            windows: {
                version: V,
                releasedAt: RELEASED_AT,
                file: process.env.LATEST_LAUNCHER_WINDOWS_FILE || `windows/Breeze-Client-${V}.exe`,
                sha256:
                    process.env.LATEST_LAUNCHER_WINDOWS_SHA256 ||
                    '9ddec551d14344461ea598107c91985e3a4a1672506dd890a3a8227ea41dea11',
                kind: 'nsis',
                ext: 'exe',
                arch: 'x64',
                label: 'Windows 10 / 11 (64-bit)',
            },
            linux: {
                version: V,
                releasedAt: RELEASED_AT,
                file: process.env.LATEST_LAUNCHER_LINUX_FILE || `linux/Breeze-Client-${V}.AppImage`,
                sha256: process.env.LATEST_LAUNCHER_LINUX_SHA256 || null,
                kind: 'appimage',
                ext: 'AppImage',
                arch: 'x86_64',
                label: 'Linux (AppImage, 64-bit)',
            },
            macos: {
                version: V,
                releasedAt: RELEASED_AT,
                file: process.env.LATEST_LAUNCHER_MACOS_FILE || `macos/Breeze-Client-${V}.dmg`,
                sha256: process.env.LATEST_LAUNCHER_MACOS_SHA256 || null,
                kind: 'dmg',
                ext: 'dmg',
                arch: 'universal',
                label: 'macOS 12+ (Universal)',
            },
        },

        // Back-compat single-file fields (default to the Windows build) so any
        // older client / website code still resolves a download.
        version: V,
        fileName: process.env.LATEST_LAUNCHER_FILE || `windows/Breeze-Client-${V}.exe`,
        sha256:
            process.env.LATEST_LAUNCHER_SHA256 ||
            'e668743d00ee2a76d01a4917d4795f4c8f86248762ad8bea0f34cc59249e9003',
    },

    mod: {
        latest: process.env.LATEST_MOD_VERSION || '1.0.0',
        versions: [
            {
                version: process.env.LATEST_MOD_VERSION || '1.0.0',
                fileName: process.env.LATEST_MOD_FILE || '',
                sha256: process.env.LATEST_MOD_SHA256 || null,
                channel: 'stable',
            },
        ],
    },
};
