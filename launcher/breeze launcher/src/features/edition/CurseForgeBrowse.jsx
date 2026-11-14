import { useCallback, useEffect, useRef, useState } from "react";
import { getCurseForgeFiles, searchCurseForge } from "../../services/breezeApi";
import { installCurseForgeMod, openExternalUrl, isRunningInTauri } from "../../services/nativeBridge";
import { loaderName } from "../loaders/loaders";

/**
 * Browse on CurseForge (1.2.0): Fabric mods for the version shown, searched
 * through the Breeze API, which holds CurseForge's key.
 *
 * Install takes the newest file for this version that CurseForge offers to
 * other launchers (release before beta before alpha), then the mods it
 * requires, each checked the same way. A mod whose author keeps it on
 * CurseForge has no such file: its row opens CurseForge's page instead, and
 * nothing is fetched another way.
 */
// The Mods page's own three icons for a row's button (it keeps its set to itself).
const Check = () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round"><path d="M20 6 9 17l-5-5" /></svg>;
const Down = () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round"><path d="M12 5v14m-7-7l7 7 7-7" /></svg>;
const Spin = () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" className="installing"><path d="M21 12a9 9 0 11-6.219-8.56" /></svg>;

const ORDER = { release: 0, beta: 1, alpha: 2 };
const MAX_WITH_DEPENDENCIES = 12;

/** The file to install: for this version and loader, downloadable, the most stable, then the newest. */
export function pickFile(files, gameVersion, loader = "fabric") {
  // Quilt loads Fabric's mods too; of two equally stable files, the one made for Quilt is taken.
  const wanted = loader === "quilt" ? ["quilt", "fabric"] : [loader];
  const rank = (f) => wanted.findIndex((name) => f.loaders.includes(name));
  return (files || [])
    .filter((f) => f.download && f.sha1 && rank(f) >= 0 && f.gameVersions.includes(gameVersion))
    .sort((a, b) => (ORDER[a.release] - ORDER[b.release]) || (rank(a) - rank(b)) || String(b.date || "").localeCompare(String(a.date || "")))[0] || null;
}

const openPage = (url) => {
  if (!url) return;
  if (isRunningInTauri()) openExternalUrl(url).catch(() => {});
  else window.open(url, "_blank", "noopener,noreferrer");
};

export default function CurseForgeBrowse({ token, query, gameVersion, installedMods, nativeDesktop, onInstalled, notify, loader = "fabric", profileId }) {
  const loaderLabel = loaderName(loader);
  const [results, setResults] = useState([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(null);
  // Projects found to have no file for other launchers: their row links out.
  const [kept, setKept] = useState({});
  const asked = useRef(0);

  const load = useCallback(async (index) => {
    if (!token || !gameVersion) return;
    const mine = ++asked.current;
    setLoading(true);
    setError("");
    try {
      const res = await searchCurseForge(token, { query, gameVersion, loader, index });
      if (mine !== asked.current) return;
      setResults((cur) => (index === 0 ? res.results : [...cur, ...res.results]));
      setTotal(res.total || 0);
    } catch (e) {
      if (mine !== asked.current) return;
      if (index === 0) setResults([]);
      setError(e?.message || "CurseForge is unavailable right now.");
    } finally {
      if (mine === asked.current) setLoading(false);
    }
  }, [token, query, gameVersion, loader]);

  useEffect(() => {
    const timer = setTimeout(() => load(0), query ? 300 : 0);
    return () => clearTimeout(timer);
  }, [load, query]);

  const installedIds = new Set((installedMods || []).map((m) => m.canonicalId || m.projectId));
  const isInstalled = (id) => installedIds.has(`curseforge:${id}`);

  const install = async (mod) => {
    if (!nativeDesktop) { notify?.("!", "Installing mods needs the desktop app."); return; }
    setBusy(mod.id);
    const seen = new Set();
    const queue = [{ id: mod.id, project: mod }];
    let installed = 0;
    try {
      while (queue.length && seen.size < MAX_WITH_DEPENDENCIES) {
        const next = queue.shift();
        if (seen.has(next.id)) continue;
        seen.add(next.id);
        if (next.id !== mod.id && isInstalled(next.id)) continue;
        const info = await getCurseForgeFiles(token, next.id, { gameVersion, loader });
        const file = pickFile(info.files, gameVersion, loader);
        if (!file) {
          if (next.id === mod.id) {
            // Nothing CurseForge lets another launcher download: the player gets it there.
            setKept((cur) => ({ ...cur, [mod.id]: info.websiteUrl || mod.websiteUrl || "" }));
            notify?.("!", info.files.length
              ? `${mod.name} is only available on CurseForge's own site.`
              : `${mod.name} has no ${loaderLabel} file for Minecraft ${gameVersion}.`);
            return;
          }
          notify?.("!", `${info.name || "A mod it needs"} could not be added from here. Get it on CurseForge.`);
          continue;
        }
        await installCurseForgeMod({
          profileId: profileId || gameVersion, gameVersion,
          loader: file.loaders.includes(loader) ? loader : "fabric",
          modId: next.id, fileId: file.id, fileName: file.fileName, downloadUrl: file.download, sha1: file.sha1,
          title: next.project?.name || info.name, slug: next.project?.slug || "", summary: next.project?.summary || null,
          iconUrl: next.project?.icon || null, versionName: file.name,
        });
        installed += 1;
        for (const id of file.required) if (!seen.has(id)) queue.push({ id, project: null });
      }
      await onInstalled?.();
      notify?.("ok", installed > 1 ? `${mod.name} installed, with ${installed - 1} it needs` : `${mod.name} installed`);
    } catch (e) {
      notify?.("!", e?.message || String(e));
    } finally {
      setBusy(null);
    }
  };

  return (
    <>
      {results.map((mod) => {
        const done = isInstalled(mod.id);
        const out = kept[mod.id] !== undefined || !mod.distributable;
        const page = kept[mod.id] || mod.websiteUrl;
        return (
          <div key={mod.id} className={`mr ${done ? "ins" : ""}`}>
            <div className="mi">
              {mod.icon ? <img src={mod.icon} alt="" style={{ width: 24, height: 24, borderRadius: 6 }} /> : null}
            </div>
            <div className="mb">
              <div className="mn2">{mod.name}</div>
              <div className="mm">{mod.summary}{mod.authors ? ` By ${mod.authors}.` : ""}</div>
            </div>
            <div className="badge fab">{gameVersion}</div>
            {out && !done ? (
              <button className="btn" onClick={() => openPage(page)} disabled={!page} aria-label={`Open ${mod.name} on CurseForge`}>
                On CurseForge
              </button>
            ) : (
              <button
                className={`mod-btn ${done ? "ok" : "get"}`}
                onClick={() => !done && install(mod)}
                disabled={busy !== null || done}
                aria-label={done ? `${mod.name} is installed` : `Install ${mod.name}`}
                title={done ? "Installed" : "Install"}
              >
                {busy === mod.id ? <Spin /> : done ? <Check /> : <Down />}
              </button>
            )}
          </div>
        );
      })}
      {results.length < total && (
        <button className="btn accent w-full" onClick={() => load(results.length)} disabled={loading}>
          {loading ? "Loading…" : "Load More"}
        </button>
      )}
      {!loading && results.length === 0 && (
        <div style={{ textAlign: "center", padding: "36px 0", color: "var(--text-faint)", fontSize: 12 }}>
          {error || `No ${loaderLabel} mods found on CurseForge for this version`}
        </div>
      )}
    </>
  );
}
