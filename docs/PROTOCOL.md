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
    "projectDir": "/home/me/mods/yourmod",
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
| `screen.open` | `{ blockPos: [x,y,z], approach?, face?, at?: [x,y,z] }` | `{ screenClass, opened, hint? }` |
| `screen.close` | – | `{ screenClass: null }` |
| `input.mouseMove` | `{ x, y, space? }` | `{ screenClass, mouse }` |
| `input.mouseClick` | `{ x, y, button?, space? }` | `{ screenClass, mouse }` |
| `input.slotClick` | `{ slot? \| x, y, button?, type? }` | `{ screenClass, mouse, slot, type }` |
| `input.mouseDrag` | `{ from: [x,y], to: [x,y], button?, steps?, space? }` | `{ screenClass, mouse }` |
| `input.scroll` | `{ x, y, dx?, dy, space? }` | `{ screenClass, mouse }` |
| `input.key` | `{ key, action?: press\|release\|tap, modifiers? }` | `{ screenClass, mouse }` |
| `input.type` | `{ text }` | `{ screenClass, mouse }` |
| `input.hold` | `{ key, ticks }` | `{ screenClass, mouse }` |
| `player.look` | `{ yaw, pitch }` or `{ at: [x,y,z] }` | `{ pos, yaw, pitch }` |
| `player.teleport` | `{ x, y, z, yaw?, pitch? }` | `{ pos, yaw, pitch, arrived, requested, falling }` |
| `player.inventory` | – | `{ slots: [...], selected, carried }` |
| `player.hotbar` | `{ slot }` | `{ selected }` |
| `world.reset` | `{ name?, template?, setup? }` | `{ world, template, spawn, seed, platformY, platformRadius }` |
| `world.load` | `{ name }` | `{ world }` |
| `world.leave` | – | `{}` |
| `world.list` | – | `{ worlds: [string] }` |
| `world.command` | `{ command }` | `{ success, value, output: [string] }` |
| `world.block` | `{ x, y, z, nbt? }` | `{ block, pos, state, properties, blockEntity? }` |
| `world.use` | `{ blockPos: [x,y,z], approach?, face?, at?: [x,y,z], hand?, sneak? }` | `{ pos, face, result, blockBefore, blockAfter, heldBefore, heldAfter, screenClass, screenOpened }` |
| `wait.ticks` | `{ ticks }` | `{ tick }` |
| `wait.for` | `{ condition, value?, timeoutMs? }` | `{ met, condition, screenClass, inWorld }` |
| `window.resize` | `{ width, height, guiScale? }` | metrics |
| `eval` | `{ language: "groovy", code }` | `{ value, stdout, language }` |
| `log.tail` | `{ lines?, filter?, level? }` | `{ lines: [string], level, buffered }` |

### Clicking a slot

`input.mouseClick` cannot express a shift-click, and no parameter would fix it. A screen decides
what a click meant *before* it acts: `AbstractContainerScreen.mouseClicked` works out a `ClickType`
from the button and the modifiers, and the modifiers come from the static `Screen.hasShiftDown()`,
which asks GLFW for the real keyboard state. Synthetic input never touches that state, and
`Screen.mouseClicked(x, y, button)` has nowhere to pass one anyway.

So `input.slotClick` names the operation instead of the input it would be inferred from. `type` is
one of `pickup` (a plain click), `quick_move` (**shift-click**), `swap`, `clone`, `throw`,
`quick_craft` or `pickup_all`.

Give either `slot` — the index `screen.snapshot` already reports for every slot, which is the
handle a caller usually has — or `x, y`, which resolves to the slot under that point and fails
naming the screen if none covers it. The pointer is moved onto the slot first, so a screenshot
taken afterwards shows the hover highlight where the click landed.

What this does not do is run a screen's own `slotClicked` override, and there is no way to: it is
`protected`. A mod that filters slot moves there is bypassed. Nothing found so far does; the
alternative is a mixin on `hasShiftDown`, which this mod does not need yet.

### Teleporting

`player.teleport` waits for the player to *settle*, not merely to arrive. A target in the air is
reached long before it is held — the player is still falling — and a reply sent then is true for one
tick and wrong for every screenshot after it. `falling` is true only when that wait timed out with
nothing under the player, and it means the `pos` in the same reply is already going stale.

`requested` is what was asked for. Gravity acts between the two, so it and `pos` legitimately
differ; a caller wanting a stable camera should check `arrived`.

`wait.for` conditions: `screen` (value = simple or qualified class name), `noScreen`, `inWorld`,
`outOfWorld`, `chunkLoaded` (value = `[x, y, z]`), `expr` (value = a Groovy expression).

