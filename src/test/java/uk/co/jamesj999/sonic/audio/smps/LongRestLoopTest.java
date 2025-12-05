package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.synth.VirtualSynthesizer;

import static org.junit.Assert.assertTrue;

public class LongRestLoopTest {

    @Test
    public void testLongLoopOfRests() {
        byte[] data = new byte[2048];

        // Header setup
        data[0x02] = 1; // 1 FM
        data[0x03] = 1; // 1 PSG
        data[0x04] = 1; // Timing 1
        data[0x05] = (byte) 0x80; // Tempo 128

        int dacPtr = 0x100;
        data[0x06] = (byte) (dacPtr & 0xFF);
        data[0x07] = (byte) (dacPtr >> 8);

        int fmPtr = 0x200;
        data[0x0A] = (byte) (fmPtr & 0xFF);
        data[0x0B] = (byte) (fmPtr >> 8);
        data[0x0C] = 0;
        data[0x0D] = 0;

        int psgPtr = 0x300;
        data[0x0E] = (byte) (psgPtr & 0xFF);
        data[0x0F] = (byte) (psgPtr >> 8);
        data[0x10] = 0;
        data[0x11] = 0;

        int loopCount = 75;
        int restDuration = 10;

        // DAC Track construction
        {
            int pos = dacPtr;
            int startPos = pos;
            data[pos++] = (byte) 0x80; data[pos++] = (byte) restDuration;
            data[pos++] = (byte) 0xF7; data[pos++] = 0x00; data[pos++] = (byte) loopCount;
            data[pos++] = (byte) (startPos & 0xFF); data[pos++] = (byte) (startPos >> 8);
            data[pos++] = (byte) 0xF2;
        }

        // FM Track construction
        {
            int pos = fmPtr;
            int startPos = pos;
            data[pos++] = (byte) 0x80; data[pos++] = (byte) restDuration;
            data[pos++] = (byte) 0xF7; data[pos++] = 0x00; data[pos++] = (byte) loopCount;
            data[pos++] = (byte) (startPos & 0xFF); data[pos++] = (byte) (startPos >> 8);
            data[pos++] = (byte) 0xF2;
        }

        // PSG Track construction
        {
            int pos = psgPtr;
            int startPos = pos;
            data[pos++] = (byte) 0x80; data[pos++] = (byte) restDuration;
            data[pos++] = (byte) 0xF7; data[pos++] = 0x00; data[pos++] = (byte) loopCount;
            data[pos++] = (byte) (startPos & 0xFF); data[pos++] = (byte) (startPos >> 8);
            data[pos++] = (byte) 0xF2;
        }

        SmpsData smpsData = new SmpsData(data, 0, true);
        SmpsSequencer sequencer = new SmpsSequencer(smpsData, null, new VirtualSynthesizer());

        int totalFrames = 0;
        int activeFrames = 0;
        short[] buffer = new short[735];

        // Run playback up to a safe limit (2000 frames)
        while (totalFrames < 2000) {
            SmpsSequencer.DebugState state = sequencer.debugState();
            boolean anyActive = state.tracks.stream().anyMatch(t -> t.active);

            if (!anyActive) {
                break;
            }

            activeFrames++;
            sequencer.read(buffer);
            totalFrames++;
        }

        // Expected approx 1500 frames (25 seconds)
        assertTrue("Should run for at least 24 seconds (1440 frames)", activeFrames > 1440);
        assertTrue("Should stop around 26 seconds (1560 frames)", activeFrames < 1600);

        // Verify all tracks finished
        SmpsSequencer.DebugState state = sequencer.debugState();
        for (SmpsSequencer.DebugTrack t : state.tracks) {
            assertTrue("Track " + t.type + " should be inactive", !t.active);
        }
    }
}
