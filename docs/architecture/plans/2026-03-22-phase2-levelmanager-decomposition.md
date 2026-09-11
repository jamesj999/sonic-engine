# Phase 2: LevelManager Decomposition — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract 3 focused classes from LevelManager.java (5,068 lines) to reduce it to ~2,500-2,800 lines with clear responsibility boundaries.

**Architecture:** Move-and-delegate: move methods and fields to the new class, leave thin delegation wrappers in LevelManager for external callers, update `resetState()` to delegate. Each extraction is one commit. Order: LevelDebugRenderer (cleanest), LevelTransitionCoordinator, LevelTilemapManager.

**Tech Stack:** Java 21, Maven

**Spec:** `docs/architecture/designs/2026-03-22-architectural-refactoring-design.md` (Phase 2)

---

### Task 1: Create LevelGeometry and LevelDebugContext Records

Small foundational types that the extracted classes will depend on.

**Files:**
- Create: `src/main/java/com/openggf/level/LevelGeometry.java`
- Create: `src/main/java/com/openggf/level/LevelDebugContext.java`

- [ ] **Step 1: Create LevelGeometry record**

```java
package com.openggf.level;

/**
 * Immutable snapshot of level geometry dimensions, shared between
 * LevelManager and LevelTilemapManager to avoid back-references.
 */
public record LevelGeometry(
    Level level,
    int fgWidthPx, int fgHeightPx,
    int bgWidthPx, int bgContiguousWidthPx, int bgHeightPx,
    int blockPixelSize, int chunksPerBlockSide
) {}
```

- [ ] **Step 2: Create LevelDebugContext record**

```java
package com.openggf.level;

import com.openggf.debug.DebugOverlayManager;
import com.openggf.graphics.GraphicsManager;

/**
 * Read-only context passed to LevelDebugRenderer for rendering
 * debug overlays without coupling to LevelManager fields.
 */
public record LevelDebugContext(
    Level level,
    int blockPixelSize,
    DebugOverlayManager overlayManager,
    GraphicsManager graphicsManager
) {}
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -pl . -q`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/LevelGeometry.java \
        src/main/java/com/openggf/level/LevelDebugContext.java
git commit -m "refactor: add LevelGeometry and LevelDebugContext records for LevelManager decomposition"
```

---

### Task 2: Extract LevelDebugRenderer

Move ~10 debug methods and ~12 fields from LevelManager into a new LevelDebugRenderer class. This is the least coupled extraction.

**Files:**
- Create: `src/main/java/com/openggf/level/LevelDebugRenderer.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

- [ ] **Step 1: Read LevelManager.java and identify all debug methods and fields**

Debug methods (exact line ranges from analysis):
- `isInCameraFrustum` (~575-580)
- `drawAllPatterns` (~1477-1524)
- `generateCollisionDebugCommands` (~2613-2675)
- `generateTilePriorityDebugCommands` (~2790-2896)
- `drawPlayableSpriteBounds` (~2898-2972)
- `drawCameraBounds` (~2974-2998)
- `appendLine` (~3000-3013)
- `appendCross` (~3015-3025)
- `appendPlaneSwitcherDebug` (~3027-3079)
- `appendBox` (~3081-3109)

Debug fields:
- Constants (~94-97): `SWITCHER_DEBUG_R/G/B/ALPHA`
- Command lists (~216-225): `debugObjectCommands`, `debugSwitcherLineCommands`, `debugSwitcherAreaCommands`, `debugRingCommands`, `debugBoxCommands`, `debugCenterCommands`, `collisionCommands`, `priorityDebugCommands`, `sensorCommands`, `cameraBoundsCommands`
- Context (~128,130): `overlayManager`, `reusableDebugCtx`

- [ ] **Step 2: Create LevelDebugRenderer class**

Move all debug methods and fields into the new class. The class receives a `LevelDebugContext` and exposes the same public methods. Constructor initializes the command lists and debug context.

Key design:
- Constructor takes `LevelDebugContext`
- `generateCollisionDebugCommands` and similar methods take `Camera` as parameter (already the case)
- Methods that access `level` or `blockPixelSize` use the context record
- The `getBlockAtPosition()` call uses `context.level().getBlockAtPosition()` (verify this method exists on Level)

- [ ] **Step 3: Update LevelManager**

