package dev.breeze.bridge;

/**
 * Failure codes, in the exact order of "errors" in contract/bridge.json.
 *
 * The page receives the number (ordinal + 1) through JCEF's failure callback
 * and maps it back by position, so the order is part of the protocol. A test
 * compares this enum with the contract file.
 */
public enum BridgeError {
    BAD_REQUEST,
    UNKNOWN_ACTION,
    INVALID_PARAMS,
    UNAVAILABLE,
    FORBIDDEN,
    TIMEOUT,
    BUSY,
    DUPLICATE,
    CANCELLED,
    INTERNAL;

    /** The number sent to JavaScript. Never 0, which JCEF reserves. */
    public int code() {
        return ordinal() + 1;
    }
}
