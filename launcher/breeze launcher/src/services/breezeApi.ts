import { fitCapeForUpload, fitCapeFramesForUpload } from "./imageFit";



export type BreezeRole = "user" | "creator" | "developer" | "admin" | "owner";

/**
 * Whether creator rules apply to a role: creators, and developers, who sit
 * above them (owner > developer > creator > user) and get the same tools under
 * the same limits. Mirrors isCreatorTier in the API, which is what enforces it.
 */
export function isCreatorTier(role: string | null | undefined): boolean {
  return role === "creator" || role === "developer";
}

export type BreezeUserProfile = {
  uuid: string;
  username: string;
  role: BreezeRole;
  
  creator_share_percent: number | null;
  
  capeUrl?: string | null;
  
  equippedCapeId?: string | null;
  email?: string | null;
  paypalEmail?: string | null;
  avatarUrl?: string | null;
};

export type BreezeAuthSession = {
  token: string;
  user: BreezeUserProfile;
};

export type BreezeSystemVersion = {
  
  latestVersion: string;
  downloadUrl: string;
  mandatory: boolean;
  changelog?: string;
  apiBaseUrl?: string;
  manifestSource?: string;
  
  launcher?: {
    latestVersion: string;
    version?: string;
    downloadUrl: string;
    mandatory: boolean;
    changelog?: string;
    notesUrl?: string | null;
    fileName?: string | null;
    sha256?: string | null;
    channel?: string;
    releasedAt?: string | null;
  };
  mod?: {
    latestVersion: string;
    version?: string;
    downloadUrl: string;
    fileName?: string | null;
    sha256?: string | null;
    channel?: string;
    releasedAt?: string | null;
  };
  latestLauncher?: BreezeSystemVersion["launcher"];
  latestMod?: BreezeSystemVersion["mod"];
  generatedAt?: string;
};


export type BreezeCape = {
  id: string;
  name: string;
  description?: string | null;
  price_usd: number;
  rarity?: "free" | "premium" | "event" | "limited";
  image_url: string;
  is_limited: boolean;
  
  is_animated?: boolean;
  
  animation_fps?: number | null;
  
  animation_frames?: string[];
  created_at?: string;
  creator_id?: string | null;
  is_public?: boolean;
};


export type BreezeUserCape = {
  cape_id: string;
  equipped: boolean;
  acquired_at?: string;
  cape: Pick<BreezeCape, "id" | "name" | "image_url" | "rarity" | "price_usd">;
};

export type BreezeEquipResult = { cape_id: string | null };

export type BreezeCapeUploadResponse = {
  
  cape_url?: string;
  cape?: BreezeCape;
};


export type CapeUploadInput = {
  token: string;
  file: File | Blob;
  fileName?: string;
  name: string;
  
  price_usd: number;
  rarity?: "free" | "premium" | "event" | "limited";
  description?: string;
  isPublic?: boolean;
  
  animationFps?: number | null;
};


export type CreatorStats = {
  capes_created: number;
  total_sales: number;
  total_gross_usd: number;
  total_net_usd: number;
  creator_earnings_usd: number;
  creator_share_percent: number;
};

export type BreezeNotification = {
  id: string;
  type: string;
  title: string;
  body?: string | null;
  data?: Record<string, unknown> | null;
  read_at?: string | null;
  created_at?: string;
};

export type BreezeFriendship = {
  id: string;
  requester_uuid: string;
  addressee_uuid: string;
  status: "pending" | "accepted" | "blocked" | string;
  /** The other player's uuid, always present even when their profile is gone. */
  uuid?: string;
  /** Unread messages from this friend. */
  unread?: number;
  user?: BreezeUserProfile | null;
  created_at?: string;
  accepted_at?: string | null;
};

export type BreezeMessage = {
  id: string;
  sender_uuid: string;
  recipient_uuid: string;
  body?: string | null;
  attachment?: Record<string, unknown> | null;
  created_at?: string;
  /** When the recipient opened the conversation. Null until they do. */
  read_at?: string | null;
};


export type CreateOrderResponse =
  | {
      granted: true;
      message: string;
    }
  | {
      granted?: false;
      order_id: string;
      approve_url: string;
      amount_usd: number;
    };


const DEFAULT_API_BASE_URL = "https://api.breezeclient.net";
const configuredApiBaseUrl = import.meta.env.VITE_BREEZE_API_URL?.replace(/\/$/, "");
const API_BASE_URLS = Array.from(
  new Set([configuredApiBaseUrl, DEFAULT_API_BASE_URL].filter(Boolean))
) as string[];

export function getApiBaseUrl(): string { return API_BASE_URLS[0]; }
export function getApiFallbackUrls(): string[] { return API_BASE_URLS; }


export async function getSystemVersion(): Promise<BreezeSystemVersion> {
  return apiRequest<BreezeSystemVersion>("/system/version", undefined, { raw: true });
}

export async function getVersionManifest(): Promise<BreezeSystemVersion> {
  return apiRequest<BreezeSystemVersion>("/versions", undefined, { raw: true });
}

export interface AudiusTrack { id: string; title: string; artist: string; artwork: string | null; duration: number; streamUrl: string; }

/** Breeze FM search via Audius (free, open, no account or premium needed). */
export async function searchAudius(query: string): Promise<AudiusTrack[]> {
  const { tracks } = await apiRequest<{ tracks: AudiusTrack[] }>(`/audius/search?q=${encodeURIComponent(query)}`);
  return tracks ?? [];
}
export async function trendingAudius(): Promise<AudiusTrack[]> {
  const { tracks } = await apiRequest<{ tracks: AudiusTrack[] }>("/audius/trending");
  return tracks ?? [];
}

export interface BreezeUpdateCheck {
  current: string;
  role: string;
  channels: string[];
  stable: { channel: string; version: string; url: string | null; changelog: string | null; mandatory: boolean };
  update: { channel: string; version: string; url: string | null; changelog: string | null; mandatory: boolean } | null;
  upToDate: boolean;
}

/** Role-aware update check. With a token, creators/owners also see testing
 *  channels; normal users only ever see the Stable channel. */
