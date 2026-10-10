import type { Action, ErrorCode, EventMap, EventName, ParamsOf, ResultOf } from './types'

/**
 * The one way the page talks to Minecraft.
 *
 * In game, the mod's JCEF message router exposes window.breezeQuery (named so
 * another MCEF mod's cefQuery cannot collide with it).
 * Each call carries one JSON request and Java answers it exactly once, through
 * onSuccess or onFailure, so there is no global reply channel to correlate and
 * no reply can reach the wrong caller. Java pushes events the other way through
 * window.__breezeEvent.
 *
 * Outside the game there is no breezeQuery. The page then loads the preview
 * fixture (src/dev/fixture.ts), a separate chunk the in-game path never loads,
 * and says so on screen.
 */

export const PROTOCOL = 1

/** Longer than Java's own per-action timeouts, so its specific error wins. */
const CLIENT_TIMEOUT_MS = 20_000

export class BridgeError extends Error {
  readonly code: ErrorCode
  readonly action: string
  constructor(code: ErrorCode, message: string, action: string) {
    super(message)
    this.name = 'BridgeError'
    this.code = code
    this.action = action
  }
}

export interface Transport {
  /** Send one serialized request. Must call exactly one of the callbacks. */
  send(request: string, onSuccess: (response: string) => void, onFailure: (code: number, message: string) => void): () => void
  readonly kind: 'cef' | 'preview'
}

interface CefQueryArgs {
  request: string
  persistent?: boolean
  onSuccess: (response: string) => void
  onFailure: (code: number, message: string) => void
}

declare global {
  interface Window {
    breezeQuery?: (args: CefQueryArgs) => number
    breezeQueryCancel?: (id: number) => void
    __breezeEvent?: (event: { type: string; payload?: unknown }) => void
  }
}

/** Java's numeric failure codes, in the order of contract/bridge.json "errors". */
export const ERROR_CODES: ErrorCode[] = [
  'BAD_REQUEST', 'UNKNOWN_ACTION', 'INVALID_PARAMS', 'UNAVAILABLE', 'FORBIDDEN',
  'TIMEOUT', 'BUSY', 'DUPLICATE', 'CANCELLED', 'INTERNAL',
]

export function codeFromNumber(n: number): ErrorCode {
  return ERROR_CODES[n - 1] ?? 'INTERNAL'
}

export function cefTransport(): Transport | null {
  if (typeof window === 'undefined' || typeof window.breezeQuery !== 'function') return null
  return {
    kind: 'cef',
    send(request, onSuccess, onFailure) {
      const id = window.breezeQuery!({ request, persistent: false, onSuccess, onFailure })
      return () => window.breezeQueryCancel?.(id)
    },
  }
}

type Listener<E extends EventName> = (payload: EventMap[E]) => void

export class Bridge {
  private nextId = 1
  private readonly listeners = new Map<string, Set<(payload: unknown) => void>>()
  private readonly inflight = new Map<number, () => void>()

  constructor(private readonly transport: Transport) {
    window.__breezeEvent = (event) => this.emit(event?.type, event?.payload)
  }

  get kind() {
    return this.transport.kind
  }

  call<A extends Action>(action: A, params?: ParamsOf<A>): Promise<ResultOf<A>> {
    const id = this.nextId++
    const request = JSON.stringify({ v: PROTOCOL, id, action, params: params ?? {} })
    return new Promise<ResultOf<A>>((resolve, reject) => {
      let settled = false
      const finish = () => {
        settled = true
        clearTimeout(timer)
        this.inflight.delete(id)
      }
      const timer = setTimeout(() => {
        if (settled) return
        finish()
        cancel()
        reject(new BridgeError('TIMEOUT', `${action} did not answer in time`, action))
      }, CLIENT_TIMEOUT_MS)
      const cancel = this.transport.send(
        request,
        (response) => {
          if (settled) return
          finish()
          try {
            resolve((response ? JSON.parse(response) : {}) as ResultOf<A>)
          } catch {
            reject(new BridgeError('INTERNAL', `${action} answered with unreadable data`, action))
          }
        },
        (code, message) => {
          if (settled) return
          finish()
          reject(new BridgeError(codeFromNumber(code), message || `${action} failed`, action))
        },
      )
      this.inflight.set(id, () => {
        if (settled) return
        finish()
        cancel()
        reject(new BridgeError('CANCELLED', `${action} was cancelled`, action))
      })
    })
  }

  on<E extends EventName>(type: E, listener: Listener<E>): () => void {
    let set = this.listeners.get(type)
    if (!set) this.listeners.set(type, (set = new Set()))
    set.add(listener as (payload: unknown) => void)
    return () => set!.delete(listener as (payload: unknown) => void)
  }

  emit(type: string | undefined, payload: unknown): void {
    if (!type) return
    const set = this.listeners.get(type)
    if (!set) return
    for (const listener of [...set]) {
      try {
        listener(payload)
      } catch (err) {
        console.error(`[breeze] ${type} listener failed`, err)
      }
    }
  }

  /** Reject everything still waiting. Called on pagehide. */
  cancelAll(): void {
    for (const cancel of [...this.inflight.values()]) cancel()
  }

  get pending(): number {
    return this.inflight.size
  }
}

export function describeStartupError(err: unknown): string {
  if (err instanceof BridgeError) return `${err.action} failed: ${err.message} (${err.code}).`
  return err instanceof Error ? err.message : String(err)
}
