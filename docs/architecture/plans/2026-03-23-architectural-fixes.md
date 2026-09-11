# Architectural Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 6 layering/coupling violations and 2 code-health issues, then add diagnostic guardrails and lifecycle documentation.

**Architecture:** Each task is an independent, self-contained commit. Phase A (Tasks 1-6) fixes coupling violations in shared code. Phase B (Tasks 7-8) adds diagnostics and documentation after Phase A is merged. No new features — only structural improvements.

**Tech Stack:** Java 21, Maven

**Spec:** `docs/architecture/designs/2026-03-23-architectural-fixes-design.md`

---

### Task 1: Extract game-specific art dispatching from LevelManager (T1-1)

`LevelManager` uses `instanceof` chains against 3 game-specific `ObjectArtProvider` subclasses. Move the dispatching into the providers via a new polymorphic default method on `ObjectArtProvider`.

**Files:**
- Modify: `src/main/java/com/openggf/game/ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1ObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java:1270-1299,3086-3092`

- [ ] **Step 1: Add default method to ObjectArtProvider**

Add to `ObjectArtProvider.java` after the existing `getHudFlashMode()` default (after line 143):

```java
/**
 * Registers object art sheets that depend on level tile data (e.g., smashable
 * ground, collapsing ledges, platforms that reuse level patterns).
 * Called after level load when the level's Pattern[] is available.
 *
 * @param level     the loaded level (provides pattern data)
 * @param zoneIndex the current zone index
 */
default void registerLevelTileArt(Level level, int zoneIndex) {
    // Default no-op — games without level-tile-based object art need not override
}
```

Add import: `import com.openggf.level.Level;`

- [ ] **Step 2: Override in Sonic2ObjectArtProvider**

Add override that moves the two calls currently at `LevelManager.java:1271-1274`:

```java
@Override
public void registerLevelTileArt(Level level, int zoneIndex) {
    registerSmashableGroundSheet(level);
    registerSteamSpringPistonSheet(level);
}
```

Both `registerSmashableGroundSheet` and `registerSteamSpringPistonSheet` already exist as methods on this class. Add `import com.openggf.level.Level;` if not present.

- [ ] **Step 3: Override in Sonic1ObjectArtProvider**

Add override that moves the block currently at `LevelManager.java:1276-1294`. The zone-specific conditionals use `Sonic1Constants` which is already imported in this class:

```java
@Override
public void registerLevelTileArt(Level level, int zoneIndex) {
    registerPlatformSheet(level, zoneIndex);
    registerCollapsingLedgeSheet(level, zoneIndex);
    registerMzBrickSheet(level, zoneIndex);
    registerLargeGrassyPlatformSheet(level, zoneIndex);
    registerLavaWallSheet(level, zoneIndex);
    registerFloatingBlockSheet(level, zoneIndex);
    registerCirclingPlatformSheet(level, zoneIndex);
    registerStaircaseSheet(level, zoneIndex);
    registerElevatorSheet(level, zoneIndex);
    if (zoneIndex == Sonic1Constants.ZONE_SYZ) {
        registerSpinningLightSheet(level);
        registerBossBlockSheet(level);
    }
    if (zoneIndex == Sonic1Constants.ZONE_LZ) {
        registerSbz3BigDoorSheet(level, zoneIndex);
    }
}
```

Add `import com.openggf.level.Level;` if not present.

- [ ] **Step 4: Override in Sonic3kObjectArtProvider**

Add override that moves the call currently at `LevelManager.java:1296-1298`:

```java
@Override
public void registerLevelTileArt(Level level, int zoneIndex) {
    registerLevelArtSheets(level, zoneIndex);
}
```

Add `import com.openggf.level.Level;` if not present.

- [ ] **Step 5: Replace both instanceof chains in LevelManager**

At line 1270 (first chain, inside `initArt`), replace the entire `if (provider instanceof Sonic2ObjectArtProvider ...` through `if (provider instanceof Sonic3kObjectArtProvider ...` block (lines 1271-1299) with:

