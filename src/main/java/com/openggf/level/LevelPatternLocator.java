package com.openggf.level;

final class LevelPatternLocator {

    private LevelPatternLocator() {
    }

    static int[] findPatternOffset(Level level, int blockPixelSize,
                                   int refX, int refY,
                                   int minTileIdx, int maxTileIdx,
                                   int searchRadius) {
        if (level == null) {
            return null;
        }

        Map map = level.getMap();
        if (map == null) {
            return null;
        }

        int startX = Math.max(refX - searchRadius, level.getMinX());
        int startY = Math.max(refY - searchRadius, level.getMinY());
        int endX = Math.min(refX + searchRadius, level.getMaxX());
        int endY = Math.min(refY + searchRadius, level.getMaxY());

        for (int worldY = startY; worldY < endY; worldY += 8) {
            for (int worldX = startX; worldX < endX; worldX += 8) {
                int tileIdx = getPatternIndexAt(level, blockPixelSize, map, worldX, worldY);
                if (tileIdx >= minTileIdx && tileIdx <= maxTileIdx) {
                    int patternLeftX = worldX - Math.floorMod(worldX, 8);
                    int patternTopY = worldY - Math.floorMod(worldY, 8);
                    return new int[]{
                            patternLeftX + 4 - refX,
                            patternTopY + 4 - refY
                    };
                }
            }
        }

        return null;
    }

    private static int getPatternIndexAt(Level level, int blockPixelSize,
                                         Map map, int worldX, int worldY) {
        try {
            int blockX = worldX / blockPixelSize;
            int blockY = worldY / blockPixelSize;
            if (blockX < 0 || blockX >= map.getWidth()
                    || blockY < 0 || blockY >= map.getHeight()) {
                return -1;
            }

            int blockIdx = map.getValue(0, blockX, blockY) & 0xFF;
            if (blockIdx == 0 || blockIdx >= level.getBlockCount()) {
                return -1;
            }

            Block block = level.getBlock(blockIdx);
            if (block == null) {
                return -1;
            }

            int chunkX = (worldX % blockPixelSize) / Chunk.CHUNK_WIDTH;
            int chunkY = (worldY % blockPixelSize) / Chunk.CHUNK_HEIGHT;
            ChunkDesc chunkDesc = block.getChunkDesc(chunkX, chunkY);
            if (chunkDesc == null) {
                return -1;
            }

            int chunkIdx = chunkDesc.getChunkIndex();
            if (chunkIdx == 0 || chunkIdx >= level.getChunkCount()) {
                return -1;
            }

            Chunk chunk = level.getChunk(chunkIdx);
            if (chunk == null) {
                return -1;
            }

            int patternX = (worldX % Chunk.CHUNK_WIDTH) / 8;
            int patternY = (worldY % Chunk.CHUNK_HEIGHT) / 8;
            PatternDesc patternDesc = chunk.getPatternDesc(patternX, patternY);
            return patternDesc == null ? -1 : patternDesc.getPatternIndex();
        } catch (Exception e) {
            return -1;
        }
    }
}
