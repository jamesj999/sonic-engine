package com.openggf.level;

import java.util.List;

import com.openggf.level.rings.RingSpawn;
import com.openggf.level.rings.RingSpriteSheet;
import com.openggf.level.objects.ObjectSpawn;

/**
 * Abstract class representing a Level, containing palettes, patterns, chunks, and blocks.
 */
@com.openggf.game.ModApi
public interface Level {

    // Destructor equivalent is not needed, as Java has automatic garbage collection.

    // Abstract method to get the number of palettes
    public int getPaletteCount();

    // Abstract method to get a specific palette by index
    public Palette getPalette(int index);

    /**
     * Updates a specific palette at the given index.
     * Used for boss palettes and other dynamic palette changes.
     * Default implementation is a no-op for immutable level implementations.
     *
     * @param index   The palette index (0-3)
     * @param palette The new palette to set
     */
    default void setPalette(int index, Palette palette) {
        // Default no-op for immutable implementations
    }

    // Abstract method to get the number of patterns
    public int getPatternCount();

    // Abstract method to get a specific pattern by index
    public Pattern getPattern(int index);

    /**
     * Ensure the pattern buffer can address at least {@code minCount} tiles.
     * Default implementation is a no-op for immutable level implementations.
     */
    default void ensurePatternCapacity(int minCount) {
    }

    // Abstract method to get the number of chunks
    public int getChunkCount();

    // Abstract method to get a specific chunk by index
    public Chunk getChunk(int index);

    // Abstract method to get the number of blocks
    public int getBlockCount();

    // Abstract method to get a specific block by index
    public Block getBlock(int index);

    /** Returns the number of solid (collision) tiles. Default returns 0 for stubs. */
    default int getSolidTileCount() { return 0; }

    SolidTile getSolidTile(int index);

    // Abstract method to get the map associated with the level
    public Map getMap();

    public List<ObjectSpawn> getObjects();

    public List<RingSpawn> getRings();

    public RingSpriteSheet getRingSpriteSheet();

    int getMinX();
    int getMaxX();
    int getMinY();
    int getMaxY();

    /**
     * Returns the ROM zone index for this level.
     * This is used by animated pattern/palette managers to look up zone-specific data.
     */
    int getZoneIndex();

    /** Block size in pixels (128 for Sonic 2, 256 for Sonic 1). */
    default int getBlockPixelSize() { return 128; }

    /** Number of 16x16 chunks along one side of a block (8 for Sonic 2, 16 for Sonic 1). */
    default int getChunksPerBlockSide() { return 8; }

    /**
     * Returns layout width in 128x128 blocks for the given layer.
     * Default implementation uses the shared map dimensions.
     */
    default int getLayerWidthBlocks(int layer) {
        Map map = getMap();
        return map != null ? map.getWidth() : 0;
    }

    /**
     * Returns layout height in 128x128 blocks for the given layer.
     * Default implementation uses the shared map dimensions.
     */
    default int getLayerHeightBlocks(int layer) {
        Map map = getMap();
        return map != null ? map.getHeight() : 0;
    }

    /**
     * Whether a background-collision probe at the supplied 16-bit BG-space Y
     * coordinate has a real layout row. Most games wrap their decoded layer;
     * S3K overrides this for its interleaved row-pointer table, where a zero
     * pointer is an absent row rather than a request to wrap through the
     * declared visual background height.
     */
    default boolean hasBackgroundCollisionRowAt(int y) {
        return true;
    }

    /**
     * Returns the VDP backdrop color for this level.
     * VDP register 7 determines which palette line/color is the backdrop.
     * S2/S1 default: $8720 = palette line 2, color 0.
     */
    default Palette.Color getBackdropColor() {
        if (getPaletteCount() > getBackdropPaletteLine()) {
            return getPalette(getBackdropPaletteLine()).getColor(0);
        }
        return new Palette.Color((byte) 0, (byte) 0, (byte) 0);
    }

    /**
     * CRAM line the VDP backdrop register names ({@code $8720}: line 2, colour 0).
     * The backdrop is an ordinary CRAM entry, so a palette fade that covers this
     * line fades it too.
     */
    default int getBackdropPaletteLine() {
        return 2;
    }

    /**
     * Resolves the collision block index for a given map cell.
     * In Sonic 1, loop-flagged blocks use the next block (index + 1) for
     * collision when the player is on the "low plane."
     * Default implementation returns the block index unchanged.
     *
     * @param blockIndex the raw block index from the layout map
     * @param mapX       the map cell X coordinate
     * @param mapY       the map cell Y coordinate
     * @return the resolved block index for collision lookup
     */
    default int resolveCollisionBlockIndex(int blockIndex, int mapX, int mapY) {
        return blockIndex;
    }
}
