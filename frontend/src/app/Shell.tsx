import { useEffect, useState, type ReactNode } from 'react'
import logo from '../assets/breeze-logo.png'
import backdrop from '../assets/title-backdrop.jpg'
import { Icon } from '../ui/icons'
import { ConfirmDialog, Toasts } from '../ui/controls'
import { groupIntegrations } from './integrationGroups'
import { describeError, routeKey, useAction, useApp, type Route } from './state'

interface NavItem {
  label: string
  icon: keyof typeof Icon
  route?: Route
  /** Hidden when the Java side reports the feature cannot work here. */
  feature?: 'cosmetics' | 'friends' | 'hosting'
}

const NAV: NavItem[] = [
  { label: 'Home', icon: 'Home', route: { name: 'home' } },
  { label: 'Mods', icon: 'Grid', route: { name: 'mods', tab: 'modules' } },
  { label: 'Wardrobe', icon: 'Hanger', route: { name: 'wardrobe' }, feature: 'cosmetics' },
  { label: 'Friends', icon: 'Users', route: { name: 'friends' }, feature: 'friends' },
  { label: 'Hosting', icon: 'Server', route: { name: 'hosting' }, feature: 'hosting' },
]

const TITLES: Record<Route['name'], string> = {
  home: 'Home', mods: 'Mods', module: 'Mods', wardrobe: 'Wardrobe',
  friends: 'Friends', hosting: 'Hosting', settings: 'Settings',
}

/** Mods/Other mods is the Other mods entry's page when that entry is on the rail. */
function isActive(item: NavItem, route: Route, otherEntry: boolean): boolean {
  if (!item.route) return false
  if (item.route.name === 'mods') {
    const other = route.name === 'mods' && route.tab === 'other' && otherEntry
    return item.route.tab === 'other' ? other : (route.name === 'mods' || route.name === 'module') && !other
  }
  return item.route.name === route.name
}

