import { useCallback, useEffect, useState } from "react";
import {
  applyModChange,
  discardModChange,
  listModBackups,
  listModVersions,
  removeModFile,
  restoreModBackup,
  scanInstanceMods,
  stageModVersion,
} from "../../services/nativeBridge";

/**
 * The Mods page's view of the version's real mods folder: what the launcher
 * read from every jar (scan_instance_mods), duplicates Fabric refuses to start
 * with, what each mod is missing, choosing another version of a mod, and the
 * files the launcher moved to the backups. Drawn with the Mods page's own
 * rows, buttons and badges (BreezeV2.css, MODS PAGE).
 */

const errorText = (e) => (typeof e === "string" ? e : e?.message || String(e));

const SHOWN_IDS = {
  minecraft: "Minecraft",
  fabricloader: "Fabric Loader",
  "fabric-loader": "Fabric Loader",
  java: "Java",
  "fabric-api": "Fabric API",
  fabric: "Fabric API",
};

/** A mod id as a player knows it: its name when it is installed. */
export function modName(id, names) {
  return names?.get(id) || SHOWN_IDS[id] || id;
}

/** One problem in a sentence, without the mod it is about. Same wording as the launcher's Rust side. */
export function issueText(issue, names) {
  const wants = (w) => {
    const list = (w || []).filter((x) => x !== "*");
    return list.length ? ` ${list.join(" or ")}` : "";
  };
  switch (issue?.kind) {
    case "missingDependency":
      return `needs ${modName(issue.dependency, names)}${wants(issue.wants)}, which is not installed or is switched off`;
    case "wrongVersion":
      return `needs ${modName(issue.dependency, names)}${wants(issue.wants)}, but ${issue.found} is installed`;
    case "breaks":
      return `does not work with ${modName(issue.other, names)} ${issue.version}`;
    case "conflicts":
      return `may not work well with ${modName(issue.other, names)} ${issue.version}`;
    case "duplicate":
      return `is installed more than once: ${issue.files.join(", ")}`;
    default:
      return "";
  }
}

/** The folder scan, read again whenever the list of mods changes. */
export function useInstanceReport(profileId, gameVersion, refreshKey, enabled) {
  const [state, setState] = useState({ report: null, error: "" });
  const reload = useCallback(() => {
    if (!enabled || !profileId) return Promise.resolve();
    return scanInstanceMods(profileId, gameVersion)
      .then((report) => setState({ report, error: "" }))
      .catch((e) => setState((cur) => ({ report: cur.report, error: errorText(e) })));
  }, [enabled, profileId, gameVersion]);
  useEffect(() => {
    setState({ report: null, error: "" });
  }, [profileId]);
  useEffect(() => {
    reload();
  }, [reload, refreshKey]);
  return { ...state, reload };
}

/** Matches a row of the installed list to what the scan read from its jar. */
export function entryFor(report, record) {
  if (!report || !record) return null;
  const file = (record.fileName || "").toLowerCase();
  return report.mods.find((m) => m.jarName.toLowerCase() === file) || null;
}

function PressTwice({ label, confirmLabel, onConfirm, disabled }) {
  const [armed, setArmed] = useState(false);
  useEffect(() => {
    if (!armed) return undefined;
    const t = setTimeout(() => setArmed(false), 4000);
    return () => clearTimeout(t);
  }, [armed]);
  return (
    <button
      type="button"
      className={`btn mod-small ${armed ? "danger" : ""}`}
      disabled={disabled}
      onClick={() => {
        if (!armed) { setArmed(true); return; }
        setArmed(false);
        onConfirm();
      }}
    >
      {armed ? confirmLabel : label}
    </button>
  );
}

/**
 * What stops this version from starting, above the list: copies of one mod
 * installed twice (each file with its version, removable one by one after a
 * second press; the launcher does not start the version until one is left,
 * because Fabric would quietly load one and ignore the rest), and Fabric's
 * own explanation from the last failed start.
 */
