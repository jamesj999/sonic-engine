# S3K Queue Lifecycle Recovery Wave 2 Implementation Plan

Date: 2026-08-02

Design: `docs/architecture/designs/trace/2026-08-02-s3k-queue-lifecycle-recovery-wave2-design.md`

## Execution rules

- Work serially in this campaign worktree because Tasks 1–5 share title/provider/transition ownership.
- Use strict RED/GREEN tests for every behavior change and commit each task independently.
- Do not edit hardware timing authority, direct/module queue matching, or canonical trace fixtures.
- After every shared-owner task, run its affected trace(s) and the focused authority/queue matrix. Record a newly exposed frontier instead of compensating for it.
- Update `docs/status/trace-frontier-log.md` whenever a canonical frontier moves.

## Shared verification commands

Before every Maven gate, confirm `mvn -v` reports JDK 21.

The exact focused authority/queue matrix is:

```bash
mvn -q -Dmse=off \
  -Dtest='TestS3kKosDecompressionQueue,TestS3kKosDecompressionQueueLifecycle,TestS3kKosModuleQueue,TestS3kKosModuleReadiness,TestS3kKosStructuralSequence,TestS3kHardwareTimingReplay,TestHardwareTimingReplayPort,TestHardwareTimingAuthorityGuard,TestHardwareTimingService,TestLevelIterationHardwareTimingAdmissionOrder,TestSpecialStageHardwareTimingLifecycle,TestTraceRunHardwareTimingCoordinator,TestTraceSuppressedRowClosure,TestLoadQueueTraceComparison,TestQueueDiagnosticSnapshot' \
  -Ds3k.rom.path=<repo>/s3k.gen test
```

The exact S1/S2 trace regression command is:

```bash
mvn -q -Dmse=off \
  -Dtest='com.openggf.tests.trace.s1.*TraceReplay,com.openggf.tests.trace.s2.*TraceReplay' \
  -Dsonic1.rom.path=<repo>/s1.gen \
  -Dsonic2.rom.path=<repo>/s2.gen test
```

The exact complete fleet command is:

```bash
mvn -q -Dmse=off -Dtest='*TraceReplay' \
  -Dsonic1.rom.path=<repo>/s1.gen \
  -Dsonic2.rom.path=<repo>/s2.gen \
  -Ds3k.rom.path=<repo>/s3k.gen test
```

Every source-task commit carries all repository trailers. A `fix`/`refactor` commit that changes `src/main` either stages `CHANGELOG.md` with `Changelog: updated` or supplies a specific policy-valid `Changelog: n/a: <reason>`. If a task moves a canonical frontier, it updates and commits `docs/status/trace-frontier-log.md` in that same task.

