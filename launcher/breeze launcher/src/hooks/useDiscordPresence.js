import { useEffect, useRef } from "react";
import { buildPresence, clearPresence, presenceKey, pushPresence } from "../services/discordPresence";

/**
 * Keep Discord showing what the user is actually doing.
 *
 * Two rules shape this:
 *
 * Updates are sent the moment state changes, not on a timer, so switching tabs
 * is reflected immediately rather than up to N seconds later. A timer would also
 * be the thing that leaves a stale activity on screen when the launcher goes
 * quiet, which is exactly the "stuck on an old state" failure to avoid.
 *
 * Identical states are dropped. Music progress ticks every second, and resending
 * the same activity each tick would hammer Discord's rate limiter, which
 * responds by ignoring updates and freezing the presence at whatever it last
 * accepted. The key deliberately excludes progress: `start`/`end` are absolute
 * timestamps, so Discord animates the bar itself and only genuinely new tracks
 * need a resend.
 */
export default function useDiscordPresence({ page, launchStatus, music, enabled = true }) {
  const lastKeyRef = useRef("");
  // Fixed for the life of the process, so Discord shows one continuous session
  // rather than resetting the elapsed timer on every navigation.
  const sessionStartRef = useRef(Math.floor(Date.now() / 1000));

  useEffect(() => {
    if (!enabled) return;

    const presence = buildPresence({
      page,
      launch: launchStatus,
      music,
      sessionStart: sessionStartRef.current,
    });

    const key = presenceKey(presence);
    if (key === lastKeyRef.current) return;
    lastKeyRef.current = key;
    pushPresence(presence);
  }, [
    enabled,
    page,
    launchStatus,
    music?.playing,
    music?.title,
    music?.artist,
    music?.duration,
  ]);

  // Clear on unmount and on window close. The Rust side also clears when the
  // window closes, because an unmount does not run if the process is killed.
  useEffect(() => {
    const onUnload = () => { clearPresence(); };
    window.addEventListener("beforeunload", onUnload);
    return () => {
      window.removeEventListener("beforeunload", onUnload);
      clearPresence();
    };
  }, []);
}
