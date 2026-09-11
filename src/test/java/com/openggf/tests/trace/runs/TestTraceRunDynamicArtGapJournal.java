package com.openggf.tests.trace.runs;

import com.openggf.game.resources.DynamicArtGapTransition;
import com.openggf.game.resources.DynamicArtLifecycleService;
import com.openggf.trace.DynamicArtTransfer;
import com.openggf.trace.TraceRunManifest;
import com.openggf.trace.replay.runs.TraceRunDynamicArtGapJournal;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestTraceRunDynamicArtGapJournal {

    @Test
    void requiresObservedCloseBeforeGapAndComparesObservedDestinationOpen() {
        DynamicArtLifecycleService lifecycle = new DynamicArtLifecycleService();
        lifecycle.beginRun();
        lifecycle.openComparisonSegment();
        TraceRunDynamicArtGapJournal journal =
                new TraceRunDynamicArtGapJournal(manifest(), lifecycle);

        assertThrows(IllegalStateException.class, () -> journal.gapOpened(0));
        lifecycle.closeComparisonSegment();
        journal.sourceClosed(0);
        journal.gapOpened(0);
        lifecycle.openComparisonSegment();

        assertFalse(journal.destinationOpened(1, 0).hasError());
    }

    @Test
    void terminalTailClosesWithoutFabricatingADestinationSegment() {
        DynamicArtLifecycleService lifecycle = new DynamicArtLifecycleService();
        lifecycle.beginRun();
        lifecycle.openComparisonSegment();
        TraceRunDynamicArtGapJournal journal =
                new TraceRunDynamicArtGapJournal(terminalManifest(), lifecycle);

        lifecycle.closeComparisonSegment();
        journal.sourceClosed(0);
        journal.gapOpened(0);

        var comparison = journal.terminalTailClosed(120);

        assertFalse(comparison.hasError(), comparison.divergentFields()::toString);
        assertTrue(comparison.fields().containsKey("run_tail.edge_count"));
        assertFalse(comparison.fields().keySet().stream()
                .anyMatch(field -> field.contains("destination")),
                "terminal tails have no synthetic destination identity or ledger hash");
    }

    /**
     * A stale gap stamp is re-rowed by counting the unannounced rows that
     * passed between it and the admission, off the stamp itself.
     *
     * <p>The stamp a frozen cursor re-announces is the destination's first
     * row, and the admission stands on that row whether or not it has run yet,
     * so both title-card release phases count back from the same base. The
     * recovery used to take that base from
     * {@code bk2FrameOffset + rowsConsumed - 1} instead, which equals the
     * admission row only while every return consumes the destination's row
     * zero; a return that consumes none put every recovered row in its gap one
     * row early. Measured on the halfpipe round-trip's {@code ss -> seg2_ehz1}
     * gap, where both phases report the identical unannounced counts
     * (admission 5841, emit 5815) and only that derived base moved.
     */
    @Test
    void staleGapStampsRecoverTheSameRowOnEitherSideOfTheArmFrame() {
        int frozenRow = 9701;

        List<DynamicArtGapTransition> rowed =
                TraceRunDynamicArtGapJournal.rowsCountedBackFromAdmission(
                        List.of(staleEdge(frozenRow, 5815)), frozenRow, 5841);

        assertEquals(frozenRow - 26, rowed.get(0).edge().movieLogicalFrame(),
                "a stale stamp counts back the unannounced rows that passed "
                        + "between its emit and the admission");
    }

    /**
     * The unannounced-row count is the only thing the recovery reads, and it is
     * load-bearing in both directions: a one-row error either way moves every
     * recovered gap edge by that much, which reads as a physics divergence
     * rather than as a stamping fault.
     */
    @Test
    void aShiftedUnannouncedCountShiftsEveryRecoveredRowByTheSameAmount() {
        int frozenRow = 9701;
        for (int shift : new int[] {-1, 1}) {
            List<DynamicArtGapTransition> shifted =
                    TraceRunDynamicArtGapJournal.rowsCountedBackFromAdmission(
                            List.of(staleEdge(frozenRow, 5839)),
                            frozenRow, 5840 + shift);
            assertEquals(frozenRow - 1 - shift,
                    shifted.get(0).edge().movieLogicalFrame(),
                    "recovered rows must track the unannounced count exactly, "
                            + "shift " + shift);
        }
    }

    /**
     * A live stamp — one that is not the admission's own row — was taken while
     * the cursor was still announcing, so it is already the row it happened on
     * and has nothing to count back.
     */
    @Test
    void aLiveGapStampKeepsItsOwnRow() {
        List<DynamicArtGapTransition> rowed =
                TraceRunDynamicArtGapJournal.rowsCountedBackFromAdmission(
                        List.of(staleEdge(9600, 5000)), 9701, 5840);

        assertEquals(9600, rowed.get(0).edge().movieLogicalFrame());
    }

    private static DynamicArtGapTransition staleEdge(
            int movieLogicalFrame, int unannouncedRowsAtEmit) {
        return new DynamicArtGapTransition(
                new DynamicArtGapTransition.GapEdge(
                        1L, 2L, "submitted", "sonic", 0, movieLogicalFrame, 0,
                        unannouncedRowsAtEmit, List.of()),
                List.of(), List.of());
    }

    private static TraceRunManifest manifest() {
        TraceRunManifest.Segment source = new TraceRunManifest.Segment(
                "source", "level", "gameplay_unlock", 100, 10,
                0, 1, null, null, List.of(), null);
        TraceRunManifest.Segment destination = new TraceRunManifest.Segment(
                "destination", "level", "gameplay_unlock", 120, 10,
                0, 1, null, null, List.of(),
                DynamicArtTransfer.ledgerHash(List.of()));
        return new TraceRunManifest(
                "s2", "run", "movie.bk2", "crc",
                List.of(source, destination), List.of(), List.of(),
                TraceRunManifest.ExpectedMovieEndMode.UNSPECIFIED);
    }

    private static TraceRunManifest terminalManifest() {
        TraceRunManifest.Segment source = new TraceRunManifest.Segment(
                "source", "level", "gameplay_unlock", 100, 10,
                0, 1, null, null, List.of(), null);
        return new TraceRunManifest(
                "s1", "terminal", "movie.bk2", "crc",
                List.of(source), List.of(), List.of(),
                TraceRunManifest.ExpectedMovieEndMode.LEVEL);
    }
}
