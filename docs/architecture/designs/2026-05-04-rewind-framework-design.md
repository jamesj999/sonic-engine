# Rewind Framework Design

**Date:** 2026-05-04 (revised 2026-05-05 after design-review absorption)
**Status:** Design approved by user; ready for implementation plan.
**V1 target:** Rewind support in the trace-replay visualiser.
**V2 target:** Live in-game rewind during normal gameplay (additive extension; no rewrite).

## Goals

- Game-agnostic rewind framework usable across S1, S2, S3K and any future game module.
- Arbitrary-frame seeking; held-rewind plays backward at the configured framerate; paused single-step both directions.
- Frame-perfect parity between forward simulation and post-rewind replay (verified by trace-replay parity tests).
- Performance suitable for a debugger-class tool: O(1)-amortised backward step inside an active segment; bounded keyframe memory.
- Framework primitives chosen so the v2 live-mode extension is a swap of two implementations (`InputSource`, `KeyframeStore`), not a rewrite.

## Non-goals (v1)

- Audio scrub fidelity. Audio mixer/SMPS sequencer state is not snapshotted; rewinding causes audible artifacts. The audit (Phase A) confirmed no gameplay-path code reads back from audio state, so this is purely cosmetic.
- OpenGL/VRAM snapshots. Render state is re-derived from snapshotted gameplay state on the next forward step.
- Disk-backed history. v1 keeps keyframes in-memory; v2 adds a bounded ring + optional disk spill.
- Rewinding *during* a live recording session. v1 only rewinds within an already-recorded trace.

## Architecture

Five collaborating pieces, all owned by `GameplayModeContext` (the gameplay-scoped session container; see `2026-04-07-runtime-ownership-migration-design.md` for the ownership model). `GameRuntime` is now a thin coordinator that delegates to `GameplayModeContext`; rewind primitives are exposed via the same delegation pattern as `Camera`, `TimerManager`, etc.

```
RewindController        public API: seekTo(frame), step(), stepBackward(),
   |                                currentFrame(), earliestAvailableFrame()
   |
   |-- InputSource          v1: trace-file reader; v2: live input recorder
   |
   |-- KeyframeStore        frame -> CompositeSnapshot
   |                        v1: in-memory map; v2: bounded ring + disk spill
   |
   |-- SegmentCache         transient per-frame snapshot strip around the
   |                        rewind cursor (perf optimisation)
   |
   |-- RewindRegistry       list of RewindSnapshottable subsystems
         |
         |-- RewindSnapshottable<S>  one per subsystem (camera, ObjectManager
                                     (composite), level events, level state
                                     (CoW), palette/anim-tile registries,
                                     GameStateManager, parallax, water,
                                     scroll composers, RNG, ...)

PlaybackController       wraps RewindController with PLAYING/PAUSED/REWINDING
                         state machine driven by visualiser/UI
```

Naming: `RewindSnapshottable` (not bare `Snapshottable`) avoids collision with the existing `MutableLevel.snapshot()` deep-copy semantics and `Block.saveState()/restoreState()` per-block undo semantics.

### Forward step

1. `RewindController.step()` pulls the frame's inputs from `InputSource`.
2. Engine advances one frame normally (existing game loop, unchanged).
3. If `frame % keyframeInterval == 0`, `RewindRegistry.capture()` produces a `CompositeSnapshot` stored into `KeyframeStore`. Capture also bumps the level CoW epoch (see "Level CoW snapshots" below).

### Seek / rewind

1. `seekTo(F)` finds highest stored keyframe `K <= F`.
2. `RewindRegistry.restore(keyframeStore.get(K))` walks the registry and restores each `RewindSnapshottable`.
3. Drive `step()` until `currentFrame == F`.

The replay loop **is** the forward loop. The only thing different about rewind is "restore a keyframe first, then play forward as usual." This is what makes the v2 extension a swap of `InputSource` and `KeyframeStore` implementations rather than a rewrite.

## PlaybackController and rewind UX

