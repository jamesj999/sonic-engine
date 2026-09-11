package com.openggf;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.game.GameServices;
import com.openggf.game.InitialProcessSpritesLifecycle;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.rewind.RewindSeekAwareEngineStepper;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.HeadlessTestFixture;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.trace.replay.TraceReplayFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.clearInvocations;

@RequiresRom(SonicGame.SONIC_3K)
class TestTraceSessionLauncherAdvanceOnlyRewind {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void visualRewindAdvanceOnlyLatchesInputWithoutExecutingGameplay() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        Object oldSkipIntros = config.getConfigValue(SonicConfiguration.S3K_SKIP_INTROS);
        config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS, true);
        try {
            HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                    .withZoneAndAct(0, 0)
                    .build();
            TraceReplayFixture timingFixture = mock(TraceReplayFixture.class);
            GameLoop loop = new GameLoop(new InputHandler());
            Bk2Movie movie = heldActionMovie();
            RewindSeekAwareEngineStepper stepper =
                    visualStepper(loop, movie, advanceOnlyTrace(), timingFixture);
            int spriteFrame = GameServices.sprites().getFrameCounter();
            int levelFrame = GameServices.level().getFrameCounter();
            int vblank = GameServices.level().getObjectManager().getVblaCounter();

            stepper.step(movie.getFrame(0));

            assertEquals(spriteFrame, GameServices.sprites().getFrameCounter(),
                    "ADVANCE_ONLY must not dispatch playable gameplay");
            assertEquals(levelFrame, GameServices.level().getFrameCounter(),
                    "ADVANCE_ONLY must not run level/object updates");
            assertEquals(vblank, GameServices.level().getObjectManager().getVblaCounter(),
                    "ADVANCE_ONLY must not advance the VBlank/object clock");
            assertEquals(AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP,
                    loop.getInputHandler().logical().player1().heldMask(),
                    "the input-only row must still latch its held controller state");
            assertEquals(0, fixture.sprite().getForcedInputMask(),
                    "recorded raw input must not overwrite game-owned forced input");
            assertEquals(0x01, loop.getInputHandler().logical()
                    .player1().actionPressedMask(),
                    "the input-only action edge must remain pending in recorded input");
            assertFalse(fixture.sprite().isForcedJumpPress(),
                    "rewind input publication must not mutate gameplay-owned latches");
            assertFalse(fixture.sprite().getAir(),
                    "the input-only row must leave player gameplay untouched");

            stepper.step(movie.getFrame(1));

            assertEquals(spriteFrame, GameServices.sprites().getFrameCounter(),
                    "the structural level boundary remains a no-gameplay row");
            assertEquals(0x01, loop.getInputHandler().logical()
                    .player1().actionPressedMask(),
                    "the action edge must remain pending across later no-gameplay rows");
            assertFalse(fixture.sprite().isForcedJumpPress());

            int setupRetries = 0;
            do {
                stepper.step(movie.getFrame(2));
                setupRetries++;
            } while (GameServices.sprites().getFrameCounter() == spriteFrame
                    && setupRetries < 120);

            assertEquals(spriteFrame + 1, GameServices.sprites().getFrameCounter(),
                    "the following gameplay row must dispatch exactly one playable tick");
            assertTrue(fixture.sprite().getAir(),
                    "the following held gameplay row must consume the pending jump edge");
            assertTrue(fixture.sprite().getYSpeed() < 0);
            assertFalse(fixture.sprite().isForcedJumpPress(),
                    "the gameplay dispatch must consume the action edge once");

            clearInvocations(timingFixture);
            stepper.restoreToFrame(0, movie.getFrame(0));
            stepper.step(movie.getFrame(0));
            verify(timingFixture).beginTraceRow(1, 1);
        } finally {
            config.setConfigValue(SonicConfiguration.S3K_SKIP_INTROS,
                    oldSkipIntros != null ? oldSkipIntros : false);
        }
    }

    @Test
    void visualRewindRestoreReconstructsPendingActionUntilGameplayConsumesIt()
            throws Exception {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS, true);
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        GameLoop loop = new GameLoop(new InputHandler());
        Bk2Movie movie = heldActionMovie();
        RewindSeekAwareEngineStepper stepper = visualStepper(
                loop, movie, advanceOnlyTrace(), mock(TraceReplayFixture.class));

        stepper.restoreToFrame(1, movie.getFrame(1));

        assertEquals(0x01, loop.getInputHandler().logical()
                .player1().actionPressedMask(),
                "restore inside the no-gameplay interval must reconstruct the exact edge");
        assertFalse(fixture.sprite().isForcedJumpPress(),
                "restored pending replay state must not be stored in the player");

        int spriteFrame = GameServices.sprites().getFrameCounter();
        int retries = 0;
        do {
            stepper.step(movie.getFrame(2));
            retries++;
        } while (GameServices.sprites().getFrameCounter() == spriteFrame
                && retries < 120);

        assertTrue(fixture.sprite().getAir(),
                "the next unlocked gameplay dispatch consumes the reconstructed edge");
        stepper.step(movie.getFrame(2));
        assertEquals(0, loop.getInputHandler().logical()
                .player1().actionPressedMask(),
                "a later row must not repeat the consumed reconstructed edge");
    }

    @Test
    void visualRewindRestoreAfterGameplayDoesNotResurrectEarlierAction() throws Exception {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS, true);
        HeadlessTestFixture.builder().withZoneAndAct(0, 0).build();
        GameLoop loop = new GameLoop(new InputHandler());
        Bk2Movie movie = heldActionMovie();
        RewindSeekAwareEngineStepper stepper = visualStepper(
                loop, movie, advanceOnlyTrace(), mock(TraceReplayFixture.class));

        stepper.restoreToFrame(2, movie.getFrame(2));

        assertEquals(0, loop.getInputHandler().logical()
                .player1().actionPressedMask(),
                "a gameplay boundary must consume earlier pending action edges");
    }

    @Test
    void visualRewindRestoredRawActionStillRespectsControlLock() throws Exception {
        SonicConfigurationService.getInstance().setConfigValue(
                SonicConfiguration.S3K_SKIP_INTROS, true);
        HeadlessTestFixture fixture = HeadlessTestFixture.builder()
                .withZoneAndAct(0, 0)
                .build();
        GameLoop loop = new GameLoop(new InputHandler());
        Bk2Movie movie = heldActionMovie();
        RewindSeekAwareEngineStepper stepper = visualStepper(
                loop, movie, advanceOnlyTrace(), mock(TraceReplayFixture.class));

        stepper.restoreToFrame(1, movie.getFrame(1));
        assertEquals(0x01, loop.getInputHandler().logical()
                .player1().actionPressedMask(),
                "the locked case must begin with a genuinely reconstructed edge");
        fixture.sprite().setControlLocked(true);

        int spriteFrame = GameServices.sprites().getFrameCounter();
        int retries = 0;
        do {
            stepper.step(movie.getFrame(2));
            retries++;
        } while (GameServices.sprites().getFrameCounter() == spriteFrame
                && retries < 120);

        assertTrue(fixture.sprite().isRawControllerJumpJustPressed(),
                "objects must still observe the restored raw controller edge");
        assertFalse(fixture.sprite().getAir(),
                "Ctrl_1_locked must suppress the restored edge for movement");
        assertFalse(fixture.sprite().isForcedJumpPress());
    }

    @Test
    void visualRewindDefersAdvanceOnlyNoVblankMarkerToNextProductionClosure()
            throws Exception {
        HeadlessTestFixture.builder().withZoneAndAct(0, 0).build();
        TraceData trace = starvedAdvanceOnlyTrace();
        int inputOnlyIndex = 1;
        assertEquals(TraceExecutionPhase.ADVANCE_ONLY,
                TraceReplayBootstrap.phaseForReplay(
                        trace, trace.getFrame(0), trace.getFrame(1)));
        assertTrue(TraceReplayBootstrap.isVblankStarvedIterationForReplay(
                trace.getFrame(0), trace.getFrame(1)));
        Bk2Movie movie = new Bk2Movie(
                Path.of("synthetic-marker-deferral.bk2"), "logkey", Map.of(),
                List.of(
                        new Bk2FrameInput(0, 0, 0, false, "input only"),
                        new Bk2FrameInput(1, 0, 0, false, "input-only chain"),
                        new Bk2FrameInput(2, 0, 0, false, "production")),
                1);
        RewindSeekAwareEngineStepper stepper = visualStepper(
                new GameLoop(new InputHandler()), movie, trace,
                mock(TraceReplayFixture.class), inputOnlyIndex);

        stepper.step(movie.getFrame(0));
        assertTrue(booleanField(stepper, "pendingVblankStarvedProductionMarker"),
                "the ownerless ADVANCE_ONLY closure must not consume the marker");

        stepper.step(movie.getFrame(1));
        assertTrue(booleanField(stepper, "pendingVblankStarvedProductionMarker"));
        setBooleanField(stepper, "pendingVblankStarvedProductionMarker", false);
        stepper.restoreToFrame(1, movie.getFrame(1));
        assertTrue(booleanField(stepper, "pendingVblankStarvedProductionMarker"),
                "restore must reconstruct pending state across the input-only chain");

        stepper.step(movie.getFrame(2));
        assertFalse(booleanField(stepper, "pendingVblankStarvedProductionMarker"),
                "the next production row must consume the deferred marker");
    }

    @Test
    void visualRewindDistinguishesPausedSetupAndUnpauseLifecycleRows()
            throws Exception {
        HeadlessTestFixture.builder().withZoneAndAct(0, 0).build();
        GameplayModeContext gameplay = SessionManager.getCurrentGameplayMode();
        List<String> events = new ArrayList<>();
        replaceCoordinator(gameplay,
                new PlcFrameLifecycleCoordinator(recording(events)));
        gameplay.setHardwareTimingBoundaryObserver(
                boundary -> events.add(boundary.name()));
        GameLoop loop = new GameLoop(new InputHandler());
        TraceReplayFixture timingFixture = mock(TraceReplayFixture.class);
        TraceData trace = fullFrameTrace();

        gameplay.getGameStateManager().setGamePaused(true);
        RewindSeekAwareEngineStepper paused = visualStepper(
                loop, singleFrameMovie(false), trace, timingFixture);
        paused.step(singleFrameMovie(false).getFrame(0));
        assertEquals(List.of(
                "service:NORMAL_PAUSE", "VINT_SERVICE"), events);

        events.clear();
        gameplay.getGameStateManager().setGamePaused(false);
        GameServices.level().restorePendingInitialProcessSpritesLifecycleForRewind(
                InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE);
        RewindSeekAwareEngineStepper setup = visualStepper(
                loop, singleFrameMovie(false), trace, timingFixture);
        setup.step(singleFrameMovie(false).getFrame(0));
        assertEquals(List.of(), events);

        events.clear();
        gameplay.getGameStateManager().setGamePaused(true);
        Bk2Movie unpauseMovie = singleFrameMovie(true);
        RewindSeekAwareEngineStepper unpause = visualStepper(
                loop, unpauseMovie, trace, timingFixture);
        unpause.step(unpauseMovie.getFrame(0));
        assertTrue(events.contains("service:ORDINARY_LEVEL"));
        assertTrue(events.contains("prepare:ORDINARY_LEVEL"));
        assertEquals(1, events.stream()
                .filter("service:ORDINARY_LEVEL"::equals).count());
    }

    @Test
    void visualRewindTreatsHeldStartAsAnEdgeOnlyAgainstAppliedPredecessor()
            throws Exception {
        HeadlessTestFixture.builder().withZoneAndAct(0, 0).build();
        GameplayModeContext gameplay = SessionManager.getCurrentGameplayMode();
        List<String> events = new ArrayList<>();
        replaceCoordinator(gameplay,
                new PlcFrameLifecycleCoordinator(recording(events)));
        gameplay.getGameStateManager().setGamePaused(true);
        GameLoop loop = new GameLoop(new InputHandler());
        Bk2Movie movie = heldStartMovie();
        RewindSeekAwareEngineStepper stepper = visualStepper(
                loop, movie, threeFullFrameTrace(), mock(TraceReplayFixture.class));

        stepper.step(movie.getFrame(1));

        assertTrue(gameplay.getGameStateManager().isGamePaused(),
                "a held Start row must not toggle ROM pause a second time");
        assertEquals(List.of("service:NORMAL_PAUSE"), events);
    }

    private static RewindSeekAwareEngineStepper visualStepper(
            GameLoop loop,
            Bk2Movie movie,
            TraceData trace,
            TraceReplayFixture fixture) throws Exception {
        return visualStepper(loop, movie, trace, fixture, 1);
    }

    private static RewindSeekAwareEngineStepper visualStepper(
            GameLoop loop,
            Bk2Movie movie,
            TraceData trace,
            TraceReplayFixture fixture,
            int traceBaseFrame) throws Exception {
        Class<?> type = Class.forName(
                "com.openggf.TraceSessionLauncher$VisualTraceRewindStepper");
        Constructor<?> constructor = type.getDeclaredConstructor(
                GameLoop.class, Bk2Movie.class, TraceData.class,
                com.openggf.trace.replay.TraceReplayFixture.class,
                int.class, int.class);
        constructor.setAccessible(true);
        return (RewindSeekAwareEngineStepper) constructor.newInstance(
                loop, movie, trace, fixture, 0, traceBaseFrame);
    }

    private static boolean booleanField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getBoolean(target);
    }

    private static void setBooleanField(
            Object target, String name, boolean value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.setBoolean(target, value);
    }

    private static Bk2Movie heldActionMovie() {
        int heldMask = AbstractPlayableSprite.INPUT_RIGHT | AbstractPlayableSprite.INPUT_JUMP;
        return new Bk2Movie(
                Path.of("synthetic-rewind-advance-only-action.bk2"),
                "logkey",
                Map.of(),
                List.of(
                        new Bk2FrameInput(0, heldMask, 1, false, "press"),
                        new Bk2FrameInput(1, heldMask, 1, false, "hold"),
                        new Bk2FrameInput(2, heldMask, 1, false, "hold")),
                1);
    }

    private static Bk2Movie singleFrameMovie(boolean startPressed) {
        return new Bk2Movie(
                Path.of("synthetic-visual-lifecycle.bk2"),
                "logkey", Map.of(),
                List.of(new Bk2FrameInput(
                        0, 0, 0, startPressed, 0, 0, false, "row")),
                1);
    }

    private static Bk2Movie heldStartMovie() {
        return new Bk2Movie(
                Path.of("synthetic-held-start.bk2"),
                "logkey", Map.of(),
                List.of(
                        new Bk2FrameInput(0, 0, 0, true, "press"),
                        new Bk2FrameInput(1, 0, 0, true, "hold")),
                1);
    }

    private static TraceData fullFrameTrace() {
        return TraceFixtures.trace(
                TraceFixtures.metadata("s3k", 0, 0),
                List.of(
                        TraceFrame.of(0, 0, (short) 0, (short) 0,
                                (short) 0, (short) 0, (short) 0,
                                (byte) 0, false, false, 0),
                        TraceFrame.executionTestFrame(1, 1, 1, 0)),
                Map.of());
    }

    private static TraceData threeFullFrameTrace() {
        return TraceFixtures.trace(
                TraceFixtures.metadata("s3k", 0, 0),
                List.of(
                        TraceFrame.executionTestFrame(0, 0, 0, 0),
                        TraceFrame.executionTestFrame(1, 1, 1, 0),
                        TraceFrame.executionTestFrame(2, 2, 2, 0)),
                Map.of());
    }

    private static PlcLifecycleService recording(List<String> events) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                events.add("service:" + phase);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.ORDINARY_LEVEL;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                events.add("prepare:" + phase);
            }
        };
    }

    private static void replaceCoordinator(
            GameplayModeContext gameplay,
            PlcFrameLifecycleCoordinator coordinator) throws Exception {
        Field field = GameplayModeContext.class.getDeclaredField(
                "plcFrameLifecycle");
        field.setAccessible(true);
        field.set(gameplay, coordinator);
    }

    private static TraceData advanceOnlyTrace() {
        TraceFrame beforeLatch = TraceFrame.of(0, 0,
                (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceFrame inputLatch = TraceFrame.of(1, AbstractPlayableSprite.INPUT_JUMP,
                (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceFrame gameplay = TraceFrame.of(2, AbstractPlayableSprite.INPUT_JUMP,
                (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceFrame firstGameplayDispatch = TraceFrame.executionTestFrame(3, 1, 1, 0);
        return TraceFixtures.trace(
                TraceFixtures.metadata("s3k", 0, 0),
                List.of(beforeLatch, inputLatch, gameplay, firstGameplayDispatch),
                Map.of(
                        0, List.of(new TraceEvent.ZoneActState(0, 0, 0, 0, 4)),
                        2, List.of(
                                new TraceEvent.ZoneActState(2, 0, 0, 0, 12),
                                new TraceEvent.Checkpoint(
                                        2, "gameplay_start", 0, 0, 0, 12, null))));
    }

    private static TraceData starvedAdvanceOnlyTrace() {
        TraceFrame before = executionFrame(0, 0, 10, 0x100);
        TraceFrame inputOnly = executionFrame(1, 1, 10, 0x100);
        TraceFrame inputOnlyChain = executionFrame(2, 2, 10, 0x100);
        TraceFrame production = executionFrame(3, 2, 11, 0x101);
        return TraceFixtures.trace(
                TraceFixtures.metadata("s3k", 0, 0),
                List.of(before, inputOnly, inputOnlyChain, production),
                Map.of(
                        0, List.of(new TraceEvent.ZoneActState(0, 0, 0, 0, 4)),
                        3, List.of(
                                new TraceEvent.ZoneActState(3, 0, 0, 0, 12),
                                new TraceEvent.Checkpoint(
                                        3, "gameplay_start", 0, 0, 0, 12, null))));
    }

    private static TraceFrame executionFrame(
            int frame, int input, int vblank, int gameplay) {
        return new TraceFrame(
                frame, input,
                (short) 0, (short) 0, (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0,
                0, 0, -1, -1, -1, -1, -1,
                gameplay, -1, vblank, 0);
    }
}
