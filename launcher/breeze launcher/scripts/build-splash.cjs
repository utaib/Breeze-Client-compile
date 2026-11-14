#!/usr/bin/env node
/**
 * Turn the standalone startup-animation previews into launcher splash scenes.
 *
 * The files in breeze-startup-v12/ are design documents: each is a full page
 * with a headline, an explanation, replay/speed/reduced-motion controls and a
 * block of design notes wrapped around the actual 720x420 scene. They are also
 * the source of truth for the animations, so this rewrites them at build time
 * rather than anyone hand-porting twelve canvas renderers and letting the copies
 * drift from the originals.
 *
 * Three transforms, and each is needed:
 *
 *  1. Strip the Google Fonts <link>s. The launcher's CSP allows styles only from
 *     'self', so a remote stylesheet is blocked outright; worse, leaving it in
 *     makes the splash wait on a network round trip before first paint, on the
 *     one screen that must appear instantly.
 *  2. Hide the design chrome, keeping only .stage. Every scene shares the same
 *     harness markup, so one rule covers all of them.
 *  3. Neutralise the page background so the scene composites onto the launcher.
 *
 * Each scene keeps its own <script>, ids and requestAnimationFrame loop. They
 * all reuse ids like #cv, #win and #scaler, and none of them ever disconnect
 * their ResizeObserver. Mounting them in an iframe sidesteps both: the document
 * is isolated, and removing the element tears down the scene's globals, its
 * animation loop and its observers in one go.
 *
 * Run: node scripts/build-splash.cjs   (wired into npm run build)
 */
const fs = require('fs');
const path = require('path');

const root = path.join(__dirname, '..');
// The scene sources live in str/ at the repository root. They used to live in
// breeze-startup-v12/, and after the rename this script still pointed at the
// old name, so `npm run build` (and therefore every `tauri build` and every CI
// run) failed before reaching tsc. Both names are accepted so the build works
// whichever the checkout has.
const SOURCE_CANDIDATES = ['str', 'breeze-startup-v12'];
const source =
  SOURCE_CANDIDATES.map((name) => path.join(root, '..', name)).find((dir) => fs.existsSync(dir)) ||
  path.join(root, '..', SOURCE_CANDIDATES[0]);
const outDir = path.join(root, 'public', 'splash');

/**
 * The roster.
 *
 * DESIGN.md ships eight and cuts v5, v5b, v6 and v9. Niv asked for every scene
 * in the folder, so all twelve are here; the four the document cut carry lower
 * weights, which keeps them rare rather than absent.
 *
 * `key` drives time-of-day weighting: day scenes are likelier in daylight hours
 * and night scenes after dark.
 */
const ROSTER = [
  { id: 'burst',        file: 'v1-wind-burst.html',           weight: 12, key: 'night' },
  { id: 'forest',       file: 'v2-silhouette-forest.html',    weight: 16, key: 'night' },
  { id: 'blocks',       file: 'v3-block-forest.html',         weight: 7,  key: 'night' },
  { id: 'island',       file: 'v4-isometric-island.html',     weight: 14, key: 'night' },
  { id: 'finalisland',  file: 'v5-final-island.html',         weight: 5,  key: 'night' },
  { id: 'textures',     file: 'v5b-swappable-textures.html',  weight: 4,  key: 'night' },
  { id: 'threestyles',  file: 'v6-three-styles.html',         weight: 5,  key: 'night' },
  { id: 'tunnel',       file: 'v7-wind-tunnel.html',          weight: 16, key: 'night' },
  { id: 'tunnelforest', file: 'v8-wind-tunnel-forest.html',   weight: 14, key: 'night' },
  { id: 'cape',         file: 'v9-cape.html',                 weight: 5,  key: 'night' },
  { id: 'daybreak',     file: 'v10-daybreak.html',            weight: 14, key: 'day'   },
  { id: 'cloudsea',     file: 'v11-cloud-sea.html',           weight: 14, key: 'day'   },
];

