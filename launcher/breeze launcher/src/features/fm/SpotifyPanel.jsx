import { useCallback, useEffect, useRef, useState } from "react";
import { I } from "../../ui/icons";
import {
  getSpotifyStatus,
  getSpotifyLoginUrl,
  disconnectSpotify,
  getSpotifyNowPlaying,
  spotifyControl,
  setSpotifyApp,
  clearSpotifyApp,
  looksLikeSpotifyClientId,
} from "../../services/breezeApi";
import { isRunningInTauri, openExternalUrl } from "../../services/nativeBridge";

/**
 * Spotify connection and playback.
 *
 * Spotify only permits playback control on Premium accounts. A free account can
 * still connect and everything that reads state keeps working, so this does not
 * block connecting; it explains the restriction at the point the user hits it
 * and offers the two things they can actually do about it.
 *
 * Premium is only called missing when it is known to be missing: the API says
 * premium: false (Spotify reported a free account) or a control came back
 * PREMIUM_REQUIRED. premium: null means Spotify did not say, which is what an
 * account outside the Spotify app's Development Mode allowlist looks like
 * (status.blocked NOT_REGISTERED); that gets its own message, because telling
 * a Premium player to buy Premium was the bug players reported.
 *
 * A Spotify app of the player's own (1.1.2): Spotify lets an app in
 * Development Mode serve only the five accounts its owner adds, so Breeze's
 * app cannot serve everyone. A player with Premium can create an app in their
 * own Spotify account and give Breeze its Client ID; their sign-in then goes
 * through that app (PKCE, no secret), where they are the owner. OwnSpotifyApp
 * below walks through it. status.ownApp says the account already uses one.
 */
const SPOTIFY_DASHBOARD = "https://developer.spotify.com/dashboard";

const openLink = (url) => {
  if (isRunningInTauri()) openExternalUrl(url).catch(() => {});
  else window.open(url, "_blank", "noopener,noreferrer");
};

/**
 * The three steps to a Spotify app of the player's own, and the field for its
 * Client ID. Only the Client ID is asked for: the secret is never needed.
 */
