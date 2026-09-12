#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir"

# This is a normal, non-certifying local launcher. Keep the distributable in
# this worktree's target/ directory.
mvn -Dmse=off -DskipTests package -q

manifest="$script_dir/target/openggf-artifact.properties"
if [[ ! -f "$manifest" ]]; then
    echo "Maven packaging did not produce $manifest" >&2
    exit 1
fi

final_name="$(sed -n 's/^finalName=//p' "$manifest")"
if [[ -z "$final_name" || "$final_name" == *$'\n'* || "$final_name" == *$'\r'* ]]; then
    echo "Maven packaging wrote an empty or multiline finalName to $manifest" >&2
    exit 1
fi

jar="$script_dir/target/${final_name}-jar-with-dependencies.jar"

if [[ ! -f "$jar" ]]; then
    echo "Expected packaged jar not found: $jar" >&2
    exit 1
fi

exec java \
    --add-exports java.base/java.lang=ALL-UNNAMED \
    --add-exports java.desktop/sun.awt=ALL-UNNAMED \
    --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=5 \
    -jar "$jar"