```java
// Register level-tile-based object art (must be after level load)
provider.registerLevelTileArt(level, zoneIndex);
objectRenderManager.ensurePatternsCached(graphicsManager, OBJECT_PATTERN_BASE);
```

At line 3086 (second site, inside the act-transition method), the existing code is a single S3K-only `instanceof` check. Replace lines 3086-3092:

```java
// Before:
// ObjectArtProvider artProvider = gameModule != null ? gameModule.getObjectArtProvider() : null;
// if (artProvider instanceof Sonic3kObjectArtProvider s3kProvider) {
//     s3kProvider.registerLevelArtSheets(level, currentZone);
//     ...
// }

// After:
ObjectArtProvider artProvider = gameModule != null ? gameModule.getObjectArtProvider() : null;
if (artProvider != null) {
    artProvider.registerLevelTileArt(level, currentZone);
    if (objectRenderManager != null) {
        objectRenderManager.ensurePatternsCached(graphicsManager, OBJECT_PATTERN_BASE);
    }
}
```

Note: the `artProvider` local variable already exists at line 3086 — reuse it.

- [ ] **Step 6: Remove unused imports from LevelManager**

Remove these imports (verify each is no longer referenced after the changes):
- `import com.openggf.game.sonic1.Sonic1ObjectArtProvider;`
- `import com.openggf.game.sonic2.Sonic2ObjectArtProvider;`
- `import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;`
- `import com.openggf.game.sonic1.constants.Sonic1Constants;` — only if no other references remain in the file

- [ ] **Step 7: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Verify no ObjectArtProvider instanceof remains**

Run: `grep -n "instanceof.*ObjectArtProvider" src/main/java/com/openggf/level/LevelManager.java`
Expected: no matches

- [ ] **Step 9: Run tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/openggf/game/ObjectArtProvider.java \
       src/main/java/com/openggf/game/sonic2/Sonic2ObjectArtProvider.java \
       src/main/java/com/openggf/game/sonic1/Sonic1ObjectArtProvider.java \
       src/main/java/com/openggf/game/sonic3k/Sonic3kObjectArtProvider.java \
       src/main/java/com/openggf/level/LevelManager.java
git commit -m "$(cat <<'EOF'
refactor: extract game-specific art dispatching from LevelManager

LevelManager used instanceof chains against Sonic1/2/3k ObjectArtProvider
subclasses to register level-tile-based object art. Add polymorphic
registerLevelTileArt() default method to ObjectArtProvider and override
in each game's provider, eliminating 3 game-specific imports from
LevelManager.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Move S2 scroll handlers out of ParallaxManager (T1-2)

This is the largest task. ParallaxManager imports 10 S2 scroll handler classes, `BackgroundCamera`, `ParallaxTables`, and `DynamicHtz`, and hardcodes 11 zone constants. Complete the migration to `Sonic2ScrollHandlerProvider` and eliminate the `loaded` flag dual-path.

**Files:**
- Modify: `src/main/java/com/openggf/level/scroll/ZoneScrollHandler.java`
- Modify: `src/main/java/com/openggf/game/ScrollHandlerProvider.java`
- Modify: `src/main/java/com/openggf/game/sonic2/scroll/Sonic2ScrollHandlerProvider.java`
- Modify: `src/main/java/com/openggf/game/GameModule.java`
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java`
- Modify: `src/main/java/com/openggf/level/ParallaxManager.java`

- [ ] **Step 1: Extend ZoneScrollHandler with default methods**

Add to `ZoneScrollHandler.java` after the existing `getBgPeriodWidth()` default:

```java
/**
 * Get the VScroll factor for Plane A (foreground).
 * Used by MCZ and HTZ for earthquake FG scroll offsets.
 *
 * @return VScroll factor for foreground plane (default 0 = no FG scroll adjustment)
 */
default short getVscrollFactorFG() { return 0; }

/**
 * Screen shake X offset produced by this zone's scroll routine.
 * Accumulated by ParallaxManager into the global shake state.
 *
 * @return shake X offset in pixels (default 0)
 */
