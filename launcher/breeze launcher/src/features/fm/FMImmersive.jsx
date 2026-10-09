import { useEffect, useRef } from "react";
import { I } from "../../ui/icons";
import { useBreezeFM } from "./BreezeFMProvider";

/**
 * The full-screen music experience.
 *
 * Covers the entire window rather than living inside the page grid, because the
 * browse view is a list with an ad column beside it and that is the opposite of
 * what listening should feel like. Everything here is driven from the shared
 * provider, so opening and closing this never touches playback.
 *
 * The backdrop is the track's own artwork, scaled up and heavily blurred. That
 * gives every song its own colour without shipping a palette per track or
 * reading pixels back off a canvas, which would taint on cross-origin artwork.
 */

function fmt(sec) {
  const s = Math.max(0, Math.floor(sec || 0));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

export default function FMImmersive({ onClose }) {
  const fm = useBreezeFM();
  const { current, playing, progress, duration, volume } = fm;
  const closeRef = useRef(onClose);
  closeRef.current = onClose;

  // Escape closes, matching every other full-screen surface in the launcher.
  useEffect(() => {
    const onKey = (e) => {
      if (e.key === "Escape") { e.preventDefault(); closeRef.current?.(); }
      if (e.key === " " || e.code === "Space") { e.preventDefault(); fm.toggle(); }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [fm]);

  if (!current) return null;

  const pct = duration ? (progress / duration) * 100 : 0;

  return (
    <div className="fm-immersive">
      {/* Two layers: the blurred art, then a scrim. Keeping them separate means
          the scrim stays at a fixed strength while the art moves and blurs,
          instead of the text legibility changing from track to track. */}
      {current.artwork && (
        <div className="fm-imm-bg" style={{ backgroundImage: `url(${current.artwork})` }} />
      )}
      <div className="fm-imm-scrim" />

      <button className="fm-imm-close" onClick={() => closeRef.current?.()} title="Back to Breeze FM">
        <I.X />
      </button>

      <div className="fm-imm-body">
        <div className={`fm-imm-art${playing ? " spinning" : ""}`}>
          {current.artwork
            ? <img src={current.artwork} alt="" />
            : <div className="fm-imm-art-fallback"><I.Music /></div>}
        </div>

        <div className="fm-imm-title">{current.title}</div>
        <div className="fm-imm-artist">{current.artist}</div>

        <div className="fm-imm-seek">
          <span className="fm-time">{fmt(progress)}</span>
          <input
            type="range" min="0" max="100" value={pct}
            onChange={(e) => fm.seekTo(Number(e.target.value) / 100)}
            className="fm-range"
            aria-label="Seek"
          />
          <span className="fm-time">{fmt(duration)}</span>
        </div>

        <div className="fm-imm-controls">
          <button className="fm-imm-btn" onClick={fm.prev} title="Previous"><I.SkipBack /></button>
          <button className="fm-imm-play" onClick={fm.toggle} title={playing ? "Pause" : "Play"}>
            {playing ? <I.Pause /> : <I.Play />}
          </button>
          <button className="fm-imm-btn" onClick={fm.next} title="Next"><I.SkipForward /></button>
        </div>

        <div className="fm-imm-vol">
          <I.Volume />
          <input
            type="range" min="0" max="1" step="0.01" value={volume}
            onChange={(e) => fm.setVolume(Number(e.target.value))}
            className="fm-range vol"
            aria-label="Volume"
          />
        </div>
      </div>
    </div>
  );
}
