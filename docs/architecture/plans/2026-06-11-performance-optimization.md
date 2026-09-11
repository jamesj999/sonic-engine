# Engine Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the verified engine hot-path waste identified in `docs/architecture/designs/2026-06-11-performance-optimization-design.md` while preserving trace replay, rendering, rewind, and audio behavior.

**Architecture:** Ship this as four independently reversible phase branches or commit groups: exact-equivalence quick wins, rendering upload reduction, rewind structural allocation fixes, and evidence-gated behavior-sensitive cleanup. Each phase begins with a baseline/targeted failing test where practical, lands small commits, and ends with focused tests plus a full `*TraceReplay` sweep before the next phase starts.

**Tech Stack:** Java 21, Maven, JUnit 5, LWJGL/OpenGL render path, existing `PerformanceProfiler`, existing trace replay and rewind test infrastructure.

---

## Design Review Notes

The design is directionally sound, but do not execute it as one large change. It crosses audio, rendering, rewind, collision, object management, and benchmarking. Keep phase commits independent so a rendering or audio-risk item can be reverted without losing low-risk wins.

Two items need explicit handling before implementation:

- The design overlaps the prior plan `docs/architecture/plans/2026-05-26-rewind-allocation-optimization.md`, and parts of that plan have already landed — its guard tests exist in the tree (`TestRewindCaptureScratchReuse`, `TestRewindCodecFieldScratchReuse`, `TestCompositeSnapshotOwnership`). Task 9 begins with an explicit reconciliation step; the Phase 3 copy-reduction work must not regress those landed scratch-reuse invariants.
- Phase 4 items 4.1 and 4.6 are not normal optimizations. They are behavior changes unless proven otherwise. Implement them behind tests that compare the old algorithm to the new one or drop the item.

Use an isolated worktree and branch. The repo instructions prefer `bugfix/ai-...` for fixes; this work is remediation/performance without feature semantics, so use:

```bash
git worktree add ../sonic-engine-performance-optimization -b bugfix/ai-performance-optimization develop
cd ../sonic-engine-performance-optimization
```

The `develop` start point is mandatory: the main checkout may sit on a mid-flight branch, and this repo bases work on `develop`, not `master`.

Use your-files-only staging. Do not use `git add -A`.

**Commit trailers:** every phase commit below is a `perf:` commit touching `src/main/`, so the `commit-msg` hook requires either `Changelog: updated` with `CHANGELOG.md` staged or an explicit justification. Unless a task says otherwise, use `Changelog: n/a: aggregated into final perf changelog entry (Task 13)` on Tasks 1-12 and fill the remaining auto-appended trailers according to what is staged.

---

## File Structure

**Primary modified files:**
- `src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java` — partial zero-fill and later snapshot internals.
- `src/main/java/com/openggf/audio/synth/BlipResampler.java` — snapshot restore internals, interpolation optimizations, tail-only history snapshots.
- `src/main/java/com/openggf/audio/smps/SmpsSequencer.java` — evidence-gated fade fallback and tempo sample calculation.
- `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java` — command/scratch fast paths.
- `src/main/java/com/openggf/audio/runtime/PcmHistoryRing.java` and `src/main/java/com/openggf/audio/runtime/AudioOutputFifo.java` — cursor math cleanup.
- `src/main/java/com/openggf/audio/rewind/AudioCommandTimeline.java` and `src/main/java/com/openggf/audio/AudioManager.java` — bounded timeline and tail scans.
- `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java` and `src/main/java/com/openggf/audio/rewind/AudioKeyframeStore.java` — cheaper restore and deferred logical restore.
- `src/main/java/com/openggf/game/session/SessionManager.java` — volatile read-only session accessors.
- `src/main/java/com/openggf/physics/GroundSensor.java` — per-scan `LevelManager`/background-collision caching.
- `src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java` — class-level eligibility memoization.
- `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java` — codec lookup memoization users, array/record clone memoization, blob copy reduction.
- `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java` and `src/main/java/com/openggf/game/rewind/schema/RewindFieldPlan.java` — cached field codecs and typed compact access plans.
- `src/main/java/com/openggf/game/rewind/schema/RewindObjectStateBlob.java` and `src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java` — restore-path non-copying accessors.
- `src/main/java/com/openggf/graphics/PatternAtlas.java` — dirty-rect uploads and int-keyed lookup.
- `src/main/java/com/openggf/graphics/GraphicsManager.java`, `src/main/java/com/openggf/graphics/PatternRenderCommand.java`, `src/main/java/com/openggf/graphics/InstancedPatternRenderer.java`, and `src/main/java/com/openggf/graphics/BatchedPatternRenderer.java` — SAT batching and projection/display-height hoists.
- `src/main/java/com/openggf/graphics/HScrollBuffer.java` and `src/main/java/com/openggf/graphics/VScrollBuffer.java` — persistent upload buffers.
- `src/main/java/com/openggf/level/LevelRenderer.java` and `src/main/java/com/openggf/level/objects/ObjectManager.java` — lazy render-bucket invalidation and in-place object restore.
- `src/main/java/com/openggf/level/rings/RingManager.java` — primitive active index storage.
- `src/main/java/com/openggf/level/LevelManager.java` and `src/main/java/com/openggf/level/LevelTilemapManager.java` — incremental BG rebuild and chunk-desc shift/mask fast path.
- `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java` — S3K dynamic-art batching after atlas changes.
- `CHANGELOG.md` and `docs/status/trace-frontier-log.md` — phase evidence and user-visible performance notes.

