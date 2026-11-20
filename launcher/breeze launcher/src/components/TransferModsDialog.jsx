import { useMemo, useState } from "react";
import { I } from "../ui/icons";

/**
 * Move the mods installed under one Minecraft version to another.
 *
 * The Transfer button used to call a state setter nothing read, so clicking it
 * did nothing whatsoever. This is the screen it should have opened.
 *
 * Transferring is not simply copying the jars, because a Fabric mod is built
 * against a particular Minecraft version. Sodium for 1.20.1 does not load on
 * 1.21.8: it throws during startup, and the user is left with a game that will
 * not open and no idea which mod did it. So a mod that came from Modrinth is
 * installed again for the target version, which fetches the build made for it,
 * and only a locally imported jar, which has no project to reinstall from, is
 * copied byte for byte. Those copies are labelled, because they are the ones
 * that genuinely might not load.
 */
export default function TransferModsDialog({
  fromVersionId,
  versions,
  installedMods,
  onTransfer,
  onClose,
}) {
  const targets = (versions || []).filter((version) => version.id !== fromVersionId);
  const mods = installedMods || [];
  const [targetId, setTargetId] = useState(targets[0]?.id || "");
  const [chosen, setChosen] = useState(() => new Set(mods.map(modKey)));
  const [busy, setBusy] = useState(false);
  const [result, setResult] = useState(null);

  const localCount = useMemo(
    () => mods.filter((mod) => isLocalOnly(mod) && chosen.has(modKey(mod))).length,
    [mods, chosen],
  );

  const toggle = (mod) => {
    const key = modKey(mod);
    setChosen((current) => {
      const next = new Set(current);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });
  };

  const allChosen = mods.length > 0 && chosen.size === mods.length;
  const setAll = () => setChosen(allChosen ? new Set() : new Set(mods.map(modKey)));

  async function run() {
    if (!targetId || !chosen.size) return;
    setBusy(true);
    try {
      setResult(await onTransfer(targetId, mods.filter((mod) => chosen.has(modKey(mod)))));
    } catch (error) {
      // A throw here means the transfer never started, rather than one mod
      // failing, so it is reported as the whole operation being refused.
      setResult({ moved: [], skipped: [{ title: "Transfer", reason: String(error?.message || error) }] });
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="modal-backdrop" onClick={busy ? undefined : onClose}>
      <div className="modal" onClick={(event) => event.stopPropagation()} style={{ maxWidth: 560 }}>
        <div className="modal-body">
          <div className="modal-header">
            <div>
              <div className="modal-title">Transfer mods</div>
              <div className="modal-desc">
                Copy what you have on {fromVersionId} to another version. Mods from Modrinth are
                fetched again for the version you pick, so you get the build made for it.
              </div>
            </div>
            <button className="mini-btn" onClick={onClose} disabled={busy}><I.X /></button>
          </div>

          {!targets.length && (
            <div className="empty-panel">
              Only one version is installed, so there is nowhere to transfer to yet.
            </div>
          )}

          {targets.length > 0 && !result && (
            <>
              <div className="sec-label">Transfer to</div>
              <select
                className="vsel"
                value={targetId}
                onChange={(event) => setTargetId(event.target.value)}
                disabled={busy}
                style={{ width: "100%", height: 38, marginBottom: 12 }}
              >
                {targets.map((version) => <option key={version.id} value={version.id}>{version.id}</option>)}
              </select>

              <div
                className="sec-label"
                style={{ display: "flex", justifyContent: "space-between", alignItems: "center" }}
              >
                <span>Mods ({chosen.size} of {mods.length})</span>
                {mods.length > 0 && (
                  <button className="mini-btn" onClick={setAll} disabled={busy} style={{ fontSize: 11 }}>
                    {allChosen ? "None" : "All"}
                  </button>
                )}
              </div>

              {!mods.length && <div className="empty-panel">No mods are installed on {fromVersionId} yet.</div>}

              <div style={{ maxHeight: 260, overflowY: "auto", display: "grid", gap: 4 }}>
                {mods.map((mod) => {
                  const key = modKey(mod);
                  return (
                    <label
                      key={key}
                      className={`mr ${chosen.has(key) ? "ins" : ""}`}
                      style={{ cursor: busy ? "default" : "pointer", margin: 0 }}
                    >
                      <input
                        type="checkbox"
                        checked={chosen.has(key)}
                        onChange={() => toggle(mod)}
                        disabled={busy}
                        style={{ marginRight: 8 }}
                      />
                      <div className="mb">
                        <div className="mn2">{mod.title || mod.projectSlug || key}</div>
                        <div className="mm">
                          {isLocalOnly(mod) ? "Local jar, copied unchanged" : "From Modrinth, refetched for the target"}
                        </div>
                      </div>
                      {!mod.enabled && <span className="badge bi">Disabled</span>}
                    </label>
                  );
                })}
              </div>

              {localCount > 0 && (
                <div className="modal-desc" style={{ marginTop: 10 }}>
                  {localCount === 1 ? "One mod was" : `${localCount} mods were`} imported from a file, so
                  there is no project to fetch a matching build from and the jar is copied as it is. A jar
                  built for a different Minecraft version may not load. If the game refuses to start, remove
                  it from the new version.
                </div>
              )}
            </>
          )}

          {result && (
            <>
              <div className="sec-label">
                {result.moved.length
                  ? `${result.moved.length} mod${result.moved.length === 1 ? "" : "s"} now on ${targetId}`
                  : "Nothing was transferred"}
              </div>
              <div style={{ maxHeight: 240, overflowY: "auto", display: "grid", gap: 4 }}>
                {result.moved.map((item) => (
                  <div key={`ok-${item.title}`} className="mr ins">
                    <div className="mi"><I.Check /></div>
                    <div className="mb">
                      <div className="mn2">{item.title}</div>
                      <div className="mm">{item.how}</div>
                    </div>
                  </div>
                ))}
                {result.skipped.map((item) => (
                  <div key={`no-${item.title}`} className="mr">
                    <div className="mi"><I.X /></div>
                    <div className="mb">
                      <div className="mn2">{item.title}</div>
                      <div className="mm">{item.reason}</div>
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}

          <div style={{ display: "flex", gap: 8, justifyContent: "flex-end", marginTop: 14 }}>
            {result
              ? <button className="btn accent" onClick={onClose}>Done</button>
              : (
                <>
                  <button className="btn" onClick={onClose} disabled={busy}>Cancel</button>
                  <button
                    className="btn accent"
                    onClick={run}
                    disabled={busy || !targetId || !chosen.size}
                  >
                    {busy ? <I.Spin /> : <I.Transfer />}
                    {busy ? " Transferring…" : " Transfer"}
                  </button>
                </>
              )}
          </div>
        </div>
      </div>
    </div>
  );
}

/** The id the rest of the launcher uses to talk about one installed mod. */
function modKey(mod) {
  return mod.canonicalId || mod.projectId || mod.projectSlug || mod.title;
}

/**
 * Whether this mod can only be copied, rather than fetched again.
 *
 * `import_custom_mod` writes its ids as `custom:<jar name>`, and that prefix is
 * the only marker that there is no Modrinth project behind the record.
 */
function isLocalOnly(mod) {
  return [mod.canonicalId, mod.projectId, mod.projectSlug]
    .some((value) => String(value || "").toLowerCase().startsWith("custom:"));
}
