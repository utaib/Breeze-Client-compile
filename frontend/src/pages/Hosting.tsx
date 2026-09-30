import { useEffect, useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { HostingState } from '../bridge/types'
import { EmptyState, ErrorState, Skeleton } from '../ui/controls'
import { Icon } from '../ui/icons'

/**
 * Hosting opens the current singleplayer world to Breeze friends through the
 * Breeze relay, and lists worlds friends have invited you to. Nothing is
 * reported as hosted until the Java side says the world is actually open.
 */
export function Hosting() {
  const { call, toast, hello } = useApp()
  const state = useAction('hosting.state')
  const friends = useAction('friends.list', undefined, { enabled: hello.features.friends })
  const [invite, setInvite] = useState<Set<string>>(new Set())

  useEffect(() => {
    const t = setInterval(state.reload, 5000)
    return () => clearInterval(t)
  }, [state.reload])

  const run = async (fn: () => Promise<HostingState>) => {
    try {
      state.setData(await fn())
    } catch (err) {
      toast(describeError(err), 'error')
    }
  }

  const s = state.data
  const candidates = friends.data?.friends ?? []

  return (
    <div className="page page-enter">
      <div className="page-head">
        <div>
          <h1 className="page-title">Hosting</h1>
          <p className="page-sub">Open your singleplayer world to Breeze friends. No port forwarding, and only people you invite can join.</p>
        </div>
      </div>

      {state.loading && !s && <Skeleton height="6rem" />}
      {state.error != null && <ErrorState error={state.error} what="hosting" onRetry={state.reload} />}

      {s && (
        <>
          <section className="section">
            <div className={`host-card ${s.hosting ? 'on' : ''}`}>
              <div>
                <div className="host-title">{s.hosting ? 'Your world is open to invited friends' : s.canHost ? 'Ready to host this world' : 'Open a singleplayer world to host it'}</div>
                {s.status && <div className="row-desc">{s.status}</div>}
              </div>
              {s.hosting ? (
                <button className="btn btn-danger" disabled={s.busy} onClick={() => run(() => call('hosting.stop'))}>Stop hosting</button>
              ) : (
                <button className="btn btn-primary" disabled={!s.canHost || s.busy} onClick={() => run(() => call('hosting.start', { invite: [...invite] }))}>
                  {s.busy ? <Icon.Spin /> : <Icon.Server />}Start hosting
                </button>
              )}
            </div>
          </section>

          {s.canHost && !s.hosting && candidates.length > 0 && (
            <section className="section">
              <h2 className="section-title">Invite</h2>
              <div className="rows">
                {candidates.map((f) => (
                  <label className="row check-row" key={f.uuid}>
                    <span className="friend">
                      <span className={`presence ${f.online ? 'on' : ''}`} aria-hidden="true" />
                      <span className="row-label">{f.name}</span>
                    </span>
                    <input
                      type="checkbox"
                      checked={invite.has(f.uuid)}
                      onChange={(e) => setInvite((prev) => {
                        const next = new Set(prev)
                        if (e.target.checked) next.add(f.uuid)
                        else next.delete(f.uuid)
                        return next
                      })}
                    />
                  </label>
                ))}
              </div>
            </section>
          )}

          <section className="section">
            <h2 className="section-title">Invites for you</h2>
            {s.invites.length === 0 ? (
              <EmptyState title="No invites right now" text="When a friend hosts and invites you, their world shows up here." />
            ) : (
              <div className="rows">
                {s.invites.map((i) => (
                  <div className="row" key={i.host}>
                    <div className="row-text"><div className="row-label">{i.hostName}</div><div className="row-desc">Invited you to their world</div></div>
                    <button className="btn btn-primary btn-sm" onClick={() => run(() => call('hosting.join', { host: i.host }))}>Join</button>
                  </div>
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  )
}
