import { convertFileSrc, invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { openUrl, openPath, revealItemInDir } from "@tauri-apps/plugin-opener";

export type NativeAccount = {
  uuid: string;
  username: string;
  accessToken: string;
  refreshToken?: string | null;
  xuid?: string | null;
  
  breezeToken?: string | null;
};

export type LauncherSettings = {
  allocatedRamMb: number;
  theme: string;
  includeSnapshots: boolean;
  performanceProfile: string;
  prewarmEnabled: boolean;
  /** What the picker last showed. A UI preference, not a launch record. */
  selectedVersion?: string | null;
  /**
   * The version that actually launched, written only after the game process
   * started. This is what the launcher should default to, so a version that
   * failed preflight never becomes the remembered choice.
   */
  lastLaunchedVersion?: string | null;
  lastLaunchedAt?: string | null;
  memorySized?: boolean;
  modAutoUpdate: boolean;
  graphicsPerformance: boolean;
  browserAccel: string;
  afterLaunch: string;
  minecraftPath: string;
  resolutionWidth?: number | null;
  resolutionHeight?: number | null;
  importedFromFeather: boolean;
  /** Custom background adjustments. All percentages except bgPosition. */
  bgDim?: number;
  bgBlur?: number;
  bgBrightness?: number;
  bgOpacity?: number;
  bgScale?: number;
  bgPosition?: string;
};

export type FeatherImportSummary = {
  importedVersions: string[];
  importedCustomMods: number;
  importedModrinthMods: number;
  copiedConfigDirectories: number;
  selectedVersion?: string | null;
  allocatedRamMb: number;
  warnings: string[];
};

export type PerformanceProfileResult = {
  profileName: string;
  recommendedRamMb: number;
  installedCount: number;
  installedTitles: string[];
};

export type PrewarmResult = {
  versionId: string;
  loaderVersion?: string | null;
  assetIndexName: string;
  libraryCount: number;
  cachedModCount: number;
  gameDirectory: string;
  status: string;
};

export type SystemMemoryInfo = {
  totalRamMb: number;
  safeMaxRamMb: number;
  recommendedRamMb: number;
};

export type ManagedModRecord = {
  /**
   * The Modrinth project id, which is the only stable identity a mod has.
   *
   * `projectId` used to carry whichever id the caller happened to hold: the
   * Browse page sent the hex id, the recommended panel sent the slug, and the
   * performance profile hardcoded hex ids. The manifest de-duplicated on that
   * field, so the same jar was recorded twice and toggles, removals and
   * "installed?" checks all disagreed with each other. Match on this first.
   */
  canonicalId: string;
  projectId: string;
  projectSlug: string;
  title: string;
  summary?: string | null;
  iconUrl?: string | null;
  gameVersion: string;
  loader: string;
  installedVersionId: string;
  installedVersionName: string;
  fileName: string;
  enabled: boolean;
  /** False for a jar in the folder the launcher did not install (its key is `file:<jar>`). */
  managed?: boolean;
  /** The launcher's record names a jar that is no longer in the folder. */
  fileMissing?: boolean;
  modId?: string | null;
  duplicate?: boolean;
};

/** One problem Fabric would report for a mod, read from the jars themselves. */
export type ModIssue =
  | { kind: "missingDependency"; dependency: string; wants: string[] }
  | { kind: "wrongVersion"; dependency: string; wants: string[]; found: string }
  | { kind: "breaks"; other: string; version: string }
  | { kind: "conflicts"; other: string; version: string }
  | { kind: "duplicate"; files: string[] };

export type InstanceModEntry = {
  key: string;
  fileName: string;
  jarName: string;
  enabled: boolean;
  size: number;
  sha1: string;
  sha512: string;
  modId: string | null;
  name: string;
  version: string;
  description: string;
  managed: boolean;
  projectId: string | null;
  iconUrl: string | null;
  installedVersionId: string | null;
  breeze: boolean;
  problem: string | null;
  issues: ModIssue[];
  duplicate: boolean;
  loadedLastRun: boolean | null;
};

export type InstanceModsReport = {
  instanceDir: string;
  modsDir: string;
  minecraft: string;
  loader: string;
  java: number | null;
  mods: InstanceModEntry[];
  duplicates: { modId: string; name: string; files: { fileName: string; version: string }[] }[];
  missingFiles: string[];
  lastRun: unknown;
  lastLaunchProblem: string | null;
};

export type ModVersionChoice = {
  id: string;
  versionNumber: string;
  name: string;
  versionType: string;
  datePublished: string;
  featured: boolean;
  gameVersions: string[];
  fileName: string;
  size: number;
  installed: boolean;
};

export type ModVersions = {
  key: string;
  modId: string;
  name: string;
  installedVersion: string;
  fileName: string;
  projectId: string | null;
  identifiedBy: "record" | "hash" | null;
  minecraft: string;
  loader: string;
  versions: ModVersionChoice[];
  note: string | null;
};

export type OtherModIssue = { modId: string; name: string; fileName: string; issue: ModIssue };

export type StagedModChange = {
  token: string;
  modId: string;
  name: string;
  from: { fileName: string; version: string; enabled: boolean }[];
  toVersion: string;
  toFile: string;
  size: number;
  enabled: boolean;
  impact: { own: ModIssue[]; breaks: OtherModIssue[]; fixes: OtherModIssue[] };
  blocking: boolean;
};

export type AppliedModChange = {
  fileName: string;
  enabled: boolean;
  replaced: { fileName: string; backupPath: string }[];
  backupId: string | null;
};

export type ModBackup = {
  id: string;
  at: number;
  reason: string;
  files: { fileName: string; modId: string | null; name: string; version: string; enabled: boolean; size: number }[];
  change: { kind: string; modId: string; name: string; toVersion: string; toFile: string; at: number } | null;
};

export type InstanceFolder = "instance" | "mods" | "logs" | "config" | "crash-reports" | "backups";

/**
 * Four-state pack lifecycle. `installed` and `disabled` sit in the same folder
 * on disk; the difference is intent, and only `enabled` reaches options.txt.
 */
export type PackState = "downloaded" | "installed" | "enabled" | "disabled";

export type ManagedPackRecord = {
  projectId: string;
  projectSlug: string;
  title: string;
  summary?: string | null;
  iconUrl?: string | null;
  packType: "resourcepack" | "shaderpack" | "datapack";
  gameVersion: string;
  installedVersionId: string;
  installedVersionName: string;
  fileName: string;
  state: PackState;
};

export type LaunchStageEvent = {
  stage: string;
  message: string;
  progress?: number | null;
};

export type LaunchLogEvent = {
  message: string;
};

export type AuthLogEvent = {
  stage: string;
  message: string;
};

/**
 * Where a launch got to.
 *
 * "running" used to be emitted the instant the process spawned, so the UI
 * reported success before a single class loaded and a crash still looked like a
 * clean run. The statuses now describe what actually happened:
 *
 *   starting        the process spawned, nothing has proved it survived
 *   running         it is alive past the startup window, or Minecraft said so
 *   exited          it closed cleanly
 *   startup_failed  it died during startup
 *   crashed         it left a crash report or a JVM error log
 */
export type LaunchStatus =
  | "starting"
  | "running"
  | "exited"
  | "startup_failed"
  | "crashed";

export type LaunchCompleteEvent = {
  status: LaunchStatus | string;
  pid?: number | null;
  versionId: string;
  gameDirectory: string;
  /** Short, user-facing summary for the failure statuses. */
  message?: string | null;
  /** The first meaningful line of a crash report, when there is one. */
  detail?: string | null;
  /** Path to the crash report, so the user can open it. */
  reportPath?: string | null;
};

export type InstallModInput = {
  profileId: string;
  gameVersion: string;
  loader: string;
  projectId: string;
  projectSlug: string;
  title: string;
  summary?: string | null;
  iconUrl?: string | null;
};

export type ImportCustomModInput = {
  profileId: string;
  gameVersion: string;
  loader: string;
  fileName: string;
  /** The jar's bytes, from the file picker. */
  bytes?: number[];
  /** A path instead, for a jar dropped onto the window. */
  sourcePath?: string;
};

export type LocalServerPrepareInput = {
  id: string;
  name: string;
  loader: string;
  version: string;
  ramMb: number;
  port: number;
};

export type LocalServerPrepareResult = {
  id: string;
  path: string;
  serverJarPath?: string | null;
  connectionHost: string;
  connectionPort: number;
  logs: string[];
};

export type LocalServerFileImportInput = {
  serverPath: string;
  fileName: string;
  /** "root" writes into the server's working directory (server.properties, eula.txt). */
  fileType: "mods" | "plugins" | "config" | "logs" | "files" | "root";
  bytes: number[];
};

export type LocalServerFileImportResult = {
  name: string;
  path: string;
  fileType: string;
  size: number;
};

export type LocalServerProcessResult = {
  id: string;
  status: "running" | "stopped";
  logPath?: string | null;
  logs: string[];
};

type TauriWindow = Window & {
  __TAURI_INTERNALS__?: unknown;
};

export function isRunningInTauri(): boolean {
  return Boolean((window as TauriWindow).__TAURI_INTERNALS__);
}


export async function beginMicrosoftAuth(): Promise<NativeAccount> {
  ensureTauriBridge("Microsoft login");
  return invoke<NativeAccount>("begin_microsoft_auth");
}

export async function restoreSavedSession(): Promise<NativeAccount> {
  ensureTauriBridge("Session restore");
  return invoke<NativeAccount>("restore_saved_session");
}

/** Signs out of the active account. Returns the next stored account, if any. */
export async function clearSavedSession(): Promise<NativeAccount | null> {
  ensureTauriBridge("Session clear");
  return (await invoke<NativeAccount | null>("clear_saved_session")) ?? null;
}

export type SavedAccountSummary = {
  uuid: string;
  username: string;
  active: boolean;
  /**
   * True when this account holds a Microsoft refresh token that has not been
   * rejected, i.e. switching to it will not prompt for a sign-in.
   */
  canResume: boolean;
};

/** Every account signed in on this device (no tokens are exposed here). */
export async function listSavedAccounts(): Promise<SavedAccountSummary[]> {
  if (!isRunningInTauri()) return [];
  try {
    return (await invoke<SavedAccountSummary[]>("list_saved_accounts")) ?? [];
  } catch {
    return [];
  }
}

/**
 * Switch to another signed-in account. Its stored Microsoft refresh token is
 * used to mint fresh tokens, so this does not prompt for a new sign-in unless
 * Microsoft has expired or revoked the session.
 */
export async function switchAccount(uuid: string): Promise<NativeAccount> {
  ensureTauriBridge("Account switch");
  return invoke<NativeAccount>("switch_account", { uuid });
}

/** Forget one stored account, leaving the others signed in. */
export async function removeSavedAccount(uuid: string): Promise<SavedAccountSummary[]> {
  ensureTauriBridge("Account removal");
  return (await invoke<SavedAccountSummary[]>("remove_saved_account", { uuid })) ?? [];
}

/** Forget every stored account on this device. */
export async function signOutAllAccounts(): Promise<void> {
  ensureTauriBridge("Sign out");
  await invoke("sign_out_all_accounts");
}

/**
 * Save a background that has already been staged to disk in chunks.
 *
 * Preferred over the byte-array form: a large photo sent as JSON numbers is
 * silently dropped by the IPC layer, which is why only small images appeared
 * to work. Stored as a real file rather than in localStorage, whose ~5MB quota
 * silently rejected any full-size photo.
 *
 * Returns a URL the webview can render. The Rust side hands back a filesystem
 * path and it is converted here, so no image bytes cross IPC in either
 * direction.
 */
export async function saveCustomBackgroundStaged(stagedPath: string, ext: string): Promise<string> {
  ensureTauriBridge("Background image");
  const saved = await invoke<string>("save_custom_background_staged", { stagedPath, ext });
  return toBackgroundUrl(saved);
}

/**
 * Turn whatever the backend returned into something CSS can load.
 *
 * Tolerates a data URL as well as a path. Older builds returned base64 and a
 * user who has not restarted since updating can still have one in flight; there
 * is no reason to break their background over it.
 */
function toBackgroundUrl(value: string): string {
  if (!value) return value;
  if (value.startsWith("data:") || value.startsWith("asset:") || value.startsWith("http")) return value;
  return convertFileSrc(value);
}

export async function saveCustomBackground(bytes: number[], ext: string): Promise<string> {
  ensureTauriBridge("Custom background");
  const saved = await invoke<string>("save_custom_background", { bytes, ext });
  return toBackgroundUrl(saved);
}

/**
 * The stored background as a URL the webview can render, or null if unset.
 *
 * Served straight off disk through the asset protocol. It used to come back as
 * base64 over IPC on every launch, which for a large wallpaper meant a payload
 * a third bigger than the file itself and a background that could fail to
 * reappear after a restart while smaller ones were fine.
 */
export async function loadCustomBackground(): Promise<string | null> {
  if (!isRunningInTauri()) return null;
  try {
    const stored = (await invoke<string | null>("load_custom_background")) ?? null;
    return stored ? toBackgroundUrl(stored) : null;
  } catch {
    return null;
  }
}

export async function clearCustomBackground(): Promise<void> {
  if (!isRunningInTauri()) return;
  try { await invoke("clear_custom_background"); } catch { /* already gone */ }
}

export async function getLauncherSettings(): Promise<LauncherSettings> {
  ensureTauriBridge("Launcher settings");
  return invoke<LauncherSettings>("get_launcher_settings");
}

export async function updateLauncherSettings(
  settings: LauncherSettings,
): Promise<LauncherSettings> {
  ensureTauriBridge("Launcher settings");
  return invoke<LauncherSettings>("update_launcher_settings", { settings });
}

export async function importFeatherPreferences(input?: {
  includeAllVersions?: boolean;
}): Promise<FeatherImportSummary> {
  ensureTauriBridge("Feather import");
  return invoke<FeatherImportSummary>("import_feather_preferences", {
    request: {
      includeAllVersions: input?.includeAllVersions ?? false,
    },
  });
}

export type ThirdPartyImportSummary = {
  clientDetected: boolean;
  importedCustomMods: number;
  importedModrinthMods: number;
  copiedConfigDirectories: number;
  selectedVersion?: string | null;
  warnings: string[];
};

export async function importLunarPreferences(): Promise<ThirdPartyImportSummary> {
  ensureTauriBridge("Lunar import");
  return invoke<ThirdPartyImportSummary>("import_lunar_preferences");
}

export async function importBadlionPreferences(): Promise<ThirdPartyImportSummary> {
  ensureTauriBridge("Badlion import");
  return invoke<ThirdPartyImportSummary>("import_badlion_preferences");
}

/** What importing one launcher's mods did (src-tauri/src/foreign_mods.rs). */
export type ClientModsImport = {
  client: string;
  clientFound: boolean;
  targetVersion: string;
  foldersSearched: number;
  modsFound: number;
  otherJars: number;
  installed: string[];
  alreadyInstalled: string[];
  copied: string[];
  unavailable: string[];
  warnings: string[];
};

/**
 * Finds another launcher's Fabric mods, identifies each on Modrinth and
 * installs its build for `targetVersion` (with dependencies). Mods not on
 * Modrinth are copied only when their own jar runs on that version.
 */
export async function importClientMods(input: { clientId: string; targetVersion: string }): Promise<ClientModsImport> {
  ensureTauriBridge("Mod import");
  return invoke<ClientModsImport>("import_client_mods", { request: input });
}


export type DetectedClient = {
  id: string;
  name: string;
  detected: boolean;
  rootPath?: string | null;
  activeVersion?: string | null;
  profileCount: number;
  modCount: number;
  hasOptions: boolean;
  hasOptifineOptions: boolean;
  hasResourcepacks: boolean;
  hasShaderpacks: boolean;
  hasSaves: boolean;
  hasConfig: boolean;
};

export type DetectionReport = {
  clients: DetectedClient[];
  breezeHome: string;
};

export type VanillaImportSummary = {
  detected: boolean;
  profileId?: string | null;
  activeVersion?: string | null;
  importedCustomMods: number;
  importedResourcepacks: number;
  importedShaderpacks: number;
  copiedOptions: boolean;
  copiedOptifineOptions: boolean;
  copiedSaves: number;
  copiedConfigDirectories: number;
  warnings: string[];
};

export type VanillaImportInput = {
  targetVersion?: string | null;
  includeResourcepacks?: boolean;
  includeShaderpacks?: boolean;
  includeSaves?: boolean;
  includeOptions?: boolean;
  includeMods?: boolean;
};

export type ModrinthAppImportSummary = {
  detected: boolean;
  rootPath?: string | null;
  importedProfiles: string[];
  importedMods: number;
  importedResourcepacks: number;
  importedShaderpacks: number;
  copiedConfigDirectories: number;
  copiedOptions: number;
  selectedVersion?: string | null;
  warnings: string[];
};

export type ModrinthAppImportInput = {
  includeAllProfiles?: boolean;
  includeResourcepacks?: boolean;
  includeShaderpacks?: boolean;
  includeSaves?: boolean;
  includeOptions?: boolean;
};

export type MrpackImportSummary = {
  packName: string;
  packVersion?: string | null;
  gameVersion: string;
  loader: string;
  profileId: string;
  downloadedMods: number;
  copiedOverrides: number;
  warnings: string[];
};

export type ImportEverythingInput = {
  clients?: string[];
  includeResourcepacks?: boolean;
  includeShaderpacks?: boolean;
  includeSaves?: boolean;
  includeOptions?: boolean;
  includeMods?: boolean;
  createBackup?: boolean;
};

export type ImportEverythingSummary = {
  attempted: string[];
  succeeded: string[];
  failed: Array<{ client: string; error: string }>;
  totalMods: number;
  totalResourcepacks: number;
  totalShaderpacks: number;
  totalConfigs: number;
  totalOptions: number;
  backupPath?: string | null;
  selectedVersion?: string | null;
  warnings: string[];
};

export async function detectImportableClients(): Promise<DetectionReport> {
  ensureTauriBridge("Client detection");
  return invoke<DetectionReport>("detect_importable_clients");
}

export async function importVanillaPreferences(
  input?: VanillaImportInput,
): Promise<VanillaImportSummary> {
  ensureTauriBridge("Vanilla import");
  return invoke<VanillaImportSummary>("import_vanilla_preferences", {
    request: input ?? {},
  });
}

export async function importModrinthAppPreferences(
  input?: ModrinthAppImportInput,
): Promise<ModrinthAppImportSummary> {
  ensureTauriBridge("Modrinth App import");
  return invoke<ModrinthAppImportSummary>("import_modrinth_app_preferences", {
    request: input ?? {},
  });
}

/**
 * Stream a file to a temp path in chunks, then return that path.
 *
 * Sending a whole modpack as a byte array over IPC serialises millions of
 * numbers as JSON; past ~100MB the payload is dropped and the Rust side reports
 * that it received neither a path nor bytes. Chunking keeps every message small
 * and memory flat, and the per-chunk callback gives the UI real progress.
 */
export async function stageFileForImport(
  file: File,
  onProgress?: (fraction: number) => void,
  chunkSize = 4 * 1024 * 1024,
): Promise<string> {
  ensureTauriBridge("File import");
  let stagedPath = "";
  let offset = 0;
  let first = true;
  while (offset < file.size) {
    const slice = file.slice(offset, Math.min(offset + chunkSize, file.size));
    const chunk = Array.from(new Uint8Array(await slice.arrayBuffer()));
    stagedPath = await invoke<string>("stage_import_chunk", {
      fileName: file.name,
      chunk,
      first,
    });
    first = false;
    offset += chunkSize;
    onProgress?.(Math.min(1, offset / file.size));
  }
  return stagedPath;
}

/** Delete a staged import file. Safe to call even if it is already gone. */
export async function clearStagedImport(fileName: string): Promise<void> {
  if (!isRunningInTauri()) return;
  try { await invoke("clear_staged_import", { fileName }); } catch { /* already removed */ }
}

export type MrpackExportSummary = {
  path: string;
  /** Mods the pack lists for Modrinth to download. */
  linkedMods: number;
  /** Mods carried inside the pack because they are not on Modrinth. */
  bundledMods: number;
  overrides: number;
  warnings: string[];
};

/** Write a profile out as a Modrinth pack (.mrpack) under ~/.breezeclient/exports. */
export async function exportMrpack(input: {
  profileId: string;
  name?: string;
  includeConfig?: boolean;
  includeResourcepacks?: boolean;
}): Promise<MrpackExportSummary> {
  ensureTauriBridge("Modpack export");
  return invoke<MrpackExportSummary>("export_mrpack", { request: input });
}

/**
 * Files dropped onto the launcher window, with their real paths.
 *
 * Tauri delivers the OS drop as a webview event. The browser's own drop event
 * gives a File object with no path, and passing a 30MB jar through IPC as a
 * JSON array of numbers is what made large imports fail, so the path is what
 * gets handed to Rust.
 */
export async function listenToFileDrops(handler: (paths: string[]) => void): Promise<UnlistenFn> {
  ensureTauriBridge("File drops");
  const { getCurrentWebview } = await import("@tauri-apps/api/webview");
  return getCurrentWebview().onDragDropEvent((event) => {
    const payload = event.payload as { type: string; paths?: string[] };
    if (payload?.type !== "drop") return;
    handler(payload.paths || []);
  });
}

export type LibraryPack = {
  fileName: string;
  packType: string;
  size: number;
  /** True when this version already lists the pack. */
  inThisVersion: boolean;
};

/** Packs already downloaded for any version, stored once and shared. */
export async function listPackLibrary(profileId: string, packType: string): Promise<LibraryPack[]> {
  ensureTauriBridge("Pack library");
  return invoke<LibraryPack[]>("list_pack_library", { profileId, packType });
}

/** Turn a pack that is already in the library on for this version. */
export async function useLibraryPack(profileId: string, packType: string, fileName: string): Promise<ManagedPackRecord> {
  ensureTauriBridge("Pack library");
  return invoke<ManagedPackRecord>("use_library_pack", { profileId, packType, fileName });
}

/** Show a file in the system file manager. */
export async function revealPath(target: string): Promise<void> {
  ensureTauriBridge("Reveal in folder");
  await revealItemInDir(target);
}

export async function importMrpack(input: {
  path?: string;
  bytes?: number[];
  targetProfileId?: string | null;
}): Promise<MrpackImportSummary> {
  ensureTauriBridge("Modrinth pack import");
  return invoke<MrpackImportSummary>("import_mrpack", { request: input });
}

export async function importEverything(
  input?: ImportEverythingInput,
): Promise<ImportEverythingSummary> {
  ensureTauriBridge("One-click import");
  return invoke<ImportEverythingSummary>("import_everything", {
    request: input ?? {},
  });
}


export type JavaRuntimeStatus = {
  installed: boolean;
  javaPath: string;
  source: "managed" | "bundled" | "env" | "system" | "missing" | string;
  root?: string | null;
  /** Major version of the runtime found, when one was found. */
  major?: number | null;
};

export type JavaRuntimeEvent = {
  stage: string;
  message: string;
  progress?: number | null;
};

export type BreezePlatform = "windows" | "macos" | "linux";

/** The OS this launcher is running on, so the updater fetches the right build. */
export async function getPlatform(): Promise<BreezePlatform> {
  if (isRunningInTauri()) {
    const os = await invoke<string>("get_platform");
    return (os as BreezePlatform) || "windows";
  }
  // Web/dev fallback, best-effort from the user agent.
  const ua = navigator.userAgent || "";
  if (/Mac/i.test(ua)) return "macos";
  if (/Linux/i.test(ua) && !/Android/i.test(ua)) return "linux";
  return "windows";
}

export type UpdaterEvent = { stage: string; message: string; progress?: number | null };

/** Downloads the new installer from the API, launches it, and exits Breeze so
 *  the installer can replace this build. Listen on breeze://updater for
 *  progress. */
export async function downloadAndInstallUpdate(input: {
  url: string;
  fileName?: string | null;
  version?: string | null;
  /** Published sha256, when the version API returns one. Verified in Rust
   *  before the installer is executed. Omitted means the download is accepted
   *  unverified, which is what older API responses produce. */
  sha256?: string | null;
}): Promise<void> {
  ensureTauriBridge("Launcher update");
  await invoke("download_and_install_update", { request: input });
}

export async function listenToUpdater(
  handler: (event: UpdaterEvent) => void,
): Promise<UnlistenFn> {
  ensureTauriBridge("Updater events");
  return listen<UpdaterEvent>("breeze://updater", (event) => {
    handler(event.payload);
  });
}

export type CompatibilityStep = {
  key: string;
  label: string;
  status: "ok" | "warn" | "error" | "unknown";
  detail: string;
};

export type VersionCompatibility = {
  versionId: string;
  overall: "supported" | "without-breeze" | "unsupported" | "unknown";
  summary: string;
  steps: CompatibilityStep[];
  javaMajor?: number | null;
  fabricLoader?: string | null;
  breezeModVersion?: string | null;
};

/** Minecraft, Java, Fabric Loader, Breeze build and Fabric API for one version. */
export async function getVersionCompatibility(versionId: string, apiBaseUrl?: string): Promise<VersionCompatibility> {
  ensureTauriBridge("Version compatibility");
  return invoke<VersionCompatibility>("get_version_compatibility", { request: { versionId, apiBaseUrl } });
}

export async function ensureJavaRuntime(): Promise<JavaRuntimeStatus> {
  ensureTauriBridge("Java runtime bootstrap");
  return invoke<JavaRuntimeStatus>("ensure_java_runtime");
}

export async function getJavaRuntimeStatus(): Promise<JavaRuntimeStatus> {
  ensureTauriBridge("Java runtime status");
  return invoke<JavaRuntimeStatus>("get_java_runtime_status");
}

export async function listenToJavaRuntime(
  handler: (event: JavaRuntimeEvent) => void,
): Promise<UnlistenFn> {
  ensureTauriBridge("Java runtime events");
  return listen<JavaRuntimeEvent>("breeze://java-runtime", (event) => {
    handler(event.payload);
  });
}

export async function applyPerformanceProfile(input: {
  profileId: string;
  gameVersion: string;
  loader: string;
  profileName: string;
}): Promise<PerformanceProfileResult> {
  ensureTauriBridge("Performance profile");
  return invoke<PerformanceProfileResult>("apply_performance_profile", {
    request: input,
  });
}

export async function prewarmVersion(input: {
  versionId: string;
  loaderVersion?: string | null;
}): Promise<PrewarmResult> {
  ensureTauriBridge("Version prewarm");
  return invoke<PrewarmResult>("prewarm_version", { request: input });
}

export async function getSystemMemoryInfo(): Promise<SystemMemoryInfo> {
  ensureTauriBridge("System memory info");
  return invoke<SystemMemoryInfo>("get_system_memory_info");
}

export async function openExternalUrl(url: string) {
  if (isRunningInTauri()) {
    await openUrl(url);
    return;
  }
  window.open(url, "_blank", "noopener,noreferrer");
}

export type RecordingFile = { name: string; size_bytes: number; modified_unix: number };
export type RecordingsInfo = { path: string; files: RecordingFile[] };


export async function getRecordingsInfo(versionId: string): Promise<RecordingsInfo> {
  ensureTauriBridge("Recordings folder");
  return invoke<RecordingsInfo>("get_recordings_dir", { versionId });
}


export async function openRecordingsFolder(versionId: string): Promise<void> {
  const info = await getRecordingsInfo(versionId);
  await openPath(info.path);
}


export async function revealRecording(versionId: string, fileName: string): Promise<void> {
  const info = await getRecordingsInfo(versionId);
  const sep = info.path.includes("\\") ? "\\" : "/";
  await revealItemInDir(`${info.path}${sep}${fileName}`);
}

export async function prepareNativeLaunch(input: {
  versionId: string;
  account: NativeAccount;
}): Promise<void> {
  ensureTauriBridge("Minecraft launch");
  await invoke("prepare_launch", { request: input });
}

export async function launchMinecraft(input: {
  versionId: string;
  loaderVersion?: string | null;
  maxRamMb?: number;
  account: NativeAccount;
  breezeModUrl?: string | null;
  breezeModSha256?: string | null;
  /**
   * The signed-in Breeze session token. The launcher uses it to fetch the
   * Breeze mod from the authorized endpoint and to obtain a short-lived game
   * token for the mod. It never reaches the game itself. Before this was sent,
   * Rust only ever had an empty token, so the authorized download failed and
   * the mod had no identity.
   */
  breezeToken?: string | null;
  /** API base the token belongs to, so Rust never talks to a different host. */
  apiBaseUrl?: string | null;
}): Promise<LaunchCompleteEvent> {
  ensureTauriBridge("Minecraft launch");
  return invoke<LaunchCompleteEvent>("launch_minecraft", {
    request: input,
  });
}

export async function listInstalledMods(
  profileId: string,
): Promise<ManagedModRecord[]> {
  ensureTauriBridge("Mod profile loading");
  return invoke<ManagedModRecord[]>("list_installed_mods", { profileId });
}

/** Every jar in the version's mods folder, with its metadata, problems and duplicates. */
export async function scanInstanceMods(profileId: string, gameVersion?: string): Promise<InstanceModsReport> {
  ensureTauriBridge("Mods folder check");
  return invoke<InstanceModsReport>("scan_instance_mods", { profileId, gameVersion: gameVersion ?? null });
}

/** Moves one jar out of the mods folder into the version's backups. */
export async function removeModFile(profileId: string, fileName: string): Promise<{ fileName: string; backupPath: string }> {
  ensureTauriBridge("Mod removal");
  return invoke("remove_mod_file", { profileId, fileName });
}

export async function openInstanceFolder(profileId: string, which: InstanceFolder): Promise<string> {
  ensureTauriBridge("Opening a folder");
  return invoke<string>("open_instance_folder", { profileId, which });
}

export async function listModVersions(profileId: string, key: string, gameVersion?: string): Promise<ModVersions> {
  ensureTauriBridge("Mod versions");
  return invoke<ModVersions>("list_mod_versions", { profileId, key, gameVersion: gameVersion ?? null });
}

/** Downloads and checks a version without touching the mods folder. */
export async function stageModVersion(profileId: string, key: string, versionId: string, gameVersion?: string): Promise<StagedModChange> {
  ensureTauriBridge("Mod version download");
  return invoke<StagedModChange>("stage_mod_version", { profileId, key, versionId, gameVersion: gameVersion ?? null });
}

export async function applyModChange(profileId: string, token: string, acceptIssues: boolean, gameVersion?: string): Promise<AppliedModChange> {
  ensureTauriBridge("Mod version change");
  return invoke<AppliedModChange>("apply_mod_change", { profileId, token, acceptIssues, gameVersion: gameVersion ?? null });
}

export async function discardModChange(profileId: string, token: string): Promise<void> {
  ensureTauriBridge("Mod version change");
  await invoke("discard_mod_change", { profileId, token });
}

export async function listModBackups(profileId: string): Promise<ModBackup[]> {
  ensureTauriBridge("Mod backups");
  return invoke<ModBackup[]>("list_mod_backups", { profileId });
}

export async function restoreModBackup(profileId: string, backupId: string, fileName: string): Promise<AppliedModChange> {
  ensureTauriBridge("Mod backups");
  return invoke<AppliedModChange>("restore_mod_backup", { profileId, backupId, fileName });
}

export async function installModrinthMod(
  input: InstallModInput,
): Promise<ManagedModRecord> {
  ensureTauriBridge("Mod installation");
  return invoke<ManagedModRecord>("install_modrinth_mod", { request: input });
}

export async function importCustomMod(
  input: ImportCustomModInput,
): Promise<ManagedModRecord> {
  ensureTauriBridge("Custom mod import");
  return invoke<ManagedModRecord>("import_custom_mod", { request: input });
}

export async function listInstalledPacks(
  profileId: string,
): Promise<ManagedPackRecord[]> {
  ensureTauriBridge("Pack loading");
  return invoke<ManagedPackRecord[]>("list_installed_packs", { profileId });
}

export async function downloadModrinthPack(input: {
  profileId: string;
  gameVersion: string;
  packType: string;
  projectId: string;
  projectSlug: string;
  title: string;
  summary?: string | null;
  iconUrl?: string | null;
}): Promise<ManagedPackRecord> {
  ensureTauriBridge("Pack download");
  return invoke<ManagedPackRecord>("download_modrinth_pack", { request: input });
}

export async function importCustomPack(input: {
  profileId: string;
  gameVersion: string;
  packType: string;
  fileName: string;
  /**
   * A path from {@link stageFileForImport}, not raw bytes.
   *
   * A byte array crosses IPC as JSON numbers, and high resolution texture packs
   * routinely exceed the size where that payload is silently dropped. This is
   * the same problem the modpack importer already solved.
   */
  stagedPath: string;
}): Promise<ManagedPackRecord> {
  ensureTauriBridge("Pack import");
  return invoke<ManagedPackRecord>("import_custom_pack", { request: input });
}

export async function setPackState(input: {
  profileId: string;
  projectId: string;
  state: PackState;
}): Promise<ManagedPackRecord> {
  ensureTauriBridge("Pack state change");
  return invoke<ManagedPackRecord>("set_pack_state", input);
}

export async function removeInstalledPack(input: {
  profileId: string;
  projectId: string;
}): Promise<void> {
  ensureTauriBridge("Pack removal");
  await invoke("remove_installed_pack", input);
}

export async function setInstalledModEnabled(input: {
  profileId: string;
  projectId: string;
  enabled: boolean;
}): Promise<ManagedModRecord> {
  ensureTauriBridge("Mod toggling");
  return invoke<ManagedModRecord>("set_mod_enabled", input);
}

export async function removeInstalledMod(input: {
  profileId: string;
  projectId: string;
}): Promise<void> {
  ensureTauriBridge("Mod removal");
  await invoke("remove_installed_mod", input);
}

/**
 * Copy one locally imported jar into another version's profile.
 *
 * Only for mods with no Modrinth project to reinstall from. A mod that came
 * from Modrinth is transferred by installing it again for the target version,
 * because the jar built for one Minecraft version usually will not load on
 * another, and copying it would hand the user a crash instead of a mod.
 */
export async function copyLocalModToProfile(input: {
  fromProfileId: string;
  toProfileId: string;
  projectId: string;
  gameVersion: string;
  loader: string;
}): Promise<ManagedModRecord> {
  ensureTauriBridge("Mod transfer");
  return invoke<ManagedModRecord>("copy_local_mod_to_profile", { request: input });
}

export async function prepareLocalServer(
  input: LocalServerPrepareInput,
): Promise<LocalServerPrepareResult> {
  ensureTauriBridge("Local server preparation");
  return invoke<LocalServerPrepareResult>("prepare_local_server", { request: input });
}

export async function importLocalServerFile(
  input: LocalServerFileImportInput,
): Promise<LocalServerFileImportResult> {
  ensureTauriBridge("Local server file import");
  return invoke<LocalServerFileImportResult>("import_local_server_file", { request: input });
}

export async function startLocalServer(input: {
  id: string;
  serverPath: string;
  ramMb: number;
}): Promise<LocalServerProcessResult> {
  ensureTauriBridge("Local server start");
  return invoke<LocalServerProcessResult>("start_local_server", { request: input });
}

export async function stopLocalServer(input: {
  id: string;
  serverPath: string;
  ramMb?: number;
}): Promise<LocalServerProcessResult> {
  ensureTauriBridge("Local server stop");
  return invoke<LocalServerProcessResult>("stop_local_server", { request: input });
}

/** Tails the running server's log file and reports whether the process is
 *  still alive, so the hosting console shows real output and crashed servers
 *  flip back to "stopped" in the UI. */
export async function readLocalServerLog(input: {
  id: string;
  serverPath: string;
}): Promise<LocalServerProcessResult> {
  ensureTauriBridge("Local server log");
  return invoke<LocalServerProcessResult>("read_local_server_log", { request: input });
}

export async function openLocalServerFolder(path: string): Promise<void> {
  ensureTauriBridge("Local server folder");
  await openPath(path);
}

export async function listenToLaunchStage(
  handler: (event: LaunchStageEvent) => void,
): Promise<UnlistenFn> {
  ensureTauriBridge("Launch events");
  return listen<LaunchStageEvent>("breeze://launch-stage", (event) => {
    handler(event.payload);
  });
}

export async function listenToLaunchLog(
  handler: (event: LaunchLogEvent) => void,
): Promise<UnlistenFn> {
  ensureTauriBridge("Launch events");
  return listen<LaunchLogEvent>("breeze://launch-log", (event) => {
    handler(event.payload);
  });
}

export async function listenToLaunchComplete(
  handler: (event: LaunchCompleteEvent) => void,
): Promise<UnlistenFn> {
  ensureTauriBridge("Launch events");
  return listen<LaunchCompleteEvent>("breeze://launch-complete", (event) => {
    handler(event.payload);
  });
}

export async function listenToLaunchError(
  handler: (event: LaunchLogEvent) => void,
): Promise<UnlistenFn> {
  ensureTauriBridge("Launch events");
  return listen<LaunchLogEvent>("breeze://launch-error", (event) => {
    handler(event.payload);
  });
}

export async function listenToAuthLog(
  handler: (event: AuthLogEvent) => void,
): Promise<UnlistenFn> {
  ensureTauriBridge("Authentication events");
  return listen<AuthLogEvent>("breeze://auth-log", (event) => {
    handler(event.payload);
  });
}

function ensureTauriBridge(action: string) {
  if (!isRunningInTauri()) {
    throw new Error(
      `${action} requires the Breeze desktop app. ` +
      `Run "npm run tauri dev" for development, or use the installed Breeze desktop application.`,
    );
  }
}
