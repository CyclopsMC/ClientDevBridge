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
git tag <mod_version>-mc<minecraft_version>    # e.g. 1.0.0-mc26.2
git push --tags
```

The push publishes to the Maven. The tag additionally creates a **GitHub release** with the three
loader jars attached.

Tags must be distinct per line, because all three branches tag in the same repository. The jars
themselves are already unambiguous — every filename carries its Minecraft version and loader — so
the only thing needing a convention is the tag name.

## Why the release assets are not how the mod is resolved

The jars are attached to releases for humans: to download a build without a login, and to pin a
known one. They are deliberately **not** what `clientdevbridge-cli` resolves against.

The CLI injects a Gradle *coordinate*, and Gradle resolves it. A release asset is not a Maven
repository — no metadata, no version layout — so pointing at one means the CLI downloading, caching,
verifying and version-selecting the jar itself. That is work Gradle already does correctly, it only
helps consumers who go through the CLI, and it would need the CLI to either query the GitHub API
(60 requests an hour unauthenticated, shared per IP, which CI runners exhaust) or hard-code exactly
the version knowledge the design keeps to one table.

The problem worth solving is the one underneath: **GitHub Packages needs a token even for public
packages.** That belongs at the Maven layer, not in the CLI. Two ways out, both leaving the CLI
untouched apart from one URL in `clientdevbridge-cli/src/initscript.ts`:

- **A static Maven on GitHub Pages.** Publish the same repository layout to a `gh-pages` branch (or
  a dedicated `CyclopsMC/maven` repository) and serve it anonymously over HTTPS. Cheapest by far;
  the one wrinkle is that three branches deploying concurrently need a retry-and-rebase, or a
  separate repository to avoid pushing over each other.
- **Maven Central.** Properly anonymous, permanent and mirrored, at the cost of a Sonatype account,
  namespace verification for `org.cyclops`, and GPG signing. The right answer for something meant
  to be depended on widely; heavier than a dev-only tool usually justifies.

Until one of those happens, a consumer needs `MAVEN_USERNAME` / `MAVEN_KEY` in their environment —
the same credentials they already need for CyclopsCore.

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
