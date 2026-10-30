package dev.breeze.bridge;

/**
 * A failure the page should see as it is. The message is shown to the player,
 * so it is written for them: what went wrong and what to do, never a stack
 * trace or an internal class name.
 */
public final class BridgeException extends RuntimeException {

    private final BridgeError error;

    public BridgeException(BridgeError error, String message) {
        super(message, null, false, false);
        this.error = error;
    }

    public BridgeError error() {
        return error;
    }

    public static BridgeException invalid(String message) {
        return new BridgeException(BridgeError.INVALID_PARAMS, message);
    }

    public static BridgeException unavailable(String message) {
        return new BridgeException(BridgeError.UNAVAILABLE, message);
    }

    public static BridgeException forbidden(String message) {
        return new BridgeException(BridgeError.FORBIDDEN, message);
    }
}
