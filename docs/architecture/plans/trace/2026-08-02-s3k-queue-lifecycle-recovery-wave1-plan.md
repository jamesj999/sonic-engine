# S3K Queue Lifecycle Recovery Wave 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the three standalone bonus timing-tail errors and restore both ROM-owned LBZ miniboss-box KosM submissions without weakening hardware timing authority.

**Architecture:** Derive the standalone bonus scope from existing recorder-observed `zone_act_state` transitions and add a narrow prefix-close operation that verifies all in-scope edges and an empty production ledger. Keep live exit-producer parity under separate ROM-boundary tests because `Restart_level_flag` timing does not align uniformly with the recorder's next-boundary observation. Model both LBZ `Queue_Kos_Module` calls in `Lbz1RobotnikEventController`, retaining scalar ordinals so handles can be rebound after rewind.

**Tech Stack:** Java 21, JUnit Jupiter, Maven Surefire, OpenGGF trace replay and hardware timing services.

## Global Constraints

- Runtime art bytes come only from the user-supplied ROM.
- Do not relax kind, ordinal, fingerprint, prepared-state, or service-boundary matching.
- Do not synthesize work, admit an absent job, drain a queue from trace data, or mutate canonical trace fixtures.
- Do not add game, zone, route, trace, or frame-number carve-outs to shared runtime code.
- Objects use injected `services()` and never call `getInstance()`.
- Follow test-driven development: add a focused failing test, verify the expected failure, implement minimally, and rerun it.
- Run Maven on JDK 21 and provide `-Ds3k.rom.path=<verified-s3k>` to ROM-backed tests.
- Update `docs/status/trace-frontier-log.md` whenever a frontier moves or a full sweep selects a new target.

---

### Task 1: Standalone bonus timing prefix closure

**Files:**

- Create: `src/test/java/com/openggf/tests/trace/s3k/TestS3kBonusTerminalScope.java`
- Modify: `src/test/java/com/openggf/tests/trace/s3k/AbstractS3kBonusStageTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/objects/TestPachinkoEnergyTrapObjectInstance.java`
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestGumballMachineExitTrigger.java`
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Modify: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`
- Modify: `src/main/java/com/openggf/trace/timing/HardwareTimingReplayPort.java`
- Modify: `src/test/java/com/openggf/trace/timing/TestHardwareTimingReplayPort.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kGumballBonusTraceReplay.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kPachinkoBonusTraceReplay.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kSlotsBonusTraceReplay.java`

**Interfaces:**

- Consumes: existing `TraceEvent.ZoneActState`, `TraceData.getEventsForFrame(int)`, `TraceFrame.frame()`, `RecordedCompletionAuthority.pendingSubmissions()`, and strict `HardwareTimingReplayPort.verifyRunComplete()`.
- Produces: a pure bonus-scope derivation seam, a replay hook that returns the inclusive last raw frame owned by a semantic prefix, and `HardwareTimingReplayPort.verifyPrefixComplete(int inclusiveRawFrame)` which ends admission only after verifying every edge through that frame and an empty pending ledger.

- [ ] **Step 1: Add failing structural-scope tests**

  Add tests that construct represented raw frames plus `ZoneActState` changes and assert:

  ```java
  assertEquals(1276, deriveLastBonusRawFrame(frames,
          List.of(new ZoneActState(0, 19, 0, 0, 12),
                  new ZoneActState(1277, 0, 0, 0, 140))));
  ```

  Also assert failure when frame 0 is not bonus `game_mode=12`, when no later departure exists, when departure has no represented predecessor, and when more than one candidate departure makes the scope ambiguous.

- [ ] **Step 2: Run the structural-scope tests and verify RED**

  Run:

  ```bash
  mvn -q -Dmse=off -Dtest=TestS3kBonusTerminalScope test
  ```

  Expected: compilation or assertion failure because the structural derivation seam does not exist.

- [ ] **Step 3: Implement structural scope derivation**

  Add a package-visible pure helper in the bonus replay base (or a focused package-private value type beside it) that:

  ```java
  // Contract, not implementation shorthand:
  // initial state: game_mode == 12
  // departure: first later ZoneActState with game_mode != 12
  // result: greatest represented raw frame strictly below departure.frame()
  ```

  Validate the complete shape described by the tests. Do not inspect zone names, bonus types, or hard-coded raw frames.

