#!/bin/sh

set -eu

GITHUB_FILE_SIZE_LIMIT_BYTES=100000000
TRACE_COMPRESSION_THRESHOLD_BYTES=1048576
# Maintainer-approved 0.6 trailer-debt baseline; all later incoming commits are checked.
RELEASE_TRAILER_CUTOVER_BASE=45cecf566825aa50612f5e687b2682fc9681aed1
RESOURCE_POLICY_CUTOVER=ccdd33edf4f9cd4a7937791f1d4c2f37cbeeb5e0
RELEASE_RESOURCE_BASELINE=45cecf566825aa50612f5e687b2682fc9681aed1
NEXT_ROLLOVER_RESOURCE_BASELINE=218b8fff1a829c7a509331c453d2f3be7e51533b
NEXT_ROLLOVER_TRAILER_BASELINE=218b8fff1a829c7a509331c453d2f3be7e51533b
PUBLISHED_RELEASE_TRAILER_BASELINE=37aeb6b84d6634457616c942187cdd40dd93c24f
RELEASE_RANGE_BASE=
ROM_LIKE_DENYLIST_EXTENSIONS=".gen .smd .bin .sms .gg .32x"
EMPTY_TREE_OID=4b825dc642cb6eb9a060e54bf8d69288fbee4904
ALL_ZERO_OID=0000000000000000000000000000000000000000
POSIX_HOME_ROOT=/home
VAR_HOME_ROOT=/var/home
MACOS_HOME_ROOT=/Users
WINDOWS_USERS_ROOT='[A-Za-z]:[\\/]+[Uu][Ss][Ee][Rr][Ss]'
POLICY_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MACHINE_LOCAL_PATH_GRANDFATHER="$POLICY_DIR/machine-local-path-grandfather.sha256"

die() {
    echo "policy: $*" >&2
    exit 1
}

note() {
    echo "policy: $*" >&2
}

current_branch() {
    git symbolic-ref --quiet --short HEAD 2>/dev/null || echo "HEAD"
}

is_merge_in_progress() {
    git rev-parse -q --verify MERGE_HEAD >/dev/null 2>&1
}

merge_head_oid() {
    git rev-parse -q --verify MERGE_HEAD 2>/dev/null || true
}

master_tip_oid() {
    git rev-parse -q --verify refs/heads/master 2>/dev/null || true
}

is_merge_from_master() {
    merge_oid=$(merge_head_oid)
    master_oid=$(master_tip_oid)
    [ -n "$merge_oid" ] && [ -n "$master_oid" ] && [ "$merge_oid" = "$master_oid" ]
}

staged_files() {
    git diff --cached --name-only --diff-filter=ACMRT
}

staged_candidates() {
    merge_oid=$(merge_head_oid)
    if [ -n "$merge_oid" ]; then
        git diff --cached --no-renames --name-only --diff-filter=AMT "$merge_oid"
        return
    fi
    if git rev-parse -q --verify HEAD >/dev/null 2>&1; then
        git diff --cached --no-renames --name-only --diff-filter=AMT HEAD
    else
        git diff --cached --no-renames --name-only --diff-filter=AMT
    fi
}

commit_files() {
    git diff-tree --root --no-commit-id --name-only --diff-filter=ACMRT -r "$1"
}

commit_parent_or_empty_tree() {
    if git rev-parse -q --verify "$1^2" >/dev/null 2>&1; then
        git rev-parse "$1^2"
        return
    fi
    git rev-parse -q --verify "$1^1" 2>/dev/null || printf '%s\n' "$EMPTY_TREE_OID"
}

commit_candidates() {
    commit=$1
    if git rev-parse -q --verify "$commit^2" >/dev/null 2>&1; then
        # A merge commit's own candidates are the paths whose content differs
        # from every parent. Anything matching either side was already
        # published on that side and is that branch's history, not content
        # this merge introduces.
        first=$(git rev-parse "$commit^1")
        second=$(git rev-parse "$commit^2")
        first_list=$(mktemp)
        second_list=$(mktemp)
        git diff --no-renames --name-only --diff-filter=AMT "$first" "$commit" |
            sort >"$first_list"
        git diff --no-renames --name-only --diff-filter=AMT "$second" "$commit" |
            sort >"$second_list"
        comm -12 "$first_list" "$second_list"
        rm -f "$first_list" "$second_list"
        return
    fi
    parent=$(commit_parent_or_empty_tree "$commit")
    git diff --no-renames --name-only --diff-filter=AMT "$parent" "$commit"
}

MOD_API_DESCRIPTOR=mod-api-release-policy.properties
MOD_API_VERSION=src/main/java/com/openggf/mods/ModApiVersion.java
MOD_API_PIN_PREFIX=src/test/resources/mods/mod-api-signatures-

blob_text() {
    ref=$1
    path=$2
    if [ "$ref" = INDEX ]; then
        git show ":$path" 2>/dev/null || true
    elif [ "$ref" = EMPTY ]; then
        :
    else
        git show "$ref:$path" 2>/dev/null || true
    fi
}

descriptor_pins() {
    ref=$1
    text=$(blob_text "$ref" "$MOD_API_DESCRIPTOR")
    status=$(printf '%s\n' "$text" | sed -n 's/^currentStatus=//p')
    current=$(printf '%s\n' "$text" | sed -n 's/^currentApi=//p')
    published=$(printf '%s\n' "$text" | sed -n 's/^publishedBaselines=//p')
    descriptor_pins_ifs=$IFS
    IFS=,
    for version in $published; do
        [ -n "$version" ] && printf '%s%s.txt\n' "$MOD_API_PIN_PREFIX" "$version"
    done
    IFS=$descriptor_pins_ifs
    if [ "$status" = candidate ] && [ -n "$current" ]; then
        printf '%s%s.txt\n' "$MOD_API_PIN_PREFIX" "${current%.*}"
    fi
}

actual_pins() {
    ref=$1
    if [ "$ref" = INDEX ]; then
        git ls-files --cached | grep "^$MOD_API_PIN_PREFIX.*\.txt$" || true
    elif [ "$ref" != EMPTY ]; then
        git ls-tree -r --name-only "$ref" | grep "^$MOD_API_PIN_PREFIX.*\.txt$" || true
    fi
}

