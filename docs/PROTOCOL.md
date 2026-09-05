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
- **Pixel space** — raw framebuffer pixels. Screenshots are in pixel space, and so is the size
  `window.resize` takes. Framebuffer pixels are not the screen coordinates a window manager sizes a
  window in: on a display that scales windows there are several of the former behind one of the
  latter, and the bridge converts rather than let the two mean the same number.

Every snapshot and screenshot result carries `guiScale`, `guiWidth`, `guiHeight`, `pixelWidth` and
`pixelHeight`. Input methods take an optional `space` of `gui` (default) or `pixel`.

## Methods

| Method | Params | Result |
|---|---|---|
| `status` | – | `{ loaded, inWorld, screenClass, tick, fps, dimension, gameDir, glRenderer, player: { pos, yaw, pitch }, ...metrics }` |
| `screenshot` | `{ region?: {x,y,w,h,space?}, mouse?: {x,y,space?}, scale?, afterTicks? }` | `{ png: base64, width, height, bytes, region?, regionGui?, ...metrics }` |
| `screen.snapshot` | `{ includeHidden?, maxDepth? }` | see below |
| `screen.tooltip` | `{ x, y, space? }` | `{ lines: [string], source, slot?, item?, widget?, note? }` |
| `screen.open` | `{ blockPos: [x,y,z], approach?, face?, at?: [x,y,z] }` | `{ screenClass, opened, hint? }` |
| `screen.close` | – | `{ screenClass }` — the screen in focus *after* closing, usually null |
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
| `player.walkTo` | `{ x, z, within?, timeoutTicks? }` | `{ pos, yaw, pitch, arrived, requested }` |
| `player.useItem` | `{ hand?: "auto"\|"main"\|"off", waitScreenTicks? }` | `{ screenClass, mouse, held, aimedAt, hand, screenOpened }` |
| `player.inventory` | – | `{ slots: [...], selected, carried }` |
| `player.hotbar` | `{ slot }` | `{ selected, index, item, count, name, details? }` |
| `world.reset` | `{ name?, template?, setup? }` | `{ world, template, spawn, seed, platformY, platformRadius }` |
| `world.load` | `{ name }` | `{ world }` |
| `world.leave` | – | `{}` |
| `world.list` | – | `{ worlds: [string] }` |
| `world.command` | `{ command }` | `{ success, value, output: [string], thread }` |
| `world.entity` | `{ selector?, path? }` | `{ selector, path, success, output, value }` |
| `world.block` | `{ x, y, z, nbt? }` | `{ block, pos, state, properties, blockEntity? }` |
| `world.break` | `{ blockPos: [x,y,z], approach?, face?, at?: [x,y,z], timeoutTicks? }` | `{ pos, face, broken, predictedBroken, ticks, blockBefore, blockAfter, heldAfter, drops, collected }` |
| `world.use` | `{ blockPos: [x,y,z], approach?, face?, at?: [x,y,z], hand?, sneak? }` | `{ pos, face, result, blockBefore, blockAfter, blockEntityBefore, blockEntityAfter, heldBefore, heldAfter, screenClass, screenOpened }` |
| `wait.ticks` | `{ ticks }` | `{ tick }` |
| `wait.for` | `{ condition, value?, timeoutMs? }` | `{ met, condition, screenClass, inWorld }` |
| `window.resize` | `{ width, height, guiScale? }` | metrics |
| `eval` | `{ language: "groovy", code }` | `{ value, stdout, language }` |
| `log.tail` | `{ lines?, filter?, level? }` | `{ lines: [string], level, buffered }` |

### Clicking a slot

`input.hold` takes the mouse bindings as well as keys. Attack is bound to `key.mouse.left`, so
holding it — which is how every block in the game is mined — needs a name that is not a keyboard
code: `ATTACK`, `USE` and `PICK` name the bindings themselves, and `MOUSE_LEFT`/`MOUSE_RIGHT`/
`MOUSE_MIDDLE` name the buttons. Holding `USE` is eating, drinking, drawing a bow and raising a
shield; none of it could be expressed before. `HOTBAR_1`…`HOTBAR_9` name the number row, which a
bare `"3"` cannot reach because it parses as a key code first.

`player.hotbar` is the direct way to select a slot, and the one to prefer: it sets the selection
rather than queueing a click for the next tick, and it replies with what is now held. The selection
reaches the server lazily, right before the next interaction, exactly as it does for a player.

`input.scroll` with no screen open changes the hotbar slot rather than failing. Scrolling is how a
player changes slot, so refusing it there refused the only meaning scrolling has in the world. The
arithmetic matches vanilla's -- up moves the selection left, and it wraps -- and is done here rather
than through the game's own `swapPaint`, which exists on 1.21 and not on 26 while the selection
itself is reachable on every branch.

