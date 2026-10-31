package dev.breeze.integrations;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls into Mod Menu's published API (com.terraformersmc.modmenu.api) by
 * name, so Breeze neither depends on Mod Menu nor copies it: with Mod Menu
 * installed its own screens open, without it nothing here is touched.
 *
 * The names are Mod Menu's own and are not remapped by Fabric, and the screen
 * type is passed in as a class, so the same calls work on every Minecraft
 * version Mod Menu supports. The API, as Mod Menu publishes it:
 *
 * <pre>
 * interface ModMenuApi {
 *     static Screen createModsScreen(Screen previous);
 *     default ConfigScreenFactory&lt;?&gt; getModConfigScreenFactory();
 *     default Map&lt;String, ConfigScreenFactory&lt;?&gt;&gt; getProvidedConfigScreenFactories();
 * }
 * interface ConfigScreenFactory&lt;S extends Screen&gt; { S create(Screen parent); }
 * </pre>
 */
public final class ModMenuApiCalls {

    public static final String API = "com.terraformersmc.modmenu.api.ModMenuApi";
    public static final String FACTORY = "com.terraformersmc.modmenu.api.ConfigScreenFactory";
    /** The Fabric entrypoint Mod Menu reads each mod's settings screen from. */
    public static final String ENTRYPOINT = "modmenu";

    private final Method createModsScreen;
    private final Method getModConfigScreenFactory;
    private final Method getProvidedConfigScreenFactories;
    private final Method create;

    private ModMenuApiCalls(Method createModsScreen, Method getModConfigScreenFactory,
                            Method getProvidedConfigScreenFactories, Method create) {
        this.createModsScreen = createModsScreen;
        this.getModConfigScreenFactory = getModConfigScreenFactory;
        this.getProvidedConfigScreenFactories = getProvidedConfigScreenFactories;
        this.create = create;
    }

    /**
     * Finds the API in the given class loader. Throws when Mod Menu is there
     * but does not have the API Breeze calls (an unexpected version).
     */
    public static ModMenuApiCalls find(ClassLoader loader, Class<?> screenClass) throws ReflectiveOperationException {
        Class<?> api = Class.forName(API, false, loader);
        Class<?> factory = Class.forName(FACTORY, false, loader);
        return new ModMenuApiCalls(
                api.getMethod("createModsScreen", screenClass),
                api.getMethod("getModConfigScreenFactory"),
                optional(api, "getProvidedConfigScreenFactories"),
                factory.getMethod("create", screenClass));
    }

    private static Method optional(Class<?> c, String name) {
        try {
            return c.getMethod(name);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    /** Mod Menu's list of mods, returning to {@code parent} when closed. */
    public Object modsScreen(Object parent) throws ReflectiveOperationException {
        return createModsScreen.invoke(null, parent);
    }

    /**
     * The settings-screen factories one "modmenu" entrypoint offers: its own
     * mod's, and any it provides for other mods. Keyed by mod id.
     */
    public Map<String, Object> factories(String providerModId, Object entrypoint) throws ReflectiveOperationException {
        Map<String, Object> out = new LinkedHashMap<>();
        Object own = getModConfigScreenFactory.invoke(entrypoint);
        if (own != null) out.put(providerModId, own);
        if (getProvidedConfigScreenFactories != null) {
            Object provided = getProvidedConfigScreenFactories.invoke(entrypoint);
            if (provided instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (e.getKey() instanceof String id && e.getValue() != null) out.putIfAbsent(id, e.getValue());
                }
            }
        }
        return out;
    }

    /**
     * A mod's settings screen from its factory, or null when the mod has none
     * (Mod Menu's default factory answers null, and so may a mod whose config
     * library is missing).
     */
    public Object configScreen(Object factory, Object parent) throws ReflectiveOperationException {
        return create.invoke(factory, parent);
    }
}
