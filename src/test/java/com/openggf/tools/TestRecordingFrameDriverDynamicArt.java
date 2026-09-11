package com.openggf.tools;

import com.openggf.LevelFrameResult;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.tests.TestEnvironment;
import com.openggf.trace.replay.runs.TraceRunReplayWalker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class TestRecordingFrameDriverDynamicArt {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void pausedHeadlessIterationOpensAndPublishesFromProductionPhaseState() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        context.getGameStateManager().setGamePaused(true);
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));

        LevelFrameResult result =
                driver.stepFrame(false, false, false, false, false);

        assertEquals(LevelFrameResult.PAUSED, result);
        assertTrue(context.dynamicArtLifecycle().isComparisonSegmentOpen());
        assertEquals(0, context.dynamicArtLifecycle().latestSnapshot().frame());
        assertTrue(context.dynamicArtLifecycle()
                .latestSnapshot().edges().isEmpty());
    }

    @Test
    void externalSegmentSeamAcceptsNoExpectedTraceState() {
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        GameplayModeContext context = SessionManager.getCurrentGameplayMode();
        context.getGameStateManager().setGamePaused(true);
        RecordingFrameDriver driver =
                new RecordingFrameDriver(mock(AbstractPlayableSprite.class));
        var segments =
                new TraceRunReplayWalker.DynamicArtSegmentController(driver);
        segments.beginSegment();

        driver.stepFrame(false, false, false, false, false);
        segments.close();

        assertEquals(0, context.dynamicArtLifecycle().latestSnapshot().frame());
        assertTrue(context.dynamicArtLifecycle().gapTransitions().isEmpty());
    }
}
