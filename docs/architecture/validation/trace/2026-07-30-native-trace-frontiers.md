# Native Trace Fleet Frontiers — 2026-07-30

## Scope

This is the authoritative post-publication measurement of the 64 concrete
gameplay `*TraceReplay` classes. Each class ran alone on JDK 21 with all three
verified ROM properties. Surefire and trace-comparator reports were copied
immediately after each class.

The eight S1 credits traces are **retained non-regenerable legacy fixtures**.
Their original movies are unavailable, so they were not regenerated and do
not carry the new PLC/DPLC audit streams. They remain useful physics
regressions, but they are not evidence for load-queue synchronization.

The per-class command shape was:

```bash
JAVA_HOME=<jdk-21> mvn -q -Dmse=relaxed \
  -Dsonic1.rom.path=<repo>/s1.gen \
  -Dsonic2.rom.path=<repo>/s2.gen \
  -Ds3k.rom.path=<repo>/s3k.gen \
  -Dtest=<fully-qualified-concrete-class> test
```

Context: branch `feature/ai-trace-fleet-regeneration`, worktree
`.worktrees/trace-fleet-regeneration`, source commit `122ed4095` plus the
reviewed in-worktree fixture publication and validator correction.

## Aggregate and baseline comparison

| Measurement | Green | Comparator red | Runtime error | Not executed |
|---|---:|---:|---:|---:|
| Pre-publication canonical baseline | 51 | 0 | 13 | 0 |
| Post-publication authoritative sweep | 9 | 42 | 13 | 0 |

The post-publication total is 108 Surefire tests: 46 assertion failures,
40 errors, and 0 skips. Four assertion failures belong to
`TestS3kAizTraceReplay`, whose class status is `error` because its target
replay terminates at the production queue runtime frontier.

The post-validator wildcard command
`mvn -q -Dmse=relaxed -Dsonic1.rom.path=<repo>/s1.gen
-Dsonic2.rom.path=<repo>/s2.gen -Ds3k.rom.path=<repo>/s3k.gen
-Dtest=*TraceReplay test` independently produced the same 108-test aggregate:
46 failures, 40 errors, and 0 skips. Its nonzero exit therefore reflects the
classified comparison/runtime frontiers below, not discovery or fixture-load
failure.

| Game | Classes | Green | Red | Error |
|---|---:|---:|---:|---:|
| S1 | 30 | 8 | 22 | 0 |
| S2 | 20 | 0 | 20 | 0 |
| S3K | 14 | 1 | 0 | 13 |

This is an expected visibility change, not 42 newly introduced physics
regressions: the regenerated S1/S2 fixtures now expose PLC/DPLC lifecycle
state that the engine did not previously compare. The first frontiers show
where engine load ownership or ordinal alignment differs from the ROM.
S3K remains at production queue-admission/completion-authority frontiers; its
special-stage replay remains green.

## Exhaustive frontiers

`—` means the class completed without a comparator error, or terminated
before a comparator report supplied that field. Runtime text is shown only
for error-status classes.

