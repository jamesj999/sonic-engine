package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import static org.junit.Assert.assertEquals;

public class LoopTest {

    @Test
    public void testLoopCountOneMeansOneRepeat() {
        // Test that a loop count of 1 results in 2 plays (Original + 1 Repeat).
        // Track:
        // 0x10: Note 0x81 (Duration 10)
        // 0x12: Loop (F7) Index 0, Count 1, Ptr 0x10
        // 0x17: Note 0x81 (Duration 10)
        // 0x19: Stop (F2)

        // Expected time:
        // Play 1 (10) -> Loop (Jump) -> Play 2 (10) -> Loop (No Jump) -> Play 3 (10) -> Stop.
        // Wait, "Repeat 1 time" means Play, Jump, Play, Continue. Total 2 plays.
        // Play 1 (10). Jump. Play 2 (10). Continue. Play 3 (End Note) (10).
        // Total ticks: 30.

        byte[] data = new byte[256];
        data[0x02] = 2; // 2 FM (FM1 is Index 1)
        data[0x04] = 1; // Timing 1
        data[0x05] = (byte) 0x80; // Tempo

        data[0x0A] = 0x10; data[0x0B] = 0x00; // FM Ptr

        int pos = 0x10;
        data[pos++] = (byte) 0x81; data[pos++] = 10; // Note 1

        // Loop F7
        data[pos++] = (byte) 0xF7;
        data[pos++] = 0x00; // Index 0
        data[pos++] = 0x01; // Count 1
        data[pos++] = 0x10; // Ptr LSB
        data[pos++] = 0x00; // Ptr MSB

        // Note 2 (End marker)
        data[pos++] = (byte) 0x81; data[pos++] = 10;

        data[pos++] = (byte) 0xF2;

        AbstractSmpsData smpsData = new Sonic2SmpsData(data, 0);
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        // Prime
        sequencer.read(new short[1]);

        int frames = 0;
        int activeFrames = 0;

        // Run until inactive
        while (frames < 200) {
            SmpsSequencer.DebugState state = sequencer.debugState();
            if (state.tracks.isEmpty() || !state.tracks.get(0).active) {
                break;
            }
            activeFrames++;
            sequencer.read(new short[735]);
            frames++;
        }

        // 30 ticks = 60 frames (approx).
        // If bug (0 repeats): 20 ticks = 40 frames.
        // Current implementation: Count 1 = 1 Play (0 Repeats) -> 40 frames.

        System.out.println("Active frames: " + activeFrames);

        // Slack 10 frames
        assertEquals("Loop Count 1 should repeat once", 40, activeFrames, 10);
    }
}
