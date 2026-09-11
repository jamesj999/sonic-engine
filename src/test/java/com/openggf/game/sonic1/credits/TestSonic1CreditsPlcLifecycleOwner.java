package com.openggf.game.sonic1.credits;

import com.openggf.game.resources.NoOpNativeFadeLifecycle;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.resources.PlcLifecycleService;
import com.openggf.graphics.FadeManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestSonic1CreditsPlcLifecycleOwner {

    @Test
    void realSlowFadeKeepsCreditsDemoFadeOwnershipForAllSixtyUpdates() {
        FadeManager fade = new FadeManager();
        Sonic1CreditsManager credits =
                new Sonic1CreditsManager(NoOpNativeFadeLifecycle.INSTANCE, fade);
        credits.beginDemoPlayingForLifecycleTest(1);
        Sonic1EndingProvider provider = new Sonic1EndingProvider();
        provider.bindCreditsManagerForLifecycleTest(credits);
        provider.update();

        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));
        int iterations = 0;
        while (!credits.hasTextReturnRequestForLifecycleTest() && iterations < 70) {
            coordinator.runLogicalIteration(fade::update, frame -> {
                PlcLifecyclePhase phase =
                        provider.plcLifecyclePhaseOverride().orElseThrow();
                frame.claim(phase);
                if (phase == PlcLifecyclePhase.CREDITS_DEMO) {
                    frame.prepareAfterLoop(phase);
                }
                provider.update();
                return null;
            });
            iterations++;
        }

        assertTrue(credits.hasTextReturnRequestForLifecycleTest());
        assertEquals(60, iterations);
        assertEquals(60, events.stream()
                .filter("service:CREDITS_DEMO_FADE"::equals).count());
        assertEquals(0, events.stream()
                .filter("prepare:CREDITS_DEMO_FADE"::equals).count());

        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.CREDITS_DEMO);
            frame.prepareAfterLoop(PlcLifecyclePhase.CREDITS_DEMO);
            return null;
        });
        assertEquals("prepare:CREDITS_DEMO", events.get(events.size() - 1));
    }

    private static PlcLifecycleService recording(List<String> events) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                events.add("service:" + phase);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.CREDITS_DEMO;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                events.add("prepare:" + phase);
            }
        };
    }
}
