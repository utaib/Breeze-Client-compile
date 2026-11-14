import { useEffect, useRef, useState } from "react";
import { I } from "../../ui/icons";
import { EDITIONS, editionById } from "./editions";
import "./edition.css";

/**
 * The edition menu in the title bar: Java Edition, which is what Breeze runs,
 * and Bedrock Edition, planned. Choosing Bedrock calls onPick("bedrock"),
 * which opens the page saying what is planned; it does not switch anything.
 */
export function EditionSwitch({ current = "java", onPick }) {
  const [open, setOpen] = useState(false);
  const wrap = useRef(null);
  const items = useRef([]);
  const edition = editionById(current);

  useEffect(() => {
    if (!open) return undefined;
    // Focus the chosen edition, as a menu does, so the keys work at once.
    const at = Math.max(0, EDITIONS.findIndex((e) => e.id === current));
    items.current[at]?.focus();
    const away = (event) => {
      if (wrap.current && !wrap.current.contains(event.target)) setOpen(false);
    };
    document.addEventListener("mousedown", away);
    return () => document.removeEventListener("mousedown", away);
  }, [open, current]);

  const choose = (id) => {
    setOpen(false);
    onPick?.(id);
  };

  const keys = (event) => {
    const at = items.current.indexOf(document.activeElement);
    if (event.key === "Escape") {
      event.preventDefault();
      setOpen(false);
      wrap.current?.querySelector(".ed-btn")?.focus();
    } else if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      const step = event.key === "ArrowDown" ? 1 : -1;
      items.current[(at + step + EDITIONS.length) % EDITIONS.length]?.focus();
    }
  };

  return (
    <div className="ed-wrap" ref={wrap} onKeyDown={open ? keys : undefined}>
      <button
        type="button"
        className={`ed-btn ${open ? "on" : ""}`}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`Minecraft edition: ${edition.name}`}
        onClick={() => setOpen((v) => !v)}
      >
        <span>{edition.name}</span>
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m6 9 6 6 6-6" /></svg>
      </button>
      {open && (
        <div className="ed-menu" role="menu" aria-label="Minecraft edition">
          {EDITIONS.map((e, i) => (
            <button
              key={e.id}
              ref={(el) => { items.current[i] = el; }}
              type="button"
              role="menuitemradio"
              aria-checked={e.id === current}
              className={`ed-item ${e.id === current ? "on" : ""}`}
              onClick={() => choose(e.id)}
            >
              <span className="ed-item-text">
                <span className="ed-item-name">
                  {e.name}
                  {!e.ready && <span className="ed-soon">Planned</span>}
                </span>
                <span className="ed-item-sum">{e.summary}</span>
              </span>
              {e.id === current && <I.Check />}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}
