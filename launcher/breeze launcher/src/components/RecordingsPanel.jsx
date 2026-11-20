import { useCallback, useEffect, useState } from "react";
import {
  deleteRecording,
  installFfmpeg,
  listRecordings,
  openRecordingsFolder,
  playVideo,
  requestOpenRecording,
  revealRecordingEntry,
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
  if (!unix) return "";
  return new Date(unix * 1000).toLocaleString();
}

function fmtLength(ms) {
  if (!ms) return "";
  const s = Math.round(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${r}` : `${m}:${r}`;
}

const btn = {
  border: "1px solid rgba(255,255,255,0.1)",
  background: "rgba(255,255,255,0.05)",
  color: "rgba(225,235,252,0.92)",
  borderRadius: 7,
  padding: "4px 8px",
  fontSize: 11,
  cursor: "pointer",
};

/**
 * The Recordings panel (1.2.3): recordings made with Breeze's in-game
 * recorder, and the videos exported from them, for the selected instance.
 * A recording opens in the game (the instance starts and plays it); a video
 * plays in the computer's own player.
 */
export default function RecordingsPanel({ versionId, open, onClose, onLaunch, canLaunch }) {
  const [list, setList] = useState(null);
  const [err, setErr] = useState(null);
  const [notice, setNotice] = useState(null);
  const [confirm, setConfirm] = useState(null);
  const [fetching, setFetching] = useState(false);

  const reload = useCallback(() => {
    if (!versionId) return;
    setErr(null);
    listRecordings(versionId)
      .then(setList)
      .catch((e) => setErr(String(e?.message || e)));
  }, [versionId]);

  useEffect(() => {
    if (open) reload();
  }, [open, reload]);

  if (!open) return null;

  const items = list?.items || [];
  const act = (p) => p.catch((e) => setNotice(String(e?.message || e)));

  async function openInGame(e) {
    try {
      await requestOpenRecording(versionId, e.name);
      setNotice(`Starting Minecraft to watch ${e.name}.`);
      if (onLaunch && canLaunch) await onLaunch();
      else setNotice("The recording opens when this instance next starts.");
    } catch (x) {
      setNotice(String(x?.message || x));
    }
  }

  async function remove(e) {
    setConfirm(null);
    try {
      await deleteRecording(versionId, e);
      setNotice(`Deleted ${e.name}.`);
      reload();
    } catch (x) {
      setNotice(String(x?.message || x));
    }
  }

  async function getFfmpeg() {
    setFetching(true);
    try {
      await installFfmpeg();
      setNotice("ffmpeg is ready: recordings can be exported to videos.");
      reload();
    } catch (x) {
      setNotice(String(x?.message || x));
    } finally {
      setFetching(false);
    }
  }

  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed", inset: 0, background: "rgba(2,4,12,0.55)", backdropFilter: "blur(2px)",
        zIndex: 1100, display: "flex", justifyContent: "flex-end",
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: "min(460px, 94vw)", height: "100%",
          background: "linear-gradient(180deg, rgba(14,18,32,0.96), rgba(8,10,22,0.96))",
          borderLeft: "1px solid rgba(255,255,255,0.08)", boxShadow: "-24px 0 60px rgba(0,0,0,0.4)",
          display: "flex", flexDirection: "column",
        }}
      >
        <div style={{ padding: "18px 20px 14px", borderBottom: "1px solid rgba(255,255,255,0.06)", display: "flex", alignItems: "center", gap: 12 }}>
          <div style={{
            width: 32, height: 32, borderRadius: 9, display: "grid", placeItems: "center",
            background: "rgba(80,140,220,0.16)", border: "1px solid rgba(80,140,220,0.3)", fontSize: 12,
          }}>Rec</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 14, fontWeight: 700, color: "#eaf0ff" }}>Recordings</div>
            <div style={{ fontSize: 11, color: "rgba(180,195,220,0.65)" }}>
              Recorded in the game with the Recorder key, and the videos made from them
            </div>
          </div>
          <button onClick={onClose} style={{ ...btn, width: 28, height: 28, padding: 0 }} aria-label="Close recordings panel">x</button>
        </div>

        <div style={{ padding: "12px 20px", borderBottom: "1px solid rgba(255,255,255,0.04)", display: "flex", gap: 8, flexWrap: "wrap" }}>
          <button
            disabled={!versionId}
            onClick={() => versionId && act(openRecordingsFolder(versionId))}
            style={{ ...btn, padding: "8px 12px", fontSize: 12, fontWeight: 600, borderColor: "rgba(80,140,220,0.4)", background: "rgba(80,140,220,0.14)" }}
          >Open recordings folder</button>
          {list && !list.ffmpeg && (
            <button disabled={fetching} onClick={getFfmpeg} style={{ ...btn, padding: "8px 12px", fontSize: 12 }}>
              {fetching ? "Getting ffmpeg..." : "Get ffmpeg for exporting"}
            </button>
          )}
          {notice && <div style={{ width: "100%", fontSize: 11, color: "rgba(190,205,230,0.8)" }}>{notice}</div>}
        </div>

        <div style={{ flex: 1, overflowY: "auto", padding: "8px 12px" }}>
          {err && (
            <div style={{
              margin: 12, padding: 12, fontSize: 12, color: "rgba(255,160,160,0.85)",
              background: "rgba(255,80,80,0.06)", border: "1px solid rgba(255,80,80,0.2)", borderRadius: 8,
            }}>{err}</div>
          )}
          {!err && items.length === 0 && (
            <div style={{ padding: 32, fontSize: 12, textAlign: "center", color: "rgba(180,195,220,0.45)" }}>
              {list ? "No recordings yet. Press the Recorder key (F9) in a world to record." : "Loading..."}
            </div>
          )}
          {items.map((e) => {
            const video = e.kind === "video";
            const detail = video
              ? ["Video", fmtLength(e.duration_ms), fmtSize(e.size_bytes), fmtTime(e.modified_unix)]
              : [e.world || "Recording", e.minecraft && `Minecraft ${e.minecraft}`, fmtLength(e.duration_ms) || (e.finished ? "" : "cut short"),
                 fmtSize(e.size_bytes), fmtTime(e.modified_unix)];
            return (
              <div key={`${e.kind}:${e.name}`} style={{
                padding: 8, margin: "6px 0", borderRadius: 9, border: "1px solid rgba(255,255,255,0.05)",
                background: "rgba(255,255,255,0.025)", display: "flex", gap: 10,
              }}>
                <div style={{
                  width: 112, height: 63, borderRadius: 6, flexShrink: 0, overflow: "hidden",
                  background: "rgba(80,140,220,0.12)", display: "grid", placeItems: "center",
                  fontSize: 10, color: "rgba(180,200,230,0.6)",
                }}>
                  {e.thumbnail ? <img src={e.thumbnail} alt="" style={{ width: "100%", height: "100%", objectFit: "cover" }} /> : (video ? "Video" : "Recording")}
                </div>
                <div style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 4 }}>
                  <div style={{ fontSize: 12, color: "#eaf0ff", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>{e.name}</div>
                  <div style={{ fontSize: 10, color: "rgba(150,170,200,0.6)" }}>{detail.filter(Boolean).join("  ·  ")}</div>
                  {confirm === e.name ? (
                    <div style={{ display: "flex", gap: 6, alignItems: "center" }}>
                      <span style={{ fontSize: 11, color: "rgba(255,190,190,0.85)" }}>Delete it? This cannot be undone.</span>
                      <button style={btn} onClick={() => remove(e)}>Delete</button>
                      <button style={btn} onClick={() => setConfirm(null)}>Cancel</button>
                    </div>
                  ) : (
                    <div style={{ display: "flex", gap: 6, flexWrap: "wrap" }}>
                      {video
                        ? <button style={btn} onClick={() => act(playVideo(list, e))}>Play</button>
                        : <button style={btn} disabled={!versionId} onClick={() => openInGame(e)}>Watch in game</button>}
                      <button style={btn} onClick={() => act(revealRecordingEntry(list, e))}>Show in folder</button>
                      <button style={btn} onClick={() => setConfirm(e.name)}>Delete</button>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
