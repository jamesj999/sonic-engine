package com.openggf.tests.rules;

import com.openggf.tests.TestTempFiles;
import com.openggf.data.Rom;
import com.openggf.tests.RomTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRomCacheAvailability {

    @Test
    void invalidConfiguredRomFileIsUnavailable() throws Exception {
        Path invalidRom = TestTempFiles.createTempFile("openggf-invalid-rom", ".gen");
        Files.writeString(invalidRom, "not a rom");

        String previous = System.getProperty("sonic1.rom.path");
        clearRomCache();
        System.setProperty("sonic1.rom.path", invalidRom.toString());
        try {
            assertNull(RomCache.getRom(SonicGame.SONIC_1),
                    "existing but invalid configured ROM files should not enable @RequiresRom tests");
        } finally {
            restoreProperty("sonic1.rom.path", previous);
            clearRomCache();
            Files.deleteIfExists(invalidRom);
        }
    }

    @Test
    void closedCachedRomIsReopenedOnNextAccess() throws Exception {
        Assumptions.assumeTrue(RomTestUtils.ensureSonic2RomAvailable() != null,
                "Sonic 2 ROM is required for ROM cache close/reopen regression");

        clearRomCache();
        Rom first = RomCache.getRom(SonicGame.SONIC_2);
        assertTrue(first.isOpen(), "initial cached ROM should be open");

        first.close();
        Rom second = RomCache.getRom(SonicGame.SONIC_2);

        try {
            assertNotSame(first, second, "closed cached ROM handle should be discarded");
            assertTrue(second.isOpen(), "replacement cached ROM should be open");
        } finally {
            clearRomCache();
        }
    }

    @SuppressWarnings("unchecked")
    private static void clearRomCache() throws ReflectiveOperationException {
        Field cacheField = RomCache.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        ((Map<SonicGame, ?>) cacheField.get(null)).clear();
    }

    private static void restoreProperty(String key, String previous) {
        if (previous == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previous);
        }
    }
}
