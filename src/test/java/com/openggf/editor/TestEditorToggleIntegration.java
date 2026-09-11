package com.openggf.editor;

import com.openggf.tests.TestTempFiles;
import com.openggf.Engine;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.editor.persistence.EditorSaveManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.GameMode;
import com.openggf.game.GameModuleRegistry;
import com.openggf.game.ZoneFeatureProvider;
import com.openggf.game.session.EditorCursorState;
import com.openggf.game.session.EditorPlaytestStash;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.AbstractLevel;
import com.openggf.level.Block;
import com.openggf.level.Chunk;
import com.openggf.level.ChunkDesc;
import com.openggf.level.Level;
import com.openggf.level.Map;
import com.openggf.level.MutableLevel;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SolidTile;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.Sonic;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F5;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_DOWN;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_E;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_RIGHT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_TAB;
import static org.lwjgl.glfw.GLFW.GLFW_PRESS;
import static org.lwjgl.glfw.GLFW.GLFW_RELEASE;

class TestEditorToggleIntegration {
    private static final Path S2_ROM = Path.of("s2.gen");

    @TempDir
    Path tempSaves;

    private static final ParallaxManager SINGLE_CHUNK_BG_PERIOD = new ParallaxManager() {
        @Override
        public int getBgPeriodWidth() {
            return 16;
        }
    };

    private static final ZoneFeatureProvider BG_WRAPPING_ZONE_FEATURES = new ZoneFeatureProvider() {
        @Override
        public void initZoneFeatures(Rom rom, int zoneIndex, int actIndex, int cameraX) {
        }

        @Override
        public void update(com.openggf.sprites.playable.AbstractPlayableSprite player, int cameraX, int zoneIndex) {
        }

        @Override
        public void reset() {
        }

        @Override
        public boolean hasCollisionFeatures(int zoneIndex) {
            return false;
        }

        @Override
        public boolean hasWater(int zoneIndex) {
            return false;
        }

        @Override
        public int getWaterLevel(int zoneIndex, int actIndex) {
            return Integer.MAX_VALUE;
        }

        @Override
        public void render(com.openggf.camera.Camera camera, int frameCounter) {
        }

        @Override
        public int ensurePatternsCached(GraphicsManager graphicsManager, int baseIndex) {
            return baseIndex;
        }

        @Override
        public boolean bgWrapsHorizontally() {
            return true;
        }
    };

    @BeforeEach
    void setUp() {
        SonicConfigurationService.getInstance().resetToDefaults();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void tearDown() {
        // See TestEngine.tearDown: a leaked Engine static poisons the fork.
        com.openggf.Engine.clearGlobalInstance();
        SonicConfigurationService.getInstance().resetToDefaults();
        SessionManager.clear();
        GameModuleRegistry.reset();
    }

    @Test
    void enterEditorFromCurrentPlayer_whenEditorDisabled_rejectsActivation() {
        Engine engine = new Engine();
        createGameplayMode(engine);
        EditorPlaytestStash stash = new EditorPlaytestStash(100, 200, 9, -3, true, 47, 1);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> engine.enterEditorFromCurrentPlayer(stash, 100, 200));

        assertEquals("Level editor is disabled by configuration.", error.getMessage());
        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertNull(SessionManager.getCurrentEditorMode());
        assertNotNull(SessionManager.getCurrentGameplayMode());
    }

    @Test
    void shiftTabInGameplay_whenEditorDisabled_doesNotEnterEditor() {
        Engine engine = new Engine();
        createGameplayMode(engine);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);

