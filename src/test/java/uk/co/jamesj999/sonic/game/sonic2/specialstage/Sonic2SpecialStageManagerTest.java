package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import org.junit.Test;

import static org.junit.Assert.*;
import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Unit tests for Sonic2SpecialStageManager.
 * These tests don't require the ROM file.
 */
public class Sonic2SpecialStageManagerTest {

    @Test
    public void testSingletonInstance() {
        Sonic2SpecialStageManager instance1 = Sonic2SpecialStageManager.getInstance();
        Sonic2SpecialStageManager instance2 = Sonic2SpecialStageManager.getInstance();

        assertNotNull("Manager instance should not be null", instance1);
        assertSame("Should return same singleton instance", instance1, instance2);
    }

    @Test
    public void testNotInitializedByDefault() {
        Sonic2SpecialStageManager manager = Sonic2SpecialStageManager.getInstance();
        manager.reset();
        assertFalse("Manager should not be initialized by default", manager.isInitialized());
    }

    @Test
    public void testH32Dimensions() {
        assertEquals("H32 width should be 256 pixels", 256, Sonic2SpecialStageManager.H32_WIDTH);
        assertEquals("H32 height should be 224 pixels", 224, Sonic2SpecialStageManager.H32_HEIGHT);
    }

    @Test
    public void testSegmentAnimationLengths() {
        assertEquals("SEGMENT_TURN_THEN_RISE animation should have 24 frames",
                24, ANIM_TURN_THEN_RISE.length);
        assertEquals("SEGMENT_TURN_THEN_DROP animation should have 24 frames",
                24, ANIM_TURN_THEN_DROP.length);
        assertEquals("SEGMENT_TURN_THEN_STRAIGHT animation should have 12 frames",
                12, ANIM_TURN_THEN_STRAIGHT.length);
        assertEquals("SEGMENT_STRAIGHT animation should have 16 frames",
                16, ANIM_STRAIGHT.length);
        assertEquals("SEGMENT_STRAIGHT_THEN_TURN animation should have 11 frames",
                11, ANIM_STRAIGHT_THEN_TURN.length);
    }

    @Test
    public void testAnimBaseDurations() {
        assertEquals("First duration should be 60", 60, ANIM_BASE_DURATIONS[0]);
        assertEquals("Second duration should be 30", 30, ANIM_BASE_DURATIONS[1]);
        assertEquals("Third duration should be 15", 15, ANIM_BASE_DURATIONS[2]);
        assertEquals("Fourth duration should be 10", 10, ANIM_BASE_DURATIONS[3]);
        assertEquals("Fifth duration should be 8", 8, ANIM_BASE_DURATIONS[4]);
        assertEquals("Sixth duration should be 6", 6, ANIM_BASE_DURATIONS[5]);
        assertEquals("Seventh duration should be 5", 5, ANIM_BASE_DURATIONS[6]);
        assertEquals("Eighth duration should be 0", 0, ANIM_BASE_DURATIONS[7]);
    }

    @Test
    public void testSegmentFrameCounts() {
        assertEquals("Should have 5 segment types", 5, SEGMENT_FRAME_COUNTS.length);
        assertEquals("TurnThenRise should have 24 frames", 24, SEGMENT_FRAME_COUNTS[SEGMENT_TURN_THEN_RISE]);
        assertEquals("TurnThenDrop should have 24 frames", 24, SEGMENT_FRAME_COUNTS[SEGMENT_TURN_THEN_DROP]);
        assertEquals("TurnThenStraight should have 12 frames", 12, SEGMENT_FRAME_COUNTS[SEGMENT_TURN_THEN_STRAIGHT]);
        assertEquals("Straight should have 16 frames", 16, SEGMENT_FRAME_COUNTS[SEGMENT_STRAIGHT]);
        assertEquals("StraightThenTurn should have 11 frames", 11, SEGMENT_FRAME_COUNTS[SEGMENT_STRAIGHT_THEN_TURN]);
    }

    @Test
    public void testTrackFrameOffsets() {
        assertEquals("Should have 56 track frame offsets", TRACK_FRAME_COUNT, TRACK_FRAME_OFFSETS.length);
        assertEquals("Should have 56 track frame sizes", TRACK_FRAME_COUNT, TRACK_FRAME_SIZES.length);

        assertEquals("First frame offset should be 0x0CA904", 0x0CA904, TRACK_FRAME_OFFSETS[0]);
        assertEquals("First frame size should be 1188", 1188, TRACK_FRAME_SIZES[0]);

        long expectedEnd = TRACK_FRAME_OFFSETS[TRACK_FRAME_COUNT - 1] + TRACK_FRAME_SIZES[TRACK_FRAME_COUNT - 1];
        assertEquals("Last frame should end at TRACK_FRAMES_END", TRACK_FRAMES_END, expectedEnd);
    }
}
