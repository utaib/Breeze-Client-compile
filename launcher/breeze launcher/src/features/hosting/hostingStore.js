import { listLocalServers } from "../../services/nativeBridge";

/**
 * How many local servers are running, for the badge on the Hosting tab. A
 * server uses the computer until it is stopped, so the launcher shows it on
 * every page, not only on Hosting. While one runs the count is checked every
 * 10 s (a server that crashed or was stopped from its own console drops off).
 */
let running = 0;
let timer = 0;
const listeners = new Set();

function set(next) {
  const n = Math.max(0, Number(next) || 0);
  if (n === running) return;
  running = n;
  for (const fn of listeners) fn();
  if (running > 0 && !timer) timer = window.setInterval(refreshRunning, 10000);
  if (running === 0 && timer) { window.clearInterval(timer); timer = 0; }
}

export function reportServers(servers) {
  set((servers || []).filter((s) => s.status !== "stopped").length);
}

export async function refreshRunning() {
  try {
    reportServers((await listLocalServers()).servers);
  } catch {
    /* outside the desktop app there are no servers */
  }
}

export function subscribeRunning(fn) {
  listeners.add(fn);
  return () => listeners.delete(fn);
}

export const getRunning = () => running;
