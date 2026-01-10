package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.File;
import java.io.IOException;

import static org.junit.Assert.*;
import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Tests for Sonic 2 Special Stage data loading from ROM.
 * These tests require the Sonic 2 REV01 ROM to be present.
 */
public class Sonic2SpecialStageDataLoaderTest {

    private static final String ROM_FILENAME = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";

    private Rom rom;
    private Sonic2SpecialStageDataLoader loader;

    @Before
    public void setUp() {
        File romFile = new File(ROM_FILENAME);
        Assume.assumeTrue("ROM file not found, skipping test", romFile.exists());

        rom = new Rom();
        boolean opened = rom.open(ROM_FILENAME);
        Assume.assumeTrue("Failed to open ROM", opened);

        loader = new Sonic2SpecialStageDataLoader(rom);
    }

    @Test
    public void testLoadPerspectiveData() throws IOException {
        byte[] data = loader.getPerspectiveData();
        assertNotNull("Perspective data should not be null", data);
        assertTrue("Perspective data should be decompressed (> compressed size)",
                data.length > PERSPECTIVE_DATA_SIZE);
    }

    @Test
    public void testLoadLevelLayouts() throws IOException {
        byte[] data = loader.getLevelLayouts();
        assertNotNull("Level layouts should not be null", data);
        assertTrue("Level layouts should be decompressed", data.length > 0);
    }

    @Test
    public void testLoadObjectLocations() throws IOException {
        byte[] data = loader.getObjectLocations();
        assertNotNull("Object locations should not be null", data);
        assertTrue("Object locations should be decompressed (> compressed size)",
                data.length > OBJECT_LOCATIONS_SIZE);
    }

    @Test
    public void testLoadTrackFrames() throws IOException {
        byte[][] frames = loader.getTrackFrames();
        assertNotNull("Track frames should not be null", frames);
        assertEquals("Should have 56 track frames", TRACK_FRAME_COUNT, frames.length);

        for (int i = 0; i < TRACK_FRAME_COUNT; i++) {
            assertNotNull("Track frame " + i + " should not be null", frames[i]);
            assertEquals("Track frame " + i + " size mismatch",
                    TRACK_FRAME_SIZES[i], frames[i].length);
        }
    }

    @Test
    public void testLoadBackgroundMappings() throws IOException {
        byte[] mainMappings = loader.getBackgroundMainMappings();
        assertNotNull("Main background mappings should not be null", mainMappings);
        assertTrue("Main mappings should be decompressed", mainMappings.length > 0);
        assertEquals("Main mappings should be word-aligned", 0, mainMappings.length % 2);

        byte[] lowerMappings = loader.getBackgroundLowerMappings();
        assertNotNull("Lower background mappings should not be null", lowerMappings);
        assertTrue("Lower mappings should be decompressed", lowerMappings.length > 0);
        assertEquals("Lower mappings should be word-aligned", 0, lowerMappings.length % 2);
    }

    @Test
    public void testLoadSkydomeScrollTable() throws IOException {
        byte[] table = loader.getSkydomeScrollTable();
        assertNotNull("Skydome scroll table should not be null", table);
        assertEquals("Skydome table size mismatch", SKYDOME_SCROLL_TABLE_SIZE, table.length);
    }

    @Test
    public void testLoadRingRequirements() throws IOException {
        byte[] teamReqs = loader.getRingRequirementsTeam();
        assertNotNull("Team ring requirements should not be null", teamReqs);
        assertEquals("Team requirements size mismatch", RING_REQ_TABLE_SIZE, teamReqs.length);

        byte[] soloReqs = loader.getRingRequirementsSolo();
        assertNotNull("Solo ring requirements should not be null", soloReqs);
        assertEquals("Solo requirements size mismatch", RING_REQ_TABLE_SIZE, soloReqs.length);
    }

    @Test
    public void testGetRingRequirement() throws IOException {
        int req = loader.getRingRequirement(0, 0, false);
        assertTrue("Ring requirement should be positive", req > 0);
        assertTrue("Ring requirement should be reasonable", req < 256);
    }

    @Test
    public void testLoadAnimDurationTable() throws IOException {
        byte[] table = loader.getAnimDurationTable();
        assertNotNull("Anim duration table should not be null", table);
        assertEquals("Anim duration table size mismatch", ANIM_DURATION_TABLE_SIZE, table.length);

        assertEquals("First duration should be 60", 60, table[0] & 0xFF);
        assertEquals("Second duration should be 30", 30, table[1] & 0xFF);
        assertEquals("Third duration should be 15", 15, table[2] & 0xFF);
    }

    @Test
    public void testParseSegmentByte() {
        int[] parsed = Sonic2SpecialStageDataLoader.parseSegmentByte(0x03);
        assertEquals("Segment type should be 3", 3, parsed[0]);
        assertEquals("Flip flag should be 0", 0, parsed[1]);

        parsed = Sonic2SpecialStageDataLoader.parseSegmentByte(0x82);
        assertEquals("Segment type should be 2", 2, parsed[0]);
        assertEquals("Flip flag should be 1", 1, parsed[1]);
    }

    @Test
    public void testGetSegmentAnimation() {
        int[] anim = Sonic2SpecialStageDataLoader.getSegmentAnimation(SEGMENT_STRAIGHT);
        assertNotNull("Animation should not be null", anim);
        assertEquals("Straight animation should have 16 frames", 16, anim.length);
        assertEquals("First frame should be 0x11", 0x11, anim[0]);

        anim = Sonic2SpecialStageDataLoader.getSegmentAnimation(SEGMENT_TURN_THEN_RISE);
        assertEquals("TurnThenRise animation should have 24 frames", 24, anim.length);
        assertEquals("First frame should be 0x26 (turning)", 0x26, anim[0]);
    }

    @Test
    public void testTrackFrameStructure() throws IOException {
        byte[] frame = loader.getTrackFrame(0x11);

        assertTrue("Frame should have at least 12 bytes for headers", frame.length >= 12);

        int seg1Len = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        assertTrue("Segment 1 length should be reasonable", seg1Len > 0 && seg1Len < frame.length);
    }

    @Test
    public void testPerspectiveDataStructure() throws IOException {
        byte[] data = loader.getPerspectiveData();

        assertTrue("Perspective data should have offset table", data.length >= 112);

        int firstOffset = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        assertTrue("First offset should point within data", firstOffset < data.length);
    }
}
