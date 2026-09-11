# Production-owned sound-driver validation design

Date: 2026-08-31  
Status: proposed for review  
Scope: S2/S3K production-owned validation, with S1 as precedent and a regression gate

## Decision

Validation has two connected but causally independent halves. TraceChaser/BizHawk is the
emulator-facing reference producer and authority for the exact raw candidate it captures.
OpenGGF is the consumer/client and canonical-store publication authority: it validates
TraceChaser output, runs itself from the same identified ROM and BK2 controller input,
captures its own behavior, compares the two streams, and controls installation of a
canonical fixture after explicit approval.

TraceChaser output flows into OpenGGF's validation tooling but never into OpenGGF gameplay
decisions. OpenGGF must naturally submit its own music and sound-effect requests and advance
the production audio boundary. Reference driver state, requests, mailbox values, trace
physics, auxiliary state, and fixture coordinates remain comparison-only.

This replaces the previously contemplated request-replay approach and the initial mistaken
proposal to build a second reference framework inside OpenGGF. It reuses TraceChaser's
reviewed complete-run audio observer, profiles, raw sinks, capture runners, capability
ledger, and publication workflow together with OpenGGF's existing `CompleteRunAudio*`
consumer framework. Existing fixture-assisted S2 and S3K oracle tools remain useful
diagnostics, but cannot certify an authenticated `MATCH`.

## Six-stage roadmap

The work remains one cross-game roadmap, not merely an attempt to move two comparator
frontiers.

| Stage | Objective | Entry evidence | Exit gate |
|---|---|---|---|
| 1 | Reverse-engineer each shipped per-game driver. | Canonical disassembly, ROM identity, existing driver research. | Relevant entry points, RAM, tables, control flow, and shipped bug-compatible paths are cited. `FixBugs`, `fixBugs`, or `fix_sndbugs` paths follow the shipped-ROM setting, including zero/off behavior. |
| 2 | Document routines, state, cadence, and behavior. | Stage 1 findings. | A current behavior specification and shared comparison vocabulary distinguish request, admission, service, channel ownership, chip write, and audible output. Stale claims are corrected or explicitly marked historical. |
| 3 | Compare the ROM model with OpenGGF. | Current specification plus inspected engine code and tests. | Each claim is classified by evidence strength: code inspection, source-owned synthetic contract, fixture-assisted projection, or authenticated production comparison. |
| 4 | Catalogue the gaps. | Stage 3 findings with their evidence strength. | A current gap ledger names the source owner, OpenGGF owner, impact, evidence, dependencies, and next proof for each gap. |
| 5 | Implement source-owned gaps. | A Stage 4 gap with a primary-source owner. | Implementations model ROM state and routines without trace-, route-, zone-, game-name-, or frame-specific exceptions. Focused tests cite the owning source behavior. |
| 6 | Validate and publish. | Independent reference evidence plus production-owned OpenGGF behavior. | Exact identities and bounds validate; comparison stops at the first divergence and never realigns; focused, ordinary, and guard suites introduce no regression; the frontier log and durable evidence are current; human listening is completed by a human where required. |

The runner and observer work below enables comparisons in stage 3 and validation in stage 6.
It does not by itself complete
the earlier reverse-engineering, gap, or implementation objectives.

## Evidence terminology

- `MATCH` means a bounded, authenticated, layer-scoped comparison in which the identified
  TraceChaser producer and OpenGGF independently receive the same identified controller
  input, OpenGGF naturally produces its behavior, all records in the declared coverage
  inventory compare equal, and no observation required by that inventory is missing. A
  service/state/chip `MATCH` does not imply request/admission parity.
- `fixture-assisted projection` means a diagnostic whose OpenGGF-side behavior is partly
  selected or populated by reference data. It may identify a useful first divergence but
  cannot pass an acceptance gate.
- `unit/synthetic contract` means source-owned stimulus constructed to prove a specific
  routine or state-machine behavior. It is valid Stage 3/4 evidence but is not an
  end-to-end production comparison.
- `unresolved` means the required authority, observation, startup route, or implementation
  is absent. Missing data never implies equality.

Oracle execution, unit tests, and human listening prove different things and are never
substituted for one another. Agents may prepare the listening queue but cannot certify its
human-owned result.

## Authority boundary

### Permitted behavior inputs

The production OpenGGF capture may receive only:

1. an exact ROM selected through normal configuration and verified against the repository's
   pinned identity;
2. normal engine initialization and source-owned configuration;
3. the current and previous controller rows from an identified BK2 movie; and
4. the pinned run manifest only through a strict identity/segment-inventory view matching
   `CompleteRunFixture`; and