current_api_value() {
    blob_text "$1" "$MOD_API_VERSION" |
        sed -n '/^[[:space:]]*\(public[[:space:]]*\)\?static[[:space:]]*final.*CURRENT[[:space:]]*=/s/.*CURRENT[^=]*=[^(]*(\?"\([0-9][0-9.]*\)".*/\1/p' |
        head -n 1
}

contains_mod_api_annotation() {
    printf '%s\n' "$1" | grep -Eq '^[[:space:]]*@ModApi([.([:space:]]|$)'
}

validate_mod_api_coupling() {
    old_ref=$1
    new_ref=$2
    diff_args=$3
    status_file=$(mktemp)
    # shellcheck disable=SC2086 -- diff_args is an intentionally split revision argument.
    git diff --cached --name-status -M >"$status_file" 2>/dev/null || true
    if [ "$new_ref" != INDEX ]; then
        # shellcheck disable=SC2086
        git diff --name-status -M $diff_args >"$status_file"
    fi

    candidate=$(descriptor_pins "$new_ref" | awk -F/ '/mod-api-signatures-[0-9]+\.[0-9]+\.txt$/ { print; exit }')
    descriptor_changed=0 current_changed=0 api_delta=0 candidate_content=0 published_content=0 pin_structural=0 candidate_structural=0
    while IFS="$(printf '\t')" read -r status first second; do
        [ -n "$status" ] || continue
        paths=$first
        [ -n "${second:-}" ] && paths="$paths
$second"
        printf '%s\n' "$paths" | while IFS= read -r path; do :; done
        if printf '%s\n' "$paths" | grep -Fxq "$MOD_API_DESCRIPTOR"; then descriptor_changed=1; fi
        if printf '%s\n' "$paths" | grep -Fxq "$MOD_API_VERSION"; then
            old_current=$(current_api_value "$old_ref")
            new_current=$(current_api_value "$new_ref")
            [ "$old_current" != "$new_current" ] && current_changed=1
        fi
        if printf '%s\n' "$paths" | grep -q "^$MOD_API_PIN_PREFIX"; then
            case "$status" in
                M*)
                    if [ "$first" = "$candidate" ]; then candidate_content=1; else published_content=1; fi
                    ;;
                *)
                    pin_structural=1
                    if [ -n "$candidate" ] && { [ "$first" = "$candidate" ] || [ "${second:-}" = "$candidate" ]; }; then candidate_structural=1; fi
                    ;;
            esac
        fi
        case "$status" in
            A*) before=EMPTY; after=$new_ref ;;
            D*) before=$old_ref; after=EMPTY ;;
            R*) before=$old_ref; after=$new_ref ;;
            *) before=$old_ref; after=$new_ref ;;
        esac
        saved_ifs=$IFS
        IFS='
'
        for path in $paths; do
            case "$path" in *.java)
                old_text=$(blob_text "$before" "$path")
                new_text=$(blob_text "$after" "$path")
                if contains_mod_api_annotation "$old_text" || contains_mod_api_annotation "$new_text"; then
                    [ "$old_text" != "$new_text" ] && api_delta=1
                fi
            esac
        done
        IFS=$saved_ifs
    done <"$status_file"
    rm -f "$status_file"

    if [ "$current_changed" -eq 1 ] && { [ "$descriptor_changed" -ne 1 ] || { [ "$candidate_content" -ne 1 ] && [ "$candidate_structural" -ne 1 ]; }; }; then
        append_error "ModApiVersion.CURRENT changes require the release descriptor and its normalized signature-pin operation."
    fi
    if [ "$published_content" -eq 1 ]; then
        append_error "published full-version signature pins are immutable and may never be edited in place."
    fi
    if [ "$api_delta" -eq 1 ] && { [ -z "$candidate" ] || { [ "$candidate_content" -ne 1 ] && [ "$candidate_structural" -ne 1 ]; }; }; then
        append_error "detectable @ModApi surface changes require updating the current candidate signature pin."
    fi
    if [ "$candidate_content" -eq 1 ] && [ "$api_delta" -ne 1 ]; then
        append_error "candidate signature-pin content changes require a detectable @ModApi surface change; descriptor edits are not a substitute."
    fi
    if [ "$candidate_content" -eq 1 ] && [ "$descriptor_changed" -eq 1 ] && [ "$pin_structural" -ne 1 ]; then
        append_error "ordinary candidate signature regeneration must not edit the release descriptor."
    fi
    if [ "$pin_structural" -eq 1 ] && [ "$descriptor_changed" -ne 1 ]; then
        append_error "signature-pin additions, deletions, and renames require a descriptor publication or promotion edit."
    fi
    old_pins=$(descriptor_pins "$old_ref" | sort)
    new_pins=$(descriptor_pins "$new_ref" | sort)
    actual_old_pins=$(actual_pins "$old_ref" | sort)
    actual_new_pins=$(actual_pins "$new_ref" | sort)
    old_descriptor=$(blob_text "$old_ref" "$MOD_API_DESCRIPTOR")
    bootstrap_normalized=0
    if [ -z "$old_descriptor" ] && [ "$actual_old_pins" = "$new_pins" ] && [ "$actual_new_pins" = "$new_pins" ]; then bootstrap_normalized=1; fi
    if [ "$descriptor_changed" -eq 1 ] && [ "$old_pins" != "$new_pins" ] && [ "$pin_structural" -ne 1 ] && [ "$bootstrap_normalized" -ne 1 ]; then
        append_error "descriptor topology/status changes must include the normalized signature-pin add, delete, or rename implied by the new state."
    fi
    if { [ "$descriptor_changed" -eq 1 ] || [ "$pin_structural" -eq 1 ]; } && [ "$actual_new_pins" != "$new_pins" ]; then
        append_error "the resulting signature-pin inventory does not match the descriptor's normalized pin map."
    fi
}

staged_blob_size() {
    git cat-file -s ":$1" 2>/dev/null || true
}

commit_blob_size() {
    git cat-file -s "$1:$2" 2>/dev/null || true
}

staged_entry_mode() {
    git ls-files --stage -- ":(literal)$1" | awk '$3 == "0" { print $1; exit }'
}

commit_entry_mode() {
    git ls-tree "$1" -- ":(literal)$2" | awk '{ print $1; exit }'
}

staged_blob() {
    git cat-file blob ":$1"
}

