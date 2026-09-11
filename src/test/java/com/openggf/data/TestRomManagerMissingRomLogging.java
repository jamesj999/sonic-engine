package com.openggf.data;

import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.game.sonic2.events.Sonic2ZoneEvents;
import com.openggf.level.ParallaxManager;
import com.openggf.game.session.EngineContext;
import com.openggf.game.session.EngineServices;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRomManagerMissingRomLogging {
    @AfterEach
    void tearDown() {
        RomManager.getInstance().close();
        SonicConfigurationService.getInstance().resetToDefaults();
    }

    @Test
    void missingConfiguredRomDoesNotLogSevereFromRomOpen() {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        config.setConfigValue(SonicConfiguration.DEFAULT_ROM, "s2");
        config.setConfigValue(SonicConfiguration.SONIC_2_ROM, "__missing_ci_rom__.gen");
        RomManager.getInstance().close();

        Logger romLogger = Logger.getLogger(Rom.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = capturingHandler(records);
        romLogger.addHandler(handler);
        try {
            assertThrows(IOException.class, () -> RomManager.getInstance().getRom());

            assertTrue(records.stream().noneMatch(TestRomManagerMissingRomLogging::isRomOpenFailure),
                    "missing configured ROM should fail before Rom.open logs a SEVERE stack trace");
        } finally {
            romLogger.removeHandler(handler);
        }
    }

    @Test
    void missingConfiguredRomDoesNotWarnFromScrollOrPaletteProbes() throws Exception {
        EngineServices.configure(EngineContext.fromLegacySingletonsForBootstrap());
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        config.resetToDefaults();
        config.setConfigValue(SonicConfiguration.DEFAULT_ROM, "s2");
        config.setConfigValue(SonicConfiguration.SONIC_2_ROM, "__missing_ci_rom__.gen");
        RomManager.getInstance().close();

        Logger scrollLogger = Logger.getLogger(ParallaxManager.class.getName());
        Logger paletteLogger = Logger.getLogger(Sonic2ZoneEvents.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = capturingHandler(records);
        scrollLogger.addHandler(handler);
        paletteLogger.addHandler(handler);
        try {
            new ParallaxManager().advanceCameraDrivenScroll(0, 0, null, 0);

            Method loadBossPalette = Sonic2ZoneEvents.class.getDeclaredMethod(
                    "loadBossPalette", int.class, int.class);
            loadBossPalette.setAccessible(true);
            loadBossPalette.invoke(null, 1, 0x2E62);

            assertTrue(records.stream().noneMatch(TestRomManagerMissingRomLogging::isWarningOrHigher),
                    "missing configured ROM should not warn from scroll or palette probes");
        } finally {
            scrollLogger.removeHandler(handler);
            paletteLogger.removeHandler(handler);
        }
    }

    private static Handler capturingHandler(List<LogRecord> records) {
        return new Handler() {
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
    }

    private static boolean isRomOpenFailure(LogRecord record) {
        return record.getLevel().intValue() >= Level.SEVERE.intValue()
                && record.getMessage() != null
                && record.getMessage().startsWith("Failed to open ROM:");
    }

    private static boolean isWarningOrHigher(LogRecord record) {
        return record.getLevel().intValue() >= Level.WARNING.intValue();
    }
}
