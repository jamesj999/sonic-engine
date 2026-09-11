package com.openggf.game.sonic2.specialstage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.List;
import com.openggf.level.Pattern;
import com.openggf.level.render.TileLoadRequest;

import static org.junit.jupiter.api.Assertions.*;
import static com.openggf.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Tests for Sonic 2 Special Stage data loading from ROM.
 * These tests require the Sonic 2 REV01 ROM to be present.
 */
@RequiresRom(SonicGame.SONIC_2)
public class Sonic2SpecialStageDataLoaderTest {
    private Rom rom;
    private Sonic2SpecialStageDataLoader loader;

    @BeforeEach
    public void setUp() {
        rom = com.openggf.tests.TestEnvironment.currentRom();
        loader = new Sonic2SpecialStageDataLoader(rom);
    }

    @Test
    public void testLoadPerspectiveData() throws IOException {
        byte[] data = loader.getPerspectiveData();
        assertNotNull(data, "Perspective data should not be null");
        assertTrue(data.length > PERSPECTIVE_DATA_SIZE, "Perspective data should be decompressed (> compressed size)");
    }

    @Test
    public void testLoadLevelLayouts() throws IOException {
        byte[] data = loader.getLevelLayouts();
        assertNotNull(data, "Level layouts should not be null");
        assertTrue(data.length > 0, "Level layouts should be decompressed");
    }

    @Test
    public void testLoadObjectLocations() throws IOException {
        byte[] data = loader.getObjectLocations();
        assertNotNull(data, "Object locations should not be null");
        assertTrue(data.length > OBJECT_LOCATIONS_SIZE, "Object locations should be decompressed (> compressed size)");
    }

    @Test
    public void testLoadTrackFrames() throws IOException {
        byte[][] frames = loader.getTrackFrames();
        assertNotNull(frames, "Track frames should not be null");
        assertEquals(TRACK_FRAME_COUNT, frames.length, "Should have 56 track frames");

        for (int i = 0; i < TRACK_FRAME_COUNT; i++) {
            assertNotNull(frames[i], "Track frame " + i + " should not be null");
            assertEquals(TRACK_FRAME_SIZES[i], frames[i].length, "Track frame " + i + " size mismatch");
        }
    }

    @Test
    public void testLoadBackgroundMappings() throws IOException {
        byte[] mainMappings = loader.getBackgroundMainMappings();
        assertNotNull(mainMappings, "Main background mappings should not be null");
        assertTrue(mainMappings.length > 0, "Main mappings should be decompressed");
        assertEquals(0, mainMappings.length % 2, "Main mappings should be word-aligned");

        byte[] lowerMappings = loader.getBackgroundLowerMappings();
        assertNotNull(lowerMappings, "Lower background mappings should not be null");
        assertTrue(lowerMappings.length > 0, "Lower mappings should be decompressed");
        assertEquals(0, lowerMappings.length % 2, "Lower mappings should be word-aligned");
    }

    @Test
    public void testLoadSkydomeScrollTable() throws IOException {
        byte[] table = loader.getSkydomeScrollTable();
        assertNotNull(table, "Skydome scroll table should not be null");
        assertEquals(SKYDOME_SCROLL_TABLE_SIZE, table.length, "Skydome table size mismatch");
    }

    @Test
    public void testLoadRingRequirements() throws IOException {
        byte[] teamReqs = loader.getRingRequirementsTeam();
        assertNotNull(teamReqs, "Team ring requirements should not be null");
        assertEquals(RING_REQ_TABLE_SIZE, teamReqs.length, "Team requirements size mismatch");

        byte[] soloReqs = loader.getRingRequirementsSolo();
        assertNotNull(soloReqs, "Solo ring requirements should not be null");
        assertEquals(RING_REQ_TABLE_SIZE, soloReqs.length, "Solo requirements size mismatch");
    }

    @Test
    public void testGetRingRequirement() throws IOException {
        int req = loader.getRingRequirement(0, 0, false);
        assertTrue(req > 0, "Ring requirement should be positive");
        assertTrue(req < 256, "Ring requirement should be reasonable");
    }

