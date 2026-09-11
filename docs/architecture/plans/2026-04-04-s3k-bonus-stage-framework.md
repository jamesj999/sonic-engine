# S3K Bonus Stage Framework & Gumball Machine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the shared bonus stage framework (entry/exit lifecycle, state persistence, GameMode integration) and implement the Gumball Machine as the first concrete stage.

**Architecture:** Thin coordinator pattern — `GameMode.BONUS_STAGE` runs the same level pipeline as `LEVEL` mode, with `AbstractBonusStageCoordinator` managing entry/exit state and ring persistence. Objects communicate completion via `GameServices.bonusStage()`. No results screen — ROM fades out and returns directly.

**Tech Stack:** Java 21, existing engine subsystems (LevelManager, ObjectManager, LevelTransitionCoordinator, GameServices/GameRuntime, FadeManager)

**Spec:** `docs/architecture/designs/2026-04-04-s3k-bonus-stage-framework-design.md`

---

### Task 1: Redesign BonusStageProvider Interface

Drop `MiniGameProvider` inheritance and redesign as a coordinator interface. Update the no-op implementation.

**Files:**
- Modify: `src/main/java/com/openggf/game/BonusStageProvider.java`
- Modify: `src/main/java/com/openggf/game/NoOpBonusStageProvider.java`
- Create: `src/main/java/com/openggf/game/BonusStageState.java`

- [ ] **Step 1: Create BonusStageState record**

```java
// src/main/java/com/openggf/game/BonusStageState.java
package com.openggf.game;

/**
 * Immutable snapshot of game state saved before entering a bonus stage.
 * Restored on exit so the player resumes at the checkpoint with correct
 * event routine state, camera position, and collision path.
 * <p>
 * Mirrors ROM Saved_* variables plus the BigRingReturnState pattern.
 */
public record BonusStageState(
        int savedZoneAndAct,
        int savedApparentZoneAndAct,
        int savedRingCount,
        int savedExtraLifeFlags,
        int savedLastStarPostHit,
        int savedStatusSecondary,
        int dynamicResizeRoutineFg,
        int dynamicResizeRoutineBg,
        int playerX,
        int playerY,
        int cameraX,
        int cameraY,
        byte topSolidBit,
        byte lrbSolidBit,
        int cameraMaxY
) {}
```

- [ ] **Step 2: Rewrite BonusStageProvider interface**

Replace the entire file content:

```java
// src/main/java/com/openggf/game/BonusStageProvider.java
package com.openggf.game;

/**
 * Coordinator interface for bonus stage lifecycle.
 * <p>
 * Unlike special stages (which own their own rendering), bonus stages use
 * the normal level pipeline. This interface manages entry/exit state,
 * not frame updates or rendering.
 * <p>
 * Accessed via {@link GameServices#bonusStage()}.
 */
public interface BonusStageProvider {

    boolean hasBonusStages();

    BonusStageType selectBonusStage(int ringCount);

    /** Called by GameLoop on entry after fade. Stores saved state for later restoration. */
    void onEnter(BonusStageType type, BonusStageState savedState);

    /** Called by GameLoop on exit after fade. */
    void onExit();

    /** Called each frame during BONUS_STAGE mode, after level frame steps. */
    void onFrameUpdate();

    /** Returns true when the exit trigger object has signalled completion. */
    boolean isStageComplete();

    /** Called by exit trigger objects to signal the stage should end. */
    void requestExit();

    BonusStageRewards getRewards();

    /** Returns the S3K zone ID for the given bonus stage type. */
    int getZoneId(BonusStageType type);

    /** Returns the SMPS music ID for the given bonus stage type. */
    int getMusicId(BonusStageType type);

    /** Returns the saved state snapshot captured on entry. */
    BonusStageState getSavedState();

    record BonusStageRewards(
            int rings, int lives,
            boolean shield, boolean fireShield,
            boolean lightningShield, boolean bubbleShield
    ) {
        public static BonusStageRewards none() {
            return new BonusStageRewards(0, 0, false, false, false, false);
        }
    }
}
```

- [ ] **Step 3: Rewrite NoOpBonusStageProvider**

Replace the entire file content:

```java
// src/main/java/com/openggf/game/NoOpBonusStageProvider.java
package com.openggf.game;

/**
 * No-op implementation of {@link BonusStageProvider} for games without bonus stages.
 */
public final class NoOpBonusStageProvider implements BonusStageProvider {
    public static final NoOpBonusStageProvider INSTANCE = new NoOpBonusStageProvider();

    private NoOpBonusStageProvider() {}

    @Override public boolean hasBonusStages() { return false; }
    @Override public BonusStageType selectBonusStage(int ringCount) { return BonusStageType.NONE; }
    @Override public void onEnter(BonusStageType type, BonusStageState savedState) {}
    @Override public void onExit() {}
    @Override public void onFrameUpdate() {}
    @Override public boolean isStageComplete() { return false; }
    @Override public void requestExit() {}
    @Override public BonusStageRewards getRewards() { return BonusStageRewards.none(); }
    @Override public int getZoneId(BonusStageType type) { return -1; }
    @Override public int getMusicId(BonusStageType type) { return -1; }
    @Override public BonusStageState getSavedState() { return null; }
}
```

- [ ] **Step 4: Build and verify compilation**

Run: `mvn compile -q`
Expected: BUILD SUCCESS — no compilation errors from the interface change.

- [ ] **Step 5: Fix any compilation errors from callers of the old interface**

Search for any code that calls the removed `MiniGameProvider` methods (`initialize()`, `update()`, `draw()`, `handleInput()`, `isFinished()`, `reset()`, `isInitialized()`, `initializeBonusStage()`) on `BonusStageProvider` and update or remove. The `GameModule.getBonusStageProvider()` default method return type is unchanged so it should still compile.

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Run tests**

Run: `mvn test -q`
Expected: All existing tests pass (the interface change is backward-compatible via NoOp).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/BonusStageProvider.java \
       src/main/java/com/openggf/game/NoOpBonusStageProvider.java \
       src/main/java/com/openggf/game/BonusStageState.java
git commit -m "refactor: redesign BonusStageProvider as coordinator interface

Drop MiniGameProvider inheritance. Bonus stages use the level pipeline,
so the provider doesn't own rendering or frame updates. Add BonusStageState
record for entry/exit state snapshots. Add requestExit() for object
communication."
```

---

### Task 2: Add GameMode.BONUS_STAGE and LevelTransitionCoordinator Support

Wire up the request/consume pattern for bonus stage entry and add the new game mode.

**Files:**
- Modify: `src/main/java/com/openggf/game/GameMode.java`
- Modify: `src/main/java/com/openggf/level/LevelTransitionCoordinator.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java` (delegate methods)

- [ ] **Step 1: Add BONUS_STAGE to GameMode enum**

In `src/main/java/com/openggf/game/GameMode.java`, add after `ENDING_CUTSCENE`:

```java
    /** S3K bonus stage (Gumball, Pachinko, Slots) — uses level pipeline with coordinator lifecycle */
    BONUS_STAGE
```

- [ ] **Step 2: Add bonus stage request fields to LevelTransitionCoordinator**

In `src/main/java/com/openggf/level/LevelTransitionCoordinator.java`, add a new section after the special stage section (after `bigRingReturn` field declarations, before the title card section):

```java
    // ── Bonus stage ───────────────────────────────────────────────────
    private BonusStageType bonusStageRequested;
