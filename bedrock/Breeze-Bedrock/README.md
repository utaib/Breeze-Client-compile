# Breeze for Bedrock Edition

The Breeze add-on for Minecraft Bedrock Edition: two packs, Breeze art only.
Version in [`VERSION`](VERSION), changes in [`CHANGELOG.md`](CHANGELOG.md),
the plan in [`docs/PLAN_1.3.0.md`](../docs/PLAN_1.3.0.md).

| Pack | Kind | Where it works |
| --- | --- | --- |
| **Breeze UI** | Resource pack | Turn it on in Settings, Global Resources and it applies in every world, Realm and server (Windows, Android, iOS). Or add it to one world |
| **Breeze HUD** | Behaviour pack (Script API) | Worlds and servers that add it. No experiments needed |

Works on Bedrock **1.21.0 (June 2024) and later**, up to 26.60, the newest
release on 2026-11-28. Consoles cannot import packs; a console player gets
them from a world, Realm or server that has them.

## Install

On Windows, the Breeze launcher does it: Bedrock Edition, Home, **Install
the add-on** (it downloads the file from the Breeze API, checks its SHA-256,
imports both packs and turns Breeze UI on in every world). By hand, open
`dist/Breeze-1.0.1.mcaddon` (or one `.mcpack`) on Windows, Android or iOS:
the game imports it. Then:

- **Breeze UI:** Settings, Global Resources, activate Breeze UI. Its gear
  button chooses one of eight looks: crosshair (the game's own, Breeze
  cross, Breeze dot, Breeze circle) with normal or low fire.
- **Breeze HUD:** in a world's settings, Behavior Packs, activate Breeze
  HUD. Look straight up and crouch for a second to open the Breeze menu, or
  run `/scriptevent breeze:menu` where commands are allowed.

## What is in it

**Breeze UI** replaces textures only: the action bar and title background,
the hotbar and its selected slot, the crosshair (subpacks) and the flames
on burning players and mobs (`textures/flame_atlas`, subpacks with low
fire; fire blocks in the world are unchanged). It does not edit the JSON UI
layout: a layout edit copies Mojang's screen code and breaks silently when
an update renames a control, a texture keeps working. Ore UI screens (start
screen, settings) cannot be changed by any pack.

**Breeze HUD** writes one to three lines in the action bar, twice a second:
coordinates, facing, the time of day and the day; armour and held-item
durability (by default only items at 25% or less, in red); effects with
the time left. Each part can be switched off in the Breeze menu, and the
whole HUD too. Settings are kept per player in the world. It uses only
stable modules, `@minecraft/server` 1.11.0 and `@minecraft/server-ui`
1.1.0. A server or map that uses the action bar itself will be overwritten
while Breeze HUD is on; turn the HUD off there.

## Layout

```
packs/breeze_ui/     resource pack (manifest, textures, subpacks, texts)
packs/breeze_hud/    behaviour pack (manifest, scripts/main.js, scripts/format.js, texts)
dist/                the packaged .mcpack and .mcaddon files and SHA256SUMS (committed)
tools/make_art.py    draws every texture from code (needs Pillow); output committed
tools/package.py     builds dist/ reproducibly (standard library only)
tools/check.py       offline checks: JSON, manifests, UUIDs, textures, languages, imports, product text
tools/check_vanilla.py  re-reads Mojang/bedrock-samples: the paths replaced still exist at that size
tools/test_format.mjs   unit tests for the HUD's helpers
tools/ci.sh          all of the above plus the manifest schema and the type check
```

## Release steps

1. Change the packs; for art, change `tools/make_art.py` and run it.
2. Raise `VERSION` and the `version` of both manifests (header and module)
   to the same number; add a `CHANGELOG.md` entry.
3. `python3 tools/package.py`, then `bash tools/ci.sh` (CI runs it on
   `utaib/Breeze-Client-compile`, never in this repository).
4. Never change a pack's UUIDs (`tools/uuids.lock.json`): the game uses them
   to replace an older copy with a newer one.

## Not verified

Nothing has been loaded in a real game yet. **UNVERIFIED:** the look of
every texture in game, the crosshair path (`textures/ui/cross_hair`, not in
Mojang's sample files), that `flame_atlas` is what the game draws over the
screen when the player burns, and the HUD script running in a world. A load
test in the Bedrock Dedicated Server needs its licence accepted by the owner.