commit_blob() {
    git cat-file blob "$1:$2"
}

is_protected_resource_path() {
    case "$1" in
        config.yaml|*.gen|docs/s1disasm|docs/s2disasm|docs/kis2disasm|docs/scddisasm|docs/skdisasm)
            return 0
            ;;
    esac
    return 1
}

is_absolute_link_target() {
    case "$1" in
        /*|[A-Za-z]:[\\/]*|\\\\*)
            return 0
            ;;
    esac
    return 1
}

is_root_scratch_path() {
    case "$1" in
        */*)
            return 1
            ;;
    esac
    case "$1" in
        MERGE-STATUS*.md|HANDOVER*.md)
            return 0
            ;;
    esac
    return 1
}

staged_blob_has_machine_local_home() {
    git grep --cached -I -q -E \
        -e "$POSIX_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$VAR_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$MACOS_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$WINDOWS_USERS_ROOT"'[\\/]+[^\\/$<%[:space:]][^\\/[:space:]]*[\\/]' \
        -- ":(literal)$1"
}

commit_blob_has_machine_local_home() {
    git grep -I -q -E \
        -e "$POSIX_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$VAR_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$MACOS_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$WINDOWS_USERS_ROOT"'[\\/]+[^\\/$<%[:space:]][^\\/[:space:]]*[\\/]' \
        "$1" -- ":(literal)$2"
}

staged_candidate_base() {
    merge_oid=$(merge_head_oid)
    if [ -n "$merge_oid" ]; then
        printf '%s\n' "$merge_oid"
        return
    fi
    git rev-parse -q --verify HEAD 2>/dev/null || printf '%s\n' "$EMPTY_TREE_OID"
}

baseline_has_path() {
    git cat-file -e "$1:$2" 2>/dev/null
}

staged_introduced_lines() {
    base=$1
    path=$2
    git diff --cached --no-ext-diff --unified=0 "$base" -- ":(literal)$path" |
        sed -n '/^+++ /d; /^+/s/^+//p'
}

commit_introduced_lines() {
    base=$1
    commit=$2
    path=$3
    git diff --no-ext-diff --unified=0 "$base" "$commit" -- ":(literal)$path" |
        sed -n '/^+++ /d; /^+/s/^+//p'
}

machine_local_home_lines() {
    grep -I -E \
        -e "$POSIX_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$VAR_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$MACOS_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
        -e "$WINDOWS_USERS_ROOT"'[\\/]+[^\\/$<%[:space:]][^\\/[:space:]]*[\\/]'
}

line_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        printf '%s' "$1" | sha256sum | awk '{ print $1 }'
        return
    fi
    if command -v shasum >/dev/null 2>&1; then
        printf '%s' "$1" | shasum -a 256 | awk '{ print $1 }'
        return
    fi
    return 2
}

stream_sha256() {
    if command -v sha256sum >/dev/null 2>&1; then
        sha256sum | awk '{ print $1 }'
        return
    fi
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 | awk '{ print $1 }'
        return
    fi
    return 2
}

grandfather_prefix_field() {
    field=$1
    path=$2
    awk -F '	' -v field="$field" -v path="$path" '
        $1 == "# baseline-prefix" && $4 == path { print $field }
    ' "$MACHINE_LOCAL_PATH_GRANDFATHER"
}

grandfather_prefix_is_valid() {
    source=$1
    commit=$2
    path=$3
    [ -f "$MACHINE_LOCAL_PATH_GRANDFATHER" ] || return 1
    length=$(grandfather_prefix_field 2 "$path")
    expected_hash=$(grandfather_prefix_field 3 "$path")
    case "$length" in
        ''|*[!0-9]*|0)
            return 1
            ;;
    esac
    case "$expected_hash" in
        *[!0-9a-f]*|'')
            return 1
            ;;
    esac
    [ "${#expected_hash}" -eq 64 ] || return 1

    if [ "$source" = "commit" ]; then
        size=$(commit_blob_size "$commit" "$path")
        [ "$size" -ge "$length" ] || return 1
        actual_hash=$(commit_blob "$commit" "$path" | head -c "$length" | stream_sha256)
    else
        size=$(staged_blob_size "$path")
        [ "$size" -ge "$length" ] || return 1
        actual_hash=$(staged_blob "$path" | head -c "$length" | stream_sha256)
    fi
    [ "$actual_hash" = "$expected_hash" ]
}

path_has_grandfather_prefix() {
    awk -F '	' -v path="$1" '
        $1 == "# baseline-prefix" && $4 == path { found = 1 }
        END { exit(found ? 0 : 1) }
    ' "$MACHINE_LOCAL_PATH_GRANDFATHER"
}

grandfather_allowance() {
    hash=$1
    path=$2
    awk -F '	' -v hash="$hash" -v path="$path" '
        $1 == hash && $3 == path { print $2 }
    ' "$MACHINE_LOCAL_PATH_GRANDFATHER"
}

final_blob_occurrence_count() {
    source=$1
    commit=$2
    path=$3
    line=$4
    if [ "$source" = "commit" ]; then
        commit_blob "$commit" "$path"
    else
        staged_blob "$path"
    fi |
        grep -F -x -c -- "$line" || true
}

grandfather_allows_line() {
    source=$1
    commit=$2
    path=$3
    line=$4
    [ -f "$MACHINE_LOCAL_PATH_GRANDFATHER" ] || return 1
    hash=$(line_sha256 "$line") || return 1
    allowance=$(grandfather_allowance "$hash" "$path")
    case "$allowance" in
        ''|*[!0-9]*|0)
            return 1
            ;;
    esac
    occurrences=$(final_blob_occurrence_count "$source" "$commit" "$path" "$line")
    case "$occurrences" in
        ''|*[!0-9]*)
            return 1
            ;;
    esac
    [ "$occurrences" -le "$allowance" ]
}

staged_introduced_lines_are_allowed() {
    base=$1
    path=$2
    staged_introduced_lines "$base" "$path" |
        machine_local_home_lines |
        while IFS= read -r line; do
            grandfather_allows_line staged "" "$path" "$line" || exit 1
        done
}

commit_introduced_lines_are_allowed() {
    base=$1
    commit=$2
    path=$3
    commit_introduced_lines "$base" "$commit" "$path" |
        machine_local_home_lines |
        while IFS= read -r line; do
            grandfather_allows_line commit "$commit" "$path" "$line" || exit 1
        done
}

