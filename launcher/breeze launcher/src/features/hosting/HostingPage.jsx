import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  listLocalServers, prepareLocalServer, startLocalServer, stopLocalServer,
  readLocalServerLog, sendLocalServerCommand, openLocalServerFolder, openExternalUrl,
} from "../../services/nativeBridge";
import { I } from "../../ui/icons";
import { reportServers } from "./hostingStore";

/**
 * Hosting (1.1.0 directive, item fourteen): a Minecraft server on the
 * player's own computer. Breeze gives out no addresses of its own, so the
 * page says plainly how friends reach it (this computer, the home network,
 * or the internet through the player's own router) and what that shows.
 *
 * Nothing is made before the player has read what a server uses and shows
 * and ticked both boxes (one is the Minecraft EULA). A server runs only
 * while the launcher is open; Stop asks it to save first. The rules are in
 * src-tauri/src/local_server.rs.
 */
export const EULA_URL = "https://aka.ms/MinecraftEULA";
const SOFTWARE = [
  { id: "fabric", label: "Fabric", hint: "For Fabric mods, like the game Breeze runs." },
  { id: "paper", label: "Paper", hint: "For plugins. Fast, and often the choice for friends' servers." },
  { id: "vanilla", label: "Vanilla", hint: "Minecraft's own server, nothing added." },
];
const SOFTWARE_LABEL = Object.fromEntries(SOFTWARE.map((s) => [s.id, s.label]));
const gb = (mb) => `${Math.round((Number(mb) || 0) / 102.4) / 10} GB`;
const newId = () => `srv-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`;

