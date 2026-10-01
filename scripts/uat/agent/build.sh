#!/usr/bin/env bash
# Builds the harness's phone-side agent (scripts/uat/agent/src) into a dex jar app_process can run.
# Output: scripts/uat/agent/build/wispr-agent.jar. Uses only the SDK already pinned in local.properties.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_HOME:-$HOME/Android/sdk}"
android_jar="$sdk/platforms/android-36/android.jar"
d8="$(ls -d "$sdk"/build-tools/*/d8 | sort -V | tail -1)"
[ -f "$android_jar" ] || { echo "missing $android_jar" >&2; exit 1; }
[ -x "$d8" ] || { echo "missing d8 under $sdk/build-tools" >&2; exit 1; }
out="$here/build"
rm -rf "$out/classes" "$out/dex"
mkdir -p "$out/classes" "$out/dex"
javac --release 11 -nowarn -cp "$android_jar" -d "$out/classes" $(find "$here/src" -name '*.java')
"$d8" --min-api 33 --lib "$android_jar" --output "$out/dex" $(find "$out/classes" -name '*.class')
(cd "$out/dex" && rm -f ../wispr-agent.jar && zip -q ../wispr-agent.jar classes.dex)
echo "$out/wispr-agent.jar"
