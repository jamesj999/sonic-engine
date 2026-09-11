package com.openggf.testmode;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceRenderVisibilityTest {

    @BeforeEach
    @AfterEach
    void resetConfig() {
        // These tests mutate the config singleton; reset before and after so
        // neither dev environment nor sibling tests leak state across runs.
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void readsAllThreeFlagsIndependentlyFromConfig() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, true);
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_GAME_HUD, false);
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_DEBUG_HUD, true);

        TraceRenderVisibility vis = TraceRenderVisibility.fromConfig(config);
        assertTrue(vis.showGhosts());
        assertFalse(vis.showGameHud());
        assertTrue(vis.showDebugHud());
    }

    @Test
    void reflectsFlippedFlags() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, false);
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_GAME_HUD, true);
        config.setConfigValue(SonicConfiguration.TRACE_SHOW_DEBUG_HUD, false);

        TraceRenderVisibility vis = TraceRenderVisibility.fromConfig(config);
        assertFalse(vis.showGhosts());
        assertTrue(vis.showGameHud());
        assertFalse(vis.showDebugHud());
    }

    @Test
    void allFlagCombinationsReuseImmutableFlyweightsAndObserveSameFrameToggles() {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        TraceRenderVisibility[] identities = new TraceRenderVisibility[8];
        for (int bits = 0; bits < 8; bits++) {
            config.setConfigValue(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, (bits & 1) != 0);
            config.setConfigValue(SonicConfiguration.TRACE_SHOW_GAME_HUD, (bits & 2) != 0);
            config.setConfigValue(SonicConfiguration.TRACE_SHOW_DEBUG_HUD, (bits & 4) != 0);
            identities[bits] = TraceRenderVisibility.fromConfig(config);
            assertSame(identities[bits], TraceRenderVisibility.fromConfig(config));
            assertEquals((bits & 1) != 0, identities[bits].showGhosts());
            assertEquals((bits & 2) != 0, identities[bits].showGameHud());
            assertEquals((bits & 4) != 0, identities[bits].showDebugHud());
        }

        assertSame(identities[3], TraceRenderVisibility.defaults());
        for (int left = 0; left < identities.length; left++) {
            for (int right = left + 1; right < identities.length; right++) {
                assertNotSame(identities[left], identities[right]);
            }
        }

        for (int bits = 0; bits < 8; bits++) {
            config.setConfigValue(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS, (bits & 1) != 0);
            config.setConfigValue(SonicConfiguration.TRACE_SHOW_GAME_HUD, (bits & 2) != 0);
            config.setConfigValue(SonicConfiguration.TRACE_SHOW_DEBUG_HUD, (bits & 4) != 0);
            assertSame(identities[bits], TraceRenderVisibility.fromConfig(config),
                    "same-frame config reads must select the matching flyweight");
        }
    }
}
