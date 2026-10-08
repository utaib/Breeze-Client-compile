package dev.breeze.integrations;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * The "breeze" Fabric entrypoint: how any mod can put an entry in Breeze's
 * menus without depending on Breeze. In its fabric.mod.json:
 *
 * <pre>
 * "entrypoints": { "breeze": [ "com.example.mymod.BreezeEntry" ] }
 * </pre>
 *
 * and the class has two public methods:
 *
 * <pre>
 * public String breezeLabel()                 // what the menu shows, e.g. "World map"
 * public Screen breezeOpen(Screen parent)     // the screen to open; Done returns to parent
 * </pre>
 *
 * Nothing else is required, so a mod can offer this and still load where
 * Breeze is absent. Breeze calls both on the game's own thread.
 */
public final class BreezeEntrypoint {

    public static final String KEY = "breeze";

    private final Object target;
    private final Method label;
    private final Method open;

    private BreezeEntrypoint(Object target, Method label, Method open) {
        this.target = target;
        this.label = label;
        this.open = open;
    }

    /** Reads an entrypoint object; throws with the reason when it does not follow the contract. */
    public static BreezeEntrypoint of(Object target, Class<?> screenClass) throws NoSuchMethodException {
        Method label = target.getClass().getMethod("breezeLabel");
        if (label.getReturnType() != String.class || Modifier.isStatic(label.getModifiers())) {
            throw new NoSuchMethodException("breezeLabel() must be an instance method returning String");
        }
        Method open = null;
        for (Method m : target.getClass().getMethods()) {
            if (m.getName().equals("breezeOpen") && m.getParameterCount() == 1
                    && m.getParameterTypes()[0].isAssignableFrom(screenClass)
                    && screenClass.isAssignableFrom(m.getReturnType())
                    && !Modifier.isStatic(m.getModifiers())) {
                open = m;
                break;
            }
        }
        if (open == null) throw new NoSuchMethodException("breezeOpen(Screen) returning a Screen is missing");
        // Public methods of a class that is not itself public.
        label.setAccessible(true);
        open.setAccessible(true);
        return new BreezeEntrypoint(target, label, open);
    }

    public String label() throws ReflectiveOperationException {
        Object l = label.invoke(target);
        String s = l == null ? "" : l.toString().trim();
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    public Object open(Object parent) throws ReflectiveOperationException {
        return open.invoke(target, parent);
    }
}
