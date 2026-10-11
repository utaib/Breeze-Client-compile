import { useCallback, useEffect, useMemo, useRef, useState } from "react";

/**
 * The startup animation.
 *
 * Twelve scenes ship and one is chosen per launch, so the launcher does not look
 * identical on launch #1 and launch #400. The scenes themselves live in
 * public/splash/, generated from the design files by scripts/build-splash.cjs.
 *
 * They are mounted in an iframe rather than ported into React, for three
 * reasons that all bite otherwise: every scene declares the same element ids
 * (#cv, #win, #scaler), none of them stop their requestAnimationFrame loop, and
 * none disconnect their ResizeObserver. An iframe gives each one its own
 * document, and removing the element tears down the globals, the loop and the
 * observers together. It also keeps the design files as the single source of
 * truth instead of creating twelve copies that drift.
 */

/** Must match --dur-dissolve on .bz-splash-host.leaving, or the splash stays
 *  mounted and invisible after the dissolve has already finished. */
const DISSOLVE_MS = 420;
/** The longest dwell any scene has before its exit starts, in real time (the
 *  scenes run at 0.6x). Used only to stop waiting on a scene that never answers. */
const SCENE_MAX_DWELL_MS = 12000;
/** How long an exit may take once it has been asked for, before giving up on
 *  the scene's own messages. */
const EXIT_FALLBACK_MS = 2500;

const RECENT_KEY = "breeze.splash.recent";
const LAUNCHED_KEY = "breeze.splash.hasLaunched";
/** Shown to a brand-new user. A first impression is too important to shuffle. */
const FIRST_RUN_SCENE = "tunnel";
/** How many previous picks to exclude, so "random" does not feel broken. */
const NO_REPEAT_WINDOW = 2;

function readRecent() {
  try {
    const raw = JSON.parse(localStorage.getItem(RECENT_KEY) || "[]");
    return Array.isArray(raw) ? raw.filter((x) => typeof x === "string") : [];
  } catch {
    return [];
  }
}

function rememberPick(id) {
  try {
    const next = [...readRecent(), id].slice(-NO_REPEAT_WINDOW);
    localStorage.setItem(RECENT_KEY, JSON.stringify(next));
    localStorage.setItem(LAUNCHED_KEY, "1");
  } catch {
    // A launcher that cannot write a preference still has to start.
  }
}

/**
 * Choose a scene.
 *
 * Weighted rather than uniform, because the scenes are not equally strong: one
 * you see occasionally is a treat, the same one every fourth launch is a chore.
 * Scenes matching the user's actual time of day are twice as likely, which costs
 * one getHours() call and makes the shuffle feel like it knows something.
 */
export function pickScene(scenes, now = new Date(), recent = readRecent(), firstRun = false) {
  if (!scenes.length) return null;
  if (firstRun) {
    const opener = scenes.find((s) => s.id === FIRST_RUN_SCENE);
    if (opener) return opener;
  }

  const hour = now.getHours();
  const daytime = hour >= 7 && hour < 19;

  // Excluding recents can empty the pool if few scenes built; fall back to the
  // full list rather than returning nothing and showing a blank splash.
  let pool = scenes.filter((s) => !recent.includes(s.id));
  if (!pool.length) pool = scenes;

  const weighted = pool.map((s) => ({
    scene: s,
    w: Math.max(1, s.weight || 1) * ((s.key === "day") === daytime ? 2 : 1),
  }));

  const total = weighted.reduce((sum, x) => sum + x.w, 0);
  let roll = Math.random() * total;
  for (const x of weighted) {
    roll -= x.w;
    if (roll <= 0) return x.scene;
  }
  return weighted[weighted.length - 1].scene;
}

/**
 * @param ready  True once the launcher behind the splash has what it needs to
 *   show its first real screen: the saved session has been restored (or there
 *   is none) and settings and the version list have loaded. The scene holds its
 *   ambient loop until then, so the hand-off lands on a loaded UI instead of on
 *   loading placeholders.
 */
