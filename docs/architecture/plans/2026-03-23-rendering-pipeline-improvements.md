# Rendering Pipeline Improvements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add slot reclamation to PatternAtlas, batch DPLC atlas uploads, and extract duplicated vertex construction in BatchedPatternRenderer.

**Architecture:** Three independent commits touching different subsystems. Task 1 (slot reclamation) modifies PatternAtlas internals. Task 2 (batch DPLC) wraps DynamicPatternBank's upload loop with existing batch API. Task 3 (writeQuad) is a pure refactoring of BatchedPatternRenderer. No behavioral changes — rendering output must be identical.

**Tech Stack:** Java 21, OpenGL (LWJGL), Maven

**Spec:** `docs/architecture/designs/2026-03-23-rendering-pipeline-improvements-design.md`

---

### Task 1: PatternAtlas Slot Reclamation

Add a free list to `AtlasPage` so removed entries' slots can be reused. Update `hasCapacity()` and `removeEntry()` with alias safety.

**Files:**
- Modify: `src/main/java/com/openggf/graphics/PatternAtlas.java`
- Create: `src/test/java/com/openggf/graphics/TestPatternAtlasSlotReclamation.java`

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/openggf/graphics/TestPatternAtlasSlotReclamation.java`:

```java
package com.openggf.graphics;

