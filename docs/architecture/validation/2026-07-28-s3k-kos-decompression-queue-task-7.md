# S3K direct Kosinski queue Task 7 validation

Date: 2026-07-28

## Outcome

Task 7 completed the `LevelManager` extraction and the independent-review
runtime fixes. The final runtime uses a game-neutral session-owned art
coordinator, a level-owned deferred resource loader, a four-entry physical
KosM parent FIFO, and one complete live direct child per admitted PRE. Fresh
level assembly and teardown preserve the one-shot initial
`Process_Sprites` lifecycle.

The focused queue, timing, architecture, rewind, title-card, headless, and
native matrices are green except for explicitly identified pre-existing
baseline failures. End-to-end publication is not green:

- the committed schema-1 ICZ complete-run fixture now reaches
  `KOS_MODULE_QUEUE#160`, where it expects a recorded completion after the
  engine has no pending parent;
- the fresh full Java attempt still contains broad baseline and publication
  failures; and
- Maven/Surefire again deadlocked after all 1,725 reports were written.
  Surefire 3.2.5 recognized `-Dsurefire.exitTimeout=30`, but the fork remained
  blocked in `futex_wait` for more than two minutes and emitted no timeout
  diagnostic.

Every reported missing-coordinator signature was repaired and independently
rerun. The full attempt exposed one remaining feature-attributable HCZ
late-route fixture error: a direct teleport activated four historical
horizontal geyser art producers and the target vertical geyser in one
dispatch, overflowing the physical four-parent FIFO. The fixture now drains
initial runtime art and warms the route window through production boundaries
before entering the vertical pipe; it passes independently and in the final
72-test compact matrix. The FIFO and production object behavior were not
weakened.

No trace fixture or disassembly content was changed.

## Task 7 commits

- `7cca4692d refactor: extract level pattern locator`
- `6c7d20200 test: align S3K queue verification harnesses`
- `aca800da4 docs: record S3K queue Task 7 validation`
- `9a00a9633 refactor: isolate deferred level resource loading`
- `3c253d154 refactor: isolate S3K runtime art queues`
- `35bbeef34 fix: preserve S3K Kos queue progression`
- `1c396116f test: use session-owned S3K art coordinator`
- `d2c96cccb test: align direct queue lifecycle cadence`
- `3e23ddb8a test: supply S3K runtime art fixtures`
- `42d160246 test: repair runtime art coordinator fixtures`
- `4859c294d test: warm late HCZ route art state`

The tracked Task 2, 3, and 4 feature reports under
`.superpowers/sdd/2026-07-28-s3k-kos-decompression-queue/` were removed as
requested. Their briefs and all other reports were preserved.

## Environment and ROM identity

```text
Apache Maven 3.9.16
Java version: 21.0.11
```

| Game | File | CRC32 | SHA-1 |
|---|---|---|---|
| Sonic 1 World REV01 | `Sonic The Hedgehog (W) (REV01) [!].gen` | `AFE05EEE` | `69E102855D4389C3FD1A8F3DC7D193F8EEE5FE5B` |
| Sonic 2 World REV01 | `Sonic The Hedgehog 2 (W) (REV01) [!].gen` | `7B905383` | `8BCA5DCEF1AF3E00098666FD892DC1C2A76333F9` |
| Sonic 3&K locked-on | `Sonic and Knuckles & Sonic 3 (W) [!].gen` | `63522553` | `CFBF98C36C776677290A872547AC47C53D2761D6` |

BizHawk 2.11 was supplied from `docs/BizHawk-2.11-linux-x64`.

## Focused Java verification

Architecture ownership is green:

```text
mvn -Dmse=off \
  "-Dtest=TestArchUnitRules,TestZoneEventRuntimeAccessGuard" test
Tests run: 30, Failures: 0, Errors: 0, Skipped: 0
```

The complete Task 1 timing/authority matrix is green:

```text
Tests run: 99, Failures: 0, Errors: 0, Skipped: 0
```

The Task 2 queue/decoder/rewind-guard matrix ran 23 tests. Twenty-two passed;
the sole failure is the known unrelated baseline gap:

```text
com.openggf.game.sonic3k.objects.badniks.Flybot767BadnikInstance
  #finalScalar#layoutWaitUsesRetainedRenderFlag
```

The Task 3 composition matrix, including physical-parent capacity, is green:

```text
mvn -Dmse=off \
  "-Dtest=TestS3kKosModuleQueue,TestS3kKosModuleReadiness,TestS3kKosStructuralSequence,TestS3kKosTimingRewindIntegration" \
  test
Tests run: 19, Failures: 0, Errors: 0, Skipped: 0
```

The Task 4 AIZ/ICZ matrix ran 112 tests: 107 passed, five failed, and none
errored. All five zone Kos rewind cases and the ICZ transition headless case
pass. The remaining failures match the branch baseline:

- two AIZ intro/sidekick assertions;
- the AIZ active-slot save harness whose live level has been reset; and
- two FixedAir snapshot assertions.

Additional post-review matrices:

```text
TestS3kObjectKosOwnerRewind,
TestS3kResultsKosQueueRewind,
TestS3kInitialObjectSetupLifecycle
Tests run: 24, Failures: 0, Errors: 0, Skipped: 0

TestS3kKosDecompressionQueueLifecycle
Tests run: 3, Failures: 0, Errors: 0, Skipped: 0

TestS3kKosDecompressionQueue,TestS3kKosModuleQueue
Tests run: 17, Failures: 0, Errors: 0, Skipped: 2

TestS3kHeadlessInLevelTitleCardProgression
Tests run: 2, Failures: 0, Errors: 0, Skipped: 0

TestS3kAiz1SkipHeadless
Tests run: 8, Failures: 0, Errors: 0, Skipped: 0

TestSonic3kTitleCardKosQueue
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0

TestTitleCardObjectExecution#titleCardLegacyPath_s3kAiz1
Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
```

