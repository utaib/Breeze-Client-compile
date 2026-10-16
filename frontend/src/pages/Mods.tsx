import { useMemo, useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { ModuleCategory, ModuleInfo } from '../bridge/types'
import { EmptyState, ErrorState, SearchField, Segmented, Skeleton, Switch } from '../ui/controls'
import { Icon, type IconName } from '../ui/icons'
import { ModuleGlyph, useModuleIcons } from '../ui/ModuleGlyph'

/** Category colours are the five Breeze state colours, used as labels only. */
export const CATEGORY: Record<ModuleCategory, { icon: IconName; tone: string }> = {
  HUD: { icon: 'Layout', tone: 'blue' },
  Visual: { icon: 'Eye', tone: 'pink' },
  Utility: { icon: 'Wrench', tone: 'green' },
  Performance: { icon: 'Bolt', tone: 'yellow' },
  PvP: { icon: 'Sword', tone: 'red' },
  Chat: { icon: 'Chat', tone: 'blue' },
}

const FILTERS = ['All', 'On', 'HUD', 'Visual', 'Utility', 'Performance', 'PvP', 'Chat'] as const
type Filter = (typeof FILTERS)[number]

export function Mods({ tab }: { tab: 'modules' | 'installed' }) {
  const { navigate } = useApp()
  return (
    <div className="page page-enter">
      <div className="page-head">
        <div>
          <h1 className="page-title">Mods</h1>
          <p className="page-sub">Breeze modules change how the game looks and plays. Installed mods are everything Fabric loaded.</p>
        </div>
        <Segmented
          label="Section"
          value={tab}
          options={[{ value: 'modules', label: 'Breeze modules' }, { value: 'installed', label: 'Installed mods' }]}
          onChange={(t) => navigate({ name: 'mods', tab: t })}
        />
      </div>
      {tab === 'modules' ? <Modules /> : <Installed />}
    </div>
  )
}

function Modules() {
  const { call, toast, navigate, hello } = useApp()
  const list = useAction('modules.list')
  const icons = useModuleIcons()
  const [query, setQuery] = useState('')
  const [filter, setFilter] = useState<Filter>('All')

  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return (list.data ?? []).filter((m) => {
      if (filter === 'On' && !m.enabled) return false
      if (filter !== 'All' && filter !== 'On' && m.category !== filter) return false
      if (!q) return true
      return m.name.toLowerCase().includes(q) || m.description.toLowerCase().includes(q)
    })
  }, [list.data, query, filter])

  const present = useMemo(() => new Set((list.data ?? []).map((m) => m.category)), [list.data])

  const toggle = async (m: ModuleInfo, enabled: boolean) => {
    list.setData((all) => all?.map((x) => (x.name === m.name ? { ...x, enabled } : x)) ?? all)
    try {
      const updated = await call('modules.setEnabled', { name: m.name, enabled })
      list.setData((all) => all?.map((x) => (x.name === m.name ? updated : x)) ?? all)
    } catch (err) {
      list.setData((all) => all?.map((x) => (x.name === m.name ? { ...x, enabled: !enabled } : x)) ?? all)
      toast(`${m.name} could not be changed. ${describeError(err)}`, 'error')
    }
  }

  return (
    <>
      <div className="toolbar">
        <SearchField value={query} onChange={setQuery} placeholder="Search modules" autoFocus />
        <div className="chips" role="tablist" aria-label="Filter">
          {FILTERS.filter((f) => f === 'All' || f === 'On' || present.has(f as ModuleCategory)).map((f) => (
            <button key={f} role="tab" aria-selected={filter === f} className="chip" onClick={() => setFilter(f)}>{f}</button>
          ))}
        </div>
        {/* The HUD editor moves the real HUD, so it needs a world to show it in. */}
        {hello.context === 'ingame' && (
          <button className="btn btn-secondary btn-sm toolbar-end" onClick={() => call('hud.openEditor').catch((err) => toast(describeError(err), 'error'))}>
            <Icon.Layout />Edit HUD layout
          </button>
        )}
      </div>

      {list.loading && !list.data && <div className="module-grid"><Skeleton height="7.5rem" count={6} /></div>}
      {list.error != null && <ErrorState error={list.error} what="modules" onRetry={list.reload} />}
      {list.data && shown.length === 0 && (
        <EmptyState
          title={query ? `Nothing matches "${query}"` : 'No modules here'}
          text={query ? 'Try a shorter search, or pick All.' : 'Turn a module on and it shows up under On.'}
        />
      )}

      <div className="module-grid" role="list">
        {shown.map((m) => {
          const cat = CATEGORY[m.category] ?? CATEGORY.Utility
          return (
            <div
              key={m.name}
              role="listitem"
              className={`module ${m.enabled ? 'on' : ''}`}
              data-testid={`module-${m.name}`}
            >
              <button className="module-open" onClick={() => navigate({ name: 'module', module: m.name })} aria-label={`${m.name} settings`}>
                <ModuleGlyph name={m.name} tone={cat.tone} symbol={cat.icon} icon={icons[m.name]} />
                <span className="module-name">{m.name}</span>
                <span className="module-desc">{m.description}</span>
                <span className="module-foot">
                  <span className={`module-cat tone-${cat.tone}`}>{m.category}</span>
                  {m.settings.length > 0 && <span className="module-count">{m.settings.length} settings</span>}
                  {m.keybind && <span className="module-key mono">{m.keybind}</span>}
                </span>
              </button>
              <div className="module-switch"><Switch checked={m.enabled} label={`${m.name} on`} onChange={(v) => toggle(m, v)} /></div>
            </div>
          )
        })}
      </div>
    </>
  )
}

