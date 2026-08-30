# Phase 7 re-validation — independent run

**Date:** 2026-08-30
**CLI:** `clientdevbridge` 0.1.0 (same binary for every run below; `npm link`ed from `$PHASE7/cli`)
**Branches under test:**
- `$PHASE7/cdb-26lts` — `master-26-lts`, Minecraft 26.1.2
- `$PHASE7/cdb-26` — `master-26`, Minecraft 26.2

**Projects driven:** `<repo>/e2e/consumer` in each clone (the minimal fixture; Flopper unavailable).
**Environment:** headless (no `$DISPLAY`), Xvfb + Mesa llvmpipe, `JAVA_HOME` = Java 21, Java 25 also installed.

`PHASE7=/tmp/claude-0/-home-user-ClientDevBridge/afb55b5f-523b-5ad0-9797-5c16511da073/scratchpad/validate7b`
is elided to `$PHASE7` throughout.

Method: black box. Only `README.md`, `AGENTS.md`, `docs/AGENT_WORKFLOW.md`,
`skills/clientdevbridge/SKILL.md` and `--help` were read before testing. Source was opened only
afterwards, and only to pin down failures already observed.

---

## 0. Preparation

```
$ ./gradlew publishToMavenLocal        # in $PHASE7/cdb-26lts   -> BUILD SUCCESSFUL in 27s
$ ./gradlew publishToMavenLocal        # in $PHASE7/cdb-26      -> BUILD SUCCESSFUL in 28s
```

Both succeeded with `JAVA_HOME` still pointing at Java 21 (the Gradle toolchain resolved Java 25
itself). No hand workaround was needed anywhere in this run.

---

## 1. Coverage matrix

Four cells were exercised, all with **byte-identical commands**:

| Branch | Minecraft | Loader | Phase 1 | Phase 2 | Phase 3 | Phase 4 |
|---|---|---|---|---|---|---|
| `master-26-lts` | 26.1.2 | neoforge | **PASS** | **PASS** | **PASS** | **PASS** |
| `master-26-lts` | 26.1.2 | fabric   | **PASS** | **PASS** | **PASS** | **PASS** |
| `master-26`     | 26.2   | neoforge | **PASS** | **PASS** | **PASS** | **PASS** |
| `master-26`     | 26.2   | fabric   | **PASS** | **PASS** | **PASS** | **PASS** (see finding H) |

**Acceptance criterion — "repeat the Phase 1–4 scenario on `master-26-lts` and `master-26` with the
SAME CLI version, unchanged commands": PASS on both branches.**

Not a single command needed a branch-specific flag, a different argument, or a different
interpretation of output. The human-readable outline was *textually identical* across all four
cells for the same scene (same widget paths, same coordinates, same slot indices).

### What betrays the Minecraft version to the user

Only informational fields, never behaviour:

- `start` / `status` print a `minecraft` row (`26.1.2` / `26.2`).
- `doctor` prints `Minecraft 26.1.2, neoforge` and `... from branch master-26-lts`.
- `status --json` carries `mcVersion` in `session` and `hello`.

Nothing in the command surface, the outline format, the coordinate system, the exit codes or the
error texts differs. The one behavioural difference found is game data, not bridge data: the
vanilla recipe-book grid orders recipes differently on 26.2 than on 26.1.2 (26.1.2 hovers
"Diamond Pickaxe" at gui 140,80; 26.2 hovers "Diamond Boots" at the same point). That would break
a golden shared between the two, and is out of the bridge's control.

### Cross-cell pixel parity (stronger than required)

| Golden recorded on | Frame compared | Result |
|---|---|---|
| 26.1.2 neoforge | 26.1.2 fabric | `matches (0 of 409920 pixels differ, 0.000%)` |
| 26.1.2 neoforge | 26.2 neoforge | `matches (0 of 409920 pixels differ, 0.000%)` |
| 26.2 neoforge | 26.2 fabric | differed — see finding H |

Two of three cross-cell comparisons were **pixel-perfect**, which is a much stronger determinism
result than the criterion asks for.

---

## 2. Phase 1 — doctor / start / status / screenshot / stop

### `doctor` (before any client, on `master-26-lts`)

```
$ clientdevbridge doctor --project $PHASE7/cdb-26lts/e2e/consumer
warn  java                    Java 21 from JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 is too old
                              for this project, which needs 25; Gradle will be run on
                              /usr/lib/jvm/java-1.25.0-openjdk-amd64 (Java 25) instead
ok    node                    v22.22.2
ok    gradle wrapper          .../e2e/consumer/gradlew
ok    xvfb                    xvfb-run available
ok    mesa (software GL)      llvmpipe drivers present in /usr/lib/x86_64-linux-gnu/dri
ok    project                 Minecraft 26.1.2, neoforge, task :loader-neoforge:runClient
ok    clientdevbridge build   org.cyclops.clientdevbridge:clientdevbridge-26.1.2-neoforge
                              from branch master-26-lts
ok    mavenLocal build        found /root/.m2/.../clientdevbridge-26.1.2-neoforge
ok    loaders available       fabric, neoforge
ok    net <15 hosts>          ...

Worked around:
  - java: Nothing has to be done -- 'clientdevbridge start' substitutes that JDK itself. ...

Everything checks out. Run: clientdevbridge start
EXIT=0
```

Same shape on `master-26` (`Minecraft 26.2, neoforge`, `from branch master-26`) and with
`--loader fabric`.

