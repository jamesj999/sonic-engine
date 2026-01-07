package uk.co.jamesj999.sonic.audio.smps;
import uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.driver.SmpsDriver;
import uk.co.jamesj999.sonic.audio.synth.Synthesizer;
import uk.co.jamesj999.sonic.audio.smps.DacData;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestFade {

    private static class MockSynthesizer implements Synthesizer {
        public void setDacData(DacData data) {
        }

        public void playDac(Object source, int note) {
        }

        public void stopDac(Object source) {
        }

        public void writeFm(Object source, int port, int reg, int val) {
        }

        public void writePsg(Object source, int val) {
        }

        public void setInstrument(Object source, int channelId, byte[] voice) {
        }

        public void setFmMute(int channel, boolean mute) {
        }

        public void setPsgMute(int channel, boolean mute) {
        }

        public void setDacInterpolate(boolean interpolate) {
        }
    }

    @Test
    public void testFadeOut() {
        // Create a dummy SMPS data with E4 command (Fade Out)
        // E4: Fade Out.
        // Data: 0xE4, 0xF2 (Stop)
        byte[] fullData = new byte[200];
        fullData[2] = 2; // 2 Channels (DAC + FM1) to hit FM1 at index 1
        fullData[4] = 1; // Div
        fullData[5] = (byte) 0xFF; // Tempo (Fast, so it ticks every frame)
        // FM1 Ptr at 0x0A. 0x0A + 4 = 0x0E (Header end).
        // Track starts at 0x10.
        fullData[0x0A] = 0x10;
        fullData[0x0B] = 0x00;

        // Track data at 0x10
        int pos = 0x10;
        fullData[pos++] = (byte) 0x81;
        fullData[pos++] = 0x01; // Note 1 tick
        fullData[pos++] = (byte) 0xFD; // Fade Out
        fullData[pos++] = 0x28; // 40 steps
        fullData[pos++] = 0x03; // 3 delay
        // Default fade: 40 steps, delay 3. Total 120 frames (approx 2 sec).
        for (int i = 0; i < 60; i++) {
            fullData[pos++] = (byte) 0x81;
            fullData[pos++] = 0x03; // Note 3 ticks
        }
        fullData[pos++] = (byte) 0xF2;

        Sonic2SmpsData sData = new Sonic2SmpsData(fullData);
        // Use MockSynthesizer to avoid slow emulation
        Synthesizer synth = new MockSynthesizer();
        SmpsSequencer seq = new SmpsSequencer(sData, null, synth, Sonic2SmpsSequencerConfig.CONFIG);

        // We use seq.read() directly.

        // Initial state: Volume Offset 0
        assertEquals(0, seq.debugState().tracks.get(0).volumeOffset);

        int samplesPerFrame = 735;

        // Run 10 frames.
        seq.advance(10 * samplesPerFrame);

        // Fade should have started.

        int vol = seq.debugState().tracks.get(0).volumeOffset;
        assertTrue("Volume offset should increase (current: " + vol + ")", vol > 0);

        // Run more frames to complete fade
        seq.advance(200 * samplesPerFrame);

        // Should be stopped
        assertFalse("Track should be inactive after fade", seq.debugState().tracks.get(0).active);
    }
}
