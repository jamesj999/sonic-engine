package com.openggf.game.sonic1.specialstage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSonic1SpecialStageWallMetadata {
    @Test
    void wallMetadataIsPackedWithoutPerLookupContainer() {
        for (int blockId = 1; blockId <= 0x24; blockId++) {
            int packed = Sonic1SpecialStageBlockType.getWallGroupAndPositionPacked(blockId);
            assertEquals((blockId - 1) / 9, Sonic1SpecialStageBlockType.wallGroup(packed));
            assertEquals((blockId - 1) % 9, Sonic1SpecialStageBlockType.wallPosition(packed));
        }
        assertEquals(-1, Sonic1SpecialStageBlockType.getWallGroupAndPositionPacked(0));
        assertEquals(-1, Sonic1SpecialStageBlockType.getWallGroupAndPositionPacked(0x25));
    }

    @Test
    void legacyWallPairApiRemainsCompatible() {
        assertEquals(null, Sonic1SpecialStageBlockType.getWallGroupAndPosition(0));
        assertEquals(null, Sonic1SpecialStageBlockType.getWallGroupAndPosition(0x25));
        for (int blockId = 1; blockId <= 0x24; blockId++) {
            int[] pair = Sonic1SpecialStageBlockType.getWallGroupAndPosition(blockId);
            assertEquals((blockId - 1) / 9, pair[0]);
            assertEquals((blockId - 1) % 9, pair[1]);
        }
    }
}
