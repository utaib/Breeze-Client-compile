import { useEffect, useRef } from "react";

/**
 * setInterval that stops while the launcher is in the background.
 *
 * The launcher keeps several long-lived polls running (platform panels, wallet,
 * presence, feature flags). Left as plain intervals they keep firing while the
 * window is minimised or the user is in-game, burning CPU, network and battery
 * for data nobody is looking at. This pauses them when the document is hidden
 * and fires once immediately on the way back so the UI is never stale.
 *
 * @param callback  invoked on each tick; always sees the latest closure
 * @param delayMs   tick interval, or null/0 to disable
 * @param options.immediate  run once as soon as the poll becomes active
 */
export default function usePolling(callback, delayMs, { immediate = false } = {}) {
  const saved = useRef(callback);
  saved.current = callback;

  useEffect(() => {
    if (!delayMs) return undefined;

    let timer = null;
    const tick = () => saved.current?.();

    const start = (runNow) => {
      if (timer) return;
      if (runNow) tick();
      timer = setInterval(tick, delayMs);
    };
    const stop = () => {
      if (!timer) return;
      clearInterval(timer);
      timer = null;
    };

    const onVisibility = () => {
      // Coming back from hidden: refresh straight away, then resume ticking.
      if (document.hidden) stop();
      else start(true);
    };

    if (!document.hidden) start(immediate);
    document.addEventListener("visibilitychange", onVisibility);
    return () => {
      document.removeEventListener("visibilitychange", onVisibility);
      stop();
    };
  }, [delayMs, immediate]);
}
