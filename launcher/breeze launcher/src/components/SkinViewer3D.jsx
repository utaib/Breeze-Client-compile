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
  // A full address, or one of the launcher's own files (the default skin in
  // public/skins) or an in-memory image.
  if (/^(https?:\/\/|\/(?!\/)|data:image\/|blob:)/i.test(input)) return [input];
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

// Stage framing (mode="stage"): field of view, how much of the canvas the
// player fills, the camera a little below the middle looking a little up.
const STAGE_FOV = 30;
const STAGE_ZOOM = 0.84;
const STAGE_CAMERA_Y = -0.08;
// Halfway between the feet (-16) and the top of the nametag (about 23).
const STAGE_LOOK_Y = 3.5;
/** The body's turn at rest: a three-quarter view, facing a little to the viewer's left. */
const STAGE_REST_YAW = -0.55;

/**
 * The stage pose: a slow arm and cape sway, the head following
 * the pointer anywhere on the window (within limits, relative to the body),
 * and dragging on the player turning it, after which it eases back to its
 * three-quarter rest. Everything eases with time, so it is the same at any
 * frame rate, and stops with the viewer when it is paused off screen.
 */
class StageMotion {
  constructor() {
    this.turn = STAGE_REST_YAW;
    this.turnTarget = STAGE_REST_YAW;
    this.dragging = false;
    this.headYaw = 0;
    this.headPitch = 0;
    this.lookYaw = 0;
    this.lookPitch = 0;
    this.lastPointer = 0;
  }

  animation() {
    return new skinview3d.FunctionAnimation((player, progress, delta) => {
      const dt = Math.min(0.1, Math.max(0, delta || 0));
      const t = progress * 1.6;
      // A slow arm and cape sway, as Minecraft's own idle.
      player.skin.leftArm.rotation.z = Math.cos(t) * 0.035 + Math.PI * 0.025;
      player.skin.rightArm.rotation.z = Math.cos(t + Math.PI) * 0.035 - Math.PI * 0.025;
      player.skin.leftArm.rotation.x = Math.sin(t * 0.5) * 0.04;
      player.skin.rightArm.rotation.x = -Math.sin(t * 0.5) * 0.04;
      player.cape.rotation.x = Math.PI * 0.07 + Math.sin(t) * 0.025;

      // The body eases to where it is dragged, or back to rest.
      const turnRate = this.dragging ? 28 : 3.2;
      this.turn += (this.turnTarget - this.turn) * (1 - Math.exp(-dt * turnRate));
      player.rotation.y = this.turn;

      // With no pointer for a while the head turns back towards the viewer,
      // the body staying three-quarter on.
      const idle = performance.now() - this.lastPointer > 2500;
      const wantYaw = idle ? -this.turn * 0.6 : this.lookYaw - this.turn;
      const wantPitch = idle ? 0 : this.lookPitch;
      const k = 1 - Math.exp(-dt * 7);
      this.headYaw += (Math.max(-0.7, Math.min(0.7, wantYaw)) - this.headYaw) * k;
      this.headPitch += (Math.max(-0.35, Math.min(0.45, wantPitch)) - this.headPitch) * k;
      player.skin.head.rotation.y = this.headYaw;
      player.skin.head.rotation.x = this.headPitch;
    });
  }

