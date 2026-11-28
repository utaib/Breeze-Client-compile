import { useEffect, useMemo, useState } from "react";
import { openInstanceFolder } from "../../services/nativeBridge";
import { BackupsList, entryFor, ModFlags, ModsAlerts, useInstanceReport, VersionsPanel } from "./ModsLocal";
import { SourceSwitch } from "../../features/edition/SourceSwitch";
import CurseForgeBrowse from "../../features/edition/CurseForgeBrowse";
import { getCurseForgeStatus } from "../../services/breezeApi";
import LoaderPicker from "../../features/loaders/LoaderPicker";
import { loaderHasMods, loaderName, loaderRunsBreeze } from "../../features/loaders/loaders";

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
  Folder: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" /></svg>,
};

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
  performanceBusy, boostRoles = [], onAddBoostMods,
  setShowTransferDialog,
  onExportPack,
  exportBusy,
  nativeDesktop = false,
  onModsChanged,
  notify,
  breezeToken = "",
  profileId, selectedLoader = "fabric", setSelectedLoader, loaderStatus,
}) {
  // The instance this page manages: the version with its loader (Fabric's is
  // the bare version). Every folder and mod command is about this.
  const instance = profileId || selectedVersionId;
  const runsBreeze = loaderRunsBreeze(selectedLoader);
  const hasMods = loaderHasMods(selectedLoader);
  const loaderBuild = (loaderStatus || []).find((entry) => entry.id === selectedLoader)?.build || "";
  // The FPS mods tab is for every loader that loads mods (1.2.1).
  useEffect(() => {
    if (!hasMods && modsView === "built") setModsView("my");
  }, [hasMods, modsView, setModsView]);
  // Where Browse looks. CurseForge only once Breeze's server has its key;
  // not saved, so a restart is on Modrinth.
  const [source, setSource] = useState("modrinth");
  const [curseForgeReady, setCurseForgeReady] = useState(false);
  useEffect(() => {
    let alive = true;
    getCurseForgeStatus().then((s) => { if (alive) setCurseForgeReady(Boolean(s?.configured) && Boolean(breezeToken)); }).catch(() => {});
    return () => { alive = false; };
  }, [breezeToken]);
  const onCurseForge = modsView === "browse" && source === "curseforge" && curseForgeReady;
  // What the launcher read from every jar in this version's mods folder:
  // duplicates, missing dependencies, versions. See ModsLocal.jsx.
  const { report, reload } = useInstanceReport(instance, selectedVersionId, installedMods, nativeDesktop);
  // Mod ids to names, so "needs sodium" reads "needs Sodium" when it is installed.
  const names = useMemo(() => new Map((report?.mods || []).filter((m) => m.modId).map((m) => [m.modId, m.name])), [report]);
  const [versionsFor, setVersionsFor] = useState(null);
  useEffect(() => { setVersionsFor(null); }, [instance, modsView]);
  const changed = async () => {
    await onModsChanged?.();
    await reload();
  };
  const openFolder = (which) => openInstanceFolder(instance, which).catch((e) => notify?.("!", String(e?.message || e)));
  const duplicateCount = report?.duplicates?.length || 0;
  // Loader and Java only from a report for the version shown: right after
  // switching versions the last one's report is still here for a moment.
  const reportHere = report && (!report.minecraft || report.minecraft === selectedVersionId) ? report : null;

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

  // Installed mods in A to Z order by the name shown (numbers in order too:
  // "Mod 2" before "Mod 10"), whatever order the profile lists them in.
  const shownName = (m) => String(m.title || m.name || m.fileName || "");
  const filteredInstalledMods = (installedMods || []).filter((m) => {
    if (!query) return true;
    return (m.title || m.name || "").toLowerCase().includes(query.toLowerCase());
  }).sort((a, b) => shownName(a).localeCompare(shownName(b), undefined, { sensitivity: "base", numeric: true }));

  // One row per job a performance mod does (performance.rs): the mod of the
  // role that is installed, else the first one the launcher would try.
  const builtInRows = (boostRoles || []).filter((role) => role.mods.length > 0).map((role) => {
    const have = role.mods.map((m) => ({ m, record: findInstalled({ id: m.slug, slug: m.slug }) })).find((x) => x.record);
    const shown = have ? have.m : role.mods[0];
    return {
      id: role.role,
      slug: shown.slug,
      name: have?.record?.title || shown.title,
      description: role.description,
      alternatives: role.mods.filter((m) => m.slug !== shown.slug).map((m) => m.title),
      installed: Boolean(have),
    };
  });

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
          {runsBreeze && <button className="btn" onClick={onRecommended}><I.Check /> Recommended</button>}
          {hasMods && <button className="btn accent" onClick={onImport}><I.Plus /> Import .jar</button>}
          {setShowTransferDialog && hasMods && (
            <button className="btn" onClick={() => setShowTransferDialog(true)} title="Transfer mods to another version">
              <I.Transfer /> Transfer
            </button>
          )}
          {onExportPack && runsBreeze && (
            <button className="btn" onClick={onExportPack} disabled={exportBusy} title="Save this version's mods as a Modrinth pack">
              {exportBusy ? <I.Spin /> : <I.Download />} Export pack
            </button>
          )}
          {visibleVersions?.length > 0 && setSelectedVersionId && (
            <select className="vsel" aria-label="Minecraft version" style={{ height: 36, minWidth: 130, fontSize: 12 }} value={selectedVersionId} onChange={(e) => setSelectedVersionId(e.target.value)}>
              {visibleVersions.map((v) => <option key={v.id} value={v.id}>{v.id}</option>)}
            </select>
          )}
          {setSelectedLoader && (
            <LoaderPicker value={selectedLoader} onChange={setSelectedLoader} status={loaderStatus} version={selectedVersionId} style={{ height: 36, minWidth: 130, fontSize: 12 }} />
          )}
        </div>
        {hasMods && (
          <div className="tabs">
            {[["browse", "Browse"], ["my", "My Mods"], ["built", "FPS mods"]].map(([id, label]) => (
              <button key={id} className={`tab ${modsView === id ? "on" : ""}`} onClick={() => setModsView(id)}>{label}</button>
            ))}
          </div>
        )}
        {hasMods && modsView === "browse" && <SourceSwitch source={onCurseForge ? "curseforge" : "modrinth"} onChange={setSource} curseForgeReady={curseForgeReady} />}
      </div>

      {/* Which version this page manages, what runs it, and where its files
          are: every version is its own folder, so nothing here reaches the
          others (1.1.0 directive, item sixteen). */}
      <div className="mods-instance" role="region" aria-label="This version">
        <div className="mods-instance-what">
          <strong>Minecraft {selectedVersionId}</strong>
          {runsBreeze
            ? <>{" with Fabric"}{reportHere?.loader ? ` Loader ${reportHere.loader}` : ""}</>
            : hasMods
              ? ` with ${loaderName(selectedLoader)}${(reportHere?.loaderKind === selectedLoader && reportHere?.loader) || loaderBuild ? ` ${(reportHere?.loaderKind === selectedLoader && reportHere?.loader) || loaderBuild}` : ""}`
              : " alone, with no mod loader"}
          {reportHere?.java ? `, Java ${reportHere.java}` : ""}
          <span className="mods-instance-note">
            {runsBreeze
              ? "Mods, resource packs and settings here belong to this version only."
              : `${loaderName(selectedLoader)} has its own folder for this version: its mods, resource packs and settings are separate from Fabric's.`}
          </span>
        </div>
        {nativeDesktop && (
          <div className="mod-folders">
            {[["mods", "Mods", "Open mods folder"], ["resourcepacks", "Resource packs", "Open resource packs folder"],
              ["shaderpacks", "Shader packs", "Open shader packs folder"], ["config", "Config", "Open config folder"],
              ["logs", "Logs", "Open logs"], ["instance", "Whole folder", "Open instance folder"]].map(([which, label, name]) => (
              <button key={which} type="button" className="btn mod-small" aria-label={name} title={name} onClick={() => openFolder(which)}>
                <I.Folder /> {label}
              </button>
            ))}
          </div>
        )}
      </div>

      <div className="msc">
        <div className="ml">
          {!hasMods && (
            <div style={{ textAlign: "center", padding: "36px 0", color: "var(--text-faint)", fontSize: 12 }}>
              This version is set to start Minecraft alone, which loads no mods. Choose Fabric, Quilt, Forge or NeoForge above to use mods.
            </div>
          )}
          {/* Browse tab */}
          {onCurseForge && (
            <CurseForgeBrowse
              token={breezeToken} query={query} gameVersion={selectedVersionId} installedMods={installedMods}
              profileId={instance} loader={selectedLoader}
              nativeDesktop={nativeDesktop} onInstalled={changed} notify={notify}
            />
          )}
          {hasMods && modsView === "browse" && !onCurseForge && filteredBrowseMods.map((mod) => {
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
                  aria-label={installed ? `${mod.title || mod.name} is installed: turn it on or off` : `Install ${mod.title || mod.name}`}
                  title={installed ? "Installed: turn it on or off" : "Install"}
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
            <ModsAlerts profileId={instance} report={report} onChanged={changed} notify={notify} />
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
                {/* Choosing a version works on Fabric's metadata: Fabric and Quilt. */}
                {entry?.modId && !entry.breeze && (runsBreeze || selectedLoader === "quilt") && (
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
                profileId={instance}
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

          {/* FPS mods: the loader's performance set, by what each mod does */}
          {modsView === "built" && builtInRows.length > 0 && (
            <div className="fps-head">
              <span>
                The launcher adds these the first time a version is launched. A version with no build of a mod goes without it.
              </span>
              {onAddBoostMods && builtInRows.some((row) => !row.installed) && (
                <button className="btn accent" onClick={onAddBoostMods} disabled={performanceBusy}>
                  {performanceBusy ? "Adding…" : "Add the missing ones"}
                </button>
              )}
            </div>
          )}
          {modsView === "built" && builtInRows.length === 0 && (
            <div className="fps-head">
              <span>
                Breeze adds no FPS mods on this loader: today's builds of them do not start on it.
                Fabric, Forge and NeoForge each have a set. Browse has what works here.
              </span>
            </div>
          )}
          {modsView === "built" && builtInRows.map((mod) => (
            <div key={mod.id} className={`mr ${mod.installed ? "ins" : ""}`}>
              <div className="mi" style={{ color: "var(--warn, #f4a02a)" }}><I.Bolt /></div>
              <div className="mb">
                <div className="mn2">{mod.name}</div>
                <div className="mm">
                  {mod.description}
                  {!mod.installed && mod.alternatives.length > 0 ? ` · or ${mod.alternatives.join(", ")} where it has no build` : ""}
                </div>
              </div>
              <div className={`badge ${mod.installed ? "perf" : "bi"}`}>{mod.installed ? "Installed" : "Available"}</div>
              <button
                className={`mod-btn ${mod.installed ? "ok" : "get"}`}
                onClick={() => !mod.installed && onInstall?.({ id: mod.slug, slug: mod.slug, title: mod.name, name: mod.name, description: mod.description })}
                disabled={performanceBusy || Boolean(busyModProjectId)}
                title={mod.installed ? "Installed" : `Install ${mod.name}`}
              >
                {busyModProjectId === mod.slug ? <I.Spin /> : mod.installed ? <I.Check /> : <I.Down />}
              </button>
            </div>
          ))}

          {/* Load more */}
          {hasMods && modsView === "browse" && !onCurseForge && modHasMore && (
            <button className="btn accent w-full" onClick={handleLoadMoreMods} disabled={modsLoading}>
              {modsLoading ? "Loading…" : "Load More"}
            </button>
          )}

          {/* Empty states */}
          {hasMods && !modsLoading && modsView === "browse" && !onCurseForge && filteredBrowseMods.length === 0 && (
            <div style={{ textAlign: "center", padding: "36px 0", color: "var(--text-faint)", fontSize: 12 }}>
              {modError || (runsBreeze ? "No compatible mods found" : `No ${loaderName(selectedLoader)} mods found for Minecraft ${selectedVersionId}`)}
            </div>
          )}
          {hasMods && modsView === "my" && filteredInstalledMods.length === 0 && (
            <div style={{ textAlign: "center", padding: "36px 0", color: "var(--text-faint)", fontSize: 12 }}>
              {installedModsError
                ? `Could not read this profile's mods: ${installedModsError}`
                : !query && report?.mods?.some((m) => !m.breeze)
                  ? <>The mods folder has {report.mods.filter((m) => !m.breeze).length} mod files, but the list did not load. <button type="button" className="link-btn" onClick={() => changed()}>Read it again</button></>
                  : "No mods installed for this version"}
            </div>
          )}
          {hasMods && modsView === "my" && nativeDesktop && (
            <BackupsList profileId={instance} refreshKey={installedMods} onChanged={changed} notify={notify} />
          )}
        </div>
      </div>
    </div>
  );
}
