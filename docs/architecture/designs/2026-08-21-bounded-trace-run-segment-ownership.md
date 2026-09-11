# Bounded trace-run segment ownership

## Status

Implemented and feature-branch validated on 2026-08-22. Phase-one descriptor
planning and descriptor-backed catalog validation landed first; phase two now
uses those descriptors for complete-run replay and owns at most one eager
segment payload. The acceptance evidence is recorded in
[`2026-08-22-active-segment-ownership-validation.md`](../validation/trace/2026-08-22-active-segment-ownership-validation.md).
Independent final review and main-workspace integration remain pending.

## Problem

`TraceRunReplayWalker.plan()` retains a `TraceData` for every segment in a run.
Each `TraceData` eagerly owns its parsed physics rows and auxiliary-event
objects. The 67-segment Knuckles super-emerald run contains 1,607,123,581
uncompressed payload bytes, of which 1,548,327,089 are auxiliary JSONL.

A forced-GC class histogram taken after all 67 plans were built reported
1,094,956,904 live bytes. Direct `TraceEvent` implementations accounted for
399,294,016 bytes (36.47%), `CompactFieldMap` for 86,444,992 bytes (7.89%),
and physics/special-stage frame records for 62,909,200 bytes (5.75%). Common
trace-dominated arrays, strings, boxed integers, lists, and map storage
accounted for another 535,363,856 bytes (48.89%). By contrast, hardware-timing
classes occupied 102,864 bytes (0.0094%).

The ownership boundary, rather than parsing throughput, is therefore the
measured problem.

## Historical phase-one checkpoint

At the phase-one checkpoint, `TraceRunReplayWalker.planDescriptors()` performed
the existing validation scan sequentially and published payload-independent
summaries. `TraceCatalog.validateRunLaunch()` consumed those summaries, while
the then-live `prepareRunLaunch()` API and replay callers still retained the
eager `plan()` path. Phase two subsequently removed that API and those eager
callers; this paragraph records the intermediate measurement state, not the
current interface.

On the real 67-segment Knuckles super-emerald run, both planners first ran
unmeasured whole-run warmups whose graphs were released before separate
forced-GC measurement phases. The warmed measurement reported 1,087,200,800
retained bytes for eager planning and 8,660,152 bytes for descriptor planning.
The descriptor graph retained 1,078,540,648 fewer
bytes (99.20%) while representing the same 409,630 rows. This is a planning
and catalog-validation result, not a replay-memory result: at that checkpoint,
live, visual, and audio replay memory was unchanged. The active-payload work
below subsequently implemented the replay ownership boundary.

## Validated implementation result

The phase-two implementation preserves the approved eager comparator,
bootstrap, phase, timing, and special-stage objects behind a guarded,
closeable active lease. Production, headless, visual, and complete-audio
owners detach their aliases and close the old lease before another payload is
retained. Descriptor-only planning has no eager launch escape path.

Two independent warmed, forced-GC forks measured descriptor graphs of
9,253,296 and 9,252,768 bytes. Their maximum installed consumer graphs were
170,550,952 and 170,910,128 bytes, reductions of 84.31% and 84.28% from the
fixed 1,087,200,800-byte eager baseline. Both maxima came from `s3k-59-soz_2`.
Unlike the superseded initial benchmark, every installed sample retains the
real catalog `TraceEntry`, parsed `Bk2Movie`, complete descriptor sets, a real
`TraceSessionLauncher`, the exact headless harness and `LiveEngineFixture`, and
the installed ordinary or special consumer graph. The benchmark asserts each
consumer's identity in the launcher's and harness's actual owner field rather
than retaining parallel consumer roots. Representative S1 and S2 special
stages remain in both runs, including the S2 recorded-pass binder.

The deterministic resource oracle passed with three failure-atomicity controls
in addition to its 100-cycle matrix: each matrix run completed 100 cycles
per ordinary and special-stage shape and observed exactly 1,000 reader opens
and 1,000 closes. After the later enforcement-only corrections through
`7b16dc94b`, the authority guard passes 35/35, authority plus ownership passes
38/38, the combined reader gate passes 44/44, and focused migration tests pass
179/179. Those corrections did not change the production ownership graph or
the retained-memory samples. The recorded 67-segment oracle consumed all 1,653 AIZ
rows, retained the first `camera_x` mismatch (`0x1300` / `0x1308`), the terminal
segment-0 `giant_ring` miss, both unmatched timing completions, and zero
dynamic-art gaps/failures. Fresh current-main and feature all-game trace sweeps
reported the same ten red identities and no errors; the feature sweep launched
all 165 of its XML classes, and separate replay-family accounting proved every
baseline-green replay completed without starvation. The exact JDK 21 default-
suite comparison found one raw feature-only order-dependent red, which identical
predecessor controls reproduced on synchronized `develop`; exhaustive shared-
red comparison and those controls found zero feature-attributable new or
worsened failures. Focused groups retained the same frontiers after that suite.

