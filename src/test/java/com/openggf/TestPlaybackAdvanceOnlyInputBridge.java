package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.GameMode;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.replay.runs.TraceRunFrameDriver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestPlaybackAdvanceOnlyInputBridge {

    private final PlaybackDebugManager playback = PlaybackDebugManager.getInstance();

    @AfterEach
    void tearDown() {
        playback.endSession();
        SessionManager.clear();
    }

    @Test
    void advanceOnlyActionEdgeRemainsPendingUntilOneGameplayDispatch() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(0, 0)
                    .build();
            GameLoop loop = new GameLoop(new InputHandler());
            loop.changeGameModeWithoutRewindBoundary(GameMode.LEVEL);
            playback.startSession(heldActionMovie(), 0);
            playback.setFrameObserver(new FirstRowAdvanceOnlyObserver());

            invokeSyncPlaybackInputBridge(loop);
            assertTrue(playback.isCurrentForcedJumpPress(),
                    "the ADVANCE_ONLY row introduces the action press edge");
            invokeUpdateLevelMode(loop);

            assertTrue(playback.isCurrentForcedJumpPress(),
                    "skipping gameplay must leave the action edge pending");
            assertFalse(fixture.sprite().getAir(),
                    "ADVANCE_ONLY must not execute player gameplay");

            invokeSyncPlaybackInputBridge(loop);
            assertTrue(playback.isCurrentForcedJumpPress(),
                    "publishing the following held row must not erase the pending edge");
            assertEquals(0x01, loop.getInputHandler().logical()
                    .player1().actionPressedMask(),
                    "the first gameplay row must publish the pending action edge");
            assertFalse(fixture.sprite().isForcedJumpPress(),
                    "the playback bridge must not mutate gameplay-owned input latches");
            invokeUpdateLevelMode(loop);

            assertTrue(playback.isCurrentForcedJumpPress(),
                    "the initial Process_Sprites setup pass must not consume the pending gameplay edge");
            assertFalse(GameServices.level().hasPendingInitialProcessSpritesPass(),
                    "the setup-only retry should consume the fresh-level setup authority");
            invokeUpdateLevelMode(loop);

            assertFalse(playback.isCurrentForcedJumpPress(),
                    "the gameplay dispatch must consume the pending edge exactly once");
            assertTrue(fixture.sprite().getAir(),
                    "the unlocked gameplay dispatch must consume the carried jump edge");
            invokeSyncPlaybackInputBridge(loop);
            assertFalse(playback.isCurrentForcedJumpPress(),
                    "a later held row must not repeat the consumed action edge");
            assertFalse(fixture.sprite().isForcedJumpPress(),
                    "the consumed edge must not be republished to gameplay");
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkipIntros != null ? oldSkipIntros : false);
        }
    }

    @Test
    void immediateLoadedLevelPublicationRemainsOwnedByPlaybackBridgeForCleanup() throws Exception {
        InputHandler input = new InputHandler();
        GameLoop loop = new GameLoop(input);
        playback.startSession(heldActionMovie(), 0);

        loop.applyScheduledPlaybackInputImmediately();
        assertTrue(input.hasLogicalOverride(),
                "the synchronously loaded player must see trace input before its first tick");

        playback.endSession();
        invokeSyncPlaybackInputBridge(loop);
        assertFalse(input.hasLogicalOverride(),
                "the bridge must clear the immediate publication when playback ends");
        assertEquals(0, input.logical().player1().heldMask(),
                "same-step consumers must see refreshed live input, not stale BK2 state");
    }

    @Test
    void controlLockSuppressesRecordedActionEdgeForMovement() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(0, 0)
                    .build();
            GameLoop loop = new GameLoop(new InputHandler());
            loop.changeGameModeWithoutRewindBoundary(GameMode.LEVEL);
            playback.startSession(heldActionMovie(), 0);
            fixture.sprite().setControlLocked(true);

            invokeSyncPlaybackInputBridge(loop);
            invokeUpdateLevelMode(loop);
            invokeUpdateLevelMode(loop);

            assertEquals(0x01, loop.getInputHandler().logical()
                    .player1().actionPressedMask(),
                    "the recorded raw controller edge remains published");
            assertTrue(fixture.sprite().isRawControllerJumpJustPressed(),
                    "objects retain the raw controller press while movement is locked");
            assertFalse(fixture.sprite().getAir(),
                    "Ctrl_1_locked must prevent the recorded press from starting a jump");
            assertFalse(fixture.sprite().isForcedJumpPress(),
                    "playback publication must not leave a gameplay-owned latch behind");
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkipIntros != null ? oldSkipIntros : false);
        }
    }

    @Test
    void recordedStartDoesNotToggleVisibleUserPause() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        InputHandler input = new InputHandler();
        GameLoop loop = new GameLoop(input);
        loop.changeGameModeWithoutRewindBoundary(GameMode.LEVEL);
        playback.startSession(startEdgeMovie(), 0);

        loop.applyScheduledPlaybackInputImmediately();
        loop.step();

        assertFalse(loop.isUserPaused(),
                "recorded Start belongs to ROM input and must not toggle the visual pause overlay");

        int pauseKey = SonicConfigurationService.getInstance()
                .getInt(SonicConfiguration.PAUSE_KEY);
        input.handleKeyEvent(pauseKey, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        loop.step();

        assertTrue(loop.isUserPaused(),
                "the configured live pause key must remain usable during playback");
    }

    @Test
    void driverOwnedPresentationStartDoesNotToggleVisibleUserPause() {
        HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        InputHandler input = new InputHandler();
        GameLoop loop = new GameLoop(input);
        loop.changeGameModeWithoutRewindBoundary(GameMode.LEVEL);
        Bk2Movie movie = startEdgeMovie();
        TraceRunFrameDriver driver = new TraceRunFrameDriver();
        SessionManager.getCurrentGameplayMode()
                .installTraceRunFrameDriver(driver);

        driver.execute(
                new TraceRunFrameDriver.Step(
                        TraceRunFrameDriver.Disposition.PRESENTATION_VBLANK,
                        0, false),
                new TraceRunFrameDriver.Hooks<Void>() {
                    @Override public void preparePhysicalRow(
                            TraceRunFrameDriver.Step step) {
                        input.setLogicalOverride(
                                RecordedInputSnapshots.fromBk2(
                                        movie.getFrame(0), null));
                    }
                    @Override public void prepareHardwareTiming(
                            TraceRunFrameDriver.Step step) { }
                    @Override public Void captureBefore(
                            TraceRunFrameDriver.Step step) { return null; }
                    @Override public void runProductionLifecycle(
                            TraceRunFrameDriver.Step step) { loop.step(); }
                    @Override public void advancePhysicalRow(
                            TraceRunFrameDriver.Step step) { }
                    @Override public Void captureAfter(
                            TraceRunFrameDriver.Step step) { return null; }
                    @Override public void compare(
                            TraceRunFrameDriver.Step step,
                            Void before, Void after) { }
                    @Override public void afterStep(
                            TraceRunFrameDriver.Step step) {
                        input.clearLogicalOverride();
                    }
                });

        assertFalse(loop.isUserPaused(),
                "recorded Start belongs to run presentation, not the visual pause overlay");
    }

    private static Bk2Movie heldActionMovie() {
        int heldMask = AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP;
        return new Bk2Movie(
                Path.of("synthetic-advance-only-action.bk2"),
                "logkey",
                Map.of(),
                List.of(
                        new Bk2FrameInput(0, heldMask, 1, false, "press"),
                        new Bk2FrameInput(1, heldMask, 1, false, "hold"),
                        new Bk2FrameInput(2, heldMask, 1, false, "hold")),
                1);
    }

    private static Bk2Movie startEdgeMovie() {
        return new Bk2Movie(
                Path.of("synthetic-start-edge.bk2"),
                "logkey",
                Map.of(),
                List.of(
                        new Bk2FrameInput(0, 0, 0, true, "start"),
                        new Bk2FrameInput(1, 0, 0, false, "release")),
                1);
    }

    private static void invokeSyncPlaybackInputBridge(GameLoop loop) throws Exception {
        Method method = GameLoop.class.getDeclaredMethod("syncPlaybackInputBridge");
        method.setAccessible(true);
        method.invoke(loop);
    }

    private static void invokeUpdateLevelMode(GameLoop loop) throws Exception {
        GameLoopTestStep.invoke(loop, "updateLevelMode", new Class<?>[] { boolean.class }, false);
    }

    private static final class FirstRowAdvanceOnlyObserver
            implements PlaybackDebugManager.PlaybackFrameObserver {
        @Override
        public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
            return frame.frameIndex() == 0;
        }

        @Override
        public int vblankAdvanceCountOnSkippedTick(Bk2FrameInput frame) {
            return 0;
        }

        @Override
        public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
        }
    }
}
