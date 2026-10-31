package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.Module;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.config.BreezeConfig;
import dev.breeze.settings.Setting;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Edits one line of text in the native menus, which have no field of their
 * own: a text setting (Display Name's name) or a command key's command. Save
 * keeps only what the target's rule allows; a refusal is shown under the
 * field and the typed text stays.
 */
public class TextSettingScreen extends BreezeScreen {

    /** What is edited: its title, its current text, and how it is kept. */
    public interface Target {
        String title();

        String value();

        int maxLength();

        /** Keeps the typed text, or throws IllegalArgumentException with what to change. */
        void save(String typed);

        /** A line or two under the field (where the effect reaches); null for none. */
        String note();
    }

    private final Screen parent;
    private final Target target;

    private Rect panel = new Rect(0, 0, 0, 0);
    private EditBox field;
    private String error;

    public TextSettingScreen(Screen parent, Target target) {
        super(Component.literal(target.title()));
        this.parent = parent;
        this.target = target;
    }

    /** A module's text setting, saved with the module's config. */
    public TextSettingScreen(Screen parent, Module module, Setting.Text setting) {
        this(parent, new Target() {
            @Override public String title() { return module.getName() + ": " + setting.label; }
            @Override public String value() { return setting.value; }
            @Override public int maxLength() { return setting.maxLength; }
            @Override public void save(String typed) {
                setting.set(typed);
                try { BreezeConfig.save(); } catch (Throwable ignored) {}
            }
            @Override public String note() { return module.scopeNote(); }
        });
    }

    @Override
    protected void init() {
        panel = Rect.centered(this.width, this.height, 320, 170);
        String typed = field == null ? target.value() : field.getValue();
        field = new EditBox(this.font, panel.x + Spacing.MD, panel.y + 40, panel.w - Spacing.MD * 2, 20, Component.literal(target.title()));
        // Room for what the rule tidies away (spaces, a slash); the rule decides the rest.
        field.setMaxLength(target.maxLength() + 16);
        field.setValue(typed);
        addRenderableWidget(field);
        setInitialFocus(field);
        int by = panel.bottom() - 30;
        addRenderableWidget(Widgets.button(Component.literal("Save"), b -> save(), panel.right() - Spacing.MD - 170, by, 80, 20));
        addRenderableWidget(Widgets.button(Component.literal("Cancel"), b -> onClose(), panel.right() - Spacing.MD - 80, by, 80, 20));
    }

    private void save() {
        try {
            target.save(field.getValue());
        } catch (IllegalArgumentException e) {
            error = e.getMessage();
            return;
        }
        onClose();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        g.drawString(this.font, target.title(), panel.x + Spacing.MD, panel.y + 14, Palette.TEXT_PRIMARY, true);
        int y = panel.y + 68;
        if (error != null) {
            g.drawString(this.font, error, panel.x + Spacing.MD, y, 0xFFFF6B6B, false);
            y += 12;
        }
        String note = target.note();
        if (note != null) {
            for (String line : wrap(note, panel.w - Spacing.MD * 2)) {
                if (y > panel.bottom() - 40) break;
                g.drawString(this.font, line, panel.x + Spacing.MD, y, Palette.TEXT_FAINT, false);
                y += 10;
            }
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String next = line.length() == 0 ? word : line + " " + word;
            if (this.font.width(next) > width && line.length() > 0) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(next);
            }
        }
        if (line.length() > 0) lines.add(line.toString());
        return lines;
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (key == InputConstants.KEY_RETURN) {
            save();
            return true;
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
