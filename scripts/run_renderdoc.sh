#!/usr/bin/env bash

set -euo pipefail

script_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$script_dir"

renderdoc_cmd="$(command -v renderdoccmd || true)"
if [[ -z "$renderdoc_cmd" ]]; then
    echo "RenderDoc command-line launcher not found." >&2
    echo "Expected renderdoccmd on PATH." >&2
    exit 1
fi

java_exe="$(command -v java || true)"
if [[ -z "$java_exe" ]]; then
    echo "java not found on PATH." >&2
    exit 1
fi

mvn -Dmse=off -DskipTests package -q

shopt -s nullglob
jars=(target/*-jar-with-dependencies.jar)
shopt -u nullglob

if (( ${#jars[@]} == 0 )); then
    echo "No jar file found in $script_dir/target" >&2
    exit 1
fi

jar="${jars[${#jars[@]} - 1]}"
capture_dir="$script_dir/target/renderdoc"
mkdir -p "$capture_dir"

echo "Launching OpenGGF under RenderDoc..."
echo "Capture hotkey: PrtSc"
echo "Captures: $capture_dir"

exec "$renderdoc_cmd" capture \
    --working-dir "$script_dir" \
    --capture-file "$capture_dir/openggf" \
    --opt-hook-children \
    -w \
    "$java_exe" \
    --add-exports java.base/java.lang=ALL-UNNAMED \
    --add-exports java.desktop/sun.awt=ALL-UNNAMED \
    --add-exports java.desktop/sun.java2d=ALL-UNNAMED \
    -XX:+UseG1GC \
    -XX:MaxGCPauseMillis=5 \
    -jar "$jar"
