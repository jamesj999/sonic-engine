package com.openggf.audio.driver;

import com.openggf.audio.AudioBenchmarkMemoryProbe;
import com.openggf.audio.smps.AbstractSmpsData;
import com.openggf.audio.smps.DacData;
import com.openggf.audio.smps.SmpsSequencer;
import com.openggf.audio.synth.Ym2612Chip;
import com.openggf.data.Rom;
import com.openggf.tests.TestEnvironment;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic2.audio.Sonic2SmpsSequencerConfig;
import com.openggf.game.sonic2.audio.smps.Sonic2SmpsLoader;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Locale;

import static com.openggf.tests.RomTestUtils.ensureRomAvailable;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Measurement harness, not a regression test: quantifies fade-window render
 * throughput of the real SMPS synthesis stack (SmpsDriver -> SmpsSequencer ->
 * Ym2612Chip / PsgChip / BlipResampler) in production-shaped 1024-frame
 * buffer chunks with the production-default read mode.
 *
 * <p>Each iteration constructs a fresh driver with ROM-loaded EHZ music,
 * pre-rolls 1.5 s untimed (JIT warmup + steady state), triggers the ROM
 * default fade-out (0x28 steps, 3 frames/step), then renders the full 4.5 s
 * fade window timed. The headline number is rendered-seconds of audio per
 * wall-second, median over {@value #ITERATIONS} iterations, printed as a
 * parseable {@code FADE_THROUGHPUT} line.
 *
 * <p>Assertions are sanity-only (audible PCM, positive throughput) so the
 * harness never fails on slow machines. ROM-gated: skipped when the S2 ROM
 * is absent. Designed to compile against both the perf-optimization HEAD and
 * the pre-optimization baseline (a737d65a9) for apples-to-apples comparison.
 */
@Tag("performance-measurement")
public class TestSmpsFadeAudioThroughput {

    private static final double SAMPLE_RATE = Ym2612Chip.getDefaultOutputRate();
    private static final int BUFFER_FRAMES = 1024;

    private static final int MUSIC_EHZ = 0x81;

    // ROM zFadeOutMusic defaults: 0x28 steps, 3 frames between steps.
    private static final int FADE_OUT_STEPS = 0x28;
    private static final int FADE_OUT_DELAY = 3;

    // Whole-chunk counts (the era-stable SmpsDriver.read(short[]) overload
    // always fills the full buffer), rounded up from 1.5 s / 4.5 s.
    private static final int PRE_ROLL_CHUNKS = (int) Math.ceil(1.5 * SAMPLE_RATE / BUFFER_FRAMES);
    // Fade-out lasts ~steps*(delay+1) tempo frames (~2.7 s); render 4.5 s after
    // the trigger so completion, sequencer removal, and silent tail are covered.
    private static final int FADE_WINDOW_CHUNKS = (int) Math.ceil(4.5 * SAMPLE_RATE / BUFFER_FRAMES);
    private static final int FADE_WINDOW_FRAMES = FADE_WINDOW_CHUNKS * BUFFER_FRAMES;

    private static final int ITERATIONS = 5;

    private static Rom rom;
    private static Sonic2SmpsLoader loader;
    private static DacData dacData;

    @BeforeAll
    public static void setUpClass() {
        TestEnvironment.resetAll();
        File romFile = ensureRomAvailable();
        if (romFile == null) {
            return;
        }
        rom = new Rom();
        if (!rom.open(romFile.getAbsolutePath())) {
            rom = null;
            return;
        }
        loader = new Sonic2SmpsLoader(rom);
        dacData = loader.loadDacData();
    }

    @AfterAll
    public static void tearDownClass() {
        SessionManager.clear();
    }

    @Test
    public void fadeWindowRenderThroughput() {
        assumeTrue(loader != null, "ROM not available, skipping fade throughput measurement");

        FadeRun[] runs = new FadeRun[ITERATIONS];
        double[] throughputs = new double[ITERATIONS];
        long[] allocatedBytes = new long[ITERATIONS];
        boolean allocatedSupported = true;
        for (int i = 0; i < ITERATIONS; i++) {
            runs[i] = renderFadeWindowOnce();
            throughputs[i] = runs[i].throughput();
            allocatedBytes[i] = runs[i].allocatedBytes();
            allocatedSupported &= runs[i].supported();
        }

        double median = median(throughputs.clone());
        long medianAllocatedBytes = allocatedSupported ? median(allocatedBytes.clone()) : -1L;
        StringBuilder iterations = new StringBuilder("[");
        for (int i = 0; i < throughputs.length; i++) {
            if (i > 0) {
                iterations.append(',');
            }
            iterations.append(String.format(Locale.ROOT, "%.2f", throughputs[i]));
        }
        iterations.append("]");
        System.out.printf(Locale.ROOT,
                "FADE_THROUGHPUT median=%.2f unit=renderedSecPerWallSec "
                        + "medianAllocatedBytes=%d allocatedSupported=%s iterations=%s "
                        + "preRollSec=1.5 fadeWindowFrames=%d bufferFrames=%d sampleRate=%.0f%n",
                median, medianAllocatedBytes, allocatedSupported, iterations,
                FADE_WINDOW_FRAMES, BUFFER_FRAMES, SAMPLE_RATE);

        assertTrue(median > 0, "fade-window render throughput must be positive");
        if (allocatedSupported) {
            assertTrue(medianAllocatedBytes >= 0,
                    "fade-window allocation must be non-negative when supported");
        }
    }

    /**
     * One full setup + measurement pass: fresh driver, untimed pre-roll, fade
     * trigger, timed fade-window render. Returns rendered-seconds per
     * wall-second for the timed window.
     */
    private FadeRun renderFadeWindowOnce() {
        AbstractSmpsData musicData = loader.loadMusic(MUSIC_EHZ);
        assertNotNull(musicData, "Music data should load");

        SmpsDriver driver = SmpsDriverTestAccess.create(SAMPLE_RATE);
        driver.setRegion(SmpsSequencer.Region.NTSC);
        // Intentionally no read-mode override: measure the production default.

        SmpsSequencer musicSeq = new SmpsSequencer(musicData, dacData, driver, Sonic2SmpsSequencerConfig.CONFIG);
        musicSeq.setSampleRate(SAMPLE_RATE);
        driver.addSequencer(musicSeq, false);

        short[] buffer = new short[BUFFER_FRAMES * 2];

        // Untimed pre-roll: JIT warmup and musical steady state.
        renderChunks(driver, buffer, PRE_ROLL_CHUNKS);

        musicSeq.triggerFadeOut(FADE_OUT_STEPS, FADE_OUT_DELAY);

        // Record per-chunk peak amplitude so we can assert fade behavior in
        // addition to throughput.
        int[] chunkPeaks = new int[FADE_WINDOW_CHUNKS];
        AudioBenchmarkMemoryProbe probe = AudioBenchmarkMemoryProbe.create();
        boolean[] audible = new boolean[1];
        AudioBenchmarkMemoryProbe.RunResult measurement = probe.measureTimedRun(
                () -> audible[0] = renderChunks(driver, buffer, FADE_WINDOW_CHUNKS, chunkPeaks));

        assertTrue(audible[0], "fade window should contain audible PCM before fade completion");
        assertTrue(measurement.elapsedNanos() > 0, "timed window must consume wall time");
        if (measurement.allocatedBytesSupported()) {
            assertTrue(measurement.allocatedBytes() >= 0,
                    "fade-window allocation must be non-negative when supported");
        }

        // Behavioral invariant: a fade-out must reduce amplitude over the window.
        // The early peak (start of fade) should be louder than the late peak
        // (near/after fade completion). The fade does not necessarily reach full
        // silence within this window, so we assert decreasing amplitude rather
        // than a silent tail.
        int earlyPeak = chunkPeaks[0];
        int latePeak = chunkPeaks[chunkPeaks.length - 1];
        assertTrue(earlyPeak > 0, "fade window should start with audible PCM");
        assertTrue(latePeak < earlyPeak,
                "fade-out must reduce PCM amplitude across the fade window "
                        + "(early peak=" + earlyPeak + ", late peak=" + latePeak + ")");

        double renderedSeconds = FADE_WINDOW_FRAMES / SAMPLE_RATE;
        return new FadeRun(
                renderedSeconds / (measurement.elapsedNanos() / 1_000_000_000.0),
                measurement.allocatedBytes(),
                measurement.allocatedBytesSupported());
    }

    /** Renders {@code chunks} full buffers; returns true if any sample was non-zero. */
    private static boolean renderChunks(SmpsDriver driver, short[] buffer, int chunks) {
        return renderChunks(driver, buffer, chunks, null);
    }

    /**
     * Renders {@code chunks} full buffers; returns true if any sample was
     * non-zero. When {@code chunkPeaks} is non-null, records each chunk's peak
     * absolute amplitude into it (length must be {@code >= chunks}).
     */
    private static boolean renderChunks(SmpsDriver driver, short[] buffer, int chunks, int[] chunkPeaks) {
        boolean nonZero = false;
        for (int chunk = 0; chunk < chunks; chunk++) {
            SmpsDriverTestAccess.read(driver, buffer);
            int peak = 0;
            for (short s : buffer) {
                int mag = Math.abs(s);
                if (mag > peak) {
                    peak = mag;
                }
            }
            if (peak != 0) {
                nonZero = true;
            }
            if (chunkPeaks != null) {
                chunkPeaks[chunk] = peak;
            }
        }
        return nonZero;
    }

    private static double median(double[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        return values.length % 2 == 1 ? values[mid] : (values[mid - 1] + values[mid]) / 2.0;
    }

    private static long median(long[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        return values.length % 2 == 1
                ? values[mid]
                : Math.round((values[mid - 1] + values[mid]) / 2.0);
    }

    private record FadeRun(double throughput, long allocatedBytes, boolean supported) {
    }
}
