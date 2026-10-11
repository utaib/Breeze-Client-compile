import { useBreezeFM } from "./BreezeFMProvider";
import { I } from "../../ui/icons";

/**
 * Compact Breeze FM popup.
 *
 * The top-right icon used to navigate to the full page, which was heavy for
 * "pause the music" and, because the page owned the audio element, leaving it
 * stopped playback entirely. This reads from the root-level provider instead, so
 * it reflects live state from anywhere in the app and controls playback without
 * changing page.
 */
export default function FMMiniPlayer({ onClose, onOpenFull }) {
  const fm = useBreezeFM();
  const { current, playing, progress, duration, volume } = fm;
  const pct = duration ? Math.min(100, (progress / duration) * 100) : 0;

  const time = (seconds) => {
    if (!Number.isFinite(seconds) || seconds < 0) return "0:00";
    const m = Math.floor(seconds / 60);
    const s = Math.floor(seconds % 60);
    return `${m}:${String(s).padStart(2, "0")}`;
  };

  return (
    <div className="fm-mini page-enter">
      <div className="fm-mini-head">
        <span className="fm-mini-title"><I.Music /> Breeze FM</span>
        <button className="mini-btn" onClick={onClose} title="Close"><I.X /></button>
      </div>

      {current ? (
        <>
          <div className="fm-mini-now">
            {current.artwork
              ? <img src={current.artwork} alt="" className="fm-mini-art" onError={(e) => { e.currentTarget.style.display = "none"; }} />
              : <div className="fm-mini-art placeholder"><I.Music /></div>}
            <div className="fm-mini-meta">
              <div className="fm-mini-track" title={current.title}>{current.title}</div>
              <div className="fm-mini-artist" title={current.artist}>{current.artist}</div>
            </div>
          </div>

          <div className="fm-mini-seek">
            <span className="fm-mini-time">{time(progress)}</span>
            <input
              type="range" min={0} max={100} value={pct}
              onChange={(e) => fm.seekTo(Number(e.target.value) / 100)}
              aria-label="Seek"
            />
            <span className="fm-mini-time">{time(duration)}</span>
          </div>
        </>
      ) : (
        <div className="fm-mini-empty">
          Nothing playing yet. Open Breeze FM to pick a track.
        </div>
      )}

      <div className="fm-mini-controls">
        <button className="fm-mini-btn" onClick={fm.prev} title="Previous" disabled={!fm.tracks.length}>
          <I.SkipBack />
        </button>
        <button className="fm-mini-btn primary" onClick={fm.toggle} title={playing ? "Pause" : "Play"}>
          {playing ? <I.Pause /> : <I.Play />}
        </button>
        <button className="fm-mini-btn" onClick={fm.next} title="Next" disabled={!fm.tracks.length}>
          <I.SkipForward />
        </button>
        <div className="fm-mini-vol">
          <I.Volume />
          <input
            type="range" min={0} max={100} value={Math.round(volume * 100)}
            onChange={(e) => fm.setVolume(Number(e.target.value) / 100)}
            aria-label="Volume"
          />
        </div>
      </div>

      <button className="fm-mini-full" onClick={onOpenFull}>Browse all music</button>
    </div>
  );
}
