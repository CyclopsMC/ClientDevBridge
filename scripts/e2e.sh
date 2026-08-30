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

log "Phase 4: eval"
"$CLI" --project "$CONSUMER" eval "player.getY()" | grep -qE '^[0-9]' || fail "eval returned nothing usable"
"$CLI" --project "$CONSUMER" wait --expr "mc.level != null" --timeout 3000 || fail "wait --expr failed"

log "logs"
"$CLI" --project "$CONSUMER" logs --lines 5 --level warn >/dev/null

log "stop"
"$CLI" --project "$CONSUMER" stop
"$CLI" --project "$CONSUMER" status | grep -q "Not running" || fail "status should report not running after stop"

log "PASSED ($LOADER against $(basename "$CONSUMER"))"
