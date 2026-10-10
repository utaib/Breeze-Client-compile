import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  acceptFriendRequest,
  declineFriendRequest,
  getPendingGifts,
  markGiftSeen,
  equipCape,
  equipCosmetic,
  getAllCapes,
  getOwnedCosmetics,
  setCosmeticPlacement,
  getSystemVersion,
  getMe,
  getUserCapes,
  getVersionManifest,
  checkForUpdate,
  getFeatureFlags,
  getWallet,
  getWalletPacks,
  getAdBoxes,
  purchaseWindCharges,
  purchaseItemWithWc,
  removeFriend,
  priceInWc,
  grantGift,
  listCosmetics,
  listFriends,
  listMessages,
  listNotifications,
  loginWithMinecraftToken,
  markAllNotificationsRead,
  dismissNotification,
  dismissNotifications,
  searchSocialUsers,
  sendFriendRequest,
  sendGift,
  sendMessage,
  syncUserData,
  unequipCape,
  unequipCosmetic,
  getCreatorCapes,
  getCreatorStats,
  updatePresence,
  isFriendOnline,
  getApiBaseUrl,
  listAvailableModVersions,
  isCreatorTier,
} from "./services/breezeApi";
import { loadMinecraftVersions, resolveFabricLoader } from "./services/minecraftVersions";
import usePolling from "./hooks/usePolling";
import { searchModrinthProjects } from "./services/modrinth";
import {
  applyPerformanceProfile,
  beginMicrosoftAuth,
  clearSavedSession,
  detectImportableClients,
  downloadAndInstallUpdate,
  getPlatform,
  exportMrpack,
  getVersionCompatibility,
  listenToUpdater,
  revealPath,
  ensureJavaRuntime,
  getLauncherSettings,
  saveCustomBackground,
  saveCustomBackgroundStaged,
  loadCustomBackground,
  clearCustomBackground,
  getJavaRuntimeStatus,
  getSystemMemoryInfo,
  importCustomMod,
  importEverything,
  importFeatherPreferences,
  importClientMods,
  importLocalServerFile,
  importModrinthAppPreferences,
  importMrpack,
  stageFileForImport,
  clearStagedImport,
  importVanillaPreferences,
  installModrinthMod,
  copyLocalModToProfile,
  isRunningInTauri,
  launchMinecraft,
  listInstalledMods,
  listenToFileDrops,
  listenToJavaRuntime,
  listenToLaunchStage,
  listenToLaunchComplete,
  listenToLaunchError,
  listenToLaunchLog,
  openLocalServerFolder,
  openExternalUrl,
  prepareLocalServer,
  readLocalServerLog,
  removeInstalledMod,
  restoreSavedSession,
  listSavedAccounts,
  switchAccount,
  removeSavedAccount,
  signOutAllAccounts,
  setInstalledModEnabled,
  startLocalServer,
  stopLocalServer,
  updateLauncherSettings,
} from "./services/nativeBridge";
import PromoCodeInput from "./components/PromoCodeInput";
import RecommendedModsPanel from "./components/RecommendedModsPanel";
import RecordingsPanel from "./components/RecordingsPanel";
import PromoPage from "./features/promo/PromoPage";
import "./BreezeV2.css";
import SettingsPage from "./components/pages/SettingsPage";
import ConsolePage from "./components/pages/ConsolePage";
import ModsPage from "./components/pages/ModsPage";
import StylePage from "./components/pages/StylePage";
import ResourcePacksPage from "./components/pages/ResourcePacksPage";
import StorePage from "./components/pages/StorePage";
import AdBox from "./components/AdBox";
import OwnerAdminPage from "./features/admin/OwnerAdminPage";
import AudiusFMPage from "./features/fm/AudiusFMPage";
import FMMiniPlayer from "./features/fm/FMMiniPlayer";
import HostingPage from "./features/hosting/HostingPage";
import LoginGate from "./components/LoginGate";
import SplashScene from "./components/SplashScene";
import useDiscordPresence from "./hooks/useDiscordPresence";
import { useBreezeFM } from "./features/fm/BreezeFMProvider";
import PageErrorBoundary from "./components/PageErrorBoundary";
import NotificationPanel from "./components/NotificationPanel";
import CapeUploadModal from "./components/CapeUploadModal";
import CosmeticUploadModal from "./components/CosmeticUploadModal";
import PayEquipCapeModal from "./components/PayEquipCapeModal";
import TransferModsDialog from "./components/TransferModsDialog";
import { PlayerViewer3D, resolveSkinUrl } from "./components/SkinViewer3D";
import { WEBSITE_URL } from "./ui/urls";
import { Panel, Row, Stat } from "./components/Shared";
import SocialPage from "./features/social/SocialPage";
import { I, CRAFATAR_AVATAR } from "./ui/icons";
import GiftsPage from "./features/gifts/GiftsPage";

// Injected by Vite from package.json. Never hand-edit this: a literal here is
// what made the launcher report an update available forever, because the
// constant said 1.0.10 while the shipped build was three versions past it.
const APP_VERSION = __APP_VERSION__;
/**
 * The update banner's shape, from a /versions/check answer.
 *
 * Signed in, /versions/check is the only source of updates: it knows the
 * account's role, so creators, developers, admins and owners are offered
 * test-channel builds and everyone else only published releases.
 */
function updateFromCheck(update) {
  return {
    latestVersion: update.version,
    downloadUrl: update.url,
    // The installer is verified against this before it runs, and the updater
    // refuses an update without it.
    sha256: update.sha256 || null,
    fileName: update.fileName || null,
    channel: update.channel,
    changelog: update.changelog,
    mandatory: update.mandatory,
    notesUrl: null,
  };
}

const API_FALLBACK_VERSION = "0.1.0-beta";

// Each theme is a full material identity: background treatment, surface
// opacity, blur level, border style, and shadows are defined per-theme in
// BreezeV2.css (.theme-<id> blocks). The entries here carry the accent color
// ramp plus display metadata for the theme gallery.
const THEMES = [
  { id: "glass", name: "Breeze Glass", tagline: "Deep blue glass", accent: "rgba(120,178,255,0.95)", accentDim: "rgba(100,160,255,0.32)", accentGhost: "rgba(90,148,255,0.12)", borderAccent: "rgba(110,168,255,0.28)", glow: "rgba(90,148,255,0.16)" },
  { id: "black", name: "Onyx", tagline: "Matte black, zero noise", accent: "rgba(210,224,248,0.92)", accentDim: "rgba(190,205,235,0.28)", accentGhost: "rgba(180,200,235,0.08)", borderAccent: "rgba(190,205,235,0.22)", glow: "rgba(190,205,235,0.1)" },
  { id: "white", name: "Frost", tagline: "Light frosted glass", accent: "rgba(45,105,235,0.92)", accentDim: "rgba(45,105,235,0.3)", accentGhost: "rgba(45,105,235,0.08)", borderAccent: "rgba(45,105,235,0.24)", glow: "rgba(45,105,235,0.12)" },
  { id: "silver", name: "Titanium", tagline: "Brushed metal panels", accent: "rgba(186,204,236,0.94)", accentDim: "rgba(166,186,222,0.3)", accentGhost: "rgba(156,176,214,0.1)", borderAccent: "rgba(166,186,222,0.26)", glow: "rgba(156,176,214,0.12)" },
  { id: "midnight", name: "Dusk", tagline: "Violet gradient wash", accent: "rgba(172,142,255,0.95)", accentDim: "rgba(172,142,255,0.32)", accentGhost: "rgba(172,142,255,0.12)", borderAccent: "rgba(172,142,255,0.26)", glow: "rgba(172,142,255,0.14)" },
  { id: "forest", name: "Grove", tagline: "Mossy dark green", accent: "rgba(98,214,152,0.94)", accentDim: "rgba(98,214,152,0.3)", accentGhost: "rgba(98,214,152,0.1)", borderAccent: "rgba(98,214,152,0.24)", glow: "rgba(98,214,152,0.12)" },
  { id: "ember", name: "Ember", tagline: "Warm charcoal + fire", accent: "rgba(255,146,92,0.95)", accentDim: "rgba(255,146,92,0.32)", accentGhost: "rgba(255,146,92,0.11)", borderAccent: "rgba(255,146,92,0.26)", glow: "rgba(255,146,92,0.13)" },
  { id: "arctic", name: "Arctic", tagline: "Ice blue clarity", accent: "rgba(118,212,255,0.95)", accentDim: "rgba(118,212,255,0.3)", accentGhost: "rgba(118,212,255,0.1)", borderAccent: "rgba(118,212,255,0.24)", glow: "rgba(118,212,255,0.12)" },
  { id: "rose", name: "Rose Noir", tagline: "Plum dark + pink", accent: "rgba(255,122,188,0.95)", accentDim: "rgba(255,122,188,0.3)", accentGhost: "rgba(255,122,188,0.11)", borderAccent: "rgba(255,122,188,0.24)", glow: "rgba(255,122,188,0.12)" },
  { id: "abyss", name: "Abyss", tagline: "Bottom-of-ocean teal", accent: "rgba(84,222,202,0.92)", accentDim: "rgba(84,222,202,0.28)", accentGhost: "rgba(84,222,202,0.09)", borderAccent: "rgba(84,222,202,0.22)", glow: "rgba(84,222,202,0.1)" },
];

const DEFAULT_SETTINGS = {
  allocatedRamMb: 4096,
  theme: "glass",
  includeSnapshots: false,
  performanceProfile: "Performance",
  prewarmEnabled: true,
  selectedVersion: null,
  modAutoUpdate: true,
  graphicsPerformance: true,
  browserAccel: "auto",
  afterLaunch: "keep-open",
  minecraftPath: "",
  resolutionWidth: null,
  resolutionHeight: null,
  importedFromFeather: false,
  autoUpdate: true,
};

const PAGE_NAMES = {
  play: "Launch",
  mods: "Mods",
  store: "Store",
  social: "Social",
  gifts: "Gifts",
  hosting: "Hosting",
  radio: "Breeze FM",
  custom: "Style",
  settings: "Settings",
  console: "Console",
  admin: "Admin",
  promos: "Promos",
};

// The launcher shows no ads: AdSense does not allow its ads in desktop apps,
// and the Rewards tab that paid a discount for watching them went with them in
// 1.0.30.
// Hosting is left out until it ships: its page is only a placeholder, and
// unfinished features are hidden rather than announced (CLAUDE.md). The page
// and the Rust local-server commands stay for when it returns.
const NAV_ITEMS = [
  ["play", "Play", "Play"],
  ["mods", "Mods", "Layers"],
  ["packs", "Packs", "Image"],
  ["store", "Store", "Store"],
  ["social", "Social", "Users"],
  ["gifts", "Gifts", "Gift"],
  ["radio", "FM", "Music"],
  ["custom", "Style", "Palette"],
  ["console", "Console", "Terminal"],
  ["settings", "Config", "Sliders"],
];

function getError(error) {
  if (typeof error === "string") return error;
  if (error instanceof Error) return error.message;
  return "Unknown error";
}

function formatTime(date = new Date()) {
  return [date.getHours(), date.getMinutes(), date.getSeconds()].map((part) => String(part).padStart(2, "0")).join(":");
}

function isNewerVersion(remote, local) {
  const parse = (value) => String(value || "0").replace(/^v/, "").split(".").map((part) => parseInt(part, 10) || 0);
  const a = parse(remote);
  const b = parse(local);
  for (let i = 0; i < Math.max(a.length, b.length, 3); i += 1) {
    if ((a[i] || 0) !== (b[i] || 0)) return (a[i] || 0) > (b[i] || 0);
  }
  return false;
}

/**
 * Newest genuine release, by release date rather than manifest order.
 *
 * The Mojang manifest is ordered newest first and puts snapshots ahead of
 * releases, so taking the head of it handed a brand new snapshot to people who
 * had never asked for one, and the settings write then saved it as the default.
 */
function newestRelease(versions) {
  const newest = (list) => list.slice().sort((a, b) => String(b.releaseTime || "").localeCompare(String(a.releaseTime || "")))[0] || null;
  const releases = versions.filter((version) => version.type === "release");
  return newest(releases.filter((version) => version.fabricSupported)) || newest(releases);
}

/**
 * Version to start on: what was actually launched last, then whatever the
 * picker was left on, then the newest release Fabric supports. A snapshot is
 * only ever offered when the user has asked to see snapshots.
 */
function chooseVersionId(versions, remembered, includeSnapshots) {
  for (const candidate of remembered) {
    if (candidate && versions.some((version) => version.id === candidate)) return candidate;
  }
  const release = newestRelease(versions);
  if (release) return release.id;
  const visible = versions.filter((version) => includeSnapshots || version.type === "release");
  return visible[0]?.id || "";
}

/**
 * Two records are the same installed mod when any of the ids they carry agree.
 * Matching on projectId alone meant a record handed back under its canonical id
 * never replaced the row it came from, which is how a toggle appeared to revert
 * the moment it was flipped.
 */
function isSameMod(a, b) {
  if (!a || !b) return false;
  const ids = (record) => [record.canonicalId, record.projectId, record.projectSlug].filter(Boolean);
  const other = new Set(ids(b));
  return ids(a).some((id) => other.has(id));
}

const LOCAL_INSTANCES_KEY = "breeze.localInstances";

/** Version ids this machine has actually launched. The version list comes off
 *  the network, so without these the picker is empty when Breeze is offline and
 *  an instance sitting on disk cannot be launched at all. */
function readLocalInstanceIds() {
  try {
    const parsed = JSON.parse(window.localStorage.getItem(LOCAL_INSTANCES_KEY) || "[]");
    return Array.isArray(parsed) ? parsed.filter((id) => typeof id === "string" && id) : [];
  } catch {
    return [];
  }
}

function rememberLocalInstanceId(id) {
  const next = id ? [id, ...readLocalInstanceIds().filter((item) => item !== id)].slice(0, 24) : readLocalInstanceIds();
  try { window.localStorage.setItem(LOCAL_INSTANCES_KEY, JSON.stringify(next)); } catch { /* storage unavailable, the offline list just won't persist */ }
  return next;
}

/**
 * Read a Breeze runtime mod failure out of a launch stage or log line.
 *
 * Neither event carries a field for this, and the Rust side deliberately
 * carries on without the mod rather than failing the whole launch, so the text
 * is the only signal there is that the game started without Breeze in it.
 */
function readBreezeModFailure(text) {
  if (typeof text !== "string") return null;
  if (!/breeze\s+(runtime\s+)?mod|runtime\.jar/i.test(text)) return null;
  if (!/fail|could not|couldn't|cannot|unable|missing|not available|without/i.test(text)) return null;
  return text.replace(/^\[[^\]]*\]\s*/, "").trim() || null;
}

function StoreAssetImage({ src, alt, slot }) {
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [src]);
  if (!src || failed) return <CosmeticFallback slot={slot} label={alt} />;
  return <img src={src} alt={alt} className="store-asset-img" loading="lazy" decoding="async" onError={() => setFailed(true)} />;
}

function CosmeticFallback({ slot = "cosmetic", label = "Breeze cosmetic" }) {
  return <div className="cosmetic-fallback" aria-label={label}><I.Logo /><span>{slot}</span></div>;
}

// Coloured Wind Charges that fall from the top of the splash and, as they rain
// down, the splash wipes away to reveal the launcher underneath. Values are
// index-derived (deterministic) so the shower reads as an even, varied fall.
const BZ_CHARGE_TEXTURES = [
  "/blue_wind_charge.png",
  "/purple_wind_charge.png",
  "/pink_wind_charge.png",
  "/red_wind_charge.png",
  "/wind_charge.png",
];
const BZ_FALLING = Array.from({ length: 20 }, (_, i) => ({
  src: BZ_CHARGE_TEXTURES[i % BZ_CHARGE_TEXTURES.length],
  left: (i * 53 + 5) % 100, // spread across the width
  size: 26 + ((i * 17) % 30), // 26-56px
  delay: 1.35 + ((i * 7) % 20) / 10, // staggered 1.35-3.25s
  dur: 1.5 + ((i * 11) % 14) / 10, // 1.5-2.9s fall time
  rot: (i % 2 ? 1 : -1) * (140 + ((i * 37) % 260)),
  drift: ((i % 3) - 1) * 46, // slight sideways drift
}));