const OVERLAY = `
<style id="breeze-splash-fit">
  /* Everything except the scene itself is design-document chrome. */
  html, body {
    margin: 0; padding: 0; width: 100%; height: 100%;
    background: transparent !important; overflow: hidden;
  }
  body > *:not(.stage) { display: none !important; }
  /* max-width matters as much as width here: the design pages cap .stage at
     760px, and a max-width beats a plain width, so without clearing it the
     scene stayed in a 760px column and drew off-centre. */
  .stage {
    position: fixed !important; inset: 0 !important;
    margin: 0 !important; padding: 0 !important; border: 0 !important;
    background: transparent !important; box-shadow: none !important;
    width: 100vw !important; height: 100vh !important;
    max-width: none !important; max-height: none !important;
    min-width: 0 !important; min-height: 0 !important;
    display: flex !important; align-items: center !important; justify-content: center !important;
    overflow: hidden !important;
  }
  /* Full bleed. The scene's own layout() sets an inline height on .scaler from
     its computed scale, so these need !important to win, otherwise the stage
     collapses back to the 720x420 design box. */
  .scaler {
    width: 100vw !important; height: 100vh !important;
    max-width: none !important; max-height: none !important;
    margin: 0 !important; padding: 0 !important;
    display: flex !important; align-items: center !important; justify-content: center !important;
  }
  /* .win carries the scene's own transform: scale(S), so it must not also be
     offset by a margin or the cover-scaled canvas lands off centre.
     The border, radius and shadow are the design pages dressing .win up to look
     like an application window on a documentation page. Full screen there is no
     window to frame, and the border showed as a hairline seam down the left
     edge because it shifts the canvas a pixel. */
  .win {
    margin: 0 !important; max-width: none !important;
    border: 0 !important; border-radius: 0 !important;
    box-shadow: none !important; outline: 0 !important;
  }
  .win > canvas, .stage canvas { border: 0 !important; border-radius: 0 !important; display: block !important; }
  /* The previews load Archivo from Google Fonts, which the CSP blocks. Fall
     back to the same stack the launcher itself uses so the wordmark still sets
     in a humanist sans rather than in Times. */
  * { font-family: 'DM Sans', -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif !important; }
</style>
<script id="breeze-splash-bridge">
/* The launcher and the scene agree on the hand-off here.
 *
 * The scene holds its ambient loop, with the rail stopped at its last stage,
 * until the launcher says it is ready. It then plays its own exit and reports
 * when that exit starts and when it has finished, instead of replaying itself.
 * The launcher uses those two messages to dissolve the window over the tail of
 * the exit, onto a UI that has already loaded underneath.
 *
 * Opened on its own (not inside the launcher) a scene treats itself as ready. */
(function () {
  var ready = window.parent === window;
  var exiting = false;
  var exited = false;
  function post(event, detail) {
    if (window.parent === window) return;
    try { window.parent.postMessage({ type: 'breeze-splash', event: event, detail: detail || null }, '*'); } catch (e) {}
  }
  window.__breezeReady = function () { return ready; };
  window.__breezeExiting = function (sceneMs) {
    if (exiting) return;
    exiting = true;
    var ms = sceneMs;
    if (typeof ms !== 'number') {
      ms = typeof EXIT !== 'undefined' ? EXIT : typeof EXIT_DUR !== 'undefined' ? EXIT_DUR : 600;
      if (typeof MORPH !== 'undefined') ms += MORPH - 140;
    }
    var rate = typeof speed === 'number' && speed > 0 ? speed : 1;
    post('exiting', { ms: Math.round(ms / rate) });
  };
  window.__breezeExited = function () {
    if (exited) return;
    exited = true;
    post('exited');
  };
  window.addEventListener('message', function (e) {
    if (e.source !== window.parent || !e.data || e.data.type !== 'breeze-splash') return;
    if (e.data.event === 'ready') ready = true;
  });
  post('loaded');
})();
</script>
`;

const APP_VERSION = JSON.parse(
  fs.readFileSync(path.join(root, 'package.json'), 'utf8'),
).version;

