/**
 * Browser preview of the launcher, for design work and screenshots.
 *
 * Development only: loaded by preview.html, which is not a build input, so none
 * of this reaches an installer. It replaces the Rust side with Tauri's own IPC
 * mocks and answers each command with a fixed, clearly-labelled sample. A launch
 * replays the real event sequence the Rust side emits (stages, then starting,
 * running and, when asked, exited or crashed), with timings from a cached
 * launch, so the interface can be designed against real states.
 *
 * URL switches: ?fresh=1 skips the stored splash history, ?nosplash=1 removes
 * the startup scene, ?launch=fail makes the next launch crash, ?slow=1 runs the
 * launch at the pace of a first launch, ?signedout=1 opens on the sign-in gate
 * with no account on this computer, and ?signedout=saved with one account whose
 * session has expired. Signing in from the gate takes 1.5 s. ?mods=folder
 * shows a mods folder with duplicates, jars added outside the launcher and a
 * missing library, with version changes and backups that act on it.
 * ?afterLaunch=minimize turns "Keep Launcher Open" off.
 */
import { mockIPC, mockWindows } from "@tauri-apps/api/mocks";
import { emit } from "@tauri-apps/api/event";

const params = new URLSearchParams(location.search);
const ACCOUNT = { uuid: "00000000-0000-4000-8000-000000000001", username: "PreviewPlayer", accessToken: "preview", breezeToken: "preview" };
let settings: Record<string, unknown> = {
  allocatedRamMb: 6144, theme: params.get("theme") || "glass", includeSnapshots: false, performanceProfile: "Balanced",
  prewarmEnabled: true, selectedVersion: params.get("version") || "1.21.4", lastLaunchedVersion: "1.21.4", lastLaunchedAt: null,
  modAutoUpdate: true, graphicsPerformance: false, browserAccel: "auto", afterLaunch: params.get("afterLaunch") || "keep-open", minecraftPath: "~/.breezeclient",
  importedFromFeather: false,
};
const mod = (canonicalId: string, projectId: string, title: string, summary: string, versionName: string, enabled = true) => ({
  canonicalId, projectId, projectSlug: canonicalId, title, summary, gameVersion: "1.21.4", loader: "fabric",
  installedVersionId: "x", installedVersionName: versionName, fileName: `${canonicalId}.jar`, enabled,
});
let MODS = [
  mod("sodium", "AANobbMI", "Sodium", "Rendering engine replacement", "0.6.x"),
  mod("lithium", "gvQqBUqZ", "Lithium", "General-purpose optimization mod", "0.14.x"),
  mod("ferrite-core", "uXXizFIs", "FerriteCore", "Memory usage optimizations", "7.1.x"),
  mod("entityculling", "NNAgCjsB", "EntityCulling", "Skips rendering entities you cannot see", "1.7.x", false),
  mod("immediatelyfast", "5ZwdcRci", "ImmediatelyFast", "Faster immediate mode rendering", "1.3.x"),
  mod("modmenu", "mOgUt4GM", "Mod Menu", "Lists your mods in game", "13.0.x"),
];
if (params.get("mods") === "none") MODS = [];

