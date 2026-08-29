# Wire protocol

JSON-RPC 2.0 over a WebSocket on `127.0.0.1`, default port `25599`.

The protocol is **identical on every ClientDevBridge branch**. Version-specific behaviour is an
implementation detail behind it, so one `clientdevbridge-cli` release drives every supported
Minecraft version.

## Framing

The transport is a hand-rolled RFC 6455 server. Minecraft ships Netty, but not `netty-codec-http`,
so `io.netty.handler.codec.http.websocketx` is not on the classpath; rather than jar-in-jarring a
dependency into a dev-only mod, the handful of framing rules needed are implemented directly. Only
text frames, continuations, ping/pong and close are supported. Messages are capped at 32 MiB.

## Handshake

On connect the mod immediately sends:

```json
{
  "jsonrpc": "2.0",
  "method": "hello",
  "params": {
    "protocol": 1,
    "mcVersion": "1.21.1",
    "loader": "neoforge",
    "clientDevBridgeVersion": "1.0.0",
    "evalEnabled": true,
    "mods": ["clientdevbridge", "minecraft", "neoforge"]
  }
}
```

The CLI accepts `protocol: 1` and fails with an explicit "update the CLI" / "update the mod"
message otherwise. Additive changes only within a version; a breaking change bumps `protocol` and
must land on **all** active branches in the same release train.

**The socket opens during mod initialisation, before the game is usable.** Wait for `status.loaded`
before doing anything else — the CLI's `start` does this for you.

## Errors

Standard JSON-RPC codes, plus these in the implementation-defined range:

| Code | Meaning |
|---|---|
| `-32001` | Illegal state: no world, no screen, no integrated server |
| `-32002` | A wait or condition timed out |
| `-32003` | Disabled: the method is gated behind a system property that is not set |

## Notifications

Pushed by the mod, with no `id` and no reply expected:

| Method | When |
|---|---|
| `log.line` | A line was logged at INFO or above |
| `screen.changed` | The open screen changed |
| `world.joined` / `world.left` | A world was entered or left |

Notifications are dropped rather than queued without bound when a client stops reading: a dropped
notification is always preferable to a stalled game loop.

## Coordinate spaces

- **GUI space** — Minecraft's scaled coordinates. Every widget position in a snapshot is in GUI
  space, and it is the default for every input method, so snapshot output can be fed straight back.
- **Pixel space** — raw framebuffer pixels. Screenshots are in pixel space.

Every snapshot and screenshot result carries `guiScale`, `guiWidth`, `guiHeight`, `pixelWidth` and
`pixelHeight`. Input methods take an optional `space` of `gui` (default) or `pixel`.

## Methods

| Method | Params | Result |
|---|---|---|
| `status` | – | `{ loaded, inWorld, screenClass, tick, fps, dimension, gameDir, glRenderer, player: { pos, yaw, pitch }, ...metrics }` |
| `screenshot` | `{ region?: {x,y,w,h,space?}, scale?, afterTicks? }` | `{ png: base64, width, height, bytes, ...metrics }` |
| `screen.snapshot` | `{ includeHidden?, maxDepth? }` | see below |
| `screen.tooltip` | `{ x, y, space? }` | `{ lines: [string], source, slot?, item?, widget? }` |
| `screen.open` | `{ blockPos: [x,y,z], approach? }` | `{ screenClass, opened, hint? }` |
| `screen.close` | – | `{ screenClass: null }` |
| `input.mouseMove` | `{ x, y, space? }` | `{ screenClass, mouse }` |
| `input.mouseClick` | `{ x, y, button?, space? }` | `{ screenClass, mouse }` |
| `input.mouseDrag` | `{ from: [x,y], to: [x,y], button?, steps?, space? }` | `{ screenClass, mouse }` |
| `input.scroll` | `{ x, y, dx?, dy, space? }` | `{ screenClass, mouse }` |
| `input.key` | `{ key, action?: press\|release\|tap, modifiers? }` | `{ screenClass, mouse }` |
| `input.type` | `{ text }` | `{ screenClass, mouse }` |
| `input.hold` | `{ key, ticks }` | `{ screenClass, mouse }` |
| `player.look` | `{ yaw, pitch }` or `{ at: [x,y,z] }` | `{ pos, yaw, pitch }` |
| `player.teleport` | `{ x, y, z, yaw?, pitch? }` | `{ pos, yaw, pitch, arrived }` |
| `player.inventory` | – | `{ slots: [...], selected, carried }` |
| `player.hotbar` | `{ slot }` | `{ selected }` |
| `world.reset` | `{ name?, template?, setup? }` | `{ world, template, spawn, seed, platformY, platformRadius }` |
| `world.load` | `{ name }` | `{ world }` |
| `world.leave` | – | `{}` |
| `world.list` | – | `{ worlds: [string] }` |
| `world.command` | `{ command }` | `{ success, value, output: [string] }` |
| `world.block` | `{ x, y, z, nbt? }` | `{ block, pos, state, properties, blockEntity? }` |
| `wait.ticks` | `{ ticks }` | `{ tick }` |
| `wait.for` | `{ condition, value?, timeoutMs? }` | `{ met, condition, screenClass, inWorld }` |
| `window.resize` | `{ width, height, guiScale? }` | metrics |
| `eval` | `{ language: "groovy", code }` | `{ value, stdout, language }` |
| `log.tail` | `{ lines?, filter?, level? }` | `{ lines: [string], level, buffered }` |

