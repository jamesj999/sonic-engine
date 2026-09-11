#!/usr/bin/env bash
# S3K sound-driver oracle reference capture, v2.
#
# Replays the committed S3K complete-run BK2 through the headless GPGX host
# with the pinned patch-0001 audio-observer core and records one row per
# *completed zVInt service*. The driver-RAM snapshot (Z80 1C00h-1FA0h) is taken
# by the observer core at the zVInt return instruction (Z80 PC 0084h) as a
# completion snapshot, not by an out-of-band read at the video-frame boundary as
# v1 did; see the header of the capture source for the boundary derivation. Also
# records the frame-entry 68k request mailboxes and the ordered YM/PSG write
# stream, partitioned by the same service-completion boundary. See
# tools/audio/s3k/S3kAudioOracleReferenceCaptureV2.cs and
# docs/architecture/designs/audio/2026-08-30-s3k-audio-oracle-design.md.
#
# Required environment:
#   OGGF_BIZHAWK_STOCK   stock BizHawk 2.11 Linux x64 distribution
#   OGGF_OBSERVER_CORE   patch-0001 observer gpgx.wbx.zst (compressed SHA-256
#                        must equal the core pinned by tools/tracechaser/
#                        bizhawk-headless/native/gpgx-audio-observer/artifact-lock.json)
#   OGGF_WORKDIR         scratch working directory outside the repository
#   OGGF_OUT             output JSONL path (must not exist)
# Optional:
#   OGGF_FRAMES          movie frames to replay (default 5400); the tick
#                        count is however many zVInt services complete
#   OGGF_MOVIE           BK2 path (default: committed s3k-complete-sonic-tails.bk2)
set -euo pipefail