// ?mods=folder: a tester's folder as reported on 2026-10-03, with jars the
// launcher did not install, Fabric API twice, and a mod missing its library.
type PreviewFile = { fileName: string; modId: string; name: string; version: string; enabled: boolean; managed: boolean; depends?: Record<string, string> };
let FILES: PreviewFile[] = [
  { fileName: "fabric-api-0.138.0+1.21.11.jar", modId: "fabric-api", name: "Fabric API", version: "0.138.0+1.21.11", enabled: true, managed: true },
  { fileName: "fabric-api-0.139.1+1.21.11.jar", modId: "fabric-api", name: "Fabric API", version: "0.139.1+1.21.11", enabled: true, managed: false },
  { fileName: "sodium-fabric-0.6.13+mc1.21.11.jar", modId: "sodium", name: "Sodium", version: "0.6.13+mc1.21.11", enabled: true, managed: true },
  { fileName: "reeses-sodium-options-1.8.4+mc1.21.11.jar", modId: "reeses-sodium-options", name: "Reese's Sodium Options", version: "1.8.4+mc1.21.11", enabled: true, managed: false, depends: { sodium: "<0.7.0" } },
  { fileName: "modmenu-17.0.0.jar", modId: "modmenu", name: "Mod Menu", version: "17.0.0", enabled: true, managed: false },
  { fileName: "zoomify-2.14.6+1.21.11.jar", modId: "zoomify", name: "Zoomify", version: "2.14.6+1.21.11", enabled: true, managed: false, depends: { yet_another_config_lib_v3: ">=3.6.0" } },
];
let BACKUPS: { id: string; at: number; reason: string; files: PreviewFile[] }[] = [];
const folderMode = params.get("mods") === "folder";
const fileRecords = () => FILES.map((f) => ({
  canonicalId: f.managed ? f.modId : `file:${f.fileName}`, projectId: f.managed ? f.modId : `file:${f.fileName}`, projectSlug: f.modId,
  title: f.name, summary: null, gameVersion: "1.21.11", loader: "fabric", installedVersionId: "", installedVersionName: f.version,
  fileName: f.fileName, enabled: f.enabled, managed: f.managed, modId: f.modId,
}));
function previewReport() {
  const byId = new Map<string, PreviewFile[]>();
  for (const f of FILES.filter((x) => x.enabled)) byId.set(f.modId, [...(byId.get(f.modId) || []), f]);
  const duplicates = [...byId.entries()].filter(([, v]) => v.length > 1).map(([modId, v]) => ({
    modId, name: v[0].name, files: [...v].sort((a, b) => b.version.localeCompare(a.version)).map((f) => ({ fileName: f.fileName, version: f.version })),
  }));
  const mods = FILES.map((f) => {
    const issues: unknown[] = [];
    for (const [dep, range] of Object.entries(f.depends || {})) {
      const there = FILES.find((x) => x.enabled && x.modId === dep);
      if (!there) issues.push({ kind: "missingDependency", dependency: dep, wants: [range] });
      else if (range.startsWith("<") && there.version.localeCompare(range.slice(1)) >= 0) issues.push({ kind: "wrongVersion", dependency: dep, wants: [range], found: there.version });
    }
    const dup = duplicates.find((d) => d.modId === f.modId && f.enabled);
    return {
      key: f.managed ? f.modId : `file:${f.fileName}`, fileName: f.fileName, jarName: f.fileName, enabled: f.enabled, size: 1, sha1: "", sha512: "",
      modId: f.modId, name: f.name, version: f.version, description: "", managed: f.managed, projectId: f.managed ? f.modId : null,
      iconUrl: null, installedVersionId: null, breeze: false, problem: null, issues, duplicate: Boolean(dup), loadedLastRun: null,
    };
  });
  return {
    instanceDir: "~/.breezeclient/instances/1.21.11", modsDir: "~/.breezeclient/instances/1.21.11/mods", minecraft: "1.21.11", loader: "0.19.5", java: 21,
    mods, duplicates, missingFiles: [], lastRun: null,
    lastLaunchProblem: duplicates.length
      ? "Incompatible mods found!\nnet.fabricmc.loader.impl.FormattedException: Some of your mods are incompatible with the game or each other!\nA potential solution has been determined, this may resolve your problem:\n\t - Remove one of the duplicate fabric-api files.\nMore details:\n\t - Mod 'Fabric API' (fabric-api) 0.139.1+1.21.11 is installed more than once."
      : null,
  };
}
const moveToBackup = (fileName: string, reason: string) => {
  const f = FILES.find((x) => x.fileName === fileName);
  if (!f) throw new Error(`${fileName} is no longer in the mods folder.`);
  FILES = FILES.filter((x) => x !== f);
  BACKUPS = [{ id: `${Math.floor(Date.now() / 1000)}-${reason}`, at: Math.floor(Date.now() / 1000), reason, files: [f] }, ...BACKUPS];
};
const SODIUM_VERSIONS = [
  { id: "s07", versionNumber: "mc1.21.11-0.7.0-fabric", name: "Sodium 0.7.0", versionType: "release", datePublished: "2026-09-20T10:00:00Z", featured: true, gameVersions: ["1.21.11"], fileName: "sodium-fabric-0.7.0+mc1.21.11.jar", size: 1 },
  { id: "s0614", versionNumber: "mc1.21.11-0.6.14-fabric", name: "Sodium 0.6.14", versionType: "beta", datePublished: "2026-09-02T10:00:00Z", featured: false, gameVersions: ["1.21.11"], fileName: "sodium-fabric-0.6.14+mc1.21.11.jar", size: 1 },
  { id: "s0613", versionNumber: "mc1.21.11-0.6.13-fabric", name: "Sodium 0.6.13", versionType: "release", datePublished: "2026-08-12T10:00:00Z", featured: false, gameVersions: ["1.21.11"], fileName: "sodium-fabric-0.6.13+mc1.21.11.jar", size: 1 },
];
let STAGED: { token: string; modId: string; to: (typeof SODIUM_VERSIONS)[number] } | null = null;
const FOLDER_HANDLERS: Record<string, (args: any) => unknown> = {
  list_installed_mods: () => fileRecords(),
  scan_instance_mods: () => previewReport(),
  set_mod_enabled: async (a) => {
    await wait(160);
    const name = String(a?.projectId || "").replace(/^file:/, "");
    FILES = FILES.map((f) => (f.fileName === name || f.modId === a?.projectId ? { ...f, enabled: Boolean(a?.enabled) } : f));
    return fileRecords().find((r) => r.projectId === a?.projectId);
  },
  remove_installed_mod: async (a) => { await wait(200); const name = String(a?.projectId || "").replace(/^file:/, ""); const f = FILES.find((x) => x.fileName === name || x.modId === a?.projectId); if (f) moveToBackup(f.fileName, "removed"); return null; },
  remove_mod_file: async (a) => { await wait(200); moveToBackup(a?.fileName, "removed"); return { fileName: a?.fileName, backupPath: "~/.breezeclient/instances/1.21.11/.breeze/mod-backups" }; },
  open_instance_folder: (a) => `~/.breezeclient/instances/1.21.11/${a?.which === "instance" ? "" : a?.which}`,
  list_mod_versions: async (a) => {
    await wait(400);
    const f = FILES.find((x) => (a?.key === `file:${x.fileName}`) || a?.key === x.modId);
    if (!f) throw new Error("That mod is no longer in the mods folder.");
    if (f.modId !== "sodium") return { key: a.key, modId: f.modId, name: f.name, installedVersion: f.version, fileName: f.fileName, projectId: null, identifiedBy: null, minecraft: "1.21.11", loader: "0.19.5", versions: [], note: "Modrinth does not know this file, so the launcher cannot offer other versions of it. It may come from another site or a private build." };
    return { key: a.key, modId: "sodium", name: "Sodium", installedVersion: f.version, fileName: f.fileName, projectId: "AANobbMI", identifiedBy: "record", minecraft: "1.21.11", loader: "0.19.5",
      versions: SODIUM_VERSIONS.map((v) => ({ ...v, installed: f.fileName === v.fileName })), note: null };
  },
  stage_mod_version: async (a) => {
    await wait(900);
    const to = SODIUM_VERSIONS.find((v) => v.id === a?.versionId)!;
    const from = FILES.filter((x) => x.modId === "sodium");
    STAGED = { token: "a1b2c3", modId: "sodium", to };
    const breaks = to.id === "s07" ? [{ modId: "reeses-sodium-options", name: "Reese's Sodium Options", fileName: "reeses-sodium-options-1.8.4+mc1.21.11.jar", issue: { kind: "wrongVersion", dependency: "sodium", wants: ["<0.7.0"], found: "0.7.0+mc1.21.11" } }] : [];
    return { token: "a1b2c3", modId: "sodium", name: "Sodium", from: from.map((f) => ({ fileName: f.fileName, version: f.version, enabled: f.enabled })),
      toVersion: to.versionNumber.replace(/^mc1\.21\.11-|-fabric$/g, "") + "+mc1.21.11", toFile: to.fileName, size: 1, enabled: true,
      impact: { own: [], breaks, fixes: [] }, blocking: breaks.length > 0 };
  },
  apply_mod_change: async (a) => {
    await wait(300);
    if (!STAGED || a?.token !== STAGED.token) throw new Error("That staged change does not exist any more. Choose the version again.");
    const old = FILES.filter((x) => x.modId === STAGED!.modId);
    old.forEach((f) => moveToBackup(f.fileName, "replaced"));
    FILES = [...FILES, { fileName: STAGED.to.fileName, modId: "sodium", name: "Sodium", version: STAGED.to.versionNumber.replace(/^mc1\.21\.11-|-fabric$/g, "") + "+mc1.21.11", enabled: true, managed: true }];
    STAGED = null;
    return { fileName: FILES[FILES.length - 1].fileName, enabled: true, replaced: old.map((f) => ({ fileName: f.fileName, backupPath: "" })), backupId: BACKUPS[0]?.id ?? null };
  },
  discard_mod_change: () => { STAGED = null; return null; },
  list_mod_backups: () => BACKUPS.map((b) => ({ ...b, files: b.files.map((f) => ({ fileName: f.fileName, modId: f.modId, name: f.name, version: f.version, enabled: f.enabled, size: 1 })), change: null })),
  restore_mod_backup: async (a) => {
    await wait(300);
    const b = BACKUPS.find((x) => x.id === a?.backupId);
    const f = b?.files.find((x) => x.fileName === a?.fileName);
    if (!b || !f) throw new Error(`${a?.fileName} is no longer in that backup.`);
    FILES.filter((x) => x.modId === f.modId).forEach((x) => moveToBackup(x.fileName, "rollback"));
    b.files = b.files.filter((x) => x !== f);
    BACKUPS = BACKUPS.filter((x) => x.files.length);
    FILES = [...FILES, f];
    return { fileName: f.fileName, enabled: f.enabled, replaced: [], backupId: null };
  },
};