default int getShakeOffsetX() { return 0; }

/**
 * Screen shake Y offset produced by this zone's scroll routine.
 * Accumulated by ParallaxManager into the global shake state.
 *
 * @return shake Y offset in pixels (default 0)
 */
default int getShakeOffsetY() { return 0; }

/**
 * Initialize this handler for a zone/act transition.
 * Called once when a new zone is loaded or after an act transition.
 *
 * @param actId   current act (0-based)
 * @param cameraX camera X position at load time
 * @param cameraY camera Y position at load time
 */
default void init(int actId, int cameraX, int cameraY) { /* no-op */ }
```

- [ ] **Step 2: Extend ScrollHandlerProvider with new default methods**

Add to `ScrollHandlerProvider.java` after the `getZoneConstants()` method:

```java
/**
 * Initialize scroll state for a zone/act transition.
 * Called once when a new zone is loaded.
 *
 * @param zoneId  the zone index
 * @param actId   the act index (0-based)
 * @param cameraX camera X at load time
 * @param cameraY camera Y at load time
 */
default void initForZone(int zoneId, int actId, int cameraX, int cameraY) { /* no-op */ }

/**
 * Update dynamic art streaming (e.g., HTZ cloud tiles).
 * Called once per frame after scroll handlers have updated.
 *
 * @param level   the current level (for pattern access)
 * @param cameraX camera X position
 */
default void updateDynamicArt(com.openggf.level.Level level, int cameraX) { /* no-op */ }

/**
 * Returns the tornado velocity X for SCZ-style auto-scrolling zones.
 * @return velocity in subpixels, or 0 if not applicable
 */
default int getTornadoVelocityX() { return 0; }

/**
 * Returns the tornado velocity Y for SCZ-style auto-scrolling zones.
 * @return velocity in subpixels, or 0 if not applicable
 */
