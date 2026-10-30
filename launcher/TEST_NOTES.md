# Breeze Client 1.1.0_test_3

Third testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_3** (Config, About); the splash reads
`v1.1.0-test.3`. Everything in 1.1.0_test_2 is in it too; its notes still
apply (`docs/test-builds/1.1.0_test_2.md`).

Installs over 1.0.38, 1.1.0_test_1 or 1.1.0_test_2 and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open (or `xattr -dr com.apple.quarantine "/Applications/Breeze
Client.app"` if it says the app is damaged).

This build is two security fixes; nothing should look different.

## What to test

1. **Staying signed in (Windows).** Open it once: you should still be
   signed in. Close it, open it again: still signed in. Switch accounts and
   sign out as usual. Then open `%USERPROFILE%\.breezeclient\accounts.json`
   in Notepad: it now starts with `breeze-sealed:dpapi:1:` followed by
   letters and numbers, with no readable token in it. Windows locks it to
   your Windows user, so a copy of the file is useless on another PC or
   account.
2. **The start-up scene.** It should play as before (several scenes, picked
   at random; reopen the launcher a few times). The launcher no longer
   allows any script that is not part of it, and the scenes still run.
3. Everything from 1.1.0_test_2 (Try a moment, export, sign-in) as before.

## What changed since 1.1.0_test_2

- Windows: the saved accounts file (which holds your Microsoft sign-in) is
  encrypted for your Windows user (security backlog item 9).
- The launcher's content policy allows no inline scripts any more (item
  17).

## Known limits

- **Going back on Windows asks you to sign in again.** 1.0.38,
  1.1.0_test_1 and 1.1.0_test_2 cannot read the encrypted file, so they
  start signed out; signing in there writes a plain file again, which this
  build encrypts the next time it opens. Linux and macOS are unchanged.
- The same as 1.1.0_test_2: the API changes are not live until `server.js`
  and `src/` are uploaded; the in-game changes (mod 2.17.0) are not served
  by the API yet; everything after sign-in is UNVERIFIED in the real app
  windows (the build server has no Microsoft account).
- Checked automatically: launcher unit tests 62/62, UI tests 63/63 in a
  browser, Rust tests 143 passed here on Linux; the account file tests that
  call Windows run on the build server's Windows machine; the build server
  installs and starts each installer. The start-up scene was seen drawing
  under the new policy in a Linux build here.

## Reporting

Say "1.1.0_test_3" with what you did, what you expected and what happened.
