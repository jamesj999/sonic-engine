# Sonic Engine Performance Breakdown

Frame budget at 60 FPS = **16.67 ms / frame**. Audio runs in parallel at 44.1 kHz output (~22.6 µs/sample).

## 1. Existing Profiling Infrastructure

The engine already has a real-time profiler and a benchmark harness; both are functional but **coverage is partial**.

### Runtime profiler (gated by the debug performance overlay)

Enabled in `Engine.display()` only when `debugViewEnabled && !isNativeImage() && debugOverlayManager.isEnabled(DebugOverlayToggle.PERFORMANCE)` (`Engine.java:1162-1164`). When any of those is false, `beginSection`/`endSection` short-circuit and nothing is measured.

| File | Role |
|------|------|
| `src/main/java/com/openggf/debug/PerformanceProfiler.java` | Singleton, `nanoTime()`-based section timers, 60-frame rolling avg, 120-frame history |
| `src/main/java/com/openggf/debug/ProfileSnapshot.java` | Immutable per-frame snapshot (% per section, sorted) |
| `src/main/java/com/openggf/debug/MemoryStats.java` | JMX heap, GC count/time, `ThreadMXBean.getThreadAllocatedBytes` alloc rate, per-section alloc top-5 |
| `src/main/java/com/openggf/debug/PerformancePanelRenderer.java` | Overlay: pie chart + history graph + heap/GC/top allocators |

**Sections instrumented** (`beginSection`/`endSection` calls). **Sections do not nest** — `PerformanceProfiler.beginSection` implicitly ends the active section (`PerformanceProfiler.java:153, 158-167`). The result is a series of non-overlapping segments, so the pie chart sums correctly, but "outer" labels like `update` and `render` only cover the prologue before the first nested section starts, not the whole phase.

`PerformanceProfiler.recordSectionTime(name, elapsedNanos)` is the non-truncating alternative: it credits elapsed nanos to a named section *and* shifts the active section's start timestamp forward by the same amount, so the non-overlapping invariant is preserved without ending the active section. Used by `PatternAtlas.endBatch()` so DPLC-driven uploads don't truncate `render.sprites`.

| Section | Site | Notes |
|---------|------|-------|
| `update` | `Engine.java:1247` | Outer marker — truncated as soon as `timers` (or whatever runs first) begins. Not a whole-tick total. |
| `render` | `Engine.java:1251` | Outer marker — truncated by the first `render.*` sub-section. |
| `debug` | `Engine.java:1293` | Debug overlay render as a whole. No sub-breakdown. |
| `timers`, `input`, `audio` | `GameLoop.java:545, 549, 964` | Pre-tick / post-tick; each is truncated by the next section that starts. |
| `physics`, `objects`, `post-player-hooks`, `camera`, `level` | `LevelFrameStep.java:90, 94, 98, 101, 107, 130, 145` | Wrapped via `LevelFrameStep`. Collision, rings, and sprites are *not* broken out — they live inside `physics` / `objects` / `level`. |
| `render.water_setup`, `render.bg`, `render.fg`, `render.fbo_compose`, `render.sprites` (×2), `render.hud` | `LevelRenderer.java:556, 561, 573, 617, 897/1002, 653` | Per-pass draw breakdown. `render.fbo_compose` wraps the high-priority FBO compose (formerly named `render.fg.priority`). |
| `render.atlas_upload` | `PatternAtlas.java:414-438` | GPU atlas upload (`glTexSubImage2D` per dirty page). Recorded via `recordSectionTime` so DPLC bursts mid-`render.sprites` don't truncate the outer section. |
| `audio.music_stream`, `audio.sfx_stream`, `audio.upload` | `LWJGLAudioBackend.java:686, 703, 734` | Music stream `read()` (SMPS sequencer + FM synth + resampler interleaved at sample granularity inside `SmpsDriver.read()`), separate-SFX stream + mix loop, DirectBuffer fill + OpenAL upload. Names describe the wrap windows; a clean SMPS/synth/resample phase split is *not* available at this seam. |
| `rewind.capture` | `RewindRegistry.java:54` | Snapshot cost on capture-interval frames. Called from `LiveRewindManager` after the level tick; `activeSection` is null at that point, so no truncation. |
| `rewind.restore` | `RewindRegistry.restore()` | Restore cost. Mirrors `rewind.capture`. Dominant on hot-segment held rewind. |
| `rewind.step` | `RewindController.stepBackward()` | Outer wrapper. Catches audio bookkeeping + segment-cache array alloc + post-restore work. Re-opens itself after inner `rewind.tick`/`rewind.capture`/`rewind.restore` sections implicitly close it. |
| `rewind.seek` | `RewindController.seekTo()` | Outer wrapper for the seek path. Same re-open pattern as `rewind.step`. Used by debug/Bk2 seek paths. |
| `rewind.tick` | `RewindController.stepBackward()` segment-expansion lambda + `RewindController.seekTo()` forward loop | Wraps each `engineStepper.step(...)` inside segment-cache cold expansion and `seekTo` forward-stepping. Surfaces the "60× game frames replayed in one visual frame" cost at backward keyframe crossings. |