## Task 1: title payload readiness is not title-owner retirement

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kTitleCardKosQueue.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/titlecard/TestSonic3kTitleCardManagerRewind.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kTitleCardTeardownModel.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRewindSnapshot.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kAizTraceReplay.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kAizCompleteRunTraceReplay.java`

1. Add RED tests proving that claiming all queued title KosM payloads does not call the runtime-art release callback, and that cached title art also does not release at initialization/readiness.
2. Add RED tests proving the callback occurs once when `updateExit()` changes the owner to `COMPLETE`, never on later `COMPLETE` updates or reset.
3. Preserve and characterize skipped initial presentation at provider level: its provider-owned teardown releases once after the existing 34-tick production model. Add `TestSonic3kPlcArtRewindSnapshot` cases around tick 33/34 and after release. At this pre-policy stage, add only an executor false-overlay characterization proving it does not create the skipped-initial-title teardown model; its existing ordinary retirement callback remains unchanged until Task 2.
4. Move the callback from `finishQueuedArtIfReady()` to the single `EXIT -> COMPLETE` transition. Ensure rewind immediately before completion produces one release and restore after completion produces none.
5. Run:

   ```bash
   mvn -q -Dmse=off \
     -Dtest='TestSonic3kTitleCardKosQueue,TestSonic3kTitleCardManagerRewind,TestSonic3kTitleCardTeardownModel,TestSonic3kPlcArtRewindSnapshot,TestS3kHeadlessInLevelTitleCardProgression' \
     -Ds3k.rom.path=<repo>/s3k.gen test
   mvn -q -Dmse=off -Dtest='TestS3kAizTraceReplay,TestS3kAizCompleteRunTraceReplay' \
     -Ds3k.rom.path=<repo>/s3k.gen test
   ```

6. If either canonical AIZ frontier moves, update `docs/status/trace-frontier-log.md`. Commit Task 1 source/tests and required ledger movement as `fix(s3k): retire enemy art at title owner completion` with all trailers and changelog policy satisfied.

## Task 2: lease-backed transition admission infrastructure

**Files:**

- Modify: `src/main/java/com/openggf/game/ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/titlecard/Sonic3kTitleCardManager.java`
- Modify: `src/main/java/com/openggf/level/SeamlessLevelTransitionRequest.java`
- Modify: `src/main/java/com/openggf/level/LevelActTransitionExecutor.java`
- Modify: every S3K transition builder in `Sonic3kAIZEvents`, `Sonic3kCNZEvents`, `Sonic3kICZEvents`, `Sonic3kLBZEvents`, `Sonic3kMGZEvents`, `Sonic3kHCZEvents`, and `Sonic3kMHZEvents`
- Create: typed runtime-art admission policy/lease classes under the smallest shared game-resource or level-transition package
- Create: focused lease/policy tests beside the owning classes
- Modify: `src/test/java/com/openggf/tests/TestArchitecturalSourceGuard.java` or create a dedicated S3K transition-policy source guard
- Modify: rewind snapshot tests for `Sonic3kObjectArtProvider` and `Sonic3kTitleCardManager`
- Modify: `src/main/java/com/openggf/game/rewind/snapshot/PlcProgressSnapshot.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kPlcArtRewindSnapshot.java`
- Modify: `Sonic3kTitleCardManager.Snapshot` and `TestSonic3kTitleCardManagerRewind`

1. Add RED provider tests for a production-issued lease bound to generation, stable target-batch fingerprint, and owner kind. Cover exact-once consumption plus missing, stale, duplicate, wrong-generation, wrong-batch, and wrong-owner failure.
2. Extend `PlcProgressSnapshot` with the provider-issued generation, batch fingerprint, owner kind, lease id, and consumption state. Add RED rewind tests in `TestSonic3kPlcArtRewindSnapshot` for held, skipped-title-bound, and consumed leases. Extend `Sonic3kTitleCardManager.Snapshot` and its rewind test for a title-bound lease. Because production restores the title adapter before the provider adapter, title restore copies the scalar id without touching provider state; the next lease-dependent owner action performs the exact rebind after composite restore. Add a real production-registry round trip across lease replacement and consumption. None may claim whatever batch is currently pending.
3. Add `IMMEDIATE`, `PRESERVE_CURRENT`, `TITLE_OWNER`, and `RESOURCE_HANDOFF_OWNER` to the transition request contract. Keep the non-S3K default behavior compatible, but require every S3K builder to assign a policy explicitly through a guard.
4. Change target-act art registration so the S3K provider either:
   - issues/consumes an `IMMEDIATE` lease transactionally to arm the batch, without submitting parents until the existing following `processRuntimeArtQueue()` pump;
   - performs `PRESERVE_CURRENT` renderer refresh without clearing, scheduling, issuing, or consuming enemy work;
   - issues a held `TITLE_OWNER` lease for later binding; or
   - rejects `RESOURCE_HANDOFF_OWNER` at executor entry without any mutation until Task 5 atomically installs its concrete immutable handoff transfer path. The preflight runs before handoff claim, game-state reset, zone/act mutation, level loading, renderer refresh, batch registration, generation advance, lease issue, or admission.
5. Bind title leases during title initialization even when title art is cached. Store only rewind-safe scalar identity; rebind through the provider and fail closed if the exact lease is absent. Completion and skipped-initial teardown consume their own lease once. The legacy S3K retirement callback cannot fabricate or select a current lease, and skipped-title initialization may bind only an existing production-issued lease.
6. Assign every existing S3K builder explicitly without activating a deferred policy before its production owner is migrated. Task 2 ends with this safe intermediate table; Tasks 3–5 apply the reviewed final assignments at the same commit that installs each release owner:

   | Route | Policy | `showInLevelTitleCard` |
   |---|---|---:|
   | AIZ fire | `PRESERVE_CURRENT` | false |
   | CNZ | `IMMEDIATE` (Task 3 changes to `TITLE_OWNER`) | false |
   | ICZ | `IMMEDIATE` (Task 5 changes to `RESOURCE_HANDOFF_OWNER`) | false |
   | LBZ | `IMMEDIATE` (Task 4 changes to `TITLE_OWNER`) | false |
   | MGZ | `TITLE_OWNER` (Task 4 changes sole publisher) | true until Task 4 |
   | HCZ | `TITLE_OWNER` | true |
   | MHZ | `TITLE_OWNER` | true |

   The source guard requires an explicit policy at every stage; it does not require a deferred final policy before its owner exists.
7. Add an interim-policy regression proving `IMMEDIATE` creates/consumes the lease and arms the batch during executor execution, submits no parent inside that execution, and submits at the same subsequent provider pump as the pre-lease implementation. Add an executor-entry fail-closed regression proving premature `RESOURCE_HANDOFF_OWNER` use leaves handoff-registry ownership, game state, zone/act, level state, renderer state, scheduling, generation, lease identity, and admission unchanged. Add a ROM-characterization test for AIZ fire reload proving `PRESERVE_CURRENT` neither creates the skipped-initial teardown owner nor releases/registers an enemy batch: no new generation, clearing, or resubmission, and existing AIZ enemy descriptors/handles remain owned while act-local renderer registrations refresh.
8. Run focused provider, title, transition, rewind, source-guard, structural-sequence, and AIZ tests. Then run the exact 142-test matrix under **Shared verification commands**.
9. If AIZ or another canonical frontier moves, update `docs/status/trace-frontier-log.md`. Commit Task 2 source/tests and required ledger movement as `refactor(s3k): lease runtime art admission owners` with all trailers and changelog policy satisfied.

## Task 3: restore the CNZ carried-results title owner

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kCNZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java` only if new RED evidence contradicts its already-green carried-state behavior
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify: `src/main/java/com/openggf/level/LevelActTransitionExecutor.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: transition persistence/recreation support only when RED evidence requires it; the existing persistent-object handoff already carries the exact results owner
- Modify/Create: gameplay rewind-registry transition-manager rebinding tests
- Modify/Create: CNZ act-transition and results-owner tests
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzTraceReplay.java`

