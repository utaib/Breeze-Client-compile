# Breeze Client — Startup

**Design spec v5** · splash window · Tauri 2 + React 19 + TypeScript

---

## 0. The five

All five stay live. Nothing gets overwritten.

| | File | Concept |
|---|---|---|
| v1 | `v1-wind-burst.html` | Wind charge implodes and bursts; the logo assembles from the shockwave. |
| v2 | `v2-silhouette-forest.html` | Night forest in flat silhouette layers, gust rolling through. |
| v3 | `v3-block-forest.html` | Same scene in real 16×16 block textures, side-on. Reads as Terraria. |
| v4 | `v4-isometric-island.html` | Isometric island of actual cubes. Square moon. |
| v5 | `v5-final-island.html` | All four merged, with a hand-drawn Breeze mob. **Superseded — the mob wasn't the real model.** |
| v5b | `v5b-swappable-textures.html` | Mob removed, atlas made swappable at runtime. |
| v6 | `v6-three-styles.html` | Three texture-free styles + seamless morph into the launcher UI. |
| v7 | `v7-wind-tunnel.html` | The name revealed as a void in flowing air. |
| v8 | `v8-wind-tunnel-forest.html` | v7 + forest + moon + aurora + torn leaves + morph to launcher. |
| v9 | `v9-cape.html` | Window opens from a line; Verlet cape. **Rejected — the figure was a hand-drawn approximation, not a real Minecraft model.** |
| **v10** | **`v10-daybreak.html`** | **Current.** Daylight. Square sun crests the treeline; light does the reveal. |


---

## v10 — Daybreak

`v10-daybreak.html` · **New direction: daylight.**

### What the feedback actually said

Your two favourites are **v1 (wind burst)** and **v2 (silhouette forest)**. Both are pure graphic
language — no characters, no textures, nothing pretending to be Minecraft art. Every version where I
hand-drew Minecraft-ish assets in code failed, and v9's figure failed hardest because a one-armed
side-view player model is instantly wrong to anyone who plays the game.

So the rule from here: **I don't draw Minecraft assets.** If real player models or block textures are
wanted, they come from the runtime extraction path in §Textures or from your artist. Everything I
build is shapes and light.

v10 is v2's language in daylight, with v1's punch put back in.

### The beat sheet

| | |
|---|---|
| 0 – 1250ms | Pre-dawn. Cold teal sky, the treeline in silhouette. The wordmark is **already there** — a dark shape barely separable from the sky at 30% alpha. The rail is running. |
| **1250** | **The square sun crests the treeline.** 460ms bloom pulse, a 0.26 alpha lift across the whole frame, and the ray fan snaps to 2.5× length. This is v1's burst, in daylight. |
| 1250 – 1950 | **The light sweep.** A 150px band travels right → left across the treeline and rim-lights the sun-facing edge of every trunk it passes, in warm `rgba(255,231,178)`. |
| 1350 – 2450 | The wordmark darkens into legibility as the sky brightens — ink from `rgb(16,52,70)` to `rgb(5,19,28)`, alpha 0.30 → 1.0. It is never *drawn in*. |
| 2350 | Tagline. |
| ready | Blow-out and morph into a **light-themed** launcher chrome. |

### The two ideas worth keeping

**The square sun.** Same device as the moon you liked, warm. 86px, rising from y 344 to 168 over
3.8s, with two darker patches so it reads as a disc-that-is-a-square rather than a plain rectangle.

**The rays read the wind.** 44 beams fan upward from the sun. Each one samples the *live* canopy
height at its own angle:

```js
const canopyTop = canopy[ (sunX + cos(a)*180) / 6 ];
const blocked   = clamp((HORIZON+40 - canopyTop)/70, 0, 1);
const flick     = 0.30 + 0.70*(1-blocked)*(0.75 + 0.25*sin(t/220 + i*1.7));
```

`canopy[]` is rebuilt every frame from the swayed tree positions. So when a gust bends a tree, the
beam behind it flickers. **The wind becomes visible in the light, not just in the branches** — which
is the most on-brand thing in the whole project and costs 121 floats.

### Notes

- First light-background version. The wordmark is ink on sky, so it reads as part of the landscape
  rather than a layer floating over it. The launcher chrome the morph reveals is light-themed to match.
- Five parallax layers with real atmospheric perspective — far trees `#2A6070` and hazy, near trees
  `#01060A`. In daylight distance means *lighter*, the opposite of every night version here.
