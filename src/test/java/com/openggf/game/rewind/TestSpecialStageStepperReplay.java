package com.openggf.game.rewind;

import com.openggf.control.InputHandler;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.game.ResultsScreen;
import com.openggf.game.SpecialStageAccessType;
import com.openggf.game.SpecialStageDebugProvider;
import com.openggf.game.SpecialStageProvider;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.WorldSession;
import com.openggf.game.GameModule;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.graphics.FadeManager;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestSpecialStageStepperReplay {

    @Test
    void stepMapsInputAndUpdatesProviderInOrder() {
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        FakeSpecialStageProvider provider = new FakeSpecialStageProvider();
        SpecialStageStepper stepper = stepper(inputs, input, () -> provider);

        stepper.step(new Bk2FrameInput(1, 0x0C, 0x03, true, 0x01, 0x04, false, "row"));

        assertIterableEquals(List.of(
                "handleInput:220:220",
                "handlePlayer2Input:33:33",
                "update"), provider.calls);
        assertFalse(input.hasLogicalOverride());
    }

    @Test
    void clearsLogicalOverrideWhenProviderThrows() {
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        FakeSpecialStageProvider provider = new FakeSpecialStageProvider();
        RuntimeException expected = new RuntimeException("update failed");
        provider.updateFailure = expected;
        SpecialStageStepper stepper = stepper(inputs, input, () -> provider);

        RuntimeException actual = assertThrows(RuntimeException.class,
                () -> stepper.step(new Bk2FrameInput(1, 0x0C, 0x03, true, 0x01, 0x04, false, "row")));

        assertEquals(expected, actual);
        assertFalse(input.hasLogicalOverride());
    }

    @Test
    void providerIsResolvedEveryStep() {
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        FakeSpecialStageProvider first = new FakeSpecialStageProvider();
        FakeSpecialStageProvider second = new FakeSpecialStageProvider();
        AtomicInteger supplierCalls = new AtomicInteger();
        SpecialStageProvider[] providers = { first, second };
        SpecialStageStepper stepper = stepper(
                inputs, input,
                () -> providers[Math.min(
                        supplierCalls.getAndIncrement(), providers.length - 1)]);

        Bk2FrameInput row = new Bk2FrameInput(1, 0x0C, 0x03, true, 0x01, 0x04, false, "row");
        stepper.step(row);
        stepper.step(row);

        assertIterableEquals(List.of("handleInput:220:220", "handlePlayer2Input:33:33", "update"),
                first.calls);
        assertIterableEquals(List.of("handleInput:220:220", "handlePlayer2Input:33:33", "update"),
                second.calls);
    }

    @Test
    void finishingDuringReplayDoesNotDispatchResults() {
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        FakeSpecialStageProvider provider = new FakeSpecialStageProvider();
        provider.finishDuringUpdate = true;
        SpecialStageStepper stepper = stepper(inputs, input, () -> provider);

        stepper.step(new Bk2FrameInput(1, 0x0C, 0x03, true, 0x01, 0x04, false, "row"));

        assertIterableEquals(List.of(
                "handleInput:220:220",
                "handlePlayer2Input:33:33",
                "update"), provider.calls);
        assertEquals(0, provider.resultScreenCreations);
    }

    @Test
    void productionStepperOwnsSpecialStageServiceScanAndPreparation() {
        List<String> lifecycle = new ArrayList<>();
        PlcLifecycleService service = new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                lifecycle.add("service-three:" + phase);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.SPECIAL_STAGE;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                lifecycle.add("prepare:" + phase);
            }
        };
        LiveRewindInputSource inputs = new LiveRewindInputSource();
        InputHandler input = new InputHandler();
        FakeSpecialStageProvider provider = new FakeSpecialStageProvider() {
            @Override
            public void update() {
                lifecycle.add("provider");
                super.update();
            }
        };
        GameplayModeContext gameplayMode = gameplayMode(service);
        SpecialStageStepper stepper = new SpecialStageStepper(
                inputs, () -> input, () -> provider, () -> gameplayMode);

        stepper.step(new Bk2FrameInput(
                1, 0, 0, false, 0, 0, false, "row"));

        assertEquals(List.of(
                "service-three:SPECIAL_STAGE",
                "provider",
                "prepare:SPECIAL_STAGE"), lifecycle);
    }

    private static SpecialStageStepper stepper(
            LiveRewindInputSource inputs,
            InputHandler input,
            java.util.function.Supplier<SpecialStageProvider> provider) {
        GameplayModeContext gameplayMode = gameplayMode(null);
        return new SpecialStageStepper(
                inputs, () -> input, provider, () -> gameplayMode);
    }

    private static GameplayModeContext gameplayMode(PlcLifecycleService service) {
        GameplayModeContext gameplayMode = mock(GameplayModeContext.class);
        GameModule module = mock(GameModule.class);
        WorldSession world = mock(WorldSession.class);
        when(world.getGameModule()).thenReturn(module);
        when(gameplayMode.getWorldSession()).thenReturn(world);
        when(gameplayMode.plcFrameLifecycle()).thenReturn(
                new PlcFrameLifecycleCoordinator(service));
        when(gameplayMode.getFadeManager()).thenReturn(mock(FadeManager.class));
        when(gameplayMode.hardwareTiming()).thenReturn(new HardwareTimingService());
        when(gameplayMode.runtimeArtCoordinator()).thenReturn(RuntimeArtCoordinator.NONE);
        return gameplayMode;
    }

    private static class FakeSpecialStageProvider implements SpecialStageProvider {
        private final List<String> calls = new ArrayList<>();
        private RuntimeException updateFailure;
        private boolean finishDuringUpdate;
        private boolean finished;
        private int resultScreenCreations;

        @Override
        public void initialize() throws IOException {
        }

        @Override
        public void update() {
            calls.add("update");
            if (finishDuringUpdate) {
                finished = true;
            }
            if (updateFailure != null) {
                throw updateFailure;
            }
        }

        @Override
        public void draw() {
        }

        @Override
        public void handleInput(int heldButtons, int pressedButtons) {
            calls.add("handleInput:" + heldButtons + ":" + pressedButtons);
        }

        @Override
        public void handlePlayer2Input(int heldButtons, int logicalButtons) {
            calls.add("handlePlayer2Input:" + heldButtons + ":" + logicalButtons);
        }

        @Override
        public boolean isFinished() {
            calls.add("isFinished");
            return finished;
        }

        @Override
        public void reset() {
        }

        @Override
        public boolean isInitialized() {
            return true;
        }

        @Override
        public boolean hasSpecialStages() {
            return true;
        }

        @Override
        public SpecialStageAccessType getAccessType() {
            return SpecialStageAccessType.GIANT_RING;
        }

        @Override
        public void initializeStage(int stageIndex) throws IOException {
        }

        @Override
        public int getCurrentStage() {
            return 0;
        }

        @Override
        public boolean isEmeraldCollected() {
            return false;
        }

        @Override
        public int getEmeraldIndex() {
            return -1;
        }

        @Override
        public int getRingsCollected() {
            return 0;
        }

        @Override
        public void setEmeraldCollected(boolean collected) {
        }

        @Override
        public boolean isSpriteDebugMode() {
            return false;
        }

        @Override
        public void toggleSpriteDebugMode() {
        }

        @Override
        public void cyclePlaneDebugMode() {
        }

        @Override
        public SpecialStageDebugProvider getDebugProvider() {
            return null;
        }

        @Override
        public boolean isAlignmentTestMode() {
            return false;
        }

        @Override
        public void toggleAlignmentTestMode() {
        }

        @Override
        public void adjustAlignmentOffset(int delta) {
        }

        @Override
        public void adjustAlignmentSpeed(double delta) {
        }

        @Override
        public void toggleAlignmentStepMode() {
        }

        @Override
        public void renderAlignmentOverlay(int viewportWidth, int viewportHeight) {
        }

        @Override
        public void renderLagCompensationOverlay(int viewportWidth, int viewportHeight) {
        }

        @Override
        public void setLagCompensation(double factor) {
        }

        @Override
        public ResultsScreen createResultsScreen(int ringsCollected, boolean gotEmerald,
                int stageIndex, int totalEmeraldCount) {
            resultScreenCreations++;
            return null;
        }
    }
}
