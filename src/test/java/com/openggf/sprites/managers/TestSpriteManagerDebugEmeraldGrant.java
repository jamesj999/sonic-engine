package com.openggf.sprites.managers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.openggf.audio.AudioManager;
import com.openggf.audio.NullAudioBackend;
import com.openggf.audio.rewind.AudioCommand;
import com.openggf.camera.Camera;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.control.InputHandler;
import com.openggf.game.GameRng;
import com.openggf.game.GameServices;
import com.openggf.game.GameStateManager;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.mutation.ZoneLayoutMutationPipeline;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.render.AdvancedRenderModeController;
import com.openggf.game.render.SpecialRenderEffectRegistry;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.sonic2.audio.Sonic2AudioProfile;
import com.openggf.game.sonic2.audio.Sonic2Music;
import com.openggf.game.zone.ZoneRuntimeRegistry;
import com.openggf.graphics.FadeManager;
import com.openggf.level.LevelManager;
import com.openggf.level.ParallaxManager;
import com.openggf.level.WaterSystem;
import com.openggf.physics.CollisionSystem;
import com.openggf.physics.TerrainCollisionManager;
import com.openggf.timer.TimerManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.glfw.GLFW;

class TestSpriteManagerDebugEmeraldGrant {
    private com.openggf.data.Rom openedRom;

    @BeforeEach
    void setUp() {
        TestEnvironment.resetAll();
        TestEnvironment.configureGameModuleFixture(SonicGame.SONIC_2);
        AudioManager.getInstance().resetState();
        AudioManager.getInstance().setBackend(new NullAudioBackend());
        // Sonic 2 resolves a request against its ROM-backed sample before the
        // driver publishes it and rejects the request outright when no ROM is
        // installed, so the chime needs the real ROM to reach the timeline.
        java.io.File romFile = com.openggf.tests.RomTestUtils
                .ensureSonic2RomAvailable();
        org.junit.jupiter.api.Assumptions.assumeTrue(romFile != null,
                "Sonic 2 REV01 ROM is required to resolve an S2 request");
        openedRom = new com.openggf.data.Rom();
        assertTrue(openedRom.open(romFile.getAbsolutePath()));
        AudioManager.getInstance().setRom(openedRom);
        AudioManager.getInstance().setAudioProfile(new Sonic2AudioProfile());

        GameplayModeContext mode = TestEnvironment.activeGameplayMode();
        SpriteManager spriteManager = new SpriteManager();
        GameStateManager gameStateManager = new GameStateManager();
        CollisionSystem collisionSystem = mock(CollisionSystem.class);
        when(collisionSystem.key()).thenReturn("collision");
        ZoneLayoutMutationPipeline mutationPipeline =
                mock(ZoneLayoutMutationPipeline.class);
        when(mutationPipeline.key()).thenReturn("mutation-pipeline");
        mode.attachGameplayManagers(
                new Camera(),
                new TimerManager(),
                gameStateManager,
                new FadeManager(),
                new GameRng(GameRng.Flavour.S1_S2),
                new DefaultSolidExecutionRegistry(),
                null,
                AudioManager.getInstance());
        mode.attachLevelManagers(
                new WaterSystem(),
                new ParallaxManager(),
                mock(TerrainCollisionManager.class),
                collisionSystem,
                spriteManager,
                mock(LevelManager.class));
        mode.attachSharedRegistries(
                new ZoneRuntimeRegistry(),
                new PaletteOwnershipRegistry(),
                new AnimatedTileChannelGraph(),
                new SpecialRenderEffectRegistry(),
                new AdvancedRenderModeController(),
                mutationPipeline);
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        AudioManager.getInstance().resetState();
        if (openedRom != null) {
            openedRom.close();
            openedRom = null;
        }
    }

    @Test
    void giveEmeraldsDebugKeyPlaysEmeraldChimeWhenEmeraldsAreGranted() {
        EngineServices.current().configuration().setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, true);
        InputHandler input = new InputHandler();
        int giveEmeraldsKey = EngineServices.current().configuration().getInt(SonicConfiguration.GIVE_EMERALDS_KEY);
        input.handleKeyEvent(giveEmeraldsKey, GLFW.GLFW_PRESS);

        GameServices.sprites().update(input);

        assertEquals(7, GameServices.gameState().getEmeraldCount());
        // S2 requests become commands at the frame's forward presentation
        // boundary, the way the game loop drives them.
        AudioManager.getInstance().presentFrame(
                com.openggf.audio.presentation.PresentationMode.FORWARD);
        var commands = musicCommands();
        assertEquals(1, commands.size());
        assertEquals(Sonic2Music.GOT_EMERALD.id, commands.getFirst().musicId());
    }

    @Test
    void giveEmeraldsDebugKeyIsIgnoredWhenDebugViewIsDisabled() {
        EngineServices.current().configuration().setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, false);
        InputHandler input = new InputHandler();
        int giveEmeraldsKey = EngineServices.current().configuration().getInt(SonicConfiguration.GIVE_EMERALDS_KEY);
        input.handleKeyEvent(giveEmeraldsKey, GLFW.GLFW_PRESS);

        GameServices.sprites().update(input);

        assertEquals(0, GameServices.gameState().getEmeraldCount());
        assertEquals(0, musicCommands().size());
    }

    private static java.util.List<AudioCommand.PlayMusic> musicCommands() {
        return AudioManager.getInstance().commandTimeline().entries().stream()
                .map(entry -> entry.command())
                .filter(AudioCommand.PlayMusic.class::isInstance)
                .map(AudioCommand.PlayMusic.class::cast)
                .toList();
    }
}