- Six elements total: sky, sun, rays, trees, lockup, rail. Nothing arrives without a reason. That's
  the note about "a lot of stuff just appearing" taken seriously.
- Same `bendAt(x,t)` wind function as every version since v2.

### Do not

- Don't draw characters, mobs or block textures in code. That has now failed four times.
- Don't let the sun sit behind the treeline. It has to clear it — an occluded hero is no hero.
- Don't stretch the crest pulse past ~500ms. It's a beat, not a transition.
- Don't raise the tagline above ~0.86 alpha; on a light sky it starts competing with the wordmark.


---

## v9 — the cape

`v9-cape.html` · **The new direction.**

Everything up to v8 was a *scene*. v9 puts something alive in it, and makes the wind act on it in a
way you can feel rather than just see.

### The open

The splash does not fade in. It starts as a **2px line of aurora light** and the window itself grows
to full height in 620ms. In Tauri that's real geometry, not a CSS trick:

```rust
// splash window created at 720 x 2, centred, then animated
window.set_size(PhysicalSize::new(720, h))?;   // h: 2 → 420, ease-out-quart
window.set_position(PhysicalPosition::new(x, y_centre - h/2))?;
```

A web page physically cannot do this. That's the point — the first half second tells you this is a
native app, before any content has appeared.

### The cape

A figure stands on a ridge, backlit by a low **124px square moon**. The cape is a real Verlet cloth
solve — **6 × 11 points, 8 constraint passes per frame**, pinned along the shoulder line.

It hangs dead straight in still air. When the gust front reaches `x = 162` the lower rows swing out
and it streams. That moment is the whole design.

```js
const wx = wind*wind*1250 + wind*300;          // px/s², quadratic in wind
const ax = wx*(0.35 + depth*0.9) + flutter*0.30;
const ay = 760 - wind*wind*430*depth + flutter*0.55;   // lift as it planes out
```

Force scales with `depth` down the cape, so the hem moves furthest and the shoulders barely — which
is what makes it read as cloth rather than as a rotating rectangle. Two flutter frequencies (118ms
and 61ms) give it the high-frequency ripple over the low-frequency swing.

**Fold shading is the simulation, not a texture.** Each quad's colour comes from its live area over
its rest area:

```js
const area = |(b-a) × (e-a)|;
const lit  = clamp(area / (RX*RY), 0, 1.15);   // face-on = lit, edge-on = crease
```

There is no cloth texture anywhere in this file. Every crease you see is the solver.

*What went wrong first:* I had the forces roughly 10× too high and only 3 constraint passes, so the
cloth stretched unbounded and rendered as a rigid 300px plank. And the topology was wrong — pinned
along a vertical edge like a flag, which collapses under gravity. Pinned along the **top** row, it
hangs correctly at rest and streams correctly under load. Both were the same lesson: the sim has to
be right at zero wind before it can look right at full wind.

### Then the name

The v7 mechanic survives because nothing has beaten it: 1150 streamlines part around an invisible
body, and the body is the wordmark **plus the figure** — so the flow wraps the person too. You read
BREEZE as a hole in the air, then it ignites left to right on a 105ms stagger.

Ambient wind now sits at `0.26` rather than `0.15`, because a cape that only moves once every 3.6s
looks broken. The air is never dead after the first gust.

### Timing

Everything derives from `arriveAt(x)` — when the gust front reaches a given column.

| | |
|---|---|
| 0 – 620ms | Window grows from a line |
| 640 | Dead air. Ridge, moon, figure, cape hanging still |
| ~1370 | **The gust reaches the cape.** It snaps open |
| ~2050 → 3170 | The same front reaches the wordmark; the air parts around it |
| 3350 → 4230 | Ignition sweep |
| 4250 | Tagline |
| ready | Flow closes, lockup blows downwind, then morphs to the launcher chrome |

`MIN_DWELL` is 7000ms here rather than 5600 — the cape needs two full gusts to be worth watching.

### Do not

- Don't drop below 8 constraint passes. At 3 the cloth stretches and it's instantly obvious.
- Don't pin the cape along a vertical edge. It has to hang from the shoulders.
- Don't let ambient wind fall below ~0.22, or the cape sits limp between gusts.
- Don't tighten `WM.track` below `0.46em` — still the minimum gap for the void reveal to read.


---

## v8 — everything, merged

`v8-wind-tunnel-forest.html` · **Final.**

