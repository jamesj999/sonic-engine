package com.openggf.game.resources;

import com.openggf.LevelFrameStep;
import com.openggf.GameLoop;
import com.openggf.control.InputHandler;
import com.openggf.game.GameModule;
import com.openggf.game.LevelInitProfile;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.WorldSession;
import com.openggf.game.timing.HardwareTimingService;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class TestPlcLifecycleDriverParity {

    @Test
    void logicalIterationPinsServiceFadePreparationAndBodyOrdering() {
        assertEquals(List.of(
                "fade", "service:ORDINARY_LEVEL", "body",
                "prepare:ORDINARY_LEVEL",
                "service:PALETTE_FADE", "fade", "prepare:PALETTE_FADE",
                "body"), runRepresentativeIterations());
    }

    @Test
    void multiplePumpedStepsLatchSeparateTokensAndAdvanceFadeOnceEach() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));

        coordinator.runLogicalIteration(
                () -> events.add("fade"), frame -> {
                    frame.claim(PlcLifecyclePhase.LAG);
                    return null;
                });
        coordinator.runLogicalIteration(
                () -> events.add("fade"), frame -> {
                    frame.claim(PlcLifecyclePhase.LAG);
                    return null;
                });

        assertEquals(List.of(
                "fade", "service:LAG", "fade", "service:LAG"), events);
    }

    @Test
    void publicPlcFrameEntriesRequireTheCallersLatchedToken() {
        List<String> phaseOwned = List.of(
                "execute", "executeWithPause", "serviceVBlankOnly",
                "serviceHardwareVBlankOnly", "dispatchGameVBlank",
                "executeHardwareTimedObjectScan");
        for (var method : LevelFrameStep.class.getDeclaredMethods()) {
            if (Modifier.isPublic(method.getModifiers())
                    && phaseOwned.contains(method.getName())) {
                assertTrue(method.getParameterCount() > 1
                                && method.getParameterTypes()[1]
                                == PlcFrameLifecycleCoordinator.PlcLifecycleFrame.class,
                        method.toString());
            }
        }
    }

    @Test
    void lockedSonic2TitleCardUsesOneTitleCardTokenWithoutOrdinaryNesting()
            throws Exception {
        List<String> events = new ArrayList<>();
        PlcLifecycleService service = recording(events);
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(service);
        GameModule module = mock(GameModule.class);
        LevelInitProfile profile = mock(LevelInitProfile.class);
        when(module.getLevelInitProfile()).thenReturn(profile);
        WorldSession world = mock(WorldSession.class);
        when(world.getGameModule()).thenReturn(module);
        GameplayModeContext gameplay = mock(GameplayModeContext.class);
        when(gameplay.getWorldSession()).thenReturn(world);
        when(gameplay.hardwareTiming()).thenReturn(new HardwareTimingService());
        when(gameplay.runtimeArtCoordinator()).thenReturn(RuntimeArtCoordinator.NONE);
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        when(titleCard.shouldAdvanceVblankClockDuringLockedPhase()).thenReturn(true);
        when(titleCard.shouldReleaseControl()).thenReturn(false);
        doAnswer(ignored -> {
            events.add("provider");
            return null;
        }).when(titleCard).update();

        GameLoop loop = new GameLoop(new InputHandler());
        set(loop, "gameplayMode", gameplay);
        set(loop, "titleCardProvider", titleCard);
        set(loop, "levelManager", mock(com.openggf.level.LevelManager.class));
        set(loop, "camera", mock(com.openggf.camera.Camera.class));
        set(loop, "audioManager", mock(com.openggf.audio.AudioManager.class));
        var frame = coordinator.latchBeforeFadeUpdate();
        set(loop, "activePlcLifecycleFrame", frame);
        Method update = GameLoop.class.getDeclaredMethod(
                "updateTitleCardMode", boolean.class);
        update.setAccessible(true);

        update.invoke(loop, true);
        frame.finish();

        assertEquals(List.of(
                "service:LEVEL_TITLE_CARD",
                "provider",
                "prepare:LEVEL_TITLE_CARD"), events);
        assertTrue(events.stream().noneMatch(
                "service:ORDINARY_LEVEL"::equals));
        verify(profile).serviceLevelLoadVBlank();
    }

    @Test
    void normallyPresentedTitleCardDispatchesOneGameVblankWithoutObjectClockCoupling()
            throws Exception {
        GameModule module = mock(GameModule.class);
        LevelInitProfile profile = mock(LevelInitProfile.class);
        when(module.getLevelInitProfile()).thenReturn(profile);
        WorldSession world = mock(WorldSession.class);
        when(world.getGameModule()).thenReturn(module);
        GameplayModeContext gameplay = mock(GameplayModeContext.class);
        when(gameplay.getWorldSession()).thenReturn(world);
        when(gameplay.hardwareTiming()).thenReturn(new HardwareTimingService());
        when(gameplay.runtimeArtCoordinator()).thenReturn(RuntimeArtCoordinator.NONE);
        TitleCardProvider titleCard = mock(TitleCardProvider.class);
        when(titleCard.shouldReleaseControl()).thenReturn(false);
        when(titleCard.shouldRunPlayerPhysics()).thenReturn(false);

        GameLoop loop = new GameLoop(new InputHandler());
        set(loop, "gameplayMode", gameplay);
        set(loop, "titleCardProvider", titleCard);
        set(loop, "levelManager", mock(com.openggf.level.LevelManager.class));
        set(loop, "camera", mock(com.openggf.camera.Camera.class));
        set(loop, "audioManager", mock(com.openggf.audio.AudioManager.class));
        var coordinator = new PlcFrameLifecycleCoordinator(recording(new ArrayList<>()));
        var frame = coordinator.latchBeforeFadeUpdate();
        set(loop, "activePlcLifecycleFrame", frame);
        Method update = GameLoop.class.getDeclaredMethod("updateTitleCardMode", boolean.class);
        update.setAccessible(true);

        update.invoke(loop, true);
        frame.finish();

        verify(profile).serviceLevelLoadVBlank();
        verify(titleCard).update();
    }

    private static void set(Object target, String fieldName, Object value)
            throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static List<String> runRepresentativeIterations() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));
        coordinator.runLogicalIteration(
                () -> events.add("fade"), frame -> {
                    frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
                    events.add("body");
                    frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
                    return null;
                });
        coordinator.beginNativeBlockingFade();
        coordinator.runLogicalIteration(
                () -> events.add("fade"), frame -> {
                    events.add("body");
                    return null;
                });
        return events;
    }

    private static PlcLifecycleService recording(List<String> events) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                events.add("service:" + phase);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.ORDINARY_LEVEL
                        || phase == PlcLifecyclePhase.PALETTE_FADE
                        || phase == PlcLifecyclePhase.LEVEL_TITLE_CARD;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                events.add("prepare:" + phase);
            }
        };
    }
}
