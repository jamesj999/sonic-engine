package com.openggf.trace.live;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GroundMode;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceExecutionPhase;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import com.openggf.trace.TraceReplayBootstrap;
import com.openggf.tests.FullReset;
import com.openggf.tests.RuntimeStateContaminationExtension;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.nio.file.Path;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@FullReset
@ExtendWith({
        RuntimeStateContaminationExtension.class,
        SingletonResetExtension.class
})
class TestLiveTraceComparatorObserver {

    private static AbstractPlayableSprite stubSprite() {
        AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
        when(sprite.getCentreX()).thenReturn((short) 10);
        when(sprite.getCentreY()).thenReturn((short) 0);
        when(sprite.getXSpeed()).thenReturn((short) 0);
        when(sprite.getYSpeed()).thenReturn((short) 0);
        when(sprite.getGSpeed()).thenReturn((short) 0);
        when(sprite.getAngle()).thenReturn((byte) 0);
        when(sprite.getAir()).thenReturn(false);
        when(sprite.getRolling()).thenReturn(false);
        when(sprite.getGroundMode()).thenReturn(GroundMode.GROUND);
        return sprite;
    }

    private static TraceData twoFrameTrace() {
        // Two s2 FULL_LEVEL_FRAME frames (previous=null for frame 0 → always FULL_LEVEL_FRAME;
        // frame 1 has previous with same gameplayFrameCounter=0x100 → legacy path → since
        // vblankCounter advances it would be VBLANK_ONLY in legacy. Use of(…) with distinct
        // gameplayFrameCounters avoids that: frame 1 has counter 0x101 → counter advanced,
        // so NOT a same-counter pin, and speeds=0/air=false → FULL_LEVEL_FRAME).
        return TraceFixtures.trace(
                TraceFixtures.metadata("s2", 0, 0),
                List.of(
                        TraceFrame.of(0, 0,
                                (short) 10, (short) 0,
                                (short) 0, (short) 0, (short) 0,
                                (byte) 0, false, false, 0),
                        TraceFrame.of(1, 0,
                                (short) 10, (short) 0,
                                (short) 0, (short) 0, (short) 0,
                                (byte) 0, false, false, 0)));
    }

    private static TraceData emptyDynamicArtTrace() {
        return emptyDynamicArtTrace(0);
    }