function transform(html) {
  let out = html;

  // 0. The previews paint a hardcoded "v2.4.0" onto the splash rail; it is a
  //    placeholder from the design mockups and appears in all twelve. Shipping
  //    it would print a version that has never existed on the first screen every
  //    user sees. Sourced from package.json, so it tracks the real build.
  //
  //    Two forms across the twelve: a JS string the canvas renderers measure and
  //    draw, and DOM text in the ones that lay the rail out in HTML. Replacing
  //    only the quoted form silently missed seven of them.
  out = out.replace(/(['"])v\d+\.\d+\.\d+\1/g, `$1v${APP_VERSION}$1`);
  out = out.replace(/(>\s*)v\d+\.\d+\.\d+(\s*<)/g, `$1v${APP_VERSION}$2`);

  // 1. Remote font stylesheets and their preconnects.
  out = out.replace(/<link[^>]*fonts\.(googleapis|gstatic)\.com[^>]*>\s*/gi, '');

  // 1a. Make the scene fill the window instead of sitting in a 720x420 box.
  //
  //     Every scene computes `S = Math.min(1, w/W)`. The cap at 1 is right for a
  //     design preview embedded in a page, and wrong for a splash: it is exactly
  //     why the animation rendered as a small rectangle floating in the middle
  //     of the launcher. Switching to the larger of the two axis ratios makes it
  //     cover, so the scene bleeds off whichever edge is proportionally shorter
  //     rather than letterboxing.
  const before = out;
  out = out.replace(/S=Math\.min\(1,w\/W\)/g, 'S=Math.max(innerWidth/W,innerHeight/H)');
  if (out === before) {
    // Loud rather than silently shipping a boxed splash.
    throw new Error('scale-cap pattern not found; the scene layout changed shape');
  }

  // 1a-ii. Centre the cover-scaled scene.
  //
  //     The design pages set transform-origin to the top left corner, which is
  //     correct when the scale only ever shrinks: the scene stays pinned to the
  //     corner of its box. Now that it scales up past the viewport, the same
  //     origin makes it grow down and to the right, so it sat flush to the top
  //     left and hung off the bottom edge. Prepending a translate re-centres it:
  //     whichever axis overflows now overflows equally on both sides.
  //
  //     Nine scenes scale an element called `win`, three call it `frame`; the
  //     expression is otherwise identical in all twelve.
  const CENTRE = "translate('+((innerWidth-W*S)/2)+'px,'+((innerHeight-H*S)/2)+'px) scale('+S+')'";
  const scaledBefore = out;
  out = out.replace(/(win|frame)\.style\.transform='scale\('\+S\+'\)'/g, `$1.style.transform='${CENTRE}`);
  if (out === scaledBefore) {
    throw new Error('stage transform pattern not found; the scene layout changed shape');
  }

  // 1a-iii. Shrink the composition without letterboxing it.
  //
  //     Scaling a 720x420 design to cover a 1080p window means everything is
  //     drawn at roughly 2.6x: a 92px badge becomes 245px and a 48px wordmark
  //     becomes 128px. It fills the screen but reads like Minecraft with the GUI
  //     scale cranked up.
  //
  //     Simply reducing the scale would letterbox it, because the canvas would
  //     stop covering the window. Instead the canvas keeps covering and only the
  //     drawing transform shrinks, with the composition re-centred; the
  //     background fills then have to be widened to the new logical viewport or
  //     they would leave the outer band unpainted. Both are uniform across all
  //     twelve scenes, so this is mechanical rather than per-scene work.
  //
  //     BGX/BGY/BGW/BGH are constants, not functions of S: the canvas is W*S
  //     wide and content is drawn at S*K, so the visible logical width is
  //     W*S/(S*K) = W/K, and S cancels out entirely.
  out = out.replace(
    /const W=720,H=420/g,
    'const W=720,H=420,K=1,' +
    'BGX=-W*(1-K)/(2*K),BGY=-H*(1-K)/(2*K),BGW=W/K,BGH=H/K',
  );

  const shrankBefore = out;
  out = out.replace(
    /([a-z]+)\.setTransform\(S\*dpr,0,0,S\*dpr,0,0\)/g,
    '$1.setTransform(S*K*dpr,0,0,S*K*dpr,W*S*(1-K)/2*dpr,H*S*(1-K)/2*dpr)',
  );
  if (out === shrankBefore) {
    throw new Error('canvas transform pattern not found; the scene layout changed shape');
  }

  // Every full-canvas fill now has to cover the enlarged logical viewport.
  out = out.replace(/fillRect\(0,0,W,H\)/g, 'fillRect(BGX,BGY,BGW,BGH)');

  // 1c. Slow the animation down.
  //
  //     Every scene advances its clock with dt=(ts-last)*speed and defaults
  //     speed to 1. At that rate the wordmark and status text arrive and leave
  //     faster than they can be read, which is why the animation felt like a
  //     flash rather than a sequence.
  out = out.replace(/speed=1,/g, 'speed=0.6,');

  // 1b. Covering the window means the canvas is now the size of the display, so
  //     a 2.5x or 3x device pixel ratio on top of that is a very large surface to
  //     repaint every frame on the integrated GPUs this launcher targets. 1.5 is
  //     still past the point where the edges read as soft.
  out = out.replace(/dpr=Math\.min\(window\.devicePixelRatio\|\|1,(?:2\.5|3)\)/g,
    'dpr=Math.min(window.devicePixelRatio||1,1.5)');

  // 2 and 3. Injected last in <head> so it wins over the page's own rules
  // without needing to understand them.
  if (out.includes('</head>')) {
    out = out.replace('</head>', OVERLAY + '</head>');
  } else {
    out = OVERLAY + out;
  }

  return out;
}

/**
 * Hand the scene's timing to the launcher.
 *
 * The previews run a fixed demo: dwell, exit, replay forever, with a "Slow
 * network" toggle that only delays the exit. Inside the launcher that meant the
 * launcher's own timer cut each scene off before its exit ever played (the
 * scenes run at 0.6x, so a 5.6s dwell is 9.3s of real time), and a slow session
 * restore showed the scene replaying from the start.
 *
 * Each rewrite is counted, and a scene that no longer matches fails the build
 * rather than shipping a splash that never hands off.
 */
function handOff(id, html) {
  let out = html;
  const count = {};
  function sub(label, pattern, replacement) {
    let n = 0;
    out = out.replace(pattern, (...args) => {
      n += 1;
      return typeof replacement === 'function' ? replacement(...args) : replacement.replace(/\$(\d)/g, (_, i) => args[Number(i)] ?? '');
    });
    count[label] = (count[label] || 0) + n;
  }

  // The rail's stage labels are shown to the player, so they have to be true.
  // The launcher does not verify Java while starting; it does load settings.
  sub('labels', /Verifying Java 21/g, 'Loading your settings');

  if (id === 'burst') {
    // v1 freezes its timeline just before the hand-off while holding.
    sub('hold', /holding&&t>T\.rail\+520\*TIME_SCALE&&now<holdUntil/g, '!__breezeReady()&&t>T.rail+520*TIME_SCALE');
    sub('exit', /if\(t>T\.end\+900\*TIME_SCALE\)\{ ?start\(\); ?return; ?\}/g,
      'if(t>=T.exit)__breezeExiting((T.end-T.exit));if(t>T.end+900*TIME_SCALE){ __breezeExited(); return; }');
  } else if (id === 'threestyles') {
    sub('hold', /holding\s*\?\s*t\s*>=\s*\d+\s*:\s*t\s*>=\s*MIN_DWELL/g, 't>=MIN_DWELL&&__breezeReady()');
    sub('rail', /setStage\(t,holding&&t<\d+\)/g, 'setStage(t,!__breezeReady())');
    sub('exit', /done=true;morph\(\);setTimeout\(start,(\d+)\)/g, 'done=true;morph();__breezeExiting(900);setTimeout(__breezeExited,$1)');
  } else {
    sub('hold', /holding\s*\?\s*t\s*>=\s*\d+\s*:\s*t\s*>=\s*MIN_DWELL/g, 't>=MIN_DWELL&&__breezeReady()');
    sub('rail', /(setStage|rail)\(t,holding&&t<\d+\)/g, '$1(t,!__breezeReady())');
    sub('exiting', /exitAt=t;/g, 'exitAt=t,__breezeExiting();');
    sub('exit', /(t>exitAt\+[A-Z_]+(?:\+MORPH)?\+\d+\)\{\s*)start\(\);\s*return;?(\s*\})/g, '$1__breezeExited();return;$2');
  }

  const required = id === 'burst' ? ['hold', 'exit'] : id === 'threestyles' ? ['hold', 'rail', 'exit'] : ['hold', 'rail', 'exiting', 'exit'];
  const missing = required.filter((label) => !count[label]);
  if (missing.length) {
    throw new Error(`build-splash: ${id} no longer matches the hand-off rewrite (${missing.join(', ')}); the scene's loop changed shape`);
  }
  if (out.includes('start();return') && id !== 'burst' && id !== 'threestyles' && /t>exitAt\+/.test(out) && /\{\s*start\(\);\s*return/.test(out)) {
    throw new Error(`build-splash: ${id} still replays itself after its exit`);
  }
  return out;
}

function main() {
  if (!fs.existsSync(source)) {
    console.error(`build-splash: source folder not found. Looked for: ${SOURCE_CANDIDATES.join(', ')}`);
    console.error('build-splash: the splash scenes cannot be built without it.');
    process.exit(1);
  }

  fs.mkdirSync(outDir, { recursive: true });

  const built = [];
  const missing = [];

  for (const scene of ROSTER) {
    const from = path.join(source, scene.file);
    if (!fs.existsSync(from)) {
      missing.push(scene.file);
      continue;
    }
    const html = fs.readFileSync(from, 'utf8');
    fs.writeFileSync(path.join(outDir, `${scene.id}.html`), handOff(scene.id, transform(html)));
    built.push(scene);
  }

  // The manifest the launcher reads. Emitted rather than hardcoded in the React
  // component so a scene that failed to build can never be picked at runtime,
  // which would show the user an empty splash.
  fs.writeFileSync(
    path.join(outDir, 'scenes.json'),
    JSON.stringify(built.map(({ id, weight, key }) => ({ id, weight, key })), null, 2) + '\n',
  );

  console.log(`build-splash: ${built.length} scenes -> public/splash/`);
  if (missing.length) {
    // Loud, not silent. A missing scene means the shuffle is smaller than
    // intended and that should not be discovered by a user noticing repeats.
    console.warn(`build-splash: MISSING ${missing.length}: ${missing.join(', ')}`);
  }
}

main();