### `start`

| Cell | wall time | exit |
|---|---|---|
| 26.1.2 neoforge | 43.6 s | 0 |
| 26.1.2 fabric   | 35.4 s | 0 |
| 26.2 neoforge   | 44.5 s | 0 |
| 26.2 fabric     | 34.9 s | 0 |

(Warm Gradle caches; the documented 2–4 min cold figure was not exercised.) `start` prints the JDK
substitution up front — `This project needs Java 25; running Gradle on
/usr/lib/jvm/java-1.25.0-openjdk-amd64 (Java 25) instead of the environment's own.` — then a
progress line every 15 s carrying the newest Gradle/game log line, which is genuinely useful.

### `status`

Reports project, loader, minecraft, protocol, port, pid, display, uptime, gradle-log path, and
then live state: `in world`, `screen`, `tick`, `fps`, `gui size ... @ scale 2`, `pixel size`,
`game dir`. `--json` returns the same plus `glRenderer` and the full session record.

### `screenshot`

```
$ clientdevbridge screenshot --project ... --name phase1-title
854x480 px  (window 854x480, gui 427x240 @ scale 2)
.../.clientdevbridge/screenshots/phase1-title.png
```

**Opened and judged:**

- `$PHASE7/cdb-26lts/.../screenshots/phase1-title.png` — real Minecraft title screen, panorama
  background, `Minecraft 26.1.2` / `NeoForge 26.1.2.22-beta (4 mods)` in the corner, a
  `Create Test World` button from the consumer fixture. **Real Minecraft screen: yes.**
- `$PHASE7/cdb-26lts/.../screenshots/fab-title.png` — same, `Minecraft 26.1.2 (Modded)`, no
  NeoForge beta banner. **Real: yes.**
- `$PHASE7/cdb-26/.../screenshots/m26-title.png` — `Minecraft 26.2` / `NeoForge 26.2.0.6-beta`.
  **Real: yes.**

### `stop`

1.7 s, exit 0. Second `stop` prints `No client was running.` and exits 0. `status` afterwards:
`Not running (no session recorded) in <dir>. Start one with: clientdevbridge start`, exit 0.

**Phase 1: PASS on both branches, both loaders.**

---

## 3. Phase 2 — world-reset / setblock / open-gui / wait --screen / screenshot

Identical on all four cells:

```
$ clientdevbridge world-reset --project $P
World 'clientdevbridge' is ready (fresh creative superflat), player at 0, 4, 0.     (~8 s)
$ clientdevbridge setblock 0 4 2 minecraft:crafting_table --project $P
Changed the block at 0, 4, 2
$ clientdevbridge block 0 4 2 --project $P
Block{minecraft:crafting_table}
$ clientdevbridge open-gui 0 4 2 --project $P
screen: net.minecraft.client.gui.screens.inventory.CraftingScreen
$ clientdevbridge wait --screen CraftingScreen --project $P
Condition met (screen: net.minecraft.client.gui.screens.inventory.CraftingScreen, in world: true).
$ clientdevbridge screenshot --project $P --name phase2-crafting
854x480 px  (window 854x480, gui 427x240 @ scale 2)
```

**Opened and judged:**

- `$PHASE7/cdb-26lts/.../screenshots/phase2-crafting.png` (26.1.2 neoforge) — a real vanilla
  crafting GUI: "Crafting" label, 3×3 grid, arrow, result slot, recipe-book book icon on the left,
  "Inventory" label, 3×9 grid + hotbar row, dim superflat behind. One inventory slot in the top row
  is visibly highlighted (the hovered slot the outline reports). **Crafting GUI confirmed.**
- `$PHASE7/cdb-26lts/.../screenshots/fab-crafting.png` (26.1.2 fabric) — indistinguishable.
- `$PHASE7/cdb-26/.../screenshots/m26-crafting.png` (26.2 neoforge) — indistinguishable.

**Phase 2: PASS on both branches, both loaders.**

---

## 4. Phase 3 — inspect-gui, cross-check, click, tooltip

### The outline

```
$ clientdevbridge inspect-gui 0 4 2 --project $P --name insp-1
CraftingScreen  "Crafting"
gui 427x240 @ scale 2, window 854x480px, mouse at 0,0
container CraftingMenu at (125,37) 176x166, 46 slots (0 filled)
  slot 14 (empty) @(205,121) hovered
  ImageButton " button Left click to activate" @(130,71 20x18)  /root/children[0]
  CraftingRecipeBookComponent @(unknown)  /root/children[1]

.../.clientdevbridge/screenshots/insp-1.png
```

Byte-identical on 26.1.2/neoforge, 26.1.2/fabric, 26.2/neoforge, 26.2/fabric.

### Cross-check of the outline against `insp-1.png` (opened and measured)

GUI space × scale 2 = pixel space.

| Outline claim | Predicted pixels | Seen in the PNG | Verdict |
|---|---|---|---|
| `container ... at (125,37) 176x166` | panel (250,74)–(602,406) | panel left edge ≈250, top ≈74, right ≈602, bottom ≈406 | **matches** |
| `ImageButton @(130,71 20x18)` | (260,142) 40×36 | green recipe-book icon at ≈(262,140)–(298,176) | **matches** |
| `slot 14 (empty) @(205,121) hovered` | (410,242) 32×32 | the 5th slot of the top inventory row is rendered lighter (vanilla hover highlight) at ≈(410,242) | **matches** |

