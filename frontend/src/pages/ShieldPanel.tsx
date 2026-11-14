import { useEffect, useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { ModuleInfo } from '../bridge/types'
import { ErrorState, Skeleton } from '../ui/controls'
import { Icon } from '../ui/icons'

/**
 * Custom Shield's picture: the pictures in the breeze_shields folder to
 * choose from, and the shield's front as the game will draw it. The preview
 * is made in Java by the same code that makes the texture (shield.state), so
 * Fit and the resource pack's shield round the picture show as they will.
 */
export function ShieldPanel({ module }: { module: ModuleInfo }) {
  const { call, toast } = useApp()
  const state = useAction('shield.state')
  const { data, setData, reload } = state
  const [busy, setBusy] = useState(false)

  // Fit, sharing and the module's switch are ordinary settings on this page;
  // the preview and the sharing line follow them.
  const fit = module.settings.find((s) => s.id === 'fit')?.value
  const share = module.settings.find((s) => s.id === 'share')?.value
  useEffect(() => {
    void reload()
  }, [fit, share, module.enabled, reload])

  const choose = async (picture: string) => {
    setBusy(true)
    try {
      setData(await call('shield.choose', { picture }))
    } catch (err) {
      toast(`That picture was not chosen. ${describeError(err)}`, 'error')
    } finally {
      setBusy(false)
    }
  }

  const openFolder = async () => {
    try {
      await call('shield.openFolder')
    } catch (err) {
      toast(describeError(err), 'error')
    }
  }

  if (state.loading && !data) return <Skeleton height="10rem" />
  if (state.error != null && !data) return <ErrorState error={state.error} what="your shield pictures" onRetry={reload} />
  if (!data) return null

  const sharingLine = data.shareStatus
    || (!data.sharing ? 'Only you see it. Turn on "Show it to other Breeze players" below to share it.'
      : !data.enabled ? 'Turn Custom Shield on to share your picture.'
        : !data.picture ? 'Choose a picture to share it.' : 'Sharing your shield.')

  return (
    <section className="section shield" aria-labelledby="shield-title" data-anchor="picture">
      <h2 className="section-title" id="shield-title">Your picture</h2>
      <div className="shield-layout">
        <figure className="shield-face">
          {data.preview
            ? <img src={data.preview} alt="Your shield's front as the game will draw it" />
            : <span className="shield-face-empty" aria-hidden="true"><Icon.Shield /></span>}
          <figcaption>{data.preview ? 'As it will look' : 'Minecraft\'s own shield'}</figcaption>
        </figure>
        <div className="shield-pick">
          <div className="shield-list" role="radiogroup" aria-label="Shield picture">
            <PictureRow name="No picture" checked={data.picture === ''} disabled={busy} onPick={() => void choose('')} />
            {data.pictures.map((p) => (
              <PictureRow key={p} name={p} checked={data.picture === p} disabled={busy} onPick={() => void choose(p)} />
            ))}
          </div>
          {data.pictures.length === 0 && (
            <p className="section-note">No pictures yet. Put a PNG or JPEG in the breeze_shields folder, then look again.</p>
          )}
          {data.problem && <p className="row-desc warn-text" role="alert">{data.problem}</p>}
          <div className="shield-actions">
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => void openFolder()}>
              <Icon.Package />Open the folder
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => void reload()}>
              <Icon.Reset />Look again
            </button>
          </div>
        </div>
      </div>
      {!data.enabled && data.picture && (
        <p className="section-note">Turn Custom Shield on to see the picture on your shield.</p>
      )}
      <p className="section-note" aria-live="polite">{sharingLine}</p>
    </section>
  )
}

function PictureRow({ name, checked, disabled, onPick }: { name: string; checked: boolean; disabled: boolean; onPick: () => void }) {
  return (
    <button
      type="button"
      role="radio"
      aria-checked={checked}
      className={`shield-pick-row ${checked ? 'on' : ''}`}
      disabled={disabled}
      onClick={onPick}
    >
      <span className="shield-pick-dot" aria-hidden="true" />
      <span className="shield-pick-name">{name}</span>
    </button>
  )
}
