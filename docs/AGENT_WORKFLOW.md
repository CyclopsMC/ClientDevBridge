# The agent workflow

How to actually use ClientDevBridge to change a mod's GUI or rendering and know that it worked.

Everything here is a bash command, so it works the same in a local terminal, in Claude Code on the
web, and in CI.

## The loop

```
edit code  →  hotswap (or restart)  →  world-reset  →  inspect-gui  →  read the outline
                                                                    →  Read the screenshot PNG
                                                                    →  compare against a golden
```

The two halves matter for different reasons. The **outline** tells you what the game thinks is
there — every widget, its bounds, its state, its slots. The **screenshot** tells you what a player
would see. A bug is usually a disagreement between the two.

## Getting started

```bash
cd path/to/your/mod
npx cyclops-clientdevbridge-cli doctor    # can this machine build and launch a client?
npx cyclops-clientdevbridge-cli start     # ~2 min cold, headless if there is no $DISPLAY
```

`start` returns only once the client has finished loading and is ready to be driven. It leaves the
client running in its own process group, so every later command is a fast, separate invocation.

`doctor` is worth running first every time, not just once: it is what tells you which JDK Gradle
will use, which loader and Minecraft version it detected, and where the ClientDevBridge build it is
about to inject is coming from. If it reports no build for your Minecraft version, run
`./gradlew publishToMavenLocal` in a ClientDevBridge checkout of the matching branch — a build in
the local Maven repository always wins over a released one, which is also how you test a change to
the bridge itself.

## Looking at a GUI

```bash
clientdevbridge world-reset
clientdevbridge setblock 0 4 2 yourmod:your_block
clientdevbridge inspect-gui 0 4 2
```

`inspect-gui` is the composite you will reach for most: it right-clicks the block, waits for the
screen, prints the outline, and writes a screenshot whose path it prints on the last line.

**It moves the player.** To right-click a block the server has to consider it in reach, so
`inspect-gui` and `open-gui` teleport the player next to it and look at it (pass `--no-approach` to
suppress that and position yourself). This matters for golden images: always `teleport` and `look`
explicitly before `compare --update`, or your golden will not be reproducible.

```
YourScreen  "Your Machine"
gui 427x240 @ scale 2, window 854x480px, mouse at 0,0
container YourMenu at (125,37) 176x166, 46 slots (1 filled)
  slot 0 yourmod:ingot x3 @(133,45)
  Button "Apply" @(312,208 60x20) disabled  /root/children[3]
  EditBox = "name" @(200,60 80x20) focused  /root/children[4]

/path/to/mod/.clientdevbridge/screenshots/inspect-gui_2026-08-29_20-12-03.png
```

**Open that path with your file-reading tool.** Screenshots are never printed as base64 — the path
is the contract.

Two things the outline deliberately leaves out:

- **Empty container slots**, unless they are hovered — a 46-slot screen would otherwise be 45 lines
  of nothing. `--json` omits them too, for the same reason and a larger saving, and reports
  `slotCount`; the slots are a regular grid, so the geometry of the missing ones follows from the
  ones that are there. `--json --include-empty` lists every slot.
- **Components that report no rectangle of their own.** Vanilla's recipe book is the common case:
  it appears as one node marked `bounds unknown`, and its own widgets are not reachable. Read a
  screenshot to see it, and click it by coordinate.

Every number in the outline is in GUI space, which is exactly what the input commands take, so you
can feed them straight back:

```bash
clientdevbridge click --widget "Apply"          # by label
clientdevbridge click --widget "/root/children[3]"   # by path -- prefer this, see below
clientdevbridge click --at 312,208              # by coordinate
clientdevbridge tooltip --at 133,45             # what does that slot say?
clientdevbridge type "hello"                    # into the focused widget
clientdevbridge key ESCAPE
```

**Shift-click is `slot-click`.** A screen works out that a click was a shift-click from the real
keyboard state, which synthetic input cannot reach, so the operation is named rather than inferred:

```bash
clientdevbridge slot-click 12 --type quick_move   # move the stack to the other inventory
clientdevbridge click --at 125,202 --shift        # the same thing, by point
```

The index is the one `snapshot --json` reports for each slot. `--type` also takes `pickup`, `swap`,
`clone`, `throw`, `quick_craft` and `pickup_all`.

For a text field, use `set-text` rather than assembling the edit yourself:

```bash
clientdevbridge set-text "/root/children[4]" 77 --commit enter
```

It focuses the field, clears exactly as many characters as it holds (the snapshot knows), types,
presses the commit key, and reads the value back — so a field that rejected part of your input says
so here rather than three commands later.

**Prefer paths over labels for anything you click more than once.** Many vanilla widgets have no
real label and fall back to their narration, which *changes with their state* — the recipe-book
button reads `button Left click to activate` until it is focused and `button Press Enter to
activate` afterwards. A label that matched before a click may not match after it. Paths are stable
within a screen.

`find` is the quick way to locate something on a busy screen:

```bash
clientdevbridge find "Apply" --type Button
```

## `setblock` places a block; it does not place it *the way a player does*

`setblock` writes a block state. That is all it does, and for most blocks it is enough. Two kinds
of block need a real placement instead, and both fail in ways that look like the bridge is broken:

- **Blocks that build something on placement.** An Integrated Dynamics cable joins a network in its
  own placement logic, so a `setblock` cable is a cable that belongs to no network — every part on
  it reports `part_not_in_network` and nothing works. Place them:

  ```bash
  clientdevbridge command "item replace entity @s weapon.mainhand with integrateddynamics:cable 64"
  clientdevbridge use 0 3 0 --face up      # places at 0,4,0
  ```

- **Redstone components.** A `setblock` repeater, torch or comparator is dropped in without the
  neighbour update that would make it evaluate its input, so it sits inert at its default state
  however much power is next to it. Changing any neighbour afterwards wakes it — including
  `setblock`ing the same position twice, or a block above it. Do not poke the block a torch is
  attached to: removing its support breaks the torch.

Vanilla blocks with no placement logic — lamps, dust, solid blocks — are fine with `setblock`.

## Setting up a scene

`world-reset` deletes and regenerates a creative superflat world with time, weather, mobs and
random ticks all switched off, a stone platform under the spawn, and the player at `0, 4, 0` with
yaw 0 — which faces **+Z (south)**, so the block at `0, 4, 2` used in every example here is
straight ahead. It is the same world every time, which is what makes screenshots comparable.

```bash
clientdevbridge world-reset
clientdevbridge command "setblock 0 4 2 minecraft:furnace"
clientdevbridge give yourmod:wrench 1
clientdevbridge hold 0
clientdevbridge teleport 0 5 6 --yaw 180 --pitch 20
clientdevbridge look --at 0,4,2
clientdevbridge block 0 4 2 --nbt
```

`give` fills the first free hotbar slot and leaves the selection where it was, so after the second
`give` the item you want is no longer the one in your hand. Everything that places, uses or mines
acts on the selected slot: name it with `hold <slot>` and the ambiguity goes away. `inventory` marks
the selected slot with `>`.

For a scene too fiddly to script, commit a world under `clientdevbridge/templates/<name>/` in your
repository and use `world-reset --template <name>`.

### Breaking a block, and picking up what drops

```bash
clientdevbridge command "item replace entity @s weapon.mainhand with minecraft:diamond_pickaxe 1"
clientdevbridge command "gamemode survival"
clientdevbridge break 0 4 2
#   broke Block{minecraft:cobblestone} in 9 ticks
#     dropped minecraft:cobblestone x1 at 0.26, 4.00, 1.82
clientdevbridge walk-to 0.26 1.82
```

**Put the tool in the hand, not just the inventory.** `give` finds a free slot and leaves the
selection alone, and mining cobblestone bare-handed works fine and drops nothing. The tick count is
how you notice: nine with a diamond pickaxe, about two hundred with bare hands.

