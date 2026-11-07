# Breeze Client 1.1.0_test_10

Tenth testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_10** (Config, About); the splash reads
`v1.1.0-test.10`. It is test_9 with the changes from your test of it.

Installs over 1.0.38 or any earlier 1.1.0 test build and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open.

## What to test

1. **Moments.** Style, Try a moment: Look around, Check the cape, Wave,
   Hop, Stretch, Spin, Backflip, Dance, Cheer. The sword, ender pearl and
   cat are gone.
2. **Store.** No glow: not behind your player in the spotlight, not
   behind the big item, not on the cards or in an item's window. Rarity
   shows on its label.
3. **Hosting, the page.** With no server: one line saying it hosts on this
   computer and is local for now, and New server. No boxes, no green dot,
   no banner.
4. **New server.** One column: name, software, version, port, memory
   (at most a quarter of your computer's memory, so the game has room), a
   short paragraph, the two boxes, Create.
5. **Running.** The card says Running in plain text; the tab shows a plain
   count. Stop it.
6. **The crash.** Start a server, stop it, and keep using Breeze for a bit.
   If Breeze closes again, look in `%USERPROFILE%\.breezeclient\logs\` for
   `launcher-crash.log` and send it (or say it is not there, which means
   the crash was outside Breeze's own code).
7. **Delete.** On a stopped server, Delete, then Delete again to confirm.
   The server and its world are removed. A running server has no Delete.
8. **Hosting on Windows.** Start a server, then end Breeze from Task
   Manager (End task). The server's `java` should be gone within a few
   seconds.

## What changed since 1.1.0_test_9

- The sword, ender pearl and cat moments are removed.
- The Store has no coloured glows or gradients.
- Hosting: a plain page and New server window, text status, a plain count
  on the tab, Delete for a stopped server.
- Server memory is capped at a quarter of the computer's memory (it was
  three quarters).
- Release builds write `logs/launcher-crash.log` if Breeze stops on an
  internal error.
- The 3D player is drawn only as often as it needs: full rate while
  something moves, 30 a second while it only sways, 10 while another
  window (the game) is in front.

## Known limits

- The crash you saw after stopping a server was not reproduced here. The
  lower memory cap and the crash log are the fixes so far; its cause is
  UNVERIFIED.
- Joining from outside your home network is not offered yet.
- The same as before: everything after sign-in is UNVERIFIED in the real
  app window on the build server, so these pages were checked in a
  browser here.

## Build results

Filled in after the build run.

## Reporting

Say "1.1.0_test_10" with what you did, what you expected and what happened.
