export type ModrinthProject = {
  id: string;
  slug: string;
  title: string;
  description: string;
  iconUrl: string | null;
  latestVersions: string[];
  categories: string[];
  downloads: number;
};

export type ModrinthSearchResponse = {
  hits: ModrinthProject[];
  offset: number;
  limit: number;
  totalHits: number;
};

type ModrinthApiSearchResponse = {
  hits: Array<{
    project_id: string;
    slug: string;
    title: string;
    description: string;
    icon_url: string | null;
    latest_version: string;
    categories: string[];
    downloads: number;
  }>;
  offset: number;
  limit: number;
  total_hits: number;
};

const MODRINTH_SEARCH_URL = "https://api.modrinth.com/v2/search";

/** Pack kinds the launcher manages, mapped to Modrinth's own project types. */
export const PACK_TYPES = {
  resourcepack: { projectType: "resourcepack", label: "Resource Packs" },
  shaderpack: { projectType: "shader", label: "Shaders" },
  datapack: { projectType: "datapack", label: "Datapacks" },
} as const;

export type PackType = keyof typeof PACK_TYPES;

/**
 * Packs have no mod loader, so the loader facet used for mods would return
 * nothing. Only project type and game version are constrained.
 */
export async function searchModrinthPacks(input: {
  query: string;
  gameVersion: string;
  packType: PackType;
  offset: number;
  limit?: number;
}): Promise<ModrinthSearchResponse> {
  const facets = [
    [`project_type:${PACK_TYPES[input.packType].projectType}`],
    [`versions:${input.gameVersion}`],
  ];
  return runSearch(facets, input.query, input.offset, input.limit);
}

export async function searchModrinthProjects(input: {
  query: string;
  gameVersion: string;
  loader: string;
  offset: number;
  limit?: number;
}): Promise<ModrinthSearchResponse> {
  const facets = [
    [`project_type:mod`],
    [`categories:${input.loader.toLowerCase()}`],
    [`versions:${input.gameVersion}`],
  ];
  return runSearch(facets, input.query, input.offset, input.limit);
}

async function runSearch(
  facets: string[][],
  query: string,
  offset: number,
  limit?: number,
): Promise<ModrinthSearchResponse> {
  const input = { query, offset, limit };
  const url = new URL(MODRINTH_SEARCH_URL);
  url.searchParams.set("query", input.query);
  url.searchParams.set("offset", String(input.offset));
  url.searchParams.set("limit", String(input.limit ?? 20));
  url.searchParams.set("index", "relevance");
  url.searchParams.set("facets", JSON.stringify(facets));

  const response = await fetch(url);

  if (!response.ok) {
    throw new Error(`Modrinth search failed with ${response.status}`);
  }

  const data = (await response.json()) as ModrinthApiSearchResponse;

  return {
    hits: data.hits.map((hit) => ({
      id: hit.project_id,
      slug: hit.slug,
      title: hit.title,
      description: hit.description,
      iconUrl: hit.icon_url,
      latestVersions: [hit.latest_version],
      categories: hit.categories,
      downloads: hit.downloads,
    })),
    offset: data.offset,
    limit: data.limit,
    totalHits: data.total_hits,
  };
}
