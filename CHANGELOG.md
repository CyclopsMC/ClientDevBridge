# Changelog

All notable changes to ClientDevBridge are documented here.

## Unreleased

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
