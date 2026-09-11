package com.openggf.game.mutation;

import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.snapshot.MutationPipelineSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime-owned queue for deterministic level-layout mutations.
 *
 * <p>Zone code can enqueue mutation intents during gameplay and flush them at a
 * controlled point in the frame. If a batched mutation throws, the remaining
 * queued work is preserved so the caller can recover or retry.
 */
public final class ZoneLayoutMutationPipeline
        implements RewindSnapshottable<MutationPipelineSnapshot> {

    private final List<LayoutMutationIntent> queued = new ArrayList<>();

    /** Appends a mutation intent to the end of the pending queue. */
    public void queue(LayoutMutationIntent intent) {
        queued.add(Objects.requireNonNull(intent, "intent"));
    }

    /** Applies all queued intents in order and clears the queue on success. */
    public void flush(LayoutMutationContext context) {
        Objects.requireNonNull(context, "context");
        if (queued.isEmpty()) {
            return;
        }

        MutationBatch batch = new MutationBatch(queued);
        queued.clear();
        apply(batch, context);
    }

    /** Applies a single intent immediately without passing through the queue. */
    public void applyImmediately(LayoutMutationIntent intent, LayoutMutationContext context) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(context, "context");
        context.publish(intent.apply(context));
    }

    /**
     * Applies a single intent immediately, stripping all redraw hints from the
     * published {@link MutationEffects} before forwarding them to the context sink.
     *
     * <p>Use when the caller controls its own redraw sequencing and the pipeline's
     * automatic redraw publication would break the desired ordering (e.g. a
     * snapshot-then-clear effect where the cleared tiles must remain invisible
     * until an explicit flush).  Non-rendering side effects (dirty pattern uploads,
     * object/ring resync) are still published.
     */
    public void applyImmediatelyWithoutRedraw(LayoutMutationIntent intent, LayoutMutationContext context) {
        Objects.requireNonNull(intent, "intent");
        Objects.requireNonNull(context, "context");
        MutationEffects effects = intent.apply(context);
        context.publish(effects != null ? effects.withoutRedrawHints() : MutationEffects.NONE);
    }

    /** Drops all queued intents without applying them. */
    public void clear() {
        queued.clear();
    }

    /** Returns {@code true} when no pending intents remain. */
    public boolean isEmpty() {
        return queued.isEmpty();
    }

    private void apply(MutationBatch batch, LayoutMutationContext context) {
        for (int i = 0; i < batch.intents().size(); i++) {
            LayoutMutationIntent intent = batch.intents().get(i);
            MutationEffects effects;
            try {
                effects = intent.apply(context);
            } catch (RuntimeException | Error ex) {
                queued.addAll(0, batch.remainingFrom(i));
                throw ex;
            }
            context.publish(effects);
        }
    }

    // ── RewindSnapshottable ───────────────────────────────────────────────

    @Override
    public String key() {
        return "mutation-pipeline";
    }

    @Override
    public MutationPipelineSnapshot capture() {
        return new MutationPipelineSnapshot(queued);
    }

    @Override
    public void restore(MutationPipelineSnapshot s) {
        queued.clear();
        queued.addAll(s.queued());
    }
}
