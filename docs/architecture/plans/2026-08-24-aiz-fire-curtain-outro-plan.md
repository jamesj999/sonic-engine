# AIZ flame-curtain outro implementation plan

## Objective

Restore the AIZ fire curtain's ROM-accurate outro while preserving the recent
continuity behavior that keeps the AIZ1-to-AIZ2 curtain present during trace
replay. The change must be driven by the existing ROM-derived `WaitFire`
release latch, not by a trace row, frame number, artificial delay, or zone
exception.

## Files and responsibilities

1. `src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRenderer.java`
   will cover the renderer's pre-latch and latched `AIZ2_WAIT_FIRE` decisions.
   The pre-latch cases must continue to wrap a carried fire plane; the latched
   case must disable wrapping and prove that the cached plan contains only the
   finite trailing rows below the ROM release threshold.
2. `src/test/java/com/openggf/game/sonic3k/events/TestSonic3kAIZEvents.java`
   will retain or strengthen the event-state assertion around the `WaitFire`
   latch. It must show that phase progression and `sourceWorldY` remain under
   the existing event logic, while the existing `$0200` source-strip switch is
   still made when the latch is admitted. The test must also observe the
   renderer wrapping predicate changing only at that semantic boundary.
3. `src/test/java/com/openggf/game/sonic3k/features/TestAizFireCurtainRendererRom.java`
   will turn the existing ROM-backed stage statistics into assertions: the
   latched `AIZ2_WAIT_FIRE` interval emits fire overlay patterns and the
   `AIZ2_BG_REDRAW` interval remains overlay-free.
4. `src/main/java/com/openggf/game/sonic3k/events/Sonic3kAIZEvents.java`
   will make the phase-to-render mapping accept the existing
   `act2WaitFireDrawActive` latch. Only the wrapping bit changes:
   `AIZ2_WAIT_FIRE` wraps before the latch and does not wrap after it; all
   other phase behavior remains as designed in the reviewed artifact.
5. `CHANGELOG.0.6.md` will receive a concise user-facing fix note. No trace
   fixture, runtime asset, or configuration flag is expected.
6. `docs/status/trace-frontier-log.md` will record the full replay comparison
   required for this trace-visible fix, even if the frontier is unchanged.
   The entry will include the command, worktree/commit context, pass/fail and
   error totals, first-error frame/field, and an explicit statement that no
   previously green replay regressed.

The reviewed design remains at
`docs/architecture/designs/2026-08-24-aiz-fire-curtain-outro-design.md` and is
the source of truth for the ROM evidence and non-goals.

## Test-first sequence

### 1. Add the failing contract

Update the renderer test before production code:

* preserve an explicit pre-latch case for `AIZ2_FIRE_REDRAW` and an unlatched
  `AIZ2_WAIT_FIRE` case, both with a carried source position beyond the dense
  curtain range and a non-empty cached plan;
* add a latched `AIZ2_WAIT_FIRE` case with the source position just below
  `$310`, assert `wrapFireTiles() == false`, retain the `$0200` source-X
  contract, and verify that all emitted cached rows are in the narrow trailing
  range rather than rows remapped into the dense body;
* use row-identifiable cached descriptors so the test detects an accidental
  wrap instead of merely checking that some pixels were emitted.

Update the event and ROM diagnostic assertions described above. Run the
focused renderer/event tests through the test-session wrapper and record the
expected failure before changing the production implementation.

### 2. Implement the smallest semantic change

Change the existing phase helper/call site so the wrapping decision is:

* true for the existing AIZ1 phases and `AIZ2_FIRE_REDRAW`;
* true for `AIZ2_WAIT_FIRE` only while `act2WaitFireDrawActive` is false;
* false for latched `AIZ2_WAIT_FIRE`, `AIZ2_BG_REDRAW`, and terminal states.

Do not change `runAiz2WaitFire()`, the rise speed, the `$310` release check,
the source-Y computation, the `$0200` source-X switch, the BG redraw duration,
camera bounds, player unlock, palette, haze, or any trace scheduling path.

### 3. Make the tests green

Run the same focused tests again. Inspect the event-stage assertions to ensure
the latch is still admitted at the ROM residue window, the source strip changes
to `$0200`, and the release tail is rendered only until the existing `$310`
boundary. Fix only contract or implementation issues exposed by those tests.

### 4. ROM-backed and visual verification

Run the ROM-backed renderer diagnostic with the discovered locked-on S3K ROM
and the committed AIZ BK2 through the wrapper:

