import { useEffect, useRef, useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { ModuleInfo, ModuleSetting, SettingValue } from '../bridge/types'
import { ConfirmDialog, EmptyState, ErrorState, Row, Segmented, Skeleton, Slider, Switch } from '../ui/controls'
import { Icon } from '../ui/icons'
import { ModuleGlyph, useModuleIcons } from '../ui/ModuleGlyph'
import { CATEGORY } from './Mods'

export function ModuleDetail({ name }: { name: string }) {
  const { call, toast, navigate, hello } = useApp()
  const list = useAction('modules.list')
  const icons = useModuleIcons()
  const [module, setModule] = useState<ModuleInfo | null>(null)
  const [confirmReset, setConfirmReset] = useState(false)
  // Newest answer wins; see setSetting in app/state.tsx for why.
  const seq = useRef(0)

  useEffect(() => {
    if (list.data) setModule(list.data.find((m) => m.name === name) ?? null)
  }, [list.data, name])

  if (list.loading && !list.data) return <div className="page"><Skeleton height="4rem" count={4} /></div>
  if (list.error != null) return <div className="page"><ErrorState error={list.error} what="this module" onRetry={list.reload} /></div>
  if (!module) {
    return (
      <div className="page">
        <EmptyState
          title={`${name} is not a module on this version`}
          text="It may have been renamed or removed."
          action={<button className="btn btn-secondary btn-sm" onClick={() => navigate({ name: 'mods', tab: 'modules' })}>All modules</button>}
        />
      </div>
    )
  }

  const cat = CATEGORY[module.category] ?? CATEGORY.Utility

  const apply = async (fn: () => Promise<ModuleInfo>, rollback: ModuleInfo, failure: string) => {
    const mine = ++seq.current
    try {
      const saved = await fn()
      if (mine === seq.current) setModule(saved)
    } catch (err) {
      if (mine === seq.current) setModule(rollback)
      toast(`${failure} ${describeError(err)}`, 'error')
    }
  }

  const setEnabled = (enabled: boolean) => {
    const before = module
    setModule({ ...module, enabled })
    apply(() => call('modules.setEnabled', { name: module.name, enabled }), before, `${module.name} could not be changed.`)
  }

  const setSetting = (s: ModuleSetting, value: SettingValue) => {
    const before = module
    setModule({ ...module, settings: module.settings.map((x) => (x.id === s.id ? ({ ...x, value } as ModuleSetting) : x)) })
    apply(() => call('modules.setSetting', { name: module.name, id: s.id, value }), before, `${s.label} was not saved.`)
  }

  const groups = new Map<string, ModuleSetting[]>()
  for (const s of module.settings) {
    const g = s.group || 'General'
    groups.set(g, [...(groups.get(g) ?? []), s])
  }

  return (
    <div className="page page-enter module-detail">
      <div className="page-head">
        <div className="detail-id">
          <ModuleGlyph name={module.name} tone={cat.tone} symbol={cat.icon} icon={icons[module.name]} large />
          <div>
            <h1 className="page-title">{module.name}</h1>
            <p className="page-sub">{module.description}</p>
          </div>
        </div>
        <div className="detail-actions">
          {module.hud && hello.context === 'ingame' && (
            <button className="btn btn-secondary btn-sm" onClick={() => call('hud.openEditor').catch((err) => toast(describeError(err), 'error'))}>
              <Icon.Layout />Position on screen
            </button>
          )}
          {module.settings.length > 0 && (
            <button className="btn btn-ghost btn-sm" onClick={() => setConfirmReset(true)}><Icon.Reset />Reset</button>
          )}
        </div>
      </div>

      <div className="rows">
        <Row label="On" desc={module.keybind ? `Also toggled with ${module.keybind}.` : 'Set a key for it in Minecraft\'s Controls.'}>
          <Switch checked={module.enabled} label={`${module.name} on`} onChange={setEnabled} />
        </Row>
      </div>

      {module.settings.length === 0 && (
        <p className="section-note detail-none">This module has nothing to adjust.</p>
      )}

      {[...groups.entries()].map(([group, settings]) => (
        <section className="section" key={group}>
          <h2 className="section-title">{group}</h2>
          <div className="rows">
            {settings.map((s) => (
              <Row key={s.id} label={s.label}>
                <SettingControl setting={s} onChange={(v) => setSetting(s, v)} />
              </Row>
            ))}
          </div>
        </section>
      ))}

      {confirmReset && (
        <ConfirmDialog
          title={`Reset ${module.name}?`}
          text="Every setting on this module goes back to its default. Whether it is on stays as it is."
          confirm="Reset settings"
          onCancel={() => setConfirmReset(false)}
          onConfirm={() => {
            setConfirmReset(false)
            apply(() => call('modules.reset', { name: module.name }), module, `${module.name} could not be reset.`)
          }}
        />
      )}
    </div>
  )
}

function SettingControl({ setting, onChange }: { setting: ModuleSetting; onChange: (v: SettingValue) => void }) {
  switch (setting.type) {
    case 'bool':
      return <Switch checked={setting.value} label={setting.label} onChange={onChange} />
    case 'int':
      return <Slider value={setting.value} min={setting.min} max={setting.max} suffix={setting.suffix} label={setting.label} onCommit={onChange} />
    case 'mode':
      return setting.options.length <= 4
        ? <Segmented label={setting.label} value={setting.value} options={setting.options.map((o) => ({ value: o, label: o }))} onChange={onChange} />
        : <ModeStepper setting={setting} onChange={onChange} />
    case 'color':
      return <ColorControl value={setting.value} label={setting.label} onChange={onChange} />
  }
}

/** More than four options: step through them rather than overflow the row. */
function ModeStepper({ setting, onChange }: { setting: Extract<ModuleSetting, { type: 'mode' }>; onChange: (v: string) => void }) {
  const i = Math.max(0, setting.options.indexOf(setting.value))
  const n = setting.options.length
  return (
    <div className="stepper" role="group" aria-label={setting.label}>
      <button className="iconbtn" aria-label="Previous" onClick={() => onChange(setting.options[(i - 1 + n) % n]!)}><Icon.Back /></button>
      <span className="stepper-value">{setting.value}</span>
      <button className="iconbtn" aria-label="Next" onClick={() => onChange(setting.options[(i + 1) % n]!)}><Icon.Chevron /></button>
    </div>
  )
}

const HEX = /^#([0-9a-f]{6}|[0-9a-f]{8})$/i

/** Colours arrive as #AARRGGBB from Java. The swatch shows alpha over a checker. */
function ColorControl({ value, label, onChange }: { value: string; label: string; onChange: (v: string) => void }) {
  const [text, setText] = useState(value)
  useEffect(() => setText(value), [value])
  const css = toCss(value)
  const valid = HEX.test(text)
  const commit = () => {
    if (!valid) return setText(value)
    const normal = text.length === 7 ? `#FF${text.slice(1)}` : text
    if (normal.toUpperCase() !== value.toUpperCase()) onChange(normal.toUpperCase())
  }
  return (
    <label className={`color-field ${valid ? '' : 'invalid'}`}>
      <span className="color-swatch"><span style={{ background: css }} /></span>
      <input
        value={text}
        aria-label={label}
        spellCheck={false}
        maxLength={9}
        onChange={(e) => setText(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => e.key === 'Enter' && commit()}
        className="mono"
      />
    </label>
  )
}

function toCss(hex: string): string {
  if (!HEX.test(hex)) return 'transparent'
  if (hex.length === 7) return hex
  const a = parseInt(hex.slice(1, 3), 16) / 255
  return `rgba(${parseInt(hex.slice(3, 5), 16)}, ${parseInt(hex.slice(5, 7), 16)}, ${parseInt(hex.slice(7, 9), 16)}, ${a.toFixed(3)})`
}