The drop's position is reported because a drop is *thrown* — it lands a block or two from where the
block was, so `walk-to` needs the drop's coordinates and not the block's.

`hold-key ATTACK --ticks 20` is the same thing without the composite, for when you want to hold the
button rather than break a particular block. `hold-key USE` is eating, drinking, drawing a bow and
raising a shield.

### Interacting with an item rather than a block

Some mods' main entry point is an item you right-click holding nothing in particular — an Ability
Bottle, a wrench, a book:

```bash
clientdevbridge use-item --wait-screen
clientdevbridge open-gui               # the same thing: no coordinates means the held item
```

**A right-click aimed at a block interacts with the block**, and the item is never reached — what a
player gets, and the likeliest reason an item looks like it did nothing. `use-item` reports what it
was aimed at and warns when something took the click:

```console
$ clientdevbridge use-item
used minecraft:writable_book x1 (aimed at block); screen: none
warning: The player was looking at a block, which takes a right-click before the held item does.
Aim at nothing first (`look --pitch -90`), or pass --hand main to use the item regardless.
```

`--hand main` (or `off`) skips that decision and uses the item outright, which is also the only way
to reach an off-hand item.

### Finding out what a mod registered

The first question about an unfamiliar mod, and the cheapest:

```bash
clientdevbridge registry namespaces                              # which mods loaded at all
clientdevbridge registry blocks evilcraft --filter ore --limit 20
clientdevbridge registry items integratedtunnels --filter part
```

`registry namespaces` is also the quickest way to tell a mod is genuinely *loaded* rather than
merely listed in the run configuration: one that failed to initialise registers nothing. The lists
are long — one mod has 53 blocks and 90 items — so `--limit` defaults to 100 and says when it
truncated.

### When a command says there is no world

A server-side exception kicks the client out, and the reason is reported rather than buried:

```
error: The client was disconnected, so there is no world: Connection Lost. Server closed
Run 'clientdevbridge world-reset' to start again, but read the reason first -- a disconnect is
usually a server-side exception in the mod under test.
```

Read the reason before resetting. `logs --level error` has the stack that goes with it.

### Reading a mod's own data off an item

`inventory --json` prints a data component through its `toString`, which for most mod types is a
class name and an identity hash — no use for telling a full container from an empty one. A mod fixes
this for its own items by registering an `ItemExtractor` (see `docs/PROTOCOL.md`); the vanilla cases
are covered already:

```console
$ clientdevbridge --json inventory
… "item": "minecraft:shulker_box", "details": { "contains": ["minecraft:diamond x3"], … }
```

For a mod that has registered nothing, Groovy dispatches on the object it is handed, so a script can
call the mod's own methods without naming any of its classes:

```bash
clientdevbridge eval '
def store = player.getMainHandItem().getComponents()
    .find { it.value().getClass().getSimpleName().contains("AbilityStore") }?.value()
store?.getAbilities()'
```

This needs nothing from the mod. It is the general escape hatch for any modded data the typed
commands do not describe.

### Interacting with a particular side of a block

`use` is a right-click with whatever is held, and both it and `open-gui` take `--face` (or `--at` for
a point) to say **where** on the block. Most blocks do not care. Multipart blocks — a cable with a
part on each side — care about nothing else:

```bash
clientdevbridge use 0 3 2 --face up             # place the cable (setblock skips its network)
clientdevbridge command "item replace entity @s weapon.mainhand with integrateddynamics:part_redstone_writer 1"
clientdevbridge use 0 4 2 --face up            # place the part on the top side
clientdevbridge command "item replace entity @s weapon.mainhand with minecraft:air"
clientdevbridge open-gui 0 4 2 --face up       # open that part's GUI, not the one next to it
clientdevbridge block 0 4 2 --nbt              # what is actually on each side
```

Read `use`'s `SUCCESS`/`CONSUME`/`PASS` rather than the before/after lines: in creative nothing
leaves the hand, and a cable gaining a part changes neither its block id nor its state.

