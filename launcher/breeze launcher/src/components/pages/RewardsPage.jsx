import { useEffect, useState } from "react";
import { I } from "../../ui/icons";
import { AdFrame } from "../Shared";

const STEPS = [
  { ads: 2, discount: 5 },
  { ads: 4, discount: 10 },
  { ads: 6, discount: 20 },
];

const MAX_ADS = 6;
const AD_WATCH_SECONDS = 15;

/** Full-screen ad viewing modal: shows a real ad frame, counts down, and only
 *  credits progress once the countdown finishes. Closing early warns that the
 *  current ad won't count. */
function AdWatchModal({ adIndex, onComplete, onClose }) {
  const [countdown, setCountdown] = useState(AD_WATCH_SECONDS);
  const [confirmLeave, setConfirmLeave] = useState(false);
  const done = countdown <= 0;

  useEffect(() => {
    if (done) return;
    const timer = setTimeout(() => setCountdown((current) => current - 1), 1000);
    return () => clearTimeout(timer);
  }, [countdown, done]);

  const requestClose = () => {
    if (done) return onClose();
    setConfirmLeave(true);
  };

  return (
    <div className="modal-backdrop" onClick={requestClose}>
      <div className="modal" style={{ maxWidth: 460 }} onClick={(event) => event.stopPropagation()}>
        <div className="modal-body">
          <div className="modal-header">
            <div>
              <div className="modal-title">Watching ad {adIndex} of {MAX_ADS}</div>
              <div className="modal-desc" style={{ fontSize: 11 }}>
                {done ? "Done, collect your progress below." : `Keep this open for ${countdown}s to earn progress.`}
              </div>
            </div>
            <button className="mini-btn" onClick={requestClose}><I.X /></button>
          </div>

          <AdFrame
            className="ad-frame"
            title={`Breeze reward ad ${adIndex}`}
            src={`/ads${((adIndex - 1) % 6) + 1}.html`}
            style={{ width: "100%", height: 220, borderRadius: 10, border: "1px solid var(--border)" }}
          />

          <div className="reward-progress">
            <div
              className="reward-progress-fill"
              style={{ width: `${Math.round(((AD_WATCH_SECONDS - Math.max(0, countdown)) / AD_WATCH_SECONDS) * 100)}%` }}
            />
          </div>

          {confirmLeave && !done ? (
            <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "10px 12px", borderRadius: 10, background: "rgba(245,197,66,.07)", border: "1px solid rgba(245,197,66,.18)" }}>
              <div style={{ flex: 1, fontSize: 11.5, color: "var(--text-secondary)" }}>
                Leave now and this ad won&apos;t count toward your discount.
              </div>
              <button className="btn" onClick={onClose}>Leave</button>
              <button className="btn accent" onClick={() => setConfirmLeave(false)}>Keep watching</button>
            </div>
          ) : (
            <div className="modal-actions">
              <button className="modal-close" onClick={requestClose}>{done ? "Close" : "Cancel"}</button>
              <button className="modal-buy" style={{ flex: 1 }} disabled={!done} onClick={onComplete}>
                {done ? "Collect progress" : `${countdown}s remaining…`}
              </button>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default function RewardsPage({ rewardSession, rewardDiscount, rewardCode, start, progress, claim, featureFlags, notify }) {
  const watched = rewardSession?.ads_watched || 0;
  const [showAdModal, setShowAdModal] = useState(false);
  const [starting, setStarting] = useState(false);

  if (featureFlags?.rewards_disabled) {
    return (
      <div className="sv page-enter" style={{ display: "flex", alignItems: "center", justifyContent: "center", flex: 1 }}>
        <div style={{ textAlign: "center" }}>
          <div style={{ fontSize: 13, fontWeight: 700, color: "var(--text-primary)", marginBottom: 6 }}>Rewards Unavailable</div>
          <div style={{ fontSize: 11, color: "var(--text-faint)" }}>This feature is currently under maintenance.</div>
        </div>
      </div>
    );
  }

  const beginAd = async () => {
    if (watched >= MAX_ADS) return;
    setStarting(true);
    try {
      if (!rewardSession) await start();
      setShowAdModal(true);
    } finally {
      setStarting(false);
    }
  };

  const completeAd = async () => {
    await progress();
    setShowAdModal(false);
  };

  const copyCode = () => {
    navigator.clipboard?.writeText(rewardCode).then(() => notify?.("ok", "Promo code copied")).catch(() => {});
  };

  return (
    <div className="sv page-enter">
      <div className="vtl"><I.Ticket /> Ads &amp; Rewards</div>

      <div className="rewards-compact-layout">
        {/* Left: reward progress */}
        <div className="rewards-main">
          <div className="sg">
            <div className="sgl">Discount Progress</div>
            <div className="scd">
              <div className="sr">
                <div className="si">
                  <div className="sn">Thanks for supporting Breeze</div>
                  <div className="sd">Watch optional partner ads to earn a one-time discount code.</div>
                </div>
                <div style={{ textAlign: "right", flexShrink: 0 }}>
                  <div style={{ fontSize: 28, fontWeight: 700, color: "var(--accent)", lineHeight: 1 }}>{rewardDiscount}%</div>
                  <div style={{ fontSize: 10, color: "var(--text-faint)", marginTop: 2 }}>earned</div>
                </div>
              </div>

              <div className="reward-progress" style={{ margin: "8px 0 4px" }}>
                <div className="reward-progress-fill" style={{ width: `${Math.min(100, (watched / MAX_ADS) * 100)}%` }} />
              </div>

              <div className="reward-step-grid">
                {[1, 2, 3, 4, 5, 6].map((step) => (
                  <div key={step} className={`reward-step ${watched >= step ? "done" : ""}`}>{step}</div>
                ))}
              </div>

              <div style={{ fontSize: 11, color: "var(--text-faint)", margin: "6px 0 12px" }}>
                {watched}/{MAX_ADS} ads watched, {rewardDiscount}% discount earned
              </div>

              {rewardSession && !rewardCode && (
                <div style={{ fontSize: 11, color: "var(--text-faint)", margin: "-4px 0 12px" }}>
                  Your progress is saved, come back anytime and pick up where you left off.
                </div>
              )}

              <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                <button className="btn accent" onClick={beginAd} disabled={starting || watched >= MAX_ADS || Boolean(rewardCode)}>
                  {watched >= MAX_ADS ? "All ads watched" : rewardSession ? "Watch Next Ad" : "Watch Ads for Rewards"}
                </button>
                <button className="btn" onClick={claim} disabled={!rewardSession || rewardDiscount <= 0 || Boolean(rewardCode)}>
                  Claim Code
                </button>
              </div>

              {rewardCode && (
                <div className="promo-applied-row" style={{ marginTop: 12 }}>
                  <span className="promo-tag">{rewardCode}</span>
                  <span className="promo-pct">-{rewardDiscount}%</span>
                  <button className="btn" style={{ marginLeft: "auto" }} onClick={copyCode}>Copy</button>
                </div>
              )}
            </div>
          </div>

          {/* Milestone breakdown */}
          <div className="sg">
            <div className="sgl">Reward Tiers</div>
            <div className="scd">
              {STEPS.map(({ ads, discount }) => (
                <div key={ads} className="sr">
                  <div className="si">
                    <div className="sn">{discount}% off</div>
                    <div className="sd">After watching {ads} ads</div>
                  </div>
                  {watched >= ads
                    ? <span className="badge perf">Reached</span>
                    : <span className="badge bi">{ads - watched} more</span>}
                </div>
              ))}
            </div>
          </div>
        </div>

        {/* Right: ad tiles */}
        <div className="rewards-ads-col">
          <div className="sg">
            <div className="sgl">Partner Ads</div>
            <div className="scd" style={{ padding: "4px 0 0" }}>
              <div className="ad-tile-grid compact">
                {[1, 2, 3, 4, 5, 6].map((n) => (
                  <AdFrame
                    key={n}
                    className="ad-frame ad-tile"
                    title={`Breeze reward ad ${n}`}
                    src={`/ads${n}.html`}
                  />
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>

      {showAdModal && (
        <AdWatchModal
          adIndex={Math.min(MAX_ADS, watched + 1)}
          onComplete={completeAd}
          onClose={() => setShowAdModal(false)}
        />
      )}
    </div>
  );
}
