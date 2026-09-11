# Handover Follow-ups Implementation Plan

## Delivery baseline

- Integration branch: the branch currently checked out in the main workspace,
  `develop`.
- Development branch/worktree: `bugfix/ai-handover-followups` at
  `.worktrees/handover-followups`.
- Actual starting commit: `2c64e09d4925cd6d9628ea59ac9874b2e26e6829`. The handover's
  `220e0bc35` is an ancestor.
- Required JVM: JDK 21 as reported by `mvn -v`.
- Verified ROM inputs (resolved from the repository root at execution time):
  - S1 REV01: `<repo>/Sonic The Hedgehog (W) (REV01) [!].gen`,
    SHA-1 `69e102855d4389c3fd1a8f3dc7d193f8eee5fe5b`.
  - S2 REV01: `<repo>/Sonic The Hedgehog 2 (W) (REV01) [!].gen`,
    SHA-1 `8bca5dcef1af3e00098666fd892dc1c2a76333f9`.
  - S3K locked-on: `<repo>/Sonic and Knuckles & Sonic 3 (W) [!].gen`,
    SHA-1 `cfbf98c36c776677290a872547ac47c53d2761d6`.
- Baseline focused outcomes:
  - S2 contract: 6 tests, 1 failure, exact version mismatch.
  - AIZ complete: 57 comparator errors over 6,344 represented rows, then direct
    admission `#35` at raw 6346.
  - HCZ isolated replay method: 28 comparator errors over 3,295 represented rows,
    then direct admission `#90` at raw 3341.
  - MHZ complete: 865 comparator errors over 7,218 represented rows, then direct
    admission `#335` at raw 7221.

Run every complete-run replay in its own Maven invocation. Preserve each generated report
and context long enough to compare the next candidate, but do not commit generated target
output.

## Task 1: Correct the S2 canonical recorder assertion

Files:

- `src/test/java/com/openggf/tests/trace/s2/S2SpecialStageRecorderContractTest.java`

Steps:

1. Change only the committed-artifact assertion from `1.4-s2ss` to the exact native
   fixture stamp `1.4-s2ss-native`.
2. Leave the Lua source assertion at `1.4-s2ss`.
3. Do not edit or regenerate the fixture.

Verification:

```bash
mvn -Dmse=off \
  -Dtest=com.openggf.tests.trace.s2.S2SpecialStageRecorderContractTest test
tools/bizhawk-headless/test.sh --filter "S2 standalone special-stage"
```

If the native headless dependency is unavailable locally, record that exact environment
limitation; the Java contract is mandatory.

## Task 2: Publish and act on the bounded persistence audit

Files:

- `docs/architecture/audits/2026-08-02-aiz-hcz-mhz-persistence-audit.md`
- `src/main/java/com/openggf/game/sonic3k/objects/AizDrawBridgeObjectInstance.java`
- `src/test/java/com/openggf/game/sonic3k/objects/TestAiz2BossEndSequenceObjects.java`
- `src/test/java/com/openggf/level/objects/TestObjectManagerCounterBasedDynamicUnload.java`
- `src/main/java/com/openggf/game/sonic3k/objects/MhzSwingVineObjectInstance.java`
- `src/test/java/com/openggf/game/sonic3k/objects/TestMhzSwingVineObjectInstance.java`

### 2.1 Audit artifact

Publish the reviewed table for the concrete route shortlist. Include placement counts,
Java behavior, S&K ROM tail, disposition, and current test coverage. Mark the AIZ Draw
Bridge as actionable, the MHZ Swing Vine as characterization-gated, and the other entries
as exact self-managed, coupled, fixed-slot, or event-owned lifetimes.

### 2.2 AIZ Draw Bridge test-first correction

1. Add a direct contract test showing persistence is false and the cull reference is the
   fixed pivot rather than the bridge's moving display position.
2. Add a manager-level test using the real object class and a placement-backed spawn:
   - keep the bridge loaded at the native `$280` boundary;
   - move past the boundary and prove its dynamic slot unloads;
   - prove the respawn/load state is cleared rather than pinned.
