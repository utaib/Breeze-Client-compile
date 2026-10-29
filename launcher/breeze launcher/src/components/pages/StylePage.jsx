import { useState } from "react";
import { PlayerStage } from "../PlayerStage";
import TagSelector from "../TagSelector";
import PlacementControls from "../PlacementControls";
import { CREATOR_UPLOAD_LIMIT, isCreatorTier } from "../../services/breezeApi";

const I = {
  Store: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z" /><line x1="3" y1="6" x2="21" y2="6" /><path d="M16 10a4 4 0 01-8 0" /></svg>,
  Upload: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" y1="3" x2="12" y2="15" /></svg>,
  Palette: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><circle cx="13.5" cy="6.5" r=".5" fill="currentColor" /><circle cx="17.5" cy="10.5" r=".5" fill="currentColor" /><circle cx="8.5" cy="7.5" r=".5" fill="currentColor" /><circle cx="6.5" cy="12.5" r=".5" fill="currentColor" /><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 011.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z" /></svg>,
};

function Panel({ title, children }) {
  return <div className="sg"><div className="sgl">{title}</div><div className="scd">{children}</div></div>;
}
function Row({ title, desc, action }) {
  return <div className="sr"><div className="si"><div className="sn">{title}</div><div className="sd">{desc}</div></div>{action}</div>;
}

