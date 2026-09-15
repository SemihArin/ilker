#!/usr/bin/env bash
# Runs the off-device checks and renders preview images from the game's own
# source files. Needs nothing but a JDK — no Android SDK, no emulator.
#
#   tools/harness/run.sh            # checks only
#   tools/harness/run.sh --preview  # checks plus docs/*.png
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
src="$root/app/src/main/java"
out="$here/out"

rm -rf "$out"
mkdir -p "$out"

javac -nowarn -d "$out" \
    "$here/android/opengl/Matrix.java" \
    "$src/com/ilker/opendrive/world/Terrain.java" \
    "$src/com/ilker/opendrive/world/ChunkData.java" \
    "$src/com/ilker/opendrive/world/ChunkBuilder.java" \
    "$src/com/ilker/opendrive/gl/MeshBuilder.java" \
    "$src/com/ilker/opendrive/gl/Frustum.java" \
    "$src/com/ilker/opendrive/game/CarSpec.java" \
    "$src/com/ilker/opendrive/game/CarMesh.java" \
    "$src/com/ilker/opendrive/game/Car.java" \
    "$src/com/ilker/opendrive/game/Controls.java" \
    "$here/Harness.java" \
    "$here/Preview.java"

java -cp "$out" Harness

if [[ "${1:-}" == "--preview" ]]; then
    mkdir -p "$root/docs"
    java -cp "$out" Preview "$root/docs"
fi