default int getTornadoVelocityY() { return 0; }
```

Add import: `import com.openggf.level.Level;` (only needed if the default method references it by simple name — otherwise use the FQN shown above).

- [ ] **Step 3: Complete Sonic2ScrollHandlerProvider**

Rewrite `Sonic2ScrollHandlerProvider.java` to:
1. Add the 5 missing handlers: `cnzHandler`, `htzHandler`, `oozHandler`, `sczHandler`, `wfzHandler`
2. Own `BackgroundCamera` and `DynamicHtz`
3. Override `initForZone()`, `updateDynamicArt()`, `getTornadoVelocityX()/Y()`
4. Update `getHandler()` switch to return all 10 zones
5. Remove `getTables()` and `getMczHandler()` accessors (no longer needed externally)

The `load()` method instantiates all 10 handlers plus `BackgroundCamera` and `DynamicHtz`. The `initForZone()` method dispatches to zone-specific `handler.init()` calls plus `bgCamera.init()`. The `updateDynamicArt()` method calls `dynamicHtz.update(level, cameraX, htzHandler)`.

Constructors that need `BackgroundCamera`: `SwScrlHtz(tables, bgCamera)` and `SwScrlWfz(tables, bgCamera)`. Constructors that need no extra args: `SwScrlCnz(tables)`, `SwScrlOoz(tables)`, `SwScrlScz()`.

- [ ] **Step 4: Remove `hasInlineParallaxHandlers()` from GameModule**

In `GameModule.java`, delete the `hasInlineParallaxHandlers()` method (lines ~303-306).

In `Sonic2GameModule.java`, delete the `hasInlineParallaxHandlers()` override that returns `true`.

- [ ] **Step 5: Unify ParallaxManager dispatch**

In `ParallaxManager.java`:
1. Remove all 10 `SwScrl*` imports, `BackgroundCamera`, `ParallaxTables`, `DynamicHtz` imports
2. Remove the 11 hardcoded zone constants (`ZONE_EHZ` through `ZONE_DEZ`)
3. Remove the `loaded` flag and all handler instance fields
4. Remove the S2-specific code in `load()` (handler instantiation gated by `hasInlineParallaxHandlers()`)
5. Remove the S2-specific code in `initZone()` (zone-specific handler init dispatch)
6. Replace with: `scrollProvider.initForZone(zoneId, actId, cameraX, cameraY)` in `initZone()`
7. Replace the 120-line S2 switch statement in `update()` AND the existing provider path with a single unified path:
   ```java
   ZoneScrollHandler handler = scrollProvider != null ? scrollProvider.getHandler(zoneId) : null;
   if (handler != null) {
       handler.update(hScroll, cameraX, cameraY, frameCounter, actId);
       minScroll = handler.getMinScrollOffset();
       maxScroll = handler.getMaxScrollOffset();
       vscrollFactorBG = handler.getVscrollFactorBG();
       vscrollFactorFG = handler.getVscrollFactorFG();
       currentShakeOffsetX = handler.getShakeOffsetX();
       currentShakeOffsetY = handler.getShakeOffsetY();
       cachedBgCameraX = handler.getBgCameraX();
       cachedBgPeriodWidth = handler.getBgPeriodWidth();
       capturePerLineVScroll(handler);
       capturePerColumnVScroll(handler);
   }
   ```
8. Replace tornado velocity accessors with: `scrollProvider.getTornadoVelocityX()` / `Y()`
9. Replace DynamicHtz update with: `scrollProvider.updateDynamicArt(level, cam.getX())` (called once per frame when handler is non-null)

- [ ] **Step 6: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Verify no S2 imports remain in ParallaxManager**

Run: `grep -n "import com.openggf.game.sonic" src/main/java/com/openggf/level/ParallaxManager.java`
Expected: no matches

- [ ] **Step 8: Run tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/openggf/level/scroll/ZoneScrollHandler.java \
       src/main/java/com/openggf/game/ScrollHandlerProvider.java \
       src/main/java/com/openggf/game/sonic2/scroll/Sonic2ScrollHandlerProvider.java \
       src/main/java/com/openggf/game/GameModule.java \
       src/main/java/com/openggf/game/sonic2/Sonic2GameModule.java \
       src/main/java/com/openggf/level/ParallaxManager.java
git commit -m "$(cat <<'EOF'
refactor: migrate all S2 scroll handlers to Sonic2ScrollHandlerProvider

Complete the migration of 10 zone scroll handlers from inline
ParallaxManager code to the provider. Move BackgroundCamera, ParallaxTables,
and DynamicHtz ownership into the provider. Eliminate the loaded flag
dual-path and hasInlineParallaxHandlers() flag. ParallaxManager now uses
a single unified provider-based dispatch for all games.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Replace swallowed exceptions in S3K code (T1-3)

Add `LOG.fine()` to all 28 `catch (Exception ignored) {}` blocks across 17 S3K files.

**Files:**
- Modify: 17 files in `src/main/java/com/openggf/game/sonic3k/` (listed in spec)

- [ ] **Step 1: Replace all empty catch blocks**

For each of the 17 files listed in the spec, replace every `catch (Exception ignored) {}` with:

```java
} catch (Exception e) {
    LOG.fine(() -> "<method/context>: " + e.getMessage());
}
```

Where `<method/context>` is the enclosing method name or a brief description of what was attempted. For example:
- In `SwScrlAiz.java`: `"SwScrlAiz.update"`, `"SwScrlAiz.getShakeOffsetY"`, etc.
- In `AizPlaneIntroInstance.java`: `"AizPlaneIntro.loadArt"`, `"AizPlaneIntro.spawnChild"`, etc.

For files that don't already have a `LOG` field, add one:
```java
private static final Logger LOG = Logger.getLogger(ClassName.class.getName());
```
And add `import java.util.logging.Logger;` if missing.

- [ ] **Step 2: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 3: Verify no swallowed exceptions remain**

Run: `grep -rn "catch (Exception ignored)" src/main/java/com/openggf/game/sonic3k/`
Expected: no matches

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/
git commit -m "$(cat <<'EOF'
fix: replace 28 swallowed exceptions in S3K code with LOG.fine()

All catch (Exception ignored) {} blocks in S3K now log the exception
message at FINE level. No behavioral change — catch-and-continue is
preserved for S3K bring-up stability, but failures are now visible
when debug logging is enabled.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Standardize singleton reset (T1-4)

Add `resetState()` to `GameStateManager`, update teardown call sites, and add 3 missing fields to `LevelManager.resetState()`.

**Files:**
- Modify: `src/main/java/com/openggf/game/GameStateManager.java`
- Modify: `src/main/java/com/openggf/game/AbstractLevelInitProfile.java:257,286`
- Modify: `src/main/java/com/openggf/level/LevelManager.java:3334-3358`

- [ ] **Step 1: Add resetState() to GameStateManager**

Add after the `resetForLevel()` method (after line 150):

```java
/**
 * Resets all mutable state for test teardown.
 * Delegates to {@link #resetSession()} for the actual reset logic.
 * This method exists for naming consistency with other singletons
 * (Camera, CollisionSystem, TimerManager, etc.).
 */