  /** Listens to the pointer; returns the function that stops listening. */
  attach(canvas, interactive) {
    const onMove = (e) => {
      const r = canvas.getBoundingClientRect();
      if (!r.width) return;
      // The head sits near the top fifth of the canvas.
      const dx = e.clientX - (r.left + r.width / 2);
      const dy = e.clientY - (r.top + r.height * 0.24);
      const reach = Math.max(260, r.width * 1.6);
      this.lookYaw = Math.atan2(dx, reach);
      this.lookPitch = Math.atan2(dy, reach * 1.2);
      this.lastPointer = performance.now();
      if (this.dragging) {
        this.turnTarget = this.dragFrom + (e.clientX - this.dragX) * 0.012;
      }
    };
    const onDown = (e) => {
      if (!interactive) return;
      this.dragging = true;
      this.dragX = e.clientX;
      this.dragFrom = this.turn;
      try { canvas.setPointerCapture(e.pointerId); } catch {}
    };
    const onUp = (e) => {
      if (!this.dragging) return;
      this.dragging = false;
      this.turnTarget = STAGE_REST_YAW;
      try { canvas.releasePointerCapture(e.pointerId); } catch {}
    };
    window.addEventListener("pointermove", onMove, { passive: true });
    canvas.addEventListener("pointerdown", onDown);
    window.addEventListener("pointerup", onUp);
    window.addEventListener("pointercancel", onUp);
    return () => {
      window.removeEventListener("pointermove", onMove);
      canvas.removeEventListener("pointerdown", onDown);
      window.removeEventListener("pointerup", onUp);
      window.removeEventListener("pointercancel", onUp);
    };
  }
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
  /**
   * "turntable": spins and walks (store cards, previews). "stage": stands in a
   * three-quarter pose and breathes, its head follows the pointer, dragging
   * turns it and it eases back when let go (the play page and the Wardrobe,
   * inside PlayerStage).
   */
  mode = "turntable",
  /** A name shown above the head, like a nametag in game (stage mode). */
  nameTag = null,
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
  // No WebGL (disabled GPU, a blocked driver, a weak machine): the player is
  // drawn flat from the same skin instead of leaving an empty frame.
  const [noWebgl, setNoWebgl] = useState(false);
  const showElytraRef = useRef(false);
  showElytraRef.current = showElytra;

  const hasFrames = Array.isArray(animationFrames) && animationFrames.length > 1;
  const hasCape = Boolean(capeUrl || hasFrames);

  // The viewer is built once; a size change (a dashboard widget resized, the
  // window changed) is applied to the same viewer rather than rebuilding it.
  useEffect(() => {
    const viewer = viewerRef.current;
    const canvas = canvasRef.current;
    if (!viewer || !canvas) return;
    try { viewer.setSize(width, height); } catch {}
    canvas.style.width = `${width}px`;
    canvas.style.height = `${height}px`;
  }, [width, height]);

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
      console.warn("[SkinViewer] Init failed, drawing the player flat:", err);
      initializedRef.current = false;
      setNoWebgl(true);
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

