import { useEffect, useMemo, useState } from 'react'
import { groupIntegrations, searchGroups, UNOWNED, type GroupAction, type IntegrationGroup } from '../app/integrationGroups'
import { matchSummary, searchModules } from '../app/moduleSearch'
import { describeError, useAction, useApp, type ModsTab } from '../app/state'
import type { ModuleCategory, ModuleInfo } from '../bridge/types'
import { EmptyState, ErrorState, SearchField, Segmented, Skeleton, Switch } from '../ui/controls'
import { Icon, type IconName } from '../ui/icons'
import { ModuleGlyph, useModuleIcons } from '../ui/ModuleGlyph'
import { KeysTab } from './Keys'

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

const SUB: Record<ModsTab, string> = {
  modules: 'Breeze modules change how the game looks and plays.',
  keys: 'Every Breeze key in one place. Click one, then press the key you want.',
  other: 'Settings and screens other mods offer here, one entry per mod.',
  installed: 'Everything Fabric loaded, with each mod\'s settings where it has them.',
}

export function Mods({ tab }: { tab: ModsTab }) {
  const { navigate } = useApp()
  return (
    <div className="page page-enter">
      <div className="page-head">
        <div>
          <h1 className="page-title">Mods</h1>
          <p className="page-sub">{SUB[tab]}</p>
        </div>
        <Segmented
          label="Section"
          value={tab}
          options={[
            { value: 'modules', label: 'Breeze modules' }, { value: 'keys', label: 'Keys' },
            { value: 'other', label: 'Other mods' }, { value: 'installed', label: 'Installed mods' },
          ]}
          onChange={(t) => navigate({ name: 'mods', tab: t })}
        />
      </div>
      {tab === 'modules' ? <Modules /> : tab === 'keys' ? <KeysTab /> : tab === 'other' ? <OtherMods /> : <Installed />}
    </div>
  )
}