```

Add the import at the top of the file:

```java
import com.openggf.game.BonusStageType;
```

- [ ] **Step 3: Add bonus stage request/consume methods**

In `LevelTransitionCoordinator.java`, add a new section after the big ring return section:

```java
    // ================================================================
    //  Bonus stage requests
    // ================================================================

    /**
     * Request entry to a bonus stage from a star post bonus star.
     * Called by Sonic3kStarPostBonusStarChild on player touch.
     *
     * @param type the bonus stage type to enter
     */
    public void requestBonusStageEntry(BonusStageType type) {
        this.bonusStageRequested = type;
    }

    /**
     * Consumes and clears the bonus stage request.
     *
     * @return the requested bonus stage type, or null if none requested
     */
    public BonusStageType consumeBonusStageRequest() {
        BonusStageType requested = bonusStageRequested;
        bonusStageRequested = null;
        return requested;
    }
```

- [ ] **Step 4: Clear bonus stage request in resetState()**

In `LevelTransitionCoordinator.resetState()`, add:

```java
        bonusStageRequested = null;
```

- [ ] **Step 5: Add delegate methods on LevelManager**

In `src/main/java/com/openggf/level/LevelManager.java`, find the existing special stage delegate methods (near `consumeSpecialStageRequest()`) and add nearby:

```java
    /** @see LevelTransitionCoordinator#requestBonusStageEntry(BonusStageType) */
    public void requestBonusStageEntry(BonusStageType type) { transitions.requestBonusStageEntry(type); }

    /** @see LevelTransitionCoordinator#consumeBonusStageRequest() */
    public BonusStageType consumeBonusStageRequest() { return transitions.consumeBonusStageRequest(); }
```

Add the import for `BonusStageType` if not already present.

- [ ] **Step 6: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 7: Run tests**

Run: `mvn test -q`
Expected: All tests pass.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/openggf/game/GameMode.java \
       src/main/java/com/openggf/level/LevelTransitionCoordinator.java \
       src/main/java/com/openggf/level/LevelManager.java
git commit -m "feat: add GameMode.BONUS_STAGE and bonus stage request plumbing

Add BONUS_STAGE to GameMode enum. Add requestBonusStageEntry/consumeBonusStageRequest
to LevelTransitionCoordinator with LevelManager delegates."
```

---

### Task 3: Add GameServices.bonusStage() Accessor

Expose the active bonus stage provider through the service locator so objects (exit trigger) can signal completion.

**Files:**
- Modify: `src/main/java/com/openggf/game/GameServices.java`
- Modify: `src/main/java/com/openggf/game/GameRuntime.java`
- Modify: `src/main/java/com/openggf/game/RuntimeManager.java`

- [ ] **Step 1: Add bonusStageProvider field to GameRuntime**

In `src/main/java/com/openggf/game/GameRuntime.java`, add a mutable field (unlike other managers, this is set/cleared during transitions):

```java
    private volatile BonusStageProvider activeBonusStageProvider = NoOpBonusStageProvider.INSTANCE;
```

Add getter and setter:

```java
    public BonusStageProvider getActiveBonusStageProvider() { return activeBonusStageProvider; }

    public void setActiveBonusStageProvider(BonusStageProvider provider) {
        this.activeBonusStageProvider = provider != null ? provider : NoOpBonusStageProvider.INSTANCE;
    }
```

Add import for `NoOpBonusStageProvider`.

- [ ] **Step 2: Add GameServices.bonusStage() accessor**

In `src/main/java/com/openggf/game/GameServices.java`, add in the runtime-owned section:

```java
    /**
     * Returns the active bonus stage provider. Returns {@link NoOpBonusStageProvider}
     * when not in a bonus stage. Objects call this to signal stage completion
     * via {@link BonusStageProvider#requestExit()}.
     */
    public static BonusStageProvider bonusStage() {
        return requireRuntime("bonusStage").getActiveBonusStageProvider();
    }
```

- [ ] **Step 3: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/GameServices.java \
       src/main/java/com/openggf/game/GameRuntime.java
git commit -m "feat: add GameServices.bonusStage() accessor

Exposes active BonusStageProvider through GameServices so exit trigger
objects can signal stage completion. Returns NoOp when not in a bonus stage."
```

---

### Task 4: Implement AbstractBonusStageCoordinator and Sonic3kBonusStageCoordinator

Build the shared base class and the S3K concrete implementation.

**Files:**
- Create: `src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java`
- Create: `src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`

- [ ] **Step 1: Create AbstractBonusStageCoordinator**

```java
// src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java
package com.openggf.game;

import java.util.logging.Logger;

/**
 * Shared base class for bonus stage lifecycle coordination.
 * Manages entry/exit state, ring persistence, and object communication.
 * <p>
 * Subclasses provide game-specific zone/music mapping.
 * Methods are non-final to allow overrides (e.g., Slots player sprite swap).
 */
public abstract class AbstractBonusStageCoordinator implements BonusStageProvider {

    private static final Logger LOGGER = Logger.getLogger(AbstractBonusStageCoordinator.class.getName());

    private BonusStageState savedState;
    private BonusStageType activeType = BonusStageType.NONE;
    private boolean exitRequested;
    private int ringsCollected;
    private int livesAwarded;

    @Override
    public boolean hasBonusStages() {
        return true;
    }

    @Override
    public void onEnter(BonusStageType type, BonusStageState savedState) {
        this.savedState = savedState;
        this.activeType = type;
        this.exitRequested = false;
        this.ringsCollected = 0;
        this.livesAwarded = 0;

        LOGGER.info("Entering bonus stage: " + type + " (zone 0x"
                + Integer.toHexString(getZoneId(type)) + ")");
    }

    @Override
    public void onExit() {
        LOGGER.info("Exiting bonus stage: " + activeType
                + " (rings collected: " + ringsCollected + ")");
        activeType = BonusStageType.NONE;
        exitRequested = false;
    }

    @Override
    public void onFrameUpdate() {
        // Default: no per-frame work beyond exit flag checking.
        // Subclasses may override for stage-specific logic.
    }

    @Override
    public boolean isStageComplete() {
        return exitRequested;
    }

    @Override
    public void requestExit() {
        exitRequested = true;
    }

    @Override
    public BonusStageRewards getRewards() {
        return new BonusStageRewards(ringsCollected, livesAwarded,
                false, false, false, false);
    }

    @Override
    public BonusStageState getSavedState() {
        return savedState;
    }

    public BonusStageType getActiveType() {
        return activeType;
    }

    /** Accumulate rings during the bonus stage. Called by gumball item objects. */
    public void addRings(int count) {
        ringsCollected += count;
    }

    /** Accumulate lives during the bonus stage. */
    public void addLife() {
        livesAwarded++;
    }
}
```

- [ ] **Step 2: Create Sonic3kBonusStageCoordinator**

```java
// src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java
package com.openggf.game.sonic3k;

import com.openggf.game.AbstractBonusStageCoordinator;
import com.openggf.game.BonusStageType;

