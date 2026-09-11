package com.openggf.level;

import com.openggf.game.InitialProcessSpritesLifecycle;

/**
 * Owns pending initial object-setup authority for one live level runtime.
 */
final class InitialProcessSpritesLifecycleCoordinator {
    private InitialProcessSpritesLifecycle pending = InitialProcessSpritesLifecycle.NONE;

    void publish(InitialProcessSpritesLifecycle lifecycle) {
        pending = lifecycle == null ? InitialProcessSpritesLifecycle.NONE : lifecycle;
    }

    boolean hasPendingPass() {
        return pending == InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE;
    }

    boolean consume(Runnable dispatch) {
        InitialProcessSpritesLifecycle result = pending;
        pending = InitialProcessSpritesLifecycle.NONE;
        if (result != InitialProcessSpritesLifecycle.LOAD_THEN_PROCESS_ONCE) {
            return false;
        }
        dispatch.run();
        return true;
    }

    void discard() {
        pending = InitialProcessSpritesLifecycle.NONE;
    }

    InitialProcessSpritesLifecycle captureForRewind() {
        return pending;
    }

    void restoreForRewind(InitialProcessSpritesLifecycle lifecycle) {
        pending = lifecycle == null ? InitialProcessSpritesLifecycle.NONE : lifecycle;
    }
}
