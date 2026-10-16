import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { fmPlayerUrl, getYouTubeStatus, searchAudius, searchYouTube, trendingAudius, youTubeCharts } from "../../services/breezeApi";
import { I } from "../../ui/icons";

/**
 * Breeze FM playback, owned at the application root.
 *
 * The `<audio>` element used to live inside the Breeze FM page component, so
 * React destroyed it the moment the user navigated to any other tab and music
 * stopped dead. Mounting it once here, above the router, means playback survives
 * arbitrary navigation and only an explicit pause stops it.
 *
 * Two sources (from 1.0.38):
 * - YouTube: real songs for free, found through the Breeze API (it holds the
 *   YouTube key) and played by YouTube's own player. YouTube's rules want the
 *   player visible while it plays, so the video sits over the slot a page
 *   marks with data-fm-video-slot (the Breeze FM page, the full-screen view)
 *   and otherwise docks as a small card in the corner. The player page is
 *   served by the API (/fm/player): YouTube refuses embeds from a page without
 *   an https origin, which the launcher's own pages are not. It is mounted
 *   once and moved, never re-parented, so moving it never restarts the song.
 * - Audius: free independent music in an <audio> element, as before. The only
 *   source when the API has no YouTube key or is older than 1.0.38.
 *
 * The FM page and the compact popup are both thin UI layers over this context.
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
  source: "audius", sources: ["audius"],
  setQuery: () => {}, search: () => {}, playAt: () => {}, toggle: () => {},
  next: () => {}, prev: () => {}, seekTo: () => {}, setVolume: () => {}, loadTrending: () => {},
  setSource: () => {}, stop: () => {},
};

const SOURCE_KEY = "breeze.fm.source";
const PLAYER_URL = fmPlayerUrl();
const PLAYER_ORIGIN = (() => { try { return new URL(PLAYER_URL).origin; } catch { return ""; } })();
/** YouTube's player states, as its iframe API numbers them. */
const YT_PLAYING = 1;
const YT_PAUSED = 2;
/** The docked video card: never under YouTube's 200 by 200 minimum. */
const DOCK = { width: 300, height: 200 };

