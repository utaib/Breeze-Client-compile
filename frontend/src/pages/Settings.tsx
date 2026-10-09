import { useState } from 'react'
import { describeError, useAction, useApp } from '../app/state'
import type { AccentId, ThemeId } from '../bridge/types'
import { ConfirmDialog, Row, Segmented, Slider, Switch } from '../ui/controls'
import { Icon } from '../ui/icons'

export const THEMES: { id: ThemeId; name: string; note: string; swatch: [string, string, string] }[] = [
  { id: 'glass', name: 'Breeze Glass', note: 'Deep blue glass', swatch: ['#0d1220', 'rgba(9,12,22,0.8)', 'rgba(255,255,255,0.09)'] },
  { id: 'black', name: 'Black', note: 'Plain and dark', swatch: ['#0a0b0d', '#050607', '#16171b'] },
  { id: 'white', name: 'White', note: 'Light', swatch: ['#e9ebf0', '#dfe3ea', '#ffffff'] },
  { id: 'silver', name: 'Silver', note: 'Cool steel', swatch: ['#141a24', '#10151d', '#1a2432'] },
  { id: 'midnight', name: 'Midnight', note: 'Violet glass', swatch: ['#100d1e', 'rgba(12,10,22,0.9)', 'rgba(190,170,255,0.1)'] },
  { id: 'forest', name: 'Forest', note: 'Green dark', swatch: ['#0d140f', '#0a100c', '#131c15'] },
  { id: 'ember', name: 'Ember', note: 'Warm dark', swatch: ['#181310', '#141008', '#201a15'] },
  { id: 'arctic', name: 'Arctic', note: 'Blue grid', swatch: ['#0d1926', '#0a1420', '#122232'] },
  { id: 'rose', name: 'Rose Noir', note: 'Plum glass', swatch: ['#170d14', 'rgba(20,10,17,0.9)', 'rgba(255,170,210,0.1)'] },
  { id: 'abyss', name: 'Abyss', note: 'True black', swatch: ['#0a0a0b', '#0a0a0b', '#111214'] },
]

export const ACCENTS: { id: AccentId; name: string; color: string }[] = [
  { id: 'blue', name: 'Blue', color: 'rgb(120, 178, 255)' },
  { id: 'green', name: 'Green', color: '#3dd68c' },
  { id: 'yellow', name: 'Yellow', color: '#f5c542' },
  { id: 'pink', name: 'Pink', color: '#f28bc0' },
  { id: 'red', name: 'Red', color: '#ef6461' },
]