**New or expanded tests:**
- `src/test/java/com/openggf/audio/synth/TestBlipDeltaBufferReadSamples.java`
- `src/test/java/com/openggf/game/session/TestSessionManagerReadAccessors.java`
- `src/test/java/com/openggf/physics/TestGroundSensorServiceResolution.java`
- `src/test/java/com/openggf/game/rewind/TestGenericRewindEligibilityCache.java`
- `src/test/java/com/openggf/game/rewind/TestRewindCaptureMemoizationEquivalence.java`
- `src/test/java/com/openggf/level/TestLevelRendererBucketInvalidation.java`
- `src/test/java/com/openggf/level/rings/TestRingManagerActiveIndices.java`
- `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntimeCommands.java`
- `src/test/java/com/openggf/graphics/TestPatternAtlasDirtyUploads.java`
- `src/test/java/com/openggf/level/TestIncrementalBgTilemapWindow.java`
- `src/test/java/com/openggf/graphics/TestSatReplayBatching.java`
- `src/test/java/com/openggf/audio/rewind/TestAudioCommandTimelineIndexing.java`
- `src/test/java/com/openggf/game/rewind/TestRewindInPlaceObjectRestore.java`
- `src/test/java/com/openggf/game/rewind/TestHeldRewindAudioRestoreDeferral.java`
- `src/test/java/com/openggf/audio/smps/TestSmpsSequencerTempoMath.java`
- `src/test/java/com/openggf/audio/synth/TestBlipResamplerBitExactness.java`
- `src/test/java/com/openggf/audio/runtime/TestAudioRingBuffers.java`

**Always-run regression set after each phase:**

```bash
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test
mvn "-Dtest=TestRewind*,TestGameplayModeContextRewindRegistry,TestGameplayModeContextPlaybackController" test
mvn "-Dtest=*TraceReplay" test
```

If any `*TraceReplay` test fails, stop and invoke `trace-replay-bug-fixing` before changing behavior further.

**Distinguishing regressions from pre-existing noise:** a real trace failure reports `First error: frame N`. Ignore lwjgl/glfw `UnsatisfiedLinkError` failures and `TestBundledConfigResource` flakes (known environment noise), and ignore the MSE project-wide `total=` line under `-Dtest` (it counts the whole project, not the selection). Compare failures against the Task 0 baseline list — only a trace that passed at baseline and fails now is a regression.

---

## Task 0: Baseline Measurement Harness

**Files:**
- Modify: `docs/architecture/designs/2026-06-11-performance-optimization-design.md` only if the measured baseline numbers should be recorded there.
- Modify: `docs/status/trace-frontier-log.md` after the initial trace sweep.
- Optional create: `docs/architecture/validation/performance/2026-06-11-baseline.md`

- [ ] **Step 1: Confirm clean source baseline**

Run:

```bash
git status --short
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test
mvn "-Dtest=*TraceReplay" test
```

Expected: no unrelated local edits in the worktree; S3K green list passes; trace failures, if any, match the known pre-work frontier.

- [ ] **Step 2: Capture frame-time and keyframe-spike baseline**

Use the existing `PerformanceProfiler` over the same deterministic driver for every later phase. Prefer AIZ1 trace replay because it exercises core gameplay without relying on interactive timing. Record p50, p99, max frame, and the every-60th-frame keyframe spike.

Record:

```markdown
## Baseline
- Driver:
- Command:
- p50 frame:
- p99 frame:
- max frame:
- keyframe-frame spike:
```

- [ ] **Step 3: Capture held-rewind allocation baseline**

Run a 10-second held rewind with either JFR allocation sampling or GC allocation deltas. Record MB/s, object instances constructed during backward stepping, and audio backend objects constructed during backward stepping.

Expected baseline symptom: repeated `Ym2612Chip`, `PsgChipGPGX`, `BlipResampler`, and object instance construction during held rewind.

- [ ] **Step 4: Capture GPU upload baseline**

Add temporary local counters around `PatternAtlas.endBatch()` upload bytes and BG tilemap upload bytes. Do not commit temporary counters unless converting them into debug-only test helpers. Measure CNZ slot-machine animation and normal AIZ running.

Expected baseline symptom: whole atlas-page uploads for small DPLC changes and full BG window rebuilds on single-column scroll.

- [ ] **Step 5: Commit only durable documentation**

If a baseline doc is created:

```bash
git add docs/architecture/validation/performance/2026-06-11-baseline.md docs/status/trace-frontier-log.md
git commit -m "docs: record performance optimization baseline"
```

Use trailers required by the hook. If only scratch notes/counters were used, do not commit this task.

---

## Task 1: Phase 1A — Exact Audio, Session, and Collision Quick Wins

**Files:**
- Modify: `src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java`
- Modify: `src/main/java/com/openggf/game/session/SessionManager.java`
- Modify: `src/main/java/com/openggf/physics/GroundSensor.java`
- Test: `src/test/java/com/openggf/audio/synth/TestBlipDeltaBufferReadSamples.java`
- Test: `src/test/java/com/openggf/game/session/TestSessionManagerReadAccessors.java`
- Test: `src/test/java/com/openggf/physics/TestGroundSensorServiceResolution.java`

- [ ] **Step 1: Write the `BlipDeltaBuffer` equivalence test**

This is an exact-equivalence change, so a behavioral red-green test is impossible — both old and new code produce identical output. Instead, write an equivalence harness (the same pattern Task 10 Step 4 uses for the resampler): create `TestBlipDeltaBufferReadSamples` in package `com.openggf.audio.synth`, keep a test-local copy of the current eager-fill `readSamples` tail logic as a reference helper, and assert that production `readSamples` output and post-read buffer state match the reference across randomized delta/read sequences, including reads that drain partially, fully, and across `endFrame` boundaries.

Also assert the maintained invariant directly: after each `readSamples`, the region beyond `availableSamples + BUF_EXTRA` is all zero (use package-visible state or `captureSnapshot()` to inspect; adapt to the actual `BlipDeltaBuffer` API — the constructor/accessor signatures in this plan are sketches).

- [ ] **Step 2: Run the new audio test and focused audio regressions**

Run:

```bash
mvn "-Dtest=TestBlipDeltaBufferReadSamples,TestBlipResampler*,TestStreamBackedDeterministicAudioRuntimeCommands" test
```

Expected before implementation: the equivalence harness passes against the current code (it pins behavior; it will catch any deviation introduced by Step 3). Existing tests pass.

- [ ] **Step 3: Implement partial zero-fill**

In `BlipDeltaBuffer.readSamples`, replace the full tail clear:

```java
Arrays.fill(bufferL, remain, size, 0);
Arrays.fill(bufferR, remain, size, 0);
```

with:

```java
int clearTo = Math.min(size, remain + count);
Arrays.fill(bufferL, remain, clearTo, 0);
Arrays.fill(bufferR, remain, clearTo, 0);
```

