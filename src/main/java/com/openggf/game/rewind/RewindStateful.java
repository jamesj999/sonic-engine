package com.openggf.game.rewind;

/**
 * Small mutable helper objects can implement this when their identity is structural
 * but their fields are rewind-relevant. The generic capturer stores only the
 * returned state value and restores it into the existing helper instance.
 */
public interface RewindStateful<S> {
    S captureRewindStateValue();

    void restoreRewindStateValue(S state);
}