v7's wind tunnel is the spine. Everything else in this project that survived is layered onto it, and
the reason it holds together is that **one function drives all of it** — `bendAt(x, t)`. The same
wind that traces the streamlines bends the trees, tears the leaves loose, drifts the aurora and
sweeps the ignition across the letters.

### What came from where

| Layer | From |
|---|---|
| Sky ramp, stars, aurora ribbons, **square moon** | v2 / v4 |
| Five-layer parallax silhouette forest, bend 0.40 → 1.75 | v2, rebuilt |
| Torn leaves picked up by the flow field | v5 |
| Streamline wind tunnel, void reveal, ignition sweep | v7 |
| Seamless morph into the launcher chrome | v6 |

### The one thing I had to throw away

I first put the **individual tree canopies** into the obstacle mask, so the flow would wrap every
branch. It looked like static. Air wiggling through fifty small bodies destroys the long clean
streamlines that make the wordmark reveal legible.

The fix is a single soft **ground wedge** in the mask — a vertical gradient from transparent at
`HORIZON+6` to solid by `HORIZON+70`. The flow now lifts smoothly over the treeline as one mass,
which is both calmer and more physically honest: at this scale a forest canopy *is* a rough surface,
not fifty separate airfoils.

The trees still move — they just move because `bendAt` bends them, not because the solver pushes air
around them. Two systems reading the same wind, rendered independently.

### Layer separation

The hard part of merging a dense particle field with a scene is that the flow washes everything out.
Three things fix it:

- **The flow layer is composited twice** — full strength above the horizon, `0.22` below it. The
  forest sits in calm air; the sky carries the streamlines.
- **A fog band** at `HORIZON−46 → +56` separates the canopy from the sky so the treeline reads as an
  edge instead of dissolving into it.
- **Two canvases.** `#scenecv` holds sky, forest and flow; `#uicv` holds the lockup and rail. The
  scene canvas can be CSS-blurred at hand-off while the type stays perfectly sharp — you can't do
  that on one canvas.

### The hand-off

At `MIN_DWELL` the obstacle dissolves, the flow closes over the lockup and the wordmark is carried
downwind with a four-copy smear (760ms). Then, overlapping by 140ms, the morph starts: the badge,
wordmark and progress rail **lerp to their real launcher positions** in canvas —

```
badge  92px @ (74,150)  →  40px @ (12,14)     sidebar
wm     48px @ (180,214) →  19px @ (90,31)     header
rail   y352, x56, w608  →  y386, x0, w720     status bar
```

— while `#scenecv` blurs to 9px at 38% opacity and becomes the launcher's ambient background, and the
panels rise 14px into place. Same window, same canvas, no cut. The badge you watched carve a hole in
the air is the badge in the sidebar, and you saw it travel there.

### Do not

- Don't put the canopies back in the obstacle mask.
- Don't tighten `WM.track` below `0.46em` — that's the minimum gap at which air can dive between
  letters at this flow speed. Tighter and the reveal collapses into one blob.
- Don't raise the flow alpha over the forest above ~0.25.
- Don't drop the `(seedY - y) * 0.105` restoring term. Without it the flow never closes behind the
  letters and the whole reveal fails.


---

## v7 — the wind tunnel

`v7-wind-tunnel.html` · **This is the one I'd ship.**

### Why the others never landed

Every version before this had the same *structure* as Lunar's, Dawn's and Badlion's: pretty
background, logo slides in, bar fills. Mine were prettier. They were not different. That's why none
of them made anyone stare — you can't out-polish your way to surprise when the shape of the thing is
the same shape everyone else uses.

v7 changes the structure. **There is no logo entrance.** The name is not animated in. It is
*already there*, as a hole in moving air, and you read it before a single letter is drawn.

### How it works

1750 particles trace streamlines left to right across a static flow field. The field is built once,
at startup, from the lockup itself:

```
render the badge + wordmark to an offscreen canvas   →  alpha mask
one 3×3 box blur at 3px resolution                   →  D, a pressure field
central-difference gradient of D                     →  n, "which way is into the shape"
```

Per particle, per frame:

```js
let vx = 1, vy = 0;                       // clean air
if (d > 0.002) {
  vx -= n.x * d * 1.35;  vy -= n.y * d * 1.35;   // pushed off the body
  vx += t.x * d * 5.0;   vy += t.y * d * 5.0;    // slides along its surface
  vx *= (1 - d * 0.55);  vy *= (1 - d * 0.55);   // stagnates at the skin
}
if (d < 0.55) vy += (seedY - y) * 0.105 * back²;  // and closes behind it
```

