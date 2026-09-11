# Canonical Trace Replay Baseline — 2026-07-30

## Scope and context

This is the read-only pre-publication baseline for the native trace-fleet
regeneration. It measures the currently committed canonical fixtures; it does
not install or inspect candidate payloads.

- Worktree: `.worktrees/trace-fleet-regeneration`
- HEAD at measurement start: `86d2ceff9cde33d0e09a44d7c1666c7c627e7162`
- Maven JVM: OpenJDK 21.0.11
- S1 ROM SHA-1: `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`
  (CRC32 `AFE05EEE`)
- S2 ROM SHA-1: `8bca5dcef1af3e00098666fd892dc1c2a76333f9`
  (CRC32 `7B905383`)
- S3K ROM SHA-1: `cfbf98c36c776677290a872547ac47c53d2761d6`
  (CRC32 `63522553`)

Concrete gameplay classes were enumerated mechanically from
`src/test/java/com/openggf/tests/trace/{s1,s2,s3k}/Test*TraceReplay.java`.
Abstract bases, report/policy/closure tests, run-chain infrastructure, and
guards were excluded. The resulting inventory is 64 classes: 30 S1, 20 S2,
and 14 S3K.

## Commands and aggregate result

The fleet-selection command was:

```text
JAVA_HOME=/usr/lib/jvm/java-21-openjdk mvn -q -Dmse=relaxed \
  -Dsonic1.rom.path=${REPO_ROOT}/s1.gen \
  -Dsonic2.rom.path=${REPO_ROOT}/s2.gen \
  -Ds3k.rom.path=${REPO_ROOT}/s3k.gen \
  -Dtest=*TraceReplay test
```

The wildcard returned nonzero. Its preserved Surefire reports contain 184
tests, 4 failures, 40 errors, and 0 skips. It also reported a fork-time
`ClassNotFoundException` for `TestS1Syz3CompleteRunTraceReplay`; that class
passed when run alone, so the wildcard failure is recorded as a fleet
selection/fork error rather than that class's frontier.

Each of the 64 concrete classes was then run alone with the same JDK and ROM
properties, after clearing `target/trace-reports` and Surefire reports. The
isolated aggregate is:

- `green`: 51 classes
- `red` (comparison assertion without runtime error): 0 classes
- `error` (runtime/setup/build): 13 classes
- `not executed`: 0 classes
- Surefire methods across isolated classes: 341 tests, 4 failures, 40 errors,
  0 skips

Per-class logs, Surefire XML, and trace reports are preserved under
`.scratch/trace-fleet-baseline-20260730/baseline/classes/<class>/`.

## Green classes

Every class below reached the end of its canonical fixture. Counts are class
counts, not parameterized methods.

| Game | Fixture recorder version | Green concrete classes |
|---|---|---|
| S1 | `credits-retro-1.4` | `TestS1Credits00Ghz1TraceReplay`, `TestS1Credits01Mz2TraceReplay`, `TestS1Credits02Syz3TraceReplay`, `TestS1Credits03Lz3TraceReplay`, `TestS1Credits04Slz3TraceReplay`, `TestS1Credits05Sbz1TraceReplay`, `TestS1Credits06Sbz2TraceReplay`, `TestS1Credits07Ghz1bTraceReplay` |
| S1 | `3.14` | `TestS1FzCompleteRunTraceReplay`, `TestS1Ghz1CompleteRunTraceReplay`, `TestS1Ghz2CompleteRunTraceReplay`, `TestS1Ghz3CompleteRunTraceReplay`, `TestS1Lz1CompleteRunTraceReplay`, `TestS1Lz2CompleteRunTraceReplay`, `TestS1Lz3CompleteRunTraceReplay`, `TestS1Mz1CompleteRunTraceReplay`, `TestS1Mz2CompleteRunTraceReplay`, `TestS1Mz3CompleteRunTraceReplay`, `TestS1Sbz1CompleteRunTraceReplay`, `TestS1Sbz2CompleteRunTraceReplay`, `TestS1Sbz3CompleteRunTraceReplay`, `TestS1Slz1CompleteRunTraceReplay`, `TestS1Slz2CompleteRunTraceReplay`, `TestS1Slz3CompleteRunTraceReplay`, `TestS1Syz1CompleteRunTraceReplay`, `TestS1Syz2CompleteRunTraceReplay`, `TestS1Syz3CompleteRunTraceReplay` |
| S1 | `3.5` | `TestS1Ghz1TraceReplay`, `TestS1Mz1TraceReplay` |
| S1 | `3.15` | `TestS1SpecialStageTraceReplay` |
| S2 | `9.11-s2` | `TestS2ArzLevelSelectTraceReplay`, `TestS2Arz2LevelSelectTraceReplay`, `TestS2CnzLevelSelectTraceReplay`, `TestS2Cnz2LevelSelectTraceReplay`, `TestS2CpzLevelSelectTraceReplay`, `TestS2Cpz2LevelSelectTraceReplay`, `TestS2DezEndingLevelSelectTraceReplay`, `TestS2Ehz1TraceReplay`, `TestS2HtzLevelSelectTraceReplay`, `TestS2Htz2LevelSelectTraceReplay`, `TestS2MczLevelSelectTraceReplay`, `TestS2Mcz2LevelSelectTraceReplay`, `TestS2MtzLevelSelectTraceReplay`, `TestS2Mtz2LevelSelectTraceReplay`, `TestS2Mtz3LevelSelectTraceReplay`, `TestS2OozLevelSelectTraceReplay`, `TestS2Ooz2LevelSelectTraceReplay`, `TestS2SczLevelSelectTraceReplay`, `TestS2WfzLevelSelectTraceReplay` |
| S2 | `1.4-s2ss` | `TestS2SpecialStageTraceReplay` |
| S3K | `6.37-s3k-completerun`, hardware timing schema 1 | `TestS3kSpecialStageTraceReplay` |

