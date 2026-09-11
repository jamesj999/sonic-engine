# Render And Special-Stage Performance Optimization Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove verified render and special-stage CPU, allocation, native-memory, and GPU-upload waste while preserving pixel output and deterministic ordering.

**Architecture:** Renderer-owned grow-only scratch handles frame-local work; queued commands publish immutable/double-buffered snapshots. Static geometry is cached behind explicit content/context version invalidation, while mutable palettes remain live. GPU architecture changes retain the current full-upload/direct-render fallback until parity tests pass.

**Tech Stack:** Java 21, LWJGL/OpenGL, JUnit 5, Maven, existing visual/determinism tests.

---

Every commit uses the repository trailer block. A `perf:` commit touching `src/main/` also stages `CHANGELOG.md` and sets `Changelog: updated`; all unrelated trailers are `n/a`.

### Task 1: Measurement And Palette Upload Deduplication

**Files:**
- Modify: `src/main/java/com/openggf/graphics/color/DisplayColorConverter.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Modify: `src/main/java/com/openggf/level/LevelRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/RenderContext.java`
- Test: `src/test/java/com/openggf/graphics/color/TestDisplayColorConverter.java`
- Create: `src/test/java/com/openggf/tests/TestUnderwaterPaletteRendering.java`

- [ ] Add a failing allocation/parity test that converts all palette colors into caller-provided RGBA storage and asserts the bytes equal the existing allocating API.
- [ ] Run `mvn "-Dtest=TestDisplayColorConverter,TestUnderwaterPaletteRendering" test`; confirm failure because no caller-owned conversion/upload path exists.
- [ ] Add `writeRgbBytes(..., int[] target, int offset)`, persistent grow-only native palette staging, donor-row caching keyed by palette content/version, and a per-frame/content-version underwater upload token.
- [ ] Resolve/upload underwater palette once before configuring both shaders in `LevelRenderer`; never dirty-gate by palette reference identity.
- [ ] Re-run the focused command and add a counter assertion proving one underwater upload per unchanged frame.
- [ ] Update `CHANGELOG.md`; commit `perf: deduplicate palette conversion and underwater uploads` with policy trailers.

### Task 2: Allocation-Free S3K Blue Sphere And Slot Visibility

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotLayoutRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotBonusStageRuntime.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/bonusstage/slots/S3kSlotRenderBuffers.java`
- Test: `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageRenderer.java`
- Test: `src/test/java/com/openggf/game/sonic3k/bonusstage/slots/TestS3kSlotRenderBuffers.java`

- [ ] Add failing tests for stable equal-Y ordering, static emerald/ring mapping identity, visible-cell scan order, and buffer reuse across 600 frames.
- [ ] Run `mvn "-Dtest=TestSonic3kSpecialStageRenderer,TestS3kSlotRenderBuffers,TestS3kSlotLayoutRenderer" test`; confirm the reuse assertions fail.
- [ ] Replace `List<int[]>` and per-cell records with grow-only parallel primitive arrays plus count. Implement a stable insertion/merge sort whose equal-key branch retains traversal order.
- [ ] Move HUD, emerald, and ring mapping arrays to immutable static flat tables. Double-buffer slot visibility if deferred commands retain a frame.
- [ ] Re-run focused tests and `mvn "-Dtest=TestSonic3kSpecialStageVisualSnapshot,TestSonic3kSpecialStageGameplaySnapshot,TestS3kSlotBonusStageRuntime" test`.
- [ ] Update `CHANGELOG.md`; commit `perf: reuse special-stage visibility buffers` with trailers.

### Task 3: Cache Static Special-Stage Planes And Decoded Frames

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2TrackFrameDecoder.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageSnapshot.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/Sonic3kSpecialStageRenderer.java`
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/Sonic2SpecialStageRendererDeterminismTest.java`
- Test: `src/test/java/com/openggf/game/sonic2/specialstage/TestSonic2SpecialStageRewindSnapshot.java`
- Test: `src/test/java/com/openggf/game/sonic3k/specialstage/TestSonic3kSpecialStageVisualSnapshot.java`

