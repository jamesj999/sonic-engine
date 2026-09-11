package com.openggf.trace.live;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GroundMode;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import com.openggf.tests.SingletonResetExtension;
import com.openggf.tests.trace.TraceV5TestFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(SingletonResetExtension.class)
class LiveTraceComparatorTest {

    @Test
    void skipIncrementsLagCounter() {
        LiveTraceComparator c = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.executionTestFrame(0, 10, 0x100, 0),
                        TraceFrame.executionTestFrame(1, 10, 0x100, 1))),
                ToleranceConfig.DEFAULT,
                0,
                () -> null);
        Bk2FrameInput empty = new Bk2FrameInput(0, 0, 0, false, "0");
        c.afterFrameAdvanced(empty, true);
        assertEquals(1, c.laggedFrames());
        assertEquals(0, c.errorCount());
    }

    @Test
    void shouldSkipGameplayTickDelegatesToPhase() {
        // First two frames share the same gameplay_frame_counter → second is VBLANK_ONLY
        LiveTraceComparator c = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.executionTestFrame(0, 10, 0x100, 0),
                        TraceFrame.executionTestFrame(1, 11, 0x100, 1))),
                ToleranceConfig.DEFAULT,
                0,
                () -> null);
        Bk2FrameInput empty = new Bk2FrameInput(1, 0, 0, false, "0");
        // Advance our internal cursor past index 0 first:
        c.afterFrameAdvanced(new Bk2FrameInput(0, 0, 0, false, "0"), false);
        assertTrue(c.shouldSkipGameplayTick(empty));
    }

    @Test
    void skippedStoredRowOwnsExactlyOneClosureRegardlessOfCounterDelta() {
        Bk2FrameInput empty = new Bk2FrameInput(0, 0, 0, false, "0");
        LiveTraceComparator repeated = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.executionTestFrame(0, 10, 0x100, 0),
                        TraceFrame.executionTestFrame(1, 10, 0x100, 1))),
                ToleranceConfig.DEFAULT, 1, () -> null);
        LiveTraceComparator advanced = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.executionTestFrame(0, 10, 0x100, 0),
                        TraceFrame.executionTestFrame(1, 11, 0x100, 1))),
                ToleranceConfig.DEFAULT, 1, () -> null);
        LiveTraceComparator doubleAdvanced = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.executionTestFrame(0, 10, 0x100, 0),
                        TraceFrame.executionTestFrame(1, 12, 0x100, 1))),
                ToleranceConfig.DEFAULT, 1, () -> null);

        assertTrue(repeated.shouldAdvanceVblankOnSkippedTick(empty));
        assertTrue(advanced.shouldAdvanceVblankOnSkippedTick(empty));
        assertEquals(1, repeated.vblankAdvanceCountOnSkippedTick(empty));
        assertEquals(1, advanced.vblankAdvanceCountOnSkippedTick(empty));
        assertEquals(1, doubleAdvanced.vblankAdvanceCountOnSkippedTick(empty));
    }

    @Test
    void advanceOnlySkipsGameplayWithoutAdvancingVblankOrLagCount() {
        TraceFrame beforeLatch = TraceFrame.of(0, 0,
                (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceFrame inputLatch = TraceFrame.of(1, AbstractPlayableSprite.INPUT_JUMP,
                (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceFrame levelBoundary = TraceFrame.of(2, AbstractPlayableSprite.INPUT_JUMP,
                (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        TraceData trace = TraceFixtures.trace(
                TraceFixtures.metadata("s3k", 0, 0),
                List.of(beforeLatch, inputLatch, levelBoundary),
                Map.of(
                        0, List.of(new TraceEvent.ZoneActState(0, 0, 0, 0, 4)),
                        2, List.of(
                                new TraceEvent.ZoneActState(2, 0, 0, 0, 12),
                                new TraceEvent.Checkpoint(
                                        2, "gameplay_start", 0, 0, 0, 12, null))));
        LiveTraceComparator comparator = new LiveTraceComparator(
                trace, ToleranceConfig.DEFAULT, 1, () -> null);
        Bk2FrameInput jump = new Bk2FrameInput(
                1, 0, 1, false, "jump latch");

        assertTrue(comparator.shouldSkipGameplayTick(jump),
                "ADVANCE_ONLY must suppress the already-resident level");
        assertFalse(comparator.shouldAdvanceVblankOnSkippedTick(jump));
        assertEquals(0, comparator.vblankAdvanceCountOnSkippedTick(jump));

        comparator.afterFrameAdvanced(jump, true);

        assertEquals(0, comparator.laggedFrames(),
                "an input-latch row is not a ROM lag/VBlank frame");
        assertEquals(1, comparator.currentVisualFrame().frame());
    }

    @Test
    void s3kTraceWithoutGameplayStartCheckpointStillComparesFullLevelFrames() {
        AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
        when(sprite.getCentreX()).thenReturn((short) 11);
        when(sprite.getCentreY()).thenReturn((short) 0);
        when(sprite.getXSpeed()).thenReturn((short) 0);
        when(sprite.getYSpeed()).thenReturn((short) 0);
        when(sprite.getGSpeed()).thenReturn((short) 0);
        when(sprite.getAngle()).thenReturn((byte) 0);
        when(sprite.getAir()).thenReturn(false);
        when(sprite.getRolling()).thenReturn(false);
        when(sprite.getGroundMode()).thenReturn(GroundMode.GROUND);

        LiveTraceComparator c = new LiveTraceComparator(
                TraceFixtures.trace(
                        TraceFixtures.metadata("s3k", 2, 1),
                        List.of(TraceFrame.of(0, 0,
                                (short) 10, (short) 0,
                                (short) 0, (short) 0, (short) 0,
                                (byte) 0, false, false, 0))),
                ToleranceConfig.DEFAULT,
                0,
                () -> sprite);

        c.afterFrameAdvanced(new Bk2FrameInput(0, 0, 0, false, "0"), false);

        assertEquals(1, c.errorCount());
        assertEquals("x", c.firstNonCameraPhysicsMismatch().field());
        assertEquals(0, c.firstNonCameraPhysicsMismatch().frame());
    }

    @Test
    void invokesFirstErrorCallbackOnceOnFirstDesync() {
        AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
        when(sprite.getCentreX()).thenReturn((short) 11);
        when(sprite.getCentreY()).thenReturn((short) 0);
        when(sprite.getXSpeed()).thenReturn((short) 0);
        when(sprite.getYSpeed()).thenReturn((short) 0);
        when(sprite.getGSpeed()).thenReturn((short) 0);
        when(sprite.getAngle()).thenReturn((byte) 0);
        when(sprite.getAir()).thenReturn(false);
        when(sprite.getRolling()).thenReturn(false);
        when(sprite.getGroundMode()).thenReturn(GroundMode.GROUND);
        Runnable onFirstError = mock(Runnable.class);

        LiveTraceComparator c = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.executionTestFrame(0, 10, 0x100, 0),
                        TraceFrame.executionTestFrame(1, 11, 0x100, 1))),
                ToleranceConfig.DEFAULT,
                0,
                () -> sprite,
                onFirstError);

        c.afterFrameAdvanced(new Bk2FrameInput(0, 0, 0, false, "0"), false);
        c.afterFrameAdvanced(new Bk2FrameInput(1, 0, 0, false, "0"), false);

        assertTrue(c.hasRecordingDesync());
        verify(onFirstError, times(1)).run();
    }

    @Test
    void comparesRecordedSubpixelsDuringLivePlayback() {
        AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
        when(sprite.getCentreX()).thenReturn((short) 0);
        when(sprite.getCentreY()).thenReturn((short) 0);
        when(sprite.getXSpeed()).thenReturn((short) 0);
        when(sprite.getYSpeed()).thenReturn((short) 0);
        when(sprite.getGSpeed()).thenReturn((short) 0);
        when(sprite.getAngle()).thenReturn((byte) 0);
        when(sprite.getAir()).thenReturn(false);
        when(sprite.getRolling()).thenReturn(false);
        when(sprite.getGroundMode()).thenReturn(GroundMode.GROUND);
        when(sprite.getXSubpixelRaw()).thenReturn(1);
        when(sprite.getYSubpixelRaw()).thenReturn(0);
        TraceFrame frame = TraceFrame.parseCsvRow(TraceV5TestFixture.levelRow(0));
        LiveTraceComparator c = new LiveTraceComparator(
                stubTrace(List.of(frame)), ToleranceConfig.DEFAULT, 0, () -> sprite);

        c.afterFrameAdvanced(new Bk2FrameInput(0, 0, 0, false, "0"), false);

        assertEquals(1, c.errorCount(),
                "live whole-movie replay must surface subpixel drift before it carries into x/y");
    }

    @Test
    void inputMisalignmentIsAnExplicitLiveErrorWithoutMovingTheCursorTwice() {
        TraceFrame expected = TraceFrame.of(
                0, AbstractPlayableSprite.INPUT_JUMP,
                (short) 0, (short) 0,
                (short) 0, (short) 0, (short) 0,
                (byte) 0, false, false, 0);
        LiveTraceComparator comparator = new LiveTraceComparator(
                stubTrace(List.of(expected)),
                ToleranceConfig.DEFAULT, 0, () -> null);

        comparator.afterFrameAdvanced(
                new Bk2FrameInput(0, 0, 0, false, "misaligned"), false);

        assertEquals(1, comparator.errorCount());
        assertEquals("input_alignment",
                comparator.recentMismatches().getFirst().field());
        assertEquals(1, comparator.cursor());
    }

    @Test
    void rewindSeekShowsLastAppliedFrameButKeepsNextComparisonCursor() {
        AbstractPlayableSprite sprite = mock(AbstractPlayableSprite.class);
        when(sprite.getCentreX()).thenReturn((short) 300);
        when(sprite.getCentreY()).thenReturn((short) 0);
        when(sprite.getXSpeed()).thenReturn((short) 0);
        when(sprite.getYSpeed()).thenReturn((short) 0);
        when(sprite.getGSpeed()).thenReturn((short) 0);
        when(sprite.getAngle()).thenReturn((byte) 0);
        when(sprite.getAir()).thenReturn(false);
        when(sprite.getRolling()).thenReturn(false);
        when(sprite.getGroundMode()).thenReturn(GroundMode.GROUND);

        LiveTraceComparator c = new LiveTraceComparator(
                stubTrace(List.of(
                        TraceFrame.of(0, 0, (short) 100, (short) 0,
                                (short) 0, (short) 0, (short) 0,
                                (byte) 0, false, false, 0),
                        TraceFrame.of(1, 0, (short) 200, (short) 0,
                                (short) 0, (short) 0, (short) 0,
                                (byte) 0, false, false, 0),
                        TraceFrame.of(2, 0, (short) 300, (short) 0,
                                (short) 0, (short) 0, (short) 0,
                                (byte) 0, false, false, 0))),
                ToleranceConfig.DEFAULT,
                0,
                () -> sprite);

        c.seekForRewind(2);

        assertEquals(1, c.currentVisualFrame().frame(),
                "restored frame boundary 2 should draw the last applied trace frame");

        c.afterFrameAdvanced(new Bk2FrameInput(2, 0, 0, false, "0"), false);

        assertEquals(0, c.errorCount(),
                "the next live comparison after releasing rewind should still use cursor 2");
        assertEquals(2, c.currentVisualFrame().frame());
    }

    private static TraceData stubTrace(List<TraceFrame> frames) {
        return TraceFixtures.trace(TraceFixtures.metadata("s2", 0, 0), frames);
    }
}
