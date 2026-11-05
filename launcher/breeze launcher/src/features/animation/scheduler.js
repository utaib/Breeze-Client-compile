import { samplePose, clipDone } from "./keyframes.js";

/**
 * Decides when the player on the stage does something: now and then it plays
 * one of its moments (never the same one twice in a row), fading in and out
 * so it blends with the idle pose. Nothing new starts while the player is
 * being turned, but a moment already playing plays on, so it can be watched
 * from any side (owner, 1.1.0_test_3: turning the player stopped the cape
 * check he wanted to look at). Pure: time comes in through update(), so it
 * is tested without a browser.
 */
export class MomentScheduler {
  constructor(clips, { random = Math.random, firstDelay = [5, 9], gap = [14, 28], fade = 0.3 } = {}) {
    // Moments have an end; a looping clip would never hand back.
    this.clips = [...clips].filter((c) => c.loop !== true && c.length > 0);
    this.random = random;
    this.firstDelay = firstDelay;
    this.gap = gap;
    this.fade = fade;
    this.wait = this.between(firstDelay);
    this.current = null;
    this.elapsed = 0;
    this.last = null;
    /** Seconds into an early fade-out, or null. */
    this.fadingOut = null;
  }

  between([lo, hi]) {
    return lo + (hi - lo) * this.random();
  }

  /**
   * Start a moment now (by name), or a random one. Returns its name or null.
   * `requested`: the player asked for it, so it plays to its end even with
   * the random moments switched off.
   */
  play(name = null, { requested = false } = {}) {
    const pool = this.clips.filter((c) => (name ? c.name === name : c.name !== this.last));
    const clip = pool.length ? pool[Math.floor(this.random() * pool.length) % pool.length] : null;
    if (!clip) return null;
    this.current = clip;
    this.elapsed = 0;
    this.fadingOut = null;
    this.last = clip.name;
    this.requested = Boolean(requested && name);
    return clip.name;
  }

  /**
   * Fade the moment out early (the player started turning it, say). The
   * moment keeps its own timing while it fades, so nothing jumps: skipping to
   * its end would drop an arm that is up in one frame.
   */
  stop() {
    if (this.current && this.fadingOut === null) this.fadingOut = 0;
  }

  weightAt(t) {
    if (!this.current) return 0;
    const f = Math.max(1e-3, this.fade);
    const own = Math.max(0, Math.min(1, t / f, (this.current.length - t) / f));
    return this.fadingOut === null ? own : own * Math.max(0, 1 - this.fadingOut / f);
  }

  /**
   * Advance by dt seconds. `allowed` is false when random moments are
   * switched off or the system asks for reduced motion; a moment asked for by
   * name still finishes. `still` (reduced motion) stops everything. `busy`
   * (or `interrupt`, the player being turned): no new moment starts, but one
   * already playing plays on to its end. Returns { name, pose, weight }
   * while a moment plays, otherwise null.
   */
  update(dt, { allowed = true, busy = false, interrupt = false, still = false } = {}) {
    const step = Math.min(0.25, Math.max(0, Number(dt) || 0));
    if (still || (!allowed && !(this.current && this.requested))) {
      this.current = null;
      this.wait = Math.max(this.wait, this.between(this.firstDelay));
      return null;
    }
    if (this.current) {
      this.elapsed += step;
      if (this.fadingOut !== null) this.fadingOut += step;
      if (clipDone(this.current, this.elapsed) || (this.fadingOut !== null && this.fadingOut >= this.fade)) {
        this.current = null;
        this.wait = this.between(this.gap);
        return null;
      }
      return { name: this.current.name, pose: samplePose(this.current, this.elapsed), weight: this.weightAt(this.elapsed) };
    }
    if (!allowed) return null;
    if (busy || interrupt) {
      this.wait = Math.max(this.wait, 2);
      return null;
    }
    this.wait -= step;
    if (this.wait > 0 || !this.play()) return null;
    return { name: this.current.name, pose: samplePose(this.current, 0), weight: 0 };
  }
}
