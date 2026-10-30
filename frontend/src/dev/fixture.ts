/**
 * Standalone preview fixture.
 *
 * This file exists so the interface can be developed and tested in an ordinary
 * browser, where Minecraft is not present. It is loaded only when the page
 * finds no window.breezeQuery (see main.tsx), lives in its own chunk, and every
 * screen shows a "Preview data" marker while it is active. Nothing here is ever
 * shown to a player as real data, and nothing here stands in for the Breeze API.
 *
 * Module names, categories, descriptions and setting shapes are copied from the
 * real Java modules so the preview exercises the same layouts the game will.
 *
 * Query parameters (tests use these):
 *   context=ingame        open as the in-game menu instead of the title screen
 *   preview=empty         signed in, but no capes, friends or modules enabled
 *   preview=error         every network-backed action fails
 *   preview=signedout     no Breeze session was handed over
 *   preview=oldapi        an API without the in-game cosmetic routes: 3D
 *                         cosmetics are listed, not equipped
 *   latency=<ms>          simulated bridge latency, default 90
 */
import type {
  Account, ActionMap, Action, Cape, CosmeticState, FriendsState, GameState, Hello, HostingState,
  InstalledMod, Integrations, KeysState, ModuleInfo, ModuleSetting, OwnedCosmetic, UiSettings,
} from '../bridge/types'
import type { Transport } from '../bridge/client'
import { ERROR_CODES } from '../bridge/client'
// The build's own version, which scripts/check-version.sh keeps equal to the mod's.
import { version as MOD_VERSION } from '../../package.json'

const params = new URLSearchParams(window.location.search)
const scenario = params.get('preview') ?? 'default'
const context = params.get('context') === 'ingame' ? 'ingame' : 'title'
const latency = Math.max(0, Number(params.get('latency') ?? 90) || 0)

export const DEFAULT_SETTINGS: UiSettings = {
  theme: 'glass',
  accent: 'blue',
  uiScale: 0,
  animations: true,
  reduceTransparency: false,
  replaceTitleScreen: true,
  backdropDim: 55,
}

function hudStyle(): ModuleSetting[] {
  // Mirrors versions/1.20.1/src/main/java/dev/breeze/hud/HudStyle.java exactly.
  return [
    { type: 'mode', id: 'panel', label: 'Background', group: 'Appearance', value: 'Solid', options: ['None', 'Solid', 'Gradient', 'Outline'] },
    { type: 'color', id: 'bgColor', label: 'Background colour', group: 'Appearance', value: '#A0101420' },
    { type: 'color', id: 'bgColor2', label: 'Gradient to', group: 'Appearance', value: '#9078B2FF' },
    { type: 'int', id: 'radius', label: 'Corner radius', group: 'Appearance', value: 4, min: 0, max: 10, suffix: 'px' },
    { type: 'bool', id: 'border', label: 'Border', group: 'Appearance', value: false },
    { type: 'color', id: 'borderColor', label: 'Border colour', group: 'Appearance', value: '#FF2B3140' },
    { type: 'bool', id: 'shadow', label: 'Drop shadow', group: 'Appearance', value: false },
    { type: 'color', id: 'textColor', label: 'Text colour', group: 'Text', value: '#FFFFFFFF' },
    { type: 'bool', id: 'textShadow', label: 'Text shadow', group: 'Text', value: true },
    { type: 'mode', id: 'textCase', label: 'Text case', group: 'Text', value: 'Normal', options: ['Normal', 'Upper', 'Lower'] },
    { type: 'mode', id: 'align', label: 'Alignment', group: 'Layout', value: 'Left', options: ['Left', 'Center', 'Right'] },
    { type: 'int', id: 'scale', label: 'Scale', group: 'Layout', value: 100, min: 50, max: 200, suffix: '%' },
    { type: 'int', id: 'padding', label: 'Padding', group: 'Layout', value: 3, min: 0, max: 12, suffix: 'px' },
    { type: 'int', id: 'lineGap', label: 'Line spacing', group: 'Layout', value: 1, min: 0, max: 8, suffix: 'px' },
  ]
}

function armorSettings(): ModuleSetting[] {
  // Mirrors versions/1.20.1/src/main/java/dev/breeze/modules/ArmorStatusHud.java.
  return [
    { type: 'mode', id: 'look', label: 'Look', group: 'Armor', value: 'Breeze', options: ['Breeze', 'Hotbar'] },
    { type: 'mode', id: 'format', label: 'Show', group: 'Armor', value: 'Percent', options: ['Percent', 'Durability', 'Remaining', 'Bar', 'Bar and percent'] },
    { type: 'mode', id: 'orientation', label: 'Orientation', group: 'Armor', value: 'Vertical', options: ['Vertical', 'Horizontal'] },
    { type: 'bool', id: 'icons', label: 'Item icons', group: 'Armor', value: true },
    { type: 'bool', id: 'names', label: 'Item names', group: 'Armor', value: false },
    { type: 'bool', id: 'colours', label: 'Colour by durability', group: 'Armor', value: true },
    { type: 'bool', id: 'empties', label: 'Keep empty slots', group: 'Armor', value: false },
  ]
}

