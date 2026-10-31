import { describe, expect, it } from 'vitest'
import type { ModuleInfo, ModuleSetting } from '../bridge/types'
import { matchSummary, searchModules } from './moduleSearch'

const s = (id: string, label: string, group = 'General'): ModuleSetting => ({ type: 'bool', id, label, group, value: false })
const m = (name: string, description: string, settings: ModuleSetting[] = []): ModuleInfo =>
  ({ name, category: 'HUD', description, enabled: false, hud: true, keybind: null, settings })

const MODULES = [
  m('FPS', 'Frames per second.', [s('bgColor', 'Background colour', 'Appearance'), s('textShadow', 'Text shadow', 'Text')]),
  m('Zoom', 'Zoom in with a key.', [s('smooth', 'Smooth zoom')]),
  m('Opacity Tester', 'A module named after the search.'),
  m('Coordinates', 'Where you are.', [s('bgColor', 'Background colour', 'Appearance'), s('dir', 'Show direction')]),
]

describe('searching modules and their settings', () => {
  it('finds a module by a setting inside it, saying which', () => {
    const hits = searchModules(MODULES, 'background')
    expect(hits.map((h) => [h.module.name, h.byName, h.settings.map((x) => x.id)])).toEqual([
      ['FPS', false, ['bgColor']], ['Coordinates', false, ['bgColor']],
    ])
  })

  it('matches a setting\'s group as well as its label', () => {
    expect(searchModules(MODULES, 'appearance').map((h) => h.module.name)).toEqual(['FPS', 'Coordinates'])
  })

  it('puts modules matched by name first, and keeps the setting matches inside them', () => {
    const hits = searchModules(MODULES, 'zoom')
    expect(hits.map((h) => h.module.name)).toEqual(['Zoom'])
    expect(hits[0]!.byName).toBe(true)
    expect(hits[0]!.settings.map((x) => x.label)).toEqual(['Smooth zoom'])
    // FPS comes first in the list but matched only by a setting.
    const mixed = searchModules([...MODULES, m('Background Image', 'A picture behind the menu.')], 'background')
    expect(mixed.map((h) => h.module.name)).toEqual(['Background Image', 'FPS', 'Coordinates'])
  })

  it('ignores case and spaces around the search; empty shows all; nothing matches nothing', () => {
    expect(searchModules(MODULES, '  TEXT SHADOW ').map((h) => h.module.name)).toEqual(['FPS'])
    expect(searchModules(MODULES, '').map((h) => h.module.name)).toEqual(MODULES.map((x) => x.name))
    expect(searchModules(MODULES, 'no such thing')).toEqual([])
  })
})

describe('what a card says it matched', () => {
  it('names one, two, three, then counts the rest', () => {
    const list = ['A', 'B', 'C', 'D', 'E'].map((l) => s(l, l))
    expect(matchSummary(list.slice(0, 1))).toBe('A')
    expect(matchSummary(list.slice(0, 2))).toBe('A and B')
    expect(matchSummary(list.slice(0, 3))).toBe('A, B and C')
    expect(matchSummary(list)).toBe('A, B and 3 more')
    expect(matchSummary([])).toBe('')
  })
})
