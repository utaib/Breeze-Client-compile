package dev.breeze.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Origin, resource, link and event-script checks: the bridge's attack surface. */
class SecurityTest {

    @Test
    void onlyOurExactOriginIsTrusted() {
        assertTrue(PageOrigin.trusted("https://breeze.local/index.html"));
        assertTrue(PageOrigin.trusted("https://breeze.local/"));
        assertTrue(PageOrigin.trusted("https://BREEZE.local/index.html"));
        assertTrue(PageOrigin.trusted("https://breeze.local:443/x"));

        assertFalse(PageOrigin.trusted(null));
        assertFalse(PageOrigin.trusted(""));
        assertFalse(PageOrigin.trusted("http://breeze.local/"));
        assertFalse(PageOrigin.trusted("https://breeze.local.evil.com/"));
        assertFalse(PageOrigin.trusted("https://evil.com/breeze.local/"));
        assertFalse(PageOrigin.trusted("https://breeze.local@evil.com/"));
        assertFalse(PageOrigin.trusted("https://user@breeze.local/"));
        assertFalse(PageOrigin.trusted("https://breeze.local:8443/"));
        assertFalse(PageOrigin.trusted("mod://breeze/index.html"));
        assertFalse(PageOrigin.trusted("file:///etc/passwd"));
        assertFalse(PageOrigin.trusted("data:text/html,<script>"));
        assertFalse(PageOrigin.trusted("javascript:alert(1)"));
        assertFalse(PageOrigin.trusted("https://xn--breeze-local.com/"));
    }

    @Test
    void resourcesResolveInsideTheInterfaceFolderOnly() {
        assertEquals(new PageResources.Resource("assets/breeze/html/index.html", "text/html"),
                PageResources.resolve("https://breeze.local/"));
        assertEquals(new PageResources.Resource("assets/breeze/html/index.html", "text/html"),
                PageResources.resolve("https://breeze.local/index.html?x=1#y"));
        assertEquals(new PageResources.Resource("assets/breeze/html/assets/breeze.js", "text/javascript"),
                PageResources.resolve("https://breeze.local/assets/breeze.js"));
        assertEquals("font/woff2", PageResources.resolve("https://breeze.local/assets/a.woff2").mimeType());
        assertEquals("image/jpeg", PageResources.resolve("https://breeze.local/assets/title-backdrop.jpg").mimeType());
        // Case is preserved: this handler, unlike MCEF's mod://, is case-sensitive.
        assertEquals("assets/breeze/html/assets/Mixed.js", PageResources.resolve("https://breeze.local/assets/Mixed.js").classpathPath());
    }

    @Test
    void traversalAndOddPathsAreRefusedInEveryEncoding() {
        String[] refused = {
                "https://breeze.local/../fabric.mod.json",
                "https://breeze.local/assets/../../../dev/breeze/BreezeClient.class",
                "https://breeze.local/%2e%2e/fabric.mod.json",
                "https://breeze.local/assets/%2E%2E/%2E%2E/x.js",
                "https://breeze.local/assets%2f..%2f..%2fx.js",
                "https://breeze.local/./index.html",
                "https://breeze.local//index.html",
                "https://breeze.local/assets\\..\\x.js",
                "https://breeze.local/index.html%00.png",
                "https://breeze.local/secret.class",
                "https://breeze.local/noextension",
                "https://breeze.local/assets/",
                "https://evil.com/index.html",
                "http://breeze.local/index.html",
        };
        for (String url : refused) assertNull(PageResources.resolve(url), url);
    }

    @Test
    void onlyBreezeHttpsLinksOpenExternally() {
        assertEquals("breezeclient.net", ExternalLinks.check("https://breezeclient.net").getHost());
        assertNotNull(ExternalLinks.check("https://www.breezeclient.net/store"));
        assertNotNull(ExternalLinks.check("https://discord.gg/breeze"));

        String[] refused = {
                "http://breezeclient.net", "https://breezeclient.net.evil.com", "https://evil.com",
                "https://breezeclient.net@evil.com", "file:///C:/Windows/system32/calc.exe",
                "javascript:alert(1)", "https://breezeclient.net:8080/", "", null,
                "https://" + "a".repeat(600) + ".breezeclient.net",
        };
        for (String url : refused) {
            assertThrows(BridgeException.class, () -> ExternalLinks.check(url), String.valueOf(url));
        }
    }

    @Test
    void eventScriptsCannotBreakOutOfTheirLiteral() {
        JsonObject hostile = new JsonObject();
        hostile.addProperty("name", "</script><script>alert(1)</script>\u2028\u2029'\"`${x}");
        String js = Events.script("modules.changed", hostile);
        assertTrue(js.startsWith("window.__breezeEvent&&window.__breezeEvent({"), js);
        assertTrue(js.endsWith("})"), js);
        assertFalse(js.contains("</script>"), js);
        assertFalse(js.contains("\u2028"), js);
        assertFalse(js.contains("\u2029"), js);
        assertFalse(js.contains("'"), js);
        // Still the same data once parsed.
        String literal = js.substring("window.__breezeEvent&&window.__breezeEvent(".length(), js.length() - 1);
        assertEquals(hostile.get("name").getAsString(),
                JsonParser.parseString(literal).getAsJsonObject().getAsJsonObject("payload").get("name").getAsString());
    }

    @Test
    void onlyContractEventsCanBeSent() {
        assertThrows(IllegalArgumentException.class, () -> Events.script("eval", null));
        assertEquals("window.__breezeEvent&&window.__breezeEvent({\"type\":\"key.escape\"})", Events.script("key.escape", null));
    }
}
