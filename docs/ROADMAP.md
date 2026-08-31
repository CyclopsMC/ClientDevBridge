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

> **Shipped.** `dev.prop(x, y, z, "lit")` and `dev.props(x, y, z)`.

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

> **Shipped.** `PlayerControl.isSettledAt`, waited on by `player.teleport` only, plus a `falling` field.

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

> **Shipped**, by route A. `input.slotClick`, `clientdevbridge slot-click`, and `--shift` on `click`.

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

### What the port added to the plan

Two things the plan did not know, both found by building it.

**`ClickType` is `ContainerInput` on 26**, and `handleInventoryMouseClick` is
`handleContainerInput`. The seven constants and the argument order are unchanged, so the difference
is two names — but left inside `InputControl` it would have put a conflict in the middle of a large
shared file on every future upmerge. It lives in `SlotInput` instead, forty lines that are the whole
of what the branches differ in, with the protocol's names rather than the enum's crossing the
boundary so a rename cannot reach the wire.

**Route A's one worry did not materialise.** The concern was that going through
`handleInventoryMouseClick` skips a screen's own `slotClicked`. `scripts/e2e-multipart.sh` now
`quick_move`s a variable card out of an Integrated Dynamics part screen — a real modded container
from a mod that does a great deal of its own slot handling — and it works. That is not proof no
screen anywhere filters there, but it is the case that would most likely have shown it.

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

## Found by driving Everlasting Abilities

The whole task — download the mod, boot it, open the Ability Bottle a new player starts with, move
an ability out of it, and confirm the move on a screenshot — took **9m 41s**, of which about two
minutes was Gradle. Nothing had to be worked around by trial and error, which is the first time
that has been true of a mod this bridge had never seen. Four gaps still showed up, and the first is
the only one that made the task harder rather than merely less pleasant.

### 1. There is no way to use the item in your hand · cli · the real gap

> **Shipped.** `player.useItem`, `clientdevbridge use-item`, and `open-gui` with no coordinates.

Opening the Ability Bottle is a right-click holding it, aimed at nothing. Every use command this CLI
has takes a block position: `use <x> <y> <z>`, `open-gui <x> <y> <z>`, `inspect-gui <x> <y> <z>`.
There is no command for the plainest interaction in the game.

It is reachable by accident — `click --at 213,120 --button 1` with no screen open falls through to
the in-world use keybinding — but nothing says so, `--at` is meaningless there, and a reader of
`--help` would never find it. Any mod whose main entry point is an item rather than a block hits
this immediately.

**The plan.** `clientdevbridge use-item [--hand main|off] [--wait-screen]`, over a new
`player.useItem`. It is `InputControl.mouseClick`'s existing no-screen branch, given a name and a
report: what was held, and what screen it opened. `open-gui` should take no coordinates as a way of
saying "the held item", so the composite that waits for the screen works for items too.

### 2. An in-world click reports the state before the game has acted · cli and mod

> **Shipped.** The no-screen branch settles for five ticks, the same allowance `world.use` makes.

`click --button 1` answered `screen: none` at the moment it opened a screen. Nothing is wrong with
the value — it is read immediately, and the keybinding it queued is processed on the next tick, so
"none" was true when it was measured and false a frame later.

Every other input command is honest because a screen handles a click synchronously. This one is not,
and it reads as a failure: a caller sees `screen: none` and concludes the click did nothing.

**The plan.** When there is no screen, the in-world branch should wait a tick before reporting, the
way `world.use` already does for a block interaction. `afterInput` becomes the same
"queue, tick, then read" shape for the one case that needs it, rather than every caller having to
know which branch it took.

### 3. A mod's data components are opaque in `inventory` and `snapshot` · mod

> **Shipped.** `ItemExtractors`, merged into `describeStack`, with vanilla registrations for containers, block items and damage.

`inventory --json` reported the bottle's contents as
`everlastingabilities:ability_store=>org.cyclops.everlastingabilities.api.capability.DefaultMutableAbilityStore@0`
— the component's `toString`, which for most mod types is a class name and an identity hash. So the
one field that says what is actually in the bottle is unreadable, and a caller cannot tell a full
bottle from an empty one.

This is the same problem `BlockExtractors` was added for, one layer over: a mod can already say what
distinguishes one instance of its *block entity* from another, and has no way to say the same about
its *item*.

**The plan.** `ItemExtractors`, keyed by `Item` or by data-component type, merged into the `details`
of every stack `PlayerControl.describeStack` produces — so it reaches `inventory`, the container
block of `snapshot`, and `world.use`'s held-item reports at once, exactly as `BlockExtractors`
reaches everything that describes a block.

