import { useRef, useState } from "react";
import useModalExit from "../ui/useModalExit";
import { uploadCapeAdmin, uploadAnimatedCape } from "../services/breezeApi";
import GifToFramesTool from "./GifToFramesTool";


export default function CapeUploadModal({ session, creatorSharePercent, onClose: onCloseProp, onUploaded }) {
  // Delays the parent's unmount so the dialog can animate out. Shadowing
  // the prop means every existing onClose call site below is unchanged.
  const { closing, close: onClose } = useModalExit(onCloseProp);
  const fileInputRef  = useRef(null);
  const framesInputRef = useRef(null);

  
  const [name, setName]               = useState("");
  const [price, setPrice]             = useState(10);
  const [rarity, setRarity]           = useState("premium");
  const [description, setDescription] = useState("");
  const [isPublic, setIsPublic]       = useState(true);
  const [isAnimated, setIsAnimated]   = useState(false);

  
  const [file, setFile]       = useState(null);
  const [preview, setPreview] = useState(null);

  
  const [frames, setFrames]   = useState([]);

  const [busy, setBusy]   = useState(false);
  const [error, setError] = useState("");
  const [showGifTool, setShowGifTool] = useState(false);

  const creatorPct = creatorSharePercent != null ? Number(creatorSharePercent).toFixed(0) : "N/A";

  function handleStaticFile(e) {
    const f = e.target.files?.[0];
    if (!f) return;
    setFile(f);
    setPreview((prev) => { if (prev) URL.revokeObjectURL(prev); return URL.createObjectURL(f); });
  }

  function handleFramesAdd(e) {
    const picked = Array.from(e.target.files || []);
    e.target.value = "";
    if (picked.length === 0) return;
    
    
    picked.sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: "base" }));
    const newOnes = picked.map((f) => ({ url: URL.createObjectURL(f), file: f }));
    setFrames((cur) => [...cur, ...newOnes]);
  }

  function handleGifFrames(fileArray) {
    
    setFrames((old) => {
      old.forEach((f) => URL.revokeObjectURL(f.url));
      return fileArray.map((f) => ({ url: URL.createObjectURL(f), file: f }));
    });
    setShowGifTool(false);
  }

  function removeFrame(idx) {
    setFrames((cur) => {
      const victim = cur[idx];
      if (victim) URL.revokeObjectURL(victim.url);
      return cur.filter((_, i) => i !== idx);
    });
  }

  function moveFrame(idx, direction) {
    setFrames((cur) => {
      const target = idx + direction;
      if (target < 0 || target >= cur.length) return cur;
      const copy = [...cur];
      [copy[idx], copy[target]] = [copy[target], copy[idx]];
      return copy;
    });
  }

  function clearAllFrames() {
    setFrames((cur) => { cur.forEach((f) => URL.revokeObjectURL(f.url)); return []; });
  }

  function toggleAnimated() {
    if (busy) return;
    setIsAnimated((cur) => {
      const next = !cur;
      
      if (next) {
        setFile(null);
        setPreview((p) => { if (p) URL.revokeObjectURL(p); return null; });
      } else {
        clearAllFrames();
        setShowGifTool(false);
      }
      return next;
    });
  }

  async function handleSubmit() {
    if (!name.trim())             { setError("Cape name is required."); return; }
    if (price < 0)                { setError("Price cannot be negative."); return; }
    if (isAnimated) {
      if (frames.length < 2)      { setError("Animated capes need at least 2 frames."); return; }
      if (frames.length > 40)     { setError("Maximum 40 frames per cape."); return; }
    } else {
      if (!file)                  { setError("Cape image is required."); return; }
    }

    setBusy(true); setError("");
    try {
      let result;
      if (isAnimated) {
        result = await uploadAnimatedCape({
          token: session.breezeToken,
          frames: frames.map((f) => f.file),
          name: name.trim(),
          price_usd: Number(price),
          rarity,
          description: description.trim(),
          isPublic,
        });
      } else {
        result = await uploadCapeAdmin({
          token: session.breezeToken,
          file,
          fileName: file.name,
          name: name.trim(),
          price_usd: Number(price),
          rarity,
          description: description.trim(),
          isPublic,
        });
      }
      onUploaded?.(result);
      onClose();
    } catch (err) {
      setError(err?.message || "Upload failed");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`modal-backdrop${closing ? " closing" : ""}`} onClick={busy ? undefined : onClose}>
      <div
        className="modal cape-upload-modal"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="modal-body">
          <div className="modal-header">
            <div>
              <div style={{ fontSize: 11, color: "rgba(165,135,255,0.7)", marginBottom: 4, fontWeight: 700, letterSpacing: ".06em", textTransform: "uppercase" }}>Creator Tools</div>
              <div className="modal-title">List Cape on Marketplace</div>
            </div>
            <span className="admin-badge">Creator</span>
          </div>

          
          <div className="sr">
            <div className="si">
              <div className="sn">Advanced: manual frames</div>
              <div className="sd">Leave off to just upload a GIF, it is split into frames automatically. Turn on only to add or reorder frames by hand.</div>
            </div>
            <div className={`pill ${isAnimated ? "on" : ""}`} onClick={toggleAnimated} />
          </div>

          
          {!isAnimated && (
            <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
              <div
                className="admin-cape-preview"
                onClick={() => fileInputRef.current?.click()}
                style={{ backgroundImage: preview ? `url(${preview})` : undefined }}
              >
                {!preview && (
                  <>
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" y1="3" x2="12" y2="15" /></svg>
                    <span>Upload PNG or GIF</span>
                  </>
                )}
              </div>
              <input ref={fileInputRef} type="file" accept=".png,.gif,.apng,image/png,image/gif,image/apng" hidden onChange={handleStaticFile} />
              <CommonFields
                name={name} setName={setName}
                price={price} setPrice={setPrice}
                rarity={rarity} setRarity={setRarity}
              />
            </div>
          )}

          
          {isAnimated && (
            <>
              <div style={{ display: "flex", gap: 14, alignItems: "flex-start" }}>
                <div className="admin-cape-preview" style={{ backgroundImage: frames[0]?.url ? `url(${frames[0].url})` : undefined }}>
                  {!frames[0] && (
                    <>
                      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><rect x="3" y="3" width="18" height="18" rx="2" /><path d="M3 9h18M9 3v18" /></svg>
                      <span>{frames.length} frames</span>
                    </>
                  )}
                </div>
                <CommonFields
                  name={name} setName={setName}
                  price={price} setPrice={setPrice}
                  rarity={rarity} setRarity={setRarity}
                />
              </div>

              <div className="frames-bar">
                <button className="btn" onClick={() => framesInputRef.current?.click()} disabled={busy}>
                  + Add frames
                </button>
                <button className="btn" onClick={() => setShowGifTool((v) => !v)} disabled={busy}>
                  {showGifTool ? "Hide GIF tool" : "From GIF"}
                </button>
                {frames.length > 0 && (
                  <button className="btn" onClick={clearAllFrames} disabled={busy}>
                    Clear all
                  </button>
                )}
                <div className="frames-count">{frames.length} frame{frames.length === 1 ? "" : "s"}</div>
              </div>
              <input
                ref={framesInputRef}
                type="file"
                accept=".png,image/png"
                multiple
                hidden
                onChange={handleFramesAdd}
              />

              {showGifTool && (
                <div className="gif-tool-embed">
                  <GifToFramesTool onFrames={handleGifFrames} disabled={busy} />
                </div>
              )}

              {frames.length > 0 && (
                <div className="frames-strip">
                  {frames.map((f, i) => (
                    <div key={i} className="frame-cell">
                      <img src={f.url} alt={`Frame ${i + 1}`} />
                      <div className="frame-cell-idx">#{i + 1}</div>
                      <div className="frame-cell-controls">
                        <button className="frame-mini" onClick={() => moveFrame(i, -1)} disabled={i === 0 || busy} title="Move left">‹</button>
                        <button className="frame-mini" onClick={() => removeFrame(i)} disabled={busy} title="Remove">×</button>
                        <button className="frame-mini" onClick={() => moveFrame(i, 1)} disabled={i === frames.length - 1 || busy} title="Move right">›</button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </>
          )}

          
          <div>
            <div style={{ fontSize: 10.5, color: "var(--text-faint)", marginBottom: 4 }}>Description (optional)</div>
            <textarea
              className="settings-input"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="Describe this cape…"
              maxLength={240}
              rows={2}
              style={{ resize: "vertical", fontFamily: "inherit", fontSize: 12 }}
            />
          </div>

          
          <div style={{ padding: "9px 12px", borderRadius: "var(--radius-md)", background: "rgba(165,135,255,0.06)", border: "1px solid rgba(165,135,255,0.14)" }}>
            <div style={{ fontSize: 10.5, color: "rgba(165,135,255,0.7)", fontWeight: 600, marginBottom: 3 }}>Revenue Share (set by platform)</div>
            <div style={{ fontSize: 11.5, color: "var(--text-secondary)" }}>
              You receive <strong>{creatorPct}%</strong> of each sale. The platform retains the rest, co-owner and developer allocations are handled internally.
            </div>
          </div>

          <div className="sr" style={{ padding: "6px 0" }}>
            <div className="si">
              <div className="sn">List publicly in marketplace</div>
              <div className="sd">Visible to all users immediately after upload</div>
            </div>
            <div className={`pill ${isPublic ? "on" : ""}`} onClick={() => setIsPublic((v) => !v)} />
          </div>

          {error && <div className="promo-error">{error}</div>}

          <div className="modal-actions">
            <button className="modal-close" onClick={onClose} disabled={busy}>Cancel</button>
            <button
              className="modal-buy"
              onClick={handleSubmit}
              disabled={busy || !name.trim() || (isAnimated ? frames.length < 2 : !file)}
              style={{ flex: 1 }}
            >
              {busy ? "Uploading…" : isAnimated ? `List Animated Cape (${frames.length} frames)` : "List Cape"}
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}


function CommonFields({ name, setName, price, setPrice, rarity, setRarity }) {
  return (
    <div style={{ flex: 1, display: "flex", flexDirection: "column", gap: 9 }}>
      <div>
        <div style={{ fontSize: 10.5, color: "var(--text-faint)", marginBottom: 4 }}>Cape Name</div>
        <input
          className="settings-input"
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="e.g. Cosmic Drift"
          maxLength={48}
        />
      </div>
      <div style={{ display: "flex", gap: 9 }}>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 10.5, color: "var(--text-faint)", marginBottom: 4 }}>Price (USD)</div>
          <input
            className="settings-input"
            type="number"
            min={0}
            step={0.5}
            value={price}
            onChange={(e) => setPrice(Number(e.target.value))}
          />
        </div>
        <div style={{ flex: 1 }}>
          <div style={{ fontSize: 10.5, color: "var(--text-faint)", marginBottom: 4 }}>Rarity</div>
          <select className="vsel" value={rarity} onChange={(e) => setRarity(e.target.value)} style={{ width: "100%", height: 34 }}>
            <option value="free">Free</option>
            <option value="premium">Premium</option>
            <option value="event">Event</option>
            <option value="limited">Limited</option>
          </select>
        </div>
      </div>
    </div>
  );
}