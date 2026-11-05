import { THEMES, CUSTOM_THEME_ID, PRESET_SURFACE, presetById, toHex, accentWarning, needsDarkText } from "./themes";

/**
 * Theme picker for Settings: the presets and the player's own theme, in one
 * place (Style used to have a second copy). Picking a card applies it at
 * once; the custom theme's editor shows while it is chosen.
 */
const ACCENTS = ["#78b2ff", "#62d698", "#ff925c", "#ac8eff", "#ff7abc", "#54deca", "#ffd166", "#ef5350"];

export default function ThemeSettings({ settings, patchSettings, customTheme, setCustomTheme, guard = null }) {
  const current = String(settings.theme || "glass").toLowerCase();
  const onCustom = current === CUSTOM_THEME_ID;
  const base = presetById(customTheme.base);
  const shownAccent = customTheme.accent || toHex(base.accent);
  const shownPanel = customTheme.panel || PRESET_SURFACE[base.id];
  const warning = accentWarning(customTheme);

  const pickCustom = () => {
    // A first visit starts from whatever theme was in use, so nothing jumps.
    if (!onCustom && !customTheme.accent && !customTheme.panel && current !== customTheme.base && current !== CUSTOM_THEME_ID) {
      setCustomTheme({ ...customTheme, base: presetById(current).id });
    }
    patchSettings({ theme: CUSTOM_THEME_ID });
  };
  const update = (patch) => setCustomTheme({ ...customTheme, ...patch });

  return (
    <div className="theme-settings">
      <div className="theme-grid">
        {THEMES.map((theme) => (
          <button
            key={theme.id}
            type="button"
            className={`theme-card theme-preview-${theme.id} ${current === theme.id ? "sel" : ""}`}
            aria-pressed={current === theme.id}
            onClick={() => patchSettings({ theme: theme.id })}
          >
            <div className="theme-swatch">
              <span className="theme-swatch-bar" />
              <span className="theme-swatch-chip" />
              <span className="theme-swatch-dot" style={{ background: theme.accent }} />
            </div>
            <div className="theme-name">{theme.name}</div>
            <div className="theme-tag">{theme.tagline}</div>
          </button>
        ))}
        <button
          type="button"
          className={`theme-card theme-preview-${base.id} theme-card-custom ${onCustom ? "sel" : ""}`}
          aria-pressed={onCustom}
          onClick={pickCustom}
        >
          <div className="theme-swatch">
            <span className="theme-swatch-bar" />
            <span className="theme-swatch-chip" style={customTheme.panel ? { background: shownPanel } : undefined} />
            <span className="theme-swatch-dot" style={{ background: shownAccent }} />
          </div>
          <div className="theme-name">Your theme</div>
          <div className="theme-tag">Your accent and panels</div>
        </button>
      </div>

      {guard?.adjusted && (
        <div className="theme-guard-note" role="status">
          {guard.raised
            ? `Over your background image the panels are ${Math.round(guard.panel * 100)}% opaque, so their text stays readable.`
            : "Over your background image the panels are made more solid, so their text stays readable."}
        </div>
      )}

      {onCustom && (
        <div className="theme-editor" aria-label="Your theme">
          <div className="theme-editor-row">
            <label htmlFor="theme-base">Built on</label>
            <select id="theme-base" className="vsel" value={base.id} onChange={(e) => update({ base: e.target.value })}>
              {THEMES.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </div>
          <div className="theme-editor-row">
            <span className="theme-editor-label">Accent</span>
            <div className="dash-swatches">
              <button type="button" className={`dash-swatch theme ${customTheme.accent ? "" : "on"}`} title={`${base.name} accent`} aria-label={`${base.name} accent`}
                onClick={() => update({ accent: null })} />
              {ACCENTS.map((c) => (
                <button key={c} type="button" className={`dash-swatch ${customTheme.accent === c ? "on" : ""}`} style={{ background: c }}
                  title={c} aria-label={`Accent ${c}`} onClick={() => update({ accent: c })} />
              ))}
              <input type="color" aria-label="Any accent colour" value={shownAccent} onChange={(e) => update({ accent: e.target.value })} />
            </div>
          </div>
          <div className="theme-editor-row">
            <span className="theme-editor-label">Panels</span>
            <div className="theme-editor-panel">
              <input type="color" aria-label="Panel colour" value={shownPanel} onChange={(e) => update({ panel: e.target.value })} />
              <button type="button" className="btn" onClick={() => update({ panel: null, panelOpacity: 100 })} disabled={!customTheme.panel}>
                {base.name} panels
              </button>
              {customTheme.panel && (
                <span className="theme-editor-hint">{needsDarkText(customTheme.panel) ? "Dark text on these panels" : "Light text on these panels"}</span>
              )}
            </div>
          </div>
          {customTheme.panel && (
            <div className="theme-editor-row">
              <label htmlFor="theme-panel-opacity">Panel opacity</label>
              <input id="theme-panel-opacity" type="range" min="40" max="100" step="5" value={customTheme.panelOpacity}
                onChange={(e) => update({ panelOpacity: Number(e.target.value) })} />
              <span className="dash-look-val">{customTheme.panelOpacity}%</span>
            </div>
          )}
          {warning && <div className="theme-editor-warn" role="status">{warning}</div>}
          <div className="theme-editor-foot">
            <button type="button" className="btn" onClick={() => setCustomTheme({ base: base.id, accent: null, panel: null, panelOpacity: 100 })}>
              Reset your theme
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
