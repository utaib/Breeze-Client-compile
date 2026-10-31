package dev.breeze.cosmetics;

import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.compat.Ids;
import dev.breeze.compat.Images;
import dev.breeze.compat.Resources;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom shield textures (Custom Shield): a picture put on the front of
 * Minecraft's own shield texture by ShieldArt, the player's own from the
 * breeze_shields folder, other Breeze players' from the API (the address in
 * their cosmetic state, on the API's own host only, as for capes).
 */
public final class ShieldTextures {

    /** A picture file larger than this is not read. */
    public static final int MAX_FILE = 4 * 1024 * 1024;
    private static final int MAX_DOWNLOAD = 512 * 1024;
    /** The size kept for sharing: sixteen times the shield's 12x22 face. */
    public static final int SHARE_W = 192;
    public static final int SHARE_H = 352;

    private static final ResourceLocation OWN = Ids.breeze("shield/own");
    private static int[] base;
    private static String ownKey;
    private static boolean ownReady;
    private static String ownProblem;
    private static int ownScale = 1;
    /** The file and fit last looked at, and when: the file is looked at again at most once a second. */
    private static String checkedFile;
    private static ShieldArt.Fit checkedFit;
    private static long checkedAt;
    private static final Map<String, ResourceLocation> REMOTE = new ConcurrentHashMap<>();
    private static final Set<String> LOADING = ConcurrentHashMap.newKeySet();
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    private ShieldTextures() {}

    public static Path folder(Minecraft mc) {
        return mc.gameDirectory.toPath().resolve("breeze_shields");
    }

    /** The pictures in the breeze_shields folder by name, sorted; made if missing. */
    public static List<String> pictures(Minecraft mc) {
        List<String> out = new ArrayList<>();
        try {
            Path dir = folder(mc);
            Files.createDirectories(dir);
            try (var files = Files.list(dir)) {
                files.filter(Files::isRegularFile).forEach(p -> {
                    String name = p.getFileName().toString();
                    try {
                        if (ShieldArt.cleanPictureName(name).equals(name)) out.add(name);
                    } catch (IllegalArgumentException ignored) {}
                });
            }
        } catch (Throwable ignored) {}
        out.sort(String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    /** Why the player's own picture is not on their shield, or null when it is (or none is chosen). */
    public static String ownProblem() {
        return ownProblem;
    }

    /**
     * The player's own shield texture for this picture and fit, built when
     * either (or the file) changed; null when there is no usable picture.
     * On the render thread.
     */
    public static ResourceLocation own(Minecraft mc, String file, ShieldArt.Fit fit) {
        if (file == null || file.isEmpty()) {
            ownProblem = null;
            return null;
        }
        // Drawn every frame: the file's date is read again only once a second.
        long now = System.currentTimeMillis();
        if (file.equals(checkedFile) && fit == checkedFit && now - checkedAt < 1000) return ownReady ? OWN : null;
        checkedFile = file;
        checkedFit = fit;
        checkedAt = now;
        Path path = folder(mc).resolve(file);
        long modified;
        try {
            modified = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            ownReady = false;
            ownKey = null;
            ownProblem = "The picture " + file + " is not in the breeze_shields folder.";
            return null;
        }
        String key = file + "|" + fit + "|" + modified;
        if (!key.equals(ownKey)) {
            ownKey = key;
            ownReady = false;
            try {
                BufferedImage picture = readPicture(path);
                ownScale = upload(OWN, picture, fit);
                ownReady = true;
                ownProblem = null;
            } catch (Throwable t) {
                ownProblem = t instanceof IOException ? t.getMessage() : "The picture could not be read.";
            }
        }
        return ownReady ? OWN : null;
    }

    /** Reads a picture file after checking its size, refusing files and pictures that are too large. */
    public static BufferedImage readPicture(Path path) throws IOException {
        if (Files.size(path) > MAX_FILE) throw new IOException("The picture is larger than 4 MB.");
        try (ImageInputStream in = ImageIO.createImageInputStream(path.toFile())) {
            if (in == null) throw new IOException("The picture could not be read.");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("Use a PNG or JPEG picture.");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                if (!format.equals("png") && !format.equals("jpeg") && !format.equals("jpg")) {
                    throw new IOException("Use a PNG or JPEG picture.");
                }
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > ShieldArt.MAX_SIDE || h > ShieldArt.MAX_SIDE) {
                    throw new IOException("Use a picture at most " + ShieldArt.MAX_SIDE + " pixels a side.");
                }
                BufferedImage img = reader.read(0);
                if (img == null) throw new IOException("The picture could not be read.");
                return img;
            } finally {
                reader.dispose();
            }
        }
    }

