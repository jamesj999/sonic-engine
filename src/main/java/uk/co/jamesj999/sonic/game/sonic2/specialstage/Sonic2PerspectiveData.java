package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import java.io.IOException;
import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Parses and provides access to Special Stage object perspective data.
 *
 * The perspective data maps object depth and angle to screen coordinates.
 * It's stored at ROM offset 0x0E24FE (Kosinski compressed, 4080 bytes).
 *
 * Structure after decompression (6908 bytes total):
 * - 56 word offsets (112 bytes): one per track frame, pointing to per-frame blocks
 * - Per-frame blocks: word max_depth + array of 6-byte entries
 *
 * 6-byte entry format:
 * - Byte 0: x_base - Base X screen position
 * - Byte 1: y_base - Base Y screen position (signed, >= $48 is sign-extended)
 * - Byte 2: x_radius - X projection radius
 * - Byte 3: y_radius - Y projection radius
 * - Byte 4: angle_min - Minimum visible angle (for culling)
 * - Byte 5: angle_max - Maximum visible angle (0 = no culling)
 *
 * Screen position calculation:
 *   x_pos = x_base + (cos(angle) * x_radius) >> 8
 *   y_pos = y_base + (sin(angle) * y_radius) >> 8
 */
public class Sonic2PerspectiveData {
    private static final Logger LOGGER = Logger.getLogger(Sonic2PerspectiveData.class.getName());

    /** Number of track frames */
    private static final int FRAME_COUNT = 56;

    /** Size of each perspective entry */
    private static final int ENTRY_SIZE = 6;

    /** Raw decompressed perspective data */
    private byte[] data;

    /** Pre-parsed frame offsets for quick access */
    private int[] frameOffsets;

    /** Pre-parsed max depth counts per frame */
    private int[] maxDepthCounts;

    /** Cosine lookup table (256 entries, -128 to 127 fixed point) */
    private static final int[] COSINE_TABLE = new int[256];

    /** Sine lookup table (256 entries, -128 to 127 fixed point) */
    private static final int[] SINE_TABLE = new int[256];

    static {
        // Initialize sine/cosine tables matching Mega Drive CalcSine
        for (int i = 0; i < 256; i++) {
            double radians = (i / 256.0) * 2 * Math.PI;
            COSINE_TABLE[i] = (int) Math.round(Math.cos(radians) * 256);
            SINE_TABLE[i] = (int) Math.round(Math.sin(radians) * 256);
        }
    }

    /**
     * Loads perspective data from the data loader.
     */
    public void load(Sonic2SpecialStageDataLoader dataLoader) throws IOException {
        data = dataLoader.getPerspectiveData();

        if (data == null || data.length < FRAME_COUNT * 2) {
            throw new IOException("Invalid perspective data");
        }

        // Parse frame offsets
        frameOffsets = new int[FRAME_COUNT];
        maxDepthCounts = new int[FRAME_COUNT];

        for (int i = 0; i < FRAME_COUNT; i++) {
            int offset = ((data[i * 2] & 0xFF) << 8) | (data[i * 2 + 1] & 0xFF);
            frameOffsets[i] = offset;

            // Parse max depth count for this frame
            if (offset + 2 <= data.length) {
                maxDepthCounts[i] = ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
            }
        }

        LOGGER.fine("Loaded perspective data: " + data.length + " bytes, " + FRAME_COUNT + " frames");
    }

