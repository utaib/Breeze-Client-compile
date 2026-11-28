# Breeze Client 1.3.0_test_1

First testing build of 1.3.0: **Bedrock Edition on Windows**. Not a
release. Inside the app it shows as **1.3.0-test.1**, and under Settings,
About it says "Test build, uses the test mods". Java Edition is unchanged
from 1.2.3 (Breeze mod 2.30.0 from `versions/test-mods/`).

**Built** by the public build repository's launcher workflow with channel
test; the run number, file size and SHA-256 are added below when the run
is done.

Installs over 1.2.3 or any earlier build and keeps accounts, settings and
mods. Unsigned: on Windows, More info, Run anyway.

## Before you test

- **The Breeze add-on on the API.** "Install the add-on" downloads it from
  `GET /bedrock/addon`, which serves `versions/bedrock/` on the server. A
  session fills it with `breeze-api/deploy-to-host.sh <checkout> --bedrock
  1.0.0` (with the API code of this branch, which has the route), then a
  restart. Until then the Home tab says the add-on is not published yet;
  everything else works.
- **Java test mods** stay as they are (`versions/test-mods/`, 2.30.0).

## What to test (Windows, with Minecraft for Windows installed)

1. **The edition switch.** Title bar, Java Edition, choose **Bedrock
   Edition**. It is no longer marked Planned.
2. **Home.** It should name your install ("Minecraft Bedrock" for the
   current Windows build, "Minecraft Bedrock Preview" if you have Preview,
   or the older Microsoft Store build). Press **Play Bedrock**: the game
   should open the way it does from the Start menu. Tell us if nothing
   happens or a different app opens.
3. **The Breeze add-on.** Press **Install the add-on** (after the API has
   it). Then in Minecraft: Settings, Global Resources: Breeze UI should be
   on. Its gear chooses a crosshair and low fire. Breeze HUD: make a world
   (or edit one), Behavior Packs, activate Breeze HUD. In the world, look
   straight up and crouch for a second: the Breeze menu opens. The action
   bar shows coordinates, facing, time and day, low durability and effects.
4. **Add-ons.** Import a `.mcpack` or `.mcaddon` you trust (Import a file).
   It appears in the list. Turn a resource pack on for **every world** and
   check Global Resources in the game. Back up and Remove (press twice).
5. **Worlds.** Your worlds should be listed with their names. Back one up,
   then Restore it from Backups: it comes back as a second copy, never over
   the original.

## Known limits

- **UNVERIFIED on a real PC:** where the current Windows build keeps worlds
  and the global resource packs file (Breeze looks in the shared folder and
  in each user folder under `%APPDATA%\Minecraft Bedrock\Users\`), and that
  `minecraft:` opens the current build. These are what this test is for.
- **UNVERIFIED in game:** the add-on itself; it has only passed file
  checks so far.
- macOS and Linux show Bedrock as not available there, by design.
- Backups are kept in `%USERPROFILE%\.breezeclient\bedrock-backups\`.
