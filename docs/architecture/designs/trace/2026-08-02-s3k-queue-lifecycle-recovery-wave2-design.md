# S3K Queue Lifecycle Recovery Wave 2 Design

Date: 2026-08-02

## Objective

Advance the remaining S3K hardware-work frontiers by correcting the production owners that admit enemy art, publish retained title cards, and retire late special-stage entry rings. The direct and module queues and the recorded timing authority remain unchanged.

Wave 1 established the current baseline on JDK 21:

- the authority/queue regression matrix is 142/142 green;
- all 30 S1 and all 20 S2 trace classes are green;
- S3K Gumball, Pachinko, Slots, and Special Stage are green;
- LBZ advanced past the two missing miniboss-box parent submissions to direct-Kos ordinal 282;
- the remaining actionable queue lanes are AIZ title ownership, CNZ/ICZ transition ordering, CNZ late-ring placement, and MGZ retained-results title publication.

## Constraints

- Timing replay may release only matching production-submitted ROM work. It must not create work, reorder fingerprints, or infer gameplay from trace identity.
- Shared code receives semantic ownership state, never game, zone, route, trace, or frame-number carve-outs.
- A title-card art payload becoming ready is not the same event as the title owner retiring.
- A seamless transition without an immediately displayed overlay is not necessarily title-owner-free. Retained results objects and transition resource owners may still hold enemy-art admission.
- Recorder-attribution changes and canonical fixture replacement remain outside this wave.
- MGZ standard, HCZ, and MHZ remain outside queue implementation until their earlier gameplay divergences move.

## Evidence and ownership map

### AIZ initial title owner

`Sonic3kTitleCardManager.finishQueuedArtIfReady()` currently calls `ObjectArtProvider.onTitleCardArtRetired()` as soon as the four title KosM payloads are claimed. The ROM instead keeps `Obj_TitleCardWait2` alive for its `$2E` 90-frame hold and staggered child exit, reaching `loc_2D8CA -> LoadEnemyArt` only after the child counter drains. This premature callback submits Monkey Dude, Bloominator, and Caterkiller children at the first failing AIZ row.

The title manager already models the hold and child-retirement state and reaches `Sonic3kTitleCardState.COMPLETE`. That owner-complete transition, not art readiness, is the production release boundary. Skipped initial presentation remains owned by `onTitleCardPresentationSkipped()` and `Sonic3kTitleCardTeardownModel`; it must not receive a second release from a non-running overlay.

### Seamless transition admission

`LevelActTransitionExecutor` reloads target-act standalone art, which records pending enemy archives, and currently calls `onTitleCardArtRetired()` whenever `showInLevelTitleCard` is false. The boolean describes immediate overlay presentation only. It does not prove that no retained results/title or transition-resource owner exists.

CNZ and MGZ carry the Act 1 results lifecycle across the reload. The retained results object later mutates into `Obj_TitleCard`, and its next dispatch queues the target title parents. Enemy art must remain pending until that title owner reaches completion. ICZ has a different semantic owner: its pre-submitted terrain/module handoff must publish before target-act enemy archives enter the same FIFOs. LBZ likewise uses a false-overlay reload while a retained results/title owner controls admission. AIZ's false-overlay fire reload is the contrasting presentation case, but its live `Load_Level` continuation re-enters the target act's `LoadEnemyArt` lifecycle and therefore owns a fresh `PLCKosM_AIZ` batch without displaying a title card. The batch is admitted immediately by the transition executor and submits through the normal provider pump.

The request therefore needs an explicit, game-neutral runtime-art admission policy, separate from `showInLevelTitleCard`:

- `IMMEDIATE`: no retained presentation/resource owner exists; the executor consumes the new batch's lease and arms it during transition execution, but the parents submit only at the existing following `processRuntimeArtQueue()` pump. The infrastructure migration must preserve that current service/submission boundary unless a focused ROM trace proves it wrong.
- `PRESERVE_CURRENT`: the ROM reload does not execute `LoadEnemyArt`; the transition refreshes act-local renderer registrations without registering a new enemy batch or issuing a lease. Existing enemy art and any already-owned work remain intact.
- `TITLE_OWNER`: target runtime art remains held for a title owner. `showInLevelTitleCard=true` asks the executor to initialize that owner now; `false` means a retained results SST will be the sole later publisher. In either case, title initialization binds the lease and title completion consumes it.
- `RESOURCE_HANDOFF_OWNER`: target runtime art remains held for a concrete seamless resource handoff. The executor transfers the lease to that handoff, and the target production owner consumes it only after publishing the carried resources.

The exact representation may be an enum or equivalently typed request policy. A boolean whose meaning is inferred from `showInLevelTitleCard` is not sufficient. The default preserves existing non-S3K behavior, but every S3K transition builder must state a policy explicitly and a source guard must reject omitted assignments. `PRESERVE_CURRENT` is materially different from an indefinitely deferred lease: no target enemy batch exists to release.

The policy is backed by a production-issued admission lease, not a global unqualified callback. When `reloadStandaloneArtForActTransition()` registers the target art plan, the provider creates a `RuntimeArtAdmissionLease` (or equivalent typed generation) containing a monotonically increasing provider generation and a stable fingerprint of the registered target batch. The provider retains the descriptors and the authoritative lease state. The declared owner receives or binds that exact lease id and consumes it once. Missing, stale, already-consumed, wrong-generation, wrong-batch, and wrong-owner release attempts fail closed and cannot arm whatever batch happens to be current.

For a displayed or retained title, title initialization binds the provider's one pending `TITLE_OWNER` lease and stores the scalar lease id in the title manager's rewind snapshot; cached title art still performs this bind even though no `finishQueuedArtIfReady()` edge occurs. For skipped initial presentation, `Sonic3kTitleCardTeardownModel` binds an existing production-issued lease, owns that scalar lease id, retires its last child after the 34-tick production lifetime, and consumes the lease only when the lower-slot owner observes that retirement on its following dispatch. Missing lease state fails closed and neither title path may fabricate a lease or release “the current batch.”

Production registers the title rewind adapter before the provider adapter, and the registry restores in registration order. Title restore therefore copies only its scalar lease id and never calls the still-live provider. Exact-id rebind and validation occur lazily at the next lease-dependent owner action, after the composite registry restore has restored provider state. A production-registry round trip across lease replacement and consumption proves this order independence; validation remains exact and fail closed rather than being weakened.

For a resource handoff, the executor transfers the lease alongside the ROM-backed handles, and the target event owner captures/restores it. Registered handoff holders are immutable: attaching a lease creates a claimed-path replacement (for example `withAdmissionLease(...)`) rather than mutating the object retained by `SeamlessTransitionResourceHandoffRegistry.Snapshot`'s shallow map copy. A pre-claim registry snapshot therefore cannot observe a future lease after restore. Until that concrete transfer path lands atomically in Task 5, `RESOURCE_HANDOFF_OWNER` is a typed but unsupported policy. `LevelActTransitionExecutor` rejects it at method entry, before handoff claim, game-state reset, zone/act mutation, level loading, renderer refresh, batch registration, generation advance, lease issue, or admission. `IMMEDIATE` consumption remains an executor arm transaction.

`ObjectArtProvider` exposes typed prepare/bind/consume operations rather than using the art-retirement name as a catch-all. The S3K provider keeps pending enemy descriptors while admission is held and submits them once after successful lease consumption. Rewind snapshots capture the pending descriptors, issued generation, active lease, owner kind, consumption state, submitted handles, and skipped-presentation counter.

### Retained results to title publication

`S3kResultsScreenObjectInstance.onExitReady()` distinguishes an Act 1 seamless transition from a carried results object. Production-path Task 3 evidence confirms its existing `carriedAcrossSeamlessTransition` state already changes the owner role correctly: the reload has happened, so the next results dispatch publishes the Act 2 title once even though the zone normally uses a seamless transition. No results-object behavior change is justified unless a later route produces new RED evidence.

