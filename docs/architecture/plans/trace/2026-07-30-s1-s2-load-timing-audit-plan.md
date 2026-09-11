# S1/S2 PLC and Player-DPLC Load-Timing Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` to implement this plan task by
> task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make PLC and player-DPLC timing evidence mandatory in newly captured
S1/S2 traces, compare it without trace-driven state, regenerate the fleet, and
report every replay frontier.

**Architecture:** Existing per-frame `load_queue_state` becomes default-on for
S1/S2 level rows. A new native DPLC observer publishes one typed heartbeat
envelope per stored row and manifest-only gap transitions; production
game-owned diagnostics expose independently derived lifecycle envelopes to the
ordinary trace comparator.

**Tech Stack:** C#/.NET Framework 4.8 under Mono, BizHawk 2.11 GPGX, Java 21,
Jackson, JUnit 5, Maven.

## Global constraints

- Runtime asset bytes come only from the user-supplied ROM.
- Trace data is comparison-only and never hydrates gameplay, art, PLC, DMA, or
  renderer state.
- S1/S2 PLC/DPLC work does not become `HardwareTimingService` authority.
- Canonical S1/S2 level capture has no positive audit opt-in.
- Standalone independently replayed segments arm with an empty submitted-
  transfer DPLC ledger. Continuous named runs may carry an unpublished S1
  staging preparation (no ID/serialized descriptor until VBlank promotion) or
  exact already-accepted S2 DMA FIFO descriptors (stable IDs and immutable
  initial-ledger continuity, never trace-seeded production state).
- Preserve all unrelated worktree and main-workspace changes.
- Capture into scratch and obtain explicit approval of frozen exact bytes
  before installing canonical fixtures.

---

### Task 1: Freeze ROM DPLC invariants

**Files:**
- Create: `tools/bizhawk-headless/src/Recording/DynamicArtRomProfile.cs`
- Create: `tools/bizhawk-headless/tests/DynamicArtRomProfileTests.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Produces: immutable S1/S2 profile values for decision entry/return windows,
  accepted DMA return, VBlank completion sites, RAM variables, DPLC tables,
  art spans, staging buffers, VRAM banks, and opcode signatures.

- [ ] Write ROM-backed tests that verify every address/opcode window against
  S1 REV01 and S2 REV01 and fail on the current missing profile.
- [ ] Run
  `BIZHAWK_HOME=<BH> S1_ROM_PATH=<S1> S2_ROM_PATH=<S2> tools/bizhawk-headless/test.sh --filter DynamicArtRomProfile --jobs 1`
  and record the expected missing-profile failure.
- [ ] Implement the immutable profiles using offsets independently verified
  against `docs/s1disasm/` and `docs/s2disasm/`.
- [ ] Rerun the focused test green and independently review every literal.

### Task 2: Define the native DPLC wire model

**Files:**
- Create: `tools/bizhawk-headless/src/Recording/DynamicArtTransferState.cs`
- Create: `tools/bizhawk-headless/tests/DynamicArtTransferStateTests.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Produces: `DynamicArtRequest`, `DynamicArtSubmissionOrigin`,
  `DynamicArtTransferDescriptor`, `DynamicArtTransferEdge`,
  `DynamicArtTransferEnvelope`, `DynamicArtGapEdge`,
  `DynamicArtGapTransition`, and deterministic JSON formatting for
  `dynamic_art_transfer_state_per_frame_v1`.

- [ ] Write failing golden tests for empty heartbeat, ordered requests,
  segment/run-gap origins, submission/completion pairing, terminal forwarding,
  address-domain sentinels, and deterministic lowercase fingerprints. Pin
  manifest-only gap cursors, rejection of segment cursor fields in gap edges,
  before-ledger hashes, after-ledger descriptors, and deterministic gap JSON.
- [ ] Confirm red because the types/formatter do not exist.
- [ ] Implement strict constructors and LF-only JSON formatting.
- [ ] Rerun focused tests green; add negative cases for duplicate edge ordinal,
  illegal owner/phase, invalid cursor, and malformed request domains.

### Task 3: Observe S1 DPLC decisions and completions

