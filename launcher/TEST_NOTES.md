# Breeze Client 1.2.0_test_1

First testing build of the 1.2.0 work. Not a release. Inside the app it
shows as **Test build 1.2.0_test_1** (Config, About); the splash reads
`v1.2.0-test.1`. It is 1.1.2 with mod loaders, CurseForge and the edition
menu added.

Installs over 1.1.2 or any earlier build and keeps accounts, settings and
mods. Unsigned: on Windows, More info, Run anyway; on macOS, right click,
Open.

## What to test

1. **Loaders on Play.** Next to the version there is a new choice: Fabric,
   Quilt, Forge, NeoForge, Vanilla. Each says the build it would use. Pick
   **Forge** on **1.20.1** and press Launch. The first start installs Forge
   (about half a minute, with steps shown), then the game opens with
   Forge's loading screen. Close it and launch again: no install this time.
2. **NeoForge on 1.21.1**, the same way.
3. **Quilt on 1.21.1** and **Vanilla** on any version: both should start.
4. **Fabric** still works as before, with Breeze's menu and HUD in game.
   On the other loaders Breeze's in-game features are not there (the Play
   card says so); that is expected for now.
5. **Mods per loader.** On Mods, switch the loader. Forge's mods list is
   empty at first and separate from Fabric's. Install a mod from Browse
   (for example JEI) on Forge 1.20.1, launch, and check it is in the
   game's Mods screen. Back on Fabric, your Fabric mods are as they were.
6. **A loader with no build.** Pick NeoForge, then a version NeoForge does
   not have (for example 1.21.11): the choice goes back to Fabric and says
   why.
7. **Edition menu.** The title bar says Java Edition; its menu shows
   Bedrock Edition as planned.
8. **CurseForge** shows as Planned on Mods, Browse. It starts working once
   the CurseForge key is on the server.

## What changed since 1.1.2

- Choose the mod loader per version: Fabric, Quilt, Forge, NeoForge or the
  game alone. Each has its own folder, so their mods never mix.
- Mods, Browse searches Modrinth (and CurseForge, once set up) for the
  loader chosen; Quilt also gets Fabric's mods.
- Forge, NeoForge and Quilt mods are recognised on the Mods page, and a
  new version of one replaces the old copy (the old one goes to backups).
- The title bar's edition menu is back.

## Known limits

- Forge, NeoForge, Quilt and Vanilla were installed and started on the
  build server (Linux), and Forge and NeoForge installed on Windows there.
  A start from this app with your account, and the game window on
  Windows, are **UNVERIFIED** until you try it.
- Breeze's own mod runs on Fabric only. A Forge and NeoForge version of it
  is the next part of 1.2.0.
- Modpacks, imports from other launchers and the performance set stay
  Fabric's; their buttons are hidden for the other loaders.
- CurseForge needs its key on the server first.
