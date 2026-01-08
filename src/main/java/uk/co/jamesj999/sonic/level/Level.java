package uk.co.jamesj999.sonic.level;

import java.util.List;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.rings.RingSpawn;
import uk.co.jamesj999.sonic.level.rings.RingSpriteSheet;

/**
 * Abstract class representing a Level, containing palettes, patterns, chunks, and blocks.
 */
public interface Level {

    // Destructor equivalent is not needed, as Java has automatic garbage collection.

    // Abstract method to get the number of palettes
    public int getPaletteCount();

    // Abstract method to get a specific palette by index
    public Palette getPalette(int index);

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
}
