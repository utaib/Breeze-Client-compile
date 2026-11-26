package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.cosmetics.ShieldArt;
import dev.breeze.cosmetics.ShieldTextures;
import dev.breeze.settings.Setting;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A picture of your own on the front of your shield (1.1.0 directive), from
 * the breeze_shields folder, drawn where Minecraft draws the shield
 * (cosmetics/ShieldDraw). Sharing it with other Breeze players is a choice,
 * off until the player turns it on (cosmetics/ShieldShare); other players'
 * shared shields show unless "Show other players' shields" is off, which
 * holds even while this module is off, as for capes.
 */
public class CustomShield extends Module {

    public static final String FILL = "Fill";
    public static final String WHOLE = "Whole picture";

    private static CustomShield instance;

    private final Setting.Text picture = add(new Setting.Text("picture", "Picture", "Picture", "", ShieldArt.MAX_NAME, ShieldArt::cleanPictureName));
    private final Setting.Mode fit = add(new Setting.Mode("fit", "Fit", "Look", new String[] {FILL, WHOLE}, 0));
    private final Setting.Bool share = add(new Setting.Bool("share", "Show it to other Breeze players", "Sharing", false));
    private final Setting.Bool others = add(new Setting.Bool("others", "Show other players' shields", "Sharing", true));

    public CustomShield() {
        super("Custom Shield", Category.VISUAL,
                "Puts a picture of your own on your shield, from the breeze_shields folder.", KEY_NONE);
        instance = this;
    }

    @Override
    public String scopeNote() {
        return "Shows on a shield in your hands, in first and third person; inventory slots keep the plain shield. "
                + "Other Breeze players see it only while sharing is on: the picture is sent to Breeze's server, made smaller, "
                + "and removed from it when you turn sharing or this module off.";
    }

    public static boolean active() {
        return instance != null && instance.isEnabled();
    }

    /** Whether other Breeze players' shared shields are drawn; on while this module is off too. */
    public static boolean showOthers() {
        return instance == null || instance.others.value;
    }

    public static String picture() {
        return instance == null ? "" : instance.picture.value;
    }

    public static ShieldArt.Fit fit() {
        return instance != null && instance.fit.is(WHOLE) ? ShieldArt.Fit.CONTAIN : ShieldArt.Fit.COVER;
    }

    public static boolean sharing() {
        return instance != null && instance.share.value;
    }

    /** Your own shield's texture: this module on and a usable picture chosen; else null. */
    public static ResourceLocation ownTexture(Minecraft mc) {
        if (!active()) return null;
        return ShieldTextures.own(mc, instance.picture.value, fit());
    }

    /**
     * What should be on Breeze's server now: "picture|modified|fit" while
     * this module is on, sharing is on and the picture is in the folder; ""
     * for nothing. ShieldShare compares it with what it last sent.
     */
    public static String shareKey(Minecraft mc) {
        if (!active() || !instance.share.value || instance.picture.value.isEmpty()) return "";
        try {
            Path file = ShieldTextures.folder(mc).resolve(instance.picture.value);
            if (!Files.isRegularFile(file)) return "";
            return instance.picture.value + "|" + Files.getLastModifiedTime(file).toMillis() + "|" + fit();
        } catch (Throwable t) {
            return "";
        }
    }

    /** Chooses a picture by its file name ("" for none), as the menus do; throws with the reason when refused. */
    public static void choose(String name) {
        if (instance != null) instance.picture.set(name);
    }

    /** For the menus and the self-test: the fit by its option name, sharing on or off. */
    public static void setFit(String option) {
        if (instance == null) return;
        for (int i = 0; i < instance.fit.options.length; i++) {
            if (instance.fit.options[i].equalsIgnoreCase(option)) instance.fit.index = i;
        }
    }

    public static void setSharing(boolean on) {
        if (instance != null) instance.share.value = on;
    }
}