This preserves the shifted live region and only clears slots vacated by the left shift.

- [ ] **Step 4: Make read-only session fields volatile and unsynchronized**

In `SessionManager`, make these fields `volatile`:

```java
private static volatile WorldSession currentWorldSession;
private static volatile GameplayModeContext currentGameplayMode;
private static volatile EditorModeContext currentEditorMode;
```

Then remove `synchronized` from:

```java
public static WorldSession getCurrentWorldSession()
public static GameplayModeContext getCurrentGameplayMode()
public static EditorModeContext getCurrentEditorMode()
```

Keep `synchronized` on lifecycle mutators and `requireCurrentGameModule()`.

- [ ] **Step 5: Cache service reads per ground-sensor scan**

In `GroundSensor`, introduce local variables at the start of each scan/probe method that currently calls `getLevelManager()` and `isBackgroundCollisionEnabled()` per tile. Pass those values into helper methods rather than resolving services inside the inner tile loop.

Do not change collision rules. The method signatures should look like:

```java
private int getSolidTile(LevelManager levelManager, boolean backgroundCollisionEnabled, int x, int y) {
    ...
}
```

- [ ] **Step 6: Verify quick wins**

Run:

```bash
mvn "-Dtest=TestBlipDeltaBufferReadSamples,TestSessionManagerReadAccessors,TestGroundSensor,TestGroundSensorServiceResolution,TestTerrainCollisionManager" test
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test
mvn "-Dtest=*TraceReplay" test
```

Expected: all previously passing tests still pass; no trace frontier regression.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java \
        src/main/java/com/openggf/game/session/SessionManager.java \
        src/main/java/com/openggf/physics/GroundSensor.java \
        src/test/java/com/openggf/audio/synth/TestBlipDeltaBufferReadSamples.java \
        src/test/java/com/openggf/game/session/TestSessionManagerReadAccessors.java \
        src/test/java/com/openggf/physics/TestGroundSensorServiceResolution.java
git commit -m "perf: remove exact-equivalence hot path overhead"
```

Use `Changelog: updated` if `CHANGELOG.md` is staged in this commit; otherwise justify `Changelog: n/a: internal exact-equivalence performance cleanup`.

---

## Task 2: Phase 1B — Rewind Capture Memoization Quick Wins

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Modify: `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java`
- Test: `src/test/java/com/openggf/game/rewind/TestGenericRewindEligibilityCache.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindCaptureMemoizationEquivalence.java`

- [ ] **Step 1: Write eligibility cache tests**

Create tests with nested classes:

```java
static final class DefaultObject extends AbstractObjectInstance { ... }
static final class OverrideObject extends AbstractObjectInstance {
    @Override public PerObjectRewindSnapshot captureRewindState() { ... }
}
```

Assert `usesDefaultObjectSubclassCapture(DefaultObject.class)` remains true across repeated calls and `OverrideObject.class` remains false. Add a similar badnik subclass if a lightweight test subclass already exists; otherwise keep the object coverage and rely on existing badnik rewind tests.

- [ ] **Step 2: Add class-level memoization**

In `GenericRewindEligibility`, add:

```java
private static final ConcurrentHashMap<Class<?>, Boolean> DEFAULT_OBJECT_CAPTURE_CACHE = new ConcurrentHashMap<>();
private static final ConcurrentHashMap<Class<?>, Boolean> DEFAULT_BADNIK_CAPTURE_CACHE = new ConcurrentHashMap<>();
```

Change the public methods to:

```java
return DEFAULT_OBJECT_CAPTURE_CACHE.computeIfAbsent(type, GenericRewindEligibility::computeUsesDefaultObjectSubclassCapture);
```

and equivalent for badniks. Move current method bodies into private compute methods.

- [ ] **Step 3: Add `RewindCodecs.codecFor(Field)` cache**

In `RewindCodecs`, add:

```java
private static final ConcurrentHashMap<Field, Optional<RewindCodec>> FIELD_CODEC_CACHE = new ConcurrentHashMap<>();
```

Make `codecFor(Field field)` call `computeIfAbsent`. Build the codec exactly as today inside a private helper and call `field.setAccessible(true)` once when a codec is present.

Caching changes codec instances from per-call to shared. Before landing this, verify every `RewindCodec` implementation is stateless (no mutable instance fields used across `capture`/`restore` calls). If any codec holds per-use mutable state, exclude it from the cache and document why; do not share a stateful codec.

- [ ] **Step 4: Add primitive array clone fast paths**

In `GenericFieldCapturer.deepCloneArray`, special-case primitive arrays with native clone methods:

```java
if (value instanceof int[] ints) return ints.clone();
if (value instanceof long[] longs) return longs.clone();
if (value instanceof byte[] bytes) return bytes.clone();
if (value instanceof short[] shorts) return shorts.clone();
if (value instanceof char[] chars) return chars.clone();
if (value instanceof boolean[] booleans) return booleans.clone();
if (value instanceof float[] floats) return floats.clone();
if (value instanceof double[] doubles) return doubles.clone();
```

Leave object arrays on the existing element-wise deep-clone path.

- [ ] **Step 5: Cache record clone reflection**

In `GenericFieldCapturer`, add a `ConcurrentHashMap<Class<?>, RecordClonePlan>`. The plan stores `RecordComponent[]`, accessor `Method[]`, and canonical `Constructor<?>`. Build it once per record class with all accessors/constructor set accessible. `deepCloneRecord` should read the plan and only allocate the argument array and cloned record.

- [ ] **Step 6: Remove per-access reflection in field read/write and restore lookup**

In `GenericFieldCapturer`:

- `readField`/`writeField` currently call `field.setAccessible(true)` on every access. Set accessibility once when fields enter `CAPTURABLE_FIELDS_CACHE` (and once in the codec cache from Step 3) and drop the per-access calls.
- `findField` currently calls `getDeclaredField` per field per object per restore. Cache the resolved `Field` per `(Class, fieldName)` — reuse the capture-side field cache where the same fields are involved.

- [ ] **Step 7: Assert byte-identical capture**

`TestRewindCaptureMemoizationEquivalence` should capture a representative object before and after clearing/reusing caches if possible. If direct before/after old-code comparison is impossible, assert repeated captures of the same object produce equal `GenericObjectSnapshot` contents and equal compact blobs after cache warmup.

Run:

```bash
mvn "-Dtest=TestGenericRewindEligibilityCache,TestRewindCaptureMemoizationEquivalence,TestRewindController,TestRewindRegistry,TestRewindTorture,TestGenericFieldCapturer,TestRewindCaptureScratchReuse,TestRewindCodecFieldScratchReuse" test
```

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/GenericRewindEligibility.java \
        src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java \
        src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java \
        src/test/java/com/openggf/game/rewind/TestGenericRewindEligibilityCache.java \
        src/test/java/com/openggf/game/rewind/TestRewindCaptureMemoizationEquivalence.java
git commit -m "perf(rewind): memoize generic capture reflection"
```