Every reported widget and slot is visibly where the outline says it is. After `give minecraft:diamond 5`
the outline gained `slot 37 minecraft:diamond x5 @(133,179)`, and the diamond is visible at pixel
(266,358) in the screenshot. **Cross-check: PASS.**

### `tooltip`

```
$ clientdevbridge tooltip --at 133,179 --project $P
Diamond
$ clientdevbridge tooltip --at 133,179 --project $P --json
{ "at": [133,179], "lines": ["Diamond"], "source": "slot", "slot": 37,
  "item": { "index": 37, "item": "minecraft:diamond", "count": 5, "name": "Diamond",
            "components": "{minecraft:item_model=>minecraft:diamond, ...}" } }
```

The coordinate came straight out of the outline and resolved to the right slot. Same on both
branches and both loaders.

### `click --widget` and the outline changing

```
$ clientdevbridge find "button" --project $P
ImageButton " button Left click to activate"  click at 140,80  /root/children[0]

$ clientdevbridge click --widget "/root/children[0]" --project $P
screen: net.minecraft.client.gui.screens.inventory.CraftingScreen
```

Outline **before** → **after**:

```
container CraftingMenu at (125,37) ...        ->  container CraftingMenu at (202,37) ...
  slot 37 minecraft:diamond x5 @(133,179)     ->    slot 37 minecraft:diamond x5 @(210,179)
  ImageButton " button Left click to activate"->    ImageButton " button Press Enter to activate"
     @(130,71 20x18)  /root/children[0]                @(207,71 20x18) focused  /root/children[0]
```

The container shifted 77 px right, every slot moved with it, and the button's narration label
changed exactly as `docs/AGENT_WORKFLOW.md` warns it would. **The outline changes after the click.**

**Opened and judged:** `$PHASE7/cdb-26lts/.../screenshots/phase3-afterclick.png` — the recipe book
panel is open on the left, the crafting panel has shifted right, the diamond stack sits at the new
slot-37 position, and a "Diamond Pickaxe / When in Main Hand: 5 Attack Damage / 1.2 Attack Speed"
tooltip is drawn under the cursor at pixel (280,160) = gui (140,80), which is exactly where `find`
said the widget centre was. Screenshot and outline agree completely.
`.../screenshots/fab-afterclick.png` and `$PHASE7/cdb-26/.../screenshots/m26-afterclick.png` are
the same (26.2 shows "Diamond Boots" at that grid position — a vanilla recipe-ordering difference).

**Phase 3: PASS on both branches, both loaders.**

---

## 5. Phase 4 — golden flow and determinism

```
$ clientdevbridge resize --width 854 --height 480 --gui-scale 2 --project $P
window 854x480px, gui 427x240 @ scale 2
$ clientdevbridge teleport 0 5 6 --yaw 180 --pitch 20 --project $P
Player at 0.00, 5.00, 6.00, facing -180/20.
$ clientdevbridge wait --ticks 10 --project $P
Waited 10 ticks (now at tick 3520).

$ clientdevbridge compare golden-scene --update --project $P
Wrote the golden image for 'golden-scene' (renderer llvmpipe).
.../.clientdevbridge/golden/llvmpipe/golden-scene.png

$ clientdevbridge compare golden-scene --project $P
golden-scene: matches (0 of 409920 pixels differ, 0.000% <= 0.1%).      exit 0
$ clientdevbridge compare golden-scene --project $P
golden-scene: matches (0 of 409920 pixels differ, 0.000% <= 0.1%).      exit 0
```

**Opened and judged:** `$PHASE7/cdb-26lts/.../golden/llvmpipe/golden-scene.png` — daylit superflat
stone platform, a crafting table centred at eye height with its selection outline, a held diamond in
the bottom-right, hotbar at the bottom. Clean, correct, reproducible-looking.
`$PHASE7/cdb-26/.../golden/llvmpipe/m26-scene.png` (26.2) is visually indistinguishable.

### Perturbing the scene

```
$ clientdevbridge look --yaw 200 --pitch 20 --project $P
Facing 200.0/20.0.
$ clientdevbridge compare golden-scene --project $P
golden-scene: DIFFERS — 36782 of 409920 pixels, 8.973% > 0.1%. Golden: .../golden-scene.png
.../.clientdevbridge/diffs/golden-scene_2026-08-30_00-47-19-053-diff.png
If the diff is concentrated on an animated block (lava, fire, water, a portal), on a toast popup,
or on the chat lines that commands like give and setblock leave on screen for ten seconds, that is
animation rather than a regression: wait it out, raise --threshold, or pass --region to compare
only the part that should hold still.
exit 1
```

**Opened and judged (three diff PNGs, one per cell):**

- `$PHASE7/cdb-26lts/.../diffs/golden-scene_2026-08-30_00-47-19-053-diff.png`
- `$PHASE7/cdb-26lts/.../diffs/fab-scene_2026-08-30_00-51-22-149-diff.png`
- `$PHASE7/cdb-26/.../diffs/m26-scene_2026-08-30_00-54-28-447-diff.png`

All three are immediately readable: a washed-out version of the frame with the changed pixels in
solid red. The crafting table appears **twice** in red — its old and new screen positions — which
tells you at a glance that the camera moved rather than the block changed. The unchanged sky and
hotbar are white. This is exactly what the diff is supposed to communicate.

### Determinism