5. ordinary user-independent capture bounds that decide when observation starts and stops,
   never what the engine does.

All startup values must be attributable to one of those owners. The capture may record the
BK2 cursor, game mode, requests, admissions, service events, ownership, and chip writes as
diagnostics or comparison fields.

### Forbidden behavior inputs

The production capture must not consume `TraceData`, `TraceEntry`, `TraceSessionLauncher`,
`TraceReplayDrive`, `TraceReplaySessionBootstrap`, manifest bootstrap/descriptors, physics
rows, auxiliary rows, `hardware_timing.jsonl`, reference audio rows, reference sound IDs,
queue vectors, mailbox bytes, speed-up coordinates, or output-inferred requests. Its
`CompleteRunAudioProducer.Request.runManifest()` input is restricted to exact file identity,
movie identity, and segment boundaries already pinned by the profile; all other fields are
rejected or ignored by a tested whitelist. It must not jump the input cursor, hydrate
gameplay state, synchronize state each frame, or infer a request from its later consequences.

The existing hardware-timing exception remains exactly as documented elsewhere: it may
delay readiness of matching production-submitted work or admit an already-existing lag
loop. It cannot carry a sound ID or decide what work exists. This design does not require
that exception, so this authenticated producer accepts no hardware-timing input. Guards
confine that decision to this producer and reject gameplay-bearing timing use; they do not
ban the repository's separate, policy-permitted timing port.

`TraceReplaySessionBootstrap` is therefore excluded, rather than treated as a permitted
pre-window reconstruction. Its position, collision, random, counter, and alignment values
are trace-derived gameplay state and cannot authenticate natural startup for this purpose.

## Producer-consumer architecture

### TraceChaser/BizHawk reference producer

TraceChaser owns capture correctness, emulator observation, native service reconstruction,
producer/runtime identity, raw staging, and exact candidate evidence. OpenGGF owns canonical
store publication after strict consumer validation. The design reuses, rather than
duplicates:

- `CompleteRunAudioObserver` and the locked GPGX buffered audio observer ABI;
- `S2AudioObserverProfile` with `S2CompleteAudioCaptureRunner` and
  `S2CompleteAudioRawSink`;
- `S3kAudioObserverProfile` with `S3kCompleteAudioCaptureRunner` and
  `S3kCompleteAudioRawSink`;
- the reviewed service manifest, capability fixture, source/build/artifact locks, and native
  behavioral gates; and
- TraceChaser's validate/capture/publish policy, including external scratch capture and
  explicit approval before canonical fixture replacement.

TraceChaser produces lossless raw streams. It does not write directly into OpenGGF's
canonical fixture tree and does not decide whether OpenGGF passes. The missing OpenGGF fixed
reference producers must invoke the pinned TraceChaser runner, validate the raw stream
through the game-owned strict adapter, project it into the existing canonical
`CompleteRunAudioTrace`, and publish through `CompleteRunAudioCaptureStore`.

The native observer already uses a closed, reviewed service graph of exact Z80/M68K
instruction boundaries. Reuse of that graph is part of this design. No general callback API,
arbitrary address supplied by OpenGGF, or new fixture-specific request hook is introduced.

### OpenGGF consumer/client

OpenGGF owns the canonical schema, typed per-game profiles and state normalizers, strict raw
adapters, consumer-side fixture installation, independent engine producer, comparator,
first-divergence report, and acceptance tests. Existing owners are retained:

- `CompleteRunAudioTrace`, `CompleteRunAudioCaptureStore`,
  `CompleteRunAudioComparator`, and `CompleteRunAudioTool`;
- `S2CompleteRunAudioProfile`, `S2CompleteRunReferenceRawAdapter`, state decoder,
  normalizer, asset catalog, and native sound resolver; and
- the equivalent S3K profile/adapter/preflight/decoder/normalizer/catalog/resolver stack.

The missing integration is not a new schema family. It is the fixed S2/S3K reference
producer adapters, the independent OpenGGF producers, the runtime/proof/capability bindings
that replace `UnavailableProducerBinding`, and any source-owned normalization needed to turn
validated raw events into the semantic layers actually supported by the approved observer
graph. In particular, current S2 raw services cannot become canonical requests or decisions;
those layers remain unavailable until a policy-compliant pre-consumption observation exists.

### `ProductionBk2AudioRunner`

A test/tooling-owned `ProductionBk2AudioRunner` will drive the genuine all-mode
`GameLoop` from startup without trace replay infrastructure. It will use existing narrow
production seams where possible:

