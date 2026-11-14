package dev.breeze.web;

/**
 * BreezeWeb where there is no embedded browser: nothing to install, and never a
 * page attached. See WebInit in this folder.
 */
final class BreezeWeb {

    private BreezeWeb() {}

    static boolean install() {
        return false;
    }

    static boolean installed() {
        return false;
    }

    static void attach(BreezeBrowser s) {
    }

    static void detach(BreezeBrowser s) {
    }

    static BreezeBrowser current() {
        return null;
    }
}
