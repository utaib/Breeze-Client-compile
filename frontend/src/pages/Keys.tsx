import { useEffect, useMemo, useRef, useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { CommandKey, CommandKeysState, KeyBinding } from '../bridge/types'
import { EmptyState, ErrorState, Row, SearchField, Segmented, Skeleton, Switch } from '../ui/controls'
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
        <div className="rows" role="list" aria-label="Breeze keys">
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
      <CommandKeysSection />
    </>
  )
}

/** Who else has a key, in words. */
function others(list: string[]): string {
  return list.length === 1 ? list[0]! : `${list.slice(0, -1).join(', ')} and ${list.at(-1)}`
}

/**
 * Command keys: a key, with Ctrl, Shift or Alt if wanted, that runs a
 * command as if it were typed. Java checks each command and asks for the
 * key the same way as for Breeze's keys; the server's permissions decide
 * what runs, as for a typed command.
 */
function useCommandKeys() {
  const { bridge, call, toast, pushEscapeLayer } = useApp()
  const list = useAction('commandKeys.list')
  const { reload, setData } = list
  const [waiting, setWaiting] = useState<string | null>(null)
  const [refused, setRefused] = useState<{ id: string; text: string } | null>(null)
  const waitingRef = useRef<string | null>(null)
  waitingRef.current = waiting

  useEffect(() => bridge.on('commandKeys.changed', (p) => {
    setWaiting(null)
    setRefused(p.refused && p.id ? { id: p.id, text: p.refused } : null)
    reload()
  }), [bridge, reload])

  useEffect(() => () => {
    if (waitingRef.current) call('keys.cancel').catch(() => {})
  }, [call])

  const cancel = () => {
    setWaiting(null)
    call('keys.cancel').catch(() => {})
  }

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
      await call('commandKeys.capture', { id })
      setRefused(null)
      setWaiting(id)
    } catch (err) {
      toast(`Could not change that key. ${describeError(err)}`, 'error')
    }
  }

  /** Errors come back as words for the caller to show where they belong. */
  const run = async (fn: () => Promise<CommandKeysState>): Promise<string | null> => {
    try {
      setData(await fn())
      return null
    } catch (err) {
      return describeError(err)
    }
  }

  return { list, waiting, refused, capture, cancel, call, run }
}

type CommandKeysApi = ReturnType<typeof useCommandKeys>

function CommandKeysSection() {
  const ck = useCommandKeys()
  const [draft, setDraft] = useState('')
  const [draftError, setDraftError] = useState<string | null>(null)
  const data = ck.list.data
  const full = data != null && data.bindings.length >= data.max

  const add = async () => {
    const before = new Set(data?.bindings.map((b) => b.id))
    let next: CommandKeysState
    try {
      next = await ck.call('commandKeys.save', { command: draft })
    } catch (err) {
      setDraftError(describeError(err))
      return
    }
    ck.list.setData(next)
    setDraft('')
    setDraftError(null)
    // Straight on to its key, as the next thing to set.
    const added = next.bindings.find((b) => !before.has(b.id))
    if (added) void ck.capture(added.id)
  }

  return (
    <section className="section command-keys" aria-labelledby="command-keys-title">
      <h2 className="section-title" id="command-keys-title">Command keys</h2>
      <p className="section-note">
        A key that runs a command as if you typed it. The server decides what you may run, as it does for typed commands.
      </p>
      {ck.list.loading && !data && <Skeleton height="3.25rem" count={2} gap="1px" />}
      {ck.list.error != null && <ErrorState error={ck.list.error} what="command keys" onRetry={ck.list.reload} />}
      {data && data.bindings.length > 0 && (
        <div className="rows" role="list" aria-label="Command keys">
          {data.bindings.map((b) => <CommandKeyRow key={b.id} binding={b} ck={ck} />)}
        </div>
      )}
      {data && (
        <form
          className="command-add"
          onSubmit={(e) => {
            e.preventDefault()
            if (!full) void add()
          }}
        >
          <input
            className={`text-field ${draftError ? 'invalid' : ''}`}
            value={draft}
            placeholder="/spawn"
            aria-label="New command"
            aria-invalid={draftError ? true : undefined}
            spellCheck={false}
            autoComplete="off"
            maxLength={300}
            disabled={full}
            onChange={(e) => {
              setDraft(e.target.value)
              if (draftError) setDraftError(null)
            }}
          />
          <button type="submit" className="btn btn-secondary btn-sm" disabled={full || draft.trim() === ''}>Add command key</button>
          {draftError && <p className="row-error" role="alert">{draftError}</p>}
          {full && <p className="section-note">That is {data.max} command keys, the most Breeze keeps. Remove one to add another.</p>}
        </form>
      )}
    </section>
  )
}