```bash
tools/testing/test-session.sh -- mvn -Dmse=off \
  -Ds3k.rom.path=<S3K_ROM_PATH> \
  '-Dtest=com.openggf.game.sonic3k.features.TestAizFireCurtainRendererRom#realAizFakeoutReportsPerPhaseCurtainDescriptorStats' \
  test -B
```

Split its statistics by the `act2WaitFireDrawActive` latch so a pre-latch
overlay cannot make the acceptance pass: the latched `AIZ2_WAIT_FIRE`
interval must emit overlay patterns, while `AIZ2_BG_REDRAW` must remain
overlay-free. Capture the corresponding engine frames with the native
headless capture tool, retaining its output under the task directory created
by `agent-scratch`:

```bash
agent-scratch status
AIZ_CURTAIN_CAPTURE_TASK=$(agent-scratch new aiz-fire-curtain-outro-capture)
tools/testing/test-session.sh -- mvn -Ptrace-replay -Dmse=off \
  -Ds3k.rom.path=<S3K_ROM_PATH> \
  '-Dexec.mainClass=com.openggf.tools.TraceCaptureTool' \
  "-Dexec.args=--trace src/test/resources/traces/s3k/aiz1_to_hcz_fullrun --out-dir ${AIZ_CURTAIN_CAPTURE_TASK}/video --no-ghosts" \
  test-compile exec:java
```

Inspect consecutive frames around the release window: the curtain should
recede into a finite tail, then the ordinary AIZ2 scene should appear without
an extra artificial delay.

Run the focused S3K AIZ tests and the complete `*TraceReplay#replayMatchesTrace`
matrix used by the current frontier log. Compare candidate results with the
updated-develop baseline class by class: every previously green replay must
remain green, the known failure/error totals must not worsen, and each known
red replay's first-error frame/field must remain unchanged unless the fix is
explicitly intended to advance that frontier. The AIZ1-to-HCZ frontier must
not shorten, and the trace must not gain a progress/termination failure. The
visual capture is supplementary evidence, never input to replay.

## Certifying command shape

Every build, test, replay, or capture is run through the project wrapper, with
JDK 21 verified by `mvn -v`. Let the coordinator create its managed
`agent-scratch` session root by default; do not place retained manifests,
reports, or captures under `/tmp`. For durable visual artifacts, create a
task directory with `agent-scratch new aiz-fire-curtain-outro` and pass that
path to the producing capture tool.

```bash
tools/testing/test-session.sh -- mvn -Dmse=off \
  '-Dtest=com.openggf.game.sonic3k.features.TestAizFireCurtainRenderer' test -B

tools/testing/test-session.sh -- mvn -Dmse=off \
  '-Dtest=com.openggf.game.sonic3k.events.TestSonic3kAIZEvents' test -B

tools/testing/test-session.sh -- mvn -Ptrace-replay -Dmse=off \
  -Dsurefire.forkCount=1 -Dsurefire.runOrder=alphabetical \
  '-Dtest=*TraceReplay#replayMatchesTrace' -DfailIfNoTests=false \
  -Dsonic1.rom.path=<S1_ROM_PATH> -Dsonic2.rom.path=<S2_ROM_PATH> \
  -Ds3k.rom.path=<S3K_ROM_PATH> test -B

tools/testing/test-session.sh -- mvn -Dmse=off -Pguards test -B
```

The ROM-backed test and trace capture use their existing game/fixture
properties and the same wrapper. Replace the placeholders with the discovered
ROM paths and verify their hashes before running. Each result must retain its
run ID and manifest path; raw Maven output alone is not release evidence. The
trace matrix is valid only when its wrapper manifest contains the expected
Surefire report inventory for the baseline (not an empty or truncated set),
even though `-DfailIfNoTests=false` is retained for the profile's filtered
class set. Make this an executable post-run gate, not a visual inspection:

```bash
set -euo pipefail
agent-scratch status
FRONTIER_TASK_ROOT=$(agent-scratch new aiz-fire-curtain-frontier-comparison)
# BASELINE_MANIFEST and CANDIDATE_MANIFEST are the wrapper paths printed by
# OPENGGF_TEST_RUN_START; keep the comparison files in FRONTIER_TASK_ROOT.
jq -e '(.state == "PASSED" or .state == "FAILED") and (.reports | length > 0)' "$BASELINE_MANIFEST"
jq -e '(.state == "PASSED" or .state == "FAILED") and (.reports | length > 0)' "$CANDIDATE_MANIFEST"
jq -e '.reports | length > 0' "$BASELINE_MANIFEST"
jq -e '.reports | length > 0' "$CANDIDATE_MANIFEST"
jq -r '.reports[]' "$BASELINE_MANIFEST" | sed -E 's#^.*/(surefire-reports|trace-reports|diagnostics)/#\1/#' | sort > "$FRONTIER_TASK_ROOT/baseline-reports.txt"
jq -r '.reports[]' "$CANDIDATE_MANIFEST" | sed -E 's#^.*/(surefire-reports|trace-reports|diagnostics)/#\1/#' | sort > "$FRONTIER_TASK_ROOT/candidate-reports.txt"
diff -u "$FRONTIER_TASK_ROOT/baseline-reports.txt" "$FRONTIER_TASK_ROOT/candidate-reports.txt"
test "$(find "$(jq -r .surefire_reports "$CANDIDATE_MANIFEST")" -type f -name 'TEST-*.xml' | wc -l)" -gt 0

sum_junit_attr() {
  find "$2" -type f -name 'TEST-*.xml' -exec grep -ho "$1=\"[0-9][0-9]*\"" {} + |
    sed -E 's/[^0-9]//g' | awk '{sum += $1} END {print sum + 0}'
}
BASELINE_SUREFIRE=$(jq -r .surefire_reports "$BASELINE_MANIFEST")
CANDIDATE_SUREFIRE=$(jq -r .surefire_reports "$CANDIDATE_MANIFEST")
BASELINE_TESTS=$(sum_junit_attr tests "$BASELINE_SUREFIRE")
CANDIDATE_TESTS=$(sum_junit_attr tests "$CANDIDATE_SUREFIRE")
EXPECTED_TRACE_TESTS=117  # Current selector inventory recorded in trace-frontier-log.md.
test "$BASELINE_TESTS" -gt 0
test "$CANDIDATE_TESTS" -gt 0
test "$BASELINE_TESTS" -eq "$EXPECTED_TRACE_TESTS"
test "$CANDIDATE_TESTS" -eq "$EXPECTED_TRACE_TESTS"
test "$BASELINE_TESTS" -eq "$CANDIDATE_TESTS"

pwsh -NoProfile -File tools/testing/Compare-SurefireRedSet.ps1 \
  -ReportsPath "$BASELINE_SUREFIRE" \
  -WriteActualPath "$FRONTIER_TASK_ROOT/baseline-red.txt"
pwsh -NoProfile -File tools/testing/Compare-SurefireRedSet.ps1 \
  -ReportsPath "$CANDIDATE_SUREFIRE" \
  -WriteActualPath "$FRONTIER_TASK_ROOT/candidate-red.txt"
comm -23 "$FRONTIER_TASK_ROOT/candidate-red.txt" "$FRONTIER_TASK_ROOT/baseline-red.txt" > "$FRONTIER_TASK_ROOT/new-red.txt"
test ! -s "$FRONTIER_TASK_ROOT/new-red.txt"

for attribute in failures errors skipped; do
  printf '%s\t%s\t%s\n' "$attribute" \
    "$(sum_junit_attr "$attribute" "$BASELINE_SUREFIRE")" \
    "$(sum_junit_attr "$attribute" "$CANDIDATE_SUREFIRE")"
done > "$FRONTIER_TASK_ROOT/junit-counts.tsv"

python3 - "$(jq -r .trace_reports "$BASELINE_MANIFEST")" \
  "$(jq -r .trace_reports "$CANDIDATE_MANIFEST")" \
  > "$FRONTIER_TASK_ROOT/trace-frontier-comparison.tsv" <<'PY'
import json
import sys
from pathlib import Path

def load_reports(root):
    return {
        path.name: json.loads(path.read_text(encoding="utf-8"))
        for path in Path(root).rglob("*_report.json")
    }

def bootstrap_rows(report):
    return {
        (
            item.get("severity", ""),
            item.get("field", ""),
            item.get("expected", ""),
            item.get("actual", ""),
            item.get("context", ""),
        )
        for item in report.get("bootstrap", [])
    }

def first_error(report):
    errors = report.get("errors", [])
    if not errors:
        return None
    return int(errors[0]["start_frame"]), errors[0].get("field", "")

baseline = load_reports(sys.argv[1])
candidate = load_reports(sys.argv[2])
if baseline.keys() != candidate.keys():
    raise SystemExit("trace report inventory differs after normalization")

for name in sorted(baseline):
    old = baseline[name]
    new = candidate[name]
    old_errors = int(old.get("error_count", 0))
    new_errors = int(new.get("error_count", 0))
    old_warnings = int(old.get("warning_count", 0))
    new_warnings = int(new.get("warning_count", 0))
    old_frames = int(old.get("total_frames", 0))
    new_frames = int(new.get("total_frames", 0))
    if new_errors > old_errors:
        raise SystemExit(f"trace regression: {name} error count increased {old_errors}->{new_errors}")
    if new_warnings > old_warnings:
        raise SystemExit(f"trace regression: {name} warning count increased {old_warnings}->{new_warnings}")
    if new_frames < old_frames:
        raise SystemExit(f"trace regression: {name} replay shortened {old_frames}->{new_frames} frames")
    extra_bootstrap = bootstrap_rows(new) - bootstrap_rows(old)
    if extra_bootstrap:
        raise SystemExit(f"trace regression: {name} gained bootstrap divergence(s): {extra_bootstrap}")

    old_first = first_error(old)
    new_first = first_error(new)
    if old_first is None and new_first is not None:
        raise SystemExit(f"trace regression: {name} gained first error {new_first}")
    if old_first is not None and new_first is not None:
        old_frame, old_field = old_first
        new_frame, new_field = new_first
        if new_frame < old_frame:
            raise SystemExit(f"trace regression: {name} first error moved {old_frame}->{new_frame}")
        if new_frame == old_frame and new_field != old_field:
            raise SystemExit(f"trace regression: {name} field changed at frame {new_frame}")
        print(f"{name}\terrors={old_errors}->{new_errors}\twarnings={old_warnings}->{new_warnings}\t"
              f"frames={old_frames}->{new_frames}\t"
              f"baseline={old_frame}:{old_field}\tcandidate={new_frame}:{new_field}")
    elif old_first is not None:
        print(f"{name}\terrors={old_errors}->{new_errors}\twarnings={old_warnings}->{new_warnings}\t"
              f"frames={old_frames}->{new_frames}\t"
              f"baseline={old_first[0]}:{old_first[1]}\tcandidate=clean")
    else:
        print(f"{name}\terrors={old_errors}->{new_errors}\twarnings={old_warnings}->{new_warnings}\t"
              f"frames={old_frames}->{new_frames}\t"
              "baseline=clean\tcandidate=clean")
PY
```

