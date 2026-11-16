package dev.breeze.bridge;

import java.net.URI;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a request for https://breeze.local/... to a file inside the mod jar.
 *
 * The CEF resource handler in each version module calls {@link #resolve} and
 * serves the classpath resource it names, or a 404 when it returns null. All
 * the security decisions live here, where they can be tested: only our
 * origin, only files under the interface's own folder, no traversal in any
 * encoding, and a MIME type for every file type the build produces.
 */
public final class PageResources {

    /** Where the frontend build sits inside the jar. */
    public static final String ROOT = "assets/breeze/html/";

    public record Resource(String classpathPath, String mimeType) {}

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html"),
            Map.entry("js", "text/javascript"),
            Map.entry("mjs", "text/javascript"),
            Map.entry("css", "text/css"),
            Map.entry("json", "application/json"),
            Map.entry("map", "application/json"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("webp", "image/webp"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"));

    private PageResources() {}

    /** The resource for a URL, or null when it must not be served. */
    public static Resource resolve(String url) {
        if (!PageOrigin.trusted(url)) return null;
        String path;
        try {
            // getPath() decodes percent-escapes, so %2e%2e arrives as ".." and
            // is caught below like any other traversal.
            path = new URI(url).getPath();
        } catch (Exception malformed) {
            return null;
        }
        if (path == null || path.isEmpty() || path.equals("/")) path = "/index.html";
        if (!path.startsWith("/")) return null;
        String rel = path.substring(1);
        if (rel.isEmpty() || rel.length() > 200) return null;
        for (String part : rel.split("/", -1)) {
            if (part.isEmpty() || part.equals(".") || part.equals("..")) return null;
        }
        for (int i = 0; i < rel.length(); i++) {
            char c = rel.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == '/' || c == '.' || c == '-' || c == '_';
            if (!ok) return null;
        }
        int dot = rel.lastIndexOf('.');
        if (dot < 0) return null;
        String mime = MIME.get(rel.substring(dot + 1).toLowerCase(Locale.ROOT));
        if (mime == null) return null;
        return new Resource(ROOT + rel, mime);
    }
}
