package dev.breeze.cape;

import dev.breeze.compat.Ids;
import com.mojang.blaze3d.platform.NativeImage;
import dev.breeze.compat.Images;
import dev.breeze.BreezeClient;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class CapeManager {

    private static final List<ResourceLocation> FRAMES = new ArrayList<>();
    private static final List<Integer> DELAYS = new ArrayList<>();
    private static long totalDuration;
    private static long lastLookup;
    private static int lastFrame;

    private CapeManager() {}

    private static final String README =
            "Put a PNG or GIF cape image in this folder, then turn on Custom Cape in Breeze.\n"
            + "It shows on your player when you have no Breeze cape equipped, and only you can see it.\n";

    /** What earlier versions wrote here. Replaced; a README the player edited is left alone. */
    private static final String OLD_README = "this should not work i don't think ??? but uhh yea";

    private static Path folder() {
        Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("breeze_capes");
        try {
            Files.createDirectories(dir);
            Path readme = dir.resolve("README.txt");
            if (!Files.exists(readme) || Files.readString(readme).trim().equals(OLD_README)) {
                Files.writeString(readme, README);
            }
        } catch (Throwable ignored) {}
        return dir;
    }

    private static boolean pending;

    public static void init() {
        folder();
        pending = true;
    }

    public static void load() {
        try {
            FRAMES.clear();
            DELAYS.clear();
            totalDuration = 0;
            Path dir = folder();
            Path gif = null;
            Path png = null;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
                for (Path p : ds) {
                    String n = p.getFileName().toString().toLowerCase();
                    if (gif == null && n.endsWith(".gif")) gif = p;
                    if (png == null && n.endsWith(".png")) png = p;
                }
            }
            if (gif != null) {
                loadGif(gif);
            } else if (png != null) {
                BufferedImage img = ImageIO.read(png.toFile());
                if (img != null) addFrame(img, 0, 1000);
            }
            if (!FRAMES.isEmpty()) {
                BreezeClient.LOGGER.info("[Breeze] Loaded cape ({} frame(s))", FRAMES.size());
            }
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] cape load failed: {}", t.toString());
        }
    }

    private static void loadGif(Path file) throws Exception {
        try (InputStream in = Files.newInputStream(file);
             ImageInputStream iis = ImageIO.createImageInputStream(in)) {
            ImageReader reader = ImageIO.getImageReaders(iis).next();
            reader.setInput(iis, false);
            int count = Math.min(reader.getNumImages(true), 100);
            BufferedImage canvas = null;
            Graphics2D g2 = null;
            for (int i = 0; i < count; i++) {
                BufferedImage frame = reader.read(i);
                int x = 0;
                int y = 0;
                int delay = 100;
                try {
                    IIOMetadata meta = reader.getImageMetadata(i);
                    Node root = meta.getAsTree("javax_imageio_gif_image");
                    for (Node c = root.getFirstChild(); c != null; c = c.getNextSibling()) {
                        NamedNodeMap a = c.getAttributes();
                        if ("ImageDescriptor".equalsIgnoreCase(c.getNodeName()) && a != null) {
                            x = Integer.parseInt(a.getNamedItem("imageLeftPosition").getNodeValue());
                            y = Integer.parseInt(a.getNamedItem("imageTopPosition").getNodeValue());
                        }
                        if ("GraphicControlExtension".equalsIgnoreCase(c.getNodeName()) && a != null) {
                            delay = Math.max(20, Integer.parseInt(a.getNamedItem("delayTime").getNodeValue()) * 10);
                        }
                    }
                } catch (Throwable ignored) {}
                if (canvas == null) {
                    canvas = new BufferedImage(x + frame.getWidth(), y + frame.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    g2 = canvas.createGraphics();
                }
                g2.drawImage(frame, x, y, null);
                BufferedImage snap = new BufferedImage(canvas.getWidth(), canvas.getHeight(), BufferedImage.TYPE_INT_ARGB);
                snap.createGraphics().drawImage(canvas, 0, 0, null);
                addFrame(snap, i, delay);
            }
            if (g2 != null) g2.dispose();
            reader.dispose();
        }
    }

    private static void addFrame(BufferedImage bi, int index, int delayMs) {
        int w = bi.getWidth();
        int h = bi.getHeight();
        NativeImage img = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                Images.setPixelArgb(img, x, y, bi.getRGB(x, y));
            }
        }
        ResourceLocation id = Ids.breeze("capes/frame_" + index);
        Images.register(id, img);
        FRAMES.add(id);
        DELAYS.add(delayMs);
        totalDuration += delayMs;
    }

    public static ResourceLocation current() {
        if (pending) {
            pending = false;
            load();
        }
        if (FRAMES.isEmpty()) return null;
        if (FRAMES.size() == 1) return FRAMES.get(0);
        long now = System.currentTimeMillis();
        if (now - lastLookup > 15) {
            lastLookup = now;
            long t = now % Math.max(1, totalDuration);
            int idx = 0;
            for (int i = 0; i < FRAMES.size(); i++) {
                t -= DELAYS.get(i);
                if (t < 0) {
                    idx = i;
                    break;
                }
            }
            lastFrame = idx;
        }
        return FRAMES.get(Math.min(lastFrame, FRAMES.size() - 1));
    }
}