public void resetState() {
    resetSession();
}
```

- [ ] **Step 2: Update teardown call sites in AbstractLevelInitProfile**

At line 257, change:
```java
() -> GameServices.gameState().resetSession()),
```
to:
```java
() -> GameServices.gameState().resetState()),
```

At line 286, make the same change:
```java
() -> GameServices.gameState().resetSession()),
```
to:
```java
() -> GameServices.gameState().resetState()),
```

- [ ] **Step 3: Add missing fields to LevelManager.resetState()**

In `LevelManager.java`, add these 3 lines inside `resetState()` before the `cacheLevelDimensions()` call (before line 3356):

```java
touchResponseTable = null;
currentShimmerStyle = 0;
useShaderBackground = true;
```

- [ ] **Step 4: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/GameStateManager.java \
       src/main/java/com/openggf/game/AbstractLevelInitProfile.java \
       src/main/java/com/openggf/level/LevelManager.java
git commit -m "$(cat <<'EOF'
refactor: standardize singleton reset naming and completeness

Add resetState() to GameStateManager (delegates to resetSession()) for
naming consistency with other singletons. Update teardown call sites in
AbstractLevelInitProfile to use resetState(). Add 3 missing field resets
to LevelManager.resetState(): touchResponseTable, currentShimmerStyle,
useShaderBackground.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Resolve AbstractPlayableSprite Sonic2AnimationIds import (T2-7)

Replace the single `Sonic2AnimationIds.BUBBLE` reference with a game-agnostic resolved ID.

**Files:**
- Modify: `src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java:5,1973,2872`

- [ ] **Step 1: Add bubbleAnimId field**

Add a field near the other animation/physics fields in `AbstractPlayableSprite`:

```java
/** Resolved native animation ID for CanonicalAnimation.BUBBLE. -1 if unsupported. */
private int bubbleAnimId = -1;
```

- [ ] **Step 2: Resolve the ID in resolvePhysicsProfile()**

Add after the insta-shield registration block (after line 1973, just before the method's final closing brace at line 1974). This is outside the try/catch and after all profile/feature-set resolution:

```java
bubbleAnimId = GameModuleRegistry.getCurrent().resolveAnimationId(CanonicalAnimation.BUBBLE);
```

Add import: `import com.openggf.game.CanonicalAnimation;` (if not already present).

- [ ] **Step 3: Replace the usage in replenishAir()**

At line 2872, change:
```java
setAnimationId(Sonic2AnimationIds.BUBBLE);
```
to:
```java
if (bubbleAnimId >= 0) {
    setAnimationId(bubbleAnimId);
}
```

- [ ] **Step 4: Remove the S2 import**

Remove: `import com.openggf.game.sonic2.constants.Sonic2AnimationIds;` (line 5)

- [ ] **Step 5: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Verify import is gone**

Run: `grep "Sonic2AnimationIds" src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java`
Expected: no matches

- [ ] **Step 7: Run tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java
git commit -m "$(cat <<'EOF'
refactor: resolve bubble animation ID via CanonicalAnimation

Replace hardcoded Sonic2AnimationIds.BUBBLE in replenishAir() with a
game-agnostic resolved ID from GameModule.resolveAnimationId(). The ID
is cached at init time; -1 guard skips the set for games without a
BUBBLE animation (e.g., S1). Removes the last Sonic2-specific import
from AbstractPlayableSprite.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Make Engine debug fields private (T2-9)

Encapsulate `debugState` and `debugOption` with `volatile` and accessors.

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java:65-66,862,926-927,931`
- Modify: `src/main/java/com/openggf/level/LevelDebugRenderer.java:150-157`

