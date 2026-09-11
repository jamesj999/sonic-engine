package com.openggf.game;

import com.openggf.control.InputHandler;

/**
 * No-op implementation of {@link DataSelectProvider} for games without a data select screen.
 * Used as the default return from {@link GameModule#getDataSelectProvider()}.
 */
public final class NoOpDataSelectProvider implements DataSelectProvider {

    public static final NoOpDataSelectProvider INSTANCE = new NoOpDataSelectProvider();

    private NoOpDataSelectProvider() {}

    @Override public void initialize() {}
    @Override public void update(InputHandler input) {}
    @Override public void draw() {}
    @Override public void setClearColor() {}
    @Override public void reset() {}
    @Override public State getState() { return State.INACTIVE; }
    @Override public boolean isExiting() { return false; }
    @Override public boolean isActive() { return false; }
}
