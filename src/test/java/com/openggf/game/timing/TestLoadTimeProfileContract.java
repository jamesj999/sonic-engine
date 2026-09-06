package com.openggf.game.timing;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestLoadTimeProfileContract {

    @Test
    void parsesEverySupportedModeCaseInsensitively() {
        assertEquals(LoadTimeSimulationMode.NONE, LoadTimeSimulationMode.parse("none"));
        assertEquals(LoadTimeSimulationMode.PROFILED, LoadTimeSimulationMode.parse("PROFILED"));
        assertEquals(LoadTimeSimulationMode.FAST, LoadTimeSimulationMode.parse("Fast"));
        assertEquals(LoadTimeSimulationMode.REALISTIC, LoadTimeSimulationMode.parse(" realistic "));
    }

    @Test
    void rejectsUnknownMode() {
        assertThrows(IllegalArgumentException.class,
                () -> LoadTimeSimulationMode.parse("turbo"));
    }

    @Test
    void immediateProfileHasNoDelayOrEligibleBoundaries() {
        LoadTimeDecision decision = LoadTimeProfile.IMMEDIATE.assign(null, null);

        assertEquals(0, decision.serviceFrames());
        assertTrue(decision.eligibleBoundaries().isEmpty());
        assertEquals(LoadTimeDecisionSource.IMMEDIATE, decision.source());
    }

    @Test
    void positiveDelayRequiresAnEligibleBoundary() {
        assertThrows(IllegalArgumentException.class,
                () -> new LoadTimeDecision(
                        1, Set.of(), LoadTimeDecisionSource.MEASURED, "test-v1"));
    }

    @Test
    void fastUsesTheGamesFastManifestWithoutWarning() {
        LoadTimeProfile profiled = (submission, handle) -> new LoadTimeDecision(
                2, Set.of(HardwareServiceBoundary.PRE_MAIN_LOOP),
                LoadTimeDecisionSource.MEASURED, "test-v1");
        LoadTimeProfile fast = (submission, handle) -> new LoadTimeDecision(
                1, Set.of(HardwareServiceBoundary.PRE_MAIN_LOOP),
                LoadTimeDecisionSource.MEASURED, "test-fast-v1");
        List<String> warnings = new ArrayList<>();

        assertSame(fast, LoadTimeProfileFactory.resolve(
                LoadTimeSimulationMode.FAST, profiled, fast, warnings::add));
        assertSame(profiled, LoadTimeProfileFactory.resolve(
                LoadTimeSimulationMode.PROFILED, profiled, fast, warnings::add));
        assertSame(LoadTimeProfile.IMMEDIATE, LoadTimeProfileFactory.resolve(
                LoadTimeSimulationMode.NONE, profiled, fast, warnings::add));
        assertEquals(List.of(), warnings);
    }

    @Test
    void reservedModesWarnAndUseTheirSpecifiedFallbacks() {
        LoadTimeProfile profiled = (submission, handle) -> new LoadTimeDecision(
                2,
                Set.of(HardwareServiceBoundary.PRE_MAIN_LOOP),
                LoadTimeDecisionSource.MEASURED,
                "test-v1");
        List<String> warnings = new ArrayList<>();

        assertSame(LoadTimeProfile.IMMEDIATE,
                LoadTimeProfileFactory.resolve(
                        LoadTimeSimulationMode.FAST, profiled, warnings::add));
        assertSame(profiled,
                LoadTimeProfileFactory.resolve(
                        LoadTimeSimulationMode.REALISTIC, profiled, warnings::add));
        assertEquals(2, warnings.size());
    }
}
