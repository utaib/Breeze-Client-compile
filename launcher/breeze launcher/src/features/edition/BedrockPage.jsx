import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { getApiBaseUrl, getNews } from "../../services/breezeApi";
import {
  bedrockAddonInfo,
  bedrockBackupPack,
  bedrockBackupWorld,
  bedrockImport,
  bedrockInstallAddon,
  bedrockLaunch,
  bedrockListBackups,
  bedrockListPacks,
  bedrockListWorlds,
  bedrockRemovePack,
  bedrockRestoreBackup,
  bedrockSetGlobal,
  bedrockStatus,
} from "../../services/nativeBridge";
import { BEDROCK_LIMITS } from "./editions";

/**
 * Bedrock Edition on Windows (1.3.0, docs/PLAN_1.3.0.md): Home (open the
 * game through Windows, the Breeze add-on, the Breeze account and news),
 * Add-ons (import, turn on for every world, back up, remove) and Worlds
 * (back up and restore). Breeze never changes the game's files or version.
 * On macOS and Linux the page says plainly that Bedrock is Windows only.
 */

const TABS = [
  { id: "home", label: "Home" },
  { id: "addons", label: "Add-ons" },
  { id: "worlds", label: "Worlds" },
];

const errText = (e) => String(e?.message || e || "Something went wrong.");

function size(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "0 KB";
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  if (bytes < 1024 ** 3) return `${(bytes / 1024 ** 2).toFixed(1)} MB`;
  return `${(bytes / 1024 ** 3).toFixed(2)} GB`;
}

function date(secs) {
  if (!secs) return "";
  return new Date(secs * 1000).toLocaleDateString(undefined, { day: "numeric", month: "short", year: "numeric" });
}

function importSummary(r) {
  const parts = [];
  if (r.packs?.length) parts.push(r.packs.map((p) => (p.replaced ? `${p.name} updated to ${p.version}` : `${p.name} ${p.version} added`)).join(", "));
  if (r.worlds?.length) parts.push(`world ${r.worlds.join(", ")} added`);
  return parts.length ? `Imported: ${parts.join("; ")}.` : "Nothing was imported.";
}

function Unavailable({ onBack }) {
  return (
    <section className="ed-section" aria-labelledby="bd-unavailable">
      <h2 id="bd-unavailable" className="ed-h2">Not available on this computer</h2>
      <p className="ed-lead">
        Bedrock Edition runs on Windows. macOS and Linux have no official Bedrock Edition, so Breeze offers it on
        Windows PCs only. Java Edition works here as always.
      </p>
      <button type="button" className="modal-buy ed-back" onClick={onBack}>Back to Java Edition</button>
    </section>
  );
}

function NotInstalled({ onRefresh }) {
  return (
    <section className="ed-section" aria-labelledby="bd-missing">
      <h2 id="bd-missing" className="ed-h2">Bedrock is not installed</h2>
      <p className="ed-lead">
        Breeze did not find Minecraft for Windows on this PC. Install it from the Microsoft Store, the Xbox app or the
        Minecraft Launcher and start it once, then check again.
      </p>
      <button type="button" className="btn" onClick={onRefresh}>Check again</button>
    </section>
  );
}