function blockOverlaySettings(): ModuleSetting[] {
  // Mirrors versions/1.20.1/src/main/java/dev/breeze/modules/BlockOverlay.java (1.19.4 and later).
  return [
    { type: 'mode', id: 'shape', label: 'Shape', group: 'Outline', value: 'Hitbox', options: ['Hitbox', 'Full block'] },
    { type: 'bool', id: 'outline', label: 'Outline', group: 'Outline', value: true },
    { type: 'color', id: 'outlineColour', label: 'Outline colour', group: 'Outline', value: '#FF33FFCC' },
    { type: 'int', id: 'width', label: 'Line width', group: 'Outline', value: 3, min: 1, max: 10, suffix: ' px' },
    { type: 'bool', id: 'fill', label: 'Fill', group: 'Fill', value: false },
    { type: 'color', id: 'fillColour', label: 'Fill colour', group: 'Fill', value: '#3333FFCC' },
    { type: 'bool', id: 'rainbow', label: 'Rainbow', group: 'Outline', value: false },
  ]
}

function crosshairSettings(): ModuleSetting[] {
  // Mirrors versions/1.20.1/src/main/java/dev/breeze/modules/CustomCrosshair.java.
  return [
    { type: 'mode', id: 'style', label: 'Style', group: 'Crosshair', value: 'Cross', options: ['Cross', 'T', 'Dot'] },
    { type: 'int', id: 'length', label: 'Length', group: 'Crosshair', value: 4, min: 1, max: 20, suffix: ' px' },
    { type: 'int', id: 'thickness', label: 'Thickness', group: 'Crosshair', value: 1, min: 1, max: 8, suffix: ' px' },
    { type: 'int', id: 'gap', label: 'Gap', group: 'Crosshair', value: 2, min: 0, max: 12, suffix: ' px' },
    { type: 'color', id: 'color', label: 'Colour', group: 'Crosshair', value: '#FFFFFFFF' },
    { type: 'bool', id: 'dot', label: 'Centre dot', group: 'Crosshair', value: false },
    { type: 'bool', id: 'third_person', label: 'Show in third person', group: 'Crosshair', value: false },
    { type: 'bool', id: 'outline', label: 'Outline', group: 'Outline', value: true },
    { type: 'int', id: 'outline_thickness', label: 'Outline thickness', group: 'Outline', value: 1, min: 1, max: 3, suffix: ' px' },
    { type: 'color', id: 'outline_color', label: 'Outline colour', group: 'Outline', value: '#FF000000' },
  ]
}

/**
 * Two share codes from contract/crosshair.json, so the preview can show a
 * code being used; codes are really made and read in Java.
 */
const CROSSHAIR_CODES: Record<string, Record<string, string | number | boolean>> = {
  'BZC-AQAEAQICAf______AAAApS4': { style: 'Cross', length: 4, thickness: 1, gap: 2, color: '#FFFFFFFF', dot: false, outline: true, outline_thickness: 1, outline_color: '#FF000000' },
  'BZC-AQADAgABAf8A_wD_AAAA_vg': { style: 'Cross', length: 3, thickness: 2, gap: 0, color: '#FF00FF00', dot: true, outline: false, outline_thickness: 1, outline_color: '#FF000000' },
}

function freeLookSettings(): ModuleSetting[] {
  // Mirrors versions/1.20.1/src/main/java/dev/breeze/modules/FreeLook.java.
  return [
    { type: 'mode', id: 'mode', label: 'Key', group: 'Free Look', value: 'Hold', options: ['Hold', 'Toggle'] },
    { type: 'int', id: 'sensitivity', label: 'Sensitivity', group: 'Free Look', value: 100, min: 10, max: 300, suffix: '%' },
    { type: 'bool', id: 'third_person', label: 'Third person while looking', group: 'Free Look', value: true },
  ]
}