staged_introduced_lines_have_machine_local_home() {
    staged_introduced_lines "$1" "$2" |
        grep -I -E \
            -e "$POSIX_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
            -e "$VAR_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
            -e "$MACOS_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
            -e "$WINDOWS_USERS_ROOT"'[\\/]+[^\\/$<%[:space:]][^\\/[:space:]]*[\\/]' \
            >/dev/null
}

commit_introduced_lines_have_machine_local_home() {
    commit_introduced_lines "$1" "$2" "$3" |
        grep -I -E \
            -e "$POSIX_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
            -e "$VAR_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
            -e "$MACOS_HOME_ROOT/"'[^/$<[:space:]][^/[:space:]]*/' \
            -e "$WINDOWS_USERS_ROOT"'[\\/]+[^\\/$<%[:space:]][^\\/[:space:]]*[\\/]' \
            >/dev/null
}

is_rom_like_path() {
    lower=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
    for extension in $ROM_LIKE_DENYLIST_EXTENSIONS; do
        case "$lower" in
            *"$extension")
                return 0
                ;;
        esac
    done
    return 1
}

effective_base_for_ci_pr() {
    base_sha=$1
    head_sha=$2
    base_ref=$3

    if [ "$base_ref" != "master" ]; then
        printf '%s\n' "$base_sha"
        return 0
    fi
    if [ -z "$RELEASE_TRAILER_CUTOVER_BASE" ]; then
        printf '%s\n' "$base_sha"
        return 0
    fi
    if ! git merge-base --is-ancestor "$RELEASE_TRAILER_CUTOVER_BASE" "$head_sha"; then
        die "release trailer cutover baseline $RELEASE_TRAILER_CUTOVER_BASE is not reachable from PR head $head_sha."
    fi
    if ! git merge-base --is-ancestor "$RELEASE_TRAILER_CUTOVER_BASE" "$base_sha"; then
        printf '%s\n' "$RELEASE_TRAILER_CUTOVER_BASE"
        return 0
    fi
    printf '%s\n' "$base_sha"
}

has_exact() {
    files=$1
    needle=$2
    has_exact_ifs=$IFS
    IFS='
'
    for path in $files; do
        if [ "$path" = "$needle" ]; then
            IFS=$has_exact_ifs
            return 0
        fi
    done
    IFS=$has_exact_ifs
    return 1
}

has_prefix() {
    files=$1
    prefix=$2
    has_prefix_ifs=$IFS
    IFS='
'
    for path in $files; do
        case "$path" in
            "$prefix"*)
                IFS=$has_prefix_ifs
                return 0
                ;;
        esac
    done
    IFS=$has_prefix_ifs
    return 1
}

trailer_value() {
    key=$1
    message=$2
    printf '%s\n' "$message" | awk -v key="$key" '
        index($0, key ":") == 1 {
            value = substr($0, length(key) + 2)
            sub(/^[[:space:]]+/, "", value)
            sub(/[[:space:]]+$/, "", value)
        }
        END {
            if (value != "") {
                print value
            }
        }
    '
}

decision_kind() {
    value=$(printf '%s' "$1" | tr '[:upper:]' '[:lower:]')
    case "$value" in
        updated|updated\ *|updated:*|updated-*)
            echo "updated"
            ;;
        n/a|n/a\ *|n/a:*|n/a-*)
            echo "na"
            ;;
        *)
            echo "invalid"
            ;;
    esac
}

print_commit_template() {
    cat <<'EOF' >&2
Use these trailers on non-master branch commits:

Changelog: updated|n/a
Guide: updated|n/a
Known-Discrepancies: updated|n/a
S3K-Known-Discrepancies: updated|n/a
Agent-Docs: updated|n/a
Configuration-Docs: updated|n/a
Skills: updated|n/a

If a trailer says `updated`, the matching files must be staged in the same commit.
EOF
}

append_error() {
    if [ -z "${ERRORS:-}" ]; then
        ERRORS="- $1"
    else
        ERRORS="${ERRORS}
- $1"
    fi
}

validate_content_candidates() {
    files=$1
    source=$2
    commit=${3:-}
    if [ "$source" = "commit" ]; then
        baseline=$(commit_parent_or_empty_tree "$commit")
    else
        baseline=$(staged_candidate_base)
    fi
    content_candidates_ifs=$IFS
    IFS='
'
    for path in $files; do
        [ -n "$path" ] || continue

        if is_root_scratch_path "$path"; then
            append_error "\`$path\` is a root-level merge/handover scratch artifact. Classify retained engineering material under \`docs/architecture/\`."
        fi

        if [ "$source" = "commit" ]; then
            mode=$(commit_entry_mode "$commit" "$path")
        else
            mode=$(staged_entry_mode "$path")
        fi
        if [ -z "$mode" ]; then
            append_error "\`$path\` could not be read from the ${source} candidate set."
            continue
        fi

        if [ "$mode" = "120000" ]; then
            if is_protected_resource_path "$path"; then
                append_error "\`$path\` is a generated worktree resource and must not be committed as a symlink."
            fi

            if [ "$source" = "commit" ]; then
                if ! target=$(commit_blob "$commit" "$path" 2>/dev/null); then
                    append_error "\`$path\` is a symlink whose committed target blob could not be read."
                    continue
                fi
            else
                if ! target=$(staged_blob "$path" 2>/dev/null); then
                    append_error "\`$path\` is a symlink whose staged target blob could not be read."
                    continue
                fi
            fi
            if is_absolute_link_target "$target"; then
                append_error "\`$path\` has an absolute symlink target. Use a repository-relative target or keep the link untracked."
            fi
        fi

        if baseline_has_path "$baseline" "$path"; then
            if path_has_grandfather_prefix "$path" &&
                    ! grandfather_prefix_is_valid "$source" "$commit" "$path"; then
                append_error "\`$path\` does not preserve its verified historic prefix byte-for-byte."
                continue
            fi
            if [ "$source" = "commit" ]; then
                introduced_allowed() {
                    commit_introduced_lines_are_allowed "$baseline" "$commit" "$path"
                }
            else
                introduced_allowed() {
                    staged_introduced_lines_are_allowed "$baseline" "$path"
                }
            fi
            if ! introduced_allowed; then
                append_error "\`$path\` contains a machine-local user-home path. Use a repository-relative path, environment variable, or neutral placeholder."
            fi
        else
            if [ "$source" = "commit" ]; then
                if commit_blob_has_machine_local_home "$commit" "$path"; then
                    grep_status=0
                else
                    grep_status=$?
                fi
            else
                if staged_blob_has_machine_local_home "$path"; then
                    grep_status=0
                else
                    grep_status=$?
                fi
            fi
            if [ "$grep_status" -eq 0 ]; then
                append_error "\`$path\` contains a machine-local user-home path. Use a repository-relative path, environment variable, or neutral placeholder."
            elif [ "$grep_status" -gt 1 ]; then
                append_error "\`$path\` could not be inspected for machine-local paths."
            fi
        fi
    done
    IFS=$content_candidates_ifs
}

