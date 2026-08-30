# Running in a cloud sandbox

ClientDevBridge is designed for Claude Code on the web and for CI, where there is no display and
no GPU. Everything is a bash command, so there is no MCP server to configure and nothing to install
in the agent itself.

## Quick start

```bash
# Any branch's copy of this script does the same thing; pick the one for your Minecraft version.
curl -fsSL https://raw.githubusercontent.com/CyclopsMC/ClientDevBridge/master-1.21-lts/scripts/cloud-setup.sh | bash
# or, from a checkout:
./scripts/cloud-setup.sh --project path/to/your/mod
```

Then:

```bash
cd path/to/your/mod
clientdevbridge doctor
clientdevbridge start
clientdevbridge screenshot
```

## What "headless" means here

A **real Minecraft client on a virtual display**, not a client that skips rendering. On Linux
without `$DISPLAY`, the CLI wraps the launch in `xvfb-run` and forces Mesa's llvmpipe software
rasteriser (`LIBGL_ALWAYS_SOFTWARE=1`). Screenshots therefore show exactly what a player would see,
which is the entire reason to do it this way rather than stubbing the renderer.

It is slower than a GPU — expect 20–60 fps at 854x480 — but entirely usable, and frame rate does
not affect correctness.

## System packages

```bash
sudo apt-get update
sudo apt-get install -y xvfb libgl1-mesa-dri mesa-utils libglu1-mesa
```

`libgl1-mesa-dri` is the one that matters: without it there is no software rasteriser and the
client dies during shader loading with no useful error. `clientdevbridge doctor` checks for it
explicitly.

You also need a **JDK** and **Node 20+**. Which JDK depends on the Minecraft version the mod
targets — 1.21 needs 21, the 26 line needs 25 — and the mod's own `gradle.properties` states it as
`java_version`.

Install it where Gradle will find it, which is `JAVA_HOME`, **not** whichever `java` comes first on
the PATH: the loader plugins check the JDK Gradle itself runs on, and Loom refuses to configure a
Minecraft 26 project on Java 21 with an error that never mentions `JAVA_HOME`.
`clientdevbridge doctor` reports the JDK Gradle will actually use and where it came from, and
`clientdevbridge start` runs Gradle on an installed JDK that satisfies the project when the
environment's own is too old, saying so when it does.

## Network allowlist

A cold checkout has to reach all of these to build and launch. This list is what
`clientdevbridge doctor` probes, and it was arrived at by watching a real cold run fail.

| Host | Needed for |
|---|---|
| `services.gradle.org` | the Gradle distribution the wrapper downloads |
| `plugins.gradle.org` | the Gradle plugin portal (Loom, ModDevGradle) |
| `repo.maven.apache.org` | Maven Central |
| `piston-meta.mojang.com` | the Minecraft version manifest |
| `piston-data.mojang.com` | the Minecraft client and server jars |
| `libraries.minecraft.net` | Minecraft libraries (LWJGL, authlib) |
| `resources.download.minecraft.net` | Minecraft assets |
| `maven.neoforged.net` | NeoForge and NeoForm |
| `maven.minecraftforge.net` | `srgutils` and `unsafe`, pulled in transitively by NeoGradle |
| `maven.fabricmc.net` | the Fabric loader, Loom, and intermediary mappings |
| `maven.parchmentmc.org` | Parchment parameter mappings |
| `repo.spongepowered.org` | Mixin |
| `maven.pkg.github.com` | CyclopsMC packages, including ClientDevBridge itself |
| `registry.npmjs.org` | the CLI, via `npx` |

`maven.minecraftforge.net` is the one people miss: NeoGradle still resolves two Forge artifacts
through it, and without it the build fails during *configuration*, before any task runs, which
makes it look like a plugin problem rather than a network one.

If your consumer mod depends on CyclopsCore (or anything else on GitHub Packages), that Maven
needs credentials — `MAVEN_USERNAME` and `MAVEN_KEY`, or `GITHUB_USER` and `GITHUB_TOKEN` with
`read:packages`. A token without that scope returns `401`, and Gradle reports it as
`Username must not be null!`, which is not a helpful message.

Run `clientdevbridge doctor` to see which of these your sandbox can actually reach.

## Warming the cache

The first `start` is dominated by downloads: Minecraft, the loader, and the Gradle distribution
add up to a few hundred megabytes. Pre-warm it once, so the first real command is not what pays:

```bash
cd path/to/your/mod
./gradlew --no-daemon tasks > /dev/null
```

`scripts/cloud-setup.sh --project <dir>` does this for you.

## Sessions that outlive the VM

A cloud VM can be reclaimed between commands. `.clientdevbridge/session.json` records the client's
pid, and every command checks whether that process still exists, so on a fresh VM `status` reports
"not running" cleanly rather than hanging or reporting a client that is long gone. `start` is
idempotent from a cold clone.

If a client is somehow orphaned — its session file deleted while the process lives on — `start`
identifies the process holding the port and tells you what to kill.

## Screenshots

Claude Code reads images by file path, so the CLI **never** prints base64. `screenshot` writes the
PNG and prints its absolute path on its own line; hand that path to your file-reading tool.

## Known limits in a sandbox

- **No audio.** OpenAL fails to open a device and the client logs an error at startup. It is
  harmless and expected.
- **No narrator.** `libflite` is missing, which produces a long stack trace during startup. Also
  harmless.
- **Animated textures still animate.** Lava, fire, water and portals are not deterministic frame to
  frame; see `docs/AGENT_WORKFLOW.md` for how to compare around them.
