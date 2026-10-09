import { useState, useEffect } from "react";
import AdminTagManager from "../AdminTagManager";
import { CREATOR_UPLOAD_LIMIT, isCreatorTier } from "../../services/breezeApi";

const I = {
  Shield: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" /></svg>,
  Store: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z" /><line x1="3" y1="6" x2="21" y2="6" /><path d="M16 10a4 4 0 01-8 0" /></svg>,
  Upload: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4" /><polyline points="17 8 12 3 7 8" /><line x1="12" y1="3" x2="12" y2="15" /></svg>,
  Palette: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><circle cx="13.5" cy="6.5" r=".5" fill="currentColor" /><circle cx="17.5" cy="10.5" r=".5" fill="currentColor" /><circle cx="8.5" cy="7.5" r=".5" fill="currentColor" /><circle cx="6.5" cy="12.5" r=".5" fill="currentColor" /><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 011.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z" /></svg>,
};

export default function AdminPage({
  profile, session, userRole,
  creatorStats, creatorCapes, setPage,
  setShowAdminCapeModal, setShowCosmeticUploadModal,
  notify,
}) {
  const creatorSharePct = profile?.creator_share_percent != null
    ? Number(profile.creator_share_percent).toFixed(0)
    : null;

  const creatorCapeCount = typeof creatorStats?.capes_created === "number"
    ? creatorStats.capes_created
    : (creatorCapes?.length || 0);

  const creatorAtLimit = isCreatorTier(userRole) && creatorCapeCount >= CREATOR_UPLOAD_LIMIT;

  return (
    <div className="sv page-enter">
      <div className="vtl" style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <I.Shield />
        Creator Dashboard
        <span className="admin-badge">{userRole}</span>
      </div>

      {/* Stats row */}
      <div className="creator-stats-row">
        {[
          { label: "Total Sales", value: creatorStats?.total_sales?.toLocaleString() ?? "N/A" },
          { label: "Gross Revenue", value: creatorStats?.total_gross_usd != null ? `$${Number(creatorStats.total_gross_usd).toFixed(2)}` : "N/A" },
          { label: "Your Earnings", value: creatorStats?.creator_earnings_usd != null ? `$${Number(creatorStats.creator_earnings_usd).toFixed(2)}` : "N/A" },
          { label: "Your Share", value: creatorSharePct != null ? `${creatorSharePct}%` : "N/A" },
          { label: "Capes Listed", value: isCreatorTier(userRole) ? `${creatorCapeCount} / ${CREATOR_UPLOAD_LIMIT}` : String(creatorCapeCount) },
        ].map((s) => (
          <div key={s.label} className="creator-stat-card">
            <div className="creator-stat-val">{s.value}</div>
            <div className="creator-stat-lbl">{s.label}</div>
          </div>
        ))}
      </div>

      {/* Identity */}
      <div className="sg">
        <div className="sgl">Identity</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">Account</div>
              <div className="sd">{session?.username} · {userRole} · UUID {session?.uuid?.slice(0, 8)}…</div>
            </div>
            <span className="admin-badge">{userRole}</span>
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">Revenue Share</div>
              <div className="sd">
                {creatorSharePct != null
                  ? `You receive ${creatorSharePct}% of each sale. Platform retains the rest (incl. co-owner split handled internally).`
                  : "Revenue share is configured by the platform admin."}
              </div>
            </div>
            <div style={{ fontSize: 11, color: "rgba(165,135,255,0.7)", fontWeight: 700 }}>{creatorSharePct != null ? `${creatorSharePct}%` : "N/A"}</div>
          </div>
        </div>
      </div>

      {/* Cape Management */}
      <div className="sg">
        <div className="sgl">Cape Management</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">List New Cape</div>
              <div className="sd">
                Upload a cape to the marketplace with price, rarity &amp; description
                {isCreatorTier(userRole) && <> · <strong>{creatorCapeCount} / {CREATOR_UPLOAD_LIMIT}</strong> slots used</>}
              </div>
            </div>
            <button
              className="btn accent"
              onClick={() => setShowAdminCapeModal?.(true)}
              disabled={creatorAtLimit}
              title={creatorAtLimit ? "Creator limit reached (2 capes)" : undefined}
            >
              <I.Upload /> New Cape
            </button>
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">Your Listed Capes</div>
              <div className="sd">{creatorCapeCount} cape{creatorCapeCount !== 1 ? "s" : ""} in marketplace</div>
            </div>
            <button className="btn" onClick={() => setPage?.("store")}><I.Store /> View Store</button>
          </div>
        </div>
      </div>

      {/* Cosmetics Management */}
      <div className="sg">
        <div className="sgl">Cosmetics Management</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">Upload Cosmetic</div>
              <div className="sd">
                Hats · Wings · Pets · Auras · Shields · Back · Trail (.glb / .gltf)
                {isCreatorTier(userRole) && <> · <strong>{creatorCapeCount} / {CREATOR_UPLOAD_LIMIT}</strong> creator slots used</>}
              </div>
            </div>
            <button
              className="btn accent"
              onClick={() => setShowCosmeticUploadModal?.(true)}
              disabled={isCreatorTier(userRole) && creatorAtLimit}
              title={isCreatorTier(userRole) && creatorAtLimit ? `Creator upload limit reached (${CREATOR_UPLOAD_LIMIT} cosmetics)` : "Upload a new cosmetic (.glb / .gltf)"}
            >
              <I.Upload /> New Cosmetic
            </button>
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">Browse Catalogue</div>
              <div className="sd">See every cosmetic currently in the public catalogue.</div>
            </div>
            <button className="btn" onClick={() => setPage?.("custom")}>
              <I.Palette /> Open Wardrobe
            </button>
          </div>
        </div>
      </div>

      {/* Tag Management: admins and owners only, matching the endpoint's own guard */}
      {(userRole === "admin" || userRole === "owner") && (
        <div className="sg">
          <div className="sgl">Tag Management</div>
          <div className="scd">
            <AdminTagManager token={session?.breezeToken} notify={notify} />
          </div>
        </div>
      )}

      {/* Listed Capes */}
      {creatorCapes && creatorCapes.length > 0 && (
        <div className="sg">
          <div className="sgl">Your Capes</div>
          <div className="scd">
            {creatorCapes.map((cape) => (
              <div key={cape.id} className="sr">
                <div style={{ width: 36, height: 36, borderRadius: 8, overflow: "hidden", flexShrink: 0, background: "var(--surface)", border: "1px solid var(--border)", display: "flex", alignItems: "center", justifyContent: "center" }}>
                  {cape.image_url
                    ? <img src={cape.image_url} alt="" style={{ width: "100%", height: "100%", objectFit: "contain" }} />
                    : <svg viewBox="0 0 24 24" width="16" height="16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10" /></svg>}
                </div>
                <div className="si">
                  <div className="sn">{cape.name}</div>
                  <div className="sd">
                    <span className={`badge ${cape.rarity || "premium"}`} style={{ marginRight: 6 }}>{cape.rarity || "premium"}</span>
                    {Number(cape.price_usd ?? 0) === 0 ? "Free" : `$${Number(cape.price_usd).toFixed(2)}`}
                    {" · "}{cape.is_public ? "Public" : "Hidden"}
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Production notes */}
      <div className="sg">
        <div className="sgl">Platform Notes</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">Payments</div>
              <div className="sd">Webhook driven through server.js and .env configuration.</div>
            </div>
          </div>
          <div className="sr">
            <div className="si">
              <div className="sn">Uploads</div>
              <div className="sd">Cosmetic and cape upload flows stay backend owned.</div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
