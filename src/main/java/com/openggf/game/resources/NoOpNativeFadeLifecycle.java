package com.openggf.game.resources;

/** Compatibility lifecycle for isolated presentation tests without a gameplay session. */
public enum NoOpNativeFadeLifecycle implements NativeFadeLifecycle {
    INSTANCE;

    @Override
    public NativeBlockingFade beginNativeBlockingFade() {
        return new NativeBlockingFade() {
            @Override
            public Runnable wrapCompletion(Runnable completion) {
                return completion != null ? completion : () -> { };
            }

            @Override
            public void close() {
            }
        };
    }
}
