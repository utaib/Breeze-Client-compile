import { useCallback, useEffect, useRef, useState } from "react";
import {
  adminAssignTag,
  adminClearTagIcon,
  adminListTags,
  adminSaveTag,
  adminUploadTagIcon,
} from "../services/breezeApi";
import { I } from "../ui/icons";
import { windChargeArtFor } from "../ui/windCharge";

const BLANK = { slug: "", name: "", color: "#55FFFF", priority_weight: 0, auto_role: "" };

/**
 * Admin tag management (Section 5.5).
 *
 * Three jobs in one panel: see what exists, create or edit a definition, and
 * grant or revoke one for a named user. Editing reuses the create form because
 * the backend upserts by slug, so a save with an existing slug is an edit.
 */
export default function AdminTagManager({ token, notify }) {
  const [tags, setTags] = useState([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [form, setForm] = useState(BLANK);
  const [saving, setSaving] = useState(false);
  const [assign, setAssign] = useState({ tagId: "", username: "" });
  const [assigning, setAssigning] = useState(false);
  const [iconBusyId, setIconBusyId] = useState(null);
  // One hidden input, retargeted per row, rather than one per tag.
  const iconInputRef = useRef(null);
  const iconTargetRef = useRef(null);

  const load = useCallback(async () => {
    if (!token) return;
    setLoading(true);
    setError("");
    try {
      const res = await adminListTags(token);
      setTags(res.tags || []);
    } catch (e) {
      setError(e?.message || "Could not load tags.");
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => { load(); }, [load]);

  const edit = (tag) => setForm({
    slug: tag.slug,
    name: tag.name,
    color: tag.color || "#55FFFF",
    priority_weight: tag.priority_weight ?? 0,
    auto_role: tag.auto_role || "",
  });

  const save = async () => {
    if (!form.slug.trim() || !form.name.trim()) {
      notify?.("!", "A slug and a display name are required");
      return;
    }
    setSaving(true);
    try {
      await adminSaveTag(token, {
        slug: form.slug.trim(),
        name: form.name.trim(),
        color: form.color,
        priority_weight: Number(form.priority_weight) || 0,
        // An empty select means "not tied to a role"; the column is nullable.
        auto_role: form.auto_role || null,
      });
      notify?.("ok", `Tag "${form.name}" saved`);
      setForm(BLANK);
      await load();
    } catch (e) {
      notify?.("!", e?.message || "Could not save that tag");
    } finally {
      setSaving(false);
    }
  };

  const doAssign = async (revoke) => {
    if (!assign.tagId || !assign.username.trim()) {
      notify?.("!", "Pick a tag and type a username");
      return;
    }
    setAssigning(true);
    try {
      await adminAssignTag(token, { tag_id: assign.tagId, username: assign.username.trim(), revoke });
      notify?.("ok", `${revoke ? "Revoked from" : "Granted to"} ${assign.username.trim()}`);
      setAssign((a) => ({ ...a, username: "" }));
      await load();
    } catch (e) {
      notify?.("!", e?.message || "Could not change that grant");
    } finally {
      setAssigning(false);
    }
  };

  const pickIcon = (tag) => {
    iconTargetRef.current = tag;
    iconInputRef.current?.click();
  };

  const handleIconFile = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = "";
    const tag = iconTargetRef.current;
    if (!file || !tag) return;
    setIconBusyId(tag.id);
    try {
      await adminUploadTagIcon(token, tag.id, file);
      notify?.("ok", `Icon set for ${tag.name}`);
      await load();
    } catch (e) {
      notify?.("!", e?.message || "Could not upload that icon");
    } finally {
      setIconBusyId(null);
      iconTargetRef.current = null;
    }
  };

  const clearIcon = async (tag) => {
    setIconBusyId(tag.id);
    try {
      await adminClearTagIcon(token, tag.id);
      notify?.("ok", `${tag.name} is back to the wind charge`);
      await load();
    } catch (e) {
      notify?.("!", e?.message || "Could not clear that icon");
    } finally {
      setIconBusyId(null);
    }
  };

  if (loading) return <div className="empty-panel">Loading tags…</div>;
  if (error) return <div className="friendly-empty">{error}</div>;

  return (
    <div className="admin-tags">
      <input
        ref={iconInputRef}
        type="file"
        accept="image/png,image/jpeg,image/gif"
        style={{ display: "none" }}
        onChange={handleIconFile}
      />
      <div className="admin-tag-list">
        {tags.length === 0 && <div className="empty-panel">No tags defined yet.</div>}
        {tags.map((tag) => (
          <div key={tag.id} className="admin-tag-row">
            <span className="tag-swatch" style={{ color: tag.color }}>
              {tag.icon_asset
                ? <img src={tag.icon_asset} alt="" />
                : windChargeArtFor(tag.color)
                  ? <img src={windChargeArtFor(tag.color)} alt="" />
                  : <I.WindCharge />}
            </span>
            <span className="tag-name" style={{ color: tag.color }}>[{tag.name}]</span>
            <span className="admin-tag-meta">
              {tag.slug} · weight {tag.priority_weight}
              {tag.auto_role ? ` · auto: ${tag.auto_role}` : ""}
              {` · ${tag.granted_to} granted`}
            </span>
            <button
              className="btn sm"
              disabled={iconBusyId === tag.id}
              onClick={() => pickIcon(tag)}
              title="Upload an icon for this tag"
            >
              {iconBusyId === tag.id ? "…" : "Icon"}
            </button>
            {tag.icon_asset && (
              <button
                className="btn sm"
                disabled={iconBusyId === tag.id}
                onClick={() => clearIcon(tag)}
                title="Back to the wind charge"
              >
                <I.X />
              </button>
            )}
            <button className="btn sm" onClick={() => edit(tag)}>Edit</button>
          </div>
        ))}
      </div>

      <div className="admin-tag-form">
        <div className="admin-tag-form-title">{tags.some((t) => t.slug === form.slug) ? "Edit tag" : "New tag"}</div>
        <div className="admin-tag-fields">
          <label>
            <span>Slug</span>
            <input className="field-input"
              value={form.slug}
              onChange={(e) => setForm({ ...form, slug: e.target.value })}
              placeholder="veteran"
            />
          </label>
          <label>
            <span>Display name</span>
            <input className="field-input"
              value={form.name}
              onChange={(e) => setForm({ ...form, name: e.target.value })}
              placeholder="Veteran"
            />
          </label>
          <label>
            <span>Color</span>
            <input className="field-input" type="color" value={form.color} onChange={(e) => setForm({ ...form, color: e.target.value })} />
          </label>
          <label>
            <span>Priority</span>
            <input className="field-input"
              type="number"
              value={form.priority_weight}
              onChange={(e) => setForm({ ...form, priority_weight: e.target.value })}
            />
          </label>
          <label>
            <span>Auto role</span>
            <select className="field-input" value={form.auto_role} onChange={(e) => setForm({ ...form, auto_role: e.target.value })}>
              <option value="">None</option>
              <option value="owner">Owner</option>
              <option value="developer">Developer</option>
              <option value="admin">Admin</option>
              <option value="creator">Creator</option>
              <option value="user">User</option>
            </select>
          </label>
        </div>
        <div className="admin-tag-actions">
          <button className="btn accent" onClick={save} disabled={saving}>{saving ? "Saving…" : "Save tag"}</button>
          {form.slug && <button className="btn" onClick={() => setForm(BLANK)}>Clear</button>}
        </div>
      </div>

      <div className="admin-tag-form">
        <div className="admin-tag-form-title">Grant or revoke</div>
        <div className="admin-tag-fields">
          <label>
            <span>Tag</span>
            <select className="field-input" value={assign.tagId} onChange={(e) => setAssign({ ...assign, tagId: e.target.value })}>
              <option value="">Pick a tag</option>
              {tags.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </label>
          <label>
            <span>Username or uuid</span>
            <input className="field-input"
              value={assign.username}
              onChange={(e) => setAssign({ ...assign, username: e.target.value })}
              placeholder="Notch"
            />
          </label>
        </div>
        <div className="admin-tag-actions">
          <button className="btn accent" onClick={() => doAssign(false)} disabled={assigning}>Grant</button>
          <button className="btn danger" onClick={() => doAssign(true)} disabled={assigning}>Revoke</button>
        </div>
      </div>
    </div>
  );
}
