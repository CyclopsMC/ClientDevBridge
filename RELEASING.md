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

### The releases Maven (how consumers get the mod)

Builds are published to [CyclopsMC/ClientDevBridge-Releases](https://github.com/CyclopsMC/ClientDevBridge-Releases),
a static Maven repository served by GitHub Pages at
`https://cyclopsmc.github.io/ClientDevBridge-Releases`. That is the repository
`clientdevbridge-cli` injects into a consumer's build, with no credentials block — which is the
whole reason it exists. The CyclopsMC GitHub Packages Maven that the rest of the Cyclops artifacts
live in requires a token *even for public packages*, and a tool whose selling point is that a mod
repository needs no setup cannot ask every user for one.

Two things to configure, once:

1. In **ClientDevBridge-Releases**: Settings → Pages → deploy from branch `master`, folder `/` (root).
2. In **this** repository: a secret **`RELEASES_TOKEN`** that can push to ClientDevBridge-Releases —
   a fine-grained PAT with Contents: read and write on that repository, or a GitHub App
   installation token. Without it the publish step skips itself rather than failing the build, so
   forks and unconfigured checkouts still go green.

CI then publishes on every push to a `master*` branch: it clones the releases repository, runs
`./gradlew publish` *into* that checkout, commits and pushes. It reads the branch to push to from
the clone rather than naming one, so the releases repository can rename its default branch without
this workflow knowing. Publishing into the existing
checkout rather than into a fresh directory is deliberate — Gradle merges each artifact's
`maven-metadata.xml` with the one already there, and that file is what makes a dynamic `+`
version resolvable. Staging into an empty directory would quietly rewrite the version history
down to whatever that one run built.

All three branches publish into the same tree and can finish at the same time. They write disjoint
paths, because every artifact id carries its Minecraft version, so the push retry in CI is for the
race and never for a conflict.

### The GitHub Packages Maven (optional, unchanged)

The older `MAVEN_URL` / `MAVEN_USERNAME` / `MAVEN_KEY` secrets still work and still publish, for
consistency with the other Cyclops artifacts. They are no longer what the CLI resolves against, and
nothing breaks if they are left unset.

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

The jars attached to a GitHub release are for humans: to download a build without a login, and to
pin a known one. They are deliberately **not** what `clientdevbridge-cli` resolves against — the
releases Maven above is.

The CLI injects a Gradle *coordinate*, and Gradle resolves it. A release asset is not a Maven
repository, so pointing at one would mean the CLI downloading, caching, verifying and
version-selecting the jar itself. That is work Gradle already does correctly, it would only help
consumers who go through the CLI rather than anyone depending on the mod directly, and it would
need the CLI to either query the GitHub API (60 requests an hour unauthenticated, shared per IP,
which CI runners exhaust) or hard-code exactly the version knowledge the design keeps to one table.

A static Maven costs none of that: it is a Maven repository, so Gradle keeps doing resolution,
caching, checksums and `+` selection, and the CLI needed one URL changed.

If this ever outgrows GitHub Pages, the next step up is **Maven Central** — properly anonymous,
permanent and mirrored, at the cost of a Sonatype account, namespace verification for `org.cyclops`
and GPG signing. Heavier than a dev-only tool usually justifies, but the shape of the change is the
same one URL.

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
