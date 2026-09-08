# Sound-driver roadmap completion implementation plan

> **2026-09-05 S3K follow-up:** the diagnostic oracle now models retail's
> dispatch-time `33h` ring alternation. Production still needs one source-owned
> three-slot S3K mailbox service, including 1-up mailbox clearing and boot-only
> ring-speaker reset. A partial one-request service was rejected because it
> would retain suppressed sounds and change music/secondary ordering.

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> `superpowers:subagent-driven-development` to implement this plan task-by-task. Every behavior
> change follows test-first development and receives an independent task review.

**Goal:** Connect TraceChaser's native S2/S3K reference producers to OpenGGF's existing
complete-run consumer, add an independent production-owned OpenGGF replay producer, and then
advance sound-driver parity by first divergence without weakening the proven S1 lanes.

**Architecture:** TraceChaser/BizHawk remains the sole emulator-facing reference producer.
OpenGGF validates and projects that raw evidence, independently drives itself from the same
verified ROM and BK2 input, compares only explicitly declared semantic layers, and reports
missing reference authority as `REFERENCE_LIMITATION`. Shared code owns identity, capture,
comparison, and production replay mechanics; typed game profiles own ROM/movie identities,
state normalization, raw projection, and source-specific behavior.

**Tech stack:** Java 21, Maven/JUnit 5, TraceChaser C# native headless harness, BizHawk 2.11
GPGX, Lua 5.4 guards, gzip JSONL canonical capture store.

**Spec:**
`docs/architecture/designs/audio/2026-08-31-sound-driver-production-owned-validation-design.md`

## Global constraints

- Work only in isolated worktrees; never switch the main OpenGGF workspace branch.
- Accuracy comes from the shipped ROM/disassembly path. Preserve `FixBugs = 0`, `fixBugs = 0`,
  and `fix_sndbugs = 0` behavior and cite the owning source routine in behavior fixes.
- TraceChaser output is comparison-only. It may not hydrate, schedule, or select OpenGGF
  gameplay/audio behavior.
- The OpenGGF producer may consume only the exact ROM, current/previous BK2 controller rows,
  normal startup/configuration, strict manifest identity/segment inventory, and capture bounds.
- Do not consume `TraceData`, `TraceSessionLauncher`, `TraceReplaySessionBootstrap`,
  `TraceReplayDrive`, physics/auxiliary rows, hardware timing, reference requests/mailboxes,
  fixture coordinates, or direct level-load helpers from the authenticated OpenGGF producer.
- Reuse TraceChaser's reviewed buffered GPGX audio observer and closed service graph. Do not add
  caller-configurable execute/memory hooks or infer requests from state, service, or chip output.
- S2 uses profile `s2_rev01_complete_emeralds.v1` and comparison interval `[769,259590)`.
- S3K uses profile `s3k_locked_on_knuckles_superemeralds.v1` and comparison interval
  `[810,434417)`. The Sonic/Tails 5,400-row diagnostic remains a separate identity.
- A missing request/admission observation is `REFERENCE_LIMITATION`, never equal empty arrays,
  inferred parity, or `MATCH`.
- Comparison stops at the first divergence and never realigns.
- The S1 GHZ 14,690-tick and S1 sound-test 1,967-tick lanes are regression gates; their current
  evidence labels remain bounded driver-core/fixture-assisted rather than production-owned.
- Use JDK 21, `LUA_BIN=lua5.4`, absolute verified ROM paths, and check ROM-gated skip counts.
- Maven output stays in each worktree's `target/`. Raw captures use an explicit external task
  archive and are never committed uncompressed under trace resources.
- Human listening remains human-owned; agents only prepare the queue.
- Keep `docs/status/audio-frontier-log.md` current whenever a frontier moves or regresses.
- Follow repository trailers and documentation obligations; never use `--no-verify`.

---

## Requirements

### Goals

1. Preserve a single six-stage roadmap: reverse-engineer, specify, compare, catalogue,
   implement, validate/publish.
2. Make semantic coverage explicit in every complete-run profile and capture.
3. Expose stable TraceChaser complete-audio commands for the existing S2/S3K native runners.
4. Project validated raw reference services/state/chip evidence into OpenGGF's canonical store
   without inventing request/admission records.
5. Drive OpenGGF naturally from power-on using one BK2 row and one outer-audio presentation per
   outer frame.
6. Bind the reserved S2/S3K fixed producer classes only after identities and capability proofs
   are pinned.
7. Execute authenticated comparisons, then resolve each first divergence at its ROM-owned
   implementation boundary.

### Non-goals

- A second emulator/reference framework inside OpenGGF.
- Replaying fixture requests, S2 speed coordinates, or S3K mailbox bytes into the engine.
- Claiming request parity from post-consumption Z80 state or audible/chip consequences.
- Replacing complete-run evidence with fresh-level scenarios.
- Treating analog mix or subjective quality as driver-state equality.

### Acceptance criteria

- Incompatible layer inventories fail before semantic comparison.
- Equal compared layers plus any unavailable layer return `REFERENCE_LIMITATION`; only an
  all-compared equal capture returns `MATCH`.
- S2/S3K raw streams without approved request events emit zero canonical requests/decisions.
- Stable TraceChaser CLI invocations publish create-new raw files only after complete capture.
- The production runner proves row/step/presentation/cursor cardinality and observer cleanup.
- Authority guards reject every forbidden dependency named in the spec.
- Fixed registry bindings load in a fresh CLI JVM and carry pinned runtime/observer/capability
  identities.
- Complete comparisons report a first semantic divergence or a layer-scoped limitation; they do
  not silently skip to the old EHZ or frame-242 diagnostic frontier.
- Focused, ordinary, and fresh-JVM guard suites introduce no new regression.

### Risks and conservative rulings

- The canonical store has no installed complete-run captures. The prerequisite therefore adopts
  `complete_run_audio.v2` and rejects v1 rather than accepting metadata without explicit producer
  observation and shared comparison inventories.
- The initial integration deliberately retained service/state/chip-only authority. The
  post-Task8 Sonic 2 request-authority prerequisite below now requires the closed ABI 4
  action-7/managed-register extension before the S2 comparison may advance beyond its request
  frontier. That extension is planned, not implemented or authoritative.
- If natural power-on replay cannot traverse a mode or special stage, the run reports an explicit
  product/route feasibility gap. It does not use trace pacing or direct loading.

