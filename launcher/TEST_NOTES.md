# Breeze Client 1.1.0_test_8

Eighth testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_8** (Config, About); the splash reads
`v1.1.0-test.8`. Everything in 1.1.0_test_7 is in it too (Hosting, the new
Store and the rest), and its notes still apply
(`docs/test-builds/1.1.0_test_7.md`).

Installs over 1.0.38 or any earlier 1.1.0 test build and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open.

This build adds two character moments that hold items from the game.

## What to test

1. **Look over a sword.** Style (Wardrobe), Try a moment, Look over a
   sword: your player draws a sword, turns it over in the hand to show the
   other side, raises it and swings.
2. **Ender pearl.** Try a moment, Ender pearl: your player holds a pearl,
   throws it, purple ender particles rise where it lands, and your player
   is there, then walks back to the middle.
3. **The real items.** If you have played a version in Breeze (or have the
   vanilla Minecraft launcher), the sword and the pearl are Minecraft's own
   pictures, read from that game on your computer. Without one, Breeze's
   own small drawings of a sword and a pearl stand in.
4. **On their own.** Both moments also come by themselves now and then on
   the Play page and in the Wardrobe, like the others. Config, Character
   moments turns them all off; the system's reduced motion setting does too.

## What changed since 1.1.0_test_7

- The two item moments (sword, ender pearl teleport) and the item in the
  hand, built from the item's picture as little cubes, the way Minecraft
  draws a held item.

## Known limits

- The cat, climbing and the parkour across the Play page's widgets are
  still to come; the motion-capture clips wait on the Mixamo files.
- The same as before: everything after sign-in is UNVERIFIED in the real
  app window on the build server (it has no Microsoft account), so the
  moments were checked in a browser here (unit 75/75, UI 84/84).

## Build results

To be filled in from the build run.

## Reporting

Say "1.1.0_test_8" with what you did, what you expected and what happened.