function SplashScreen({ onDone }) {
  useEffect(() => {
    const timer = setTimeout(onDone, 3600);
    return () => clearTimeout(timer);
  }, [onDone]);

  return (
    <div className="bz-splash">
      {/* Background + lockup fade away to unveil the launcher underneath. */}
      <div className="bz-splash-bg" />
      <div className="bz-splash-lockup">
        <div className="bz-splash-logo">
          <img src="/breeze-logo.png" alt="Breeze Client" onError={(event) => { event.currentTarget.style.display = "none"; }} />
        </div>
        <div className="bz-splash-text">
          <div className="bz-splash-title">Breeze Client</div>
          <div className="bz-splash-tagline">A Minecraft client that doesn&apos;t get in your way.</div>
        </div>
      </div>
      {/* Falling coloured Wind Charges. */}
      <div className="bz-fall" aria-hidden="true">
        {BZ_FALLING.map((c, i) => (
          <img
            key={i}
            src={c.src}
            alt=""
            className="bz-charge"
            onError={(event) => { event.currentTarget.style.display = "none"; }}
            style={{
              left: `${c.left}%`,
              width: `${c.size}px`,
              height: `${c.size}px`,
              "--rot": `${c.rot}deg`,
              "--drift": `${c.drift}px`,
              animationDelay: `${c.delay}s`,
              animationDuration: `${c.dur}s`,
            }}
          />
        ))}
      </div>
    </div>
  );
}