export default function SplashScene({ onDone, ready = true, maxMs = 16000 }) {
  const [scene, setScene] = useState(null);
  const [leaving, setLeaving] = useState(false);
  const frameRef = useRef(null);
  const doneRef = useRef(false);
  const readyRef = useRef(ready);
  readyRef.current = ready;
  const onDoneRef = useRef(onDone);
  onDoneRef.current = onDone;

  const reduceMotion = useMemo(
    () => typeof matchMedia === "function" && matchMedia("(prefers-reduced-motion: reduce)").matches,
    [],
  );

  // Dissolve the window, then unmount. Idempotent: the scene's messages, the
  // fallbacks and the hard stop can all ask for it.
  const dissolve = useCallback(() => {
    if (doneRef.current) return;
    doneRef.current = true;
    setLeaving(true);
    setTimeout(() => onDoneRef.current?.(), reduceMotion ? 0 : DISSOLVE_MS);
  }, [reduceMotion]);

  const tellSceneReady = useCallback(() => {
    try {
      frameRef.current?.contentWindow?.postMessage({ type: "breeze-splash", event: "ready" }, "*");
    } catch {
      // A scene that cannot be messaged still ends through the fallbacks below.
    }
  }, []);

  // Pick and load a scene. A splash must never be the reason the launcher does
  // not open, so every failure path dissolves rather than leaving a dead screen.
  useEffect(() => {
    let cancelled = false;
    if (reduceMotion) {
      dissolve();
      return () => { cancelled = true; };
    }
    fetch("/splash/scenes.json")
      .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
      .then((scenes) => {
        if (cancelled) return;
        const firstRun = !localStorage.getItem(LAUNCHED_KEY);
        const chosen = pickScene(scenes, new Date(), readRecent(), firstRun);
        if (!chosen) { dissolve(); return; }
        rememberPick(chosen.id);
        setScene(chosen);
      })
      .catch(dissolve);

    // Past this the launcher opens whatever state it is in and shows its own
    // loading or error UI; a splash that never ends reads as a hang.
    const hardStop = setTimeout(() => {
      tellSceneReady();
      setTimeout(dissolve, EXIT_FALLBACK_MS);
    }, maxMs);
    return () => {
      cancelled = true;
      clearTimeout(hardStop);
    };
  }, [maxMs, reduceMotion, dissolve, tellSceneReady]);

  // The scene reports when its exit starts and when it has finished. The
  // window dissolves over the last part of the exit, so the lockup has already
  // blown away and the launcher comes up underneath.
  useEffect(() => {
    if (!scene) return undefined;
    let exitTimer = null;
    let fallbackTimer = null;
    const onMessage = (event) => {
      if (event.source !== frameRef.current?.contentWindow) return;
      const data = event.data;
      if (!data || data.type !== "breeze-splash") return;
      if (data.event === "loaded" && readyRef.current) tellSceneReady();
      if (data.event === "exiting") {
        const ms = Math.max(0, Math.min(Number(data.detail?.ms) || 0, 3000));
        clearTimeout(exitTimer);
        exitTimer = setTimeout(dissolve, ms * 0.7);
      }
      if (data.event === "exited") dissolve();
    };
    window.addEventListener("message", onMessage);
    return () => {
      window.removeEventListener("message", onMessage);
      clearTimeout(exitTimer);
      clearTimeout(fallbackTimer);
    };
  }, [scene, dissolve, tellSceneReady]);

  // Readiness can arrive before or after the scene has loaded; both orders work.
  // If the scene never answers once told, the fallback still dissolves it.
  useEffect(() => {
    if (!scene || !ready) return undefined;
    tellSceneReady();
    const fallback = setTimeout(dissolve, SCENE_MAX_DWELL_MS + EXIT_FALLBACK_MS);
    return () => clearTimeout(fallback);
  }, [scene, ready, dissolve, tellSceneReady]);

  if (reduceMotion) return null;

  return (
    <div className={`bz-splash-host${leaving ? " leaving" : ""}`} aria-hidden="true">
      {scene && (
        <iframe
          ref={frameRef}
          key={scene.id}
          className="bz-splash-frame"
          src={`/splash/${scene.id}.html`}
          title=""
          tabIndex={-1}
          scrolling="no"
          onLoad={() => { if (readyRef.current) tellSceneReady(); }}
          // No allow-scripts escape hatch beyond what the scene needs: these are
          // first-party files, but the sandbox keeps a scene from reaching the
          // launcher document if one is ever edited carelessly.
          sandbox="allow-scripts"
        />
      )}
    </div>
  );
}
