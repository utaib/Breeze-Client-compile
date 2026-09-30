package dev.breeze.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.*;

/** Java against contract/bridge.json, the file the TypeScript side is tested against too. */
class ContractTest {

    static JsonObject contract() throws Exception {
        Path file = Path.of(System.getProperty("user.dir")).resolve("../contract/bridge.json").normalize();
        return JsonParser.parseString(Files.readString(file)).getAsJsonObject();
    }

    @Test
    void errorCodesAreInContractOrder() throws Exception {
        JsonArray errors = contract().getAsJsonArray("errors");
        List<String> names = new ArrayList<>();
        for (JsonElement e : errors) names.add(e.getAsString());
        List<String> java = new ArrayList<>();
        for (BridgeError e : BridgeError.values()) java.add(e.name());
        assertEquals(names, java);
        assertEquals(1, BridgeError.BAD_REQUEST.code());
    }

    @Test
    void actionListMatchesTheContract() throws Exception {
        Set<String> contract = new TreeSet<>(contract().getAsJsonObject("actions").keySet());
        assertEquals(contract, new TreeSet<>(BridgeActions.ALL));
    }

    @Test
    void everyContractActionDeclaresAKnownThread() throws Exception {
        JsonObject actions = contract().getAsJsonObject("actions");
        for (String name : actions.keySet()) {
            String thread = actions.getAsJsonObject(name).get("thread").getAsString();
            assertNotNull(BridgeActions.thread(name), name);
            assertEquals(thread, BridgeActions.thread(name).name().toLowerCase(), name);
        }
    }

    @Test
    void eventNamesMatchTheContract() throws Exception {
        assertEquals(contract().getAsJsonObject("events").keySet(), Events.NAMES);
    }

    @Test
    void limitsMatchTheContract() throws Exception {
        JsonObject limits = contract().getAsJsonObject("limits");
        assertEquals(limits.get("maxRequestBytes").getAsInt(), Request.MAX_BYTES);
        assertEquals(limits.get("maxInFlight").getAsInt(), Router.MAX_IN_FLIGHT);
        assertEquals(contract().get("protocol").getAsInt(), Request.PROTOCOL);
    }

    @Test
    void aRouterWithEveryActionIsComplete() {
        Router r = new Router(Runnable::run);
        try {
            for (String a : BridgeActions.ALL) r.register(a, BridgeActions.thread(a), p -> Router.ok());
            assertEquals(Set.of(), r.missing(BridgeActions.ALL));
        } finally {
            r.close();
        }
    }
}
