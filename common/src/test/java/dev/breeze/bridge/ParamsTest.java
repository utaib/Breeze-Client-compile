package dev.breeze.bridge;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ParamsTest {

    static Params p(String json) {
        return new Params(JsonParser.parseString(json).getAsJsonObject());
    }

    static String invalid(Runnable r) {
        BridgeException e = assertThrows(BridgeException.class, r::run);
        assertEquals(BridgeError.INVALID_PARAMS, e.error());
        return e.getMessage();
    }

    @Test
    void strings() {
        assertEquals("FPS", p("{\"n\":\"FPS\"}").str("n", 10));
        assertEquals("Missing n.", invalid(() -> p("{}").str("n", 10)));
        assertEquals("Missing n.", invalid(() -> p("{\"n\":null}").str("n", 10)));
        assertEquals("n must be text.", invalid(() -> p("{\"n\":3}").str("n", 10)));
        assertEquals("n has the wrong type.", invalid(() -> p("{\"n\":{}}").str("n", 10)));
        assertEquals("n is too long.", invalid(() -> p("{\"n\":\"abcdef\"}").str("n", 5)));
        assertNull(p("{\"n\":null}").optStr("n", 5));
        assertNull(p("{}").optStr("n", 5));
    }

    @Test
    void booleansAreNotCoerced() {
        assertTrue(p("{\"b\":true}").bool("b"));
        invalid(() -> p("{\"b\":\"true\"}").bool("b"));
        invalid(() -> p("{\"b\":1}").bool("b"));
    }

    @Test
    void integersAreWholeAndBounded() {
        assertEquals(5, p("{\"i\":5}").integer("i", 0, 10));
        assertEquals(5, p("{\"i\":5.0}").integer("i", 0, 10));
        assertEquals("i must be a whole number.", invalid(() -> p("{\"i\":5.5}").integer("i", 0, 10)));
        assertEquals("i must be between 0 and 10.", invalid(() -> p("{\"i\":11}").integer("i", 0, 10)));
        assertEquals("i must be between 0 and 10.", invalid(() -> p("{\"i\":-1}").integer("i", 0, 10)));
        invalid(() -> p("{\"i\":1e400}").integer("i", 0, 10));
        invalid(() -> p("{\"i\":\"5\"}").integer("i", 0, 10));
    }

    @Test
    void uuidsMustBeCanonical() {
        UUID u = UUID.randomUUID();
        assertEquals(u, p("{\"u\":\"" + u + "\"}").uuid("u"));
        assertEquals("u is not a player id.", invalid(() -> p("{\"u\":\"1-1-1-1-1\"}").uuid("u")));
        invalid(() -> p("{\"u\":\"not-a-uuid\"}").uuid("u"));
    }

    @Test
    void stringListsAreBounded() {
        assertEquals(List.of("a", "b"), p("{\"l\":[\"a\",\"b\"]}").strList("l", 5, 5));
        assertEquals("l has too many entries.", invalid(() -> p("{\"l\":[\"a\",\"b\",\"c\"]}").strList("l", 2, 5)));
        assertEquals("l must contain only text.", invalid(() -> p("{\"l\":[1]}").strList("l", 2, 5)));
        assertEquals("l must be a list.", invalid(() -> p("{\"l\":\"a\"}").strList("l", 2, 5)));
        invalid(() -> p("{\"l\":[\"toolong\"]}").strList("l", 2, 5));
    }
}
