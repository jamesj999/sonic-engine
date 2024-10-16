package uk.co.jamesj999.sonic.level;

public class LevelConstants {

    public static final int BLOCK_HEIGHT = 128;
    public static final int BLOCK_WIDTH = 128;
    public static final int BYTES_PER_CHUNK = 2;
    public static final int CHUNKS_PER_BLOCK = 64;
    public static final int BLOCK_SIZE_IN_ROM = CHUNKS_PER_BLOCK * BYTES_PER_CHUNK;

    public static final int CHUNK_HEIGHT = 16;
    public static final int CHUNK_WIDTH = 16;
    public static final int PATTERNS_PER_CHUNK = 4;
    public static final int BYTES_PER_PATTERN = 2;
    public static final int CHUNK_SIZE_IN_ROM = PATTERNS_PER_CHUNK * BYTES_PER_PATTERN;

    public static final int COLORS_PER_PALETTE = 16; //1 is transparent
    public static final int MAX_PALETTES = 4;
}