function CommandKeyRow({ binding: b, ck }: { binding: CommandKey; ck: CommandKeysApi }) {
  const [text, setText] = useState(`/${b.command}`)
  const [error, setError] = useState<string | null>(null)
  const saving = useRef(false)
  useEffect(() => {
    setText(`/${b.command}`)
    setError(null)
  }, [b.command])
  const waiting = ck.waiting === b.id

  const saveCommand = async () => {
    if (saving.current || text.trim().replace(/^\/+/, '').trim() === b.command) return
    saving.current = true
    setError(await ck.run(() => ck.call('commandKeys.save', { id: b.id, command: text })))
    saving.current = false
  }

  const desc = error
    ? null
    : ck.refused?.id === b.id
      ? <span className="warn-text">{ck.refused.text}</span>
      : b.conflicts.length > 0
        ? <span className="warn-text">{b.key} is also {others(b.conflicts)}. Pressing it does both.</span>
        : b.available === false
          ? <span className="warn-text">This server does not offer /{b.command.split(' ')[0]} to you here. The key will say so.</span>
          : !b.key ? 'No key yet.' : undefined

  return (
    <div role="listitem">
      <Row
        anchor={`command-${b.id}`}
        label={
          <span className="command-field">
            <input
              className={`text-field ${error ? 'invalid' : ''}`}
              value={text}
              aria-label={`Command for ${b.key || 'a command key'}`}
              aria-invalid={error ? true : undefined}
              spellCheck={false}
              autoComplete="off"
              maxLength={300}
              onChange={(e) => {
                setText(e.target.value)
                if (error) setError(null)
              }}
              onBlur={() => void saveCommand()}
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault()
                  void saveCommand()
                } else if (e.key === 'Escape' && text !== `/${b.command}`) {
                  e.stopPropagation()
                  setText(`/${b.command}`)
                  setError(null)
                }
              }}
            />
            {error && <span className="row-error" role="alert">{error}</span>}
          </span>
        }
        desc={desc}
      >
        <div className="keyctl">
          <button
            type="button"
            className={`keycap ${waiting ? 'waiting' : ''} ${b.conflicts.length > 0 ? 'conflict' : ''} ${b.key ? '' : 'none'}`}
            data-state={waiting ? 'waiting' : 'idle'}
            aria-label={waiting ? `Press a key for /${b.command}, with Ctrl, Shift or Alt if you like, or Escape to cancel` : `/${b.command}: ${b.key || 'no key'}. Change`}
            onClick={() => (waiting ? ck.cancel() : ck.capture(b.id))}
          >
            {waiting ? 'Press keys' : b.key || 'No key'}
          </button>
          {!waiting && b.key && (
            <button type="button" className="iconbtn" aria-label={`Remove the key of /${b.command}`} title="No key"
              onClick={() => void ck.run(() => ck.call('commandKeys.unbind', { id: b.id }))}>
              <Icon.X />
            </button>
          )}
          <Switch checked={b.enabled} label={`/${b.command} on`}
            onChange={(on) => void ck.run(() => ck.call('commandKeys.save', { id: b.id, command: b.command, enabled: on }))} />
          <button type="button" className="btn btn-ghost btn-sm" aria-label={`Delete the command key /${b.command}`}
            onClick={() => void ck.run(() => ck.call('commandKeys.remove', { id: b.id }))}>
            Delete
          </button>
        </div>
      </Row>
    </div>
  )
}