**Files:**
- Create: `tools/bizhawk-headless/src/Recording/S1DynamicArtObserver.cs`
- Create: `tools/bizhawk-headless/tests/S1DynamicArtObserverTests.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Consumes: `DynamicArtRomProfile`, `IGpgxHost`, `ICpuRegisterReader`, ROM
  bytes.
- Produces: buffered per-row envelopes and run-gap transitions.

- [ ] Write failing callback tests for changed-frame submission, multi-run ROM
  preparation, multi-run ROM decode, duplicate suppression, empty DPLC reuse,
  repeated-preparation replacement, all VBlank promotion/completion sites,
  same-frame submit/complete, lag forwarding, terminal forwarding, and empty
  submitted ledger at segment arm. Prove no transfer id/edge is allocated at
  preparation time and only the final staging payload at the verified
  pre-transfer probe is submitted. Pin the distinct S1 special-stage path and
  prove its submissions/completions or invariant-correct empty heartbeats.
- [ ] Pin pre-transfer probes `$0D20/$0E34/$0F24/$1030` separately from
  post-transfer/post-clear completion PCs `$0D50/$0E64/$0F54/$1060`;
  account for BizHawk's pre-instruction callback state, prove a flagged probe
  promotes exactly one compatible final preparation, and require that latch
  before completion so the no-change branch cannot fabricate one.
- [ ] Cover a segment-terminal submission completed in a following run gap:
  the completion must be a manifest gap edge for the same transfer id, never
  forwarded into the closed segment. Prove segment-local cursors reset only
  at the next arm while run-wide ordinals/transfer ids remain monotonic.
- [ ] Confirm the tests fail because the observer is absent.
- [ ] Implement callback registration with opcode-window checks, ordered ROM
  request decoding, fixed RAM-buffer completion evidence, and strict ledger
  reconciliation.
- [ ] Rerun focused tests green and run an S1 ROM smoke capture that proves at
  least one paired lifecycle without reading canonical expected bytes.

### Task 4: Observe S2 level and special-stage DPLC DMA

**Files:**
- Create: `tools/bizhawk-headless/src/Recording/S2DynamicArtObserver.cs`
- Create: `tools/bizhawk-headless/tests/S2DynamicArtObserverTests.cs`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.csproj`
- Modify: `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`

**Interfaces:**
- Consumes: `DynamicArtRomProfile`, `IGpgxHost`, `ICpuRegisterReader`, S2
  ROM/RAM.
- Produces: Sonic/Tails/tails-tail and special-stage lifecycle envelopes.

- [ ] Write failing tests for verified decision entry/return batching,
  accepted `QueueDMATransfer` return, queue-full rejection, unrelated DMA
  interleaving, multiple runs, forced repeated mapping frames,
  `ProcessDMAQueue` completion, same-frame order, lag/terminal forwarding, and
  special-stage owners.
- [ ] Cover VBlank interruption of a gated decision: at `$14AC`, retire only
  previously accepted ledger work and mark a zero-request pre-mapping gate
  interrupted. Prove a later matching mapping/return resumes normally; if no
  such callback occurs, only the next entry with the same owner, typed entry
  kind, and required caller/context latch may expire and replace it. Reject
  different-owner/kind/context replacement and active queue-call overlap. Add
  a negative case where the interrupted decision has already accepted one DMA
  request before `$14AC`; fail closed rather than retiring, preserving, or
  replacing that partially owned decision. Pin the retail halfpipe sequence:
  `$34AB0` at movie frame 15076, `$14AC` at 15078 retiring transfer 9419, no
  `$34AC4`/`$34B1A`, then identical `$34AB0` replacement at 15079. Retain a
  normal `$34AB0→$34AC4→$34B1A` case.
- [ ] Pin typed S2 level entry kinds: object-state mapping frames at Sonic
  `$1B848` / Tails `$1D1AC`, and direct-`d0` Part2 frames at Sonic `$1B84E` /
  Tails `$1D1B2`. ROM-test the callback/caller windows and prove direct Part2
  shares the same owner return without permitting unmatched returns. Prove
  normal fallthrough through Part2 does not open a duplicate decision, and
  require a pinned pilot-caller latch before a direct Part2 entry can open.
- [ ] Pin Tails-tails independently at entry `$1D184` / return `$1D1FE`,
  including its mapping-frame suppression byte, destination bank, accepted
  batch, queue-full rejection, and separation from the overlapping Tails
  routine.
- [ ] Cover explicit source domains: normal level player-art batches remain
  within pinned ROM spans, while verified special-stage owner batches may
  submit decompressed main-RAM sources. Reject mixed-domain batches and
  source-range-only ownership.
- [ ] Use the special-stage shared decoder `$33ADA→$33B3E` as the typed
  Sonic/Tails owner boundary, not wrapper reachability. Pin and test exact
  prepared contexts: `ss-sonic` (`a4=$F766`, `d4=$5CA0`, `d1=0`) and
  `ss-tails` (`a4=$F7DE`, `d4=$6000`, `d1=$12`), with mapping read at
  `$33ADE`; reject every other context and unmatched return.
- [ ] Pin special-stage Tails-tails independently at
  `$34AB0→$34B1A`, mapping read `$34AC4`, suppression `$F7DF`, and
  destination `$62C0`.
- [ ] Confirm red because the observer is absent.
- [ ] Implement exact decision scopes and entry/return acceptance
  reconciliation; never classify by source range alone.
- [ ] Rerun focused tests green and perform an S2 ROM smoke capture proving
  all expected owners seen in their representative movies.

### Task 5: Make native capture audit mandatory

