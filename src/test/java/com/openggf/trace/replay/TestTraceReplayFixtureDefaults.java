package com.openggf.trace.replay;

import com.openggf.game.session.GameplayModeContext;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTraceReplayFixtureDefaults {

    @Test
    void terminalPlayableSlicesUseGameplayModeSpriteManager() {
        TraceReplayFixture fixture = mock(
                TraceReplayFixture.class, CALLS_REAL_METHODS);
        GameplayModeContext gameplayMode = mock(GameplayModeContext.class);
        SpriteManager sprites = mock(SpriteManager.class);
        when(fixture.gameplayMode()).thenReturn(gameplayMode);
        when(gameplayMode.getSpriteManager()).thenReturn(sprites);

        fixture.advancePlayableAnimationsOnly();
        fixture.advancePlayableFixedSlotsOnly();

        verify(sprites).advancePlayableSlotPrefix();
        verify(sprites).advanceTailsTailsAfterObjectExecution();
    }
}
