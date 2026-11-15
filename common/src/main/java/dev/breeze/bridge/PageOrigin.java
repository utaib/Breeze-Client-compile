package dev.breeze.bridge;

import java.net.URI;
import java.util.Locale;

/**
 * The one origin the interface is served from, and the check the bridge
 * applies before answering anything.
 *
 * {@code https://breeze.local/} is not a real host. The mod registers a CEF
 * scheme handler for it that serves the page from the mod's own jar, so no
 * request for it ever leaves the machine, and https makes it a secure context.
 * A frame on any other origin, for example one the page was tricked into
 * navigating to, gets no bridge at all.
 */
public final class PageOrigin {

    public static final String SCHEME = "https";
    public static final String HOST = "breeze.local";
    public static final String ORIGIN = SCHEME + "://" + HOST;
    public static final String INDEX = ORIGIN + "/index.html";

    private PageOrigin() {}

    /** True only for URLs whose scheme, host and port are exactly ours. */
    public static boolean trusted(String url) {
        if (url == null || url.isEmpty()) return false;
        try {
            URI u = new URI(url);
            return SCHEME.equals(lower(u.getScheme()))
                    && HOST.equals(lower(u.getHost()))
                    && u.getRawUserInfo() == null
                    && (u.getPort() == -1 || u.getPort() == 443);
        } catch (Exception malformed) {
            return false;
        }
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }
}
