# S3K Trace Branch LLM Review Remediation

## Requirements

### Goals

- Validate every distinct finding from the two supplied reviews against the current branch.
- Fix confirmed branch-introduced correctness, contract, validation, documentation, and hygiene defects.
- Preserve the v5 comparison-only hardware-timing contract: recorded timing may affect only when matching production-submitted work becomes ready.
- Preserve ROM-backed S3K queue semantics, rewind ownership, and cross-game behavior.
- Produce focused tests for corrected boundaries and failure modes.

### Non-goals

- Advancing the already-documented AIZ, CNZ, or LBZ trace frontier beyond regressions caused by this branch.
- Replacing trace fixtures or publishing recorder output.
- Merging into `develop`, pushing, or opening a pull request without explicit human approval.
- Deleting untracked user files or worktrees.

### Constraints and assumptions

- JDK 21 is required for all trustworthy Maven validation. The current shell defaults to JDK 26 and must not be used for test conclusions.
- `origin/develop` is locally eight commits ahead of this branch; integration is a later, explicitly reviewed step.
- Existing `.idea/vcs.xml` and `docs/status/rewind-round-trip-gaps.md` edits plus `ghosts/` and `identity/` are user-owned and must not be overwritten.
- Review findings that merely request broader polish are accepted only when they identify a concrete correctness, policy, or maintainability defect in the branch.

### Acceptance criteria

- The two branch-introduced `TestS3kCnzAct1EventFlow` failures pass while explicitly proving zero submissions before first dispatch and three afterward.
- Fresh-level runtime-art work is production-created for equivalent live and recording loads, with headless title-card suppression represented independently.
- A deferred two-parent submission is failure-atomic and rewind-tested.
- Trace evidence does not choose whether a production hardware service boundary executes;
  a negative test proves that a recorded POST edge cannot make an otherwise-unreached
  production boundary run.
- The canonical frame sequence services exactly one POST followed by one PRE; a sequence
  test proves that a late post-PRE producer performs only its ROM-owned module state step
  and does not manufacture a second timing boundary.
- Semantic-prefix teardown verifies the exact expected pending hardware work.
- Broad exception swallowing in LBZ boss-art ownership is replaced with fail-closed contextual handling.
- Confirmed event/lifecycle/rewind gaps and small correctness defects have focused tests.
- Changelog and README descriptions accurately scope shared changes and frontier metrics; repository policy issues are either fixed locally or called out for human branch/integration action.

### Risks

- Queue-order corrections can move known-red trace frontiers; those changes require exact frontier logging, not tolerance.
- Shared `LevelFrameStep` or movement changes have S1/S2 blast radius.
- Merging `origin/develop` before the local fixes are reviewed can obscure causality on colliding lifecycle files.

## Exploration Synthesis

Both reviews independently reproduce the same CNZ regression and identify the same stale helper boundary. Local inspection confirms the helper asserts three module parents immediately after allocation even though the production object now defers submission to its first dispatch.

Both reviews also identify the same hardware-timing contract concern: `TraceSuppressedRowClosure` consults `TraceHardwareTimingBoundaryObserver.hasPendingCompletionAtCurrentRawFrame(...)` to decide whether to invoke `POST_OBJECTS`. That makes trace evidence select production control flow and is incompatible with the repository's delay-only exception.

Local inspection confirms `S3kRuntimeArtCoordinator.afterTimingService` clears its deferred request before queueing, queues and claims the primary before validating secondary capacity, and catches only `IOException`. The reviewers' partial-mutation scenario is therefore credible and requires an atomic-batch design.

Local inspection also confirms the canonical normal frame path services POST, then PRE,
then invokes `processRuntimeArtQueueAfterPreMainLoop`. Disassembly review established that
the late producer is followed by `Process_Kos_Module_Queue`, but not by another hardware
service edge: the correct implementation advances the newly published parent state while
leaving its direct child unprepared until the next canonical service.

The locally available refs confirm the branch is 29 commits ahead and 8 commits behind `origin/develop`. Integration and branch renaming are policy actions requiring human-visible handoff; they are not silently performed during remediation.

## Architecture Decision

- Production service-boundary ownership remains in `LevelFrameStep` and runtime coordinators. Replay timing ports may approve readiness at a boundary but may not select which boundary executes.
- Fresh-load cause and title-card presentation are separate semantic inputs. Live and recording drivers use the same production load cause; standalone bootstrap suppression remains a harness/presentation concern.
- Fresh-level parent descriptors are resolved and validated as one batch. The deferred request remains owned until complete admission succeeds; queue and timing-ledger mutation must be all-or-nothing.
- Canonical hardware order is one POST followed by one PRE per logical frame. Late producers remain deferred to the next canonical POST unless ROM evidence establishes a separate production iteration owner.
- Prefix traces use an explicit manifest/fixture semantic and exact pending-work verification, not unconditional abort cleanup.
- Event behavior is keyed on semantic lifecycle state and real production slots, never on the presence of a level fixture or an alternate test-only call path.

