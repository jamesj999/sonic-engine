package com.openggf;

import com.openggf.control.InputHandler;
import com.openggf.game.GameMode;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.Sonic3kGameModule;
import com.openggf.tests.TestEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestGameLoopHardwareTimingBoundaries {

    private GameplayModeContext context;
    private GameLoop loop;
    private List<String> events;

    @BeforeEach
    void setUp() {
        TestEnvironment.configureGameModuleFixture(new Sonic3kGameModule());
        context = SessionManager.getCurrentGameplayMode();
        context.activateRecordedHardwareAdmission();
        loop = new GameLoop(new InputHandler());
        events = new ArrayList<>();
        context.setHardwareTimingBoundaryObserver(
                boundary -> events.add(boundary.name()));
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
    }

    @Test
    void admittedSpecialStageIterationSurroundsProviderScanWithAllBoundaries()
            throws Exception {
        SpecialStageProvider provider = mock(SpecialStageProvider.class);
        doAnswer(ignored -> {
            events.add("SPECIAL_STAGE_SCAN");
            return null;
        }).when(provider).update();
        when(provider.isFinished()).thenReturn(false);
        setField(loop, "activeSpecialStageProvider", provider);
        loop.changeGameModeWithoutRewindBoundary(GameMode.SPECIAL_STAGE);

        loop.step();

        assertEquals(List.of(
                "VINT_SERVICE",
                "SPECIAL_STAGE_SCAN",
                "POST_OBJECTS",
                "PRE_MAIN_LOOP"), events);
    }

    @Test
    void s3kTitleCardIterationServicesBeforeAndImmediatelyAfterProviderScan()
            throws Exception {
        TitleCardProvider provider = mock(TitleCardProvider.class);
        when(provider.shouldAdvanceVblankClockDuringLockedPhase()).thenReturn(true);
        when(provider.shouldRunPlayerPhysics()).thenReturn(false);
        when(provider.shouldRunLevelObjectsDuringLockedPhase()).thenReturn(false);
        when(provider.shouldReleaseControl()).thenReturn(false);
        doAnswer(ignored -> {
            events.add("TITLE_CARD_SCAN");
            return null;
        }).when(provider).update();
        setField(loop, "titleCardProvider", provider);
        loop.changeGameModeWithoutRewindBoundary(GameMode.TITLE_CARD);

        loop.step();

        assertEquals(List.of(
                "VINT_SERVICE",
                "TITLE_CARD_SCAN",
                "POST_OBJECTS",
                "PRE_MAIN_LOOP"), events);
    }

    private static void setField(Object target, String name, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