That last line is the one that took four attempts. Without it, air deflected over the **B** never
comes back down, so the gaps between letters fill with wake and the whole word reads as one blob. With
it, the flow dives into every gap and you get six distinct letter-shaped bodies.

**Colour is mapped to speed, not chosen.** `speedColor()` runs steel → aurora → frost with velocity,
so the streamlines brighten exactly where they accelerate around the letterforms. That's the Venturi
effect doing the highlighting for you — the glowing outline that traces each glyph is physics, not a
stroke.

Downstream there's a real wake: curl noise scaled by `1 − (x − wordmarkX)/300`, clamped to the
wordmark's vertical band. The letters leave turbulence behind them, like a body in an actual airflow.

### The sequence

| | |
|---|---|
| **0 – 900ms** | Calm air. Empty frame, moving. The rail is already running. |
| **900 – 2200** | The obstacle fades *in*. The name appears as an absence. |
| **2320 – 2950** | Ignition: each letter lights left to right on a 105ms stagger, with a 210ms overbright flash as the wind reaches it. The badge lights 220ms before the **B**. |
| **3150** | Tagline, tracking settling `3.6px` → `0.2px`. |
| **on ready** | The obstacle dissolves, the flow closes over the lockup, and the wordmark is carried downwind with a four-copy smear. |

That exit is the tagline, executed literally: a client that doesn't get in your way — and then
doesn't. The air goes right back through where it was.

### Notes

- No Minecraft textures at all, so nothing invites a comparison it can't win. The square moon sits
  far off in the background and the flow passes in front of it.
- Field build is ~8ms once. Steady state is 1750 line segments per frame onto a decaying trail layer
  (`destination-out` at 0.021 alpha), composited `lighter` over the sky. 60fps on integrated
  graphics.
- **Show field** in the toolbar renders `D` directly, if you want to see the obstacle.
- `prefers-reduced-motion`: the field is stepped 90 frames to a settled still and cross-faded in.
  No flowing motion.
- Honest progress unchanged: caps at 92% until genuinely ready.
- Everything is canvas — the wordmark is drawn with the same font call that generated the mask, so
  the letters and the hole they carve can never drift apart.

**Wire the tracking value carefully if you touch it.** `WM.track = 0.46em` is not a style choice; it's
the minimum gap at which air can dive between letters at this flow speed. Tighten it and the reveal
collapses back into a blob.


---

## v6 — three styles, and the hand-off that actually differentiates

`v6-three-styles.html` — one file, three directions, switchable in the toolbar.

### The competitive read

Lunar describe their own aesthetic as *"lively hues, gradients, and luminous accents"* — big images,
vibrant colour, glow on everything. Dawn (formerly Feather, acquired by InPvP) sits in the same
family. Badlion too. **Every one of them competes on more.** More panels, more gradient, more neon.

Breeze's tagline promises less. Out-glowing them is a fight on their terms that a smaller team loses.
So v6 makes two bets:

1. **Be the calmest thing in the category.** One accent, air instead of neon, and no fake block
   textures anywhere — which also retires the problem that killed v3 and v5.
2. **Own the seam.** Every competitor's splash *cuts* to the launcher. Breeze's doesn't.

### The hand-off

820ms, `cubic-bezier(.16,1,.3,1)`, and **nothing fades out**:

| Element | From | To |
|---|---|---|
| Badge | 96px at (56, 104) | 40px at (12, 14) — the sidebar slot |
| Wordmark | 44px at (190, 112) | 19px at (90, 24) — the header |
| Progress line | y 356, spanning 56→664 | y 386, full width — the status bar divider |
| Scene | full canvas | `blur(9px) saturate(.72)`, 36% — the launcher's ambient background |
| Panels | — | rise 14px and fade in, 190ms after the morph starts |

The splash and the launcher are the same window and the same DOM. There is no second window, no
cross-fade, no flash of desktop. The badge you watched arrive on the wind is the badge sitting in the
sidebar three seconds later, and you saw it travel there. That's the thing none of them do.

### The three styles