Scrolling and dragging inside a screen go through `mouseScrolled` and a
`mouseClicked` / `mouseDragged` x N / `mouseReleased` sequence, so a screen that tracks its own drag
state -- a scrollbar -- follows them. The creative inventory's list and scrollbar are covered by the
end-to-end suite on both loaders.

`input.mouseClick` with **no screen open** is an in-world click: button 0 attacks, button 1 uses.
That branch queues a key binding rather than acting, so its reply is also sent five ticks later —
without the wait it reported `screen: none` at the moment a click opened one, which is
indistinguishable from the click having done nothing. Prefer `player.useItem`, which says what it
was aimed at.

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

### Breaking a block

Mining is a *held* action. A single click does nothing, and how long it takes depends on the block,
the tool, and whether the tool can harvest it at all — which is exactly what a caller should not
have to know.

`world.break` approaches and aims like `world.use`, presses attack, and then advances the destroy
progress **once per tick** until the block gives way or `timeoutTicks` runs out. Once per tick and
not in a loop: looping does break the block, because an integrated server validates loosely, but
then the tool stops mattering and `ticks` stops meaning anything.

`drops` is what is lying on the ground afterwards, with each one's position, because a drop is
thrown rather than placed and lands a block or two away. `collected` is what reached the player's
inventory instead: a drop becomes collectable ten ticks after it spawns, which is exactly the settle
this waits out, so mining within arm's reach routinely ends with the item in hand and nothing on the
ground. **Read both.** Either one alone reports a break that dropped something as dropping nothing,
depending on timing you do not control.

`ticks` is worth reading. A diamond pickaxe takes about nine on cobblestone and bare hands take
about two hundred and drop nothing — so a large number is usually the answer to "why did my block
drop nothing", and it is the assertion that says mining happened rather than a block being removed.

`broken` is read after the settle, from the same moment `blockAfter` is, so the two cannot
contradict each other. `predictedBroken` is what the mining loop itself concluded ten ticks earlier;
when they differ, the client predicted a break the server did not agree to.

`drops` carries each item's **position** as well as its id and count, because a drop is thrown
rather than placed: it lands a block or two from where the block was, and that is where the player
has to walk to pick it up. They are read ten ticks after the break, since the drop is a server-side
entity that reaches the client after the block has gone.

### Walking

`player.walkTo` holds forward until the player is `within` blocks of a horizontal position, or
`timeoutTicks` expires. Horizontal only — walking does not control height, and requiring a `y`
would fail on every slab and drop. The heading is re-aimed each tick, and the pitch is left alone:
`player.look` at a point on the ground tilts the camera down, and walking forward while looking down
walks into the ground.

Prefer `player.teleport` unless the movement is the thing being tested — picking up a drop is the
case that needs this one.

### Using the held item

`player.useItem` is the right-click with nothing under the cursor — how a great many mods open an
item's own screen, and the one interaction that had no method: everything else takes a block
position.

With `hand: "auto"` (the default) it presses the use key binding, so the game makes the same
decision it makes for a player: a block or an entity under the crosshair takes the click and the
held item is never reached. `aimedAt` reports which — `block`, `entity` or `miss` — because "the
item did nothing" and "you were looking at a chest" are the same reply otherwise.

`hand: "main"` or `"off"` calls the game mode's use directly, skipping that decision. It is the way
to reach an off-hand item, which a player cannot aim at, and the way to use an item while facing a
block.

The key binding is *queued*: Minecraft processes it in the next tick, and what it does then may be
a server round trip. The reply is sent five ticks later, the same allowance `world.use` makes.

Five ticks is enough for a screen the client opens and not for one the server does — a container
screen arrives with an `OpenScreen` packet, whenever that is. `waitScreenTicks` awaits a screen for
up to that many ticks before replying, which is what the CLI's `--wait-screen` sends; without it the
reply reported "no screen opened" for an item that had opened one.

### Teleporting

`player.teleport` waits for the player to *settle*, not merely to arrive. A target in the air is
reached long before it is held — the player is still falling — and a reply sent then is true for one
tick and wrong for every screenshot after it. `falling` is true only when that wait timed out with
nothing under the player, and it means the `pos` in the same reply is already going stale.

`requested` is what was asked for. Gravity acts between the two, so it and `pos` legitimately
differ; a caller wanting a stable camera should check `arrived`.

`wait.for` conditions: `screen` (value = simple or qualified class name), `noScreen`, `inWorld`,
`outOfWorld`, `chunkLoaded` (value = `[x, y, z]`), `expr` (value = a Groovy expression).

