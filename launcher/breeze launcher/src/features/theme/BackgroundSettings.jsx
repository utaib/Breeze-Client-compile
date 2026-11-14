import { useRef } from "react";

/**
 * The launcher's own background image and how it is shown, next to the
 * theme in Settings (it used to sit on the Style page). The image is stored
 * by the Rust side; every adjustment is a launcher setting.
 */
const I = {
  Upload: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" y1="3" x2="12" y2="15" /></svg>,
  X: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round"><path d="M18 6 6 18M6 6l12 12" /></svg>,
};

const BG_POSITIONS = [
  ["left top", "↖"], ["center top", "↑"], ["right top", "↗"],
  ["left center", "←"], ["center", "•"], ["right center", "→"],
  ["left bottom", "↙"], ["center bottom", "↓"], ["right bottom", "↘"],
];

export default function BackgroundSettings({ customBg, setCustomBg, status = "none", settings, patchSettings }) {
  const bgInputRef = useRef(null);

  // The file is handed straight to the Rust side, which writes it to disk and
  // returns a data URL. Reading it into a data URL here and stuffing it in
  // localStorage is what used to fail silently on anything above ~5MB.
  const handleBgPick = (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    if (!file || !setCustomBg) return;
    setCustomBg(file);
  };

  const bg = {
    dim: settings.bgDim ?? 35,
    blur: settings.bgBlur ?? 0,
    brightness: settings.bgBrightness ?? 100,
    opacity: settings.bgOpacity ?? 100,
    scale: settings.bgScale ?? 100,
    position: settings.bgPosition || "center",
  };
  const BG_SLIDERS = [
    { key: "bgDim", label: "Dimness", value: bg.dim, min: 0, max: 90, suffix: "%" },
    { key: "bgBrightness", label: "Brightness", value: bg.brightness, min: 40, max: 160, suffix: "%" },
    { key: "bgOpacity", label: "Opacity", value: bg.opacity, min: 20, max: 100, suffix: "%" },
    { key: "bgBlur", label: "Blur", value: bg.blur, min: 0, max: 24, suffix: "px" },
    { key: "bgScale", label: "Scale", value: bg.scale, min: 100, max: 250, suffix: "%" },
  ];

  return (
    <>
      <input ref={bgInputRef} type="file" accept="image/png,image/jpeg,image/webp,image/gif,image/avif,image/bmp" hidden onChange={handleBgPick} />
      <div className="sr">
        <div className="si">
          <div className="sn">Background image</div>
          <div className="sd">
            {!customBg
              ? "A dimmed image behind the main panel, kept subtle so text stays readable"
              : status === "broken"
                ? "Your background image could not be loaded. Choose it again, or remove it."
                : status === "loading"
                  ? "Loading your background image"
                  : "Your image is behind the launcher"}
          </div>
        </div>
        <div style={{ display: "flex", gap: 6 }}>
          {customBg && <button className="btn" onClick={() => setCustomBg(null)}><I.X /> Remove</button>}
          <button className="btn accent" onClick={() => bgInputRef.current?.click()}><I.Upload /> {customBg ? "Replace" : "Choose Image"}</button>
        </div>
      </div>
      {customBg && status !== "broken" && (
        <>
          {/* Live preview: mirrors exactly how the launcher renders it, so
              what you adjust is what you get. */}
          <div className="bg-preview-frame">
            <div
              className="bg-preview-img"
              style={{
                backgroundImage: `url(${customBg})`,
                // Identical model to the live background in BreezeV2.css:
                // cover plus a transform zoom. Sizing by percentage here would
                // make the preview lie about what the real window will look like.
                backgroundSize: "cover",
                backgroundPosition: bg.position,
                opacity: bg.opacity / 100,
                filter: `blur(${bg.blur}px) brightness(${bg.brightness / 100})`,
                transform: `scale(${bg.scale / 100 + bg.blur * 0.004})`,
                transformOrigin: "center",
              }}
            />
            {/* The dim layer is the theme's own window colour, as on the window. */}
            <div className="bg-preview-scrim" style={{ background: `rgb(var(--bg-scrim, 10 12 20) / ${bg.dim / 100})` }} />
          </div>

          <div className="bg-controls">
            {BG_SLIDERS.map((s) => (
              <div className="bg-control-row" key={s.key}>
                <label htmlFor={`bg-${s.key}`}>{s.label}</label>
                <input
                  id={`bg-${s.key}`}
                  type="range"
                  min={s.min}
                  max={s.max}
                  value={s.value}
                  onChange={(e) => patchSettings({ [s.key]: Number(e.target.value) })}
                />
                <span className="bg-control-val">{s.value}{s.suffix}</span>
              </div>
            ))}

            <div className="bg-control-row" style={{ alignItems: "start" }}>
              <label>Position</label>
              <div className="bg-pos-grid">
                {BG_POSITIONS.map(([pos, glyph]) => (
                  <button
                    key={pos}
                    type="button"
                    title={pos}
                    className={`bg-pos-btn ${bg.position === pos ? "on" : ""}`}
                    onClick={() => patchSettings({ bgPosition: pos })}
                  >
                    {glyph}
                  </button>
                ))}
              </div>
              <button
                className="bg-control-val"
                style={{ background: "none", border: 0, cursor: "pointer", color: "var(--accent)" }}
                onClick={() => patchSettings({ bgDim: 35, bgBlur: 0, bgBrightness: 100, bgOpacity: 100, bgScale: 100, bgPosition: "center" })}
              >
                Reset
              </button>
            </div>
          </div>
        </>
      )}
    </>
  );
}
