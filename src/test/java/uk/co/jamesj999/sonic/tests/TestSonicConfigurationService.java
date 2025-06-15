package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

import static org.junit.Assert.*;

public class TestSonicConfigurationService {
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
