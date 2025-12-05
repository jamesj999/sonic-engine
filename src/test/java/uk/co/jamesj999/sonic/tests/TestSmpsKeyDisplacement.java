package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TestSmpsKeyDisplacement {

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
        public void writeFm(int port, int reg, int val) {
            if ((reg & 0xF0) == 0xA0) {
                writes.add(new FmWrite(port, reg, val));
            }
            super.writeFm(port, reg, val);
        }
    }

    @Test
    public void testKeyDisplacementAccumulation() {
        byte[] data = new byte[100];

        // Header
        data[0] = 0x00; data[1] = 0x28; // Voice Ptr (Big Endian 0028 = 40)
        data[2] = 1; // 1 FM Channel
        data[3] = 0; // 0 PSG
        data[5] = (byte) 0x80; // Tempo

        // FM Track 1 Ptr
        data[10] = 0x00;
        data[11] = 0x10;

        // Track Data at 16
        int t = 16;

        // 1. Set Voice (EF 00)
        data[t++] = (byte) 0xEF;
        data[t++] = 0x00;

        // 2. Key Displacement +12 (E9 0C)
        data[t++] = (byte) 0xE9;
        data[t++] = 12;

        // 3. Key Displacement +12 (E9 0C) - Should accumulate to +24
        data[t++] = (byte) 0xE9;
        data[t++] = 12;

        // 4. Play Note 0x81 (C).
        // 0x81 is C0.
        // +24 semitones -> C2.
        // C2 is 0x99.
        // n = 0x81 - 0x81 + 24 = 24.
        // Octave = 24 / 12 = 2.
        // If accumulation fails, Octave = 1.
        data[t++] = (byte) 0x81;
        data[t++] = 0x01; // Duration

        // Voice Data
        int v = 40;
        for(int i=0; i<25; i++) data[v+i] = 0;

        SmpsData smps = new SmpsData(data, 0, false);
        MockSynthesizer synth = new MockSynthesizer();
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());
        SmpsSequencer seq = new SmpsSequencer(smps, dac, synth);

        short[] buf = new short[2000];
        seq.read(buf);

        // Analyze writes
        int block = -1;
        for (FmWrite w : synth.writes) {
            if ((w.reg & 0xFF) == 0xA4) {
                block = (w.val >> 3) & 0x7;
            }
        }

        // Expect Octave 2
        assertEquals("Block should be 2 (Accumulated KeyOffset)", 2, block);
    }
}