const MODULE_ROWS: [string, ModuleInfo['category'], string][] = [
  ['FPS', 'HUD', 'Shows current framerate.'],
  ['Armor Status', 'HUD', 'Shows your armour and what is in your hands, with durability as a number or a bar, in Breeze\'s look or in Minecraft\'s hotbar slots.'],
  ['Coordinates', 'HUD', 'Shows player coordinates.'],
  ['CPS', 'HUD', 'Shows clicks per second.'],
  ['Keystrokes', 'HUD', 'Shows WASD + jump keys.'],
  ['Ping', 'HUD', 'Shows server latency.'],
  ['Potion Effects', 'HUD', 'Lists active effects.'],
  ['Direction', 'HUD', 'Shows facing direction.'],
  ['Zoom', 'Visual', 'Toggles a zoomed-in FOV.'],
  ['Low Fire', 'Visual', 'Lowers the fire overlay while you are burning so you can see. At 100% it is hidden.'],
  ['Custom Crosshair', 'Visual', 'Your own crosshair: style, size, gap, colours, outline, and a code to share it.'],
  ['Free Look', 'Utility', 'Look around without turning your player.'],
  ['Block Overlay', 'Visual', 'Outlines the block you look at, as its real shape or a full square, in your colours.'],
  ['Fullbright', 'Utility', 'Lights everything up fully, without changing your brightness setting.'],
  ['Toggle Sprint', 'Utility', 'Always sprint.'],
  ['Toggle Sneak', 'Utility', 'Keeps you sneaking without holding the key.'],
  ['Shulker Tooltips', 'Utility', 'Previews shulker box contents on hover.'],
  ['Auto Text', 'Chat', 'Sends a preset chat message on its keybind.'],
]

function makeModules(): ModuleInfo[] {
  const enabledByDefault = scenario === 'empty' ? new Set<string>() : new Set(['FPS', 'Armor Status', 'Coordinates', 'Toggle Sprint'])
  return MODULE_ROWS.map(([name, category, description]) => ({
    name,
    category,
    description,
    enabled: enabledByDefault.has(name),
    hud: category === 'HUD',
    keybind: name === 'Zoom' ? 'C' : name === 'Free Look' ? 'Left Alt' : null,
    ...(name === 'Free Look' ? { keyActs: true } : {}),
    settings: name === 'Armor Status' ? [...armorSettings(), ...hudStyle()] : category === 'HUD' ? hudStyle() : name === 'Zoom'
      ? [{ type: 'int', id: 'fov', label: 'Zoom level', group: 'General', value: 30, min: 5, max: 70, suffix: '°' },
         { type: 'bool', id: 'smooth', label: 'Smooth zoom', group: 'General', value: true }]
      : name === 'Block Overlay'
        ? blockOverlaySettings()
      : name === 'Low Fire'
        // Mirrors versions/1.20.1/src/main/java/dev/breeze/modules/LowFire.java.
        ? [{ type: 'int', id: 'lower', label: 'Lower by', group: 'Fire', value: 50, min: 0, max: 100, suffix: '%' }]
      : name === 'Custom Crosshair'
        ? crosshairSettings()
      : name === 'Free Look'
        ? freeLookSettings()
        : [],
  }))
}

/** ?preview=manymods: mods with a Mod Menu settings screen each. */
const MANY_SETTINGS: [string, string][] = [
  ['sodium', 'Sodium'], ['iris', 'Iris'], ['lithium', 'Lithium'], ['appleskin', 'AppleSkin'], ['jade', 'Jade'],
  ['continuity', 'Continuity'], ['entityculling', 'Entity Culling'], ['betterf3', 'BetterF3'],
  ['lambdynlights', 'LambDynamicLights'], ['controlling', 'Controlling'], ['chat_heads', 'Chat Heads'],
  ['shulkerboxtooltip', 'Shulker Box Tooltip'],
]
/** Essential's title screen buttons, as many as it can add. */
const ESSENTIAL_BUTTONS = ['Essential', 'Cosmetics', 'Social', 'Pictures', 'Invite friends', 'Host a world', 'Wardrobe', 'Essential settings', 'Updates']
const titleButton = (label: string, mod?: string) => ({
  id: `title:${label}`, name: label, provider: 'title-screen' as const,
  actions: [{ id: 'press', label, ...(mod ? { mod } : {}) }],
})