This executable gate permits a documented frontier improvement only when all
checked totals remain non-increasing. It rejects any new red test, increased
per-replay error/warning totals, an earlier first-error frame, a different
first-error field at the same frame, or a shortened replay. It records the
failure/error/skipped totals and the per-replay error/warning totals, frame
counts, and first-error rows for the trace-frontier log. A missing, empty, or
truncated inventory is invalid regardless of Maven's exit code. The expected
117-test assertion is an independently recorded selector inventory, not a
candidate-derived count; if a fetched baseline legitimately changes it, stop
and refresh the frontier evidence rather than weakening the gate.

## Integration and regression protocol

After the implementation passes in the development worktree:

1. Fetch `origin/develop` and fast-forward the main-workspace `develop` branch
   without overwriting its unrelated dirty user changes. Run and record both
   the ordinary full-suite baseline, a separate fresh `-Pguards` baseline, and
   the complete trace-replay frontier baseline from that updated integration
   base, including exact failures and first-error frame/field data.
2. Run the ordinary full suite, a separate fresh `-Pguards` session, the
   complete trace-replay matrix, and focused tests in this development
   worktree. Compare all failures and the complete frontier against the
   baseline before committing.
3. Update the trace-frontier log with the verified comparison, then commit the
   implementation, tests, release note, and architecture artifacts
   with the repository trailer block. Use the release-note mapping required by
   the hooks (`Changelog: n/a: release note recorded in CHANGELOG.0.6.md` if
   the root release index is unchanged).
4. Merge the feature branch into the main-workspace `develop` branch without
   switching the main workspace branch. Reconcile only actual upstream
   conflicts and preserve the user's unrelated changes.
5. Run the ordinary full suite, a separate fresh `-Pguards` session, and the
   complete trace-replay matrix on merged `develop`. Compare them with the
   recorded baselines, confirm no previously green trace regressed and no AIZ
   frontier shortened, then push only `develop`.
6. Verify the development worktree is clean/fully merged, remove it and its
   local branch, prune stale worktree metadata, and report the exact commands,
   manifests, branch, commit, and any pre-existing baseline failures.
