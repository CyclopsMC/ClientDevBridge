# Changelog

All notable changes to ClientDevBridge are documented here.

## Unreleased

### Phase 9 — making the loop faster

- `input.slotClick`: a container click with an explicit `ClickType`, which is the only way to
  express a shift-click. A screen works out what a click meant from the static
  `Screen.hasShiftDown()`, which reads the real GLFW keyboard state, and `Screen.mouseClicked` has
  nowhere to pass a modifier anyway — so the operation is named rather than the input it would be
  inferred from. It does not run a screen's own `slotClicked` override, which is `protected` and so
  unreachable; the alternative is a mixin on `hasShiftDown`, and this mod has none.
- `ScriptHelpers.prop` and `props`: a block's state properties by name, because the direct route
  names `BlockStateProperties` and so hits the class loader wall `dev` exists to remove. Asking for
  a property a block does not have lists the ones it does.
- `player.teleport` waits for the player to *settle*, not merely to arrive. The old condition was
  satisfied while they were still falling, so the reply described a position held for one tick and
  every screenshot after it was of somewhere else. `isAt` is unchanged — `Aim` puts the player in
  mid-air on purpose — and the new `falling` field says when the position in the reply is stale.

- `ScriptHelpers`, bound into `eval` as `dev`: `pos`, `vec`, `block`, `blockId`, `blockEntity`,
  `nbt` and `item`. The game is loaded by a transforming class loader and the script engine is not,
  so a script could not construct a `BlockPos` at all; now it does not have to name the class.
- `ScreenControl.close` presses escape rather than nulling the screen, so a screen's own exit
  handling runs: the container-close packet, and Integrated Dynamics' save-on-escape, which was
  silently discarding every value typed into an aspect settings screen.
- `EvalHandler` explains the two mistakes that are easy to make here — that the code is a script
  and not one expression, and that game classes come from `dev`.

### Phase 8 — interacting with a chosen side of a block

- `Aim`: where on a block an interaction is pointed, as both the hit result's point and face and
  the position and rotation the player needs for the ray from their eye to arrive through it.
  `screen.open` and the new `world.use` take `face` or `at`.
- `world.use`: the general right-click, for interactions that never open a screen — placing a block
  or a cable part, tools, wrenching with `sneak`. It reports the interaction's own outcome, in a
  vocabulary normalised across branches because `InteractionResult` is an enum on 1.21 and a sealed
  interface of records on 26.
- `BlockExtractors`: the counterpart of `SnapshotExtractors` for `world.block`, so a mod can say
  what distinguishes one instance of its block entity from another. A cable with a part on it and a
  bare one are otherwise identical in every field the description carries.
- `scripts/e2e-multipart.sh`: the end-to-end suite against Integrated Dynamics' cables, which is
  the only coverage of a block that resolves a click by raytracing rather than by hit result.

### Phase 1 — headless boot and screenshots

- `BridgeServer`: a localhost-only WebSocket endpoint, started from the client setup hook and only
  when `-Dclientdevbridge.enabled=true`. Implemented directly on `java.net` because Minecraft ships
  Netty without `netty-codec-http`, so `websocketx` is not on the classpath.
- A JSON-RPC 2.0 `Dispatcher` with a handler registry and the `hello` handshake.
- Handlers: `status`, `screenshot`, `wait.ticks`, `log.tail`.
- `mcadapter`: frame capture, the coordinate spaces, the tick clock, and the client-thread
  scheduling rule, isolated from the protocol and handler layers.

### Phase 0 — skeletons

- Multiloader project (Fabric and NeoForge) laid out like CyclopsMC/Flopper, for Minecraft 1.21.1.
- `e2e/consumer`: a minimal consumer mod used as the end-to-end test bed where Flopper cannot be built.

### Phase 2 — world, input and commands

- `world.reset` (programmatic creative superflat, or a committed template), `world.load`,
  `world.leave`, `world.list`, `world.command` with feedback capture, and `world.block`.
