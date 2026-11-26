import '@fontsource/dm-sans/latin-400.css'
import '@fontsource/dm-sans/latin-ext-400.css'
import '@fontsource/dm-sans/latin-500.css'
import '@fontsource/dm-sans/latin-600.css'
import '@fontsource/dm-sans/latin-ext-600.css'
import '@fontsource/jetbrains-mono/latin-400.css'
import './styles/tokens.css'
import './styles/base.css'
import './styles/pages.css'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './app/App'
import { AppProvider } from './app/state'
import { Bridge, cefTransport, describeStartupError, type Transport } from './bridge/client'
import { ErrorBoundary, report } from './ui/ErrorBoundary'

/** Where the mod serves this page inside Minecraft (PageOrigin.java: https, host breeze.local). */
const inGame = () => location.protocol === 'https:' && location.hostname === 'breeze.local'

async function transport(): Promise<Transport> {
  const cef = cefTransport()
  if (cef) return cef
  // Inside Minecraft without the bridge something is broken. Say so: sample
  // data must never stand in for the player's real game.
  if (inGame()) {
    throw new Error('The menu opened, but its connection to Minecraft did not.')
  }
  // No Minecraft here: load the preview fixture, a separate chunk the game
  // never downloads. See src/dev/fixture.ts.
  document.body.classList.add('standalone')
  const { previewTransport } = await import('./dev/fixture')
  return previewTransport()
}

function StartupFailure({ message }: { message: string }) {
  return (
    <div className="startup-failure" role="alert">
      <div className="startup-title">Breeze could not start its menu</div>
      <p className="startup-text">{message}</p>
      <p className="startup-text">Press Escape to go back to Minecraft. If this keeps happening, the game log names the cause.</p>
    </div>
  )
}

/** After the browser has drawn what React just rendered: two animation frames. */
function afterPaint(): Promise<void> {
  return new Promise((done) => requestAnimationFrame(() => requestAnimationFrame(() => done())))
}

async function start() {
  const container = document.getElementById('root')!
  let root = createRoot(container)
  let bridge: Bridge
  try {
    bridge = new Bridge(await transport())
  } catch (err) {
    root.render(<StartupFailure message={describeStartupError(err)} />)
    return
  }
  window.addEventListener('pagehide', () => bridge.cancelAll())
  // Errors outside React's drawing (a handler, a timer, a rejected promise)
  // go to the game log too.
  window.addEventListener('error', (e) => report(bridge, e.error ?? e.message, undefined, false))
  window.addEventListener('unhandledrejection', (e) => report(bridge, e.reason, undefined, false))

  // One browser serves every open of the menu (BreezeWebScreen and
  // WarmBrowser in the mod): each open is a visit with its own context and
  // starting route, drawn fresh; between visits the page draws nothing, so
  // Chromium has nothing to repaint while the game runs.
  let visit = 0
  const show = async () => {
    const mine = ++visit
    try {
      const [hello, settings] = await Promise.all([bridge.call('app.hello'), bridge.call('settings.get')])
      if (mine !== visit) return
      if (hello.standby) return
      document.documentElement.dataset.runtime = bridge.kind
      root.render(
        <StrictMode>
          <ErrorBoundary key={mine} bridge={bridge} whole ingame={hello.context === 'ingame'}>
            <AppProvider bridge={bridge} hello={hello} initialSettings={settings}>
              <App />
            </AppProvider>
          </ErrorBoundary>
        </StrictMode>,
      )
      await afterPaint()
      if (mine === visit) await bridge.call('ui.shown').catch(() => undefined)
    } catch (err) {
      if (mine !== visit) return
      root.render(<StartupFailure message={describeStartupError(err)} />)
    }
  }
  bridge.on('app.visit', () => { void show() })
  bridge.on('app.hide', () => {
    visit++
    root.unmount()
    root = createRoot(container)
  })
  await show()
}

start()
