export type MinecraftVersionType =
  | "release"
  | "snapshot"
  | "old_beta"
  | "old_alpha";

export type MinecraftVersionOption = {
  id: string;
  type: MinecraftVersionType;
  releaseTime: string;
  fabricSupported: boolean;
};

export type FabricLoaderResolution = {
  gameVersion: string;
  loaderVersion: string | null;
  supported: boolean;
};

type MojangVersionManifest = {
  latest: {
    release: string;
    snapshot: string;
  };
  versions: Array<{
    id: string;
    type: MinecraftVersionType;
    releaseTime: string;
  }>;
};

type FabricGameVersion = {
  version: string;
  stable: boolean;
};

type FabricLoaderEntry = {
  loader: {
    version: string;
    stable: boolean;
  };
};

const MOJANG_VERSION_MANIFEST_URL =
  "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
const FABRIC_GAME_VERSIONS_URL = "https://meta.fabricmc.net/v2/versions/game";
const FABRIC_LOADER_BASE_URL = "https://meta.fabricmc.net/v2/versions/loader";

export async function loadMinecraftVersions(): Promise<MinecraftVersionOption[]> {
  const [mojangManifest, fabricGameVersions] = await Promise.all([
    fetchJson<MojangVersionManifest>(MOJANG_VERSION_MANIFEST_URL),
    fetchJson<FabricGameVersion[]>(FABRIC_GAME_VERSIONS_URL),
  ]);

  const fabricSupportedVersions = new Set(
    fabricGameVersions.map((version) => version.version),
  );

  return mojangManifest.versions.map((version) => ({
    id: version.id,
    type: version.type,
    releaseTime: version.releaseTime,
    fabricSupported: fabricSupportedVersions.has(version.id),
  }));
}

export async function resolveFabricLoader(
  gameVersion: string,
): Promise<FabricLoaderResolution> {
  const loaders = await fetchJson<FabricLoaderEntry[]>(
    `${FABRIC_LOADER_BASE_URL}/${encodeURIComponent(gameVersion)}`,
  );
  const loader = loaders.find((entry) => entry.loader.stable) ?? loaders[0];

  return {
    gameVersion,
    loaderVersion: loader?.loader.version ?? null,
    supported: Boolean(loader),
  };
}

async function fetchJson<T>(url: string): Promise<T> {
  const response = await fetch(url);

  if (!response.ok) {
    throw new Error(`Request failed: ${response.status} ${url}`);
  }

  return (await response.json()) as T;
}
