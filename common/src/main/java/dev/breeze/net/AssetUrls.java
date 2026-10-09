package dev.breeze.net;

import java.net.URI;

/**
 * Which cosmetic asset URLs the game downloads, and from where.
 *
 * Cape images and models come from the API's answers, and the game fetches
 * them from the player's own machine, so only the API's own host is ever
 * fetched: a URL on any other host would let whoever could set it make every
 * player who sees the cape request an address of their choosing.
 *
 * Cape rows written before the API's proxy forwarded the scheme carry
 * "http://api.breezeclient.net/...". Every cape in the live catalogue looked
 * like that (2026-10-03), and an http URL was refused outright, so those capes
 * never showed. The host is ours either way, so such a URL is upgraded to
 * https, which is what the API itself does when it serves them.
 */
public final class AssetUrls {

    private AssetUrls() {}

    /**
     * The URL to download {@code url} from, or null when it must not be fetched.
     *
     * @param url the URL as the API gave it
     * @param api the API's base URL
     */
    public static String resolve(String url, URI api) {
        if (url == null || api == null || api.getHost() == null) return null;
        URI u;
        try {
            u = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = u.getScheme();
        if (u.getRawUserInfo() != null || u.getHost() == null || scheme == null) return null;
        if (!u.getHost().equalsIgnoreCase(api.getHost())) return null;

        boolean apiHttps = "https".equalsIgnoreCase(api.getScheme());
        if ("https".equalsIgnoreCase(scheme)) {
            return port(u) == port(api) ? u.toString() : null;
        }
        if (!"http".equalsIgnoreCase(scheme)) return null;
        if (!apiHttps) {
            // A local development API is served over http; keep it.
            return port(u) == port(api) ? u.toString() : null;
        }
        // http on our own host: the same file over https. An explicit :80 is
        // the http default and does not carry over.
        int p = u.getPort() == 80 ? -1 : u.getPort();
        String upgraded = "https://" + u.getRawAuthority().replaceFirst(":\\d+$", "")
                + (p > 0 ? ":" + p : "")
                + (u.getRawPath() == null ? "" : u.getRawPath())
                + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
        URI up = URI.create(upgraded);
        return port(up) == port(api) ? upgraded : null;
    }

    private static int port(URI u) {
        if (u.getPort() > 0) return u.getPort();
        return "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
    }
}
