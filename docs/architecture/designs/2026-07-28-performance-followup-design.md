# Performance Follow-up Design

## Status

Draft for independent review. This design coordinates a set of separately
cherry-pickable performance experiments. A candidate is delivered only when its
focused benchmark demonstrates a repeatable benefit and its semantic tests
remain exact.

## Requirements

- Re-evaluate the remaining performance opportunities identified after the July
  2026 performance integration pass.
- Use the available graphical session for engine benchmarks instead of assuming
  a headless machine.
- Keep each accepted improvement on its own `feature/ai-*` branch and worktree
  so it can be reviewed and cherry-picked independently.
- Preserve ROM-accurate behavior, trace comparison output, rewind determinism,
  OpenGL state, and guard-test policy semantics.
- Use test-driven development: establish the semantic and measurement oracle
  before changing production behavior.
- Reject and remove experiments that do not show a repeatable benefit.
- Report branch names, commit hashes, measurements, and verification results
  before any integration into `develop`.

## Exploration Synthesis

### Current gameplay baseline

The current `develop` baseline was built at
`405630a3e3e00c7e5c18dd530515580f823168ce` and run through the live Wayland/X11
session. The S2 CNZ update benchmark used 2,000 warmup frames, 7,469 measured
frames, and three iterations:

| Metric | Result |
|---|---:|
| Steady p50 | 0.114 ms/frame |
| Steady p90 | 0.139 ms/frame |
| Steady p99 | 0.254 ms/frame |
| Maximum | 1.700 ms |
| Trajectory digest | `cf6995fe1dc1a47d` |
| Audio section | 0.0936 ms/frame |
| Object section | 0.0176 ms/frame |
| Physics section | 0.0014 ms/frame |

JFR attributes most sampled CPU time to YM2612 synthesis:
`Ym2612Chip.renderOneSample`, `renderStereo`, `doAlgo`, and `renderChannel`.
`SmpsSequencer.getSamplesUntilNextObservableEvent` accounted for only 1.06% of
samples. That result weakens the source-inspection hypothesis that repeated
sequencer boundary scans are a primary gameplay bottleneck, while still
allowing a small isolated experiment if exact scan-count evidence supports it.

The allocation view includes startup and level-load work as well as steady
frames, so it is not sufficient evidence for a frame-local allocation change.
Candidate branches must use focused allocation counters or a measurement window
that excludes bootstrap.

### Rewind

The present `SegmentCache` has a single partial strip, but the production
controller only consumes it through monotonic `stepBackward` calls. Forward
steps and seeks invalidate it, while committed backward steps discard future
keyframes. A prior segment therefore has no reachable reuse path. A
multi-segment LRU would retain more memory and add rollback/poison complexity
without a current hit-rate benefit; it is rejected from implementation.

Two bounded opportunities remain:

- `ObjectRewindTypeSafety` reflectively resolves legacy/context dispatch on each
  object capture and restore. A per-concrete-class dispatch cache can remove
  that repeat reflection without changing snapshot data.
- `AbstractObjectInstance.captureRewindState` creates a base
  `PerObjectRewindSnapshot`, then creates a second record when compact generic
  state is present. Capturing the optional sidecar first permits one final
  record construction.

Object scalar slabs are deferred. They require a manager-owned capture sink,
slice-aware immutable blobs, and broader restore/ownership validation. They are
considered only if the one-pass object measurement leaves material retained
allocation.

### Trace comparison and auxiliary events

`TraceBinder` formats expected and actual numeric values eagerly, including
matches, and normal replay assembly eagerly builds ROM, engine, event, and
character diagnostic strings. The gameplay benchmark does not include this
comparison path. A replay-test JFR/allocation baseline is therefore required
before design or implementation of lazy presentation.

`TraceData.missingAdvertisedAuxSchemas` checks each advertised event subtype by
rescanning every per-frame event list. The largest present fixture contains
1,569,911 auxiliary events; an all-missing report can perform approximately
28.3 million `isInstance` checks per call. Recording concrete observed event
classes during the constructor's existing index pass makes these queries
constant-time without changing event ordering or the public frame index.

