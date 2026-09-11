# Performance investigation report accuracy audit

## Decision

The nine reports in the 2026-08-21 performance investigation are useful
source reconnaissance, but they are not a reliable implementation priority
list. They identify several real code shapes, yet almost every impact ranking
is inferred rather than measured and several proposed transformations would
change observable ordering, lifecycle, failure-isolation, or rendered state.

Treat the reports as a **static candidate survey**. Do not carry their
`HIGH`/`MEDIUM` labels into planning. Promote a candidate only after a
reproducible measurement at a named commit and an accuracy oracle appropriate
to that subsystem.

## Review basis

| Item | Value |
|---|---|
| Review date | 2026-08-21 |
| Reviewed checkout | `develop` at `683b1a3984958b4b6ae53baf383a81bee6727078` |
| Report directory | `$AGENT_SCRATCH_ROOT/tasks/perf-investigation-20260821T122623Z-2247429-42c53748/reports/` |
| Reports | `00-summary.md` through `08-build-test-bench.md` |
| Method | Source and call-path inspection followed by focused GPU, JFR, forced-GC, and lifetime validation |
| Validation baseline | `18c20dec8938e898a37a5eb270234d9dc5fe38db` on branch `feature/ai-performance-candidate-validation` |
| Runtime profiling | Serialized on a 32-logical-CPU x86_64 host, OpenJDK 21.0.11, Maven 3.9.16; timing probes pinned to CPU 31 and display `:0` |
| Test execution | Default-suite baseline plus focused AIZ, trace-run, and rewind ownership tests; exact outcomes below |
| Raw artifacts | `$AGENT_SCRATCH_ROOT/tasks/performance-candidate-validation-20260821T161822Z-3319778-904a0080/` |

Confidence labels used below:

- **Confirmed:** the stated code shape and its frequency/lifetime premise are
  directly supported by the current source or existing recorded measurement.
- **Plausible:** the code shape is real, but its runtime materiality or the
  proposed equivalence is not established.
- **Incorrect:** a factual statement conflicts with the source.
- **Unsafe:** the proposed transformation can change observable behaviour.

## Measured validation results

### AIZ fire curtain: not promoted

The checked-in GPU diagnostic initially skipped because it requested the
camera before active gameplay services existed. A disposable bootstrap repair
produced fresh framebuffers, but diagnostic counters at command admission and
draw execution proved that the test never submitted the fire-curtain workload.
Across two warmups and seven serialized samples, every sampled milestone
reported zero curtain tile draws, zero admitted curtain pattern commands, and
zero curtain pattern draw calls. The observed two instanced batches per frame
belonged to the ordinary background path. The manually advanced diagnostic did
not service the production Kosinski queue, so its fire-overlay tile count
remained zero; converting it to the production frame driver left the event
inactive and still produced no overlay.

The renderer's direct-command source shape remains confirmed, but the required
real-frame workload observation failed. The recorded submission timings and
framebuffer hashes are not evidence about this candidate, so no rendering
design was produced. Confidence: **disproved measurement workload**, not
“immaterial renderer.”

The focused correctness baseline ran 28 tests: the 9 renderer and 4 ROM-backed
renderer tests passed; the 15 headless tests had two pre-existing errors in the
fire-continuation queue fixture (`AIZ fire continuation has no prepared
fire-overlay payload`).

### Complete-run trace retention: implemented and validated

The 67-segment Knuckles super-emerald fixture contains 58,796,492 uncompressed
physics bytes and 1,548,327,089 uncompressed auxiliary bytes, for
1,607,123,581 bytes total. The largest segment is `soz_2` at 161,853,777 bytes,
followed by `mgz_2` at 133,722,923, `lbz` at 105,539,097, `icz` at 102,691,624,
and `fbz_3` at 79,020,684.

With the trace fork at its configured 3 GiB heap, a full class histogram after
all 67 `TraceData` plans were built reported 1,094,956,904 live bytes across
27,919,039 objects. Direct `TraceEvent` implementations accounted for
399,294,016 bytes (36.47%), `CompactFieldMap` for 86,444,992 bytes (7.89%), and
physics/special-stage frame records for 62,909,200 bytes (5.75%). Common
trace-dominated arrays, strings, boxed integers, lists, and maps accounted for
535,363,856 bytes (48.89%). Hardware-timing classes used only 102,864 bytes
(0.0094%), so timing schedules should stay globally retained.

