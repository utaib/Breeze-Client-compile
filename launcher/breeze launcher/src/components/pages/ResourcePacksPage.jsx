import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  downloadModrinthPack,
  importCustomPack,
  listInstalledPacks,
  listPackLibrary,
  removeInstalledPack,
  setPackState,
  useLibraryPack,
  stageFileForImport,
} from "../../services/nativeBridge";
import { PACK_TYPES, searchModrinthPacks } from "../../services/modrinth";
import { I } from "../../ui/icons";

const TYPE_TABS = [
  { key: "resourcepack", label: "Resource Packs" },
  { key: "shaderpack", label: "Shaders" },
  { key: "datapack", label: "Datapacks" },
];

/**
 * What each state means to the user, and what the primary button does next.
 * Keeping this as data rather than nested conditionals is what makes the
 * four-state lifecycle readable at a glance.
 */
const STATES = {
  downloaded: {
    label: "Downloaded",
    hint: "On disk, not yet in your game folder",
    next: { state: "installed", label: "Install" },
  },
  installed: {
    label: "Installed",
    hint: "In the folder, not switched on",
    next: { state: "enabled", label: "Enable" },
  },
  enabled: {
    label: "Enabled",
    hint: "Active the next time you launch",
    next: { state: "disabled", label: "Disable" },
  },
  disabled: {
    label: "Disabled",
    hint: "Installed but switched off",
    next: { state: "enabled", label: "Enable" },
  },
};

const ORDER = ["enabled", "disabled", "installed", "downloaded"];