export async function checkForUpdate(
  current: string,
  token?: string | null,
  platform?: string | null,
): Promise<BreezeUpdateCheck> {
  const headers: Record<string, string> = {};
  if (token) headers.Authorization = `Bearer ${token}`;
  // Without the platform the API assumes Windows, which would offer a macOS
  // or Linux user a .exe they cannot run.
  const params = new URLSearchParams({ current });
  if (platform) params.set("platform", platform);
  return apiRequest<BreezeUpdateCheck>(`/versions/check?${params.toString()}`, { headers }, { raw: true });
}

/** Which Minecraft versions currently have a Breeze mod jar published on the API
 *  (breeze-api/versions/mods/<version>.jar). Used to show accurate availability
 *  instead of silently trying a download that 404s. */
export async function listAvailableModVersions(): Promise<string[]> {
  const { versions } = await apiRequest<{ versions: string[] }>("/versions/mod/list", undefined, { raw: true });
  return versions;
}

export type BreezeRadioTrack = {
  id: string;
  title: string;
  artist: string;
  album?: string | null;
  mood?: string | null;
  length?: string | null;
  artworkUrl?: string | null;
  previewUrl?: string | null;
  provider?: string;
};

export async function searchRadioTracks(query: string): Promise<{ tracks: BreezeRadioTrack[]; provider: string; configured: boolean }> {
  const params = new URLSearchParams();
  if (query.trim()) params.set("q", query.trim());
  return apiRequest(`/radio/search?${params.toString()}`, undefined, { raw: true });
}


export async function loginWithMinecraftToken(mcAccessToken: string): Promise<BreezeAuthSession> {
  const body = await apiRequest<{ token: string; user: BreezeUserProfile }>("/auth/login", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ mcAccessToken }),
  });
  return { token: body.token, user: body.user };
}


export async function getMe(token: string): Promise<BreezeUserProfile> {
  const { user } = await apiRequest<{ user: BreezeUserProfile }>("/users/me", {
    headers: { Authorization: `Bearer ${token}` },
  });
  return user;
}


export async function getUserProfile(uuid: string, token?: string | null): Promise<BreezeUserProfile> {
  const headers: Record<string, string> = {};
  if (token) headers["Authorization"] = `Bearer ${token}`;
  const { user } = await apiRequest<{ user: BreezeUserProfile }>(
    `/users/${encodeURIComponent(uuid)}`,
    { headers }
  );
  return user;
}


export async function syncUserData(uuid: string, token: string): Promise<{ profile: BreezeUserProfile }> {
  const profile = await getMe(token).catch(() => null);
  if (profile) return { profile };
  
  const fallback = await getUserProfile(uuid, token);
  return { profile: fallback };
}


export async function getAllCapes(): Promise<BreezeCape[]> {
  const { capes } = await apiRequest<{ capes: BreezeCape[] }>("/capes");
  return capes ?? [];
}


export type BreezeTag = {
  id: string;
  slug: string;
  name: string;
  color: string;
  icon: string | null;
  priority: number;
  /** How the user came to hold it: their role, an explicit grant, or the default. */
  source: "role" | "granted" | "default";
};

export type BreezeTagState = {
  tags: BreezeTag[];
  equippedTagId: string | null;
  /** True when no explicit choice has been made and the priority default applies. */
  usingDefault: boolean;
  /**
   * The official role badge. Separate from the equipped tag on purpose: it is
   * derived from the account's role and no tag choice can hide or fake it.
   */
  badge: { slug: string; name: string; color: string } | null;
  /** True for creators: the one role whose tag colour can be chosen. */
  canChooseColor: boolean;
  /** The creator's chosen colour, or null while the role colour applies. */
  tagColor: string | null;
  /** The colours on offer, role colour first. Empty unless canChooseColor. */
  colorOptions: BreezeTagColorOption[];
};

export type BreezeTagColorOption = { id: string; name: string; color: string | null };

/** Every tag the signed-in user owns, and which one currently displays. */
export async function getMyTags(token: string): Promise<BreezeTagState> {
  return apiRequest<BreezeTagState>("/tags/mine", { headers: { Authorization: `Bearer ${token}` } });
}

/** Choose which owned tag to display. Pass null to return to the default. */
export async function equipTag(token: string, tagId: string | null): Promise<{ equippedTagId: string | null }> {
  return apiRequest("/tags/equip", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ tag_id: tagId }),
  });
}

/**
 * Choose the colour of the signed-in creator's tag, from the options
 * getMyTags returned. Pass null to go back to the role colour.
 *
 * Only the launcher can do this. The mod is uuid-addressed and holds no token,
 * so a route it could call would let anyone restyle anyone else's tag by uuid.
 */
export async function setTagColor(token: string, color: string | null): Promise<{ tagColor: string | null }> {
  return apiRequest("/tags/color", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ color }),
  });
}

// ── Spotify ───────────────────────────────────────────────────────────────

export type SpotifyStatus = {
  /** False when the server has no Spotify credentials; the UI hides the feature. */
  configured: boolean;
  connected: boolean;
  /** Spotify only allows playback control on Premium. */
  premium: boolean;
  displayName?: string | null;
  /** The stored token stopped working; offer to reconnect. */
  needsReconnect?: boolean;
};

export type SpotifyNowPlaying = {
  playing: boolean;
  progressMs?: number;
  device?: { name: string; type: string } | null;
  track: {
    id: string;
    title: string;
    artist: string;
    album?: string | null;
    artwork?: string | null;
    durationMs: number;
    url?: string | null;
  } | null;
};

const authed = (token: string) => ({ headers: { Authorization: `Bearer ${token}` } });

export async function getSpotifyStatus(token: string): Promise<SpotifyStatus> {
  return apiRequest<SpotifyStatus>("/spotify/status", authed(token));
}

/** The Spotify consent URL. Opened in the user's browser, not in the launcher. */
export async function getSpotifyLoginUrl(token: string): Promise<{ url: string }> {
  return apiRequest<{ url: string }>("/spotify/login", authed(token));
}

export async function disconnectSpotify(token: string): Promise<void> {
  await apiRequest("/spotify/disconnect", { method: "POST", ...authed(token) });
}

