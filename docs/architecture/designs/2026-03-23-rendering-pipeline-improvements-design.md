# Rendering Pipeline Improvements — Design Spec

**Date:** 2026-03-23
**Branch:** `feature/ai-common-utility-refactors`
**Status:** Draft

## Overview

Three targeted improvements to the rendering pipeline: PatternAtlas slot reclamation, batched DPLC updates, and BatchedPatternRenderer vertex deduplication. Each is independently implementable and committable.

## Non-Goals

- Changing PatternAtlas lookup structure (fast array + sparse map)
- Modifying the atlas page count or dimensions
- Altering render ordering or shader logic
- Any changes to level tile rendering (TilemapGpuRenderer)

---

## Task 2-3: PatternAtlas Slot Reclamation

### Problem

`AtlasPage.allocateSlot()` (line 464) is a monotonic counter. `removeEntry()` (line 211) clears the lookup table entry but does not free the physical slot. Once a pattern is uncached, its slot is permanently leaked. In crowded zones with dynamic object art (PLCs loading/unloading art per act), this leads to silent rendering failures when the atlas fills — only a `LOGGER.warning` at line 292.

### Design

Add a free list to `AtlasPage`:

```java
private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();

int allocateSlot() {
    if (!freeSlots.isEmpty()) {
        return freeSlots.removeLast();
    }
    if (nextSlot >= maxSlots) {
        return -1; // exhausted
    }
    return nextSlot++;
}

void freeSlot(int slot) {
    freeSlots.addLast(slot);
}
```

Update `hasCapacity()` to account for free slots:

```java
boolean hasCapacity() {
    return nextSlot < maxSlots || !freeSlots.isEmpty();
}
```

Without this, `getOrCreatePage()` would prematurely create a second atlas page or return null even when free slots are available.

Extend `removeEntry()` to call `freeSlot()`, with alias safety:

```java
public boolean removeEntry(int patternId) {
    Entry old;
    if (patternId >= 0 && patternId < FAST_ENTRIES_SIZE) {
        old = fastEntries[patternId];
        fastEntries[patternId] = null;
    } else {
        old = sparseEntries.remove(patternId);
    }
    if (old != null) {
        // Only free the slot if no other entry shares it (alias safety).
        // aliasEntry() creates entries with the same slot — freeing an
        // aliased slot would corrupt the original entry's texture data.
        if (!isSlotReferencedByOtherEntry(old)) {
            AtlasPage page = pages.get(old.atlasIndex());
            page.freeSlot(old.slot());
        }
        return true;
    }
    return false;
}
```

`isSlotReferencedByOtherEntry()` scans `fastEntries` and `sparseEntries` for any remaining entry with the same `atlasIndex()` and `slot()`. This is O(n) but `removeEntry()` is called infrequently (level transitions, PLC unloads) — not per-frame.

**Note:** `aliasEntry()` and `uncachePattern()` currently have zero external callers. The alias guard is defensive. If aliases are ever used in production, a per-slot reference count would be more efficient than scanning.

The `Entry` record already has `slot()` accessor (verified at line 481). Handle the `-1` exhaustion case in `ensureEntry()` (line 295) — if `allocateSlot()` returns -1, treat it the same as `getOrCreatePage()` returning null.

### Testing

Add a test in `src/test/java/com/openggf/graphics/`:

Follow the existing pattern in `PatternAtlasFallbackTest.java` — use `new PatternAtlas(256, 256)` and `cachePatternHeadless()`:

```java
@Test
public void reclaimedSlotsAreReused() {
    PatternAtlas atlas = new PatternAtlas(256, 256);

    Pattern p1 = new Pattern();
    atlas.cachePatternHeadless(p1, 0x20000);
    Entry e1 = atlas.getEntry(0x20000);

    atlas.removeEntry(0x20000);
    assertNull(atlas.getEntry(0x20000));

    atlas.cachePatternHeadless(p1, 0x20001);
    Entry e2 = atlas.getEntry(0x20001);

    // New entry reuses the freed slot
    assertEquals(e1.tileX(), e2.tileX());
    assertEquals(e1.tileY(), e2.tileY());
}
```

---

## Task 2-4: Batch DPLC Atlas Updates

### Problem

`DynamicPatternBank.applyRequests()` (line 49) calls `graphicsManager.updatePatternTexture()` per tile. Each call triggers an individual `glTexSubImage2D` for one 8×8 tile. A player sprite DPLC frame loads 10-30 tiles per animation change, generating 10-30 separate GL upload calls.

The batch mechanism already exists on `PatternAtlas` (`beginBatch()`/`endBatch()`, lines 324-364). In batch mode, `uploadPattern()` writes to the CPU-side `cpuPixels` buffer and marks the page dirty. `endBatch()` flushes one `glTexSubImage2D` per dirty page covering all accumulated tile writes.

### Design

`DynamicPatternBank` needs access to the `PatternAtlas` to call `beginBatch()`/`endBatch()`. Two options:

**Option A (recommended):** Route through `GraphicsManager`, which already owns the atlas:

