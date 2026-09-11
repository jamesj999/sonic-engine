package com.openggf.level;

import com.openggf.game.InitialProcessSpritesLifecycle;

/** Owns the one-shot initial {@code Process_Sprites} lifecycle token. */
public abstract class InitialProcessSpritesLevelManagerBase {
    private final InitialProcessSpritesLifecycleCoordinator lifecycle =
            new InitialProcessSpritesLifecycleCoordinator();

    public final boolean hasPendingInitialProcessSpritesPass() {
        return lifecycle.hasPendingPass();
    }

    public final boolean consumePendingInitialProcessSpritesPass() {
        return lifecycle.consume(this::executeInitialProcessSprites);
    }

    public final void discardPendingInitialProcessSpritesForStateRestoration() {
        lifecycle.discard();
    }

    public final InitialProcessSpritesLifecycle
            capturePendingInitialProcessSpritesLifecycleForRewind() {
        return lifecycle.captureForRewind();
    }

    public final void restorePendingInitialProcessSpritesLifecycleForRewind(
            InitialProcessSpritesLifecycle lifecycle) {
        this.lifecycle.restoreForRewind(lifecycle);
    }

    final void publishInitialProcessSpritesLifecycle(InitialProcessSpritesLifecycle requested) {
        lifecycle.publish(requested);
    }

    final void discardInitialProcessSpritesLifecycle() {
        lifecycle.discard();
    }

    protected abstract void executeInitialProcessSprites();
}