- `Bk2MovieLoader` parses the BK2;
- `RecordedInputSnapshots.fromBk2(current, previous)` creates the logical controller
  state;
- `InputHandler.setLogicalOverride(...)` publishes that state before the step; and
- the outer-frame cadence is one `GameLoop.step()` followed by the same one outer-audio
  callback used by `Engine.display()`: `Engine.presentOuterAudioFrame(loop, false, false)`.

The runner advances only the BK2 cursor. Capture bounds control retention and termination,
not engine behavior. Fast-forward, trace sessions, per-mode cursor substitution, and direct
SMPS driver advancement are forbidden and asserted absent.

The implementation must extract the smallest package/test-visible headless
production-startup seam needed to exercise the same ROM selection, game initialization,
game-mode routing, and audio ownership as the normal engine. It should reuse the ownership
currently expressed by `Engine.initializeGame()`, `enterConfiguredStartupMode()`, and the
normal starting-level path while accepting an already configured headless graphics/audio
service set. `HeadlessGameBoot`, `HeadlessTestFixture`, and
`UserRecordingSmokeHarness` may inform tests, but none is sufficient as the authenticated
runner because each bypasses master/title routing or uses trace/fresh-level setup. The new
seam separates GLFW/window initialization from production game initialization; it must not
duplicate a second startup state machine or call a direct level-load convenience helper from
the runner.

Audio remains enabled and uses the production `AudioManager` and presentation producer. A
headless/no-device sink is supplied through the established service boundary; the runner
must not disable audio or silently initialize the live LWJGL/OpenAL backend in CI.
`Engine.presentOuterAudioFrame(...)` is the cadence authority. The runner must not combine a
helper that already presents audio, such as a `HeadlessTestRunner.stepFrame*()` path, with a
second presentation call.

### BK2 input ownership

Input is owned by a runner-local `Bk2InputCursor`. It exposes current and previous rows,
publishes their logical snapshot before `GameLoop.step()`, advances exactly once after
`Engine.presentOuterAudioFrame(...)`, fails if the movie is exhausted, and never seeks away
from its initial frame-zero position. This preserves held and pressed edges through normal
`InputHandler` semantics. The same input cursor must remain valid across legal disclaimer,
master title, per-game title/data-select, level, special-stage, and transition modes.
`PlaybackDebugManager` is not the cursor authority because its driving scope is currently
limited to level and bonus-stage modes. Programmatic calls such as master-title
`selectEntry(...)`, direct zone loads, forced game-mode changes, and skipped input rows may
be used only in separately labelled feasibility diagnostics; they cannot certify the
current power-on route.

### Observation

Observers are attached before frame zero or before any game-owned request can be submitted:

- `AudioRequestObserver` records raw OpenGGF caller requests;
- admission/contention observers record resolution and hardware-role arbitration;
- driver-service and chip-write observers record sequencer and output consequences.

Observers are append-only and disabled by default. They are never consulted by production
decisions, never stored in rewind state, and are detached in `finally`. A scoped observer
lease owns installation and cleanup, refuses conflicting ownership, and exposes enough
diagnostic state for tests to prove cleanup on success and failure. The runner fails if the
lease cannot acquire exclusive observer ownership or cleanup leaves an observer installed.

Request, admission, service, ownership, state, and chip writes remain distinct event kinds.
Later output cannot be used to reconstruct a missing earlier request.

### Independent producer flow

The TraceChaser reference producer and the OpenGGF producer run separately and complete
their captures before comparison. No reference path, stream, parsed row, callback, or
request collection is passed to the OpenGGF producer. Both consume the exact ROM and BK2
identities pinned by the same `CompleteRunAudioProfile`; only the controller movie is a
shared behavior input. The OpenGGF capture is regenerated under the current worktree's
`target/` tree or an explicit external output directory; Maven output is never redirected
to a shared durable root.

The comparator validates both streams completely, then compares from their declared common
boundary. It reports the first missing, extra, reordered, or different semantic event and
does not realign after divergence. Frame, tick, service, and cursor coordinates describe
where an event was observed; they cannot trigger it.

The existing `CompleteRunAudioProfile` and `CompleteRunFixture` are the identity and bounds
contract. They pin the ROM, BK2, segment inventory, comparison interval, producer bindings,
runtime identities, observer proofs, and capability summary. No new parallel run-manifest
type is introduced. `CompleteRunAudioProducer.Request` already supplies the pinned run
manifest to both producers. The OpenGGF producer may validate only its identity and the
profile-matching movie/segment inventory; bootstrap state, dynamic-art descriptors,
physics/auxiliary paths, hardware timing, and replay descriptors cannot affect capture.

