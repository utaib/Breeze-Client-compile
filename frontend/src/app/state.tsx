import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Bridge, BridgeError } from '../bridge/client'
import type { Action, Hello, ParamsOf, ResultOf, UiSettings } from '../bridge/types'

export type Route =
  | { name: 'home' }
  | { name: 'hosting' }
  | { name: 'wardrobe' }
  | { name: 'friends' }
  | { name: 'mods'; tab: 'modules' | 'installed' }
  | { name: 'module'; module: string }
  | { name: 'settings' }

export function routeKey(r: Route): string {
  switch (r.name) {
    case 'mods': return `mods/${r.tab}`
    case 'module': return `module/${r.module}`
    default: return r.name
  }
}

export function parseRoute(key: string | null | undefined): Route {
  if (!key) return { name: 'home' }
  const [head, ...rest] = key.split('/')
  const tail = rest.join('/')
  switch (head) {
    case 'hosting': case 'wardrobe': case 'friends': case 'settings': case 'home':
      return { name: head }
    case 'mods': return { name: 'mods', tab: tail === 'installed' ? 'installed' : 'modules' }
    case 'module': return tail ? { name: 'module', module: tail } : { name: 'mods', tab: 'modules' }
    default: return { name: 'home' }
  }
}

export interface Toast {
  id: number
  tone: 'info' | 'error' | 'ok'
  text: string
}

/** Something that wants first claim on Escape, such as an open dialog. */
type EscapeLayer = () => boolean

interface AppState {
  bridge: Bridge
  hello: Hello
  settings: UiSettings
  setSetting: <K extends keyof UiSettings>(key: K, value: UiSettings[K]) => Promise<void>
  resetSettings: () => Promise<void>
  route: Route
  canGoBack: boolean
  navigate: (to: Route) => void
  /** Go back one step. Returns false at the root. */
  back: () => boolean
  toasts: Toast[]
  toast: (text: string, tone?: Toast['tone']) => void
  dismissToast: (id: number) => void
  pushEscapeLayer: (layer: EscapeLayer) => () => void
  /** Run the page's Escape logic: top layer, then history. */
  escape: () => boolean
  call: <A extends Action>(action: A, params?: ParamsOf<A>) => Promise<ResultOf<A>>
}

const Ctx = createContext<AppState | null>(null)

export function useApp(): AppState {
  const v = useContext(Ctx)
  if (!v) throw new Error('useApp outside AppProvider')
  return v
}

export function describeError(err: unknown): string {
  if (err instanceof BridgeError) return err.message
  if (err instanceof Error) return err.message
  return String(err)
}

