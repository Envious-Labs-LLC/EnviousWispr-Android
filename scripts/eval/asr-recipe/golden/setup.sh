#!/bin/sh
# Builds the desktop golden harness (#374, #453) from the app's own speech sources. Run from anywhere.
# Links TdtRunner/TdtRecipe/TdtModel plus this folder's Golden.kt, Probe.kt and Probe1.kt into src/, copies the
# repository's Gradle wrapper, then installs build/install/golden/bin/golden.
set -eu
here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../../.." && pwd)
pkg="$here/src/com/envi/wispr/asr"
mkdir -p "$pkg"
for f in TdtRunner TdtRecipe TdtModel; do ln -sf "$repo/app/src/main/java/com/envi/wispr/asr/$f.kt" "$pkg/$f.kt"; done
for f in Golden Probe Probe1; do ln -sf "$here/$f.kt" "$pkg/$f.kt"; done
cp -R "$repo/gradle" "$here/" && cp "$repo/gradlew" "$here/"
cd "$here" && ./gradlew -q installDist
echo "built: $here/build/install/golden/bin/golden"
