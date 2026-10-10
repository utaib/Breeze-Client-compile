macOS launcher releases go here.

Build ON a Mac (Tauri can't cross-compile bundles):
    cd breeze\ launcher && npm run tauri build
Artifact appears under src-tauri/target/release/bundle/dmg/.

Drop the .dmg here with the naming convention:
    Breeze-Client-<version>.dmg            (universal, or -x64 / -aarch64 if split)

Then update versions/versions.js (launcher.platforms.macos.file + version)
or the LATEST_LAUNCHER_MACOS_FILE env var, and restart the API.
For a signed + notarized build, set the Apple signing env before building.
