package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;

public class TestSmpsLoop {

    static class MockSynth extends VirtualSynthesizer {
        List<String> log = new ArrayList<>();
        @Override
        public void writeFm(int port, int reg, int val) {
            // Log Key On (Reg 28)
            // Channel 0 (Val 0, 1, 2)
            if (port == 0 && reg == 0x28 && (val & 0xF0) == 0xF0) {
                log.add(String.format("KeyOn Ch%d", val & 0x0F));
            }
        }
    }

    @Test
    public void testLoopF7() {
        byte[] data = new byte[64];
        data[2] = 2; // 2 FM Channels
        data[4] = 0x01; // Dividing timing
        data[5] = (byte) 0x80; // Tempo

        // Track 1 Header at 0x0A -> Pointer 0x20
        int trackPtr = 0x20;
        data[0x0A] = (byte) (trackPtr & 0xFF);
        data[0x0B] = (byte) ((trackPtr >> 8) & 0xFF);

        // Track Data at 0x20
        // 0x20: Note 0x81 (C), Duration 0x01
        data[0x20] = (byte) 0x81;
        data[0x21] = 0x01;

        // 0x22: F7 Loop. Index 0, Count 3, Ptr 0x20.
        // Format: F7 Index Count P1 P2
        // Count 3 means:
        // 1. Init to 3. Dec to 2. Jump. (Loop 1)
        // 2. Init already set. Dec to 1. Jump. (Loop 2)
        // 3. Init already set. Dec to 0. No Jump.
        // Total 2 jumps.
        data[0x22] = (byte) 0xF7;
        data[0x23] = 0x00; // Index
        data[0x24] = 0x03; // Count
        data[0x25] = (byte) (trackPtr & 0xFF); // P1
        data[0x26] = (byte) ((trackPtr >> 8) & 0xFF); // P2

        // 0x27: Note 0x82 (C#), Duration 0x01
        data[0x27] = (byte) 0x82;
        data[0x28] = 0x01;

        // 0x29: Stop
        data[0x29] = (byte) 0xF2;

        SmpsData smps = new SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        short[] buf = new short[20000]; // Enough for many frames
        seq.read(buf);

        // Expect: KeyOn 0x81 (Initial), KeyOn 0x81 (Loop 1), KeyOn 0x81 (Loop 2), KeyOn 0x82 (End).
        // Total 4 KeyOns.

        long keyOns = synth.log.size();
        assertEquals("Should loop 2 times (play 3 times) then play next note", 4, keyOns);
    }
}
