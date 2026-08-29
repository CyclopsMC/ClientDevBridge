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

The **wire protocol is identical on every branch**, so one CLI release drives all of them. Fixes land
on the oldest affected branch and are upmerged forwards.

## Building

```bash
./gradlew build                # both loaders, plus unit tests
./gradlew publishToMavenLocal  # so the CLI injects *your* build
./gradlew spotlessApply        # formatting
```

See `AGENTS.md` for the full developer and agent guide, and `docs/` for the protocol reference.
