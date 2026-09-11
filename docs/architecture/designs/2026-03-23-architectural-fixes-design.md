# Architectural Fixes — Design Spec

**Date:** 2026-03-23
**Scope:** Tier 1 fixes (4), Tier 2 selected (2), Tier 3 follow-up (2)
**Goal:** Eliminate game-specific coupling in shared code, improve error visibility, standardize singleton lifecycle, and add diagnostic guardrails. No new features.

---

## Phase A — Primary Fixes

### T1-1: Extract game-specific art dispatching from LevelManager

**Problem:** `LevelManager.java` imports `Sonic1ObjectArtProvider`, `Sonic2ObjectArtProvider`, and `Sonic3kObjectArtProvider` and uses `instanceof` chains at two call sites (lines 1271-1299 and 3087-3092) to invoke game-specific art registration methods. This violates the GameModule abstraction.

**Design:** Add a default method to `ObjectArtProvider`:

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
    // Default no-op
}
```

Each game's provider overrides it:

- **Sonic2ObjectArtProvider**: calls `registerSmashableGroundSheet(level)` and `registerSteamSpringPistonSheet(level)`
- **Sonic1ObjectArtProvider**: calls the 10+ `registerXxxSheet(level, zoneIndex)` methods plus zone conditionals (SYZ spinning light/boss block, LZ SBZ3 big door)
- **Sonic3kObjectArtProvider**: calls `registerLevelArtSheets(level, zoneIndex)`

LevelManager replaces both `instanceof` chains with:

```java
provider.registerLevelTileArt(level, zoneIndex);
objectRenderManager.ensurePatternsCached(graphicsManager, OBJECT_PATTERN_BASE);
```

**Imports removed from LevelManager:** `Sonic1ObjectArtProvider`, `Sonic2ObjectArtProvider`, `Sonic3kObjectArtProvider`, `Sonic1Constants` (the ZONE_SYZ/ZONE_LZ references move into S1's provider).

**Imports remaining (out of scope):** `Sonic2Level` (isForceBlackBackdrop), `Sonic2Constants` (ART_TILE_TAILS_TAILS), `S3kSeamlessMutationExecutor`, `Sonic3kPlayerArt`. These are better addressed in a future LevelManager decomposition.

**Files modified:**
- `ObjectArtProvider.java` — add default method
- `Sonic1ObjectArtProvider.java` — override with existing logic
- `Sonic2ObjectArtProvider.java` — override with existing logic
- `Sonic3kObjectArtProvider.java` — override with existing logic
- `LevelManager.java` — replace 2 instanceof chains, remove 3-4 imports

---

### T1-2: Move S2 scroll handlers out of ParallaxManager

**Problem:** `ParallaxManager.java` imports 10 S2 `SwScrl*` classes, `DynamicHtz`, `BackgroundCamera`, and `ParallaxTables`. It hardcodes 11 S2 zone ID constants. `Sonic2ScrollHandlerProvider` currently covers only 5 of 10 zones; the remaining 5 (CNZ, HTZ, OOZ, SCZ, WFZ) are handled inline.

**Structural constraints (from analysis):**
- `BackgroundCamera` is shared mutable state — initialized per-zone, modified by handlers, persists across frames. HTZ and WFZ receive it at construction.
- `DynamicHtz` is a Level-aware resource manager for HTZ cloud art streaming. It depends on HTZ handler state but is not itself a scroll handler.
- Screen shake offsets (ARZ, HTZ, MCZ) are accumulated per-frame from individual handlers into ParallaxManager's `currentShakeOffsetX/Y`.

**Current state:** `Sonic2ScrollHandlerProvider` already registers 5 of 10 zone handlers (EHZ, CPZ, ARZ, DEZ, MCZ), but these are **never consulted for S2** — the `loaded` flag in `ParallaxManager.update()` gates S2 onto the inline switch-statement path (`if (!loaded && scrollProvider != null)`), bypassing the provider entirely. The 5 existing provider handlers are dead code for S2.

**Design:**

1. **Complete Sonic2ScrollHandlerProvider** — add the 5 missing handlers (CNZ, HTZ, OOZ, SCZ, WFZ). Move `BackgroundCamera` and `ParallaxTables` ownership into the provider. Expose `BackgroundCamera` via a getter for tornado velocity queries.

2. **Extend ZoneScrollHandler interface** with default methods for features the remaining handlers need:
   ```java
   default short getVscrollFactorFG() { return 0; }
   default int getShakeOffsetX() { return 0; }
   default int getShakeOffsetY() { return 0; }
   default void init(int actId, int cameraX, int cameraY) { /* no-op */ }
   ```
   These defaults mean non-S2 handlers don't need to implement them. MCZ and HTZ handlers override `getVscrollFactorFG()` to return meaningful foreground scroll values.

3. **Eliminate the `loaded` flag dual-path** — currently `ParallaxManager.update()` branches on `loaded` (true = inline S2 switch, false = provider dispatch). After all 10 handlers are in the provider, remove the `loaded` flag and the inline code path entirely. The unified update path becomes:
   ```java
   ZoneScrollHandler handler = scrollProvider.getHandler(zoneId);
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
   This replaces both the 120-line switch statement (lines 427-553) and the existing provider path (lines 398-416) with a single unified path. The `loaded` field, the `load()` method's S2-specific handler instantiation, and the `initZone()` S2 dispatch are all removed.

