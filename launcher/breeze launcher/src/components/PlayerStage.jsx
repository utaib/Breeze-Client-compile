import { PlayerViewer3D } from "./SkinViewer3D";

/**
 * The player shown on a small stage (the play page and the Wardrobe), in
 * place of the spinning viewer: a dark room whose floor and ceiling lines
 * meet at a point behind the player, a soft light behind them, their
 * reflection on the floor and a little wind drifting past. The player stands
 * in a three-quarter pose with a nametag, looks towards the pointer and can
 * be turned by dragging (PlayerViewer3D, mode "stage").
 *
 * The room is CSS and one small SVG, so it costs nothing while the viewer is
 * paused; the motion stops for players who ask for reduced motion.
 */
const MOTES = [
  { x: 14, d: 0, t: 9 }, { x: 28, d: 3.5, t: 11 }, { x: 41, d: 6, t: 8 },
  { x: 63, d: 1.5, t: 10 }, { x: 77, d: 4.5, t: 12 }, { x: 88, d: 7.5, t: 9.5 },
];
const GUSTS = [
  { y: 30, d: 0, t: 7 }, { y: 52, d: 2.6, t: 8.5 }, { y: 70, d: 5.1, t: 7.5 },
];

/** The room's lines, in a 100 by 100 box with the vanishing point at (50, HORIZON). */
const HORIZON = 62;
const ROOM_LINES = (() => {
  const lines = [];
  // Floor and ceiling: rays from the vanishing point to the bottom and top edges.
  for (let x = -150; x <= 250; x += 20) {
    lines.push({ x1: 50, y1: HORIZON, x2: x, y2: 100, floor: true });
    lines.push({ x1: 50, y1: HORIZON, x2: x, y2: 0, floor: false });
  }
  // Floor rows, closer together towards the horizon.
  for (let i = 1; i <= 7; i++) {
    const f = (i / 7) ** 2;
    const y = HORIZON + (100 - HORIZON) * f;
    lines.push({ x1: -200, y1: y, x2: 300, y2: y, floor: true, row: true });
  }
  return lines;
})();

export function PlayerStage({
  skinUrl, capeUrl, animationFrames, animationFps, cosmetics, onCosmeticError,
  name, width = 230, height = 320, showElytraToggle = false, interactive = true, className = "",
}) {
  return (
    <div className={`player-stage ${className}`} style={{ "--stage-w": `${width}px`, "--stage-h": `${height}px`, "--stage-horizon": `${HORIZON}%` }}>
      <div className="player-stage-room" aria-hidden="true">
        <div className="player-stage-wall" />
        <svg className="player-stage-lines" width="100%" height="100%" viewBox="0 0 100 100" preserveAspectRatio="none">
          {ROOM_LINES.map((l, i) => (
            <line key={i} x1={l.x1} y1={l.y1} x2={l.x2} y2={l.y2}
              className={l.floor ? (l.row ? "row" : "floor") : "ceiling"}
              vectorEffect="non-scaling-stroke" />
          ))}
        </svg>
        <div className="player-stage-light" />
        <div className="player-stage-horizon" />
        <div className="player-stage-spot" />
        {GUSTS.map((g, i) => (
          <span key={`g${i}`} className="player-stage-gust" style={{ top: `${g.y}%`, animationDelay: `${g.d}s`, animationDuration: `${g.t}s` }} />
        ))}
        {MOTES.map((m, i) => (
          <span key={`m${i}`} className="player-stage-mote" style={{ left: `${m.x}%`, animationDelay: `${m.d}s`, animationDuration: `${m.t}s` }} />
        ))}
      </div>
      <PlayerViewer3D
        mode="stage"
        nameTag={name || null}
        skinUrl={skinUrl}
        capeUrl={capeUrl}
        animationFrames={animationFrames}
        animationFps={animationFps}
        cosmetics={cosmetics}
        onCosmeticError={onCosmeticError}
        width={width}
        height={height}
        interactive={interactive}
        showElytraToggle={showElytraToggle}
        className="player-stage-viewer"
      />
    </div>
  );
}
