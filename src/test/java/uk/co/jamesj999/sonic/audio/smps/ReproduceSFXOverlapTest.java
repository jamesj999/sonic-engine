package uk.co.jamesj999.sonic.audio.smps;

import org.junit.Test;
import uk.co.jamesj999.sonic.audio.driver.SmpsDriver;
import uk.co.jamesj999.sonic.audio.smps.DacData;
import uk.co.jamesj999.sonic.audio.smps.SmpsSequencer;
import uk.co.jamesj999.sonic.audio.smps.Sonic2SfxData;

import java.util.ArrayList;
import java.util.HashMap;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReproduceSFXOverlapTest {

    static class FakeSonic2SfxData extends Sonic2SfxData {
        private final int[] psgPointers;

        public FakeSonic2SfxData(byte[] data, int[] psgPointers) {
            super(data, 0x8000, 0, 0);
            this.psgPointers = psgPointers;
        }

        @Override public int read16(int offset) {
             if (offset + 1 >= data.length) return 0;
             return (data[offset] & 0xFF) | ((data[offset + 1] & 0xFF) << 8);
        }
        @Override public int[] getFmPointers() { return new int[0]; }
        @Override public int[] getPsgPointers() { return psgPointers; }
        @Override public java.util.List<TrackEntry> getTrackEntries() {
            java.util.List<TrackEntry> list = new ArrayList<>();
            // Mock track entry: Channel C0 (PSG3)
            for (int ptr : psgPointers) {
                list.add(new TrackEntry(0, 0xC0, 0, 0, ptr));
            }
            return list;
        }
    }

    @Test
    public void testSfxOverlap() {
        // SFX 1 (Long Note)
        byte[] sfx1Data = new byte[0x8000];
        // Track data at index 10 (0x800A)
        int t1 = 10;
        sfx1Data[t1++] = (byte)0xBB; // Note
        sfx1Data[t1++] = (byte)0xFF; // Duration 255
        sfx1Data[t1++] = (byte)0xBB; // Note
        sfx1Data[t1++] = (byte)0xFF; // Duration 255
        // Total 510 ticks

        // Pass 0x800A as pointer
        FakeSonic2SfxData data1 = new FakeSonic2SfxData(sfx1Data, new int[] { 0x800A });

        // SFX 2 (Short Note)
        byte[] sfx2Data = new byte[0x8000];
        int t2 = 10;
        sfx2Data[t2++] = (byte)0xBB; // Note
        sfx2Data[t2++] = (byte)0x10; // Duration 16
        sfx2Data[t2++] = (byte)0xF2; // Stop

        FakeSonic2SfxData data2 = new FakeSonic2SfxData(sfx2Data, new int[] { 0x800A });

        SmpsDriver driver = new SmpsDriver();

        SmpsSequencer seq1 = new SmpsSequencer(data1, new DacData(new HashMap<>(), new HashMap<>()), driver);
        seq1.setSfxMode(true);

        SmpsSequencer seq2 = new SmpsSequencer(data2, new DacData(new HashMap<>(), new HashMap<>()), driver);
        seq2.setSfxMode(true);

        driver.addSequencer(seq1, true);

        short[] buf = new short[32000]; // 16000 frames -> ~21 ticks

        // Run SFX 1 for a bit
        driver.read(buf);
        assertFalse("SFX 1 should be running", seq1.isComplete());

        // Add SFX 2 (Same channel C0)
        driver.addSequencer(seq2, true);

        // Run SFX 2. Duration 16 ticks.
        driver.read(buf);

        assertTrue("SFX 2 should finish", seq2.isComplete());

        // SFX 1 should be KILLED by overlap (Expect Fail currently)
        assertTrue("SFX 1 should be killed by overlap", seq1.isComplete());
    }
}
