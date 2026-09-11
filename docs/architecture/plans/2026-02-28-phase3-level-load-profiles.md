# Phase 3: Wire Level Load Profiles Into Production Level Loading

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Populate `levelLoadSteps()` in each game's `LevelInitProfile` with the ROM-aligned initialization sequences, then route production `LevelManager.loadLevel()` through the profile step list.

**Architecture:** The current level loading spans 3 layers:
1. `LevelManager.loadLevel()` — game-agnostic orchestrator (audio, object manager, rings, zone features, camera, etc.)
2. `Game.loadLevel()` — game-specific resource loading (ROM parsing, address resolution, resource plan building)
3. Level constructor (`Sonic2Level`, `Sonic1Level`, `Sonic3kLevel`) — ROM decompression into level data structures

Phase 3 decomposes layers 1+2 into ordered `InitStep` lists declared per-game. Layer 3 (ROM decompression) stays as-is inside the Level constructors — these are "atomic" operations that correspond to multi-step ROM routines but execute as single Java constructor calls.

**Key constraint:** The current code works and passes 1432 tests. The refactoring must be zero-behavior-change: same operations, same order, just routed through the profile system.

**Approach:** Start with S2 (most mature, best tested), then S1, then S3K. Each game follows the same pattern:
1. Extract `LevelManager.loadLevel()` operations into named, game-aware helper methods
2. Declare `levelLoadSteps()` in the game's profile, calling those helpers
3. Replace `LevelManager.loadLevel()` body with profile step execution
4. Validate all tests pass

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, Maven

**Design doc:** `docs/architecture/designs/2026-02-27-rom-driven-init-profiles-design.md` (Phases A-J for S2, A-L for S1, A-Q for S3K)

---

## Architecture Analysis

### Current `LevelManager.loadLevel()` Decomposition (lines 567-675)

The orchestrator performs these operations in order:

| # | Operation | ROM Phase (S2) | Code Location |
|---|-----------|---------------|---------------|
| 1 | Get ROM, load parallax data | Phase E | `parallaxManager.load(rom)` |
| 2 | Get game module, refresh zone list | — | `GameModuleRegistry.getCurrent()` |
| 3 | Create Game instance | — | `gameModule.createGame(rom)` |
| 4 | Configure audio manager | Phase C | `audioManager.setAudioProfile/setRom/setSoundMap/resetRingSound` |
| 5 | Play level music | Phase C (#21) | `audioManager.playMusic(game.getMusicId(levelIndex))` |
| 6 | Delegate to Game.loadLevel() | Phases E-F | `game.loadLevel(levelIndex)` → Level constructor |
| 7 | Cache level dimensions | — | `cacheLevelDimensions()` |
| 8 | Set tilemap dirty flags | Phase E (#33) | `backgroundTilemapDirty = true`, etc. |
| 9 | Init animated patterns/palettes | Phase I (#49) | `initAnimatedPatterns()`, `initAnimatedPalettes()` |
| 10 | Create ObjectManager | Phase G (#37,44) | `new ObjectManager(...)` |
| 11 | Wire CollisionSystem | Phase F (#34-35) | `CollisionSystem.getInstance().setObjectManager(objectManager)` |
| 12 | Reset camera bounds | Phase E (#26) | `camera.setMinX/setMaxX`, `objectManager.reset()` |
| 13 | Game-specific onLevelLoad | Phase J (#36) | `gameModule.onLevelLoad()` |
| 14 | Create RingManager | Phase I (#45) | `new RingManager(...)` |
| 15 | Init zone features | Phase G (#39-40) | `zoneFeatureProvider.initZoneFeatures(...)` |
| 16 | Load object art | Phase C (#8) | `initObjectArt()` |
| 17 | Load player art | Phase C (#8) | `initPlayerSpriteArt()` |
| 18 | Reset player state | Phase G (#37) | `resetPlayerState()` |
| 19 | Init checkpoint state | Phase H (#41) | `checkpointState.clear()` |
| 20 | Create level gamestate | Phase H (#41) | `gameModule.createLevelState()` |
| 21 | Init water system | Phase B (#13,18) | `WaterSystem.getInstance().loadForLevel(...)` |
| 22 | Pre-allocate BG FBO | — | `bgRenderer.ensureCapacity(...)` |

### Why NOT Decompose Into 57/44/65 Fine-Grained Steps

The design doc's 57 S2 steps represent individual ROM instructions. Many of these map to a single Java method call, or are hardware-specific no-ops (VDP registers, DMA queues). Decomposing `LevelManager.loadLevel()` to that granularity would mean:

1. Breaking apart methods that currently work as atomic units (e.g., the Level constructor does steps 26-35 in one call)
2. Creating artificial step boundaries that don't improve testability
3. Massive refactoring risk for zero behavioral benefit in this phase

**Phase 3 strategy:** Group ROM phases into ~10-12 coarse-grained InitSteps that match the natural boundaries in the existing code. Each step maps to a specific ROM phase range. Phase 4 can later split steps where finer control is needed.

---

## Prerequisite: LevelLoadContext

All load steps need shared state (ROM, level index, zone/act, the loaded Level object, etc.). Rather than threading parameters through each `Runnable`, we introduce a lightweight context object that steps populate and read.

---

### Task 1: Create LevelLoadContext

**Files:**
- Create: `src/main/java/com/openggf/game/LevelLoadContext.java`
- Test: `src/test/java/com/openggf/game/TestLevelLoadContext.java`

A mutable context that accumulates state as load steps execute. Each InitStep's Runnable captures the context reference.

**Step 1: Write the failing test**

```java
package com.openggf.game;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class TestLevelLoadContext {

    @Test
    public void contextStartsEmpty() {
        var ctx = new LevelLoadContext();
        assertNull(ctx.getRom());
        assertEquals(-1, ctx.getLevelIndex());
        assertNull(ctx.getLevel());
    }

    @Test
    public void contextAccumulatesState() {
        var ctx = new LevelLoadContext();
        ctx.setLevelIndex(5);
        assertEquals(5, ctx.getLevelIndex());
    }
}
```

**Step 2: Run test to verify it fails**

Run: `mvn test -Dtest=com.openggf.game.TestLevelLoadContext -pl .`
Expected: FAIL — class not found

**Step 3: Write minimal implementation**

```java
package com.openggf.game;

import com.openggf.data.Rom;
import com.openggf.level.Level;

/**
 * Mutable context accumulated during level load step execution.
 * <p>
 * Each {@link InitStep} in a {@link LevelInitProfile#levelLoadSteps()} list
 * captures a reference to this context. Steps populate fields as they execute;
 * later steps read values set by earlier ones. This replaces the local variables
 * that were previously threaded through {@code LevelManager.loadLevel()}.
 * <p>
 * The context is created fresh for each level load and discarded afterward.
 */
public class LevelLoadContext {
    private Rom rom;
    private int levelIndex = -1;
    private int zone = -1;
    private int act = -1;
    private Level level;
    private GameModule gameModule;

    // Getters and setters for all fields
    public Rom getRom() { return rom; }
    public void setRom(Rom rom) { this.rom = rom; }
    public int getLevelIndex() { return levelIndex; }
    public void setLevelIndex(int levelIndex) { this.levelIndex = levelIndex; }
    public int getZone() { return zone; }
    public void setZone(int zone) { this.zone = zone; }
    public int getAct() { return act; }
    public void setAct(int act) { this.act = act; }
    public Level getLevel() { return level; }
    public void setLevel(Level level) { this.level = level; }
    public GameModule getGameModule() { return gameModule; }
    public void setGameModule(GameModule gameModule) { this.gameModule = gameModule; }
}
```

**Step 4: Run test to verify it passes**

Run: `mvn test -Dtest=com.openggf.game.TestLevelLoadContext -pl .`
Expected: PASS

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/game/LevelLoadContext.java src/test/java/com/openggf/game/TestLevelLoadContext.java
git commit -m "feat: add LevelLoadContext for shared state across load steps"
```

---

### Task 2: Add levelLoadSteps() Builder Hook to AbstractLevelInitProfile

**Files:**
- Modify: `src/main/java/com/openggf/game/AbstractLevelInitProfile.java`
- Modify: `src/main/java/com/openggf/game/LevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/TestGameModuleProfiles.java`

The current `levelLoadSteps()` returns an empty list. Change the signature to accept a `LevelLoadContext` so that steps can capture context references. Add a new overload or change the existing method.

**Design decision:** Since `levelLoadSteps()` is defined on the interface, and load steps need a context, we have two options:
- (A) `levelLoadSteps(LevelLoadContext ctx)` — context passed at call time
- (B) Keep `levelLoadSteps()` parameterless, have profiles hold a mutable context field

**Choose (A)** — stateless profiles are simpler and more testable. The context is created by the caller (LevelManager) and passed in.

**Step 1: Update LevelInitProfile interface**

Change `levelLoadSteps()` to `levelLoadSteps(LevelLoadContext ctx)`. Update `AbstractLevelInitProfile` and `EMPTY` accordingly.

```java
// In LevelInitProfile.java
List<InitStep> levelLoadSteps(LevelLoadContext ctx);
```

**Step 2: Update AbstractLevelInitProfile**

```java
// Change the non-final method:
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    return List.of();
}
```

And update the EMPTY constant's override too.

**Step 3: Update tests**

Update `TestGameModuleProfiles` to pass `new LevelLoadContext()` where `levelLoadSteps()` is called.

**Step 4: Run all tests**

Run: `mvn test`
Expected: All 1432 tests pass (no behavior change — still returns empty list)

**Step 5: Commit**

```bash
git add -u
git commit -m "refactor: add LevelLoadContext parameter to levelLoadSteps()"
```

---

### Task 3: Extract LevelManager.loadLevel() Into Named Helper Methods

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

This is the key refactoring step. Extract the body of `loadLevel()` into well-named package-private methods that each correspond to a ROM phase group. The current method body stays unchanged — we just extract methods so the profiles can call them.

**Step 1: Extract methods from loadLevel() body**

Extract these methods from `LevelManager.loadLevel(int levelIndex)`:

```java
// Phase A-B: Audio & module setup
void initGameModuleAndAudio(int levelIndex) throws IOException {
    // Lines 569-582: ROM, parallax, game module, audio setup, play music
}

// Phase E-F: Level data loading (delegates to Game.loadLevel)
Level loadLevelData(int levelIndex) throws IOException {
    // Lines 583-596: game.loadLevel(), cache dimensions, set dirty flags
}

// Phase I: Animated patterns
void initAnimatedContent() {
    // Lines 597-598: initAnimatedPatterns(), initAnimatedPalettes()
}

// Phase G: Object system
void initObjectSystem() throws IOException {
    // Lines 599-613: ObjectManager creation, CollisionSystem wiring, camera bounds
}

// Phase H: Game state
void initGameState() {
    // Lines 615-636: onLevelLoad, ringManager, checkpoint, level gamestate
}

// Phase G: Zone features
void initZoneFeatures() throws IOException {
    // Lines 621-627: zoneFeatureProvider init
}

// Phase C: Art loading
void initArtAndPlayer() {
    // Lines 628-630: object art, player art, player state reset
}

// Phase B: Water
void initWater() {
    // Lines 643-648: water system
}

// Engine-specific: FBO
void initBackgroundRenderer() {
    // Lines 652-667: BG FBO pre-allocation
}
```

**Step 2: Replace loadLevel() body with calls to extracted methods**

```java
public void loadLevel(int levelIndex) throws IOException {
    try {
        initGameModuleAndAudio(levelIndex);
        level = loadLevelData(levelIndex);
        initAnimatedContent();
        initObjectSystem();
        initGameState();
        initArtAndPlayer();
        initWater();
        initBackgroundRenderer();
    } catch (IOException e) {
        // ... existing error handling
    }
}
```

**Step 3: Run all tests**

Run: `mvn test`
Expected: All 1432 tests pass — pure extract-method refactoring, zero behavior change.

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: extract loadLevel() into named helper methods for profile integration"
```

---

### Task 4: Implement Sonic2LevelInitProfile.levelLoadSteps()

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/TestGameModuleProfiles.java`

Populate the S2 profile's `levelLoadSteps()` with InitSteps that call the extracted LevelManager helpers. Each step maps to a ROM phase group from the design doc.

**Step 1: Implement levelLoadSteps()**

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    return List.of(
        // Phase A-C: Audio setup, fade out, PLC clear, music
        new InitStep("InitModuleAndAudio",
            "S2 Phase A-C: Pal_FadeToBlack, ClearPLC, LoadTitleCard, Level_SetPlayerMode, PlayMusic",
            () -> { try { lm.initGameModuleAndAudio(ctx.getLevelIndex()); } catch (IOException e) { throw new RuntimeException(e); } }),

        // Phase E-F: Level geometry, tiles, blocks, chunks, collision
        new InitStep("LoadLevelData",
            "S2 Phase E-F: LevelSizeLoad, DeformBgLayer, LoadZoneTiles, loadZoneBlockMaps, ConvertCollisionArray, LoadCollisionIndexes",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new RuntimeException(e); } }),

        // Phase E: Animated patterns
        new InitStep("InitAnimatedContent",
            "S2 Phase E (#32): LoadAnimatedBlocks",
            () -> lm.initAnimatedContent()),

        // Phase G: Object & ring spawning
        new InitStep("InitObjectSystem",
            "S2 Phase G: InitPlayers, WaterEffects, zone-specific objects (CPZ pylon, OOZ oil)",
            () -> { try { lm.initObjectSystem(); } catch (IOException e) { throw new RuntimeException(e); } }),

        // Phase H: Game state init
        new InitStep("InitGameState",
            "S2 Phase H: Clear rings/timer/lives, OscillateNumInit, HUD flags",
            () -> lm.initGameState()),

        // Phase C: Art loading
        new InitStep("InitArtAndPlayer",
            "S2 Phase C (#8-9): Zone PLC, Standard PLC, character palette",
            () -> lm.initArtAndPlayer()),

        // Phase B: Water initialization
        new InitStep("InitWater",
            "S2 Phase B (#13,18): Water flag check, H-INT, WaterHeight table",
            () -> lm.initWater()),

        // Engine-specific: GPU resource pre-allocation
        new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO at maximum required size",
            () -> lm.initBackgroundRenderer())
    );
}
```

**Step 2: Add test for S2 load steps count**

```java
@Test
public void sonic2ProfileHasLoadSteps() {
    Sonic2LevelInitProfile profile = new Sonic2LevelInitProfile();
    List<InitStep> steps = profile.levelLoadSteps(new LevelLoadContext());
    assertFalse("S2 load steps should not be empty", steps.isEmpty());
    assertEquals("S2 should have 8 load step groups", 8, steps.size());
    assertEquals("InitModuleAndAudio", steps.get(0).name());
    assertEquals("InitBackgroundRenderer", steps.get(steps.size() - 1).name());
}
```

**Step 3: Run all tests**

Run: `mvn test`
Expected: All tests pass — the steps are declared but not yet executed by LevelManager.

**Step 4: Commit**

```bash
git add -u
git commit -m "feat: implement Sonic2LevelInitProfile.levelLoadSteps() with 8 ROM-aligned step groups"
```

---

### Task 5: Route LevelManager.loadLevel() Through Profile Steps

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

This is the critical wiring step. Replace the direct helper calls in `loadLevel()` with profile step execution.

**Step 1: Modify loadLevel() to use profile**

```java
public void loadLevel(int levelIndex) throws IOException {
    try {
        LevelInitProfile profile = GameModuleRegistry.getCurrent().getLevelInitProfile();
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setLevelIndex(levelIndex);

        List<InitStep> steps = profile.levelLoadSteps(ctx);

        if (steps.isEmpty()) {
            // Fallback for EMPTY profile or games not yet wired
            initGameModuleAndAudio(levelIndex);
            level = loadLevelData(levelIndex);
            initAnimatedContent();
            initObjectSystem();
            initGameState();
            initArtAndPlayer();
            initWater();
            initBackgroundRenderer();
        } else {
            for (InitStep step : steps) {
                step.execute();
            }
            // The LoadLevelData step stores the result in ctx
            if (ctx.getLevel() != null) {
                level = ctx.getLevel();
            }
        }
    } catch (IOException e) {
        LOGGER.log(SEVERE, "Failed to load level " + levelIndex, e);
        throw e;
    } catch (Exception e) {
        LOGGER.log(SEVERE, "Unexpected error while loading level " + levelIndex, e);
        throw new IOException("Failed to load level due to unexpected error.", e);
    }
}
```

**Step 2: Run all tests — this is the critical validation**

Run: `mvn test`
Expected: All 1432 tests pass. S2 levels now load through the profile; S1/S3K fall through the empty-steps fallback.

**Step 3: Run the full headless test suite specifically**

Run: `mvn test -Dtest="com.openggf.tests.Test*Headless*"` and `mvn test -Dtest="com.openggf.tests.TestS3k*"`
Expected: All headless and S3K tests pass.

**Step 4: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "feat: route LevelManager.loadLevel() through profile steps with fallback"
```

---

### Task 6: Implement Sonic1LevelInitProfile.levelLoadSteps()

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/TestGameModuleProfiles.java`

Same pattern as S2: declare InitSteps that call the same LevelManager helpers. S1's ROM sequence differs in ordering (object placement before game state clear), but the existing Java code already handles this correctly — the helpers don't enforce S2-specific ordering.

**Step 1: Implement levelLoadSteps()**

S1 uses the same helper methods since `LevelManager.loadLevel()` is game-agnostic. The profile just declares the same steps with S1-specific ROM routine descriptions.

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    return List.of(
        new InitStep("InitModuleAndAudio",
            "S1 Phase A-D: bgm_Fade, ClearPLC, PaletteFadeOut, NemDec Nem_TitleCard, AddPLC, PalLoad palid_Sonic, PlayMusic",
            () -> { try { lm.initGameModuleAndAudio(ctx.getLevelIndex()); } catch (IOException e) { throw new RuntimeException(e); } }),

        new InitStep("LoadLevelData",
            "S1 Phase G-H: LevelSizeLoad, DeformLayers, LevelDataLoad, ConvertCollisionArray, ColIndexLoad",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new RuntimeException(e); } }),

        new InitStep("InitAnimatedContent",
            "S1 Phase G: LoadAnimatedBlocks (S1 loads animation scripts during LevelDataLoad)",
            () -> lm.initAnimatedContent()),

        new InitStep("InitObjectSystem",
            "S1 Phase I-J: LZWaterFeatures, Sonic spawn, ObjPosLoad, ExecuteObjects",
            () -> { try { lm.initObjectSystem(); } catch (IOException e) { throw new RuntimeException(e); } }),

        new InitStep("InitGameState",
            "S1 Phase K: Clear game state, OscillateNumInit, HUD flags",
            () -> lm.initGameState()),

        new InitStep("InitArtAndPlayer",
            "S1 Phase A (#6-7): Zone PLC, plcid_Main2",
            () -> lm.initArtAndPlayer()),

        new InitStep("InitWater",
            "S1 Phase C: LZ water check, WaterHeight table, Water_flag",
            () -> lm.initWater()),

        new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO",
            () -> lm.initBackgroundRenderer())
    );
}
```

**Step 2: Add test**

```java
@Test
public void sonic1ProfileHasLoadSteps() {
    Sonic1LevelInitProfile profile = new Sonic1LevelInitProfile();
    List<InitStep> steps = profile.levelLoadSteps(new LevelLoadContext());
    assertFalse("S1 load steps should not be empty", steps.isEmpty());
    assertEquals("S1 should have 8 load step groups", 8, steps.size());
}
```

**Step 3: Run all tests**

Run: `mvn test`
Expected: All tests pass. S1 levels now load through the profile.

**Step 4: Commit**

```bash
git add -u
git commit -m "feat: implement Sonic1LevelInitProfile.levelLoadSteps() with 8 ROM-aligned step groups"
```

---

### Task 7: Implement Sonic3kLevelInitProfile.levelLoadSteps()

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/TestGameModuleProfiles.java`

S3K uses the same LevelManager helpers. S3K-specific operations (AIZ intro overlay, bootstrap resolver, KosinskiM queues) are handled inside `Sonic3k.loadLevel()` which is called by the `LoadLevelData` step.

**Step 1: Implement levelLoadSteps()**

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    return List.of(
        new InitStep("InitModuleAndAudio",
            "S3K Phase A-F: cmd_FadeOut, Pal_FadeToBlack, Clear_Nem_Queue, LevelLoad_ActiveCharacter, LoadPalette_Immediate, Play_Music",
            () -> { try { lm.initGameModuleAndAudio(ctx.getLevelIndex()); } catch (IOException e) { throw new RuntimeException(e); } }),

        new InitStep("LoadLevelData",
            "S3K Phase H-K: Get_LevelSizeStart, DeformBgLayer, LoadLevelLoadBlock, LoadLevelLoadBlock2, j_LevelSetup, LoadSolids",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new RuntimeException(e); } }),

        new InitStep("InitAnimatedContent",
            "S3K Phase J (#37): Animate_Init (zone-specific animation counter initialization)",
            () -> lm.initAnimatedContent()),

        new InitStep("InitObjectSystem",
            "S3K Phase O-P: SpawnLevelMainSprites, Load_Sprites, Load_Rings, Process_Sprites",
            () -> { try { lm.initObjectSystem(); } catch (IOException e) { throw new RuntimeException(e); } }),

        new InitStep("InitGameState",
            "S3K Phase N: Clear game state, OscillateNumInit, Level_started_flag, HUD flags",
            () -> lm.initGameState()),

        new InitStep("InitArtAndPlayer",
            "S3K Phase C (#12-14): Load_PLC zone, character PLCs, standard PLCs",
            () -> lm.initArtAndPlayer()),

        new InitStep("InitWater",
            "S3K Phase E (#22): CheckLevelForWater, StartingWaterHeights",
            () -> lm.initWater()),

        new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO for AIZ intro ocean-to-beach transition",
            () -> lm.initBackgroundRenderer())
    );
}
```

**Step 2: Add test**

```java
@Test
public void sonic3kProfileHasLoadSteps() {
    Sonic3kLevelInitProfile profile = new Sonic3kLevelInitProfile();
    List<InitStep> steps = profile.levelLoadSteps(new LevelLoadContext());
    assertFalse("S3K load steps should not be empty", steps.isEmpty());
    assertEquals("S3K should have 8 load step groups", 8, steps.size());
}
```

**Step 3: Run all tests (including S3K-specific)**

Run: `mvn test`
Then specifically: `mvn test -Dtest="com.openggf.tests.TestS3kAiz1SkipHeadless,com.openggf.tests.TestSonic3kLevelLoading"`
Expected: All pass.

**Step 4: Commit**

```bash
git add -u
git commit -m "feat: implement Sonic3kLevelInitProfile.levelLoadSteps() with 8 ROM-aligned step groups"
```

---

### Task 8: Remove Fallback Path From LevelManager.loadLevel()

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

Now that all 3 games have load steps, remove the fallback path. The `EMPTY` profile still returns an empty list, but no production code should hit it.

**Step 1: Remove the if/else fallback**

```java
public void loadLevel(int levelIndex) throws IOException {
    try {
        LevelInitProfile profile = GameModuleRegistry.getCurrent().getLevelInitProfile();
        LevelLoadContext ctx = new LevelLoadContext();
        ctx.setLevelIndex(levelIndex);

        List<InitStep> steps = profile.levelLoadSteps(ctx);
        if (steps.isEmpty()) {
            throw new IllegalStateException(
                "No level load steps defined for " + GameModuleRegistry.getCurrent().getClass().getSimpleName() +
                ". All game modules must implement levelLoadSteps().");
        }
        for (InitStep step : steps) {
            step.execute();
        }
        if (ctx.getLevel() != null) {
            level = ctx.getLevel();
        }
    } catch (IOException e) {
        LOGGER.log(SEVERE, "Failed to load level " + levelIndex, e);
        throw e;
    } catch (Exception e) {
        LOGGER.log(SEVERE, "Unexpected error while loading level " + levelIndex, e);
        throw new IOException("Failed to load level due to unexpected error.", e);
    }
}
```

**Step 2: Run full test suite**

Run: `mvn test`
Expected: All 1432 tests pass.

**Step 3: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: remove fallback path from loadLevel(), require profile steps"
```

---

### Task 9: Enhance LevelLoadContext With Per-Step Logging

**Files:**
- Modify: `src/main/java/com/openggf/game/LevelLoadContext.java`
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

Add timing and logging to each step execution for debugging. This makes the profile-driven loading observable.

**Step 1: Add step execution logging to LevelManager**

```java
for (InitStep step : steps) {
    long start = System.nanoTime();
    step.execute();
    long elapsed = (System.nanoTime() - start) / 1_000_000;
    LOGGER.fine(() -> String.format("  [%s] %dms — %s", step.name(), elapsed, step.romRoutine()));
}
```

**Step 2: Run tests**

Run: `mvn test`
Expected: All pass.

**Step 3: Commit**

```bash
git add -u
git commit -m "feat: add per-step timing and logging to profile-driven level loading"
```

---

### Task 10: Full Validation Sweep

**Step 1: Run complete test suite**

```bash
mvn test 2>&1 | tail -20
```

Expected: All tests pass (1432+).

**Step 2: Run S1-specific tests**

```bash
mvn test -Dtest="com.openggf.tests.TestSonic1*"
```

**Step 3: Run S2-specific tests**

```bash
mvn test -Dtest="com.openggf.tests.Test*Headless*"
```

**Step 4: Run S3K-specific tests**

```bash
mvn test -Dtest="com.openggf.tests.TestS3k*,com.openggf.tests.TestSonic3k*,sonic3k.com.openggf.game.Test*"
```

**Step 5: Run physics tests**

```bash
mvn test -Dtest="com.openggf.game.Test*"
```

**Step 6: Verify profile test**

```bash
mvn test -Dtest="com.openggf.game.TestGameModuleProfiles"
```

All must pass. If any fail, diagnose and fix before proceeding.

**Step 7: Commit (if any fixes were needed)**

---

## Summary of Changes

| Task | What | Files | Test Impact |
|------|------|-------|-------------|
| 1 | LevelLoadContext record | +1 new, +1 test | New test only |
| 2 | levelLoadSteps(ctx) parameter | 4 modified | Signature change, no behavior |
| 3 | Extract LevelManager helpers | 1 modified | Pure refactoring |
| 4 | S2 load steps | 1 modified, 1 test | Steps declared, not yet executed |
| 5 | Wire loadLevel() through profile | 1 modified | **Critical** — S2 levels through profile |
| 6 | S1 load steps | 1 modified, 1 test | S1 levels through profile |
| 7 | S3K load steps | 1 modified, 1 test | S3K levels through profile |
| 8 | Remove fallback | 1 modified | Enforce profile usage |
| 9 | Logging | 2 modified | Observable loading |
| 10 | Validation | — | Full sweep |

## ROM Phase Coverage (Post-Phase 3)

After this plan, the `levelLoadSteps()` cover these ROM phases as coarse groups:

| Step Group | S2 Phases | S1 Phases | S3K Phases |
|------------|-----------|-----------|------------|
| InitModuleAndAudio | A-C (#1-21) | A-D (#1-15) | A-F (#1-25) |
| LoadLevelData | E-F (#26-35) | G-H (#20-26) | H-K (#30-38) |
| InitAnimatedContent | E (#32) | G (part) | J (#37) |
| InitObjectSystem | G (#36-40) | I-J (#27-35) | O-P (#47-52) |
| InitGameState | H (#41-43) | K (#36-38) | N (#43-46) |
| InitArtAndPlayer | C (#8-9) | A (#6-7) | C (#12-14) |
| InitWater | B (#13,18) | C (#11) | E (#22) |
| InitBackgroundRenderer | — | — | — |

Phase 4 will split these groups into finer-grained steps where needed (e.g., separating title card animation, fade-in, control unlock).
