# S3K direct Kosinski decompression queue implementation plan

Date: 2026-07-28

Design:
[`../designs/2026-07-28-s3k-kos-decompression-queue.md`](../designs/2026-07-28-s3k-kos-decompression-queue.md)

## Delivery constraints

- Develop on `bugfix/ai-s3k-kos-decompression-queue` in an isolated worktree
  created from the main workspace's current `develop`.
- Use JDK 21 (`mvn -v` is authoritative).
- Follow test-first red/green/refactor for each behavior task.
- Do not modify committed files under `src/test/resources/traces/` without a
  separate explicit user approval for an exact reviewed publication candidate.
- The native headless recorder is fixture authority. Lua changes are optional
  corroboration and are not required solely for parity.
- C# changes remain C# 7.x and new files, if any, are added to both project
  files and registered in the plain test registry.
- Preserve unrelated dirty files in the main workspace.

## Task 1: Per-kind hardware authority and schema 2

### Tests first

Extend:

- `src/test/java/com/openggf/game/timing/TestHardwareTimingService.java`
- `src/test/java/com/openggf/game/timing/TestHardwareTimingRewind.java`
- `src/test/java/com/openggf/trace/timing/TestHardwareTimingStreamLoader.java`
- `src/test/java/com/openggf/trace/timing/TestHardwareTimingReplayPort.java`
- `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`
- `src/test/java/com/openggf/trace/TestTraceDataHardwareTiming.java`
- `src/test/java/com/openggf/trace/timing/TestCommittedHardwareTimingFixtures.java`
- `src/test/java/com/openggf/TestLevelFrameHardwareTimingBoundaries.java`
- `src/test/java/com/openggf/TestLevelIterationHardwareTimingAdmissionOrder.java`
- `src/test/java/com/openggf/tools/TestRecordingFrameDriverHardwareTiming.java`

Prove:

- schema 1 authorizes only `KOS_MODULE_QUEUE`;
- schema 2 authorizes module and direct kinds;
- no stream leaves both kinds live;
- direct readiness is live while a module parent remains recorded under
  schema 1;
- an edge for a non-recorded kind is rejected;
- per-kind policy and ordinal state round-trip through rewind;
- canonical ordering and ordinal continuity remain per kind.
- schema-2 direct readiness is admitted at PRE before same-frame consumers,
  followed by an independently matching module POST edge;
- committed schema-1 streams remain loadable and enforce recorded module work
  while live-only direct jobs do not become leftover recorded submissions.

Run the focused tests and observe the expected failures before implementation.

### Implementation

Update:

- `HardwareWorkKind`
- `HardwareTimingService` and snapshot/job helpers
- `RecordedCompletionAuthority`
- `HardwareTimingReplayPort`, schedule, loader, coordinator/metadata plumbing
- schema/version validation and error messages

Replace the single admission policy with an immutable/snapshotted per-kind
map. Make preparation and live readiness FIFO ordering per kind. Preserve
generic authority isolation.

### Verification

```bash
mvn "-Dtest=TestHardwareTimingService,TestHardwareTimingRewind,TestHardwareTimingStreamLoader,TestHardwareTimingReplayPort,TestHardwareTimingAuthorityGuard,TestTraceDataHardwareTiming,TestCommittedHardwareTimingFixtures,TestLevelFrameHardwareTimingBoundaries,TestLevelIterationHardwareTimingAdmissionOrder,TestRecordingFrameDriverHardwareTiming" test
```

Reviewer checklist: no gameplay APIs become reachable; schema 1 behavior for
module-only fixtures remains parse-compatible; unknown schema/kind fails.

## Task 2: Shared direct queue and standard-Kos scanner

### Tests first

Create `TestS3kKosDecompressionQueue` and extend
`TestResumableKosinskiDecoder`.

Prove:

- standard Kos source begins at its descriptor and scans its exact compressed
  and decoded lengths;
- descriptor refill, literal, short/long match, no-output command, terminator,
  invalid backreference, and ROM-bound failures;
- four physical jobs fit and a fifth ordinary submission fails;
- only `PRE_MAIN_LOOP` advances/retire direct work;
- ready-unclaimed payload does not consume physical capacity or keep the
  queue-pending predicate true;
- head shift, identical adjacent jobs, and retire-plus-append retain identity;
- snapshot/restore works before, during, and after active decoding;
- recorded direct admission holds an already prepared head until its exact PRE
  edge;
- exact `0xFFFFxxxx` destination bits participate in fingerprinting.

An ordinary production call that attempts to append beyond physical capacity
is an invariant failure. Module service always checks capacity first and
returns/retries without submitting. Ready-but-unclaimed retired payload is not
physical occupancy.