- Remove moved fields and methods
- Add `private LevelDebugRenderer debugRenderer;` field
- Create the debugRenderer in constructor or after level load (when context data is available)
- Update call sites in `drawWithSpritePriority()` to delegate: `debugRenderer.generateCollisionDebugCommands(commands, camera)` etc.
- In `resetState()`: no debug state needs resetting (command lists are transient)

- [ ] **Step 4: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass (no behavior change)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/LevelDebugRenderer.java \
        src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: extract LevelDebugRenderer from LevelManager (~10 methods, ~12 fields)"
```

---

### Task 3: Extract LevelTransitionCoordinator

Move ~41 transition state machine methods and ~19 fields from LevelManager.

**Files:**
- Create: `src/main/java/com/openggf/level/LevelTransitionCoordinator.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

- [ ] **Step 1: Read LevelManager.java and identify all transition methods and fields**

Transition methods (~4451-4971):
- Special stage requests (~4451-4483)
- Big ring save/restore (~4490-4515)
- Title card requests (~4524-4599)
- Respawn/next act/next zone requests (~4732-4806)
- Seamless transition requests (~4812-4874)
- Credits requests (~4917-4930)
- HUD/music suppression (~4936-4947)

Transition fields (~176-209):
- `specialStageRequestedFromCheckpoint`, `specialStageReturnLevelReloadRequested`
- `bigRingReturnActive`, `bigRingReturnX`, `bigRingReturnY`, `bigRingReturnCameraX`, `bigRingReturnCameraY`
- `titleCardRequested`, `titleCardZone`, `titleCardAct`
- `inLevelTitleCardRequested`, `inLevelTitleCardZone`, `inLevelTitleCardAct`
- `respawnRequested`, `nextActRequested`, `nextZoneRequested`, `specificZoneActRequested`
- `requestedZone`, `requestedAct`
- `seamlessTransitionRequested`, `creditsRequested`
- `pendingSeamlessTransitionRequest`
- `forceHudSuppressed`, `suppressNextMusicChange`, `levelInactiveForTransition`

- [ ] **Step 2: Create LevelTransitionCoordinator class**

Move all transition methods and fields. Pure state machine — no singleton references needed.

Key design:
- All request/consume methods move as-is
- Add getters for `forceHudSuppressed` and `suppressNextMusicChange` (currently read directly by LevelManager rendering path)
- Add `resetState()` method that clears all transition fields (extracted from LevelManager.resetState() lines ~4631-4652)
- `executeActTransition()` and `applySeamlessTransition()` stay in LevelManager (they orchestrate level loading)

- [ ] **Step 3: Update LevelManager**

- Remove moved fields and methods
- Add `private final LevelTransitionCoordinator transitions = new LevelTransitionCoordinator();`
- Add `public LevelTransitionCoordinator getTransitions() { return transitions; }`
- Add thin delegation wrappers for commonly called methods (e.g., `requestRespawn()` → `transitions.requestRespawn()`)
- Update `resetState()` to call `transitions.resetState()` instead of clearing transition fields directly
- Update rendering path to call `transitions.isForceHudSuppressed()` instead of reading field directly
- Update `initAudio()` to call `transitions.isSuppressNextMusicChange()`

- [ ] **Step 4: Update external callers**

Grep for `LevelManager.getInstance().request*`, `consume*`, `saveBigRing*` etc. in the codebase. If callers go through `GameServices.level()`, the delegation wrappers handle it. Direct callers may need updating.

- [ ] **Step 5: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/LevelTransitionCoordinator.java \
        src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: extract LevelTransitionCoordinator from LevelManager (~41 methods, ~19 fields)"
```

---

### Task 4: Extract LevelTilemapManager

Move ~15 tilemap lifecycle methods and ~18 fields from LevelManager.

**Files:**
- Create: `src/main/java/com/openggf/level/LevelTilemapManager.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

- [ ] **Step 1: Read LevelManager.java and identify all tilemap methods and fields**

Tilemap build methods (~2312-2553):
- `ensureBackgroundTilemapData`, `ensureForegroundTilemapData`, `ensurePatternLookupData`
- `buildBackgroundTilemapData`, `buildForegroundTilemapData`, `buildTilemapData`
- `writeEmptyChunk`, `writeEmptyTile`, `writeTileDescriptor`
- `findActualBgTilemapDataHeight`

