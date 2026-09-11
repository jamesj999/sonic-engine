# Level Entry Profile Steps — Move loadCurrentLevel() Into Profile System

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.
>
> **Worktree:** Execute all work in the existing `post-load-assembly` worktree at `.worktrees/post-load-assembly` (branch `feature/ai-post-load-assembly`).

**Goal:** Move the ad-hoc post-load orchestration in `LevelManager.loadCurrentLevel()` (checkpoint save/restore, player positioning, camera finalization, level events, sidekick reset) into the ROM-aligned init profile system so level entry is predictable across S1/S2/S3K. Then update `HeadlessTestFixture` to use the same extracted helpers so headless tests achieve parity with the production level-entry path.

**Architecture:** Extend `LevelLoadContext` with checkpoint snapshot fields and a `LevelData` reference. Add `LevelLoadMode.LEVEL_START` (fresh zone/act) and keep `FULL` as the low-level default. Add 5 new profile steps (`RestoreCheckpoint`, `PositionPlayer`, `FinalizeCamera`, `InitLevelEvents`, `ResetSidekick`) to each game's `levelLoadSteps()`. Extract the corresponding logic from `loadCurrentLevel()` into new LevelManager helper methods. `loadCurrentLevel()` shrinks to: populate context → call `loadLevel()` → request title card. Finally, update `HeadlessTestFixture.Builder.build()` to call the same LevelManager helpers (`finalizeCamera()`, `initLevelEvents()`) instead of reimplementing them, so headless tests exercise the same code paths as production.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, Maven

---

## Architecture Analysis

### Current `loadCurrentLevel()` Decomposition (lines 3699-3882)

After calling `loadLevel()` (which runs 13 profile steps), `loadCurrentLevel()` performs:

| # | Operation | Lines | Profile step? |
|---|-----------|-------|---------------|
| 1 | Save checkpoint values to locals | 3713-3723 | No — pre-load snapshot |
| 2 | `loadLevel(levelData.getLevelIndex())` | 3725 | Yes — runs 13 steps |
| 3 | Restore checkpoint from locals | 3729-3735 | No — post-load restore |
| 4 | Restore water state from checkpoint | 3741-3754 | No — post-load restore |
| 5 | Set frameCounter = 0 | 3756 | No |
| 6 | Position player (checkpoint vs level start) | 3762-3790 | No |
| 7 | Reset player movement state | 3792-3826 | Partial overlap with `InitPlayerAndCheckpoint` |
| 8 | Camera setup (frozen, focus, bounds, snap) | 3827-3844 | Partial overlap with `InitCameraBounds` |
| 9 | Init level events | 3847-3850 | No |
| 10 | Reset sidekick | 3854-3866 | No |
| 11 | Request title card | 3873-3877 | No — stays outside profile |

Operations 3-10 become profile steps. Operation 1 becomes context population. Operation 11 stays in `loadCurrentLevel()`.

### New Profile Steps (appended after existing 13)

| Step | Name | LevelManager method | What it does |
|------|------|---------------------|-------------|
| 14 | `RestoreCheckpoint` | `restoreCheckpointFromContext()` | Restore checkpoint + water state from context snapshot |
| 15 | `PositionPlayer` | `positionPlayer()` | Set player X/Y from checkpoint or level start |
| 16 | `FinalizeCamera` | `finalizeCamera()` | Unfreeze, focus, apply bounds, snap, vertical wrap |
| 17 | `InitLevelEvents` | `initLevelEvents()` | Init level event manager for current zone/act |
| 18 | `ResetSidekick` | `resetSidekick()` | Position sidekick near player, clear state |

### LevelLoadMode Changes

| Mode | When used | Entry steps included? |
|------|-----------|----------------------|
| `FULL` | `loadLevel(int)` — test code, direct loads | No (existing 13 steps only) |
| `LEVEL_START` | `loadCurrentLevel(true)` — fresh zone start, respawn | Yes (18 steps) |
| `SEAMLESS_RELOAD` | `loadZoneAndActSeamless()` — in-place transition | Partial (S3K skips `InitPlayerAndCheckpoint`) |

### LevelLoadContext Additions

New fields for checkpoint snapshot and level metadata:

```java
// Checkpoint snapshot (populated before profile steps execute)
private boolean hasCheckpointSnapshot;
private int snapshotCheckpointX, snapshotCheckpointY;
private int snapshotCameraX, snapshotCameraY;
private int snapshotCheckpointIndex = -1;
private int snapshotWaterLevel, snapshotWaterRoutine;
private boolean snapshotHasWaterState;

// Level metadata (populated before profile steps execute)
private LevelData levelData;
private boolean showTitleCard = true;
```

### Headless Test Parity Gap

`SharedLevel.load()` calls `loadZoneAndAct()` → `loadCurrentLevel()` → full production path. So the **initial load is production-faithful**. The gap is in the **per-test reset** performed by `HeadlessTestFixture.Builder.build()`.

| Step | Production `loadCurrentLevel()` | `HeadlessTestFixture.build()` | Gap |
|------|------|------|-----|
| Camera bounds | `finalizeCamera()` → from level | Manual: `camera.setMinX/Max/Y` from level | **Duplicated** — same logic, different code path |
| Camera snap | Two `updatePosition(true)` calls | One `updatePosition(true)` call | S3K AIZ may misalign |
| Vertical wrap | `verticalWrapEnabled = camera.isVerticalWrapEnabled()` | Not set | LZ3/SBZ2 wrapping wrong |
| Level events | Always via `initLevelEvents()` | Optional: only with `.withLevelEvents()` | **Opt-in vs always-on** |
| GroundSensor wiring | Profile step `InitPlayerAndCheckpoint` | Manual: `GroundSensor.setLevelManager()` | **Duplicated** |
| Player state | Full `positionPlayer()` reset (18 fields) | None — fresh sprite has defaults | Player state differs from production |

**Fix (Task 12):** After extracting `finalizeCamera()` and `initLevelEvents()` as public LevelManager methods, update `HeadlessTestFixture.build()` to call them instead of reimplementing the logic. This makes the per-test path share code with the production path, so bugs fixed in one are fixed in both.

---

## Task 1: Extend LevelLoadMode With LEVEL_START

**Files:**
- Modify: `src/main/java/com/openggf/game/LevelLoadMode.java`
- Test: `src/test/java/com/openggf/game/TestLevelLoadContext.java`

**Step 1: Add LEVEL_START to LevelLoadMode**

```java
package com.openggf.game;

public enum LevelLoadMode {
    /** Low-level load path (13 resource-loading steps only). */
    FULL,
    /** Full level entry (resource loading + player positioning + camera + events). */
    LEVEL_START,
    /** In-place reload that preserves runtime player/checkpoint state. */
    SEAMLESS_RELOAD
}
```

**Step 2: Add test for new mode**

In `TestLevelLoadContext.java`, add:

```java
@Test
public void levelStartModeIsDistinctFromFull() {
    var ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.LEVEL_START);
    assertEquals(LevelLoadMode.LEVEL_START, ctx.getLoadMode());
}
```