---

## Task 3: Phase 1C — Render Buckets, Ring Active Indices, and Audio Runtime Fast Paths

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelRenderer.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java`
- Modify: `src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java`
- Test: `src/test/java/com/openggf/level/TestLevelRendererBucketInvalidation.java`
- Test: `src/test/java/com/openggf/level/rings/TestRingManagerActiveIndices.java`
- Test: `src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntimeCommands.java`

- [ ] **Step 1: Guard lazy render buckets with an equivalence test**

Add a test that builds a deterministic set of renderable objects, records eager bucket ordering, mutates object priority, and asserts lazy bucket rebuild produces the same bucket order after the mutation. Include at least one object insertion, removal, and priority change.

- [ ] **Step 2: Remove unconditional per-frame invalidation**

In `LevelRenderer`, remove the unconditional `invalidateRenderBuckets()` call in the per-frame path. Audit priority-changing paths in `ObjectManager` and object render-priority setters; every path that changes priority, active renderability, or object membership must call the existing dirty/invalidation hook.

If an untrackable path exists, keep eager invalidation and document why. Do not ship a stale-order risk.

- [ ] **Step 3: Replace boxed ring active indices**

In `RingManager`, replace `ArrayList<Integer> activeIndices` plus stream snapshots with:

```java
private int[] activeIndices = new int[256];
private int activeIndexCount;
private final BitSet activeIndexMembership = new BitSet();
```

Implement `addActiveIndex`, `removeActiveIndex`, and iteration without boxing or snapshot streams. **Iteration order must exactly match the current `ArrayList` behavior** — active-ring order feeds collection and draw order, which is parity-relevant (a player overlapping two rings in one frame collects them in iteration order). Use order-preserving compaction on removal, not swap-with-last. Add a test asserting insertion-order iteration survives interleaved adds and removes; any ordering change is a behavior change and out of scope for this task.

- [ ] **Step 4: Tighten deterministic audio runtime scratch**

In `StreamBackedDeterministicAudioRuntime.consumeCommands`, return immediately when `pendingCommands` is empty. In `ensureScratch`, only reallocate when `scratch.length < samples`; track the valid sample count separately so stale tail values are never consumed.

- [ ] **Step 5: Verify Phase 1C**

Run:

```bash
mvn "-Dtest=TestLevelRendererBucketInvalidation,TestRingManagerActiveIndices,TestStreamBackedDeterministicAudioRuntimeCommands" test
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test
mvn "-Dtest=*TraceReplay" test
```

- [ ] **Step 6: Record Phase 1 measurements**

Re-run the Task 0 frame-time and held-rewind capture baselines. Record p50/p99 and keyframe-frame spike changes. Do not claim acceptance targets yet; Phase 3 carries the larger rewind fixes.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/level/LevelRenderer.java \
        src/main/java/com/openggf/level/objects/ObjectManager.java \
        src/main/java/com/openggf/level/rings/RingManager.java \
        src/main/java/com/openggf/audio/runtime/StreamBackedDeterministicAudioRuntime.java \
        src/test/java/com/openggf/level/TestLevelRendererBucketInvalidation.java \
        src/test/java/com/openggf/level/rings/TestRingManagerActiveIndices.java \
        src/test/java/com/openggf/audio/runtime/TestStreamBackedDeterministicAudioRuntimeCommands.java
git commit -m "perf: remove steady-state frame churn"
```

---

## Task 4: Phase 2A — Dirty-Rect Pattern Atlas Uploads

**Files:**
- Modify: `src/main/java/com/openggf/graphics/PatternAtlas.java`
- Test: `src/test/java/com/openggf/graphics/TestPatternAtlasDirtyUploads.java`

- [ ] **Step 1: Add upload accounting seam for tests**

Introduce a package-private upload sink or counter inside `PatternAtlas` so tests can assert upload rectangle count and byte volume without requiring a live GL context. The production sink calls the existing GL upload methods.

- [ ] **Step 2: Track dirty tile bounds per page during batch mode**

For each page, store min/max dirty tile x/y and dirty slot count. When a tile is written, mark its page dirty and update bounds.

- [ ] **Step 3: Upload small changes as individual tile uploads under one bind**

If dirty slot count is below the design threshold, bind the texture once and issue one `glTexSubImage2D` per dirty tile. The test should dirty 3 non-contiguous tiles and assert upload bytes equal `3 * tileByteSize`, not page size.

- [ ] **Step 4: Upload contiguous dirty rectangles for larger changes**

For larger dirty regions, set `GL_UNPACK_ROW_LENGTH` to the page row width, upload the dirty rectangle, then reset `GL_UNPACK_ROW_LENGTH` to zero. Wrap the reset in `try/finally` style control so later uploads are not poisoned by stale pixel-store state.

- [ ] **Step 5: Verify atlas upload reduction**

Run:

```bash
mvn "-Dtest=TestPatternAtlasDirtyUploads" test
```

Then run a CNZ slot-machine scene with upload accounting. Expected: DPLC animation upload bytes drop by at least 100x compared with Task 0 baseline.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/graphics/PatternAtlas.java \
        src/test/java/com/openggf/graphics/TestPatternAtlasDirtyUploads.java