- `input.*` (mouse move/click/drag/scroll, key, type, hold), `player.*`, `screen.open` by block
  position and `screen.close`, `wait.for`.
- `screen.changed`, `world.joined` and `world.left` notifications, derived from a single per-tick
  state comparison rather than from loader-specific events.

### Phase 3 — structured snapshot and tooltips

- `screen.snapshot` with the `SnapshotExtractor` registry, extractors for the vanilla widget types,
  and `AbstractContainerScreen` slot extraction in absolute GUI space.
- `screen.tooltip`, reading the hovered slot's item tooltip or the hovered widget's attached one.

### Phase 4 — determinism and golden screenshots

- `window.resize` with GUI scale, and the determinism settings pinned in `options.txt` at launch.
- `eval` (Groovy through `javax.script`, gated behind `-Dclientdevbridge.eval=true`) and `wait --expr`.

### Phase 5 — fast iteration

- `hotswap`: recompiles the consumer and redefines changed classes in the running client over JDWP,
  distinguishing classes the JVM refused (a restart is needed) from ones not yet loaded (no action).

### Phase 6 — cloud support and agent documentation

- `docs/AGENT_WORKFLOW.md`, `docs/PROTOCOL.md`, `docs/cloud-setup.md`, a ready-to-paste consumer
  `CLAUDE.md` snippet, and a Claude Code skill.
- `scripts/cloud-setup.sh` and the verified network allowlist.
- `scripts/e2e.sh`, running the whole scenario against a real client on both loaders.
- CI: build and publish, the end-to-end suite on both loaders, and a guard that fails an
  unannounced `PROTOCOL_VERSION` change.

### Fixes from independent validation (see `validation/phase-1-6.md`)

- `world.command` now returns `success` and `value` alongside `output`, so a caller can tell a
  built scene from one that was never built; `setblock`, `give` and `command` exit non-zero when the
  game rejects them. Additive, so the protocol version is unchanged.
- `input.mouseMove` writes `MouseHandler`'s position directly instead of asking GLFW to move the
  cursor, which is ignored while the window is unfocused — as it always is under a virtual display.
  Hover highlights, hovered slots and rendered tooltips now follow synthetic mouse moves.
- `status` and `stop` probe the bridge port when no session is recorded, so an orphaned client is
  reported with its pid instead of being called "not running".
- `world.load` validates the world name before leaving the current world.
- A widget that reports an empty rectangle is marked `boundsUnknown` rather than claimed to be
  `0x0`.
- The CLI no longer prints `function toString()` for object and array `eval` results.

### Fixes found by porting forwards

Everything here was found by driving a newer Minecraft version and fixed on this branch first, as
the upmerge rule asks.

- No handler, protocol or snapshot file names a version-sensitive Minecraft member any more.
  `WorldHandler`, `WaitHandler` and `EvalHandler` were reaching into `Minecraft` directly;
  `ClientState` grew `isWorldReadyAt`, `isChunkLoaded`, `scriptBindings` and `vanillaClassLoader`,
  and they go through those. Two of the Minecraft 26.2 compile errors landed in `handler/`, which
  the layout exists to prevent.
- `screen.open` waits for its approach teleport to actually land instead of giving the round trip
  a fixed five ticks. Right after a world is created the integrated server has plenty else to do,
  and the interaction was silently rejected as out of reach.
- `status` reports `gameDir`. Which directory the client runs in is decided by the Gradle plugin
  rather than by the loader, so the CLI cannot know it before launch; this is how it checks the
  guess it pinned `options.txt` into.
- CI fails when the access widener exists and `fabric.mod.json` does not declare it. That
  combination has no runtime effect and nothing about the build says so — the mod loads, and the
  first sign is an `IllegalAccessError` from whichever widened field a caller reaches first.

### Fixes from independent validation (see `validation/phase-7.md`, `validation/phase-7-rerun.md`)

