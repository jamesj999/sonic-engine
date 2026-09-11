package com.openggf.game.resources;

/** Explicit injection capability for providers that start native blocking fades. */
public interface NativeFadeLifecycleAware {
    void bindNativeFadeLifecycle(NativeFadeLifecycle lifecycle);
}
