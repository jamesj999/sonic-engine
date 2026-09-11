package com.openggf.game.sonic3k;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameServices;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;

/**
 * Resolves S3K bootstrap behavior for a zone/act using runtime configuration.
 *
 * <p>Intro start positions are data-driven: add a new zone entry in
 * {@link #getIntroStartPosition(int, int)} to support its intro sequence.
 */
public final class Sonic3kBootstrapResolver {
    // ROM source of truth: sonic3k.asm:38174-38177 (Level_FromSavedGame override)
    // move.w #$40,(Player_1+x_pos).w / move.w #$420,(Player_1+y_pos).w
    private static final int[] AIZ1_INTRO_START_POS = new int[]{0x40, 0x420};
    private static final int[] LBZ1_INTRO_START_POS = new int[]{0x00B0, 0x0650};

    private Sonic3kBootstrapResolver() {
    }

    public static Sonic3kLoadBootstrap resolve(int zone, int act) {
        int[] introPos = getIntroStartPosition(zone, act);
        if (introPos == null) {
            return Sonic3kLoadBootstrap.NORMAL; // Zone has no intro
        }

        // ROM: LoadLevelLoadBlock selects the post-intro AIZ resource profile
        // when Saved2_* state is live for a giant-ring special-stage return
        // (sonic3k.asm:9727-9739). The return reload must not recreate the
        // surfing intro just because the global skip-intros option is disabled.
        var levelManager = GameServices.levelOrNull();
        if (levelManager != null && levelManager.hasBigRingReturn()) {
            return new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null);
        }

        // Zone has an intro — check if we should skip it
        SonicConfigurationService config = GameServices.configuration();
        boolean skipIntros = config.getBoolean(SonicConfiguration.S3K_SKIP_INTROS);
        String mainCharacter = resolveActiveMainCharacter(config);
        boolean nonSonicMain = mainCharacter != null
                && !mainCharacter.isBlank()
                && !"sonic".equalsIgnoreCase(mainCharacter.trim());

        if (skipIntros || nonSonicMain) {
            return new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.SKIP_INTRO, null);
        }

        return new Sonic3kLoadBootstrap(Sonic3kLoadBootstrap.Mode.INTRO, introPos);
    }

    /** Per-zone intro start positions. Returns null if zone has no intro. */
    private static int[] getIntroStartPosition(int zone, int act) {
        if (zone == Sonic3kZoneIds.ZONE_AIZ && act == 0) return AIZ1_INTRO_START_POS.clone();
        if (zone == Sonic3kZoneIds.ZONE_LBZ && act == 0) return LBZ1_INTRO_START_POS.clone();
        // Future zones: add entries here
        return null;
    }

    private static String resolveActiveMainCharacter(SonicConfigurationService config) {
        var worldSession = SessionManager.getCurrentWorldSession();
        if (worldSession != null
                && worldSession.getSaveSessionContext() != null
                && worldSession.getSaveSessionContext().selectedTeam() != null) {
            return worldSession.getSaveSessionContext().selectedTeam().mainCharacter();
        }
        return config.getString(SonicConfiguration.MAIN_CHARACTER_CODE);
    }
}
