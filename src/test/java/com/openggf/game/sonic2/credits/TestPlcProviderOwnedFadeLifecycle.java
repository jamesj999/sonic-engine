package com.openggf.game.sonic2.credits;

import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.graphics.FadeManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPlcProviderOwnedFadeLifecycle {

    @Test
    void creditsSlideCompletionStartsRevealAfterOutgoingToken() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));
        FadeManager fade = new FadeManager();
        Sonic2EndingProvider provider = new Sonic2EndingProvider(fade);
        provider.bindNativeFadeLifecycle(coordinator);
        provider.beginCreditsTextExitForLifecycleTest();
        provider.update();

        for (int frame = 0; frame < Sonic2CreditsData.FADE_DURATION; frame++) {
            coordinator.runLogicalIteration(fade::update, token -> {
                provider.update();
                return null;
            });
        }

        assertEquals(List.of("service:PALETTE_FADE", "prepare:PALETTE_FADE"),
                events.subList(events.size() - 2, events.size()));

        coordinator.runLogicalIteration(fade::update, frame -> null);
        assertEquals(List.of("service:PALETTE_FADE", "prepare:PALETTE_FADE"),
                events.subList(events.size() - 2, events.size()));
    }

    private static PlcLifecycleService recording(List<String> events) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                events.add("service:" + phase);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.PALETTE_FADE;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                events.add("prepare:" + phase);
            }
        };
    }
}
