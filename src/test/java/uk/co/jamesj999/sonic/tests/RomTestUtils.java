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
    private static final String ROM_URL = "http://bluetoaster.net/secretfolder/Sonic%20The%20Hedgehog%202%20(W)%20(REV01)%20%5B!%5D.gen";

    static File ensureRomAvailable() {
        File romFile = new File(ROM_FILENAME);
        if (romFile.exists()) {
            return romFile;
        }

        try (InputStream in = new BufferedInputStream(new URL(ROM_URL).openStream());
             BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(romFile))) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        } catch (IOException e) {
            Assert.fail("Failed to download ROM from " + ROM_URL + ": " + e.getMessage());
        }

        Assert.assertTrue("ROM download should create the expected file", romFile.exists());
        Assert.assertTrue("ROM file should not be empty", romFile.length() > 0);
        return romFile;
    }
}
