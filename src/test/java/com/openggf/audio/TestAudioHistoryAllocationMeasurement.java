package com.openggf.audio;

import com.openggf.audio.runtime.PcmHistoryRing;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.configuration.SonicConfigurationService;
import com.openggf.debug.PerformanceProfiler;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("performance-measurement")
class TestAudioHistoryAllocationMeasurement {

    private static final int SAMPLE_RATE = 48_000;
    private static final int FRAME_RATE = 60;
    private static final int MEASUREMENT_ITERATIONS = 5;

    @Test
    void reportsProducerAndCaptureLeaseHistoryOwnership() throws Exception {
        SonicConfigurationService config = SonicConfigurationService.getInstance();
        AudioManager audio = AudioManager.getInstance();
        try {
            cleanupAudio(audio);
            config.resetToDefaults();

            AudioBenchmarkMemoryProbe probe = AudioBenchmarkMemoryProbe.create();

            // Discard equivalent first-touch work (class loading, MXBean setup,
            // JIT compilation) before collecting fresh-instance samples.
            measureProducerHistoryAllocation(config, audio, probe);
            measureCaptureLeaseAllocation(config, audio, probe);

            AllocationRun[] producerRuns = new AllocationRun[MEASUREMENT_ITERATIONS];
            AllocationRun[] captureRuns = new AllocationRun[MEASUREMENT_ITERATIONS];
            for (int i = 0; i < MEASUREMENT_ITERATIONS; i++) {
                producerRuns[i] = measureProducerHistoryAllocation(config, audio, probe);
                captureRuns[i] = measureCaptureLeaseAllocation(config, audio, probe);
            }

            long[] producerBytes = allocatedBytes(producerRuns);
            long[] captureLeaseBytes = allocatedBytes(captureRuns);
            boolean allocatedSupported = allocationSupported(producerRuns)
                    && allocationSupported(captureRuns);
            long producerMedianBytes =
                    allocatedSupported ? median(producerBytes.clone()) : -1L;
            long captureLeaseMedianBytes = allocatedSupported
                    ? median(captureLeaseBytes.clone())
                    : -1L;
            if (allocatedSupported) {
                assertTrue(producerMedianBytes >= 0,
                        "producer history allocation median must be non-negative when supported");
                assertTrue(captureLeaseMedianBytes >= 0,
                        "capture-lease allocation median must be non-negative when supported");
            }

            int historyCapacityFrames = producerRuns[0].historyCapacityFrames();
            long structuralPcmBytes = producerRuns[0].structuralPcmBytes();
            for (AllocationRun run : producerRuns) {
                assertEquals(historyCapacityFrames, run.historyCapacityFrames(),
                        "fresh producers must use the same effective history capacity");
                assertEquals(structuralPcmBytes, run.structuralPcmBytes(),
                        "fresh producers must allocate the same structural PCM storage");
            }
            for (AllocationRun run : captureRuns) {
                assertEquals(historyCapacityFrames, run.historyCapacityFrames(),
                        "offline capture must reuse the producer history capacity");
                assertEquals(structuralPcmBytes, run.structuralPcmBytes(),
                        "offline capture must reuse exactly one PCM history ring");
            }

            System.out.printf(Locale.ROOT,
                    "AUDIO_HISTORY_ALLOCATION producerMedianBytes=%d captureLeaseMedianBytes=%d "
                            + "allocatedSupported=%s iterations=%d producerBytes=%s captureLeaseBytes=%s "
                            + "historyLimitType=%s historySeconds=%d historySizeMb=%d sampleRate=%d "
                            + "historyCapacityFrames=%d structuralPcmBytes=%d%n",
                    producerMedianBytes, captureLeaseMedianBytes, allocatedSupported,
                    MEASUREMENT_ITERATIONS, formatList(producerBytes), formatList(captureLeaseBytes),
                    config.getString(SonicConfiguration.REWIND_AUDIO_HISTORY_LIMIT_TYPE),
                    config.getInt(SonicConfiguration.REWIND_AUDIO_HISTORY_SECONDS),
                    config.getInt(SonicConfiguration.REWIND_AUDIO_HISTORY_SIZE_MB),
                    SAMPLE_RATE, historyCapacityFrames, structuralPcmBytes);
        } finally {
            try {
                cleanupAudio(audio);
            } finally {
                config.resetToDefaults();
            }
        }
    }