export async function getSpotifyNowPlaying(token: string): Promise<SpotifyNowPlaying> {
  return apiRequest<SpotifyNowPlaying>("/spotify/now-playing", authed(token));
}

/**
 * Control playback. Throws on failure; a Premium restriction arrives as an
 * Error carrying reason === "PREMIUM_REQUIRED" so callers branch on that
 * rather than on the wording of the message.
 */
export async function spotifyControl(
  token: string,
  action: "play" | "pause" | "next" | "previous",
): Promise<void> {
  await apiRequest(`/spotify/${action}`, { method: "POST", ...authed(token) });
}

export type BreezeAdminTag = {
  id: string;
  slug: string;
  name: string;
  color: string;
  priority_weight: number;
  icon_asset: string | null;
  auto_role: string | null;
  /** How many users hold this via an explicit grant (role-derived holders are not counted). */
  granted_to: number;
};

/** Admin: every tag definition, with grant counts. */
export async function adminListTags(token: string): Promise<{ tags: BreezeAdminTag[] }> {
  return apiRequest("/admin/tags", { headers: { Authorization: `Bearer ${token}` } });
}

/** Admin: create or update a tag. Matching is by slug, so reusing one edits it. */
export async function adminSaveTag(
  token: string,
  tag: { slug: string; name: string; color?: string; priority_weight?: number; icon_asset?: string | null; auto_role?: string | null },
): Promise<{ tag: BreezeAdminTag }> {
  return apiRequest("/admin/tags", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(tag),
  });
}

/** Admin: grant or revoke a tag for one user, addressed by username or uuid. */
export async function adminAssignTag(
  token: string,
  opts: { tag_id: string; username: string; revoke?: boolean },
): Promise<{ ok: true }> {
  return apiRequest("/admin/tags/assign", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(opts),
  });
}

/**
 * Admin: upload a tag icon. Multipart, so no Content-Type header is set here;
 * the browser has to add its own multipart boundary.
 */
export async function adminUploadTagIcon(
  token: string,
  tagId: string,
  file: File,
): Promise<{ icon_asset: string }> {
  const body = new FormData();
  body.append("tag_id", tagId);
  body.append("icon", file);
  return apiRequest("/admin/tags/icon", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
    body,
  });
}

/** Admin: clear a tag icon, returning it to the built-in wind charge. */
export async function adminClearTagIcon(
  token: string,
  tagId: string,
): Promise<{ icon_asset: null }> {
  return apiRequest(`/admin/tags/icon/${encodeURIComponent(tagId)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
}

export async function getUserCapes(token: string): Promise<BreezeUserCape[]> {
  const { capes } = await apiRequest<{ capes: BreezeUserCape[] }>("/capes/owned", {
    headers: { Authorization: `Bearer ${token}` },
  });
  return capes ?? [];
}

export async function equipCape(input: { token: string; capeId: string }): Promise<BreezeEquipResult> {
  const body = await apiRequest<{ cape_id: string | null }>("/capes/equip", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${input.token}` },
    body: JSON.stringify({ cape_id: input.capeId }),
  });
  return { cape_id: body.cape_id };
}

export async function unequipCape(token: string): Promise<BreezeEquipResult> {
  const body = await apiRequest<{ cape_id: null }>("/capes/unequip", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({}),
  });
  return { cape_id: body.cape_id };
}


/**
 * Shrink before sending.
 *
 * The API sits behind nginx with a 1 MB body limit that cannot be raised from
 * the Pterodactyl panel, and an oversized upload surfaces as an unreadable
 * "failed to fetch" because nginx's 413 page carries no CORS headers. The
 * server downscales every cape to at most 2048x1024 anyway, so sending the full
 * source was always wasted bytes. See services/imageFit.ts.
 */
export async function uploadCape(input: {
  token: string;
  file: File | Blob;
  fileName?: string;
}): Promise<BreezeCapeUploadResponse> {
  const fitted = input.file instanceof File ? (await fitCapeForUpload(input.file)).file : input.file;
  const formData = new FormData();
  formData.append("cape", fitted, input.fileName ?? "cape.png");
  const body = await apiRequest<{ cape_url: string }>("/capes/upload-personal", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}` },
    body: formData,
  });
  return { cape_url: body.cape_url };
}


/** Same shrink-before-send reasoning as {@link uploadCape}. */
export async function uploadCapeAdmin(input: CapeUploadInput): Promise<BreezeCapeUploadResponse> {
  const fitted = input.file instanceof File ? (await fitCapeForUpload(input.file)).file : input.file;
  const formData = new FormData();
  formData.append("cape", fitted, input.fileName ?? "cape.png");
  formData.append("name", input.name);
  formData.append("price_usd", String(input.price_usd));
  formData.append("rarity", input.rarity ?? "premium");
  if (input.description) formData.append("description", input.description);
  formData.append("is_public", String(input.isPublic !== false));
  if (input.animationFps) formData.append("animation_fps", String(input.animationFps));
  const body = await apiRequest<{ cape: BreezeCape }>("/capes", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}` },
    body: formData,
  });
  return { cape: body.cape };
}


