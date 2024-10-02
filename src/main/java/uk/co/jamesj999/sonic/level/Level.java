package uk.co.jamesj999.sonic.level;

/**
 * Abstract class representing a Level, containing palettes, patterns, chunks, and blocks.
 */
public abstract class Level {

    // Destructor equivalent is not needed, as Java has automatic garbage collection.

    // Abstract method to get the number of palettes
    public abstract int getPaletteCount();

    // Abstract method to get a specific palette by index
    public abstract Palette getPalette(int index);

    // Abstract method to get the number of patterns
    public abstract int getPatternCount();

    // Abstract method to get a specific pattern by index
    public abstract Pattern getPattern(int index);

    // Abstract method to get the number of chunks
    public abstract int getChunkCount();

    // Abstract method to get a specific chunk by index
    public abstract Chunk getChunk(int index);

    // Abstract method to get the number of blocks
    public abstract int getBlockCount();

    // Abstract method to get a specific block by index
    public abstract Block getBlock(int index);

    // Abstract method to get the map associated with the level
    public abstract Map getMap();

    //TODO refactor out of level?
    public abstract SolidTile getSolidTile(int index);

    public abstract int getSolidTileCount();
}
