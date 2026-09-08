#!/usr/bin/env sh
set -eu
engine=$1 sdk=$2 out=$3
cp -R "$(dirname "$0")/project" "$out"
level_source="$out/src/main/mod/level-source"
# Read the entire value: dash treats a trailing '=' IFS delimiter as a separator,
# which would remove single-character Base64 padding.
while IFS= read -r asset; do
  name=${asset%%=*}
  data=${asset#*=}
  printf '%s' "$data" | base64 -d > "$level_source/$name"
done < "$level_source/binary-assets.properties"
rm "$level_source/binary-assets.properties"
base64 -d < "$out/src/main/mod/runner.png.base64" > "$out/src/main/mod/runner.png"
base64 -d < "$out/src/main/mod/sample-tone.wav.base64" > "$out/src/main/resources/audio/sample-tone.wav"
mvn -q -f "$out/pom.xml" package "-Dopenggf.engine.jar=$engine" "-Dopenggf.sdk.jar=$sdk"