This validation does not certify the quarantined phase/bootstrap authority
listed below. The reachability guard also lacks a historical live-leak RED,
although its retained-comparator mutation proves sensitivity to the extra-root
shape. These are recorded evidence and authority debts, not additions to the
approved surface.

## Full design decision

Retain compact run descriptors globally and retain at most one existing eager
segment payload while a run is preparing or driving that segment. This phase is
an ownership-only migration: it removes whole-run reachability without changing
the row representation or generalising any trace consumer.

Planning continues to scan and validate each segment in manifest order. It
publishes the phase-one `TraceRunSegmentDescriptor` shape, extending it only
with the exact existing coordinator scalar `levelLoopRowCount`:

- manifest segment, directory, metadata, row count, and opening physics row;
- local-row-to-raw-frame mapping and a lag bit set indexed by local row;
- hardware-timing schedule and terminal dynamic-art ledger;
- entry and exit boundaries; and
- the already-present `SegmentPlan.executionPolicy` and newly retained
  `TraceRunReplayWalker.levelLoopRowCount` scalars, relocated unchanged and
  consumed only by the run coordinator.

The descriptor gains no per-row phase vector, bootstrap projection, auxiliary
event, special-stage row, parser, reader, or generic replay-data surface. Its
component types remain guarded by an exact whitelist and a transitive
reachability test.

Replay owns a public final, run-specific `ActiveSegmentPayload` whose
construction remains confined to the run walker/factory. This narrow visibility
is required because production launch control and the headless/visual/audio
harnesses live in different Java packages. It is not a generic replay-data
interface: it exposes only guarded `trace()` and `specialStageRows()` accessors
for the two existing eager types, descriptor identity, `isClosed()`, and
`close()`. Access after close fails. For any
non-special-stage segment (level, presentation bridge, or `bonus_stage`) it
contains the existing eager `TraceData`. For a special-stage interior it is a
composite containing both objects the current driver already requires:

- metadata-only shared `TraceData`, including validated dynamic-art auxiliary
  state and the segment hardware-timing schedule; and
- the game-owned `TraceRunSpecialStageRows`, including S2 pass binding and its
  spill-normalised dynamic-art rows.

The special-stage factory deliberately performs the same two reads as the
current `loadSegmentPayload`: the shared and profile readers may parse overlapping
auxiliary content, but both graphs exist only for the active segment. Removing
that duplication is not part of this ownership proof.

`ActiveSegmentPayload` implements `AutoCloseable` only as a lifetime boundary.
Its constructor and mutable fields are not public, and the factory accepts only
a validated `TraceRunSegmentDescriptor`. Cross-package callers acquire it only
through the public run-owned facade
`TraceRunReplayWalker.openActiveSegment(descriptor, segmentIndex)`. The eager
readers close their files during construction; `close()` idempotently clears the
wrapper's strong references so the graphs become collectible. A failed
composite construction publishes no wrapper and retains no partial payload.

### Authority quarantine

The current replay path contains pre-existing phase and bootstrap authority that
does not satisfy the repository's present trace-authority rule, including
physics/aux-derived loop selection and metadata RNG seeding. This performance
phase does not certify, copy, compact, broaden, or otherwise legitimise that
debt. It preserves the current eager objects and leaves these APIs and their
lookup semantics unchanged:

- `LiveTraceComparator`;
- `TraceReplaySessionBootstrap` and `TraceReplayBootstrap`;
- `TraceReplayRowPolicy`, `TraceBinder`, and
  `LoadQueueComparisonProjection`; and
- `TraceStructuralRowComparator` and `TraceRunSpecialStageRowDriver`.

New code may pass only the active payload's existing `TraceData` or
`TraceRunSpecialStageRows` into those existing call sites. The lease accessors
and open facade have an exact source/bytecode allowlist:

- `com.openggf.TraceSessionLauncher`;
- `com.openggf.tests.trace.runs.AbstractRunChainTest`; and
- `com.openggf.tests.trace.runs.VisualRunReplayHarness`, which also owns the
  complete-audio replay path.

The only test classes that may exercise the API directly are:

- `com.openggf.trace.replay.runs.TestActiveSegmentPayload`;
- `com.openggf.trace.TestTraceReaderLifecycle`;
- `com.openggf.TestTraceSessionLauncherActivePayloadLifecycle`;
- `com.openggf.tests.trace.runs.TestHeadlessRunActivePayloadLifecycle`;
- `com.openggf.tests.trace.runs.TestVisualRunActivePayloadLifecycle`;
- `com.openggf.tests.trace.runs.TestTraceRunActivePayloadOwnership`;
- `com.openggf.tests.trace.runs.TestTraceRunActivePayloadPerformance`; and
- `com.openggf.tests.trace.runs.TestActiveSegmentPayloadAuthorityGuard`.

These tests cannot relay the payload to another production or harness class.
Mutation fixtures are analysed as explicit guard inputs and are not members of
the repository allowlist. A CI guard rejects every other direct
call, method reference, reflective lookup, or string-named reflective access to
`openActiveSegment`, `trace`, or `specialStageRows`; it also locks constructor
visibility and the exact public method surface. Descriptor metadata
may replace a payload read only where the old launch path already consumed that
same metadata without hydrating gameplay. RNG and all other gameplay bootstrap
calls remain confined to the active eager payload path. The only relocated
derived values are the already-consumed `executionPolicy` and
`levelLoopRowCount` coordinator scalars; neither gains another consumer or a
generic descriptor query. True row streaming requires a separate authority
audit/remediation first; it is not silently implemented by adding an
authority-bearing forward-window interface.

### Alternatives considered

1. **Selected: one active eager segment payload.** This removes the measured
   whole-run graph while leaving comparison, bootstrap, phase, report, S2 pass,
   and spill-normalisation semantics unchanged.
2. **Stream ordinary physics and auxiliary rows now.** This would require a new
   phase/bootstrap abstraction around pre-existing authority debt and would
   either preserve a prohibited path or change replay scheduling. It is deferred
   until that authority is separately resolved.
3. **Stream all ordinary and special-stage formats now.** In addition to the
   authority blocker, this requires three profile migrations and an S2 two-pass
   binder/normalisation design. It adds risk that the measured ownership change
   does not need.

## Consumer contract

| Owner | Required data | Lifetime |
|---|---|---|
| Run plan, manifest, hardware timing, playback coordinator | Existing descriptor components only | Whole run |
| Ordinary comparator/bootstrap/policy/dynamic-art consumers | Existing eager `TraceData` API | Active ordinary segment only |
| Special-stage driver | Composite shared `TraceData` plus game-owned special-stage rows | Active special-stage segment only |
| Visual run/audio `frameView` | Segment row range plus lag bit indexed by local row | Whole run descriptor |
| Trace catalog | Metadata and row counts | Whole run descriptor |

Row identity remains explicit. BK2 membership and visual/audio lag lookup use
`localRow = bk2Cursor - segment.bk2FrameOffset()`. Auxiliary and hardware-timing
lookups use `descriptor.rawFrames().get(localRow)`. These identities are never
interchanged.

An admission receipt may report `rowsConsumed == 1`. The destination payload is
opened before its consumers attach; existing comparator adoption then advances
the eager payload exactly as today and compares row zero's dynamic-art state
before continuing at row one. Return-boundary comparison may use the descriptor's
opening physics row and metadata, while adopted-row comparison continues to use
the active eager payload.

Hardware timing stays globally retained because its measured footprint is
negligible and it coordinates matching production-submitted work across
boundaries. The compact lag bit set also remains global because `frameView()`
must answer arbitrary gap and handoff cursors. It admits only the existing lag
loop and carries no gameplay value.

Standalone replay keeps eager `TraceData` and rewind unchanged. Run sessions
continue to disable rewind across structural segment boundaries.

## Active-owner lifecycle

For production, headless, visual, and complete-audio run drivers:

1. plan and retain descriptors only;
2. open segment zero before the existing pre-load configuration call and retain
   it through title-card presentation and replay bootstrap;
3. retain the source payload through source-tail capture, observer detachment,
   special-stage verification, dynamic-art closure, terminal comparison, and
   gap-journal publication;