A dense auxiliary-event index is deferred until retained-heap and load-time
measurements show that `HashMap<Integer, List<TraceEvent>>` is a material
problem. Current frame lookup is already constant-time.

### Test source guards

The July pass reduced repeated source scans, but two measured classes still
re-read stable source trees:

- `TestHardwareTimingAuthorityGuard` takes about 1.001 seconds and performs
  repeated whole-production-tree walks/reads across four tests.
- `TestNoServicesInObjectConstructors` takes about 13.61 seconds. Its two
  largest methods take 5.827 and 5.727 seconds because each rebuilds object
  sources and sweeps all production call sites.

Each class can own one immutable, deterministically ordered source catalogue
while retaining separate policy evaluation and exact violation text.

### Render and runtime residuals

The strongest render allocation hypothesis is the S3K slot bonus-stage display
path. `S3kSlotBonusStageRuntime.slotMachineDisplayState()` constructs state and
anchor objects plus several three-element arrays every render frame.
`S3kSlotMachineDisplayState.fromState()` uses stream machinery, and
`S3kSlotMachinePanelAnimator.syncPanelPatterns()` allocates an offset array
before its unchanged-state early return. A changed panel additionally creates
48 `Pattern` objects and their pixel arrays. The previous pass already removed
live layout-cell churn with primitive double-buffered render storage; this is
the next bounded owner.

The safe first slot task is a scalar/frame-owned state-to-panel path with the
existing immutable state snapshot retained for public/test compatibility.
Reusing the 48 panel patterns is a separate follow-up that requires proving
that atlas upload copies pixels and does not retain the mutable pattern.

`LevelRenderer` still creates private two-field background sampling records
twice per background frame. Private-helper scalarization is a bounded candidate,
but callback-facing advanced/special render contexts must remain immutable until
their retention contract is known.

`SmpsDriver.readHybrid` scans live sequencers once for fallback admission and
again for the safe chunk boundary before the required advancement pass. Fusing
the first two scans can preserve behavior, but the JFR gameplay profile ranks
YM2612 chip synthesis far above this work: sequencer observable-event lookup
accounted for only 1.06% of samples. It is a measured experiment, not a presumed
win.

`TraceBenchmarkTool --mode full` is not a complete GPU benchmark: it omits the
visible display pipeline, upscaling, UI/effects, pending render tasks, debug
overlay, and swap/vsync. Slot and background work therefore require focused
allocation probes and pixel/screenshot validation through the live display.

## Architecture Decision

Use an evidence-gated portfolio of isolated branches rather than one broad
optimization branch.

Each worker receives:

1. one non-overlapping production/test ownership boundary;
2. a pre-change semantic test and benchmark;
3. a required post-change comparison using the same workload;
4. authority to return a no-change result when the hypothesis is disproved.

Accepted commits must not combine unrelated optimizations. Measurement-only
instrumentation may be committed only when it has lasting regression value;
temporary probes are removed before handoff.

Candidate workers do not edit `CHANGELOG.md`, `README.md`, architecture
documents, or shared benchmark reports. Production `perf` commits use:

`Changelog: n/a: independently cherry-pickable performance candidate; aggregate release note deferred until selected integration`

The coordination branch alone owns design, plan, validation, and aggregate
release-note edits. This prevents documentation conflicts between branches.

### Common measurement protocol

Every candidate first records its baseline from the candidate worktree before
production edits. The same JVM, flags, ROM, fixture, frame range, and
instrumentation are then used after the change.

- Wall-time comparisons use two unreported warmups followed by seven reported
  samples. The report records every sample and the median. A candidate needs at
  least a 5% median improvement and no material regression in p99 or maximum;
  source-guard candidates, whose process/compiler overhead dilutes the measured
  class, need at least a 10% focused Surefire-class improvement.