const KIND_TAG: Record<string, string> = { game: 'Built in', library: 'Library', breeze: 'Breeze' }

function Installed() {
  const { call, toast } = useApp()
  const mods = useAction('mods.list')
  const integrations = useAction('integrations.list')
  const [query, setQuery] = useState('')
  // A mod's settings screen, when Mod Menu offers one for it.
  const settingsFor = useMemo(() => {
    const out = new Map<string, { id: string; action: string }>()
    for (const i of integrations.data?.integrations ?? []) {
      for (const a of i.actions) if (a.mod && a.id.startsWith('config:')) out.set(a.mod, { id: i.id, action: a.id })
    }
    return out
  }, [integrations.data])
  const shown = (mods.data ?? []).filter((m) => {
    const q = query.trim().toLowerCase()
    return !q || m.name.toLowerCase().includes(q) || m.id.includes(q)
  })
  const open = (target: { id: string; action: string }) => {
    call('integrations.open', target).catch((err) => toast(describeError(err), 'error'))
  }
  return (
    <>
      <div className="toolbar">
        <SearchField value={query} onChange={setQuery} placeholder="Search installed mods" />
        {mods.data && (() => {
          // Everything Fabric loaded from jars: Breeze, libraries and mods, not the game itself.
          const n = mods.data.filter((m) => (m.kind ?? (m.builtin ? 'game' : 'mod')) !== 'game').length
          return <span className="toolbar-count">{n} {n === 1 ? 'mod' : 'mods'} loaded</span>
        })()}
      </div>
      {(integrations.data?.problems ?? []).map((p) => (
        <div className="integration-problem" role="status" key={`${p.provider}/${p.mod}/${p.detail}`}>
          <Icon.Alert />{p.detail}
        </div>
      ))}
      {mods.loading && !mods.data && <Skeleton height="3.25rem" count={5} gap="1px" />}
      {mods.error != null && <ErrorState error={mods.error} what="installed mods" onRetry={mods.reload} />}
      {mods.data && (
        <div className="rows" role="list">
          {shown.map((m) => {
            const kind = m.kind ?? (m.builtin ? 'game' : 'mod')
            const settings = settingsFor.get(m.id)
            return (
              <div className="row" role="listitem" key={m.id} data-testid={`installed-${m.id}`}>
                <div className="row-text">
                  <div className="row-label">{m.name} <span className="mono installed-ver">{m.version}</span></div>
                  {(m.description || m.authors.length > 0) && (
                    <div className="row-desc">{m.description}{m.authors.length > 0 && ` By ${m.authors.join(', ')}.`}</div>
                  )}
                </div>
                {KIND_TAG[kind] && <span className="installed-tag">{KIND_TAG[kind]}</span>}
                {settings && (
                  <button className="btn btn-ghost btn-sm" onClick={() => open(settings)} aria-label={`${m.name} settings`}>
                    <Icon.Sliders />Settings
                  </button>
                )}
              </div>
            )
          })}
          {shown.length === 0 && <div className="row"><div className="row-desc">Nothing matches "{query}".</div></div>}
        </div>
      )}
    </>
  )
}
