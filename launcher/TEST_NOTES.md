# Breeze Client 1.1.0_test_4

Fourth testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_4** (Config, About); the splash reads
`v1.1.0-test.4`. Everything in 1.1.0_test_3 is in it too, and its notes
still apply (`docs/test-builds/1.1.0_test_3.md`).

Installs over 1.0.38 or any earlier 1.1.0 test build and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open.

This build answers the owner's test of 1.1.0_test_3.

## What to test

1. **Moments on their own.** Leave the launcher on Play or the Wardrobe and
   keep using the mouse: the player now does something every 8 to 16
   seconds on Play and every 5 to 9 seconds in the Wardrobe, without
   pressing anything. Moving the mouse no longer holds them back.
2. **New moments.** Spin, Backflip, Dance and Cheer join the five from
   before. They come at random, and each is also under Try a moment in the
   Wardrobe.
3. **Turning the player during a moment.** Press Check the cape, then drag
   the player round to watch it from behind: the moment keeps playing. When
   you let go the player stays where you turned it for a few seconds, then
   eases back.
4. **Taking off a 3D cosmetic.** Wardrobe, 3D cosmetics: each worn cosmetic
   now has Take off next to Adjust. It waits for the server, then the list
   shows what the server says.

## What changed since 1.1.0_test_3

- Moments come on their own and more often; turning the player no longer
  stops one; four new moments.
- Take off for worn 3D cosmetics in the Wardrobe.

## Known limits

- Character moments can still be switched off in Config, and the system's
  reduce motion setting still turns them all off.
- Hosting has no page yet (to be decided later, owner 2026-10-30).
- The same as before: the API changes are not live until `server.js` and
  `src/` are uploaded; the in-game changes (mod 2.18.0) are not served by
  the API yet; everything after sign-in is UNVERIFIED in the real app
  windows on the build server (it has no Microsoft account).
- Checked automatically: launcher unit tests 62/62, UI tests 66/66 in a
  browser (two new: turning lets a moment play on, moments start while the
  pointer moves), Rust tests 143 here; the build server installs and starts
  each installer. Frames of the new moments were looked at in a browser.

## Reporting

Say "1.1.0_test_4" with what you did, what you expected and what happened.
