import { cleanTransform } from "../cosmetics/rig";

/**
 * Where a cosmetic sits, as sliders: offset in Minecraft pixels, rotation in
 * degrees, and size. The preview moves as they do; saving is the caller's.
 * The ranges are a comfortable subset of what the API accepts (±32 px, ±360°,
 * 0.1 to 4x), so nothing set here is clamped on the way in.
 */
const SLIDERS = [
  { label: "Sideways", key: "offset", index: 0, min: -16, max: 16, step: 0.5, unit: " px" },
  { label: "Height", key: "offset", index: 1, min: -16, max: 16, step: 0.5, unit: " px" },
  { label: "Forward", key: "offset", index: 2, min: -16, max: 16, step: 0.5, unit: " px" },
  { label: "Turn", key: "rotation", index: 1, min: -180, max: 180, step: 5, unit: "°" },
  { label: "Tilt", key: "rotation", index: 0, min: -180, max: 180, step: 5, unit: "°" },
  { label: "Roll", key: "rotation", index: 2, min: -180, max: 180, step: 5, unit: "°" },
  { label: "Size", key: "scale", min: 0.25, max: 3, step: 0.05, unit: "×" },
];

export default function PlacementControls({ value, onChange, disabled = false, idPrefix = "placement" }) {
  const t = cleanTransform(value);

  const set = (slider, raw) => {
    const n = Number(raw);
    const next = { offset: [...t.offset], rotation: [...t.rotation], scale: t.scale };
    if (slider.key === "scale") next.scale = n;
    else next[slider.key][slider.index] = n;
    onChange?.(next);
  };

  return (
    <div className="placement-controls">
      {SLIDERS.map((s) => {
        const current = s.key === "scale" ? t.scale : t[s.key][s.index];
        const id = `${idPrefix}-${s.label.toLowerCase()}`;
        return (
          <div className="bg-control-row" key={s.label}>
            <label htmlFor={id}>{s.label}</label>
            <input
              id={id}
              type="range"
              min={s.min}
              max={s.max}
              step={s.step}
              value={current}
              disabled={disabled}
              onChange={(e) => set(s, e.target.value)}
            />
            <span className="bg-control-val">
              {s.key === "scale" ? current.toFixed(2) : Math.round(current * 10) / 10}{s.unit}
            </span>
          </div>
        );
      })}
    </div>
  );
}
