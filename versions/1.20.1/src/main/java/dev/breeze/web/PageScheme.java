package dev.breeze.web;

import dev.breeze.BreezeClient;
import dev.breeze.bridge.PageOrigin;
import dev.breeze.bridge.PageResources;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefCallback;
import org.cef.callback.CefSchemeHandlerFactory;
import org.cef.handler.CefResourceHandler;
import org.cef.misc.IntRef;
import org.cef.misc.StringRef;
import org.cef.network.CefRequest;
import org.cef.network.CefResponse;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves the interface to CEF from this mod's jar at https://breeze.local/.
 *
 * MCEF has its own mod:// scheme, but it lowercases every path, looks the file
 * up with a leading slash (which a packed jar's class loader may not resolve),
 * and has no MIME type for web fonts. Registering a handler for one https host
 * sidesteps all three, gives the page a secure origin, and keeps every
 * decision about what may be served in {@link PageResources}, where it is
 * unit-tested.
 */
final class PageScheme implements CefSchemeHandlerFactory {

    @Override
    public CefResourceHandler create(CefBrowser browser, CefFrame frame, String schemeName, CefRequest request) {
        return new Handler();
    }

    private static final class Handler implements CefResourceHandler {
        private InputStream in;
        private String mime = "text/plain";
        private int status = 404;

        @Override
        public boolean processRequest(CefRequest request, CefCallback callback) {
            PageResources.Resource r = "GET".equalsIgnoreCase(request.getMethod())
                    ? PageResources.resolve(request.getURL()) : null;
            if (r != null) {
                in = PageScheme.class.getClassLoader().getResourceAsStream(r.classpathPath());
                if (in != null) {
                    mime = r.mimeType();
                    status = 200;
                }
            }
            if (status != 200) {
                BreezeClient.LOGGER.warn("[Breeze] interface file not served: {}", request.getURL());
            }
            callback.Continue();
            return true;
        }

        @Override
        public void getResponseHeaders(CefResponse response, IntRef length, StringRef redirect) {
            response.setStatus(status);
            response.setStatusText(status == 200 ? "OK" : "Not Found");
            response.setMimeType(mime);
            response.setHeaderByName("Cache-Control", "no-store", true);
            response.setHeaderByName("X-Content-Type-Options", "nosniff", true);
            length.set(-1);
        }

        @Override
        public boolean readResponse(byte[] out, int bytesToRead, IntRef bytesRead, CefCallback callback) {
            if (in == null) {
                bytesRead.set(0);
                return false;
            }
            try {
                int n = in.read(out, 0, bytesToRead);
                if (n <= 0) {
                    close();
                    bytesRead.set(0);
                    return false;
                }
                bytesRead.set(n);
                return true;
            } catch (IOException e) {
                close();
                bytesRead.set(0);
                return false;
            }
        }

        @Override
        public void cancel() {
            close();
        }

        private void close() {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            in = null;
        }
    }

    /** Registers the handler with CEF. Call once, after CEF has initialised. */
    static boolean register(org.cef.CefApp app) {
        boolean ok = app.registerSchemeHandlerFactory(PageOrigin.SCHEME, PageOrigin.HOST, new PageScheme());
        if (!ok) BreezeClient.LOGGER.error("[Breeze] could not register the interface at {}", PageOrigin.ORIGIN);
        return ok;
    }
}
