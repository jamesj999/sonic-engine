package com.openggf.tests;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.jupiter.api.Test;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.game.sonic2.audio.smps.Sonic2SfxData;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.VirtualSynthesizer;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import com.openggf.audio.smps.DacData;
import com.openggf.data.Rom;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    static class MockFmSynth extends VirtualSynthesizer {
        final List<String> fmLog = new ArrayList<>();

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            fmLog.add(port + ":" + String.format("%02X", reg) + ":" + String.format("%02X", val));
            super.writeFm(source, port, reg, val);
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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // Increase buffer to ensure at least one tick at 0x80 tempo (~2 frames)
        short[] buf = new short[2000];
        seq.advanceSamples(buf.length);

        boolean foundFreq = false;
        boolean foundKeyOn = false;

        for (String entry : synth.log) {
            if (entry.contains("R28 VF0") || entry.contains("R28 VF1")) foundKeyOn = true;
        }
        // Check full log for frequency components
        String logStr = synth.log.toString();
        // Note C (81) -> S2 Base Note (+1) -> Index 1 -> FNum 644 (0x284). Block 0.
        // 0x284 -> FNum 284, Block 0.
        // Reg A4: (Block << 3) | (FNum >> 8) -> (0 << 3) | 2 = 2 (0x02).
        // Reg A0: FNum & 0xFF -> 0x84.
        // RA4 V02, RA0 V84.
        if (logStr.contains("RA4 V02") && logStr.contains("RA0 V84")) foundFreq = true;

        assertTrue(foundFreq, "Should set Frequency. Log: " + logStr);
        assertTrue(foundKeyOn, "Should Key On");
    }

    @Test
    public void testS2TempoZeroWalksTracksWithoutExpiringTheCurrentNote() {
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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        short[] buf = new short[4000];
        seq.advanceSamples(buf.length);

        long keyOnCount = synth.log.stream()
                .filter(entry -> entry.contains("R28 VF"))
                .count();
        assertEquals(1, keyOnCount,
                "S2 TempoWait still walks tracks at tempo zero, but its"
                        + " no-carry pre-increment prevents the note expiring");
    }

    @Test
    public void testS2TempoChangePreservesAccumulator() {
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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // S2 cfSetTempo changes CurrentTempo only (sd:3207-3209); the live
        // TempoTimeout phase is preserved, so the second note remains in range.
        short[] buf = new short[12000];
        seq.advanceSamples(buf.length);

        String logStr = synth.log.toString();
        long keyOnCount = synth.log.stream().filter(entry -> entry.contains("R28 VF")).count();
        int firstNoteIdx = logStr.indexOf("RA4 V02");
        int secondNoteIdx = logStr.indexOf("RA4 V02", firstNoteIdx + 1);
        assertTrue(firstNoteIdx >= 0, "First note should play. Log: " + logStr);
        assertTrue(logStr.contains("R28 V00"), "Rest after tempo change should key off the channel");
        assertEquals(2, keyOnCount,
                "Preserving TempoTimeout should keep the second note in range");
        assertTrue(secondNoteIdx >= 0,
                "The preserved accumulator should reach the second note");
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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        short[] buf = new short[12000];
        seq.advanceSamples(buf.length); // should execute both notes

        String logStr = String.join(" | ", synth.log);
        long keyOnCount = synth.log.stream()
                .filter(s -> s.startsWith("FM") && s.contains("R28") && s.contains("VF"))
                .count();
        assertEquals(2, keyOnCount, "Call/return should allow both notes to play. Log: " + logStr);
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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        short[] buf = new short[5000];
        seq.advanceSamples(buf.length);

        String logStr = String.join(" | ", synth.log);

        // SMPSPlay: Writes 0x0F to 0x88 and 0x8C (Op 3/4 Release Rate).
        // FM P0 R88 V0F
        assertTrue(logStr.contains("FM P0 R88 V0F"), "Should write to 0x88 (RR Op3). Log: " + logStr);
        assertTrue(logStr.contains("FM P0 R8C V0F"), "Should write to 0x8C (RR Op4). Log: " + logStr);

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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        short[] buf = new short[100];
        seq.advanceSamples(buf.length);

        assertEquals(0xAA, seq.getCommData(), "Comm data should be 0xAA");
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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // Read small buffer. Track should still be active and playing the note.
        short[] buf = new short[2000];
        seq.advanceSamples(buf.length);

        SmpsSequencer.DebugState state = seq.debugState();
        // Index 0 is FM1 (DAC track skipped because ptr is 0)
        assertTrue(state.tracks.get(0).active, "Track should be active");
        assertTrue(state.tracks.get(0).duration > 60000, "Duration should be very large (> 60000). Was: " + state.tracks.get(0).duration);
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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        short[] buf = new short[735]; // ~1 frame

        int c4Count = 0;
        int d4Count = 0;
        int lastNote = -1;

        for (int i = 0; i < 300; i++) {
            seq.advanceSamples(buf.length);
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

        assertEquals(3, c4Count, "Should play C4 3 times");
        assertEquals(3, d4Count, "Should play D4 3 times");
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
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // Prime sequencer (runs initial tick) without advancing tempo frames
        seq.advanceSamples(2);
        // Advance enough samples for one additional tempo tick (2 frames @ tempo 0x80)
        seq.advance(1500);

        List<Integer> volumeWrites = new ArrayList<>();
        for (int val : synth.psgLog) {
            if ((val & 0xF0) == 0x90) {
                volumeWrites.add(val);
            }
        }

        assertEquals(1, volumeWrites.size(), "PSG volume should be written once (hold, no loop)");
        assertEquals(0x91, (int) volumeWrites.get(0), "First envelope step should apply immediately");
    }

    @Test
    public void testNoiseChannelUsesTone2Frequency() {
        byte[] data = new byte[96];
        data[0] = 0x00; // Voice pointer (none)
        data[1] = 0x00;
        data[2] = 0x01; // Tick multiplier
        data[3] = 0x01; // Track count

        // Track header: flags, channel (PSG3), pointer, transpose, volume
        data[4] = 0x00;
        data[5] = (byte) 0xC0;       // PSG channel 3 (noise-capable)
        data[6] = 0x20; data[7] = (byte) 0x80; // Pointer to 0x8020 -> relocates to 0x20
        data[8] = 0x00;
        data[9] = 0x00;

        // PSG track script at 0x20: enable noise (tone2 match), note, duration, stop
        int pos = 0x20;
        data[pos++] = (byte) 0xF3; // PSG noise
        data[pos++] = 0x07;        // White noise, tone2 frequency
        data[pos++] = (byte) 0x81; // Note
        data[pos++] = 0x02;        // Duration
        data[pos] = (byte) 0xF2;   // Stop

        Sonic2SfxData smps = new Sonic2SfxData(data, 0x8000, 0, 0);

        MockPsgSynth synth = new MockPsgSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // Prime sequencer (runs initial tick)
        seq.advanceSamples(2);
        // Advance enough samples for one additional tempo tick
        seq.advance(1500);

        boolean hasNoiseLatch = false;
        boolean hasTone2Latch = false;
        for (int val : synth.psgLog) {
            if (val == 0xE7) {
                hasNoiseLatch = true;
            }
            if ((val & 0xF0) == 0xC0) {
                hasTone2Latch = true;
            }
        }

        assertTrue(hasNoiseLatch, "Noise command should be issued");
        assertTrue(hasTone2Latch, "Tone 2 frequency should still be latched while in noise mode (tone2 match)");
    }

    @Test
    public void testS2HoldE7StillRetriggersFm() {
        byte[] data = new byte[64];
        data[2] = 2; // DAC + FM1
        data[4] = 1; // Dividing timing
        data[5] = (byte) 0x80; // Tempo

        // FM1 pointer at header slot 1 (0x0A/0x0B)
        data[0x0A] = 0x14;
        data[0x0B] = 0x00;

        int pos = 0x14;
        data[pos++] = (byte) 0x81; // Note 1
        data[pos++] = 0x01;        // Duration
        data[pos++] = (byte) 0xE7; // HOLD / no-attack for next note
        data[pos++] = (byte) 0x83; // Note 2 (no attack state, but FM keys on)
        data[pos++] = 0x01;        // Duration
        data[pos] = (byte) 0xF2;   // Stop

        AbstractSmpsData smps = new Sonic2SmpsData(data);
        MockFmSynth synth = new MockFmSynth();
        SmpsSequencer seq = new SmpsSequencer(smps, null, synth, Sonic2SmpsSequencerConfig.CONFIG);
        seq.advanceSamples(12000);

        long fmKeyOnCount = synth.fmLog.stream()
                .filter(s -> s.startsWith("0:28:") && s.endsWith("F0"))
                .count();
        assertEquals(2, fmKeyOnCount,
                "S2's shipped no-attack path still executes the FM key-on");
    }

    @Test
    public void testSfxBcFmVoicePlaysWithCenteredPan() {
        // Load real SFX 0xBC from ROM to exercise the FM blip.
        File romFile = RomTestUtils.ensureRomAvailable();
        assumeTrue(romFile != null, "Sonic 2 ROM not available Ã¢â‚¬â€ skipping test");
        Rom rom = new Rom();
        assertTrue(rom.open(romFile.getAbsolutePath()), "Failed to open ROM");
        Sonic2SmpsLoader loader = new Sonic2SmpsLoader(rom);
        AbstractSmpsData sfx = loader.loadSfx(0xBC);
        assertNotNull(sfx, "SFX 0xBC should load");
        assertTrue(sfx instanceof Sonic2SfxData, "Expected Sonic2SfxData for SFX 0xBC");
        assertNotNull(sfx.getVoice(0), "SFX 0xBC should have voice 0");
        Sonic2SfxData sfxData = (Sonic2SfxData) sfx;
        int ptr = sfxData.getTrackEntries().get(0).pointer;
        assertTrue(ptr >= 0 && ptr < sfxData.getData().length, "Track pointer should be within data");
        assertEquals((byte) 0xEF, sfxData.getData()[ptr], "SFX 0xBC track should start with Set Voice");

        DacData dacData = loader.loadDacData();
        MockFmSynth synth = new MockFmSynth();
        SmpsSequencer seq = new SmpsSequencer(sfx, dacData, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // Prime and run a few ticks
        seq.advanceSamples(2);
        SmpsSequencer.DebugState initial = seq.debugState();
        seq.advance(20000);

        boolean hasKeyEvent = false;
        boolean hasCenteredPan = false;
        for (String log : synth.fmLog) {
            if (log.contains(":28:")) {
                hasKeyEvent = true;
            }
            if (log.startsWith("1:B") && log.endsWith("C0")) {
                hasCenteredPan = true;
            }
        }

        assertFalse(initial.tracks.isEmpty(), "FM track should exist");
        assertEquals(SmpsSequencer.TrackType.FM, initial.tracks.get(0).type, "Track 0 should be FM for SFX 0xBC");
        assertEquals(0, initial.tracks.get(0).voiceId, "FM track should use voice 0");
        assertFalse(initial.tracks.get(0).tieNext, "FM track should not be tied when key-on expected");

        assertTrue(hasKeyEvent, "SFX 0xBC FM should poke the key on/off register. FM log: " + synth.fmLog);
        assertTrue(hasCenteredPan, "SFX 0xBC FM should center pan (not inherit music pan). FM log: " + synth.fmLog);
    }
}
