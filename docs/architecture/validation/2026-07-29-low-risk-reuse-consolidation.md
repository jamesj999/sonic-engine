# Low-Risk Reuse Consolidation Validation

**Date:** 2026-07-29

## Scope

This record validates the trace-file, ROM-header detector, and HUD static-art
consolidation against its fork baseline, rather than aggregating test reports
left by previous focused runs.

## Focused comparison

The completed tranche command was:

```bash
mvn -Dmse=off \
  "-Dtest=com.openggf.trace.TraceFilesTest,com.openggf.trace.catalog.TraceCatalogTest,com.openggf.tests.trace.TestTraceDataParsing,com.openggf.tests.trace.TestS1SpecialStageTraceParsing,com.openggf.tests.trace.TestS3kSpecialStageTraceParsing,com.openggf.trace.timing.TestHardwareTimingAuthorityGuard,com.openggf.game.TestHeaderNameRomDetectors,com.openggf.game.TestHudStaticArtLivesFrameMappings,com.openggf.game.sonic1.TestSonic1LivesHudDonation,com.openggf.game.sonic2.TestSonic2LivesHudDonation,com.openggf.game.sonic3k.TestSonic3kLivesHudPaletteOverride,com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchitecturalReviewGuard" \
  test
```

| Revision / set | Tests | Failures | Errors | Result |
|---|---:|---:|---:|---|
| Fork baseline `eb1b138c4`, comparable set excluding tranche tests | 127 | 1 | 0 | `TestTraceDataParsing.parsesRecordedRingFloorCheckCounterPhase` expected `2`, got `null` |
| Consolidation tranche after detector compatibility correction, complete set | 151 | 1 | 0 | Same method and assertion only |

The pre-existing HCZ/MGZ metadata do not contain
`ring_floor_check_counter_phase`. This is a known fixture failure, so focused
acceptance is preservation of that one failure with no additional failure or
error.

## Clean full-suite comparison

The baseline was checked out detached at `eb1b138c4`; the tranche was run in
its feature worktree. Each run used:

```bash
mvn clean test
```

| Revision | Suites | Tests | Failures | Errors | Skipped | Failure/error set |
|---|---:|---:|---:|---:|---:|---|
| Detached clean baseline `eb1b138c4` | 1,726 | 13,497 | 1 | 1 | 35 | `TestGameLoop` only |
| Consolidation tranche before detector compatibility correction | 1,728 | 13,521 | 1 | 1 | 35 | Same `TestGameLoop` only |

The unchanged `TestGameLoop` cases were
`traceRealtimeRewindRunsBeforePlaybackInputBridge` (failure: expected `true`,
got `false`) and
`setupAdmissionPrecedesSeamlessBoundaryAndTraceCameraMutations` (error:
`StringIndexOutOfBoundsException`, `Range [34669, -1) out of bounds for length
191573`). That clean tranche run added two suites and 24 tests but no failure
or error. The subsequent detector compatibility correction adds one reflection
test; its focused verification is recorded below. A future clean tranche sweep
is therefore expected to report 13,522 tests, but this correction does not
retroactively change the completed clean-run measurement above.

## Detector override compatibility correction

Review identified that moving `canHandle(Rom)` to a final public method on the
abstract template removed the concrete detectors' previously overridable
declared methods. The correction keeps the shared algorithm in protected final
`canHandleHeaderName(Rom)` and restores a public, non-final forwarding
`canHandle(Rom)` declaration on each concrete detector.

The focused detector test first failed with 11 tests / 1 failure / 0 errors:
reflection reported `NoSuchMethodException` for all three concrete classes.
After the correction, the detector and architecture command ran 45 tests / 0
failures / 0 errors. The complete focused tranche command then ran 151 tests /
1 failure / 0 errors, retaining only the known trace-metadata assertion.

## Methodology and termination state

`clean` is necessary because Surefire report files persist under
`target/surefire-reports`; a prior aggregate included reports from the focused
run and therefore falsely attributed seven failures and 24 errors to the full
suite. Each clean Maven process stopped making progress after emitting its
final output, but its XML reports had already been written. After confirming
the complete XML report set and aggregating only those freshly generated files,
the hung process was terminated. The table above is the authoritative clean-run
comparison; it does not claim a Maven zero exit because the known `TestGameLoop`
failure/error remain.