function OwnSpotifyApp({ token, redirectUri, notify, onSaved, onCancel }) {
  const [clientId, setClientId] = useState("");
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState("");
  const [copied, setCopied] = useState(false);
  const value = clientId.trim();
  const valid = looksLikeSpotifyClientId(value);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(redirectUri);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch { /* clipboard refused: the address is on screen to select */ }
  };

  const save = async () => {
    if (!valid || saving) return;
    setSaving(true);
    setProblem("");
    try {
      await setSpotifyApp(token, value);
      notify?.("ok", "Your Spotify app is saved");
      onSaved?.();
    } catch (e) {
      setProblem(e?.message || "Could not save your Spotify app.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="spotify-own" role="group" aria-label="Use your own Spotify app">
      <div className="spotify-premium-title">Use your own Spotify app</div>
      <div className="spotify-premium-body">Needs Spotify Premium. Takes about two minutes.</div>
      <ol className="spotify-steps">
        <li>
          Create an app on the Spotify dashboard.
          <button className="btn" onClick={() => openLink(SPOTIFY_DASHBOARD)}>Open the dashboard</button>
        </li>
        <li>
          Add this Redirect URI and tick Web API.
          <div className="spotify-redirect">
            <code>{redirectUri}</code>
            <button className="btn ghost" onClick={copy}>{copied ? "Copied" : "Copy"}</button>
          </div>
        </li>
        <li>
          Paste the app's Client ID (not the secret).
          <input
            className="spotify-client-id"
            type="text"
            inputMode="text"
            autoComplete="off"
            spellCheck={false}
            maxLength={64}
            placeholder="Client ID"
            aria-label="Client ID"
            aria-invalid={value.length > 0 && !valid}
            value={clientId}
            onChange={(e) => { setClientId(e.target.value); setProblem(""); }}
            onKeyDown={(e) => { if (e.key === "Enter") save(); }}
          />
          {value.length > 0 && !valid && (
            <div className="spotify-own-problem">That is not a Client ID (32 letters and digits).</div>
          )}
          {problem && <div className="spotify-own-problem">{problem}</div>}
        </li>
      </ol>
      <div className="spotify-premium-actions">
        <button className="btn accent" onClick={save} disabled={!valid || saving}>
          {saving ? "Saving…" : "Save Client ID"}
        </button>
        {onCancel && <button className="btn ghost" onClick={onCancel} disabled={saving}>Cancel</button>}
      </div>
    </div>
  );
}

export default function SpotifyPanel({ token, notify, onUseFree, isAdmin = false }) {
  const [status, setStatus] = useState(null);
  const [now, setNow] = useState(null);
  const [busy, setBusy] = useState(false);
  const [premiumBlocked, setPremiumBlocked] = useState(false);
  // Spotify refused this account outright (Development Mode allowlist).
  const [notRegistered, setNotRegistered] = useState(false);
  // True from the moment we send someone to Spotify until we see them come back
  // connected. Authorization finishes in a different application, so nothing in
  // this window knows it happened unless we go and look.
  const [awaitingConnect, setAwaitingConnect] = useState(false);
  // The "Use your own Spotify app" steps are open.
  const [ownOpen, setOwnOpen] = useState(false);
  const pollRef = useRef(null);
  const connectPollRef = useRef(null);

  const refresh = useCallback(async () => {
    if (!token) return;
    try {
      const s = await getSpotifyStatus(token);
      setStatus(s);
      if (s.connected) {
        try { setNow(await getSpotifyNowPlaying(token)); } catch { setNow(null); }
      } else {
        setNow(null);
      }
    } catch {
      // A server without Spotify configured, or an outage. Treat as unavailable
      // rather than showing an error the user cannot act on.
      setStatus({ configured: false, connected: false, premium: false });
    }
  }, [token]);

  useEffect(() => { refresh(); }, [refresh]);

  // Come back from the browser and the panel has to ask again.
  //
  // Connecting happens in the real browser, so this window learns nothing from
  // it. Without this the panel kept rendering the answer it got before the user
  // ever authorized, which is how a connection that the API had already stored
  // read as "Your Spotify connection expired" forever.
  //
  // Focus is the signal that they came back, but a Tauri window does not always
  // get a focus event when the browser hands control over, so a slow poll backs
  // it up. Both stop as soon as the account reads connected.
  useEffect(() => {
    if (!awaitingConnect) return undefined;
    if (status?.connected) {
      setAwaitingConnect(false);
      return undefined;
    }
    const check = () => { refresh(); };
    window.addEventListener("focus", check);
    document.addEventListener("visibilitychange", check);
    connectPollRef.current = setInterval(check, 3000);
    // Give up after two minutes rather than polling for the rest of the session:
    // by then they have either finished or abandoned it, and the Connect button
    // is still there either way.
    const giveUp = setTimeout(() => setAwaitingConnect(false), 120_000);
    return () => {
      window.removeEventListener("focus", check);
      document.removeEventListener("visibilitychange", check);
      clearInterval(connectPollRef.current);
      clearTimeout(giveUp);
    };
  }, [awaitingConnect, status?.connected, refresh]);

  // Poll only while connected and only while this panel is mounted. The
  // interval is cleared on unmount, so navigating away stops the requests.
  useEffect(() => {
    if (!status?.connected) return undefined;
    pollRef.current = setInterval(() => {
      getSpotifyNowPlaying(token).then(setNow).catch(() => {});
    }, 5000);
    return () => clearInterval(pollRef.current);
  }, [status?.connected, token]);

  const connect = async () => {
    setBusy(true);
    try {
      const { url } = await getSpotifyLoginUrl(token);
      // Opened in the real browser: Spotify's consent screen refuses to run in
      // an embedded webview, and the user may already be signed in there.
      if (isRunningInTauri()) await openExternalUrl(url);
      else window.open(url, "_blank", "noopener,noreferrer");
      setAwaitingConnect(true);
      notify?.("ok", "Finish connecting in your browser, then come back");
    } catch (e) {
      notify?.("!", e?.message || "Could not start Spotify sign-in");
    } finally {
      setBusy(false);
    }
  };

  const disconnect = async () => {
    setBusy(true);
    try {
      await disconnectSpotify(token);
      setPremiumBlocked(false);
      setNotRegistered(false);
      notify?.("ok", "Spotify disconnected");
      await refresh();
    } catch (e) {
      notify?.("!", e?.message || "Could not disconnect Spotify");
    } finally {
      setBusy(false);
    }
  };

  // Back to Breeze's own Spotify app (the API signs the connection out).
  const useBreezeApp = async () => {
    setBusy(true);
    try {
      await clearSpotifyApp(token);
      setPremiumBlocked(false);
      setNotRegistered(false);
      setAwaitingConnect(false);
      notify?.("ok", "Using Breeze's Spotify app again");
      await refresh();
    } catch (e) {
      notify?.("!", e?.message || "Could not change your Spotify app");
    } finally {
      setBusy(false);
    }
  };

  const ownSaved = async () => {
    setOwnOpen(false);
    setPremiumBlocked(false);
    setNotRegistered(false);
    await refresh();
  };

  const control = async (action) => {
    try {
      await spotifyControl(token, action);
      setPremiumBlocked(false);
      setTimeout(() => getSpotifyNowPlaying(token).then(setNow).catch(() => {}), 350);
    } catch (e) {
      // Branch on the machine-readable reason, not the message text.
      if (e?.reason === "PREMIUM_REQUIRED") setPremiumBlocked(true);
      else if (e?.reason === "NOT_REGISTERED") setNotRegistered(true);
      else notify?.("!", e?.message || "Spotify would not accept that");
    }
  };

  // Nothing is shown until the server answers, and nothing at all to players
  // while Spotify is not set up on the server: an unfinished feature is
  // hidden, never announced (CLAUDE.md, product text).
  if (!status) return null;

  const ownAvailable = Boolean(status.ownAppAvailable && status.redirectUri);
  const ownSteps = (cancel) => (
    <OwnSpotifyApp
      token={token}
      redirectUri={status.redirectUri}
      notify={notify}
      onSaved={ownSaved}
      onCancel={cancel}
    />
  );

  if (!status.configured && ownAvailable) {
    // Breeze's own app is not set up here, but a player's own app works.
    return (
      <div className="panel spotify-panel">
        <div className="panel-title">Spotify</div>
        {ownSteps(null)}
      </div>
    );
  }

  if (!status.configured) {
    // Only an admin can act on this, and only they will recognise it as a
    // deployment detail rather than a fault.
    if (!isAdmin) return null;
    return (
      <div className="panel">
        <div className="panel-title">Spotify</div>
        <div className="sd">Spotify is not set up on the server, so players do not see this panel.</div>
        {status.missing?.length > 0 && (
          <div className="sd spotify-missing">
            Server is missing: {status.missing.join(", ")}
          </div>
        )}
      </div>
    );
  }

  if (!status.connected) {
    return (
      <div className="panel spotify-panel">
        <div className="panel-title">Spotify</div>
        <div className="sd">
          {awaitingConnect
            ? "Waiting for you to finish in the browser…"
            : status.needsReconnect
            ? "Your Spotify connection expired. Connect again to keep using it."
            : status.ownApp
            ? "Your Spotify app is saved. Now connect Spotify."
            : "Connect Spotify to play your own music through Breeze. Playback control needs Spotify Premium."}
        </div>
        <button className="btn accent" onClick={connect} disabled={busy}>
          {busy ? "Opening Spotify…" : "Connect Spotify"}
        </button>
        {status.ownApp && (
          <div className="spotify-own-note">
            Using your own Spotify app. If Spotify refuses you, add your email under User Management in your app.
            <div className="spotify-premium-actions">
              <button className="btn ghost" onClick={() => openLink(SPOTIFY_DASHBOARD)}>Open the dashboard</button>
              <button className="btn ghost" onClick={useBreezeApp} disabled={busy}>Use Breeze's app instead</button>
            </div>
          </div>
        )}
        {!status.ownApp && ownAvailable && (ownOpen
          ? ownSteps(() => setOwnOpen(false))
          : (
            <button className="btn ghost spotify-own-open" onClick={() => setOwnOpen(true)}>
              Use your own Spotify app
            </button>
          ))}
      </div>
    );
  }

  const track = now?.track;
  const blocked = notRegistered || status.blocked === "NOT_REGISTERED";
  // Known to be free, or Spotify refused a control for Premium.
  const noPremium = !blocked && (premiumBlocked || status.premium === false);
  const canControl = !blocked && !noPremium;

  return (
    <div className="panel spotify-panel">
      <div className="panel-title">
        Spotify
        {status.displayName && <span className="spotify-who">{status.displayName}</span>}
      </div>

      {blocked && status.ownApp && (
        <div className="spotify-premium">
          <div className="spotify-premium-title">Your Spotify app has not allowed this account yet</div>
          <div className="spotify-premium-body">
            In your app's Settings, add your Spotify email under User Management.
          </div>
          <div className="spotify-premium-actions">
            <button className="btn" onClick={() => openLink(SPOTIFY_DASHBOARD)}>Open the dashboard</button>
            <button className="btn" onClick={() => { setNotRegistered(false); refresh(); }}>Check again</button>
            <button className="btn ghost" onClick={useBreezeApp} disabled={busy}>Use Breeze's app instead</button>
            <button className="btn ghost" onClick={disconnect} disabled={busy}>Disconnect</button>
          </div>
        </div>
      )}

      {blocked && !status.ownApp && (
        <div className="spotify-premium">
          <div className="spotify-premium-title">Spotify has not allowed this account yet</div>
          <div className="spotify-premium-body">
            {ownAvailable
              ? "Breeze's Spotify app only takes a few accounts. With Premium you can use your own app instead."
              : "Breeze's Spotify app only takes a few accounts, added by the Breeze team."}
          </div>
          {ownAvailable && ownOpen && ownSteps(() => setOwnOpen(false))}
          <div className="spotify-premium-actions">
            {ownAvailable && !ownOpen && (
              <button className="btn accent" onClick={() => setOwnOpen(true)}>Use your own Spotify app</button>
            )}
            <button className="btn" onClick={onUseFree}>Use Breeze FM instead</button>
            <button className="btn ghost" onClick={disconnect} disabled={busy}>Disconnect</button>
          </div>
        </div>
      )}

      {/* The Premium wall: only when Spotify said the account is free, or
          refused a control for Premium, so a Premium player never sees it. */}
      {noPremium && (
        <div className="spotify-premium">
          <div className="spotify-premium-title">Spotify Premium required</div>
          <div className="spotify-premium-body">
            Spotify only lets apps control playback on Premium accounts, so Breeze
            cannot play or skip tracks on a free account.
          </div>
          <div className="spotify-premium-actions">
            <button className="btn" onClick={() => openLink("https://www.spotify.com/premium/")}>
              Get Premium
            </button>
            <button className="btn" onClick={onUseFree}>Use Breeze FM instead</button>
            <button className="btn ghost" onClick={disconnect} disabled={busy}>Disconnect</button>
          </div>
        </div>
      )}

      {track ? (
        <div className="spotify-now">
          <div className="spotify-art">
            {track.artwork ? <img src={track.artwork} alt="" /> : <I.Music />}
          </div>
          <div className="spotify-meta">
            <div className="spotify-title">{track.title}</div>
            <div className="spotify-artist">{track.artist}</div>
            {now?.device && <div className="spotify-device">on {now.device.name}</div>}
          </div>
        </div>
      ) : (
        <div className="sd">Nothing playing on Spotify right now.</div>
      )}

      {canControl && (
        <div className="spotify-controls">
          <button className="mini-btn" onClick={() => control("previous")} title="Previous"><I.SkipBack /></button>
          <button className="fm-play" onClick={() => control(now?.playing ? "pause" : "play")}>
            {now?.playing ? <I.Pause /> : <I.Play />}
          </button>
          <button className="mini-btn" onClick={() => control("next")} title="Next"><I.SkipForward /></button>
        </div>
      )}

      {canControl && (
        <div className="spotify-foot">
          <button className="btn ghost spotify-disconnect" onClick={disconnect} disabled={busy}>
            Disconnect Spotify
          </button>
          {status.ownApp && <span className="spotify-own-tag">Through your own Spotify app</span>}
        </div>
      )}
    </div>
  );
}
