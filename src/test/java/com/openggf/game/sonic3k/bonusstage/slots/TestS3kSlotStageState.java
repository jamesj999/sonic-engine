package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TestS3kSlotStageState {

    @Test
    void bootstrapUsesRomInitialState() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        assertEquals(0, state.statTable());
        assertEquals(0x40, state.scalarIndex1());
        assertFalse(state.paletteCycleEnabled());
        assertEquals(0, state.lastCollisionTileId());
        assertEquals(0x10, state.eventsBgX());
        assertEquals(0x2D, state.eventsBgY());
    }

    @Test
    void angleUsesHighByteOfStatTable() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        state.setStatTable(0x0040);
        assertEquals(0x00, state.angle());

        state.setStatTable(0x0100);
        assertEquals(0x01, state.angle());

        state.setStatTable(0x3FC0);
        assertEquals(0x3F, state.angle());
    }

    @Test
    void renderBuffersUseExpandedLayoutStride() {
        S3kSlotRenderBuffers buffers = S3kSlotRenderBuffers.fromRomData();

        assertEquals(0x80, buffers.layoutStrideBytes());
        assertEquals(0x80, buffers.layoutRows());
        assertEquals(0x80, buffers.layoutColumns());
    }

    @Test
    void expandedLayoutCopiesRowsIntoRomWorldOffsetSlots() {
        byte[] expandedLayout = S3kSlotRomData.buildExpandedLayoutBuffer();

        assertEquals(0x80 * 0x80, expandedLayout.length);
        int firstRow = (0x20 * 0x80) + 0x20;
        int secondRow = (0x21 * 0x80) + 0x20;
        int lastRow = (0x3F * 0x80) + 0x20;
        assertArrayEquals(row(0), Arrays.copyOfRange(expandedLayout, firstRow, firstRow + 0x20));
        assertArrayEquals(row(1), Arrays.copyOfRange(expandedLayout, secondRow, secondRow + 0x20));
        assertArrayEquals(row(0x1F), Arrays.copyOfRange(expandedLayout, lastRow, lastRow + 0x20));
        assertEquals(0, expandedLayout[0]);
        assertEquals(0, expandedLayout[(0x20 * 0x80) + 0x1F]);
    }

    private static byte[] row(int rowIndex) {
        int start = rowIndex * 0x20;
        return Arrays.copyOfRange(S3kSlotRomData.SLOT_BONUS_LAYOUT, start, start + 0x20);
    }
}


