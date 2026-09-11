# Handover follow-ups validation

## Context

- Foundation worktree: `.worktrees/handover-followups`
- Foundation branch: `bugfix/ai-handover-followups`
- Foundation commit: `19a220906`
- Timing worktree: `.worktrees/handover-aiz`
- Timing branch: `bugfix/ai-handover-aiz`
- JDK: OpenJDK 21.0.11
- ROM: verified Sonic 3&K locked-on image supplied outside the repository

The foundation commit contains the exact S2 native-recorder provenance assertion and the
route-led AIZ/HCZ/MHZ persistence corrections. This validation covers the subsequent
held-counter S3K timing work and remeasures the two unaffected route terminals.

## Authority and queue verification

Command:

```text
mvn -Dmse=off \
  -Dtest=TestHardwareTimingAuthorityGuard,TestHardwareTimingReplayPort,TestHardwareTimingService,TestLevelIterationHardwareTimingAdmissionOrder,TestS3kKosDecompressionQueue,TestS3kKosModuleQueue,TestS3kKosStructuralSequence,TestS3kHardwareTimingReplay,TestRecordingFrameDriverHardwareTiming,TestTraceSuppressedRowClosure \
  -Ds3k.rom.path=<verified-s3k> test
```

Result: 118 tests, 0 failures, 0 errors, 0 skipped.

Coverage proves that the suppressed-row entry:

- consumes only an exact compiled current-raw `PRE_MAIN_LOOP` edge after VInt;
- rejects missing, unprepared, wrong-boundary, wrong-kind/ordinal/fingerprint, stale, and
  gap-crossing work;
- bypasses only the ordinary last-serviced-boundary equality;
- preserves exact-once and rewind re-consumption;
- retires the real S3K direct FIFO head only through the production coordinator post-hook;
- leaves the KosM parent unprepared until its next ordinary `POST_OBJECTS` state step; and
- remains source-confined to the replay port and stateless timing observer.

## S3K owner, keep-green, and rewind verification

Command:

```text
mvn -Dmse=off \
  -Dtest=TestSonic3kAIZEvents,TestSonic3kHCZEvents,TestHCZWaterWallObjectInstance,TestS3kSignpostInstance,TestS3kResultsScreenObjectInstance,TestSonic3kSSEntryRingFormation,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard \
  -Ds3k.rom.path=<verified-s3k> test
```

Result: 151 tests, 0 failures, 0 errors, 0 skipped. The static rewind guard reported its
pre-existing tighten-baseline notice for `Sonic3kTitleCardManager#missingRewindAdapter`.

## Trace results

Each trace ran alone under the `trace-replay` profile.

| Route | Result | Terminal |
|---|---:|---|
| AIZ complete | 60 errors / 6,347 represented rows | module `#16`, raw 6351 `VINT_SERVICE`, exact engine job unprepared |
| HCZ isolated replay method | 28 errors / 3,295 represented rows | direct `#90`, engine pending `<none>` |
| MHZ complete | 865 errors / 7,218 represented rows | direct `#335`, engine pending `<none>` |

AIZ previously stopped after 6,344 rows at direct `#35`. The new path consumes that exact
prepared production job, retires its direct FIFO head, and then admits dependent module
`#15` through ordinary production ordering. The comparator frontier remains frame 1106
`queue.s3k_kos_direct.busy`; this change is deliberately scoped to the later admission
error.

The next AIZ edge is a different contract question. The native recorder first observes
module `#16` retirement on a held-counter row and stamps it `VINT_SERVICE`, but replay's
production parent is not yet prepared. Hard rule 4 therefore requires failure. The next
safe work is an audited native-recorder observation-row/service-row attribution review. A
stale stamp would require separately approved regeneration/publication; a validated stamp
would require a separately designed partial-CPU-prefix representation. No fixture changed.

HCZ and MHZ reproduce their exact earlier missing-production-submission terminals, proving
that the held-row capability did not loosen ordinary admission or synthesize route work.

## Deferred work

The misleading object-update parameter name remains unchanged. Approximately 590 update
implementations call the V-int run count `frameCounter`; the atomic `vIntRunCount` hierarchy
rename is reserved for a quiet-tree branch.

## Integration-suite isolation

The first development full suite exposed
`Sonic1SpecialStageResultsScreenTest#testRingBonusTalliesIntoScoreAndCompletes` only under
reused-fork ordering. Both the unchanged baseline and candidate passed the four-method class
alone. The test polls session-owned S1 PLC readiness, but its setup previously relied on
ambient gameplay state and could inherit a busy S1 module from an earlier class.

The test now uses class-scoped `SingletonResetExtension` plus `@FullReset`; its stale entry
in `TestSingletonLifecycleGuard.AMBIENT_GAMEPLAY_MODE_SETUP_BASELINE` was removed. No
production result-card or PLC behavior changed. Focused verification passed 12 tests across
the results class and lifecycle guard. The repeated candidate full suite passed this method
and introduced no new failure relative to the exact baseline failure set.

## Remaining gates

Before delivery, run the exact full JDK 21 suite against the updated `develop` baseline,
the integrated development branch, and the merged main workspace; compare exact failures,
then record fetch/pull, conflict handling, merge, push, and worktree cleanup in the
integration report.
