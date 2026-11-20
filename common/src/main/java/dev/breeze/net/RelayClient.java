package dev.breeze.net;

import dev.breeze.Log;


import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

public final class RelayClient {

    private static ServerSocket local;

    private RelayClient() {}

    public static synchronized int startJoin(String session) {
        stop();
        try {
            ServerSocket ss = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            local = ss;
            Thread t = new Thread(() -> acceptLoop(ss, session), "Breeze-Join-" + session);
            t.setDaemon(true);
            t.start();
            return ss.getLocalPort();
        } catch (Throwable e) {
            Log.warn("[Breeze] join proxy failed: {}", e.toString());
            return -1;
        }
    }

    private static void acceptLoop(ServerSocket ss, String session) {
        while (!ss.isClosed()) {
            try {
                Socket vanilla = ss.accept();
                vanilla.setTcpNoDelay(true);
                new Thread(() -> {
                    try {
                        Socket relay = Relay.open("JOIN " + session);
                        Log.info("[Breeze] join: relay tunnel open for session {}", session);
                        Relay.pump(vanilla, relay);
                    } catch (Throwable e) {
                        Log.warn("[Breeze] join: relay tunnel failed: {}", e.toString());
                        try { vanilla.close(); } catch (Throwable ignored) {}
                    }
                }, "Breeze-Join-Conn").start();
            } catch (Throwable e) {
                break;
            }
        }
    }

    public static synchronized void stop() {
        if (local != null) {
            try { local.close(); } catch (Throwable ignored) {}
            local = null;
        }
    }
}
