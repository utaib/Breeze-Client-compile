package dev.breeze.web;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * How many interface browsers are open. Kept apart from BreezeBrowser, which
 * uses MCEF's classes, so that it can be read on a game without MCEF (the
 * self-test reports it on every screen change).
 */
public final class BrowserCount {

    static final AtomicInteger LIVE = new AtomicInteger();

    private BrowserCount() {}

    public static int live() {
        return LIVE.get();
    }
}