export async function uploadAnimatedCape(input: {
  token: string;
  frames: (File | Blob)[];
  name: string;
  price_usd: number;
  rarity?: "free" | "premium" | "event" | "limited";
  description?: string;
  isPublic?: boolean;
}): Promise<BreezeCapeUploadResponse> {
  if (!input.frames || input.frames.length < 2) {
    throw new Error("Animated capes require at least 2 frames");
  }
  const formData = new FormData();
  formData.append("is_animated", "true");
  formData.append("name", input.name);
  formData.append("price_usd", String(input.price_usd));
  formData.append("rarity", input.rarity ?? "premium");
  if (input.description) formData.append("description", input.description);
  formData.append("is_public", String(input.isPublic !== false));

  
  
  // Every frame travels in ONE request, so the budget is shared across them.
  // This is the upload most likely to exceed the server's 1 MB limit: a single
  // still cape is small, but twenty frames at 60 KB is 1.2 MB and nginx rejects
  // the whole thing with an error the browser cannot even read.
  const allFiles = input.frames.every((f): f is File => f instanceof File);
  const framesToSend: (File | Blob)[] = allFiles
    ? await fitCapeFramesForUpload(input.frames as File[])
    : input.frames;

  framesToSend.forEach((frame, idx) => {
    const name = frame instanceof File ? frame.name : `frame_${String(idx).padStart(3, "0")}.png`;
    formData.append("frames", frame, name);
  });

  const body = await apiRequest<{ cape: BreezeCape }>("/capes", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}` },
    body: formData,
  });
  return { cape: body.cape };
}


export async function getCreatorCapes(token: string): Promise<BreezeCape[]> {
  const { capes } = await apiRequest<{ capes: BreezeCape[] }>("/capes/creator", {
    headers: { Authorization: `Bearer ${token}` },
  });
  return capes ?? [];
}


export async function getCreatorStats(token: string): Promise<CreatorStats> {
  return apiRequest<CreatorStats>("/creator/stats", {
    headers: { Authorization: `Bearer ${token}` },
  });
}


/** Creates a purchase order for a cape or a cosmetic, both flow through the
 *  same backend pipeline (owner bypass, promo codes, free grants, PayPal). */
export async function createPurchaseOrder(input: {
  token: string;
  capeId?: string;
  cosmeticId?: string;
  promoCode?: string | null;
}): Promise<CreateOrderResponse> {
  const payload: Record<string, unknown> = {};
  if (input.capeId) payload.cape_id = input.capeId;
  if (input.cosmeticId) payload.cosmetic_id = input.cosmeticId;
  const promo = (input.promoCode ?? "").trim();
  if (promo) {
    payload.promo_code = promo;
  }
  return apiRequest<CreateOrderResponse>("/purchases/create-order", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${input.token}` },
    body: JSON.stringify(payload),
  });
}


export async function pollForCapeOwnership(
  token: string,
  capeId: string,
  options: { maxAttempts?: number; intervalMs?: number } = {}
): Promise<{ owned: boolean; capes: BreezeUserCape[] }> {
  const maxAttempts = options.maxAttempts ?? 40; 
  const intervalMs  = options.intervalMs  ?? 3000;
  let lastCapes: BreezeUserCape[] = [];
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    try {
      lastCapes = await getUserCapes(token);
      if (lastCapes.some((c) => c.cape_id === capeId)) {
        return { owned: true, capes: lastCapes };
      }
    } catch {
      
    }
    await sleep(intervalMs);
  }
  return { owned: false, capes: lastCapes };
}


export type CreatePersonalCapeOrderResponse =
  | {
      granted: true;
      cape_url: string;
      message: string;
    }
  | {
      granted?: false;
      order_id: string;
      approve_url: string;
      amount_usd: number;
    };