### Implementation

Add beside `S3kKosModuleQueue`:

- direct descriptor;
- direct queue/session owner;
- preparation and rewind snapshot;
- standard-Kos inspection result if the existing reader cannot express it.

Reuse `ResumableKosinskiDecoder`; do not add a second codec. Keep physical FIFO
state separate from ready/claimed timing jobs. Add exact canonical 68000 RAM
destination constants/mapping.

Wire the physical owner into the gameplay-session lifecycle. Inspect and update
the actual construction path among `GameplayModeContext`, `GameServices`,
`Sonic3kGameModule`, and `Sonic3kObjectArtProvider`; register/reset exactly one
owner and ensure all facades resolve it after level load, seamless transition,
rewind restore, and session teardown. Add registration/reset tests and include
`TestRewindCoverageGuard` and `TestStaticStateRewindCoverageGuard`.

### Verification

```bash
mvn "-Dtest=TestS3kKosDecompressionQueue,TestResumableKosinskiDecoder,TestHardwareTimingRewind,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard" test
```

Reviewer checklist: ROM bytes only; exact four-entry FIFO; PRE boundary; no
zone/route branches; rewind contains active decoder and physical order.

## Task 3: Compose KosM parents over direct children

### Tests first

Extend:

- `TestS3kKosModuleQueue`
- `TestS3kKosModuleReadiness`
- `TestS3kKosStructuralSequence`
- `TestS3kKosTimingRewindIntegration`

Prove frame-by-frame:

- POST A submits child N and returns;
- PRE B completes or prepares child N;
- POST B retires/claims child N and returns;
- POST C submits child N+1;
- ordinary work before and after a child delays module advancement until the
  complete direct FIFO is empty;
- full direct capacity defers child submission rather than throwing;
- direct PRE retirement and final module POST retirement can share a raw frame;
- one physical child produces one direct event and one archive produces at
  most one final module event;
- schema-1 live direct children can prepare a recorded module parent;
- rewind after direct retirement but before module claim is exact.

### Implementation

Refactor `S3kKosModuleQueue`:

- remove its embedded direct decoder;
- make archive parents coordinator-owned aggregate jobs;
- submit exact aligned standard-Kos child streams into the shared direct owner;
- perform at most one module state transition per POST service;
- assemble the parent payload only from claimed child payloads;
- retain parent-child handle metadata in snapshots.

Update all construction sites so facades share the one session/resource owner.
Do not broaden generic timing-service knowledge of S3K parents.

### Verification

```bash
mvn "-Dtest=TestS3kKosModuleQueue,TestS3kKosModuleReadiness,TestS3kKosStructuralSequence,TestS3kKosTimingRewindIntegration" test
```

Reviewer checklist: no private decoder remains; physical capacity/queue-empty
is shared; parent preparation is never generic-scheduler-driven.

## Task 4: AIZ and ICZ gameplay consumers

### Tests first

Extend:

- `TestSonic3kAIZEvents`
- `TestS3kZoneKosRewind`
- AIZ terrain/mutation tests
- ICZ event and level-transition tests
- `TestZoneEventRewindSchemaGuard`
- `TestSonic3kLevelEventRewindSnapshot`
- `TestS3kIczAct1TransitionHeadless`
- `TestSonic3kIczRewindRoundTrip`

Prove:

- AIZ queues its direct block and KosM work once;
- AIZ event/deformation progression and 16x16 publication occur on frame N's
  first scan after final PRE retirement;
- AIZ KosM 8x8 publication waits until the module parent retires at POST and is
  not consumer-visible before frame N+1;
- ICZ queues two ordinary direct streams plus its KosM archive at `$6900`;
- ICZ contains no 41-frame readiness path and waits on physical direct-empty;
- ICZ handles survive PRE completion, in-frame `executeActTransition`, later
  POST module retirement, and ICZ2 publication;
- rewind works before and after both decisive boundaries.

### Implementation

- Split `AizIntroTerrainSwap` direct-block publication from KosM pattern
  publication.
- Add a ROM-derived S3K level-resource profile for the AIZ intro
  load that composes immediate LevelLoadBlock entry 0 with deferred
  declarations sourced from entry 26: the exact main-level 16x16 block and
  KosM pattern descriptors. Keep entry 0's secondary intro resources loaded.
  Keep the profile immutable and
  create a fresh exact-consumption tracker per `Sonic3k.loadLevel` call.
  Consume/verify it in the S3K `LevelResourcePlan` builder without an AIZ
  branch in shared code; prove repeated intro loads reproduce the same
  deferral, entry 0 assets remain visible, entry 26 bytes remain absent until
  their fences, missing/duplicate/non-matching entries fail, and the non-intro
  profile/later ordinary loads are unaffected.
