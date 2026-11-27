import { useCallback, useEffect, useState } from "react";
import { equipTag, getMyTags, setTagColor } from "../services/breezeApi";
import { I } from "../ui/icons";
import { windChargeArtFor } from "../ui/windCharge";

/**
 * Wardrobe tag picker (Section 5.4).
 *
 * A user who qualifies for several tags picks exactly one to display. The choice
 * always beats the priority-weight default, and clearing it returns to that
 * default rather than leaving the user with no tag.
 *
 * Tags a user qualifies for through their role are labelled as such, because
 * they cannot be revoked here and it would otherwise look like a bug that they
 * always reappear.
 */
export default function TagSelector({ token, notify }) {
  const [state, setState] = useState(null);
  const [loading, setLoading] = useState(true);
  const [busyId, setBusyId] = useState(null);
  const [error, setError] = useState("");

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setError("");
    try {
      setState(await getMyTags(token));
    } catch (e) {
      // A database that has not run schema.sql yet has no tags table. Say so
      // plainly instead of rendering an empty panel that looks broken.
      setError(e?.message || "Tags are not available right now.");
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => { load(); }, [load]);

  const choose = async (tagId) => {
    if (!token || busyId) return;
    const next = tagId === state?.equippedTagId ? null : tagId;
    setBusyId(tagId);
    // Optimistic: the list is small and the write is a single row, so a failure
    // simply reloads rather than needing a rollback path.
    setState((cur) => (cur ? { ...cur, equippedTagId: next, usingDefault: next === null } : cur));
    try {
      await equipTag(token, next);
      notify?.("ok", next ? "Tag equipped" : "Using your highest tag");
      await load();
    } catch (e) {
      notify?.("!", e?.message || "Could not change your tag");
      await load();
    } finally {
      setBusyId(null);
    }
  };

  if (loading) return <div className="empty-panel">Loading tags…</div>;
  if (error) return <div className="friendly-empty">{error}</div>;

  const tags = state?.tags || [];
  if (!tags.length) {
    return <div className="empty-panel">You don't have any tags yet.</div>;
  }

  return (
    <div className="tag-selector">
      {state.canChooseColor && (
        <TagColorPicker
          token={token}
          notify={notify}
          options={(state.colorOptions || []).filter((option) => option.color)}
          current={state.tagColor || null}
          onSaved={load}
        />
      )}
      <div className="tag-selector-hint">
        {state.usingDefault
          ? "Showing your highest tag automatically. Pick one to lock it in."
          : "Click your equipped tag again to go back to automatic."}
      </div>
      <div className="tag-grid">
        {tags.map((tag) => {
          const equipped = tag.id === state.equippedTagId;
          return (
            <button
              key={tag.id}
              className={`tag-chip ${equipped ? "on" : ""} ${busyId === tag.id ? "busy" : ""}`}
              onClick={() => choose(tag.id)}
              disabled={Boolean(busyId)}
              title={
                tag.source === "role"
                  ? "You have this because of your role"
                  : tag.source === "default"
                    ? "Everyone has this one"
                    : "Granted to you"
              }
            >
              <TagMark color={tag.color} icon={tag.icon} />
              <span className="tag-name" style={{ color: tag.color }}>[{tag.name}]</span>
              {tag.source === "role" && <span className="tag-source">role</span>}
              {equipped && <span className="tag-check"><I.Check /></span>}
            </button>
          );
        })}
      </div>
    </div>
  );
}

/**
 * A tag's mark. An uploaded icon wins. Otherwise the real wind charge art when
 * the colour matches a shipped variant, and the tintable glyph for anything
 * else (Creator yellow, for one), so every tag has a mark without an admin
 * having to upload one.
 */
function TagMark({ color, icon }) {
  const art = windChargeArtFor(color);
  return (
    <span className="tag-swatch" style={{ color }}>
      {icon ? <img src={icon} alt="" /> : art ? <img src={art} alt="" /> : <I.WindCharge />}
    </span>
  );
}

/**
 * The colour of a creator's tag.
 *
 * Tags are role-based, so this is the one thing about a tag anyone configures,
 * and only creators see it. The first option is the role's own yellow; the rest
 * are wind charge colours. The server holds the list and refuses anything else,
 * which is why red and purple, the Owner and Developer colours, never appear.
 */
function TagColorPicker({ token, notify, options, current, onSaved }) {
  const [savingId, setSavingId] = useState(null);
  if (!options.length) return null;

  const valueOf = (option) => (option.id === "role" ? null : option.color);
  const selected = options.find((option) => valueOf(option) === current) || options[0];

  const pick = async (option) => {
    if (!token || savingId || option.id === selected.id) return;
    setSavingId(option.id);
    try {
      await setTagColor(token, valueOf(option));
      notify?.("ok", "Tag colour updated");
      await onSaved?.();
    } catch (e) {
      notify?.("!", e?.message || "Could not change your tag colour");
    } finally {
      setSavingId(null);
    }
  };

  return (
    <div className="tag-color-picker">
      <div className="tag-color-head">
        <span className="tag-color-title">Creator tag colour</span>
        <span className="tag-color-preview" style={{ color: selected.color }}>[Creator]</span>
      </div>
      <div className="tag-color-options" role="radiogroup" aria-label="Creator tag colour">
        {options.map((option) => (
          <button
            key={option.id}
            role="radio"
            aria-checked={option.id === selected.id}
            className={`tag-chip ${option.id === selected.id ? "on" : ""} ${savingId === option.id ? "busy" : ""}`}
            onClick={() => pick(option)}
            disabled={Boolean(savingId)}
          >
            <TagMark color={option.color} />
            <span className="tag-name">{option.name}</span>
            {option.id === selected.id && <span className="tag-check"><I.Check /></span>}
          </button>
        ))}
      </div>
    </div>
  );
}
