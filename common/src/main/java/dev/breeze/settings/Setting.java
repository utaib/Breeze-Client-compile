package dev.breeze.settings;

import com.google.gson.JsonObject;

/**
 * One configurable value on a module.
 *
 * Deliberately a small closed set of types rather than a generic {@code
 * Setting<T>}: the editor has to render every setting, and an open type
 * parameter means the editor either reflects over values or falls back to a text
 * box. Four types cover everything the HUD needs, and each one has an obvious
 * control.
 *
 * The id is a stable config key and must never change once shipped; the label is
 * what the user reads and is free to change.
 */
public abstract class Setting {

    public final String id;
    public final String label;
    /** Section heading in the editor. Settings with the same group sit together. */
    public final String group;

    protected Setting(String id, String label, String group) {
        this.id = id;
        this.label = label;
        this.group = group;
    }

    /** Only shown when this returns true. Lets a mode hide settings it does not use. */
    public boolean visible() {
        return true;
    }

    public abstract void write(JsonObject out);

    public abstract void read(JsonObject in);

    /** What the editor shows on the right of the row. */
    public abstract String display();

    // ── Concrete types ──────────────────────────────────────────────────────

    public static final class Bool extends Setting {
        public boolean value;
        private final boolean initial;

        public Bool(String id, String label, String group, boolean value) {
            super(id, label, group);
            this.value = value;
            this.initial = value;
        }

        public void toggle() { value = !value; }

        @Override public void write(JsonObject out) { out.addProperty(id, value); }

        @Override public void read(JsonObject in) {
            if (in.has(id) && in.get(id).isJsonPrimitive()) value = in.get(id).getAsBoolean();
            else value = initial;
        }

        @Override public String display() { return value ? "on" : "off"; }
    }

    public static final class Int extends Setting {
        public int value;
        public final int min;
        public final int max;
        public final String suffix;
        private final int initial;

        public Int(String id, String label, String group, int value, int min, int max, String suffix) {
            super(id, label, group);
            this.min = min;
            this.max = max;
            this.suffix = suffix == null ? "" : suffix;
            this.value = clamp(value);
            this.initial = this.value;
        }

        public int clamp(int v) { return Math.max(min, Math.min(max, v)); }

        public void set(int v) { value = clamp(v); }

        /** Position in 0..1, for drawing and dragging a slider. */
        public float fraction() {
            return max == min ? 0f : (float) (value - min) / (max - min);
        }

        public void setFraction(double f) {
            set((int) Math.round(min + f * (max - min)));
        }

        @Override public void write(JsonObject out) { out.addProperty(id, value); }

        @Override public void read(JsonObject in) {
            if (in.has(id) && in.get(id).isJsonPrimitive()) {
                try { value = clamp(in.get(id).getAsInt()); return; } catch (Throwable ignored) {}
            }
            value = initial;
        }

        @Override public String display() { return value + suffix; }
    }

    /**
     * A colour, stored as "#AARRGGBB".
     *
     * Written as a string rather than an int because a config edited by hand is
     * a real workflow here, and -16777216 is not something anyone can read as
     * black. Alpha is part of the value so transparency needs no second setting.
     */
    public static final class Color extends Setting {
        public int argb;
        private final int initial;

        public Color(String id, String label, String group, int argb) {
            super(id, label, group);
            this.argb = argb;
            this.initial = argb;
        }

        public int rgb() { return argb & 0xFFFFFF; }

        public int alpha() { return (argb >>> 24) & 0xFF; }

        public void setAlpha(int a) {
            argb = (Math.max(0, Math.min(255, a)) << 24) | (argb & 0xFFFFFF);
        }

        public void setRgb(int rgb) {
            argb = (argb & 0xFF000000) | (rgb & 0xFFFFFF);
        }

        @Override public void write(JsonObject out) { out.addProperty(id, hex(argb)); }

        @Override public void read(JsonObject in) {
            argb = initial;
            if (!in.has(id) || !in.get(id).isJsonPrimitive()) return;
            try {
                String h = in.get(id).getAsString().trim().replace("#", "").replace("0x", "");
                long v = Long.parseLong(h, 16);
                // A six digit value is opaque. Without this a "#FF0000" written
                // by hand parses to fully transparent red and silently vanishes.
                if (h.length() <= 6) v |= 0xFF000000L;
                argb = (int) v;
            } catch (Throwable ignored) {}
        }

        @Override public String display() { return hex(argb); }

        public static String hex(int argb) {
            return String.format("#%08X", argb);
        }
    }

    /** One of a fixed list of named choices. */
    public static final class Mode extends Setting {
        public final String[] options;
        public int index;
        private final int initial;

        public Mode(String id, String label, String group, String[] options, int index) {
            super(id, label, group);
            this.options = options;
            this.index = wrap(index);
            this.initial = this.index;
        }

        private int wrap(int i) {
            if (options.length == 0) return 0;
            return ((i % options.length) + options.length) % options.length;
        }

        public String value() { return options.length == 0 ? "" : options[index]; }

        public boolean is(String option) { return value().equalsIgnoreCase(option); }

        public void cycle(int by) { index = wrap(index + by); }

        @Override public void write(JsonObject out) { out.addProperty(id, value()); }

        @Override public void read(JsonObject in) {
            index = initial;
            if (!in.has(id) || !in.get(id).isJsonPrimitive()) return;
            String want = in.get(id).getAsString();
            for (int i = 0; i < options.length; i++) {
                // By name, not by index. An index would silently mean something
                // different the moment an option is inserted into the list.
                if (options[i].equalsIgnoreCase(want)) { index = i; return; }
            }
        }

        @Override public String display() { return value(); }
    }
}
