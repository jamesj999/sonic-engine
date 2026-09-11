# Engine Performance Optimization — Design

**Date:** 2026-06-11
**Branch (initiating):** `bugfix/ai-release-remediation`
**Status:** Proposed, awaiting plan

## Background

A four-track performance audit (audio, rendering, rewind, core loop/objects)
identified concrete hot-path inefficiencies, all verified against source.
The dominant themes:

1. **Per-sample audio waste** — a redundant full-buffer zero-fill in
   `BlipDeltaBuffer` that becomes pathological (~100M int stores/sec) whenever
   the driver falls back to sample-accurate rendering, and a fallback
   condition that engages that mode for the entire duration of every fade.
2. **Over-broad GPU uploads** — whole 1 MB atlas pages re-uploaded when ~2 KB
   of DPLC tiles changed; the whole BG tilemap window rebuilt in Java every
   16 px of background scroll.
3. **Held-rewind allocation storm** — every backward frame tears down and
   rebuilds the full audio driver stack (new `Ym2612Chip`, `PsgChipGPGX`,
   64 KB `BlipResampler`) and destroys/re-instantiates every active object,
   defeating the `SegmentCache` that exists to make `stepBackward` cheap.
4. **Periodic keyframe hitch** — the every-60th-frame `registry.capture()`
   concentrates uncached reflection (hierarchy walks with exception control
   flow, uncached codec resolution, element-wise reflective array clones)
   into a 1 Hz frame spike.
5. **Steady-state churn** — `static synchronized` service resolution on the
   per-tile collision path, unconditional render-bucket rebuild + 16 sorts
   per frame, boxed-collection snapshots, per-frame native malloc/free, and
   an unbounded `AudioCommandTimeline` scanned O(N) every frame.

The audit also confirmed several systems are already healthy and out of
scope: the `Ym2612Chip` per-sample core, the instanced sprite batcher, level
rewind copy-on-write snapshotting, collision windowing, and dirty-gated
tilemap uploads.

## Goals

1. Eliminate the verified hot-path waste without changing observable engine
   behavior: trace replays, audio output, and rendering must remain
   ROM-accurate.
2. Make held rewind allocation-light: no per-backward-frame audio driver
   rebuild, no per-backward-frame object re-instantiation.
3. Remove the 1 Hz keyframe-capture frame spike's largest contributors via
   memoization (no architectural change to the rewind capture model).
4. Cut GPU upload volume on the DPLC and BG-scroll paths by uploading only
   what changed.
5. Bound `AudioCommandTimeline` memory and make its per-frame operations
   O(changed), not O(session length).

## Non-Goals

- Migrating badniks onto the compact rewind schema path (the gate at
  `AbstractObjectInstance.java:1006` stays; we only make the legacy path
  cheaper). Separate follow-up.
- Off-thread or amortized keyframe capture. Memoization first; revisit only
  if the spike persists after Phase 3.
- Replacing `PatternAtlas`'s sparse `HashMap<Integer, Entry>` with a custom
  int-map library. We hand-roll or use per-range flat arrays; no new
  dependencies.
- Any change to ROM-parity semantics. Items that could plausibly alter
  emulated behavior (fade fallback, trig-table routing) ship only with trace
  evidence; if evidence is ambiguous the item is dropped, not forced.
- Frame pacing / vsync / GameLoop timing redesign.
- Editor-path performance.

## Constraints

- **Accuracy is paramount.** Every phase ends with a full `*TraceReplay`
  sweep; any regression blocks the phase. Audio-affecting changes also get a
  before/after comparison via the deterministic audio runtime (trace-capture
  pipeline) where the change claims bit-exactness.
- **No zone/route/frame carve-outs**, per repo policy. None of the planned
  fixes branch on zone identity; keep it that way.
- **Shared working tree.** All implementation happens in an isolated
  worktree; verify with your-files-only staging (never `git add -A`).
- **JUnit 5 only** for new tests.
- **Keep the S3K green list green:** `TestS3kAiz1SkipHeadless`,
  `TestSonic3kLevelLoading`, `TestSonic3kBootstrapResolver`,
  `TestSonic3kDecodingUtils`.
