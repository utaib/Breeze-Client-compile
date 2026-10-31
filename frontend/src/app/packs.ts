import type { PackInfo } from '../bridge/types'

/**
 * The resource pack list as the page edits it before Apply: the ids of the
 * packs on, top first. Java applies it with Minecraft's own rules
 * (PackOrder), so these only keep the page's picture honest: required packs
 * cannot be turned off, fixed packs do not move, a pack turned on goes to
 * the top under any fixed top pack.
 */
export function turnOn(order: string[], id: string, packs: PackInfo[]): string[] {
  if (order.includes(id)) return order
  let at = 0
  order.forEach((o, i) => {
    const p = packs.find((k) => k.id === o)
    if (p?.fixed && p.top) at = i + 1
  })
  return [...order.slice(0, at), id, ...order.slice(at)]
}

export function turnOff(order: string[], id: string, packs: PackInfo[]): string[] {
  if (packs.find((p) => p.id === id)?.required) return order
  return order.filter((o) => o !== id)
}

/** Moves an on pack up (-1) or down (1) past the next movable one; fixed packs stay put. */
export function move(order: string[], id: string, by: -1 | 1, packs: PackInfo[]): string[] {
  const fixed = (o: string) => packs.find((p) => p.id === o)?.fixed ?? false
  const i = order.indexOf(id)
  const other = order[i + by]
  if (i < 0 || fixed(id) || other === undefined || fixed(other)) return order
  const next = [...order]
  next[i] = other
  next[i + by] = id
  return next
}

export function sameOrder(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((v, i) => v === b[i])
}

/** Packs not on, in Minecraft's order, matching a search on title, description or file name. */
export function offPacks(packs: PackInfo[], order: string[], query: string): PackInfo[] {
  const q = query.trim().toLowerCase()
  return packs.filter((p) => !order.includes(p.id)
    && (q === '' || p.title.toLowerCase().includes(q) || p.description.toLowerCase().includes(q) || p.id.toLowerCase().includes(q)))
}