export async function createPersonalCapeOrder(input: {
  token: string;
  file: File | Blob;
  fileName?: string;
  promoCode?: string | null;
}): Promise<CreatePersonalCapeOrderResponse> {
  const formData = new FormData();
  formData.append("cape", input.file, input.fileName ?? "cape.png");
  const promo = (input.promoCode ?? "").trim();
  if (promo) {
    formData.append("promo_code", promo);
  }
  return apiRequest<CreatePersonalCapeOrderResponse>("/capes/personal-order", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}` },
    body: formData,
  });
}


export async function pollForPersonalCapeApplied(
  token: string,
  previousCapeUrl: string | null,
  options: { maxAttempts?: number; intervalMs?: number } = {}
): Promise<{ applied: boolean; capeUrl: string | null }> {
  const maxAttempts = options.maxAttempts ?? 40;
  const intervalMs  = options.intervalMs  ?? 3000;
  let lastUrl: string | null = previousCapeUrl;
  for (let attempt = 0; attempt < maxAttempts; attempt++) {
    try {
      const me = await getMe(token);
      const cur = me.capeUrl ?? null;
      if (cur && cur !== previousCapeUrl) {
        return { applied: true, capeUrl: cur };
      }
      lastUrl = cur;
    } catch {
      
    }
    await sleep(intervalMs);
  }
  return { applied: false, capeUrl: lastUrl };
}


export const COSMETIC_SLOTS = [
  "hat", "wings", "pet", "cape", "shield", "aura", "back", "trail",
] as const;
export type BreezeCosmeticSlot = typeof COSMETIC_SLOTS[number];

export type BreezeCosmetic = {
  id: string;
  slot: BreezeCosmeticSlot;
  name: string;
  description?: string | null;
  rarity?: string | null;
  price_usd?: number | null;
  model_url?: string | null;
  thumbnail_url?: string | null;
  idle_animation?: string | null;
  random_animations?: string[] | null;
  animation_chance?: number | null;
  creator_id?: string | null;
  is_public?: boolean;
  metadata?: Record<string, unknown> | null;
  created_at?: string;
};

export type BreezeOwnedCosmetic = {
  cosmetic_id: string;
  acquired_at: string;
  cosmetic: BreezeCosmetic | null;
};


export async function listCosmetics(slot?: BreezeCosmeticSlot): Promise<BreezeCosmetic[]> {
  const path = slot ? `/cosmetics?slot=${encodeURIComponent(slot)}` : "/cosmetics";
  const { cosmetics } = await apiRequest<{ cosmetics: BreezeCosmetic[] }>(path);
  return cosmetics ?? [];
}


export async function getOwnedCosmetics(token: string): Promise<{
  owned: BreezeOwnedCosmetic[];
  equipped: Partial<Record<BreezeCosmeticSlot, string>>;
  /** The player's own placement per cosmetic id, where they changed it. */
  placements?: Record<string, CosmeticTransform>;
}> {
  return apiRequest<{
    owned: BreezeOwnedCosmetic[];
    equipped: Partial<Record<BreezeCosmeticSlot, string>>;
    placements?: Record<string, CosmeticTransform>;
  }>("/cosmetics/owned", { headers: { Authorization: `Bearer ${token}` } });
}

/**
 * Save where this player wears a cosmetic they own. Pass null to go back to
 * the creator's placement.
 */
export async function setCosmeticPlacement(
  token: string,
  cosmeticId: string,
  transform: CosmeticTransform | null,
): Promise<{ placement: CosmeticTransform | null }> {
  return apiRequest(`/cosmetics/${encodeURIComponent(cosmeticId)}/placement`, {
    method: "PUT",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ transform }),
  });
}


export async function getEquippedCosmetics(uuid: string): Promise<BreezeCosmetic[]> {
  const { cosmetics } = await apiRequest<{ cosmetics: BreezeCosmetic[] }>(
    `/cosmetics/equipped/${encodeURIComponent(uuid)}`
  );
  return cosmetics ?? [];
}


export async function equipCosmetic(token: string, cosmeticId: string): Promise<void> {
  await apiRequest("/cosmetics/equip", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ cosmetic_id: cosmeticId }),
  });
}


export async function unequipCosmetic(token: string, slot: BreezeCosmeticSlot): Promise<void> {
  await apiRequest("/cosmetics/unequip", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ slot }),
  });
}


/** Where a cosmetic sits on the player. See docs/COSMETICS.md. */
export type CosmeticAttachment =
  | "HEAD" | "SHOULDER" | "BACK" | "HAND" | "FEET" | "SIDE" | "FLYING_PET" | "TRAIL";
export type CosmeticAnimationRole = "idle" | "fly" | "walk" | "sit";
/** Offset in Minecraft pixels, rotation in degrees, scale on the fitted size. */
export type CosmeticTransform = { offset: number[]; rotation: number[]; scale: number };

/** What the API measured about a cosmetic model, plus the creator's choices. */
export type CosmeticAssetMetadata = {
  spec: number;
  attachment: CosmeticAttachment;
  transform: CosmeticTransform;
  animations: {
    clips: { name: string; duration: number }[];
    roles: Partial<Record<CosmeticAnimationRole, string>>;
    /** Clips no state claims; they play now and then for variety. */
    extras: string[];
  };
  bounds: { min: number[]; max: number[] };
  stats: {
    triangles: number;
    textures: { mime: string; width: number; height: number }[];
    materials: number;
    skinned: boolean;
    bytes: number;
    source: "glb" | "gltf" | "zip";
  };
};

export type CosmeticSpec = {
  spec: number;
  attachments: CosmeticAttachment[];
  slotAttachments: Record<string, CosmeticAttachment[]>;
  roles: CosmeticAnimationRole[];
  limits: { requestBytes: number; fileBytes: number; packageFiles: number; triangles: number; textures: number; textureEdge: number };
};

/** The cosmetic format as the API enforces it. */
export async function getCosmeticSpec(): Promise<CosmeticSpec> {
  return apiRequest<CosmeticSpec>("/cosmetics/spec");
}

/**
 * Split picked files into the model and the files it points at. A .zip or a
 * .glb stands alone; a .gltf brings its .bin and textures along.
 */
export function splitCosmeticFiles(files: File[]): { model: File | null; resources: File[]; error?: string } {
  const models = files.filter((f) => /\.(glb|gltf|zip)$/i.test(f.name));
  if (models.length === 0) return { model: null, resources: [], error: "Pick a .glb, a .gltf with its files, or a .zip." };
  if (models.length > 1) return { model: null, resources: [], error: "Pick one model at a time." };
  return { model: models[0], resources: files.filter((f) => f !== models[0]) };
}

function appendCosmeticFiles(form: FormData, model: File | Blob, resources: (File | Blob)[] = [], modelFileName?: string) {
  const modelName = modelFileName ?? (model instanceof File ? model.name : "model.glb");
  form.append("model", model, modelName);
  for (const file of resources) form.append("resources", file, file instanceof File ? file.name : "resource");
}

/**
 * Check a model without publishing it. Returns the finished GLB exactly as it
 * would be stored, so the preview shows the real textures and clips.
 */
export async function inspectCosmetic(input: {
  token: string;
  slot: BreezeCosmeticSlot;
  model: File | Blob;
  resources?: (File | Blob)[];
  attachment?: CosmeticAttachment;
}): Promise<{ metadata: CosmeticAssetMetadata; model: Blob }> {
  const form = new FormData();
  appendCosmeticFiles(form, input.model, input.resources);
  form.append("slot", input.slot);
  if (input.attachment) form.append("attachment", input.attachment);
  const body = await apiRequest<{ metadata: CosmeticAssetMetadata; model: string }>("/cosmetics/inspect", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}` },
    body: form,
  });
  // A plain indexed loop: TypedArray.from with a mapping function takes a slow
  // generic path and held the UI for seconds on a few-MB model.
  const text = atob(body.model);
  const bytes = new Uint8Array(text.length);
  for (let i = 0; i < text.length; i++) bytes[i] = text.charCodeAt(i);
  return { metadata: body.metadata, model: new Blob([bytes], { type: "model/gltf-binary" }) };
}

export async function uploadCosmetic(input: {
  token: string;
  name: string;
  slot: BreezeCosmeticSlot;
  model: File | Blob;
  modelFileName?: string;
  /** The .bin and textures a .gltf points at, when not packed in a .zip. */
  resources?: (File | Blob)[];
  attachment?: CosmeticAttachment;
  transform?: CosmeticTransform;
  /** Only needed to override what the API finds from clip names. */
  animationRoles?: Partial<Record<CosmeticAnimationRole, string>>;
  thumbnail?: File | Blob | null;
  description?: string;
  rarity?: string;
  price_usd?: number;
  isPublic?: boolean;
  idleAnimation?: string;
  randomAnimations?: string[];
  animationChance?: number;
  metadata?: Record<string, unknown>;
}): Promise<BreezeCosmetic> {
  const form = new FormData();
  appendCosmeticFiles(form, input.model, input.resources, input.modelFileName);
  if (input.attachment) form.append("attachment", input.attachment);
  if (input.transform) form.append("transform", JSON.stringify(input.transform));
  if (input.animationRoles) form.append("animation_roles", JSON.stringify(input.animationRoles));
  if (input.thumbnail) form.append("thumbnail", input.thumbnail, "thumbnail.png");
  form.append("name", input.name);
  form.append("slot", input.slot);
  if (input.description) form.append("description", input.description);
  if (input.rarity) form.append("rarity", input.rarity);
  if (input.price_usd != null) form.append("price_usd", String(input.price_usd));
  form.append("is_public", String(input.isPublic !== false));
  if (input.idleAnimation) form.append("idle_animation", input.idleAnimation);
  if (input.animationChance != null) form.append("animation_chance", String(input.animationChance));
  if (input.randomAnimations?.length) {
    form.append("random_animations", JSON.stringify(input.randomAnimations));
  }
  if (input.metadata) form.append("metadata", JSON.stringify(input.metadata));

  const body = await apiRequest<{ cosmetic: BreezeCosmetic }>("/cosmetics", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}` },
    body: form,
  });
  return body.cosmetic;
}


export async function deleteCosmetic(
  token: string, id: string, opts: { hard?: boolean } = {}
): Promise<void> {
  const qs = opts.hard ? "?hard=true" : "";
  await apiRequest(`/cosmetics/${encodeURIComponent(id)}${qs}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
}

