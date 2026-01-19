package uk.co.jamesj999.sonic.game;

import uk.co.jamesj999.sonic.graphics.GLCommand;

import java.util.List;

/**
 * No-op implementation of {@link ResultsScreen}.
 * Used by {@link NoOpSpecialStageProvider} when results screens are requested.
 */
public final class NoOpResultsScreen implements ResultsScreen {
    public static final NoOpResultsScreen INSTANCE = new NoOpResultsScreen();

    private NoOpResultsScreen() {}

    @Override
    public void update(int frameCounter, Object context) {
        // No-op
    }

    @Override
    public boolean isComplete() {
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // No-op
    }
}
