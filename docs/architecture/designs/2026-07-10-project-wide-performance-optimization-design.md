# Project-Wide Performance Optimization Design

**Date:** 2026-07-10
**Branch:** `feature/ai-performance-optimization`
**Status:** Approved for implementation

## Requirements

### Goals

1. Reduce live gameplay frame time, allocation rate, native allocation churn, and retained memory across audio, rendering, special stages, rewind, and shared runtime code.
2. Reduce rewind capture/restore and scrub-boundary latency without changing snapshot independence or replay results.
3. Reduce trace-replay and architecture-test wall time and heap usage without weakening comparisons or coverage.
4. Preserve ROM-accurate update order, audio output, render order, pixel output, object-slot allocation, RNG state, rewind identity, and trace comparison semantics.
5. Keep the full default test suite green and add focused performance-equivalence tests for every changed subsystem.

### Non-goals

- No lower-quality resampling, lower chip rate, relaxed trace tolerance, unstable render sorting, state hydration from traces, or reduced rewind duration.
- No zone, route, or frame carve-outs.
- No off-thread gameplay mutation, audio synthesis, render-command production, or rewind restore.
- No new third-party collections or benchmarking dependencies.
- No speculative rewrite when a measured, bounded optimization is available.

### Constraints

- Implementation uses JUnit 5 and test-first development.
- All work remains in the isolated worktree for `feature/ai-performance-optimization`.
- Snapshot-owned buffers are immutable after publication. Render-command buffers remain immutable until the queued command is consumed.
- Audio arithmetic preserves channel order, tap order, rounding, and tempo/event boundaries.
- Virtual pattern IDs continue through `GraphicsManager.renderPatternWithId()` and are never truncated to the VDP 11-bit range.
- Full-suite Maven runs have a single owner because concurrent lifecycles share `target/` and can corrupt compiled-test discovery.

### Acceptance criteria

1. `mvn test` completes with zero failures and errors.
2. Audio-focused changes pass bit-exact PCM comparison tests.
3. Special-stage and render changes pass existing deterministic, SAT, render-order, and visual snapshot tests; captured reference scenes remain pixel-identical.
4. Rewind changes pass capture/restore, torture, seek, COW, identity, coverage, and deterministic replay tests.
5. Trace changes preserve all comparison fields, severities, first-frontier selection, event order, and configured context output.
6. Allocation probes demonstrate that each allocation-focused change removes or bounds the targeted allocation stream.
7. Risky GPU, snapshot-slab, lazy trace-store, and paged-COW work lands only after a focused benchmark demonstrates the expected bottleneck and equivalence tests cover rollback.

## Exploration synthesis

Three independent audits inspected audio and special stages, rendering and memory, and rewind, trace, and tests. The uncontended baseline completed 11,211 tests with zero failures or errors and 12 skips in 3:01. A first baseline attempt failed because two Maven lifecycles overlapped in the shared output directory; a single-owner rerun proved the source tree itself was green.

The strongest agreements were:

- static or derived special-stage geometry is repeatedly rebuilt even when only shader scroll or a small frame selector changes;
- several active render paths allocate temporary arrays, lists, records, lambdas, viewport arrays, or native buffers every frame;
- audio retains a large PCM rewind ring even when rewind history is not armed and repeats stereo FIR setup for identical phases;
- rewind capture still performs uncached legacy-override reflection and publishes many small snapshot objects;
- trace replay eagerly creates strings and object graphs that are unnecessary for passing frames;
- source-analysis guards repeatedly read the same Java tree within one test JVM.

The audits rejected silent-channel skipping, reduced FIR quality, parallel gameplay updates, unstable sorting, mutable snapshot sharing, trace tolerance changes, and palette-reference dirty checks.

## Architecture decision

Work is divided into independently verifiable subsystem plans with a common measurement and integration contract. Each change follows red-green-refactor, receives specification and quality review, and is committed separately. Medium- and high-risk changes are sequenced after the low-risk work so fresh profiles measure the remaining cost rather than historical cost.

### Ownership and boundaries

- **Audio:** `audio`, `audio.runtime`, `audio.rewind`, and `audio.synth` own PCM storage, command consumption, and synthesis arithmetic. Gameplay code only arms/disarms history through existing audio APIs.
- **Render and special stages:** renderer-owned reusable scratch and immutable queued command snapshots stay presentation-only. Palette and pattern versions provide invalidation; gameplay state is not cached in graphics objects.
- **Rewind:** snapshot stores own immutable slabs/pages; live objects never own historical storage. `RewindRegistry` retains restore order and RNG-last semantics.
- **Trace and tests:** loading/indexing may become compact or lazy, but public frame/event order and comparator output remain identical.
- **Measurement:** focused allocation counters and deterministic equivalence tests live beside the subsystem tests. Timing thresholds remain opt-in to avoid noisy CI failures.

### Delivery waves

