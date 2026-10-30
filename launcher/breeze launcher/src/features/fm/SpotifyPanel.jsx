import { useCallback, useEffect, useRef, useState } from "react";
import { I } from "../../ui/icons";
import {
  getSpotifyStatus,
  getSpotifyLoginUrl,
  disconnectSpotify,
  getSpotifyNowPlaying,
  spotifyControl,
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
 */
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
            : "Connect Spotify to play your own music through Breeze. Playback control needs Spotify Premium."}
        </div>
        <button className="btn accent" onClick={connect} disabled={busy}>
          {busy ? "Opening Spotify…" : "Connect Spotify"}
        </button>
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

      {blocked && (
        <div className="spotify-premium">
          <div className="spotify-premium-title">Spotify has not allowed this account yet</div>
          <div className="spotify-premium-body">
            Breeze's Spotify app is still in Spotify's test mode, where only accounts the Breeze team adds can use it.
            Your sign-in worked; Spotify will let it play once your account is added. Premium is still needed to control music.
          </div>
          <div className="spotify-premium-actions">
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
            <button
              className="btn"
              onClick={() => {
                const url = "https://www.spotify.com/premium/";
                if (isRunningInTauri()) openExternalUrl(url).catch(() => {});
                else window.open(url, "_blank", "noopener,noreferrer");
              }}
            >
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
        <button className="btn ghost spotify-disconnect" onClick={disconnect} disabled={busy}>
          Disconnect Spotify
        </button>
      )}
    </div>
  );
}