## Exploration synthesis

- `CompleteRunAudioTrace.Metadata` lacks layer identity, while
  `CompleteRunAudioComparator` unconditionally compares requests/decisions and their terminal
  counts. This must change before any partial-authority reference can publish.
- `CompleteRunAudioProducerRegistry` already reserves the four correct S2/S3K producer class
  names. Both profiles intentionally expose `UnavailableProducerBinding`.
- `S2CompleteRunReferenceRawAdapter` and `S3kCompleteRunReferenceRawAdapter` already provide
  strict transactional scans; game-owned decoders and normalizers already exist.
- TraceChaser's `S2CompleteAudioCaptureRunner` and `S3kCompleteAudioCaptureRunner` already own
  verified native capture, but the public program has no stable complete-audio raw command.
- OpenGGF already has the required input and observation primitives:
  `Bk2MovieLoader`, `RecordedInputSnapshots`, `InputHandler.setLogicalOverride`,
  `AudioRequestObserver`, `AudioAdmissionObserver`, `SmpsDriverServiceObserver`, and
  `ChipWriteObserver`.
- Existing headless helpers are unsuitable because they direct-load levels or use trace
  bootstrap. `Engine.initializeGame()` and `Engine.presentOuterAudioFrame(...)` are the reusable
  production owners; they need the smallest tooling-visible headless seam.

## Architecture decision

The data flow is:

```text
verified ROM + BK2 ──> TraceChaser/BizHawk ──> validated raw reference
        │                                           │
        └──────────> OpenGGF production replay      │
                             │                      │
                             └──> canonical engine  │
                                                    v
                          OpenGGF canonical projection/comparator
                                      │
                         MATCH / first divergence /
                           REFERENCE_LIMITATION
```

TraceChaser owns capture correctness and raw publication. OpenGGF owns canonical publication,
independent execution, comparison, and acceptance. Both producers share identity inputs, never
runtime state. Rollback is removal of producer bindings: the existing unavailable profiles and
diagnostic tools remain usable without changing gameplay behavior.

## Current execution checkpoint (2026-09-01)

- Tasks 1-6 have production implementations on
  `feature/ai-sound-driver-roadmap-completion`: comparison-layer schema, closed TraceChaser
  producer command, reference projectors, BK2 cursor, production headless runner/observer lease,
  and v2 OpenGGF capture reducer. Focused review fixes and authority guards are integrated.
- Task 7 remains deliberately fail-closed where independent artifact attestation or producer
  identity is unavailable. The rejected unbound S3K Java v2 consumer was removed from production
  bytecode; a green synthetic path is not publication authority.
- S2 first-divergence work fixed source-owned admission ownership and PSG3 hardware writes. S1
  music remains `MATCH (14,690 ticks)` and S1 SFX remains
  `MATCH (1,967 ticks, 8 dispatches)`.
- S3K stop/boot exploration exposed a runtime architecture defect: presentation currently models
  several independently rendered YM2612/PSG pairs, so a global ROM command cannot have one stable
  physical owner. The reviewed decision is
  `docs/architecture/designs/audio/2026-09-01-session-owned-smps-physical-device-design.md`.
- Before Task 8 can make a source-correct S3K global-command change, execute
  `docs/architecture/plans/audio/2026-09-01-session-owned-smps-physical-device-implementation-plan.md`
  Tasks 1-8. That subplan is an architecture prerequisite, not a replacement for the six-stage
  producer/consumer roadmap. Its Task 9 returns here to authenticated first-divergence work.
- The physical-device subplan now has an atomic bundle implementation, but its terminal status
  remains `REFERENCE_LIMITATION` / `FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE`.
  Those publication mechanics neither supply fresh native authority nor expose the missing Sonic 2
  request stream. Before Task 8 can advance the S2 tick-210 request frontier, execute Task 8A below.
- The bounded S3K service-128 diagnostic remains the distinct Sonic/Tails `[0,5400)` identity;
  it is not the Knuckles `[810,434417)` complete-run lane. Task 8B below plans its missing
  pre-consumption request authority after Task 8A. Task 8B does not replace, broaden, or relabel
  the Knuckles production profile.

## Feature design

- `ComparisonLayerInventory` declares `ROW_LAG`, `REQUESTS`, `DECISIONS`, `SERVICES`, `STATE`,
  `OWNERSHIP`, `LIFECYCLE`, `FRAME_CHIP_EVENTS`, `BOUNDARY_CHIP_STATE`, and `CUTOFF_FRONTIER`
  in canonical enum order. Each producer separately pins the same ten-layer observed/unobserved
  inventory.
- Each comparison layer is `COMPARED` with no reason or `UNAVAILABLE` with a nonblank reason;
  `COMPARED` requires both producer inventories observed, but observed evidence may deliberately
  remain unavailable for equality.
- Decisions cannot be compared when requests are unavailable.
- A producer may claim ownership observed only when decision and lifecycle carriers are also
  observed; ownership equality uses their ordered flattened owner-transition projection rather
  than unavailable decision/lifecycle record grouping.
- Both inventories are mandatory and frozen into profile/capture metadata and source identity;
  no constructor or profile default may synthesize all-observed/all-compared claims.
- Comparator skips unavailable fields/counts but returns `REFERENCE_LIMITATION`, not `MATCH`,
  after all compared layers agree.
- Nullable frame/boundary fields mean unobserved; non-null empty means observed-empty. Frame owns
  lag, requests, decisions, services, post-row state, and frame chip events. `DriverService` owns
  only service semantics/lifetime/ancestry. Admission decisions remain frame-owned because
  `AudioAdmissionObserver` fires in `AbstractSmpsAudioBackend.playSfxSmps`/`evaluateAdmission`
  before and independently of `SmpsDriver` service callbacks.
- Baseline/cutoff service topology, boundary chip state/latches, normalized state, and owners are
  independently nullable within mandatory envelopes. Buffered-native authentication is validated
  whenever declared, even when its corresponding semantic layer is unavailable. Native topology
  and global chip/latch projections prove each present semantic boundary component independently.
- `Bk2InputCursor` indexes `Bk2Movie.getFrames()` directly, publishes current/previous logical
  input before `GameLoop.step()`, advances after audio presentation, and fails on exhaustion.
- A scoped observer lease installs append-only observers before startup and removes them in
  `finally`, refusing conflicting ownership.
