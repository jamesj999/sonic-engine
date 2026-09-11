# WFZ Ending Persistent Plane-B Ring Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace WFZ's incorrect 8192-pixel static background snapshot with the ROM's retained 64x32-cell Plane-B nametable so the sky remains blue until the horizon begins around trace frame 15850.

**Architecture:** Extend the existing background tilemap ring from X-only to X+Y physical origins and partial column/row uploads. Add an opt-in persistent-nametable mode to `ZoneScrollHandler`; `LevelTilemapManager` owns the retained 64x32 descriptor bytes and updates two pattern columns/rows for each 16-pixel BG crossing. A gameplay-scoped rewind adapter snapshots the history-dependent bytes and origins; all other zones keep the current stateless rebuild path.

**Tech Stack:** Java 21, LWJGL/OpenGL tilemap renderer, GLSL, JUnit 5, Maven, existing rewind registry, headless trace capture.

**Design:** `docs/architecture/designs/2026-07-16-wfz-ending-space-background-design.md` §§7.3-7.4, 7.7.

---

### Task 1: Make the GPU background ring two-dimensional

**Files:**
- Modify: `src/main/java/com/openggf/graphics/TilemapTexture.java`
- Modify: `src/main/java/com/openggf/graphics/TilemapGpuRenderer.java`
- Modify: `src/main/java/com/openggf/graphics/TilemapShaderProgram.java`
- Modify: `src/main/resources/shaders/shader_tilemap.glsl`
- Modify: `src/main/java/com/openggf/level/LevelRenderer.java`
- Test: `src/test/java/com/openggf/level/TestIncrementalBgTilemapWindow.java`

- [ ] **Step 1: Add failing row-upload and X/Y-origin tests**

Extend `TestIncrementalBgTilemapWindow` with tests equivalent to:

```java
@Test
void incrementalTwoAxisShiftPublishesBothOriginsAndUploadsTwoRows() {
    byte[] next = descriptorGrid(64, 32);
    renderer.setTilemapData(TilemapGpuRenderer.Layer.BACKGROUND, next, 64, 32);
    renderer.setBackgroundTilemapDataIncremental(next, 64, 32, 2, -2);

    assertEquals(2, renderer.getBackgroundRingBaseXTiles());
    assertEquals(30, renderer.getBackgroundRingBaseYTiles());
    assertEquals(2, renderer.getPendingBackgroundColumnCountForTesting());
    assertEquals(2, renderer.getPendingBackgroundRowCountForTesting());
}

@Test
void rowUploadPacksFullLogicalRowsIntoPhysicalDestination() {
    RecordingTilemapTexture texture = new RecordingTilemapTexture();
    texture.allocateForTesting(4, 3);
    byte[] source = descriptorGrid(4, 3);

    assertTrue(texture.uploadRows(source, 4, 3, 1, 2, 1));
    assertEquals(new UploadRect(0, 2, 4, 1), texture.lastUpload());
}

@Test
void retainedFrameCapturesBothRingOriginsForOneDraw() {
    renderer.setBackgroundTilemapDataIncremental(descriptorGrid(64, 32), 64, 32, 2, 2);
    int generation = renderer.getBackgroundContentGeneration();
    renderer.setBackgroundRenderRingBaseOverride(2, 2, generation);

    assertEquals(2, renderer.getBackgroundRenderRingBaseXForTesting());
    assertEquals(2, renderer.getBackgroundRenderRingBaseYForTesting());
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```powershell
mvn -o "-Dtest=com.openggf.level.TestIncrementalBgTilemapWindow" test
```

Expected: compilation failures for `uploadRows`, the two-axis incremental overload, and Y-origin accessors.

- [ ] **Step 3: Implement partial rows and X/Y ring metadata**

Add this public contract to `TilemapTexture` (using a contiguous source slice, unlike column staging):

```java
public boolean uploadRows(byte[] data, int widthTiles, int sourceHeightTiles,
        int sourceRow, int destinationRow, int rowCount) {
    long required = (long) widthTiles * sourceHeightTiles * 4L;
    if (data == null || widthTiles <= 0 || sourceHeightTiles <= 0 || rowCount <= 0
            || sourceRow < 0 || sourceRow + rowCount > sourceHeightTiles
            || destinationRow < 0 || destinationRow + rowCount > sourceHeightTiles
            || required > data.length || required > Integer.MAX_VALUE
            || !hasStorage(widthTiles, sourceHeightTiles)) {
        return false;
    }
    int rowBytes = widthTiles * 4;
    int uploadBytes = rowBytes * rowCount;
    ensureUploadCapacity(uploadBytes);
    uploadBuffer.clear();
    uploadBuffer.put(data, sourceRow * rowBytes, uploadBytes).flip();
    uploadRowsSubImage(destinationRow, rowCount, widthTiles, uploadBuffer);
    return true;
}
```

Retain the existing X API as a delegating compatibility overload, and add the two-axis form:

```java
public void setBackgroundTilemapDataIncremental(byte[] data, int widthTiles, int heightTiles,
        int shiftXTiles, int shiftYTiles)
