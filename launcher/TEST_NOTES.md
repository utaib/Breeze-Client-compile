# Breeze Client 1.1.0_test_9

Ninth testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_9** (Config, About); the splash reads
`v1.1.0-test.9`. Everything in 1.1.0_test_8 is in it too (the sword and
ender pearl, Hosting, the new Store and the rest), and its notes still
apply (`docs/test-builds/1.1.0_test_8.md`).

Installs over 1.0.38 or any earlier 1.1.0 test build and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open.

This build adds three character moments (a cat, parkour, building) and
makes hosted servers safer on Windows.

## What to test

1. **Pet the cat.** Style (Wardrobe), Try a moment, Pet the cat: a ginger
   cat walks in, sits by your player, is petted with hearts rising, then
   walks off.
2. **Parkour.** Try a moment, Parkour: two blocks appear beside your
   player, the camera pulls back, and your player jumps up onto one, runs
   across, jumps the gap to the other, jumps down, cheers, and walks back
   when the blocks are gone. The camera then comes back to where it was.
3. **Build.** Try a moment, Build: your player places two planks, one on
   the other, and a stone brick block, looks them over, then breaks them
   one by one, bits flying.
4. **The real blocks.** With a Minecraft version installed (played in
   Breeze, or in the vanilla launcher), the planks and stone bricks are
   Minecraft's own pictures from your computer; without one, Breeze's own
   drawings stand in.
5. **Your name follows you.** During the ender pearl, parkour and the
   cat, the name above your player now goes where your player goes.
6. **On the Play page too.** All of them also come by themselves now and
   then on the Play page and in the Wardrobe. Config, Character moments
   turns them off; the system's reduced motion setting does too.
7. **Hosting on Windows.** Start a server in Hosting, then end Breeze
   from Task Manager (End task, not the close button). The server's `java`
   should be gone from Task Manager within a few seconds. Closing Breeze
   normally still saves the world and stops the server, as before.

## What changed since 1.1.0_test_8

- Pet the cat, Parkour and Build, with the camera pulling back for the
  course and the blocks read from the installed game.
- The name tag follows the player when a moment moves them.
- On Windows a hosted server is tied to Breeze with a job object, so it
  ends with Breeze even when Breeze is ended by force or crashes.

## Known limits

- The parkour runs on blocks inside the player's stage, not across the
  Play page's other panels: the player would cover Launch and News.
- The motion-capture clips wait on the Mixamo files.
- The same as before: everything after sign-in is UNVERIFIED in the real
  app window on the build server (it has no Microsoft account), so the
  moments were checked in a browser here. The job object is compiled for
  Windows here and runs for the first time in this build (UNVERIFIED until
  the Task Manager test above).

## Build results

Filled in after the build run.

## Reporting

Say "1.1.0_test_9" with what you did, what you expected and what happened.
