import { useMemo, useState } from "react";
import { PlayerStage } from "../../components/PlayerStage";
import TagSelector from "../../components/TagSelector";
import PlacementControls from "../../components/PlacementControls";
import { Panel, Row } from "../../components/Shared";
import { I } from "../../ui/icons";
import useBoxSize from "../../hooks/useBoxSize";
import { CREATOR_UPLOAD_LIMIT, isCreatorTier } from "../../services/breezeApi";
import { capeCatalog, CAPE_FILTERS, CAPE_LABEL } from "./catalog";
import CapeThumb from "./CapeThumb";
import { MOMENT_LABELS } from "../animation/moments";
import { prefersReducedMotion } from "../animation/motionPrefs";

/**
 * The Wardrobe (1.1.0): the player large on the left, what they can wear on
 * the right, and one bar under the player for the cape being looked at.
 *
 * Clicking a cape puts it on the player at once (a preview); Equip asks the
 * API, which checks ownership, and the list is reloaded from its answer, so
 * nothing is shown as worn until the server says so. The list is labelled
 * yours, a gift, granted to you, your upload, free, in the Store or taken
 * back (features/wardrobe/catalog.js). While it loads, the list saved on this
 * computer shows, marked as such; offline, it stays and equipping waits.
 *
 * Categories are tabs from one list, so a new kind of cosmetic is one more
 * entry, not a new screen.
 */
const CAPE_CHECK = "animation.breeze.cape_check";
const TURN_AROUND = "turn-around";

const when = (at) => (at ? new Date(at).toLocaleString(undefined, { dateStyle: "medium", timeStyle: "short" }) : null);

