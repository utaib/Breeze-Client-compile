package dev.breeze.compat;

import net.minecraft.resources.ResourceLocation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * Version-agnostic {@link ResourceLocation} construction.
 *
 * Mojang made the two-argument constructor private in 1.21 and replaced it with
 * the static factory {@code fromNamespaceAndPath}. Neither form exists on both
 * sides, so a single source tree cannot call either directly and still compile
 * for 1.20.x and 1.21.x.
 *
 * Resolving the right one reflectively removes that as a porting blocker. The
 * lookup happens once in a static initialiser and is cached in a MethodHandle,
 * and every call site in this mod is a {@code static final} field, so the cost
 * is paid once at class load and never on a render path.
 *
 * This is deliberately the ONLY place in the mod that knows about the
 * difference. When 1.20.x support is eventually dropped, delete this class and
 * inline the factory; nothing else has to change.
 */
public final class Ids {

    private static final MethodHandle FACTORY;
    private static final boolean USES_STATIC_FACTORY;

    static {
        MethodHandle handle = null;
        boolean staticFactory = false;
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            // 1.21+ path.
            handle = lookup.findStatic(ResourceLocation.class, "fromNamespaceAndPath",
                    MethodType.methodType(ResourceLocation.class, String.class, String.class));
            staticFactory = true;
        } catch (NoSuchMethodException | IllegalAccessException ignored) {
            try {
                // 1.20.x and earlier.
                handle = lookup.findConstructor(ResourceLocation.class,
                        MethodType.methodType(void.class, String.class, String.class));
            } catch (NoSuchMethodException | IllegalAccessException e) {
                throw new ExceptionInInitializerError(
                        "Neither ResourceLocation.fromNamespaceAndPath nor the (String, String) "
                        + "constructor is available. Breeze cannot build identifiers on this "
                        + "Minecraft version: " + e);
            }
        }
        FACTORY = handle;
        USES_STATIC_FACTORY = staticFactory;
    }

    private Ids() {}

    /** A ResourceLocation in the given namespace, on any supported version. */
    public static ResourceLocation of(String namespace, String path) {
        try {
            return (ResourceLocation) FACTORY.invoke(namespace, path);
        } catch (Throwable t) {
            // A MethodHandle that resolved at class load should not fail here.
            // If it does, the identifier is unusable and failing loudly beats
            // returning a wrong texture.
            throw new IllegalStateException("Could not build ResourceLocation " + namespace + ":" + path, t);
        }
    }

    /** Everything Breeze owns lives under the "breeze" namespace. */
    public static ResourceLocation breeze(String path) {
        return of("breeze", path);
    }

    /** Which API this Minecraft version exposes. Useful in a debug readout. */
    public static String resolvedStrategy() {
        return USES_STATIC_FACTORY ? "ResourceLocation.fromNamespaceAndPath (1.21+)"
                                   : "new ResourceLocation(String, String) (1.20.x and earlier)";
    }
}
