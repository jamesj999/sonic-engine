package com.openggf.game.animation;

/**
 * Declares the level pattern destinations owned by one animated tile channel.
 *
 * <p>Some channels upload to a single contiguous tile range, while others split
 * their output across two destinations.
 */
public record DestinationPlan(int primaryTile, Integer secondaryTile) {

    /** Convenience factory for channels that target one destination range. */
    public static DestinationPlan single(int primaryTile) {
        return new DestinationPlan(primaryTile, null);
    }
}
