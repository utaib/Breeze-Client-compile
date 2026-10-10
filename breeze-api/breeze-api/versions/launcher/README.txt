Breeze Launcher installers
==========================

One folder per system. The API serves what is here and the launcher's updater
picks the file for the player's system.

    versions/launcher/
      windows/   Breeze-Client-<version>-x86_64.exe
      macos/     Breeze-Client-<version>-universal.dmg
      linux/     see linux/README.txt (AppImage, deb, rpm)
      testing/   test channels for creators and above, see testing/README.txt

Served at:  https://api.breezeclient.net/versions/launcher/<os>/<file>

The tested installers for the current release, their SHA-256 hashes and the
exact upload steps are in launcher-builds/README.md at the repository root.
In short:

1. Copy each file into its folder here (on the API host).
2. Update the launcher entry in versions/versions.js from
   launcher-builds/release-metadata.json, or set LATEST_LAUNCHER_VERSION and
   the LATEST_LAUNCHER_<OS>_FILE / _SHA256 variables.
3. Restart the API and check that /versions/check offers the new version.

Building installers: docs/BUILD.md and docs/RELEASES.md. They are built on the
public build repository while this repository's Actions are blocked.

The installers are unsigned, so Windows SmartScreen warns and macOS Gatekeeper
refuses until they are signed. Do not try to get around either; sign them.
