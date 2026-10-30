import { useState } from 'react'
import { describeError, useApp } from '../app/state'
import { argbCss, fromSettings, rects } from '../app/crosshair'
import type { ModuleInfo } from '../bridge/types'
import { Row } from '../ui/controls'

// Room for the largest crosshair (gap 12, length 20, thickness 8, outline 3)
// around the centre pixel, in GUI pixels: one GUI pixel is two screen pixels
// here, as at GUI scale 2.
const REACH = 44
const BACKDROPS = [
  { id: 'sky', label: 'Over the sky' },
  { id: 'cave', label: 'In a cave' },
]

/** The Custom Crosshair as the game will draw it, redrawn as each setting changes. */
export function CrosshairPreview({ module }: { module: ModuleInfo }) {
  const shape = rects(fromSettings(module.settings))
  return (
    <div className="xhair-preview" aria-label="Crosshair preview" role="group">
      {BACKDROPS.map((b) => (
        <figure key={b.id} className={`xhair-pane xhair-${b.id}`}>
          <svg
            viewBox={`${-REACH} ${-REACH} ${REACH * 2 + 1} ${REACH * 2 + 1}`}
            shapeRendering="crispEdges"
            role="img"
            aria-label={`Crosshair ${b.label.toLowerCase()}`}
            data-rects={shape.length}
          >
            {shape.map(([x0, y0, x1, y1, color], i) => (
              <rect key={i} x={x0} y={y0} width={x1 - x0} height={y1 - y0} fill={argbCss(color)} />
            ))}
          </svg>
          <figcaption>{b.label}</figcaption>
        </figure>
      ))}
    </div>
  )
}

/**
 * Share codes: make one from the crosshair, or use another player's. Codes
 * are made and checked in Java (crosshair.export, crosshair.import); a code
 * only ever changes the crosshair's own settings.
 */
export function CrosshairShare({ onApplied }: { onApplied: (m: ModuleInfo) => void }) {
  const { call, toast } = useApp()
  const [code, setCode] = useState<string | null>(null)
  const [paste, setPaste] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const make = async () => {
    try {
      const made = await call('crosshair.export', {})
      setCode(made.code)
      try {
        await navigator.clipboard.writeText(made.code)
        toast('Code copied.')
      } catch {
        // Some browsers inside the game refuse the clipboard: the code is
        // shown selected instead, for Ctrl+C.
        toast('Code ready. Select it and press Ctrl+C.')
      }
    } catch (err) {
      toast(`No code was made. ${describeError(err)}`, 'error')
    }
  }

  const use = async () => {
    if (!paste.trim() || busy) return
    setBusy(true)
    setError(null)
    try {
      const applied = await call('crosshair.import', { code: paste.trim() })
      onApplied(applied)
      setPaste('')
      setCode(null)
      toast('Crosshair changed to the code\'s.')
    } catch (err) {
      setError(describeError(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="section">
      <h2 className="section-title">Share</h2>
      <div className="rows">
        <Row label="Your code" desc="Gives your crosshair to another Breeze player. Only the crosshair settings above go in it.">
          <div className="xhair-code">
            {code && (
              <input readOnly value={code} className="mono" aria-label="Your crosshair code" autoFocus onFocus={(e) => e.currentTarget.select()} />
            )}
            <button className="btn btn-secondary btn-sm" onClick={make}>{code ? 'Copy again' : 'Make a code'}</button>
          </div>
        </Row>
        <Row
          label="Use a code"
          desc={error
            ? <span className="row-error" role="alert">{error}</span>
            : 'Paste a code from another player. It changes the crosshair settings above and nothing else.'}
        >
          <div className="xhair-code">
            <input
              value={paste}
              onChange={(e) => { setPaste(e.target.value); setError(null) }}
              onKeyDown={(e) => e.key === 'Enter' && use()}
              placeholder="BZC-..."
              aria-label="Crosshair code to use"
              className="mono"
              maxLength={200}
              spellCheck={false}
            />
            <button className="btn btn-primary btn-sm" disabled={!paste.trim() || busy} onClick={use}>Use</button>
          </div>
        </Row>
      </div>
    </section>
  )
}