1. Add a RED production-path test that creates a live Act 1 results owner, executes the CNZ reload, reacquires the carried/recreated results owner from the target `ObjectManager`, and proves it remains the same semantic SST owner rather than only a delayed-control bridge.
2. Add RED dispatch-order assertions: the carried results owner finishes its child retirement, mutates/publishes the Act 2 title on the following dispatch, binds the exact held `TITLE_OWNER` lease, and queues the four title parents once.
3. Assert no executor-created title, no second four-parent batch, no premature enemy submission, and one enemy batch only after title COMPLETE. Drive results and title progression through the rebuilt `ObjectManager` and headless logical-frame/session dispatcher, not direct owner `update()` calls. Identify the exact four title and exact target enemy parents by stable hardware fingerprints or ROM source/destination, not cumulative counts. Add a transition-specific RED case where the session-registered, genuinely bound stale pre-reload title lease attempts to bind/consume the new CNZ lease and is rejected; compare the complete provider snapshot and hardware-job inventory before and after rejection.
4. RED production-registry evidence shows that rebuilding the transition managers leaves object/ring rewind adapters closing over discarded source managers. Add a narrow `GameplayModeContext` operation that deregisters/re-registers exactly `object-manager` and `rings`; call it after target event initialization and before resource-handoff transfer. Do not invoke broad level-adapter registration or move title, provider, event, solid, or tilemap owners. A lower-level registry test restores a pre-rebind object/ring snapshot after rebinding and proves only replacement adapters receive it while all other owner identity/order stays fixed.
5. Do not restore a full pre-reload zone snapshot after the act identity changes: `ZoneRuntimeRegistry` correctly rejects it. Instead, prove the pre-reload checkpoint restores before the transition, then exercise the existing `RewindBoundary.SEAMLESS_LEVEL_TRANSITION` completion and assert the old frame is inaccessible in both live and trace rewind history. Prove the new root plus post-recreation, pre-title-publication, and post-completion snapshots capture/restore through the rebound target managers.
6. Change CNZ from the Task 2 interim `IMMEDIATE` policy to `TITLE_OWNER` in the same commit that preserves the owner. The existing results object already branches on carried semantic state and publishes on the next dispatch, so do not alter it without new RED evidence. No shared results logic may branch on zone identity. Clarify the CNZ source comment: the event bridge owns delayed control release while the persistent results SST is carried.
7. Run focused CNZ transition/results/rewind tests, generic rewind-registry and seamless-transition boundary tests, `TestS3kCnzTraceReplay#replayMatchesTrace`, then the exact 142-test matrix under **Shared verification commands**.
8. If the canonical CNZ frontier moves, update `docs/status/trace-frontier-log.md`. Commit Task 3 source/tests and that required ledger movement as `fix(s3k): preserve CNZ results title ownership` with all trailers and changelog policy satisfied.

