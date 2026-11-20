import { useSyncExternalStore } from "react";
import { getMotionPrefs, setMotionPrefs, subscribeMotionPrefs, prefersReducedMotion } from "./motionPrefs";

/**
 * The switch for the player's moments on the stage (looking around, checking
 * the cape, a wave, a hop, a stretch), in Config next to the theme. With the
 * system's "reduce motion" on they stay off whatever this says, and the row
 * says so.
 */
export default function MotionSettings({ notify }) {
  const prefs = useSyncExternalStore(subscribeMotionPrefs, getMotionPrefs);
  const reduced = prefersReducedMotion();
  const toggle = () => {
    if (!setMotionPrefs({ moments: !prefs.moments })) notify?.("!", "This choice could not be saved on this computer");
  };
  return (
    <div className="sr">
      <div className="si">
        <div className="sn">Character moments</div>
        <div className="sd">
          {reduced
            ? "Off while your system asks for reduced motion"
            : "Now and then your player looks around, checks the cape, waves or stretches"}
        </div>
      </div>
      <button
        type="button"
        className={`pill ${prefs.moments && !reduced ? "on" : ""}`}
        role="switch"
        aria-checked={prefs.moments && !reduced}
        aria-label="Character moments"
        disabled={reduced}
        onClick={toggle}
      />
    </div>
  );
}