export default function StylePage({
  session, profile, userCapes, allCapes,
  userRole, isAdmin, capeBusy,
  handleUnequipCape, setPage, setShowAdminCapeModal, setShowCosmeticUploadModal, setShowPayEquipModal,
  userSkinUrl, equippedCapeData,
  notify,
  ownedCosmetics, onSavePlacement,
}) {
  /** The cosmetic whose placement is being adjusted: { id, transform }. */
  const [placing, setPlacing] = useState(null);
  const [placingBusy, setPlacingBusy] = useState(false);

  // What the player is wearing: each equipped 3D cosmetic with this player's
  // own placement, which the preview shows and the sliders below adjust.
  const ownedById = new Map((ownedCosmetics?.owned || []).map((o) => [o.cosmetic_id, o.cosmetic]).filter(([, c]) => c));
  const worn = Object.values(ownedCosmetics?.equipped || {})
    .map((id) => ownedById.get(id))
    .filter((c) => c && c.model_url)
    .map((c) => ({ ...c, placement: ownedCosmetics?.placements?.[c.id] || null }));
  const wornForViewer = worn.map((c) => ({
    key: String(c.id),
    url: c.model_url,
    cosmetic: c,
    transform: placing?.id === c.id ? placing.transform : c.placement,
  }));

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
  const equippedCape = equippedCapeData;
  const creatorCapeCount = (userCapes || []).length;
  const creatorAtLimit = isCreatorTier(userRole) && creatorCapeCount >= CREATOR_UPLOAD_LIMIT;
  const canUploadMarketplaceCape = isAdmin || isCreatorTier(userRole);

  return (
    <div className="cv page-enter">
      <div className="vtl">
        <I.Palette /> Style &amp; Profile
      </div>

      <div className="style-layout">
        {/* Live player preview, actual skin + equipped cape */}
        <div className="profile-preview-card">
          <PlayerStage
            name={session?.username || null}
            skinUrl={userSkinUrl}
            capeUrl={equippedCape?.image_url || null}
            animationFrames={Array.isArray(equippedCape?.animation_frames) && equippedCape.animation_frames.length > 1 ? equippedCape.animation_frames : null}
            animationFps={equippedCape?.animation_fps || null}
            cosmetics={wornForViewer}
            width={248}
            height={320}
            showElytraToggle
          />
          <div className="panel-title">{session?.username || "Breeze Player"}</div>
          <div className="panel-sub">{equippedCape?.name ? `Cape · ${equippedCape.name}` : "No cape equipped"}</div>
        </div>

        <div className="style-main">
          {/* Cape / Skin */}
          <Panel title="Skin &amp; Cape">
            <Row
              title="Minecraft Account"
              desc={session?.uuid ? `UUID ${session.uuid}` : "Not linked"}
            />
            <Row
              title="Equipped Cape"
              desc={equippedCape?.name || "None equipped"}
              action={
                <div style={{ display: "flex", gap: 6 }}>
                  {equippedCape?.name && (
                    <button className="btn" onClick={handleUnequipCape} disabled={capeBusy}>Remove</button>
                  )}
                  <button className="btn accent" onClick={() => setPage?.("store")}><I.Store /> Browse</button>
                </div>
              }
            />
            {canUploadMarketplaceCape && (
              <Row
                title="Upload &amp; List Cape"
                desc="Marketplace upload, appears in the public store"
                action={<button className="btn" onClick={() => setShowAdminCapeModal?.(true)}><I.Upload /></button>}
              />
            )}
            {(isAdmin || isCreatorTier(userRole)) && (
              <Row
                title="Upload Cosmetic"
                desc={`Hats, wings, pets, auras, shields, back, trail (.glb / .gltf)${isCreatorTier(userRole) ? ` · slot ${creatorCapeCount}/${CREATOR_UPLOAD_LIMIT}` : ""}`}
                action={
                  <button
                    className="btn"
                    onClick={() => setShowCosmeticUploadModal?.(true)}
                    disabled={isCreatorTier(userRole) && creatorAtLimit}
                  >
                    <I.Upload />
                  </button>
                }
              />
            )}
            <Row
              title="Pay &amp; Equip Cape"
              desc="Upload a private cape: $20 one-time · personal use, not listed publicly"
              action={
                <button
                  className="btn accent"
                  onClick={() => setShowPayEquipModal?.(true)}
                  disabled={!session?.breezeToken}
                >
                  $20 · Upload
                </button>
              }
            />
          </Panel>

          {worn.length > 0 && (
            <Panel title="Worn cosmetics">
              {worn.map((c) => (
                <div key={c.id}>
                  <Row
                    title={c.name}
                    desc={c.placement ? "Your own placement" : "Placed where its creator set it"}
                    action={placing?.id === c.id ? null : (
                      <button className="btn" onClick={() => setPlacing({ id: c.id, transform: c.placement || c.metadata?.transform || null })}>
                        Adjust
                      </button>
                    )}
                  />
                  {placing?.id === c.id && (
                    <div className="wardrobe-placement">
                      {/* The preview on the left moves as these do. */}
                      <PlacementControls
                        value={placing.transform}
                        onChange={(transform) => setPlacing({ id: c.id, transform })}
                        disabled={placingBusy}
                        idPrefix={`placement-${c.id}`}
                      />
                      <div className="wardrobe-placement-actions">
                        <button className="btn" onClick={() => setPlacing(null)} disabled={placingBusy}>Cancel</button>
                        {c.placement && (
                          <button className="btn" onClick={() => savePlacement(c.id, null)} disabled={placingBusy}>Creator's placement</button>
                        )}
                        <button className="btn accent" onClick={() => savePlacement(c.id, placing.transform)} disabled={placingBusy}>
                          {placingBusy ? "Saving…" : "Save"}
                        </button>
                      </div>
                    </div>
                  )}
                </div>
              ))}
            </Panel>
          )}

          {/* Tags: which one shows next to your name in chat and socials */}
          <div className="sec-label" style={{ marginTop: 20, marginBottom: 10 }}>Tag</div>
          <Panel title="Displayed Tag">
            <TagSelector token={session?.breezeToken} notify={notify} />
          </Panel>

          {/* The theme and the background moved to Settings, Appearance:
              one theme system, one place to change it. */}
          <div className="sec-label" style={{ marginTop: 20, marginBottom: 10 }}>Launcher look</div>
          <Panel title="Theme and background">
            <Row
              title="Launcher theme and background"
              desc="Colours, panels and the background image are in Settings"
              action={<button className="btn" onClick={() => setPage?.("settings")}>Open Settings</button>}
            />
          </Panel>
        </div>
      </div>
    </div>
  );
}