3. Implement the ROM tail contract:
   - `isPersistent()` is false in normal/wait phases and true only during the triggered
     collapse countdown that self-deletes without a range tail;
   - `checksOutOfRangeAfterRoutine()` lets the wait operation consume the collapse trigger
     before the manager selects range deletion versus countdown persistence;
   - `usesCustomOutOfRangeCheck()` returns true;
   - `isCustomOutOfRange(cameraX)` computes
     `coarseBack = (cameraX - 0x80) & 0xFF80` and compares the saved `pivotX` bucket
     with unsigned native distance `> 0x280`.
4. Do not duplicate child deletion in the object. The manager owns ordinary range slot
   release, while the collapse operation retains its existing timed rider ejection and
   self-delete; verify referenced internal bridge pieces do not survive root removal.

### 2.3 MHZ Swing Vine characterization, then correction if reachable

1. Add a focused grabbed/off-screen test that exercises camera forcing and the root's
   coarse-back cull decision together.
2. If the real reachable state can cross the native boundary while a player is grabbed,
   change the root to non-persistent with the fixed `$280` anchor predicate, preserving
   `onUnload()` player-control cleanup and child-chain ownership.
3. If camera forcing makes the contradictory state unreachable, retain current production
   behavior and record the negative result in the audit rather than adding an artificial
   setup-only change.

Focused verification:

```bash
mvn -Dmse=off \
  -Dtest=TestAiz2BossEndSequenceObjects,TestMhzSwingVineObjectInstance,TestObjectManagerCounterBasedDynamicUnload,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard \
  test
```

Remeasure AIZ and MHZ after the candidate; a lifetime fix need not move the current early
frontier, but it must not worsen counts or move a previously matching field earlier.

## S3K route pipeline ownership

Tasks 3 through 5 are strictly serial: AIZ, merge/review/remeasure, then HCZ,
merge/review/remeasure, then MHZ. They must not run as concurrent edits because HCZ and
MHZ may both touch `Sonic3kObjectArtProvider`, and all three can affect shared placement or
transition code.

For each route, use a short-lived isolated worktree/branch created from the then-current
`bugfix/ai-handover-followups` tip:

| Route | Worktree | Local branch | Exclusive production ownership |
|---|---|---|---|
| AIZ | `.worktrees/handover-aiz` | `bugfix/ai-handover-aiz` | AIZ event, cutscene, and transition owner selected by triage |
| HCZ | `.worktrees/handover-hcz` | `bugfix/ai-handover-hcz` | StarPost, HCZ water-wall/geyser, or sidekick owner selected by triage; may own `Sonic3kObjectArtProvider` |
| MHZ | `.worktrees/handover-mhz` | `bugfix/ai-handover-mhz` | entry-ring/placement owner selected by triage; may own `Sonic3kObjectArtProvider` only after HCZ integration |

Each pipeline has three explicit handoffs:

1. **Triage:** read-only reproduction and instrumentation; name the causal or independent
   owner, exact files, focused failing test, and expected frontier effect. If evidence selects
   a file outside the candidate list or changes an architectural assumption, amend the design
   and plan and rerun their review gates before Fix.
2. **Fix:** test-first production change confined to the named owner; local focused tests and
   self-review.
3. **Verify:** an independent reviewer checks ROM citations, hard-rule compliance, changed
   files, focused tests, route replay, and cross-route counts. Blocking findings return to Fix.

After Verify is green, merge the local route branch into `bugfix/ai-handover-followups`,
rerun the three isolated route replays, remove the clean route worktree, and delete the fully
merged local route branch. Route branches are never pushed.

## Task 3: Resolve or re-characterize the AIZ direct `#35` frontier

Triage selected one exclusive ownership set:

- `docs/architecture/designs/2026-07-27-cross-game-hardware-timing-trace-contract.md`
- `src/main/java/com/openggf/trace/replay/TraceSuppressedRowClosure.java`
- `src/main/java/com/openggf/trace/timing/TraceHardwareTimingBoundaryObserver.java`
- `src/main/java/com/openggf/trace/timing/HardwareTimingReplayPort.java` for a current-row
  scheduled-boundary helper that shares ordinary edge-consumption bookkeeping
- `src/main/java/com/openggf/game/timing/RecordedCompletionAuthority.java` and
  `src/main/java/com/openggf/game/timing/HardwareTimingService.java` for one suppressed-row
  admission operation that bypasses only the ordinary last-service-boundary equality
