package dev.breeze.integrations;

/** Minecraft's Screen, for tests: a name and the screen Done returns to. */
public class FakeScreen {
    public final String name;
    public final FakeScreen parent;

    public FakeScreen(String name, FakeScreen parent) {
        this.name = name;
        this.parent = parent;
    }
}
