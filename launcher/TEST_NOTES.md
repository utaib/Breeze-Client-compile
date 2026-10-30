# Breeze Client 1.1.0_test_2

Second testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_2** (Config, About); the splash reads
`v1.1.0-test.2`. Everything in 1.1.0_test_1 is in it too; its notes still
apply (`docs/test-builds/1.1.0_test_1.md`), and 1.1.0_test_1 stays
available to compare.

Installs over 1.0.38 or 1.1.0_test_1 and keeps accounts and settings.
Unsigned: on Windows, More info, Run anyway; on macOS, right click, Open (or
`xattr -dr com.apple.quarantine "/Applications/Breeze Client.app"` if it
says the app is damaged).

## What to test

1. **Try a moment (Wardrobe).** Under the player, five buttons: Look
   around, Check the cape, Wave, Hop, Stretch. Each plays that moment now,
   so you can judge each one: keep, off by default, or remove. They play
   even with Character moments switched off in Config (that switch only
   stops the random ones). With your system's reduced motion on, the
   buttons are greyed and nothing plays.
2. **Sign in, switch account, sign out** as before. Behind the scenes the
   launcher window no longer receives your Microsoft refresh token (it
   stays in the account file); nothing should look different, and staying
   signed in across restarts must still work.
3. **Export a modpack** (Mods). If a mod's config holds a password or a
   sign-in token, that file is left out and the launcher says which.
   Everything else exports as before.

## What changed since 1.1.0_test_1

- Try a moment in the Wardrobe.
- Security: the refresh token kept out of the launcher window (backlog item
  9); config files that look like they hold a password or token left out of
  exported packs (item 25).

## Not in this build, or not checked

- Same as 1.1.0_test_1: the API changes are not live until `server.js` and
  `src/` are uploaded; the in-game changes (mod 2.17.0) are not served by
  the API yet; everything after sign-in is UNVERIFIED in the real app
  windows (the build server has no Microsoft account).
- Checked automatically: launcher unit tests 58/58, UI tests 63/63 in a
  browser, Rust tests 137 passed here (debug, as the build server runs
  them); the build server installs and starts each installer.

## Reporting

Say "1.1.0_test_2" with what you did, what you expected and what happened.