The fix is state-owned: the pre-reload results instance requests/permits the transition, while the carried instance mutates into and publishes the title on its next dispatch. CNZ's existing persistent-object handoff preserves that exact results owner instead of replacing its lifetime with only a delayed-control bridge. MGZ must stop asking `LevelActTransitionExecutor` to initialize a second title overlay. Both requests use `TITLE_OWNER` with `showInLevelTitleCard=false`; the carried results object is the sole publisher, binds the pending lease during title initialization, and the resulting title manager is the sole releaser at completion. No zone-specific exception is added to the shared results logic.

MGZ's request also owns ROM-derived title timing (`resetAtDisplay`, reset additional/phase-one overlap, player-control lock, and exit additional/phase-one overlap). Removing the executor overlay must not delete those values. Add an immutable, game-neutral carried-title publication value to the transition/object carry contract. `LevelManager` derives it from the exact `SeamlessLevelTransitionRequest` and passes it with the offset to persistent objects; the default object hook delegates to the existing offset-only behavior, while the carried results owner stores the scalar fields and an explicit-timing predicate in its rewind/recreate state. The hook is semantic and runs even when geometric offsets are zero. It never looks up a current request, zone, or route.

Timing precedence is single-owner and explicit. When the carried value contains explicit timing, the results owner applies exactly those fields through the existing title timing APIs and suppresses its generic retained-results reset fallback; MGZ therefore retains reset-at-display `12/6`, exit `10/5`, and its existing reset/lock booleans. When the carried value is absent/default, the results owner applies no request timing and preserves its existing retained-results reset-after-create fallback of `38/40` dispatches; LBZ and CNZ remain on that fallback. Executor and carried owner cannot both consume timing. Tests assert the actual reset/countdown and exit frame ownership, not merely transported scalars.

MGZ also supplies new production RED evidence for the generic carried-state branch: after reload, the historical HCZ/MGZ `hasSeamlessTransition` predicate still suppresses publication and deletes the carried owner. `retainedReloadState` must take semantic precedence, so a results owner that has already been carried treats the reload as complete and publishes on its next dispatch. This is a zone-neutral correction to precedence over the existing pre-reload route predicate; no new zone condition is added.

An in-place reload replaces only the concrete `ObjectManager` and `RingManager` among the rewind owners involved here. The active `GameplayModeContext` rewind registry must therefore expose a narrow transition-manager rebind operation that deregisters/re-registers exactly `object-manager` and `rings` against the target managers after target level-event initialization and before resource-handoff transfer. It must not call the broad level-adapter registration path, move the title/provider/event/solid/tilemap owners, or alter their restore-order contract. Otherwise captures taken after recreation still serialize discarded source managers. This is a generic transition-lifecycle correction, not CNZ-specific.

Full registry restoration across the act identity change is intentionally unsupported: zone-runtime snapshots validate `s3k:zone/act` identity, and the existing `LevelIterationAdmissionController` completes every seamless transition with `RewindBoundary.SEAMLESS_LEVEL_TRANSITION`, which retains only the current frame and reroots live and trace rewind history. Tests therefore restore the pre-reload checkpoint only before the boundary, then prove the boundary makes that frame inaccessible. The new root and all post-recreation, pre-title-publication, and post-completion snapshots use the rebound target managers. A lower-level registry-layout test may restore a pre-rebind object/ring-only snapshot into replacements to prove adapter routing, but it must not claim that a full cross-act zone snapshot is supported.

Tests construct the carried results owner through the production transition/recreation path and drive it through the rebuilt `ObjectManager`/headless logical-frame dispatcher. They verify child retirement without title submission on one dispatch, the exact four title parents on the following dispatch, and the exact target enemy parents only after title completion, with neither a second overlay initialization nor a second title batch. They also verify that the session-registered stale pre-reload title lease cannot bind or consume the new lease and that rejection leaves the full provider and hardware-job state unchanged.

### ICZ resource publication

