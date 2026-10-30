# Breeze Client 1.1.0_test_1

First testing build of the 1.1.0 launcher work. Not a release: it is for the
owner to try the changes on a real computer and report what is wrong. Inside
the app it shows as **Test build 1.1.0_test_1** (Config, About) and the
splash reads `v1.1.0-test.1` (installers need the dotted form).

Built from branch `ccr-a07e84ab-qg92p3` (PR #71). Earlier builds are not
replaced: each test build has its own draft release.

## Install

- **Windows**: `Breeze-Client-1.1.0_test_1-x86_64.exe`. It installs over
  1.0.38 and keeps your accounts and settings. Unsigned, so SmartScreen
  warns: More info, Run anyway. To go back, install 1.0.38 over it.
- **macOS**: `Breeze-Client-1.1.0_test_1-universal.dmg`. Unsigned: right
  click the app, Open. If macOS says it is damaged, run
  `xattr -dr com.apple.quarantine "/Applications/Breeze Client.app"`.
- **Linux**: AppImage, deb or rpm.

## What to test

1. **Play page as a dashboard.** The Customize button on the play page:
   drag widgets, resize from the corner, add from the list, remove with the
   cross, Undo, Cancel, Reset. Restart the launcher: the layout stays.
2. **Themes in one place.** Config, Appearance: the ten themes and Your
   theme (accent, panel colour, panel opacity, Reset). Style has no theme
   picker any more. Check text stays readable with your colours.
3. **Background image with any theme.** Config, Appearance, Background
   image: try a bright picture, a dark one and a busy one with a light and
   a dark theme. Panels should become as solid as their text needs; the
   player stands on the picture with an outline. Remove the image, choose
   it again, choose a file that is not a picture.
4. **Character moments.** On the play page and in the Wardrobe the player
   now and then does one of five short moves (look around, check the cape,
   wave, hop, stretch). Config, Appearance, Character moments switches
   them off. These are the hand-made ones you said you want to judge:
   keep, off by default, or remove.
5. **Wardrobe (Style).** The player large; capes labelled by how you have
   them; click one to see it on the player from behind; Equip waits for
   the API. Try it offline: your capes as last seen, marked, Equip waits.
6. **News.** Only real announcements; when there are none it says so, and
   when the API cannot be reached it says that instead.
7. **Updates.** Config, About shows the test build. The launcher must not
   offer you 1.0.38 as an update.

## What changed since 1.0.38

Everything in PR #71 on the launcher side: dashboard and layout editor, one
theme system with custom colours, background images that work with every
theme (readability measured from the picture), character moments, the
Wardrobe, honest News. New in this build: test builds are ordered correctly
against releases (a test build of 1.1.0 is offered 1.1.0 when it comes out),
and Config, About names the test build.

## Not in this build, or not checked

- **The API changes are not live.** The launcher talks to the live API,
  which still runs the code before PR #71 until `server.js` and `src/` are
  uploaded. Until then the Wardrobe cannot tell a gift or a granted cape
  from a bought one (all show as yours), and no cape shows as taken back.
- **The in-game changes are not in this build.** The launcher installs the
  Breeze mod the API serves. Mod 2.17.0 (other mods grouped in one entry,
  setting search, keys in the menu) is being tested on all 34 Minecraft
  versions and is not uploaded.
- **Checked automatically:** launcher unit tests 58/58 and the UI tests
  62/62 in a browser; the Windows, macOS and Linux installers built,
  installed and started on the build server, and the Rust unit tests ran
  there. The build server has no Microsoft account, so it only reached the
  sign-in screen: **everything after sign-in is UNVERIFIED inside the real
  app window** (WebView2, WKWebView, WebKitGTK) until you try it.
- Motion-capture animations (waiting on Mixamo clips), item props and
  parkour are not built yet.

## Reporting

Say "1.1.0_test_1" with what you did, what you expected, what happened, and
your system. The Console page's log (Copy log) helps; it never shows IP
addresses or tokens.