import com.openggf.graphics.PatternAtlas.Entry;
import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestPatternAtlasSlotReclamation {

    @Test
    public void reclaimedSlotsAreReused() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        Pattern p1 = new Pattern();
        atlas.cachePatternHeadless(p1, 0x20000);
        Entry e1 = atlas.getEntry(0x20000);
        assertNotNull(e1);

        atlas.removeEntry(0x20000);
        assertNull(atlas.getEntry(0x20000));

        atlas.cachePatternHeadless(p1, 0x20001);
        Entry e2 = atlas.getEntry(0x20001);
        assertNotNull(e2);

        // New entry reuses the freed slot
        assertEquals(e1.tileX(), e2.tileX());
        assertEquals(e1.tileY(), e2.tileY());
    }

    @Test
    public void aliasRemovalDoesNotFreeSharedSlot() {
        PatternAtlas atlas = new PatternAtlas(256, 256);

        Pattern p1 = new Pattern();
        atlas.cachePatternHeadless(p1, 0x20000);
        Entry original = atlas.getEntry(0x20000);

        atlas.aliasEntry(0x20001, 0x20000);
        Entry alias = atlas.getEntry(0x20001);
        assertEquals(original.slot(), alias.slot());

        // Remove the alias — should NOT free the slot
        atlas.removeEntry(0x20001);

        // Original still works, slot not corrupted
        Entry stillThere = atlas.getEntry(0x20000);
        assertNotNull(stillThere);
        assertEquals(original.slot(), stillThere.slot());

        // Now remove the original — slot should be freed
        atlas.removeEntry(0x20000);

        // New entry should reuse the freed slot
        atlas.cachePatternHeadless(p1, 0x20002);
        Entry reused = atlas.getEntry(0x20002);
        assertEquals(original.tileX(), reused.tileX());
        assertEquals(original.tileY(), reused.tileY());
    }

    @Test
    public void hasCapacityReflectsFreeSlots() {
        // Use a tiny atlas so we can exhaust it
        PatternAtlas atlas = new PatternAtlas(16, 16);
        // 16x16 atlas = 2x2 tiles = 4 slots per page

        Pattern p = new Pattern();
        // Fill all 4 slots
        for (int i = 0; i < 4; i++) {
            assertNotNull(atlas.cachePatternHeadless(p, 0x20000 + i),
                "Should have capacity for slot " + i);
        }

        // Atlas page should be full — next cache goes to page 2 or fails
        // Remove one entry to free a slot
        atlas.removeEntry(0x20000);

        // Should reuse the freed slot, not allocate from a new page
        Entry reused = atlas.cachePatternHeadless(p, 0x20010);
        assertNotNull(reused);
        // Verify it's on the same atlas page (index 0), not a new page
        assertEquals(0, reused.atlasIndex());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest="TestPatternAtlasSlotReclamation" -q 2>&1 | tail -5`
Expected: FAIL — `reclaimedSlotsAreReused` fails because slot is not reused (new slot allocated instead)

- [ ] **Step 3: Add free list to AtlasPage**

In `PatternAtlas.java`, find the `AtlasPage` inner class (around line 440). It has fields `nextSlot` and `maxSlots`.

Add a free list field:
```java
private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
```
Add import `java.util.ArrayDeque;` at the top of the file if not present.

Modify `allocateSlot()` (line ~464):
```java
int allocateSlot() {
    if (!freeSlots.isEmpty()) {
        return freeSlots.removeLast();
    }
    if (nextSlot >= maxSlots) {
        return -1;
    }
    return nextSlot++;
}
```

Add `freeSlot()` method:
```java
void freeSlot(int slot) {
    freeSlots.addLast(slot);
}
```

Modify `hasCapacity()` (line ~460):
```java
boolean hasCapacity() {
    return nextSlot < maxSlots || !freeSlots.isEmpty();
}
```

- [ ] **Step 4: Handle -1 return from allocateSlot**

In `ensureEntry()` (around line 295), after `int slot = page.allocateSlot();`, add:
```java
if (slot < 0) {
    LOGGER.warning("Pattern atlas slot allocation failed; patternId=" + patternId);
    return null;
}
```

- [ ] **Step 5: Add alias-safe slot freeing to removeEntry**

In `removeEntry()` (line ~211), replace the current implementation with:

```java
public boolean removeEntry(int patternId) {
    // IMPORTANT: Remove from lookup BEFORE calling isSlotShared().
    // The scan must not find this entry itself, otherwise it would
    // always return true and no slot would ever be freed.
    Entry old;
    if (patternId >= 0 && patternId < FAST_ENTRIES_SIZE) {
        old = fastEntries[patternId];
        fastEntries[patternId] = null;
    } else {
        old = sparseEntries.remove(patternId);
    }
    if (old != null) {
        if (!isSlotShared(old)) {
            AtlasPage page = pages.get(old.atlasIndex());
            page.freeSlot(old.slot());
        }
        return true;
    }
    return false;
}
```

**Known limitation:** `getOrCreatePage()` (line ~411) only checks the *last* page for capacity (`pages.get(pages.size() - 1)`). Freed slots on earlier pages are invisible to it. In practice this is low-risk because the engine uses at most 2 atlas pages and PLC art churn occurs on the same page. If multi-page reclamation is needed in the future, `getOrCreatePage()` should scan all pages.

Add the alias-check helper method:

```java
private boolean isSlotShared(Entry removed) {
    int targetAtlas = removed.atlasIndex();
    int targetSlot = removed.slot();
    // Check fast entries
    for (int i = 0; i < FAST_ENTRIES_SIZE; i++) {
        Entry e = fastEntries[i];
        if (e != null && e.atlasIndex() == targetAtlas && e.slot() == targetSlot) {
            return true;
        }
    }
    // Check sparse entries
    for (Entry e : sparseEntries.values()) {
        if (e.atlasIndex() == targetAtlas && e.slot() == targetSlot) {
            return true;
        }
    }
    return false;
}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `mvn test -Dtest="TestPatternAtlasSlotReclamation" -q 2>&1 | tail -5`
Expected: PASS — all 3 tests green

- [ ] **Step 7: Run full test suite**

Run: `mvn test -q 2>&1 | tail -5`
Expected: Same pass/fail counts as before (3 pre-existing S3K failures only)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/graphics/PatternAtlas.java src/test/java/com/openggf/graphics/TestPatternAtlasSlotReclamation.java
git commit -m "$(cat <<'EOF'
feat: add slot reclamation to PatternAtlas

AtlasPage now tracks freed slots in an ArrayDeque. allocateSlot()
checks the free list before incrementing the monotonic counter.
hasCapacity() accounts for free slots so getOrCreatePage() doesn't
prematurely create new pages. removeEntry() frees the slot with
alias safety — shared slots (from aliasEntry) are not freed until
all references are removed.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Batch DPLC Atlas Updates

Wrap `DynamicPatternBank.applyRequests()` with the existing `beginBatch()`/`endBatch()` API so 10-30 individual `glTexSubImage2D` calls collapse into one full-page upload.

**Files:**
- Modify: `src/main/java/com/openggf/level/render/DynamicPatternBank.java:49-71`

- [ ] **Step 1: Verify batch API exists on GraphicsManager**

Run: `grep -n "beginPatternAtlasBatch\|endPatternAtlasBatch" src/main/java/com/openggf/graphics/GraphicsManager.java`
Expected: Two method declarations found (around lines 349 and 359). These delegate to `patternAtlas.beginBatch()`/`endBatch()` with null/headless guards.

- [ ] **Step 2: Read current DynamicPatternBank.applyRequests()**

Read `src/main/java/com/openggf/level/render/DynamicPatternBank.java` fully. The method (lines 49-71) has:
- Early return if `requests == null || source == null`
- Loop over `TileLoadRequest` entries
- Inner loop: `patterns[dstIndex].copyFrom(source[srcIndex])` then `graphicsManager.updatePatternTexture(...)` per tile
- The `graphicsManager` field is already stored on the class

- [ ] **Step 3: Add batch wrapping**

Modify `applyRequests()` to wrap the upload loop:

```java
public void applyRequests(List<TileLoadRequest> requests, Pattern[] source) {
    if (requests == null || source == null) {
        return;
    }

    if (cached && graphicsManager != null) {
        graphicsManager.beginPatternAtlasBatch();
    }

    int dstIndex = 0;
    for (TileLoadRequest request : requests) {
        int count = Math.max(0, request.count());
        int startTile = Math.max(0, request.startTile());
        for (int i = 0; i < count; i++) {
            if (dstIndex >= patterns.length) {
                if (cached && graphicsManager != null) {
                    graphicsManager.endPatternAtlasBatch();
                }
                return;
            }
            int srcIndex = startTile + i;
            if (srcIndex >= 0 && srcIndex < source.length) {
                patterns[dstIndex].copyFrom(source[srcIndex]);
                if (cached && graphicsManager != null) {
                    graphicsManager.updatePatternTexture(patterns[dstIndex], basePatternIndex + dstIndex);
                }
            }
            dstIndex++;
        }
    }

    if (cached && graphicsManager != null) {
        graphicsManager.endPatternAtlasBatch();
    }
}
```

**Key detail:** The early return at `dstIndex >= patterns.length` must also call `endBatch()` to avoid leaving the atlas in batch mode. Use a try/finally if preferred:

```java
if (cached && graphicsManager != null) {
    graphicsManager.beginPatternAtlasBatch();
}
try {
    // ... existing loop unchanged ...
} finally {
    if (cached && graphicsManager != null) {
        graphicsManager.endPatternAtlasBatch();
    }
}
```

The try/finally approach is cleaner and avoids the duplicated endBatch call on early return.

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run tests**

Run: `mvn test -q 2>&1 | tail -5`
Expected: Same pass/fail counts as before

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/render/DynamicPatternBank.java
git commit -m "$(cat <<'EOF'
perf: batch DPLC atlas updates in DynamicPatternBank

applyRequests() now wraps the tile upload loop in beginBatch/endBatch.
In batch mode, PatternAtlas defers glTexSubImage2D calls and writes to
a CPU-side buffer instead. endBatch() flushes one upload per dirty page.
This collapses 10-30 individual GL calls per animation frame change
into a single bulk upload.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: BatchedPatternRenderer writeQuad Extraction

Extract the duplicated 24-line vertex+texcoord construction block from three methods into a shared `writeQuad()` helper.

**Files:**
- Modify: `src/main/java/com/openggf/graphics/BatchedPatternRenderer.java`

- [ ] **Step 1: Read the three methods**

Read `BatchedPatternRenderer.java` and identify the duplicated blocks:
- `addPattern()` lines 180-208: vertex writes at 180-192, texcoord writes at 196-208
- `addStripPattern()` lines 314-342: vertex writes at 314-326, texcoord writes at 330-342
- `addShadowPattern()` lines 458-486: vertex writes at 458-470, texcoord writes at 472-486

Verify all three have identical array assignment structure with parameterized x0/y0/x1/y1 and u0/v0/u1/v1 values.

- [ ] **Step 2: Add the writeQuad helper method**

Add a private method to `BatchedPatternRenderer`:

```java
/**
 * Writes a 2-triangle quad (6 vertices) into the vertex and texcoord arrays.
 */
private void writeQuad(int vertOffset, int texOffset,
        float x0, float y0, float x1, float y1,
        float u0, float v0, float u1, float v1) {
    // Triangle 1: bottom-left, bottom-right, top-right
    vertexData[vertOffset]      = x0;
    vertexData[vertOffset + 1]  = y0;
    vertexData[vertOffset + 2]  = x1;
    vertexData[vertOffset + 3]  = y0;
    vertexData[vertOffset + 4]  = x1;
    vertexData[vertOffset + 5]  = y1;
    // Triangle 2: bottom-left, top-right, top-left
    vertexData[vertOffset + 6]  = x0;
    vertexData[vertOffset + 7]  = y0;
    vertexData[vertOffset + 8]  = x1;
    vertexData[vertOffset + 9]  = y1;
    vertexData[vertOffset + 10] = x0;
    vertexData[vertOffset + 11] = y1;

    // Triangle 1 texture coords
    texCoordData[texOffset]      = u0;
    texCoordData[texOffset + 1]  = v0;
    texCoordData[texOffset + 2]  = u1;
    texCoordData[texOffset + 3]  = v0;
    texCoordData[texOffset + 4]  = u1;
    texCoordData[texOffset + 5]  = v1;
    // Triangle 2 texture coords
    texCoordData[texOffset + 6]  = u0;
    texCoordData[texOffset + 7]  = v0;
    texCoordData[texOffset + 8]  = u1;
    texCoordData[texOffset + 9]  = v1;
    texCoordData[texOffset + 10] = u0;
    texCoordData[texOffset + 11] = v1;
}
```

- [ ] **Step 3: Replace duplicated blocks in addPattern()**

In `addPattern()`, replace the vertex and texcoord array writes (lines ~180-208) with:
```java
int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;
writeQuad(vertOffset, texOffset, x0, y0, x1, y1, u0, v0, u1, v1);
```
Keep the palette data fill and `patternCount++` that follow.

- [ ] **Step 4: Replace duplicated blocks in addStripPattern()**

In `addStripPattern()`, replace the vertex and texcoord writes (lines ~314-342) with:
```java
int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;
writeQuad(vertOffset, texOffset, x0, y0, x1, y1, u0, v0, u1, v1);
```
Keep the palette data fill and `patternCount++` that follow. All the strip-specific UV calculation stays above this call.

- [ ] **Step 5: Replace duplicated blocks in addShadowPattern()**

In `addShadowPattern()`, replace the vertex and texcoord writes (lines ~458-486) with:
```java
int vertOffset = patternCount * FLOATS_PER_PATTERN_VERTS;
int texOffset = patternCount * FLOATS_PER_PATTERN_TEXCOORDS;
writeQuad(vertOffset, texOffset, x0, y0, x1, y1, u0, v0, u1, v1);
```
Keep `patternCount++` (no palette data in shadow path).

- [ ] **Step 6: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Run full test suite**

Run: `mvn test -q 2>&1 | tail -5`
Expected: Same pass/fail counts as before (no rendering regressions)

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/graphics/BatchedPatternRenderer.java
git commit -m "$(cat <<'EOF'
refactor: extract writeQuad() from BatchedPatternRenderer

addPattern(), addStripPattern(), and addShadowPattern() each had
identical 24-line vertex+texcoord construction blocks. Extracted into
a shared writeQuad() helper. No behavioral change — rendering output
is identical.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```
