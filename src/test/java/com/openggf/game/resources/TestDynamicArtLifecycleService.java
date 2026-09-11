package com.openggf.game.resources;

import com.openggf.game.GameId;
import com.openggf.level.render.SpriteDplcFrame;
import com.openggf.level.render.TileLoadRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDynamicArtLifecycleService {

    private static final int SONIC_ART = 0x22610;
    private static final int SONIC_VRAM = 0xF000;

    @Test
    void freshPlayablePrimeUpdatesTheDedupeBankWithoutPublishingAnEdge() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        SpriteDplcFrame frame = new SpriteDplcFrame(
                List.of(new TileLoadRequest(0, 3)));

        DynamicArtLifecycleService.ArtUpdate prime =
                service.primePlayerDplc(
                        GameId.S1, "sonic", 1, frame);
        DynamicArtLifecycleService.ArtUpdate repeated =
                service.observePlayerDplc(
                        GameId.S1, "sonic", 1, frame);
        service.finishProductionIteration(false);

        assertTrue(prime.mappingChanged());
        assertEquals(frame.requests(), prime.tileRequests());
        assertFalse(repeated.mappingChanged());
        assertTrue(service.latestSnapshot().edges().isEmpty());
    }

    @Test
    void consumedDestinationRowsAdvanceOnlyTheComparisonCursor() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        int movieFrameBefore = service.gapSnapshot().movieLogicalFrame();

        service.advanceComparisonCursor(1);
        service.finishProductionIteration(false);

        assertEquals(1, service.latestSnapshot().frame());
        assertEquals(movieFrameBefore + 1,
                service.gapSnapshot().movieLogicalFrame(),
                "the consumed row already advanced the run clock in the preceding gap");
    }

    @Test
    void reservedComparisonSegmentOwnsGenerationBeforeActivation() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        service.finishProductionIteration(false);
        service.closeComparisonSegment();
        long automaticGeneration =
                service.latestSnapshot().segmentGeneration();

        service.reserveComparisonSegment();
        DynamicArtDiagnosticsSnapshot reserved = service.latestSnapshot();

        assertTrue(service.isComparisonSegmentReserved());
        assertFalse(service.isComparisonSegmentOpen());
        assertFalse(reserved.published());
        assertEquals(automaticGeneration + 1,
                reserved.segmentGeneration());

        service.activateReservedComparisonSegment();
        service.finishProductionIteration(false);

        assertFalse(service.isComparisonSegmentReserved());
        assertTrue(service.isComparisonSegmentOpen());
        assertEquals(0, service.latestSnapshot().frame());
        assertEquals(reserved.segmentGeneration(),
                service.latestSnapshot().segmentGeneration(),
                "activation and row zero must retain the reserved generation");
    }

    @Test
    void reservedComparisonSegmentRejectsInvalidTransitionsAtomically() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();
        service.reserveComparisonSegment();
        long generation = service.latestSnapshot().segmentGeneration();

        assertThrows(IllegalStateException.class,
                service::reserveComparisonSegment);
        assertEquals(generation,
                service.latestSnapshot().segmentGeneration());

        service.observeRomDplc("sonic", 1,
                List.of(new TileLoadRequest(0, 1)), SONIC_ART, SONIC_VRAM);
        assertThrows(IllegalStateException.class,
                service::activateReservedComparisonSegment);
        assertTrue(service.isComparisonSegmentReserved());
        assertEquals(generation,
                service.latestSnapshot().segmentGeneration());
    }

    @Test
    void cancellingAndRewindingReservedComparisonSegmentNeverPublishesIt() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();
        service.reserveComparisonSegment();
        DynamicArtLifecycleService.RewindState reserved = service.capture();
        long generation = service.latestSnapshot().segmentGeneration();

        service.activateReservedComparisonSegment();
        service.finishProductionIteration(false);
        service.restore(reserved);

        assertTrue(service.isComparisonSegmentReserved());
        assertFalse(service.isComparisonSegmentOpen());
        assertFalse(service.latestSnapshot().published());
        assertEquals(generation,
                service.latestSnapshot().segmentGeneration());

        service.activateReservedComparisonSegment();
        assertEquals(generation,
                service.latestSnapshot().segmentGeneration());
        service.abandonComparisonSegment();

        service.reserveComparisonSegment();
        long cancelledGeneration =
                service.latestSnapshot().segmentGeneration();
        service.cancelReservedComparisonSegment();
        assertFalse(service.isComparisonSegmentReserved());
        assertFalse(service.latestSnapshot().published());
        assertEquals(cancelledGeneration,
                service.latestSnapshot().segmentGeneration());
    }

    @Test
    void duplicateAndEmptyDplcsAdvanceTheOwnerCursorWithoutSubmittingWork() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);

        DynamicArtLifecycleService.ArtUpdate empty =
                service.observeRomDplc("sonic", 2, List.of(), SONIC_ART, SONIC_VRAM);
        DynamicArtLifecycleService.ArtUpdate duplicateEmpty =
                service.observeRomDplc("sonic", 2, List.of(), SONIC_ART, SONIC_VRAM);
        DynamicArtLifecycleService.ArtUpdate loaded =
                service.observeRomDplc("sonic", 3,
                        List.of(new TileLoadRequest(4, 2)), SONIC_ART, SONIC_VRAM);
        DynamicArtLifecycleService.ArtUpdate duplicateLoaded =
                service.observeRomDplc("sonic", 3,
                        List.of(new TileLoadRequest(4, 2)), SONIC_ART, SONIC_VRAM);

        assertTrue(empty.mappingChanged());
        assertFalse(empty.submitted());
        assertFalse(duplicateEmpty.mappingChanged());
        assertTrue(loaded.submitted());
        assertFalse(duplicateLoaded.mappingChanged());
        assertEquals(1, service.publishRow(0, false).edges().size());
    }

    @Test
    void multipleDplcRunsRemainOneOrderedSubmissionBatch() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);

        DynamicArtLifecycleService.ArtUpdate update =
                service.observeRomDplc("tails", 7,
                        List.of(new TileLoadRequest(3, 2),
                                new TileLoadRequest(11, 1)),
                        0x64320, 0xF400);

        DynamicArtDiagnosticsSnapshot snapshot = service.publishRow(0, false);
        assertEquals(1, snapshot.edges().size());
        assertEquals(List.of(
                new DynamicArtDiagnosticsSnapshot.Request(
                        0x64380, 3, -1, 0xF400, 0x40),
                new DynamicArtDiagnosticsSnapshot.Request(
                        0x64480, 11, -1, 0xF440, 0x20)),
                snapshot.edges().getFirst().requests());
        assertEquals(List.of(update.transferId()), snapshot.outstandingTransferIds());
    }

    @Test
    void completionIsPublishedOnlyAfterRuntimeArtApplication() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtLifecycleService.ArtUpdate update =
                service.observeRomDplc("sonic", 4,
                        List.of(new TileLoadRequest(1, 1)), SONIC_ART, SONIC_VRAM);

        DynamicArtDiagnosticsSnapshot submitted = service.publishRow(0, false);
        assertEquals("submitted", submitted.edges().getFirst().phase());
        assertEquals(List.of(update.transferId()), submitted.outstandingTransferIds());

        service.completeApplied(update);
        DynamicArtDiagnosticsSnapshot completed = service.publishRow(1, false);
        assertEquals("completed", completed.edges().getFirst().phase());
        assertTrue(completed.outstandingTransferIds().isEmpty());
    }

    @Test
    void sonicOneCompletionUsesThePhysicalStagingTransfer() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtLifecycleService.ArtUpdate update =
                service.observeRomDplc("sonic", 4,
                        List.of(new TileLoadRequest(1, 1)), SONIC_ART, SONIC_VRAM);

        service.completeApplied(update,
                List.of(DynamicArtDiagnosticsSnapshot.Request.ram(
                        0xC800, SONIC_VRAM, 0x2E0)));

        DynamicArtDiagnosticsSnapshot snapshot = service.publishRow(0, false);
        assertEquals(2, snapshot.edges().size());
        assertEquals(List.of(DynamicArtDiagnosticsSnapshot.Request.ram(
                        0xC800, SONIC_VRAM, 0x2E0)),
                snapshot.edges().get(1).requests());
    }

    @Test
    void specialStageRamOwnersHaveIndependentDuplicateCursors() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);

        DynamicArtLifecycleService.ArtUpdate sonic =
                service.observeRamDplc("ss-sonic", 0,
                        List.of(new TileLoadRequest(0, 2)),
                        0xFF0000, 0x5CA0);
        DynamicArtLifecycleService.ArtUpdate tails =
                service.observeRamDplc("ss-tails", 0,
                        List.of(new TileLoadRequest(0x183, 3)),
                        0xFF0000, 0x6000);
        DynamicArtLifecycleService.ArtUpdate tailsTail =
                service.observeRamDplc("ss-tails-tails", 0,
                        List.of(new TileLoadRequest(0x2AE, 1)),
                        0xFF0000, 0x62C0);

        assertTrue(sonic.submitted());
        assertTrue(tails.submitted());
        assertTrue(tailsTail.submitted());
        assertEquals(List.of("ss-sonic", "ss-tails", "ss-tails-tails"),
                service.publishRow(0, false).edges().stream()
                        .map(DynamicArtDiagnosticsSnapshot.Edge::owner).toList());
    }

    @Test
    void lagRowsKeepThePublishedLedgerAndForwardEdgesToTheNextNonLagRow() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtLifecycleService.ArtUpdate update =
                service.observeRomDplc("sonic", 1,
                        List.of(new TileLoadRequest(0, 1)), SONIC_ART, SONIC_VRAM);
        service.completeApplied(update);

        DynamicArtDiagnosticsSnapshot lag = service.publishRow(0, true);
        DynamicArtDiagnosticsSnapshot next = service.publishRow(1, false);

        assertTrue(lag.edges().isEmpty());
        assertTrue(lag.outstandingTransferIds().isEmpty());
        assertEquals(2, next.edges().size());
        assertEquals(0, next.edges().getFirst().logicalFrame());
        assertEquals(1, next.edges().getFirst().publicationFrame());
    }

    @Test
    void terminalPublicationForwardsBufferedEdgesOnTheFinalRow() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        service.observeRomDplc("sonic", 1,
                List.of(new TileLoadRequest(0, 1)), SONIC_ART, SONIC_VRAM);

        DynamicArtDiagnosticsSnapshot terminal = service.publishTerminal(4);

        assertTrue(terminal.edges().getFirst().terminalForwarded());
        assertEquals(4, terminal.edges().getFirst().publicationFrame());
        assertEquals(List.of(0L), terminal.outstandingTransferIds());
    }

    @Test
    void terminalVblankCompletionExtendsTheLastPublishedRow() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtLifecycleService.ArtUpdate pending =
                service.observePlayerDplc(GameId.S2, "sonic", 1,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(0, 1))));
        service.finishProductionIteration(false);

        // The main-loop iteration after the last sampled frame still services
        // its V-int (docs/s2disasm/s2.asm:5091 WaitForVint ->
        // docs/s2disasm/s2.asm:1769 ProcessDMAQueue), retiring the transfer
        // submitted on that frame.
        service.serviceTerminalProductionVBlank();
        service.closeComparisonSegment();

        DynamicArtDiagnosticsSnapshot terminal = service.latestSnapshot();
        assertEquals(0, terminal.frame());
        assertEquals(List.of("submitted", "completed"),
                terminal.edges().stream()
                        .map(DynamicArtDiagnosticsSnapshot.Edge::phase).toList());
        assertFalse(terminal.edges().getFirst().terminalForwarded());
        assertTrue(terminal.edges().getLast().terminalForwarded());
        assertEquals(0, terminal.edges().getLast().publicationFrame());
        assertEquals(1, terminal.edges().getLast().logicalFrame());
        assertEquals(pending.transferId(),
                terminal.edges().getLast().transferId());
        assertTrue(terminal.outstandingTransferIds().isEmpty());
    }

    @Test
    void terminalVblankLeavesStagedArtUnsubmitted() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        service.observePlayerDplc(GameId.S1, "sonic", 1,
                new SpriteDplcFrame(List.of(new TileLoadRequest(0, 1))));
        service.finishProductionIteration(false);

        // Staged-only art is not queued work
        // (docs/s1disasm/_incObj/01 Sonic.asm:2392 Sonic_LoadGfx writes
        // v_sgfx_buffer and sets f_sonframechg; the V-int issues the transfer
        // at docs/s1disasm/sonic.asm:831), so the terminal boundary emits none.
        service.serviceTerminalProductionVBlank();
        service.closeComparisonSegment();

        assertTrue(service.latestSnapshot().edges().isEmpty());
        assertTrue(service.latestSnapshot().outstandingTransferIds().isEmpty());
    }

    @Test
    void closingCannotInventATerminalRowBeforeAnyProductionIteration() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        service.observeRomDplc("sonic", 1,
                List.of(new TileLoadRequest(0, 1)), SONIC_ART, SONIC_VRAM);

        assertThrows(IllegalStateException.class,
                service::closeComparisonSegment);
    }

    @Test
    void abandoningUnpublishedWindowPreservesProductionIdentityAndPendingWork() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtLifecycleService.ArtUpdate pending = service.observeRamDplc(
                GameId.S2, "sonic", 1,
                List.of(new TileLoadRequest(0, 1)), 0x1000, SONIC_VRAM);

        service.abandonComparisonSegment();

        assertFalse(service.isComparisonSegmentOpen());
        assertFalse(service.observeRamDplc(
                GameId.S2, "sonic", 1,
                List.of(new TileLoadRequest(0, 1)), 0x1000, SONIC_VRAM)
                .submitted(), "mapping identity must prevent duplicate submission");
        assertEquals(List.of(pending.transferId()),
                service.gapSnapshot().ledger().stream()
                        .map(DynamicArtGapDiagnosticsSnapshot.Descriptor::transferId)
                        .toList());

        service.serviceProductionVBlank();
        assertTrue(service.gapSnapshot().ledger().isEmpty(),
                "pre-abort pending work must retire at its normal VBlank");
        service.openComparisonSegment();
        DynamicArtLifecycleService.ArtUpdate next = service.observeRamDplc(
                GameId.S2, "sonic", 2,
                List.of(new TileLoadRequest(1, 1)), 0x1000, SONIC_VRAM);
        assertEquals(pending.transferId() + 1, next.transferId(),
                "abandon must preserve stable monotonic transfer identities");
    }

    @Test
    void comparisonSegmentOpenRejectsPendingWorkSubmittedByPriorSegment() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        service.observeRomDplc("sonic", 1,
                List.of(new TileLoadRequest(0, 1)), SONIC_ART, SONIC_VRAM);
        service.publishTerminal(0);
        service.closeComparisonSegment();

        assertThrows(IllegalStateException.class, service::openComparisonSegment);
        service.resetForMissingSnapshot();
        startOpen(service);
        assertEquals(-1, service.latestSnapshot().frame());
        assertTrue(service.latestSnapshot().edges().isEmpty());
        assertTrue(service.latestSnapshot()
                .outstandingTransferIds().isEmpty());
    }

    @Test
    void rewindRestoresLedgerBufferAndPublicationCursorTogether() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtLifecycleService.ArtUpdate first =
                service.observeRomDplc("sonic", 1,
                        List.of(new TileLoadRequest(0, 1)), SONIC_ART, SONIC_VRAM);
        DynamicArtLifecycleService.RewindState saved = service.capture();

        service.completeApplied(first);
        service.publishRow(0, false);
        service.restore(saved);

        DynamicArtDiagnosticsSnapshot restored = service.publishTerminal(3);
        assertEquals(1, restored.edges().size());
        assertEquals("submitted", restored.edges().getFirst().phase());
        assertEquals(List.of(first.transferId()), restored.outstandingTransferIds());
    }

    @Test
    void deliverySerialSurvivesRewindAndAdvancesOnRepublish() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtLifecycleService.RewindState beforePublication =
                service.capture();
        long generation =
                service.latestSnapshot().segmentGeneration();

        DynamicArtDiagnosticsSnapshot first = service.publishRow(0, false);
        service.restore(beforePublication);

        assertEquals(first.deliverySerial(),
                service.latestSnapshot().deliverySerial(),
                "rewind restore must preserve the current delivery epoch");
        assertEquals(generation,
                service.latestSnapshot().segmentGeneration(),
                "rewind restore must preserve the service-lifetime segment generation");
        assertFalse(service.latestSnapshot().published(),
                "rewind restore must restore payload publication state");
        DynamicArtDiagnosticsSnapshot republished =
                service.publishRow(0, false);
        assertTrue(republished.deliverySerial() > first.deliverySerial());
    }

    @Test
    void deliverySerialSurvivesFinishBeginAndFirstPublication() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtDiagnosticsSnapshot first = service.publishRow(0, false);
        service.closeComparisonSegment();
        service.finishRun();

        service.beginRun();
        service.openComparisonSegment();

        assertEquals(first.deliverySerial(),
                service.latestSnapshot().deliverySerial());
        DynamicArtDiagnosticsSnapshot next = service.publishRow(0, false);
        assertTrue(next.deliverySerial() > first.deliverySerial());
    }

    @Test
    void s2PendingBatchesCompleteFifoAtTheNextProductionVblank() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);

        DynamicArtLifecycleService.ArtUpdate sonic =
                service.observePlayerDplc(GameId.S2, "sonic", 1,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(0, 1))));
        DynamicArtLifecycleService.ArtUpdate tails =
                service.observePlayerDplc(GameId.S2, "tails", 2,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(3, 2))));
        DynamicArtDiagnosticsSnapshot submitted =
                service.publishRow(0, false);

        assertEquals(List.of("submitted", "submitted"),
                submitted.edges().stream()
                        .map(DynamicArtDiagnosticsSnapshot.Edge::phase).toList());
        assertEquals(List.of(sonic.transferId(), tails.transferId()),
                submitted.outstandingTransferIds());

        service.serviceProductionVBlank();
        service.observePlayerDplc(GameId.S2, "sonic", 3,
                new SpriteDplcFrame(List.of(new TileLoadRequest(7, 1))));
        DynamicArtDiagnosticsSnapshot next = service.publishRow(1, false);

        assertEquals(List.of("completed", "completed", "submitted"),
                next.edges().stream()
                        .map(DynamicArtDiagnosticsSnapshot.Edge::phase).toList());
        assertEquals(List.of(sonic.transferId(), tails.transferId()),
                next.edges().subList(0, 2).stream()
                        .map(DynamicArtDiagnosticsSnapshot.Edge::transferId).toList());
    }

    @Test
    void terminalTransferCompletesInClosedGapAndNextSegmentCanOpen() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        startOpen(service);
        DynamicArtLifecycleService.ArtUpdate terminal =
                service.observePlayerDplc(GameId.S2, "sonic", 1,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(0, 1))));
        service.publishRow(0, false);
        service.closeComparisonSegment();

        service.serviceProductionVBlank();

        assertEquals(List.of(terminal.transferId()),
                service.gapEdges().stream()
                        .map(DynamicArtGapTransition.GapEdge::transferId).toList());
        assertEquals("completed", service.gapEdges().getFirst().phase());
        service.openComparisonSegment();
        assertTrue(service.isComparisonSegmentOpen());
    }

    @Test
    void unpublishedS1PreparationSurvivesArmAndPromotesAtVblank() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();
        DynamicArtLifecycleService.ArtUpdate pending =
                service.observePlayerDplc(GameId.S1, "sonic", 48,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(700, 16))));

        assertFalse(pending.submitted());
        assertTrue(service.gapEdges().isEmpty());
        service.openComparisonSegment();
        assertTrue(service.latestSnapshot().outstandingTransferIds().isEmpty());

        service.serviceProductionVBlank();
        DynamicArtDiagnosticsSnapshot first = service.publishRow(0, false);
        assertEquals(List.of(0L, 0L),
                first.edges().stream()
                        .map(DynamicArtDiagnosticsSnapshot.Edge::transferId).toList());
        assertEquals(List.of("submitted", "completed"),
                first.edges().stream()
                        .map(DynamicArtDiagnosticsSnapshot.Edge::phase).toList());
        assertTrue(first.outstandingTransferIds().isEmpty());
    }

    @Test
    void s1PreparationReplacementIsRewindAtomic() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();
        service.observePlayerDplc(GameId.S1, "sonic", 9,
                new SpriteDplcFrame(List.of(new TileLoadRequest(84, 6))));
        DynamicArtLifecycleService.RewindState snapshot = service.capture();
        service.observePlayerDplc(GameId.S1, "sonic", 1,
                new SpriteDplcFrame(List.of(new TileLoadRequest(0, 3))));

        service.restore(snapshot);
        service.openComparisonSegment();
        service.serviceProductionVBlank();
        DynamicArtDiagnosticsSnapshot row = service.publishRow(0, false);
        assertEquals(9, row.edges().getFirst().mappingFrame());
        assertEquals(SONIC_ART + 84 * 0x20,
                row.edges().getFirst().requests().getFirst().romSourceAddress());
    }

    @Test
    void s1PreparationCannotLeakAcrossMissingSnapshotOrRunReset() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();
        service.observePlayerDplc(GameId.S1, "sonic", 9,
                new SpriteDplcFrame(List.of(new TileLoadRequest(84, 6))));
        service.resetForMissingSnapshot();

        service.beginRun();
        service.openComparisonSegment();
        service.serviceProductionVBlank();
        assertTrue(service.publishRow(0, false).edges().isEmpty());
        service.finishRun();

        service.beginRun();
        service.openComparisonSegment();
        service.serviceProductionVBlank();
        assertTrue(service.publishRow(0, false).edges().isEmpty());
    }

    @Test
    void completeLifecycleInGapIsJournaledWithoutResettingOwnerCursor() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();

        DynamicArtLifecycleService.ArtUpdate first =
                service.observePlayerDplc(GameId.S2, "tails", 5,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(1, 1))));
        service.serviceProductionVBlank();

        assertEquals(List.of("submitted", "completed"),
                service.gapEdges().stream()
                        .map(DynamicArtGapTransition.GapEdge::phase).toList());
        assertEquals(List.of(first.transferId(), first.transferId()),
                service.gapEdges().stream()
                        .map(DynamicArtGapTransition.GapEdge::transferId).toList());

        service.openComparisonSegment();
        DynamicArtLifecycleService.ArtUpdate duplicate =
                service.observePlayerDplc(GameId.S2, "tails", 5,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(1, 1))));
        assertFalse(duplicate.mappingChanged());
    }

    @Test
    void immutableGapLedgerPreservesProductionSubmissionOrigin() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();

        service.observePlayerDplc(GameId.S2, "sonic", 9,
                new SpriteDplcFrame(List.of(new TileLoadRequest(2, 1))));

        DynamicArtGapDiagnosticsSnapshot.Descriptor pending =
                service.gapSnapshot().ledger().getFirst();
        assertEquals("run_gap", pending.submissionOrigin());

        service.openComparisonSegment();
        assertEquals("run_gap",
                service.gapSnapshot().ledger().getFirst().submissionOrigin(),
                "opening a destination must not relabel pending gap work");
    }

    @Test
    void rewindRestoresClosedGapFifoLedgerAndRunCursorsAtomically() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();
        DynamicArtLifecycleService.ArtUpdate pending =
                service.observePlayerDplc(GameId.S2, "sonic", 9,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(2, 1))));
        DynamicArtLifecycleService.RewindState saved = service.capture();

        service.serviceProductionVBlank();
        service.openComparisonSegment();
        service.restore(saved);
        service.serviceProductionVBlank();

        assertFalse(service.isComparisonSegmentOpen());
        assertEquals(List.of("submitted", "completed"),
                service.gapEdges().stream()
                        .map(DynamicArtGapTransition.GapEdge::phase).toList());
        assertEquals(List.of(pending.transferId(), pending.transferId()),
                service.gapEdges().stream()
                        .map(DynamicArtGapTransition.GapEdge::transferId).toList());
    }

    @Test
    void finishRunAllowsNativePendingWorkButRejectsFurtherDecisions() {
        DynamicArtLifecycleService service = new DynamicArtLifecycleService();
        service.beginRun();
        service.observePlayerDplc(GameId.S2, "sonic", 1,
                new SpriteDplcFrame(List.of(new TileLoadRequest(0, 1))));

        service.finishRun();

        assertFalse(service.isRunActive());
        assertThrows(IllegalStateException.class,
                () -> service.observePlayerDplc(GameId.S2, "sonic", 2,
                        new SpriteDplcFrame(List.of(new TileLoadRequest(1, 1)))));
    }

    private static void startOpen(DynamicArtLifecycleService service) {
        service.beginRun();
        service.openComparisonSegment();
    }
}
