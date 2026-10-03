import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { describeError, useApp } from '../app/state'
import { BridgeError } from '../bridge/client'
import type { Role } from '../bridge/types'
import { Icon } from './icons'

export function Switch({ checked, onChange, label, disabled }: {
  checked: boolean
  onChange: (next: boolean) => void
  label: string
  disabled?: boolean
}) {
  return (
    <button
      type="button"
      role="switch"
      className="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      onClick={(e) => {
        e.stopPropagation()
        onChange(!checked)
      }}
    />
  )
}

/**
 * A range slider that shows its value and commits on release, so dragging does
 * not send a bridge request per pixel. onInput fires live for previews.
 */
export function Slider({ value, min, max, step = 1, suffix = '', label, onCommit, onInput, format }: {
  value: number
  min: number
  max: number
  step?: number
  suffix?: string
  label: string
  onCommit: (v: number) => void
  onInput?: (v: number) => void
  format?: (v: number) => string
}) {
  const [local, setLocal] = useState(value)
  const dragging = useRef(false)
  useEffect(() => {
    if (!dragging.current) setLocal(value)
  }, [value])
  const fill = max === min ? 0 : ((local - min) / (max - min)) * 100
  const commit = () => {
    dragging.current = false
    if (local !== value) onCommit(local)
  }
  return (
    <div className="slider">
      <input
        type="range"
        min={min}
        max={max}
        step={step}
        value={local}
        aria-label={label}
        style={{ '--fill': `${fill}%` } as React.CSSProperties}
        onPointerDown={() => (dragging.current = true)}
        onChange={(e) => {
          const v = Number(e.target.value)
          setLocal(v)
          onInput?.(v)
          if (!dragging.current) onCommit(v)
        }}
        onPointerUp={commit}
        onBlur={commit}
      />
      <span className="slider-value">{format ? format(local) : `${local}${suffix}`}</span>
    </div>
  )
}

export function Segmented<T extends string>({ value, options, onChange, label }: {
  value: T
  options: { value: T; label: string }[]
  onChange: (v: T) => void
  label: string
}) {
  return (
    <div className="seg" role="group" aria-label={label}>
      {options.map((o) => (
        <button key={o.value} type="button" aria-pressed={o.value === value} onClick={() => onChange(o.value)}>
          {o.label}
        </button>
      ))}
    </div>
  )
}

export function SearchField({ value, onChange, placeholder, autoFocus }: {
  value: string
  onChange: (v: string) => void
  placeholder: string
  autoFocus?: boolean
}) {
  return (
    <label className="field" style={{ minWidth: '15rem' }}>
      <Icon.Search />
      <input
        type="search"
        value={value}
        placeholder={placeholder}
        aria-label={placeholder}
        autoFocus={autoFocus}
        spellCheck={false}
        onChange={(e) => onChange(e.target.value)}
        onKeyDown={(e) => {
          // Clearing the search is the first thing Escape should undo.
          if (e.key === 'Escape' && value) {
            e.stopPropagation()
            e.preventDefault()
            onChange('')
          }
        }}
      />
    </label>
  )
}

export function Row({ label, desc, children }: { label: ReactNode; desc?: ReactNode; children: ReactNode }) {
  return (
    <div className="row">
      <div className="row-text">
        <div className="row-label">{label}</div>
        {desc && <div className="row-desc">{desc}</div>}
      </div>
      {children}
    </div>
  )
}

export function Skeleton({ height, count = 1, gap = '0.625rem' }: { height: string; count?: number; gap?: string }) {
  return (
    <div style={{ display: 'grid', gap }} aria-busy="true" aria-label="Loading">
      {Array.from({ length: count }, (_, i) => <div key={i} className="skeleton" style={{ height }} />)}
    </div>
  )
}

export function EmptyState({ title, text, action }: { title: string; text: string; action?: ReactNode }) {
  return (
    <div className="state" role="status">
      <div className="state-title">{title}</div>
      <div className="state-text">{text}</div>
      {action}
    </div>
  )
}

/** Explains a failed load in terms of what the player can do about it. */
export function ErrorState({ error, onRetry, what }: { error: unknown; onRetry?: () => void; what: string }) {
  const code = error instanceof BridgeError ? error.code : null
  const signedOut = code === 'FORBIDDEN'
  const title = signedOut ? 'Sign in to Breeze to see this' : `Could not load ${what}`
  const text = signedOut
    ? 'Start Minecraft from the Breeze launcher while signed in, and your account comes with you.'
    : describeError(error)
  return (
    <div className={`state ${signedOut ? '' : 'error'}`} role="alert">
      <div className="state-title">{title}</div>
      <div className="state-text">{text}</div>
      {onRetry && !signedOut && <button className="btn btn-secondary btn-sm" onClick={onRetry}><Icon.Reset />Try again</button>}
    </div>
  )
}

export function RoleBadge({ role }: { role: Role | null }) {
  if (!role || role === 'user') return null
  const label = role === 'owner' ? 'Owner' : role === 'developer' ? 'Developer' : 'Creator'
  return <span className={`role role-${role}`}><Icon.WindCharge />{label}</span>
}

/** A confirmation dialog. Escape and the backdrop both cancel. */
export function ConfirmDialog({ title, text, confirm, tone = 'primary', onConfirm, onCancel }: {
  title: string
  text: string
  confirm: string
  tone?: 'primary' | 'danger'
  onConfirm: () => void
  onCancel: () => void
}) {
  const { pushEscapeLayer } = useApp()
  const titleId = useId()
  const confirmRef = useRef<HTMLButtonElement>(null)
  useEffect(() => pushEscapeLayer(() => {
    onCancel()
    return true
  }), [pushEscapeLayer, onCancel])
  useEffect(() => confirmRef.current?.focus(), [])
  return (
    <div className="overlay" onMouseDown={(e) => e.target === e.currentTarget && onCancel()}>
      <div className="dialog" role="dialog" aria-modal="true" aria-labelledby={titleId}>
        <div className="dialog-title" id={titleId}>{title}</div>
        <div className="dialog-text">{text}</div>
        <div className="dialog-actions">
          <button className="btn btn-ghost" onClick={onCancel}>Cancel</button>
          <button ref={confirmRef} className={`btn ${tone === 'danger' ? 'btn-danger' : 'btn-primary'}`} onClick={onConfirm}>{confirm}</button>
        </div>
      </div>
    </div>
  )
}

export function Toasts() {
  const { toasts, dismissToast } = useApp()
  return (
    <div className="toasts" aria-live="polite">
      {toasts.map((t) => (
        <div key={t.id} className={`toast ${t.tone}`} role={t.tone === 'error' ? 'alert' : 'status'}>
          <span className="toast-text">{t.text}</span>
          <button className="iconbtn" aria-label="Dismiss" onClick={() => dismissToast(t.id)}><Icon.X /></button>
        </div>
      ))}
    </div>
  )
}