| | What it is | Trade-off |
|---|---|---|
| **Aurora** | No scenery. Seven aurora ribbons in two speed sets, star field, square moon, a horizon band that brightens with the gust. Depth from light alone. | The most premium and the most restrained. Also the least obviously a *Minecraft* client — which may be exactly the differentiator, or may be a step too far. |
| **Silhouette** | The forest you liked, rebuilt properly: five parallax layers of generated vector trees, fog, square moon, per-layer bend factors 0.4 → 1.7. | Warmest and safest. Vector shapes, so nothing invites a comparison with real textures. |
| **Voxel line** | The isometric island as edge-lit geometry — glowing surface plate, solid dark mass beneath, wire cubes for trees that still sway in whole pixels. | The most distinctive by far. Minecraft's silhouette without pretending to be its art. Reads slightly holographic; less cosy than Silhouette. |

All three share the wind model, the letters-carried-in-by-the-front timing, the honest 92% progress
cap, the square moon, and the hand-off.

**My read:** Silhouette is the safe winner and the most "breezy". Voxel line is the one people would
screenshot. Aurora is the one that would still look good in three years. Pick on how much you want to
look like a Minecraft client versus like a premium tool that happens to launch Minecraft.

---

## 1. What v5 takes from each

- **From v4** — the dimetric camera. Every block shows top + left + right faces, which is the single
  thing that makes something read as Minecraft. And the square moon, because Minecraft's moon is a
  quad.
- **From v2** — weather. Ambient sway that never reaches zero, a gust front you can watch travel, the
  loading rail up from 240ms.
- **From v3** — real 16×16 block textures on a pixel grid, now extended with modern blocks.
- **From v1** — nothing, in v5b. The wind-burst ring came back as something the Breeze mob threw, and
  went out again with the mob. See §3.

Plus new: the waterfall, leaf litter, moss, firefly bushes, pale oak, and a slow vertical float on
the whole island.

---

## 2. The waterfall — and why it works

My honest first reaction was that it was risky. The whole design is built on one direction:
everything moves left → right on the wind. A waterfall is a hard vertical, and two competing motion
axes usually make a composition feel busy rather than alive.

So it only earns its place because of one detail: **the gust visibly pushes it sideways.**

```js
const dx = Math.round(bend * bend * 11 * f * f * 1.4);   // f = how far down the stream you are
```

The drift is squared in both terms — quadratic in the wind and quadratic in fall distance — so the
top of the stream barely moves and the bottom swings wide when a gust hits. The waterfall stops being
a second motion and becomes *evidence of the first one*. It's the most legible wind-reading in the
scene, better than the trees, because water has no stiffness to argue with.

It also solves a compositional problem: a floating island needs something to prove there's nothing
below it. The stream fades out and turns to mist about 110px down, which reads as water dispersing
into open air. Physically right for a sky island, and it keeps the fall clear of the loading rail.

Structure: a three-block basin at the `+x` edge (drawn live, not baked, so it shimmers), a bright
lip where the water leaves the block, ten falling segments narrowing and fading, two scrolling
highlight streaks per segment, and a mist plume at the bottom that also gets pushed downwind.

---

## 3. Art is a plug, not a painting — read this

**What went wrong in v5.** I hand-painted every texture from memory in a Python script, and drew a
"Breeze" that isn't the Breeze model — a purple blob with a vortex. To anyone who plays the game that
reads as wrong instantly, and no amount of palette tweaking fixes it. Producing accurate Minecraft
art is not something I can do well. The motion system, timing, composition and code are.

So v5b changes the architecture instead of the paint:

**The fake mob is gone.** The badge in the lockup already *is* a Breeze render — putting a worse one
next to it was redundant as well as wrong. v1's wind-burst ring went with it; if you want it back it
should be triggered by something real, not by a stand-in.

**The atlas is now a runtime plug.** At the top of the splash:

```js
const TEX_SLOTS = ['oak_log','oak_leaves','spruce_log','spruce_leaves','birch_log',
  'birch_leaves','grass_block_side','grass_block_top','dirt','stone','cobblestone',
  'short_grass','oak_log_top','water_still','leaf_litter','moss_block','firefly_bush',
  'pale_oak_log','pale_oak_leaves','pale_oak_log_top'];

const CUSTOM_TEX = {
  oak_log: '/textures/block/oak_log.png',
  // …any subset. Anything you leave out uses the bundled painted fallback.
};
```

Give it 16×16 PNGs — paths or data URIs — and `buildAtlasFrom()` composes the three-face shaded
atlas in an offscreen canvas at startup (night wash + 0 / 0.24 / 0.45 black per face, all
`source-atop` so alpha survives). Costs about 2ms, once. Slot names are the vanilla filenames on
purpose, so you can point straight at real textures.

**`extract-textures.py`** does that for you:

```
python3 extract-textures.py ~/.minecraft/versions/1.21.5/1.21.5.jar
```

It pulls the 20 files out of the jar, crops `water_still.png` (an animation strip) to frame 0,
biome-tints the greyscale foliage — `grass_block_top`, `short_grass` and all the leaves ship
**greyscale** in vanilla and render white if you don't — and writes a paste-ready `CUSTOM_TEX` block.
Point it at a resource pack `.zip` instead and the splash matches the player's pack.

This runs on the machine that already owns the game, so nothing is redistributed. **Ship the logic,
ported to Rust in the launcher — never the PNGs.** See §4 for why. The bundled painted atlas stays as
the first-launch fallback, which is the only moment it's actually needed.

**If you want the real Breeze in the shot**, that's a different job: it's an entity model
(`models/entity/breeze/`), not a block texture, so it needs a small model renderer or a pre-rendered
sprite sheet — and the sprite would have to come from your artist or from a render, not from me.
Worth doing as its own piece of work rather than bolted onto this.

## 4. Modern blocks

The atlas is now **20 tiles** (`breeze-iso-atlas.png`, 320×48, 3 face-shading rows).

| Tile | Use | Era |
|---|---|---|
| `leaf_litter` | Flat top-face overlay on ~30% of grass blocks. 42% transparent, so the grass shows through. | 1.21.5 |
| `moss` | Replaces grass top on ~16% of blocks. | 1.17 |
| `firefly_bush` | Three bushes, each emitting five fireflies. | 1.21.5 |
| `pale_oak_log` / `pale_oak_leaves` | One small pale oak at `(1,4)`. | 1.21.4 |
| `water` | Basin + waterfall. | — |
| plus | oak, spruce, birch, grass side/top, dirt, stone, cobble, tall grass, log ends | — |

**Two honest notes on these.**

*Fireflies break my own rule.* I said don't add a second accent colour, then added a warm one. The
argument for keeping it: fireflies are 2px, they blink to zero, and at that size the eye reads them
as *light* rather than as colour — the way a candle in a blue room doesn't make the room purple.
Sparse and small is doing all the work. If they ever get bigger or more numerous, cut them.

*Pale oak nearly didn't survive.* First pass it read as a grey boulder and dragged the eye straight
off the wordmark — the same failure that got birch cut in v3. It stayed only after darkening the
palette (`#C2CBBE` → `#8E998C`) and moving it to the far left of the island. It's now a quiet echo of
the moon rather than a competitor. Full-brightness pale oak is in the atlas if you ever want a Pale
Garden variant, but not in this scene.

### Texture licensing — unchanged and important

Mojang's block textures ship inside `client.jar` under the EULA, licensed to the player with the
game, not for redistribution. Breeze has an installer and real revenue; bundling them is the kind of
thing that gets a launcher a takedown rather than a warning.

**Path A** — the atlas here: 20 vanilla-style textures I painted from scratch. Yours outright.
**Path B** — at runtime, read the real ones out of the user's own install, where they're licensed:

```
versions/<version>/<version>.jar
  └─ assets/minecraft/textures/block/oak_log.png, leaf_litter.png, water_still.png …
```

Extract once after first game install, cache next to the launcher config, prefer the cache over the
bundled atlas. First launch falls back to Path A — exactly when you need a fallback. **And if the
user has a resource pack selected, read from that** and the splash quietly matches their pack.

`make-atlas.py` (needs Pillow) regenerates both atlases. Run the same tinting over extracted vanilla
PNGs and you get an identical layout.

---

## 5. Rendering

### Camera

2:1 dimetric, `HW 16 / HH 8 / VH 16` → 32px cubes. Origin `OX 536, OY 188`.

```
sx = OX + (x - z) * HW
sy = OY + (x + z) * HH - y * VH + FLOAT
```

Three skewed `drawImage` calls per cube, `imageSmoothingEnabled = false`, `E = 1.02` overscale so
anti-aliasing leaves no hairline seams between faces.

### The island floats

```js
FLOAT = Math.round(Math.sin(t / 2700) * 3);
```

Whole pixels, 5.4s period, ±3px. Applied to `syOf()` and to the terrain blit, so everything moves
together. It's barely perceptible frame to frame and it's the difference between a diorama and a
thing that's actually hovering.

### Everything else

- 9 × 7 footprint, heightmap 0–2, underside tapered by `max(1, round(6 × (1 − r×1.85)))` so it
  narrows to a point instead of a flat slab.