- Game-owned reference projectors decode only approved raw events and state. Native TraceChaser
  service kinds are not assumed equivalent to OpenGGF's SMPS service callbacks; exact per-game
  projection tests must prove a shared semantic layer before its status becomes `COMPARED`.
  Missing semantic owners remain unavailable.
- Fixed producer classes validate producer kind/profile/paths, invoke their owned runner, write a
  private transaction, and expose no replacement/registration seam.

---

## Implementation plan

### Task 1: Layer-scoped capture identity and comparison

**Files:**

- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioTrace.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfile.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProfiles.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioJson.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioComparator.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioReport.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunAudioProfile.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunAudioProfile.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioTrace.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureStore.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioComparator.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCli.java`

**Interfaces:**

- Produces: canonical `complete_run_audio.v2`; `ComparisonLayer`, `ComparisonLayerStatus`,
  `ComparisonLayerClaim`, `ComparisonLayerInventory`, `ProducerObservationClaim`,
  `ProducerObservationInventory`, both mandatory profile inventories, strict nullable evidence
  shapes, and `CompleteRunAudioReport.Kind.REFERENCE_LIMITATION`.
- Consumes: existing strict metadata/profile/store validation.

- [x] Write RED constructor tests for duplicate, missing, reordered, and invalid dependent claims;
  add a metadata round-trip test with literal canonical JSON.
- [x] Run
  `mvn -Dmse=off "-Dtest=TestCompleteRunAudioTrace,TestCompleteRunAudioCaptureStore" test -B`
  and confirm failures are caused by the absent inventory contract.
- [x] Add immutable shared-comparison and producer-observation inventory types. Initially compare
  only `FRAME_CHIP_EVENTS` for S2/S3K; every other layer is an explicit limitation for both
  producers. S1's uninstalled complete-run producers claim no observed layers, while its legacy
  music/SFX oracle gates remain unchanged.
- [x] Freeze both inventories in `CompleteRunAudioProfiles.FrozenProfile`, append the selected
  producer inventory to strict v2 metadata JSON,
  and reject metadata whose inventory differs from its registered profile.
- [x] Write RED comparator tests proving engine requests do not mismatch an unavailable reference
  request layer, equal empty arrays cannot produce `MATCH`, incompatible inventories fail, and a
  real compared-layer mismatch still wins over the limitation result.
- [x] Run
  `mvn -Dmse=off "-Dtest=TestCompleteRunAudioComparator,TestCompleteRunAudioCli" test -B`
  and confirm the missing behavior fails.
- [x] Make cross-producer frame/boundary comparisons conditional on `COMPARED`, while retaining
  producer-local shape/profile/native authentication for observed-but-not-compared evidence. Return
  `REFERENCE_LIMITATION` only when every compared layer is equal; preserve exact first-divergence
  behavior otherwise. Emit inventory through existing source metadata in JSON/text reports.
- [x] Run all focused classes, existing S1/S2/S3K fixture profile tests, legacy S1 oracle gates,
  the ordinary relevant suite, and fresh-JVM guards.
- [x] Commit with repository trailers as `feat(audio): declare complete-run comparison layers`.

### Task 2: Stable TraceChaser complete-audio producer command

**Files (TraceChaser repository/worktree):**

- Modify: `bizhawk-headless/src/Program.cs`
- Modify: `bizhawk-headless/src/Recording/S2CompleteAudioCaptureRunner.cs` only if a public command
  wrapper cannot call the existing pinned method without widening internal behavior.
- Modify: `bizhawk-headless/src/Recording/S3kCompleteAudioCaptureRunner.cs` under the same rule.
- Modify: `bizhawk-headless/tests/TraceCliTests.cs`
- Create: `bizhawk-headless/run-complete-audio.sh`
- Test: `bizhawk-headless/tests/S2CompleteAudioCaptureRunnerTests.cs`
- Test: `bizhawk-headless/tests/S3kCompleteAudioCaptureRunnerTests.cs`
- Modify: the TraceChaser capture guide owning command-line publication.
- Modify in OpenGGF after the TraceChaser commit: `tools/tracechaser` gitlink.

**Interfaces:**

- Produces stable commands equivalent to S2:
  `--complete-audio-game s2 --rom <absolute> --movie <absolute>
  --service-manifest <absolute> --capability <absolute> --output <create-new-absolute-file>`;
  and S3K:
  `--complete-audio-game s3k --rom <absolute> --movie <absolute>
  --service-manifest <absolute> --output <create-new-absolute-file>`.
  S2 requires `--capability`; S3K forbids it because its pinned runner/profile
  has no capability input.
  These are TraceChaser's observer service manifest and capability fixture, not OpenGGF's
  complete-run run manifest. OpenGGF retains the latter separately for canonical fixture/segment
  identity.
- Consumes the existing `CaptureRawPinned(...)` methods and raw sinks without changing their ABI,
  observer graph, service manifest, identity, bounds, or cutoff rules.
- Produces one dedicated Linux launcher below the TraceChaser root. It accepts only the closed
  complete-audio argv, sources the pinned environment, unsets `DISPLAY`, and executes the fixed
  built assembly through Mono. It does not accept the generic trace wrapper's repository/fixture
  roots and does not invoke a shell command string supplied by OpenGGF.

- [ ] Create an isolated TraceChaser feature worktree/branch from its pinned commit; do not switch
  the checkout used by the OpenGGF main workspace.
- [ ] Write RED argument/publication tests for exact game selection, absolute existing ROM/movie/
  service-manifest/capability inputs, create-new output, unknown options, short capture failure,
  and no partial destination after failure.
- [ ] Run the TraceChaser filtered argument/runner tests and confirm the command is absent.
- [ ] Add one closed command branch selecting only the two compiled runner methods. Stage output
  beside the destination, close and validate capture, then atomically create the destination;
  never overwrite an existing file.
- [ ] Dispatch complete-audio before the generic trace boundary and add the dedicated launcher;
  prove at Main level that generic boundary arguments remain rejected by the closed command and
  that the launcher preserves the exact argv.
- [ ] Run `bizhawk-headless/test.sh --jobs 1 --filter 'TraceCliTests'`, then the S2 and S3K
  complete-audio runner/raw-sink filters.
- [ ] Commit and independently review the TraceChaser change; update the OpenGGF submodule pointer
  only to that reviewed commit.

### Task 3: OpenGGF reference-process boundary and transactional projectors

**Files:**

- Create: `src/main/java/com/openggf/tools/audio/completerun/TraceChaserAudioProcess.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunReferenceProjector.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunReferenceProducer.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunReferenceProjector.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunReferenceProducer.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestTraceChaserAudioProcess.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/s2/TestS2CompleteRunReferenceProducer.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kCompleteRunReferenceProducer.java`

**Interfaces:**

- Consumes: `CompleteRunAudioProducer.Request`, Task 2's fixed command, raw adapters, state
  decoders/normalizers, and `CompleteRunAudioCaptureStore.writeNew(...)`.
- Produces: transactionally staged canonical reference records for state/chip/cutoff layers and any service/ownership/
  lifecycle layer whose exact native-to-canonical equivalence is proven by the game-owned
  projector tests; zero request/decision records under the current observer graph. Authenticated
  canonical store publication remains fail-closed until Task 7 installs exact reference runtime
  identities, observer identities/proofs, and capability summaries.

- [ ] Write RED process tests using a deterministic fake executable that records argv and creates
  or fails a raw file. Treat `Request.referenceHome()` as the pinned TraceChaser root and assert
  deterministic resolution of
  `bizhawk-headless/fixtures/gpgx-audio-service-manifests-v1.json` for both games and
  `bizhawk-headless/fixtures/gpgx-audio-capability-v1.json` for S2 only, exact absolute paths,
  no shell interpretation, bounded stderr, nonzero exit propagation, and cleanup of uncommitted
  output.
- [ ] Implement `ProcessBuilder` invocation with an explicit argv list and Task 2's closed game
  selector. Resolve the service manifest and dedicated complete-audio launcher only from the canonical fixed paths
  below `Request.referenceHome()` for both games; resolve and pass the capability fixture only
  for S2. Require ordinary non-symlink files and let TraceChaser enforce their pinned content
  hashes. OpenGGF's `Request.runManifest()` remains separate and is used only for canonical
  fixture/segment identity. Accept no caller-provided executable arguments beyond the typed
  request fields.
- [ ] Write RED projector tests from hand-authored minimal valid raw prefixes: state/chip/cutoff
  events become canonical records in source order; raw streams without approved request events
  produce no `Request`/`Decision`; unknown semantic events abort publication. For every native
  service kind, either prove a source-cited mapping to an OpenGGF service/ownership/lifecycle
  record with literal expected coordinates and order, or keep that layer unavailable.
- [ ] Implement each adapter `Sink` as a private staged transaction. Decode/normalize driver state
  through the existing game-owned classes and write canonical records only on adapter `commit()`.
- [ ] Write RED direct producer tests for wrong producer kind/profile/ROM/BK2/manifest/
  reference-home, pre-existing output, subprocess failure, adapter failure, and fail-closed
  authentication while the profile binding is unavailable. Exercise projector/store mechanics
  only with explicitly test-only synthetic metadata; never publish production captures with a
  placeholder identity. Move successful direct producer, registry, and CLI publication to Task 7.
- [ ] Implement the two reserved fixed reference producers and run their focused tests plus both
  existing raw-adapter suites.
- [ ] Commit as `feat(audio): consume TraceChaser complete-run references`.

### Task 4: Production BK2 cursor and authority guard

**Files:**

- Create: `src/main/java/com/openggf/tools/audio/completerun/Bk2InputCursor.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioAuthorityGuard.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/TestBk2InputCursor.java`

**Interfaces:**

- Produces: `publish(InputHandler)`, `advance()`, `absoluteFrame()`, and `exhausted()` over the
  immutable `Bk2Movie.getFrames()` list.
- Consumes: `RecordedInputSnapshots.fromBk2(current, previous)` and
  `InputHandler.setLogicalOverride(...)`.

- [ ] Write RED tests for frame zero, current/previous edges, two players, publish-before-advance,
  duplicate publish/advance rejection, and fail-fast exhaustion. Name the production mutation
  each test catches; do not assert on a mock input handler.
- [ ] Implement the minimal state machine without using clamping `Bk2Movie.getFrame(int)`.
- [ ] Extend the static authority guard with an authenticated-producer package inventory that
  rejects every forbidden trace/reference/timing/direct-load symbol from the design.
- [ ] Run `mvn -Dmse=off "-Dtest=TestBk2InputCursor,TestCompleteRunAudioAuthorityGuard" test -B`.
- [ ] Commit as `feat(audio): add production BK2 audio cursor`.

### Task 5: Headless production startup, cadence, and observer lease

**Files:**

- Modify: `src/main/java/com/openggf/Engine.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/ProductionBk2AudioRunner.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioObserverLease.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/TestProductionBk2AudioRunner.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioObserverLease.java`
- Modify: `src/test/java/com/openggf/TestEngineLiveCapturePresentation.java`

**Interfaces:**

- Produces `Engine.initializeConfiguredHeadlessSession(InputHandler, AudioBackend)`, which sets
  the supplied logical input owner, installs the caller-owned production headless backend, marks
  that exact backend initialized, and delegates to one extracted `initializeConfiguredStartup()`
  method. `ensureAudioBackend()` must retain that backend and only ensure its presentation sink;
  it must never construct `LWJGLAudioBackend` for this session.
  The ordinary `Engine.init()` path calls that same extracted method after GLFW/graphics setup;
  it preserves the existing legal-disclaimer → master-title → `initializeGame()` choice and the
  existing `initializeGame()` → title/level-select/default-level ownership. The seam receives an
  already configured headless `EngineContext`, creates no GLFW/OpenAL device, and contains no
  mode or direct-level decision of its own.
- Makes `Engine.presentOuterAudioFrame(GameLoop, boolean, boolean)` tooling-visible without
  duplicating its `GameLoop.presentOuterFrame(...)` then `GameServices.audio().update()` order.
- Produces a runner callback carrying the absolute BK2 row and append-only observed events after
  exactly one step/presentation/cursor advance.

- [ ] Write RED engine tests proving
  `initializeConfiguredHeadlessSession(InputHandler, AudioBackend)` selects the same
  legal/master/title/level-select/default startup branches as the ordinary initialization block,
  installs and retains the exact input/backend instances through `initializeGame()` and master
  title creation, and never constructs or installs `LWJGLAudioBackend`.
- [ ] Extract the current post-platform startup branch into
  `initializeConfiguredStartup()`, call it from both `init()` and the headless seam, and keep
  `enterConfiguredStartupMode()` plus `loadDefaultStartingLevel(...)` private and unchanged.
- [ ] Write RED runner tests covering legal/master/title/level/special-stage mode transitions,
  one row → one `step()` → one outer presentation/update → one cursor advance, and failure when
  trace fast-forward/user-recording pumps are active.
- [ ] Write RED observer-lease tests for install-before-start, exclusive ownership, behaviorally
  inert append-only observation, and cleanup after success plus each injected failure point.
- [ ] Implement the runner and lease. Construct `HeadlessSmpsAudioBackend` over the established
  no-device presentation sink, pass it to the headless session seam, and assert identity after
  startup. Audio stays enabled and the runner never double-presents.
- [ ] Run the new tests plus `TestEngineLiveCapturePresentation`,
  `TestGameLoopAudioPresentationModes`, `TestAudioPresentationBoundary`, and
  `TestAudioDiagnosticObservers`.
- [ ] Run ROM-gated S2 and S3K frame-zero route feasibility tests with absolute ROM/BK2 paths.
  A failed natural transition becomes a named product gap and focused RED, not a bypass.
- [ ] Commit as `feat(audio): drive production audio from BK2 input`.

### Task 6: Canonical OpenGGF capture reducer and fixed engine producers

**Revised dependency:** Task 6 consumes the reviewed v2 contract above, not the former bundled
service model. Its reducer must populate frame-owned admission decisions, post-row state, and chip
events directly from their production observers even when a row has zero or a different number of
semantic services. It must select the OPENGGF producer observation inventory explicitly and emit
null for every unobserved layer; an empty observed list is not a substitute. Task 6 may widen a
producer inventory only after focused source-backed projection tests, and may not widen shared
comparison authority by itself.

**Files:**

- Create: `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioCaptureReducer.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunOpenGgfProducer.java`
- Create: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunOpenGgfProducer.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCaptureReducer.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/s2/TestS2CompleteRunOpenGgfProducer.java`
- Test: `src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kCompleteRunOpenGgfProducer.java`

