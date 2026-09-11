package com.openggf.tests;

import com.openggf.game.sonic2.credits.Sonic2CreditsData;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class TestSonic2EndingCreditsDataParity {

    @Test
    public void testObjCaCoreDurationsMatchDisassembly() {
        assertEquals(0x40, Sonic2CreditsData.CHARACTER_APPEAR_DURATION);
        assertEquals(0xC0, Sonic2CreditsData.CAMERA_SCROLL_DURATION);
        assertEquals(0x100, Sonic2CreditsData.TAILS_BOOT_WAIT);
        assertEquals(0x100, Sonic2CreditsData.OBJCC_SPAWN_DELAY);
        assertEquals(0x880, Sonic2CreditsData.OBJCC_SPAWN_DELAY_TAILS_60FPS);
        assertEquals(0x660, Sonic2CreditsData.OBJCC_SPAWN_DELAY_TAILS_50FPS);
    }

    @Test
    public void testSuperSonicDeparturePathMatchesWordA766() {
        assertEquals(30, Sonic2CreditsData.SUPER_SONIC_PATH.length);
        assertArrayEquals(new int[]{0xC0, 0x90}, Sonic2CreditsData.SUPER_SONIC_PATH[0]);
        assertArrayEquals(new int[]{0x9B, 0x96}, Sonic2CreditsData.SUPER_SONIC_PATH[3]);
        assertArrayEquals(new int[]{0xE8, 0xB0}, Sonic2CreditsData.SUPER_SONIC_PATH[15]);
        assertArrayEquals(new int[]{0x101, 0xD1}, Sonic2CreditsData.SUPER_SONIC_PATH[26]);
        assertArrayEquals(new int[]{0xF9, 0x118}, Sonic2CreditsData.SUPER_SONIC_PATH[29]);
    }

    @Test
    public void testSuperSonicDepartureFramesMatchByteA748() {
        int[] expected = {
                0x12, 0x12, 0x12, 0x12, 0x12, 0x12, 0x12,
                0x13, 0x13, 0x13, 0x13, 0x13, 0x13,
                0x14, 0x14, 0x14, 0x14,
                0x15, 0x15, 0x15,
                0x16, 0x16, 0x16, 0x16, 0x16, 0x16, 0x16, 0x16, 0x16,
                0
        };
        assertArrayEquals(expected, Sonic2CreditsData.SUPER_SONIC_FRAMES);
    }
}


