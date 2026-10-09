import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Bridge, BridgeError, codeFromNumber, ERROR_CODES, type Transport } from './client'

type Pending = { request: string; ok: (r: string) => void; fail: (c: number, m: string) => void; cancelled: boolean }

function fakeTransport() {
  const sent: Pending[] = []
  const t: Transport = {
    kind: 'cef',
    send(request, ok, fail) {
      const p: Pending = { request, ok, fail, cancelled: false }
      sent.push(p)
      return () => {
        p.cancelled = true
      }
    },
  }
  return { t, sent }
}

describe('Bridge', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('sends a versioned request and resolves with the parsed answer', async () => {
    const { t, sent } = fakeTransport()
    const b = new Bridge(t)
    const p = b.call('modules.setEnabled', { name: 'FPS', enabled: true })
    const req = JSON.parse(sent[0]!.request)
    expect(req).toMatchObject({ v: 1, action: 'modules.setEnabled', params: { name: 'FPS', enabled: true } })
    expect(typeof req.id).toBe('number')
    sent[0]!.ok(JSON.stringify({ name: 'FPS', enabled: true }))
    await expect(p).resolves.toMatchObject({ name: 'FPS', enabled: true })
    expect(b.pending).toBe(0)
  })

  it('gives every request its own id', () => {
    const { t, sent } = fakeTransport()
    const b = new Bridge(t)
    b.call('game.state')
    b.call('game.state')
    const ids = sent.map((s) => JSON.parse(s.request).id)
    expect(new Set(ids).size).toBe(2)
  })

  it('maps Java failure codes to named errors', async () => {
    const { t, sent } = fakeTransport()
    const b = new Bridge(t)
    const p = b.call('cosmetics.state')
    sent[0]!.fail(ERROR_CODES.indexOf('FORBIDDEN') + 1, 'Sign in first')
    const err = await p.catch((e) => e)
    expect(err).toBeInstanceOf(BridgeError)
    expect(err.code).toBe('FORBIDDEN')
    expect(err.message).toBe('Sign in first')
    expect(err.action).toBe('cosmetics.state')
  })

  it('treats an unknown failure code as INTERNAL', () => {
    expect(codeFromNumber(0)).toBe('INTERNAL')
    expect(codeFromNumber(999)).toBe('INTERNAL')
    expect(codeFromNumber(1)).toBe('BAD_REQUEST')
  })

  it('rejects unreadable answers instead of passing garbage on', async () => {
    const { t, sent } = fakeTransport()
    const b = new Bridge(t)
    const p = b.call('game.state')
    sent[0]!.ok('{not json')
    await expect(p).rejects.toMatchObject({ code: 'INTERNAL' })
  })

  it('times out, cancels the query, and ignores a late answer', async () => {
    const { t, sent } = fakeTransport()
    const b = new Bridge(t)
    const p = b.call('game.state')
    vi.advanceTimersByTime(20_001)
    await expect(p).rejects.toMatchObject({ code: 'TIMEOUT' })
    expect(sent[0]!.cancelled).toBe(true)
    sent[0]!.ok('{}')
    expect(b.pending).toBe(0)
  })

  it('cancelAll rejects everything in flight', async () => {
    const { t, sent } = fakeTransport()
    const b = new Bridge(t)
    const a = b.call('game.state')
    const c = b.call('modules.list')
    b.cancelAll()
    await expect(a).rejects.toMatchObject({ code: 'CANCELLED' })
    await expect(c).rejects.toMatchObject({ code: 'CANCELLED' })
    expect(sent.every((s) => s.cancelled)).toBe(true)
    expect(b.pending).toBe(0)
  })

  it('delivers Java events to listeners and survives a throwing listener', () => {
    const { t } = fakeTransport()
    const b = new Bridge(t)
    const seen: string[] = []
    const errors = vi.spyOn(console, 'error').mockImplementation(() => {})
    b.on('settings.changed', () => {
      throw new Error('boom')
    })
    const off = b.on('settings.changed', (s) => seen.push(s.theme))
    window.__breezeEvent!({ type: 'settings.changed', payload: { theme: 'black' } })
    off()
    window.__breezeEvent!({ type: 'settings.changed', payload: { theme: 'white' } })
    window.__breezeEvent!({ type: 'unknown.event' })
    expect(seen).toEqual(['black'])
    // The throwing listener stays subscribed and fails on both events; the
    // other listener still received its event, which is the point.
    expect(errors).toHaveBeenCalledTimes(2)
    errors.mockRestore()
  })
})
