# Changelog

Breeze mod 2.x. Versions are `MAJOR.MINOR.PATCH`: new features bump MINOR,
fixes bump PATCH. `scripts/check-version.sh` keeps `gradle.properties`,
`frontend/package.json` and this file in agreement.

Each entry says what was verified and how. Anything not verified is marked
**UNVERIFIED**.

## [2.4.1] - 2026-09-30 (not released)

Fixes from the first runs in a real Minecraft 1.20.1 client.

### Fixed
- Clicking Singleplayer on the Breeze title menu crashed the game ("Rendering
  screen", NullPointerException "Screen cannot be null") on a game with no
  worlds yet. The menu ran its queued screen changes from inside a frame, so
  Minecraft switched screens halfway through drawing one. Queued changes now
  run between frames (on the client tick).
- Keys pressed before the first mouse click went nowhere: the embedded browser
  was given focus after each event instead of before it, and focus requested
  at creation can arrive before there is a page. Focus is now given before
  every input event and again once the page has painted.
- Enter could not press a focused button: Chromium activates a button on
  Enter's character event, which Minecraft does not send. The menu now sends
  it after Enter's key-down.

### Changed (in-game test harness)
- The in-game run starts past Minecraft's first-launch accessibility screen,
  stops waiting as soon as the self-test reports a failure, and prints the
  self-test events, keys received, Breeze and MCEF log lines, errors and any
  crash report into the job output.
- The Singleplayer check accepts either of Minecraft's world selection
  screens: a new game folder has no worlds, so Minecraft opens Create World
  directly.

### Verified
In CI on the temporary build mirror, run 36661189403 (source identical to
this commit's `Breeze-Mod-New/`):
- Build: the jar built from the extracted 1.20.1 source ZIP, with JUnit, is
  `1.20.1.jar` (2,847,448 bytes), and its `fabric.mod.json` reads 2.4.1 for
  Minecraft `>=1.20 <1.20.2`.
- Interface: type check, Vitest, and 72 Playwright tests passed (Chromium 141
  and Chrome 116).
- Real Minecraft 1.20.1 (software rendered, real X11 input): 0 failed checks.
  The menu painted; Tab and Enter opened Mods; a click opened Settings; Escape
  went back and the page acknowledged it; Singleplayer opened Create World
  without a crash; Back returned to the Breeze menu; Escape at the root was
  declined and the menu stayed open; 20 open and close cycles left 0
  browsers open (heap 247 MB); Quit from the menu reached the game.

## [2.4.0] - 2026-09-29 (not released)

Cape previews in the Wardrobe.

### Added
- The Wardrobe shows each owned cape's front. The mod downloads the image
  (the first frame of an animated cape) under the same rules as in-game capes:
  only from the Breeze API over https, never following a redirect. It hands
  the image to the page as a `data:` URI, because the page itself may not load
  anything from the network. Nothing is uploaded to the GPU for a preview.
  The first open waits up to 2.5 seconds for the pictures, later opens use the
  ones already downloaded, and a failed image is retried after a minute
  (`cosmetics/CapePreviews`).
- Only real PNG, GIF and JPEG files up to 256 KB are passed on, recognised by
  their own first bytes (`common/.../cosmetics/ImageData`).
- `scripts/check-java-offline.sh --since` also checks new files not yet
  committed.

### Verified
- 83 JUnit tests (4 new: image recognition, refusal of SVG and HTML, exact
  bytes in the URI, size limit).
- 72 browser tests in Chromium 141 and Chrome 116; the Wardrobe test now
  checks that a cape with a picture shows it and one without shows the
  placeholder, using real 64x32 cape textures.
- Offline Java check: no errors in Breeze's own code in the 19 files changed
  or added since `f8665ed`.
- **UNVERIFIED**: compiling against Minecraft, and previews from the real API
  (needs a signed-in account).

## [2.3.0] - 2026-09-29 (not released)

Armor HUD, from the spec's module list.

### Added
- **Armor Status** is now the Armor HUD the spec describes: each piece's item
  icon with its durability shown as a percentage, remaining out of maximum,
  remaining only, a bar, or a bar with the percentage; stacked or in a row;
  item names, colour by durability (the same red-to-green ramp as Minecraft's
  own durability bar) and empty slots each switchable. Size, background,
  border, colours, transparency and spacing come from the shared HUD style,
  and position from the HUD editor. Layout is `common/.../hud/ArmorHudLayout`.
- Percentages never claim 100% for a piece that has taken a hit, or 0% for one
  with a use left.
- A module's own settings are listed before the shared HUD style on its menu
  page.
- HUD modules that draw icons or shapes (`drawsShapes`) get their background
  under their content; it used to be drawn on top, and they now report their
  real size to the HUD editor.

### Verified
- 79 JUnit tests (13 new for the Armor HUD layout, formats, percentages and
  colours).
- 72 browser tests in Chromium 141 and Chrome 116 (new: Armor Status settings
  fit an 854x480 window, and the format stepper and orientation write).
- Offline Java check: no errors in Breeze's own code in the 18 files changed
  since `f8665ed`.
- **UNVERIFIED**: compiling against Minecraft and the HUD in game (icon and
  text placement, bar colours, the HUD editor's handle).

## [2.2.1] - 2026-09-29 (not released)

### Fixed
- Fullbright made the game darker instead of brighter for anyone whose
  brightness was above the default. It set the brightness option to 100, but
  since 1.19 that option only accepts 0 to 1, so Minecraft refused the value
  and reset brightness to its default while Fullbright was on. Fullbright now
  leaves the option alone and changes only what the lightmap reads while it is
  on (`LightTextureMixin`), using 15 where the slider stops at 1. Worked
  through the lightmap: its darkest cell ends at about 1.3 before clamping, so
  everything is fully lit. If a later Minecraft moves that read, Fullbright
  stops working rather than the game failing to start (`require = 0`).

### Verified
- Offline Java check: no errors in Breeze's own code in the two changed files.
- 19 Vitest, bundle checks, version check.
- **UNVERIFIED**: compiling against Minecraft and brightness in game. The
  redirect's target (`OptionInstance.get()` inside
  `LightTexture.updateLightTexture`) is from 1.20.1's source as known, not
  checked against its bytecode here.

## [2.2.0] - 2026-09-29 (not released)

Low Fire, from the spec's module list.

### Added
- **Low Fire** replaces No Fire Overlay: a "Lower by" slider from 0 to 100%
  that moves the first-person fire overlay down, with its value shown and
  applied the moment it changes. 100% hides the overlay as No Fire Overlay
  did. Anyone who had No Fire Overlay on keeps it on, at 100%.
- Renamed modules keep what players saved: a module can list its earlier
  names, and the config loader looks there when the current name is absent
  (`common/.../settings/Renames`).

### Fixed
- 56 of the 81 module keybinds had no label, so Minecraft's Controls screen
  listed them as raw keys like `key.breeze.no_fire_overlay`. Every module now
  has a "Toggle ..." label.
- Keybind ids were lower-cased with the system's language, so on a Turkish
  system "Item Scale" became a different key from the one in the language file.

### Verified
- 66 JUnit tests (8 new: the Low Fire drop and the rename lookup).
- 70 browser tests in Chromium 141 and Chrome 116, including a new one: Low
  Fire's slider shows 50%, then 100% and 0%, and writes each value.
- Offline Java check: no errors in Breeze's own code in the 14 files changed
  since `f8665ed`.
- **UNVERIFIED**: compiling against Minecraft; how far the overlay moves at
  each value in a real game (the 0.55 full drop is worked out from the
  overlay's geometry at the default field of view, not measured); that a saved
  No Fire Overlay migrates in a real install.

## [2.1.1] - 2026-09-29 (not released)

Capes: the spec's activation bug and the unequip bug behind its two-client
test.

### Fixed
- Your own Breeze cape only showed while the Custom Cape module was on, and
  turning the module on opened the old cape editor. Equipping in the Wardrobe
  is now all it takes. From the Breeze menu, that editor would also have
  closed the menu in the middle of the request.
- An unequipped cape kept showing, on you and to other players. When the
  cosmetic state said "no cape", the renderer fell back to older lookups that
  are cached for the whole session. Once the state has loaded it is now the
  only source. This is what the spec's two-client test checks (A unequips, B
  sees it go).
- While an equipped cape was still downloading, an older cape could flash
  first: the check meant to wait could never be true.
- On the older cape route, a cape whose name had a space or capital letters
  (or any cape on a Turkish-language system) never got a texture, so it never
  drew.
- The README the mod writes into `breeze_capes/` was a developer note. It now
  says what the folder is for; one the player edited is left alone.

### Changed
- Custom Cape now means the cape wave plus a cape image of your own from
  `breeze_capes/`, shown only to you and only when no Breeze cape is
  equipped. The rule is `common/.../cosmetics/CapePolicy`.
- The old cape editor screen is no longer opened from anywhere (the Wardrobe
  replaces it). It is kept until the Phase 2 decision.

### Added
- `scripts/check-java-offline.sh`: type-checks a version module's Java against
  `common` with no Minecraft available, reporting mistakes in Breeze's own
  code. It cannot check calls into Minecraft.

### Verified
- 58 JUnit tests (17 new: cape policy and texture names). Putting the old
  module gate back makes 3 of them fail.
- Offline Java check: no errors in Breeze's own code in the 8 files changed
  since the last CI build (`f8665ed`), including this change.
- **UNVERIFIED**: compiling against Minecraft, capes in game, and the
  two-client test (needs CI and two signed-in accounts).

## [2.1.0] - 2026-09-29 (not released)

Phase 1 foundation: the in-game interface and the bridge, rebuilt.

### Added
- React 19 interface on the launcher's design system: rail and top bar, all
  ten theme materials, five accents, interface size, motion and transparency
  settings. Home, Mods (module cards, search, filters, per-module settings,
  installed mods), Wardrobe, Friends, Hosting and Settings, each with loading,
  empty, error and signed-out states.
- Breeze title menu that stands in for Minecraft's title screen (can be turned
  off, or skipped for one session). Singleplayer, Multiplayer, Options and
  Quit open Minecraft's own flows.
- Bridge over a JCEF message router (`window.breezeQuery`): typed actions and
  errors in `contract/bridge.json`, per-action threads (never blocking CEF or
  the game thread on the network), timeouts, cancellation, duplicate and
  in-flight limits, exactly-once answers, and an Escape handshake with a
  500 ms failsafe.
- The page is served from the jar at `https://breeze.local/` with path and
  origin checks, a production CSP with no network access, and only Breeze's
  own links allowed out to the system browser.
- Development self-test (`-Dbreeze.autotest`) and a CI job that launches a real
  client, drives it with X11 input and checks open/close for leaked browsers.
- `scripts/package-source.sh`: the release source ZIP for one Minecraft version
  (`Breeze Mod for 1.20.1.zip`), reproducible, from committed files only. CI
  builds the jar from it.
- README with build, test and run instructions; the phase A version matrix
  (`docs/MOD_VERSION_MATRIX.md`), which code comments already cited.

### Fixed
- Settings: a reply to an earlier write could undo a newer change.
- Custom Cape's description was a developer note.
- Equipping a cape required a loaded world, so it could not work from the
  title menu.
- Joining a friend's hosted world and the Wardrobe's player preview failed in
  every real install: they looked Minecraft classes up by Mojang names
  (`ServerData`, `clearLevel`, `renderEntityInInventoryFollowsMouse`), which
  only exist in the development client. They are direct calls now.
- Found in review before the first in-game run: falling back from a failed
  browser switched screens from inside Minecraft's own setScreen; one Escape
  pressed while the title menu was still loading would have switched the
  session to the vanilla title screen; the cape-equip waits could outlast
  the request's own timeout.
- The frontend's bundle check could not find the build when the project path
  had a space or a Windows drive letter in it (it read the path from a URL
  without decoding it), so `npm run build`, and with it the Gradle build,
  failed in such folders. Found by building from the extracted source ZIP.

### Verified
- Frontend: typecheck, 19 unit tests, 68 browser tests in Chromium 141 and
  Chrome 116 (the engine MCEF embeds), bundle checks.
- Bridge core (`common`): 41 JUnit tests.
- Build: the 1.20.1 module compiled against Minecraft 1.20.1 and remapped a
  jar in CI run 1 (commit f8665ed). Later Java changes are small and have not
  been through CI yet, because Actions stopped starting jobs.
- Source ZIP: packaged twice from the same commit with identical SHA-256;
  extracted under a path with spaces, it passed the version check, 41 JUnit,
  19 Vitest, 34 Chromium browser tests and the bundle checks. **UNVERIFIED**:
  building the 1.20.1 jar from it (needs Fabric's Maven; CI does this).
- **UNVERIFIED**: everything in a running Minecraft (the CI job that launches
  the game has not been able to run).

## [2.0.0] - 2026-09-27 (not released)

The phase A tree from `mod/rebuild-phase-a` (`5a1389e`): Gradle multi-project
with `common` and `versions/1.20.1`, the modules ported from the old mod, and a
first MCEF screen. Imported into this branch as the baseline.
