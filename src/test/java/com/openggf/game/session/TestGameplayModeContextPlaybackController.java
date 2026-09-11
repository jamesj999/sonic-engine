package com.openggf.game.session;

import com.openggf.camera.Camera;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.rewind.EngineStepper;
import com.openggf.game.rewind.InputSource;
import com.openggf.game.rewind.PlaybackController;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.graphics.FadeManager;
import com.openggf.timer.TimerManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the on-demand {@link PlaybackController} installation on
 * {@link GameplayModeContext}.
 */
class TestGameplayModeContextPlaybackController {

    @BeforeEach
    void configureServices() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
    }

    private static GameplayModeContext buildAttachedContext() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        GameplayModeContext ctx = new GameplayModeContext(world);

        Camera camera = new Camera();
        TimerManager timers = new TimerManager();
        GameStateManager gameState = new GameStateManager();
        FadeManager fade = new FadeManager();
        GameRng rng = new GameRng(GameRng.Flavour.S1_S2);
        DefaultSolidExecutionRegistry solid = new DefaultSolidExecutionRegistry();

        ctx.attachGameplayManagers(camera, timers, gameState, fade, rng, solid);
        return ctx;
    }

    /** Stub InputSource returning {@code count} frames of empty inputs. */
    private static InputSource stubInputs(int count) {
        return new InputSource() {
            @Override
            public int frameCount() { return count; }

            @Override
            public Bk2FrameInput read(int frame) {
                return new Bk2FrameInput(frame, 0, 0, false, "stub");
            }
        };
    }

    @Test
    void getPlaybackControllerReturnsNullBeforeInstall() {
        GameplayModeContext ctx = buildAttachedContext();
        assertNull(ctx.getPlaybackController(),
                "getPlaybackController() must return null before installPlaybackController is called");
    }

    @Test
    void installAndGetReturnSameInstance() {
        GameplayModeContext ctx = buildAttachedContext();
        AtomicInteger stepCount = new AtomicInteger();
        EngineStepper stepper = in -> {
            stepCount.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        PlaybackController pc = ctx.installPlaybackController(stubInputs(10), stepper, 60);
        assertNotNull(pc);
        assertSame(pc, ctx.getPlaybackController(),
                "getPlaybackController() must return the installed instance");
    }

    @Test
    void installAndTickInvokesStepperOnce() {
        GameplayModeContext ctx = buildAttachedContext();
        AtomicInteger stepCount = new AtomicInteger();
        EngineStepper stepper = in -> {
            stepCount.incrementAndGet();
            return com.openggf.LevelFrameResult.GAMEPLAY_FRAME;
        };

        PlaybackController pc = ctx.installPlaybackController(stubInputs(10), stepper, 60);
        // Default state is PLAYING; tick() should advance one frame.
        pc.tick();
        assertEquals(1, stepCount.get(),
                "One tick() in PLAYING state should invoke the stepper exactly once");
    }

    @Test
    void installThrowsWhenRegistryNotInitialised() {
        WorldSession world = new WorldSession(new Sonic2GameModule());
        GameplayModeContext ctx = new GameplayModeContext(world);
        // attachGameplayManagers not called — registry is null

        assertThrows(IllegalStateException.class,
                () -> ctx.installPlaybackController(
                        stubInputs(10),
                        in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                        60),
                "installPlaybackController should throw when registry is not initialised");
    }

    @Test
    void tearDownClearsPlaybackController() {
        GameplayModeContext ctx = buildAttachedContext();
        ctx.installPlaybackController(
                stubInputs(10),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                60);
        assertNotNull(ctx.getPlaybackController());

        ctx.tearDownManagers();
        assertNull(ctx.getPlaybackController(),
                "getPlaybackController() should return null after tearDownManagers");
    }

    @Test
    void getRewindControllerIsNullBeforeInstall() {
        GameplayModeContext ctx = buildAttachedContext();
        assertNull(ctx.getRewindController(),
                "getRewindController() must return null before installPlaybackController is called");
    }

    @Test
    void getRewindControllerIsNonNullAfterInstall() {
        GameplayModeContext ctx = buildAttachedContext();
        ctx.installPlaybackController(
                stubInputs(10),
                in -> com.openggf.LevelFrameResult.GAMEPLAY_FRAME,
                60);
        assertNotNull(ctx.getRewindController());
    }
}
