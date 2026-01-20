package uk.co.jamesj999.sonic.physics;

/**
 * Result of a terrain collision check (floor or ceiling).
 * <p>
 * The distance value indicates how far the check position is from the surface:
 * <ul>
 *   <li>Negative distance = collision found (surface is above the check point for floor checks)</li>
 *   <li>Zero = exactly on surface</li>
 *   <li>Positive distance = no collision yet (surface is below the check point for floor checks)</li>
 *   <li>{@link #NO_COLLISION} = no solid surface found at all</li>
 * </ul>
 *
 * @param distance Distance to surface in pixels (negative means collision)
 * @param angle Surface angle from SolidTile (for slope handling)
 * @param tileIndex Collision tile index for debugging
 */
public record TerrainCheckResult(
    int distance,
    byte angle,
    int tileIndex
) {
    /**
     * Sentinel value indicating no collision was found at all.
     */
    public static final int NO_COLLISION = 0x7FFF;

    /**
     * Create a result indicating no surface was found.
     */
    public static TerrainCheckResult noCollision() {
        return new TerrainCheckResult(NO_COLLISION, (byte) 0, -1);
    }

    /**
     * Check if a surface was found during the collision check.
     *
     * @return true if a surface was detected (distance is not NO_COLLISION)
     */
    public boolean foundSurface() {
        return distance != NO_COLLISION;
    }

    /**
     * Check if there is an actual collision (surface is at or past the check point).
     * For floor checks, this means the floor is at or above the bottom of the object.
     *
     * @return true if there is a collision (distance <= 0)
     */
    public boolean hasCollision() {
        return distance != NO_COLLISION && distance <= 0;
    }
}
