import { useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { Cape, OwnedCosmetic } from '../bridge/types'
import { EmptyState, ErrorState, Skeleton } from '../ui/controls'
import { Icon } from '../ui/icons'

/**
 * Capes the Breeze API says this account owns, and which one is equipped,
 * then the rest of the catalogue (got in the launcher's store), then the
 * account's 3D cosmetics, equipped from here with the game's own token.
 * Ownership and the equipped state come from the backend, never from here.
 * When the API cannot take a change from the game (owned is null), the 3D
 * cosmetics the account wears are listed without controls.
 */

const SLOT_LABEL: Record<string, string> = {
  hat: 'Hat', wings: 'Wings', pet: 'Pet', cape: 'Cape', shield: 'Shield', aura: 'Aura', back: 'Back', trail: 'Trail',
}
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

  const equipModel = async (item: OwnedCosmetic) => {
    setBusy(`model-${item.id}`)
    try {
      state.setData(item.equipped
        ? await call('cosmetics.unequipModel', { slot: item.slot })
        : await call('cosmetics.equipModel', { id: item.id }))
      toast(item.equipped ? `${item.name} removed` : `${item.name} equipped`, 'ok')
    } catch (err) {
      toast(`The cosmetic was not changed. ${describeError(err)}`, 'error')
    } finally {
      setBusy(null)
    }
  }

  const capes = state.data?.capes ?? []
  const store = state.data?.store ?? []
  const equipped = capes.find((c) => c.id === state.data?.equippedCapeId) ?? null
  // Taking a personal cape off loses it, as in the launcher, so it is not offered here.
  const canRemove = equipped != null && !equipped.personal && state.data?.canRemove === true

  return (
    <div className="page page-enter">
      <div className="page-head">
        <div>
          <h1 className="page-title">Wardrobe</h1>
          <p className="page-sub">Capes and 3D cosmetics on your Breeze account. Other players with Breeze see what you wear.</p>
        </div>
        <div className="detail-actions">
          <button className="btn btn-ghost btn-sm" onClick={state.reload} disabled={state.loading}><Icon.Reset />Refresh</button>
          {canRemove && <button className="btn btn-secondary btn-sm" disabled={busy != null} onClick={() => equip(null)}>Remove cape</button>}
        </div>
      </div>

      {state.loading && !state.data && <div className="cape-grid"><Skeleton height="13rem" count={3} /></div>}
      {state.error != null && <ErrorState error={state.error} what="your capes" onRetry={state.reload} />}
      {state.data && capes.length === 0 && (
        <EmptyState
          title="No capes on this account yet"
          text="Capes you get in the Breeze launcher's store appear here, ready to wear."
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
                {c.personal && <div className="cape-note">Your own upload. Equipping another cape replaces it.</div>}
              </div>
            )
          })}
        </div>
      )}

      {store.length > 0 && (
        <section className="section" aria-labelledby="store-title">
          <h2 className="section-title" id="store-title">More capes</h2>
          <p className="section-note">Get these in the Breeze launcher's store. Once one is yours, wear it from here.</p>
          <div className="cape-grid" role="list">
            {store.map((c) => (
              <div key={c.id} role="listitem" className="cape locked" data-testid={`store-${c.id}`}>
                <div className="cape-stage">
                  {c.preview
                    ? <span className="cape-front" style={{ backgroundImage: `url(${c.preview})` }} aria-hidden="true" />
                    : <span className="cape-blank" aria-hidden="true" />}
                </div>
                <div className="cape-info">
                  <div className="cape-name">{c.name}</div>
                  <span className="cape-state">In the launcher</span>
                </div>
              </div>
            ))}
          </div>
        </section>
      )}

      {state.data && (
        <section className="section" aria-labelledby="worn-title">
          <h2 className="section-title" id="worn-title">3D cosmetics</h2>
          {state.data.owned ? (
            <>
              <p className="section-note">Drawn on you in game, for you and other Breeze players. One per slot.</p>
              {state.data.owned.length === 0 ? (
                <div className="state" data-testid="owned-empty">
                  <div className="state-text">No 3D cosmetics on this account yet. Ones you get on breezeclient.net appear here.</div>
                </div>
              ) : (
                <div className="rows" role="list">
                  {state.data.owned.map((o) => (
                    <div key={o.id} role="listitem" className="row" data-testid={`owned-${o.id}`}>
                      <div className="row-text">
                        <div className="row-label">{o.name}</div>
                        <div className="row-desc">{SLOT_LABEL[o.slot] ?? o.slot}{o.equipped ? ', wearing' : ''}</div>
                      </div>
                      <button className={`btn btn-sm ${o.equipped ? 'btn-ghost' : 'btn-secondary'}`} disabled={busy != null} onClick={() => equipModel(o)}>
                        {busy === `model-${o.id}` ? <Icon.Spin /> : null}{o.equipped ? 'Remove' : 'Equip'}
                      </button>
                    </div>
                  ))}
                </div>
              )}
            </>
          ) : (
            <>
              <p className="section-note">Drawn on you in game, for you and other Breeze players. Change them in the Breeze launcher's Wardrobe.</p>
              {state.data.worn.length === 0 ? (
                <div className="state" data-testid="worn-empty">
                  <div className="state-text">You are not wearing any 3D cosmetics.</div>
                </div>
              ) : (
                <div className="rows" role="list">
                  {state.data.worn.map((w) => (
                    <div key={w.id} role="listitem" className="row" data-testid={`worn-${w.id}`}>
                      <div className="row-text">
                        <div className="row-label">{w.name}</div>
                        <div className="row-desc">{SLOT_LABEL[w.slot] ?? w.slot}</div>
                      </div>
                      <span className="cape-state"><Icon.Check />Wearing</span>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}
        </section>
      )}
    </div>
  )
}
