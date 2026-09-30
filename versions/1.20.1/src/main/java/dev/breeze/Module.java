package dev.breeze;

import dev.breeze.settings.Setting;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

public abstract class Module {

    public static final int KEY_NONE = -1;

    private final String name;
    private final Category category;
    private final String description;
    private final int defaultKey;

    private boolean enabled;
    private KeyMapping keyMapping;

    private final List<Setting> settings = new ArrayList<>();

    protected Module(String name, Category category, String description, int defaultKey) {
        this.name = name;
        this.category = category;
        this.description = description;
        this.defaultKey = defaultKey;
    }

    protected void onEnable() {}
    protected void onDisable() {}
    protected void onTick(Minecraft mc) {}
    protected void onHudRender(GuiGraphics g, float partialTick) {}
    protected void onWorldRender(WorldRenderContext ctx) {}

    public final String getName() { return name; }
    public final Category getCategory() { return category; }
    public final String getDescription() { return description; }
    public final int getDefaultKey() { return defaultKey; }

    public final boolean isEnabled() { return enabled; }

    /** How many of {@link #settings} are the module's own, which come first. */
    private int ownSettings;

    /**
     * Register a setting. Returns it, so a module can hold the reference:
     * {@code private final Setting.Int size = add(new Setting.Int(...));}
     *
     * A module's own settings are listed before shared blocks added with
     * {@link #addAll} (the HUD style), so its menu page opens on what is
     * particular to it rather than on background colours.
     */
    protected final <T extends Setting> T add(T setting) {
        settings.add(ownSettings++, setting);
        return setting;
    }

    protected final void addAll(List<Setting> more) {
        settings.addAll(more);
    }

    public final List<Setting> getSettings() { return settings; }

    public final boolean hasSettings() { return !settings.isEmpty(); }

    /**
     * Names this module was saved under before a rename, newest first. Saved
     * state is keyed by name, so without these a rename would quietly reset a
     * player's choice.
     */
    public List<String> earlierNames() { return List.of(); }

    /** Called after the enabled state was loaded from one of {@link #earlierNames()}. */
    public void migratedFrom(String earlierName) {}

    public final KeyMapping getKeyMapping() { return keyMapping; }
    public final void setKeyMapping(KeyMapping keyMapping) { this.keyMapping = keyMapping; }

    public final void setStateSilently(boolean enabled) { this.enabled = enabled; }

    public final boolean isHud() {
        return category == Category.HUD || category == Category.VISUAL;
    }

    public final void toggle() { setEnabled(!enabled); }

    public final void setEnabled(boolean enabled) {
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        try {
            if (enabled) onEnable();
            else onDisable();
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] Module '{}' threw during enable/disable: {}", name, t.toString());
        }
    }
}