### Event contract

The game-neutral `complete_run_audio.v2` schema records ordered metadata, baseline, frame,
lifecycle, cutoff, and terminal records. V1 is rejected rather than guessed because no installed
complete-run store requires migration. Its ten independent semantic layers are `ROW_LAG`,
`REQUESTS`, `DECISIONS`, `SERVICES`, `STATE`, `OWNERSHIP`, `LIFECYCLE`,
`FRAME_CHIP_EVENTS`, `BOUNDARY_CHIP_STATE`, and `CUTOFF_FRONTIER`.
Raw TraceChaser coordinates, service tokens, hook tokens, source PCs, mailbox addresses,
queue slots, and native ordinals are validated provenance or diagnostics; the canonical
adapter projects them into producer-neutral frame/service ownership and may not silently
discard an event it cannot semantically explain.

Every canonical request carries its producer-owned ordinal, request class/native identity,
source owner, and frame. Frame-owned decisions correlate requests and carry resolved identity,
priority/arbitration outcome, and ownership transition. This follows production:
`AudioAdmissionObserver` is emitted synchronously by
`AbstractSmpsAudioBackend.playSfxSmps`/`evaluateAdmission`, before and independently of
`SmpsDriver` service callbacks. `DriverService` therefore carries only service kind/order,
completion, lifetime, carry, and ancestry. Post-row state and frame chip events are also owned by
the frame; none of these fields depends on service cardinality.

Profile identity contains two distinct inventories. The shared comparison inventory authorizes
cross-producer equality. A producer-specific observation inventory states which canonical fields
that producer actually observed. `COMPARED` requires both producers to observe the layer, but the
reverse is deliberately false: both may retain authenticated evidence while comparison remains
unavailable pending review. Nullable scalar/object/list fields are the only unobserved
representation; a non-null empty list means observed-empty. Strict constructors and JSON reject
missing/unknown fields and any observed/present mismatch. Observed decisions require observed
requests; observed ownership also requires its complete decision and lifecycle carriers so nested
owner transitions cannot disappear behind an unavailable layer.

Baseline and cutoff envelopes are mandatory while topology, boundary chip events/latches, state,
and owners remain independently nullable. Buffered-native diagnostics authenticate the observer
stream independently of semantic comparison availability. Native order, manifest, digest,
capability, managed/carry completion, and terminal numeric counts are checked whenever that native
observer is declared; comparison status governs equality only. Each present semantic boundary
component is correlated independently: topology/lifetime projection ignores chip partitioning,
while global boundary chips and any native-observable YM address latch are proven without requiring
semantic topology.

The comparator reports `REFERENCE_LIMITATION` only after every compared layer is equal. A missing
request or lag layer is never represented by equal empty arrays, inferred `false`, or a partial
`MATCH`; the first real mismatch still wins. Semantic-digest behavior remains the existing
all-compared optimization, while numeric terminal counts remain store-integrity evidence.

## Feasibility gates

Implementation proceeds in fail-closed gates. A failed gate produces an explicit product or
authority gap rather than a weaker acceptance claim.

1. **Reference-producer integration.** The pinned TraceChaser runner produces a complete raw
   candidate whose identities, ABI, bounds, event graph, driver state, baseline, and cutoff
   pass the existing game-owned OpenGGF adapter. The canonical projection declares every
   represented and unavailable semantic layer and publishes only through a private
   transaction.
2. **Headless production initialization.** The exact ROM can enter and step the normal
   startup game mode without GLFW presentation and without trace bootstrap, direct level
   load, or fixture state.
3. **Input cardinality and edges.** One BK2 cursor row is applied per production outer
   frame. Held/pressed semantics, cursor advancement, presentation, and
   `AudioManager.update()` each have asserted cardinality, including non-gameplay modes.
   Trace fast-forward and user-recording pump paths are disabled and asserted absent.
4. **Natural audio observation.** Observers attached before the first request record
   OpenGGF-owned requests and their later consequences without changing behavior.
5. **Route reachability.** The identified movie naturally reaches the intended comparable
   game mode and window. An unsupported transition, special-stage cadence, title path, or
   initialization mode is recorded as a product gap.
6. **Authenticated comparison.** Only after gates 1-5 pass may the declared common layers be
   compared and described as `MATCH` or as a first divergence.

## Game-specific application

### Sonic 1

