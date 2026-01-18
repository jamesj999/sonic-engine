package uk.co.jamesj999.sonic.game;

/**
 * Defines how special stages are accessed in each game.
 *
 * <ul>
 *   <li>{@link #GIANT_RING} - Sonic 1, Sonic 3&K: Hidden giant rings in levels</li>
 *   <li>{@link #STARPOST} - Sonic 2: Star post/checkpoint with 50+ rings</li>
 * </ul>
 */
public enum SpecialStageAccessType {
    /**
     * Special stage is accessed via hidden giant rings in levels.
     * Used by Sonic 1 and Sonic 3 & Knuckles.
     */
    GIANT_RING,

    /**
     * Special stage is accessed via star posts (checkpoints) with 50+ rings.
     * Used by Sonic 2.
     */
    STARPOST
}
