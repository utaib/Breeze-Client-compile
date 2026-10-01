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
  InstalledMod, ModuleInfo, ModuleSetting, OwnedCosmetic, UiSettings,
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
    { type: 'mode', id: 'format', label: 'Show', group: 'Armor', value: 'Percent', options: ['Percent', 'Durability', 'Remaining', 'Bar', 'Bar and percent'] },
    { type: 'mode', id: 'orientation', label: 'Orientation', group: 'Armor', value: 'Vertical', options: ['Vertical', 'Horizontal'] },
    { type: 'bool', id: 'icons', label: 'Item icons', group: 'Armor', value: true },
    { type: 'bool', id: 'names', label: 'Item names', group: 'Armor', value: false },
    { type: 'bool', id: 'colours', label: 'Colour by durability', group: 'Armor', value: true },
    { type: 'bool', id: 'empties', label: 'Keep empty slots', group: 'Armor', value: false },
  ]
}

const MODULE_ROWS: [string, ModuleInfo['category'], string][] = [
  ['FPS', 'HUD', 'Shows current framerate.'],
  ['Armor Status', 'HUD', 'Shows each armour piece with its durability as a percentage, a number or a bar.'],
  ['Coordinates', 'HUD', 'Shows player coordinates.'],
  ['CPS', 'HUD', 'Shows clicks per second.'],
  ['Keystrokes', 'HUD', 'Shows WASD + jump keys.'],
  ['Ping', 'HUD', 'Shows server latency.'],
  ['Potion Effects', 'HUD', 'Lists active effects.'],
  ['Direction', 'HUD', 'Shows facing direction.'],
  ['Zoom', 'Visual', 'Toggles a zoomed-in FOV.'],
  ['Low Fire', 'Visual', 'Lowers the fire overlay while you are burning so you can see. At 100% it is hidden.'],
  ['Custom Crosshair', 'Visual', 'Replaces the crosshair with a custom dot.'],
  ['Block Overlay', 'Visual', 'Outlines the targeted block.'],
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
    keybind: name === 'Zoom' ? 'C' : null,
    settings: name === 'Armor Status' ? [...armorSettings(), ...hudStyle()] : category === 'HUD' ? hudStyle() : name === 'Zoom'
      ? [{ type: 'int', id: 'fov', label: 'Zoom level', group: 'General', value: 30, min: 5, max: 70, suffix: '°' },
         { type: 'bool', id: 'smooth', label: 'Smooth zoom', group: 'General', value: true }]
      : name === 'Low Fire'
        // Mirrors versions/1.20.1/src/main/java/dev/breeze/modules/LowFire.java.
        ? [{ type: 'int', id: 'lower', label: 'Lower by', group: 'Fire', value: 50, min: 0, max: 100, suffix: '%' }]
        : [],
  }))
}

const INSTALLED: InstalledMod[] = [
  { id: 'breeze', name: 'Breeze', version: MOD_VERSION, description: 'Breeze Client.', authors: ['utaib'], builtin: false },
  { id: 'fabric-api', name: 'Fabric API', version: '0.92.2+1.20.1', description: 'Core API module providing key hooks and inter-compatibility features.', authors: ['FabricMC'], builtin: false },
  { id: 'mcef', name: 'MCEF', version: '2.1.6-1.20.1', description: 'Chromium Embedded Framework for Minecraft.', authors: ['CinemaMod Group'], builtin: false },
  { id: 'fabricloader', name: 'Fabric Loader', version: '0.16.14', description: 'The mod loading component of Fabric.', authors: ['FabricMC'], builtin: true },
  { id: 'minecraft', name: 'Minecraft', version: '1.20.1', description: '', authors: [], builtin: true },
  { id: 'java', name: 'OpenJDK 64-Bit Server VM', version: '17', description: '', authors: [], builtin: true },
]

// 64x32 cape textures drawn for the preview (a gradient front with two light
// streaks), in the form Java hands over: a data: URI of the whole texture.
const SAMPLE_CAPE_BLUE = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAA0klEQVR42u2QIQ9BYRiF7+/wKwRFUARFUATFRnADgc1mNkmRFMEUiXCT4h9Iik2wsdnuJtgEm02wCbZjX7jtC291nGd70jnpCQIRBP01YNF9U+kQFn8qQHf1gUXaAO3oDYu0AZqLFyzSBgjnT1ikDVCfPeCMYnhNdtoA1ckdzuUJXpOdNkBlfINF2gDl0RUWaQOUhhdMt/DqtkTaAMVBjMkGXt2WSBug0DvDIm2AfOcIi7QBcq0DLNIGyDb2sEgbIFPbwSJtACGEEEIIIYQQ4r/4AjFt1zM9+AcGAAAAAElFTkSuQmCC'
const SAMPLE_CAPE_PINK = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAEAAAAAgCAYAAACinX6EAAAA0UlEQVR42u2QMQuBYRhFv1/ilxgog8FgMBgUg8FgMBjMShmUUiQppUhSyg8wWAwGi+ErFoNiMRgshqt3eLdveFbXPXWme6cTBCII0NjAovsWY0lY/KkAn/oaFmkDvGsrWKQN8KouYJE2wLMyg0XaAI/yFE4s75H6nTbArTSBE/NbpH6nDXAtjGGRNsAlP4RF2gBhrg+MzpG6zUsb4JTtAYMwUrd5aQMcM11YpA1wSHdgkTbAPtWGRdoAu0QLFmkDbONNWKQNIIQQQgghhBBC/BdfCnjpWzuj1TsAAAAASUVORK5CYII='

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
    case 'game.options':
    case 'game.pauseMenu':
    case 'game.quit':
    case 'hud.openEditor':
      return {}
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