git commit -m "perf(render): upload dirty pattern atlas regions only"
```

---

## Task 5: Phase 2B — Incremental BG Window and SAT Replay Batching

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/level/LevelTilemapManager.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Modify: `src/main/java/com/openggf/graphics/PatternRenderCommand.java`
- Test: `src/test/java/com/openggf/level/TestIncrementalBgTilemapWindow.java`
- Test: `src/test/java/com/openggf/graphics/TestSatReplayBatching.java`

- [ ] **Step 1: Test single-column BG scroll equivalence**

Build a test that renders or serializes the BG tilemap window for base X `N`, then advances to `N + 16` **and separately retreats to `N - 16`** — leftward scroll is as common as rightward and must take the incremental path too. Assert both directions produce the same bytes as a full rebuild and upload only the one entering column.

- [ ] **Step 2: Implement BG column shift**

In `LevelTilemapManager`, retain the existing byte array between frames. When the zone/act/layout/height/full-width predicate is unchanged and base X moves by exactly one 16 px column **in either direction**, shift the existing bytes and rebuild only the entering column (right edge on advance, left edge on retreat). Fall back to the current full rebuild for multi-column jumps, vertical changes, layout mutation, or zone transitions.

- [ ] **Step 3: Test SAT replay command batching**

Add a test or render-order recorder path proving that `appendDirectReplayCommands` preserves priority transition ordering while reducing per-tile command count.

- [ ] **Step 4: Route SAT replay through the instanced batcher**

In `GraphicsManager.appendDirectReplayCommands`, group replay commands into batches and flush at priority transitions. Cache display height on reshape or batch begin instead of resolving configuration per command.

- [ ] **Step 5: Verify**

Run:

```bash
mvn "-Dtest=TestIncrementalBgTilemapWindow,TestSatReplayBatching,RenderOrderTest" test
mvn "-Dtest=*TraceReplay" test
```

Use visual validation for AIZ/HCZ/CNZ and SAT bonus-stage scenes. Compare screenshots with `s3k-zone-validate` where available.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java \
        src/main/java/com/openggf/level/LevelTilemapManager.java \
        src/main/java/com/openggf/graphics/GraphicsManager.java \
        src/main/java/com/openggf/graphics/PatternRenderCommand.java \
        src/test/java/com/openggf/level/TestIncrementalBgTilemapWindow.java \
        src/test/java/com/openggf/graphics/TestSatReplayBatching.java
git commit -m "perf(render): batch scrolling and SAT replay uploads"
```

---

## Task 6: Phase 2C — Render Lookup and Upload Buffer Cleanup

**Files:**
- Modify: `src/main/java/com/openggf/graphics/PatternAtlas.java`
- Modify: `src/main/java/com/openggf/graphics/HScrollBuffer.java`
- Modify: `src/main/java/com/openggf/graphics/VScrollBuffer.java`
- Modify: `src/main/java/com/openggf/graphics/InstancedPatternRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/BatchedPatternRenderer.java`
- Test: `src/test/java/com/openggf/graphics/TestPatternAtlasDirtyUploads.java`

- [ ] **Step 1: Replace boxed atlas lookup**

Keep `PatternAtlasRange` as the ownership model. For registered ranges, allocate flat `Entry[]` arrays indexed by `patternId - rangeBase`. Use a tiny open-addressing int map only for IDs outside registered ranges. Preserve lookup behavior for IDs under and over `0x7FF`.

- [ ] **Step 2: Make scroll upload buffers persistent**

Allocate one `FloatBuffer` per `HScrollBuffer`/`VScrollBuffer` instance during init or first upload. Reuse it, growing only when needed. Free it during existing dispose/cleanup.

- [ ] **Step 3: Hoist projection/display-height lookups**

In `InstancedPatternRenderer` and `BatchedPatternRenderer`, resolve `isFBOProjectionActive()` and display height once in `beginBatch()` and store fields used by per-command code.

- [ ] **Step 4: Verify**

Run:

```bash
mvn "-Dtest=TestPatternAtlasDirtyUploads,TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading" test
mvn "-Dtest=*TraceReplay" test
```

Then re-run upload and frame-time measurements from Task 0. Expected: atlas upload bytes meet the acceptance target, and per-frame native allocation from scroll buffers disappears.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/PatternAtlas.java \
        src/main/java/com/openggf/graphics/HScrollBuffer.java \
        src/main/java/com/openggf/graphics/VScrollBuffer.java \
        src/main/java/com/openggf/graphics/InstancedPatternRenderer.java \
        src/main/java/com/openggf/graphics/BatchedPatternRenderer.java
git commit -m "perf(render): remove boxed lookups and per-frame upload allocation"
```

---

## Task 7: Phase 3A — In-Place Object Restore

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: `src/test/java/com/openggf/game/rewind/TestRewindInPlaceObjectRestore.java`

- [ ] **Step 1: Test identity reuse and full-state equivalence on matching restore**

Create a rewind test that captures object manager state, steps backward to a snapshot with the same `(spawn, slotIndex, class)`, restores, and asserts the live object instance identity is unchanged.

The same test must also assert **full-state equivalence, not just identity**: restore the same snapshot once in place and once via fresh construction (the current path), then compare the complete field state of the resulting objects reflectively — including fields the snapshot does not capture. This is what catches stale non-captured state on reused instances.

- [ ] **Step 2: Test fallback on membership/class mismatch**

The same test class must cover a despawn/spawn boundary where membership changes. Assert missing objects are constructed and extra objects are destroyed exactly as before.

- [ ] **Step 3: Audit non-captured field guarantees**

The current destroy/recreate path resets *every* field — including `@RewindTransient` and otherwise non-captured fields — to constructor defaults on restore. In-place reuse retains stale values in those fields, and existing `@RewindTransient` annotations were authored under recreate semantics, so some may silently rely on the reset.

Before implementing: enumerate the non-captured fields of default-capture object classes (the `RewindFieldInventoryTool` at `com.openggf.tools.rewind` covers field inventory). Classify each as (a) derivable/reset on next update, (b) construction-constant (services, renderers), or (c) frame-coupled mutable state. Category (c) fields must be explicitly cleared/reinitialized by the in-place restore path or force that class onto the recreate fallback. Record the audit outcome in the test class Javadoc.

- [ ] **Step 4: Implement matching restore path**

In `ObjectManager.restore()`, build a map keyed by the snapshot identity tuple already used by the restore comparator. If the live instance exists and the class matches, call the existing per-object restore path on that instance, plus the category (c) reset from Step 3. Only use destroy/recreate for missing entries, extra entries, class mismatch, or classes the audit flagged as unsafe to reuse.

Do not bypass `ObjectServices` or renderer initialization; reused instances must keep their existing injected services and renderer wiring.

- [ ] **Step 5: Verify rewind determinism**

Run:

```bash
mvn "-Dtest=TestRewindInPlaceObjectRestore,TestObjectIdentityRebindingAcrossRewind,TestRewindTorture,TestRewindTraceSeekDeterminism,TestRewindParityAgainstTrace" test
mvn "-Dtest=*TraceReplay" test
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java \
        src/test/java/com/openggf/game/rewind/TestRewindInPlaceObjectRestore.java
