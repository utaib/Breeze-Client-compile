import { useEffect, useMemo, useState } from "react";
import { I } from "../../ui/icons";
import { AdFrame } from "../../components/Shared";
import { useBreezeFM } from "./BreezeFMProvider";
import FMImmersive from "./FMImmersive";
import SpotifyPanel from "./SpotifyPanel";

function fmt(sec) {
  const s = Math.max(0, Math.floor(sec || 0));
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

/**
 * Breeze FM browse view, powered by Audius (free, no account or premium).
 *
 * This page owns no audio element. Playback lives in BreezeFMProvider at the
 * application root, because when the <audio> element was mounted here React
 * destroyed it on navigation and music stopped the moment the user changed tab.
 * The page is now purely a browser and remote control over that shared state.
 */
export default function AudiusFMPage({ featureFlags, session, notify, isAdmin }) {
  const fm = useBreezeFM();
  // The full-screen view is a sibling overlay rather than a route, so closing
  // it returns to the list the user had built up instead of navigating away.
  const [immersive, setImmersive] = useState(false);
  const {
    tracks, current, index, playing, loading, error, heading,
    progress, duration, volume, query,
  } = fm;

  // Load trending on first visit. The provider no-ops on repeat calls, so
  // returning to this page does not refetch.
  useEffect(() => { fm.loadTrending(); }, [fm]);

  if (featureFlags?.radio_disabled) {
    return (
      <div className="sv page-enter" style={{ display: "flex", alignItems: "center", justifyContent: "center", flex: 1 }}>
        <div style={{ textAlign: "center" }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: "var(--text-primary)", marginBottom: 6 }}>Breeze FM Unavailable</div>
          <div style={{ fontSize: 11, color: "var(--text-faint)" }}>This feature is currently under maintenance.</div>
        </div>
      </div>
    );
  }

  const pct = useMemo(() => (duration ? (progress / duration) * 100 : 0), [progress, duration]);

  return (
    <div className="sv page-enter ecosystem-page">
      <div className="ecosystem-main">
        <div className="vtl"><I.Music /> Breeze FM</div>
        <div className="fm-search-row">
          <div className="sw">
            <I.Search />
            <input value={query} onChange={(e) => fm.setQuery(e.target.value)} onKeyDown={(e) => e.key === "Enter" && fm.search()} placeholder="Search songs, artists…" />
          </div>
          <button className="btn accent" onClick={() => fm.search()}>Search</button>
        </div>

        <div className="fm-list-head">{heading}</div>
        {loading ? (
          <div className="empty-panel">Loading music…</div>
        ) : error ? (
          <div className="friendly-empty">{error}</div>
        ) : (
          <div className="fm-track-list">
            {tracks.map((t, i) => (
              <button key={t.id} className={`fm-track ${i === index ? "on" : ""}`} onClick={() => fm.playAt(i)}>
                <div className="fm-art">
                  {t.artwork ? <img src={t.artwork} alt="" /> : <I.Music />}
                  <span className="fm-art-play">{i === index && playing ? <I.Pause /> : <I.Play />}</span>
                </div>
                <div className="fm-track-meta">
                  <div className="fm-track-title">{t.title}</div>
                  <div className="fm-track-artist">{t.artist}</div>
                </div>
                <div className="fm-track-dur">{fmt(t.duration)}</div>
              </button>
            ))}
          </div>
        )}

        {/* Now playing bar */}
        {current && (
          <div className="fm-nowplaying">
            <div className="fm-np-art">{current.artwork ? <img src={current.artwork} alt="" /> : <I.Music />}</div>
            <div className="fm-np-meta">
              <div className="fm-np-title">{current.title}</div>
              <div className="fm-np-artist">{current.artist}</div>
            </div>
            <div className="fm-np-controls">
              <button className="mini-btn" onClick={fm.prev} title="Previous"><I.SkipBack /></button>
              <button className="fm-play" onClick={fm.toggle}>{playing ? <I.Pause /> : <I.Play />}</button>
              <button className="mini-btn" onClick={fm.next} title="Next"><I.SkipForward /></button>
            </div>
            <div className="fm-np-seek">
              <span className="fm-time">{fmt(progress)}</span>
              <input type="range" min="0" max="100" value={pct} onChange={(e) => fm.seekTo(Number(e.target.value) / 100)} className="fm-range" />
              <span className="fm-time">{fmt(duration)}</span>
            </div>
            <button className="fm-expand" onClick={() => setImmersive(true)} title="Full screen"><I.Maximize /></button>
            <div className="fm-np-vol">
              <I.Volume />
              <input type="range" min="0" max="1" step="0.01" value={volume} onChange={(e) => fm.setVolume(Number(e.target.value))} className="fm-range vol" />
            </div>
            {/* No <audio> here on purpose: the element lives in
                BreezeFMProvider so navigation cannot stop playback. */}
          </div>
        )}
      </div>
      {immersive && current && <FMImmersive onClose={() => setImmersive(false)} />}
      <div className="ecosystem-side">
        <div className="panel">
          <div className="panel-title">Breeze FM</div>
          <div className="sd">Free music from Audius, no account or premium needed. Search or press play on any track.</div>
        </div>
        {session?.breezeToken && (
          <SpotifyPanel
            token={session.breezeToken}
            notify={notify}
            onUseFree={() => fm.loadTrending()}
            isAdmin={isAdmin}
          />
        )}
        <AdFrame />
      </div>
    </div>
  );
}