        engine.getGameLoop().step();

        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertNull(SessionManager.getCurrentEditorMode());
        assertNotNull(SessionManager.getCurrentGameplayMode());
    }

    @Test
    void createGameplayMode_createsRuntimeBeforeConstructingPlayer() {
        Engine engine = new Engine();

        GameplayModeContext gameplayMode = assertDoesNotThrow(() -> createGameplayMode(engine));

        assertNotNull(gameplayMode);
        assertSame(gameplayMode, SessionManager.getCurrentGameplayMode());
        assertNotNull(gameplayMode.getSpriteManager().getSprite("sonic"));
    }

    // Removed: 3 tests that exercised the old gameplay-mode parking path.
    // Editor entry/exit now uses proper teardown+rebuild with level
    // restoration; the parking mechanism has been removed entirely. The
    // editor round-trip behavior is covered by
    // enterEditorFromCurrentPlayer_thenResumePlaytestFromEditor_roundTripsStashAndSpawn
    // and editorRoundTrip_preservesMutableLevelMutations below.

    @Test
    void enterEditorFromCurrentPlayer_thenResumePlaytestFromEditor_roundTripsStashAndSpawn() throws Exception {
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine);
        EditorPlaytestStash stash = new EditorPlaytestStash(100, 200, 9, -3, true, 47, 1);

        engine.enterEditorFromCurrentPlayer(stash, 100, 200);

        assertEquals(GameMode.EDITOR, engine.getCurrentGameMode());
        assertNotNull(SessionManager.getCurrentEditorMode());
        assertEquals(100, SessionManager.getCurrentEditorMode().getCursor().x());
        assertEquals(200, SessionManager.getCurrentEditorMode().getCursor().y());
        assertSame(stash, SessionManager.getCurrentEditorMode().getPlaytestStash());
        assertNull(SessionManager.getCurrentGameplayMode());

        engine.resumePlaytestFromEditor();

        // Post-migration: editor exit rebuilds a fresh gameplay mode over the
        // surviving WorldSession (no longer the same instance).
        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertNotNull(SessionManager.getCurrentGameplayMode());
        assertEquals(100, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(200, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertSame(stash, SessionManager.getCurrentGameplayMode().getResumeStash().orElseThrow());
    }

    @Test
    void editorRoundTrip_preservesMutableLevelMutations() throws Exception {
        assumeS2RomAvailableForResumeReload();
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);

        // Install a synthetic MutableLevel via setLevel(), which writes
        // through to WorldSession (per the session ownership migration).
        MutableLevel mutable = MutableLevel.snapshot(new SyntheticLevel());
        gameplayMode.getLevelManager().setLevel(mutable);

        com.openggf.game.session.WorldSession worldSession = gameplayMode.getWorldSession();
        assertSame(mutable, worldSession.getCurrentLevel(),
                "precondition: setLevel must write through to WorldSession");

        // Mutate a map cell to an unambiguous value.
        int newBlockIndex = (mutable.getMap().getValue(0, 0, 0) & 0xFF) ^ 0xAA;
        mutable.setBlockInMap(0, 0, 0, newBlockIndex);

        engine.enterEditorFromCurrentPlayer(
                new EditorPlaytestStash(50, 50, 0, 0, true, 0, 1),
                100, 200);
        engine.resumePlaytestFromEditor();

        // After the editor round trip, WorldSession's Level reference should
        // still be the same MutableLevel and the mutation should still be
        // present â€” proving editor enter/exit does not throw away world data.
        assertSame(mutable, worldSession.getCurrentLevel(),
                "MutableLevel must survive editor round trip on WorldSession");
        assertEquals(newBlockIndex,
                ((MutableLevel) worldSession.getCurrentLevel()).getMap().getValue(0, 0, 0) & 0xFF,
                "mutation made before editor entry must persist through the round trip");
    }

    @Test
    void resumePlaytestFromEditor_savesEditorControllerMutableLevelAfterRuntimeTeardown() throws Exception {
        assumeS2RomAvailableForResumeReload();
        enableEditor();
        Engine engine = new Engine();
        EditorSaveManager saveManager = new EditorSaveManager(tempSaves);
        setPrivateField(engine, "editorSaveManager", saveManager);
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        MutableLevel mutable = MutableLevel.snapshot(new SyntheticLevel());
        gameplayMode.getLevelManager().setLevel(mutable);
        int zone = 1;
        int act = 1;
        gameplayMode.getWorldSession().setCurrentZone(zone);
        gameplayMode.getWorldSession().setCurrentAct(act);
        Path saveFile = saveManager.editPath(gameplayMode.getWorldSession().getGameModule().getGameId(), zone, act);

        engine.enterEditorFromCurrentPlayer(
                new EditorPlaytestStash(50, 50, 0, 0, true, 0, 1),
                100, 200);
        engine.getLevelEditorController().placeBlock(0, 1, 1, 1);

        engine.resumePlaytestFromEditor();

        assertTrue(Files.exists(saveFile),
                "editor resume should save the MutableLevel attached to LevelEditorController");
        MutableLevel fresh = MutableLevel.snapshot(new SyntheticLevel());
        assertEquals(EditorSaveManager.ApplyResult.APPLIED,
                saveManager.tryApplyEdits(gameplayMode.getWorldSession().getGameModule().getGameId(), zone, act, fresh));
        assertEquals(1, Byte.toUnsignedInt(fresh.getMap().getValue(0, 1, 1)));
    }

    @Test
    void enterEditor_restoresLevelManagerViewForEditorRenderingAfterRuntimeTeardown() throws Exception {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        MutableLevel mutable = MutableLevel.snapshot(new SyntheticLevel());
        gameplayMode.getLevelManager().setLevel(mutable);

        engine.enterEditorFromCurrentPlayer(
                new EditorPlaytestStash(50, 50, 0, 0, true, 0, 1),
                100, 128);

        LevelManager editorLevelManager = engineLevelManager(engine);
        assertNull(SessionManager.getCurrentGameplayMode(),
                "entering editor must still tear down the gameplay mode");
        assertSame(mutable, engine.getLevelEditorController().currentLevel(),
                "pre-mode editor controller level should survive gameplay mode teardown");
        assertSame(mutable, editorLevelManager.getCurrentLevel(),
                "Engine's level manager must keep a renderable editor view after teardown");
        assertNotNull(editorLevelManager.getTilemapManager(),
                "editor view restore must rebuild tilemap rendering state");
        assertEquals(0, editorLevelManager.getCurrentZone());
        assertEquals(0, editorLevelManager.getCurrentAct());
        assertNull(editorLevelManager.getObjectManager(),
                "editor view restore must not initialize gameplay object systems");
        assertNull(editorLevelManager.getRingManager(),
                "editor view restore must not initialize gameplay ring systems");
        assertSame(mutable.getBlock(0), lookupBlock(editorLevelManager, (byte) 0, 0, 0));
    }

    @Test
    void editorDrawPathFlushesMutableLevelDirtyRegionsBeforeRendering() throws Exception {
        enableEditor();
        Engine engine = new Engine();
        LevelManager levelManager = mock(LevelManager.class);
        SpriteManager spriteManager = mock(SpriteManager.class);
        RuntimeException sentinel = new RuntimeException("dirty-region flush reached");
        doThrow(sentinel).when(levelManager).processDirtyRegions();
        setPrivateField(engine, "levelManager", levelManager);
        setPrivateField(engine, "spriteManager", spriteManager);
        engine.getGameLoop().setGameMode(GameMode.EDITOR);

        RuntimeException thrown = assertThrows(RuntimeException.class, engine::draw);

        assertSame(sentinel, thrown);
        verify(levelManager).processDirtyRegions();
        verify(levelManager, never()).drawWithSpritePriority(spriteManager);
    }

    @Test
    void editorRoundTrip_preservesWorldSessionAndResetsGameplayCounters() throws Exception {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);

        // Capture the durable world state on WorldSession before editor entry.
        com.openggf.game.session.WorldSession worldSession = gameplayMode.getWorldSession();
        com.openggf.level.Level loadedLevelBefore = worldSession.getCurrentLevel();
        int zoneBefore = worldSession.getCurrentZone();
        int actBefore = worldSession.getCurrentAct();

        // Set a session counter to a non-default value; the design requires
        // it to reset on editor exit.
        gameplayMode.getGameStateManager().addScore(7777);
        int scoreBefore = gameplayMode.getGameStateManager().getScore();
        assertTrue(scoreBefore > 0, "score precondition: must be non-zero before editor entry");

        engine.enterEditorFromCurrentPlayer(
                new EditorPlaytestStash(50, 50, 0, 0, true, 0, 1),
                100, 200);
        engine.resumePlaytestFromEditor();

        // World survived the round trip: same WorldSession instance, same
        // loaded Level, same zone/act metadata.
        GameplayModeContext resumedGameplayMode = SessionManager.getCurrentGameplayMode();
        assertNotNull(resumedGameplayMode, "editor exit must restore gameplay mode");
        assertSame(worldSession, resumedGameplayMode.getWorldSession(),
                "WorldSession must survive editor round trip");
        assertSame(loadedLevelBefore, worldSession.getCurrentLevel(),
                "Loaded Level must survive editor round trip on WorldSession");
        assertEquals(zoneBefore, worldSession.getCurrentZone(),
                "currentZone must be preserved on WorldSession");
        assertEquals(actBefore, worldSession.getCurrentAct(),
                "currentAct must be preserved on WorldSession");

        // Gameplay counters were reset per the design (editor exit reinit).
        assertEquals(0, resumedGameplayMode.getGameStateManager().getScore(),
                "score must reset on editor exit (was " + scoreBefore + ")");
    }

    @Test
    void editorRoundTrip_rebuildsCameraBoundsAndFocusedSpriteAtCursor() throws Exception {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        // Set non-trivial bounds on the gameplay-mode camera so we can check
        // they survive (or are correctly re-derived) across the round trip.
        gameplayMode.getCamera().setMinX((short) 0);
        gameplayMode.getCamera().setMaxX((short) 1024);
        gameplayMode.getCamera().setMinY((short) 0);
        gameplayMode.getCamera().setMaxY((short) 768);

        engine.enterEditorFromCurrentPlayer(
                new EditorPlaytestStash(50, 50, 0, 0, true, 0, 1), 100, 200);
        // Move cursor to a deliberate spawn target before exiting editor.
        engine.getLevelEditorController().setWorldCursor(new EditorCursorState(384, 256));
        engine.resumePlaytestFromEditor();

        // After teardown+rebuild, the gameplay mode is a fresh instance and the
        // sprite/camera are too. Re-resolve the active sprite + camera and
        // assert the rebuild produced sensible state at the cursor position.
        GameplayModeContext resumed = SessionManager.getCurrentGameplayMode();
        assertNotNull(resumed, "rebuild must produce a fresh gameplay mode");
        Sonic resumedPlayer = (Sonic) resumed.getSpriteManager().getSprite("sonic");
        assertNotNull(resumedPlayer, "rebuild must spawn the main character");
        assertEquals(384, resumedPlayer.getCentreX(),
                "rebuilt player should be at cursor X (applyResumedPlaytestState)");
        assertEquals(256, resumedPlayer.getCentreY(),
                "rebuilt player should be at cursor Y (applyResumedPlaytestState)");
        assertSame(resumedPlayer, resumed.getCamera().getFocusedSprite(),
                "rebuilt camera should focus on the resumed player");
    }

    @Test
    void syncEditorState_keepsSessionCursorAlignedWithControllerCursor() {
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine);

        engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 0, 0, true, 0, 0), 100, 200);
        engine.getLevelEditorController().setWorldCursor(new EditorCursorState(160, 224));
        engine.syncEditorState();

        assertEquals(160, SessionManager.getCurrentEditorMode().getCursor().x());
        assertEquals(224, SessionManager.getCurrentEditorMode().getCursor().y());
    }

    @Test
    void gameLoop_editorModeStepSyncsSessionCursorWithoutRender() {
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 0, 0, true, 0, 0), 100, 200);
        inputHandler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);

        engine.getGameLoop().step();

        assertEquals(103, engine.getLevelEditorController().worldCursor().x());
        assertEquals(103, SessionManager.getCurrentEditorMode().getCursor().x());
        assertEquals(200, SessionManager.getCurrentEditorMode().getCursor().y());
    }

    @Test
    void editorEntryTearsDownParkedRuntimeSpriteManager() {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        SpriteManager oldSpriteManager = gameplayMode.getSpriteManager();

        engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 0, 0, true, 0, 0), 100, 200);

        assertNull(SessionManager.getCurrentGameplayMode());
        assertNull(gameplayMode.getSpriteManager(),
                "editor entry should detach the old runtime sprite manager");
        assertDoesNotThrow(oldSpriteManager::drawLowPriority,
                "the cleared old sprite manager should remain safe if a stale reference is rendered");
    }

    // Removed: parkedRuntimeBackgroundTilemapBuild_doesNotRequireActiveGameServicesRuntime
    // Tested the parking mechanism, which is no longer
    // used by the editor flow â€” editor entry now does a proper teardown+rebuild
    // per the session ownership migration design.

    @Test
    void gameLoop_f5InEditorModeInvokesFreshStartHandler() {
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);
        int[] freshStartCount = {0};

        engine.getGameLoop().setEditorFreshStartHandler(() -> freshStartCount[0]++);
        engine.getGameLoop().setGameMode(GameMode.EDITOR);

        inputHandler.handleKeyEvent(GLFW_KEY_F5, GLFW_PRESS);

        engine.getGameLoop().step();

        assertEquals(1, freshStartCount[0]);
        assertFalse(inputHandler.isKeyPressed(GLFW_KEY_F5));
    }

    @Test
    void gameLoop_f5InEditorModeUsesEngineFreshStartHandlerByDefault() {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 9, -3, true, 47, 1), 100, 200);
        inputHandler.handleKeyEvent(GLFW_KEY_F5, GLFW_PRESS);

        engine.getGameLoop().step();

        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertNotSame(gameplayMode, SessionManager.getCurrentGameplayMode());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertTrue(SessionManager.getCurrentGameplayMode().getResumeStash().isEmpty());
    }

    @Test
    void gameLoop_f5InEditorModeIgnoresLeakedGarbageRomState() throws Exception {
        enableEditor();
        Engine engine = new Engine();
        injectLeakedGarbageRom();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 9, -3, true, 47, 1), 100, 200);
        inputHandler.handleKeyEvent(GLFW_KEY_F5, GLFW_PRESS);

        assertDoesNotThrow(() -> engine.getGameLoop().step());

        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertNotSame(gameplayMode, SessionManager.getCurrentGameplayMode());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertTrue(SessionManager.getCurrentGameplayMode().getResumeStash().isEmpty());
    }

    @Test
    void gameLoop_f5OutsideEditorModeDoesNotInvokeFreshStartHandler() {
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);
        int[] freshStartCount = {0};

        engine.getGameLoop().setEditorFreshStartHandler(() -> freshStartCount[0]++);
        engine.getGameLoop().pause();
        inputHandler.handleKeyEvent(GLFW_KEY_F5, GLFW_PRESS);

        engine.getGameLoop().step();

        assertEquals(0, freshStartCount[0]);
    }

    @Test
    void syncEditorState_inWorldDepthCentersCameraOnEditorCursorWhenInsideBounds() {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        gameplayMode.getCamera().setMinX((short) 0);
        gameplayMode.getCamera().setMaxX((short) 1024);
        gameplayMode.getCamera().setMinY((short) 0);
        gameplayMode.getCamera().setMaxY((short) 1024);

        engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 0, 0, true, 0, 0), 100, 200);
        engine.getLevelEditorController().setWorldCursor(new EditorCursorState(512, 384));
        engine.syncEditorState();

        Camera editorCamera = engineCamera(engine);
        assertEquals(360, editorCamera.getX());
        assertEquals(288, editorCamera.getY());
    }

    @Test
    void syncEditorState_inWorldDepthClampsCameraToEditorCursorWhenCenterWouldExceedBounds() {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        gameplayMode.getCamera().setMinX((short) 64);
        gameplayMode.getCamera().setMaxX((short) 80);
        gameplayMode.getCamera().setMinY((short) 0);
        gameplayMode.getCamera().setMaxY((short) 160);

        engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 200, 0, 0, true, 0, 0), 100, 200);
        engine.getLevelEditorController().setWorldCursor(new EditorCursorState(192, 288));
        engine.syncEditorState();

        Camera editorCamera = engineCamera(engine);
        assertEquals(64, editorCamera.getX());
        assertEquals(160, editorCamera.getY());
    }

    @Test
    void resumePlaytestFromEditor_usesMovedControllerCursorForGameplaySpawn() {
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine);
        EditorPlaytestStash stash = new EditorPlaytestStash(100, 200, 9, -3, true, 47, 1);

        engine.enterEditorFromCurrentPlayer(stash, 100, 200);
        engine.getLevelEditorController().setWorldCursor(new EditorCursorState(320, 448));

        engine.resumePlaytestFromEditor();

        // Post-migration: editor exit rebuilds a fresh gameplay mode; assert non-null
        // rather than instance identity.
        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertNotNull(SessionManager.getCurrentGameplayMode());
        assertEquals(320, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(448, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertSame(stash, SessionManager.getCurrentGameplayMode().getResumeStash().orElseThrow());
    }

    @Test
    void resumePlaytestFromEditor_repairsProgrammaticOutOfBoundsCursorBeforeApplyingSpawn() throws Exception {
        assumeS2RomAvailableForResumeReload();
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine, (short) 100, (short) 180);
        com.openggf.game.GameServices.level().setLevel(MutableLevel.snapshot(new SyntheticLevel()));
        com.openggf.game.GameServices.camera().setMinX((short) 0);
        com.openggf.game.GameServices.camera().setMaxX((short) 255);
        com.openggf.game.GameServices.camera().setMinY((short) 0);
        com.openggf.game.GameServices.camera().setMaxY((short) 191);

        engine.enterEditorFromCurrentPlayer(new EditorPlaytestStash(100, 180, 0, 0, true, 0, 0), 100, 180);
        forceControllerCursor(engine.getLevelEditorController(), new EditorCursorState(999, -99));

        engine.resumePlaytestFromEditor();

        // Post-migration: editor exit rebuilds a fresh gameplay mode; resolve the
        // active sprite to read its position rather than using the stale ref.
        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        GameplayModeContext resumedRuntime = SessionManager.getCurrentGameplayMode();
        assertNotNull(resumedRuntime);
        Sonic resumedPlayer = (Sonic) resumedRuntime.getSpriteManager().getSprite("sonic");
        assertNotNull(resumedPlayer);
        assertEquals(255, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertEquals(255, resumedPlayer.getCentreX());
        assertEquals(0, resumedPlayer.getCentreY());
        assertSame(resumedPlayer, resumedRuntime.getCamera().getFocusedSprite());
    }

    @Test
    void movedEditorCursor_becomesResumePositionWhenReturningToGameplay() {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        Sonic player = (Sonic) gameplayMode.getSpriteManager().getSprite("sonic");
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();

        assertEquals(GameMode.EDITOR, engine.getCurrentGameMode());
        assertEquals(player.getCentreX(), SessionManager.getCurrentEditorMode().getCursor().x());
        assertEquals(player.getCentreY(), SessionManager.getCurrentEditorMode().getCursor().y());

        inputHandler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
        engine.getGameLoop().step();
        EditorCursorState movedCursor = engine.getLevelEditorController().worldCursor();

        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_RELEASE);
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
        inputHandler.update();
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();

        // Post-migration: editor exit rebuilds a fresh gameplay mode; re-resolve
        // the active sprite to read its position.
        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        GameplayModeContext resumedRuntime = SessionManager.getCurrentGameplayMode();
        assertNotNull(resumedRuntime);
        Sonic resumedPlayer = (Sonic) resumedRuntime.getSpriteManager().getSprite("sonic");
        assertNotNull(resumedPlayer);
        assertEquals(movedCursor.x(), SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(movedCursor.y(), SessionManager.getCurrentGameplayMode().getSpawnY());
        assertEquals(movedCursor.x(), resumedPlayer.getCentreX());
        assertEquals(movedCursor.y(), resumedPlayer.getCentreY());
    }

    @Test
    void outOfBoundsEditorMovement_resumesFromClampedCursorPosition() {
        assumeS2RomAvailableForResumeReload();
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine, (short) 100, (short) 180);
        com.openggf.game.GameServices.level().setLevel(MutableLevel.snapshot(new SyntheticLevel()));
        com.openggf.game.GameServices.camera().setMinX((short) 0);
        com.openggf.game.GameServices.camera().setMaxX((short) 255);
        com.openggf.game.GameServices.camera().setMinY((short) 0);
        com.openggf.game.GameServices.camera().setMaxY((short) 191);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();

        engine.getLevelEditorController().setWorldCursor(new EditorCursorState(255, 191));
        inputHandler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
        engine.getGameLoop().step();

        EditorCursorState boundedCursor = engine.getLevelEditorController().worldCursor();
        assertEquals(255, boundedCursor.x());
        assertEquals(191, boundedCursor.y());
        assertEquals(255, SessionManager.getCurrentEditorMode().getCursor().x());
        assertEquals(191, SessionManager.getCurrentEditorMode().getCursor().y());
        SessionManager.getCurrentEditorMode().setCursor(new EditorCursorState(12, 34));
        assertEquals(12, SessionManager.getCurrentEditorMode().getCursor().x());
        assertEquals(34, SessionManager.getCurrentEditorMode().getCursor().y());

        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_RELEASE);
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
        inputHandler.update();
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();

        // Post-migration: editor exit rebuilds a fresh gameplay mode; resolve the
        // active sprite to read its position rather than using the stale ref.
        Sonic resumedPlayer = (Sonic) SessionManager.getCurrentGameplayMode().getSpriteManager().getSprite("sonic");
        assertNotNull(resumedPlayer);
        assertEquals(255, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(191, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertEquals(255, resumedPlayer.getCentreX());
        assertEquals(191, resumedPlayer.getCentreY());
    }

    @Test
    void startGameplayFromBeginning_discardsResumeStashAndReturnsToCanonicalSpawn() throws Exception {
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine);
        EditorPlaytestStash stash = new EditorPlaytestStash(100, 200, 9, -3, true, 47, 1);

        engine.enterEditorFromCurrentPlayer(stash, 100, 200);

        engine.startGameplayFromBeginning();

        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertTrue(SessionManager.getCurrentGameplayMode().getResumeStash().isEmpty());
        GameplayModeContext restartedRuntime = SessionManager.getCurrentGameplayMode();
        assertNotNull(restartedRuntime);
        assertNotNull(restartedRuntime.getSpriteManager().getSprite("sonic"));
        assertSame(restartedRuntime.getSpriteManager().getSprite("sonic"),
                restartedRuntime.getCamera().getFocusedSprite());
        if (RomManager.getInstance().isRomAvailable()) {
            assertNotNull(restartedRuntime.getLevelManager().getCurrentLevel());
        }
    }

    @Test
    void shiftTabInGameplayTogglesEditorAndBackThroughEngineHelpers() {
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine);
        Sonic player = (Sonic) gameplayMode.getSpriteManager().getSprite("sonic");
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);

        engine.getGameLoop().step();

        assertEquals(GameMode.EDITOR, engine.getCurrentGameMode());
        assertNotNull(SessionManager.getCurrentEditorMode());
        assertEquals(player.getCentreX(), SessionManager.getCurrentEditorMode().getCursor().x());
        assertEquals(player.getCentreY(), SessionManager.getCurrentEditorMode().getCursor().y());

        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_RELEASE);
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_RELEASE);
        inputHandler.update();
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);

        engine.getGameLoop().step();

        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertEquals(player.getCentreX(), SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(player.getCentreY(), SessionManager.getCurrentGameplayMode().getSpawnY());
        assertTrue(SessionManager.getCurrentGameplayMode().getResumeStash().isPresent());
    }

    @Test
    void preRuntimePlayer_roundTripsThroughEditorModeAndResumesAtEditorCursor() {
        enableEditor();
        Engine engine = new Engine();
        createGameplayMode(engine, (short) 144, (short) 288);
        EditorPlaytestStash stash = new EditorPlaytestStash(144, 288, 9, -3, true, 47, 1);

        engine.enterEditorFromCurrentPlayer(stash, 320, 448);

        assertEquals(GameMode.EDITOR, engine.getCurrentGameMode());
        assertNull(SessionManager.getCurrentGameplayMode());
        assertEquals(320, SessionManager.getCurrentEditorMode().getCursor().x());
        assertEquals(448, SessionManager.getCurrentEditorMode().getCursor().y());

        engine.resumePlaytestFromEditor();

        // Post-migration: editor exit rebuilds a fresh gameplay mode over the
        // surviving WorldSession, so mode/player references from before
        // the editor detour are stale. Re-resolve the active sprite.
        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        GameplayModeContext resumedRuntime = SessionManager.getCurrentGameplayMode();
        assertNotNull(resumedRuntime);
        Sonic resumedPlayer = (Sonic) resumedRuntime.getSpriteManager().getSprite("sonic");
        assertNotNull(resumedPlayer);
        assertSame(resumedPlayer, resumedRuntime.getCamera().getFocusedSprite());
        assertEquals(320, resumedPlayer.getCentreX());
        assertEquals(448, resumedPlayer.getCentreY());
        assertEquals(320, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(448, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertSame(stash, SessionManager.getCurrentGameplayMode().getResumeStash().orElseThrow());
    }

    @Test
    void editorMvpFlow_shiftTabMoveEyedropApplyResumeAndFreshStartRemainConnected() {
        assumeS2RomAvailableForResumeReload();
        enableEditor();
        Engine engine = new Engine();
        GameplayModeContext gameplayMode = createGameplayMode(engine, (short) 0, (short) 0);
        Sonic player = (Sonic) gameplayMode.getSpriteManager().getSprite("sonic");
        player.setCentreX((short) 1);
        player.setCentreY((short) 1);
        MutableLevel level = MutableLevel.snapshot(new EditorMvpFlowLevel());
        gameplayMode.getLevelManager().setLevel(level);
        gameplayMode.getCamera().setMinX((short) 0);
        gameplayMode.getCamera().setMaxX((short) 7);
        gameplayMode.getCamera().setMinY((short) 0);
        gameplayMode.getCamera().setMaxY((short) 7);
        InputHandler inputHandler = new InputHandler();
        engine.setInputHandler(inputHandler);

        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();

        assertEquals(GameMode.EDITOR, engine.getCurrentGameMode());
        assertEquals(new EditorCursorState(1, 1), engine.getLevelEditorController().worldCursor());

        releaseAndAdvance(inputHandler, GLFW_KEY_TAB);
        releaseAndAdvance(inputHandler, GLFW_KEY_LEFT_SHIFT);
        inputHandler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
        engine.getGameLoop().step();
        releaseAndAdvance(inputHandler, GLFW_KEY_RIGHT);
        releaseAndAdvance(inputHandler, GLFW_KEY_DOWN);

        assertEquals(new EditorCursorState(4, 4), engine.getLevelEditorController().worldCursor());

        inputHandler.handleKeyEvent(GLFW_KEY_E, GLFW_PRESS);
        engine.getGameLoop().step();
        releaseAndAdvance(inputHandler, GLFW_KEY_E);

        assertEquals(1, engine.getLevelEditorController().selection().selectedBlock());

        inputHandler.handleKeyEvent(GLFW_KEY_RIGHT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_DOWN, GLFW_PRESS);
        engine.getGameLoop().step();
        releaseAndAdvance(inputHandler, GLFW_KEY_RIGHT);
        releaseAndAdvance(inputHandler, GLFW_KEY_DOWN);

        inputHandler.handleKeyEvent(GLFW_KEY_SPACE, GLFW_PRESS);
        engine.getGameLoop().step();
        releaseAndAdvance(inputHandler, GLFW_KEY_SPACE);

        assertEquals(new EditorCursorState(7, 7), engine.getLevelEditorController().worldCursor());
        assertEquals(1, Byte.toUnsignedInt(level.getMap().getValue(0, 7, 7)));

        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();

        // Post-migration: editor exit rebuilds a fresh gameplay mode; resolve the
        // active sprite to read its position rather than using the stale ref.
        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        GameplayModeContext resumedRuntime = SessionManager.getCurrentGameplayMode();
        assertNotNull(resumedRuntime);
        Sonic resumedPlayer = (Sonic) resumedRuntime.getSpriteManager().getSprite("sonic");
        assertNotNull(resumedPlayer);
        assertEquals(7, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(7, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertEquals(7, resumedPlayer.getCentreX());
        assertEquals(7, resumedPlayer.getCentreY());
        assertTrue(SessionManager.getCurrentGameplayMode().getResumeStash().isPresent());

        releaseAndAdvance(inputHandler, GLFW_KEY_TAB);
        releaseAndAdvance(inputHandler, GLFW_KEY_LEFT_SHIFT);
        inputHandler.handleKeyEvent(GLFW_KEY_LEFT_SHIFT, GLFW_PRESS);
        inputHandler.handleKeyEvent(GLFW_KEY_TAB, GLFW_PRESS);
        engine.getGameLoop().step();
        releaseAndAdvance(inputHandler, GLFW_KEY_TAB);
        releaseAndAdvance(inputHandler, GLFW_KEY_LEFT_SHIFT);
        inputHandler.handleKeyEvent(GLFW_KEY_F5, GLFW_PRESS);
        engine.getGameLoop().step();

        assertEquals(GameMode.LEVEL, engine.getCurrentGameMode());
        assertNotSame(gameplayMode, SessionManager.getCurrentGameplayMode());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnX());
        assertEquals(0, SessionManager.getCurrentGameplayMode().getSpawnY());
        assertTrue(SessionManager.getCurrentGameplayMode().getResumeStash().isEmpty());
    }

    @Test
    void levelManagerAppliesPersistedEditorEditsThroughInjectedSaveManager() throws IOException {
        String source = Files.readString(Path.of("src/main/java/com/openggf/level/LevelManager.java"));

        assertFalse(source.contains("new EditorSaveManager(Path.of(\"saves\"))"),
                "LevelManager must not create a process-root save manager that can quarantine real user saves in tests");
        assertTrue(source.contains("editorSaveManager.tryApplyEdits("),
                "LevelManager should apply persisted edits through the save manager injected by Engine");
    }

    @Test
    void levelManagerDoesNotApplyPersistedS3kEditorEditsUntilRuntimeOverlaysAreMutableLevelSafe() throws IOException {
        String levelManager = Files.readString(Path.of("src/main/java/com/openggf/level/LevelManager.java"));
        String saveManager = Files.readString(Path.of("src/main/java/com/openggf/editor/persistence/EditorSaveManager.java"));

        assertTrue(levelManager.contains("supportsRuntimeEditApply(gameModule.getGameId())"),
                "LevelManager must ask the editor save manager before wrapping the loaded level in MutableLevel");
        assertTrue(saveManager.contains("return gameId != GameId.S3K;"),
                "S3K persisted editor saves must be gated while S3K events require Sonic3kLevel runtime overlay support");
    }

    private GameplayModeContext createGameplayMode(Engine engine) {
        return createGameplayMode(engine, (short) 100, (short) 200);
    }

    private GameplayModeContext createGameplayMode(Engine engine, short playerX, short playerY) {
        installTempEditorSaveManager(engine);
        RomManager.getInstance().setRom(null);
        GameplayModeContext gameplayMode = SessionManager.openGameplaySession(new Sonic2GameModule());
        TestEnvironment.activeGameplayMode();
        SpriteManager spriteManager = gameplayMode.getSpriteManager();
        Sonic player = new Sonic("sonic", playerX, playerY);
        spriteManager.addSprite(player);
        gameplayMode.getCamera().setFocusedSprite(player);
        engine.getGameLoop().setGameplayMode(gameplayMode);
        return gameplayMode;
    }

    private void installTempEditorSaveManager(Engine engine) {
        try {
            setPrivateField(engine, "editorSaveManager", new EditorSaveManager(tempSaves));
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to install test editor save manager", e);
        }
    }

    private static void assumeS2RomAvailableForResumeReload() {
        assumeTrue(Files.exists(S2_ROM), "S2 ROM is not available in this environment");
    }

    private static void injectLeakedGarbageRom() throws IOException {
        Path romPath = TestTempFiles.createTempFile("editor-toggle-garbage-rom", ".bin");
        Files.write(romPath, new byte[512 * 1024]);
        romPath.toFile().deleteOnExit();
        Rom rom = new Rom();
        assertTrue(rom.open(romPath.toString()), "Expected garbage ROM temp file to open");
        RomManager.getInstance().setRom(rom);
    }

    private static void releaseAndAdvance(InputHandler inputHandler, int key) {
        inputHandler.handleKeyEvent(key, GLFW_RELEASE);
        inputHandler.update();
    }

    private static Block lookupBlock(LevelManager levelManager, byte layer, int x, int y) {
        try {
            Method getBlockAtPosition = LevelManager.class.getDeclaredMethod("getBlockAtPosition", byte.class, int.class, int.class);
            getBlockAtPosition.setAccessible(true);
            return (Block) getBlockAtPosition.invoke(levelManager, layer, x, y);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to invoke LevelManager block lookup", e);
        }
    }

    private static LevelManager engineLevelManager(Engine engine) {
        try {
            Field field = Engine.class.getDeclaredField("levelManager");
            field.setAccessible(true);
            return (LevelManager) field.get(engine);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read Engine level manager", e);
        }
    }

    private static Camera engineCamera(Engine engine) {
        try {
            Field field = Engine.class.getDeclaredField("camera");
            field.setAccessible(true);
            return (Camera) field.get(engine);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("Failed to read Engine camera", e);
        }
    }

    private static void forceControllerCursor(LevelEditorController controller, EditorCursorState cursor) throws Exception {
        Field field = LevelEditorController.class.getDeclaredField("worldCursor");
        field.setAccessible(true);
        field.set(controller, cursor);
    }

    private static void setPrivateField(Object target, String fieldName, Object value) throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void enableEditor() {
        SonicConfigurationService.getInstance().setConfigValue(SonicConfiguration.EDITOR_ENABLED, true);
        assertTrue(SonicConfigurationService.getInstance().getBoolean(SonicConfiguration.EDITOR_ENABLED));
    }

    private static final class EditorMvpFlowLevel extends AbstractLevel {
        private EditorMvpFlowLevel() {
            super(0);
            patternCount = 1;
            patterns = new Pattern[] { new Pattern() };

            chunkCount = 1;
            chunks = new Chunk[] { new Chunk() };

            blockCount = 2;
            blocks = new Block[blockCount];
            for (int i = 0; i < blockCount; i++) {
                blocks[i] = new Block(1);
                blocks[i].setChunkDesc(0, 0, new ChunkDesc(0));
            }

            solidTileCount = 1;
            solidTiles = new SolidTile[] {
                    new SolidTile(0, new byte[SolidTile.TILE_SIZE_IN_ROM], new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0)
            };

            map = new Map(2, 8, 8);
            map.setValue(0, 4, 4, (byte) 1);

            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }

            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 7;
            minY = 0;
            maxY = 7;
        }

        @Override
        public int getChunksPerBlockSide() {
            return 1;
        }

        @Override
        public int getBlockPixelSize() {
            return 1;
        }
    }

    private static final class BackgroundTilemapLevel extends AbstractLevel {
        private BackgroundTilemapLevel() {
            super(0);
            patternCount = 1;
            patterns = new Pattern[patternCount];
            patterns[0] = new Pattern();
            patterns[0].setPixel(0, 0, (byte) 1);

            chunkCount = 1;
            chunks = new Chunk[chunkCount];
            chunks[0] = new Chunk();
            chunks[0].restoreState(new int[8 * 8]);

            blockCount = 1;
            blocks = new Block[blockCount];
            blocks[0] = new Block(8);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    blocks[0].setChunkDesc(x, y, new ChunkDesc(0));
                }
            }

            solidTileCount = 1;
            solidTiles = new SolidTile[] {
                    new SolidTile(0, new byte[SolidTile.TILE_SIZE_IN_ROM], new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0)
            };

            map = new Map(2, 1, 1);
            map.setValue(0, 0, 0, (byte) 0);
            map.setValue(1, 0, 0, (byte) 0);

            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }

            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 127;
            minY = 0;
            maxY = 127;
        }

        @Override
        public int getChunksPerBlockSide() {
            return 8;
        }

        @Override
        public int getBlockPixelSize() {
            return 128;
        }
    }

    private static final class SyntheticLevel extends AbstractLevel {
        private SyntheticLevel() {
            super(0);
            patternCount = 1;
            patterns = new Pattern[patternCount];
            patterns[0] = new Pattern();
            patterns[0].setPixel(0, 0, (byte) 1);

            chunkCount = 1;
            chunks = new Chunk[chunkCount];
            chunks[0] = new Chunk();
            chunks[0].restoreState(new int[] { 0, 0, 0, 0, 0, 0 });

            blockCount = 2;
            blocks = new Block[blockCount];
            for (int i = 0; i < blockCount; i++) {
                blocks[i] = new Block(2);
                blocks[i].setChunkDesc(0, 0, new ChunkDesc(0));
                blocks[i].setChunkDesc(1, 0, new ChunkDesc(0));
                blocks[i].setChunkDesc(0, 1, new ChunkDesc(0));
                blocks[i].setChunkDesc(1, 1, new ChunkDesc(0));
            }

            solidTileCount = 1;
            solidTiles = new SolidTile[] {
                    new SolidTile(0, new byte[SolidTile.TILE_SIZE_IN_ROM], new byte[SolidTile.TILE_SIZE_IN_ROM], (byte) 0)
            };

            map = new Map(2, 4, 3);
            for (int layer = 0; layer < 2; layer++) {
                for (int y = 0; y < 3; y++) {
                    for (int x = 0; x < 4; x++) {
                        map.setValue(layer, x, y, (byte) 0);
                    }
                }
            }

            palettes = new Palette[PALETTE_COUNT];
            for (int i = 0; i < PALETTE_COUNT; i++) {
                palettes[i] = new Palette();
            }

            objects = List.of();
            rings = List.of();
            minX = 0;
            maxX = 255;
            minY = 0;
            maxY = 191;
        }

        @Override
        public int getChunksPerBlockSide() {
            return 2;
        }

        @Override
        public int getBlockPixelSize() {
            return 64;
        }
    }

}
