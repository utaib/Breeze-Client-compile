# Breeze Client — Startup

**DESIGN.md · v12 — the shuffle**

The startup animation is not one animation. It's a **roster of eight**, and the launcher picks a
different one every time you open it. You never know which one is coming.

That is the feature. Every competitor has exactly one splash, so their launcher looks the same on
launch #1 and launch #400. Breeze's looks different every time, and after a week a user has seen a
night forest, a wind tunnel, a floating island and a sunrise — all obviously the same product,
because they share a wind model, a type system, a palette and a progress rail.

---

## 1. The roster

> **Superseded by an owner decision (2026-08): all twelve scenes ship,** including
> the four listed below under "Not shipping". The live roster and weights are in
> `breeze launcher/public/splash/scenes.json`. The rest of this document (the
> shared contract, budgets and rules) still applies.

Eight scenes ship. Each is a self-contained HTML preview in this folder.

| id | File | Scene | Weight | Key |
|---|---|---|---|---|
| `burst` | `v1-wind-burst.html` | Wind charge implodes; the logo is compressed **inside** the core and the burst releases it, block by block. Runs at a third speed — ~7.9s. | 12 | night |
| `forest` | `v2-silhouette-forest.html` | Night forest, five parallax silhouette layers, gust rolling through. | 16 | night |
| `blocks` | `v3-block-forest.html` | Same forest built from 16×16 block textures, side-on. | 7 | night |
| `island` | `v4-isometric-island.html` | Isometric floating island of real cubes. Square moon. | 14 | night |
| `tunnel` | `v7-wind-tunnel.html` | 1750 streamlines part around an invisible body — you read BREEZE as a hole in the air before any letter is drawn. | 16 | night |
| `tunnelforest` | `v8-wind-tunnel-forest.html` | The wind tunnel over the silhouette forest. | 14 | night |
| `daybreak` | `v10-daybreak.html` | Pre-dawn; a square sun crests the treeline and a band of light sweeps the trunks. | 14 | **day** |
| `cloudsea` | `v11-cloud-sea.html` | Golden hour above a sea of flat cloud slabs; shafts fire only through the gaps. | 14 | **day** |

### Not shipping

| | Why |
|---|---|
| `v5-final-island.html` | The hand-drawn Breeze mob wasn't the real model. Cut. |
| `v5b-swappable-textures.html` | Superseded — its texture-swap loader is worth porting into `blocks`, but the scene itself is empty without the mob. |
| `v6-three-styles.html` | Three abstract styles, none as strong as what's on the roster. |
| `v9-cape.html` | The figure was a hand-drawn approximation — one arm, wrong proportions. **Only revive this if you supply a real player model**; see §6. |

Both are kept in the folder as reference. Don't wire them into the shuffle.

---

## 2. Picking a scene

```js
// launcher config: breeze/config.json → { "splash": { "recent": ["tunnel","forest"] } }

function pickScene(now, recent, isFirstEverLaunch) {
  if (isFirstEverLaunch) return 'tunnel';          // §3 — never leave the first one to chance

  const hour = now.getHours();
  const daytime = hour >= 7 && hour < 19;

  return weightedPick(ROSTER.filter(s =>
    !recent.slice(-2).includes(s.id)               // no repeats within 3 launches
  ).map(s => ({
    id: s.id,
    // scenes matching the user's actual time of day are twice as likely
    w: s.w * ((s.key === 'day') === daytime ? 2 : 1)
  })));
}
```

Three rules, and each earns its place:

**No repeats inside three launches.** Without this, random feels broken — people get the same scene
twice and assume it isn't shuffling at all.

**Time-of-day weighting.** Open the launcher at 8am and you're twice as likely to get `daybreak` or
`cloudsea`; at 11pm you'll almost always get a night scene. It costs one `getHours()` call and it
makes the shuffle feel like it knows something.

**Weights, not uniform.** `forest` and `tunnel` are the strongest, so they come up most. `blocks` is
the weakest of the eight, so it's rare — a scene you see occasionally is a treat; the same scene
every fourth launch is a chore.

