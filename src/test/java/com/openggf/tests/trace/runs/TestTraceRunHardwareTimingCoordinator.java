package com.openggf.tests.trace.runs;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.PlaybackDebugManager;
import com.openggf.game.BonusStageType;
import com.openggf.game.GameMode;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.WorldSession;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.camera.Camera;
import com.openggf.game.GameRng;
import com.openggf.game.GameStateManager;
import com.openggf.game.timing.HardwareReadinessAdmissionPolicy;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareTimingSnapshot;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.timing.HardwareWorkPreparation;
import com.openggf.game.timing.HardwareWorkPreparationSnapshot;
import com.openggf.game.timing.HardwareWorkSubmission;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.replay.TraceReplayFixture;
import com.openggf.trace.replay.TraceReplaySessionBootstrap;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import com.openggf.trace.replay.runs.TraceRunSegmentDescriptor;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.timing.HardwareCompletionEdge;
import com.openggf.trace.timing.HardwareTimingReplayPort;
import com.openggf.trace.timing.HardwareTimingSchedule;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;
import org.junit.jupiter.api.Test;
import com.openggf.graphics.FadeManager;
import com.openggf.game.solid.DefaultSolidExecutionRegistry;
import com.openggf.timer.TimerManager;

import java.util.List;
import java.util.Map;
import java.util.BitSet;
import java.nio.file.Path;