**Interfaces:**

- Consumes Task 5 observations and game profiles; produces canonical frame/lifecycle/cutoff/
  terminal records through `CompleteRunAudioCaptureStore`.
- Both game producers consume only the whitelisted request fields and run from movie frame zero.

- [ ] Write RED reducer tests for request/admission/service/chip ordering, zero/multiple services
  per row, role ownership, lifecycle, segment gaps, cutoff frontier, terminal counts/digests, and
  first failure abort.
- [ ] Implement a game-neutral reducer; game-specific normalization/resolution remains in profile
  collaborators and packages.
- [ ] Write RED producer authority tests that poison every forbidden manifest/bootstrap field and
  prove output is unchanged or rejected before startup. Assert the engine producer never reads
  `referenceHome`.
- [ ] Implement S2/S3K fixed engine producers, driving from frame zero and retaining only each
  profile's declared comparison interval without resetting audio at the first retained row.
- [ ] Run focused reducer/producer/authority tests and the S1 regression oracle tests.
- [ ] Commit as `feat(audio): capture production-owned complete-run audio`.

### Task 7: Pin fixed identities and enable fresh-CLI execution

**OpenGGF prerequisite:** the executable JAR has no independent artifact-attestation trust root.
Keep S2/S3K `OPENGGF` bindings unavailable with that exact reason until the detached signed-manifest
contract in
[the producer attestation design](../../designs/audio/2026-09-01-openggf-producer-artifact-attestation-design.md)
has real signing custody and an independently provisioned verification key. Do not embed a
self-referential JAR hash, trust a runtime self-hash, or substitute unsigned build/Git provenance.

