package uk.co.jamesj999.sonic.audio.smps;
import uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer.Region;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TimingTempoTest {

    private SmpsSequencer setupSequencer(int tempo, boolean palDisabled, int musicId) {
        byte[] data = new byte[256];
        data[0x02] = 1; // 1 FM channel
        data[0x04] = 4; // Dividing timing 4 (so duration 50 -> 200 ticks)
        data[0x05] = (byte) tempo; // Tempo

        // FM Ptr at 0x06 -> 0x10
        data[0x06] = 0x10;
        data[0x07] = 0x00;

        // Track at 0x10: Note 0x81, Duration 50 (scaled to 200), Stop
        data[0x10] = (byte) 0x81;
        data[0x11] = (byte) 50; // 50 ticks * 4 = 200
        data[0x12] = (byte) 0xF2;

        Sonic2SmpsData smpsData = new Sonic2SmpsData(data, 0);
        smpsData.setId(musicId);
        smpsData.setPalSpeedupDisabled(palDisabled);

        return new SmpsSequencer(smpsData, null, new VirtualSynthesizer(), Sonic2SmpsSequencerConfig.CONFIG);
    }

    private int runSimulation(SmpsSequencer sequencer, double seconds) {
        // Read initial duration
        // We need to prime it first
        sequencer.read(new short[1]);

        SmpsSequencer.DebugState state = sequencer.debugState();
        int initialDuration = 0;
        if (!state.tracks.isEmpty()) {
            initialDuration = state.tracks.get(0).duration;
        }

        // Run for specified seconds
        sequencer.advance(44100.0 * seconds);

        SmpsSequencer.DebugState finalState = sequencer.debugState();
        int finalDuration = 0;
        if (!finalState.tracks.isEmpty()) {
            finalDuration = finalState.tracks.get(0).duration;
        }

        return initialDuration - finalDuration;
    }

    @Test
    public void testNtscTiming() {
        SmpsSequencer sequencer = setupSequencer(0x80, false, 0);
        sequencer.setRegion(Region.NTSC);

        int ticks = runSimulation(sequencer, 1.0);

        assertEquals("NTSC should produce 30 ticks/sec with tempo 0x80", 30, ticks);
    }

    @Test
    public void testPalTimingSpeedup() {
        SmpsSequencer sequencer = setupSequencer(0x80, false, 0);
        sequencer.setRegion(Region.PAL); // Enables 50Hz and Speedup check

        int ticks = runSimulation(sequencer, 1.0);

        assertTrue("PAL Speedup should be close to 30 ticks (was " + ticks + ")", ticks >= 29 && ticks <= 30);
    }

    @Test
    public void testPalTimingDisabled() {
        SmpsSequencer sequencer = setupSequencer(0x80, true, 0); // palDisabled = true
        sequencer.setRegion(Region.PAL);

        int ticks = runSimulation(sequencer, 1.0);

        assertEquals("PAL Disabled should produce 25 ticks/sec with tempo 0x80", 25, ticks);
    }

    @Test
    public void testSpeedShoes() {
        SmpsSequencer sequencer = setupSequencer(0x80, false, 0x81); // ID 0x81
        sequencer.setRegion(Region.NTSC);
        sequencer.setSpeedShoes(true);

        int ticks = runSimulation(sequencer, 1.0);

        assertEquals("Speed Shoes should produce 44 ticks/sec for Emerald Hill (0xBE)", 44, ticks);
    }
}