    /**
     * Gets a perspective entry for the given track frame and depth.
     *
     * @param trackFrame Track frame index (0-55)
     * @param depth Object depth value (objoff_30)
     * @return PerspectiveEntry or null if out of range
     */
    public PerspectiveEntry getEntry(int trackFrame, int depth) {
        if (data == null || trackFrame < 0 || trackFrame >= FRAME_COUNT) {
            return null;
        }

        // Depth index is depth - 1 (depth 0 means not visible)
        int depthIndex = depth - 1;
        if (depthIndex < 0 || depthIndex >= maxDepthCounts[trackFrame]) {
            return null;
        }

        // Calculate offset to the entry
        int frameOffset = frameOffsets[trackFrame];
        int entryOffset = frameOffset + 2 + (depthIndex * ENTRY_SIZE);

        if (entryOffset + ENTRY_SIZE > data.length) {
            return null;
        }

        return new PerspectiveEntry(
            data[entryOffset] & 0xFF,         // x_base
            data[entryOffset + 1],            // y_base (signed)
            data[entryOffset + 2] & 0xFF,     // x_radius
            data[entryOffset + 3] & 0xFF,     // y_radius
            data[entryOffset + 4] & 0xFF,     // angle_min
            data[entryOffset + 5] & 0xFF      // angle_max
        );
    }

    /**
     * Gets the maximum depth count for a track frame.
     */
    public int getMaxDepthCount(int trackFrame) {
        if (trackFrame < 0 || trackFrame >= FRAME_COUNT) {
            return 0;
        }
        return maxDepthCounts[trackFrame];
    }

    /**
     * Represents a single perspective data entry.
     */
    public static class PerspectiveEntry {
        public final int xBase;
        public final int yBase;  // Signed
        public final int xRadius;
        public final int yRadius;
        public final int angleMin;
        public final int angleMax;

        public PerspectiveEntry(int xBase, int yBase, int xRadius, int yRadius,
                               int angleMin, int angleMax) {
            this.xBase = xBase;
            // Sign-extend y_base if >= $48
            if (yBase >= 0x48 && yBase < 0x80) {
                this.yBase = yBase;
            } else if ((yBase & 0x80) != 0) {
                // Already negative in signed byte
                this.yBase = yBase - 256;
            } else {
                this.yBase = yBase;
            }
            this.xRadius = xRadius;
            this.yRadius = yRadius;
            this.angleMin = angleMin;
            this.angleMax = angleMax;
        }

        /**
         * Checks if an angle is visible for this depth entry.
         *
         * @param angle Object angle (0-255)
         * @param trackFlipped Whether the track is flipped
         * @return true if the angle is within visible range
         */
        public boolean isAngleVisible(int angle, boolean trackFlipped) {
            if (angleMax == 0) {
                return true; // No culling
            }

            if (trackFlipped) {
                // Invert angle range around $80
                int invertedAngle = (0x100 - angle) & 0xFF;
                return invertedAngle < angleMin || invertedAngle >= angleMax;
            } else {
                return angle < angleMin || angle >= angleMax;
            }
        }

        /**
         * Calculates screen position for an object at this depth and angle.
         *
         * The original game uses VDP coordinates where screen Y = VDP Y - 128.
         * The perspective data y_base values appear to be relative to a track center
         * point, and the original loc_351F8 adds +$80 (128) to convert to VDP Y.
         * Since we use screen coordinates directly, we add 128 to match the original
         * VDP output, then rendering will produce the correct visual position.
         *
         * @param angle Object angle (0-255)
         * @param trackFlipped Whether the track is flipped
         * @return int array [x, y]
         */
        public int[] calculateScreenPosition(int angle, boolean trackFlipped) {
            // Get sine/cosine values
            int cos = COSINE_TABLE[angle & 0xFF];
            int sin = SINE_TABLE[angle & 0xFF];

            // Calculate position offsets
            int xOffset = (cos * xRadius) >> 8;
            int yOffset = (sin * yRadius) >> 8;

            // Calculate base X (flipped if track is flipped)
            int effectiveXBase = xBase;
            if (trackFlipped) {
                // Negate relative to $100 (256 / 2 = 128 center)
                effectiveXBase = 0x100 - xBase;
            }

            // Final screen position
            int x = effectiveXBase + xOffset;
            int y = yBase + yOffset;

            return new int[] { x, y };
        }
    }
}
