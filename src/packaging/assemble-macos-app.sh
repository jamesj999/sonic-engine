#!/bin/bash
# Assembles a macOS .app bundle from a GraalVM native-image binary.
#
# Usage: ./assemble-macos-app.sh <path-to-native-binary> [output-dir] [maven-version]
#
# Example:
#   ./assemble-macos-app.sh <artifact-root>/OpenGGF <distribution-root>

set -euo pipefail

# Only assemble .app on macOS; silently skip on other platforms
if [ "$(uname)" != "Darwin" ]; then
    echo "Skipping .app assembly (not macOS)"
    exit 0
fi

BINARY="${1:?Usage: $0 <path-to-native-binary> [output-dir]}"
OUTPUT_DIR="${2:-.}"
PROJECT_VERSION="${3:-}"
APP_NAME="OpenGGF"
APP_BUNDLE="${OUTPUT_DIR}/${APP_NAME}.app"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Apple requires CFBundleVersion and CFBundleShortVersionString to contain
# numeric version components. Maven's development version has a textual
# suffix (for example, 0.6.prerelease), so keep the release identity in Maven
# while emitting the compatible numeric prefix in the app bundle.
if [ -z "${PROJECT_VERSION}" ] && [ -f "${SCRIPT_DIR}/../../pom.xml" ]; then
    PROJECT_VERSION="$(sed -n '/<version>/ { s/.*<version>\([^<]*\)<\/version>.*/\1/p; q; }' \
        "${SCRIPT_DIR}/../../pom.xml")"
fi
if [[ "${PROJECT_VERSION}" =~ ^([0-9]+)(\.([0-9]+))?(\.([0-9]+))? ]]; then
    MACOS_VERSION="${BASH_REMATCH[1]}.${BASH_REMATCH[3]:-0}.${BASH_REMATCH[5]:-0}"
else
    echo "Cannot derive a numeric macOS bundle version from '${PROJECT_VERSION}'" >&2
    exit 1
fi

# Clean previous bundle
rm -rf "${APP_BUNDLE}"

# Create directory structure
mkdir -p "${APP_BUNDLE}/Contents/MacOS"
mkdir -p "${APP_BUNDLE}/Contents/Resources"

# Copy Info.plist
cp "${SCRIPT_DIR}/Info.plist" "${APP_BUNDLE}/Contents/"
plutil -replace CFBundleVersion -string "${MACOS_VERSION}" \
    "${APP_BUNDLE}/Contents/Info.plist"
plutil -replace CFBundleShortVersionString -string "${MACOS_VERSION}" \
    "${APP_BUNDLE}/Contents/Info.plist"

# Copy native binary as OpenGGF.bin
cp "${BINARY}" "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}.bin"
chmod +x "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}.bin"

# Copy LWJGL native libraries (extracted by Maven build)
NATIVE_LIBS_DIR="$(dirname "${BINARY}")/native-libs"
if [ -d "${NATIVE_LIBS_DIR}" ]; then
    cp "${NATIVE_LIBS_DIR}"/*.dylib "${APP_BUNDLE}/Contents/MacOS/" 2>/dev/null || true
    echo "Bundled native libraries from ${NATIVE_LIBS_DIR}"
fi

# Create launcher script as the CFBundleExecutable.
# Handles two GraalVM native-image issues on macOS:
# 1. SONIC_NATIVE_LIBS_DIR: custom env var for LWJGL library discovery
#    (macOS SIP strips DYLD_LIBRARY_PATH from Finder-launched processes)
# 2. -Duser.dir: GraalVM's getcwd() fails when launched via Finder/open,
#    so we explicitly set user.dir for property init and file path resolution
cat > "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}" << 'LAUNCHER'
#!/bin/bash
DIR="$(cd "$(dirname "$0")" && pwd)"
# Set working directory to the folder containing the .app bundle,
# so the engine finds the ROM file placed next to OpenGGF.app
APP_DIR="$(cd "${DIR}/../../.." && pwd)"
cd "${APP_DIR}"

# Custom env var for LWJGL native lib discovery (not stripped by SIP)
export SONIC_NATIVE_LIBS_DIR="${DIR}"
# Also set DYLD_LIBRARY_PATH as fallback (works from Terminal, stripped by Finder)
export DYLD_LIBRARY_PATH="${DIR}${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"

# GraalVM native-image's getcwd() fails when launched via macOS
# LaunchServices (Finder/open). -Duser.dir bypasses the broken
# getcwd() in property initialization and file path resolution.
"${DIR}/OpenGGF.bin" "-Duser.dir=${APP_DIR}" "$@"
LAUNCHER
chmod +x "${APP_BUNDLE}/Contents/MacOS/${APP_NAME}"

# Copy config.yaml next to the .app bundle so it can be edited without rebuilding
CONFIG_SRC="$(cd "$(dirname "${BINARY}")" && pwd)/config.yaml"
CONFIG_DST="$(cd "${OUTPUT_DIR}" && pwd)/config.yaml"
if [ -f "${CONFIG_SRC}" ] && [ "${CONFIG_SRC}" != "${CONFIG_DST}" ]; then
    cp "${CONFIG_SRC}" "${CONFIG_DST}"
    echo "Exported config.yaml to ${OUTPUT_DIR}/"
elif [ -f "${CONFIG_DST}" ]; then
    echo "config.yaml already present in ${OUTPUT_DIR}/"
fi

# Build the bundle icon from the same packaged PNG used by direct JAR launches.
# sips is supplied by macOS; no Java desktop APIs are involved.
ICON_SOURCE="${SCRIPT_DIR}/../main/resources/icon/openggf-256.png"
if [ -f "${ICON_SOURCE}" ]; then
    sips -s format icns "${ICON_SOURCE}" \
        --out "${APP_BUNDLE}/Contents/Resources/${APP_NAME}.icns" >/dev/null
fi

echo "Created ${APP_BUNDLE}"