## Finding Assessment

### Confirmed defects fixed

- **CNZ results lifecycle (Review 1.1 / Review 2 B1):** confirmed. The stale test
  helper now proves zero parents at allocation, advances the first production dispatch,
  and then proves the exact three-parent submission.
- **Fresh-load cause coupling (Review 1.2 / Review 2 A2):** confirmed. Fresh runtime-art
  ownership and headless title rendering are now separate inputs. Live loaders use the
  explicit fresh-load API; rendered and omitted-presentation title owners publish at the
  same native completion semantics. Standalone trace bootstrap and inherited-state restore
  remain deliberately non-fresh because their fixtures begin after, or restore across, that
  production boundary.
- **Recorded boundary selection (Review 1.3 / Review 2 A1):** confirmed hard-rule
  violation. Recorded-edge look-ahead was removed. The production coordinator determines
  held-row ownership, and incompatible recorded POST edges fail schedule compilation.
- **Two-parent partial queue mutation (Review 1.4):** confirmed for capacity and archive
  validation failures. The whole batch is capacity-checked and decoded before either parent
  reaches the ledger, the deferred request remains pending under pressure, and rewind
  restores the same request/handles. This is operational failure atomicity for the reported
  paths, not a claim that `HardwareTimingService` is a general transactional ledger API.
- **AIZ prefix teardown (Review 1.5):** confirmed. The unconditional abort escape hatch was
  replaced with an exact fixture contract for pending kind, ordinal, ROM source,
  fingerprint, order, and count before detachment.
- **Second POST after PRE (Review 1.6):** confirmed. The late AIZ path now performs only the
  native module-parent state step. It does not service timing, notify the observer, or
  prepare the direct child in the same Java frame.
- **LBZ broad exception suppression (Review 1.7 and Review 2 code specifics):** confirmed.
  Explicit missing fixture services remain tolerated; ROM, queue, ledger, and programming
  failures now fail closed with owner context.
- **Environment-keyed production branches (Review 2 A3):** confirmed. The CNZ direct-caller
  fallback and AIZ `currentLevel == null` fixture branch were removed; tests drive the real
  lifecycle slots. The double CNZ state-machine step is retained because the two calls model
  distinct ROM slots on the completion row.
- **Rewind ownership gaps:** confirmed for LBZ1 Robotnik, the LBZ final boss, the LBZ end
  boss, and the fresh runtime-art coordinator. Pending, ready, and missing-owner restoration
  cases now have focused coverage. Review also exposed and remediation fixed a superclass-
  construction ordinal clobber; the absent-owner sentinel is explicitly established for
  object-only construction.
- **Small code defects:** the unused Death Egg flag was removed, restored-ordinal failures
  gained messages, AIZ battleship paired-handle/queue invariants fail closed, the redundant
  title readiness local was removed, the Clamer seam now executes production initialization,
  and the CNZ `WAIT` write now cites `Restore_PlayerControl2`.

### Confirmed policy and documentation work

- The missing shared `move_lock` and Bubbler/Air Countdown changelog entries were added.
- The oscillator entry now says the change is shared, frontier entries distinguish
  comparator errors from hardware-authority boundaries, and fresh terrain wording is
  destination-generic. The merge-repair-only bullet was folded into the owning change.
- `README.md` now contains the required prerelease summary. It must be staged with the
  eventual merge/PR change; no merge is performed in this remediation.
- `LevelEventProvider.java` was normalized from mixed CRLF/LF to LF without semantic edits.
- Root-only ignore rules cover `identity/` and `ghosts/`. Their private/generated contents
  were neither inspected nor removed.

### Correct concerns that remain human or architectural follow-up

- The current `bugfix/s3k-traces` name violates the `bugfix/ai-*` convention, and the branch
  is eight commits behind local `origin/develop`. Renaming and merging are explicit human
  integration actions. The colliding complete-run/level-loop files require LBZ replay
  validation after that merge.
- Fresh terrain payloads are still claimed after the synchronous level decoder has populated
  live patterns. Eliminating the duplicate decompression requires a destination-aware pattern
  mutation/GPU invalidation design and removal of the synchronous owner; it is valid debt,
  but not safe to improvise in this review repair.
- The branch's documented AIZ/LBZ comparator frontiers and CNZ hardware-authority stop remain
  known-red parity work. This remediation does not relabel them as regressions or tune around
  them.
- Shared oscillator and hurt-recovery changes would still benefit from complete-run S1/S2
  transition/hurt traces. Focused GHZ1/EHZ1 replay smoke lanes and shared boundary/lifecycle
  tests are green, but do not substitute for a full cross-game sweep.

### Findings not accepted as defects

- The results timing overloads represent distinct retained-owner dispatch inputs; collapsing
  them into one arithmetic record is optional refactoring without a demonstrated parity bug.
- The two LBZ `$2` values belong to different owners: the event mutates camera state and the
  boss projects that state in an earlier object slot. Sharing one constant would incorrectly
  imply one owner.
