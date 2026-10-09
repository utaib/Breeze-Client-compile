// Compact Breeze FM popout anchored to the topbar. Shares playback state with
// BreezeApp (same audio element as the full FM page), so switching between the
// popout and the FM page never interrupts the music.
export function RadioPopout(props) {
  const { I } = props;
  return (
    <div className="radio-popout page-enter">
      <div className="panel-head">
        <div>
          <div className="panel-title">Breeze FM</div>
          <div className="panel-sub">{props.activeTrack ? `${props.activeTrack.title} · ${props.activeTrack.artist}` : "Search to discover music"}</div>
        </div>
        <button className="mini-btn" onClick={props.onClose}><I.X /></button>
      </div>
      <RadioControls {...props} compact />
    </div>
  );
}

function RadioControls({ I, activeTrack, tracks, query, setQuery, setActive, playing, setPlaying, liked, setLiked, compact, progress = 0, duration = 0, volume = 0.65, setVolume, onSeek }) {
  const fmt = (seconds) => {
    const safe = Number.isFinite(seconds) ? Math.max(0, Math.floor(seconds)) : 0;
    return `${Math.floor(safe / 60)}:${String(safe % 60).padStart(2, "0")}`;
  };
  const canPlay = Boolean(activeTrack?.previewUrl);
  const currentIndex = tracks.findIndex((track) => track.id === activeTrack?.id);
  const jump = (offset) => {
    if (!tracks.length) return;
    const nextIndex = currentIndex < 0 ? 0 : (currentIndex + offset + tracks.length) % tracks.length;
    setActive(tracks[nextIndex].id);
  };
  const toggleLike = () => {
    if (!activeTrack) return;
    setLiked((current) => {
      const next = new Set(current);
      next.has(activeTrack.id) ? next.delete(activeTrack.id) : next.add(activeTrack.id);
      return next;
    });
  };

  return (
    <>
      <div className="sw"><I.Search /><input value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search songs, artists, albums" /></div>
      <div className={compact ? "radio-mini-list" : "radio-library"}>
        {tracks.map((track) => (
          <button key={track.id} className={`${compact ? "radio-mini-row" : "radio-library-row"} ${activeTrack?.id === track.id ? "on" : ""}`} onClick={() => setActive(track.id)}>
            {track.artworkSmall || track.artworkUrl ? <img src={track.artworkSmall || track.artworkUrl} alt="" className="radio-row-art" /> : <span className="radio-row-art placeholder"><I.Music /></span>}
            <span>{track.title}</span>
            <small>{track.length || track.artist}</small>
          </button>
        ))}
        {tracks.length === 0 && <div className="empty-panel">No Breeze FM tracks match this search.</div>}
      </div>
      <input
        className="radio-seek"
        type="range"
        min="0"
        max={Math.max(1, Math.floor(duration || 1))}
        value={Math.min(Math.floor(progress || 0), Math.max(1, Math.floor(duration || 1)))}
        onChange={(event) => onSeek?.(Number(event.target.value))}
        disabled={!canPlay || !duration}
      />
      <div className="radio-time-row"><span>{fmt(progress)}</span><span>{canPlay ? fmt(duration) : "Preview unavailable"}</span></div>
      <div className="radio-controls">
        <button className="btn" onClick={() => jump(-1)} disabled={!tracks.length}>Prev</button>
        <button className="btn accent" onClick={() => canPlay && setPlaying((current) => !current)} disabled={!canPlay}><I.Play /> {playing ? "Pause" : "Play"}</button>
        <button className="btn" onClick={() => jump(1)} disabled={!tracks.length}>Next</button>
        <button className="btn" onClick={toggleLike} disabled={!activeTrack}><I.Check /> {activeTrack && liked.has(activeTrack.id) ? "Liked" : "Like"}</button>
        <label className="radio-volume"><I.Volume /><input type="range" min="0" max="1" step="0.01" value={volume} onChange={(event) => setVolume?.(Number(event.target.value))} /></label>
      </div>
    </>
  );
}