- Branch documentation trailers per `.githooks` policy; engine `perf:`/`fix:`
  commits touching `src/main/` stage `CHANGELOG.md` or justify the skip.

## Measurement

Before Phase 1, capture a baseline so wins are demonstrable rather than
asserted:

- `PerformanceProfiler` frame-time samples over a fixed scripted run
  (AIZ1 trace replay is a good deterministic driver) — record p50/p99 frame
  time and the periodic keyframe-frame spike.
- Allocation profile of a 10-second held rewind (JFR
  `jdk.ObjectAllocationSample` or `-Xlog:gc` delta) — record MB/s allocated.
- A timed counter around `PatternAtlas.endBatch` upload bytes per second
  during a CNZ slot-machine scene and during normal AIZ running.

Re-run the same captures after each phase. Acceptance criteria reference
these numbers.

## Architecture

Work is organized into four phases ordered by risk and payoff. Phases are
independently shippable; each is its own commit series with its own trace
sweep.

### Phase 1 — Surgical quick wins (low risk, exact-equivalence)

| # | Change | Files | Notes |
|---|--------|-------|-------|
| 1.1 | Fill only the vacated region `[remain, remain + count)` in `readSamples` instead of `[remain, size)` | `audio/synth/BlipDeltaBuffer.java:319-320` | Exact equivalence: everything past `remain + count` is already zero. One line. |
| 1.2 | Make `SessionManager` mode/session fields `volatile`; drop `synchronized` from the read-only getters (`getCurrentGameplayMode`, `getCurrentWorldSession`, `getCurrentEditorMode`); keep it on lifecycle mutators | `game/session/SessionManager.java:108-118` | Removes 100–500 monitor acquisitions/frame routed via `GameServices`. |
| 1.3 | Resolve `LevelManager` once per scan in `GroundSensor` and pass it down instead of re-calling `getLevelManager()` per tile and inside `getSolidTile()` | `physics/GroundSensor.java:388,580,628,716-721` | Same for `isBackgroundCollisionEnabled` per scan. |
| 1.4 | Memoize `GenericRewindEligibility.usesDefaultObjectSubclassCapture` / `usesDefaultBadnikSubclassCapture` per `Class<?>` in a `ConcurrentHashMap` | `game/rewind/GenericRewindEligibility.java:21-99` | Kills repeated `getDeclaredMethod` hierarchy walks + `NoSuchMethodException` construction, twice per object per capture. |
| 1.5 | Memoize `RewindCodecs.codecFor(Field)` in a `ConcurrentHashMap<Field, Optional<RewindCodec>>`; `setAccessible` once at cache build; special-case primitive arrays with `clone()` in `deepCloneArray`; cache record components/constructor per record class in `deepCloneRecord` | `game/rewind/schema/RewindCodecs.java:54-105`, `game/rewind/GenericFieldCapturer.java:357-383,512-535,555-610,760-772` | Pure caching; capture output bytes must be byte-identical (assert in test). |
| 1.6 | Remove the unconditional `invalidateRenderBuckets()` per frame; audit that every priority-mutating path sets `bucketsDirty` (most already do) and add it where missing | `level/LevelRenderer.java:931-944,1037-1045`, `level/objects/ObjectManager.java:963-1005` | Behavior risk is a stale bucket → add a debug-only consistency assert comparing lazy vs eager bucketing for a few frames in a headless test. |
| 1.7 | Replace `RingManager.activeIndices` boxed `ArrayList<Integer>` + per-call stream snapshot with a primitive growable int array + `BitSet` membership; let callers iterate without copying | `level/rings/RingManager.java:882,977-1032,1038-1040` | Three+ stream pipelines/frame removed; `addActiveIndex` O(n)→O(1). |
| 1.8 | Early-return `consumeCommands` when `pendingCommands` is empty; relax `ensureScratch` to `length < samples` with tracked valid length | `audio/runtime/StreamBackedDeterministicAudioRuntime.java:200-210,251-256` | |

**Verification:** full test suite, full `*TraceReplay` sweep, byte-identical
rewind-capture assertion for 1.4/1.5 (capture a keyframe before/after the
change on a fixed scenario and compare blobs), S3K green list.

### Phase 2 — Rendering upload reduction