    /**
     * The producer owns the single PCM history ring and realizes it lazily on
     * first presentation use. Measure that realization.
     */
    private static AllocationRun measureProducerHistoryAllocation(
            SonicConfigurationService config,
            AudioManager audio,
            AudioBenchmarkMemoryProbe probe) throws Exception {
        try {
            audio.setBackend(new HeadlessSmpsAudioBackend(
                    config, PerformanceProfiler.getInstance()));

            AudioBenchmarkMemoryProbe.RunResult measurement =
                    probe.measureTimedRun(() -> audio.setRewindHistoryArmed(true));
            assertTrue(audio.releaseStateForTesting().producer().historyArmed());
            PcmHistoryRing history = producerHistory(audio);
            assertNotNull(history,
                    "the presentation producer must own a PCM history ring");
            return allocationRun(measurement, history);
        } finally {
            cleanupAudio(audio);
        }
    }

    private static AllocationRun measureCaptureLeaseAllocation(
            SonicConfigurationService config,
            AudioManager audio,
            AudioBenchmarkMemoryProbe probe) throws Exception {
        try {
            audio.setBackend(new HeadlessSmpsAudioBackend(
                    config, PerformanceProfiler.getInstance()));
            audio.setRewindHistoryArmed(true);
            assertTrue(audio.releaseStateForTesting().producer().historyArmed());
            PcmHistoryRing before = producerHistory(audio);

            AudioBenchmarkMemoryProbe.RunResult measurement =
                    probe.measureTimedRun(() -> audio.beginCaptureMode(SAMPLE_RATE, FRAME_RATE));
            PcmHistoryRing captureHistory = producerHistory(audio);
            assertNotNull(captureHistory,
                    "the presentation producer must own a PCM history ring");
            assertEquals(before, captureHistory,
                    "attaching an offline lease must not allocate a second ring");
            return allocationRun(measurement, captureHistory);
        } finally {
            cleanupAudio(audio);
        }
    }

    private static AllocationRun allocationRun(
            AudioBenchmarkMemoryProbe.RunResult measurement,
            PcmHistoryRing history) throws Exception {
        return new AllocationRun(
                measurement.allocatedBytes(),
                measurement.allocatedBytesSupported(),
                capacityFrames(history),
                structuralPcmBytes(history));
    }

    private static void cleanupAudio(AudioManager audio) {
        try {
            audio.setRewindHistoryArmed(false);
        } finally {
            try {
                audio.endCaptureMode();
            } finally {
                try {
                    audio.setBackend(new NullAudioBackend());
                } finally {
                    audio.resetState();
                }
            }
        }
    }

    /**
     * Offline capture attaches one non-consuming lease to the authoritative
     * presentation producer, which already owns the single PCM history ring.
     * Measure that ring.
     */
    private static PcmHistoryRing producerHistory(AudioManager audio) throws Exception {
        Field producerField = AudioManager.class.getDeclaredField("shadowProducer");
        producerField.setAccessible(true);
        Object producer = producerField.get(audio);
        assertNotNull(producer, "the presentation producer must own capture history");

        Field historyField = producer.getClass().getDeclaredField("history");
        historyField.setAccessible(true);
        return (PcmHistoryRing) historyField.get(producer);
    }

    private static int capacityFrames(PcmHistoryRing history) throws Exception {
        Field field = PcmHistoryRing.class.getDeclaredField("capacityFrames");
        field.setAccessible(true);
        return field.getInt(history);
    }

    private static long structuralPcmBytes(PcmHistoryRing history) throws Exception {
        Field field = PcmHistoryRing.class.getDeclaredField("samples");
        field.setAccessible(true);
        return (long) ((short[]) field.get(history)).length * Short.BYTES;
    }

    private static long[] allocatedBytes(AllocationRun[] runs) {
        long[] values = new long[runs.length];
        for (int i = 0; i < runs.length; i++) {
            values[i] = runs[i].allocatedBytes();
        }
        return values;
    }

    private static boolean allocationSupported(AllocationRun[] runs) {
        for (AllocationRun run : runs) {
            if (!run.allocatedBytesSupported()) {
                return false;
            }
        }
        return true;
    }

    private static long median(long[] values) {
        Arrays.sort(values);
        int mid = values.length / 2;
        return values.length % 2 == 1
                ? values[mid]
                : Math.round((values[mid - 1] + values[mid]) / 2.0);
    }

    private static String formatList(long[] values) {
        StringBuilder result = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                result.append(',');
            }
            result.append(values[i]);
        }
        return result.append(']').toString();
    }

    private record AllocationRun(
            long allocatedBytes,
            boolean allocatedBytesSupported,
            int historyCapacityFrames,
            long structuralPcmBytes) {
    }
}
