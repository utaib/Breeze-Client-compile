import { useCallback, useEffect, useState } from "react";
import FeatureToggles from "./FeatureToggles";
import {
  getCreatorWallet, requestWithdrawal,
  adminListWithdrawals, adminResolveWithdrawal,
  WC_PER_ROD, CREATOR_UPLOAD_LIMIT,
  isCreatorTier,
} from "../../services/breezeApi";

const I = {
  Shield: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>,
  Upload: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"><path d="M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/></svg>,
  Store: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><path d="M6 2L3 6v14a2 2 0 002 2h14a2 2 0 002-2V6l-3-4z"/><line x1="3" y1="6" x2="21" y2="6"/><path d="M16 10a4 4 0 01-8 0"/></svg>,
  Palette: () => <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round"><circle cx="13.5" cy="6.5" r=".5" fill="currentColor"/><circle cx="17.5" cy="10.5" r=".5" fill="currentColor"/><circle cx="8.5" cy="7.5" r=".5" fill="currentColor"/><circle cx="6.5" cy="12.5" r=".5" fill="currentColor"/><path d="M12 2C6.5 2 2 6.5 2 12s4.5 10 10 10c.926 0 1.648-.746 1.648-1.688 0-.437-.18-.835-.437-1.125-.29-.289-.438-.652-.438-1.125a1.64 1.64 0 011.668-1.668h1.996c3.051 0 5.555-2.503 5.555-5.554C21.965 6.012 17.461 2 12 2z"/></svg>,
};

function rods(amountWc) {
  return (Number(amountWc || 0) / WC_PER_ROD).toFixed(2);
}

/** Creator earnings: Breeze Rod balance, sale history, withdrawal requests.
 *  Rods are display-only, 1 rod = 64 Wind Charges = $1. */
