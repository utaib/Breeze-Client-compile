import { useCallback, useEffect, useRef, useState } from "react";
import * as skinview3d from "skinview3d";
import { CosmeticRig } from "../cosmetics/rig";

// 3D player rendering, ported from the stable launcher's working implementation.
// Renders the player's actual skin with a cape (static, HD, or animated frame
// sequences from GIF uploads) using skinview3d. Two components:
//   <PlayerViewer3D>: full-size interactive viewer (store modal, play page, style page)
//   <CapeCardViewer>: small non-interactive card preview, lazy-initialised on scroll

const SKIN_SOURCES = [
  (uuid) => `https://mc-heads.net/skin/${uuid}`,
  (uuid) => `https://visage.surgeplay.com/skin/${uuid}.png`,
  (uuid) => `https://crafatar.com/skins/${uuid}`,
  (uuid) => `https://minotar.net/skin/${uuid}`,
];

function normalizeUuid(uuid) {
  return String(uuid || "").replace(/-/g, "").trim();
}

function skinCandidates(uuidOrUrl) {
  const input = String(uuidOrUrl || "").trim();
  if (!input) return [];
  if (/^https?:\/\//i.test(input)) return [input];
  const normalized = normalizeUuid(input);
  if (!normalized) return [];
  return SKIN_SOURCES.map((makeSrc) => makeSrc(normalized));
}

function testImageUrl(url, timeoutMs = 7000) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    const t = setTimeout(() => {
      img.src = "";
      reject(new Error("timeout"));
    }, timeoutMs);
    img.onload = () => {
      clearTimeout(t);
      resolve(url);
    };
    img.onerror = () => {
      clearTimeout(t);
      reject(new Error("load failed"));
    };
    img.src = url;
  });
}

/**
 * A plain grey stand-in skin, drawn once. skinview3d hides the whole skin, and
 * so every cosmetic worn on the head, body or arm, until a skin loads; with no
 * skin at all (offline, or every mirror down) a worn cosmetic would never show.
 */
let standIn = null;
function standInSkin() {
  if (standIn) return standIn;
  const canvas = document.createElement("canvas");
  canvas.width = 64;
  canvas.height = 64;
  const g = canvas.getContext("2d");
  g.fillStyle = "#8b919c";
  g.fillRect(0, 0, 64, 64);
  // A slightly darker face so the head reads the right way round.
  g.fillStyle = "#6f7580";
  g.fillRect(8, 8, 8, 8);
  standIn = canvas;
  return standIn;
}

/** Resolve a usable skin texture URL. The viewer also retries every mirror
 * when skinview3d itself rejects a URL (for example because of CORS), so an
 * image that merely passes the browser preload test can never force Alex as
 * the final result. */
export async function resolveSkinUrl(uuid) {
  for (const url of skinCandidates(uuid)) {
    try {
      await testImageUrl(url);
      return url;
    } catch {
      // Try the next skin provider.
    }
  }
  return null;
}

// Skin textures stay crisp (nearest-neighbour, authentic Minecraft pixels).
// Capes pick their filter from their own resolution: a standard 64x32 cape is
// pixel art and turns into a blurry smear under linear magnification, while a
// genuine HD cape (256px+) needs linear + mipmaps + anisotropy so it renders
// smoothly instead of shimmering. Filtering everything linearly was making
// every ordinary cape look soft in the store.
const HD_CAPE_MIN_WIDTH = 128;

// three.js filter constants, inlined so this file does not import three itself.
const NEAREST = 1003;
const NEAREST_MIPMAP_LINEAR = 1005;
const LINEAR = 1006;
const LINEAR_MIPMAP_LINEAR = 1008;
const CLAMP_TO_EDGE = 1001;

/**
 * skinview3d 3.4.1 puts the cape texture on `playerObject.cape.material.map`.
 * There is no `capeMap` property, so the previous version's cape branch never
 * ran and the generic map loop below it treated the cape exactly like the skin:
 * nearest filtering with mipmaps disabled.
 *
 * That is right for a 64x32 pixel-art cape but wrong for an HD one. A 256x128
 * cape drawn at preview size is minified hard, and nearest minification without
 * mipmaps aliases badly, which is the shimmering, broken look on HD capes.
 *
 * Skin stays nearest always (authentic Minecraft pixels). Capes choose by their
 * own resolution, and always get mipmaps so minification is stable.
 */
