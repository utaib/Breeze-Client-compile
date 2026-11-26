package dev.breeze.recorder;

import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * The export's progress panel with its Cancel button, on the window while
 * a recording is rendered (1.2.3, round 3). It is drawn after the frame was
 * read back for the video (Exporter.frameRendered), straight into the
 * picture the window is about to show, so the video never has it. The panel
 * is drawn with Java's own 2D graphics into a small texture a few times a
 * second and copied onto the frame on the graphics card; every OpenGL
 * binding touched is read first and put back, as ScreenCapture does. The
 * mouse is set free while exporting, and a click on Cancel stops it.
 */
final class ExportOverlay {

    private ExportOverlay() {}

    private static int tex, fbo, dstFbo, texW, texH, attached;
    private static long builtAt;
    private static String builtText = "";
    /** Cancel's place on the frame (top-left origin, frame pixels), and the frame's size. */
    private static int cx, cy, cw, ch, frameW, frameH;
    private static boolean wasDown;
    /** Panels drawn (self-test). */
    static volatile long drawn;

    /** Draws the panel on the frame just drawn (render thread). */
    static void draw(Minecraft mc, Exporter e) {
        int target = ScreenSource.texture(mc);
        if (target == 0) return;
        int[] size = RecFrames.size(mc);
        int fw = size[0], fh = size[1];
        int pw = Math.max(300, Math.min(900, (int) (fw * 0.36)));
        pw = Math.min(pw, fw - 8);
        int ph = Math.max(72, pw * 24 / 100);
        if (pw < 120 || ph >= fh) return;
        long left = e.leftSeconds();
        String text = e.stage() + "|" + Exporter.progress + "|" + left + "|" + pw;
        long now = System.currentTimeMillis();
        if (tex == 0 || pw != texW || ph != texH || !text.equals(builtText) && now - builtAt > 400) {
            build(pw, ph, e.stage(), Exporter.progress, left);
            builtText = text;
            builtAt = now;
        }
        int x = (fw - pw) / 2, yTop = fh - ph - Math.max(8, fh / 25);
        frameW = fw;
        frameH = fh;
        // Cancel, in frame pixels from the top left (build() puts it at the panel's right).
        cw = pw / 4;
        ch = ph / 3;
        cx = x + pw - cw - ph / 8;
        cy = yTop + ph - ch - ph / 8;
        blit(target, x, fh - yTop - ph, pw, ph);
        drawn++;
    }

