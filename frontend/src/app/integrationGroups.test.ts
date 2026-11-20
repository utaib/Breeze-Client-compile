import { describe, expect, it } from 'vitest'
import type { InstalledMod, Integration } from '../bridge/types'
import { groupIntegrations, searchGroups, UNOWNED } from './integrationGroups'

const mod = (id: string, name: string): InstalledMod => ({ id, name, version: '1', description: '', authors: [], builtin: false, kind: 'mod' })
const title = (label: string, owner?: string): Integration => ({
  id: `title:${label}`, name: label, provider: 'title-screen',
  actions: [{ id: 'press', label, ...(owner ? { mod: owner } : {}) }],
})
const MODS = [mod('modmenu', 'Mod Menu'), mod('zoomify', 'Zoomify'), mod('essential', 'Essential'), mod('flashback', 'Flashback'), mod('waila', 'WTHIT')]
const MOD_MENU: Integration = {
  id: 'modmenu', name: 'Mod Menu', provider: 'modmenu',
  actions: [
    { id: 'mods', label: 'Mod Menu' },
    { id: 'config:zoomify', label: 'Zoomify settings', mod: 'zoomify' },
    { id: 'config:modmenu', label: 'Mod Menu settings', mod: 'modmenu' },
  ],
}

describe('other mods, grouped per mod', () => {
  it('puts every one of a mod\'s title screen buttons under that mod, in the title screen\'s order', () => {
    const essential = ['Essential', 'Cosmetics', 'Social', 'Pictures', 'Settings', 'Invite', 'Host world', 'Updates'].map((l) => title(l, 'essential'))
    const groups = groupIntegrations([MOD_MENU, ...essential], MODS)
    const g = groups.find((x) => x.key === 'essential')!
    expect(g.name).toBe('Essential')
    expect(g.actions.map((a) => a.label)).toEqual(['Essential', 'Cosmetics', 'Social', 'Pictures', 'Settings', 'Invite', 'Host world', 'Updates'])
    expect(g.actions.every((a) => a.kind === 'button' && a.action === 'press')).toBe(true)
    expect(g.actions[1]!).toMatchObject({ id: 'title:Cosmetics', name: 'Essential: Cosmetics' })
    expect(g.actions[0]!.name).toBe('Essential')
    // Eight buttons, one entry: Mod Menu, Essential, Zoomify.
    expect(groups.map((x) => x.key)).toEqual(['modmenu', 'essential', 'zoomify'])
  })

  it('files a mod\'s Mod Menu settings under the mod, and keeps Mod Menu\'s list first', () => {
    const groups = groupIntegrations([MOD_MENU], MODS)
    expect(groups[0]!).toMatchObject({ key: 'modmenu', name: 'Mod Menu' })
    expect(groups[0]!.actions.map((a) => [a.kind, a.action])).toEqual([['list', 'mods'], ['settings', 'config:modmenu']])
    expect(groups[1]!.actions).toEqual([{ id: 'modmenu', action: 'config:zoomify', label: 'Settings', name: 'Zoomify settings', kind: 'settings' }])
  })

  it('gathers a mod\'s settings, entrypoint and title buttons in one group', () => {
    const groups = groupIntegrations([
      title('Replay Viewer', 'replaymod'),
      { id: 'breeze:replaymod', name: 'Replay Mod', provider: 'breeze-entrypoint', actions: [{ id: 'open', label: 'Replay Mod', mod: 'replaymod' }] },
      { ...MOD_MENU, actions: [{ id: 'config:replaymod', label: 'Replay Mod settings', mod: 'replaymod' }] },
    ], [mod('replaymod', 'Replay Mod')])
    expect(groups).toHaveLength(1)
    expect(groups[0]!.actions.map((a) => `${a.kind}:${a.label}`)).toEqual(['settings:Settings', 'open:Replay Mod', 'button:Replay Viewer'])
  })

  it('a button Java could not credit goes to the mod named like it, or to Title screen buttons, last', () => {
    const groups = groupIntegrations([title('Flashback'), title('Realms News'), title('Some button'), MOD_MENU], MODS)
    expect(groups.map((g) => g.key)).toEqual(['modmenu', 'flashback', 'zoomify', UNOWNED])
    const unowned = groups.at(-1)!
    expect(unowned.name).toBe('Title screen buttons')
    expect(unowned.actions.map((a) => a.name)).toEqual(['Realms News', 'Some button'])
  })

  it('uses Fabric\'s name for a mod, and a readable fallback before the list loads', () => {
    expect(groupIntegrations([title('Overlay', 'waila')], MODS)[0]!.name).toBe('WTHIT')
    expect(groupIntegrations([MOD_MENU])[1]!.name).toBe('Zoomify')
  })

  it('never lists one entry twice, and skips malformed ones', () => {
    const dupe = groupIntegrations([title('A', 'x'), title('A', 'x'), { id: 'bad', name: 'Bad', provider: 'title-screen', actions: [null as never, { id: 'press' } as never] }])
    expect(dupe).toHaveLength(1)
    expect(dupe[0]!.actions).toHaveLength(1)
  })

  it('nothing offered, nothing listed', () => {
    expect(groupIntegrations([], MODS)).toEqual([])
  })
})

describe('searching other mods', () => {
  const groups = groupIntegrations([MOD_MENU, ...['Cosmetics', 'Social', 'Settings'].map((l) => title(l, 'essential')), title('Realms News')], MODS)

  it('a mod\'s name keeps all of its entries', () => {
    const r = searchGroups(groups, 'essen')
    expect(r.map((g) => g.key)).toEqual(['essential'])
    expect(r[0]!.actions).toHaveLength(3)
  })

  it('an entry\'s label keeps only the matching entries, across mods', () => {
    const r = searchGroups(groups, 'settings')
    expect(r.map((g) => [g.key, g.actions.map((a) => a.label)])).toEqual([
      ['modmenu', ['Settings']], ['essential', ['Settings']], ['zoomify', ['Settings']],
    ])
    expect(searchGroups(groups, 'news')[0]!.key).toBe(UNOWNED)
  })

  it('an empty or blank search shows everything; no match shows nothing', () => {
    expect(searchGroups(groups, '  ')).toBe(groups)
    expect(searchGroups(groups, 'nothing like this')).toEqual([])
  })
})
