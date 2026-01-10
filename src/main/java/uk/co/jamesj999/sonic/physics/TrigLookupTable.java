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

    /**
     * Original Sonic engine SINCOSLIST from SPG:Calculations.
     * Returns integer values from -256 to 256 for precision.
     * Divide result by 256 to get typical -1 to 1 decimal result.
     * Index 0 = sin(0), Index 64 = sin(90°), etc.
     */
    private static final short[] SINCOSLIST = {
            // 0-63: 0° to ~90°
            0, 6, 12, 18, 25, 31, 37, 43, 49, 56, 62, 68, 74, 80, 86, 92,
            97, 103, 109, 115, 120, 126, 131, 136, 142, 147, 152, 157, 162, 167, 171, 176,
            181, 185, 189, 193, 197, 201, 205, 209, 212, 216, 219, 222, 225, 228, 231, 234,
            236, 238, 241, 243, 244, 246, 248, 249, 251, 252, 253, 254, 254, 255, 255, 255,
            // 64-127: ~90° to ~180°
            256, 255, 255, 255, 254, 254, 253, 252, 251, 249, 248, 246, 244, 243, 241, 238,
            236, 234, 231, 228, 225, 222, 219, 216, 212, 209, 205, 201, 197, 193, 189, 185,
            181, 176, 171, 167, 162, 157, 152, 147, 142, 136, 131, 126, 120, 115, 109, 103,
            97, 92, 86, 80, 74, 68, 62, 56, 49, 43, 37, 31, 25, 18, 12, 6,
            // 128-191: ~180° to ~270°
            0, -6, -12, -18, -25, -31, -37, -43, -49, -56, -62, -68, -74, -80, -86, -92,
            -97, -103, -109, -115, -120, -126, -131, -136, -142, -147, -152, -157, -162, -167, -171, -176,
            -181, -185, -189, -193, -197, -201, -205, -209, -212, -216, -219, -222, -225, -228, -231, -234,
            -236, -238, -241, -243, -244, -246, -248, -249, -251, -252, -253, -254, -254, -255, -255, -255,
            // 192-255: ~270° to ~360°
            -256, -255, -255, -255, -254, -254, -253, -252, -251, -249, -248, -246, -244, -243, -241, -238,
            -236, -234, -231, -228, -225, -222, -219, -216, -212, -209, -205, -201, -197, -193, -189, -185,
            -181, -176, -171, -167, -162, -157, -152, -147, -142, -136, -131, -126, -120, -115, -109, -103,
            -97, -92, -86, -80, -74, -68, -62, -56, -49, -43, -37, -31, -25, -18, -12, -6
    };

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

    /**
     * Get sine value using original Mega Drive SINCOSLIST.
     * Returns an integer from -256 to 256 for precision.
     * Divide by 256.0 to get a -1 to 1 result.
     * 
     * SPG: These are the exact values from the original game.
     * 
     * @param hexAngle The angle in 256-step hex format (0x00-0xFF)
     * @return Integer sine value from -256 to 256
     */
    public static int sinHex(int hexAngle) {
        return SINCOSLIST[hexAngle & 0xFF];
    }

    /**
     * Get cosine value using original Mega Drive SINCOSLIST.
     * Returns an integer from -256 to 256 for precision.
     * Divide by 256.0 to get a -1 to 1 result.
     * 
     * SPG: cos(angle) = sin(angle + 64) due to 256-step circle.
     * 
     * @param hexAngle The angle in 256-step hex format (0x00-0xFF)
     * @return Integer cosine value from -256 to 256
     */
    public static int cosHex(int hexAngle) {
        return SINCOSLIST[(hexAngle + 64) & 0xFF];
    }

    /**
     * Get sine as a normalized double using hex angle lookup.
     * Convenience method that divides by 256 for -1 to 1 result.
     * 
     * @param hexAngle The angle in 256-step hex format
     * @return Sine value from -1.0 to 1.0
     */
    public static double sinHexNormalized(int hexAngle) {
        return SINCOSLIST[hexAngle & 0xFF] / 256.0;
    }

    /**
     * Get cosine as a normalized double using hex angle lookup.
     * Convenience method that divides by 256 for -1 to 1 result.
     * 
     * @param hexAngle The angle in 256-step hex format
     * @return Cosine value from -1.0 to 1.0
     */
    public static double cosHexNormalized(int hexAngle) {
        return SINCOSLIST[(hexAngle + 64) & 0xFF] / 256.0;
    }
}