### Offline harness (rewind)

`src/test/java/com/openggf/game/rewind/RewindBenchmark.java` plus `BenchmarkTiming` is the most rigorous perf gate in the repo — seven phases. Assertions are **mean-only** (`assertMeanAtMost` at `RewindBenchmark.java:693-712`); there are no p99 assertions. Phase 7 audio budgets are **opt-in** via `-Dopenggf.rewind.benchmark.audioBudgets=true` (`:718-721`):

| Phase | What it measures | Mean budget |
|-------|------------------|-------------|
| 1 | Forward overhead with/without registry | – |
| 2 | `registry.capture()` | mean ≤ **1 ms** (`MAX_CAPTURE_MEAN_NS = 1_000_000` ns) |
| 3 | Restore | mean ≤ **1.5 ms** |
| 4 | Cold seek (8 targets × 8 reps) | mean ≤ **10 ms** |
| 5 | Hot seek (within-segment) | mean ≤ **10 ms** (shares `MAX_SEEK_MEAN_NS`) |
| 6 | Per-keyframe memory, per-subsystem attribution via `IdentityHashMap` | ≤ **128 KB / keyframe** |
| 7 | Audio capture/restore/replay (opt-in) | capture/restore ≤ **250 µs**, replay ≤ **2 ms** |
| – | `longtail.determinism` minimum clean frames | ≥ **1200 frames** |

### Blind spots (uninstrumented or under-instrumented)

Render coverage was extended this round: `render.water_setup` and `render.atlas_upload` added; `render.fg.priority` renamed to `render.fbo_compose` so the name matches what it wraps. Audio now has `audio.music_stream`, `audio.sfx_stream`, `audio.upload` — stream-boundary splits rather than the cleaner SMPS/synth/resample split first proposed, because `SmpsDriver.read()` interleaves those phases at sample granularity. Rewind capture is timed via `rewind.capture`. The `debug` section still has no sub-breakdown (glyph batching vs. sensor labels vs. panel). **Still no per-system timers** for: collision (TerrainCollisionManager), pattern decompression, parallax/scroll computation, special stages, ring physics.

---

## 2. Per-Subsystem Hot-Path Breakdown

### 2.1 Main loop & rendering

- **Fixed-step + vsync** at 60 FPS: `Engine.java:1085-1147` (`loop()`), accumulator at `:1086-1103`, `glfwSwapInterval(1)` at `:350`.
- **Mode dispatch repeated** in `Engine.display()` (`:1180-1230`) and `GameLoop.stepInternal()` (`GameLoop.java:564, 625, 634`) — multiple `switch(currentGameMode)` per frame. Cheap individually, but the >450-line `stepInternal()` is hard to optimize as a unit.
- **Tilemap GPU pipeline** (`LevelRenderer.java:1710`, `TilemapGpuRenderer`): two passes (low + high priority), pattern atlas (1024×1024), batched up to 4096 patterns. The four `glGetIntegerv(GL_VIEWPORT)` sync reads that previously ran per-frame (`:186, :281, :314, :409`) are now cached once via `cacheViewportForFrame()`, called from `drawWithRenderOptions()` and `renderEndingBackground()`.
- **FBO**: `tileFbo` is created once and reused (good). Blend mode flips per frame to `GL_MAX` for sprite-to-tile priority compositing — minor.
- **Parallax/scroll** (`ParallaxManager.java:30-37`, `:276-348`): 224-entry per-line tables, computed once per frame by the zone handler. Cost is correctness-bound (ROM-accurate per-line scroll), not redundant.
- **Pattern atlas** (`PatternAtlas`): tiered lookup (flat 8192 array + `HashMap` for sparse IDs), batched `glTexSubImage2D` per dirty page. Already efficient.

