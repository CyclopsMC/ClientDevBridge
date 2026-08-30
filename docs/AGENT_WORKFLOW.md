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
npx @cyclopsmc/clientdevbridge-cli doctor    # can this machine build and launch a client?
npx @cyclopsmc/clientdevbridge-cli start     # ~2 min cold, headless if there is no $DISPLAY
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
  of nothing. Slot geometry is a regular grid from the ones that are shown; or use `--json`, which
  lists every slot.
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

**Prefer paths over labels for anything you click more than once.** Many vanilla widgets have no
real label and fall back to their narration, which *changes with their state* — the recipe-book
button reads `button Left click to activate` until it is focused and `button Press Enter to
activate` afterwards. A label that matched before a click may not match after it. Paths are stable
within a screen.

`find` is the quick way to locate something on a busy screen:

```bash
clientdevbridge find "Apply" --type Button
```

## Setting up a scene

`world-reset` deletes and regenerates a creative superflat world with time, weather, mobs and
random ticks all switched off, a stone platform under the spawn, and the player at `0, 4, 0` with
yaw 0 — which faces **+Z (south)**, so the block at `0, 4, 2` used in every example here is
straight ahead. It is the same world every time, which is what makes screenshots comparable.

```bash
clientdevbridge world-reset
clientdevbridge command "setblock 0 4 2 minecraft:furnace"
clientdevbridge give yourmod:wrench 1
clientdevbridge teleport 0 5 6 --yaw 180 --pitch 20
clientdevbridge look --at 0,4,2
clientdevbridge block 0 4 2 --nbt
```

For a scene too fiddly to script, commit a world under `clientdevbridge/templates/<name>/` in your
repository and use `world-reset --template <name>`.

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