export default function WardrobePage({
  session, profile, userCapes, revokedCapes, allCapes, capesLoad, onRetryCapes,
  userRole, isAdmin, creatorUploadCount = 0,
  handleEquipCape, handleUnequipCape, setPage,
  setShowAdminCapeModal, setShowCosmeticUploadModal, setShowPayEquipModal,
  userSkinUrl, equippedCapeData, notify,
  ownedCosmetics, onSavePlacement,
}) {
  const [stageRef, stageSize] = useBoxSize();
  const [tab, setTab] = useState("capes");
  const [filter, setFilter] = useState("all");
  const [previewId, setPreviewId] = useState(null);
  const [busy, setBusy] = useState(false);
  const [cue, setCue] = useState(null);
  /** The cosmetic whose placement is being adjusted: { id, transform }. */
  const [placing, setPlacing] = useState(null);
  const [placingBusy, setPlacingBusy] = useState(false);

  const capes = useMemo(
    () => capeCatalog({ owned: userCapes, revoked: revokedCapes, publicCapes: allCapes, personalCapeUrl: profile?.capeUrl }),
    [userCapes, revokedCapes, allCapes, profile?.capeUrl],
  );
  const worn = capes.find((c) => c.equipped) || null;
  const selected = (previewId && capes.find((c) => c.id === previewId)) || worn;
  const previewing = Boolean(previewId && selected && selected !== worn);
  const shown = selected || (equippedCapeData ? { image_url: equippedCapeData.image_url, frames: null } : null);
  const status = capesLoad?.status || "idle";
  const offline = status === "offline";
  const list = capes.filter(CAPE_FILTERS.find((f) => f.id === filter)?.test || (() => true));
  const ownedCount = capes.filter((c) => c.owned).length;

  // 3D cosmetics the player wears, with their own placement (unchanged from
  // the Style page this replaces).
  const ownedById = new Map((ownedCosmetics?.owned || []).map((o) => [o.cosmetic_id, o.cosmetic]).filter(([, c]) => c));
  const wornCosmetics = Object.values(ownedCosmetics?.equipped || {})
    .map((id) => ownedById.get(id))
    .filter((c) => c && c.model_url)
    .map((c) => ({ ...c, placement: ownedCosmetics?.placements?.[c.id] || null }));
  const wornForViewer = wornCosmetics.map((c) => ({
    key: String(c.id),
    url: c.model_url,
    cosmetic: c,
    transform: placing?.id === c.id ? placing.transform : c.placement,
  }));

  const canUpload = isAdmin || isCreatorTier(userRole);
  const atLimit = isCreatorTier(userRole) && creatorUploadCount >= CREATOR_UPLOAD_LIMIT;
  const tabs = [
    { id: "capes", label: "Capes" },
    { id: "cosmetics", label: "3D cosmetics" },
    { id: "tag", label: "Tag" },
    ...(canUpload ? [{ id: "uploads", label: "Uploads" }] : []),
  ];

  // Picking a cape puts it on and turns the player round to show it.
  const pick = (cape) => {
    setPreviewId(cape.equipped ? null : cape.id);
    setCue({ name: TURN_AROUND, n: Date.now() });
  };
  const equip = async (cape) => {
    if (busy || !cape) return;
    setBusy(true);
    try {
      // Only once the API has said yes: the player then checks the new cape.
      if (await handleEquipCape({ id: cape.id, name: cape.name, equipped: false })) {
        setPreviewId(null);
        setCue({ name: CAPE_CHECK, n: Date.now() });
      }
    } finally {
      setBusy(false);
    }
  };
  const takeOff = async () => {
    if (busy) return;
    setBusy(true);
    try {
      if (await handleUnequipCape()) setPreviewId(null);
    } finally {
      setBusy(false);
    }
  };
  const savePlacement = async (id, transform) => {
    if (!onSavePlacement || placingBusy) return;
    setPlacingBusy(true);
    try {
      await onSavePlacement(id, transform);
      setPlacing(null);
      notify?.("ok", transform ? "Placement saved" : "Back to the creator's placement");
    } catch (err) {
      notify?.("!", err?.message || "Could not save the placement");
    } finally {
      setPlacingBusy(false);
    }
  };

  const barText = !selected
    ? "No cape. Pick one on the right to see it on you."
    : selected.equipped
      ? "You are wearing this, here and in game."
      : selected.how === "revoked"
        ? "This was given to you and taken back, so it can no longer be worn."
        : selected.owned
          ? "Yours. Equip it to wear it here and in game."
          : selected.how === "free"
            ? "Free for everyone. Get it in the Store to wear it."
            : "A preview. Get it in the Store to wear it.";

  return (
    <div className="cv page-enter wardrobe-page">
      <div className="vtl"><I.Palette /> Wardrobe</div>

      <div className="wardrobe">
        <section className="wardrobe-stage" aria-label="Your player">
          <div className="wardrobe-stage-box" ref={stageRef}>
            {stageSize.w > 40 && stageSize.h > 80 && (
              <PlayerStage
                name={session?.username || null}
                skinUrl={userSkinUrl}
                capeUrl={shown?.image_url || null}
                animationFrames={shown?.frames || null}
                animationFps={shown?.fps || null}
                cosmetics={wornForViewer}
                width={Math.min(stageSize.w, 560)}
                height={Math.max(200, stageSize.h - 30)}
                showElytraToggle
                momentPace="lively"
                momentCue={cue}
              />
            )}
          </div>
          {/* Any moment on request, to see each one (also with the random
              ones switched off in Config); never with reduced motion. */}
          <div className="wardrobe-moments" role="group" aria-label="Try a moment">
            <span className="wardrobe-moments-label">Try a moment</span>
            {Object.entries(MOMENT_LABELS).map(([id, label]) => (
              <button key={id} type="button" className="chip" disabled={prefersReducedMotion()}
                title={prefersReducedMotion() ? "Your system asks for reduced motion" : undefined}
                onClick={() => setCue({ name: id, n: Date.now(), force: true })}>
                {label}
              </button>
            ))}
          </div>
          <div className="wardrobe-bar" aria-live="polite">
            <div className="wardrobe-bar-info">
              <div className="wardrobe-bar-name">
                {selected ? selected.name : "No cape"}
                {selected && <span className={`wd-badge ${selected.how}`}>{selected.equipped ? "Wearing" : CAPE_LABEL[selected.how]}</span>}
                {selected?.private && selected.owned && <span className="wd-badge private">Private</span>}
              </div>
              <div className="wardrobe-bar-sub">{barText}</div>
            </div>
            <div className="wardrobe-bar-actions">
              {previewing && <button className="btn" onClick={() => setPreviewId(null)}>Back to what you wear</button>}
              {selected?.owned && !selected.equipped && selected.how !== "personal" && (
                <button className="btn accent" onClick={() => equip(selected)} disabled={busy || offline}
                  title={offline ? "Equipping needs a connection to Breeze" : undefined}>
                  {busy ? <I.Spin /> : <I.Check />} Equip
                </button>
              )}
              {selected?.equipped && (
                <button className="btn" onClick={takeOff} disabled={busy || offline}>Take it off</button>
              )}
              {selected && (selected.how === "store" || selected.how === "free") && (
                <button className="btn accent" onClick={() => setPage?.("store")}><I.Store /> Open the Store</button>
              )}
            </div>
          </div>
        </section>

        <aside className="wardrobe-side">
          <div className="wardrobe-tabs" role="tablist" aria-label="What to wear">
            {tabs.map((t) => (
              <button key={t.id} type="button" role="tab" aria-selected={tab === t.id}
                className={`wardrobe-tab ${tab === t.id ? "on" : ""}`} onClick={() => setTab(t.id)}>
                {t.label}
              </button>
            ))}
          </div>

          {tab === "capes" && (
            <div className="wardrobe-capes" role="tabpanel" aria-label="Capes">
              {(status === "loading" && capesLoad?.cached) && (
                <div className="wd-note" role="status">Your capes as saved on this computer ({when(capesLoad.at)}). Checking for changes.</div>
              )}
              {offline && (
                <div className="wd-note warn" role="status">
                  Breeze could not be reached{capesLoad?.at ? `, so these are your capes as of ${when(capesLoad.at)}` : ""}. Equipping waits for a connection.
                  <button className="btn" onClick={onRetryCapes}>Try again</button>
                </div>
              )}
              {status === "error" && (
                <div className="wd-note warn" role="alert">
                  Your capes could not be loaded.
                  <button className="btn" onClick={onRetryCapes}>Try again</button>
                </div>
              )}

              <div className="seg wd-filters" role="group" aria-label="Show">
                {CAPE_FILTERS.map((f) => (
                  <button key={f.id} type="button" className={`segb ${filter === f.id ? "on" : ""}`} aria-pressed={filter === f.id} onClick={() => setFilter(f.id)}>
                    {f.label}{f.id === "mine" ? ` (${ownedCount})` : ""}
                  </button>
                ))}
              </div>

              {status === "loading" && !capes.length ? (
                <div className="wd-grid" aria-busy="true" aria-label="Loading your capes">
                  {Array.from({ length: 6 }, (_, i) => <span key={i} className="wd-cape skeleton" />)}
                </div>
              ) : list.length ? (
                <div className="wd-grid">
                  {list.map((c) => (
                    <button
                      key={`${c.how}-${c.id}`}
                      type="button"
                      className={`wd-cape ${c.how} ${selected?.id === c.id ? "sel" : ""} ${c.equipped ? "worn" : ""}`}
                      aria-pressed={selected?.id === c.id}
                      aria-label={`${c.name}, ${c.equipped ? "wearing" : CAPE_LABEL[c.how].toLowerCase()}`}
                      onClick={() => pick(c)}
                    >
                      <CapeThumb url={c.image_url} name={c.name} />
                      <span className="wd-cape-name">{c.name}</span>
                      <span className={`wd-badge ${c.how}`}>{c.equipped ? "Wearing" : CAPE_LABEL[c.how]}</span>
                      {c.equipped && <span className="wd-worn" aria-hidden="true"><I.Check /></span>}
                    </button>
                  ))}
                </div>
              ) : (
                <div className="wd-empty">
                  {filter === "mine" ? "You do not have a cape yet." : "No capes to show."}
                  <button className="btn accent" onClick={() => setPage?.("store")}><I.Store /> Browse the Store</button>
                </div>
              )}

              <Panel title="Your own cape">
                <Row
                  title="Pay and Equip"
                  desc="Upload a private cape: $20 once, for you only, not listed in the Store"
                  action={<button className="btn" onClick={() => setShowPayEquipModal?.(true)} disabled={!session?.breezeToken}><I.Upload /> $20</button>}
                />
              </Panel>
            </div>
          )}

          {tab === "cosmetics" && (
            <div role="tabpanel" aria-label="3D cosmetics">
              {wornCosmetics.length ? (
                <Panel title="Worn cosmetics">
                  {wornCosmetics.map((c) => (
                    <div key={c.id}>
                      <Row
                        title={c.name}
                        desc={c.placement ? "Your own placement" : "Placed where its creator set it"}
                        action={placing?.id === c.id ? null : (
                          <button className="btn" onClick={() => setPlacing({ id: c.id, transform: c.placement || c.metadata?.transform || null })}>Adjust</button>
                        )}
                      />
                      {placing?.id === c.id && (
                        <div className="wardrobe-placement">
                          {/* The player on the left moves as these do. */}
                          <PlacementControls
                            value={placing.transform}
                            onChange={(transform) => setPlacing({ id: c.id, transform })}
                            disabled={placingBusy}
                            idPrefix={`placement-${c.id}`}
                          />
                          <div className="wardrobe-placement-actions">
                            <button className="btn" onClick={() => setPlacing(null)} disabled={placingBusy}>Cancel</button>
                            {c.placement && <button className="btn" onClick={() => savePlacement(c.id, null)} disabled={placingBusy}>Creator's placement</button>}
                            <button className="btn accent" onClick={() => savePlacement(c.id, placing.transform)} disabled={placingBusy}>
                              {placingBusy ? "Saving…" : "Save"}
                            </button>
                          </div>
                        </div>
                      )}
                    </div>
                  ))}
                </Panel>
              ) : (
                <div className="wd-empty">
                  You are not wearing a 3D cosmetic. Hats, wings, pets and more are in the Store.
                  <button className="btn accent" onClick={() => setPage?.("store")}><I.Store /> Browse the Store</button>
                </div>
              )}
            </div>
          )}

          {tab === "tag" && (
            <div role="tabpanel" aria-label="Tag">
              <Panel title="Displayed tag">
                <TagSelector token={session?.breezeToken} notify={notify} />
              </Panel>
            </div>
          )}

          {tab === "uploads" && canUpload && (
            <div role="tabpanel" aria-label="Uploads">
              <Panel title="Creator uploads">
                <Row
                  title="Upload and list a cape"
                  desc="A marketplace cape, listed in the public Store"
                  action={<button className="btn" onClick={() => setShowAdminCapeModal?.(true)}><I.Upload /> Cape</button>}
                />
                <Row
                  title="Upload a cosmetic"
                  desc={`Hats, wings, pets, auras, shields, back and trails (.glb or .gltf)${isCreatorTier(userRole) ? `, ${creatorUploadCount} of ${CREATOR_UPLOAD_LIMIT} used` : ""}`}
                  action={<button className="btn" onClick={() => setShowCosmeticUploadModal?.(true)} disabled={atLimit}><I.Upload /> Cosmetic</button>}
                />
              </Panel>
            </div>
          )}

          {/* The theme and the background are in Config, Appearance: one
              theme system, one place to change it. */}
          <Panel title="Launcher look">
            <Row
              title="Launcher theme and background"
              desc="Colours, panels and the background image are in Settings"
              action={<button className="btn" onClick={() => setPage?.("settings")}>Open Settings</button>}
            />
          </Panel>
        </aside>
      </div>
    </div>
  );
}