- [ ] **Step 4: Add failing prefix-close authority tests**

  Extend `TestHardwareTimingReplayPort` with independent cases proving:

  ```java
  port.verifyPrefixComplete(100); // succeeds only when next edge is >100 and pendingSubmissions is empty
  ```

  Required failures:

  - the next unconsumed edge is at or before 100;
  - the authority reports any pending production submission;
  - ordinary `verifyRunComplete()` with the same future edge remains strict and fails.

- [ ] **Step 5: Run prefix-close tests and verify RED**

  Run:

  ```bash
  mvn -q -Dmse=off -Dtest=TestHardwareTimingReplayPort test
  ```

  Expected: compilation failure because `verifyPrefixComplete` does not exist.

- [ ] **Step 6: Implement the narrow prefix close**

  In `HardwareTimingReplayPort`, add `verifyPrefixComplete(int inclusiveRawFrame)` with this order:

  ```java
  requireActive();
  reject any next edge whose rawFrame() <= inclusiveRawFrame;
  reject any non-empty authority.pendingSubmissions();
  authority.endRecordedAdmission();
  runComplete = true;
  ```

  Use existing diagnostic formatting for failures. Do not advance `edgeCursor`, mark future identities consumed, or change `verifySegmentEdges()` / `verifyRunComplete()`.

- [ ] **Step 7: Add failing structural-boundary control tests**

  Extend `TestS3kBonusTerminalScope` to exercise a pure decision seam that:

  - returns `CONTINUE` before the derived last raw frame regardless of live provider completion;
  - returns `CLOSE_PREFIX` on the derived last raw frame regardless of live provider completion;
  - rejects replay advancing beyond the derived last raw frame.

  These cases must fail against the initial live-equality implementation exposed by the first acceptance run.

- [ ] **Step 8: Integrate semantic closure into the replay harness**

  Add a default-null semantic-prefix hook to `AbstractTraceReplayTest`; only `AbstractS3kBonusStageTraceReplayTest` derives a boundary from `zone_act_state`. The S3K loop must drive and compare every row through the derived inclusive boundary, then call `HeadlessTestFixture.closeHardwareTimingReplayPrefix(inclusiveRawFrame)`, which mirrors ordinary close teardown but invokes `verifyPrefixComplete`. Do not consult `BonusStageProvider.isStageComplete()` for timing scope. Preserve strict ordinary closure for every replay without this hook.

- [ ] **Step 9: Add independent ROM exit-producer characterization**

  Add a Pachinko boundary case in `TestPachinkoEnergyTrapObjectInstance` proving `getCentreY()==-0x20` does not call `requestBonusStageExit()`; retain the existing `-0x21` immediate-exit test. Add package-local Gumball coverage for `GumballMachineObjectInstance.ExitTriggerChild` proving the inclusive ROM range `dx=[-0x100,0x200]`, `dy=[-0x10,0x40]`, outside-bound rejection, and exactly-once exit request. Retain `TestS3kSlotBonusStageRuntime.goalExitReportsCompletedProviderFadeAfterRomExitFadeCompletes` as the Slots 155-tick proof.

  Run:

  ```bash
  mvn -q -Dmse=off \
    -Dtest='com.openggf.game.sonic3k.objects.TestPachinkoEnergyTrapObjectInstance,com.openggf.game.sonic3k.objects.TestGumballMachineExitTrigger,com.openggf.game.sonic3k.bonusstage.slots.TestS3kSlotBonusStageRuntime' \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Expected: all characterization tests pass without changing production exit timing.

- [ ] **Step 10: Run focused tests and all three bonus traces**

  Run:

  ```bash
  mvn -q -Dmse=off \
    -Dtest='TestS3kBonusTerminalScope,TestHardwareTimingReplayPort,TestS3kGumballBonusTraceReplay,TestS3kPachinkoBonusTraceReplay,TestS3kSlotsBonusTraceReplay' \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Expected: all tests pass; the bonus traces close at their structural boundary with no ignored in-prefix work.

- [ ] **Step 11: Commit Task 1**

  Stage only Task 1 files and commit with project trailers. Use subject:

  ```text
  fix(trace): close standalone bonus timing prefixes
  ```

---

