package com.openggf.game.sonic1.specialstage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.data.Rom;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static com.openggf.game.sonic1.constants.Sonic1Constants.SS_BLOCKBUFFER_OFFSET;
import static com.openggf.game.sonic1.constants.Sonic1Constants.SS_BLOCKBUFFER_ROWS;
import static com.openggf.game.sonic1.constants.Sonic1Constants.SS_LAYOUT_COLS;
import static com.openggf.game.sonic1.constants.Sonic1Constants.SS_LAYOUT_STRIDE;
import static com.openggf.game.sonic1.constants.Sonic1Constants.SS_STAGE_COUNT;

@RequiresRom(SonicGame.SONIC_1)
public class Sonic1SpecialStageDataLoaderTest {
    private Sonic1SpecialStageDataLoader loader;

    @BeforeEach
    public void setUp() {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        loader = new Sonic1SpecialStageDataLoader(rom);
    }

    @Test
    public void testStageLayoutsHaveValidBlockContent() throws IOException {
        for (int stageIndex = 0; stageIndex < SS_STAGE_COUNT; stageIndex++) {
            byte[] layout = loader.getStageLayout(stageIndex);
            assertNotNull(layout, "Layout should not be null for stage " + (stageIndex + 1));
            assertTrue(layout.length > 0, "Layout should not be empty for stage " + (stageIndex + 1));
            assertTrue(layout.length % SS_LAYOUT_STRIDE == 0, "Layout should be stride-aligned for stage " + (stageIndex + 1));

            int totalCells = SS_BLOCKBUFFER_ROWS * SS_LAYOUT_COLS;
            int validCount = 0;
            int nonZeroCount = 0;
            int wallCount = 0;
            int interactiveCount = 0;

            for (int row = 0; row < SS_BLOCKBUFFER_ROWS; row++) {
                int rowOff = SS_BLOCKBUFFER_OFFSET + row * SS_LAYOUT_STRIDE;
                for (int col = 0; col < SS_LAYOUT_COLS; col++) {
                    int blockId = layout[rowOff + col] & 0xFF;
                    if (blockId <= Sonic1SpecialStageBlockType.MAX_BLOCK_ID) {
                        validCount++;
                    }
                    if (blockId != 0) {
                        nonZeroCount++;
                    }
                    if (blockId >= 0x01 && blockId <= 0x24) {
                        wallCount++;
                    }
                    if (blockId >= 0x25 && blockId <= Sonic1SpecialStageBlockType.MAX_BLOCK_ID) {
                        interactiveCount++;
                    }
                }
            }

            double validRatio = validCount / (double) totalCells;
            double nonZeroRatio = nonZeroCount / (double) totalCells;

            assertTrue(validRatio >= 0.75, "Stage " + (stageIndex + 1) + " should have mostly valid block IDs, ratio=" + validRatio);
            assertTrue(nonZeroRatio >= 0.05, "Stage " + (stageIndex + 1) + " should have non-zero block content, ratio=" + nonZeroRatio);
            assertTrue(wallCount > 0, "Stage " + (stageIndex + 1) + " should contain at least one wall block");
            assertTrue(interactiveCount > 0, "Stage " + (stageIndex + 1) + " should contain interactive blocks");
        }
    }

    @Test
    public void testStageStartPositionsAreNearNonEmptyCells() throws IOException {
        for (int stageIndex = 0; stageIndex < SS_STAGE_COUNT; stageIndex++) {
            byte[] layout = loader.getStageLayout(stageIndex);
            int[] start = loader.getStartPosition(stageIndex);

            int gridCol = (start[0] + 0x20) / 0x18;
            int gridRow = (start[1] + 0x50) / 0x18;

            int nonZeroNearby = 0;
            int samples = 0;
            for (int dr = -3; dr <= 3; dr++) {
                for (int dc = -3; dc <= 3; dc++) {
                    int row = gridRow + dr;
                    int col = gridCol + dc;
                    if (row < 0 || col < 0 || col >= SS_LAYOUT_STRIDE) {
                        continue;
                    }
                    int idx = row * SS_LAYOUT_STRIDE + col;
                    if (idx < 0 || idx >= layout.length) {
                        continue;
                    }
                    samples++;
                    if ((layout[idx] & 0xFF) != 0) {
                        nonZeroNearby++;
                    }
                }
            }

            assertTrue(samples > 0 && nonZeroNearby > 0, "Stage " + (stageIndex + 1) + " start should be near non-empty layout cells");
        }
    }

    @Test
    public void testSpecialStageBackgroundPlanesAreBuiltFromSsBgLoadLogic() throws IOException {
        byte[] bgPlane5 = loader.getBgPlane5Tilemap();
        byte[] bgPlane6 = loader.getBgPlane6Tilemap();
        assertNotNull(bgPlane5);
        assertNotNull(bgPlane6);
        assertEquals(64 * 64 * 2, bgPlane5.length, "BG plane 5 should be 64x64 words");
        assertEquals(64 * 64 * 2, bgPlane6.length, "BG plane 6 should be 64x64 words");
        assertTrue(countNonZeroWords(bgPlane5) > 0, "BG plane 5 should contain non-zero mappings");
        assertTrue(countNonZeroWords(bgPlane6) > 0, "BG plane 6 should contain non-zero mappings");
        assertTrue(!Arrays.equals(bgPlane5, bgPlane6), "BG planes 5 and 6 should differ");

        for (int plane = 1; plane <= 4; plane++) {
            byte[] fgPlane = loader.getFgPlaneTilemap(plane);
            assertNotNull(fgPlane, "FG plane should load for plane " + plane);
            assertEquals(64 * 64 * 2, fgPlane.length, "FG plane should be 64x64 words for plane " + plane);
            assertTrue(countNonZeroWords(fgPlane) > 0, "FG plane should contain non-zero mappings for plane " + plane);
        }
    }

    private int countNonZeroWords(byte[] tilemap) {
        int nonZero = 0;
        for (int i = 0; i + 1 < tilemap.length; i += 2) {
            int word = ((tilemap[i] & 0xFF) << 8) | (tilemap[i + 1] & 0xFF);
            if (word != 0) {
                nonZero++;
            }
        }
        return nonZero;
    }
}


