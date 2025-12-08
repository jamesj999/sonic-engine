package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import static org.junit.Assert.assertEquals;

public class SmpsSequencerTest {

    @Test
    public void testDurationWrapping() {
        // Construct a mock SMPS track
        // Header:
        // 00-01: Voice Ptr (0)
        // 02: Channels (1)
        // 03: PSG Channels (0)
        // 04: Dividing Timing (3) - Multiplier of 3
        // 05: Tempo (0x80) - Non-zero so it runs
        // 06-07: DAC Ptr (0)
        // 08-09: Unused
        // 0A-0B: FM Track 1 Ptr (pointing to 0x10)
        // 0C: Key (0)
        // 0D: Vol (0)
        // ...
        // 10: Command 0x81 (Note)
        // 11: Duration 100 (0x64)
        // 12: Command 0xF2 (Stop)

        // Expected Duration: 100 * 3 = 300.
        // If wrapped to byte: 300 & 0xFF = 44.

        byte[] data = new byte[256];
        // Header
        data[0x02] = 1; // 1 FM channel
        data[0x04] = 3; // Dividing timing 3
        data[0x05] = (byte) 0x80; // Tempo

        // FM Track Ptr at 0x06 (Little Endian for S2 - SmpsData reads from 0x06)
        data[0x06] = 0x10;
        data[0x07] = 0x00;

        // Track Data at 0x10
        data[0x10] = (byte) 0x81; // Note C
        data[0x11] = 100;         // Duration 100
        data[0x12] = (byte) 0xF2; // Stop

        Sonic2SmpsData smpsData = new Sonic2SmpsData(data, 0); // S2 is Little Endian
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        // First call primes the sequencer (calls tick() if tempoWeight != 0).
        sequencer.read(new short[1]);

        SmpsSequencer.DebugState state = sequencer.debugState();
        if (state.tracks.isEmpty()) {
            throw new RuntimeException("No tracks found");
        }

        SmpsSequencer.DebugTrack track = state.tracks.get(0);
        System.out.println("Track duration: " + track.duration);
        System.out.println("Track scaled duration (internal): " + track.duration);

        assertEquals("Duration should not wrap", 300, track.duration);
    }

    @Test
    public void testJumpEndiannessLittle() {
        // Test F6 Jump with Little Endian (Standard S2)
        // Jump to 0x20
        // LE: 20 00
        byte[] data = new byte[256];
        data[0x02] = 1; // 1 FM channel
        data[0x04] = 1;
        data[0x05] = (byte) 0x80;

        // FM Ptr at 0x06 -> 0x10
        data[0x06] = 0x10;
        data[0x07] = 0x00;

        // Track at 0x10: Jump to 0x20
        data[0x10] = (byte) 0xF6;
        data[0x11] = 0x20; // LSB
        data[0x12] = 0x00; // MSB

        // Track at 0x20: Note 0x81, Duration 10, Stop
        data[0x20] = (byte) 0x81;
        data[0x21] = 10;
        data[0x22] = (byte) 0xF2;

        Sonic2SmpsData smpsData = new Sonic2SmpsData(data, 0);
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        // Prime
        sequencer.read(new short[1]);

        SmpsSequencer.DebugState state = sequencer.debugState();
        SmpsSequencer.DebugTrack track = state.tracks.get(0);

        // Should be at position 0x22 (after reading note at 0x20 and duration at 0x21)
        // Wait, tick() reads commands until duration > 0.
        // It reads F6 -> handleJump -> pos becomes 0x20.
        // Loop continues.
        // Reads 0x81 at 0x20 -> note.
        // Reads 10 at 0x21 -> duration.
        // Breaks.
        // Pos should be 0x22.

        assertEquals("Should have jumped to 0x20 and read note", 0x22, track.position);
    }

    @Test
    public void testJumpEndiannessBig() {
        // Test F6 Jump with Big Endian (Sonic 1)
        // Jump to 0x20
        // BE: 00 20
        byte[] data = new byte[256];
        data[0x00] = 0;
        data[0x01] = 0; // Voice ptr BE
        data[0x02] = 1; // 1 FM channel
        data[0x04] = 1;
        data[0x05] = (byte) 0x80;

        // FM Ptr at 0x06 -> 0x10 (BE: 00 10)
        // Sonic1SmpsData constructor reads header from 0x06 for tracks.
        data[0x06] = 0x00;
        data[0x07] = 0x10;

        // Track at 0x10: Jump to 0x20
        data[0x10] = (byte) 0xF6;
        data[0x11] = 0x00; // MSB
        data[0x12] = 0x20; // LSB

        // Track at 0x20: Note 0x81, Duration 10, Stop
        data[0x20] = (byte) 0x81;
        data[0x21] = 10;
        data[0x22] = (byte) 0xF2;

        Sonic1SmpsData smpsData = new Sonic1SmpsData(data, 0); // BE
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        // Prime
        sequencer.read(new short[1]);

        SmpsSequencer.DebugState state = sequencer.debugState();
        if (state.tracks.isEmpty()) {
             // Debugging help
             throw new RuntimeException("No tracks found. Header parsing might have failed.");
        }
        SmpsSequencer.DebugTrack track = state.tracks.get(0);

        // Should be at position 0x22
        assertEquals("Should have jumped to 0x20 using BE pointer", 0x22, track.position);
    }
}