The change that *does* show up is the block entity's NBT, which `use` compares and names — a part
added, a side wrenched, a variable card written, a tank filled. It says the NBT changed rather than
printing it; `block <x> <y> <z> --nbt` is how you read it.

Without `--face` a click lands on the block's centre, which on a cable reaches whichever part
happens to be in the way — usually none. With `--face`, the aim point is the centre of the block's
real shape on that side, so a cable, a slab or a panel is hit where it actually is rather than where
a full cube's face would be.

**A part whose side faces a solid block cannot be reached from that side.** The part's shape lives
inside the cable's own block, but the ray to it has to travel through the neighbour, and it stops
at the neighbour. A redstone reader pointed at a machine is the normal case, and it is exactly the
case `--face` alone cannot open. Aim down at the part's strip from above instead: a part occupies
roughly the outer fifth of the block on its side, so for a part on the *south* face of a cable at
`x,y,z` that is

```bash
clientdevbridge open-gui x y z --face up --at <x+0.5>,<y+1>,<z+0.9>
```

The `0.9` is the part's own slice of the block. Point-aiming beats face-aiming whenever the
straight-on approach is blocked.

## Golden screenshots

```bash
clientdevbridge resize --width 854 --height 480 --gui-scale 2   # pin the frame
clientdevbridge compare my-scene --update                        # record
clientdevbridge compare my-scene                                 # check; non-zero exit on mismatch
```

Pin the camera as well as the frame — `world-reset` alone is not enough if anything since then has
moved the player (`inspect-gui` does):

```bash
clientdevbridge teleport 0 5 6 --yaw 180 --pitch 20
clientdevbridge wait --ticks 10
```

Golden images live in `.clientdevbridge/golden/<renderer>/`, which **is** meant to be committed.
They are keyed by renderer because software rasterisation (llvmpipe, what CI uses) and a real GPU
do not produce identical pixels, and one tolerance cannot cover both without hiding regressions.

On a mismatch, `compare` writes a diff PNG and prints its path. Open it: the red pixels tell you
immediately whether you are looking at a regression or at noise.

**Animated textures are the one thing determinism cannot fix.** Lava, fire, water and portals
advance every frame and no game rule stops them. If your scene needs one, either raise
`--threshold`, or compare only the part that holds still:

```bash
clientdevbridge compare my-scene --region 150,60,140,120
clientdevbridge compare my-scene --threshold 5
```

## Fast iteration

```bash
clientdevbridge restart --jdwp-port 5005   # once, to open a debug port
clientdevbridge hotswap --baseline         # record the starting point
# ... edit a method body ...
clientdevbridge hotswap                    # recompile and redefine in place
clientdevbridge screenshot
```

`hotswap` reports what it swapped and what it could not. HotSpot can only replace **method bodies**:
adding or removing a field, a method or a superclass needs `restart`. Classes not yet loaded are
reported as `pending`, which is not a failure — the new code is used the first time they load.
Pointing `JAVA_HOME` at a JetBrains Runtime makes far more swaps succeed.

`hotswap --restart-if-needed` makes that call for you, restarting with the options the running
client was launched with. Use it when you want the change live and do not care which route it took.

**Keep one client alive for the whole session.** Booting is a minute or two, and a restart also
throws away the world you built — which for anything non-trivial cost more commands than the edit
did. Reach for `hotswap` first and `restart` only when it says it cannot swap.

## Running a whole script at once

```bash
clientdevbridge batch scene.txt      # or '-' for stdin
```

Each command otherwise opens a socket, does one thing and closes it — fine for a command you type,
wasteful for the fifty a scene takes to build. `batch` runs them over a single connection, echoing
each line, and stops at the first failure with its line number. `--continue-on-error` runs the rest;
`--json` prints one result object per command.

A whole scene therefore costs one invocation:

```
world-reset
give minecraft:redstone_lamp
setblock 0 4 2 minecraft:redstone_lamp
open-gui 0 4 2 --face north
set-text "Pulse length" 77 --commit enter
close-screen
screenshot --name after
```

