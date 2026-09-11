package com.openggf.game.session;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.GameModule;
import com.openggf.game.timing.LoadTimeSimulationMode;
import com.openggf.tests.SingletonResetExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

@ExtendWith(SingletonResetExtension.class)
class TestWorldSessionLoadTimeMode {
    @Test
    void modeIsResolvedOnceAndSurvivesContextReconstruction() {
        SonicConfigurationService configuration =
                SonicConfigurationService.getInstance();
        configuration.setConfigValue(
                SonicConfiguration.LOAD_TIME_SIMULATION, "PROFILED");
        WorldSession session = new WorldSession(mock(GameModule.class));

        assertEquals(
                LoadTimeSimulationMode.PROFILED,
                session.loadTimeSimulationMode());

        configuration.setConfigValue(
                SonicConfiguration.LOAD_TIME_SIMULATION, "NONE");

        assertEquals(
                LoadTimeSimulationMode.PROFILED,
                session.loadTimeSimulationMode());
    }
}
