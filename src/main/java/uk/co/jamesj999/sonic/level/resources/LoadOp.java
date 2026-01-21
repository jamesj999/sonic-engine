package uk.co.jamesj999.sonic.level.resources;

/**
 * Describes a single load operation for level resources.
 * Multiple LoadOps can be combined to create composite resources
 * (e.g., base + overlay pattern).
 *
 * @param romAddr        Absolute ROM address to load from
 * @param compressionType Type of compression used (KOSINSKI, NEMESIS, or UNCOMPRESSED)
 * @param destOffsetBytes Byte offset in the destination buffer where this data should be written.
 *                        Use 0 for base loads; use specific offsets for overlays.
 */
public record LoadOp(int romAddr, CompressionType compressionType, int destOffsetBytes) {

    /**
     * Creates a load operation with offset 0 (base load).
     */
    public static LoadOp base(int romAddr, CompressionType compressionType) {
        return new LoadOp(romAddr, compressionType, 0);
    }

    /**
     * Creates an overlay load operation at the specified byte offset.
     */
    public static LoadOp overlay(int romAddr, CompressionType compressionType, int destOffsetBytes) {
        return new LoadOp(romAddr, compressionType, destOffsetBytes);
    }

    /**
     * Creates a Kosinski-compressed base load operation.
     */
    public static LoadOp kosinskiBase(int romAddr) {
        return base(romAddr, CompressionType.KOSINSKI);
    }

    /**
     * Creates a Kosinski-compressed overlay load operation.
     */
    public static LoadOp kosinskiOverlay(int romAddr, int destOffsetBytes) {
        return overlay(romAddr, CompressionType.KOSINSKI, destOffsetBytes);
    }

    /**
     * Creates an uncompressed base load operation.
     */
    public static LoadOp uncompressedBase(int romAddr) {
        return base(romAddr, CompressionType.UNCOMPRESSED);
    }
}