export function AppProvider({ bridge, hello, initialSettings, children }: {
  bridge: Bridge
  hello: Hello
  initialSettings: UiSettings
  children: ReactNode
}) {
  const [settings, setSettings] = useState(initialSettings)
  const [stack, setStack] = useState<Route[]>(() => {
    const start = parseRoute(hello.lastRoute)
    return start.name === 'home' ? [start] : [{ name: 'home' }, start]
  })
  const [toasts, setToasts] = useState<Toast[]>([])
  const layers = useRef<EscapeLayer[]>([])
  const toastId = useRef(1)

  const route = stack[stack.length - 1]!

  const toast = useCallback((text: string, tone: Toast['tone'] = 'info') => {
    const id = toastId.current++
    setToasts((t) => [...t.slice(-2), { id, tone, text }])
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), tone === 'error' ? 6000 : 3200)
  }, [])

  const dismissToast = useCallback((id: number) => setToasts((t) => t.filter((x) => x.id !== id)), [])

  const call = useCallback(<A extends Action>(action: A, params?: ParamsOf<A>) => bridge.call(action, params), [bridge])

  // Only the newest write's answer may replace local state. Java applies
  // writes in order, so the last answer already includes every earlier one,
  // while an earlier answer arriving after a newer optimistic change would
  // quietly undo it (a switch flicking back, a slider losing steps).
  const settingsSeq = useRef(0)
  const confirmedSettings = useRef(initialSettings)

  const setSetting = useCallback(async <K extends keyof UiSettings>(key: K, value: UiSettings[K]) => {
    const mine = ++settingsSeq.current
    setSettings((s) => ({ ...s, [key]: value }))
    try {
      const saved = await bridge.call('settings.set', { key, value })
      confirmedSettings.current = saved
      if (mine === settingsSeq.current) setSettings(saved)
    } catch (err) {
      if (mine === settingsSeq.current) setSettings(confirmedSettings.current)
      toast(`Could not save that setting. ${describeError(err)}`, 'error')
    }
  }, [bridge, toast])

  const resetSettings = useCallback(async () => {
    const mine = ++settingsSeq.current
    try {
      const saved = await bridge.call('settings.reset')
      confirmedSettings.current = saved
      if (mine === settingsSeq.current) setSettings(saved)
      toast('Interface settings restored to their defaults', 'ok')
    } catch (err) {
      toast(`Could not reset settings. ${describeError(err)}`, 'error')
    }
  }, [bridge, toast])

  const navigate = useCallback((to: Route) => {
    setStack((s) => {
      const top = s[s.length - 1]!
      if (routeKey(top) === routeKey(to)) return s
      // Top-level destinations replace the trail instead of growing it, so
      // Back always means "up one level", never "replay every click".
      const topLevel = to.name !== 'module'
      return topLevel ? (to.name === 'home' ? [to] : [{ name: 'home' }, to]) : [...s, to]
    })
  }, [])

  // Read through a ref: a state updater may run after this returns, so it
  // cannot report whether it moved.
  const stackRef = useRef(stack)
  stackRef.current = stack

  const back = useCallback(() => {
    if (stackRef.current.length <= 1) return false
    stackRef.current = stackRef.current.slice(0, -1)
    setStack((s) => (s.length > 1 ? s.slice(0, -1) : s))
    return true
  }, [])

  const escape = useCallback(() => {
    const top = layers.current[layers.current.length - 1]
    if (top && top()) return true
    return back()
  }, [back])

  const pushEscapeLayer = useCallback((layer: EscapeLayer) => {
    layers.current.push(layer)
    return () => {
      layers.current = layers.current.filter((l) => l !== layer)
    }
  }, [])

  // Report the route so a reopened menu starts where the player left it.
  useEffect(() => {
    bridge.call('ui.route', { route: routeKey(route) }).catch(() => {})
  }, [bridge, route])

  // Settings changed from Java (another screen, a reset elsewhere).
  useEffect(() => bridge.on('settings.changed', (s) => {
    confirmedSettings.current = s
    settingsSeq.current++
    setSettings(s)
  }), [bridge])

  const value = useMemo<AppState>(() => ({
    bridge, hello, settings, setSetting, resetSettings, route, canGoBack: stack.length > 1,
    navigate, back, toasts, toast, dismissToast, pushEscapeLayer, escape, call,
  }), [bridge, hello, settings, setSetting, resetSettings, route, stack.length, navigate, back, toasts, toast, dismissToast, pushEscapeLayer, escape, call])

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

/** Load one action's result, with reload and local updates. */
export function useAction<A extends Action>(action: A, params?: ParamsOf<A>, opts: { enabled?: boolean } = {}) {
  const { call } = useApp()
  const enabled = opts.enabled ?? true
  const [data, setData] = useState<ResultOf<A> | null>(null)
  const [error, setError] = useState<unknown>(null)
  const [loading, setLoading] = useState(enabled)
  const key = JSON.stringify(params ?? {})
  const seq = useRef(0)

  const reload = useCallback(async () => {
    if (!enabled) return
    const mine = ++seq.current
    setLoading(true)
    setError(null)
    try {
      const result = await call(action, params)
      if (mine === seq.current) setData(result)
    } catch (err) {
      if (mine === seq.current) setError(err)
    } finally {
      if (mine === seq.current) setLoading(false)
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [call, action, key, enabled])

  useEffect(() => {
    reload()
    return () => {
      seq.current++
    }
  }, [reload])

  return { data, setData, error, loading, reload }
}
