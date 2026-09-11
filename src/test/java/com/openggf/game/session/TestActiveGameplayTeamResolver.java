package com.openggf.game.session;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameModule;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.save.SaveSessionContext;
import com.openggf.game.save.SelectedTeam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestActiveGameplayTeamResolver {

    private SonicConfigurationService config;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
    }

    @AfterEach
    void tearDown() {
        SessionManager.clear();
        config.resetToDefaults();
    }

    // --- resolveMainCharacterCode ---

    @Test
    void resolveMainCharacterCode_noSession_returnsConfig() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        assertEquals("knuckles", ActiveGameplayTeamResolver.resolveMainCharacterCode(config));
    }

    @Test
    void resolveMainCharacterCode_withSession_prefersSessionOverConfig() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        SessionManager.openGameplaySession(neutralGameModule(),
                SaveSessionContext.noSave("s3k", new SelectedTeam("knuckles", List.of()), 0, 0));
        assertEquals("knuckles", ActiveGameplayTeamResolver.resolveMainCharacterCode(config));
    }

    @Test
    void resolveMainCharacterCode_nullConfig_returnsSonic() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "");
        assertEquals("sonic", ActiveGameplayTeamResolver.resolveMainCharacterCode(config));
    }

    // --- resolvePlayerCharacter ---

    @Test
    void resolvePlayerCharacter_noSession_sonicWithTails_returnsSonicAndTails() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        assertEquals(PlayerCharacter.SONIC_AND_TAILS,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_noSession_sonicAlone_returnsSonicAlone() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "");
        assertEquals(PlayerCharacter.SONIC_ALONE,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_noSession_knuckles_returnsKnuckles() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        assertEquals(PlayerCharacter.KNUCKLES,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_noSession_tails_returnsTailsAlone() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "tails");
        assertEquals(PlayerCharacter.TAILS_ALONE,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_sessionKnuckles_configSonic_returnsKnuckles() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        SessionManager.openGameplaySession(neutralGameModule(),
                SaveSessionContext.noSave("s3k", new SelectedTeam("knuckles", List.of()), 0, 0));
        assertEquals(PlayerCharacter.KNUCKLES,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_sessionSonicWithTails_configKnuckles_returnsSonicAndTails() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "knuckles");
        SessionManager.openGameplaySession(neutralGameModule(),
                SaveSessionContext.noSave("s3k",
                        new SelectedTeam("sonic", List.of("tails")), 0, 0));
        assertEquals(PlayerCharacter.SONIC_AND_TAILS,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_sessionTails_configSonic_returnsTailsAlone() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        SessionManager.openGameplaySession(neutralGameModule(),
                SaveSessionContext.noSave("s3k",
                        new SelectedTeam("tails", List.of()), 0, 0));
        assertEquals(PlayerCharacter.TAILS_ALONE,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolvePlayerCharacter_sessionSonicAlone_configSonicAndTails_returnsSonicAlone() {
        config.setConfigValue(SonicConfiguration.MAIN_CHARACTER_CODE, "sonic");
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails");
        SessionManager.openGameplaySession(neutralGameModule(),
                SaveSessionContext.noSave("s3k",
                        new SelectedTeam("sonic", List.of()), 0, 0));
        assertEquals(PlayerCharacter.SONIC_ALONE,
                ActiveGameplayTeamResolver.resolvePlayerCharacter(config));
    }

    @Test
    void resolveSidekicks_noSession_parsesCommaSeparatedConfig() {
        config.setConfigValue(SonicConfiguration.SIDEKICK_CHARACTER_CODE, "tails, knuckles, sonic");

        assertEquals(List.of("tails", "knuckles", "sonic"),
                ActiveGameplayTeamResolver.resolveSidekicks(config));
    }

    private static GameModule neutralGameModule() {
        GameModule module = mock(GameModule.class);
        when(module.createRuntimeArtCoordinator(
                org.mockito.ArgumentMatchers.any())).thenReturn(RuntimeArtCoordinator.NONE);
        return module;
    }
}
