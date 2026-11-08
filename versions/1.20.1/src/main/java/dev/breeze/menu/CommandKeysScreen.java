package dev.breeze.menu;

import dev.breeze.commands.CommandKeyRunner;
import dev.breeze.commands.CommandKeys;
import dev.breeze.commands.CommandKeys.Binding;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.compat.Widgets;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;
import dev.breeze.ui.Spacing;
import dev.breeze.ui.UiRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Command keys in the native menus (the versions without the web menu): the
 * same list, checks and key capture as the web menu's Keys page, through
 * CommandKeyRunner. A row: the command (click to edit), its key (click, then
 * press the key with Ctrl, Shift or Alt if wanted; Escape cancels), on or
 * off, and delete.
 */
public class CommandKeysScreen extends BreezeScreen {

    private static final int KEY_W = 92;
    private static final int ROW_H = Spacing.ROW_H + 6;

    private final Screen parent;
    private Rect panel = new Rect(0, 0, 0, 0);
    private Rect content = new Rect(0, 0, 0, 0);
    private List<Binding> list = new ArrayList<>();
    private double scroll;
    private String notice;
    /** After Add: ask for the new command's key straight away. */
    private boolean keyForNewest;

    public CommandKeysScreen(Screen parent) {
        super(Component.literal("Command keys"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        panel = Rect.centered(this.width, this.height, 420, Math.min(320, this.height - 20));
        content = new Rect(panel.x + Spacing.MD, panel.y + 52, panel.w - Spacing.MD * 2, panel.h - 52 - 40);
        int before = list.size();
        list = CommandKeyRunner.all();
        if (keyForNewest && list.size() > before && !list.isEmpty()) {
            Binding added = list.get(list.size() - 1);
            CommandKeyRunner.capture(added.id());
        }
        keyForNewest = false;
        boolean full = list.size() >= CommandKeys.MAX_BINDINGS;
        addRenderableWidget(Widgets.button(Component.literal(full ? "Most command keys reached" : "Add a command key"), b -> {
            if (CommandKeyRunner.all().size() >= CommandKeys.MAX_BINDINGS) return;
            keyForNewest = true;
            edit(null, "");
        }, panel.x + Spacing.MD, panel.bottom() - 30, 150, 20));
    }

    private void edit(String id, String current) {
        if (this.minecraft == null) return;
        dev.breeze.compat.ActiveScreen.set(this.minecraft, new TextSettingScreen(this, new TextSettingScreen.Target() {
            @Override public String title() { return id == null ? "New command key: command" : "Command"; }
            @Override public String value() { return current.isEmpty() ? "" : "/" + current; }
            @Override public int maxLength() { return CommandKeys.MAX_COMMAND; }
            @Override public void save(String typed) { CommandKeyRunner.save(id, typed, null); }
            @Override public String note() { return "Runs as if you typed it. The server decides what you may run."; }
        }));
    }

    private Rect rowBox(int i) {
        return new Rect(content.x, content.y - (int) scroll + i * (ROW_H + Spacing.XS), content.w, ROW_H);
    }

    private Rect keyCell(Rect row) {
        return new Rect(row.right() - KEY_W - 26 - 14 - 2 * Spacing.SM, row.y + 3, KEY_W, row.h - 6);
    }

    private Rect toggleCell(Rect row) {
        return new Rect(row.right() - 26 - 14 - Spacing.SM, row.y + (row.h - 12) / 2, 26, 12);
    }

    private Rect deleteCell(Rect row) {
        return new Rect(row.right() - 14, row.y + (row.h - 14) / 2, 14, 14);
    }

    private static String keyLabel(Binding b) {
        if (!b.bound()) return "No key";
        String key;
        try {
            key = com.mojang.blaze3d.platform.InputConstants.getKey(b.key()).getDisplayName().getString();
        } catch (Throwable t) {
            key = b.key();
        }
        return (b.ctrl() ? "Ctrl + " : "") + (b.shift() ? "Shift + " : "") + (b.alt() ? "Alt + " : "") + key;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int total = list.size() * (ROW_H + Spacing.XS);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - content.h)));
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, panel);
        g.drawString(this.font, "Command keys", panel.x + Spacing.MD, panel.y + 12, Palette.TEXT_PRIMARY, true);
        UiRender.textClipped(g, this.font, "A key that runs a command as if you typed it. The server decides what you may run.",
                panel.x + Spacing.MD, panel.y + 28, panel.w - Spacing.MD * 2, Palette.TEXT_FAINT);

        String waiting = CommandKeyRunner.capturing();
        g.enableScissor(content.x, content.y, content.right(), content.bottom());
        if (list.isEmpty()) {
            g.drawString(this.font, "No command keys yet.", content.x, content.y + 4, Palette.TEXT_SECONDARY, false);
        }
        for (int i = 0; i < list.size(); i++) {
            Binding b = list.get(i);
            Rect row = rowBox(i);
            if (row.bottom() < content.y || row.y > content.bottom()) continue;
            if (row.contains(mouseX, mouseY)) {
                Glass.fillRounded(g, row.x - 2, row.y, row.right() + 2, row.bottom(), Glass.RADIUS_SM, Palette.SURFACE_HOVER);
            }
            Rect key = keyCell(row);
            UiRender.textClipped(g, this.font, "/" + b.command(), row.x + Spacing.XS, row.y + (row.h - 8) / 2,
                    key.x - row.x - Spacing.MD, b.enabled() ? Palette.TEXT_PRIMARY : Palette.TEXT_FAINT);
            Glass.surface(g, key, key.contains(mouseX, mouseY), false);
            boolean asking = b.id().equals(waiting);
            UiRender.textCentered(g, this.font, asking ? "Press keys" : keyLabel(b), key, asking ? Palette.TEXT_PRIMARY : Palette.TEXT_SECONDARY);
            Rect t = toggleCell(row);
            Glass.toggle(g, t.x, t.y, t.w, t.h, b.enabled());
            Rect d = deleteCell(row);
            UiRender.textCentered(g, this.font, "x", d, d.contains(mouseX, mouseY) ? 0xFFFF6B6B : Palette.TEXT_FAINT);
            dev.breeze.devtest.Targets.put("command-key-" + b.id(), key.x + key.w / 2, key.y + key.h / 2);
        }
        g.disableScissor();
        if (notice != null) {
            UiRender.textClipped(g, this.font, notice, panel.x + Spacing.MD + 160, panel.bottom() - 24,
                    panel.w - Spacing.MD * 2 - 160, 0xFFF5C542);
        } else if (waiting != null) {
            UiRender.textClipped(g, this.font, "Press the key, with Ctrl, Shift or Alt if you like. Escape cancels.",
                    panel.x + Spacing.MD + 160, panel.bottom() - 24, panel.w - Spacing.MD * 2 - 160, Palette.TEXT_SECONDARY);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (CommandKeyRunner.capturing() != null) {
            CommandKeyRunner.cancelCapture();
            return true;
        }
        if (my >= content.y && my <= content.bottom()) {
            for (int i = 0; i < list.size(); i++) {
                Binding b = list.get(i);
                Rect row = rowBox(i);
                if (!row.contains(mx, my)) continue;
                notice = null;
                if (deleteCell(row).contains(mx, my)) {
                    CommandKeyRunner.remove(b.id());
                } else if (toggleCell(row).contains(mx, my)) {
                    CommandKeyRunner.save(b.id(), b.command(), !b.enabled());
                } else if (keyCell(row).contains(mx, my)) {
                    if (button == 1) CommandKeyRunner.unbind(b.id());
                    else CommandKeyRunner.capture(b.id());
                } else {
                    edit(b.id(), b.command());
                    return true;
                }
                list = CommandKeyRunner.all();
                return true;
            }
        }
        return superMouseClicked(mx, my, button);
    }

    @Override
    protected boolean onKeyPressed(int key, int scanCode, int modifiers) {
        if (CommandKeyRunner.capturing() != null) {
            if (CommandKeyRunner.modifierKey(key)) return true;
            String[] taken = CommandKeyRunner.takeKey(Minecraft.getInstance(), key, scanCode);
            notice = taken[1];
            list = CommandKeyRunner.all();
            return true;
        }
        return superKeyPressed(key, scanCode, modifiers);
    }

    @Override
    protected boolean onMouseScrolled(double mx, double my, double dx, double dy) {
        scroll -= dy * 18;
        return true;
    }

    @Override
    public void onClose() {
        CommandKeyRunner.cancelCapture();
        if (this.minecraft != null) dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
