package uk.co.jamesj999.sonic.audio.synth;

import org.junit.Before;
import org.junit.Test;
import uk.co.jamesj999.sonic.audio.smps.DacData;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class Ym2612DacAmplitudeTest {

    private Ym2612Chip chip;
    private int[] leftBuf;
    private int[] rightBuf;

    @Before
    public void setUp() {
        chip = new Ym2612Chip();
        chip.reset();
        chip.write(0, 0x2B, 0x80); // Enable DAC
        leftBuf = new int[100];
        rightBuf = new int[100];
    }

    @Test
    public void testDacAmplitude() {
        // Create DAC data with a single full-scale sample
        Map<Integer, byte[]> samples = new HashMap<>();
        // 0xFF -> 255 - 128 = 127. Max positive value.
        // We use a few samples to ensure we catch it during render
        byte[] sampleData = new byte[] { (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF };
        samples.put(1, sampleData);

        Map<Integer, DacData.DacEntry> mapping = new HashMap<>();
        // Note 81 -> Sample 1, Rate 0 (doesn't matter much for amplitude)
        mapping.put(81, new DacData.DacEntry(1, 0));

        DacData dacData = new DacData(samples, mapping);
        chip.setDacData(dacData);
        chip.setDacInterpolate(false); // Disable interpolation for simpler check

        // Play the note
        chip.playDac(81);

        // Render
        chip.renderStereo(leftBuf, rightBuf);

        // Find max amplitude in buffers
        int maxAmp = 0;
        for (int val : leftBuf) {
            maxAmp = Math.max(maxAmp, Math.abs(val));
        }

        // Check expectation
        // Old Gain: 64.0 -> 127 * 64 = 8128
        // New Gain: 128.0 -> 127 * 128 = 16256

        System.out.println("Max DAC Amplitude: " + maxAmp);

        // We expect > 10000 with the fix.
        // With current code (Gain 64), this should be around 8128.
        // With fix (Gain 128), this should be around 16256.

        // For the purpose of the test, let's assert it is within the 16-bit range we target.
        // > 12000 implies gain > 64.
        assertTrue("DAC Amplitude should be boosted to match 16-bit FM levels (expected > 12000, got " + maxAmp + ")", maxAmp > 12000);
    }
}