| Check | Result |
|---|---|
| `compare` twice in a row, nothing touched | `0 of 409920` both times, all cells |
| camera moved away and teleported back | `7 of 409920 (0.002%)` — within threshold |
| `resize 640x360 s1` → `resize 854x480 s2` → compare | `0 of 409920` — a resize round trip is exactly reversible |
| `--gui-scale` pinned | `status` reports `gui size 427x240 @ scale 2 / pixel size 854x480` consistently, and `screenshot` echoes it on every capture |

Window size and GUI scale are deterministic and correctly pinned. Screenshots are always
`854x480 px (window 854x480, gui 427x240 @ scale 2)` unless deliberately resized.

### `--threshold` and `--region`

```
$ clientdevbridge compare golden-scene --threshold 99 --project $P
golden-scene: matches (36733 of 409920 pixels differ, 8.961% <= 99%).      exit 0

$ clientdevbridge compare golden-scene --region 0,0,60,40 --project $P
Size mismatch: the golden image is 854x480 but the screenshot is 120x80.
Pin the window with `clientdevbridge resize` before comparing, or re-record with --update.
.../diffs/golden-scene-actual.png
exit 1                                                        <-- see finding C
```

`--threshold` works. `--region` does not work the way the docs describe — see **finding C**.

**Phase 4: PASS on both branches, both loaders** (the golden loop, the failing diff and the
determinism all work; `--region` is a separate documented-behaviour failure, filed below).

---

## 6. Verdict on each of the six "claimed fixed" items

### 1. Repeated `open-gui` / `inspect-gui` — **FIXED, verified**

`inspect-gui 0 4 2` run 4× back to back with **no** `close-screen` between calls: all four exited 0
and printed identical outlines, on 26.1.2/neoforge, 26.1.2/fabric, 26.2/neoforge and 26.2/fabric.
Three further runs with `close-screen` interleaved: same. `open-gui` 3× with no close and 3× with
close: `screen: net.minecraft.client.gui.screens.inventory.CraftingScreen`, exit 0, every time.
No "opened no screen" was ever seen.

### 2. `resize` — **FIXED, verified**

```
$ clientdevbridge resize --width 640 --height 360 --gui-scale 1 --project $P
window 640x360px, gui 640x360 @ scale 1
$ echo $?
0
$ clientdevbridge status ... | grep -E "gui size|pixel size"
gui size    640x360 @ scale 1
pixel size  640x360

$ clientdevbridge resize --width 854 --height 480 --gui-scale 2 --project $P
window 854x480px, gui 427x240 @ scale 2
$ echo $?
0
$ clientdevbridge status ... | grep -E "gui size|pixel size"
gui size    427x240 @ scale 2
pixel size  854x480
```

- Printed geometry is the **post**-resize geometry and matches `status` exactly.
- Exit code is **0** on success (was 1).
- Validation uses the **new** size, not the old one — proven directly:
  `resize --width 1920 --height 1080 --gui-scale 4` is **accepted** (`window 1920x1080px,
  gui 480x270 @ scale 4`, exit 0) while `--gui-scale 4` at 854x480 is rejected. Scale 4 is only
  legal at the requested size, so the check must be running against it.
- Out-of-range: `--gui-scale 99`, `4`, `-1` at 854x480 all give
  `error: GUI scale must be between 0 (auto) and 2 at 854x480, but was N`, exit **1**, and the
  window is **unchanged** afterwards. `--width 0` and `--width 99999` give
  `error: Window size must be between 64 and 7680 pixels in each dimension, but was 0x480`, exit 1.
  Omitting `--height` gives `error: required option '--height <px>' not specified` plus the usage
  block, exit 1.

Identical on `master-26`.

### 3. `click --at` outside the screen — **FIXED, verified**

```
$ clientdevbridge click --at 9999,9999 --project $P
error: Point 9999,9999 is outside the 427x240 screen (in gui space). Take a fresh
'clientdevbridge snapshot': the coordinates it prints are in GUI space, and the window may have
been resized since the last one.
$ echo $?
1
```

Same for `-5,-5` and for `500,300` (outside the 427-wide GUI). Also correctly covered:
`scroll --at` and `mouse-move`. **Not** covered: `drag` and `tooltip` — see findings A and B.

### 4. `doctor` and the Java-version mismatch — **FIXED, verified**

It is now a `warn`, not an `ok`, and there is a dedicated `Worked around:` section explaining that
nothing has to be done and how to silence it. Matches what `SKILL.md` promises about `warn` lines.

### 5. A `--project` directory that does not exist — **FIXED, verified**

```
$ clientdevbridge status --project /nope/not/here
error: No such directory: /nope/not/here
Check the path passed to --project.
$ echo $?
2
```

Same for `doctor`. Flagged, with the right exit code.

### 6. A client already running for a different project — **PARTIALLY FIXED**

`status` and `stop` are **fixed** and now excellent:

```
$ clientdevbridge status --project $PHASE7/cdb-26lts/e2e/consumer     # while cdb-26 is running
Not running (no session recorded) in .../cdb-26lts/e2e/consumer.

A ClientDevBridge client is on port 25599 (pid 18029 (java)), but it belongs to another project:
  .../cdb-26/e2e/consumer  (Minecraft 26.2, neoforge)
Drive it from there, stop it with: clientdevbridge --project .../cdb-26/e2e/consumer stop
or start this project on a different port with --port <other>.
```