validate_staged_content() {
    files=$(staged_candidates)
    ERRORS=""
    validate_file_size_policy "$files" staged
    validate_content_candidates "$files" staged
    if [ -n "$ERRORS" ]; then
        note "staged content violates the repository resource policy."
        echo "$ERRORS" >&2
        exit 1
    fi
}

validate_file_size_policy() {
    files=$1
    mode=$2
    commit=${3:-}
    file_size_policy_ifs=$IFS
    IFS='
'
    for path in $files; do
        if is_rom_like_path "$path"; then
            append_error "\`$path\` looks like a ROM/binary asset. Keep user-supplied ROMs and ROM-derived binary assets untracked."
        fi
        if [ "$mode" = "commit" ]; then
            size=$(commit_blob_size "$commit" "$path")
        else
            size=$(staged_blob_size "$path")
        fi
        if [ -z "$size" ]; then
            continue
        fi

        case "$path" in
            aux_state*.jsonl|physics*.csv|*/aux_state*.jsonl|*/physics*.csv)
                if [ "$size" -ge "$TRACE_COMPRESSION_THRESHOLD_BYTES" ]; then
                    append_error "\`$path\` is an uncompressed trace payload (${size} bytes). Commit the \`.gz\` instead: the native harness (tools/bizhawk-headless) compresses at capture time by default, and \`tools/traces/compress-traces.ps1\` does it for a Lua capture directory."
                fi
                ;;
        esac

        if [ "$size" -ge "$GITHUB_FILE_SIZE_LIMIT_BYTES" ]; then
            append_error "\`$path\` is ${size} bytes; GitHub rejects files >= ${GITHUB_FILE_SIZE_LIMIT_BYTES} bytes."
        fi
    done
    IFS=$file_size_policy_ifs
}

validate_exact_trailer() {
    message=$1
    files=$2
    key=$3
    path=$4
    label=$5

    value=$(trailer_value "$key" "$message")
    if [ -z "$value" ]; then
        append_error "Missing \`$key\` trailer."
        return
    fi

    kind=$(decision_kind "$value")
    changed=1
    if has_exact "$files" "$path"; then
        changed=0
    fi

    case "$kind" in
        updated)
            if [ "$changed" -ne 0 ]; then
                append_error "\`$key\` says updated, but \`$label\` is not staged."
            fi
            ;;
        na)
            if [ "$changed" -eq 0 ]; then
                append_error "\`$key\` says n/a, but \`$label\` is staged."
            fi
            ;;
        *)
            append_error "\`$key\` must start with \`updated\` or \`n/a\`."
            ;;
    esac
}

validate_prefix_trailer() {
    message=$1
    files=$2
    key=$3
    prefix=$4
    label=$5

    value=$(trailer_value "$key" "$message")
    if [ -z "$value" ]; then
        append_error "Missing \`$key\` trailer."
        return
    fi

    kind=$(decision_kind "$value")
    changed=1
    if has_prefix "$files" "$prefix"; then
        changed=0
    fi

    case "$kind" in
        updated)
            if [ "$changed" -ne 0 ]; then
                append_error "\`$key\` says updated, but \`$label\` has no staged changes."
            fi
            ;;
        na)
            if [ "$changed" -eq 0 ]; then
                append_error "\`$key\` says n/a, but \`$label\` has staged changes."
            fi
            ;;
        *)
            append_error "\`$key\` must start with \`updated\` or \`n/a\`."
            ;;
    esac
}

validate_agent_docs_trailer() {
    message=$1
    files=$2
    key="Agent-Docs"

    value=$(trailer_value "$key" "$message")
    if [ -z "$value" ]; then
        append_error "Missing \`$key\` trailer."
        return
    fi

    kind=$(decision_kind "$value")
    agents_changed=1
    claude_changed=1
    if has_exact "$files" "AGENTS.md"; then
        agents_changed=0
    fi
    if has_exact "$files" "CLAUDE.md"; then
        claude_changed=0
    fi

    case "$kind" in
        updated)
            if [ "$agents_changed" -ne 0 ] || [ "$claude_changed" -ne 0 ]; then
                append_error "\`Agent-Docs\` says updated, but both \`AGENTS.md\` and \`CLAUDE.md\` must be staged together."
            fi
            ;;
        na)
            if [ "$agents_changed" -eq 0 ] || [ "$claude_changed" -eq 0 ]; then
                append_error "\`Agent-Docs\` says n/a, but agent docs are staged."
            fi
            ;;
        *)
            append_error "\`Agent-Docs\` must start with \`updated\` or \`n/a\`."
            ;;
    esac
}

validate_skills_trailer() {
    message=$1
    files=$2
    key="Skills"

    value=$(trailer_value "$key" "$message")
    if [ -z "$value" ]; then
        append_error "Missing \`$key\` trailer."
        return
    fi

    kind=$(decision_kind "$value")
    agents_changed=1
    claude_changed=1
    if has_prefix "$files" ".agents/skills/"; then
        agents_changed=0
    fi
    if has_prefix "$files" ".claude/skills/"; then
        claude_changed=0
    fi

    case "$kind" in
        updated)
            if [ "$agents_changed" -ne 0 ] || [ "$claude_changed" -ne 0 ]; then
                append_error "\`Skills\` says updated, but both \`.agents/skills/\` and \`.claude/skills/\` must have staged changes."
            fi
            ;;
        na)
            if [ "$agents_changed" -eq 0 ] || [ "$claude_changed" -eq 0 ]; then
                append_error "\`Skills\` says n/a, but skill changes are staged."
            fi
            ;;
        *)
            append_error "\`Skills\` must start with \`updated\` or \`n/a\`."
            ;;
    esac
}

