package dev.breeze.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.BreezeClient;
import dev.breeze.compat.Ids;
import dev.breeze.compat.Images;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.function.BiConsumer;

/**
 * Minecraft's wallpapers behind Breeze's title menu. The Breeze launcher puts
 * them in its home folder ({@code -Dbreeze.home}, folder {@code wallpapers});
 * without that folder the menu keeps Breeze's own background.
 *
 * <p>A wallpaper changes only when a title menu is opened, never while one is
 * on screen: each new visit (a new title screen, or a new web menu page in
 * the title context) takes the next picture in name order, and the choice is
 * remembered across restarts (config/breeze_wallpaper.json). For the native
 * title screen the next picture is decoded on a worker thread while the
 * current one is shown, so the next visit starts with it ready; the web menu
 * gets the file itself as a data: URI through app.hello, nothing decoded.
 */
public final class Wallpaper {

    private static final ResourceLocation[] SLOTS = {Ids.breeze("wallpaper/a"), Ids.breeze("wallpaper/b")};
    private static final Gson GSON = new Gson();

    private record Decoded(String name, int w, int h, int[] argb) {}

    private static boolean started;
    private static Path dir;
    private static final List<Path> files = new ArrayList<>();
    private static String last;

    // Native title screen: the texture of the visit on screen now.
    private static Object visitScreen;
    private static ResourceLocation visitTexture;
    private static int texW, texH, slot = -1;
    private static volatile Decoded ready;
    private static volatile boolean decoding;

    /** Told about each visit (self-test): the wallpaper's name and where it was shown. */
    public static volatile BiConsumer<String, String> onVisit;

    private Wallpaper() {}

    /** The folder the wallpapers are read from, or null when there is none. */
    public static synchronized Path dir() {
        start();
        return dir;
    }

    public static synchronized int count() {
        start();
        return files.size();
    }

    private static void start() {
        if (started) return;
        started = true;
        String explicit = System.getProperty("breeze.wallpapers.dir");
        String home = System.getProperty("breeze.home");
        Path d = explicit != null && !explicit.isBlank() ? Path.of(explicit)
                : home != null && !home.isBlank() ? Path.of(home).resolve("wallpapers") : null;
        files.addAll(WallpaperRotation.list(d));
        dir = files.isEmpty() ? null : d;
        last = readLast();
        BreezeClient.LOGGER.info("[Breeze] Menu wallpapers: {}", files.isEmpty() ? "none (Breeze's own background)" : files.size() + " found");
    }

    // ── Native title screen ─────────────────────────────────────────────────

    /**
     * The texture to draw behind this title screen, or null for Breeze's own
     * background. Call from the render thread each frame; a new screen object
     * is a new visit and takes the next wallpaper, the same one stays for the
     * whole visit.
     */
    public static synchronized ResourceLocation titleTexture(Object screen) {
        start();
        if (files.isEmpty()) return null;
        if (screen != visitScreen) {
            visitScreen = screen;
            // The first visit decodes here (one slow frame as the menu
            // appears); later visits find the next picture decoded already.
            Decoded d = ready;
            if (d == null && visitTexture == null) d = decode(nextFile());
            if (d != null) {
                ready = null;
                upload(d);
                remember(d.name());
                visit(d.name(), "native");
                decodeNext();
            }
        }
        return visitTexture;
    }

    public static synchronized int width() {
        return texW;
    }

    public static synchronized int height() {
        return texH;
    }

    private static void upload(Decoded d) {
        NativeImage img = new NativeImage(d.w(), d.h(), true);
        for (int y = 0; y < d.h(); y++) {
            for (int x = 0; x < d.w(); x++) Images.setPixelArgb(img, x, y, d.argb()[y * d.w() + x]);
        }
        // Two slots: the visit before keeps its texture until this one is up.
        slot = (slot + 1) % SLOTS.length;
        Images.register(SLOTS[slot], img);
        visitTexture = SLOTS[slot];
        texW = d.w();
        texH = d.h();
    }

    private static void decodeNext() {
        if (decoding || files.isEmpty()) return;
        Path next = nextFile();
        decoding = true;
        Thread t = new Thread(() -> {
            try {
                Decoded d = decode(next);
                if (d != null) ready = d;
            } finally {
                decoding = false;
            }
        }, "Breeze wallpaper");
        t.setDaemon(true);
        t.start();
    }

    private static Decoded decode(Path file) {
        if (file == null) return null;
        try {
            BufferedImage bi = ImageIO.read(file.toFile());
            if (bi == null || bi.getWidth() > 4096 || bi.getHeight() > 4096) return null;
            int w = bi.getWidth(), h = bi.getHeight();
            return new Decoded(file.getFileName().toString(), w, h, bi.getRGB(0, 0, w, h, null, 0, w));
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Wallpaper {} could not be read: {}", file.getFileName(), t.toString());
            return null;
        }
    }

    // ── Web menu ────────────────────────────────────────────────────────────

    /**
     * The next wallpaper for a web menu page opening in the title context, as
     * a data: URI, or null for the menu's own background. Each call is a new
     * visit.
     */
    public static synchronized String webVisit() {
        start();
        if (files.isEmpty()) return null;
        Path file = nextFile();
        try {
            byte[] bytes = Files.readAllBytes(file);
            String name = file.getFileName().toString();
            remember(name);
            visit(name, "web");
            return "data:" + WallpaperRotation.mime(name) + ";base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Wallpaper {} could not be read: {}", file.getFileName(), t.toString());
            return null;
        }
    }

    // ── Rotation ────────────────────────────────────────────────────────────

    /** The wallpaper after the one shown last. */
    private static Path nextFile() {
        List<String> names = new ArrayList<>();
        for (Path f : files) names.add(f.getFileName().toString());
        int i = WallpaperRotation.next(names, last);
        return i < 0 ? null : files.get(i);
    }

    private static void visit(String name, String where) {
        BiConsumer<String, String> listener = onVisit;
        if (listener != null) listener.accept(name, where);
    }

    private static Path stateFile() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("breeze_wallpaper.json");
    }

    private static String readLast() {
        try {
            Path p = stateFile();
            if (!Files.exists(p)) return null;
            JsonObject o = GSON.fromJson(Files.readString(p), JsonObject.class);
            return o != null && o.has("last") ? o.get("last").getAsString() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static void remember(String name) {
        last = name;
        try {
            Path p = stateFile();
            Files.createDirectories(p.getParent());
            JsonObject o = new JsonObject();
            o.addProperty("last", name);
            Files.writeString(p, GSON.toJson(o));
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Wallpaper choice not saved: {}", t.toString());
        }
    }
}