git commit -m "perf(rewind): restore matching objects in place"
```

---

## Task 8: Phase 3B — Held-Rewind Audio Restore Deferral and Cheap Snapshot Restore

**Files:**
- Modify: `src/main/java/com/openggf/game/rewind/RewindController.java`
- Modify: `src/main/java/com/openggf/audio/rewind/AudioKeyframeStore.java`
- Modify: `src/main/java/com/openggf/audio/synth/BlipResampler.java`
- Modify: `src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java`
- Modify: `src/main/java/com/openggf/audio/synth/Ym2612Chip.java`
- Modify: `src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java`
- Test: `src/test/java/com/openggf/game/rewind/TestHeldRewindAudioRestoreDeferral.java`

- [ ] **Step 1: Test release equivalence**

Create a deterministic audio test: play forward to frame `F`, rewind held for `N` seconds using the presentation audio path, release at frame `R`, then compare the logical audio state with a fresh replay to `R`. This test is the gate for deferring intermediate logical restores.

- [ ] **Step 2: Defer logical restore inside active replay scope**

Inside `RewindController`, when `AudioReplayScope` is active for held backward stepping, skip `restoreAudioLogicalState` on intermediate frames. On release/seek commit, perform one logical restore for the committed target frame.

- [ ] **Step 3: Add package-private non-copying snapshot accessors**

For in-memory restore paths only, add package-private accessors on `BlipResampler`/`BlipDeltaBuffer` snapshots that expose owned arrays without copying. Keep public record accessors defensive if they currently form a boundary.

- [ ] **Step 4: Reuse backend instances when fully overwritten**

In `AbstractSmpsAudioBackend`, restore state into existing driver/chip/resampler instances where the snapshot fully overwrites internal state. If any component has construction-time invariants not represented in the snapshot, keep recreate fallback and document the reason in code.

- [ ] **Step 5: Tail-only audio history snapshot**

In `BlipResampler` (and its caller `Ym2612Chip.captureSnapshot`), snapshot only the history window that can be read after restore (the tap window behind `inputIndex`) instead of the full 64 KB ring. Update restore to rebuild the same logical ring state. Because this format is in-memory only, no persisted migration is needed. The Step 1 release-equivalence test gates this change too.

- [ ] **Step 6: Verify**

Run:

```bash
mvn "-Dtest=TestHeldRewindAudioRestoreDeferral,TestRewindTorture,TestRewindTraceSeekDeterminism,TestLiveRewindManagerAudioCleanup" test
mvn "-Dtest=*TraceReplay" test
```

Re-run the held-rewind allocation profile. Expected: no per-intermediate-backward-frame audio driver stack rebuild.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/rewind/RewindController.java \
        src/main/java/com/openggf/audio/rewind/AudioKeyframeStore.java \
        src/main/java/com/openggf/audio/synth/BlipResampler.java \
        src/main/java/com/openggf/audio/synth/BlipDeltaBuffer.java \
        src/main/java/com/openggf/audio/synth/Ym2612Chip.java \
        src/main/java/com/openggf/audio/AbstractSmpsAudioBackend.java \
        src/test/java/com/openggf/game/rewind/TestHeldRewindAudioRestoreDeferral.java
git commit -m "perf(rewind): avoid audio rebuilds during held rewind"
```

---

## Task 9: Phase 3C — Bound Audio Timeline and Compact Rewind Restore Costs

**Files:**
- Modify: `src/main/java/com/openggf/audio/rewind/AudioCommandTimeline.java`
- Modify: `src/main/java/com/openggf/audio/AudioManager.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindFieldPlan.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindObjectStateBlob.java`
- Modify: `src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java`
- Modify: `src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Test: `src/test/java/com/openggf/audio/rewind/TestAudioCommandTimelineIndexing.java`

- [ ] **Step 1: Reconcile against the landed 2026-05-26 rewind allocation plan**

`docs/architecture/plans/2026-05-26-rewind-allocation-optimization.md` covered dropping `CodecFieldSnapshot` accessor clones, `RewindStateBuffer` reset, scratch pooling in both capturers, and `CompositeSnapshot` ownership — and its guard tests exist (`TestRewindCaptureScratchReuse`, `TestRewindCodecFieldScratchReuse`, `TestCompositeSnapshotOwnership`). Read the current code for each Step 5 (blob copies) target before changing it: delete from this task anything already landed, and treat the landed scratch-reuse invariants as constraints the new work must not regress.

- [ ] **Step 2: Test timeline indexing and pruning**

`TestAudioCommandTimelineIndexing` must cover:

- `entryCount()` returns count without allocating a copied list.
- `entriesOnFrame(frame)` walks from the tail and returns only matching recent entries.
- `discardAfter(frame)` truncates from the tail.
- pruning by earliest retained keyframe keeps command indices consistent.

- [ ] **Step 3: Implement tail-oriented timeline operations**

Replace full-list scans in `AudioCommandTimeline` with tail walks for frame-local operations. Store base offsets when pruning so `commandEntryCount` references remain valid.

- [ ] **Step 4: Add typed compact field access**

Extend `RewindFieldPlan` with a precomputed type tag and either a `VarHandle` or typed `Field.getInt`/`setInt` style operations. Replace runtime `type ==` chains in compact capture/restore with a switch over the tag.

- [ ] **Step 5: Reduce restore-path blob copies**

Mirror the existing `CompositeSnapshot.owned()` pattern: keep constructor ownership boundaries intact, but add package-private restore-path accessors that do not clone arrays already owned by immutable snapshots. Skip any copy already removed by the 2026-05-26 plan (Step 1).

- [ ] **Step 6: Precompute object snapshot comparator keys**

In `ObjectManager`, compute sort keys once per snapshot entry instead of string concatenation inside comparators. Remove redundant outer `List.copyOf` when the source is already an owned snapshot list.

- [ ] **Step 7: Verify**

Run:

```bash
mvn "-Dtest=TestAudioCommandTimelineIndexing,TestRewind*,TestCompositeSnapshotOwnership,TestGameplayModeContextRewindRegistry,TestGameplayModeContextPlaybackController" test
mvn "-Dtest=*TraceReplay" test
```

(`TestRewind*` already matches the scratch-reuse guards `TestRewindCaptureScratchReuse` / `TestRewindCodecFieldScratchReuse`.)

Re-run held-rewind allocation and keyframe-spike measurements. Expected: held rewind allocation rate is at least 10x lower than Task 0 baseline; keyframe-frame spike is materially reduced.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/audio/rewind/AudioCommandTimeline.java \
        src/main/java/com/openggf/audio/AudioManager.java \
        src/main/java/com/openggf/game/rewind/schema/RewindCodecs.java \
        src/main/java/com/openggf/game/rewind/schema/RewindFieldPlan.java \
        src/main/java/com/openggf/game/rewind/schema/RewindObjectStateBlob.java \
        src/main/java/com/openggf/game/rewind/schema/RewindStateBuffer.java \
        src/main/java/com/openggf/game/rewind/GenericFieldCapturer.java \
        src/main/java/com/openggf/level/objects/ObjectManager.java \
        src/test/java/com/openggf/audio/rewind/TestAudioCommandTimelineIndexing.java
git commit -m "perf(rewind): bound audio timeline and reduce restore copies"
```

