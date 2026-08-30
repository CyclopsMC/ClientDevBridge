# Releasing

The mod publishes one artifact line per branch. A consumer never picks a branch: they name a
Minecraft version, and `clientdevbridge-cli` maps it to the line that serves it.

| Branch | Minecraft | Artifacts |
|---|---|---|
| `master-1.21-lts` | 1.21.1 | `clientdevbridge-1.21.1-{fabric,neoforge}` |
| `master-26-lts` | 26.1.2 | `clientdevbridge-26.1.2-{fabric,neoforge}` |
| `master-26` | 26.2 | `clientdevbridge-26.2-{fabric,neoforge}` |

## What the CI already does

| Trigger | What runs |
|---|---|
| every push and pull request | `./gradlew build` (both loaders, Spotless included), the access-widener guard, and the end-to-end suite against a real headless client on both loaders |
| a pull request touching `Reference.java` or `docs/PROTOCOL.md` | the protocol guard, which fails an unannounced `PROTOCOL_VERSION` change |
| a push to any `master*` branch | `./gradlew publish` |

Publishing is on **push to a branch**, not on a tag: every branch continuously publishes its own
line. Tagging only sets `RELEASE=true`, which drops the `-SNAPSHOT`-style version decoration.

## One-time setup

Three repository secrets, all consumed by `./gradlew publish`:

- **`MAVEN_URL`** — where to deploy. For the Cyclops convention this is the GitHub Packages Maven
  for `CyclopsMC/packages`, which is what `clientdevbridge-cli` injects into a consumer's build and
  what Flopper already resolves CyclopsCore from.
- **`MAVEN_USERNAME`** and **`MAVEN_KEY`** — a token that can write packages there.

Note for consumers: **GitHub Packages requires a token even for public packages.** A mod developer
using ClientDevBridge therefore needs `MAVEN_USERNAME` / `MAVEN_KEY` (or `gpr.user` / `gpr.key`
Gradle properties) in their environment, exactly as they already do for CyclopsCore. If that
friction is unacceptable for this project specifically, the alternative is deploying to an
anonymously readable Maven and changing the repository the CLI injects, in
`clientdevbridge-cli/src/initscript.ts`. That is a decision, not an oversight — the current setup
deliberately matches every other Cyclops artifact.

## Cutting a release

```bash
git checkout <branch>
# update CHANGELOG.md, commit
git push
```

The push publishes. To make it a tagged release, tag the same commit and push the tag.

## Changes that touch more than one branch

Fixes land on the **oldest** affected branch and are upmerged forwards:

```
master-1.21-lts  →  master-26-lts  →  master-26
```

Each branch records where its changes come from in `.upmerge-src-branch`. A fix made on a newer
branch first is a fix the older ones silently lose.

A `PROTOCOL_VERSION` bump must land on **every** active branch in the same release train, together
with the matching `clientdevbridge-cli` release. The protocol guard exists to stop one branch
drifting alone; do not work around it.

## Adding a Minecraft version

1. Branch from the newest existing line, following the Cyclops naming (`master-<version>` for the
   trunk, `master-<version>-lts` for a long-term line).
2. Bump `gradle.properties`, set `clientdevbridge_line`, and write `.upmerge-src-branch`.
3. Port `loader-common/src/main/java/org/cyclops/clientdevbridge/mcadapter/` — and nothing else. If
   a port needs a change outside `mcadapter/`, the mixins, or the build files, that is a bug in the
   adapter: widen it on the oldest affected branch first and upmerge. See
   `loader-common/src/main/java/org/cyclops/clientdevbridge/mcadapter/README.md`.
4. Run `scripts/e2e.sh neoforge` and `scripts/e2e.sh fabric`.
5. Add the line to `clientdevbridge-cli/src/artifacts.ts`, record a transcript per loader with
   `scripts/record-fixture.mjs`, and release the CLI **after** this branch's artifacts are on the
   Maven.
