package uk.co.jamesj999.sonic.tests.audio;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Utility to dump YM2612 lookup tables for verification and embedding.
 * Run as a standalone program to generate table constants.
 * Also includes a JUnit test to verify table checksums for reproducibility.
 */
public class Ym2612TableDumper {

    private static final int SIN_HBITS = 12;
    private static final int SIN_LBITS = 14;
    private static final int ENV_HBITS = 12;
    private static final int LFO_HBITS = 10;

    private static final int SIN_LEN = 1 << SIN_HBITS; // 4096
    private static final int ENV_LEN = 1 << ENV_HBITS; // 4096
    private static final int LFO_LEN = 1 << LFO_HBITS; // 1024
    private static final int TL_LEN = ENV_LEN * 3;

    private static final double ENV_STEP = 96.0 / ENV_LEN;
    private static final int PG_CUT_OFF = (int) (78.0 / ENV_STEP);

    private static final int MAX_OUT_BITS = SIN_HBITS + SIN_LBITS + 2; // 28
    private static final int MAX_OUT = (1 << MAX_OUT_BITS) - 1;

    public static void main(String[] args) {
        System.out.println("=== YM2612 Table Verification ===");
        System.out.println("SIN_LEN = " + SIN_LEN);
        System.out.println("ENV_LEN = " + ENV_LEN);
        System.out.println("TL_LEN = " + TL_LEN);
        System.out.println("LFO_LEN = " + LFO_LEN);
        System.out.println("PG_CUT_OFF = " + PG_CUT_OFF);
        System.out.println("MAX_OUT = " + MAX_OUT);
        System.out.println();

        // Generate and verify TL_TAB
        int[] tlTab = new int[TL_LEN * 2];
        for (int i = 0; i < TL_LEN; i++) {
            if (i >= PG_CUT_OFF) {
                tlTab[TL_LEN + i] = 0;
                tlTab[i] = 0;
            } else {
                double x = MAX_OUT;
                x /= StrictMath.pow(10, (ENV_STEP * i) / 20);
                tlTab[i] = (int) x;
                tlTab[TL_LEN + i] = -tlTab[i];
            }
        }

        System.out.println("TL_TAB sample values:");
        System.out.println("  TL_TAB[0] = " + tlTab[0] + " (expected: " + MAX_OUT + ")");
        System.out.println("  TL_TAB[1] = " + tlTab[1]);
        System.out.println("  TL_TAB[100] = " + tlTab[100]);
        System.out.println("  TL_TAB[" + (PG_CUT_OFF - 1) + "] = " + tlTab[PG_CUT_OFF - 1]);
        System.out.println("  TL_TAB[" + PG_CUT_OFF + "] = " + tlTab[PG_CUT_OFF] + " (should be 0)");
        System.out.println();

        // Generate SIN_TAB
        int[] sinTab = new int[SIN_LEN];
        for (int i = 1; i <= SIN_LEN / 4; i++) {
            double x = StrictMath.sin(2.0 * StrictMath.PI * i / SIN_LEN);
            x = 20 * StrictMath.log10(1.0 / x);
            int j = (int) (x / ENV_STEP);
            if (j > PG_CUT_OFF) j = PG_CUT_OFF;
            sinTab[i] = j;
            sinTab[(SIN_LEN / 2) - i] = j;
            sinTab[(SIN_LEN / 2) + i] = TL_LEN + j;
            sinTab[SIN_LEN - i] = TL_LEN + j;
        }
        sinTab[0] = PG_CUT_OFF;
        sinTab[SIN_LEN / 2] = PG_CUT_OFF;

        System.out.println("SIN_TAB sample values:");
        System.out.println("  SIN_TAB[0] = " + sinTab[0] + " (PG_CUT_OFF)");
        System.out.println("  SIN_TAB[1] = " + sinTab[1]);
        System.out.println("  SIN_TAB[1024] = " + sinTab[1024]); // 1/4 wave
        System.out.println("  SIN_TAB[2048] = " + sinTab[2048] + " (half wave, should be PG_CUT_OFF)");
        System.out.println();

        // Generate ENV_TAB
        int[] envTab = new int[2 * ENV_LEN + 8];
        for (int i = 0; i < ENV_LEN; i++) {
            double x = StrictMath.pow(((double) (ENV_LEN - 1 - i) / ENV_LEN), 8.0);
            x *= ENV_LEN;
            envTab[i] = (int) x;
            x = StrictMath.pow(((double) i / ENV_LEN), 1.0);
            x *= ENV_LEN;
            envTab[ENV_LEN + i] = (int) x;
        }

        System.out.println("ENV_TAB sample values:");
        System.out.println("  ENV_TAB[0] = " + envTab[0] + " (attack start, should be ~4095)");
        System.out.println("  ENV_TAB[2048] = " + envTab[2048]);
        System.out.println("  ENV_TAB[4095] = " + envTab[4095] + " (attack end, should be 0)");
        System.out.println("  ENV_TAB[4096] = " + envTab[4096] + " (decay start, should be 0)");
        System.out.println("  ENV_TAB[8191] = " + envTab[8191] + " (decay end, should be ~4095)");
        System.out.println();

        // Generate LFO_ENV_TAB
        int[] lfoEnvTab = new int[LFO_LEN];
        int[] lfoFreqTab = new int[LFO_LEN];
        for (int i = 0; i < LFO_LEN; i++) {
            double x = StrictMath.sin(2.0 * StrictMath.PI * i / LFO_LEN);
            x += 1.0;
            x /= 2.0;
            x *= 11.8 / ENV_STEP;
            lfoEnvTab[i] = (int) x;

            x = StrictMath.sin(2.0 * StrictMath.PI * i / LFO_LEN);
            x *= (double) ((1 << (LFO_HBITS - 1)) - 1);
            lfoFreqTab[i] = (int) x;
        }

        System.out.println("LFO_ENV_TAB sample values:");
        System.out.println("  LFO_ENV_TAB[0] = " + lfoEnvTab[0]);
        System.out.println("  LFO_ENV_TAB[256] = " + lfoEnvTab[256] + " (1/4 wave, should be max)");
        System.out.println("  LFO_ENV_TAB[512] = " + lfoEnvTab[512] + " (half wave)");
        System.out.println();

        System.out.println("LFO_FREQ_TAB sample values:");
        System.out.println("  LFO_FREQ_TAB[0] = " + lfoFreqTab[0] + " (should be 0)");
        System.out.println("  LFO_FREQ_TAB[256] = " + lfoFreqTab[256] + " (1/4 wave, should be max ~511)");
        System.out.println("  LFO_FREQ_TAB[512] = " + lfoFreqTab[512] + " (half wave, should be ~0)");
        System.out.println();

        // Checksum for verification
        long tlSum = 0, sinSum = 0, envSum = 0, lfoEnvSum = 0, lfoFreqSum = 0;
        for (int v : tlTab) tlSum += v;
        for (int v : sinTab) sinSum += v;
        for (int v : envTab) envSum += v;
        for (int v : lfoEnvTab) lfoEnvSum += v;
        for (int v : lfoFreqTab) lfoFreqSum += v;

        System.out.println("=== Table Checksums (for cross-platform verification) ===");
        System.out.println("TL_TAB checksum: " + tlSum);
        System.out.println("SIN_TAB checksum: " + sinSum);
        System.out.println("ENV_TAB checksum: " + envSum);
        System.out.println("LFO_ENV_TAB checksum: " + lfoEnvSum);
        System.out.println("LFO_FREQ_TAB checksum: " + lfoFreqSum);
    }

