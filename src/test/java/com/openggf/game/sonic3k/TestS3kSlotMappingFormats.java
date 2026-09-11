package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.data.RomByteReader;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
public class TestS3kSlotMappingFormats {
    private RomByteReader reader;

    @BeforeEach
    public void setUp() throws IOException {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        reader = RomByteReader.fromRom(rom);
    }

    @Test
    public void legacySlotStageMapsDecodeToExpectedPieceCounts() {
        assertLegacyPieceCounts(Sonic3kConstants.MAP_SLOT_COLORED_WALL_ADDR, 16, 1);
        assertLegacyPieceCounts(Sonic3kConstants.MAP_SLOT_GOAL_ADDR, 3, 1);
        assertLegacyPieceCounts(Sonic3kConstants.MAP_SLOT_BUMPER_ADDR, 3, 1);
        assertLegacyPieceCounts(Sonic3kConstants.MAP_SLOT_R_AND_PEPPERMINT_ADDR, 4, 1);
        assertLegacyPieceCounts(Sonic3kConstants.MAP_SLOT_MACHINE_FACE_ADDR, 6, 8);
        List<SpriteMappingFrame> ringFrames = S3kSpriteDataLoader.loadMappingFrames(
                reader, Sonic3kConstants.MAP_SLOT_RING_STAGE_ADDR,
                S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X);
        assertEquals(9, ringFrames.size());
        assertEquals(1, ringFrames.getFirst().pieces().size());
        assertEquals(0, ringFrames.getLast().pieces().size());
    }

    @Test
    public void standardSlotObjectMapsRemainOnStandardDecoder() {
        assertStandardPieceCounts(Sonic3kConstants.MAP_SLOT_BONUS_CAGE_ADDR, 6, 8);
        assertStandardPieceCounts(Sonic3kConstants.MAP_SLOT_SPIKE_REWARD_ADDR, 1, 1);
    }

    private void assertLegacyPieceCounts(int mappingAddr, int expectedFrames, int expectedFirstPieceCount) {
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                reader, mappingAddr, S3kSpriteDataLoader.MappingFormat.LEGACY_BYTE_X);
        assertEquals(expectedFrames, frames.size());
        assertEquals(expectedFirstPieceCount, frames.getFirst().pieces().size());
        assertTrue(frames.stream().allMatch(frame -> frame.pieces().size() > 0 && frame.pieces().size() < 32));
    }

    private void assertStandardPieceCounts(int mappingAddr, int expectedFrames, int expectedFirstPieceCount) {
        List<SpriteMappingFrame> frames = S3kSpriteDataLoader.loadMappingFrames(
                reader, mappingAddr, S3kSpriteDataLoader.MappingFormat.STANDARD);
        assertEquals(expectedFrames, frames.size());
        assertEquals(expectedFirstPieceCount, frames.getFirst().pieces().size());
        assertTrue(frames.stream().allMatch(frame -> frame.pieces().size() > 0 && frame.pieces().size() < 64));
    }
}