- `src/test/java/com/openggf/tools/TestRecordingFrameDriverHardwareTiming.java`
- `src/test/java/com/openggf/trace/replay/TestTraceSuppressedRowClosure.java`
- `src/test/java/com/openggf/trace/timing/TestHardwareTimingReplayPort.java` only for the
  helper's current-row/stale/gap and rewind contracts, plus the stale pre-August-1
  same-row module/direct ordering expectation
- `src/test/java/com/openggf/game/timing/TestHardwareTimingService.java` for direct
  suppressed-authority boundary and ordinary-authority regression coverage
- `src/test/java/com/openggf/game/sonic3k/resources/TestS3kKosStructuralSequence.java`
  for real direct-FIFO/KosM-parent and rewind integration coverage
- `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java` only if a
  new source-level confinement assertion is needed

Investigation gate:

1. Preserve the extracted queue groups at frames 1106-1238, 6216-6288, and 6300 through
   the terminal edge as comparator context.
2. Pin the decisive raw-row facts: raw 6346 has unchanged gameplay-frame counter and a lag
   observation, while `hardware_timing.jsonl` records prepared direct `#35` at
   `PRE_MAIN_LOOP`; raw 6347 currently rejects the unconsumed edge.
3. Confirm the engine pending head before the edge is the real post-reload Monkey Dude child
   with kind, ordinal, and fingerprint matching `#35`.
4. Keep the separate one-frame seamless offset and intro/title-card queue windows recorded
   as later comparator frontiers; do not use them to deny an otherwise exact hardware edge.

Implementation gate:

- Add a focused red driver test with a real, already-prepared S3K direct job and a
  `PRE_MAIN_LOOP` edge on raw N. Drive raw N through the VBlank-only skip path and require
  the exact edge to be consumed, with no gameplay body, object scan, gameplay event update,
  coordinator pre-step, `HardwareTimingService.service`, new submission, or payload
  preparation. Require the coordinator post-service hook described below and preserve the
  closure's native event/object VBlank-only state advancement after admission.
- Add fail-closed coverage proving the suppressed-row path does nothing without a compiled
  current-row edge and rejects absent, unprepared, wrong-kind, wrong-ordinal,
  wrong-fingerprint, wrong-boundary, stale same-enum, and unrepresented-gap cases. Preserve
  exact-once deduplication and rewind re-consumption.
- Correct the pre-existing schema-2 ordering test to traverse same-row module
  `POST_OBJECTS` before direct loop-tail `PRE_MAIN_LOOP`, matching
  `HardwareServiceBoundary` and commit `ddaf8e152`; do not change production ordering.
- After the ordinary VInt closure, let `TraceSuppressedRowClosure` ask only an installed
  `TraceHardwareTimingBoundaryObserver` to expose a scheduled suppressed-row completion.
  The observer/port may apply only a current-raw `PRE_MAIN_LOOP` head and must share the
  existing port's ordering, identity, rollback, deduplication, cursor, and rewind path.
- Return whether the observer consumed an exact edge. Only on success, have the closure run
  `RuntimeArtCoordinator.afterTimingService(PRE_MAIN_LOOP)` so the production direct FIFO
  observes and retires its newly ready head. Do not run the coordinator pre-step or
  `HardwareTimingService.service`; the KosM parent remains owned by its next ordinary
  `POST_OBJECTS` state step.
- Add a real S3K queue integration test: successful suppressed admission retires the direct
  physical FIFO head through the post-service hook, leaves its KosM parent unprepared until
  the next ordinary `POST_OBJECTS` state step, and after restoring timing, port, and direct
  FIFO rewind snapshots re-admits/retires the same child exactly once.
- Add `RecordedCompletionAuthority.admitRecordedSuppressedRowCompletion` as a separate
  capability. It accepts only `PRE_MAIN_LOOP` and bypasses only
  `HardwareTimingService.lastServicedBoundary`, which is necessarily `VINT_SERVICE` after
  the row closure. It must reuse all ordinary FIFO-head, identity, fingerprint,
  preparation, already-released, and readiness mutations.
- Add direct service tests proving the suppressed authority admits exact prepared work
  after `VINT_SERVICE`, rejects `VINT_SERVICE` and `POST_OBJECTS`, and leaves ordinary
  admission's `lastServicedBoundary` enforcement unchanged.
- Pin closure ordering in `TestTraceSuppressedRowClosure`: scheduled exposure occurs once,
  immediately after `VINT_SERVICE`, followed only on success by the runtime-art coordinator
  post-service hook, then pending-title start, level-event VBlank state, and object
  VBlank-counter mutation. The no-edge path retains the existing VInt-only event sequence
  and does not call the coordinator.