| # | Change | Files | Notes |
|---|--------|-------|-------|
| 2.1 | **Dirty-rect atlas uploads.** Track min/max dirty tile coords per page during batch mode; in `endBatch` upload only the sub-rectangle (`glPixelStorei(GL_UNPACK_ROW_LENGTH, …)` + sub-rect `glTexSubImage2D`). Below a small threshold (<64 tiles) upload dirty slots individually under one bind. | `graphics/PatternAtlas.java:421-497,130-139` | ~500× reduction on DPLC frame changes; constant win in CNZ slot machines (`S3kSlotMachinePanelAnimator.java:81-90`). |
| 2.2 | **Incremental BG window rebuild.** When only the window base X advanced by one 16 px column, shift the existing tilemap bytes (or adopt ring-buffer addressing — the shader already supports `NametableBase`) and rebuild + upload only the leading column. Reuse the `byte[]` instead of reallocating; skip `findActualBgTilemapDataHeight` when only base X changed. | `level/LevelManager.java:1848-1854`, `level/LevelTilemapManager.java:296-454` | Full rebuild remains the fallback for zone/act/layout changes. |
| 2.3 | **Batch the SAT replay path.** Route `appendDirectReplayCommands` through the instanced batcher with flushes at priority transitions, instead of per-tile `PatternRenderCommand` (3 × `glBufferData` + 1 draw per 8×8 tile). Cache display height in a field invalidated on reshape instead of a config lookup per `obtain()`. | `graphics/GraphicsManager.java:1494-1574`, `graphics/PatternRenderCommand.java:200-444` | Active in S3K SAT-masked scenes (100–300 draws/frame today). |
| 2.4 | **Int-keyed sparse pattern lookup.** Replace `HashMap<Integer, Entry>` with per-range flat arrays keyed off the already-registered `PatternAtlasRange` bases (fall back to a small open-addressing int map for unranged IDs). | `graphics/PatternAtlas.java:42,214-219` | Removes per-visible-tile boxing on all object/HUD/sidekick lookups. |
| 2.5 | **Persistent scroll upload buffers.** Allocate one `FloatBuffer` per instance at `init()`; stop `memAllocFloat`/`memFree` per upload. | `graphics/HScrollBuffer.java:107-124`, `graphics/VScrollBuffer.java:76-92` | |
| 2.6 | Hoist `isFBOProjectionActive()`/display-height resolution to `beginBatch()` in both batchers. | `graphics/InstancedPatternRenderer.java:99-105,185`, `graphics/BatchedPatternRenderer.java:97-103,175` | |

**Verification:** visual validation via the s3k-zone-validate screenshot
comparison on AIZ/HCZ/CNZ scenes (player animation, slot machines, BG
scroll both directions, SAT bonus stage); trace sweep; upload-bytes
counter shows the expected reduction.

### Phase 3 — Rewind structural fixes