# A feat/fix/perf commit that touches engine source (src/main/) is almost always
# changelog-worthy. The base trailer gate only checks staged<->trailer consistency,
# so it cannot catch a wrong `Changelog: n/a`. This requires such commits to either
# set `Changelog: updated` or justify the skip with a reason, e.g. `Changelog: n/a: test-only helper`.
changelog_justified() {
    rest=$(printf '%s' "$1" | sed -E 's/^[[:space:]]*[nN]\/[aA]//')
    rest=$(printf '%s' "$rest" | sed -E 's/^[[:space:]:,_-]+//')
    rest=$(printf '%s' "$rest" | sed -E 's/[[:space:]]+$//')
    [ -n "$rest" ]
}

validate_changelog_justification() {
    message=$1
    files=$2

    subject=$(printf '%s\n' "$message" | sed -n '1p')
    case "$subject" in
        feat:*|feat\(*|feat!*|fix:*|fix\(*|fix!*|perf:*|perf\(*|perf!*) ;;
        *) return 0 ;;
    esac

    if ! has_prefix "$files" "src/main/"; then
        return 0
    fi

    value=$(trailer_value "Changelog" "$message")
    if [ -z "$value" ]; then
        return 0
    fi

    if [ "$(decision_kind "$value")" != "na" ]; then
        return 0
    fi

    if ! changelog_justified "$value"; then
        append_error "\`Changelog\` is \`n/a\` on a \`${subject%%:*}\` commit touching \`src/main/\`. Set \`Changelog: updated\` (and stage CHANGELOG.md) or justify the skip, e.g. \`Changelog: n/a: <reason>\`."
    fi
}

validate_non_master_commit_message() {
    message=$1
    files=$2
    ERRORS=""

    validate_file_size_policy "$files" staged
    validate_exact_trailer "$message" "$files" "Changelog" "CHANGELOG.md" "CHANGELOG.md"
    validate_changelog_justification "$message" "$files"
    validate_prefix_trailer "$message" "$files" "Guide" "docs/guide/" "docs/guide/"
    validate_exact_trailer "$message" "$files" "Known-Discrepancies" "docs/status/known-discrepancies.md" "docs/status/known-discrepancies.md"
    validate_exact_trailer "$message" "$files" "S3K-Known-Discrepancies" "docs/S3K_KNOWN_DISCREPANCIES.md" "docs/S3K_KNOWN_DISCREPANCIES.md"
    validate_agent_docs_trailer "$message" "$files"
    validate_exact_trailer "$message" "$files" "Configuration-Docs" "CONFIGURATION.md" "CONFIGURATION.md"
    validate_skills_trailer "$message" "$files"
    validate_mod_api_coupling HEAD INDEX cached

    if [ -n "$ERRORS" ]; then
        note "non-master branch commits must declare the documentation/discrepancy policy explicitly."
        echo "$ERRORS" >&2
        print_commit_template
        exit 1
    fi
}

validate_merge_into_develop() {
    branch=$(current_branch)
    if [ "$branch" != "develop" ]; then
        return 0
    fi

    if ! is_merge_in_progress; then
        return 0
    fi

    if is_merge_from_master; then
        return 0
    fi

    # The README release section is a handful of version themes, not a change
    # log. A merge touches it only when it adds or changes a theme, which is a
    # review judgement rather than a staged-file check, so no file is required.
    return 0
}

prepare_commit_message() {
    msg_file=$1
    source=${2:-}

    if [ "$(current_branch)" = "master" ]; then
        return 0
    fi

    case "$source" in
        merge|squash)
            return 0
            ;;
    esac

    if is_merge_in_progress; then
        return 0
    fi

    message=$(cat "$msg_file")

    append_block=""
    for key in \
        "Changelog" \
        "Guide" \
        "Known-Discrepancies" \
        "S3K-Known-Discrepancies" \
        "Agent-Docs" \
        "Configuration-Docs" \
        "Skills"
    do
        if [ -z "$(trailer_value "$key" "$message")" ]; then
            if [ -z "$append_block" ]; then
                append_block="$key: TODO"
            else
                append_block="${append_block}
$key: TODO"
            fi
        fi
    done

    if [ -z "$append_block" ]; then
        return 0
    fi

    {
        printf '\n'
        printf '%s\n' "$append_block"
    } >>"$msg_file"
}

validate_commit_msg_hook() {
    msg_file=$1
    branch=$(current_branch)

    validate_staged_content

    if [ "$branch" = "master" ]; then
        return 0
    fi

    if is_merge_in_progress; then
        validate_merge_into_develop
        return 0
    fi

    message=$(cat "$msg_file")
    files=$(staged_files)
    validate_non_master_commit_message "$message" "$files"
}

PUBLISHED_HISTORY_REFS="refs/remotes/origin/develop
refs/remotes/origin/master"

is_published_history() {
    published_history_ifs=$IFS
    IFS='
'
    for ref in $PUBLISHED_HISTORY_REFS; do
        [ -n "$ref" ] || continue
        git rev-parse -q --verify "$ref" >/dev/null 2>&1 || continue
        if git merge-base --is-ancestor "$1" "$ref" 2>/dev/null; then
            IFS=$published_history_ifs
            return 0
        fi
    done
    IFS=$published_history_ifs
    return 1
}

validate_commit_content() {
    commit=$1
    # Commits already published on a protected branch were accepted there.
    # Merging that history forward must not re-litigate it.
    if is_published_history "$commit"; then
        return 0
    fi
    files=$(commit_candidates "$commit")
    ERRORS=""
    validate_file_size_policy "$files" commit "$commit"
    validate_content_candidates "$files" commit "$commit"
    if [ -n "$ERRORS" ]; then
        note "commit $commit violates the repository resource policy."
        echo "$ERRORS" >&2
        exit 1
    fi
}