ICZ queues the Act 2 secondary 128x128, 16x16, and 8x8 resources before `Load_Level` and transfers their exact handles through `IczSeamlessTransitionResourceHandoff`. `Sonic3kICZEvents.publishTransferredIcz2Resources()` is the production owner that claims and publishes them. Its `RESOURCE_HANDOFF_OWNER` transition transfers the exact admission lease through the same handoff. Target-act enemy descriptors are real engine production state registered during the reload, but remain unsubmitted until the transferred resources have all become ready, been claimed, and been applied successfully.

Lease consumption is the final step of that publication transaction. Module-ready-before-direct and direct-ready-before-module both stay held. A transfer, claim, terrain apply, or art apply failure leaves the lease unconsumed and cannot admit enemies. Duplicate or stale handoff acceptance and duplicate lease consumption fail closed. Rewind coverage spans a registry snapshot before claim/lease attachment, immediately before target handoff acceptance, while either queue is still incomplete, and after publication. The pre-claim restore must recover an immutable lease-free handoff even after the claimed-path replacement later attached or consumed a lease; every other restore preserves the exact lease generation and neither loses nor duplicates the target enemy batch. This is an engine ownership model for ordering registered work, not an assertion that the ROM transition contains another `LoadEnemyArt` call.

## S3K transition policy assignment

Every existing S3K `SeamlessLevelTransitionRequest` builder is assigned explicitly:

| Builder | Overlay now | Policy | Sole owner and release point |
|---|---:|---|---|
| AIZ fire reload | no | `IMMEDIATE` | live `Load_Level` continuation admits the fresh `PLCKosM_AIZ` batch through the executor/provider boundary; no title owner is created |
| CNZ Act 1 reload | no | `TITLE_OWNER` | production-preserved/recreated results owner publishes on next dispatch; title COMPLETE consumes lease |
| ICZ Act 1 reload | no | `RESOURCE_HANDOFF_OWNER` | `IczSeamlessTransitionResourceHandoff` transfers lease; `publishTransferredIcz2Resources()` consumes after successful apply |
| LBZ Act 1 reload | no | `TITLE_OWNER` | carried results owner publishes later Act 2 title; title COMPLETE consumes lease |
| MGZ Act 1 reload | change to no | `TITLE_OWNER` | carried results owner is sole publisher; title COMPLETE consumes lease |
| HCZ Act 1 reload | yes | `TITLE_OWNER` | executor initializes one title owner; title COMPLETE consumes lease |
| MHZ Act 1 reload | yes | `TITLE_OWNER` | executor initializes one title owner; title COMPLETE consumes lease |

No `TITLE_OWNER`/false-overlay request calls `onTitleCardPresentationSkipped()`: that method models the omitted standard initial-title owner, not a retained seamless-results lifetime. `PRESERVE_CURRENT` does not create, clear, replace, bind, or consume a lease. A source guard enumerates S3K request builders and fails if any relies on the default policy.

The table is the final state, not an authorization to issue an unowned lease between commits. Infrastructure lands first with explicit, behavior-preserving intermediate assignments: CNZ, ICZ, and LBZ remain `IMMEDIATE`; MGZ remains `TITLE_OWNER` with its existing displayed overlay; HCZ/MHZ remain displayed `TITLE_OWNER`; AIZ uses the independently proved `IMMEDIATE` live-reload owner. An interim regression proves that `IMMEDIATE` only arms inside executor execution and submits at the same subsequent provider pump as the pre-lease code. CNZ, MGZ/LBZ, and ICZ each switch to the final policy in the same atomic commit that installs and tests the corresponding results/title or resource-handoff owner. The source guard requires explicit assignment throughout this migration.

### CNZ late entry ring

The CNZ complete-run trace expects the first direct child of parent `ArtKosM_BadnikExplosion` (`0xDB406`, child `0xDB408`) when the later `SSEntryRing_Display` retires. `Sonic3kSSEntryRingObjectInstance.retireRing()` already submits that parent. The missing work is therefore upstream: the placement controller must construct and retain the later ring at fixture slot 10, `(0x2DC0,0x064C)`, subtype 4, until its ROM retirement path.