    const stage = mode === "stage";
    viewer.autoRotate = !stage;
    viewer.autoRotateSpeed = 0.6;
    try { viewer.globalLight = 1.6; } catch {}
    try { viewer.cameraLight = 0.8; } catch {}
    if (stage) {
      // A slightly low camera looking a little up at the player, as on a stage.
      try {
        viewer.controls.enableRotate = false;
        viewer.controls.enableZoom = false;
        viewer.controls.target.set(0, STAGE_LOOK_Y, 0);
        viewer.camera.position.set(0, STAGE_CAMERA_Y, 1);
        viewer.fov = STAGE_FOV;
        viewer.zoom = STAGE_ZOOM;
        viewer.controls.update();
      } catch {}
      try { viewer.globalLight.intensity = 2.2; viewer.cameraLight.intensity = 0.9; } catch {}
    }
    // Cosmetics tick inside the viewer's own frame loop, so they pause with
    // it when the canvas is off screen or the window is hidden.
    const rig = new CosmeticRig(viewer, { onError: (key, err) => cosmeticErrorRef.current?.(key, err) });
    rigRef.current = rig;
    // Development builds only: lets a test page measure what the rig built.
    if (import.meta.env.DEV) canvas.__breezeRig = rig;
    let stageMotion = null;
    try {
      if (stage) {
        stageMotion = new StageMotion();
        viewer.animation = rig.wrapAnimation(stageMotion.animation());
      } else {
        const walk = skinview3d.WalkingAnimation ? new skinview3d.WalkingAnimation() : null;
        if (walk) walk.speed = 0.8;
        viewer.animation = rig.wrapAnimation(walk);
      }
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
    const detachStage = stage && stageMotion ? stageMotion.attach(canvas, interactive) : null;
    if (interactive && !stage) {
      canvas.addEventListener("mousedown", stopRotate);
      canvas.addEventListener("mouseup", resumeRotate);
      canvas.addEventListener("touchstart", stopRotate, { passive: true });
      canvas.addEventListener("touchend", resumeRotate);
    }

    return () => {
      detachStage?.();
      if (interactive && !stage) {
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
    try {
      v.nameTag = nameTag
        ? new skinview3d.NameTagObject(String(nameTag), {
            font: "600 44px 'DM Sans', system-ui, sans-serif",
            textStyle: "#ffffff",
            backgroundStyle: "rgba(10, 12, 18, 0.5)",
            margin: [6, 16, 4, 16],
            height: 2.6,
          })
        : null;
      // A little higher than skinview3d puts it, clear of the hair.
      if (v.nameTag) v.nameTag.position.y = 21.5;
    } catch {}
  }, [nameTag]);

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

  if (noWebgl) {
    return (
      <div className={`skin-viewer-wrap ${className}`} style={{ width }}>
        <FlatPlayer skinUrl={skinUrl} width={width} height={height} nameTag={mode === "stage" ? nameTag : null} />
      </div>
    );
  }

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

/**
 * The player drawn flat, front on, straight from the skin texture: head,
 * body, arms and legs with their outer layers, in crisp pixels. Used where
 * WebGL is not available; costs one 2D canvas and no animation.
 */
const FLAT_PARTS = [
  // [source x, y, w, h, dest x, y] in skin pixels; dest is a 16 by 32 figure.
  [8, 8, 8, 8, 4, 0], [40, 8, 8, 8, 4, 0],          // head, hat
  [20, 20, 8, 12, 4, 8], [20, 36, 8, 12, 4, 8],     // body, jacket
  [44, 20, 4, 12, 0, 8], [44, 36, 4, 12, 0, 8],     // right arm, sleeve
  [36, 52, 4, 12, 12, 8], [52, 52, 4, 12, 12, 8],   // left arm, sleeve
  [4, 20, 4, 12, 4, 20], [4, 36, 4, 12, 4, 20],     // right leg, trouser
  [20, 52, 4, 12, 8, 20], [4, 52, 4, 12, 8, 20],    // left leg, trouser
];
function FlatPlayer({ skinUrl, width, height, nameTag }) {
  const ref = useRef(null);
  useEffect(() => {
    const canvas = ref.current;
    if (!canvas || !skinUrl) return undefined;
    let cancelled = false;
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload = () => {
      if (cancelled) return;
      const dpr = Math.max(window.devicePixelRatio || 1, 1);
      canvas.width = Math.round(width * dpr);
      canvas.height = Math.round(height * dpr);
      const ctx = canvas.getContext("2d");
      if (!ctx) return;
      ctx.imageSmoothingEnabled = false;
      ctx.clearRect(0, 0, canvas.width, canvas.height);
      const legacy = img.height < 64; // 64x32 skins have no second layer or left limbs
      const top = nameTag ? 0.16 : 0.06;
      const scale = Math.floor(Math.min((canvas.width * 0.8) / 16, (canvas.height * (0.94 - top)) / 32)) || 1;
      const ox = Math.round((canvas.width - 16 * scale) / 2);
      const oy = Math.round(canvas.height * top);
      for (const [sx, sy, w, h, dx, dy] of FLAT_PARTS) {
        let srcX = sx;
        let srcY = sy;
        if (legacy && sy >= 32) {
          // Old skins mirror the right limbs for the left ones and have no overlays.
          if (sy === 52 && (sx === 36 || sx === 20)) { srcX = sx === 36 ? 44 : 4; srcY = 20; } else continue;
        }
        ctx.drawImage(img, srcX, srcY, w, h, ox + dx * scale, oy + dy * scale, w * scale, h * scale);
      }
    };
    img.src = skinUrl;
    return () => { cancelled = true; };
  }, [skinUrl, width, height, nameTag]);
  return (
    <div className="skin-flat" style={{ width, height }}>
      {nameTag && <div className="skin-flat-tag">{nameTag}</div>}
      <canvas ref={ref} className="skin-flat-canvas" style={{ width, height }} aria-label={nameTag ? `${nameTag}'s skin` : "Player skin"} role="img" />
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
