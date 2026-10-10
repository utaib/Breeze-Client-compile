import { useEffect, useMemo, useRef, useState } from "react";

/**
 * Live launch console.
 *
 * Before this existed, a failed launch surfaced only as "exit code 1" with no
 * way for a user or for support to see why. The Rust side already piped the
 * JVM's stdout and stderr through the launch-log event; nothing rendered it.
 *
 * Every line arriving here has already been redacted in Rust by
 * sanitize_log_line, at the point logs are emitted rather than in this
 * component, so nothing sensitive crosses the IPC boundary in the first place.
 * Filtering here would be too late.
 */
const LEVELS = [
  { id: "all", label: "All" },
  { id: "error", label: "Errors" },
  { id: "warn", label: "Warnings" },
];

function classify(line) {
  const t = line.toLowerCase();
  if (t.includes("exception") || t.includes("error") || t.includes("caused by") || t.includes("fatal")) return "error";
  if (t.includes("warn")) return "warn";
  if (line.startsWith("[")) return "stage";
  return "info";
}

export default function ConsolePage({ logs, launchState, versionId }) {
  const lines = Array.isArray(logs) ? logs : [];
  const [level, setLevel] = useState("all");
  const [follow, setFollow] = useState(true);
  const [copied, setCopied] = useState(false);
  const endRef = useRef(null);
  const boxRef = useRef(null);

  const visible = useMemo(
    () => (level === "all" ? lines : lines.filter((l) => classify(l) === level)),
    [lines, level],
  );

  const counts = useMemo(() => {
    let errors = 0;
    let warnings = 0;
    for (const l of lines) {
      const c = classify(l);
      if (c === "error") errors++;
      else if (c === "warn") warnings++;
    }
    return { errors, warnings };
  }, [lines]);

  // Auto-scroll only while the user has not scrolled up to read something.
  useEffect(() => {
    if (follow) endRef.current?.scrollIntoView({ block: "end" });
  }, [visible.length, follow]);

  const onScroll = () => {
    const el = boxRef.current;
    if (!el) return;
    const atBottom = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
    setFollow(atBottom);
  };

  const copyAll = async () => {
    try {
      await navigator.clipboard.writeText(lines.join("\n"));
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch { /* clipboard unavailable, button simply does nothing visible */ }
  };

  return (
    <div className="sv page-enter">
      <div className="vtl">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" style={{ width: 18, height: 18 }}>
          <polyline points="4 17 10 11 4 5" /><line x1="12" y1="19" x2="20" y2="19" />
        </svg>
        Console
      </div>

      <div className="console-toolbar">
        <div className="console-status">
          <span className={`console-dot ${launchState?.status || "idle"}`} />
          <span className="console-status-text">
            {launchState?.status === "launching"
              ? launchState.message || "Launching..."
              : launchState?.status === "error"
                ? "Last launch failed"
                : lines.length
                  ? "Last launch log"
                  : "Nothing launched yet"}
          </span>
          {versionId && <span className="console-version">{versionId}</span>}
        </div>
        <div className="console-actions">
          {LEVELS.map((l) => (
            <button
              key={l.id}
              className={`console-filter ${level === l.id ? "on" : ""}`}
              onClick={() => setLevel(l.id)}
            >
              {l.label}
              {l.id === "error" && counts.errors > 0 && <span className="console-count err">{counts.errors}</span>}
              {l.id === "warn" && counts.warnings > 0 && <span className="console-count warn">{counts.warnings}</span>}
            </button>
          ))}
          <button className="console-filter" onClick={copyAll} disabled={!lines.length}>
            {copied ? "Copied" : "Copy log"}
          </button>
        </div>
      </div>

      <div className="console-box" ref={boxRef} onScroll={onScroll}>
        {visible.length === 0 ? (
          <div className="console-empty">
            {lines.length
              ? `No ${level} lines in this launch.`
              : "Press Launch on the Play page. Every step of the launch appears here, including the real error if it fails."}
          </div>
        ) : (
          visible.map((line, index) => (
            <div key={index} className={`console-line ${classify(line)}`}>
              <span className="console-gutter">{index + 1}</span>
              <span className="console-text">{line}</span>
            </div>
          ))
        )}
        <div ref={endRef} />
      </div>

      {!follow && (
        <button className="console-jump" onClick={() => { setFollow(true); endRef.current?.scrollIntoView({ block: "end" }); }}>
          Jump to latest
        </button>
      )}

      <div className="console-note">
        Usernames, file paths, IP addresses and access tokens are removed before
        anything reaches this view, so this log is safe to share when asking for help.
      </div>
    </div>
  );
}