const INSTALLED: InstalledMod[] = [
  { id: 'breeze', name: 'Breeze', version: MOD_VERSION, description: 'Breeze Client.', authors: ['utaib'], builtin: false, kind: 'breeze' },
  { id: 'fabric-api', name: 'Fabric API', version: '0.92.2+1.20.1', description: 'Core API module providing key hooks and inter-compatibility features.', authors: ['FabricMC'], builtin: false, kind: 'library' },
  { id: 'mcef', name: 'MCEF', version: '2.1.6-1.20.1', description: 'Chromium Embedded Framework for Minecraft.', authors: ['CinemaMod Group'], builtin: false, kind: 'mod' },
  { id: 'fabricloader', name: 'Fabric Loader', version: '0.16.14', description: 'The mod loading component of Fabric.', authors: ['FabricMC'], builtin: true, kind: 'game' },
  { id: 'minecraft', name: 'Minecraft', version: '1.20.1', description: '', authors: [], builtin: true, kind: 'game' },
  { id: 'java', name: 'OpenJDK 64-Bit Server VM', version: '17', description: '', authors: [], builtin: true, kind: 'game' },
  // ?preview=mods: other mods with Mod Menu, as a tester's game has them.
  ...(scenario === 'mods' || scenario === 'manymods' ? [
    { id: 'modmenu', name: 'Mod Menu', version: '7.2.2', description: 'Adds a mod menu to view the list of mods you have installed.', authors: ['Prospector'], builtin: false, kind: 'mod' as const },
    { id: 'zoomify', name: 'Zoomify', version: '2.14.6+1.20.1', description: 'A zoom mod with infinite customizability.', authors: ['isXander'], builtin: false, kind: 'mod' as const },
    { id: 'xaerominimap', name: "Xaero's Minimap", version: '24.6.1', description: 'Displays a map of nearby terrain.', authors: ['xaero96'], builtin: false, kind: 'mod' as const },
  ] : []),
  // ?preview=manymods: a game full of mods, one of them with many title buttons.
  ...(scenario === 'manymods' ? [
    { id: 'essential', name: 'Essential', version: '1.3.5', description: 'Cosmetics, friends and hosting worlds.', authors: ['Spark Universe'], builtin: false, kind: 'mod' as const },
    { id: 'flashback', name: 'Flashback', version: '0.20.1', description: 'A replay mod.', authors: ['Moulberry'], builtin: false, kind: 'mod' as const },
    { id: 'replaymod', name: 'Replay Mod', version: '2.6.20', description: 'Records and replays your game.', authors: ['johni0702'], builtin: false, kind: 'mod' as const },
    ...MANY_SETTINGS.map(([id, name]) => ({ id, name, version: '1.0.0', description: '', authors: [], builtin: false, kind: 'mod' as const })),
  ] : []),
]

const INTEGRATIONS: Integrations = scenario === 'mods'
  ? {
      integrations: [{
        id: 'modmenu', name: 'Mod Menu', provider: 'modmenu',
        actions: [
          { id: 'mods', label: 'Mod Menu' },
          { id: 'config:zoomify', label: 'Zoomify settings', mod: 'zoomify' },
        ],
      }],
      problems: [{ provider: 'modmenu', mod: 'xaerominimap', detail: "Xaero's Minimap's settings screen failed to open: NoClassDefFoundError: (preview sample)" }],
    }
  : scenario === 'manymods'
    ? {
        integrations: [
          {
            id: 'modmenu', name: 'Mod Menu', provider: 'modmenu',
            actions: [
              { id: 'mods', label: 'Mod Menu' },
              { id: 'config:zoomify', label: 'Zoomify settings', mod: 'zoomify' },
              ...MANY_SETTINGS.map(([id, name]) => ({ id: `config:${id}`, label: `${name} settings`, mod: id })),
            ],
          },
          { id: 'breeze:replaymod', name: 'Replay Mod', provider: 'breeze-entrypoint', actions: [{ id: 'open', label: 'Replay Mod', mod: 'replaymod' }] },
          ...ESSENTIAL_BUTTONS.map((label) => titleButton(label, 'essential')),
          // Java could not tell these buttons' mods: one is named like its mod.
          titleButton('Flashback'),
          titleButton('Replay Viewer', 'replaymod'),
          titleButton('Changelog'),
        ],
        problems: [],
      }
    : { integrations: [], problems: [] }

// 64x32 cape textures drawn for the preview (a gradient front with two light
// streaks), in the form Java hands over: a data: URI of the whole texture.
const SAMPLE_CAPE_BLUE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAA0klEQVR42u2QIQ9BYRiF7+/wKwRFUARFUATFRnADgc1mNkmRFMEUiXCT4h9Iik2wsdnuJtgEm02wCbZjX7jtC291nGd70jnpCQIRBP01YNF9U+kQFn8qQHf1gUXaAO3oDYu0AZqLFyzSBgjnT1ikDVCfPeCMYnhNdtoA1ckdzuUJXpOdNkBlfINF2gDl0RUWaQOUhhdMt/DqtkTaAMVBjMkGXt2WSBug0DvDIm2AfOcIi7QBcq0DLNIGyDb2sEgbIFPbwSJtACGEEEIIIYQQ4r/4AjFt1zM9+AcGAAAAAElFTkSuQmCC'
const SAMPLE_CAPE_PINK = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAA0UlEQVR42u2QMQuBYRhFv1/ilxgog8FgMBgUg8FgMBjMShmUUiQppUhSyg8wWAwGi+ErFoNiMRgshqt3eLdveFbXPXWme6cTBCII0NjAovsWY0lY/KkAn/oaFmkDvGsrWKQN8KouYJE2wLMyg0XaAI/yFE4s75H6nTbArTSBE/NbpH6nDXAtjGGRNsAlP4RF2gBhrg+MzpG6zUsb4JTtAYMwUrd5aQMcM11YpA1wSHdgkTbAPtWGRdoAu0QLFmkDbONNWKQNIIQQQgghhBBC/BdfCnjpWzuj1TsAAAAASUVORK5CYII='

