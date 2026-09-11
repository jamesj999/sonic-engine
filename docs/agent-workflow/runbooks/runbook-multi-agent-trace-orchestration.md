# Runbook — Multi-Agent Trace Orchestration Loop (lead)

This runbook documents the **lead/orchestrator** loop for running a fleet of
trace-replay bug-fixing agents continuously. It sits one layer above
[`runbook-trace-divergence.md`](runbook-trace-divergence.md) (which is "how one
agent fixes one trace") and above the `trace-replay-bug-fixing` skill. For the
fleet-scale decision history see
[`../trace-green-fleet-decisions.md`](../trace-green-fleet-decisions.md).

## Purpose

Advance the `*TraceReplay` frontiers (engine vs recorded BizHawk ROM traces)
across all three games as fast as the fixes can be verified, **banking every
net-positive win** and never stalling on a single hard root. The standing
operating mode is *continuous*: there is a deep backlog and the point is to keep
grinding it, not to reach a fixed stopping point.

## The loop

```
        ┌────────────────────────────────────────────────────────────┐
        │ 1. SURVEY    fleet/agent triages frontiers (TraceTriageTool, │
        │              isolated first-error frames). Classify each     │
        │              first divergence (object-local / sidekick-1px / │
        │              camera / phase-timing / boss / RNG).            │
        │ 2. ASSIGN    give each agent a tractable target (see         │
        │              "Targeting"). One owner per cluster; no two     │
        │              agents on the same shared file.                 │
        │ 3. FIX       agent runs the per-trace loop (diagnose → ROM-  │
        │              cite → narrowest-scope fix → broad sweep).      │
        │ 4. GATE      agent self-checks NET-POSITIVE before claiming. │
        │ 5. VERIFY    lead independently checks the branch: commit    │
        │              content (`git show --stat`), merge-base, AND    │
        │              the UNIT tests for any shared-code change.      │
        │ 6. MERGE     net-positive → merge to develop + README        │
        │              release-log + push. Else → bounce back/revert.  │
        │ 7. REASSIGN  immediately feed the agent a fresh target.      │
        └───────────────────────────────┬────────────────────────────┘
                                         └── repeat, no "should we wrap?" ──┐
                                                                            └►
```

There is **no consolidation/wrap step**. After a merge, reassign immediately.

## Targeting (what to feed agents)

Prefer the *shallowest* frontier whose first divergence is **object-local** — the
object's position matches ROM but the contact/seat/landing/state/respawn outcome
differs. This class is tractable, low-blast-radius, and ROM-citable (the recurring
wins: inclusive-right-edge `bhi`, top-landing band, spawn-frame skip, frame-counter
source, platform exit re-seat).

**Defer (document, don't grind)** the deep classes:
- **phase-timing** — object phase not ROM-aligned (oscillation-counter seed/gate,
  placement/init-frame materialization). High-leverage but shared/broad.
- **CPU-Tails-follow 1px** — sidekick AI sub-pixel; trace-only suite, easy to regress.
- **camera vsettle** — boundary-accel transition; frequently non-greenable.
- **RNG / boss-AI sub-frame** — needs BizHawk register traces, deep.

When the shallow object-local backlog is **exhausted** (all three games surveyed,
every frontier deep), the heuristic flips: stop skimming and put each agent on the
**highest-leverage deep CLUSTER** (one owner each, independent code). Cracking one
cluster unblocks a whole family of frontiers — that is still "bank solid wins,"
just bigger and multi-turn. Require a **plan-first** report for any shared change
with real regression surface before the build starts.

## The net-positive gate (non-negotiable)

A "win" is **only** a win if ALL hold:
- the target frontier's first-error frame advances (or the trace greens);
- the must-keep-green set stays green (GHZ2, SYZ2, and the must-keep-green S3K:
  AizSkipHeadless, LevelLoading, Bootstrap, DecodingUtils);
- a **broad cross-game sweep** shows zero regressions; AND
- **the UNIT tests for any shared/physics/contact/lifecycle code you touched pass.**
  The trace sweep alone misses unit regressions — this has bitten us (a ceiling fix
  greened traces but broke `testCalcRoomOverHead…`). Validate units independently of
  the agent's report.

Any regression → **revert**. No zone/route/frame carve-outs, never `if gameId==`,
never hydrate engine state from the trace per-frame (frame-0 bootstrap only).

## Agent lifecycle

- **Spawn** fresh agents with a self-contained prompt (mission rules, the gate, the
  tooling traps below, hygiene, and the focus region). See
  [`../delegation-prompt-templates.md`](../delegation-prompt-templates.md).
- **Respawn** an agent that is killed (rate limits) or stuck in a stale
  cross-session task-queue loop — terminate (`shutdown_request`) and start fresh
  from its memory notes. Don't try to nurse a wedged agent.
- **One owner per shared file.** Two agents editing the same shared resolver/bootstrap
  will collide. Split by game/region/cluster.
- `isolation:"worktree"` is **unreliable** here — agents land in sibling/shared
  worktrees and branch off each other. ALWAYS verify each incoming branch's actual
  commit content + merge-base before merging; never trust the isolation flag.

## Model routing and escalation

The conductor chooses every child route before launch; an active worker cannot
reroute itself. Terra is the default for mechanical and object-local work, while
Sol owns shared/deep work and defined escalation. Record requested and (when
available) actual routes, reasons, cumulative attempts, and nullable telemetry;
never estimate unavailable token or duration values.

| Stage or classification | Exact route |
| --- | --- |
| Discovery | `gpt-5.6-terra/low` |
| Triage | `gpt-5.6-terra/medium` |
| Narrow object-local fix | `gpt-5.6-terra/medium` |
| Shared/deep fix | `gpt-5.6-sol/high` |
| Ordinary verify | `gpt-5.6-terra/medium` |
| Verify after Sol, a shared edit, disputed ROM evidence, or escalation | `gpt-5.6-sol/high` |

Classify shared runtime physics, collision, sidekick, camera, oscillation,
bootstrap, object lifecycle, recorder/publication work, and cross-game semantics
directly to Sol. Terra Triage escalates once to Sol for multiple owners, low
confidence, unresolved ownership, or missing ROM basis. Sol Triage without ROM
basis blocks Fix. Terra narrow Fix stops after two unsuccessful attempts and
hands off once to Sol for one final attempt when there is no frontier advance,
context contradiction, a newly discovered shared/cross-game surface, recorder
evidence need, causal-thread loss, or insufficient reasoning. Never change
effort in place or escalate a stage twice.

Route Verify directly to Sol after Sol, shared, disputed, or escalated work. A
Terra-detected regression is a Sol handoff for independent repeat verification.
An unavailable required Sol route or a failed Sol worker blocks the stage; never
silently fall back. The lead retains sole worktree ownership between sequential
workers: end Terra ownership before launching Sol, retain only ROM-backed edits
listed in `filesTouched`, and let Sol review that retained diff before its one
attempt. Workers never own one worktree concurrently.

### Reproducible routing benchmark

Use the frozen protocol in
[`trace-model-routing-benchmark.json`](../../architecture/validation/trace/trace-model-routing-benchmark.json).
Run every case/policy in a newly-created clean worktree; verify the ROM SHA-1
and fixture SHA-256 values first. Store the result at
`target/trace-model-routing/<policy>/<case>.json` and its patch at the same path
with `.patch`. The parent commit pins the recorded first error as benchmark input.

Retain results and patches by default. Before normal `git worktree remove`,
restore only benchmark-owned enumerated files and confirm `git status --short`
is empty. Never reset, `git clean`, or force-remove a dirty benchmark worktree:
it is evidence to retain and diagnose, not permission to discard work.

Use this lifecycle verbatim for one `<policy>` / `<case>` pair. It creates a
dedicated branch and worktree from the case's immutable parent; it does not
switch the lead checkout. It snapshots both tracked and untracked baseline
state before the worker starts. Hook-created disassembly links and foreign files
are baseline state, never benchmark-owned files.

Before starting, set `BENCH_ARCHIVE_ROOT` to an existing disk-backed directory outside the
repository and verify its capacity. The recipe creates one uniquely named child there for
benchmark evidence; it never writes durable evidence to `/tmp`.

```bash
set -euo pipefail

BENCH_ROOT=$(git rev-parse --show-toplevel)
BENCH_POLICY=<policy>
BENCH_CASE=<case>
: "${BENCH_ARCHIVE_ROOT:?set BENCH_ARCHIVE_ROOT to a disk-backed external archive directory}"
test -d "$BENCH_ARCHIVE_ROOT"
BENCH_MANIFEST="$BENCH_ROOT/docs/architecture/validation/trace/trace-model-routing-benchmark.json"
# Fail closed before allocating retention, a branch, or a worktree. An enabled
# policy may contain only routes supported by this runbook.
jq -e --arg policy "$BENCH_POLICY" --arg case "$BENCH_CASE" '
  ([.policies[] | select(.name == $policy)] | length) == 1 and
  ([.cases[] | select(.id == $case)] | length) == 1 and
  (.policies[] | select(.name == $policy) |
   .enabled == true and all(.routes[][]; test("^gpt-5\\.6-(terra|sol)/(low|medium|high)$")))
' "$BENCH_MANIFEST" >/dev/null
BENCH_BASE=$(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .baseCommit' "$BENCH_MANIFEST")
BENCH_RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-$$"
BENCH_BRANCH="feature/ai-trace-model-benchmark-${BENCH_POLICY}-${BENCH_CASE}-${BENCH_RUN_ID}"
BENCH_WORKTREE="$BENCH_ROOT/.worktrees/trace-model-routing-${BENCH_POLICY}-${BENCH_CASE}-${BENCH_RUN_ID}"
BENCH_RESULT="$BENCH_WORKTREE/target/trace-model-routing/${BENCH_POLICY}/${BENCH_CASE}.json"
BENCH_PATCH="${BENCH_RESULT%.json}.patch"
BENCH_RESULT_REL="target/trace-model-routing/${BENCH_POLICY}/${BENCH_CASE}.json"
BENCH_PATCH_REL="${BENCH_RESULT_REL%.json}.patch"
BENCH_RETAIN="$BENCH_ARCHIVE_ROOT/benchmark-${BENCH_POLICY}-${BENCH_CASE}-${BENCH_RUN_ID}"
mkdir -m 700 "$BENCH_RETAIN"
BENCH_OWNED="$BENCH_RETAIN/owned-files"
BENCH_BASELINE_TRACKED="$BENCH_RETAIN/baseline-tracked"
BENCH_BASELINE_UNTRACKED="$BENCH_RETAIN/baseline-untracked"
BENCH_NEW_UNTRACKED="$BENCH_RETAIN/new-untracked"
BENCH_OWNED_TRACKED="$BENCH_RETAIN/owned-tracked"
BENCH_OWNED_NEW="$BENCH_RETAIN/owned-new"
BENCH_COMPLETE_TRACKED="$BENCH_RETAIN/complete-tracked"
BENCH_COMPLETE_OWNED="$BENCH_RETAIN/complete-owned"
BENCH_TEMP_INDEX="$BENCH_RETAIN/result-tree.index"

git cat-file -e "${BENCH_BASE}^{commit}"
test ! -e "$BENCH_WORKTREE"
git worktree add -b "$BENCH_BRANCH" "$BENCH_WORKTREE" "$BENCH_BASE"
git -C "$BENCH_WORKTREE" status --porcelain --untracked-files=no > "$BENCH_BASELINE_TRACKED"
git -C "$BENCH_WORKTREE" ls-files --others --exclude-standard | sort > "$BENCH_BASELINE_UNTRACKED"
test ! -s "$BENCH_BASELINE_TRACKED"
```

In that worktree, verify the exact input before invoking the manifest's
`targetCommand` with `<ROM_PATH>` replaced by the supplied ROM. The fixture
loop checks the bytes at the pinned parent, not the moving benchmark tree.

```bash
ROM_PATH=<absolute-user-supplied-ROM-path>
test "$(sha1sum "$ROM_PATH" | cut -d' ' -f1 | tr '[:lower:]' '[:upper:]')" = "$(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .rom.sha1' "$BENCH_MANIFEST")"
jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .fixtures[] | [.path,.sha256] | @tsv' "$BENCH_MANIFEST" |
while IFS=$'\t' read -r path expected; do
  test "$(git -C "$BENCH_WORKTREE" show "${BENCH_BASE}:${path}" | sha256sum | cut -d' ' -f1)" = "$expected"
done
```

The pinned target is intentionally red at its historical parent. Run it inside
an `if`-guarded child shell so the outer fail-closed lifecycle can capture that
expected non-zero status without disabling `errexit`. The child receives the
ROM path through its environment; only the manifest's `<ROM_PATH>` placeholder
is replaced. A non-zero command is accepted only when Surefire proves that the
one requested test ran as a failure (not an error or skip) and the freshly
written trace report has the manifest's exact first frontier. Preserve the
combined command output, status, Surefire report, and trace report outside the
worktree before continuing.

```bash
BENCH_TEST_CLASS=$(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .testClass' "$BENCH_MANIFEST")
BENCH_EXPECTED_FRAME=$(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .startingFrontier.frame' "$BENCH_MANIFEST")
BENCH_EXPECTED_FIELD=$(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .startingFrontier.field' "$BENCH_MANIFEST")
BENCH_TARGET_TEMPLATE=$(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .targetCommand' "$BENCH_MANIFEST")
BENCH_TARGET_COMMAND="${BENCH_TARGET_TEMPLATE//<ROM_PATH>/\$ROM_PATH}"
BENCH_TARGET_OUTPUT="$BENCH_RETAIN/target-command.log"
BENCH_TARGET_STATUS_FILE="$BENCH_RETAIN/target-command.status"
BENCH_TARGET_REPORT="$BENCH_WORKTREE/target/surefire-reports/${BENCH_TEST_CLASS}.txt"
BENCH_TRACE_REPORT_DIR="$BENCH_WORKTREE/target/trace-reports"

test "$BENCH_TARGET_COMMAND" != "$BENCH_TARGET_TEMPLATE"
test ! -e "$BENCH_TARGET_REPORT"
test ! -e "$BENCH_TRACE_REPORT_DIR"
if (
  cd "$BENCH_WORKTREE"
  export ROM_PATH
  bash -o pipefail -c "$BENCH_TARGET_COMMAND"
) >"$BENCH_TARGET_OUTPUT" 2>&1; then
  BENCH_TARGET_STATUS=0
else
  BENCH_TARGET_STATUS=$?
fi
printf '%s\n' "$BENCH_TARGET_STATUS" > "$BENCH_TARGET_STATUS_FILE"

test "$BENCH_TARGET_STATUS" -ne 0
grep -Fq "Tests run: 1, Failures: 1, Errors: 0, Skipped: 0," "$BENCH_TARGET_REPORT"
mapfile -t BENCH_TRACE_REPORTS < <(find "$BENCH_TRACE_REPORT_DIR" -maxdepth 1 -type f -name '*_report.json' -print)
test "${#BENCH_TRACE_REPORTS[@]}" -eq 1
BENCH_TRACE_REPORT="${BENCH_TRACE_REPORTS[0]}"
jq -e --argjson frame "$BENCH_EXPECTED_FRAME" --arg field "$BENCH_EXPECTED_FIELD" '
  (.bootstrap | length) == 0 and
  (.errors | length) > 0 and
  .errors[0].start_frame == $frame and
  .errors[0].field == $field
' "$BENCH_TRACE_REPORT" >/dev/null
cp "$BENCH_TARGET_REPORT" "$BENCH_RETAIN/target-surefire-report.txt"
cp "$BENCH_TRACE_REPORT" "$BENCH_RETAIN/target-trace-report.json"
```

Before workers run, initialize a case- and policy-specific result from the
manifest. This does not copy the illustrative result template. It records only
the initial route for each stage; workers replace pending values with observed
route fields and actual results. Before final validation, delete stages that
never started and replace every retained `pending` status. Narrow Fix starts at
the policy's first narrow route, shared/deep Fix at its shared route, and
shared/deep Verify at its Sol verification route. Nullable runtime telemetry
may remain null when the runtime did not expose it; that does not make a stage
pending.

```bash
mkdir -p "$(dirname "$BENCH_RESULT")"
jq -n --arg policy "$BENCH_POLICY" --arg case "$BENCH_CASE" --arg patch "$BENCH_PATCH_REL" --slurpfile manifest "$BENCH_MANIFEST" '
  ($manifest[0]) as $manifest |
  ($manifest.policies[] | select(.name == $policy)) as $policyDef |
  ($manifest.cases[] | select(.id == $case)) as $caseDef |
  def routeFields($name; $route; $complexity; $before):
    ($route | split("/")) as $parts |
    {name: $name, requestedModel: $parts[0], requestedEffort: $parts[1],
     actualModel: null, actualEffort: null, complexity: $complexity,
     confidence: "medium", beforeFrame: $before, afterFrame: $before,
     status: "pending", attemptCount: 0, regressionCount: 0,
     sharedSurfaces: [], needsEscalation: false, escalationReasons: [],
     modelRoute: [$route], usage: {inputTokens: null, cachedInputTokens: null,
     outputTokens: null, reasoningTokens: null}, durationMs: null};
  ($caseDef.complexity) as $complexity |
  (if $complexity == "narrow" then $policyDef.routes.narrowFix[0] else $policyDef.routes.sharedFix[0] end) as $fixRoute |
  (if $complexity == "narrow" then $policyDef.routes.ordinaryVerify[0] else $policyDef.routes.escalatedVerify[0] end) as $verifyRoute |
  {schemaVersion: 1, policy: $policyDef.name, caseId: $caseDef.id,
   baseCommit: $caseDef.baseCommit, resultTree: "0000000000000000000000000000000000000000",
   patch: {path: $patch, sha256: "0000000000000000000000000000000000000000000000000000000000000000"},
   stages: [routeFields("discovery"; $policyDef.routes.discovery[0]; "mechanical"; null),
            routeFields("triage"; $policyDef.routes.triage[0]; $complexity; $caseDef.startingFrontier.frame),
            routeFields("fix"; $fixRoute; $complexity; $caseDef.startingFrontier.frame),
            routeFields("verify"; $verifyRoute; $complexity; $caseDef.startingFrontier.frame)],
   tokens: {inputTokens: null, cachedInputTokens: null, outputTokens: null, reasoningTokens: null},
   wallTimeMs: null, totalAttemptCount: 0, beforeFrontier: $caseDef.startingFrontier,
   afterFrontier: $caseDef.startingFrontier, status: "no-change",
   accepted: false, genuine: false, reviewerRejected: false,
   romCitations: [], verificationResults: [], regressions: []}' > "$BENCH_RESULT"
```

After Verify has committed or returned its final result, run the manifest's exact
regression set against the complete branch plus working tree. This is independent
of the historical red target. It includes a same-game green trace for S1/S2 and
the four S3K fallback guards. Record every class outcome; do not summarize the
command as a single boolean or substitute a same-package class.

```bash
BENCH_ROM_PROPERTY=$(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .rom.property' "$BENCH_MANIFEST")
BENCH_VERIFY_CLASSES=$(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .verificationClasses | join(",")' "$BENCH_MANIFEST")
BENCH_VERIFY_OUTPUT="$BENCH_RETAIN/verification-command.log"
BENCH_VERIFY_REPORT_DIR="$BENCH_RETAIN/verification-reports"
BENCH_PRE_VERIFY_REPORT_DIR="$BENCH_RETAIN/pre-verify-reports"
BENCH_PRE_VERIFY_REPORT_HASHES="$BENCH_RETAIN/pre-verify-report-sha256.tsv"
BENCH_VERIFY_REPORTS_ABSENT_AT_LAUNCH="$BENCH_RETAIN/verification-reports-absent-at-launch"
mkdir -p "$BENCH_VERIFY_REPORT_DIR" "$BENCH_PRE_VERIFY_REPORT_DIR"
: > "$BENCH_PRE_VERIFY_REPORT_HASHES"
: > "$BENCH_VERIFY_REPORTS_ABSENT_AT_LAUNCH"
while IFS= read -r testClass; do
  report="$BENCH_WORKTREE/target/surefire-reports/${testClass}.txt"
  if test -e "$report"; then
    test -f "$report"
    retainedPreVerifyReport="$BENCH_PRE_VERIFY_REPORT_DIR/${testClass}.txt"
    test ! -e "$retainedPreVerifyReport"
    preVerifySha256=$(sha256sum "$report" | cut -d' ' -f1)
    cp -- "$report" "$retainedPreVerifyReport"
    test "$preVerifySha256" = "$(sha256sum "$retainedPreVerifyReport" | cut -d' ' -f1)"
    printf '%s\t%s\t%s\n' "$testClass" "$preVerifySha256" "$retainedPreVerifyReport" >> "$BENCH_PRE_VERIFY_REPORT_HASHES"
    rm -- "$report"
  fi
  # A report accepted below must therefore have been written by this Maven run.
  test ! -e "$report"
  printf '%s\n' "$report" >> "$BENCH_VERIFY_REPORTS_ABSENT_AT_LAUNCH"
done < <(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .verificationClasses[]' "$BENCH_MANIFEST")
if (
  cd "$BENCH_WORKTREE"
  mvn -q -Dmse=off -Dsurefire.forkCount=1 -DreuseForks=true \
    "-D${BENCH_ROM_PROPERTY}=${ROM_PATH}" "-Dtest=${BENCH_VERIFY_CLASSES}" test
) >"$BENCH_VERIFY_OUTPUT" 2>&1; then
  BENCH_VERIFY_STATUS=0
else
  BENCH_VERIFY_STATUS=$?
fi

jq -n --arg case "$BENCH_CASE" --slurpfile manifest "$BENCH_MANIFEST" '
  ($manifest[0].cases[] | select(.id == $case) | .verificationClasses) |
  map({testClass: ., outcome: "pending",
       reportKind: "command-log", report: "", reportSha256: ""})
' > "$BENCH_RETAIN/verification-results.json"
while IFS= read -r testClass; do
  report="$BENCH_WORKTREE/target/surefire-reports/${testClass}.txt"
  retainedReport="$BENCH_VERIFY_REPORT_DIR/${testClass}.command.log"
  reportKind=command-log
  outcome=error
  if test -f "$report"; then
    # The exact path was absent at launch, so this is fresh Surefire evidence.
    grep -Fqx "$report" "$BENCH_VERIFY_REPORTS_ABSENT_AT_LAUNCH"
    retainedReport="$BENCH_VERIFY_REPORT_DIR/${testClass}.txt"
    reportKind=surefire
    cp "$report" "$retainedReport"
    if grep -Eq 'UnsatisfiedLinkError|lwjgl\\.(dll|so|dylib)|glfw\\.(dll|so|dylib)|TestBundledConfigResource' "$retainedReport"; then outcome=error
    elif grep -Eq 'Errors: [1-9][0-9]*' "$retainedReport"; then outcome=error
    elif grep -Eq 'Failures: [1-9][0-9]*' "$retainedReport"; then
      if [[ "$testClass" == *".tests.trace."* ]] &&
         grep -Fq 'org.opentest4j.AssertionFailedError' "$retainedReport" &&
         grep -Eq 'First error: frame [0-9]+ -- [^[:space:]]+' "$retainedReport"; then outcome=failed
      elif [[ "$testClass" != *".tests.trace."* ]] &&
           grep -Eq 'org\.opentest4j\.AssertionFailedError|java\.lang\.AssertionError' "$retainedReport"; then outcome=failed
      else outcome=error
      fi
    elif grep -Eq 'Skipped: [1-9][0-9]*' "$retainedReport"; then outcome=skipped
    elif grep -Eq 'Tests run: [1-9][0-9]*, Failures: 0, Errors: 0, Skipped: 0' "$retainedReport"; then outcome=passed
    else outcome=error
    fi
  else
    cp "$BENCH_VERIFY_OUTPUT" "$retainedReport"
  fi
  reportSha256=$(sha256sum "$retainedReport" | cut -d' ' -f1)
  jq --arg class "$testClass" --arg outcome "$outcome" \
    --arg reportKind "$reportKind" --arg report "$retainedReport" --arg reportSha256 "$reportSha256" \
    'map(if .testClass == $class then
      .outcome = $outcome | .reportKind = $reportKind |
      .report = $report | .reportSha256 = $reportSha256
    else . end)' \
    "$BENCH_RETAIN/verification-results.json" > "$BENCH_RETAIN/verification-results.next.json"
  mv "$BENCH_RETAIN/verification-results.next.json" "$BENCH_RETAIN/verification-results.json"
done < <(jq -r --arg case "$BENCH_CASE" '.cases[] | select(.id == $case) | .verificationClasses[]' "$BENCH_MANIFEST")
jq --slurpfile verification "$BENCH_RETAIN/verification-results.json" \
  '.verificationResults = $verification[0]' "$BENCH_RESULT" > "$BENCH_RETAIN/result-with-verification.json"
mv "$BENCH_RETAIN/result-with-verification.json" "$BENCH_RESULT"
```

Before capture, the worker lists each file it authored or intentionally changed,
one repo-relative path per line in `$BENCH_OWNED`. Create an empty file when a
blocked, error, or no-change run owns no source changes. Do not add hook-created
links, baseline files, result files, or foreign paths. The following commands
reject a non-empty owned-file list that contains an unchanged file or an
untracked baseline path. They capture exactly the listed tracked diff and listed
new files; no broad untracked scan ever enters the patch or cleanup. All comparisons
are against pinned `BENCH_BASE`, not the possibly advanced benchmark `HEAD`, so a
Verify commit and any later working-tree edits are represented together.

```bash
test -f "$BENCH_OWNED"
sort -u "$BENCH_OWNED" -o "$BENCH_OWNED"
git -C "$BENCH_WORKTREE" ls-files --others --exclude-standard | sort | comm -13 "$BENCH_BASELINE_UNTRACKED" - > "$BENCH_NEW_UNTRACKED"
git -C "$BENCH_WORKTREE" diff --name-only "$BENCH_BASE" -- | sort > "$BENCH_COMPLETE_TRACKED"
{ cat "$BENCH_COMPLETE_TRACKED"; cat "$BENCH_NEW_UNTRACKED"; } | sort -u > "$BENCH_COMPLETE_OWNED"
cmp "$BENCH_OWNED" "$BENCH_COMPLETE_OWNED"
: > "$BENCH_OWNED_TRACKED"
: > "$BENCH_OWNED_NEW"
while IFS= read -r path; do
  test -n "$path"
  if grep -Fqx "$path" "$BENCH_NEW_UNTRACKED"; then
    printf '%s\n' "$path" >> "$BENCH_OWNED_NEW"
  else
    git -C "$BENCH_WORKTREE" diff --quiet "$BENCH_BASE" -- "$path" && exit 1
    printf '%s\n' "$path" >> "$BENCH_OWNED_TRACKED"
  fi
done < "$BENCH_OWNED"

: > "$BENCH_RETAIN/worker.patch"
while IFS= read -r path; do git -C "$BENCH_WORKTREE" diff --binary "$BENCH_BASE" -- "$path" >> "$BENCH_RETAIN/worker.patch"; done < "$BENCH_OWNED_TRACKED"
while IFS= read -r path; do git -C "$BENCH_WORKTREE" diff --no-index --binary -- /dev/null "$path" >> "$BENCH_RETAIN/worker.patch" || test $? -eq 1; done < "$BENCH_OWNED_NEW"
BENCH_PATCH_SHA256=$(sha256sum "$BENCH_RETAIN/worker.patch" | cut -d' ' -f1)

GIT_INDEX_FILE="$BENCH_TEMP_INDEX" git -C "$BENCH_WORKTREE" read-tree "$BENCH_BASE"
while IFS= read -r path; do GIT_INDEX_FILE="$BENCH_TEMP_INDEX" git -C "$BENCH_WORKTREE" add -- "$path"; done < "$BENCH_OWNED_TRACKED"
while IFS= read -r path; do GIT_INDEX_FILE="$BENCH_TEMP_INDEX" git -C "$BENCH_WORKTREE" add -- "$path"; done < "$BENCH_OWNED_NEW"
BENCH_RESULT_TREE=$(GIT_INDEX_FILE="$BENCH_TEMP_INDEX" git -C "$BENCH_WORKTREE" write-tree)
BENCH_BASE_TREE=$(git -C "$BENCH_WORKTREE" rev-parse "${BENCH_BASE}^{tree}")
if test ! -s "$BENCH_OWNED"; then
  test ! -s "$BENCH_RETAIN/worker.patch"
  test "$BENCH_RESULT_TREE" = "$BENCH_BASE_TREE"
fi

jq --arg tree "$BENCH_RESULT_TREE" --arg patchSha256 "$BENCH_PATCH_SHA256" '.resultTree = $tree | .patch.sha256 = $patchSha256' "$BENCH_RESULT" > "$BENCH_RETAIN/result.json"
mv "$BENCH_RETAIN/result.json" "$BENCH_RESULT"
cp "$BENCH_RETAIN/worker.patch" "$BENCH_PATCH"
check-jsonschema --schemafile "$BENCH_ROOT/docs/architecture/validation/trace/trace-model-routing-result.schema.json" "$BENCH_RESULT"
test -f "$BENCH_PATCH"
# `$BENCH_OWNED`, `$BENCH_OWNED_TRACKED`, and `$BENCH_OWNED_NEW` already live
# in `$BENCH_RETAIN`; copy only the result out of the worktree.
cp "$BENCH_RESULT" "$BENCH_RETAIN/"
```

The semantic gate is intentionally separate from JSON Schema. It binds the
selected result to an enabled manifest policy and case, checks the game inputs,
requires an executed stage prefix, validates each requested/actual route against
the selected policy, and checks status, frontier, attempt, regression, stage
completion, and source-change consistency. A route outside the selected policy
always fails. An empty patch and unchanged result tree are valid only for
`blocked`, `error`, or `no-change`; `green` and `advanced` require a source diff.

```bash
jq -n -e --arg policy "$BENCH_POLICY" --arg case "$BENCH_CASE" --arg patch "$BENCH_PATCH_REL" --arg baseTree "$BENCH_BASE_TREE" --arg emptyPatchSha256 "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855" --slurpfile manifest "$BENCH_MANIFEST" --slurpfile result "$BENCH_RESULT" '
  ($manifest[0]) as $manifest | ($result[0]) as $result |
  ($manifest.policies[] | select(.name == $policy)) as $policyDef |
  ($manifest.cases[] | select(.id == $case)) as $caseDef |
  def routeOf($stage): $stage.requestedModel + "/" + $stage.requestedEffort;
  def isPrefix($actual; $allowed):
    ($actual | length) > 0 and ($actual | length) <= ($allowed | length) and
    all(range(0; $actual | length); . as $index | $actual[$index] == $allowed[$index]);
  def allowedRoutes($stage):
    if $stage.name == "discovery" then $policyDef.routes.discovery
    elif $stage.name == "triage" then $policyDef.routes.triage
    elif $stage.name == "fix" then
      if $stage.complexity == "narrow" then $policyDef.routes.narrowFix else $policyDef.routes.sharedFix end
    else
      if $stage.complexity != "narrow" or
         any($result.stages[] | select(.name != "verify");
             (.modelRoute | length) > 1 or (.modelRoute[-1] | startswith("gpt-5.6-sol/")))
      then $policyDef.routes.escalatedVerify else $policyDef.routes.ordinaryVerify end
    end;
  def oneOf($value; $allowed): any($allowed[]; . == $value);
  ($caseDef.game as $game |
   $policyDef.enabled == true and
   all($policyDef.routes[][]; test("^gpt-5\\.6-(terra|sol)/(low|medium|high)$")) and
   ($caseDef.targetCommand | contains("-Dtest=" + $caseDef.testClass + "#")) and
   (if ($result.stages | map(.name) | index("verify")) != null then
      (($result.verificationResults | map(.testClass)) == $caseDef.verificationClasses)
    else ($result.verificationResults | length) == 0 end) and
   (if $game == "s1" then $caseDef.rom.property == "sonic1.rom.path" and $caseDef.rom.sha1 == "69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B" and ($caseDef.targetCommand | contains("-Dsonic1.rom.path=<ROM_PATH>"))
    elif $game == "s2" then $caseDef.rom.property == "sonic2.rom.path" and $caseDef.rom.sha1 == "8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9" and ($caseDef.targetCommand | contains("-Dsonic2.rom.path=<ROM_PATH>"))
    else $caseDef.rom.property == "s3k.rom.path" and $caseDef.rom.sha1 == "CFBF98C36C776677290A872547AC47C53D2761D6" and ($caseDef.targetCommand | contains("-Ds3k.rom.path=<ROM_PATH>")) end) and
   $result.policy == $policyDef.name and $result.caseId == $caseDef.id and
   $result.baseCommit == $caseDef.baseCommit and $result.beforeFrontier == $caseDef.startingFrontier and
   $result.patch.path == $patch and
   (if $result.patch.sha256 == $emptyPatchSha256 or $result.resultTree == $baseTree then
      $result.patch.sha256 == $emptyPatchSha256 and $result.resultTree == $baseTree and
      oneOf($result.status; ["blocked", "error", "no-change"])
    else true end) and
   ($result.stages | map(.name)) == (["discovery", "triage", "fix", "verify"][0:($result.stages | length)]) and
   all($result.stages[]; . as $stage |
     isPrefix($stage.modelRoute; allowedRoutes($stage)) and
     routeOf($stage) == $stage.modelRoute[-1] and
     (($stage.actualModel == null and $stage.actualEffort == null) or
      ($stage.actualModel + "/" + $stage.actualEffort) == routeOf($stage)) and
     $stage.needsEscalation == false and
     (($stage.modelRoute | length) == 1 or ($stage.escalationReasons | length) > 0) and
     (if $stage.name == "discovery" then
        $stage.complexity == "mechanical" and $stage.beforeFrame == null and $stage.afterFrame == null and
        $stage.attemptCount == 0 and oneOf($stage.status; ["completed", "blocked", "error"])
      elif $stage.name == "triage" then
        $stage.complexity == $caseDef.complexity and
        $stage.beforeFrame == $caseDef.startingFrontier.frame and $stage.afterFrame == $stage.beforeFrame and
        oneOf($stage.status; ["completed", "blocked", "error"])
      else
        $stage.complexity == $caseDef.complexity and
        oneOf($stage.status; ["green", "advanced", "advanced-with-regression", "no-change", "rejected-not-genuine", "blocked", "error"])
      end)) and
   all(range(0; ($result.stages | length) - 1); . as $index |
     ($result.stages[$index] |
      if .name == "discovery" or .name == "triage" then .status == "completed"
      else oneOf(.status; ["green", "advanced", "advanced-with-regression"]) end)) and
   ($result.stages[-1] as $last |
     (if $last.name == "discovery" or $last.name == "triage" then oneOf($last.status; ["blocked", "error"])
      elif $last.name == "fix" then oneOf($last.status; ["no-change", "rejected-not-genuine", "blocked", "error"])
      else true end) and
     $result.status == $last.status and
     (if $last.afterFrame == null then $result.afterFrontier == null
      else $result.afterFrontier != null and $result.afterFrontier.frame == $last.afterFrame end)) and
   (all($result.stages[] | select(.name != "fix"); .attemptCount == 0)) and
   (([$result.stages[] | select(.name == "fix") | .attemptCount] | add // 0) == $result.totalAttemptCount) and
   (($result.stages | map(.regressionCount) | add) == ($result.regressions | length)) and
   (if any($result.verificationResults[]; .outcome == "error") then
      $result.status == "error"
    elif ($result.regressions | length) > 0 then
      $result.status == "advanced-with-regression"
    else $result.status != "advanced-with-regression" end) and
   (($result.regressions | map(.testClass) | sort) ==
    ($result.verificationResults | map(select(.outcome == "failed") | .testClass) | sort)) and
   (($result.genuine | not) or ($result.romCitations | length) > 0) and
   ($result.accepted ==
    (oneOf($result.status; ["green", "advanced", "advanced-with-regression"]) and
     $result.genuine and ($result.reviewerRejected | not) and
     (($result.stages | map(.name) | index("verify")) != null) and
     (($result.verificationResults | map(.testClass)) == $caseDef.verificationClasses) and
     all($result.verificationResults[]; .outcome != "error" and .outcome != "skipped"))) and
   (($result.status == "rejected-not-genuine") ==
    (($result.genuine | not) and $result.reviewerRejected)) and
   (if $result.status == "green" then $result.afterFrontier == null
    elif $result.status == "advanced" then $result.afterFrontier.frame > $result.beforeFrontier.frame
    elif $result.status == "no-change" then $result.afterFrontier == $result.beforeFrontier
    else true end) and
   (if ($result.stages | map(.name) | index("fix")) != null then
      ($result.stages[] | select(.name == "fix") | .beforeFrame == $caseDef.startingFrontier.frame)
    else true end) and
   (if ($result.stages | map(.name) | index("verify")) != null then
      (($result.stages[] | select(.name == "fix") | .afterFrame) as $fixAfter |
       ($result.stages[] | select(.name == "verify") |
        .beforeFrame == $fixAfter and .afterFrame == .beforeFrame))
    else true end))
'
```

The result and patch are now preserved outside `target/` and the worktree.
Restore only the enumerated worker-owned paths. Tracked paths return to the base
tree; untracked paths and the two protocol artifacts move into the retained
directory rather than being deleted. The final comparison is against the exact
baseline, so hook-created and foreign untracked files must remain unchanged. If
either comparison fails, stop and retain the worktree.

```bash
while IFS= read -r path; do git -C "$BENCH_WORKTREE" restore --source=HEAD --worktree -- "$path"; done < "$BENCH_OWNED_TRACKED"
while IFS= read -r path; do mv "$BENCH_WORKTREE/$path" "$BENCH_RETAIN/owned-new-$(basename "$path")"; done < "$BENCH_OWNED_NEW"
mv "$BENCH_RESULT" "$BENCH_PATCH" "$BENCH_RETAIN/"
rmdir --ignore-fail-on-non-empty "$(dirname "$BENCH_RESULT")" 2>/dev/null || true
git -C "$BENCH_WORKTREE" status --porcelain --untracked-files=no > "$BENCH_RETAIN/final-tracked"
git -C "$BENCH_WORKTREE" ls-files --others --exclude-standard | sort > "$BENCH_RETAIN/final-untracked"
cmp "$BENCH_BASELINE_TRACKED" "$BENCH_RETAIN/final-tracked"
cmp "$BENCH_BASELINE_UNTRACKED" "$BENCH_RETAIN/final-untracked"
# The post-checkout hook creates only these disposable symlinks. They were
# baseline state, not worker-owned files; unlinking them here never touches
# their targets. Any other baseline untracked path leaves the worktree retained.
for path in docs/kis2disasm docs/s1disasm docs/s2disasm docs/scddisasm docs/skdisasm; do
  test ! -e "$BENCH_WORKTREE/$path" || test -L "$BENCH_WORKTREE/$path"
  test ! -L "$BENCH_WORKTREE/$path" || unlink "$BENCH_WORKTREE/$path"
done
test -z "$(git -C "$BENCH_WORKTREE" status --porcelain)"
git worktree remove "$BENCH_WORKTREE"
# Retain a branch that carries benchmark commits for audit. If the run made no
# commits, this safe non-force deletion may reclaim the otherwise-empty branch.
if test -z "$(git log --format=%H "${BENCH_BASE}..${BENCH_BRANCH}")"; then git branch -d "$BENCH_BRANCH"; fi
```

## Shared-worktree hygiene (critical — many concurrent sessions)

- **Stage ONLY your own authored files.** NEVER `git add -A`. There is frequently
  foreign WIP in the tree (e.g. a bulk skill-regen touching every `SKILL.md`, or a
  rewind-codec session's staged files in the shared index); never stage or "fix up"
  files you didn't author. Run git from your assigned worktree, not the shared repo
  root — the shared index will surface other sessions' staged work in your
  `git diff --cached`.
- **NEVER `git stash`** for A/B baselining — stash push/pop has eaten changes and
  injected foreign files. Use a separate checkout or `git show HEAD:<file>` diffs.
- To land a change without entangling foreign uncommitted WIP on the same files,
  cherry-pick your isolated commit into a **fresh `git worktree add --detach` off
  `origin/develop`**, verify, then push — the clean tree has none of the WIP.
- Commit trailers: src/main feat/fix needs `Changelog: updated` **and** a CHANGELOG.md
  edit in the **same** commit. No `--no-verify`. Merges into develop need a README
  release-log entry. Update `docs/status/trace-frontier-log.md` whenever a frontier moves.
- A correct, regression-free, ROM-faithful change that does **not** advance a frontier
  yet (e.g. a foundational bootstrap seed gated by a separate root) is committed to
  its **branch** but **not merged to develop** until a frontier actually moves — so
  develop only ever gains advances, and the work is still safe from worktree churn.

## BizHawk tooling traps (hand these to every agent)

- **ROM name:** use `s1.gen` / `s2.gen` / `s3k.gen` (repo root) for the EmuHawk
  `--movie ... <rom>` arg. Paths with spaces/parens/`[!]` have caused EmuHawk to
  load no ROM and hang (~316MB, frames never advance). This was the single biggest
  time-sink; mandate the simple names. (mvn trace tests are unaffected - they use
  `-D<game>.rom.path=...`.)
- **Fast headless trio** (≈100x faster, ~475MB vs ~3.4GB): at the top of the lua,
  `emu.limitframerate(false)` + `client.speedmode(6400)` + `client.invisibleemulation(true)`.
  `--chromeless` alone does NOT do this.
- **Self-exit:** the lua MUST `client.exit()` when done. A bare
  `while true do emu.frameadvance() end` (no exit) or a `client.pause()` tail LEAKS
  EmuHawk at multiple GB. Use `tools/tracechaser/bizhawk/diag_template_fast.lua` (fill only its
  two marked sections).
- **Read-count crash:** >~12–16 `mainmemory.read_*` per frame at speedmode 6400 makes
  EmuHawk silently exit. Drop to `speedmode(100)` in the capture window, or buffer/
  split the scans.
- **Long seek:** seeking to a high BizHawk frame (~190000) takes minutes even at
  6400% — use a 600s+ timeout, and never wrap the EmuHawk launch in bash `timeout`
  that fires mid-seek.
- **System.err is swallowed** by the surefire/MSE harness — for engine-side debug,
  `Files.writeString` to a Windows/relative path (NOT `/tmp`, which throws on the
  Windows JVM and the catch hides it).

## Why this loop

The engine's correctness claim is "play back any BK2 movie pixel-for-pixel from
controller input alone." Each banked frontier advance is one more verified slice of
that claim. The loop optimizes for *continuous verified progress*: small ROM-cited
wins merged immediately, deep roots documented and owned rather than grinded blindly,
and develop kept always-green so every agent starts from a trustworthy base.
