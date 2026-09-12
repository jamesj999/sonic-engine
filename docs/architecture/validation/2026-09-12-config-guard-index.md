# Configuration documentation guard source index

## Change and scope

Base: `3703a4a92` on `develop`. Candidate worktree:
`.worktrees/ai-config-guard-index`, branch `feature/ai-config-guard-index`.

`TestModifierSupportDocumentation` now walks production source paths once,
reads and flattens each source once, and indexes binding read sites. A lazy
per-class cache holds immutable lists of diagnostic strings, not entire
sources. Temporary fixture scans build independent indexes. The existing
statement, enclosing-call, and hoisted-local classification helpers are
unchanged, as are all seven tests, source exclusions, traversal order, and
suite selection (`slow-suite`). Runtime code and configuration behavior do
not change.

The previous implementation repeated the file walk, reads, and whitespace
normalization for each binding. The new implementation extracts all binding
references during the single source pass. It preserves occurrence order and
duplicates within each binding's list and returns an empty list for an
absent binding.

## Behavioral evidence

A temporary reflection probe invoked `readSitesOf` for every enum value on
the baseline and candidate. Both returned 475 read sites across all 160
configuration bindings. The complete ordered strings, grouped by enum
value, were serialized identically. Both files have SHA-256:

```
2420b896732427cd8dea12794fc6dc4234bc297d64cfccb05f8205a08e2c095c
```

Two temporary mutations in the candidate worktree verified that the existing
assertions still reject actual violations:

- Removing `SPECIAL_STAGE_FAIL_KEY` from the documentation row failed
  `everyBindingReadThroughAnUnmodifiedCheckIsDocumentedAsSuch`, naming the
  binding and its production `GameLoop` read.
- Adding a source read of that binding through plain `isKeyPressed` failed
  `everyBindingDocumentedAsChordDeadIsOnlyReadThroughTheUnmodifiedCheck`,
  naming the added file and call.

Each mutation ran in a fresh JVM. Both were restored immediately afterward;
no mutation or probe source is part of the change. Existing tests also
exercise hoisted locals and mixed inline calls with different modifier
classification.

Raw probes and mutation diagnostics are retained in the external task
directory named by `$TASK_EVIDENCE_ROOT`. `$OPENGGF_MAIN` below denotes the
absolute main-checkout path; neither variable requires any ROM relocation.

## Timing

Java 21.0.11 and Maven 3.9.16. Focused command in each checkout:

```bash
mvn -Dmse=off -Dtest=TestModifierSupportDocumentation \
  -Dopenggf.surefire.reports=target/<task-run>/focused-repeat test -B
```

| Run | Baseline test-class seconds | Candidate test-class seconds |
|---|---:|---:|
| Initial | 26.651 | 0.708 |
| Serial repeat after upstream sync | 27.415 | 0.733 |

All four invocations completed seven tests with zero failures, errors, or
skips. The serial repeat ran baseline first, then candidate, with this task's
other Maven runs stopped. Its test-class time improved by about 37.4 times,
saving 26.682 seconds. These are Surefire class timings, not total Maven
wall times or a claimed whole-suite speedup. The initial candidate timing
overlapped the full baseline suite; use the serial repeat for the comparison.

## Full ordinary comparison before upstream sync

Both completed runs used the original `3703a4a92` base, one unchanged in the
main workspace and one with the index change in the task worktree:

```bash
mvn -Dmse=off test -B \
  "-Dsonic1.rom.path=$OPENGGF_MAIN/Sonic The Hedgehog (W) (REV01) [!].gen" \
  "-Dsonic2.rom.path=$OPENGGF_MAIN/Sonic The Hedgehog 2 (W) (REV01) [!].gen" \
  "-Ds3k.rom.path=$OPENGGF_MAIN/Sonic and Knuckles & Sonic 3 (W) [!].gen" \
  -Dopenggf.surefire.reports=target/<task-run>/full
```

All three ROMs matched the documented SHA-1 and CRC32 identities. Both runs
completed 20,299 tests: 20,260 passes, 14 failures, no errors, and 25 skips.
Every outcome identity, multiplicity, status, failure type/message, report
class, and complete nonpassing diagnostic body matched. No successful test
disappeared. Maven exited 1 in both runs because of the baseline failures.
These overlapping full runs are correctness evidence, not a timing pair.

The failures are the existing FBZ Act 2 route test and thirteen FBZ
compatibility-matrix cases. Skips were inspected: optional audio references
and explicit BK2/oracle inputs, opt-in measurements/captures/soak tests,
unavailable EGL, and the existing CPZ spin-tube scenario assumption. They
are unchanged and are not claimed as passing coverage. Complete failure and
skip diagnostics and comparison results are in the bounded external
`full-suite-comparison.json` (about 263 KiB).

## Upstream reconciliation and current-policy validation

During validation, origin advanced to `3e56cfb24`. Both develop and the task
worktree fast-forwarded without conflicts. The upstream changes add the
category runner, its tests, CI wiring, and documentation; the only changed
Java file is `TestBuildToolingGuard`, excluded from the ordinary suite and
covered by the separate structural run below. The earlier full-suite results
remain attributed to `3703a4a92`, not relabeled as runs of the updated base.

The upstream selector/retention tests passed:

```bash
python3 -m unittest discover -s tools/testing -p 'test_run_categor*.py'
```

Result: 25 tests, all passing.

The current repository policy selected common and tooling (285 candidate
source classes) plus all guards for this patch against its actual updated
integration base:

```bash
LUA_BIN=lua5.4 python3 tools/testing/run_categories.py --base 3e56cfb24 --run
```

Run `20260912T141143Z-cd6c76f7` completed successfully in the task worktree:
2,080 ordinary tests in 284 reports and 656 structural tests in 82 reports,
with zero failures, errors, skips, or omitted diagnostics in either lane.
The runner checked that the working tree stayed fixed for the run. This is
partial ordinary validation plus the complete guards profile, not a fresh
full-suite pass. The validation note was completed afterward; Java source
was unchanged. Its SHA-256 is
`116c957b96c625985b4bb41f0afcda4fe3198b5af75542e6405a0d416fe78e3e`.
