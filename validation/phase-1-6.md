# ClientDevBridge — independent validation report

**Validator:** black-box; docs + `--help` only during testing, source consulted afterwards solely to
root-cause failures already observed.
**Workspace:** `/tmp/validation` (fresh clones).
**Target:** `/tmp/validation/cdb/e2e/consumer`, NeoForge, Minecraft 1.21.1, headless (no `$DISPLAY`).
**Setup:** clone → `npm install && npm run build && npm link` (clean, ~30s) → `./gradlew publishToMavenLocal --no-daemon` (BUILD SUCCESSFUL, 1m31s).

---

## 1. Results table

| # | Item | Verdict | Evidence |
|---|---|---|---|
| 1 | `doctor` | **PASS** | 23/23 checks `ok` incl. xvfb, llvmpipe, mavenLocal build, all 14 network hosts. Ends `Everything checks out. Run: clientdevbridge start`. Exit 0. |
| 2 | `start` → `status` → `screenshot` | **PASS** | `start --timeout 900` returned ready in **1m40s**. `status` reported `screen TitleScreen`, `fps 43`, `gui 427x240 @ scale 2`. Opened the PNG: a genuine, fully-rendered Minecraft 1.21.1 title screen — panorama, logo, splash, all six buttons, `NeoForge 21.1.2 (4 mods)` in the corner. Real rendering, not a stub. |
| 3 | `world-reset` → `setblock` → `inspect-gui` | **PASS**, with two real caveats | Outline vs. image cross-check was **exact**: `container CraftingMenu at (125,37) 176x166` → panel occupies px (250,74)–(602,406) ✅; `46 slots` = 1 result + 9 grid + 36 inventory ✅; `slot 14 … hovered` → the 5th slot of the top inventory row *is* the visibly lightened slot ✅; `ImageButton @(130,71 20x18)` → the green recipe-book icon at px (260,142) 40×36 ✅. GUI-space × 2 = pixels, exactly as documented. **Caveats: (a) the header's `mouse at 0,0` is false — see F1; (b) the entire recipe-book UI is invisible to the outline — see F2.** |
| 4 | `give` → `snapshot` → `tooltip` | **PASS** | `Gave 5 [Diamond]`; outline flipped to `46 slots (1 filled)` / `slot 37 minecraft:diamond x5 @(133,179)`; screenshot shows the diamond stack at px (266,358) — dead on. `tooltip --at 133,179` → `Diamond`. `inventory` agreed. |
| 5 | `click --widget` | **PASS** | `find "button"` → `click at 140,80  /root/children[0]`. `click --widget "button"` toggled the recipe book. Outline changed substantially and correctly: container shifted `(125,37)` → `(202,37)`, diamond slot `(133,179)` → `(210,179)`, hovered slot `14` → `10`, button gained `focused` and its narration changed `Left click to activate` → `Press Enter to activate`. Screenshot confirms every one of those. |
| 6 | Golden flow | **PASS** | `resize --width 854 --height 480 --gui-scale 2` → `window 854x480px, gui 427x240 @ scale 2`. `compare crafting-scene --update` wrote `golden/llvmpipe/crafting-scene.png`. `compare crafting-scene` → **`matches (0 of 409920 pixels differ, 0.000%)`** — bit-exact, genuinely deterministic. After `look --yaw 145 --pitch 10`: `DIFFERS — 165439 of 409920 pixels, 40.359% > 0.1%`, exit 1, diff PNG written. **Diff PNG opened: highly readable** — current frame ghosted to near-white, changed pixels in saturated red; the shifted horizon band, the crafting table's selection box, and the swung-in held item are all individually identifiable, and the unchanged hotbar stays white. I could tell "camera moved" from "one widget regressed" at a glance. |
| 7 | `logs`, `eval`, `wait` | **PARTIAL FAIL** | `logs --level warn` → 4 of 1636 lines with a clear `# 4 of 1636 buffered lines` footer ✅. `wait --screen CraftingScreen` raced against a concurrent `open-gui` and returned `Condition met (…)` exit 0 ✅; timeout path returns a good message and exit 1 ✅. `eval "player.getY()"` → `4` ✅. **But `eval` returning any object/list prints literal `function toString() { [native code] }` — see F3.** |
| 8 | `stop` → `status` | **PASS**, noisy | `stop` → `Stopped the ClientDevBridge client.` exit 0; both the `xvfb-run` wrapper (7285) and the java client (7602) were gone; the session's Xvfb was cleaned up. `status` → `Not running (no session recorded)… Start one with: clientdevbridge start`, exit 0. Correct, but it then dumps 20 lines of gradle log — see F6. |
| 9 | `session.json` deleted mid-run | **PARTIAL FAIL** | With pid 7285 verifiably alive and port 25599 held: `status` → **`Not running (no session recorded)`** (a confident lie), `stop` → **`No client was running.` exit 0** (leaves the client orphaned with no CLI way to kill it). `screenshot`/`snapshot` → clean `error: No ClientDevBridge session… Run 'clientdevbridge start' first (add --project <dir> …)`, exit 2 — good wording, wrong premise. Only `start` recovers, and it recovers *excellently*: `An orphaned client still holds it: pid 7602 (java). Stop it with 'kill 7602', or start this one elsewhere with --port <other>.` — see F4. |

