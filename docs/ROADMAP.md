# Roadmap — what to build next, and why

This file is a retrospective turned into a work list. It was written after building
Integrated Dynamics support end to end (aiming at cable parts, `world.use`, block
descriptions, and a working redstone-clock network driven entirely through the CLI),
which is the largest single piece of agent-driven work the bridge has carried so far.

Everything below is ordered by how much time it would actually have saved, measured
against that task. The estimates are rough; the ordering is not.

Each item says which repo it lands in: **mod** (`CyclopsMC/ClientDevBridge`) or
**cli** (`CyclopsMC/clientdevbridge-cli`). Items marked **Shipped** are done; the reasoning is kept
because it is why the thing looks the way it does.

---

## 1. `batch` — run many commands over one connection · cli

> **Shipped.** `clientdevbridge batch <file|->`, with `--continue-on-error` and `--json`.

**The problem.** Building the ID network took roughly fifty CLI invocations. Each one
paid a Node start, a session-file read, a TCP connect and a WebSocket handshake, and —
for an agent driving this — a whole round trip through the model. The work itself was
milliseconds; the overhead was everything.

**The shape.** `clientdevbridge batch <file>` (and `-` for stdin) reads newline-separated
CLI command lines, runs them in order against a single connection, prints each result,
and stops at the first failure with a non-zero exit and the line number.

```
open-gui 0 4 2 --face north
click --path /root/child[3]
set-text /root/child[5] 77
close-screen
```

**Why it is cheap.** `src/commands/context.ts` already funnels every command through
`withClient`, which is the only place a socket is opened or closed. Give the module a
sticky connection: when batch mode sets it, `connect()` hands back the existing client
and `withClient` skips the `close()` in its `finally`. Then batch is a loop over
`program.parseAsync(['node', 'cdb', ...tokenize(line)])`. No command needs to change.

Add `--continue-on-error` for exploratory use, and `--json` emitting one result object
per line so a caller can parse the lot.

**Payoff:** the single largest one. Fifty turns become one.

## 2. Keep one client alive, and actually use `hotswap` · docs, then cli

> **Shipped.** `hotswap --restart-if-needed`, and the rule is in `AGENTS.md` and `docs/AGENT_WORKFLOW.md`.

**The problem.** Client boot is 60–120 s and it dominated every iteration. `hotswap`
has existed since the first release and went unused for this entire piece of work,
because nothing said "use this instead of restarting" at the moment it mattered. Every
mod-side edit turned into a full restart, and a restart also throws away the world
state that took twenty commands to build.

**The shape.** Two parts.

- Docs: `AGENTS.md` and `docs/AGENT_WORKFLOW.md` should state the rule plainly — one
  client for the whole session; after a mod-side edit run `hotswap`, not `restart`;
  restart only when hotswap reports it cannot swap.
- Code: `hotswap --restart-if-needed`. HotSwap cannot add or remove methods and fields
  or change a class's shape, so make the command detect that case from the JVM's own
  error and fall back to a restart, printing which it did. Today the agent has to know
  the JVM's redefinition rules to make that call.

**Payoff:** most of the wall-clock in any edit–verify loop.

## 3. `set-text` — one command for editing a text field · cli

> **Shipped.** `clientdevbridge set-text <widget> <value> [--commit enter|tab|none]`.

**The problem.** Setting a value in an edit box meant: click the widget, press
BACKSPACE as many times as the old value is long, type the new value, then find and
trigger whatever commits it. Four to eight commands, and the backspace count is a
guess unless you snapshot first to read the current value.

**The shape.** `clientdevbridge set-text <path> <value> [--commit enter|tab|none]`:
focus the widget at that snapshot path, select all, type, then apply the commit action.
`snapshot` already reports an `EditBox`'s current value through `VanillaExtractors`, so
the length is known and no guessing is needed.

**Payoff:** roughly a third of the GUI commands in the ID work were this pattern.

## 4. `upmerge` as a script, not as discipline · mod

**The problem.** Three branches (`master-1.21-lts` → `master-26-lts` → `master-26`) times
two loaders means a change is not done until six builds pass. The sequence is always
the same: fetch, merge, resolve, build, push — and it is only correct if the push is
gated on the build. I got that wrong once in this session, chaining the steps with `;`
instead of `&&`, and pushed a branch whose build I had not checked. The fix is not to
remember harder.

**The shape.** `scripts/upmerge.sh <from-branch> <to-branch>` that runs the sequence with
`set -e`, stops on a conflict and says which files need hands, runs `./gradlew build
spotlessCheck` (Spotless matters: it is not part of `compileJava`, and skipping it is how
three branches went red earlier), and pushes only on success. Then document it as the
only supported way to upmerge.

**Payoff:** removes a whole class of self-inflicted red branches, and makes the six-build
matrix one command per hop.

## 5. Bindings that remove the `eval` classloader trap · mod