`world.command` reports `success` separately from `output` because a failing command still prints
something ("Unknown block type ..."), so output alone cannot tell a built scene from one that was
never built. It comes from Brigadier's result callback, which is the only place the outcome is
available.

### Aiming an interaction

`screen.open` and `world.use` both right-click a block, and both take an optional aim: `face`
(`down`, `up`, `north`, `south`, `east`, `west`) or `at` (a world-space point on the block). With
neither, the click lands on the block's centre with the face reported as `up`, which is what every
release before this did.

Aiming exists because two different mechanisms decide what a click means, and only one of them
reads the `BlockHitResult`:

- **The hit result.** Vanilla placement (`getClickedFace()`), a chiseled bookshelf's six slots
  (the hit location), and Integrated Dynamics' own part *placement* all read it. For these, `face`
  and `at` are what choose the outcome.
- **A fresh raytrace from the player's eye.** CyclopsCore's `VoxelShapeComponents`, which
  Integrated Dynamics' cables and everything built on it use, throws the hit result away and casts
  its own ray from the *server* player's eye along their look angle. For these, the aim matters
  only because it decides where the bridge stands the player and what it points them at.

Both are handled the same way from the caller's side: name the side you mean. Under the covers an
aim also moves the player to where that side is visible, and waits for the server to have both the
new position and the new rotation before clicking -- a click sent in the same tick as a look is
evaluated against the previous rotation, which for the second kind of block silently picks the
wrong part.

`world.use` is `screen.open` without the expectation of a screen. Placing a part, using a tool and
wrenching all leave no screen behind, so `use` reports what changed instead of failing. Trust
`result` over the before/after fields: in creative nothing leaves the hand, and a cable gaining a
part changes neither its block id nor its state. It is `SUCCESS` when the block handled the click
and `PASS` when it did not — the finer distinctions Minecraft draws internally differ between
versions and are deliberately not exposed, since one CLI release drives every branch.

`eval` and `wait.for expr` need `-Dclientdevbridge.eval=true` **and** a Groovy engine on the
classpath. The mod reaches it through `javax.script`, so it is genuinely optional; the CLI's init
script adds `org.apache.groovy:groovy-jsr223` alongside the mod.

### What a script can see

Bound names: `mc`, `player`, `level`, `screen`, `window`, `server`, and `dev`.

`dev` exists because the game is loaded by a transforming class loader and the script engine is
not, so `new net.minecraft.core.BlockPos(0, 4, 2)` fails with a message about class loaders and no
way to act on it. Nothing constructed on the script's side can be a game object; `dev` builds them
on the game's side instead:

| Call | Answers |
| --- | --- |
| `dev.pos(x, y, z)` | a `BlockPos` |
| `dev.vec(x, y, z)` | a `Vec3` |
| `dev.block(x, y, z)` | the `BlockState` |
| `dev.blockId(x, y, z)` | the block's registry name, as a string |
| `dev.blockEntity(x, y, z)` | the `BlockEntity`, or null |
| `dev.nbt(x, y, z)` | the block entity's synced data, as a string |
| `dev.item("minecraft:stone"[, count])` | an `ItemStack` |
| `dev.prop(x, y, z, "lit")` | one state property's value, as the game's own object |
| `dev.props(x, y, z)` | every state property, as a name-to-value map |

The code is a **script**, not a single expression: statements are allowed and the last one is its
value. So negate the last statement, not the whole thing — `def p = dev.pos(0, 4, 2); !level.getBlockState(p).isAir()`,
never `!(def p = ...; ...)`.

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

### Describing your own blocks

`world.block` reports the block id, its state properties and its block entity type -- everything a
vanilla block is. It is not everything a multipart block is: a cable carrying a Redstone Writer and
a bare one have the same id, the same state and the same block entity type, so a caller cannot tell
from the description whether the setup it just built worked.

`BlockExtractors` is the counterpart of `SnapshotExtractors` for the world. Register against a
block entity class and write whatever distinguishes one instance from another; the result appears
under `blockEntity.details`:

```java
BlockExtractors.register(BlockEntityMultipartTicking.class, (blockEntity, details) -> {
    for (Direction side : Direction.values()) {
        details.addProperty(side.getName(), describePart(blockEntity, side));
    }
});
```

With no extractor registered, `world.block` with `nbt: true` is the fallback: the block entity's
synced NBT usually carries the same information, if less readably. For an Integrated Dynamics cable
it contains `partContainer.parts[].__partType` and `__side`.