export async function listNotifications(token: string): Promise<BreezeNotification[]> {
  const { notifications } = await apiRequest<{ notifications: BreezeNotification[] }>("/notifications", {
    headers: { Authorization: `Bearer ${token}` },
  });
  return notifications ?? [];
}

export async function markAllNotificationsRead(token: string): Promise<void> {
  await apiRequest("/notifications/read-all", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
}

/** Dismiss one notification permanently. */
export async function dismissNotification(token: string, id: string): Promise<void> {
  await apiRequest(`/notifications/${encodeURIComponent(id)}`, {
    method: "DELETE",
    headers: { Authorization: `Bearer ${token}` },
  });
}

/**
 * Bulk dismissal. Pass explicit `ids` for a multi-select, or a `scope` of
 * "read" to tidy up, or "all" to clear the list entirely.
 */
export async function dismissNotifications(
  token: string,
  input: { ids?: string[]; scope?: "read" | "all" },
): Promise<void> {
  await apiRequest("/notifications/dismiss", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify(input),
  });
}

export async function searchSocialUsers(token: string, query: string): Promise<{ users: BreezeUserProfile[]; message?: string | null }> {
  return apiRequest<{ users: BreezeUserProfile[]; message?: string | null }>(
    `/social/search?q=${encodeURIComponent(query)}`,
    { headers: { Authorization: `Bearer ${token}` } }
  );
}

export async function listFriends(
  token: string,
): Promise<{ friends: BreezeFriendship[]; requests: BreezeFriendship[]; blocked?: BreezeFriendship[] }> {
  return apiRequest<{ friends: BreezeFriendship[]; requests: BreezeFriendship[]; blocked?: BreezeFriendship[] }>(
    "/social/friends",
    { headers: { Authorization: `Bearer ${token}` } },
  );
}

/**
 * Add someone. The uuid is sent whenever it is known, because a search result
 * already carries it and a name can be typed wrong, changed, or contain
 * characters the server would otherwise have to interpret.
 */
export async function sendFriendRequest(
  token: string,
  who: string | { username?: string; uuid?: string },
): Promise<{ request: BreezeFriendship; message?: string }> {
  const target = typeof who === "string" ? { username: who } : who;
  return apiRequest<{ request: BreezeFriendship; message?: string }>("/social/friend-requests", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ username: target.username, target_uuid: target.uuid }),
  });
}

/** Remove a friend, or block them so they cannot ask again. */
export async function removeFriend(token: string, uuid: string, options?: { block?: boolean }): Promise<void> {
  await apiRequest(`/social/friends/${encodeURIComponent(uuid)}/remove`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ block: options?.block === true }),
  });
}