```java
// In DynamicPatternBank.applyRequests():
if (cached && graphicsManager != null) {
    graphicsManager.beginPatternAtlasBatch();
}

// ... existing tile copy + updatePatternTexture loop ...

if (cached && graphicsManager != null) {
    graphicsManager.endPatternAtlasBatch();
}
```

Check if `GraphicsManager` already exposes `beginPatternAtlasBatch()`/`endPatternAtlasBatch()` methods — they may already exist (the CLAUDE.md mentions them in the GraphicsManager API). If not, add thin delegation methods.

**Option B:** Pass the `PatternAtlas` directly. Less clean since `DynamicPatternBank` currently only knows about `GraphicsManager`.

Use Option A. The existing `updatePatternTexture()` calls inside the loop remain unchanged — they'll automatically defer to the CPU buffer when batch mode is active.

### Testing

Verify visually that player sprite animation still renders correctly. No unit test needed for this — the batch mechanism is already tested by the atlas's existing tests. The change is purely about wrapping an existing loop with begin/end calls.

### Performance Impact

- **Before:** 10-30 `glTexSubImage2D` calls per animation frame change, each uploading 64 bytes (8×8 tile)
- **After:** 1 `glTexSubImage2D` call per dirty atlas page, uploading the full page (1024×1024 = ~1MB)
- **Tradeoff:** Uploading a full 1MB page instead of 10-30 individual tiles (~640-1920 bytes) uses more bandwidth but far fewer GL calls. The driver overhead of 30 separate bind/upload/unbind cycles far exceeds the bandwidth cost of one larger upload. Net positive.
- **Future optimization:** Track dirty bounding box within the page and use a partial `glTexSubImage2D` covering only the dirty region. Not needed for this iteration.

---

## Task 2-5: BatchedPatternRenderer writeQuad Extraction

### Problem

Three methods contain identical vertex + texcoord construction code:
- `addPattern()` lines 180-208 (30 lines)
- `addStripPattern()` lines 314-342 (30 lines)
- `addShadowPattern()` lines 458-486 (30 lines)

All construct the same 2-triangle quad (6 vertices, 12 vertex floats, 12 texcoord floats) from pre-computed position and UV coordinates.

### Design

Extract a private helper:

```java
private void writeQuad(int vertOffset, int texOffset,
        float x0, float y0, float x1, float y1,
        float u0, float v0, float u1, float v1) {
    // Triangle 1: bottom-left, bottom-right, top-right
    vertexData[vertOffset + 0] = x0;
    vertexData[vertOffset + 1] = y0;
    vertexData[vertOffset + 2] = x1;
    vertexData[vertOffset + 3] = y0;
    vertexData[vertOffset + 4] = x1;
    vertexData[vertOffset + 5] = y1;
    // Triangle 2: bottom-left, top-right, top-left
    vertexData[vertOffset + 6] = x0;
    vertexData[vertOffset + 7] = y0;
    vertexData[vertOffset + 8] = x1;
    vertexData[vertOffset + 9] = y1;
    vertexData[vertOffset + 10] = x0;
    vertexData[vertOffset + 11] = y1;

    // Triangle 1 texture coords
    texCoordData[texOffset + 0] = u0;
    texCoordData[texOffset + 1] = v0;
    texCoordData[texOffset + 2] = u1;
    texCoordData[texOffset + 3] = v0;
    texCoordData[texOffset + 4] = u1;
    texCoordData[texOffset + 5] = v1;
    // Triangle 2 texture coords
    texCoordData[texOffset + 6] = u0;
    texCoordData[texOffset + 7] = v0;
    texCoordData[texOffset + 8] = u1;
    texCoordData[texOffset + 9] = v1;
    texCoordData[texOffset + 10] = u0;
    texCoordData[texOffset + 11] = v1;
}
```

Each caller computes its own coordinates (including flip handling and strip UV math), then calls `writeQuad()`. The palette data fill stays in each caller since `addShadowPattern` doesn't write palette data.

### What Changes Per Caller

| Aspect | addPattern | addStripPattern | addShadowPattern |
|--------|-----------|----------------|-----------------|
| Tile height | 8px | 2px (strip) | 8px |
| UV computation | Direct from Entry | Strip-specific pixel-center sampling | Direct from Entry |
| H/V flip | Swap u0↔u1, v0↔v1 | Swap u0↔u1, v0↔v1 | Swap u0↔u1, v0↔v1 |
| Palette data | Yes (6 entries) | Yes (6 entries) | No |
| Batch guard | `batchActive` | `batchActive` | `shadowBatchActive` |

All flip handling and UV computation stays in the caller. Only the 24-float vertex+texcoord write is extracted.

### Net Reduction

~60 lines removed (2 copies of the 30-line block eliminated), replaced by 2 calls to the shared helper. The helper itself is ~24 lines. Net: ~36 lines reduced.

### Testing

No new test needed — this is a pure refactoring with no behavioral change. Existing tests that exercise sprite rendering (headless physics tests with visual verification) implicitly cover this.