### 2.2 Objects & collision

- **Update loop** in `ObjectManager.java:603-680` and exec loop at `:883-965`. Reported pattern: **~4 full passes over `activeObjects` + `dynamicObjects` per frame** (snapshot, build `execOrder[]`, exec, fallback). With ~100 active objects that is ~400 iterations and ~12 implicit `Iterator` allocations per frame from enhanced-for loops at lines 595/598/664/689/746/749/754/759/887/890/896/901.
- **Solid contacts** (`ObjectManager.SolidContacts`, `:5110+`, processing at `:5683`): `computeIfAbsent` at `:5181, :5205, :5250` triggers `new HashSet` on first standing/pushing contact each frame. Pre-allocate or pool.
- **Touch responses** (`:4250+`, loop at `:4552-4670`): ~20–40 AABB tests, single-hit early exit at `:4668`. Uses a **double-buffered overlap set** (`:4255-4258, :4461-4473`) — already a good optimization.
- **Terrain sensors** (`TerrainCollisionManager.java:21`): 6 sensors max, pre-allocated pool — constant-time. Called twice per player per frame (ground + ceiling) via `CollisionSystem.step()` at `:79`.
- **Player sensor activation** (`AbstractPlayableSprite.updateSensors()`, `:3907-3965`): 6 `setActive` calls + 1 `TrigLookupTable.calcAngle()` (`:3918`) per airborne frame. Cheap.

### 2.3 Audio synthesis (the largest steady CPU cost)

Audio dwarfs game logic in raw ops/sec.

| Stage | File | Rate | Per-unit work |
|-------|------|------|---------------|
| YM2612 sample | `audio/synth/Ym2612Chip.java:1522-1594` | ~53.3 kHz internal | 6ch × 4op env+phase+LFO+mix |
| Envelope generator | `Ym2612Chip.java:106-145, 1574-1591` | every 3 samples | EG_INC table lookup × 8 ops/sample avg |
| PSG | `audio/synth/PsgChipGPGX.java:337-401` | 223.7 kHz internal | 4 channels, band-limited deltas via blip |
| Blip resampler | `audio/synth/BlipResampler.java` | 44.1 kHz out | 16-tap windowed-sinc FIR (32 phases) |
| SMPS sequencer | `audio/smps/SmpsSequencer.java:905-1011` | 60 Hz | 6–10 tracks × env+mod+parse |
| FIFO | `audio/runtime/AudioOutputFifo.java:24-38` | 44.1 kHz | Modulo wrap (not bitmask) |

**Top opportunities:**

- `Ym2612Chip` inner loops: no SIMD, repeated table lookups (`LFO_PM_TABLE`, `DT_TAB`, `TL_TAB`, `SIN_TAB`) per operator. Consider packed `int[]` channel state and Java `Vector API` for the 6-channel mix.
- `PsgChipGPGX.java:373-378`: `hqPsg ? addDelta : addDeltaFast` branch in the inner tone loop — hoist out of the loop.
- `BlipResampler`: `double` accumulators in the resampler. Fixed-point (int64 + shift) would reduce cache pressure and pair better with vectorization.
- `AudioOutputFifo.java:35`: `% capacityFrames` — make capacity power-of-2 and bitmask.

### 2.4 Rewind capture

`registry.capture()` flow is the main per-frame cost the user already profiles:

- `RewindRegistry.java:51-59` — **one `new LinkedHashMap` per capture** (`bundle.put(...)` for each subsystem).
- `GenericFieldCapturer.java:108-117` — per captured object: 2 × `new ArrayList`, plus `values.toArray()` → `new Object[]`, plus a `GenericObjectSnapshot` record. With N rewound objects, this is **3N small allocations per capture**.
- `GenericFieldCapturer.java:535-541` — `RewindStateful` list fields: `new Object[value.size()]` per such field, contents copied.
- `audio/rewind/SmpsTrackSnapshot.java:80-88` — **8 × `Arrays.copyOf`** per track snapshot (voiceData, voiceScratch, loopCounters, returnStack, modEnvData, envData, fmVolEnvData, ssgEg).

