# Rewind, Trace, And Test Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reduce rewind latency/retained memory and trace/test cost while preserving byte-for-byte deterministic state and comparison semantics.

**Architecture:** Cache immutable class/source metadata, specialize primitive capture paths, and publish immutable snapshot-owned slabs/pages. Trace diagnostics become demand-formatted around mismatches. Structural changes retain reference implementations as equivalence oracles until randomized seek/mutation tests pass.

**Tech Stack:** Java 21, JUnit 5, Maven, existing rewind/trace frameworks, JFR/ThreadMXBean opt-in measurements.

---

Every `perf:`/`fix:` commit touching `src/main/` updates `CHANGELOG.md` and includes the repository trailer block. No task changes trace fixtures or comparator tolerances.

### Task 1: Repair And Refresh The Rewind Benchmark

**Files:**
- Modify: `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`
- Create: `src/test/java/com/openggf/game/rewind/TestRewindBenchmarkStructuralSizer.java`

- [ ] Add a failing test that sizes a package-private nested record and asserts traversal succeeds without `IllegalAccessException` and counts its scalar payload.
- [ ] Run `mvn "-Dtest=TestRewindBenchmarkStructuralSizer" test`; confirm failure at the inaccessible accessor.
- [ ] Make record accessors accessible once in a cached structural-size plan; preserve cycle detection and deterministic field order.
- [ ] Run the focused test, then `mvn "-Dtest=RewindBenchmark" "-Dopenggf.rewind.benchmark.run=true" "-Ds2.rom.path=s2.gen" test` and save current capture/restore/retained-size results under `target/` for the integration report.
- [ ] Commit `test: repair rewind benchmark structural sizing` with all trailers `n/a`.

### Task 2: Cache Legacy Override And Primitive Codec Metadata

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindCaptureMemoizationEquivalence.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindCodecFieldScratchReuse.java`

- [ ] Add failing counter tests proving each concrete class hierarchy and field codec is resolved once while repeated captures produce identical blobs.
- [ ] Run the two tests; confirm lookup-count assertions fail.
- [ ] Cache capture/restore legacy-override decisions by concrete class and share eligibility metadata where possible. Add typed primitive-array clone/restore loops for every primitive component type; preserve object/stateful array handling.
- [ ] Re-run tests plus `mvn "-Dtest=TestRewindCaptureScratchReuse,TestEveryObjectRewindRoundTrip" test`.
- [ ] Update `CHANGELOG.md`; commit `perf: cache rewind metadata and primitive codecs` with trailers.

### Task 3: One-Pass Object Snapshots And Immutable Scalar Slabs

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindObjectStateBlob.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/CompactFieldCapturer.java`
- Modify: `src/main/java/com/openggf/level/objects/AbstractObjectInstance.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindSnapshotSlab.java`

- [ ] Add failing tests that capture multiple objects, assert scalar slices share one snapshot-owned backing slab, mutate all live objects, and verify every historical slice restores independently and rejects out-of-range access.
- [ ] Run `mvn "-Dtest=TestRewindSnapshotSlab" test`; confirm no slab/slice API exists.
- [ ] Add an owned immutable slab plus offset/length reader. Build final per-object snapshots once instead of base-plus-`withCompactGenericState`; pack scalar data during the manager capture pass and freeze before publication.
- [ ] Preserve schema/type validation and never share scratch or opaque mutable values. Re-run focused test plus capture scratch, round-trip, graph identity, and torture tests.
- [ ] Update `CHANGELOG.md`; commit `perf: pack object rewind scalars into snapshot slabs` with trailers.

### Task 4: Bounded Multi-Segment Scrub Cache

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/SegmentCache.java`
- Modify: `src/main/java/com/openggf/game/rewind/RewindController.java`
- Test: `src/test/java/com/openggf/game/rewind/TestSegmentCache.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindController.java`

- [ ] Add failing tests for alternating boundary seeks, a strict expansion-count bound with a two/three-entry LRU ring, and poisoned fade expansion leaving the complete pre-expansion registry/RNG/audio state unchanged.
- [ ] Run focused tests and confirm the single-segment cache re-expands.
- [ ] Retain a bounded ring keyed by segment start. Reuse an already captured adjacent live snapshot for rollback; if none exists, capture once. Publish a segment only after successful expansion.
- [ ] Re-run focused tests plus `TestRewindTraceSeekDeterminism` and `TestRewindTorture`.
- [ ] Update `CHANGELOG.md`; commit `perf: cache adjacent rewind segments` with trailers.

### Task 5: Page-Granular Level Copy-On-Write

**Files:**
- Modify: `src/main/java/com/openggf/level/Map.java`
- Modify: `src/main/java/com/openggf/level/Block.java`
- Modify: `src/main/java/com/openggf/level/Chunk.java`
- Modify: `src/main/java/com/openggf/level/AbstractLevel.java`
- Modify: `src/main/java/com/openggf/level/rewind/LevelRewindSnapshotAdapter.java`
- Modify: `src/main/java/com/openggf/game/mutation/DirectLevelMutationSurface.java`
- Test: `src/test/java/com/openggf/game/rewind/TestPagedLevelCoW.java`

- [ ] First extend `RewindBenchmark` with a mutation-heavy map allocation probe. Record evidence that whole-array cloning remains material; if not, document the disproved candidate and keep the existing implementation.
- [ ] When material, add failing tests that mutate cells/pages after two snapshots and assert untouched pages are shared, touched pages copy once per epoch, and both snapshots remain byte-identical.
- [ ] Implement fixed-size page tables for map cells and suitably sized block/chunk descriptor pages behind existing accessors. Mutation surfaces call page-aware `cowEnsureWritable`; snapshot adapters retain page-table references and epochs.
- [ ] Run `mvn "-Dtest=TestPagedLevelCoW,TestMapCoW,TestBlockCoW,TestChunkCoW,TestLevelRewindSnapshotAdapter" test` and mutation/rewind integration tests.
- [ ] Update `CHANGELOG.md`; commit `perf: use page-granular level rewind cow` with trailers, or commit a measurement-only test/docs result if the premise is disproved.

### Task 6: Lazy Trace Diagnostic Formatting

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceBinder.java`
- Modify: `src/main/java/com/openggf/trace/FrameComparison.java`
- Modify: `src/test/java/com/openggf/tests/trace/AbstractTraceReplayTest.java`
- Create: `src/test/java/com/openggf/trace/TestLazyTraceDiagnostics.java`

