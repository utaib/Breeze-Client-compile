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
 */
export default function SpotifyPanel({ token, notify, onUseFree, isAdmin = false }) {
  const [status, setStatus] = useState(null);
  const [now, setNow] = useState(null);
  const [busy, setBusy] = useState(false);
  const [premiumBlocked, setPremiumBlocked] = useState(false);
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
      else notify?.("!", e?.message || "Spotify would not accept that");
    }
  };

  if (!status) return <div className="empty-panel">Checking Spotify…</div>;

  if (!status.configured) {
    return (
      <div className="panel">
        <div className="panel-title">Spotify</div>
        <div className="sd">This isn&apos;t implemented yet. I&apos;ll implement it later.</div>
        {/* Only the account owner can act on this, and only they will recognise
            it as a deployment detail rather than a fault. Everyone else sees
            the plain line above. */}
        {status.missing?.length > 0 && isAdmin && (
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

  return (
    <div className="panel spotify-panel">
      <div className="panel-title">
        Spotify
        {status.displayName && <span className="spotify-who">{status.displayName}</span>}
      </div>

      {/* The Premium wall. Shown only after a control was actually refused, so a
          Premium user never sees it, and it offers the two real choices rather
          than telling the user to go and fix something. */}
      {(premiumBlocked || !status.premium) && (
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

      {status.premium && (
        <div className="spotify-controls">
          <button className="mini-btn" onClick={() => control("previous")} title="Previous"><I.SkipBack /></button>
          <button className="fm-play" onClick={() => control(now?.playing ? "pause" : "play")}>
            {now?.playing ? <I.Pause /> : <I.Play />}
          </button>
          <button className="mini-btn" onClick={() => control("next")} title="Next"><I.SkipForward /></button>
        </div>
      )}

      {status.premium && (
        <button className="btn ghost spotify-disconnect" onClick={disconnect} disabled={busy}>
          Disconnect Spotify
        </button>
      )}
    </div>
  );
}
