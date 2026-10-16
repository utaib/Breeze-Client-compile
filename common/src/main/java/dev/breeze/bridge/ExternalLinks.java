package dev.breeze.bridge;

import java.net.URI;
import java.util.Locale;
import java.util.Set;

/**
 * Which links the interface may open in the player's own browser.
 *
 * The embedded browser never navigates anywhere itself. app.openExternal hands
 * an allow-listed https link to the operating system, and anything else is
 * refused, so the page cannot be used to launch arbitrary URLs or local files.
 */
public final class ExternalLinks {

    public static final Set<String> HOSTS = Set.of(
            "breezeclient.net", "www.breezeclient.net", "discord.gg", "modrinth.com");

    private ExternalLinks() {}

    public static URI check(String url) {
        if (url == null || url.length() > 500) throw BridgeException.invalid("That link is not allowed.");
        URI u;
        try {
            u = new URI(url);
        } catch (Exception e) {
            throw BridgeException.invalid("That link is not allowed.");
        }
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
        if (!"https".equalsIgnoreCase(u.getScheme()) || u.getRawUserInfo() != null
                || (u.getPort() != -1 && u.getPort() != 443) || !HOSTS.contains(host)) {
            throw BridgeException.forbidden("Breeze only opens its own links.");
        }
        return u;
    }
}
