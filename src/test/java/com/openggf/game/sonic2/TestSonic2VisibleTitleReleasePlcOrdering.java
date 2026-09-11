package com.openggf.game.sonic2;

import com.openggf.GameLoop;
import com.openggf.control.InputHandler;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.sonic2.constants.Sonic2Constants;
import com.openggf.game.sonic2.resources.Sonic2PlcService;
import com.openggf.level.LevelManager;
import com.openggf.tests.TestEnvironment;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@RequiresRom(SonicGame.SONIC_2)
class TestSonic2VisibleTitleReleasePlcOrdering {
    @Test
    void headerSecondaryIsPublishedAfterTheFinalLockedTitlePreparation() throws Exception {
        Sonic2PlcService service = new Sonic2PlcService(TestEnvironment.currentRom());
        LevelManager levelManager = mock(LevelManager.class);
        doAnswer(ignored -> {
            service.append(Sonic2Constants.PLC_EHZ2);
            return null;
        }).when(levelManager).completeInitialTitleCardPresentation();
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        when(titleCard.shouldReleaseControl()).thenReturn(true);

        GameLoop loop = new GameLoop(new InputHandler());
        set(loop, "titleCardProvider", titleCard);
        set(loop, "levelManager", levelManager);
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(service);
        var frame = coordinator.latchBeforeFadeUpdate();
        set(loop, "activePlcLifecycleFrame", frame);

        Method update = GameLoop.class.getDeclaredMethod(
                "updateTitleCardMode", boolean.class);
        update.setAccessible(true);
        update.invoke(loop, true);
        frame.finish();

        assertNull(service.capture().activeEntry(),
                "loadZoneBlockMaps publishes only after the final RunPLC_RAM preparation");
        assertTrue(service.capture().queuedEntries().size() > 0);
    }

    private static void set(Object target, String fieldName, Object value)
            throws ReflectiveOperationException {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