    @Test
    public void testLoadAnimDurationTable() throws IOException {
        byte[] table = loader.getAnimDurationTable();
        assertNotNull(table, "Anim duration table should not be null");
        assertEquals(ANIM_DURATION_TABLE_SIZE, table.length, "Anim duration table size mismatch");

        assertEquals(60, table[0] & 0xFF, "First duration should be 60");
        assertEquals(30, table[1] & 0xFF, "Second duration should be 30");
        assertEquals(15, table[2] & 0xFF, "Third duration should be 15");
    }

    @Test
    public void testLoadSpecialTailsTextArt() throws IOException {
        Pattern[] patterns = loader.getTailsTextArtPatterns();

        assertEquals(5, patterns.length,
                "ArtNem_SpecialTailsText should decompress to exactly five patterns");
        assertSame(patterns, loader.getTailsTextArtPatterns(), "Tails text art should be cached");
    }

    @Test
    public void testParseSegmentByte() {
        int[] parsed = Sonic2SpecialStageDataLoader.parseSegmentByte(0x03);
        assertEquals(3, parsed[0], "Segment type should be 3");
        assertEquals(0, parsed[1], "Flip flag should be 0");

        parsed = Sonic2SpecialStageDataLoader.parseSegmentByte(0x82);
        assertEquals(2, parsed[0], "Segment type should be 2");
        assertEquals(1, parsed[1], "Flip flag should be 1");
    }

    @Test
    public void testGetSegmentAnimation() {
        int[] anim = Sonic2SpecialStageDataLoader.getSegmentAnimation(SEGMENT_STRAIGHT);
        assertNotNull(anim, "Animation should not be null");
        assertEquals(16, anim.length, "Straight animation should have 16 frames");
        assertEquals(0x11, anim[0], "First frame should be 0x11");

        anim = Sonic2SpecialStageDataLoader.getSegmentAnimation(SEGMENT_TURN_THEN_RISE);
        assertEquals(24, anim.length, "TurnThenRise animation should have 24 frames");
        assertEquals(0x26, anim[0], "First frame should be 0x26 (turning)");
    }

    @Test
    public void testTrackFrameStructure() throws IOException {
        byte[] frame = loader.getTrackFrame(0x11);

        assertTrue(frame.length >= 12, "Frame should have at least 12 bytes for headers");

        int seg1Len = ((frame[2] & 0xFF) << 8) | (frame[3] & 0xFF);
        assertTrue(seg1Len > 0 && seg1Len < frame.length, "Segment 1 length should be reasonable");
    }

    @Test
    public void testPerspectiveDataStructure() throws IOException {
        byte[] data = loader.getPerspectiveData();

        assertTrue(data.length >= 112, "Perspective data should have offset table");

        int firstOffset = ((data[0] & 0xFF) << 8) | (data[1] & 0xFF);
        assertTrue(firstOffset < data.length, "First offset should point within data");
    }

    @Test
    public void decodesCachedSpecialStagePlayerDplcPlansFromTheSuppliedRom()
            throws IOException {
        Sonic2SpecialStageDataLoader.PlayerDplcPlans plans =
                loader.getPlayerDplcPlans();

        assertEquals(18, plans.sonic().size());
        assertEquals(18, plans.tails().size());
        assertEquals(21, plans.tailsTails().size());
        assertEquals(List.of(
                        new TileLoadRequest(0x000, 16),
                        new TileLoadRequest(0x010, 9),
                        new TileLoadRequest(0x019, 2)),
                plans.sonic().getFirst().requests());
        assertEquals(List.of(
                        new TileLoadRequest(0x183, 9),
                        new TileLoadRequest(0x18C, 6),
                        new TileLoadRequest(0x192, 1)),
                plans.tails().getFirst().requests());
        assertEquals(List.of(new TileLoadRequest(0x2E3, 9)),
                plans.tailsTails().get(7).requests());
        assertSame(plans, loader.getPlayerDplcPlans(),
                "ROM-decoded plans should be cached for the special-stage lifetime");
    }
}
