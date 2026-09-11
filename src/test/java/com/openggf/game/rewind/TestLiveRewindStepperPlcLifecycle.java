package com.openggf.game.rewind;

import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameResult;
import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.GameModule;
import com.openggf.game.GameStateManager;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLiveRewindStepperPlcLifecycle {

    @Test
    void heldPauseServicesNormalPauseWithoutPreparation() {
        Fixture fixture = fixture(true);

        LevelFrameResult result = fixture.step(false);

        assertEquals(LevelFrameResult.PAUSED, result);
        assertEquals(List.of("service:NORMAL_PAUSE"), fixture.events);
    }

    @Test
    void unpausePressRunsAndPreparesOrdinaryLevel() {
        Fixture fixture = fixture(false);

        LevelFrameResult result = fixture.step(true);

        assertEquals(LevelFrameResult.GAMEPLAY_FRAME, result);
        assertEquals(List.of(
                "service:ORDINARY_LEVEL",
                "prepare:ORDINARY_LEVEL"), fixture.events);
    }

    private static Fixture fixture(boolean remainsPaused) {
        List<String> events = new ArrayList<>();
        PlcLifecycleService service = recording(events);
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(service);
        GameStateManager state = mock(GameStateManager.class);
        when(state.applyPauseToggle(org.mockito.ArgumentMatchers.anyBoolean()))
                .thenReturn(remainsPaused);
        GameModule module = mock(GameModule.class);
        LevelFrameContext context = new LevelFrameContext(
                module, null, null, null, mock(SpriteManager.class), state,
                null, null, new HardwareTimingService(), null,
                RuntimeArtCoordinator.NONE);
        LevelManager level = mock(LevelManager.class);
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        LiveRewindStepper stepper = new LiveRewindStepper(
                inputs, InputHandler::new, () -> context, () -> null);
        return new Fixture(
                stepper, coordinator, context, level,
                mock(SpriteManager.class), events);
    }

    private static PlcLifecycleService recording(List<String> events) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                events.add("service:" + phase);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.ORDINARY_LEVEL;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                events.add("prepare:" + phase);
            }
        };
    }

    private record Fixture(
            LiveRewindStepper stepper,
            PlcFrameLifecycleCoordinator coordinator,
            LevelFrameContext context,
            LevelManager level,
            SpriteManager sprites,
            List<String> events) {
        LevelFrameResult step(boolean startPressed) {
            var frame = coordinator.latchBeforeFadeUpdate();
            LevelFrameResult result = stepper.step(
                    new Bk2FrameInput(
                            1, 0, 0, startPressed, 0, 0, false, "row"),
                    sprites, level, mock(com.openggf.camera.Camera.class),
                    new InputHandler(), frame);
            frame.finish();
            return result;
        }
    }
}
