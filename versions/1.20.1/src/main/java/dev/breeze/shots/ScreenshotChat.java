package dev.breeze.shots;

import dev.breeze.compat.ChatButtons;
import dev.breeze.compat.Game;
import dev.breeze.menu.ScreenshotsScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Breeze's screenshot line in chat: "Screenshot saved: name" with View
 * (Breeze's Screenshots page), Folder (the system's file manager with the
 * picture selected) and Copy (the picture on the clipboard). It replaces
 * Minecraft's own "Saved screenshot as" line, which arrives through the
 * screenshot callback (ScreenshotMixin); anything else (a failed screenshot)
 * passes through unchanged.
 */
public final class ScreenshotChat {

    private static final int BUTTON = 0x78B2FF;

    /** Told about each line Breeze writes and each button handled (self-test). */
    public static volatile Consumer<String> onEvent;

    private ScreenshotChat() {}

    /** The game's screenshots folder. */
    public static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("screenshots");
    }

    /** The screenshot callback, wrapped once (the short grab calls the named one). */
    public static Consumer<Component> wrap(Consumer<Component> done) {
        if (done == null || done instanceof Wrapped) return done;
        // File times can be a second coarse, so "written since" starts a little early.
        return new Wrapped(done, System.currentTimeMillis() - 2500);
    }

    private record Wrapped(Consumer<Component> inner, long since) implements Consumer<Component> {
        @Override
        public void accept(Component msg) {
            inner.accept(rewrite(msg, since));
        }
    }

    static Component rewrite(Component msg, long since) {
        try {
            if (msg == null) return null;
            ShotFiles.Shot s = ShotFiles.named(dir(), msg.getString(), since);
            if (s == null) return msg;
            event("line " + s.name());
            return line(s.name());
        } catch (Throwable t) {
            return msg;
        }
    }

    /** The chat line for a saved picture. */
    public static Component line(String name) {
        MutableComponent out = Component.literal("Screenshot saved: ").withStyle(ChatFormatting.GRAY);
        out.append(Component.literal(name).withStyle(ChatFormatting.WHITE));
        out.append(Component.literal("  "));
        out.append(button("View", ShotLinks.Action.VIEW, name, "Open it in Breeze's screenshots"));
        out.append(Component.literal(" "));
        out.append(button("Folder", ShotLinks.Action.FOLDER, name, "Show it in its folder"));
        out.append(Component.literal(" "));
        out.append(button("Copy", ShotLinks.Action.COPY, name, "Copy the picture, to paste it anywhere"));
        return out;
    }

    private static Component button(String label, ShotLinks.Action action, String name, String hover) {
        String link = ShotLinks.encode(action, name);
        return Component.literal("[" + label + "]").withStyle(st ->
                ChatButtons.style(st.withColor(TextColor.fromRgb(BUTTON)), link, Component.literal(hover)));
    }

    /** A click on a piece of chat text: true when it was a Breeze screenshot button (and was handled). */
    public static boolean click(Style style) {
        try {
            ShotLinks.Link link = style == null ? null : ShotLinks.decode(style.getInsertion());
            if (link == null) return false;
            act(link);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** Does what a button asks, on the render thread. */
    public static void act(ShotLinks.Link link) {
        Minecraft mc = Minecraft.getInstance();
        event(link.action().name().toLowerCase(java.util.Locale.ROOT) + " " + link.name());
        switch (link.action()) {
            case RECORDINGS -> {
                dev.breeze.compat.ActiveScreen.set(mc, new dev.breeze.menu.RecordingsScreen(null));
                return;
            }
            case WATCH, EXPORT -> {
                Path rec = recordingsDir().resolve(link.name());
                if (!java.nio.file.Files.isRegularFile(rec)) {
                    say(mc, "That recording is no longer in the recordings folder.");
                    return;
                }
                String why = link.action() == ShotLinks.Action.WATCH ? dev.breeze.recorder.RecorderHooks.watch(mc, rec)
                        : dev.breeze.recorder.RecorderHooks.export(mc, rec, 60, dev.breeze.recorder.VideoEncoder.Quality.HIGH);
                if (why != null) say(mc, why);
                return;
            }
            default -> { }
        }
        Path file = ShotFiles.resolve(dir(), link.name());
        if (file == null) {
            say(mc, "That screenshot is no longer in the screenshots folder.");
            return;
        }
        switch (link.action()) {
            case VIEW -> dev.breeze.compat.ActiveScreen.set(mc, new ScreenshotsScreen(null, link.name()));
            case FOLDER -> SystemOpen.reveal(file, r -> {
                event("folder-result " + r.ok());
                if (!r.ok()) say(mc, r.message());
            });
            case COPY -> SystemOpen.copyImage(file, r -> {
                event("copy-result " + r.ok() + " " + r.message());
                say(mc, r.message());
            });
            default -> { }
        }
    }

    /** The game's recordings folder. */
    public static Path recordingsDir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("recordings");
    }

    /** The chat line for a saved recording: Watch, Export (60 fps) and the Recordings page. */
    public static Component recordingLine(String name) {
        MutableComponent out = Component.literal("Recording saved: ").withStyle(ChatFormatting.GRAY);
        out.append(Component.literal(name).withStyle(ChatFormatting.WHITE));
        out.append(Component.literal("  "));
        out.append(button("Watch", ShotLinks.Action.WATCH, name, "Leave the world and watch it"));
        out.append(Component.literal(" "));
        out.append(button("Export", ShotLinks.Action.EXPORT, name, "Make a 60 fps video of it"));
        out.append(Component.literal(" "));
        out.append(button("Recordings", ShotLinks.Action.RECORDINGS, "all", "Every recording"));
        return out;
    }

    /** A line of Breeze's own in chat, from any thread. */
    public static void say(Minecraft mc, String text) {
        mc.execute(() -> Game.message(mc, Component.literal(text).withStyle(ChatFormatting.GRAY)));
    }

    /** Tells the self-test what happened (page actions too). */
    public static void event(String e) {
        Consumer<String> c = onEvent;
        if (c != null) {
            try {
                c.accept(e);
            } catch (Throwable ignored) {}
        }
    }
}
