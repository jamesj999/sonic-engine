# Visual Trace Complete-Run Playback Design

Date: 2026-08-01
Status: Implemented

## Requirements

### Goal

The trace picker on the master title screen must replay a manifest-backed run
continuously across level/act loads, bonus stages, special stages, and the
manifest-declared movie tail. The visual path must preserve the headless run
chain's input, lifecycle, hardware-timing, PLC/DPLC/Kosinski, boundary, and
comparison contracts rather than treating each segment as an isolated launch.

### User-visible acceptance criteria

1. Every visually launchable run with a resolvable BK2 appears as one picker
   entry, including runs whose BK2 is stored inside the run directory. Catalog
   discovery may list a run whose deeper launch validation later reports a
   parser, BK2-bounds, profile, or row-count incompatibility; the tool must
   surface that diagnostic rather than silently omitting the run.
2. A selected run starts at segment zero and advances through every manifest
   segment without manual relaunch. Visual launch currently requires segment
   zero to be a level; unsupported bonus-/special-first manifests fail with a
   visible catalog/launch diagnostic.
3. Level-to-level handoffs work even when `GameMode` remains `LEVEL`; the
   destination zone/act and distinct load generation are verified, except for
   a walker-classified lag-only same-level continuation.
4. Bonus- and special-stage entry verifies the manifest entry request and
   destination identity. Destination BK2 row zero and return-level row zero are
   each owned exactly once.
5. Special-stage segments use a segment-local BK2 row clock and recorded lag
   pacing for S1, S2, and S3K while the shared playback cursor is frozen.
6. Return boundaries compare the same manifest-recorded position/checkpoint,
   rings, emeralds, and next-act contracts as the headless chain.
7. Mismatches after a handoff are attributed to the destination segment in the
   HUD, camera focus, error counters, and pause-on-first-error behavior.
8. The final segment is followed by any manifest-declared terminal movie tail;
   completion occurs only after the expected end mode is reached.
9. Escape exits cleanly from every run phase and returns to the master title.
10. A wrong or frozen transition fails closed with a persistent diagnostic
    after a manifest-derived step cap instead of waiting forever.

### Safety and fidelity constraints

- Trace physics, aux, transition, and dynamic-art values remain comparison-only.
- Recorded controller rows may drive input, as they already do for ordinary
  replay. They must not hydrate gameplay state.
- Hardware timing may only delay readiness of matching, prepared,
  production-submitted ROM jobs under the documented cross-game timing port.
- A trace must never create PLC, DPLC, Kosinski, object, or gameplay work.
- Shared runtime code receives structural lifecycle facts, not game, zone,
  route, or known-trace carve-outs.
- Native fades, title cards, results, level loads, and stage transitions remain
  engine-owned and run without per-frame gameplay comparison.
- Transition wait steps advance on admitted `GameLoop` steps, independently of
  the BK2 cursor, because the cursor intentionally freezes in several modes.

### Non-goals

- Per-frame gameplay comparison of S1/S2/S3K special-stage interiors. The
  existing `ADVANCE-GAMEPLAY-UNCOMPARED` policy remains; advertised dynamic-art
  rows are still compared.
- Rewind across run segment boundaries.
- Changing recorder schemas or regenerating fixtures.
- Making the existing red headless fleet green.
- Hydrating return positions, rings, emeralds, or stage results from manifests.
- Supporting bonus- or special-stage segment zero in the visual launcher.

### Assumptions

- `run_manifest.json` segment order and BK2 offsets are authoritative input
  alignment metadata after `TraceRunManifest.validate` succeeds.
- `TraceRunReplayWalker` remains the shared owner of segment planning, expected
  modes, level identity helpers, transition budgets, timing schedules, dynamic
  art lifecycle data, boundary windows, and terminal-tail planning.
- The production level-load seams already activate scheduled playback before a
  same-step destination player tick; they can report the newly loaded identity
  before consuming a pending target-aware rebind.