export function ModsAlerts({ profileId, report, onChanged, notify }) {
  const [busy, setBusy] = useState("");
  if (!report) return null;
  const { duplicates, lastLaunchProblem, missingFiles } = report;
  if (!duplicates.length && !lastLaunchProblem && !missingFiles.length) return null;

  const remove = async (fileName) => {
    setBusy(fileName);
    try {
      const moved = await removeModFile(profileId, fileName);
      notify?.("ok", `${fileName} moved to the backups`);
      await onChanged?.();
      return moved;
    } catch (e) {
      notify?.("!", errorText(e));
    } finally {
      setBusy("");
    }
    return null;
  };

  return (
    <section className="mods-alerts" aria-label="Problems in this version's mods">
      {duplicates.map((group) => (
        <div key={group.modId} className="mods-alert err" data-testid={`duplicate-${group.modId}`}>
          <p className="mods-alert-head">
            <strong>{group.name}</strong> is installed {group.files.length} times. Fabric would load only one of them and ignore the rest, so the launcher waits for you to choose which to keep before it starts this version.
          </p>
          <ul className="mods-alert-files">
            {group.files.map((f, i) => (
              <li key={f.fileName}>
                <span className="mods-alert-file">{f.fileName}</span>
                <span className="mods-alert-ver">{f.version}{i === 0 ? ", newest" : ""}</span>
                <PressTwice
                  label="Remove duplicate"
                  confirmLabel="Press again to move it to the backups"
                  disabled={Boolean(busy)}
                  onConfirm={() => remove(f.fileName)}
                />
              </li>
            ))}
          </ul>
        </div>
      ))}
      {missingFiles.length > 0 && (
        <div className="mods-alert warn">
          <p className="mods-alert-head">
            The launcher installed {missingFiles.length === 1 ? "a mod whose file is" : "mods whose files are"} no longer in the mods folder: {missingFiles.join(", ")}. Remove {missingFiles.length === 1 ? "it" : "them"} below or install again.
          </p>
        </div>
      )}
      {lastLaunchProblem && (
        <div className="mods-alert err">
          <p className="mods-alert-head">The last start of this version stopped on its mods. Fabric said:</p>
          <pre className="mods-alert-log">{lastLaunchProblem}</pre>
        </div>
      )}
    </section>
  );
}

/** The flags under a mod's name: who installed it, and what Fabric would object to. */
export function ModFlags({ entry, names }) {
  if (!entry) return null;
  const lines = [];
  if (entry.problem) lines.push({ cls: "err", text: entry.problem });
  for (const issue of entry.issues || []) {
    if (issue.kind === "duplicate") continue;
    lines.push({ cls: issue.kind === "conflicts" ? "warn" : "err", text: `This mod ${issueText(issue, names)}.` });
  }
  if (entry.loadedLastRun === false) lines.push({ cls: "warn", text: "Fabric did not load it the last time this version started." });
  if (!lines.length) return null;
  return (
    <div className="mod-flags">
      {lines.map((l) => <p key={l.text} className={`mod-flag ${l.cls}`}>{l.text}</p>)}
    </div>
  );
}

const shortDate = (iso) => {
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
};

/**
 * Another version of one installed mod. Choosing one downloads and checks it
 * first; the player sees exactly what changes (the file, the version, which
 * other mods it breaks or fixes) before anything in the mods folder moves.
 */
