package uk.co.jamesj999.sonic.tests;

import org.junit.Assert;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

final class RomTestUtils {
    private RomTestUtils() {
    }

    private static final String ROM_FILENAME = "Sonic The Hedgehog 2 (W) (REV01) [!].gen";
    private static final String ROM_URL = "http://bluetoaster.net/secretfolder/Sonic%20The%20Hedgehog%202%20%28W%29%20%28REV01%29%20%5B!%5D.gen";
    private static final String ROM_PATH_PROPERTY = "sonic.rom.path";
    private static final String ROM_PATH_ENV = "SONIC_ROM_PATH";

    static File ensureRomAvailable() {
        File romFile = findLocalRom();
        if (romFile == null) {
            romFile = downloadRom();
        }

        Assert.assertTrue("ROM download should create the expected file", romFile.exists());
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

    private static File downloadRom() {
        File romFile = new File(ROM_FILENAME);
        try (InputStream in = new BufferedInputStream(new URL(ROM_URL).openStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(romFile))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Assert.fail("Failed to download ROM from " + ROM_URL + ": " + e.getMessage() +
                    ". Provide the ROM locally via -D" + ROM_PATH_PROPERTY + " or the " + ROM_PATH_ENV + " env var.");
        }
        return romFile;
    }
}