- Update `Sonic3kAIZEvents` ownership and rewind handle rebinding.
- Replace `ICZ2_SECONDARY_KOS_DRAIN_FRAMES` and
  `act2TransitionKosDrainFrames` with shared-queue submissions and handles.
- Transfer ICZ module ownership explicitly through the seamless-transition
  resource owner; do not reset session timing state.
- Add an immutable transition-scoped deferred-resource manifest containing the
  exact already-submitted ICZ2 secondary chunk, block, and KosM descriptors.
  Register it as an immutable `SeamlessTransitionResourceHandoff` containing
  the exact production handles/facades in a session-owned rewind-snapshottable
  registry; carry only its opaque id on the request.
  `LevelActTransitionExecutor` atomically claims/removes the id before any
  mutation, creates a local consumption tracker, passes the policy through a
  `LevelManager.loadLevelData` overload which selects the optional
  level-layer `DeferredLevelResourceLoader` implemented by `Sonic3k` (without
  adding a tracker overload to base `Game`), verifies exact-once consumption,
  initializes target events, then transfers runtime ownership.
  Preserve the handoff when `RELOAD_SAME_LEVEL` is rebuilt. The S3K
  `LevelResourcePlan` builder consumes descriptor identity, omits each matched
  op exactly once, fails missing/duplicate/non-matches, and contains no
  ICZ/zone-name branch. Immediate and queued transitions use the same handoff
  path; ordinary later loads and unrelated transitions remain unchanged.
- Add negative/isolation tests for missing, duplicate, non-matching, and
  double-consumed manifests; exact-once omission; queued execution;
  `RELOAD_SAME_LEVEL` preservation; unrelated transitions; and an ordinary
  later ICZ2 load. Add rewind tests before and after registry claim, plus a
  failed execution proving the claimed id is not restored for retry.
- Update rewind schemas rather than baselining new gaps.

### Verification

```bash
mvn "-Dtest=TestSonic3kAIZEvents,TestS3kZoneKosRewind,TestZoneEventRewindSchemaGuard,TestSonic3kLevelEventRewindSnapshot,TestS3kIczAct1TransitionHeadless,TestSonic3kIczRewindRoundTrip" test
```

Reviewer checklist: ROM predicate, centre-coordinate conventions unaffected,
no fixed delay, no zone check in shared runtime, mutations use the pipeline.

## Task 5: Native recorder schema-2 direct ledger

### Tests first

Extend:

- `tools/bizhawk-headless/tests/HardwareTimingEventEngineTests.cs`
- `tools/bizhawk-headless/tests/S3KTraceMetadataWriterTests.cs`
- `tools/bizhawk-headless/tests/S3KCompleteRunPublicationTests.cs`
- `tools/bizhawk-headless/tests/S3KTraceDifferentialTests.cs`
- applicable standard/complete-run capture runner tests

Prove:

- direct RAM count uses `$FF0E & $7FFF`;
- direct RAM busy state uses bit 15 and remains part of the mirrored lifecycle
  evidence instead of being discarded after count extraction;
- direct FIFO is `$FF40`, four eight-byte entries;
- pending/busy, shift without zero, stale final slot, identical jobs, and
  all retire-plus-append count transitions (`1 -> 1`, `1 -> 2`, `2 -> 3`,
  and longer unchanged-count cases) reconcile through proven canonical
  suffix/prefix overlap;
- unexplained mutation of any occupied slot, including slot zero, fails;
- a still-busy head cannot retire or change identity; busy-to-not-busy proves
  retirement even for an identical `A -> A` replacement, while a stable
  identical snapshot emits no completion and consumes no ordinal;
- standard-Kos scan lengths and fingerprints come from one checked-in
  language-neutral vector source consumed independently by Java and C# tests;
- those shared vectors cover descriptor refill, literal, short/long/extended
  matches, no-output commands, invalid backreferences, terminators, ROM-bound
  failure, compressed length, and decoded length;
- all direct events use `pre_main_loop`;
- direct PRE then module POST sort canonically on the same raw frame;
- gaps/handoffs preserve both ledgers;
- accepted discard/reset clears both ledgers and restarts both ordinal bases;
- no event is fabricated without a previously mirrored submission;
- metadata publishes hardware timing schema 2 and bumped recorder versions.
- schema-1 output remains deliberately selectable for compatibility tests.
- STANDARD differential metadata validation accepts current production
  `6.38-s3k` / trace-schema 7 / hardware-schema 2 output while retaining an
  explicit load-only compatibility path for committed `6.37-s3k` /
  trace-schema 7 / hardware-schema 1 fixtures.

