#!/usr/bin/bash -p
# Fetch the pinned Nuked-OPN2 (ym3438) reference source into a caller-supplied
# directory. The engine's Java FM core is a port of exactly this revision; see
# PIN.md next to this script for the recorded hashes and licence.
set -euo pipefail

fail() { /usr/bin/printf 'fetch-source: %s\n' "$*" >&2; exit 1; }

upstream=https://github.com/nukeykt/Nuked-OPN2.git
commit=335747d78cb0abbc3b55b004e62dad9763140115
tree=6637a500d1da3b08cbc0cec1532ab305197b8978

output=
while (($#)); do
  case "$1" in
    --output) output=${2-}; shift 2 ;;
    *) fail "unknown argument: $1" ;;
  esac
done
[[ -n "$output" ]] || fail "--output is required"
[[ "$output" = /* ]] || fail "--output must be an absolute path"
[[ ! -e "$output" && ! -L "$output" ]] || fail "output already exists: $output"
parent=${output%/*}; [[ -n "$parent" ]] || parent=/
[[ -d "$parent" && ! -L "$parent" ]] || fail "output parent must be an existing non-symlink directory"

stage=$(/usr/bin/mktemp -d "$parent/.nuked-opn2-source-staging.XXXXXX")
config_stage=$(/usr/bin/mktemp -d "$parent/.nuked-opn2-git-config-staging.XXXXXX")
cleanup() {
  if [[ -n "${stage-}" && -d "$stage" ]]; then /usr/bin/rm -rf -- "$stage"; fi
  if [[ -n "${config_stage-}" && -d "$config_stage" ]]; then /usr/bin/rm -rf -- "$config_stage"; fi
}
trap cleanup EXIT
home=$config_stage/home
xdg=$config_stage/xdg
/usr/bin/mkdir -p -- "$home" "$xdg"
locked_git() {
  /usr/bin/env -i HOME="$home" XDG_CONFIG_HOME="$xdg" PATH=/usr/bin:/bin LC_ALL=C \
    GIT_CONFIG_NOSYSTEM=1 GIT_TERMINAL_PROMPT=0 GIT_ASKPASS=/bin/false SSH_ASKPASS=/bin/false \
    /usr/bin/git -c core.hooksPath=/dev/null -c protocol.allow=never \
    -c protocol.https.allow=always "$@"
}

locked_git -C "$stage" init -q
locked_git -C "$stage" fetch -q --depth=1 "$upstream" "$commit"
locked_git -C "$stage" checkout -q --detach FETCH_HEAD
[[ $(locked_git -C "$stage" rev-parse HEAD) = "$commit" ]] || fail "wrong Nuked-OPN2 commit"
[[ $(locked_git -C "$stage" rev-parse 'HEAD^{tree}') = "$tree" ]] || fail "wrong Nuked-OPN2 tree"

while read -r expected relative; do
  /usr/bin/printf '%s  %s\n' "$expected" "$stage/$relative" | /usr/bin/sha256sum -c - >/dev/null \
    || fail "sha256 mismatch: $relative"
done <<'LOCKED_FILES'
8fa385546f0f2d1c975d097002af00cd729ae2ae097c068e9c883ce08ddf3a76 ym3438.c
8e60e35f77049d0e600ad1a47bfc3dfc8b832483e614104473a83c1f33cd7189 ym3438.h
20c17d8b8c48a600800dfd14f95d5cb9ff47066a9641ddeab48dc54aec96e331 LICENSE
21634adf91e4e2a483adfb10084ce06f105225265cf869fe22fd4ab3dcd77bf1 README.md
c4ec292d3857048ecef2fb75e869269e753aa1d8f358ce34855b20a4d1e1a53c ym3438.svg
LOCKED_FILES

[[ -z $(locked_git -C "$stage" status --short --untracked-files=all) ]] || fail "source tree is not clean"
[[ -z $(locked_git -C "$stage" clean -ndx) ]] || fail "source tree has untracked or ignored files"
/usr/bin/rm -rf -- "$config_stage"
config_stage=
/usr/bin/mv -T --no-clobber -- "$stage" "$output"
stage=
/usr/bin/printf '%s\n' "$output"
