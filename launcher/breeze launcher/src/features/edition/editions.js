/**
 * The Minecraft editions and mod sources Breeze knows, and which of them work.
 *
 * Only Java Edition with Modrinth works today. Bedrock Edition and CurseForge
 * are planned (docs/EDITIONS_AND_SOURCES.md) and are marked Planned:
 * choosing one explains what is planned and changes nothing, so the launcher
 * never looks as if it could launch or manage something it cannot (1.1.0
 * directive, items nineteen and twenty). Nothing here is saved: a player who
 * restarts is on Java and Modrinth, as before.
 */

export const EDITIONS = [
  {
    id: "java",
    name: "Java Edition",
    ready: true,
    summary: "Fabric, your mods and the Breeze mod",
  },
  {
    id: "bedrock",
    name: "Bedrock Edition",
    ready: false,
    summary: "Planned, for Windows",
  },
];

export const MOD_SOURCES = [
  { id: "modrinth", name: "Modrinth", ready: true },
  { id: "curseforge", name: "CurseForge", ready: false },
];

/** The edition with this id, or Java when the id is unknown. */
export function editionById(id) {
  return EDITIONS.find((e) => e.id === id) || EDITIONS[0];
}

/** What the Bedrock page says is planned, in the order it would come. */
export const BEDROCK_PLAN = [
  {
    title: "Open Bedrock from Breeze",
    text: "Start the Bedrock Edition installed on your Windows PC, with your Breeze account, news and cosmetics beside it.",
  },
  {
    title: "Add-ons and packs",
    text: "Import, turn on and remove behaviour packs and resource packs, checked before anything is copied, with a backup.",
  },
  {
    title: "Worlds",
    text: "See, back up and restore your Bedrock worlds.",
  },
];

/** What will not change, said plainly so nobody expects more. */
export const BEDROCK_LIMITS = [
  "Bedrock runs on Windows only; macOS and Linux have no official Bedrock Edition.",
  "The Breeze mod's in-game features (Free Look, the crosshair, HUD and the rest) are for Java Edition. Bedrock uses add-ons, not mods.",
  "Breeze will not change the game's own files or switch its version for you.",
];
