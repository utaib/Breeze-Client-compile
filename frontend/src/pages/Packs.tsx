import { useEffect, useMemo, useState, type ReactNode } from 'react'
import { move, offPacks, sameOrder, turnOff, turnOn } from '../app/packs'
import { describeError, useAction, useApp } from '../app/state'
import type { PackInfo } from '../bridge/types'
import { EmptyState, ErrorState, SearchField, Skeleton, Switch } from '../ui/controls'
import { Icon } from '../ui/icons'

/**
 * Resource packs, from Minecraft's own pack list. Changes wait for Apply,
 * which goes through Minecraft's own options (saved, and resources reload
 * once), so the game's Resource Packs screen shows the same thing.
 */
export function PacksTab() {
  const { call, toast } = useApp()
  const list = useAction('packs.list')
  const { data, setData } = list
  const [order, setOrder] = useState<string[]>([])
  const [query, setQuery] = useState('')
  const [applying, setApplying] = useState(false)

  useEffect(() => {
    if (data) setOrder(data.selected)
  }, [data])

  const packs = useMemo(() => data?.packs ?? [], [data])
  const byId = useMemo(() => new Map(packs.map((p) => [p.id, p])), [packs])
  const dirty = data != null && !sameOrder(order, data.selected)
  const onAll = order.map((id) => byId.get(id)).filter((p): p is PackInfo => p != null)
  // The game's own and every mod's resources stay as they are: folded into one line.
  const on = onAll.filter((p) => !p.locked)
  const locked = onAll.filter((p) => p.locked)
  const off = offPacks(packs, order, query)

  const apply = async () => {
    setApplying(true)
    try {
      const next = await call('packs.apply', { selected: order })
      setData(next)
      toast(next.changed ? 'Resource packs applied. Minecraft is reloading its resources.' : 'Nothing changed.', 'ok')
    } catch (err) {
      toast(`The resource packs were not applied. ${describeError(err)}`, 'error')
    } finally {
      setApplying(false)
    }
  }

  const openFolder = async () => {
    try {
      await call('packs.openFolder')
    } catch (err) {
      toast(describeError(err), 'error')
    }
  }

  if (list.loading && !data) return <Skeleton height="3.5rem" count={4} gap="1px" />
  if (list.error != null && !data) return <ErrorState error={list.error} what="resource packs" onRetry={list.reload} />
  if (!data) return null

  return (
    <div className="packs">
      <div className="packs-bar">
        <button type="button" className="btn btn-primary btn-sm" disabled={!dirty || applying} onClick={() => void apply()}>
          {applying ? 'Applying' : 'Apply'}
        </button>
        <button type="button" className="btn btn-ghost btn-sm" disabled={!dirty || applying} onClick={() => setOrder(data.selected)}>
          Undo changes
        </button>
        <span className="packs-bar-note" aria-live="polite">
          {dirty ? 'Not applied yet. Applying reloads Minecraft\'s resources, which takes a few seconds.' : ''}
        </span>
        <button type="button" className="btn btn-secondary btn-sm" onClick={() => void openFolder()}>
          <Icon.Package />Open the folder
        </button>
      </div>

      <section className="section" aria-labelledby="packs-on-title">
        <h2 className="section-title" id="packs-on-title">On</h2>
        <p className="section-note">The top pack wins where packs change the same thing.</p>
        {on.length > 0 && (
          <div className="rows" role="list" aria-label="Resource packs on">
            {on.map((p, i) => (
              <PackRow key={p.id} pack={p} on>
                <span className="pack-move">
                  <button type="button" className="btn btn-ghost btn-sm" aria-label={`Move ${p.title} up`}
                    disabled={i === 0} onClick={() => setOrder((o) => move(o, p.id, -1, packs))}>
                    <Icon.Chevron className="up" />
                  </button>
                  <button type="button" className="btn btn-ghost btn-sm" aria-label={`Move ${p.title} down`}
                    disabled={i === on.length - 1} onClick={() => setOrder((o) => move(o, p.id, 1, packs))}>
                    <Icon.Chevron className="down" />
                  </button>
                </span>
                <Switch checked label={`${p.title} on`} onChange={() => setOrder((o) => turnOff(o, p.id, packs))} />
              </PackRow>
            ))}
          </div>
        )}
        {on.length === 0 && <p className="section-note">Only Minecraft's own look. Turn a pack on below.</p>}
        {locked.length > 0 && (
          <details className="packs-locked">
            <summary>
              Always on: {locked.length === 1 ? locked[0]!.title : `${locked[0]!.title} and ${locked.length - 1} more from the game and your mods`}
            </summary>
            <p className="section-note">These keep the game's and your mods' own resources. They stay as they are.</p>
            <ul aria-label="Resource packs always on">
              {locked.map((p) => <li key={p.id}>{p.title}</li>)}
            </ul>
          </details>
        )}
      </section>

      <section className="section" aria-labelledby="packs-off-title">
        <div className="packs-off-head">
          <h2 className="section-title" id="packs-off-title">Available</h2>
          {packs.length - order.length > 6 && <SearchField value={query} onChange={setQuery} placeholder="Search packs" />}
        </div>
        {off.length === 0
          ? <EmptyState
              title={query ? 'No pack matches' : 'Every pack is on'}
              text={query ? 'Try another name.' : 'Put .zip packs in the resourcepacks folder to see them here.'}
            />
          : (
            <div className="rows" role="list" aria-label="Resource packs available">
              {off.map((p) => (
                <PackRow key={p.id} pack={p} on={false}>
                  <Switch checked={false} label={`${p.title} on`} onChange={() => setOrder((o) => turnOn(o, p.id, packs))} />
                </PackRow>
              ))}
            </div>
          )}
      </section>
    </div>
  )
}

function compatibilityNote(p: PackInfo): string | null {
  if (p.compatible) return null
  return p.compatibility === 'too_old'
    ? 'Made for an older Minecraft: some of it may not show.'
    : 'Made for a newer Minecraft: some of it may not show.'
}

function PackRow({ pack: p, on, children }: { pack: PackInfo; on: boolean; children: ReactNode }) {
  const note = compatibilityNote(p)
  return (
    <div className={`row pack-row ${on ? 'on' : ''}`} role="listitem" data-pack={p.id}>
      <div className="pack-main">
        {p.picture
          ? <img className="pack-picture" src={p.picture} alt="" width={32} height={32} />
          : <span className="pack-picture empty" aria-hidden="true"><Icon.Package /></span>}
        <div className="row-text">
          <div className="row-label">
            {p.title}
            {!p.folder && <span className="pack-tag">Built in</span>}
          </div>
          {p.description && <div className="row-desc pack-desc">{p.description}</div>}
          {note && <div className="row-desc warn-text">{note}</div>}
        </div>
      </div>
      <div className="pack-controls">{children}</div>
    </div>
  )
}