const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
async function replayLaunch(versionId: string) {
  const slow = params.get("slow") === "1";
  const k = slow ? 4 : 1;
  const stages: [string, string, number, number][] = [
    ["java_check", "Checking Java runtime…", 5, 260],
    ["version_manifest", `Resolving Minecraft ${versionId}`, 20, 300],
    ["assets", "Checking game assets", 35, 520],
    ["libraries", "Checking libraries", 50, 420],
    ["client_jar", "Checking the game", 65, 260],
    ["breeze_mod", "Fetching the Breeze mod", 75, 380],
    ["natives", "Preparing natives", 82, 220],
    ["prewarm", "Warming up", 90, 200],
    ["launching", "Starting Minecraft…", 95, 300],
  ];
  for (const [stage, message, progress, ms] of stages) {
    await emit("breeze://launch-stage", { stage, message, progress });
    await wait(ms * k);
  }
  await emit("breeze://launch-complete", { status: "starting", versionId, gameDirectory: "", message: "Starting Minecraft…" });
  await wait(1400 * k);
  if (params.get("launch") === "fail") {
    await emit("breeze://launch-complete", { status: "crashed", versionId, gameDirectory: "", message: "Minecraft crashed", detail: "Preview: a crash, as the Rust side reports one", reportPath: null });
    return;
  }
  await emit("breeze://launch-complete", { status: "running", versionId, gameDirectory: "", pid: 4242 });
  const exitAfter = Number(params.get("exitAfter") || 0);
  if (exitAfter) { await wait(exitAfter); await emit("breeze://launch-complete", { status: "exited", versionId, gameDirectory: "" }); }
}

