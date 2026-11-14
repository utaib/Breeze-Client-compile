import { LOADERS, loaderName } from "./loaders";

/**
 * The choice of what runs the selected version. `status` is what the native
 * side answered for this version (get_loader_status): the build each loader
 * has, or that it has none. Until it has answered, every loader is offered;
 * one that turns out to have no build is then marked and cannot be chosen.
 */
export default function LoaderPicker({ value, onChange, status, version, style, disabled = false }) {
  const byId = new Map((status || []).map((s) => [s.id, s]));
  const label = (loader) => {
    const s = byId.get(loader.id);
    if (!s || loader.id === "vanilla") return loader.name;
    if (s.build) return `${loader.name} ${s.build}`;
    return s.problem ? `${loader.name} (could not be checked)` : `${loader.name} (none for ${version})`;
  };
  const unavailable = (loader) => {
    const s = byId.get(loader.id);
    return Boolean(s) && loader.id !== "vanilla" && !s.build;
  };
  return (
    <select
      className="vsel"
      aria-label="Mod loader"
      title={`What runs Minecraft ${version || ""}: ${loaderName(value)}`}
      style={style}
      value={value}
      disabled={disabled}
      onChange={(event) => onChange?.(event.target.value)}
    >
      {LOADERS.map((loader) => (
        <option key={loader.id} value={loader.id} disabled={unavailable(loader) && loader.id !== value}>
          {label(loader)}
        </option>
      ))}
    </select>
  );
}