**Files:**
- Modify: `tools/bizhawk-headless/src/Program.cs`
- Modify: `S1TraceCaptureRunner.cs`, `S1RunCaptureRunner.cs`,
  `S2TraceCaptureRunner.cs`, `S2RunCaptureRunner.cs`, and
  `S2SpecialStageCaptureRunner.cs`.
- Modify: `S1TraceMetadataWriter.cs`, `S1CompleteRunMetadataWriter.cs`,
  `S1RunManifestWriter.cs`, `S2TraceMetadataWriter.cs`,
  `S1SpecialStageMetadataWriter.cs`, `S2SpecialStageMetadataWriter.cs`, and
  `S2RunManifestWriter.cs`.
- Modify: `tools/bizhawk-headless/tests/TraceCliTests.cs`
- Modify: `tools/bizhawk-headless/tests/S1TraceCaptureRunnerTests.cs`
- Modify: `tools/bizhawk-headless/tests/S1RunCaptureRunnerTests.cs`
- Modify: `tools/bizhawk-headless/tests/S1TraceDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S1CompleteRunDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S1RunModeDifferentialTests.cs`
- Modify: `tools/bizhawk-headless/tests/S2TraceCaptureRunnerTests.cs`
- Modify: `tools/bizhawk-headless/tests/S2RunCaptureRunnerTests.cs`
- Modify: `tools/bizhawk-headless/tests/S2SpecialStageCaptureRunnerTests.cs`
- Modify: `tools/bizhawk-headless/tests/S2TraceDifferentialTests.cs`

**Interfaces:**
- Consumes: S1/S2 observers and envelopes.
- Produces: exactly one PLC state for each stored level row, exactly one DPLC
  envelope for every stored level/special-stage row, and ordered
  `dynamic_art_gap_transitions`.

- [ ] Write failing CLI/runner tests proving audit defaults on, canonical
  publication cannot disable it, special stages advertise DPLC only, all
  prefix/lag rows have heartbeats, standalone arms are empty, an unpublished
  S1 gap preparation may cross without an ID/edge/descriptor, and exact
  already-accepted S2 FIFO descriptors reconcile from the final gap ledger to
  the next segment's immutable initial ledger.
- [ ] Confirm existing default-off behavior fails those tests.
- [ ] Remove positive opt-in semantics from canonical capture, integrate
  one-row buffering, metadata versions/capabilities, terminal envelopes, and
  run manifests.
- [ ] Rerun all native no-gates tests with zero failures/skips.

### Task 6: Add strict Java parsing and fixture validation

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceEvent.java`
- Modify: `TraceMetadata.java`, `TraceData.java`, `TraceEventFormatter.java`
- Modify: `src/main/java/com/openggf/trace/TraceRunManifest.java`
- Modify: `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/main/java/com/openggf/trace/SpecialStageTraceData.java`
- Modify:
  `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageTraceData.java`
- Create: `src/main/java/com/openggf/trace/StoredPhysicsFrameDomain.java`
- Create: focused model classes only if `TraceEvent.java` would become
  materially less coherent.
- Modify: `src/test/java/com/openggf/trace/TestLoadQueueTraceComparison.java`
- Create: `src/test/java/com/openggf/trace/TestDynamicArtTransferTrace.java`
- Modify: `src/test/java/com/openggf/tests/trace/TestTraceRunManifest.java`
- Modify:
  `src/test/java/com/openggf/tests/trace/runs/TestTraceRunReplayWalkerControlFlow.java`

**Interfaces:**
- Produces: typed event dispatch, full-row completeness validation, lifecycle
  validation, special-stage frame-domain scanning, run-schema migration, and
  manifest gap validation.

- [ ] Write failing JUnit tests for valid envelopes, unknown advertised event,
  missing/extra row, first/last/lag row, cursor/order/pairing failures,
  terminal pending, empty submitted-ledger-at-arm, gap ledger transitions,
  exact S2 named-run carry-at-arm, legacy omission, and plain/gzip
  special-stage row scans.
- [ ] Cover request/address-domain sentinels and ranges, S1 physical completion
  versus ROM request batches, transfer/edge uniqueness, and validated-but-
  noncompared `rom_callback_pc`.
- [ ] Cover manifest gap ordering, before/after hashes, complete gap
  lifecycles, empty submitted ledger at every standalone and S1 arm, segment
  adjacency, and legacy run-schema compatibility. At a continuous named-run S2
  arm require exact final-gap → initial-ledger descriptor/fingerprint
  continuity; reject S1 initial ledgers and S2 mismatch, missing continuity,
  nonaccepted origin, or fabricated completion.
- [ ] Run
  `mvn -Dmse=off -Dtest=TestDynamicArtTransferTrace,TestLoadQueueTraceComparison,TestTraceRunManifest,TestTraceRunReplayWalkerControlFlow test`
  and confirm focused red failures.
- [ ] Implement the minimal strict parser/validator without accepting unknown
  events as `StateSnapshot` under the advertised capability.
- [ ] Rerun the same focused selection green with zero skips.

### Task 7: Add production-owned engine DPLC diagnostics

**Files:**
- Create: `DynamicArtDiagnosticsProvider.java`,
  `DynamicArtDiagnosticsSnapshot.java`, and
  `DynamicArtLifecycleService.java` under
  `src/main/java/com/openggf/game/resources/`.
- Modify: `src/main/java/com/openggf/sprites/playable/Sonic.java`,
  `Tails.java`, `src/main/java/com/openggf/sprites/managers/TailsTailsController.java`,
  `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java`,
  and `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`.
- Modify: `src/main/java/com/openggf/sprites/render/PlayerSpriteRenderer.java`
  and `src/main/java/com/openggf/level/render/DynamicPatternBank.java` only to
  consume runtime-owned art state rather than owning lifecycle decisions.
- Modify: `src/main/java/com/openggf/game/GameServices.java`
- Modify: `src/main/java/com/openggf/game/rules/GameRules.java`
- Create:
  `src/main/java/com/openggf/game/rules/DynamicArtDmaServiceModel.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Modify:
  `src/main/java/com/openggf/game/resources/PlcFrameLifecycleCoordinator.java`