The workaround meanwhile is worth documenting, because it is genuinely good: Groovy dispatches on
the object it is handed, so a script can call a mod's own methods without naming any of its classes.

```groovy
def store = player.getMainHandItem().getComponents()
    .find { it.value().getClass().getSimpleName().contains("AbilityStore") }?.value()
store.getAbilities()          // []
```

That is how the bottle was confirmed empty, and it needs no support from the mod at all.

### What the build added to the plan

The e2e suite failed the first time it ran, and the reason was not a bug: the player was still
standing at the chest from the phase before, so the right-click hit the *block* and the held item
was never reached. That is exactly what a player gets, and it is the single most confusing way for
`use-item` to appear not to work — the reply was "screen: ContainerScreen" with no hint that the
item had nothing to do with it.

So the result carries `aimedAt` (`block`, `entity` or `miss`), and the CLI warns when something took
the click before the item could. "The item did nothing" and "you were looking at a chest" are the
same reply otherwise.

**And `--wait-screen` did not wait.** It read the screen once after the five-tick settle, which is
enough for a chest and not for the Ability Bottle: a container screen is opened by the *server*, so
it arrives with a packet rather than on the tick the click was processed. The flag reported "no
screen opened" for an item that had opened one — worse than not offering the wait at all. It now
awaits a screen for five seconds, in the mod, so it stays one round trip.

Redoing the whole Everlasting Abilities task with all of this in place took **4m 47s**, against
**9m 41s** the first time.

### 4. Nothing reads a list that is drawn rather than built from widgets · nothing to build yet

The two ability lists are drawn directly, so `snapshot` shows six arrow buttons and no rows: the
contents are only in the screenshot. Reading them meant looking.

Worth recording and **not** worth building for. The arrows' `disabled` flags carried the state that
mattered — selecting a row flipped `Left` from disabled to enabled, which is how the selection was
confirmed without seeing anything — and a general "read the drawn text" facility is a much larger
thing than this cost. Revisit if a second mod hits it.

---

## Found by trying to mine a block

The task: give the player a diamond pickaxe, put cobblestone in front of them, switch to survival,
break the block with the pickaxe, and pick up what drops. **All of it works** — but the breaking
only through `eval`, and that is the gap. Two and three quarter minutes to establish, most of it
Gradle.

Everything except the mining is a command already: `give`, `setblock`, `command "gamemode survival"`,
`teleport`, `look`, and `hold-key W` to walk onto the drop, which the player picked up. The mining
had to be this:

```groovy
def pos = dev.pos(0, 4, 2)
def face = mc.hitResult.getDirection()
mc.gameMode.startDestroyBlock(pos, face)
while (!level.getBlockState(pos).isAir()) { mc.gameMode.continueDestroyBlock(pos, face) }
```

It works — the block broke in eight iterations, dropped a cobblestone `ItemEntity`, and the pickaxe
took one point of durability, so the server agreed it was a genuine mining action rather than a
block being deleted. But nobody would find it, and it is wrong in one way that matters: it spins
`continueDestroyBlock` inside a single tick, where a player takes eight. That works against an
integrated server which validates loosely, and it is not what mining is.

### 1. `hold-key` cannot reach the mouse buttons · mod · the actual cause

> **Shipped.** `Keys.toBinding` answers an `InputConstants.Key`; `ATTACK`, `USE`, `PICK` and the `MOUSE_*` names all hold.

Attack is bound to `key.mouse.left`, and `hold-key` is keyboard-only:

```console
$ clientdevbridge hold-key MOUSE_LEFT --ticks 20
error: Unknown key 'MOUSE_LEFT'. …
$ clientdevbridge hold-key 0 --ticks 20
error: No key binding matches key code 0, so it cannot be held.
```

Holding attack is *the* mechanism for mining, and holding use is the mechanism for eating, drinking,
drawing a bow and raising a shield. None of them can be expressed.

`Keys.toKeyCode` answers a bare keyboard `int` and `findMapping` matches against keyboard defaults,
so the fix is to stop passing an `int` around: resolve to an `InputConstants.Key`, which already
distinguishes keyboard from mouse, and match bindings on that. Then add the names —
`MOUSE_LEFT`/`MOUSE_RIGHT`/`MOUSE_MIDDLE`, and `ATTACK`/`USE`/`PICK` for the bindings themselves,
which is what a caller actually means.

Contained, and entirely inside `mcadapter`. `hold-key ATTACK --ticks 20` then mines, the way a
player does, one `continueAttack` per tick with the game deciding when the block gives way.

### 2. `break` as a composite · mod and cli

