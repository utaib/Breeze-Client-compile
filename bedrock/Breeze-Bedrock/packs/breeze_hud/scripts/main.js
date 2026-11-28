// Breeze HUD: an information line in the action bar, switched part by part
// in the Breeze menu. Stable Script API only (@minecraft/server 1.11.0,
// @minecraft/server-ui 1.1.0), so it needs no experiment and runs on
// Bedrock 1.21.0 and later. Settings are kept per player in the world.

import { EntityEquippableComponent, EquipmentSlot, ItemDurabilityComponent, Player, system, world } from "@minecraft/server";
import { ActionFormData, ModalFormData } from "@minecraft/server-ui";
import { PARTS, clock, defaultSettings, duration, effectName, facing, parseSettings } from "./format.js";

const SETTINGS_KEY = "breeze:hud";
const UPDATE_TICKS = 10; // twice a second
const MENU_HOLD_TICKS = 20; // look up and crouch for one second
const LOW_DURABILITY = 0.25;

/** @param {Player} player */
function loadSettings(player) {
  return parseSettings(player.getDynamicProperty(SETTINGS_KEY));
}

/** @param {Player} player @param {import("./format.js").Settings} s */
function saveSettings(player, s) {
  player.setDynamicProperty(SETTINGS_KEY, JSON.stringify(s));
}

const SLOTS = [
  [EquipmentSlot.Head, "Helmet"],
  [EquipmentSlot.Chest, "Chestplate"],
  [EquipmentSlot.Legs, "Leggings"],
  [EquipmentSlot.Feet, "Boots"],
  [EquipmentSlot.Mainhand, "Held"],
];

/**
 * Durability lines for worn and held items. With lowOnly, only items at a
 * quarter or less are shown, in red.
 * @param {Player} player @param {boolean} lowOnly
 */
function armourLines(player, lowOnly) {
  const out = [];
  const eq = /** @type {EntityEquippableComponent | undefined} */ (
    player.getComponent(EntityEquippableComponent.componentId)
  );
  if (!eq) return out;
  for (const [slot, name] of SLOTS) {
    let item;
    try {
      item = eq.getEquipment(/** @type {EquipmentSlot} */ (slot));
    } catch {
      continue;
    }
    if (!item) continue;
    const dur = /** @type {ItemDurabilityComponent | undefined} */ (
      item.getComponent(ItemDurabilityComponent.componentId)
    );
    if (!dur || dur.maxDurability <= 0) continue;
    const left = (dur.maxDurability - dur.damage) / dur.maxDurability;
    const low = left <= LOW_DURABILITY;
    if (lowOnly && !low) continue;
    out.push(`${low ? "§c" : "§7"}${name} ${Math.round(left * 100)}%§r`);
  }
  return out;
}

/** @param {Player} player */
function effectLines(player) {
  const out = [];
  for (const e of player.getEffects()) {
    out.push(`§b${effectName(e.typeId, e.amplifier)}§r ${duration(e.duration)}`);
  }
  return out;
}

/** The whole action bar text for one player, or "" when nothing is on. */
/** @param {Player} player @param {import("./format.js").Settings} s */
function hudText(player, s) {
  const lines = [];
  const top = [];
  if (s.parts.coords) {
    const l = player.location;
    top.push(`§fXYZ§7 ${Math.floor(l.x)} ${Math.floor(l.y)} ${Math.floor(l.z)}§r`);
  }
  if (s.parts.facing) top.push(`§f${facing(player.getRotation().y)}§r`);
  if (s.parts.clock) top.push(`§f${clock(world.getTimeOfDay())}§7 day ${world.getDay() + 1}§r`);
  if (top.length) lines.push(top.join("  "));
  if (s.parts.armour) {
    const a = armourLines(player, s.lowOnly);
    if (a.length) lines.push(a.join("  "));
  }
  if (s.parts.effects) {
    const e = effectLines(player);
    if (e.length) lines.push(e.join("  "));
  }
  return lines.join("\n");
}

/** Players with the menu open, so the HUD and the hold check leave them be. */
const busy = new Set();
/** Ticks each player has spent looking up while crouching. */
const holding = new Map();

/** @param {Player} player */
async function openMenu(player) {
  if (busy.has(player.id)) return;
  busy.add(player.id);
  try {
    const s = loadSettings(player);
    const main = new ActionFormData()
      .title("Breeze")
      .body("Breeze HUD shows what you choose in the action bar.")
      .button(s.enabled ? "Turn the HUD off" : "Turn the HUD on")
      .button("Choose what it shows")
      .button("Reset to defaults")
      .button("Close");
    const r = await main.show(player);
    if (r.canceled || r.selection === undefined) return;
    if (r.selection === 0) {
      s.enabled = !s.enabled;
      saveSettings(player, s);
      if (!s.enabled) player.onScreenDisplay.setActionBar("");
    } else if (r.selection === 1) {
      const form = new ModalFormData().title("Breeze HUD");
      for (const p of PARTS) form.toggle(p.label, s.parts[p.id]);
      form.toggle("Durability only when low", s.lowOnly);
      const m = await form.show(player);
      if (m.canceled || !m.formValues) return;
      PARTS.forEach((p, i) => {
        s.parts[p.id] = Boolean(m.formValues?.[i]);
      });
      s.lowOnly = Boolean(m.formValues[PARTS.length]);
      saveSettings(player, s);
    } else if (r.selection === 2) {
      saveSettings(player, defaultSettings());
    }
  } catch (err) {
    console.warn(`[Breeze] menu: ${err}`);
  } finally {
    busy.delete(player.id);
  }
}

system.runInterval(() => {
  for (const player of world.getAllPlayers()) {
    try {
      // Look straight up and crouch for a second to open the menu.
      const lookingUp = player.getRotation().x < -80;
      if (player.isSneaking && lookingUp && !busy.has(player.id)) {
        const t = (holding.get(player.id) || 0) + UPDATE_TICKS;
        holding.set(player.id, t);
        if (t >= MENU_HOLD_TICKS) {
          holding.delete(player.id);
          openMenu(player);
          continue;
        }
      } else {
        holding.delete(player.id);
      }
      if (busy.has(player.id)) continue;
      const s = loadSettings(player);
      if (!s.enabled) continue;
      const text = hudText(player, s);
      if (text) player.onScreenDisplay.setActionBar(text);
    } catch (err) {
      console.warn(`[Breeze] hud: ${err}`);
    }
  }
}, UPDATE_TICKS);

system.afterEvents.scriptEventReceive.subscribe((ev) => {
  if (ev.id !== "breeze:menu") return;
  const who = ev.sourceEntity;
  if (who instanceof Player) openMenu(who);
});

world.afterEvents.playerSpawn.subscribe((ev) => {
  if (!ev.initialSpawn) return;
  const player = ev.player;
  if (player.getDynamicProperty(SETTINGS_KEY) === undefined) {
    saveSettings(player, defaultSettings());
    player.sendMessage("§9Breeze HUD§r is on. Look straight up and crouch for a second to open the Breeze menu.");
  }
});

world.afterEvents.playerLeave.subscribe((ev) => {
  busy.delete(ev.playerId);
  holding.delete(ev.playerId);
});