4. detach every consumer alias -- comparator, structural comparator, special
   driver/pass binder, dynamic-art comparison, boundary observer, HUD/camera
   model, fixture, and launcher field -- then close the source in a
   failure-preserving `finally` before entering the transition gap;
5. while no payload is active, advance presentation and resolve `frameView()`
   solely from descriptors;
6. perform timing handoff, open exactly one destination payload, apply any
   return-boundary/adopted-row comparison, and attach destination consumers; and
7. close the final payload before terminal-tail playback.

Launch failure, session abort, user exit, comparison failure, production
failure, harness failure, and repeated teardown all close the active payload
idempotently. Cleanup exceptions are suppressed onto the primary failure.

## Implementation boundary

Phase two proceeds in the following compatibility-preserving order:

1. extend descriptors with the exact existing coordinator row-count scalar;
2. introduce the closeable active-payload holder, one-descriptor factory, and
   exact open/close counters for lifecycle tests;
3. add descriptor-only catalog launch preparation alongside the eager path;
4. atomically migrate production plus visual/complete-audio acquisition,
   ownership transfer, minimum failure cleanup, and arbitrary BK2 `frameView()`
   lookup to descriptors without changing eager comparator/bootstrap APIs;
5. migrate headless chain ownership;
6. harden exhaustive visual and complete-audio failure cleanup; and
7. remove the eager `SegmentPlan` path from run launch and prove no payload,
   auxiliary-event, special-stage, or I/O owner is reachable from either the
   run plan or any closed segment's session/observer/HUD/driver roots; and
8. add the API/caller authority guard for the public lease and open facade.

Do not combine this phase with parser-format, trace-schema, execution-phase,
bootstrap-authority, RNG, compact-field, or hardware-timing changes.

## Acceptance gate

Use the same warmed, forced-GC protocol as the phase-one measurement and the
same 1,087,200,800-byte eager retained-graph baseline. Measure the descriptor
graph alone, then the live replay/session graph with each payload installed into
its real comparator, driver, observer, HUD, fixture, and binder consumers. The
67-segment S3K run supplies every ordinary segment and its S3K special stages;
representative S1 and S2 run fixtures supply their composite special-stage
shapes. Require all of:

- descriptor graph at or below 16 MiB (16,777,216 bytes);
- maximum combined live ownership graph -- descriptors, session/driver consumer
  roots, wrapper, and the sole installed active payload -- at or below 256 MiB
  (268,435,456 bytes);
- at least a 75% retained-graph reduction from the warmed eager baseline;
- exactly one or zero active payloads at every lifecycle observation, plus a
  transitive reachability check after each normal transition and failure exit
  proving no closed segment's `TraceData`, special-stage rows, or auxiliary
  graph remains reachable from session, playback observer, HUD/camera, driver,
  fixture, or binder roots;
- identical first mismatch (`camera_x`, expected `0x1300`, actual `0x1308`), all
  1,653 AIZ rows consumed, and the same terminal segment-0 `giant_ring` failure;
- identical unmatched hardware completions, dynamic-art result, visual/audio
  frame/lag observations, and special-stage driver results;
- no missing or starved trace class in a fresh complete trace sweep; and
- zero file-descriptor growth after 100 repeated open/close cycles for each
  ordinary and special-stage payload shape, plus normal boundary, constructor
  failure, comparison failure, and abort exits.

The retained-graph measurements run in equivalently warmed fresh forks or in
both arm orders, with the installed consumer graph kept reachable until after
the forced-GC sample. `/proc/self/fd` is a Linux smoke check; injected
reader/stream open-close counters are the deterministic file-resource oracle,
while payload-holder counters prove only active-owner cardinality. Failure of
either numeric memory cap or the closed-payload reachability check makes
row/profile streaming or additional alias cleanup prerequisite work rather than
an acceptable partial result.

Raw evidence is retained under
`$AGENT_SCRATCH_ROOT/tasks/performance-candidate-validation-20260821T161822Z-3319778-904a0080/trace-retention/`
and the phase-one benchmark and verification logs under
`$AGENT_SCRATCH_ROOT/tasks/trace-segment-descriptor-benchmark-20260821T192520Z-4127426-d936ca5a/`.
Phase-two implementation and validation evidence is retained under
`$AGENT_SCRATCH_ROOT/tasks/trace-active-segment-cursor-20260822T002616Z-260779-06974cb7/`;
the durable result and exact regression accounting are in the linked validation
report.