    private static void build(int w, int h, String stage, long pct, long left) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x12, 0x15, 0x1D));
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(0x27, 0x2C, 0x38));
        g.setStroke(new BasicStroke(Math.max(1, h / 40f)));
        g.drawRect(0, 0, w - 1, h - 1);
        int pad = h / 8;
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(10, h / 5)));
        g.setColor(new Color(0xE7, 0xE9, 0xEE));
        text(g, stage, pad, pad + h / 5);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, Math.max(9, h / 7)));
        g.setColor(new Color(0xA6, 0xAD, 0xBA));
        String sub = pct + "%" + (left >= 0 ? String.format(Locale.ROOT, ", about %d:%02d left", left / 60, left % 60) : "")
                + ". Keys do nothing meanwhile.";
        text(g, sub, pad, pad + h / 5 + h / 5);
        // The bar.
        int by = h - pad - h / 3 + h / 12, bw = w - pad * 3 - w / 4, bh = Math.max(4, h / 12);
        g.setColor(new Color(0x19, 0x1D, 0x27));
        g.fillRect(pad, by, bw, bh);
        g.setColor(new Color(0x78, 0xB2, 0xFF));
        g.fillRect(pad, by, (int) (bw * Math.min(100, Math.max(0, pct)) / 100), bh);
        // Cancel.
        int cw = w / 4, ch = h / 3, cx = w - cw - pad, cy = h - ch - pad;
        g.setColor(new Color(0x27, 0x2C, 0x38));
        g.fillRect(cx, cy, cw, ch);
        g.setColor(new Color(0xE7, 0xE9, 0xEE));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, Math.max(9, h / 7)));
        int tw = g.getFontMetrics().stringWidth("Cancel");
        text(g, "Cancel", cx + (cw - tw) / 2, cy + ch / 2 + g.getFontMetrics().getAscent() / 3);
        g.dispose();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        try {
            for (int v : argb) {
                buf.put((byte) (v >> 16)).put((byte) (v >> 8)).put((byte) v).put((byte) 0xFF);
            }
            buf.flip();
            upload(buf, w, h);
        } finally {
            MemoryUtil.memFree(buf);
        }
    }

    /** Java 2D text (through drawChars: the source chain renames Minecraft's own text call on 26.x). */
    private static void text(Graphics2D g, String s, int x, int y) {
        char[] c = s.toCharArray();
        g.drawChars(c, 0, c.length, x, y);
    }

    private static void upload(ByteBuffer rgba, int w, int h) {
        int prevTex = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        int prevUnpack = GL11.glGetInteger(GL21.GL_PIXEL_UNPACK_BUFFER_BINDING);
        int prevAlign = GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT);
        int prevRow = GL11.glGetInteger(GL11.GL_UNPACK_ROW_LENGTH);
        int prevSkipR = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_ROWS);
        int prevSkipP = GL11.glGetInteger(GL11.GL_UNPACK_SKIP_PIXELS);
        try {
            if (tex == 0) tex = GL11.glGenTextures();
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
            org.lwjgl.opengl.GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 4);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, 0);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, 0);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, rgba);
            texW = w;
            texH = h;
        } finally {
            GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, prevAlign);
            GL11.glPixelStorei(GL11.GL_UNPACK_ROW_LENGTH, prevRow);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_ROWS, prevSkipR);
            GL11.glPixelStorei(GL11.GL_UNPACK_SKIP_PIXELS, prevSkipP);
            org.lwjgl.opengl.GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, prevUnpack);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex);
        }
    }

    /** Copies the panel onto the frame's texture at (x, y) from the bottom left, the right way up. */
    private static void blit(int target, int x, int y, int w, int h) {
        int prevRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        try {
            if (fbo == 0) fbo = GL30.glGenFramebuffers();
            if (dstFbo == 0) dstFbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_READ_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
            GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, dstFbo);
            if (attached != target) {
                GL30.glFramebufferTexture2D(GL30.GL_DRAW_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, target, 0);
                attached = target;
            }
            GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
            if (scissor) GL11.glDisable(GL11.GL_SCISSOR_TEST);
            // The panel's rows are top first: flipped onto the bottom-up frame.
            GL30.glBlitFramebuffer(0, 0, texW, texH, x, y + h, x + w, y, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        } finally {
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevRead);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, prevDraw);
        }
    }

    /** Each frame of the export (runTick's end): the mouse is free, and a click on Cancel stops it. */
    static void input(Minecraft mc, Exporter e) {
        try {
            if (mc.mouseHandler.isMouseGrabbed()) mc.mouseHandler.releaseMouse();
            boolean down = mc.mouseHandler.isLeftPressed();
            if (down && !wasDown && frameW > 0 && cw > 0) {
                double sx = mc.getWindow().getScreenWidth(), sy = mc.getWindow().getScreenHeight();
                double fx = mc.mouseHandler.xpos() / Math.max(1, sx) * frameW, fy = mc.mouseHandler.ypos() / Math.max(1, sy) * frameH;
                if (fx >= cx && fx <= cx + cw && fy >= cy && fy <= cy + ch) Exporter.cancel();
            }
            wasDown = down;
        } catch (Throwable ignored) {
            // No mouse to read: the export still runs.
        }
    }

    /** Frees the panel's texture and framebuffers (render thread, when the export ends). */
    static void release() {
        try {
            if (tex != 0) GL11.glDeleteTextures(tex);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (dstFbo != 0) GL30.glDeleteFramebuffers(dstFbo);
        } catch (Throwable ignored) {}
        tex = fbo = dstFbo = attached = 0;
        texW = texH = 0;
        builtText = "";
        frameW = frameH = 0;
    }

    /** Cancel's centre in window coordinates (self-test clicks it), or null when not drawn. */
    static double[] cancelCentre(Minecraft mc) {
        if (frameW <= 0) return null;
        double sx = mc.getWindow().getScreenWidth(), sy = mc.getWindow().getScreenHeight();
        return new double[]{(cx + cw / 2.0) / frameW * sx, (cy + ch / 2.0) / frameH * sy};
    }
}
