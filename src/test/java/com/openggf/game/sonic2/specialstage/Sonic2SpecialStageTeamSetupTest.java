package com.openggf.game.sonic2.specialstage;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic2.debug.Sonic2SpecialStageSpriteDebug;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Verifies that {@link Sonic2SpecialStageManager#setupPlayers()} resolves the
 * active team through the standard two-key config
 * ({@code MAIN_CHARACTER_CODE} + {@code SIDEKICK_CHARACTER_CODE}) via
 * {@link com.openggf.game.session.ActiveGameplayTeamResolver}, rather than the
 * unreachable {@code "sonic_and_tails"} literal.
 */
class Sonic2SpecialStageTeamSetupTest {

    @TempDir
    Path tempDir;

    @Test
    void standardTwoKeyTeamConfigSpawnsSonicAndTails() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");

        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager(
                new Sonic2SpecialStageSpriteDebug(), config, null);

        manager.setupPlayersForTest();

        assertNotNull(manager.getSonicPlayer(), "Sonic should spawn as team leader");
        assertNotNull(manager.getTailsPlayer(), "Tails should spawn as team sidekick");
        assertEquals(2, manager.getPlayers().size());
    }

    @Test
    void soloTailsConfigStillSpawnsTailsAlone() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");

        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager(
                new Sonic2SpecialStageSpriteDebug(), config, null);

        manager.setupPlayersForTest();

        assertNull(manager.getSonicPlayer());
        assertNotNull(manager.getTailsPlayer());
        assertEquals(1, manager.getPlayers().size());
    }

    @Test
    void soloSonicConfigSpawnsSonicAlone() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone(tempDir);
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        // SIDEKICK_CHARACTER_CODE defaults to "tails"; disable it explicitly for the solo case.
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");

        Sonic2SpecialStageManager manager = new Sonic2SpecialStageManager(
                new Sonic2SpecialStageSpriteDebug(), config, null);

        manager.setupPlayersForTest();

        assertNotNull(manager.getSonicPlayer());
        assertNull(manager.getTailsPlayer());
        assertEquals(1, manager.getPlayers().size());
    }
}