export async function unblockFriend(token: string, uuid: string): Promise<void> {
  await apiRequest(`/social/friends/${encodeURIComponent(uuid)}/unblock`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
}

export async function acceptFriendRequest(token: string, id: string): Promise<BreezeFriendship> {
  const { friendship } = await apiRequest<{ friendship: BreezeFriendship }>(`/social/friend-requests/${encodeURIComponent(id)}/accept`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
  return friendship;
}

export async function declineFriendRequest(token: string, id: string): Promise<void> {
  await apiRequest(`/social/friend-requests/${encodeURIComponent(id)}/decline`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
}

export interface BreezePendingGift {
  id: string;
  sender_uuid: string;
  sender_username: string;
  cape_id: string | null;
  cosmetic_id: string | null;
  cape?: { id: string; name: string; image_url: string | null; is_animated?: boolean; animation_frames?: string[] | null; animation_fps?: number | null } | null;
  cosmetic?: { id: string; name: string; slot: string; model_url: string | null; thumbnail_url: string | null } | null;
}

/** Gifts received but not yet opened, drives the gift-open animation. */
export async function getPendingGifts(token: string): Promise<BreezePendingGift[]> {
  const { gifts } = await apiRequest<{ gifts: BreezePendingGift[] }>("/gifts/pending", {
    headers: { Authorization: `Bearer ${token}` },
  });
  return gifts ?? [];
}

export async function markGiftSeen(token: string, id: string): Promise<void> {
  await apiRequest(`/gifts/${encodeURIComponent(id)}/seen`, {
    method: "POST",
    headers: { Authorization: `Bearer ${token}` },
  });
}

/**
 * A conversation. `since` asks only for what arrived after that timestamp,
 * which is how an open chat stays current without refetching it every few
 * seconds. Opening a conversation marks the other side's messages read.
 */
export async function listMessages(token: string, friendUuid: string, since?: string | null): Promise<BreezeMessage[]> {
  const query = since ? `?since=${encodeURIComponent(since)}` : "";
  const { messages } = await apiRequest<{ messages: BreezeMessage[] }>(
    `/social/messages/${encodeURIComponent(friendUuid)}${query}`,
    { headers: { Authorization: `Bearer ${token}` } },
  );
  return messages ?? [];
}

export async function sendMessage(token: string, recipientUuid: string, body: string): Promise<BreezeMessage> {
  const { message } = await apiRequest<{ message: BreezeMessage }>("/social/messages", {
    method: "POST",
    headers: { Authorization: `Bearer ${token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ recipient_uuid: recipientUuid, body }),
  });
  return message;
}

/** Heartbeat: stamps the signed-in user's last_seen so friends can see them as online. */
export async function updatePresence(token: string): Promise<{ lastSeen: string }> {
  return apiRequest<{ lastSeen: string }>("/social/presence", {
    method: "PATCH",
    headers: { Authorization: `Bearer ${token}` },
  });
}

/** A friend counts as online if their lastSeen timestamp is within this many minutes. */
export const PRESENCE_ONLINE_WINDOW_MINUTES = 5;

export function isFriendOnline(lastSeen?: string | null): boolean {
  if (!lastSeen) return false;
  const diffMs = Date.now() - new Date(lastSeen).getTime();
  return diffMs >= 0 && diffMs <= PRESENCE_ONLINE_WINDOW_MINUTES * 60 * 1000;
}

export async function grantGift(input: { token: string; username?: string; targetUuid?: string; capeId?: string; cosmeticId?: string }): Promise<void> {
  await apiRequest("/gifts/grant", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ username: input.username, target_uuid: input.targetUuid, cape_id: input.capeId, cosmetic_id: input.cosmeticId }),
  });
}

/**
 * Send an item you own. This is a transfer: it leaves your inventory. To give
 * someone something you do not own, buy it for them with purchaseStoreItem and
 * a giftTo, which charges you and pays the creator like any other sale.
 */
export async function sendGift(input: {
  token: string;
  username?: string;
  targetUuid?: string;
  capeId?: string;
  cosmeticId?: string;
}): Promise<{ recipient: BreezeUserProfile; item?: { id: string; name: string; type: string } }> {
  return apiRequest("/gifts/send", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}`, "Content-Type": "application/json" },
    body: JSON.stringify({ username: input.username, target_uuid: input.targetUuid, cape_id: input.capeId, cosmetic_id: input.cosmeticId }),
  });
}

/** Global feature toggles set by the owner. Every signed-in user polls these so
 *  a globally disabled feature (mods/store/hosting/chat/gifting/radio/rewards)
 *  shows its maintenance state instead of silently misbehaving. */
export async function getFeatureFlags(token: string): Promise<Record<string, boolean>> {
  const { flags } = await apiRequest<{ flags: Record<string, boolean> }>("/feature-flags", {
    headers: { Authorization: `Bearer ${token}` },
  });
  return flags ?? {};
}


/* â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•
   Economy, Wind Charges (user currency) & Breeze Rods (creator
   earnings). $1 = 64 WC; 1 rod = 64 WC. Items stay priced in USD
   server-side and convert at 64 WC per dollar.
   â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â•â• */

export const WC_PER_USD = 64;
export const WC_PER_ROD = 64;

/**
 * How many marketplace items a creator may publish. Must stay in step with the
 * API's CREATOR_CAPE_LIMIT, the server is authoritative and will reject an
 * upload past it, this constant only drives the UI's slot counters so the
 * launcher never disables the upload button while the server would accept it.
 */
export const CREATOR_UPLOAD_LIMIT = 25;

/** Wind Charge price for a USD item price. */
export function priceInWc(priceUsd: number | null | undefined): number {
  return Math.max(0, Math.round(Number(priceUsd || 0) * WC_PER_USD));
}

export type WcPack = { id: string; usd: number; wc: number; bonus: number; total_wc: number; popular?: boolean };
export type WalletTransaction = { type: string; amount_wc: number; balance_after: number | null; note: string | null; created_at: string };

/** One ad box as the admin panel set it (src/adBoxes.js in the API). */
export type AdBoxConfig = { id: string; mode: "image" | "adsense"; image_url: string | null; link: string | null; alt: string; adsense_slot: string | null };

export async function getAdBoxes(): Promise<{ boxes: Record<string, AdBoxConfig> }> {
  return apiRequest("/ads/boxes");
}

export async function getWalletPacks(): Promise<{ packs: WcPack[]; wc_per_usd: number; wc_per_rod: number }> {
  return apiRequest("/wallet/packs");
}

export type Wallet = {
  wind_charges: number;
  /** Gold test currency. Non-zero only for owners, admins and creators. */
  creator_passes: number;
  /** True for owner/admin/creator, drives the Creator Passes UI. */
  is_creator: boolean;
  wc_per_usd: number;
  transactions: WalletTransaction[];
};

export async function getWallet(token: string): Promise<Wallet> {
  return apiRequest("/wallet", { headers: { Authorization: `Bearer ${token}` } });
}

export async function purchaseWindCharges(token: string, packId: string): Promise<{
  granted?: boolean; wind_charges?: number; order_id?: string; approve_url?: string; amount_usd?: number;
}> {
  return apiRequest("/wallet/purchase", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ pack_id: packId }),
  });
}

/** Spend Wind Charges on a cape or cosmetic. 402-style errors surface as
 *  normal Error messages ("Not enough Wind Chargesâ€¦"). */
