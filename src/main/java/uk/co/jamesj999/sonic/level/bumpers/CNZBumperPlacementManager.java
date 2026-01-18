package uk.co.jamesj999.sonic.level.bumpers;

import uk.co.jamesj999.sonic.level.spawn.AbstractPlacementManager;

import java.util.List;

/**
 * Camera-culled manager for CNZ map bumpers.
 * <p>
 * Tracks which bumpers are within the visible camera range for efficient
 * collision checking. Uses the same windowing pattern as RingPlacementManager.
 * <p>
 * Disassembly Reference:
 * <ul>
 *   <li>CNZ_Visible_bumpers_start, CNZ_Visible_bumpers_end (s2.asm line 32176-32187)</li>
 *   <li>SpecialCNZBumpers_Main update logic (s2.asm line 32192-32274)</li>
 * </ul>
 * <p>
 * The original game uses camera_x - 8 for the left edge and camera_x + $150 (336)
 * for the right edge. We use slightly larger margins for safety.
 */
public class CNZBumperPlacementManager extends AbstractPlacementManager<CNZBumperSpawn> {

    /**
     * Load ahead distance in pixels.
     * ROM uses $150 (336), we use 640 for consistency with RingPlacementManager.
     */
    private static final int LOAD_AHEAD = 640;

    /**
     * Unload behind distance in pixels.
     * ROM uses 8, we use 768 for consistency with RingPlacementManager.
     */
    private static final int UNLOAD_BEHIND = 768;

    private int lastWindowStart = -1;
    private int lastWindowEnd = -1;

    public CNZBumperPlacementManager(List<CNZBumperSpawn> bumpers) {
        super(bumpers, LOAD_AHEAD, UNLOAD_BEHIND);
    }

    /**
     * Update the active bumper set based on camera position.
     * <p>
     * This recalculates which bumpers are in the visible window and updates
     * the active set accordingly. Should be called each frame.
     *
     * @param cameraX Current camera X position
     */
    public void update(int cameraX) {
        int windowStart = getWindowStart(cameraX);
        int windowEnd = getWindowEnd(cameraX);

        // Skip update if window hasn't changed significantly
        if (windowStart == lastWindowStart && windowEnd == lastWindowEnd) {
            return;
        }

        lastWindowStart = windowStart;
        lastWindowEnd = windowEnd;

        // Clear and rebuild active set
        active.clear();

        // Find bumpers within the window using binary search
        int startIdx = lowerBound(windowStart);
        int endIdx = upperBound(windowEnd);

        for (int i = startIdx; i < endIdx && i < spawns.size(); i++) {
            CNZBumperSpawn bumper = spawns.get(i);
            if (bumper.x() >= windowStart && bumper.x() <= windowEnd) {
                active.add(bumper);
            }
        }
    }

    /**
     * Reset the manager state and rebuild active set from scratch.
     *
     * @param cameraX Current camera X position
     */
    public void reset(int cameraX) {
        active.clear();
        lastWindowStart = -1;
        lastWindowEnd = -1;
        update(cameraX);
    }
}
