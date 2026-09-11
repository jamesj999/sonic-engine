package com.openggf.trace.live;

import com.openggf.game.GameId;
import com.openggf.game.resources.DynamicArtDiagnosticsSnapshot;
import com.openggf.game.resources.DynamicArtGapTransition;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.TileLoadRequest;
import com.openggf.trace.FrameComparison;
import com.openggf.trace.ToleranceConfig;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceEvent;
import com.openggf.trace.TraceFixtures;
import com.openggf.trace.TraceFrame;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Defends the property the opening-row adoption exists for: the destination row
 * that already ran in the transition gap is republished as the segment's row
 * zero AND is genuinely compared against the fixture's row zero — on every path
 * that adopts it.
 */
class TestAdoptedOpeningRowComparison {

    /** Trace whose row zero advertises no dynamic-art edges at all. */
    private static TraceData emptyRowZeroTrace() {
        return TraceFixtures.trace(
                TraceFixtures.metadataWithDynamicArt("s2", 0, 0, 1),
                List.of(TraceFrame.executionTestFrame(0, 10, 0x100, 0)),
                Map.of(0, List.of(new TraceEvent.DynamicArtTransferState(
                        0, List.of(), List.of()))));
    }

    /**
     * Runs two gap iterations, the second of which is the destination's row
     * zero, and returns the lifecycle parked at the moment of admission.
     */
    private static DynamicArtLifecycleService gapWithTrailingDestinationRow() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();
        // Iteration one: gap work that stays gap work.
        service.observePlayerDplc(GameId.S2, "tails", 16,
                new SpriteDplcFrame(List.of(new TileLoadRequest(1, 1))));
        service.serviceProductionVBlank();
        service.finishProductionIteration(false);
        // Iteration two: the destination segment's row zero, run before the
        // destination's readiness was observable.
        service.observePlayerDplc(GameId.S2, "sonic", 15,
                new SpriteDplcFrame(List.of(new TileLoadRequest(2, 1))));
        service.serviceProductionVBlank();
        service.finishProductionIteration(false);
        return service;
    }

    @Test
    void adoptionMovesOnlyTheLastGapIterationIntoSegmentRowZero() {
        DynamicArtLifecycleService service = gapWithTrailingDestinationRow();
        int gapEdgesBefore = service.gapEdges().size();

        service.openComparisonSegment();
        service.adoptGapResidentOpeningRow();

        List<DynamicArtGapTransition.GapEdge> remaining = service.gapEdges();
        assertEquals(2, remaining.size(),
                "only the last iteration's edges may leave the gap ledger");
        assertEquals(gapEdgesBefore - 2, remaining.size());
        assertTrue(remaining.stream().allMatch(
                edge -> "tails".equals(edge.owner())),
                "the earlier gap iteration must stay gap-resident");

        DynamicArtDiagnosticsSnapshot published = service.latestSnapshot();
        assertTrue(published.published());
        assertEquals(0, published.frame());
        assertEquals(2, published.edges().size(),
                "row zero carries the adopted iteration's whole edge batch");
        assertTrue(published.edges().stream().allMatch(
                edge -> "segment".equals(edge.submissionOrigin())),
                "a transfer submitted in the adopted iteration is segment work");
        assertTrue(published.edges().stream().allMatch(
                edge -> "sonic".equals(edge.owner())));
    }

    @Test
    void adoptedRowZeroIsComparedThroughTheOrdinaryCountersAndObserver() {
        DynamicArtLifecycleService service = gapWithTrailingDestinationRow();
        service.openComparisonSegment();
        service.adoptGapResidentOpeningRow();

        List<FrameComparison> observed = new ArrayList<>();
        LiveTraceComparator comparator = new LiveTraceComparator(
                emptyRowZeroTrace(), ToleranceConfig.DEFAULT, 1,
                () -> null, null, observed::add);

        comparator.compareAdoptedOpeningRow(0, service.latestSnapshot());

        // The fixture's row zero advertises no edges and the adopted row
        // carries two, so a comparison that actually happens MUST diverge.
        // If adoption stops being compared -- the call removed, stubbed, or
        // routed past the counters -- these all fall to zero.
        assertEquals(1, observed.size(),
                "the adopted row must reach the per-frame observer");
        assertTrue(comparator.errorCount() > 0,
                "the adopted row must be compared, not merely published");
        assertTrue(comparator.hasRecordingDesync());
        assertFalse(comparator.recentMismatches().isEmpty());
        assertTrue(comparator.recentMismatches().stream().anyMatch(
                mismatch -> mismatch.field().startsWith("dynamic_art")),
                "the divergence must be reported on the dynamic-art axis");
    }

    @Test
    void everyAdoptingCallSiteAlsoComparesTheAdoptedRow() throws IOException {
        List<Path> sources = List.of(
                Path.of("src", "main", "java", "com", "openggf",
                        "TraceSessionLauncher.java"),
                Path.of("src", "test", "java", "com", "openggf", "tests",
                        "trace", "runs", "AbstractRunChainTest.java"));
        Pattern adopt = Pattern.compile("adoptGapResidentOpeningRow\\s*\\(");
        List<String> violations = new ArrayList<>();
        for (Path source : sources) {
            String text = Files.readString(source);
            Matcher matcher = adopt.matcher(text);
            int adoptions = 0;
            while (matcher.find()) {
                adoptions++;
            }
            if (adoptions == 0) {
                violations.add(source + " no longer adopts an opening row");
                continue;
            }
            int comparisons = 0;
            Matcher compare = Pattern.compile(
                    "compareAdoptedOpeningRow\\s*\\(").matcher(text);
            while (compare.find()) {
                comparisons++;
            }
            if (comparisons < adoptions) {
                violations.add(source + " adopts an opening row "
                        + adoptions + " time(s) but compares it only "
                        + comparisons + " time(s)");
            }
        }
        if (!violations.isEmpty()) {
            fail("An adopted opening row must be compared on every path:\n"
                    + String.join("\n", violations));
        }
    }
}