- A special-stage trace profile can be reduced to a read-only row-admission
  view exposing pacing/lifecycle decisions without introducing a gameplay
  comparator.

### Risks

- A level load may occur inside the source capture's recorded tail, before the
  source comparator reports exhaustion.
- Rebinding after title-card fallthrough can duplicate destination row zero.
- Rebinding before source post-finish publication can attribute PLC/DPLC/Kos
  edges to the wrong comparison generation.
- A death/reload can steal a generic “next level load” rebind unless both the
  expected destination identity and the boundary request are validated.
- Long runs amplify one stale observer, override, timing schedule, or ledger
  generation into misleading later failures.

## Exploration Synthesis

Two independent repository reviews agreed that the current visual run branch
is a scaffold rather than an end-to-end implementation.

### Existing reusable work

- `TraceCatalog`, `TraceEntry`, and `TestModeTracePicker` already represent a
  run as one picker entry.
- `TraceSessionLauncher` already has a run launch branch, per-segment comparator
  rebuilding, run-wide hardware timing, external dynamic-art segment ownership,
  and post-production deferred handoff plumbing.
- `PlaybackDebugManager.scheduleSessionAtNextLevelLoad` activates at common
  level-load seams before same-step destination gameplay.
- `TraceRunReplayWalker` owns validated `SegmentPlan`s, its stable
  `BoundaryProbe`, expected modes, hardware timing, dynamic-art windows, level
  identity helpers, boundary windows, inter-level budgets, and terminal tails.
- The headless `AbstractRunChainTest` proves ordinary level loads, bonus
  entry/return, special-stage local input, lag rows, return-state checks, runtime
  dynamic-art gap evidence, and terminal tails.

### Confirmed gaps

- Run BK2 discovery accepts only `<game>/_movies`; committed local-run movies
  are skipped by the visual picker.
- `RunSegmentAdvancer` observes only `(GameMode, cursorFrame)`, so it cannot see
  a same-mode level replacement or validate zone/act/interior identity.
- The shared-tail advance hook is bypassed by early returns and runs after
  destination fallthrough production, making its rebind too late.
- Run special stages have `ssTrace == null`; neither the standalone special
  input gate nor `PlaybackDebugManager` drives their input.
- Destination comparison/timing/dynamic-art ownership can remain attached to
  the source through the destination's first production row.
- The visual branch does not perform headless return-state or runtime
  dynamic-art gap checks and fades before a declared terminal tail.
- Escape handling is gated to `LEVEL` and current tests invoke hooks in an ideal
  order instead of exercising real early-return paths.

### Recommendation

Keep this as a separate follow-up feature. Introduce one production run
coordinator driven at explicit admission/lifecycle seams, reuse the manifest
policies in `TraceRunReplayWalker`, and leave `TraceSessionLauncher` responsible
for fixture/comparator/HUD presentation.

## Architecture Decision

### Ownership and phases

Add `TraceRunPlaybackCoordinator` under `com.openggf.trace.replay.runs` as the
single production owner of visual run structure:

```text
CURRENT_SEGMENT
    -> TRANSITION_GAP
    -> DESTINATION_READY
    -> CURRENT_SEGMENT
    -> TERMINAL_TAIL
    -> COMPLETE | FAILED
```

`TraceSessionLauncher` owns the coordinator and translates its typed actions
into existing playback, timing, comparator, HUD, camera, and cleanup operations.
`GameLoop` and level-load owners expose only value-only lifecycle notifications.
No manifest or comparison value enters gameplay owners.

The coordinator is engine-agnostic policy, not a second copy of
`AbstractRunChainTest`. Both the visual launcher and headless chain use the
same coordinator transitions, destination-admission receipts, row-admission
policies, boundary comparator, and dynamic-art journal comparator.
Transcript-equivalence begins after each adapter's initial fixture/session
bootstrap and covers boundary signals, input-row ownership, timing, dynamic-art,
comparison, and tail actions. The adapters may differ in engine stepping,
rendering, and pre-existing test-only bootstrap/alignment policy; those legacy
headless gameplay writes are explicitly outside the shared transcript, are
never requested by the coordinator, and are unavailable to the visual adapter.
Tests assert both the shared action sequence and that no coordinator action can
encode an RNG/VBlank/V-int/gameplay-state write. The existing headless driver is
reduced around this shared handoff policy as the visual implementation lands;
it does not remain an independent source of structural decisions.

