import { useEffect, useRef, useState } from "react";

/** A ref and the size of the element it is on, kept up to date as it resizes. */
export default function useBoxSize() {
  const ref = useRef(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver !== "function") return undefined;
    const ro = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setSize((s) => (Math.abs(s.w - width) < 2 && Math.abs(s.h - height) < 2 ? s : { w: Math.round(width), h: Math.round(height) }));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, size];
}