- [ ] Add failing draw-counter tests proving unchanged S2 background mappings render once, decoded `(frame, flipped)` data is reused and immutable, and S3K starfield/floor batches invalidate on context/stage reset.
- [ ] Run the three tests and confirm repeated-draw/decode assertions fail.
- [ ] Add explicit dirty/context generation fields. Cache S2 background FBO content while leaving shader scroll live. Cache immutable decoded track frames by complete key and reconstruct derived arrays on rewind restore.
- [ ] Cache S3K starfield geometry and nine floor geometry batches without baking mutable palette colors into RGBA data.
- [ ] Re-run focused tests plus `mvn "-Dtest=S2SpecialStageReplayDeterminismTest,TestSonic2SpecialStageTrackAnimatorSnapshot,TestSonic3kSpecialStageRewindAdapter" test`.
- [ ] Update `CHANGELOG.md`; commit `perf: cache static special-stage render data` with trailers.

### Task 4: Frame-Owned Advanced State And Deferred Commands

**Files:**
- Modify: `src/main/java/com/openggf/game/render/AdvancedRenderFrameState.java`
- Modify: `src/main/java/com/openggf/game/render/AdvancedRenderModeController.java`
- Modify: `src/main/java/com/openggf/level/LevelRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageManager.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageBackgroundRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/specialstage/SpecialStageBackgroundRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/shaderlib/DisplayShaderPipeline.java`
- Test: `src/test/java/com/openggf/game/render/TestAdvancedRenderModeController.java`
- Test: `src/test/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageRendererTest.java`

- [ ] Add failing tests that retain frame N state/command, build frame N+1, then assert frame N data is unchanged; add an allocation-count assertion for steady-state frames.
- [ ] Run focused tests and confirm ownership/reuse expectations fail.
- [ ] Copy advanced render columns once into controller-owned double buffers and expose a package-private frame-scoped view used by all passes. Pool immutable command snapshots and viewport arrays; reuse S1 background scratch and H-scroll snapshots only after command consumption.
- [ ] Apply the same ownership pattern to display-shader state and S1/S2 special-stage deferred commands; do not expose mutable public arrays.
- [ ] Re-run focused tests plus `mvn "-Dtest=TestRenderContext,RenderOrderTest,Sonic2SpecialStageRendererDeterminismTest" test`.
- [ ] Update `CHANGELOG.md`; commit `perf: reuse frame-owned render command state` with trailers.

### Task 5: Virtual-ID-Safe Overflow Batching And GL Lifecycle

**Files:**
- Modify: `src/main/java/com/openggf/graphics/PatternRenderCommand.java`
- Modify: `src/main/java/com/openggf/graphics/InstancedPatternRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/GraphicsManager.java`
- Modify: `src/main/java/com/openggf/graphics/GLCommand.java`
- Modify: `src/main/java/com/openggf/graphics/GLCommandGroup.java`
- Modify: `src/main/java/com/openggf/graphics/PatternAtlas.java`
- Test: `src/test/java/com/openggf/graphics/TestSatReplayBatching.java`
- Test: `src/test/java/com/openggf/graphics/TestPatternAtlasDirtyUploads.java`
- Create: `src/test/java/com/openggf/graphics/TestGraphicsManagerReinit.java`
- Create: `src/test/java/com/openggf/graphics/TestVirtualPatternIdRanges.java`

- [ ] Add failing tests for virtual IDs above `0x7FF`, atlas-page transition flush order, cached uniform lookup counts, and repeated destroy/reinit with empty static pools.
- [ ] Run focused tests and confirm missing batching/lifecycle assertions fail.
- [ ] Cache projection/camera uniform locations per shader. Extend instanced batches with atlas page as part of the batch key and flush on page/priority/water/order boundaries; retain direct fallback for unsupported commands.
- [ ] Clear recycled manager references and delete static GL objects during active-context teardown after queues drain. Keep process-lifetime native scratch recreatable.
- [ ] Re-run focused tests plus `mvn "-Dtest=TestVirtualPatternIdRanges,TestPatternAtlasFallback,RenderOrderTest" test`.
- [ ] Update `CHANGELOG.md`; commit `perf: batch overflow patterns and close gl lifetimes` with trailers.

