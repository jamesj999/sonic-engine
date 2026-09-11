package com.openggf.game;

import com.openggf.game.timing.LoadTimeDecision;
import com.openggf.game.timing.LoadTimeDecisionSource;
import com.openggf.game.timing.LoadTimeProfile;
import com.openggf.game.timing.LoadTimeSimulationMode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;

class TestGameModuleLoadTimeProfile {

    @Test
    void defaultModuleResolvesReservedModesWithoutGameCarveouts() {
        GameModule module = mock(GameModule.class, CALLS_REAL_METHODS);
        LoadTimeProfile profiled = (submission, handle) -> new LoadTimeDecision(
                1,
                Set.of(com.openggf.game.timing.HardwareServiceBoundary.POST_OBJECTS),
                LoadTimeDecisionSource.MEASURED,
                "test-v1");
        List<String> warnings = new ArrayList<>();

        assertSame(LoadTimeProfile.IMMEDIATE,
                module.createLoadTimeProfile(
                        LoadTimeSimulationMode.FAST, profiled, warnings::add));
        assertSame(profiled,
                module.createLoadTimeProfile(
                        LoadTimeSimulationMode.REALISTIC, profiled, warnings::add));
    }
}
