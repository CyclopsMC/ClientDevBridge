# AGENTS.md — developer and AI agent guide

ClientDevBridge is a **dev-only** Minecraft mod that lets a coding agent launch a real client, drive
it, and inspect what it rendered. It is one half of a pair; the other is
[`clientdevbridge-cli`](https://github.com/CyclopsMC/clientdevbridge-cli), which is what people run.

Read this before changing anything here.

## Architecture in one picture

```
clientdevbridge-cli  ──ws://127.0.0.1:25599──▶  Minecraft client JVM
  (short-lived, version-agnostic)                 └─ clientdevbridge
                                                      ├─ net/        WebSocket transport
                                                      ├─ protocol/   JSON-RPC 2.0
                                                      ├─ handler/    one file per method group
                                                      ├─ snapshot/   the extractor API mods use
                                                      └─ mcadapter/  ALL version-sensitive code
```

The CLI starts the client with a generated Gradle init script, so **consumer repositories are never
edited**.

## Where to put a change

Two repositories, and most non-trivial changes touch both:

| Repository | Holds | Branching |
|---|---|---|
| `CyclopsMC/ClientDevBridge` (this one) | the mod: transport, protocol, handlers, `mcadapter/` | one branch per Minecraft line |
| `CyclopsMC/clientdevbridge-cli` | the CLI people run, and the recorded protocol fixtures | a single `master`, version-agnostic |

A protocol change lands **mod-side first**, on the oldest affected branch, and upmerges; the CLI
subcommand and re-recorded fixtures follow. The other order ships a CLI that talks to builds nobody
can resolve yet.

The branch table and the upmerge order live in [README.md](README.md) and
[RELEASING.md](RELEASING.md). Which branch you target depends on your access:

- **With push access here** — land the change on the oldest affected branch, then upmerge forwards
  yourself:

  ```bash
  git checkout master-1.21-lts                            && ./gradlew build && git push
  git checkout master-26-lts && git merge master-1.21-lts && ./gradlew build && git push
  git checkout master-26     && git merge master-26-lts   && ./gradlew build && git push
  ```

  `./gradlew build` runs Spotless and the unit tests, and a merge that compiles on 1.21 can fail on
  26 inside `mcadapter/` — pushing an unbuilt merge is the usual way these branches go red.
  `git worktree` for the other two branches saves re-resolving each one's Minecraft dependencies.

- **Without push access** — target **`master-1.21-lts`**, the lowest Minecraft line, and nothing
  else; the maintainer upmerges from there. A pull request against `master-26*` is a change the
  older branches silently lose. Only if the change genuinely cannot exist on 1.21 (it touches an
  API that branch does not have) does it belong on a newer branch instead — say so in the pull
  request.

## The one rule that matters: `mcadapter/`

Everything that touches version-churning Minecraft internals lives in
`loader-common/src/main/java/org/cyclops/clientdevbridge/mcadapter/`. Read that package's
`README.md` before adding anything.

`net/`, `protocol/`, `handler/`, `snapshot/` and the whole CLI must compile and behave identically
on every branch. If a port needs a change outside `mcadapter/`, the mixins, or the build files,
**that is a bug in the adapter**: widen the interface on the oldest affected branch first, then
upmerge.

Enforce this in review.

## Loader surface

The loaders provide exactly one hook, `IClientHooks#registerClientTick`, plus their own identity.
Screen changes and world transitions are derived by comparing state each tick rather than by
subscribing to loader-specific events, which is why both loaders report them at identical moments.

Prefer this. Adding a loader-specific event means two implementations that can drift; adding a
mixin means one more thing to port. There are currently **no mixins**, and that is worth keeping.

## Threading — read this before writing a handler

Minecraft's client thread is a `ReentrantBlockableEventLoop`. Three rules follow, and each one was
learned from a real deadlock:

1. **Touching game state goes through `ClientThread.submit`/`run`**, and the WebSocket thread awaits
   the future. Never block the client thread waiting for anything.

2. **Blocking vanilla calls go through `ClientThread.runOnTick`**, not `submit`. Loading a world and
   disconnecting from one block the client thread and pump `runTick` while they wait. Because the
   loop refuses to run scheduled tasks while already inside one, starting such a call from a
   scheduled task waits forever for callbacks that are themselves scheduled tasks.

3. **Waiting for a blocking operation to finish goes through `Polling`, not `TickClock`.** During a
   world load the client may not deliver ticks at all — and whether it does differs between
   loaders. `TickClock` is for `wait.ticks`; `Polling` is for "is it ready yet".

Likewise, never write to a socket from the client thread. `BridgeConnection` queues into a bounded
outbox drained by its own thread precisely so a client that stops reading cannot apply TCP
backpressure into the game loop.

## Determinism

Screenshot comparison only works if the client holds still. What is already handled:

- `options.txt` is pinned at launch by the CLI (GUI scale, vsync, clouds, particles, view bobbing).
- `world.reset` sets the game rules that stop time, weather, mobs and random ticks, and builds a
  stone platform under the spawn so the player is not falling.
- Toasts are suppressed every tick — they fade over seconds and would otherwise poison any
  screenshot taken near one.
- `player.teleport` waits for the position to round-trip before returning.

What is **not** handled, deliberately: animated block textures (lava, fire, water, portals) advance
every frame and no game rule stops them. A golden image containing one needs `--threshold` or a
`--region` that excludes it.

## Building and testing

```bash
./gradlew build                 # both loaders, plus unit tests
./gradlew test                  # unit tests only
./gradlew publishToMavenLocal   # so the CLI injects *your* build
./gradlew spotlessApply         # formatting
./scripts/e2e.sh neoforge       # full end-to-end against a real client
./scripts/e2e.sh fabric
```

Unit tests cover the version-independent layers — the WebSocket framing, the JSON-RPC dispatcher,
parameter validation, the log ring — and deliberately do not depend on Minecraft. Everything that
touches the game is covered by `scripts/e2e.sh`, which boots a real client.

`scripts/e2e.sh` runs against, in order of preference: `$CDB_CONSUMER_DIR`, a checkout of
`CyclopsMC/Flopper` on the matching branch when CyclopsCore is resolvable, or `e2e/consumer` — a
minimal fixture in this repository that depends on nothing but the loader. Flopper is the better
test bed; the fixture is what keeps the suite runnable without package credentials.

**Both must pass before committing.** Run the loader you did not touch too: the two have diverged
in behaviour before, and only the end-to-end run catches it.

## The protocol is a contract

`Reference.PROTOCOL_VERSION` is **identical on every branch**. Within a version, only additive
changes are allowed. A breaking change bumps the version and has to land on every active branch in
the same release train, or a single CLI release stops being able to drive them all.

When you add a method:

1. Put the version-sensitive part in `mcadapter/`, the rest in `handler/`.
2. Validate parameters through `Params`, whose messages are what an agent sees when it gets a call
   wrong. Say what was expected *and* what arrived.
3. Add the CLI subcommand, and make its output readable without `--json`.
4. Extend `scripts/e2e.sh`.
5. Re-record the protocol fixtures (`node scripts/record-fixture.mjs <name>` in the CLI repo)
   against a running client, so the CLI's compatibility tests cover it.
6. Document it in `docs/PROTOCOL.md`.

## Code changes across Minecraft updates

When asked to "fix upmerge issues" or "update to the next Minecraft version": the old version is in
the parent directory's `.upmerge-src-branch`, the new one in `gradle.properties`.

Work `mcadapter/` first, and treat anything else needing a change as a signal to widen the adapter.
Do **not** guess class or method names from memory — inspect the branch's own decompiled Minecraft
sources. On this branch the build writes them to:

```
loader-common/build/moddev/artifacts/vanilla-<neoform_version>-minecraft-sources.jar
```

Unzip it and read the real code. For non-trivial API changes, the NeoForge primers
(<https://github.com/neoforged/.github/tree/main/primers>) and the
<https://neoforged.net/> and <https://fabricmc.net/blog/> blogs explain the reasoning.

After porting, `./gradlew build` **and** both `scripts/e2e.sh` runs must pass.

## Key principles

1. **Minimal changes.** Match the surrounding code.
2. **`mcadapter/` isolation** is the whole point of the layout.
3. **Error messages are a feature.** An agent cannot ask a follow-up question; the message has to
   say what to do next.
4. **Never break the protocol within a version.**
5. **Dev-only.** Nothing here should ever be reachable in a player's game: the server is loopback
   only and does not start without `-Dclientdevbridge.enabled=true`.
