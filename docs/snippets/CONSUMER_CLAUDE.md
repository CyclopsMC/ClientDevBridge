<!--
Paste this section into the CLAUDE.md or AGENTS.md of a mod that uses ClientDevBridge.
It is deliberately self-contained: an agent reading only this should be able to work.
-->

## Looking at the game (ClientDevBridge)

This project can be driven through a real Minecraft dev client from the shell. Use it whenever you
change a GUI, a screen, a container, or anything that renders — do not guess at what a change looks
like when you can look.

```bash
npx @cyclopsmc/clientdevbridge-cli doctor   # first time, or if something is wrong
npx @cyclopsmc/clientdevbridge-cli start    # ~2 min cold; leave it running between commands
```

Nothing needs to be added to this repository: the mod is injected at launch through a generated
Gradle init script.

### The loop

```bash
clientdevbridge world-reset                       # deterministic world, player at 0,4,0
clientdevbridge setblock 0 4 2 <mod>:<block>
clientdevbridge inspect-gui 0 4 2                 # outline + a screenshot path
```

`inspect-gui` prints a widget outline and then a PNG path on its own line.

**Screenshots are file paths, never inline images. Open the path with the Read tool and look at
it.** The outline says what the game thinks is on screen; the screenshot says what a player sees.

### Interacting

```bash
clientdevbridge click --widget "Apply"     # by label, by /root/children[N] path, or --at x,y
clientdevbridge type "text"
clientdevbridge key ESCAPE
clientdevbridge tooltip --at 133,179
clientdevbridge snapshot                   # re-read the screen after acting
```

All coordinates are in GUI space — the same space the outline reports — so numbers can be fed
straight back.

### Rendering regressions

```bash
clientdevbridge resize --width 854 --height 480 --gui-scale 2
clientdevbridge compare <name> --update    # record a golden (commit it)
clientdevbridge compare <name>             # check it; writes a readable diff PNG on failure
```

### Notes

- `setblock`, `give` and `command` exit `1` when the game rejects them — chain scene setup with `&&`.
- `inspect-gui` moves the player to bring the block into reach; teleport and look explicitly before
  recording a golden image.
- `--project` defaults to the current directory; pass it explicitly if your shell resets cwd.
- **The same commands work on every Minecraft-version branch of this repository.** The CLI is
  version-agnostic; only the mod build it injects differs.
- `clientdevbridge logs --level warn` for the game's log, `--gradle` for startup crashes.
- Exit codes: `0` fine, `1` bad arguments, `2` nothing running.
- `clientdevbridge stop` when finished.
