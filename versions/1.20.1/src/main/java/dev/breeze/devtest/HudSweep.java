package dev.breeze.devtest;

import com.google.gson.JsonObject;
import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.hud.HudPlacement;
import dev.breeze.modules.AbstractHudModule;
import dev.breeze.ui.HudLayout;
import net.minecraft.client.Minecraft;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The self-test's checks on every HUD module at once: each draws without
 * throwing, and each can be put anywhere on the screen, saved, read back and
 * drawn where it was put. The HUD editor's drags (driven with the real mouse)
 * cover the editor itself; this covers every element through the same
 * placement code the editor uses.
 */
final class HudSweep {

    private HudSweep() {}

    static List<AbstractHudModule> huds() {
        List<AbstractHudModule> out = new ArrayList<>();
        for (Module m : ModuleManager.getModules()) {
            if (m instanceof AbstractHudModule h) out.add(h);
        }
        return out;
    }

    /**
     * Each HUD module, switched on: drew in the last two seconds without
     * throwing; its size and place. None of them, all at their default or
     * automatic places, may cover Minecraft's hotbar and bars.
     */
    static JsonObject check() {
        Minecraft mc = Minecraft.getInstance();
        HudPlacement.Box bars = AbstractHudModule.vanillaBars(
                mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
        JsonObject o = new JsonObject();
        boolean all = true;
        int n = 0;
        List<String> onBars = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (AbstractHudModule h : huds()) {
            n++;
            boolean recent = now - h.lastDrawnAt() < 2_000;
            boolean ok = h.isEnabled() && recent && !h.drawFailed();
            all &= ok;
            o.addProperty(h.getName(), (ok ? "drawn " : h.drawFailed() ? "threw " : "not drawn ")
                    + h.getHudW() + "x" + h.getHudH() + " at " + h.getHudX() + "," + h.getHudY());
            boolean apart = h.getHudX() + h.getHudW() <= bars.x() || bars.x() + bars.w() <= h.getHudX()
                    || h.getHudY() + h.getHudH() <= bars.y() || bars.y() + bars.h() <= h.getHudY();
            if (!apart) onBars.add(h.getName());
        }
        o.addProperty("count", String.valueOf(n));
        o.addProperty("onHotbar", String.join(", ", onBars));
        o.addProperty("pass", String.valueOf(all && n > 0 && onBars.isEmpty()));
        return o;
    }

    private static final Map<String, String> SAVED = new HashMap<>();
    private static long placedAt;

    /**
     * Puts every element in its own cell of a grid over the screen, saves the
     * layout, forgets it, reads it back from disk and applies what was read.
     */
    static void place(Minecraft mc) {
        List<AbstractHudModule> list = huds();
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        int cols = 6;
        int rows = (list.size() + cols - 1) / cols;
        SAVED.clear();
        for (int i = 0; i < list.size(); i++) {
            AbstractHudModule h = list.get(i);
            int tx = (i % cols) * (sw / cols) + 2;
            int ty = (i / cols) * (sh / Math.max(1, rows)) + 2;
            h.setHudPos(tx, ty);
            HudLayout.set(h.getName(), h.getPlacement());
            SAVED.put(h.getName(), h.getPlacement() == null ? "none" : h.getPlacement().encode());
        }
        HudLayout.save();
        for (AbstractHudModule h : list) {
            h.resetPlacement();
            HudLayout.set(h.getName(), null);
        }
        HudLayout.load();
        for (AbstractHudModule h : list) h.setPlacement(HudLayout.get(h.getName()));
        placedAt = System.currentTimeMillis();
    }

    /**
     * After a few frames: each element's placement read back from disk is the
     * one saved, it is drawn where that placement puts it, and it drew since.
     */
    static JsonObject verify(Minecraft mc) {
        int sw = mc.getWindow().getGuiScaledWidth();
        int sh = mc.getWindow().getGuiScaledHeight();
        JsonObject o = new JsonObject();
        List<String> wrong = new ArrayList<>();
        int moved = 0;
        for (AbstractHudModule h : huds()) {
            HudPlacement p = h.getPlacement();
            String read = p == null ? "none" : p.encode();
            boolean persisted = read.equals(SAVED.get(h.getName())) && p != null;
            int[] at = p == null ? new int[]{-1, -1} : p.resolve(h.getHudW(), h.getHudH(), sw, sh);
            boolean placed = at[0] == h.getHudX() && at[1] == h.getHudY();
            boolean onScreen = h.getHudX() >= 0 && h.getHudY() >= 0
                    && h.getHudX() + h.getHudW() <= sw && h.getHudY() + h.getHudH() <= sh;
            boolean drew = h.lastDrawnAt() > placedAt;
            if (persisted && placed && onScreen && drew) moved++;
            else wrong.add(h.getName() + " (saved " + SAVED.get(h.getName()) + ", read " + read
                    + ", at " + h.getHudX() + "," + h.getHudY() + (drew ? "" : ", not drawn") + ")");
        }
        o.addProperty("moved", String.valueOf(moved));
        o.addProperty("wrong", String.join("; ", wrong));
        o.addProperty("pass", String.valueOf(wrong.isEmpty() && moved > 0));
        return o;
    }

    /** Everything back to its default place, saved, so the editor step starts from defaults. */
    static void restore() {
        for (AbstractHudModule h : huds()) {
            h.resetPlacement();
            HudLayout.set(h.getName(), null);
        }
        HudLayout.save();
    }
}
