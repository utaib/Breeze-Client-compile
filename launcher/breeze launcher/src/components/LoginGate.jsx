import { I, CRAFATAR_AVATAR } from "../ui/icons";
import { TERMS_URL, PRIVACY_URL } from "../ui/urls";
import { isRunningInTauri, openExternalUrl } from "../services/nativeBridge";

// Blocks the entire launcher UI until a Microsoft/Minecraft session exists.
// Rendered by BreezeApp.jsx whenever session === null.
export default function LoginGate({ onLogin, authMessage, authProgress, accounts, onSwitchAccount, switching, busy }) {
  const openLink = (url) => {
    if (isRunningInTauri()) openExternalUrl(url).catch(() => {});
    else window.open(url, "_blank", "noopener,noreferrer");
  };

  return (
    <div className="login-shell page-enter">
      <div className="login-art">
        <img className="login-art-image" src="/login-artwork.png" alt="" onError={(event) => { event.currentTarget.src = "/login-artwork-placeholder.svg"; }} />
        <div className="login-art-grid" />
        <div className="login-brand-lockup">
          <div className="login-brand-mark"><I.Logo /></div>
          <div>
            <div className="login-kicker">Breeze Client</div>
            <div className="login-headline">Sign in before the launcher opens.</div>
          </div>
        </div>
      </div>
      <div className="login-card">
        {/* The mark also sits in the artwork panel, but that panel is hidden on
            narrow windows and disappears entirely if the artwork fails to load,
            which is why the branding could vanish. This copy belongs to the
            card, so it is present on every layout and every failure path. */}
        <div className="login-card-brand">
          <span className="login-card-mark"><I.Logo /></span>
          <span className="login-card-wordmark">Breeze Client</span>
        </div>

        <div className="login-card-icon"><I.Shield /></div>
        <div>
          <div className="login-title">Minecraft account required</div>
          <div className="login-sub">Sign in with the Microsoft account you play Minecraft on. Breeze then opens your games, the store and your friends.</div>
        </div>
        <button className={`login-primary${busy ? " busy" : ""}`} onClick={onLogin} disabled={busy}>
          {busy ? <><I.Spin /> Signing you in</> : <><I.User /> Sign in with Microsoft</>}
        </button>
        <div className="login-status">
          <span className={`status-dot ${busy ? "" : "online"}`} />
          <span>{authProgress?.message || authMessage}</span>
        </div>
        {accounts.length > 0 && (
          <div className="saved-account-list">
            {accounts.slice(0, 3).map((account, index) => {
              const isSwitching = switching && switching === account.uuid;
              const canResume = account.canResume !== false;
              return (
                <button
                  key={account.uuid || index}
                  className={`saved-account ${isSwitching ? "busy" : ""}`}
                  disabled={Boolean(switching)}
                  title={canResume ? `Resume ${account.username}` : "Session expired, sign in with Microsoft"}
                  onClick={() => { if (!switching) onSwitchAccount(index); }}
                >
                  <img src={CRAFATAR_AVATAR(account.uuid)} alt="" onError={(event) => { event.currentTarget.style.display = "none"; }} />
                  <span>{account.username}</span>
                  {isSwitching && <I.Spin />}
                </button>
              );
            })}
          </div>
        )}
        <div className="legal-links">
          <button onClick={() => openLink(TERMS_URL)}>Terms</button>
          <span />
          <button onClick={() => openLink(PRIVACY_URL)}>Privacy</button>
        </div>
      </div>
    </div>
  );
}