### Implementation

Update:

- `S3KRam.cs`
- `HardwareTimingEventEngine.cs`
- `S3KTraceMetadataWriter.cs`
- `S3KCompleteRunMetadataWriter.cs`
- standard and complete-run capture plumbing only where schema selection or
  reset/handoff requires it
- `S3KTraceDifferentialTests.cs`, so current `6.38-s3k` / schema 7 /
  hardware-schema 2 output validates both ledgers while committed
  `6.37-s3k` / schema 7 / hardware-schema 1 remains load-only and cannot be
  normalized into direct-authority differential success

Keep the existing source file unless separation materially improves clarity;
avoid new ignored files when unnecessary. Do not update the frozen Lua
recorder merely for parity; the maintained cross-language vectors are Java and
native C#.

If a new ignored harness file is unavoidable, add it with `git add -f` and
verify tracking through both `git ls-files tools/bizhawk-headless` and the
eventual `git show --stat`.

### Verification

```bash
cd tools/bizhawk-headless
./test.sh --filter HardwareTimingEventEngine --jobs 1
./test.sh --no-gates
```

Run `--no-gates` with all locally available ROM variables supplied as well as
without them, and treat any ROM-enabled runner/CLI failure as blocking. Record
pass/fail/skip counts. Do not publish or replace fixtures.

Reviewer checklist: ROM-derived lifecycle, count-based reconciliation, no
self-certifying expectations, exact C# 7.x compatibility.

## Task 6: Documentation, guards, and compatibility inventory

Update:

- the approved cross-game hardware-timing design;
- the S3K hardware-timing inventory;
- trace format/recorder documentation and known discrepancy/status files
  whose claims change;
- `CHANGELOG.md`;
- `README.md` release/change-log entry required for integration into
  `develop`;
- trace-frontier log only when a measured frontier moves or regresses.

Add a checked inventory of committed schema-1 traces that reach AIZ intro or
the ICZ act transition. Do not change their payloads. State that they remain
loadable but cannot certify those direct-count boundaries until a separately
approved schema-2 publication.

Run documentation/authority/fixture guards.

## Task 7: End-to-end verification and review

Before the end-to-end runs, restore the `LevelManager` source-size guard
without increasing its budget. Extract the self-contained implementation of
`findPatternOffset(...)` to a package-local `LevelPatternLocator`; keep the
existing public method as a thin delegate and add focused parity tests for
successful lookup, search order, coordinate conversion, and not-found
results. Run `TestArchitecturalSourceGuard` to prove `LevelManager` is at or
below 2500 effective lines. Do not change the pre-existing GameLoop or
AbstractPlayableSprite budgets/failures as part of this extraction.

Repair attributable stale harnesses before the full suite:

- make `TestLevelIterationHardwareTimingAdmissionOrder` prepare Kos work
  through the runtime-owned direct/module queue path instead of submitting a
  fake raw module job to `HardwareTimingService`;
- update `TestGameplayModeContextRewindRegistry` to assert the registered
  seamless-transition handoff adapter and the resulting ten-key registry.
- give AIZ fire-transition queue tests a deliberately scoped drain helper that
  invokes timing admission, direct-FIFO retirement, and module-parent
  coordination in production order; do not call it from unrelated intro or
  save-event tests.

Run each repaired test in isolation before repeating combined verification.

Resolve the independent-review blockers before repeating the full suite:

- add a game-neutral runtime-art coordinator contract created by `GameModule`
  from the session hardware-timing owner;
- make shared session/frame/service/object layers depend only on that contract,
  with concrete direct/module queues retained inside an S3K-local facade;
- route S3K zone event queue access through protected
  `Sonic3kZoneEvents` accessors and make the zone-event runtime-access guard
  green;
- replace the `Game.loadLevel(...DeferredLevelResourceTracker)` overload with
  a level-layer `DeferredLevelResourceLoader` provider implemented by
  `Sonic3k`;
- make module FIFO capacity represent unprepared physical parents rather than
  ready-but-unclaimed timing payloads, and prove repeated title-card/object-art
  producers do not resubmit pending handles;
- make the non-cycle-accurate live fallback decode the active standard-Kos
  direct child through its terminator in each admitted PRE (one physical head
  per PRE), while recorded authority preserves ROM-observed interrupt/bookmark
  completion timing; prove real AIZ title archives complete in bounded
  production scans and update rewind tests away from a false
  one-command-per-frame live assumption while retaining prepared/recorded
  snapshot coverage;