**Off-script probes** (typo, `status` before `start`, second `start`, nonsense args, bad eval, missing world, deleted session file) are folded into the friction list below.

---

## 2. Friction list

Ordered by how much damage each one does to an agent driving this tool.

### F1 — `mouse-move` does not move the mouse, and the outline reports a phantom cursor. **(bug)**
The outline header prints `mouse at X,Y`, and `mouse-move` updates that number — but nothing else.
Hover is still driven by the *real* GLFW cursor, which sits at the window centre.

```
$ clientdevbridge mouse-move 155,54        # crafting-grid slot, top-left
$ clientdevbridge snapshot
gui 427x240 @ scale 2, window 854x480px, mouse at 155,54
  slot 14 (empty) @(205,121) hovered        <-- still slot 14, not the grid slot
$ clientdevbridge eval "'' + mc.mouseHandler.xpos() + ',' + mc.mouseHandler.ypos()"
"427.0,240.0"                               <-- real cursor never moved
```
The screenshot taken immediately after still highlights slot 14. And on the very *first* snapshot of a
fresh screen the header says `mouse at 0,0` while the same outline says `slot 14 hovered` — two
mutually contradictory facts, three lines apart, and the screenshot sides with neither the header nor
the user's last command.

Root cause (`mcadapter/InputControl.java:37`): `mouseMove` stores a synthetic `mouseX/mouseY` and calls
`screen.mouseMoved(x, y)`, which is a no-op on `AbstractContainerScreen`; `hoveredSlot` is recomputed in
`render()` from the real handler's coordinates.

Consequences: (a) the header's `mouse at` is untrustworthy and should either be removed or labelled
"last synthetic input position"; (b) **you cannot screenshot a hover state you chose** — no hover
highlight, no rendered tooltip — which is a real capability gap for a tool whose pitch is "see what a
player would see". `tooltip --at` works only because `TooltipCapture` renders at the point explicitly.
The docs' central promise ("a bug is usually a disagreement between the outline and the screenshot")
is undermined when the tool manufactures such a disagreement itself.

### F2 — Whole visible sub-UIs can be missing from the outline, reported as `0x0`. **(bug)**
With the recipe book open — a search `EditBox`, a close button, 4 tab buttons and ~11 recipe buttons
plainly rendered across a third of the screen — the outline says, in full:

```
  RecipeBookComponent @(0,0 0x0)  /root/children[1]
```

`--include-hidden` adds nothing. `find "Search"` → `No widget matches 'Search'.` An agent working from
the outline alone cannot see, locate or click any of it. Reporting `0x0` is worse than omitting it:
it silently asserts a zero-sized thing rather than admitting "not introspectable". Vanilla's recipe
book is about as common a screen as exists, so this is not an exotic edge case.