function tuneTexture(tex, { pixelArt, maxAniso }) {
  if (!tex) return;
  tex.magFilter = pixelArt ? NEAREST : LINEAR;
  tex.minFilter = pixelArt ? NEAREST_MIPMAP_LINEAR : LINEAR_MIPMAP_LINEAR;
  tex.generateMipmaps = true;
  tex.anisotropy = maxAniso;
  tex.wrapS = CLAMP_TO_EDGE;
  tex.wrapT = CLAMP_TO_EDGE;
  tex.needsUpdate = true;
}

function applyLinearFiltering(viewer) {
  const player = viewer?.playerObject;
  if (!player) return;
  let maxAniso = 1;
  try { maxAniso = viewer.renderer?.capabilities?.getMaxAnisotropy?.() || 1; } catch { /* default 1 */ }

  try {
    // Cape and elytra share the cape texture.
    for (const part of [player.cape, player.elytra]) {
      const tex = part?.material?.map;
      if (!tex) continue;
      const width = tex.image?.naturalWidth || tex.image?.width || 0;
      tuneTexture(tex, { pixelArt: width > 0 && width < HD_CAPE_MIN_WIDTH, maxAniso });
      part.material.needsUpdate = true;
    }

    // The player skin is always pixel art, and must never be smoothed. Worn
    // cosmetics hang off the same parts but keep their own filtering, and are
    // not re-uploaded every time this runs (every frame of an animated cape).
    const walk = (obj) => {
      if (obj.userData?.breezeCosmetic) return;
      const mats = obj.material ? (Array.isArray(obj.material) ? obj.material : [obj.material]) : [];
      for (const mat of mats) {
        const tex = mat.map;
        if (!tex) continue;
        tex.magFilter = NEAREST;
        tex.minFilter = NEAREST;
        tex.generateMipmaps = false;
        tex.needsUpdate = true;
        mat.needsUpdate = true;
      }
      for (const child of obj.children || []) walk(child);
    };
    if (player.skin) walk(player.skin);
  } catch { /* filtering is cosmetic, never break rendering over it */ }
}

// Decoded animation frames, shared across every viewer on the page. Animated
// capes used to re-fetch each frame's URL on every tick, so an 8fps 33-frame
// cape fired 8 network requests a second and frames landed late and out of
// order, which is what made playback stutter. Frames are now fetched once,
// decoded, and cached; playback is a pure in-memory swap.
const frameCache = new Map();

function preloadFrames(urls) {
  const key = urls.join("\u0000");
  const hit = frameCache.get(key);
  if (hit) return hit;
  const job = Promise.all(
    urls.map(
      (url) =>
        new Promise((resolve) => {
          const img = new Image();
          img.crossOrigin = "anonymous";
          img.onload = () => resolve(img);
          // A failed frame resolves null and is skipped, so one bad frame
          // cannot stall the whole animation.
          img.onerror = () => resolve(null);
          img.src = url;
        }),
    ),
  ).then((imgs) => imgs.filter(Boolean));
  frameCache.set(key, job);
  return job;
}

/** Full interactive player model: drag to rotate, scroll to zoom, walking
 *  animation, cape/elytra toggle, animated cape frame playback. */