/**
 * S3K-specific bonus stage coordinator.
 * Provides zone ID and music ID mapping for Gumball, Pachinko, and Slots.
 * <p>
 * Ring-based selection formula: {@code remainder = ((rings - 20) / 15) % 3}
 * <ul>
 *   <li>0 → GUMBALL (zone $1300, music $1E)</li>
 *   <li>1 → GLOWING_SPHERE (zone $1400, music $1F)</li>
 *   <li>2 → SLOT_MACHINE (zone $1500, music $20)</li>
 * </ul>
 */
public class Sonic3kBonusStageCoordinator extends AbstractBonusStageCoordinator {

    // S3K bonus stage zone IDs (zone byte << 8, act=0)
    private static final int ZONE_GUMBALL       = 0x1300;
    private static final int ZONE_PACHINKO      = 0x1400;
    private static final int ZONE_SLOTS         = 0x1500;

    // S3K SMPS music IDs
    private static final int MUS_GUMBALL        = 0x1E;
    private static final int MUS_PACHINKO       = 0x1F;
    private static final int MUS_SLOTS          = 0x20;

    private static final int RING_THRESHOLD     = 20;
    private static final int RING_DIVISOR       = 15;
    private static final int STAGE_COUNT        = 3;

    @Override
    public BonusStageType selectBonusStage(int ringCount) {
        if (ringCount < RING_THRESHOLD) {
            return BonusStageType.NONE;
        }
        int remainder = ((ringCount - RING_THRESHOLD) / RING_DIVISOR) % STAGE_COUNT;
        return switch (remainder) {
            case 0 -> BonusStageType.GUMBALL;
            case 1 -> BonusStageType.GLOWING_SPHERE;
            case 2 -> BonusStageType.SLOT_MACHINE;
            default -> BonusStageType.NONE;
        };
    }

    @Override
    public int getZoneId(BonusStageType type) {
        return switch (type) {
            case GUMBALL -> ZONE_GUMBALL;
            case GLOWING_SPHERE -> ZONE_PACHINKO;
            case SLOT_MACHINE -> ZONE_SLOTS;
            default -> -1;
        };
    }

    @Override
    public int getMusicId(BonusStageType type) {
        return switch (type) {
            case GUMBALL -> MUS_GUMBALL;
            case GLOWING_SPHERE -> MUS_PACHINKO;
            case SLOT_MACHINE -> MUS_SLOTS;
            default -> -1;
        };
    }
}
```

- [ ] **Step 3: Override getBonusStageProvider() in Sonic3kGameModule**

In `src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java`, add:

```java
    private final Sonic3kBonusStageCoordinator bonusStageCoordinator = new Sonic3kBonusStageCoordinator();

    @Override
    public BonusStageProvider getBonusStageProvider() {
        return bonusStageCoordinator;
    }
```

Add import for `BonusStageProvider`.

- [ ] **Step 4: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 5: Run tests**

Run: `mvn test -q`
Expected: All tests pass.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/AbstractBonusStageCoordinator.java \
       src/main/java/com/openggf/game/sonic3k/Sonic3kBonusStageCoordinator.java \
       src/main/java/com/openggf/game/sonic3k/Sonic3kGameModule.java
git commit -m "feat: implement AbstractBonusStageCoordinator and S3K coordinator

Shared base manages entry/exit state, ring persistence, exit flag.
S3K coordinator provides zone/music mapping and ring-based selection formula."
```

---

### Task 5: Wire Bonus Star Child to Request Bonus Stage Entry

Replace the NYI log in the star post bonus star child with actual entry request.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kStarPostBonusStarChild.java`

- [ ] **Step 1: Read the full file to understand the current structure**

Read `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kStarPostBonusStarChild.java` in full.

- [ ] **Step 2: Replace NYI log with bonus stage entry request**

Find the NYI block (lines ~138-148) and replace:

```java
            // ROM: S3K lampposts enter bonus stages, not special stages.
            // loc_2D47E uses Saved_ring_count with same formula as star art selection.
            // Bonus stages (Gumball/Glowing Spheres/Slot Machine) are NYI.
            LOGGER.info("Player touched S3K bonus star - " + variant.bonusStageType
                    + " bonus stage entry NYI (variant=" + variant + ", rings="
                    + player.getRingCount() + ")");
            if (parentStarPost != null) {
                parentStarPost.markUsedForSpecialStage();
            }
            setDestroyed(true);
```

with:

```java
            // ROM: loc_2D47E — S3K lampposts enter bonus stages via Restart_level_flag.
            // Engine: request bonus stage entry through LevelTransitionCoordinator.
            LOGGER.info("Requesting bonus stage entry: " + variant.bonusStageType
                    + " (variant=" + variant + ", rings=" + player.getRingCount() + ")");
            services().levelManager().requestBonusStageEntry(variant.bonusStageType);
            if (parentStarPost != null) {
                parentStarPost.markUsedForSpecialStage();
            }
            setDestroyed(true);
```

Add import for `com.openggf.game.BonusStageType` if not already present (it should be available via the `BonusStarVariant` reference).

- [ ] **Step 3: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/Sonic3kStarPostBonusStarChild.java
git commit -m "feat: wire bonus star child to request bonus stage entry

Replace NYI log with actual LevelTransitionCoordinator.requestBonusStageEntry()
call. Bonus star touch now triggers the full entry flow."
```

---

### Task 6: Integrate BONUS_STAGE Mode into GameLoop

Add bonus stage entry/exit transitions and BONUS_STAGE frame processing to GameLoop.

**Files:**
- Modify: `src/main/java/com/openggf/GameLoop.java`

- [ ] **Step 1: Read GameLoop entry/special-stage sections**

Read `src/main/java/com/openggf/GameLoop.java` lines 446-524 (LEVEL mode step), lines 761-828 (enterSpecialStage), and lines 837-860 (doEnterSpecialStage) to understand the patterns.

- [ ] **Step 2: Add bonus stage transition state fields**

Near the existing `specialStageTransitionPending` field, add:

```java
    private boolean bonusStageTransitionPending;
    private BonusStageProvider activeBonusStageProvider;
```

Add imports:

```java
import com.openggf.game.BonusStageProvider;
import com.openggf.game.BonusStageState;
import com.openggf.game.BonusStageType;
import com.openggf.game.AbstractBonusStageCoordinator;
```

- [ ] **Step 3: Add bonus stage request check in LEVEL mode step**

In the LEVEL mode step (after the special stage request check at line ~521-523), add:

```java
                // Check if a bonus star requested a bonus stage
                BonusStageType bonusRequest = levelManager.consumeBonusStageRequest();
                if (bonusRequest != null) {
                    enterBonusStage(bonusRequest);
                }
```

- [ ] **Step 4: Add BONUS_STAGE mode processing in step()**

After the `LEVEL` mode block (after line ~524), add a new `else if` for bonus stage before the debug key section:

```java
        } else if (currentGameMode == GameMode.BONUS_STAGE) {
            // Bonus stage runs the same level frame steps as LEVEL mode
            boolean freezeForBonusExit = bonusStageTransitionPending;
            if (!freezeForBonusExit) {
                LevelFrameStep.execute(levelManager, camera, () -> {
                    spriteManager.update(inputHandler);
                }, (name, step) -> {
                    profiler.beginSection(name);
                    step.run();
                    profiler.endSection(name);
                });

                // Check bonus stage completion
                if (activeBonusStageProvider != null && activeBonusStageProvider.isStageComplete()) {
                    exitBonusStage();
                }
            }
        }
```

