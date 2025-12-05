package uk.co.jamesj999.sonic.tests;

import org.junit.Assert;
import java.io.File;

final class RomTestUtils {
    private RomTestUtils() {
    }

    private static final String ROM_FILENAME = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";
    private static final String ROM_PATH_PROPERTY = "sonic.rom.path";
    private static final String ROM_PATH_ENV = "SONIC_ROM_PATH";

    static File ensureRomAvailable() {
        File romFile = findLocalRom();
        if (romFile == null) {
            Assert.fail("ROM file not found. Provide the ROM locally via -D" + ROM_PATH_PROPERTY + " or the " + ROM_PATH_ENV + " env var.");
        }

        Assert.assertTrue("ROM file should not be empty", romFile.length() > 0);
        return romFile;
    }

    private static File findLocalRom() {
        String configuredPath = System.getProperty(ROM_PATH_PROPERTY);
        if (configuredPath == null || configuredPath.isEmpty()) {
            configuredPath = System.getenv(ROM_PATH_ENV);
        }

        if (configuredPath != null && !configuredPath.isEmpty()) {
            File configuredRom = new File(configuredPath);
            Assert.assertTrue("Configured ROM path does not exist: " + configuredRom.getAbsolutePath(), configuredRom.exists());
            Assert.assertTrue("Configured ROM file should not be empty", configuredRom.length() > 0);
            return configuredRom;
        }

        File romFile = new File(ROM_FILENAME);
        return romFile.exists() ? romFile : null;
    }
}