- reproduce and green `TestS3kHeadlessInLevelTitleCardProgression`,
  `TestS3kAiz1SkipHeadless`, and
  `TestTitleCardObjectExecution#titleCardLegacyPath_s3kAiz1`;
- repair `TestS3kObjectKosOwnerRewind` and
  `TestS3kResultsKosQueueRewind`, `TestS3kZoneKosRewind`, and AIZ save-event
  queue fixtures with session-backed neutral coordinator services and
  production multi-dispatch creation cadence;
- migrate every S3K object/event fixture that submits runtime Kos art,
  including HCZ event, WaterWall, LargeFan, MGZ drilling Robotnik, and LBZ
  FinalBoss1 coverage, away from bare `TestObjectServices` instances to the
  session-backed neutral coordinator fixture; production remains fail-closed
  when a coordinator is genuinely absent;
- make late-route shared-level headless fixtures drain their initial
  title/level runtime-art parents through production boundaries before
  teleporting directly into an object activation range, and warm up earlier
  neighboring art producers in route order before activating the target; do
  not enlarge the four-slot FIFO or suppress the object's fifth submission to
  accommodate an impossible simultaneous first dispatch;
- restore the fresh-level initial-`Process_Sprites` token before post-load
  assembly and discard it during gameplay reset; prove title release consumes
  only the setup pass and teardown cannot leak the token;
- run `TestArchUnitRules` and `TestZoneEventRuntimeAccessGuard`, requiring no
  feature-attributable violations;
- remove task-local `.superpowers/sdd/...` reports introduced by this feature;
  repository engineering reports remain under
  `docs/architecture/validation/`.

### Java

Discover actual ROM filenames at repository root and supply the verified S3K
path through `-Ds3k.rom.path=...`.

Run focused queue, timing, AIZ, ICZ, rewind, guard, and trace tests, followed
by:

```bash
mvn "-Dtest=TestS3kIczCompleteRunTraceReplay" test
mvn "-Dtest=TestCommittedHardwareTimingFixtures" test
mvn test
```

### Native recorder

Run:

```bash
cd tools/bizhawk-headless
./test.sh --no-gates
```

If BizHawk and all ROMs are available, run the full suite with required
environment variables. Treat fixture-differential failures caused solely by
schema-2 output as an unresolved publication gate, not permission to replace
fixtures.

### Independent review

Review the completed diff against the design for:

- PRE/POST visibility and same-frame ordering;
- shared physical FIFO and parent-child dependency;
- per-kind authority isolation;
- rewind and seamless ICZ ownership;
- recorder correctness independent of fixture output;
- documentation and commit-policy obligations.

Fix all blocking findings and repeat review until green.

## Task 8: Publication candidate and approval gate

If any affected AIZ/ICZ fixture or differential gate fails because it lacks
schema-2 direct events:

1. Capture selected AIZ/ICZ publication candidates with the reviewed native
   recorder into harness `.scratch/`, never into committed fixtures.
2. Independently review recorder semantics against the named ROM lifecycle and
   the completed behavioral/unit tests.
3. Freeze candidate SHA-256 digests, byte lengths, metadata/recorder versions,
   segment and event inventories, boundary ordering, ordinal/fingerprint
   ranges, and categorise every byte delta mechanically against a named cause.
4. Measure the trace frontiers before candidate installation.
5. Present the exact candidate/evidence to the user and stop for explicit
   publication approval.

After approval only:

1. Install the exact reviewed native bytes without hand edits.
2. Pin frozen literal publication expectations.
3. Re-run native differential gates, fixture loader/schema/compression/reference
   guards, and Java replay tests.
4. Measure after-publication frontiers and update
   `docs/status/trace-frontier-log.md` with command, context, failures, error
   count, and first-error field/frame.
5. Independently review the installed byte deltas and publication evidence.

Without approval, do not modify `src/test/resources/traces/`, merge, push, or
claim completion if the implementation introduces attributable fixture/gate
failures.

## Task 8.5: Resolve the exposed AIZ module-buffer identity blocker

Do not change approved fixture bytes or recorder output.

### Tests first

Extend `TestS3kKosStructuralSequence` and/or
`TestS3kKosDecompressionQueue` with the exact AIZ intro-plane first-child
vector:

- source `0x382626`;
- compressed length `1894`;
- destination `0xFFFFD000`;
- decoded length `4096`;
- variant `kosinski`;
- module count `1`;
- fingerprint
  `sha256:c381a8f75b41d3e2d1e52fb90ae8a5c269b1daeb88dd198bd8fb3d07d3703a7b`.