- [ ] **Step 1: Change fields and add accessors in Engine.java**

At lines 65-66, change:
```java
public static DebugState debugState = DebugState.NONE;
public static DebugOption debugOption = DebugOption.A;
```
to:
```java
private static volatile DebugState debugState = DebugState.NONE;
private static volatile DebugOption debugOption = DebugOption.A;

public static DebugState getDebugState() { return debugState; }
public static DebugOption getDebugOption() { return debugOption; }
public static void setDebugOption(DebugOption option) { debugOption = option; }
```

Internal references within `Engine.java` (lines 862, 926, 927, 931) can continue using the field directly since they're in the same class.

- [ ] **Step 2: Update LevelDebugRenderer**

At line 150, change:
```java
if (Engine.debugOption.ordinal() > LevelConstants.MAX_PALETTES) {
```
to:
```java
if (Engine.getDebugOption().ordinal() > LevelConstants.MAX_PALETTES) {
```

At line 151, change:
```java
Engine.debugOption = DebugOption.A;
```
to:
```java
Engine.setDebugOption(DebugOption.A);
```

At line 157, change:
```java
reusablePatternDesc.setPaletteIndex(Engine.debugOption.ordinal());
```
to:
```java
reusablePatternDesc.setPaletteIndex(Engine.getDebugOption().ordinal());
```

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/Engine.java \
       src/main/java/com/openggf/level/LevelDebugRenderer.java
git commit -m "$(cat <<'EOF'
refactor: encapsulate Engine debug fields with volatile accessors

Make debugState and debugOption private volatile with public accessors.
Prevents unsynchronized cross-thread field access between input handling
and debug rendering. Update LevelDebugRenderer to use accessors.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: Add virtual pattern ID range validation (T3-10)

Add diagnostic range registration and collision detection to `PatternAtlas`.

**Files:**
- Modify: `src/main/java/com/openggf/graphics/PatternAtlas.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

- [ ] **Step 1: Add PatternRange record and registration to PatternAtlas**

Add inside `PatternAtlas.java` class body (after the field declarations, before the constructor):

```java
/** Describes a registered virtual pattern ID range for collision detection. */
public record PatternRange(int base, int size, String category) {}

private final List<PatternRange> registeredRanges = new ArrayList<>();

/**
 * Registers a virtual pattern ID range for collision detection.
 * Logs a warning if the range overlaps an existing registered range.
 * Diagnostic only — does not prevent allocation.
 *
 * @param base     the starting pattern ID
 * @param size     the number of patterns in this range
 * @param category a human-readable name for logging (e.g., "Objects", "HUD")
 */
public void registerRange(int base, int size, String category) {
    int newEnd = base + size;
    for (PatternRange existing : registeredRanges) {
        int existingEnd = existing.base() + existing.size();
        if (base < existingEnd && newEnd > existing.base()) {
            LOGGER.warning("Pattern range collision: " + category
                + " [0x" + Integer.toHexString(base) + "-0x" + Integer.toHexString(newEnd)
                + "] overlaps " + existing.category()
                + " [0x" + Integer.toHexString(existing.base())
                + "-0x" + Integer.toHexString(existingEnd) + "]");
        }
    }
    registeredRanges.add(new PatternRange(base, size, category));
}