Instrumentation and focused tests must first prove whether the sorted placement cursor reaches the spawn and whether remembered/window state drops it. The repair belongs to `ObjectPlacementController` or its captured cursor state, not to the ring tail and not to a trace-specific spawn. A synthetic ordered-spawn regression must demonstrate that later eligible entries are not skipped when earlier remembered or unloaded entries change the active window. The CNZ route test then proves the real ring reaches `retireRing()` and submits exactly one parent.

### Fleet-discovered teardown cadence correction

The first full Wave 2 fleet exposes a real frame-33 regression in eight S3K traces. Commit `633e06cec` correctly moved enemy-art admission to the title owner, but it also began consuming a completed skipped-title teardown in the same provider pump that `tick()` retires its last child. `processRuntimeArtQueue()` then submits the first enemy KosM parent in that pump, one physical engine row before the trace.

The source-of-truth cause is SST order, not queue readiness. The lower-slot title owner tests its child counter and returns before higher-slot children decrement that counter. When the last child retires on provider tick 34 (zero-based trace frame 33), the owner cannot observe zero until provider tick 35 (trace frame 34). `Sonic3kTitleCardTeardownModel` must model those phases: tick 34 changes only child state; tick 35 reaches `LoadEnemyArt` and consumes the exact existing lease. This keeps Task 1's sole-owner/exactly-once contract while restoring ROM cadence. Rewind captures the distinct tick-34 state where all children are drained but the owner has not observed completion. Tests use a real scheduled batch and prove no physical enemy job through trace frame 33 and exact first-parent submission at frame 34. No provider-only delay, fixture regeneration, hardware-authority change, or zone/frame/trace branch is permitted.

### Delivery guard remediation

The campaign must not worsen repository guard debt. The original integration baseline already exceeds the `LevelManager` 2,500-effective-line ratchet at 2,537 lines, and Task 4's exact request-timing copy raises it to 2,548. Raising the guard or reverting timing fields would be dishonest. Extract the complete seamless-transition orchestration from `LevelManager.applySeamlessTransition(...)` into a focused package collaborator, preserving the switch, `RELOAD_SAME_LEVEL` request normalization (including admission/resource and all carried-title timing fields), exception/finally behavior, and reload-frame bridge. `LevelManager` retains a small facade and ends below the existing ratchet.

The separate agent-guidance phrase failure predates the campaign: `AGENTS.md` and `CLAUDE.md` are byte-identical, but the guard searches raw Markdown for a lowercase phrase that is now capitalized and line-wrapped. Normalize whitespace only in the test and require the exact current capitalized semantic sentence. Do not weaken the required phrase and do not churn mirrored policy documents.

### Updated-develop in-level completion reconciliation

The integration baseline adds an in-level title completion cadence hook after this campaign's original base. Its no-argument form may arm whichever provider batch is current, which is incompatible with lease identity and fail-closed stale-owner rejection. Reconcile the hook as an exact-lease operation: `ObjectArtProvider.onInLevelTitleCardCompleted(RuntimeArtAdmissionLease)` defaults to consuming that lease as `TITLE_OWNER`; the S3K provider override validates/consumes that exact lease before applying the existing one-runtime-pass physical-submission deferral.

`Sonic3kTitleCardManager` rebinds its stored scalar lease exactly once at `EXIT -> COMPLETE`. Ordinary in-level cards pass that lease to the cadence hook; pre-level and held-level-counter/carried-results owners consume it directly. Title KosM readiness never calls either path. State reaches `COMPLETE` and publishes the end flag only after the exact lease action succeeds. The existing provider rewind bit for next-pass arming preserves the deferred physical-submission edge; stale, missing, or wrong-owner leases fail before arming and leave the current batch unchanged. This merge reconciliation preserves updated-develop AIZ timing without reopening a current-batch callback.

### Final measured outcome

