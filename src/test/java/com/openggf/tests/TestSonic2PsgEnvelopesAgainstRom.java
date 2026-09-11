package com.openggf.tests;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.game.sonic2.audio.smps.Sonic2PsgEnvelopes;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.data.Rom;
import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@RequiresRom(SonicGame.SONIC_2)
public class TestSonic2PsgEnvelopesAgainstRom {
    private Sonic2SmpsLoader loader;

    @BeforeEach
    public void setUp() {
        Rom rom = com.openggf.tests.TestEnvironment.currentRom();
        loader = new Sonic2SmpsLoader(rom);
    }

    @Test
    public void testHardcodedEnvelopesMatchDataFromRom() {
        // Load any music entry to ensure loader extracts PSG envelopes from ROM.
        AbstractSmpsData data = loader.loadMusic(0x81); // Emerald Hill
        assertNotNull(data, "Music data should load");

        for (int id = 1; ; id++) {
            byte[] expected = Sonic2PsgEnvelopes.getEnvelope(id);
            if (expected == null) break;
            byte[] fromRom = data.getPsgEnvelope(id);
            assertNotNull(fromRom, "Envelope " + id + " should exist in ROM-derived data");
            assertArrayEquals(expected, fromRom, "Envelope " + id + " should match ROM-derived data");
        }
    }
}