**Step 3: Run test**

Run: `mvn test -Dtest=com.openggf.game.TestLevelLoadContext`
Expected: PASS

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/game/LevelLoadMode.java src/test/java/com/openggf/game/TestLevelLoadContext.java
git commit -m "feat: add LevelLoadMode.LEVEL_START for full level entry"
```

---

## Task 2: Extend LevelLoadContext With Checkpoint Snapshot and LevelData

**Files:**
- Modify: `src/main/java/com/openggf/game/LevelLoadContext.java`
- Modify: `src/test/java/com/openggf/game/TestLevelLoadContext.java`

**Step 1: Write tests for new context fields**

Add to `TestLevelLoadContext.java`:

```java
@Test
public void checkpointSnapshotStartsInactive() {
    var ctx = new LevelLoadContext();
    assertFalse(ctx.hasCheckpointSnapshot());
    assertEquals(-1, ctx.getSnapshotCheckpointIndex());
}

@Test
public void snapshotCheckpointPreservesAllFields() {
    var ctx = new LevelLoadContext();
    ctx.snapshotCheckpoint(100, 200, 80, 160, 3);
    assertTrue(ctx.hasCheckpointSnapshot());
    assertEquals(100, ctx.getSnapshotCheckpointX());
    assertEquals(200, ctx.getSnapshotCheckpointY());
    assertEquals(80, ctx.getSnapshotCameraX());
    assertEquals(160, ctx.getSnapshotCameraY());
    assertEquals(3, ctx.getSnapshotCheckpointIndex());
}

@Test
public void snapshotWaterState() {
    var ctx = new LevelLoadContext();
    ctx.snapshotWaterState(0x300, 2);
    assertTrue(ctx.hasSnapshotWaterState());
    assertEquals(0x300, ctx.getSnapshotWaterLevel());
    assertEquals(2, ctx.getSnapshotWaterRoutine());
}

@Test
public void levelDataAccessor() {
    var ctx = new LevelLoadContext();
    assertNull(ctx.getLevelData());
    ctx.setLevelData(LevelData.EMERALD_HILL_1);
    assertEquals(LevelData.EMERALD_HILL_1, ctx.getLevelData());
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=com.openggf.game.TestLevelLoadContext`
Expected: FAIL — methods don't exist

**Step 3: Add fields and methods to LevelLoadContext**

Add to `LevelLoadContext.java` after existing fields:

```java
// Checkpoint snapshot (populated before profile steps execute)
private boolean hasCheckpointSnapshot;
private int snapshotCheckpointX, snapshotCheckpointY;
private int snapshotCameraX, snapshotCameraY;
private int snapshotCheckpointIndex = -1;
private int snapshotWaterLevel, snapshotWaterRoutine;
private boolean snapshotHasWaterState;

// Level metadata
private LevelData levelData;

public void snapshotCheckpoint(int x, int y, int cameraX, int cameraY, int checkpointIndex) {
    this.hasCheckpointSnapshot = true;
    this.snapshotCheckpointX = x;
    this.snapshotCheckpointY = y;
    this.snapshotCameraX = cameraX;
    this.snapshotCameraY = cameraY;
    this.snapshotCheckpointIndex = checkpointIndex;
}

public boolean hasCheckpointSnapshot() { return hasCheckpointSnapshot; }
public int getSnapshotCheckpointX() { return snapshotCheckpointX; }
public int getSnapshotCheckpointY() { return snapshotCheckpointY; }
public int getSnapshotCameraX() { return snapshotCameraX; }
public int getSnapshotCameraY() { return snapshotCameraY; }
public int getSnapshotCheckpointIndex() { return snapshotCheckpointIndex; }

public void snapshotWaterState(int waterLevel, int waterRoutine) {
    this.snapshotWaterLevel = waterLevel;
    this.snapshotWaterRoutine = waterRoutine;
    this.snapshotHasWaterState = true;
}

public boolean hasSnapshotWaterState() { return snapshotHasWaterState; }
public int getSnapshotWaterLevel() { return snapshotWaterLevel; }
public int getSnapshotWaterRoutine() { return snapshotWaterRoutine; }

public LevelData getLevelData() { return levelData; }
public void setLevelData(LevelData levelData) { this.levelData = levelData; }
```

Add import: `import com.openggf.level.LevelData;`

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=com.openggf.game.TestLevelLoadContext`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/LevelLoadContext.java src/test/java/com/openggf/game/TestLevelLoadContext.java
git commit -m "feat: add checkpoint snapshot and LevelData fields to LevelLoadContext"
```

---

## Task 3: Extract Entry Helper Methods From loadCurrentLevel()

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

This extracts 5 new public methods from `loadCurrentLevel()` without changing any behavior. `loadCurrentLevel()` will still call them directly until the profiles are wired in Task 6+.

**Step 1: Extract `restoreCheckpointFromContext(LevelLoadContext ctx)`**

Add to `LevelManager.java` after `initBackgroundRenderer()` (after line ~857):

```java
/**
 * Restore checkpoint state and water state from context snapshot.
 * ROM: Lamp_LoadInfo — restore saved checkpoint after level reload.
 */
public void restoreCheckpointFromContext(LevelLoadContext ctx) {
    if (!ctx.hasCheckpointSnapshot()) {
        return;
    }
    if (checkpointState != null) {
        checkpointState.restoreFromSaved(
            ctx.getSnapshotCheckpointX(), ctx.getSnapshotCheckpointY(),
            ctx.getSnapshotCameraX(), ctx.getSnapshotCameraY(),
            ctx.getSnapshotCheckpointIndex());
        if (ctx.hasSnapshotWaterState() && checkpointState instanceof CheckpointState cs) {
            cs.saveWaterState(ctx.getSnapshotWaterLevel(), ctx.getSnapshotWaterRoutine());
        }
    }
    // Restore water level/routine to match checkpoint state
    if (ctx.hasSnapshotWaterState()) {
        int featureZone = getFeatureZoneId();
        int featureAct = getFeatureActId();
        WaterSystem waterSystem = WaterSystem.getInstance();
        if (waterSystem.hasWater(featureZone, featureAct)) {
            waterSystem.setWaterLevelDirect(featureZone, featureAct, ctx.getSnapshotWaterLevel());
            waterSystem.setWaterLevelTarget(featureZone, featureAct, ctx.getSnapshotWaterLevel());
        }
        if (zoneFeatureProvider instanceof com.openggf.game.sonic1.Sonic1ZoneFeatureProvider s1zfp
                && s1zfp.getWaterEvents() != null) {
            s1zfp.getWaterEvents().setWaterRoutine(ctx.getSnapshotWaterRoutine());
        }
    }
}
```

**Step 2: Extract `positionPlayer(LevelLoadContext ctx)`**

```java
/**
 * Position player from checkpoint or level start coordinates.
 * ROM: Player X/Y init within Level: routine.
 */
public void positionPlayer(LevelLoadContext ctx) {
    frameCounter = 0;
    Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
    if (player == null) {
        return;
    }

    boolean hasCheckpoint = ctx.hasCheckpointSnapshot();
    int spawnY = -1;

    if (hasCheckpoint) {
        player.setCentreX((short) ctx.getSnapshotCheckpointX());
        player.setCentreY((short) ctx.getSnapshotCheckpointY());
        spawnY = ctx.getSnapshotCheckpointY();
        LOGGER.info("Set player position from checkpoint: X=" + ctx.getSnapshotCheckpointX() +
                ", Y=" + ctx.getSnapshotCheckpointY() + " (center coordinates)");
    } else {
        LevelData levelData = ctx.getLevelData();
        int spawnX = levelData != null ? levelData.getStartXPos() : 0x60;
        spawnY = levelData != null ? levelData.getStartYPos() : 0x28F;

        if (game instanceof DynamicStartPositionProvider dynamicStartProvider) {
            int[] dynamicStart = dynamicStartProvider.getStartPosition(currentZone, currentAct);
            if (dynamicStart != null && dynamicStart.length >= 2) {
                spawnX = dynamicStart[0];
                spawnY = dynamicStart[1];
                LOGGER.info("Set player position from dynamic start provider: X=" + spawnX +
                        ", Y=" + spawnY + " (zone=" + currentZone + ", act=" + currentAct + ")");
            }
        }

        player.setCentreX((short) spawnX);
        player.setCentreY((short) spawnY);
        LOGGER.info("Set player position from level start: X=" + spawnX +
                ", Y=" + spawnY + " (center coordinates)" +
                (levelData != null ? ", level: " + levelData.name() : ""));
    }

    if (player instanceof AbstractPlayableSprite playable) {
        playable.resetState();
        playable.setXSpeed((short) 0);
        playable.setYSpeed((short) 0);
        playable.setGSpeed((short) 0);
        playable.setAir(spawnY == 0);
        playable.setRolling(false);
        playable.setDead(false);
        playable.setHurt(false);
        playable.setDeathCountdown(0);
        playable.setInvulnerableFrames(0);
        playable.setInvincibleFrames(0);
        playable.setDirection(Direction.RIGHT);
        playable.setAngle((byte) 0);
        player.setLayer((byte) 0);
        playable.setHighPriority(false);
        playable.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);
        playable.setRingCount(0);
        AudioManager.getInstance().getBackend().setSpeedShoes(false);
    }
}
```

**Step 3: Extract `finalizeCamera()`**

```java
/**
 * Finalize camera after player positioning: unfreeze, focus, apply bounds, snap.
 * ROM: Camera init after player placed in Level: routine.
 */
