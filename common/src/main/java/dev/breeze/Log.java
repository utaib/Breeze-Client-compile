package dev.breeze;

/**
 * Logging for code that cannot see Minecraft.
 *
 * The portable half of Breeze needs to report things, and the obvious way to do
 * that, {@code BreezeClient.LOGGER}, lives in a class that imports Minecraft.
 * One static field was enough to pin the API client, the relay and the settings
 * model to a particular Minecraft version, which is the whole reason this
 * module exists.
 *
 * So common logs through here and the version module installs the real logger
 * at startup. Until it does, messages go nowhere rather than throwing: a mod
 * that crashes because logging was not ready yet would be a poor trade for
 * tidiness, and the window is a few milliseconds during init.
 */
public final class Log {

    /** What a version module installs. Slf4j's Logger fits without adapting. */
    public interface Sink {
        void info(String message);

        void warn(String message);

        void error(String message, Throwable error);
    }

    private static volatile Sink sink;

    private Log() {}

    /** Called once by the version module as it starts. */
    public static void install(Sink target) {
        sink = target;
    }

    public static void info(String message) {
        Sink s = sink;
        if (s != null) s.info(message);
    }

    public static void warn(String message) {
        Sink s = sink;
        if (s != null) s.warn(message);
    }

    /**
     * Slf4j's {@code {}} placeholders, formatted here.
     *
     * The call sites were written against a Logger and read better for it, so
     * the facade supports the same shape rather than making every one of them
     * build a string by hand. Formatting happens only when there is somewhere
     * to send the result.
     */
    public static void info(String pattern, Object... args) {
        Sink s = sink;
        if (s != null) s.info(format(pattern, args));
    }

    public static void warn(String pattern, Object... args) {
        Sink s = sink;
        if (s != null) s.warn(format(pattern, args));
    }

    public static void error(String pattern, Object... args) {
        Sink s = sink;
        if (s != null) s.error(format(pattern, args), null);
    }

    private static String format(String pattern, Object... args) {
        if (args == null || args.length == 0) return pattern;
        StringBuilder out = new StringBuilder(pattern.length() + 16 * args.length);
        int arg = 0;
        int i = 0;
        while (i < pattern.length()) {
            int at = pattern.indexOf("{}", i);
            if (at < 0 || arg >= args.length) {
                out.append(pattern, i, pattern.length());
                break;
            }
            out.append(pattern, i, at).append(args[arg++]);
            i = at + 2;
        }
        return out.toString();
    }

    public static void error(String message, Throwable error) {
        Sink s = sink;
        if (s != null) s.error(message, error);
    }
}
