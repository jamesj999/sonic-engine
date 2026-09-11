package com.openggf.game.rewind.encounter;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

@RequiresRom(SonicGame.SONIC_2)
class TestRewindEncounterValidation {

    @Test
    void s2Ehz1EarlyTraversalMatchesAfterRewindReplay() throws Exception {
        RewindEncounterScenario scenario = new RewindEncounterScenario(
                "s2-ehz1-early-traversal",
                SonicGame.SONIC_2,
                "EHZ",
                1,
                "baseline-objects",
                "trace-input traversal before torture-scale dynamic spawns",
                Path.of("src/test/resources/traces/s2/ehz1_fullrun"),
                0,
                0,
                180,
                300,
                List.of("camera", "object-manager", "rings", "sprites"));

        RewindEncounterValidator.assertRewindReplayMatchesForwardRun(scenario);
    }

    @Test
    void s2Ehz1MidRunTraversalMatchesAfterRewindReplay() throws Exception {
        RewindEncounterScenario scenario = new RewindEncounterScenario(
                "s2-ehz1-mid-run-traversal",
                SonicGame.SONIC_2,
                "EHZ",
                1,
                "transient-dynamics",
                "mid-run traversal crossing badnik kills, animals, points popups",
                Path.of("src/test/resources/traces/s2/ehz1_fullrun"),
                0,
                0,
                180,
                1500,
                List.of("camera", "object-manager", "rings", "sprites"));

        RewindEncounterValidator.assertRewindReplayMatchesForwardRun(scenario);
    }
}