### One stable observer

One `TraceRunReplayWalker.BoundaryProbe` is installed as the playback observer
for the entire run. Its delegate is the current `LiveTraceComparator`; the raw
observer is never replaced during a handoff.

- The next transition is armed when the current segment becomes active, not
  when its comparator exhausts. This observes transient bonus/special requests
  and permits a next-level rebind before a load inside the source trace tail.
- Before any destination production, the probe delegate is detached from the
  source. It is attached to the destination comparator before destination
  production when row zero has not run, or after the one unavoidable
  fallthrough row with `initialCursor=1`.
- The source delegate must never receive destination row zero.
- The probe's before-frame hook remains run-wide and drives the timing
  coordinator before whichever segment delegate is active.

`BoundaryProbe` pins the delegate that prepared a row until that row's
`afterFrameAdvanced` callback completes. A load or mode change inside an open
production iteration may stage the next delegate, but cannot redirect the
source row's completion callback to it. Staged actions commit atomically after
publication; on failure none of the destination owners remain installed.

### Target-aware level prearm

When a current segment becomes active and its next segment is a level, the
launcher prearms a target-aware load descriptor immediately. This descriptor
can observe a matching load before the source trace tail is exhausted without
prematurely seeking the shared movie. Once the source closes, it either commits
the already-observed load or becomes the active
`scheduleSessionAtNextLevelLoad` rebind. The guard contains only structural
expectations:

- destination ROM zone and zero-based act resolved through the active
  `TracePlaybackProfile`;
- loaded-level object must differ from the source object unless the walker
  classifies a lag-only same-level continuation;
- a manifest transition, when present, must have latched the expected
  `entry_kind` inside `BOUNDARY_WINDOW_FRAMES`;
- the level-load activation must occur before the independent transition-step
  cap expires.

Both production level-load seams pass an opaque load generation, typed level
identity, and semantic load cause to target-aware activation. A matching load
observed while source comparison remains active is remembered but does not
change input/comparison ownership until the source row range closes. A
non-matching load leaves the descriptor pending and emits no input. This
prevents an unrelated death/reload from stealing a later segment's BK2 offset.
Lag-only continuations do not schedule a load; they rebind at the same identity
and validated cursor boundary.

### Interior identity and boundary validation

`RunPlaybackObservation` contains:

- current `GameMode` and shared BK2 cursor;
- monotonically increasing admitted-step ordinal;
- loaded-level object identity, engine/ROM zone, and act;
- active bonus identity: loaded bonus zone/act and `BonusStageType`;
- active special-stage index from `SpecialStageProvider.getCurrentStage()`;
- open-production and current-segment-exhausted flags.

A destination is admitted only when all manifest identity fields match:

- level: profile-resolved zone/act plus load identity rules above;
- bonus: `BONUS_STAGE`, manifest zone/act, and `bonus_stage_type`;
- special: `SPECIAL_STAGE` and `special_stage_index`.

The armed `BoundaryProbe` must also latch the manifest `entry_kind` in the
walker window. Mode alone can never admit an interior. A missing, late, or
wrong request reaches `FAILED` at the transition-step cap.

Extend the walker's semantic boundary mapping to every schema-supported entry:

| `entry_kind` | Required production signal |
|---|---|
| `starpost_bonus` | transient bonus request plus matching bonus identity |
| `giant_ring`, `starpost_special` | transient special request plus matching stage index |
| `stage_exit` | interior exit followed by matching return-level load/mode |
| `level_advance` | `RunLevelLoadCause.LEVEL_ADVANCE` plus matching new level |
| `death_restart` | `RunLevelLoadCause.DEATH_RESTART` plus a new load generation and matching level |
| no transition record | matching adjacent level load, with no invented entry event |

