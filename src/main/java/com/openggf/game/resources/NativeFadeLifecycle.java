package com.openggf.game.resources;

/** Narrow port used by owners of native blocking palette-fade loops. */
public interface NativeFadeLifecycle {
    NativeBlockingFade beginNativeBlockingFade();

    interface NativeBlockingFade extends AutoCloseable {
        Runnable wrapCompletion(Runnable completion);

        @Override
        void close();
    }
}