export function Settings() {
  const { settings, setSetting, resetSettings, hello, call, toast } = useApp()
  const account = useAction('account.get')
  const [confirmReset, setConfirmReset] = useState(false)
  const auto = settings.uiScale === 0

  return (
    <div className="page page-enter">
      <div className="page-head">
        <div>
          <h1 className="page-title">Settings</h1>
          <p className="page-sub">How Breeze looks and behaves. These are saved on this computer, in the game's config folder.</p>
        </div>
        <button className="btn btn-ghost btn-sm" onClick={() => setConfirmReset(true)}><Icon.Reset />Restore defaults</button>
      </div>

      <section className="section">
        <h2 className="section-title">Minecraft</h2>
        <div className="rows">
          <Row label="Minecraft settings" desc="Video, sound, controls, language and resource packs: Minecraft's own options. Done brings you back here.">
            <button className="btn btn-secondary btn-sm" data-testid="minecraft-settings" onClick={() => call('game.options').catch((err) => toast(describeError(err), 'error'))}><Icon.Sliders />Open</button>
          </Row>
        </div>
      </section>

      <section className="section">
        <h2 className="section-title">Theme</h2>
        <div className="theme-grid" role="radiogroup" aria-label="Theme">
          {THEMES.map((t) => (
            <button
              key={t.id}
              role="radio"
              aria-checked={settings.theme === t.id}
              className={`theme-card ${settings.theme === t.id ? 'on' : ''}`}
              onClick={() => setSetting('theme', t.id)}
              data-testid={`theme-${t.id}`}
            >
              <span className="theme-swatch" style={{ background: t.swatch[0] }}>
                <span className="theme-swatch-bar" style={{ background: t.swatch[1] }} />
                <span className="theme-swatch-chip" style={{ background: t.swatch[2] }} />
                <span className="theme-swatch-dot" />
              </span>
              <span className="theme-name">{t.name}</span>
              <span className="theme-note">{t.note}</span>
            </button>
          ))}
        </div>
      </section>

      <section className="section">
        <h2 className="section-title">Appearance</h2>
        <div className="rows">
          <Row label="Accent colour" desc="Buttons, switches and the HUD accent in game.">
            <div className="accents" role="radiogroup" aria-label="Accent colour">
              {ACCENTS.map((a) => (
                <button
                  key={a.id}
                  role="radio"
                  aria-checked={settings.accent === a.id}
                  aria-label={a.name}
                  title={a.name}
                  className={`accent ${settings.accent === a.id ? 'on' : ''}`}
                  style={{ '--c': a.color } as React.CSSProperties}
                  onClick={() => setSetting('accent', a.id)}
                />
              ))}
            </div>
          </Row>
          <Row label="Interface size" desc={auto ? 'Fits the game window automatically.' : 'Larger or smaller than the automatic size.'}>
            <div className="size-control">
              <Segmented label="Interface size mode" value={auto ? 'auto' : 'custom'} options={[{ value: 'auto', label: 'Automatic' }, { value: 'custom', label: 'Custom' }]}
                onChange={(v) => setSetting('uiScale', v === 'auto' ? 0 : 100)} />
              {!auto && <Slider label="Interface size" value={settings.uiScale} min={75} max={150} step={5} suffix="%" onCommit={(v) => setSetting('uiScale', v)} />}
            </div>
          </Row>
          <Row label="Reduce transparency" desc="Solid panels instead of glass.">
            <Switch checked={settings.reduceTransparency} label="Reduce transparency" onChange={(v) => setSetting('reduceTransparency', v)} />
          </Row>
          <Row label="Animations" desc="Page and panel motion. Off also respects your system setting.">
            <Switch checked={settings.animations} label="Animations" onChange={(v) => setSetting('animations', v)} />
          </Row>
          <Row label="World dimming" desc="How much the game darkens behind this menu in a world.">
            <Slider label="World dimming" value={settings.backdropDim} min={0} max={90} step={5} suffix="%" onCommit={(v) => setSetting('backdropDim', v)} />
          </Row>
        </div>
      </section>

      <section className="section">
        <h2 className="section-title">Menu</h2>
        <div className="rows">
          <Row label="Breeze title screen" desc="Off brings back Minecraft's own title screen. Breeze stays one key away (Right Shift by default).">
            <Switch checked={settings.replaceTitleScreen} label="Breeze title screen" onChange={(v) => setSetting('replaceTitleScreen', v)} />
          </Row>
          {hello.features.vanillaMenu && (
            <Row label="Minecraft's title screen" desc="Switch to it for the rest of this session without changing the setting.">
              <button className="btn btn-secondary btn-sm" onClick={() => call('ui.vanillaMenu').catch((err) => toast(describeError(err), 'error'))}>Show it now</button>
            </Row>
          )}
          <Row label="Controls and keybinds" desc="Breeze keybinds live in Minecraft's Controls, under Breeze.">
            <button className="btn btn-secondary btn-sm" onClick={() => call('game.options').catch((err) => toast(describeError(err), 'error'))}><Icon.Keyboard />Open options</button>
          </Row>
        </div>
      </section>

      <section className="section">
        <h2 className="section-title">Account</h2>
        <div className="rows">
          <Row label={account.data?.playerName ?? 'Minecraft account'} desc={account.data ? `Minecraft profile ${account.data.uuid}` : 'Reading your profile.'}>
            <span className={`status-pill ${account.data?.breeze === 'ready' ? 'ok' : ''}`}>
              {account.data ? { ready: 'Breeze connected', 'signing-in': 'Connecting', 'signed-out': 'Not signed in to Breeze', unreachable: 'Breeze unreachable' }[account.data.breeze] : ''}
            </span>
          </Row>
        </div>
        <p className="section-note footnote">Breeze signs in through the launcher. Your account details stay with the launcher, and the game only receives a short-lived session.</p>
      </section>

      <section className="section">
        <h2 className="section-title">About</h2>
        <div className="rows about">
          <Row label="Breeze"><span className="mono">{hello.modVersion}</span></Row>
          <Row label="Minecraft"><span className="mono">{hello.minecraftVersion}</span></Row>
          <Row label="Fabric Loader"><span className="mono">{hello.loaderVersion}</span></Row>
          <Row label="Embedded browser"><span className="mono">{hello.chromiumVersion ? `Chromium ${hello.chromiumVersion}` : 'Unknown'}</span></Row>
        </div>
      </section>

      {confirmReset && (
        <ConfirmDialog
          title="Restore default settings?"
          text="Theme, accent, size, motion and menu settings go back to how Breeze ships. Modules keep their own settings."
          confirm="Restore defaults"
          onCancel={() => setConfirmReset(false)}
          onConfirm={() => {
            setConfirmReset(false)
            resetSettings()
          }}
        />
      )}
    </div>
  )
}