- A block is drawn only if exposed. **Terrain is baked once** to an offscreen canvas (with
  `FLOAT = 0`) and blitted with a single `drawImage` per frame — only trees, water, mob and particles
  redraw.
- Trees are `{dx, dy, dz, isLog}` block lists. Five candidate spots, each 22% likely to stay empty:
  a different island every launch. Painter's sort by `(x + z)` then `y`.
- **Sway is pixel-snapped.** `Math.round(bend × 11 × hf + wobble)` for leaves, `× 3` for logs. Blocks
  jump one pixel at a time. Fractional sliding is what made v3's trees feel like a CSS transform.

---

## 6. The wind model

One function. Everything reads from it — trees, waterfall drift, dust, mist, vortex spin, cloud
speed, even the badge nudge.

```
PERIOD = 3400ms,  FRONT_X0 = -220,  FRONT_SPAN = 1140px,  SPEED = 0.335 px/ms

frontX(t)   = FRONT_X0 + (t % PERIOD)/PERIOD * FRONT_SPAN
arriveAt(x) = (x - FRONT_X0) / SPEED

bendAt(x, t):
  d       = (x - frontX(t)) / 150
  pulse   = exp(-d²) * (d < 0 ? 1.0 : 0.55)      // sharp front, soft recovery
  ambient = 0.14 + 0.09 * sin(t/1650 + x/240)
  return ambient + pulse
```

Because it's a function of *x*, the bend travels. **Ambient never reaches zero** — a frozen scene
during a long load is the failure we're designing against.

