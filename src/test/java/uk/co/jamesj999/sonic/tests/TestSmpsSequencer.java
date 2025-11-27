package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class TestSmpsSequencer {

    static class MockSynth extends VirtualSynthesizer {
        List<String> log = new ArrayList<>();

        @Override
        public void writeFm(int port, int reg, int val) {
            log.add(String.format("FM P%d R%02X V%02X", port, reg, val));
        }
    }

    @Test
    public void testNoteParsing() {
        byte[] data = new byte[32];
        data[2] = 2; // 2 FM Channels. Track 0 is DAC. Track 1 is FM1.
        data[5] = 1; // Tempo

        // Track 0 Header at 0x06 (Ignore)

        // Track 1 Header at 0x0A (10)
        // 4 bytes per FM track header.
        // 6, 7, 8, 9 -> Track 0.
        // 10, 11, 12, 13 -> Track 1.

        int trackDataPtr = 0x14; // 20
        data[10] = (byte)(trackDataPtr & 0xFF);
        data[11] = (byte)((trackDataPtr >> 8) & 0xFF);

        // Track Data at 20 (0x14)
        data[0x14] = (byte)0x81; // Note C
        data[0x15] = 0x01; // Duration
        data[0x16] = (byte)0xF2; // Stop

        SmpsData smps = new SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        // Increase buffer to ensure tick
        short[] buf = new short[2000];
        seq.read(buf);

        boolean foundFreq = false;
        boolean foundKeyOn = false;

        for (String entry : synth.log) {
            if (entry.contains("R28 VF0") || entry.contains("R28 VF1")) foundKeyOn = true;
        }
        // Check full log for frequency components
        String logStr = synth.log.toString();
        // Note C (81) -> FNum 617 (0x269). Block 0.
        // RA4 V02, RA0 V69.
        if (logStr.contains("RA4 V02") && logStr.contains("RA0 V69")) foundFreq = true;

        assertTrue("Should set Frequency. Log: " + logStr, foundFreq);
        assertTrue("Should Key On", foundKeyOn);
    }
}
