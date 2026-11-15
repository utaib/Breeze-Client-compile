import { useCallback, useEffect, useState } from "react";
import { useConfirm } from "./ConfirmDialog";
import {
  createPromoCode,
  deactivatePromoCode,
  listPromoCodes,
  updatePromoCode,
} from "../services/promoCodes";


export default function PromoCodeManager({ token, onNotify, onLog }) {
  const confirm = useConfirm();
  const [codes, setCodes]       = useState([]);
  const [loading, setLoading]   = useState(true);
  const [loadError, setLoadErr] = useState("");

  const [newCode, setNewCode]         = useState("");
  const [newPct, setNewPct]           = useState(10);
  const [newLimit, setNewLimit]       = useState("");
  const [creating, setCreating]       = useState(false);
  const [createError, setCreateError] = useState("");

  const [editing, setEditing]         = useState(null); 
  const [editPct, setEditPct]         = useState(0);
  const [editLimit, setEditLimit]     = useState("");
  const [savingEdit, setSavingEdit]   = useState(false);

  const notify = onNotify || (() => {});
  const log    = onLog    || (() => {});

  const reload = useCallback(async () => {
    if (!token) return;
    setLoading(true); setLoadErr("");
    try {
      const list = await listPromoCodes(token);
      setCodes(list);
    } catch (err) {
      setLoadErr(err?.message || "Failed to load promo codes");
    } finally {
      setLoading(false);
    }
  }, [token]);

  useEffect(() => { void reload(); }, [reload]);

  async function handleCreate() {
    const code = newCode.trim().toUpperCase();
    const pct  = Number(newPct);
    if (!code) { setCreateError("Code is required"); return; }
    if (!/^[A-Z0-9_-]{3,20}$/.test(code)) {
      setCreateError("3-20 characters, A-Z / 0-9 / _ / - only");
      return;
    }
    if (isNaN(pct) || pct <= 0 || pct > 100) {
      setCreateError("Discount must be 1-100");
      return;
    }
    const limit = newLimit.trim() ? Number(newLimit) : null;
    if (limit !== null && (isNaN(limit) || limit < 1)) {
      setCreateError("Usage limit must be a positive number or blank");
      return;
    }
    setCreating(true); setCreateError("");
    try {
      await createPromoCode({ token, code, discount_percent: pct, usage_limit: limit });
      notify("✓", `Promo ${code} created`);
      log("success", `[Promo] Created ${code} (${pct}% off${limit !== null ? `, max ${limit} uses` : ""})`);
      setNewCode(""); setNewPct(10); setNewLimit("");
      await reload();
    } catch (err) {
      setCreateError(err?.message || "Failed to create promo code");
    } finally {
      setCreating(false);
    }
  }

  async function handleToggleActive(promo) {
    try {
      await updatePromoCode({ token, id: promo.id, is_active: !promo.is_active });
      notify("✓", `${promo.code} ${!promo.is_active ? "activated" : "deactivated"}`);
      log("info", `[Promo] ${promo.code} set is_active=${!promo.is_active}`);
      await reload();
    } catch (err) {
      notify("!", err?.message || "Failed to update promo code");
    }
  }

  async function handleDelete(promo) {
    const ok = await confirm({
      title: `Deactivate ${promo.code}?`,
      body: "Anyone still holding this code will no longer be able to redeem it. Codes already used keep their discount.",
      confirmLabel: "Deactivate",
      danger: true,
    });
    if (!ok) return;
    try {
      await deactivatePromoCode(token, promo.id);
      notify("✓", `${promo.code} deactivated`);
      log("info", `[Promo] Deactivated ${promo.code}`);
      await reload();
    } catch (err) {
      notify("!", err?.message || "Failed to deactivate promo code");
    }
  }

  function startEdit(promo) {
    setEditing(promo);
    setEditPct(promo.discount_percent);
    setEditLimit(promo.usage_limit != null ? String(promo.usage_limit) : "");
  }

  async function handleSaveEdit() {
    if (!editing) return;
    const pct = Number(editPct);
    if (isNaN(pct) || pct <= 0 || pct > 100) {
      notify("!", "Discount must be 1-100");
      return;
    }
    const limit = editLimit.trim() ? Number(editLimit) : null;
    if (limit !== null && (isNaN(limit) || limit < 1)) {
      notify("!", "Usage limit must be positive or blank");
      return;
    }
    setSavingEdit(true);
    try {
      await updatePromoCode({
        token,
        id: editing.id,
        discount_percent: pct,
        usage_limit: limit,
      });
      notify("✓", `${editing.code} updated`);
      log("success", `[Promo] Updated ${editing.code}: ${pct}%${limit !== null ? `, max ${limit}` : ""}`);
      setEditing(null);
      await reload();
    } catch (err) {
      notify("!", err?.message || "Failed to update");
    } finally {
      setSavingEdit(false);
    }
  }

  return (
    <div className="promo-manager">
      
      <div className="sg">
        <div className="sgl">Create Promo Code</div>
        <div className="scd">
          <div className="promo-create-grid">
            <div>
              <div className="promo-field-label">Code</div>
              <input
                className="settings-input"
                type="text"
                placeholder="SUMMER25"
                value={newCode}
                onChange={(e) => setNewCode(e.target.value.toUpperCase())}
                maxLength={20}
                autoCapitalize="characters"
                spellCheck={false}
                disabled={creating}
              />
            </div>
            <div>
              <div className="promo-field-label">Discount (%)</div>
              <input
                className="settings-input"
                type="number"
                min={1}
                max={100}
                step={1}
                value={newPct}
                onChange={(e) => setNewPct(Number(e.target.value))}
                disabled={creating}
              />
            </div>
            <div>
              <div className="promo-field-label">Usage Limit (optional)</div>
              <input
                className="settings-input"
                type="number"
                min={1}
                step={1}
                placeholder="Unlimited"
                value={newLimit}
                onChange={(e) => setNewLimit(e.target.value)}
                disabled={creating}
              />
            </div>
            <div style={{ alignSelf: "end" }}>
              <button className="btn accent" onClick={handleCreate} disabled={creating} style={{ width: "100%" }}>
                {creating ? "Creating…" : "Create"}
              </button>
            </div>
          </div>
          {createError && <div className="promo-error">{createError}</div>}
        </div>
      </div>

      
      <div className="sg">
        <div className="sgl">Existing Promo Codes</div>
        <div className="scd">
          {loading ? (
            <div className="promo-empty">Loading…</div>
          ) : loadError ? (
            <div className="promo-error">{loadError}</div>
          ) : codes.length === 0 ? (
            <div className="promo-empty">No promo codes yet. Create one above.</div>
          ) : (
            <div className="promo-table">
              <div className="promo-table-head">
                <div className="pt-code">Code</div>
                <div className="pt-pct">Discount</div>
                <div className="pt-uses">Uses</div>
                <div className="pt-status">Status</div>
                <div className="pt-actions">Actions</div>
              </div>
              {codes.map((promo) => (
                <div key={promo.id} className={`promo-table-row ${!promo.is_active ? "inactive" : ""}`}>
                  <div className="pt-code"><strong>{promo.code}</strong></div>
                  <div className="pt-pct">{promo.discount_percent}%</div>
                  <div className="pt-uses">
                    {promo.times_used}
                    {promo.usage_limit != null && <span className="pt-uses-limit"> / {promo.usage_limit}</span>}
                  </div>
                  <div className="pt-status">
                    <span className={`promo-status-pill ${promo.is_active ? "on" : "off"}`}>
                      {promo.is_active ? "Active" : "Inactive"}
                    </span>
                  </div>
                  <div className="pt-actions">
                    <button className="btn" onClick={() => startEdit(promo)} style={{ fontSize: 10.5 }}>Edit</button>
                    <button className="btn" onClick={() => handleToggleActive(promo)} style={{ fontSize: 10.5 }}>
                      {promo.is_active ? "Disable" : "Enable"}
                    </button>
                    {promo.is_active && (
                      <button className="btn" onClick={() => handleDelete(promo)} style={{ fontSize: 10.5, color: "rgba(255,120,120,0.9)" }}>
                        Deactivate
                      </button>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>

      
      {editing && (
        <div className="modal-backdrop" onClick={savingEdit ? undefined : () => setEditing(null)}>
          <div className="modal" style={{ maxWidth: 380 }} onClick={(e) => e.stopPropagation()}>
            <div className="modal-body">
              <div className="modal-header">
                <div className="modal-title">Edit {editing.code}</div>
              </div>
              <div>
                <div className="promo-field-label">Discount (%)</div>
                <input
                  className="settings-input"
                  type="number"
                  min={1}
                  max={100}
                  step={1}
                  value={editPct}
                  onChange={(e) => setEditPct(Number(e.target.value))}
                />
              </div>
              <div>
                <div className="promo-field-label">Usage Limit</div>
                <input
                  className="settings-input"
                  type="number"
                  min={1}
                  step={1}
                  placeholder="Unlimited"
                  value={editLimit}
                  onChange={(e) => setEditLimit(e.target.value)}
                />
                <div className="promo-hint">Leave blank for unlimited. Already-used count: {editing.times_used}</div>
              </div>
              <div className="modal-actions">
                <button className="modal-close" onClick={() => setEditing(null)} disabled={savingEdit}>Cancel</button>
                <button className="modal-buy" onClick={handleSaveEdit} disabled={savingEdit} style={{ flex: 1 }}>
                  {savingEdit ? "Saving…" : "Save"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}