4. **Remove `hasInlineParallaxHandlers()` flag** from `GameModule` — no longer needed since all games use `ScrollHandlerProvider` exclusively.

5. **Move DynamicHtz into Sonic2ScrollHandlerProvider** — to fully eliminate S2 imports from ParallaxManager. The provider exposes a `updateDynamicArt(Level, Camera)` method that internally coordinates HTZ handler + DynamicHtz. ParallaxManager calls this via the `ScrollHandlerProvider` interface (add a default no-op method). Note: `DynamicHtz.update()` takes a concrete `SwScrlHtz` reference — this is fine within the S2 provider class where the concrete type is known.

6. **Move zone init into the provider** — add `initForZone(int zoneId, int actId, int cameraX, int cameraY)` to `ScrollHandlerProvider` (default no-op). `Sonic2ScrollHandlerProvider` overrides to dispatch zone-specific `handler.init()` calls and `bgCamera.init()`. ParallaxManager's `initZone()` calls `scrollProvider.initForZone(...)` instead of the inline S2 switch.

**Imports removed from ParallaxManager:** All 10 `SwScrl*` classes, `BackgroundCamera`, `ParallaxTables`, `DynamicHtz`. The 11 hardcoded zone constants and the `loaded` flag are also removed.

**ZoneScrollHandler additions:** 4 default methods (vscrollFG, shakeX, shakeY, init). Tornado velocity accessors stay on the concrete `SwScrlScz` class — `Sonic2ScrollHandlerProvider` exposes them via `getTornadoVelocityX()/Y()` methods on the provider interface (default returns 0).

**Files modified:**
- `ZoneScrollHandler.java` — add 4 default methods
- `ScrollHandlerProvider.java` — add `updateDynamicArt()`, `initForZone()`, `getTornadoVelocityX()/Y()` defaults
- `Sonic2ScrollHandlerProvider.java` — add 5 handlers, own BackgroundCamera/ParallaxTables/DynamicHtz, implement `initForZone()` and `updateDynamicArt()`
- `Sonic2GameModule.java` — remove `hasInlineParallaxHandlers()` override
- `GameModule.java` — remove `hasInlineParallaxHandlers()` method
- `ParallaxManager.java` — remove `loaded` flag, inline handlers, zone constants, switch statement, S2-specific `load()`/`initZone()` paths; unify to single provider-based dispatch

**Risk:** Largest task. SCZ has camera-freeze behavior, HTZ has DynamicHtz coupling. Both need careful frame-by-frame parity testing against the existing behavior.

---

