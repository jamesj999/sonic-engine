# Actworks extraction and Maven rollback validation

## Preservation gate

- Public repository: <https://github.com/OpenGGF/Actworks>
- Public/default branch: `main`, public visibility verified with GitHub CLI.
- Actworks remote commit: `a0b86640e7b9269141576b2cdffce35a98930a66`.
- OpenGGF reviewed source branch commit:
  `27b9840a8137e7cbf30b2fb4ee5be8eedf263e6c`.
- Filtered imported head before Actworks restructuring:
  `d9c20e73c4d0d928c518d97d4686361a53cb0a0f`.
- Durable clone was clean at the same Actworks remote commit.
- cowtree remains external at <https://github.com/raiscan/cowtree>.

## Actworks validation

- Slipmat: 105 tests passed, 1 sandbox-only `systemd-analyze` skip.
- Lifecycle: 30 tests passed.
- OpenGGF Maven coordinator self-test: passed.
- External process harness: passed.
- Python compilation, Git fsck, executable-mode, filtered-boundary, and diff
  checks: passed.

## OpenGGF baseline

See [the baseline record](2026-08-27-actworks-maven-rollback-baseline.md) and
its ordinary/guards red identity files. Raw Maven was rejected before executing
tests, which is the behavior this rollback removes.

## Rollback boundary

- Maven build, report, diagnostics, artifact, distribution, temporary, and
  per-fork LWJGL paths resolve below the current worktree's `target/` tree.
- The session validate/pre-clean guards and launcher bypass are removed.
- CI and release workflows use raw Maven plus static `target/` consumers.
- Coordinator, wrapper, process harness, managed scratch source/tests, and
  session-guard fixture are removed from OpenGGF after remote preservation.
- Active OpenGGF guidance uses direct Maven; lifecycle research is a separate
  v0.8 Actworks ask with no OpenGGF dependency.

## Rollback branch validation

- `mvn -Dmse=off clean test -B`: 15,160 tests, 45 failures, 25 errors,
  45 skipped. The 70-method red-set cardinality matched the baseline. Two
  baseline failures passed and two late-suite ICZ object tests failed instead;
  both ICZ methods then passed together in a focused raw-Maven run (2/2),
  confirming suite-order/shared-state rotation rather than an engine change.
- `mvn -Dmse=off
  -Dtest=com.openggf.tests.TestS3kIczCrushingColumnObject#nativeInitDispatchDoesNotRunSubtypeMovement+registryCreatesIczCrushingColumnInstance
  test -B`: 2 tests passed.
- `mvn -Dmse=off clean -Pguards test -B`: 557 tests, 1 failure, 0
  errors. The sole failing method,
  `TestArchitecturalSourceGuard#objectManagerFacadeStaysWithinExtractedCollaboratorBudget`,
  was already in the 19-method guard baseline; the other 18 baseline failures
  passed in the fresh direct-Maven JVM.
- `mvn -Dmse=off -Dtest=com.openggf.tests.TestBuildToolingGuard test -B`:
  92 tests passed.

The first attempted raw full run exposed Surefire's separate Maven-process
output spooler still using `/tmp/surefire-farrell`. The plugin-level
`tempDir` setting did not govern that path. `.mvn/jvm.config` now sets the
Maven JVM's temporary root to `target/maven-tmp`, while the validate phase
recreates that directory after `clean`. A second clean run reached the same
fault before that Maven-JVM setting was added; the final ordinary and guard
runs completed without the `/tmp` fault. These interrupted runs are
non-certifying and are retained here because they found the last
sandbox-compatibility gap before integration.

Merged-suite, host-cutover, and cleanup evidence are recorded only after those
operations complete.

## Independent pre-merge review

The first independent review rejected the branch for six concrete gaps: stale
trace reports could not be replaced in a reused target tree, release jobs
uploaded all of `target`, one supported audio script invoked Maven from outside
the worktree root, audio tools still accepted session-era relocation variables,
three active guides retained coordinator language, and the Actworks heading
captured the 1.0 roadmap body.

The follow-up closes each gap. Report and owner-sidecar publication now uses an
atomic replacing move; release upload paths name only the three platform
archives; supported audio Maven changes directory to the worktree root; audio
outputs are fixed beneath that worktree's `target/audio-parity`; active guide
references and the roadmap heading are corrected. The new boundary guard
asserts these conditions. Its release-upload assertion and the repeated-report
test were each observed red before the fixes and green afterward.

The re-review then found that CI/release still consumed the pre-owner report
layout and that a red-to-green rerun retained stale context text. The workflow
consumers now recurse through profile directories, ignore owner sidecars, and
select the required S2 special-stage report by its owner-aware pattern. A green
publication explicitly retires its prior context file. Both follow-up guards
were observed red before these fixes.

The final review identified two legitimate owner-distinct S2 special-stage
index-0 producers. The develop gate now requires at least one matching report
and validates every matching owner report instead of incorrectly requiring a
single file. Its multiplicity guard was observed red before the correction.
