import { useEffect, useState } from "react";
import CapeFace from "./CapeFace";
import { modelSnapshot } from "./modelSnapshot";
import { isAnimated, slotOf, slotLabel } from "./storeLook";

/** The icon shown when an item has no picture to show. */
export function ItemFallback({ slot = "other", label = "Breeze cosmetic" }) {
  return (
    <span className="store-art-fallback" role="img" aria-label={label}>
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 2L2 7l10 5 10-5-10-5z" /><path d="M2 17l10 5 10-5" /><path d="M2 12l10 5 10-5" />
      </svg>
      <span>{slotLabel(slot)}</span>
    </span>
  );
}

/** A 3D cosmetic as a still picture (one shared renderer, see modelSnapshot). */
export function ModelThumb({ url, name, slot }) {
  const [src, setSrc] = useState(null);
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    let live = true;
    setSrc(null);
    setFailed(false);
    modelSnapshot(url).then((s) => { if (live) setSrc(s); }, () => { if (live) setFailed(true); });
    return () => { live = false; };
  }, [url]);
  if (failed) return <ItemFallback slot={slot} label={`${name}: preview unavailable`} />;
  if (!src) return <span className="store-art-model loading" role="img" aria-label={name} />;
  return <img className="store-art-model" src={src} alt={name} draggable={false} />;
}

function PlainImage({ src, name, slot }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [src]);
  if (!src || failed) return <ItemFallback slot={slot} label={name} />;
  return <img className="store-art-image" src={src} alt={name} draggable={false} onError={() => setFailed(true)} />;
}

/**
 * An item's picture on a card or in the spotlight's list: a cape's outside,
 * a cosmetic's own picture, or a still of its 3D model.
 */
export function ItemArt({ item, animate = true }) {
  const slot = slotOf(item);
  if (item._type === "cape") {
    return (
      <CapeFace
        url={item.image_url}
        frames={isAnimated(item) ? item.animation_frames : null}
        fps={item.animation_fps}
        name={item.name}
        animate={animate}
      />
    );
  }
  if (item.thumbSrc) return <PlainImage src={item.thumbSrc} name={item.name} slot={slot} />;
  if (item.model_url) return <ModelThumb url={item.model_url} name={item.name} slot={slot} />;
  return <ItemFallback slot={slot} label={item.name} />;
}