export async function purchaseItemWithWc(input: {
  token: string; capeId?: string; cosmeticId?: string; promoCode?: string | null;
  currency?: "wind_charges" | "creator_passes";
  /** Buy it for someone else: a username, or a uuid when one is known. */
  giftTo?: { username?: string; uuid?: string } | null;
}): Promise<{
  granted: boolean;
  /** Who received it, when the purchase was a gift. */
  recipient?: BreezeUserProfile | null;
  /** Which currency was actually charged. */
  currency?: "wind_charges" | "creator_passes";
  /** Amount charged in the currency used. */
  spent?: number;
  /** Remaining balance of the currency used. */
  balance?: number;
  spent_wc: number;
  wind_charges: number;
  creator_passes?: number;
  discount_percent?: number;
  message?: string;
}> {
  const payload: Record<string, unknown> = {};
  if (input.capeId) payload.cape_id = input.capeId;
  if (input.cosmeticId) payload.cosmetic_id = input.cosmeticId;
  const promo = (input.promoCode ?? "").trim();
  if (promo) payload.promo_code = promo;
  if (input.currency === "creator_passes") payload.currency = "creator_passes";
  if (input.giftTo?.uuid) {
    payload.recipient_uuid = input.giftTo.uuid;
    payload.gift_to = input.giftTo.uuid;
  } else if (input.giftTo?.username) {
    payload.recipient_username = input.giftTo.username;
    payload.gift_to = input.giftTo.username;
  }
  return apiRequest("/store/purchase-item", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${input.token}` },
    body: JSON.stringify(payload),
  });
}

/** Personal cape paid in Wind Charges (1280 WC = $20). Applies instantly. */
export async function purchasePersonalCapeWc(input: { token: string; file: File | Blob; fileName?: string }): Promise<{
  granted: boolean; cape_url: string; spent_wc: number; wind_charges: number;
}> {
  const formData = new FormData();
  formData.append("cape", input.file, input.fileName ?? "cape.png");
  return apiRequest("/capes/personal-wc", {
    method: "POST",
    headers: { Authorization: `Bearer ${input.token}` },
    body: formData,
  });
}

export type WithdrawalRequest = {
  id: number; amount_wc: number; status: string; note: string | null;
  admin_note: string | null; created_at: string; resolved_at: string | null;
  username?: string; user_uuid?: string; paypal_email?: string;
};

export async function getCreatorWallet(token: string): Promise<{
  earned_wind_charges: number; breeze_rods: number; wc_per_rod: number;
  min_withdrawal_rods: number; withdrawals: WithdrawalRequest[]; recent_earnings: WalletTransaction[];
}> {
  return apiRequest("/creator/wallet", { headers: { Authorization: `Bearer ${token}` } });
}

export async function requestWithdrawal(token: string, input: { amountRods: number; paypalEmail: string; note?: string }): Promise<{ request: WithdrawalRequest }> {
  return apiRequest("/creator/withdrawals", {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ amount_rods: input.amountRods, paypal_email: input.paypalEmail, note: input.note || "" }),
  });
}

export async function adminListWithdrawals(token: string): Promise<{ withdrawals: WithdrawalRequest[]; wc_per_rod: number }> {
  return apiRequest("/admin/withdrawals", { headers: { Authorization: `Bearer ${token}` } });
}

export async function adminResolveWithdrawal(token: string, id: number, status: "approved" | "paid" | "rejected", adminNote?: string): Promise<{ request: WithdrawalRequest }> {
  return apiRequest(`/admin/withdrawals/${id}`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
    body: JSON.stringify({ status, admin_note: adminNote || "" }),
  });
}

export function getMojangSkinUrl(uuid: string): string {
  return `https://crafatar.com/skins/${uuid}`;
}

export function getMojangAvatarUrl(uuid: string, size = 64): string {
  return `https://crafatar.com/avatars/${uuid}?size=${size}&overlay=true`;
}


function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}


async function apiRequest<T>(
  path: string,
  init?: RequestInit,
  opts: { raw?: boolean } = {}
): Promise<T> {
  let lastError: unknown;
  for (const baseUrl of API_BASE_URLS) {
    try {
      const response = await fetch(`${baseUrl}${path}`, init);
      const bodyText = await response.text();
      let parsed: unknown = null;
      if (bodyText) {
        try { parsed = JSON.parse(bodyText); } catch { parsed = null; }
      }

      if (!response.ok) {
        // 413 comes from the reverse proxy, not the app, so it carries no
        // JSON body to read an error out of. Say what actually happened
        // instead of "API request failed with 413".
        if (response.status === 413) {
          // Carries its status like every other answered request, so it is
          // final and not sent again to the next base URL.
          const tooLarge = new Error(
            "That file is too large for the server to accept. " +
            "If this keeps happening on reasonable files, the API's upload limit needs raising.",
          ) as Error & { status?: number };
          tooLarge.status = 413;
          throw tooLarge;
        }
        const msg =
          (parsed && typeof parsed === "object" && parsed !== null && "error" in parsed
            ? String((parsed as { error?: unknown }).error ?? "")
            : "") || `API request failed with ${response.status}`;
        const err = new Error(msg) as Error & { status?: number; reason?: string };
        // Carry the machine-readable parts through. Without these a caller can
        // only match on the message text, which breaks the moment the wording
        // changes; Spotify's PREMIUM_REQUIRED needs a real branch, not a string
        // comparison against a sentence.
        err.status = response.status;
        if (parsed && typeof parsed === "object" && parsed !== null && "reason" in parsed) {
          err.reason = String((parsed as { reason?: unknown }).reason ?? "");
        }
        throw err;
      }

      if (opts.raw) return (parsed ?? {}) as T;

      
      if (parsed && typeof parsed === "object" && "success" in parsed) {
        const envelope = parsed as { success?: boolean; error?: string };
        if (envelope.success === false) {
          const rejected = new Error(envelope.error || "Request rejected by server") as Error & { status?: number };
          rejected.status = response.status;
          throw rejected;
        }
        
        const { success: _ignored, ...payload } = envelope as Record<string, unknown>;
        return payload as T;
      }

      return (parsed ?? {}) as T;
    } catch (error) {
      // A server that answered has decided: its error is the result. Only a
      // request that never got an answer moves on to the next base URL;
      // otherwise a refused upload would be sent again to another server and
      // the user would see that server's error instead of the real one.
      if (error && typeof error === "object" && typeof (error as { status?: unknown }).status === "number") throw error;
      lastError = error;
    }
  }

  // A bare "Failed to fetch" means the request never produced a readable
  // response. For an upload that is almost always the reverse proxy rejecting
  // the body: nginx serves its own 413 page, which carries no CORS headers, so
  // the browser blocks it and the real status is invisible here. Saying so is
  // far more useful than repeating the browser's generic message.
  const isNetworkFailure =
    lastError instanceof TypeError ||
    (lastError instanceof Error && /failed to fetch|networkerror|load failed/i.test(lastError.message));

  if (isNetworkFailure) {
    const looksLikeUpload =
      init?.body instanceof FormData ||
      (typeof init?.method === "string" && init.method.toUpperCase() === "POST");
    throw new Error(
      looksLikeUpload
        ? "Could not reach the Breeze API. If you were uploading a file, it is most likely " +
          "larger than the server accepts (see breeze-api/NGINX.md, client_max_body_size)."
        : "Could not reach the Breeze API. Check your connection, then that the API is running.",
    );
  }

  throw lastError instanceof Error ? lastError : new Error("Breeze API request failed");
}