**Particles:** wind-charge dust (cap 55, alpha 0.42, 4–6px squares — swap the `fillRect` for your
real sprite PNGs at 6–8px, blue/white/lilac only); torn leaves (cap 26, each a 6×6 crop of *that
tree's own leaf texture*); waterfall mist (cap unbounded but short-lived, pushed downwind).

---

## 7. Timing

| Element | World-x | Arrives |
|---|---|---|
| Scene, moon, island | — | 0ms — already in progress when the window opens |
| Loading rail | — | 240 – 1200ms; stage label at 380ms |
| Logo badge | 104 | ~1000ms, then a ±2.2px hover |
| `B R E E Z E` | 190 → 400 | 1230 – 1880ms — **each letter arrives as the front reaches its x** |
| `— CLIENT` | 190 | 1510 – 2350ms |
| Tagline | — | 2060 – 3080ms, tracking `0.26em` → `0.01em` |
| Gusts | — | every 3400ms, forever |
| Breeze fires | — | every 6600ms, forever |

```
MIN_DWELL = 5200ms    // one full extra gust after the lockup settles
MAX_DWELL = 14000ms   // exit anyway; let the app show its own error state
EXIT      = 430ms
```

| Stage | Copy | Target |
|---|---|---|
| 1 | Checking for updates | 20% |
| 2 | Verifying Java 21 | 46% |
| 3 | Restoring your session | 68% |
| 4 | Loading cosmetics | 92% |
| 5 | Ready | 100% |

Progress **caps at 92% until the app is genuinely ready.** A bar that hits 100% and sits there is the
most common launcher lie, and it's the exact feeling we're selling against.

Failure copy replaces the stage label — no modal, no apology:
`Can't reach breezeclient.net — retrying` · `Java 21 download failed — opening the launcher anyway`

---

## 8. Type, colour, layout

| Role | Face | Settings |
|---|---|---|
| Display | **Archivo** `wdth 125 / wght 700` | `BREEZE` 44px, tracking `0.115em` |
| Display body | **Archivo** `wdth 104 / wght 400` | Tagline 14px |
| Utility | **IBM Plex Mono** 500 | `CLIENT` 10.5px/`0.60em`, stage 10px/`0.26em`, version 10px/`0.20em` |

Archivo at max width is the point — stretched wide and open, so the wordmark reads as *air*. Ship
both self-hosted. Type carries `0 3px 26px rgba(3,7,14,.95)` since it sits over a scene.

`--aurora #6ADBDB` is the only cool accent — ribbons, `CLIENT`, progress line, gust streaks, the
Breeze's eyes, the impact ring. `--gust #7A82C4`, `--charge #BFAFC9`, `--frost #E4F1F2`,
`--steel #2C5C82`. Sky ramp `#04070F → #0A1B31 → #123A55 → #175263`. Firefly `#F6E79A` is the sole
warm value; see §4.

```
 ┌───────────────────────────────────────────────────────────┐
 │              ◻ moon        ~ aurora ~        ⟁ Breeze     │
 │  ┌─────┐                                  ▄▟███▙▄         │
 │  │logo │  B R E E Z E                   ▟█████████▙       │
 │  │ 96  │  —— C L I E N T                ▜███████████      │
 │  └─────┘  A client that doesn't…          ▜█████▛ ║ fall  │
 │                                             ▜█▛   ░ mist  │
 │  ──────────────────────────────────────────────────────   │ 356
 │  RESTORING YOUR SESSION                         v2.4.0    │ 373
 └───────────────────────────────────────────────────────────┘
```

Lockup left, island right, gust left → right, waterfall on the far side so it never crosses the type.
Scrim from `y 336` down keeps the rail on a clean field.

---

## 9. Hand-off

430ms: lockup translates `+150px` and blurs out (same direction as every gust) → canvas clears →
launcher UI comes up beneath → only in the last 30% does the window itself dissolve.

```rust
#[tauri::command]
async fn splash_done(app: tauri::AppHandle) {
    use tauri::Manager;
    if let Some(main) = app.get_webview_window("main") { let _ = main.show(); let _ = main.set_focus(); }
    if let Some(splash) = app.get_webview_window("splash") { let _ = splash.close(); }
}
```

```jsonc
{ "label": "splash", "url": "splash.html", "width": 720, "height": 420,
  "decorations": false, "transparent": true, "shadow": false, "resizable": false,
  "center": true, "alwaysOnTop": true, "skipTaskbar": true, "visible": true }
// + "macOSPrivateApi": true for a transparent window on macOS
```

`main` shows **before** `splash` closes — one frame of overlap, so there's never a flash of desktop.

---

## 10. Budget & accessibility

- One canvas, one 20 KB atlas in a single GPU texture. Type is DOM, world is canvas.
- Per frame: 1 terrain blit + ~120 tree cubes × 3 faces + 20 waterfall faces + ~30 mob cubes + 55
  dust + 26 leaves + 15 fireflies ≈ **480 draw calls.** Comfortable 60fps on integrated graphics.
- Nothing allocated in the loop; block lists built once at start.
- `dt` clamped at 60ms so a GC pause stretches the wind instead of teleporting it.
- Atlas and logo inlined as base64 — **no network request at startup, ever.** ~87 KB total.
- `prefers-reduced-motion: reduce` → composed scene as a still, cross-faded over 500ms. No gust, no
  charge, no blow-away. `MIN_DWELL` still applies.
- **No flash frames.** Brightest moment is a 0.05 alpha veil; the impact ring peaks at 0.85 stroke on
  a 78px ellipse. Inside WCAG 2.3.1.
- Tagline 74% and stage 78% clear 4.5:1 over the scrim. Splash takes no focus; `Esc` jumps to
  hand-off.

---

## 11. Do not

- Don't ship Mojang's PNGs in the installer. §4.
- Don't render blocks side-on. If you can't see a grass block top, it isn't Minecraft.
- Don't make the fireflies bigger or more numerous. §4.
- Don't brighten pale oak back to vanilla in this scene. §4.
- Don't let the waterfall stop bending with the wind — the bend is the only reason it isn't a
  competing motion.
- Don't animate sway with fractional pixels. `Math.round` or it stops looking like Minecraft.
- Don't freeze the scene during a long load. Ambient sway is what says the launcher is alive.
- Don't centre the lockup, don't add a spinner, don't write "Loading…".

---

## 12. Files

| File | What |
|---|---|
| `v5b-swappable-textures.html` | **Current.** Self-contained, offline, real timings. |
| `extract-textures.py` | Pulls the 20 textures from a client.jar or resource pack. |
| `v5-final-island.html` | Superseded; kept for comparison. |
| `v1-`…`v4-*.html` | Kept for comparison. |
| `still-v1.png` … `still-v5b.png` | One frame each. |
| `breeze-iso-atlas.png` | 320×48, 20 tiles × 3 face-shading rows (v4, v5). |
| `breeze-block-atlas.png` | 208×48 depth-tinted atlas (v3 only). |
| `make-atlas.py` | Generator for both. Needs Pillow. |

Preview controls: Replay · 1× / 0.5× · **Slow network** · **Reduced motion**. The blurred UI at
hand-off is a placeholder skeleton, not the real launcher.

Next: say the word and I'll port v5b to `BreezeSplash.tsx` with `ready` / `logoSrc` / `atlasSrc` /
`onDone` props.