- [ ] Add a failing golden test that compares eager and lazy output for passing frames, isolated mismatches, consecutive mismatches, same-frame replacement, and configured before/after context windows; count formatter calls.
- [ ] Run the test and confirm all passing frames are eagerly formatted.
- [ ] Store compact primitive/enum comparison results per frame. Maintain a bounded preceding-frame ring and requested post-error window; invoke string formatters only when output is requested for a mismatch/context frame.
- [ ] Preserve `comparisonForFrame`, field order, severity, first frontier, and report text. Re-run the golden test and representative S1/S2/S3K trace replay tests.
- [ ] Update `CHANGELOG.md`; commit `perf: format trace diagnostics on demand` with trailers.

### Task 7: Dense Auxiliary Event Index And Cached Schema Presence

**Files:**
- Modify: `src/main/java/com/openggf/trace/TraceData.java`
- Create: `src/main/java/com/openggf/trace/TraceEventIndex.java`
- Test: `src/test/java/com/openggf/trace/TestTraceEventIndex.java`

- [ ] Add failing tests comparing event order, duplicate-frame events, typed queries, missing advertised schemas, and memory estimates between the current map/list graph and a dense/chunked index.
- [ ] Run the focused test and confirm the compact-index assertions fail.
- [ ] Parse once into chunked frame offsets plus an ordered event array and cached type-presence bits. Replace repeated full scans in `missingAdvertisedAuxSchemas()` while retaining public ordered views.
- [ ] Measure large complete runs. If retained heap remains above the design threshold, add a second failing equivalence test and implement a gzip-backed sequential spool with sparse checkpoints; otherwise document that lazy storage was disproved and stop at dense indexing.
- [ ] Run trace parser/report/replay tests and update `CHANGELOG.md`; commit `perf: compact auxiliary trace event indexing` with trailers.

### Task 8: Shared Per-JVM Source Index For Coverage Guards

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/coverage/ObjectClasspathScan.java`
- Modify: `src/main/java/com/openggf/game/rewind/coverage/StaticStateRewindCoverageAnalyzer.java`
- Create: `src/main/java/com/openggf/game/rewind/coverage/SourceTreeIndex.java`
- Test: `src/test/java/com/openggf/game/rewind/coverage/TestSourceTreeIndex.java`

- [ ] Add a failing fake-filesystem/counting test proving two analyzers share one immutable file read/index while different roots do not, and policy results remain independently computed.
- [ ] Run focused test and confirm repeated reads.
- [ ] Add a root-keyed per-JVM immutable source index containing normalized paths, contents, class enumeration, and searchable tokens. Do not cache baselines or policy decisions.
- [ ] Run `mvn "-Dtest=TestSourceTreeIndex,TestRewindCoverageAnalyzer,TestRewindCoverageGuard,TestStaticStateRewindCoverageGuard,TestRewindArchitectureGuard" test`.
- [ ] Update `CHANGELOG.md`; commit `perf: share rewind coverage source indexes` with trailers.

### Task 9: History Pruning And Test Lifecycle Cleanup

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/LiveRewindManager.java`
- Modify: `src/test/java/com/openggf/tests/TestEnvironment.java`
- Modify: `src/test/java/com/openggf/tests/SharedLevel.java`
- Create: `src/test/java/com/openggf/game/rewind/TestLiveRewindHistoryPruning.java`
- Create: `src/test/java/com/openggf/tests/TestEnvironmentReset.java`

- [ ] Add failing call-count tests proving pruning runs only when the retained keyframe floor advances and each reset path calls `SessionManager.clear()` once while leaving no live context/session.
- [ ] Run focused tests and confirm current repeated calls.
- [ ] Cache the last pruned keyframe/floor and prune gameplay/audio/input history together only on advancement. Remove duplicate clear calls without changing reset ordering.
- [ ] Re-run focused tests plus singleton/session lifecycle guards.
- [ ] Update `CHANGELOG.md` for production pruning; commit `perf: avoid redundant rewind and test cleanup` with trailers.

### Task 10: Rewind And Trace Verification Gate

- [ ] Run `mvn "-Dtest=TestRewindController,TestSegmentCache,TestRewindCaptureScratchReuse,TestRewindCodecFieldScratchReuse,TestRewindCaptureMemoizationEquivalence,TestRewindTraceSeekDeterminism,TestRewindTorture,TestLevelRewindSnapshotAdapter,TestMapCoW,TestBlockCoW,TestChunkCoW" test`.
- [ ] Run coverage/architecture guards and `TestEveryObjectRewindRoundTrip`.
- [ ] Run `mvn -Ptrace-replay "-Dtest=*TraceReplay" "-DfailIfNoTests=false" test`; do not modify fixtures to obtain green.
- [ ] Re-run the opt-in benchmark and record capture/restore p50/p95/p99, retained bytes, hot/cold segment boundaries, and mutation allocations.
- [ ] Run `mvn test`; require zero failures/errors.
