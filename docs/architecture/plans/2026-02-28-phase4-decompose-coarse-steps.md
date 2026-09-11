# Phase 4: Decompose Coarse Init Steps Into Finer-Grained ROM-Aligned Steps

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Split the 8 coarse-grained `levelLoadSteps()` into ~13 finer-grained steps that map more closely to ROM initialization phases, improving observability and ROM alignment.

**Architecture:** Phase 3 extracted 8 LevelManager helper methods and wired them into per-game profiles. Each helper bundles 2-4 distinct ROM phases into a single method. Phase 4 decomposes 4 of those helpers into smaller methods, creating natural split points that match ROM phase boundaries. The remaining 4 helpers (`loadLevelData`, `initAnimatedContent`, `initWater`, `initBackgroundRenderer`) are already well-scoped and stay unchanged.

**Tech Stack:** Java 21, JUnit 5 / Jupiter only, Maven

**Key constraint:** Zero behavior change. Same operations, same order, just finer step boundaries for better logging and ROM traceability. All 1434+ tests must remain green.

---

## Split Plan

| Current Helper (Phase 3) | New Methods (Phase 4) | ROM Phase Alignment |
|---|---|---|
| `initGameModuleAndAudio(int)` | `initGameModule(int)` + `initAudio(int)` | Module setup vs. music play |
| `initObjectSystem()` | `initObjectManager()` + `initCameraBounds()` | Object spawn vs. boundary setup |
| `initGameState()` | `initGameplayState()` + `initRings()` + `initZoneFeatures()` | State clear vs. ring spawn vs. zone objects |
| `initArtAndPlayer()` | `initArt()` + `initPlayerAndCheckpoint()` | Art load vs. player state + checkpoint |

This takes each game from 8 steps to 13 steps.

---

### Task 1: Split `initGameModuleAndAudio` Into `initGameModule` + `initAudio`

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

**Step 1: Extract `initGameModule(int levelIndex)`**

This method handles ROM access, parallax loading, game module creation, and zone list refresh. It does NOT touch audio.

```java
/**
 * Phase A: Initialize ROM access, parallax, game module, and zone registry.
 * <p>
 * S2 Phase A (#1-4): Pal_FadeToBlack, ClearPLC, clear variables.
 * S1 Phase A (#1-4): bgm_Fade, ClearPLC, PaletteFadeOut.
 * S3K Phase A-D (#1-20): cmd_FadeOut, Pal_FadeToBlack, Clear_Nem_Queue, clearRAM.
 */
public void initGameModule(int levelIndex) throws IOException {
    Rom rom = GameServices.rom().getRom();
    parallaxManager.load(rom);
    gameModule = GameModuleRegistry.getCurrent();
    refreshZoneList();
    game = gameModule.createGame(rom);
}
```

**Step 2: Extract `initAudio(int levelIndex)`**

This method configures the audio manager and plays level music.

```java
/**
 * Phase C/F: Configure audio manager and play level music.
 * <p>
 * S2 Phase C (#21): Level_SetPlayerMode, PlayMusic.
 * S1 Phase D (#15): QueueSound1 from MusicList.
 * S3K Phase F (#25): Play_Music from LevelMusic_Playlist.
 */
public void initAudio(int levelIndex) {
    AudioManager audioManager = AudioManager.getInstance();
    audioManager.setAudioProfile(gameModule.getAudioProfile());
    audioManager.setRom(GameServices.rom().getRom());
    audioManager.setSoundMap(game.getSoundMap());
    audioManager.resetRingSound();
    if (!suppressNextMusicChange) {
        audioManager.playMusic(game.getMusicId(levelIndex));
    }
    suppressNextMusicChange = false;
}
```

**Step 3: Update `initGameModuleAndAudio` to delegate**

Keep the original method as a convenience wrapper that calls both (for backward compat with any internal callers):

```java
public void initGameModuleAndAudio(int levelIndex) throws IOException {
    initGameModule(levelIndex);
    initAudio(levelIndex);
}
```

**Step 4: Run tests**

