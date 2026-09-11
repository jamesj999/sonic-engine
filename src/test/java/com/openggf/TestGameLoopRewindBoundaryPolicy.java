package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.rewind.RewindBoundary;
import com.openggf.game.session.EngineServices;
import com.openggf.game.session.GameplaySessionFactory;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.session.WorldSession;
import com.openggf.game.sonic2.Sonic2GameModule;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class TestGameLoopRewindBoundaryPolicy {

    private GameplayModeContext context;
    private GameLoop loop;
    private List<RewindBoundary> boundaries;

    @BeforeEach
    void setUp() throws Exception {
        setActiveTraceSession(null);
        TestEnvironment.configureGameModuleFixture(new Sonic2GameModule());
        context = SessionManager.getCurrentGameplayMode();
        boundaries = new ArrayList<>();
        loop = new GameLoop(new InputHandler());
        context.setRewindBoundaryReporter(boundaries::add);
    }

    @AfterEach
    void tearDown() throws Exception {
        setActiveTraceSession(null);
        SessionManager.clear();
    }

    @Test
    void installedReporterDoesNotForwardBoundariesWhenTraceSessionIsActive() throws Exception {
        TraceSessionLauncher previous = activeTraceSession();
        try {
            setActiveTraceSession(mock(TraceSessionLauncher.class));
            loop.installLiveRewindBoundaryReporter(boundaries::add);

            context.markRewindBoundary(RewindBoundary.LEVEL_LOAD);

            assertEquals(List.of(), boundaries);
        } finally {
            setActiveTraceSession(previous);
        }
    }

    @Test
    void levelToNonLevelReportsModeExitBoundary() {
        loop.changeGameModeForBoundary(GameMode.SPECIAL_STAGE);

        assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
    }

    @Test
    void nonLevelToLevelReportsModeEnterBoundary() {
        loop.changeGameModeForBoundary(GameMode.TITLE_SCREEN);
        boundaries.clear();

        loop.changeGameModeForBoundary(GameMode.LEVEL);

        assertEquals(List.of(RewindBoundary.MODE_ENTER_REWINDABLE), boundaries);
    }

    @Test
    void modeBoundaryReportsToLoopBoundGameplayContext() {
        GameplayModeContext sessionContext = context;
        List<RewindBoundary> sessionBoundaries = new ArrayList<>();
        List<RewindBoundary> loopBoundaries = new ArrayList<>();
        GameplayModeContext loopContext = new GameplayModeContext(new WorldSession(new Sonic2GameModule()));
        GameplaySessionFactory.attachManagers(loopContext, EngineServices.current());

        loop.setGameplayMode(loopContext);
        sessionContext.setRewindBoundaryReporter(sessionBoundaries::add);
        loopContext.setRewindBoundaryReporter(loopBoundaries::add);

        loop.changeGameModeForBoundary(GameMode.SPECIAL_STAGE);

        assertEquals(List.of(), sessionBoundaries);
        assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), loopBoundaries);
    }

    @Test
    void reporterInstallsOnLoopBoundGameplayContext() {
        List<RewindBoundary> installedBoundaries = new ArrayList<>();
        GameplayModeContext loopContext = new GameplayModeContext(new WorldSession(new Sonic2GameModule()));
        GameplaySessionFactory.attachManagers(loopContext, EngineServices.current());
        loop.setGameplayMode(loopContext);

        loop.installLiveRewindBoundaryReporter(installedBoundaries::add);
        loopContext.markRewindBoundary(RewindBoundary.LEVEL_LOAD);

        assertEquals(List.of(RewindBoundary.LEVEL_LOAD), installedBoundaries);
    }

    @Test
    void bonusStageEntryTitleCardTransitionDoesNotDuplicateExplicitModeExit() {
        context.markRewindBoundary(RewindBoundary.LEVEL_LOAD);

        loop.enterBonusTitleCardAfterLevelLoadBoundary();

        assertEquals(List.of(
                RewindBoundary.LEVEL_LOAD,
                RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
    }

    @Test
    void installedReporterForwardsBoundariesWhenTraceSessionIsInactive() {
        loop.installLiveRewindBoundaryReporter(boundaries::add);
        context.markRewindBoundary(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE);

        assertEquals(List.of(RewindBoundary.MODE_EXIT_TO_NON_REWINDABLE), boundaries);
    }

    private static TraceSessionLauncher activeTraceSession() throws Exception {
        return (TraceSessionLauncher) activeTraceSessionField().get(null);
    }

    private static void setActiveTraceSession(TraceSessionLauncher session) throws Exception {
        activeTraceSessionField().set(null, session);
    }

    private static Field activeTraceSessionField() throws Exception {
        Field field = TraceSessionLauncher.class.getDeclaredField("activeSession");
        field.setAccessible(true);
        return field;
    }
}