`RunLevelLoadCause` is emitted at the existing production-owned respawn,
next-act/results, and ordinary level-load seams. It describes why the engine
already chose to load; it never causes a load. Boundary-window validation uses
the physical BK2 row latched by the observer or load receipt.

### Synchronous seam order and typed actions

The coordinator exposes two non-interchangeable action classes:

1. `ImmediateAdmissionAction` must execute synchronously before the next
   `syncPlaybackInputBridge()` or production call. It detaches source ownership,
   seeks/activates input, opens destination timing/dynamic-art ownership, and
   attaches a row-zero comparator when applicable.
2. `PublishedIterationAction` executes only after the current production
   iteration publishes. It closes the represented source row/generation,
   advances special local clocks, and records comparison/gap evidence.

The exact bonus/title-card path is:

```text
engine mode/request change
-> coordinator.afterModeChange()
-> apply ImmediateAdmissionAction
-> syncPlaybackInputBridge()
-> destination fallthrough production
-> publish production
-> coordinator.afterProduction()
```

The exact loaded-level path is:

```text
engine completes load
-> coordinator.beforeLoadedLevelActivation(identity)
-> target-aware playback activation/seek
-> detach source, open destination timing/dynamic-art, attach row-0 comparator
-> apply playback input to rebuilt player
-> optional same-step destination production
-> publish production
```

If an existing path has already produced destination row zero, the source is
detached before that production by the mode/load seam, and the destination
comparator attaches afterward at `initialCursor=1`. Any derived
`framesConsumed` outside 0/1 fails; row zero is never replayed.

Every commit action returns a `DestinationAdmissionReceipt` containing the
segment index, input clock (`SHARED` or `SPECIAL_LOCAL`), absolute physical BK2
row, `rowsConsumed` (only 0 or 1), load generation/interior identity, timing
schedule generation, and dynamic-art generation. The receipt, rather than an
inference from the shared cursor, is the authority for comparator start:

- level loaded during a source tail: remember the load, finish the source
  range, then attach destination at row zero before title release (`0`);
- ordinary level load after source close: activate at the load seam before the
  first destination tick (`0`), or record the one unavoidable fallthrough
  publication and attach at row one (`1`);
- bonus title-card exit: apply deferred setup, commit destination owners, attach
  row-zero comparator, sync input, then fall through (`0`);
- special entry: commit local row zero and timing/dynamic-art owners before the
  provider update; there is no gameplay comparator (`0`);
- return-level title-card exit: preseek while the interior owns no shared
  gameplay cursor, commit return owners before fallthrough (`0`), with the
  explicit row-one fallback only when a production receipt proves row zero
  already published (`1`).

An outer `try/finally` around each admitted `GameLoop` step calls the active
session's all-mode `afterStep` exactly once, including every current early
return and exception. Transition budgets use this step ordinal, not the frozen
BK2 cursor.

### Boundary comparison parity

Extract the headless return checks into a comparison-only
`TraceRunBoundaryComparator` shared by headless and visual paths. It consumes
only engine snapshots plus the manifest transition and reports ordinary
`FrameComparison` diagnostics for:

- positional restore for `starpost_special`;
- checkpoint restore followed by return-frame-zero centre-position comparison
  for `starpost_bonus`;
- next-act identity for S1-style `giant_ring` return;
- return-frame-zero centre-position comparison for S3K-style `giant_ring`
  (`RINGS_EMERALDS_ONLY`);
- rings for positional, checkpoint, and rings/emeralds-only returns, where the
  manifest exit sample and the settled engine return snapshot are co-temporal;
  `NEXT_ACT` omits the ring field because its manifest value is the interior
  stage tally sampled before the fresh act load clears the level ring count;
