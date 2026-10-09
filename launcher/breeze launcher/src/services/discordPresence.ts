import { invoke } from "@tauri-apps/api/core";
import { isRunningInTauri } from "./nativeBridge";

/**
 * Discord Rich Presence for the launcher.
 *
 * Launcher-side only. The in-game states (which server, which world, what the
 * player is doing) come from the Minecraft mod later, so nothing here tries to
 * describe gameplay beyond "Minecraft is running".
 */

export type Presence = {
  details?: string | null;
  state?: string | null;
  largeImage?: string | null;
  largeText?: string | null;
  smallImage?: string | null;
  smallText?: string | null;
  start?: number | null;
  end?: number | null;
};

/**
 * Asset keys as uploaded to the Discord developer portal.
 *
 * `breeze_logo` and `1000002673` are the two that exist today. Everything else
 * is declared here as the key it *will* use, and `resolveAsset` falls back to
 * the logo until it is uploaded, so adding artwork later is a portal upload
 * with no code change.
 */
export const ASSETS = {
  logo: "breeze_logo",
  /** The second uploaded asset. Named by its portal key, which is a numeric id. */
  mark: "1000002673",
  windCharge: "breeze_wind_charge",
  rod: "breeze_rod",
  store: "breeze_store",
  mods: "breeze_mods",
  music: "breeze_music",
  style: "breeze_style",
  minecraft: "breeze_minecraft",
} as const;

/** Keys confirmed present in the portal. Anything else degrades to the logo. */
const UPLOADED = new Set<string>([ASSETS.logo, ASSETS.mark]);

/**
 * Discord renders a broken grey square for an asset key it does not know, which
 * looks worse than repeating the logo. Until a key is uploaded, fall back.
 */
function resolveAsset(key: string | null | undefined): string {
  if (!key) return ASSETS.logo;
  return UPLOADED.has(key) ? key : ASSETS.logo;
}

/**
 * One entry per launcher surface.
 *
 * `image` is the artwork this activity would ideally use; it is resolved
 * through the uploaded set at send time, so listing artwork that does not exist
 * yet is safe and becomes live the moment it is uploaded.
 */
const PAGES: Record<string, { details: string; image: string; small?: string }> = {
  play:     { details: "Getting ready to play",     image: ASSETS.windCharge, small: ASSETS.mark },
  mods:     { details: "Looking at mods",           image: ASSETS.mods },
  packs:    { details: "Managing resource packs",   image: ASSETS.mods },
  store:    { details: "Browsing the store",        image: ASSETS.store },
  social:   { details: "Catching up with friends",  image: ASSETS.logo },
  gifts:    { details: "Sending a gift",            image: ASSETS.mark },
  hosting:  { details: "Setting up a server",       image: ASSETS.logo },
  rewards:  { details: "Checking rewards",          image: ASSETS.windCharge },
  radio:    { details: "Browsing Breeze FM",        image: ASSETS.music },
  custom:   { details: "Customizing Breeze",        image: ASSETS.style },
  console:  { details: "Reading the console",       image: ASSETS.logo },
  settings: { details: "Editing settings",          image: ASSETS.rod },
  admin:    { details: "In the admin panel",        image: ASSETS.logo },
  promos:   { details: "Managing promo codes",      image: ASSETS.logo },
  skin:     { details: "Editing their skin",        image: ASSETS.mark },
  updating: { details: "Checking for updates",      image: ASSETS.rod },
};

const FALLBACK = { details: "Looking at the launcher", image: ASSETS.logo };

export type PresenceInput = {
  page: string;
  /** "idle" | "launching" | "running" */
  launch?: string;
  music?: {
    title?: string | null;
    artist?: string | null;
    playing?: boolean;
    /** Seconds into the track. */
    progress?: number;
    /** Track length in seconds. */
    duration?: number;
  } | null;
  /** Session start, so Discord shows elapsed time rather than restarting it. */
  sessionStart: number;
};

/**
 * Turn launcher state into an activity.
 *
 * Priority is deliberate: launching and running Minecraft outrank everything,
 * because that is what someone reading the profile cares about. Music outranks
 * page navigation, since a track playing is a better description of what the
 * user is doing than whichever tab happens to be open behind it.
 */
export function buildPresence(input: PresenceInput): Presence {
  const { page, launch, music, sessionStart } = input;

  if (launch === "launching") {
    return {
      details: "Launching Minecraft",
      largeImage: resolveAsset(ASSETS.minecraft),
      largeText: "Breeze Client",
      smallImage: resolveAsset(ASSETS.logo),
      smallText: "Breeze",
      start: sessionStart,
    };
  }

  if (launch === "running") {
    return {
      details: "Minecraft is running",
      // Deliberately vague. The mod owns the detailed in-game presence, and two
      // sources both claiming to describe the session would fight each other.
      state: "Launched with Breeze",
      largeImage: resolveAsset(ASSETS.minecraft),
      largeText: "Breeze Client",
      smallImage: resolveAsset(ASSETS.logo),
      smallText: "Breeze",
      start: sessionStart,
    };
  }

  if (music?.playing && music.title) {
    const now = Math.floor(Date.now() / 1000);
    const progress = Math.max(0, Math.floor(music.progress || 0));
    const duration = Math.max(0, Math.floor(music.duration || 0));
    return {
      details: "Listening to music",
      state: music.artist ? `${music.title} by ${music.artist}` : music.title,
      largeImage: resolveAsset(ASSETS.music),
      largeText: "Breeze FM",
      smallImage: resolveAsset(ASSETS.logo),
      smallText: "Breeze",
      // Anchoring start in the past and end in the future is what makes Discord
      // draw a real progress bar that tracks the song rather than a static time.
      start: now - progress,
      end: duration > 0 ? now - progress + duration : null,
    };
  }

  const entry = PAGES[page] || FALLBACK;
  return {
    details: entry.details,
    largeImage: resolveAsset(entry.image),
    largeText: "Breeze Client",
    smallImage: entry.small ? resolveAsset(entry.small) : null,
    smallText: entry.small ? "Breeze" : null,
    start: sessionStart,
  };
}

/**
 * Identity of what is being *shown*, used to skip redundant updates.
 *
 * Timestamps are deliberately excluded. `start` and `end` are derived from
 * playback position, so including them made every single second of a song a
 * different key: an update per second, against a rate limit of roughly one per
 * fifteen. Discord's response to being flooded is to ignore the excess, which
 * strands the presence on whatever it last accepted. That is the "stuck showing
 * an old state" failure, caused by trying too hard to stay current.
 *
 * Nothing is lost by omitting them. Discord animates the progress bar itself
 * from the absolute timestamps it already has, and pausing flips the activity to
 * the page presence, so pause and resume both change this key on their own.
 *
 * The tradeoff: seeking within a playing track is not reflected until the track
 * changes. That is worth one wrong progress bar rather than a frozen presence.
 */
export function presenceKey(p: Presence): string {
  return [p.details, p.state, p.largeImage, p.smallImage].join("|");
}

export async function pushPresence(presence: Presence): Promise<void> {
  if (!isRunningInTauri()) return;
  try {
    await invoke("discord_set_presence", { presence });
  } catch {
    // Discord not running, or not installed. Not worth surfacing.
  }
}

export async function clearPresence(): Promise<void> {
  if (!isRunningInTauri()) return;
  try {
    await invoke("discord_clear_presence");
  } catch {
    // Nothing to clear.
  }
}