### Task 2: LBZ miniboss-box two-site KosM lifecycle

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Lbz1RobotnikEventController.java`
- Modify: `src/test/java/com/openggf/tests/TestS3kLbz1KnucklesSequenceHeadless.java`
- Create: `src/test/java/com/openggf/game/sonic3k/objects/TestLbz1RobotnikKosOwnerRewind.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kLbzCompleteRunTraceReplay.java`

**Interfaces:**

- Consumes: `S3kRuntimeArtCoordinator.from(services()).moduleQueue()`, `S3kKosModuleQueue.queue(Rom,int,int)`, `S3kKosModuleQueue.isReady(HardwareWorkHandle)`, `S3kKosModuleQueue.claim(HardwareWorkHandle)`, and `services().hardwareTiming().pendingHandle(HardwareWorkKind,long)`.
- Produces: controller-owned initial and collapse parent handles plus scalar ordinals, both submitting parent source `Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_BOX_ADDR` to destination tile `Sonic3kConstants.ART_TILE_LBZ_MINIBOSS_BOX`.

- [ ] **Step 1: Add a failing initialization submission test**

  In the existing ROM-backed headless test, snapshot `services().hardwareTiming().pendingHandles()` before the first controller update, run `ROUTINE_INIT`, then diff the pending handles filtered to `HardwareWorkKind.KOS_MODULE_QUEUE`. Assert exactly one new handle and inspect that handle through the module queue:

  ```java
  assertEquals(Sonic3kConstants.ART_KOSM_LBZ_MINIBOSS_BOX_ADDR,
          queue.descriptor(handle).sourceAddress());
  assertEquals(Sonic3kConstants.ART_TILE_LBZ_MINIBOSS_BOX * 32,
          queue.descriptor(handle).destinationAddress());
  ```

  Run a second update and assert no second initialization submission.

- [ ] **Step 2: Run the initialization test and verify RED**

  Run:

  ```bash
  mvn -q -Dmse=off \
    -Dtest='TestS3kLbz1KnucklesSequenceHeadless#lbz1RobotnikInitQueuesMinibossBoxKosmOnce' \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Expected: assertion failure because initialization currently loads only standalone renderer art.

- [ ] **Step 3: Implement the initialization owner lifecycle**

  Add runtime queue/handle reference fields and scalar ordinal fields. On `ROUTINE_INIT`, submit the parent once and retain its handle/ordinal. Do not yet add restore rebinding or ready-handle claiming; those behaviors follow their failing tests below.

- [ ] **Step 4: Add a failing collapse-clear submission test**

  Extend `lbz1RobotnikUnlocksCameraAfterEndingCollapseCompletes` or add a focused sibling. Diff `HardwareTimingService.pendingHandles()` filtered to `KOS_MODULE_QUEUE` before and after the `0x08 -> 0x0A` update, and assert exactly one additional descriptor with the same parent source/destination. A subsequent `ROUTINE_AFTER_COLLAPSE` update must not submit again.

- [ ] **Step 5: Run the collapse test and verify RED**

  Run:

  ```bash
  mvn -q -Dmse=off \
    -Dtest='TestS3kLbz1KnucklesSequenceHeadless#lbz1RobotnikCollapseClearRequeuesMinibossBoxKosmOnce' \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Expected: assertion failure because collapse-clear currently changes the routine without queueing the parent.

- [ ] **Step 6: Implement the second ROM producer site**

  Submit the same parent from the `collapseEventFinished()` transition before setting `ROUTINE_AFTER_COLLAPSE`. Keep its handle/ordinal independent of the initialization handle so both can coexist and be claimed exactly once.

- [ ] **Step 7: Add failing rewind/rebind/claim lifecycle coverage**

  Create `TestLbz1RobotnikKosOwnerRewind` using a ROM-backed `HeadlessTestFixture` for LBZ1. Spawn the controller through `GameServices.level().getObjectManager().createDynamicObject(...)`, capture with `fixture.gameplayMode().getRewindRegistry().capture()`, diverge, and restore with `RewindRegistry.restore(snapshot)`. After restore, locate the active `Lbz1RobotnikEventController` from `ObjectManager`; never keep using the pre-restore object reference. Reuse the drain and next-ordinal assertion pattern from `TestS3kObjectKosOwnerRewind`.

  Add RED cases for both stored ordinals that assert:

  - pending restore rebinds the exact original `KOS_MODULE_QUEUE` handles and does not increment the next ordinal;
  - ready restore claims each original handle exactly once and does not submit replacement work;
  - a non-negative captured ordinal whose pending handle is absent throws a missing-restored-job error rather than resubmitting.

  Run:

  ```bash
  mvn -q -Dmse=off \
    -Dtest='com.openggf.game.sonic3k.objects.TestLbz1RobotnikKosOwnerRewind' \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Expected: the new LBZ lifecycle cases fail because the controller has no rebind/claim service.

