package uk.co.jamesj999.sonic.audio.smps;
import uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import static org.junit.Assert.assertEquals;

public class GlobalTimingTest {

    @Test
    public void testGlobalTimingDoesNotRescaleActiveNotes() {
        // Track 1 (FM1): Note Dur 100. (At Mult 1).
        // Track 2 (FM2): Note Dur 10. Then 0xEB 2.

        // At T=0:
        // T1 starts Note (100).
        // T2 starts Note (10).
        // T=10:
        // T2 finishes. Reads 0xEB 2.
        // Global dividing timing becomes 2.
        // T1 has 90 ticks remaining.
        // IF BUGGY: T1 rescales.
        //   Old Scaled: 100. Elapsed: 10.
        //   New Scaled: 100 * 2 = 200.
        //   New Remaining: 200 - 10 = 190.
        //   T1 now lasts 190 more ticks (Total 200).
        // IF CORRECT: T1 ignores change until next note.
        //   T1 has 90 ticks remaining. (Total 100).

        byte[] data = new byte[256];
        data[0x02] = 2; // 2 FM Channels
        data[0x04] = 1; // Timing 1
        data[0x05] = (byte) 0x80; // Tempo

        // Pointers
        data[0x0A] = 0x10; data[0x0B] = 0x00; // FM1 -> 0x10
        data[0x0C] = 0x00; data[0x0D] = 0x00; // FM1 Key/Vol
        data[0x0E] = 0x20; data[0x0F] = 0x00; // FM2 -> 0x20
        data[0x10] = 0x00; data[0x11] = 0x00; // FM2 Key/Vol

        // FM1 Data (0x10)
        data[0x10] = (byte) 0x81; data[0x11] = 100; // Note 100
        data[0x12] = (byte) 0xF2; // Stop

        // FM2 Data (0x20)
        data[0x20] = (byte) 0x81; data[0x21] = 10; // Note 10
        data[0x22] = (byte) 0xEB; data[0x23] = 2;  // Global Mult 2
        data[0x24] = (byte) 0xF2; // Stop

        AbstractSmpsData smpsData = new Sonic2SmpsData(data, 0);
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer(), Sonic2SmpsSequencerConfig.CONFIG);

        sequencer.read(new short[1]); // Prime

        // Run for 30 ticks (60 frames)
        // Enough for T2 to trigger EB (at tick 10) and verify T1 status at tick 30.
        // At tick 30:
        // Expected T1 remaining: 100 - 30 = 70.
        // Buggy T1 remaining: 200 - 30 = 170.

        int frames = 0;
        while (frames < 60) {
            sequencer.read(new short[735]);
            frames++;
        }

        SmpsSequencer.DebugState state = sequencer.debugState();
        SmpsSequencer.DebugTrack t1 = state.tracks.get(0);

        System.out.println("T1 Duration: " + t1.duration);

        // Allow slack for priming/rounding
        assertEquals("Track 1 should have ~70 ticks remaining", 70, t1.duration, 5);
    }
}