```
PlaybackController
  state: PLAYING | PAUSED | REWINDING
  tick():
    PLAYING   -> RewindController.step()
    REWINDING -> RewindController.stepBackward()
    PAUSED    -> no-op
  stepBackwardOnce() / stepForwardOnce()  for paused single-step
```

- Hold rewind button -> `REWINDING`; release -> `PAUSED`.
- Step-back-while-paused -> `stepBackwardOnce()`.
- Hitting `earliestAvailableFrame()` flips state to `PAUSED` and surfaces a "buffer end" event for UI feedback.

`RewindController.stepBackward()` is `seekTo(currentFrame - 1)` clamped to `earliestAvailableFrame()`.

## SegmentCache (perf optimisation)

Naive `seekTo(F-1)` at 60 fps inside a 60-frame keyframe segment gives O(interval^2) sim-steps per rewind second. ~1800 sim-steps/sec backward is borderline in v1 and unacceptable in v2.

**Mitigation:** When the rewind cursor enters segment `[K, K+interval)` for the first time, expand it once: restore K, step forward to the current frame capturing every frame's snapshot into an in-memory strip. Subsequent backward steps within the segment are O(1) snapshot lookups. When the cursor crosses into the previous segment `[K-interval, K)`, expand that one and recycle the now-future strip.

The `RewindController` <-> `KeyframeStore` interface stays the same; `SegmentCache` sits between them, invisible to callers. Memory budget knob: "expand at most M segments on either side of the cursor."

This decouples keyframe interval from rewind cost: rewind cost is independent of N because each segment is expanded once per visit.

## RewindSnapshottable interface and registry

```java
public interface RewindSnapshottable<S> {
    String key();              // stable id, e.g. "camera", "object-manager"
    S capture();               // typically returns an immutable record
    void restore(S snapshot);
}

public final class RewindRegistry {
    void register(RewindSnapshottable<?> s);
    void deregister(String key);
    CompositeSnapshot capture();           // map<key, Object>, registration order
    void restore(CompositeSnapshot cs);
}
```

Per-subsystem snapshots are records (`CameraSnapshot`, `ObjectManagerSnapshot`, ...). Type-safe, zero serialization tax in v1, easy to inspect in a debugger. V2's disk-spill story adds a `serialize(ByteBuffer)` per record (or plug Kryo) -- additive, not a rewrite.

Capture and restore are atomic per frame -- no subsystem is mid-step during the operation, so registration order does not affect correctness. Use registration order for predictable diffing during debugging.

`RewindRegistry` is a field on `GameplayModeContext`. Subsystems register on construction during the runtime build-up; deregister happens automatically when `GameplayModeContext` is torn down (see "Lifecycle and editor mode" below).

### Composite vs per-instance snapshots

Subsystems that own collections of dynamic instances must register a single composite `RewindSnapshottable`, not one per instance. Examples:

- `ObjectManager` registers one `ObjectManagerSnapshottable` whose snapshot record holds the slot inventory (`usedSlots`, `reservedChildSlots`, `frameCounter`, `vblaCounter`, `currentExecSlot`, `peakSlotCount`, `bucketsDirty`) plus per-slot state. On restore, `ObjectManager` re-instantiates objects from the snapshot — sprite identity tracks the slot, not a Java reference. This avoids registry churn (sprites spawn/despawn every frame) and ensures destroyed sprites are correctly recreated on rewind past their destruction.
- `RingManager.LostRingPool` follows the same pattern.
- The pattern animators (`AniPlcScriptState`, `Sonic1PatternAnimator`, `Sonic3kPatternAnimator`) register composite snapshots of their channel/script counter state.

### Object identity rebinding

Cross-subsystem references to dynamic instances (camera target, `LevelEventManager` "boss spawned this frame" flags, child-spawn parent links) break across rewind because the referenced object is recreated on restore. The contract:

- Snapshots store **stable identifiers** — slot index, the `ObjectSpawn` reference itself (immutable level layout), sprite role enum — never raw runtime Java references.
- `ObjectSpawn` references coming from `Level.getObjects()` are stable across rewind because the level layout is immutable; `IdentityHashMap` keying in `ObjectManager.activeObjects` / `instanceToSpawn` / `reservedChildSlots` therefore round-trips cleanly. **Child-spawn `ObjectSpawn`s** (created at runtime by parents) are NOT layout-immutable — they must be regenerated identically by deterministic spawn logic on restore. The composite `ObjectManagerSnapshot` records the parent identifier and the spawn parameters; restore re-runs the parent's spawn logic to produce a fresh `ObjectSpawn` instance with equivalent identity.
- On restore, holders re-resolve identifiers to the freshly recreated instances. The composite snapshot for the holding subsystem includes the identifier.
- Where the cross-reference can be derived from gameplay state at any time (e.g., camera target is the active player sprite), no rebinding is needed — the holder re-resolves on demand.

Subsystems known to hold cross-references: `Camera` (target sprite), `LevelEventManager` subclasses (boss reference), `ObjectManager` (parent-child links via `reservedChildSlots`), `SpriteManager` (active player references).

### Side-effecting restore

Some subsystems' `restore()` interacts with non-snapshotted external state (GL VRAM, dirty-region machinery, audio re-trigger). The contract:

- `restore()` must leave the subsystem in a state where the next forward `step()` reproduces correct behaviour. It does not need to re-derive every byte of external state itself; the next step's normal flow is allowed to do that work.
- Specifically: the level snapshot's `restore()` does NOT re-upload patterns to VRAM directly. It marks dirty regions on `LevelManager` so the next frame's `processDirtyRegions()` re-uploads. This avoids double-uploads and respects the existing GL plumbing.
- Audio re-trigger relies on `LevelManager`/`LevelEventManager` calling `playMusic` during normal level state setup. Since gameplay never reads audio state (Phase A audit confirmed), missed triggers are cosmetic, not semantic.
- Pattern animators' `restore()` resets counters; the next frame's animator step regenerates the pattern bytes from ROM source data.

## Level state snapshots — copy-on-write

Level tile/block/chunk/pattern/map data is the heaviest single subsystem (~250 KB per S2 snapshot, ~400 KB per S1, larger for S3K). At 1 Hz keyframes, full deep-copy is 15–25 MB/min; for a debugger that wants long history, that's the optimisation worth solving day 1.

### Why CoW, not delta

The original spec proposed `DeltaSnapshottable` for level data, citing existing dirty-region tracking. Phase A confirmed that's wrong: the dirty-region BitSets in `MutableLevel` (and the redraw hints in `MutationEffects`) are **redraw signals with no before-image**, cleared every frame at `LevelManager.processDirtyRegions()` (`LevelManager.java:456-486`). `ZoneLayoutMutationPipeline` is a forward-intent queue, not a journal.

Mutations are also rare: typical gameplay frame mutates 0 cells; destruction events mutate 1–10 cells. For this access pattern, copy-on-write is strictly better than a delta journal — same memory footprint, simpler invariants, no per-mutator instrumentation. CoW is the only level-state optimisation in v1; `DeltaSnapshottable` is dropped from the design entirely.

### CoW design

