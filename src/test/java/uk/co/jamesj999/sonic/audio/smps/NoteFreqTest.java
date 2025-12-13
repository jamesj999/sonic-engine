package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;
import java.util.Collections;
import static org.junit.Assert.assertEquals;

public class NoteFreqTest {

    static class MockSynthesizer extends VirtualSynthesizer {
        int lastFnum = -1;
        int lastBlock = -1;

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            // Channel 0 (FM1) uses registers A0/A4. Port 0.
            if (port == 0) {
                if (reg == 0xA0) {
                    // Lower 8 bits
                    if (lastFnum == -1) lastFnum = 0;
                    lastFnum = (lastFnum & 0x700) | (val & 0xFF);
                } else if (reg == 0xA4) {
                    // Upper bits and Block
                    if (lastFnum == -1) lastFnum = 0;
                    int fnumHi = val & 0x07;
                    lastFnum = (lastFnum & 0xFF) | (fnumHi << 8);

                    lastBlock = (val >> 3) & 0x07;
                }
            }
        }
    }

    @Test
    public void testSonic2BaseNote() {
        // Sonic 2 (Little Endian). Note 0x81.
        // BaseNoteOffset = 13.
        // n = 0x81 - 0x81 + 13 = 13.
        // n % 12 = 1 -> FNUM_TABLE[1] = 653.
        // n / 12 = 1 -> Block 1.

        byte[] data = new byte[100];
        // Header (LE)
        write16(data, 0, 0x20, true); // Voice Ptr
        data[2] = 2; // Channels
        data[3] = 0;
        data[4] = 1;
        data[5] = 1;
        write16(data, 6, 0x30, true); // DAC Ptr

        write16(data, 0x0A, 0x10, true); // FM1 Ptr

        // Track
        int t = 0x10;
        data[t++] = (byte) 0x81; // Note 0x81 (C)
        data[t++] = 0x01; // Duration
        data[t++] = (byte) 0xF2; // Stop

        AbstractSmpsData smps = new Sonic2SmpsData(data, 0); // S2 Little Endian
        MockSynthesizer synth = new MockSynthesizer();
        DacData dac = new DacData(Collections.emptyMap(), Collections.emptyMap());

        SmpsSequencer seq = new SmpsSequencer(smps, dac, synth);

        short[] buf = new short[2000];
        seq.read(buf);

        // FNUM_TABLE[1] = 653.
        assertEquals("Sonic 2 Note 0x81 FNum", 653, synth.lastFnum);
        // Block should be 1 (Octave 1)
        assertEquals("Sonic 2 Note 0x81 Block", 1, synth.lastBlock);
    }

    private void write16(byte[] data, int offset, int val, boolean le) {
        if (le) {
            data[offset] = (byte)(val & 0xFF);
            data[offset+1] = (byte)((val >> 8) & 0xFF);
        } else {
            data[offset] = (byte)((val >> 8) & 0xFF);
            data[offset+1] = (byte)(val & 0xFF);
        }
    }
}