Run: `mvn test`
Expected: All tests pass — pure method extraction, no behavior change.

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: split initGameModuleAndAudio into initGameModule + initAudio"
```

---

### Task 2: Split `initObjectSystem` Into `initObjectManager` + `initCameraBounds`

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

**Step 1: Extract `initObjectManager()`**

Creates the ObjectManager, wires CollisionSystem. Camera stays frozen from previous state.

```java
/**
 * Phase G: Create ObjectManager, wire CollisionSystem.
 * <p>
 * S2 Phase G (#36-38): InitPlayers, WaterEffects, zone-specific objects.
 * S1 Phase I (#28-32): Spawn Sonic, HUD, water surface objects.
 * S3K Phase O (#47-48): SpawnLevelMainSprites, Load_Sprites.
 */
public void initObjectManager() throws IOException {
    Rom rom = GameServices.rom().getRom();
    RomByteReader romReader = RomByteReader.fromRom(rom);
    touchResponseTable = gameModule.createTouchResponseTable(romReader);
    objectManager = new ObjectManager(level.getObjects(),
            gameModule.createObjectRegistry(),
            gameModule.getPlaneSwitcherObjectId(),
            gameModule.getPlaneSwitcherConfig(),
            touchResponseTable);
    CollisionSystem.getInstance().setObjectManager(objectManager);
}
```

**Step 2: Extract `initCameraBounds()`**

Resets camera state and initializes object placement window.

```java
/**
 * Phase E: Reset camera bounds from level geometry.
 * <p>
 * S2 Phase E (#26): LevelSizeLoad (camera min/max X/Y).
 * S1 Phase G (#20): LevelSizeLoad.
 * S3K Phase H (#32): Get_LevelSizeStart.
 */
public void initCameraBounds() {
    Camera camera = Camera.getInstance();
    camera.setFrozen(false);
    camera.setMinX((short) 0);
    camera.setMaxX((short) (level.getMap().getWidth() * blockPixelSize));
    objectManager.reset(camera.getX());
}
```

**Step 3: Update `initObjectSystem` to delegate**

```java
public void initObjectSystem() throws IOException {
    initObjectManager();
    initCameraBounds();
}
```

**Step 4: Run tests**

Run: `mvn test`
Expected: All tests pass.

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: split initObjectSystem into initObjectManager + initCameraBounds"
```

---

### Task 3: Split `initGameState` Into `initGameplayState` + `initRings` + `initZoneFeatures`

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

**Step 1: Extract `initGameplayState()`**

Resets game-specific object state for the new level (OscillateNumInit, CNZ bumper reset, etc.).

```java
/**
 * Phase H/N: Reset game-specific state for new level.
 * <p>
 * S2 Phase H (#41-43): Clear game state, OscillateNumInit, HUD flags.
 * S1 Phase K (#36-38): Clear game state, OscillateNumInit.
 * S3K Phase N (#43-45): Clear game state, OscillateNumInit, Level_started_flag.
 */
public void initGameplayState() {
    gameModule.onLevelLoad();
}
```

**Step 2: Extract `initRings()`**

Creates and initializes the RingManager.

```java
/**
 * Phase I: Initialize ring placement and rendering.
 * <p>
 * S2 Phase I (#45): RingsManager (initial ring placement).
 * S1 Phase J (#33): ObjPosLoad (rings are objects in S1).
 * S3K Phase O (#49): Load_Rings.
 */
public void initRings() {
    RingSpriteSheet ringSpriteSheet = level.getRingSpriteSheet();
    ringManager = new RingManager(level.getRings(), ringSpriteSheet, this, touchResponseTable);
    ringManager.reset(Camera.getInstance().getX());
    ringManager.ensurePatternsCached(graphicsManager, level.getPatternCount());
}
```

**Step 3: Extract `initZoneFeatures()`**

Initializes zone-specific features (CNZ bumpers, CPZ pylon, water surface, etc.).

```java
/**
 * Phase G: Initialize zone-specific features.
 * <p>
 * S2 Phase G (#39-40): WaterSurface, CPZ Pylon, OOZ Oil, CNZ bumpers.
 * S1 Phase I (#32): LZ water surface objects.
 * S3K Phase J (#36): j_LevelSetup → LevelSetupArray per-zone dispatch.
 */
public void initZoneFeatures() throws IOException {
    Rom rom = GameServices.rom().getRom();
    Camera camera = Camera.getInstance();
    zoneFeatureProvider = gameModule.getZoneFeatureProvider();
    if (zoneFeatureProvider != null) {
        zoneFeatureProvider.initZoneFeatures(rom, getFeatureZoneId(), getFeatureActId(), camera.getX());
        int waterPatternBase = 0x30000;
        zoneFeatureProvider.ensurePatternsCached(graphicsManager, waterPatternBase);
    }
}
```