---

## 3. The first launch is not random

A brand-new user's very first launch always plays **`tunnel`**. It's the one that makes people stop
and watch, and a first impression is too important to leave to a dice roll. Store
`splash.hasLaunched` and shuffle from launch #2 onward.

Corollary worth building: on the **second** launch, show a one-line toast in the launcher —
*"Startup animation changes every time you open Breeze."* Otherwise a good chunk of users will
assume the second one is a bug.

---

## 4. The shared contract

This is what makes eight scenes read as one product. Every scene **must** satisfy all of it. A new
scene that breaks any line here doesn't belong on the roster.

### Wind — one function, every scene

```js
const PERIOD = 3400, FX0 = -220, FSPAN = W + 420, SPEED = FSPAN / PERIOD;
const frontX   = t => FX0 + (t % PERIOD) / PERIOD * FSPAN;
const arriveAt = x => (x - FX0) / SPEED;

function bendAt(x, t) {
  const d = (x - frontX(t)) / 150;
  return 0.14 + 0.09 * Math.sin(t/1650 + x/240) + Math.exp(-d*d) * (d < 0 ? 1 : 0.55);
}
```

Because it's a function of *x*, the gust **travels**. Trees on the left bend before trees on the
right; the wordmark's letters arrive as the front reaches each one. Every scene reads this same
function for every moving thing in it.

### Geometry — 720 × 420, undecorated, transparent, radius 16

| | Splash | → launcher home |
|---|---|---|
| Badge | 92–96px, left | 40px at (12, 14), sidebar |
| Wordmark | 44–50px Archivo `wdth 125 / wght 700` | 19px at (90, 31), header |
| Rail | y ≈ 356, x 56, w 608 | y 386, full width, status bar |

### Type

Archivo (display, width axis at 125 for the wordmark) and IBM Plex Mono (utility). Both self-hosted —
**the splash must never wait on a network font.** Tracking on the wordmark is `0.115em` everywhere
except `tunnel` and `tunnelforest`, where it is `0.46em` and **must not be reduced** — that's the
minimum gap at which air can dive between the letters, and tightening it collapses the reveal.

### Progress — identical in all eight

| Stage | Copy | Target |
|---|---|---|
| 1 | Checking for updates | 20% |
| 2 | Verifying Java 21 | 46% |
| 3 | Restoring your session | 68% |
| 4 | Loading cosmetics | 92% |
| 5 | Ready | 100% |

**The bar caps at 92% until the app is genuinely ready.** A bar that hits 100% and then sits there is
the most common launcher lie and it is exactly what "doesn't get in your way" is arguing against.

Failure copy replaces the stage label — no modal, no apology:
`Can't reach breezeclient.net — retrying` · `Java 21 download failed — opening the launcher anyway`

### Duration

```
MIN_DWELL   5200ms      // burst: 7900ms — it's the slow one on purpose
MAX_DWELL  14000ms      // exit anyway; let the app show its own error state
EXIT         760ms
MORPH        900ms      // overlaps EXIT by 140ms
```

Every scene loops its ambient state indefinitely if the app isn't ready. **Nothing freezes** — the
ambient wind never drops to zero in any scene, because a still frame during a long cold start reads
as a crash.

### Hand-off

Identical everywhere: lockup blows downwind → canvas clears → the launcher comes up beneath → only in
the last 30% does the window itself dissolve. `main` is shown **before** `splash` closes, so there is
never a frame of bare desktop.

```rust
#[tauri::command]
async fn splash_done(app: tauri::AppHandle) {
    use tauri::Manager;
    if let Some(m) = app.get_webview_window("main")   { let _ = m.show(); let _ = m.set_focus(); }
    if let Some(s) = app.get_webview_window("splash") { let _ = s.close(); }
}
```

`daybreak` and `cloudsea` morph into a **light-themed** launcher chrome; the six night scenes morph
into the dark one. If the launcher has a theme setting, the splash should read it and the shuffle
should filter to matching scenes — a dark-theme user should never get a golden-hour splash.

---

## 5. Turning eight previews into one splash

