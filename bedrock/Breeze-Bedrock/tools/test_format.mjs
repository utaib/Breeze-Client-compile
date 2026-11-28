// Unit tests for the Breeze HUD's pure helpers: node --test tools/test_format.mjs
import { test } from "node:test";
import assert from "node:assert/strict";
import { clock, defaultSettings, duration, effectName, facing, parseSettings, PARTS } from "../packs/breeze_hud/scripts/format.js";

test("facing follows Bedrock yaw (0 is south, 90 west)", () => {
  assert.equal(facing(0), "S");
  assert.equal(facing(90), "W");
  assert.equal(facing(180), "N");
  assert.equal(facing(-180), "N");
  assert.equal(facing(-90), "E");
  assert.equal(facing(-45), "SE");
  assert.equal(facing(359), "S");
});

test("clock: tick 0 is 06:00, 6000 noon, 18000 midnight", () => {
  assert.equal(clock(0), "06:00");
  assert.equal(clock(6000), "12:00");
  assert.equal(clock(18000), "00:00");
  assert.equal(clock(500), "06:30");
  assert.equal(clock(23999), "05:59");
});

test("duration in minutes and seconds", () => {
  assert.equal(duration(0), "0:00");
  assert.equal(duration(20 * 65), "1:05");
  assert.equal(duration(1), "0:01");
  assert.equal(duration(-5), "0:00");
});

test("effect names with levels", () => {
  assert.equal(effectName("minecraft:speed", 0), "Speed");
  assert.equal(effectName("minecraft:fire_resistance", 1), "Fire Resistance II");
  assert.equal(effectName("minecraft:haste", 14), "Haste 15");
});

test("settings: defaults, round trip and broken input", () => {
  const d = defaultSettings();
  assert.equal(d.enabled, true);
  assert.equal(Object.keys(d.parts).length, PARTS.length);
  assert.deepEqual(parseSettings(JSON.stringify(d)), d);
  assert.deepEqual(parseSettings("{not json"), d);
  assert.deepEqual(parseSettings(undefined), d);
  const s = parseSettings(JSON.stringify({ enabled: false, parts: { coords: false, unknown: true, clock: "yes" } }));
  assert.equal(s.enabled, false);
  assert.equal(s.parts.coords, false);
  assert.equal(s.parts.clock, true);
  assert.equal("unknown" in s.parts, false);
});