export function Shell({ children }: { children: ReactNode }) {
  const app = useApp()
  const { hello, settings, route, navigate, canGoBack, back, call, toast } = app
  const [confirmQuit, setConfirmQuit] = useState(false)
  const ingame = hello.context === 'ingame'
  const home = route.name === 'home'
  // What other mods in this game offer (Mod Menu's list, mods' settings and
  // own Breeze entries, their title screen buttons): one rail entry for all
  // of them, opening Mods / Other mods, where each mod is one entry. Only
  // when something is really there.
  const integrations = useAction('integrations.list')
  const otherEntry = groupIntegrations(integrations.data?.integrations ?? []).length > 0

  // Standalone browser: Escape comes from the keyboard directly. In game Java
  // intercepts Escape and sends key.escape instead, so the page stays in charge
  // of what Back means and Java stays in charge of the failsafe.
  useEffect(() => {
    if (app.bridge.kind === 'cef') return
    const onKey = (e: KeyboardEvent) => {
      if (e.key !== 'Escape' || e.defaultPrevented) return
      e.preventDefault()
      app.escape()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [app])

  useEffect(() => app.bridge.on('key.escape', () => {
    const handled = app.escape()
    call('ui.escapeAck', { handled }).catch(() => {})
  }), [app, call])

  const run = (action: 'game.singleplayer' | 'game.multiplayer' | 'game.options' | 'game.pauseMenu' | 'ui.close') => {
    call(action).catch((err) => toast(describeError(err), 'error'))
  }

  const style = {
    '--dim': String(settings.backdropDim / 100),
  } as React.CSSProperties

  return (
    <div className="app" style={style}>
      <div className="backdrop" aria-hidden="true">
        {!ingame && <div className="backdrop-art" style={{ backgroundImage: `url(${backdrop})` }} />}
        <div className="backdrop-scrim" />
      </div>

      <nav className="rail" aria-label="Breeze">
        <div className="rail-logo"><img src={logo} alt="Breeze" /></div>
        {NAV.slice(0, 1).map(renderNav)}
        {!ingame && (
          <>
            <button className="ni" aria-label="Singleplayer" title="Singleplayer" onClick={() => run('game.singleplayer')}>
              <Icon.Play /><span className="ni-lbl">Singleplayer</span>
            </button>
            <button className="ni" aria-label="Multiplayer" title="Multiplayer" onClick={() => run('game.multiplayer')}>
              <Icon.Globe /><span className="ni-lbl">Multiplayer</span>
            </button>
          </>
        )}
        <div className="rail-sep" />
        {NAV.slice(1).map(renderNav)}
        {/* The Music player is a native screen (it opens at once and does not
            need this browser); this opens it over the menu, Back returns here. */}
        <button className="ni" aria-label="Music" title="Music" data-testid="music-open"
          onClick={() => call('music.open').catch((err) => toast(describeError(err), 'error'))}>
          <Icon.Music /><span className="ni-lbl">Music</span>
        </button>
        {otherEntry && <div className="rail-sep" />}
        {otherEntry && renderNav({ label: 'Other mods', icon: 'Package', route: { name: 'mods', tab: 'other' } })}
        <div className="rail-foot">
          {renderNav({ label: 'Settings', icon: 'Gear', route: { name: 'settings' } })}
          {ingame ? (
            <button className="ni" aria-label="Close" title="Close" onClick={() => run('ui.close')}>
              <Icon.X /><span className="ni-lbl">Close</span>
            </button>
          ) : (
            <button className="ni" aria-label="Quit" title="Quit" onClick={() => setConfirmQuit(true)}>
              <Icon.Power /><span className="ni-lbl">Quit</span>
            </button>
          )}
        </div>
      </nav>

      <div className="main">
        <header className={`bar ${home ? 'clear' : ''}`}>
          <div className="bar-l">
            <button className="iconbtn" aria-label="Back" disabled={!canGoBack} onClick={() => back()}><Icon.Back /></button>
            <div className="crumb">
              <span className="crumb-app">Breeze</span>
              <span className="crumb-sep">/</span>
              <span className="crumb-pg">{TITLES[route.name]}{route.name === 'module' ? ` / ${route.module}` : route.name === 'mods' && route.tab === 'other' ? ' / Other mods' : ''}</span>
            </div>
          </div>
          <div className="bar-r">
            {hello.preview && <span className="preview-flag" title="Running outside Minecraft. Data on screen is sample data.">Preview data</span>}
            {ingame && <button className="btn btn-ghost btn-sm" onClick={() => run('game.pauseMenu')}>Game menu</button>}
            <button className="btn btn-ghost btn-sm" onClick={() => run('game.options')}><Icon.Sliders />Options</button>
          </div>
        </header>
        <main className={`content ${home ? '' : 'panel'}`} key={routeKey(route)}>
          {children}
        </main>
      </div>

      <Toasts />
      {confirmQuit && (
        <ConfirmDialog
          title="Quit Minecraft?"
          text="Minecraft will close."
          confirm="Quit game"
          tone="danger"
          onCancel={() => setConfirmQuit(false)}
          onConfirm={() => {
            setConfirmQuit(false)
            call('game.quit').catch((err) => toast(describeError(err), 'error'))
          }}
        />
      )}
    </div>
  )

  // A render function, not a nested component: a component declared in here
  // would be a new type every render and React would remount each button,
  // dropping keyboard focus whenever anything changed.
  function renderNav(item: NavItem) {
    const available = !item.feature || hello.features[item.feature]
    if (!available) return null
    const active = isActive(item, route, otherEntry)
    const I = Icon[item.icon]
    return (
      <button
        key={item.label}
        className={`ni ${active ? 'on' : ''}`}
        aria-current={active ? 'page' : undefined}
        aria-label={item.label}
        title={item.label}
        onClick={() => item.route && navigate(item.route)}
      >
        <I />
        <span className="ni-lbl">{item.label}</span>
      </button>
    )
  }
}
