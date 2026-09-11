package com.openggf.game.sonic3k.specialstage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.openggf.game.sonic3k.specialstage.Sonic3kSpecialStageConstants.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the S3K special stage grid.
 * No ROM or OpenGL required.
 */
class TestSonic3kSpecialStageGrid {
    private Sonic3kSpecialStageGrid grid;

    @BeforeEach
    void setUp() {
        grid = new Sonic3kSpecialStageGrid();
    }

    @Test
    void testEmptyGridReturnsEmptyCells() {
        for (int x = 0; x < 32; x++) {
            for (int y = 0; y < 32; y++) {
                assertEquals(CELL_EMPTY, grid.getCell(x, y),
                        "Cell (" + x + "," + y + ") should be empty");
            }
        }
    }

    @Test
    void testSetAndGetCell() {
        grid.setCell(5, 10, CELL_BLUE);
        assertEquals(CELL_BLUE, grid.getCell(5, 10));
    }

    @Test
    void testGridWrapsX() {
        grid.setCell(0, 0, CELL_RED);
        assertEquals(CELL_RED, grid.getCell(32, 0), "X should wrap at 32");
        assertEquals(CELL_RED, grid.getCell(64, 0), "X should wrap at 64");
        assertEquals(CELL_RED, grid.getCell(-32, 0), "X should wrap at -32");
    }

    @Test
    void testGridWrapsY() {
        grid.setCell(0, 0, CELL_BUMPER);
        assertEquals(CELL_BUMPER, grid.getCell(0, 32), "Y should wrap at 32");
        assertEquals(CELL_BUMPER, grid.getCell(0, -32), "Y should wrap at -32");
    }

    @Test
    void testPositionToIndex() {
        // At position (0x100, 0x100), grid cell should be (1, 1)
        // Formula: ((Y+0x80)>>8 & 0x1F) * 0x20 + ((X+0x80)>>8 & 0x1F)
        // = ((0x180)>>8 & 0x1F) * 0x20 + ((0x180)>>8 & 0x1F)
        // = (1 & 0x1F) * 0x20 + (1 & 0x1F) = 0x20 + 1 = 0x21
        assertEquals(0x21, Sonic3kSpecialStageGrid.positionToIndex(0x100, 0x100));
    }

    @Test
    void testPositionToIndexAtOrigin() {
        // At position (0, 0): ((0+0x80)>>8 & 0x1F) = 0
        assertEquals(0, Sonic3kSpecialStageGrid.positionToIndex(0, 0));
    }

    @Test
    void testPositionToIndexWraps() {
        // Position (0x2000, 0x2000) should wrap to (0, 0) since
        // (0x2080 >> 8) & 0x1F = 0x20 & 0x1F = 0
        assertEquals(0, Sonic3kSpecialStageGrid.positionToIndex(0x2000, 0x2000));
    }

    @Test
    void testGetCellAtPosition() {
        grid.setCell(1, 1, CELL_SPRING);
        // Position (0x100, 0x100) maps to grid cell (1, 1)
        assertEquals(CELL_SPRING, grid.getCellAtPosition(0x100, 0x100));
    }

    @Test
    void testCellByIndex() {
        // Index 0x21 = cell (1, 1)
        grid.setCellByIndex(0x21, CELL_RING);
        assertEquals(CELL_RING, grid.getCellByIndex(0x21));
        assertEquals(CELL_RING, grid.getCell(1, 1));
    }

    @Test
    void testCellByIndexWraps() {
        // Index wraps at 0x400
        grid.setCellByIndex(0x400, CELL_BLUE);
        assertEquals(CELL_BLUE, grid.getCellByIndex(0));
    }

    @Test
    void testCountBlueSpheres() {
        grid.setCell(0, 0, CELL_BLUE);
        grid.setCell(1, 0, CELL_BLUE);
        grid.setCell(2, 0, CELL_BLUE);
        grid.setCell(3, 0, CELL_RED);
        assertEquals(3, grid.countBlueSpheres());
    }

    @Test
    void testClearAll() {
        grid.setCell(5, 5, CELL_BLUE);
        grid.setCell(10, 10, CELL_RED);
        grid.clearAll();
        assertEquals(CELL_EMPTY, grid.getCell(5, 5));
        assertEquals(CELL_EMPTY, grid.getCell(10, 10));
    }

    @Test
    void testLoadFromLayoutData() {
        // Create mock layout data: 0x400 bytes of grid + 8 bytes of params
        byte[] layoutData = new byte[0x408];
        layoutData[0] = CELL_BLUE; // Cell (0, 0)
        layoutData[0x21] = CELL_RED; // Cell (1, 1)
        // Params: angle=0x40, xPos=0x0100, yPos=0x0200, spheresLeft=0x000A
        layoutData[0x400] = 0x00;
        layoutData[0x401] = 0x40;
        layoutData[0x402] = 0x01;
        layoutData[0x403] = 0x00;
        layoutData[0x404] = 0x02;
        layoutData[0x405] = 0x00;
        layoutData[0x406] = 0x00;
        layoutData[0x407] = 0x0A;

        int[] params = grid.loadFromLayoutData(layoutData);

        assertEquals(CELL_BLUE, grid.getCell(0, 0));
        assertEquals(CELL_RED, grid.getCell(1, 1));
        assertEquals(0x40, params[0], "angle");
        assertEquals(0x100, params[1], "xPos");
        assertEquals(0x200, params[2], "yPos");
        assertEquals(0x0A, params[3], "spheresLeft");
    }

    @Test
    void testOrAndCellByIndex() {
        grid.setCellByIndex(5, CELL_TOUCHED); // 0x0A
        grid.orCellByIndex(5, 0x80);
        assertEquals(0x8A, grid.getCellByIndex(5), "OR should set high bit");

        grid.andCellByIndex(5, 0x7F);
        assertEquals(0x0A, grid.getCellByIndex(5), "AND should clear high bit");
    }
}