export function PlayerViewer3D({
  skinUrl, capeUrl, animationFrames, animationFps,
  width = 220, height = 440,
  interactive = true, showElytraToggle = true,
  className = "",
  /**
   * 3D cosmetics worn by the player: [{ key, cosmetic, url, attachment?, transform? }].
   * attachment and transform override the cosmetic's own, for live placement
   * previews. See src/cosmetics/rig.js.
   */
  cosmetics = null,
  onCosmeticError,
}) {
  const canvasRef = useRef(null);
  const viewerRef = useRef(null);
  const rigRef = useRef(null);
  const cosmeticErrorRef = useRef(onCosmeticError);
  cosmeticErrorRef.current = onCosmeticError;
  const frameIntRef = useRef(null);
  const frameIdxRef = useRef(0);
  const initializedRef = useRef(false);
  const [showElytra, setShowElytra] = useState(false);
  const showElytraRef = useRef(false);
  showElytraRef.current = showElytra;

  const hasFrames = Array.isArray(animationFrames) && animationFrames.length > 1;
  const hasCape = Boolean(capeUrl || hasFrames);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || typeof skinview3d.SkinViewer !== "function") return;
    if (initializedRef.current) return;
    initializedRef.current = true;

    const dpr = Math.max(window.devicePixelRatio || 1, 2);
    let viewer;
    try {
      viewer = new skinview3d.SkinViewer({ canvas, width, height, alpha: true });
    } catch (err) {
      console.warn("[SkinViewer] Init failed:", err);
      initializedRef.current = false;
      return;
    }

    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;
    try { viewer.renderer?.setPixelRatio(dpr); } catch {}
    try { viewer.fov = 70; } catch {}
    try {
      viewer.camera?.position.set(0, 0, 40);
      viewer.camera?.lookAt(0, 0, 0);
    } catch {}
    try {
      if (viewer.controls) {
        viewer.controls.enableZoom = interactive;
        viewer.controls.enablePan = false;
        viewer.controls.enableRotate = interactive;
        viewer.controls.rotateSpeed = 1.2;
        try { viewer.controls.minDistance = 15; } catch {}
        try { viewer.controls.maxDistance = 100; } catch {}
      }
    } catch {}

    viewer.autoRotate = true;
    viewer.autoRotateSpeed = 0.6;
    try { viewer.globalLight = 1.6; } catch {}
    try { viewer.cameraLight = 0.8; } catch {}
    // Cosmetics tick inside the viewer's own frame loop, so they pause with
    // it when the canvas is off screen or the window is hidden.
    const rig = new CosmeticRig(viewer, { onError: (key, err) => cosmeticErrorRef.current?.(key, err) });
    rigRef.current = rig;
    // Development builds only: lets a test page measure what the rig built.
    if (import.meta.env.DEV) canvas.__breezeRig = rig;
    try {
      const walk = skinview3d.WalkingAnimation ? new skinview3d.WalkingAnimation() : null;
      if (walk) walk.speed = 0.8;
      viewer.animation = rig.wrapAnimation(walk);
    } catch {}

    viewerRef.current = viewer;
    applyLinearFiltering(viewer);

    // A viewer left running renders at full rate behind a scrolled page. There
    // is one on the play page, one in gifts and one in the wardrobe, each with
    // its own WebGL context, so this is the difference between one canvas
    // drawing and three.
    const setPaused = (paused) => { try { if (viewerRef.current) viewerRef.current.renderPaused = paused; } catch {} };
    const visibility = () => setPaused(document.hidden);
    document.addEventListener("visibilitychange", visibility);
    let observer = null;
    if (typeof IntersectionObserver === "function") {
      observer = new IntersectionObserver(([entry]) => setPaused(!entry.isIntersecting || document.hidden), { threshold: 0.01 });
      observer.observe(canvas);
    }

    const stopRotate = () => { try { if (viewerRef.current) viewerRef.current.autoRotate = false; } catch {} };
    const resumeRotate = () => { setTimeout(() => { try { if (viewerRef.current) viewerRef.current.autoRotate = true; } catch {} }, 2000); };
    if (interactive) {
      canvas.addEventListener("mousedown", stopRotate);
      canvas.addEventListener("mouseup", resumeRotate);
      canvas.addEventListener("touchstart", stopRotate, { passive: true });
      canvas.addEventListener("touchend", resumeRotate);
    }

    return () => {
      if (interactive) {
        canvas.removeEventListener("mousedown", stopRotate);
        canvas.removeEventListener("mouseup", resumeRotate);
        canvas.removeEventListener("touchstart", stopRotate);
        canvas.removeEventListener("touchend", resumeRotate);
      }
      document.removeEventListener("visibilitychange", visibility);
      observer?.disconnect();
      if (frameIntRef.current) { clearInterval(frameIntRef.current); frameIntRef.current = null; }
      try { rig.dispose(); } catch {}
      rigRef.current = null;
      try { viewer.dispose(); } catch {}
      viewerRef.current = null;
      initializedRef.current = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Cheap to run every render: the rig only loads, removes or restyles what changed.
  useEffect(() => {
    rigRef.current?.sync(cosmetics || []);
  });

  useEffect(() => {
    const v = viewerRef.current;
    if (!v) return;

    let cancelled = false;
    const candidates = skinCandidates(skinUrl);

    if (!candidates.length) {
      try { v.loadSkin(standInSkin()); } catch {}
      return () => { cancelled = true; };
    }

    (async () => {
      for (const url of candidates) {
        if (cancelled) return;
        try {
          await v.loadSkin(url);
          if (!cancelled) applyLinearFiltering(v);
          return;
        } catch (error) {
          console.warn("[SkinViewer] Skin source failed:", url, error);
        }
      }
      if (!cancelled) {
        console.error("[SkinViewer] All skin sources failed; showing a stand-in skin.");
        try { v.loadSkin(standInSkin()); } catch {}
      }
    })();

    return () => { cancelled = true; };
  }, [skinUrl]);

  useEffect(() => {
    const v = viewerRef.current;
    if (!v) return;
    if (frameIntRef.current) { clearInterval(frameIntRef.current); frameIntRef.current = null; }

    const loadWithMode = (vv, url) => {
      if (!url) { try { vv.loadCape(null); } catch {} return; }
      const opts = { backEquipment: showElytraRef.current ? "elytra" : "cape" };
      // loadCape returns a Promise for a URL string but plain undefined
      // for an already-decoded image, so .then() must not be assumed. Calling
      // it blindly threw on every animation tick and killed playback.
      try {
        const done = vv.loadCape(url, opts);
        if (done && typeof done.then === 'function') done.then(() => applyLinearFiltering(vv)).catch(() => {});
        else applyLinearFiltering(vv);
      } catch {}
    };

    if (hasFrames) {
      frameIdxRef.current = 0;
      const fps = animationFps && animationFps > 0 ? Math.min(animationFps, 30) : 8;
      let cancelled = false;
      // Show frame 0 immediately so the cape is never blank while decoding,
      // then start playback once every frame is in memory.
      loadWithMode(v, animationFrames[0]);
      preloadFrames(animationFrames).then((imgs) => {
        if (cancelled || imgs.length < 2 || !viewerRef.current) return;
        if (frameIntRef.current) clearInterval(frameIntRef.current);
        frameIntRef.current = setInterval(() => {
          const vv = viewerRef.current;
          if (!vv) return;
          frameIdxRef.current = (frameIdxRef.current + 1) % imgs.length;
          loadWithMode(vv, imgs[frameIdxRef.current]);
        }, 1000 / fps);
      });
      return () => {
        cancelled = true;
        if (frameIntRef.current) { clearInterval(frameIntRef.current); frameIntRef.current = null; }
      };
    } else if (capeUrl) {
      loadWithMode(v, capeUrl);
    } else {
      try { v.loadCape(null); } catch {}
    }

    return () => { if (frameIntRef.current) { clearInterval(frameIntRef.current); frameIntRef.current = null; } };
  }, [capeUrl, animationFrames, animationFps, showElytra, hasFrames]);

  return (
    <div className={`skin-viewer-wrap ${className}`} style={{ width }}>
      <canvas ref={canvasRef} className="skin-viewer-canvas" style={{ width, height }} />
      {showElytraToggle && hasCape && (
        <button className="skin-viewer-elytra-btn" onClick={() => setShowElytra((v) => !v)}>
          {showElytra ? "View as cape" : "View as elytra"}
        </button>
      )}
    </div>
  );
}