export function BreezeFMProvider({ children }) {
  const audioRef = useRef(null);
  const frameRef = useRef(null);
  const frameReadyRef = useRef(false);
  const pendingRef = useRef([]);
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
  const [heading, setHeading] = useState("");
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [youtube, setYoutube] = useState(false);
  const [wantSource, setWantSource] = useState(() => {
    try { return window.localStorage?.getItem(SOURCE_KEY) === "audius" ? "audius" : "youtube"; } catch { return "youtube"; }
  });
  const [frameMounted, setFrameMounted] = useState(false);
  const [slotRect, setSlotRect] = useState(null);
  const loadedRef = useRef("");

  const source = youtube ? wantSource : "audius";
  const current = index >= 0 ? tracks[index] || null : null;
  const isYouTube = current?.source === "youtube";

  // Does this API have YouTube? An API older than 1.0.38 answers 404: Audius only.
  useEffect(() => {
    let cancelled = false;
    getYouTubeStatus()
      .then((s) => { if (!cancelled) setYoutube(!!s.configured); })
      .catch(() => { if (!cancelled) setYoutube(false); });
    return () => { cancelled = true; };
  }, []);

  // ── Talking to the YouTube player page ──────────────────────────────────
  const post = useCallback((msg) => {
    const win = frameRef.current?.contentWindow;
    if (!win || !PLAYER_ORIGIN) return;
    if (!frameReadyRef.current && msg.type !== "hello") {
      pendingRef.current.push(msg);
      return;
    }
    try { win.postMessage({ source: "breeze-launcher", ...msg }, PLAYER_ORIGIN); } catch { /* the page is reloading */ }
  }, []);

  // Until the page answers, say hello every half second: it only learns who
  // may command it from a first message.
  const greet = useCallback(() => {
    frameReadyRef.current = false;
    let tries = 0;
    const t = setInterval(() => {
      if (frameReadyRef.current || ++tries > 40) { clearInterval(t); return; }
      post({ type: "hello" });
    }, 500);
    post({ type: "hello" });
  }, [post]);

  const loadList = useCallback(async (from, label) => {
    setLoading(true);
    setError("");
    try {
      const list = await from();
      setTracks(list);
      setIndex(-1);
      setHeading(label);
      if (!list.length) setError("Nothing found. Try another search.");
    } catch (e) {
      setError(e?.message && e.status === 503 ? e.message : "Couldn't load music right now.");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadTrending = useCallback(() => {
    // Once per source per session unless the user searches; the lists are not volatile.
    if (loadedRef.current === source) return;
    loadedRef.current = source;
    if (source === "youtube") loadList(youTubeCharts, "Popular on YouTube today");
    else loadList(trendingAudius, "Trending on Audius");
  }, [source, loadList]);

  const search = useCallback(() => {
    const q = query.trim();
    if (!q) return;
    loadedRef.current = `search:${q}`;
    if (source === "youtube") loadList(() => searchYouTube(q), `Results for "${q}"`);
    else loadList(() => searchAudius(q), `Results for "${q}" on Audius`);
  }, [query, source, loadList]);

  const setSource = useCallback((next) => {
    const s = next === "audius" ? "audius" : "youtube";
    setWantSource(s);
    try { window.localStorage?.setItem(SOURCE_KEY, s); } catch { /* not fatal */ }
    loadedRef.current = "";
  }, []);

  const playAt = useCallback((i) => {
    setIndex(i);
    setPlaying(true);
    setProgress(0);
  }, []);

  const toggle = useCallback(() => {
    const a = audioRef.current;
    if (!current) {
      if (tracks.length) playAt(0);
      return;
    }
    if (isYouTube) {
      post({ type: playing ? "pause" : "play" });
      setPlaying(!playing);
      return;
    }
    if (playing) { a?.pause(); setPlaying(false); }
    else { a?.play().catch(() => {}); setPlaying(true); }
  }, [current, isYouTube, playing, tracks.length, playAt, post]);

  const next = useCallback(() => {
    if (tracks.length) playAt((index + 1) % tracks.length);
  }, [tracks.length, index, playAt]);

  const prev = useCallback(() => {
    if (tracks.length) playAt((index - 1 + tracks.length) % tracks.length);
  }, [tracks.length, index, playAt]);

  const stop = useCallback(() => {
    post({ type: "stop" });
    audioRef.current?.pause();
    setPlaying(false);
    setIndex(-1);
  }, [post]);

  const seekTo = useCallback((fraction) => {
    if (!duration) return;
    const seconds = Math.max(0, Math.min(1, fraction)) * duration;
    if (isYouTube) { post({ type: "seek", seconds }); setProgress(seconds); return; }
    const a = audioRef.current;
    if (a) a.currentTime = seconds;
  }, [duration, isYouTube, post]);

  const setVolume = useCallback((v) => {
    const clamped = Math.max(0, Math.min(1, v));
    setVolumeState(clamped);
    try { window.localStorage?.setItem("breeze.fm.volume", String(clamped)); } catch { /* not fatal */ }
  }, []);

  // Latest callbacks for the message listener, which is added once.
  const nextRef = useRef(next);
  nextRef.current = next;
  const currentRef = useRef(current);
  currentRef.current = current;

  useEffect(() => {
    const onMessage = (e) => {
      if (e.origin !== PLAYER_ORIGIN) return;
      const m = e.data;
      if (!m || m.source !== "breeze-fm") return;
      if (m.type === "ready") {
        frameReadyRef.current = true;
        for (const queued of pendingRef.current.splice(0)) post(queued);
        return;
      }
      if (currentRef.current?.source !== "youtube") return;
      if (m.type === "time") {
        setProgress(Number(m.current) || 0);
        if (Number(m.duration) > 0) setDuration(Number(m.duration));
      } else if (m.type === "state") {
        if (m.state === YT_PLAYING) setPlaying(true);
        else if (m.state === YT_PAUSED) setPlaying(false);
      } else if (m.type === "ended") {
        nextRef.current();
      } else if (m.type === "error") {
        // 101 and 150: the uploader does not allow playing outside YouTube.
        setError("A song could not be played outside YouTube, so it was skipped.");
        nextRef.current();
      }
    };
    window.addEventListener("message", onMessage);
    return () => window.removeEventListener("message", onMessage);
  }, [post]);

  // A song from YouTube: the player page loads it; the <audio> element rests.
  useEffect(() => {
    if (!current) return;
    if (current.source === "youtube") {
      audioRef.current?.pause();
      setFrameMounted(true);
      setDuration(current.duration || 0);
      if (playing) post({ type: "load", videoId: current.videoId });
    } else {
      post({ type: "stop" });
    }
    // Only when the song changes; play and pause are sent by toggle.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [current?.id]);

  // Volume to both players.
  useEffect(() => {
    if (audioRef.current) audioRef.current.volume = volume;
    post({ type: "volume", volume });
  }, [volume, current, post]);

  // Start Audius playback when the selected track changes. Waiting for the src
  // to be applied avoids calling play() on the previous track.
  useEffect(() => {
    const a = audioRef.current;
    if (!a || !current || !playing || current.source === "youtube") return;
    const t = setTimeout(() => { a.play().catch(() => {}); }, 30);
    return () => clearTimeout(t);
  }, [current?.id, playing, current]);

  // Where the video goes: over a page's slot, or docked in the corner.
  useEffect(() => {
    if (!isYouTube) return undefined;
    const place = () => {
      // The slot with the highest number wins (the full-screen view over the page).
      const slot = [...document.querySelectorAll("[data-fm-video-slot]")]
        .sort((a, b) => (Number(b.dataset.fmVideoSlot) || 0) - (Number(a.dataset.fmVideoSlot) || 0))[0];
      const r = slot?.getBoundingClientRect();
      if (r && r.width >= DOCK.width * 0.66 && r.height >= DOCK.height) {
        setSlotRect((old) => (old && old.left === r.left && old.top === r.top && old.width === r.width && old.height === r.height
          ? old : { left: r.left, top: r.top, width: r.width, height: r.height }));
      } else {
        setSlotRect((old) => (old ? null : old));
      }
    };
    place();
    const t = setInterval(place, 250);
    window.addEventListener("resize", place);
    return () => { clearInterval(t); window.removeEventListener("resize", place); };
  }, [isYouTube]);

  const sources = youtube ? ["youtube", "audius"] : ["audius"];
  const value = useMemo(
    () => ({
      tracks, current, index, playing, loading, error, heading,
      progress, duration, volume, query, source, sources,
      setQuery, search, playAt, toggle, next, prev, seekTo, setVolume, loadTrending, setSource, stop,
    }),
    [tracks, current, index, playing, loading, error, heading, progress, duration, volume, query, source, youtube,
     search, playAt, toggle, next, prev, seekTo, setVolume, loadTrending, setSource, stop],
  );

  const docked = isYouTube && !slotRect;
  const videoStyle = isYouTube && slotRect
    ? { left: slotRect.left, top: slotRect.top, width: slotRect.width, height: slotRect.height }
    : undefined;

  return (
    <FMContext.Provider value={value}>
      {children}
      {/* Mounted once, above the router. Never unmounted while the app runs, so
          navigation cannot interrupt playback. */}
      <audio
        ref={audioRef}
        src={current && current.source !== "youtube" ? current.streamUrl || undefined : undefined}
        onTimeUpdate={(e) => { if (currentRef.current?.source !== "youtube") setProgress(e.currentTarget.currentTime); }}
        onLoadedMetadata={(e) => { if (currentRef.current?.source !== "youtube") setDuration(e.currentTarget.duration || 0); }}
        onEnded={next}
        onPause={() => { if (currentRef.current?.source !== "youtube") setPlaying(false); }}
        onPlay={() => setPlaying(true)}
        onError={() => { if (currentRef.current?.source === "youtube") return; setPlaying(false); setError("That track could not be played."); }}
        preload="none"
        style={{ display: "none" }}
      />
      {frameMounted && PLAYER_ORIGIN && (
        <div className={`fm-video${isYouTube ? " on" : ""}${docked ? " docked" : ""}`} style={videoStyle} aria-hidden={!isYouTube}>
          {docked && (
            <div className="fm-video-bar">
              <span className="fm-video-title" title={current?.title}><I.Music /> {current?.title}</span>
              <button className="fm-video-close" onClick={stop} title="Stop Breeze FM" aria-label="Stop Breeze FM"><I.X /></button>
            </div>
          )}
          <iframe
            ref={frameRef}
            className="fm-video-frame"
            src={PLAYER_URL}
            title="Breeze FM player"
            allow="autoplay; encrypted-media; picture-in-picture"
            referrerPolicy="strict-origin-when-cross-origin"
            onLoad={() => {
              greet();
              // The first song asked for before the page was ready.
              const c = currentRef.current;
              if (c?.source === "youtube") post({ type: "load", videoId: c.videoId });
              post({ type: "volume", volume });
            }}
          />
        </div>
      )}
    </FMContext.Provider>
  );
}
