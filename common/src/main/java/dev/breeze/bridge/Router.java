package dev.breeze.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.breeze.Log;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Dispatches page requests to handlers and guarantees every request is
 * answered exactly once.
 *
 * <p>Threading is declared per action, never decided by the handler:
 * <ul>
 *   <li>{@link Thread#ANY}: runs on the calling thread (CEF's). For cheap reads
 *       of state that is already thread-safe.</li>
 *   <li>{@link Thread#CLIENT}: runs on Minecraft's client thread through the
 *       executor the version module supplies. Anything that touches the game.</li>
 *   <li>{@link Thread#IO}: runs on the router's own IO pool. Network calls go
 *       here, so neither CEF nor the game thread ever waits on the network.</li>
 * </ul>
 *
 * <p>No thread ever blocks waiting for another: answers are delivered by
 * callback when the work completes, and a timeout answers on the page's
 * behalf when it does not. Work that outlives its timeout still finishes, but
 * its result is dropped, because the page has already been told.
 */
public final class Router implements AutoCloseable {

    public enum Thread { ANY, CLIENT, IO }

    @FunctionalInterface
    public interface Handler {
        JsonElement handle(Params params) throws Exception;
    }

    @FunctionalInterface
    public interface AsyncHandler {
        CompletableFuture<JsonElement> handle(Params params) throws Exception;
    }

    /** Where an answer goes. JCEF's CefQueryCallback, adapted. */
    public interface Responder {
        void success(String json);

        void failure(int code, String message);
    }

    public static final int MAX_IN_FLIGHT = 64;

    /**
     * Observes every accepted request (action name and validated params) for
     * the development self-test. Null in normal play. Must not block.
     */
    public static volatile java.util.function.BiConsumer<String, Params> tap;

    private static final Gson GSON = new Gson();
    private static final String INTERNAL_MESSAGE = "Something went wrong inside Breeze. The game log has the details.";

    private record Route(Thread thread, AsyncHandler handler, long timeoutMs) {}

    private final class Call {
        final long queryId;
        final long requestId;
        final String action;
        final Responder responder;
        final AtomicBoolean done = new AtomicBoolean();
        volatile ScheduledFuture<?> timeout;
        volatile CompletableFuture<JsonElement> work;

        Call(long queryId, long requestId, String action, Responder responder) {
            this.queryId = queryId;
            this.requestId = requestId;
            this.action = action;
            this.responder = responder;
        }

        void succeed(JsonElement result) {
            if (!done.compareAndSet(false, true)) return;
            release();
            String json = result == null || result.isJsonNull() ? "{}" : GSON.toJson(result);
            deliver(() -> responder.success(json));
        }

        void fail(BridgeError error, String message) {
            if (!done.compareAndSet(false, true)) return;
            release();
            deliver(() -> responder.failure(error.code(), message));
        }

        private void release() {
            ScheduledFuture<?> t = timeout;
            if (t != null) t.cancel(false);
            inflight.remove(requestId, this);
            byQuery.remove(queryId, this);
        }

        private void deliver(Runnable r) {
            try {
                r.run();
            } catch (Throwable t) {
                // The browser may be gone by the time an answer is ready.
                Log.warn("[Breeze] bridge answer for {} could not be delivered: {}", action, t.toString());
            }
        }
    }

    private final Executor client;
    private final ExecutorService io;
    private final ScheduledExecutorService timer;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Map<Long, Call> inflight = new ConcurrentHashMap<>();
    private final Map<Long, Call> byQuery = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * @param client runs a task on Minecraft's client thread (Minecraft::execute)
     */
    public Router(Executor client) {
        this.client = client;
        this.io = Executors.newFixedThreadPool(2, daemon("Breeze bridge IO"));
        this.timer = Executors.newSingleThreadScheduledExecutor(daemon("Breeze bridge timer"));
    }

    private static ThreadFactory daemon(String name) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            java.lang.Thread t = new java.lang.Thread(r, name + " " + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    public static long defaultTimeout(Thread thread) {
        switch (thread) {
            case ANY: return 2_000;
            case CLIENT: return 5_000;
            default: return 15_000;
        }
    }

    public Router register(String action, Thread thread, Handler handler) {
        return registerAsync(action, thread, p -> CompletableFuture.completedFuture(handler.handle(p)), defaultTimeout(thread));
    }

    public Router register(String action, Thread thread, long timeoutMs, Handler handler) {
        return registerAsync(action, thread, p -> CompletableFuture.completedFuture(handler.handle(p)), timeoutMs);
    }

    public Router registerAsync(String action, Thread thread, AsyncHandler handler, long timeoutMs) {
        if (routes.putIfAbsent(action, new Route(thread, handler, timeoutMs)) != null) {
            throw new IllegalStateException("Action registered twice: " + action);
        }
        return this;
    }

    public Set<String> actions() {
        return Collections.unmodifiableSet(new TreeSet<>(routes.keySet()));
    }

    /** Actions in the contract with no handler. Empty when complete. */
    public Set<String> missing(Set<String> contract) {
        Set<String> out = new TreeSet<>(contract);
        out.removeAll(routes.keySet());
        return out;
    }

    public int inFlight() {
        return inflight.size();
    }

    /**
     * Handle one request. The responder is called exactly once, possibly on
     * another thread, possibly before this method returns.
     *
     * @param queryId CEF's id for the query, used for cancellation
     */
    public void dispatch(long queryId, String raw, Responder responder) {
        if (closed) {
            safeFail(responder, BridgeError.CANCELLED, "The Breeze menu is closing.");
            return;
        }
        Request request;
        try {
            request = Request.parse(raw);
        } catch (BridgeException e) {
            safeFail(responder, e.error(), e.getMessage());
            return;
        }
        Route route = routes.get(request.action);
        if (route == null) {
            safeFail(responder, BridgeError.UNKNOWN_ACTION, "Breeze does not know how to do " + request.action + " here.");
            return;
        }
        if (inflight.size() >= MAX_IN_FLIGHT) {
            safeFail(responder, BridgeError.BUSY, "Breeze is busy. Try again in a moment.");
            return;
        }
        Call call = new Call(queryId, request.id, request.action, responder);
        if (inflight.putIfAbsent(request.id, call) != null) {
            safeFail(responder, BridgeError.DUPLICATE, "That request is already being handled.");
            return;
        }
        byQuery.put(queryId, call);
        java.util.function.BiConsumer<String, Params> t = tap;
        if (t != null) {
            try {
                t.accept(request.action, request.params);
            } catch (Throwable ignored) {
                // a test observer must never affect the request
            }
        }

        try {
            call.timeout = timer.schedule(
                    () -> call.fail(BridgeError.TIMEOUT, "Minecraft did not finish " + request.action + " in time."),
                    route.timeoutMs(), TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException shuttingDown) {
            call.fail(BridgeError.CANCELLED, "The Breeze menu is closing.");
            return;
        }

        Runnable run = () -> execute(call, route, request.params);
        try {
            switch (route.thread()) {
                case ANY: run.run(); break;
                case CLIENT: client.execute(run); break;
                case IO: io.execute(run); break;
            }
        } catch (RejectedExecutionException e) {
            call.fail(BridgeError.UNAVAILABLE, "Minecraft is not accepting work right now.");
        }
    }

    private void execute(Call call, Route route, Params params) {
        if (call.done.get()) return; // timed out or cancelled while queued
        try {
            CompletableFuture<JsonElement> f = route.handler().handle(params);
            call.work = f;
            f.whenComplete((result, error) -> {
                if (error == null) call.succeed(result);
                else fail(call, unwrap(error));
            });
        } catch (Throwable t) {
            fail(call, t);
        }
    }

    private static Throwable unwrap(Throwable t) {
        while ((t instanceof java.util.concurrent.CompletionException || t instanceof java.util.concurrent.ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    private static void fail(Call call, Throwable t) {
        if (t instanceof BridgeException b) {
            call.fail(b.error(), b.getMessage());
        } else if (t instanceof java.util.concurrent.CancellationException) {
            call.fail(BridgeError.CANCELLED, call.action + " was cancelled.");
        } else {
            // Params are deliberately not logged: they may carry player input.
            Log.warn("[Breeze] bridge action {} failed: {}", call.action, t.toString());
            call.fail(BridgeError.INTERNAL, INTERNAL_MESSAGE);
        }
    }

    private static void safeFail(Responder r, BridgeError error, String message) {
        try {
            r.failure(error.code(), message);
        } catch (Throwable ignored) {
            // browser already gone
        }
    }

    /** The page cancelled a query (cefQueryCancel, or the frame navigated away). */
    public void cancel(long queryId) {
        Call call = byQuery.get(queryId);
        if (call == null) return;
        CompletableFuture<JsonElement> w = call.work;
        if (w != null) w.cancel(false);
        call.fail(BridgeError.CANCELLED, call.action + " was cancelled.");
    }

    /**
     * Answer everything still pending and refuse new work. Called when the
     * browser closes. Idempotent.
     */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        for (Call call : inflight.values()) call.fail(BridgeError.CANCELLED, "The Breeze menu closed.");
        timer.shutdownNow();
        io.shutdown();
    }

    public boolean isClosed() {
        return closed;
    }

    /** Convenience for handlers with nothing to return. */
    public static JsonObject ok() {
        return new JsonObject();
    }
}