| # | Change | Files | Notes |
|---|--------|-------|-------|
| 3.1 | **In-place object restore.** In `ObjectManager.restore()`, when the live instance for `(spawn, slotIndex)` exists with the same class, restore state onto it directly; create/destroy only on membership or class mismatch. | `level/objects/ObjectManager.java:2951-3013` | The dominant per-rewound-frame cost. Must preserve construction-context invariants (renderer wiring) — instances are reused, never re-constructed. |
| 3.2 | **Defer audio logical restore during held rewind.** Within an active `AudioReplayScope`, skip `restoreAudioLogicalState` on intermediate backward frames; perform a single logical restore at release / seek-commit (reverse presentation already reads from `PcmHistoryRing`). | `game/rewind/RewindController.java:288-293`, `audio/rewind/AudioKeyframeStore.java:51-70` | Removes the per-frame `SmpsDriver`/`Ym2612Chip`/`BlipResampler` rebuild (~10–20 MB/s). |
| 3.3 | **Cheap snapshot restore internals.** Package-private non-copying accessors for `BlipResampler`/`BlipDeltaBuffer` snapshot arrays (record accessors currently copy 32 KB, then `System.arraycopy` copies again); reuse existing driver/chip instances on restore where state is fully overwritten. | `audio/synth/BlipResampler.java:129-136,226-231`, `audio/synth/BlipDeltaBuffer.java:335-354`, `audio/AbstractSmpsAudioBackend.java:832-953` | |
| 3.4 | **Bound and index `AudioCommandTimeline`.** Add `entryCount()` (stop `List.copyOf` just to read size); make `entriesOnFrame` walk backward from the tail; truncate `discardAfter` from the tail; add pruning keyed off the earliest retained audio keyframe (store entry counts relative to a base offset so `commandEntryCount` indices survive pruning). | `audio/rewind/AudioCommandTimeline.java`, `audio/AudioManager.java:277,326` | Fixes unbounded growth + per-frame O(N) scan in forward play. |
| 3.5 | **`VarHandle`/typed access in compact codecs.** Store a precomputed type tag + `VarHandle` (or `field.getInt`/`setInt` style typed access) in `RewindFieldPlan`; replace the 8-way `type ==` chains with a switch on the tag. | `game/rewind/schema/RewindCodecs.java:331-345,384-411,1528-1569`, `RewindFieldPlan.java` | |
| 3.6 | **Reduce defensive blob copies.** Keep the ownership-boundary constructor copy; add non-copying package-private read access for the restore path (mirror the existing `CompositeSnapshot.owned()` pattern). | `game/rewind/schema/RewindObjectStateBlob.java:15-32`, `RewindStateBuffer.java:96`, `GenericFieldCapturer.java:657-670` | |
| 3.7 | **Snapshot comparator keys.** Precompute sort keys once per entry instead of string concatenation per comparison; drop the redundant outer `List.copyOf`. | `level/objects/ObjectManager.java:2881-2937,3128-3136` | |
| 3.8 | **Tail-only audio history snapshot.** Snapshot only the live tap window behind `inputIndex` of the `BlipResampler` history (the rest is never read after restore) instead of the full 64 KB, shrinking the per-second capture under `streamLock`. | `audio/synth/BlipResampler.java:125-127`, `Ym2612Chip.java:778-826` | Requires a restore-path change in the same commit; snapshot format is in-memory only (not persisted), so no migration. |

**Verification:** the rewind determinism tests (capture → rewind → replay →
compare), a scripted held-rewind allocation profile (target: >10× reduction
in MB/s vs baseline), trace sweep, and an explicit seek/release audio test:
rewind N seconds, release, assert the driver state matches a from-scratch
replay to the same frame.

### Phase 4 — Behavior-sensitive and cleanup items

These either carry parity risk (ship only with trace evidence) or are
small enough to batch.

| # | Change | Files | Risk note |
|---|--------|-------|-----------|
| 4.1 | Drop `fadeState.active` from `requiresSampleAccurateFallback()` (fade volume only changes at tempo-frame boundaries, which the hybrid chunker respects via the existing clamp at `SmpsSequencer.java:859-861`) | `audio/smps/SmpsSequencer.java:887-889` | **Audio-output-affecting if the premise is wrong.** Gate on a deterministic-audio capture comparison across a fade (act transition); if not sample-identical, drop the item. |
| 4.2 | Closed-form `samplesUntilTempoTicks` (`ceil(firstRemaining + (ticks-1) * samplesPerFrame)`) | `audio/smps/SmpsSequencer.java:891-913` | Assert equality with the loop across representative tempo configs in a unit test. |
| 4.3 | Per-sample setup hoist in `BlipResampler.interpolate`: compute center/frac/phase once shared by L/R, advance ring position incrementally, hoist the (provably dead in steady state) bounds check | `audio/synth/BlipResampler.java:174-212` | Must stay bit-exact; unit-test against the current implementation on random delta streams. |
| 4.4 | `PcmHistoryRing`: wrapping int cursor + two-segment `System.arraycopy` instead of per-frame `Math.floorMod`; same for `AudioOutputFifo` `%` | `audio/runtime/PcmHistoryRing.java:37-71`, `AudioOutputFifo.java:35,49` | |
| 4.5 | Object-pipeline scratch reuse: per-frame identity set in `runExecLoop` (or a frame-stamp int on the object), `runPostPlayerHooks` snapshot, `syncActiveSpawnsLoad` list, `collectActivePlayers`, `buildPlayableUpdateOrder` maps, `drainPendingCursorLoadSpawns`, reusable `RidingState` | `level/objects/ObjectManager.java:484,709,1242,2484-2501`, `ObjectPlacementController.java:874-884`, `sprites/SpriteManager.java:1391-1431`, `ObjectSolidContactController.java:~1820` | Follow the existing `dynamicFallbackScratch` pattern. |
| 4.6 | Route `Math.sin`/`atan2` in per-frame object code through `TrigLookupTable` | `level/objects/AbstractBadnikInstance.java:219`, `BreathingBubbleInstance.java:182-185`, `sprites/managers/TailsTailsController.java:312,343` | **Parity-affecting by design** (ROM uses the 256-step table). Each call site needs a disasm citation showing the ROM routine uses the sine table; verify with trace sweep. If a site is engine-original (no ROM counterpart), leave it. |
| 4.7 | Cache `blockPixelShift`/mask at level load; replace div/mod in `getChunkDescAt` with shifts/masks where sizes are powers of two | `level/LevelManager.java:2092-2140` | Keep the branchy fallback for non-power-of-two widths. |
| 4.8 | Small render-path allocations: reuse `staticPieceDesc` in `HudRenderManager:313`, avoid `List.copyOf` per `GLCommandGroup`, skip the `SpriteSatMaskPostProcessor` defensive copy when masking is off, reuse the `executeCapturedCommands` list | `level/objects/HudRenderManager.java:313`, `graphics/GLCommandGroup.java:22`, `SpriteSatMaskPostProcessor.java:26`, `GraphicsManager.java:187` | |
| 4.9 | Batch the S3K dynamic-art tile uploads under one texture bind; reuse scratch `Pattern`/byte arrays (do **not** wrap in atlas batch mode until 2.1 lands) | `game/sonic3k/Sonic3kPatternAnimator.java:975-1010`, `graphics/PatternAtlas.java:487-497` | |

