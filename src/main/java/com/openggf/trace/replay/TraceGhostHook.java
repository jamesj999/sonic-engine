package com.openggf.trace.replay;

import com.openggf.SpritePriorityLayerHook;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The active trace desync-ghost renderer, set by whichever trace driver is live
 * — the interactive Trace Test Mode launcher or the headless capture session.
 *
 * <p>{@code LevelRenderer} queries {@link #active()} both to render the ghosts
 * and to decide whether the trace HUD-visibility flags ({@code TRACE_SHOW_*})
 * apply. It is {@code null} during normal gameplay, so those flags never affect
 * ordinary play — only trace replay/capture.
 */
public final class TraceGhostHook {

    /** Renders the ghost sprites for one priority bucket/layer. */
    @FunctionalInterface
    public interface GhostLayerRenderer extends SpritePriorityLayerHook {
        void renderGhostsForLayer(int bucket, boolean highPriority);

        @Override
        default void beforePriorityLayer(int bucket, boolean highPriority) {
            renderGhostsForLayer(bucket, highPriority);
        }
    }

    private static final AtomicReference<GhostLayerRenderer> ACTIVE = new AtomicReference<>();

    private TraceGhostHook() {
    }

    public static void set(GhostLayerRenderer renderer) {
        ACTIVE.set(renderer);
    }

    /** Clears the hook only if {@code renderer} is still the active one. */
    public static void clear(GhostLayerRenderer renderer) {
        ACTIVE.compareAndSet(renderer, null);
    }

    public static GhostLayerRenderer active() {
        return ACTIVE.get();
    }
}
