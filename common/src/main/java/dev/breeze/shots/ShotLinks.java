package dev.breeze.shots;

/**
 * The buttons on Breeze's screenshot chat line carry their action in the
 * text style's insertion: {@code breeze:shot:<action>:<file name>}. Clicking
 * one is caught before Minecraft acts on the style, so nothing is sent to a
 * server and no text is inserted.
 */
public final class ShotLinks {

    public static final String PREFIX = "breeze:shot:";

    /** Screenshot buttons, then the recorder's: watch or export a recording, open the Recordings page. */
    public enum Action { VIEW, FOLDER, COPY, WATCH, EXPORT, RECORDINGS }

    public record Link(Action action, String name) {}

    private ShotLinks() {}

    public static String encode(Action action, String name) {
        return PREFIX + action.name().toLowerCase(java.util.Locale.ROOT) + ":" + name;
    }

    /** The action a style's insertion asks for, or null when it is not a valid Breeze screenshot button. */
    public static Link decode(String insertion) {
        if (insertion == null || !insertion.startsWith(PREFIX)) return null;
        String rest = insertion.substring(PREFIX.length());
        int colon = rest.indexOf(':');
        if (colon <= 0) return null;
        Action a;
        try {
            a = Action.valueOf(rest.substring(0, colon).toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
        String name = rest.substring(colon + 1);
        boolean ok = switch (a) {
            case WATCH, EXPORT -> safeRecording(name);
            case RECORDINGS -> true;
            default -> ShotFiles.safeName(name);
        };
        return ok ? new Link(a, name) : null;
    }

    /** A bare recording file name (the same rules as a picture's, ending in .breezerec). */
    public static boolean safeRecording(String name) {
        if (name == null || !name.endsWith(dev.breeze.recorder.RecFormat.EXTENSION)) return false;
        String asPicture = name.substring(0, name.length() - dev.breeze.recorder.RecFormat.EXTENSION.length()) + ".png";
        return ShotFiles.safeName(asPicture);
    }
}