- Modify: `src/main/java/com/openggf/GameLoopPlcLifecycle.java`
- Modify: `src/main/java/com/openggf/tools/RecordingFrameDriver.java`
- Modify:
  `src/main/java/com/openggf/level/LevelPlayableArtInitializer.java`
- Modify:
  `src/main/java/com/openggf/sprites/managers/PlayableSpriteAnimation.java`
- Modify:
  `src/main/java/com/openggf/game/sonic2/credits/Sonic2EndingCutsceneManager.java`
- Modify:
  `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageDataLoader.java`
- Modify:
  `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageConstants.java`
- Modify:
  `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Rewind inventory selection (recorded before Task 7 production edits):
  `src/main/java/com/openggf/game/resources/DynamicArtLifecycleService.java`
  is the concrete session-owned `RewindSnapshottable` adapter that captures
  the persistent outstanding ledger, owner duplicate-suppression cursors,
  logical cursor, buffered edges, last published one-row state, and the
  complete unpublished S1 staging preparation atomically. That preparation
  includes owner/mapping identity and ordered decoded requests. Missing-
  snapshot reset clears it together with all other run state;
  `src/main/java/com/openggf/game/session/GameplayModeContext.java` owns its
  registration, deregistration, and missing-snapshot reset path.
- Add focused JUnit tests beside the owning services.
- Modify:
  `src/test/java/com/openggf/game/resources/TestPlcFrameLifecycleCoordinator.java`
- Modify: `src/test/java/com/openggf/tests/game/TestGameRulesConstants.java`
- Create:
  `src/test/java/com/openggf/game/rules/TestDynamicArtDmaServiceModel.java`
- Modify:
  `src/test/java/com/openggf/game/resources/TestPlcLifecycleDriverParity.java`
- Create:
  `src/test/java/com/openggf/tools/TestRecordingFrameDriverDynamicArt.java`
- Create:
  `src/test/java/com/openggf/sprites/playable/TestPlayableDynamicArtOwnership.java`
- Create:
  `src/test/java/com/openggf/sprites/ghost/TestDynamicArtGhostIsolation.java`
- Create:
  `src/test/java/com/openggf/game/sonic2/credits/TestSonic2EndingCutsceneDynamicArt.java`
- Modify:
  `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageDataLoaderTest.java`
- Modify:
  `src/test/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManagerTest.java`
- Modify:
  `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManagerTest.java`
- Modify:
  `src/test/java/com/openggf/trace/TestS1S2PlcComparisonOnlyGuard.java`
- Modify:
  `src/test/java/com/openggf/tests/trace/runs/TestTraceRunReplayWalkerControlFlow.java`
- Modify:
  `src/test/java/com/openggf/tests/trace/runs/AbstractRunChainTest.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- Modify:
  `src/test/java/com/openggf/tests/trace/runs/TestS1GhzMazeRoundTripChain.java`
- Modify:
  `src/test/java/com/openggf/tests/trace/runs/TestS2EhzHalfpipeRoundTripChain.java`

**Interfaces:**
- Produces: immutable `DynamicArtDiagnosticsSnapshot` envelopes independently
  derived from production decisions, including the same lag/terminal
  publication rule.