The test must fail against the current `0xFFFFD400` production constant and
must obtain the handle through the real `S3kKosModuleQueue` child path, not by
calling the fingerprint helper with a hand-built expected tuple.

Extend native `HardwareTimingEventEngineTests` with an independently literal
AIZ child tuple/fingerprint case. Keep the native scanner differential as the
ROM-backed integration proof; the native unit need not be RED because its
implementation already observes the correct RAM longword.

### Implementation

Change only `S3kKosRamDestinations.KOS_DECOMP_BUFFER` from `0xFFFFD400` to the
ROM-owned `0xFFFFD000`. Audit callers to confirm the constant is used only as
the shared module-child RAM destination. Do not add AIZ/ICZ branches, trace
conditions, alternate fingerprints, or compatibility normalization.

### Verification

Run:

```bash
mvn "-Dtest=TestS3kKosDecompressionQueue,TestS3kKosModuleQueue,TestS3kKosStructuralSequence,TestS3kHardwareTimingReplay" \
  "-Ds3k.rom.path=<verified-s3k-rom>" test
mvn "-Dtest=TestS3kAizTraceReplay" "-Ds3k.rom.path=<verified-s3k-rom>" test
mvn "-Dtest=TestS3kAizCompleteRunTraceReplay" "-Ds3k.rom.path=<verified-s3k-rom>" test
BIZHAWK_HOME=<verified-bizhawk> S3K_ROM_PATH=<verified-s3k-rom> \
  tools/bizhawk-headless/test.sh --filter \
  "HardwareTimingEventEngine AIZ module child identity" --jobs 1
BIZHAWK_HOME=<verified-bizhawk> S3K_ROM_PATH=<verified-s3k-rom> \
  tools/bizhawk-headless/test.sh --filter \
  "S3KTraceDifferential native capture matches canonical AIZ timing stream" --jobs 1
mvn "-Dtest=TestS3kIczCompleteRunTraceReplay" "-Ds3k.rom.path=<verified-s3k-rom>" test
```

The AIZ replays must move beyond direct ordinal 8. Any later divergence is
recorded as the new frontier rather than hidden by fixture edits.

ICZ is diagnosed independently before this production edit. Its module
ordinal 158 matches `9d76...`, but its child was native pre-segment work and
has no ICZ-local direct edge; standalone replay recreates that child at direct
ordinal 161 after boot. Leave this bootstrap gap unresolved in Task 8.5 and
record its exact frontier. Any solution requires a separate reviewed
structural-run/prefix design that preserves the hardware-authority isolation
contract.

## Task 8.6: Close the module-child recorder observability gap

Do not overwrite the installed approved fixtures or update publication
literals while implementing this task.

### Tests first

Extend `HardwareTimingEventEngineTests` with RED cases that invoke a new
submission-only direct observation between ordinary frame-end samples:

- a module child enqueued after a POST observation and retired at the next PRE
  is mirrored before retirement and emits exactly one direct PRE event;
- the equivalent VINT-enqueued child has the same identity and lifecycle;
- a callback with an empty logical direct ledger, including after an empty
  boot frame-end sample, mirrors all currently occupied entries in FIFO order,
  emits/stages no retirement, and later PRE completions preserve those
  bootstrap ordinals across gap/reset coverage;
- submission observation itself writes no event, retires no prior entry, and
  does not mutate the prior busy-state proof;
- `[A] -> [C]` retains and stages `A` logically while the new child `C`
  receives the next ordinal;
- `[A,B] -> [B,C]` stages only `A`, verifies the retained `B` suffix/prefix
  identity, and appends `C` at the physical tail;
- an identical old-head/new-child replacement still stages the old ordinal
  and allocates a distinct child ordinal;
- loss of multiple prior heads or mutation of a retained tail fails closed;
- the next frame-end PRE emits each staged completion once in canonical order
  before reconciling the then-current physical snapshot, with no double
  retirement or unexplained loss;
- a callback-observed direct PRE edge sorts before a same-frame module POST
  edge;
- work observed and retired wholly while the writer is null fabricates no
  represented event, while later ordinals remain canonical across the gap;
- `Reset()` clears callback-observed direct/module ledgers and restarts both
  ordinal bases without duplicating the host registration;
- malformed count, mutation, or non-append physical state at the callback
  fails loudly.

Add capture-runner tests with a callback-capable fake host. Prove both standard
and complete-run runners register one synchronous execute callback at ROM PC
`0x001B46`, keep it active across unexported gaps, and dispose it at capture
end. Add a pinned-runtime `GpgxHost` integration test that verifies the
registered callback uses BizHawk scope `M68K BUS` and sees the direct FIFO
count/entry after `Queue_Kos` returns.