**S2 prerequisite status:** TraceChaser and OpenGGF now implement the authenticated raw-v2 contract
carrying the true pre-row-769 begin row and native ordinal for every boundary service. The strict
adapter validates and the native-only projector preserves this evidence without inferring the DPCM
origin or publishing callback provenance. Keep the production REFERENCE binding unavailable until
a real duplicate capture is independently reviewed and S2's exact buffered-native executable,
observer, and capability identities are installed. The existing OPENGGF/callback prefix-projector
metadata remains test-only.

**Files:**

- Modify: `src/main/java/com/openggf/tools/audio/completerun/s2/S2CompleteRunAudioProfile.java`
- Modify: `src/main/java/com/openggf/tools/audio/completerun/s3k/S3kCompleteRunAudioProfile.java`
- Modify only if required by the existing closed dispatch:
  `src/main/java/com/openggf/tools/audio/completerun/CompleteRunAudioProducerRegistry.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/TestCompleteRunAudioCli.java`
- Modify: `src/test/java/com/openggf/tools/audio/completerun/s2/TestS2CompleteRunAudioFixture.java`
- Create: `src/test/java/com/openggf/tools/audio/completerun/s3k/TestS3kCompleteRunAudioFixture.java`
- Modify: `tools/audio/run_complete_audio_parity.sh`

**Interfaces:**

- Produces pinned `ProducerRuntimeIdentity`, `ObserverProof`, `ObserverRuntimeIdentity`, and
  `PinnedProducerBinding` entries for both producer kinds. `NativeCapabilitySummary` is pinned
  only for TraceChaser reference producers using the buffered native observer; OpenGGF uses its
  callback/production observer identity and does not impersonate native capability.
- Consumes reviewed TraceChaser artifacts and the built OpenGGF producer artifact hash.

- [ ] Write RED fresh-JVM CLI tests for `producer-status`, deterministic double production, validate,
  compare, unknown profile, wrong producer kind, changed binary/core/observer/capability hash, and
  output no-replace behavior.
- [ ] Preserve pair-wide `producer-status` for the full parity wrapper and add
  `producer-kind-status <kind> <profile>` so an independently pinned OPENGGF producer can be
  queried while the corresponding REFERENCE producer remains unavailable.
- [ ] Replace unavailable bindings only with exact reviewed hashes/proofs. Finalize each profile's
  layer inventory from Task 3's projector evidence: a service/ownership/lifecycle layer stays
  unavailable unless its exact cross-producer equivalence was proven. Keep request/decision
  limitations until a separately reviewed source observation exists.
- [ ] Immediately after installing each exact REFERENCE binding, run the deferred Task 3 direct
  producer success/create-new-store tests before enabling registry/CLI production. The resulting
  store must pass both fixture-profile and runtime-profile validation; no late identity patching.
