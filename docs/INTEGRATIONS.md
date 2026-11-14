# Other mods in Breeze's menus

Breeze lists what other mods offer in its menus (the web menu's rail and
Installed mods, the native menu's sidebar) through real Fabric APIs. Only
what is loaded is listed; an API that fails is shown as a problem and left
out, and Breeze carries on.

## Mod Menu

With [Mod Menu](https://github.com/TerraformersMC/ModMenu) installed, Breeze
offers its mods list and every settings screen Mod Menu knows. Mod authors do
nothing extra: the `modmenu` entrypoint they already declare is what Breeze
reads. Breeze calls Mod Menu's published API by name
(`com.terraformersmc.modmenu.api.ModMenuApi`, `ConfigScreenFactory`), so it
has no dependency on Mod Menu and never copies it.

## The `breeze` entrypoint

Any mod can put an entry in Breeze's menus without depending on Breeze. In
`fabric.mod.json`:

```json
"entrypoints": {
  "breeze": ["com.example.mymod.BreezeEntry"]
}
```

and a class with two public methods:

```java
public class BreezeEntry {
    /** What Breeze's menu shows. Up to 40 characters. */
    public String breezeLabel() {
        return "World map";
    }

    /** The screen to open; when it closes, return to parent. */
    public Screen breezeOpen(Screen parent) {
        return new MyMapScreen(parent);
    }
}
```

Both are called on the game's own thread. The class loads only when Breeze
asks for it, so the mod still loads where Breeze is not installed. A class
that does not follow this is reported in Breeze's Installed mods, not used.

## What the launcher sees

At startup Breeze writes `.breeze/runtime-mods.json` in the game folder: every
mod Fabric loaded (id, name, version, kind: `breeze`, `game`, `library` or
`mod`, and the mod that bundles it), the integrations found, and their
problems. The Breeze launcher reads it to show which jars in the mods folder
Fabric did not load.