repo=$(git rev-parse --show-toplevel)
headless="$repo/tools/tracechaser/bizhawk-headless"
[[ -d "$headless" ]] || {
  echo "tools/tracechaser submodule is not initialised (git submodule update --init tools/tracechaser)" >&2
  exit 4
}
: "${OGGF_BIZHAWK_STOCK:?set OGGF_BIZHAWK_STOCK to the stock BizHawk 2.11 Linux x64 directory}"
: "${OGGF_OBSERVER_CORE:?set OGGF_OBSERVER_CORE to the lock-matching patch-0001 gpgx.wbx.zst}"
: "${OGGF_WORKDIR:?set OGGF_WORKDIR to a scratch directory outside the repository}"
: "${OGGF_OUT:?set OGGF_OUT to the output JSONL path}"
frames=${OGGF_FRAMES:-5400}
movie=${OGGF_MOVIE:-$repo/src/test/resources/traces/s3k/_movies/s3k-complete-sonic-tails.bk2}
# Identify the pinned locked-on S3K ROM by content. Written as a plain loop:
# the v1 script's find|xargs|awk pipeline exits awk early on a match, which
# SIGPIPEs xargs and, under `set -o pipefail`, aborts the whole script.
rom=
for candidate in "$repo"/*.gen; do
  [[ -f "$candidate" ]] || continue
  if [[ "$(sha1sum "$candidate" | cut -d' ' -f1)" == cfbf98c36c776677290a872547ac47c53d2761d6 ]]; then
    rom="$candidate"
    break
  fi
done
[[ -n "$rom" ]] || { echo "pinned locked-on S3K ROM not found at the repository root" >&2; exit 4; }
[[ ! -e "$OGGF_OUT" ]] || { echo "output already exists: $OGGF_OUT" >&2; exit 4; }
case "$OGGF_OUT" in "$repo"/src/test/resources/*) echo "output must not be under src/test/resources" >&2; exit 4;; esac

# Verify the observer core against the committed artifact lock.
lock="$headless/native/gpgx-audio-observer/artifact-lock.json"
expected=$(python3 -c "import json,sys;print(json.load(open(sys.argv[1]))['core']['compressed_sha256'])" "$lock")
actual=$(sha256sum "$OGGF_OBSERVER_CORE" | cut -d' ' -f1)
[[ "$actual" == "$expected" ]] || {
  echo "observer core SHA-256 $actual does not match artifact-lock $expected" >&2; exit 4; }

# Verify the stock distribution per install-core.sh's LOCKED_STOCK identity.
( cd "$OGGF_BIZHAWK_STOCK" && sha256sum -c --quiet - <<'LOCKED_STOCK'
b2d4be5e2a766a5161cc26f3af2a90753c39d64c91c54a9884171aed09e21df3  EmuHawk.exe
0144e6e236be68ce126eb771dcb5a9ae7c153a083fa0333f345ac37b4a60acf7  dll/BizHawk.Emulation.Cores.dll
f20cd009f6f5b0a95bd47b66c48dc8de85afcd7ae0cc6aab3486baf55f501fb4  dll/BizHawk.Emulation.Common.dll
8d05389bf0e02be1244bdc7a2adcd93b4cff95acf199fc927987ca699760a1b7  dll/BizHawk.BizInvoke.dll
438a49d6a45d9fcac17016240ae205d1af7a4632865f6f70468b684b82323f33  dll/BizHawk.Common.dll
d2367818aafb4e520ad5ab005b5762c61506b0c819c4d79687235acfb0fc0c78  dll/libwaterboxhost.so
c4231296ec5ba59b431df22b68e234ae7bfbbfc87b6e72fa471234ac1b220d12  dll/gpgx.wbx.zst
LOCKED_STOCK
) || { echo "stock BizHawk distribution does not match the locked 2.11 identity" >&2; exit 4; }

mkdir -p "$OGGF_WORKDIR"
home="$OGGF_WORKDIR/s3k-oracle-observer-home"
if [[ ! -d "$home" ]]; then
  cp -a "$OGGF_BIZHAWK_STOCK" "$home"
  cp "$OGGF_OBSERVER_CORE" "$home/dll/gpgx.wbx.zst"
fi
[[ "$(sha256sum "$home/dll/gpgx.wbx.zst" | cut -d' ' -f1)" == "$expected" ]] || {
  echo "assembled home core does not match the artifact lock; delete $home and retry" >&2; exit 4; }

# Compile the capture from the pinned harness sources plus the committed
# capture entry point, with the same Roslyn build.sh pins.
csc=/usr/lib/mono/msbuild/Current/bin/Roslyn/csc.exe
[[ -f "$csc" ]] || { echo "pinned Roslyn compiler not found: $csc" >&2; exit 4; }
exe="$OGGF_WORKDIR/S3kAudioOracleCaptureV2.exe"
( cd "$headless" && mono "$csc" -nologo -optimize+ -langversion:7.3 \
  -out:"$exe" \
  -main:OpenGGF.BizHawk.Headless.S3kAudioOracleReferenceCaptureV2 \
  -lib:/usr/lib/mono/4.8-api -lib:/usr/lib/mono/4.8-api/Facades \
  -r:mscorlib.dll -r:System.dll -r:System.Core.dll -r:netstandard.dll \
  -r:System.IO.Compression.dll -r:System.IO.Compression.FileSystem.dll \
  -r:"$home/dll/BizHawk.Common.dll" -r:"$home/dll/BizHawk.Emulation.Common.dll" \
  -r:"$home/dll/BizHawk.Emulation.Cores.dll" -r:"$home/dll/BizHawk.Emulation.DiscSystem.dll" \
  -r:"$home/dll/BizHawk.BizInvoke.dll" -r:"$home/dll/Newtonsoft.Json.dll" \
  $(find src -name '*.cs') \
  "$repo/tools/audio/s3k/S3kAudioOracleReferenceCaptureV2.cs" )

env -u DISPLAY \
  BIZHAWK_HOME="$home" \
  MONO_PATH="$home/dll" \
  LD_LIBRARY_PATH="$home/dll" \
  S3K_ROM_PATH="$rom" \
  OGGF_S3K_ORACLE_MOVIE="$movie" \
  OGGF_S3K_ORACLE_MANIFEST="$headless/fixtures/gpgx-audio-service-manifest-s3k-oracle-v2.json" \
  OGGF_S3K_ORACLE_OUTPUT="$OGGF_OUT" \
  OGGF_S3K_ORACLE_FRAMES="$frames" \
  timeout 1800 mono "$exe"