A timed-out `expr` reply also carries `expression`, `evaluations`, `lastValue`, `lastValueType` and
`hint`. It needs them because `screenClass` and `inWorld` describe nothing an expression asked
about: a false expression, a throwing one and an unbound name all read identically without them.
An expression that throws, or that answers something other than a boolean, fails the request
outright — so reaching a timeout is itself a diagnosis, and the reply says so.

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

Commands run on the **server thread**, not on the thread the request arrived on. Minecraft's
command dispatch mutates world state, and running it from the client thread races the tick: it
produced a `ConcurrentModificationException` inside a mod's collision code that took the client
down, and left a following command reading blocks that had been placed but not finished. `thread`
is in the reply so that invariant is checkable from outside rather than only visible as a
`[Render thread/ERROR] [minecraft/Commands]` line in a log after something has already broken.

`CommandRunner.onServerThread` groups a sequence into one server task, so the server does not tick
between the commands in it. The determinism setup uses it: otherwise the world ticks a few times
while half its game rules are still the defaults.

`screen.tooltip`'s `source` says how the answer was reached: `slot`, `widget`, `none` (no screen),
`widgetWithoutTooltip`, or `unmodelled`. The last one is why the distinction exists — nothing at
that point is a widget or a slot, and **a mod that paints its own tooltip in `render()` looks
exactly like that**, because it registers nothing to read. Reporting all of these as "no tooltip"
said the opposite of what a screenshot of the same point showed. `note` explains the two empty
cases.

`world.break`'s `drops` lists only item entities that were not there before the break. It used to
list everything within four blocks, so a break in creative — where nothing can drop — still reported
a drop, naming whatever happened to be lying on the floor.

`world.entity` is `world.block` for things that are not blocks. It runs through the same command
source `/data get` does, because abilities, attributes and capability data are on the **server**
entity and the client's copy does not carry them — reading the client entity would answer
confidently and wrongly. `value` is the data with the command's explanatory sentence stripped;
`output` is the whole line. Pass `path` unless you really want a player's entire NBT, which is tens
of kilobytes.

`screenshot`'s `mouse` parks the pointer before the frame is captured. The cursor is part of the
frame — a hover highlight, and in some GUIs a player model or an item that turns to follow it — and
it is the only piece of render state not pinned by `options.txt`, so two captures taken after
different clicks differ for reasons unrelated to what was being tested. It belongs on the capture
rather than in a separate `input.mouseMove` so that a golden and the capture compared against it
carry the same pin.

`blockEntityBefore` and `blockEntityAfter` are the block entity's synced NBT as a string, or `""`
where there is no block entity. They are the field that catches a machine being *configured* — a
wrench turning a side, a variable card being written, a tank filling — none of which touch the
block id or the block state, and all of which otherwise read as "nothing happened".

`hello` reports `toastsEnabled` beside `evalEnabled`. Toasts are cleared every tick unless
`-Dclientdevbridge.toasts=true` (the CLI's `start --toasts`) is set, because one fading across a
frame makes that frame unreproducible — and a suppressed toast is indistinguishable from a toast
that never fired, which is why the flag's state is reported rather than assumed.

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
| `dev.blocks([namespace])` | every registered block id, or one mod's |
| `dev.items([namespace])` | every registered item id, or one mod's |
| `dev.namespaces()` | which mods registered anything — also how to tell a mod really loaded |

The code is a **script**, not a single expression: statements are allowed and the last one is its
value. So negate the last statement, not the whole thing — `def p = dev.pos(0, 4, 2); !level.getBlockState(p).isAir()`,
never `!(def p = ...; ...)`.

## `screen.snapshot`

The protocol always reports every container slot, empty ones included. The CLI omits the empty ones
from `--json` because a container is mostly empty and each one costs about eighty bytes to describe;
that is presentation, and `--include-empty` turns it off. A client reading the protocol directly
sees them all.


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

### Describing your own items

`ItemExtractors` is the item counterpart of `BlockExtractors`. A stack is described by its id,
count, name and its components' combined `toString`, and that last part is the problem: a data
component of a mod's own type prints as a class name and an identity hash, so the one field that
says what is *in* a container item is unreadable. An Everlasting Abilities bottle full of abilities
and an empty one describe identically.

```java
ItemExtractors.register(ItemAbilityBottle.class, (stack, details) ->
        details.addProperty("abilities", String.valueOf(stack.get(ABILITY_STORE).getAbilities())));
```

Whatever it writes appears as `details` on every stack description at once — `player.inventory`,
`screen.snapshot`'s container slots, and the held-item fields of `world.use`. Registration is by
`Item` class and walks up the hierarchy, so registering against a mod's base item covers all of them.

ClientDevBridge registers a few itself, for the vanilla cases where the default is unhelpful: what
block a `BlockItem` places, what a container item holds, and a damaged item's durability.

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