    private static TraceData emptyDynamicArtTrace(int advertisedRow) {
        TraceFrame frame0 = TraceFrame.of(0, 0,
                (short) 10, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceFrame frame1 = TraceFrame.of(1, 0,
                (short) 10, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceFrame frame2 = TraceFrame.of(2, 0,
                (short) 10, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        return TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s1", 0, 0, 2),
                List.of(frame0, frame1, frame2),
                Map.of(advertisedRow, List.of(
                        new TraceEvent.DynamicArtTransferState(
                                advertisedRow, List.of(), List.of()))));
    }

    @Test
    void perFrameObserverReceivesEveryComparison() {
        AbstractPlayableSprite sprite = stubSprite();
        TraceData trace = twoFrameTrace();

        List<FrameComparison> observed = new ArrayList<>();
        Consumer<FrameComparison> observer = observed::add;

        LiveTraceComparator c = new LiveTraceComparator(
                trace,
                ToleranceConfig.DEFAULT,
                0,
                () -> sprite,
                null,
                observer);

        Bk2FrameInput empty = new Bk2FrameInput(0, 0, 0, false, "0");
        c.afterFrameAdvanced(empty, false);
        c.afterFrameAdvanced(empty, false);

        assertEquals(2, observed.size(),
                "Observer should fire exactly once per gameplay-compared frame");
        assertNotNull(observed.get(0));
        assertNotNull(observed.get(1));
    }

    @Test
    void nullObserverIsHonoured() {
        // Uses the existing 4-arg constructor — must not NPE.
        AbstractPlayableSprite sprite = stubSprite();
        TraceData trace = twoFrameTrace();

        LiveTraceComparator c = new LiveTraceComparator(
                trace,
                ToleranceConfig.DEFAULT,
                0,
                () -> sprite);

        Bk2FrameInput empty = new Bk2FrameInput(0, 0, 0, false, "0");
        c.afterFrameAdvanced(empty, false);
        c.afterFrameAdvanced(empty, false);

        // No assertion needed beyond surviving without NPE.
        assertEquals(0, c.errorCount());
    }

    @Test
    void existingFiveArgConstructorDelegatesWithNullObserver() {
        // Uses the existing 5-arg constructor — must not NPE.
        AbstractPlayableSprite sprite = stubSprite();
        TraceData trace = twoFrameTrace();

        LiveTraceComparator c = new LiveTraceComparator(
                trace,
                ToleranceConfig.DEFAULT,
                0,
                () -> sprite,
                null);

        Bk2FrameInput empty = new Bk2FrameInput(0, 0, 0, false, "0");
        c.afterFrameAdvanced(empty, false);
        c.afterFrameAdvanced(empty, false);

        assertEquals(0, c.errorCount());
    }

    @Test
    void advertisedDynamicArtIsComparedFromTheSuppliedImmutableSnapshot() {
        AbstractPlayableSprite sprite = stubSprite();
        TraceFrame frame = TraceFrame.of(0, 0,
                (short) 10, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 2),
                List.of(frame, TraceFrame.of(1, 0,
                        (short) 10, (short) 0,
                        (short) 0, (short) 0, (short) 0,
                        (byte) 0, false, false, 0)),
                Map.of(0, List.of(new com.openggf.trace.TraceEvent.DynamicArtTransferState(
                        0, List.of(), List.of(9L)))));
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> sprite,
                null, observed::add,
                () -> new DynamicArtDiagnosticsSnapshot(
                        0, List.of(), List.of()));

        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "0"), false);

        assertEquals(0, observed.size(),
                "dynamic art must wait for the outer lifecycle publication");
        comparator.publishPendingDynamicArtComparison(
                DynamicArtDiagnosticsSnapshot.unpublished(0, 0),
                new DynamicArtDiagnosticsSnapshot(
                        0, List.of(), List.of(), 1, 0, true));

        assertEquals(1, observed.size());
        assertTrue(observed.getFirst().hasErrorInField(
                "dynamic_art.outstanding_transfer_ids"));
    }

    @Test
    void advertisedDynamicArtStillComparesOnSkippedLagRows() {
        TraceFrame frame = TraceFrame.executionTestFrame(0, 10, 0x100, 0);
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 2),
                List.of(frame,
                        TraceFrame.executionTestFrame(1, 10, 0x101, 1)),
                Map.of(0, List.of(new com.openggf.trace.TraceEvent.DynamicArtTransferState(
                        0, List.of(), List.of()))));
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null,
                null, observed::add,
                () -> new DynamicArtDiagnosticsSnapshot(
                        0, List.of(), List.of()));

        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "0"), true);

        assertEquals(0, observed.size());
        comparator.publishPendingDynamicArtComparison(
                DynamicArtDiagnosticsSnapshot.unpublished(0, 0),
                new DynamicArtDiagnosticsSnapshot(
                        0, List.of(), List.of(), 1, 0, true));

        assertEquals(1, observed.size());
        assertFalse(observed.getFirst().hasDivergence());
        assertEquals(1, comparator.laggedFrames());
    }

    @Test
    void rowZeroRejectsGenerationChange() {
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator comparator = new LiveTraceComparator(
                emptyDynamicArtTrace(), ToleranceConfig.DEFAULT, 0,
                TestLiveTraceComparatorObserver::stubSprite,
                null, observed::add);
        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "0"), false);

        assertThrows(IllegalStateException.class,
                () -> comparator.publishPendingDynamicArtComparison(
                        DynamicArtDiagnosticsSnapshot.unpublished(4, 7),
                        new DynamicArtDiagnosticsSnapshot(
                                0, List.of(), List.of(), 5, 8, true)));
    }

    @Test
    void rowZeroRejectsNonAdjacentSegmentGeneration() {
        LiveTraceComparator comparator = new LiveTraceComparator(
                emptyDynamicArtTrace(), ToleranceConfig.DEFAULT, 0,
                TestLiveTraceComparatorObserver::stubSprite,
                null, ignored -> { });
        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "0"), false);

        assertThrows(IllegalStateException.class,
                () -> comparator.publishPendingDynamicArtComparison(
                        DynamicArtDiagnosticsSnapshot.unpublished(4, 7),
                        new DynamicArtDiagnosticsSnapshot(
                                0, List.of(), List.of(), 5, 9, true)));
    }

    @Test
    void nonzeroRowRejectsAdjacentSegmentGeneration() {
        LiveTraceComparator comparator = new LiveTraceComparator(
                emptyDynamicArtTrace(1), ToleranceConfig.DEFAULT, 1,
                TestLiveTraceComparatorObserver::stubSprite,
                null, ignored -> { });
        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "0"), false);

        assertThrows(IllegalStateException.class,
                () -> comparator.publishPendingDynamicArtComparison(
                        DynamicArtDiagnosticsSnapshot.unpublished(4, 7),
                        new DynamicArtDiagnosticsSnapshot(
                                1, List.of(), List.of(), 5, 8, true)));
    }

    @Test
    void rowZeroRejectsDeferredGenerationWithoutNewDelivery() {
        LiveTraceComparator comparator = new LiveTraceComparator(
                emptyDynamicArtTrace(), ToleranceConfig.DEFAULT, 0,
                TestLiveTraceComparatorObserver::stubSprite,
                null, ignored -> { });
        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "0"), false);

        assertThrows(IllegalStateException.class,
                () -> comparator.publishPendingDynamicArtComparison(
                        DynamicArtDiagnosticsSnapshot.unpublished(4, 7),
                        new DynamicArtDiagnosticsSnapshot(
                                0, List.of(), List.of(), 4, 8, true)));
    }

    @Test
    void rowZeroRejectsDeferredGenerationFromPublishedSnapshot() {
        LiveTraceComparator comparator = new LiveTraceComparator(
                emptyDynamicArtTrace(), ToleranceConfig.DEFAULT, 0,
                TestLiveTraceComparatorObserver::stubSprite,
                null, ignored -> { });
        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "0"), false);

        assertThrows(IllegalStateException.class,
                () -> comparator.publishPendingDynamicArtComparison(
                        new DynamicArtDiagnosticsSnapshot(
                                0, List.of(), List.of(), 4, 7, true),
                        new DynamicArtDiagnosticsSnapshot(
                                0, List.of(), List.of(), 5, 8, true)));
    }

    @Test
    void laterRowRejectsGenerationChangeAfterStableRowZero() {
        TraceData source = emptyDynamicArtTrace();
        TraceData trace = TraceFixtures.trace(
                source.metadata(),
                List.of(source.getFrame(0), source.getFrame(1),
                        source.getFrame(2)),
                Map.of(
                        0, List.of(new TraceEvent.DynamicArtTransferState(
                                0, List.of(), List.of())),
                        1, List.of(new TraceEvent.DynamicArtTransferState(
                                1, List.of(), List.of()))));
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0,
                TestLiveTraceComparatorObserver::stubSprite,
                null, ignored -> { });
        Bk2FrameInput input = new Bk2FrameInput(0, 0, 0, false, "0");
        comparator.afterFrameAdvanced(input, false);
        comparator.publishPendingDynamicArtComparison(
                DynamicArtDiagnosticsSnapshot.unpublished(4, 7),
                new DynamicArtDiagnosticsSnapshot(
                        0, List.of(), List.of(), 5, 7, true));

        comparator.afterFrameAdvanced(input, false);
        assertThrows(IllegalStateException.class,
                () -> comparator.publishPendingDynamicArtComparison(
                        new DynamicArtDiagnosticsSnapshot(
                                0, List.of(), List.of(), 5, 7, true),
                        new DynamicArtDiagnosticsSnapshot(
                                1, List.of(), List.of(), 6, 8, true)));
    }

    @Test
    void playableAnimationOnlyComparisonWaitsForPostProductionPrefix()
            throws Exception {
        TraceData source = TraceData.load(Path.of(
                "src/test/resources/traces/s3k/lbz_completerun"));
        TraceFrame previous = TraceFrame.executionTestFrame(0, 10, 0x100, 0);
        TraceFrame current = TraceFrame.executionTestFrame(1, 11, 0x100, 0);
        TraceData trace = TraceFixtures.trace(
                source.metadata(),
                List.of(previous, current),
                Map.of(
                        0, List.of(cpuState(0, 0)),
                        1, List.of(cpuState(1, 4))));
        assertEquals(TraceExecutionPhase.PLAYABLE_ANIMATION_ONLY,
                TraceReplayBootstrap.phaseForReplay(trace, previous, current));
        AbstractPlayableSprite sprite = stubSprite();
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 1, () -> sprite);

        comparator.afterFrameAdvanced(
                new Bk2FrameInput(1, 0, 0, false, "playable prefix"), true);

        verify(sprite, never()).getCentreX();
        assertTrue(comparator.consumePostProductionPlayableAnimationAction(),
                "gameplay diagnostics must not sample before the playable prefix");
        verify(sprite, atLeastOnce()).getCentreX();
    }

    private static TraceEvent.CpuState cpuState(int frame, int posTableIndex) {
        return new TraceEvent.CpuState(
                frame, "tails", 0, 0, 0, 6,
                (short) 0, (short) 0, 0, 0,
                0, 0, 0, 0, posTableIndex, 0,
                (short) 0, (short) 0, 0, 0, 0, 0, 0);
    }

    @Test
    void finalDynamicArtWaitsForProductionCloseAndComparesExactlyOnce() {
        DynamicArtLifecycleService lifecycle =
                new DynamicArtLifecycleService();
        lifecycle.beginRun();
        lifecycle.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        (PlcLifecycleService) null, lifecycle);
        TraceEvent.DynamicArtTransferState expected =
                new TraceEvent.DynamicArtTransferState(
                        0,
                        List.of(new DynamicArtTransfer.SegmentEdge(
                                0, 0, "submitted", "sonic", "segment",
                                4, 0, 0, 0, true, 0x1B848,
                                List.of(new DynamicArtTransfer.Request(
                                        0x22630, 1, -1, 0xF000, 0x20)))),
                        List.of(0L));
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 1),
                List.of(TraceFrame.executionTestFrame(0, 10, 0x100, 0)),
                Map.of(0, List.of(expected)));
        List<FrameComparison> observed = new ArrayList<>();
        AbstractPlayableSprite sprite = stubSprite();
        when(sprite.getCentreX()).thenReturn((short) 11);
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> sprite,
                null, observed::add, lifecycle::latestSnapshot);

        coordinator.runLogicalIteration(() -> {
        }, row -> {
            row.claim(PlcLifecyclePhase.LAG);
            lifecycle.observeRomDplc(
                    "sonic", 4, List.of(new TileLoadRequest(1, 1)),
                    0x22610, 0xF000);
            row.prepareAfterLoop(PlcLifecyclePhase.LAG);
            comparator.afterFrameAdvanced(
                    new Bk2FrameInput(0, 0, 1, false, "0"), true);
            return null;
        });

        assertTrue(comparator.hasDeferredTerminalDynamicArt());
        assertEquals(1, observed.size(),
                "terminal base fields publish before the deferred DPLC");
        assertTrue(observed.getFirst().fields().containsKey("input_alignment"));

        lifecycle.closeComparisonSegment();
        comparator.finalizeTerminalDynamicArtComparison();
        comparator.finalizeTerminalDynamicArtComparison();

        assertFalse(comparator.hasDeferredTerminalDynamicArt());
        assertEquals(2, observed.size(),
                "terminal base and DPLC deltas must each publish exactly once");
        assertFalse(observed.getLast().fields().containsKey("input_alignment"),
                "the terminal DPLC observer event must not repeat base fields");
        assertTrue(observed.getLast().fields().containsKey(
                "dynamic_art.edge[0].terminal_forwarded"));
    }

    @Test
    void externalDynamicArtResultUsesNormalMismatchFrontierAndObserver() {
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 1),
                List.of(),
                Map.of());
        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 0, () -> null,
                null, observed::add);
        FrameComparison result = new FrameComparison(
                0, Map.of("dynamic_art.frame",
                        new com.openggf.trace.FieldComparison(
                                "dynamic_art.frame", "1", "0",
                                com.openggf.trace.Severity.ERROR, 1)));

        comparator.ingestExternalComparison(result);

        assertEquals(1, comparator.errorCount());
        assertTrue(comparator.hasRecordingDesync());
        assertEquals(1, observed.size());
        assertEquals("dynamic_art.frame",
                comparator.recentMismatches().getFirst().field());
    }
}
