package com.openggf.game.rewind.snapshot;

import com.openggf.game.mutation.LayoutMutationIntent;

import java.util.List;

/**
 * Snapshot of {@link com.openggf.game.mutation.ZoneLayoutMutationPipeline}
 * pending-queue state.
 *
 * <p>In normal gameplay the pipeline flushes its queue every frame, so the
 * queue is empty at frame boundaries. However, if a mutation intent throws
 * during {@code flush()}, the remaining intents are preserved for retry.
 * The snapshot captures the queue state so rewind correctly restores any
 * pending-but-unflushed intents.
 */
public record MutationPipelineSnapshot(List<LayoutMutationIntent> queued) {
    public MutationPipelineSnapshot {
        queued = List.copyOf(queued);
    }
}