- [ ] Update the shell wrapper to validate its external run root and TraceChaser home, invoke both
  fixed producers twice, require byte-identical captures, compare, and publish only the small run
  manifest/report.
- [ ] Run `mvn -Dmse=off package -B` followed by the fresh-CLI tests in a new JVM.
- [ ] Commit as `feat(audio): enable fixed S2 and S3K parity producers`.

### Task 8: Execute comparisons and advance first divergences

**Architecture prerequisite (amended 2026-09-02):** Tasks 1-7 of
`2026-09-01-session-owned-smps-physical-device-implementation-plan.md` established the
single session-owned logical driver and physical device needed by the Sonic 2 request
path. The earlier requirement to finish that subplan's Task 8 before any Task 8 comparison
is superseded for Sonic 2 Task 8A by
`docs/architecture/designs/audio/2026-09-02-s2-c0-request-consequences-design.md`.
The broader session-device and unmeasured S3K command work remains frozen. This amendment
does not waive or decide the later S3K architecture gate. Do not re-land the rejected
per-voice S3K stop emitter.

**Files:**

- Modify only the ROM-owned production source and focused tests proven by each first divergence.
- Modify: `docs/status/audio-frontier-log.md`
- Modify: current behavior specs/gap ledger when evidence changes.
- Add validation reports under `docs/architecture/validation/audio/` for durable completed runs.

**Interfaces:**

- Consumes Tasks 1-7; produces an authenticated service/state/chip first frontier or
  `REFERENCE_LIMITATION` for each complete run.

- [ ] Run the S2 full command from frame zero against `[769,259590)` and preserve the first report.
  If it stops at the request frontier, retain the honest limitation and execute Task 8A before
  attempting to advance it; never infer the request from the reported consequences.
  After Task 8A C0-A and Tranche C production wiring are reviewed, Tranche D may run an
  authenticated `MEASUREMENT_ONLY` comparison. Do not update the frontier from that run.
  C0-B pause/resume and C0-C header key displacement must pass their separate reviews before
  an authenticated repeat comparison can be eligible to move the frontier.
- [ ] Run the S3K Knuckles full command from frame zero against `[810,434417)` and preserve the
  first report. Do not run the Sonic/Tails diagnostic under the Knuckles identity; its separate
  fixed-prefix authority belongs only to Task 8B.
- [ ] For each product-route or semantic divergence: identify the exact shipped routine/state,
  write and verify a focused RED, implement the minimal source-owned behavior, run GREEN, rerun
  S1 regression gates, then restart comparison from the profile boundary.
- [ ] Update the frontier log on every movement with command, commit/worktree, result category,
  comparison/error counts, and first frame/service/field.
- [ ] Stop only at complete compared-layer equality, an honest reference limitation, or a
  reproducible product gap whose next source-owned RED is recorded. Never realign or skip ahead.

### Task 8A: Establish Sonic 2 pre-consumption request authority

**Status:** the fixed unbound observer/raw-v3 producer and the extractor's semantic raw-v3
validation/projection are complete. The unpublished bounded-v2 carrier now closes over its own
750-row body and inventories, without copying the five absent-domain full-source claims. Its
strict Java Tranche-A reader independently recomputes that closure through a package-private,
immutable candidate seam. The candidate remains CLI-unreachable and `production_bound:false`. **The
duplicate-capture gate is met and, with explicit human approval on 2026-09-03, the bounded
window is published** as
`src/test/resources/audio/parity/s2/s2-request-window-w10150-10900.raw-v2.jsonl.gz` with its
metadata sidecar; `TestS2RequestAwareOracleRawStream` now defaults to it and keeps
`-Ds2.request.candidate.path` as an override. Publication installs a committed comparison
reference only: no producer binding, profile, or CLI route exists, `production_bound` stays
false, the reader stays package-private, and request equality remains a reference limitation
until an independent authenticated OpenGGF producer observes equivalent source-owned evidence. Tranche B's game-owned S2 mailbox/queue mechanism is complete.
Before Tranche C production wiring, C0-A makes its consequences transactional. Tranche D then
runs an authenticated measurement without a frontier claim; C0-B pause/resume and C0-C header
key displacement follow as separate reviewed tranches before the frontier-eligible repeat.
The exact ordering and superseding Sonic 2 architecture ruling are in
`docs/architecture/designs/audio/2026-09-02-s2-c0-request-consequences-design.md`. Tranche A did
not modify profile, comparator, or engine behavior. This task is still required
before Task 8 may advance the Sonic 2 comparison past the tick-210 request frontier. Its fixed
boundary and authority constraints are audited in
`docs/architecture/audits/audio/2026-09-02-s2-preconsumption-request-producer-audit.md`.

**Files:**

- Modify the pinned TraceChaser `AGENTS.md` and `CLAUDE.md` together, after explicit approval, to
  authorize only the fixed `0x10D6` observation callback. Their current two-exception rule is a
  hard implementation gate, not permission to add a general diagnostic-hook surface.
- Modify the fixed Sonic 2 service manifest/profile, complete-audio runner, raw sink/schema,
  capability, and exact pure C# tests in the pinned `tools/tracechaser` submodule.
- Create a closed full-run-raw-v3-to-**new request-aware oracle raw-v2** extractor and its
  negative tests in the pinned `tools/tracechaser` submodule. The committed bounded EHZ oracle is
  raw-v1 and remains unchanged; raw-v2 is an explicit successor required to carry transfer
  evidence rather than an existing fixture format.
- Modify the Sonic 2 oracle raw stream, comparator reference model, ROM-shaped engine
  mailbox/queue/admission producer, complete-run raw adapter/projector/profile, and focused Java
  tests and authority guards.
- Modify validation records only after fresh authority and duplicate capture gates complete. Do
  not edit the frontier or publish a fixture merely because the observation code exists.

**Interfaces:**

- Observes only the exact accepted M68K-to-Z80 transfer before ROM PC `0x10D6` executes opcode
  bytes `13 80 10 09`: D0.b is the nonzero raw request, D1.w is the actual slot 0 through 3, and
  A7 is the exact managed/native correlation token.
- Reuses the reviewed ABI 4 action-7 A7 marker and Sonic 1 managed-register/native-marker pattern.
  The manifest owns the fixed PC/opcode/token inventory; callers cannot select an observation PC.
