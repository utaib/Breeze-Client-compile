package dev.breeze.integrations;

/**
 * A mod Breeze tried to integrate with and could not: it is loaded, but its
 * API did not answer as published. The player sees this instead of a missing
 * entry, and Breeze carries on without it.
 *
 * @param provider "modmenu" or "breeze-entrypoint"
 * @param mod      the mod involved
 * @param detail   what failed, in a sentence
 */
public record IntegrationProblem(String provider, String mod, String detail) {

    /** The cause, cut to one line a player can read. */
    public static String describe(Throwable t) {
        Throwable cause = t;
        while ((cause instanceof java.lang.reflect.InvocationTargetException || cause instanceof ExceptionInInitializerError)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String msg = cause.getMessage();
        String text = cause.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg);
        text = text.replaceAll("\\s+", " ").trim();
        return text.length() > 240 ? text.substring(0, 240) : text;
    }
}
