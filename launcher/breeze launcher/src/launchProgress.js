/**
 * Convert launch-event progress to the UI's percentage contract (0..100).
 * Rust reports fractions; stage fallbacks are already percentages.
 */
export function normalizeLaunchProgress(value) {
  if (typeof value !== "number" || !Number.isFinite(value)) return undefined;
  const percentage = value >= 0 && value <= 1 ? value * 100 : value;
  return Math.round(Math.max(0, Math.min(100, percentage)) * 10) / 10;
}
