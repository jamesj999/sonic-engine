package uk.co.jamesj999.sonic.tests;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.HashMap;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestSmpsSequencerInstrumentLoading {

    static class MockSynthesizer extends VirtualSynthesizer {
        boolean setInstrumentCalled = false;
        int lastChannelId = -1;
        byte[] lastVoice = null;

        @Override
        public void setInstrument(int channelId, byte[] voice) {
            this.setInstrumentCalled = true;
            this.lastChannelId = channelId;
            this.lastVoice = voice;
            super.setInstrument(channelId, voice);
        }
    }

    @Test
    public void testInstrumentLoading() {
        // Construct SMPS data
        // Header:
        // 00-01: Voice Ptr
        // 02: FM Channels
        // 03: PSG Channels
        // 04-05: Tempo
        // 06-09: Track 1 Ptr

        byte[] data = new byte[100];

        // Voice Ptr at 40 (0x28)
        // Big Endian: 00 28
        data[0] = 0x00;
        data[1] = 0x28;

        data[2] = 1; // 1 FM Channel
        data[3] = 0;
        data[5] = (byte) 0x80; // Main tempo

        // DAC Pointer at 6-7 (should be 0 for this test or pointing somewhere else)
        data[6] = 0x00;
        data[7] = 0x00;

        // FM Track Ptr at 0x0A (10).
        // Since forceLittleEndian is false (Big Endian), 16-bit read is (b1 << 8) | b2.
        // We want pointer to be 16 (0x0010).
        data[10] = 0x00;
        data[11] = 0x10;

        // Track 1 Data at 16 (0x10)
        int t = 16;

        data[t++] = (byte) 0xEF; // Flag Set Voice
        data[t++] = 0x00;        // Voice ID 0
        data[t++] = (byte) 0x81; // Note C (to advance time)
        data[t++] = 0x01;        // Duration

        // Voice Data at 40
        int v = 40;
        byte[] expectedVoice = new byte[25];
        for(int i=0; i<25; i++) {
            data[v+i] = (byte) (i + 10);
            expectedVoice[i] = (byte) (i + 10);
        }

        // Explicitly set to Big Endian (S1) to verify 25-byte voice loading
        SmpsData smps = new SmpsData(data, 0, false);
        MockSynthesizer synth = new MockSynthesizer();
        DacData dac = new DacData(new HashMap<>(), new HashMap<>()); // Empty

        SmpsSequencer seq = new SmpsSequencer(smps, dac, synth);

        short[] buf = new short[2000]; // Enough to tick
        seq.read(buf); // Should trigger tick and process commands

        assertTrue("setInstrument should be called", synth.setInstrumentCalled);
        // Track 0 maps to HW Channel 0
        assertEquals("Channel ID should be 0", 0, synth.lastChannelId);
        assertArrayEquals("Voice data should match", expectedVoice, synth.lastVoice);
    }
}
