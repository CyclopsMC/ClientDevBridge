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
