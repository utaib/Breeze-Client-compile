// Breeze FM track source: iTunes Search API (free, keyless, HTTPS, CSP-safe).
// Only tracks with a 30-second preview URL are returned so everything shown
// in the player is actually playable.

export type FmTrack = {
  id: string;
  title: string;
  artist: string;
  album: string;
  artworkUrl: string | null;
  artworkSmall: string | null;
  previewUrl: string | null;
  length: string;
  genre: string;
};

const ITUNES_SEARCH_URL = "https://itunes.apple.com/search";

/** Genres that consistently return preview-enabled tracks, used to seed the
 *  player with playable music before the user searches for anything. */
export const FM_SEED_QUERIES = [
  "lofi hip hop",
  "ambient electronic",
  "study music",
  "minecraft c418",
  "chill beats",
];

export function pickSeedQuery(): string {
  return FM_SEED_QUERIES[Math.floor(Math.random() * FM_SEED_QUERIES.length)];
}

function formatTrackLength(seconds: number): string {
  const safe = Number.isFinite(seconds) ? Math.max(0, Math.floor(seconds)) : 0;
  return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, "0")}`;
}

type ItunesResult = {
  trackId: number;
  trackName: string;
  artistName: string;
  collectionName?: string;
  artworkUrl100?: string;
  previewUrl?: string;
  trackTimeMillis?: number;
  primaryGenreName?: string;
};

export async function searchItunesTracks(term: string, limit = 25): Promise<FmTrack[]> {
  const url = `${ITUNES_SEARCH_URL}?term=${encodeURIComponent(term)}&media=music&limit=${limit}&entity=song`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Music search failed with ${response.status}`);
  const data = (await response.json()) as { results?: ItunesResult[] };
  return (data.results || [])
    .map((result) => ({
      id: String(result.trackId),
      title: result.trackName,
      artist: result.artistName,
      album: result.collectionName || "",
      artworkUrl: (result.artworkUrl100 || "").replace("100x100bb", "300x300bb") || null,
      artworkSmall: (result.artworkUrl100 || "").replace("100x100bb", "60x60bb") || null,
      previewUrl: result.previewUrl || null,
      length: result.trackTimeMillis ? formatTrackLength(result.trackTimeMillis / 1000) : "N/A",
      genre: result.primaryGenreName || "",
    }))
    .filter((track) => track.previewUrl);
}
