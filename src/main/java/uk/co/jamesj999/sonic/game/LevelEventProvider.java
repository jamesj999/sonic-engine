package uk.co.jamesj999.sonic.game;

/**
 * Interface for game-specific dynamic level events.
 *
 * Level events handle runtime changes to camera boundaries, boss arena setup,
 * earthquake effects, and other zone-specific behaviors that trigger based on
 * player/camera position during gameplay.
 *
 * Implementations are game-specific (e.g., Sonic 2's RunDynamicLevelEvents).
 */
public interface LevelEventProvider {

    /**
     * Initialize level event state for a new level.
     * Called when a level is loaded or restarted.
     *
     * @param zone The zone index
     * @param act The act index within the zone
     */
    void initLevel(int zone, int act);

    /**
     * Update level events for the current frame.
     * Called once per frame before camera boundary easing.
     *
     * Implementations should check camera/player position and trigger
     * appropriate boundary changes or other level events.
     */
    void update();
}
