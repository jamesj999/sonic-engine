package com.openggf.level;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class TestLevelPatternLocator {

    @Test
    void returnsOffsetToMatchingPatternCenter() {
        LocatorLevel level = new LocatorLevel();
        level.setPattern(24, 32, 0x345);

        assertArrayEquals(new int[]{-4, 4},
                LevelPatternLocator.findPatternOffset(level, 128, 32, 32, 0x340, 0x34F, 16));
    }

    @Test
    void searchesRowsBeforeLaterColumns() {
        LocatorLevel level = new LocatorLevel();
        level.setPattern(24, 16, 0x345);
        level.setPattern(8, 24, 0x345);

        assertArrayEquals(new int[]{-12, -20},
                LevelPatternLocator.findPatternOffset(level, 128, 40, 40, 0x345, 0x345, 32));
    }

    @Test
    void snapsUnalignedProbeCoordinatesToPatternCenter() {
        LocatorLevel level = new LocatorLevel();
        level.setPattern(16, 16, 0x345);

        assertArrayEquals(new int[]{-1, 1},
                LevelPatternLocator.findPatternOffset(level, 128, 21, 19, 0x345, 0x345, 10));
    }

    @Test
    void returnsNullWhenNoPatternMatches() {
        LocatorLevel level = new LocatorLevel();
        level.setPattern(16, 16, 0x344);

        assertNull(LevelPatternLocator.findPatternOffset(level, 128, 21, 19, 0x345, 0x345, 10));
        assertNull(LevelPatternLocator.findPatternOffset(null, 128, 21, 19, 0x345, 0x345, 10));
    }

    private static final class LocatorLevel extends AbstractLevel {
        private static final int GRID_SIDE = 8;

        private LocatorLevel() {
            super(0);
            map = new Map(1, 1, 1);
            map.setValue(0, 0, 0, (byte) 1);
            minX = 0;
            minY = 0;
            maxX = 128;
            maxY = 128;

            blocks = new Block[2];
            blocks[1] = new Block(GRID_SIDE);
            blockCount = blocks.length;

            chunks = new Chunk[1 + GRID_SIDE * GRID_SIDE];
            for (int chunkY = 0; chunkY < GRID_SIDE; chunkY++) {
                for (int chunkX = 0; chunkX < GRID_SIDE; chunkX++) {
                    int chunkIndex = 1 + chunkY * GRID_SIDE + chunkX;
                    chunks[chunkIndex] = new Chunk();
                    blocks[1].setChunkDesc(chunkX, chunkY, new ChunkDesc(chunkIndex));
                }
            }
            chunkCount = chunks.length;
        }

        private void setPattern(int worldX, int worldY, int patternIndex) {
            int chunkX = worldX / Chunk.CHUNK_WIDTH;
            int chunkY = worldY / Chunk.CHUNK_HEIGHT;
            int chunkIndex = blocks[1].getChunkDesc(chunkX, chunkY).getChunkIndex();
            chunks[chunkIndex].setPatternDesc(
                    (worldX % Chunk.CHUNK_WIDTH) / 8,
                    (worldY % Chunk.CHUNK_HEIGHT) / 8,
                    new PatternDesc(patternIndex));
        }
    }
}