- Freeze the service lifecycle as `beginRun()`, `openComparisonSegment()`,
  `closeComparisonSegment()`, and `finishRun()`. `GameplayModeContext`
  attachment/teardown exclusively owns `beginRun()`/`finishRun()` for live
  and headless sessions. Normal live/headless gameplay opens its production
  segment through `GameLoopPlcLifecycle`; run replay may invoke
  open/close only from a new nested
  `TraceRunReplayWalker.DynamicArtSegmentController`, which targets a
  value-free `DynamicArtSegmentWindow` interface (`open()` / `close()` only).
  `RecordingFrameDriver` implements the headless adapter; the real named-run
  owners `AbstractRunChainTest` and `TraceSessionLauncher` construct adapters
  over the current production `GameplayModeContext` and invoke the controller
  at their already-established structural segment/gap/terminal boundaries.
  That seam passes no expected event, envelope, ledger, ID, cursor, lag flag,
  or gameplay value. Parsers, binders, and comparators have no reference to
  any other mutating lifecycle API.

- [ ] Write failing owner tests for duplicate/empty DPLC behavior, multi-run
  batches, completion timing, special-stage paths, empty submitted ledger at
  standalone arm, replaceable S1 preparation across a named-run gap/arm, and
  accepted S2 FIFO continuity across a named-run arm.
- [ ] Confirm failures reflect absent production diagnostics, not trace input.
- [ ] Move semantic lifecycle ownership out of renderer-only code with the
  smallest accurate owner; rendering consumes state but trace comparison
  remains read-only.
- [ ] Inject the service into `PlcFrameLifecycleCoordinator`; prove
  live/headless claim/finish parity for active, lag, pause, special stage,
  credits, ending, and terminal close without expected trace input.
- [ ] Split run-active observation from comparison-segment publication.
  While closed, journal decisions/completions as gap activity; preserve
  IDs, ordinals, FIFO, and duplicate cursors. At next open the S1 submitted
  ledger must be empty; S2 may preserve only independently accepted pending
  FIFO work. Preserve an independently produced unpublished S1 staging
  preparation across the arm
  without receiving descriptors, fingerprints, IDs, or values from trace data.
  Add owner/service tests
  proving repeated preparations replace before claim, no ID is allocated
  early, and the final preparation alone submits/completes at the S1 VBlank
  boundary; separately prove the same S2 transfer ID survives gap → arm until
  the matching production service claim.
- [ ] Remove any artificial `serviceProductionVBlank()` from structural
  comparison-segment open. Permit `openComparisonSegment()` to preserve an
  independently populated S2 ledger/FIFO while continuing to reject S1
  submitted-ledger carry. Initialize the new segment's publication baseline
  from production outstanding IDs without accepting trace descriptors,
  fingerprints, IDs, or timing values.
- [ ] Add a real Java named-run regression for the pinned halfpipe boundary:
  production transfer 8078 is already accepted before `ss_2` arms, remains
  outstanding through rows 0–125 under non-service transition/fade claims,
  and completes only on the first real row-126 production `SPECIAL_STAGE`
  service claim. Reject premature retirement at row 0 and delayed retirement
  after that first service boundary. The expected manifest may compare
  identity/continuity but must not submit, preserve, release, or select service
  for the job.
- [ ] Test `DynamicArtSegmentController` structural begin/end translation,
  balanced close on normal, terminal, and exceptional exits, and that no
  expected trace payload or value crosses into the mutating lifecycle API.
  Drive both actual named-run owners (`AbstractRunChainTest` and
  `TraceSessionLauncher`) through at least one represented segment,
  unrepresented gap, next segment, and terminal close; prove their production
  gap journal receives the boundary activity.
- [ ] Remove mutation from generic Sonic/Tails mapping setters. Inject a
  production-only capability through `LevelPlayableArtInitializer` into
  `PlayableSpriteAnimation` and `TailsTailsController`; prove animated ghosts
  cannot change lifecycle state.
- [ ] Complete S2 batches only at the next coordinator claim approved by a
  typed `DynamicArtDmaServiceModel` in `GameRules` (`ProcessDMAQueue`
  equivalent); prove
  FIFO completion of multiple owners before current-row submissions.
  Non-service claims publish without retirement. The policy may consume only
  production phase/mode state, never trace frames, expected events,
  descriptors, or cursors.
- [ ] Inject the typed model into the coordinator without game-name checks.
  Prove S2 transition/fade claims are non-service, S2 `SPECIAL_STAGE` claims
  service, S1 claim behavior is unchanged, and unsupported/ambiguous phases
  fail closed rather than defaulting to service.
- [ ] Move ending and repeated direct-Part2 pilot decisions from draw into
  `Sonic2EndingCutsceneManager.update()`; prove headless update emits and draw
  is diagnostics-inert. Initialize the pilot timer from the ROM's zeroed
  `objoff_37` state so the first main update decrements to negative and
  immediately prepares/publishes the first pilot decision; test default
  cadence without overriding private timer state.
- [ ] Decode/cache S2 special-stage DPLC plans through
  `Sonic2SpecialStageDataLoader` from `$345FA`, `$33AA2`, `$349B8`, and
  `$34AA0`; cover 18 Sonic, 18 Tails, and 21 tails-tail frames and remove
  diagnostic use of copied Java tables.
