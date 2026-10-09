import { useEffect, useMemo, useState } from "react";
import { openInstanceFolder } from "../../services/nativeBridge";
import { BackupsList, entryFor, ModFlags, ModsAlerts, useInstanceReport, VersionsPanel } from "./ModsLocal";

const I = {
  Search: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><circle cx="11" cy="11" r="8" /><path d="m21 21-4.35-4.35" /></svg>,
  Plus: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>,
  Check: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6 9 17l-5-5" /></svg>,
  Down: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M12 5v14m-7-7l7 7 7-7" /></svg>,
  X: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>,
  Spin: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="installing"><path d="M21 12a9 9 0 11-6.219-8.56" /></svg>,
  Layers: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M12 2L2 7l10 5 10-5-10-5z" /><path d="M2 17l10 5 10-5" /><path d="M2 12l10 5 10-5" /></svg>,
  Transfer: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M7 16V4m0 0L3 8m4-4 4 4" /><path d="M17 8v12m0 0 4-4m-4 4-4-4" /></svg>,
  Bolt: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round"><polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2" /></svg>,
  // Export pack. This page keeps its own icon set, and this one was missing
  // while the shared set had it, so the button rendered <undefined /> and React
  // refused to draw the section: the whole Mods page read "Mods could not load".
  Download: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M12 4v10m0 0 4-4m-4 4-4-4M5 19h14" /></svg>,
};

const BUILT_IN_MODS = [
  { id: "sodium", name: "Sodium", description: "Modern rendering engine, massive FPS boost", author: "CaffeineMC", required: true },
  { id: "lithium", name: "Lithium", description: "Game logic & server-side optimizations", author: "CaffeineMC", required: true },
  { id: "ferrite-core", name: "FerriteCore", description: "Drastically reduces memory usage", author: "malte0811", required: false },
  { id: "immediatelyfast", name: "ImmediatelyFast", description: "Faster UI rendering & immediate mode", author: "RaphiMC", required: false },
  { id: "entityculling", name: "EntityCulling", description: "Skip rendering hidden entities", author: "tr7zw", required: false },
  { id: "starlight", name: "Starlight", description: "Complete rewrite of the light engine", author: "Spottedleaf", required: false },
  { id: "distant-horizons", name: "Distant Horizons", description: "LOD rendering for huge view distances", author: "jeseibel", required: false },
  { id: "sodium-extra", name: "Sodium Extra", description: "Extra options for Sodium", author: "FlashyReese", required: false },
  { id: "reese-sodium-options", name: "Reese's Sodium Options", description: "Better Sodium options UI", author: "FlashyReese", required: false },
];