Run the focused native tests and record their RED output before implementation.

### Implementation

Expose an address-filtered execute-callback registration contract from
`IGpgxHost`; implement it in `GpgxHost` with
`IDebuggable.MemoryCallbacks`, `MemoryCallbackType.Execute`, scope
`M68K BUS`, and deterministic disposal/removal.

Separate the direct logical authority ledger from the latest physical FIFO
snapshot. Add
`HardwareTimingEventEngine.ObserveDirectSubmissions(IGpgxHost)` as an
observation/staging operation. It reads
`Kos_decomp_queue_count & 0x7FFF`, verifies capacity and every retained
physical entry, and handles exactly one enqueue at the proven callback:

- if the logical direct ledger is empty, mirror all currently occupied slots
  in order and stage/emit no retirement, even if an earlier frame-end sample
  established an empty physical snapshot;
- when current count is prior count plus one, require the full prior physical
  prefix and append the new child tail;
- when current count equals prior count, require the prior suffix after one
  head to match the current prefix, retain/stage that old logical head, and
  append the new child tail. This admits `[A,B] -> [B,C]` and assigns a fresh
  ordinal for identical `[A] -> [A]`;
- reject loss of more than one prior head, retained-tail mutation, duplicate
  staging, any other count delta, or any non-enqueue shape.

The callback must not:

- remove or complete a mirrored submission;
- change `priorDirectBusy`;
- write an authority event; or
- inspect a trace row, writer, zone, or game-mode predicate.

In both S3K capture runners, register that observer at exact S&K PC
`0x001B46` before the first advance and dispose it only after capture
finalization. Keep existing `ObserveFrameEnd` as the only direct retirement
and event-emission path. It emits/removes staged PRE retirements in ordinal
order before reconciling the current physical snapshot, and prevents that
same logical entry from retiring twice. A PRE completion is legal only for a
job already mirrored either by a prior frame-end sample or the execute
callback.

Do not hook the generic `Queue_Kos` return, the FIFO-count RAM write, or every
direct submission. Do not add fixture-specific fingerprints, synthesize
events from the module ledger, or weaken unexplained-loss failures.

Amend both `tools/bizhawk-headless/AGENTS.md` and its mirrored `CLAUDE.md`
section. Permit only the exact address-filtered `0x001B46` hardware-timing
submission observer, with a strongly rooted delegate and deterministic
unregistration. Keep the ban on diagnostic callback families, memory-write
hooks, hook-derived sync/completion authority, and callback event emission.
Stage both mirrored agent docs together and set the `Agent-Docs` trailer to
`updated`.

### Verification and renewed publication gate

Run:

```bash
BIZHAWK_HOME=<verified-bizhawk> S3K_ROM_PATH=<verified-s3k-rom> \
  tools/bizhawk-headless/test.sh --filter HardwareTimingEventEngine --jobs 1
BIZHAWK_HOME=<verified-bizhawk> S3K_ROM_PATH=<verified-s3k-rom> \
  tools/bizhawk-headless/test.sh --filter GpgxHost --jobs 1
BIZHAWK_HOME=<verified-bizhawk> S3K_ROM_PATH=<verified-s3k-rom> \
  tools/bizhawk-headless/test.sh --no-gates
```

Recapture the affected standard AIZ and complete-run candidates into new,
uniquely named `.scratch/task8-candidates-observability-*` directories. Run
the exact native differential gates against those isolated outputs. If the
complete-run capture changes another segment's timing stream, include that
segment in the candidate set; do not copy any new file into
`src/test/resources/traces/`.

Freeze and independently review:

- all four file hashes and byte lengths per candidate;
- recorder/trace/hardware schema versions;
- direct/module event counts, ordinal and fingerprint ranges, boundaries, and
  canonical same-frame order;
- the new `4767...` child edge and its later PRE retirement;
- every byte delta against the installed approved candidate, mechanically
  classified as callback-observed direct authority or a resulting metadata
  hash/version change;
- replay frontiers when run from isolated candidate paths.

If any corrected candidate differs from the installed approved bytes, keep it
isolated. The committed fixture tree and publication literals remain at (or
are restored exactly to) the currently installed, explicitly approved
schema-2 baseline while the corrected candidates are reviewed in scratch.
Present the exact corrected candidates and stop. Install no replacement bytes
without renewed explicit user approval.

## Task 8.7: Close corrected-candidate production-owner gaps

Keep all corrected recorder outputs isolated. These are engine-only fixes and
must not trigger another capture or fixture/publication edit.

