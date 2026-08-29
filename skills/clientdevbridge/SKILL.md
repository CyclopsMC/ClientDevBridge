---
name: clientdevbridge
description: Launch and drive a real Minecraft dev client from the shell to inspect and debug a mod's GUIs and rendering. Use when working on a Minecraft mod and you need to see what a screen actually looks like, click through it, check widget or slot state, or catch a rendering regression with golden screenshots. Triggers on Minecraft modding work involving GUIs, screens, containers, menus, widgets, block rendering, or "does this look right".
---

# ClientDevBridge

Drive a real Minecraft dev client from bash. You get two views of the game: a **structured
outline** of every widget and slot, and a **screenshot** you can open and look at.

Works headless, so it is available in cloud sessions and CI as well as locally.

## Before you start

```bash
clientdevbridge doctor    # or: npx @cyclopsmc/clientdevbridge-cli doctor
```

If `doctor` reports problems, fix those first — it prints the exact command for each. On a headless
Linux box the usual missing piece is `sudo apt-get install -y xvfb libgl1-mesa-dri`.

## The loop

```bash
cd path/to/the/mod
clientdevbridge start                 # ~2 min cold; returns when the client is actually ready
clientdevbridge world-reset           # deterministic creative superflat, player at 0,4,0
clientdevbridge setblock 0 4 2 themod:the_block
clientdevbridge inspect-gui 0 4 2     # right-click it, print the outline, write a screenshot
```

`inspect-gui` prints the widget outline and then a PNG path on its own line. **Open that path with
your file-reading tool and actually look at it.** The outline tells you what the game thinks is
there; the screenshot tells you what a player sees. Bugs live in the gap between them.

Leave the client running between commands — each command is a separate, fast invocation against the
same session. `clientdevbridge stop` when you are done.

## Reading the outline

```
CraftingScreen  "Crafting"
gui 427x240 @ scale 2, window 854x480px, mouse at 0,0
container CraftingMenu at (125,37) 176x166, 46 slots (1 filled)
  slot 37 minecraft:diamond x5 @(133,179)
  Button "Apply" @(312,208 60x20) disabled  /root/children[3]
```

Every coordinate is in GUI space, which is what the input commands take, so numbers can be fed
straight back. The trailing `/root/children[N]` path is what `--widget` accepts when a label is
ambiguous.

## Interacting

```bash
clientdevbridge click --widget "Apply"      # or --widget /root/children[3], or --at 312,208
clientdevbridge type "some text"
clientdevbridge key ESCAPE                  # or E, F3, GLFW_KEY_ENTER
clientdevbridge tooltip --at 133,179
clientdevbridge snapshot                    # re-read the screen after acting
clientdevbridge find "Apply" --type Button
```

State-changing commands print the resulting screen class, so you can tell immediately whether a
click did anything.

## Setting up the world

```bash
clientdevbridge command "setblock 0 4 2 minecraft:furnace"
clientdevbridge give themod:wrench 1
clientdevbridge teleport 0 5 6 --yaw 180 --pitch 20
clientdevbridge look --at 0,4,2
clientdevbridge block 0 4 2 --nbt
clientdevbridge inventory
```

`world-reset` gives the same world every time: fixed seed, no daylight cycle, no weather, no mobs,
a stone platform under the spawn.

## Catching rendering regressions

```bash
clientdevbridge resize --width 854 --height 480 --gui-scale 2
clientdevbridge compare my-scene --update    # record the golden, once
clientdevbridge compare my-scene             # check it; non-zero exit and a diff PNG on mismatch
```

Goldens live in `.clientdevbridge/golden/<renderer>/` and are meant to be committed. Animated blocks
(lava, fire, water, portals) never match exactly — use `--threshold` or a `--region` that excludes
them.

## Iterating on code

```bash
clientdevbridge restart --jdwp-port 5005
clientdevbridge hotswap --baseline
# edit a method body
clientdevbridge hotswap        # says what it swapped, and what needs a full restart
```

Only method bodies can be swapped. Adding a field or method needs `clientdevbridge restart`.

## When stuck

```bash
clientdevbridge status
clientdevbridge logs --level warn
clientdevbridge logs --gradle --lines 50    # for crashes during startup
clientdevbridge eval "player.getY()"        # Groovy, with mc/player/level/screen/server bound
```

Exit codes: `0` success, `1` protocol error (bad arguments), `2` session error (nothing running).
Add `--json` to any command for the raw result.

## Things to remember

- Screenshots are **file paths**, never inline data. Open them.
- The same commands work on every Minecraft-version branch of a mod — the CLI is version-agnostic.
- One client per project directory; use `--port` and `--project` for more.
- Full reference: `docs/AGENT_WORKFLOW.md` and `docs/PROTOCOL.md` in the ClientDevBridge repository.