- Publishes an ordered raw `request_transfers` inventory. It does not infer a request from Z80
  state, a live sound pointer, chip writes, audible output, fixture coordinates, or the known
  tick-210 result.
- Keeps transfer, Z80 queue scan, priority/admission, and playback consequences distinct. Multiple
  transfers may precede one `zCycleQueue`, and the shipped FixBugs=0 bridge index 3 reads
  `Music1` (`SoundQueue.SFX0+3`) and writes the low byte of `VoiceTblPtr`; no layer may collapse
  these into one direct request per tick.
- Reference requests remain comparison-only. They never call `DriverRequest`, `admitSfx`, or any
  production behavior owner. Request equality stays unavailable until an independent authenticated
  OpenGGF producer observes equivalent source-owned evidence.

- [x] Obtain explicit approval for the fixed observer exception and update the pinned TraceChaser
  `AGENTS.md` and `CLAUDE.md` together before implementation. Keep general M68K diagnostic hooks
  prohibited.
- [x] Write RED pure C# tests for the fixed hook/opcode, D0/D1/A7 capture, every reviewed active
  service topology, bounded correlation, orphan/reordered/duplicate/cross-frame/terminal failures,
  and strict raw v3 schema negatives.
- [x] Implement exactly one Sonic 2 pre-execution callback at `0x10D6`, correlate it with the next
  exact action-7 native marker, observe from power-on, and publish only from comparison row 769.
  Add native ordering regression tests; do not change the native patch or ABI unless a separately
  reviewed observer identity family is first approved.
- [ ] Pin the fixed request inventory and all manifest, capability-template, harness, correlator,
  count, digest, occupancy, and terminal identities. Retain native hashes/ABI only when the patch is
  byte-identical and freshly reproduced.
- [x] Write RED extractor tests, then implement the closed deterministic raw-v3-to-new
  request-aware-oracle-v2 extraction for `[10150,10900)`. Its baseline declares its
  source-preceding row and folded latch phase; do not revive the environment-gated window capture
  or derive request values from outcomes.
- [x] Close the unpublished bounded-v2 body/inventory evidence and add only the Tranche-A strict,
  package-private Java candidate reader with its independent producer-shaped closure vector and
  authority guards. Do not alter raw-v1, any profile/comparator/capture/engine owner, or install
  a publication/fixture route.
- [ ] Write RED Java adapter/oracle/queue/profile/guard tests, then replace the direct at-most-one
  request-per-tick diagnostic shortcut with ROM-shaped source-owned mailbox, transfer, Z80 queue,
  and admission semantics. Keep authenticated reference observations behavior-inert.
- [ ] Run native self-tests and exact named pure C# non-live tests with empty process inventories
  before and after every Mono invocation. Then restore locked trust roots and complete the mandatory
  two-fresh-build native-observer and deterministic managed-harness reproduction gates.
- [ ] After the authority gates pass, run two serial power-on S2 captures to distinct absent
  external paths under closed process supervision. Require identical normalized attestations,
  ordered requests, events, terminal state, cutoff frontier, and digests before independent review.
- [x] Only after independent approval, extract the fixed window and install reviewed identities.
  Preserve `REFERENCE_LIMITATION` and do not publish, fabricate, hand-edit, or consume a request
  fixture while fresh authenticated native-GPGX authority remains unavailable.
  Done 2026-09-03: two independent captures agree at SHA-256
  `a7d56fe71674d9f4a9307e6fb6078f7832409bb310916e808faf28b1e9426c2c`, the payload is committed
  gzipped with its reviewed identities in a metadata sidecar, and the window is byte-identical to
  the captured bytes. Follow-on widening and second-recording work is planned in
  [2026-09-03-multi-recording-oracle-roadmap.md](2026-09-03-multi-recording-oracle-roadmap.md).

### Task 8B: Establish S3K Sonic/Tails pre-consumption request authority

**Status:** planned after Task 8A; no request capture, fixture, producer binding, or comparison
authority exists yet. This task applies only to the Sonic/Tails service-128 diagnostic prefix
`[0,5400)`. It does not modify or supersede the Knuckles complete-run profile
`s3k_locked_on_knuckles_superemeralds.v1` or its `[810,434417)` comparison. The exact source,
identity, topology, and authority constraints are audited in
`docs/architecture/audits/audio/2026-09-02-s3k-preconsumption-request-producer-audit.md`.

**Files:**

- If policy review classifies this boundary as a third address-filtered observer exception,
  modify the pinned TraceChaser `AGENTS.md` and `CLAUDE.md` together, after explicit approval,
  and update their policy tests. The current exactly-two-exception rule is an implementation
  gate, not implicit authorization.
- Modify a full fixed S3K service manifest, a distinct Sonic/Tails profile/capability,
  complete-audio runner, submission raw sink/schema, closed launcher/CLI, and exact native/pure
  C# tests in the pinned `tools/tracechaser` submodule.
- Create a closed duplicate-raw-to-bounded-oracle-v2 extractor and its negative tests in the
  pinned `tools/tracechaser` submodule.
- Modify the S3K oracle consumer, strict raw adapter/projector/profile, independent production
  BK2 OpenGGF request observer, comparator projection, and focused Java authority guards.
- Modify validation records only after fresh authority, duplicate capture, independent review,
  and explicit publication gates complete. Do not edit the frontier merely because observer
  code exists.

**Interfaces:**

- Pins `s3k-complete-sonic-tails.bk2`, SHA-256
  `82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf`, its full
  466,334-row identity, and only the fixed publication prefix `[0,5400)`. It never accepts the
  Knuckles BK2, hash, interval, capability, or profile.
- Observes `Play_Music` from exact M68K PC `$1358` / opcode
  `33fc010000a11100` through PC `$1374` / opcode `33fc000000a11100`, snapshots exactly
  Z80 RAM `$1C0A..$1C0B` while the bus remains held, and authenticates `$FE` from that source
  byte. It does not infer a request from source frame 242, service 128, mailbox residue, the
  84-write stop burst, or SEGA-PCM exit.
- Uses the existing ABI 4 generic action/service/snapshot mechanics only if native tests prove
  the real cross-CPU topology. A new native action requires a separately approved ABI/core and
  full artifact-identity cascade.
- Keeps reference requests comparison-only. They never dispatch into OpenGGF or select behavior.
  Request equality remains unavailable until an independent authenticated OpenGGF BK2 producer
  observes equivalent source-owned evidence through `AudioRequestObserver`.

