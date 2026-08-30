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
small and they each cost real minutes. Take them in the order below: the first two are additive and
carry no risk, and the third is the only one that touches the protocol.

### 1. `dev` cannot read a block property · mod · ~20 lines

**The problem.** `dev.block(x, y, z)` answers a `BlockState`, and the obvious next question — is the
lamp lit? — is `state.getValue(BlockStateProperties.LIT)`, which names a game class and so hits the
very class loader wall `dev` exists to remove. The working incantation is

```groovy
state.getValue(state.getProperties().find { it.getName() == "lit" })
```

which nobody would guess, and which I wrote a helper closure for four separate times in one session.

**The plan.** Two methods on `ScriptHelpers`:

- `dev.prop(x, y, z, "lit")` — the value, as the game's own object, so `== true` and `> 0` both work
  on it rather than on a string.
- `dev.props(x, y, z)` — a name-to-value map, because "what does this block even have" is the
  question immediately before the other one.

`prop` on a name the block does not have must fail with the list of names it does have; that is the
whole reason the caller is in the dark, and a `NullPointerException` from `getValue(null)` — which
is what happens today — tells them nothing.

Version-neutral: `BlockState.getProperties()`, `Property.getName()` and `getValue` are unchanged
across every branch, so it upmerges without a conflict. No protocol change, no CLI change.

### 2. A teleport reports a position the player is about to leave · mod · ~15 lines

**The problem, corrected.** I first wrote this up as "teleport reports the position it asked for".
That is not what happens. `player.teleport` already returns both `pos` and `requested`, and the CLI
already prints `(asked for ...; the player has since fallen or been pushed)` when they differ. The
machinery is all there and it did not fire.

It did not fire because of `PlayerControl.isAt`, which the handler waits on: it accepts the player
being within **1.5 blocks vertically** of the target, and that is true the instant they arrive —
while they are still in the air. The reply goes out, truthfully describing a position the player
holds for one more tick, and gravity does the rest after the command has returned. Every screenshot
after that is of somewhere else, and nothing ever said so.

**The plan.** A second predicate, `PlayerControl.isSettledAt`, that adds "and the player is on the
ground". `player.teleport` waits on that one.

`isAt` itself must not change. `Interaction.approach` waits on it to position the player for an aim,
and `Aim.standingPosition` deliberately puts the player in mid-air for a `down` face — requiring
solid ground there would hang every downward interaction until the timeout. Two predicates, two
callers, no shared surprise.

When the timeout expires with the player still falling, say that: `arrived: false` plus a message
naming the fall, rather than a position that is already stale. A teleport into the void or a
deliberate mid-air placement stays possible — it just reports honestly that it did not settle.

### 3. `click` cannot shift-click · mod and cli · the only protocol change

**The problem.** Moving a stack between a container and the player inventory is one shift-click for
a player, and here it is two clicks plus finding an empty slot to drop into. There is no way to
express it at all: `click --modifiers 1` is rejected, and adding the flag would not help, because
`Screen.mouseClicked(x, y, button)` takes no modifiers. Minecraft decides shift-click inside
`AbstractContainerScreen.mouseClicked`, by calling the **static** `Screen.hasShiftDown()`, which
reads the real GLFW key state through `InputConstants.isKeyDown`. Synthetic input cannot reach it.

**Two routes, and they are a genuine trade.**

*Route A — name the operation.* `MultiPlayerGameMode.handleInventoryMouseClick(containerId, slotId,
button, ClickType, player)` is **public**, and is exactly what `AbstractContainerScreen.slotClicked`
calls once it has worked out which `ClickType` the mouse and modifiers meant. Calling it directly
skips the guessing: `quick_move` *is* what shift-click means. No mixin, no new failure mode on a
version bump.

The cost is real and worth writing down: it goes around a screen's own `slotClicked` override, and
`slotClicked` is `protected`, so there is no way to route through an override without widening it.
A mod that filters slot moves there would be bypassed. That is the same mistake `ScreenControl.close`
was fixed for, and the honest defence is different here — `close` had a public, correct path
available (pressing escape) and this one does not.

*Route B — make the modifier real.* A mixin on `Screen.hasShiftDown()` returning true while a
virtual modifier is held, and then an ordinary `click`. Every consumer sees it, mod code included,
and nothing is bypassed. The cost is that this mod has **no mixins today** — that is a deliberate
property, and it is why it has survived three Minecraft versions with version-sensitive code
confined to `mcadapter/` and no injection points to re-target. Route B spends that.

**Recommendation: A now, B only if a real screen needs it.** Route A covers every case that has come
up, and if a mod is ever found that filters in `slotClicked`, B can be added behind the same command
without changing the protocol again.

**The shape.**

- Protocol: `input.slotClick` with `{ slot | x, y, button, type }`, `type` one of `pickup`,
  `quick_move`, `swap`, `clone`, `throw`, `quick_craft`, `pickup_all`. Additive, so still protocol
  version 1.
- `slot` is the index the snapshot already reports for every slot, which is a better handle than a
  coordinate and one the caller already has. `x, y` stays available and resolves to a slot by
  hit-testing the rectangles the snapshot also already reports; a point that is not over a slot is
  an error naming the nearest one.
- CLI: `clientdevbridge slot-click <slot> [--type quick_move] [--button 0]`, plus `--shift` on the
  existing `click` as sugar for the case everyone actually wants.
- `ClickType` is an enum in both 1.21 and 26; confirm the constant names on each branch during the
  upmerge rather than assuming.

### Testing all three

- `scripts/e2e.sh` gains a vanilla shift-click: give an item, open a chest, `slot-click` it from the
  inventory, assert with `--json` that the stack changed slots. That is the assertion that would
  have caught the whole gap.
- `scripts/e2e-multipart.sh` moves the written variable card with one `slot-click` instead of the
  pick-up-and-place pair, which is the real-world case that found this.
- A teleport onto thin air, asserting the reported `pos` equals where the player is a second later.
- `eval "dev.prop(0, 4, 2, 'lit')"` in the e2e suite, which also proves the binding survives the
  class loader.

---

## Not on this list

Some things that hurt during the ID work turned out to be fixed by the work itself, and
are recorded here so nobody re-solves them: aiming at a specific face or point on a
multipart block (`Aim`, `--face`, `--at`), reporting what an interaction actually did
rather than diffing the world around it (`world.use`), describing a mod's own block
entities in `block` output (`BlockExtractors`), and closing a screen the way a player does
so the screen's own escape handling runs (`ScreenControl.close`).