- Extend `TestHardwareTimingAuthorityGuard` so the new authority method is callable only
  from `HardwareTimingReplayPort`, and the port helper is callable only from
  `TraceHardwareTimingBoundaryObserver`. Gameplay/replay drivers may not import or invoke
  either capability directly.
- Do not infer the boundary from lag/physics/auxiliary row contents. Do not advance a stale
  edge at `beginRawFrame`, run `HardwareBoundaryDispatch` or
  `HardwareTimingService.service`, create or prepare work, or change fixture data,
  gameplay owners, or shared game logic.
- Run `TestHardwareTimingAuthorityGuard`, replay-port/service/order tests, then AIZ replay.
  HCZ and MHZ must retain their producer-mismatch terminal errors rather than being admitted
  by this change.

Terminal condition:

- Advance/remove `#35` without new regressions, or publish an exact negative result with
  the demonstrated causal chain, unchanged baseline, and next safe owner.

Measured disposition:

- The current-row path admits exact prepared direct `#35` at raw 6346 and the coordinator
  post-hook retires the real FIFO head. The dependent module `#15` then prepares and admits
  through the next ordinary `POST_OBJECTS` step.
- The next terminal is module `#16` at raw 6351 `VINT_SERVICE`. Raw 6351 is another
  held-counter row, but the engine's production parent is not prepared because its ordinary
  `POST_OBJECTS` state step has not run since the direct child became ready. Do not add a
  timing-authority path that prepares it or runs the module coordinator.
- `HardwareTimingEventEngine.ObserveFrameEnd` and the frozen Lua scanner classify a module
  retirement first observed on a duplicate `Level_frame_counter` sample as `vint_service`.
  The current fixture timing was published before the August 1 `ddaf8e152` loop-tail phase
  migration; the later `8a6313bb3` regeneration explicitly found the timing bytes unchanged.
  Record this as the next safe owner: audited native-recorder service-row attribution. If
  the audit proves the stamp stale, correction and fixture regeneration/publication require
  separate approval. If the stamp is validated, a broader partial-CPU-prefix replay
  contract requires a separate design/review. Fixture edits and either speculative outcome
  are outside this branch.

## Task 4: Resolve or re-characterize the HCZ direct `#90` frontier

Likely files, selected only after measurement:

- `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kStarPostObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- `src/main/java/com/openggf/game/sonic3k/objects/HCZWaterWallObjectInstance.java`
- sidekick control owner identified from the frame-3253 report
- corresponding existing StarPost, HCZ water-wall, and sidekick tests

The Triage handoff must choose exactly one causal/independent owner set and its focused test
before Fix begins. `Sonic3kObjectArtProvider` cannot be assigned elsewhere during this task.

Steps:

1. Confirm fixture direct `#90` is the Stars3 child of module `28a69b8f...` and map the
   complete engine producer call chain into `spawnBonusStars()`.
2. Dump the StarPost object/contact and P1/P2 state from before frame 3253 through raw 3342.
3. Determine whether the earlier Tails motion divergence changes StarPost contact or whether
   the missing Stars3 submission is independent. Also compare the second geyser enemy-art
   reload because it can shift object/sidekick lifecycle without being the StarPost owner.
4. Add a focused test at the first causal production boundary, implement the smallest
   ROM-derived correction, and remeasure.

Terminal condition:

- Advance/remove `#90`, or record exact evidence that the edge remains downstream of an
  unresolved owner with unchanged baseline. Never synthesize the Stars3 work from timing
  data.

Measured disposition on the AIZ candidate: unchanged at 28 errors / 3,295 represented
rows, first frame 3253 `tails_x_speed`, then direct `#90` with no engine submission. The
suppressed-row capability is not reached because this is an ordinary production boundary;
the next safe owner remains the earlier Tails/water-wall interaction and its downstream
StarPost contact.

## Task 5: Resolve or re-characterize the MHZ direct `#335` frontier

Likely files, selected only after measurement:

- `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kSSEntryRingObjectInstance.java`
- `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- placement/slot or sidekick owner established by instrumentation
- `src/test/java/com/openggf/game/sonic3k/objects/TestSonic3kSSEntryRingFormation.java`
- relevant placement/lifetime tests

The Triage handoff must name the exact entry-ring/placement owner and focused test before Fix
begins. `Sonic3kObjectArtProvider` is available only after the HCZ branch is integrated and
cleaned up.

Steps:

1. Compare the three repeated ROM explosion-art submissions (`#334` at 1670, `#335` at
   7221, and `#336` at 7986) by entry-ring identity, slot, retirement reason, and producer
   call path.
2. Determine whether the later ring is absent because its placement never occurs, it
   retires through a different branch, or the earlier slot/RNG divergence changes the route.
3. Use the persistence audit result to rule the Swing Vine in or out; do not assume it
   explains pollen/bouncing-ring slot drift.
4. If an independent ROM-owned entry-ring lifecycle defect is isolated, add a focused test
   and fix it even if the frame-3420 ring comparator remains red. Otherwise publish the
   exact negative result and next safe owner.

Terminal condition:

- Advance/remove `#335`, or record an evidence-backed negative with unchanged baseline.
  The timing layer must not create the missing repeated work.

Measured disposition on the AIZ candidate: unchanged at 865 errors / 7,218 represented
rows, first frame 3420 `rings`, then direct `#335` with no engine submission. The
suppressed-row capability is not reached; the next safe owner remains the earlier
slot-phased bouncing-ring/entry-ring production chain rather than timing authority.

## Task 6: Regression, documentation, and review

Documentation files:

- `CHANGELOG.md` for any `src/main` fix.
- `README.md`, updated on the feature branch after the final behavior/validation result so
  the merge into `develop` stages the required release/change-log summary.
- `docs/status/trace-frontier-log.md` whenever a frontier, count, or interpretation changes.
- `docs/architecture/validation/trace/2026-08-02-handover-followups-validation.md`
- `docs/architecture/validation/trace/2026-08-02-handover-followups-integration.md`
- `docs/architecture/validation/trace/2026-08-02-handover-followups-end-to-end-review.md`

Focused authority/core batch:

```bash
mvn -Dmse=off \
  -Dtest=TestHardwareTimingAuthorityGuard,TestHardwareTimingReplayPort,TestHardwareTimingService,TestLevelIterationHardwareTimingAdmissionOrder,TestS3kKosDecompressionQueue,TestS3kKosModuleQueue,TestS3kKosStructuralSequence,TestS3kHardwareTimingReplay \
  '-Ds3k.rom.path=<repo>/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

S3K owner/keep-green batch:

```bash
mvn -Dmse=off \
  -Dtest=TestSonic3kAIZEvents,TestSonic3kHCZEvents,TestHCZWaterWallObjectInstance,TestS3kSignpostInstance,TestS3kResultsScreenObjectInstance,TestSonic3kSSEntryRingFormation,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard \
  '-Ds3k.rom.path=<repo>/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Trace batch, one invocation per class:

```bash
mvn -Ptrace-replay -Dmse=off -Dtest=TestS3kAizCompleteRunTraceReplay \
  '-Ds3k.rom.path=<repo>/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
mvn -Ptrace-replay -Dmse=off -Dtest=TestS3kHczCompleteRunTraceReplay#replayMatchesTrace \
  '-Ds3k.rom.path=<repo>/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
mvn -Ptrace-replay -Dmse=off -Dtest=TestS3kMhzCompleteRunTraceReplay \
  '-Ds3k.rom.path=<repo>/Sonic and Knuckles & Sonic 3 (W) [!].gen' test
```

Use this exact JDK 21 command unchanged at all three full-suite gates:

```bash
mvn -Dmse=off \
  '-Dsonic1.rom.path=<repo>/Sonic The Hedgehog (W) (REV01) [!].gen' \
  '-Dsonic2.rom.path=<repo>/Sonic The Hedgehog 2 (W) (REV01) [!].gen' \
  '-Ds3k.rom.path=<repo>/Sonic and Knuckles & Sonic 3 (W) [!].gen' \
  test
```

Before integration, fetch and fast-forward the main-workspace `develop` branch without
overwriting uncommitted files. Run the command first on the updated main-workspace baseline,
then in the development worktree, recording exact Surefire failures for both. After merge,
run the same command a third time on `develop` and compare exact failures. A pre-existing red
baseline is acceptable; any new or worsened failure is not.