## Error classes and frontiers

These are runtime timing/queue errors rather than ordinary comparison-red
frontiers. Where a comparator report was written before the runtime error, its
first comparison error and totals are retained as secondary evidence; the
runtime error remains the class status.

| Class | Fixture version / timing schema | Runtime frontier | Comparator report before error |
|---|---|---|---|
| `TestS3kAizCompleteRunTraceReplay` | `6.38-s3k-completerun` / 2 | `Unable to queue AIZ intro sprite KosM art`; cause: `S3K KosM module FIFO is full` | none |
| `TestS3kAizTraceReplay` | `6.38-s3k` / 2 | same AIZ intro KosM FIFO-full error | none; class also had 4 independent assertion failures |
| `TestS3kCnzCompleteRunTraceReplay` | `6.38-s3k-completerun` / 2 | raw frame 5337, pre-main-loop: expected direct Kos completion ordinal 201, engine pending none | frame 0 `y`, 1544 errors, 0 warnings |
| `TestS3kCnzTraceReplay` | `6.37-s3k` / 1 | raw frame 17279, post-objects: module completion ordinal 9 fingerprint mismatch | frame 16661 `player_animation_id`, 78 errors, 0 warnings |
| `TestS3kGumballBonusTraceReplay` | `6.37-s3k-completerun` / 1 | segment end: unconsumed module completion ordinal 15 at raw frame 1303, post-objects | none |
| `TestS3kHczCompleteRunTraceReplay` | `6.38-s3k-completerun` / 2 | raw frame 1335, pre-main-loop: expected direct Kos completion ordinal 80, engine pending none | none |
| `TestS3kIczCompleteRunTraceReplay` | `6.38-s3k-completerun` / 2 | raw frame 1629, pre-main-loop: expected direct Kos completion ordinal 236, engine pending none | frame 1232 `x`, 81 errors, 0 warnings |
| `TestS3kLbzCompleteRunTraceReplay` | `6.37-s3k-completerun` / 1 | raw frame 36, post-objects: expected module completion ordinal 184, engine pending none | none |
| `TestS3kMgzCompleteRunTraceReplay` | `6.38-s3k-completerun` / 2 | raw frame 14631, pre-main-loop: expected direct Kos completion ordinal 134, engine pending none | none |
| `TestS3kMgzTraceReplay` | `6.37-s3k` / 1 | raw frame 14387, post-objects: expected module completion ordinal 9, engine pending none | frame 13903 `player_animation_id`, 64 errors, 0 warnings |
| `TestS3kMhzCompleteRunTraceReplay` | `6.37-s3k-completerun` / 1 | raw frame 37, post-objects: expected module completion ordinal 221, engine pending none | frame 0 `y`, 35 errors, 0 warnings |
| `TestS3kPachinkoBonusTraceReplay` | `6.37-s3k-completerun` / 1 | segment end: unconsumed module completion ordinal 220 at raw frame 2929, post-objects | none |
| `TestS3kSlotsBonusTraceReplay` | `6.37-s3k-completerun` / 1 | segment end: unconsumed module completion ordinal 36 at raw frame 1055, post-objects | none |

## Interpretation

The canonical baseline is fully green for the concrete S1 and S2 gameplay
fleet, but those fixtures predate the mandatory PLC/DPLC audit capability and
therefore do not validate load timing. S3K special stage is green under schema
1. The remaining S3K canonical fixtures expose a mixed schema-1/schema-2 queue
timing baseline and fail at production queue admission or completion
authority. Candidate publication must be evaluated against these exact
classes after regeneration; it must not treat the canonical S1/S2 green result
as PLC/DPLC timing coverage.
