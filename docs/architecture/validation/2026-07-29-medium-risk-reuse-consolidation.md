# Medium-Risk Reuse Consolidation Validation

**Date:** 2026-07-29
**Branch under validation:** `feature/ai-reuse-consolidation-next`
**Base:** `8c9b7378b3bf255bd979292c61a4b8584272c12c`
**Exact reviewed test head:** `e6a366ba354c2dbb6a0d5a390224a7d42dc376f9`

## Environment

Validation used Maven 3.9.16 on OpenJDK 21.0.11 (`mvn -v`) with the
discovered ROMs:

- Sonic 1: `/home/farrell/code/projects/OpenGGF/s1.gen`
- Sonic 2: `/home/farrell/code/projects/OpenGGF/s2.gen`
- Sonic 3 & Knuckles: `/home/farrell/code/projects/OpenGGF/s3k.gen`

Every Maven command supplied those paths through `sonic1.rom.path`,
`sonic2.rom.path`, and `s3k.rom.path`.

## Focused tranche suite

The exact reviewed head ran the prescribed focused command:

```bash
mvn -Dmse=off \
  -Dsonic1.rom.path=/home/farrell/code/projects/OpenGGF/s1.gen \
  -Dsonic2.rom.path=/home/farrell/code/projects/OpenGGF/s2.gen \
  -Ds3k.rom.path=/home/farrell/code/projects/OpenGGF/s3k.gen \
  -Dtest=com.openggf.game.TestBuiltInRomDetectors,com.openggf.game.TestRomDetectionService,com.openggf.game.TestHeaderNameRomDetectors,com.openggf.tests.rules.TestRomCacheAvailability,com.openggf.tools.TestCliArguments,com.openggf.tools.TraceCaptureToolArgsTest,com.openggf.tools.TestTraceBenchmarkToolArgs,com.openggf.tests.trace.TestRecordedInputRows,com.openggf.game.TestSpecialStageInputMapper,com.openggf.game.rewind.TestLiveRewindLogicalInput,com.openggf.tests.trace.s1.TestS1SpecialStageTraceReplay,com.openggf.tests.trace.s2.TestS2SpecialStageTraceReplay,com.openggf.tests.trace.s2.S2SpecialStageReplayDeterminismTest,com.openggf.tests.trace.s3k.TestS3kSpecialStageTraceReplay,com.openggf.tests.trace.runs.TestS1GhzMazeRoundTripChain,com.openggf.tests.trace.runs.TestS2EhzHalfpipeRoundTripChain,com.openggf.tests.TestArchUnitTestRules,com.openggf.tests.TestArchUnitRules \
  test
```

| Tests | Failures | Errors | Skipped | Maven exit |
|---:|---:|---:|---:|---:|
| 106 | 0 | 0 | 0 | 0 |

This covers detector delegation, shared CLI parsing, reusable recorded input
rows, special-stage and round-trip paths, logical input, and the architecture
guards selected for this tranche.

## Validation-discovered suite interaction

The Task 4 branch sweep reported three cases that passed in its base sweep:

- `TestPlayableSpriteRollSpeed.s3kTailsStopsRollingBelowMinimumRollSpeedThreshold`
  (expected ground velocity `65409`, actual `0`);
- `TestLiveTraceComparatorObserver.existingFiveArgConstructorDelegatesWithNullObserver`
  (expected error count `0`, actual `2`); and
- `TestLiveTraceComparatorObserver.nullObserverIsHonoured`
  (expected error count `0`, actual `2`).

The first Task 5 action was an identical clean branch sweep before any fix.
It reproduced all three cases in a complete 1,731-XML report set: 13,548
tests, 4 failures, 1 error, and 31 skips including the two shared
`TestGameLoop` cases. Surefire assigned the roll-speed failure to `jvmRun1`
and both comparator failures to `jvmRun2`. The comparator diagnostics
identified the contaminated field directly:
`sidekick_present expected=0 actual=1`.

Two initial candidates were falsified in isolated reused forks:

- `LiveTraceComparatorTest` plus `MismatchRingBufferTest` did not contaminate
  `TestLiveTraceComparatorObserver` (14 tests green); and
- `TestTraceCaptureUnifiedAudio` did not contaminate
  `TestLiveTraceComparatorObserver` (10 tests green).

The reduced same-JVM reproductions were:

```bash
mvn -Dmse=off \
  -Dsonic1.rom.path="$S1_ROM" \
  -Dsonic2.rom.path="$S2_ROM" \
  -Ds3k.rom.path="$S3K_ROM" \
  -Dsurefire.forkCount=1 \
  -Dsurefire.reuseForks=true \
  -Dsurefire.runOrder=alphabetical \
  -Dtest=com.openggf.tools.TestRecordingFrameDriverInputOnly,com.openggf.trace.live.TestLiveTraceComparatorObserver \
  test

mvn -Dmse=off \
  -Dsonic1.rom.path="$S1_ROM" \
  -Dsonic2.rom.path="$S2_ROM" \
  -Ds3k.rom.path="$S3K_ROM" \
  -Dsurefire.forkCount=1 \
  -Dsurefire.reuseForks=true \
  -Dsurefire.runOrder=alphabetical \
  -Dtest=com.openggf.tests.TestHtzBgTilemapDiagnostic,com.openggf.tests.TestPlayableSpriteRollSpeed \
  test
```

| Reproducer | Branch before fix | Exact base `8c9b7378b` | Branch after fix |
|---|---:|---:|---:|
| Recording-frame/comparator | 6 tests, 2 failures | 6 tests, 2 failures | 6 tests, green |
| HTZ diagnostic/roll speed | 14 tests, 1 failure | 14 tests, 1 failure | 14 tests, green |

Both class pairs ran in one reused fork. Reproducing the same failures at the
exact base proves the consolidation did not introduce the underlying state
leaks; its added classes changed full-suite fork neighbours and exposed them.

### Root cause and fix

`TestRecordingFrameDriverInputOnly` creates an S3K `HeadlessTestFixture` that
registers a sidekick in the active session sprite manager.
`TestLiveTraceComparatorObserver` then observes that legitimate runtime state
through `GameServices.spritesOrNull().getRegisteredSidekicks()`. Its trace
fixture expects no sidekick but the consumer class did not participate in the
project reset contract.

`TestHtzBgTilemapDiagnostic` loads HTZ and leaves its active collision system
in the session. `PlayableSpriteMovement` correctly prefers the sprite's
runtime collision system over its bootstrap collision system. The subsequent
roll-speed test therefore used the loaded HTZ terrain and reduced the tested
ground speed to zero. That consumer class also omitted the reset contract.

Production state ownership and lookup behavior are correct in both cases. The
minimal isolation fix annotates only the two state-sensitive consumer test
classes with `@FullReset` and
`@ExtendWith(SingletonResetExtension.class)`. No assertion, test order, retry,
or production behavior changed.

The two originally failing classes pass together after the fix (9 tests,
0 failures, 0 errors, 0 skips). The two fixed-order reproducers and the
106-test focused tranche command also pass as shown above.

### Committed deterministic regression

Commit `e6a366ba354c2dbb6a0d5a390224a7d42dc376f9` strengthens the isolation fix
with `RuntimeStateContaminationExtension`, a test-only
`BeforeEachCallback`. Before every affected consumer test, it starts from a
known test environment, registers a CPU-controlled Tails in the active session
sprite manager, and attaches a session collision system whose ground-wall
response sets ground speed to zero.

Both affected classes declare one ordered extension chain:

```java
@ExtendWith({
        RuntimeStateContaminationExtension.class,
        SingletonResetExtension.class
})
```

The contaminator runs first and the real reset callback runs second. Existing
consumer assertions then prove the full reset replaced both session owners
before the test body. With the contaminator present but the reset callback
temporarily absent, the focused command produced 9 tests and 5 failures: the
two comparator error-count assertions observed the sidekick, and three
roll-speed assertions observed zero ground speed. Restoring only the reset
extension made the same command green.

The exact reviewed head reran that green side of the regression:

```bash
mvn -Dmse=off \
  -Dsonic1.rom.path=/home/farrell/code/projects/OpenGGF/s1.gen \
  -Dsonic2.rom.path=/home/farrell/code/projects/OpenGGF/s2.gen \
  -Ds3k.rom.path=/home/farrell/code/projects/OpenGGF/s3k.gen \
  -Dtest=com.openggf.tests.TestPlayableSpriteRollSpeed,com.openggf.trace.live.TestLiveTraceComparatorObserver \
  test
```

| Tests | Failures | Errors | Skipped | Maven exit |
|---:|---:|---:|---:|---:|
| 9 | 0 | 0 | 0 | 0 |

This is the durable behavior-level regression. It does not inspect source or
annotations, add retries, or rely on Surefire class order.

## Frozen ArchUnit baseline metadata

The shared-layer frozen rule has 14 current entries. Its published count,
`.because(...)` metadata, and stored-rule description now all say 14 instead
of 20. The stored rule retains ID
`e0b8ef04-86e9-4001-b35e-c5de3ef4d940`; its violation payload remains
exactly 14 lines. The exception guide now correctly identifies entries 1–9 as
`MasterTitleRomPreview` dependencies and entries 10–14 as
`DefaultPowerUpSpawner` dependencies.

