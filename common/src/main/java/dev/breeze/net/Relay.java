package dev.breeze.net;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;

public final class Relay {

    /** Bounds the TLS handshake and the upgrade reply; the tunnel itself has no read timeout. */
    private static final int HANDSHAKE_TIMEOUT_MS = 10_000;

    private Relay() {}

    public static String host() {
        String h = BreezeApi.baseUri().getHost();
        return h == null ? "" : h;
    }

    public static int port() {
        URI u = BreezeApi.baseUri();
        if (u.getPort() > 0) return u.getPort();
        return secure() ? 443 : 80;
    }

    private static boolean secure() {
        return "https".equalsIgnoreCase(BreezeApi.baseUri().getScheme());
    }

    public static Socket open(String xbreeze) throws Exception {
        String host = host();
        int port = port();
        Socket raw = new Socket();
        Socket sock = raw;
        try {
            raw.connect(new InetSocketAddress(host, port), 6000);
            raw.setTcpNoDelay(true);
            raw.setSoTimeout(HANDSHAKE_TIMEOUT_MS);
            if (secure()) {
                // The relay carries a friend's whole game connection and, since
                // v1.0.22, the player's game token, so an https base means real
                // TLS with the certificate checked against the host name. A
                // default SSLSocket encrypts but does not verify the name, which
                // would accept any valid certificate for any domain.
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault())
                        .createSocket(raw, host, port, true);
                sock = tls;
                SSLParameters params = tls.getSSLParameters();
                try {
                    params.setServerNames(List.of(new SNIHostName(host)));
                } catch (IllegalArgumentException notAHostName) {
                    // An IP literal cannot be sent as SNI. Verification below
                    // still applies.
                }
                params.setEndpointIdentificationAlgorithm("HTTPS");
                tls.setSSLParameters(params);
                // Complete it before anything is written, so a failed
                // verification surfaces here and not as a confusing I/O error
                // halfway through the upgrade request.
                tls.startHandshake();
            }

            StringBuilder req = new StringBuilder()
                    .append("GET /breeze-relay HTTP/1.1\r\n")
                    .append("Host: ").append(host).append("\r\n")
                    .append("Upgrade: breeze\r\n")
                    .append("Connection: Upgrade\r\n");
            String token = BreezeApi.gameToken();
            // A token is a JWT and never contains a line break; checking anyway
            // keeps a malformed server reply from injecting request headers.
            if (token != null && token.indexOf('\r') < 0 && token.indexOf('\n') < 0) {
                req.append("Authorization: Bearer ").append(token).append("\r\n");
            }
            req.append("X-Breeze: ").append(xbreeze).append("\r\n\r\n");

            OutputStream out = sock.getOutputStream();
            out.write(req.toString().getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = sock.getInputStream();
            StringBuilder head = new StringBuilder();
            int b;
            while ((b = in.read()) != -1) {
                head.append((char) b);
                int len = head.length();
                if (len >= 4 && head.charAt(len - 4) == '\r' && head.charAt(len - 3) == '\n'
                        && head.charAt(len - 2) == '\r' && head.charAt(len - 1) == '\n') break;
            }
            if (head.indexOf("101") < 0) {
                throw new Exception("relay handshake failed");
            }
            // The control socket idles between joins, so the tunnel must not
            // inherit the handshake timeout.
            sock.setSoTimeout(0);
            return sock;
        } catch (Exception e) {
            try { sock.close(); } catch (Throwable ignored) {}
            try { raw.close(); } catch (Throwable ignored) {}
            throw e;
        }
    }

    public static void pump(Socket a, Socket b) {
        Thread t1 = new Thread(() -> copy(a, b), "Breeze-Relay-AB");
        Thread t2 = new Thread(() -> copy(b, a), "Breeze-Relay-BA");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();
    }

    private static void copy(Socket from, Socket to) {
        byte[] buf = new byte[8192];
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                out.flush();
            }
        } catch (Throwable ignored) {
        } finally {
            try { from.close(); } catch (Throwable ignored) {}
            try { to.close(); } catch (Throwable ignored) {}
        }
    }
}