`start` is **not fixed** and still emits the old, wrong message:

```
$ clientdevbridge start --project $PHASE7/cdb-26lts/e2e/consumer      # while cdb-26 is running
error: Port 25599 on 127.0.0.1 is already in use, so the client could not claim it.
An orphaned client still holds it: pid 18029 (java). Stop it with 'kill 18029', or start this one
elsewhere with --port <other>.
exit 2
```

Verified in both directions (26lts running / starting 26, and 26 running / starting 26lts).
`start` is precisely the command a user hits first, so the fix misses its main case.

**Diagnosis** (source read after the fact): `$PHASE7/cli/src/commands/lifecycle.ts:104`
`describeOccupiedPort()` holds the good logic, complete with a comment saying *"`kill <pid>` is
confident, wrong advice about a client they are still using"* — but it is only wired into
`status`/`stop`. `$PHASE7/cli/src/launcher.ts:196-205` builds its own message inline and never
calls it.

**Second-order problem in the same message:** the pid it names is the *java* process, and that
process is in a **different process group** from the one the session records:

```
  PID  PPID  PGID COMMAND
14924     1 14924 /bin/sh /usr/bin/xvfb-run -a -s -screen 0 1280x800x24 ...   <- session pid
15247 14969 14969 /usr/lib/jvm/java-25-openjdk-amd64/bin/java -Dclientdevbridge.enabled=true ...
```

`status` reports `pid 14924`; `start` advises `kill 15247`. Following the advice kills the JVM and
leaves the `xvfb-run` wrapper and its Xvfb behind, which `stop` would have reaped.

---

## 7. New failures found in this run

### A. `drag` does not validate its coordinates — silently destructive (FAIL, both branches, both loaders)

```
$ clientdevbridge drag --from 218,187 --to 9999,9999 --project $P
screen: net.minecraft.client.gui.screens.inventory.CraftingScreen
$ echo $?
0
$ clientdevbridge drag --from 9999,9999 --to 218,187 --project $P
screen: net.minecraft.client.gui.screens.inventory.CraftingScreen
$ echo $?
0
$ clientdevbridge drag --from 240,62 --to -50,-50 --project $P
screen: ...CraftingScreen
$ echo $?
0
```

This is exactly the class of bug that was fixed for `click`. It is worse here, because dragging a
stack out of the GUI window **throws the items on the floor**:

```
$ clientdevbridge inventory --project $P
# selected hotbar slot 0            <- the 5 diamonds are gone; no warning, exit 0
```

Interestingly `drag` *does* error correctly when no screen is open at all
(`error: No screen is open, so there is nothing to deliver dragging to. Open one with 'open-gui'
first.`, exit 1), which makes the missing bounds check look like a plain oversight.

**Diagnosed:** `loader-common/src/main/java/org/cyclops/clientdevbridge/handler/InputHandler.java`
line ~52 calls `Geometry.toGui(from[0], space), ...` directly for the drag path, bypassing the
private `point()` helper at line ~117 that carries the bounds check used by every other input
method. Same file, same line, on both branches.

### B. `tooltip --at` off screen exits 0 *and* corrupts the tracked mouse (FAIL, both branches)

```
$ clientdevbridge tooltip --at 9999,9999 --project $P
No tooltip at 9999,9999 (source: none).
$ echo $?
0
$ clientdevbridge snapshot --project $P | head -2
CraftingScreen  "Crafting"
gui 427x240 @ scale 2, window 854x480px, mouse at 9999,9999
```

Two problems: a nonsense coordinate is reported as "no tooltip here" (indistinguishable from a
legitimate empty spot), and the out-of-range point is then *retained* as the mouse position, so
every following `snapshot` reports an impossible cursor and no hover state.

### C. `compare --region` cannot be applied to an existing golden (FAIL — docs and error message both wrong)

`docs/AGENT_WORKFLOW.md` and `SKILL.md` both present `--region` as the remedy when a scene contains
an animated block, applied at compare time to a golden you already have:

> If your scene needs one, either raise `--threshold`, or compare only the part that holds still:
> `clientdevbridge compare my-scene --region 150,60,140,120`

and `compare`'s own failure hint repeats it: *"...or pass `--region` to compare only the part that
should hold still."* Following that literally always fails:

```
$ clientdevbridge compare golden-scene --region 0,0,60,40 --project $P
Size mismatch: the golden image is 854x480 but the screenshot is 120x80.
Pin the window with `clientdevbridge resize` before comparing, or re-record with --update.
exit 1
```

`--region` is in fact a **property of the golden**, fixed at record time:

```
$ clientdevbridge compare region-scene --region 0,0,60,40 --update   -> writes a 120x80 golden, ok
$ clientdevbridge compare region-scene --region 0,0,60,40            -> matches (0 of 9600), exit 0
$ clientdevbridge compare region-scene                               -> Size mismatch, exit 1
```

So the feature works, but only if `--region` is passed identically on every call including
`--update`. Two things need fixing: the docs (and the failure hint) should say so, and the error
message should say *"this golden was recorded without `--region`; re-record it with
`--update --region ...`"* instead of blaming the window size, which sends the reader to `resize`
for a problem `resize` cannot solve. Reproduced identically on both branches.

### D. `world-reset --template <missing>` destroys the world before validating (FAIL, both branches)