- [ ] Rerun owner, coordinator parity, rewind coverage, representative
  headless, ending, ghost-isolation, and ROM-loader tests green.
- [ ] Run rewind, static-state, segment-reset, and session-reset guards proving
  the persistent provider is restored only from production rewind state and
  never from trace data.
- [ ] Prove unpublished S1 preparation rewind/reset semantics: prepare A →
  capture → replace with B → restore → claim promotes only A; restoring a
  snapshot with no preparation clears stale staging; finish/begin run and
  session reset cannot leak a preparation into the next run.
- [ ] Run
  `mvn -Dmse=off -Dtest=TestDynamicArtLifecycleService,TestPlcFrameLifecycleCoordinator,TestPlcLifecycleDriverParity,TestDynamicArtDmaServiceModel,TestGameRulesConstants,TestRecordingFrameDriverDynamicArt,TestPlayableDynamicArtOwnership,TestDynamicArtGhostIsolation,TestSonic2EndingCutsceneDynamicArt,Sonic2SpecialStageDataLoaderTest,Sonic1SpecialStageManagerTest,Sonic2SpecialStageManagerTest,TestGameplayModeContextRewindRegistry,TestS1S2PlcComparisonOnlyGuard,TestRewindCoverageGuard,TestHelperStateRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestTraceRunReplayWalkerControlFlow,TestTraceSessionLauncherRunBranch,TestS1GhzMazeRoundTripChain,TestS2EhzHalfpipeRoundTripChain test`
  and require zero failures, errors, and skips.

### Task 8: Compare DPLC envelopes without authority

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceBinder.java`
- Modify: `src/main/java/com/openggf/trace/live/LiveTraceComparator.java`
- Modify:
  `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Modify: `src/main/java/com/openggf/trace/SpecialStageTraceData.java`
- Modify:
  `src/main/java/com/openggf/trace/replay/runs/TraceRunReplayWalker.java`
- Modify: `src/main/java/com/openggf/GameLoop.java`
- Modify: `src/main/java/com/openggf/TraceSessionLauncher.java`
- Modify:
  `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageTraceData.java`
- Create: `src/main/java/com/openggf/trace/DynamicArtSpecialStageComparator.java`
- Modify:
  `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`
- Modify:
  `src/test/java/com/openggf/trace/TestS1S2PlcComparisonOnlyGuard.java`
- Create:
  `src/test/java/com/openggf/trace/TestDynamicArtDiagnosticsComparator.java`
- Modify: `src/test/java/com/openggf/TestTraceSessionLauncherRunBranch.java`
- Create:
  `src/test/java/com/openggf/TestGameLoopTraceRunPostIteration.java`

**Interfaces:**
- Consumes: expected typed envelopes and production snapshots.
- Produces: zero-tolerance `dynamic_art.*` frontier fields.
- Freeze visual comparison ordering as
  `GameLoop.runLogicalIteration(...)` (including coordinator `finish()`),
  followed by the value-free
  `TraceSessionLauncher.afterProductionIteration()` hook, followed by any
  deferred end-of-run or teardown. The launcher may obtain audit state only by
  pulling `GameServices.captureDynamicArtDiagnostics()` after publication.
  The lifecycle service stores no callback, listener, comparator, launcher,
  trace object, or other capable reference; rewind restore emits no
  notification.
- Add a production-owned service-lifetime delivery serial to each immutable
  snapshot. Preserve it across `beginRun()`, `finishRun()`, segment changes,
  and row-counter resets; increment it exactly once per published row.
  Exclude it from rewind state: restore rebuilds the restored payload with the
  current serial and emits no notification. `TraceSessionLauncher` records the
  current serial at each `beforeProductionIteration()` and detects freshness
  from a newer post-finish serial, equality with the production segment
  generation recorded after the target segment opens and binds, and equality
  between the snapshot's segment-local frame and the pending target row. Add a
  service-lifetime segment generation to immutable snapshots and increment it
  only on `openComparisonSegment()`; like the delivery serial it is
  production delivery identity, excluded from rewind state, and preserved
  across restore. Add an explicit published-row marker to the immutable
  snapshot. Segment open installs `published=false` for the new generation;
  actual publication atomically installs `published=true`, delivery serial,
  segment generation, and row for one production row. The launcher requires
  the marker plus the three identity checks. It never orders frames across
  segments or retains a cursor across run boundaries. This conjunction
  prevents an old-segment terminal publication during rebind from satisfying
  an omitted new-segment row even when both local row numbers are zero.
  When a run starts directly in an SS segment that is already current/open,
  bind its current production generation while arming the first pending SS
  row; for anticipated transitions, continue binding only after the target
  segment opens so an old generation cannot leak forward.

