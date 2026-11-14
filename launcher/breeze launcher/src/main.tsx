import React from "react";
import ReactDOM from "react-dom/client";
import { ConfirmProvider } from "./components/ConfirmDialog";
import TitleBar from "./components/TitleBar";
import { BreezeFMProvider } from "./features/fm/BreezeFMProvider";
import App from "./App";
// The launcher's typefaces, bundled so they load offline and under the CSP.
import "@fontsource/dm-sans/300.css";
import "@fontsource/dm-sans/300-italic.css";
import "@fontsource/dm-sans/400.css";
import "@fontsource/dm-sans/500.css";
import "@fontsource/dm-sans/600.css";
import "@fontsource/dm-sans/700.css";
import "@fontsource/jetbrains-mono/400.css";
import "@fontsource/jetbrains-mono/500.css";
import "./index.css";

if (!import.meta.env.DEV) {
  window.addEventListener("contextmenu", (e) => e.preventDefault());
  window.addEventListener(
    "keydown",
    (e) => {
      const k = e.key;
      const blocked =
        k === "F12" ||
        ((e.ctrlKey || e.metaKey) && e.shiftKey && (k === "I" || k === "i" || k === "J" || k === "j" || k === "C" || k === "c")) ||
        ((e.ctrlKey || e.metaKey) && (k === "U" || k === "u")) ||
        (e.ctrlKey && (k === "P" || k === "p" || k === "S" || k === "s"));
      if (blocked) {
        e.preventDefault();
        e.stopPropagation();
      }
    },
    { capture: true },
  );
}

type RootCrashBoundaryState = {
  message: string | null;
  stack?: string | null;
};

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#039;");
}

function renderStartupCrash(message: string, stack?: string) {
  const root = document.getElementById("root");
  if (!root) {
    return;
  }

  const stackHtml = import.meta.env.DEV && stack
    ? `<details style="margin-top:14px;"><summary style="cursor:pointer;font-size:11px;color:#6b7280;letter-spacing:0.06em;text-transform:uppercase;">Stack trace</summary><pre style="margin-top:8px;padding:12px;background:rgba(15,23,42,0.05);border:1px solid rgba(15,23,42,0.08);border-radius:6px;font-size:11px;color:#1f2937;white-space:pre-wrap;word-break:break-word;max-height:240px;overflow:auto;">${escapeHtml(stack)}</pre></details>`
    : "";
  const body = import.meta.env.DEV
    ? escapeHtml(message)
    : "Breeze hit a startup issue. Restart the launcher, and check the launcher log if it happens again.";

  root.innerHTML = `
    <div style="min-height:100vh;display:grid;place-items:center;padding:32px;background:radial-gradient(circle at top, rgba(255,255,255,0.18), transparent 45%), linear-gradient(180deg, #f7f8fb, #e8edf4);color:#111827;font-family:Inter, 'SF Pro Display', 'Segoe UI', system-ui, sans-serif;">
      <div style="width:min(640px,100%);padding:24px;border-radius:12px;border:1px solid rgba(17,24,39,0.08);background:rgba(255,255,255,0.8);box-shadow:0 24px 60px rgba(15,23,42,0.14);">
        <p style="margin:0;font-size:12px;font-weight:700;letter-spacing:0.08em;text-transform:uppercase;color:#4b5563;">Breeze Launcher</p>
        <h1 style="margin:10px 0 8px;font-size:28px;line-height:1.05;">Breeze could not finish starting.</h1>
        <p style="margin:0;color:#374151;line-height:1.6;">${body}</p>
        ${stackHtml}
      </div>
    </div>
  `;
}

window.addEventListener("error", (event) => {
  console.error("[Breeze/WindowError]", event.error ?? event.message);
  if (document.querySelector(".root") && !import.meta.env.DEV) {
    return;
  }
  renderStartupCrash(
    event.error?.message || event.message || "Unknown window startup error",
    event.error?.stack || undefined,
  );
});