export default function ResourcePacksPage({ profileId, gameVersion, notify }) {
  const [packType, setPackType] = useState("resourcepack");
  const [installed, setInstalled] = useState([]);
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const [busyId, setBusyId] = useState(null);
  const [loadError, setLoadError] = useState("");
  const [importProgress, setImportProgress] = useState(null);
  /** Packs already downloaded for any version. The file is stored once. */
  const [library, setLibrary] = useState([]);
  const fileRef = useRef(null);

  const refresh = useCallback(async () => {
    if (!profileId) return;
    try {
      setInstalled(await listInstalledPacks(profileId));
      setLibrary(await listPackLibrary(profileId, packType).catch(() => []));
      setLoadError("");
    } catch (e) {
      setLoadError(e?.message || "Could not read your packs.");
    }
  }, [profileId, packType]);

  /** Use a pack this machine already has, with no second download or copy. */
  async function addFromLibrary(pack) {
    setBusyId(pack.fileName);
    try {
      await useLibraryPack(profileId, pack.packType, pack.fileName);
      notify?.("ok", `${pack.fileName.replace(/\.zip$/i, "")} added to this version`);
      await refresh();
    } catch (e) {
      notify?.("!", e?.message || "Could not add that pack.");
    } finally {
      setBusyId(null);
    }
  }

  useEffect(() => { refresh(); }, [refresh]);

  // Debounced so typing does not fire a request per keystroke.
  useEffect(() => {
    if (!query.trim() || !gameVersion) { setResults([]); return; }
    let cancelled = false;
    setSearching(true);
    const timer = setTimeout(async () => {
      try {
        const res = await searchModrinthPacks({ query: query.trim(), gameVersion, packType, offset: 0, limit: 20 });
        if (!cancelled) setResults(res.hits);
      } catch (e) {
        if (!cancelled) { setResults([]); notify?.("!", e?.message || "Modrinth search failed"); }
      } finally {
        if (!cancelled) setSearching(false);
      }
    }, 350);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [query, gameVersion, packType, notify]);

  const ofType = useMemo(
    () => installed
      .filter((p) => p.packType === packType)
      .sort((a, b) => ORDER.indexOf(a.state) - ORDER.indexOf(b.state) || a.title.localeCompare(b.title)),
    [installed, packType],
  );
  const installedIds = useMemo(() => new Set(installed.map((p) => p.projectId)), [installed]);

  const run = async (id, fn, okMessage) => {
    setBusyId(id);
    try {
      await fn();
      if (okMessage) notify?.("ok", okMessage);
      await refresh();
    } catch (e) {
      notify?.("!", e?.message || "That did not work");
    } finally {
      setBusyId(null);
    }
  };

  const download = (hit) => run(
    hit.id,
    () => downloadModrinthPack({
      profileId, gameVersion, packType,
      projectId: hit.id, projectSlug: hit.slug, title: hit.title,
      summary: hit.description, iconUrl: hit.iconUrl,
    }),
    `${hit.title} downloaded`,
  );

  const advance = (pack) => {
    const next = STATES[pack.state]?.next;
    if (!next) return;
    return run(pack.projectId, () => setPackState({ profileId, projectId: pack.projectId, state: next.state }), null);
  };

  const handleImport = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file) return;
    // Streamed to disk in chunks rather than sent as one byte array. A 300MB
    // texture pack serialised as JSON numbers is silently dropped by the IPC
    // layer, which is why importing large packs appeared to do nothing.
    setImportProgress(0);
    await run(
      file.name,
      async () => {
        const stagedPath = await stageFileForImport(file, (f) => setImportProgress(f));
        return importCustomPack({ profileId, gameVersion, packType, fileName: file.name, stagedPath });
      },
      `${file.name} imported`,
    );
    setImportProgress(null);
  };

  if (!profileId) {
    return <div className="sv page-enter"><div className="empty-panel">Pick a version first.</div></div>;
  }

  return (
    <div className="sv page-enter">
      <div className="vtl" style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <I.Palette />
        Packs
        <span className="admin-badge">{gameVersion || "no version"}</span>
      </div>

      <div className="pack-tabs">
        {TYPE_TABS.map((tab) => (
          <button
            key={tab.key}
            className={`pack-tab ${packType === tab.key ? "on" : ""}`}
            onClick={() => setPackType(tab.key)}
          >
            {tab.label}
          </button>
        ))}
        <button className="btn" style={{ marginLeft: "auto" }} onClick={() => fileRef.current?.click()}>
          <I.Upload /> Import .zip
        </button>
        <input ref={fileRef} type="file" accept=".zip" style={{ display: "none" }} onChange={handleImport} />
      </div>

      <input
        className="field-input"
        style={{ marginBottom: 14 }}
        placeholder={`Search Modrinth for ${PACK_TYPES[packType].label.toLowerCase()}`}
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />

      {importProgress !== null && (
        <div className="import-progress-wrap" style={{ marginBottom: 12 }}>
          <div className="import-progress-track">
            <div className="import-progress-fill" style={{ width: `${Math.round(importProgress * 100)}%` }} />
          </div>
          <div className="sd">Importing… {Math.round(importProgress * 100)}%</div>
        </div>
      )}

      {searching && <div className="empty-panel">Searching…</div>}

      {results.length > 0 && (
        <div className="sg">
          <div className="sgl">Search results</div>
          <div className="scd">
            {results.map((hit) => (
              <div key={hit.id} className="sr">
                <div className="pack-icon">
                  {hit.iconUrl ? <img src={hit.iconUrl} alt="" /> : <I.Palette />}
                </div>
                <div className="si">
                  <div className="sn">{hit.title}</div>
                  <div className="sd">{hit.description}</div>
                </div>
                <button
                  className="btn accent"
                  disabled={busyId === hit.id || installedIds.has(hit.id)}
                  onClick={() => download(hit)}
                >
                  {installedIds.has(hit.id) ? "Added" : busyId === hit.id ? "…" : "Download"}
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      {library.some((pack) => !pack.inThisVersion) && (
        <div className="sg">
          <div className="sgl">Already downloaded</div>
          <div className="scd">
            {/* One copy on disk, shared by every version: adding it here costs
                no download and no extra space. */}
            {library.filter((pack) => !pack.inThisVersion).map((pack) => (
              <div key={pack.fileName} className="sr">
                <div className="pack-icon"><I.Palette /></div>
                <div className="si">
                  <div className="sn">{pack.fileName.replace(/\.zip$/i, "")}</div>
                  <div className="sd">{(pack.size / (1024 * 1024)).toFixed(1)} MB, already on this computer</div>
                </div>
                <button className="btn" disabled={busyId === pack.fileName} onClick={() => addFromLibrary(pack)}>
                  {busyId === pack.fileName ? <I.Spin /> : "Use here"}
                </button>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="sg">
        <div className="sgl">Your {PACK_TYPES[packType].label.toLowerCase()}</div>
        <div className="scd">
          {loadError && <div className="friendly-empty">{loadError}</div>}
          {!loadError && ofType.length === 0 && (
            <div className="empty-panel">Nothing here yet. Search above or import a .zip.</div>
          )}
          {ofType.map((pack) => {
            const meta = STATES[pack.state] || STATES.downloaded;
            return (
              <div key={pack.projectId} className="sr">
                <div className="pack-icon">
                  {pack.iconUrl ? <img src={pack.iconUrl} alt="" /> : <I.Palette />}
                </div>
                <div className="si">
                  <div className="sn">{pack.title}</div>
                  <div className="sd">
                    <span className={`pack-state ${pack.state}`}>{meta.label}</span>
                    {" "}{meta.hint}
                  </div>
                </div>
                {meta.next && (
                  <button
                    className={`btn ${meta.next.state === "enabled" ? "accent" : ""}`}
                    disabled={busyId === pack.projectId}
                    onClick={() => advance(pack)}
                  >
                    {meta.next.label}
                  </button>
                )}
                <button
                  className="btn danger"
                  disabled={busyId === pack.projectId}
                  onClick={() => run(
                    pack.projectId,
                    () => removeInstalledPack({ profileId, projectId: pack.projectId }),
                    `${pack.title} removed`,
                  )}
                >
                  <I.X />
                </button>
              </div>
            );
          })}
        </div>
      </div>

      {packType === "datapack" && (
        <div className="friendly-empty">
          Datapacks apply per world. Breeze keeps them in the profile's datapacks
          folder; copy one into a world's own datapacks folder to use it there.
        </div>
      )}
    </div>
  );
}
