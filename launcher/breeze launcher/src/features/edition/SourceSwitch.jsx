import { useState } from "react";
import { MOD_SOURCES } from "./editions";
import "./edition.css";

/**
 * Where Browse finds mods: Modrinth, and CurseForge once Breeze's server has
 * CurseForge's key (curseForgeReady, from /curseforge/status). Until then
 * CurseForge is marked Planned: choosing it says why and keeps Modrinth
 * (docs/EDITIONS_AND_SOURCES.md), and nothing is fetched from CurseForge.
 * The choice is not saved: a restart is on Modrinth.
 */
export function SourceSwitch({ source = "modrinth", onChange, curseForgeReady = false }) {
  const [note, setNote] = useState(false);
  const ready = (s) => s.ready || (s.id === "curseforge" && curseForgeReady);
  return (
    <div className="src-wrap">
      <div className="src-switch" role="radiogroup" aria-label="Where mods come from">
        {MOD_SOURCES.map((s) => (
          <button
            key={s.id}
            type="button"
            role="radio"
            aria-checked={source === s.id}
            className={`src-opt ${source === s.id ? "on" : ""}`}
            onClick={() => {
              if (ready(s)) {
                setNote(false);
                onChange?.(s.id);
              } else {
                setNote(true);
              }
            }}
          >
            {s.name}
            {!ready(s) && <span className="ed-soon">Planned</span>}
          </button>
        ))}
      </div>
      <p className="src-note" aria-live="polite">
        {note
          ? "CurseForge is planned for a later release. It needs CurseForge's permission first, and only mods whose authors allow other launchers will be offered. Mods still come from Modrinth."
          : source === "curseforge"
            ? "From CurseForge. Mods whose authors keep them on CurseForge open its page instead of installing."
            : ""}
      </p>
    </div>
  );
}