    // Expected checksums from StrictMath-based table generation
    // These values are deterministic across all Java implementations
    private static final long EXPECTED_TL_SUM = 0L; // Positive and negative values cancel out
    private static final long EXPECTED_SIN_SUM = 26204188L;
    private static final long EXPECTED_ENV_SUM = 10247175L;
    private static final long EXPECTED_LFO_ENV_SUM = 257287L;
    private static final long EXPECTED_LFO_FREQ_SUM = 0L; // Symmetric around 0

    @Test
    public void testTableChecksumsAreReproducible() {
        // Generate tables using same logic as Ym2612Chip
        int[] tlTab = new int[TL_LEN * 2];
        for (int i = 0; i < TL_LEN; i++) {
            if (i >= PG_CUT_OFF) {
                tlTab[TL_LEN + i] = 0;
                tlTab[i] = 0;
            } else {
                double x = MAX_OUT;
                x /= StrictMath.pow(10, (ENV_STEP * i) / 20);
                tlTab[i] = (int) x;
                tlTab[TL_LEN + i] = -tlTab[i];
            }
        }

        int[] sinTab = new int[SIN_LEN];
        for (int i = 1; i <= SIN_LEN / 4; i++) {
            double x = StrictMath.sin(2.0 * StrictMath.PI * i / SIN_LEN);
            x = 20 * StrictMath.log10(1.0 / x);
            int j = (int) (x / ENV_STEP);
            if (j > PG_CUT_OFF) j = PG_CUT_OFF;
            sinTab[i] = j;
            sinTab[(SIN_LEN / 2) - i] = j;
            sinTab[(SIN_LEN / 2) + i] = TL_LEN + j;
            sinTab[SIN_LEN - i] = TL_LEN + j;
        }
        sinTab[0] = PG_CUT_OFF;
        sinTab[SIN_LEN / 2] = PG_CUT_OFF;

        int[] envTab = new int[2 * ENV_LEN + 8];
        for (int i = 0; i < ENV_LEN; i++) {
            double x = StrictMath.pow(((double) (ENV_LEN - 1 - i) / ENV_LEN), 8.0);
            x *= ENV_LEN;
            envTab[i] = (int) x;
            x = StrictMath.pow(((double) i / ENV_LEN), 1.0);
            x *= ENV_LEN;
            envTab[ENV_LEN + i] = (int) x;
        }

        int[] lfoEnvTab = new int[LFO_LEN];
        int[] lfoFreqTab = new int[LFO_LEN];
        for (int i = 0; i < LFO_LEN; i++) {
            double x = StrictMath.sin(2.0 * StrictMath.PI * i / LFO_LEN);
            x += 1.0;
            x /= 2.0;
            x *= 11.8 / ENV_STEP;
            lfoEnvTab[i] = (int) x;

            x = StrictMath.sin(2.0 * StrictMath.PI * i / LFO_LEN);
            x *= (double) ((1 << (LFO_HBITS - 1)) - 1);
            lfoFreqTab[i] = (int) x;
        }

        // Compute checksums
        long tlSum = 0, sinSum = 0, envSum = 0, lfoEnvSum = 0, lfoFreqSum = 0;
        for (int v : tlTab) tlSum += v;
        for (int v : sinTab) sinSum += v;
        for (int v : envTab) envSum += v;
        for (int v : lfoEnvTab) lfoEnvSum += v;
        for (int v : lfoFreqTab) lfoFreqSum += v;

        // Verify checksums match expected values
        assertEquals("TL_TAB checksum mismatch", EXPECTED_TL_SUM, tlSum);
        assertEquals("SIN_TAB checksum mismatch", EXPECTED_SIN_SUM, sinSum);
        assertEquals("ENV_TAB checksum mismatch", EXPECTED_ENV_SUM, envSum);
        assertEquals("LFO_ENV_TAB checksum mismatch", EXPECTED_LFO_ENV_SUM, lfoEnvSum);
        assertEquals("LFO_FREQ_TAB checksum mismatch", EXPECTED_LFO_FREQ_SUM, lfoFreqSum);
    }

    @Test
    public void testKeyTableValues() {
        // Verify specific table values that are critical for accuracy
        int[] tlTab = new int[TL_LEN * 2];
        for (int i = 0; i < TL_LEN; i++) {
            if (i >= PG_CUT_OFF) {
                tlTab[TL_LEN + i] = 0;
                tlTab[i] = 0;
            } else {
                double x = MAX_OUT;
                x /= StrictMath.pow(10, (ENV_STEP * i) / 20);
                tlTab[i] = (int) x;
                tlTab[TL_LEN + i] = -tlTab[i];
            }
        }

        // TL_TAB[0] should be MAX_OUT (full volume)
        assertEquals("TL_TAB[0] should be MAX_OUT", MAX_OUT, tlTab[0]);

        // TL_TAB[PG_CUT_OFF] should be 0 (cutoff point)
        assertEquals("TL_TAB[PG_CUT_OFF] should be 0", 0, tlTab[PG_CUT_OFF]);

        // Negative table should be symmetric
        assertEquals("TL_TAB negative should be symmetric", -tlTab[0], tlTab[TL_LEN]);
        assertEquals("TL_TAB negative should be symmetric", -tlTab[100], tlTab[TL_LEN + 100]);
    }
}