- `screen.open` skips the approach teleport when the block is already in reach. A teleport the
  player does not need is not merely wasted: the server ignores interactions from a client it is
  still waiting on a teleport confirmation from, so every repeated `open-gui` on the same block
  failed. It also compares the screen instance rather than its class name, so re-opening the same
  kind of screen is reported as having opened.
- `window.resize` applies the size and the GUI scale as separate steps, waiting for the window to
  actually change in between. GLFW delivers a resize asynchronously, so validating the scale in the
  same step measured the window that was being replaced — and rejected valid scales after having
  already applied the resize.
- `world.reset` checks the template exists before leaving and deleting the world. A typo'd name
  used to cost the caller the world they had.
- `input.mouseDrag` and `screen.tooltip` reject an off-screen point like every other input method.
  A drag dropped what it was carrying on the floor and reported success; a tooltip left the cursor
  parked outside the window, so every later snapshot reported an impossible position.
- The reported mouse position comes from the game rather than from the last position the bridge
  asked for. On a virtual display the real pointer starts at the centre of the window, so a fresh
  snapshot said "mouse at 0,0" and marked the centre slot hovered in the same breath.
- An out-of-bounds point is reported in the space the caller used, not converted into GUI space and
  then labelled "pixel".
- `world.load` says a run directory has no worlds at all rather than printing an empty list.
- `hello` carries `projectDir`, and `player.teleport` reports the position that was requested
  alongside the one the player ended up at.

## Minecraft 26.2 (`master-26`)

Upmerged from `master-26-lts`. The toolchain is unchanged; the client is not.

26.2 moved the screen, the overlay and the toasts off `Minecraft` and onto `Minecraft.gui`
(`net.minecraft.client.gui.Gui`), so `minecraft.screen` is `minecraft.gui.screen()`,
`setScreen` is `setScreenAndShow`, `getOverlay()` is `gui.overlay()` and `getToastManager()`
is `gui.toastManager()`. The main render target moved to `gameRenderer.mainRenderTarget()`, and
the GL renderer string is now `RenderSystem.getDevice().getDeviceInfo().name()`.

Two of those renames landed outside `mcadapter/` — `WorldHandler` and `EvalHandler` were reaching
into `Minecraft` themselves — which is the boundary failing, not the port. `ClientState` grew
`isWorldReadyAt`, `isChunkLoaded`, `scriptBindings` and `vanillaClassLoader`, the handlers now go
through them, and the fix was made on `master-1.21-lts` first and upmerged, as the rule says. No
handler, protocol or CLI file contains a version-sensitive Minecraft call any more.

## Minecraft 26.1.2 (`master-26-lts`)

Upmerged from `master-1.21-lts`. Everything outside `mcadapter/`, the build files and the access
widener compiled unchanged, which is what the layout exists to achieve.

The 26 toolchain: Gradle 9.3, Java 25, ModDevGradle 2 for both `loader-common` and
`loader-neoforge` (NeoGradle userdev is gone), Loom 1.15 under its new `net.fabricmc.fabric-loom`
plugin id, and access wideners in the `official` namespace.

The adapter changes the port needed:

- Reading a frame is now an asynchronous texture-to-buffer copy with a callback, and `NativeImage`
  no longer exposes PNG bytes, so encoding goes through a temporary file.
- Input arrives as `MouseButtonEvent`, `KeyEvent` and `CharacterEvent` records rather than loose
  parameters.
- `LevelSettings` is a record without game rules; difficulty and hardcore moved into
  `DifficultySettings`.
- `GameRules` moved package; `AbstractScrollWidget` became `AbstractScrollArea`;
  `AbstractWidget#getTooltip` is behind a holder; `Component.Serializer` became
  `ComponentSerialization`; `Window#getWindow` became `handle()`; `Minecraft#resizeDisplay` became
  `resizeGui()`; `Minecraft#getToasts` became `getToastManager()`; `Inventory#selected` is behind
  accessors; command permissions take a `PermissionSet`; `ResourceKey#location` became
  `identifier()`; and `GlUtil` gave way to the GPU device's implementation information.