validate_tip_tree_links() {
    tip=$1
    if ! git cat-file -e "$tip^{commit}" 2>/dev/null; then
        die "required pushed tip $tip is not available as a commit."
    fi
    if ! canonical_tip=$(git rev-parse "$tip^{commit}" 2>/dev/null); then
        die "could not resolve delivered tip $tip to its full object id."
    fi
    expected_oid_length=${#canonical_tip}

    if ! tree_entries=$(git ls-tree -r "$tip"); then
        die "could not enumerate delivered tip tree $tip."
    fi
    ERRORS=""
    tip_tree_links_ifs=$IFS
    IFS='
'
    for entry in $tree_entries; do
        [ -n "$entry" ] || continue
        case "$entry" in
            *"	"*)
                metadata=${entry%%	*}
                path=${entry#*	}
                ;;
            *)
                die "delivered tip tree $tip contains a malformed entry: $entry"
                ;;
        esac
        mode=${metadata%% *}
        remaining_metadata=${metadata#* }
        object_type=${remaining_metadata%% *}
        object_oid=${remaining_metadata#* }
        if [ "$mode" = "$metadata" ] ||
            [ "$object_type" = "$remaining_metadata" ] ||
            [ -z "$object_oid" ] ||
            [ -z "$path" ]; then
            die "delivered tip tree $tip contains malformed metadata for $path."
        fi
        case "$object_oid" in
            *[!0-9a-f]*)
                die "delivered tip tree $tip contains a malformed object id for $path."
                ;;
        esac
        if [ "${#object_oid}" -ne "$expected_oid_length" ]; then
            die "delivered tip tree $tip contains a truncated object id for $path."
        fi
        case "$mode:$object_type" in
            100644:blob|100755:blob|160000:commit)
                continue
                ;;
            120000:blob)
                ;;
            *)
                die "delivered tip tree $tip contains unsupported metadata \`$metadata\` for $path."
                ;;
        esac

        if is_protected_resource_path "$path"; then
            append_error "\`$path\` is a generated worktree resource symlink in delivered tip $tip."
        fi
        if ! target=$(git cat-file blob "$object_oid" 2>/dev/null); then
            append_error "\`$path\` is a symlink whose delivered target blob could not be read."
            continue
        fi
        if is_absolute_link_target "$target"; then
            append_error "\`$path\` has an absolute symlink target in delivered tip $tip."
        fi
    done
    IFS=$tip_tree_links_ifs

    if [ -n "$ERRORS" ]; then
        note "delivered tip $tip violates the repository resource policy."
        echo "$ERRORS" >&2
        exit 1
    fi
}

