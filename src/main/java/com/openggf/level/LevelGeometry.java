package com.openggf.level;

/**
 * Immutable snapshot of level geometry dimensions, shared between
 * LevelManager and LevelTilemapManager to avoid back-references.
 */
public record LevelGeometry(
    Level level,
    int fgWidthPx, int fgHeightPx,
    int bgWidthPx, int bgContiguousWidthPx, int bgHeightPx,
    int blockPixelSize, int chunksPerBlockSide
) {

    /**
     * Derives the geometry a {@code LevelManager} would cache for {@code level}
     * on load. Used to build tilemaps for a level before it is installed.
     */
    public static LevelGeometry forLevel(Level level) {
        int blockPixelSize = level.getBlockPixelSize();
        int fgWidthPx = Math.max(1, level.getLayerWidthBlocks(0)) * blockPixelSize;
        int fgHeightPx = Math.max(1, level.getLayerHeightBlocks(0)) * blockPixelSize;
        int bgWidthPx = Math.max(1, level.getLayerWidthBlocks(1)) * blockPixelSize;
        int bgHeightPx = Math.max(1, level.getLayerHeightBlocks(1)) * blockPixelSize;
        return new LevelGeometry(level, fgWidthPx, fgHeightPx,
                bgWidthPx, contiguousBgDataWidthPx(level, blockPixelSize), bgHeightPx,
                blockPixelSize, level.getChunksPerBlockSide());
    }

    /**
     * Scans the BG layer left-to-right for the first all-zero column. On the
     * Mega Drive the BG nametable is a 512px ring the scroll handler fills from
     * the BG map, wrapping at the map's data width; the Map stores FG and BG at
     * the same total width, but BG data typically spans only a contiguous
     * region from column 0. Stray non-zero blocks at distant columns are ignored.
     */
    public static int contiguousBgDataWidthPx(Level level, int blockPixelSize) {
        if (level == null || level.getMap() == null) {
            return blockPixelSize;
        }
        Map map = level.getMap();
        int mapWidth = map.getWidth();
        int mapHeight = map.getHeight();
        int contiguousWidth = 0;
        for (int col = 0; col < mapWidth; col++) {
            boolean hasData = false;
            for (int row = 0; row < mapHeight; row++) {
                if ((map.getValue(1, col, row) & 0xFF) != 0) {
                    hasData = true;
                    break;
                }
            }
            if (hasData) {
                contiguousWidth = col + 1;
            } else {
                break;
            }
        }
        if (contiguousWidth == 0) {
            // No BG data at all: use the full map width.
            return mapWidth * blockPixelSize;
        }
        return contiguousWidth * blockPixelSize;
    }
}
