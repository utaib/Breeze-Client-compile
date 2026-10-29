import { useEffect, useRef, useState } from "react";
import { I } from "../../ui/icons";
import { PlayerStage } from "../../components/PlayerStage";
import { Panel, Row, Stat } from "../../components/Shared";
import { NewsPanel } from "../news/NewsPanel";
import { isFriendOnline } from "../../services/breezeApi";

/**
 * Everything the play page can show, as widgets. Each one is existing
 * launcher content (nothing here talks to the API on its own); `ctx` is the
 * launcher state and actions BreezeApp hands over.
 *
 *   min / max      size limits in grid cells (12 columns, rows of ROW_PX)
 *   defaultPlace   where it sits in the default layout (absent: only added
 *                  from the widget list)
 *   required       cannot be removed (Play)
 *
 * Adding a widget is one entry here; layout.js validates any layout against
 * this list, so a saved layout can only name widgets that exist.
 */

/** Size of one grid cell's row and the gap, kept in step with the CSS. */
export const ROW_PX = 36;
export const GAP_PX = 12;

/** Watch an element's size, for content that draws to a fixed pixel size. */
function useBoxSize() {
  const ref = useRef(null);
  const [size, setSize] = useState({ w: 0, h: 0 });
  useEffect(() => {
    const el = ref.current;
    if (!el || typeof ResizeObserver !== "function") return undefined;
    const ro = new ResizeObserver(([entry]) => {
      const { width, height } = entry.contentRect;
      setSize((s) => (Math.abs(s.w - width) < 2 && Math.abs(s.h - height) < 2 ? s : { w: Math.round(width), h: Math.round(height) }));
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, size];
}

function PlayerWidget({ ctx }) {
  const [ref, size] = useBoxSize();
  const { session, equippedCapeData, userRole } = ctx;
  const frames = Array.isArray(equippedCapeData?.animation_frames) && equippedCapeData.animation_frames.length > 1
    ? equippedCapeData.animation_frames
    : null;
  return (
    <div className="identity-card dash-card">
      <div className="identity-render" ref={ref}>
        {size.w > 40 && size.h > 80 && (
          <PlayerStage
            name={session.username || null}
            skinUrl={ctx.userSkinUrl}
            capeUrl={equippedCapeData?.image_url || null}
            animationFrames={frames}
            animationFps={equippedCapeData?.animation_fps || null}
            width={Math.min(size.w, 520)}
            height={Math.max(160, size.h - 30)}
          />
        )}
      </div>
      <div className="identity-meta">
        <div className="identity-name">{session.username || "Player"}</div>
        <div className="identity-sub">
          <span className="status-dot online" />
          {userRole !== "user" ? <span className="identity-role">{userRole}</span> : "Minecraft account"}
        </div>
        {equippedCapeData?.name && <div className="identity-cape">Cape · {equippedCapeData.name}</div>}
        <div className="identity-actions">
          <button className="btn" onClick={() => ctx.setShowAccountPanel(true)}>Accounts</button>
          <button className="btn" onClick={() => ctx.setPage("custom")}>Wardrobe</button>
        </div>
      </div>
    </div>
  );
}

function LaunchWidget({ ctx }) {
  const {
    selectedVersionId, setSelectedVersionId, visibleVersions, settings, patchSettings, loader,
    breezeModIssue, compatibility, modVersionsAvailable, launchState, handleLaunch, canLaunch,
    installedMods, activeMods, session,
  } = ctx;
  const progress = Math.min(100, Math.max(0, Number(launchState.progress) || 0));
  return (
    <div className="pc dash-card">
      <div className="ph">
        <div><div className="pe">Ready to Launch</div><div className="pt">Fabric <span>{selectedVersionId || "Loading"}</span></div></div>
        <div className="fabric-badge"><div className="fabric-dot" /><span>{loader?.loaderVersion || "Resolving"}</span></div>
      </div>
      <div className="vr">
        <select className="vsel" aria-label="Minecraft version" value={selectedVersionId} onChange={(event) => setSelectedVersionId(event.target.value)}>
          {visibleVersions.map((version) => <option key={version.id} value={version.id}>{version.id}</option>)}
        </select>
        <button className="btn" onClick={() => patchSettings({ includeSnapshots: !settings.includeSnapshots })}>Snapshots {settings.includeSnapshots ? "On" : "Off"}</button>
      </div>
      {breezeModIssue ? (
        <div className="mod-availability"><span className="status-dot" />Breeze Mod not loaded: {breezeModIssue}</div>
      ) : compatibility ? (
        <div className={`compat compat-${compatibility.overall}`}>
          <div className="compat-summary">
            <span className={`status-dot ${compatibility.overall === "supported" ? "online" : ""}`} />
            {compatibility.summary}
          </div>
          <ul className="compat-steps">
            {compatibility.steps.map((step) => (
              <li key={step.key} className={`compat-step ${step.status}`}>
                <span className="compat-label">{step.label}</span>
                <span className="compat-detail">{step.detail}</span>
              </li>
            ))}
          </ul>
        </div>
      ) : modVersionsAvailable !== null && (
        <div className="mod-availability">
          <span className={`status-dot ${modVersionsAvailable.includes(selectedVersionId) ? "online" : ""}`} />
          {modVersionsAvailable.includes(selectedVersionId) ? "Breeze Mod ready for this version" : "No Breeze Mod build for this version yet, launches without it"}
        </div>
      )}
      <div className="prg">
        <div className="prg-m"><span>{launchState.message}</span><span>{Math.round(progress)}%</span></div>
        <div className="prg-tr"><div className="prg-b" style={{ width: `${progress}%` }} /></div>
      </div>
      <button className={`launch ${launchState.status}`} onClick={handleLaunch} disabled={!canLaunch}>
        {launchState.status === "launching" ? "Launching…" : launchState.status === "running" ? "Running" : `Launch ${selectedVersionId || "Minecraft"}`}
      </button>
      <div className="ss">
        <Stat label="Mods" value={installedMods.length} sub={`${activeMods.length} active`} />
        <Stat label="RAM" value={`${Math.round(settings.allocatedRamMb / 1024)} GB`} sub="allocated" />
        <Stat label="Backend" value={session.breezeToken ? "Linked" : "Offline"} sub="Breeze API" />
      </div>
    </div>
  );
}

function QuickWidget({ ctx }) {
  const { settings, setPage, apiStatus } = ctx;
  return (
    <div className="dash-card dash-panel">
      <Panel title="Quick settings">
        <Row title="Performance" desc={settings.performanceProfile} action={<button className="btn" onClick={() => setPage("settings")}>{Math.round(settings.allocatedRamMb / 1024)} GB RAM</button>} />
        <Row title="Backend" desc={apiStatus} />
      </Panel>
    </div>
  );
}

function RecordingsWidget({ ctx }) {
  return (
    <div className="dash-card dash-panel">
      <Panel title="Recordings">
        <Row title="Clips and replays" desc="Captures from your games" action={<button className="btn" onClick={() => ctx.setShowRecordings(true)}>Open</button>} />
      </Panel>
    </div>
  );
}

function ModsWidget({ ctx }) {
  const { installedMods, activeMods, setPage, nativeDesktop, onImportJar, onImportPack, onExportPack, exportBusy, selectedVersionId } = ctx;
  return (
    <div className="dash-card dash-panel">
      <Panel title={`Mods · ${selectedVersionId || "no version"}`}>
        <Row title={`${installedMods.length} installed`} desc={`${activeMods.length} active`} action={<button className="btn" onClick={() => setPage("mods")}>Manage</button>} />
        <div className="dash-actions">
          <button className="btn" onClick={onImportJar}><I.Upload /> Add a mod</button>
          {nativeDesktop && <button className="btn" onClick={onImportPack}><I.Layers /> Import modpack</button>}
          {nativeDesktop && <button className="btn" onClick={onExportPack} disabled={exportBusy}>{exportBusy ? <I.Spin /> : <I.Download />} Export</button>}
        </div>
      </Panel>
    </div>
  );
}

function WardrobeWidget({ ctx }) {
  const { equippedCapeData, setPage } = ctx;
  return (
    <div className="dash-card dash-panel">
      <Panel title="Wardrobe">
        <Row title={equippedCapeData?.name || "No cape"} desc={equippedCapeData?.name ? "Equipped" : "Pick one in the Wardrobe"} action={<button className="btn" onClick={() => setPage("custom")}>Open</button>} />
      </Panel>
    </div>
  );
}

function FriendsWidget({ ctx }) {
  const { friends, setPage } = ctx;
  const list = Array.isArray(friends) ? friends : [];
  const online = list.filter((f) => isFriendOnline(f.user?.last_seen || f.user?.lastSeen || f.lastSeen));
  return (
    <div className="dash-card dash-panel">
      <Panel title={`Friends · ${online.length} online`}>
        {online.slice(0, 4).map((f) => (
          <Row key={f.id || f.uuid} title={f.user?.username || "Friend"} desc="Online" />
        ))}
        {online.length === 0 && <div className="dash-empty">{list.length ? "Nobody is online right now." : "Add friends on the Social page."}</div>}
        <div className="dash-actions"><button className="btn" onClick={() => setPage("social")}><I.Users /> Social</button></div>
      </Panel>
    </div>
  );
}

function NewsWidget({ ctx }) {
  return (
    <div className="dash-card dash-panel">
      <NewsPanel notifications={ctx.notifications} />
    </div>
  );
}

export const WIDGETS = {
  player: {
    id: "player", title: "Your player", description: "Your character on a stage, with your cape and name.",
    icon: "User", min: { w: 3, h: 7 }, max: { w: 8, h: 16 }, defaultPlace: { x: 0, y: 0, w: 4, h: 12 },
    Component: PlayerWidget,
  },
  launch: {
    id: "launch", title: "Play", description: "Version, readiness and the Launch button.",
    icon: "Play", required: true, min: { w: 4, h: 9 }, max: { w: 8, h: 16 }, defaultPlace: { x: 4, y: 0, w: 5, h: 12 },
    Component: LaunchWidget,
  },
  news: {
    id: "news", title: "News", description: "Announcements from the Breeze team.",
    icon: "Bell", min: { w: 3, h: 4 }, max: { w: 6, h: 14 }, defaultPlace: { x: 9, y: 0, w: 3, h: 6 },
    Component: NewsWidget,
  },
  quick: {
    id: "quick", title: "Quick settings", description: "Performance preset, memory and the Breeze backend.",
    icon: "Sliders", min: { w: 3, h: 3 }, max: { w: 6, h: 6 }, defaultPlace: { x: 9, y: 6, w: 3, h: 3 },
    Component: QuickWidget,
  },
  recordings: {
    id: "recordings", title: "Recordings", description: "Open your clips and replay captures.",
    icon: "Image", min: { w: 3, h: 3 }, max: { w: 6, h: 4 }, defaultPlace: { x: 9, y: 9, w: 3, h: 3 },
    Component: RecordingsWidget,
  },
  mods: {
    id: "mods", title: "Mods", description: "Mod count, add a mod, import or export a modpack.",
    icon: "Layers", min: { w: 3, h: 4 }, max: { w: 6, h: 6 },
    Component: ModsWidget,
  },
  wardrobe: {
    id: "wardrobe", title: "Wardrobe", description: "Your equipped cape and a way into the Wardrobe.",
    icon: "Palette", min: { w: 3, h: 3 }, max: { w: 6, h: 4 },
    Component: WardrobeWidget,
  },
  friends: {
    id: "friends", title: "Friends", description: "Who is online right now.",
    icon: "Users", min: { w: 3, h: 4 }, max: { w: 6, h: 10 },
    Component: FriendsWidget,
  },
};