The first baseline/development comparison found one development-only failure in
`Sonic1SpecialStageResultsScreenTest#testRingBonusTalliesIntoScoreAndCompletes`. Both
revisions pass the four-method class alone. Before repeating the development suite:

1. Add class-scoped `SingletonResetExtension` and `@FullReset` to
   `Sonic1SpecialStageResultsScreenTest`. The lighter `resetPerTest()` path does not clear
   `Sonic1PlcService`; the full reset rebuilds the inherited module boundary without a
   production workaround.
2. Remove
   `Sonic1SpecialStageResultsScreenTest.java#setUp` from
   `TestSingletonLifecycleGuard.AMBIENT_GAMEPLAY_MODE_SETUP_BASELINE`; the scanner already
   recognizes class-scoped `SingletonResetExtension`, so the exemption becomes stale.
3. Leave the results test's existing setup, teardown, frame budget, and result-card
   assertions unchanged.
4. Run `Sonic1SpecialStageResultsScreenTest` and `TestSingletonLifecycleGuard` together on
   the candidate, then rerun the exact full-suite command and
   compare only reports written by that invocation; generated rewind-gap inventory is not
   a deliverable.
5. If the class still fails, stop this correction and investigate the concrete inherited
   owner rather than widening reset or result-card behavior speculatively.

The first post-merge suite added one baseline-passing failure:
`TestS3kSignpostInstance#fallingDispatchSkipsExpiringCooldownThenAppliesBumpBeforeGravity`.
The complete signpost class passes alone, 17/17. Its falling-only local services omit
terrain, but `ObjectTerrainUtils` consults the ambient `GameServices` level; inherited
terrain can therefore land the signpost and zero `yVel`. Before the repeated post-merge
suite:

1. Add class-scoped `SingletonResetExtension` and `@FullReset` to
   `TestS3kSignpostInstance`.
2. Leave production code, its falling-state setup, and the ROM-order assertions unchanged.
3. Run `TestS3kSignpostInstance` with `TestSingletonLifecycleGuard`; no ambient-setup
   baseline entry currently exists for this class.
4. Repeat the exact post-merge full-suite command and require its failure/error identities
   to be a subset of the updated `develop` baseline.
5. Record the focused and repeated-suite results in both integration and end-to-end
   artifacts, then rerun the independent whole-delivery review. Commit and push only after
   that renewed review reports no blocker.

### Whole-delivery review gate

After code, tests, audit, validation, frontier log, changelog, and README are final, produce
the End-to-End Review artifact listed above. An independent reviewer must check:

- every requirement and acceptance criterion against a changed file or recorded result;
- architecture consistency and hard-rule-4 confinement;
- code quality, ROM citations, test-first evidence, documentation, and commit policy;
- exact focused/trace/full-suite outcomes and baseline comparisons;
- unresolved risks, explicit deferrals (including the atomic parameter rename), and the
  final human-review checklist.

Fix every blocking finding and rerun the independent review until it reports no blocker.
Only then proceed to integration.

## Task 7: Atomic rename deferral

Do not change the `frameCounter` parameter in this branch. Record in validation that
`ObjectExecutionController` supplies `ObjectManager.vblaCounter()` while approximately
590 update implementations use the misleading name. The dedicated future change must:

1. begin from a quiet tree;
2. rename the interface and every override/use to `vIntRunCount` atomically;
3. assess local bridges such as `resolveVIntRunCount` after the hierarchy rename;
4. compile before running focused and full tests;
5. avoid combining the rename with behavior changes.

## Commit and integration sequence

1. Commit the reviewed design and implementation plan with the implementation changes;
   include all required trailers and never bypass hooks.
2. Complete the serial AIZ, HCZ, and MHZ Triage -> Fix -> Verify pipelines, integrating and
   cleaning each route branch before starting the next.
3. Obtain an independent code/disassembly review for every changed S3K object or event owner;
   repeat until no blocking issue remains.
4. Produce the validation report, README release summary, and green End-to-End Review.
5. Fetch and fast-forward `develop`, record its full-suite baseline, merge the development
   branch into the main workspace without switching branches, and run the post-merge suite.
6. Complete the integration report with the merge commit, exact regression comparison,
   upstream/conflict reconciliation, and push result.
7. Push only `develop`.
8. Verify the worktree is clean/merged, remove it, delete the fully merged local feature
   branch, and prune worktree metadata.
