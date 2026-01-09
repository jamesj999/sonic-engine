package uk.co.jamesj999.sonic.physics;

/**
 * Pre-computed lookup tables for trigonometric functions.
 * Uses 256-step angles (byte-based, matching Mega Drive hardware).
 * 
 * The original Sonic games use a 256-step angle system where:
 * - 0x00 = 0° (right)
 * - 0x40 = 90° (down)
 * - 0x80 = 180° (left)
 * - 0xC0 = 270° (up)
 */
public final class TrigLookupTable {

    private static final int TABLE_SIZE = 256;
    private static final double[] SIN_TABLE = new double[TABLE_SIZE];
    private static final double[] COS_TABLE = new double[TABLE_SIZE];

    // Also provide degree-based tables for compatibility with existing code
    private static final double[] SIN_DEG_TABLE = new double[360];
    private static final double[] COS_DEG_TABLE = new double[360];

    static {
        // Initialize 256-step tables (native Mega Drive format)
        for (int i = 0; i < TABLE_SIZE; i++) {
            double radians = i * (2.0 * Math.PI / TABLE_SIZE);
            SIN_TABLE[i] = Math.sin(radians);
            COS_TABLE[i] = Math.cos(radians);
        }

        // Initialize degree-based tables for compatibility
        for (int i = 0; i < 360; i++) {
            double radians = Math.toRadians(i);
            SIN_DEG_TABLE[i] = Math.sin(radians);
            COS_DEG_TABLE[i] = Math.cos(radians);
        }
    }

    private TrigLookupTable() {
        // Prevent instantiation
    }

    /**
     * Get sine value for a byte angle (0-255, wraps automatically).
     * 
     * @param byteAngle The angle in 256-step format
     * @return The sine value
     */
    public static double sin(int byteAngle) {
        return SIN_TABLE[byteAngle & 0xFF];
    }

    /**
     * Get cosine value for a byte angle (0-255, wraps automatically).
     * 
     * @param byteAngle The angle in 256-step format
     * @return The cosine value
     */
    public static double cos(int byteAngle) {
        return COS_TABLE[byteAngle & 0xFF];
    }

    /**
     * Get sine value for a degree angle (0-359, wraps to valid range).
     * 
     * @param degrees The angle in degrees
     * @return The sine value
     */
    public static double sinDeg(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return SIN_DEG_TABLE[normalized];
    }

    /**
     * Get cosine value for a degree angle (0-359, wraps to valid range).
     * 
     * @param degrees The angle in degrees
     * @return The cosine value
     */
    public static double cosDeg(int degrees) {
        int normalized = ((degrees % 360) + 360) % 360;
        return COS_DEG_TABLE[normalized];
    }

    /**
     * Convert byte angle to degrees (for debugging/display).
     * 
     * @param byteAngle The angle in 256-step format
     * @return The equivalent angle in degrees
     */
    public static double toDegrees(int byteAngle) {
        return (byteAngle & 0xFF) * (360.0 / 256.0);
    }

    /**
     * Convert degrees to byte angle.
     * 
     * @param degrees The angle in degrees
     * @return The equivalent angle in 256-step format
     */
    public static int toByteAngle(double degrees) {
        return (int) Math.round(degrees * (256.0 / 360.0)) & 0xFF;
    }
}
