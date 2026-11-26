# The Breeze bridge

How the in-game interface (React, in MCEF's Chromium) and the mod (Java, in
Minecraft) talk to each other. The contract itself is
[`contract/bridge.json`](../contract/bridge.json); this page explains it.

## Shape

```
 React page (https://breeze.local)            Minecraft (Java)
 ─────────────────────────────────            ───────────────────────────────
 bridge.call(action, params)
   └ window.breezeQuery({request}) ──────▶  BreezeWeb message router
                                              ├ same browser?  frame origin ok?
                                              └ Router.dispatch(queryId, json)
                                                  ├ Request.parse (validate)
                                                  ├ thread: ANY | CLIENT | IO
                                                  ├ timeout, duplicate, in-flight cap
                                                  └ Handler (versions/*/web/Handlers)
   onSuccess(json) / onFailure(code, msg) ◀──  exactly one answer
 window.__breezeEvent({type, payload})  ◀──  BreezeBrowser.emit (Events.script)
```

## Where things live

| Piece | Location | Minecraft-specific |
| --- | --- | --- |
| Contract | `contract/bridge.json` | no |
| TypeScript client | `frontend/src/bridge/` (`client.ts`, `types.ts`, `actions.ts`) | no |
| Router, validation, errors, events, origin, resources, links, settings | `common/src/main/java/dev/breeze/bridge`, `.../settings/UiSettings.java` | no |
| Page origin (CEF scheme handler) | `versions/1.20.1/.../web/PageScheme.java` | CEF only |
| Message router setup | `versions/1.20.1/.../web/BreezeWeb.java` | CEF only |
| Browser, input, drawing | `versions/1.20.1/.../web/BreezeBrowser.java` | yes |
| Screen, Escape, lifecycle | `versions/1.20.1/.../web/BreezeWebScreen.java` | yes |
| Handlers | `versions/1.20.1/.../web/Handlers.java`, `ModuleJson.java`, `UiState.java` | yes |

Anything version-specific stays in `versions/<mc>/`. The page never sees a
Minecraft class name, and `common/` never imports one.

## Request and answer

Request, sent as the `request` string of `window.breezeQuery`:

```json
{ "v": 1, "id": 7, "action": "modules.setEnabled", "params": { "name": "FPS", "enabled": true } }
```

- `v` must be 1. `id` must be a positive integer, unique while in flight.
- `action` must match `[a-z][a-zA-Z]*(\.[a-z][a-zA-Z]*){1,2}` and be in the
  contract. `params` must be an object. Total size at most 64 KiB.
- Success: `onSuccess(json)` with the action's result (see `types.ts`).
- Failure: `onFailure(code, message)`. `code` is the 1-based position in the
  contract's `errors` list; `message` is written for the player.

| Code | Name | Meaning |
| --- | --- | --- |
| 1 | BAD_REQUEST | not valid JSON, wrong version, bad id or action name |
| 2 | UNKNOWN_ACTION | not an action on this version |
| 3 | INVALID_PARAMS | a parameter is missing, the wrong type, or out of range |
| 4 | UNAVAILABLE | cannot be done right now (no world, network down) |
| 5 | FORBIDDEN | not allowed (not signed in, not owned, wrong context) |
| 6 | TIMEOUT | Java did not finish in time |
| 7 | BUSY | more than 64 requests in flight |
| 8 | DUPLICATE | that id is already in flight |
| 9 | CANCELLED | the page cancelled, or the menu closed |
| 10 | INTERNAL | an unexpected failure; details are in the game log only |

## Threads

Each action declares one of three threads in the contract, and
`BridgeActions` enforces it. Handlers never choose.

- **any**: runs on CEF's thread. Only cheap reads of thread-safe state.
- **client**: runs on Minecraft's client thread through `Minecraft::execute`.
  Everything that touches the game. Default timeout 5 s.
- **io**: runs on the router's own two-thread pool. Network calls. Default
  timeout 20 s here, so neither CEF nor the game thread ever waits on the
  network.

No thread blocks on another. When a timeout fires, the page is answered and
the work's eventual result is dropped. Work still queued when its timeout fires
is skipped, so a timed-out request never acts on the game late.

## Screen changes

A handler that opens another screen (world selection, options, quitting)
schedules it with `BreezeWebScreen.afterAnswer`. The page gets its answer first;
then the screen changes, `removed()` closes the browser, and the router answers
anything still pending with CANCELLED.

## Escape

Java does not forward Escape to Chromium. `BreezeWebScreen` sends the page a
`key.escape` event and waits up to 500 ms for `ui.escapeAck`:

- `handled: true`: the page closed a dialog or went back a page. Nothing else.
- `handled: false`: the page was at its root. In game the menu closes; on the
  title menu nothing happens, exactly like Minecraft's own title screen.
- no answer: the page failed, hung or is still loading. In game the menu
  closes. On the title menu one unanswered press does nothing (the page may
  still be loading); two in a row switch to Minecraft's own title screen for
  the rest of the session, so a broken page never traps the player.

## Security

- The page is served only from the mod jar, at `https://breeze.local/`, by a
  CEF scheme handler. `PageResources` allows only that origin, files under
  `assets/breeze/html/`, known file types, and no traversal in any encoding.
- MCEF starts Chromium with `--disable-web-security`, so the page carries a
  production CSP with `connect-src 'none'`: it cannot reach the network at all.
  Everything network-bound goes through Java.
- Queries are answered only for the one live Breeze browser and only from a
  frame on `https://breeze.local`. Another MCEF browser, or a page the Breeze
  browser was somehow navigated to, gets nothing.
- `app.openExternal` opens only Breeze's own https hosts, in the system browser.
- `game.joinServer` only accepts the server the player last joined.
- Tokens never reach the page. The API client in `common/net/BreezeApi` holds
  the short-lived game token and attaches it in Java.
- Parameters are never logged. The development self-test records only route,
  Escape, settings and module toggle parameters.

## Events

`Events.script(type, payload)` builds `window.__breezeEvent({...})` with Gson's
escaping, so no payload can break out of the literal. Only the contract's event
names are accepted.

## Adding an action

1. Add it to `contract/bridge.json` with its thread and params.
2. Add it to `BridgeActions` (Java) and `ActionMap` and `actions.ts` (TypeScript).
   `ContractTest` and `contract.test.ts` fail until all three agree.
3. Register a handler in every `versions/*/web/Handlers.java`. Each version logs
   an error at startup for any contract action it has no handler for.
4. Add a preview implementation to `frontend/src/dev/fixture.ts` and a
   Playwright test for the screen that uses it.
