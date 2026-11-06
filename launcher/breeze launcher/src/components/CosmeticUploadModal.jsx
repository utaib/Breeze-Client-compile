import { useEffect, useRef, useState } from "react";
import useModalExit from "../ui/useModalExit";
import {
  uploadCosmetic, inspectCosmetic, getCosmeticSpec, splitCosmeticFiles,
  COSMETIC_SLOTS, isCreatorTier,
} from "../services/breezeApi";
import { PlayerViewer3D } from "./SkinViewer3D";
import PlacementControls from "./PlacementControls";

/** How each attachment reads to a creator. */
const ATTACHMENT_LABELS = {
  HEAD: "On the head",
  SHOULDER: "On the shoulder",
  BACK: "On the back",
  HAND: "In the hand",
  FEET: "At the feet",
  SIDE: "Walking beside",
  FLYING_PET: "Flying around",
  TRAIL: "Trail behind",
};

const ROLE_LABELS = {
  idle: "Idle",
  fly: "Flying",
  walk: "Walking",
  sit: "Sitting",
};

/** Used when the spec cannot be fetched: the API still checks on upload. */
const FALLBACK_ROLES = ["idle", "fly", "walk", "sit"];

export default function CosmeticUploadModal({ session, onClose: onCloseProp, onUploaded }) {
  // Delays the parent's unmount so the dialog can animate out. Shadowing
  // the prop means every existing onClose call site below is unchanged.
  const { closing, close: onClose } = useModalExit(onCloseProp);
  const modelInputRef = useRef(null);
  const thumbInputRef = useRef(null);

  const [name, setName]                 = useState("");
  const [slot, setSlot]                 = useState("hat");
  const [description, setDescription]   = useState("");
  const [rarity, setRarity]             = useState("premium");
  const [price, setPrice]               = useState(0);
  const [isPublic, setIsPublic]         = useState(true);
  const [animChance, setAnimChance]     = useState(0.15);

  const [modelFile, setModelFile]         = useState(null);
  const [resources, setResources]         = useState([]);
  const [thumbFile, setThumbFile]         = useState(null);
  const [thumbPreview, setThumbPreview]   = useState(null);

  /** The format as the API enforces it: allowed attachments per slot, roles. */
  const [spec, setSpec] = useState(null);
  /** The server's reading of the picked files: { metadata, url } once checked. */
  const [check, setCheck] = useState({ status: "empty" });
  const [attachment, setAttachment] = useState("");
  const [roles, setRoles] = useState({});
  /** The creator's placement: every player's default for this cosmetic. */
  const [transform, setTransform] = useState(null);

  const [busy, setBusy]   = useState(false);
  const [error, setError] = useState("");

  /**
   * The latest model check. A newer pick or a slot change starts a new one,
   * and anything an older one returns is dropped, so the preview and roles
   * always belong to the files that will be uploaded.
   */
  const checkIdRef = useRef(0);
  const mountedRef = useRef(true);
  useEffect(() => () => { mountedRef.current = false; }, []);

  useEffect(() => {
    let live = true;
    getCosmeticSpec().then((s) => { if (live) setSpec(s); }).catch(() => {});
    return () => { live = false; };
  }, []);

  // The preview URL belongs to this modal; release it when it changes or closes.
  useEffect(() => () => { if (check.url) URL.revokeObjectURL(check.url); }, [check.url]);

  const allowedAttachments = spec?.slotAttachments?.[slot] || [];
  useEffect(() => {
    // A slot change can leave the chosen attachment invalid; fall back to the
    // slot's default rather than letting the upload be refused later.
    if (allowedAttachments.length && !allowedAttachments.includes(attachment)) setAttachment(allowedAttachments[0]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slot, spec]);

  async function runCheck(model, extra, forSlot = slot) {
    const id = ++checkIdRef.current;
    const current = () => mountedRef.current && id === checkIdRef.current;
    setCheck({ status: "checking" });
    try {
      const result = await inspectCosmetic({ token: session.breezeToken, slot: forSlot, model, resources: extra });
      if (!current()) return;
      // A package's own cosmetic.json can choose the attachment; keep it.
      const allowed = spec?.slotAttachments?.[forSlot] || [];
      if (!allowed.length || allowed.includes(result.metadata.attachment)) setAttachment(result.metadata.attachment);
      setRoles(result.metadata.animations.roles || {});
      setTransform(result.metadata.transform || null);
      setCheck({ status: "ready", metadata: result.metadata, url: URL.createObjectURL(result.model) });
    } catch (err) {
      if (!current()) return;
      setCheck({ status: "error", message: err?.message || "That model could not be read." });
    }
  }

  // The server checks the attachment against the slot, so a slot change
  // re-checks the picked model; a stale error from the old slot never stays.
  useEffect(() => {
    if (modelFile) runCheck(modelFile, resources, slot);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [slot]);

  function handleModel(e) {
    const picked = Array.from(e.target.files || []);
    e.target.value = "";
    if (!picked.length) return;
    const split = splitCosmeticFiles(picked);
    if (split.error) { setError(split.error); return; }
    // Say so here rather than let the proxy refuse it with a bare 413.
    const total = [split.model, ...split.resources].reduce((n, f) => n + f.size, 0);
    const limit = spec?.limits?.requestBytes;
    if (limit && total > limit * 0.95) {
      setError(`That is ${(total / 1024 / 1024).toFixed(1)} MB; an upload can be at most ${Math.floor(limit / 1024 / 1024)} MB. Compress the textures or pack the files in a .zip.`);
      return;
    }
    setError("");
    setModelFile(split.model);
    setResources(split.resources);
    runCheck(split.model, split.resources);
  }

  function handleThumb(e) {
    const f = e.target.files?.[0];
    if (!f) return;
    setThumbFile(f);
    setThumbPreview((prev) => {
      if (prev) URL.revokeObjectURL(prev);
      return URL.createObjectURL(f);
    });
  }

  async function handleSubmit() {
    if (!name.trim())       { setError("Cosmetic name is required."); return; }
    if (!modelFile)         { setError("Pick a model first."); return; }
    if (check.status !== "ready") { setError("Wait for the model check to finish, or fix what it found."); return; }
    if (price < 0)          { setError("Price cannot be negative."); return; }
    // Only asked for, and only checked, when the model has extra clips.
    const hasExtras = check.status === "ready"
      && check.metadata.animations.clips.some((c) => !Object.values(roles).includes(c.name));
    const chance = Number(animChance);
    if (hasExtras && (isNaN(chance) || chance < 0 || chance > 1)) {
      setError("How often the extra animations play must be between 0 and 1.");
      return;
    }

    setBusy(true); setError("");
    try {
      const clips = check.metadata.animations.clips;
      const chosenRoles = {};
      // Every role is sent, set or "not used", so what was shown here is what
      // is stored; the API only fills in roles it was not told about.
      for (const role of spec?.roles || FALLBACK_ROLES) {
        if (clips.length) chosenRoles[role] = roles[role] || "";
      }
      const created = await uploadCosmetic({
        token: session.breezeToken,
        name: name.trim(),
        slot,
        model: modelFile,
        modelFileName: modelFile.name,
        resources,
        attachment: attachment || undefined,
        transform: transform || undefined,
        animationRoles: clips.length ? chosenRoles : undefined,
        thumbnail: thumbFile ?? null,
        description: description.trim() || undefined,
        rarity,
        price_usd: Number(price),
        isPublic,
        animationChance: hasExtras ? chance : undefined,
      });
      onUploaded?.(created);
      onClose();
    } catch (err) {
      setError(err?.message || "Upload failed");
    } finally {
      setBusy(false);
    }
  }

  const meta = check.metadata;
  const clips = meta?.animations?.clips || [];
  const extras = clips.map((c) => c.name).filter((n) => !Object.values(roles).includes(n));
  const fileLabel = modelFile
    ? `${modelFile.name}${resources.length ? ` + ${resources.length} file${resources.length === 1 ? "" : "s"}` : ""}`
    : "Select model";

  return (
    <div className={`modal-backdrop${closing ? " closing" : ""}`} onClick={busy ? undefined : onClose}>
      <div className="modal cape-upload-modal" onClick={(e) => e.stopPropagation()}>
        <div className="modal-body">
          {(() => {
            const role = (session?.role ?? "user").toLowerCase();
            const roleLabel =
              role === "owner"   ? "Owner" :
              role === "admin"   ? "Admin" :
              role === "developer" ? "Developer" :
              role === "creator" ? "Creator" : "User";
            const tagline =
              isCreatorTier(role)
                ? "Cosmetics · Creator Tools"
                : "Cosmetics · Admin Tools";
            return (
              <div className="modal-header">
                <div>
                  <div style={{ fontSize: 11, color: "rgba(165,135,255,0.7)", marginBottom: 4, fontWeight: 700, letterSpacing: ".06em", textTransform: "uppercase" }}>
                    {tagline}
                  </div>
                  <div className="modal-title">Upload Cosmetic</div>
                  <div style={{ fontSize: 11, color: "rgba(255,255,255,0.55)", marginTop: 4 }}>
                    Capes · Hats · Pets · Wings · Auras · Shields · Back · Trail
                  </div>
                </div>
                <span className="admin-badge">{roleLabel}</span>
              </div>
            );
          })()}

          <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
            <div
              className="admin-cape-preview"
              onClick={() => thumbInputRef.current?.click()}
              style={{
                backgroundImage: thumbPreview ? `url(${thumbPreview})` : undefined,
                cursor: "pointer",
              }}
              title="Click to pick a thumbnail"
            >
              {!thumbPreview && (
                <>
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" /><circle cx="8.5" cy="8.5" r="1.5" /><path d="M21 15l-5-5L5 21" /></svg>
                  <span>Thumbnail</span>
                </>
              )}
            </div>
            <input
              ref={thumbInputRef}
              type="file"
              accept=".png,.jpg,.jpeg,image/png,image/jpeg"
              hidden
              onChange={handleThumb}
            />

            <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 8 }}>
              <label className="field">
                <span className="field-label">Name</span>
                <input
                  className="field-input"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. Phoenix Wings"
                  disabled={busy}
                />
              </label>

              <label className="field">
                <span className="field-label">Slot</span>
                <select
                  className="field-input"
                  value={slot}
                  onChange={(e) => setSlot(e.target.value)}
                  disabled={busy}
                >
                  {COSMETIC_SLOTS.map((s) => (
                    <option key={s} value={s}>
                      {s.charAt(0).toUpperCase() + s.slice(1)}
                    </option>
                  ))}
                </select>
              </label>

              <label className="field">
                <span className="field-label">Price (USD)</span>
                <input
                  className="field-input"
                  type="number"
                  min={0}
                  step="0.01"
                  value={price}
                  onChange={(e) => setPrice(e.target.value)}
                  disabled={busy}
                />
              </label>

              <label className="field">
                <span className="field-label">Rarity</span>
                <select
                  className="field-input"
                  value={rarity}
                  onChange={(e) => setRarity(e.target.value)}
                  disabled={busy}
                >
                  <option value="free">Free</option>
                  <option value="premium">Premium</option>
                  <option value="event">Event</option>
                  <option value="limited">Limited</option>
                </select>
              </label>
            </div>
          </div>

          <div className="frames-bar" style={{ marginTop: 12 }}>
            <button
              className="btn"
              onClick={() => modelInputRef.current?.click()}
              disabled={busy}
            >
              {fileLabel}
            </button>
            {modelFile && (
              <div className="frames-count">
                {((modelFile.size + resources.reduce((n, f) => n + f.size, 0)) / 1024 / 1024).toFixed(2)} MB
              </div>
            )}
          </div>
          <div className="cosmetic-file-hint">
            A .glb, a .zip of a .gltf and its files, or the .gltf with its .bin and textures picked together.
          </div>
          <input
            ref={modelInputRef}
            type="file"
            multiple
            accept=".glb,.gltf,.zip,.bin,.png,.jpg,.jpeg,.webp,model/gltf-binary,model/gltf+json,application/zip"
            hidden
            onChange={handleModel}
          />

          {check.status === "checking" && <div className="cosmetic-inspect-note">Checking the model…</div>}
          {check.status === "error" && <div className="cosmetic-inspect-note bad">{check.message}</div>}
          {check.status === "ready" && meta && (
            <div className="cosmetic-inspect">
              {/* The file exactly as it will be stored, worn on the creator's
                  own skin where it attaches, so this is what players will see. */}
              <div className="cosmetic-inspect-preview">
                <PlayerViewer3D
                  skinUrl={session?.uuid}
                  width={170}
                  height={270}
                  showElytraToggle={false}
                  cosmetics={[{
                    key: "upload",
                    url: check.url,
                    cosmetic: { slot, metadata: { ...meta, animations: { ...meta.animations, roles } }, animation_chance: Number(animChance) },
                    attachment: attachment || meta.attachment,
                    transform,
                  }]}
                  onCosmeticError={() => setCheck({ status: "error", message: "The model could not be shown." })}
                />
              </div>
              <div className="cosmetic-inspect-body">
                <div className="cosmetic-inspect-stats">
                  {meta.stats.triangles} triangles
                  {" · "}{meta.stats.textures.length === 0 ? "no textures" : meta.stats.textures.map((t) => `${t.width}×${t.height}`).join(", ")}
                  {" · "}{clips.length} animation{clips.length === 1 ? "" : "s"}
                </div>

                {allowedAttachments.length > 1 ? (
                  <label className="field">
                    <span className="field-label">Where it goes</span>
                    <select className="field-input" value={attachment} onChange={(e) => setAttachment(e.target.value)} disabled={busy}>
                      {allowedAttachments.map((a) => <option key={a} value={a}>{ATTACHMENT_LABELS[a] || a}</option>)}
                    </select>
                  </label>
                ) : allowedAttachments.length === 1 ? (
                  <div className="cosmetic-inspect-stats">{ATTACHMENT_LABELS[allowedAttachments[0]]}</div>
                ) : null}

                <details className="cosmetic-placement" open>
                  <summary>Placement</summary>
                  <PlacementControls value={transform} onChange={setTransform} disabled={busy} idPrefix="upload-placement" />
                  <button className="btn" type="button" onClick={() => setTransform(meta.transform || null)} disabled={busy}>Reset</button>
                </details>

                {clips.length === 0 ? (
                  <div className="cosmetic-inspect-stats">No animations, so it will be shown still.</div>
                ) : (
                  <div className="cosmetic-roles">
                    {(spec?.roles || FALLBACK_ROLES).map((role) => (
                      <label key={role} className="field">
                        <span className="field-label">{ROLE_LABELS[role] || role}</span>
                        <select
                          className="field-input"
                          value={roles[role] || ""}
                          onChange={(e) => setRoles((cur) => ({ ...cur, [role]: e.target.value }))}
                          disabled={busy}
                        >
                          <option value="">Not used</option>
                          {clips.map((c) => <option key={c.name} value={c.name}>{c.name}</option>)}
                        </select>
                      </label>
                    ))}
                  </div>
                )}
                {extras.length > 0 && (
                  <>
                    <div className="cosmetic-inspect-stats">Also plays now and then: {extras.join(", ")}</div>
                    <label className="field">
                      <span className="field-label">How often (0 to 1)</span>
                      <input
                        className="field-input"
                        type="number"
                        min={0}
                        max={1}
                        step="0.01"
                        value={animChance}
                        onChange={(e) => setAnimChance(e.target.value)}
                        disabled={busy}
                      />
                    </label>
                  </>
                )}
              </div>
            </div>
          )}

          <label className="field" style={{ marginTop: 8 }}>
            <span className="field-label">Description</span>
            <textarea
              className="field-input"
              rows={3}
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Shown on the store page."
              disabled={busy}
            />
          </label>

          <div className="sr" style={{ marginTop: 8 }}>
            <div className="si">
              <div className="sn">List publicly</div>
              <div className="sd">Unchecked keeps the cosmetic hidden until you enable it.</div>
            </div>
            <div
              className={`pill ${isPublic ? "on" : ""}`}
              onClick={() => !busy && setIsPublic((v) => !v)}
            />
          </div>

          {error && (
            <div style={{
              marginTop: 10, padding: "8px 12px",
              background: "rgba(255,80,80,0.1)", border: "1px solid rgba(255,80,80,0.3)",
              borderRadius: 8, color: "#ff9a9a", fontSize: 13,
            }}>{error}</div>
          )}

          <div className="modal-actions" style={{ marginTop: 14 }}>
            <button className="btn" onClick={onClose} disabled={busy}>Cancel</button>
            <button className="btn btn-primary" onClick={handleSubmit} disabled={busy || check.status !== "ready"}>
              {busy ? "Uploading…" : "Upload Cosmetic"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