### Tests first

Add a ROM-backed AIZ structural test that invokes the fire-transition queue
owner and freezes the exact five-job order:

1. direct AIZ2 blocks (level-load-block entry `+16`) to `RAM_START`;
2. direct primary chunks (entry `+8`) to `BLOCK_TABLE`;
3. direct secondary chunks (entry `+12`) to `BLOCK_TABLE + 0xAB8`;
4. primary KosM art (entry `+0`) to tile `0x000`;
5. secondary KosM art (entry `+4`) to tile `0x1FC`.

Freeze the first three direct fingerprints as `1cea...`, `6ab93...`, and
`3b4e...`, and prove the first module child remains `086520...` after them.
Add lifecycle/rewind assertions for all three direct ordinals: reset to `-1`,
rebind by direct kind/ordinal, derived facade discard, and claim before the
existing module claims once the transition is ready.

Add provider-profile tests that arm from `onTitleCardArtRetired()` and freeze
the exact ROM order for:

- MGZ1: Spiker `0x36E0C4`/`0x530`, MGZMiniboss
  `0x36B02C`/`0x54F`, MGZEndBossDebris `0x36D572`/`0x570`;
- MGZ2: Spiker `0x36E0C4`/`0x530`, Mantis
  `0x36E2D6`/`0x54F`;
- CNZ: Sparkle `0x3700CA`/`0x524`, Batbot
  `0x3703EC`/`0x552`, ClamerShot `0x370058`/`0x570`,
  CNZBalloon `0x37060E`/`0x574`.

The tests must prove no submission occurs before title-card retirement and
that existing provider capture/restore preserves pending entries, handle
ordinals, and the armed flag. Record focused RED output before implementation.

### Implementation

In `Sonic3kAIZEvents`, add one transient direct queue facade, three transient
direct handles, and three scalar ordinals. `queueAct2KosArt()` reads all five
sources from the same AIZ2 level-load-block entry and queues the three direct
jobs before the two existing module parents. Extend initialization,
rewind-rebind, derived-facade discard, and ready-path claiming for the new
state. Retain the existing mutation pipeline as the terrain consumer; do not
write layout data from timing payloads.

Add named S&K-half source/tile constants for the missing MGZ/CNZ enemy
profiles. Extend only
`Sonic3kObjectArtProvider.scheduleEnemyKosArt(zone, act)` with the ROM lists
above. Reuse the existing title-retirement arm, queue facade, pending entries,
claims, capture, and restore. Do not create work from a trace, arm early, or
change shared timing behavior.

Do not alter the correct module coordinator/POST observer order. Do not add
timing exceptions for STANDARD AIZ StarPost, later HCZ Blastoid reload, or
later ICZ StarPost; record those as separate gameplay-owner frontiers.

### Verification

Run the focused Java structural, rewind, AIZ event, provider, queue, timing
authority, and architecture guards with the verified S3K ROM. Then run the
corrected candidate overlay with the Surefire fork explicitly rooted at that
overlay:

```bash
mvn -Dsurefire.argLine=-Duser.dir=<candidate-overlay> \
  "-Ds3k.rom.path=<verified-s3k-rom>" \
  "-Dtest=TestS3kAizCompleteRunTraceReplay,\
TestS3kMgzCompleteRunTraceReplay,TestS3kCnzCompleteRunTraceReplay" test
```

Freeze the new first timing frontier for each replay. Success means AIZ
advances through ordinary direct ordinals 26-28 and MGZ/CNZ admit their
title-retired enemy-art groups; it does not require unrelated physics
frontiers to become green. Rerun STANDARD AIZ, HCZ, and ICZ only to confirm
their explicitly deferred gameplay-owner stops did not regress. Do not
recapture: production-only changes cannot change the already captured native
bytes.

## Task 9: Integration

Following the root `AGENTS.md`:

1. Fetch and fast-forward the main workspace `develop` without overwriting its
   unrelated dirty files.
2. Record the full-suite baseline on updated `develop`.
3. Rebase/merge updated `develop` into the worktree and repeat focused/full
   verification.
4. Commit with required trailers and no `--no-verify`.
5. Merge into main-workspace `develop`, including the staged README update.
6. Run the merged full suite and compare against baseline.
7. Push only main-workspace `develop`.
8. Verify the worktree is clean/merged, remove it, delete its fully merged local
   branch, and prune metadata.

Integration proceeds only after either all attributable tests/gates are green
without fixture changes or an explicitly approved publication restores them.
Do not merge/push a reduced delivery unless the user separately authorizes that
exact unresolved state, and never claim it as completed delivery.