Tilemap control methods (~3529-3744):
- `invalidateForegroundTilemap`, `invalidateBackgroundTilemap` (if exists)
- `uploadForegroundTilemap`

Prebuilt tilemap methods (~3762-3822):
- `prebuildTransitionTilemaps`, `swapToPrebuiltTilemaps`, `hasPrebuiltTilemaps`

Inner type (~2602): `record TilemapData(byte[] data, int widthTiles, int heightTiles)`

Tilemap fields (~149-174):
- Background: `backgroundTilemapData`, `backgroundTilemapWidthTiles`, `backgroundTilemapHeightTiles`, `backgroundTilemapDirty`, `backgroundVdpWrapHeightTiles`, `bgTilemapBaseX`, `currentBgPeriodWidth`
- Foreground: `foregroundTilemapData`, `foregroundTilemapWidthTiles`, `foregroundTilemapHeightTiles`, `foregroundTilemapDirty`
- Pattern lookup: `patternLookupData`, `patternLookupSize`, `patternLookupDirty`, `multiAtlasWarningLogged`
- Prebuilt: `prebuiltFgTilemap`, `prebuiltFgWidth`, `prebuiltFgHeight`, `prebuiltBgTilemap`, `prebuiltBgWidth`, `prebuiltBgHeight`
- Constants: `VDP_BG_PLANE_WIDTH_PX`, `VDP_BG_PLANE_HEIGHT_TILES`

- [ ] **Step 2: Create LevelTilemapManager class**

Key design:
- Constructor takes `LevelGeometry` record for level data access
- Also needs: `GraphicsManager` reference (for `getPatternAtlas()`), `ParallaxManager` reference (for scroll data in prebuilt)
- `resetState()` clears all tilemap arrays, dirty flags, prebuilt data (extracted from LevelManager.resetState() lines ~4618-4630)
- `updateGeometry(LevelGeometry)` method to refresh when level data changes (e.g., after seamless transition)

Dependencies needed (from analysis):
- `level` (via LevelGeometry record)
- `graphicsManager` (for pattern atlas queries)
- `parallaxManager` (for prebuilt tilemap scroll data — verify actual usage)
- `reusablePatternDesc` (for tile writing — may need to pass or recreate)
- `blockPixelSize`, `currentZone` (via LevelGeometry or constructor params)

- [ ] **Step 3: Update LevelManager**

- Remove moved fields, methods, constants, and inner record
- Add `private LevelTilemapManager tilemapManager;` field
- Create tilemapManager after `cacheLevelDimensions()` with `new LevelTilemapManager(levelGeometry, graphicsManager, ...)`
- Add `public LevelTilemapManager getTilemapManager() { return tilemapManager; }`
- Update `resetState()` to call `tilemapManager.resetState()`
- Update rendering methods (`renderBackgroundShader`, `enqueueForegroundTilemapPass`, etc.) to delegate tilemap data access to `tilemapManager`
- The pre-allocated GL command lambdas that reference tilemap fields need updating to call `tilemapManager.getBackgroundTilemapData()` etc.

- [ ] **Step 4: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/LevelTilemapManager.java \
        src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: extract LevelTilemapManager from LevelManager (~15 methods, ~18 fields)"
```

---

### Task 5: Verify Decomposition

- [ ] **Step 1: Count LevelManager lines**

Run: `wc -l src/main/java/com/openggf/level/LevelManager.java`
Expected: ~2,500-2,800 lines (down from 5,068)

- [ ] **Step 2: Run full test suite**

Run: `mvn test -pl . -q`
Expected: All tests pass

- [ ] **Step 3: Verify extracted classes exist and are reasonable size**

```bash
wc -l src/main/java/com/openggf/level/LevelDebugRenderer.java
wc -l src/main/java/com/openggf/level/LevelTransitionCoordinator.java
wc -l src/main/java/com/openggf/level/LevelTilemapManager.java
```

- [ ] **Step 4: Verify no duplicate code**

Grep for any methods that exist in both LevelManager and an extracted class — should be zero (only delegation wrappers).

- [ ] **Step 5: Commit verification (if fixups needed)**

```bash
git add -u
git commit -m "refactor: complete Phase 2 LevelManager decomposition"
```