const signedOut = params.get("signedout");
let signedIn = !signedOut;

const HANDLERS: Record<string, (args: any) => unknown> = {
  restore_saved_session: () => { if (!signedIn) throw new Error("No saved session"); return ACCOUNT; },
  list_saved_accounts: () => (signedOut === "1" && !signedIn ? [] : [{ uuid: ACCOUNT.uuid, username: ACCOUNT.username, active: signedIn, canResume: signedIn }]),
  begin_microsoft_auth: async () => { await wait(1500); signedIn = true; return ACCOUNT; },
  get_launcher_settings: () => settings,
  update_launcher_settings: (a) => (settings = { ...settings, ...(a?.settings || {}) }),
  get_platform: () => "windows",
  get_system_memory_info: () => ({ totalRamMb: 16384, safeMaxRamMb: 12288, recommendedRamMb: 6144 }),
  get_java_runtime_status: () => ({ installed: true, javaPath: "runtime/java-runtime-delta", source: "managed", major: 21 }),
  ensure_java_runtime: () => ({ installed: true, javaPath: "runtime/java-runtime-delta", source: "managed", major: 21 }),
  get_version_compatibility: (a) => ({
    versionId: a?.versionId, overall: "supported", summary: "Breeze is ready for this version",
    steps: [
      { key: "java", label: "Java", status: "ok", detail: "Java 21, from Mojang" },
      { key: "fabric", label: "Fabric", status: "ok", detail: "Loader 0.19.5" },
      { key: "breeze", label: "Breeze", status: "ok", detail: "Breeze 2.9.3" },
    ],
    javaMajor: 21, fabricLoader: "0.19.5", breezeModVersion: "2.9.3",
  }),
  list_installed_mods: () => MODS,
  set_mod_enabled: async (a) => {
    await wait(160);
    MODS = MODS.map((m) => (m.projectId === a?.projectId ? { ...m, enabled: Boolean(a?.enabled) } : m));
    return MODS.find((m) => m.projectId === a?.projectId);
  },
  remove_installed_mod: async (a) => { await wait(200); MODS = MODS.filter((m) => m.projectId !== a?.projectId); return null; },
  list_installed_packs: () => [],
  list_pack_library: () => [],
  load_custom_background: () => null,
  get_recordings_dir: () => ({ path: "~/.breezeclient/instances/1.21.4/recordings", files: [] }),
  detect_importable_clients: () => ({ clients: [] }),
  prepare_launch: () => ({}),
  prewarm_version: (a) => ({ versionId: a?.versionId, status: "ready", assetIndexName: "19", libraryCount: 0, cachedModCount: 0, gameDirectory: "" }),
  launch_minecraft: (a) => { replayLaunch(a?.request?.versionId || a?.versionId || String(settings.selectedVersion)); return null; },
  // Counted for the tests: window.__previewMinimized.
  "plugin:window|minimize": () => { const w = window as unknown as { __previewMinimized?: number }; w.__previewMinimized = (w.__previewMinimized || 0) + 1; return null; },
};