## Task 4: make MGZ and LBZ carried results the sole title publishers

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kMGZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kLBZEvents.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/S3kResultsScreenObjectInstance.java` for generic carried-title timing ownership only
- Modify: `src/main/java/com/openggf/level/objects/ObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Create/Modify: immutable game-neutral carried-title publication value beside the transition/object carry contract
- Modify: results rewind/recreate snapshots and tests for the carried timing scalars
- Modify/Create: MGZ/LBZ transition/results/title tests
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kMgzCompleteRunTraceReplay.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kLbzCompleteRunTraceReplay.java`

1. Add RED production-path tests proving MGZ and LBZ no longer admit/create competing title work while their carried results owners survive. MGZ stops asking the executor to publish a title; LBZ changes from interim immediate enemy admission to the final held title-owner lease.
2. Assert each carried owner publishes on its next native dispatch, queues exactly four title parents, binds its held lease, and releases exactly one target enemy batch at completion.
3. Add RED tests for an immutable carried-title publication value derived from the exact transition request. `LevelManager` passes it with the offset to persistent objects even when offsets are zero; default objects retain their existing offset hook. The results owner captures/restores/recreates the scalar reset-at-display, reset additional/phase-one overlap, player-lock, exit additional/phase-one overlap, and explicit-timing predicate. It performs no lookup of a current request or zone/route branch.
4. Define timing precedence with behavioral RED tests. Explicit carried timing suppresses the generic retained-results reset fallback: MGZ applies reset-at-display `12/6`, exit `10/5`, and its existing reset/lock booleans exactly once. Absent/default carried timing applies no request timing and preserves the existing retained reset-after-create `38/40` dispatch fallback for LBZ/CNZ. Assert actual reset/countdown and exit frames/ownership, not only stored values. Prove executor and carried owner cannot both consume timing.
5. Add a generic RED case for MGZ's carried semantic state: once `carriedAcrossSeamlessTransition` is true, retained reload state takes precedence over the historical pre-reload HCZ/MGZ transition predicate, so the owner publishes on its next dispatch instead of deleting itself. Change precedence without adding a new zone branch.
6. Atomically switch MGZ to `showInLevelTitleCard=false` and LBZ to `TITLE_OWNER`, and install the semantic precedence/timing owner in the same commit.
5. Run focused MGZ/LBZ transition/results/title tests, `TestS3kMgzCompleteRunTraceReplay`, `TestS3kLbzCompleteRunTraceReplay`, and the exact 142-test matrix under **Shared verification commands**.
6. Update `docs/status/trace-frontier-log.md` for every moved MGZ/LBZ canonical frontier. Commit Task 4 source/tests and ledger changes as `fix(s3k): publish carried transition titles once` with all trailers and changelog policy satisfied.

## Task 5: consume ICZ admission after transactional resource publication

**Files:**

- Modify: `src/main/java/com/openggf/game/sonic3k/events/IczSeamlessTransitionResourceHandoff.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/events/Sonic3kICZEvents.java`
- Modify: `src/main/java/com/openggf/level/SeamlessTransitionResourceHandoff.java` only if typed lease transfer belongs in the shared interface
- Modify/Create: ICZ transition handoff, ordering, failure, and rewind tests
- Modify: `src/test/java/com/openggf/game/sonic3k/events/TestSonic3kIczRewindRoundTrip.java`
- Modify: `src/test/java/com/openggf/level/TestSeamlessTransitionResourceHandoffRegistry.java`
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kIczCompleteRunTraceReplay.java`