The final post-fix compact matrix combined architecture ownership, object and
results queue rewind, initial object setup lifecycle, direct queue lifecycle,
title-card progression, AIZ skip, and the repaired HCZ late route:

```text
Tests run: 72, Failures: 0, Errors: 0, Skipped: 0
```

The remaining runtime-coordinator fixture classes were also isolated:

```text
TestEngine                                      26/26
TestZoneLayoutMutationPipeline                   9/9
TestActiveGameplayTeamResolver                  12/12
TestAiz2BossEndSequenceObjects                  36/36
TestAizPlaneIntroInstance                       14/14
TestS3kIczEndBossObject                         55/55
```

`TestGameLoop` passed 81/86 with its five known source-shape/reflection
baseline failures and no coordinator error. The targeted
`TestScalarOnlyCodecDeletion` results-screen case no longer fails at
coordinator lookup; it reaches its pre-existing null results-element failure.
Unrelated generic-recreate production seams were deliberately not changed.

With the S3K ROM property supplied explicitly, the direct and module queue
classes pass 7/7 and 10/10 respectively.

The committed-fixture inventory is included in the green 99-test Task 1
matrix and passes 2/2 independently.

## ICZ complete-run publication boundary

```text
mvn -Dmse=off "-Ds3k.rom.path=<locked-on-rom>" \
  "-Dtest=TestS3kIczCompleteRunTraceReplay" test
Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
```

The final error is:

```text
expected completion: KOS_MODULE_QUEUE#160
sha256:0fbbcb25822bda53fc0b212780f2218200ef117c753fa623fa1a05c66379f152
engine pending: <none>
```

This is later than the pre-fix `#158 engine job is not prepared` stop. The
committed fixture remains schema-1 load-only compatibility and does not carry
the schema-2 direct ledger needed for publication. The fixture was not edited
or bypassed.

## Final all-ROM Java attempt

The previous reports were moved to
`/tmp/openggf-task7-post4859-timeout-reports.PaLXIy/pre-run`. A fresh non-PTY
post-fix run used all three verified ROM properties and Surefire's documented
fork-exit timeout:

```text
mvn -Dsurefire.exitTimeout=30 \
  "-Dsonic1.rom.path=<rev01-s1>" \
  "-Dsonic2.rom.path=<rev01-s2>" \
  "-Ds3k.rom.path=<locked-on-s3k>" test
```

The run wrote all 1,725 `TEST-*.xml` files. The Maven JVM and its Surefire
fork then remained blocked in `futex_wait` for more than two minutes after
the final report, despite the 30-second setting. The Surefire 3.2.5 plugin
descriptor maps `${surefire.exitTimeout}` to
`forkedProcessExitTimeoutInSeconds`, confirming that the property was
recognized. It did not produce a timeout message or terminate the fork in
this state, so the exact Maven/Surefire process pair was sent `SIGTERM`; the
wrapper marker recorded exit status 143.

The fresh XML totals were:

```text
Tests represented: 13,488
Passed: 13,401
Failures: 53
Errors: 3
Skipped: 31
Execution result: infrastructure-incomplete; timeout ineffective; status 143
```

The three errors were:

- `TestGameLoop#userRecordingBoundaryDoesNotClassifyCursorZeroBeforeAnyMovieFrameApplied`
  (`NoSuchMethodException`);
- `TestGameLoop#setupAdmissionPrecedesSeamlessBoundaryAndTraceCameraMutations`
  (`StringIndexOutOfBoundsException`); and
- `TestScalarOnlyCodecDeletion#s3kResultsGenericRecreateRestoresCapturedConstructorStateAndPlayerRef`
  (the pre-existing null results-element `NullPointerException`).

The 53 failures remained distributed across 32 known baseline/publication
suites. A scan of all fresh XML found zero missing-coordinator signatures and
no feature-attributable HCZ FIFO error. The only
`S3K KosM module FIFO is full` text was a tolerated warning in the passing
`TestBonusStageReturnWaterRestore` report:

```text
runtime-art coordination is unavailable in these object services
S3K runtime art requires the S3K game-owned coordinator
```

## Native recorder verification

```text
BIZHAWK_HOME=<bizhawk> ./test.sh \
  --filter HardwareTimingEventEngine --jobs 1
15 passed; unrelated GpgxHost test skipped because S1_ROM_PATH was absent

env -u S1_ROM_PATH -u S2_ROM_PATH -u S3K_ROM_PATH \
  BIZHAWK_HOME=<bizhawk> ./test.sh --no-gates
408 total: 378 passed, 0 failed, 30 skipped

BIZHAWK_HOME=<bizhawk> \
S1_ROM_PATH=<rev01-s1> \
S2_ROM_PATH=<rev01-s2> \
S3K_ROM_PATH=<locked-on-s3k> \
  ./test.sh --no-gates
410 total: 410 passed, 0 failed, 0 skipped
```

## Repository integrity

`git diff --name-only -- src/test/resources/traces` produced no output.
`git diff --check` passed before the runtime commit. The full suite's
generated `docs/status/rewind-round-trip-gaps.md` change was restored and is
not part of this work. No ROM, trace fixture, or disassembly bytes were
renamed, copied, deleted, linked, or modified.
