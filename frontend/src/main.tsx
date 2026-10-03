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

async function transport(): Promise<Transport> {
  const cef = cefTransport()
  if (cef) return cef
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

async function start() {
  const root = createRoot(document.getElementById('root')!)
  const bridge = new Bridge(await transport())
  window.addEventListener('pagehide', () => bridge.cancelAll())
  try {
    const [hello, settings] = await Promise.all([bridge.call('app.hello'), bridge.call('settings.get')])
    document.documentElement.dataset.runtime = bridge.kind
    root.render(
      <StrictMode>
        <AppProvider bridge={bridge} hello={hello} initialSettings={settings}>
          <App />
        </AppProvider>
      </StrictMode>,
    )
  } catch (err) {
    root.render(<StartupFailure message={describeStartupError(err)} />)
  }
}

start()