The post-correction fleet at `f05ac8eae` confirms that the eight common f33
groups are gone and every affected class has returned to its prior gameplay or
later queue lane. The exact authority/queue matrix is 142/142 green. The full
three-ROM inventory is 64 classes / 108 methods: 67 pass, 4 fail, 37 error,
with S1 30/30 classes green, S2 20/20 green, and S3K 4/14 green. CNZ standard
ends at raw 17421/direct `#24`; CNZ complete consumes `#203/#204` and ends at
raw 13962/direct `#205`. AIZ's raw 5543/6346 outcomes remain isolated to the
separate recorder/service-row attribution lane, and the MGZ-standard, HCZ, and
MHZ gameplay-first lanes remain open.

### Post-merge full-suite lifecycle closure

The updated-develop full suite exposes three integration-only lifecycle gaps
that focused queue tests do not cover. First, a headless bonus-stage return load
falls through the ordinary omitted-presentation path, creating a skipped-title
lease owner before `GameLoop` enters the mandatory return title card. The return
flag is active only around that reload and identifies the real title-card owner,
so `requestTitleCardIfNeeded()` must retain the pending request instead of
starting an omitted owner. Production must continue to reject a second bind.
The explicit locked-title fixture must likewise leave its fresh-AIZ owner absent
until the title card under test starts. Bonus-title state-machine tests must
isolate the ROM/service state left by an earlier fork so unserviced KosM work
cannot change their parent-observation tick.

Second, `Lbz1RobotnikEventController` stores `S3kKosModuleQueue` service
references beside the scalar ordinals that identify its two outstanding jobs.
The queues are runtime-owned services already captured by their registered
rewind adapter, so retaining them in the object creates a second, uncaptured
ownership edge. The object must retain only the hardware handles/ordinals and
resolve the current coordinator queue at each service operation. After rewind,
the ordinal remains the authority used to recover a pending handle; no queue
reference is serialized, baselined, or marked as an exception.

Third, `TestTraceReplayInvariantGuard` still locates the S3K replay method by its
former `void` declaration even though Task 7E made terminal-scope closure an
explicit boolean result. The guard must follow the exact boolean signature while
preserving its existing BK2-alignment and row-strict ring assertions.

These are delivery-gate corrections, not new trace behavior. They must leave the
142-test matrix and the measured 108-method frontier unchanged, remove every new
ordinary-suite regression against the updated-develop baseline, and keep the
user-owned rewind-gap report byte-identical.

## Delivery sequence

Shared owners are serialized:

1. Move S3K title enemy-art admission from payload readiness to title-owner completion. Cover queued and cached title art, exactly-once completion, the existing 34-tick skipped-initial-title owner, and a false-overlay transition that never starts the skipped-title model.
2. Add lease-backed admission-policy infrastructure, snapshot coverage, explicit behavior-preserving intermediate assignments for every S3K builder, and the no-default source guard. Verify stale, duplicate, missing, and wrong-owner lease failures, plus `PRESERVE_CURRENT` no-batch behavior, before migrating a route. Characterize the AIZ fire reload against its ROM path and prove it neither resubmits nor clears the existing AIZ enemy set.
3. Preserve/recreate the CNZ carried results production owner, atomically change CNZ to final `TITLE_OWNER`, remove any competing title publisher, and verify CNZ standard.
4. Make the MGZ and LBZ carried results owners the sole title publishers, atomically change their requests to final `TITLE_OWNER`/false-overlay behavior, remove the MGZ executor-owned duplicate, and verify both complete traces.
5. Atomically change ICZ to `RESOURCE_HANDOFF_OWNER`, transfer and transactionally consume its handoff lease after successful resource publication, including ordering/failure/rewind tests, then verify ICZ complete.
6. Diagnose and repair the CNZ late-ring placement cursor with generic two-axis window, remembered/dormant entry, post-camera extension, and snapshot/restore ordering tests, then verify CNZ complete.
7. Rerun AIZ, CNZ, ICZ, MGZ, and LBZ plus the 142-test authority/queue matrix and full three-ROM fleet after each shared-owner change.
8. Correct the fleet-discovered skipped-title SST cadence, verify all eight affected traces plus AIZ and the 142-test matrix, and record every restored first-error frontier.
9. Extract seamless-transition orchestration so `LevelManager` falls below the existing 2,500-line ratchet, normalize only Markdown whitespace in the pre-existing prose guard, and rerun focused transition/guard tests plus the ordinary full suite.
10. Publish the post-correction 64-class fleet and perform baseline comparison, integration, push, and cleanup.
11. During updated-develop reconciliation, replace its no-argument in-level completion callback with an exact-lease hook, cover stale/missing/rewind and ordinary-versus-held owner timing, then rerun the affected AIZ/title/queue gates before post-merge comparison.
12. Close full-suite-only lifecycle gaps: make title fixtures own exactly one lease, remove LBZ's secondary queue-reference ownership, and update the invariant guard to the boolean replay signature without weakening its checks.