Note: This block should be inserted as a peer to the existing `if (currentGameMode == GameMode.LEVEL)` block. The exact insertion point is after the closing `}` of the LEVEL block but before the debug key handling that follows.

- [ ] **Step 5: Implement enterBonusStage()**

Add this method near `enterSpecialStage()`:

```java
    /**
     * Enters a bonus stage from level mode.
     * Captures current state, fades to white, loads the bonus zone.
     */
    private void enterBonusStage(BonusStageType type) {
        if (currentGameMode != GameMode.LEVEL) {
            return;
        }

        BonusStageProvider provider = GameModuleRegistry.getCurrent().getBonusStageProvider();
        if (!provider.hasBonusStages()) {
            LOGGER.fine("Current game module has no bonus stages; ignoring entry request");
            return;
        }

        // Capture state snapshot
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (mainCode == null) mainCode = "sonic";
        var sprite = spriteManager.getSprite(mainCode);

        int playerX = 0, playerY = 0;
        byte topSolidBit = 0, lrbSolidBit = 0;
        if (sprite instanceof AbstractPlayableSprite playable) {
            playerX = playable.getCentreX();
            playerY = playable.getCentreY();
            topSolidBit = playable.getTopSolidBit();
            lrbSolidBit = playable.getLrbSolidBit();
        }

        LevelEventProvider eventProvider = GameModuleRegistry.getCurrent().getLevelEventProvider();
        int resizeFg = 0, resizeBg = 0;
        if (eventProvider instanceof AbstractLevelEventManager eventMgr) {
            resizeFg = eventMgr.getEventRoutineFg();
            resizeBg = eventMgr.getEventRoutineBg();
        }

        BonusStageState savedState = new BonusStageState(
                levelManager.getCurrentZoneAndAct(),
                levelManager.getApparentZoneAndAct(),
                gameState.getRingCount(),
                gameState.getExtraLifeFlags(),
                levelManager.getLastStarPostHit(),
                0, // statusSecondary — populate if needed
                resizeFg, resizeBg,
                playerX, playerY,
                camera.getX(), camera.getY(),
                topSolidBit, lrbSolidBit,
                camera.getMaxY()
        );

        // Fade out music
        AudioManager.getInstance().fadeOutMusic();

        bonusStageTransitionPending = true;
        fadeManager.startFadeToWhite(() -> {
            doEnterBonusStage(provider, type, savedState);
        });

        LOGGER.info("Starting fade-to-white for Bonus Stage " + type);
    }
```

- [ ] **Step 6: Implement doEnterBonusStage()**

```java
    /**
     * Actually enters the bonus stage after the fade-to-white completes.
     */
    private void doEnterBonusStage(BonusStageProvider provider, BonusStageType type,
                                    BonusStageState savedState) {
        bonusStageTransitionPending = false;
        activeBonusStageProvider = provider;

        // Register on GameRuntime so objects can access via GameServices.bonusStage()
        GameRuntime runtime = RuntimeManager.getCurrent();
        if (runtime != null) {
            runtime.setActiveBonusStageProvider(provider);
        }

        provider.onEnter(type, savedState);

        // Load the bonus zone through the normal level loading path
        int zoneId = provider.getZoneId(type);
        int zone = (zoneId >> 8) & 0xFF;
        int act = zoneId & 0xFF;

        try {
            levelManager.loadZoneAndAct(zone, act);
            // Consume title card — bonus stages don't show one
            levelManager.consumeTitleCardRequest();
        } catch (IOException e) {
            LOGGER.severe("Failed to load bonus stage zone: " + e.getMessage());
            // Abort — restore original zone
            provider.onExit();
            activeBonusStageProvider = null;
            if (runtime != null) {
                runtime.setActiveBonusStageProvider(null);
            }
            currentGameMode = GameMode.LEVEL;
            return;
        }

        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.BONUS_STAGE;

        // Play bonus stage music
        int musicId = provider.getMusicId(type);
        if (musicId >= 0) {
            AudioManager.getInstance().playMusic(musicId);
        }

        fadeManager.startFadeFromWhite(null);

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Entered Bonus Stage " + type + " (zone 0x" + Integer.toHexString(zoneId) + ")");
    }
```

Add import for `java.io.IOException` and `com.openggf.game.RuntimeManager` if not already present.

- [ ] **Step 7: Implement exitBonusStage()**

```java
    /**
     * Exits the current bonus stage. Fades to white, restores previous zone.
     */
    private void exitBonusStage() {
        if (currentGameMode != GameMode.BONUS_STAGE || activeBonusStageProvider == null) {
            return;
        }

        BonusStageProvider provider = activeBonusStageProvider;
        BonusStageState savedState = provider.getSavedState();

        // Persist ring gains: current rings carry back
        // (ROM: move.w (Ring_count).w,(Saved_ring_count).w at exit)

        AudioManager.getInstance().fadeOutMusic();

        bonusStageTransitionPending = true;
        fadeManager.startFadeToWhite(() -> {
            doExitBonusStage(provider, savedState);
        });

        LOGGER.info("Starting fade-to-white to exit Bonus Stage");
    }

    /**
     * Actually exits the bonus stage after the fade-to-white completes.
     */
    private void doExitBonusStage(BonusStageProvider provider, BonusStageState savedState) {
        bonusStageTransitionPending = false;

        provider.onExit();
        activeBonusStageProvider = null;

        // Clear from GameRuntime
        GameRuntime runtime = RuntimeManager.getCurrent();
        if (runtime != null) {
            runtime.setActiveBonusStageProvider(null);
        }

        if (savedState == null) {
            LOGGER.warning("No saved state for bonus stage exit — returning to zone 0,0");
            try {
                levelManager.loadZoneAndAct(0, 0);
            } catch (IOException e) {
                throw new RuntimeException("Failed to load fallback level", e);
            }
            currentGameMode = GameMode.LEVEL;
            fadeManager.startFadeFromWhite(null);
            return;
        }

        // Restore previous zone
        int zone = (savedState.savedZoneAndAct() >> 8) & 0xFF;
        int act = savedState.savedZoneAndAct() & 0xFF;

        try {
            levelManager.loadZoneAndAct(zone, act);
            levelManager.consumeTitleCardRequest(); // No title card on return
        } catch (IOException e) {
            throw new RuntimeException("Failed to reload level after bonus stage", e);
        }

        // Restore event routine state (prevents camera lock replay)
        LevelEventProvider eventProvider = GameModuleRegistry.getCurrent().getLevelEventProvider();
        if (eventProvider instanceof com.openggf.game.sonic3k.Sonic3kLevelEventManager s3kEvents) {
            s3kEvents.setDynamicResizeRoutine(savedState.dynamicResizeRoutineFg());
        }

        // Restore player position and collision path
        String mainCode = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (mainCode == null) mainCode = "sonic";
        var sprite = spriteManager.getSprite(mainCode);
        if (sprite instanceof AbstractPlayableSprite playable) {
            playable.setCentreX((short) savedState.playerX());
            playable.setCentreY((short) savedState.playerY());
            playable.setTopSolidBit(savedState.topSolidBit());
            playable.setLrbSolidBit(savedState.lrbSolidBit());
            playable.setXSpeed((short) 0);
            playable.setYSpeed((short) 0);
            playable.setGSpeed((short) 0);
        }

        // Restore camera
        camera.setX((short) savedState.cameraX());
        camera.setY((short) savedState.cameraY());
        camera.setMaxY((short) savedState.cameraMaxY());
        camera.updatePosition(true);

        // Restore ring count (with any bonus gains)
        gameState.setRingCount(savedState.savedRingCount());

        GameMode oldMode = currentGameMode;
        currentGameMode = GameMode.LEVEL;

        fadeManager.startFadeFromWhite(null);

        if (gameModeChangeListener != null) {
            gameModeChangeListener.onGameModeChanged(oldMode, currentGameMode);
        }

        LOGGER.info("Returned from Bonus Stage to zone " + zone + " act " + act);
    }
```

