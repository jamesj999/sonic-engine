package com.openggf.game.sonic3k.specialstage;

import com.openggf.audio.AudioManager;
import com.openggf.audio.AudioTestFixtures;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.camera.Camera;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.game.sonic3k.audio.Sonic3kAudioProfile;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.graphics.FadeManager;
import com.openggf.graphics.GraphicsManager;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.timer.TimerManager;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TestSonic3kSpecialStageManagerSnapshot {
    private GameStateManager gameState;

    @BeforeEach
    void configureServices() {
        TestEnvironment.resetAll();
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new AudioTestFixtures.RecordingAudioBackend());
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @AfterEach
    void clearServices() {
        TestEnvironment.resetAll();
        AudioManager.getInstance().resetState();
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    @Test
    void managerSnapshotRestoresScalarsAndSubsystemsWithoutInitializationReload() {
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedManagerWithDistinctScalars(manager);
        manager.getGrid().setCellByIndex(0x44, Sonic3kSpecialStageConstants.CELL_RING);
        manager.getPlayer().initialize(0x8000, 0x2222, 0x3333, false);

        Sonic3kSpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        mutateManagerScalarsToWrongValues(manager);
        manager.getGrid().setCellByIndex(0x44, Sonic3kSpecialStageConstants.CELL_RED);

        manager.restoreRewindSnapshot(snapshot);

        assertManagerScalarsRestored(manager);
        assertAll(
                () -> assertEquals(Sonic3kSpecialStageConstants.CELL_RING, manager.getGrid().getCellByIndex(0x44)),
                () -> assertEquals(0x2222, manager.getPlayer().getXPos()));
    }

    @Test
    void managerRestoreRejectsUninitializedLiveOrSnapshotState() {
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        assertThrows(IllegalStateException.class, manager::captureRewindSnapshot);
        assertThrows(IllegalStateException.class,
                () -> manager.restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.uninitializedForTest()));
    }

    @Test
    void managerRestoreRejectsUninitializedSnapshotIntoInitializedManager() {
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedInitializedManager(manager);

        assertThrows(IllegalStateException.class,
                () -> manager.restoreRewindSnapshot(Sonic3kSpecialStageSnapshot.uninitializedForTest()));
    }

    @Test
    void managerSnapshotRestoresDurableEmeraldGameState() {
        attachRuntime();
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedInitializedManager(manager);
        manager.getPlayer().initialize(0x8000, 0x2222, 0x3333, false);

        gameState.markEmeraldCollected(0);
        gameState.markSuperEmeraldCollected(1);
        Sonic3kSpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        gameState.markEmeraldCollected(2);
        gameState.markSuperEmeraldCollected(3);
        manager.restoreRewindSnapshot(snapshot);

        assertTrue(gameState.hasEmerald(0));
        assertFalse(gameState.hasEmerald(2));
        assertTrue(gameState.hasSuperEmerald(1));
        assertFalse(gameState.hasSuperEmerald(3));
        assertEquals(1, gameState.getEmeraldCount());
    }

    @Test
    void managerRestoreSetsAudioSpeedFromMusicSpeedFlag() {
        attachRuntime();
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedInitializedManager(manager);
        manager.getPlayer().initialize(0x8000, 0x2222, 0x3333, false);

        set(manager, "musicSpedUp", false);
        Sonic3kSpecialStageSnapshot normalSpeed = manager.captureRewindSnapshot();
        manager.restoreRewindSnapshot(normalSpeed);
        assertTrue(hasSpeedMultiplier(1));
        assertOnlyAudioSpeedRestoreCalls();

        AudioManager.getInstance().commandTimeline().clear();
        set(manager.getPlayer(), "rate", 0x1800);
        set(manager, "musicSpedUp", true);
        Sonic3kSpecialStageSnapshot spedUp = manager.captureRewindSnapshot();
        set(manager.getPlayer(), "rate", 0x1000);
        set(manager, "musicSpedUp", false);
        manager.restoreRewindSnapshot(spedUp);

        assertTrue(hasSpeedMultiplier(24));
        assertOnlyAudioSpeedRestoreCalls();
    }

    @Test
    void specialStageRingCollectionQueuesRomRingRightSound() throws Exception {
        AudioManager audio = AudioManager.getInstance();
        audio.setSoundMap(new Sonic3kAudioProfile().getSoundMap());
        audio.resetRingSound();
        var requestedSounds = new java.util.ArrayList<Integer>();
        audio.setRequestObserver((requestClass, rawSoundId) ->
                requestedSounds.add(rawSoundId));

        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        set(manager, "ringsLeft", 2);
        set(manager, "ringsCollected", 0);

        Method collectRing = Sonic3kSpecialStageManager.class
                .getDeclaredMethod("collectRing", int.class);
        collectRing.setAccessible(true);
        collectRing.invoke(manager, 0x44);

        assertEquals(java.util.List.of(Sonic3kSfx.RING_RIGHT.id), requestedSounds,
                "S3K's special-stage ring path always queues sfx_RingRight");
    }

    @Test
    void managerRestoreRecachesRestoredPaletteAndDoesNotReplayAudio() {
        GraphicsManager graphics = mock(GraphicsManager.class);
        configureEngineServices(graphics);
        Sonic3kSpecialStageManager manager = new Sonic3kSpecialStageManager();
        seedInitializedManager(manager);
        manager.getPlayer().initialize(0x8000, 0x2222, 0x3333, false);
        Sonic3kSpecialStagePalette palette = seededPalette(16);
        set(manager, "palette", palette);

        Sonic3kSpecialStageSnapshot snapshot = manager.captureRewindSnapshot();

        palette.getPalette(0).getColor(0).r = (byte) 99;
        AudioManager.getInstance().commandTimeline().clear();
        manager.restoreRewindSnapshot(snapshot);

        verify(graphics).cachePaletteTexture(any(Palette.class), eq(0));
        verify(graphics).cachePaletteTexture(any(Palette.class), eq(1));
        verify(graphics).cachePaletteTexture(any(Palette.class), eq(2));
        verify(graphics).cachePaletteTexture(any(Palette.class), eq(3));
        assertEquals(16, palette.getPalette(0).getColor(0).r & 0xFF);
        assertTrue(hasSpeedMultiplier(1));
        assertOnlyAudioSpeedRestoreCalls();
    }

    private void attachRuntime() {
        GameplayModeContext context = SessionManager.openGameplaySession(new Sonic3kGameModule());
        gameState = new GameStateManager();
        context.attachGameplayManagers(
                new Camera(),
                new TimerManager(),
                gameState,
                new FadeManager(),
                new GameRng(GameRng.Flavour.S3K),
                new DefaultSolidExecutionRegistry());
        context.attachSharedRegistries(
                new ZoneRuntimeRegistry(),
                new PaletteOwnershipRegistry(),
                new AnimatedTileChannelGraph(),
                new SpecialRenderEffectRegistry(),
                new AdvancedRenderModeController(),
                new ZoneLayoutMutationPipeline());
        WaterSystem water = new WaterSystem();
        ParallaxManager parallax = new ParallaxManager();
        TerrainCollisionManager terrain = new TerrainCollisionManager();
        CollisionSystem collision = new CollisionSystem(terrain);
        SpriteManager sprites = new SpriteManager();
        LevelManager level = mock(LevelManager.class);
        context.attachLevelManagers(water, parallax, terrain, collision, sprites, level);
    }

    private static void configureEngineServices(GraphicsManager graphics) {
        EngineContext base = EngineContext.fromLegacySingletonsForBootstrap();
        EngineServices.configure(new EngineContext(
                base.configuration(),
                graphics,
                base.audio(),
                base.roms(),
                base.profiler(),
                base.debugOverlay(),
                base.playbackDebug(),
                base.romDetection(),
                base.crossGameFeatures()));
    }

    private static void seedInitializedManager(Sonic3kSpecialStageManager manager) {
        set(manager, "initialized", true);
        set(manager, "finished", false);
        set(manager, "currentStage", 2);
        set(manager, "emeraldCollected", false);
        set(manager, "superEmeraldMode", false);
        set(manager, "tailsEnabled", true);
        set(manager, "playerCharacter", com.openggf.game.PlayerCharacter.SONIC_AND_TAILS);
    }

    private static void seedManagerWithDistinctScalars(Sonic3kSpecialStageManager manager) {
        set(manager, "currentStage", 5);
        set(manager, "initialized", true);
        set(manager, "finished", true);
        set(manager, "emeraldCollected", true);
        set(manager, "superEmeraldMode", true);
        set(manager, "ringsCollected", 17);
        set(manager, "spheresLeft", 88);
        set(manager, "ringsLeft", 5);
        set(manager, "frameCounter", 1234);
        set(manager, "heldButtons", 0x0F);
        set(manager, "pressedButtons", 0x70);
        set(manager, "p2HeldButtons", 0x10);
        set(manager, "clearRoutine", 3);
        set(manager, "clearTimer", 9);
        set(manager, "emeraldTimer", 7);
        set(manager, "emeraldInteractIndex", 0x155);
        set(manager, "exitSpinStarted", true);
        set(manager, "palFadeDelay", 1);
        set(manager, "musicSpedUp", true);
        set(manager, "ringAnimTimer", 2);
        set(manager, "ringAnimFrame", 1);
        set(manager, "bannerPhase", 4);
        set(manager, "bannerTimer", 31);
        set(manager, "bannerOffset", -12);
        set(manager, "tailsAnimTimer", 0x1200);
        set(manager, "tailsMappingFrame", 4);
        set(manager, "tailsTailsAnimTimer", 0x3400);
        set(manager, "tailsTailsMappingFrame", 6);
        set(manager, "tailsJumping", 0x80);
        set(manager, "tailsJumpHeight", -0x10000L);
        set(manager, "tailsJumpVelocity", -0x2000L);
        set(manager, "tailsEnabled", false);
        set(manager, "playerCharacter", PlayerCharacter.KNUCKLES);
        set(manager, "spriteDebugMode", true);
        set(manager, "useSkLayouts", true);
    }

    private static void mutateManagerScalarsToWrongValues(Sonic3kSpecialStageManager manager) {
        set(manager, "currentStage", 1);
        set(manager, "initialized", false);
        set(manager, "finished", false);
        set(manager, "emeraldCollected", false);
        set(manager, "superEmeraldMode", false);
        set(manager, "ringsCollected", 0);
        set(manager, "spheresLeft", 0);
        set(manager, "ringsLeft", 0);
        set(manager, "frameCounter", 0);
        set(manager, "heldButtons", 0x22);
        set(manager, "pressedButtons", 0x11);
        set(manager, "p2HeldButtons", 0x44);
        set(manager, "clearRoutine", 1);
        set(manager, "clearTimer", 77);
        set(manager, "emeraldTimer", 66);
        set(manager, "emeraldInteractIndex", 0x011);
        set(manager, "exitSpinStarted", false);
        set(manager, "palFadeDelay", 8);
        set(manager, "musicSpedUp", false);
        set(manager, "ringAnimTimer", 6);
        set(manager, "ringAnimFrame", 2);
        set(manager, "bannerPhase", 1);
        set(manager, "bannerTimer", 2);
        set(manager, "bannerOffset", 3);
        set(manager, "tailsAnimTimer", 0x2100);
        set(manager, "tailsMappingFrame", 9);
        set(manager, "tailsTailsAnimTimer", 0x4300);
        set(manager, "tailsTailsMappingFrame", 10);
        set(manager, "tailsJumping", 0);
        set(manager, "tailsJumpHeight", 0L);
        set(manager, "tailsJumpVelocity", 0L);
        set(manager, "tailsEnabled", true);
        set(manager, "playerCharacter", PlayerCharacter.SONIC_AND_TAILS);
        set(manager, "spriteDebugMode", false);
        set(manager, "useSkLayouts", false);
        assertEquals(false, get(manager, "initialized"));
        set(manager, "initialized", true);
    }

    private static void assertManagerScalarsRestored(Sonic3kSpecialStageManager manager) {
        assertAll(
                () -> assertEquals(5, manager.getCurrentStage()),
                () -> assertEquals(true, get(manager, "initialized")),
                () -> assertEquals(true, manager.isFinished()),
                () -> assertEquals(true, manager.hasEmeraldCollected()),
                () -> assertEquals(true, get(manager, "superEmeraldMode")),
                () -> assertEquals(17, manager.getRingsCollected()),
                () -> assertEquals(88, manager.getSpheresLeft()),
                () -> assertEquals(5, manager.getRingsLeft()),
                () -> assertEquals(1234, manager.getFrameCounter()),
                () -> assertEquals(0x0F, get(manager, "heldButtons")),
                () -> assertEquals(0x70, get(manager, "pressedButtons")),
                () -> assertEquals(0x10, get(manager, "p2HeldButtons")),
                () -> assertEquals(3, manager.getClearRoutine()),
                () -> assertEquals(9, manager.getClearTimer()),
                () -> assertEquals(7, get(manager, "emeraldTimer")),
                () -> assertEquals(0x155, get(manager, "emeraldInteractIndex")),
                () -> assertEquals(true, get(manager, "exitSpinStarted")),
                () -> assertEquals(1, get(manager, "palFadeDelay")),
                () -> assertEquals(true, get(manager, "musicSpedUp")),
                () -> assertEquals(2, get(manager, "ringAnimTimer")),
                () -> assertEquals(1, manager.getRingAnimFrame()),
                () -> assertEquals(4, manager.getBannerPhase()),
                () -> assertEquals(31, get(manager, "bannerTimer")),
                () -> assertEquals(-12, manager.getBannerOffset()),
                () -> assertEquals(0x1200, get(manager, "tailsAnimTimer")),
                () -> assertEquals(4, manager.getTailsMappingFrame()),
                () -> assertEquals(0x3400, get(manager, "tailsTailsAnimTimer")),
                () -> assertEquals(6, manager.getTailsTailsMappingFrame()),
                () -> assertEquals(0x80, get(manager, "tailsJumping")),
                () -> assertEquals(-0x10000L, manager.getTailsJumpHeight()),
                () -> assertEquals(-0x2000L, get(manager, "tailsJumpVelocity")),
                () -> assertEquals(false, manager.isTailsEnabled()),
                () -> assertEquals(PlayerCharacter.KNUCKLES, manager.getPlayerCharacter()),
                () -> assertEquals(true, manager.isSpriteDebugMode()),
                () -> assertEquals(true, get(manager, "useSkLayouts")));
    }

    private static Sonic3kSpecialStagePalette seededPalette(int baseColor) {
        Sonic3kSpecialStagePalette palette = new Sonic3kSpecialStagePalette();
        for (int line = 0; line < 4; line++) {
            Palette linePalette = new Palette();
            linePalette.getColor(0).r = (byte) (baseColor + line);
            palette.getPalettes()[line] = linePalette;
        }
        return palette;
    }

    private void assertOnlyAudioSpeedRestoreCalls() {
        var commands = AudioManager.getInstance().commandTimeline().entries().stream()
                .map(entry -> entry.command()).toList();
        assertFalse(commands.isEmpty());
        assertTrue(commands.stream().allMatch(AudioCommand.SetSpeedMultiplier.class::isInstance),
                () -> "Unexpected audio replay/fade commands: " + commands);
    }

    private boolean hasSpeedMultiplier(int multiplier) {
        return AudioManager.getInstance().commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .anyMatch(command -> command.equals(new AudioCommand.SetSpeedMultiplier(multiplier)));
    }

    private static Object get(Object target, String field) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void set(Object target, String field, Object value) {
        try {
            Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }
}
