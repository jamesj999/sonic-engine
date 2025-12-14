package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.AudioStream;
import uk.co.jamesj999.sonic.audio.synth.Synthesizer;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;

public class ReproduceSpindashBugTest {

    static class MockSynthesizer implements Synthesizer {
        List<Integer> psgWrites = new ArrayList<>();

        @Override
        public void writeFm(Object source, int port, int register, int data) {}

        @Override
        public void writePsg(Object source, int data) {
            psgWrites.add(data);
        }

        @Override
        public void setDacData(DacData dacData) {}

        @Override
        public void playDac(Object source, int sampleId) {}

        @Override
        public void stopDac(Object source) {}

        @Override
        public void setInstrument(Object source, int channelId, byte[] voice) {}

        @Override
        public void setFmMute(int channel, boolean mute) {}

        @Override
        public void setPsgMute(int channel, boolean mute) {}

        @Override
        public void setDacInterpolate(boolean interpolate) {}
    }

    static class FakeSonic2SfxData extends Sonic2SfxData {
        public FakeSonic2SfxData(byte[] data) {
            super(data, 0x8000, 0, 0x7441);
        }

        @Override
        public int read16(int offset) {
             if (offset + 1 >= data.length) return 0;
             return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }
    }

    @Test
    public void testSpindashModulation() {
        // Construct byte array for BC.sfx
        byte[] buffer = new byte[0x8000];
        int base = 0x7441;

        // Header
        buffer[base + 0] = 0x69; buffer[base + 1] = (byte)0xF4; // Voice Ptr F469
        buffer[base + 2] = 0x01; // Tick mult
        buffer[base + 3] = 0x02; // Track count

        // Track 1 Header (FM)
        buffer[base + 4] = (byte)0x80; buffer[base + 5] = 0x05;
        buffer[base + 6] = 0x51; buffer[base + 7] = (byte)0xF4;
        buffer[base + 8] = (byte)0x90; buffer[base + 9] = 0x00;

        // Track 2 Header (PSG3)
        buffer[base + 10] = (byte)0x80; buffer[base + 11] = (byte)0xC0;
        buffer[base + 12] = 0x5B; buffer[base + 13] = (byte)0xF4;
        buffer[base + 14] = 0x00; buffer[base + 15] = 0x00;

        // Track 1 Data at 0x7451
        int t1 = 0x7451;
        buffer[t1++] = (byte)0xEF; buffer[t1++] = 0x00; // Voice 00
        buffer[t1++] = (byte)0xF0; buffer[t1++] = 0x01; buffer[t1++] = 0x01; buffer[t1++] = (byte)0xC5; buffer[t1++] = 0x1A; // Mod
        buffer[t1++] = (byte)0xCD; // Note CD
        buffer[t1++] = 0x07; // Dur 07
        buffer[t1++] = (byte)0xF2; // Stop

        // Track 2 Data at 0x745B (PSG3)
        int t2 = 0x745B;
        buffer[t2++] = (byte)0xF5; buffer[t2++] = 0x07; // PSG Inst 07
        buffer[t2++] = (byte)0x80; // Rest
        buffer[t2++] = 0x07; // Dur 07
        buffer[t2++] = (byte)0xF0; buffer[t2++] = 0x01; buffer[t2++] = 0x02; buffer[t2++] = (byte)0x05; buffer[t2++] = (byte)0xFF; // Mod
        buffer[t2++] = (byte)0xF3; buffer[t2++] = (byte)0xE7; // PSG Noise E7 (Uses Tone 3)
        buffer[t2++] = (byte)0xBB; // Note BB
        buffer[t2++] = 0x4F; // Dur 4F
        buffer[t2++] = (byte)0xF2; // Stop

        FakeSonic2SfxData sfxData = new FakeSonic2SfxData(buffer);
        // Force parsing
        sfxData.getTrackEntries();

        MockSynthesizer synth = new MockSynthesizer();
        SmpsSequencer seq = new SmpsSequencer(sfxData, new DacData(new java.util.HashMap<>(), new java.util.HashMap<>()), synth);
        seq.setSfxMode(true);

        short[] buf = new short[1024];

        // Advance 100 frames
        for (int i = 0; i < 100; i++) {
            seq.read(buf);
        }

        int tone3FreqWrites = 0;

        for (int val : synth.psgWrites) {
            // Check if it's a latch write to Channel 2 Tone
            if ((val & 0xF0) == 0xC0) {
                tone3FreqWrites++;
            }
        }

        System.out.println("Tone 3 Freq Writes: " + tone3FreqWrites);

        // Expect failure initially (0 or very few writes)
        assertTrue("Should have many Tone 3 frequency writes due to modulation. Found: " + tone3FreqWrites, tone3FreqWrites > 5);

        // Check for Tone 3 Volume Silence (0xDF) AFTER Noise Mode (0xE7) is set
        boolean noiseModeSet = false;
        boolean foundTone3SilenceAfterNoise = false;

        for (int val : synth.psgWrites) {
            if (val == 0xE7) { // Noise Control E7
                noiseModeSet = true;
            }
            if (noiseModeSet && val == 0xDF) { // Latch Channel 2 (Tone 3) Volume 15 (Silence)
                foundTone3SilenceAfterNoise = true;
                break;
            }
        }
        assertTrue("Should mute Tone 3 (Channel 2) when driving Noise Mode (after 0xE7 write)", foundTone3SilenceAfterNoise);
    }
}
