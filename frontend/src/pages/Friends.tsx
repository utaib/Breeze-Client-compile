import { useEffect, useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { Friend, FriendsState } from '../bridge/types'
import { ConfirmDialog, EmptyState, ErrorState, Skeleton } from '../ui/controls'
import { Icon } from '../ui/icons'

const NAME = /^[A-Za-z0-9_]{3,16}$/

function ago(ms: number): string {
  const s = Math.max(0, Math.round((Date.now() - ms) / 1000))
  if (s < 5) return 'just now'
  if (s < 60) return `${s} seconds ago`
  const m = Math.round(s / 60)
  return m === 1 ? 'a minute ago' : `${m} minutes ago`
}

export function Friends() {
  const { call, toast } = useApp()
  const list = useAction('friends.list')
  const [name, setName] = useState('')
  const [removing, setRemoving] = useState<Friend | null>(null)
  const [, tick] = useState(0)

  // Friends refresh on the Java side's own poll; re-read it while open.
  useEffect(() => {
    const t = setInterval(() => {
      list.reload()
      tick((n) => n + 1)
    }, 15_000)
    return () => clearInterval(t)
  }, [list.reload]) // eslint-disable-line react-hooks/exhaustive-deps

  const run = async (fn: () => Promise<FriendsState>, done?: string) => {
    try {
      list.setData(await fn())
      if (done) toast(done, 'ok')
    } catch (err) {
      toast(describeError(err), 'error')
    }
  }

  const valid = NAME.test(name)
  const data = list.data
  const online = data?.friends.filter((f) => f.online) ?? []
  const offline = data?.friends.filter((f) => !f.online) ?? []

  return (
    <div className="page page-enter">
      <div className="page-head">
        <div>
          <h1 className="page-title">Friends</h1>
          <p className="page-sub">Your Breeze friends, and who is playing right now.</p>
        </div>
        <form
          className="add-friend"
          onSubmit={(e) => {
            e.preventDefault()
            if (!valid) return
            run(() => call('friends.request', { name }), `Friend request sent to ${name}`)
            setName('')
          }}
        >
          <label className="field">
            <Icon.UserPlus />
            <input value={name} placeholder="Minecraft name" aria-label="Minecraft name" spellCheck={false} maxLength={16} onChange={(e) => setName(e.target.value.trim())} />
          </label>
          <button className="btn btn-primary" type="submit" disabled={!valid}>Add friend</button>
        </form>
      </div>

      {list.loading && !data && <Skeleton height="3.25rem" count={4} gap="1px" />}
      {list.error != null && <ErrorState error={list.error} what="your friends" onRetry={list.reload} />}

      {data && (
        <>
          {data.incoming.length > 0 && (
            <section className="section">
              <h2 className="section-title">Requests</h2>
              <div className="rows">
                {data.incoming.map((r) => (
                  <div className="row" key={r.uuid}>
                    <div className="row-text"><div className="row-label">{r.name}</div><div className="row-desc">Wants to be your friend</div></div>
                    <div className="detail-actions">
                      <button className="btn btn-ghost btn-sm" onClick={() => run(() => call('friends.respond', { uuid: r.uuid, accept: false }))}>Decline</button>
                      <button className="btn btn-primary btn-sm" onClick={() => run(() => call('friends.respond', { uuid: r.uuid, accept: true }), `You and ${r.name} are now friends`)}>Accept</button>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}

          <section className="section">
            <h2 className="section-title">Friends</h2>
            {data.friends.length === 0 ? (
              <EmptyState title="No friends yet" text="Add someone by their Minecraft name. They see your request next time they play with Breeze." />
            ) : (
              <div className="rows" role="list">
                {[...online, ...offline].map((f) => (
                  <div className="row" role="listitem" key={f.uuid}>
                    <div className="friend">
                      <span className={`presence ${f.online ? 'on' : ''}`} aria-hidden="true" />
                      <div className="row-text">
                        <div className="row-label">{f.name}</div>
                        <div className="row-desc">{f.online ? 'Playing now' : 'Offline'}</div>
                      </div>
                    </div>
                    <button className="iconbtn" aria-label={`Remove ${f.name}`} onClick={() => setRemoving(f)}><Icon.X /></button>
                  </div>
                ))}
              </div>
            )}
          </section>

          {data.outgoing.length > 0 && (
            <section className="section">
              <h2 className="section-title">Sent</h2>
              <div className="rows">
                {data.outgoing.map((r) => (
                  <div className="row" key={r.uuid}><div className="row-text"><div className="row-label">{r.name}</div><div className="row-desc">Waiting for them to accept</div></div></div>
                ))}
              </div>
            </section>
          )}

          <p className="section-note footnote">
            {data.status || (data.updatedAt ? `Updated ${ago(data.updatedAt)}` : 'Waiting for the first update from Breeze.')}
          </p>
        </>
      )}

      {removing && (
        <ConfirmDialog
          title={`Remove ${removing.name}?`}
          text="You stop seeing each other here. You can add them again later."
          confirm="Remove friend"
          tone="danger"
          onCancel={() => setRemoving(null)}
          onConfirm={() => {
            const f = removing
            setRemoving(null)
            run(() => call('friends.remove', { uuid: f.uuid }), `${f.name} removed`)
          }}
        />
      )}
    </div>
  )
}
