package uk.co.jamesj999.sonic.tests;
import uk.co.jamesj999.sonic.game.sonic2.audio.Sonic2SmpsSequencerConfig;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.game.sonic2.audio.smps.Sonic2SmpsData;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class TestSmpsInstrumentParsing {

    static class MockSynthesizer extends VirtualSynthesizer {
        boolean setInstrumentCalled = false;
        int lastChannelId = -1;
        byte[] lastVoice = null;

        List<String> writeLog = new ArrayList<>();

        @Override
        public void setInstrument(Object source, int channelId, byte[] voice) {
            this.setInstrumentCalled = true;
            this.lastChannelId = channelId;
            // Copy voice to avoid mutation issues if any
            this.lastVoice = new byte[voice.length];
            System.arraycopy(voice, 0, this.lastVoice, 0, voice.length);
            super.setInstrument(source, channelId, voice);
        }

        @Override
        public void writeFm(Object source, int port, int reg, int val) {
            writeLog.add(String.format("P%d_R%02X_V%02X", port, reg, val));
            super.writeFm(source, port, reg, val);
        }
    }

    @Test
    public void testInstrumentParsingOrder() {
        // Construct a Sonic 2 SMPS blob
        byte[] data = new byte[200];

        // Header
        data[0] = 0x28; // Voice Ptr LSB (Little Endian) -> 0x0028
        data[1] = 0x00; // Voice Ptr MSB
        data[2] = 2;    // Channels (DAC, FM1)
        data[3] = 0;    // PSG Channels
        data[4] = 1;    // Div Timing
        data[5] = (byte)0x80; // Tempo
        data[6] = 0x00; // DAC Ptr
        data[7] = 0x00;

        // FM1 Ptr at offset 0x0A (10)
        // 06: DAC Ptr (6-7)
        // 08: Key/Vol (8-9)
        // 10: FM1 Ptr (10-11)
        data[10] = 0x20; // Ptr -> 0x0020 (32)
        data[11] = 0x00;

        // Track Data at 32 (0x20)
        int t = 32;
        data[t++] = (byte)0xEF; // Set Voice
        data[t++] = 0x00;       // Voice ID 0
        data[t++] = (byte)0x81; // Note C
        data[t++] = 0x01;       // Duration 1
        data[t++] = (byte)0xF9; // SND_OFF
        data[t++] = (byte)0xF2; // Stop

        // Voice Data at 40 (0x28)
        int v = 40;
        // Construct a voice with unique values for every byte to verify mapping.
        // Index 0: Algo/FB
        // Indices 1-4: DT/MUL
        // ...
        // Indices 21-24: TL

        for (int i = 0; i < 25; i++) {
            data[v + i] = (byte)(i + 10);
        }
        // TL Bytes:
        // Index 21 (0x15): 21+10 = 31 (0x1F) -> Op 1 TL
        // Index 22 (0x16): 22+10 = 32 (0x20) -> Op 3 TL
        // Index 23 (0x17): 23+10 = 33 (0x21) -> Op 2 TL
        // Index 24 (0x18): 24+10 = 34 (0x22) -> Op 4 TL

        Sonic2SmpsData smps = new Sonic2SmpsData(data, 0);
        MockSynthesizer synth = new MockSynthesizer();
        DacData dac = new DacData(new HashMap<>(), new HashMap<>());

        SmpsSequencer seq = new SmpsSequencer(smps, dac, synth, Sonic2SmpsSequencerConfig.CONFIG);

        short[] buf = new short[2000];
        seq.read(buf);

        // 1. Verify setInstrument received raw data in Slot Order (1,3,2,4)
        assertTrue("setInstrument should be called", synth.setInstrumentCalled);
        assertEquals("Channel ID should be 0 (FM1)", 0, synth.lastChannelId);

        byte[] expectedVoice = new byte[25];
        System.arraycopy(data, v, expectedVoice, 0, 25);

        assertArrayEquals("Voice data should be passed exactly as read (Slot Order)",
                expectedVoice, synth.lastVoice);

        // 2. Verify SND_OFF (F9) writes
        // Should write 0x0F to 0x88 (Op2) and 0x8C (Op4) on Port 0 (FM1 is ch 0, port 0)
        // FM1 is Channel 0.
        // hwCh=0. Port=0. Ch=0.
        // Writes: 0x88+0 = 0x88. 0x8C+0 = 0x8C.

        boolean found88 = false;
        boolean found8C = false;
        for (String log : synth.writeLog) {
            if (log.equals("P0_R88_V0F")) found88 = true;
            if (log.equals("P0_R8C_V0F")) found8C = true;
        }

        assertTrue("Should write 0x0F to 0x88 (Op2)", found88);
        assertTrue("Should write 0x0F to 0x8C (Op4)", found8C);
    }
}