The JFR allocation sample independently points to `TraceData.load`,
`TraceData.loadAuxEvents`, and `TraceEvent.parseJsonLine`; these are allocation
weights, not wall-time shares. The trace consumed all 1,653 AIZ physics rows,
first differed at trace frame 0 on `camera_x` (expected `0x1300`, actual
`0x1308`), then failed because segment 0's `giant_ring` exit boundary was not
observed. Those values form the later equivalence oracle.

Consumer inventory shows no payload consumer requiring whole-run random
access. Planning needs a one-time sequential validation scan; live comparison
needs previous/current/next physics rows and the current raw frame's typed
auxiliary events; cross-boundary consumers need compact metadata, timing,
opening-row, and terminal-ledger summaries. Visual/audio harness frame views
also need a compact per-row lag mapping for arbitrary BK2 cursors during gaps;
that scheduling outcome remains global without retaining trace payloads. The
selected design is therefore a global compact plan plus lag mapping and one
closeable segment-local cursor, documented in
`docs/architecture/designs/2026-08-21-bounded-trace-run-segment-ownership.md`.
Confidence: **confirmed and measured**.

At the phase-one checkpoint, implementation scanned one segment at a time into
immutable descriptors and routed catalog launch validation through those
descriptors.
An opt-in forced-GC comparison on the same 67-segment run, from the Task 3
working tree based at `b89a732e4`, measured 1,087,200,800 retained bytes for
the eager plan and 8,660,152 retained bytes for the descriptor plan: a
1,078,540,648-byte (99.20%) reduction. Both planners first completed unmeasured
whole-run warmups and released those graphs before either measured arm, so
persistent parser/cache initialization is outside both deltas. Both plans
represented 67 segments and
409,630 rows/raw-frame mappings. This established the planning boundary and
benefited catalog validation at that checkpoint; actual replay still owned the
eager `SegmentPlan` graph, so its retained heap was unchanged in that phase.
Moving replay to a closeable active-segment cursor remained separately approved
future work.

Phase two has now completed that approved migration. Two warmed, forced-GC
forks retained 9,253,296 and 9,252,768 bytes for the complete descriptor graph
and peaked at 170,550,952 and 170,910,128 bytes with one real segment payload
installed into its consumers. Those are 84.31% and 84.28% reductions from the
fixed eager baseline; both overall maxima were `s3k-59-soz_2`. The ownership
graph now includes the real catalog entry, parsed movie, complete descriptor
set, `TraceSessionLauncher`, exact headless harness/fixture, and installed
consumer roots linked through the actual launcher and harness fields; the
earlier lower figures omitted or synthesized required owners and are superseded.
Representative S1 and S2 special samples remain included. Reader lifecycle
checks balanced exactly 1,000 opens and
1,000 closes per run across 100 cycles of every ordinary and special-stage
shape.

The benchmark passed 1/1 and the descriptor-plus-six-catalog functional set
passed 49/49. Of 151 requested authority/replay controls, 148 passed and three
pre-existing `TestTraceSessionLauncherRunBranch` methods errored; each error
reproduced in a fresh isolated one-test fork, and both that test class and
`TraceSessionLauncher` are byte-identical to base `c046e0298`. The descriptor
change therefore introduces no observed control regression, but this evidence
must not be restated as an all-green control run. Raw logs are under
`$AGENT_SCRATCH_ROOT/tasks/trace-segment-descriptor-benchmark-20260821T192520Z-4127426-d936ca5a/`.

### Dynamic rewind identities: retention reproduced, pruning not promoted

A temporary red test showed that adding then removing one dynamic object left
one `rewindObjectIds` entry (`expected: <0> but was: <1>`). A companion
capture/remove/restore test passed and recovered the captured ID, proving that
historical identity comes from the snapshot rather than the stale map entry.

The initial pruning hypothesis fails the graph-lifetime gate.
`ObjectCollisionResponseList` retains previous and partial-current object
references independently of `dynamicObjects`, and rewind capture encodes those
views through `rewindObjectIds`. Immediate pruning could therefore make a valid
collision-list capture lose its ID. In addition,
`cleanupDestroyedDynamicObjects()` removes through its iterator and bypasses
`removeDynamicObjectInstance()`, so the proposed one-method change would not
cover every retirement path.

The unmodified seven-class graph/guard baseline ran 29 tests, all passing. The
follow-up ownership questions and adversarial test matrix are documented in
`docs/architecture/designs/2026-08-21-dynamic-rewind-identity-ownership.md`.
Confidence: **retention confirmed; fix rejected pending explicit ownership**.

### Development-suite comparison

