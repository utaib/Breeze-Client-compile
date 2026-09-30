import type { Action } from './types'

/**
 * Every action, as a runtime list. The Record below makes this exhaustive at
 * compile time: adding an action to ActionMap without listing it here fails
 * the type check, and src/bridge/contract.test.ts compares the list with
 * contract/bridge.json.
 */
const ALL: Record<Action, true> = {
  'app.hello': true, 'app.openExternal': true,
  'ui.close': true, 'ui.escapeAck': true, 'ui.route': true, 'ui.vanillaMenu': true,
  'game.state': true, 'game.singleplayer': true, 'game.multiplayer': true, 'game.joinServer': true,
  'game.options': true, 'game.pauseMenu': true, 'game.quit': true,
  'settings.get': true, 'settings.set': true, 'settings.reset': true,
  'modules.list': true, 'modules.setEnabled': true, 'modules.setSetting': true, 'modules.reset': true,
  'hud.openEditor': true, 'mods.list': true, 'account.get': true,
  'cosmetics.state': true, 'cosmetics.equipCape': true,
  'friends.list': true, 'friends.request': true, 'friends.respond': true, 'friends.remove': true,
  'hosting.state': true, 'hosting.start': true, 'hosting.stop': true, 'hosting.join': true,
}

export const ACTIONS = Object.keys(ALL) as Action[]