window.addEventListener("unhandledrejection", (event) => {
  const reason =
    event.reason instanceof Error
      ? event.reason.message
      : typeof event.reason === "string"
        ? event.reason
        : "Unknown promise rejection";
  const stack = event.reason instanceof Error ? event.reason.stack : undefined;
  console.error("[Breeze/UnhandledRejection]", event.reason);
  if (document.querySelector(".root") && !import.meta.env.DEV) {
    return;
  }
  renderStartupCrash(reason, stack || undefined);
});

class RootCrashBoundary extends React.Component<
  React.PropsWithChildren,
  RootCrashBoundaryState
> {
  override state: RootCrashBoundaryState = {
    message: null,
  };

  static getDerivedStateFromError(error: Error): RootCrashBoundaryState {
    return {
      message: error.message || "Unknown frontend error",
      stack: error.stack || null,
    };
  }

  override componentDidCatch(error: Error, info: React.ErrorInfo) {
    console.error("[Breeze/CrashBoundary]", error, info);
  }

  override render() {
    if (this.state.message) {
      return (
        <div
          style={{
            minHeight: "100vh",
            display: "grid",
            placeItems: "center",
            padding: "32px",
            background:
              "radial-gradient(circle at top, rgba(255,255,255,0.18), transparent 45%), linear-gradient(180deg, #f7f8fb, #e8edf4)",
            color: "#111827",
            fontFamily:
              'Inter, "SF Pro Display", "Segoe UI", system-ui, sans-serif',
          }}
        >
          <div
            style={{
              width: "min(640px, 100%)",
              padding: "24px",
              borderRadius: "12px",
              border: "1px solid rgba(17,24,39,0.08)",
              background: "rgba(255,255,255,0.8)",
              boxShadow: "0 24px 60px rgba(15,23,42,0.14)",
            }}
          >
            <p
              style={{
                margin: 0,
                fontSize: "12px",
                fontWeight: 700,
                letterSpacing: "0.08em",
                textTransform: "uppercase",
                color: "#4b5563",
              }}
            >
              Breeze Launcher
            </p>
            <h1
              style={{
                margin: "10px 0 8px",
                fontSize: "28px",
                lineHeight: 1.05,
              }}
            >
              Breeze could not finish rendering.
            </h1>
            <p
              style={{
                margin: 0,
                color: "#374151",
                lineHeight: 1.6,
              }}
            >
              {import.meta.env.DEV
                ? this.state.message
                : "A launcher panel failed to render. Restart Breeze, and check the launcher log if this repeats."}
            </p>
            {import.meta.env.DEV && this.state.stack && (
              <details style={{ marginTop: 14 }}>
                <summary
                  style={{
                    cursor: "pointer",
                    fontSize: "11px",
                    color: "#6b7280",
                    letterSpacing: "0.06em",
                    textTransform: "uppercase",
                  }}
                >
                  Stack trace
                </summary>
                <pre
                  style={{
                    marginTop: 8,
                    padding: 12,
                    background: "rgba(15,23,42,0.05)",
                    border: "1px solid rgba(15,23,42,0.08)",
                    borderRadius: 6,
                    fontSize: 11,
                    color: "#1f2937",
                    whiteSpace: "pre-wrap",
                    wordBreak: "break-word",
                    maxHeight: 240,
                    overflow: "auto",
                  }}
                >
                  {this.state.stack}
                </pre>
              </details>
            )}
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <RootCrashBoundary>
    {/* Provides the await-able Breeze confirmation dialog to every page,
        replacing window.confirm's unstyled OS chrome. */}
    <ConfirmProvider>
      {/* Owns the single <audio> element, above the router. Music therefore
          survives navigation between tabs; only an explicit pause stops it. */}
      <BreezeFMProvider>
        {/* Native decorations are disabled, so Breeze draws its own title bar.
            It sits outside App so it is present on the login gate too. */}
        <div className="app-shell">
          <TitleBar />
          <div className="app-shell-body">
            <App />
          </div>
        </div>
      </BreezeFMProvider>
    </ConfirmProvider>
  </RootCrashBoundary>
);