**Verification:** full trace sweep (4.1 and 4.6 are the items the sweep
exists for), audio capture comparison for 4.1–4.4, test suite, S3K green
list.

## Acceptance Criteria

1. Full `*TraceReplay` sweep: no trace regresses vs the pre-work baseline
   (record sweep results in `docs/status/trace-frontier-log.md` per policy).
2. Held-rewind allocation rate reduced ≥10× vs baseline; no audio driver or
   object instances constructed on intermediate backward frames.
3. Keyframe-capture frame (every 60th) cost measurably reduced (target ≥50%
   of the spike removed by Phases 1+3; measured via `PerformanceProfiler`).
4. `PatternAtlas` upload bytes during DPLC animation reduced ≥100×; BG
   scroll no longer triggers full-window rebuilds for single-column
   advances.
5. `AudioCommandTimeline` memory bounded by the keyframe retention window;
   `beginFrame` no longer O(session length).
6. No new dependencies; no zone/route/frame carve-outs; no behavior change
   without trace + audio-capture evidence.

## Risks

- **1.6 (lazy buckets):** a missed `bucketsDirty` site causes stale render
  order. Mitigation: debug-assert eager-vs-lazy equivalence in a headless
  test before removing the per-frame invalidation; revert to eager if any
  mutation path proves untrackable.
- **3.1 (in-place restore):** object identity now persists across restore;
  any code caching per-instance state keyed on construction (renderers,
  services) must remain valid. Mitigation: keep the create-path fallback on
  any mismatch and add a rewind-determinism test that exercises
  spawn/despawn churn across the restore boundary.
- **3.2 (deferred audio restore):** releasing rewind mid-segment must land
  on exactly the state a per-frame restore would have produced. Mitigation:
  the explicit seek/release equivalence test in Phase 3 verification.
- **4.1 (fade fallback):** premise could be wrong for some fade interaction
  (e.g. SFX fades). Item is evidence-gated and individually revertable.
- **4.6 (trig tables):** changes physics-adjacent values. Each site is
  citation-gated and individually revertable.

## Phase ordering rationale

Phase 1 is all exact-equivalence or trivially assertable changes — it
front-loads the best effort-to-payoff fixes (the `BlipDeltaBuffer` one-liner
alone removes the worst per-sample cost). Phase 2 is GPU-side and
independent of rewind. Phase 3 is the largest behavioral surface and gets
its own determinism test investment. Phase 4 holds everything that needs
per-item evidence so a dropped item never blocks the rest.
