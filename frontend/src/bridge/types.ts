// Types for every payload that crosses the bridge. Field names match what the
// Java handlers write (versions/*/src/main/java/dev/breeze/web/handlers).

export type Context = 'title' | 'ingame'

export interface Hello {
  protocol: number
  modVersion: string
  minecraftVersion: string
  loaderVersion: string
  chromiumVersion: string | null
  context: Context
  /** Route the screen was last left on, so a reopened menu starts there. */
  lastRoute: string | null
  /** Features the Java side can actually serve on this install. */
  features: Features
  /** True only for the standalone preview fixture, never in game. */
  preview?: boolean
}

export interface Features {
  cosmetics: boolean
  friends: boolean
  hosting: boolean
  vanillaMenu: boolean
}

export type ThemeId =
  | 'glass' | 'black' | 'white' | 'silver' | 'midnight'
  | 'forest' | 'ember' | 'arctic' | 'rose' | 'abyss'

export type AccentId = 'blue' | 'green' | 'yellow' | 'pink' | 'red'

export interface UiSettings {
  theme: ThemeId
  accent: AccentId
  /** 0 means automatic; otherwise a percentage (75 to 150) of the automatic size. */
  uiScale: number
  animations: boolean
  reduceTransparency: boolean
  /** Replace Minecraft's title screen with the Breeze menu. */
  replaceTitleScreen: boolean
  /** Dim the world behind the menu in game, percent. */
  backdropDim: number
}

export interface GameState {
  inWorld: boolean
  singleplayer: boolean
  worldName: string | null
  serverName: string | null
  serverAddress: string | null
  playerName: string
  dimension: string | null
  fps: number
  pingMs: number | null
  lastServer: { name: string; address: string } | null
}

export type SettingValue = boolean | number | string

export type ModuleSetting =
  | { type: 'bool'; id: string; label: string; group: string; value: boolean }
  | { type: 'int'; id: string; label: string; group: string; value: number; min: number; max: number; suffix: string }
  | { type: 'color'; id: string; label: string; group: string; value: string }
  | { type: 'mode'; id: string; label: string; group: string; value: string; options: string[] }

export type ModuleCategory = 'HUD' | 'Visual' | 'Utility' | 'Chat' | 'Performance' | 'PvP'

export interface ModuleInfo {
  name: string
  category: ModuleCategory
  description: string
  enabled: boolean
  hud: boolean
  keybind: string | null
  settings: ModuleSetting[]
}

export interface InstalledMod {
  id: string
  name: string
  version: string
  description: string
  authors: string[]
  builtin: boolean
}

export type Role = 'owner' | 'developer' | 'creator' | 'user'

export interface Account {
  playerName: string
  uuid: string
  /** State of the Breeze game session the launcher handed over. */
  breeze: 'ready' | 'signing-in' | 'signed-out' | 'unreachable'
  role: Role | null
}

export interface Cape {
  id: string
  name: string
  /** Texture served by the Breeze API, as an https URL or a data: URL. */
  preview: string | null
  equipped: boolean
}

export interface CosmeticState {
  capes: Cape[]
  equippedCapeId: string | null
}

/** Only what /friends/list actually returns: no server, no role. */
export interface Friend {
  uuid: string
  name: string
  online: boolean
}

export interface FriendsState {
  friends: Friend[]
  incoming: { uuid: string; name: string }[]
  outgoing: { uuid: string; name: string }[]
  /** Epoch millis of the last successful poll, or null before the first. */
  updatedAt: number | null
  /** The last thing the friends client reported, in its own words. */
  status: string
}

export interface HostingState {
  hosting: boolean
  busy: boolean
  /** A singleplayer world is open and the Breeze session is signed in. */
  canHost: boolean
  status: string
  invites: { host: string; hostName: string }[]
}

export interface ActionMap {
  'app.hello': [Record<string, never>, Hello]
  'app.openExternal': [{ url: string }, Record<string, never>]
  'ui.close': [Record<string, never>, Record<string, never>]
  'ui.escapeAck': [{ handled: boolean }, Record<string, never>]
  'ui.route': [{ route: string }, Record<string, never>]
  'ui.vanillaMenu': [Record<string, never>, Record<string, never>]
  'game.state': [Record<string, never>, GameState]
  'game.singleplayer': [Record<string, never>, Record<string, never>]
  'game.multiplayer': [Record<string, never>, Record<string, never>]
  'game.joinServer': [{ address: string; name?: string }, Record<string, never>]
  'game.options': [Record<string, never>, Record<string, never>]
  'game.pauseMenu': [Record<string, never>, Record<string, never>]
  'game.quit': [Record<string, never>, Record<string, never>]
  'settings.get': [Record<string, never>, UiSettings]
  'settings.set': [{ key: keyof UiSettings; value: unknown }, UiSettings]
  'settings.reset': [Record<string, never>, UiSettings]
  'modules.list': [Record<string, never>, ModuleInfo[]]
  'modules.setEnabled': [{ name: string; enabled: boolean }, ModuleInfo]
  'modules.setSetting': [{ name: string; id: string; value: SettingValue }, ModuleInfo]
  'modules.reset': [{ name: string }, ModuleInfo]
  'hud.openEditor': [Record<string, never>, Record<string, never>]
  'mods.list': [Record<string, never>, InstalledMod[]]
  'account.get': [Record<string, never>, Account]
  'cosmetics.state': [Record<string, never>, CosmeticState]
  'cosmetics.equipCape': [{ id: string | null }, CosmeticState]
  'friends.list': [Record<string, never>, FriendsState]
  'friends.request': [{ name: string }, FriendsState]
  'friends.respond': [{ uuid: string; accept: boolean }, FriendsState]
  'friends.remove': [{ uuid: string }, FriendsState]
  'hosting.state': [Record<string, never>, HostingState]
  'hosting.start': [{ invite: string[] }, HostingState]
  'hosting.stop': [Record<string, never>, HostingState]
  'hosting.join': [{ host: string }, HostingState]
}

export type Action = keyof ActionMap
export type ParamsOf<A extends Action> = ActionMap[A][0]
export type ResultOf<A extends Action> = ActionMap[A][1]

export interface EventMap {
  'key.escape': Record<string, never>
  'settings.changed': UiSettings
  'modules.changed': { name: string }
  'game.changed': GameState
  'window.resized': { width: number; height: number; scale: number }
}

export type EventName = keyof EventMap

export type ErrorCode =
  | 'BAD_REQUEST' | 'UNKNOWN_ACTION' | 'INVALID_PARAMS' | 'UNAVAILABLE' | 'FORBIDDEN'
  | 'TIMEOUT' | 'BUSY' | 'DUPLICATE' | 'CANCELLED' | 'INTERNAL'