```

Track independent pending column and row spans, `backgroundRingBaseXTiles`, and
`backgroundRingBaseYTiles`. Split wrapped uploads into at most two rectangles per axis. A full
upload resets both origins and all pending spans. Preserve `getBackgroundRingBaseTiles()` as a
deprecated X alias until all callers migrate.

- [ ] **Step 4: Add shader Y remapping and frame-command capture**

Replace the scalar uniform with two explicit uniforms:

```glsl
uniform float TilemapRingBaseX;
uniform float TilemapRingBaseY;
...
float physicalTileX = mod(tileXf + TilemapRingBaseX, TilemapWidth);
if (physicalTileX < 0.0) physicalTileX += TilemapWidth;
float physicalTileY = mod(tileYf + TilemapRingBaseY, TilemapHeight);
if (physicalTileY < 0.0) physicalTileY += TilemapHeight;
vec2 tileUv = vec2((physicalTileX + 0.5) / TilemapWidth,
                   (physicalTileY + 0.5) / TilemapHeight);
```

Update `TilemapShaderProgram`, `TilemapGpuRenderer.render(...)`, and the retained BG-pass fields in
`LevelRenderer` so a queued render command captures `(baseX, baseY, contentGeneration)` atomically.
Stale-generation rejection must remain unchanged.

- [ ] **Step 5: Run focused renderer tests and confirm GREEN**

Run:

```powershell
mvn -o "-Dtest=com.openggf.level.TestIncrementalBgTilemapWindow,com.openggf.graphics.TestTilemapGpuRendererPerLineSampling" test
```

Expected: all selected tests pass; existing X-only incremental cases retain their results with Y=0.

- [ ] **Step 6: Commit Task 1**

Stage only Task 1 files plus `CHANGELOG.md`, add a concise renderer entry, and commit:

```text
fix(render): support two-axis background tile rings

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

---

### Task 2: Add the retained 64x32 CPU nametable and opt-in mode

**Files:**
- Create: `src/main/java/com/openggf/level/scroll/BgTilemapUpdateMode.java`
- Modify: `src/main/java/com/openggf/level/scroll/ZoneScrollHandler.java`
- Modify: `src/main/java/com/openggf/level/scroll/ParallaxManager.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/level/LevelTilemapManager.java`
- Test: `src/test/java/com/openggf/level/TestPersistentBgNametable.java`

- [ ] **Step 1: Write failing retained-ring tests**

Create tests covering seed, positive/negative X and Y crossings, simultaneous crossings, modulo wrap,
and large-jump reseed. Use a deterministic descriptor source where each map cell encodes its logical
X/Y so physical-slot contents are assertable:

```java
@Test
void oneSixteenPixelCrossingReplacesExactlyTwoEnteringPatternColumns() {
    fixture.seed(0x2800, 0x0300);
    byte[] before = fixture.copyRing();

    fixture.reconcile(0x2810, 0x0300);

    assertEquals(2, fixture.ringOriginXTiles());
    assertEquals(0, fixture.ringOriginYTiles());
    assertOnlyPhysicalColumnsChanged(before, fixture.copyRing(), 0, 1);
}

@Test
void cloudScrollDoesNotChangeResidencyWithoutBgCameraCrossing() {
    fixture.seed(0x2800, 0x0300);
    byte[] before = fixture.copyRing();
    fixture.setPerLineHscrollWords(0x0000, 0x1000, 0x7000);

    fixture.reconcile(0x2800, 0x0300);

    assertArrayEquals(before, fixture.copyRing());
}

@Test
void sixteenPixelVerticalCrossingRotatesTwoRowsAndPreservesOtherSlots() {
    fixture.seed(0x2800, 0x0300);
    byte[] before = fixture.copyRing();
    fixture.reconcile(0x2800, 0x02F0);

    assertEquals(30, fixture.ringOriginYTiles());
    assertOnlyPhysicalRowsChanged(before, fixture.copyRing(), 30, 31);
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

```powershell
mvn -o "-Dtest=com.openggf.level.TestPersistentBgNametable" test
```

Expected: compilation failure because the update mode and persistent-ring seam do not exist.

- [ ] **Step 3: Add the update-mode contract**

Create:

```java
package com.openggf.level.scroll;

