package com.openggf.tests.trace;

import com.openggf.trace.TraceData;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestTraceDataAuxSchemaPerformance {

    private static final Path LARGEST_AUX_FIXTURE =
            Path.of("src/test/resources/traces/s3k/soz_completerun");
    private static final int AUX_EVENT_COUNT = 1_569_911;
    private static final int WARMUP_BATCHES = 2;
    private static final int MEASURED_BATCHES = 7;
    private static final int QUERIES_PER_BATCH = 4;
    private static volatile int resultSink;

    @Test
    void measuresPostLoadMissingSchemaQueriesOnLargestFixture() throws IOException {
        assumeTrue(Boolean.getBoolean("openggf.trace.auxSchemaBenchmark"),
                "opt in with -Dopenggf.trace.auxSchemaBenchmark=true");

        forceGc();
        long heapBeforeLoad = usedHeapBytes();
        TraceData data = TraceData.load(LARGEST_AUX_FIXTURE);
        forceGc();
        long retainedHeapDelta = usedHeapBytes() - heapBeforeLoad;

        List<String> expectedMissing = data.missingAdvertisedAuxSchemas();
        for (int batch = 0; batch < WARMUP_BATCHES; batch++) {
            runQueryBatch(data, expectedMissing);
        }

        long[] samples = new long[MEASURED_BATCHES];
        for (int batch = 0; batch < MEASURED_BATCHES; batch++) {
            long start = System.nanoTime();
            runQueryBatch(data, expectedMissing);
            samples[batch] = System.nanoTime() - start;
        }

        System.out.printf(
                "TRACE_AUX_SCHEMA_BENCH fixture=%s aux_events=%d queries_per_batch=%d "
                        + "retained_heap_delta_bytes=%d samples_ns=%s median_ns=%d sink=%d%n",
                LARGEST_AUX_FIXTURE,
                AUX_EVENT_COUNT,
                QUERIES_PER_BATCH,
                retainedHeapDelta,
                Arrays.toString(samples),
                median(samples),
                resultSink);
    }

    private static void runQueryBatch(TraceData data, List<String> expectedMissing) {
        int hash = 1;
        for (int query = 0; query < QUERIES_PER_BATCH; query++) {
            List<String> actual = data.missingAdvertisedAuxSchemas();
            assertEquals(expectedMissing, actual);
            hash = 31 * hash + actual.hashCode();
        }
        resultSink = hash;
    }

    private static long median(long[] samples) {
        long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static long usedHeapBytes() {
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    private static void forceGc() {
        System.gc();
        System.runFinalization();
        System.gc();
    }
}