1. Replace Task 2's fail-closed unsupported `RESOURCE_HANDOFF_OWNER` branch while changing ICZ from the interim `IMMEDIATE` assignment to `RESOURCE_HANDOFF_OWNER`, and add RED tests that the exact lease transfers with the existing chunk/block/module handles and is accepted once by the target ICZ event owner in the same commit. Keep registered handoff holders immutable: lease attachment produces a claimed-path `withAdmissionLease(...)` replacement rather than mutating the instance retained by registry snapshots.
2. Prove module-ready-before-direct and direct-ready-before-module both keep enemy admission held.
3. Add executor RED tests for failure after destructive handoff-registry claim but before target transfer/title bind. The claimed handoff/lease enters a terminal failed-transfer fence: it cannot be rebound to a later batch or silently retried, and its unconsumed lease can never release current work.
4. Inject ICZ transfer, claim, terrain-apply, and art-apply failures. Each leaves the lease unconsumed and target enemy work unsubmitted. If any handle was claimed or any terrain/art applied, record a terminal failed-publication fence in rewind state: later updates and restores rethrow/fail closed without retry, double-claim, reapply, or lease consumption. Duplicate/stale handoff acceptance or consumption also fails closed.
5. Add rewind tests in `TestSonic3kIczRewindRoundTrip` immediately before handoff acceptance, while either queue remains incomplete, after a terminal failed publication, and after successful publication. Extend `TestSeamlessTransitionResourceHandoffRegistry` with the critical shallow-snapshot regression: capture before claim, attach/consume a lease only on the claimed immutable replacement, restore, and prove the restored pre-claim handoff has no future lease/state. Also cover claimed-then-failed transfer. Restore retains exact handles/lease/fence and never duplicates the batch.
6. Make lease consumption the final successful step of `publishTransferredIcz2Resources()` after both claims and both applies.
7. Run focused ICZ event/handoff/rewind/registry tests, `TestS3kIczCompleteRunTraceReplay`, and the exact 142-test matrix under **Shared verification commands**.
8. Update `docs/status/trace-frontier-log.md` if ICZ moves. Commit Task 5 source/tests and ledger changes as `fix(s3k): order ICZ enemy art after resource handoff` with all trailers and changelog policy satisfied.

## Task 6: repair the generic late-placement lifecycle for the CNZ ring

**Files:**

- Modify: `src/main/java/com/openggf/level/objects/ObjectPlacementController.java` and/or its captured cursor state, only after RED evidence identifies the defect
- Modify/Create: generic placement manager, vertical/two-axis window, post-camera extension, remembered/dormant, and rewind tests
- Modify/Create: focused CNZ SS-entry-ring placement/retirement test
- Test: `src/test/java/com/openggf/tests/trace/s3k/TestS3kCnzCompleteRunTraceReplay.java`

1. Instrument through test seams, not production logging, to prove whether the ordered cursor reaches CNZ fixture slot 10 at `(0x2DC0,0x064C)`, subtype 4, and where eligibility/remembered state drops it.
2. Add the smallest generic RED reproducer with earlier remembered/dormant entries, two-axis eligibility changes, post-camera load-window extension, and a later eligible spawn. Include snapshot/restore immediately before the cursor crosses the later entry and assert identical pending-load order after restore.
3. Fix the generic placement/window/cursor state. Do not special-case object id, coordinates, zone, game, or trace.
4. Add a CNZ route test proving the real ring constructs, reaches existing `retireRing()`, and submits `ArtKosM_BadnikExplosion` (`0xDB406`, direct child `0xDB408`) exactly once.
5. Run all generic placement and rewind guards, S1/S2 placement suites, `TestS3kCnzCompleteRunTraceReplay`, the exact 142-test matrix, and the exact S1/S2 trace command under **Shared verification commands**.
6. Update `docs/status/trace-frontier-log.md` if CNZ complete moves. Commit Task 6 source/tests and ledger changes as `fix(level): preserve late placement cursor ownership` with all trailers and changelog policy satisfied.

## Task 7A: pre-correction Wave 2 fleet measurement

1. Run the focused route group:

   ```bash
   mvn -q -Dmse=off \
     -Dtest='TestS3kAizTraceReplay,TestS3kAizCompleteRunTraceReplay,TestS3kCnzTraceReplay,TestS3kCnzCompleteRunTraceReplay,TestS3kIczCompleteRunTraceReplay,TestS3kLbzCompleteRunTraceReplay,TestS3kMgzCompleteRunTraceReplay' \
     -Ds3k.rom.path=<repo>/s3k.gen test
   ```

