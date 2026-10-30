import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { argbCss, fromSettings, rects, type CrosshairConfig } from './crosshair'
import type { ModuleSetting } from '../bridge/types'

// The same examples the game's Java is tested against.
const contract = JSON.parse(readFileSync(resolve(process.cwd(), '../contract/crosshair.json'), 'utf8'))

interface Example {
  name: string
  config: Omit<CrosshairConfig, 'color' | 'outlineColor'> & { color: string; outlineColor: string }
  rects: [number, number, number, number, string][]
}

describe('crosshair preview shape', () => {
  it('draws every contract example exactly as the game does', () => {
    expect(contract.examples.length).toBeGreaterThan(2)
    for (const ex of contract.examples as Example[]) {
      const cfg: CrosshairConfig = { ...ex.config, color: `#${ex.config.color}`, outlineColor: `#${ex.config.outlineColor}` }
      const got = rects(cfg).map(([a, b, c, d, col]) => [a, b, c, d, col.replace('#', '').toLowerCase()])
      expect(got, ex.name).toEqual(ex.rects)
    }
  })

  it('brings settings out of range back into range, as the game does', () => {
    const wild = rects({ style: 'Star', length: 500, thickness: 0, gap: -4, color: '#FFFFFFFF', dot: false, outline: false, outlineThickness: 9, outlineColor: '#FF000000' })
    const tame = rects({ style: 'Cross', length: 20, thickness: 1, gap: 0, color: '#FFFFFFFF', dot: false, outline: false, outlineThickness: 3, outlineColor: '#FF000000' })
    expect(wild).toEqual(tame)
  })

  it('reads the module settings by their ids', () => {
    const settings = [
      { type: 'mode', id: 'style', label: 'Style', group: 'Crosshair', value: 'T', options: ['Cross', 'T', 'Dot'] },
      { type: 'int', id: 'length', label: 'Length', group: 'Crosshair', value: 7, min: 1, max: 20, suffix: ' px' },
      { type: 'bool', id: 'outline', label: 'Outline', group: 'Outline', value: false },
      { type: 'color', id: 'outline_color', label: 'Outline colour', group: 'Outline', value: '#80000000' },
    ] as ModuleSetting[]
    const cfg = fromSettings(settings)
    expect(cfg).toMatchObject({ style: 'T', length: 7, outline: false, outlineColor: '#80000000', thickness: 1, gap: 2 })
  })

  it('turns ARGB into a CSS colour', () => {
    expect(argbCss('#80FF0000')).toBe('rgba(255, 0, 0, 0.502)')
    expect(argbCss('nonsense')).toBe('transparent')
  })
})
