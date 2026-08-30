# Phase 7 validation — multi-version support

Independent black-box validation. I did not write ClientDevBridge. I read only `README.md`,
`AGENTS.md`, `docs/`, `skills/`, `CHANGELOG.md` and `--help` output before testing; source was read
only afterwards, to diagnose failures already found by testing.

- CLI: `clientdevbridge` 0.1.0 (`npm link`ed from `$PHASE7/cli`)
- Branch A: `$PHASE7/cdb-26lts` — `master-26-lts`, Minecraft **26.1.2**, project `e2e/consumer`
- Branch B: `$PHASE7/cdb-26` — `master-26`, Minecraft **26.2**, project `e2e/consumer`
- Loader: neoforge (auto-detected), headless via Xvfb + llvmpipe, no `$DISPLAY`
- `$PHASE7 = /tmp/claude-0/-home-user-ClientDevBridge/afb55b5f-523b-5ad0-9797-5c16511da073/scratchpad/validate7`

Every command below was run with **the same CLI binary and identical arguments** on both branches,
differing only in `--project`.

---

## Verdict summary

| Criterion | `master-26-lts` (26.1.2) | `master-26` (26.2) |
|---|---|---|
| Phase 1 — doctor / start / status / screenshot / stop | **PASS** | **PASS** |
| Phase 2 — world-reset / setblock / open-gui / wait / screenshot | **PASS** | **PASS** |
| Phase 3 — inspect-gui / outline↔screenshot cross-check / click / tooltip / outline changes | **PASS with a defect** (see D1) | **PASS with a defect** (see D1) |
| Phase 4 — golden create / pass / perturb / readable diff / size+scale determinism | **PASS with a defect** (see D2) | **PASS with a defect** (see D2) |
| **Phase 7 acceptance — same CLI, unchanged commands, both versions** | **PASS** | **PASS** |

**Phase 7 itself passes.** Nothing I found is version-specific: the two defects below reproduce
*identically* on both branches, so they are pre-existing bugs in the shared code, not multi-version
regressions. Output text, widget outlines, widget paths, GUI coordinates and rendered pixels were
indistinguishable between 26.1.2 and 26.2.

The strongest single result: a golden image **recorded on Minecraft 26.1.2 matched the live 26.2
client with 0 of 409920 pixels differing.**

---

## Setup — no manual workarounds needed

`JAVA_HOME` pointed at Java 21 while both branches need Java 25. I ran `doctor` first without
touching anything, and the tool handled it:

```
$ clientdevbridge doctor --project $PHASE7/cdb-26lts/e2e/consumer
ok    java             Java 21 from JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 is too old for this
                       project, which needs 25; Gradle will be run on
                       /usr/lib/jvm/java-1.25.0-openjdk-amd64 (Java 25) instead
ok    node             v22.22.2
ok    gradle wrapper   .../cdb-26lts/e2e/consumer/gradlew
ok    xvfb             xvfb-run available
ok    mesa (software GL)  llvmpipe drivers present in /usr/lib/x86_64-linux-gnu/dri
ok    project          Minecraft 26.1.2, neoforge, task :loader-neoforge:runClient
ok    clientdevbridge build   org.cyclops.clientdevbridge:clientdevbridge-26.1.2-neoforge from branch master-26-lts
ok    mavenLocal build        found /root/.m2/repository/org/cyclops/clientdevbridge/clientdevbridge-26.1.2-neoforge
ok    loaders available       fabric, neoforge
... 14 network probes, all ok ...
Everything checks out. Run: clientdevbridge start
EXIT 0
```

`master-26` produced byte-for-byte the same report except `Minecraft 26.2` /
`clientdevbridge-26.2-neoforge from branch master-26`. **No `publishToMavenLocal` was needed** —
the `mavenLocal build` check told me so directly, which is exactly the right affordance. Total
elapsed: ~40 s per branch (dominated by the network probes).

I made no manual changes to `JAVA_HOME`, Gradle, or anything else at any point.

---

## Phase 1 — launch and look

