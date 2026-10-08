# Changelog

Breeze mod 2.x. Versions are `MAJOR.MINOR.PATCH`: new features bump MINOR,
fixes bump PATCH. `scripts/check-version.sh` keeps `gradle.properties`,
`frontend/package.json` and this file in agreement.

Each entry says what was verified and how. Anything not verified is marked
**UNVERIFIED**.

## [2.13.0] - 2026-10-08

Minecraft's own items and pictures on the HUD, and your real inventory laid
out as Minecraft's inventory screen.

### Added
- **Item icons on the item modules.** Totem Counter, Held Item, Item
  Counter and Item Info draw the item itself with Minecraft's item renderer
  (its count, durability bar and enchantment glint are Minecraft's own), with
  the number or name beside it. Armor Bar draws Minecraft's armour point.
  Each has an icon switch; off, the text is as before.
- **Potion Effects** lists each effect with Minecraft's icon for it, its
  name in your language, its level and the time left (m:ss, "infinite" for
  an effect without an end). Before, a line read only "II (30s)" and did not
  say which effect.
- **Inventory HUD** lays your real inventory out as Minecraft's inventory
  screen does: three rows, then the hotbar below them, on Minecraft's slot
  picture. New switches: Hotbar row, Armour and off hand (the armour you wear
  in a column on the left, the off hand after the hotbar), Slot
  backgrounds. Read from the player every frame; nothing is copied.
- **Empty slots** in Armor Status (with "Keep empty slots") and in the
  Inventory HUD's armour column show Minecraft's empty-slot pictures.
- Every picture is looked up in the running game (`GameTextures`, common):
  the sprite where this version has one, the older texture where it does not
  (empty armour slots, the armour point), so a resource pack changes them as
  it changes Minecraft's. Nothing is copied into the jar.

### Tests
- `GameTexturesTest` (6), `InventoryHudLayoutTest` (5).
- In game: after the Armor HUD check, the self-test fills the inventory
  through the game's own server (stone 64, dirt 32, iron ingots, diamonds, a
  netherite ingot, a worn iron pickaxe, a diamond axe, a totem, bread,
  planks; the armour, sword and shield stay on), shows the Inventory HUD with
  armour and the item modules, screenshots them (`autotest-inventory-hud`)
  and checks each slot drew the right item and count, the totem, held item
  and item info modules drew Minecraft's items, the armour point was found,
  and which of Minecraft's pictures this version has (`inventory-check`).

## [2.12.0] - 2026-10-03

Minecraft icons for every module, hands on the Armor HUD, Minecraft's own
settings one click from Breeze's, and other mods in Breeze's menus.

### Added
- **Other mods in Breeze's menus, through their real APIs.** Breeze reads
  what Fabric actually loaded (each mod marked Breeze, game, library or mod;
  a library is a jar bundled in another, or one its author badges as one
  for Mod Menu) and asks two published APIs what can be opened
  (`dev.breeze.integrations`):
  - **Mod Menu**: its own mods list (`ModMenuApi.createModsScreen`), and each
    mod's settings screen from the `modmenu` entrypoint
    (`getModConfigScreenFactory`, `getProvidedConfigScreenFactories`), called
    by name so Breeze neither needs Mod Menu nor copies it. A factory that
    makes no screen is not offered (asked once, as Mod Menu itself does).
  - **The `breeze` entrypoint**, for any mod: two public methods,
    `String breezeLabel()` and `Screen breezeOpen(Screen parent)`, no
    dependency on Breeze (`docs/INTEGRATIONS.md`).
  The web menu's rail gets an entry per integration (Mod Menu opens its
  list; closing it comes back to Breeze), and Installed mods gets a Settings
  button on every mod with a settings screen. The native menu's sidebar
  lists them under the module tabs. Nothing is listed for a mod that is not
  loaded, so no entry is dead; an API that fails is shown as a problem and
  left out, and Breeze carries on (`integrations.list`, `integrations.open`).
- **Mod Menu's "Mods" button on the game menu.** With the web menu on,
  Breeze shows its own title screen, so Mod Menu's title button never
  appeared there (the rail entry replaces it). On the game menu Breeze looks
  one tick after the screen is built and adds a Mods button only when Mod
  Menu is loaded and its own is not there; never a second one.
- **`.breeze/runtime-mods.json`** in the game folder at startup: the mods
  Fabric loaded, the integrations found and their problems, for the launcher
  (1.0.28 shows which mods in the folder Fabric did not load).
- **Every module has a Minecraft icon** instead of the category symbol: a
  clock for the Stopwatch, a redstone torch for FPS, the hunger drumstick for
  Saturation, a heart for Hearts, diamond armour for the Armor HUD, and so on
  for all 81 (`common/.../ui/ModuleIcons`). Each is read from the running
  game's own textures (`ModuleIconCache`, `compat/Resources`), so it is that
  Minecraft version's texture, and a resource pack changes it too; nothing is
  copied into the jar. Where a texture moved between versions the next place
  is tried (the heart and drumstick were cut from `gui/icons.png` until
  1.20.2 made them sprites). The web menu gets them through the new
  `modules.icons` action and draws them pixel for pixel on the Mods page and
  each module's page; the native menu draws them on its module cards.
- **The Armor HUD shows your hands**: the item in your main hand (a sword
  with its durability) and your off hand (a shield), after the four armour
  pieces, with the same icons, formats and durability colours. A stack that
  has no durability shows its count (arrows, totems). New "Hands" setting,
  on by default.
- **Minecraft settings** in Breeze's settings: the first section of the web
  Settings page, and a button at the top of the native settings screen. It
  opens Minecraft's own options; Done comes back to Breeze.

