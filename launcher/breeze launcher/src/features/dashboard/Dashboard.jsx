import { useEffect, useMemo, useRef, useState } from "react";
import { I } from "../../ui/icons";
import { WIDGETS, ROW_PX, GAP_PX } from "./widgets";
import {
  COLS, loadLayout, saveLayout, defaultLayout, moveItem, resizeItem, removeItem, addItem,
  setItemStyle, rowsUsed,
} from "./layout";

/**
 * The play page as a dashboard the player arranges (1.1.0).
 *
 * Widgets sit on a 12-column grid. "Customize" opens edit mode: drag a
 * widget by its bar to move it, drag the corner to resize it, the cross
 * removes it (not Play), "Add widget" lists what is not shown, each widget's
 * paint icon sets its own panel opacity and accent. Arrow keys move the
 * selected widget and Shift with the arrows resizes it. Undo steps back,
 * Reset brings the default, Cancel drops the edit, Done saves.
 *
 * The layout is kept on this computer only (localStorage, validated on
 * every read by layout.js), never sent to the API. A damaged layout gives
 * the default back and says so; it can never stop the launcher from
 * starting, because the page renders from a validated copy.
 */

const ACCENTS = ["#78b2ff", "#62d698", "#ff925c", "#ac8eff", "#ff7abc", "#54deca", "#ffd166"];
const NARROW_PX = 880;

