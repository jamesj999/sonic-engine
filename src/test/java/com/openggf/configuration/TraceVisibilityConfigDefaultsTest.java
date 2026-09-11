package com.openggf.configuration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TraceVisibilityConfigDefaultsTest {

    @Test
    void traceVisibilityDefaults() {
        SonicConfigurationService config = SonicConfigurationService.createStandalone();
        assertTrue(config.getBoolean(SonicConfiguration.TRACE_SHOW_DESYNC_GHOSTS),
                "ghosts default on");
        assertTrue(config.getBoolean(SonicConfiguration.TRACE_SHOW_GAME_HUD),
                "game HUD default on");
        assertFalse(config.getBoolean(SonicConfiguration.TRACE_SHOW_DEBUG_HUD),
                "debug HUD default off");
    }
}
