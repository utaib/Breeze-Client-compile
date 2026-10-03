import { describe, expect, it } from 'vitest'
import { autoScale, effectiveScale } from './App'
import { parseRoute, routeKey, type Route } from './state'
import { DEFAULT_SETTINGS } from '../dev/fixture'

describe('routes', () => {
  const all: Route[] = [
    { name: 'home' }, { name: 'hosting' }, { name: 'wardrobe' }, { name: 'friends' }, { name: 'settings' },
    { name: 'mods', tab: 'modules' }, { name: 'mods', tab: 'installed' }, { name: 'module', module: 'Armor Status' },
  ]
  it('round-trips every route through its key', () => {
    for (const r of all) expect(parseRoute(routeKey(r))).toEqual(r)
  })
  it('falls back to home for anything unknown or empty', () => {
    expect(parseRoute(null)).toEqual({ name: 'home' })
    expect(parseRoute('')).toEqual({ name: 'home' })
    expect(parseRoute('../../etc')).toEqual({ name: 'home' })
    expect(parseRoute('module/')).toEqual({ name: 'mods', tab: 'modules' })
  })
  it('keeps module names containing slashes intact', () => {
    expect(parseRoute('module/A/B')).toEqual({ name: 'module', module: 'A/B' })
  })
})

describe('interface scale', () => {
  it('is 1 near 1100x680 and grows with the window', () => {
    expect(autoScale(1100, 680)).toBe(1)
    expect(autoScale(1920, 1080)).toBeCloseTo(1.6, 5)
    expect(autoScale(2560, 1440)).toBeCloseTo(2.1, 5)
  })
  it('never goes below readable at the default 854x480 window', () => {
    expect(autoScale(854, 480)).toBe(0.85)
    expect(autoScale(320, 200)).toBe(0.85)
  })
  it('caps very large windows', () => {
    expect(autoScale(7680, 4320)).toBe(2.5)
  })
  it('applies a custom size relative to automatic', () => {
    expect(effectiveScale({ ...DEFAULT_SETTINGS, uiScale: 0 }, 1100, 680)).toBe(1)
    expect(effectiveScale({ ...DEFAULT_SETTINGS, uiScale: 150 }, 1100, 680)).toBe(1.5)
    expect(effectiveScale({ ...DEFAULT_SETTINGS, uiScale: 75 }, 1920, 1080)).toBe(1.2)
  })
})