/** Small auto-rotating player preview for store cards. Initialises lazily when
 *  scrolled into view so a grid of cards stays cheap. */
export function CapeCardViewer({ skinUrl, capeUrl, animationFrames, animationFps, width = 120, height = 160 }) {
  const wrapRef = useRef(null);
  const canvasRef = useRef(null);
  const viewerRef = useRef(null);
  const frameIntRef = useRef(null);
  const frameIdxRef = useRef(0);
  const initializedRef = useRef(false);

  const loadCapeInto = useCallback((v) => {
    if (!v) return;
    if (frameIntRef.current) { clearInterval(frameIntRef.current); frameIntRef.current = null; }
    const frames = animationFrames;
    const doLoad = (vv, url) => {
      if (!url) { try { vv.loadCape(null); } catch {} return; }
      // Same dual return shape as the full viewer: URL gives a Promise,
      // decoded image gives undefined.
      try {
        const done = vv.loadCape(url, { backEquipment: 'cape' });
        if (done && typeof done.then === 'function') done.then(() => applyLinearFiltering(vv)).catch(() => {});
        else applyLinearFiltering(vv);
      } catch {}
    };
    if (Array.isArray(frames) && frames.length > 1) {
      frameIdxRef.current = 0;
      const fps = animationFps && animationFps > 0 ? Math.min(animationFps, 30) : 8;
      doLoad(v, frames[0]);
      // Frames are cached across cards, so a grid of animated capes decodes
      // each unique frame set once instead of re-fetching per tick.
      preloadFrames(frames).then((imgs) => {
        if (imgs.length < 2 || !viewerRef.current) return;
        if (frameIntRef.current) clearInterval(frameIntRef.current);
        frameIntRef.current = setInterval(() => {
          const vv = viewerRef.current;
          if (!vv) return;
          frameIdxRef.current = (frameIdxRef.current + 1) % imgs.length;
          doLoad(vv, imgs[frameIdxRef.current]);
        }, 1000 / fps);
      });
    } else if (capeUrl) {
      doLoad(v, capeUrl);
    } else {
      try { v.loadCape(null); } catch {}
    }
  }, [capeUrl, animationFrames, animationFps]);

  const initViewer = useCallback(() => {
    const canvas = canvasRef.current;
    if (!canvas || typeof skinview3d.SkinViewer !== "function") return;
    if (initializedRef.current) return;
    initializedRef.current = true;

    const dpr = Math.max(window.devicePixelRatio || 1, 2);
    let viewer;
    try {
      viewer = new skinview3d.SkinViewer({ canvas, width, height, alpha: true });
    } catch { initializedRef.current = false; return; }

    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;
    try { viewer.renderer?.setPixelRatio(dpr); } catch {}
    try { viewer.fov = 50; } catch {}
    try { viewer.camera?.position.set(-6, 10, 45); viewer.camera?.lookAt(0, 10, 0); } catch {}
    try {
      if (viewer.controls) {
        viewer.controls.enableZoom = false;
        viewer.controls.enablePan = false;
        viewer.controls.enableRotate = false;
      }
    } catch {}

    viewer.autoRotate = true;
    viewer.autoRotateSpeed = 1.2;
    try { viewer.globalLight = 1.8; } catch {}
    try { viewer.cameraLight = 0.6; } catch {}
    try {
      if (skinview3d.WalkingAnimation) {
        const walk = new skinview3d.WalkingAnimation();
        walk.speed = 0.8;
        viewer.animation = walk;
      }
    } catch {}

    viewerRef.current = viewer;
    applyLinearFiltering(viewer);
    if (skinUrl) try { viewer.loadSkin(skinUrl).then(() => applyLinearFiltering(viewer)).catch(() => {}); } catch {}
    loadCapeInto(viewer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    const wrap = wrapRef.current;
    if (!wrap) return;
    const obs = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting) { initViewer(); obs.disconnect(); }
    }, { threshold: 0.1 });
    obs.observe(wrap);
    return () => obs.disconnect();
  }, [initViewer]);

  useEffect(() => () => {
    if (frameIntRef.current) { clearInterval(frameIntRef.current); frameIntRef.current = null; }
    try { viewerRef.current?.dispose(); } catch {}
    viewerRef.current = null;
    initializedRef.current = false;
  }, []);

  useEffect(() => {
    const v = viewerRef.current;
    if (!v) return;

    let cancelled = false;
    const candidates = skinCandidates(skinUrl);

    if (!candidates.length) {
      try { v.loadSkin(null); } catch {}
      return () => { cancelled = true; };
    }

    (async () => {
      for (const url of candidates) {
        if (cancelled) return;
        try {
          await v.loadSkin(url);
          if (!cancelled) applyLinearFiltering(v);
          return;
        } catch (error) {
          console.warn("[SkinViewer] Skin source failed:", url, error);
        }
      }
      if (!cancelled) {
        console.error("[SkinViewer] All skin sources failed; no custom skin was loaded.");
      }
    })();

    return () => { cancelled = true; };
  }, [skinUrl]);

  useEffect(() => {
    const v = viewerRef.current;
    if (!v) return;
    loadCapeInto(v);
  }, [loadCapeInto]);

  return (
    <div ref={wrapRef} className="cape-card-viewer-wrap" style={{ width, height }}>
      <canvas ref={canvasRef} className="cape-card-viewer-canvas" style={{ width, height }} />
    </div>
  );
}
