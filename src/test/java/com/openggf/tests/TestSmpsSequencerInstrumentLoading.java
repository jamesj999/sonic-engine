package com.openggf.tests;
import org.junit.jupiter.api.Test;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.Sonic1SmpsData;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

public class TestSmpsSequencerInstrumentLoading {
    @Test
    public void sonic1VoiceLoaderNormalizesItsRawMiddleOperators() {
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

        data[2] = 2; // 2 FM Channels (to reach FM1, as Chn 0 is DAC)
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
        // Generate source voice
        for(int i=0; i<25; i++) {
            data[v+i] = (byte) (i + 10);
        }

        // Expected Voice: S1 voices (InsMode=DEFAULT) store operator groups as Op4,Op3,Op2,Op1.
        // getVoice() converts to S2 format (Op4,Op2,Op3,Op1) by swapping the middle two
        // bytes of each 4-byte group for the S1 direct-write profile.
        byte[] expectedVoice = new byte[25];
        System.arraycopy(data, v, expectedVoice, 0, 25);
        // Apply the same swap that getVoice() performs
        for (int g = 1; g < 25; g += 4) {
            byte tmp = expectedVoice[g + 1];
            expectedVoice[g + 1] = expectedVoice[g + 2];
            expectedVoice[g + 2] = tmp;
        }

        // Explicitly use the S1 big-endian loader to verify its 25-byte normalization.
        AbstractSmpsData smps = new Sonic1SmpsData(data, 0);
        assertArrayEquals(expectedVoice, smps.getVoice(0), "S1 voice data should normalize once at load");
    }

}
