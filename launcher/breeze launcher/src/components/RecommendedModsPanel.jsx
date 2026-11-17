import { useMemo, useState } from "react";
import {
  RECOMMENDED_MODS,
  installableRecommended,
} from "../services/recommendedMods";
import { installModrinthMod } from "../services/nativeBridge";

const CAT_LABEL = {
  performance: "Performance",
  visual: "Visual",
  ui: "UI / HUD",
  utility: "Utility",
  compatibility: "Library / API",
  social: "Replay / Social",
};


export default function RecommendedModsPanel({
  open,
  onClose,
  versionId,
  loaderVersion,
  notify,
}) {
  const [installing, setInstalling] = useState(false);
  const [progress, setProgress] = useState({ done: 0, total: 0, current: "" });
  const [installed, setInstalled] = useState(new Set());
  const [failed, setFailed] = useState(new Set());

  const groups = useMemo(() => {
    const out = {};
    for (const m of RECOMMENDED_MODS) {
      (out[m.category] ||= []).push(m);
    }
    return out;
  }, []);

  if (!open) return null;

  const installable = installableRecommended();

  async function installAll() {
    if (!versionId) {
      notify?.("!", "Pick a Minecraft version first.");
      return;
    }
    setInstalling(true);
    setProgress({ done: 0, total: installable.length, current: "" });
    const okSet = new Set(installed);
    const failSet = new Set(failed);
    for (let i = 0; i < installable.length; i++) {
      const mod = installable[i];
      setProgress({ done: i, total: installable.length, current: mod.name });
      try {
        // Only the slug is known here. Sending it as the project id as well is
        // how the same mod ended up in mods-state.json twice, once under the
        // slug and once under the hex id a browse install wrote, so the id is
        // left for the Rust side to canonicalise from the slug.
        await installModrinthMod({
          profileId: versionId,
          gameVersion: versionId,
          loader: "fabric",
          projectSlug: mod.slug,
          title: mod.name,
        });
        okSet.add(mod.slug);
      } catch (e) {
        failSet.add(mod.slug);
      }
      setInstalled(new Set(okSet));
      setFailed(new Set(failSet));
    }
    setProgress({ done: installable.length, total: installable.length, current: "" });
    setInstalling(false);
    notify?.("ok", `Recommended mods: ${okSet.size} installed, ${failSet.size} failed`);
  }

  return (
    <div
      onClick={installing ? undefined : onClose}
      style={{
        position: "fixed", inset: 0,
        background: "rgba(2,4,12,0.65)",
        backdropFilter: "blur(3px)",
        zIndex: 1100,
        display: "grid", placeItems: "center",
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: "min(640px, 92vw)",
          maxHeight: "82vh",
          background: "linear-gradient(180deg, rgba(14,18,32,0.97), rgba(8,10,22,0.97))",
          border: "1px solid rgba(255,255,255,0.08)",
          borderRadius: 14,
          boxShadow: "0 24px 60px rgba(0,0,0,0.5)",
          display: "flex", flexDirection: "column",
        }}
      >
        <div style={{
          padding: "16px 20px",
          borderBottom: "1px solid rgba(255,255,255,0.06)",
          display: "flex", alignItems: "center", gap: 12,
        }}>
          <div style={{
            width: 30, height: 30, borderRadius: 8,
            background: "rgba(255,200,80,0.16)",
            border: "1px solid rgba(255,200,80,0.3)",
            display: "grid", placeItems: "center", fontSize: 16,
          }}>M</div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 14, fontWeight: 700, color: "#eaf0ff" }}>
              Recommended Mods
            </div>
            <div style={{ fontSize: 11, color: "rgba(180,195,220,0.65)" }}>
              Breeze's curated performance + QoL set. Install all, or pick what you want.
            </div>
          </div>
          {!installing && (
            <button
              onClick={onClose}
              style={{
                border: "1px solid rgba(255,255,255,0.1)",
                background: "rgba(255,255,255,0.04)",
                color: "rgba(220,230,250,0.9)",
                width: 28, height: 28, borderRadius: 8, cursor: "pointer",
              }}
            >x</button>
          )}
        </div>

        <div style={{
          padding: "12px 20px",
          borderBottom: "1px solid rgba(255,255,255,0.04)",
          display: "flex", gap: 12, alignItems: "center",
        }}>
          <button
            disabled={installing || !versionId}
            onClick={installAll}
            style={{
              flex: 1,
              padding: "10px 14px",
              border: "1px solid rgba(80,180,120,0.4)",
              background: installing
                ? "rgba(80,180,120,0.06)"
                : "linear-gradient(180deg, rgba(80,180,120,0.20), rgba(80,180,120,0.06))",
              color: "#eaf0ff",
              borderRadius: 10,
              cursor: installing || !versionId ? "not-allowed" : "pointer",
              fontSize: 13,
              fontWeight: 600,
            }}
          >
            {installing
              ? `Installing ${progress.done}/${progress.total} - ${progress.current}...`
              : `Install all (${installable.length})`}
          </button>
        </div>

        <div style={{ flex: 1, overflowY: "auto", padding: "8px 12px" }}>
          {Object.keys(CAT_LABEL).map((cat) => {
            const list = groups[cat] || [];
            if (list.length === 0) return null;
            return (
              <div key={cat} style={{ marginBottom: 12 }}>
                <div style={{
                  fontSize: 10, letterSpacing: 1,
                  textTransform: "uppercase",
                  color: "rgba(150,170,200,0.55)",
                  padding: "8px 10px 6px",
                }}>
                  {CAT_LABEL[cat]}
                </div>
                {list.map((m) => {
                  const isInstalled = m.slug && installed.has(m.slug);
                  const didFail = m.slug && failed.has(m.slug);
                  return (
                    <div
                      key={m.name}
                      style={{
                        padding: "8px 10px",
                        margin: "2px 0",
                        borderRadius: 8,
                        border: "1px solid rgba(255,255,255,0.04)",
                        background: isInstalled
                          ? "rgba(80,180,120,0.08)"
                          : didFail
                            ? "rgba(255,80,80,0.06)"
                            : "rgba(255,255,255,0.02)",
                        display: "flex", alignItems: "center", gap: 10,
                      }}
                    >
                      <div style={{
                        width: 22, height: 22, borderRadius: 5,
                        background: m.slug
                          ? "rgba(80,140,220,0.14)"
                          : "rgba(255,255,255,0.04)",
                        display: "grid", placeItems: "center", fontSize: 11,
                        color: m.slug ? "rgba(160,200,255,0.8)" : "rgba(180,195,220,0.45)",
                      }}>
                        {isInstalled ? "On" : didFail ? "Fail" : m.slug ? "Get" : "?"}
                      </div>
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{
                          fontSize: 12, fontWeight: 600, color: "#eaf0ff",
                          whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis",
                        }}>{m.name}</div>
                        <div style={{
                          fontSize: 10, color: "rgba(150,170,200,0.55)",
                        }}>{m.blurb}</div>
                      </div>
                      {!m.slug && (
                        <div style={{
                          fontSize: 9, padding: "2px 6px", borderRadius: 4,
                          background: "rgba(255,255,255,0.05)",
                          color: "rgba(180,195,220,0.55)",
                        }}>search by hand</div>
                      )}
                    </div>
                  );
                })}
              </div>
            );
          })}
        </div>
      </div>
    </div>
  );
}
