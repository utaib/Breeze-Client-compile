Breeze Client, Linux installer formats
======================================

One folder per format. Each is published, listed and updated independently, so
a user picks the one that fits their distribution instead of guessing.

    linux/
      appimage/  Breeze-Client-<version>.AppImage    portable, any distro
      deb/       Breeze-Client-<version>.deb         Debian, Ubuntu, Mint, Pop!_OS
      rpm/       Breeze-Client-<version>.rpm         Fedora, RHEL, openSUSE
      flatpak/   Breeze-Client-<version>.flatpak     sandboxed, any distro

NAMING (exact, the updater parses the version out of the filename)

    Breeze-Client-<version>.<ext>      e.g. Breeze-Client-1.0.11.AppImage

Drop a correctly named file into the right folder and it is picked up
automatically. No versions.js edit is required for the file to be found; only
the sha256 needs adding there if you want download verification.

AppImage is the format the in-launcher auto-updater uses on Linux, because it is
the only one that does not require root to install.

Files placed directly in linux/ (the pre-1.0.11 layout) are still recognised.
