import { useEffect, useState } from "react";
import { isRunningInTauri } from "../services/nativeBridge";

/**
 * Custom window chrome.
 *
 * The native Windows title bar sat above the launcher as a mismatched grey
 * strip. `decorations: false` removes it and Breeze draws its own controls, so
 * the window is one continuous surface.
 *
 * Two things the previous version got wrong, both silent:
 *  - It used `WebkitAppRegion: "drag"`, which is Electron's API. Tauri uses the
 *    `data-tauri-drag-region` attribute, so the window could not be moved.
 *  - It read `window.__TAURI__`, which only exists when `withGlobalTauri` is
 *    enabled in tauri.conf.json. It isn't, so every button was a no-op.
 *    The window API is imported directly instead.
 */
export default function TitleBar({ title = "Breeze Client" }) {
  const [win, setWin] = useState(null);
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    if (!isRunningInTauri()) return undefined;
    let alive = true;
    let unlisten;

    (async () => {
      try {
        const { getCurrentWindow } = await import("@tauri-apps/api/window");
        const current = getCurrentWindow();
        if (!alive) return;
        setWin(current);
        setMaximized(await current.isMaximized().catch(() => false));
        // Keep the restore glyph correct when the user double-clicks the bar
        // or snaps the window with the keyboard.
        unlisten = await current.onResized(async () => {
          if (!alive) return;
          setMaximized(await current.isMaximized().catch(() => false));
        });
      } catch (error) {
        // Never swallow this silently. If the window API is unreachable the
        // controls are dead and the user has no way to move or close the app,
        // so it has to be visible in the console at minimum.
        console.error("[Breeze/TitleBar] window API unavailable:", error);
      }
    })();

    return () => { alive = false; unlisten?.(); };
  }, []);

  /**
   * Window commands are gated by Tauri v2's capability system. Missing a
   * permission in capabilities/default.json makes the call reject rather than
   * throw at import time, which is exactly how these buttons appeared wired but
   * did nothing. Logging the rejection makes that failure mode obvious.
   */
  const run = async (label, fn) => {
    if (!win) return;
    try {
      await fn();
    } catch (error) {
      console.error(`[Breeze/TitleBar] ${label} failed:`, error);
    }
  };

  const toggle = () => run("toggleMaximize", async () => {
    await win.toggleMaximize();
    setMaximized(await win.isMaximized().catch(() => false));
  });

  return (
    <div className="titlebar" data-tauri-drag-region onDoubleClick={toggle}>
      <div className="titlebar-brand" data-tauri-drag-region>
        <img className="titlebar-mark" src="/wind_charge.png" alt="" draggable={false} />
        {title}
      </div>
      {win && (
        // stopPropagation guarantees a button press is never consumed by the
        // parent drag region, regardless of how Tauri matches the attribute.
        // This overlap is what makes drag and click fight each other.
        <div className="titlebar-controls" onMouseDown={(event) => event.stopPropagation()}>
          <button className="win-btn" title="Minimise" aria-label="Minimise" onClick={() => run("minimize", () => win.minimize())}>
            <svg viewBox="0 0 12 12" aria-hidden="true"><rect x="2" y="5.6" width="8" height="1" fill="currentColor" /></svg>
          </button>
          <button className="win-btn" title={maximized ? "Restore" : "Maximise"} aria-label={maximized ? "Restore" : "Maximise"} onClick={toggle}>
            {maximized ? (
              <svg viewBox="0 0 12 12" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1">
                <rect x="2" y="4" width="6" height="6" />
                <path d="M4 4V2h6v6H8" />
              </svg>
            ) : (
              <svg viewBox="0 0 12 12" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1">
                <rect x="2.5" y="2.5" width="7" height="7" />
              </svg>
            )}
          </button>
          <button className="win-btn close" title="Close" aria-label="Close" onClick={() => run("close", () => win.close())}>
            <svg viewBox="0 0 12 12" aria-hidden="true" fill="none" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round">
              <path d="M3.2 3.2l5.6 5.6M8.8 3.2l-5.6 5.6" />
            </svg>
          </button>
        </div>
      )}
    </div>
  );
}