The existing GHZ music result is a bounded driver-core comparison: OpenGGF starts the
source-owned GHZ song but uses reference metadata to define the cadence and terminal bound.
The sound-test SFX result is fixture-assisted because `S1OpenGgfSfxAudioCapture` replays the
reference `dispatches` sequence. Neither is a production-owned `MATCH` under this design's
new terminology. The S1 gameplay timeline provides a useful observer/schema/comparator
split, but its `VisualRunReplayHarness` and trace-derived level bootstrap are not the startup
authority for this stricter cross-game path. Existing driver-core regression tests remain
green; any future production-owned S1 claim must be rerun through the new authority boundary
instead of exempting legacy hydration.

### Sonic 2

The primary authenticated lane is the existing TraceChaser/OpenGGF complete-run contract
`s2_rev01_complete_emeralds.v1`. It pins
`sonic-2-sonic-tails-complete-emeralds.bk2`, SHA-256
`e850798f882b8c580aad148bc97cb50f260cae1d336dd649fe2f4dfae6796aa5`, with comparison rows
`[769,259590)`. TraceChaser already proves two identical full native captures: 259,590
frames, 169,986,419 events, maximum frame occupancy 1,825, and an empty cutoff frontier.
`S2CompleteRunReferenceRawAdapter`, the state decoder/normalizer, native sound resolver,
profile, store, comparator, and CLI already exist in OpenGGF. The missing pieces are the
fixed reference producer/projection and independent OpenGGF producer bindings.

OpenGGF starts from movie frame zero, retains naturally produced audio state through the
prepublication interval, and compares from row 769. It does not hydrate the row-769 state.
The first authenticated result is the first divergence of that complete-run prefix; the
design does not skip directly to a known later fixture frontier.

This design uses the full canonical interval `[769,259590)` and does not introduce a second
window profile. The older EHZ fixture `[10150,10900)` remains a diagnostic projection whose first reported
divergence is movie row 10412 / driver tick 210. That coordinate lies inside the complete-run
movie and becomes ordinary downstream coverage only if the production-owned comparison
naturally reaches it. The speed-up transition must arise from OpenGGF gameplay/runtime
state. `SPEED_UP_ROW` may identify a diagnostic mismatch but cannot schedule the transition.

The current TraceChaser S2 service manifest does not observe the M68K `sndDriverInput`
request transfer at ROM PC `$1084`; its raw stream therefore authenticates native services,
Z80 state, and chip writes, not a pre-consumption request timeline. The canonical reference
adapter must leave that layer unavailable rather than infer it from `$0000..$1FFF` snapshots
or output. Initial complete-run results may authenticate declared service/state/chip layers
while reporting `REFERENCE_LIMITATION` for request/admission parity.
The raw-to-canonical adapter is tested to emit no `Request` or `Decision` record from a raw
stream lacking an approved source event. It may not synthesize them from driver snapshots,
service boundaries, chip writes, or coordinates.

Before the long-route capture is attempted, a dedicated feasibility test must prove that
pure BK2 logical input can enter, run, and exit the S2 special stage through normal
`GameLoop` cadence without `TraceSessionLauncher.currentSpecialStagePassPacing()` or any
row-derived pacing. Failure keeps route reachability `unresolved`; it does not authorize
reuse of trace-specific special-stage pacing.

`S2OracleEngineCapture.DriverRequest` remains a source-owned synthetic seam. Static and
runtime guards must prevent reference fixtures or trace rows from being connected to it.
`S2AudioOracleComparator.compare(...)`, its fixture-derived `speedUpTick`, and
`S2OracleEngineCapture.capture(..., speedUpTick, ...)` are fenced diagnostic entry points.
The production comparator rejects their v1 schema and their result/logging path reports only
`fixture-assisted projection`, never authenticated `MATCH`.

If production startup cannot reach row 769 or later transitions, the result is `unresolved`
and the route blocker becomes implementation work. A smaller fresh-EHZ source-owned
scenario may test driver behavior, but it is labelled `unit/synthetic contract` and cannot
be relabelled as authentication of the complete-run window.

### Sonic 3&K

The primary authenticated lane is the separate existing complete-run contract
`s3k_locked_on_knuckles_superemeralds.v1`. It pins
`s3k-knuckles-complete-superemeralds.bk2`, SHA-256
`aa892856df22b7bb1fe5accb48db10b90dc26845d1dccee90352da30349f53cc`, with comparison rows
`[810,434417)`. TraceChaser already proves two identical full native captures: 434,417
frames, 254,921,281 events, maximum frame occupancy 1,446, and the source-correct
one-active/four-pending cutoff frontier. OpenGGF already has the strict raw adapter,
publication-inert preflight, state decoder/normalizer, native sound resolver, profile,
store, comparator, and CLI. Both fixed producer bindings and the canonical semantic
projection remain unavailable.