- live emerald count only when the interior was organically reproduced, and
  otherwise the recorded `emeralds_after == emeralds_before + 1` manifest
  invariant used by the current advance-uncompared special-stage policy.

It never writes gameplay state. The destination is not declared active until
its boundary comparison has been published to the destination HUD/session.

### Special-stage local rows

Add sealed/read-only `TraceRunSpecialStageRows`, selected by `trace_profile`:

- `s1_special_stage` wraps `Sonic1SpecialStageTraceData`;
- `s2_special_stage` wraps `SpecialStageTraceData`;
- `s3k_special_stage` wraps `S3kSpecialStageTraceData`.

It exposes metadata, row count, and a profile-owned
`SpecialStageRowAdmission` containing `executeGameplay`, optional synthetic PLC
phase, `advancePreservedVblankIfUnchanged`, and `admitHardwareTiming`. This
captures the existing cross-game differences rather than reducing them to one
lag boolean:

- S1 lag rows skip gameplay, emit the advertised lag lifecycle row, and advance
  the preserved VBlank clock; ordinary rows run gameplay and advance that clock
  only when the engine did not.
- S2 lag rows skip gameplay and emit an advertised lag lifecycle row, without
  S1's explicit preserved-clock advance; ordinary rows run gameplay.
- S3K's current schema has no lag field, so every represented row runs gameplay
  and uses the generic preserved-clock fallback.

Input comes from the shared movie at `bk2_frame_offset + localRow`; press edges
use the preceding physical BK2 row. Every row installs the logical override in
a `try/finally`, so normal completion, early mode exit, row exhaustion, Escape,
and production failure all clear it. Hardware timing is admitted exactly once
for every represented row selected by policy, including skipped lag rows, and
advertised special DPLC comparison uses the same local index. Row exhaustion
before mode exit enters native transition stepping with overrides disabled;
mode exit before row exhaustion fails with the remaining-row diagnostic.

### Trace authority and production clock state

Inter-segment visual handoff never calls `applyInitialRngSeedForReplay`, writes
VBlank counters from manifest budgets, primes slot `V_int_run_count`, restores
positions, or otherwise hydrates gameplay state. Those existing headless
bootstrap/alignment operations are not part of the visual parity target and
must not leak through the shared coordinator. The allowed initial standalone
launch bootstrap remains owned by the already-existing trace-session contract;
there is no new bootstrap at later segments.

Accordingly, “supports a complete run” means the visual tool preserves input,
ordering, comparison, and transfer ownership and reports organic engine
divergence; it does not promise that every currently red engine route reaches
every later segment. If missing production modelling (for example S3K's
free-running `V_int_run_count`) changes an outcome, the boundary comparator or
step cap reports it and the run remains diagnostic. Production clock/RNG parity
is a separate engine fix, never a trace-fed workaround. Catalog visibility is
therefore distinct from launch validation and from a green end-to-end result.

### PLC/DPLC/Kosinski boundary contract

The required order is:

```text
source final production publication
-> source comparison/dynamic-art close and timing verification
-> native transition gap
-> next timing schedule handoff
-> destination dynamic-art generation open
-> destination admission and input
-> destination production publication
-> destination comparison publication
```

Extract the headless runtime gap journal probe into a comparison-only
`TraceRunDynamicArtGapComparator`. For schema 2 it compares the visual
execution's actual `DynamicArtLifecycleService.gapTransitions()` against the
manifest gap records, including edge ordinals, before/after outstanding ledger
fingerprints, forwarded/completed transfer edges, and destination ownership.
For schema 1 it still asserts structural close/gap/open ordering and schedule
verification but has no manifest gap payload to compare. Production remains
the only source of jobs.

A runtime dynamic-art mismatch in an otherwise uncompared special stage is
attributed to that segment/local row in the persistent HUD diagnostic and
honours pause-on-first-error even though the shared playback cursor is frozen.

### Terminal tail