The freshly cleared default baseline produced 1,941 Surefire XML files and a
terminal Maven result of 15,274 tests, 58 failures, 64 errors, and 18 skips.
The documentation tree produced the same 1,941-file class set and 15,274 tests,
with 56 failures, 63 errors, and 36 skips. Identity comparison in both
directions found one apparent new failing test, but the validation command had
exported `MAVEN_OPTS` to redirect sandboxed temporary output and that test
explicitly requires a clean tool environment. Its clean-environment focused
rerun passed all 7 tests. Four baseline failures were absent from the
development run, consistent with the repository's documented order-dependent
red-set churn. No new failure was attributable to the documentation changes.

### Active-segment implementation comparison

The authoritative full-suite comparison uses synchronized `develop` at
`d473365ed72facfffcd36d9e07af09666b094d37` and reconciled feature measurement
point `1a96fbdf1588564d584afb57040f749656f3cbf4`, both on JDK 21 with the exact
`mvn -Dmse=off test` command. Main completed 15,299 tests with 55 failures, 81
errors, and 26 skips; the feature rerun completed 15,330 tests with 55 failures,
65 errors, and 26 skips. The 119 shared reds have complete message/root-detail
comparison: 116 raw-identical, two identical after only a demonstrated object
identity-hash normalization, and one identical except for a moved source line.
The raw feature-only ICZ result and the first run's MGZ result both reproduce
exactly on current main under their same-fork predecessors, proving upstream
singleton/order leaks rather than feature attribution. Attributable new or
worsened reds are zero.

Subsequent whole-branch review added enforcement-only corrections through
`7b16dc94b`. They did not change the production ownership graph or supersede
the full-suite/trace measurements above. At that correction head the authority
guard passes 35/35, authority plus ownership passes 38/38, and the combined
reader gate passes 44/44.

Fresh all-game sweeps completed current main at 811 tests / 10 failures and
feature at 840 / 10, with exact red identity equality and complete-message
parity. The earlier two S1 visual failures were real feature regressions, fixed
by `25d4a41b7` and `c8cb56808`. They are green on the reconciled feature;
current main is the passing unchanged eager-ownership control. Completion
accounting covers 796 baseline-green executions and 82 actual replay methods
across generic, credits, special-stage, bounded-chain, visual, and bonus
families; no completion issue remains. The 67-segment oracle still consumes all
1,653 AIZ rows with its exact frontier and terminal result. Full evidence is in
[`2026-08-22-active-segment-ownership-validation.md`](../../validation/trace/2026-08-22-active-segment-ownership-validation.md).

## Report-level assessment

| Report | Assessment | Principal correction |
|---|---|---|
| `00-summary.md` | Overconfident | The ranking combines unmeasured hypotheses with unsafe proposals and cannot be used as a roadmap. |
| `01-rendering.md` | Mixed | The AIZ curtain direct-command path is real; broad “no visibility gate anywhere” and the reported implementation count are false, while plan caching omits mutable descriptor state. |
| `02-physics-collision.md` | Mostly sound static analysis | Duplicate ground scans are real, but impact labels are unmeasured and reuse must preserve scan-result values and side effects rather than merely share mutable result objects. |
| `03-object-manager.md` | Mixed | Repeated passes, allocation sites, linear lookup, and dynamic rewind-map retention are real; replacing the processed-identity set or maintaining fallback state naively is unsafe. |
| `04-audio.md` | Mixed with two unsafe recommendations | Logging, modulo, and copy costs are real; deferring sequencer cleanup and mixing the first voice directly into the accumulator violate existing contracts. |
| `05-decompression-loading.md` | Real hotspot shape, inaccurate redesign details | One-byte channel reads are real; `ByteArrayOutputStream.write` is not synchronized, standard Kosinski output size is not known before decode, and KosM module history cannot be reused without a full reset. |
| `06-allocation-gc-sweep.md` | Incomplete | “Fully catalogued” is false; `DefaultSolidExecutionRegistry.finishFrame()` alone creates per-object maps and per-player state records each frame. |
| `07-game-loop-timing-camera.md` | Hypothesis only | The claimed 6–12% core burn was not measured, and trace replay does not exercise the GLFW/vsync pacing loop. |
| `08-build-test-bench.md` | Best-supported report, with two factual errors | Both physics and auxiliary rows are eagerly retained; the default JVM argument disables CDS with `-Xshare:off`, it does not use a CDS archive. |

## Confirmed or well-supported candidates

### Trace-run retention

