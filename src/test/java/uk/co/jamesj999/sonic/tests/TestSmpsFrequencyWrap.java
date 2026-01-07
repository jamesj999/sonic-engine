package uk.co.jamesj999.sonic.tests;
import uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.game.sonic2.audio.smps.Sonic2SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestSmpsFrequencyWrap {

    static class FmWrite {
        int port;
        int reg;
        int val;

        FmWrite(int p, int r, int v) {
            port = p;
            reg = r;
            val = v;
        }
    }

    static class MockSynthesizer extends VirtualSynthesizer {
        List<FmWrite> writes = new ArrayList<>();

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            // Log frequency writes (A0/A4)
            if ((reg & 0xF0) == 0xA0) {
                writes.add(new FmWrite(port, reg, val));
            }
            super.writeFm(source, port, reg, val);
        }
    }

    @Test
    public void testHighNoteWrapping() {
        // Construct SMPS data
        byte[] data = new byte[100];

        // Header
        data[0] = 0x28; data[1] = 0x00; // Voice Ptr (Little Endian 0x0028 = 40)
        data[2] = 2; // 2 FM Channels (FM1 is Index 1)
        data[3] = 0; // 0 PSG
        data[5] = (byte) 0x80; // Tempo

        // FM Track 1 Ptr at offset 0x0A (10) -> 0x0010 (16)
        data[10] = 0x10;
        data[11] = 0x00;

        // Track Data at 16
        int t = 16;

        // 1. Set Voice (EF 00)
        data[t++] = (byte) 0xEF;
        data[t++] = 0x00;

        // 2. Set Key Displacement +12 semitones (1 octave) (E9 0C)
        data[t++] = (byte) 0xE9;
        data[t++] = 12;

        // 3. Play Note 0xDF (Highest note 7A#).
        // 0xDF is 7A#. Octave 7.
        // With +12 displacement, it should become 8A# (Octave 8).
        data[t++] = (byte) 0xDF;
        data[t++] = 0x01; // Duration

        // Voice Data at 40
        int v = 40;
        for(int i=0; i<25; i++) {
            data[v+i] = 0;
        }

        AbstractSmpsData smps = new Sonic2SmpsData(data, 0);
        MockSynthesizer synth = new MockSynthesizer();
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());
        SmpsSequencer seq = new SmpsSequencer(smps, dac, synth, Sonic2SmpsSequencerConfig.CONFIG);

        short[] buf = new short[2000];
        seq.read(buf);

        // Analyze writes
        // We expect a write to A4 (Block/FNum MSB)
        // Reg A4.
        boolean found = false;
        int block = -1;
        for (FmWrite w : synth.writes) {
            if ((w.reg & 0xFF) == 0xA4) { // Channel 0 (A4)
                block = (w.val >> 3) & 0x7;
                // We are looking for the LAST frequency write, which corresponds to our note.
                // (Note: there might be other writes if initialization happens, but playNote writes frequency last).
                found = true;
            }
        }

        assertTrue("Should have written frequency", found);

        // Assert that Block is NOT 0.
        // If it wraps, block will be 0.
        // If fixed, block should be 7.
        // We fail if it is 0.
        assertTrue("Block should not wrap to 0 for high notes. Got: " + block, block != 0);
        assertEquals("Block should be 7 for Octave 8", 7, block);
    }
}