> **Shipped.** `world.break` and `clientdevbridge break`, one tick of progress per tick, reporting `ticks` and `drops`.

`hold-key ATTACK --ticks 20` still asks the caller to know how long cobblestone takes with a diamond
pickaxe. That is the same thing `world.use` exists to avoid, and the answer is the same shape:

`world.break` with `{ blockPos, face?, approach?, timeoutTicks? }` — approach and aim through the
existing `Aim` machinery, `startDestroyBlock`, then `continueDestroyBlock` **once per tick** off the
tick clock until the block changes or the timeout expires. One per tick, not a loop: it is what a
player does, it is what makes mining speed observable (a test can assert that the wrong tool is
slower, or that an unbreakable block times out), and it will not be rejected by a server that
validates progress.

It reports `{ pos, face, broken, ticks, blockBefore, blockAfter, drops, heldAfter }`. `drops` is the
point of the exercise — the item entities that appeared — and `ticks` is what says the tool mattered.

CLI: `clientdevbridge break <x> <y> <z> [--face] [--no-approach] [--timeout-ticks]`.

### 3. Walking to a position · cli · smaller

> **Shipped.** `player.walkTo` and `clientdevbridge walk-to`, re-aimed each tick and leaving the pitch alone.

Picking the drop up meant `look --pitch 0` (to stop walking into the ground) and then guessing
`hold-key W --ticks 20`. It worked first time, but it is dead reckoning: nothing says how far twenty
ticks goes, and the pitch reset is a trap nothing warns about.

A `walk-to <x> <z> [--timeout-ticks]` that holds forward until the player arrives or gives up would
remove the guess. Worth doing after the two above, and only if a second task wants it — `teleport`
covers most cases, and the walk only matters when the movement itself is the thing being tested.

### What the build added to the plan

The drop was reported as "nothing dropped" on the first run, and then the e2e failed for a different
reason on the second. Both were the same kind of mistake as before: reading a server's answer
before it arrived, and assuming a command did more than it says.

**A drop is a server entity and it is thrown.** It is spawned when the server agrees the block
broke, reaches the client a few ticks later, and lands a block or two from where the block was —
so `drops` is read ten ticks after the break and carries each item's position. Without the position
there is nowhere to walk to.

**`eval` had been dead on Minecraft 26 all along.** Running the suite on the 26 worktree by hand --
which the new mining phases made worth doing -- failed on an `eval` assertion with
`Unsupported class file major version 69`: the init script pinned Groovy 4.0.22, which cannot read
Java 25 class files. So `eval` and `wait --expr`, a headline feature, had never worked on either 26
branch. Nothing caught it because CI's e2e job pins Java 21 and never gets far enough there to try.
Groovy 5.1.1 fixes it and passes on 1.21 too.

**A CI gap worth closing separately:** the e2e job's `java-version: 21` means the suite has never
run against a 26 branch at all. That is why a whole feature could be broken there unnoticed, and it
is a bigger hole than any single bug it has been hiding.

**`give` does not put the item in your hand.** It finds a free slot and leaves the selection alone,
so the e2e mined cobblestone bare-handed: 202 ticks, and no drop, because cobblestone needs a
pickaxe. That is the tick count doing exactly the job it was added for — it is now asserted from
both sides, at least two ticks and at most sixty, so a wrong-tool regression cannot pass.

---

## Token usage: what the bridge actually costs, and how to cut it

The Integrated Dynamics clock, built a third time and instrumented. **4m 34s**, against 21m 33s the
first time and 9m 41s the second. Every CLI invocation's output was byte-counted; tokens are
estimated at four bytes each, which is close enough to rank things by.

| | bytes | ~tokens |
| --- | ---: | ---: |
| Emitted by the CLI over the whole task (32 invocations) | 20,916 | 5,200 |
| …of which two `--json` dumps, piped through `python3` and never read | 15,183 | 3,800 |
| **Actually reached the agent's context** | **5,953** | **1,488** |
| One screenshot read as an image (854×480) | — | 546 |
| Screenshots read this run | 0 | 0 |

Two things stand out immediately. The whole task cost about **1.5k tokens of bridge output** — the
agent's own reasoning dwarfs it. And the single largest thing the bridge can emit is
`--json snapshot`, at 11,741 bytes, which is more than everything else in the task put together.

### The cost of asking the same question two ways

| command | text | `--json` | ratio |
| --- | ---: | ---: | ---: |
| `snapshot` (a modded container screen) | 775 B | 11,741 B | **15×** |
| `inventory` (one item) | 25 B | 2,909 B | 116× |
| `status` | 709 B | 1,838 B | 2.6× |
| `block 1 4 1` | 42 B | 198 B | 4.7× |