/** Minecraft's own default bindings, by key, for conflicts (as Minecraft names them in English). */
const VANILLA_KEYS: Record<string, string> = {
  W: 'Walk Forwards', A: 'Strafe Left', S: 'Walk Backwards', D: 'Strafe Right', Space: 'Jump',
  'Left Shift': 'Sneak', 'Left Control': 'Sprint', Q: 'Drop Selected Item', E: 'Open/Close Inventory',
  F: 'Swap Item With Offhand', T: 'Open Chat', '/': 'Open Command', Tab: 'List Players', L: 'Advancements',
  F1: 'Toggle HUD', F2: 'Take Screenshot', F5: 'Toggle Perspective', F11: 'Toggle Fullscreen',
  C: 'Save Hotbar Activator', X: 'Load Hotbar Activator',
  ...Object.fromEntries(Array.from({ length: 9 }, (_, i) => [String(i + 1), `Hotbar Slot ${i + 1}`])),
}

interface FixtureKey { id: string; label: string; module?: string; key: string; defaultKey: string }

function makeKeys(modules: ModuleInfo[]): FixtureKey[] {
  return [
    { id: 'key.breeze.menu', label: 'Open the Breeze menu', key: 'Right Shift', defaultKey: 'Right Shift' },
    { id: 'key.breeze.music', label: 'Music player', key: 'M', defaultKey: 'M' },
    ...[...modules].sort((a, b) => a.name.localeCompare(b.name)).map((m) => ({
      id: `key.breeze.${m.name.toLowerCase().replace(/ /g, '_')}`, label: m.name, module: m.name,
      key: m.keybind ?? '', defaultKey: m.keybind ?? '',
    })),
  ]
}

const state = {
  settings: { ...DEFAULT_SETTINGS },
  modules: makeModules(),
  owned3d: (scenario === 'empty' ? [] : [
    { id: 'preview-hat', name: 'Preview hat', slot: 'hat', equipped: true },
    { id: 'preview-hat-2', name: 'Preview hat two', slot: 'hat', equipped: false },
    { id: 'preview-pet', name: 'Preview pet', slot: 'pet', equipped: true },
    { id: 'preview-trail', name: 'Preview trail', slot: 'trail', equipped: false },
  ]) as OwnedCosmetic[],
  capes: (scenario === 'empty' ? [] : [
    { id: 'preview-a', name: 'Preview cape A', preview: SAMPLE_CAPE_BLUE, equipped: true },
    { id: 'preview-b', name: 'Preview cape B', preview: SAMPLE_CAPE_PINK, equipped: false },
    // No picture: how a cape looks while its image is still downloading or failed.
    { id: 'preview-c', name: 'Preview cape C', preview: null, equipped: false },
  ]) as Cape[],
  // Catalogue capes this account does not own.
  store: [
    { id: 'preview-d', name: 'Preview cape D', preview: SAMPLE_CAPE_PINK, equipped: false },
    { id: 'preview-e', name: 'Preview cape E', preview: SAMPLE_CAPE_BLUE, equipped: false },
  ] as Cape[],
  friends: {
    friends: scenario === 'empty' ? [] : [
      { uuid: '00000000-0000-4000-8000-000000000001', name: 'PreviewFriendOne', online: true },
      { uuid: '00000000-0000-4000-8000-000000000002', name: 'PreviewFriendTwo', online: false },
    ],
    incoming: scenario === 'empty' ? [] : [{ uuid: '00000000-0000-4000-8000-000000000003', name: 'PreviewRequest' }],
    outgoing: [],
    updatedAt: Date.now(),
    status: '',
  } as FriendsState,
  hosting: { hosting: false, busy: false, canHost: context === 'ingame', status: '', invites: scenario === 'empty' ? [] : [{ host: '00000000-0000-4000-8000-000000000001', hostName: 'PreviewFriendOne' }] } as HostingState,
  lastRoute: null as string | null,
}

