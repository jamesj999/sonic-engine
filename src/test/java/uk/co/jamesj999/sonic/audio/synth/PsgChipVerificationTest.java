package uk.co.jamesj999.sonic.audio.synth;

import org.junit.Test;
import static org.junit.Assert.*;

public class PsgChipVerificationTest {

    @Test
    public void testToneFrequency() {
        PsgChip psg = new PsgChip();

        // Set Volume to max for Channel 0
        // Latch (1), Ch 0 (00), Vol (1), Data (0000 - 0dB) -> 10010000 -> 0x90
        psg.write(0x90);

        // Set Tone for Channel 0 to N=100
        // Target Freq = 3579545 / (32 * 100) = 1118.6078 Hz
        // N = 100 -> 0x064
        // Low 4 bits: 0x4
        // High 6 bits: 0x06

        // Latch (1), Ch 0 (00), Tone (0), Data (0100) -> 10000100 -> 0x84
        psg.write(0x84);
        // Data (0), Data (000110) -> 00000110 -> 0x06
        psg.write(0x06);

        int sampleRate = 44100;
        int numSamples = sampleRate; // 1 second
        int[] left = new int[numSamples];
        int[] right = new int[numSamples];

        psg.renderStereo(left, right);

        // Count zero crossings (rising edge)
        int crossings = 0;
        boolean wasPositive = left[0] > 0;

        for (int i = 1; i < numSamples; i++) {
            boolean isPositive = left[i] > 0;
            if (!wasPositive && isPositive) {
                crossings++;
            }
            wasPositive = isPositive;
        }

        double measuredFreq = crossings;
        double expectedFreq = 3579545.0 / (32 * 100);

        System.out.println("Measured Frequency: " + measuredFreq);
        System.out.println("Expected Frequency: " + expectedFreq);

        // Allow some margin of error due to integer sampling
        double error = Math.abs(measuredFreq - expectedFreq);
        assertTrue("Frequency should be within 10Hz of expected. Measured: " + measuredFreq + ", Expected: " + expectedFreq, error < 10);
    }
}