- [ ] **Step 8: Implement restore rebinding and ready claiming**

  At the start of controller updates, service both owner slots independently. For a non-negative ordinal with a null runtime handle, bind `services().hardwareTiming().pendingHandle(KOS_MODULE_QUEUE, ordinal)` or fail closed. Claim a ready handle once, then clear only that slot's handle and ordinal. Match the established owner behavior in `AizEndBossInstance.serviceBossArtQueue()` without sharing one handle between the two producer sites.

- [ ] **Step 9: Run rewind, owner, and coverage guards GREEN**

  Run:

  ```bash
  mvn -q -Dmse=off \
    -Dtest='TestS3kLbz1KnucklesSequenceHeadless,com.openggf.game.sonic3k.objects.TestLbz1RobotnikKosOwnerRewind,com.openggf.game.rewind.coverage.TestRewindCoverageGuard' \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Expected: all tests pass.

- [ ] **Step 10: Run the LBZ complete trace**

  Run:

  ```bash
  mvn -q -Dmse=off -Dtest=TestS3kLbzCompleteRunTraceReplay \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Expected: ordinals 279 and 280 are backed by real parent submissions. If an earlier or later non-LBZ-art divergence becomes the first error, record it rather than changing timing authority.

- [ ] **Step 11: Commit Task 2**

  Stage only Task 2 files and commit with project trailers. Use subject:

  ```text
  fix(s3k): model LBZ miniboss box KosM lifecycle
  ```

---

### Task 3: Wave 1 regression gates and next-frontier publication

**Files:**

- Modify: `docs/status/trace-frontier-log.md`
- Modify: `CHANGELOG.md`
- Create: `docs/architecture/validation/trace/2026-08-02-s3k-queue-lifecycle-wave1-validation.md`
- Modify after integration as required: `README.md`

**Interfaces:**

- Consumes: committed Tasks 1 and 2 plus the current three-ROM trace fleet.
- Produces: exact verification evidence and a ranked owner list for Wave 2; no production-code changes.

- [ ] **Step 1: Run the focused authority/queue guard matrix**

  Run:

  ```bash
  mvn -q -Dmse=off \
    -Dtest='TestS3kKosDecompressionQueue,TestS3kKosDecompressionQueueLifecycle,TestS3kKosModuleQueue,TestS3kKosModuleReadiness,TestS3kKosStructuralSequence,TestS3kHardwareTimingReplay,TestHardwareTimingReplayPort,TestHardwareTimingAuthorityGuard,TestHardwareTimingService,TestLevelIterationHardwareTimingAdmissionOrder,TestSpecialStageHardwareTimingLifecycle,TestTraceRunHardwareTimingCoordinator,TestTraceSuppressedRowClosure,TestLoadQueueTraceComparison,TestQueueDiagnosticSnapshot' \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Record total tests, failures, errors, and elapsed time; the baseline result is 138/138 green.

- [ ] **Step 2: Run the complete trace fleet**

  Run:

  ```bash
  mvn -q -Dmse=off -Dtest='*TraceReplay' \
    -Dsonic1.rom.path=<verified-s1> \
    -Dsonic2.rom.path=<verified-s2> \
    -Ds3k.rom.path=<verified-s3k> test
  ```

  Record every S1, S2, and S3K class line-by-line with pass/fail, error count, and first error frame/field. Confirm S1 remains 30/30 and S2 remains 20/20 unless the updated baseline itself changed.

- [ ] **Step 3: Publish frontier and validation evidence**

  Update `docs/status/trace-frontier-log.md` for all moved bonus/LBZ frontiers and the next selected S3K targets. Write the validation artifact with commands, commit/worktree context, exact outcomes, and the Wave 2 ordering: queue/producer-owned AIZ, CNZ, ICZ, and MGZ before HCZ/MHZ downstream gameplay divergences; keep the AIZ recorder-attribution lane separate.

- [ ] **Step 4: Update release-facing documentation**

  Add concise entries to `CHANGELOG.md`. Before merging the campaign branch into `develop`, update the README release/change-log section as required by project policy.

- [ ] **Step 5: Commit Task 3**

  Stage the design, plan, validation, frontier ledger, changelog, and README changes together with correct project trailers. Use subject:

  ```text
  docs(trace): publish queue lifecycle wave 1 frontier
  ```

- [ ] **Step 6: Write and review Wave 2 plan**

  Using the new first-error evidence, amend the campaign design if ownership assumptions changed and create the next dated implementation plan under `docs/architecture/plans/trace/`. Run the required design and plan subagent review loops before any Wave 2 production edit.