function account(): Account {
  return {
    playerName: 'PreviewPlayer',
    uuid: '00000000-0000-4000-8000-00000000abcd',
    breeze: scenario === 'signedout' ? 'signed-out' : 'ready',
    role: scenario === 'signedout' ? null : 'user',
  }
}

function cosmetics(): CosmeticState {
  return {
    capes: state.capes,
    equippedCapeId: state.capes.find((c) => c.equipped)?.id ?? null,
    // An API without the in-game routes cannot take a cape off either.
    canRemove: scenario !== 'oldapi',
    store: state.store,
    worn: state.owned3d.filter((o) => o.equipped).map(({ id, name, slot }) => ({ id, name, slot })),
    owned: scenario === 'oldapi' ? null : state.owned3d,
  }
}

function game(): GameState {
  const ingame = context === 'ingame'
  return {
    inWorld: ingame,
    singleplayer: ingame,
    worldName: ingame ? 'Preview world' : null,
    serverName: null,
    serverAddress: null,
    playerName: 'PreviewPlayer',
    dimension: ingame ? 'minecraft:overworld' : null,
    fps: 144,
    pingMs: null,
    lastServer: scenario === 'empty' ? null : { name: 'Preview server', address: 'preview.invalid' },
  }
}

class FixtureError extends Error {
  constructor(readonly code: (typeof ERROR_CODES)[number], message: string) {
    super(message)
  }
}

const networkBacked = new Set<Action>(['cosmetics.state', 'cosmetics.equipCape', 'cosmetics.equipModel', 'cosmetics.unequipModel', 'friends.list', 'friends.request', 'friends.respond', 'friends.remove', 'hosting.start', 'hosting.join'])

