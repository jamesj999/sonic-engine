package uk.co.jamesj999.sonic.level.resources;

/**
 * Compression types used in Sonic 2 ROM data.
 */
public enum CompressionType {
    /**
     * Kosinski compression - used for level tiles, blocks, chunks, layouts.
     */
    KOSINSKI,

    /**
     * Nemesis compression - used for sprite art.
     */
    NEMESIS,

    /**
     * Uncompressed raw data.
     */
    UNCOMPRESSED
}