**Step 4: Update `initGameState` to delegate**

```java
public void initGameState() throws IOException {
    initGameplayState();
    initRings();
    initZoneFeatures();
}
```

**Step 5: Run tests**

Run: `mvn test`
Expected: All tests pass.

**Step 6: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: split initGameState into initGameplayState + initRings + initZoneFeatures"
```

---

### Task 4: Split `initArtAndPlayer` Into `initArt` + `initPlayerAndCheckpoint`

**Files:**
- Modify: `src/main/java/com/openggf/level/LevelManager.java`

**Step 1: Extract `initArt()`**

Loads object and player sprite art.

```java
/**
 * Phase C: Load object art and player sprite art.
 * <p>
 * S2 Phase C (#8-9): ObjectArt, PlayerSpriteArt.
 * S1 Phase A (#6-7): Zone PLC, plcid_Main2.
 * S3K Phase C (#12-14): Load_PLC zone, character PLCs, standard PLCs.
 */
public void initArt() {
    initObjectArt();
    initPlayerSpriteArt();
}
```

**Step 2: Extract `initPlayerAndCheckpoint()`**

Resets player state and initializes checkpoint/level gamestate.

```java
/**
 * Phase G/H: Reset player state and initialize checkpoint tracking.
 * <p>
 * S2 Phase G (#37): InitPlayers (player state reset).
 * S2 Phase H (#41): Checkpoint clear, LevelGamestate creation.
 * S1 Phase I (#28): Spawn Sonic.
 * S3K Phase O (#47): SpawnLevelMainSprites.
 */
public void initPlayerAndCheckpoint() {
    resetPlayerState();
    if (checkpointState == null) {
        checkpointState = gameModule.createRespawnState();
    }
    checkpointState.clear();
    levelGamestate = gameModule.createLevelState();
}
```

**Step 3: Update `initArtAndPlayer` to delegate**

```java
public void initArtAndPlayer() {
    initArt();
    initPlayerAndCheckpoint();
}
```

**Step 4: Run tests**

Run: `mvn test`
Expected: All tests pass.

**Step 5: Commit**

```bash
git add src/main/java/com/openggf/level/LevelManager.java
git commit -m "refactor: split initArtAndPlayer into initArt + initPlayerAndCheckpoint"
```

---

### Task 5: Update Sonic2LevelInitProfile to Use 13 Finer-Grained Steps

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic2/Sonic2LevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic2/TestSonic2LevelInitProfile.java`

**Step 1: Update `levelLoadSteps()` to use new methods**