### F3 — `eval` prints garbage for every non-primitive value. **(bug, one-line fix)**
```
$ clientdevbridge eval "[mc.mouseHandler.xpos(), mc.mouseHandler.ypos()]"
function toString() { [native code] }
$ clientdevbridge eval "[…]" --json
{ "value": [427, 240], … }        # the data was there all along
```
`src/commands/input.ts:176` — `if (… 'toString' in (value as object))` is true for **every** JS object
(inherited from `Object.prototype`), so the branch fires for all objects and arrays and then prints
`String(value['toString'])`, i.e. the function itself. Nothing in the docs hints that `eval` needs
`--json`; the escape hatch is broken by default for anything that isn't a number or a string.

### F4 — `status` and `stop` never probe the port, so they lie about orphans. **(bug)**
`start` already knows how to detect an orphan and even names the right pid and the exact `kill`
command — that message is genuinely excellent. But `status` reports `Not running` and `stop` reports
`No client was running.` **exit 0** for the same live process. `cloud-setup.md` documents the orphan
story only for `start`. The result: the command an agent reaches for first (`status`) gives a
confidently wrong answer, and the command it reaches for to clean up (`stop`) silently no-ops and
leaves a Minecraft client running. `status` and `stop` should do the same port probe `start` does.

### F5 — Failed Minecraft commands exit **0**. **(bug, contradicts the documented contract)**
Every doc states exit `1` means "a protocol-level failure (bad arguments, a method that refused)".
Observed:
```
$ clientdevbridge setblock 0 4 2 minecraft:not_a_real_block ; echo $?
Unknown block type 'minecraft:not_a_real_block'
0
$ clientdevbridge give minecraft:not_an_item 5 ; echo $?      → 0
$ clientdevbridge command "totallybogus 1 2 3" ; echo $?       → 0
$ clientdevbridge setblock 0 4 2 minecraft:nope --json
{ "output": [ "Unknown block type 'minecraft:nope'", "…<--[HERE]" ] }   # no success flag at all
```
`src/commands/world.ts:143,147` route `setblock` and `give` through `runCommand`, which prints
`result.output` and never inspects it. The `--json` payload carries no status field either, so the CLI
*cannot* currently tell success from failure — this needs a protocol change (return the command's
result/success), not just a CLI patch. **This is the most damaging finding for automation:**
`clientdevbridge setblock … && clientdevbridge inspect-gui …` will happily proceed against a scene
that was never built, and the agent then debugs a phantom.

### F6 — `world-load <missing>` leaves the world *before* validating the name. **(bug)**
```
$ clientdevbridge world-load no_such_world
error: There is no world called 'no_such_world'. Existing worlds: clientdevbridge   (exit 1)
$ clientdevbridge block 0 4 2
error: Not in a world. Run 'clientdevbridge world-reset' or 'world-load <name>' first.
```
The message itself is first-rate (it even lists the valid names), but a typo'd world name **destroys
your live session state** and dumps you back to the title screen. Validate the name first.

### F7 — `inspect-gui` / `open-gui` silently teleport and re-aim the player. **(doc gap, breaks goldens)**
Measured:
```
before inspect-gui:  pos=0.5,4.0,0.5  yaw=0.0  pitch=0.0
after  inspect-gui:  pos=0.5,4.0,4.5  yaw=0.0  pitch=30.0
```
(`mcadapter/ScreenControl.java:74` — `tp @s %.2f %.2f %.2f 0 30`.) `AGENT_WORKFLOW.md` describes
`inspect-gui` as only "right-clicks the block, waits for the screen, prints the outline, and writes a
screenshot". Because it moves the camera, a golden recorded after `inspect-gui` is **not** reproducible
from the documented `world-reset` → `setblock` → `compare` recipe: my golden failed to re-match at
56.2% after a `world-load` + `look --yaw 180 --pitch 0`, because the *position* had drifted and
nothing in the docs tells you to pin it. The golden section should say: always `teleport` **and**
`look` explicitly before `compare --update`, and note that `inspect-gui` moves you.