- [ ] Write failing comparisons for exact match, missing/extra edge,
  request-order mismatch, lifecycle/ledger mismatch, and lag/terminal rows.
- [ ] Add negative guard fixtures proving trace data cannot submit, complete,
  seed, or mutate production work.
- [ ] Prove the production publication boundary directly: the post-iteration
  hook runs only after coordinator `finish()`, pulls one immutable snapshot,
  drains the terminal row before deferred end-of-run/teardown, and never
  registers a callback or capable reference with the lifecycle service.
- [ ] Exercise the real `GameLoop` ordering: prove an SS row zero produced
  after a populated level segment and segment rebind reaches only the rebound
  comparator despite the row counter restarting at zero, and end-of-run or
  teardown requested inside the iteration body is deferred until the terminal
  immutable snapshot is compared exactly once. Add guards rejecting any
  capable reference in the diagnostics service fields or APIs.
- [ ] Prove delivery-serial lifetime rules with publish → rewind → republish
  and finish-run → begin-run → first-publication tests. Rewind restore must
  neither roll back nor increment the serial, while each republished/new-run
  row increments it once and remains visible to the post-finish pull.
- [ ] Add a negative transition regression with buffered old-segment work:
  closing a one-row level segment publishes terminal row zero and advances the
  serial, the SS segment opens with a new generation and also expects row zero,
  and omission of the SS publication must still fail because the post-open
  snapshot is explicitly unpublished. Assert the complete mixed-state
  collision: old serial, new generation, row-zero target, no new publication.
- [ ] Add a special-stage-first named-run regression: the initial production
  segment is already open before SS comparison arms, its current generation
  is bound at arm time, and the first published SS row compares successfully
  without a synthetic close/open transition.
- [ ] Implement comparison and shared replay/live wiring.
- [ ] Keep all S1/S2 special-stage stepping in the existing replay
  driver/harness. After each production step, capture an immutable
  `DynamicArtDiagnosticsSnapshot` and pass only that snapshot plus the
  expected envelope to the DPLC-only comparator. Compare
  first/last/lag/terminal envelopes while leaving unrelated special-stage
  gameplay columns under their existing comparison policies; the comparator
  must expose no stepping, mutation, submission, or completion API.
- [ ] Run focused comparator and authority guards green.
- [ ] Run
  `mvn -Dmse=off -Dtest=TestDynamicArtDiagnosticsComparator,TestGameLoopTraceRunPostIteration,TestTraceSessionLauncherRunBranch,TestHardwareTimingAuthorityGuard,TestS1S2PlcComparisonOnlyGuard test`
  and require zero failures, errors, and skips.

### Task 9: Representative end-to-end proof and review

**Files:**
- Modify:
  `tools/bizhawk-headless/tests/S1TraceDifferentialTests.cs`
- Modify:
  `tools/bizhawk-headless/tests/S1CompleteRunDifferentialTests.cs`
- Modify:
  `tools/bizhawk-headless/tests/S2TraceDifferentialTests.cs`
- Modify:
  `tools/bizhawk-headless/docs/s1-trace-recorder-behavior.md`
- Modify:
  `tools/bizhawk-headless/docs/s2-trace-recorder-behavior.md`
- Modify: `tools/bizhawk-headless/README.md`
- Update: the design if ROM evidence changes any assumption.

- [ ] Capture one S1 level, one S1 special stage, one S2 level, and one S2
  special stage into new scratch roots.
- [ ] Verify per-row PLC/DPLC completeness, event ordering, paired lifecycles,
  empty submitted ledgers at standalone and S1 segment arms, exact final-gap
  to initial-ledger descriptor/fingerprint continuity at named-run S2 arms,
  and unpublished S1 preparation replacement/cross-arm behavior with
  independent literal expectations. Pin
  the observed S1 SS→GHZ2 case: movie frame 8048 prepares mapping 48, GHZ2
  arms at 8049 with no submitted transfer, and row 0 promotes the final
  preparation then completes it only at callback `$1060`. Pin the late
  replacement case at movie frame 136632: pending mapping `$09` preparation
  (ROM `$23090`, 192 bytes to VRAM `$F000`) is superseded by mapping `$01`
  (ROM `$22610`, 96 bytes to the same destination) before VBlank, so only the
  latter may receive a transfer id and lifecycle pair.
  Pin the S2 EHZ→`ss_2` carry: accepted tails-tail transfer 8078, mapping
  `$0D`, ROM `$650E0` tile 110, 384 bytes to VRAM `$F600`, arms at BK2 frame
  12605 and completes only at new-segment row 126 / BK2 frame 12731 at
  `$14AC`.
- [ ] Run representative Java replay tests and record the first genuine DPLC
  frontier rather than normalizing it.
- [ ] Run the complete native suite with all ROMs and obtain independent
  semantic/code review with no blockers. Before candidate publication, permit
  only exact enumerated old-fixture capability refusals after successful
  recorder execution; any runtime, semantic, unclassified failure, skip, or
  additional comparator mismatch stops.
