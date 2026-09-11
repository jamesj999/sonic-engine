package com.openggf.game.animation;

import com.sun.management.ThreadMXBean;
import com.openggf.game.rewind.snapshot.AnimatedTileChannelSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAnimatedTileChannelGraphAllocation {

    private static final int CHANNEL_COUNT = 32;
    private static final int WARM_UPDATES = 20_000;
    private static final int MEASURED_UPDATES = 20_000;
    private static final int LEGACY_PATH = 0;
    private static final int OPTIMIZED_PATH = 1;
    private static int measurementPath;
    private static long legacyCallbackCount;
    private static long optimizedCallbackCount;
    private static volatile ChannelContext legacyEscapedContext;
    private static volatile ChannelContext optimizedEscapedContext;
    private static volatile AnimatedTileChannelSnapshot escapedSnapshot;

    @Test
    void primitiveInstalledPhaseStoreRemovesHotLoopIntegerBoxing() {
        ThreadMXBean bean = allocationBeanOrSkip();
        List<AnimatedTileChannel> channels = channels();
        AnimatedTileChannelGraph optimized = new AnimatedTileChannelGraph();
        optimized.install(channels);
        LegacyGraph legacy = new LegacyGraph(channels, optimized);
        ChannelContext base = new ChannelContext(null, null, null, null, 2, 1, 211);

        for (int i = 0; i < WARM_UPDATES; i++) {
            measurementPath = LEGACY_PATH;
            legacy.update(base);
            measurementPath = OPTIMIZED_PATH;
            optimized.update(base);
        }

        long[] legacyBytesPerUpdate = new long[5];
        long[] optimizedBytesPerUpdate = new long[5];
        for (int repetition = 0; repetition < legacyBytesPerUpdate.length; repetition++) {
            if ((repetition & 1) == 0) {
                legacyBytesPerUpdate[repetition] = measureLegacy(bean, legacy, base, repetition);
                optimizedBytesPerUpdate[repetition] = measureOptimized(bean, optimized, base, repetition);
            } else {
                optimizedBytesPerUpdate[repetition] = measureOptimized(bean, optimized, base, repetition);
                legacyBytesPerUpdate[repetition] = measureLegacy(bean, legacy, base, repetition);
            }
        }

        long legacyMedian = median(legacyBytesPerUpdate);
        long optimizedMedian = median(optimizedBytesPerUpdate);
        System.out.printf("animated-channel allocation bytes/update legacy=%s optimized=%s medians=%d/%d%n",
                Arrays.toString(legacyBytesPerUpdate), Arrays.toString(optimizedBytesPerUpdate),
                legacyMedian, optimizedMedian);
        assertTrue(optimizedMedian + 256 <= legacyMedian,
                () -> "primitive live phases should remove material per-channel boxing: legacy="
                        + legacyMedian + " optimized=" + optimizedMedian);
        assertTrue(optimizedMedian * 10 <= legacyMedian * 9,
                () -> "optimized allocation should be at least 10% lower: legacy="
                        + legacyMedian + " optimized=" + optimizedMedian);
        assertTrue(legacyEscapedContext != null, "legacy derived contexts must escape through the callback");
        assertTrue(optimizedEscapedContext != null, "optimized derived contexts must escape through the callback");
    }

    @Test
    void compactCaptureRemovesMapNodesAndBoxedPhaseValues() {
        ThreadMXBean bean = allocationBeanOrSkip();
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        graph.install(channels());
        for (int index = 0; index < CHANNEL_COUNT; index++) {
            graph.recordPhase("allocation." + index, 211 + index);
        }
        LinkedHashMap<String, Integer> expectedEntries = new LinkedHashMap<>();
        for (int index = 0; index < CHANNEL_COUNT; index++) {
            expectedEntries.put("allocation." + index, 211 + index);
        }
        Map<String, Integer> expected = Map.copyOf(expectedEntries);

        for (int i = 0; i < 10_000; i++) {
            escapedSnapshot = legacyCapture(graph);
            escapedSnapshot = graph.capture();
        }

        long[] legacyBytesPerCapture = new long[5];
        long[] compactBytesPerCapture = new long[5];
        long expectedWork = (long) CHANNEL_COUNT * MEASURED_UPDATES;
        for (int repetition = 0; repetition < legacyBytesPerCapture.length; repetition++) {
            CaptureRun legacy;
            CaptureRun compact;
            if ((repetition & 1) == 0) {
                legacy = measureCapture(bean, () -> legacyCapture(graph));
                validateCaptureRun(legacy, expected, expectedWork,
                        "legacy repetition " + repetition);
                compact = measureCapture(bean, graph::capture);
                validateCaptureRun(compact, expected, expectedWork,
                        "compact repetition " + repetition);
            } else {
                compact = measureCapture(bean, graph::capture);
                validateCaptureRun(compact, expected, expectedWork,
                        "compact repetition " + repetition);
                legacy = measureCapture(bean, () -> legacyCapture(graph));
                validateCaptureRun(legacy, expected, expectedWork,
                        "legacy repetition " + repetition);
            }
            legacyBytesPerCapture[repetition] = legacy.bytesPerCapture;
            compactBytesPerCapture[repetition] = compact.bytesPerCapture;
        }

        long legacyMedian = median(legacyBytesPerCapture);
        long compactMedian = median(compactBytesPerCapture);
        System.out.printf("animated-channel capture bytes/capture legacy=%s compact=%s medians=%d/%d%n",
                Arrays.toString(legacyBytesPerCapture), Arrays.toString(compactBytesPerCapture),
                legacyMedian, compactMedian);
        assertTrue(compactMedian + 1_000 <= legacyMedian,
                () -> "compact capture should remove map nodes and boxed values: legacy="
                        + legacyMedian + " compact=" + compactMedian);
        assertTrue(compactMedian * 2 < legacyMedian,
                () -> "compact capture should allocate less than half the legacy path: legacy="
                        + legacyMedian + " compact=" + compactMedian);
        assertTrue(escapedSnapshot != null, "each measured snapshot must escape");
    }

    @Test
    void sameLayoutRestoreCopiesPrimitivePayloadWithoutAllocation() {
        ThreadMXBean bean = allocationBeanOrSkip();
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        graph.install(channels());
        for (int index = 0; index < CHANNEL_COUNT; index++) {
            graph.recordPhase("allocation." + index, 211 + index);
        }
        AnimatedTileChannelSnapshot snapshot = graph.capture();
        for (int i = 0; i < WARM_UPDATES; i++) {
            graph.restore(snapshot);
        }

        long[] bytesPerRestore = new long[5];
        for (int repetition = 0; repetition < bytesPerRestore.length; repetition++) {
            bytesPerRestore[repetition] = measure(bean, () -> graph.restore(snapshot));
        }

        System.out.printf("animated-channel same-layout restore bytes/restore=%s%n",
                Arrays.toString(bytesPerRestore));
        assertTrue(median(bytesPerRestore) == 0L,
                () -> "same-layout restore should copy primitive slots without allocation: "
                        + Arrays.toString(bytesPerRestore));
        assertEquals(CHANNEL_COUNT, graph.recordedPhaseCount());
    }

    private static List<AnimatedTileChannel> channels() {
        List<AnimatedTileChannel> channels = new ArrayList<>(CHANNEL_COUNT);
        for (int i = 0; i < CHANNEL_COUNT; i++) {
            channels.add(new AnimatedTileChannel(
                    "allocation." + i,
                    () -> true,
                    ChannelContext::frameCounter,
                    DestinationPlan.single(i),
                    AnimatedTileCachePolicy.ALWAYS,
                    context -> {
                        if (measurementPath == OPTIMIZED_PATH) {
                            optimizedCallbackCount++;
                            optimizedEscapedContext = context;
                        } else {
                            legacyCallbackCount++;
                            legacyEscapedContext = context;
                        }
                    }));
        }
        return List.copyOf(channels);
    }

    private static long measureLegacy(ThreadMXBean bean, LegacyGraph legacy,
                                      ChannelContext base, int repetition) {
        legacyCallbackCount = 0;
        measurementPath = LEGACY_PATH;
        long bytes = measure(bean, () -> legacy.update(base));
        assertTrue(legacyCallbackCount == (long) CHANNEL_COUNT * MEASURED_UPDATES,
                () -> "legacy callback count repetition " + repetition + ": " + legacyCallbackCount);
        return bytes;
    }

    private static long measureOptimized(ThreadMXBean bean, AnimatedTileChannelGraph optimized,
                                         ChannelContext base, int repetition) {
        optimizedCallbackCount = 0;
        measurementPath = OPTIMIZED_PATH;
        long bytes = measure(bean, () -> optimized.update(base));
        assertTrue(optimizedCallbackCount == (long) CHANNEL_COUNT * MEASURED_UPDATES,
                () -> "optimized callback count repetition " + repetition + ": " + optimizedCallbackCount);
        return bytes;
    }

    private static long measure(ThreadMXBean bean, Runnable update) {
        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        assertTrue(before >= 0, "current-thread allocation read must be available before measurement");
        for (int i = 0; i < MEASURED_UPDATES; i++) {
            update.run();
        }
        long after = bean.getThreadAllocatedBytes(threadId);
        assertTrue(after >= 0, "current-thread allocation read must be available after measurement");
        return (after - before) / MEASURED_UPDATES;
    }

    private static CaptureRun measureCapture(ThreadMXBean bean,
                                             Supplier<AnimatedTileChannelSnapshot> capture) {
        long threadId = Thread.currentThread().threadId();
        long before = bean.getThreadAllocatedBytes(threadId);
        assertTrue(before >= 0, "current-thread allocation read must be available before capture");
        long workCount = 0;
        for (int i = 0; i < MEASURED_UPDATES; i++) {
            AnimatedTileChannelSnapshot snapshot = capture.get();
            workCount += snapshot.lastPhaseByChannel().size();
            escapedSnapshot = snapshot;
        }
        long after = bean.getThreadAllocatedBytes(threadId);
        assertTrue(after >= 0, "current-thread allocation read must be available after capture");
        return new CaptureRun((after - before) / MEASURED_UPDATES, workCount, escapedSnapshot);
    }

    private static void validateCaptureRun(CaptureRun run,
                                           Map<String, Integer> expected,
                                           long expectedWork,
                                           String label) {
        assertEquals(expectedWork, run.workCount, label + " must visit every phase");
        assertEquals(expected, run.lastSnapshot.lastPhaseByChannel(),
                label + " escaped snapshot must preserve every key and phase");
    }

    private static AnimatedTileChannelSnapshot legacyCapture(AnimatedTileChannelGraph graph) {
        Map<String, Integer> phases = new LinkedHashMap<>(graph.recordedPhaseCount());
        for (AnimatedTileChannel channel : graph.channels()) {
            phases.put(channel.channelId(), graph.getLastPhase(channel.channelId()));
        }
        return new AnimatedTileChannelSnapshot(phases);
    }

    private static long median(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    private static ThreadMXBean allocationBeanOrSkip() {
        java.lang.management.ThreadMXBean raw = ManagementFactory.getThreadMXBean();
        Assumptions.assumeTrue(raw instanceof ThreadMXBean,
                "ThreadMXBean allocation accounting unavailable");
        ThreadMXBean bean = (ThreadMXBean) raw;
        Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported(),
                "thread allocation accounting unsupported");
        if (!bean.isThreadAllocatedMemoryEnabled()) {
            bean.setThreadAllocatedMemoryEnabled(true);
        }
        Assumptions.assumeTrue(bean.getThreadAllocatedBytes(Thread.currentThread().threadId()) >= 0,
                "current-thread allocation reads unavailable after enabling accounting");
        return bean;
    }

    /** Frozen pre-primitive live-phase algorithm; callback counts prove equivalent measured work. */
    private static final class LegacyGraph {
        private final List<AnimatedTileChannel> channels;
        private final AnimatedTileChannelGraph contextGraph;
        private final Map<String, Integer> lastPhaseByChannel = new HashMap<>();

        private LegacyGraph(List<AnimatedTileChannel> channels,
                            AnimatedTileChannelGraph contextGraph) {
            this.channels = channels;
            this.contextGraph = contextGraph;
        }

        private void update(ChannelContext baseContext) {
            for (AnimatedTileChannel channel : channels) {
                if (!channel.guard().allows()) {
                    continue;
                }
                ChannelContext channelContext = new ChannelContext(
                        contextGraph, channel, baseContext.level(), baseContext.runtimeState(),
                        baseContext.zoneIndex(), baseContext.actIndex(), baseContext.frameCounter());
                int phase = channel.phaseSource().resolve(channelContext);
                Integer previousPhase = lastPhaseByChannel.get(channel.channelId());
                if (channel.cachePolicy() == AnimatedTileCachePolicy.ON_PHASE_CHANGE
                        && previousPhase != null && previousPhase.intValue() == phase) {
                    continue;
                }
                lastPhaseByChannel.put(channel.channelId(), phase);
                channel.applyStrategy().apply(channelContext);
            }
        }
    }

    private record CaptureRun(long bytesPerCapture,
                              long workCount,
                              AnimatedTileChannelSnapshot lastSnapshot) {
    }
}