### Changed
- **Tags are Breeze's own Wind Charge art** (owner: "the asset we have like
  the wind charge"). Since 2.10.0 a tag was a small grey 8 pixel drawing
  tinted with the tag's colour. Now it is the repository's Wind Charge
  pictures (`sty/*wind_charge.png`, the same files as the web menu's), each
  cut to the 12x10 pixels the charge covers, unscaled and checked pixel for
  pixel against the 512 pixel originals, and drawn in their own colours:
  red for Owner, purple for Developer and Admin, yellow for Creator and
  Donator, blue for Breeze, green, pink, and the plain one for a grey
  (`common/.../ui/TagArt` picks it from the API's tag colour). They are
  seven characters of the font (U+EB2E to U+EB34,
  `assets/breeze/textures/font/tags.png`), 8 high like Minecraft's letters,
  above the head, in the tab list, in chat and in the Wardrobe (which shows
  each tag's picture with its name in the tag's colour). On 1.17 the tag
  stays a star in the tag's colour: that version's font cannot take Breeze's
  font file (see the 1.17 fix below).

### Fixed
- **Every text in Minecraft 1.17 drew as empty boxes** (2.10.0 to 2.11.0,
  1.17 only), Minecraft's own menus and chat included. The Wind Charge tag
  glyph is added through `minecraft:font/default.json` and `uniform.json`;
  with the Fabric API that exists for 1.17 (0.36.0, which serves every mod's
  resources as one pack) that file took the place of Minecraft's font
  instead of adding to it, silently. 1.17.1 and later add to it as they
  should (checked in screenshots of 1.17.1, 1.18, 1.18.2, 1.19, 1.19.2 to
  1.19.4, 1.20). The 1.17 jar no longer carries those files, and its tag is
  a star from Minecraft's own font. The self-test now checks that "i" draws
  narrower than "W" on every version (`font-check`); the old tag check
  passed on 1.17 because the Wind Charge glyph itself was there.
- **One failed open of the web menu switched it off until restart.** When
  the embedded browser could not be created, or its page origin or bridge
  could not be set up, the title screen became Minecraft's for the whole
  session (`UiState.useVanillaTitle(true)`), and the log said only "the
  embedded browser is not available". Now Minecraft's title screen shows for
  a moment and the web menu is tried again 3 s later, up to 3 times a
  session; each failure is logged with its reason and attempt number (where
  it failed: Chromium not ready, origin or bridge, or the exception Chromium
  threw). Choosing Minecraft's title screen yourself still holds for the
  session. When MCEF itself fails to start, the log line points at MCEF's
  own lines before it. The cause of the fallback players saw is not proven:
  CI starts the web menu on every web version; a player's `latest.log` from
  a session where it happened would show which reason it was.
- **The web menu could stay dark with a page that never started.** In run
  37783649946 on 26.1.1, the first browser after Chromium's first download
  painted nothing and its page never ran for over a minute (keys and clicks
  did nothing), while the next browser, opened later in the run, worked.
  Nothing noticed: the open had "succeeded" when the browser was created.
  Now an open succeeds when the page first calls the bridge (logged as "the
  interface page answered N ms after its browser opened"). A page that has
  not done so 12 s after its browser opened counts as a failed open, with
  the reason (Chromium painted nothing, or painted but the script never
  reached the bridge): Minecraft's title screen for 3 s, then a fresh
  browser, up to 3 times a session, as for the other failed opens. Whether
  this is what players saw as the fallback is not proven (UNVERIFIED).
- **Mod Menu's mods list crashed the game** on versions with an older Mod
  Menu (seen with 7.2.2 on 1.20.1): Breeze's `fabric.mod.json` declared the
  badge `client`, which is not a badge key (Mod Menu gives that badge from
  `"environment": "client"` itself), and that Mod Menu drew the unknown key as
  a null badge (`Mod$Badge.getText()` on null in `ModBadgeRenderer`). The
  block is gone; Breeze still shows as client-side. Found in run 37769870690,
  whose mods summary lists every mod's declared badges: only Breeze declared
  `client`.

### Tests
- `ModuleIconsTest` (6), `ImageDataTest` (PNG size), `ArmorHudLayoutTest`
  (hands and stack counts).
- `TagArtTest` (4): the API's role colours get their pictures, the font
  files list one character per picture over the whole sheet with letter
  metrics, and every picture reaches its cell's edge. In game, `tag-icon`
  measures all seven characters (11 wide each, or the star on 1.17), and
  the world step opens the Wardrobe's Tags tab with the six tags of the live
  tags table (served by the stand-in API, Owner worn) and screenshots it
  (`autotest-wardrobe-tags`, event `wardrobe-tags`).
- In game: the self-test logs `module-icons` (every module found its icon in
  that version's textures, and which needed a later place to look); the
  world step equips worn diamond and iron armour, a diamond sword and a
  shield, screenshots the Armor HUD alone (`autotest-armor-hud`) and checks
  six pieces were drawn with the nearly broken chestplate in red
  (`armor-check`). The web driver checks the Mods page asked for the icons;
  the native driver opens Minecraft settings from Breeze's and comes back.
- `IntegrationsTest` (5): Mod Menu's API called by name against stand-ins
  with its published shape (mods list, a settings factory, the default
  factory with no screen, provided factories), absent Mod Menu, the
  `breeze` entrypoint, mod kinds, the runtime report.
- In game: the self-test logs `integrations` at the title screen and, the
  first time it leaves the menu, opens Mod Menu's list through the same call
  as the rail entry (`integration-open`). CI can now add real mods from
  Modrinth with their dependencies (`perf_mods`, e.g. `modmenu sodium
  reeses-sodium-options zoomify xaeros-minimap xaeros-world-map flashback`);
  with Mod Menu among them the driver checks Breeze found it, opened its
  list, and that the game menu has exactly one Mods button. `duplicate`
  installs a mod twice: Fabric must refuse to start (its words are kept in
  `refusal-excerpt.txt`), then the older copy is moved out and the full test
  runs. Each run keeps `mods-summary.jsonl` (what every jar declares) and
  `runtime-mods.json`.
- The web page refuses its sample data inside the game: served from
  `https://breeze.local/` without the bridge, it shows the startup error
  instead of the standalone preview.
- `fail_web_opens` (CI input): the first N opens of the web menu fail on
  purpose (`-Dbreeze.autotest.failWebOpens`); the driver requires each to be
  logged with its reason and the web menu to come back by itself.
- `stall_web_pages` (CI input): the first N browsers open `about:blank`, a
  page that never calls the bridge (`-Dbreeze.autotest.stallWebPages`); the
  driver requires each to be replaced by itself and the real page to start.
  The driver now waits up to 45 s for the page's first route, enough for one
  replacement, and prints the page's start time from the game log.
- Test harness: the test player's air is kept full under water (a random
  seed spawned 1.19.1 in the sea and it drowned during the module sweep,
  which failed three later checks); "Display, input and capture tools"
  retries a stalled apt up to three times (it cost 1.20.1 and 1.21.4 their
  jobs once); the mixin audit reads Gson with `fromJson`, which 1.17's Gson
  has.

## [2.11.0] - 2026-10-03

The web menu on the newer Minecraft versions.

### Added
- **The embedded browser on Minecraft 1.21.5, 1.21.6, 1.21.7, 1.21.8,
  1.21.10, 1.21.11, 26.1.1, 26.1.2, 26.2 and 26.3.** MCEF stops at 1.21.4.
  Keksuccino's continuation of it (Modrinth project `rinku`, LGPL-2.1+)
  publishes builds for these versions (read in CI with
  `browser-facts.sh`): MCEF 2.1.6 and 2.1.7 for 1.21.5 to 1.21.10 and 2.2.0
  for 26.1.x (the same API as before), Rinku 3.0.4 and 3.0.5 for 1.21.11,
  26.2 and 26.3 (the same API under new names, renamed at build time by
  `versions/shared/rinku/renames.txt`). The build travels inside Breeze's
  jar (Fabric nested jar), so the launcher needs no update.
- These builds no longer draw the page by OpenGL texture number, which
  Minecraft 1.21.5 removed; `BrowserFrame` draws their named texture
  (`versions/shared/browser-texture`).
- Not covered: 1.21.9 (the only build for it refuses its Fabric API,
  0.134.1, below its 0.135.0) and 26.1 (no build), which keep the native
  menus, as do 1.17 to 1.20 and 1.20.5.
- The in-game test now fails when a version that carries the browser comes
  up on the native menus (`EXPECT_WEB`).

### Fixed
- **Clicks in the web menu did nothing on 26.3.** Breeze numbers mouse
  buttons the GLFW way (0 is left) and handed that number to the browser.
  Rinku 3.0.5 maps Minecraft's own number itself (read from its bytecode:
  `RinkuInput.toCefMouseButton`, and its example screen passes
  `MouseButtonEvent.button()`), and on 26.3 SDL's left button is 1, so a 0
  was dropped. The browser now gets Minecraft's number
  (`Buttons.toGame`), which is the same number on every other version.
  Found by run 37110433757: 26.3's keyboard checks passed, its three click
  checks failed.

### Verified
- **All 34 versions passed**, run 37115332707 on the public build
  repository (`utaib/Breeze-Client-compile` commit `adc94d5`, an exact copy
  of this `Breeze-Mod-New/` at `8be908a`), set up as launcher 1.0.27 does.
  The ten versions with the browser inside the jar came up on the web menu
  (`EXPECT_WEB`), Rinku downloaded Chromium on first start, and every
  keyboard, mouse and in-world check passed; on 26.3 the clicks that failed
  in run 37110433757 (Settings, Singleplayer, Quit) passed. Jars in
  `Breeze Jars/` (SHA-256 equal to the run's); each bundled jar carries its
  one nested browser build. Screenshots on `ci/evidence-37115332707` there.
- Not verified: players' machines (Windows, macOS), a real signed-in
  account.

## [2.10.0] - 2026-10-03

For the testers: capes you own listed and worn from the game, tags as
icons.

### Changed
- **The Wardrobe lists the capes on your account.** Read from the live API
  in CI: its cosmetic state answered "no account" for every player, so the
  Wardrobe was empty and refused every equip. It now takes the list from
  the cosmetic state when that describes your account, and otherwise from
  the older routes, which read the same tables: `/capes/:uuid` (the
  catalogue capes you own; the API's test images are left out),
  `/selected/:uuid` (the one you wear) and the public catalogue `GET
  /capes` for names and images (`AccountCapes`, `CapeLists`). Your personal
  cape is listed first while you wear it.
- **Capes you do not own are shown under "More capes"**, marked "In the
  launcher", without an Equip button: they are got in the launcher's store.
  The native Wardrobe ends with the same pointer.
- **Remove cape only where it works.** The live API's older route cannot
  clear the account's cape link, so a removed cape came straight back; the
  button shows once the API describes your account (its fix deployed).
  Equipping works on the live API.
- **Tags are icons.** The text tags (`[Breeze]`, `[Owner]` ...) are gone
  from name tags, the tab list and chat. Each Breeze player has a Wind
  Charge icon in their tag's colour instead (Owner red, a creator's chosen
  colour, Breeze blue). It is a character of the game's font
  (`assets/minecraft/font/default.json` and `uniform.json`, picture
  `assets/breeze/textures/font/wind_charge.png`), so text drawing places it
  on every version. The second icon beside the ping bars in the tab list is
  removed. The Wardrobe shows the icon beside each tag and in its preview.

### Verified
- `:common:test` 136 passed, including `CapeListsTest` (6, with the live
  API's answers).
- Front end: tsc, 19 Vitest, `account.spec.ts` 18 passed on Chromium 141
  and Chrome 116 (new: More capes, no Remove on the old API).
- **In game, all 34 versions passed**, run 37110404967 on the public build
  repository (`utaib/Breeze-Client-compile` commit `4fdb123`, an exact copy
  of this `Breeze-Mod-New/` at `738a633`): set up as launcher 1.0.27 does,
  every menu, HUD, module and Wardrobe check, `tag-icon` (the Wind Charge
  is one 9-unit character of the game's font), cape-check from the
  account and the personal-cape switch. Jars in `Breeze Jars/` (SHA-256
  equal to the run's), screenshots on `ci/evidence-37110404967` there.
- Not verified: a real signed-in account (equip against the live API, the
  icon beside a real player's name on a server).

## [2.9.5] - 2026-10-03

Fix: Breeze capes did not show in game.

### Fixed
- **No Breeze cape showed in game**, your own or anyone else's. The mod takes
  the cosmetic state (`GET /cosmetics/state/:uuid`) as the truth once it has
  loaded. The live API answered that route with "not a Breeze account, no
  cape" for every player: it looked players up with a dashed uuid while the
  database stores every uuid undashed, and it asked for a column the users
  table does not have (`equipped_cape_id`). The mod believed it and drew
  nothing. A state that does not describe an account is no longer taken as
  the truth (`CapePolicy.trustState`): your own cape then comes from
  `/selected` and `/capefile`, other players' from `/cape/:uuid`, which read
  the equipped cape from `user_capes` and work with the live API.
- **Cape images stored with an `http://` link were refused.** Every cape in
  the catalogue is stored as `http://api.breezeclient.net/...`, and only
  https was fetched. A link on the API's own host is now upgraded to https
  (`AssetUrls`, the same rule the API applies); any other host is still
  refused.
- On those routes your own cape is asked for again every 15 seconds and other
  players' every minute, so a cape put on or taken off in the launcher shows
  without restarting. Before, each was asked once per session. An unchanged
  image is not uploaded to the GPU again.
- **Personal capes (a player's own upload) did not show to their wearer.**
  Most players with a cape wear one: `users.cape_url`, kept in Supabase
  Storage, with no catalogue row. `/selected` answers "personal" for them,
  and the mod then asked `/capefile/personal`, which is not a cape. Your
  own personal cape now comes from `/cape/:uuid`, the route that serves it.
- **Only capes from the account database are drawn.** When Supabase has no
  cape, the older routes fall back to a folder of test images on the API
  server (`mod_capes`), which are not players' capes. A `/selected` answer
  is now drawn only when it is a catalogue cape id or "personal"
  (`CapePolicy.legacySource`), the Wardrobe's cape list keeps catalogue ids
  only, and other players' capes are named by `/selected` before any image
  is fetched (a catalogue image is downloaded once for everyone wearing it).
  A failed catalogue image download is retried after a minute instead of
  never.

### API (needs a deploy, `breeze-api/`)
- `/cosmetics/state` looks players up undashed or dashed, reads the equipped
  cape from `user_capes.equipped` (as `/cape` and `/selected` do, and as the
  launcher writes it), and answers a failed database read with an error
  instead of "no account". `POST /cosmetics/tag/:uuid` had the same uuid
  mistake. `POST /select/:uuid` no longer names the missing column, which
  made its update fail: removing a cape in game never cleared `cape_url`,
  so the cape came back.
- `/cosmetics/state` serves a personal cape through `/cape/:uuid` on the
  API's own host (its stored link is on Supabase Storage, which the mod does
  not fetch from) and gives it an id of its own (`personal-<uuid>-<hash>`).
  All personal capes shared the id "personal", and the mod caches textures
  by id, so the first one loaded would have been drawn on everyone who has
  one.

### Verified
- `:common:test`: 130 passed, including `AssetUrlsTest` (8) and four new
  `CapePolicyTest` cases (the state without an account falls back to the
  older routes; only catalogue ids and "personal" are drawn from them).
- `scripts/check-java-offline.sh 1.20.1` on the changed files: 0 errors in
  Breeze's own code.
- In game, with the stand-in API (`stub-api.mjs`, `drive-world.sh`): the
  account's catalogue cape must be drawn while the state answers "no
  account", then the account switches to a personal cape and that must
  replace it without a restart (`cape-personal-check`).
- The HUD layout check compared where an element was drawn with where its
  placement puts it at its current size. A text element is placed with the
  size of the frame before (its width is known only once its lines are
  measured), so one whose width had just changed failed until the next
  frame: Item Despawn Timer on 1.21.6 in run 37095646221, when an item
  appeared. The check now uses the size the frame was placed with
  (`AbstractHudModule.placedW/placedH`). Drawing is unchanged.
- API: new `test/mod-cape-state.test.cjs` (8 tests) seeds rows the way
  production stores them; it fails 4 of 6 on the old route, and the personal
  cape test fails on the route before its personal-cape change. Full suite
  144 of 146; the 2 failures need Mojang's username lookup, which this
  container cannot reach, and fail on `main` too.
- Production data read (SELECT only) on 2026-10-03: 10 capes, all
  `http://api.breezeclient.net/assets/capes/...`; users and user_capes
  uuids all undashed; users has no `equipped_cape_id`; 15 players have a
  cape, 8 of them a personal one on Supabase Storage.
- **Run 37097045783 (`7b10ce7`, all 34 versions, launcher setup): all
  passed**, 38 jobs green. On every version the account's catalogue cape was
  drawn while the state answered "no account", then the personal cape
  replaced it (1 to 10 s), and the HUD layout check passed with 36 of 36
  elements. The 34 jars are in `Breeze Jars/` (SHA-256 checked against the
  jars branch; each declares only its own Minecraft version). Screenshots on
  `ci/evidence-37097045783`.
- **UNVERIFIED** until a real install with a signed-in account: a cape seen
  in game by its wearer and by a second player.

## [2.9.4] - 2026-10-02

Fix for a crash on start.

### Fixed
- **Minecraft crashed while starting for anyone who had moved a HUD
  element**, on every version, from 2.8.0 (2.9.3 is the first players got).
  Breeze applies the saved HUD layout (`config/breeze_hud.json`) when it
  starts, and placing an element read the game window. Fabric starts client mods inside Minecraft's
  constructor before the window is created (Fabric Loader's EntrypointPatch:
  before `Thread.currentThread()` from 1.19.4, before the "Backend library"
  log line earlier), so the read threw and the game closed with "Could not
  execute entrypoint stage 'client'" (`AbstractHudModule.resolvePosition`).
  The old Breeze mod wrote the same file and 2.9.x reads its format, so
  returning players had it. A new game folder has none, which is why the
  release tests passed. Placing now waits for the window; the element takes
  its saved place on the first frame.
- The real-install test now starts with a saved HUD layout, as returning
  players do.

### Verified
- Run 37039534225 (`76c5025`), real Fabric installs set up as launcher
  1.0.27 does (Breeze through `-Dfabric.addMods`), each starting with a saved
  layout: 1.21.11, 1.21.10, 1.21.9, 26.3, 1.21.4 and 1.20.1 passed. On
  1.21.11: Breeze initialised, the world loaded, FPS drew at its saved place,
  HUD layout 36/36, cosmetics 0 failures.
- The other 28 versions build from the same changed file and were not run:
  **UNVERIFIED** until a full run.
- The crash itself is from a player's log (1.21.11); the old jar was not
  re-run against the saved layout in CI.

## [2.9.3] - 2026-10-01 (not released)

Release polish: HUD panels, the title screen, and a frame rate reading.

### Changed
- **Text HUD elements sit on a panel by default**: a translucent dark
  rounded box (Background: Solid) instead of bare words on the world.
  Keystrokes, Mouse Strokes, Armor Status and Inventory HUD, which draw
  their own boxes, start without one. The setting is saved under a new key,
  so everyone gets the panel once; it can still be switched off per
  element.
- **The panel counts as part of the element.** Placing, dragging, the
  editor's handles, keeping elements on screen and finding a free spot all
  use the box with its padding, so panels never overlap each other, the
  hotbar or the screen edge.
- **Title screen**: the "BG: Breeze" / "BG: Vanilla" button is now
  "Backdrop" with an on/off switch (Breeze's scene on, Minecraft's panorama
  off), on every version.
- **Native Breeze menu, laid out for the sizes players have.** Minecraft's
  automatic GUI scale makes a 1280x720 window 426x240 units and 1920x1080
  480x270; the menu was laid out for more and showed it:
  - the "UI: Native only" button (which did nothing) sat on top of the
    search box. The menu choice moved to Breeze's settings (the gear) as
    "Menu: Auto / Classic / Web", only where this version has the embedded
    browser;
  - three columns of 64-unit cards cut names to "Coordin..." and drew the
    favourite heart over them: columns now keep cards at least 120 wide
    (two columns at 720p), and names stop before the heart;
  - the wardrobe, HUD editor and settings icons were stacked and left room
    for three of the eight category tabs: they sit in one row and every tab
    shows;
  - the search box shrinks to fit and the tab name and hint stop before it;
  - margins shrink on small screens instead of the content.
- **Breeze settings (native)**: at 720p the seven colour sliders ran into
  the presets row; their spacing now closes up to fit.
- **Friends**: the hint under the add box ran on into the chat panel (about
  250 pixels of text in a 168-pixel column); it is now "Right-click a
  friend to invite", clipped to the column.

### Test harness
- The in-world test reads Minecraft's frame rate once a second for ten
  seconds in the new world before switching anything on (`fps-sample`).
  Software rendered, so only comparable between runs on the same machines.
- `as_launcher`: Fabric Loader, Fabric API and MCEF exactly as launcher
  1.0.26 picks them, and Breeze loaded with `-Dfabric.addMods` as the
  launcher does. `perf_mods`: optimisation mods from Modrinth beside Breeze.
- The driver's whole-window screenshots (the web and native Breeze menus)
  go to the evidence branch with the game's own. The native driver also
  opens a module's settings (right-click) and Breeze's settings (the gear)
  and checks Escape comes back from each.

### Verified
- **All 34 versions passed, set up as launcher 1.0.26 sets up a game**:
  public build repository run 36858704296 (`6229f6a` = private `2c31ca9`),
  39 jobs, all green, 1.20.1 without MCEF included. Fabric Loader 0.19.5,
  Fabric API and MCEF as the launcher picks them from Modrinth (sha1
  checked), Breeze handed over with `-Dfabric.addMods`. The 34 jars are in
  `Breeze Jars/`.
- Screenshots on `evidence/36858704296`: text HUD elements on panels, none
  overlapping, none on the hotbar; the native menu at 1280x720 shows all
  eight tabs, whole card names and the search box clear of the header; the
  module settings and Breeze settings screens fit.
- The same 2.9.2 setup before these changes (run 36854828253): 33 of 34
  passed; 26.3 lost the first typed letter in the search box (driver
  timing, fixed above).
- **Beside Sodium, Lithium and FerriteCore** (public build repository runs
  36855923325 with them and 36855926255 without, 1.20.1, 1.21.4, 1.21.11
  and 26.3, launcher setup): every check passed with them on all four.
  Frame rate in the new world, software rendered (no GPU), one reading
  each: 1.20.1 3.8 to 9.0, 1.21.4 5.8 to 7.2, 1.21.11 8.1 to 4.5, 26.3 5.3
  to 4.1. Mixed and unreliable without a GPU: this shows they work together,
  not that they raise FPS. **UNVERIFIED** on real hardware.
- Interface: build, 19 Vitest, 14 module browser tests (fixture updated).

## [2.9.2] - 2026-10-01 (not released)

The web-menu jars start without MCEF.

### Fixed
- **On 1.20.1 to 1.20.4, 1.20.6 and 1.21 to 1.21.4 the game did not start
  when MCEF was not installed**: Fabric stopped at the client entrypoint
  with `NoClassDefFoundError: com/cinemamod/mcef/listeners/MCEFInitListener`
  (the owner's "mcef error" on 1.20.2 is very likely this). WebInit checked
  for MCEF before using it, but it held an MCEF listener in its own body,
  and the JVM resolved MCEF's types as soon as WebInit was loaded, before
  the check ran. Every MCEF call now lives in `McefBridge`, which is only
  touched after the check has found MCEF; the browser count moved to the
  MCEF-free `BrowserCount`. Without MCEF these versions use the native
  menus, as intended. Found by running the in-game test with MCEF left out
  (public build repository run 36846218672: 1.20.2 and 1.21.4 crashed at
  start); every earlier run had MCEF installed, so this path was never
  exercised.

### Test harness
- Every full run now also starts the first web-menu version (1.20.1) with
  MCEF left out; it must fall back to the native menus and pass every
  check. The public build workflow also has a `without_mcef` switch for
  runs by hand.

### Verified
- Without MCEF: 1.20.2 and 1.21.4 start, use the native menus and pass
  every check, 0 failed (run 36847264113 on the public build repository;
  the mods folder held only the Breeze jar and Fabric API).
- **All 34 versions passed with MCEF where it exists, and 1.20.1 without
  it**: run 36847277607 (`6537042` = private `1073a37`), 39 jobs, all
  green. These jars are in `Breeze Jars/` (SHA-256 checked).

## [2.9.1] - 2026-10-01 (not released)

Every HUD module can be moved; every module is tested in a world.

### Fixed
- **Zoom crashed the game on 1.21.2 to 1.21.11** the moment it was switched
  on: from 1.21.2 GameRenderer.getFov returns a float, and Breeze read it as
  a double (ClassCastException on the next frame). Found by the new module
  sweep on 1.21.4, 1.21.9 and 1.21.11 (mirror run 36807747143). 1.21.2 now
  has its own GameRendererMixin reading a float; up to 1.21.1 it stays a
  double, and 26.x already had its own. The 2.9.0 and earlier jars for
  1.21.2 to 1.21.11 have this crash.
- Keystrokes and Mouse Strokes draw their own boxes but reported no size,
  so the HUD editor's handle covered only a 24 by 10 corner of them and the
  background frame did not fit. They now report their real size (58 by 58,
  46 by 46).
- Recording Indicator was always drawn in the top-right corner, whatever its
  position said, so it could not be moved. It is now drawn at its position
  (top right until moved).
- Many HUD elements share a default corner (the in-world test showed ten at
  the bottom left with every element on), so switching on several piled
  them on top of each other. The first time an element that was never moved
  is drawn, if its default place is taken by another element on screen, it
  goes to the nearest free place (common HudPlacement.freeSpot, 3 unit
  tests) and that place is saved. Elements the player has placed are never
  moved. Minecraft's hotbar and the health, food, armour and experience
  rows above it count as taken: in the all-elements screenshot (run
  36809332861, 1.21.11) Potion Effects had been moved onto the hotbar,
  because several left-side defaults lie below the bottom of an 854 by 480
  window and the search went right along the bottom edge.
- 3D cosmetics stored as a self-contained .gltf were never drawn: the model
  reader took only GLB and refused them ("not a GLB: wrong magic"). Some
  cosmetics in the live catalogue are .gltf files with their buffers and
  textures inside as data: URIs (Blockbench's export; docs/COSMETICS.md),
  for example the pet "Glare 23", which the in-world test fetched and could
  not read (mirror run 36809332861). GlbReader now reads those too; a .gltf
  that points at files outside itself is refused with the reason, since
  those files were never uploaded. 3 unit tests: the same model as GLB and
  as .gltf reads the same (rest pose and animation), data: URI textures,
  and the refusals.

### Test harness
- In the world, all 36 HUD modules are switched on and each must draw
  without an error and none may cover the hotbar; then each is moved to its own place on the screen, the
  layout is saved, forgotten, read back from disk, and each must be drawn
  where it was put (screenshot autotest-hud-layout).
- Every one of the 81 modules is switched on in the world, every setting
  moved through its range (a switch flipped, a number to its maximum and
  minimum, a colour changed, a choice through two others), then put back and
  switched off; none may throw (ModuleManager.ERRORS counts what modules
  throw in tick, HUD and world drawing, and switching). One number per
  module is then saved and read back.
- The HUD editor step drags three kinds of element with the real mouse:
  FPS (text), Keystrokes (boxes) and the Inventory HUD (right edge, after H
  hides the editor's panel), and Done must save all three.
- Real creator cosmetics: the test fetches up to four 3D cosmetics from
  the public catalogue (GET /cosmetics, read only), only models on the API
  host, since the game loads nothing else; the stand-in API lets the test
  player own them; the self-test wears them one per slot at a time, in
  rounds (a second pet after the first), with the camera on the player's
  back for each round and on the front at the end; each must be drawn
  (screenshots autotest-real-cosmetics, -2, -3 and -front). When none can
  be fetched the step is reported as skipped, not passed.

- The test world is switched to Peaceful as soon as the test player is in
  it. Worlds come from a random seed, and in run 36834405326 1.21.10's
  player spawned in a dark forest and was killed by mobs during the module
  sweep, which then failed the Wardrobe, real cosmetics and pause menu
  checks (a dead player is not drawn). If the test player ever dies, the
  driver now says so as its own failure.
- The web menu test waits for the page to report its first route (React
  mounted) before pressing keys. In run 36837259634 the page on 1.20.4 and
  1.21.2 mounted 2.5 to 3 s after the game said it was ready for input; a
  Tab sent in that gap was lost and Enter opened Multiplayer instead of
  Mods, failing the three web menu checks after it. Not a mod fault: keys
  pressed before a page loads go nowhere.

### Verified
- The catalogue's .gltf pet "Glare 23" read and drawn on the player in a
  real Fabric install on 1.17, 1.20.1, 1.21.4, 1.21.9, 1.21.11, 26.1 and
  26.3 (mirror run 36810389512, source `58f8a9a`; 1.21.11 drew it 83 times,
  1.17 118 times, 0 failures). Screenshots: a green leafy pet beside the
  player's head on 1.17, 1.21.11 and 26.3. Same run: every module and
  setting swept with no errors, 36 of 36 HUD elements moved and saved.
- **All 34 versions passed** in publishing run 36811362936 (mirror
  `a33be63` = private `1241ab5`): menus, world, all 36 HUD elements drawn
  and none on the hotbar, all moved and saved, all 81 modules and their
  settings, settings saved and read back, the HUD editor drags, the
  Wardrobe equip, and the catalogue's three API-hosted pets ("Glare 23",
  "Glare" as .glb and as .gltf) worn in turn and each drawn (screenshots
  per round on every version). 1.21.3's job hung in the runner's apt
  install before any test in the first attempt and passed on its one
  re-run.
- **All 34 versions passed again from the latest test harness** (Peaceful
  test world, web menu waits for the page): run 36839566673 on the public
  build repository (`f06c60d` = private `3378525`), every check on every
  version, no re-runs. These are the jars now in `Breeze Jars/` (SHA-256
  of each checked against the run). The mod itself is unchanged since the
  previous 2.9.1 jars; only the test harness moved on.

## [2.9.0] - 2026-10-01 (not released)

3D cosmetics are equipped from inside the game.

### Added
- The Wardrobe equips and removes 3D cosmetics (owner decision, 2026-10-01).
  It lists every 3D cosmetic the account owns, one per slot, with Equip and
  Remove (web page: the "3D cosmetics" section; native: the "3D" tab, click
  a row). The change is drawn on the player at once and other players see
  it on their next look-up. Calls GET /cosmetics/owned/:uuid and
  POST /cosmetics/equip/:uuid and /cosmetics/unequip/:uuid with the game
  token (docs/COSMETICS.md); bridge actions cosmetics.equipModel and
  cosmetics.unequipModel; common/OwnedCosmetics reads the answer (4 unit
  tests).
- With an API that does not have those routes yet (they are on branch
  `api/mod-cosmetics-equip`, PR #25, not deployed), the Wardrobe shows what
  the account wears without controls, as in 2.8.0.

### Test harness
- The in-game test runs against a stand-in API (scripts/ci/stub-api.mjs):
  the cosmetics routes as documented, in memory, with one test model, and a
  game token for a test player. After the HUD editor step the self-test
  opens the Wardrobe on its 3D tab, the driver clicks the test hat with the
  real mouse, and the check passes only when the API recorded it, the
  Wardrobe shows it equipped, the game fetched it from the API and drew it on
  the player. The stand-in holds no API code, data or secrets.

### Verified
- API routes: 8 tests on the API branch (see PR #25), run here.
- Web Wardrobe: 76 Playwright tests (equip, remove, one per slot, the
  read-only fallback), Vitest, tsc.
- In-game, against the stand-in API (wip run 36801550351, 8 versions: 1.17,
  1.19.3, 1.20.1, 1.21.4, 1.21.9, 1.21.11, 26.1, 26.3): every check passed;
  the Wardrobe listed the test player's cosmetics, a real mouse click on the
  test hat equipped it through the API, the game fetched the model from the
  API and drew it (screenshots: mirror branch evidence/36801550351, the
  player preview wears the hat). The API routes themselves passed in this
  repository's CI on PR #25 (run 36798948347).
- Publishing run 36802149941 (source `10cf6f0`): all 34 versions, 1.17 to
  26.3, passed every check, the Wardrobe equip step included. All 34 2.9.0
  jars are in Breeze Jars with SHA-256.
- **UNVERIFIED** against the real API until PR #25 is deployed, and with a
  real account (the owner's check).

## [2.8.0] - 2026-09-30 (not released)

The HUD editor to the spec, 3D cosmetics drawn on players, and an in-world
test on every version.

### Added
- HUD editor (every version): click to select, drag with snapping to the
  screen's edges and centre and to other elements (guide lines, Snap on or
  off), scroll or +/- to scale, arrow keys to nudge, Delete to reset one,
  Reset all, Arrange (stacks visible elements with no overlap), a side list
  that switches HUD elements on and off, Settings for the selected element,
  Done (also Escape and Enter) and Cancel, which puts everything back.
  Overlapping elements get an amber frame. Every control sits in a side
  panel clear of the HUD; Hide (or H) folds it to one small button.
- HUD positions are anchored to the screen (common/HudPlacement, unit
  tested): an element against the right edge stays there when the window
  or GUI scale changes, and every element is kept wholly on screen.
  Saved as {"at": ..} in config/breeze_hud.json; old {x, y} files load as
  top-left offsets.
- 3D cosmetics drawn on players, every version. What a player wears comes
  from GET /cosmetics/equipped/:uuid; each GLB is downloaded once, only
  from the Breeze API's own host over https, read off the render thread,
  posed at the moment's animation time and placed as the launcher's
  Wardrobe places it (head, shoulder, back, hand, feet, side, flying pet,
  trail). Flying pets circle and bob, trails leave fading copies, side pets
  walk while the player walks (common/GlbReader, Pose, CosmeticRig,
  CosmeticMotion; unit tested with GLBs built in code).
- The Wardrobe lists the 3D cosmetics your account wears (web page: a "3D
  cosmetics" section; native: a "3D" tab), with where to change them. They
  are listed, not changed: the API lets only a signed-in account equip
  them, and that sign-in stays in the launcher (the game holds a game token
  only). Equipping in game would need a game-token route on the API (owner
  decision). 2 Playwright tests (74 pass).

### Fixed
- 26.3: entering a world crashed the game. 26.3 reads keys through SDL,
  where "no key" is 0; Breeze's unbound keys were -1, and Minecraft asked
  the keyboard state for index -1 when it grabbed the mouse. Unbound keys
  are now registered as Minecraft's own unknown key.
- Friends: Backspace and Enter in the add-friend and message fields used
  GLFW's numbers, which 26.3 no longer uses; they now use Minecraft's
  constants for each version.
- Inventory HUD draws at its own position (default top right) and reports
  its size, so the HUD editor can move it.
- Title screen, 1.20.2 and later: Minecraft's icon buttons (language,
  accessibility, and on 26.3 friends) were drawn by Breeze's button style
  as their screen-reader labels, which spilled over Options and Quit Game
  (seen in the run's screenshots on 1.21.11 and 26.3). A button whose label
  does not fit keeps Minecraft's own look.

### Test harness
- In-world test on every version (scripts/ci/drive-world.sh, AutoTest):
  create a singleplayer world with Minecraft's own button; switch on FPS,
  Coordinates, CPS, Keystrokes, Direction and Inventory HUD and check each
  drew; put a test cape image in breeze_capes, switch on Custom Cape, look
  from behind and check Breeze's cape is the one Minecraft draws; wear a
  GLB hat built in code and check it was drawn; drag FPS in the HUD editor
  with the real mouse, press Done and check the move was saved; save and
  quit to the title screen.

### Verified
- In-world test passed (world, six HUD elements drawn, cape, HUD editor
  drag saved): 1.17, 1.19.4, 1.20.1, 1.21.4, 1.21.8, 1.21.11, 26.1 (run
  36755606757). With the test hat as well (cosmetic drawn, 17 draws on
  1.21.9): 1.17, 1.19.2, 1.20.1, 1.20.6, 1.21, 1.21.4, 1.21.9, 1.21.11,
  26.1 (run 36756371829).
- Full in-world run of all 34 versions, 1.17 to 26.3 (mirror run
  36793720749, source `b1e2806`): 33 passed every check, 26.3 included (the
  key fix works: world entered, HUD, cape, hat, HUD editor drag saved).
  1.19.3 passed every Breeze check but the test could not leave the world
  (its pause menu's buttons sit in a layout widget the self-test did not
  look inside; harness fixed).
- Screenshots from that run (mirror branch evidence/36793720749), looked at
  for 1.17, 1.20.1, 1.21.4, 1.21.9, 1.21.11, 26.1 and 26.3: the test hat
  sits on the head at about head size, the test cape is on the back, and
  the HUD is drawn, the same in every rendering era.
- Publishing run 36794798144 (source `f67df65`): all 34 versions, 1.17 to
  26.3, passed every check, menus and world, with the test wearing a hat, a
  flying pet and a trail (on 26.3: each drawn 35 times, 0 failures). All 34
  jars are in Breeze Jars with SHA-256. Its screenshots (mirror branch
  evidence/36794798144) show the flying pet beside the player and a trail
  copy behind the legs, and the title screen's icon buttons as icons on
  1.21.11 and 26.3.
- **UNVERIFIED**: real Breeze cosmetics and capes from the API, and another
  player seeing them (needs signed-in accounts); the Wardrobe's 3D list
  with real data (compiles and the web page is tested with preview data).

## [2.7.0] - 2026-09-30 (not released)

Minecraft 26.1 to 26.3 and 1.17 to 1.19.4.

### Added
- Jars for 26.1, 26.1.1, 26.1.2, 26.2 and 26.3 (native menus; MCEF has no
  build for them). 26.x draws by "extracting" into render states: Breeze
  screens keep their render() and BreezeScreen bridges it; the HUD is a Fabric
  HUD element; world drawing uses the level render events (submitted to the
  frame's node collector from 26.2). Time Changer holds the world clocks
  (26.1 moved day time to clocks).
- Version projects for 1.17, 1.17.1, 1.18, 1.18.1, 1.18.2, 1.19, 1.19.1,
  1.19.2, 1.19.3 and 1.19.4. Minecraft before 1.20 has no GuiGraphics, so
  Breeze brings its own with the calls it makes, over a PoseStack; each older
  family holds only what differs from the next newer one.

### Changed
- Small adapters so versions can differ in one place: compat/Lan (Hosting's
  LAN publish), Links (opening a web link), Widgets (buttons, field hints,
  widget positions), Chat (sending a chat line), Perf (frame rate), Toggles
  (view bobbing, subtitles), Profiles.self, and ActiveScreen.hudHidden (Auto
  Hide HUD). The calls are the same as before on 1.20 to 1.21.11.
- common compiles against the Java 16 API, and reads JSON through
  dev.breeze.Json, which uses only Gson 2.8.0 calls (Minecraft 1.17 ships
  Gson 2.8.0). 83 unit tests pass.
- fabric.mod.json names Fabric API by the mod id of that version's build:
  "fabric" on 1.17 to 1.19.1 (except 1.18.2), "fabric-api" elsewhere. Before,
  the loader refused to start Breeze on those versions with Fabric API
  installed. 1.17 builds against Fabric API 0.36.0 and 1.18 against 0.44.0,
  the newest builds that accept those exact versions.

### Fixed
- 1.17 to 1.19.3: gradients (GuiComponent's seven-argument fillGradient is an
  instance method before 1.19.4; the static one with z = 0 is used).
- 26.3: mouse buttons. Minecraft now passes SDL's button numbers (left 1,
  middle 2, right 3); compat/Buttons maps them to the numbers Breeze's
  screens and click counters use.

### Test harness
- Fabric API comes from Modrinth, as players get it, and must bundle its
  modules (Fabric's Maven served a 4877-byte stub of 0.77.0+1.18.2).
- 26.3 opens its window through SDL 3 and asks for an sRGB OpenGL
  framebuffer, which Xvfb's GLX cannot give; the test sets
  SDL_VIDEO_FORCE_EGL=1 (test only; the same software renderer then gives GL
  4.5 with an sRGB back buffer).
- The drivers find the game window from xwininfo's tree (on 26.3 in CI,
  xdotool search listed no windows at all).
- SDL_VIDEO_X11_XINPUT2=0 on 26.3 so each test click arrives once.

### Verified
- Mirror run 36705618640 (source `059b3a4`, numbered 2.6.0): 1.20 to 1.21.11,
  26.1, 26.1.1, 26.1.2 and 26.2 each passed every check in a real Fabric
  install, including the base changes above.
- Real-install test passed at 2.7.0: 1.19.2, 1.19.3, 1.19.4 (run
  36739211954), 1.18.2 (run 36741374246), 1.17, 1.17.1, 1.18, 1.18.1, 1.19,
  1.19.1 (run 36742564206).
- Full run 36753770065 (source `0e9f25e`): all 34 versions from 1.17 to
  26.3 passed the real-install menu test. 33 of those jars are in Breeze
  Jars; 26.3's is not, because entering a world crashes it (fixed in 2.8.0).
- **UNVERIFIED**: in-world behaviour (the 2.7.0 test does not enter a
  world), including the 26.x world drawing, Zoom on 26.x (Camera.getFov)
  and Item Scale on 26.3.

## [2.6.0] - 2026-09-30 (not released)

Minecraft 1.20.2 to 1.21.11 get their own jars.

### Added
- Jars for 1.20.2, 1.20.3, 1.20.4, 1.20.5, 1.20.6, 1.21, 1.21.1, 1.21.2,
  1.21.3, 1.21.4, 1.21.5, 1.21.6, 1.21.7, 1.21.8, 1.21.9, 1.21.10 and
  1.21.11. The web menu where MCEF has a build (1.20.2 to 1.20.4, 1.20.6,
  1.21 to 1.21.4), the native menus everywhere else.
- A thin adapter layer (`dev.breeze.compat`, `dev.breeze.render`) so the
  modules and screens keep one shape while Minecraft's screen input, drawing,
  HUD and world render hooks, networking, item data, key mappings and
  identifiers change between versions. Each version folder holds only the
  adapters and mixins that differ from the one before.
- `renames.txt` in a version folder renames classes (and, with a leading dot,
  methods) in the merged source, for moves such as `ResourceLocation` to
  `Identifier` in 1.21.11.

### Fixed
- 1.20.5 and later crashed at startup: the chat mixin targeted a private
  `addMessage` whose parameters changed. It now targets the public
  three-argument form every version has.
- 1.21.6 and later crashed at startup: `SoundEngine.play` returns a result
  from 1.21.6, so the Sound Filter mixin must use `CallbackInfoReturnable`.
  A muted sound now returns `NOT_STARTED` there.
- Renderer mixins follow render states (1.21.2), the fire overlay (1.21.4),
  the held item (1.21.5), and submit calls, input events and title clicks
  (1.21.9).

### Verified
- Mirror run 36702996724 (source `ab5fdc2`): each of the 19 jars, 1.20 to
  1.21.11, built from its source ZIP and launched in a real Fabric install
  with 0 failed checks, including that every Breeze mixin applies. Web menu
  on 1.20.1 to 1.20.4, 1.20.6 and 1.21 to 1.21.4; native menus on the rest.
  The jars are in `Breeze Jars/` with their SHA-256.
- The test does not enter a world, so module behaviour in a world (HUD,
  world drawing, capes, zoom, sound filter) is **UNVERIFIED** on every
  version. World drawing modules are switched off on 1.21.9, where Fabric
  API has no world render event; line width on 1.21.11 is **UNVERIFIED**.

## [2.5.0] - 2026-09-30 (not released)

One build for every Minecraft version, and the first new version: 1.20.

### Added
- Minecraft 1.20 gets its own jar, `1.20.jar`, built and tested against 1.20
  itself. MCEF has no build for 1.20, so the native menus are the interface.
- The native menus are tested in a real Fabric install too:
  `scripts/ci/drive-native.sh` opens the Breeze menu from its title screen
  button, types in the search field, switches a module on and off, opens
  Singleplayer and quits, clicking controls by name. The self-test logs where
  each control is (`Targets` for the ones Breeze draws itself) and every
  module that changes state.

### Changed
- Build layout for many versions: every folder in `versions/` with a
  `gradle.properties` is a version project, configured by the shared
  `gradle/version.gradle`. A version's source is a chain of folders
  (`source_chain`): `versions/1.20.1/src/main` is the base and later versions
  hold only the files that differ; `versions/shared/no-browser` stands in for
  the four classes that talk to MCEF on versions MCEF does not cover. The
  source ZIP ships the merged folder.
- Loom 1.18.2 and Gradle 9.7.1 (what Fabric's example mod uses from 1.20.6
  to 26.3), with `net.fabricmc.fabric-loom-remap` up to 1.21.11 and
  `net.fabricmc.fabric-loom` for 26.x. MCEF comes from Modrinth's Maven
  instead of a copy in the repository.
- Each jar declares only its own Minecraft version (`1.20.1.jar` said
  `>=1.20 <1.20.2`; 1.20 now has its own jar) and the Java level it needs.
- `-Pbreeze_versions=...` limits a build to the named versions, since Loom
  sets up Minecraft for every version it configures.

### Verified
- Mirror run 36667483385: `1.20.jar` (native menus) and `1.20.1.jar` (web
  menu) built from their source ZIPs and passed every check in a real Fabric
  install.

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
- The released jar is now tested in a real Fabric install as well as the
  development client: `scripts/ci/run-minecraft-test.sh` with
  `BREEZE_LAUNCH=prod` installs vanilla Minecraft and Fabric Loader with
  portablemc and puts the jar, Fabric API and MCEF in `mods/`. The self-test
  reports screens by kind, because a released jar runs with intermediary
  class names.
- `scripts/ci/version-facts.sh` reads each Minecraft release's Java level,
  Fabric and Fabric API support and MCEF builds from Mojang, Fabric and
  Modrinth.

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
- The released `1.20.1.jar` (SHA-256 `2474f4e9...a7c714`) in a real Fabric
  install, run 36665561143: Fabric loaded it with MCEF, MCEF downloaded
  Chromium on first start, and the same checks passed with 0 failures (Create
  World showed up as `class_525`, confirming intermediary names). The jar is
  in `Breeze Jars/`.

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