function storage() {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

/** The CSS variables that give one widget its own accent and panel opacity. */
function itemStyleVars(style) {
  if (!style) return {};
  const vars = {};
  if (style.accent) {
    const n = parseInt(style.accent.slice(1), 16);
    const rgb = `${(n >> 16) & 255},${(n >> 8) & 255},${n & 255}`;
    Object.assign(vars, {
      "--accent": `rgba(${rgb},0.95)`,
      "--accent-dim": `rgba(${rgb},0.32)`,
      "--accent-ghost": `rgba(${rgb},0.12)`,
      "--border-accent": `rgba(${rgb},0.26)`,
      "--accent-glow": `rgba(${rgb},0.14)`,
    });
  }
  if (style.opacity !== undefined) vars["--dash-alpha"] = String(style.opacity / 100);
  return vars;
}

function LookPopover({ item, onChange, onClose }) {
  const style = item.style || {};
  return (
    <div className="dash-look" role="dialog" aria-label={`Look of ${WIDGETS[item.widget].title}`} onPointerDown={(e) => e.stopPropagation()}>
      <div className="dash-look-row">
        <label htmlFor={`dash-op-${item.id}`}>Panel</label>
        <input
          id={`dash-op-${item.id}`} type="range" min="40" max="100" step="5"
          value={style.opacity ?? 100}
          onChange={(e) => onChange({ ...style, opacity: Number(e.target.value) })}
        />
        <span className="dash-look-val">{style.opacity ?? 100}%</span>
      </div>
      <div className="dash-look-row">
        <span>Accent</span>
        <div className="dash-swatches">
          <button type="button" className={`dash-swatch theme ${style.accent ? "" : "on"}`} title="Theme accent" aria-label="Theme accent"
            onClick={() => onChange({ ...style, accent: undefined })} />
          {ACCENTS.map((c) => (
            <button key={c} type="button" className={`dash-swatch ${style.accent === c ? "on" : ""}`} style={{ background: c }}
              title={c} aria-label={`Accent ${c}`} onClick={() => onChange({ ...style, accent: c })} />
          ))}
          <input type="color" aria-label="Custom accent" value={style.accent || "#78b2ff"}
            onChange={(e) => onChange({ ...style, accent: e.target.value })} />
        </div>
      </div>
      <div className="dash-look-foot">
        <button type="button" className="btn" onClick={() => onChange(null)}>Theme default</button>
        <button type="button" className="btn accent" onClick={onClose}>Close</button>
      </div>
    </div>
  );
}

export function Dashboard({ ctx, notify }) {
  const initial = useMemo(() => loadLayout(WIDGETS, storage()), []);
  const [layout, setLayout] = useState(initial.layout);
  const [notice, setNotice] = useState(initial.problems.length > 0);
  const [draft, setDraft] = useState(null);
  const [history, setHistory] = useState([]);
  const [drag, setDrag] = useState(null);
  const [selected, setSelected] = useState(null);
  const [lookFor, setLookFor] = useState(null);
  const [adding, setAdding] = useState(false);
  const [width, setWidth] = useState(0);
  const gridRef = useRef(null);

  const editing = draft !== null;
  const narrow = width > 0 && width < NARROW_PX;
  const shown = drag?.preview || draft || layout;

  useEffect(() => {
    const el = gridRef.current;
    if (!el || typeof ResizeObserver !== "function") return undefined;
    const ro = new ResizeObserver(([entry]) => setWidth(Math.round(entry.contentRect.width)));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const apply = (next) => {
    if (next === draft) return;
    setHistory((h) => [...h.slice(-49), draft]);
    setDraft(next);
  };
  const begin = () => {
    setDraft(layout);
    setHistory([]);
    setNotice(false);
  };
  const cancel = () => {
    setDraft(null);
    setSelected(null);
    setLookFor(null);
    setAdding(false);
  };
  const done = () => {
    setLayout(draft);
    if (!saveLayout(draft, storage())) notify?.("!", "The layout could not be saved on this computer, so it will reset when the launcher restarts.");
    cancel();
  };
  const undo = () => {
    if (!history.length) return;
    setDraft(history[history.length - 1]);
    setHistory(history.slice(0, -1));
  };

  const startDrag = (event, item, mode) => {
    if (!editing || narrow || event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    const rect = gridRef.current.getBoundingClientRect();
    event.currentTarget.setPointerCapture?.(event.pointerId);
    setSelected(item.id);
    setLookFor(null);
    setDrag({
      id: item.id, mode, px: event.clientX, py: event.clientY, item,
      colW: (rect.width + GAP_PX) / COLS, rowH: ROW_PX + GAP_PX, base: draft, preview: null,
    });
  };
  const moveDrag = (event) => {
    if (!drag) return;
    const dc = Math.round((event.clientX - drag.px) / drag.colW);
    const dr = Math.round((event.clientY - drag.py) / drag.rowH);
    const preview = drag.mode === "move"
      ? moveItem(drag.base, drag.id, drag.item.x + dc, drag.item.y + dr, WIDGETS)
      : resizeItem(drag.base, drag.id, drag.item.w + dc, drag.item.h + dr, WIDGETS);
    setDrag((d) => (d ? { ...d, preview } : d));
  };
  const endDrag = () => {
    if (!drag) return;
    if (drag.preview) apply(drag.preview);
    setDrag(null);
  };

  const onItemKey = (event, item) => {
    if (!editing || narrow) return;
    const step = { ArrowLeft: [-1, 0], ArrowRight: [1, 0], ArrowUp: [0, -1], ArrowDown: [0, 1] }[event.key];
    if (step) {
      event.preventDefault();
      apply(event.shiftKey
        ? resizeItem(draft, item.id, item.w + step[0], item.h + step[1], WIDGETS)
        : moveItem(draft, item.id, item.x + step[0], item.y + step[1], WIDGETS));
    } else if ((event.key === "Delete" || event.key === "Backspace") && !WIDGETS[item.widget].required) {
      event.preventDefault();
      apply(removeItem(draft, item.id, WIDGETS));
    }
  };

  const items = narrow ? [...shown.items].sort((a, b) => a.y - b.y || a.x - b.x) : shown.items;
  const missing = Object.values(WIDGETS).filter((def) => !def.required && !(draft || layout).items.some((i) => i.widget === def.id));

  return (
    <div className={`dash ${editing ? "editing" : ""} ${narrow ? "narrow" : ""}`}>
      <div className="dash-bar">
        {editing ? (
          <>
            <span className="dash-bar-title">Customize your play page</span>
            <div className="dash-bar-actions">
              <button type="button" className="btn" onClick={() => setAdding((v) => !v)} aria-expanded={adding} disabled={narrow}><I.Plus /> Add widget</button>
              <button type="button" className="btn" onClick={undo} disabled={!history.length}>Undo</button>
              <button type="button" className="btn" onClick={() => apply(defaultLayout(WIDGETS))}>Reset to default</button>
              <button type="button" className="btn" onClick={cancel}>Cancel</button>
              <button type="button" className="btn accent" onClick={done}><I.Check /> Done</button>
            </div>
          </>
        ) : (
          <button type="button" className="dash-edit" onClick={begin} title="Arrange the widgets on this page" aria-label="Customize the play page">
            <I.Sliders /> <span>Customize</span>
          </button>
        )}
      </div>

      {notice && !editing && (
        <div className="dash-notice" role="status">
          Your saved layout could not be read in full, so the default came back where it was damaged.
          <button type="button" className="news-retry" onClick={() => setNotice(false)}>OK</button>
        </div>
      )}
      {editing && narrow && <div className="dash-notice" role="status">Make the window wider to move and resize widgets.</div>}

      {adding && editing && (
        <div className="dash-drawer" role="region" aria-label="Widgets you can add">
          {missing.length === 0 && <div className="dash-empty">Every widget is already on the page.</div>}
          {missing.map((def) => {
            const Icon = I[def.icon] || I.Plus;
            return (
              <div key={def.id} className="dash-drawer-item">
                <Icon />
                <div className="dash-drawer-text">
                  <div className="dash-drawer-name">{def.title}</div>
                  <div className="dash-drawer-desc">{def.description}</div>
                </div>
                <button type="button" className="btn" onClick={() => apply(addItem(draft, def.id, WIDGETS))}>Add</button>
              </div>
            );
          })}
        </div>
      )}

      <div
        className="dash-grid"
        ref={gridRef}
        style={{ "--dash-rows": String(Math.max(rowsUsed(shown), 1)) }}
        onPointerMove={moveDrag}
        onPointerUp={endDrag}
        onPointerCancel={endDrag}
      >
        {items.map((item) => {
          const def = WIDGETS[item.widget];
          const Component = def.Component;
          const isDragged = drag?.id === item.id;
          return (
            <div
              key={item.id}
              className={`dash-item ${item.style ? "styled" : ""} ${isDragged ? "dragging" : ""} ${selected === item.id ? "selected" : ""}`}
              data-widget={item.widget}
              style={{
                gridColumn: narrow ? undefined : `${item.x + 1} / span ${item.w}`,
                gridRow: `${narrow ? "auto" : item.y + 1} / span ${item.h}`,
                ...itemStyleVars(item.style),
              }}
              tabIndex={editing ? 0 : undefined}
              role={editing ? "group" : undefined}
              aria-label={editing ? `${def.title}, ${item.w} by ${item.h}. Arrow keys move it, Shift and arrows resize it${def.required ? "" : ", Delete removes it"}.` : undefined}
              onFocus={() => editing && setSelected(item.id)}
              onKeyDown={(e) => onItemKey(e, item)}
            >
              <div className="dash-body" inert={editing ? true : undefined}>
                <Component ctx={ctx} />
              </div>
              {editing && (
                <>
                  <div className="dash-handle" onPointerDown={(e) => startDrag(e, item, "move")}>
                    <span className="dash-grip" aria-hidden="true" />
                    <span className="dash-handle-title">{def.title}</span>
                    <button type="button" className="dash-icon" aria-label={`Look of ${def.title}`} title="Panel look"
                      onPointerDown={(e) => e.stopPropagation()} onClick={() => setLookFor((v) => (v === item.id ? null : item.id))}>
                      <I.Palette />
                    </button>
                    {!def.required && (
                      <button type="button" className="dash-icon remove" aria-label={`Remove ${def.title}`} title="Remove"
                        onPointerDown={(e) => e.stopPropagation()} onClick={() => apply(removeItem(draft, item.id, WIDGETS))}>
                        <I.X />
                      </button>
                    )}
                  </div>
                  {!narrow && <div className="dash-resize" aria-hidden="true" onPointerDown={(e) => startDrag(e, item, "resize")} />}
                  {lookFor === item.id && (
                    <LookPopover item={item} onChange={(style) => apply(setItemStyle(draft, item.id, style))} onClose={() => setLookFor(null)} />
                  )}
                </>
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