`--json` is the right answer when a script has to assert on a field. It is the wrong answer when a
human or a model is going to read it, and the gap is large enough that the choice matters more than
anything else on this list.

### 1. `--json` pretty-prints, and 42% of every payload is whitespace · cli · two lines

> **Shipped.** Compact unless `process.stdout.isTTY`.

`printJson` is `JSON.stringify(value, null, 2)`. On the snapshot above that is **5,017 bytes of
indentation** — 43% of the payload, for nothing. Compact output is byte-for-byte the same
information:

```
pretty-printed : 11,741 B  (~2,935 tokens)   <- today
compact        :  6,724 B  (~1,681 tokens)
```

Pretty-printing earns its keep at a terminal a person is reading. The fix is to keep it there and
drop it everywhere else: compact unless `process.stdout.isTTY`. Nothing else changes, no flag to
learn, and every `--json` call in every script gets 43% cheaper.

### 2. Empty container slots are 3.2 kB of the snapshot · cli

> **Shipped.** `--json` omits them and reports `slotCount`; `--include-empty` restores them. Measured on a chest screen holding one item: **9,813 B → 792 B, a 92% cut.**

39 of the 40 slots in that screen were empty, and each one costs about 80 bytes of
`{"index":n,"item":null,"count":0,"x":..,"y":..,"hovered":false}`. The text outline has always
omitted them, for exactly this reason; `--json` lists them because completeness was the point of
`--json`.

Both can be true. Omit empty slots by default and report `slotCount` alongside, so the grid is still
derivable, with `--include-empty` for the caller that genuinely wants every rectangle. Filter in the
CLI, not the mod: `screen.snapshot` stays complete, and only the presentation changes.

Compounded with compact output: **11,741 B → 4,123 B, a 65% cut**, with no information a caller has
actually wanted made unavailable.

### 3. Screenshots are ~546 tokens each, and mostly avoidable · docs

> **Shipped.** The cost order is in `docs/AGENT_WORKFLOW.md`, with the measured table.

An 854×480 capture read as an image costs about 546 tokens. This run read **none**: `screenshot
--diff` answered "did the lamp change" in one line, and `dev.prop(1,4,1,"lit")` answered "is it on
now" in twenty bytes. The first run of this same task read about ten screenshots — call it 5.5k
tokens spent looking at pictures to learn things the world state already knew.

Nothing to build. `AGENT_WORKFLOW.md` should say, in order: assert on state with `eval` or `block`;
assert on pixels with `compare` or `screenshot --diff`; **read** an image only when you genuinely do
not know what you are looking for. And when you must read one, `--scale 0.5` quarters the pixel
count and so the token cost, and `--region` narrows it further.

### 4. `batch --quiet` is free and nobody knows · docs

> **Shipped.** Documented alongside the cost order.

Three commands cost 105 bytes with the `$ command` echo and 0 with `--quiet`. On a fifty-line scene
the echo is a few hundred wasted tokens every time. `--quiet` is the right default for a scene you
have already built and are rebuilding; the echo earns its keep only while a batch is being debugged.

### What the change turned up

The saving was larger than estimated — 92% rather than 65% — because the two cuts compound on a
container screen more than the modded one they were measured on suggested.

And it broke something. **The slots array was dense by accident**, and two of our own e2e assertions
indexed it positionally: `slots[54]` for slot 54. That only ever worked because every slot was
present, and it is exactly the kind of thing a consumer would have written. They key on the `index`
field now, the docs say to, and `slotCount` is there so nothing about the grid is lost. Worth
knowing before anyone else's script meets it.

### Not worth doing

- **Shortening error messages.** The failed aim in this run cost about 450 bytes and saved a
  round trip that would have cost more. Error text is the cheapest thing in the system relative to
  what it prevents.
- **Compressing `eval` results.** Nineteen calls cost 1,179 bytes between them. It is already the
  cheapest way to ask the game a question.
- **Reducing the number of commands further.** `batch` already collapsed this task from fifty
  invocations to thirty-two, and the remaining ones each answer a distinct question.

### The order

1 and 2 are the same file and together cut the largest payload by two thirds. 3 and 4 are
documentation, and 3 is worth more than either code change on a task that leans on screenshots —
ten image reads is 5.5k tokens, which is three times what this entire run cost.

---

## Found by surveying every CyclopsMC mod for 1.21.1

