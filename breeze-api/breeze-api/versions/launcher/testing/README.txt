Breeze Client, test build channels
==================================

Unreleased builds that only creators, admins and owners can see or download.
Normal users never receive these, and the public download page never lists them.

STRUCTURE (one folder per channel, then one per OS)
---------------------------------------------------
    testing/
      pre-beta/      Earliest builds. Rough edges, breaking changes expected.
        windows/     Breeze-Client-<version>.exe
        macos/       Breeze-Client-<version>.dmg
        linux/       Breeze-Client-<version>.AppImage
      beta/          Feature complete, still being tested. Same 3 OS folders.
      pre-release/   Release candidates, final checks. Same 3 OS folders.

NAMING (keep it exact, the updater parses the version out of the filename)
--------------------------------------------------------------------------
    Breeze-Client-<version>.<ext>

    Stable style      Breeze-Client-1.0.6.exe
    Pre-release style Breeze-Client-1.0.7-beta.1.exe

HOW AN UPDATE BECOMES VISIBLE
-----------------------------
A test build only shows as an update when its version is HIGHER than the
current stable version in versions.js. Publishing 1.0.6 to a channel while
stable is already 1.0.6 shows nothing, that is not a bug.

Served at: /versions/launcher/testing/<channel>/<os>/<file>
Listed at: /creator/test-builds        (creator, admin, owner only)
Offered by: /versions/check?platform=<os>   (role aware)