public void finalizeCamera() {
    Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
    if (!(player instanceof AbstractPlayableSprite playable)) {
        return;
    }
    Camera camera = Camera.getInstance();
    camera.setFrozen(false);
    camera.setFocusedSprite(playable);
    camera.updatePosition(true);

    Level currentLevel = getCurrentLevel();
    if (currentLevel != null) {
        camera.setMinX((short) currentLevel.getMinX());
        camera.setMaxX((short) currentLevel.getMaxX());
        camera.setMinY((short) currentLevel.getMinY());
        camera.setMaxY((short) currentLevel.getMaxY());
        verticalWrapEnabled = camera.isVerticalWrapEnabled();
        camera.updatePosition(true);
    }
}
```

**Step 4: Extract `initLevelEvents()`**

```java
/**
 * Initialize level event manager for the current zone and act.
 * ROM: Zone-specific event handler dispatch in Level: routine.
 */
public void initLevelEvents() {
    LevelEventProvider levelEvents = GameModuleRegistry.getCurrent().getLevelEventProvider();
    if (levelEvents != null) {
        levelEvents.initLevel(currentZone, currentAct);
    }
}
```

**Step 5: Extract `resetSidekick()`**

```java
/**
 * Reset sidekick (Tails) position and state near the main player.
 * ROM: Tails init after Sonic positioned in Level: routine.
 */
public void resetSidekick() {
    Sprite player = spriteManager.getSprite(configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE));
    if (player == null) {
        return;
    }
    AbstractPlayableSprite sidekick = spriteManager.getSidekick();
    if (sidekick != null) {
        sidekick.setX((short) (player.getX() - 40));
        sidekick.setY(player.getY());
        sidekick.setXSpeed((short) 0);
        sidekick.setYSpeed((short) 0);
        sidekick.setGSpeed((short) 0);
        sidekick.setAir(false);
        sidekick.setDead(false);
        sidekick.setDeathCountdown(0);
        sidekick.setHighPriority(false);
        sidekick.setDirection(Direction.RIGHT);
    }
}
```

**Step 6: Run all tests**

Run: `mvn test`
Expected: All 1510 tests pass — methods extracted but not yet called.

**Step 7: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: extract 5 entry helper methods from loadCurrentLevel()"
```

---

## Task 4: Rewrite loadCurrentLevel() To Use Extracted Helpers

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

Replace the body of `loadCurrentLevel(boolean showTitleCard)` (lines 3699-3882) with calls to the new helpers. This is a pure refactoring — same operations, same order, using the extracted methods.

**Step 1: Rewrite loadCurrentLevel()**

```java
private void loadCurrentLevel(boolean showTitleCard) {
    try {
        specialStageReturnLevelReloadRequested = false;
        levelInactiveForTransition = false;

        // Ensure zone list is populated before accessing it
        if (levels.isEmpty()) {
            gameModule = GameModuleRegistry.getCurrent();
            refreshZoneList();
        }
        LevelData levelData = levels.get(currentZone).get(currentAct);

        // Snapshot checkpoint state before loadLevel() clears it
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setLevelIndex(levelData.getLevelIndex());
        ctx.setLevelData(levelData);
        ctx.setLoadMode(LevelLoadMode.LEVEL_START);

        boolean hasCheckpoint = checkpointState != null && checkpointState.isActive();
        if (hasCheckpoint) {
            ctx.snapshotCheckpoint(
                checkpointState.getSavedX(), checkpointState.getSavedY(),
                checkpointState.getSavedCameraX(), checkpointState.getSavedCameraY(),
                checkpointState.getLastCheckpointIndex());
            if (checkpointState instanceof CheckpointState cs && cs.hasWaterState()) {
                ctx.snapshotWaterState(cs.getSavedWaterLevel(), cs.getSavedWaterRoutine());
            }
        }

        // Run all profile steps (13 resource-load + 5 entry steps)
        loadLevel(levelData.getLevelIndex(), ctx.getLoadMode());

        // Restore checkpoint from snapshot (loadLevel cleared it)
        restoreCheckpointFromContext(ctx);

        // Position player, finalize camera, init events, reset sidekick
        positionPlayer(ctx);
        finalizeCamera();
        initLevelEvents();
        resetSidekick();

        // Title card (engine UI concern, stays outside profile)
        if (showTitleCard
                && !graphicsManager.isHeadlessMode()
                && !(zoneFeatureProvider != null && zoneFeatureProvider.shouldSuppressInitialTitleCard(currentZone, currentAct))) {
            requestTitleCard(currentZone, currentAct);
        }

    } catch (IOException e) {
        throw new RuntimeException(e);
    }
}
```