/** Clears all registered ranges. Called on level unload or atlas reset. */
public void clearRanges() {
    registeredRanges.clear();
}
```

- [ ] **Step 2: Register ranges in LevelManager**

`ObjectRenderManager.ensurePatternsCached()` returns `int` (the next available pattern index after caching). Use this return value to compute the range size.

After the object art caching call, capture the return value and register:

```java
int objectEndIndex = objectRenderManager.ensurePatternsCached(graphicsManager, OBJECT_PATTERN_BASE);
graphicsManager.getPatternAtlas().registerRange(
    OBJECT_PATTERN_BASE, objectEndIndex - OBJECT_PATTERN_BASE, "Objects");
```

For HUD, apply the same pattern — `HudRenderManager` initialization at lines 1310-1349 chains base indices. Register after the HUD is set up:

```java
graphicsManager.getPatternAtlas().registerRange(
    HUD_PATTERN_BASE, hudRenderManager.getTotalPatternCount(), "HUD");
```

If `HudRenderManager` doesn't expose a total count, compute it from the chained base indices already visible in the init code (digits → text → lives → livesNumbers).

For water and sidekick banks, apply the same return-value capture pattern at their respective `ensurePatternsCached` calls.

Add `graphicsManager.getPatternAtlas().clearRanges()` at the start of the level load method (before any pattern caching).

- [ ] **Step 3: Verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Run tests**

Run: `mvn test -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/openggf/graphics/PatternAtlas.java \
       src/main/java/com/openggf/level/LevelManager.java
git commit -m "$(cat <<'EOF'
feat: add virtual pattern ID range validation to PatternAtlas

Register named ranges when allocating pattern IDs and log warnings if
any ranges overlap. Diagnostic only — does not prevent allocation.
Catches accidental range collisions that would silently corrupt textures.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: Document singleton lifecycle (T3-12)

Create comprehensive lifecycle documentation for all singletons.

**Files:**
- Create: `docs/architecture/singleton-lifecycle.md`

- [ ] **Step 1: Write the document**

Create `docs/architecture/singleton-lifecycle.md` with:

1. **Singleton inventory table** — all singletons with: class name, package, reset method, thread safety, critical dependencies. Source this by grepping for `getInstance()` across the codebase and reading each class.

2. **Initialization order** — document the Engine constructor's eager captures (SonicConfigurationService, SpriteManager, GraphicsManager, Camera, PerformanceProfiler, LevelManager) and note that all other singletons are lazy-created on first `getInstance()` call.

3. **Reset graph** — the full `GameContext.forTesting()` → `AbstractLevelInitProfile.levelTeardownSteps()` sequence with all 13 steps, using `resetState()` consistently (after Task 4).

4. **Thread-safety contract** — document which singletons use `synchronized getInstance()` (most of them), which assume single-thread game loop access, and the `volatile` fields in Engine (after Task 6).

5. **HeadlessTestRunner setup checklist** — the 5-step manual reset sequence from the HeadlessTestRunner javadoc, with ordering rationale.

- [ ] **Step 2: Commit**

```bash
git add docs/architecture/singleton-lifecycle.md
git commit -m "$(cat <<'EOF'
docs: add singleton lifecycle documentation

Document all 43+ singletons with reset methods, thread safety,
initialization order, the full GameContext.forTesting() teardown
sequence, and the HeadlessTestRunner manual reset checklist.

Co-Authored-By: Claude Opus 4.6 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

### Final Verification

After all tasks:

- [ ] **Run full test suite**

Run: `mvn test`
Expected: BUILD SUCCESS, all tests pass

- [ ] **Verify import cleanup**

```bash
grep -r "import com.openggf.game.sonic" src/main/java/com/openggf/level/ParallaxManager.java
# Expected: no matches

grep "import com.openggf.game.sonic.*ObjectArtProvider" src/main/java/com/openggf/level/LevelManager.java
# Expected: no matches

grep "import com.openggf.game.sonic2.constants.Sonic2AnimationIds" src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java
# Expected: no matches

grep -r "catch (Exception ignored)" src/main/java/com/openggf/game/sonic3k/
# Expected: no matches
```
