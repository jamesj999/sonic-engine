package com.openggf;

/** Render-only hook invoked immediately before a nonempty sprite priority layer. */
@FunctionalInterface
public interface SpritePriorityLayerHook {
    void beforePriorityLayer(int bucket, boolean highPriority);
}