export function VersionsPanel({ profileId, gameVersion, entry, names, onChanged, onClose, notify }) {
  const [list, setList] = useState({ data: null, error: "" });
  const [staging, setStaging] = useState("");
  const [staged, setStaged] = useState(null);
  const [applying, setApplying] = useState(false);

  useEffect(() => {
    let gone = false;
    listModVersions(profileId, entry.key, gameVersion)
      .then((data) => { if (!gone) setList({ data, error: "" }); })
      .catch((e) => { if (!gone) setList({ data: null, error: errorText(e) }); });
    return () => { gone = true; };
  }, [profileId, gameVersion, entry.key]);

  // A download the player walked away from is not left in the staging folder.
  useEffect(() => () => { if (staged) discardModChange(profileId, staged.token).catch(() => {}); }, [profileId, staged]);

  const choose = async (version) => {
    setStaging(version.id);
    try {
      setStaged(await stageModVersion(profileId, entry.key, version.id, gameVersion));
    } catch (e) {
      notify?.("!", errorText(e));
    } finally {
      setStaging("");
    }
  };

  const apply = async () => {
    if (!staged) return;
    setApplying(true);
    try {
      const done = await applyModChange(profileId, staged.token, staged.blocking, gameVersion);
      setStaged(null);
      notify?.("ok", `${staged.name} ${staged.toVersion} is in place. The previous file is in the backups.`);
      await onChanged?.();
      onClose?.();
      return done;
    } catch (e) {
      notify?.("!", errorText(e));
    } finally {
      setApplying(false);
    }
    return null;
  };

  const cancel = () => {
    if (staged) discardModChange(profileId, staged.token).catch(() => {});
    setStaged(null);
  };

  if (staged) {
    const { impact } = staged;
    return (
      <div className="mod-more" data-testid="version-change">
        <p className="mod-more-head">
          {staged.from.map((f) => `${f.fileName} (${f.version})`).join(", ")} will go to the backups and <strong>{staged.toFile}</strong> ({staged.toVersion}) takes its place{staged.enabled ? "" : ", switched off like the one it replaces"}.
        </p>
        {impact.own.map((i) => <p key={JSON.stringify(i)} className={`mod-flag ${i.kind === "conflicts" ? "warn" : "err"}`}>This version {issueText(i, names)}.</p>)}
        {impact.breaks.map((o) => <p key={o.fileName + JSON.stringify(o.issue)} className={`mod-flag ${o.issue.kind === "conflicts" ? "warn" : "err"}`}>{o.name} {issueText(o.issue, names)} afterwards.</p>)}
        {impact.fixes.map((o) => <p key={o.fileName + JSON.stringify(o.issue)} className="mod-flag ok">Fixes {o.name}, which {issueText(o.issue, names)} now.</p>)}
        {staged.blocking && <p className="mod-flag err">Fabric would refuse to start with this change until those mods are changed too.</p>}
        <div className="mod-more-acts">
          <button type="button" className={`btn ${staged.blocking ? "danger" : "accent"}`} onClick={apply} disabled={applying}>
            {applying ? "Changing" : staged.blocking ? "Change anyway" : "Change version"}
          </button>
          <button type="button" className="btn" onClick={cancel} disabled={applying}>Keep {staged.from[0]?.version || "the current one"}</button>
        </div>
      </div>
    );
  }

  const data = list.data;
  return (
    <div className="mod-more" data-testid="version-list">
      <div className="mod-more-top">
        <p className="mod-more-head">
          {data
            ? <>Installed: <strong>{data.installedVersion || entry.version || "unknown"}</strong>. Versions on Modrinth for Minecraft {data.minecraft} with Fabric{data.identifiedBy === "hash" ? ", found by this file's hash" : ""}.</>
            : list.error ? list.error : "Asking Modrinth"}
        </p>
        <button type="button" className="btn mod-small" onClick={onClose}>Close</button>
      </div>
      {data?.note && <p className="mod-flag warn">{data.note}</p>}
      {data?.versions?.length > 0 && (
        <ul className="mod-vers">
          {data.versions.map((v) => (
            <li key={v.id} className={v.installed ? "is-installed" : ""}>
              <span className="mod-ver-num">{v.versionNumber || v.name}</span>
              <span className="mod-ver-meta">{[v.versionType !== "release" ? v.versionType : "", shortDate(v.datePublished)].filter(Boolean).join(", ")}</span>
              {v.installed
                ? <span className="badge perf">Installed</span>
                : <button type="button" className="btn mod-small" onClick={() => choose(v)} disabled={Boolean(staging)}>{staging === v.id ? "Downloading" : "Use this version"}</button>}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}

const when = (secs) => {
  const d = new Date(secs * 1000);
  return d.toLocaleString(undefined, { month: "short", day: "numeric", hour: "2-digit", minute: "2-digit" });
};
const REASONS = { replaced: "Replaced", removed: "Removed", rollback: "Put back", duplicate: "Duplicate" };

/** Files the launcher moved out of this version's mods folder, newest first, each one can be put back. */
export function BackupsList({ profileId, refreshKey, onChanged, notify }) {
  const [backups, setBackups] = useState(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState("");

  useEffect(() => {
    let gone = false;
    listModBackups(profileId).then((b) => { if (!gone) setBackups(b); }).catch(() => { if (!gone) setBackups([]); });
    return () => { gone = true; };
  }, [profileId, refreshKey]);

  if (!backups?.length) return null;
  const putBack = async (backup, file) => {
    setBusy(`${backup.id}/${file.fileName}`);
    try {
      await restoreModBackup(profileId, backup.id, file.fileName);
      notify?.("ok", `${file.name} ${file.version} is back in the mods folder`);
      await onChanged?.();
    } catch (e) {
      notify?.("!", errorText(e));
    } finally {
      setBusy("");
    }
  };
  return (
    <section className="mod-backups">
      <button type="button" className="btn mod-small" aria-expanded={open} onClick={() => setOpen((o) => !o)}>
        {open ? "Hide" : "Show"} earlier files ({backups.reduce((n, b) => n + b.files.length, 0)})
      </button>
      {open && (
        <ul className="mod-vers">
          {backups.flatMap((b) => b.files.map((f) => (
            <li key={`${b.id}/${f.fileName}`}>
              <span className="mod-ver-num">{f.name} {f.version}</span>
              <span className="mod-ver-meta">{REASONS[b.reason] || b.reason}, {when(b.at)}</span>
              <PressTwice
                label="Put back"
                confirmLabel="Press again: the current copy goes to the backups"
                disabled={Boolean(busy)}
                onConfirm={() => putBack(b, f)}
              />
            </li>
          )))}
        </ul>
      )}
    </section>
  );
}