| Class | Status | First frame | First field | Comparator total / runtime frontier |
|---|---|---:|---|---|
| `TestS1Credits00Ghz1TraceReplay` | green (retained legacy) | — | — | — |
| `TestS1Credits01Mz2TraceReplay` | green (retained legacy) | — | — | — |
| `TestS1Credits02Syz3TraceReplay` | green (retained legacy) | — | — | — |
| `TestS1Credits03Lz3TraceReplay` | green (retained legacy) | — | — | — |
| `TestS1Credits04Slz3TraceReplay` | green (retained legacy) | — | — | — |
| `TestS1Credits05Sbz1TraceReplay` | green (retained legacy) | — | — | — |
| `TestS1Credits06Sbz2TraceReplay` | green (retained legacy) | — | — | — |
| `TestS1Credits07Ghz1bTraceReplay` | green (retained legacy) | — | — | — |
| `TestS1FzCompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 3,119 errors |
| `TestS1Ghz1CompleteRunTraceReplay` | red | 69 | `queue.s1_nemesis_plc.queued_fingerprints` | 982 errors |
| `TestS1Ghz1TraceReplay` | red | 69 | `queue.s1_nemesis_plc.queued_fingerprints` | 588 errors |
| `TestS1Ghz2CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 5,338 errors |
| `TestS1Ghz3CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 12,285 errors |
| `TestS1Lz1CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 14,940 errors |
| `TestS1Lz2CompleteRunTraceReplay` | red | 2 | `dynamic_art.edges` | 13,222 errors |
| `TestS1Lz3CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 25,466 errors |
| `TestS1Mz1CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 9,117 errors |
| `TestS1Mz1TraceReplay` | red | 69 | `queue.s1_nemesis_plc.queued_fingerprints` | 2,050 errors |
| `TestS1Mz2CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 16,575 errors |
| `TestS1Mz3CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 20,611 errors |
| `TestS1Sbz1CompleteRunTraceReplay` | red | 3 | `dynamic_art.edges` | 8,949 errors |
| `TestS1Sbz2CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 11,872 errors |
| `TestS1Sbz3CompleteRunTraceReplay` | red | 45 | `dynamic_art.edges` | 11,255 errors |
| `TestS1Slz1CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 6,559 errors |
| `TestS1Slz2CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 5,996 errors |
| `TestS1Slz3CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 15,690 errors |
| `TestS1SpecialStageTraceReplay` | red | 99 | `dynamic_art.edges` | 5,379 errors |
| `TestS1Syz1CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 9,404 errors |
| `TestS1Syz2CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 8,162 errors |
| `TestS1Syz3CompleteRunTraceReplay` | red | 1 | `dynamic_art.edges` | 14,815 errors |
| `TestS2Arz2LevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 23,618 errors |
| `TestS2ArzLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 14,858 errors |
| `TestS2Cnz2LevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 36,368 errors |
| `TestS2CnzLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 24,834 errors |
| `TestS2Cpz2LevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 36,172 errors |
| `TestS2CpzLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 15,622 errors |
| `TestS2DezEndingLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 5,017 errors |
| `TestS2Ehz1TraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 16,725 errors |
| `TestS2Htz2LevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 31,021 errors |
| `TestS2HtzLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 24,608 errors |
| `TestS2Mcz2LevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 28,658 errors |
| `TestS2MczLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 18,580 errors |
| `TestS2Mtz2LevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 40,291 errors |
| `TestS2Mtz3LevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 46,257 errors |
| `TestS2MtzLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 32,064 errors |
| `TestS2Ooz2LevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 36,123 errors |
| `TestS2OozLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 33,975 errors |
| `TestS2SczLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 7,506 errors |
| `TestS2SpecialStageTraceReplay` | red | 136 | `dynamic_art.edges` | 27,595 errors |
| `TestS2WfzLevelSelectTraceReplay` | red | 0 | `dynamic_art.outstanding_transfer_ids` | 13,230 errors |
| `TestS3kAizCompleteRunTraceReplay` | error | 0 | `queue.s3k_kos_direct.busy` | 8 comparator errors; AIZ intro KosM module FIFO full |
| `TestS3kAizTraceReplay` | error | 290 | `queue.s3k_kos_direct.busy` | 8 comparator errors; AIZ intro KosM module FIFO full |
| `TestS3kCnzCompleteRunTraceReplay` | error | 0 | `y` | 1,564 comparator errors; direct completion `#201`, engine pending none |
| `TestS3kCnzTraceReplay` | error | 0 | `queue.s3k_kos_direct.busy` | 20 comparator errors; direct completion `#15`, engine pending none |
| `TestS3kGumballBonusTraceReplay` | error | — | — | unconsumed direct completion `#22` at raw frame 1302, pre-main-loop |
| `TestS3kHczCompleteRunTraceReplay` | error | 0 | `queue.s3k_kos_direct.busy` | 30 comparator errors; direct completion `#80`, engine pending none |
| `TestS3kIczCompleteRunTraceReplay` | error | 0 | `queue.s3k_kos_direct.busy` | 95 comparator errors; direct completion `#236`, engine pending none |
| `TestS3kLbzCompleteRunTraceReplay` | error | 34 | `queue.s3k_kos_direct.busy` | 8 comparator errors; direct completion `#273`, engine pending none |
| `TestS3kMgzCompleteRunTraceReplay` | error | 0 | `queue.s3k_kos_direct.busy` | 17 comparator errors; direct completion `#134`, engine pending none |
| `TestS3kMgzTraceReplay` | error | 0 | `queue.s3k_kos_direct.busy` | 17 comparator errors; direct completion `#14`, engine pending none |
| `TestS3kMhzCompleteRunTraceReplay` | error | 0 | `y` | 43 comparator errors; direct completion `#330`, engine pending none |
| `TestS3kPachinkoBonusTraceReplay` | error | — | — | unconsumed direct completion `#339` at raw frame 2928, pre-main-loop |
| `TestS3kSlotsBonusTraceReplay` | error | — | — | unconsumed direct completion `#61` at raw frame 1054, pre-main-loop |
| `TestS3kSpecialStageTraceReplay` | green | — | — | 0 errors, 3 warnings |

All reported comparator warning totals are zero except the green S3K special
stage, which carries three non-blocking warnings.

## Next targets

1. Reconcile S2's segment-start transfer ledger: all 19 level captures first
   diverge at frame 0 on `dynamic_art.outstanding_transfer_ids`.
2. Reconcile S1's recorded callback/edge ordinals, then the GHZ1/MZ1
   `s1_nemesis_plc` queued fingerprints at frame 69.
3. Continue S3K production-submitted direct/module queue parity from the
   earliest admission/completion frontiers; do not create trace-derived work.