`wait.for` conditions: `screen` (value = simple or qualified class name), `noScreen`, `inWorld`,
`outOfWorld`, `chunkLoaded` (value = `[x, y, z]`), `expr` (value = a Groovy expression).

`world.command` reports `success` separately from `output` because a failing command still prints
something ("Unknown block type ..."), so output alone cannot tell a built scene from one that was
never built. It comes from Brigadier's result callback, which is the only place the outcome is
available.

`eval` and `wait.for expr` need `-Dclientdevbridge.eval=true` **and** a Groovy engine on the
classpath. The mod reaches it through `javax.script`, so it is genuinely optional; the CLI's init
script adds `org.apache.groovy:groovy-jsr223` alongside the mod.

## `screen.snapshot`

```jsonc
{
  "screenClass": "net.minecraft.client.gui.screens.inventory.CraftingScreen",
  "title": "Crafting",
  "guiScale": 2, "guiWidth": 427, "guiHeight": 240,
  "pixelWidth": 854, "pixelHeight": 480,
  "mouse": [210, 100],
  "focused": "/root/children[3]",
  "hovered": null,
  "truncated": false,
  "container": {                       // null unless the screen is an AbstractContainerScreen
    "menuClass": "net.minecraft.world.inventory.CraftingMenu",
    "leftPos": 125, "topPos": 37, "imageWidth": 176, "imageHeight": 166,
    "carried": { "item": null, "count": 0 },
    "slots": [
      // x and y are absolute GUI-space coordinates, ready to click
      { "index": 37, "item": "minecraft:diamond", "count": 5, "name": "Diamond",
        "x": 133, "y": 179, "hovered": false }
    ]
  },
  "root": {
    "path": "/root",
    "type": "net.minecraft.client.gui.screens.inventory.CraftingScreen",
    "bounds": { "x": 0, "y": 0, "w": 427, "h": 240 },   // GUI space, absolute
    "message": "Crafting",
    "narration": null,
    "visible": true, "active": true, "focused": false, "hovered": false,
    "value": null,                                       // sliders, edit boxes, checkboxes, ...
    "extra": { "kind": "button" },                       // per-type, from the extractor registry
    "children": [ /* ... */ ]
  }
}
```

Rules:

- `screen.children()` is walked, recursing into `ContainerEventHandler`s. `Renderable`s that are not
  `GuiEventListener`s are pure decoration and appear only when `includeHidden` is set.
- Bounds come from `AbstractWidget`, then `GuiEventListener#getRectangle()`, then reflection on
  conventional `x`/`y`/`width`/`height` fields, then `null`. A component whose rectangle is empty
  gets `extra.boundsUnknown` and a note rather than a claimed `0x0`: vanilla's recipe book is the
  common case, and it exposes neither bounds nor children through the standard interfaces.
- Depth is capped at 12 and the tree at 2000 nodes; `truncated` says when a cap was hit.
- `path` is stable within a snapshot and is what `click --widget` consumes.

### Registering your own extractors

Mods can describe their own widgets. Register during client setup:

```java
SnapshotExtractors.register(MyFancyWidget.class, (widget, node) -> {
    node.value(widget.getFraction());
    node.extra("mode", widget.getMode().name());
});
```

Every matching extractor in the class hierarchy runs, base classes first, so a specific extractor
can refine what a general one set. An extractor that throws is reported in the node's
`extra.extractorError` rather than failing the whole snapshot.