function App() {
  const nativeDesktop = isRunningInTauri();
  const customModInputRef = useRef(null);

  const [showSplash, setShowSplash] = useState(true);
  /**
   * True while a saved login is being revived at startup.
   *
   * Only meaningful on desktop; in a browser there is no stored session to
   * restore, so it settles immediately and the gate shows at once.
   */
  const [restoringSession, setRestoringSession] = useState(isRunningInTauri());
  const [page, setPage] = useState("play");
  const [toast, setToast] = useState(null);
  const [settings, setSettings] = useState(DEFAULT_SETTINGS);
  const [memoryInfo, setMemoryInfo] = useState({ totalRamMb: 16384, safeMaxRamMb: 12288, recommendedRamMb: 4096 });
  const [session, setSession] = useState(null);
  const [profile, setProfile] = useState(null);
  const [accounts, setAccounts] = useState([]);
  const [activeAccountIdx, setActiveAccountIdx] = useState(0);
  const [showAccountPanel, setShowAccountPanel] = useState(false);
  const [switchingAccount, setSwitchingAccount] = useState(null);
  // The token whose responses are currently allowed to commit to state. Reads
  // synchronously (unlike `session`) so in-flight requests from a previous
  // account can be dropped the instant a switch starts.
  const activeTokenRef = useRef(null);
  // Versions worth restoring on startup, most wanted first: the one actually
  // launched last, then whatever the picker was left on. State rather than a
  // ref so the effect that chooses a version re-runs once the settings land,
  // whichever way it races the version list.
  const [rememberedVersions, setRememberedVersions] = useState([]);
  /** Both must settle before a Minecraft version is chosen, so the saved
   *  preference can never be beaten to it by the default. */
  const [versionsLoaded, setVersionsLoaded] = useState(false);
  const [settingsLoaded, setSettingsLoaded] = useState(!isRunningInTauri());
  const [authMessage, setAuthMessage] = useState("Microsoft account required");
  const [authProgress, setAuthProgress] = useState(null);
  /** True from the moment sign-in starts until it succeeds or fails, so the
   *  gate can show a real signing-in state instead of an idle button. */
  const [authBusy, setAuthBusy] = useState(false);
  const [apiStatus, setApiStatus] = useState("Checking Breeze API");
  const [breezeVersions, setBreezeVersions] = useState(null);
  const [modVersionsAvailable, setModVersionsAvailable] = useState(null);
  const [updateRelease, setUpdateRelease] = useState(null);
  /** The launcher entry from the version manifest, held until the auto-update
   *  setting has actually loaded so the two can be compared honestly. */
  const [launcherManifest, setLauncherManifest] = useState(null);
  const [updateError, setUpdateError] = useState(null);
  const [updateState, setUpdateState] = useState(null); // { stage, message } while installing

  const [allVersions, setAllVersions] = useState([]);
  const [localInstanceIds, setLocalInstanceIds] = useState(readLocalInstanceIds);
  const [selectedVersionId, setSelectedVersionId] = useState("");
  const [loader, setLoader] = useState(null);
  const [launchState, setLaunchState] = useState({ status: "idle", message: "Waiting for launch", progress: 0 });
  /** Set when a launch reported that the Breeze runtime mod could not be put in
   *  place, so the play page stops claiming the mod is ready when it is not. */
  const [breezeModIssue, setBreezeModIssue] = useState(null);
  /** Minecraft, Java, Fabric Loader and Breeze build for the selected version. */
  const [compatibility, setCompatibility] = useState(null);
  // Launch console output. Cleared at the start of each launch so the view shows
  // the current attempt, and retained afterwards so a failure can be read.
  const [launchLogs, setLaunchLogs] = useState([]);

  const [installedMods, setInstalledMods] = useState([]);
  /** Why the last read of this profile's mods failed, if it did. Kept separate
   *  from the list itself so a failed read never passes for an empty profile. */
  const [installedModsError, setInstalledModsError] = useState(null);
  const [modResults, setModResults] = useState([]);
  const [modsView, setModsView] = useState("browse");
  const [query, setQuery] = useState("");
  const [busyModProjectId, setBusyModProjectId] = useState(null);
  const [modsLoading, setModsLoading] = useState(false);
  const [modHasMore, setModHasMore] = useState(false);
  const [modOffset, setModOffset] = useState(0);
  const [modError, setModError] = useState(null);
  const [performanceBusy, setPerformanceBusy] = useState(false);
  const [showTransferDialog, setShowTransferDialog] = useState(false);
  const [exportBusy, setExportBusy] = useState(false);
  /** Files being installed from a drop, so the UI can say what is happening. */
  const [dropBusy, setDropBusy] = useState(null);
  const [showRecommendedMods, setShowRecommendedMods] = useState(false);
  const [showRecordings, setShowRecordings] = useState(false);

  // Java runtime management
  const [javaStatus, setJavaStatus] = useState(null);
  const [javaProgress, setJavaProgress] = useState(null);
  const [javaBusy, setJavaBusy] = useState(false);

  // Client migration / import
  const [importResults, setImportResults] = useState({});
  const [importBusy, setImportBusy] = useState(null);
  const [importProgress, setImportProgress] = useState(null);
  const [detectedClients, setDetectedClients] = useState(null);
  const [detectionBusy, setDetectionBusy] = useState(false);
  const [importEverythingSummary, setImportEverythingSummary] = useState(null);
  const [adBoxes, setAdBoxes] = useState({});
  const mrpackInputRef = useRef(null);

  const [allCapes, setAllCapes] = useState([]);
  const [userCapes, setUserCapes] = useState([]);
  const [allCosmetics, setAllCosmetics] = useState([]);
  const [ownedCosmetics, setOwnedCosmetics] = useState({ owned: [], equipped: {} });
  const [storeSearch, setStoreSearch] = useState("");
  const [storeSlotFilter, setStoreSlotFilter] = useState("all");
  const [selectedStoreItem, setSelectedStoreItem] = useState(null);
  const [promoCode, setPromoCode] = useState(null);
  const [promoDiscount, setPromoDiscount] = useState(0);
  const [checkoutBusy, setCheckoutBusy] = useState(false);

  const [notifications, setNotifications] = useState([]);
  const [showNotificationPanel, setShowNotificationPanel] = useState(false);
  const [showFmMini, setShowFmMini] = useState(false);
  const [notifBusy, setNotifBusy] = useState(false);
  const [friends, setFriends] = useState([]);
  const [friendRequests, setFriendRequests] = useState([]);
  const [socialSearch, setSocialSearch] = useState("");
  const [socialSearchResult, setSocialSearchResult] = useState({ users: [], message: "" });
  const [activeFriend, setActiveFriend] = useState(null);
  const [friendMessages, setFriendMessages] = useState([]);
  const [chatDraft, setChatDraft] = useState("");
  const [chatState, setChatState] = useState({ loading: false, error: "" });
  const [sendingMessage, setSendingMessage] = useState(false);
  // Which social action is in flight, as "add:<id>" or "remove:<id>", so one
  // row can show a spinner without disabling the whole page.
  const [socialBusy, setSocialBusy] = useState("");
  const [socialSearching, setSocialSearching] = useState(false);

  const [giftTarget, setGiftTarget] = useState("");
  const [giftBusy, setGiftBusy] = useState(false);
  const [giftItemId, setGiftItemId] = useState("");
  const [giftType, setGiftType] = useState("cape");
  const [pendingGifts, setPendingGifts] = useState([]);
  const activeGift = pendingGifts[0] || null;
  const [serverDraft, setServerDraft] = useState({ name: "Breeze Local Server", loader: "Paper", version: "1.21.4", ram: 4096, port: 25565 });
  // Server profiles survive launcher restarts; processes don't, so every
  // profile is restored as stopped with a fresh console.
  const [localServers, setLocalServers] = useState(() => {
    try {
      const saved = JSON.parse(window.localStorage.getItem("breeze.localServers") || "[]");
      return Array.isArray(saved)
        ? saved.map((server) => ({ ...server, status: "stopped", liveLogs: [], logs: server.logs?.slice(-40) || [] }))
        : [];
    } catch {
      return [];
    }
  });
  const [hostingTab, setHostingTab] = useState("console");
  const [creatorCapes, setCreatorCapes] = useState([]);
  const [creatorStats, setCreatorStats] = useState(null);
  const [showAdminCapeModal, setShowAdminCapeModal] = useState(false);
  const [showCosmeticUploadModal, setShowCosmeticUploadModal] = useState(false);
  const [showPayEquipModal, setShowPayEquipModal] = useState(false);
  const [capeBusy, setCapeBusy] = useState(false);
  const [featureFlags, setFeatureFlags] = useState({});
  const [userSkinUrl, setUserSkinUrl] = useState(null);
  // Wind Charge wallet, the launcher's only currency surface.
  const [wallet, setWallet] = useState({ wind_charges: 0, transactions: [] });
  const [walletPacks, setWalletPacks] = useState([]);
  const [showWalletModal, setShowWalletModal] = useState(false);
  const [walletBusy, setWalletBusy] = useState(false);

  // Declared before any callback that closes over it. `const` bindings are in
  // the temporal dead zone until their initialiser runs, so referencing notify
  // from an earlier useCallback dependency array threw on the first render and
  // took the whole launcher down.
  const notify = useCallback((type, title) => {
    setToast({ type, title });
    window.clearTimeout(notify._timer);
    notify._timer = window.setTimeout(() => setToast(null), 2600);
  }, []);

  // Custom launcher background. The image lives on disk via the Rust side;
  // localStorage's ~5MB quota silently rejected any full-size photo, which is
  // why backgrounds used to vanish on restart. Only the data URL is held here.
  const [customBg, setCustomBgState] = useState(null);

  useEffect(() => {
    if (!nativeDesktop) return;
    loadCustomBackground().then((url) => { if (url) setCustomBgState(url); }).catch(() => {});
  }, [nativeDesktop]);

  const setCustomBg = useCallback(async (file) => {
    if (!file) {
      setCustomBgState(null);
      await clearCustomBackground().catch(() => {});
      return;
    }
    try {
      // Prefer the browser's own MIME sniff over the filename. A file called
      // "wallpaper" with no extension previously produced ext="wallpaper",
      // which was stored with a nonsense extension and served as image/jpeg.
      const MIME_EXT = {
        "image/png": "png",
        "image/jpeg": "jpg",
        "image/jpg": "jpg",
        "image/webp": "webp",
        "image/gif": "gif",
        "image/avif": "avif",
        "image/bmp": "bmp",
      };
      const fromName = (file.name?.includes(".") ? file.name.split(".").pop() : "") || "";
      const ext = (MIME_EXT[file.type] || fromName || "png").toLowerCase();

      // Streamed in chunks. Sent as one byte array, a large photo becomes
      // roughly three times its size as JSON and the IPC layer drops it, which
      // is why small images worked and big ones silently did nothing.
      const stagedPath = await stageFileForImport(file);
      const url = await saveCustomBackgroundStaged(stagedPath, ext);
      setCustomBgState(url);
      notify("ok", "Background applied");
    } catch (error) {
      notify("!", getError(error));
    }
  }, [notify]);

  const theme = useMemo(() => THEMES.find((entry) => entry.id === settings.theme) || THEMES[0], [settings.theme]);
  const rootStyle = useMemo(() => ({
    "--accent": theme.accent,
    "--accent-dim": theme.accentDim,
    "--accent-ghost": theme.accentGhost,
    "--border-accent": theme.borderAccent,
    "--accent-glow": theme.glow,
    ...(customBg
      ? {
          "--custom-bg": `url(${customBg})`,
          // Every adjustment is a CSS variable so dragging a slider repaints
          // instantly without re-reading or re-encoding the image.
          "--bg-dim": `${(settings.bgDim ?? 35) / 100}`,
          "--bg-blur": `${settings.bgBlur ?? 0}px`,
          "--bg-brightness": `${(settings.bgBrightness ?? 100) / 100}`,
          "--bg-opacity": `${(settings.bgOpacity ?? 100) / 100}`,
          // Zoom is a transform multiplier, not a background-size percentage,
          // so `cover` keeps filling the window at any aspect ratio.
          "--bg-zoom": `${(settings.bgScale ?? 100) / 100}`,
          // Blur bleeds past the layer's edges; scale a little extra to hide it.
          "--bg-blur-bleed": `${(settings.bgBlur ?? 0) * 0.004}`,
          "--bg-position": settings.bgPosition || "center",
        }
      : {}),
  }), [theme, customBg, settings.bgDim, settings.bgBlur, settings.bgBrightness, settings.bgOpacity, settings.bgScale, settings.bgPosition]);
  const rootClass = `root theme-${theme.id}${customBg ? " has-custom-bg" : ""}`;
  /**
   * Versions the picker may offer. Normally the Mojang manifest; when that
   * request failed the picker was left completely empty, so a version already
   * installed on this machine could not be launched while offline. The
   * fallback entries are typed as releases purely so the snapshot filter below
   * cannot hide the only version the user is able to launch.
   */
  const pickableVersions = useMemo(() => {
    if (allVersions.length) return allVersions;
    const seen = new Set();
    return [...rememberedVersions, ...localInstanceIds]
      .filter((id) => {
        if (!id || seen.has(id)) return false;
        seen.add(id);
        return true;
      })
      .map((id) => ({ id, type: "release", releaseTime: "", fabricSupported: true }));
  }, [allVersions, rememberedVersions, localInstanceIds]);
  const visibleVersions = pickableVersions.filter((version) => settings.includeSnapshots || version.type === "release");
  const selectedVersion = pickableVersions.find((version) => version.id === selectedVersionId);
  const activeMods = installedMods.filter((mod) => mod.enabled);
  const unreadNotifications = notifications.filter((item) => !item.read_at).length;
  const userRole = String(profile?.role || session?.role || "user").toLowerCase();
  const isOwner = userRole === "owner";
  const isAdmin = userRole === "admin" || isOwner;
  const canLaunch = Boolean(session && selectedVersionId && launchState.status !== "launching" && launchState.status !== "running");
  const ownedCapeIds = new Set(userCapes.map((entry) => entry.cape_id));
  const equippedCapeId = userCapes.find((entry) => entry.equipped)?.cape_id || profile?.equippedCapeId || null;
  // Full record for the equipped cape (texture + animation frames) so 3D
  // previews render the player exactly as they appear in game. A personal
  // uploaded cape (profile.capeUrl) is used when no store cape is equipped.
  const equippedCapeData =
    userCapes.find((entry) => entry.equipped)?.cape ||
    allCapes.find((cape) => cape.id === equippedCapeId) ||
    (profile?.capeUrl ? { image_url: profile.capeUrl, name: "Personal Cape" } : null);
  const activeAccount = session || accounts[activeAccountIdx];
  const activeIcon = profile?.avatar_url || profile?.avatarUrl || activeAccount?.avatarUrl || (activeAccount?.uuid ? CRAFATAR_AVATAR(activeAccount.uuid, 96) : null);
  // What the player can send from their own inventory. The picture matters:
  // the Gift Center draws item.image_url, and this list used to carry only an
  // id, a type and a name, so every icon and the preview were blank.
  const giftInventory = [
    ...userCapes.map((entry) => {
      const cape = entry.cape || allCapes.find((c) => c.id === entry.cape_id) || {};
      return {
        id: entry.cape_id,
        type: "cape",
        name: cape.name || "Cape",
        image_url: cape.image_url || null,
        rarity: cape.rarity || null,
      };
    }),
    ...(ownedCosmetics?.owned || []).map((entry) => {
      const item = entry.cosmetic || allCosmetics.find((c) => c.id === entry.cosmetic_id) || {};
      return {
        id: entry.cosmetic_id,
        type: "cosmetic",
        name: item.name || "Cosmetic",
        image_url: item.thumbnail_url || null,
        slot: item.slot || null,
        rarity: item.rarity || null,
      };
    }),
  ];

  // What the player can buy for someone else. Gifting an item you do not own is
  // an ordinary purchase with a different destination, so the catalogue is the
  // store's, priced the same way.
  const giftCatalogue = [
    ...allCapes
      .filter((cape) => cape.is_public !== false)
      .map((cape) => ({
        id: cape.id,
        type: "cape",
        name: cape.name || "Cape",
        image_url: cape.image_url || null,
        rarity: cape.rarity || null,
        price_usd: cape.price_usd ?? null,
      })),
    ...allCosmetics
      .filter((item) => item.is_public !== false)
      .map((item) => ({
        id: item.id,
        type: "cosmetic",
        name: item.name || "Cosmetic",
        image_url: item.thumbnail_url || null,
        slot: item.slot || null,
        rarity: item.rarity || null,
        price_usd: item.price_usd ?? null,
      })),
  ];

  // What is waiting in Social: requests addressed to this account, plus
  // unread messages from friends.
  const socialWaiting =
    (friendRequests || []).filter((r) => r?.addressee_uuid === session?.uuid).length +
    (friends || []).reduce((sum, f) => sum + (Number(f.unread) || 0), 0);

  const patchSettings = useCallback((patch) => {
    setSettings((current) => {
      const next = { ...current, ...patch };
      if (nativeDesktop) updateLauncherSettings(next).catch(() => {});
      return next;
    });
  }, [nativeDesktop]);

  const refreshPlatformPanels = useCallback(async (token) => {
    if (!token) return;
    const [notes, social, capes, owned, creatorC, creatorS] = await Promise.allSettled([
      listNotifications(token),
      listFriends(token),
      getUserCapes(token),
      getOwnedCosmetics(token),
      getCreatorCapes(token),
      getCreatorStats(token),
    ]);
    // These requests can outlive an account switch. Committing a response
    // issued with the previous account's token would paint their friends,
    // capes and notifications under the new account.
    if (activeTokenRef.current !== token) return;
    if (notes.status === "fulfilled") setNotifications(notes.value);
    if (social.status === "fulfilled") {
      setFriends(social.value.friends || []);
      setFriendRequests(social.value.requests || []);
    }
    if (capes.status === "fulfilled") setUserCapes(capes.value);
    if (owned.status === "fulfilled") setOwnedCosmetics(owned.value);
    if (creatorC.status === "fulfilled") setCreatorCapes(creatorC.value || []);
    if (creatorS.status === "fulfilled") setCreatorStats(creatorS.value || null);
  }, []);

  // Pull the stored account list out of the Rust credential store, keeping any
  // role we already know for each account so the switcher doesn't flicker.
  const refreshSavedAccounts = useCallback(async (activeUuid) => {
    try {
      const saved = await listSavedAccounts();
      if (!saved.length) return;
      setAccounts((current) => {
        const roles = new Map(current.map((item) => [item.uuid, item.role]));
        return saved.map((item) => ({
          uuid: item.uuid,
          username: item.username,
          role: roles.get(item.uuid),
          canResume: item.canResume,
        }));
      });
      const wanted = activeUuid || saved.find((item) => item.active)?.uuid;
      const index = saved.findIndex((item) => item.uuid === wanted);
      setActiveAccountIdx(index >= 0 ? index : 0);
    } catch { /* switcher is best-effort; never blocks sign-in */ }
  }, []);

  const applyAccount = useCallback(async (account) => {
    try {
      setAuthMessage("Syncing Breeze profile...");
      // Breeze being unreachable must not cost the user their launcher.
      //
      // This was the first await in the function and nothing caught it, so if
      // api.breezeclient.net was down, or the user was on a plane, or DNS was
      // having a bad day, applyAccount threw and every single person was left
      // at the sign-in gate with no way past it. Microsoft had already
      // authenticated them and the Minecraft token in hand is what actually
      // launches the game: none of that needs Breeze.
      //
      // The distinction that matters is who said no. apiRequest puts a numeric
      // status on the error when a server answered, so a Breeze account that is
      // genuinely refused still stops here. Only a request that never reached
      // anyone falls through to playing offline.
      let breezeSession = null;
      try {
        breezeSession = await loginWithMinecraftToken(account.accessToken);
      } catch (error) {
        if (typeof error?.status === "number") throw error;
        const offlineSession = { ...account, breezeToken: null, role: "user", offline: true };
        activeTokenRef.current = null;
        setSession(offlineSession);
        setProfile({ uuid: account.uuid, username: account.username, role: "user" });
        setAccounts((current) => {
          const clean = current.filter((item) => item.uuid !== account.uuid);
          return [{ uuid: account.uuid, username: account.username, role: "user", canResume: true }, ...clean].slice(0, 8);
        });
        setActiveAccountIdx(0);
        if (nativeDesktop) refreshSavedAccounts(account.uuid);
        setAuthMessage("");
        notify("!", "Breeze is offline, so you are playing signed in to Minecraft only. The store, friends and gifts need a connection.");
        return;
      }
      const syncedProfile = await getMe(breezeSession.token).catch(() => breezeSession.user);
      const fullSession = { ...account, breezeToken: breezeSession.token, role: syncedProfile.role };
      // Open the gate for this token before any refresh fires.
      activeTokenRef.current = breezeSession.token;
      setSession(fullSession);
      setProfile(syncedProfile);
      setAccounts((current) => {
        const clean = current.filter((item) => item.uuid !== fullSession.uuid);
        return [{ uuid: fullSession.uuid, username: fullSession.username, role: fullSession.role, canResume: true }, ...clean].slice(0, 8);
      });
      setActiveAccountIdx(0);
      // The Rust side is the source of truth for which accounts can resume
      // without a fresh Microsoft sign-in.
      if (nativeDesktop) refreshSavedAccounts(fullSession.uuid);
      await syncUserData(syncedProfile.uuid, breezeSession.token).then((result) => {
        setProfile(result.profile);
        setSession((current) => current ? { ...current, role: result.profile.role } : current);
      }).catch(() => {});
      await refreshPlatformPanels(breezeSession.token);
      refreshPendingGifts(breezeSession.token);
      // Role-aware update check, creators/owners also see testing channels.
      // The platform is sent so each OS is only offered its own installer.
      getPlatform()
        .catch(() => null)
        .then((os) => checkForUpdate(APP_VERSION, breezeSession.token, os))
        .then((res) => {
          // Set or cleared from this answer alone, so a stale offer from the
          // public manifest never survives signing in.
          setUpdateRelease(res.update ? updateFromCheck(res.update) : null);
        }).catch(() => {});
      notify("ok", `Signed in as ${syncedProfile.username}`);
    } catch (error) {
      setAuthMessage(getError(error));
      notify("!", getError(error));
      // Rethrow so callers (account switching) can tell success from failure
      // instead of leaving the previous account live under the new name.
      throw error;
    }
  }, [notify, refreshPlatformPanels, refreshSavedAccounts]);

  useEffect(() => {
    loadMinecraftVersions().then((items) => {
      setAllVersions(items);
      // Deliberately does NOT choose a version here.
      //
      // This request almost always resolves before getLauncherSettings(), so
      // choosing now meant falling back to the newest release while the saved
      // preference was still in flight. The effect below then wrote that
      // fallback straight back to disk, destroying the real preference: a user
      // on 1.21.11 was silently moved to 26.1.x on every single launch, and the
      // old value was gone before it could be read.
      //
      // The choice is made once both this and the settings load have settled.
      setVersionsLoaded(true);
    }).catch((error) => {
      // Still mark it settled, or a failed version list would leave the picker
      // permanently empty waiting on a list that is never coming.
      setVersionsLoaded(true);
      notify("!", getError(error));
    });

    getVersionManifest().catch(() => getSystemVersion()).then((manifest) => {
      setBreezeVersions(manifest);
      const launcher = manifest.launcher || manifest.latestLauncher || manifest;
      setApiStatus(`Breeze API ${launcher.latestVersion || API_FALLBACK_VERSION} online`);
      // The auto-update decision deliberately does NOT happen here. This effect
      // has an empty dependency array, so anything it reads from `settings` is
      // the first-render default forever, and the real saved settings are loaded
      // further down inside this same effect. Reading settings.autoUpdate here
      // therefore always saw `true` and the toggle did nothing. The decision
      // moved to its own effect below, which re-evaluates once settings arrive.
      setLauncherManifest(launcher);
    }).catch(() => setApiStatus("API offline"));

    // One Breeze mod jar per Minecraft version now lives on the API
    // (breeze-api/versions/mods/<version>.jar) instead of being bundled in
    // the installer. null = "haven't checked yet", so the UI doesn't flash
    // a false "not available" before this resolves.
    listAvailableModVersions().then(setModVersionsAvailable).catch(() => setModVersionsAvailable([]));

    getAllCapes().then(setAllCapes).catch(() => setAllCapes([]));
    listCosmetics().then(setAllCosmetics).catch(() => setAllCosmetics([]));
    if (nativeDesktop) {
      Promise.allSettled([getLauncherSettings(), getSystemMemoryInfo()]).then(([settingsResult, memoryResult]) => {
        if (settingsResult.status === "fulfilled") {
          const saved = settingsResult.value;
          setSettings((current) => ({ ...current, ...saved, theme: String(saved.theme || current.theme).toLowerCase() }));
          // Only recorded here. The version is chosen in the effect below, once
          // the list has loaded too, so neither ordering can lose the value.
          // lastLaunchedVersion is what the user actually played; selectedVersion
          // is only where the picker happened to be left.
          setRememberedVersions([saved.lastLaunchedVersion, saved.selectedVersion].filter(Boolean));
        }
        if (memoryResult.status === "fulfilled") setMemoryInfo(memoryResult.value);
        setSettingsLoaded(true);
      });
      // Show the account switcher immediately, even before the active session
      // finishes refreshing.
      refreshSavedAccounts();
      // Sign the user straight back in. The login gate is held off screen until
      // this settles (see `restoringSession`), because rendering it immediately
      // meant a returning user was shown an account picker and had to choose an
      // account that was about to be restored anyway, which read as being asked
      // to sign in again on every launch.
      restoreSavedSession()
        .then(applyAccount)
        .catch(() => {})
        .finally(() => setRestoringSession(false));
    } else {
      setRestoringSession(false);
    }
  }, []);

  // Discord Rich Presence. Reads the same state the UI renders from, so what a
  // friend sees on your profile cannot drift from what is actually on screen.
  const fm = useBreezeFM();
  useDiscordPresence({
    page: showAccountPanel ? "skin" : page,
    launchStatus: launchState.status,
    music: fm.current
      ? {
          title: fm.current.title,
          artist: fm.current.artist,
          playing: fm.playing,
          progress: fm.progress,
          duration: fm.duration,
        }
      : null,
    enabled: nativeDesktop && Boolean(session),
  });

  /**
   * Offer an update only when there genuinely is one and the user wants it.
   *
   * Split out of the mount effect because that one runs with an empty
   * dependency array: it captured the default settings permanently, so
   * autoUpdate always read as on. Keyed on both inputs, this re-runs whichever
   * of the two lands last, and re-runs again if the user changes the toggle.
   */
  useEffect(() => {
    if (!launcherManifest) return;
    // Signed in, the role-aware /versions/check decides. This effect used to
    // run again whenever settings arrived and clear the test-channel update
    // that check had found, or put the public release back in its place.
    if (session?.breezeToken) return;
    if (!settings.autoUpdate) {
      setUpdateRelease(null);
      return;
    }
    let cancelled = false;
    getPlatform().catch(() => "windows").then((os) => {
      if (cancelled) return;
      // A newer version is not an update until there is an installer for this
      // computer. The manifest's top-level download URL is not one: it can be
      // empty, or a web page, while the release folder has nothing in it.
      const plat = launcherManifest.platforms?.[os];
      const version = plat?.version || launcherManifest.latestVersion;
      if (plat?.available && plat.downloadUrl && isNewerVersion(version, APP_VERSION)) {
        setUpdateRelease({
          latestVersion: version,
          downloadUrl: plat.downloadUrl,
          sha256: plat.sha256 || null,
          fileName: plat.fileName || null,
          channel: "stable",
          changelog: launcherManifest.changelog || null,
          mandatory: Boolean(launcherManifest.mandatory),
          notesUrl: null,
        });
      } else {
        // Already current. Clearing matters: without it a banner raised before
        // an update stayed on screen after installing, which is what made Breeze
        // look like it never finished updating.
        setUpdateRelease(null);
      }
    });
    return () => { cancelled = true; };
  }, [launcherManifest, settings.autoUpdate, session?.breezeToken]);

  /**
   * Choose the Minecraft version, once and only once both inputs exist.
   *
   * Split out of the two loaders because whichever resolved first used to win:
   * the version list normally beat the settings, picked the newest release, and
   * the persistence effect below then overwrote the saved preference with it.
   */
  useEffect(() => {
    if (!versionsLoaded || !settingsLoaded) return;
    if (!pickableVersions.length) return;
    setSelectedVersionId((current) => current || chooseVersionId(pickableVersions, rememberedVersions, settings.includeSnapshots));
  }, [versionsLoaded, settingsLoaded, pickableVersions, rememberedVersions, settings.includeSnapshots]);

  /**
   * Check the whole chain for the selected version. Scrolling through the picker
   * fires this for every version passed, so it waits for the choice to settle
   * and ignores answers for a version that is no longer selected.
   */
  useEffect(() => {
    if (!nativeDesktop || !selectedVersionId) {
      setCompatibility(null);
      return undefined;
    }
    let current = true;
    setCompatibility((previous) => (previous?.versionId === selectedVersionId ? previous : null));
    const timer = setTimeout(() => {
      getVersionCompatibility(selectedVersionId, getApiBaseUrl())
        .then((result) => { if (current) setCompatibility(result); })
        .catch(() => { if (current) setCompatibility(null); });
    }, 400);
    return () => { current = false; clearTimeout(timer); };
  }, [selectedVersionId]);

  /**
   * Keep the picker honest about what it is showing.
   *
   * A selected id missing from the visible list (a snapshot while snapshots are
   * hidden, or a version that has since left the manifest) left the select
   * rendering nothing at all, with no way to tell which of the two had
   * happened.
   */
  useEffect(() => {
    if (!selectedVersionId || !pickableVersions.length) return;
    if (visibleVersions.some((version) => version.id === selectedVersionId)) return;
    // A release is visible whatever the snapshot setting says, so this is the
    // fallback that cannot fight the user's own Snapshots toggle.
    const fallback = newestRelease(pickableVersions);
    if (fallback) {
      notify("!", selectedVersion
        ? `${selectedVersionId} is hidden while snapshots are off, switched to ${fallback.id}`
        : `${selectedVersionId} is no longer listed, switched to ${fallback.id}`);
      setSelectedVersionId(fallback.id);
      return;
    }
    if (selectedVersion && !settings.includeSnapshots) {
      // No release to fall back to, so the only way to show the chosen version
      // at all is to stop hiding snapshots.
      patchSettings({ includeSnapshots: true });
      notify("ok", `Snapshots turned on to show ${selectedVersionId}`);
      return;
    }
    const firstVisible = visibleVersions[0];
    if (!firstVisible) return;
    notify("!", `${selectedVersionId} is no longer listed, switched to ${firstVisible.id}`);
    setSelectedVersionId(firstVisible.id);
  }, [selectedVersionId, selectedVersion, pickableVersions, visibleVersions, settings.includeSnapshots, patchSettings, notify]);

  useEffect(() => {
    if (!selectedVersionId) return;
    resolveFabricLoader(selectedVersionId).then(setLoader).catch(() => setLoader(null));
    if (nativeDesktop) {
      listInstalledMods(selectedVersionId)
        .then((records) => {
          // An empty array is a real answer from the profile, so it is the only
          // thing allowed to empty the list.
          setInstalledMods(records || []);
          setInstalledModsError(null);
        })
        .catch((error) => {
          // A failed read is not evidence that the mods are gone. Blanking the
          // list here is what read as "my mods have reset themselves".
          setInstalledModsError(getError(error));
          notify("!", `Could not read installed mods: ${getError(error)}`);
        });
    }
    patchSettings({ selectedVersion: selectedVersionId });
  }, [nativeDesktop, notify, patchSettings, selectedVersionId]);

  usePolling(
    () => { if (session?.breezeToken) refreshPlatformPanels(session.breezeToken); },
    session?.breezeToken ? 45000 : null,
  );

  // Resolve the player's raw skin texture (with mirror fallbacks) for the 3D
  // player previews on the play page, store, and style page.
  useEffect(() => {
    if (!session?.uuid) { setUserSkinUrl(null); return; }
    let alive = true;
    resolveSkinUrl(session.uuid).then((url) => { if (alive) setUserSkinUrl(url); }).catch(() => {});
    return () => { alive = false; };
  }, [session?.uuid]);

  // An open conversation keeps itself current. Only what arrived since the last
  // message is fetched, so this costs one small request; without it a chat was
  // frozen from the moment it was opened and the other person's replies never
  // appeared at all.
  usePolling(
    async () => {
      if (!session?.breezeToken || !activeFriend?.uuid) return;
      const last = [...friendMessages].reverse().find((m) => m.created_at && !m.pending && !m.failed);
      try {
        const fresh = await listMessages(session.breezeToken, activeFriend.uuid, last?.created_at || null);
        if (!fresh.length) return;
        setFriendMessages((current) => {
          const known = new Set(current.map((m) => m.id));
          return [...current, ...fresh.filter((m) => !known.has(m.id))];
        });
      } catch { /* a dropped poll is not worth a message; the next tick retries */ }
    },
    session?.breezeToken && activeFriend?.uuid ? 5000 : null,
  );

  // Social presence heartbeat (item N): stamps last_seen every 60s while signed in
  // so friends can see this account as online in the Social tab.
  usePolling(
    () => { if (session?.breezeToken) updatePresence(session.breezeToken).catch(() => {}); },
    session?.breezeToken ? 60000 : null,
    { immediate: true },
  );

  // Persist hosting profiles (without volatile console output) across restarts.
  useEffect(() => {
    try {
      const snapshot = localServers.map(({ liveLogs, ...server }) => ({ ...server, logs: (server.logs || []).slice(-40) }));
      window.localStorage.setItem("breeze.localServers", JSON.stringify(snapshot));
    } catch { /* storage unavailable, profiles just won't persist */ }
  }, [localServers]);

  // Global feature toggles: every signed-in user polls so owner-side disables
  // (mods/store/hosting/chat/gifting/radio/rewards) take effect within a minute.
  usePolling(
    () => { if (session?.breezeToken) getFeatureFlags(session.breezeToken).then(setFeatureFlags).catch(() => {}); },
    session?.breezeToken ? 60000 : null,
    { immediate: true },
  );

  const refreshWallet = useCallback((token) => {
    if (!token) return;
    // Same stale-response guard as refreshPlatformPanels: a balance fetched
    // with the old account's token must never land in the new account's UI.
    getWallet(token).then((value) => {
      if (activeTokenRef.current === token) setWallet(value);
    }).catch(() => {});
  }, []);

  // Wallet balance: load on sign-in, refresh on a slow poll so webhook-credited
  // Wind Charge purchases appear without a restart.
  useEffect(() => {
    if (!session?.breezeToken) return;
    getWalletPacks().then((result) => setWalletPacks(result.packs || [])).catch(() => {});
    // An API without ad boxes (or offline) just means no boxes are shown.
    getAdBoxes().then((result) => setAdBoxes(result.boxes || {})).catch(() => setAdBoxes({}));
  }, [session?.breezeToken]);

  usePolling(
    () => refreshWallet(session?.breezeToken),
    session?.breezeToken ? 30000 : null,
    { immediate: true },
  );

  // A Wind Charge payment finishes in the browser, and the charges arrive once
  // PayPal confirms it to the API. For ten minutes after the payment page
  // opens, look every few seconds instead of every 30, and say when they land.
  const [pendingTopUp, setPendingTopUp] = useState(null); // { from, until }
  usePolling(
    () => refreshWallet(session?.breezeToken),
    pendingTopUp && session?.breezeToken ? 4000 : null,
  );
  useEffect(() => { setPendingTopUp(null); }, [session?.breezeToken]);
  useEffect(() => {
    if (!pendingTopUp) return;
    const balance = wallet?.wind_charges ?? 0;
    if (balance > pendingTopUp.from) {
      notify("ok", `${(balance - pendingTopUp.from).toLocaleString()} Wind Charges added`);
      setPendingTopUp(null);
    } else if (Date.now() > pendingTopUp.until) {
      setPendingTopUp(null);
    }
  }, [wallet, pendingTopUp, notify]);

  useEffect(() => {
    if (page !== "mods" || modsView !== "browse" || !selectedVersionId) return;
    setModOffset(0);
    setModError(null);
    const timer = setTimeout(() => {
      setModsLoading(true);
      searchModrinthProjects({ query, gameVersion: selectedVersionId, loader: "fabric", offset: 0, limit: 20 })
        .then((result) => {
          setModResults(result.hits || []);
          setModHasMore((result.hits?.length || 0) < result.totalHits);
          setModOffset(result.hits?.length || 0);
        })
        .catch((err) => {
          setModError("Could not load mods. Check your internet connection.");
          setModResults([]);
        })
        .finally(() => setModsLoading(false));
    }, query ? 300 : 0);
    return () => clearTimeout(timer);
  }, [modsView, page, query, selectedVersionId]);

  // Wire up Tauri launch events so the progress bar reflects actual launch stages
  useEffect(() => {
    if (!nativeDesktop) return;
    let unlistenStage, unlistenComplete, unlistenError, unlistenLog;
    const STAGE_PROGRESS = {
      "java_check": 5, "java_install": 15, "version_manifest": 20,
      "assets": 35, "libraries": 50, "client_jar": 65,
      "breeze_mod": 75, "natives": 82, "prewarm": 90, "launching": 95,
    };
    listenToLaunchStage((event) => {
      const progress = event.progress ?? STAGE_PROGRESS[event.stage] ?? undefined;
      const modFailure = readBreezeModFailure(event.message);
      if (modFailure) setBreezeModIssue(modFailure);
      setLaunchState((cur) => ({
        ...cur,
        status: "launching",
        message: event.message || cur.message,
        ...(progress !== undefined ? { progress } : {}),
      }));
    }).then((fn) => { unlistenStage = fn; }).catch(() => {});
    listenToLaunchComplete((event) => {
      const status = String(event?.status || "");
      if (status === "starting") {
        setLaunchState((cur) => ({ status: "launching", message: event.message || "Starting Minecraft…", progress: Math.max(cur.progress || 0, 95) }));
        return;
      }
      if (status === "running") {
        setLaunchState({ status: "running", message: "Minecraft is running", progress: 100 });
        return;
      }
      if (status === "exited") {
        setLaunchState({ status: "idle", message: "Minecraft closed", progress: 0 });
        return;
      }
      // crashed, startup_failed, and anything else the Rust side has not
      // certified as a clean finish. This used to read "Launch complete", which
      // told the user it had worked while the game was already gone.
      const summary = event?.message || (status === "crashed" ? "Minecraft crashed" : "Minecraft could not start");
      const detail = event?.detail && event.detail !== summary ? event.detail : null;
      const reportPath = event?.reportPath || null;
      setLaunchState({
        status: "error",
        message: [summary, detail, reportPath ? `Crash report: ${reportPath}` : null].filter(Boolean).join(" - "),
        progress: 0,
      });
      setLaunchLogs((cur) => [
        ...cur,
        `[Error] ${summary}`,
        ...(detail ? [`[Error] ${detail}`] : []),
        ...(reportPath ? [`[Error] Crash report: ${reportPath}`] : []),
      ]);
      notify("!", summary);
    }).then((fn) => { unlistenComplete = fn; }).catch(() => {});
    listenToLaunchError((event) => {
      setLaunchState({ status: "error", message: event.message || "Launch failed", progress: 0 });
      // Record the failure in the console too, so the reason survives the toast.
      setLaunchLogs((cur) => [...cur, `[Error] ${event.message || "Launch failed"}`]);
      notify("!", event.message || "Launch failed");
    }).then((fn) => { unlistenError = fn; }).catch(() => {});
    // Launch output. Lines are already redacted in Rust before they are emitted,
    // so nothing sensitive reaches this state. Bounded so a chatty mod or a
    // crash loop cannot grow memory without limit over a long session.
    listenToLaunchLog((event) => {
      const line = event?.message;
      if (typeof line !== "string" || !line.trim()) return;
      const modFailure = readBreezeModFailure(line);
      if (modFailure) setBreezeModIssue(modFailure);
      setLaunchLogs((cur) => (cur.length >= 4000 ? [...cur.slice(-3500), line] : [...cur, line]));
    }).then((fn) => { unlistenLog = fn; }).catch(() => {});
    return () => {
      unlistenStage?.();
      unlistenComplete?.();
      unlistenError?.();
      unlistenLog?.();
    };
  }, [nativeDesktop, notify]);

  // "Keep Launcher Open" off: once Minecraft is running, the launcher
  // minimises. The setting was saved for a long time without anything
  // reading it.
  const lastLaunchStatus = useRef(launchState.status);
  useEffect(() => {
    const was = lastLaunchStatus.current;
    lastLaunchStatus.current = launchState.status;
    if (!nativeDesktop || launchState.status !== "running" || was === "running") return undefined;
    if (settings.afterLaunch !== "minimize") return undefined;
    const timer = setTimeout(() => {
      import("@tauri-apps/api/window").then(({ getCurrentWindow }) => getCurrentWindow().minimize()).catch(() => {});
    }, 1200);
    return () => clearTimeout(timer);
  }, [launchState.status, settings.afterLaunch, nativeDesktop]);

  // One-click update: detect this OS, pick the matching installer from the
  // manifest's per-platform list, download it, and hand off to the native
  // installer. Falls back to the website download page if no build exists yet
  // for this OS or the native updater fails.
  async function handleInstallUpdate() {
    if (!updateRelease) return;
    const os = await getPlatform().catch(() => "windows");
    let release = updateRelease;
    // A test build's link carries its own proof, valid for 30 minutes. Ask
    // again right before downloading: a launcher left open longer than that
    // used to be handed a link the API refused, and the update failed.
    if (session?.breezeToken) {
      try {
        const res = await checkForUpdate(APP_VERSION, session.breezeToken, os);
        if (!res.update) {
          setUpdateRelease(null);
          notify("ok", "You're on the latest version");
          return;
        }
        release = updateFromCheck(res.update);
        setUpdateRelease(release);
      } catch {
        // Offline for a moment: try the offer already in hand. The download
        // reports its own failure if that link no longer works.
      }
    }
    // Two shapes reach this handler:
    //   /versions   -> { platforms: { windows: { available, downloadUrl } } }
    //   /versions/check (stable AND test channels) -> flat { downloadUrl }
    // Only reading the first shape meant every test-channel update fell through
    // to "no build for this OS" and opened the website instead of installing.
    const plat = release.platforms?.[os];
    const url = (plat?.available ? plat.downloadUrl : null) || release.downloadUrl || null;
    const fileName = (plat?.available ? plat.fileName : null) || release.fileName || null;
    const version = plat?.version || release.latestVersion || null;
    // Whichever response shape carried the URL also carries its hash.
    const sha256 = (plat?.available ? plat.sha256 : null) || release.sha256 || null;

    if (!nativeDesktop) {
      // The browser preview cannot install anything.
      openExternalUrl(url || `${WEBSITE_URL}/download.html`).catch(() => {});
      return;
    }
    if (!url) {
      // Never send the user to the website here: it only offers published
      // releases, so for a test build it showed nothing to download.
      setUpdateError(`No ${os} installer for ${version || "this update"} on the server`);
      notify("!", `No ${os} installer for this update on the server yet.`);
      return;
    }
    let unlisten;
    try {
      setUpdateError(null);
      setUpdateState({ stage: "download", message: "Downloading update…" });
      unlisten = await listenToUpdater((event) => setUpdateState(event));
      await downloadAndInstallUpdate({
        url,
        fileName: fileName || null,
        version,
        sha256,
      });
      // On success the app exits; nothing more to do here.
    } catch (error) {
      // Stay inside the launcher. Kicking the user out to a browser on failure
      // is what made a failed update look like "it just opens Chrome and does
      // nothing". Clearing the state re-enables the button so they can retry.
      setUpdateState(null);
      setUpdateError(getError(error));
      notify("!", `Update failed: ${getError(error)}`);
    } finally {
      unlisten?.();
    }
  }

  /**
   * Explicit escape hatch, only when the user asks for it: the installer
   * itself in the browser. A test build needs a fresh link (the one in hand may
   * be past its 30 minutes); a published release can come from the website.
   */
  async function handleOpenDownloadPage() {
    let url = updateRelease?.downloadUrl || null;
    if (session?.breezeToken && updateRelease?.channel && updateRelease.channel !== "stable") {
      const os = await getPlatform().catch(() => "windows");
      const res = await checkForUpdate(APP_VERSION, session.breezeToken, os).catch(() => null);
      if (res?.update?.url) url = res.update.url;
    }
    openExternalUrl(url || `${WEBSITE_URL}/download.html`).catch(() => {});
  }

  async function handleLogin() {
    if (!nativeDesktop) {
      notify("!", "Open the Breeze desktop app to sign in.");
      return;
    }
    try {
      setAuthBusy(true);
      setAuthMessage("Opening Microsoft sign-in");
      const account = await beginMicrosoftAuth();
      await applyAccount(account);
    } catch (error) {
      // applyAccount already surfaced its own failure; only report auth errors.
      if (!session) setAuthMessage(getError(error));
    } finally {
      // Cleared on every path. Leaving it set after a cancelled Microsoft
      // prompt would strand the gate on a spinner with no way back.
      setAuthBusy(false);
    }
  }

  // Everything that belongs to ONE account. Cleared whenever the active
  // account changes, so account A's balance, capes, friends, DMs, gifts and
  // applied promo never show up under account B's name.
  function resetAccountScopedState() {
    // Close the gate first: any request already in flight for the outgoing
    // account is now ignored when it resolves.
    activeTokenRef.current = null;
    setWallet({ wind_charges: 0, creator_passes: 0, is_creator: false, transactions: [] });
    setUserCapes([]);
    setOwnedCosmetics([]);
    setCreatorCapes([]);
    setCreatorStats(null);
    setNotifications([]);
    setFriends([]);
    setFriendRequests([]);
    setFriendMessages([]);
    setActiveFriend(null);
    // Must keep the { users, message } shape. Setting this to null crashed the
    // Social page, which reads result.message during render.
    setSocialSearchResult({ users: [], message: "" });
    setPendingGifts([]);
    setUserSkinUrl(null);
    setPromoCode(null);
    setPromoDiscount(0);
  }

  function resetSignedOutState() {
    setSession(null);
    setProfile(null);
    resetAccountScopedState();
    setAuthMessage("Microsoft account required");
    setLaunchState({ status: "idle", message: "Waiting for launch", progress: 0 });
  }

  // Signing out drops only the active account. If another account is still
  // signed in on this device, we land straight into it instead of the login gate.
  async function handleSignOut() {
    let next = null;
    try { if (nativeDesktop) next = await clearSavedSession(); } catch {}
    resetSignedOutState();
    await refreshSavedAccounts();
    if (next) {
      setAuthMessage(`Switching to ${next.username}...`);
      // If the next stored account can't be resumed, stay on the login gate
      // rather than letting the rejection escape as an unhandled rejection.
      try { await applyAccount(next); } catch { resetSignedOutState(); }
    }
  }

  async function handleSignOutEverywhere() {
    try { if (nativeDesktop) await signOutAllAccounts(); } catch {}
    setAccounts([]);
    setActiveAccountIdx(0);
    setShowAccountPanel(false);
    resetSignedOutState();
  }

  // Instant switch: the Rust store holds each account's Microsoft refresh
  // token, so this mints fresh tokens silently. A new sign-in is only needed
  // when Microsoft has genuinely expired or revoked the session.
  async function handleSwitchAccount(index) {
    const account = accounts[index];
    if (!account || account.uuid === session?.uuid) { setShowAccountPanel(false); return; }
    if (!nativeDesktop) return notify("!", "Open the Breeze desktop app to switch accounts.");
    const previousIdx = activeAccountIdx;
    setActiveAccountIdx(index);
    setShowAccountPanel(false);
    setAuthMessage(`Switching to ${account.username}...`);
    setSwitchingAccount(account.uuid);
    // Drop the outgoing account's data up front so nothing of theirs is on
    // screen while the new session loads.
    resetAccountScopedState();
    try {
      const resumed = await switchAccount(account.uuid);
      await applyAccount(resumed);
    } catch (error) {
      // Expired or revoked, this is the only case that needs a real sign-in.
      setActiveAccountIdx(previousIdx);
      resetSignedOutState();
      setAuthMessage(`${account.username} needs to sign in again`);
      notify("!", `${account.username}: ${getError(error)}`);
      // Pull canResume:false back out of the Rust store so the switcher stops
      // offering this account as instantly resumable.
      refreshSavedAccounts();
    } finally {
      setSwitchingAccount(null);
    }
  }

  async function handleRemoveAccount(index) {
    const account = accounts[index];
    if (!account) return;
    const wasActive = account.uuid === session?.uuid;
    try { if (nativeDesktop) await removeSavedAccount(account.uuid); } catch (error) { return notify("!", getError(error)); }
    setAccounts((current) => current.filter((_, itemIndex) => itemIndex !== index));
    if (wasActive) resetSignedOutState();
    if (index === activeAccountIdx) setActiveAccountIdx(0);
  }

  async function handleLaunch() {
    if (!canLaunch) return;
    setLaunchState({ status: "launching", message: "Checking Java runtime...", progress: 3 });
    // The previous attempt's verdict on the Breeze mod says nothing about this one.
    setBreezeModIssue(null);
    // This instance is on disk from here on, so the picker can still offer it
    // when the version list cannot be fetched.
    setLocalInstanceIds(rememberLocalInstanceId(selectedVersionId));
    // Start a fresh log for this attempt. The previous attempt's log stays
    // visible until the moment a new launch begins, so a failure can be read.
    setLaunchLogs([`[Launch] Starting ${selectedVersionId}`]);
    try {
      // Java is not installed here: the launch picks the runtime the chosen
      // version needs (8, 17, 21 or 25) and downloads it if it is missing.
      setLaunchState({ status: "launching", message: "Preparing instance...", progress: 8 });
      await launchMinecraft({
        versionId: selectedVersionId,
        loaderVersion: loader?.loaderVersion || null,
        maxRamMb: settings.allocatedRamMb,
        account: session,
        // One mod jar per Minecraft version now lives on the API at
        // breeze-api/versions/mods/<version>.jar (not bundled in the Tauri
        // installer, see /versions/mod/list). If nothing is published for
        // this version yet, the Rust side downloads a 404 body, fails the
        // JAR-magic check, logs it, and launches without the mod rather
        // than blocking the whole launch.
        breezeModUrl: selectedVersionId
          ? `${getApiBaseUrl()}/versions/mod/${encodeURIComponent(selectedVersionId)}.jar`
          : null,
        breezeModSha256: null,
        // Needed for the authorized mod download and the game session token.
        breezeToken: session?.breezeToken || activeTokenRef.current || null,
        apiBaseUrl: getApiBaseUrl(),
      });
      // Launch complete is handled by the Tauri event listener above
    } catch (error) {
      // The launch events are the single source of truth for why a launch
      // failed, and they already toast and log it. This rejection is the same
      // failure arriving a second time, which is what produced two toasts, so
      // it only releases the busy state, and only if no event has already moved
      // us out of it.
      setLaunchState((cur) => (cur.status === "launching"
        ? { status: "error", message: getError(error), progress: 0 }
        : cur));
    }
  }

  async function handleInstallMod(mod) {
    if (!nativeDesktop || !selectedVersionId) return;
    const projectId = mod.projectId || mod.id;
    setBusyModProjectId(projectId);
    try {
      await installModrinthMod({
        profileId: selectedVersionId,
        gameVersion: selectedVersionId,
        loader: "fabric",
        projectId,
        projectSlug: mod.slug || mod.id,
        title: mod.title || mod.name,
        summary: mod.description || null,
        iconUrl: mod.iconUrl || null,
      });
      setInstalledMods(await listInstalledMods(selectedVersionId));
      notify("ok", `${mod.title || mod.name} installed`);
    } catch (error) {
      notify("!", getError(error));
    } finally {
      setBusyModProjectId(null);
    }
  }

  /**
   * Move chosen mods from the selected version to another one.
   *
   * Mods from Modrinth are installed again for the target version rather than
   * copied, because the jar is built against a specific Minecraft version and
   * the wrong build throws during startup. Only a locally imported jar, which
   * has no project to fetch from, is copied as it is. Each mod is attempted on
   * its own so one that has no build for the target version does not stop the
   * rest, and the caller is told exactly which ones did not make it.
   */
  async function handleTransferMods(targetVersionId, mods) {
    const moved = [];
    const skipped = [];
    for (const mod of mods) {
      const title = mod.title || mod.projectSlug || mod.projectId;
      const local = [mod.canonicalId, mod.projectId, mod.projectSlug]
        .some((value) => String(value || "").toLowerCase().startsWith("custom:"));
      try {
        if (local) {
          await copyLocalModToProfile({
            fromProfileId: selectedVersionId,
            toProfileId: targetVersionId,
            projectId: mod.projectId || mod.canonicalId,
            gameVersion: targetVersionId,
            loader: mod.loader || "fabric",
          });
          moved.push({ title, how: "Jar copied unchanged" });
        } else {
          await installModrinthMod({
            profileId: targetVersionId,
            gameVersion: targetVersionId,
            loader: "fabric",
            projectId: mod.projectId || mod.canonicalId,
            projectSlug: mod.projectSlug || "",
            title,
            summary: mod.summary || null,
            iconUrl: mod.iconUrl || null,
          });
          moved.push({ title, how: `Installed for ${targetVersionId}` });
        }
      } catch (error) {
        skipped.push({ title, reason: getError(error) });
      }
    }
    // The list on screen belongs to the version being transferred from, which
    // has not changed, but reloading keeps it honest if a copy healed a record.
    setInstalledMods(await listInstalledMods(selectedVersionId).catch(() => installedMods));
    if (moved.length) notify("ok", `${moved.length} mod${moved.length === 1 ? "" : "s"} transferred to ${targetVersionId}`);
    else if (skipped.length) notify("!", "Nothing could be transferred");
    return { moved, skipped };
  }

  async function handleToggleMod(record) {
    try {
      const updated = await setInstalledModEnabled({ profileId: selectedVersionId, projectId: record.projectId, enabled: !record.enabled });
      // A jar the launcher did not install comes back as a bare record; the
      // folder is read again so its row keeps what was read from the jar.
      if (record.managed === false) setInstalledMods(await listInstalledMods(selectedVersionId));
      else setInstalledMods((current) => current.map((item) => isSameMod(item, updated) ? { ...item, ...updated } : item));
    } catch (error) {
      notify("!", getError(error));
    }
  }

  async function handleRemoveMod(record) {
    try {
      await removeInstalledMod({ profileId: selectedVersionId, projectId: record.projectId });
      setInstalledMods(await listInstalledMods(selectedVersionId));
    } catch (error) {
      notify("!", getError(error));
    }
  }

  async function handleInstallJava() {
    if (!nativeDesktop) return;
    setJavaBusy(true);
    setJavaProgress({ message: "Checking Java runtime…" });
    let unlisten;
    try {
      unlisten = await listenToJavaRuntime((event) => setJavaProgress(event));
      const status = await ensureJavaRuntime();
      setJavaStatus(status);
      notify("ok", status.installed ? `Java ${status.major ?? 21} is ready` : "Java not found");
    } catch (err) {
      notify("!", getError(err));
    } finally {
      unlisten?.();
      setJavaBusy(false);
      setJavaProgress(null);
      if (nativeDesktop) getJavaRuntimeStatus().then(setJavaStatus).catch(() => {});
    }
  }

  /**
   * Save this version's mods as a Modrinth pack.
   *
   * Mods that came from Modrinth are listed by their published URL and hashes,
   * so the file stays small; hand-imported jars and the config travel inside
   * it. Breeze itself is never included.
   */
  async function handleExportPack() {
    if (!nativeDesktop || !selectedVersionId) return;
    setExportBusy(true);
    try {
      const summary = await exportMrpack({ profileId: selectedVersionId, includeConfig: true });
      const bundled = summary.bundledMods ? `, ${summary.bundledMods} bundled` : "";
      notify("ok", `Pack saved: ${summary.linkedMods} from Modrinth${bundled}`);
      for (const warning of summary.warnings || []) notify("!", warning);
      await revealPath(summary.path).catch(() => {});
    } catch (err) {
      notify("!", getError(err));
    } finally {
      setExportBusy(false);
    }
  }

  /**
   * Install files dropped onto the window.
   *
   * A .jar goes into the selected version's mods folder, and a .mrpack is
   * imported as a modpack. The path is handed to Rust, which reads the file:
   * a dropped jar is often tens of megabytes, and sending that through IPC as
   * a JSON array of numbers is what made large imports fail.
   */
  const handleDroppedPaths = useCallback(async (paths) => {
    if (!nativeDesktop || !selectedVersionId || !paths?.length) return;
    const jars = paths.filter((p) => /\.jar$/i.test(p));
    const packs = paths.filter((p) => /\.mrpack$/i.test(p));
    const ignored = paths.filter((p) => !/\.(jar|mrpack)$/i.test(p));
    if (ignored.length) {
      notify("!", `Only .jar mods and .mrpack modpacks can be dropped here. Ignored ${ignored.length} other file(s).`);
    }
    if (!jars.length && !packs.length) return;

    setDropBusy({ total: jars.length + packs.length, done: 0 });
    let done = 0;
    for (const source of jars) {
      const fileName = source.split(/[/\\]/).pop() || "mod.jar";
      try {
        await importCustomMod({
          profileId: selectedVersionId,
          gameVersion: selectedVersionId,
          loader: "fabric",
          fileName,
          sourcePath: source,
        });
        notify("ok", `Installed ${fileName}`);
      } catch (err) {
        notify("!", `${fileName}: ${getError(err)}`);
      }
      done += 1;
      setDropBusy({ total: jars.length + packs.length, done });
    }
    for (const source of packs) {
      try {
        const summary = await importMrpack({ path: source, targetProfileId: selectedVersionId });
        notify("ok", `Imported ${summary.packName || "modpack"}`);
      } catch (err) {
        notify("!", getError(err));
      }
      done += 1;
      setDropBusy({ total: jars.length + packs.length, done });
    }
    setDropBusy(null);
    setInstalledMods(await listInstalledMods(selectedVersionId).catch(() => []));
  }, [nativeDesktop, selectedVersionId]);

  // Tauri delivers the OS drop as an event on the webview, with real paths.
  useEffect(() => {
    if (!nativeDesktop) return undefined;
    let unlisten = null;
    let cancelled = false;
    listenToFileDrops(handleDroppedPaths)
      .then((stop) => {
        if (cancelled) stop();
        else unlisten = stop;
      })
      .catch(() => {});
    return () => {
      cancelled = true;
      unlisten?.();
    };
  }, [nativeDesktop, handleDroppedPaths]);

  async function handleDetectClients() {
    if (!nativeDesktop) return;
    setDetectionBusy(true);
    try {
      const report = await detectImportableClients();
      setDetectedClients(report);
    } catch (err) {
      notify("!", getError(err));
    } finally {
      setDetectionBusy(false);
    }
  }

  async function handleImportClient(clientId) {
    if (!nativeDesktop || !selectedVersionId) return;
    setImportBusy(clientId);
    try {
      // Settings, packs and options first, from the launchers that have them;
      // mods never come over as copied jars here: importClientMods finds them,
      // identifies each on Modrinth and installs the build for this version.
      const settingsImports = {
        vanilla: () => importVanillaPreferences({ targetVersion: selectedVersionId, includeMods: false }),
        feather: () => importFeatherPreferences(),
        modrinth: () => importModrinthAppPreferences(),
      };
      const settingsWarnings = [];
      if (settingsImports[clientId]) {
        try { await settingsImports[clientId](); } catch (err) { settingsWarnings.push(getError(err)); }
      }
      const result = await importClientMods({ clientId, targetVersion: selectedVersionId });
      result.warnings = [...settingsWarnings, ...(result.warnings || [])];
      setImportResults((cur) => ({ ...cur, [clientId]: result }));
      setInstalledMods(await listInstalledMods(selectedVersionId));
      const added = (result.installed?.length || 0) + (result.copied?.length || 0);
      notify("ok", result.clientFound ? `${added} mod${added === 1 ? "" : "s"} added for ${selectedVersionId}` : "That launcher was not found on this computer");
    } catch (err) {
      setImportResults((cur) => ({ ...cur, [clientId]: { error: getError(err) } }));
      notify("!", getError(err));
    } finally {
      setImportBusy(null);
    }
  }

  async function handleImportEverything() {
    if (!nativeDesktop || !selectedVersionId) return;
    setImportBusy("__all__");
    try {
      const summary = await importEverything({ profileId: selectedVersionId, gameVersion: selectedVersionId });
      setImportEverythingSummary(summary);
      setInstalledMods(await listInstalledMods(selectedVersionId));
      notify("ok", `Imported ${summary.totalMods || 0} mods from ${summary.succeeded?.join(", ") || "detected clients"}`);
    } catch (err) {
      notify("!", getError(err));
    } finally {
      setImportBusy(null);
    }
  }

  async function handleLoadMoreMods() {
    if (modsLoading || !modHasMore || !selectedVersionId) return;
    setModsLoading(true);
    try {
      const result = await searchModrinthProjects({ query, gameVersion: selectedVersionId, loader: "fabric", offset: modOffset, limit: 20 });
      setModResults((cur) => [...cur, ...(result.hits || [])]);
      const newOffset = modOffset + (result.hits?.length || 0);
      setModOffset(newOffset);
      setModHasMore(newOffset < result.totalHits);
    } catch {
      setModError("Failed to load more mods.");
    } finally {
      setModsLoading(false);
    }
  }

  async function handleApplyPerformanceProfile(profileName) {
    if (!nativeDesktop || !selectedVersionId) return;
    setPerformanceBusy(true);
    try {
      await applyPerformanceProfile({ profileId: selectedVersionId, gameVersion: selectedVersionId, loader: "fabric", profileName });
      setInstalledMods(await listInstalledMods(selectedVersionId));
      notify("ok", `${profileName} profile applied`);
    } catch (error) {
      notify("!", getError(error));
    } finally {
      setPerformanceBusy(false);
    }
  }

  async function handleImportJar(event) {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || !nativeDesktop || !selectedVersionId) return;
    try {
      await importCustomMod({
        profileId: selectedVersionId,
        gameVersion: selectedVersionId,
        loader: "fabric",
        fileName: file.name,
        bytes: Array.from(new Uint8Array(await file.arrayBuffer())),
      });
      setInstalledMods(await listInstalledMods(selectedVersionId));
      notify("ok", `${file.name} added`);
    } catch (error) {
      notify("!", getError(error));
    }
  }

  async function handleUnequipCape() {
    if (!session?.breezeToken) return;
    setCapeBusy(true);
    try {
      await unequipCape(session.breezeToken);
      setUserCapes(await getUserCapes(session.breezeToken));
      setProfile((cur) => cur ? { ...cur, equippedCapeId: null } : cur);
      notify("ok", "Cape unequipped");
    } catch (error) {
      notify("!", getError(error));
    } finally {
      setCapeBusy(false);
    }
  }

  // Items are bought with Wind Charges, never with money directly. If the
  // balance is short, the wallet opens so the user can top up.
  async function handleBuyItem(item, currency = "wind_charges") {
    if (!session?.breezeToken) return notify("!", "Sign in required");
    const isCosmetic = item._type === "cosmetic";
    setCheckoutBusy(true);
    try {
      const result = await purchaseItemWithWc({
        token: session.breezeToken,
        ...(isCosmetic ? { cosmeticId: item.id } : { capeId: item.id }),
        promoCode,
        currency,
      });
      if (result.granted) {
        // Refresh ownership BEFORE touching the modal so the grid and the open
        // panel agree, then keep the modal open showing Equip. Closing it meant
        // the purchase appeared to do nothing until the user reopened the item.
        if (isCosmetic) setOwnedCosmetics(await getOwnedCosmetics(session.breezeToken));
        else setUserCapes(await getUserCapes(session.breezeToken));
        refreshWallet(session.breezeToken);
        setSelectedStoreItem((current) =>
          current && current.id === item.id ? { ...current, owned: true, equipped: false } : current,
        );
        setPromoCode(null);
        setPromoDiscount(0);
        notify("ok", `${item.name} is yours. Equip it below.`);
      }
    } catch (error) {
      const message = getError(error);
      notify("!", message);
      if (/not enough wind charges/i.test(message)) setShowWalletModal(true);
    } finally {
      setCheckoutBusy(false);
    }
  }

  async function handleBuyWcPack(pack) {
    if (!session?.breezeToken) return;
    setWalletBusy(true);
    try {
      const result = await purchaseWindCharges(session.breezeToken, pack.id);
      if (result.granted) {
        refreshWallet(session.breezeToken);
        notify("ok", "Wind Charges added");
      } else if (result.approve_url) {
        setPendingTopUp({ from: wallet?.wind_charges ?? 0, until: Date.now() + 10 * 60 * 1000 });
        await openExternalUrl(result.approve_url);
        notify("ok", "Finish paying in your browser. Your Wind Charges show up here once PayPal confirms it.");
      }
    } catch (error) {
      notify("!", getError(error));
    } finally {
      setWalletBusy(false);
    }
  }

  // Reflect the new equip state on the open modal so the button flips between
  // Equip and Unequip instead of always showing "Equip".
  function syncOpenStoreItemEquip(item, nowEquipped) {
    setSelectedStoreItem((cur) =>
      cur && cur.id === item.id && cur._type === item._type ? { ...cur, equipped: nowEquipped } : cur,
    );
  }

  async function handleEquipCape(item) {
    if (!session?.breezeToken) return;
    try {
      const willEquip = !item.equipped;
      if (item.equipped) await unequipCape(session.breezeToken);
      else await equipCape({ token: session.breezeToken, capeId: item.id });
      setUserCapes(await getUserCapes(session.breezeToken));
      setProfile((cur) => (cur ? { ...cur, equippedCapeId: willEquip ? item.id : null } : cur));
      syncOpenStoreItemEquip(item, willEquip);
      notify("ok", item.equipped ? "Cape unequipped" : `${item.name} equipped`);
    } catch (error) {
      notify("!", getError(error));
    }
  }

  async function handleEquipStoreItem(item) {
    if (item._type === "cape") return handleEquipCape(item);
    if (!session?.breezeToken) return;
    try {
      const willEquip = !item.equipped;
      if (item.equipped) await unequipCosmetic(session.breezeToken, item.slot);
      else await equipCosmetic(session.breezeToken, item.id);
      setOwnedCosmetics(await getOwnedCosmetics(session.breezeToken));
      syncOpenStoreItemEquip(item, willEquip);
      notify("ok", item.equipped ? "Cosmetic unequipped" : `${item.name} equipped`);
    } catch (error) {
      notify("!", getError(error));
    }
  }

  async function handleSocialSearch() {
    if (!session?.breezeToken || !socialSearch.trim()) return;
    try {
      // The endpoint answers with an envelope, { users, message }, not a bare
      // array. Treating the envelope as the array made `users.length` undefined,
      // so every search reported "not registered" no matter who was searched
      // for, including the signed-in user's own name, and the results that did
      // come back were nested one level too deep to render.
      setSocialSearching(true);
      const response = await searchSocialUsers(session.breezeToken, socialSearch.trim());
      const users = Array.isArray(response) ? response : (response?.users ?? []);
      setSocialSearchResult({
        users,
        message: users.length
          ? ""
          : (response?.message || "This user has not registered with Breeze Client yet."),
      });
    } catch (error) {
      setSocialSearchResult({ users: [], message: getError(error), failed: true });
    } finally {
      setSocialSearching(false);
    }
  }

  // A search result carries the player's uuid, so the request is addressed by
  // uuid and never depends on the name being typed or spelled the same way.
  async function handleAddFriend(target) {
    const who = typeof target === "string" ? { username: target } : { username: target?.username, uuid: target?.uuid };
    if (!who.username && !who.uuid) return;
    setSocialBusy(`add:${who.uuid || who.username}`);
    try {
      const result = await sendFriendRequest(session.breezeToken, who);
      // The server answers "now friends" when the other player had already
      // asked, which is a different thing to have happened.
      notify("ok", result?.message || "Friend request sent");
      await refreshPlatformPanels(session.breezeToken);
    } catch (error) {
      notify("!", getError(error));
    } finally {
      setSocialBusy("");
    }
  }

  async function handleRemoveFriend(uuid, { block = false } = {}) {
    if (!session?.breezeToken || !uuid) return;
    setSocialBusy(`remove:${uuid}`);
    try {
      await removeFriend(session.breezeToken, uuid, { block });
      if (activeFriend?.uuid === uuid) setActiveFriend(null);
      notify("ok", block ? "Blocked" : "Removed from friends");
      await refreshPlatformPanels(session.breezeToken);
    } catch (error) {
      notify("!", getError(error));
    } finally {
      setSocialBusy("");
    }
  }

  async function handleAcceptFriend(id) {
    if (!session?.breezeToken) return;
    try {
      await acceptFriendRequest(session.breezeToken, id);
      notify("ok", "Friend request accepted");
      await refreshPlatformPanels(session.breezeToken);
    } catch (error) {
      notify("!", getError(error));
    }
  }

  async function handleDeclineFriend(id) {
    try {
      await declineFriendRequest(session.breezeToken, id);
      notify("ok", "Request declined");
      await refreshPlatformPanels(session.breezeToken);
    } catch (error) {
      notify("!", getError(error));
    }
  }

  // Gift-open flow: fetch unopened gifts once a session is active, then show a
  // celebratory modal for each. Dismissing/equipping marks it seen server-side.
  const refreshPendingGifts = useCallback(async (token) => {
    if (!token) return;
    try { setPendingGifts(await getPendingGifts(token)); } catch { /* non-fatal */ }
  }, []);

  // Notification history management. Each action updates local state first so
  // the list responds instantly, then reconciles with the server.
  async function handleDismissNotification(id) {
    if (!session?.breezeToken) return;
    const previous = notifications;
    setNotifications((cur) => cur.filter((n) => n.id !== id));
    try {
      await dismissNotification(session.breezeToken, id);
    } catch (error) {
      setNotifications(previous);
      notify("!", getError(error));
    }
  }

  async function handleDismissNotifications(input) {
    if (!session?.breezeToken) return;
    const previous = notifications;
    const ids = new Set(input?.ids || []);
    setNotifications((cur) =>
      input?.scope === "all" ? []
        : input?.scope === "read" ? cur.filter((n) => !n.read_at)
        : cur.filter((n) => !ids.has(n.id)),
    );
    setNotifBusy(true);
    try {
      await dismissNotifications(session.breezeToken, input);
      notify("ok", input?.scope === "all" ? "Notifications cleared" : "Notifications dismissed");
    } catch (error) {
      setNotifications(previous);
      notify("!", getError(error));
    } finally {
      setNotifBusy(false);
    }
  }

  async function handleMarkAllNotificationsRead() {
    if (!session?.breezeToken) return;
    const stamp = new Date().toISOString();
    setNotifications((cur) => cur.map((n) => (n.read_at ? n : { ...n, read_at: stamp })));
    setNotifBusy(true);
    try {
      await markAllNotificationsRead(session.breezeToken);
    } catch (error) {
      notify("!", getError(error));
    } finally {
      setNotifBusy(false);
    }
  }

  async function handleCheckForUpdates() {
    try {
      const os = await getPlatform().catch(() => null);
      const res = await checkForUpdate(APP_VERSION, session?.breezeToken, os);
      if (res.update) {
        setUpdateRelease(updateFromCheck(res.update));
        notify("ok", `Update available: ${res.update.version} (${res.update.channel})`);
      } else {
        // An offer the server no longer makes (a test build taken down, or one
        // already installed) goes away with the answer.
        setUpdateRelease(null);
        notify("ok", "You're on the latest version");
      }
    } catch (error) {
      notify("!", getError(error));
    }
  }

  async function dismissActiveGift(equip) {
    const gift = pendingGifts[0];
    if (!gift) return;
    try {
      if (equip) {
        if (gift.cape_id) await equipCape({ token: session.breezeToken, capeId: gift.cape_id });
        else if (gift.cosmetic_id && gift.cosmetic?.slot) await equipCosmetic(session.breezeToken, gift.cosmetic_id);
        setUserCapes(await getUserCapes(session.breezeToken).catch(() => userCapes));
        setOwnedCosmetics(await getOwnedCosmetics(session.breezeToken).catch(() => ownedCosmetics));
        notify("ok", `${gift.cape?.name || gift.cosmetic?.name || "Gift"} equipped`);
      }
      await markGiftSeen(session.breezeToken, gift.id).catch(() => {});
    } finally {
      setPendingGifts((cur) => cur.slice(1));
    }
  }

  async function openFriendChat(friend) {
    // A friendship row carries the other player's uuid even when their profile
    // could not be loaded, so the chat is never opened against the friendship's
    // own id, which used to request an avatar for "undefined".
    const user = friend?.user?.uuid ? friend.user : { uuid: friend?.uuid || friend?.user?.uuid, username: friend?.user?.username };
    if (!user?.uuid) return;
    setActiveFriend(user);
    setChatDraft("");           // a draft belongs to the conversation it was typed in
    setFriendMessages([]);
    setChatState({ loading: true, error: "" });
    if (!session?.breezeToken) return;
    try {
      const messages = await listMessages(session.breezeToken, user.uuid);
      setFriendMessages(messages);
      setChatState({ loading: false, error: "" });
      // Opening the conversation marked it read, so the unread badge should go.
      setFriends((current) => current.map((f) => (f.uuid === user.uuid ? { ...f, unread: 0 } : f)));
    } catch (error) {
      setChatState({ loading: false, error: getError(error) });
    }
  }

  async function handleSendMessage() {
    const body = chatDraft.trim();
    if (!session?.breezeToken || !activeFriend?.uuid || !body || sendingMessage) return;
    setSendingMessage(true);
    // Shown immediately, marked as sending, and replaced by the server's copy.
    // A message that fails stays on screen as failed rather than vanishing.
    const pendingId = `pending-${Date.now()}`;
    const pending = {
      id: pendingId,
      sender_uuid: session.uuid,
      recipient_uuid: activeFriend.uuid,
      body,
      created_at: new Date().toISOString(),
      pending: true,
    };
    setFriendMessages((current) => [...current, pending]);
    setChatDraft("");
    try {
      const saved = await sendMessage(session.breezeToken, activeFriend.uuid, body);
      setFriendMessages((current) => current.map((m) => (m.id === pendingId ? saved : m)));
    } catch (error) {
      setFriendMessages((current) => current.map((m) => (m.id === pendingId ? { ...m, pending: false, failed: true } : m)));
      notify("!", getError(error));
    } finally {
      setSendingMessage(false);
    }
  }

  /**
   * Three ways an item reaches someone else:
   *
   *   "own"   send one you own. It leaves your inventory.
   *   "buy"   buy it for them. You pay, the creator earns, they receive it.
   *   "grant" staff only, free, for moderation and testing.
   */
  async function handleSendGift(mode = "own") {
    const recipient = giftTarget.trim();
    if (!session?.breezeToken || !recipient || !giftItemId || giftBusy) return;
    setGiftBusy(true);
    try {
      const ids = giftType === "cape" ? { capeId: giftItemId } : { cosmeticId: giftItemId };
      if (mode === "buy") {
        const result = await purchaseItemWithWc({
          token: session.breezeToken,
          ...ids,
          giftTo: { username: recipient },
        });
        const who = result?.recipient?.username || recipient;
        notify("ok", result?.spent ? `Bought for ${who} for ${result.spent.toLocaleString()} Wind Charges` : `Bought for ${who}`);
        refreshWallet(session.breezeToken);
      } else if (mode === "grant") {
        await grantGift({ token: session.breezeToken, username: recipient, ...ids });
        notify("ok", "Item granted");
      } else {
        const result = await sendGift({ token: session.breezeToken, username: recipient, ...ids });
        const who = result?.recipient?.username || recipient;
        notify("ok", `Sent to ${who}. It is no longer in your inventory.`);
      }
      setGiftTarget("");
      setGiftItemId("");
      await refreshPlatformPanels(session.breezeToken);
    } catch (error) {
      notify("!", getError(error));
    } finally {
      setGiftBusy(false);
    }
  }

  const capeItems = allCapes.map((cape) => ({
    ...cape,
    _type: "cape",
    slot: "cape",
    owned: ownedCapeIds.has(cape.id),
    equipped: cape.id === equippedCapeId,
    thumbSrc: cape.image_url,
  }));
  const cosmeticItems = allCosmetics.filter((item) => item.slot !== "cape").map((item) => ({
    ...item,
    _type: "cosmetic",
    owned: (ownedCosmetics?.owned || []).some((entry) => entry.cosmetic_id === item.id),
    equipped: ownedCosmetics?.equipped?.[item.slot] === item.id,
    thumbSrc: item.thumbnail_url,
  }));
  // StorePage filters by slot, "owned" and search itself. Filtering here as
  // well by item.slot left the "owned" tab empty: no item has that slot.
  const storeItems = [...capeItems, ...cosmeticItems];

  // Rendered as a fixed overlay on top of the real UI so it can wipe away and
  // reveal the loaded launcher underneath as the Wind Charges fall.
  // The splash hands off only once the first real screen can render: the saved
  // session has been restored (or there is none) and, when signed in, settings
  // and the version list have arrived. Otherwise the hand-off landed on loading
  // placeholders and the launcher appeared to pop in a moment later.
  const launcherReady = !restoringSession && (!session || (settingsLoaded && versionsLoaded));
  const splashOverlay = showSplash ? <SplashScene onDone={() => setShowSplash(false)} ready={launcherReady} /> : null;

  // A returning user goes straight into the launcher. While the saved session
  // is being revived, show only the splash: the login gate must not appear and
  // then vanish, and the account picker must never be presented for an account
  // that is already signing itself in.
  if (!session && restoringSession) {
    return (
      <div className={`${rootClass} login-root`} style={rootStyle}>
        {showSplash
          ? <SplashScene onDone={() => setShowSplash(false)} ready={launcherReady} />
          : <div className="bz-resume"><span className="bz-resume-dot" /><span>Signing you in</span></div>}
      </div>
    );
  }

  if (!session) {
    return (
      <div className={`${rootClass} login-root`} style={rootStyle}>
        {splashOverlay}
        <LoginGate
          onLogin={handleLogin}
          authMessage={authMessage}
          authProgress={authProgress}
          busy={authBusy}
          accounts={accounts}
          switching={switchingAccount}
          onSwitchAccount={handleSwitchAccount}
        />
        {dropBusy && (
        <div className="toast info"><span>Installing dropped files ({dropBusy.done}/{dropBusy.total})…</span></div>
      )}
      {toast && <div className={`toast ${toast.type || "info"}`}><span>{toast.title}</span></div>}
      </div>
    );
  }

  return (
    <div className={rootClass} style={rootStyle}>
      {splashOverlay}
      <input ref={customModInputRef} type="file" accept=".jar" hidden onChange={handleImportJar} />
      <input ref={mrpackInputRef} type="file" accept=".mrpack" hidden onChange={async (e) => {
        const file = e.target.files?.[0];
        e.target.value = "";
        if (!file || !nativeDesktop || !selectedVersionId) return;
        setImportBusy("mrpack");
        try {
          if (!/\.mrpack$/i.test(file.name)) {
            throw new Error("That doesn't look like a .mrpack file. Export one from Modrinth first.");
          }
          if (file.size === 0) {
            throw new Error("The .mrpack file is empty or could not be read.");
          }
          // The file is streamed to a temp path in 4MB chunks and imported by
          // path. Sending it as one byte array over IPC is what broke large
          // packs: the payload was dropped and Rust reported receiving neither
          // a path nor bytes. There is no size cap now, and staging gives us
          // real progress to show instead of a frozen button.
          const staged = await stageFileForImport(file, (fraction) => {
            setImportProgress({ stage: "staging", fraction, name: file.name });
          });
          setImportProgress({ stage: "installing", fraction: 1, name: file.name });
          const result = await importMrpack({ path: staged, targetProfileId: selectedVersionId });
          await clearStagedImport(file.name);
          setImportResults((cur) => ({ ...cur, mrpack: result }));
          setInstalledMods(await listInstalledMods(selectedVersionId));
          const modCount = result?.downloadedMods ?? 0;
          const warnCount = Array.isArray(result?.warnings) ? result.warnings.length : 0;
          notify(
            warnCount ? "!" : "ok",
            `Installed ${result?.packName || "pack"}, ${modCount} mod${modCount === 1 ? "" : "s"}` +
              (warnCount ? ` (${warnCount} file${warnCount === 1 ? "" : "s"} skipped)` : ""),
          );
        } catch (err) {
          // Leave no partial file behind on failure, or a retry would append
          // onto it. stage_import_chunk truncates on the first chunk, but
          // clearing here keeps the temp directory tidy either way.
          await clearStagedImport(file.name).catch(() => {});
          notify("!", getError(err));
        } finally {
          setImportBusy(null);
          setImportProgress(null);
        }
      }} />

      <aside className="sb">
        <div className="logo"><img src="/breeze-logo.png" alt="" className="logo-img" onError={(event) => { event.currentTarget.style.display = "none"; }} /><div className="logo-svg"><I.Logo /></div></div>
        {NAV_ITEMS.map(([id, label, icon]) => {
          // A typo'd icon name would otherwise render <undefined /> and take the
          // whole app down with it, which is a very expensive way to find a typo.
          const Icon = I[icon] || I.Layers;
          // Nothing else tells a player that someone wrote to them, so the tab
          // carries the count of what is waiting behind it.
          const badge = id === "social" ? socialWaiting : 0;
          return (
            <button key={id} className={`ni ${page === id ? "on" : ""}`} onClick={() => setPage(id)}>
              <Icon />
              <span className="ni-lbl">{label}</span>
              {badge > 0 && <span className="ni-badge">{badge > 9 ? "9+" : badge}</span>}
            </button>
          );
        })}
        {(isAdmin || isCreatorTier(userRole)) && <button className={`ni ${page === "admin" ? "on" : ""}`} onClick={() => setPage("admin")}><I.Shield /><span className="ni-lbl">Admin</span></button>}
        {isOwner && <button className={`ni ${page === "promos" ? "on" : ""}`} onClick={() => setPage("promos")}><I.Ticket /><span className="ni-lbl">Promos</span></button>}
        <div className="sb-ft"><button className="av-wrap av-button" onClick={() => setShowAccountPanel(true)} title="Accounts">{activeIcon ? <img src={activeIcon} alt="" className="av-pfp" /> : <div className="av">{session.username?.slice(0, 1) || "B"}</div>}<div className="av-online-dot" /></button></div>
      </aside>

      <main className="mn">
        <header className="tb">
          <div className="tb-l"><div className="tb-brand"><div className="tb-brand-logo"><I.Logo /></div><span className="tb-app">Breeze</span></div><span className="tb-sep">/</span><span className="tb-pg">{PAGE_NAMES[page] || page}</span></div>
          <div className="tb-r">
            {updateRelease && (
              <button
                className="tb-icon-btn"
                style={{ borderColor: updateError ? "var(--danger, #e5484d)" : "var(--border-accent)", color: updateError ? "var(--danger, #e5484d)" : "var(--accent)" }}
                onClick={handleInstallUpdate}
                disabled={Boolean(updateState)}
                title={updateError ? `${updateError}. Click to retry.` : `Download and install ${updateRelease.latestVersion} in the launcher`}
              >
                {updateState ? updateState.message : updateError ? "Retry update" : `Update to ${updateRelease.latestVersion}`}
              </button>
            )}
            <button className="wc-pill" onClick={() => setShowWalletModal(true)} title="Wind Charges, your Breeze balance">
              <span className="wc-ico" />{Number(wallet.wind_charges || 0).toLocaleString()}
            </button>
            {/* Opens the compact player rather than navigating to the full
                page. Playback is owned by BreezeFMProvider at the app root, so
                it continues regardless of which page is open. */}
            <button
              className={`tb-icon-btn ${showFmMini ? "on" : ""}`}
              onClick={() => setShowFmMini((v) => !v)}
              title="Breeze FM"
            >
              <I.Music /><span>Breeze FM</span>
            </button>
            {/* Opening the panel no longer force-marks everything read; the
                user decides via the panel's own controls. */}
            <button className={`tb-icon-btn ${unreadNotifications ? "has-unread" : ""}`} onClick={() => setShowNotificationPanel((current) => !current)}>
              <I.Bell />{unreadNotifications > 0 && <span className="notif-dot">{unreadNotifications > 9 ? "9+" : unreadNotifications}</span>}
            </button>
            <button className="auth-pill" onClick={handleSignOut}><span className="auth-dot" />{session.username || "Signed in"}</button>
          </div>
        </header>

        {showFmMini && (
          <FMMiniPlayer
            onClose={() => setShowFmMini(false)}
            onOpenFull={() => { setShowFmMini(false); setPage("radio"); }}
          />
        )}

        {showNotificationPanel && (
          <NotificationPanel
            notifications={notifications}
            busy={notifBusy}
            onClose={() => setShowNotificationPanel(false)}
            onDismiss={handleDismissNotification}
            onDismissMany={handleDismissNotifications}
            onMarkAllRead={handleMarkAllNotificationsRead}
          />
        )}

        {/* One failing page must never blank the whole launcher. The sidebar
            and header live outside this boundary so navigation always works. */}
        <section className="ct">
          <PageErrorBoundary pageKey={page} pageName={PAGE_NAMES[page] || "This page"}>
          {page === "play" && (
            <div className="pv page-enter">
              <div className="play-shell">
                {/* Player identity, "this is you" */}
                <div className="identity-card">
                  <div className="identity-render">
                    <PlayerViewer3D
                      skinUrl={userSkinUrl}
                      capeUrl={equippedCapeData?.image_url || null}
                      animationFrames={Array.isArray(equippedCapeData?.animation_frames) && equippedCapeData.animation_frames.length > 1 ? equippedCapeData.animation_frames : null}
                      animationFps={equippedCapeData?.animation_fps || null}
                      width={190}
                      height={300}
                      showElytraToggle={false}
                    />
                  </div>
                  <div className="identity-meta">
                    <div className="identity-name">{session.username || "Player"}</div>
                    <div className="identity-sub">
                      <span className="status-dot online" />
                      {userRole !== "user" ? <span className="identity-role">{userRole}</span> : "Minecraft account"}
                    </div>
                    {equippedCapeData?.name && <div className="identity-cape">Cape · {equippedCapeData.name}</div>}
                    <div className="identity-actions">
                      <button className="btn" onClick={() => setShowAccountPanel(true)}>Accounts</button>
                      <button className="btn" onClick={() => setPage("custom")}>Wardrobe</button>
                    </div>
                  </div>
                </div>

                {/* Launch card */}
                <div className="pc">
                  <div className="ph"><div><div className="pe">Ready to Launch</div><div className="pt">Fabric <span>{selectedVersionId || "Loading"}</span></div></div><div className="fabric-badge"><div className="fabric-dot" /><span>{loader?.loaderVersion || "Resolving"}</span></div></div>
                  <div className="vr"><select className="vsel" value={selectedVersionId} onChange={(event) => setSelectedVersionId(event.target.value)}>{visibleVersions.map((version) => <option key={version.id} value={version.id}>{version.id}</option>)}</select><button className="btn" onClick={() => patchSettings({ includeSnapshots: !settings.includeSnapshots })}>Snapshots {settings.includeSnapshots ? "On" : "Off"}</button></div>
                  {breezeModIssue ? (
                    <div className="mod-availability">
                      <span className="status-dot" />
                      Breeze Mod not loaded: {breezeModIssue}
                    </div>
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
                  <div className="prg"><div className="prg-m"><span>{launchState.message}</span><span>{launchState.progress}%</span></div><div className="prg-tr"><div className="prg-b" style={{ width: `${launchState.progress}%` }} /></div></div>
                  <button className={`launch ${launchState.status}`} onClick={handleLaunch} disabled={!canLaunch}>{launchState.status === "launching" ? "Launching…" : launchState.status === "running" ? "Running" : `Launch ${selectedVersionId || "Minecraft"}`}</button>
                  <div className="ss">
                    {[["Mods", installedMods.length, `${activeMods.length} active`], ["RAM", `${Math.round(settings.allocatedRamMb / 1024)} GB`, "allocated"], ["Backend", session.breezeToken ? "Linked" : "Offline", "Breeze API"]].map(([label, value, sub]) => <Stat key={label} label={label} value={value} sub={sub} />)}
                  </div>
                </div>

                {/* Utility column */}
                <div className="play-side">
                  <Panel title="Quick Actions"><Row title="Performance Preset" desc={settings.performanceProfile} action={<button className="btn" onClick={() => setPage("settings")}>{Math.round(settings.allocatedRamMb / 1024)} GB RAM</button>} /><Row title="Recordings" desc="Clips and replay captures" action={<button className="btn" onClick={() => setShowRecordings(true)}>Open</button>} /><Row title="Backend" desc={apiStatus} /></Panel>
                  <AdBox box={adBoxes["launcher-play"]} className="ad-box-side" />
                </div>
              </div>
            </div>
          )}

          {page === "mods" && (featureFlags?.mods_disabled
            ? <div className="sv page-enter" style={{display:"flex",alignItems:"center",justifyContent:"center",flex:1}}><div style={{textAlign:"center"}}><div style={{fontSize:13,fontWeight:700,marginBottom:6}}>Mods Unavailable</div><div style={{fontSize:11,color:"var(--text-faint)"}}>This feature is currently under maintenance.</div></div></div>
            : <ModsPage
                modsView={modsView} setModsView={setModsView}
                query={query} setQuery={setQuery}
                modResults={modResults} installedMods={installedMods}
                busyModProjectId={busyModProjectId}
                onInstall={handleInstallMod} onToggle={handleToggleMod} onRemove={handleRemoveMod}
                onImport={() => customModInputRef.current?.click()}
                onRecommended={() => setShowRecommendedMods(true)}
                selectedVersionId={selectedVersionId} visibleVersions={visibleVersions}
                setSelectedVersionId={setSelectedVersionId}
                modsLoading={modsLoading}
                modHasMore={modHasMore}
                modError={modError}
                installedModsError={installedModsError}
                handleLoadMoreMods={handleLoadMoreMods}
                performanceBusy={performanceBusy}
                handleApplyPerformanceProfile={handleApplyPerformanceProfile}
                setShowTransferDialog={setShowTransferDialog}
                onExportPack={handleExportPack}
                exportBusy={exportBusy}
                nativeDesktop={nativeDesktop}
                notify={notify}
                onModsChanged={async () => {
                  try {
                    setInstalledMods(await listInstalledMods(selectedVersionId));
                    setInstalledModsError(null);
                  } catch (error) {
                    setInstalledModsError(getError(error));
                  }
                }}
              />
          )}
          {/* gameVersion: the version id from the Mojang manifest IS the
              Minecraft version string ("1.21.1"). These objects carry
              { id, type, releaseTime, fabricSupported } and no `minecraft`
              field, so reading one gave undefined, which left gameVersion
              empty and made the Packs page refuse to search at all. */}
          {page === "packs" && <ResourcePacksPage
            profileId={selectedVersionId}
            gameVersion={selectedVersionId || ""}
            notify={notify}
          />}
          {page === "store" && <AdBox box={adBoxes["launcher-store"]} className="ad-box-banner" />}
          {page === "store" && <StorePage items={storeItems} filter={storeSlotFilter} setFilter={setStoreSlotFilter} search={storeSearch} setSearch={setStoreSearch} onOpen={setSelectedStoreItem} featureFlags={featureFlags} userSkinUrl={userSkinUrl} />}
          {page === "social" && (
            <SocialPage
              search={socialSearch}
              setSearch={setSocialSearch}
              onSearch={handleSocialSearch}
              searching={socialSearching}
              result={socialSearchResult}
              onAdd={handleAddFriend}
              addBusy={socialBusy}
              friends={friends}
              requests={friendRequests}
              session={session}
              onAccept={handleAcceptFriend}
              onDecline={handleDeclineFriend}
              onRemove={handleRemoveFriend}
              activeFriend={activeFriend}
              openFriendChat={openFriendChat}
              messages={friendMessages}
              chatState={chatState}
              draft={chatDraft}
              setDraft={setChatDraft}
              sendMessage={handleSendMessage}
              sending={sendingMessage}
              featureFlags={featureFlags}
            />
          )}
          {page === "gifts" && (
            <GiftsPage
              inventory={giftInventory}
              catalogue={giftCatalogue}
              friends={friends}
              target={giftTarget}
              setTarget={setGiftTarget}
              type={giftType}
              setType={setGiftType}
              itemId={giftItemId}
              setItemId={setGiftItemId}
              sendGift={handleSendGift}
              busy={giftBusy}
              isAdmin={isAdmin}
              featureFlags={featureFlags}
              userSkinUrl={userSkinUrl}
              windCharges={wallet?.wind_charges ?? 0}
            />
          )}
          {page === "hosting" && <HostingPage
            draft={serverDraft} setDraft={setServerDraft}
            servers={localServers} setServers={setLocalServers}
            tab={hostingTab} setTab={setHostingTab}
            notify={notify} memoryInfo={memoryInfo} nativeDesktop={nativeDesktop}
            featureFlags={featureFlags}
            prepareLocalServer={prepareLocalServer}
            startLocalServer={startLocalServer}
            stopLocalServer={stopLocalServer}
            importLocalServerFile={importLocalServerFile}
            readLocalServerLog={readLocalServerLog}
            openLocalServerFolder={openLocalServerFolder}
          />}
          {page === "radio" && <AudiusFMPage featureFlags={featureFlags} session={session} notify={notify} isAdmin={isAdmin} />}
          {page === "custom" && <StylePage
            session={session} profile={profile}
            userCapes={userCapes} allCapes={allCapes}
            themes={THEMES} settings={settings} patchSettings={patchSettings}
            userRole={userRole} isAdmin={isAdmin} capeBusy={capeBusy}
            handleUnequipCape={handleUnequipCape}
            setPage={setPage}
            setShowAdminCapeModal={setShowAdminCapeModal}
            setShowCosmeticUploadModal={setShowCosmeticUploadModal}
            setShowPayEquipModal={setShowPayEquipModal}
            userSkinUrl={userSkinUrl} equippedCapeData={equippedCapeData}
            customBg={customBg} setCustomBg={setCustomBg}
            notify={notify}
            ownedCosmetics={ownedCosmetics}
            onSavePlacement={async (cosmeticId, transform) => {
              if (!session?.breezeToken) throw new Error("Sign in to save a placement.");
              await setCosmeticPlacement(session.breezeToken, cosmeticId, transform);
              setOwnedCosmetics(await getOwnedCosmetics(session.breezeToken));
            }}
          />}
          {page === "console" && (
            <ConsolePage logs={launchLogs} launchState={launchState} versionId={selectedVersionId} />
          )}
          {page === "settings" && <SettingsPage
            themes={THEMES} settings={settings} patchSettings={patchSettings}
            memoryInfo={memoryInfo} apiStatus={apiStatus} setApiStatus={setApiStatus}
            getSystemVersion={getSystemVersion}
            APP_VERSION={APP_VERSION} updateRelease={updateRelease}
            onCheckUpdates={handleCheckForUpdates}
            onInstallUpdate={handleInstallUpdate}
            onOpenDownloadPage={handleOpenDownloadPage}
            updateError={updateError}
            performanceBusy={performanceBusy}
            handleApplyPerformanceProfile={handleApplyPerformanceProfile}
            javaStatus={javaStatus} javaProgress={javaProgress} javaBusy={javaBusy}
            handleInstallJava={handleInstallJava}
            importResults={importResults} importBusy={importBusy} importProgress={importProgress}
            detection={detectedClients} detectionBusy={detectionBusy}
            importEverythingSummary={importEverythingSummary}
            handleImportClient={nativeDesktop ? handleImportClient : undefined}
            handleDetectClients={nativeDesktop ? handleDetectClients : undefined}
            handleImportEverything={nativeDesktop ? handleImportEverything : undefined}
            mrpackInputRef={nativeDesktop ? mrpackInputRef : undefined}
            notify={notify}
          />}
          {page === "admin" && <OwnerAdminPage
            profile={profile} session={session} userRole={userRole} isOwner={isOwner}
            creatorStats={creatorStats} creatorCapes={creatorCapes}
            setPage={setPage}
            setShowAdminCapeModal={setShowAdminCapeModal}
            setShowCosmeticUploadModal={setShowCosmeticUploadModal}
            token={session?.breezeToken}
            notify={notify}
            featureFlags={featureFlags}
            setFeatureFlags={setFeatureFlags}
          />}
          {page === "promos" && isOwner && <PromoPage I={I} token={session.breezeToken} notify={notify} />}
          </PageErrorBoundary>
        </section>
      </main>

      {selectedStoreItem && <StoreModal item={selectedStoreItem} wallet={wallet} promoCode={promoCode} promoDiscount={promoDiscount} setPromoCode={setPromoCode} setPromoDiscount={setPromoDiscount} onClose={() => setSelectedStoreItem(null)} onBuy={handleBuyItem} onEquip={handleEquipStoreItem} busy={checkoutBusy} token={session.breezeToken} userSkinUrl={userSkinUrl} username={session.username} />}
      {showAccountPanel && <AccountPanel accounts={accounts.length ? accounts : [session]} activeIdx={activeAccountIdx} onSwitch={handleSwitchAccount} onAdd={handleLogin} onRemove={handleRemoveAccount} onClose={() => setShowAccountPanel(false)} switching={switchingAccount} onSignOutAll={handleSignOutEverywhere} />}
      <RecommendedModsPanel open={showRecommendedMods} onClose={() => setShowRecommendedMods(false)} versionId={selectedVersionId} loaderVersion={loader?.loaderVersion} notify={(type, title) => notify(type === "!" ? "!" : "ok", title)} />
      <RecordingsPanel open={showRecordings} onClose={() => setShowRecordings(false)} versionId={selectedVersionId} />
      {showTransferDialog && (
        <TransferModsDialog
          fromVersionId={selectedVersionId}
          versions={visibleVersions}
          installedMods={installedMods}
          onTransfer={handleTransferMods}
          onClose={() => setShowTransferDialog(false)}
        />
      )}
      {showAdminCapeModal && (
        <CapeUploadModal
          session={session}
          creatorSharePercent={profile?.creator_share_percent}
          onClose={() => setShowAdminCapeModal(false)}
          onUploaded={() => {
            getAllCapes().then(setAllCapes).catch(() => {});
            refreshPlatformPanels(session.breezeToken);
            notify("ok", "Cape listed on the marketplace");
          }}
        />
      )}
      {showCosmeticUploadModal && (
        <CosmeticUploadModal
          session={session}
          onClose={() => setShowCosmeticUploadModal(false)}
          onUploaded={() => {
            listCosmetics().then(setAllCosmetics).catch(() => {});
            refreshPlatformPanels(session.breezeToken);
            notify("ok", "Cosmetic uploaded");
          }}
        />
      )}
      {showPayEquipModal && (
        <PayEquipCapeModal
          session={session}
          walletBalance={wallet.wind_charges}
          onOpenWallet={() => setShowWalletModal(true)}
          onClose={() => setShowPayEquipModal(false)}
          onNotify={(type, message) => notify(type === "!" ? "!" : "ok", message)}
          onApplied={(capeUrl) => {
            setProfile((cur) => (cur ? { ...cur, capeUrl } : cur));
            refreshWallet(session.breezeToken);
            refreshPlatformPanels(session.breezeToken);
          }}
        />
      )}
      {showWalletModal && (
        <WalletModal
          wallet={wallet}
          packs={walletPacks}
          busy={walletBusy}
          onBuy={handleBuyWcPack}
          onClose={() => setShowWalletModal(false)}
        />
      )}
      {activeGift && (
        <GiftOpenModal
          gift={activeGift}
          userSkinUrl={userSkinUrl}
          remaining={pendingGifts.length}
          onEquip={() => dismissActiveGift(true)}
          onClose={() => dismissActiveGift(false)}
        />
      )}
      {toast && <div className={`toast ${toast.type || "info"}`}><span>{toast.title}</span></div>}
    </div>
  );
}

/** Full-screen "you've been gifted" celebration shown when a recipient opens
 *  Breeze with an unopened gift. Wind Charge burst + the item on their model. */
const GIFT_BURST = Array.from({ length: 22 }, (_, i) => {
  const angle = (i / 22) * Math.PI * 2 + (i % 2 ? 0.2 : -0.2);
  const dist = 150 + (i % 5) * 40;
  return { tx: Math.round(Math.cos(angle) * dist), ty: Math.round(Math.sin(angle) * dist), size: 8 + (i % 4) * 5, delay: 0.15 + (i % 6) * 0.05 };
});

function GiftOpenModal({ gift, userSkinUrl, remaining, onEquip, onClose }) {
  const [modelFailed, setModelFailed] = useState(false);
  const isCape = Boolean(gift.cape_id);
  const cape = gift.cape;
  const cosmetic = gift.cosmetic;
  const name = cape?.name || cosmetic?.name || "a Breeze gift";
  const isAnimated = isCape && Array.isArray(cape?.animation_frames) && cape.animation_frames.length > 1;
  return (
    <div className="modal-backdrop gift-open-backdrop">
      <div className="gift-open" onClick={(e) => e.stopPropagation()}>
        <div className="gift-burst" aria-hidden="true">
          {GIFT_BURST.map((p, i) => (
            <span key={i} className="pass-ico" style={{ "--tx": `${p.tx}px`, "--ty": `${p.ty}px`, width: p.size, height: p.size, animationDelay: `${p.delay}s` }} />
          ))}
        </div>
        <div className="gift-open-head">🎁 You've been gifted!</div>
        <div className="gift-open-sub">{gift.sender_username} sent you <strong>{name}</strong></div>
        <div className="gift-open-preview">
          {isCape
            ? <PlayerViewer3D skinUrl={userSkinUrl} capeUrl={cape?.image_url || null} animationFrames={isAnimated ? cape.animation_frames : null} animationFps={cape?.animation_fps || null} width={220} height={360} />
            : cosmetic?.model_url && !modelFailed
              ? <PlayerViewer3D skinUrl={userSkinUrl} cosmetics={[{ key: String(cosmetic.id), url: cosmetic.model_url, cosmetic }]} onCosmeticError={() => setModelFailed(true)} showElytraToggle={false} width={220} height={360} />
              : <StoreAssetImage src={cosmetic?.thumbnail_url} alt={name} slot={cosmetic?.slot} />}
        </div>
        <div className="gift-open-actions">
          <button className="modal-close" onClick={onClose}>Maybe later</button>
          <button className="modal-buy" onClick={onEquip}>Equip now</button>
        </div>
        {remaining > 1 && <div className="gift-open-more">+{remaining - 1} more gift{remaining - 1 === 1 ? "" : "s"} waiting</div>}
      </div>
    </div>
  );
}



/** Wind Charge wallet: balance, top-up packs, recent activity. Users only ever
 *  buy Wind Charges, items are then bought with charges inside the launcher. */
// Ascending bundle tiers, colour is presentation only; all packs award
// ordinary blue Wind Charges (see WalletModal note).
const WC_PACK_TIERS = ["blue", "purple", "pink", "red"];

function WalletModal({ wallet, packs, busy, onBuy, onClose }) {
  const balance = Number(wallet.wind_charges || 0);
  const isCreator = Boolean(wallet.is_creator);
  const passes = Number(wallet.creator_passes || 0);
  return (
    <div className="modal-backdrop" onClick={busy ? undefined : onClose}>
      <div className="modal wallet-modal" onClick={(event) => event.stopPropagation()}>
        <div className="modal-body">
          <div className="modal-header">
            <div>
              <div className="modal-title">Wind Charges</div>
              <div className="modal-desc">Spend Wind Charges on capes and cosmetics in the store.</div>
            </div>
            <button className="mini-btn" onClick={onClose}><I.X /></button>
          </div>

          <div className="wallet-balance-hero">
            <span className="wc-ico" />
            <div>
              <div className="wallet-balance-num">{balance.toLocaleString()}</div>
              <div className="wallet-balance-sub">Wind Charges available</div>
            </div>
          </div>

          {isCreator && (
            <div className="wallet-balance-hero pass-hero">
              <span className="pass-ico" />
              <div>
                <div className="wallet-balance-num pass-num">{passes.toLocaleString()}</div>
                <div className="wallet-balance-sub">Creator Passes: test-only, never earns creators money</div>
              </div>
            </div>
          )}

          <div className="wc-pack-grid">
            {packs.map((pack, index) => {
              // Bundle tier is purely visual, the colored Wind Charge just
              // signals a bigger bundle. Every pack still credits ordinary
              // (blue) Wind Charges. Tier rises with bundle size.
              const tier = WC_PACK_TIERS[index % WC_PACK_TIERS.length];
              return (
                <button key={pack.id} className={`wc-pack tier-${tier} ${pack.popular ? "popular" : ""}`} onClick={() => onBuy(pack)} disabled={busy}>
                  {pack.popular && <span className="wc-pack-badge">Most popular</span>}
                  <span className="wc-pack-amount"><span className="wc-ico" />{(pack.wc + pack.bonus).toLocaleString()}</span>
                  {pack.bonus > 0 && <span className="wc-pack-bonus">includes +{pack.bonus} bonus</span>}
                  <span className="wc-pack-price">${pack.usd}</span>
                </button>
              );
            })}
            {!packs.length && <div className="empty-panel" style={{ gridColumn: "1/-1" }}>Wind Charge packs are loading…</div>}
          </div>

          {wallet.transactions?.length > 0 && (
            <>
              <div className="sec-label" style={{ marginBottom: 0 }}>Recent activity</div>
              <div className="wallet-txn-list">
                {wallet.transactions.slice(0, 10).map((txn, index) => (
                  <div key={index} className="wallet-txn">
                    <span>{txn.note || txn.type}</span>
                    <span className={`wallet-txn-amt ${txn.amount_wc >= 0 ? "pos" : "neg"}`}>
                      {txn.amount_wc >= 0 ? "+" : ""}{Number(txn.amount_wc).toLocaleString()}
                    </span>
                  </div>
                ))}
              </div>
            </>
          )}

          <div style={{ fontSize: 11, color: "var(--text-faint)", lineHeight: 1.5 }}>
            Payments are processed by PayPal. Charges are credited automatically once the
            payment settles, usually within a few seconds.
          </div>
        </div>
      </div>
    </div>
  );
}



function AccountPanel({ accounts, activeIdx, onSwitch, onAdd, onRemove, onClose, switching, onSignOutAll }) {
  const list = accounts.filter(Boolean);
  return (
    <div className="account-panel-backdrop" onClick={onClose}>
      <div className="account-panel" onClick={(event) => event.stopPropagation()}>
        <div className="account-panel-header">
          <span className="account-panel-title">Accounts</span>
          <button className="icon-btn" onClick={onClose}><I.X /></button>
        </div>
        <div className="account-list">
          {list.map((account, index) => {
            const isActive = index === activeIdx;
            const isSwitching = switching && switching === account.uuid;
            // canResume is undefined for the in-memory session before the Rust
            // store has answered; treat that as resumable rather than scaring
            // the user with a "sign in again" hint that isn't true.
            const canResume = account.canResume !== false;
            return (
              <div
                key={account.uuid || index}
                className={`account-item ${isActive ? "active" : ""} ${isSwitching ? "busy" : ""}`}
                onClick={() => { if (!isSwitching) onSwitch(index); }}
              >
                <div className="account-avatar-wrap">
                  {account.uuid ? <img src={CRAFATAR_AVATAR(account.uuid)} alt="" className="account-pfp" onError={(event) => { event.currentTarget.style.display = "none"; }} /> : <div className="account-pfp-fallback">{(account.username || "B").slice(0, 1).toUpperCase()}</div>}
                  {isActive && <div className="account-active-dot" />}
                </div>
                <div className="account-info">
                  <div className="account-name">{account.username || "Player"}</div>
                  <div className="account-sub">
                    {isSwitching ? "Switching..." : isActive ? "Active" : canResume ? "Click to switch" : "Session expired, sign in again"}
                  </div>
                </div>
                {isSwitching
                  ? <I.Spin />
                  : <button className="icon-btn danger" onClick={(event) => { event.stopPropagation(); onRemove(index); }} title={isActive ? "Sign out of this account" : "Remove account"}><I.X /></button>}
              </div>
            );
          })}
        </div>
        <button className="add-account-btn" onClick={onAdd}><I.Plus /> Add Microsoft Account</button>
        {list.length > 1 && (
          <button className="account-signout-all" onClick={onSignOutAll}>Sign out of all accounts</button>
        )}
      </div>
    </div>
  );
}



function StoreModal({ item, wallet, onClose, onBuy, onEquip, busy, token, promoCode, promoDiscount, setPromoCode, setPromoDiscount, userSkinUrl, username }) {
  const price = Number(item.price_usd || 0);
  const priceWc = priceInWc(price);
  const isCape = item._type === "cape";
  // A 3D cosmetic is shown worn, at its attachment point, like a cape is.
  const [modelFailed, setModelFailed] = useState(false);
  const isWorn = item._type === "cosmetic" && Boolean(item.model_url) && !modelFailed;
  const onPlayer = isCape || isWorn;
  const isAnimated = Array.isArray(item.animation_frames) && item.animation_frames.length > 1;
  const isCreator = Boolean(wallet?.is_creator);
  const passes = Number(wallet?.creator_passes || 0);
  const [currency, setCurrency] = useState("wind_charges");
  const usingPasses = currency === "creator_passes";

  return (
    <div className="modal-backdrop" onClick={onClose}>
      <div className={`modal ${onPlayer ? "store-modal-3d" : ""}`} onClick={(event) => event.stopPropagation()}>
        {onPlayer && (
          <div className="store-modal-preview-panel">
            <div className="store-modal-preview-bg" />
            <div className={`item-rarity-stripe ${item.rarity || "premium"}`} style={{ position: "absolute", top: 0, left: 0, right: 0, zIndex: 2 }} />
            <div className="store-modal-cape-label">
              <span className={`modal-rarity ${item.rarity || "premium"}`}>{item.rarity || "premium"}</span>
              {isAnimated && <span className="cape-anim-tag">Animated</span>}
              {item.owned && <span className="badge perf">Owned</span>}
            </div>
            <PlayerViewer3D
              skinUrl={userSkinUrl}
              capeUrl={isCape ? item.image_url || null : null}
              animationFrames={isCape && isAnimated ? item.animation_frames : null}
              animationFps={isCape ? item.animation_fps || null : null}
              cosmetics={isWorn ? [{ key: String(item.id), url: item.model_url, cosmetic: item }] : null}
              onCosmeticError={() => setModelFailed(true)}
              showElytraToggle={isCape}
              width={300}
              height={470}
            />
            <div className="store-modal-hint">Previewed on {username || "your"} skin · drag to rotate</div>
          </div>
        )}
        <div className="modal-body store-modal-info-panel">
          <div className="modal-header">
            <div>
              <div className="modal-title">{item.name}</div>
              <div className="modal-desc">{item.description || (isCape ? "Rendered live on your player." : "Breeze cosmetic.")}</div>
            </div>
            <button className="mini-btn" onClick={onClose}><I.X /></button>
          </div>
          {!onPlayer && (
            <div style={{ minHeight: 180 }}>
              <StoreAssetImage src={item.thumbSrc} alt={item.name} slot={item.slot} />
            </div>
          )}
          {!item.owned && price > 0 && <PromoCodeInput token={token} priceUsd={price} appliedCode={promoCode} appliedDiscount={promoDiscount} onApply={(code, discount) => { setPromoCode(code); setPromoDiscount(discount); }} onRemove={() => { setPromoCode(null); setPromoDiscount(0); }} disabled={busy} />}
          {!item.owned && priceWc > 0 && isCreator && (
            <div className="pay-with">
              <div className="pay-with-label">Pay with</div>
              <div className="pay-with-toggle">
                <button className={`pay-opt ${!usingPasses ? "on" : ""}`} onClick={() => setCurrency("wind_charges")} disabled={busy}>
                  <span className="wc-ico" /> Wind Charges
                </button>
                <button className={`pay-opt gold ${usingPasses ? "on" : ""}`} onClick={() => setCurrency("creator_passes")} disabled={busy}>
                  <span className="pass-ico" /> Creator Passes
                </button>
              </div>
              <div className="pay-with-hint">
                {usingPasses
                  ? `Test purchase, ${passes.toLocaleString()} passes available. No money goes to the creator.`
                  : "Real Wind Charges, the creator earns their commission."}
              </div>
            </div>
          )}
          <div className="modal-actions">
            <div className="modal-price">
              {priceWc <= 0
                ? "Free"
                : <><span className={usingPasses ? "pass-ico" : "wc-ico"} />{priceWc.toLocaleString()}</>}
            </div>
            <button className="modal-close" onClick={onClose}>Close</button>
            {item.owned
              ? <button className="modal-buy" onClick={() => onEquip(item)} disabled={busy}>{item.equipped ? "Unequip" : "Equip"}</button>
              : <button className="modal-buy" onClick={() => onBuy(item, currency)} disabled={busy}>{busy ? <I.Spin /> : priceWc <= 0 ? "Claim" : usingPasses ? "Test buy" : "Buy"}</button>}
          </div>
        </div>
      </div>
    </div>
  );
}







export default App;