export default function ModsPage({
  modsView, setModsView,
  query, setQuery,
  modResults, installedMods,
  busyModProjectId,
  onInstall, onToggle, onRemove,
  onImport, onRecommended,
  selectedVersionId, setSelectedVersionId,
  visibleVersions,
  modsLoading, modHasMore, modError,
  installedModsError,
  handleLoadMoreMods,
  performanceBusy, handleApplyPerformanceProfile,
  setShowTransferDialog,
  onExportPack,
  exportBusy,
  nativeDesktop = false,
  onModsChanged,
  notify,
}) {
  // What the launcher read from every jar in this version's mods folder:
  // duplicates, missing dependencies, versions. See ModsLocal.jsx.
  const { report, reload } = useInstanceReport(selectedVersionId, selectedVersionId, installedMods, nativeDesktop);
  // Mod ids to names, so "needs sodium" reads "needs Sodium" when it is installed.
  const names = useMemo(() => new Map((report?.mods || []).filter((m) => m.modId).map((m) => [m.modId, m.name])), [report]);
  const [versionsFor, setVersionsFor] = useState(null);
  useEffect(() => { setVersionsFor(null); }, [selectedVersionId, modsView]);
  const changed = async () => {
    await onModsChanged?.();
    await reload();
  };
  const openFolder = (which) => openInstanceFolder(selectedVersionId, which).catch((e) => notify?.("!", String(e?.message || e)));
  const duplicateCount = report?.duplicates?.length || 0;

  // The same installed mod can be reached by three different ids: the hex
  // project id a browse install writes, the Modrinth slug an older install
  // wrote, and the canonical id the Rust side now settles on. Indexing all
  // three is what stops an installed mod reading as "not installed" and being
  // installed a second time under the other key.
  const installedByProjectId = new Map();
  for (const record of installedMods || []) {
    for (const key of [record.canonicalId, record.projectId, record.projectSlug]) {
      if (key && !installedByProjectId.has(key)) installedByProjectId.set(key, record);
    }
  }
  const findInstalled = (mod) =>
    installedByProjectId.get(mod.canonicalId) ||
    installedByProjectId.get(mod.projectId) ||
    installedByProjectId.get(mod.id) ||
    installedByProjectId.get(mod.slug) ||
    null;
  const isModBusy = (mod) =>
    Boolean(busyModProjectId) &&
    [mod.canonicalId, mod.projectId, mod.id, mod.slug].includes(busyModProjectId);

  const filteredBrowseMods = (modResults || []).filter((m) => {
    if (!query) return true;
    return (m.title || m.name || "").toLowerCase().includes(query.toLowerCase()) ||
      (m.description || "").toLowerCase().includes(query.toLowerCase());
  });

  const filteredInstalledMods = (installedMods || []).filter((m) => {
    if (!query) return true;
    return (m.title || m.name || "").toLowerCase().includes(query.toLowerCase());
  });

  const builtInRows = BUILT_IN_MODS.map((mod) => ({
    ...mod,
    installed: Boolean(findInstalled(mod)),
  }));

  return (
    <div className="mv page-enter">
      {/* Toolbar */}
      <div className="vp">
        <div className="vtl"><I.Layers /> Mod Manager</div>
        <div className="tbar">
          <div className="sw">
            <I.Search />
            <input placeholder="Search mods…" value={query} onChange={(e) => setQuery(e.target.value)} />
          </div>
          <button className="btn" onClick={onRecommended}><I.Check /> Recommended</button>
          <button className="btn accent" onClick={onImport}><I.Plus /> Import .jar</button>
          {setShowTransferDialog && (
            <button className="btn" onClick={() => setShowTransferDialog(true)} title="Transfer mods to another version">
              <I.Transfer /> Transfer
            </button>
          )}
          {onExportPack && (
            <button className="btn" onClick={onExportPack} disabled={exportBusy} title="Save this version's mods as a Modrinth pack">
              {exportBusy ? <I.Spin /> : <I.Download />} Export pack
            </button>
          )}
          {visibleVersions?.length > 0 && setSelectedVersionId && (
            <select className="vsel" aria-label="Minecraft version" style={{ height: 36, minWidth: 130, fontSize: 12 }} value={selectedVersionId} onChange={(e) => setSelectedVersionId(e.target.value)}>
              {visibleVersions.map((v) => <option key={v.id} value={v.id}>{v.id}</option>)}
            </select>
          )}
        </div>
        <div className="tabs">
          {[["browse", "Browse"], ["my", "My Mods"], ["built", "Built-in Perf"]].map(([id, label]) => (
            <button key={id} className={`tab ${modsView === id ? "on" : ""}`} onClick={() => setModsView(id)}>{label}</button>
          ))}
        </div>
      </div>

      <div className="msc">
        <div className="ml">
          {/* Browse tab */}
          {modsView === "browse" && filteredBrowseMods.map((mod) => {
            const installed = findInstalled(mod);
            const installing = isModBusy(mod);
            return (
              <div key={mod.id || mod.projectId} className={`mr ${installed ? "ins" : ""}`}>
                <div className="mi">
                  {mod.iconUrl
                    ? <img src={mod.iconUrl} alt="" style={{ width: 24, height: 24, borderRadius: 6 }} />
                    : <I.Layers />}
                </div>
                <div className="mb">
                  <div className="mn2">{mod.title || mod.name}</div>
                  <div className="mm">{mod.description || mod.summary}</div>
                </div>
                <div className="badge fab">{selectedVersionId}</div>
                <button
                  className={`mod-btn ${installed ? "ok" : "get"}`}
                  onClick={() => installed ? onToggle(installed) : onInstall(mod)}
                  disabled={installing}
                >
                  {installing ? <I.Spin /> : installed ? <I.Check /> : <I.Down />}
                </button>
              </div>
            );
          })}

          {/* Duplicates stop this version from starting, so they are named on every tab. */}
          {modsView !== "my" && duplicateCount > 0 && (
            <div className="mods-alert err mods-alert-short">
              <p className="mods-alert-head">
                {duplicateCount === 1 ? "A mod is" : `${duplicateCount} mods are`} installed more than once for {selectedVersionId}.{" "}
                <button type="button" className="link-btn" onClick={() => setModsView("my")}>Choose which copy to keep</button>
              </p>
            </div>
          )}

          {/* My Mods tab */}
          {modsView === "my" && nativeDesktop && (
            <>
              <div className="mod-folders">
                <button type="button" className="btn mod-small" onClick={() => openFolder("mods")}>Open mods folder</button>
                <button type="button" className="btn mod-small" onClick={() => openFolder("instance")}>Open instance folder</button>
                <button type="button" className="btn mod-small" onClick={() => openFolder("logs")}>Open logs</button>
              </div>
              <ModsAlerts profileId={selectedVersionId} report={report} onChanged={changed} notify={notify} />
            </>
          )}
          {modsView === "my" && filteredInstalledMods.map((mod) => {
            const entry = entryFor(report, mod);
            // What the jar itself says, then what the launcher recorded.
            const version = entry?.version || mod.installedVersionName || mod.gameVersion || selectedVersionId;
            const meta = [
              version,
              mod.fileMissing ? "file missing" : mod.fileName,
              mod.managed === false && !entry?.breeze ? "added outside the launcher" : "",
            ].filter(Boolean).join(" · ");
            const rowKey = mod.canonicalId || mod.projectId || mod.id;
            const versionsOpen = versionsFor === rowKey && entry;
            return (
            <div key={rowKey} className="mod-item">
            <div className={`mr ins ${entry?.duplicate ? "dup" : ""}`}>
              <div className="mi">
                {mod.iconUrl
                  ? <img src={mod.iconUrl} alt="" style={{ width: 24, height: 24, borderRadius: 6 }} />
                  : <I.Layers />}
              </div>
              <div className="mb">
                <div className="mn2">{mod.title || mod.name}</div>
                <div className="mm" title={meta}>{meta}</div>
                <ModFlags entry={entry} names={names} />
              </div>
              {entry?.duplicate
                ? <span className="badge fab">Installed twice</span>
                : <span className={`badge ${mod.enabled ? "perf" : "bi"}`}>{mod.enabled ? "Enabled" : "Disabled"}</span>}
              <div style={{ display: "flex", gap: 5, alignItems: "center" }}>
                {entry?.modId && !entry.breeze && (
                  <button
                    type="button"
                    className={`btn mod-small ${versionsOpen ? "accent" : ""}`}
                    aria-expanded={Boolean(versionsOpen)}
                    onClick={() => setVersionsFor(versionsOpen ? null : rowKey)}
                    title="Choose another version of this mod"
                  >
                    Versions
                  </button>
                )}
                {/* Toggle switch */}
                <button
                  className={`mod-toggle ${mod.enabled ? "on" : ""}`}
                  onClick={() => onToggle(mod)}
                  disabled={isModBusy(mod)}
                  title={mod.enabled ? "Disable mod" : "Enable mod"}
                >
                  <span className="mod-toggle-knob" />
                  <span className="mod-toggle-text">{mod.enabled ? "On" : "Off"}</span>
                </button>
                <button
                  className="mod-btn off"
                  onClick={() => onRemove(mod)}
                  disabled={isModBusy(mod)}
                  title="Remove mod"
                >
                  <I.X />
                </button>
              </div>
            </div>
            {versionsOpen && (
              <VersionsPanel
                profileId={selectedVersionId}
                gameVersion={selectedVersionId}
                entry={entry}
                names={names}
                notify={notify}
                onChanged={changed}
                onClose={() => setVersionsFor(null)}
              />
            )}
            </div>
            );
          })}

          {/* Built-in Performance tab */}
          {modsView === "built" && builtInRows.map((mod) => (
            <div key={mod.id} className={`mr ${mod.installed ? "ins" : ""}`}>
              <div className="mi" style={{ color: "var(--warn, #f4a02a)" }}><I.Bolt /></div>
              <div className="mb">
                <div className="mn2">
                  {mod.name}
                  {mod.required && <span style={{ fontSize: 9, color: "var(--accent)", marginLeft: 4 }}>CORE</span>}
                </div>
                <div className="mm">{mod.description} · {mod.author}</div>
              </div>
              <div className={`badge ${mod.installed ? "perf" : "bi"}`}>{mod.installed ? "Installed" : "Available"}</div>
              <button
                className={`mod-btn ${mod.installed ? "ok" : "get"}`}
                onClick={() => !mod.installed && handleApplyPerformanceProfile?.("Performance")}
                disabled={performanceBusy}
              >
                {performanceBusy ? <I.Spin /> : mod.installed ? <I.Check /> : <I.Down />}
              </button>
            </div>
          ))}

          {/* Load more */}
          {modsView === "browse" && modHasMore && (
            <button className="btn accent w-full" onClick={handleLoadMoreMods} disabled={modsLoading}>
              {modsLoading ? "Loading…" : "Load More"}
            </button>
          )}

          {/* Empty states */}
          {!modsLoading && modsView === "browse" && filteredBrowseMods.length === 0 && (
            <div style={{ textAlign: "center", padding: "36px 0", color: "var(--text-faint)", fontSize: 12 }}>
              {modError || "No compatible mods found"}
            </div>
          )}
          {modsView === "my" && filteredInstalledMods.length === 0 && (
            <div style={{ textAlign: "center", padding: "36px 0", color: "var(--text-faint)", fontSize: 12 }}>
              {installedModsError
                ? `Could not read this profile's mods: ${installedModsError}`
                : !query && report?.mods?.some((m) => !m.breeze)
                  ? <>The mods folder has {report.mods.filter((m) => !m.breeze).length} mod files, but the list did not load. <button type="button" className="link-btn" onClick={() => changed()}>Read it again</button></>
                  : "No mods installed for this version"}
            </div>
          )}
          {modsView === "my" && nativeDesktop && (
            <BackupsList profileId={selectedVersionId} refreshKey={installedMods} onChanged={changed} notify={notify} />
          )}
        </div>
      </div>
    </div>
  );
}