    /** The picture as shared: fitted inside 192x352, PNG. */
    public static byte[] sharePng(BufferedImage picture) throws IOException {
        double s = Math.min(1.0, Math.min(SHARE_W / (double) picture.getWidth(), SHARE_H / (double) picture.getHeight()));
        int w = Math.max(1, (int) Math.round(picture.getWidth() * s)), h = Math.max(1, (int) Math.round(picture.getHeight() * s));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(picture, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(out, "png", bytes);
        return bytes.toByteArray();
    }

    /** The front face of the shield as it would look, as a PNG (the menu's preview). */
    public static byte[] previewPng(BufferedImage picture, ShieldArt.Fit fit) throws IOException {
        int scale = ShieldArt.scaleFor(picture.getWidth(), picture.getHeight());
        int[] tex = compose(picture, fit, scale);
        int size = ShieldArt.BASE * scale;
        int fw = ShieldArt.FACE_W * scale, fh = ShieldArt.FACE_H * scale;
        BufferedImage face = new BufferedImage(fw, fh, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < fh; y++) {
            for (int x = 0; x < fw; x++) {
                face.setRGB(x, y, tex[(ShieldArt.FACE_Y * scale + y) * size + ShieldArt.FACE_X * scale + x]);
            }
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(face, "png", bytes);
        return bytes.toByteArray();
    }

    /** Another Breeze player's shield texture, downloaded once per picture; null until it is ready. */
    public static ResourceLocation remote(CosmeticState.ShieldInfo shield) {
        if (shield == null) return null;
        ResourceLocation id = REMOTE.get(shield.hash());
        if (id != null) return id;
        if (FAILED.contains(shield.hash()) || !LOADING.add(shield.hash())) return null;
        String url = CapeTextures.fetchUrl(shield.url());
        if (url == null) {
            LOADING.remove(shield.hash());
            FAILED.add(shield.hash());
            return null;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).GET().build();
            CapeTextures.http().sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, err) -> {
                byte[] body = err == null && res != null && res.statusCode() == 200 ? res.body() : null;
                Minecraft.getInstance().execute(() -> {
                    LOADING.remove(shield.hash());
                    try {
                        if (body == null || body.length > MAX_DOWNLOAD) throw new IOException("not fetched");
                        BufferedImage picture = ImageIO.read(new ByteArrayInputStream(body));
                        if (picture == null || picture.getWidth() > SHARE_W * 2 || picture.getHeight() > SHARE_H * 2) {
                            throw new IOException("not a usable picture");
                        }
                        ResourceLocation target = Ids.breeze("shield/" + shield.hash());
                        upload(target, picture, ShieldArt.Fit.COVER);
                        REMOTE.put(shield.hash(), target);
                    } catch (Throwable t) {
                        FAILED.add(shield.hash());
                    }
                });
            });
        } catch (Throwable t) {
            LOADING.remove(shield.hash());
            FAILED.add(shield.hash());
        }
        return null;
    }

    private static int[] compose(BufferedImage picture, ShieldArt.Fit fit, int scale) {
        int w = picture.getWidth(), h = picture.getHeight();
        int[] argb = picture.getRGB(0, 0, w, h, null, 0, w);
        return ShieldArt.compose(base(), argb, w, h, scale, fit);
    }

    /** The player's own texture's scale (its side is 64 times this), for the native preview. */
    public static int ownScale() {
        return ownScale;
    }

    /** Reads Minecraft's own shield texture now (on the render thread), so previews can be made off it. */
    public static void ensureBase() {
        base();
    }

    /** Uploads the texture made from the picture; its scale. */
    private static int upload(ResourceLocation id, BufferedImage picture, ShieldArt.Fit fit) {
        int scale = ShieldArt.scaleFor(picture.getWidth(), picture.getHeight());
        int[] tex = compose(picture, fit, scale);
        int size = ShieldArt.BASE * scale;
        NativeImage img = new NativeImage(size, size, true);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) Images.setPixelArgb(img, x, y, tex[y * size + x]);
        }
        Images.register(id, img);
        return scale;
    }

    /**
     * Minecraft's own shield texture without a pattern, 64x64 ARGB, from the
     * game's resources (so a resource pack's shield shows round the
     * picture); a plain wooden one if it cannot be read.
     */
    private static int[] base() {
        if (base != null) return base;
        int[] out = null;
        for (String path : new String[] {"textures/entity/shield_base_nopattern.png", "textures/entity/shield/shield_base_nopattern.png"}) {
            try {
                byte[] png = Resources.read(Ids.of("minecraft", path), 1024 * 1024);
                if (png == null) continue;
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
                if (img == null || img.getWidth() != img.getHeight() || img.getWidth() % ShieldArt.BASE != 0) continue;
                // A resource pack's larger shield: one pixel in every k.
                int k = img.getWidth() / ShieldArt.BASE;
                out = new int[ShieldArt.BASE * ShieldArt.BASE];
                for (int y = 0; y < ShieldArt.BASE; y++) {
                    for (int x = 0; x < ShieldArt.BASE; x++) out[y * ShieldArt.BASE + x] = img.getRGB(x * k, y * k);
                }
                break;
            } catch (Throwable ignored) {}
        }
        if (out == null) {
            out = new int[ShieldArt.BASE * ShieldArt.BASE];
            java.util.Arrays.fill(out, 0xFF8A6237);
        }
        base = out;
        return base;
    }
}
