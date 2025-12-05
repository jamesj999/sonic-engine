package uk.co.jamesj999.sonic.tests;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/**
 * Verifies SMPS header pointer endian handling switches based on ROM choice.
 */
public class TestSmpsDataEndianParsing {
    private Map<String, Object> originalConfig;

    @Before
    public void stashConfig() throws Exception {
        SonicConfigurationService svc = SonicConfigurationService.getInstance();
        Field configField = SonicConfigurationService.class.getDeclaredField("config");
        configField.setAccessible(true);
        originalConfig = new HashMap<>((Map<String, Object>) configField.get(svc));
    }

    @After
    public void restoreConfig() throws Exception {
        SonicConfigurationService svc = SonicConfigurationService.getInstance();
        Field configField = SonicConfigurationService.class.getDeclaredField("config");
        configField.setAccessible(true);
        configField.set(svc, originalConfig);
    }

    @Test
    public void testLittleEndianParsingForSonic2() throws Exception {
        setRomName("Sonic The Hedgehog 2 (W) (REV01) [!].gen");
        // voice ptr bytes little-endian: 0x34 0x12 -> 0x1234 expected
        byte[] data = new byte[8];
        data[0] = 0x34;
        data[1] = 0x12;
        SmpsData smps = new SmpsData(data);
        assertEquals(0x1234, smps.getVoicePtr());
    }

    @Test
    public void testBigEndianParsingForSonic1() throws Exception {
        setRomName("Sonic The Hedgehog 1 (W) [!].gen");
        // voice ptr bytes big-endian: 0x12 0x34 -> 0x1234 expected
        byte[] data = new byte[8];
        data[0] = 0x12;
        data[1] = 0x34;
        SmpsData smps = new SmpsData(data);
        assertEquals(0x1234, smps.getVoicePtr());
    }

    private void setRomName(String name) throws Exception {
        SonicConfigurationService svc = SonicConfigurationService.getInstance();
        Field configField = SonicConfigurationService.class.getDeclaredField("config");
        configField.setAccessible(true);
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(SonicConfiguration.ROM_FILENAME.name(), name);
        configField.set(svc, cfg);
    }
}
