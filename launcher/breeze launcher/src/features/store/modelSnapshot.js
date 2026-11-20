import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";

/**
 * Still pictures of 3D cosmetics for the Store's cards, drawn by one shared
 * WebGL renderer, one model at a time.
 *
 * The cards used to start a live viewer each (a WebGL context per pet, hat
 * and cape). The browser keeps only about 16 contexts alive and drops the
 * oldest past that, so a Store with 26 items lost previews as it scrolled.
 * Now a card shows a picture: the model is loaded once, posed in a
 * three-quarter view, drawn, and kept as an image for the rest of the
 * session. The live, turnable model is in the spotlight and the item's
 * window. The renderer is let go a few seconds after the last picture.
 */
const SIZE = 320;
const LOAD_TIMEOUT_MS = 20000;
const IDLE_RELEASE_MS = 8000;

const cache = new Map();
let renderer = null;
let queue = Promise.resolve();
let pending = 0;
let releaseTimer = 0;

function getRenderer() {
  if (renderer) return renderer;
  const canvas = document.createElement("canvas");
  renderer = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true });
  renderer.setPixelRatio(1);
  renderer.setSize(SIZE, SIZE, false);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  return renderer;
}

function releaseLater() {
  window.clearTimeout(releaseTimer);
  releaseTimer = window.setTimeout(() => {
    if (pending || !renderer) return;
    try { renderer.dispose(); renderer.forceContextLoss(); } catch { /* already gone */ }
    renderer = null;
  }, IDLE_RELEASE_MS);
}

function disposeTree(root) {
  root.traverse((o) => {
    o.geometry?.dispose?.();
    const materials = o.material ? (Array.isArray(o.material) ? o.material : [o.material]) : [];
    for (const m of materials) {
      for (const value of Object.values(m)) if (value && value.isTexture) value.dispose();
      m.dispose?.();
    }
  });
}

function load(url) {
  return new Promise((resolve, reject) => {
    const timer = window.setTimeout(() => reject(new Error("The model took too long to load")), LOAD_TIMEOUT_MS);
    new GLTFLoader().load(url, (gltf) => { window.clearTimeout(timer); resolve(gltf); }, undefined, (e) => {
      window.clearTimeout(timer);
      reject(e instanceof Error ? e : new Error("The model could not be loaded"));
    });
  });
}

async function draw(url) {
  const gltf = await load(url);
  const root = gltf.scene;
  const r = getRenderer();
  const scene = new THREE.Scene();
  scene.add(new THREE.HemisphereLight(0xffffff, 0x222233, 0.95));
  const key = new THREE.DirectionalLight(0xffffff, 1.15);
  key.position.set(2, 3, 4);
  scene.add(key);
  const rim = new THREE.DirectionalLight(0xbfd6ff, 0.5);
  rim.position.set(-3, 2, -3);
  scene.add(rim);

  // A model with clips is shown a moment into them, not in its bind pose.
  let mixer = null;
  if (gltf.animations?.length) {
    mixer = new THREE.AnimationMixer(root);
    for (const clip of gltf.animations) mixer.clipAction(clip).play();
    mixer.update(0.4);
  }
  const pivot = new THREE.Group();
  pivot.add(root);
  pivot.rotation.y = -0.6;
  scene.add(pivot);
  pivot.updateMatrixWorld(true);
  const box = new THREE.Box3().setFromObject(pivot);
  const size = box.getSize(new THREE.Vector3());
  const center = box.getCenter(new THREE.Vector3());
  const maxDim = Math.max(size.x, size.y, size.z) || 1;
  const scale = 1.5 / maxDim;
  pivot.scale.setScalar(scale);
  pivot.position.copy(center.multiplyScalar(-scale));

  const camera = new THREE.PerspectiveCamera(32, 1, 0.05, 100);
  camera.position.set(0, 0.45, 3.2);
  camera.lookAt(0, 0, 0);
  r.setClearColor(0x000000, 0);
  r.clear();
  r.render(scene, camera);
  const blob = await new Promise((resolve) => r.domElement.toBlob(resolve, "image/png"));
  if (mixer) { mixer.stopAllAction(); mixer.uncacheRoot(root); }
  disposeTree(root);
  if (!blob) throw new Error("The picture could not be made");
  return URL.createObjectURL(blob);
}

/** A picture of the model at `url`, made once per session; rejects when it cannot be drawn. */
export function modelSnapshot(url) {
  if (!url) return Promise.reject(new Error("No model"));
  if (!cache.has(url)) {
    pending += 1;
    const job = queue.then(() => draw(url));
    queue = job.catch(() => {}).finally(() => { pending -= 1; releaseLater(); });
    cache.set(url, job);
  }
  return cache.get(url);
}
