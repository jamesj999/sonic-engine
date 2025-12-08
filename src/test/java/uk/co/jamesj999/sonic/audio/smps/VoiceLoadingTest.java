package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import uk.co.jamesj999.sonic.audio.smps.AbstractSmpsData;
import uk.co.jamesj999.sonic.audio.smps.Sonic1SmpsData;
import uk.co.jamesj999.sonic.audio.smps.Sonic2SmpsData;

public class VoiceLoadingTest {

    static class MockSynthesizer extends VirtualSynthesizer {
        byte[] lastInstrument;
        int lastChannelId;

        @Override
        public void setInstrument(int channelId, byte[] voice) {
            this.lastChannelId = channelId;
            this.lastInstrument = new byte[voice.length];
            System.arraycopy(voice, 0, this.lastInstrument, 0, voice.length);
        }
    }

    @Test
    public void testVoiceLoadingSonic1() {
        // Big Endian (Sonic 1)
        // Source: Header, DT, RS, AM, D2R, RR, TL
        // Source Order: Standard (1, 3, 2, 4)
        // Expect Target: Logical (1, 2, 3, 4). Swap Op2/Op3 (Indices 1 and 2).

        byte[] voiceData = new byte[25];
        voiceData[0] = (byte) 0x01; // Header
        fill4(voiceData, 1, 0x10); // DT
        fill4(voiceData, 5, 0x20); // RS
        fill4(voiceData, 9, 0x30); // AM
        fill4(voiceData, 13, 0x40); // D2R
        fill4(voiceData, 17, 0x50); // RR
        fill4(voiceData, 21, 0x60); // TL

        // Construct minimal SMPS data
        byte[] data = new byte[1024];

        // Header
        write16(data, 0, 0x200, false); // Voice Ptr
        data[2] = 2; // 2 Channels
        data[3] = 0; // 0 PSG
        data[4] = 1; // Div
        data[5] = 1; // Tempo
        write16(data, 6, 0x300, false); // DAC Ptr

        write16(data, 0x06, 0x100, false); // FM1 Ptr (not used as FM1, but needed for loop)

        write16(data, 0x0A, 0x100, false); // FM1 Ptr
        data[0x0C] = 0; // Key
        data[0x0D] = 0; // Vol

        // Track Data at 0x100
        data[0x100] = (byte) 0xF2; // Stop

        // Voice Data at 0x200
        System.arraycopy(voiceData, 0, data, 0x200, 25);

        AbstractSmpsData smpsData = new Sonic1SmpsData(data, 0); // S1 Big Endian
        MockSynthesizer synth = new MockSynthesizer();
        DacData dacData = new DacData(Collections.emptyMap(), Collections.emptyMap());

        SmpsSequencer seq = new SmpsSequencer(smpsData, dacData, synth);

        // Expected reordered data
        byte[] expected = new byte[25];
        expected[0] = (byte) 0x01;

        // DT (10). Source: 10, 11, 12, 13.
        // Swap indices 1 and 2: 10, 12, 11, 13.
        expected[1]=0x10; expected[2]=0x12; expected[3]=0x11; expected[4]=0x13;

        // TL (Moved from 21). Source: 60, 61, 62, 63. Swap: 60, 62, 61, 63.
        expected[5]=0x60; expected[6]=0x62; expected[7]=0x61; expected[8]=0x63;

        // RS (Moved from 5). Source: 20, 21, 22, 23. Swap: 20, 22, 21, 23.
        expected[9]=0x20; expected[10]=0x22; expected[11]=0x21; expected[12]=0x23;

        // AM (30). Swap: 30, 32, 31, 33.
        expected[13]=0x30; expected[14]=0x32; expected[15]=0x31; expected[16]=0x33;

        // D2R (40). Swap: 40, 42, 41, 43.
        expected[17]=0x40; expected[18]=0x42; expected[19]=0x41; expected[20]=0x43;

        // RR (50). Swap: 50, 52, 51, 53.
        expected[21]=0x50; expected[22]=0x52; expected[23]=0x51; expected[24]=0x53;

        assertArrayEquals("Sonic 1 Voice Reordering (Standard -> Logical)", expected, synth.lastInstrument);
    }