2. Run the exact 142-test authority/queue matrix under **Shared verification commands**.
3. Run the exact complete three-ROM `*TraceReplay` command under **Shared verification commands** and publish all 64 classes line-by-line.
4. Draft `docs/architecture/validation/trace/2026-08-02-s3k-queue-lifecycle-wave2-validation.md` and update the frontier ledger with the measured pre-correction fleet. Do not finalize or commit while Tasks 7B/7C remain.
5. Keep AIZ raw 6351 recorder attribution and MGZ-standard/HCZ/MHZ gameplay-first lanes explicitly separate from queue-owner results.
6. Deliver the draft and exact command evidence without committing; Tasks 7B/7C correct the two fleet/delivery blockers before final publication.

## Task 7B: restore skipped-title SST dispatch cadence

1. Add RED model and production-provider tests proving the last skipped-title child retires without releasing admission, and the lower-slot owner observes zero only on its following dispatch. Schedule a real enemy batch and assert its exact first parent/direct child is absent through trace frame 33 and submitted at frame 34.
2. Correct `Sonic3kTitleCardTeardownModel` owner/child ordering. Provider tick 34 (zero-based trace frame 33) retires the final child; provider tick 35 (trace frame 34) lets the lower-slot owner observe zero and release. Preserve the exact production-issued lease, sole-owner release, rewind scalars, and exactly-once completion. Do not add a generic provider delay or any game/zone/route/frame/trace branch.
3. Add rewind coverage for the distinct tick-34 state where children are drained but the owner has not observed completion, followed by exactly one release on tick 35 and none after restoring a post-release snapshot. Update comments/tests that incorrectly describe same-tick observation. Run focused teardown/provider/rewind tests, all eight affected traces (CNZ standard+complete, HCZ complete, ICZ complete, LBZ complete, MGZ standard+complete, MHZ complete), both AIZ traces, and the exact 142-test matrix.
4. Update every moved first-error/error-count entry in `docs/status/trace-frontier-log.md`. Keep the Wave 2 validation as an uncommitted draft until Task 7D. Commit as `fix(s3k): preserve skipped title SST dispatch order` with required changelog and trailers after independent review is GREEN.

## Task 7C: clear delivery guards without weakening them

1. Add/retain RED evidence that the complete `LevelManager.applySeamlessTransition(...)` behavior is unchanged while the class exceeds the 2,500-effective-line ratchet. Extract the full orchestration into a focused package collaborator. Preserve transition switch behavior, exact `RELOAD_SAME_LEVEL` reconstruction including all admission/resource/title-timing fields, exception/finally cleanup, and reload-frame bridge. Keep `LevelManager` as a small facade and make the existing guard pass below 2,500; never raise the threshold.
2. Fix the pre-existing agent-guidance false negative in `TestArchitecturalSourceGuard` by whitespace-normalizing the Markdown under test and requiring the exact current capitalized sentence. Do not modify `AGENTS.md`/`CLAUDE.md` or weaken the semantic phrase.
3. Run focused seamless-transition/executor/act-transition tests, the full `TestArchitecturalSourceGuard`, exact 142 matrix, and the ordinary three-ROM full suite. Commit as `refactor(level): extract seamless transition orchestration` with required changelog/trailers after independent review is GREEN.

## Task 7D: final Wave 2 publication, integration, and cleanup

1. Rerun the focused seven-route group, exact 142 matrix, and exact complete three-ROM `*TraceReplay` command. Replace the validation draft with the final post-correction 64-class line-by-line frontier and reconcile the ledger, `CHANGELOG.md`, and `README.md`.
2. Commit the reviewed Wave 2 design/plan and final validation/docs with required trailers.
3. Before integration, fetch the remote and fast-forward the main-workspace `develop` branch without disturbing user changes. Record the full suite on that updated branch with `mvn -q -Dmse=off -Dsonic1.rom.path=<repo>/s1.gen -Dsonic2.rom.path=<repo>/s2.gen -Ds3k.rom.path=<repo>/s3k.gen test`, plus the exact complete three-ROM trace baseline above. Then run the same full suite and trace command plus focused gates in the campaign worktree.
4. Merge the campaign branch directly into main-workspace `develop` without switching the main workspace. Resolve upstream conflicts carefully, rerun that same all-three-ROM full suite and complete trace fleet on merged `develop`, and compare against the recorded baseline so no passing test regresses and no baseline failure worsens due to this branch.
5. Push only `develop`. After successful push, verify the campaign worktree is free of unknown/unmerged changes, discard only classified generated outputs, remove the worktree, delete the fully merged local campaign branch, and prune worktree metadata. Report exact baseline/merged outcomes, conflicts, commits, and push result.