public enum BgTilemapUpdateMode {
    STATIC_WINDOW,
    PERSISTENT_NAMETABLE_64X32
}
```

Add to `ZoneScrollHandler`:

```java
default BgTilemapUpdateMode getBgTilemapUpdateMode() {
    return BgTilemapUpdateMode.STATIC_WINDOW;
}
```

Forward the current handler value through `ParallaxManager`. `LevelManager` passes the mode and the
live BG X/Y values into `LevelTilemapManager` before background data is ensured.

- [ ] **Step 4: Implement the retained descriptor ring**

Add focused persistent-ring state to `LevelTilemapManager`:

```java
private static final int BG_RING_WIDTH_TILES = 64;
private static final int BG_RING_HEIGHT_TILES = 32;
private byte[] persistentBgRing;
private int persistentBgOriginXTiles;
private int persistentBgOriginYTiles;
private int persistentBgAlignedX;
private int persistentBgAlignedY;
private boolean persistentBgBaselineValid;
```

Implement `reconcilePersistentBgNametable(...)` with these invariants:

1. Align BG positions with `Math.floorDiv(value, 16) * 16`.
2. Invalid baseline or an absolute delta above 16 pixels performs a full 64x32 seed and publishes a full upload.
3. A ±16 X delta advances the physical X origin by ±2 tiles, then writes only the two entering physical columns from the logical map coordinates at the new window edge.
4. A ±16 Y delta does the same for two rows.
5. Simultaneous X/Y crossings update both strips; the corner may be written twice but must end with the same descriptor.
6. Publish incremental data through the two-axis renderer API from Task 1.

Reuse the existing `BlockLookup`, pattern-descriptor encoding, horizontal map wrap, and WFZ linear-row-overflow rules; do not duplicate ROM decoding or call `Map.setValue`.

- [ ] **Step 5: Run persistent and stateless regression tests**

```powershell
mvn -o "-Dtest=com.openggf.level.TestPersistentBgNametable,com.openggf.level.TestIncrementalBgTilemapWindow,com.openggf.level.TestBackgroundScroll,com.openggf.level.TestLevelRendererBackgroundViewport" test
```

Expected: all selected tests pass; stateless handlers still use the current full/window build path.

- [ ] **Step 6: Commit Task 2**

```text
feat(render): add persistent Plane-B nametable mode

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

---

### Task 3: Make the history-dependent nametable rewind-safe

**Files:**
- Create: `src/main/java/com/openggf/game/rewind/snapshot/LevelTilemapSnapshot.java`
- Create: `src/main/java/com/openggf/level/rewind/LevelTilemapRewindAdapter.java`
- Modify: `src/main/java/com/openggf/level/LevelTilemapManager.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`
- Modify: `src/main/java/com/openggf/game/session/GameplayModeContext.java`
- Test: `src/test/java/com/openggf/level/rewind/TestPersistentBgNametableRewind.java`

- [ ] **Step 1: Write the failing rewind round-trip test**