```
$ clientdevbridge world-list --project $P
clientdevbridge

$ clientdevbridge world-reset --template nope --project $P
error: No world template 'nope' at .../clientdevbridge/templates/nope. Commit one there, or drop
--template to generate a fresh superflat world.
$ echo $?
1

$ clientdevbridge world-list --project $P
No worlds yet. Create one with: clientdevbridge world-reset
$ clientdevbridge status --project $P | grep -E "in world|screen"
in world    false
screen      net.minecraft.client.gui.screens.TitleScreen
```

The error message is good but arrives too late: a typo'd template name leaves the agent with no
world, out of the game, at the title screen. Verified on **both** branches.

**Diagnosed:** `handler/WorldHandler.java:35-44` — the chain is
`WorldControl::leave` → `WorldControl.delete(name)` → `WorldControl.copyTemplate(...)`, and the
existence check lives inside `copyTemplate` (`mcadapter/WorldControl.java:123-127`). The check needs
to run before `leave`.

### E. Negative block coordinates are effectively unusable (FAIL, CLI-level, both branches)

```
$ clientdevbridge teleport -10 5 -10 --project $P
error: unknown option '-10'
<usage block>
exit 1

$ clientdevbridge block 0 -500 0 --project $P
error: unknown option '-500'
exit 1
```

The obvious escape does not work either, because everything after `--` becomes positional and
`--project` is swallowed with it:

```
$ clientdevbridge teleport -- -10 5 -10 --project $P
error: No ClientDevBridge session in /home/user/ClientDevBridge.       <- --project ignored
exit 2
```

The only working form puts `--project` **before** the subcommand, which no document shows:

```
$ clientdevbridge --project $P teleport -- -10 5 -10
Player at -10.00, 5.00, -10.00, facing 0/0.
$ clientdevbridge --project $P setblock -- -5 4 -5 minecraft:stone
Changed the block at -5, 4, -5
```

Negative coordinates are routine in Minecraft, every example in the docs uses the trailing
`--project <dir>` form, and `error: unknown option '-10'` gives no hint at all. Affects `teleport`,
`setblock`, `block`, `open-gui`, `inspect-gui` and `look --at`.

### F. `--space pixel` error messages quote GUI numbers labelled as pixels (FAIL, minor, both branches)

```
$ clientdevbridge click --at 5000,5000 --space pixel --project $P
error: Point 2500,2500 is outside the 427x240 screen (in pixel space). ...

$ clientdevbridge mouse-move 900,300 --space pixel --project $P
error: Point 450,150 is outside the 427x240 screen (in pixel space). ...
```

The point has been converted to GUI space and the bounds are the GUI bounds, but the message says
"in pixel space". In pixel space the screen is 854x480 and the point the caller passed was 900,300.
An agent reading this cannot tell which of its numbers is wrong. The functionality itself is
correct: `mouse-move 800,400 --space pixel` lands at gui 400,200.

**Diagnosed:** `handler/InputHandler.java:127` formats `point.x()`/`point.y()` (post-conversion) and
`Geometry.guiWidth()`/`guiHeight()` while interpolating the caller's `space` string.

### G. The outline reports `mouse at 0,0` while simultaneously reporting a hovered slot (minor)

Straight after `inspect-gui` on a fresh screen, every cell prints:

```
gui 427x240 @ scale 2, window 854x480px, mouse at 0,0
container CraftingMenu at (125,37) 176x166, 46 slots (0 filled)
  slot 14 (empty) @(205,121) hovered
```

Slot 14's box is gui (205,121)–(221,137), i.e. the screen centre — and the screenshot confirms it is
drawn with the hover highlight. So the real cursor is at the centre while the outline claims 0,0.
`mouse-move` updates the field correctly, so only the initial value is wrong. Cosmetic, but the
outline is supposed to be the thing you trust when the screenshot is ambiguous.

### H. One-off: a `setblock` block never rendered on `master-26` + Fabric (observed once, not reproduced)

During the 26.2/fabric run the crafting table placed by `setblock 0 4 2` was **invisible** in every
screenshot — only its selection outline was drawn — while `block 0 4 2` answered
`Block{minecraft:crafting_table}` and `inspect-gui` opened a real `CraftingScreen` from it.

Evidence: `$PHASE7/cdb-26/.../diffs/m26-scene_2026-08-30_00-59-59-392-actual.png` (opened: flat
platform, a wireframe cube where the table should be, nothing else) and the accompanying
`...-diff.png`, whose red pixels are confined **exactly** to the block silhouette — 7990 of 409920
pixels, 1.949% — with sky, ground, hotbar and held item identical to the NeoForge golden.

It survived `wait --ticks 100` (`fab26-block-a.png`, still invisible 5 s later) and was only fixed
by `setblock 2 4 2 minecraft:stone`, i.e. by a neighbouring change forcing a chunk-section rebuild
(`fab26-block-c.png`, both blocks then visible).

Three repro attempts failed: a minimal `world-reset / setblock / teleport / wait / screenshot`
(`repro-fab26.png`), a full replay of the original command sequence (`replay-final.png`), and the
same replay on a freshly started client (`fresh-A.png`, `fresh-B.png`). All three rendered the
table correctly.

Reporting it because the failure mode is silent and dangerous for exactly the workflow Phase 4 is
about: `compare --update` in that state records a golden of an empty platform, and every structural
check (`block`, `inspect-gui`, `snapshot`) agrees the block is there. I could not determine whether
this is a bridge issue (a missing client-side chunk-mesh invalidation after a server-side setblock)
or vanilla/Fabric chunk-rebuild scheduling on 26.2.