The AIZ complete terminal module event at raw 6351 remains a separate native-recorder observation/service-row attribution audit. It must not receive a production workaround. MGZ standard, HCZ, and MHZ retain their earlier gameplay owners.

## Acceptance criteria

- AIZ enemy parents are not submitted when queued or cached title payloads merely become ready; they submit exactly once when the lease-owning title completes or the lease-owning skipped-presentation model completes.
- Seamless transition requests explicitly declare immediate, preserve-current, title-owner, or resource-handoff-owner admission; every S3K builder is guarded against an implicit default, and executor behavior no longer infers ownership from overlay visibility.
- Production-issued leases are batch/generation/owner bound, rewind-captured, exactly-once, and fail closed for stale, duplicate, missing, or wrong-owner consumption.
- Carried CNZ/MGZ results owners publish the Act 2 title on the correct following dispatch exactly once, with no competing executor title or duplicate four-parent batch.
- ICZ target-act enemies remain pending until the transferred transition resources publish successfully, then submit once in ROM order; every failed or partially ready transaction leaves the lease held.
- The later CNZ SS entry ring is created through generic placement-window logic and its existing retirement tail submits `ArtKosM_BadnikExplosion` exactly once.
- No change touches the timing authority, queue admission matching, or trace fixtures.
- Skipped-title teardown observes last-child retirement on the owner's following SST dispatch, so affected enemy batches first submit at trace frame 34 without changing lease identity or ownership.
- `LevelManager` is below its existing 2,500-effective-line guard with seamless-transition behavior delegated intact, and the agent-guidance guard accepts Markdown wrapping while still requiring the exact policy sentence.
- Updated-develop's one-pass in-level completion cadence accepts and consumes only the title manager's exact rebound lease; it cannot arm a current or replacement batch implicitly.
- Full-suite title fixtures cannot double-bind admission; LBZ retains no runtime queue service references; and the replay invariant guard locates the boolean replay method while enforcing the same invariants.
- The authority/queue matrix remains green, S1 stays 30/30, S2 stays 20/20, and every moved S3K frontier is recorded line-by-line.

## Risks and mitigations

- Moving the title callback can starve headless startup if skipped presentation is conflated with displayed title. Keep the existing skipped-presentation teardown as a separately tested lease owner.
- A deferred request without a release owner can deadlock enemy art. Require an explicit `TITLE_OWNER` or `RESOURCE_HANDOFF_OWNER` assignment, an exact transferred/bound lease, focused completion tests for every builder, and fail-closed missing-owner transfer.
- A preserve-current transition can accidentally erase or duplicate the live enemy set if implemented as an empty deferred batch. Test that it issues no generation, retains existing submitted/ready art, and changes only act-local renderer registrations.
- CNZ and MGZ both consume the carried-results path. Test both zones through semantic carried state and avoid zone-name branching in the shared results object.
- ICZ publication currently claims before applying. Keep admission consumption last and test each failure boundary so a partial apply cannot release enemy work.
- Placement cursor fixes can affect every game. Use generic two-axis window/remembered/dormant/post-camera/rewind tests and rerun S1/S2 trace fleets before accepting the CNZ route movement.
