# Mod version matrix

> Imported unchanged from branch `mod/rebuild-phase-a` (commit `3b0fefb`,
> 2026-09-27), because code here cites it. It was read from Mojang, Fabric and
> Modrinth by that session. **UNVERIFIED** since: this project's cloud
> environment cannot reach those hosts, so it has not been re-derived. Re-run
> the commands at the end before relying on it for a release.

Every fact here was read from an authoritative API on 2026-09-27, not from
memory. The commands are included so it can be re-derived rather than trusted.

- Minecraft releases: `https://launchermeta.mojang.com/mc/game/version_manifest_v2.json`
- Java per version: each release's own JSON, `javaVersion.majorVersion`
- Fabric: `https://meta.fabricmc.net/v2/versions/game`
- MCEF: `https://api.modrinth.com/v2/project/mcef/version`

## The headline

**Fabric covers all 40 targets. MCEF covers 10 of them.**

The in-game React interface needs an embedded browser, and the embedded browser
is MCEF. On the other 30 versions there is no browser to put it in, so the
native Java screens are not a fallback that can be deleted once the web UI is
finished: for three quarters of the range they are the only interface there will
ever be.

That is the single most important constraint on the rebuild, and it is why the
existing "MCEF compile-only, degrade to the native menu" design in build.gradle
is correct and should be kept.

## Real releases, 1.16 to current

Forty, verified present in Mojang's manifest:

```
1.16   1.16.1 1.16.2 1.16.3 1.16.4 1.16.5
1.17   1.17.1
1.18   1.18.1 1.18.2
1.19   1.19.1 1.19.2 1.19.3 1.19.4
1.20   1.20.1 1.20.2 1.20.3 1.20.4 1.20.5 1.20.6
1.21   1.21.1 1.21.2 1.21.3 1.21.4 1.21.5 1.21.6 1.21.7 1.21.8 1.21.9 1.21.10 1.21.11
26.1   26.1.1 26.1.2 26.2   26.3
```

Current release is **26.3**; current snapshot is 26.4-snapshot-1. From 26.1
Mojang numbers releases by year rather than continuing the 1.x line.

**1.20.5 is a real release.** An earlier brief recorded confusion about whether
it exists. It does, it is in the manifest, and it is the version where the Java
requirement jumps to 21. What it does *not* have is an MCEF build.

## Java, which changes five times across the range

| Minecraft | Java | Mojang runtime |
| --- | --- | --- |
| 1.16 – 1.16.5 | 8 | jre-legacy |
| 1.17 – 1.17.1 | 16 | java-runtime-alpha |
| 1.18 – 1.20.4 | 17 | java-runtime-beta / gamma |
| 1.20.5 – 1.21.11 | 21 | java-runtime-delta |
| 26.1 – 26.3 | 25 | java-runtime-epsilon |

`java_release` in gradle.properties has to follow this per target. A single
value cannot serve the range: bytecode built for 21 will not load on 1.16's
Java 8, and 26.x expects 25.

## MCEF, and therefore the web interface

MCEF publishes for Fabric, Forge and NeoForge. Latest is **2.1.6**. Its Fabric
builds exist for exactly:

```
1.20.1  1.20.2  1.20.3  1.20.4  1.20.6
1.21    1.21.1  1.21.2  1.21.3  1.21.4
```

Ten versions. Note the gaps inside its own range: **1.20 and 1.20.5 have no MCEF
build** even though versions on both sides do, so the set is not a contiguous
span and cannot be expressed as a minimum version.

Nothing at or above 1.21.5 has an MCEF build, which includes every 26.x release.
If that changes upstream the set widens; until it does, the web interface cannot
run there however the mod is written.

## What this means for the build

Two interface tiers, decided per target at build time:

- **Native tier** (30 versions): Java screens only. 1.16–1.20, 1.20.5, and
  1.21.5 onward including all of 26.x.
- **Web tier** (10 versions): Java screens plus the MCEF interface, with the
  native screens still reachable and still the fallback when MCEF is absent at
  runtime, which it will be for any player who has not installed it.

A player on the web tier only gets the web interface if MCEF is actually
present. The mod cannot bundle it: MCEF pulls platform-specific Chromium natives
on first run, and shipping a copy inside the mod jar would both bloat the jar and
collide with an existing installation. Installing it is the launcher's job,
per version, which is why 1.0.25 exists.

## Re-deriving this

```bash
# Releases
curl -s https://launchermeta.mojang.com/mc/game/version_manifest_v2.json \
  | jq -r '.versions[] | select(.type=="release") | .id'

# MCEF's real coverage
curl -s https://api.modrinth.com/v2/project/mcef/version \
  | jq -r '.[] | select(.loaders[]=="fabric") | "\(.version_number) \(.game_versions|join(","))"'

# Fabric's coverage
curl -s https://meta.fabricmc.net/v2/versions/game \
  | jq -r '.[] | select(.stable) | .version'
```
