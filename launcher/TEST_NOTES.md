# Breeze Client 1.1.0_test_7

Seventh testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_7** (Config, About); the splash reads
`v1.1.0-test.7`. Everything in 1.1.0_test_6 is in it too (the new Store,
Customize, the Mods page strip, export), and its notes still apply
(`docs/test-builds/1.1.0_test_6.md`).

Installs over 1.0.38 or any earlier 1.1.0 test build and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open.

This build is Hosting: a Minecraft server on your own computer.

## What to test

1. **The Hosting tab** is back in the rail (between Gifts and FM). With no
   server yet it says what hosting is and has Make a server.
2. **Making a server.** Make a server: a name, Fabric, Paper or Vanilla,
   the Minecraft version, the port (25565 is Minecraft's own) and how much
   memory it may use. Below that, what hosting means, and two boxes: that
   you understand it, and the Minecraft EULA. Make server stays grey until
   both are ticked. It then downloads the server (and the Java that
   version needs, if Breeze does not have it yet), which can take a minute.
3. **Starting it.** Start. The tab gets a green 1, a green banner says a
   server is running, and the card shows two addresses: `localhost:25565`
   for this computer and `<your computer>:25565` for others at home. Join
   from Minecraft (Multiplayer, Direct Connection, `localhost`). The console
   shows what the server says; type `say hello` or `list` and Send.
4. **Stopping it.** Stop: the button says Saving..., the console shows the
   world being saved, then Stopped. Start it again: your world is still
   there.
5. **Closing Breeze while it runs** stops the server too (after saving).
   Check Task Manager: no `java` left behind from the server.
6. **A port in use.** Make a second server on the same port and start it
   while the first runs: it says the port is in use and does not start.
7. **Over the internet** works only if you forward the port on your router
   yourself; the page says so, and that whoever you give the address to
   sees your home's public address.

## What changed since 1.1.0_test_6

- Hosting: the page, the tab, the consent and EULA boxes, Breeze's own Java
  for the server, Fabric servers, a port check, a console that takes
  commands, Stop that saves first, servers stopped when Breeze closes, and
  a green count on the tab while one runs.
- The rail fits a 640 px tall window with the extra tab (shorter tabs on
  short windows).

## Known limits

- Breeze does not give out server addresses (`name.breezeclient.net`);
  that waits on your decisions in `docs/HOSTING_PLAN.md`.
- Mods and plugins for the server go in its folder (Folder button):
  `mods/` for Fabric, `plugins/` for Paper. There is no in-app picker for
  server mods yet.
- A server made by the old prototype (before 1.1.0) is not listed; make it
  again here.
- Checked automatically: Rust tests (5 new for hosting), a live test on the
  build server that starts a real 1.21.4 server, sends a command and stops
  it with the world saved (results below), UI tests in a browser (6 new for
  hosting), `npm run build`. Joining from the game and the router setup are
  for you to try.

## Build results

To be filled in from the build run.

## Reporting

Say "1.1.0_test_7" with what you did, what you expected and what happened.
