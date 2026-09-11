package com.openggf.trace.replay;

import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.timing.HardwareCompletionEdge;
import com.openggf.trace.timing.HardwareTimingSchedule;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTraceHardwareTimingScheduleCompiler {

    @Test
    void installAcceptsHeldRowPostBeforeFixtureMutation() {
        TraceData trace = trace(
                6350,
                0x2a,
                6351,
                0x2a,
                edge(6351, HardwareServiceBoundary.POST_OBJECTS));
        TraceReplayFixture fixture = installingFixture();
        RuntimeArtCoordinator heldTail = heldTailCoordinator();
        when(fixture.gameplayMode().runtimeArtCoordinator())
                .thenReturn(heldTail);

        assertDoesNotThrow(() ->
                TraceReplaySessionBootstrap.installHardwareTimingReplay(
                        trace, fixture, true));

        verify(fixture).installHardwareTimingReplay(any());
    }

    @Test
    void installRejectsHeldRowPostWhenProductionDoesNotOwnThatTail() {
        TraceData trace = trace(
                6350,
                0x2a,
                6351,
                0x2a,
                edge(6351, HardwareServiceBoundary.POST_OBJECTS));
        TraceReplayFixture fixture = installingFixture();
        when(fixture.gameplayMode().runtimeArtCoordinator())
                .thenReturn(RuntimeArtCoordinator.NONE);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> TraceReplaySessionBootstrap.installHardwareTimingReplay(
                        trace, fixture, true));

        assertTrue(error.getMessage().contains("unsupported-row-POST"),
                error::getMessage);
    }

    @Test
    void installAcceptsOrdinaryCurrentRowPost() {
        TraceData trace = trace(
                100,
                0x2a,
                101,
                0x2b,
                edge(101, HardwareServiceBoundary.POST_OBJECTS));
        TraceReplayFixture fixture = installingFixture();

        assertDoesNotThrow(() ->
                TraceReplaySessionBootstrap.installHardwareTimingReplay(
                        trace, fixture, true));

        verify(fixture).installHardwareTimingReplay(any());
    }

    @Test
    void installAcceptsHeldRowPreMainLoop() {
        TraceData trace = trace(
                200,
                0x2a,
                201,
                0x2a,
                edge(201, HardwareServiceBoundary.PRE_MAIN_LOOP));
        TraceReplayFixture fixture = installingFixture();

        assertDoesNotThrow(() ->
                TraceReplaySessionBootstrap.installHardwareTimingReplay(
                        trace, fixture, true));

        verify(fixture).installHardwareTimingReplay(any());
    }

    @Test
    void postObjectsRejectsPlayableAnimationOnlyPhase() {
        assertUnsupportedPostPhase(TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY);
    }

    @Test
    void postObjectsRejectsAdvanceOnlyPhase() {
        assertUnsupportedPostPhase(TraceExecutionPhase.ADVANCE_ONLY);
    }

    @Test
    void postObjectsAcceptsVblankOnlyPhaseOnlyForProductionOwnedHeldTail() {
        assertDoesNotThrow(() ->
                TraceHardwareTimingScheduleCompiler.requireExecutablePostPhase(
                        edge(299, HardwareServiceBoundary.POST_OBJECTS),
                        TraceExecutionPhase.VBLANK_ONLY,
                        heldTailCoordinator()));
        assertThrows(IllegalStateException.class, () ->
                TraceHardwareTimingScheduleCompiler.requireExecutablePostPhase(
                        edge(299, HardwareServiceBoundary.POST_OBJECTS),
                        TraceExecutionPhase.VBLANK_ONLY,
                        RuntimeArtCoordinator.NONE));
    }

    @Test
    void postObjectsAcceptsBothFullLevelPhases() {
        assertDoesNotThrow(() ->
                TraceHardwareTimingScheduleCompiler.requireExecutablePostPhase(
                        edge(300, HardwareServiceBoundary.POST_OBJECTS),
                        TraceExecutionPhase.FULL_LEVEL_FRAME,
                        RuntimeArtCoordinator.NONE));
        assertDoesNotThrow(() ->
                TraceHardwareTimingScheduleCompiler.requireExecutablePostPhase(
                        edge(301, HardwareServiceBoundary.POST_OBJECTS),
                        TraceExecutionPhase.FULL_LEVEL_FRAME_WITH_SIDEKICK_ANIMATION_HELD,
                        RuntimeArtCoordinator.NONE));
    }

    private static void assertUnsupportedPostPhase(TraceExecutionPhase phase) {
        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> TraceHardwareTimingScheduleCompiler.requireExecutablePostPhase(
                        edge(299, HardwareServiceBoundary.POST_OBJECTS), phase,
                        RuntimeArtCoordinator.NONE));
        assertTrue(error.getMessage().contains("unsupported-row-POST"),
                error::getMessage);
        assertTrue(error.getMessage().contains("phase=" + phase),
                error::getMessage);
    }

    private static TraceData trace(
            int previousRawFrame,
            int previousGameplayCounter,
            int currentRawFrame,
            int currentGameplayCounter,
            HardwareCompletionEdge edge) {
        return TraceFixtures.trace(
                TraceFixtures.metadataWithHardwareTiming("s3k", 0, 1, 2),
                List.of(
                        TraceFrame.executionTestFrame(
                                previousRawFrame, 10, previousGameplayCounter, 0),
                        TraceFrame.executionTestFrame(
                                currentRawFrame, 11, currentGameplayCounter, 0)),
                new HardwareTimingSchedule(List.of(edge)));
    }

    private static HardwareCompletionEdge edge(
            int rawFrame,
            HardwareServiceBoundary boundary) {
        return new HardwareCompletionEdge(
                rawFrame,
                boundary,
                HardwareWorkKind.KOS_MODULE_QUEUE,
                0,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static TraceReplayFixture installingFixture() {
        TraceReplayFixture fixture = mock(TraceReplayFixture.class);
        GameplayModeContext gameplayMode = mock(GameplayModeContext.class);
        HardwareTimingService timing = new HardwareTimingService();
        when(fixture.gameplayMode()).thenReturn(gameplayMode);
        when(gameplayMode.recordedCompletionAuthority())
                .thenReturn(timing.beginRecordedAdmission());
        when(gameplayMode.runtimeArtCoordinator())
                .thenReturn(RuntimeArtCoordinator.NONE);
        return fixture;
    }

    private static RuntimeArtCoordinator heldTailCoordinator() {
        RuntimeArtCoordinator coordinator = mock(RuntimeArtCoordinator.class);
        when(coordinator.ownsHeldLevelCounterHardwareTail()).thenReturn(true);
        return coordinator;
    }
}