### I. `doctor --loader fabric` suggests the wrong next command (trivial)

It ends with `Everything checks out. Run: clientdevbridge start` — dropping the `--loader fabric`
that the whole invocation was about. `start` without it would pick the detected loader.

---

## 8. Off-script probes and the quality of the error messages

Everything in this section was deliberately wrong input. With the exceptions filed above, the
messages were consistently good: they name the value received, the value expected, and the next
command.

| Probe | Output | Exit | Judgement |
|---|---|---|---|
| `status` before `start` | `Not running (no session recorded) in <dir>. Start one with: clientdevbridge start` | 0 | good — "not running" is not an error |
| `screenshot` before `start` | `error: No ClientDevBridge session in <dir>. Run 'clientdevbridge start' first (add --project <dir> if the mod project is elsewhere).` | 2 | good |
| second `start`, same project | `error: A ClientDevBridge client is already running for <dir> (pid 14924, port 25599). Run 'clientdevbridge stop' first, or 'clientdevbridge restart' to replace it.` | 2 | good |
| `start` for a *different* project | "orphaned client ... kill 15247" | 2 | **wrong** — finding 6 |
| `clientdevbridge screnshot` | `error: unknown command 'screnshot'  (Did you mean screenshot?)` + full usage | 1 | good |
| `--projekt` | `error: unknown option '--projekt'  (Did you mean --project?)` | 1 | good |
| `--project /nope/not/here` | `error: No such directory: /nope/not/here  Check the path passed to --project.` | 2 | good |
| `click` with no `--at`/`--widget` | `error: click needs either --at x,y or --widget <text-or-path>.` | 1 | good |
| `click --at abc` | `error: --at must be two numbers 'x,y', but was 'abc'.  For example: --at 210,100` | 1 | excellent |
| `click --widget NoSuchWidget` | `error: No widget matches 'NoSuchWidget' on ...CraftingScreen. Run \`clientdevbridge snapshot\` to see the widgets, then pass a label or a /root/children[N] path.` | 1 | excellent |
| `--space nonsense` | `error: Parameter 'space' must be 'gui' or 'pixel', but was 'nonsense'` | 1 | good |
| `key NOT_A_KEY` | `error: Unknown key 'NOT_A_KEY'. Use a GLFW name such as 'GLFW_KEY_E', a single letter or digit, 'F3', a named key like 'ESCAPE', or a raw integer key code.` | 1 | excellent |
| `compare no-such-golden` | `error: There is no golden image for 'no-such-golden' at <path>. Create it with: clientdevbridge compare no-such-golden --update` | 1 | excellent |
| `compare --region 1,2,3` | `error: --region must be four numbers 'x,y,w,h', but was '1,2,3'.  For example: --region 100,80,200,120` | 1 | excellent |
| `world-load nope` | `error: There is no world called 'nope'. Existing worlds: ` | 1 | fine, though the empty list reads oddly |
| `hotswap` without a JDWP port | `error: This client was started without a debug port, so its classes cannot be redefined. Restart with: clientdevbridge restart --jdwp-port 5005` | 2 | excellent |
| `eval "this is not groovy(("` | the Groovy compilation error, verbatim | 1 | good |
| `command notacommand` | `Unknown or incomplete command. See below for error / notacommand<--[HERE]` | 1 | good — vanilla's own message |
| `setblock 0 4 2 minecraft:not_a_block` | `Unknown block type ... <--[HERE]` | 1 | good |
| `setblock` a block that is already there | `Could not set the block` | 1 | correct exit code, thin message |
| `teleport a b c` | `error: Expected three numbers for a block position, but got 'a b c'.` | 1 | good |
| `wait --screen NoSuchScreen --timeout 3000` | `Timed out after 3000 ms waiting for screen (screen is ...CraftingScreen, in world: true).` | 1 | excellent — says what it saw instead |
| `block 0 -500 0` | `error: unknown option '-500'` | 1 | **bad** — finding E |

---

## 9. Gaps the earlier run could not cover

### Fabric — covered, in full

A complete Phase 1–4 scenario was run on `start --loader fabric` on **both** branches, not just one.
Results are in the matrix in §1. Fabric matched NeoForge on every observable: same outline text,
same widget paths, same coordinates, same exit codes, same screenshots. On `master-26-lts` the frame
rendered by the Fabric client matched the golden recorded by the NeoForge client at **0 of 409920
differing pixels**.

Loader-specific observations, none of them behavioural:
- `start` lists ~50 Fabric API modules in its `mods` row versus 4 on NeoForge. Correct, but it makes
  the ready banner about 8 lines of noise.
- Fabric's game dir is `loader-fabric/runs/client`, NeoForge's is `loader-neoforge/run`. Reported
  correctly by `status` in both cases.
- The title screen differs (no NeoForge beta banner, `Minecraft 26.1.2 (Modded)`), which is vanilla.

### `--json`

Checked on `status`, `snapshot`, `tooltip`, `find`, `screenshot`, `inventory`, `compare`, `scroll`.
All produced well-formed JSON with the fields the human output shows plus more (`glRenderer`,
per-slot `hovered`, `pixelsDiff`/`pct`/`threshold`/`diffPath`, full item `components`). `compare
--json` correctly still exits 1 on a mismatch. One inconsistency: `snapshot --json` reported
`"hovered": null` at the top level in a frame whose outline said `slot 10 (empty) ... hovered` —
the top-level field appears to mean "hovered *widget*", which is defensible but undocumented.