---

## Task 10: Phase 4A — Evidence-Gated Audio Math Changes

**Files:**
- Modify: `src/main/java/com/openggf/audio/smps/SmpsSequencer.java`
- Modify: `src/main/java/com/openggf/audio/synth/BlipResampler.java`
- Modify: `src/main/java/com/openggf/audio/runtime/PcmHistoryRing.java`
- Modify: `src/main/java/com/openggf/audio/runtime/AudioOutputFifo.java`
- Test: `src/test/java/com/openggf/audio/smps/TestSmpsSequencerTempoMath.java`
- Test: `src/test/java/com/openggf/audio/synth/TestBlipResamplerBitExactness.java`
- Test: `src/test/java/com/openggf/audio/runtime/TestAudioRingBuffers.java`

- [ ] **Step 1: Prove closed-form tempo math**

Add parameterized tests comparing current loop behavior to:

```java
ceil(firstRemaining + (ticks - 1) * samplesPerFrame)
```

across representative tempo values, exact-boundary values, and one-off rounding cases.

- [ ] **Step 2: Implement closed-form tempo math**

Replace the loop only after the equivalence test fails on old helper/new helper split and passes with the implementation.

- [ ] **Step 3: Prove fade fallback can be removed**

Before changing `requiresSampleAccurateFallback()`, capture deterministic audio across at least one fade-heavy act transition and any SFX fade path. Compare before/after PCM. If not sample-identical, drop this item and leave a note in the plan or changelog.

- [ ] **Step 4: Prove resampler interpolation bit-exactness**

In `TestBlipResamplerBitExactness`, keep a copy of the old interpolation algorithm as a test-only reference helper. Feed randomized delta streams and assert production output remains byte/sample identical after hoisting center/frac/phase and ring increments.

- [ ] **Step 5: Replace `%`/`floorMod` ring operations**

In `PcmHistoryRing` and `AudioOutputFifo`, replace per-sample wrapping with an integer cursor plus two-segment `System.arraycopy`. Tests must cover wrap, no-wrap, exact-capacity, and repeated wrap cases.

- [ ] **Step 6: Verify**

Run:

```bash
mvn "-Dtest=TestSmpsSequencerTempoMath,TestBlipResamplerBitExactness,TestAudioRingBuffers,TestHeldRewindAudioRestoreDeferral" test
mvn "-Dtest=*TraceReplay" test
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/audio/smps/SmpsSequencer.java \
        src/main/java/com/openggf/audio/synth/BlipResampler.java \
        src/main/java/com/openggf/audio/runtime/PcmHistoryRing.java \
        src/main/java/com/openggf/audio/runtime/AudioOutputFifo.java \
        src/test/java/com/openggf/audio/smps/TestSmpsSequencerTempoMath.java \
        src/test/java/com/openggf/audio/synth/TestBlipResamplerBitExactness.java \
        src/test/java/com/openggf/audio/runtime/TestAudioRingBuffers.java
git commit -m "perf(audio): simplify proven-equivalent sample math"
```

Do not include the fade fallback change in this commit unless the PCM comparison is sample-identical.

---

## Task 11: Phase 4B — Object and Render Allocation Cleanup

**Files:**
- Modify: `src/main/java/com/openggf/level/objects/ObjectManager.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectPlacementController.java`
- Modify: `src/main/java/com/openggf/sprites/SpriteManager.java`
- Modify: `src/main/java/com/openggf/level/objects/ObjectSolidContactController.java`
- Modify: `src/main/java/com/openggf/level/objects/HudRenderManager.java`
- Modify: `src/main/java/com/openggf/graphics/GLCommandGroup.java`
- Modify: `src/main/java/com/openggf/graphics/SpriteSatMaskPostProcessor.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java`
- Test: add focused tests only where an allocation cleanup changes ownership or ordering contracts.

- [ ] **Step 1: Reuse object-pipeline scratch**

Follow the existing `dynamicFallbackScratch` pattern. Add per-frame scratch identity sets/lists/maps for `runExecLoop`, `runPostPlayerHooks`, `syncActiveSpawnsLoad`, `collectActivePlayers`, `buildPlayableUpdateOrder`, `drainPendingCursorLoadSpawns`, and `RidingState`.

- [ ] **Step 2: Keep scratch ownership local**

