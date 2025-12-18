package uk.co.jamesj999.sonic.tests;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.Sonic2PsgEnvelopes;
import uk.co.jamesj999.sonic.audio.smps.Sonic2SmpsLoader;
import uk.co.jamesj999.sonic.data.Rom;

import java.io.File;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class TestSonic2PsgEnvelopesAgainstRom {
    private Sonic2SmpsLoader loader;

    @Before
    public void setUp() {
        File romFile = RomTestUtils.ensureRomAvailable();
        Rom rom = new Rom();
        assertTrue("Failed to open ROM", rom.open(romFile.getAbsolutePath()));
        loader = new Sonic2SmpsLoader(rom);
    }

    @Test
    public void testHardcodedEnvelopesMatchDataFromRom() {
        // Load any music entry to ensure loader extracts PSG envelopes from ROM.
        AbstractSmpsData data = loader.loadMusic(0x81); // Emerald Hill
        assertNotNull("Music data should load", data);

        for (int id = 1; ; id++) {
            byte[] expected = Sonic2PsgEnvelopes.getEnvelope(id);
            if (expected == null) break;
            byte[] fromRom = data.getPsgEnvelope(id);
            assertNotNull("Envelope " + id + " should exist in ROM-derived data", fromRom);
            assertArrayEquals("Envelope " + id + " should match ROM-derived data", expected, fromRom);
        }
    }
}
