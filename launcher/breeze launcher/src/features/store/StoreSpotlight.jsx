import { useEffect, useRef, useState } from "react";
import { PlayerStage } from "../../components/PlayerStage";
import useBoxSize from "../../hooks/useBoxSize";
import { prefersReducedMotion } from "../animation/motionPrefs";
import { ItemArt } from "./StoreArt";
import WcPrice from "./WcPrice";
import { isAnimated, rarityOf, slotLabel, slotOf } from "./storeLook";

/** How long each item stays in the spotlight before the next one. */
export const SPOTLIGHT_MS = 8000;
const TURN_AROUND = "turn-around";

const keyOf = (item) => `${item._type}:${item.id}`;

/**
 * The top of the Store: a few items worn by the player's own character, one
 * at a time. A cape turns the player round to show it; a cosmetic is worn at
 * its place. The next item comes every few seconds, but not while the
 * pointer or keyboard focus is on the spotlight, while the window is hidden,
 * or for players who ask for reduced motion. The list under it picks one.
 */
export default function StoreSpotlight({ picks, onOpen, userSkinUrl, username }) {
  const [index, setIndex] = useState(0);
  const [held, setHeld] = useState(false);
  const [hidden, setHidden] = useState(() => typeof document !== "undefined" && document.hidden);
  const [cue, setCue] = useState(null);
  const [failedModel, setFailedModel] = useState(null);
  const [stageRef, stageBox] = useBoxSize();
  const reduced = prefersReducedMotion();
  const rootRef = useRef(null);
  const count = picks.length;
  const item = picks[Math.min(index, count - 1)] || null;
  const itemKey = item ? keyOf(item) : "";
  const picksKey = picks.map(keyOf).join("|");

  // A new set of picks (bought something, signed in) starts from the first.
  useEffect(() => setIndex(0), [picksKey]);

  useEffect(() => {
    const onVis = () => setHidden(document.hidden);
    document.addEventListener("visibilitychange", onVis);
    return () => document.removeEventListener("visibilitychange", onVis);
  }, []);

  const rotating = count > 1 && !held && !hidden && !reduced;
  useEffect(() => {
    if (!rotating) return undefined;
    const t = window.setTimeout(() => setIndex((i) => (i + 1) % count), SPOTLIGHT_MS);
    return () => window.clearTimeout(t);
  }, [rotating, index, count]);

  // Show a cape on the back: the player turns round each time one comes up.
  // (A moment after it comes up, so a player still loading turns too.)
  useEffect(() => {
    if (item?._type !== "cape") return undefined;
    const t = window.setTimeout(() => setCue({ name: TURN_AROUND, n: Date.now() }), 700);
    return () => window.clearTimeout(t);
  }, [itemKey]); // eslint-disable-line react-hooks/exhaustive-deps

  if (!item) return null;
  const rarity = rarityOf(item);
  const slot = slotOf(item);
  const isCape = item._type === "cape";
  const worn = !isCape && item.model_url && failedModel !== item.model_url
    ? [{ key: String(item.id), url: item.model_url, cosmetic: item }]
    : null;
  const action = item.owned ? (item.equipped ? "Equipped" : "Equip") : Number(item.price_usd) > 0 ? "Buy" : "Claim";
  const stageW = Math.max(160, Math.min(stageBox.w || 260, 320));
  const stageH = Math.max(200, (stageBox.h || 300) - 30);

  const onKeyDown = (e) => {
    if (count < 2) return;
    if (e.key === "ArrowRight") { e.preventDefault(); setIndex((i) => (i + 1) % count); }
    if (e.key === "ArrowLeft") { e.preventDefault(); setIndex((i) => (i - 1 + count) % count); }
  };

  return (
    <section
      ref={rootRef}
      className={`store-spot rar-${rarity.tone}`}
      aria-roledescription="carousel"
      aria-label="Featured"
      onMouseEnter={() => setHeld(true)}
      onMouseLeave={() => setHeld(false)}
      onFocus={() => setHeld(true)}
      onBlur={(e) => { if (!rootRef.current?.contains(e.relatedTarget)) setHeld(false); }}
      onKeyDown={onKeyDown}
    >
      <div className="store-spot-stage" ref={stageRef}>
        <PlayerStage
          name={username || null}
          skinUrl={userSkinUrl}
          capeUrl={isCape ? item.image_url || null : null}
          animationFrames={isCape && isAnimated(item) ? item.animation_frames : null}
          animationFps={isCape ? item.animation_fps || null : null}
          cosmetics={worn}
          onCosmeticError={() => setFailedModel(item.model_url)}
          width={stageW}
          height={stageH}
          momentPace="calm"
          momentCue={cue}
        />
      </div>
      <div className="store-spot-info" key={itemKey} aria-live="polite">
        <div className="store-spot-eyebrow">
          <span className="store-spot-kicker">Featured</span>
          <span className="store-chip rar">{rarity.label}</span>
          <span className="store-chip">{slotLabel(slot)}</span>
          {isAnimated(item) && <span className="store-chip anim">Animated</span>}
        </div>
        <h2 className="store-spot-name">{item.name}</h2>
        {item.description && <p className="store-spot-desc">{item.description}</p>}
        {item.creator_name && <div className="store-spot-by">by {item.creator_name}</div>}
        <div className="store-spot-buy">
          <div className="store-spot-price"><WcPrice priceUsd={item.price_usd} /></div>
          <button type="button" className={`store-spot-cta ${item.owned ? "owned" : ""}`} onClick={() => onOpen(item)}>
            {action}
          </button>
          <button type="button" className="store-spot-more" onClick={() => onOpen(item)}>Details</button>
        </div>
      </div>
      <div className="store-spot-art" aria-hidden="true">
        <span className="store-spot-art-item" key={itemKey}><ItemArt item={item} /></span>
      </div>
      {count > 1 && (
        <div className="store-spot-picks" role="group" aria-label="Featured items">
          {picks.map((p, i) => (
            <button
              key={keyOf(p)}
              type="button"
              className={`store-spot-pick rar-${rarityOf(p).tone} ${i === index ? "on" : ""}`}
              aria-label={`Show ${p.name}`}
              aria-current={i === index ? "true" : undefined}
              onClick={() => setIndex(i)}
            >
              <span className="store-spot-pick-art"><ItemArt item={p} animate={false} /></span>
              {i === index && rotating && <span className="store-spot-pick-time" key={`${keyOf(p)}:${index}`} style={{ animationDuration: `${SPOTLIGHT_MS}ms` }} />}
            </button>
          ))}
        </div>
      )}
    </section>
  );
}