function HomeTab({ install, playerName, breezeVersion, onChanged, say }) {
  const [addon, setAddon] = useState(null);
  const [addonError, setAddonError] = useState("");
  const [busy, setBusy] = useState("");
  const [news, setNews] = useState(null);

  useEffect(() => {
    let live = true;
    bedrockAddonInfo(getApiBaseUrl())
      .then((info) => live && setAddon(info))
      .catch((e) => live && setAddonError(errText(e)));
    getNews()
      .then((items) => live && setNews(items.slice(0, 3)))
      .catch(() => live && setNews([]));
    return () => { live = false; };
  }, []);

  const play = async () => {
    setBusy("play");
    try {
      await bedrockLaunch(install.id);
      say(`Opening ${install.label}.`);
    } catch (e) {
      say(errText(e), true);
    } finally {
      setBusy("");
    }
  };

  const installAddon = async () => {
    setBusy("addon");
    try {
      const r = await bedrockInstallAddon(install.id, getApiBaseUrl());
      say(`${importSummary(r)} Breeze UI is on in every world.`);
      onChanged();
    } catch (e) {
      say(errText(e), true);
    } finally {
      setBusy("");
    }
  };

  const upToDate = addon?.available && breezeVersion && addon.version === breezeVersion;
  return (
    <div className="bd-grid">
      <section className="bd-card bd-play" aria-labelledby="bd-play-h">
        <h2 id="bd-play-h" className="bd-card-h">{install.label}</h2>
        <p className="bd-muted">{install.build === "GDK" ? "Windows build" : "Older Microsoft Store build"}. Breeze opens it through Windows and never changes the game.</p>
        <button type="button" className="modal-buy bd-play-btn" onClick={play} disabled={busy === "play"}>
          {busy === "play" ? "Opening" : "Play Bedrock"}
        </button>
      </section>

      <section className="bd-card" aria-labelledby="bd-addon-h">
        <h2 id="bd-addon-h" className="bd-card-h">Breeze add-on</h2>
        <p className="bd-muted">
          Breeze UI: Breeze colours on the HUD, crosshairs and low fire, in every world and server. Breeze HUD:
          coordinates, time, armour and effects, in worlds where you turn it on.
        </p>
        <p className="bd-line">
          {breezeVersion ? `Installed: ${breezeVersion}` : "Not installed"}
          {addon?.available ? ` · Newest: ${addon.version}` : ""}
        </p>
        {addonError && <p className="bd-muted">{addonError}</p>}
        {addon && !addon.available && <p className="bd-muted">The add-on is not published on Breeze yet.</p>}
        <button type="button" className="btn accent" onClick={installAddon} disabled={!addon?.available || upToDate || busy === "addon"}>
          {busy === "addon" ? "Installing" : upToDate ? "Up to date" : breezeVersion ? "Update the add-on" : "Install the add-on"}
        </button>
      </section>

      <section className="bd-card" aria-labelledby="bd-acct-h">
        <h2 id="bd-acct-h" className="bd-card-h">Your Breeze account</h2>
        <p className="bd-muted">
          {playerName ? `Signed in as ${playerName}.` : "Signed in."} Your friends, themes and layout belong to your
          Breeze account and stay the same in both editions. In game, Bedrock uses your Microsoft account.
        </p>
      </section>

      <section className="bd-card bd-news" aria-labelledby="bd-news-h">
        <h2 id="bd-news-h" className="bd-card-h">News</h2>
        {news === null && <p className="bd-muted">Loading news</p>}
        {news && news.length === 0 && <p className="bd-muted">No announcements right now.</p>}
        {news && news.length > 0 && (
          <ul className="bd-list">
            {news.map((n) => (
              <li key={n.id || n.title}><strong>{n.title}</strong>{n.body ? <span className="bd-muted"> {String(n.body).slice(0, 140)}</span> : null}</li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function AddonsTab({ install, say, onChanged }) {
  const [packs, setPacks] = useState(null);
  const [busy, setBusy] = useState("");
  const [confirm, setConfirm] = useState(null);
  const fileRef = useRef(null);

  const load = useCallback(() => {
    bedrockListPacks(install.id).then(setPacks).catch((e) => { setPacks([]); say(errText(e), true); });
  }, [install.id, say]);
  useEffect(load, [load]);

  const act = async (key, fn) => {
    setBusy(key);
    try {
      await fn();
      load();
      onChanged();
    } catch (e) {
      say(errText(e), true);
    } finally {
      setBusy("");
    }
  };

  const onFile = (ev) => {
    const file = ev.target.files?.[0];
    ev.target.value = "";
    if (!file) return;
    act("import", async () => say(importSummary(await bedrockImport(install.id, file))));
  };

  const groups = useMemo(() => [
    { kind: "resource", title: "Resource packs", note: "Turn one on for every world and server, like the game's Global Resources." },
    { kind: "behavior", title: "Behaviour packs", note: "Turned on per world, in the world's settings in the game." },
  ], []);

  return (
    <div className="bd-stack">
      <div className="bd-row bd-toolbar">
        <p className="bd-muted">Import .mcpack, .mcaddon and .mcworld files. Each is checked before anything is copied, and a pack that is already there is backed up before it is replaced.</p>
        <input ref={fileRef} type="file" accept=".mcpack,.mcaddon,.mcworld" hidden onChange={onFile} aria-label="Bedrock file to import" />
        <button type="button" className="btn accent" onClick={() => fileRef.current?.click()} disabled={busy === "import"}>
          {busy === "import" ? "Importing" : "Import a file"}
        </button>
      </div>
      {packs === null && <p className="bd-muted">Reading packs</p>}
      {packs && groups.map((g) => {
        const list = packs.filter((p) => p.kind === g.kind);
        return (
          <section key={g.kind} className="ed-section" aria-labelledby={`bd-${g.kind}`}>
            <h2 id={`bd-${g.kind}`} className="ed-h2">{g.title} ({list.length})</h2>
            <p className="bd-muted">{g.note}</p>
            {list.length === 0 && <p className="bd-muted">None installed.</p>}
            <ul className="bd-list">
              {list.map((p) => (
                <li key={p.folder} className="bd-item">
                  <div className="bd-item-text">
                    <span className="bd-item-name">{p.name}{p.breeze && <span className="ed-soon">Breeze</span>}</span>
                    <span className="bd-muted">{p.version}{p.description ? ` · ${p.description}` : ""}</span>
                  </div>
                  {p.kind === "resource" && (
                    <label className="bd-global">
                      <span>Every world</span>
                      <button
                        type="button"
                        className={`pill ${p.global ? "on" : ""}`}
                        role="switch"
                        aria-checked={p.global}
                        aria-label={`${p.name} in every world`}
                        disabled={busy !== ""}
                        onClick={() => act(`g-${p.folder}`, () => bedrockSetGlobal(install.id, p.folder, !p.global))}
                      />
                    </label>
                  )}
                  <button type="button" className="btn mod-small" disabled={busy !== ""}
                    onClick={() => act(`b-${p.folder}`, async () => say(`Backed up as ${await bedrockBackupPack(install.id, p.kind, p.folder)}.`))}>
                    Back up
                  </button>
                  {confirm === p.folder ? (
                    <button type="button" className="btn danger mod-small" disabled={busy !== ""}
                      onClick={() => { setConfirm(null); act(`r-${p.folder}`, async () => say(`${p.name} removed. A backup was kept: ${await bedrockRemovePack(install.id, p.kind, p.folder)}.`)); }}>
                      Remove for good
                    </button>
                  ) : (
                    <button type="button" className="btn mod-small" disabled={busy !== ""} onClick={() => setConfirm(p.folder)}>Remove</button>
                  )}
                </li>
              ))}
            </ul>
          </section>
        );
      })}
    </div>
  );
}

function WorldsTab({ install, say }) {
  const [worlds, setWorlds] = useState(null);
  const [backups, setBackups] = useState([]);
  const [busy, setBusy] = useState("");

  const load = useCallback(() => {
    bedrockListWorlds(install.id).then(setWorlds).catch((e) => { setWorlds([]); say(errText(e), true); });
    bedrockListBackups().then(setBackups).catch(() => setBackups([]));
  }, [install.id, say]);
  useEffect(load, [load]);

  const act = async (key, fn) => {
    setBusy(key);
    try {
      await fn();
      load();
    } catch (e) {
      say(errText(e), true);
    } finally {
      setBusy("");
    }
  };

  return (
    <div className="bd-stack">
      <section className="ed-section" aria-labelledby="bd-worlds">
        <h2 id="bd-worlds" className="ed-h2">Worlds ({worlds?.length ?? 0})</h2>
        {worlds === null && <p className="bd-muted">Reading worlds</p>}
        {worlds && worlds.length === 0 && <p className="bd-muted">No worlds yet. Worlds you make in the game appear here.</p>}
        <ul className="bd-list">
          {(worlds || []).map((w) => (
            <li key={w.key} className="bd-item">
              <div className="bd-item-text">
                <span className="bd-item-name">{w.name}</span>
                <span className="bd-muted">{size(w.sizeBytes)} · played {date(w.modifiedSecs)}</span>
              </div>
              <button type="button" className="btn mod-small" disabled={busy !== ""}
                onClick={() => act(`w-${w.key}`, async () => say(`${w.name} backed up as ${await bedrockBackupWorld(install.id, w.key)}.`))}>
                {busy === `w-${w.key}` ? "Backing up" : "Back up"}
              </button>
            </li>
          ))}
        </ul>
      </section>
      <section className="ed-section" aria-labelledby="bd-backups">
        <h2 id="bd-backups" className="ed-h2">Backups ({backups.length})</h2>
        <p className="bd-muted">A restored world comes back next to your worlds and never replaces one. A restored pack replaces the copy that is there now.</p>
        {backups.length === 0 && <p className="bd-muted">No backups yet.</p>}
        <ul className="bd-list">
          {backups.map((b) => (
            <li key={`${b.kind}/${b.file}`} className="bd-item">
              <div className="bd-item-text">
                <span className="bd-item-name">{b.file}</span>
                <span className="bd-muted">{b.kind === "worlds" ? "World" : "Pack"} · {size(b.sizeBytes)} · {date(b.createdSecs)}</span>
              </div>
              <button type="button" className="btn mod-small" disabled={busy !== ""}
                onClick={() => act(`r-${b.file}`, async () => say(importSummary(await bedrockRestoreBackup(install.id, b.kind, b.file))))}>
                Restore
              </button>
            </li>
          ))}
        </ul>
      </section>
    </div>
  );
}

export default function BedrockPage({ onBack, playerName }) {
  const [status, setStatus] = useState(null);
  const [installId, setInstallId] = useState(null);
  const [tab, setTab] = useState("home");
  const [message, setMessage] = useState(null);

  const refresh = useCallback(() => {
    bedrockStatus()
      .then((s) => {
        setStatus(s);
        setInstallId((cur) => (s.installs.some((i) => i.id === cur) ? cur : s.installs[0]?.id ?? null));
      })
      .catch(() => setStatus({ supported: false, installs: [], breezeVersion: null }));
  }, []);
  useEffect(refresh, [refresh]);

  const say = useCallback((text, error = false) => setMessage({ text, error }), []);
  const install = status?.installs.find((i) => i.id === installId) || null;

  return (
    <div className="sv page-enter ed-page bd-page" aria-labelledby="ed-title">
      <div className="ed-hero">
        <h1 id="ed-title" className="ed-title">Bedrock Edition</h1>
        <p className="ed-lead">
          Play Bedrock, keep the Breeze add-on up to date, and manage your packs and worlds. Breeze opens the game the
          way Windows does and never changes its files or its version.
        </p>
      </div>

      {status === null && <p className="bd-muted">Looking for Bedrock</p>}
      {status && !status.supported && <Unavailable onBack={onBack} />}
      {status?.supported && status.installs.length === 0 && <NotInstalled onRefresh={refresh} />}

      {install && (
        <>
          <div className="bd-row">
            <div className="bd-tabs" role="tablist" aria-label="Bedrock">
              {TABS.map((t) => (
                <button key={t.id} type="button" role="tab" id={`bd-tab-${t.id}`} aria-selected={tab === t.id}
                  aria-controls="bd-panel" className={`bd-tab ${tab === t.id ? "on" : ""}`} onClick={() => setTab(t.id)}>
                  {t.label}
                </button>
              ))}
            </div>
            {status.installs.length > 1 && (
              <label className="bd-install">
                <span className="bd-muted">Install</span>
                <select value={install.id} onChange={(e) => setInstallId(e.target.value)} aria-label="Bedrock install">
                  {status.installs.map((i) => <option key={i.id} value={i.id}>{i.label}</option>)}
                </select>
              </label>
            )}
          </div>
          {message && (
            <p className={`bd-message ${message.error ? "err" : ""}`} role={message.error ? "alert" : "status"}>{message.text}</p>
          )}
          <div id="bd-panel" role="tabpanel" aria-labelledby={`bd-tab-${tab}`}>
            {tab === "home" && <HomeTab install={install} playerName={playerName} breezeVersion={status.breezeVersion} onChanged={refresh} say={say} />}
            {tab === "addons" && <AddonsTab install={install} say={say} onChanged={refresh} />}
            {tab === "worlds" && <WorldsTab install={install} say={say} />}
          </div>
        </>
      )}

      <section className="ed-section" aria-labelledby="ed-limits">
        <h2 id="ed-limits" className="ed-h2">Good to know</h2>
        <ul className="ed-limits">
          {BEDROCK_LIMITS.map((line) => <li key={line}>{line}</li>)}
        </ul>
      </section>
    </div>
  );
}