## Asserting that something changed

`compare` proves a screen did **not** change. The opposite assertion is `screenshot --diff`:

```bash
clientdevbridge screenshot --name before
clientdevbridge command "setblock 0 4 2 minecraft:redstone_block"
clientdevbridge screenshot --name after --diff .clientdevbridge/screenshots/before.png
```

It exits non-zero when the two captures are too similar, so "the lamp lit up" is a check rather
than something you have to look at yourself.

## When something goes wrong

```bash
clientdevbridge status                       # is it running, and what is on screen?
clientdevbridge logs --level warn            # the game's own log
clientdevbridge logs --gradle --lines 50     # the launch log, for crashes during startup
clientdevbridge eval "player.getY()"         # the escape hatch
clientdevbridge wait --expr "mc.screen != null" --timeout 5000
```

Exit codes are meaningful: `0` success, `1` a protocol-level failure (bad arguments, a method that
refused), `2` a session failure (nothing running, port taken, the client is gone).

Read the error text. It is written to say what to do next, not just what went wrong.

## Asking the cheapest question that answers it

Everything here goes into someone's context, and the choices are not close. Measured on a chest
screen holding one item:

| | bytes | ~tokens |
| --- | ---: | ---: |
| `snapshot` | 225 | 56 |
| `snapshot --json` | 792 | 198 |
| `snapshot --json --include-empty` | 4,893 | 1,223 |
| a screenshot **read as an image** (854×480) | — | 546 |

In order, cheapest first:

1. **Ask the world.** `eval "dev.prop(1, 4, 1, \"lit\")"` is twenty bytes and tells you whether the
   lamp is on. `block`, `inventory` and `look` are the same shape.
2. **Ask for the outline**, not the JSON. `snapshot` is a quarter the size of `snapshot --json` and
   is the form you can actually read. Reach for `--json` when a script has to assert on a field.
3. **Assert on pixels without looking.** `compare` proves a screen did not change and
   `screenshot --diff` proves it did, each in one line and with an exit code. Between them they
   answer almost every "did that work" without an image ever entering context.
4. **Read an image last**, when you genuinely do not know what you are looking for. `--scale 0.5`
   quarters the pixel count and so the cost; `--region` narrows it further.

Building the Integrated Dynamics clock three times made the point: the first run read about ten
screenshots, the third read none and cost about 1,500 tokens of bridge output in total.

Two more, free:

- **`--json` omits empty container and inventory slots**, because a container is mostly empty and
  each empty slot costs about eighty bytes to say so. `slotCount` comes alongside, and the slots are
  a regular grid, so the geometry of the missing ones is still derivable. `--include-empty` restores
  them. **Look slots up by their `index` field, never by position in the array** — it is no longer
  dense.
- **`batch --quiet`** drops the `$ command` echo. Three commands cost 105 bytes with it and 0
  without; on a fifty-line scene the echo is a few hundred tokens every rebuild. Keep the echo while
  you are debugging a batch, drop it once it works.

## Notes that save time

- **`start` is slow the first time** (Gradle resolves Minecraft and the loader) and fast afterwards.
  Leave the client running between commands; that is the whole point of the session model.
- **A screenshot reflects the frame after your last command**, so you rarely need `--after-ticks`.
  Use it when you are waiting on an animation to settle.
- **`--json` on any command** gives the raw protocol result, which is easier to assert on in a
  script than the human-readable output.
- **One client per project directory.** Use `--port` and `--project` to run more than one.
- **`.clientdevbridge/` is added to your `.gitignore` automatically**, except `golden/`.
- **`--project` defaults to the current directory.** If your shell resets its working directory
  between commands — many agent harnesses do — pass `--project <dir>` explicitly every time, or you
  will silently target a different checkout.
- **Check exit codes on scene setup.** `setblock`, `give` and `command` exit `1` when the game
  rejected the command, so `setblock ... && inspect-gui ...` will stop rather than inspect a scene
  that was never built.