Replace the 8 steps with 13 steps using the new finer-grained LevelManager methods:

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    return List.of(
        new InitStep("InitGameModule",
            "S2 Phase A (#1-4): Pal_FadeToBlack, ClearPLC, clear variables, create Game instance",
            () -> { try { lm.initGameModule(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitAudio",
            "S2 Phase C (#21): Level_SetPlayerMode, PlayMusic — configure audio manager and play level music",
            () -> lm.initAudio(ctx.getLevelIndex())),
        new InitStep("LoadLevelData",
            "S2 Phase E-F (#26-35): LevelDataLoad, LoadZoneTiles, LoadCollisionIndexes",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitAnimatedContent",
            "S2 Phase E (#32): LoadAnimatedBlocks (pattern animation scripts + palette cycling)",
            lm::initAnimatedContent),
        new InitStep("InitObjectManager",
            "S2 Phase G (#36-38): InitPlayers, WaterEffects, create ObjectManager, wire CollisionSystem",
            () -> { try { lm.initObjectManager(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitCameraBounds",
            "S2 Phase E (#26): LevelSizeLoad — reset camera bounds from level geometry",
            lm::initCameraBounds),
        new InitStep("InitGameplayState",
            "S2 Phase H (#41-43): OscillateNumInit, clear rings/time/lives, HUD update flags",
            lm::initGameplayState),
        new InitStep("InitRings",
            "S2 Phase I (#45): RingsManager — initial ring placement and pattern caching",
            lm::initRings),
        new InitStep("InitZoneFeatures",
            "S2 Phase G (#39-40): WaterSurface, CPZ Pylon, OOZ Oil, CNZ bumpers",
            () -> { try { lm.initZoneFeatures(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitArt",
            "S2 Phase C (#8-9): ObjectArt (zone PLC), PlayerSpriteArt",
            lm::initArt),
        new InitStep("InitPlayerAndCheckpoint",
            "S2 Phase G (#37) + H (#41): ResetPlayerState, checkpoint clear, LevelGamestate",
            lm::initPlayerAndCheckpoint),
        new InitStep("InitWater",
            "S2 Phase B (#13,18): WaterSystem loading for water zones (LZ, HPZ, CPZ)",
            () -> { try { lm.initWater(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO at maximum required size",
            lm::initBackgroundRenderer)
    );
}
```

**Step 2: Update test**

```java
@Test
public void levelLoadStepsContains13RomAlignedSteps() {
    List<InitStep> steps = profile.levelLoadSteps(new LevelLoadContext());
    assertEquals(13, steps.size());
    assertEquals("InitGameModule", steps.get(0).name());
    assertEquals("InitAudio", steps.get(1).name());
    assertEquals("LoadLevelData", steps.get(2).name());
    assertEquals("InitAnimatedContent", steps.get(3).name());
    assertEquals("InitObjectManager", steps.get(4).name());
    assertEquals("InitCameraBounds", steps.get(5).name());
    assertEquals("InitGameplayState", steps.get(6).name());
    assertEquals("InitRings", steps.get(7).name());
    assertEquals("InitZoneFeatures", steps.get(8).name());
    assertEquals("InitArt", steps.get(9).name());
    assertEquals("InitPlayerAndCheckpoint", steps.get(10).name());
    assertEquals("InitWater", steps.get(11).name());
    assertEquals("InitBackgroundRenderer", steps.get(12).name());
}
```

**Step 3: Run all tests**

Run: `mvn test`
Expected: All tests pass.

**Step 4: Commit**

```bash
git add -u
git commit -m "feat: update Sonic2LevelInitProfile to 13 finer-grained ROM-aligned steps"
```

---

### Task 6: Update Sonic1LevelInitProfile to Use 13 Finer-Grained Steps

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic1/Sonic1LevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic1/TestSonic1LevelInitProfile.java`

**Step 1: Update `levelLoadSteps()`**

Same 13-step structure as S2, but with S1 ROM routine descriptions. S1 uses `sonic.asm` references.

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    return List.of(
        new InitStep("InitGameModule",
            "S1 Phase A (#1-4): bgm_Fade, ClearPLC, PaletteFadeOut, create Game instance",
            () -> { try { lm.initGameModule(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitAudio",
            "S1 Phase D (#15): QueueSound1 from MusicList — SBZ3/FZ music overrides",
            () -> lm.initAudio(ctx.getLevelIndex())),
        new InitStep("LoadLevelData",
            "S1 Phase G-H (#20-26): LevelSizeLoad, DeformLayers, LevelDataLoad, ConvertCollisionArray, ColIndexLoad",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitAnimatedContent",
            "S1 Phase G: LoadAnimatedBlocks (S1 loads animation scripts during LevelDataLoad)",
            lm::initAnimatedContent),
        new InitStep("InitObjectManager",
            "S1 Phase I (#28-32): Spawn Sonic, HUD, create ObjectManager, wire CollisionSystem",
            () -> { try { lm.initObjectManager(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitCameraBounds",
            "S1 Phase G (#20): LevelSizeLoad — reset camera bounds from level geometry",
            lm::initCameraBounds),
        new InitStep("InitGameplayState",
            "S1 Phase K (#36-38): OscillateNumInit, clear game state, HUD update flags",
            lm::initGameplayState),
        new InitStep("InitRings",
            "S1 Phase J (#33): ObjPosLoad — rings are objects in S1, placed via ObjectManager",
            lm::initRings),
        new InitStep("InitZoneFeatures",
            "S1 Phase I (#32): LZ water surface objects, zone-specific features",
            () -> { try { lm.initZoneFeatures(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitArt",
            "S1 Phase A (#6-7): Zone PLC, plcid_Main2 (shared HUD/ring/monitor patterns)",
            lm::initArt),
        new InitStep("InitPlayerAndCheckpoint",
            "S1 Phase I (#28): Spawn Sonic, reset player state, checkpoint clear",
            lm::initPlayerAndCheckpoint),
        new InitStep("InitWater",
            "S1 Phase C (#11): LZ water check, WaterHeight table, Water_flag",
            () -> { try { lm.initWater(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO",
            lm::initBackgroundRenderer)
    );
}
```

**Step 2: Update test to expect 13 steps**

Update `TestSonic1LevelInitProfile.levelLoadStepsContains8RomAlignedSteps` to `levelLoadStepsContains13RomAlignedSteps` with all 13 step names.

**Step 3: Run all tests**

Run: `mvn test`
Expected: All tests pass.

**Step 4: Commit**

```bash
git add -u
git commit -m "feat: update Sonic1LevelInitProfile to 13 finer-grained ROM-aligned steps"
```

---

### Task 7: Update Sonic3kLevelInitProfile to Use 13 Finer-Grained Steps

**Files:**
- Modify: `src/main/java/com/openggf/game/sonic3k/Sonic3kLevelInitProfile.java`
- Modify: `src/test/java/com/openggf/game/sonic3k/TestSonic3kLevelInitProfile.java`

**Step 1: Update `levelLoadSteps()`**

Same 13-step structure with S3K ROM routine descriptions. S3K uses `sonic3k.asm` references. Note S3K-specific ordering differences documented in romRoutine strings.

```java
@Override
public List<InitStep> levelLoadSteps(LevelLoadContext ctx) {
    LevelManager lm = LevelManager.getInstance();
    return List.of(
        new InitStep("InitGameModule",
            "S3K Phase A-D (#1-20): cmd_FadeOut, Pal_FadeToBlack, Clear_Nem_Queue, clearRAM, create Game instance",
            () -> { try { lm.initGameModule(ctx.getLevelIndex()); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitAudio",
            "S3K Phase F (#25): Play_Music from LevelMusic_Playlist — AIZ1 lamppost 3 music override",
            () -> lm.initAudio(ctx.getLevelIndex())),
        new InitStep("LoadLevelData",
            "S3K Phase H-K (#30-38): Get_LevelSizeStart, DeformBgLayer, LoadLevelLoadBlock, LoadLevelLoadBlock2, j_LevelSetup, LoadSolids",
            () -> { try { ctx.setLevel(lm.loadLevelData(ctx.getLevelIndex())); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitAnimatedContent",
            "S3K Phase J (#37): Animate_Init (zone-specific animation counter initialization)",
            lm::initAnimatedContent),
        new InitStep("InitObjectManager",
            "S3K Phase O (#47-48): SpawnLevelMainSprites, Load_Sprites — create ObjectManager, wire CollisionSystem",
            () -> { try { lm.initObjectManager(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitCameraBounds",
            "S3K Phase H (#32): Get_LevelSizeStart — reset camera bounds from level geometry",
            lm::initCameraBounds),
        new InitStep("InitGameplayState",
            "S3K Phase N (#43-45): Clear game state, OscillateNumInit, Level_started_flag set BEFORE first object frame",
            lm::initGameplayState),
        new InitStep("InitRings",
            "S3K Phase O (#49): Load_Rings — initial ring placement",
            lm::initRings),
        new InitStep("InitZoneFeatures",
            "S3K Phase J (#36): j_LevelSetup → LevelSetupArray per-zone dispatch, HCZ water surface, MHZ pollen",
            () -> { try { lm.initZoneFeatures(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitArt",
            "S3K Phase C (#12-14): Load_PLC zone, character PLCs, standard PLCs",
            lm::initArt),
        new InitStep("InitPlayerAndCheckpoint",
            "S3K Phase O (#47): SpawnLevelMainSprites — player spawn AFTER game state init (opposite of S1/S2)",
            lm::initPlayerAndCheckpoint),
        new InitStep("InitWater",
            "S3K Phase E (#22): CheckLevelForWater, StartingWaterHeights",
            () -> { try { lm.initWater(); } catch (IOException e) { throw new UncheckedIOException(e); } }),
        new InitStep("InitBackgroundRenderer",
            "Engine-specific: Pre-allocate BG FBO for AIZ intro ocean-to-beach transition",
            lm::initBackgroundRenderer)
    );
}
```

**Step 2: Update test to expect 13 steps**

Update `TestSonic3kLevelInitProfile.levelLoadStepsContains8RomAlignedSteps` to `levelLoadStepsContains13RomAlignedSteps` with all 13 step names.

**Step 3: Run all tests**

Run: `mvn test`
Expected: All tests pass.

**Step 4: Commit**

```bash
git add -u
git commit -m "feat: update Sonic3kLevelInitProfile to 13 finer-grained ROM-aligned steps"
```

---

### Task 8: Update TestGameModuleProfiles and Cross-Game Tests

**Files:**
- Modify: `src/test/java/com/openggf/game/TestGameModuleProfiles.java`

**Step 1: Update any assertions that reference step count (8 → 13)**

Search for `assertEquals(8,` or `assertEquals("S.*should have 8"` and update to 13.

**Step 2: Run full test suite**

Run: `mvn test`
Expected: All tests pass.

**Step 3: Commit**

```bash
git add -u
git commit -m "test: update cross-game profile tests for 13-step load sequences"
```

---

### Task 9: Full Validation Sweep

**Step 1: Run complete test suite**

```bash
mvn test 2>&1 | tail -20
```

Expected: All tests pass (1434+), 0 failures, 0 errors.

**Step 2: Run game-specific test suites in parallel**

S1: `mvn test -Dtest="com.openggf.tests.TestSonic1*"`
S2: `mvn test -Dtest="com.openggf.tests.Test*Headless*"`
S3K: `mvn test -Dtest="com.openggf.tests.TestS3k*,com.openggf.tests.TestSonic3k*,sonic3k.com.openggf.game.Test*"`

**Step 3: Run profile-specific tests**

```bash
mvn test -Dtest="com.openggf.game.sonic2.TestSonic2LevelInitProfile,com.openggf.game.sonic1.TestSonic1LevelInitProfile,com.openggf.game.sonic3k.TestSonic3kLevelInitProfile,com.openggf.game.TestGameModuleProfiles"
```

All must pass.

---

## Summary of Changes

| Task | What | Files | Test Impact |
|------|------|-------|-------------|
| 1 | Split initGameModuleAndAudio | LevelManager.java | Pure refactoring |
| 2 | Split initObjectSystem | LevelManager.java | Pure refactoring |
| 3 | Split initGameState | LevelManager.java | Pure refactoring |
| 4 | Split initArtAndPlayer | LevelManager.java | Pure refactoring |
| 5 | S2 profile: 8→13 steps | Sonic2LevelInitProfile.java + test | Step count change |
| 6 | S1 profile: 8→13 steps | Sonic1LevelInitProfile.java + test | Step count change |
| 7 | S3K profile: 8→13 steps | Sonic3kLevelInitProfile.java + test | Step count change |
| 8 | Cross-game test updates | TestGameModuleProfiles.java | Step count change |
| 9 | Full validation | — | All 1434+ tests green |

## ROM Phase Coverage (Post-Phase 4)

| Step | S2 Phases | S1 Phases | S3K Phases |
|------|-----------|-----------|------------|
| InitGameModule | A (#1-4) | A (#1-4) | A-D (#1-20) |
| InitAudio | C (#21) | D (#15) | F (#25) |
| LoadLevelData | E-F (#26-35) | G-H (#20-26) | H-K (#30-38) |
| InitAnimatedContent | E (#32) | G (part) | J (#37) |
| InitObjectManager | G (#36-38) | I (#28-32) | O (#47-48) |
| InitCameraBounds | E (#26) | G (#20) | H (#32) |
| InitGameplayState | H (#41-43) | K (#36-38) | N (#43-45) |
| InitRings | I (#45) | J (#33) | O (#49) |
| InitZoneFeatures | G (#39-40) | I (#32) | J (#36) |
| InitArt | C (#8-9) | A (#6-7) | C (#12-14) |
| InitPlayerAndCheckpoint | G (#37) + H (#41) | I (#28) | O (#47) |
| InitWater | B (#13,18) | C (#11) | E (#22) |
| InitBackgroundRenderer | — | — | — |