### F8 — `world-reset` faces **south**, not north as documented.
`AGENT_WORKFLOW.md`: "the player at `0, 4, 0` facing north." Actual: `yaw=0.0`, which in Minecraft is
south / +Z. (`WorldControl.java:213` — `tp @s 0 4 0 0 0`.) Facing +Z is the *right* choice — it points
at the `0 4 2` the docs use in every example — but the compass word is wrong, and an agent that trusts
it will compute block positions in the wrong direction.

### F9 — The outline silently omits empty slots, and nothing says so.
`46 slots (0 filled)` followed by exactly one `slot` line took a moment to parse: only *filled* and
*hovered* slots are listed. That is a sensible compaction, but neither `AGENT_WORKFLOW.md`,
`SKILL.md` nor `snapshot --help` mentions it, and `--include-hidden` does not expand it. An agent
looking for "where is empty slot 3 so I can click it" has to derive the geometry instead of reading
it, on a tool whose whole selling point is that coordinates can be fed straight back.

### F10 — Widget labels can be narration strings, which makes `--widget` matching fragile.
The recipe-book button is labelled `" button Left click to activate"` — leading space, no real name,
and it **changes to `" button Press Enter to activate"` once focused**. So `click --widget "Left click
to activate"` works before the first click and silently stops matching after it. The docs' example
(`click --widget "Apply"`) sets an expectation of stable human labels that vanilla screens do not meet.
Worth documenting: prefer `/root/children[N]` paths for anything you will click twice.

### F11 — `status` after a deliberate `stop` dumps 20 lines of gradle log.
That log tail is a great touch when a client *crashed*. After a clean, intentional `stop` it reads like
a post-mortem and buries the two useful lines under 20 lines of `ThreadedAnvilChunkStorage … saved`.
The session file is gone in both cases, so the CLI can't tell them apart — recording an
"intentionally stopped" marker would let it stay quiet.

### F12 — `-p/--project` defaults to the shell's cwd, which is a footgun for agents.
`clientdevbridge --help` run from `/home/user/ClientDevBridge` shows
`--project <dir> (default: "/home/user/ClientDevBridge")`. Agent harnesses commonly reset cwd between
commands (mine does). Every single one of my invocations needed an explicit `cd …` in the same shell
line. The docs open with `cd path/to/your/mod` and never warn that a stray cwd silently retargets a
*different* project. Worth a sentence in `SKILL.md`, since coding agents are the stated audience.

### F13 — Minor.
- The unknown-command handler is good (`(Did you mean screenshot?)`) but then dumps the entire ~45-line
  command list, which is a lot of tokens for a typo.
- `find "" --type ImageButton` works with an empty search string — undocumented, but handy; worth
  making `--type` usable without a positional arg.
- `.clientdevbridge/` gitignore handling is exactly as advertised: `git status` showed only the new
  `golden/llvmpipe/crafting-scene.png` as untracked. Nice.
- Two orphaned `Xvfb` servers and ~25 stale `/tmp/xvfb-run.*` dirs were on this box from runs
  *predating* my session. My own start/stop cycle cleaned up correctly, so I can't attribute the leak —
  but a ~2-in-25 leak rate is worth someone checking.

---

## 3. What worked notably well

- **Determinism is real.** `0 of 409920 pixels differ` on a re-`compare` in a software rasteriser is a
  strong result and the single best thing about this tool.
- **The diff PNG is genuinely good.** Ghosted background plus red delta — I could diagnose "the camera
  moved" from the image alone, without the text.
- **Outline geometry is trustworthy.** Every container, slot and widget rectangle I checked against the
  rendered pixels was exact, including the 2× GUI-space→pixel conversion. That is the core claim and it
  holds.
- **`doctor` is excellent** — it checks the mavenLocal build and every network host, and it was right.
- **`start`'s orphan message** is the best error in the tool. It just needs to exist in `status`/`stop` too.
- **Headless works with no fuss.** One `unset DISPLAY`, no configuration, 1m40s cold boot on llvmpipe.
- **Error copy is consistently written for an agent** ("Run X first", "For example: --at 210,100",
  "Existing worlds: …"). Where the premise was right, the wording told me exactly what to do next.

