package uk.co.jamesj999.sonic.level.resources;

import uk.co.jamesj999.sonic.data.Rom;
import uk.co.jamesj999.sonic.tools.KosinskiReader;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Logger;

/**
 * Loads level resources from ROM with support for overlay composition.
 *
 * <p>Overlay loading works by:
 * <ol>
 *   <li>Allocating a destination buffer large enough for all operations</li>
 *   <li>Decompressing/copying each LoadOp's data into the buffer at its destOffset</li>
 *   <li>Operations are applied in order, so overlays overwrite base data</li>
 * </ol>
 *
 * <p>This class does NOT cache results. Callers should cache the returned
 * byte arrays if reuse is needed. This ensures that loading HTZ doesn't
 * accidentally mutate cached EHZ data.
 *
 * <p>Example:
 * <pre>{@code
 * ResourceLoader loader = new ResourceLoader(rom);
 *
 * // Load HTZ patterns with overlay
 * List<LoadOp> patternOps = htzPlan.getPatternOps();
 * byte[] patterns = loader.loadWithOverlays(patternOps, 0x10000);
 * }</pre>
 */
public class ResourceLoader {

    private static final Logger LOG = Logger.getLogger(ResourceLoader.class.getName());
    private static final boolean KOS_DEBUG_LOG = false;

    private final Rom rom;

    public ResourceLoader(Rom rom) {
        this.rom = rom;
    }

    /**
     * Loads and composes data from multiple LoadOps into a single buffer.
     *
     * <p>The final buffer size is the maximum of all operations (base + overlays),
     * ensuring all data is included. For proper alignment (e.g., for 128-byte blocks),
     * callers should either use aligned base data or handle alignment themselves.
     *
     * @param ops            List of load operations to apply in order.
     *                       The first op should be the base (destOffset=0).
     * @param initialBufferSize Initial buffer size hint.
     * @return The composed byte array with all operations applied
     * @throws IOException if decompression or ROM reading fails
     */
    public byte[] loadWithOverlays(List<LoadOp> ops, int initialBufferSize) throws IOException {
        if (ops == null || ops.isEmpty()) {
            throw new IllegalArgumentException("At least one LoadOp is required");
        }

        // Start with the initial buffer size
        byte[] buffer = new byte[initialBufferSize];
        int usedLength = 0;

        for (LoadOp op : ops) {
            byte[] decompressed = decompress(op);
            int destOffset = op.destOffsetBytes();
            int requiredSize = destOffset + decompressed.length;

            // Expand buffer if needed to accommodate this operation
            if (requiredSize > buffer.length) {
                buffer = Arrays.copyOf(buffer, requiredSize);
            }

            // Copy decompressed data into buffer at destOffset
            System.arraycopy(decompressed, 0, buffer, destOffset, decompressed.length);

            // Track the maximum extent of data
            usedLength = Math.max(usedLength, requiredSize);

            if (op.destOffsetBytes() > 0) {
                LOG.fine(String.format("Applied overlay: ROM 0x%06X -> offset 0x%04X (%d bytes)",
                        op.romAddr(), op.destOffsetBytes(), decompressed.length));
            } else {
                LOG.fine(String.format("Loaded base: ROM 0x%06X (%d bytes)",
                        op.romAddr(), decompressed.length));
            }
        }

        // Trim buffer to actual used size
        if (usedLength < buffer.length) {
            buffer = Arrays.copyOf(buffer, usedLength);
        }

        return buffer;
    }

    /**
     * Loads and composes data from multiple LoadOps, with alignment enforcement.
     *
     * <p>Similar to {@link #loadWithOverlays(List, int)}, but the final buffer size
     * is rounded UP to the nearest multiple of the specified alignment.
     *
     * @param ops            List of load operations to apply in order.
     * @param initialBufferSize Initial buffer size hint.
     * @param alignment      Required alignment in bytes (e.g., 128 for blocks).
     * @return The composed byte array, sized to a multiple of alignment
     * @throws IOException if decompression or ROM reading fails
     */
    public byte[] loadWithOverlaysAligned(List<LoadOp> ops, int initialBufferSize, int alignment) throws IOException {
        byte[] buffer = loadWithOverlays(ops, initialBufferSize);

        // Round up to alignment boundary
        int remainder = buffer.length % alignment;
        if (remainder != 0) {
            int alignedSize = buffer.length + (alignment - remainder);
            buffer = Arrays.copyOf(buffer, alignedSize);
        }

        return buffer;
    }

    /**
     * Loads a single LoadOp without overlay composition.
     * Equivalent to loadWithOverlays with a single-element list.
     */
    public byte[] loadSingle(LoadOp op) throws IOException {
        return decompress(op);
    }

    /**
     * Decompresses data from ROM based on the compression type.
     */
    private byte[] decompress(LoadOp op) throws IOException {
        return switch (op.compressionType()) {
            case KOSINSKI -> decompressKosinski(op.romAddr());
            case NEMESIS -> decompressNemesis(op.romAddr());
            case UNCOMPRESSED -> throw new UnsupportedOperationException(
                    "Uncompressed loading requires a size parameter. Use loadUncompressed() instead.");
        };
    }

    /**
     * Decompresses Kosinski-compressed data from the specified ROM address.
     */
    private byte[] decompressKosinski(int romAddr) throws IOException {
        FileChannel channel = rom.getFileChannel();
        channel.position(romAddr);
        return KosinskiReader.decompress(channel, KOS_DEBUG_LOG);
    }

    /**
     * Decompresses Nemesis-compressed data from the specified ROM address.
     * Currently throws UnsupportedOperationException as Nemesis is not used for level data.
     */
    private byte[] decompressNemesis(int romAddr) throws IOException {
        throw new UnsupportedOperationException(
                "Nemesis decompression not yet implemented in ResourceLoader");
    }

    /**
     * Loads uncompressed data from ROM.
     *
     * @param romAddr ROM address to read from
     * @param size    Number of bytes to read
     * @return The raw bytes from ROM
     * @throws IOException if reading fails
     */
    public byte[] loadUncompressed(int romAddr, int size) throws IOException {
        return rom.readBytes(romAddr, size);
    }
}
