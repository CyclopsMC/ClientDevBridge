# ClientDevBridge

Drive a Minecraft **dev client** from the shell, so a coding agent can launch it, click through it,
and look at what it rendered — structurally and visually.

ClientDevBridge is the mod half. The other half is
[`@cyclopsmc/clientdevbridge-cli`](https://github.com/CyclopsMC/clientdevbridge-cli), which is what
you actually run.

```bash
npx @cyclopsmc/clientdevbridge-cli start --project path/to/your/mod
npx @cyclopsmc/clientdevbridge-cli screenshot
# /path/to/your/mod/.clientdevbridge/screenshots/screenshot_2026-08-29_19-12-03.png
```

The mod is injected into your checkout at launch through a generated Gradle init script, so **you
never have to add it as a dependency**. It listens on `127.0.0.1` only, and it does nothing at all
unless the JVM was started with `-Dclientdevbridge.enabled=true`.

## Dev-only, by construction

This mod is never shipped to players. `BridgeConfig` reads `clientdevbridge.enabled` and the server
is simply not created when it is unset, so a stray copy in a mods folder opens no socket.

## Layout

| Directory | What lives there |
|---|---|
| `loader-common/` | Everything shared: the transport, the protocol, the handlers, and `mcadapter/`. |
| `loader-fabric/`, `loader-neoforge/` | The loader entry point and a single client-tick hook each. |
| `loader-common/.../mcadapter/` | **All** version-sensitive Minecraft internals — see its README. |
| `e2e/consumer/` | A minimal consumer mod, used as the local end-to-end test bed. |

The `mcadapter` boundary is the point of the layout: porting to a new Minecraft version should mean
porting that package and the mixins, and nothing else.

## Minecraft versions

One branch per Minecraft version line, following the Cyclops branching model:

| Branch | Minecraft | Artifacts |
|---|---|---|
| `master-1.21-lts` | 1.21.1 | `clientdevbridge-1.21.1-{fabric,neoforge}` |
| `master-26-lts` | 26.1.2 | `clientdevbridge-26.1.2-{fabric,neoforge}` |
| `master-26` | 26.2 | `clientdevbridge-26.2-{fabric,neoforge}` |

Every branch lists all of them: which branch you happen to be reading is not what decides which
Minecraft versions exist, and `clientdevbridge-cli` drives all of them from one release.

The **wire protocol is identical on every branch**, so one CLI release drives all of them. Fixes land
on the oldest affected branch and are upmerged forwards along
`master-1.21-lts` → `master-26-lts` → `master-26`; each branch's `.upmerge-src-branch` records where
its changes come from.

## Building

```bash
./gradlew build                # both loaders, plus unit tests
./gradlew publishToMavenLocal  # so the CLI injects *your* build
./gradlew spotlessApply        # formatting
```

## Documentation

| Document | What it covers |
|---|---|
| [`docs/AGENT_WORKFLOW.md`](docs/AGENT_WORKFLOW.md) | The edit → hotswap → inspect → compare loop, in full |
| [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | Every method, the snapshot schema, and how to register your own extractors |
| [`docs/cloud-setup.md`](docs/cloud-setup.md) | Running headless in a cloud sandbox or CI, with the network allowlist |
| [`docs/snippets/CONSUMER_CLAUDE.md`](docs/snippets/CONSUMER_CLAUDE.md) | A ready-to-paste section for your mod's `CLAUDE.md` |
| [`skills/clientdevbridge/SKILL.md`](skills/clientdevbridge/SKILL.md) | A Claude Code skill |
| [`AGENTS.md`](AGENTS.md) | Developer guide: the `mcadapter` rule, the threading rules, how to port a version |

## What it can do

- **`snapshot`** — the widget tree of the open screen: every widget's type, label, bounds, state and
  value, plus container slots with their absolute positions and contents.
- **`screenshot`** — the framebuffer as a PNG, written to a file whose path is printed.
- **`compare`** — that screenshot against a committed golden image, with a readable diff on failure.
- **Input** — clicks, drags, scrolls, key presses and typing, delivered through the same listener
  methods GLFW callbacks use, so widgets see exactly what a real click produces.
- **World control** — a deterministic creative superflat world on demand, commands on the
  integrated server, block and inventory inspection.
- **`hotswap`** — recompile and redefine changed classes in the running client over JDWP.
- **`eval`** — a Groovy escape hatch for anything the typed methods do not cover.