- [ ] Pin the S2 complete-emeralds differential child timeout to 2,400,000 ms
  only for that route. Add a test that the route receives the long allowance
  while ordinary children retain their existing budget. Record the isolated
  measurement: 35 segments, exit 0, wall 21:56.50, user 1304.25 s, system
  8.77 s, max RSS 243,744 KiB. A timeout remains a failure and must never
  publish partial output.
- [ ] Add a deterministic timeout-path test with a stub child that writes
  partial staging data and exceeds a tiny test-only timeout. Assert the child
  is terminated, the differential fails, and no candidate/final output is
  promoted or accepted. Capture writes only to fresh staging and promotes
  after exit zero plus complete validation. Prove the test override does not
  change the ordinary or complete-emeralds production timeout selection.

### Task 10: Regenerate, approve, install, and measure the fleet

**Files:**
- Update: the existing regeneration audit, validation report, and
  `docs/status/trace-frontier-log.md`.
- Create:
  `tools/bizhawk-headless/tests/ProposedTraceFleetPublicationTests.cs`
- Modify:
  `tools/bizhawk-headless/BizHawk.Headless.Gpgx.Tests.csproj`
- Modify: `tools/bizhawk-headless/tests/TestMain.cs`
- Create:
  `src/test/java/com/openggf/tests/trace/TestProposedTraceFleetValidation.java`
- Replace: approved canonical fixture payloads only.

- [ ] Re-freeze the 32-invocation matrix with mandatory audit capability and
  record recorder build/diff hashes.
- [ ] Before installation, run the full wildcard `*TraceReplay` selection and
  every replay class in isolation against the retained canonical baseline,
  preserving unique reports and exact first frontiers.
- [ ] Capture serially to a new absent scratch root; retain the eight S1 credits
  fixtures without claiming regeneration.
- [ ] Freeze every candidate file length/SHA-256, metadata version, segment
  inventory, row/event counts, ranges, ordering, and named delta cause.
- [ ] Add a durable dual-mode native publication verifier. With explicit
  `OGGF_PROPOSED_TRACE_ROOT`, validate only that candidate against a frozen
  candidate literal set. Without the variable, validate the installed
  canonical tree against a separate canonical literal set so ordinary
  native suites and CI remain self-contained. Encode literal expected file
  inventory, lengths, SHA-256 values, metadata/capability versions, segment
  inventories, row/event counts, ranges, and ordering; reject every missing
  or extra file. Candidate mode must neither recapture nor read, compare,
  mutate, or infer expectations from the canonical fixture root.
- [ ] Hand-add the proposed publication source to the non-SDK native test
  project and register it in `TestMain`; prove a deliberately missing
  registration or project entry makes the gate fail.
- [ ] Give the Java validation gate the same durable dual-mode contract.
  With explicit `OGGF_PROPOSED_TRACE_ROOT`, walk only the frozen candidate
  inventory; without it, walk the installed canonical inventory. Load every
  level/special-stage trace and run manifest through production parsers and
  validators, including DPLC lifecycle/pairing, special-stage frame domains,
  and run-gap ledgers. Candidate mode must never resolve the canonical fixture
  root. Both modes report zero schema/lifecycle/completeness errors and zero
  skips.
- [ ] Run the proposed-fleet publication tests with zero failures and zero
  skips using
  `OGGF_PROPOSED_TRACE_ROOT=<frozen-absolute-candidate-root> tools/bizhawk-headless/test.sh --filter ProposedTraceFleetPublication`,
  and run
  `OGGF_PROPOSED_TRACE_ROOT=<frozen-absolute-candidate-root> mvn -Dmse=off -Dtest=TestProposedTraceFleetValidation test`
  with zero failures and zero skips. Candidate mode must fail, never skip,
  when the supplied variable is malformed, resolves to the canonical fixture
  root, or points outside the frozen candidate inventory. Absent-variable
  canonical mode must continue passing against the retained old canonical
  literals before publication. Then obtain an independent
  review of the literal manifest,
  native/Java candidate-root isolation, and production-parser coverage before
  requesting approval.
- [ ] Obtain explicit approval for that exact new payload.
- [ ] Install byte-for-byte, promote the approved candidate literal set to the
  regenerated portion of the canonical literal set without changing any
  approved payload expectation, and combine it with a separately frozen
  unchanged literal set for the eight retained S1 credits fixtures. Canonical
  missing/extra rejection covers that exact union and must not describe the
  retained fixtures as regenerated. Prove absent-variable canonical mode
  passes. Then run
  compression/schema/reference guards, the full native gate, every isolated
  `*TraceReplay` class, and the full Maven suite.
- [ ] Produce the exhaustive green/red frontier table and complete the
  repository integration/push/cleanup workflow from `AGENTS.md`.
