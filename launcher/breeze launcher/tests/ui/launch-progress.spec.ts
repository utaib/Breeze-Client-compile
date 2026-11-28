import { test, expect } from "@playwright/test";
import { normalizeLaunchProgress } from "../../src/launchProgress.js";

test("normalizes fractional backend progress to percentage values", () => {
  expect(normalizeLaunchProgress(0.265)).toBe(26.5);
  expect(normalizeLaunchProgress(0.2626)).toBe(26.3);
  expect(normalizeLaunchProgress(0)).toBe(0);
  expect(normalizeLaunchProgress(1)).toBe(100);
});

test("preserves whole-number stage percentages", () => {
  expect(normalizeLaunchProgress(5)).toBe(5);
  expect(normalizeLaunchProgress(95)).toBe(95);
  expect(normalizeLaunchProgress(100)).toBe(100);
});

test("handles missing, invalid, and out-of-range values", () => {
  expect(normalizeLaunchProgress(undefined)).toBeUndefined();
  expect(normalizeLaunchProgress(null)).toBeUndefined();
  expect(normalizeLaunchProgress(Number.NaN)).toBeUndefined();
  expect(normalizeLaunchProgress(Number.POSITIVE_INFINITY)).toBeUndefined();
  expect(normalizeLaunchProgress(-4)).toBe(0);
  expect(normalizeLaunchProgress(120)).toBe(100);
});