**Step 2: Run all tests**

Run: `mvn test`
Expected: All 1510 tests pass — identical behavior, just using extracted methods.

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: rewrite loadCurrentLevel() to use extracted helpers"
```

---

## Task 5: Add a loadLevel() Overload That Accepts a Pre-Built Context

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

Currently `loadLevel(int, LevelLoadMode)` creates its own `LevelLoadContext`. We need an overload that accepts an externally-built context (with checkpoint snapshot, LevelData, etc.) so that when profiles include entry steps, they can read from the context.

**Step 1: Add the overload**

```java
/**
 * Loads the specified level using a pre-built context.
 * The context may carry checkpoint snapshots, LevelData, and other entry metadata
 * that profile steps can read.
 */
public void loadLevel(int levelIndex, LevelLoadContext ctx) throws IOException {
    try {
        LevelInitProfile profile = GameModuleRegistry.getCurrent().getLevelInitProfile();
        ctx.setLevelIndex(levelIndex);

        List<InitStep> steps = profile.levelLoadSteps(ctx);
        if (steps.isEmpty()) {
            throw new IllegalStateException(
                "No level load steps defined for " +
                GameModuleRegistry.getCurrent().getClass().getSimpleName() +
                ". All game modules must implement levelLoadSteps().");
        }
        for (InitStep step : steps) {
            long start = System.nanoTime();
            step.execute();
            long elapsed = (System.nanoTime() - start) / 1_000_000;
            LOGGER.fine(() -> String.format("  [%s] %dms — %s", step.name(), elapsed, step.romRoutine()));
        }
        if (ctx.getLevel() != null) {
            level = ctx.getLevel();
        }
    } catch (Exception e) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException ioe) {
            LOGGER.log(SEVERE, "Failed to load level " + levelIndex, ioe);
            throw ioe;
        }
        LOGGER.log(SEVERE, "Unexpected error while loading level " + levelIndex, e);
        throw new IOException("Failed to load level due to unexpected error.", e);
    }
}
```

**Step 2: Update `loadLevel(int, LevelLoadMode)` to delegate**

```java
public void loadLevel(int levelIndex, LevelLoadMode loadMode) throws IOException {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(loadMode);
    loadLevel(levelIndex, ctx);
}
```

**Step 3: Run all tests**

Run: `mvn test`
Expected: All 1510 tests pass.

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: add loadLevel(int, LevelLoadContext) overload for pre-built context"
```

---

## Task 6: Add Entry Steps to Sonic2LevelInitProfile

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic2/TestSonic2LevelInitProfile.java`

**Step 1: Update test to expect 18 steps in LEVEL_START mode**

Add to `TestSonic2LevelInitProfile.java`:

```java
@Test
public void levelStartModeAdds5EntrySteps() {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.LEVEL_START);

    List<InitStep> steps = profile.levelLoadSteps(ctx);
    assertEquals(18, steps.size());

    // First 13 are resource-load steps (unchanged)
    assertEquals("InitGameModule", steps.get(0).name());
    assertEquals("InitBackgroundRenderer", steps.get(12).name());

    // Last 5 are entry steps
    assertEquals("RestoreCheckpoint", steps.get(13).name());
    assertEquals("PositionPlayer", steps.get(14).name());
    assertEquals("FinalizeCamera", steps.get(15).name());
    assertEquals("InitLevelEvents", steps.get(16).name());
    assertEquals("ResetSidekick", steps.get(17).name());
}

@Test
public void fullModeStillReturns13Steps() {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.FULL);

    List<InitStep> steps = profile.levelLoadSteps(ctx);
    assertEquals(13, steps.size());
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=com.openggf.game.sonic2.TestSonic2LevelInitProfile`
Expected: FAIL — still returns 13 steps for LEVEL_START

**Step 3: Update Sonic2LevelInitProfile.levelLoadSteps()**

Replace the method body. The key change: after the existing 13 steps, conditionally append 5 entry steps when `ctx.getLoadMode() == LevelLoadMode.LEVEL_START`:

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    List<InitStep> steps = new ArrayList<>();

    // === Resource-load steps (13, always included) ===
    steps.add(new InitStep("InitGameModule",
            "S2 Phase A (#1-4): Pal_FadeToBlack, ClearPLC, clear variables, create Game instance",
            () -> { try { lm.initGameModule(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitAudio",
            "S2 Phase C (#21): Level_SetPlayerMode, PlayMusic — configure audio manager and play level music",
            () -> { try { lm.initAudio(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("LoadLevelData",
            "S2 Phase E-F (#26-35): LevelDataLoad, LoadZoneTiles, LoadCollisionIndexes",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitAnimatedContent",
            "S2 Phase E (#32): LoadAnimatedBlocks (pattern animation scripts + palette cycling)",
            lm::initAnimatedContent));
    steps.add(new InitStep("InitObjectManager",
            "S2 Phase G (#36-38): InitPlayers, WaterEffects, create ObjectManager, wire CollisionSystem",
            () -> { try { lm.initObjectManager(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitCameraBounds",
            "S2 Phase E (#26): LevelSizeLoad — reset camera bounds from level geometry",
            lm::initCameraBounds));
    steps.add(new InitStep("InitGameplayState",
            "S2 Phase H (#41-43): OscillateNumInit, clear rings/time/lives, HUD update flags",
            lm::initGameplayState));
    steps.add(new InitStep("InitRings",
            "S2 Phase I (#45): RingsManager — initial ring placement and pattern caching",
            lm::initRings));
    steps.add(new InitStep("InitZoneFeatures",
            "S2 Phase G (#39-40): WaterSurface, CPZ Pylon, OOZ Oil, CNZ bumpers",
            () -> { try { lm.initZoneFeatures(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitArt",
            "S2 Phase C (#8-9): ObjectArt (zone PLC), PlayerSpriteArt",
            lm::initArt));
    steps.add(new InitStep("InitPlayerAndCheckpoint",
            "S2 Phase G (#37) + H (#41): ResetPlayerState, checkpoint clear, LevelGamestate",
            lm::initPlayerAndCheckpoint));
    steps.add(new InitStep("InitWater",
            "S2 Phase B (#13,18): WaterSystem loading for water zones (LZ, HPZ, CPZ)",
            () -> { try { lm.initWater(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO at maximum required size",
            lm::initBackgroundRenderer));

    // === Entry steps (5, only for LEVEL_START mode) ===
    if (ctx.getLoadMode() == LevelLoadMode.LEVEL_START) {
        steps.add(new InitStep("RestoreCheckpoint",
                "ROM Lamp_LoadInfo: restore checkpoint + water state from context snapshot",
                () -> lm.restoreCheckpointFromContext(ctx)));
        steps.add(new InitStep("PositionPlayer",
                "S2 Phase G (#37): Set player X/Y from checkpoint or StartLocArray",
                () -> lm.positionPlayer(ctx)));
        steps.add(new InitStep("FinalizeCamera",
                "S2 Phase E (#26): Unfreeze camera, apply level bounds, snap to player",
                lm::finalizeCamera));
        steps.add(new InitStep("InitLevelEvents",
                "S2 Phase J: Init zone-specific event handlers (HTZ earthquake, boss arenas)",
                lm::initLevelEvents));
        steps.add(new InitStep("ResetSidekick",
                "S2 Phase G (#37): Position Tails near Sonic",
                lm::resetSidekick));
    }

    return List.copyOf(steps);
}
```

