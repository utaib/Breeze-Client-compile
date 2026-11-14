/**
 * What can run the game (launcher 1.2.0): Fabric, which Breeze's own mod
 * needs, and Quilt, Forge, NeoForge or the game alone.
 *
 * Each loader but Fabric has its own instance folder, `<version>-<loader>`,
 * so its mods never sit beside Fabric's; Fabric keeps the bare version, as
 * before loaders existed. The same rule is in src-tauri/src/loaders.rs
 * (`instance_id`); the two are kept in step by their tests.
 */
export const LOADERS = [
  { id: "fabric", name: "Fabric" },
  { id: "quilt", name: "Quilt" },
  { id: "forge", name: "Forge" },
  { id: "neoforge", name: "NeoForge" },
  { id: "vanilla", name: "Vanilla" },
];

export const DEFAULT_LOADER = "fabric";

export const isLoader = (id) => LOADERS.some((l) => l.id === id);

export const loaderName = (id) => LOADERS.find((l) => l.id === id)?.name || "Fabric";

/** The instance a version has with a loader. */
export const instanceIdFor = (version, loader) => {
  if (!version) return "";
  return !loader || loader === DEFAULT_LOADER || !isLoader(loader) ? version : `${version}-${loader}`;
};

/** Whether mod jars mean anything to it. */
export const loaderHasMods = (id) => id !== "vanilla";

/**
 * Fabric: what the launcher's Fabric tools (packs, imports, performance sets,
 * the compatibility check) are built around. Breeze's own mod also runs on
 * some Forge and NeoForge versions; see breezeBuildFor.
 */
export const loaderRunsBreeze = (id) => !id || id === DEFAULT_LOADER;

/**
 * Whether the API serves Breeze for this version and loader. `available` is
 * its list (/versions/mod/list): Fabric jars are named after the version,
 * Forge and NeoForge jars after the instance (1.20.1-forge).
 */
export const breezeBuildFor = (loader, version, available) => {
  if (!version || !Array.isArray(available)) return false;
  if (loaderRunsBreeze(loader)) return available.includes(version);
  if (loader === "forge" || loader === "neoforge") return available.includes(instanceIdFor(version, loader));
  return false;
};

/** The loaders whose mods it loads, as Modrinth names them. Quilt also loads Fabric mods. */
export const modLoadersFor = (id) => (id === "quilt" ? ["quilt", "fabric"] : loaderHasMods(id) ? [id || DEFAULT_LOADER] : []);