### T1-3: Replace swallowed exceptions in S3K code

**Problem:** 28 `catch (Exception ignored) {}` blocks across 17 S3K files silently hide bugs during development.

**Design:** Add `LOG.fine()` to each block. For files without a logger, add one.

```java
// Before:
} catch (Exception ignored) {}

// After:
} catch (Exception e) {
    LOG.fine(() -> "contextDescription: " + e.getMessage());
}
```

`LOG.fine()` (not `warning`) because these are S3K bring-up guards — catch-and-continue is intentional. `fine()` makes failures visible with debug logging enabled without flooding the console.

**17 files, 28 blocks.** No behavioral change.

**Files modified:** All in `com.openggf.game.sonic3k`:
- `scroll/SwScrlAiz.java` (4 blocks)
- `objects/AizPlaneIntroInstance.java` (4)
- `objects/AizFallingLogObjectInstance.java` (3)
- `objects/AizMinibossInstance.java` (2)
- `objects/CutsceneKnucklesAiz1Instance.java` (2)
- `Sonic3kPatternAnimator.java` (2)
- `objects/AizIntroWaveChild.java` (1)
- `objects/AizIntroPlaneChild.java` (1)
- `objects/AizIntroBoosterChild.java` (1)
- `objects/AizIntroEmeraldGlowChild.java` (1)
- `objects/AizIntroPaletteCycler.java` (1)
- `objects/AizMinibossCutsceneInstance.java` (1)
- `objects/AutoSpinObjectInstance.java` (1)
- `objects/CutsceneKnucklesRockChild.java` (1)
- `objects/S3kSignpostInstance.java` (1)
- `objects/S3kSignpostSparkleChild.java` (1)
- `objects/S3kSignpostStubChild.java` (1)

---

### T1-4: Standardize singleton reset

**Problem:** Inconsistent reset APIs. `GameStateManager` uses `resetSession()` (different name convention). `LevelManager.resetState()` is missing ~3 fields. `SpriteManager.resetState()` is already complete.

**Design:**

1. **GameStateManager** — add `resetState()` that delegates to `resetSession()`, then update the teardown call sites to use the new name:
   ```java
   /** Resets all mutable state for test teardown. Delegates to {@link #resetSession()}. */
   public void resetState() { resetSession(); }
   ```
   Update `AbstractLevelInitProfile.java` to call `resetState()` instead of `resetSession()` at both `levelTeardownSteps()` (line 257) and `perTestResetSteps()` (line 286). This makes the teardown call site consistent with all other singletons.

2. **LevelManager.resetState()** — add 3 missing fields after the existing resets:
   ```java
   touchResponseTable = null;
   currentShimmerStyle = 0;
   useShaderBackground = true;
   ```
   The 55+ `pending*` rendering fields are write-before-read (populated every frame) and do not need explicit reset.

3. **SpriteManager** — no changes. Already complete.

**Files modified:**
- `GameStateManager.java` — add `resetState()` method
- `AbstractLevelInitProfile.java` — change `resetSession()` → `resetState()` at 2 call sites
- `LevelManager.java` — add 3 field resets to existing `resetState()`

---

### T2-7: Resolve AbstractPlayableSprite Sonic2AnimationIds import

**Problem:** `AbstractPlayableSprite.java:5` imports `Sonic2AnimationIds`. Single usage at line 2872: `setAnimationId(Sonic2AnimationIds.BUBBLE)` in `replenishAir()`.

**Design:** Cache the resolved animation ID as an instance field, using the same `resolveAnimationId()` pattern applied to `SidekickCpuController` in the prior fix plan.

```java
// New field
private int bubbleAnimId = -1;

// In resolvePhysicsProfile() (called during sprite init):
bubbleAnimId = GameModuleRegistry.getCurrent().resolveAnimationId(CanonicalAnimation.BUBBLE);

// In replenishAir():
if (bubbleAnimId >= 0) {
    setAnimationId(bubbleAnimId);
}
```