`TraceRunReplayWalker.plan()` creates a list of `SegmentPlan` records retaining
each segment's `TraceData`. `TraceData.load()` eagerly parses `physics.csv` into
a `List<TraceFrame>` and eagerly parses `aux_state.jsonl` into a frame-indexed
map. The class comment calling auxiliary events “lazy-loaded” conflicts with
the implementation.

The checked-in `pom.xml` records earlier heap measurements and identifies
auxiliary events as the dominant retained data. The Knuckles super-emerald run
contains 67 segments; the recorded uncompressed sizes are approximately
1,476.6 MiB of auxiliary JSONL and 56.1 MiB of physics CSV. Those figures
support prioritising a bounded-memory design. They do not support the report's
claim that physics rows already stream.

The durable design must account for all consumers of segment metadata,
hardware-timing schedules, boundary pairing, dynamic-art ledgers, and indexed
auxiliary queries. “Stream aux events” is a goal, not yet a sufficient design.

### AIZ fire-curtain draw shape

`AizFireCurtainRenderer.render()` emits one
`GraphicsManager.renderPatternWithId()` call per `TileDraw` after
`LevelRenderer` has flushed the sprite batch and before the next normal batch
is opened. This confirms the direct-command shape on the primary AIZ route.

The report's tile and GL-call totals are derived upper bounds, not captured
measurements. A batching change must preserve execute-time shader selection,
palette state, priority, clipping, and command order. The report correctly
noticed the water-shader capture hazard; that hazard makes a naive
`beginPatternBatch()`/`flushPatternBatch()` wrapper unsuitable.

### Duplicate grounded sensor probes

`PlayableSpriteMovement.captureTiltAnglesForGroundDispatch()` scans both
ground sensors before `CollisionSystem.resolveGroundAttachment()` performs its
own terrain probe. This is a real duplicate-work candidate on the ordinary
terrain-attached path.

Any reuse design must latch `SensorResult` values, the scan mode, centre
position, relevant solidity/background-collision inputs, and any
debug-observable sensor state. The sensor result holders are mutable and must
not be shared by reference across the two consumers.

### Dynamic rewind identity retention

`removeActiveObject()` removes the instance from `rewindObjectIds`, while
`removeDynamicObjectInstance()` removes it from live collections without
pruning the identity map. This can retain every removed dynamic object until a
manager reset. It is a credible lifetime leak and a relatively narrow
correctness-preserving candidate, but removal must be tested across capture,
remove, restore, and graph-reference cases before it is declared harmless.

### Small, concrete hot-path candidates

The following source observations are accurate, though their impact remains
unmeasured:

- FM key-on logging eagerly builds a string when `FINE` logging is disabled.
- `SampleBackedVoice` recomputes an invariant 64-bit loop modulo per sample.
- The OpenAL presentation handoff performs an avoidable intermediate copy.
- `FrameCollisionPlan` factories create immutable records that could be
  constants, although escape analysis may already remove the allocations.
- Kosinski, Nemesis, and Enigma channel readers use one-byte `ByteBuffer`
  reads; this is a credible load-time optimisation target.
- `objectIdInSlot(int)` scans active objects rather than using direct slot
  authority.
- `DefaultSolidExecutionRegistry` and solid checkpoint construction allocate
  per object/player per frame.

None should be ranked by source inspection alone.

## Material inaccuracies and unsafe proposals

### Audio lifecycle cleanup cannot be deferred

`SmpsDriver.removeCompletedSequencers()` does more than remove a silent voice.
It releases FM/PSG locks, removes SFX registry entries and claims, and emits a
completion-cleanup lifecycle service event. S&K's SFX-first path deliberately
runs cleanup between SFX and music service so the same VInt's music pass can
reuse the released channel. Hoisting cleanup out of the per-sample/boundary
path can therefore change both service order and audio output.

### Mixer scratch is a failure-isolation boundary

`AudioPresentationMixer` mixes each voice into `voiceScratch` inside a
`try`/`catch`, then commits the completed scratch buffer to `accumulation`.
Allowing the first voice to write directly into `accumulation` means a voice
that writes partially and then throws leaves partial audio behind. The
proposal is unsafe unless the voice API is strengthened to guarantee atomic
completion or the destination can be rolled back without adding equivalent
work elsewhere.

### Object processed identity cannot be inferred from `execOrder`