```
$ clientdevbridge start --project <consumer> --timeout 900
Display: xvfb-run with Mesa llvmpipe
This project needs Java 25; running Gradle on /usr/lib/jvm/java-1.25.0-openjdk-amd64 (Java 25) instead of the environment's own.
Still starting... (0s)
Still starting... (15s) BUILDING VERSION: DEV
Still starting... (30s) [..] 294 Datafixer optimizations took 689 milliseconds
The client runs in <consumer>/loader-neoforge/run, not <consumer>/loader-neoforge/runs/client.
Pinned the determinism options there; restart to apply them to a running client.
ClientDevBridge is ready.
project      <consumer>
loader       neoforge
minecraft    26.1.2          # 26.2 on master-26
mod version  1.0.0
protocol     1
port         25599
username     ClientDevBridge
world        -
eval         true
mods         cdbconsumer, clientdevbridge, minecraft, neoforge
```

- 26-lts: 43.4 s. 26: 44.5 s. (Caches were warm; the docs' "~2 min cold" is consistent.)
- `status` reported `AccessibilityOnboardingScreen`, `gui 427x240 @ scale 2`, `pixel 854x480` on both.
- `stop` → `Stopped the ClientDevBridge client.`; `status` after → `Not running (no session recorded)`;
  a second `stop` → `No client was running.` (exit 0). Clean.

**Screenshots**

- 26-lts: `$PHASE7/cdb-26lts/e2e/consumer/.clientdevbridge/screenshots/screenshot_2026-08-29_23-53-32-051.png`
  — a genuine Minecraft title/onboarding screen: the MINECRAFT JAVA EDITION logo over a live cherry-grove
  panorama, the "Welcome to Minecraft!" narrator prompt, and four buttons rendered with correct fonts and
  9-slice borders. Real rendering, not a stub.
- 26: `$PHASE7/cdb-26/e2e/consumer/.clientdevbridge/screenshots/screenshot_2026-08-30_00-00-58-346.png`
  — the same screen, pixel-for-pixel the same layout, over 26.2's different (dark/nether) panorama.
  Version difference is Minecraft content, not the bridge.

**PASS on both branches.**

---

## Phase 2 — build a scene and open a GUI

Identical commands on both:

```
$ clientdevbridge world-reset
World 'clientdevbridge' is ready (fresh creative superflat), player at 0, 4, 0.     (7.6 s / 7.9 s)
$ clientdevbridge setblock 0 4 2 minecraft:crafting_table
Changed the block at 0, 4, 2
$ clientdevbridge open-gui 0 4 2
screen: net.minecraft.client.gui.screens.inventory.CraftingScreen
$ clientdevbridge wait --screen CraftingScreen
Condition met (screen: net.minecraft.client.gui.screens.inventory.CraftingScreen, in world: true).
$ clientdevbridge screenshot
854x480 px  (window 854x480, gui 427x240 @ scale 2)
<path>
```

**Screenshots**

- 26-lts: `.../cdb-26lts/e2e/consumer/.clientdevbridge/screenshots/screenshot_2026-08-29_23-54-01-768.png`
- 26: `.../cdb-26/e2e/consumer/.clientdevbridge/screenshots/screenshot_2026-08-30_00-01-19-860.png`

Both show the vanilla crafting GUI centred on screen: "Crafting" label, the 3×3 grid, the arrow, the
result slot, the green recipe-book button on the left, the "Inventory" label and the 9×4 player grid,
the empty hotbar below, and the darkened superflat world behind with the crafting table's brown side
visible bottom-right. **Visually identical between the two branches.**

**PASS on both branches.**

---

## Phase 3 — outline, cross-check, interaction

```
$ clientdevbridge world-reset && clientdevbridge give minecraft:diamond 5 \
  && clientdevbridge setblock 0 4 2 minecraft:crafting_table && clientdevbridge inspect-gui 0 4 2
```

Outline — **character-for-character identical on both branches**:

```
CraftingScreen  "Crafting"
gui 427x240 @ scale 2, window 854x480px, mouse at 0,0
container CraftingMenu at (125,37) 176x166, 46 slots (1 filled)
  slot 14 (empty) @(205,121) hovered
  slot 37 minecraft:diamond x5 @(133,179)
  ImageButton " button Left click to activate" @(130,71 20x18)  /root/children[0]
  CraftingRecipeBookComponent @(unknown)  /root/children[1]
```

Screenshots: `.../cdb-26lts/.../screenshots/inspect-gui_2026-08-29_23-57-11-518.png` and
`.../cdb-26/.../screenshots/inspect-gui_2026-08-30_00-01-43-845.png`.

**Cross-check of the outline against the pixels** (GUI space × scale 2 = pixel space):

| Outline claim | Predicted pixels | What I see in the PNG |
|---|---|---|
| `container CraftingMenu at (125,37) 176x166` | panel x 250–602, y 74–406 | panel edges land exactly there ✓ |
| `slot 37 minecraft:diamond x5 @(133,179)` | 266–298, 358–390 | the diamond stack, with a small "5" ✓ |
| `ImageButton @(130,71 20x18)` | 260–300, 142–178 | the green recipe-book icon ✓ |
| `slot 14 (empty) @(205,121) hovered` | 410–442, 242–274 | that one inventory cell is visibly lighter (hover highlight) ✓ |
| `46 slots (1 filled)` | — | 9 crafting + 1 result + 36 inventory = 46 ✓, one holds diamonds ✓ |
| `CraftingRecipeBookComponent @(unknown)` | — | matches the documented "bounds unknown" caveat ✓ |

Empty slots are omitted as the docs say. The outline and the screenshot agree completely.

**Tooltip / find / click** (identical output on both branches):

```
$ clientdevbridge tooltip --at 133,179     -> Diamond
$ clientdevbridge tooltip --at 205,121     -> No tooltip at 205,121 (source: none).
$ clientdevbridge find "Left click"        -> ImageButton " button Left click to activate"  click at 140,80  /root/children[0]
$ clientdevbridge click --widget "Left click"
screen: net.minecraft.client.gui.screens.inventory.CraftingScreen
```

**Outline after the click** — changed exactly as it should, identically on both branches:

```
gui 427x240 @ scale 2, window 854x480px, mouse at 140,80          # mouse moved
container CraftingMenu at (202,37) 176x166, 46 slots (1 filled)   # 125 -> 202: panel shifted right
  slot 37 minecraft:diamond x5 @(210,179)                         # 133 -> 210, same delta
  ImageButton " button Press Enter to activate" @(207,71 20x18) focused  /root/children[0]
  CraftingRecipeBookComponent @(unknown)  /root/children[1]
```

The narration-label change (`Left click to activate` → `Press Enter to activate`) plus `focused` is
precisely the behaviour `AGENT_WORKFLOW.md` warns about — the docs are accurate.

Screenshots after the click: `.../cdb-26lts/.../screenshot_2026-08-29_23-57-35-647.png` and
`.../cdb-26/.../screenshot_2026-08-30_00-01-57-324.png`. Both show the recipe book panel opened on
the left, the crafting panel pushed right to match the reported x=202 (pixel 404), and a hovered
recipe tooltip. Visual and structural views agree.

**PASS on both branches, with defect D1 below.**

### D1 — FAIL: `open-gui` / `inspect-gui` fail on every repeat call

**Reproduces identically on both branches.** Deterministic, verified 6+ times per branch.

```
$ clientdevbridge world-reset
$ clientdevbridge setblock 0 4 2 minecraft:crafting_table
$ clientdevbridge open-gui 0 4 2          -> screen: ...CraftingScreen        EXIT 0   (A: works)
$ clientdevbridge close-screen            -> Closed the screen.
$ clientdevbridge open-gui 0 4 2          -> "No screen opened..."            EXIT 1   (B: fails)
$ clientdevbridge teleport 0 4 0 --yaw 0 --pitch 0
$ clientdevbridge close-screen
$ clientdevbridge open-gui 0 4 2          -> screen: ...CraftingScreen        EXIT 0   (C: works again)
```

Discriminating tests, all with the player standing still at the approach spot:

```
close-screen; wait --ticks 40; open-gui 0 4 2                -> FAIL (exit 1)
close-screen; wait --ticks 40; open-gui 0 4 2 --no-approach  -> OK   (exit 0)
close-screen; wait --ticks 40; open-gui 0 4 2                -> FAIL (exit 1)
```

So it is not a timing race with `close-screen`, and not the block: it is the **`approach` step, and
only when the player is already standing where `approach` wants to put them** — which is exactly the
state `open-gui`/`inspect-gui` leave the player in. Hence "first call after `world-reset` or an
explicit `teleport` works, the immediately repeated call always fails."

Why this matters: `inspect-gui` is documented as "the usual starting point" and sits inside the
advertised `edit → hotswap → inspect-gui → compare` loop. That loop is not repeatable as written —
the second iteration fails. And the error message actively misleads:

```
No screen opened for the block at 0,4,2. Right-clicking minecraft:crafting_table at 0, 4, 2 opened
no screen. The player is 2.3 blocks away (reach 5.0). If that block has no GUI this is expected;
otherwise check 'clientdevbridge block 0 4 2' and try again with more ticks.
```

It names the reach (which is fine), suggests the block may have no GUI (it does), and suggests more
ticks (they do not help — I waited 40 extra ticks and it still failed).

**Workaround for users:** `clientdevbridge look --at X,Y,Z && clientdevbridge open-gui X Y Z --no-approach`,
or `teleport` somewhere else before every `inspect-gui`. Both verified working on both branches.

**Root cause (source read after testing, to diagnose):**
`ScreenHandler` waits for the approach teleport to round-trip by polling
`PlayerControl.isAt(target)`, where `ScreenControl.approachTarget()` returns
`(x+0.5, y+1.0, z+2.5)` and `PlayerControl.isAt` (loader-common `.../mcadapter/PlayerControl.java:60`)
uses a **y tolerance of 1.5**:

```java
return Math.abs(player.getX() - x) < 0.5d
    && Math.abs(player.getY() - y) < 1.5d
    && Math.abs(player.getZ() - z) < 0.5d;
```

The player rests at `y` (they fall the 1.0 block immediately), so `|y − (y+1.0)| = 1.0 < 1.5` is
**already true before the teleport is processed**. The "arrived" wait therefore returns instantly and
`useItemOn` is sent while the integrated server still has the old player state — precisely the failure
`ScreenControl.approach`'s own javadoc warns about ("until the new position has come back the server
still believes the player is wherever it was, and silently rejects the interaction as out of reach").
When the player starts somewhere else, the same wait genuinely synchronises, so the call succeeds.

Suggested fix direction: make the arrival signal an actual edge (e.g. record the pre-teleport
position and wait for a change, or drop the `isAt` y-slop and target the resting y), or skip the
teleport entirely when the player is already at the approach spot.

**Second, latent issue in the same handler** (found in source, not separately isolatable in the black
box because D1 masks it): the "did it open?" predicate in `ScreenHandler` is
`screenClass() != null && !Objects.equals(screenClass(), before)`. Re-opening a screen of the same
class that is already open therefore can never be reported as success, even if the interaction worked.

---

## Phase 4 — goldens and determinism

Identical commands on both branches:

```
$ clientdevbridge resize --width 854 --height 480 --gui-scale 2
window 854x480px, gui 427x240 @ scale 2
$ clientdevbridge teleport 0 5 6 --yaw 180 --pitch 20
Player at 0.00, 5.00, 6.00, facing -180/20.
$ clientdevbridge wait --ticks 10
$ clientdevbridge compare scene-lts --update
Wrote the golden image for 'scene-lts' (renderer llvmpipe).
<consumer>/.clientdevbridge/golden/llvmpipe/scene-lts.png
```

Golden images:
`$PHASE7/cdb-26lts/e2e/consumer/.clientdevbridge/golden/llvmpipe/scene-lts.png` and
`$PHASE7/cdb-26/e2e/consumer/.clientdevbridge/golden/llvmpipe/scene-lts.png`.
Both: a crafting table on a grey stone superflat plain under a clear blue sky, hotbar with the
diamond stack in slot 1, the first-person held diamond in the lower right, crosshair centred. Clean,
animation-free, exactly the kind of scene a golden should be. The two are visually indistinguishable.

**Re-compare (expect pass):**

| Branch | Result |
|---|---|
| 26-lts | `scene-lts: matches (0 of 409920 pixels differ, 0.000% <= 0.1%).` exit 0 |
| 26 | `scene-lts: matches (6 of 409920 pixels differ, 0.001% <= 0.1%).` exit 0 |

**Perturb the camera (`look --yaw 200 --pitch 20`, +20° yaw) and re-compare (expect fail):**

| Branch | Result |
|---|---|
| 26-lts | `DIFFERS — 41575 of 409920 pixels, 10.142% > 0.1%` exit 1 |
| 26 | `DIFFERS — 41605 of 409920 pixels, 10.150% > 0.1%` exit 1 |

Diff PNGs (`<consumer>/.clientdevbridge/diffs/scene-lts-diff.png` on each branch) are **readable and
genuinely useful**: the unchanged scene is washed out to near-white, and in solid red you see the two
crafting-table silhouettes (golden position and current position) side by side, the shifted horizon
as a red band, and red speckle across the ground where the stone texture slid. Anyone opening this
knows within a second that the camera moved rather than that the block changed. Both branches
produced visually identical diffs.

**World-reset reproducibility** (extra check I added): tearing the whole scene down and rebuilding it

```
world-reset; give minecraft:diamond 5; setblock 0 4 2 minecraft:crafting_table;
teleport 0 5 6 --yaw 180 --pitch 20; wait --ticks 40; compare scene-lts
```

gave `DIFFERS — 10116 / 10123 of 409920 pixels, 2.47%` on both branches. Opening the diff showed the
difference is **one horizontal red band at the chat line** — the transient `give` message — and
nothing else; the table, ground, sky and held item were pixel-clean. Waiting 400 more ticks for the
chat to fade brought it back to `matches (6 of 409920 pixels differ, 0.001%)`. So the world and camera
really are reproducible; only the chat overlay is transient. Good result, minor friction (F6).

**Cross-version golden test** (the sharpest Phase 7 evidence): I copied the golden recorded on the
**26.1.2** client into the **26.2** project and compared it against the live 26.2 client:

```
$ cp cdb-26lts/.../golden/llvmpipe/scene-lts.png cdb-26/.../golden/llvmpipe/xbranch.png
$ clientdevbridge compare xbranch --project cdb-26/e2e/consumer
xbranch: matches (0 of 409920 pixels differ, 0.000% <= 0.1%).
```

**Zero differing pixels across two Minecraft versions.**

**Window size / GUI scale determinism:** `resize --width 854 --height 480 --gui-scale 2` reliably
produces `gui 427x240 @ scale 2 / pixel 854x480`, `status` and every screenshot header agree, and the
0-pixel compares above confirm the frame really is pinned. But `resize` itself has defect D2.

**PASS on both branches, with defect D2 below.**

### D2 — FAIL: `resize` misreports and spuriously errors whenever the window size changes

**Reproduces identically on both branches.**

```
# starting at 854x480 @ scale 2
$ clientdevbridge resize --width 640 --height 360
window 854x480px, gui 427x240 @ scale 2        <-- prints the OLD geometry
EXIT 0
$ clientdevbridge status | grep size
gui size    640x360 @ scale 1                  <-- but the resize DID happen
pixel size  640x360

$ clientdevbridge resize --width 854 --height 480 --gui-scale 2
error: GUI scale must be between 0 (auto) and 1 at 854x480, but was 2
EXIT 1                                          <-- wrong bound, and...
$ clientdevbridge status | grep size
gui size    427x240 @ scale 2                  <-- ...it applied anyway, correctly
pixel size  854x480

$ clientdevbridge resize --width 854 --height 480 --gui-scale 2     # identical command, retried
window 854x480px, gui 427x240 @ scale 2
EXIT 0
```

Three problems in one command:

1. **The printed geometry is one call stale** — it reports the pre-resize window, so a successful
   resize looks like it did nothing.
2. **The GUI-scale bound is computed from the *previous* window size while the message names the
   *requested* one.** At 854x480 the real maximum scale is 2 (the tool itself says
   `must be between 0 (auto) and 2 at 854x480` when the window is already that size), but coming from
   640x360 it claims the max at 854x480 is 1. The message is therefore actively wrong.
3. **It exits 1 while having done exactly what was asked.** A script doing
   `resize ... && compare ...` aborts on a resize that succeeded.

Bounds checking itself is fine when the window size is not changing:
`--gui-scale 99` → `GUI scale must be between 0 (auto) and 2 at 854x480, but was 99` (exit 1, correct);
`--width 1 --height 1` → `Window size must be between 64 and 7680 pixels in each dimension, but was 1x1`
(exit 1, not applied). Both are good messages.

**Root cause (source read after testing):** `loader-common/.../mcadapter/WindowControl.java:35-47`
calls `GLFW.glfwSetWindowSize(...)` then `minecraft.resizeGui()` and immediately reads
`window.calculateScale(...)`. The code comment asserts this makes the new size visible right away:

```java
// GLFW delivers the resize asynchronously; asking Minecraft to re-read it now means the
// very next frame is already at the new size, rather than one frame later.
minecraft.resizeGui();
// The GUI scale has to be validated against the *new* window: ...
```

Empirically it does not — GLFW's framebuffer-size callback has not run yet, so both the validation
and the returned geometry still see the old window. The intent in the comment is right; the
implementation needs to wait for the resize to land (a frame / the callback) before validating and
reporting.

---

## Off-script probing

| # | What I did | Result | Judgement |
|---|---|---|---|
| 1 | `status` before any `start` | `Not running (no session recorded) in <dir>.` + `Start one with: clientdevbridge start`, exit 0 | Excellent — states the fact and the next command |
| 2 | `clientdevbridge screenshoot` (typo) | `error: unknown command 'screenshoot'` + `(Did you mean screenshot?)` + full help, exit 1 | Excellent |
| 3 | `screenshot` with no client | `error: No ClientDevBridge session in <dir>. Run 'clientdevbridge start' first (add --project <dir> if the mod project is elsewhere).` exit **2** | Excellent; exit code matches the documented `2 = session failure` |
| 4 | `start` twice, same project | `error: A ClientDevBridge client is already running for <dir> (pid 28073, port 25599). Run 'clientdevbridge stop' first, or 'clientdevbridge restart' to replace it.` exit 2 | Excellent |
| 5 | `start` on branch B while branch A's client runs | `error: Port 25599 on 127.0.0.1 is already in use... An orphaned client still holds it: pid 28450 (java). Stop it with 'kill 28450', or start this one elsewhere with --port <other>.` exit 2 | Correct and gives the `--port` escape, but calls a perfectly healthy client in another project "orphaned" (F3) |
| 6 | `resize --gui-scale 99`, `--width 1 --height 1` | precise bounded errors, exit 1, nothing applied | Excellent |
| 7 | `resize --gui-scale 2` alone (no `--width`) | `error: required option '--width <px>' not specified` | Correct behaviour, but `--help` does not mark `--width` as required (F4) |
| 8 | `setblock 0 4 3 minecraft:not_a_block` | `Unknown block type 'minecraft:not_a_block'` + a caret pointing at the offending token, exit 1 | Excellent — it is Minecraft's own parser error, surfaced verbatim |
| 9 | `click --widget "Nonexistent Button"` | `error: No widget matches 'Nonexistent Button' on this screen.` + `Run 'clientdevbridge snapshot' to see the widgets, then pass a label or a /root/children[N] path.` exit 1 | Excellent |
| 10 | `wait --screen FurnaceScreen --timeout 2000` (never opens) | `Timed out after 2000 ms waiting for screen (screen is none, in world: true).` exit 1 | Excellent — reports the actual state, not just "timeout" |
| 11 | `click --at 9999,9999` (GUI is 427x240) | `screen: none`, exit 0, silent | Weak (F5) |
| 12 | `status --project /tmp/nope-does-not-exist` | `Not running (no session recorded) in /tmp/nope-does-not-exist.` then `Something is still listening on port 25599: pid 28450 (java). That is an orphaned client whose session file is gone. ... stop it with: kill 28450` exit 0 | Poor (F3) — the directory does not exist and is not flagged, and the advice would kill a healthy client belonging to another project |

Error-message quality is, overall, well above average: nearly every one names the state, the cause and
the next command. The two weak spots are #11/#12 and D1's misleading hint.

---

## Friction list

Not blockers, but each cost me time or would mislead a user.

- **F1 — every `start` prints a run-directory warning.** Both branches, every launch:
  `The client runs in <consumer>/loader-neoforge/run, not <consumer>/loader-neoforge/runs/client.
  Pinned the determinism options there; restart to apply them to a running client.` It reads like
  something went wrong and tells you to restart, on a first launch where restarting is pointless. If
  this is the normal path for a NeoForge project it should not read as a warning; if it is genuinely
  a fallback, say what the consequence is (determinism options are not applied for *this* session).
- **F2 — `docs/cloud-setup.md` is wrong about the JDK on these branches.** It says
  *"You also need a **JDK 21** (Minecraft 1.21 requires it)"*, and the file is byte-identical on
  `master-26-lts` and `master-26`, where the requirement is Java 25. It also hard-codes
  `master-1.21-lts` in the `curl` quick-start URL on all branches. This is the one place where the
  docs betray a Minecraft version — and betray the wrong one. (`doctor` gets it right, which saved me.)
- **F3 — the port-conflict / orphan heuristic ignores other projects.** A client legitimately running
  for project X is described as "an orphaned client whose session file is gone", with
  `kill <pid>` as the recommended action, when you run any command against project Y. Given the docs
  explicitly bless "one client per project directory", the check should look for a live session file
  in other known project dirs before calling a process orphaned. Also, `--project` pointing at a
  directory that does not exist is not flagged at all.
- **F4 — `resize --help` does not mark `--width`/`--height` as required.** They are (see off-script #7),
  and there is no way to change only the GUI scale. Either mark them, or make them optional and keep
  the current size.
- **F5 — out-of-range `click --at` is silently accepted.** `click --at 9999,9999` on a 427x240 GUI
  exits 0 with no warning. Given `resize` bounds-checks so carefully, this is inconsistent, and a
  typo'd coordinate will look like a widget that did not respond.
- **F6 — `give`'s chat line pollutes goldens for ~200 ticks.** A perfectly reproducible scene fails at
  2.47% purely because of the chat message from a preceding `give`. The `compare` failure hint mentions
  animated blocks and "a toast popup" but not chat; adding chat to that hint (or having `world-reset`
  set `gamerule sendCommandFeedback false` / clearing chat) would save a confusing detour.
- **F7 — the diff PNG path is reused per golden name.** `<name>-diff.png` is overwritten by each
  failing compare, so two consecutive failures leave only the second. Fine for interactive use;
  surprising if you compare several times and go back to look.
- **F8 — `teleport` prints the position after gravity has acted, inconsistently.**
  `teleport 0 5 6 --yaw 180 --pitch 20` printed `Player at 0.00, 5.00, 6.00` on one call and
  `Player at 0.00, 4.00, 6.00` on another (the player falls to the platform in between). Harmless
  here, but the confirmation line is nondeterministic for the same command.
- **F9 — the `publishToMavenLocal` step is only in the ClientDevBridge `README.md` "Building" section,**
  not in `AGENT_WORKFLOW.md` or `SKILL.md`, which are the agent-facing docs. `doctor`'s
  `mavenLocal build` check is what actually made it discoverable and told me it was already done. That
  check is doing a lot of work; worth a sentence in the agent docs too.
- **F10 — `doctor` reports the Java-version mismatch as `ok`.** The line says Java 21 "is too old",
  which reads as a problem, under an `ok` marker. A `warn`/`note` level (or wording like
  "using Java 25 from /usr/lib/jvm/...") would scan better.
- **F11 — the `master-26-lts` README's version table omits `master-26`.** Correct per the forward-merge
  model (`master-26` branches off later), but a reader on the LTS branch cannot see that a newer line
  exists. Minor.

---

## What I could not verify, and why

- **Fabric.** `doctor` reports `loaders available: fabric, neoforge`, but the consumer fixture is
  detected as neoforge and I did not force `--loader fabric`; every launch here was NeoForge. Phase 7's
  criterion does not mention loaders, so I left it, but "same CLI, two versions" is only proven for
  NeoForge.
- **A real Flopper checkout.** Unavailable in this environment (private Maven credentials), as the
  fixture's `e2e/consumer/README.md` explains clearly and honestly. Everything I exercised used
  vanilla Minecraft GUIs only — no mod-authored screen, container, or block renderer was tested, so
  version-agnosticism is proven for the *bridge*, not for a real mod's own GUI code.
- **`hotswap`, `drag`, `scroll`, `type`, `hold-key`, `world-load`/`world-list`/`world-leave`,
  `world-reset --template`, `compare --region`/`--threshold`, `--json`, `--port`.** Out of scope for
  the Phase 1–4 scenario; not exercised.
- **A cold cache.** All Gradle/Minecraft artifacts were pre-warmed, so I cannot speak to the documented
  "~2 min cold" figure or to the network allowlist actually being sufficient from scratch; `doctor`'s
  14 network probes all passed.
- **`doctor` with a missing mavenLocal build.** The builds were already published for all three lines,
  so I never saw the failure path or whether it prints the exact `publishToMavenLocal` command.
- **The `screenClass() != before` latent issue** (end of D1) could not be isolated behind D1 in the
  black box; it is a source reading only.
- **Whether `--headed` works** — no `$DISPLAY` on this machine, so headless was the only mode.
