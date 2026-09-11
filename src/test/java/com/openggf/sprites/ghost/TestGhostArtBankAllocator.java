package com.openggf.sprites.ghost;

import com.openggf.sprites.art.SpriteArtSet;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class TestGhostArtBankAllocator {
    @Test
    void shiftedArtSetKeepsArtAndMappingsButUsesReservedBase() {
        SpriteArtSet source = new SpriteArtSet(
                new com.openggf.level.Pattern[0],
                List.of(),
                List.of(),
                1,
                0x100,
                2,
                0x40,
                null,
                null);

        SpriteArtSet shifted = GhostArtBankAllocator.shiftToGhostBank(source, 0x38080);

        assertSame(source.artTiles(), shifted.artTiles());
        assertSame(source.mappingFrames(), shifted.mappingFrames());
        assertSame(source.dplcFrames(), shifted.dplcFrames());
        assertEquals(source.paletteIndex(), shifted.paletteIndex());
        assertEquals(0x38080, shifted.basePatternIndex());
        assertEquals(source.bankSize(), shifted.bankSize());
    }

    @Test
    void nextBankBaseAdvancesByBankSize() {
        assertEquals(0x380C0, GhostArtBankAllocator.nextBankBase(0x38080, 0x40));
    }
}
