package com.openggf.game.sonic3k;

import com.openggf.data.Rom;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.tests.RomTestUtils;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_3K)
class TestSonic3kLifeIconAddresses {

    private static final int TAILS_ICON_BYTE_COUNT = 32;
    private static final String TAILS_ICON_SHA1 = "414cb08a2cd2039e0e6b2b4308a84bf5c39a55b1";

    @Test
    void tailsLifeIconAddressMatchesCanonicalRomPayload() throws Exception {
        File romFile = RomTestUtils.ensureSonic3kRomAvailable();

        byte[] actual;
        try (Rom rom = new Rom()) {
            assertTrue(rom.open(romFile.getAbsolutePath()));
            actual = rom.readBytes(Sonic3kConstants.ART_NEM_TAILS_LIFE_ICON_ADDR, TAILS_ICON_BYTE_COUNT);
        }

        assertEquals(TAILS_ICON_SHA1,
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(actual)),
                "ART_NEM_TAILS_LIFE_ICON_ADDR should point at ArtNem_TailsLifeIcon in the combined S3&K ROM");
    }
}