OpenGGF must replay the Knuckles movie from frame zero and retain its own audio state while
discarding only the same declared prepublication rows. It must not fresh-load AIZ or hydrate
the TraceChaser row-810 boundary. The first authenticated result is the first divergence from
the row-810 carried-in frontier.

The existing service-128 diagnostic is a different identity:
`s3k-complete-sonic-tails.bk2`, SHA-256
`82eabfbc65e33c160ce209baa1ca3f967cb677fe22350bc100625d8c41a8e1bf`, source frames
`[0,5400)`. Its character, route, frame count, and boundary are unrelated to the Knuckles
complete-run profile and must never be accepted under that profile.

`S3kOpenGgfAudioCapture` currently dispatches `referenceTick.mailbox()` and copies reference
mailbox state into its result. That path is explicitly a `fixture-assisted projection`. It
must be fenced from authenticated entry points and cannot emit `MATCH`. In the production
path, mailbox/request identity must be OpenGGF-produced and independently observed. A
missing policy-compliant pre-consumption M68K-to-Z80 observation remains `unresolved`; chip
writes or output bursts cannot stand in for it.

Accordingly, service 128 / source frame 242 remains a secondary investigation target, not a
`MATCH`-eligible endpoint of the Knuckles complete-run lane. Until a separately reviewed
Sonic/Tails TraceChaser profile exposes the missing pre-consumption request under its own
identity, the authority-correct outcome there is `REFERENCE_LIMITATION`. It is not a reason
to disconnect the row-810+ complete-run comparison.

If the normal OpenGGF product cannot reproduce either power-on/title route, that is an
upstream product gap. An AIZ fresh-level scenario may provide source-owned unit evidence but
cannot authenticate either complete-run boundary.

## TraceChaser boundary

TraceChaser is connected as the sole emulator/reference producer. Its reviewed buffered
GPGX audio observer and closed service manifests are reused as-is, including their exact
source-owned Z80 and M68K instruction observations. They are not the deferred general Lua
diagnostic-hook families prohibited by TraceChaser policy.

No new general M68K execute, memory-write, sound-request, or caller-configurable native
callback is part of the first implementation. A one-off Lua script may diagnose a source
boundary and must be discarded after use; its result cannot publish a canonical request
fixture or authorize replay into OpenGGF. Any future extension for the separate Sonic/Tails
frame-242 profile must follow TraceChaser's recorder-correctness, identity, native-gate,
independent-review, and explicit-publication-approval workflow.

The current complete-run native event graph must be projected without invention. A
reference-side pre-consumption request observation remains a declared limitation wherever
the approved graph lacks one. The design prefers an honest unresolved semantic field over
output-derived request inference or a policy exception hidden in tooling.

## Record and publication contract

TraceChaser's raw staging schemas are
`openggf.s2-complete-run-audio-raw.v2` and
`openggf.s3k-complete-run-audio-raw.v1`. The strict OpenGGF adapters validate exact ROM,
BK2, service-manifest, interval, driver-state range, event ABI, ordinals, ancestry, chip
writes, baseline, and cutoff before any canonical publication. The canonical consumer
schema is `complete_run_audio.v2`; its metadata pins producer kind, runtime/observer identity and
proof, profile, fixture, producer observation inventory, shared comparison inventory,
capabilities, segment inventory, capture counts, and terminal digest. Identity, inventory, or
schema mismatch makes the run invalid before comparison. Initial S2 and S3K profiles compare only
`FRAME_CHIP_EVENTS`; all other layers remain explicit limitations and their canonical fields are
null. The S3K reference projector retains native authentication sidecars without fabricating
semantic services, state, or admission evidence. S2 raw v2 now requires the source-observed
pre-row-769 begin row and native ordinal for every boundary service, carries that origin through
the managed observer's materialization/publication reset, and strictly validates generation
identity, token reuse, begin ordering, and cutoff continuity before projection. Its projector
retains baseline/frame/cutoff native sidecars while leaving semantic topology, services, and state
null. S2 production reference publication nevertheless remains unavailable until a real duplicate
capture is reviewed and its exact managed executable, native observer, and capability identities
are installed; this implementation deliberately does not pin the locally rebuilt executable hash.
The callback/OPENGGF metadata used by prefix projector unit tests is only a test seam and cannot
publish a production REFERENCE store. The uninstalled S1 complete-run profile likewise claims no
observed complete-run layers; this does not alter the legacy 14,690-tick music and 1,967-row SFX
oracle gates.