Every reused collection must be cleared in `finally` or at a single frame-boundary reset point. Do not return mutable scratch collections to callers.

- [ ] **Step 3: Clean small render allocations**

Reuse `staticPieceDesc` in `HudRenderManager`; avoid `List.copyOf` per `GLCommandGroup` where ownership is already clear; skip `SpriteSatMaskPostProcessor` defensive copy when masking is disabled; reuse the captured command execution list in `GraphicsManager`.

- [ ] **Step 4: Batch S3K dynamic-art tile uploads**

After Task 4 lands, update `Sonic3kPatternAnimator` to batch dynamic-art tile uploads under one texture bind and reuse scratch `Pattern`/byte arrays. Do not wrap this path in atlas batch mode until dirty atlas uploads are already verified.

- [ ] **Step 5: Verify**

Run:

```bash
mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" test
mvn "-Dtest=*TraceReplay" test
```

Run allocation profiling on normal AIZ running. Expected: lower steady-state allocation rate without trace changes.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/objects/ObjectManager.java \
        src/main/java/com/openggf/level/objects/ObjectPlacementController.java \
        src/main/java/com/openggf/sprites/SpriteManager.java \
        src/main/java/com/openggf/level/objects/ObjectSolidContactController.java \
        src/main/java/com/openggf/level/objects/HudRenderManager.java \
        src/main/java/com/openggf/graphics/GLCommandGroup.java \
        src/main/java/com/openggf/graphics/SpriteSatMaskPostProcessor.java \
        src/main/java/com/openggf/graphics/GraphicsManager.java \
        src/main/java/com/openggf/game/sonic3k/Sonic3kPatternAnimator.java
git commit -m "perf: reuse object and render scratch allocations"
```

---

## Task 12: Phase 4C — Disassembly-Gated Trig and Level Math

**Files:**
- Modify only after evidence: `src/main/java/com/openggf/level/objects/AbstractBadnikInstance.java`
- Modify only after evidence: `src/main/java/com/openggf/level/objects/BreathingBubbleInstance.java`
- Modify only after evidence: `src/main/java/com/openggf/sprites/managers/TailsTailsController.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Test: add focused tests for power-of-two chunk-desc indexing.

- [ ] **Step 1: Audit trig call sites against disassembly**

For each `Math.sin`/`Math.atan2` call, record the ROM routine and whether the original code uses the 256-step sine table. If a call is engine-original or the ROM uses a different calculation, leave it unchanged.

- [ ] **Step 2: Replace only cited ROM-table sites**

Route cited call sites through `TrigLookupTable`. Commit each call site separately if risk differs by object. Run trace replay after each call-site commit.

- [ ] **Step 3: Cache chunk-desc power-of-two math**

At level load, cache `blockPixelShift` and mask values when dimensions are powers of two. In `getChunkDescAt`, use shifts/masks only under the power-of-two flag and keep the existing div/mod fallback for all other dimensions.

- [ ] **Step 4: Verify**

Run:

```bash
mvn "-Dtest=TestGroundSensor,TestTerrainCollisionManager,TestS3kAiz1SkipHeadless" test
mvn "-Dtest=*TraceReplay" test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "perf(level): use shift math for power-of-two chunk lookups"
```

Commit trig call sites separately with messages naming the object and citing the disassembly note in the body.

---

## Task 13: Final Acceptance and Documentation

**Files:**
- Modify: `CHANGELOG.md`
- Modify: `docs/status/trace-frontier-log.md`
- Optional modify: `docs/architecture/designs/2026-06-11-performance-optimization-design.md`
- Optional create/modify: `docs/performance/2026-06-11-performance-results.md`

- [ ] **Step 1: Full Maven test suite**

Run:

```bash
mvn test
```

Expected: BUILD SUCCESS. If this is too slow for the machine, run the full required subsets and record why `mvn test` was not run.

- [ ] **Step 2: Full trace replay sweep**

Run:

```bash
mvn "-Dtest=*TraceReplay" test
```

Expected: no regression relative to the Task 0 baseline. Update `docs/status/trace-frontier-log.md` with sweep date, command, and result.

- [ ] **Step 3: Re-run all performance captures**

Repeat the Task 0 workloads and record:

- p50/p99/max frame time and keyframe-frame spike.
- held-rewind allocation MB/s and construction counts.
- PatternAtlas upload bytes during CNZ DPLC animation.
- BG-scroll upload/rebuild counts during normal AIZ running.
- Audio timeline retained entries over a long session.

- [ ] **Step 4: Check acceptance criteria**

Confirm:

- Held-rewind allocation rate is reduced at least 10x.
- No audio driver or object instances are constructed on intermediate backward frames.
- Keyframe capture spike is reduced by the measured target or documented if below target.
- DPLC upload bytes are reduced at least 100x.
- BG single-column scroll avoids full-window rebuilds.
- `AudioCommandTimeline.beginFrame` is not O(session length).
- No new dependencies.
- No zone/route/frame carve-outs.

- [ ] **Step 5: Update changelog**

Add one concise `CHANGELOG.md` entry under the current unreleased section:

```markdown
- perf: reduce audio, rendering, and rewind hot-path overhead; held rewind now restores matching objects in place, avoids intermediate audio-driver rebuilds, uploads dirty render regions instead of full pages/windows, and memoizes rewind capture reflection without changing trace replay behavior.
```

- [ ] **Step 6: Final commit**

```bash
git add CHANGELOG.md docs/status/trace-frontier-log.md docs/performance/2026-06-11-performance-results.md
git commit -m "docs: record performance optimization validation"
```

Use `Changelog: updated`, `Agent-Docs: n/a`, and other trailers according to staged files.

---

## Execution Order

1. Task 0 baseline.
2. Tasks 1-3 as Phase 1; stop if any trace regresses.
3. Tasks 4-6 as Phase 2; require visual validation before moving on.
4. Tasks 7-9 as Phase 3; require held-rewind allocation evidence before moving on.
5. Tasks 10-12 as Phase 4; drop evidence-gated items that are not proven equivalent.
6. Task 13 final acceptance.

Do not batch phases into one commit. If a phase misses its measurement target but preserves behavior, record the result and decide whether to continue; do not hide inconclusive measurements.