Add imports at top:
```java
import java.util.ArrayList;
import com.openggf.game.LevelLoadMode;
```

**Step 4: Run tests to verify they pass**

Run: `mvn test -Dtest=com.openggf.game.sonic2.TestSonic2LevelInitProfile`
Expected: PASS

**Step 5: Run full test suite**

Run: `mvn test`
Expected: All 1510 tests pass. The new entry steps are declared but `loadCurrentLevel()` hasn't been wired to use them yet.

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java src/test/java/com/openggf/game/sonic2/TestSonic2LevelInitProfile.java
git commit -m "feat: add 5 entry steps to Sonic2LevelInitProfile for LEVEL_START mode"
```

---

## Task 7: Add Entry Steps to Sonic1LevelInitProfile

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic1/TestSonic1LevelInitProfile.java`

**Step 1: Update test to expect 18 steps in LEVEL_START mode**

Add to `TestSonic1LevelInitProfile.java`:

```java
@Test
public void levelStartModeAdds5EntrySteps() {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.LEVEL_START);

    List<InitStep> steps = profile.levelLoadSteps(ctx);
    assertEquals(18, steps.size());

    assertEquals("InitGameModule", steps.get(0).name());
    assertEquals("InitBackgroundRenderer", steps.get(12).name());

    assertEquals("RestoreCheckpoint", steps.get(13).name());
    assertEquals("PositionPlayer", steps.get(14).name());
    assertEquals("FinalizeCamera", steps.get(15).name());
    assertEquals("InitLevelEvents", steps.get(16).name());
    assertEquals("ResetSidekick", steps.get(17).name());
}

@Test
public void fullModeStillReturns13Steps() {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.FULL);

    List<InitStep> steps = profile.levelLoadSteps(ctx);
    assertEquals(13, steps.size());
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=com.openggf.game.sonic1.TestSonic1LevelInitProfile`
Expected: FAIL

**Step 3: Update Sonic1LevelInitProfile.levelLoadSteps()**

