package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.Sonic2SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public class TestSmpsSequencer {

    static class MockSynth extends VirtualSynthesizer {
        List<String> log = new ArrayList<>();

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            log.add(String.format("FM P%d R%02X V%02X", port, reg, val));
        }
    }

    static class MockPsgSynth extends VirtualSynthesizer {
        final List<Integer> psgLog = new ArrayList<>();

        @Override
        public void writePsg(Object source, int val) {
            psgLog.add(val & 0xFF);
            super.writePsg(source, val);
        }
    }

    @Test
    public void testNoteParsing() {
        byte[] data = new byte[32];
        data[2] = 2; // 2 FM Channels. Track 0 is DAC. Track 1 is FM1.
        data[4] = 1; // Dividing Timing 1
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

        AbstractSmpsData smps = new Sonic2SmpsData(data);
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
        // Note C (81) -> S2 Base Note (+13) -> Index 1 -> FNum 653 (0x28D). Block 1.
        // 0x28D -> FNum 28D, Block 1.
        // Reg A4: (Block << 3) | (FNum >> 8) -> (1 << 3) | 2 = 10 (0x0A).
        // Reg A0: FNum & 0xFF -> 0x8D.
        // RA4 V0A, RA0 V8D.
        if (logStr.contains("RA4 V0A") && logStr.contains("RA0 V8D")) foundFreq = true;

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

        AbstractSmpsData smps = new Sonic2SmpsData(data);
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

        AbstractSmpsData smps = new Sonic2SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        // Enough samples for ~16 frames. Without resetting the accumulator, the leftover fast-tempo ticks
        // would advance to the second note (takes ~10 frames), but with a reset the slow tempo (takes ~17 frames)
        // keeps it out of range of this buffer.
        short[] buf = new short[12000];
        seq.read(buf);

        String logStr = synth.log.toString();
        long keyOnCount = synth.log.stream().filter(entry -> entry.contains("R28 VF")).count();
        int firstNoteIdx = logStr.indexOf("RA4 V0A");
        int secondNoteIdx = logStr.indexOf("RA4 V0A", firstNoteIdx + 1);
        assertTrue("First note should play. Log: " + logStr, firstNoteIdx >= 0);
        assertTrue("Rest after tempo change should key off the channel", logStr.contains("R28 V00"));
        assertEquals("Second note should not play within the buffer when the tempo accumulator resets", 1, keyOnCount);
        assertEquals("Accumulator reset should delay the second note past the buffer", -1, secondNoteIdx);
    }

    @Test
    public void testCallAndReturn() {
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
        data[0x20] = (byte) 0xE3;           // Return

        AbstractSmpsData smps = new Sonic2SmpsData(data);
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

    @Test
    public void testSndOffF9() {
        byte[] data = new byte[32];
        data[2] = 2;
        data[4] = 1; // Fix Dividing Timing to 1 so duration is 1 tick, not 65536
        data[5] = (byte) 0x80;

        // Track 1
        data[0x0A] = 0x14;
        data[0x0B] = 0x00;

        data[0x14] = (byte) 0x81; // Note
        data[0x15] = 0x01;
        data[0x16] = (byte) 0xF9; // SND_OFF (writes specific registers, does NOT stop track)
        data[0x17] = (byte) 0xF2; // Stop

        AbstractSmpsData smps = new Sonic2SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        short[] buf = new short[5000];
        seq.read(buf);

        String logStr = String.join(" | ", synth.log);

        // SMPSPlay: Writes 0x0F to 0x88 and 0x8C (Op 3/4 Release Rate).
        // FM P0 R88 V0F
        assertTrue("Should write to 0x88 (RR Op3). Log: " + logStr, logStr.contains("FM P0 R88 V0F"));
        assertTrue("Should write to 0x8C (RR Op4). Log: " + logStr, logStr.contains("FM P0 R8C V0F"));

        // Should NOT write to 0x40 (TL) as before (unless caused by note on/off, but F9 itself shouldn't)
        // Note: DoNoteOff writes to 0x28 (Key Off).
        // Since F9 doesn't stop track, we expect F2 to stop it.
    }

    @Test
    public void testGetCommData() {
        byte[] data = new byte[32];
        data[2] = 2;
        data[5] = (byte) 0x80;

        // Track 1
        data[0x0A] = 0x14;
        data[0x0B] = 0x00;

        data[0x14] = (byte) 0xE2;
        data[0x15] = (byte) 0xAA; // Comm byte
        data[0x16] = (byte) 0xF2;

        AbstractSmpsData smps = new Sonic2SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        short[] buf = new short[100];
        seq.read(buf);

        assertEquals("Comm data should be 0xAA", 0xAA, seq.getCommData());
    }

    @Test
    public void testTickMultZero() {
        byte[] data = new byte[64];
        data[2] = 2; // 2 FM Channels
        data[5] = (byte) 0x80; // Tempo

        // Track 1 Header at 0x0A
        data[0x0A] = 0x14;
        data[0x0B] = 0x00;

        // Data
        int pos = 0x14;
        data[pos++] = (byte) 0xE5; // Tick Mult
        data[pos++] = 0x00;        // 0 -> should mean "65536" scaling effectively

        data[pos++] = (byte) 0x81; // Note
        data[pos++] = 0x01;        // Duration 1 * 65536 = 65536 ticks

        data[pos++] = (byte) 0xF2; // Stop

        AbstractSmpsData smps = new Sonic2SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        // Read small buffer. Track should still be active and playing the note.
        short[] buf = new short[2000];
        seq.read(buf);

        SmpsSequencer.DebugState state = seq.debugState();
        // Index 0 is FM1 (DAC track skipped because ptr is 0)
        assertTrue("Track should be active", state.tracks.get(0).active);
        assertTrue("Duration should be very large (> 60000). Was: " + state.tracks.get(0).duration, state.tracks.get(0).duration > 60000);
    }

    @Test
    public void testLoopCommandS2() {
        byte[] data = new byte[256];
        data[2] = 2; // Channels = 2 (DAC, FM1)
        data[4] = 1; // Div = 1
        data[5] = 100; // Tempo

        // FM1 Entry at 0xA
        // Ptr = 0x0020
        data[0xA] = 0x20;
        data[0xB] = 0x00;

        int pos = 0x20;

        // 1. Note C4 (0x81).
        data[pos++] = (byte) 0x81;
        data[pos++] = 0x10; // Duration 16

        // 2. Note D4 (0x83).
        data[pos++] = (byte) 0x83;
        data[pos++] = 0x10; // Duration 16

        // 3. Loop (F7) back to 0x20 (C4).
        // Format: F7 [Index] [Count] [PtrLSB] [PtrMSB]
        // Count 3 (for 2 jumps, 3 plays). Index 0.
        data[pos++] = (byte) 0xF7;
        data[pos++] = 0x00; // Index
        data[pos++] = 0x03; // Count
        data[pos++] = 0x20; // Ptr LSB
        data[pos++] = 0x00; // Ptr MSB

        // 4. Stop
        data[pos++] = (byte) 0xF2;

        AbstractSmpsData smps = new Sonic2SmpsData(data);
        MockSynth synth = new MockSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        short[] buf = new short[735]; // ~1 frame

        int c4Count = 0;
        int d4Count = 0;
        int lastNote = -1;

        for (int i = 0; i < 300; i++) {
            seq.read(buf);
            SmpsSequencer.DebugState state = seq.debugState();
            if (!state.tracks.isEmpty()) {
                SmpsSequencer.DebugTrack t = state.tracks.get(0);
                if (t.active) {
                    if (t.note != lastNote) {
                        if (t.note == 0x81) c4Count++;
                        if (t.note == 0x83) d4Count++;
                        lastNote = t.note;
                    }
                }
            }
        }

        assertEquals("Should play C4 3 times", 3, c4Count);
        assertEquals("Should play D4 3 times", 3, d4Count);
    }

    @Test
    public void testPsgEnvelopeHoldAndInitialStepApplied() {
        byte[] data = new byte[64];
        data[2] = 1; // DAC only in FM table
        data[3] = 1; // 1 PSG channel
        data[4] = 1; // Dividing timing
        data[5] = (byte) 0x80; // Tempo

        // DAC track stub -> stop
        data[0x06] = 0x30;
        data[0x07] = 0x00;
        data[0x30] = (byte) 0xF2;

        // PSG track header (pointer, key offset, vol offset, mod env, instrument)
        data[0x0A] = 0x20;
        data[0x0B] = 0x00;
        data[0x0C] = 0x00;
        data[0x0D] = 0x00;
        data[0x0E] = 0x00;
        data[0x0F] = 0x01; // PSG instrument 1

        // PSG track script: note, duration, stop
        int pos = 0x20;
        data[pos++] = (byte) 0x81; // C
        data[pos++] = 0x02;        // Duration 2
        data[pos] = (byte) 0xF2;   // Stop

        Map<Integer, byte[]> envs = new HashMap<>();
        envs.put(1, new byte[] {0x01, (byte) 0x80}); // Step to 1, then hold

        Sonic2SmpsData smps = new Sonic2SmpsData(data);
        smps.setPsgEnvelopes(envs);

        MockPsgSynth synth = new MockPsgSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth);

        // Prime sequencer (runs initial tick) without advancing tempo frames
        seq.read(new short[2]);
        // Advance enough samples for one additional tempo tick (2 frames @ tempo 0x80)
        seq.advance(1500);

        List<Integer> volumeWrites = new ArrayList<>();
        for (int val : synth.psgLog) {
            if ((val & 0xF0) == 0x90) {
                volumeWrites.add(val);
            }
        }

        assertEquals("PSG volume should be written once (hold, no loop)", 1, volumeWrites.size());
        assertEquals("First envelope step should apply immediately", 0x91, (int) volumeWrites.get(0));
    }
}
