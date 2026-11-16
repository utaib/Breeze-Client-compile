import { useState } from "react";

/**
 * The frame rate part of Settings (launcher 1.2.1): the performance mods, the
 * video presets, the graphics card and the game's priority. Every row here
 * changes something the game reads or the way it is started; what each does
 * is in src-tauri/src/performance.rs.
 *
 * `perf` is the state BreezeApp loads for the selected version (null in a
 * browser or while it loads); `actions` are its handlers.
 */

const NEW_VERSION_CHOICES = [
  ["auto", "Auto"],
  ["max-fps", "Max FPS"],
  ["balanced", "Balanced"],
  ["quality", "Quality"],
  ["minecraft", "Minecraft's"],
];

const PRESET_BLURB = {
  "max-fps": "Short view, fast graphics, no clouds, no frame cap",
  balanced: "Minecraft's look with cheaper clouds and particles, no frame cap",
  quality: "Long view and every detail, frames matched to the screen",
};

function presetLabel(perf, id) {
  return perf?.presets?.find((p) => p.id === id)?.label || id;
}

export default function PerformanceSettings({ settings, patchSettings, perf, actions }) {
  const {
    versionId, loaderLabel, canHaveMods, gameRunning, busy,
    onAddBoostMods, onPreviewPreset, onApplyPreset, onUndoPreset,
  } = actions;
  // The preset whose changes are listed, waiting for Apply.
  const [preview, setPreview] = useState(null);
  const newVersions = settings.videoPreset || "auto";
  const locked = gameRunning || busy;

  async function choose(presetId) {
    if (locked || !versionId) return;
    const result = await onPreviewPreset(presetId);
    if (result) setPreview(result);
  }

  async function apply() {
    if (!preview) return;
    const done = await onApplyPreset(preview.preset);
    if (done) setPreview(null);
  }

  return (
    <>
      <div className="sr">
        <div className="si">
          <div className="sn">FPS mods</div>
          <div className="sd">
            Adds Sodium and the other performance mods of the loader the first time a version is launched.
            One you remove stays removed.
          </div>
        </div>
        <div className="perf-actions">
          {canHaveMods && versionId && (
            <button className="btn" onClick={onAddBoostMods} disabled={locked} title={`Add them to ${versionId} now`}>
              {busy === "boost" ? "Adding…" : `Add to ${versionId}`}
            </button>
          )}
          <button
            type="button"
            className={`pill ${settings.boostMods !== false ? "on" : ""}`}
            aria-label="FPS mods"
            aria-pressed={settings.boostMods !== false}
            onClick={() => patchSettings({ boostMods: settings.boostMods === false })}
          />
        </div>
      </div>

      <div className="sr">
        <div className="si">
          <div className="sn">Video settings for new versions</div>
          <div className="sd">
            {newVersions === "auto"
              ? `Auto picks for this computer: ${presetLabel(perf, perf?.autoPreset || "balanced")}.`
              : newVersions === "minecraft"
                ? "New versions start with Minecraft's own video settings."
                : `New versions start with ${presetLabel(perf, newVersions)}.`}
            {" "}Versions you have already played keep theirs.
          </div>
        </div>
        <div className="seg">
          {NEW_VERSION_CHOICES.map(([id, label]) => (
            <button
              key={id}
              className={`segb ${newVersions === id ? "on" : ""}`}
              onClick={() => patchSettings({ videoPreset: id })}
            >{label}</button>
          ))}
        </div>
      </div>

      {versionId && (
        <div className="sr perf-preset">
          <div className="si">
            <div className="sn">Video preset for {versionId}{loaderLabel ? ` (${loaderLabel})` : ""}</div>
            <div className="sd">
              {gameRunning
                ? "Close Minecraft to change its video settings from here."
                : "Sets Minecraft's own video options. You see every change before it is made, and can take it back."}
            </div>
          </div>
          <div className="perf-actions">
            <div className="seg">
              {(perf?.presets || []).map((preset) => (
                <button
                  key={preset.id}
                  className={`segb ${preview?.preset === preset.id ? "on" : ""}`}
                  onClick={() => choose(preset.id)}
                  disabled={locked}
                  title={PRESET_BLURB[preset.id] || ""}
                >{preset.label}</button>
              ))}
            </div>
            {perf?.canUndo && !preview && (
              <button className="btn" onClick={onUndoPreset} disabled={locked}>
                Undo {presetLabel(perf, perf.lastPreset)}
              </button>
            )}
          </div>
        </div>
      )}

      {preview && (
        <div className="perf-preview" role="group" aria-label={`${preview.label} preset`}>
          <div className="perf-preview-head">
            <strong>{preview.label}</strong>
            <span>{PRESET_BLURB[preview.preset]}</span>
          </div>
          {preview.changes.length === 0 ? (
            <div className="perf-preview-none">{versionId} already has these settings.</div>
          ) : (
            <ul className="perf-changes">
              {preview.changes.map((change) => (
                <li key={change.key}>
                  <span>{change.label}</span>
                  <b>{change.shown}</b>
                </li>
              ))}
            </ul>
          )}
          <div className="perf-preview-foot">
            <button className="btn" onClick={() => setPreview(null)}>Cancel</button>
            {preview.changes.length > 0 && (
              <button className="btn accent" onClick={apply} disabled={locked}>
                {busy === "preset" ? "Applying…" : `Apply to ${versionId}`}
              </button>
            )}
          </div>
        </div>
      )}

      {perf?.fastGpuSupported && (
        <div className="sr">
          <div className="si">
            <div className="sn">Use the fast graphics card</div>
            <div className="sd">
              On a computer with two graphics cards, start Minecraft on the fast one. Makes no difference with one card.
            </div>
          </div>
          <button
            type="button"
            className={`pill ${settings.fastGpu ? "on" : ""}`}
            aria-label="Use the fast graphics card"
            aria-pressed={Boolean(settings.fastGpu)}
            onClick={() => patchSettings({ fastGpu: !settings.fastGpu })}
          />
        </div>
      )}

      {perf?.prioritySupported && (
        <div className="sr">
          <div className="si">
            <div className="sn">Give Minecraft priority</div>
            <div className="sd">Windows runs the game ahead of background programs while you play</div>
          </div>
          <button
            type="button"
            className={`pill ${settings.gamePriority !== false ? "on" : ""}`}
            aria-label="Give Minecraft priority"
            aria-pressed={settings.gamePriority !== false}
            onClick={() => patchSettings({ gamePriority: settings.gamePriority === false })}
          />
        </div>
      )}
    </>
  );
}
