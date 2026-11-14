import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  listLocalServers, prepareLocalServer, startLocalServer, stopLocalServer, deleteLocalServer,
  readLocalServerLog, sendLocalServerCommand, openLocalServerFolder, openExternalUrl,
} from "../../services/nativeBridge";
import { I } from "../../ui/icons";
import { reportServers } from "./hostingStore";

/**
 * Hosting (1.1.0 directive, item fourteen): a Minecraft server on the
 * player's own computer. For now it is local: people on this computer and
 * the home network join (Breeze gives out no addresses of its own yet).
 *
 * Nothing is made before the player has read what a server uses and ticked
 * both boxes (one is the Minecraft EULA). A server runs only while the
 * launcher is open; Stop asks it to save first; a stopped server can be
 * deleted. The owner found the first page's feature boxes, green dots and
 * tinted banner machine-made (1.1.0_test_9): it is plain text and one
 * column now. The rules are in src-tauri/src/local_server.rs.
 */
export const EULA_URL = "https://aka.ms/MinecraftEULA";
const SOFTWARE = [
  { id: "fabric", label: "Fabric (mods)" },
  { id: "paper", label: "Paper (plugins)" },
  { id: "vanilla", label: "Vanilla" },
];
const SOFTWARE_LABEL = { fabric: "Fabric", paper: "Paper", vanilla: "Vanilla" };
const gb = (mb) => `${Math.round((Number(mb) || 0) / 102.4) / 10} GB`;
const newId = () => `srv-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

const STATUS_LABEL = { running: "Running", stopping: "Stopping", stopped: "Stopped" };

function CopyField({ label, value, notify }) {
  const copy = async () => {
    try { await navigator.clipboard.writeText(value); notify?.("ok", `Copied ${value}`); } catch { /* clipboard refused */ }
  };
  return (
    <div className="host-addr">
      <span className="host-addr-label">{label}</span>
      <code>{value}</code>
      <button type="button" className="mini-btn" onClick={copy} aria-label={`Copy ${value}`}><I.Copy /></button>
    </div>
  );
}

/** The window that makes a server: settings, then what hosting means, then two boxes to tick. */
function CreateServer({ versions, maxRamMb, onClose, onCreated, notify }) {
  const releases = useMemo(() => (versions || []).filter((v) => v.type === "release").map((v) => v.id), [versions]);
  const [name, setName] = useState("My Breeze server");
  const [software, setSoftware] = useState("fabric");
  const [version, setVersion] = useState(releases[0] || "");
  const [ramMb, setRamMb] = useState(Math.min(2048, maxRamMb));
  const [port, setPort] = useState(25565);
  const [understood, setUnderstood] = useState(false);
  const [eula, setEula] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  useEffect(() => { if (!version && releases[0]) setVersion(releases[0]); }, [releases, version]);
  const portOk = Number.isInteger(port) && port >= 1024 && port <= 65535;
  const ready = name.trim() && version && portOk && understood && eula && !busy;

  const create = async () => {
    if (!ready) return;
    setBusy(true);
    setError("");
    try {
      await prepareLocalServer({ id: newId(), name: name.trim(), loader: software, version, ramMb, port, eulaAccepted: true });
      notify?.("ok", `${name.trim()} is ready. Start it when you want to play.`);
      onCreated();
    } catch (e) {
      setError(String(e?.message || e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="modal-backdrop" onClick={busy ? undefined : onClose}>
      <div className="modal host-create" role="dialog" aria-modal="true" aria-labelledby="host-create-title" onClick={(e) => e.stopPropagation()}>
        <div className="modal-body">
          <div className="host-create-head">
            <h2 className="modal-title" id="host-create-title">New server</h2>
            <button type="button" className="mini-btn" onClick={onClose} disabled={busy} aria-label="Close"><I.X /></button>
          </div>

          <div className="host-form">
            <label className="host-field">
              <span>Name</span>
              <input className="settings-input" value={name} maxLength={48} onChange={(e) => setName(e.target.value)} />
            </label>
            <label className="host-field">
              <span>Software</span>
              <select className="settings-input" value={software} onChange={(e) => setSoftware(e.target.value)}>
                {SOFTWARE.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
              </select>
            </label>
            <label className="host-field">
              <span>Minecraft version</span>
              <select className="settings-input" value={version} onChange={(e) => setVersion(e.target.value)}>
                {releases.map((id) => <option key={id} value={id}>{id}</option>)}
              </select>
            </label>
            <label className="host-field">
              <span>Port</span>
              <input className="settings-input" type="number" min={1024} max={65535} value={port} onChange={(e) => setPort(Number(e.target.value))} aria-invalid={!portOk} />
              {!portOk && <small className="host-warn">From 1024 to 65535. Minecraft's own is 25565.</small>}
            </label>
            <label className="host-field">
              <span className="host-field-row">Memory <b>{gb(ramMb)}</b></span>
              <input type="range" min={1024} max={Math.max(1024, maxRamMb)} step={512} value={ramMb} onChange={(e) => setRamMb(Number(e.target.value))} aria-label="Memory for the server" />
              <small>Up to {gb(maxRamMb)} on this computer, so the game still has room.</small>
            </label>
          </div>

          <p className="host-terms">
            The server runs on this computer while Breeze is open and stops when you close Breeze. It uses processor
            time, up to {gb(ramMb)} of memory and your network. People on this computer and your home network can join.
          </p>
          <label className="host-check">
            <input type="checkbox" checked={understood} onChange={(e) => setUnderstood(e.target.checked)} />
            <span>I understand it runs on this computer and uses its resources.</span>
          </label>
          <label className="host-check">
            <input type="checkbox" checked={eula} onChange={(e) => setEula(e.target.checked)} />
            <span>I accept the <button type="button" className="host-link" onClick={() => openExternalUrl(EULA_URL)}>Minecraft EULA</button>.</span>
          </label>

          {error && <div className="host-error" role="alert">{error}</div>}
          <div className="modal-actions">
            <div className="host-busy">{busy ? "Downloading the server and, if needed, Java. This can take a minute." : ""}</div>
            <button type="button" className="modal-close" onClick={onClose} disabled={busy}>Cancel</button>
            <button type="button" className="modal-buy" onClick={create} disabled={!ready}>{busy ? <I.Spin /> : "Create"}</button>
          </div>
        </div>
      </div>
    </div>
  );
}

function Console({ server, notify }) {
  const [lines, setLines] = useState([]);
  const [command, setCommand] = useState("");
  const boxRef = useRef(null);
  const live = server.status !== "stopped";

  useEffect(() => {
    let stop = false;
    const tick = async () => {
      try {
        const res = await readLocalServerLog({ id: server.id, serverPath: server.path });
        if (!stop) setLines(res.logs || []);
      } catch { /* the folder went away */ }
    };
    tick();
    if (!live) return () => { stop = true; };
    const t = window.setInterval(tick, 1500);
    return () => { stop = true; window.clearInterval(t); };
  }, [server.id, server.path, live]);

  useEffect(() => {
    const el = boxRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [lines]);

  const send = async (e) => {
    e.preventDefault();
    if (!command.trim()) return;
    try {
      await sendLocalServerCommand(server.id, command);
      setCommand("");
    } catch (err) {
      notify?.("!", String(err?.message || err));
    }
  };

  return (
    <div className="host-console">
      <pre ref={boxRef} className="host-log" aria-label={`${server.name} console`} tabIndex={0}>
        {lines.length ? lines.join("\n") : live ? "Starting..." : "Nothing yet. Start the server to see what it says."}
      </pre>
      <form className="host-cmd" onSubmit={send}>
        <span aria-hidden="true">/</span>
        <input value={command} onChange={(e) => setCommand(e.target.value)} placeholder={live ? "A command, e.g. say hello or whitelist add Steve" : "Start the server to type commands"} disabled={server.status !== "running"} aria-label="Server command" maxLength={256} />
        <button type="submit" className="btn" disabled={server.status !== "running" || !command.trim()}>Send</button>
      </form>
    </div>
  );
}

function ServerCard({ server, lanAddress, notify, onChanged }) {
  const [open, setOpen] = useState(server.status !== "stopped");
  const [busy, setBusy] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const act = async (fn, verb) => {
    setBusy(true);
    try {
      const res = await fn({ id: server.id, serverPath: server.path, ramMb: server.ramMb });
      if (verb === "start") setOpen(true);
      if (res?.logs?.[0] && verb === "start") notify?.("ok", `${server.name}: ${res.logs[0]}`);
      if (verb === "delete") notify?.("ok", `${server.name} was deleted.`);
    } catch (e) {
      notify?.("!", String(e?.message || e));
    } finally {
      setBusy(false);
      setConfirming(false);
      onChanged();
    }
  };
  const running = server.status === "running";
  const stopped = server.status === "stopped";
  return (
    <article className={`host-card ${server.status}`} aria-label={server.name}>
      <div className="host-card-head">
        <div className="host-card-title">
          <h3>{server.name}</h3>
          <span>{SOFTWARE_LABEL[server.loader] || server.loader} {server.version} · {gb(server.ramMb)} · port {server.port}</span>
        </div>
        <span className={`host-status ${server.status}`}>{STATUS_LABEL[server.status] || "Stopped"}</span>
        {confirming ? (
          <div className="host-card-actions host-confirm" role="group" aria-label={`Delete ${server.name}`}>
            <span>Delete {server.name} and its world? This can't be undone.</span>
            <button type="button" className="btn" onClick={() => setConfirming(false)} disabled={busy}>Cancel</button>
            <button type="button" className="btn host-danger" onClick={() => act(deleteLocalServer, "delete")} disabled={busy}>Delete</button>
          </div>
        ) : (
          <div className="host-card-actions">
            {stopped
              ? <button type="button" className="modal-buy" disabled={busy || !server.jarReady} onClick={() => act(startLocalServer, "start")}>{busy ? <I.Spin /> : "Start"}</button>
              : <button type="button" className="modal-close" disabled={busy || server.status === "stopping"} onClick={() => act(stopLocalServer, "stop")}>{server.status === "stopping" ? "Saving..." : "Stop"}</button>}
            <button type="button" className="btn" onClick={() => setOpen((o) => !o)} aria-expanded={open}>Console</button>
            <button type="button" className="btn" onClick={() => openLocalServerFolder(server.path).catch((e) => notify?.("!", String(e?.message || e)))}>Folder</button>
            {stopped && <button type="button" className="btn host-danger" onClick={() => setConfirming(true)}>Delete</button>}
          </div>
        )}
      </div>
      {running && (
        <div className="host-join">
          <CopyField label="This computer" value={`localhost:${server.port}`} notify={notify} />
          {lanAddress && <CopyField label="Home network" value={`${lanAddress}:${server.port}`} notify={notify} />}
        </div>
      )}
      {open && <Console server={server} notify={notify} />}
    </article>
  );
}

export default function HostingPage({ notify, versions, featureFlags }) {
  const [list, setList] = useState(null);
  const [error, setError] = useState("");
  const [creating, setCreating] = useState(false);

  const refresh = useCallback(async () => {
    try {
      const next = await listLocalServers();
      setList(next);
      reportServers(next.servers);
      setError("");
    } catch (e) {
      setError(String(e?.message || e));
      setList((l) => l || { servers: [], lanAddress: null, totalRamMb: null, maxRamMb: 4096 });
    }
  }, []);

  useEffect(() => { refresh(); }, [refresh]);
  const anyLive = (list?.servers || []).some((s) => s.status !== "stopped");
  useEffect(() => {
    if (!anyLive) return undefined;
    const t = window.setInterval(refresh, 3000);
    return () => window.clearInterval(t);
  }, [anyLive, refresh]);

  if (featureFlags?.hosting_disabled) {
    return (
      <div className="sv page-enter host-page">
        <div className="store-empty-state"><p><strong>Hosting is turned off for now.</strong><br />Please check back soon.</p></div>
      </div>
    );
  }

  const servers = list?.servers || [];
  return (
    <div className="sv page-enter host-page">
      <div className="vp host-vp">
        <div className="host-header">
          <div>
            <div className="vtl"><I.Server /> Hosting</div>
            <p className="host-sub">Host a Minecraft server on this computer. For now it's local: people on this computer and your home network can join.</p>
          </div>
          {servers.length > 0 && <button type="button" className="modal-buy" onClick={() => setCreating(true)}>New server</button>}
        </div>
        {error && <div className="host-error" role="alert">{error}</div>}
      </div>

      <div className="host-scroll">
        {list === null ? (
          <div className="host-loading">Looking for your servers...</div>
        ) : servers.length === 0 ? (
          <section className="host-empty">
            <p>You have no servers yet.</p>
            <button type="button" className="modal-buy" onClick={() => setCreating(true)}>New server</button>
          </section>
        ) : (
          servers.map((s) => <ServerCard key={s.id} server={s} lanAddress={list.lanAddress} notify={notify} onChanged={refresh} />)
        )}
      </div>

      {creating && (
        <CreateServer
          versions={versions}
          maxRamMb={list?.maxRamMb || 4096}
          notify={notify}
          onClose={() => setCreating(false)}
          onCreated={() => { setCreating(false); refresh(); }}
        />
      )}
    </div>
  );
}
