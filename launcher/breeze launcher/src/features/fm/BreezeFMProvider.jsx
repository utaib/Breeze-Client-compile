import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { searchAudius, trendingAudius } from "../../services/breezeApi";

/**
 * Breeze FM playback, owned at the application root.
 *
 * The `<audio>` element used to live inside the Breeze FM page component, so
 * React destroyed it the moment the user navigated to any other tab and music
 * stopped dead. Mounting it once here, above the router, means playback survives
 * arbitrary navigation and only an explicit pause stops it.
 *
 * The FM page and the compact popup are both thin UI layers over this context.
 * Neither owns an audio element, so the lifecycle bug cannot be reintroduced by
 * editing either of them.
 */
const FMContext = createContext(null);

export function useBreezeFM() {
  const ctx = useContext(FMContext);
  // A missing provider must never crash a page; playback simply does nothing.
  return ctx || FM_FALLBACK;
}

const FM_FALLBACK = {
  tracks: [], current: null, index: -1, playing: false, loading: false, error: "",
  heading: "", progress: 0, duration: 0, volume: 0.8, query: "",
  setQuery: () => {}, search: () => {}, playAt: () => {}, toggle: () => {},
  next: () => {}, prev: () => {}, seekTo: () => {}, setVolume: () => {}, loadTrending: () => {},
};

export function BreezeFMProvider({ children }) {
  const audioRef = useRef(null);
  const [tracks, setTracks] = useState([]);
  const [index, setIndex] = useState(-1);
  const [playing, setPlaying] = useState(false);
  const [progress, setProgress] = useState(0);
  const [duration, setDuration] = useState(0);
  const [volume, setVolumeState] = useState(() => {
    const saved = Number(window.localStorage?.getItem("breeze.fm.volume"));
    return Number.isFinite(saved) && saved >= 0 && saved <= 1 ? saved : 0.8;
  });
  const [query, setQuery] = useState("");
  const [heading, setHeading] = useState("Trending on Audius");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const loadedRef = useRef(false);

  const current = index >= 0 ? tracks[index] || null : null;

  const loadTrending = useCallback(async () => {
    // Only once per session unless the user searches; the list is not volatile.
    if (loadedRef.current) return;
    loadedRef.current = true;
    setLoading(true);
    try {
      setTracks(await trendingAudius());
      setHeading("Trending on Audius");
    } catch {
      setError("Couldn't load music right now.");
    } finally {
      setLoading(false);
    }
  }, []);

  const search = useCallback(async () => {
    const q = query.trim();
    if (!q) return;
    setLoading(true);
    setError("");
    try {
      const found = await searchAudius(q);
      setTracks(found);
      setHeading(`Results for "${q}"`);
      if (!found.length) setError("No tracks found. Try another search.");
    } catch {
      setError("Search is unavailable right now.");
    } finally {
      setLoading(false);
    }
  }, [query]);

  const playAt = useCallback((i) => {
    setIndex(i);
    setPlaying(true);
  }, []);

  const toggle = useCallback(() => {
    const a = audioRef.current;
    if (!current) {
      if (tracks.length) playAt(0);
      return;
    }
    if (playing) { a?.pause(); setPlaying(false); }
    else { a?.play().catch(() => {}); setPlaying(true); }
  }, [current, playing, tracks.length, playAt]);

  const next = useCallback(() => {
    if (tracks.length) playAt((index + 1) % tracks.length);
  }, [tracks.length, index, playAt]);

  const prev = useCallback(() => {
    if (tracks.length) playAt((index - 1 + tracks.length) % tracks.length);
  }, [tracks.length, index, playAt]);

  const seekTo = useCallback((fraction) => {
    const a = audioRef.current;
    if (!a || !duration) return;
    a.currentTime = Math.max(0, Math.min(1, fraction)) * duration;
  }, [duration]);

  const setVolume = useCallback((v) => {
    const clamped = Math.max(0, Math.min(1, v));
    setVolumeState(clamped);
    try { window.localStorage?.setItem("breeze.fm.volume", String(clamped)); } catch { /* not fatal */ }
  }, []);

  // Apply volume to the live element whenever either changes.
  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume;
  }, [volume, current]);

  // Start playback when the selected track changes. Waiting for the src to be
  // applied avoids calling play() on the previous track.
  useEffect(() => {
    const a = audioRef.current;
    if (!a || !current || !playing) return;
    const t = setTimeout(() => { a.play().catch(() => {}); }, 30);
    return () => clearTimeout(t);
  }, [current?.id, playing, current]);

  const value = useMemo(
    () => ({
      tracks, current, index, playing, loading, error, heading,
      progress, duration, volume, query,
      setQuery, search, playAt, toggle, next, prev, seekTo, setVolume, loadTrending,
    }),
    [tracks, current, index, playing, loading, error, heading, progress, duration, volume, query,
     search, playAt, toggle, next, prev, seekTo, setVolume, loadTrending],
  );

  return (
    <FMContext.Provider value={value}>
      {children}
      {/* Mounted once, above the router. Never unmounted while the app runs, so
          navigation cannot interrupt playback. */}
      <audio
        ref={audioRef}
        src={current?.streamUrl || undefined}
        onTimeUpdate={(e) => setProgress(e.currentTarget.currentTime)}
        onLoadedMetadata={(e) => setDuration(e.currentTarget.duration || 0)}
        onEnded={next}
        onPause={() => setPlaying(false)}
        onPlay={() => setPlaying(true)}
        onError={() => { setPlaying(false); setError("That track could not be played."); }}
        preload="none"
        style={{ display: "none" }}
      />
    </FMContext.Provider>
  );
}