    @Test
    public void testVoiceLoadingSonic2() {
        // Little Endian (Sonic 2)
        // Source: Header, DT, RS, AM, D2R, RR, TL (TL is padding/unused)
        // Source order within 4 bytes: 1, 2, 3, 4 (Hardware)
        // Expect Target: Hardware (1, 2, 3, 4). No Swap.

        byte[] voiceData = new byte[25];
        voiceData[0] = (byte) 0x01; // Header
        // DT
        voiceData[1] = 0x11; voiceData[2] = 0x12; voiceData[3] = 0x13; voiceData[4] = 0x14;
        // RS
        voiceData[5] = 0x21; voiceData[6] = 0x22; voiceData[7] = 0x23; voiceData[8] = 0x24;
        // AM
        voiceData[9] = 0x31; voiceData[10] = 0x32; voiceData[11] = 0x33; voiceData[12] = 0x34;
        // D2R
        voiceData[13] = 0x41; voiceData[14] = 0x42; voiceData[15] = 0x43; voiceData[16] = 0x44;
        // RR
        voiceData[17] = 0x51; voiceData[18] = 0x52; voiceData[19] = 0x53; voiceData[20] = 0x54;
        // TL (Padding in S2)
        voiceData[21] = (byte)0xFF; voiceData[22] = (byte)0xFF; voiceData[23] = (byte)0xFF; voiceData[24] = (byte)0xFF;

        byte[] data = new byte[1024];

        // Header
        write16(data, 0, 0x200, true); // Voice Ptr, LE
        data[2] = 2; // 2 Channels
        data[3] = 0; // 0 PSG
        data[4] = 1; // Div
        data[5] = 1; // Tempo
        write16(data, 6, 0x300, true); // DAC Ptr

        write16(data, 0x0A, 0x100, true); // FM1 Ptr
        data[0x0C] = 0; // Key
        data[0x0D] = 0; // Vol

        data[0x100] = (byte) 0xF2; // Stop

        System.arraycopy(voiceData, 0, data, 0x200, 25);

        AbstractSmpsData smpsData = new Sonic2SmpsData(data, 0); // S2 Little Endian
        MockSynthesizer synth = new MockSynthesizer();
        DacData dacData = new DacData(Collections.emptyMap(), Collections.emptyMap());

        SmpsSequencer seq = new SmpsSequencer(smpsData, dacData, synth);

        // Expected reordered data (25 bytes for S2 normalization)
        // No Swap (Source HW -> Target HW)
        byte[] expected = new byte[25];
        expected[0] = (byte) 0x01;

        // DT (10). Source 1,2,3,4. Target 1,2,3,4.
        expected[1] = 0x11; expected[2] = 0x12; expected[3] = 0x13; expected[4] = 0x14;

        // TL (0)
        expected[5] = 0; expected[6] = 0; expected[7] = 0; expected[8] = 0;

        // RS (20) -> Moved to 9. No Swap.
        expected[9] = 0x21; expected[10] = 0x22; expected[11] = 0x23; expected[12] = 0x24;

        // AM (30) -> Moved to 13. No Swap.
        expected[13] = 0x31; expected[14] = 0x32; expected[15] = 0x33; expected[16] = 0x34;

        // D2R (40) -> Moved to 17. No Swap.
        expected[17] = 0x41; expected[18] = 0x42; expected[19] = 0x43; expected[20] = 0x44;

        // RR (50) -> Moved to 21. No Swap.
        expected[21] = 0x51; expected[22] = 0x52; expected[23] = 0x53; expected[24] = 0x54;

        assertArrayEquals("Sonic 2 Voice Reordering (Hardware -> Logical)", expected, synth.lastInstrument);
    }

    private void fill4(byte[] arr, int offset, int val) {
        for(int i=0; i<4; i++) arr[offset+i] = (byte)(val + i);
    }

    private void write16(byte[] data, int offset, int val, boolean le) {
        if (le) {
            data[offset] = (byte)(val & 0xFF);
            data[offset+1] = (byte)((val >> 8) & 0xFF);
        } else {
            data[offset] = (byte)((val >> 8) & 0xFF);
            data[offset+1] = (byte)(val & 0xFF);
        }
    }
}
