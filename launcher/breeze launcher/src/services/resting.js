/**
 * Whether the launcher is resting: Minecraft is running, the player has left
 * "Rest the launcher while playing" on, and the launcher's window is not the
 * one in front. While it rests nothing in it animates, the 3D character is
 * not drawn and the game's log is kept without being drawn, so the game has
 * the graphics card and the processor to itself. Coming back to the launcher
 * wakes it at once.
 */
let resting = false;
const listeners = new Set();

export function isResting() {
  return resting;
}

export function setResting(next) {
  const value = Boolean(next);
  if (value === resting) return;
  resting = value;
  try {
    document.documentElement.classList.toggle("launcher-resting", value);
  } catch {
    // No document (a test): the listeners still hear it.
  }
  for (const listener of [...listeners]) {
    try { listener(value); } catch { /* one listener must not stop the rest */ }
  }
}

/** Calls `listener(resting)` on every change. Returns the way to stop. */
export function onResting(listener) {
  listeners.add(listener);
  return () => listeners.delete(listener);
}
