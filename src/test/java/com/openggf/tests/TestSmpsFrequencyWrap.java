package com.openggf.tests;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.jupiter.api.Test;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.VirtualSynthesizer;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        seq.advanceSamples(buf.length);

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

        assertTrue(found, "Should have written frequency");

        // YM2612 block field is 3 bits â€” octave 8 wraps to block 0 via & 7.
        // This matches real hardware behavior and is required for S3K tracks
        // that use extreme negative transpose to produce low "fake drum" notes.
        assertEquals(0, block, "Block should wrap to 0 for Octave 8 (3-bit hardware wrap)");
    }
}