TraceChaser candidates are captured into explicit durable scratch outside both repositories.
They are never written directly into OpenGGF's fixture tree. OpenGGF's raw adapter and
canonical store publish create-new transactions only after complete validation; installing
or replacing a canonical reference remains a separate explicit user decision. OpenGGF
engine captures are regenerable outputs, not gameplay inputs. Durable validation evidence
commits only the small design/validation report and identity/hash manifest under the matching
architecture location. Raw captures remain in the named external task archive; transient
Maven output stays below the worktree's `target/`. No uncompressed trace payload is committed
under trace resources.

`docs/status/audio-frontier-log.md` must be updated whenever a frontier moves, a prior pass
regresses, or a complete sweep selects a new target. It records the command, commit and
worktree context, result category, comparison count, error count, and first divergent
frame/service/field. Existing S2 0-209 and S3K 0-127 wording must be corrected to
`fixture-assisted projection` until independent evidence exists.
This audio-specific record is additional to, not a replacement for, any general
`docs/status/trace-frontier-log.md` obligation triggered by a trace-replay sweep or change.

## Failure behavior

The runner or comparator fails closed on:

- ROM or BK2 identity mismatch;
- TraceChaser service-manifest, observer, capability, runtime, raw-schema, or event-ABI
  identity mismatch;
- unavailable or bypassed production startup;
- any trace/bootstrap/reference dependency on the engine producer;
- an input cursor jump, missing row, duplicate consumption, or incorrect press edge;
- fast-forward or an unexpected mode-specific input owner;
- zero or multiple presentation/audio advances for an outer row;
- observer replacement, mutation, or incomplete cleanup;
- a missing request observation needed by the declared comparison;
- malformed, duplicate, non-monotonic, incomplete, or out-of-window records; or
- unequal record cardinality or a first semantic divergence.

No failure mode falls back to fixture injection, reconstructed requests, event realignment,
or a weaker claim under the same result label.

## Verification design

The implementation must add or extend tests that prove:

1. BK2 conversion and `InputHandler` overrides preserve held/pressed edges across menu and
   gameplay modes.
2. TraceChaser's pinned S2/S3K capture runners and raw sinks produce streams accepted by the
   existing strict OpenGGF adapters, while changed identities, raw events, bounds, states,
   baselines, and cutoff frontiers fail before canonical publication.
3. The shared comparison inventory and producer-specific observation inventory are distinct parts
   of immutable profile/capture identity; `COMPARED` requires both producers observed, while
   observed-but-not-compared evidence remains valid and receives producer-local validation. The
   comparator rejects incompatible inventories, cannot report request `MATCH` when the
   reference layer is unavailable, and never treats equal empty arrays as proof. Raw S2/S3K
   streams lacking an approved request event project zero canonical requests/decisions and
   report that limitation explicitly.
4. The headless startup seam uses normal ROM selection, initialization, and `GameLoop` mode
   routing without trace bootstrap or direct level hydration.
5. Every BK2 cursor row owns exactly one production outer frame: one step, one outer-audio
   presentation/update, and one cursor advance, including legal/master/title/data-select
   modes. This is an outer-frame contract, not a claim that every mode advances one gameplay
   tick. Trace fast-forward and user-recording pump paths are disabled; any pause retention
   is source-cited and represented in the contract rather than fitted.
6. Audio observers are behaviorally inert, installed before first request, and detached on
   both success and failure.
7. A static authority guard rejects `TraceSessionLauncher`,
   `TraceReplaySessionBootstrap`, `TraceReplayDrive`, `TraceData`, trace payload readers,
   reference readers in the OpenGGF producer, any hardware-timing input to this producer,
   `S2OracleEngineCapture.DriverRequest`,
   `SPEED_UP_ROW`, `S3kOpenGgfAudioCapture`, and `referenceTick.mailbox()` dependencies from
   the authenticated OpenGGF producer. The fixed reference producer is explicitly permitted
   to invoke TraceChaser and the game-owned raw adapter. Existing diagnostic tools may retain
   their current dependencies only behind result types and labels that cannot emit
   authenticated `MATCH`. A separate manifest-authority test permits only the identity and
   segment-inventory projection described above and rejects every gameplay/bootstrap field.
8. S2 synthetic `DriverRequest` and S3K fixture-mailbox entry points cannot be called by an
   authenticated runner and cannot publish a `MATCH` result.
9. The existing `CompleteRunAudioComparator` rejects alignment shifts and reports the exact
   first request, admission,
   service, ownership, state, or write divergence.
10. ROM-gated feasibility tests prove the natural S2 and S3K routes separately before their
   full comparisons are enabled.
