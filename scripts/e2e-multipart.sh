#!/usr/bin/env bash
#
# End-to-end test against a real multipart mod: Integrated Dynamics.
#
# Cables carry parts on their sides, and a part's GUI is reached by right-clicking that side. Which
# side you hit is not decided by the hit result -- CyclopsCore's VoxelShapeComponents re-raytraces
# from the player's eye -- so this is the case that `screen.open`/`world.use` aiming exists for, and
# nothing in the vanilla-only suite exercises it end to end.
#
# It is not part of scripts/e2e.sh because it downloads three jars from Modrinth: the main suite
# stays runnable with no network beyond what a Gradle build already needs.
#
# Usage: scripts/e2e-multipart.sh
#   CDB_CLI          the CLI to drive with (default: clientdevbridge)
#   CDB_WORK         where to build the throwaway consumer (default: a temp directory)

set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLI="${CDB_CLI:-clientdevbridge}"
WORK="${CDB_WORK:-$(mktemp -d)}/multipart-consumer"

log() { printf '\n=== %s ===\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }
skip() { printf '\nSKIPPED: %s\n' "$*"; exit 0; }

MC_VERSION="$(grep -oP '^minecraft_version=\K.*' "$ROOT/gradle.properties")"
NEOFORGE_VERSION="$(grep -oP '^neoforge_version=\K.*' "$ROOT/gradle.properties")"

log "Configuration"
echo "minecraft: $MC_VERSION"
echo "neoforge:  $NEOFORGE_VERSION"
echo "work:      $WORK"

# Integrated Dynamics raises its NeoForge floor over time, and this repository pins an older
# NeoForge than the newest release wants. Rather than guess, ask Modrinth for every build for this
# Minecraft version and take the newest one this project can actually load -- which is exactly the
# check a human would otherwise do by hand, once per bump.
resolve() {
  python3 - "$1" "$MC_VERSION" "$NEOFORGE_VERSION" <<'PY'
import json, sys, tempfile, urllib.request, urllib.parse, zipfile, re, os

slug, mc, neoforge = sys.argv[1], sys.argv[2], sys.argv[3]

def get(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)

def as_tuple(version):
    return tuple(int(part) for part in re.findall(r'\d+', version)[:3])

# The filters are JSON arrays in the query string, so they have to be percent-encoded; sent raw
# the API answers 400.
query = ('https://api.modrinth.com/v2/project/' + slug + '/version?'
         + urllib.parse.urlencode({'game_versions': json.dumps([mc]),
                                   'loaders': json.dumps(['neoforge'])}))
try:
    versions = get(query)
except Exception as error:                       # noqa: BLE001 - reported, not raised
    print(f'ERROR {error}', file=sys.stderr)
    sys.exit(3)

for version in versions:                          # newest first
    url = version['files'][0]['url']
    path = os.path.join(tempfile.mkdtemp(), version['files'][0]['filename'])
    try:
        urllib.request.urlretrieve(url, path)
        with zipfile.ZipFile(path) as jar:
            toml = jar.read('META-INF/neoforge.mods.toml').decode('utf8')
    except Exception:                             # noqa: BLE001 - just try the next build
        continue
    block = re.search(r'modId\s*=\s*"neoforge".*?versionRange\s*=\s*"\[([^,\]]+)', toml, re.S)
    if block is None or as_tuple(block.group(1)) <= as_tuple(neoforge):
        print(path)
        sys.exit(0)
sys.exit(4)
PY
}

command -v python3 >/dev/null || skip "python3 is needed to resolve the mod versions"

log "Resolving Integrated Dynamics and its dependencies"
JARS=()
for slug in cyclops-core common-capabilities integrated-dynamics; do
  if ! jar="$(resolve "$slug" 2>/dev/null)"; then
    skip "no $slug build for Minecraft $MC_VERSION runs on NeoForge $NEOFORGE_VERSION (or Modrinth is unreachable)"
  fi
  echo "$(basename "$jar")"
  JARS+=("$jar")
done

log "Building a throwaway consumer"
rm -rf "$WORK"
mkdir -p "$(dirname "$WORK")"
cp -r "$ROOT/e2e/consumer" "$WORK"
rm -rf "$WORK/.clientdevbridge" "$WORK"/loader-*/build "$WORK"/loader-*/runs "$WORK"/loader-*/run "$WORK/build" "$WORK/.gradle"
# The multiloader template adds every jar in extra-mods as a runtime mod dependency, which is what
# a dev environment does with a mod it does not build itself.
mkdir -p "$WORK/loader-neoforge/extra-mods"
cp "${JARS[@]}" "$WORK/loader-neoforge/extra-mods/"

cleanup() { "$CLI" --project "$WORK" stop >/dev/null 2>&1 || true; }
trap cleanup EXIT

log "Publishing this build to mavenLocal"
"$ROOT/gradlew" -p "$ROOT" publishToMavenLocal --no-daemon --console=plain -q

log "start"
"$CLI" --project "$WORK" start --loader neoforge --timeout 1500 || fail "the client did not come up"
"$CLI" --project "$WORK" world-reset
"$CLI" --project "$WORK" resize --width 854 --height 480 --gui-scale 2 >/dev/null

log "A plain block with a GUI still works"
"$CLI" --project "$WORK" setblock 3 4 2 integrateddynamics:logic_programmer
"$CLI" --project "$WORK" open-gui 3 4 2 | grep -q ContainerScreenLogicProgrammer \
  || fail "the Logic Programmer GUI did not open"
"$CLI" --project "$WORK" close-screen >/dev/null

log "Place parts on two different sides of a cable"
"$CLI" --project "$WORK" setblock 0 4 2 integrateddynamics:cable
"$CLI" --project "$WORK" command "item replace entity @s weapon.mainhand with integrateddynamics:part_redstone_writer 1" >/dev/null
"$CLI" --project "$WORK" use 0 4 2 --face up | tee /tmp/cdb-use-up.txt
grep -q "up side of 0,4,2: SUCCESS" /tmp/cdb-use-up.txt \
  || fail "the click on the up side was not consumed, so no part was placed there"

"$CLI" --project "$WORK" command "item replace entity @s weapon.mainhand with integrateddynamics:part_redstone_reader 1" >/dev/null
"$CLI" --project "$WORK" use 0 4 2 --face north | tee /tmp/cdb-use-north.txt
grep -q "north side of 0,4,2: SUCCESS" /tmp/cdb-use-north.txt \
  || fail "the click on the north side was not consumed, so no part was placed there"

log "The block description shows both parts"
# Integrated Dynamics registers no BlockExtractor, so this is the fallback path: the block entity's
# synced NBT, which carries __partType and __side per part. A mod that registers one gets the same
# information under blockEntity.details without --nbt.
"$CLI" --project "$WORK" block 0 4 2 --nbt | tee /tmp/cdb-cable.txt >/dev/null
grep -q 'redstone_writer' /tmp/cdb-cable.txt || fail "the description does not mention the writer"
grep -q 'redstone_reader' /tmp/cdb-cable.txt || fail "the description does not mention the reader"
echo "both parts are visible in the block entity NBT"

log "Each side opens its own part GUI"
"$CLI" --project "$WORK" command "item replace entity @s weapon.mainhand with minecraft:air" >/dev/null
"$CLI" --project "$WORK" open-gui 0 4 2 --face up | grep -q ContainerScreenPartWriter \
  || fail "the up side did not open the Redstone Writer"
"$CLI" --project "$WORK" snapshot | grep -qi "Redstone Writer" || fail "the writer's outline is wrong"
"$CLI" --project "$WORK" close-screen >/dev/null

"$CLI" --project "$WORK" open-gui 0 4 2 --face north | grep -q ContainerScreenPartReader \
  || fail "the north side did not open the Redstone Reader"
"$CLI" --project "$WORK" snapshot | grep -qi "Redstone Reader" || fail "the reader's outline is wrong"
"$CLI" --project "$WORK" screenshot --name multipart-reader --quiet
"$CLI" --project "$WORK" close-screen >/dev/null

log "A side with no part reports that, rather than claiming the block has no GUI"
"$CLI" --project "$WORK" open-gui 0 4 2 --face south > /tmp/cdb-empty-side.txt 2>&1 || true
grep -q "one per side" /tmp/cdb-empty-side.txt || fail "the empty-side hint is missing"

log "stop"
"$CLI" --project "$WORK" stop

log "PASSED (multipart against Integrated Dynamics)"
