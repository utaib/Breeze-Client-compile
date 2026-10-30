/**
 * The Custom Crosshair's shape for the menu's preview: the same rules as the
 * game's (common/.../hud/Crosshair.java), checked against the same examples
 * (contract/crosshair.json). Rectangles around the centre pixel in GUI
 * pixels, end not included, outline first. Share codes are made and read in
 * Java only (crosshair.export / crosshair.import).
 */
import type { ModuleSetting } from '../bridge/types'

export const STYLES = ['Cross', 'T', 'Dot'] as const
export const RANGE = {
  length: [1, 20],
  thickness: [1, 8],
  gap: [0, 12],
  outlineThickness: [1, 3],
} as const

export interface CrosshairConfig {
  style: string
  length: number
  thickness: number
  gap: number
  /** ARGB, as #AARRGGBB */
  color: string
  dot: boolean
  outline: boolean
  outlineThickness: number
  outlineColor: string
}

/** [x0, y0, x1, y1, #AARRGGBB] */
export type Rect = [number, number, number, number, string]

const clamp = (v: number, [min, max]: readonly [number, number]) => Math.max(min, Math.min(max, Math.round(v)))

export function rects(raw: CrosshairConfig): Rect[] {
  const style = STYLES.find((s) => s.toLowerCase() === raw.style.toLowerCase()) ?? 'Cross'
  const t = clamp(raw.thickness, RANGE.thickness)
  const l = clamp(raw.length, RANGE.length)
  const g = clamp(raw.gap, RANGE.gap)
  const s = 0 - Math.trunc(t / 2) // not -Math.trunc, which is -0 for 1
  const e = s + t
  const c = raw.color
  const shape: Rect[] = []
  if (style === 'Dot') {
    shape.push([s, s, e, e, c])
  } else {
    shape.push([s - g - l, s, s - g, e, c])
    shape.push([e + g, s, e + g + l, e, c])
    if (style !== 'T') shape.push([s, s - g - l, e, s - g, c])
    shape.push([s, e + g, e, e + g + l, c])
    if (raw.dot) shape.push([s, s, e, e, c])
  }
  if (!raw.outline) return shape
  const o = clamp(raw.outlineThickness, RANGE.outlineThickness)
  return [...shape.map(([x0, y0, x1, y1]): Rect => [x0 - o, y0 - o, x1 + o, y1 + o, raw.outlineColor]), ...shape]
}

/** The crosshair's settings as the module page holds them. */
export function fromSettings(settings: ModuleSetting[]): CrosshairConfig {
  const get = (id: string) => settings.find((s) => s.id === id)?.value
  return {
    style: String(get('style') ?? 'Cross'),
    length: Number(get('length') ?? 4),
    thickness: Number(get('thickness') ?? 1),
    gap: Number(get('gap') ?? 2),
    color: String(get('color') ?? '#FFFFFFFF'),
    dot: Boolean(get('dot')),
    outline: get('outline') !== false,
    outlineThickness: Number(get('outline_thickness') ?? 1),
    outlineColor: String(get('outline_color') ?? '#FF000000'),
  }
}

/** #AARRGGBB to a CSS colour. */
export function argbCss(hex: string): string {
  const m = /^#?([0-9a-f]{8})$/i.exec(hex)
  if (!m) return 'transparent'
  const n = m[1]!
  const a = parseInt(n.slice(0, 2), 16) / 255
  return `rgba(${parseInt(n.slice(2, 4), 16)}, ${parseInt(n.slice(4, 6), 16)}, ${parseInt(n.slice(6, 8), 16)}, ${a.toFixed(3)})`
}