## Task 7E: reconcile updated-develop in-level completion ownership

1. During the merge into updated `develop`, add RED tests showing its no-argument `onInLevelTitleCardCompleted()` can arm a replacement/current batch without proving lease identity. Replace it with `onInLevelTitleCardCompleted(RuntimeArtAdmissionLease)`, whose default consumes that exact lease as `TITLE_OWNER`.
2. In `Sonic3kObjectArtProvider`, validate and consume the exact argument before setting the existing one-runtime-pass physical-submission deferral. Missing, stale, consumed, or wrong-owner leases must fail before teardown/arming mutation. Preserve the rewind-captured next-pass bit.
3. In `Sonic3kTitleCardManager`, rebind once at `EXIT -> COMPLETE`: ordinary in-level owners call the exact delayed hook; pre-level and held-level-counter/carried-results owners consume directly. Remove updated-develop's art-readiness callback block. Set COMPLETE/end flag only after successful lease handling.
4. Add focused tests for ordinary delayed submission, held-results direct consumption, stale/replacement rejection with full provider-state immutability, and rewind before/after the next-pass edge. Run title/provider/lease/rewind tests, both AIZ traces, the seven-route group, exact 142 matrix, then the ordinary full suite and full trace fleet used for post-merge comparison.
5. Re-review the amended design/plan and runtime reconciliation until GREEN before completing the merge commit.

## Task 7F: close post-merge full-suite lifecycle regressions

1. Reproduce the broad-suite title failures in a small ordered selector. Keep
   production's duplicate-lease rejection unchanged. During a headless bonus
   return, preserve the pending mandatory title request instead of creating a
   skipped-presentation owner before `GameLoop` enters the return card. Update
   `TestTitleCardObjectExecution` so its explicit fresh-AIZ title starts from the
   ROM-accurate absent owner. Isolate `TestSonic3kBonusTitleCard` from ROM/service
   state left by an earlier fork, then prove its four broad-suite-only assertions
   pass both alone and after the reproducing predecessor selector.
2. Add a RED rewind-field audit for the two `S3kKosModuleQueue` references in
   `Lbz1RobotnikEventController`. Remove those stored references. Resolve the
   current coordinator's module queue locally when queuing, readiness-testing,
   or claiming, while retaining the handle and captured ordinal as the object's
   exact job identity. Run the field audit/disposition guards and
   `TestLbz1RobotnikKosOwnerRewind`; do not add a baseline exemption.
3. Update `TestTraceReplayInvariantGuard` to locate
   `private boolean replayS3kTrace(`. Preserve the BK2 validation, gameplay-row
   normalization, and strict ring-diagnostic assertions verbatim, then run the
   complete guard class.
4. Re-run `TestMhzMushroomParachuteObjectInstance` alone and in its recorded
   predecessor order. Change production only if current-HEAD evidence produces
   a deterministic lifecycle leak; otherwise classify the non-atomic earlier
   observation as contamination and retain the source-of-truth behavior.
5. Run focused title/provider/lease/rewind gates, the exact 142-test matrix, the
   ordinary three-ROM full suite, and the complete 108-method trace fleet from
   Task 7D. Compare exact test identities against the saved updated-develop
   baseline; no baseline pass may regress. Restore and hash-check the user-owned
   rewind-gap report after every Maven batch.
6. Commit the reviewed Task 7F changes on `develop`, fetch and reconcile any new
   upstream commits without switching the main workspace, repeat verification
   if the tested tree changes, push only `develop`, then perform Task 7D's
   campaign-worktree and local-branch cleanup.

Final Task 7D evidence at `f05ac8eae`: the focused seven-route group is
11 pass / 4 fail / 33 error across 48 methods; the strict matrix is 142/142;
and the complete three-ROM fleet is 67 pass / 4 fail / 37 error across the
expected 64 classes / 108 methods. All 30 S1 and 20 S2 classes are green, four
of fourteen S3K classes are green, and the corrected reports contain none of
the temporary shared f33 groups. Integration, push, and cleanup remain the
root agent's ordered post-publication steps.
