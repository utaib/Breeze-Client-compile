import { useRef, useState } from "react";


const CAPE_W = 64;
const CAPE_H = 32;
const MAX_FRAMES = 20;
const DEFAULT_FRAMES = 12;

export default function GifToFramesTool({ onFrames, disabled }) {
  const fileInputRef = useRef(null);
  const [sourceName, setSourceName] = useState("");
  const [preview, setPreview]   = useState(null);
  const [busy, setBusy]         = useState(false);
  const [error, setError]       = useState("");
  const [frameCount, setFrameCount] = useState(DEFAULT_FRAMES);
  const [frames, setFrames]     = useState([]); 

  async function handleFile(e) {
    const file = e.target.files?.[0];
    e.target.value = "";
    if (!file) return;
    if (!file.type.includes("gif") && !file.name.toLowerCase().endsWith(".gif")) {
      setError("Please select a .gif file");
      return;
    }
    setBusy(true); setError("");
    setSourceName(file.name);
    
    setPreview((prev) => { if (prev) URL.revokeObjectURL(prev); return URL.createObjectURL(file); });

    try {
      const extracted = await extractFrames(file, frameCount);
      
      setFrames((old) => { old.forEach((f) => URL.revokeObjectURL(f.url)); return extracted; });
    } catch (err) {
      setError(err?.message || "Failed to decode GIF");
    } finally {
      setBusy(false);
    }
  }

  async function handleRedo() {
    if (!fileInputRef.current?.files?.[0] && !sourceName) return;
    const fallback = fileInputRef.current?.files?.[0];
    if (!fallback) { setError("Reselect the GIF first"); return; }
    setBusy(true); setError("");
    try {
      const extracted = await extractFrames(fallback, frameCount);
      setFrames((old) => { old.forEach((f) => URL.revokeObjectURL(f.url)); return extracted; });
    } catch (err) {
      setError(err?.message || "Failed to decode GIF");
    } finally {
      setBusy(false);
    }
  }

  function handleUse() {
    if (frames.length === 0) return;
    onFrames?.(frames.map((f) => f.file));
  }

  function handleClear() {
    setFrames((old) => { old.forEach((f) => URL.revokeObjectURL(f.url)); return []; });
    setPreview((prev) => { if (prev) URL.revokeObjectURL(prev); return null; });
    setSourceName("");
    setError("");
  }

  return (
    <div className="gif-tool">
      <div className="gif-tool-header">
        <div className="gif-tool-title">GIF → Frames</div>
        <div className="gif-tool-sub">Convert an animated GIF into cape frames</div>
      </div>

      <div className="gif-tool-body">
        {!sourceName ? (
          <div
            className="gif-drop"
            onClick={() => !disabled && fileInputRef.current?.click()}
          >
            <div className="gif-drop-icon">
              <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><rect x="2" y="4" width="20" height="16" rx="2" /><path d="M7 4v16M17 4v16M2 9h5M2 15h5M17 9h5M17 15h5" /></svg>
            </div>
            <div className="gif-drop-text">Click to select a GIF</div>
            <div className="gif-drop-hint">Up to {MAX_FRAMES} frames will be extracted</div>
          </div>
        ) : (
          <>
            <div className="gif-preview-row">
              <div className="gif-preview">
                {preview && <img src={preview} alt="GIF preview" />}
              </div>
              <div className="gif-preview-meta">
                <div className="gif-preview-name">{sourceName}</div>
                <div className="gif-frame-count-row">
                  <span className="promo-field-label">Frames to extract</span>
                  <input
                    className="settings-input"
                    type="number"
                    min={2}
                    max={MAX_FRAMES}
                    step={1}
                    value={frameCount}
                    onChange={(e) => setFrameCount(Math.max(2, Math.min(MAX_FRAMES, Number(e.target.value) || DEFAULT_FRAMES)))}
                    disabled={busy || disabled}
                    style={{ width: 70 }}
                  />
                  <button className="btn" onClick={handleRedo} disabled={busy || disabled}>
                    {busy ? "Decoding…" : "Re-decode"}
                  </button>
                </div>
              </div>
            </div>

            {frames.length > 0 && (
              <>
                <div className="gif-frames-grid">
                  {frames.map((f, i) => (
                    <div key={i} className="gif-frame-thumb" title={`Frame ${i + 1}`}>
                      <img src={f.url} alt={`Frame ${i + 1}`} />
                      <div className="gif-frame-idx">{i + 1}</div>
                    </div>
                  ))}
                </div>
                <div className="gif-tool-actions">
                  <button className="btn" onClick={handleClear} disabled={busy || disabled}>Clear</button>
                  <button className="btn accent" onClick={handleUse} disabled={busy || disabled} style={{ flex: 1 }}>
                    Use these {frames.length} frames
                  </button>
                </div>
              </>
            )}
          </>
        )}

        <input
          ref={fileInputRef}
          type="file"
          accept="image/gif,.gif"
          hidden
          onChange={handleFile}
        />
        {error && <div className="promo-error">{error}</div>}
      </div>
    </div>
  );
}


async function extractFrames(file, count) {
  const safeCount = Math.max(2, Math.min(MAX_FRAMES, count || DEFAULT_FRAMES));
  const objectUrl = URL.createObjectURL(file);
  try {
    const img = await loadImage(objectUrl);

    const canvas = document.createElement("canvas");
    canvas.width  = CAPE_W;
    canvas.height = CAPE_H;
    const ctx = canvas.getContext("2d");
    if (!ctx) throw new Error("Could not create canvas context");

    
    const sampleInterval = 100;
    const frames = [];
    for (let i = 0; i < safeCount; i++) {
      
      await sleep(sampleInterval);
      ctx.clearRect(0, 0, CAPE_W, CAPE_H);
      ctx.imageSmoothingEnabled = true;
      ctx.imageSmoothingQuality = "high";
      ctx.drawImage(img, 0, 0, CAPE_W, CAPE_H);
      const blob = await canvasToBlob(canvas);
      const frameFile = new File([blob], `frame_${String(i).padStart(3, "0")}.png`, { type: "image/png" });
      frames.push({ url: URL.createObjectURL(blob), file: frameFile });
    }
    return frames;
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.crossOrigin = "anonymous";
    img.onload  = () => resolve(img);
    img.onerror = () => reject(new Error("Failed to load GIF"));
    img.src = src;
  });
}

function canvasToBlob(canvas) {
  return new Promise((resolve, reject) => {
    canvas.toBlob((b) => { b ? resolve(b) : reject(new Error("Canvas toBlob failed")); }, "image/png");
  });
}

function sleep(ms) { return new Promise((r) => setTimeout(r, ms)); }