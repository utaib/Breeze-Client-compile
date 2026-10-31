package dev.breeze.net;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

class AssetUrlsTest {

    private static final URI API = URI.create("https://api.breezeclient.net");
    private static final String CAPE = "/assets/capes/marketplace/5a5d_qwerky_doodle_1784832413811.png";

    @Test
    void httpsOnTheApiHostIsFetchedAsIs() {
        assertEquals("https://api.breezeclient.net" + CAPE, AssetUrls.resolve("https://api.breezeclient.net" + CAPE, API));
    }

    @Test
    void httpOnTheApiHostIsUpgradedToHttps() {
        // How every cape row in the live catalogue is stored.
        assertEquals("https://api.breezeclient.net" + CAPE, AssetUrls.resolve("http://api.breezeclient.net" + CAPE, API));
        assertEquals("https://api.breezeclient.net" + CAPE, AssetUrls.resolve("http://api.breezeclient.net:80" + CAPE, API));
    }

    @Test
    void theHostIsComparedWithoutCase() {
        assertNotNull(AssetUrls.resolve("http://API.BreezeClient.net" + CAPE, API));
    }

    @Test
    void encodedPathsAndQueriesSurviveTheUpgrade() {
        assertEquals("https://api.breezeclient.net/assets/capes/a%20b.png?v=2",
                AssetUrls.resolve("http://api.breezeclient.net/assets/capes/a%20b.png?v=2", API));
    }

    @Test
    void otherHostsAreRefused() {
        assertNull(AssetUrls.resolve("https://sutvhhyggouorgmmglik.supabase.co/storage/v1/object/public/capes/x.png", API));
        assertNull(AssetUrls.resolve("http://example.com" + CAPE, API));
        assertNull(AssetUrls.resolve("https://api.breezeclient.net.evil.example" + CAPE, API));
    }

    @Test
    void credentialsOtherPortsAndOtherSchemesAreRefused() {
        assertNull(AssetUrls.resolve("https://user@api.breezeclient.net" + CAPE, API));
        assertNull(AssetUrls.resolve("https://api.breezeclient.net:8443" + CAPE, API));
        assertNull(AssetUrls.resolve("http://api.breezeclient.net:8080" + CAPE, API));
        assertNull(AssetUrls.resolve("ftp://api.breezeclient.net" + CAPE, API));
        assertNull(AssetUrls.resolve("file:///etc/passwd", API));
    }

    @Test
    void nothingAndNonsenseAreRefused() {
        assertNull(AssetUrls.resolve(null, API));
        assertNull(AssetUrls.resolve("", API));
        assertNull(AssetUrls.resolve("not a url", API));
        assertNull(AssetUrls.resolve("/assets/capes/relative.png", API));
    }

    @Test
    void aLocalHttpApiKeepsHttp() {
        URI local = URI.create("http://127.0.0.1:8787");
        assertEquals("http://127.0.0.1:8787/assets/x.png", AssetUrls.resolve("http://127.0.0.1:8787/assets/x.png", local));
        assertNull(AssetUrls.resolve("http://127.0.0.1:9999/assets/x.png", local));
    }
}
