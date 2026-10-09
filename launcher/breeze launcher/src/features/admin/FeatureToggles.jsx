import { useState, useEffect, useCallback } from "react";

const TOGGLES = [
  { id: "mods_disabled",    label: "Mods",     desc: "Disable the Mods section for all users" },
  { id: "store_disabled",   label: "Store",    desc: "Disable the Marketplace for all users" },
  { id: "hosting_disabled", label: "Hosting",  desc: "Disable Server Hosting for all users" },
  { id: "chat_disabled",    label: "Chat",     desc: "Disable Friend messaging globally" },
  { id: "gifting_disabled", label: "Gifting",  desc: "Disable Gift Center for all users" },
  { id: "radio_disabled",   label: "FM Radio", desc: "Disable Breeze FM for all users" },
  { id: "rewards_disabled", label: "Rewards",  desc: "Disable Ads & Rewards section globally" },
];

const API_BASE = import.meta.env.VITE_BREEZE_API_URL || "https://api.breezeclient.net";

async function fetchFlags(token) {
  const r = await fetch(`${API_BASE}/admin/feature-flags`, { headers: { Authorization: `Bearer ${token}` } });
  if (!r.ok) throw new Error(`${r.status}`);
  return r.json();
}

async function setFlag(token, flag, value) {
  const r = await fetch(`${API_BASE}/admin/feature-flags`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ [flag]: value }),
  });
  if (!r.ok) throw new Error(`${r.status}`);
  return r.json();
}

export default function FeatureToggles({ token, notify, featureFlags, setFeatureFlags }) {
  const [busy, setBusy] = useState({});
  const [syncing, setSyncing] = useState(false);

  const refresh = useCallback(async () => {
    if (!token) return;
    setSyncing(true);
    try {
      const data = await fetchFlags(token);
      setFeatureFlags(data?.flags || data || {});
    } catch {
      /* gracefully fail, flags stay as cached */
    } finally {
      setSyncing(false);
    }
  }, [token, setFeatureFlags]);

  useEffect(() => {
    refresh();
    const timer = setInterval(refresh, 30000);
    return () => clearInterval(timer);
  }, [refresh]);

  const toggle = async (id) => {
    const current = !!featureFlags?.[id];
    setBusy((b) => ({ ...b, [id]: true }));
    const optimistic = { ...featureFlags, [id]: !current };
    setFeatureFlags(optimistic);
    try {
      const data = await setFlag(token, id, !current);
      setFeatureFlags(data?.flags || data || optimistic);
      notify("ok", `${id.replace("_disabled", "")} ${!current ? "disabled" : "enabled"} globally`);
    } catch (err) {
      setFeatureFlags(featureFlags); // revert
      notify("!", `Could not update flag: ${err.message}. Add /admin/feature-flags to server.js.`);
    } finally {
      setBusy((b) => ({ ...b, [id]: false }));
    }
  };

  return (
    <div className="sg">
      <div className="sgl" style={{ display: "flex", alignItems: "center", gap: 8 }}>
        Feature Toggles
        <span style={{ fontSize: 9, color: "var(--text-faint)", fontWeight: 600 }}>
          {syncing ? "syncing…" : "live · 30s refresh"}
        </span>
        <button className="btn" style={{ marginLeft: "auto", fontSize: 10, padding: "3px 8px" }} onClick={refresh}>Refresh</button>
      </div>
      <div className="scd">
        {TOGGLES.map(({ id, label, desc }) => {
          const disabled = !!featureFlags?.[id];
          return (
            <div key={id} className="sr">
              <div className="si">
                <div className="sn" style={{ display: "flex", alignItems: "center", gap: 7 }}>
                  {label}
                  {disabled && <span style={{ fontSize: 9, fontWeight: 800, color: "rgba(255,100,100,.9)", background: "rgba(255,80,80,.12)", border: "1px solid rgba(255,80,80,.22)", padding: "1px 5px", borderRadius: 5 }}>DISABLED</span>}
                </div>
                <div className="sd">{desc}</div>
              </div>
              <button
                className={`mod-toggle ${disabled ? "" : "on"}`}
                style={{ minWidth: 72 }}
                onClick={() => toggle(id)}
                disabled={busy[id]}
                title={disabled ? `Enable ${label}` : `Disable ${label} for all users`}
              >
                <span className="mod-toggle-knob" />
                <span className="mod-toggle-text">{disabled ? "Off" : "On"}</span>
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