function CreatorEarnings({ token, notify, profile }) {
  const [creatorWallet, setCreatorWallet] = useState(null);
  const [showRequest, setShowRequest] = useState(false);
  const [amountRods, setAmountRods] = useState("");
  const [paypalEmail, setPaypalEmail] = useState(profile?.paypalEmail || "");
  const [busy, setBusy] = useState(false);

  const reload = useCallback(() => {
    if (!token) return;
    getCreatorWallet(token).then(setCreatorWallet).catch(() => setCreatorWallet(null));
  }, [token]);

  useEffect(() => { reload(); }, [reload]);

  if (!creatorWallet) return null;
  const balanceRods = Number(creatorWallet.breeze_rods || 0);
  const pending = (creatorWallet.withdrawals || []).find((w) => w.status === "pending");

  const submit = async () => {
    const amount = parseFloat(amountRods);
    setBusy(true);
    try {
      await requestWithdrawal(token, { amountRods: amount, paypalEmail });
      notify("ok", "Withdrawal request sent to the owner");
      setShowRequest(false);
      setAmountRods("");
      reload();
    } catch (err) {
      notify("!", err?.message || "Withdrawal request failed");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="sg">
      <div className="sgl">Creator Earnings, Breeze Rods</div>
      <div className="scd" style={{ padding: 16, gap: 12 }}>
        <div className="rod-balance-hero">
          <span className="rod-ico" />
          <div style={{ flex: 1 }}>
            <div className="rod-balance-num">{balanceRods.toFixed(2)} Breeze Rods</div>
            <div className="rod-balance-sub">≈ ${balanceRods.toFixed(2)} · 1 rod = 64 Wind Charges = $1 · credited automatically when your items sell</div>
          </div>
          <button
            className="btn accent"
            disabled={Boolean(pending) || balanceRods < creatorWallet.min_withdrawal_rods}
            onClick={() => setShowRequest((value) => !value)}
            title={pending ? "You already have a pending request" : balanceRods < creatorWallet.min_withdrawal_rods ? `Minimum withdrawal is ${creatorWallet.min_withdrawal_rods} rods` : undefined}
          >
            Request withdrawal
          </button>
        </div>

        {showRequest && (
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap", alignItems: "flex-end" }}>
            <div style={{ flex: "0 1 140px" }}>
              <div className="promo-field-label">Amount (rods)</div>
              <input className="settings-input" type="number" min={creatorWallet.min_withdrawal_rods} max={Math.floor(balanceRods * 100) / 100} step="0.01" value={amountRods} onChange={(event) => setAmountRods(event.target.value)} placeholder={`min ${creatorWallet.min_withdrawal_rods}`} />
            </div>
            <div style={{ flex: "1 1 220px" }}>
              <div className="promo-field-label">PayPal email</div>
              <input className="settings-input" type="email" value={paypalEmail} onChange={(event) => setPaypalEmail(event.target.value)} placeholder="you@paypal.com" />
            </div>
            <button className="btn accent" onClick={submit} disabled={busy || !amountRods || !paypalEmail}>
              {busy ? "Sending…" : "Submit"}
            </button>
          </div>
        )}

        {(creatorWallet.withdrawals || []).length > 0 && (
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <div className="sec-label" style={{ marginBottom: 0 }}>Withdrawal history</div>
            {creatorWallet.withdrawals.map((request) => (
              <div key={request.id} className="withdrawal-row">
                <span style={{ fontWeight: 600 }}>{rods(request.amount_wc)} rods</span>
                <span style={{ color: "var(--text-faint)", fontSize: 11 }}>{new Date(request.created_at).toLocaleDateString()}</span>
                <span className={`withdrawal-status ${request.status}`} style={{ marginLeft: "auto" }}>{request.status}</span>
              </div>
            ))}
          </div>
        )}

        {(creatorWallet.recent_earnings || []).length > 0 && (
          <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
            <div className="sec-label" style={{ marginBottom: 0 }}>Recent sales</div>
            {creatorWallet.recent_earnings.slice(0, 8).map((earning, index) => (
              <div key={index} className="withdrawal-row">
                <span>{earning.note || "Item sale"}</span>
                <span className="wallet-txn-amt pos" style={{ marginLeft: "auto" }}>+{rods(earning.amount_wc)} rods</span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

/** Owner-side payout queue: review requests, pay manually via PayPal, then
 *  mark paid (which deducts the creator's rods) or reject. */
function WithdrawalQueue({ token, notify }) {
  const [withdrawals, setWithdrawals] = useState([]);
  const [busyId, setBusyId] = useState(null);

  const reload = useCallback(() => {
    if (!token) return;
    adminListWithdrawals(token).then((result) => setWithdrawals(result.withdrawals || [])).catch(() => {});
  }, [token]);

  useEffect(() => { reload(); }, [reload]);

  const resolve = async (request, status) => {
    setBusyId(request.id);
    try {
      await adminResolveWithdrawal(token, request.id, status);
      notify("ok", `Request ${status}`);
      reload();
    } catch (err) {
      notify("!", err?.message || "Failed to update request");
    } finally {
      setBusyId(null);
    }
  };

  const open = withdrawals.filter((w) => w.status === "pending" || w.status === "approved");

  return (
    <div className="sg">
      <div className="sgl">Creator Withdrawals</div>
      <div className="scd" style={{ padding: 14, gap: 6 }}>
        {open.length === 0 && <div className="empty-panel">No pending withdrawal requests.</div>}
        {open.map((request) => (
          <div key={request.id} className="withdrawal-row" style={{ flexWrap: "wrap" }}>
            <span style={{ fontWeight: 600 }}>{request.username}</span>
            <span>{rods(request.amount_wc)} rods (${rods(request.amount_wc)})</span>
            <span style={{ color: "var(--text-faint)", fontSize: 11 }}>{request.paypal_email}</span>
            <span className={`withdrawal-status ${request.status}`}>{request.status}</span>
            <span style={{ marginLeft: "auto", display: "flex", gap: 6 }}>
              {request.status === "pending" && (
                <button className="btn" disabled={busyId === request.id} onClick={() => resolve(request, "approved")}>Approve</button>
              )}
              <button className="btn accent" disabled={busyId === request.id} onClick={() => resolve(request, "paid")} title="After sending the money in PayPal, mark it paid, this deducts the creator's rods">Mark paid</button>
              <button className="btn danger" disabled={busyId === request.id} onClick={() => resolve(request, "rejected")}>Reject</button>
            </span>
          </div>
        ))}
        <div style={{ fontSize: 11, color: "var(--text-faint)", marginTop: 4, lineHeight: 1.5 }}>
          Pay each request manually through PayPal, then press "Mark paid", that deducts
          the creator's Breeze Rods and emails them a confirmation.
        </div>
      </div>
    </div>
  );
}

export default function OwnerAdminPage({
  profile, session, userRole, isOwner,
  creatorStats, creatorCapes, setPage,
  setShowAdminCapeModal, setShowCosmeticUploadModal,
  token, notify,
  featureFlags, setFeatureFlags,
}) {
  const creatorSharePct = profile?.creator_share_percent != null ? Number(profile.creator_share_percent).toFixed(0) : null;
  const creatorCapeCount = typeof creatorStats?.capes_created === "number" ? creatorStats.capes_created : (creatorCapes?.length || 0);
  const creatorAtLimit = isCreatorTier(userRole) && creatorCapeCount >= CREATOR_UPLOAD_LIMIT;

  return (
    <div className="sv page-enter">
      <div className="vtl" style={{ display: "flex", alignItems: "center", gap: 10 }}>
        <I.Shield />
        {isOwner ? "Owner Panel" : "Creator Dashboard"}
        <span className="admin-badge">{userRole}</span>
      </div>

      {/* Stats */}
      <div className="creator-stats-row">
        {[
          { label: "Total Sales",    value: creatorStats?.total_sales?.toLocaleString() ?? "N/A" },
          { label: "Gross Revenue",  value: creatorStats?.total_gross_usd != null ? `$${Number(creatorStats.total_gross_usd).toFixed(2)}` : "N/A" },
          { label: "Your Share",     value: creatorSharePct != null ? `${creatorSharePct}%` : "N/A" },
          { label: "Capes Listed",   value: isCreatorTier(userRole) ? `${creatorCapeCount} / ${CREATOR_UPLOAD_LIMIT}` : String(creatorCapeCount) },
        ].map((stat) => (
          <div key={stat.label} className="creator-stat-card">
            <div className="creator-stat-val">{stat.value}</div>
            <div className="creator-stat-lbl">{stat.label}</div>
          </div>
        ))}
      </div>

      {/* Breeze Rod earnings + withdrawals (creators, admins, owner) */}
      <CreatorEarnings token={token} notify={notify} profile={profile} />

      {/* Owner-only: payout queue + feature toggles */}
      {isOwner && <WithdrawalQueue token={token} notify={notify} />}
      {isOwner && (
        <FeatureToggles token={token} notify={notify} featureFlags={featureFlags} setFeatureFlags={setFeatureFlags} />
      )}

      {/* Cape Management */}
      <div className="sg">
        <div className="sgl">Cape Management</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">List New Cape</div>
              <div className="sd">Upload to marketplace{isCreatorTier(userRole) ? ` · ${creatorCapeCount}/${CREATOR_UPLOAD_LIMIT} slots` : ""}</div>
            </div>
            <button className="btn accent" onClick={() => setShowAdminCapeModal?.(true)} disabled={creatorAtLimit}>
              <I.Upload /> New Cape
            </button>
          </div>
          <div className="sr">
            <div className="si"><div className="sn">Your Listed Capes</div><div className="sd">{creatorCapeCount} cape{creatorCapeCount !== 1 ? "s" : ""} in marketplace</div></div>
            <button className="btn" onClick={() => setPage?.("store")}><I.Store /> View Store</button>
          </div>
        </div>
      </div>

      {/* Cosmetics */}
      <div className="sg">
        <div className="sgl">Cosmetics Management</div>
        <div className="scd">
          <div className="sr">
            <div className="si">
              <div className="sn">Upload Cosmetic</div>
              <div className="sd">Hats · Wings · Pets · Auras · Shields · Back · Trail (.glb/.gltf)</div>
            </div>
            <button className="btn accent" onClick={() => setShowCosmeticUploadModal?.(true)} disabled={creatorAtLimit && isCreatorTier(userRole)}>
              <I.Upload /> New Cosmetic
            </button>
          </div>
          <div className="sr">
            <div className="si"><div className="sn">Browse Catalogue</div></div>
            <button className="btn" onClick={() => setPage?.("custom")}><I.Palette /> Open Wardrobe</button>
          </div>
        </div>
      </div>

      {/* Listed capes */}
      {creatorCapes?.length > 0 && (
        <div className="sg">
          <div className="sgl">Your Capes</div>
          <div className="scd">
            {creatorCapes.map((cape) => (
              <div key={cape.id} className="sr">
                <div style={{ width: 36, height: 36, borderRadius: 8, overflow: "hidden", flexShrink: 0, background: "var(--surface)", border: "1px solid var(--border)", display: "flex", alignItems: "center", justifyContent: "center" }}>
                  {cape.image_url ? <img src={cape.image_url} alt="" style={{ width: "100%", height: "100%", objectFit: "contain" }} /> : <I.Palette />}
                </div>
                <div className="si">
                  <div className="sn">{cape.name}</div>
                  <div className="sd"><span className={`badge ${cape.rarity || "premium"}`} style={{ marginRight: 4 }}>{cape.rarity || "premium"}</span>{Number(cape.price_usd ?? 0) === 0 ? "Free" : `${(Number(cape.price_usd) * 64).toLocaleString()} WC`} · {cape.is_public ? "Public" : "Hidden"}</div>
                </div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