function handle(action: Action, p: Record<string, unknown>): unknown {
  if (scenario === 'error' && networkBacked.has(action)) {
    throw new FixtureError('UNAVAILABLE', 'The Breeze service could not be reached.')
  }
  if (scenario === 'signedout' && networkBacked.has(action)) {
    throw new FixtureError('FORBIDDEN', 'Sign in to Breeze in the launcher to use this.')
  }
  switch (action) {
    case 'app.hello': {
      const hello: Hello = {
        protocol: 1, modVersion: MOD_VERSION, minecraftVersion: '1.20.1', loaderVersion: '0.16.14',
        chromiumVersion: navigator.userAgent.match(/Chrome\/([\d.]+)/)?.[1] ?? null,
        context, lastRoute: state.lastRoute,
        features: { cosmetics: true, friends: true, hosting: true, vanillaMenu: context === 'title' },
        preview: true,
      }
      return hello
    }
    case 'app.openExternal':
    case 'ui.close':
    case 'ui.escapeAck':
    case 'ui.vanillaMenu':
    case 'game.singleplayer':
    case 'game.multiplayer':
    case 'game.joinServer':
    case 'modules.icons':
      // Two stand-ins for the game's textures: a plain 16x16 picture, and a
      // 32x32 sheet whose picture is its top-right square (the rest is blue,
      // and must not show). The other modules keep their category symbol.
      return {
        icons: {
          FPS: { src: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABAAAAAQCAYAAAAf8/9hAAAAMElEQVR42mNgGLTg/wKD//8dFP7DaNINQNIMooegC0Ag20v7PwiTHZCjBowaQCwAABrrSw/+KhV5AAAAAElFTkSuQmCC', x: 0, y: 0, size: 16, w: 16, h: 16 },
          Ping: { src: 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAACAAAAAgCAYAAABzenr0AAAAbUlEQVR42mMwaPj/nxLMQCkYEQ7YkaXzf0AcALIYHdPNAdgsx+qIYekAfJZjOGI0CqjpgHfPLv0f0FwAcgC6I+haDsAcgM0RdCuISHYELRLhgDtgZNWGFHtg1AGjDhh1wKgDRh0w6oBRBwy0AwCljaNNFC9z6gAAAABJRU5ErkJggg==', x: 16, y: 0, size: 16, w: 32, h: 32 },
        },
      }
    case 'game.options':
    case 'game.pauseMenu':
    case 'game.quit':
    case 'hud.openEditor':
    case 'music.open':
    case 'ui.reportError':
      return {}
    case 'ui.menuStyle':
      return { style: p.style === 'classic' ? 'classic' : 'web' }
    case 'ui.route':
      state.lastRoute = String(p.route)
      return {}
    case 'game.state': return game()
    case 'settings.get': return state.settings
    case 'settings.set': {
      const key = p.key as keyof UiSettings
      if (!(key in state.settings)) throw new FixtureError('INVALID_PARAMS', `Unknown setting ${String(key)}`)
      state.settings = { ...state.settings, [key]: p.value } as UiSettings
      return state.settings
    }
    case 'settings.reset':
      state.settings = { ...DEFAULT_SETTINGS }
      return state.settings
    case 'modules.list': return state.modules
    case 'modules.setEnabled':
    case 'modules.setSetting':
    case 'modules.reset': {
      const m = state.modules.find((x) => x.name === p.name)
      if (!m) throw new FixtureError('INVALID_PARAMS', `No module named ${String(p.name)}`)
      if (action === 'modules.setEnabled') m.enabled = Boolean(p.enabled)
      if (action === 'modules.setSetting') {
        const s = m.settings.find((x) => x.id === p.id)
        if (!s) throw new FixtureError('INVALID_PARAMS', `No setting ${String(p.id)}`)
        ;(s as { value: unknown }).value = p.value
      }
      if (action === 'modules.reset') {
        const fresh = makeModules().find((x) => x.name === m.name)!
        m.settings = fresh.settings
      }
      return { ...m, settings: m.settings.map((s) => ({ ...s })) }
    }
    case 'mods.list': return INSTALLED
    case 'keys.list': return { ...keysState(), ...(stopListening && capturingId ? { capturing: capturingId } : {}) }
    case 'keys.capture': {
      const k = keys.find((x) => x.id === p.id)
      if (!k) throw new FixtureError('INVALID_PARAMS', 'No such Breeze key.')
      capturingId = k.id
      listenForKey(k)
      return {}
    }
    case 'keys.cancel':
      stopListening?.()
      return {}
    case 'keys.reset': {
      const k = keys.find((x) => x.id === p.id)
      if (!k || (p.to !== 'default' && p.to !== 'none')) throw new FixtureError('INVALID_PARAMS', 'No such Breeze key.')
      setKey(k, p.to === 'none' ? '' : k.defaultKey)
      return keysState()
    }
    case 'crosshair.export': {
      const m = state.modules.find((x) => x.name === 'Custom Crosshair')!
      const now = Object.fromEntries(m.settings.map((s) => [s.id, s.value]))
      const code = Object.entries(CROSSHAIR_CODES).find(([, c]) => Object.entries(c).every(([k, v]) => now[k] === v))?.[0]
      return { code: code ?? 'BZC-AQAEAQICAf______AAAApS4' }
    }
    case 'crosshair.import': {
      const code = String(p.code ?? '').trim()
      const known = CROSSHAIR_CODES[code]
      if (!code) throw new FixtureError('INVALID_PARAMS', 'Paste a crosshair code.')
      if (!code.toUpperCase().startsWith('BZC-')) throw new FixtureError('INVALID_PARAMS', 'That is not a Breeze crosshair code. They start with BZC-')
      if (!known) throw new FixtureError('INVALID_PARAMS', 'That code is damaged or incomplete. Copy it again.')
      const m = state.modules.find((x) => x.name === 'Custom Crosshair')!
      for (const s of m.settings) if (s.id in known) (s as { value: unknown }).value = known[s.id]
      return m
    }
    case 'integrations.list': return INTEGRATIONS
    case 'integrations.open': {
      // ?preview=manymods: a button its mod took off the title screen after
      // the list was read, refused the way Java refuses it.
      if (p.id === 'title:Changelog') throw new FixtureError('UNAVAILABLE', 'Changelog is not on the title screen in this game.')
      const known = INTEGRATIONS.integrations.find((i) => i.id === p.id)?.actions.some((a) => a.id === p.action)
      if (!known) throw new FixtureError('UNAVAILABLE', 'That is not available in this game.')
      return {}
    }
    case 'account.get': return account()
    case 'cosmetics.state': return cosmetics()
    case 'cosmetics.equipModel': {
      const target = state.owned3d.find((o) => o.id === p.id)
      if (!target || scenario === 'oldapi') throw new FixtureError('FORBIDDEN', 'That cosmetic is not on your account.')
      state.owned3d = state.owned3d.map((o) => (o.slot === target.slot ? { ...o, equipped: o.id === target.id } : o))
      return cosmetics()
    }
    case 'cosmetics.unequipModel':
      state.owned3d = state.owned3d.map((o) => (o.slot === p.slot ? { ...o, equipped: false } : o))
      return cosmetics()
    case 'cosmetics.equipCape':
      state.capes = state.capes.map((c) => ({ ...c, equipped: c.id === p.id }))
      return cosmetics()
    case 'friends.list': return state.friends
    case 'friends.request': {
      const name = String(p.name)
      state.friends = { ...state.friends, outgoing: [...state.friends.outgoing, { uuid: `preview-${name}`, name }] }
      return state.friends
    }
    case 'friends.respond': {
      const req = state.friends.incoming.find((r) => r.uuid === p.uuid)
      state.friends = {
        ...state.friends,
        incoming: state.friends.incoming.filter((r) => r.uuid !== p.uuid),
        friends: p.accept && req ? [...state.friends.friends, { uuid: req.uuid, name: req.name, online: false }] : state.friends.friends,
      }
      return state.friends
    }
    case 'friends.remove':
      state.friends = { ...state.friends, friends: state.friends.friends.filter((f) => f.uuid !== p.uuid) }
      return state.friends
    case 'hosting.state': return state.hosting
    case 'hosting.start':
      if (!state.hosting.canHost) throw new FixtureError('UNAVAILABLE', 'Open a singleplayer world first.')
      state.hosting = { ...state.hosting, hosting: true, status: 'Hosting. Invited friends can join.' }
      return state.hosting
    case 'hosting.stop':
      state.hosting = { ...state.hosting, hosting: false, status: 'Stopped hosting.' }
      return state.hosting
    case 'hosting.join':
      return state.hosting
  }
}

const keys: FixtureKey[] = makeKeys(state.modules)

function keysState(): KeysState {
  return {
    keys: keys.map((k) => ({
      id: k.id, label: k.label, ...(k.module ? { module: k.module } : {}), key: k.key,
      isDefault: k.key === k.defaultKey, defaultKey: k.defaultKey,
      conflicts: k.key ? [
        ...(VANILLA_KEYS[k.key] ? [VANILLA_KEYS[k.key]!] : []),
        ...keys.filter((o) => o !== k && o.key === k.key).map((o) => o.label),
      ] : [],
    })),
  }
}

function setKey(k: FixtureKey, key: string) {
  k.key = key
  const m = state.modules.find((x) => x.name === k.module)
  if (m) m.keybind = key || null
}

/** A key's name as Minecraft shows it, from the browser's key event. */
function keyName(e: KeyboardEvent): string {
  const named: Record<string, string> = {
    ShiftLeft: 'Left Shift', ShiftRight: 'Right Shift', ControlLeft: 'Left Control', ControlRight: 'Right Control',
    AltLeft: 'Left Alt', AltRight: 'Right Alt', Space: 'Space', Tab: 'Tab', Enter: 'Enter', Backspace: 'Backspace',
    ArrowUp: 'Up Arrow', ArrowDown: 'Down Arrow', ArrowLeft: 'Left Arrow', ArrowRight: 'Right Arrow', Slash: '/',
  }
  if (named[e.code]) return named[e.code]!
  if (/^Key[A-Z]$/.test(e.code)) return e.code.slice(3)
  if (/^Digit\d$/.test(e.code)) return e.code.slice(5)
  if (/^F\d{1,2}$/.test(e.code)) return e.code
  return e.key.length === 1 ? e.key.toUpperCase() : e.key
}

/** keys.capture: like Java, the next key press is the binding and the page never sees it. */
let stopListening: (() => void) | null = null
let capturingId: string | null = null
function listenForKey(k: FixtureKey) {
  stopListening?.()
  const onKey = (e: KeyboardEvent) => {
    e.preventDefault()
    e.stopImmediatePropagation()
    stop()
    const cancelled = e.key === 'Escape'
    if (!cancelled) setKey(k, keyName(e))
    window.__breezeEvent?.({ type: 'keys.changed', payload: { id: k.id, cancelled } })
  }
  const stop = () => {
    window.removeEventListener('keydown', onKey, true)
    stopListening = null
  }
  window.addEventListener('keydown', onKey, true)
  stopListening = stop
}

declare global {
  interface Window {
    /** Every action the page sent, for tests. Preview only. */
    __breezeCalls?: { action: Action; params: Record<string, unknown> }[]
  }
}

export function previewTransport(): Transport {
  window.__breezeCalls = []
  return {
    kind: 'preview',
    send(request, onSuccess, onFailure) {
      let cancelled = false
      const timer = setTimeout(() => {
        if (cancelled) return
        try {
          const { action, params: p } = JSON.parse(request) as { action: Action; params: Record<string, unknown> }
          window.__breezeCalls!.push({ action, params: p ?? {} })
          const result = handle(action, p ?? {}) as ActionMap[typeof action][1]
          onSuccess(JSON.stringify(result ?? {}))
        } catch (err) {
          if (err instanceof FixtureError) onFailure(ERROR_CODES.indexOf(err.code) + 1, err.message)
          else onFailure(ERROR_CODES.indexOf('INTERNAL') + 1, String(err))
        }
      }, latency)
      return () => {
        cancelled = true
        clearTimeout(timer)
      }
    },
  }
}
