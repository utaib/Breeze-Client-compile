package dev.breeze.commands;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import dev.breeze.BreezeClient;
import dev.breeze.commands.CommandKeys.Binding;
import dev.breeze.compat.Commands;
import dev.breeze.compat.Keys;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Command keys in the game: the saved list (config/breeze-command-keys.json),
 * the key watch, and running a command the way a typed one is run.
 *
 * Keys are read each tick from the keyboard's state and a command runs when
 * its key goes down, never while it is held, and only in a world with no
 * screen open. Before sending, the command's name is looked up in the
 * command list the server sent this player (which holds only what the
 * player may use, and Fabric's client commands); a command not in it is not
 * sent, and the player reads "That command is not available here" instead.
 * The server still checks every command it receives, as for a typed one.
 */
public final class CommandKeyRunner {

    private CommandKeyRunner() {}

    private static List<Binding> bindings = new ArrayList<>();
    /** Keys Minecraft saw go down and not yet up, so a held key's repeats do not run anything. */
    private static final java.util.Set<String> held = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** A key that went down in the world, with the modifiers held at that moment. */
    private record Press(String key, boolean ctrl, boolean shift, boolean alt) {}

    private static final java.util.Queue<Press> presses = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static String capturing;
    private static volatile String lastRun;
    private static volatile String lastRefusal;

    // ── The saved list ──────────────────────────────────────────────────────

    private static Path path() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("breeze-command-keys.json");
    }

    public static synchronized void load() {
        try {
            Path p = path();
            bindings = Files.exists(p) ? new ArrayList<>(CommandKeys.parse(Files.readString(p, StandardCharsets.UTF_8))) : new ArrayList<>();
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] command keys not read: {}", t.toString());
            bindings = new ArrayList<>();
        }
    }

    private static synchronized void save() {
        try {
            Path p = path();
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, CommandKeys.write(bindings), StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Throwable t) {
            BreezeClient.LOGGER.warn("[Breeze] command keys not saved: {}", t.toString());
        }
    }

    public static synchronized List<Binding> all() {
        return new ArrayList<>(bindings);
    }

    private static int index(String id) {
        for (int i = 0; i < bindings.size(); i++) if (bindings.get(i).id().equals(id)) return i;
        return -1;
    }

    /** Adds one (id null) or changes one's command and on/off; throws IllegalArgumentException with the reason. */
    public static synchronized Binding save(String id, String command, Boolean enabled) {
        String clean = CommandKeys.cleanCommand(command);
        if (id == null || id.isEmpty()) {
            if (bindings.size() >= CommandKeys.MAX_BINDINGS) {
                throw new IllegalArgumentException("Keep at most " + CommandKeys.MAX_BINDINGS + " command keys.");
            }
            Binding b = new Binding(CommandKeys.newId(bindings), clean, "", false, false, false, enabled == null || enabled);
            bindings.add(b);
            save();
            return b;
        }
        int i = index(id);
        if (i < 0) throw new IllegalArgumentException("No such command key.");
        Binding b = bindings.get(i).withCommand(clean);
        if (enabled != null) b = b.withEnabled(enabled);
        bindings.set(i, b);
        save();
        return b;
    }

    public static synchronized boolean remove(String id) {
        int i = index(id);
        if (i < 0) return false;
        bindings.remove(i);
        if (id.equals(capturing)) capturing = null;
        save();
        return true;
    }

    public static synchronized boolean unbind(String id) {
        int i = index(id);
        if (i < 0) return false;
        bindings.set(i, bindings.get(i).withKey("", false, false, false));
        save();
        return true;
    }

    /** For the self-test: a binding as the menu would leave it after a key was taken. */
    public static synchronized void setKey(String id, String key, boolean ctrl, boolean shift, boolean alt) {
        int i = index(id);
        if (i >= 0) {
            bindings.set(i, bindings.get(i).withKey(key, ctrl, shift, alt));
            save();
        }
    }

    // ── Taking a key from the menu ──────────────────────────────────────────

    public static synchronized boolean capture(String id) {
        if (index(id) < 0) return false;
        capturing = id;
        return true;
    }

    public static synchronized void cancelCapture() {
        capturing = null;
    }

    public static synchronized String capturing() {
        return capturing;
    }

    /** A modifier on its own: the menu keeps waiting for the key it goes with. */
    public static boolean modifierKey(int key) {
        return key == InputConstants.KEY_LCONTROL || key == InputConstants.KEY_RCONTROL
                || key == InputConstants.KEY_LSHIFT || key == InputConstants.KEY_RSHIFT
                || key == InputConstants.KEY_LALT || key == InputConstants.KEY_RALT;
    }

    /**
     * A key pressed in the menu while it waits: Escape cancels; a key
     * Minecraft needs for playing is refused unless Ctrl, Shift or Alt is
     * held with it (returned as the reason); any other key, with the
     * modifiers held now, becomes the binding's. Returns { id, refused }.
     */
    public static synchronized String[] takeKey(Minecraft mc, int key, int scanCode) {
        String id = capturing;
        capturing = null;
        if (id == null || key == InputConstants.KEY_ESCAPE) return new String[] {id, null};
        int i = index(id);
        if (i < 0) return new String[] {id, null};
        InputConstants.Key k = Keys.keyOf(key, scanCode);
        String name = k.getName();
        if (!CommandKeys.keyName(name)) return new String[] {id, "Choose a key on the keyboard."};
        boolean ctrl = ctrl(mc), shift = shift(mc), alt = alt(mc);
        if (!ctrl && !shift && !alt) {
            KeyMapping essential = essentialOn(mc, name);
            if (essential != null) {
                return new String[] {id, k.getDisplayName().getString() + " is " + I18n.get(essential.getName())
                        + " in Minecraft. Hold Ctrl, Shift or Alt with it, or choose another key."};
            }
        }
        bindings.set(i, bindings.get(i).withKey(name, ctrl, shift, alt));
        save();
        return new String[] {id, null};
    }

    /** The controls a player cannot do without: a command key takes their key only with a modifier. */
    private static KeyMapping essentialOn(Minecraft mc, String keyName) {
        if (mc.options == null) return null;
        KeyMapping[] needed = {
                mc.options.keyUp, mc.options.keyLeft, mc.options.keyDown, mc.options.keyRight,
                mc.options.keyJump, mc.options.keyShift, mc.options.keySprint,
                mc.options.keyAttack, mc.options.keyUse, mc.options.keyInventory,
                mc.options.keyChat, mc.options.keyCommand,
        };
        for (KeyMapping km : needed) if (km != null && keyName.equals(km.saveString())) return km;
        return null;
    }

    // ── Running ─────────────────────────────────────────────────────────────

    private static boolean down(Minecraft mc, int key) {
        return key >= 0 && Keys.isDown(mc, key);
    }

    private static boolean ctrl(Minecraft mc) {
        return down(mc, InputConstants.KEY_LCONTROL) || down(mc, InputConstants.KEY_RCONTROL);
    }

    private static boolean shift(Minecraft mc) {
        return down(mc, InputConstants.KEY_LSHIFT) || down(mc, InputConstants.KEY_RSHIFT);
    }

    private static boolean alt(Minecraft mc) {
        return down(mc, InputConstants.KEY_LALT) || down(mc, InputConstants.KEY_RALT);
    }

    private static int code(String keyName) {
        try {
            InputConstants.Key k = InputConstants.getKey(keyName);
            return k == null || k.equals(InputConstants.UNKNOWN) ? -1 : k.getValue();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * From Minecraft's own key handler (CommandKeyMappingMixin on
     * KeyMapping.set): a key went down or up while no screen took it. A
     * press is kept with the modifiers held at that moment and run on the
     * next tick; the repeats a held key sends are not presses.
     */
    public static void keyEvent(InputConstants.Key key, boolean down) {
        if (key == null) return;
        String name = key.getName();
        if (!down) {
            held.remove(name);
            return;
        }
        if (!held.add(name)) return;
        if (bindings.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || dev.breeze.compat.ActiveScreen.get(mc) != null) return;
        presses.add(new Press(name, ctrl(mc), shift(mc), alt(mc)));
    }

    /** Each client tick: run the commands whose keys went down since the last one, in a world with no screen open. */
    public static void tick(Minecraft mc) {
        // A key let go while a screen had it never told KeyMapping; once it
        // is up it can go down again.
        if (!held.isEmpty()) held.removeIf(k -> !down(mc, code(k)));
        List<Binding> list;
        synchronized (CommandKeyRunner.class) {
            if (bindings.isEmpty()) {
                presses.clear();
                return;
            }
            list = new ArrayList<>(bindings);
        }
        boolean live = mc.player != null && mc.getConnection() != null && dev.breeze.compat.ActiveScreen.get(mc) == null;
        Press p;
        while ((p = presses.poll()) != null) {
            if (!live) continue;
            for (Binding b : list) {
                if (b.bound() && b.key().equals(p.key()) && CommandKeys.fires(b, true, false, p.ctrl(), p.shift(), p.alt())) run(mc, b);
            }
        }
    }

    /** Sends the command as a typed one, or says it is not available; never claims it ran. */
    public static void run(Minecraft mc, Binding b) {
        String name = CommandKeys.name(b.command());
        if (!Commands.offered(mc, name)) {
            lastRefusal = name;
            dev.breeze.devtest.AutoTest.commandRan(b.command(), false);
            dev.breeze.compat.Game.message(mc, Component.literal("§b[Breeze]§r That command is not available here: /" + name));
            return;
        }
        lastRun = b.command();
        Commands.send(mc, b.command());
        dev.breeze.devtest.AutoTest.commandRan(b.command(), true);
    }

    /** For the self-test: the last command sent, and the last one refused as not available. */
    public static String lastRun() { return lastRun; }

    public static String lastRefusal() { return lastRefusal; }

    // ── For the menu ────────────────────────────────────────────────────────

    /** The key with its modifiers in the game's language: "Ctrl + K". */
    private static String label(Binding b) {
        if (!b.bound()) return "";
        String key;
        try {
            key = InputConstants.getKey(b.key()).getDisplayName().getString();
        } catch (Throwable t) {
            key = b.key();
        }
        StringBuilder s = new StringBuilder();
        if (b.ctrl()) s.append("Ctrl + ");
        if (b.shift()) s.append("Shift + ");
        if (b.alt()) s.append("Alt + ");
        return s.append(key).toString();
    }

    public static synchronized JsonObject json(Minecraft mc) {
        JsonArray arr = new JsonArray();
        boolean inWorld = mc.player != null && mc.getConnection() != null;
        for (Binding b : bindings) {
            JsonObject o = new JsonObject();
            o.addProperty("id", b.id());
            o.addProperty("command", b.command());
            o.addProperty("key", label(b));
            o.addProperty("enabled", b.enabled());
            JsonArray conflicts = new JsonArray();
            if (b.bound()) {
                if (mc.options != null) {
                    for (KeyMapping km : mc.options.keyMappings) {
                        if (b.key().equals(km.saveString())) conflicts.add(I18n.get(km.getName()));
                    }
                }
                for (Binding other : bindings) if (other != b && other.sameChord(b)) conflicts.add("/" + other.command());
            }
            o.add("conflicts", conflicts);
            if (inWorld) o.addProperty("available", Commands.offered(mc, CommandKeys.name(b.command())));
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.add("bindings", arr);
        out.addProperty("max", CommandKeys.MAX_BINDINGS);
        if (capturing != null) out.addProperty("capturing", capturing);
        return out;
    }
}