```bash
mvn -Dmse=off \
  -Dsonic1.rom.path=/home/farrell/code/projects/OpenGGF/s1.gen \
  -Dsonic2.rom.path=/home/farrell/code/projects/OpenGGF/s2.gen \
  -Ds3k.rom.path=/home/farrell/code/projects/OpenGGF/s3k.gen \
  -Dtest=com.openggf.tests.TestArchUnitRules,com.openggf.tests.TestArchUnitTestRules \
  test
```

| Tests | Failures | Errors | Skipped | Maven exit |
|---:|---:|---:|---:|---:|
| 30 | 0 | 0 | 0 | 0 |

## Exact-head clean same-ROM full-suite comparison

The retained exact base and exact reviewed head used the same normal four-fork
command:

```bash
mvn -Dmse=off \
  -Dsonic1.rom.path=/home/farrell/code/projects/OpenGGF/s1.gen \
  -Dsonic2.rom.path=/home/farrell/code/projects/OpenGGF/s2.gen \
  -Ds3k.rom.path=/home/farrell/code/projects/OpenGGF/s3k.gen \
  clean test
```

| Revision | XML suites | Tests | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| Base `8c9b7378b` final rerun | 1,728 | 13,508 | 2 | 1 | 31 |
| Exact head `e6a366ba3` | 1,731 | 13,548 | 1 | 1 | 31 |

Both Maven invocations reached the repository's known idle post-suite shutdown
tail after all XML reports were written. The retained base reports were stable
for at least 30 seconds before its idle tail was interrupted. At exact head,
all 1,731 XML reports were present and the newest report timestamp remained
unchanged for more than 80 seconds before only the idle tail was interrupted.
Counts above are aggregated from the archived `TEST-*.xml` files.

The exact-head failure/error set contains only the two cases also present on
the retained clean base:

- failure:
  `TestGameLoop.traceRealtimeRewindRunsBeforePlaybackInputBridge`; and
- error:
  `TestGameLoop.setupAdmissionPrecedesSeamlessBoundaryAndTraceCameraMutations`.

The fresh base additionally reports
`TestS3kSignpostInstance.fallingDispatchSkipsExpiringCooldownThenAppliesBumpBeforeGravity`
(expected `12`, actual `0`). Therefore no test passing in that base run fails
at exact head.

Task 4's earlier base sweep instead reported these 12 base-only
`TestS3kSnaleBlasterBadnik` cases:

1. `protectedCollisionPropertyReflectsAttackWithoutDestroying`
2. `firstUpdateSeedsClosedWaitAndCollisionFromRomSetup`
3. `coverAnimationCompletionRestoresProtectionDuringRemainingOpenWait`
4. `closedWaitRunsRawOpeningPrepBeforeVerticalMotion`
5. `registryCreatesSnaleBlasterAndMarksS3klSlotImplemented`
6. `earlyCloseWaitDoesNotRearmCollisionAfterProtectedHit`
7. `completedClosingPassUsesTheSharedOpenWaitAndReversesDirection`
8. `rollingPlayerWithinFortyEightPixelsForcesEarlyClose`
9. `openWindowCollisionPropertyZeroAllowsNormalBadnikDefeat`
10. `lowerShooterUsesItsNativeChildSubtypeIndependentOfParentSubtype`
11. `shooterChildFiresSingleProjectileAtRawAnimationOffsetFour`
12. `sharedWaitResumesVerticalScriptWithoutResettingItsCursor`

All 12 passed in the fresh exact-base rerun. The changed base-only identity
(Signpost rather than SnaleBlaster) is pre-existing suite variability and does
not alter the regression criterion: exact head introduces no
failure/error relative to the fresh same-ROM base.

## Generated files and evidence retention

The test-generated `docs/status/rewind-round-trip-gaps.md` was restored after
the exact-head full comparison. It exactly matches its pre-sweep backup,
SHA-256
`aa052b2c34908b1a3f700a275c54b87299ec876bd7eec13ba09cd74f2abb6380`.
The disposable exact-base worktree's generated copy was also restored to its
own tracked content.

Complete pre-fix, exact-base, predecessor-branch, and exact-head Surefire
reports are retained outside either worktree under
`/tmp/openggf-medium-reuse-task5-evidence/`. The exact-head reports are in
`head-e6a366ba3-final/`; the corresponding Maven log is
`head-e6a366ba3-maven.log`.

## Validation conclusion

The three validation-discovered branch-only failures were deterministic
pre-existing test-isolation defects exposed by the tranche's changed fork
neighbours. The reset-boundary fix makes both minimized reproducers green, and
the committed contaminator makes the reset contract deterministic without
depending on those neighbours. The exact-head 9-test regression and 30-test
ArchUnit verification are green, the frozen-rule metadata and ownership prose
are internally consistent, and exact head adds no full-suite failure or error
relative to the retained clean exact-base comparison.