- Deterministic allocation comparisons use
  `com.sun.management.ThreadMXBean` after 10,000 warmup operations and seven
  measured batches. All seven batches must show the expected allocation
  removal, the median reduction must exceed 5%, and total batch time must not
  regress by more than 2%.
- Count-based probes establish mechanism but are never the sole acceptance
  evidence. They accompany a wall-time or allocation result.
- Engine-update comparisons run the exact CNZ command recorded above. The
  trajectory digest must remain `cf6995fe1dc1a47d`. Because this workload is
  already sub-millisecond, an audio candidate needs a 3% median improvement in
  the audio section without worsening frame p99 by more than 2%; otherwise it
  is discarded even if its microbenchmark improves.
- Graphical candidates use deterministic pixel checks in focused tests and
  before/after captures of the same live-display scene. Slot captures cover a
  face transition and reel offsets 0, 1, and 31. Background captures cover a
  stationary viewport, positive scroll, and negative-Y alignment. Window,
  scale, shader/effect flags, camera position, and seed are recorded in the
  candidate report.

Implementation may proceed in parallel, but every Maven, benchmark, engine, or
GL command participates in one serialized host lease:

```bash
flock -x /tmp/openggf-performance-measurement.lock \
  taskset -c 31 <command>
```

This host exposes 32 logical CPUs, so CPU 31 is the fixed measurement affinity.
Workers use the lock for functional tests as well as measurements, preventing a
test in another worktree from contaminating a seven-sample window. The
coordinator records that no unrelated Maven, Java engine, or GL workload is
running before and after each baseline/after pair. Live-display capture uses
the same lock and environment but may omit `taskset` when the graphics driver
requires its own worker affinity. A candidate's baseline and after samples run
under one lease before another candidate's measurement starts.

Source-guard timing commands:

```bash
mvn -Dmse=off -q \
  "-Dtest=com.openggf.trace.timing.TestHardwareTimingAuthorityGuard" test
mvn -Dmse=off -q \
  "-Dtest=com.openggf.tests.TestNoServicesInObjectConstructors" test
```

The focused `testsuite time` from the fresh Surefire XML is the primary metric,
not total Maven startup time. Audio uses:

```bash
java -cp target/OpenGGF-0.6.prerelease-jar-with-dependencies.jar \
  com.openggf.tools.TraceBenchmarkTool \
  --trace cnz --mode update --warmup-frames 2000 \
  --measure-frames 7469 --iterations 3
```

## Feature Design

### Candidate A: object rewind dispatch cache

Owned files:

- `src/main/java/com/openggf/level/objects/ObjectRewindTypeSafety.java`
- `src/test/java/com/openggf/level/objects/TestObjectRewindTypeSafetyDispatchPerformance.java`

Cache the dispatch route derived by `ObjectRewindTypeSafety` per concrete object
class. The cached value distinguishes context-aware and legacy capture/restore
overrides. It stores method-route metadata only, never object state.

Acceptance:

- cold and warm dispatch select the same method for default, legacy, and
  context-aware implementations;
- class-loader-safe ownership is used, preferably `ClassValue`;
- reflective lookups fall from per capture/restore to once per concrete class;
- a 10,000-object mixed-route allocation/time batch meets the common threshold;
- object rewind round-trip and torture tests remain green.

### Candidate B: one-pass default object snapshot construction

Owned files:

- `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- `src/test/java/com/openggf/level/objects/TestDefaultObjectRewindCapturePerformance.java`

No snapshot record or blob API changes are permitted.

In `AbstractObjectInstance.captureRewindState(context)`, capture optional compact
generic state before constructing the immutable snapshot, then create the final
record once. Do not change subclass override behavior or blob ownership.

Acceptance:

- exact mutation/restore round trip for a compact-captured default object;
- allocation measurement covers the complete object-manager capture path;
- one record construction replaces two when a compact sidecar exists;
- the seven measured object-manager batches meet the common allocation
  threshold without a time regression;
- all object rewind ownership and determinism tests remain green.

### Candidate C: observed auxiliary-event type index

Owned files:

- `src/main/java/com/openggf/trace/TraceData.java`
- `src/test/java/com/openggf/tests/trace/TestTraceDataParsing.java`
- `src/test/java/com/openggf/tests/trace/TestTraceDataAuxSchemaPerformance.java`

No event-map, fixture, resource, or report API change is permitted.

During `TraceData` construction, add each concrete event class to an immutable
observed-type set. `hasEventOfType` becomes a membership query. The existing
events-by-frame map, event instances, event order, schema advertisement order,
and report output remain unchanged.

Acceptance:

- all existing missing-schema lists remain byte-for-byte/order equivalent;
- duplicate-frame and multi-type fixtures remain correct;
- the event collection is traversed once to build the type index;
- repeated schema reports do not scan the event lists.
- seven post-load missing-schema query batches against the largest available
  fixture improve median wall time by at least 5% and 10 ms per batch, with no
  retained-heap regression above measurement noise.

### Candidate D: timing-authority source catalogue

Owned file:

- `src/test/java/com/openggf/trace/timing/TestHardwareTimingAuthorityGuard.java`

Load a sorted immutable record per production source containing relative path,
package, filename, and content. All tests in
`TestHardwareTimingAuthorityGuard` reuse the catalogue but independently apply
their matchers and policies.

Acceptance:

- the same root is walked/read once per test class;
- a different injected root does not share stale data;
- exact violation ordering and wording are unchanged;
- seven focused Surefire runs improve the class median by at least 10% from the
  measured 1.044-second local baseline.

### Candidate E: object-constructor source catalogue

Owned file:

- `src/test/java/com/openggf/tests/TestNoServicesInObjectConstructors.java`

Create a class-local immutable production catalogue partitioned into all Java
sources and object-package sources. Preserve current individual-file
`IOException` tolerance, deterministic order, regex behavior, and line-number
calculation.

Acceptance:

- one production-tree walk/read supplies all six tests;
- non-object call sites remain visible to all-source detectors;
- every detector retains its exact current policy output;
- seven focused Surefire runs improve the class median by at least 10% from the
  fresh candidate-worktree baseline.

### Candidate F: trace presentation profiling only

The only owned artifact is
`docs/architecture/audits/performance/2026-07-28-trace-presentation-profile.md`;
all probes remain temporary and uncommitted. No production or test API change
is authorized in this portfolio.

Profile a green ROM-backed trace replay with JFR and formatter invocation
counters. Record allocations and CPU attributed to `TraceBinder`,
`FieldComparison`, `TraceFrame.formatDiagnostics`, event summaries, and engine
diagnostics. If presentation accounts for at least 5% of replay wall time or
10% of measured allocation, write a separate narrow design for raw/lazy field
values and run the repository design-review loop before implementation.

Otherwise report the hypothesis as disproved. Any later implementation must
exclude auxiliary-event indexing, report grouping/history changes, and deferred
frame diagnostics from its first branch.

### Candidate G: SMPS hybrid scan fusion

Owned files:

- `src/main/java/com/openggf/audio/driver/SmpsDriver.java`
- `src/test/java/com/openggf/audio/driver/TestSmpsHybridScanFusionPerformance.java`

`SmpsSequencer` production code and existing parity tests are read-only.

Fuse fallback admission and safe-window calculation into one non-allocating
driver-local scan, then retain the existing advancement scan. Recompute after
every batch or sample-accurate step. Preserve `-1` boundary semantics,
`MIN_BATCH_SAMPLES`, fallback dominance, sequencing/removal order, and PCM
output.

Acceptance:

- HYBRID and SAMPLE_ACCURATE output remain byte-identical on the audio
  regression corpus and new multiplier/tempo/event-boundary cases;
- sequencer inspection count falls by one full pass per chunk;
- the CNZ benchmark retains trajectory digest `cf6995fe1dc1a47d`;
- repeated benchmark samples show a benefit above noise, otherwise the
  experiment is discarded.

### Candidate H: S3K slot display/panel allocation

Owned files:

- `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneFeatureProvider.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotMachineDisplayState.java`
- `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotMachinePanelAnimator.java`
- `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotMachinePanelAllocation.java`

Add a scalar/frame-owned runtime-to-panel synchronization path. Preserve the
immutable `S3kSlotMachineDisplayState` compatibility API, exact change
detection, reel offsets, and atlas batch/update order. Replace streams and
temporary three-element arrays on the live unchanged path.

Acceptance:

- post-warmup allocation probes show zero or materially reduced allocation for
  unchanged and spinning panel states and meet the common allocation threshold;
- pattern checksums are exact at face transitions and offsets 0, 1, and 31;
- existing slot runtime, panel, buffer, palette, and boot tests remain green;
- before/after live-display captures are visually identical.

Retained reusable panel `Pattern` instances are not part of the first commit.
They may become an independent follow-up only after copy ownership is proved.

### Candidate I: private background sampling scalarization

Owned files:

- `src/main/java/com/openggf/level/LevelRenderer.java`
- `src/test/java/com/openggf/level/TestLevelRendererBackgroundSamplingPerformance.java`

Replace only the private `BackgroundTilemapSampling` record creation in the hot
background render path with scalar flow. Preserve command-owned source anchors,
ring-generation state, negative-Y alignment, and deferred command lifetime.

Acceptance:

- seven post-warmup render batches demonstrate removal of the two records and
  meet the common allocation/time thresholds;
- persistent nametable and background viewport tests remain exact;
- a deferred-command mutation test proves captured values are stable;
- live-display pixel captures match.

Callback-facing advanced/special render contexts and broader GPU architecture
are excluded.

### Candidate J: low-frequency SMPS event arrays

Owned files:

- `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- `src/test/java/com/openggf/audio/smps/TestSmpsSequencerEventArrayPerformance.java`

