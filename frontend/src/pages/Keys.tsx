import { useEffect, useMemo, useRef, useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { KeyBinding } from '../bridge/types'
import { EmptyState, ErrorState, Row, SearchField, Segmented, Skeleton } from '../ui/controls'
import { Icon } from '../ui/icons'

/**
 * Breeze's keys, the same bindings as Minecraft's Controls (Options,
 * Controls, Key Binds, Breeze). Changing one asks Java for the next key
 * pressed in the menu, so the binding is the key exactly as Minecraft reads
 * it on this version; Escape cancels.
 */
export function useKeys() {
  const { bridge, call, toast, pushEscapeLayer } = useApp()
  const list = useAction('keys.list')
  const { reload, setData } = list
  const [waiting, setWaiting] = useState<string | null>(null)
  const waitingRef = useRef<string | null>(null)
  waitingRef.current = waiting

  useEffect(() => bridge.on('keys.changed', () => {
    setWaiting(null)
    reload()
  }), [bridge, reload])

  // Leaving the page while it waits: Java stops waiting too.
  useEffect(() => () => {
    if (waitingRef.current) call('keys.cancel').catch(() => {})
  }, [call])

  const cancel = () => {
    setWaiting(null)
    call('keys.cancel').catch(() => {})
  }

  // If Escape ever reaches the page while it waits, it cancels, not Back.
  useEffect(() => {
    if (!waiting) return
    return pushEscapeLayer(() => {
      cancel()
      return true
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [waiting, pushEscapeLayer])

  const capture = async (id: string) => {
    try {
      await call('keys.capture', { id })
      setWaiting(id)
    } catch (err) {
      toast(`Could not change that key. ${describeError(err)}`, 'error')
    }
  }

  const reset = async (b: KeyBinding, to: 'default' | 'none') => {
    try {
      setData(await call('keys.reset', { id: b.id, to }))
    } catch (err) {
      toast(`${b.label}'s key was not changed. ${describeError(err)}`, 'error')
    }
  }

  return { list, waiting, capture, cancel, reset }
}

export type KeysApi = ReturnType<typeof useKeys>

/** What a row says about a binding's key: whose else it is. */
export function conflictText(b: KeyBinding): string | null {
  if (b.conflicts.length === 0) return null
  const who = b.conflicts.length === 1 ? b.conflicts[0] : `${b.conflicts.slice(0, -1).join(', ')} and ${b.conflicts.at(-1)}`
  return `${b.key} is also ${who}. Minecraft may run only one of them; pick another key.`
}

export function KeyControl({ binding: b, keys }: { binding: KeyBinding; keys: KeysApi }) {
  const waiting = keys.waiting === b.id
  return (
    <div className="keyctl">
      <button
        type="button"
        className={`keycap ${waiting ? 'waiting' : ''} ${b.conflicts.length > 0 ? 'conflict' : ''} ${b.key ? '' : 'none'}`}
        data-state={waiting ? 'waiting' : 'idle'}
        aria-label={waiting ? `Press a key for ${b.label}, or Escape to cancel` : `${b.label}: ${b.key || 'no key'}. Change`}
        onClick={() => (waiting ? keys.cancel() : keys.capture(b.id))}
      >
        {waiting ? 'Press a key' : b.key || 'No key'}
      </button>
      {!waiting && !b.isDefault && (
        <button
          type="button"
          className="iconbtn"
          aria-label={`Put ${b.label} back on ${b.defaultKey || 'no key'}`}
          title={`Back to ${b.defaultKey || 'no key'}`}
          onClick={() => keys.reset(b, 'default')}
        >
          <Icon.Reset />
        </button>
      )}
      {!waiting && b.key && (
        <button type="button" className="iconbtn" aria-label={`Remove ${b.label}'s key`} title="No key" onClick={() => keys.reset(b, 'none')}>
          <Icon.X />
        </button>
      )}
    </div>
  )
}

type Show = 'all' | 'set' | 'conflicts'

/** Mods / Keys: every Breeze key in one place. */
export function KeysTab() {
  const { navigate } = useApp()
  const keys = useKeys()
  const [query, setQuery] = useState('')
  const [show, setShow] = useState<Show>('all')
  const all = useMemo(() => keys.list.data?.keys ?? [], [keys.list.data])
  const conflicts = all.filter((b) => b.conflicts.length > 0).length
  const shown = useMemo(() => {
    const q = query.trim().toLowerCase()
    return all.filter((b) => {
      if (show === 'set' && !b.key) return false
      if (show === 'conflicts' && b.conflicts.length === 0) return false
      const key = b.key.toLowerCase()
      return !q || b.label.toLowerCase().includes(q) || key === q || (q.length > 1 && key.includes(q))
    })
  }, [all, query, show])

  return (
    <>
      <div className="toolbar">
        <SearchField value={query} onChange={setQuery} placeholder="Search keys" autoFocus />
        <Segmented
          label="Show"
          value={show}
          options={[
            { value: 'all', label: 'All' },
            { value: 'set', label: 'With a key' },
            { value: 'conflicts', label: conflicts > 0 ? `Shared (${conflicts})` : 'Shared' },
          ]}
          onChange={setShow}
        />
      </div>
      {keys.list.loading && !keys.list.data && <Skeleton height="3.25rem" count={6} gap="1px" />}
      {keys.list.error != null && <ErrorState error={keys.list.error} what="keys" onRetry={keys.list.reload} />}
      {keys.list.data && shown.length === 0 && (
        <EmptyState
          title={query ? `No key matches "${query}"` : show === 'conflicts' ? 'No key is shared' : 'No keys here'}
          text={query ? 'Search by a module\'s name, or by a key such as R.' : 'Every Breeze key is on its own.'}
        />
      )}
      {shown.length > 0 && (
        <div className="rows" role="list">
          {shown.map((b) => (
            <div role="listitem" key={b.id}>
              <Row
                anchor={b.id}
                label={b.module
                  ? <button type="button" className="linkish" onClick={() => navigate({ name: 'module', module: b.module! })}>{b.label}</button>
                  : b.label}
                desc={conflictText(b) ?? (b.module ? 'Turns the module on and off.' : undefined)}
              >
                <KeyControl binding={b} keys={keys} />
              </Row>
            </div>
          ))}
        </div>
      )}
      <p className="section-note">The same keys as Minecraft's Controls (Options, Controls, Key Binds, Breeze), saved in its options file.</p>
    </>
  )
}
