import { useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { Cape } from '../bridge/types'
import { EmptyState, ErrorState, Skeleton } from '../ui/controls'
import { Icon } from '../ui/icons'

/**
 * Capes the Breeze API says this account owns, and which one is equipped.
 * Ownership and the equipped state come from the backend, never from here.
 * Other cosmetic types appear once the mod can render them in game.
 */
export function Wardrobe() {
  const { call, toast } = useApp()
  const state = useAction('cosmetics.state')
  const [busy, setBusy] = useState<string | null>(null)

  const equip = async (cape: Cape | null) => {
    const key = cape?.id ?? 'none'
    setBusy(key)
    try {
      state.setData(await call('cosmetics.equipCape', { id: cape?.id ?? null }))
      toast(cape ? `${cape.name} equipped` : 'Cape removed', 'ok')
    } catch (err) {
      toast(`The cape was not changed. ${describeError(err)}`, 'error')
    } finally {
      setBusy(null)
    }
  }

  const capes = state.data?.capes ?? []
  const equipped = capes.find((c) => c.id === state.data?.equippedCapeId) ?? null

  return (
    <div className="page page-enter">
      <div className="page-head">
        <div>
          <h1 className="page-title">Wardrobe</h1>
          <p className="page-sub">Capes on your Breeze account. Other players with Breeze see the one you equip.</p>
        </div>
        <div className="detail-actions">
          <button className="btn btn-ghost btn-sm" onClick={state.reload} disabled={state.loading}><Icon.Reset />Refresh</button>
          {equipped && <button className="btn btn-secondary btn-sm" disabled={busy != null} onClick={() => equip(null)}>Remove cape</button>}
        </div>
      </div>

      {state.loading && !state.data && <div className="cape-grid"><Skeleton height="13rem" count={3} /></div>}
      {state.error != null && <ErrorState error={state.error} what="your capes" onRetry={state.reload} />}
      {state.data && capes.length === 0 && (
        <EmptyState
          title="No capes on this account yet"
          text="Capes you get on breezeclient.net appear here, ready to wear."
          action={<button className="btn btn-secondary btn-sm" onClick={() => call('app.openExternal', { url: 'https://breezeclient.net' }).catch((err) => toast(describeError(err), 'error'))}><Icon.External />Open breezeclient.net</button>}
        />
      )}

      {capes.length > 0 && (
        <div className="cape-grid" role="list">
          {capes.map((c) => {
            const on = c.id === state.data?.equippedCapeId
            return (
              <div key={c.id} role="listitem" className={`cape ${on ? 'on' : ''}`} data-testid={`cape-${c.id}`}>
                <div className="cape-stage">
                  {c.preview
                    ? <span className="cape-front" style={{ backgroundImage: `url(${c.preview})` }} aria-hidden="true" />
                    : <span className="cape-blank" aria-hidden="true" />}
                </div>
                <div className="cape-info">
                  <div className="cape-name">{c.name}</div>
                  {on ? (
                    <span className="cape-state"><Icon.Check />Equipped</span>
                  ) : (
                    <button className="btn btn-secondary btn-sm" disabled={busy != null} onClick={() => equip(c)}>
                      {busy === c.id ? <Icon.Spin /> : null}Equip
                    </button>
                  )}
                </div>
              </div>
            )
          })}
        </div>
      )}
    </div>
  )
}
