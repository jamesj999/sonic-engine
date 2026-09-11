package com.openggf.game.rewind.snapshot;

import com.openggf.game.animation.AnimatedTileCachePolicy;
import com.openggf.game.animation.AnimatedTileChannel;
import com.openggf.game.animation.AnimatedTileChannelGraph;
import com.openggf.game.animation.ChannelContext;
import com.openggf.game.animation.DestinationPlan;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestAnimatedTileChannelCompactSnapshot {

    @Test
    void productionCapturesShareOnlyTheGraphOwnedLayoutAndOwnPrimitivePayloads() {
        AnimatedTileChannelGraph graph = graph("A", "B");
        graph.update(context(211));

        AnimatedTileChannelSnapshot first = graph.capture();
        graph.update(context(212));
        AnimatedTileChannelSnapshot second = graph.capture();

        assertTrue(first.isCompact());
        assertTrue(first.sharesLayoutWith(second));
        assertFalse(first.sharesPayloadWith(second));
        assertEquals(2, first.compactPayloadLength());
        assertEquals(211, first.lastPhaseByChannel().get("A"));
        assertEquals(212, second.lastPhaseByChannel().get("A"));
    }

    @Test
    void diagnosticGrowthReplacesLayoutWithoutChangingOlderSnapshot() {
        AnimatedTileChannelGraph graph = graph("A");
        graph.update(context(211));
        AnimatedTileChannelSnapshot beforeDiagnostic = graph.capture();
        graph.restore(new AnimatedTileChannelSnapshot(java.util.Map.of("A", 211, "unknown", 313)));
        AnimatedTileChannelSnapshot afterDiagnostic = graph.capture();

        assertFalse(beforeDiagnostic.sharesLayoutWith(afterDiagnostic));
        assertEquals(java.util.Map.of("A", 211), beforeDiagnostic.lastPhaseByChannel());
        assertEquals(java.util.Map.of("A", 211, "unknown", 313),
                afterDiagnostic.lastPhaseByChannel());
    }

    @Test
    void sameLayoutRestoreCopiesPresenceAndPhasesWithoutChangingLayout() {
        AnimatedTileChannelGraph graph = graph("A", "B");
        graph.restore(new AnimatedTileChannelSnapshot(java.util.Map.of("A", -1)));
        AnimatedTileChannelSnapshot saved = graph.capture();
        graph.update(context(Integer.MAX_VALUE));

        graph.restore(saved);
        AnimatedTileChannelSnapshot restored = graph.capture();

        assertTrue(saved.sharesLayoutWith(restored));
        assertEquals(java.util.Map.of("A", -1), restored.lastPhaseByChannel());
        assertFalse(restored.lastPhaseByChannel().containsKey("B"));
    }

    @Test
    void compactSnapshotFromOldInstallReconcilesAgainstCurrentInstalledIds() {
        AnimatedTileChannelGraph graph = graph("A", "C");
        graph.update(context(211));
        AnimatedTileChannelSnapshot oldInstall = graph.capture();
        graph.install(java.util.List.of(
                channel("B"), channel("A"), channel("D")));

        graph.restore(oldInstall);

        Map<String, Integer> restoredPhases = graph.capture().lastPhaseByChannel();
        assertEquals(java.util.Map.of("A", 211, "C", 211), restoredPhases);
        assertEquals(java.util.List.of("A", "C"), java.util.List.copyOf(restoredPhases.keySet()),
                "saved unknown keys retain their source-layout order after reconciliation");
        assertEquals(java.util.List.of("B", "A", "D"), graph.channels().stream()
                .map(AnimatedTileChannel::channelId).toList());
    }

    private static AnimatedTileChannelGraph graph(String... ids) {
        AnimatedTileChannelGraph graph = new AnimatedTileChannelGraph();
        graph.install(java.util.Arrays.stream(ids).map(TestAnimatedTileChannelCompactSnapshot::channel)
                .toList());
        return graph;
    }

    private static AnimatedTileChannel channel(String id) {
        return new AnimatedTileChannel(
                id, () -> true, ChannelContext::frameCounter, DestinationPlan.single(0),
                AnimatedTileCachePolicy.ALWAYS, context -> { });
    }

    private static ChannelContext context(int phase) {
        return new ChannelContext(null, null, null, null, 0, 0, phase);
    }
}
