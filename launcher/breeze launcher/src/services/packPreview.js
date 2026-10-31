/**
 * What the import dialog says about a Modrinth pack before anything changes,
 * from inspect_mrpack's answer: the sentence under its title and the lines
 * below it. Kept apart from the page so it is tested on its own
 * (tests/unit/pack-preview.test.mjs).
 */

const LISTED = 3;

function plural(n, word) {
  return `${n} ${word}${n === 1 ? "" : "s"}`;
}

/** Bytes as a player reads them: "820 KB", "12.4 MB". */
export function readableSize(bytes) {
  if (!Number.isFinite(bytes) || bytes <= 0) return "";
  if (bytes < 1024 * 1024) return `${Math.max(1, Math.round(bytes / 1024))} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
  return `${(bytes / (1024 * 1024 * 1024)).toFixed(1)} GB`;
}

function some(list) {
  const shown = list.slice(0, LISTED).join(", ");
  return list.length > LISTED ? `${shown} and ${list.length - LISTED} more` : shown;
}

/** The sentence under the title: the version the pack is for, and where it goes. */
export function packPreviewBody(preview, selectedVersionId) {
  const base = `Made for Minecraft ${preview.gameVersion} with Fabric.`;
  if (selectedVersionId && preview.profileId !== selectedVersionId) {
    return `${base} It goes into your ${preview.profileId} version, not ${selectedVersionId}: mods made for one version do not run on another.`;
  }
  return base;
}

/** What will be downloaded, unpacked, replaced and left out. */
export function packPreviewDetails(preview) {
  const lines = [];
  const others = preview.downloads - preview.mods;
  if (preview.downloads > 0) {
    const what = others > 0 ? `${plural(preview.mods, "mod")} and ${plural(others, "other file")}` : plural(preview.mods, "mod");
    const size = readableSize(preview.downloadBytes);
    lines.push(`${what} to download${size ? ` (${size})` : ""}, each checked against the pack.`);
  }
  if (preview.overrides > 0) {
    lines.push(`${plural(preview.overrides, "file")} from the pack itself (settings, options, packs).`);
  }
  const replaces = preview.replaces || [];
  if (replaces.length > 0) {
    lines.push(`${plural(replaces.length, "file")} already in this version will be replaced and kept in a backup folder: ${some(replaces)}.`);
  }
  const skipped = preview.skipped || [];
  if (skipped.length > 0) {
    lines.push(`${plural(skipped.length, "file")} left out: ${some(skipped.map((s) => s.replace(/^Skipped /, "")))}.`);
  }
  if (lines.length === 0) lines.push("The pack adds nothing to download or unpack.");
  return lines;
}

/** The note after an import: where it went, and what was replaced. */
export function packImportedNote(summary) {
  const mods = summary?.downloadedMods ?? 0;
  const warnings = Array.isArray(summary?.warnings) ? summary.warnings.length : 0;
  let note = `Installed ${summary?.packName || "the pack"} into Minecraft ${summary?.profileId}, ${plural(mods, "mod")}`;
  if (summary?.replaced > 0) note += `; ${plural(summary.replaced, "file")} replaced, the old ones kept in a backup folder`;
  if (warnings > 0) note += ` (${plural(warnings, "file")} left out)`;
  return `${note}.`;
}
