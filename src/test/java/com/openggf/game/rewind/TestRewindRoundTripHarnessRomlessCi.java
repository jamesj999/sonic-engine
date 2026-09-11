package com.openggf.game.rewind;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.data.Rom;
import com.openggf.data.RomManager;
import com.openggf.game.rewind.RewindRoundTripHarness.RoundTripSweepResult;
import com.openggf.game.sonic3k.objects.S3kResultsScreenObjectInstance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRewindRoundTripHarnessRomlessCi {
    private static final String FORCE_ROMLESS_CI_PROPERTY =
            "openggf.rewind.harness.forceRomless";

    @AfterEach
    void tearDown() {
        System.clearProperty(FORCE_ROMLESS_CI_PROPERTY);
        RomManager.getInstance().close();
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void romlessCiSurrogateDoesNotTryToOpenMissingResultsScreenRom() {
        System.setProperty(FORCE_ROMLESS_CI_PROPERTY, "true");
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        config.setConfigValue(SonicConfiguration.DEFAULT_ROM, "s2");
        config.setConfigValue(SonicConfiguration.SONIC_2_ROM, "__missing_ci_rom__.gen");
        RomManager.getInstance().close();

        Logger romLogger = Logger.getLogger(Rom.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                records.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        romLogger.addHandler(handler);
        try {
            RoundTripSweepResult result = RewindRoundTripHarness.probeClass(
                    S3kResultsScreenObjectInstance.class.getName());

            assertInstanceOf(RoundTripSweepResult.Passed.class, result);
            assertTrue(records.stream().noneMatch(TestRewindRoundTripHarnessRomlessCi::isRomOpenFailure),
                    "romless CI surrogate must not attempt to open absent copyrighted ROM assets");
        } finally {
            romLogger.removeHandler(handler);
        }
    }

    private static boolean isRomOpenFailure(LogRecord record) {
        return record.getLevel().intValue() >= Level.SEVERE.intValue()
                && record.getMessage() != null
                && record.getMessage().startsWith("Failed to open ROM:");
    }
}
