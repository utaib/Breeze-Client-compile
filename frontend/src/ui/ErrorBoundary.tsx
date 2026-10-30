import { Component, type ErrorInfo, type ReactNode } from 'react'
import type { Bridge } from '../bridge/client'

/**
 * A page that throws while drawing must not take the menu with it.
 *
 * React removes everything under a component that throws during render. With
 * no boundary that was the whole menu, so a single bad value left an empty,
 * transparent page: on the title screen a dark window, in a world the game
 * dimmed behind nothing, which players described as a grey screen on Right
 * Shift. The page had already answered the bridge, so Java's watchdog saw a
 * working menu, and nothing reached the game log.
 *
 * Now each page sits in a boundary (the rail and the rest of the menu keep
 * working), the whole menu sits in another (a way out even then), and every
 * error is sent to Java, which writes it to latest.log.
 */

let reported = 0

/** Sends an error to the game log, at most 20 times a page, never throwing. */
export function report(bridge: Bridge, error: unknown, stack: string | undefined, fatal: boolean): void {
  if (bridge.kind !== 'cef' || reported >= 20) return
  reported++
  const message = error instanceof Error ? error.message : String(error)
  const trace = stack ?? (error instanceof Error ? error.stack : undefined) ?? ''
  bridge.call('ui.reportError', {
    message: message.slice(0, 600) || 'Unknown error',
    stack: trace.slice(0, 4000),
    fatal,
  }).catch(() => {})
}

interface Props {
  bridge: Bridge
  /** True for the boundary around the whole menu. */
  whole?: boolean
  ingame?: boolean
  onHome?: () => void
  children: ReactNode
}

interface State {
  error: Error | null
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null }

  static getDerivedStateFromError(error: Error): State {
    return { error }
  }

  componentDidCatch(error: Error, info: ErrorInfo): void {
    report(this.props.bridge, error, `${error.stack ?? ''}\n${info.componentStack ?? ''}`, Boolean(this.props.whole))
  }

  render(): ReactNode {
    const { error } = this.state
    if (!error) return this.props.children
    const { bridge, whole, ingame, onHome } = this.props
    const retry = () => this.setState({ error: null })
    if (!whole) {
      return (
        <div className="page">
          <div className="state error" role="alert" data-testid="page-error">
            <div className="state-title">This page hit a problem</div>
            <div className="state-text">The rest of the Breeze menu still works. The game log has the details.</div>
            <div className="detail-actions">
              <button className="btn btn-secondary btn-sm" onClick={retry}>Try again</button>
              {onHome && <button className="btn btn-ghost btn-sm" onClick={onHome}>Home</button>}
            </div>
          </div>
        </div>
      )
    }
    return (
      <div className="startup-failure" role="alert" data-testid="menu-error">
        <div className="startup-title">The Breeze menu hit a problem</div>
        <p className="startup-text">The game log has the details. You can keep playing with Breeze's classic menu.</p>
        <div className="detail-actions">
          <button className="btn btn-secondary btn-sm" onClick={() => bridge.call('ui.menuStyle', { style: 'classic' }).catch(() => {})}>Use classic menu</button>
          <button className="btn btn-ghost btn-sm" onClick={retry}>Try again</button>
          {ingame && <button className="btn btn-ghost btn-sm" onClick={() => bridge.call('ui.close').catch(() => {})}>Back to game</button>}
        </div>
      </div>
    )
  }
}
