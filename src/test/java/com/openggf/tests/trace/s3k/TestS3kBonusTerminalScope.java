package com.openggf.tests.trace.s3k;

import com.openggf.trace.TraceEvent.ZoneActState;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TestS3kBonusTerminalScope {

    @Test
    void derivesLastRepresentedBonusRawFrameBeforeDeparture() {
        List<TraceFrame> frames = List.of(frame(0), frame(1276), frame(1277));

        assertEquals(1276, AbstractS3kBonusStageTraceReplayTest.deriveLastBonusRawFrame(
                frames,
                List.of(new ZoneActState(0, 19, 0, 0, 12),
                        new ZoneActState(1277, 0, 0, 0, 140))));
    }

    @Test
    void derivesBoundaryWhenRawFramesDoNotEqualTraceIndexes() {
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadata("s3k", 19, 0),
                List.of(frame(0), frame(2), frame(3)),
                Map.of(
                        0, List.of(new ZoneActState(0, 19, 0, 0, 12)),
                        3, List.of(new ZoneActState(3, 0, 0, 0, 140))));

        assertEquals(2,
                AbstractS3kBonusStageTraceReplayTest.deriveLastBonusRawFrame(trace));
    }

    @Test
    void rejectsScopeWhoseInitialStateIsNotBonusMode() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AbstractS3kBonusStageTraceReplayTest.deriveLastBonusRawFrame(
                        List.of(frame(0), frame(1276)),
                        List.of(new ZoneActState(0, 0, 0, 0, 140),
                                new ZoneActState(1277, 0, 0, 0, 140))));

        assertEquals("standalone bonus trace must start in game_mode=12", error.getMessage());
    }

    @Test
    void rejectsScopeWithoutLaterBonusDeparture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AbstractS3kBonusStageTraceReplayTest.deriveLastBonusRawFrame(
                        List.of(frame(0), frame(1276)),
                        List.of(new ZoneActState(0, 19, 0, 0, 12))));

        assertEquals("standalone bonus trace has no departure from game_mode=12", error.getMessage());
    }

    @Test
    void rejectsScopeWithoutRepresentedPredecessorToDeparture() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AbstractS3kBonusStageTraceReplayTest.deriveLastBonusRawFrame(
                        List.of(frame(1277)),
                        List.of(new ZoneActState(0, 19, 0, 0, 12),
                                new ZoneActState(1277, 0, 0, 0, 140))));

        assertEquals("standalone bonus departure has no represented predecessor: raw_frame=1277",
                error.getMessage());
    }

    @Test
    void rejectsAmbiguousMultipleBonusDepartures() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AbstractS3kBonusStageTraceReplayTest.deriveLastBonusRawFrame(
                        List.of(frame(0), frame(1276), frame(1277), frame(1400)),
                        List.of(new ZoneActState(0, 19, 0, 0, 12),
                                new ZoneActState(1277, 0, 0, 0, 140),
                                new ZoneActState(1400, 0, 0, 0, 4))));

        assertEquals("standalone bonus trace has ambiguous departures from game_mode=12: 2",
                error.getMessage());
    }

    @Test
    void continuesBeforeDerivedLastBonusRawFrameRegardlessOfLiveCompletion() {
        assertEquals(AbstractS3kBonusStageTraceReplayTest.TimingPrefixDecision.CONTINUE,
                AbstractS3kBonusStageTraceReplayTest.decideTimingPrefixClose(
                        1275, 1276, false));
        assertEquals(AbstractS3kBonusStageTraceReplayTest.TimingPrefixDecision.CONTINUE,
                AbstractS3kBonusStageTraceReplayTest.decideTimingPrefixClose(
                        1275, 1276, true));
    }

    @Test
    void closesAtDerivedLastBonusRawFrameRegardlessOfLiveCompletion() {
        assertEquals(AbstractS3kBonusStageTraceReplayTest.TimingPrefixDecision.CLOSE_PREFIX,
                AbstractS3kBonusStageTraceReplayTest.decideTimingPrefixClose(
                        1276, 1276, false));
        assertEquals(AbstractS3kBonusStageTraceReplayTest.TimingPrefixDecision.CLOSE_PREFIX,
                AbstractS3kBonusStageTraceReplayTest.decideTimingPrefixClose(
                        1276, 1276, true));
    }

    @Test
    void rejectsReplayPastDerivedLastBonusRawFrame() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> AbstractS3kBonusStageTraceReplayTest.decideTimingPrefixClose(
                        1277, 1276, true));

        assertEquals("bonus replay advanced beyond semantic timing prefix boundary: "
                        + "current_raw_frame=1277, last_bonus_raw_frame=1276",
                error.getMessage());
    }

    private static TraceFrame frame(int rawFrame) {
        return TraceFrame.executionTestFrame(rawFrame, rawFrame, rawFrame, 0);
    }
}
