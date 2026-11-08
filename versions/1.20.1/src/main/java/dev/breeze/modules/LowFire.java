package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.render.FireOverlay;
import dev.breeze.settings.Setting;

import java.util.List;

/**
 * Low Fire: lowers the first-person fire overlay by a chosen amount, drawn in
 * ScreenEffectRendererMixin. The value is read every frame, so moving the
 * slider takes effect at once.
 *
 * Replaces No Fire Overlay, which could only hide the overlay. 100% still
 * hides it, and anyone who had No Fire Overlay on starts at 100%.
 */
public class LowFire extends Module {

    private static LowFire instance;

    private final Setting.Int lower = add(new Setting.Int("lower", "Lower by", "Fire", 50, 0, FireOverlay.HIDDEN, "%"));

    public LowFire() {
        super("Low Fire", Category.VISUAL, "Lowers the fire overlay while you are burning so you can see. At 100% it is hidden.", KEY_NONE);
        instance = this;
    }

    /** How far to lower the overlay, 0-100%. 0 while the module is off. */
    public static int lowerBy() {
        return instance != null && instance.isEnabled() ? instance.lower.value : 0;
    }

    @Override
    public List<String> earlierNames() {
        return List.of("No Fire Overlay");
    }

    @Override
    public void migratedFrom(String earlierName) {
        // No Fire Overlay hid the overlay completely. Keep that for anyone who
        // had it on; the slider is there to bring some of it back.
        if (isEnabled()) lower.set(FireOverlay.HIDDEN);
    }
}