Hoist the fixed operator-order arrays created by
`SmpsSequencer.updateFmTotalLevel()` and `refreshInstrument()` to immutable
static constants. This event-path change proceeds only if a transition-focused
allocation probe detects the arrays.

Acceptance:

- seven 10,000-transition batches meet the common allocation/time thresholds;
- PCM output remains byte-identical;
- no mutable array escapes the sequencer.

No branch combines audio and render work. Any candidate is rejected if its
focused measurement is below noise or its state-ownership constraints require a
broader redesign.

## Execution and Integration

Worker branches start from the same recorded `develop` baseline unless upstream
changes require a documented rebase. Every branch uses an isolated worktree.
Workers commit only their owned files, report the exact commit SHA, and leave
the worktree clean.

Each completed candidate receives an independent code review. Valid findings
are fixed and re-reviewed before it is offered for integration.

After candidate verification, the coordination report will list:

- accepted and disproved hypotheses;
- before/after commands and samples;
- focused and full-suite results;
- cherry-pickable commits and their dependencies.

No candidate is merged into `develop` until the user chooses the set to
integrate. Chosen commits then follow the repository's baseline comparison,
merge, full-suite regression comparison, push, and worktree-cleanup workflow.

## Risks and Rollback

- Microbenchmarks can report JIT or filesystem-cache noise. Use repeated runs,
  preserve workload digests, and prefer medians with meaningful margins.
- Caching source content can hide mutations within one JVM. JUnit source guards
  intentionally inspect one checkout snapshot; injected-root tests prevent
  accidental cross-root reuse.
- Lazy trace presentation can alter exact signed/hex formatting or diagnostics
  timing. Golden output tests are mandatory.
- Rewind dispatch caching can conflate distinct override routes. Cache a typed
  route descriptor and test each route explicitly.
- OpenGL scratch reuse is thread-confined and must not expose mutable storage.
- Audio batching can cross a hardware-observable event boundary. Exact
  sample-by-sample reference comparisons are required.

Rollback is branch-level: omit or revert the individual candidate commit. No
candidate changes persisted data formats or requires a compatibility migration.
