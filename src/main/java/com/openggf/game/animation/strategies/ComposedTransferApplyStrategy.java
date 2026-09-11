package com.openggf.game.animation.strategies;

import com.openggf.game.animation.ApplyStrategy;
import com.openggf.game.animation.ChannelContext;

import java.util.Objects;

/**
 * Simple {@link ApplyStrategy} that delegates to a caller-supplied transfer routine.
 *
 * <p>Use this when the animated-tile channel already has a focused helper that knows how to write
 * the composed result into live pattern data and does not need extra context beyond the current
 * frame having requested an apply.
 */
public final class ComposedTransferApplyStrategy implements ApplyStrategy {
    private final Runnable transfer;

    /** Wraps the concrete transfer work for one channel application. */
    public ComposedTransferApplyStrategy(Runnable transfer) {
        this.transfer = Objects.requireNonNull(transfer, "transfer");
    }

    @Override
    public void apply(ChannelContext context) {
        transfer.run();
    }
}