### Task 6: Zone Overlay And Small Render Allocation Cleanup

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ZoneFeatureProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/slotmachine/CNZSlotMachineRenderer.java`
- Modify: `src/main/java/com/openggf/game/sonic2/render/HtzEarthquakeBgOverlayEffect.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/render/HczBgHighPriorityTileRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/SpriteSatMaskPostProcessor.java`
- Modify: `src/main/java/com/openggf/game/sonic1/specialstage/Sonic1SpecialStageBlockType.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/specialstage/S3kSpecialStageResultsScreen.java`
- Modify: `src/main/java/com/openggf/level/rings/RingManager.java`
- Test: existing CNZ, HTZ, HCZ, SAT-mask, S1 special-stage, results-screen, and ring tests.

- [ ] Add failing steady-state reuse tests for multiple CNZ displays, overlay viewport snapshots, SAT clipping scratch, packed S1 wall metadata, reusable results `PatternDesc`, and canonical collected-ring lookup.
- [ ] Run `mvn "-Dtest=TestSpriteSatMaskPostProcessor,TestGumballFgPriorityDiagnostics,TestHtzEarthquakeTilemapInvalidation,TestRingManager,Sonic1SpecialStageRendererTest" test` and confirm new reuse assertions fail.
- [ ] Introduce grow-only primitive pending-display buffers and pooled immutable commands; reuse overlay/viewport/SAT scratch; pack wall metadata; reuse descriptors; retain canonical ring index instead of allocating coordinate probes.
- [ ] Re-run focused suites and pixel snapshots.
- [ ] Update `CHANGELOG.md`; commit `perf: remove zone render allocation churn` with trailers.

### Task 7: Incremental Background Tilemap Ring Upload

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelTilemapManager.java`
- Modify: `src/main/java/com/openggf/graphics/TilemapTexture.java`
- Modify: `src/main/java/com/openggf/graphics/TilemapGpuRenderer.java`
- Test: `src/test/java/com/openggf/tests/TestIncrementalBgTilemapWindow.java`

- [ ] Extend the existing test with a failing upload-byte/call oracle for one-column forward/backward shifts, wrap, wide backgrounds, and full invalidation fallback.
- [ ] Run `mvn "-Dtest=TestIncrementalBgTilemapWindow" test`; confirm full-upload assertion fails.
- [ ] Store background texture columns as a ring, upload only entering columns with row-length-safe staging, and pass the ring base to the shader. Full upload remains mandatory after layout, height, context, act, or rewind invalidation.
- [ ] Re-run the test plus `mvn "-Dtest=TestHtzEarthquakeTilemapInvalidation,TestS3kAiz1SkipHeadless" test`; capture AIZ/HCZ/CNZ scroll scenes before/after and require pixel identity.
- [ ] Update `CHANGELOG.md`; commit `perf: upload background tilemap window incrementally` with trailers.

### Task 8: Render Verification Gate

- [ ] Run all focused commands from Tasks 1-7.
- [ ] Run `mvn "-Dtest=*SpecialStage*,*Render*,*PatternAtlas*,*Tilemap*,*Palette*" test`.
- [ ] Run `mvn "-Dtest=TestS3kAiz1SkipHeadless,TestSonic3kLevelLoading,TestSonic3kBootstrapResolver,TestSonic3kDecodingUtils" "-Ds3k.rom.path=s3k.gen" test`.
- [ ] Record warmed 1,200-frame allocation and GPU timings for dry, water, S1/S2/S3K special-stage, slot, and CNZ scenes in the integration report.
- [ ] Run `mvn test`; require zero failures/errors.
