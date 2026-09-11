package com.openggf.game.session;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.PlayerCharacter;

import java.util.List;

/**
 * Resolves the active gameplay team independently from persistent config.
 * <p>
 * Donated Data Select launches keep the chosen team in the current
 * {@link com.openggf.game.save.SaveSessionContext}; gameplay systems should
 * prefer that session-owned team over {@link SonicConfiguration#MAIN_CHARACTER_CODE},
 * which remains the user's default preference for non-session startup.
 */
public final class ActiveGameplayTeamResolver {

    private ActiveGameplayTeamResolver() {
    }

    /**
     * Returns the active main character for the current gameplay/editor world session.
     * Falls back to the configured default when no session-owned team is active.
     */
    public static String resolveMainCharacterCode(SonicConfigurationService configService) {
        WorldSession worldSession = SessionManager.getCurrentWorldSession();
        if (worldSession != null
                && worldSession.getSaveSessionContext() != null
                && worldSession.getSaveSessionContext().selectedTeam() != null) {
            return worldSession.getSaveSessionContext().selectedTeam().mainCharacter();
        }

        String configured = configService.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
        if (configured == null || configured.isBlank()) {
            return "sonic";
        }
        return configured;
    }

    /**
     * Returns the {@link PlayerCharacter} for the current gameplay session.
     * Checks session-owned team first (Data Select), falls back to config.
     */
    public static PlayerCharacter resolvePlayerCharacter(SonicConfigurationService configService) {
        String mainCode = resolveMainCharacterCode(configService);
        if ("knuckles".equalsIgnoreCase(mainCode)) {
            return PlayerCharacter.KNUCKLES;
        }
        if ("tails".equalsIgnoreCase(mainCode)) {
            return PlayerCharacter.TAILS_ALONE;
        }
        // Sonic — check sidekick to distinguish SONIC_ALONE vs SONIC_AND_TAILS
        List<String> sidekicks = resolveSidekicks(configService);
        if (sidekicks.isEmpty()) {
            return PlayerCharacter.SONIC_ALONE;
        }
        return PlayerCharacter.SONIC_AND_TAILS;
    }

    /**
     * Returns the sidekick list for the current gameplay session.
     * Checks session-owned team first (Data Select), falls back to config.
     */
    public static List<String> resolveSidekicks(SonicConfigurationService configService) {
        WorldSession worldSession = SessionManager.getCurrentWorldSession();
        if (worldSession != null
                && worldSession.getSaveSessionContext() != null
                && worldSession.getSaveSessionContext().selectedTeam() != null) {
            return List.copyOf(worldSession.getSaveSessionContext().selectedTeam().sidekicks());
        }
        String sidekickCode = configService.getString(SonicConfiguration.SIDEKICK_CHARACTER_CODE);
        return parseConfiguredSidekicks(sidekickCode);
    }

    /**
     * Parses the comma-separated sidekick configuration used by gameplay startup.
     */
    public static List<String> parseConfiguredSidekicks(String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