> **Shipped.** Bound as `dev`: `pos`, `vec`, `block`, `blockId`, `blockEntity`, `nbt`, `item`.

**The problem.** Game classes are loaded by the transforming class loader and the script
engine is not, so `new net.minecraft.core.BlockPos(0, 4, 2)` fails with a message about
class loaders that says nothing about the repair. `EvalHandler.hintFor` now explains it,
which is a signpost around the hole rather than a fix.

**The shape.** Bind a small helper object into the script scope alongside `player` and
`level` — `pos(x, y, z)`, `blockEntity(x, y, z)`, `block(x, y, z)`, `item("minecraft:stone")`
— constructed on the game side where the class loader is right. Then the common cases
never need to name a game class at all. This belongs in `ClientState.scriptBindings()`,
which is already the one place bindings are assembled.

**Payoff:** turns a recurring dead end into a non-event.

## 6. `screenshot --diff` — make a visual change assertable · cli

> **Shipped.** `clientdevbridge screenshot --diff <image.png> [--min-diff <pct>]`.

**The problem.** Confirming the lamp went on and off meant taking two screenshots and
looking at them. That works for a human reading the answer, and not at all inside a
script: there is no exit code in "I looked at it".

**The shape.** `compare` already carries the whole pixelmatch pipeline against a committed
golden. Expose the same machinery between two arbitrary captures:
`clientdevbridge screenshot --diff <other.png> [--region ...] [--threshold ...]`, exiting
non-zero when they match too closely — the inverse of `compare`, and the assertion an
agent actually wants when proving that something changed.

**Payoff:** makes "did this visibly do anything" a one-line check rather than a judgement call.

## 7. Promote the Modrinth version resolver · mod

**The problem.** Picking a set of third-party mod versions that agree with each other and
with the project's NeoForge pin cost several dead ends: Integrated Dynamics 1.35.0 wanted
a newer NeoForge, the newer NeoForge broke NeoGradle, the newer NeoGradle wanted a newer
Gradle. Every dead end was a download.

**The shape.** `scripts/e2e-multipart.sh` already contains the answer: it bisects a mod's
Modrinth version list for the newest build whose loader floor is at or below the project's
pin, in seven requests instead of a linear scan of hundreds of megabytes. Lift that into
`scripts/resolve-mod-versions.py` with a documented CLI, and have the e2e script call it.
Anyone adding a mod to a test then gets a compatible set in seconds.

**Payoff:** turns an afternoon of trial and error into one command, the next time.

## 8. Do not poll — the rule that costs the most when ignored

> **Shipped.** Written into `AGENTS.md`; there was nothing to build.

**The problem.** This is a process failure rather than a missing feature, and it was the
single biggest waste of the session: waiting on a Gradle build, a client boot or a CI run
by checking whether it had finished yet. Every check is a full turn, and the answer is
almost always "not yet".

**The shape.** Nothing to build. State it in `AGENTS.md`: every long-running CLI command
already blocks until it is done or its timeout expires, so run it in the foreground and
read its exit code. For work outside the CLI, start it in the background and wait for its
completion signal. Never re-check a thing whose completion will announce itself.

---

## Found by rebuilding the Integrated Dynamics clock

A second run of the same task, with the tooling above in place, turned up three more. They are
small and they each cost real minutes.

### `click` cannot shift-click · cli

Moving a stack between a container and the inventory is one shift-click for a player and two
commands plus an empty-slot hunt here. `click --modifiers <n>` is rejected, so there is no way to
express it at all. `input.mouseClick` already carries a button; it needs the modifier bits too, and
`click` needs `--modifiers` and probably `--shift` as a name for the only one anybody wants.

### `dev` cannot read a block property · mod

`dev.block(x, y, z)` answers a `BlockState`, and the obvious next question — is the lamp lit? — is
`state.getValue(BlockStateProperties.LIT)`, which names a game class and so hits the very class
loader wall `dev` exists to remove. The workaround is
`state.getValue(state.getProperties().find { it.getName() == "lit" })`, which nobody would guess.
`dev.prop(x, y, z, "lit")` would close it.

### A teleport onto thin air is a silent trap · cli

`teleport` puts the player where it is told; gravity then moves them, and every screenshot after
that is of somewhere else. It cost two rounds of captures here, both of empty grass, because
nothing said the player had moved. `teleport` should report the position it settled at rather than
the one it asked for, or warn when the block below is not solid.

---

## Not on this list

Some things that hurt during the ID work turned out to be fixed by the work itself, and
are recorded here so nobody re-solves them: aiming at a specific face or point on a
multipart block (`Aim`, `--face`, `--at`), reporting what an interaction actually did
rather than diffing the world around it (`world.use`), describing a mod's own block
entities in `block` output (`BlockExtractors`), and closing a screen the way a player does
so the screen's own escape handling runs (`ScreenControl.close`).
