package com.openggf.game;

import com.openggf.game.animation.AnimatedTileCachePolicy;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.animation.ApplyStrategy;
import com.openggf.game.animation.ChannelContext;
import com.openggf.game.animation.DestinationPlan;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TestAnimatedTileChannelGraph {

    @Test
    void installRejectsDuplicateChannelIds() {
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        AnimatedTileChannel first = new AnimatedTileChannel(
                "duplicate",
                () -> true,
                ctx -> 1,
                DestinationPlan.single(0x120),
                AnimatedTileCachePolicy.ALWAYS,
                ctx -> { });
        AnimatedTileChannel second = new AnimatedTileChannel(
                "duplicate",
                () -> true,
                ctx -> 2,
                DestinationPlan.single(0x121),
                AnimatedTileCachePolicy.ALWAYS,
                ctx -> { });

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> graph.install(List.of(first, second)));
        assertTrue(ex.getMessage().contains("duplicate"));
    }

    @Test
    void failedInstallIsAtomicAndPreservesChannelsAndRecordedPhases() {
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        AnimatedTileChannel original = channel("original", 9, AnimatedTileCachePolicy.ALWAYS, ctx -> { });
        graph.install(List.of(original));
        graph.update(baseContext());

        assertThrows(NullPointerException.class,
                () -> graph.install(Arrays.asList(channel("replacement", 1,
                        AnimatedTileCachePolicy.ALWAYS, ctx -> { }), null)));
        assertSame(original, graph.channels().getFirst());
        assertEquals(9, graph.capture().lastPhaseByChannel().get("original"));

        AnimatedTileChannel duplicate = channel("duplicate", 1, AnimatedTileCachePolicy.ALWAYS, ctx -> { });
        assertThrows(IllegalArgumentException.class,
                () -> graph.install(List.of(duplicate, duplicate)));
        assertSame(original, graph.channels().getFirst());
        assertEquals(9, graph.capture().lastPhaseByChannel().get("original"));
    }

    @Test
    void phaseSourceAndApplyStrategySeeTheSamePerChannelContextShape() {
        AtomicReference<ChannelContext> phaseContext = new AtomicReference<>();
        AtomicReference<ChannelContext> applyContext = new AtomicReference<>();
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        AnimatedTileChannel channel = new AnimatedTileChannel(
                "shape",
                () -> true,
                ctx -> {
                    phaseContext.set(ctx);
                    return 7;
                },
                DestinationPlan.single(0x120),
                AnimatedTileCachePolicy.ALWAYS,
                ctx -> applyContext.set(ctx));

        graph.install(List.of(channel));
        graph.update(new ChannelContext(null, null, null, null, 3, 4, 5));

        assertNotNull(phaseContext.get(), "phase context");
        assertNotNull(applyContext.get(), "apply context");
        assertNotNull(phaseContext.get().graph(), "phase graph");
        assertNotNull(phaseContext.get().channel(), "phase channel");
        assertSame(applyContext.get().graph(), phaseContext.get().graph(), "graph");
        assertSame(applyContext.get().channel(), phaseContext.get().channel(), "channel");
        assertSame(channel, phaseContext.get().channel(), "expected per-channel context");
    }

    @Test
    void onPhaseChangeChannelSkipsWhenPhaseIsStable() {
        RecordingStrategy strategy = new RecordingStrategy();
        AnimatedTileChannel channel = new AnimatedTileChannel(
                "stable",
                () -> true,
                ctx -> 7,
                DestinationPlan.single(0x120),
                AnimatedTileCachePolicy.ON_PHASE_CHANGE,
                strategy);

        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        graph.install(List.of(channel));

        ChannelContext ctx = new ChannelContext(null, null, null, null, 0, 0, 0);
        graph.update(ctx);
        graph.update(ctx);

        assertEquals(1, strategy.invocationCount());
    }

    @Test
    void onPhaseChangeDistinguishesAbsenceFromEveryIntValue() {
        for (int phase : new int[]{-1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            RecordingStrategy strategy = new RecordingStrategy();
            AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
            graph.install(List.of(channel("extreme", phase,
                    AnimatedTileCachePolicy.ON_PHASE_CHANGE, strategy)));

            graph.update(baseContext());
            graph.update(baseContext());

            assertEquals(1, strategy.invocationCount(), "phase=" + phase);
            assertEquals(phase, graph.capture().lastPhaseByChannel().get("extreme"));
        }
    }

    @Test
    void alwaysPolicyAppliesAnEqualPhaseEveryTime() {
        RecordingStrategy strategy = new RecordingStrategy();
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        graph.install(List.of(channel("always", 211, AnimatedTileCachePolicy.ALWAYS, strategy)));

        graph.update(baseContext());
        graph.update(baseContext());

        assertEquals(2, strategy.invocationCount());
    }

    @Test
    void guardedOutChannelDoesNotResolveOrChangeRecordedPhase() {
        AtomicInteger resolutions = new AtomicInteger();
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        graph.install(List.of(new AnimatedTileChannel(
                "guarded", () -> false, ctx -> resolutions.incrementAndGet(),
                DestinationPlan.single(0), AnimatedTileCachePolicy.ALWAYS, ctx -> fail("must not apply"))));

        graph.update(baseContext());

        assertEquals(0, resolutions.get());
        assertFalse(graph.capture().lastPhaseByChannel().containsKey("guarded"));
    }

    @Test
    void phaseIsRecordedBeforeApplyAndRemainsRecordedWhenApplyFails() {
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        AtomicInteger applies = new AtomicInteger();
        graph.install(List.of(channel("failing", 313, AnimatedTileCachePolicy.ON_PHASE_CHANGE, ctx -> {
            applies.incrementAndGet();
            assertEquals(313, graph.capture().lastPhaseByChannel().get("failing"));
            throw new IllegalStateException("apply failed");
        })));

        assertThrows(IllegalStateException.class, () -> graph.update(baseContext()));
        assertEquals(313, graph.capture().lastPhaseByChannel().get("failing"));
        graph.update(baseContext());
        assertEquals(1, applies.get(), "failed apply still commits the phase before the callback");
    }

    @Test
    void channelsRunInRegistrationOrder() {
        StringBuilder log = new StringBuilder();
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        graph.install(List.of(
                new AnimatedTileChannel(
                        "first",
                        () -> true,
                        c -> 1,
                        DestinationPlan.single(0x100),
                        AnimatedTileCachePolicy.ALWAYS,
                        c -> log.append("A")),
                new AnimatedTileChannel(
                        "second",
                        () -> true,
                        c -> 1,
                        DestinationPlan.single(0x101),
                        AnimatedTileCachePolicy.ALWAYS,
                        c -> log.append("B"))));

        graph.update(new ChannelContext(null, null, null, null, 0, 0, 0));
        assertEquals("AB", log.toString());
    }

    @Test
    void callbackLayoutMutationsFinishOnlyTheEntryTimeChannelsWithLegacyPhaseResults() {
        for (MutationPoint point : MutationPoint.values()) {
            assertCallbackLayoutMutation(point, false);
            assertCallbackLayoutMutation(point, true);
        }
    }

    @Test
    void phaseSourceFailureLeavesPreviouslyRecordedPhaseUnchanged() {
        AtomicInteger resolutions = new AtomicInteger();
        RecordingStrategy strategy = new RecordingStrategy();
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        graph.install(List.of(new AnimatedTileChannel(
                "phase-failure", () -> true, ctx -> {
                    if (resolutions.getAndIncrement() == 0) {
                        return 77;
                    }
                    throw new IllegalStateException("phase failed");
                }, DestinationPlan.single(0), AnimatedTileCachePolicy.ALWAYS, strategy)));
        graph.update(baseContext());

        assertThrows(IllegalStateException.class, () -> graph.update(baseContext()));

        assertEquals(Map.of("phase-failure", 77), graph.capture().lastPhaseByChannel());
        assertEquals(1, strategy.invocationCount());
    }

    private static void assertCallbackLayoutMutation(MutationPoint point, boolean install) {
        String scenario = point + (install ? " install" : " clear");
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        StringBuilder applications = new StringBuilder();
        AnimatedTileChannel replacementB = channel("B", 200, AnimatedTileCachePolicy.ALWAYS,
                ctx -> applications.append('b'));
        Runnable mutate = install
                ? () -> graph.install(List.of(
                        channel("N", 100, AnimatedTileCachePolicy.ALWAYS,
                                ctx -> applications.append('N')),
                        replacementB))
                : graph::clear;
        AnimatedTileChannel first = new AnimatedTileChannel(
                "A",
                () -> {
                    if (point == MutationPoint.GUARD) {
                        mutate.run();
                    }
                    return true;
                },
                ctx -> {
                    if (point == MutationPoint.PHASE) {
                        mutate.run();
                    }
                    return 10;
                },
                DestinationPlan.single(0),
                AnimatedTileCachePolicy.ALWAYS,
                ctx -> {
                    applications.append('A');
                    if (point == MutationPoint.APPLY) {
                        mutate.run();
                    }
                });
        AnimatedTileChannel second = channel("B", 20, AnimatedTileCachePolicy.ALWAYS,
                ctx -> applications.append('B'));
        graph.install(List.of(first, second));

        graph.update(baseContext());

        assertEquals("AB", applications.toString(), scenario + " must finish the entry-time list only");
        assertEquals(install ? List.of("N", "B") : List.of(), graph.channels().stream()
                .map(AnimatedTileChannel::channelId).toList(), scenario + " installed channels");
        Map<String, Integer> expectedPhases = point == MutationPoint.APPLY
                ? Map.of("B", 20)
                : Map.of("A", 10, "B", 20);
        assertEquals(expectedPhases, graph.capture().lastPhaseByChannel(),
                scenario + " must match the legacy keyed phase store");
    }

    private enum MutationPoint {
        GUARD,
        PHASE,
        APPLY
    }

    private static AnimatedTileChannel channel(String id, int phase,
                                               AnimatedTileCachePolicy policy,
                                               ApplyStrategy strategy) {
        return new AnimatedTileChannel(id, () -> true, ctx -> phase,
                DestinationPlan.single(0), policy, strategy);
    }

    private static ChannelContext baseContext() {
        return new ChannelContext(null, null, null, null, 0, 0, 0);
    }

    private static final class RecordingStrategy implements ApplyStrategy {
        private final AtomicInteger invocations = new AtomicInteger();

        @Override
        public void apply(ChannelContext context) {
            invocations.incrementAndGet();
        }

        int invocationCount() {
            return invocations.get();
        }
    }
}
