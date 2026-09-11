package com.openggf.level;

import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.Sonic2GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestLevelManagerRewindBoundary {

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void levelBoundaryReportsThroughGameplayContext() {
        GameplayModeContext context = SessionManager.openGameplaySession(new Sonic2GameModule());
        AtomicReference<RewindBoundary> boundary = new AtomicReference<>();
        context.setRewindBoundaryReporter(boundary::set);

        LevelManager.markRewindLevelLoadBoundary();

        assertEquals(RewindBoundary.LEVEL_LOAD, boundary.get());
    }
}