function Modules() {
  const { call, toast, navigate, hello, route } = useApp()
  const list = useAction('modules.list')
  const icons = useModuleIcons()
  // Back from a module returns to the same search and filter.
  const kept = route.name === 'mods' ? route.view : undefined
  const [query, setQuery] = useState(kept?.query ?? '')
  const [filter, setFilter] = useState<Filter>(FILTERS.includes(kept?.filter as Filter) ? (kept!.filter as Filter) : 'All')
  useEffect(() => {
    if (route.name === 'mods') route.view = { query, filter }
  }, [route, query, filter])

  // Name, description, or a setting inside the module.
  const shown = useMemo(() => searchModules(list.data ?? [], query).filter(({ module: m }) => {
    if (filter === 'On' && !m.enabled) return false
    if (filter !== 'All' && filter !== 'On' && m.category !== filter) return false
    return true
  }), [list.data, query, filter])

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
        <SearchField value={query} onChange={setQuery} placeholder="Search modules and settings" autoFocus />
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
          text={query ? 'Searches cover module names and every setting inside them. Try a shorter search, or pick All.' : 'Turn a module on and it shows up under On.'}
        />
      )}

      <div className="module-grid" role="list">
        {shown.map(({ module: m, settings: matched }) => {
          const cat = CATEGORY[m.category] ?? CATEGORY.Utility
          return (
            <div
              key={m.name}
              role="listitem"
              className={`module ${m.enabled ? 'on' : ''}`}
              data-testid={`module-${m.name}`}
            >
              <button
                className="module-open"
                onClick={() => navigate({ name: 'module', module: m.name, ...(matched[0] ? { focus: matched[0].id } : {}) })}
                aria-label={`${m.name} settings`}
              >
                <ModuleGlyph name={m.name} tone={cat.tone} symbol={cat.icon} icon={icons[m.name]} />
                <span className="module-name">{m.name}</span>
                {matched.length > 0
                  ? <span className="module-desc module-match" data-testid="module-match"><Icon.Search />{matchSummary(matched)}</span>
                  : <span className="module-desc">{m.description}</span>}
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

/** Entries a mod shows before "Show all"; one more than this shows them all. */
const FIRST = 6

function describeGroup(g: IntegrationGroup): string {
  if (g.key === UNOWNED) return 'Buttons mods added to the title screen'
  const n = (k: GroupAction['kind']) => g.actions.filter((a) => a.kind === k).length
  const parts: string[] = []
  if (n('list')) parts.push('Every installed mod, in Mod Menu')
  if (n('settings')) parts.push('Settings through Mod Menu')
  if (n('open')) parts.push('Its own Breeze entry')
  const b = n('button')
  if (b) parts.push(b === 1 ? 'A title screen button' : `${b} title screen buttons`)
  return parts.join(' · ')
}

const ACTION_ICON: Record<GroupAction['kind'], IconName | null> = { list: 'Grid', settings: 'Sliders', open: 'Package', button: null }

/**
 * What other mods offer, one entry per mod, so a mod with many buttons
 * (Essential) is one entry and not a longer rail. Each button opens the mod's
 * own screen over the menu; closing it comes back here.
 */
function OtherMods() {
  const { call, toast, hello } = useApp()
  const integrations = useAction('integrations.list')
  const mods = useAction('mods.list')
  const [query, setQuery] = useState('')
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set())
  const groups = useMemo(() => groupIntegrations(integrations.data?.integrations ?? [], mods.data ?? []), [integrations.data, mods.data])
  const shown = useMemo(() => searchGroups(groups, query), [groups, query])
  const searching = query.trim() !== ''
  const modCount = groups.filter((g) => g.key !== UNOWNED).length
  const shownCount = shown.filter((g) => g.key !== UNOWNED).length
  // Said of the whole mod, also while a search shows only some of its entries.
  const meta = useMemo(() => new Map(groups.map((g) => [g.key, describeGroup(g)])), [groups])
  const press = (a: GroupAction) => {
    call('integrations.open', { id: a.id, action: a.action }).catch((err) => toast(describeError(err), 'error'))
  }
  return (
    <>
      <div className="toolbar">
        <SearchField value={query} onChange={setQuery} placeholder="Search other mods" autoFocus />
        {modCount > 0 && (
          <span className="toolbar-count">{searching ? `${shownCount} of ${modCount}` : modCount} {modCount === 1 ? 'mod' : 'mods'}</span>
        )}
      </div>
      {(integrations.data?.problems ?? []).map((p) => (
        <div className="integration-problem" role="status" key={`${p.provider}/${p.mod}/${p.detail}`}>
          <Icon.Alert />{p.detail}
        </div>
      ))}
      {integrations.loading && !integrations.data && <Skeleton height="4.5rem" count={3} />}
      {integrations.error != null && <ErrorState error={integrations.error} what="other mods" onRetry={integrations.reload} />}
      {integrations.data && groups.length === 0 && (
        <EmptyState
          title="No other mod offers anything here"
          text={hello.context === 'ingame'
            ? 'Settings screens from Mod Menu and mods\' own Breeze entries show here. Buttons mods add to the title screen show at the title screen.'
            : 'Settings screens from Mod Menu, mods\' own Breeze entries and buttons mods add to the title screen show here.'}
        />
      )}
      {groups.length > 0 && shown.length === 0 && (
        <EmptyState title={`Nothing matches "${query}"`} text="Try a mod's name, or a shorter search." />
      )}
      <div className="xmods" role="list">
        {shown.map((g) => {
          const all = searching || expanded.has(g.key) || g.actions.length <= FIRST + 1
          return (
            <section className="xmod" role="listitem" aria-label={g.name} key={g.key} data-testid={`integration-group-${g.key}`}>
              <div className="xmod-head">
                <span className="xmod-name" title={g.name}>{g.name}</span>
                <span className="xmod-meta">{meta.get(g.key)}</span>
              </div>
              <div className="xmod-actions">
                {(all ? g.actions : g.actions.slice(0, FIRST)).map((a) => {
                  const I = ACTION_ICON[a.kind] ? Icon[ACTION_ICON[a.kind]!] : null
                  return (
                    <button
                      key={`${a.id}/${a.action}`}
                      className={`btn btn-sm ${a.kind === 'list' ? 'btn-secondary' : 'btn-ghost'} xmod-action`}
                      aria-label={a.name}
                      title={a.name}
                      onClick={() => press(a)}
                    >
                      {I && <I />}<span className="xmod-label">{a.label}</span>
                    </button>
                  )
                })}
                {!all && (
                  <button
                    className="btn btn-ghost btn-sm xmod-more"
                    aria-label={`Show all ${g.actions.length} from ${g.name}`}
                    onClick={() => setExpanded((s) => new Set(s).add(g.key))}
                  >
                    Show all {g.actions.length}
                  </button>
                )}
              </div>
            </section>
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
