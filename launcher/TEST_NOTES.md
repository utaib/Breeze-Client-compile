# Breeze Client 1.1.0_test_5

Fifth testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_5** (Config, About); the splash reads
`v1.1.0-test.5`. Everything in 1.1.0_test_4 is in it too, and its notes
still apply (`docs/test-builds/1.1.0_test_4.md`).

Installs over 1.0.38 or any earlier 1.1.0 test build and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open.

This build is the modpack import review (1.1.0 directive, ninth item).

## What to test

1. **Importing a Modrinth pack.** Play page, Mods widget, Import modpack (or
   Config, Modrinth Pack), and pick a `.mrpack` made for Fabric. Before
   anything changes, a box says which Minecraft version it is for, how many
   mods it downloads and how large they are, how many of its own files it
   unpacks, which files already in that version it replaces, and what it
   leaves out. Import, or Cancel to change nothing.
2. **A pack for another version.** With 1.21.4 selected, import a pack made
   for 1.20.1: the box says it goes into your 1.20.1 version, and the mods
   land there, not in 1.21.4. Select 1.20.1 to see them.
3. **Replaced files are kept.** Import a pack into a version that already
   has mods or settings: the files it replaces are moved to
   `.breeze-import-backup/<time>/` inside that version's folder (Mods, Open
   folder), so nothing you had is lost.
4. **A pack that cannot be used.** A Forge or NeoForge pack says it is made
   for Forge and that Breeze runs Fabric, and imports nothing.
5. **Dropping a pack on the window** shows the same box first.

## What changed since 1.1.0_test_4

- Every download in a pack is checked against the pack's own hash and size;
  a file that does not match stops the import.
- A pack can no longer unpack more than it should (a file over 512 MB, or
  more than 2 GB in all, is refused before anything is written).
- Nothing changes until every file is downloaded and checked; if putting a
  file in place fails, everything already put in place is undone.
- Files already in the version are kept in a backup folder instead of being
  overwritten.
- A pack goes into its own Minecraft version; Forge, NeoForge and Quilt
  packs are refused with the reason.

## Known limits

- Breeze imports Fabric packs only (Forge support is a later item).
- Optional files of a pack are imported when they can be fetched and left
  out, with a note, when they cannot.
- The same as before: the API changes are not live until `server.js` and
  `src/` are uploaded; the in-game changes (mod 2.23.0, the custom shield)
  are not served by the API yet; everything after sign-in is UNVERIFIED in
  the real app windows on the build server (it has no Microsoft account).
- Checked automatically: Rust tests 149 here (6 new: Forge refused,
  required mods that cannot be fetched stop the import, paths and a huge
  index refused, hashes and sizes, the version a pack goes into, replaced
  files kept and a failed move undone), unit tests 66/66 (4 new, the box's
  words), UI tests 68/68 in a browser (2 new: the box, then the import into
  the pack's version; Cancel imports nothing and a Forge pack says why).
  A real import from Modrinth in the app: UNVERIFIED (the build server has
  no account and this container cannot reach Modrinth).