```java
@Test
void restoreReinstallsRingBytesOriginsAndBaselineBeforeNextDraw() {
    fixture.seed(0x2800, 0x0300);
    fixture.reconcile(0x2810, 0x02F0);
    LevelTilemapSnapshot expected = fixture.adapter().capture();

    fixture.reconcile(0x2820, 0x02E0);
    fixture.adapter().restore(expected);
    fixture.runPostRestoreCallback();

    LevelTilemapSnapshot restored = fixture.adapter().capture();
    assertArrayEquals(expected.descriptors(), restored.descriptors());
    assertEquals(expected.originXTiles(), restored.originXTiles());
    assertEquals(expected.originYTiles(), restored.originYTiles());
    assertEquals(expected.alignedBgX(), restored.alignedBgX());
    assertEquals(expected.alignedBgY(), restored.alignedBgY());
    assertEquals(expected.baselineValid(), restored.baselineValid());
    assertFalse(fixture.tilemaps().isBackgroundFullRebuildPending());
    assertTrue(fixture.tilemaps().isPersistentFullUploadPending());
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

```powershell
mvn -o "-Dtest=com.openggf.level.rewind.TestPersistentBgNametableRewind" test
```

Expected: compilation failure because the snapshot and adapter do not exist.

- [ ] **Step 3: Implement snapshot and adapter**

Use an immutable defensive copy:

```java
public record LevelTilemapSnapshot(byte[] descriptors, int originXTiles, int originYTiles,
        int alignedBgX, int alignedBgY, boolean baselineValid) {
    public LevelTilemapSnapshot {
        descriptors = descriptors == null ? null : descriptors.clone();
    }

    @Override
    public byte[] descriptors() {
        return descriptors == null ? null : descriptors.clone();
    }
}
```

`LevelTilemapRewindAdapter.key()` returns `"level-tilemap"`. Capture returns an empty/invalid
snapshot outside persistent mode. Restore installs the ring and marks one coherent full GPU upload;
it does not seed from camera/layout.

- [ ] **Step 4: Register the adapter and split post-restore behavior by mode**

Expose `levelTilemapRewindSnapshottable()` from `LevelManager`. In
`GameplayModeContext.registerLevelAdapters()` register/deregister `"level-tilemap"` next to
`"level"`. Change the existing callback to:

```java
if (tilemapManager.usesPersistentBgNametable()) {
    tilemapManager.finishPersistentRestoreUpload();
} else {
    tilemapManager.resetBgIncrementalShiftBaseline();
}
```

The callback must not overwrite the restored descriptors or origins.

- [ ] **Step 5: Run rewind and coverage regressions**

```powershell
mvn -o "-Dtest=com.openggf.level.rewind.TestPersistentBgNametableRewind,com.openggf.game.rewind.TestRewindParityAgainstTrace,com.openggf.game.rewind.coverage.TestRewindCoverageGuard" test
```

Expected: all selected tests pass.

- [ ] **Step 6: Commit Task 3**

```text
fix(rewind): snapshot persistent Plane-B state

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```

---

### Task 4: Opt WFZ into the ring and remove the failed wide-period path

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/scroll/SwScrlWfz.java`
- Modify: `src/test/java/com/openggf/game/sonic2/scroll/TestSwScrlWfz.java`
- Create: `src/test/java/com/openggf/game/sonic2/scroll/TestWfzPersistentPlaneBIntegration.java`
- Modify: `CHANGELOG.md`

- [ ] **Step 1: Replace the 8192 assertion with failing WFZ ring assertions**

```java
@Test
void wfzUsesGenesisPersistentNametableAndFixedPeriod() {
    SwScrlWfz handler = new SwScrlWfz(tables(), new BackgroundCamera());
    assertEquals(BgTilemapUpdateMode.PERSISTENT_NAMETABLE_64X32,
            handler.getBgTilemapUpdateMode());
    assertEquals(512, handler.getBgPeriodWidth());
}

@Test
void changingCloudAccumulatorsDoesNotMoveWfzTileResidency() {
    fixture.stepAtTraceFrame(14820);
    byte[] before = fixture.copyPhysicalRing();
    fixture.advanceOnlyCloudFrameCounter();
    assertArrayEquals(before, fixture.copyPhysicalRing());
}
```

- [ ] **Step 2: Run the WFZ tests and confirm RED**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.scroll.TestSwScrlWfz,com.openggf.game.sonic2.scroll.TestWfzPersistentPlaneBIntegration" test
```

Expected: the old 8192 override and missing opt-in fail the assertions.

- [ ] **Step 3: Implement the minimal WFZ selection**

Delete the dynamic `getBgPeriodWidth()` implementation and add:

```java
@Override
public BgTilemapUpdateMode getBgTilemapUpdateMode() {
    return BgTilemapUpdateMode.PERSISTENT_NAMETABLE_64X32;
}
```

Retain the live `getBgCameraX()` bridge. Do not add frame/route gates, backdrop overrides, or cloud-specific map windows.

- [ ] **Step 4: Run focused WFZ, renderer, rewind, and trace tests**

```powershell
mvn -o "-Dtest=com.openggf.game.sonic2.scroll.TestSwScrlWfz,com.openggf.game.sonic2.scroll.TestWfzPersistentPlaneBIntegration,com.openggf.level.TestPersistentBgNametable,com.openggf.level.rewind.TestPersistentBgNametableRewind" test
mvn -Ptrace-replay "-Dtest=com.openggf.tests.trace.s2.TestS2WfzLevelSelectTraceReplay,com.openggf.tests.trace.s2.TestS2DezEndingLevelSelectTraceReplay" test
```

Expected: all selected tests pass and both trace replays remain green.

- [ ] **Step 5: Commit Task 4**

```text
fix(s2): retain the WFZ Plane-B nametable

Changelog: updated
Guide: n/a
Known-Discrepancies: n/a
S3K-Known-Discrepancies: n/a
Agent-Docs: n/a
Configuration-Docs: n/a
Skills: n/a
```
