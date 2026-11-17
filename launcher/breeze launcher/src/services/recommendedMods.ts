

export type RecommendedMod = {
  
  name: string;
  
  slug: string | null;
  
  blurb: string;
  
  category: "performance" | "visual" | "ui" | "utility" | "compatibility" | "social";
};

export const RECOMMENDED_MODS: RecommendedMod[] = [
  
  { name: "Sodium",                slug: "sodium",            blurb: "Modern rendering pipeline, biggest single FPS uplift.", category: "performance" },
  { name: "Sodium Extra",          slug: "sodium-extra",      blurb: "Extra Sodium options (toggle particles, fog, etc.).",  category: "performance" },
  { name: "Reese's Sodium Options",slug: "reeses-sodium-options", blurb: "Cleaner Sodium video-settings UI.",               category: "performance" },
  { name: "Lithium",               slug: "lithium",           blurb: "General-purpose game-loop and AI optimisations.",       category: "performance" },
  { name: "FerriteCore",           slug: "ferrite-core",      blurb: "Memory usage reductions (lower RAM ceiling).",          category: "performance" },
  { name: "EntityCulling",         slug: "entityculling",     blurb: "Skips rendering hidden entities and TEs.",              category: "performance" },
  { name: "Dynamic FPS",           slug: "dynamic-fps",       blurb: "Drops FPS when window is unfocused.",                  category: "performance" },
  { name: "ImmediatelyFast",       slug: "immediatelyfast",   blurb: "Speeds up immediate-mode rendering used by HUDs.",      category: "performance" },
  { name: "MoreCulling",           slug: "moreculling",       blurb: "Smarter face-culling for blocks.",                      category: "performance" },
  { name: "Cull Less Leaves",      slug: "cull-less-leaves",  blurb: "Renders leaves more efficiently.",                      category: "performance" },
  { name: "Puzzle",                slug: "puzzle",            blurb: "Resource pack performance fixes.",                      category: "performance" },
  { name: "MixinTrace",            slug: "mixintrace",        blurb: "Better mixin crash logs (debug aid).",                  category: "performance" },
  { name: "Cull Logs",             slug: null,                blurb: "Reduces log spam.",                                     category: "performance" },

  
  { name: "Iris Shaders",          slug: "iris",              blurb: "Shader-pack support layered on Sodium.",                category: "visual" },
  { name: "BetterGrass",           slug: "bettergrassify",    blurb: "Smooths grass-block side textures.",                    category: "visual" },
  { name: "Animatica",             slug: "animatica",         blurb: "Animated textures (OptiFine-style) for resource packs.",category: "visual" },
  { name: "OptiGUI",               slug: "optigui",           blurb: "Custom GUI textures based on the open container.",      category: "visual" },
  { name: "LambDynamicLights",     slug: "lambdynamiclights", blurb: "Held-item dynamic lighting.",                           category: "visual" },
  { name: "Wavey Capes",           slug: "wavey-capes",       blurb: "Capes with proper cloth physics.",                      category: "visual" },
  { name: "Skin Layers 3D",        slug: "skinlayers3d",      blurb: "Renders the second skin layer with depth.",             category: "visual" },
  { name: "No Fog",                slug: "no-fog",            blurb: "Clears the in-game fog.",                               category: "visual" },
  { name: "Not Enough Animations", slug: "not-enough-animations", blurb: "Cleaner first-/third-person animations.",          category: "visual" },
  { name: "Motion Blur",           slug: null,                blurb: "Optional cinematic blur (off by default).",             category: "visual" },
  { name: "Animatium",             slug: null,                blurb: "Smooth UI animations (search by name).",                category: "visual" },

  
  { name: "Mod Menu",              slug: "modmenu",           blurb: "Standard mods list screen.",                            category: "ui" },
  { name: "AppleSkin",             slug: "appleskin",         blurb: "Saturation + food values in the HUD.",                  category: "ui" },
  { name: "BetterF3",              slug: "betterf3",          blurb: "Reorganised F3 debug overlay.",                         category: "ui" },
  { name: "Better Ping Display",   slug: "better-ping-display", blurb: "Larger, configurable tab-list ping.",                  category: "ui" },
  { name: "Better Mount HUD",      slug: "better-mount-hud",  blurb: "Health/jump bars while riding.",                        category: "ui" },
  { name: "Status Effect Bars",    slug: "status-effect-bars",blurb: "Effect duration bars next to icons.",                   category: "ui" },
  { name: "ShulkerBoxTooltip",     slug: "shulkerboxtooltip", blurb: "Hover a shulker to peek inside.",                       category: "ui" },
  { name: "Paginated Advancements",slug: "paginatedadvancements", blurb: "Pages on the advancements screen.",                category: "ui" },
  { name: "Resourcify",            slug: "resourcify",        blurb: "Search + manage resource/shader packs in-game.",        category: "ui" },
  { name: "Main Menu Credits",     slug: null,                blurb: "Cosmetic credits line on the main menu.",               category: "ui" },
  { name: "Player Names",          slug: null,                blurb: "Custom name styling.",                                  category: "ui" },
  { name: "Tier Tag",              slug: null,                blurb: "Hypixel tier tags.",                                    category: "ui" },

  
  { name: "FastQuit",              slug: "fastquit",          blurb: "Skip the long world-save when leaving.",                category: "utility" },
  { name: "Litematica",            slug: "litematica",        blurb: "Schematic placement / building helper.",                category: "utility" },
  { name: "Xaero's Minimap",       slug: "xaeros-minimap",    blurb: "Minimap with waypoints.",                               category: "utility" },
  { name: "Xaero's World Map",     slug: "xaeros-world-map",  blurb: "Full world map screen.",                                category: "utility" },
  { name: "Zoomify",               slug: "zoomify",           blurb: "OptiFine-style zoom keybind.",                          category: "utility" },
  { name: "Freelook",              slug: "freelook",          blurb: "Look around without changing facing.",                  category: "utility" },
  { name: "Fabrishot",             slug: "fabrishot",         blurb: "Hi-res screenshots.",                                   category: "utility" },
  { name: "Gamma Utils",           slug: "gamma-utils",       blurb: "Fullbright via gamma slider.",                          category: "utility" },
  { name: "Server Pinger Fixes",   slug: "server-pinger-fixes", blurb: "Stops bogus server-list pings.",                     category: "utility" },
  { name: "Shield Fixes",          slug: "shield-fixes",      blurb: "Shield rendering and animation polish.",                category: "utility" },
  { name: "ClearHead",             slug: "clearhead",         blurb: "Removes the pumpkin overlay.",                          category: "utility" },
  { name: "ClearWaterLava",        slug: null,                blurb: "Cleaner underwater/lava view.",                         category: "utility" },
  { name: "Player Kits",           slug: null,                blurb: "Quick inventory loadouts.",                             category: "utility" },
  { name: "Trade Cycling",         slug: "trade-cycling",     blurb: "Cycle through villager trades faster.",                 category: "utility" },
  { name: "Auto Sprint Fix",       slug: "auto-sprint-fix",   blurb: "Fixes the auto-sprint quirk on rebinds.",               category: "utility" },
  { name: "Better HitReg",         slug: "better-hitreg",     blurb: "Closes a vanilla hit-registration gap.",                category: "utility" },
  { name: "Combat Hitbox",         slug: null,                blurb: "Visualises combat hitboxes (PvP debug).",               category: "utility" },
  { name: "Modify Player Data",    slug: null,                blurb: "Tweaks save-game player NBT.",                          category: "utility" },
  { name: "Free Camera",           slug: null,                blurb: "Detached camera (Flashback-friendly).",                 category: "utility" },
  { name: "EasyNPC",               slug: "easy-npc",          blurb: "Single-player NPC builder.",                            category: "utility" },
  { name: "More Chat History",     slug: "more-chat-history", blurb: "Larger chat scrollback.",                               category: "utility" },
  { name: "No Chat Reports",       slug: "no-chat-reports",   blurb: "Removes chat-report signing.",                          category: "utility" },
  { name: "Language Reload",       slug: "language-reload",   blurb: "Hot-reloads language files without restart.",           category: "utility" },
  { name: "Command Keys",          slug: "command-keys",      blurb: "Bind keys to chat commands.",                           category: "utility" },
  { name: "Config Manager",        slug: null,                blurb: "Centralised config browser.",                           category: "utility" },

  
  { name: "Fabric API",            slug: "fabric-api",        blurb: "Hard requirement for almost every Fabric mod.",         category: "compatibility" },
  { name: "Fabric Language Kotlin",slug: "fabric-language-kotlin", blurb: "Required by any Kotlin-based Fabric mod.",        category: "compatibility" },
  { name: "Architectury API",      slug: "architectury-api",  blurb: "Cross-loader bridge required by many mods.",            category: "compatibility" },
  { name: "Cloth Config",          slug: "cloth-config",      blurb: "Standard config-screen library.",                       category: "compatibility" },
  { name: "YACL",                  slug: "yacl",              blurb: "Yet Another Config Lib, newer config screens.",        category: "compatibility" },
  { name: "MaLiLib",               slug: "malilib",           blurb: "Library for masa's mods (litematica, etc.).",           category: "compatibility" },
  { name: "Collective",            slug: "collective",        blurb: "Library for Serilum's mods.",                           category: "compatibility" },
  { name: "Ukulib",                slug: "ukulib",            blurb: "Library for ukus's mods.",                              category: "compatibility" },
  { name: "ViaFabricPlus",         slug: "viafabricplus",     blurb: "Connect to older-version servers.",                     category: "compatibility" },
  { name: "Carpet",                slug: "carpet",            blurb: "Vanilla parity tools (server-side).",                   category: "compatibility" },

  
  { name: "Flashback",             slug: "flashback",         blurb: "Modern replay/recording, Breeze integrates with this.",category: "social" },
  { name: "Replay Mod",            slug: "replaymod",         blurb: "Classic replay system.",                                category: "social" },
  { name: "Capes",                 slug: "capes",             blurb: "Third-party cape platform support.",                    category: "social" },
  { name: "Simple Voice Chat",     slug: "simple-voice-chat", blurb: "Proximity voice on supported servers.",                 category: "social" },
  { name: "Controlify",            slug: "controlify",        blurb: "Proper gamepad support.",                               category: "social" },
  { name: "Essential Mod",         slug: null,                blurb: "Essential's social platform (install via their site).", category: "social" },
  { name: "Hero Bot",              slug: null,                blurb: "Self-care widget bot (search by name).",                category: "social" },

  
  { name: "Uku's Armor HUD",       slug: "ukus-armor-hud",    blurb: "Armor display with durability bars.",                   category: "ui" },
  { name: "ScoreBoard",            slug: null,                blurb: "Sidebar tweaks (covered by Breeze module).",            category: "ui" },
];

export const RECOMMENDED_FLAG_KEY = "breeze.recommended-mods.first-launch-seen";


export function isRecommendedPromptUnseen(): boolean {
  try { return localStorage.getItem(RECOMMENDED_FLAG_KEY) !== "1"; }
  catch { return false; }
}

export function markRecommendedPromptSeen(): void {
  try { localStorage.setItem(RECOMMENDED_FLAG_KEY, "1"); } catch {  }
}


export function installableRecommended(): RecommendedMod[] {
  return RECOMMENDED_MODS.filter((m) => m.slug != null);
}
