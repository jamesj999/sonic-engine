package com.openggf.game.sonic1.credits;

import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.graphics.FadeManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestPlcProviderOwnedFadeLifecycle {

    @Test
    void postCreditsCompletionStartsRevealAfterOutgoingToken() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));
        FadeManager fade = new FadeManager();
        AtomicInteger initializations = new AtomicInteger();
        Sonic1EndingProvider provider =
                new Sonic1EndingProvider(fade, initializations::incrementAndGet);
        provider.bindNativeFadeLifecycle(coordinator);
        provider.transitionToPostCreditsForLifecycleTest();

        runFadeToCompletion(coordinator, fade, provider, initializations);

        assertEquals(1, initializations.get());
        assertEquals(List.of("service:PALETTE_FADE", "prepare:PALETTE_FADE"),
                events.subList(events.size() - 2, events.size()));

        coordinator.runLogicalIteration(fade::update, frame -> null);
        assertEquals(List.of("service:PALETTE_FADE", "prepare:PALETTE_FADE"),
                events.subList(events.size() - 2, events.size()));
    }

    private static void runFadeToCompletion(PlcFrameLifecycleCoordinator coordinator,
                                            FadeManager fade,
                                            Sonic1EndingProvider provider,
                                            AtomicInteger initializations) {
        int frames = 0;
        while (frames++ < 30 && initializations.get() == 0) {
            coordinator.runLogicalIteration(fade::update, frame -> {
                provider.update();
                return null;
            });
        }
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