1. `AbstractLevel` (not `MutableLevel`) holds the snapshot/CoW affordance. `Sonic1Level`, `Sonic2Level`, `Sonic3kLevel` extend `AbstractLevel` directly — gameplay never goes through `MutableLevel`, which is editor-only. Lifting the machinery up means both editor and gameplay paths share it.
2. `AbstractLevel` exposes a `snapshotEpoch` counter. `RewindController.capture()` bumps it.
3. `Block`, `Chunk`, `Map` track their own `lastTouchedEpoch`. On any mutation, the mutator first calls `cowEnsureWritable()` which, if `lastTouchedEpoch < currentEpoch`, clones the backing array and updates the epoch. Subsequent mutations within the same epoch are direct. **`Pattern` is excluded from CoW** — its only gameplay mutation surface is `pattern.copyFrom(...)` from animators, and pattern bytes are declared derived state (see "Animated tile patterns are derived state" below).
4. The level snapshot record holds **references** to the current `Block[]`, `Chunk[]`, `Map` data structures — not copies. Capture is O(arrays), not O(bytes).
5. Restore replaces the level's internal references atomically and bumps the epoch so future mutations CoW from the restored arrays. Dirty regions are also marked so `processDirtyRegions()` re-uploads on the next frame. The rewind restore path uses array-reference replacement, not `Block.restoreState()` — because `Block.restoreState()` allocates new `ChunkDesc` instances (`Block.java:76-78`) which would defeat reference-equality detection on the next CoW capture.
6. `Block.saveState()/restoreState()` is unchanged — it stays as the underlying serializer for editor undo/redo. CoW means it is not invoked in the rewind capture/restore path.
7. `Map.getData()` is the only raw-array escape, used solely by editor `MutableLevel.snapshot()` for deep copy (read-only). Gameplay never writes through `Map.getData()`; CoW at the `setValue` boundary is sufficient. Audit conclusion confirmed during Phase A.

### Pipeline migration prerequisite

CoW is centralized in `DirectLevelMutationSurface` — the gameplay-side path that `ZoneLayoutMutationPipeline` writes through — but only if **every** gameplay mutation routes through the pipeline. Today, 7 callsites bypass it and write directly via `level.getMap().setValue(...)`:

- `Sonic1LZEvents.java:111` — LZ3 layout gap chunk swap
- `Sonic1LZWaterEvents.java:1183` — LZ3 water-slide chunk write
- `Sonic1EndingSonicObjectInstance.java:349-350` — ending layout patch
- `Sonic2RivetObjectInstance.java:233,238` — rivet collapse layout rows
- `Sonic2TornadoObjectInstance.java:1127` — Tornado layout patches
- `Sonic3kMGZEvents.java:1770` — raw FG clear (sibling to pipelined path on `:1780`)
- `S3kSeamlessMutationExecutor.java:233,247` — AIZ seamless terrain swaps

These must be migrated onto `LayoutMutationContext.surface().setBlockInMap(...)` before CoW lands. Editor commands (`PlaceBlockCommand`, `Derive*Command`) are exempt — they are not gameplay paths.

### Animated tile patterns are derived state

Pattern animators write to `level.getPattern(destIndex)` at 60 Hz from `AniPlcScriptState.java:110`, `Sonic1PatternAnimator.java:396,624,723,746`, `Sonic3kPatternAnimator.java:787,825`. CoW per-pattern at that rate is feasible but unnecessary: pattern bytes are deterministically derived from animator counter/script state plus ROM source data.

The framework therefore:

- Does NOT CoW or snapshot animated tile pattern bytes.
- DOES snapshot the animator counter/script state (`AniPlcScriptState`, `Sonic1PatternAnimator`, `Sonic3kPatternAnimator` each register a `RewindSnapshottable`).
- On restore, the next forward step's animator tick regenerates pattern bytes from the snapshotted counters. Visually, pattern animation resumes one frame late on rewind — acceptable for a debugger.

The same logic applies to palette cycling: snapshot the cycle counters in `PaletteOwnershipRegistry` / `AnPal` runners; palette colour bytes are re-derived.

## Determinism boundary

`GameplayModeContext` (per `GameplayModeContext.java:34-53`) owns every gameplay-scoped subsystem. The determinism table:

| Subsystem | Owner | In snapshot | Notes |
|---|---|---|---|
| `Camera` | `GameplayModeContext` | Yes | per-instance `RewindSnapshottable` |
| `TimerManager` | `GameplayModeContext` | Yes | per-instance |
| `GameStateManager` (rings/score/time/lives) | `GameplayModeContext` | Yes | tiny, per-instance |
| `FadeManager` | `GameplayModeContext` | Yes | mid-fade state must round-trip |
| `GameRng` | `GameplayModeContext` | Yes | seed-restore via `setSeed` (`GameRng.java:114-116`) — confirmed clean |
| `SolidExecutionRegistry` | `GameplayModeContext` | Yes | `clearTransientState()` proves per-frame state |
| `WaterSystem` | `GameplayModeContext` | Yes | water level + surface phase |
| `ParallaxManager` | `GameplayModeContext` | Yes | scroll state, ripple data, scatter-fill |
| `TerrainCollisionManager`, `CollisionSystem` | `GameplayModeContext` | Yes | trace recorder buffers (read-only in v1 — comparison-only invariant) |
| `SpriteManager` | `GameplayModeContext` | Yes (composite) | active player references rebound on restore |
| `LevelManager` | `GameplayModeContext` | Yes | tilemap cache + dirty-region consume-state |
| Level (`Sonic1/2/3kLevel`) tile data | via `LevelManager` | Yes (CoW) | see "Level state snapshots" |
| `ObjectManager` | `GameplayModeContext` | Yes (composite) | slot inventory + per-slot state in one record |
| `RingManager.LostRingPool` | `GameplayModeContext` | Yes (composite) | same pattern |
| `ZoneRuntimeRegistry` | `GameplayModeContext` | Yes | typed per-zone runtime state |
| `PaletteOwnershipRegistry` | `GameplayModeContext` | Yes | counters; colour bytes derived |
| `AnimatedTileChannelGraph` | `GameplayModeContext` | Yes | counters; pattern bytes derived |
| `SpecialRenderEffectRegistry`, `AdvancedRenderModeController` | `GameplayModeContext` | Yes | staged effect state |
| `ZoneLayoutMutationPipeline` | `GameplayModeContext` | Yes | pending-intent queue snapshotted along with level |
| `AbstractLevelEventManager` (per-game subclass) | `GameplayModeContext` (via providers) | Yes | `eventRoutineFg` / `eventRoutineBg` counters, boss spawn flags |
| `OscillationManager` | global static (today) | Yes | `values[16]`, `deltas[16]`, `activeSpeeds`, `activeLimits`, `control`, `lastFrame`, `suppressedUpdates`. Ticked from `LevelManager.java:889` every frame. Read by S1 swing platforms / saws, S2 ARZ platforms, water tables, and MZ magma animator. Day-1 work either moves it onto `GameplayModeContext` or registers a `RewindSnapshottable` against the static fields. Without this, MZ magma re-derivation is non-deterministic. |
| `RingManager` (incl. `LostRingPool`) | `GameplayModeContext` | Yes (composite) | placement state, sparkle counters, lost-ring physics |
| Pattern animators (`AniPlcScriptState`, `Sonic1PatternAnimator`, `Sonic3kPatternAnimator`) | per-game | Yes (counters incl. host-class state) | pattern bytes are derived state, not snapshotted. Snapshot must include host-class private fields (`MzMagmaAnim.timer`, `MzLavaSurfaceAnim.currentFrame`, etc.) — not just per-channel `AniPlcScriptState`. MZ magma also reads `OscillationManager`, so derivation only round-trips when oscillator state is snapshotted (above). |
| PLC art registries (`Sonic2PlcArtRegistry`, `Sonic3kPlcArtRegistry`) | per-game | Yes | mid-load PLC state must round-trip; per-game work, not free with the runtime stack |
| `Sonic2SpecialStageManager` | per-game | **Out of scope v1** | special stage rewind deferred |
| OpenGL VRAM / FBO contents | engine-global | No | re-derived on next draw via `LevelManager.processDirtyRegions()` |
| Audio mixer + SMPS sequencer state | engine-global | No (v1) | Phase A audit: gameplay never reads audio state, so safe to skip |
| `Engine.display()` wall-clock accumulators (`lastFrameTime`, lag counter) | engine-global | No | wall-clock; intentionally excluded |
| Trace recorder buffers | per-test | Read-only in v1 | comparison-only invariant from `trace-replay-bug-fixing` |
| Input state | external | From `InputSource` | sourced, not snapshotted |

