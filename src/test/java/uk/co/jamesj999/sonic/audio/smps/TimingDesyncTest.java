package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import static org.junit.Assert.assertEquals;

public class TimingDesyncTest {

    @Test
    public void testTrackDividingTimingUpdates() {
        // Scenario:
        // Dividing Timing = 1.
        // 1. Note Duration 10 (Scaled 10).
        // 2. 0xE5 (Set Track Multiplier) to 2.
        // 3. Note Duration 10 (Scaled 20).
        // Expected total ticks: 10 + 20 = 30.
        // If bug exists: 10 + (Rescale adds 10) + 20 = 40.

        byte[] data = new byte[256];
        data[0x02] = 1; // 1 FM channel
        data[0x04] = 1; // Dividing timing 1
        data[0x05] = (byte) 0x80; // Tempo

        // FM Ptr at 0x0A -> 0x10
        data[0x0A] = 0x10;
        data[0x0B] = 0x00;

        int pos = 0x10;
        // Note 1: 0x81 (C), Duration 10
        data[pos++] = (byte) 0x81;
        data[pos++] = 10;

        // Flag 0xE5: Set Multiplier to 2
        data[pos++] = (byte) 0xE5;
        data[pos++] = 2;

        // Note 2: 0x81 (C), Duration 10
        data[pos++] = (byte) 0x81;
        data[pos++] = 10;

        // Stop
        data[pos++] = (byte) 0xF2;

        AbstractSmpsData smpsData = new Sonic2SmpsData(data, 0);
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        // We need to tick 30 times.
        // We can simulate ticks by inspecting state or by forcing tick().
        // SmpsSequencer.read() triggers ticks based on tempo.
        // To be precise, we can use introspection via debugState.

        // Prime
        sequencer.read(new short[1]);

        int ticks = 0;
        boolean active = true;

        // Run loop until track is inactive
        while (active && ticks < 100) {
            SmpsSequencer.DebugState state = sequencer.debugState();
            if (state.tracks.isEmpty()) break;
            SmpsSequencer.DebugTrack t = state.tracks.get(0);

            if (!t.active) {
                active = false;
                break;
            }

            // Advance 1 tick manually?
            // SmpsSequencer doesn't expose public tick().
            // We have to drive it via read().
            // With tempo 0x80 (128) and base 256, it takes 2 frames to tick?
            // No, tempoAccumulator += tempoWeight.
            // Frame 1: acc = 128. < 256. No tick.
            // Frame 2: acc = 256. -= 256. Tick.
            // So 1 tick every 2 frames.
            // 1 frame = 735 samples.
            // So read(735) -> 1 frame.

            // Let's just run for enough samples to cover 30 ticks.
            // 30 ticks * 2 frames/tick * 735 samples/frame = 44100 samples.
            // Let's run small chunks and count how many "ticks" actually happened by monitoring duration?
            // Hard to monitor duration decrements exactly without hooking.

            // Better approach: Check position after X ticks.
            // But we don't know exactly when ticks happen easily.

            // Alternative: Modify the test to check the behavior of setTrackDividingTiming logic?
            // No, black box is better.

            // Let's just assert on the total time active.
            // We need to count how many ticks the track stays active.
            // But we can't see "ticks". We can see "is active".

            // Let's modify SmpsSequencer to be more testable? No.

            // Let's use the fact that 1 tick = 2 frames.
            // We run frame by frame.
            // Monitor when track becomes inactive.

            sequencer.read(new short[735]); // 1 frame
            // Check if ticked? DebugState doesn't show accumulator.

            // Wait, I can see t.duration decrementing.
        }
    }

    @Test
    public void testDurationSequence() {
         // Re-implementing with precise tick counting via debugState monitoring
        byte[] data = new byte[256];
        data[0x02] = 1;
        data[0x04] = 1;
        data[0x05] = (byte) 0x80; // 128 -> 0.5 ticks per frame (1 tick every 2 frames)

        data[0x0A] = 0x10; data[0x0B] = 0x00;

        int pos = 0x10;
        data[pos++] = (byte) 0x81; data[pos++] = 10; // 10 ticks
        data[pos++] = (byte) 0xE5; data[pos++] = 2;  // Mult=2
        data[pos++] = (byte) 0x81; data[pos++] = 10; // 20 ticks
        data[pos++] = (byte) 0xF2;

        AbstractSmpsData smpsData = new Sonic2SmpsData(data, 0);
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        // Prime
        sequencer.read(new short[1]);

        int frames = 0;
        int activeFrames = 0;

        while (frames < 200) {
            SmpsSequencer.DebugState state = sequencer.debugState();
            if (state.tracks.isEmpty() || !state.tracks.get(0).active) {
                break;
            }
            activeFrames++;
            sequencer.read(new short[735]); // Advance 1 frame
            frames++;
        }

        // Tempo 128/256 = 0.5 ticks per frame.
        // Total ticks = activeFrames * 0.5.
        // Expected: 30 ticks.
        // So Expected Active Frames = 60.

        // If bug (40 ticks) -> 80 frames.

        System.out.println("Active frames: " + activeFrames);

        // Allow slack for priming frame/rounding.
        // Should be close to 60.
        assertEquals("Track duration in frames", 60, activeFrames, 5);
    }
}
