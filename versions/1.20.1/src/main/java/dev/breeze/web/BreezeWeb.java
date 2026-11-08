package dev.breeze.web;

import com.cinemamod.mcef.MCEF;
import dev.breeze.BreezeClient;
import dev.breeze.bridge.PageOrigin;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.browser.CefMessageRouter;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;

/**
 * One-time CEF setup for the Breeze interface: the page origin and the bridge.
 *
 * JCEF hands a message router's JavaScript functions to a render process when
 * that process starts, so the router must exist before any Breeze browser is
 * created and must outlive them all. It is therefore created once here, and
 * each open screen attaches its {@link BreezeBrowser} as the current session.
 *
 * The functions are named breezeQuery and breezeQueryCancel rather than
 * JCEF's default cefQuery, so another mod that also uses MCEF cannot collide
 * with them. Every query is still checked: it must come from the current
 * session's own browser, from a frame on https://breeze.local. Anything else is
 * declined, which leaves it for other routers and gives the caller nothing.
 */
final class BreezeWeb {

    static final String QUERY = "breezeQuery";
    static final String QUERY_CANCEL = "breezeQueryCancel";

    private static volatile boolean installed;
    private static volatile BreezeBrowser session;

    private BreezeWeb() {}

    /** Called by WebInit once CEF reports ready. Idempotent. */
    static synchronized boolean install() {
        if (installed) return true;
        try {
            if (!PageScheme.register(MCEF.getApp().getHandle())) return false;
            CefMessageRouter router = CefMessageRouter.create(new CefMessageRouter.CefMessageRouterConfig(QUERY, QUERY_CANCEL));
            router.addHandler(new CefMessageRouterHandlerAdapter() {
                @Override
                public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String request,
                                       boolean persistent, CefQueryCallback callback) {
                    BreezeBrowser s = session;
                    if (s == null || persistent || !s.owns(browser) || frame == null || !PageOrigin.trusted(frame.getURL())) {
                        return false;
                    }
                    s.dispatch(queryId, request, callback);
                    return true;
                }

                @Override
                public void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
                    BreezeBrowser s = session;
                    if (s != null && s.owns(browser)) s.cancel(queryId);
                }
            }, true);
            MCEF.getClient().getHandle().addMessageRouter(router);
            installed = true;
            BreezeClient.LOGGER.info("[Breeze] interface ready at {} with bridge {}", PageOrigin.ORIGIN, QUERY);
            return true;
        } catch (Throwable t) {
            BreezeClient.LOGGER.error("[Breeze] could not set up the interface bridge: {}", t.toString());
            return false;
        }
    }

    static boolean installed() {
        return installed;
    }

    static void attach(BreezeBrowser s) {
        BreezeBrowser previous = session;
        session = s;
        // Only one Breeze browser may be live. If a previous one was somehow
        // not closed, close it now rather than leak a Chromium process.
        if (previous != null && previous != s) previous.close();
    }

    static void detach(BreezeBrowser s) {
        if (session == s) session = null;
    }

    static BreezeBrowser current() {
        return session;
    }
}