Fourteen CyclopsMC mods publish 1.21.1/NeoForge builds. Eleven were resolved against this project's
NeoForge pin and loaded into **one client together** — Cyclops Core, Common Capabilities, Integrated
Dynamics, Integrated Crafting, Integrated Scripting, Integrated Terminals, Integrated Tunnels,
Integrated REST, Capability Proxy, Colossal Chests and EvilCraft. Integrated NBT has no build that
accepts NeoForge 21.1.2; Flopper is already the e2e fixture. **15m 35s** for the lot.

Everything worked. Eight GUIs opened first try — `uncolossal_chest`, `blood_infuser`,
`scripting_drive`, `http`, the storage terminal, and the crafting/tunnels/terminals cable parts —
a Colossal Chest multiblock assembled from `setblock` and opened, `break` mined an EvilCraft ore,
`use-item` opened EvilCraft's Origins of Darkness, `tooltip` read a modded item, and `dev.props`
read a modded block state. Capability Proxy has no GUI and the "one per side" hint said so
correctly. No bridge change was needed to drive any of them.

Three things are worth fixing.

### 1. `dev` cannot reach the registries · mod · the one that actually blocked

"What does this mod register" is the first question you have about an unfamiliar mod, and it cannot
be asked. Naming `BuiltInRegistries` in a script throws `ExceptionInInitializerError` — the class
loader wall `dev` exists to remove — and `dev` has no registry accessor. The survey worked around it
by unzipping the jars and reading `assets/*/models/item/*.json`, which is offline archaeology to
learn something the running game knows.

**The plan.** Three methods on `ScriptHelpers`, all of which run on the game's side where the loader
is right:

- `dev.blocks("evilcraft")` / `dev.items("evilcraft")` — the registry names in a namespace, or every
  namespace when given none.
- `dev.namespaces()` — which mods actually registered anything, which is also the fastest way to
  confirm a mod loaded and is not merely present.

A CLI `registry <kind> [namespace]` would round it off, and matters because these lists are long:
EvilCraft alone has 53 blocks and 90 items, so this is a place to be careful about output size —
names only, no metadata, and a `--filter`.

### 2. `broken` is a prediction, `blockAfter` is the truth, and they can disagree · mod

`world.break` computes `broken` from the client's own state the moment the mining loop ends, and
reads `blockAfter` ten ticks later after the drop settle. During the survey I repeatedly saw replies
carrying `broken: true` beside `blockAfter: Block{minecraft:stone}` — the reply asserting success
while its own evidence said the block was still there.

I could **not** reproduce it in a clean world: there, both a vanilla and a modded block broke
correctly with a drop, and the confounder was almost certainly my own accumulated world state
(gamemode switches and an uncertain held item). So this is not a confirmed break failure.

The reporting flaw is real regardless of what caused the disagreement, and is true by construction:
two fields describing the same fact are sampled ten ticks apart and nothing reconciles them. Derive
`broken` from the post-settle state — the same read `blockAfter` uses — so a reply cannot contradict
itself. Keep `ticks` as the moment the client thought it went, which is still the useful number.

### 3. A disconnect is reported as "not in a world" · mod

Destroying a cable out from under its parts made Integrated Dynamics throw server-side, and the
client was kicked to a `DisconnectedScreen`. Every world command then answered:

```
error: Not in a world. Run 'clientdevbridge world-reset' or 'world-load <name>' first.
```

True, and it hides the event. The client *was* in a world and was kicked out by an exception whose
text is sitting on the screen the bridge can already read. A caller follows the advice, resets the
world, and never learns that their mod threw — which is exactly the thing they would most want to
know.

**The plan.** When `requirePlayer`/`requireLevel` fails and the current screen is a
`DisconnectedScreen`, say so and quote its reason instead of the generic advice. The message becomes
"The client was disconnected: <reason>", which is both the diagnosis and the explanation for why
there is no world. `mcadapter` already has `ClientState.screen()`; this is reading the reason off it.

### Not worth doing

- **A per-mod fixture.** Eleven mods in one client is the realistic pack case and found more than
  eleven separate runs would have, in a fraction of the time.
- **Anything about the crash itself.** The bridge behaved correctly: it stayed up, kept answering,
  and `snapshot` showed the disconnect screen with the full stack reason. Only the wording of the
  follow-up errors is wrong.

---

## Not on this list

Some things that hurt during the ID work turned out to be fixed by the work itself, and
are recorded here so nobody re-solves them: aiming at a specific face or point on a
multipart block (`Aim`, `--face`, `--at`), reporting what an interaction actually did
rather than diffing the world around it (`world.use`), describing a mod's own block
entities in `block` output (`BlockExtractors`), and closing a screen the way a player does
so the screen's own escape handling runs (`ScreenControl.close`).
