package dev.breeze.modules;

import dev.breeze.Category;
import dev.breeze.Module;
import dev.breeze.names.LocalName;
import dev.breeze.settings.Setting;

import java.util.List;

/**
 * Your own name, shown as you choose, on your own computer only (1.1.0
 * directive: a local presentation preference, never an identity change).
 * Nothing is sent to the server; the account, its uuid and every packet stay
 * as they are, and other players' names are never changed. Where the name
 * changes is in names/OwnName. Was Nickname Hider (the tab list only, always
 * "You"), whose saved on/off carries over.
 */
public class DisplayName extends Module {

    private static DisplayName instance;

    private final Setting.Text name = add(new Setting.Text("name", "Name you see", "Name", "You", LocalName.MAX, LocalName::clean));
    private final Setting.Bool chat = add(new Setting.Bool("chat", "In chat", "Where", true));
    private final Setting.Bool tab = add(new Setting.Bool("tab", "In the tab list", "Where", true));

    public DisplayName() {
        super("Display Name", Category.UTILITY,
                "Shows your own name as you choose, on this computer only.", KEY_NONE);
        instance = this;
    }

    @Override
    public List<String> earlierNames() {
        return List.of("Nickname Hider");
    }

    @Override
    public String scopeNote() {
        return "Only on this computer. Other players, the server and its staff still see your real name, and commands still use it. Chat lines a server writes as plain text keep your real name.";
    }

    private static boolean on() {
        return instance != null && instance.isEnabled();
    }

    public static boolean inChat() {
        return on() && instance.chat.value;
    }

    public static boolean inTab() {
        return on() && instance.tab.value;
    }

    public static String alias() {
        return instance == null ? "You" : instance.name.value;
    }

    /** For the self-test: puts a name in as the menu would. */
    public static void setAlias(String value) {
        if (instance != null) instance.name.set(value);
    }
}
