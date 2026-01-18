package uk.co.jamesj999.sonic.game;

import java.io.IOException;

/**
 * Interface for bonus stage implementations accessed via checkpoints.
 * Extends {@link MiniGameProvider} with bonus-specific functionality.
 *
 * <p>Bonus stages differ from special stages:
 * <ul>
 *   <li>Accessed via checkpoints, not giant rings</li>
 *   <li>Award rings, shields, lives - not Chaos Emeralds</li>
 *   <li>Selection may depend on ring count (Sonic 3&K)</li>
 * </ul>
 *
 * <p>Note: Sonic 2 does not have bonus stages (it uses special stages
 * accessed via checkpoints with 50+ rings instead).
 */
public interface BonusStageProvider extends MiniGameProvider {
    /**
     * Checks if this game has bonus stages.
     *
     * @return true if bonus stages are available
     */
    boolean hasBonusStages();

    /**
     * Determines which bonus stage type to use based on ring count.
     * This implements the ring-based selection logic (e.g., Sonic 3&K).
     *
     * @param ringCount the player's current ring count
     * @return the bonus stage type to use, or NONE if not eligible
     */
    BonusStageType selectBonusStage(int ringCount);

    /**
     * Initializes a specific bonus stage type.
     *
     * @param type the bonus stage type to initialize
     * @throws IOException if initialization fails
     */
    void initializeBonusStage(BonusStageType type) throws IOException;

    /**
     * Gets the rewards earned in the bonus stage.
     * Should be called after the stage is finished.
     *
     * @return a record containing the rewards (rings, lives, shields, etc.)
     */
    BonusStageRewards getRewards();

    /**
     * Record containing the rewards earned from a bonus stage.
     */
    record BonusStageRewards(
            int rings,
            int lives,
            boolean shield,
            boolean fireShield,
            boolean lightningShield,
            boolean bubbleShield
    ) {
        public static BonusStageRewards none() {
            return new BonusStageRewards(0, 0, false, false, false, false);
        }
    }
}