- [ ] **Step 8: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS. There may be compilation issues from methods like `getCurrentZoneAndAct()`, `getApparentZoneAndAct()`, `getLastStarPostHit()`, `getExtraLifeFlags()` that may not exist on `LevelManager`/`GameStateManager` with those exact names. Check compilation errors and adjust method names to match the actual API. Read the relevant source files to find the correct method names.

- [ ] **Step 9: Run tests**

Run: `mvn test -q`
Expected: All tests pass.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/openggf/GameLoop.java
git commit -m "feat: integrate BONUS_STAGE mode into GameLoop

Add enterBonusStage/exitBonusStage transition flow parallel to special stages.
BONUS_STAGE mode runs the same level frame steps as LEVEL mode.
State snapshot includes event routine counters for correct camera lock restore."
```

---

### Task 7: Handle BONUS_STAGE in Engine.java Display Dispatch

Ensure `BONUS_STAGE` mode renders using the normal level pipeline.

**Files:**
- Modify: `src/main/java/com/openggf/Engine.java`

- [ ] **Step 1: Read Engine.java draw dispatch**

Read `src/main/java/com/openggf/Engine.java` lines 640-920 to understand the full GameMode dispatch chain in the draw method.

- [ ] **Step 2: Update clear color dispatch**

In the clear color section (around line 643), the `BONUS_STAGE` mode should fall through to the default (black) clear color. No change needed — the `else` chain already handles non-special modes with black.

Verify: if there's a `LEVEL`-specific clear color branch, `BONUS_STAGE` must be included. If the default is black, no change needed.

- [ ] **Step 3: Update draw dispatch**

The draw method at line ~899 has `} else if (!debugViewEnabled) {` as the fallthrough for LEVEL mode. This works because LEVEL is not explicitly checked — it's the default case.

However, `BONUS_STAGE` must NOT match any of the preceding explicit checks (SPECIAL_STAGE, SPECIAL_STAGE_RESULTS, TITLE_SCREEN, etc.). Since `BONUS_STAGE` is not in any of those checks, it will correctly fall through to the same rendering path as LEVEL.

Verify: confirm no `if (currentGameMode != GameMode.LEVEL)` guard exists anywhere in the render path that would exclude BONUS_STAGE. Search for `GameMode.LEVEL` in Engine.java.

- [ ] **Step 4: Update any GameMode.LEVEL exclusion guards**

Search Engine.java for `!= GameMode.LEVEL` and `== GameMode.LEVEL` checks. If any exist in the render path, add `&& currentGameMode != GameMode.BONUS_STAGE` or change to a helper method `isLevelLikeMode()`. The overlay check at line ~722 (`getCurrentGameMode() != GameMode.SPECIAL_STAGE`) should be fine.

If no LEVEL-specific guards exist, no changes needed.

- [ ] **Step 5: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 6: Commit (if changes were made)**

```bash
git add src/main/java/com/openggf/Engine.java
git commit -m "fix: ensure BONUS_STAGE mode renders through level pipeline in Engine

Add BONUS_STAGE to any GameMode.LEVEL guards in display dispatch."
```

---

### Task 8: Verify ROM Addresses for Gumball Stage Data

Use RomOffsetFinder to confirm all art, mapping, layout, and palette addresses before implementing objects.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`

- [ ] **Step 1: Search for gumball-related labels**

Run: `mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search Gumball" -q`

Note the labels found and their offsets.

- [ ] **Step 2: Search for BonusStage art label**

Run: `mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search BonusStage" -q`

- [ ] **Step 3: Search for bonus palette**

Run: `mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search Pal_Gumball" -q`

- [ ] **Step 4: Verify key addresses**

For each label found, verify the offset:

Run: `mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k verify <label>" -q`

- [ ] **Step 5: Search for Map_GumballBonus mapping address**

Run: `mvn exec:java "-Dexec.mainClass=com.openggf.tools.disasm.RomOffsetFinder" "-Dexec.args=--game s3k search Map_GumballBonus" -q`

- [ ] **Step 6: Add verified constants to Sonic3kConstants.java**

In `src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java`, add a new section:

```java
    // ── Gumball Bonus Stage ───────────────────────────────────────────
    public static final int GUMBALL_ART_NEM_ADDR = 0x______;       // ArtNem_BonusStage
    public static final int GUMBALL_MAP_ADDR = 0x______;            // Map_GumballBonus
    public static final int GUMBALL_PALETTE_ADDR = 0x______;        // Pal_Gumball_Special
```

Fill in verified addresses from the RomOffsetFinder output.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/constants/Sonic3kConstants.java
git commit -m "feat: add verified ROM addresses for Gumball bonus stage

Addresses verified via RomOffsetFinder: art, mappings, palette."
```

---

### Task 9: Implement GumballTriangleBumperObjectInstance

The simplest gumball object — fixed-velocity bounce with no complex state.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/GumballTriangleBumperObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`

- [ ] **Step 1: Create the triangle bumper object**

```java
// src/main/java/com/openggf/game/sonic3k/objects/GumballTriangleBumperObjectInstance.java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

/**
 * Gumball Machine triangle bumper (ROM: Obj_GumballTriangleBumper, sonic3k.asm:127634).
 * <p>
 * Fixed-velocity bounce: X=±$300, Y=-$600. Direction based on h-flip render flag.
 * Placed via object layout data in the Gumball bonus stage.
 * <p>
 * Collision box: 4px wide, 16px tall. 0x0F frame cooldown between bounces.
 */
public class GumballTriangleBumperObjectInstance extends AbstractObjectInstance {

    private static final Logger LOGGER = Logger.getLogger(GumballTriangleBumperObjectInstance.class.getName());

    // ROM: sub_60F94 velocity constants
    private static final int BOUNCE_X_SPEED = 0x300;   // ±$300
    private static final int BOUNCE_Y_SPEED = -0x600;  // -$600 (upward)
    private static final int COOLDOWN_FRAMES = 0x0F;

    private int cooldownTimer;

    public GumballTriangleBumperObjectInstance(ObjectSpawn spawn) {
        super(spawn);
    }

    @Override
    public void update() {
        if (cooldownTimer > 0) {
            cooldownTimer--;
        }

        // Check collision with player
        PlayableEntity player = findNearestPlayer();
        if (player == null || cooldownTimer > 0) {
            return;
        }

        if (isPlayerInBounceRange(player)) {
            applyBounce(player);
            cooldownTimer = COOLDOWN_FRAMES;
        }
    }

    private boolean isPlayerInBounceRange(PlayableEntity player) {
        int dx = player.getCentreX() - getCentreX();
        int dy = player.getCentreY() - getCentreY();
        // Collision box: 4px wide (±2), 16px tall (±8)
        return Math.abs(dx) < 4 && Math.abs(dy) < 16;
    }

    private void applyBounce(PlayableEntity player) {
        if (!(player instanceof AbstractPlayableSprite playable)) {
            return;
        }

        // ROM: X velocity direction based on h-flip flag
        boolean hFlipped = isHFlipped();
        int xVel = hFlipped ? -BOUNCE_X_SPEED : BOUNCE_X_SPEED;

        playable.setXSpeed((short) xVel);
        playable.setYSpeed((short) BOUNCE_Y_SPEED);
        playable.setAir(true);

        // Play spring SFX
        services().audioManager().playSfx(com.openggf.game.sonic3k.audio.Sonic3kSfx.SPRING.id);
    }
}
```

**Note:** This is a starting skeleton. The exact collision detection approach (`findNearestPlayer()`, `isHFlipped()`, collision box dimensions) must be verified against the engine's existing object collision patterns. Read `AbstractObjectInstance` and existing bumper implementations (like `CNZBumperManager` or spring objects) to use the correct APIs. The implementer should adjust to match.

- [ ] **Step 2: Register factory in Sonic3kObjectRegistry**

In `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`, find the factory registration section and add:

```java
        register(0x87, spawn -> new GumballTriangleBumperObjectInstance(spawn));
```

- [ ] **Step 3: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS (adjust method calls to match actual AbstractObjectInstance API).

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/GumballTriangleBumperObjectInstance.java \
       src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java
git commit -m "feat: implement GumballTriangleBumperObjectInstance

Fixed-velocity bounce (X=±0x300, Y=-0x600) with 0x0F frame cooldown.
Direction from h-flip flag. Registered as object 0x87."
```

---

### Task 10: Implement GumballItemObjectInstance

Ejected gumball items with gravity, collision, and ring rewards. Shared with future Pachinko.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/GumballItemObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`

- [ ] **Step 1: Create the gumball item object**

```java
// src/main/java/com/openggf/game/sonic3k/objects/GumballItemObjectInstance.java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.logging.Logger;

/**
 * Ejected gumball item (ROM: Obj_GumballItem, sonic3k.asm:96814).
 * Shared between Gumball Machine and Pachinko bonus stages.
 * <p>
 * Physics: gravity -4/frame on Y velocity, movement via MoveSprite2.
 * Collision: 16x16 hitbox. Subtype determines reward.
 * <p>
 * Reward table (indexed by y_position & 0xF):
 * 80, 50, 40, 35, 35, 30, 30, 20, 20, 10, 10, 10, 10, 5, 5, 5
 */
public class GumballItemObjectInstance extends AbstractObjectInstance {

    private static final Logger LOGGER = Logger.getLogger(GumballItemObjectInstance.class.getName());

    private static final int GRAVITY = -4;   // ROM: subi.w #4,y_vel(a0)
    private static final int COLLISION_SIZE = 16;

    /** ROM: byte_1E44C4 — ring reward by Y position index */
    private static final int[] REWARD_TABLE = {
        80, 50, 40, 35, 35, 30, 30, 20, 20, 10, 10, 10, 10, 5, 5, 5
    };

    private int subtype;
    private int yVelocity;
    private boolean collected;

    public GumballItemObjectInstance(ObjectSpawn spawn) {
        super(spawn);
        this.subtype = spawn.getSubtype();
    }

    public void setInitialVelocity(int xVel, int yVel) {
        this.yVelocity = yVel;
        // X velocity applied via SubpixelMotion
    }

    @Override
    public void update() {
        if (collected || isDestroyed()) {
            return;
        }

        // Apply gravity
        yVelocity += GRAVITY;

        // Move
        SubpixelMotion.moveSprite2(this, 0, yVelocity);

        // Check player collision
        PlayableEntity player = findNearestPlayer();
        if (player != null && isPlayerInRange(player)) {
            onCollected(player);
        }

        // Despawn if off-screen
        if (!isOnScreen(64)) {
            setDestroyed(true);
        }
    }

    private boolean isPlayerInRange(PlayableEntity player) {
        int dx = Math.abs(player.getCentreX() - getCentreX());
        int dy = Math.abs(player.getCentreY() - getCentreY());
        return dx < COLLISION_SIZE && dy < COLLISION_SIZE;
    }

    private void onCollected(PlayableEntity player) {
        collected = true;
        setDestroyed(true);

        switch (subtype) {
            case 1 -> {
                // Small bumper — sound only
                services().audioManager().playSfx(
                        com.openggf.game.sonic3k.audio.Sonic3kSfx.SMALL_BUMPER.id);
            }
            case 2 -> {
                // Ring item — awards 20 rings
                awardRings(20);
            }
            case 3 -> {
                // Bonus item — reward from position-based table
                int index = getCentreY() & 0xF;
                int rings = REWARD_TABLE[index];
                awardRings(rings);
            }
            default -> {
                // Normal gumball — default small reward
                awardRings(10);
            }
        }
    }

    private void awardRings(int count) {
        var provider = GameServices.bonusStage();
        if (provider instanceof com.openggf.game.AbstractBonusStageCoordinator coordinator) {
            coordinator.addRings(count);
        }
        // Also add to the active ring count for HUD display
        GameServices.gameState().addRings(count);
    }
}
```

**Note:** This is a starting skeleton. The implementer must verify: `findNearestPlayer()` API, `SubpixelMotion.moveSprite2()` signature, `isOnScreen()` API, ring count management, SFX enum values. Read existing object implementations and adjust.

- [ ] **Step 2: Register factory in Sonic3kObjectRegistry**

```java
        register(0xEB, spawn -> new GumballItemObjectInstance(spawn));
```

- [ ] **Step 3: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/GumballItemObjectInstance.java \
       src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java
git commit -m "feat: implement GumballItemObjectInstance

Ejected gumball with gravity (-4/frame), 16x16 collision, subtype-based
rewards. Ring reward table from ROM byte_1E44C4. Registered as object 0xEB."
```

---

### Task 11: Implement GumballMachineObjectInstance

The parent machine object with its 4-state state machine and 7 children.

**Files:**
- Create: `src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java`
- Modify: `src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java`

- [ ] **Step 1: Create the gumball machine object**

This is the largest object. It includes:
- 4-state state machine (IDLE, SPIN, TRIGGERED, POST_TRIGGER)
- 7 child objects spawned on init
- Exit trigger child that calls `GameServices.bonusStage().requestExit()`

```java
// src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java
package com.openggf.game.sonic3k.objects;

import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.util.AnimationTimer;

import java.util.Random;
import java.util.logging.Logger;

/**
 * Gumball Machine parent object (ROM: Obj_GumballMachine, sonic3k.asm:127399).
 * <p>
 * 4-state machine: IDLE → SPIN → TRIGGERED → POST_TRIGGER → IDLE.
 * Spawns 7 children on init: dispenser, container display, exit trigger,
 * and 4 solid platforms.
 * <p>
 * Activation range: -36/+72 X, -8/+16 Y from center.
 * Spin animation: frames [3,5,6,7,$14,5,$F4,$7F,5,5,$FC].
 * Trigger SFX: sfx_GumballTab (0xD2).
 */
public class GumballMachineObjectInstance extends AbstractObjectInstance {

    private static final Logger LOGGER = Logger.getLogger(GumballMachineObjectInstance.class.getName());

    // ROM: word_60D16 — activation range
    private static final int RANGE_X_MIN = -36;
    private static final int RANGE_X_MAX = 72;
    private static final int RANGE_Y_MIN = -8;
    private static final int RANGE_Y_MAX = 16;

    // Child offsets from parent position
    private static final int EXIT_TRIGGER_Y_OFFSET = 0x2A0;
    private static final int PLATFORM_Y_OFFSET = -0x2C;

    private enum State { IDLE, SPIN, TRIGGERED, POST_TRIGGER }

    private State state = State.IDLE;
    private boolean activationFlag;
    private final Random rng = new Random();

    public GumballMachineObjectInstance(ObjectSpawn spawn) {
        super(spawn);
    }

    @Override
    public void init() {
        // ROM: Obj_GumballMachine init
        // Seed RNG, spawn children
        rng.setSeed(System.nanoTime());

        spawnChildren();

        LOGGER.fine("Gumball Machine initialized at (" + getCentreX() + ", " + getCentreY() + ")");
    }

    private void spawnChildren() {
        int cx = getCentreX();
        int cy = getCentreY();

        // Exit trigger child — detects player and signals stage completion
        spawnChild(() -> new ExitTriggerChild(
                buildSpawnAt(cx, cy + EXIT_TRIGGER_Y_OFFSET)));

        // Platform children (4 solid platforms)
        spawnChild(() -> new PlatformChild(
                buildSpawnAt(cx - 0x38, cy + PLATFORM_Y_OFFSET)));
        spawnChild(() -> new PlatformChild(
                buildSpawnAt(cx, cy + PLATFORM_Y_OFFSET)));
        spawnChild(() -> new PlatformChild(
                buildSpawnAt(cx + 0x38, cy + PLATFORM_Y_OFFSET)));
        spawnChild(() -> new PlatformChild(
                buildSpawnAt(cx, cy - 0x28)));
    }

    @Override
    public void update() {
        switch (state) {
            case IDLE -> updateIdle();
            case SPIN -> updateSpin();
            case TRIGGERED -> updateTriggered();
            case POST_TRIGGER -> updatePostTrigger();
        }
    }

    private void updateIdle() {
        PlayableEntity player = findNearestPlayer();
        if (player == null) return;

        int dx = player.getCentreX() - getCentreX();
        int dy = player.getCentreY() - getCentreY();

        if (dx >= RANGE_X_MIN && dx < RANGE_X_MAX
                && dy >= RANGE_Y_MIN && dy < RANGE_Y_MAX) {
            state = State.SPIN;
            activationFlag = true;
            services().audioManager().playSfx(
                    com.openggf.game.sonic3k.audio.Sonic3kSfx.GUMBALL_TAB.id);
        }
    }

    private void updateSpin() {
        // ROM: Animate_RawNoSST with byte_61450 animation data
        // Simplified: transition after fixed frame count
        // The implementer should port the exact animation frame sequence
        state = State.TRIGGERED;
    }

    private void updateTriggered() {
        // Signal dispenser to eject gumballs
        // Spawn gumball items
        int itemCount = 1 + rng.nextInt(3); // 1-3 items
        for (int i = 0; i < itemCount; i++) {
            int xOff = -0x20 + rng.nextInt(0x40);
            int yVel = -0x200 - rng.nextInt(0x200);
            spawnChild(() -> {
                var item = new GumballItemObjectInstance(
                        buildSpawnAt(getCentreX() + xOff, getCentreY()));
                item.setInitialVelocity(0, yVel);
                return item;
            });
        }
        state = State.POST_TRIGGER;
    }

    private void updatePostTrigger() {
        if (!activationFlag) {
            state = State.IDLE;
        }
        // Check if player moved out of range to clear flag
        PlayableEntity player = findNearestPlayer();
        if (player != null) {
            int dx = player.getCentreX() - getCentreX();
            int dy = player.getCentreY() - getCentreY();
            if (dx < RANGE_X_MIN || dx >= RANGE_X_MAX
                    || dy < RANGE_Y_MIN || dy >= RANGE_Y_MAX) {
                activationFlag = false;
            }
        }
    }

    // ── Inner child classes ──────────────────────────────────────────────

    /**
     * Exit trigger child — detects player proximity and signals stage completion.
     * ROM: loc_61044 — range (-0x100/+0x200 X, -0x10/+0x40 Y).
     */
    static class ExitTriggerChild extends AbstractObjectInstance {
        public ExitTriggerChild(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        public void update() {
            PlayableEntity player = findNearestPlayer();
            if (player == null) return;

            int dx = player.getCentreX() - getCentreX();
            int dy = player.getCentreY() - getCentreY();

            if (dx >= -0x100 && dx < 0x200 && dy >= -0x10 && dy < 0x40) {
                GameServices.bonusStage().requestExit();
            }
        }
    }

    /**
     * Solid platform child. Visual only — collision handled by level geometry.
     * ROM: loc_60FFE with ObjDat3_6138C.
     */
    static class PlatformChild extends AbstractObjectInstance {
        public PlatformChild(ObjectSpawn spawn) {
            super(spawn);
        }

        @Override
        public void update() {
            // Static platform — no per-frame logic
        }
    }
}
```

**Note:** This is a structural skeleton. The implementer must:
1. Port the exact animation frame sequence from ROM `byte_61450`
2. Verify child spawn API (`spawnChild`, `buildSpawnAt`)
3. Add art rendering (mapping frames, art keys)
4. Verify `findNearestPlayer()` API
5. Add proper gumball ejection (ROM randomization table `byte_612E0`)
6. The dispenser and container display children are omitted for brevity — add during implementation based on the disassembly analysis in the spec

- [ ] **Step 2: Register factory in Sonic3kObjectRegistry**

```java
        register(0x86, spawn -> new GumballMachineObjectInstance(spawn));
```

- [ ] **Step 3: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/objects/GumballMachineObjectInstance.java \
       src/main/java/com/openggf/game/sonic3k/objects/Sonic3kObjectRegistry.java
git commit -m "feat: implement GumballMachineObjectInstance with children

4-state machine (IDLE/SPIN/TRIGGERED/POST_TRIGGER), spawns exit trigger
and platform children. Exit trigger signals coordinator.requestExit().
Registered as object 0x86."
```

---

### Task 12: Register Gumball Zone in Sonic3kZoneRegistry

Enable loading the Gumball bonus stage as zone 19 through the normal level loading path.

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneRegistry.java` (or equivalent zone registration file)

- [ ] **Step 1: Read the zone registry to understand registration pattern**

Read `src/main/java/com/openggf/game/sonic3k/Sonic3kZoneRegistry.java` to understand how zones are registered (layout addresses, collision addresses, art plan references).

- [ ] **Step 2: Add zone 19 (Gumball) registration**

Follow the existing pattern for other zones. Use the verified ROM addresses from Task 8. The zone uses Noninterleaved collision (bit 31 set in SolidIndexes pointer).

- [ ] **Step 3: Build and verify**

Run: `mvn compile -q`
Expected: BUILD SUCCESS

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kZoneRegistry.java
git commit -m "feat: register Gumball bonus stage as zone 19 in S3K zone registry

Layout, collision, art, and palette addresses from verified ROM offsets."
```

---

### Task 13: Document Intentional Discrepancy

Add the bonus stage `GameMode` discrepancy to the known discrepancies document.

**Files:**
- Modify: `docs/status/known-discrepancies.md`

- [ ] **Step 1: Read the current discrepancies document**

Read `docs/status/known-discrepancies.md` to see the format and table of contents.

- [ ] **Step 2: Add bonus stage discrepancy**

Add a new entry at the end:

```markdown
## Bonus Stage Game Mode

**Location:** `GameLoop.java`, `GameMode.java`
**ROM Reference:** `sonic3k.asm` Level: routine (line 7504)

### Original Implementation

S3K bonus stages (Gumball, Pachinko, Slots) are loaded through the normal `Level()` routine. The zone ID changes to a bonus zone ($1300/$1400/$1500), the level loads, and the game loop runs identically to any other level. No separate game mode exists.

### Engine Implementation

Bonus stages use a distinct `GameMode.BONUS_STAGE` that runs the same level rendering/physics/object pipeline as `LEVEL` mode, but with an explicit `AbstractBonusStageCoordinator` managing entry/exit lifecycle, state persistence, and ring gains.

### Reason

The engine's `GameLoop` dispatches behavior based on `GameMode`. Overloading `LEVEL` with conditional bonus stage logic would scatter bonus-specific checks across the codebase (timer suppression, death plane disable, exit detection, state save/restore). A dedicated mode keeps the lifecycle explicit and contained in the coordinator.

### Impact

None on gameplay. The level pipeline (rendering, physics, objects, collision) is identical between `LEVEL` and `BONUS_STAGE` modes. The only difference is the coordinator managing transitions.
```

Update the table of contents to include the new entry.

- [ ] **Step 3: Commit**

```bash
git add docs/status/known-discrepancies.md
git commit -m "docs: document bonus stage GameMode intentional discrepancy

ROM uses normal Level() routine for bonus zones. Engine uses distinct
GameMode.BONUS_STAGE with coordinator lifecycle for cleaner separation."
```

---

### Task 14: Integration Test — Bonus Stage Entry and Exit

End-to-end verification that the framework works: star post → bonus star → entry → level load → exit → zone restore.

**Files:**
- Create: `src/test/java/com/openggf/game/sonic3k/TestBonusStageLifecycle.java`

- [ ] **Step 1: Write the integration test**

```java
// src/test/java/com/openggf/game/sonic3k/TestBonusStageLifecycle.java
package com.openggf.game.sonic3k;

import com.openggf.game.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the bonus stage coordinator lifecycle without OpenGL.
 * Verifies state save/restore, ring persistence, and exit signaling.
 */
@ExtendWith(SingletonResetExtension.class)
class TestBonusStageLifecycle {

    @Test
    void testSelectBonusStage_ringFormula() {
        var coordinator = new Sonic3kBonusStageCoordinator();

        // Below threshold
        assertEquals(BonusStageType.NONE, coordinator.selectBonusStage(19));

        // remainder 0 → GUMBALL
        assertEquals(BonusStageType.GUMBALL, coordinator.selectBonusStage(20));
        assertEquals(BonusStageType.GUMBALL, coordinator.selectBonusStage(34));

        // remainder 1 → GLOWING_SPHERE
        assertEquals(BonusStageType.GLOWING_SPHERE, coordinator.selectBonusStage(35));
        assertEquals(BonusStageType.GLOWING_SPHERE, coordinator.selectBonusStage(49));

        // remainder 2 → SLOT_MACHINE
        assertEquals(BonusStageType.SLOT_MACHINE, coordinator.selectBonusStage(50));
        assertEquals(BonusStageType.SLOT_MACHINE, coordinator.selectBonusStage(64));

        // Cycle repeats
        assertEquals(BonusStageType.GUMBALL, coordinator.selectBonusStage(65));
    }

    @Test
    void testZoneIdMapping() {
        var coordinator = new Sonic3kBonusStageCoordinator();
        assertEquals(0x1300, coordinator.getZoneId(BonusStageType.GUMBALL));
        assertEquals(0x1400, coordinator.getZoneId(BonusStageType.GLOWING_SPHERE));
        assertEquals(0x1500, coordinator.getZoneId(BonusStageType.SLOT_MACHINE));
        assertEquals(-1, coordinator.getZoneId(BonusStageType.NONE));
    }

    @Test
    void testMusicIdMapping() {
        var coordinator = new Sonic3kBonusStageCoordinator();
        assertEquals(0x1E, coordinator.getMusicId(BonusStageType.GUMBALL));
        assertEquals(0x1F, coordinator.getMusicId(BonusStageType.GLOWING_SPHERE));
        assertEquals(0x20, coordinator.getMusicId(BonusStageType.SLOT_MACHINE));
    }

    @Test
    void testEntryExitLifecycle() {
        var coordinator = new Sonic3kBonusStageCoordinator();

        var savedState = new BonusStageState(
                0x0001, 0x0001, 50, 0, 1, 0,
                4, 0,
                0x100, 0x200, 0x80, 0x100,
                (byte) 0x0C, (byte) 0x0E, 0x300
        );

        // Entry
        coordinator.onEnter(BonusStageType.GUMBALL, savedState);
        assertEquals(BonusStageType.GUMBALL, coordinator.getActiveType());
        assertFalse(coordinator.isStageComplete());
        assertSame(savedState, coordinator.getSavedState());

        // Accumulate rings
        coordinator.addRings(30);
        coordinator.addRings(20);

        // Exit request
        coordinator.requestExit();
        assertTrue(coordinator.isStageComplete());

        // Get rewards
        var rewards = coordinator.getRewards();
        assertEquals(50, rewards.rings());

        // Exit
        coordinator.onExit();
        assertEquals(BonusStageType.NONE, coordinator.getActiveType());
        assertFalse(coordinator.isStageComplete());
    }

    @Test
    void testNoOpProvider() {
        var noop = NoOpBonusStageProvider.INSTANCE;
        assertFalse(noop.hasBonusStages());
        assertEquals(BonusStageType.NONE, noop.selectBonusStage(100));
        assertFalse(noop.isStageComplete());
        assertNull(noop.getSavedState());
    }
}
```

- [ ] **Step 2: Run the test**

Run: `mvn test -Dtest=TestBonusStageLifecycle -q`
Expected: All 4 tests PASS.

- [ ] **Step 3: Run full test suite**

Run: `mvn test -q`
Expected: All existing tests still pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/openggf/game/sonic3k/TestBonusStageLifecycle.java
git commit -m "test: add bonus stage coordinator lifecycle tests

Tests ring selection formula, zone/music mapping, entry/exit lifecycle
with state save/restore, ring accumulation, and NoOp provider."
```
