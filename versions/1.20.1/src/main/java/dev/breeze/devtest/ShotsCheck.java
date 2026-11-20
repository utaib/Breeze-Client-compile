package dev.breeze.devtest;

import dev.breeze.Module;
import dev.breeze.ModuleManager;
import dev.breeze.compat.ActiveScreen;
import dev.breeze.menu.ScreenshotsScreen;
import dev.breeze.shots.ScreenshotChat;
import dev.breeze.shots.ShotLinks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Screenshot actions in a real game (1.2.3): Breeze's screenshot key saves a
 * picture; chat must say "Screenshot saved: name" with View, Folder and Copy.
 * The chat screen is opened and the buttons' places published, and the
 * driver (drive-world.sh) clicks View with the real mouse: the Screenshots
 * page must open on that picture. The driver then presses Copy (and reads the
 * clipboard back with xclip), Delete and its confirmation, and Escape twice:
 * back to the grid, then back to the game, never further.
 *
 * Every step is logged (shots-*); the driver judges the clipboard and the
 * file, this class the chat line and the page's states.
 */
final class ShotsCheck {

    private static int phase;
    private static long phaseAt;
    private static volatile Component line;
    private static String name = "";
    private static String lastState = "";
    private static boolean sawPage;
    private static final List<String> states = new ArrayList<>();
    private static final List<String> events = new ArrayList<>();

    private ShotsCheck() {}

    /** Every chat line (AutoTest.chat): keeps Breeze's screenshot line. */
    static void chat(Component c) {
        if (c != null && c.getString().startsWith("Screenshot saved: ")) line = c;
        if (c != null && c.getString().startsWith("Recording saved: ")) recordingLine = c;
    }

    /** The recorder's "Recording saved" line, when one came (RecorderCheck). */
    static volatile Component recordingLine;

    static boolean start(Minecraft mc) {
        Module m = null;
        for (Module x : ModuleManager.getModules()) if (x.getName().equals("Screenshots")) m = x;
        if (m == null || mc.player == null) {
            AutoTest.report("shots-check", "pass", "false", "why", "no Screenshots module or player");
            return false;
        }
        ScreenshotChat.onEvent = e -> {
            synchronized (events) {
                events.add(e);
            }
            AutoTest.report("shots-event", "what", e);
        };
        line = null;
        phase = 0;
        phaseAt = System.currentTimeMillis();
        return true;
    }

    private static void next(int p) {
        phase = p;
        phaseAt = System.currentTimeMillis();
    }

    /** True when the check is over. */
    static boolean step(Minecraft mc, long age) {
        long inPhase = System.currentTimeMillis() - phaseAt;
        switch (phase) {
            case 0 -> {
                if (inPhase < 1_000) return false;
                // Breeze's own screenshot key, as a player presses it (the Screenshots module's action).
                for (Module x : ModuleManager.getModules()) if (x.getName().equals("Screenshots")) x.setEnabled(true);
                AutoTest.report("shots-taken");
                next(1);
            }
            case 1 -> {
                Component c = line;
                if (c == null) {
                    if (inPhase > 15_000) return finish(mc, "no Screenshot saved line in chat");
                    return false;
                }
                Map<String, String> buttons = new LinkedHashMap<>();
                collect(c, buttons);
                String text = c.getString();
                name = text.substring("Screenshot saved: ".length()).split(" ")[0];
                boolean ok = text.contains("[View]") && text.contains("[Folder]") && text.contains("[Copy]")
                        && buttons.size() == 3 && buttons.values().stream().allMatch(n -> n.equals(name));
                AutoTest.report("shots-line", "text", text, "name", name, "buttons", buttons.toString(),
                        "file", ScreenshotChat.dir().resolve(name).toAbsolutePath().toString(), "pass", String.valueOf(ok));
                if (!ok) return finish(mc, "the chat line or its buttons are wrong");
                ChatProbe.openChat(mc);
                next(2);
            }
            case 2 -> {
                if (inPhase < 800) return false;
                Map<String, int[]> at = find(mc);
                for (Map.Entry<String, int[]> e : at.entrySet()) {
                    Targets.put("chat-shot-" + e.getKey(), e.getValue()[0], e.getValue()[1]);
                }
                AutoTest.report("shots-chat-ready", "found", String.join(",", at.keySet()));
                if (!at.containsKey("view")) return finish(mc, "the View button was not found on the chat screen");
                next(3);
            }
            default -> {
                Screen s = ActiveScreen.get(mc);
                String state = s instanceof ScreenshotsScreen p ? p.state() : s == null ? "game" : s.getClass().getSimpleName();
                if (!state.equals(lastState)) {
                    lastState = state;
                    states.add(state);
                    AutoTest.report("shots-state", "state", state);
                }
                if (s instanceof ScreenshotsScreen) sawPage = true;
                if (sawPage && s == null) return finish(mc, null);
                if (inPhase > 120_000) return finish(mc, "the driver did not finish within two minutes");
            }
        }
        return false;
    }

    /** Where View, Folder and Copy are on the open chat screen, in GUI units, found by asking the chat at each point. */
    private static Map<String, int[]> find(Minecraft mc) {
        Map<String, int[]> at = new LinkedHashMap<>();
        int h = mc.getWindow().getGuiScaledHeight();
        int w = mc.getWindow().getGuiScaledWidth();
        for (int y = h - 14; y > Math.max(0, h - 140); y -= 2) {
            for (int x = 2; x < Math.min(w, 400); x += 2) {
                Style st = ChatProbe.styleAt(mc, x, y);
                ShotLinks.Link l = st == null ? null : ShotLinks.decode(st.getInsertion());
                if (l == null) continue;
                String key = l.action().name().toLowerCase(java.util.Locale.ROOT);
                // The middle of the word: first hit plus a few pixels.
                if (!at.containsKey(key)) at.put(key, new int[]{x + 6, y});
            }
        }
        return at;
    }

    private static void collect(Component c, Map<String, String> out) {
        ShotLinks.Link l = ShotLinks.decode(c.getStyle().getInsertion());
        if (l != null) out.put(l.action().name(), l.name());
        for (Component s : c.getSiblings()) collect(s, out);
    }

    private static boolean finish(Minecraft mc, String problem) {
        ScreenshotChat.onEvent = null;
        String ev;
        synchronized (events) {
            ev = String.join(" | ", events);
        }
        boolean viewed = states.stream().anyMatch(s -> s.startsWith("large " + name));
        boolean grid = states.stream().anyMatch(s -> s.startsWith("grid"));
        boolean pass = problem == null && viewed && grid && "game".equals(lastState);
        AutoTest.report("shots-check", "name", name, "states", String.join(" > ", states), "events", ev,
                "problem", problem == null ? "" : problem, "pass", String.valueOf(pass));
        if (ActiveScreen.get(mc) instanceof ChatScreen || ActiveScreen.get(mc) instanceof ScreenshotsScreen) {
            ActiveScreen.set(mc, null);
        }
        return true;
    }
}
