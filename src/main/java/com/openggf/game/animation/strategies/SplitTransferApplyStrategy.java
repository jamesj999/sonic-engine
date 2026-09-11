package com.openggf.game.animation.strategies;

import com.openggf.game.animation.ApplyStrategy;
import com.openggf.game.animation.ChannelContext;

import java.util.Objects;

/**
 * {@link ApplyStrategy} for channels that scatter one resolved frame across multiple destinations.
 *
 * <p>The supplied runnable encapsulates the split-transfer details so the channel graph can treat
 * the write as one atomic apply step.
 */
public final class SplitTransferApplyStrategy implements ApplyStrategy {
    private final Runnable transfer;

    /** Wraps the concrete split transfer used by the channel. */
    public SplitTransferApplyStrategy(Runnable transfer) {
        this.transfer = Objects.requireNonNull(transfer, "transfer");
    }

    @Override
    public void apply(ChannelContext context) {
        transfer.run();
    }
}
