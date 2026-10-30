import type { ModuleInfo, ModuleSetting } from '../bridge/types'

export interface ModuleHit {
  module: ModuleInfo
  /** Settings inside the module whose label or group matched. */
  settings: ModuleSetting[]
  /** Whether the module's own name or description matched. */
  byName: boolean
}

/**
 * Modules for a search: by name or description, or by a setting inside them
 * (its label or its group), so "opacity" finds every module with such a
 * setting even when its name says nothing of it. Modules matched by name come
 * first; otherwise the list keeps its order. Empty query: every module.
 */
export function searchModules(modules: ModuleInfo[], query: string): ModuleHit[] {
  const q = query.trim().toLowerCase()
  if (!q) return modules.map((module) => ({ module, settings: [], byName: true }))
  const out: ModuleHit[] = []
  for (const module of modules) {
    const byName = module.name.toLowerCase().includes(q) || module.description.toLowerCase().includes(q)
    const settings = module.settings.filter((s) => s.label.toLowerCase().includes(q) || (s.group ?? '').toLowerCase().includes(q))
    if (byName || settings.length > 0) out.push({ module, settings, byName })
  }
  return out.sort((a, b) => Number(b.byName) - Number(a.byName))
}

/** "Background colour, Border colour and 2 more": what a card says it matched. */
export function matchSummary(settings: ModuleSetting[], shown = 2): string {
  const names = settings.map((s) => s.label)
  if (names.length <= shown + 1) return names.length <= 1 ? (names[0] ?? '') : `${names.slice(0, -1).join(', ')} and ${names.at(-1)}`
  return `${names.slice(0, shown).join(', ')} and ${names.length - shown} more`
}
