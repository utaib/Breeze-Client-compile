# Breeze mod 2

The Breeze Client Minecraft mod: a React interface embedded in Minecraft
through MCEF, a typed Java bridge, and the Breeze modules, HUD, cosmetics,
friends and hosting.

Start with [`CLAUDE.md`](CLAUDE.md) (the master specification) and
[`docs/PROJECT_MEMORY.md`](docs/PROJECT_MEMORY.md) (current state, decisions,
blockers).

## Layout

| Path | What |
| --- | --- |
| `frontend/` | The interface. React 19, TypeScript, Vite. The same bundle runs in a browser and in game |
| `contract/bridge.json` | Every bridge action, error and event. Both sides are tested against it |
| `common/` | Plain Java 17 shared by every version: bridge core, settings, API client. No Minecraft classes |
| `versions/<minecraft>/` | One Fabric module per Minecraft version (Loom, Mojang mappings) |
| `scripts/` | Version check, CI driver, release packaging |
| `docs/` | Bridge, project memory, feature review, version matrix |

## Requirements

- JDK 21 (builds Java 17 bytecode for 1.20.1)
- Node 22 and npm
- Network access to Fabric's and Mojang's servers the first time you build
- Minecraft needs no separate install: Loom downloads it

## Build

```bash
cd frontend && npm ci && cd ..
./gradlew :common:test :versions:1.20.1:build
```

The jar is `versions/1.20.1/build/libs/1.20.1.jar`. The interface is built into
it automatically (the Gradle build runs `npm run build`).

## Test

```bash
./gradlew :common:test          # bridge core, JUnit
cd frontend
npm test                        # unit tests, Vitest
npm run build                   # bundle and bundle checks
npx playwright test             # browser tests (set CHROME116 to also test Chrome 116)
```

Without access to Fabric's and Mojang's servers the version module cannot be
built. `scripts/check-java-offline.sh 1.20.1 --since <commit>` still
type-checks Breeze's own code in it; see the script for what it cannot check.

## Run in Minecraft

```bash
./gradlew :versions:1.20.1:runClient
```

This starts a development client with MCEF, so the web interface is live. The
first start downloads MCEF's Chromium build (about 150 MB). Press Right Shift in
game for the Breeze menu.

To run the automated in-game check (real window, real X11 input, open and close
leak check), on Linux with Xvfb, openbox, xdotool, ImageMagick and ffmpeg:

```bash
scripts/ci/run-minecraft-test.sh autotest
```

Evidence lands in `autotest/`: the harness and driver logs, screenshots of the
game window, a video, and the game log.

## Release source ZIP

```bash
scripts/package-source.sh 1.20.1 [git revision]
```

Writes `build/release/Breeze Mod for 1.20.1.zip`: everything needed to build
that one version (Gradle wrapper, `common/`, `contract/`, frontend source,
`docs/`, `scripts/`, `versions/1.20.1/`), from committed files only, with a
`SOURCE.txt` naming the commit. The same commit always gives the same ZIP,
byte for byte. CI builds the jar from this ZIP, not from the checkout.

## Players

A player needs Fabric Loader, Fabric API, and this jar for their Minecraft
version. The web interface additionally needs the MCEF mod for that version;
without it Breeze uses its native menus.

## Versions

`2.MINOR.PATCH`: features bump MINOR, fixes bump PATCH, no suffixes on public
releases. `scripts/check-version.sh` keeps `gradle.properties`,
`frontend/package.json` and `CHANGELOG.md` in agreement.
