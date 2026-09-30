package dev.breeze.menu;

import dev.breeze.compat.BreezeScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

public class KeybindSearchScreen extends BreezeScreen {

    private EditBox search;

    public KeybindSearchScreen() {
        super(Component.literal("Keybind Search"));
    }

    /** A plain list over Minecraft's own dimmed background, not a Breeze panel. */
    @Override
    protected boolean vanillaBackground() {
        return true;
    }

    @Override
    protected void init() {
        search = new EditBox(this.font, this.width / 2 - 120, 24, 240, 20, Component.literal("Search"));
        addRenderableWidget(search);
        setInitialFocus(search);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(this.font, Component.literal("§bKeybind Search"), this.width / 2, 10, 0xFFFFFFFF);
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return;
        String query = search.getValue().toLowerCase();
        int y = 52;
        for (KeyMapping km : mc.options.keyMappings) {
            String name = Component.translatable(km.getName()).getString();
            if (!query.isEmpty() && !name.toLowerCase().contains(query)) continue;
            if (y > this.height - 16) break;
            g.drawString(this.font, name + " : " + km.getTranslatedKeyMessage().getString(), this.width / 2 - 120, y, 0xFFFFFFFF);
            y += 11;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
