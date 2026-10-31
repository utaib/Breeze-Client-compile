package dev.breeze.modules;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.settings.Setting;
import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

/**
 * Free Look: while its key is held (or after it is pressed, in Toggle mode)
 * the mouse turns the camera and not the player, who keeps walking and
 * facing where they were. The camera goes to third person for it if the
 * player was in first person, and comes back when the key is let go.
 *
 * Two hooks do the work: Entity.turn, which the mouse calls to turn the
 * player (EntityTurnMixin skips it for the local player while looking), and
 * the camera's first setRotation in Camera.setup (CameraMixin hands it this
 * module's angles). Both are optional injections, so a game where either is
 * missing runs without Free Look rather than crashing; the self-test checks
 * both on every version.
 *
 * The module's key is the look key: it does not switch the module, which is
 * switched in the menu. Looking stops, and the camera is put back, when the
 * key is let go, a screen opens, the world goes away or the module is
 * switched off.
 */
public class FreeLook extends Module {

    private static final String GROUP = "Free Look";
    private static FreeLook instance;

    private final Setting.Mode mode = add(new Setting.Mode("mode", "Key", GROUP, new String[]{"Hold", "Toggle"}, 0));
    private final Setting.Int sensitivity = add(new Setting.Int("sensitivity", "Sensitivity", GROUP, 100, 10, 300, "%"));
    private final Setting.Bool thirdPerson = add(new Setting.Bool("third_person", "Third person while looking", GROUP, true));

    private boolean looking;
    private boolean toggled;
    private float yaw;
    private float pitch;
    /** The camera before looking, when Free Look changed it; null when it did not. */
    private CameraType restore;
    /** Times the camera took Free Look's angles: the self-test's proof that the camera hook runs. */
    private int cameraApplied;

    public FreeLook() {
        super("Free Look", Category.UTILITY, "Look around without turning your player.", InputConstants.KEY_LALT);
        instance = this;
    }

    @Override
    public boolean keyIsAction() {
        return true;
    }

    @Override
    protected void onTick(Minecraft mc) {
        KeyMapping key = getKeyMapping();
        boolean inWorld = mc.player != null && mc.level != null;
        boolean want = false;
        if (key != null && inWorld) {
            if (mode.is("Toggle")) {
                while (key.consumeClick()) toggled = !toggled;
                want = toggled;
            } else {
                while (key.consumeClick()) { /* only held matters */ }
                want = key.isDown();
            }
        }
        if (dev.breeze.compat.ActiveScreen.get(mc) != null) want = false;
        if (want && !looking) start(mc);
        else if (!want && looking) stop(mc);
    }

    @Override
    protected void onDisable() {
        stop(Minecraft.getInstance());
    }

    private void start(Minecraft mc) {
        if (mc.player == null) return;
        yaw = mc.player.getYRot();
        pitch = mc.player.getXRot();
        restore = null;
        if (thirdPerson.value && mc.options.getCameraType().isFirstPerson()) {
            restore = mc.options.getCameraType();
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
        }
        looking = true;
    }

    private void stop(Minecraft mc) {
        boolean was = looking;
        looking = false;
        toggled = false;
        // Put the camera back only if the player did not change it meanwhile.
        if (was && restore != null && mc.options.getCameraType() == CameraType.THIRD_PERSON_BACK) {
            mc.options.setCameraType(restore);
        }
        restore = null;
    }

    /**
     * From EntityTurnMixin: the mouse turning an entity, in the units
     * Entity.turn takes. True when Free Look took the turn for the camera.
     */
    public static boolean turn(Object entity, double yRot, double xRot) {
        FreeLook f = instance;
        if (f == null || !f.looking || entity != Minecraft.getInstance().player) return false;
        float scale = 0.15F * f.sensitivity.value / 100F;
        f.yaw += (float) yRot * scale;
        f.pitch = Math.max(-90F, Math.min(90F, f.pitch + (float) xRot * scale));
        return true;
    }

    public static boolean active() {
        return instance != null && instance.looking;
    }

    /**
     * From FreeLookCameraMixin, for each turn Camera.setup gives the camera:
     * true when it should take Free Look's angles instead. Not when not
     * looking, and not for the turn to the front view, which is the camera's
     * own yaw (already Free Look's) plus 180 degrees and its pitch negated.
     */
    public static boolean takesCameraTurn(float yRot, float xRot) {
        if (!active()) return false;
        float back = net.minecraft.util.Mth.wrapDegrees(yRot - (instance.yaw + 180F));
        if (Math.abs(back) < 0.01F && Math.abs(xRot + instance.pitch) < 0.01F) return false;
        instance.cameraApplied++;
        return true;
    }

    // ── For the self-test ───────────────────────────────────────────────

    public static float yaw() {
        return instance == null ? 0 : instance.yaw;
    }

    public static float pitch() {
        return instance == null ? 0 : instance.pitch;
    }

    public static int cameraApplied() {
        return instance == null ? 0 : instance.cameraApplied;
    }
}
