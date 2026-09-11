OPTIMIZATION PLAN
=================

Scope
-----
This plan captures CPU and RAM reduction ideas for the Sonic engine. Each item
includes an estimated change complexity and expected performance impact.

Legend
------
Complexity:
- Low: localized change, 1-2 files, low risk
- Medium: multiple files or algorithm change
- High: architectural change or broad refactor

Impact:
- Low: micro-optimizations, small gains
- Medium: visible per-frame savings or reduced GC churn
- High: hot-loop wins (per-sample/per-frame), large GC or CPU drops

Recommendations
---------------
ID | Area | Change | Files | Complexity | Impact | Notes
-- | ---- | ------ | ----- | ---------- | ------ | -----
1 | Audio | Remove per-sample list copy in SmpsDriver.read(); move seq copy outside the sample loop or use safe iteration with a small pending-ops queue. | src/main/java/uk/co/jamesj999/sonic/audio/driver/SmpsDriver.java | Medium | High | Currently allocates an ArrayList per audio sample. This is a hot path.
2 | Rings | Replace per-frame LinkedHashSet window rebuild in RingPlacementManager.update() with incremental spawn/trim window (same approach as ObjectPlacementManager). | src/main/java/uk/co/jamesj999/sonic/level/rings/RingPlacementManager.java, src/main/java/uk/co/jamesj999/sonic/level/objects/ObjectPlacementManager.java | Medium | High | Eliminates a per-frame set allocation and large retain/add operations.
3 | Rendering | Pre-bucket sprites/objects once per frame and reuse per-bucket lists; reuse GLCommand lists in ObjectManager. | src/main/java/uk/co/jamesj999/sonic/graphics/SpriteRenderManager.java, src/main/java/uk/co/jamesj999/sonic/level/objects/ObjectManager.java | Medium | Medium | Avoids repeated full scans each bucket and reduces allocations.
4 | Rendering | Consolidate overlay GL state resets (TextRenderer prep) and only call when overlays are active; avoid repeated gluOrtho2D unless mode/viewport changed. | src/main/java/uk/co/jamesj999/sonic/Engine.java | Low-Medium | Medium | Reduces redundant GL state changes and CPU time each frame.
5 | Rendering | Cache viewport size from reshape() and reuse a single int[4] (or cached values) instead of glGetIntegerv(GL_VIEWPORT) allocations each frame. | src/main/java/uk/co/jamesj999/sonic/level/LevelManager.java | Low | Medium | Reduces per-frame allocations and potential GL driver stalls.
6 | Special stage | Cache sorted player/object lists and only re-sort when list changes (dirty flag). | src/main/java/uk/co/jamesj999/sonic/game/sonic2/specialstage/Sonic2SpecialStageRenderer.java | Medium | Medium | Avoids per-frame list copy and sort.
7 | Graphics cache | Replace String-keyed texture maps with int-keyed arrays or an Int2IntMap; avoid string concatenation on hot lookups. | src/main/java/uk/co/jamesj999/sonic/graphics/GraphicsManager.java | Medium-High | Medium | Reduces GC churn and lookup overhead on render paths.
8 | GL buffers | Reuse direct ByteBuffers for pattern/palette uploads (buffer pool or thread-local) instead of allocating each update. | src/main/java/uk/co/jamesj999/sonic/graphics/GraphicsManager.java | Low-Medium | Medium | Cuts native allocations and GC pressure.
9 | Level debug | Reuse collision command list and only populate when collision overlay is enabled. Remove unused list in drawAllPatterns(). | src/main/java/uk/co/jamesj999/sonic/level/LevelManager.java | Low | Low-Medium | Small but easy win.
10 | Debug overlay | Cache debug overlay strings and update at lower frequency (every N frames) or reuse StringBuilder buffers. | src/main/java/uk/co/jamesj999/sonic/debug/DebugRenderer.java | Low | Low | Only affects debug mode but reduces GC spikes.

Measurement Plan
----------------
- Use JFR or async-profiler to verify top CPU/alloc sites before and after each
  change.
- Track frame time and GC pause duration in both LEVEL and SPECIAL_STAGE modes.
- Verify no physics or render regressions (pixel-for-pixel expectations).
