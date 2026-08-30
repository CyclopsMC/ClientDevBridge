#!/usr/bin/env bash
#
# Prepares a cloud sandbox (or any headless Linux machine) to run a Minecraft dev client.
#
# Installs the virtual display and software OpenGL the client needs, installs the CLI, and
# optionally warms the Gradle cache so the first `clientdevbridge start` is not dominated by
# downloads.
#
# Usage: scripts/cloud-setup.sh [--project <dir>] [--no-install] [--no-warm]

set -euo pipefail

PROJECT=""
INSTALL=1
WARM=1

while [[ $# -gt 0 ]]; do
  case "$1" in
    --project) PROJECT="$2"; shift 2 ;;
    --no-install) INSTALL=0; shift ;;
    --no-warm) WARM=0; shift ;;
    -h|--help) sed -n '2,12p' "$0"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

log() { printf '\n==> %s\n' "$*"; }

SUDO=""
if [[ "$(id -u)" != "0" ]]; then
  SUDO="sudo"
fi

if [[ "$INSTALL" == "1" ]]; then
  log "Installing the virtual display and software OpenGL"
  # libgl1-mesa-dri is the important one: without a software rasteriser the client dies during
  # shader loading with no useful error message.
  $SUDO apt-get update -qq
  $SUDO apt-get install -y -qq xvfb libgl1-mesa-dri mesa-utils libglu1-mesa

  log "Installing cyclops-clientdevbridge-cli"
  if command -v npm >/dev/null 2>&1; then
    npm install -g cyclops-clientdevbridge-cli
  else
    echo "npm is not installed; install Node 20 or newer, then re-run." >&2
    exit 2
  fi
fi

log "Checking the toolchain"
# Which JDK version is needed depends on the Minecraft version the mod targets (1.21 wants 21, the
# 26 line wants 25), and Gradle takes it from JAVA_HOME rather than the PATH, so the real check is
# `clientdevbridge doctor` against a project. This only establishes that there is a JDK at all.
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -version 2>&1 | head -1 \
  || { echo "No JDK found. Install one matching the mod's java_version, and point JAVA_HOME at it." >&2; exit 2; }
node --version

if [[ -n "$PROJECT" ]]; then
  if [[ ! -x "$PROJECT/gradlew" ]]; then
    echo "$PROJECT does not look like a Gradle mod project (no gradlew)." >&2
    exit 2
  fi

  if [[ "$WARM" == "1" ]]; then
    log "Warming the Gradle cache (this is the slow part, and it only happens once)"
    (cd "$PROJECT" && ./gradlew --no-daemon tasks >/dev/null)
  fi

  log "Running doctor against $PROJECT"
  clientdevbridge --project "$PROJECT" doctor || true

  cat <<EOF

Ready. Next:

  cd $PROJECT
  clientdevbridge start
  clientdevbridge screenshot

EOF
else
  cat <<'EOF'

Ready. Point it at your mod:

  cd path/to/your/mod
  clientdevbridge doctor
  clientdevbridge start

EOF
fi
