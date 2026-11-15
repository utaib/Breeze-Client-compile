import { useEffect, useRef, useState } from "react";
import { capeCrop } from "../wardrobe/catalog";
import { measureCape } from "../wardrobe/CapeThumb";
import { prefersReducedMotion } from "../animation/motionPrefs";

/**
 * A cape as it hangs on a player's back: the outside of its texture, cropped
 * and drawn with sharp pixels (any sheet size, HD and the old 22 by 17 ones
 * included). Plain CSS, so a Store full of capes costs no WebGL context.
 *
 * An animated cape steps through its frames while it is on screen, at its
 * own speed (at most 20 a second), and holds the first frame for players
 * who ask for reduced motion or while `animate` is off.
 */
const preloaded = new Map();
function preload(urls) {
  return Promise.all(urls.map((url) => {
    if (!preloaded.has(url)) {
      preloaded.set(url, new Promise((resolve) => {
        const img = new Image();
        img.decoding = "async";
        img.onload = () => resolve(true);
        img.onerror = () => resolve(false);
        img.src = url;
      }));
    }
    return preloaded.get(url);
  })).then((ok) => ok.every(Boolean));
}

const cssUrl = (url) => `url("${String(url).replace(/["\\\n\r]/g, (c) => encodeURIComponent(c))}")`;

export default function CapeFace({ url, frames = null, fps = null, name = "", animate = true, className = "" }) {
  const ref = useRef(null);
  const list = Array.isArray(frames) ? frames.filter((f) => typeof f === "string" && f) : [];
  const animated = list.length > 1;
  const first = animated ? list[0] : url;
  const framesKey = animated ? list.join("\n") : "";
  const [size, setSize] = useState(undefined);
  const [frame, setFrame] = useState(0);

  useEffect(() => {
    let live = true;
    setSize(undefined);
    setFrame(0);
    if (first) measureCape(first).then((s) => { if (live) setSize(s); });
    else setSize(null);
    return () => { live = false; };
  }, [first]);

  useEffect(() => {
    const el = ref.current;
    if (!animated || !animate || !size || !el || prefersReducedMotion()) return undefined;
    const rate = Math.min(20, Math.max(1, Number(fps) || 8));
    let timer = 0;
    let visible = false;
    let ready = false;
    let disposed = false;
    const start = () => {
      if (disposed || !ready || !visible || timer) return;
      timer = window.setInterval(() => setFrame((f) => (f + 1) % list.length), 1000 / rate);
    };
    const stop = () => { if (timer) window.clearInterval(timer); timer = 0; };
    preload(list).then((ok) => { ready = ok; start(); });
    const io = typeof IntersectionObserver === "function"
      ? new IntersectionObserver(([entry]) => { visible = entry.isIntersecting; if (visible) start(); else stop(); }, { threshold: 0.05 })
      : null;
    if (io) io.observe(el); else { visible = true; start(); }
    return () => { disposed = true; io?.disconnect(); stop(); setFrame(0); };
    // list is derived from framesKey; listing it would restart on every render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [framesKey, animate, size, fps]);

  if (size === null) {
    return <span ref={ref} className={`cape-face missing ${className}`} role="img" aria-label={`${name}: picture unavailable`} />;
  }
  const shown = animated ? list[frame % list.length] : url;
  return (
    <span
      ref={ref}
      className={`cape-face${size ? "" : " loading"} ${className}`}
      role="img"
      aria-label={name}
      data-frame={animated ? frame : undefined}
      style={size ? { backgroundImage: cssUrl(shown), ...capeCrop(size.w, size.h) } : undefined}
    />
  );
}
