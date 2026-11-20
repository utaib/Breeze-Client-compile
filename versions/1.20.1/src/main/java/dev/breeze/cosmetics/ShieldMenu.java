package dev.breeze.cosmetics;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.breeze.compat.Links;
import dev.breeze.modules.CustomShield;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Custom Shield in the menus (shield.state, shield.choose, shield.openFolder,
 * and the native ShieldScreen): the pictures in the breeze_shields folder,
 * the chosen one, and the shield's face as it will look, made by the same
 * code that makes the texture (ShieldTextures.previewPng).
 */
public final class ShieldMenu {

    /** The folder's pictures listed at most. */
    public static final int MAX_LISTED = 200;

    private static String previewKey;
    private static String previewUri;
    private static String previewProblem;

    private ShieldMenu() {}

    public static JsonObject json(Minecraft mc) {
        JsonObject o = new JsonObject();
        List<String> found = ShieldTextures.pictures(mc);
        JsonArray pictures = new JsonArray();
        for (int i = 0; i < found.size() && i < MAX_LISTED; i++) pictures.add(found.get(i));
        o.add("pictures", pictures);
        String chosen = CustomShield.picture();
        o.addProperty("picture", chosen);
        o.addProperty("fit", CustomShield.fit() == ShieldArt.Fit.CONTAIN ? CustomShield.WHOLE : CustomShield.FILL);
        o.addProperty("enabled", CustomShield.active());
        o.addProperty("sharing", CustomShield.sharing());
        o.addProperty("shareStatus", ShieldShare.status());
        preview(mc, chosen, CustomShield.fit());
        if (previewUri == null) o.add("preview", null);
        else o.addProperty("preview", previewUri);
        if (previewProblem == null) o.add("problem", null);
        else o.addProperty("problem", previewProblem);
        return o;
    }

    /** Chooses a picture from the folder ("" for none); throws with the reason when it cannot be used. */
    public static void choose(Minecraft mc, String name) {
        String clean = ShieldArt.cleanPictureName(name);
        if (!clean.isEmpty() && !Files.isRegularFile(ShieldTextures.folder(mc).resolve(clean))) {
            throw new IllegalArgumentException("That picture is not in the breeze_shields folder.");
        }
        CustomShield.choose(clean);
    }

    /** Opens the breeze_shields folder (made if missing) in the system's file manager. */
    public static void openFolder(Minecraft mc) throws IOException {
        Path dir = ShieldTextures.folder(mc);
        Files.createDirectories(dir);
        Links.open(dir.toUri());
    }

    /** The face for this picture and fit, made again only when either or the file changed. */
    private static void preview(Minecraft mc, String file, ShieldArt.Fit fit) {
        if (file.isEmpty()) {
            previewKey = null;
            previewUri = null;
            previewProblem = null;
            return;
        }
        Path path = ShieldTextures.folder(mc).resolve(file);
        String key;
        try {
            key = file + "|" + fit + "|" + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            previewKey = null;
            previewUri = null;
            previewProblem = "The picture " + file + " is not in the breeze_shields folder.";
            return;
        }
        if (key.equals(previewKey)) return;
        previewKey = key;
        previewUri = null;
        previewProblem = null;
        try {
            ShieldTextures.ensureBase();
            previewUri = ImageData.dataUri(ShieldTextures.previewPng(ShieldTextures.readPicture(path), fit));
            if (previewUri == null) previewProblem = "The picture could not be shown.";
        } catch (IOException e) {
            previewProblem = e.getMessage();
        } catch (Throwable t) {
            previewProblem = "The picture could not be read.";
        }
    }
}
