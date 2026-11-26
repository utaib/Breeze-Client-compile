# Menu wallpapers

Minecraft's desktop wallpapers, shown in turn behind the Breeze mod's title
menu (mod 2.27.0, launcher 1.1.3).

- **Source:** the owner supplied them on 2026-11-15 as the zip files
  Minecraft publishes with its updates and drops (minecraft.net
  wallpapers). They are Mojang's artwork.
- **Kept:** one 16:9 desktop picture per update; phone, tablet and other
  ratios were left out (owner: "don't use the other ratios made for mobile").
  Each is scaled to 1920x1080 and saved as JPEG quality 82 (about 6 MB for
  all 23).
- **Names:** lower case, the update's name with underscores
  (`trails_and_tales.jpg`).
- **Used by:** `breeze launcher/src-tauri/src/wallpapers.rs`, which embeds
  every `.jpg` here and copies them to `~/.breezeclient/wallpapers` before
  each launch. Its unit test fails if this folder and its list differ, so
  adding a picture means adding its name there too.
- **Read by:** the mod's `ui/Wallpaper` (the folder from `-Dbreeze.home`).
  A new picture each time the title menu opens, never while it is shown.