After the final compared row publishes, ordinary playback comparison is ended:
the probe delegate is detached, dynamic-art segment is closed, and normal
playback input ownership is stopped. The terminal-tail driver then applies
remaining BK2 rows by absolute physical index in a per-row `try/finally`, with
no gameplay comparator and no second level input owner.

At movie exhaustion, a declared expected mode is checked immediately. A wrong
mode fails on that step; it does not wait for an additional cap. An unspecified
terminal mode preserves immediate completion with no tail replay/assertion.

### Failure presentation and cleanup

Failure enters a held `FAILED` state before teardown. The launcher stores a
persistent `TRACE FAILED` diagnostic (segment, expected/actual identity, cursor,
and step count) that the title/picker overlay can render after active gameplay
bindings are cleared. It remains until Escape/confirm returns to the picker or
a new selection replaces it.

Cleanup is idempotent and clears, in order-safe `finally` blocks, any open
production marker, special/tail input override, comparator delegate, scheduled
level rebind, timing schedule, dynamic-art generation, ghost/camera/HUD/audio,
and temporary configuration. Escape uses this path from all modes.

## Feature APIs

- `TraceCatalog.resolveRunBk2` tries the shared movie directory first, then a
  normalized local path beneath `runDir`; absolute or escaping `source_bk2`
  paths are rejected.
- Run-manifest schema-2 dynamic-art descriptors are parsed through the same
  explicit snake_case request/descriptor mapping as top-level gap transitions;
  committed manifests must not depend on Jackson record-name inference.
- `TraceRunSpecialStageRows.load(profile, directory)` returns the typed lag
  and admission-policy view.
- `TraceRunPlaybackCoordinator` exposes:
  - `activateInitialLevel(observation)`;
  - `beforeAdmission(observation)`;
  - `beforeLoadedLevelActivation(identity, observation)`;
  - `afterModeChange(observation)`;
  - `afterProduction(observation)`;
  - `afterStep(observation)`;
  - `specialStageRow()` / `publishSpecialStageRow()`;
  - `abort()`.
- Actions carry segment index, BK2 offset, `rowsConsumed`, and lifecycle kind,
  never gameplay values.
- `TraceRunBoundaryComparator` and `TraceRunDynamicArtGapComparator` are shared,
  comparison-only helpers used by both headless and visual paths.
- `RunBoundarySignal`, `RunLevelLoadCause`, and
  `DestinationAdmissionReceipt` make transition cause and row ownership
  explicit and transcript-testable.

## Edge Cases and Required Proofs

- A level load occurring before source comparator exhaustion preactivates the
  correct destination and proves the source observer never sees destination
  row zero.
- Wrong bonus type, wrong special-stage index, and boundary requests outside
  the allowed window fail closed.
- A lag-only same-level continuation rebinds without a new level object.
- `level_advance`, `death_restart`, and transition-less adjacency each require
  their own semantic production signal/identity proof.
- Bonus-/special-first manifests are rejected by visual launch with a stable
  diagnostic.
- Return-state comparisons match headless policy.
- A final LEVEL tail proves ordinary playback input is not applied twice; movie
  exhaustion in the wrong final mode fails immediately.
- Runtime gap journals, not just fixture structure, are compared across PLC,
  DPLC, and Kosinski boundaries.
- Abort during open production and during an installed special/tail override
  clears every owner.
- A real launcher/GameLoop integration test exercises early returns, not only a
  coordinator simulation.
- Catalog coverage includes a committed S2 local-BK2 run.
- That committed schema-2 S2 run proves nested initial-ledger requests retain
  source domain, addresses, VRAM destination, transfer identity, and provenance.
- S1/S2 lag differences, S3K no-lag rows, early special exit, and row exhaustion
  before exit are tested independently.
- Failure injection covers source close, timing handoff, dynamic-art open,
  comparator creation, special-row load, terminal completion, and repeated
  abort after each partial commit.
- Engine-backed traversal is attempted for one committed run per game; a known
  organic engine mismatch is a named diagnostic outcome, not permission to
  hydrate trace state.
