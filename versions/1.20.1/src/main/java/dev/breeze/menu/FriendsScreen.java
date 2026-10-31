package dev.breeze.menu;

import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.compat.BreezeScreen;
import dev.breeze.ui.Glass;
import dev.breeze.ui.Palette;
import dev.breeze.ui.Rect;

import dev.breeze.net.FriendsClient;
import dev.breeze.net.HostManager;
import dev.breeze.ui.Theme;
import dev.breeze.ui.UiRender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class FriendsScreen extends BreezeScreen {

    private final Screen parent;
    private int px;
    private int py;
    private int pw;
    private int ph;
    private String addInput = "";
    private String dmInput = "";
    private int focus;
    private UUID selected;
    private String selectedName = "";

    public FriendsScreen(Screen parent) {
        super(Component.literal("Friends"));
        this.parent = parent;
        FriendsClient.status = "";
        FriendsClient.refresh(Minecraft.getInstance());
    }

    @Override
    protected void init() {
        pw = Math.min(this.width - 30, 470);
        ph = Math.min(this.height - 30, 260);
        px = (this.width - pw) / 2;
        py = (this.height - ph) / 2;
    }

    private int leftX() { return px + 12; }
    private int leftW() { return 168; }
    private int rightX() { return px + 192; }
    private int rightW() { return pw - 204; }

    private List<Object[]> rows() {
        List<Object[]> out = new ArrayList<>();
        int y = py + 64;
        synchronized (HostManager.INVITES) {
            for (HostManager.Invite inv : HostManager.INVITES) {
                out.add(new Object[]{3, inv, y});
                y += 13;
            }
        }
        for (FriendsClient.Entry e : FriendsClient.INCOMING) {
            out.add(new Object[]{0, e, y});
            y += 13;
        }
        for (FriendsClient.Entry e : FriendsClient.OUTGOING) {
            out.add(new Object[]{1, e, y});
            y += 13;
        }
        y += 3;
        out.add(new Object[]{9, null, y});
        y += 12;
        for (FriendsClient.Entry e : FriendsClient.FRIENDS) {
            out.add(new Object[]{2, e, y});
            y += 13;
        }
        return out;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Glass.scrim(g, this.width, this.height);
        Glass.panel(g, new Rect(px, py, pw, ph), Theme.panelBg(), Palette.BORDER);
        g.drawString(this.font, "Friends", px + 14, py + 12, Palette.TEXT_PRIMARY, false);
        g.drawString(this.font, "Esc to close", px + pw - 68, py + 12, Palette.TEXT_FAINT, false);

        drawInput(g, leftX(), py + 28, leftW() - 46, addInput, focus == 1, "player name");
        drawBtn(g, leftX() + leftW() - 42, py + 28, 42, 16, "Add", mouseX, mouseY);
        g.drawString(this.font, this.font.plainSubstrByWidth(FriendsClient.status, leftW()), leftX(), py + 47, Palette.TEXT_SECONDARY, false);
        // Kept to the 168-wide column: the longer hint it replaced ran on
        // into the chat panel beside it.
        g.drawString(this.font, this.font.plainSubstrByWidth("Right-click a friend to invite", leftW()), leftX(), py + 55, Palette.TEXT_FAINT, false);

        int bottom = py + ph - 26;
        for (Object[] row : rows()) {
            int kind = (Integer) row[0];
            int y = (Integer) row[2];
            if (y > bottom - 10) break;
            if (kind == 9) {
                g.drawString(this.font, "Friends (" + FriendsClient.FRIENDS.size() + ")", leftX(), y, Palette.TEXT_FAINT, false);
                continue;
            }
            if (kind == 3) {
                HostManager.Invite inv = (HostManager.Invite) row[1];
                g.drawString(this.font, this.font.plainSubstrByWidth("* " + inv.hostName + "'s world", leftW() - 34), leftX(), y, Palette.ACCENT, false);
                Glass.fillRounded(g, leftX() + leftW() - 30, y - 2, leftX() + leftW() - 2, y + 10, 3, 0x8033AA55);
                g.drawString(this.font, "Join", leftX() + leftW() - 27, y, Palette.TEXT_PRIMARY, false);
                continue;
            }
            FriendsClient.Entry e = (FriendsClient.Entry) row[1];
            if (kind == 0) {
                g.drawString(this.font, this.font.plainSubstrByWidth("+ " + e.name, leftW() - 34), leftX(), y, Palette.TEXT_PRIMARY, false);
                Glass.fillRounded(g, leftX() + leftW() - 28, y - 2, leftX() + leftW() - 16, y + 10, 3, 0x8033AA55);
                g.drawString(this.font, "Y", leftX() + leftW() - 25, y, Palette.TEXT_PRIMARY, false);
                Glass.fillRounded(g, leftX() + leftW() - 13, y - 2, leftX() + leftW() - 1, y + 10, 3, 0x80AA3344);
                g.drawString(this.font, "N", leftX() + leftW() - 10, y, Palette.TEXT_PRIMARY, false);
            } else if (kind == 1) {
                g.drawString(this.font, this.font.plainSubstrByWidth("> " + e.name + " (pending)", leftW()), leftX(), y, Palette.TEXT_FAINT, false);
            } else {
                boolean sel = e.id.equals(selected);
                boolean hover = mouseX >= leftX() && mouseX <= leftX() + leftW() && mouseY >= y - 2 && mouseY <= y + 10;
                if (sel || hover) {
                    Glass.fillRounded(g, leftX() - 3, y - 2, leftX() + leftW() + 1, y + 10, Glass.RADIUS_SM,
                            sel ? Theme.withAlpha(Theme.primary(), 0x33) : Theme.cardHover());
                }
                Glass.fillRounded(g, leftX(), y + 1, leftX() + 6, y + 7, 3, e.online ? 0xFF55FF55 : Palette.TEXT_FAINT);
                g.drawString(this.font, this.font.plainSubstrByWidth(e.name, leftW() - 24), leftX() + 9, y, Palette.TEXT_PRIMARY, false);
                g.drawString(this.font, "x", leftX() + leftW() - 6, y, hover ? 0xFFFF7788 : Palette.TEXT_FAINT, false);
            }
        }

        boolean hosting = HostManager.hosting;
        g.drawString(this.font, this.font.plainSubstrByWidth(HostManager.status, leftW() - 40), leftX(), py + ph - 20, hosting ? Palette.ACCENT : Palette.TEXT_FAINT, false);
        if (hosting) {
            drawBtn(g, leftX() + leftW() - 38, py + ph - 22, 38, 16, "Stop", mouseX, mouseY);
        }

        Glass.fillRounded(g, rightX(), py + 28, rightX() + rightW(), py + ph - 36, Glass.RADIUS_SM, Palette.alpha(Palette.BG_DEEP, 0x33));
        Glass.roundedBorder(g, rightX(), py + 28, rightX() + rightW(), py + ph - 36, Glass.RADIUS_SM, Palette.BORDER);
        String header = selected == null ? "Select a friend to chat" : selectedName;
        g.drawString(this.font, header, rightX() + 6, py + 33, Palette.TEXT_SECONDARY, false);
        if (selected != null) {
            List<FriendsClient.Msg> msgs = FriendsClient.HISTORY.get(selected);
            int lines = (ph - 64 - 24) / 11;
            int my = py + 28 + ph - 64 - 12;
            if (msgs != null) {
                for (int i = msgs.size() - 1; i >= 0 && lines > 0; i--) {
                    FriendsClient.Msg m = msgs.get(i);
                    String line = m.fromName + ": " + m.text;
                    g.drawString(this.font, this.font.plainSubstrByWidth(line, rightW() - 12), rightX() + 6, my, m.mine ? Palette.ACCENT : Palette.TEXT_PRIMARY, false);
                    my -= 11;
                    lines--;
                }
            }
        }
        drawInput(g, rightX(), py + ph - 30, rightW() - 46, dmInput, focus == 2, selected == null ? "select a friend first" : "message");
        drawBtn(g, rightX() + rightW() - 42, py + ph - 30, 42, 16, "Send", mouseX, mouseY);

        super.render(g, mouseX, mouseY, partialTick);
    }

    private void drawInput(GuiGraphics g, int x, int y, int w, String text, boolean focused, String placeholder) {
        Glass.fillRounded(g, x, y, x + w, y + 16, Glass.RADIUS_SM, Palette.BG);
        Glass.roundedBorder(g, x, y, x + w, y + 16, Glass.RADIUS_SM, focused ? Theme.primary() : Palette.BORDER);
        String shown = text.isEmpty() && !focused ? placeholder : text + (focused ? "_" : "");
        int color = text.isEmpty() && !focused ? Palette.TEXT_FAINT : Palette.TEXT_PRIMARY;
        g.drawString(this.font, this.font.plainSubstrByWidth(shown, w - 10), x + 5, y + 4, color, false);
    }

    private void drawBtn(GuiGraphics g, int x, int y, int w, int h, String label, int mouseX, int mouseY) {
        boolean hover = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h;
        Glass.fillRounded(g, x, y, x + w, y + h, Glass.RADIUS_SM, hover ? Theme.cardHover() : Theme.cardBg());
        Glass.roundedBorder(g, x, y, x + w, y + h, Glass.RADIUS_SM, hover ? Palette.BORDER_HOVER : Palette.BORDER);
        g.drawString(this.font, label, x + (w - this.font.width(label)) / 2, y + 4, Palette.TEXT_PRIMARY, false);
    }

    private boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    private void handleRight(double mx, double my) {
        for (Object[] row : rows()) {
            if ((Integer) row[0] != 2) continue;
            int y = (Integer) row[2];
            if (inside(mx, my, leftX() - 3, y - 2, leftW() + 4, 12)) {
                FriendsClient.Entry e = (FriendsClient.Entry) row[1];
                if (!HostManager.canHost()) {
                    HostManager.status = "Join a singleplayer world first, then invite.";
                    return;
                }
                if (HostManager.hosting) {
                    HostManager.invite(e.id);
                    HostManager.status = "Invited " + e.name + " to your world.";
                } else {
                    List<UUID> one = new ArrayList<>();
                    one.add(e.id);
                    HostManager.startHosting(one);
                }
                return;
            }
        }
    }

    private void handleClick(double mx, double my) {
        Minecraft mc = Minecraft.getInstance();
        focus = 0;
        if (inside(mx, my, leftX(), py + 28, leftW() - 46, 16)) {
            focus = 1;
            return;
        }
        if (inside(mx, my, rightX(), py + ph - 30, rightW() - 46, 16)) {
            if (selected != null) focus = 2;
            return;
        }
        if (inside(mx, my, leftX() + leftW() - 42, py + 28, 42, 16)) {
            doAdd(mc);
            return;
        }
        if (inside(mx, my, rightX() + rightW() - 42, py + ph - 30, 42, 16)) {
            doSend(mc);
            return;
        }
        if (HostManager.hosting && inside(mx, my, leftX() + leftW() - 38, py + ph - 22, 38, 16)) {
            HostManager.stopHosting();
            return;
        }
        for (Object[] row : rows()) {
            int kind = (Integer) row[0];
            if (kind == 9) continue;
            int y = (Integer) row[2];
            if (kind == 3) {
                if (inside(mx, my, leftX() + leftW() - 30, y - 2, 28, 12)) {
                    HostManager.joinFriend((HostManager.Invite) row[1]);
                    return;
                }
                continue;
            }
            FriendsClient.Entry e = (FriendsClient.Entry) row[1];
            if (kind == 0) {
                if (inside(mx, my, leftX() + leftW() - 28, y - 2, 12, 12)) {
                    FriendsClient.accept(mc, e.id);
                    return;
                }
                if (inside(mx, my, leftX() + leftW() - 13, y - 2, 12, 12)) {
                    FriendsClient.deny(mc, e.id);
                    return;
                }
            } else if (kind == 2) {
                if (inside(mx, my, leftX() + leftW() - 10, y - 2, 10, 12)) {
                    FriendsClient.remove(mc, e.id);
                    if (e.id.equals(selected)) {
                        selected = null;
                        selectedName = "";
                    }
                    return;
                }
                if (inside(mx, my, leftX() - 3, y - 2, leftW() + 4, 12)) {
                    selected = e.id;
                    selectedName = e.name;
                    focus = 2;
                    return;
                }
            }
        }
    }

    private void doAdd(Minecraft mc) {
        String name = addInput.trim();
        if (!name.isEmpty()) {
            FriendsClient.requestFriend(mc, name);
            addInput = "";
        }
    }

    private void doSend(Minecraft mc) {
        String text = dmInput.trim();
        if (selected != null && !text.isEmpty()) {
            FriendsClient.sendDm(mc, selected, text);
            dmInput = "";
        }
    }

    private boolean typeChar(char c) {
        if (focus == 1) {
            if (addInput.length() < 16 && (Character.isLetterOrDigit(c) || c == '_')) {
                addInput += c;
            }
            return true;
        }
        if (focus == 2) {
            if (dmInput.length() < 120 && c >= 32 && c != 127) {
                dmInput += c;
            }
            return true;
        }
        return false;
    }

    private boolean typeKey(int key) {
        if (focus == 0) return false;
        if (key == InputConstants.KEY_BACKSPACE) {
            if (focus == 1 && !addInput.isEmpty()) addInput = addInput.substring(0, addInput.length() - 1);
            if (focus == 2 && !dmInput.isEmpty()) dmInput = dmInput.substring(0, dmInput.length() - 1);
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            Minecraft mc = Minecraft.getInstance();
            if (focus == 1) doAdd(mc);
            if (focus == 2) doSend(mc);
            return true;
        }
        return false;
    }

    @Override
    protected boolean onMouseClicked(double mx, double my, int button) {
        if (button == 1) {
            handleRight(mx, my);
            return true;
        }
        if (button == 0) {
            handleClick(mx, my);
            return true;
        }
        return superMouseClicked(mx, my, button);
    }

    @Override
    protected boolean onCharTyped(int c, int mods) {
        if (Character.isBmpCodePoint(c) && typeChar((char) c)) return true;
        return superCharTyped(c, mods);
    }

    @Override
    protected boolean onKeyPressed(int key, int scan, int mods) {
        if (typeKey(key)) return true;
        return superKeyPressed(key, scan, mods);
    }

    @Override
    public void onClose() {
        dev.breeze.compat.ActiveScreen.set(this.minecraft, parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
