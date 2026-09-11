# S1/S2 PLC queue trace-policy validation

Date: 2026-07-29
Scope: S1/S2 native PLC service queues and replay validation

## Result

The completed S1/S2 PLC queues do not use trace recording as timing authority.
Every replay in the preserved concrete S1/S2 matrix passes from ROM-backed,
production-submitted queue work, matching the clean pre-queue baseline.

## Trace authority boundary

`TestS1S2PlcComparisonOnlyGuard` prohibits trace imports in
`Sonic1PlcService` and `Sonic2PlcService`, PLC queue references in trace
parsers, all non-Kosinski hardware work kinds, and every concrete S1/S2 PLC
service reference from trace replay or bootstrap code. The complementary skipped
presentation isolation guard verifies that the transition is production-owned:
the trace driver may select the existing LevelManager transition, but it cannot
provide phase counts or call a PLC service.

The hardware-timing authority and stream-loader guards remain unchanged. Their
existing scope is S3K module/direct Kosinski work; no S1/S2 PLC work kind or
readiness event was added.

```text
mvn -Dmse=off \
  "-Dtest=TestHardwareTimingAuthorityGuard,TestS1S2PlcComparisonOnlyGuard,\
TestHardwareTimingStreamLoader,TestSkippedPresentationPlcTraceIsolationGuard" test

Tests run: 45, Failures: 0, Errors: 0, Skipped: 0
```

The hook-install step reports that the sandbox cannot lock the shared Git
configuration file. It is non-fatal; Maven completed successfully.

## Serialized replay matrix

The clean pre-queue baseline comprises exactly 30 S1 and 20 S2 concrete replay
classes. The same 50 class names were selected from the preserved baseline log
list and replayed serially in one Surefire fork. This also exercises the
production reset/isolation boundaries between sequential replay classes.

```text
mvn -Dmse=off -Dsurefire.forkCount=1 \
  -Dsonic1.rom.path=/home/farrell/code/projects/OpenGGF/s1.gen \
  -Dsonic2.rom.path=/home/farrell/code/projects/OpenGGF/s2.gen \
  "-Dtest=<the preserved 50-class baseline list>" test

Tests run: 51, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS (7m24s)
```

There are 51 test methods because `TestS2SpecialStageTraceReplay` contains two
tests. All 50 selected replay classes pass. The queue-sensitive S1 Final Zone,
SBZ2, and SYZ2 paths and the S2 ARZ, CNZ, CPZ2, EHZ1, HTZ, and MTZ paths are
included in this matrix.

No trace fixture, BK2 recording, physics CSV, auxiliary JSONL, or timing-stream
file was edited. The result therefore validates production queue ordering and
native readiness behavior rather than a trace-synchronized substitute.
