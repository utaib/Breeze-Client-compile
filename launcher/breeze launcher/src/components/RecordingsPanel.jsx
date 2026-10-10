import { useEffect, useState } from "react";
import {
  getRecordingsInfo,
  openRecordingsFolder,
  revealRecording,
} from "../services/nativeBridge";

function fmtSize(bytes) {
  if (!bytes) return "0 B";
  const u = ["B", "KB", "MB", "GB"];
  let i = 0;
  let n = bytes;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i++; }
  return `${n.toFixed(n >= 10 || i === 0 ? 0 : 1)} ${u[i]}`;
}

function fmtTime(unix) {
  if (!unix) return "-";
  const d = new Date(unix * 1000);
  return d.toLocaleString();
}


export default function RecordingsPanel({ versionId, open, onClose }) {
  const [info, setInfo] = useState(null);
  const [err, setErr] = useState(null);

  useEffect(() => {
    if (!open || !versionId) return;
    let alive = true;
    setErr(null);
    getRecordingsInfo(versionId)
      .then((d) => { if (alive) setInfo(d); })
      .catch((e) => { if (alive) setErr(String(e?.message || e)); });
    return () => { alive = false; };
  }, [open, versionId]);

  if (!open) return null;

  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed",
        inset: 0,
        background: "rgba(2,4,12,0.55)",
        backdropFilter: "blur(2px)",
        zIndex: 1100,
        display: "flex",
        justifyContent: "flex-end",
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: "min(420px, 92vw)",
          height: "100%",
          background: "linear-gradient(180deg, rgba(14,18,32,0.96), rgba(8,10,22,0.96))",
          borderLeft: "1px solid rgba(255,255,255,0.08)",
          boxShadow: "-24px 0 60px rgba(0,0,0,0.4)",
          display: "flex",
          flexDirection: "column",
        }}
      >
        <div style={{
          padding: "18px 20px 14px",
          borderBottom: "1px solid rgba(255,255,255,0.06)",
          display: "flex", alignItems: "center", gap: 12,
        }}>
          <div style={{
            width: 32, height: 32, borderRadius: 9,
            display: "grid", placeItems: "center",
            background: "rgba(80,140,220,0.16)",
            border: "1px solid rgba(80,140,220,0.3)",
            fontSize: 16,
          }}>Rec</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 14, fontWeight: 700, color: "#eaf0ff" }}>Recordings</div>
            <div style={{ fontSize: 11, color: "rgba(180,195,220,0.65)" }}>
              In-game clips and replay buffer flushes
            </div>
          </div>
          <button
            onClick={onClose}
            style={{
              border: "1px solid rgba(255,255,255,0.1)",
              background: "rgba(255,255,255,0.04)",
              color: "rgba(220,230,250,0.9)",
              width: 28, height: 28, borderRadius: 8, cursor: "pointer",
            }}
            aria-label="Close recordings panel"
          >x</button>
        </div>

        <div style={{ padding: "12px 20px", borderBottom: "1px solid rgba(255,255,255,0.04)" }}>
          <button
            disabled={!versionId}
            onClick={() => versionId && openRecordingsFolder(versionId)}
            style={{
              width: "100%",
              padding: "10px 14px",
              border: "1px solid rgba(80,140,220,0.4)",
              background: "linear-gradient(180deg, rgba(80,140,220,0.18), rgba(80,140,220,0.06))",
              color: "#eaf0ff",
              borderRadius: 10,
              cursor: versionId ? "pointer" : "not-allowed",
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            Open recordings folder
          </button>
          {info?.path && (
            <div style={{
              marginTop: 8,
              fontSize: 10,
              color: "rgba(150,170,200,0.55)",
              wordBreak: "break-all",
              fontFamily: "ui-monospace, Menlo, monospace",
            }}>
              {info.path}
            </div>
          )}
        </div>

        <div style={{ flex: 1, overflowY: "auto", padding: "8px 12px" }}>
          {err && (
            <div style={{
              margin: 12, padding: 12, fontSize: 12,
              color: "rgba(255,160,160,0.85)",
              background: "rgba(255,80,80,0.06)",
              border: "1px solid rgba(255,80,80,0.2)",
              borderRadius: 8,
            }}>
              {err}
            </div>
          )}

          {!err && (!info || info.files.length === 0) && (
            <div style={{
              padding: 32, fontSize: 12, textAlign: "center",
              color: "rgba(180,195,220,0.45)",
            }}>
              {info ? "No recordings yet - press the recorder hotkey in-game to capture a clip."
                    : "Loading..."}
            </div>
          )}

          {info?.files?.map((f) => (
            <div
              key={f.name}
              onClick={() => versionId && revealRecording(versionId, f.name)}
              style={{
                padding: "10px 12px",
                margin: "4px 0",
                borderRadius: 8,
                cursor: "pointer",
                border: "1px solid rgba(255,255,255,0.04)",
                background: "rgba(255,255,255,0.02)",
                display: "flex",
                alignItems: "center",
                gap: 10,
              }}
              onMouseEnter={(e) => e.currentTarget.style.background = "rgba(80,140,220,0.10)"}
              onMouseLeave={(e) => e.currentTarget.style.background = "rgba(255,255,255,0.02)"}
            >
              <div style={{
                width: 28, height: 28, borderRadius: 6,
                background: "rgba(80,140,220,0.14)",
                display: "grid", placeItems: "center", fontSize: 12,
              }}>Play</div>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{
                  fontSize: 12, color: "#eaf0ff",
                  whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
                }}>{f.name}</div>
                <div style={{ fontSize: 10, color: "rgba(150,170,200,0.55)" }}>
                  {fmtSize(f.size_bytes)} - {fmtTime(f.modified_unix)}
                </div>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
