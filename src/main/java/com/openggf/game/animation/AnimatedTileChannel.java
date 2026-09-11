package com.openggf.game.animation;

import java.util.Objects;

/**
 * Declarative description of one animated-tile producer in the runtime graph.
 *
 * <p>A channel answers three questions:
 * 1. whether it should run this frame
 * 2. which logical phase it is currently in
 * 3. how it writes its output into live level pattern data
 *
 * <p>The graph owns sequencing and phase caching; the channel itself is just
 * configuration for one independent animation stream.
 */
public final class AnimatedTileChannel {

    private final String channelId;
    private final ChannelGuard guard;
    private final PhaseSource phaseSource;
    private final DestinationPlan destinationPlan;
    private final AnimatedTileCachePolicy cachePolicy;
    private final ApplyStrategy applyStrategy;

    public AnimatedTileChannel(String channelId,
                               ChannelGuard guard,
                               PhaseSource phaseSource,
                               DestinationPlan destinationPlan,
                               AnimatedTileCachePolicy cachePolicy,
                               ApplyStrategy applyStrategy) {
        this.channelId = Objects.requireNonNull(channelId, "channelId");
        this.guard = Objects.requireNonNull(guard, "guard");
        this.phaseSource = Objects.requireNonNull(phaseSource, "phaseSource");
        this.destinationPlan = Objects.requireNonNull(destinationPlan, "destinationPlan");
        this.cachePolicy = Objects.requireNonNull(cachePolicy, "cachePolicy");
        this.applyStrategy = Objects.requireNonNull(applyStrategy, "applyStrategy");
    }

    /** Stable identifier used for duplicate detection and per-channel phase caching. */
    public String channelId() {
        return channelId;
    }

    /** Predicate controlling whether the channel participates in the current graph update. */
    public ChannelGuard guard() {
        return guard;
    }

    /** Resolves the channel's current logical phase for cache and strategy decisions. */
    public PhaseSource phaseSource() {
        return phaseSource;
    }

    /** Declares the destination tile slots that this channel owns. */
    public DestinationPlan destinationPlan() {
        return destinationPlan;
    }

    /** Describes whether unchanged phases may skip reapplication. */
    public AnimatedTileCachePolicy cachePolicy() {
        return cachePolicy;
    }

    /** Writes the channel's output for the current phase into the active level state. */
    public ApplyStrategy applyStrategy() {
        return applyStrategy;
    }
}