- The reviewed countdown/dispatch constant changes have nearby ROM-slot or trace-boundary
  explanations, and neither review supplied contradictory disassembly evidence. Their density
  justifies future simplification, but is not evidence that the values were tuned incorrectly.
- `willSetInLevelEndOfLevelFlagThisUpdate()` is intentionally retained as an exact-boundary
  diagnostic used by focused title-card tests; being test-visible is not by itself dead code.
- Hurt forced-animation release is a shared playable-sprite lifecycle invariant and already
  has generic recovery plus S3K publication coverage; no game-name rule is warranted.
- Solid-push release publication is already routed through typed `GameRules` behavior and has
  focused per-game exclusions. No shared-code carve-out was found.
- The generated rewind report, IDE metadata, and temporary worktrees are user/environment
  state. They were not reverted, committed, deleted, or pruned without authorization.

## Feature Design

- Add focused boundary tests that observe pre-dispatch/post-dispatch CNZ results ownership.
- Add live/recording load-equivalence tests and explicit load-cause naming.
- Add capacity-one and rewind round-trip tests for a deferred two-parent request.
- Add a structural service-order test covering POST then PRE with late art publication deferred.
- Add exact pending-job assertions for the AIZ semantic prefix.
- Add rewind tests for every newly introduced handle/ordinal/rebind path claimed by changelog text.
- Keep all runtime asset bytes ROM-backed and preserve submission fingerprints and ordinals.

## Implementation Plan

### Luna Contract

Owns hardware timing and fresh-level runtime-art contracts: review findings 1.2-1.6, 2.A1-2.A2, and the AIZ prefix validation gap. Primary files are `LevelFrameStep`, `LevelManager`, `RecordingFrameDriver`, trace timing/replay classes, S3K runtime-art coordinator/queues/providers, and their focused tests.

Verification: focused hardware-timing guard/compiler tests (including the negative
trace-control-flow case), queue structural/rewind tests, explicit POST/PRE service-order
coverage, live/headless load tests, S3K keep-green level-load tests, and focused S1/S2
regression coverage for any shared `LevelFrameStep` behavior under JDK 21.

### Luna Lifecycle

Owns CNZ regression, environment-keyed zone-event behavior, rewind ownership gaps, LBZ boss-art failure handling, and code-level runtime defects. Primary files are CNZ lifecycle tests, S3K event owners, LBZ object/boss owners, results/title-card lifecycle code, and focused rewind tests.

Verification: `TestS3kCnzAct1EventFlow`, affected event/object tests, rewind guards, and S3K keep-green tests under JDK 21.

### Luna Policy

Owns changelog/README corrections, metric wording, line-ending normalization, narrow
root ignore rules, and a validation inventory. It must not edit runtime behavior, remove
user-owned files, prune worktrees, rename the current branch, merge `origin/develop`, or
edit the user-modified generated rewind report.

Verification: `git diff --check`, policy guards that do not require runtime ROM work, and documentation consistency review.

## Integration Report

Three Luna-named workstreams implemented the hardware contract, lifecycle/rewind, and policy
repairs. A fourth Luna reviewer then inspected the integrated diff independently. That review
found two blockers missed by the initial focused work:

1. Fresh-load arming still occurred only on the rendered-title branch. Arming now precedes
   the render decision, while omitted presentation publishes only when its modeled native
   title-owner teardown reaches `LoadEnemyArt`.
2. A cached same-zone title had no `artLoading` completion edge and could strand the armed
   handoff. The cached ready path now publishes once; a production-backed test proves the
   terrain parent count remains two after a second update.

The orchestrator also found that removing the LBZ end-boss ordinal field initializer fixed
superclass-time submission but left object-only construction at Java's default ordinal zero.
The construction callback now establishes `-1` before any early return, with a focused test.

No trace fixtures changed. No branch merge, rename, commit, push, worktree removal, or user-file
cleanup was performed.

## End-to-End Review

The independent reviewer reports no remaining code blocker. Final validation used JDK 21:

- Integrated high-risk selection: `375` tests, `0` failures, `0` errors, `0` skipped.
- S1 GHZ1 and S2 EHZ1 trace smoke plus shared hurt/frame-boundary tests: `13` tests,
  `0` failures, `0` errors, `0` skipped.
- Focused fresh-art/title-card follow-up: `49` tests, `0` failures/errors.
- `git diff --check`: clean.

The AIZ replay still reaches its documented known-red comparator baseline of `194` errors,
first at raw frame `16067` on `queue.s3k_kos_direct.busy`; the exact terminal hardware
assertion succeeds before the comparator reports that existing frontier. The branch remains
eight commits behind `origin/develop`, so merge readiness requires an explicitly reviewed
integration followed by the LBZ complete-run replay. The current branch name also still
requires human-visible correction to `bugfix/ai-*`.

The audit artifact itself must be staged before handoff, independently of the preserved
user-owned working-tree changes.