**Hard rule:** any RNG used during simulation must route through `GameRng` (owned by `GameplayModeContext`). Phase A audit confirmed every gameplay-path `java.util.Random` / `ThreadLocalRandom` / `Math.random` has been ported. A CI guard test (mirroring the `TestObjectServicesMigrationGuard` source-scan pattern) prevents regression: `MasterTitleScreen.java` is the sole allow-listed pre-gameplay user.

## Lifecycle and editor mode

`GameplayModeContext` is created by `RuntimeManager` on level load, torn down on level exit. `RuntimeManager.parkCurrent` was removed in favour of teardown+rebuild for editor mode (`SessionManager` owns world preservation). The rewind buffer's lifecycle mirrors this:

- The `RewindRegistry`, `KeyframeStore`, and `SegmentCache` are fields on `GameplayModeContext` (or a `RewindController` it owns). They are created with the context and torn down with it.
- Entering editor mode invalidates the rewind buffer entirely. On resume, the buffer starts empty.
- A stale `RewindController` reference cannot point at a fresh session's state because the controller is keyed to the current `GameplayModeContext` instance.

This rules out a class of "rewind across editor-park" bugs by construction.

## V1 -> V2 extension

What changes for v2 live mode:

- `InputSource`: trace-file reader -> `LiveInputRecorder` capturing user inputs each frame.
- `KeyframeStore`: in-memory map -> bounded ring buffer + optional disk spill for older keyframes.
- `RewindController.earliestAvailableFrame()` reflects ring eviction policy.
- `RewindSnapshottable` records gain `serialize(ByteBuffer)` for disk spill (additive).

What does **not** change:

- `RewindSnapshottable` implementations on every subsystem.
- Level CoW machinery on `AbstractLevel`.
- `SegmentCache`.
- `PlaybackController` and its state machine.
- All capture/restore logic.
- Trace-replay parity tests (continue running in CI against trace-mode).

## Migration story

The framework's real value is the registry pattern: subsystems opt in incrementally, and rewind correctness grows monotonically with each `RewindSnapshottable` implementation.

1. **Day 1 ship:** all framework pieces (`RewindSnapshottable`, `RewindRegistry`, `RewindController`, `PlaybackController`, `KeyframeStore`, `SegmentCache`, `InputSource`). Lift snapshot/CoW affordance to `AbstractLevel`. Implement `RewindSnapshottable` for the obvious wins: `Camera`, `TimerManager`, `GameStateManager`, `FadeManager`, `GameRng`, `ObjectManager` (composite), `LevelManager` (level CoW snapshot wired through `AbstractLevel`), `AbstractLevelEventManager`. Wire visualiser.
2. **Day 1 prerequisites:**
   - Migrate the 7 gameplay-path level mutation stragglers onto `ZoneLayoutMutationPipeline` (so CoW centralizes in `DirectLevelMutationSurface`).
   - Add the RNG CI guard test.
   - Add the engine-side trace-tail dumper or reframe the parity test (see "Testing strategy" item 3).
3. **Incremental:** as gaps surface ("rewinding past a destroyed monitor leaves it gone"), each subsystem gets `RewindSnapshottable`. Already-runtime-owned frameworks (`PaletteOwnershipRegistry`, `AnimatedTileChannelGraph`, `ZoneLayoutMutationPipeline`, `SpecialRenderEffectRegistry`, `AdvancedRenderModeController`, scroll composers) implement once and cover all games. Per-game pattern animators and PLC registries are per-game work; spec acknowledges this is not free with the runtime framework stack.
4. **Convergence check:** the trace-replay parity test (below) is the safety net.

There is no central list of "things that must be snapshotted." New gameplay systems added during the runtime ownership migration get rewind support for free as long as they implement `RewindSnapshottable`.

## Testing strategy

