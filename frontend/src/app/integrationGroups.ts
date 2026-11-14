import type { InstalledMod, Integration } from '../bridge/types'

/**
 * What other mods offer in this game, one group per mod: its settings screen
 * through Mod Menu, its own "breeze" entrypoint, and the buttons it put on the
 * title screen (Essential puts several). Mod Menu's list is Mod Menu's own
 * group. Title screen buttons whose mod Java could not tell share one group.
 * However many mods there are, the menu shows one entry for all of them.
 */
export type GroupActionKind = 'list' | 'settings' | 'open' | 'button'

export interface GroupAction {
  /** The integrations.open params. */
  id: string
  action: string
  /** What the button says. */
  label: string
  /** Its accessible name, which says which mod it belongs to. */
  name: string
  kind: GroupActionKind
}

export interface IntegrationGroup {
  /** The mod id, or UNOWNED for title screen buttons of no known mod. */
  key: string
  name: string
  actions: GroupAction[]
}

export const UNOWNED = 'title-screen'
const MOD_MENU = 'modmenu'
const KIND_ORDER: Record<GroupActionKind, number> = { list: 0, settings: 1, open: 2, button: 3 }

export function groupIntegrations(integrations: Integration[], mods: InstalledMod[] = []): IntegrationGroup[] {
  const names = new Map(mods.map((m) => [m.id, m.name]))
  const byName = new Map(mods.map((m) => [m.name.trim().toLowerCase(), m.id]))
  const groups = new Map<string, IntegrationGroup>()
  const add = (key: string, fallback: string, a: Omit<GroupAction, 'name'>) => {
    let g = groups.get(key)
    if (!g) {
      g = { key, name: names.get(key) ?? fallback, actions: [] }
      groups.set(key, g)
    }
    if (g.actions.some((x) => x.id === a.id && x.action === a.action)) return
    g.actions.push({ ...a, name: '' })
  }

  for (const i of integrations) {
    for (const a of i.actions ?? []) {
      if (!a || typeof a.id !== 'string' || typeof a.label !== 'string') continue
      if (i.provider === 'modmenu') {
        if (a.mod) add(a.mod, a.label.replace(/ settings$/, ''), { id: i.id, action: a.id, label: 'Settings', kind: 'settings' })
        else add(i.id, i.name, { id: i.id, action: a.id, label: 'Mod list', kind: 'list' })
      } else if (i.provider === 'breeze-entrypoint') {
        add(a.mod ?? i.id, i.name, { id: i.id, action: a.id, label: a.label, kind: 'open' })
      } else {
        // A title screen button: its mod as Java told it, else a mod whose
        // name is the label itself (Flashback's button says "Flashback").
        const owner = a.mod ?? byName.get(a.label.trim().toLowerCase())
        add(owner ?? UNOWNED, owner ?? 'Title screen buttons', { id: i.id, action: a.id, label: a.label, kind: 'button' })
      }
    }
  }

  const out = [...groups.values()]
  for (const g of out) {
    // Stable: buttons keep the title screen's order.
    g.actions.sort((x, y) => KIND_ORDER[x.kind] - KIND_ORDER[y.kind])
    for (const a of g.actions) a.name = accessibleName(g, a)
  }
  const rank = (g: IntegrationGroup) => (g.key === MOD_MENU ? 0 : g.key === UNOWNED ? 2 : 1)
  return out.sort((x, y) => rank(x) - rank(y) || x.name.localeCompare(y.name, undefined, { sensitivity: 'base' }))
}

function accessibleName(g: IntegrationGroup, a: GroupAction): string {
  if (a.kind === 'settings') return `${g.name} settings`
  if (a.kind === 'list') return `${g.name}: mod list`
  if (g.key === UNOWNED || a.label.toLowerCase().includes(g.name.toLowerCase())) return a.label
  return `${g.name}: ${a.label}`
}

/**
 * Groups for a search: a group whose mod matches keeps everything, otherwise
 * only the entries whose label matches. Empty query: everything.
 */
export function searchGroups(groups: IntegrationGroup[], query: string): IntegrationGroup[] {
  const q = query.trim().toLowerCase()
  if (!q) return groups
  const out: IntegrationGroup[] = []
  for (const g of groups) {
    if (g.name.toLowerCase().includes(q) || (g.key !== UNOWNED && g.key.toLowerCase().includes(q))) {
      out.push(g)
      continue
    }
    const actions = g.actions.filter((a) => a.label.toLowerCase().includes(q))
    if (actions.length > 0) out.push({ ...g, actions })
  }
  return out
}