### `--region` and `--threshold` on `compare`

`--threshold` verified working (99 % accepted an 8.96 % diff; the default 0.1 % rejected it).
`--region` verified working **only** when recorded and compared with the same rectangle — finding C.

### `snapshot --json`, `type`, `scroll`, `drag`

- `snapshot --json` — works; also `--include-hidden` and `--max-depth`.
- `type` — verified end to end on both branches. Clicked the recipe book's search box by coordinate
  (the docs' prescribed workaround for `bounds unknown` components), typed `diamond`, and the
  screenshots `$PHASE7/cdb-26lts/.../screenshots/typed.png` and
  `$PHASE7/cdb-26/.../screenshots/m26-typed2.png` both show `diamond` in the box with the recipe
  grid visibly filtered down. **Opened and judged: correct.**
- `scroll` — exits 0, returns the screen class and the mouse position, validates coordinates and
  requires `--dy`. The recipe list had no scrollable overflow, so I could not confirm a *visible*
  effect; the plumbing is right.
- `drag` — functionally correct (dragging slot 37 to crafting slot 1 produced
  `carried: minecraft:diamond x5` in the outline, on both branches), but see finding A for its
  missing bounds check.

---

## 10. Friction list

1. **`start`'s port-conflict message is actively misleading** when a second project holds the port,
   and its `kill <pid>` advice targets a process whose group differs from the session's. (finding 6)
2. **Negative coordinates require an undocumented invocation form** — `clientdevbridge --project
   <dir> <cmd> -- -x y z` — and the error you get otherwise (`unknown option '-10'`) points nowhere.
   (finding E)
3. **`--region` at compare time is documented but impossible**, and the error blames the window
   size. An agent following the failure hint verbatim goes in a circle. (finding C)
4. **`world-reset --template <typo>` costs you the world**, and there is no undo. (finding D)
5. **`drag` and `tooltip` silently accept off-screen points** while `click`, `scroll` and
   `mouse-move` reject them; `drag` can throw items away doing so. (findings A, B)
6. `--space pixel` error text mixes GUI numbers with a pixel-space label. (finding F)
7. `mouse at 0,0` in a freshly opened screen contradicts the `hovered` slot in the same outline.
   (finding G)
8. `doctor --loader fabric` suggests `clientdevbridge start` without `--loader fabric`. (finding I)
9. On Fabric, `start`'s `mods` line is ~50 entries of Fabric API. Truncating or counting would keep
   the ready banner scannable.
10. `Could not set the block` (setblock of a block that is already there) is thinner than the rest
    of the error surface — it does not say the position already held that block.
11. `world-load nope` prints `Existing worlds: ` with nothing after it when there are none; the
    `world-list` wording (`No worlds yet. Create one with: ...`) is better and should be reused.
12. Minor: `compare` writes both a `-diff.png` and a `-actual.png` into `.clientdevbridge/diffs/`
    but only prints the diff path. The actual frame is often the more useful one to open, and its
    existence is not mentioned anywhere.

Things that were notably *good* and are worth keeping: the `Did you mean ...?` suggestions on both
commands and options; `wait --timeout` reporting the state it actually saw; `compare`'s failure hint
about animated blocks and lingering chat lines (which is exactly the trap a first-time user falls
into); `doctor`'s `Worked around:` section; the fact that `status` from the wrong project tells you
the right command to run and does not exit non-zero for merely not running.

---

## 11. What I could not verify, and why

- **`--port` for two simultaneous clients.** Suggested by three different error messages, and the
  natural fix for the cross-project conflict, but two Minecraft clients at once was outside what
  this sandbox could be trusted to hold. Untested.
- **`hotswap`.** Only its no-JDWP guard was exercised. No client was started with `--jdwp-port`, so
  neither `--baseline`, an actual method-body redefinition, nor the `pending` reporting was tested.
- **`restart`.** Not run; `stop` + `start` was used throughout so that each `start` could be timed.
- **`world-reset --template <valid>`, `--setup`, `world-load`, `world-leave`.** No template exists
  in the fixture, so only the failure paths were exercised.
- **`--headed`.** No `$DISPLAY` in this environment.
- **A real GPU renderer.** Every golden here is under `golden/llvmpipe/`. The claim that goldens are
  keyed by renderer is untested against a second renderer.
- **Cold `start`.** All four launches were warm (31–45 s). The documented 2–4 min cold path,
  including Gradle resolving Minecraft, was not exercised.
- **`master-1.21-lts`.** Out of scope for this run, but the README claims one CLI drives it too.
- **Flopper.** Unavailable (private Maven credentials). Everything above is against
  `e2e/consumer`, which contains no custom GUI — so the outline was only ever exercised on vanilla
  screens. A mod screen with real buttons, edit boxes and a custom menu is the case the tool exists
  for and it remains untested here.
- **Finding H's root cause.** Observed once with screenshot evidence, then not reproduced in three
  attempts, so I could not determine whether it is a bridge defect or vanilla chunk-rebuild timing.
- **`compare --pixel-threshold`, `screenshot --scale`, `screenshot --after-ticks`,
  `hold-key`, `logs --level` beyond `warn`, `snapshot --max-depth`, `find --type` beyond one case.**
  Exercised lightly or not at all.
