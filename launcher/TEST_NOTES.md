# Breeze Client 1.1.0_test_6

Sixth testing build of the 1.1.0 work. Not a release. Inside the app it
shows as **Test build 1.1.0_test_6** (Config, About); the splash reads
`v1.1.0-test.6`. Everything in 1.1.0_test_5 is in it too, and its notes
still apply (`docs/test-builds/1.1.0_test_5.md`).

Installs over 1.0.38 or any earlier 1.1.0 test build and keeps accounts and
settings. Unsigned: on Windows, More info, Run anyway; on macOS, right
click, Open.

This build is the new Store, plus three changes made after test_5.

## What to test

1. **The Store.** Open Store. At the top, the spotlight: a few items worn
   by your own character, the item's name, price and Buy beside it, and on
   a wide window the item itself, large. A cape turns your player round to
   show it. Every 8 seconds the next one comes (the line under the small
   picture fills up); it waits while the pointer is on it. Click a small
   picture to pick one; with the spotlight focused, the arrow keys do too.
2. **The cards.** Each card shows the cape's outside, sharp, in the colour
   of its rarity (common grey, uncommon green, rare blue, epic purple,
   legendary gold). Hover one: it lifts and the cape sways. Animated capes
   play on their card. Pets, hats and wings show a picture of the model.
   There is one section per kind, the tabs only list kinds that are sold,
   and Featured at the top right sorts by newest, price or name.
3. **An item's window.** Click a card. Your player wears it on the left
   (drag to turn); on the right: what it is, its rarity, whether it is
   animated, whether you own it, the price large, and how many Wind Charges
   you have. With too few, it says how many are missing and the button is
   Get Wind Charges. "Have a promo code?" opens the code field. For
   something you own: Equip or Unequip. Escape closes it.
4. **Customize on the Play page.** Drag the player or any widget: it
   follows the pointer smoothly, with a dashed outline where it will land
   (test_3 said moving the player was "a little glitchy").
5. **The Mods page.** At the top it says which Minecraft version it
   manages, with which Fabric Loader and Java, and has buttons for that
   version's mods, resource packs, shader packs, config and logs folders
   and the whole folder.
6. **Exporting mods.** Mods widget or Mods page, Export: the file is named
   after your Minecraft name; mods and resource packs that Modrinth has are
   linked instead of copied (a much smaller file), and a `README.txt`
   inside says what will be downloaded, what is carried and what was left
   out. Import it on another computer, or into another version, to check.

## What changed since 1.1.0_test_5

- Store: cards show the item itself instead of a small spinning player
  each; a spotlight; sections per kind; sorting; a fuller item window.
  With the live Store's 26 items the old page started 26 3D views at
  once, more than the browser keeps (it said so and dropped the oldest);
  the new page starts one.
- Customize: dragging follows the pointer and re-draws only when the
  landing cell changes.
- Mods page: the strip naming the version, loader and Java, and the six
  folder buttons.
- Export: linked by hash, a README in the pack, named after the player.

## Known limits

- The Store's item pictures are made on your computer the first time the
  Store opens, so pets, hats and wings show a soft placeholder for a moment.
- The same as before: the API changes are not live until `server.js` and
  `src/` are uploaded; the in-game changes (mod 2.23.1) are not served by
  the API yet; everything after sign-in is UNVERIFIED in the real app
  windows on the build server (it has no Microsoft account), so the Store
  itself is checked in a browser here, not in the app.
- Checked automatically: Store unit tests 8 (spotlight choice, sections,
  tabs, search, sorting, promo price) and UI tests 9 in a browser, the
  whole launcher suite (results below), `npm run build`.

## Build results

To be filled in from the build run.

## Reporting

Say "1.1.0_test_6" with what you did, what you expected and what happened.