- [ ] Resolve the TraceChaser policy classification first. If this is a third address-filtered
  exception, obtain explicit approval and amend both mirrored policy files and tests before any
  observer implementation. Keep caller-selectable/general diagnostic hooks prohibited.
- [ ] Write RED native and pure C# tests for exact `$1358`/`$1374` opcodes, kind-8 parent and
  kind-13 child, one-byte snapshot, native ordering, every declared non-target active-kind
  alternative, opcode/parent/scheduling faults, bounded state, terminal cleanup, and strict raw
  schema negatives.
- [ ] Extend the full production S3K manifest, not the synthetic subset: add range 3 for
  `$1C0A`, kind 13, `ALLOW_CHILDREN` only on kind 8, the target begin/end pair, and exact
  non-mutating action-7 alternatives at both watched PCs for root and every other reachable
  active kind. Prove every visit selects exactly one hook; never accept an
  unmatched-active-kind fault.
- [ ] Create the distinct fixed Sonic/Tails profile, capability, and power-on runner. Validate
  the entire 466,334-row movie identity, begin the sink from `CaptureCutoffFrontier()` before row
  zero without starting a premature publication epoch, and capture exactly `[0,5400)`.
- [ ] Add installation verification equivalent to the Sonic 2 gate and pin manifest,
  capability-template, harness, observer/install, source, count, digest, occupancy, and terminal
  identities. Retain native core hashes only after fresh locked-source/toolchain reproduction
  proves byte identity.
- [ ] Write RED duplicate-extractor tests, then implement the closed two-raw-to-oracle-v2
  transform. Require exact duplicate attestations and unchanged old-v1 shared projection; add
  only source-observed request evidence. Reject inferred/hand-inserted `$FE`, disagreement,
  provenance mismatch, partial output, overwrite, and legacy fallback.
- [ ] Write RED Java schema, adapter, projection, independent-producer, comparator, and authority
  guard tests. Keep `S3kOpenGgfAudioCapture`'s reference-mailbox dispatch fenced as
  fixture-assisted; authenticated comparison must never hydrate from the reference.
- [ ] Restore fresh native authority and run two serial power-on captures to distinct absent
  external paths under a closed launcher using `exec mono`, timeout/process-group supervision,
  and empty exact process inventories before and after each invocation.
- [ ] Only after independent review and explicit approval, publish a create-new fixture bundle
  and install reviewed identities. Preserve both `REFERENCE_LIMITATION / producer_input` and
  `FRESH_AUTHENTICATED_NATIVE_GPGX_AUTHORITY_UNAVAILABLE` until every reference and independent
  OpenGGF gate closes.

### Task 9: Refresh the six-stage evidence and remaining queue

**Files:**

- Modify: `docs/architecture/audits/audio/2026-08-31-sound-driver-re-current-state-audit.md`
- Modify: the current cross-game gap analysis and affected per-game behavior specs.
- Modify: `docs/architecture/validation/audio/2026-08-21-smps-playback-listening-checklist.md`
- Modify release/discrepancy documents only when their mapped facts actually change.

- [ ] Reclassify every roadmap item with the new production-owned evidence, distinguishing
  `MATCH`, `REFERENCE_LIMITATION`, first divergence, unit/synthetic contract, and
  fixture-assisted projection.
- [ ] Catalogue remaining source-owned gaps: request authority, priority/queue state, pause,
  1-up/fade/speed, modulation/frequency edges, ROM-read tables, DAC/PCM timing, and analog mix.
- [ ] Add affected scenarios to the human listening queue without marking them heard.
- [ ] Commit the evidence refresh with correct documentation trailers.

### Task 10: End-to-end review and delivery verification

**Files:**

- Create: `docs/architecture/validation/audio/2026-08-31-sound-driver-roadmap-integration-report.md`
- Create: `docs/architecture/audits/audio/2026-08-31-sound-driver-roadmap-end-to-end-review.md`
- Modify: `README.md` during final merge into `develop`, as required by policy.

- [ ] Run independent whole-branch review against the requirements, design, and this plan; fix all
  Critical/Important findings and record residual risk.
- [ ] Fetch and fast-forward the main-workspace `develop` without disturbing user changes; record
  the updated baseline full-suite failures exactly.
- [ ] In the feature worktree run focused audio/complete-run tests, S1 oracle regressions,
  TraceChaser native tests, the ordinary suite, and fresh-JVM guards on JDK 21 with Lua 5.4 and
  all three absolute ROM paths.
- [ ] After explicit human integration approval, merge into main-workspace `develop`, update the
  required README release summary, rerun the full ordinary/guard regression comparison, and push
  only `develop`.
- [ ] Remove only verified clean/merged worktrees and fully merged local scaffolding branches;
  preserve and report any unknown or user-authored change.

## Verification command set

Focused shared framework:

```bash
LUA_BIN=lua5.4 mvn -Dmse=off \
  '-Dtest=com.openggf.tools.audio.completerun.**,com.openggf.audio.TestAudioDiagnosticObservers,com.openggf.audio.TestAudioPresentationBoundary' \
  test -B
```

S1 regression gates:

```bash
LUA_BIN=lua5.4 mvn -Dmse=off \
  '-Dtest=com.openggf.tools.audio.parity.s1.**,com.openggf.audio.smps.TestSmpsSequencerCadence,com.openggf.audio.TestSmpsFadeAudioThroughput' \
  test -B
```

Ordinary and guards with verified absolute ROM paths:

```bash
LUA_BIN=lua5.4 mvn -Dmse=off \
  '-Dsonic1.rom.path=<absolute S1 REV01 ROM>' \
  '-Dsonic2.rom.path=<absolute S2 REV01 ROM>' \
  '-Ds3k.rom.path=<absolute locked-on S3K ROM>' test -B

LUA_BIN=lua5.4 mvn -Dmse=off -Pguards \
  '-Dsonic1.rom.path=<absolute S1 REV01 ROM>' \
  '-Dsonic2.rom.path=<absolute S2 REV01 ROM>' \
  '-Ds3k.rom.path=<absolute locked-on S3K ROM>' test -B
```

## Required closing artifacts

- Integration Report: exact changed files/commits, TraceChaser gitlink, commands/results,
  frontiers, limitations, and deferrals.
- End-to-End Review: requirements traceability, architecture consistency, findings/fixes,
  residual risk, and human listening/integration checklist.
