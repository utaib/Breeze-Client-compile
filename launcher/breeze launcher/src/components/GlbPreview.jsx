import { useEffect, useRef, useState } from "react";
import * as THREE from "three";
import { GLTFLoader } from "three/examples/jsm/loaders/GLTFLoader.js";


export default function GlbPreview({ src, size = 200 }) {
  const mountRef = useRef(null);
  const [err, setErr] = useState(null);

  useEffect(() => {
    if (!src) return;
    const mount = mountRef.current;
    if (!mount) return;

    let disposed = false;
    let raf = 0;
    let visible = true;

    const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 1.5));
    renderer.setSize(size, size);
    renderer.outputColorSpace = THREE.SRGBColorSpace;
    mount.appendChild(renderer.domElement);

    const scene = new THREE.Scene();
    const camera = new THREE.PerspectiveCamera(35, 1, 0.05, 100);
    camera.position.set(0, 0.5, 3);

    
    scene.add(new THREE.HemisphereLight(0xffffff, 0x222233, 0.9));
    const key = new THREE.DirectionalLight(0xffffff, 1.1);
    key.position.set(2, 3, 4);
    scene.add(key);

    const pivot = new THREE.Group();
    scene.add(pivot);

    let dragging = false;
    let lastX = 0;
    let userYaw = 0;
    let auto = true;
    /** Drives glTF clip playback. Null until a model with animations loads. */
    let mixer = null;

    const onDown = (e) => { dragging = true; auto = false; lastX = e.clientX; };
    const onMove = (e) => {
      if (!dragging) return;
      userYaw += (e.clientX - lastX) * 0.01;
      lastX = e.clientX;
    };
    const onUp = () => { dragging = false; };
    const dom = renderer.domElement;
    dom.addEventListener("mousedown", onDown);
    window.addEventListener("mousemove", onMove);
    window.addEventListener("mouseup", onUp);

    const loader = new GLTFLoader();
    loader.load(
      src,
      (gltf) => {
        if (disposed) return;
        const root = gltf.scene;

        
        
        const box = new THREE.Box3().setFromObject(root);
        const sizeVec = box.getSize(new THREE.Vector3());
        const center = box.getCenter(new THREE.Vector3());
        const maxDim = Math.max(sizeVec.x, sizeVec.y, sizeVec.z) || 1;
        const scale = 1.4 / maxDim;
        root.position.sub(center.multiplyScalar(scale));
        root.scale.setScalar(scale);

        pivot.add(root);

        // Play the model's own animations.
        //
        // These were parsed by GLTFLoader and then ignored, so any animated
        // cosmetic previewed as a frozen T-pose or a static mesh. A glTF file
        // can carry several clips (idle, spin, flap); a cosmetic preview should
        // show all of them together rather than guessing which is primary.
        if (gltf.animations && gltf.animations.length > 0) {
          mixer = new THREE.AnimationMixer(root);
          for (const clip of gltf.animations) {
            mixer.clipAction(clip).reset().play();
          }
        }
      },
      undefined,
      (e) => {
        if (disposed) return;
        setErr(e?.message || "Failed to load model");
      },
    );

    let lastT = performance.now();
    const tick = (t) => {
      if (disposed || !visible) {
        raf = 0;
        return;
      }
      const dt = (t - lastT) / 1000;
      lastT = t;
      if (mixer) mixer.update(dt);
      if (auto) pivot.rotation.y += dt * 0.6;
      else pivot.rotation.y = userYaw;
      renderer.render(scene, camera);
      raf = requestAnimationFrame(tick);
    };
    const startLoop = () => {
      if (disposed || raf) return;
      lastT = performance.now();
      raf = requestAnimationFrame(tick);
    };
    const stopLoop = () => {
      if (!raf) return;
      cancelAnimationFrame(raf);
      raf = 0;
    };

    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting;
      if (visible) startLoop();
      else stopLoop();
    }, { threshold: 0.05 });
    observer.observe(mount);
    startLoop();

    return () => {
      disposed = true;
      observer.disconnect();
      stopLoop();
      // Release clip actions before the scene is torn down. A mixer holds
      // references to every animated node, so leaving it attached keeps the
      // model graph alive after the preview closes.
      if (mixer) {
        mixer.stopAllAction();
        mixer.uncacheRoot(mixer.getRoot());
        mixer = null;
      }
      dom.removeEventListener("mousedown", onDown);
      window.removeEventListener("mousemove", onMove);
      window.removeEventListener("mouseup", onUp);
      if (dom.parentElement === mount) mount.removeChild(dom);
      scene.traverse((o) => {
        if (o.geometry) o.geometry.dispose?.();
        if (o.material) {
          const materials = Array.isArray(o.material) ? o.material : [o.material];
          materials.forEach((material) => {
            Object.values(material).forEach((value) => {
              if (value && typeof value === "object" && "isTexture" in value) {
                value.dispose?.();
              }
            });
            material.dispose?.();
          });
        }
      });
      renderer.dispose();
    };
  }, [src, size]);

  if (err) {
    return (
      <div
        style={{
          width: size,
          height: size,
          display: "grid",
          placeItems: "center",
          fontSize: 11,
          color: "rgba(255,160,160,0.85)",
          background: "rgba(255,80,80,0.06)",
          borderRadius: 10,
          textAlign: "center",
          padding: 12,
        }}
      >
        Model preview unavailable
      </div>
    );
  }

  return (
    <div
      ref={mountRef}
      style={{
        width: size,
        height: size,
        cursor: "grab",
        borderRadius: 10,
        overflow: "hidden",
        background:
          "radial-gradient(circle at 50% 35%, rgba(80,140,220,0.18), rgba(8,12,28,0.0) 65%)",
      }}
    />
  );
}