1. **Unit tests per `RewindSnapshottable`:** capture -> mutate -> restore -> assert equality with pre-mutation state.
2. **`SegmentCache` tests:** forward-only replay equivalence at every frame in a segment.
3. **Trace-replay parity (keystone test):** for randomly chosen frame F in each S1/S2/S3K reference trace, do `play to F+100; record divergence vector A from LiveTraceComparator; rewind to F; replay forward to F+100; record divergence vector B; assert A == B`. This catches every unsnapshotted mutable field. **Prerequisite:** today, `LiveTraceComparator` exposes only counters (`errorCount`, `warningCount`) and a 5-entry `mismatches` ring (`trace/live/LiveTraceComparator.java:33,37`); per-frame divergent-field details are consumed locally by `absorbDivergentFields` (`:135-187`) and not exposed. The rewind spec owns extending `LiveTraceComparator` to emit a per-frame `List<FrameComparison>` (or invoke a per-frame callback) so two engine runs can be compared field-for-field. **Decision:** divergence-vector comparison preferred over an engine-side `TraceFrame[]` dumper — smaller surface, respects the comparison-only invariant from `trace-replay-bug-fixing`.
4. **Level CoW round-trip:** mutate a Block/Chunk/Map after capture, verify capture epoch isolation; restore; verify mutation reverted, dirty regions correctly marked. (Pattern bytes are derived state and tested via the animator round-trip — capture animator counters, mutate pattern bytes externally, restore counters, verify next animator step regenerates correct bytes.)
5. **Object identity rebinding tests:** spawn a child sprite at frame N, destroy it at frame N+30, rewind to N+15, assert the child exists with correct state and parent reference resolved correctly.
6. **`PlaybackController` state-machine test:** PLAYING/PAUSED/REWINDING transitions, boundary clamp on `earliestAvailableFrame`.
7. **Act-boundary and warp tests:** rewind across an act transition (S2 EHZ1->EHZ2) and across a warp/seamless terrain swap (S3K AIZ). These exercise mid-load PLC state and tilemap rebuild — exactly the cases most likely to desync.

The trace-replay parity test is the strongest possible coverage gate and reuses infra we already trust.

## Performance benchmark

A dedicated headless benchmark harness drives a real reference trace through the rewind framework and measures the operations that matter. Lives at `src/test/java/com/openggf/game/rewind/RewindBenchmark.java`, runnable via `mvn test -Dtest=RewindBenchmark` and excluded from default CI to avoid flake. Runs against an existing S2 (or S3K) reference trace from `src/test/resources/traces/`.

**Phases (per trace, warm + measured):**

1. **Forward playback overhead.** Run the trace start-to-end with rewind framework enabled vs disabled. Reports per-frame mean/p50/p95/p99/max (ns) and total wall time. Catches regressions in `RewindSnapshottable.capture()` cost and registry walk.
2. **Keyframe capture cost.** Isolated measurement of `RewindRegistry.capture()` and `CompositeSnapshot` allocation. Per-subsystem breakdown so we can see which `RewindSnapshottable` is the hot one.
3. **Keyframe restore cost.** Isolated `RewindRegistry.restore(...)` time, per-subsystem breakdown.
4. **Cold seek.** `seekTo(F)` with the segment cache empty -- measures restore + replay-forward to target.
5. **Hot seek (segment-cache scrub).** Hold-rewind simulation: `stepBackward()` repeatedly across one segment, then crossing a segment boundary, then across multiple segments. Reports steady-state per-frame backward cost and the segment-boundary spike.
6. **Memory.** Reports keyframe count, average snapshot size per subsystem, peak `SegmentCache` strip size, level CoW array reuse rate, and total resident bytes. Useful for sizing v2's bounded ring.

**Outputs:** human-readable table to stdout plus a machine-readable JSON dump (so we can later track regressions in CI as a budget gate, not just locally). Baseline numbers committed alongside the harness so we have a regression check.

**Why headless and not CI by default:** numbers vary by host. Run locally on demand to validate optimisations or chase regressions; only the budget gate (e.g., "p95 forward step <= 0.5ms on developer machines") is enforced. The harness is the same tool we'll point at v2 later -- live mode just swaps `InputSource` and `KeyframeStore` while keeping the measurement scaffolding.