1. **Measurement and harness repair:** repair `RewindBenchmark`, add allocation/equivalence probes, and capture baseline measurements.
2. **Low-risk exact-equivalence work:** palette staging/deduplication, reusable primitive scratch, lazy PCM history, reflection caches, primitive codecs, source indexes, and pruning cadence.
3. **Bounded caching and batching:** static special-stage FBO/plane caches, immutable decoded-frame cache, advanced-render frame buffers, deferred command pools, GL lifecycle cleanup, trace compact diagnostics, and bounded multi-segment rewind cache.
4. **Structural memory work:** snapshot slabs, derived-state omission with deterministic reconstruction, dense/lazy auxiliary trace store, atlas-aware overflow batching, and paged level COW.
5. **GPU upload architecture:** tilemap ring texture and static floor batching, gated by GPU timings and pixel-parity captures.

### Migration and rollback

There is no persisted-data migration. Every optimization is independently revertible. New caches are rebuilt on reset, GL reinitialization, level/stage load, rewind restore, or content-version change as appropriate. Each risky task retains the existing path as a correctness fallback until its equivalence tests pass; fallback removal is a separate reviewed step.

## Feature design

### Audio

- Allocate backend PCM history on the first arm transition, not device initialization. Disarming does not silently discard history required by an active rewind; hard session teardown releases it.
- Capture mode has one authoritative PCM history owner.
- Stereo FIR walks one phase/tap window once while keeping independent left/right accumulation and rounding order.
- Pending deterministic audio commands are consumed from the already ordered prefix without stream/sort/list allocation.
- Production diagnostic timing and warning formatting are gated behind explicit diagnostics settings.

### Rendering and special stages

- Underwater palette resolution occurs once per frame/content version and uploads once for both consumers through persistent native staging.
- S2 special-stage static background mappings populate their FBO only when invalidated.
- S3K Blue Sphere visible cells and mappings use reusable primitive storage with a stable tie-preserving sort. Static background and floor geometry use context-safe cached batches.
- Slot bonus visible cells use fixed/grow-only primitive buffers with explicit count and queue-lifetime ownership.
- Advanced render state copies into one controller-owned frame buffer and exposes an internal frame-scoped view.
- Deferred render commands, viewport snapshots, display-shader state, and zone overlay state use pools or double buffers that cannot be mutated before execution.
- Direct/overflow pattern rendering caches uniforms and batches by atlas page without changing virtual IDs or draw order.
- GL static resources and command pools are released or cleared during active-context teardown.
- The background tilemap ring uploads only entering columns and advances a shader base; full upload remains the invalidation fallback.

### Rewind, trace, and test infrastructure

- `RewindBenchmark` can traverse non-public record components and produces current retained-size/capture/restore measurements.
- Legacy override and schema decisions are cached by concrete class.
- Primitive arrays use typed clone/copy loops rather than reflective element boxing.
- Object snapshot construction publishes the final record once and packs scalar bytes in snapshot-owned slabs with offsets.
- A bounded two- or three-segment cache improves direction-changing scrub locality; poisoned expansion restores the exact pre-expansion snapshot.
- Level mutation snapshots use page/chunk-granular COW only where mutation profiling proves whole-array cloning is significant.
- Trace comparison stores compact results and formats strings only for mismatches/context windows.
- Auxiliary events use dense/chunked frame indexing and cached type presence; a lazy gzip-backed store is added only if retained-heap measurements justify it.
- Source analyzers share an immutable, per-JVM source index. Policy computation remains uncached.
- History pruning runs only when the retained keyframe floor advances.
- Duplicate `SessionManager.clear()` calls are removed after lifecycle tests prove one call is complete.

## Error handling and edge cases

- Cache invalidation is explicit for GL context loss, resolution change, palette mutation/version change, stage reset, mapping/art reload, rewind restore, and level mutation.
- Equal-depth render entries retain source traversal order.
- Empty or disabled paths return immutable empty views or zero counts without allocating.
- A lazy history arm includes the first sample of the armed interval.
- Snapshot slab/page readers validate schema, bounds, and ownership before restore.
- Trace lazy loading reports malformed gzip/JSON at the same logical frame and preserves source order.
- Segment expansion failure leaves live gameplay, fade state, RNG, and audio state unchanged.

## Verification matrix

| Area | Required proof |
|---|---|
| Audio | bit-exact PCM, tempo/fade parity, rewind audio replay, history lifecycle |
| S1/S2/S3K special stages | deterministic replay, rewind snapshots, stable ordering, visual snapshots |
| Water/render | palette conversion, render order, SAT batching, context reinit, pixel captures |
| Rewind | codec bytes, snapshot independence, graph identity, seek/torture, COW mutation isolation |
| Trace/tests | comparator golden output, context windows, event order, coverage/guard results |
| Integration | S3K safety set, trace replay sweep, `mvn test`, `mvn package` |

## Risks and deferrals

- GPU ring textures and atlas-aware overflow batches have driver/platform risk; they require RenderDoc/GPU timing evidence before replacing the fallback.
- Snapshot slabs and paged COW increase ownership complexity; invariant tests and retained-size measurements are mandatory.
- Lazy gzip trace access may trade heap for random-access CPU. Dense indexing is implemented first and the lazy layer proceeds only if measurements remain compelling.
- Static special-stage caches must not bake mutable palette state into RGBA textures unless palette versioning invalidates them.

No candidate from the approved audit is silently dropped. Evidence-gated candidates remain plan tasks whose first deliverable is a benchmark and whose implementation is skipped only if the benchmark disproves the premise; such a result is documented in the integration report.