Each file in this folder is a **preview**: the scene plus a toolbar plus explanatory notes. For
production, strip to the scene.

```
src/splash/
  index.html            // 720x420, two canvases (#scene, #ui), the .app skeleton
  shuffle.ts            // pickScene() from §2
  shared/
    wind.ts             // bendAt, frontX, arriveAt
    lockup.ts           // badge + wordmark + CLIENT + tagline, and the morph lerp
    rail.ts             // stages, honest progress, the 92% cap
    tokens.ts           // palette, type, geometry
  scenes/
    burst.ts  forest.ts  blocks.ts  island.ts
    tunnel.ts  tunnelforest.ts  daybreak.ts  cloudsea.ts
```

Every scene exports the same shape:

```ts
export interface Scene {
  id: string;
  weight: number;
  key: 'day' | 'night';
  minDwell: number;                       // burst overrides to 7900
  build(w: number, h: number): void;      // called once; allocate here, never in draw
  draw(ctx: CanvasRenderingContext2D, t: number, dt: number): void;
}
```

`lockup` and `rail` are drawn by the shell on `#ui`, not by the scene — that's what guarantees the
type and progress are pixel-identical across all eight, and it's what lets the shell blur `#scene`
during the morph while the type stays sharp.

**Budget per scene:** one atlas or zero, ≤ 2000 particles, nothing allocated inside `draw`, `dt`
clamped at 60ms. All assets inlined as base64 — no network request at startup, ever. Target under
120 KB per scene after stripping the preview chrome.

---

## 6. If you want a real player model

`v9-cape.html` had the right idea — a figure with a cloth-simulated cape in the same wind — and the
wrong execution, because I drew the figure in code and it came out with one arm and wrong
proportions. Hand-drawing Minecraft art has failed every time it's been tried in this project.

The correct path, and it's a genuinely good feature:

1. The launcher already authenticates the player, so it already has their UUID.
2. Fetch **their own skin** from the session API — licensed to them, no redistribution, and it makes
   the splash personal.
3. Render it as a proper player model (a small Three.js or hand-rolled cube renderer, 6 quads per
   box, standard 64×64 skin UV layout), not as hand-drawn rectangles.
4. Keep the Verlet cape from v9 — that part was correct: 6 × 11 points, 8 constraint passes, pinned
   along the shoulders, fold shading from quad area over rest area.
5. Same for the cape texture: the player's own, or the Breeze cape you sell.

Then it becomes `player`, weight 20, and it's the strongest scene on the roster — because it's the
only one that's about *them*.

Do not put it back on the roster until the model is real.

---

## 7. Do not

- Don't hand-draw Minecraft assets in code. Four attempts, four failures.
- Don't ship Mojang's texture PNGs in the installer — see `DESIGN-ARCHIVE.md` §Textures for the
  runtime-extraction path.
- Don't make the shuffle uniform, and don't let it repeat inside three launches.
- Don't randomise the very first launch.
- Don't let a scene diverge on type, palette, rail or timing. The variety is in the scene; the
  identity is in the contract.
- Don't tighten `WM.track` below `0.46em` in `tunnel` / `tunnelforest`.
- Don't let any scene freeze while loading.

---

## 8. Files

| | |
|---|---|
| `v1-wind-burst.html` … `v11-cloud-sea.html` | The eight roster scenes, plus the three not shipping. Self-contained, offline, real timings. Controls: Replay · speed · Slow network · Reduced motion. |
| `still-v1.png` … `still-v11.png` | One frame from each, for comparison. |
| `breeze-iso-atlas.png`, `breeze-block-atlas.png` | Tinted 16×16 atlases for `island` and `blocks`. |
| `make-atlas.py` | Regenerates both atlases. Needs Pillow. |
| `extract-textures.py` | Pulls the real textures out of a `client.jar` or resource pack on the player's own machine. |
| `DESIGN-ARCHIVE.md` | The full per-version history — every concept, what worked, and what broke. Read this before changing a scene. |

The blurred UI revealed at hand-off in every preview is a placeholder skeleton, not the real launcher.