**Long-tail determinism gate (free with the benchmark):** the hot-scrub phase doubles as a determinism canary. After scrubbing back N seconds and playing forward to the original frame, the engine state must still match the trace's reference data. If it desyncs, some mutable field is escaping the registry. The longer we can rewind without desync, the more confident we are that the determinism boundary is closed. We assert this explicitly in the benchmark and report "longest clean rewind" alongside the perf numbers. Multi-second clean rewinds against a real trace are the strongest practical evidence we have that no gameplay state is leaking outside the snapshot surface — short of the keystone parity test (Testing strategy item 3), which exercises the same property at fixed offsets.

## Package layout

- `com.openggf.game.rewind` (game-agnostic):
  - `RewindSnapshottable`, `CompositeSnapshot`, `RewindRegistry`
  - `KeyframeStore`, `SegmentCache`
  - `InputSource`, `RewindController`, `PlaybackController`
- `com.openggf.game.rewind.snapshot` (per-subsystem snapshot records, where they don't fit naturally on the owning subsystem)
- `com.openggf.game.rewind.trace` (v1 trace-backed `InputSource` + `KeyframeStore` glue)
- (v2) `com.openggf.game.rewind.live` (live `InputSource`, ring `KeyframeStore`, disk spill)

`RewindSnapshottable` implementations live next to their owning subsystems (e.g., `Camera implements RewindSnapshottable<CameraSnapshot>`). The level CoW affordance and `snapshotEpoch` live on `AbstractLevel`; `Block`, `Chunk`, `Map` track per-instance `lastTouchedEpoch`. `Pattern` is excluded — its bytes are derived state.

## Hard prerequisites for day-1 ship

These are gating items, not "would be nice". All four ship in the same PR as the rewind framework.

1. **Migrate 7 gameplay-path level mutation stragglers onto `ZoneLayoutMutationPipeline`** (LZEvents, LZWater, EndingSonic, Rivet, Tornado, MGZ raw FG clear, AIZ seamless). Without this, CoW cannot centralize in `DirectLevelMutationSurface`. Scope: **small** (~half-day, 7 callsites).
2. **Lift snapshot/CoW machinery to `AbstractLevel`.** `MutableLevel` is editor-only; gameplay `Sonic1/2/3kLevel` extend `AbstractLevel` directly. Touches `Block`/`Chunk`/`Map` mutation surfaces, the `DirectLevelMutationSurface` seam, and editor commands. Scope: **medium**.
3. **Extend `LiveTraceComparator` to expose per-frame divergence vectors** for the keystone parity test (`trace/live/LiveTraceComparator.java:135-187` currently consumes them locally). Add a per-frame callback or `List<FrameComparison>` accessor so two engine runs can be compared. Decision committed: divergence-vector comparison, not engine-side TraceFrame dumper. Scope: **medium** (the schedule risk because it's net-new test infra).
4. **RNG CI guard test** to lock in the clean Phase A audit state. Mirror the `TestObjectServicesMigrationGuard` source-scan pattern. Scope: **small**.
5. **Snapshot OscillationManager.** Either move it onto `GameplayModeContext` or register a `RewindSnapshottable` against the static fields. MZ magma re-derivation is non-deterministic without it. Scope: **small**.

## Open questions resolved

- **Live recording or post-hoc?** v1 = post-hoc (trace replay); v2 = live. Framework primitives chosen for both.
- **Delta vs full snapshots?** Neither. Full snapshots default for everything except level data, which uses CoW. `DeltaSnapshottable` interface dropped from the design entirely.
- **Audio scrub?** Out of scope for v1. Phase A audit confirmed gameplay never reads audio state, so safe to skip.
- **Rewind UX?** Hold-to-rewind at framerate; paused single-step both directions; auto-stop at buffer end.
- **Owner of rewind primitives?** `GameplayModeContext` (since `2026-04-07` runtime ownership migration).
- **Editor mode interaction?** Teardown+rebuild invalidates the rewind buffer; on resume the buffer starts empty.