Objects may release their SST slot while remaining alive. That clears their
`execOrder` entry and moves subsequent execution through a slotless fallback.
The reused processed-identity set is what prevents the same instance from
executing twice in that frame. Replacing it with an `execOrder` membership
check loses that information. Incremental fallback maintenance is likewise an
ordering-sensitive redesign, not a mechanical list-pass removal.

### Fire-curtain plan cache key is incomplete

Sampled composition depends on live background descriptors in addition to
stage, cover height, source coordinates, and wave offsets. Gameplay layout
mutation can change those descriptors without changing the proposed key. List
reuse may be viable; cross-frame result reuse requires an authoritative layout
generation or immutable snapshot owned by the sampling boundary.

### Rendering visibility statement is false

The source contains existing visibility gates, including object renderers that
call `Camera.isOnScreen()`. A direct multiline search of the reviewed tree also
finds 667 `@Override` implementations of `appendRenderCommands`, not the
reported 653. More importantly, a broad object-level cull is not proven safe:
render bounds, vertical wrapping, screen-space emissions, debug paths, and
implementations with presentation side effects need separate treatment.

### Decoder redesign details overclaim equivalence

`ByteArrayOutputStream.write(int)` is not synchronized. A standard Kosinski
stream has no decompressed-size header, so an exactly-sized destination is not
available without a pre-scan or growth strategy. KosM does have a total-size
header, but each standard module begins a fresh history/descriptor state; a
reused decoder must reset that state completely at every module boundary.
Fast match copies must retain forward-overlap semantics.

### Build configuration description is wrong

The shared property is `test.cds.argLine=-Xshare:off`; this disables class data
sharing. The trace profile also overrides `surefire.argLine`, so supplying a
CLI `-Dsurefire.argLine=...` is silently ineffective for profiling. Extra JVM
diagnostics must be threaded through a property the profile actually expands,
such as `test.cds.argLine`, and their presence must be verified in the fork.

### Game-loop estimate has no evidence

The hybrid sleep/spin code and `glfwSwapInterval(1)` both exist. The conclusion
that the spin burns 6–12% of a core “on top of vsync” does not follow without
present-interval and CPU measurements on the interactive loop. Trace-replay
tests do not execute this GLFW/vsync pacing path and cannot validate it.

## Corrected priority

1. **Keep bounded trace-segment ownership enforced by its measured gates.**
   Descriptor planning and the active-segment replay migration are implemented
   and feature-branch validated. Further row/profile streaming remains a
   separate change that first requires an authority audit and must preserve the
   exact cross-boundary oracle.
2. **Resolve dynamic rewind identity ownership before pruning.** Add the
   previous/partial collision-list and destroyed-child cleanup tests, then
   choose an identity retirement owner that covers every reference holder and
   removal path.
3. **Repair the AIZ GPU diagnostic before reconsidering curtain batching.** A
   useful benchmark must drive the production frame path, service the ROM-backed
   art queues, observe non-zero curtain commands, and preserve a real-frame
   framebuffer oracle. The current samples must not be used for prioritisation.
4. **Profile grounded collision and solid-registry allocations.** Promote
   duplicate sensor reuse or allocation work only if JFR shows material cost.
5. **Benchmark decoder paths using real ROM spans.** Preserve byte output,
   consumed length, module reset semantics, and hardware readiness budgets.
6. **Take isolated audio/render micro-wins only when observed.** Logging guards,
   invariant modulo caching, and copy elision are plausible but lower priority.

Defer batch-merging across the whole unified render pass, general visibility
culling, sequencer cleanup deferral, direct-to-accumulator voice mixing,
object-loop fallback redesign, DAC fixed-point conversion, buffer upload
strategy changes, and game-loop pacing changes until each has its own measured
problem statement and behavioural design.

## Evidence required for future performance reports

Every promoted finding should record:

| Field | Requirement |
|---|---|
| Observation | Exact source location and current behaviour |
| Frequency | Calls, allocations, bytes, or draws per representative frame/run |
| Measured cost | CPU, allocation weight, retained heap, GL calls, GPU time, or wall time |
| Measurement identity | Commit, JDK, host constraints, workload, warmups, and raw artifact path |
| Semantic invariant | The state/order/bytes/pixels that must remain identical |
| Verification | Focused tests plus the appropriate trace, audio, render, or decode oracle |
| Confidence | Confirmed, plausible, disproved, or unsafe |

JFR execution samples must not be presented as wall-time shares. Trace-suite
comparisons must prove report completeness, compare failure messages and class
sets in both directions, and record `framesCompared` for greens. A candidate
that lacks these fields remains reconnaissance, regardless of how obvious its
source shape appears.