validate_content_commit_list() {
    content_list_commits=$1
    tip=$2
    # The reviewed release snapshot bounds inherited history. Audit every
    # delivered entry once, then retain the existing per-commit checks for
    # every subsequent change (even one removed again before the tip).
    set -- "$RESOURCE_POLICY_CUTOVER"
    content_audit_snapshot=0
    if git merge-base --is-ancestor "$RELEASE_RESOURCE_BASELINE" "$tip" 2>/dev/null; then
        set -- "$RELEASE_RESOURCE_BASELINE"
        content_audit_snapshot=1
    fi
    if git merge-base --is-ancestor "$NEXT_ROLLOVER_RESOURCE_BASELINE" "$tip" 2>/dev/null; then
        set -- "$@" "$NEXT_ROLLOVER_RESOURCE_BASELINE"
        content_audit_snapshot=1
    fi
    if [ "$content_audit_snapshot" -eq 1 ]; then
        if command -v python3 >/dev/null 2>&1; then
            audit_python=python3
        elif command -v python >/dev/null 2>&1; then
            audit_python=python
        else
            die "Python 3 is required for the release snapshot audit."
        fi
        "$audit_python" "$POLICY_DIR/audit-release-tree.py" "$tip" ||
            die "release snapshot audit rejected $tip."
    fi
    for content_cutover in "$@"; do
        if git merge-base --is-ancestor "$content_cutover" "$tip" 2>/dev/null; then
            legacy_commits=$(git rev-list "$content_cutover") ||
                die "could not enumerate reviewed resource history."
            content_list_commits=$(printf '%s\n' "$legacy_commits" -- "$content_list_commits" |
                awk '$0 == "--" { incoming = 1; next }
                     !incoming { legacy[$0] = 1; next }
                     !($0 in legacy) { print }')
        fi
    done
    content_commit_list_ifs=$IFS
    IFS='
'
    for commit in $content_list_commits; do
        [ -n "$commit" ] || continue
        if ! git cat-file -e "$commit^{commit}" 2>/dev/null; then
            die "required pushed commit $commit is not available."
        fi
        validate_commit_content "$commit"
    done
    IFS=$content_commit_list_ifs
    validate_tip_tree_links "$tip"
}

commits_in_range() {
    base=$1
    head=$2
    if ! git cat-file -e "$base^{commit}" 2>/dev/null; then
        die "required range base $base is not available as a commit."
    fi
    if ! git cat-file -e "$head^{commit}" 2>/dev/null; then
        die "required range head $head is not available as a commit."
    fi
    if [ -n "$RELEASE_RANGE_BASE" ]; then
        git rev-list --reverse "$base..$head" "^$RELEASE_RANGE_BASE" ||
            die "could not enumerate release range $base..$head excluding $RELEASE_RANGE_BASE."
        return
    fi
    git rev-list --reverse "$base..$head" ||
        die "could not enumerate commit range $base..$head."
}

validate_content_range() {
    base=$1
    head=$2
    commits=$(commits_in_range "$base" "$head")
    validate_content_commit_list "$commits" "$head"
}

validate_ci_pr() {
    base_sha=$1
    head_sha=$2
    base_ref=$3
    head_ref=$4

    if [ "$base_ref" != "next" ] && [ "$base_ref" != "develop" ] && [ "$base_ref" != "master" ]; then
        return 0
    fi

    if [ "$base_ref" = "master" ]; then
        RELEASE_RANGE_BASE=$base_sha
    fi
    effective_base=$(effective_base_for_ci_pr "$base_sha" "$head_sha" "$base_ref")
    range_files=$(git diff --name-only --diff-filter=ACMRD "$effective_base...$head_sha")

    if [ "$base_ref" = "develop" ]; then
        # No README.md requirement: the release section changes only when a
        # version theme does, and that is judged in review, not by file presence.
        if [ "$head_ref" = "master" ]; then
            validate_content_range "$effective_base" "$head_sha"
            return 0
        fi
    fi

    validate_ci_commit_range "$effective_base" "$head_sha"
}

validate_ci_commit_range() {
    effective_base=$1
    head_sha=$2

    commits=$(commits_in_range "$effective_base" "$head_sha")
    # Trailer debt never changes the independent resource-policy boundary.
    if [ -n "$RELEASE_RANGE_BASE" ]; then
        release_content_commits=$(commits_in_range "$RELEASE_RANGE_BASE" "$head_sha")
        validate_content_commit_list "$release_content_commits" "$head_sha"
    else
        validate_content_commit_list "$commits" "$head_sha"
    fi

    trailer_commits=$commits
    for trailer_baseline in "$NEXT_ROLLOVER_TRAILER_BASELINE" "$PUBLISHED_RELEASE_TRAILER_BASELINE"; do
        if git merge-base --is-ancestor "$trailer_baseline" "$head_sha" 2>/dev/null; then
            reviewed_trailer_commits=$(git rev-list "$trailer_baseline") ||
                die "could not enumerate reviewed rollover trailer history."
            trailer_commits=$(printf '%s\n' "$reviewed_trailer_commits" -- "$trailer_commits" |
                awk '$0 == "--" { incoming = 1; next }
                     !incoming { reviewed[$0] = 1; next }
                     !($0 in reviewed) { print }')
        fi
    done
    for commit in $trailer_commits; do
        parent_line=$(git rev-list --parents -n 1 "$commit")
        set -- $parent_line
        if [ "$#" -gt 2 ]; then
            continue
        fi

        message=$(git show -s --format=%B "$commit")
        files=$(commit_candidates "$commit")
        ERRORS=""

        validate_exact_trailer "$message" "$files" "Changelog" "CHANGELOG.md" "CHANGELOG.md"
        validate_changelog_justification "$message" "$files"
        validate_prefix_trailer "$message" "$files" "Guide" "docs/guide/" "docs/guide/"
        validate_exact_trailer "$message" "$files" "Known-Discrepancies" "docs/status/known-discrepancies.md" "docs/status/known-discrepancies.md"
        validate_exact_trailer "$message" "$files" "S3K-Known-Discrepancies" "docs/S3K_KNOWN_DISCREPANCIES.md" "docs/S3K_KNOWN_DISCREPANCIES.md"
        validate_agent_docs_trailer "$message" "$files"
        validate_exact_trailer "$message" "$files" "Configuration-Docs" "CONFIGURATION.md" "CONFIGURATION.md"
        validate_skills_trailer "$message" "$files"
        parent=$(git rev-parse "$commit^")
        validate_mod_api_coupling "$parent" "$commit" "$parent $commit"

        if [ -n "$ERRORS" ]; then
            note "commit $commit violates the non-master branch documentation policy."
            echo "$ERRORS" >&2
            print_commit_template
            exit 1
        fi
    done
}

validate_pre_push() {
    remote_name=${1:-}
    if [ -z "$remote_name" ] || ! git remote get-url "$remote_name" >/dev/null 2>&1; then
        die "pre-push could not resolve remote name \`${remote_name:-<empty>}\`; refusing to guess the published-history boundary."
    fi

    while IFS=' ' read -r local_ref local_oid remote_ref remote_oid; do
        [ -n "${local_ref:-}" ] || continue
        if [ "${local_oid:-}" = "$ALL_ZERO_OID" ]; then
            continue
        fi
        if ! git cat-file -e "$local_oid^{commit}" 2>/dev/null; then
            die "required local object $local_oid for $local_ref is not available as a commit."
        fi

        if [ "${remote_oid:-}" = "$ALL_ZERO_OID" ]; then
            commits=$(git rev-list --reverse "$local_oid" --not --remotes="$remote_name") ||
                die "could not enumerate unpublished commits for new ref $remote_ref."
            validate_content_commit_list "$commits" "$local_oid"
            continue
        fi

        if ! git cat-file -e "$remote_oid^{commit}" 2>/dev/null; then
            die "required remote object $remote_oid for $remote_ref is not available as a commit."
        fi
        validate_content_range "$remote_oid" "$local_oid"
    done
}

validate_ci_new_ref() {
    after_sha=$1
    if ! git cat-file -e "$RESOURCE_POLICY_CUTOVER^{commit}" 2>/dev/null; then
        die "resource-policy cutover $RESOURCE_POLICY_CUTOVER is not available as a commit."
    fi
    if ! git cat-file -e "$after_sha^{commit}" 2>/dev/null; then
        die "required pushed tip $after_sha is not available as a commit."
    fi
    if ! git merge-base --is-ancestor "$RESOURCE_POLICY_CUTOVER" "$after_sha"; then
        die "resource-policy cutover $RESOURCE_POLICY_CUTOVER is not an ancestor of new-ref tip $after_sha."
    fi
    validate_content_range "$RESOURCE_POLICY_CUTOVER" "$after_sha"
}

validate_ci_push() {
    before_sha=$1
    after_sha=$2
    ref_name=$3

    if [ "$before_sha" = "$ALL_ZERO_OID" ]; then
        validate_ci_new_ref "$after_sha"
        return 0
    fi

    if [ "$ref_name" = "master" ]; then
        RELEASE_RANGE_BASE=$before_sha
        before_sha=$(effective_base_for_ci_pr "$before_sha" "$after_sha" master)
    fi
    if [ "$ref_name" = "next" ] || [ "$ref_name" = "develop" ] || [ "$ref_name" = "master" ]; then
        validate_ci_commit_range "$before_sha" "$after_sha"
        return 0
    fi

    validate_content_range "$before_sha" "$after_sha"
}

mode=${1:-}

case "$mode" in
    prepare-commit-msg)
        prepare_commit_message "$2" "${3:-}"
        ;;
    commit-msg)
        validate_commit_msg_hook "$2"
        ;;
    pre-commit)
        validate_staged_content
        ;;
    pre-push)
        validate_pre_push "${2:-}"
        ;;
    pre-merge-commit)
        validate_merge_into_develop
        ;;
    ci-pr)
        validate_ci_pr "$2" "$3" "$4" "$5"
        ;;
    ci-push)
        validate_ci_push "$2" "$3" "$4"
        ;;
    *)
        die "usage: $0 {prepare-commit-msg|pre-commit|commit-msg|pre-merge-commit|pre-push|ci-pr|ci-push} ..."
        ;;
esac
