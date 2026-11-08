package dev.breeze.bridge;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RouterTest {

    /** Stands in for Minecraft's client thread: tasks run only when drained. */
    static final class ClientThread implements Executor {
        final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();
        volatile Thread ranOn;

        @Override
        public void execute(Runnable r) {
            queue.add(r);
        }

        int drain() {
            int n = 0;
            Runnable r;
            while ((r = queue.poll()) != null) {
                ranOn = Thread.currentThread();
                r.run();
                n++;
            }
            return n;
        }
    }

    /** Records every answer; fails the test if any query is answered twice. */
    static final class Answers {
        final List<String> log = new CopyOnWriteArrayList<>();
        final CountDownLatch latch;

        Answers(int expected) {
            latch = new CountDownLatch(expected);
        }

        Router.Responder responder(String tag) {
            AtomicInteger calls = new AtomicInteger();
            return new Router.Responder() {
                public void success(String json) {
                    record(tag + " ok " + json);
                }

                public void failure(int code, String message) {
                    record(tag + " fail " + BridgeError.values()[code - 1] + " " + message);
                }

                private void record(String entry) {
                    if (calls.incrementAndGet() > 1) log.add("DOUBLE ANSWER " + entry);
                    else log.add(entry);
                    latch.countDown();
                }
            };
        }

        void await() throws InterruptedException {
            assertTrue(latch.await(5, TimeUnit.SECONDS), "answers: " + log);
        }
    }

    ClientThread client;
    Router router;

    @BeforeEach
    void setUp() {
        client = new ClientThread();
        router = new Router(client);
    }

    @AfterEach
    void tearDown() {
        router.close();
    }

    static String req(long id, String action, String params) {
        return "{\"v\":1,\"id\":" + id + ",\"action\":\"" + action + "\",\"params\":" + params + "}";
    }

    @Test
    void anyThreadAnswersImmediately() throws Exception {
        router.register("app.hello", Router.Thread.ANY, p -> new JsonPrimitive("hi"));
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "app.hello", "{}"), a.responder("q1"));
        a.await();
        assertEquals(List.of("q1 ok \"hi\""), a.log);
        assertEquals(0, router.inFlight());
    }

    @Test
    void clientWorkRunsOnlyOnTheClientThread() throws Exception {
        AtomicReference<Thread> ran = new AtomicReference<>();
        router.register("game.singleplayer", Router.Thread.CLIENT, p -> {
            ran.set(Thread.currentThread());
            return Router.ok();
        });
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "game.singleplayer", "{}"), a.responder("q"));
        assertTrue(a.log.isEmpty(), "must not run before the client thread drains");
        assertEquals(1, router.inFlight());
        Thread drainer = new Thread(client::drain, "fake client thread");
        drainer.start();
        drainer.join();
        a.await();
        assertEquals("fake client thread", ran.get().getName());
        assertEquals(List.of("q ok {}"), a.log);
    }

    @Test
    void ioWorkNeverRunsOnTheCallingThread() throws Exception {
        AtomicReference<Thread> ran = new AtomicReference<>();
        router.register("cosmetics.state", Router.Thread.IO, p -> {
            ran.set(Thread.currentThread());
            return Router.ok();
        });
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "cosmetics.state", "{}"), a.responder("q"));
        a.await();
        assertNotSame(Thread.currentThread(), ran.get());
        assertTrue(ran.get().getName().startsWith("Breeze bridge IO"));
    }

    @Test
    void malformedRequestsAreRefusedWithoutReachingHandlers() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        router.register("app.hello", Router.Thread.ANY, p -> {
            ran.incrementAndGet();
            return Router.ok();
        });
        String[] bad = {
                "", "not json", "[]", "{}",
                "{\"v\":2,\"id\":1,\"action\":\"app.hello\"}",
                "{\"v\":1,\"action\":\"app.hello\"}",
                "{\"v\":1,\"id\":0,\"action\":\"app.hello\"}",
                "{\"v\":1,\"id\":1,\"action\":\"../../x\"}",
                "{\"v\":1,\"id\":1,\"action\":\"app.hello\",\"params\":[1]}",
                "{\"v\":1,\"id\":1,\"action\":\"app.hello\",\"params\":\"" + "x".repeat(70_000) + "\"}",
        };
        Answers a = new Answers(bad.length);
        for (int i = 0; i < bad.length; i++) router.dispatch(i + 1, bad[i], a.responder("q" + i));
        a.await();
        assertEquals(0, ran.get());
        for (String entry : a.log) assertTrue(entry.contains("fail BAD_REQUEST"), entry);
    }

    @Test
    void unknownActionsAreNamed() throws Exception {
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "game.explode", "{}"), a.responder("q"));
        a.await();
        assertTrue(a.log.get(0).startsWith("q fail UNKNOWN_ACTION"), a.log.get(0));
    }

    @Test
    void invalidParamsNameTheField() throws Exception {
        router.register("modules.setEnabled", Router.Thread.ANY, p -> {
            p.str("name", 64);
            p.bool("enabled");
            return Router.ok();
        });
        Answers a = new Answers(2);
        router.dispatch(1, req(1, "modules.setEnabled", "{\"name\":\"FPS\"}"), a.responder("missing"));
        router.dispatch(2, req(2, "modules.setEnabled", "{\"name\":\"FPS\",\"enabled\":\"yes\"}"), a.responder("wrong"));
        a.await();
        assertTrue(a.log.contains("missing fail INVALID_PARAMS Missing enabled."), a.log.toString());
        assertTrue(a.log.contains("wrong fail INVALID_PARAMS enabled must be true or false."), a.log.toString());
    }

    @Test
    void unexpectedExceptionsBecomeInternalWithoutLeakingDetails() throws Exception {
        router.register("app.hello", Router.Thread.ANY, p -> {
            throw new IllegalStateException("secret internal state token=abc");
        });
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "app.hello", "{}"), a.responder("q"));
        a.await();
        assertTrue(a.log.get(0).startsWith("q fail INTERNAL"));
        assertFalse(a.log.get(0).contains("secret"));
    }

    @Test
    void duplicateIdsWhileInFlightAreRefused() throws Exception {
        router.register("game.state", Router.Thread.CLIENT, p -> Router.ok());
        Answers a = new Answers(2);
        router.dispatch(10, req(5, "game.state", "{}"), a.responder("first"));
        router.dispatch(11, req(5, "game.state", "{}"), a.responder("second"));
        client.drain();
        a.await();
        assertTrue(a.log.contains("second fail DUPLICATE That request is already being handled."), a.log.toString());
        assertTrue(a.log.contains("first ok {}"), a.log.toString());
    }

    @Test
    void tooManyInFlightIsBusy() throws Exception {
        router.register("game.state", Router.Thread.CLIENT, p -> Router.ok());
        Answers a = new Answers(Router.MAX_IN_FLIGHT + 1);
        for (int i = 1; i <= Router.MAX_IN_FLIGHT + 1; i++) {
            router.dispatch(i, req(i, "game.state", "{}"), a.responder("q" + i));
        }
        assertTrue(a.log.get(0).startsWith("q" + (Router.MAX_IN_FLIGHT + 1) + " fail BUSY"), a.log.toString());
        client.drain();
        a.await();
        assertEquals(0, router.inFlight());
    }

    @Test
    void slowClientWorkTimesOutAndItsLateResultIsDropped() throws Exception {
        router.register("game.options", Router.Thread.CLIENT, 150, p -> Router.ok());
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "game.options", "{}"), a.responder("q"));
        a.await();
        assertTrue(a.log.get(0).startsWith("q fail TIMEOUT"), a.log.get(0));
        assertEquals(1, client.drain(), "the queued work still runs");
        Thread.sleep(50);
        assertEquals(1, a.log.size(), "but it must not answer a second time: " + a.log);
        assertEquals(0, router.inFlight());
    }

    @Test
    void workQueuedAfterATimeoutIsSkipped() throws Exception {
        AtomicInteger ran = new AtomicInteger();
        router.register("game.options", Router.Thread.CLIENT, 100, p -> {
            ran.incrementAndGet();
            return Router.ok();
        });
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "game.options", "{}"), a.responder("q"));
        a.await();
        client.drain();
        assertEquals(0, ran.get(), "a timed-out request must not still act on the game");
    }

    @Test
    void asyncHandlersAnswerWhenTheirFutureCompletes() throws Exception {
        CompletableFuture<com.google.gson.JsonElement> pending = new CompletableFuture<>();
        router.registerAsync("friends.list", Router.Thread.ANY, p -> pending, 5_000);
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "friends.list", "{}"), a.responder("q"));
        assertTrue(a.log.isEmpty());
        JsonObject o = new JsonObject();
        o.addProperty("n", 3);
        pending.complete(o);
        a.await();
        assertEquals(List.of("q ok {\"n\":3}"), a.log);
    }

    @Test
    void asyncBridgeExceptionsKeepTheirCode() throws Exception {
        router.registerAsync("cosmetics.state", Router.Thread.ANY,
                p -> CompletableFuture.failedFuture(BridgeException.forbidden("Sign in to Breeze first.")), 5_000);
        Answers a = new Answers(1);
        router.dispatch(1, req(1, "cosmetics.state", "{}"), a.responder("q"));
        a.await();
        assertEquals(List.of("q fail FORBIDDEN Sign in to Breeze first."), a.log);
    }

    @Test
    void cancellationAnswersOnceAndCancelsTheWork() throws Exception {
        CompletableFuture<com.google.gson.JsonElement> pending = new CompletableFuture<>();
        router.registerAsync("friends.list", Router.Thread.ANY, p -> pending, 5_000);
        Answers a = new Answers(1);
        router.dispatch(42, req(1, "friends.list", "{}"), a.responder("q"));
        router.cancel(42);
        router.cancel(42);
        a.await();
        assertTrue(pending.isCancelled());
        assertEquals(1, a.log.size(), a.log.toString());
        assertTrue(a.log.get(0).startsWith("q fail CANCELLED"));
    }

    @Test
    void closeAnswersEverythingAndRefusesNewWork() throws Exception {
        router.register("game.state", Router.Thread.CLIENT, p -> Router.ok());
        Answers a = new Answers(3);
        router.dispatch(1, req(1, "game.state", "{}"), a.responder("a"));
        router.dispatch(2, req(2, "game.state", "{}"), a.responder("b"));
        router.close();
        router.close();
        router.dispatch(3, req(3, "game.state", "{}"), a.responder("c"));
        a.await();
        assertEquals(3, a.log.size(), a.log.toString());
        for (String entry : a.log) assertTrue(entry.contains("fail CANCELLED"), entry);
        client.drain();
        Thread.sleep(50);
        assertEquals(3, a.log.size(), "queued work after close must not answer again");
    }

    @Test
    void registeringAnActionTwiceIsAProgrammingError() {
        router.register("app.hello", Router.Thread.ANY, p -> Router.ok());
        assertThrows(IllegalStateException.class, () -> router.register("app.hello", Router.Thread.ANY, p -> Router.ok()));
    }

    @Test
    void aResponderThatThrowsDoesNotBreakTheRouter() throws Exception {
        router.register("app.hello", Router.Thread.ANY, p -> Router.ok());
        router.dispatch(1, req(1, "app.hello", "{}"), new Router.Responder() {
            public void success(String json) {
                throw new IllegalStateException("browser gone");
            }

            public void failure(int code, String message) {
                throw new IllegalStateException("browser gone");
            }
        });
        assertEquals(0, router.inFlight());
        Answers a = new Answers(1);
        router.dispatch(2, req(2, "app.hello", "{}"), a.responder("next"));
        a.await();
        assertEquals(List.of("next ok {}"), a.log);
    }

    @Test
    void theTapSeesAcceptedRequestsOnlyAndCannotBreakThem() throws Exception {
        router.register("ui.route", Router.Thread.ANY, p -> Router.ok());
        List<String> seen = new CopyOnWriteArrayList<>();
        Router.tap = (action, params) -> {
            seen.add(action + " " + params.raw());
            throw new IllegalStateException("observer bug");
        };
        try {
            Answers a = new Answers(2);
            router.dispatch(1, req(1, "ui.route", "{\"route\":\"mods\"}"), a.responder("ok"));
            router.dispatch(2, "garbage", a.responder("bad"));
            a.await();
            assertEquals(List.of("ui.route {\"route\":\"mods\"}"), seen);
            assertTrue(a.log.contains("ok ok {}"), a.log.toString());
        } finally {
            Router.tap = null;
        }
    }
}