function StatusPill({ status }) {
  const label = status === "running" ? "Running" : status === "stopping" ? "Stopping" : "Stopped";
  return <span className={`host-pill ${status}`}><span className="host-dot" aria-hidden="true" />{label}</span>;
}

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
          <div className="modal-header">
            <div>
              <h2 className="modal-title" id="host-create-title">Make a server</h2>
              <p className="modal-desc">It runs on this computer, only while Breeze is open and you have started it.</p>
            </div>
            <button type="button" className="mini-btn" onClick={onClose} disabled={busy} aria-label="Close"><I.X /></button>
          </div>

          <div className="host-form">
            <label className="host-field">
              <span>Name</span>
              <input className="settings-input" value={name} maxLength={48} onChange={(e) => setName(e.target.value)} />
            </label>
            <div className="host-field">
              <span>Server software</span>
              <div className="host-seg" role="radiogroup" aria-label="Server software">
                {SOFTWARE.map((s) => (
                  <button key={s.id} type="button" role="radio" aria-checked={software === s.id} className={software === s.id ? "on" : ""} onClick={() => setSoftware(s.id)}>{s.label}</button>
                ))}
              </div>
              <small>{SOFTWARE.find((s) => s.id === software)?.hint}</small>
            </div>
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
            <label className="host-field host-field-wide">
              <span>Memory for the server: up to {gb(ramMb)}</span>
              <input type="range" min={1024} max={Math.max(1024, maxRamMb)} step={512} value={ramMb} onChange={(e) => setRamMb(Number(e.target.value))} aria-label="Memory for the server" />
              <small>{ramMb > 4096 ? "Plenty for a few friends; more is rarely faster." : "Enough for a few friends on a small world."} The game you play takes its own memory on top.</small>
            </label>
          </div>

          <div className="host-consent">
            <h3>Before you make it</h3>
            <ul>
              <li><strong>It uses this computer.</strong> While it runs it takes processor time, up to {gb(ramMb)} of memory, your network's upload and power, and its world takes disk space.</li>
              <li><strong>Who can join.</strong> You on this computer, and others on your home network. Players are checked with Mojang (online mode), so only real accounts get in.</li>
              <li><strong>Over the internet</strong> needs a port forward on your router (or a tunnel you set up). Whoever you give the address to sees your home's public address. Breeze does not hide or protect it, and does not give out addresses of its own.</li>
              <li><strong>It stops with Breeze.</strong> Closing the launcher stops it after saving the world. Stop it yourself when you are done.</li>
            </ul>
            <label className="host-check">
              <input type="checkbox" checked={understood} onChange={(e) => setUnderstood(e.target.checked)} />
              <span>I understand what a server uses on this computer and what it shows to the players I let in.</span>
            </label>
            <label className="host-check">
              <input type="checkbox" checked={eula} onChange={(e) => setEula(e.target.checked)} />
              <span>I accept the <button type="button" className="host-link" onClick={() => openExternalUrl(EULA_URL)}>Minecraft EULA</button>, which every Minecraft server needs.</span>
            </label>
          </div>

          {error && <div className="host-error" role="alert">{error}</div>}
          <div className="modal-actions">
            <div className="host-busy">{busy ? "Downloading the server and, if needed, Java. This can take a minute." : ""}</div>
            <button type="button" className="modal-close" onClick={onClose} disabled={busy}>Cancel</button>
            <button type="button" className="modal-buy" onClick={create} disabled={!ready}>{busy ? <I.Spin /> : "Make server"}</button>
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
  const act = async (fn, verb) => {
    setBusy(true);
    try {
      const res = await fn({ id: server.id, serverPath: server.path, ramMb: server.ramMb });
      if (verb === "start") setOpen(true);
      if (res?.logs?.[0] && verb === "start") notify?.("ok", `${server.name}: ${res.logs[0]}`);
    } catch (e) {
      notify?.("!", String(e?.message || e));
    } finally {
      setBusy(false);
      onChanged();
    }
  };
  const running = server.status === "running";
  return (
    <article className={`host-card ${server.status}`} aria-label={server.name}>
      <div className="host-card-head">
        <div className="host-card-icon" aria-hidden="true"><I.Server /></div>
        <div className="host-card-title">
          <h3>{server.name}</h3>
          <span>{SOFTWARE_LABEL[server.loader] || server.loader} {server.version} · up to {gb(server.ramMb)} · port {server.port}</span>
        </div>
        <StatusPill status={server.status} />
        <div className="host-card-actions">
          {server.status === "stopped"
            ? <button type="button" className="modal-buy" disabled={busy || !server.jarReady} onClick={() => act(startLocalServer, "start")}>{busy ? <I.Spin /> : "Start"}</button>
            : <button type="button" className="modal-close" disabled={busy || server.status === "stopping"} onClick={() => act(stopLocalServer, "stop")}>{server.status === "stopping" ? "Saving..." : "Stop"}</button>}
          <button type="button" className="btn" onClick={() => setOpen((o) => !o)} aria-expanded={open}>Console</button>
          <button type="button" className="btn" onClick={() => openLocalServerFolder(server.path).catch((e) => notify?.("!", String(e?.message || e)))}>Folder</button>
        </div>
      </div>
      {running && (
        <div className="host-join">
          <CopyField label="On this computer" value={`localhost:${server.port}`} notify={notify} />
          {lanAddress && <CopyField label="Others at home" value={`${lanAddress}:${server.port}`} notify={notify} />}
          <p>Over the internet: forward port {server.port} on your router to this computer, then share your public address. Anyone you give it to can see it.</p>
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
  const runningCount = servers.filter((s) => s.status !== "stopped").length;
  return (
    <div className="sv page-enter host-page">
      <div className="vp host-vp">
        <div className="host-header">
          <div>
            <div className="vtl"><I.Server /> Hosting</div>
            <p className="host-sub">A Minecraft server on this computer, for you and your friends.</p>
          </div>
          {servers.length > 0 && <button type="button" className="modal-buy" onClick={() => setCreating(true)}>Make a server</button>}
        </div>
        {runningCount > 0 && (
          <div className="host-banner" role="status">
            <span className="host-dot" aria-hidden="true" />
            {runningCount === 1 ? "A server is running" : `${runningCount} servers are running`} on this computer and using its memory and network. Closing Breeze stops {runningCount === 1 ? "it" : "them"}.
          </div>
        )}
        {error && <div className="host-error" role="alert">{error}</div>}
      </div>

      <div className="host-scroll">
        {list === null ? (
          <div className="host-loading">Looking for your servers...</div>
        ) : servers.length === 0 ? (
          <section className="host-intro">
            <div className="host-intro-icon" aria-hidden="true"><I.Server /></div>
            <h2>Host a server on this computer</h2>
            <div className="host-facts">
              <div><strong>Fabric, Paper or Vanilla</strong><span>Any Minecraft release. Breeze downloads the server and the Java it needs.</span></div>
              <div><strong>Friends join by your address</strong><span>At home straight away; over the internet through your router.</span></div>
              <div><strong>Only while you want it</strong><span>It starts when you press Start and stops when you stop it or close Breeze.</span></div>
            </div>
            <button type="button" className="modal-buy" onClick={() => setCreating(true)}>Make a server</button>
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
