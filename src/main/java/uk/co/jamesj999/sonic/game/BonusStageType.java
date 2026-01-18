package uk.co.jamesj999.sonic.game;

/**
 * Defines the types of bonus stages available in different Sonic games.
 *
 * <p>Bonus stages are typically accessed via checkpoints and provide
 * rewards like rings, shields, or extra lives (not Chaos Emeralds).
 *
 * <p>Sonic 3&K bonus stage selection (based on ring count at checkpoint):
 * <ul>
 *   <li>20-34 rings: Gumball Machine</li>
 *   <li>35-49 rings: Glowing Spheres</li>
 *   <li>50+ rings: Slot Machine</li>
 * </ul>
 */
public enum BonusStageType {
    /**
     * No bonus stage available.
     */
    NONE,

    /**
     * Sonic 3&K Gumball Machine bonus stage.
     * Accessed with 20-34 rings at checkpoint.
     */
    GUMBALL,

    /**
     * Sonic 3&K Glowing Spheres bonus stage.
     * Accessed with 35-49 rings at checkpoint.
     */
    GLOWING_SPHERE,

    /**
     * Sonic 3&K / Sonic 1 style Slot Machine bonus stage.
     * Accessed with 50+ rings at checkpoint in S3&K.
     */
    SLOT_MACHINE
}