Same pattern as S2: switch from `List.of()` to `ArrayList` + conditional entry steps. Change:

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    List<InitStep> steps = new ArrayList<>();

    // === Resource-load steps (13, unchanged) ===
    steps.add(new InitStep("InitGameModule",
            "S1 Phase A (#1-4): bgm_Fade, ClearPLC, PaletteFadeOut, create Game instance",
            () -> { try { lm.initGameModule(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitAudio",
            "S1 Phase D (#15): QueueSound1 from MusicList — SBZ3/FZ music overrides",
            () -> { try { lm.initAudio(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("LoadLevelData",
            "S1 Phase G-H (#20-26): LevelSizeLoad, DeformLayers, LevelDataLoad, ConvertCollisionArray, ColIndexLoad",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitAnimatedContent",
            "S1 Phase G: LoadAnimatedBlocks (S1 loads animation scripts during LevelDataLoad)",
            lm::initAnimatedContent));
    steps.add(new InitStep("InitObjectManager",
            "S1 Phase I (#28-32): Spawn Sonic, HUD, create ObjectManager, wire CollisionSystem",
            () -> { try { lm.initObjectManager(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitCameraBounds",
            "S1 Phase G (#20): LevelSizeLoad — reset camera bounds from level geometry",
            lm::initCameraBounds));
    steps.add(new InitStep("InitGameplayState",
            "S1 Phase K (#36-38): OscillateNumInit, clear game state, HUD update flags",
            lm::initGameplayState));
    steps.add(new InitStep("InitRings",
            "S1 Phase J (#33): ObjPosLoad — rings are objects in S1, placed via ObjectManager",
            lm::initRings));
    steps.add(new InitStep("InitZoneFeatures",
            "S1 Phase I (#32): LZ water surface objects, zone-specific features",
            () -> { try { lm.initZoneFeatures(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitArt",
            "S1 Phase A (#6-7): Zone PLC, plcid_Main2 (shared HUD/ring/monitor patterns)",
            lm::initArt));
    steps.add(new InitStep("InitPlayerAndCheckpoint",
            "S1 Phase I (#28): Spawn Sonic, reset player state, checkpoint clear",
            lm::initPlayerAndCheckpoint));
    steps.add(new InitStep("InitWater",
            "S1 Phase C (#11): LZ water check, WaterHeight table, Water_flag",
            () -> { try { lm.initWater(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO",
            lm::initBackgroundRenderer));

    // === Entry steps (5, only for LEVEL_START mode) ===
    if (ctx.getLoadMode() == LevelLoadMode.LEVEL_START) {
        steps.add(new InitStep("RestoreCheckpoint",
                "ROM Lamp_LoadInfo: restore checkpoint + water state from context snapshot",
                () -> lm.restoreCheckpointFromContext(ctx)));
        steps.add(new InitStep("PositionPlayer",
                "S1 Phase I (#28): Set player X/Y from checkpoint or StartLocArray",
                () -> lm.positionPlayer(ctx)));
        steps.add(new InitStep("FinalizeCamera",
                "S1 Phase G (#20): Unfreeze camera, apply level bounds, snap to player",
                lm::finalizeCamera));
        steps.add(new InitStep("InitLevelEvents",
                "S1 Phase L: Init per-zone event handlers (GHZ/MZ/SYZ/LZ/SLZ/SBZ/FZ)",
                lm::initLevelEvents));
        steps.add(new InitStep("ResetSidekick",
                "S1: Position Tails near Sonic (if sidekick present)",
                lm::resetSidekick));
    }

    return List.copyOf(steps);
}
```

Add imports: `import java.util.ArrayList;`, `import com.openggf.game.LevelLoadMode;`

**Step 4: Run tests**

Run: `mvn test -Dtest=com.openggf.game.sonic1.TestSonic1LevelInitProfile`
Expected: PASS

**Step 5: Run full test suite**

Run: `mvn test`
Expected: All 1510 tests pass.

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java src/test/java/com/openggf/game/sonic1/TestSonic1LevelInitProfile.java
git commit -m "feat: add 5 entry steps to Sonic1LevelInitProfile for LEVEL_START mode"
```

---

## Task 8: Add Entry Steps to Sonic3kLevelInitProfile

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelInitProfile.java`

**Step 1: Update test**

Add to `TestSonic3kLevelInitProfile.java`:

```java
@Test
public void levelStartModeAdds5EntrySteps() {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.LEVEL_START);

    List<InitStep> steps = profile.levelLoadSteps(ctx);
    // S3K has 14 resource steps (13 base + conditional InitPlayerAndCheckpoint)
    // + 5 entry steps = 19 total in LEVEL_START
    // But InitPlayerAndCheckpoint is NOT skipped in LEVEL_START
    assertEquals(18, steps.size());

    assertEquals("RestoreCheckpoint", steps.get(13).name());
    assertEquals("PositionPlayer", steps.get(14).name());
    assertEquals("FinalizeCamera", steps.get(15).name());
    assertEquals("InitLevelEvents", steps.get(16).name());
    assertEquals("ResetSidekick", steps.get(17).name());
}

@Test
public void seamlessReloadSkipsPlayerAndCheckpointAndEntrySteps() {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.SEAMLESS_RELOAD);

    List<InitStep> steps = profile.levelLoadSteps(ctx);
    // SEAMLESS_RELOAD: 12 resource steps (skips InitPlayerAndCheckpoint), no entry steps
    assertEquals(12, steps.size());
    // Verify InitPlayerAndCheckpoint is NOT in the list
    assertTrue(steps.stream().noneMatch(s -> "InitPlayerAndCheckpoint".equals(s.name())));
}
```

**Step 2: Run tests to verify they fail**

Run: `mvn test -Dtest=com.openggf.game.sonic3k.TestSonic3kLevelInitProfile`
Expected: FAIL

**Step 3: Update Sonic3kLevelInitProfile.levelLoadSteps()**

The S3K profile already uses `ArrayList` and conditionally skips `InitPlayerAndCheckpoint` for `SEAMLESS_RELOAD`. Add the entry steps for `LEVEL_START`:

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    boolean seamlessReload = ctx.getLoadMode() == LevelLoadMode.SEAMLESS_RELOAD;
    boolean levelStart = ctx.getLoadMode() == LevelLoadMode.LEVEL_START;

    List<InitStep> steps = new ArrayList<>();

    // === Resource-load steps (13 base, SEAMLESS_RELOAD skips InitPlayerAndCheckpoint) ===
    steps.add(new InitStep("InitGameModule",
            "S3K Phase A-D (#1-20): cmd_FadeOut, Pal_FadeToBlack, Clear_Nem_Queue, clearRAM, create Game instance",
            () -> { try { lm.initGameModule(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitAudio",
            "S3K Phase F (#25): Play_Music from LevelMusic_Playlist - AIZ1 lamppost 3 music override",
            () -> { try { lm.initAudio(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("LoadLevelData",
            "S3K Phase H-K (#30-38): Get_LevelSizeStart, DeformBgLayer, LoadLevelLoadBlock, LoadLevelLoadBlock2, j_LevelSetup, LoadSolids",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitAnimatedContent",
            "S3K Phase J (#37): Animate_Init (zone-specific animation counter initialization)",
            lm::initAnimatedContent));
    steps.add(new InitStep("InitObjectManager",
            "S3K Phase O (#47-48): SpawnLevelMainSprites, Load_Sprites - create ObjectManager, wire CollisionSystem",
            () -> { try { lm.initObjectManager(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitCameraBounds",
            "S3K Phase H (#32): Get_LevelSizeStart - reset camera bounds from level geometry",
            lm::initCameraBounds));
    steps.add(new InitStep("InitGameplayState",
            "S3K Phase N (#43-45): Clear game state, OscillateNumInit, Level_started_flag set before first object frame",
            lm::initGameplayState));
    steps.add(new InitStep("InitRings",
            "S3K Phase O (#49): Load_Rings - initial ring placement",
            lm::initRings));
    steps.add(new InitStep("InitZoneFeatures",
            "S3K Phase J (#36): j_LevelSetup -> LevelSetupArray per-zone dispatch, HCZ water surface, MHZ pollen",
            () -> { try { lm.initZoneFeatures(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitArt",
            "S3K Phase C (#12-14): Load_PLC zone, character PLCs, standard PLCs",
            lm::initArt));

    if (!seamlessReload) {
        steps.add(new InitStep("InitPlayerAndCheckpoint",
                "S3K Phase O (#47): SpawnLevelMainSprites - player spawn after game state init",
                lm::initPlayerAndCheckpoint));
    }

    steps.add(new InitStep("InitWater",
            "S3K Phase E (#22): CheckLevelForWater, StartingWaterHeights",
            () -> { try { lm.initWater(); } catch (IOException e) { throw new UncheckedIOException(e); } }));
    steps.add(new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO for AIZ intro ocean-to-beach transition",
            lm::initBackgroundRenderer));

    // === Entry steps (5, only for LEVEL_START mode) ===
    if (levelStart) {
        steps.add(new InitStep("RestoreCheckpoint",
                "ROM Lamp_LoadInfo: restore checkpoint + water state from context snapshot",
                () -> lm.restoreCheckpointFromContext(ctx)));
        steps.add(new InitStep("PositionPlayer",
                "S3K Phase O (#47): Set player X/Y from checkpoint or StartLocArray (dynamic start provider)",
                () -> lm.positionPlayer(ctx)));
        steps.add(new InitStep("FinalizeCamera",
                "S3K Phase H (#32): Unfreeze camera, apply level bounds, snap to player",
                lm::finalizeCamera));
        steps.add(new InitStep("InitLevelEvents",
                "S3K Phase Q: Init zone-specific event handlers (AIZ, HCZ, etc.)",
                lm::initLevelEvents));
        steps.add(new InitStep("ResetSidekick",
                "S3K Phase O (#47): Position Tails near Sonic",
                lm::resetSidekick));
    }

    return List.copyOf(steps);
}
```

**Step 4: Run tests**

Run: `mvn test -Dtest=com.openggf.game.sonic3k.TestSonic3kLevelInitProfile`
Expected: PASS

**Step 5: Run full test suite**

Run: `mvn test`
Expected: All 1510 tests pass.

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelInitProfile.java
git commit -m "feat: add 5 entry steps to Sonic3kLevelInitProfile for LEVEL_START mode"
```

---

## Task 9: Wire loadCurrentLevel() Through Profile Entry Steps

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

This is the critical wiring step. Change `loadCurrentLevel()` to call `loadLevel(levelIndex, ctx)` with `LEVEL_START` mode so the profile's entry steps execute, then remove the now-redundant direct calls to the helper methods.

**Step 1: Update loadCurrentLevel()**

```java
private void loadCurrentLevel(boolean showTitleCard) {
    try {
        specialStageReturnLevelReloadRequested = false;
        levelInactiveForTransition = false;

        if (levels.isEmpty()) {
            gameModule = GameModuleRegistry.getCurrent();
            refreshZoneList();
        }
        LevelData levelData = levels.get(currentZone).get(currentAct);

        // Build context with checkpoint snapshot and level metadata
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setLevelIndex(levelData.getLevelIndex());
        ctx.setLevelData(levelData);
        ctx.setLoadMode(LevelLoadMode.LEVEL_START);

        boolean hasCheckpoint = checkpointState != null && checkpointState.isActive();
        if (hasCheckpoint) {
            ctx.snapshotCheckpoint(
                checkpointState.getSavedX(), checkpointState.getSavedY(),
                checkpointState.getSavedCameraX(), checkpointState.getSavedCameraY(),
                checkpointState.getLastCheckpointIndex());
            if (checkpointState instanceof CheckpointState cs && cs.hasWaterState()) {
                ctx.snapshotWaterState(cs.getSavedWaterLevel(), cs.getSavedWaterRoutine());
            }
        }

        // Run all profile steps (13 resource-load + 5 entry steps)
        loadLevel(levelData.getLevelIndex(), ctx);

        // Title card (engine UI concern, stays outside profile)
        if (showTitleCard
                && !graphicsManager.isHeadlessMode()
                && !(zoneFeatureProvider != null && zoneFeatureProvider.shouldSuppressInitialTitleCard(currentZone, currentAct))) {
            requestTitleCard(currentZone, currentAct);
        }

    } catch (IOException e) {
        throw new RuntimeException(e);
    }
}
```

Note: This is identical to Task 4's version but WITHOUT the direct helper calls — the profile steps now handle RestoreCheckpoint, PositionPlayer, FinalizeCamera, InitLevelEvents, and ResetSidekick.

**Step 2: Run full test suite — this is the critical validation**

Run: `mvn test`
Expected: All 1510 tests pass. Level entry is now fully profile-driven.

**Step 3: Run S3K-specific tests**

Run: `mvn test -Dtest="com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading,sonic3k.com.openggf.game.TestSonic3kBootstrapResolver"`
Expected: All pass.

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "feat: wire loadCurrentLevel() through profile entry steps"
```

---

## Task 10: Remove Dead Code

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

Now that `loadCurrentLevel()` delegates everything to the profile, check for dead methods.

**Step 1: Remove `respawnPlayer()` (no callers)**

Remove:
```java
public void respawnPlayer() {
    loadCurrentLevel(false);
}
```

**Step 2: Remove `initLevelEventsForCurrentZoneAct()` (replaced by `initLevelEvents()`)**

The private method at line 4017 is a duplicate of the new public `initLevelEvents()`. Check for callers:
- If only called from old `loadCurrentLevel()` (now removed), delete it.
- If called elsewhere, have it delegate to `initLevelEvents()`.

**Step 3: Remove convenience wrapper methods that are no longer needed**

Check if `initObjectSystem()`, `initGameState()`, `initArtAndPlayer()`, `initGameModuleAndAudio()` have external callers. If only called from old fallback paths that no longer exist, remove them.

**Step 4: Run all tests**

Run: `mvn test`
Expected: All 1510 tests pass.

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: remove dead methods replaced by profile entry steps"
```

---

## Task 11: Update Cross-Game Profile Tests

**Files:**
- Modify: `src/test/java/com/openggf/game/TestGameModuleProfiles.java`

**Step 1: Check if TestGameModuleProfiles has step count assertions**

Search for assertions about `levelLoadSteps` size and update any that hardcode 13 to account for mode-dependent step counts. Add a test that all three games return 18 steps for LEVEL_START:

```java
@Test
public void allGamesReturn18StepsForLevelStart() {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.LEVEL_START);

    assertEquals(18, new Sonic1LevelInitProfile().levelLoadSteps(ctx).size());
    assertEquals(18, new Sonic2LevelInitProfile().levelLoadSteps(ctx).size());
    assertEquals(18, new Sonic3kLevelInitProfile().levelLoadSteps(ctx).size());
}

@Test
public void allGamesReturn13StepsForFull() {
    LevelLoadContext ctx = new LevelLoadContext();
    ctx.setLoadMode(LevelLoadMode.FULL);

    assertEquals(13, new Sonic1LevelInitProfile().levelLoadSteps(ctx).size());
    assertEquals(13, new Sonic2LevelInitProfile().levelLoadSteps(ctx).size());
    assertEquals(13, new Sonic3kLevelInitProfile().levelLoadSteps(ctx).size());
}
```

**Step 2: Run all tests**

Run: `mvn test`
Expected: All pass.

**Step 3: Commit**

```bash
git add src/test/java/com/openggf/game/TestGameModuleProfiles.java
git commit -m "test: add cross-game step count assertions for LEVEL_START and FULL modes"
```

---

## Task 12: Update HeadlessTestFixture to Use Shared Entry Helpers

**Files:**
- Modify: `src/test/java/com/openggf/tests/HeadlessTestFixture.java`
- Modify: `src/test/java/com/openggf/tests/SharedLevel.java`

The per-test fixture currently reimplements camera bounds, camera snap, and level event init inline. Now that `LevelManager` exposes `finalizeCamera()`, `initLevelEvents()`, and `resetSidekick()`, the fixture should call those instead. This ensures headless tests exercise the same code paths as production level entry.

### Current fixture `build()` (lines 169-240) vs production parity

| Concern | Current fixture code | Production helper | Change |
|---------|---------------------|-------------------|--------|
| Camera bounds | Manual `camera.setMinX/Max/Y()` from level | `finalizeCamera()` sets bounds + snaps twice + vertical wrap | **Replace** with `finalizeCamera()` |
| Camera snap | Single `camera.updatePosition(true)` | `finalizeCamera()` does two snaps (pre-bounds + post-bounds) | Covered by replacement |
| Vertical wrap | Not set | `finalizeCamera()` sets `verticalWrapEnabled` | Covered by replacement |
| Level events | Optional `.withLevelEvents()` → manual `lep.initLevel()` | `initLevelEvents()` | **Replace** with `initLevelEvents()` |
| GroundSensor | Manual `GroundSensor.setLevelManager()` | Already wired during profile steps | Keep (needed for per-test re-wiring) |

**Step 1: Update `HeadlessTestFixture.Builder.build()`**

Replace the inline camera bounds + snap + level events with the shared helpers:

```java
public HeadlessTestFixture build() {
    if (sharedLevel == null && zone < 0) {
        throw new IllegalStateException(
                "HeadlessTestFixture.Builder requires either withSharedLevel() or withZoneAndAct() before build()");
    }

    // 1. Reset transient per-test state
    TestEnvironment.resetPerTest();

    // 2. Determine character code
    String charCode;
    if (sharedLevel != null) {
        charCode = sharedLevel.mainCharCode();
    } else {
        charCode = SonicConfigurationService.getInstance()
                .getString(SonicConfiguration.MAIN_CHARACTER_CODE);
    }

    // 3. Create sprite at start position
    Sonic sprite = new Sonic(charCode, startX, startY);

    // 4. Register with SpriteManager and Camera
    SpriteManager sm = SpriteManager.getInstance();
    sm.addSprite(sprite);
    Camera camera = Camera.getInstance();
    camera.setFocusedSprite(sprite);
    camera.setFrozen(false);

    // 5. Wire GroundSensor (needed per-test because resetPerTest clears collision)
    GroundSensor.setLevelManager(LevelManager.getInstance());

    // 6. Camera bounds, snap, and vertical wrap — use the same helper as production
    LevelManager.getInstance().finalizeCamera();

    // 7. Determine effective zone/act for level events
    int effectiveZone;
    int effectiveAct;
    if (sharedLevel != null) {
        effectiveZone = sharedLevel.zone();
        effectiveAct = sharedLevel.act();
    } else {
        effectiveZone = zone;
        effectiveAct = act;
    }

    // 8. Initialize level events — use the same helper as production.
    // Always init when zone is known (production always does).
    // The .withLevelEvents() flag is kept for backward compat but
    // defaults to true when a zone is available.
    if (levelEvents && effectiveZone >= 0) {
        LevelManager.getInstance().initLevelEvents();
    }

    // 9. Create context and runner
    GameContext context = GameContext.production();
    HeadlessTestRunner runner = new HeadlessTestRunner(sprite);

    return new HeadlessTestFixture(context, runner, sprite);
}
```

Key changes from the original:
- **Removed:** Manual `camera.setMinX/MaxX/MinY/MaxY()` from level (5 lines)
- **Removed:** Manual `camera.updatePosition(true)` (1 line)
- **Added:** `LevelManager.getInstance().finalizeCamera()` (1 line) — sets bounds, snaps camera twice, sets vertical wrap
- **Changed:** Level events call uses `LevelManager.getInstance().initLevelEvents()` instead of manually getting the provider

**Step 2: Update `SharedLevel.load()` to use `finalizeCamera()`**

Replace the manual camera bounds + snap in `SharedLevel.load()` (lines 86-93):

```java
public static SharedLevel load(SonicGame game, int zone, int act) throws IOException {
    GraphicsManager.getInstance().initHeadless();

    SonicConfigurationService cs = SonicConfigurationService.getInstance();
    String mainCharCode = cs.getString(SonicConfiguration.MAIN_CHARACTER_CODE);

    Sonic temp = new Sonic(mainCharCode, (short) 0, (short) 0);
    SpriteManager.getInstance().addSprite(temp);
    Camera camera = Camera.getInstance();
    camera.setFocusedSprite(temp);
    camera.setFrozen(false);

    LevelManager lm = LevelManager.getInstance();
    lm.loadZoneAndAct(zone, act);
    GroundSensor.setLevelManager(lm);

    // Use the same camera finalization as production level entry.
    // loadZoneAndAct() -> loadCurrentLevel() already calls finalizeCamera()
    // via profile steps, but SharedLevel creates a fresh sprite BEFORE load,
    // so the profile's FinalizeCamera step ran on the pre-load temp sprite.
    // After load completes, the camera is already correctly bounded.
    // We still need to snap camera to the current sprite position.
    camera.updatePosition(true);

    return new SharedLevel(lm.getCurrentLevel(), game, zone, act, mainCharCode);
}
```

Note: `SharedLevel.load()` calls `loadZoneAndAct()` which now calls `loadCurrentLevel()` which runs the full 18 profile steps including `FinalizeCamera`. The manual bounds code in `SharedLevel` was redundant — the profile step already set them. We keep a single `updatePosition(true)` as a safety snap after everything is loaded.

**Step 3: Run full test suite**

Run: `mvn test`
Expected: All 1510 tests pass. If any fail, the camera setup in `finalizeCamera()` doesn't match the manual code. Diagnose: compare `finalizeCamera()` behavior (sets bounds from `getCurrentLevel()`, snaps twice) with the old inline code (set bounds from `level`, snap once). If a test fails, it proves the gap existed and the fix is correct.

**Step 4: Commit**

```bash
git add src/test/java/com/openggf/tests/HeadlessTestFixture.java src/test/java/com/openggf/tests/SharedLevel.java
git commit -m "refactor: headless test fixture uses shared entry helpers for production parity"
```

---

## Task 13: Full Validation Sweep

**Step 1: Run complete test suite**

```bash
mvn test 2>&1 | tail -5
```
Expected: All 1510+ tests pass, 0 failures, 0 errors.

**Step 2: Run game-specific test suites**

S1: `mvn test -Dtest="com.openggf.tests.TestSonic1*"`
S2: `mvn test -Dtest="com.openggf.tests.Test*Headless*"`
S3K: `mvn test -Dtest="com.openggf.tests.TestS3k*,com.openggf.tests.TestSonic3kLevelLoading,sonic3k.com.openggf.game.Test*"`

**Step 3: Run profile-specific tests**

```bash
mvn test -Dtest="com.openggf.game.sonic2.TestSonic2LevelInitProfile,com.openggf.game.sonic1.TestSonic1LevelInitProfile,com.openggf.game.sonic3k.TestSonic3kLevelInitProfile,com.openggf.game.TestGameModuleProfiles,com.openggf.game.TestLevelLoadContext"
```

All must pass.

---

## Summary of Changes

| Task | What | Files | Test Impact |
|------|------|-------|-------------|
| 1 | Add `LEVEL_START` mode | LevelLoadMode + test | New enum value |
| 2 | Context snapshot fields | LevelLoadContext + test | New fields |
| 3 | Extract 5 entry helpers | LevelManager | Pure extraction |
| 4 | Refactor loadCurrentLevel to use helpers | LevelManager | Same behavior |
| 5 | loadLevel(int, ctx) overload | LevelManager | New overload |
| 6 | S2 profile: 13→18 steps (LEVEL_START) | S2 profile + test | Mode-conditional steps |
| 7 | S1 profile: 13→18 steps (LEVEL_START) | S1 profile + test | Mode-conditional steps |
| 8 | S3K profile: 13→18 steps (LEVEL_START) | S3K profile + test | Mode-conditional steps |
| 9 | Wire loadCurrentLevel through profile | LevelManager | **Critical** — entry via profile |
| 10 | Remove dead code | LevelManager | Cleanup |
| 11 | Cross-game profile tests | TestGameModuleProfiles | Assertions |
| 12 | Headless test parity | HeadlessTestFixture + SharedLevel | **Tests use same code paths** |
| 13 | Full validation | — | All 1510+ green |

## Profile Step Coverage (Post-Implementation)

| Step | FULL (13) | LEVEL_START (18) | SEAMLESS_RELOAD |
|------|-----------|------------------|-----------------|
| InitGameModule | Y | Y | Y |
| InitAudio | Y | Y | Y |
| LoadLevelData | Y | Y | Y |
| InitAnimatedContent | Y | Y | Y |
| InitObjectManager | Y | Y | Y |
| InitCameraBounds | Y | Y | Y |
| InitGameplayState | Y | Y | Y |
| InitRings | Y | Y | Y |
| InitZoneFeatures | Y | Y | Y |
| InitArt | Y | Y | Y |
| InitPlayerAndCheckpoint | Y | Y | S3K: skip |
| InitWater | Y | Y | Y |
| InitBackgroundRenderer | Y | Y | Y |
| RestoreCheckpoint | — | Y | — |
| PositionPlayer | — | Y | — |
| FinalizeCamera | — | Y | — |
| InitLevelEvents | — | Y | — |
| ResetSidekick | — | Y | — |
