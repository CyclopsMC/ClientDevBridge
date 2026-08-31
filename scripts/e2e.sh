#!/usr/bin/env bash
#
# End-to-end test: build the mod, launch a real dev client, and drive it through the CLI.
#
# The consumer under test is, in order of preference:
#   1. $CDB_CONSUMER_DIR, if set
#   2. a checkout of CyclopsMC/Flopper on the branch matching this one, when the CyclopsMC
#      GitHub Packages Maven is reachable (Flopper needs CyclopsCore, which lives there)
#   3. e2e/consumer, the minimal fixture in this repository
#
# Flopper is the better test bed — it is a real Cyclops multiloader mod, and its in-world fluid
# rendering gives golden screenshots something meaningful to catch. It cannot be built without
# package credentials, though, so the fixture is what keeps this runnable everywhere.
#
# Usage: scripts/e2e.sh [neoforge|fabric]

set -euo pipefail

LOADER="${1:-neoforge}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FLOPPER_BRANCH="$(git -C "$ROOT" rev-parse --abbrev-ref HEAD)"
FLOPPER_DIR="$ROOT/e2e/flopper"
PORT="${CDB_PORT:-25599}"

cd "$ROOT"

log() { printf '\n=== %s ===\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }

# True when the CyclopsMC packages Maven answers for a CyclopsCore artifact.
cyclopscore_reachable() {
  local url="https://maven.pkg.github.com/CyclopsMC/packages/org/cyclops/cyclopscore/"
  local code
  code="$(curl -s -o /dev/null -w '%{http_code}' -L --max-time 20 \
    -u "${MAVEN_USERNAME:-x}:${MAVEN_KEY:-${GITHUB_TOKEN:-}}" "$url" 2>/dev/null || echo 000)"
  [[ "$code" != "401" && "$code" != "403" && "$code" != "000" ]]
}

resolve_consumer() {
  if [[ -n "${CDB_CONSUMER_DIR:-}" ]]; then
    echo "$CDB_CONSUMER_DIR"
    return
  fi
  # An existing checkout is only usable if CyclopsCore is still resolvable: a stale clone left
  # behind by a run that had credentials would otherwise poison every later run without them.
  if [[ -d "$FLOPPER_DIR" ]] && cyclopscore_reachable; then
    git -C "$FLOPPER_DIR" fetch --depth 1 origin "$FLOPPER_BRANCH" >/dev/null 2>&1 || true
    git -C "$FLOPPER_DIR" checkout -q "$FLOPPER_BRANCH" 2>/dev/null || true
    echo "$FLOPPER_DIR"
    return
  fi
  # Only try Flopper when CyclopsCore is genuinely resolvable. Cloning Flopper always works -- it
  # is a public repository -- but building it needs CyclopsCore from the CyclopsMC GitHub Packages
  # Maven, which requires credentials. Probing the artifact itself is the only reliable check:
  # having a token in the environment says nothing about whether it can read packages.
  if cyclopscore_reachable && \
     git clone --depth 1 --branch "$FLOPPER_BRANCH" https://github.com/CyclopsMC/Flopper "$FLOPPER_DIR" >/dev/null 2>&1; then
    echo "$FLOPPER_DIR"
    return
  fi
  echo "$ROOT/e2e/consumer"
}

CONSUMER="$(resolve_consumer)"
CLI="${CDB_CLI:-clientdevbridge}"

log "Configuration"
echo "loader:   $LOADER"
echo "consumer: $CONSUMER"
echo "branch:   $FLOPPER_BRANCH"
echo "cli:      $CLI"

log "Publishing the mod to mavenLocal"
./gradlew publishToMavenLocal --no-daemon --console=plain -q

cleanup() {
  "$CLI" --project "$CONSUMER" stop >/dev/null 2>&1 || true
}
trap cleanup EXIT

# A client left behind by an earlier run would make `start` refuse; a run should be able to
# begin from any state.
"$CLI" --project "$CONSUMER" stop >/dev/null 2>&1 || true

log "doctor"
"$CLI" --project "$CONSUMER" doctor --loader "$LOADER" --no-network || fail "doctor reported a problem"

log "start ($LOADER, headless)"
"$CLI" --project "$CONSUMER" start --loader "$LOADER" --port "$PORT" --timeout 900 \
  || fail "the client did not come up"

log "status"
"$CLI" --project "$CONSUMER" status

log "Phase 1: screenshot the title screen"
TITLE_PNG="$("$CLI" --project "$CONSUMER" screenshot --name e2e-title --quiet)"
[[ -f "$TITLE_PNG" ]] || fail "no screenshot was written"
# A single flat colour means nothing was rendered; a real title screen has many. Counting them
# needs a PNG decoder, and the only one around is the CLI's own `pngjs` -- this repository has no
# node_modules of its own. Node resolves from the CLI's installation directory, so point it there;
# when the CLI is a wrapper script or a checkout that resolution can fail, and the run falls back
# to the file size, which separates the two cases just as well: a flat 854x480 image deflates to
# well under a kilobyte, a rendered title screen to hundreds of them.
CLI_DIR="$(dirname "$(readlink -f "$(command -v "$CLI")" 2>/dev/null || echo "$CLI")")"
COLOURS="$(cd "$CLI_DIR" && node -e '
const fs = require("fs");
const { PNG } = require("pngjs");
const png = PNG.sync.read(fs.readFileSync(process.argv[1]));
const seen = new Set();
for (let i = 0; i < png.data.length; i += 4) {
  seen.add((png.data[i] << 16) | (png.data[i + 1] << 8) | png.data[i + 2]);
  if (seen.size > 200) break;
}
console.log(seen.size);
' "$TITLE_PNG" 2>/dev/null || echo "")"
if [[ -n "$COLOURS" ]]; then
  [[ "$COLOURS" -gt 50 ]] || fail "the title screenshot has only $COLOURS distinct colours, so nothing rendered"
  echo "title screen: $TITLE_PNG ($COLOURS+ distinct colours)"
else
  BYTES="$(wc -c < "$TITLE_PNG")"
  [[ "$BYTES" -gt 20000 ]] || fail "the title screenshot is only $BYTES bytes, so nothing rendered"
  echo "title screen: $TITLE_PNG ($BYTES bytes; no PNG decoder available, so size was checked instead)"
fi

log "Phase 4: pin the window for reproducible screenshots"
"$CLI" --project "$CONSUMER" resize --width 854 --height 480 --gui-scale 2 | tee /tmp/cdb-resize.txt
grep -q "854x480px" /tmp/cdb-resize.txt || fail "the window did not resize to 854x480"
grep -q "scale 2" /tmp/cdb-resize.txt || fail "the GUI scale was not pinned to 2"

log "Phase 2: create a world and place blocks"
"$CLI" --project "$CONSUMER" world-reset
"$CLI" --project "$CONSUMER" setblock 0 4 2 minecraft:crafting_table
"$CLI" --project "$CONSUMER" setblock 2 4 2 minecraft:chest
"$CLI" --project "$CONSUMER" command "setblock -2 4 2 minecraft:lava"
"$CLI" --project "$CONSUMER" give minecraft:diamond 5
"$CLI" --project "$CONSUMER" block 0 4 2 | grep -q crafting_table || fail "the crafting table was not placed"

log "Phase 3: open and inspect the crafting GUI"
"$CLI" --project "$CONSUMER" open-gui 0 4 2
"$CLI" --project "$CONSUMER" wait --screen CraftingScreen --timeout 5000 || fail "the crafting screen did not open"
"$CLI" --project "$CONSUMER" snapshot | tee /tmp/cdb-snapshot.txt
grep -q "CraftingScreen" /tmp/cdb-snapshot.txt || fail "the snapshot does not report a CraftingScreen"
grep -q "46 slots" /tmp/cdb-snapshot.txt || fail "the snapshot does not report the 46 CraftingMenu slots"
grep -q "minecraft:diamond x5" /tmp/cdb-snapshot.txt || fail "the snapshot does not show the given diamonds"

log "Phase 3: tooltips"
"$CLI" --project "$CONSUMER" tooltip --at "$(grep -o 'minecraft:diamond x5 @([0-9]*,[0-9]*)' /tmp/cdb-snapshot.txt | grep -o '[0-9]*,[0-9]*')" \
  | grep -qi diamond || fail "no diamond tooltip"

log "Phase 3: click a widget by path and confirm the screen reacted"
BEFORE_LEFT="$(grep -o 'at ([0-9]*,[0-9]*)' /tmp/cdb-snapshot.txt | head -1)"
"$CLI" --project "$CONSUMER" click --widget "/root/children[0]"
"$CLI" --project "$CONSUMER" snapshot > /tmp/cdb-snapshot2.txt
AFTER_LEFT="$(grep -o 'at ([0-9]*,[0-9]*)' /tmp/cdb-snapshot2.txt | head -1)"
[[ "$BEFORE_LEFT" != "$AFTER_LEFT" ]] || fail "clicking the recipe-book button changed nothing (was $BEFORE_LEFT)"
echo "container moved $BEFORE_LEFT -> $AFTER_LEFT"

log "Phase 3: inspect-gui composite"
"$CLI" --project "$CONSUMER" close-screen
"$CLI" --project "$CONSUMER" inspect-gui 2 4 2 --name e2e-chest | tail -3

log "Phase 4: golden screenshot of the world"
"$CLI" --project "$CONSUMER" close-screen
# Lava's texture animates every frame, and no game rule stops it, so it is taken out of the golden
# scene entirely. Clearing the whole volume rather than the one block matters: lava spreads, so
# removing where it was placed leaves the flow behind. A scene that genuinely needs an animated
# block wants --threshold or a --region that excludes it; see docs/AGENT_WORKFLOW.md.
"$CLI" --project "$CONSUMER" command "fill -8 4 -8 8 8 8 minecraft:air"
"$CLI" --project "$CONSUMER" setblock 0 4 2 minecraft:crafting_table
"$CLI" --project "$CONSUMER" setblock 2 4 2 minecraft:chest
"$CLI" --project "$CONSUMER" wait --ticks 20
"$CLI" --project "$CONSUMER" teleport 0 5 6 --yaw 180 --pitch 20
"$CLI" --project "$CONSUMER" wait --ticks 10
"$CLI" --project "$CONSUMER" compare e2e-scene --update
"$CLI" --project "$CONSUMER" compare e2e-scene || fail "a golden image did not match itself"
# And confirm the comparison can actually fail, so a passing run means something.
"$CLI" --project "$CONSUMER" look --yaw 0 --pitch 0
"$CLI" --project "$CONSUMER" wait --ticks 5
if "$CLI" --project "$CONSUMER" compare e2e-scene >/dev/null 2>&1; then
  fail "the comparison passed after the camera moved, so it proves nothing"
fi
echo "compare correctly detected a changed scene"

log "Phase 3: aiming an interaction at a point on a block"
# A chiseled bookshelf routes a right-click to one of six slots by where on its front face the hit
# landed, which is the same mechanism a multipart block uses to pick a part -- and vanilla, so this
# runs on every branch with no extra mod. Without --at every click would land dead centre and only
# ever reach one slot.
"$CLI" --project "$CONSUMER" setblock 4 4 2 "minecraft:chiseled_bookshelf[facing=south]"
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:book 6" >/dev/null
"$CLI" --project "$CONSUMER" use 4 4 2 --at 4.2,4.8,3
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:book 6" >/dev/null
"$CLI" --project "$CONSUMER" use 4 4 2 --at 4.8,4.2,3
"$CLI" --project "$CONSUMER" block 4 4 2 | tee /tmp/cdb-shelf.txt
TOP="$(grep -oE 'slot_[012]_occupied=true' /tmp/cdb-shelf.txt | wc -l)"
BOTTOM="$(grep -oE 'slot_[345]_occupied=true' /tmp/cdb-shelf.txt | wc -l)"
[[ "$TOP" -ge 1 && "$BOTTOM" -ge 1 ]] \
  || fail "aiming at two different points filled $TOP top and $BOTTOM bottom slots; --at is not routing the click"
echo "two different aim points reached two different rows"

log "Phase 3: aiming at a face places against that face"
# Which face a placement lands on comes from the hit result, so this is the half of aiming that a
# multipart mod uses to choose which side of a cable a part goes on.
"$CLI" --project "$CONSUMER" setblock 6 4 2 minecraft:stone
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:torch 4" >/dev/null
"$CLI" --project "$CONSUMER" use 6 4 2 --face north
"$CLI" --project "$CONSUMER" block 6 4 1 | grep -q "minecraft:.*torch" \
  || fail "--face north did not place the torch on the north side"
# Standing on the right side is the half of aiming that a raytrace-resolved block depends on, and
# it is not visible in the placement alone -- so assert it directly rather than infer it.
"$CLI" --project "$CONSUMER" eval "player.getZ() < 2 && player.getXRot() > -45" | grep -q true \
  || fail "approach did not put the player north of the block, looking at it"
echo "the torch landed north, and the player stood north to put it there"
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:air" >/dev/null

log "Phase 3: shift-clicking a slot moves the stack"
# A screen decides what a click meant from the real keyboard state, which synthetic input cannot
# reach, so the operation is named instead. This is the assertion that would have caught the gap:
# the item has to leave the player inventory and arrive in the chest, in one command.
"$CLI" --project "$CONSUMER" setblock 8 4 2 minecraft:chest
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:diamond 5" >/dev/null
"$CLI" --project "$CONSUMER" open-gui 8 4 2 >/dev/null
# The hotbar slot the held stack is in, as the container numbers it, read rather than assumed.
HELD_SLOT="$("$CLI" --project "$CONSUMER" --json snapshot \
  | python3 -c "import json,sys; print(next(s['index'] for s in json.load(sys.stdin)['container']['slots'] if s['item'] == 'minecraft:diamond'))")"
[[ -n "$HELD_SLOT" ]] || fail "the diamonds are not in the chest screen's slot list"
"$CLI" --project "$CONSUMER" slot-click "$HELD_SLOT" --type quick_move
MOVED="$("$CLI" --project "$CONSUMER" --json snapshot \
  | python3 -c "
import json,sys
# Looked up by 'index', not by position: --json omits the empty slots, so the array is not the
# dense grid it used to accidentally be.
s = {x['index']: x for x in json.load(sys.stdin)['container']['slots']}
print(s.get($HELD_SLOT, {}).get('item'), s.get(0, {}).get('item'))")"
[[ "$MOVED" == "None minecraft:diamond" ]] \
  || fail "quick_move left the slots as '$MOVED'; the stack did not move into the chest"
echo "quick_move moved the stack out of the inventory and into the container"
# --json omits the empty slots, which is most of a container: the same screen is an order of
# magnitude smaller than it used to be, and --include-empty still has every rectangle.
LEAN="$("$CLI" --project "$CONSUMER" --json snapshot | wc -c)"
FULL="$("$CLI" --project "$CONSUMER" --json snapshot --include-empty | wc -c)"
[[ "$LEAN" -lt "$FULL" ]] || fail "--json is not smaller than --include-empty ($LEAN vs $FULL)"
"$CLI" --project "$CONSUMER" --json snapshot \
  | python3 -c "import json,sys; c=json.load(sys.stdin)['container']; sys.exit(0 if c['slotCount'] > len(c['slots']) else 1)" \
  || fail "slotCount should say how many slots there are when the empty ones are omitted"
echo "the lean snapshot is $LEAN B against $FULL B with every empty slot"
"$CLI" --project "$CONSUMER" close-screen >/dev/null

log "Phase 3: using the item in your hand"
# Every other use command takes a block position, so a mod whose entry point is an item had no
# command at all. A writable book is the vanilla item that opens a screen on use.
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:writable_book 1" >/dev/null
# Aimed at the sky: a right-click at a block interacts with the block and never reaches the item,
# which is what a player gets and the likeliest reason an item looks like it did nothing.
"$CLI" --project "$CONSUMER" look --pitch -90 >/dev/null
"$CLI" --project "$CONSUMER" use-item --wait-screen | tee /tmp/cdb-use-item.txt
grep -q "BookEditScreen" /tmp/cdb-use-item.txt || fail "use-item did not open the book"
# The other side of --wait-screen: an item that opens nothing has to fail, after waiting, rather
# than passing. (No vanilla item opens a *server* container on use, so that half -- the one that
# made the flag look broken against Everlasting Abilities -- is only covered by hand.)
"$CLI" --project "$CONSUMER" close-screen >/dev/null
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:stone 1" >/dev/null
QUIET="$("$CLI" --project "$CONSUMER" use-item --wait-screen 2>&1 || true)"
grep -q "No screen opened" <<<"$QUIET" || fail "--wait-screen passed for an item that opens nothing: $QUIET"
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:writable_book 1" >/dev/null
"$CLI" --project "$CONSUMER" close-screen >/dev/null
# open-gui with no coordinates is the same thing, so the wait-for-screen composite works for items.
"$CLI" --project "$CONSUMER" open-gui | grep -q "BookEditScreen" || fail "open-gui with no block did not use the held item"
"$CLI" --project "$CONSUMER" close-screen >/dev/null
# And the in-world click has to report the screen it opened. It queues a key binding processed on
# the next tick, and reading before that reported 'screen: none' at the moment it opened one.
"$CLI" --project "$CONSUMER" click --at 213,120 --button 1 | grep -q "BookEditScreen" \
  || fail "an in-world click still reports the state before the game acted on it"
echo "an item opens its screen, by three routes, and each says so"
# And a use aimed at a block says why the item was not reached, rather than reporting nothing.
"$CLI" --project "$CONSUMER" close-screen >/dev/null
"$CLI" --project "$CONSUMER" look --at 8,4,2 >/dev/null
AIMED="$("$CLI" --project "$CONSUMER" use-item 2>&1 || true)"
grep -q "aimed at block" <<<"$AIMED" || fail "use-item did not report what took the click: $AIMED"
echo "a use aimed at a block says so"
"$CLI" --project "$CONSUMER" close-screen >/dev/null
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:air" >/dev/null

log "Phase 3: a container item says what is inside it"
# The components field is a toString, which for a container is unreadable -- so a full shulker box
# and an empty one used to describe identically. This is the item counterpart of BlockExtractors.
"$CLI" --project "$CONSUMER" command 'give @s minecraft:shulker_box[minecraft:container=[{slot:0,item:{id:"minecraft:diamond",count:3}}]] 1' >/dev/null
INSIDE="$("$CLI" --project "$CONSUMER" --json inventory \
  | python3 -c "import json,sys; print(next((json.dumps(s.get('details')) for s in json.load(sys.stdin)['slots'] if s['item'] == 'minecraft:shulker_box'), 'missing'))")"
grep -q "minecraft:diamond x3" <<<"$INSIDE" \
  || fail "the shulker box's contents are not described: $INSIDE"
grep -q '"places": "minecraft:shulker_box"' <<<"$INSIDE" \
  || fail "the block a BlockItem places is not described: $INSIDE"
echo "a container item reports its contents"

log "Phase 3: mining a block in survival, and picking up what it drops"
# The whole chain a player goes through, and the one that had no commands at all: attack is bound
# to a mouse button, so holding it -- which is how every block in the game is broken -- could not
# be expressed. Nor could holding use, which is eating, drinking, drawing a bow and shields.
# Into the hand, not just the inventory: `give` finds a free slot and leaves the selection alone,
# and mining cobblestone bare-handed works and drops nothing.
"$CLI" --project "$CONSUMER" command "item replace entity @s weapon.mainhand with minecraft:diamond_pickaxe 1" >/dev/null
"$CLI" --project "$CONSUMER" setblock 0 4 2 minecraft:cobblestone >/dev/null
"$CLI" --project "$CONSUMER" command "gamemode survival" >/dev/null
"$CLI" --project "$CONSUMER" teleport 0 4 0 >/dev/null
"$CLI" --project "$CONSUMER" break 0 4 2 | tee /tmp/cdb-break.txt
# Either outcome counts, because which one happens is a race the caller does not control: a drop
# becomes collectable ten ticks after it spawns, which is exactly the settle the mod waits out, so
# mining within arm's reach ends with the item either on the ground or already in hand. Demanding
# the first is what failed on CI while passing here.
grep -qE "(dropped|picked up) minecraft:cobblestone" /tmp/cdb-break.txt \
  || fail "breaking the cobblestone produced neither a drop nor a pickup"
# The tick count is what says this was mining rather than the block being removed: a diamond
# pickaxe takes a handful of ticks on cobblestone, and zero would mean something else happened.
BROKE_IN="$(grep -oE 'in [0-9]+ ticks' /tmp/cdb-break.txt | grep -oE '[0-9]+')"
[[ "$BROKE_IN" -ge 2 ]] || fail "the block broke in $BROKE_IN ticks, which is not mining"
# And a diamond pickaxe is fast. Bare-handed cobblestone takes about two hundred ticks and drops
# nothing, so a large number here means the tool never reached the player's hand.
[[ "$BROKE_IN" -le 60 ]] || fail "$BROKE_IN ticks is bare-handed; the pickaxe was not held"
# The drop is thrown, so it lands a block or two away -- which is why its position is reported.
# When it was collected during the settle there is no position and nothing to walk to, and the
# assertion that matters -- the cobblestone reached the player -- is the same either way.
DROP_AT="$(grep -oE 'at [-0-9.]+, [-0-9.]+, [-0-9.]+' /tmp/cdb-break.txt | sed 's/at //')"
if [[ -n "$DROP_AT" ]]; then
  "$CLI" --project "$CONSUMER" walk-to "$(cut -d, -f1 <<<"$DROP_AT")" "$(cut -d, -f3 <<<"$DROP_AT")"
  HOW="walked to the drop and picked it up"
else
  HOW="picked the drop up where it stood"
fi
"$CLI" --project "$CONSUMER" --json inventory \
  | python3 -c "import json,sys; sys.exit(0 if any(s['item'] == 'minecraft:cobblestone' for s in json.load(sys.stdin)['slots']) else 1)" \
  || fail "the cobblestone never reached the player's inventory"
echo "mined it in $BROKE_IN ticks, $HOW"
# broken and blockAfter describe the same fact and must be read from the same moment: broken used
# to come from the client's prediction ten ticks before blockAfter, which let a reply say it broke
# a block its own blockAfter still named.
"$CLI" --project "$CONSUMER" setblock 0 4 2 minecraft:bedrock >/dev/null
"$CLI" --project "$CONSUMER" --json break 0 4 2 --timeout-ticks 40 \
  | python3 -c "
import json,sys
d = json.load(sys.stdin)
gone = 'air' in d['blockAfter']
sys.exit(0 if d['broken'] == gone and not d['broken'] else 1)" \
  || fail "break disagreed with its own blockAfter on an unbreakable block"
echo "break agrees with the block it reports"

log "Phase 4: the registry is reachable from a script"
# "What does this mod register" is the first question about an unfamiliar mod, and naming
# BuiltInRegistries in a script throws -- the class loader wall dev exists to remove.
"$CLI" --project "$CONSUMER" registry namespaces | grep -qx "minecraft" \
  || fail "registry namespaces did not list minecraft"
"$CLI" --project "$CONSUMER" registry blocks minecraft --filter redstone --limit 5 | grep -q "minecraft:redstone" \
  || fail "registry blocks did not find the redstone blocks"
echo "the registries answer for themselves"

log "Phase 3: hold-key reaches the mouse bindings"
# The cause underneath the above: Keys answered a keyboard code, and attack is key.mouse.left.
"$CLI" --project "$CONSUMER" setblock 0 4 2 minecraft:cobblestone >/dev/null
"$CLI" --project "$CONSUMER" teleport 0 4 0 >/dev/null
"$CLI" --project "$CONSUMER" look --at 0,4,2 >/dev/null
"$CLI" --project "$CONSUMER" hold-key ATTACK --ticks 20 >/dev/null
"$CLI" --project "$CONSUMER" block 0 4 2 | grep -q "minecraft:air" \
  || fail "holding ATTACK did not mine the block"
"$CLI" --project "$CONSUMER" hold-key MOUSE_LEFT --ticks 2 >/dev/null || fail "MOUSE_LEFT is not a held input"
"$CLI" --project "$CONSUMER" hold-key USE --ticks 2 >/dev/null || fail "USE is not a held input"
echo "ATTACK, USE and the mouse buttons can all be held"
"$CLI" --project "$CONSUMER" command "gamemode creative" >/dev/null

log "Phase 2: a teleport reports where the player stays, not where they were dropped"
# The arrival condition used to be satisfied while the player was still falling, so the reply
# described a position they held for one tick and every screenshot after it was of somewhere else.
"$CLI" --project "$CONSUMER" teleport 0 12 0 | tee /tmp/cdb-tp.txt
LANDED="$(grep -oE 'Player at [-0-9.]+, [-0-9.]+' /tmp/cdb-tp.txt | grep -oE '[-0-9.]+$')"
"$CLI" --project "$CONSUMER" eval "Math.abs(player.getY() - $LANDED) < 0.5" | grep -q true \
  || fail "teleport reported y=$LANDED but the player is somewhere else a moment later"
echo "the reported position is the one the player keeps"

log "Phase 4: eval"
"$CLI" --project "$CONSUMER" eval "player.getY()" | grep -qE '^[0-9]' || fail "eval returned nothing usable"
"$CLI" --project "$CONSUMER" wait --expr "mc.level != null" --timeout 3000 || fail "wait --expr failed"
# dev builds the game objects a script cannot construct for itself, and reads the block properties
# that otherwise need a game class named. Both are the class loader boundary, so both are worth a check.
"$CLI" --project "$CONSUMER" eval "dev.blockId(8, 4, 2)" | grep -q "minecraft:chest" \
  || fail "dev.blockId did not see the chest"
"$CLI" --project "$CONSUMER" eval "dev.prop(8, 4, 2, 'facing')" | grep -qE 'north|south|east|west' \
  || fail "dev.prop did not read the chest's facing"
# Captured rather than piped: the CLI exits non-zero on a script error, and under `pipefail` that
# fails the pipeline however well grep matched.
MISSING="$("$CLI" --project "$CONSUMER" eval "dev.prop(8, 4, 2, 'nonesuch')" 2>&1 || true)"
grep -q "It has:" <<<"$MISSING" \
  || fail "dev.prop on a missing property should list the ones that exist, but said: $MISSING"

# A Boolean and the string "false" must not read the same, since telling them apart is the whole
# reason a wait expression compares against the right one.
"$CLI" --project "$CONSUMER" eval "'false'" | grep -q '"false"' \
  || fail "eval should quote a String so it cannot be mistaken for a Boolean"
"$CLI" --project "$CONSUMER" eval "1 == 2" | grep -qx "false" \
  || fail "eval should print a Boolean unquoted"

# A wait on an expression that is well-formed and simply never true used to report the screen and
# the world, which say nothing about it -- so a false expression, a throwing one and an unbound
# name all looked identical. The one that cost a cold start six minutes is the string comparison.
STALLED="$("$CLI" --project "$CONSUMER" wait --expr "dev.prop(8, 4, 2, 'type') == 'true'" --timeout 2000 2>&1 || true)"
grep -q "was evaluated" <<<"$STALLED" \
  || fail "a timed-out wait --expr should say how often it ran, but said: $STALLED"
grep -q "answered false every time" <<<"$STALLED" \
  || fail "a timed-out wait --expr should say what it answered, but said: $STALLED"
grep -q "typed value" <<<"$STALLED" \
  || fail "comparing dev.prop against a quoted string should be called out, but said: $STALLED"
# A throwing expression still fails at once rather than waiting out the timeout, which is what
# makes the message above safe to claim the expression is well-formed.
THROWS="$("$CLI" --project "$CONSUMER" wait --expr "dev.nosuchmethod()" --timeout 2000 2>&1 || true)"
grep -q "Groovy" <<<"$THROWS" \
  || fail "a failing script should name the language it is, but said: $THROWS"
echo "a wait that times out says what the expression actually did"

log "Phase 5: hotbar selection, the screen in focus after a close, and a region echo"
# Selecting a hotbar slot: the only way to hold a second item, since `give` fills the first free
# slot and everything that places, uses or mines acts on the selected one.
"$CLI" --project "$CONSUMER" teleport 0 4 0 >/dev/null
"$CLI" --project "$CONSUMER" command "clear" >/dev/null
"$CLI" --project "$CONSUMER" give minecraft:diamond_pickaxe 1 >/dev/null
"$CLI" --project "$CONSUMER" give minecraft:cobblestone 8 >/dev/null
"$CLI" --project "$CONSUMER" hold 1 | grep -q "minecraft:cobblestone" \
  || fail "hold 1 did not report the cobblestone in the second hotbar slot"
"$CLI" --project "$CONSUMER" inventory | grep -qE '^> \[ 1\]' || fail "hold 1 did not move the selection"
# The number row through the key path. Named, not typed as a digit: a bare "1" parses as the raw
# key code 1, which is bound to nothing -- which is the whole reason the HOTBAR_n names exist.
"$CLI" --project "$CONSUMER" key HOTBAR_1 >/dev/null
"$CLI" --project "$CONSUMER" wait --ticks 2 >/dev/null
"$CLI" --project "$CONSUMER" inventory | grep -qE '^> \[ 0\]' || fail "key HOTBAR_1 did not select hotbar slot 0"
UNBOUND="$("$CLI" --project "$CONSUMER" key 1 2>&1 || true)"
grep -q "HOTBAR_3" <<<"$UNBOUND" \
  || fail "an unbound in-world key should point at the action names, but said: $UNBOUND"
BAD_SLOT="$("$CLI" --project "$CONSUMER" hold 9 2>&1 || true)"
grep -q "0-8" <<<"$BAD_SLOT" || fail "hold should reject a slot outside the hotbar, but said: $BAD_SLOT"

# Giving an item while a container screen is open: reported as closing the screen, so it is pinned
# either way -- whichever it does, a change to it should be a deliberate one.
"$CLI" --project "$CONSUMER" inspect-gui 8 4 2 >/dev/null
"$CLI" --project "$CONSUMER" give minecraft:stone 1 >/dev/null
GIVE_SCREEN="$("$CLI" --project "$CONSUMER" status)"
grep -qi "ContainerScreen" <<<"$GIVE_SCREEN" \
  || fail "give closed the open container screen; status said: $GIVE_SCREEN"
echo "give leaves an open container screen alone"

# Closing reports what is in focus afterwards, which is not always nothing.
"$CLI" --project "$CONSUMER" close-screen | grep -qi "world has input" \
  || fail "close-screen should say the world has input once nothing is in focus"

# A gui-space region comes back as a pixel-sized image, so the echo is the only way to tell a crop
# that landed on the widget from one that missed it by a gui scale factor.
"$CLI" --project "$CONSUMER" screenshot --region 10,10,40,20 --name region-echo | grep -q "cropped to gui 10,10,40,20" \
  || fail "screenshot --region did not echo the gui rectangle it captured"

log "Phase 5b: entity NBT, component serialisation and the cursor pin"
# block --nbt for things that are not blocks. The data is server-side, so a client-only read would
# answer confidently and wrongly.
"$CLI" --project "$CONSUMER" entity @s Pos | grep -qE '^\[' \
  || fail "entity @s Pos should answer the player's position list"
BADPATH="$("$CLI" --project "$CONSUMER" entity @s nosuchpath 2>&1 || true)"
grep -qiE "no|found|error" <<<"$BADPATH" \
  || fail "entity with a bad path should say so, but said: $BADPATH"

# A mod's own data component used to render as a class name and an identity hash. Vanilla components
# go through the same path, so a vanilla one with structure proves the codec is being used: a
# toString of the map would not contain the component's own field names.
"$CLI" --project "$CONSUMER" command "clear" >/dev/null
"$CLI" --project "$CONSUMER" give 'minecraft:written_book[minecraft:written_book_content={title:"T",author:"A",pages:[{raw:"hello"}]}]' 1 >/dev/null
"$CLI" --project "$CONSUMER" --json inventory \
  | python3 -c "import json,sys; d=json.load(sys.stdin); c=''.join(str(s.get('components','')) for s in d['slots']); sys.exit(0 if 'hello' in c else 1)" \
  || fail "components should serialise through their codec, so the book's own text is visible"
echo "components carry their real contents"

# The cursor changes what is rendered, so it has to be pinnable at capture time.
"$CLI" --project "$CONSUMER" screenshot --mouse 213,120 --name cursor-pinned >/dev/null \
  || fail "screenshot --mouse should park the cursor and capture"
OFFSCREEN="$("$CLI" --project "$CONSUMER" screenshot --mouse 9999,9999 2>&1 || true)"
grep -qi "outside\|off.screen\|must be" <<<"$OFFSCREEN" \
  || fail "screenshot --mouse off screen should be refused, but said: $OFFSCREEN"
echo "the cursor can be pinned before a capture"

log "logs"
"$CLI" --project "$CONSUMER" logs --lines 5 --level warn >/dev/null

log "stop"
"$CLI" --project "$CONSUMER" stop
"$CLI" --project "$CONSUMER" status | grep -q "Not running" || fail "status should report not running after stop"

log "PASSED ($LOADER against $(basename "$CONSUMER"))"