---

## 4. Overall verdict

**PASS with reservations.** Seven of the nine numbered items pass outright; items 7 and 9 pass only
partially. The core value proposition — boot a real headless client, get a structurally accurate
outline, get a pixel-accurate screenshot, catch regressions against a bit-exact golden — **works, and
works well.** I would trust it for GUI-layout and rendering-regression work today.

What I would not yet trust it for, unsupervised:

1. **Scripted scene setup**, because failing `setblock`/`give`/`command` return exit 0 (F5). This is a
   correctness hazard, not a polish issue, and it needs a protocol change.
2. **Anything hover-dependent**, because `mouse-move` moves a number and not the cursor (F1).
3. **Screens with embedded sub-components**, because they can be reported as `0x0` with no children (F2).
4. **Recovery after a lost session file**, because `status` and `stop` both report a live client as
   gone (F4).

Fix F5 and F4 first: they are the two that let an agent proceed confidently on a false premise, which
is exactly the failure mode a tool like this exists to prevent. F1 and F2 are the two that break the
"outline vs. screenshot" methodology the docs are built around. F3 is a one-line fix. F7 and F8 are
doc corrections that cost nothing and prevent real confusion.

---

## Implementer's response

Every finding was reproduced. What changed in response:

| Finding | Status | What was done |
|---|---|---|
| F1 mouse-move did not move the cursor | **Fixed** | `MouseHandler`'s position is now written directly. `glfwSetCursorPos` is ignored while the window is unfocused, which it always is under a virtual display, so the real cursor never moved. Hover highlights, hovered slots and rendered tooltips now follow `mouse-move`; verified by screenshotting a hovered slot and seeing its tooltip drawn. |
| F2 sub-UIs reported as `0x0` | **Fixed (honesty, not capability)** | A component that reports no rectangle is now marked `boundsUnknown` with a note saying its children are not introspectable and to read a screenshot instead. Vanilla's recipe book still cannot be walked — it exposes no children through the standard interfaces — but the snapshot no longer asserts something false about it. |
| F3 `eval` printed `function toString()` | **Fixed** | The shape check now requires both `type` and `toString` to be strings, rather than asking whether a `toString` key exists — which is true of every JavaScript object. Regression test added. |
| F4 `status`/`stop` lied about orphans | **Fixed** | Both now probe the port when no session is recorded, and report the pid and the `kill` command, matching what `start` already did. |
| F5 failed commands exited 0 | **Fixed (protocol change)** | `world.command` now returns `success` and `value`, taken from Brigadier's result callback — the only place the outcome is available. `setblock`, `give` and `command` exit 1 when the game rejects them. |
| F6 `world-load` left the world before validating | **Fixed** | The name is checked before leaving, so a typo no longer costs the caller its session. |
| F7 `inspect-gui` silently teleports | **Documented** | The workflow doc now says it moves the player, and that a golden needs an explicit `teleport` and `look` first. |
| F8 spawn faces south, not north | **Documented** | Corrected; the doc now explains that yaw 0 faces +Z, which is why every example uses `0 4 2`. |
| F9 empty slots omitted silently | **Documented** | Called out, with `--json` given as the way to see every slot. |
| F10 narration labels change with state | **Documented** | The docs now recommend `/root/children[N]` paths for anything clicked twice, with the recipe-book button as the worked example. |
| F11 log tail after a clean stop | **Fixed** | The tail is only shown when the client is recorded as having died, not when no session was tracked at all. |
| F12 `--project` defaults to cwd | **Documented** | Flagged in both the workflow doc and the skill, since agent harnesses commonly reset the working directory. |
| F13 misc | **Partly addressed** | The rest are noted; the stale Xvfb processes predated the validated build and could not be attributed.

The two findings called out as most damaging — F5 and F4, both cases of the tool letting a caller
proceed on a false premise — are fixed, and F5 needed the protocol change the report predicted.
