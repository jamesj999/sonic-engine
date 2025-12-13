package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

import java.io.File;
import static org.junit.Assert.*;

public class TestSonicConfigurationService {
    @Test
    public void testUpdateAndSaveConfig() {
        SonicConfigurationService service = SonicConfigurationService.getInstance();

        // Save original value
        boolean originalDebug = service.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);

        File file = new File("config.json");
        boolean existed = file.exists();

        try {
            // Update value
            service.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, !originalDebug);

            // Verify update in memory
            assertEquals(!originalDebug, service.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED));

            // Save to file
            service.saveConfig();

            // Verify file exists
            assertTrue(file.exists());
            assertTrue(file.lastModified() > 0);
        } finally {
            // Cleanup: Restore original value in memory
            service.setConfigValue(SonicConfiguration.DEBUG_VIEW_ENABLED, originalDebug);

            if (!existed) {
                file.delete();
            } else {
                service.saveConfig();
            }
        }
    }

    @Test
    public void testGetters() {
        SonicConfigurationService svc = SonicConfigurationService.getInstance();
        assertEquals(640, svc.getInt(SonicConfiguration.SCREEN_WIDTH));
        assertEquals(320, svc.getShort(SonicConfiguration.SCREEN_WIDTH_PIXELS));
        assertEquals("Sonic The Hedgehog 2 (W) (REV01) [!].gen", svc.getString(SonicConfiguration.ROM_FILENAME));
        assertTrue(svc.getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED));
        assertEquals(1.0, svc.getDouble(SonicConfiguration.SCALE), 0.001);
    }
}
