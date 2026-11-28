/**
 * The Minecraft editions and mod sources Breeze knows, and which of them work.
 *
 * Java Edition and, from 1.3.0, Bedrock Edition on Windows (the Bedrock page
 * says plainly when this computer cannot run it; docs/PLAN_1.3.0.md).
 * CurseForge is planned and marked Planned (1.1.0 directive, items nineteen
 * and twenty). The edition is not saved: a player who restarts is on Java.
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
    ready: true,
    summary: "Windows: play, add-ons and worlds",
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

/** What will not change, said plainly so nobody expects more. */
export const BEDROCK_LIMITS = [
  "Bedrock runs on Windows only; macOS and Linux have no official Bedrock Edition.",
  "The Breeze mod's in-game features (Free Look, the recorder and the rest) are for Java Edition. Bedrock uses add-ons, not mods: the Breeze add-on brings a Breeze HUD and crosshairs.",
  "Global resource packs work in every world, Realm and server. Behaviour packs work only in worlds and servers that turn them on.",
  "Breeze will not change the game's own files or switch its version for you.",
];
