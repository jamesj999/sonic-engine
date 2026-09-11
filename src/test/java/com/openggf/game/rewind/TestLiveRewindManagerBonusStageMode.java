package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rewind mode gate must treat BONUS_STAGE as a rewindable mode (alongside
 * LEVEL) so Gumball/Pachinko can record and engage held rewind, while genuinely
 * non-rewindable modes (e.g. TITLE_SCREEN) stay excluded.
 */
class TestLiveRewindManagerBonusStageMode {

    private SonicConfigurationService config;

    @BeforeEach
    void setUp() {
        config = SonicConfigurationService.getInstance();
    }

    private static boolean isRewindableMode(GameMode mode) throws Exception {
        Method m = LiveRewindManager.class.getDeclaredMethod("isRewindableMode", GameMode.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, mode);
    }

    @Test
    void levelAndBonusStageAreRewindableModes() throws Exception {
        assertTrue(isRewindableMode(GameMode.LEVEL));
        assertTrue(isRewindableMode(GameMode.BONUS_STAGE));
    }

    @Test
    void nonGameplayModesAreNotRewindable() throws Exception {
        assertFalse(isRewindableMode(GameMode.TITLE_SCREEN));
        assertFalse(isRewindableMode(GameMode.SPECIAL_STAGE_RESULTS));
        assertTrue(isRewindableMode(GameMode.SPECIAL_STAGE));
    }
}
