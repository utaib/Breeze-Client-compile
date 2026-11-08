package dev.breeze.commands;

import dev.breeze.commands.CommandKeys.Binding;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommandKeysTest {

    private static String refusal(String raw) {
        return assertThrows(IllegalArgumentException.class, () -> CommandKeys.cleanCommand(raw)).getMessage();
    }

    @Test
    void commandsAreOneCleanLineWithoutTheSlash() {
        assertEquals("spawn", CommandKeys.cleanCommand("/spawn"));
        assertEquals("tp ~ ~10 ~", CommandKeys.cleanCommand("  //tp ~ ~10 ~  "));
        assertEquals("Type a command.", refusal("   /  "));
        assertEquals("Type a command.", refusal(null));
        assertTrue(refusal("say §chi").contains("Formatting"));
        assertEquals("A command is one line.", refusal("say a\nsay b"));
        assertTrue(refusal("say a\u0007").contains("control"));
        assertTrue(refusal("x".repeat(CommandKeys.MAX_COMMAND + 1)).contains(String.valueOf(CommandKeys.MAX_COMMAND)));
        assertEquals("x".repeat(CommandKeys.MAX_COMMAND), CommandKeys.cleanCommand("x".repeat(CommandKeys.MAX_COMMAND)));
    }

    @Test
    void theNameIsTheFirstWord() {
        assertEquals("spawn", CommandKeys.name("spawn"));
        assertEquals("gamemode", CommandKeys.name("/gamemode creative"));
        assertEquals("", CommandKeys.name(null));
    }

    @Test
    void firesOnThePressOnlyWithExactlyItsModifiers() {
        Binding b = new Binding("c1", "spawn", "key.keyboard.k", true, false, false, true);
        assertTrue(CommandKeys.fires(b, true, false, true, false, false));
        assertFalse(CommandKeys.fires(b, true, true, true, false, false), "held down: not again");
        assertFalse(CommandKeys.fires(b, true, false, false, false, false), "Ctrl missing");
        assertFalse(CommandKeys.fires(b, true, false, true, true, false), "Shift as well");
        assertFalse(CommandKeys.fires(b.withEnabled(false), true, false, true, false, false));
        assertFalse(CommandKeys.fires(b.withKey("", false, false, false), true, false, false, false, false));
    }

    @Test
    void theSavedFileIsReadWithoutTrustingIt() {
        String json = "{\"version\":1,\"bindings\":["
                + "{\"id\":\"c1\",\"command\":\"/spawn\",\"key\":\"key.keyboard.k\",\"ctrl\":true},"
                + "{\"id\":\"c1\",\"command\":\"home\",\"key\":\"key.keyboard.h\"},"            // duplicate id
                + "{\"id\":\"c2\",\"command\":\"say §c\",\"key\":\"key.keyboard.j\"},"       // refused command
                + "{\"id\":\"c3\",\"command\":\"home\",\"key\":\"key.mouse.left\",\"alt\":true}," // not a keyboard key
                + "{\"id\":\"bad id!\",\"command\":\"home\"},"
                + "\"text\","
                + "{\"id\":\"c4\",\"command\":\"warp hub\",\"key\":\"key.keyboard.g\",\"enabled\":false}"
                + "]}";
        List<Binding> got = CommandKeys.parse(json);
        assertEquals(3, got.size());
        assertEquals(new Binding("c1", "spawn", "key.keyboard.k", true, false, false, true), got.get(0));
        assertEquals(new Binding("c3", "home", "", false, false, false, true), got.get(1), "an odd key leaves it unbound");
        assertEquals(new Binding("c4", "warp hub", "key.keyboard.g", false, false, false, false), got.get(2));
        assertEquals(got, CommandKeys.parse(CommandKeys.write(got)));

        assertTrue(CommandKeys.parse("not json").isEmpty());
        assertTrue(CommandKeys.parse("[1,2]").isEmpty());
        assertTrue(CommandKeys.parse(null).isEmpty());

        StringBuilder many = new StringBuilder("{\"bindings\":[");
        for (int i = 0; i < CommandKeys.MAX_BINDINGS + 5; i++) {
            if (i > 0) many.append(',');
            many.append("{\"id\":\"c").append(i).append("\",\"command\":\"spawn\"}");
        }
        assertEquals(CommandKeys.MAX_BINDINGS, CommandKeys.parse(many.append("]}").toString()).size());
    }

    @Test
    void chordsAndIds() {
        Binding a = new Binding("c1", "spawn", "key.keyboard.k", true, false, false, true);
        assertTrue(a.sameChord(new Binding("c2", "home", "key.keyboard.k", true, false, false, false)));
        assertFalse(a.sameChord(new Binding("c2", "home", "key.keyboard.k", false, false, false, true)));
        assertFalse(a.withKey("", false, false, false).sameChord(a.withKey("", false, false, false)), "unbound never clashes");
        assertEquals("c2", CommandKeys.newId(List.of(a, new Binding("c3", "x", "", false, false, false, true))));
    }
}