Storage is bounded by phase 6's 128 KB/keyframe budget; capture interval is 60 frames (`RewindController.java:92-95`), so per-runtime-frame *amortized* cost is small, but **on capture frames the GC spike is real** and worth measuring with a live `MemoryStats` section.

**Attribution note (2026-05-26):** Held rewind no longer leaks into the
`update` umbrella section. The cold-segment expansion path is now broken
out into `rewind.tick` (the 60× `engineStepper.step` calls) plus
`rewind.capture` (the 60× `RewindRegistry.capture` calls), with
`rewind.restore` and `rewind.step`/`rewind.seek` covering the rest of
the path. Every `beginSection` has a paired `endSection` in `try/finally`,
so a thrown exception inside `engineStepper.step` or `registry.capture`
cannot leave the profiler with a dangling active section. `update` now
reports its normal prologue-only allocation during held rewind, the same
as during normal play.

### 2.5 Engine-wide per-frame allocation pressure

Confirmed allocations every frame (not just on capture):

| Where | What |
|-------|------|
| `ObjectManager.java:685` | `new ArrayList<>(1 + activeSidekicks.size())` |
| `ObjectManager.java:913` | `Collections.newSetFromMap(new IdentityHashMap<>())` |
| `SpriteManager.java:174, 505, 1189, 1310` | 4 × `new ArrayList<>(collection)` defensive copies for sidekick iteration |
| `SpriteManager.java:597` | `computeIfAbsent(..., ignored -> new ArrayList<>())` |
| `LevelRenderer.java:527, 1051` | `new ArrayList<>()` for debug commands |
| ObjectManager `for-each` loops | ~12 `Iterator` allocations / frame |

These are small but constant; together they're a sustained background allocation rate that `MemoryStats` already tracks but does not currently attribute by section.

---

## 3. Recommendation Status

### Implemented in 20260512

- Extended `render.*` breakdown — added `render.water_setup`, `render.atlas_upload`; renamed `render.fg.priority` → `render.fbo_compose`. (Originally rec 1.)
- Added `audio.music_stream` / `audio.sfx_stream` / `audio.upload` in `LWJGLAudioBackend.fillBuffer`. Stream-boundary splits, not a clean SMPS/synth/resample split — the latter isn't separable in `SmpsDriver.read()`. (Originally rec 2.)
- Cached the GL viewport — all four `glGetIntegerv` sites replaced by one `cacheViewportForFrame()` per frame. (Originally rec 3.)
- Wrapped `RewindRegistry.capture()` with the `rewind.capture` profiler section. (Originally rec 8.)
- Added `PerformanceProfiler.recordSectionTime(name, elapsedNanos)` — non-truncating measurement primitive; used by `PatternAtlas.endBatch()` so DPLC bursts don't break `render.sprites`. (Not in the original list; required to make `render.atlas_upload` work without truncation.)

### Remaining (ordered by likely ROI)

1. **Pool the standing/pushing bit sets** in `ObjectManager.SolidContacts` (`:5181, :5205, :5250`) — eliminates a per-contact `new HashSet`.
2. **Replace the defensive `new ArrayList<>(collection)` copies** in `SpriteManager` with a copy-on-write or snapshot-on-mutation pattern.
3. **Hoist `hqPsg`/`fast` branch** out of `PsgChipGPGX.java:373-378` (two specialized paths).
4. **YM2612 vectorization** — non-trivial but the biggest single CPU consumer. Consider packed channel state + Vector API; verify against existing audio tests.
5. **Make `AudioOutputFifo` capacity power-of-2** and replace `%` with mask.

---

## 4. Suggested Diagnostic Next Step

Run a representative gameplay session with the debug performance overlay enabled (the profiler only records while that toggle is on) and `MemoryStats` capturing per-section allocation. With the splits from this round in place, the overlay will show whether time is concentrated in `audio.music_stream` (FM synth + resampler dominate), `render.sprites` (sprite batching), `render.atlas_upload` (DPLC churn), or `objects` / `physics` (game logic). Pick the largest section and work down the **Remaining** list above in that order.
