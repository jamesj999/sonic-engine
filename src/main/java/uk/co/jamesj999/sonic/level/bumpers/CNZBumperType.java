package uk.co.jamesj999.sonic.level.bumpers;

/**
 * CNZ Map Bumper types with collision dimensions and bounce behavior.
 * <p>
 * ROM Reference: byte_17558 at s2.asm line 32347
 * <pre>
 * byte_17558:
 *     dc.b $20, $20   ; Type 0: 32x32 diagonal down-right
 *     dc.b $20, $20   ; Type 1: 32x32 diagonal down-left
 *     dc.b $40, $08   ; Type 2: 64x8 narrow top
 *     dc.b $40, $08   ; Type 3: 64x8 narrow bottom
 *     dc.b $08, $40   ; Type 4: 8x64 narrow left
 *     dc.b $08, $40   ; Type 5: 8x64 narrow right
 * </pre>
 * <p>
 * Collision dimensions are (halfWidth, halfHeight) in pixels.
 * The diagonal types (0-1) use angle-based reflection physics.
 * The narrow types (2-5) use position-based axis bouncing.
 */
public enum CNZBumperType {
    /**
     * Type 0: 32x32 diagonal, down-right oriented.
     * Handler: loc_17586 (s2.asm line 32386)
     * Base reflection angle: $20 (45 degrees clockwise from up)
     */
    DIAGONAL_DOWN_RIGHT(0, 0x20, 0x20, 0x20),

    /**
     * Type 1: 32x32 diagonal, down-left oriented.
     * Handler: loc_17638 (s2.asm line 32456)
     * Base reflection angle: $60 (135 degrees clockwise from up)
     */
    DIAGONAL_DOWN_LEFT(1, 0x20, 0x20, 0x60),

    /**
     * Type 2: 64x8 narrow top horizontal bar.
     * Handler: loc_1769E (s2.asm line 32499)
     * Bounces downward or diagonally based on position.
     */
    NARROW_TOP(2, 0x40, 0x08, -1),

    /**
     * Type 3: 64x8 narrow bottom horizontal bar.
     * Handler: loc_176F6 (s2.asm line 32537)
     * Bounces upward or diagonally based on position.
     */
    NARROW_BOTTOM(3, 0x40, 0x08, -1),

    /**
     * Type 4: 8x64 narrow left vertical bar.
     * Handler: loc_1774C (s2.asm line 32574)
     * Bounces rightward or diagonally based on position.
     */
    NARROW_LEFT(4, 0x08, 0x40, -1),

    /**
     * Type 5: 8x64 narrow right vertical bar.
     * Handler: loc_177A4 (s2.asm line 32612)
     * Bounces leftward or diagonally based on position.
     */
    NARROW_RIGHT(5, 0x08, 0x40, -1);

    private final int id;
    private final int halfWidth;
    private final int halfHeight;
    private final int baseAngle;

    CNZBumperType(int id, int halfWidth, int halfHeight, int baseAngle) {
        this.id = id;
        this.halfWidth = halfWidth;
        this.halfHeight = halfHeight;
        this.baseAngle = baseAngle;
    }

    public int getId() {
        return id;
    }

    /**
     * Get collision box half-width in pixels.
     */
    public int getHalfWidth() {
        return halfWidth;
    }

    /**
     * Get collision box half-height in pixels.
     */
    public int getHalfHeight() {
        return halfHeight;
    }

    /**
     * Get the base reflection angle for diagonal bumper types.
     * Returns -1 for non-diagonal types.
     */
    public int getBaseAngle() {
        return baseAngle;
    }

    /**
     * Check if this bumper type uses diagonal angle-based reflection physics.
     */
    public boolean isDiagonal() {
        return baseAngle >= 0;
    }

    /**
     * Get CNZBumperType by type ID (0-5).
     *
     * @param typeId The bumper type ID
     * @return The corresponding CNZBumperType, or null if invalid
     */
    public static CNZBumperType fromId(int typeId) {
        for (CNZBumperType type : values()) {
            if (type.id == typeId) {
                return type;
            }
        }
        return null;
    }
}
