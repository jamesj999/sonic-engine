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
        data[5] = (byte) 0x80; // Tempo (unsigned)

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

        // Increase buffer to ensure at least one tick at 0x80 tempo (~2 frames)
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

    @Test
    public void testTempoZeroStallsPlayback() {
        byte[] data = new byte[32];
        data[2] = 2; // 2 FM Channels so channel 1 avoids DAC path
        data[5] = 0; // Tempo zero should halt progression

        // Track 0 (DAC) stubbed with stop
        data[6] = 0x10;
        data[7] = 0x00;
        data[0x10] = (byte) 0xF2;

        int trackDataPtr = 0x14;
        data[10] = (byte) (trackDataPtr & 0xFF);
        data[11] = (byte) ((trackDataPtr >> 8) & 0xFF);

        data[trackDataPtr] = (byte) 0x81; // Note on FM1
        data[trackDataPtr + 1] = 0x01; // Duration

        SmpsData smps = new SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        short[] buf = new short[4000];
        seq.read(buf);

        // Only the DAC enable write should be present when tempo is zero
        assertEquals("Sequencer should not advance when tempo is zero", 1, synth.log.size());
    }

    @Test
    public void testTempoChangeResetsAccumulator() {
        byte[] data = new byte[48];
        data[2] = 2; // 2 FM Channels so we can use channel 1 for FM note sequencing
        data[5] = (byte) 0xC0; // Initial fast tempo
        data[4] = 0x01; // Dividing timing

        // Track 0 (DAC) stubbed with stop
        data[6] = 0x10;
        data[7] = 0x00;
        data[0x10] = (byte) 0xF2;

        int trackDataPtr = 0x14;
        data[10] = (byte) (trackDataPtr & 0xFF);
        data[11] = (byte) ((trackDataPtr >> 8) & 0xFF);

        // Note with duration, then tempo change to a very slow tempo, a rest, and another note
        data[trackDataPtr] = (byte) 0x81;
        data[trackDataPtr + 1] = 0x01;
        data[trackDataPtr + 2] = (byte) 0xEA; // Set tempo flag
        data[trackDataPtr + 3] = (byte) 0x10; // Much slower tempo
        data[trackDataPtr + 4] = (byte) 0x80; // Rest
        data[trackDataPtr + 5] = 0x01; // Rest duration
        data[trackDataPtr + 6] = (byte) 0x82; // Second note
        data[trackDataPtr + 7] = 0x01;
        data[trackDataPtr + 8] = (byte) 0xF2; // Stop

        SmpsData smps = new SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        // Enough samples for ~27 frames. Without resetting the accumulator, the leftover fast-tempo ticks
        // would advance to the second note, but with a reset the slow tempo keeps it out of range.
        short[] buf = new short[20000];
        seq.read(buf);

        String logStr = synth.log.toString();
        long keyOnCount = synth.log.stream().filter(entry -> entry.contains("R28 VF")).count();
        int firstNoteIdx = logStr.indexOf("RA4 V02");
        int secondNoteIdx = logStr.indexOf("RA4 V02", firstNoteIdx + 1);
        assertTrue("First note should play", firstNoteIdx >= 0);
        assertTrue("Rest after tempo change should key off the channel", logStr.contains("R28 V00"));
        assertEquals("Second note should not play within the buffer when the tempo accumulator resets", 1, keyOnCount);
        assertEquals("Accumulator reset should delay the second note past the buffer", -1, secondNoteIdx);
    }

    @Test
    public void testCallAndReturnWithF9() {
        byte[] data = new byte[48];
        data[2] = 2;            // 1 FM channel (slot after DAC)
        data[4] = 0x01;         // Dividing timing
        data[5] = (byte) 0x80;  // Tempo

        // FM track pointer -> 0x14
        data[0x0A] = 0x14;
        data[0x0B] = 0x00;

        // Main track script at 0x14:
        int ptrTarget = 0x1E;
        data[0x14] = (byte) 0xF8;           // Call
        data[0x15] = (byte) (ptrTarget & 0xFF);
        data[0x16] = (byte) ((ptrTarget >> 8) & 0xFF);
        data[0x17] = (byte) 0x82;           // Note after return
        data[0x18] = (byte) 0x01;           // Duration after return
        data[0x19] = (byte) 0xF2;           // Stop after main note

        // Call target at 0x1E
        data[0x1E] = (byte) 0x81;           // Note inside subroutine
        data[0x1F] = 0x01;                  // Duration
        data[0x20] = (byte) 0xF9;           // Return

        SmpsData smps = new SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        short[] buf = new short[12000];
        seq.read(buf); // should execute both notes

        String logStr = String.join(" | ", synth.log);
        long keyOnCount = synth.log.stream()
                .filter(s -> s.startsWith("FM") && s.contains("R28") && s.contains("VF"))
                .count();
        assertEquals("Call/return should allow both notes to play. Log: " + logStr, 2, keyOnCount);
    }
}