mockWindows("main");
mockIPC((cmd, args) => {
  const h = (folderMode && FOLDER_HANDLERS[cmd]) || HANDLERS[cmd];
  if (h) return h(args);
  return null;
}, { shouldMockEvents: true });

if (params.get("fresh") === "1") { try { localStorage.clear(); } catch { /* preview */ } }
// No scene list means no startup scene: SplashScene dissolves at once.
if (params.get("nosplash") === "1") {
  const realFetch = window.fetch.bind(window);
  window.fetch = (input, init) => (String(input instanceof Request ? input.url : input).endsWith("/splash/scenes.json")
    ? Promise.reject(new Error("preview: no startup scene"))
    : realFetch(input, init));
}

// Every screenshot from this harness says so.
const tag = document.createElement("div");
tag.textContent = "Preview data";
tag.setAttribute("aria-hidden", "true");
tag.style.cssText = "position:fixed;right:10px;bottom:10px;z-index:99999;font:500 11px 'JetBrains Mono',ui-monospace,monospace;color:#04070F;background:#E9C46A;padding:3px 7px;border-radius:4px;pointer-events:none";
document.addEventListener("DOMContentLoaded", () => document.body.appendChild(tag));
if (document.readyState !== "loading") document.body.appendChild(tag);

await import("../main.tsx");
