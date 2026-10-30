import { useEffect, useState } from "react";
import { capeCrop } from "./catalog";

/**
 * A cape's outside, cropped from its texture: the sheet's size decides the
 * crop (HD and old 22 by 17 sheets included). Each texture is measured once
 * per session and the browser keeps the image, so switching tabs or filters
 * does not fetch it again. A texture that does not load shows a plain tile.
 */
const sizes = new Map();

function measure(url) {
  if (!sizes.has(url)) {
    sizes.set(url, new Promise((resolve) => {
      const img = new Image();
      img.decoding = "async";
      img.onload = () => resolve(img.naturalWidth > 0 && img.naturalWidth <= 8192 ? { w: img.naturalWidth, h: img.naturalHeight } : null);
      img.onerror = () => resolve(null);
      img.src = url;
    }));
  }
  return sizes.get(url);
}

export default function CapeThumb({ url, name = "" }) {
  const [size, setSize] = useState(undefined);
  useEffect(() => {
    let live = true;
    setSize(undefined);
    if (url) measure(url).then((s) => { if (live) setSize(s); });
    else setSize(null);
    return () => { live = false; };
  }, [url]);
  if (size === null) return <span className="wd-thumb missing" role="img" aria-label={`${name}: picture unavailable`} />;
  return (
    <span
      className={`wd-thumb${size ? "" : " loading"}`}
      role="img"
      aria-label={name}
      style={size ? { backgroundImage: `url("${String(url).replace(/"/g, "%22")}")`, ...capeCrop(size.w, size.h) } : undefined}
    />
  );
}