11. Fresh-CLI tests prove the closed registry loads both profiles and their pinned reference
    and OpenGGF producer bindings without ambient class initialization.
12. Existing S1 bounded driver-core/fixture-assisted results and focused audio/parity suites
   do not regress, without relabelling them as production-owned `MATCH`.
13. Delivery runs TraceChaser's relevant native unit/ROM gates plus OpenGGF focused tests,
    the ordinary suite, and the fresh-JVM `-Pguards` suite on
    JDK 21 with all three absolute ROM properties and verified skip counts.

## Remaining work beyond the first S2/S3K frontiers

After authenticated production capture is established, stages 1-4 and 6 continue across
source-owned scenarios for queue and priority behavior, pause bursts, 1-up save/restore,
fade and speed paths, SFX takeover/release, S3K modulation envelopes and every-frame
frequency behavior, ROM-read tables, and DAC/PCM timing. The gap ledger and behavior specs
must be refreshed as each claim changes status. Analog mix and subjective audibility remain
separate from driver-state equality and stay in the human listening queue.

## Rejected alternatives

- **Replay reference requests, mailbox bytes, or speed coordinates into OpenGGF.** This
  would make comparison data decide what happens and could only produce a fixture-assisted
  projection.
- **Duplicate TraceChaser's reference producer inside OpenGGF.** This would fork the reviewed
  service graph, build identities, native gates, and publication authority for no benefit.
- **Add an arbitrary TraceChaser M68K request-hook API.** The first implementation reuses the
  approved closed audio observer; any later profile extension must be source-owned and
  reviewed inside TraceChaser, not caller-configured by OpenGGF.
- **Infer hidden requests from service bursts or chip writes.** Consequences do not prove
  request identity or timing.
- **Use trace-hydrated level replay as natural startup.** It can validate behavior after a
  declared reconstruction boundary, but cannot authenticate the power-on/current-window
  route selected here.
- **Replace end-to-end evidence with fresh-level synthetic scenarios.** Those scenarios are
  valuable source-owned unit evidence, but prove a different boundary.

## Explicitly unresolved until implemented and observed

- a clean, headless frame-zero production startup shared with the normal engine;
- natural OpenGGF S2 and S3K complete-run production capture from frame zero through the
  pinned comparison boundaries;
- canonical TraceChaser-raw-to-`CompleteRunAudioTrace` semantic projection and fixed
  reference producer bindings for S2/S3K;
- natural S3K Sonic/Tails production request capture through source frame 242 / service 128;
- a policy-compliant reference-side pre-consumption M68K-to-Z80 request observation where
  the current producer lacks one;
- the S2 speed-up transition independently produced by OpenGGF on the identified route;
- the S3K mailbox/request identity independently produced by OpenGGF;
- S2/S3K fixed OpenGGF producer bindings and complete-run comparator execution;
- authenticated pause, 1-up, fade, full DAC/PCM, table, and modulation coverage; and
- the remaining human listening checklist.

These are design gates, not promises already satisfied by existing green tests.

## Expected ownership split

The implementation plan should complete the existing framework rather than create
`tools.audio.production` duplicates:

- retain TraceChaser's `CompleteRunAudioObserver`, S2/S3K observer profiles, capture runners,
  raw sinks, manifests, capability ledger, and native gates;
- retain OpenGGF's `CompleteRunAudioTrace`, store, comparator, tool, registry, profiles, raw
  adapters, decoders, normalizers, catalogs, and resolvers;
- extend that framework with an immutable comparison-layer inventory and explicit reference
  limitation reporting before any layer-scoped result can be published;
- add the fixed classes already reserved by `CompleteRunAudioProducerRegistry`:
  `S2CompleteRunReferenceProducer`, `S2CompleteRunOpenGgfProducer`,
  `S3kCompleteRunReferenceProducer`, and `S3kCompleteRunOpenGgfProducer`;
- add only the smallest game-neutral production BK2 runner and scoped observer adapter needed
  by both OpenGGF producers;
- replace each `UnavailableProducerBinding` with pinned runtime identity, observer proof,
  observer runtime identity, and capability summary derived from reviewed artifacts; and
- continue using `tools/audio/run_complete_audio_parity.sh` rather than adding parallel S2
  and S3K validation CLIs.

Shared OpenGGF runner/store/comparator code contains no game names, zone names, route
literals, sound IDs, or frame literals. Those facts remain in typed per-game profiles.
Production gameplay packages do not import the tooling schema. TraceChaser source is never
copied into OpenGGF; OpenGGF's fixed reference producers will invoke the pinned external
producer and consume its validated output.