import static com.openggf.game.timing.HardwareServiceBoundary.POST_OBJECTS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunHardwareTimingCoordinator {

    @Test
    void emptyInitialScheduleCannotSeedOrdinalFourDuringLaterHandoff() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareTimingReplayPort port =
                new HardwareTimingReplayPort(service.beginRecordedAdmission());
        port.install(HardwareTimingSchedule.empty());
        TimingFixture fixture = new TimingFixture(port);
        fixture.installHardwareTimingReplay(port);
        HardwareWorkSubmission submission = submission(false, 0, 0x2c);
        HardwareCompletionEdge laterEdge = new HardwareCompletionEdge(
                200,
                POST_OBJECTS,
                submission.kind(),
                4,
                com.openggf.game.timing.HardwareSubmissionFingerprint
                        .compute(submission));
        var coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                10, List.of(100), HardwareTimingSchedule.empty()),
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                11, List.of(200),
                                new HardwareTimingSchedule(List.of(laterEdge)))));

        coordinator.beginPlaybackFrame(frame(10));
        // Membership is the drive's to declare; the cursor reaching segment 1's
        // offset is not an entry on its own.
        coordinator.enterSegment(1);
        coordinator.beginPlaybackFrame(frame(11));
        HardwareWorkHandle production = service.submit(submission);
        service.service(POST_OBJECTS);

        assertEquals(0, production.ordinal(),
                "handoff must not reconstruct omitted run-start submissions");
        fixture.observer.onBoundary(POST_OBJECTS);

        // The unmatched edge is dropped and reported, never admitted: the
        // handoff still cannot reconstruct the omitted run-start ordinal.
        java.util.List<String> reported =
                port.drainUnmatchedRecordedCompletions();
        assertEquals(1, reported.size(), reported::toString);
        String mismatch = reported.get(0);
        assertFalse(service.isReady(production), "a dropped edge releases nothing");
        assertTrue(mismatch.contains(
                "expected completion: KOS_MODULE_QUEUE#4"), () -> mismatch);
        assertTrue(mismatch.contains(
                "engine pending: KOS_MODULE_QUEUE#0"), () -> mismatch);
    }

    @Test
    void explicitOrdinalFourBaseIsEstablishedBeforeRunAndSurvivesHandoff() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareTimingReplayPort port =
                new HardwareTimingReplayPort(service.beginRecordedAdmission());
        port.install(
                HardwareTimingSchedule.empty(),
                Map.of(HardwareWorkKind.KOS_MODULE_QUEUE, 4L));
        TimingFixture fixture = new TimingFixture(port);
        fixture.installHardwareTimingReplay(port);
        HardwareWorkSubmission submission = submission(false, 0, 0x2d);
        HardwareCompletionEdge laterEdge = new HardwareCompletionEdge(
                200,
                POST_OBJECTS,
                submission.kind(),
                4,
                com.openggf.game.timing.HardwareSubmissionFingerprint
                        .compute(submission));
        var coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                10, List.of(100), HardwareTimingSchedule.empty()),
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                11, List.of(200),
                                new HardwareTimingSchedule(List.of(laterEdge)))));

        coordinator.beginPlaybackFrame(frame(10));
        HardwareTimingSnapshot serviceBeforeHandoff = service.capture();
        var portBeforeHandoff = port.capture();
        coordinator.enterSegment(1);
        coordinator.beginPlaybackFrame(frame(11));
        HardwareWorkHandle production = service.submit(submission);
        service.service(POST_OBJECTS);
        fixture.observer.onBoundary(POST_OBJECTS);

        assertEquals(4, production.ordinal());
        assertTrue(service.isReady(production));

        service.restore(serviceBeforeHandoff);
        port.restore(portBeforeHandoff);
        coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                10, List.of(100), HardwareTimingSchedule.empty()),
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                11, List.of(200),
                                new HardwareTimingSchedule(List.of(laterEdge)))));
        coordinator.beginPlaybackFrame(frame(10));
        // Membership is the drive's to declare; the cursor reaching segment 1's
        // offset is not an entry on its own.
        coordinator.enterSegment(1);
        coordinator.beginPlaybackFrame(frame(11));
        HardwareWorkHandle replayed = service.submit(submission);
        service.service(POST_OBJECTS);
        fixture.observer.onBoundary(POST_OBJECTS);
        assertEquals(production, replayed);
        assertTrue(service.isReady(replayed));
    }

    @Test
    void twoSegmentsLatchBeforeServicePreserveOrdinalAndCloseAdmission() {
        HardwareTimingService service = new HardwareTimingService();
        var authority = service.beginRecordedAdmission();
        HardwareWorkHandle exported = service.submit(submission(true, 1, 0x31));
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(HardwareTimingSchedule.empty());
        TimingFixture fixture = new TimingFixture(port);
        fixture.installHardwareTimingReplay(port);

        var coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                10, List.of(100), HardwareTimingSchedule.empty()),
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                11, List.of(200), new HardwareTimingSchedule(List.of(
                                        edge(200, exported))))));
        var probe = new TraceRunReplayWalker.BoundaryProbe(new InertHooks());
        probe.setBeforeFrameObserver(coordinator::beginPlaybackFrame);
        probe.setDelegate(new PlaybackDebugManager.PlaybackFrameObserver() {
            @Override
            public boolean shouldSkipGameplayTick(Bk2FrameInput frame) {
                service.service(POST_OBJECTS);
                fixture.observer.onBoundary(POST_OBJECTS);
                return false;
            }

            @Override
            public void afterFrameAdvanced(Bk2FrameInput frame, boolean wasSkipped) {
            }
        });

        probe.shouldSkipGameplayTick(frame(10));
        assertFalse(service.isReady(exported));

        coordinator.enterSegment(1);
        probe.shouldSkipGameplayTick(frame(11));
        assertTrue(service.isReady(exported));
        assertArrayEquals(new byte[] {0x31}, service.claim(exported));

        coordinator.close();
        assertEquals(HardwareReadinessAdmissionPolicy.LIVE, service.admissionPolicy());
        HardwareWorkHandle afterHandoff = service.submit(submission(false, 0, 0x32));
        assertEquals(exported.ordinal() + 1, afterHandoff.ordinal());
        coordinator.close();
    }

    @Test
    void noncontiguousBk2GapClearsLatchBeforeProductionContinues() {
        HardwareTimingService service = new HardwareTimingService();
        var authority = service.beginRecordedAdmission();
        HardwareWorkHandle handle = service.submit(submission(false, 0, 0x41));
        HardwareTimingReplayPort port = new HardwareTimingReplayPort(authority);
        port.install(new HardwareTimingSchedule(List.of(edge(100, handle))));
        TimingFixture fixture = new TimingFixture(port);
        fixture.installHardwareTimingReplay(port);
        var coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                10, List.of(100), new HardwareTimingSchedule(
                                        List.of(edge(100, handle)))),
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                20, List.of(0), HardwareTimingSchedule.empty())));

        coordinator.beginPlaybackFrame(frame(10));
        // Frame 11 falls in neither segment (segment 1 opens at 20); the drive
        // never enters segment 1 here.
        coordinator.beginPlaybackFrame(frame(11));
        service.service(POST_OBJECTS);
        fixture.observer.onBoundary(POST_OBJECTS);

        assertFalse(service.isReady(handle));
        assertEquals(null, port.capture().rawFrameLatch());
    }

    @Test
    void cursorRunningPastTheNextOffsetInAGapLatchesNoDestinationRow() {
        // The defect this pins: across a transition the drive releases its row
        // owner while the shared BK2 cursor keeps free-running through
        // choreography frames the recording never covered. Those frames run
        // past the destination's offset, and a coordinator that infers
        // membership from cursor arithmetic latches them as the destination's
        // rows 0.. -- rows the destination has not started. The drive then
        // re-seeks the cursor to the destination's true first row and the walk
        // legitimately restarts at row 0, which the port refuses as a backward
        // raw_frame. Membership is the drive's to declare.
        HardwareTimingService service = new HardwareTimingService();
        HardwareTimingReplayPort port =
                new HardwareTimingReplayPort(service.beginRecordedAdmission());
        port.install(HardwareTimingSchedule.empty());
        TimingFixture fixture = new TimingFixture(port);
        fixture.installHardwareTimingReplay(port);
        var coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                10, List.of(100, 101), HardwareTimingSchedule.empty()),
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                12, List.of(200, 201, 202),
                                HardwareTimingSchedule.empty())));

        coordinator.beginPlaybackFrame(frame(10));
        coordinator.beginPlaybackFrame(frame(11));
        coordinator.enterTransitionGap();
        // Choreography: the cursor runs across the whole destination range.
        coordinator.beginPlaybackFrame(frame(12));
        coordinator.beginPlaybackFrame(frame(13));
        coordinator.beginPlaybackFrame(frame(14));
        assertEquals(List.of(100, 101), fixture.latchedRawFrames,
                "no destination row may be latched before the drive enters it");

        // The drive re-seeks to the destination's true first row and enters.
        coordinator.enterSegment(1);
        coordinator.beginPlaybackFrame(frame(12));
        coordinator.beginPlaybackFrame(frame(13));
        assertEquals(List.of(100, 101, 200, 201), fixture.latchedRawFrames,
                "the destination starts at its own first row, moving forward");
    }

    @Test
    void repeatedSetupVblankAndAdvanceRowsRelatchEveryDriveAttempt() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareTimingReplayPort port =
                new HardwareTimingReplayPort(service.beginRecordedAdmission());
        port.install(HardwareTimingSchedule.empty());
        TimingFixture fixture = new TimingFixture(port);
        fixture.installHardwareTimingReplay(port);
        var coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(new TraceRunReplayWalker.HardwareTimingSegment(
                        40, List.of(300, 301, 302), HardwareTimingSchedule.empty())));

        coordinator.beginPlaybackFrame(frame(40)); // setup-only attempt
        coordinator.beginPlaybackFrame(frame(40)); // retry of the same row
        coordinator.beginPlaybackFrame(frame(41)); // VBlank-only row
        coordinator.beginPlaybackFrame(frame(42)); // advance-only row

        assertEquals(List.of(300, 300, 301, 302), fixture.latchedRawFrames);
    }

    @Test
    void destinationScheduleCanHandoffBeforeItsFirstRowIsLatched() {
        HardwareTimingService service = new HardwareTimingService();
        HardwareTimingReplayPort port =
                new HardwareTimingReplayPort(service.beginRecordedAdmission());
        port.install(HardwareTimingSchedule.empty());
        TimingFixture fixture = new TimingFixture(port);
        fixture.installHardwareTimingReplay(port);
        var coordinator = new TraceRunReplayWalker.HardwareTimingCoordinator(
                fixture,
                List.of(
                        new TraceRunReplayWalker.HardwareTimingSegment(
                                10, List.of(100), HardwareTimingSchedule.empty()),
                new TraceRunReplayWalker.HardwareTimingSegment(
                        20, List.of(200), HardwareTimingSchedule.recordedEmpty())));

        coordinator.beginSegmentRow(0, 0);
        coordinator.handoffToSegment(1);
        assertEquals(List.of(100), fixture.latchedRawFrames,
                "schedule handoff must not admit a destination production row");

        coordinator.beginSegmentRow(1, 0);
        assertEquals(List.of(100, 200), fixture.latchedRawFrames);
    }

    @Test
    void metadataOnlySpecialStageUsesAuditedSegmentLocalRawFrames() {
        var segment = new TraceRunManifest.Segment(
                "ss", "special_stage", "special_stage", 900, 3,
                null, null, 0, null);
        var trace = TraceFixtures.trace(
                TraceFixtures.metadataWithHardwareTiming("s3k", 0, 0, 3),
                List.of(),
                HardwareTimingSchedule.recordedEmpty());
        TraceRunSegmentDescriptor descriptor = new TraceRunSegmentDescriptor(
                segment, Path.of("ss"), trace.metadata(), 3, null,
                List.of(0, 1, 2), new BitSet(),
                trace.hardwareTimingSchedule(),
                trace.terminalDynamicArtLedger(), null, null, 0,
                TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL);
        List<TraceRunReplayWalker.HardwareTimingSegment> descriptorTiming =
                TraceRunReplayWalker.descriptorHardwareTimingSegments(
                        List.of(descriptor));

        assertTrue(TraceRunReplayWalker.hasDescriptorHardwareTimingStream(
                List.of(descriptor)));
        assertEquals(List.of(0, 1, 2), descriptorTiming.getFirst().rawFrames());
        assertEquals(900, descriptorTiming.getFirst().bk2FrameOffset());
    }

    @Test
    void laterFirstTimingSegmentStillSelectsRecordedRunPolicy() {
        var firstSegment = new TraceRunManifest.Segment(
                "first-ss", "special_stage", "special_stage", 10, 1,
                null, null, 0, null);
        var laterSegment = new TraceRunManifest.Segment(
                "ss", "special_stage", "special_stage", 20, 1,
                null, null, 0, null);
        var first = TraceFixtures.trace(
                TraceFixtures.metadata("s3k", 0, 0), List.of());
        var later = TraceFixtures.trace(
                TraceFixtures.metadataWithHardwareTiming("s3k", 0, 0, 1),
                List.of(),
                HardwareTimingSchedule.recordedEmpty());

        TraceRunSegmentDescriptor firstDescriptor = new TraceRunSegmentDescriptor(
                firstSegment, Path.of("first-ss"), first.metadata(), 1,
                null, List.of(0), new BitSet(),
                first.hardwareTimingSchedule(), List.of(), null, null, 0,
                TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL);
        TraceRunSegmentDescriptor laterDescriptor = new TraceRunSegmentDescriptor(
                laterSegment, Path.of("ss"), later.metadata(), 1,
                null, List.of(0), new BitSet(), later.hardwareTimingSchedule(),
                List.of(), null, null, 0,
                TraceRunReplayWalker.SegmentExecutionPolicy.SPECIAL_LOCAL);
        assertTrue(TraceRunReplayWalker.hasDescriptorHardwareTimingStream(
                List.of(firstDescriptor, laterDescriptor)));

        GameplayModeContext context = new GameplayModeContext(
                new WorldSession(new Sonic2GameModule()),
                HardwareReadinessAdmissionPolicy.RECORDED);
        context.attachGameplayManagers(
                new Camera(),
                new TimerManager(),
                new GameStateManager(),
                new FadeManager(),
                new GameRng(GameRng.Flavour.S1_S2),
                new DefaultSolidExecutionRegistry());
        InstallingFixture fixture = new InstallingFixture(context);

        TraceReplaySessionBootstrap.installHardwareTimingReplay(
                first, fixture, true);

        assertEquals(HardwareReadinessAdmissionPolicy.RECORDED,
                context.hardwareTiming().admissionPolicy());
        assertTrue(context.getRewindRegistry().capture().entries()
                .containsKey(HardwareTimingReplayPort.REWIND_KEY),
                "force install must register a real replay port even when segment 0 "
                        + "has no stream");
    }

    private static Bk2FrameInput frame(int frameIndex) {
        return new Bk2FrameInput(frameIndex, 0, 0, false, "");
    }

    private static HardwareCompletionEdge edge(
            int rawFrame, HardwareWorkHandle handle) {
        return new HardwareCompletionEdge(
                rawFrame,
                POST_OBJECTS,
                handle.kind(),
                handle.ordinal(),
                handle.submissionFingerprint());
    }

    private static HardwareWorkSubmission submission(
            boolean exportable, int workUnits, int payloadByte) {
        return new HardwareWorkSubmission(
                HardwareWorkKind.KOS_MODULE_QUEUE,
                0x3000 + payloadByte,
                0x100,
                0x5000,
                1,
                "KosM",
                1,
                exportable,
                new TestPreparation(workUnits, new byte[] {(byte) payloadByte}));
    }

    private static final class TimingFixture implements TraceReplayFixture {
        private final HardwareTimingReplayPort port;
        private TraceHardwareTimingBoundaryObserver observer;
        private boolean closed;
        private final java.util.ArrayList<Integer> latchedRawFrames =
                new java.util.ArrayList<>();

        private TimingFixture(HardwareTimingReplayPort port) {
            this.port = port;
        }

        @Override
        public void installHardwareTimingReplay(HardwareTimingReplayPort replayPort) {
            observer = new TraceHardwareTimingBoundaryObserver(replayPort);
        }

        @Override
        public void beginTraceRow(int traceIndex, int rawFrame) {
            latchedRawFrames.add(rawFrame);
            observer.beginRawFrame(rawFrame);
        }

        @Override
        public void enterHardwareTimingGap() {
            observer.enterUnrepresentedGap();
        }

        @Override
        public void verifyHardwareTimingSegmentEdges() {
            port.verifySegmentEdges();
        }

        @Override
        public void handoffHardwareTimingReplay(HardwareTimingSchedule nextSchedule) {
            port.handoffTo(nextSchedule);
        }

        @Override
        public void closeHardwareTimingReplayRun() {
            if (!closed) {
                closed = true;
                port.verifyRunComplete();
            }
        }

        @Override
        public void abortHardwareTimingReplayRun() {
            closed = true;
            observer = null;
        }

        @Override
        public AbstractPlayableSprite sprite() {
            return null;
        }

        @Override
        public GameplayModeContext gameplayMode() {
            return null;
        }

        @Override
        public int stepFrameFromRecording() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int skipFrameFromRecording() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advancePlayableAnimationsOnly() {
        }

        @Override
        public void advancePlayableFixedSlotsOnly() {
        }

        @Override
        public void suppressFirstSidekickAnimationOnce() {
        }

        @Override
        public int consumeRecordingFrameInputOnly() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advanceRecordingCursor(int frameCount) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InstallingFixture
            implements TraceReplayFixture {
        private final GameplayModeContext context;

        private InstallingFixture(GameplayModeContext context) {
            this.context = context;
        }

        @Override
        public GameplayModeContext gameplayMode() {
            return context;
        }

        @Override
        public void installHardwareTimingReplay(
                HardwareTimingReplayPort replayPort) {
            context.getRewindRegistry().register(replayPort);
            context.setHardwareTimingBoundaryObserver(
                    new TraceHardwareTimingBoundaryObserver(replayPort));
        }

        @Override
        public AbstractPlayableSprite sprite() {
            return null;
        }

        @Override
        public void beginTraceRow(int traceIndex, int rawFrame) {
        }

        @Override
        public void enterHardwareTimingGap() {
        }

        @Override
        public void verifyHardwareTimingSegmentEdges() {
        }

        @Override
        public void handoffHardwareTimingReplay(
                HardwareTimingSchedule nextSchedule) {
        }

        @Override
        public void closeHardwareTimingReplayRun() {
        }

        @Override
        public void abortHardwareTimingReplayRun() {
            context.setHardwareTimingBoundaryObserver(null);
            context.getRewindRegistry().deregister(
                    HardwareTimingReplayPort.REWIND_KEY);
        }

        @Override
        public int stepFrameFromRecording() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int skipFrameFromRecording() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advancePlayableAnimationsOnly() {
        }

        @Override
        public void advancePlayableFixedSlotsOnly() {
        }

        @Override
        public void suppressFirstSidekickAnimationOnce() {
        }

        @Override
        public int consumeRecordingFrameInputOnly() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void advanceRecordingCursor(int frameCount) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class InertHooks
            implements TraceRunReplayWalker.EngineHooks {
        @Override
        public int currentBk2Frame() {
            return 0;
        }

        @Override
        public BonusStageType peekBonusRequest() {
            return null;
        }

        @Override
        public boolean isSpecialStageRequested() {
            return false;
        }

        @Override
        public GameMode currentMode() {
            return GameMode.LEVEL;
        }
    }

    private record PreparationSnapshot(int remainingUnits, byte[] payload)
            implements HardwareWorkPreparationSnapshot {
        private PreparationSnapshot {
            payload = payload.clone();
        }

        @Override
        public HardwareWorkPreparation recreatePreparation() {
            return new TestPreparation(remainingUnits, payload);
        }
    }

    private static final class TestPreparation implements HardwareWorkPreparation {
        private int remainingUnits;
        private final byte[] payload;

        private TestPreparation(int remainingUnits, byte[] payload) {
            this.remainingUnits = remainingUnits;
            this.payload = payload.clone();
        }

        @Override
        public boolean stepOneWorkUnit() {
            if (remainingUnits == 0) {
                return false;
            }
            remainingUnits--;
            return true;
        }

        @Override
        public boolean isPrepared() {
            return remainingUnits == 0;
        }

        @Override
        public byte[] preparedPayload() {
            return payload.clone();
        }

        @Override
        public HardwareWorkPreparationSnapshot snapshot() {
            return new PreparationSnapshot(remainingUnits, payload);
        }

        @Override
        public void restore(HardwareWorkPreparationSnapshot snapshot) {
            remainingUnits = ((PreparationSnapshot) snapshot).remainingUnits();
        }
    }
}