Remove `import com.openggf.game.sonic2.constants.Sonic2AnimationIds`.

**Note on -1 guard:** `resolveAnimationId()` returns -1 when the active game doesn't map the canonical animation. S1 has no BUBBLE animation (it uses GET_AIR for a different mechanic), so `bubbleAnimId` will be -1 in S1. The `if (bubbleAnimId >= 0)` guard correctly skips the animation set — S1's `replenishAir()` will not set a bubble animation, which is correct behavior (S1 doesn't have bubble shields).

**Files modified:**
- `AbstractPlayableSprite.java` — add field, resolve in init, replace usage, remove import

---

### T2-9: Make Engine debug fields private

**Problem:** `Engine.debugState` and `Engine.debugOption` are `public static` mutable fields with no synchronization. Accessed from Engine (input thread context) and `LevelDebugRenderer` (render context).

**Design:**

```java
private static volatile DebugState debugState = DebugState.NONE;
private static volatile DebugOption debugOption = DebugOption.A;

public static DebugState getDebugState() { return debugState; }
public static DebugOption getDebugOption() { return debugOption; }
public static void setDebugOption(DebugOption option) { debugOption = option; }
```

`volatile` is sufficient — single-writer, single-reader, no compound operations.

Update `LevelDebugRenderer.java` (3 references at lines 150-157):
- `Engine.debugOption.ordinal()` → `Engine.getDebugOption().ordinal()`
- `Engine.debugOption = DebugOption.A` → `Engine.setDebugOption(DebugOption.A)`

**Files modified:**
- `Engine.java` — change fields to private volatile, add accessors
- `LevelDebugRenderer.java` — use accessors (3 call sites)

---

## Phase B — Follow-up

### T3-10: Virtual pattern ID range validation

**Problem:** 30+ virtual pattern ID ranges spanning `0x00000` to `0xF8000+` are manually managed with no collision detection. A misplaced allocation silently corrupts textures.

**Known ranges (from analysis):**

| Base | Category | Allocated by |
|------|----------|--------------|
| `0x00000` | Level tiles | Sonic{1,2,3k}Level |
| `0x01000` | S2 Special Stage | Sonic2SpecialStageManager |
| `0x03000` | S3K Special Stage | Sonic3kSpecialStageManager |
| `0x10000` | S1 Special Stage | Sonic1SpecialStageManager |
| `0x20000` | Objects | LevelManager → ObjectRenderManager |
| `0x28000` | HUD | LevelManager → HudRenderManager |
| `0x30000` | Water surface | LevelManager → ZoneFeatureProvider |
| `0x38000` | Sidekick DPLC banks | LevelManager |
| `0x39000` | Sidekick tails | LevelManager |
| `0x40000` | Title Card / Results | TitleCardManager / ResultsScreen |
| `0x50000` | S1 Title Card / Level Select | Sonic1TitleCardManager |
| `0x60000` | Title Screen / S3K Results / S1 Credits | Various |
| `0x70000+` | Title Screen sprites / S3K SS Results | Various |
| `0x80000+` | Credits text | Various |
| `0x90000-0xF8000` | S1/S2 title/credits/ending art | Various loaders |

**Design:** Add a `PatternRangeRegistry` to `PatternAtlas`:

```java
public record PatternRange(int base, int size, String category) {}

private final List<PatternRange> registeredRanges = new ArrayList<>();

/**
 * Registers a virtual pattern ID range for collision detection.
 * Logs a warning if the range overlaps an existing registered range.
 * Diagnostic only — does not prevent allocation.
 */
public void registerRange(int base, int size, String category) {
    for (PatternRange existing : registeredRanges) {
        int newEnd = base + size;
        int existingEnd = existing.base() + existing.size();
        if (base < existingEnd && newEnd > existing.base()) {
            LOG.warning("Pattern range collision: " + category
                + " [0x" + Integer.toHexString(base) + "-0x" + Integer.toHexString(newEnd)
                + "] overlaps " + existing.category()
                + " [0x" + Integer.toHexString(existing.base())
                + "-0x" + Integer.toHexString(existingEnd) + "]");
        }
    }
    registeredRanges.add(new PatternRange(base, size, category));
}

/** Clears registered ranges (called on level unload). */
public void clearRanges() {
    registeredRanges.clear();
}
```

Integrate at key allocation sites:
- `LevelManager` after caching object/HUD/water/sidekick patterns
- `SpecialStageManager` variants during setup
- `TitleCardManager` / `ResultsScreen` during initialization

**Files modified:**
- `PatternAtlas.java` — add `PatternRange` record, `registerRange()`, `clearRanges()`
- `LevelManager.java` — register ranges after pattern caching calls
- Optional: special stage and title card managers (lower priority)

---

### T3-12: Document singleton lifecycle

**Problem:** No documentation of initialization order, reset graph, thread-safety assumptions, or the full teardown sequence.

**Design:** Create `docs/architecture/singleton-lifecycle.md` with:

1. **Singleton inventory table** — all singletons with: class name, package, `resetState()` method (if any), thread safety (synchronized/volatile/none), critical dependencies

2. **Initialization order** — the implicit order from `Engine()` constructor → `Engine.init()` → `GameLoop`. Document which singletons are lazy-created on first `getInstance()` vs eagerly captured.

3. **Reset graph** — the full teardown sequence:
   ```
   GameContext.forTesting()
     → capture LevelInitProfile from CURRENT module
     → GameModuleRegistry.reset()
     → profile.levelTeardownSteps():
       1. ResetAudio (AudioManager.resetState())
       2. ResetCrossGameFeatures (CrossGameFeatureProvider.resetState())
       3. Game-specific level event teardown
       4. ResetParallax (ParallaxManager.getInstance().resetState())
       5. ResetLevelManager (LevelManager.getInstance().resetState())
       6. ResetSprites (SpriteManager.getInstance().resetState())
       7. ResetCollision (CollisionSystem.getInstance().resetState())
       8. ResetCamera (Camera.getInstance().resetState())
       9. ResetGraphics (GraphicsManager.getInstance().resetState())
       10. ResetFade (FadeManager.getInstance().resetState())
       11. ResetGameState (GameStateManager.getInstance().resetState())
       12. ResetTimers (TimerManager.getInstance().resetState())
       13. ResetWater (WaterSystem.getInstance().resetState())
     → profile.postTeardownFixups()
   ```

4. **Thread-safety contract** — which singletons use `synchronized getInstance()`, which assume single-thread game loop access, and the `volatile` fields in Engine.

5. **HeadlessTestRunner setup checklist** — the required manual reset sequence with ordering rationale.

**Files created:**
- `docs/architecture/singleton-lifecycle.md`

**No code changes.**

---

## Task Dependencies

```
T1-1 (art dispatching)     — independent
T1-2 (parallax handlers)   — independent
T1-3 (swallowed exceptions) — independent
T1-4 (singleton reset)     — independent
T2-7 (animation import)    — independent
T2-9 (debug fields)        — independent
T3-10 (pattern validation) — independent, after Phase A
T3-12 (lifecycle docs)     — after T1-4 (documents the standardized reset)
```

All Phase A tasks are independent and can be parallelized. Phase B tasks depend on Phase A completion for accurate documentation and integration.

## Verification

After all Phase A tasks:
- `mvn compile -q` passes
- `mvn test` passes (all existing tests)
- `grep -r "import com.openggf.game.sonic" src/main/java/com/openggf/level/ParallaxManager.java` returns no matches
- `grep "import com.openggf.game.sonic.*ObjectArtProvider" src/main/java/com/openggf/level/LevelManager.java` returns no matches
- `grep "import com.openggf.game.sonic2.constants.Sonic2AnimationIds" src/main/java/com/openggf/sprites/playable/AbstractPlayableSprite.java` returns no matches
- `grep "catch (Exception ignored)" src/main/java/com/openggf/game/sonic3k/ -r` returns no matches
