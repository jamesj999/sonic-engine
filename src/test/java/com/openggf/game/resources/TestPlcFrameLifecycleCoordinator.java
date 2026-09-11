package com.openggf.game.resources;

import com.openggf.game.GameModule;
import org.junit.jupiter.api.Test;
import com.openggf.game.rules.DynamicArtDmaServiceModel;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestPlcFrameLifecycleCoordinator {

    @Test
    void moduleWithoutTypedRulesUsesNeutralDynamicArtServicePolicy() {
        GameModule module = mock(GameModule.class);
        when(module.getGameService(PlcLifecycleService.class))
                .thenReturn(recording(new ArrayList<>()));
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(module, dynamicArt);

        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.TITLE_SCREEN);
            return null;
        });

        assertEquals(0, dynamicArt.latestSnapshot().frame());
    }

    @Test
    void sonic2CrossArmFifoWaitsForSpecialStageServiceClaim() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.observePlayerDplc(
                com.openggf.game.GameId.S2, "tails-tails", 13,
                new com.openggf.level.render.SpriteDplcFrame(List.of(
                        new com.openggf.level.render.TileLoadRequest(110, 12))));
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt,
                        DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE);

        for (int row = 0; row < 126; row++) {
            coordinator.runLogicalIteration(() -> { }, frame -> {
                frame.claim(PlcLifecyclePhase.PALETTE_FADE);
                frame.prepareAfterLoop(PlcLifecyclePhase.PALETTE_FADE);
                return null;
            });
            assertEquals(List.of(0L),
                    dynamicArt.latestSnapshot().outstandingTransferIds());
        }
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });

        assertTrue(dynamicArt.latestSnapshot().outstandingTransferIds().isEmpty());
        assertEquals("completed",
                dynamicArt.latestSnapshot().edges().getFirst().phase());
    }

    @Test
    void claimRetiresPreviousS2FifoAndFinishPublishesProductionRow() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt);

        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ENDING);
            dynamicArt.observePlayerDplc(
                    com.openggf.game.GameId.S2, "sonic", 1,
                    new com.openggf.level.render.SpriteDplcFrame(List.of(
                            new com.openggf.level.render.TileLoadRequest(0, 1))));
            return null;
        });
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ENDING);
            return null;
        });

        DynamicArtDiagnosticsSnapshot snapshot =
                dynamicArt.latestSnapshot();
        assertEquals(1, snapshot.frame());
        assertEquals("completed", snapshot.edges().getFirst().phase());
        assertTrue(snapshot.outstandingTransferIds().isEmpty());
    }

    @Test
    void immutableSnapshotCanOnlyBePulledAfterFinishPublishesIt() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt);
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            dynamicArt.observeRamDplc(
                    "ss-sonic", 2,
                    List.of(new com.openggf.level.render.TileLoadRequest(0, 1)),
                    0xFF0000, 0x5CA0);
            assertEquals(-1, dynamicArt.latestSnapshot().frame());
            return null;
        });

        DynamicArtDiagnosticsSnapshot first = dynamicArt.latestSnapshot();
        assertEquals(0, first.frame());
        assertEquals("submitted",
                first.edges().getFirst().phase());
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });

        assertEquals(1, dynamicArt.latestSnapshot().frame());
        dynamicArt.finishRun();
        dynamicArt.beginRun();
    }

    @Test
    void externalOwnershipRejectsAnUnpublishedAutomaticWindowWithoutChangingIt() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt);
        var frame = coordinator.latchBeforeFadeUpdate();
        frame.claim(PlcLifecyclePhase.ENDING);
        long generation = dynamicArt.latestSnapshot().segmentGeneration();

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                coordinator::acquireExternalComparisonSegmentOwnership);

        assertTrue(failure.getMessage().contains("has not published a row"));
        assertTrue(dynamicArt.isComparisonSegmentOpen());
        assertEquals(generation,
                dynamicArt.latestSnapshot().segmentGeneration());
        frame.finish();

        coordinator.acquireExternalComparisonSegmentOwnership();
        assertFalse(dynamicArt.isComparisonSegmentOpen(),
                "a completed automatic window can be handed off");
    }

    @Test
    void deferredExternalOwnershipServicesBootstrapArtBeforePublishingRowZero() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt,
                        DynamicArtDmaServiceModel.SONIC_1_VBLANK_SONIC_GFX);

        // Visual launch transfers ownership before TraceReplayDriver.start()
        // loads the level and performs the playable setup pass.
        coordinator.acquireExternalComparisonSegmentOwnershipAfterNextService();
        long reservedGeneration =
                dynamicArt.latestSnapshot().segmentGeneration();
        assertTrue(dynamicArt.isComparisonSegmentReserved());
        assertFalse(dynamicArt.latestSnapshot().published());
        dynamicArt.observePlayerDplc(
                com.openggf.game.GameId.S1, "sonic", 8,
                new com.openggf.level.render.SpriteDplcFrame(List.of(
                        new com.openggf.level.render.TileLoadRequest(0, 12))));

        assertFalse(dynamicArt.isComparisonSegmentOpen(),
                "segment zero must wait for the first production service");
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });

        DynamicArtDiagnosticsSnapshot rowZero = dynamicArt.latestSnapshot();
        assertTrue(dynamicArt.isComparisonSegmentOpen());
        assertTrue(rowZero.published());
        assertEquals(0, rowZero.frame());
        assertEquals(reservedGeneration, rowZero.segmentGeneration(),
                "the pre-iteration snapshot and row zero must share a generation");
        assertEquals(List.of(), rowZero.edges(),
                "bootstrap submit/complete must not become comparison row zero");
        assertEquals(List.of(), rowZero.outstandingTransferIds());
        assertEquals(List.of("submitted", "completed"),
                dynamicArt.gapEdges().stream()
                        .map(DynamicArtGapTransition.GapEdge::phase)
                        .toList());
    }

    @Test
    void closingDeferredExternalOwnershipBeforeClaimCreatesNoWindow() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt);
        long generation = dynamicArt.latestSnapshot().segmentGeneration();

        coordinator.acquireExternalComparisonSegmentOwnershipAfterNextService();
        assertTrue(dynamicArt.isComparisonSegmentReserved());
        coordinator.closeExternallyManagedComparisonSegment();
        coordinator.setComparisonSegmentsExternallyManaged(false);

        assertFalse(dynamicArt.isComparisonSegmentOpen());
        assertFalse(dynamicArt.isComparisonSegmentReserved());
        assertEquals(generation + 1,
                dynamicArt.latestSnapshot().segmentGeneration(),
                "cancellation keeps the already allocated monotonic generation");
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertTrue(dynamicArt.isComparisonSegmentOpen(),
                "automatic ownership must resume after cancellation");
    }

    @Test
    void resetCancelsDeferredExternalComparisonReservation() {
        DynamicArtLifecycleService dynamicArt = new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        PlcFrameLifecycleCoordinator coordinator = new PlcFrameLifecycleCoordinator(
                recording(new ArrayList<>()), dynamicArt);
        coordinator.acquireExternalComparisonSegmentOwnershipAfterNextService();

        coordinator.reset();

        assertFalse(dynamicArt.isComparisonSegmentReserved());
        assertFalse(dynamicArt.isComparisonSegmentOpen());
    }

    @Test
    void rewindRestoreDoesNotPublishSnapshot() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt);
        DynamicArtLifecycleService.RewindState saved = dynamicArt.capture();

        dynamicArt.restore(saved);
        assertEquals(-1, dynamicArt.latestSnapshot().frame());
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.SPECIAL_STAGE);
            return null;
        });

        assertEquals(0, dynamicArt.latestSnapshot().frame());
    }

    @Test
    void nativeFadeKeepsOutgoingTokenAcrossCompletionCallback() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));
        NativeFadeLifecycle.NativeBlockingFade fade = coordinator.beginNativeBlockingFade();
        var frame = coordinator.latchBeforeFadeUpdate();

        fade.wrapCompletion(() -> events.add("callback")).run();
        assertFalse(frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL));
        frame.prepareAfterLoop(PlcLifecyclePhase.PALETTE_FADE);
        frame.finish();

        var next = coordinator.latchBeforeFadeUpdate();
        assertTrue(next.claim(PlcLifecyclePhase.ORDINARY_LEVEL));
        next.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
        next.finish();
        org.junit.jupiter.api.Assertions.assertEquals(List.of(
                "service:PALETTE_FADE", "callback", "prepare:PALETTE_FADE",
                "service:ORDINARY_LEVEL", "prepare:ORDINARY_LEVEL"), events);
    }

    @Test
    void suppressedLagLeavesAnActiveNativeFadePaused() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));
        coordinator.beginNativeBlockingFade();

        coordinator.runSuppressedLagIteration(frame -> {
            assertTrue(frame.claim(PlcLifecyclePhase.LAG));
            return null;
        });
        coordinator.runLogicalIteration(() -> { }, frame -> {
            assertTrue(frame.isOwnedBy(PlcLifecyclePhase.PALETTE_FADE));
            return null;
        });

        assertEquals(List.of(
                "service:LAG", "service:PALETTE_FADE",
                "prepare:PALETTE_FADE"), events);
    }

    /**
     * ROM {@code Vint_CtrlDMA} (docs/s2disasm/s2.asm:998-1002) is armed at
     * s2.asm:6665, before {@code Pal_FadeFromWhite} (s2.asm:3460) is entered at
     * s2.asm:6672. That V-blank is therefore not an iteration of the fade, and
     * because it never reaches {@code ReadJoypads} it is a lag row. The fade
     * must not pre-claim it, so the lag row's own claim survives and defers its
     * dynamic-art publication to the following boundary -- while the same claim
     * still services the DMA queue the handler exists to drain.
     */
    @Test
    void declaredDmaQueueOnlyVblankKeepsAnActiveFadeFromClaimingTheLagRow() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));
        coordinator.beginNativeBlockingFade();

        coordinator.markNextVblankServicesDmaQueue();
        var ctrlDma = coordinator.latchBeforeFadeUpdate();
        assertFalse(ctrlDma.isOwnedBy(PlcLifecyclePhase.PALETTE_FADE),
                "Vint_CtrlDMA is not a Pal_FadeFromWhite iteration");
        assertTrue(ctrlDma.claim(PlcLifecyclePhase.LAG));
        ctrlDma.finish();

        // The one-shot is consumed by that claim, so the next V-blank is an
        // ordinary fade iteration again.
        var next = coordinator.latchBeforeFadeUpdate();
        assertTrue(next.isOwnedBy(PlcLifecyclePhase.PALETTE_FADE));
        next.prepareAfterLoop(PlcLifecyclePhase.PALETTE_FADE);
        next.finish();

        assertEquals(List.of(
                "service:LAG", "service:PALETTE_FADE",
                "prepare:PALETTE_FADE"), events);
    }

    /**
     * The declaration is a one-shot even when no run comparison is active, so
     * it can never leak into a later represented V-blank.
     */
    @Test
    void declaredDmaQueueOnlyVblankIsConsumedWithoutAnActiveRun() {
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(new ArrayList<>()));
        coordinator.beginNativeBlockingFade();

        coordinator.markNextVblankServicesDmaQueue();
        var ctrlDma = coordinator.latchBeforeFadeUpdate();
        assertTrue(ctrlDma.claim(PlcLifecyclePhase.LAG));
        ctrlDma.finish();

        var second = coordinator.latchBeforeFadeUpdate();
        assertTrue(second.isOwnedBy(PlcLifecyclePhase.PALETTE_FADE));
        second.prepareAfterLoop(PlcLifecyclePhase.PALETTE_FADE);
        second.finish();
    }

    @Test
    void tokenRejectsReuseDuplicatePreparationAndMissingPreparation() {
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(new ArrayList<>()));
        var missing = coordinator.latchBeforeFadeUpdate();
        missing.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
        assertThrows(IllegalStateException.class, missing::finish);
        missing.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
        missing.finish();
        assertThrows(IllegalStateException.class,
                () -> missing.claim(PlcLifecyclePhase.LAG));

        var duplicate = coordinator.latchBeforeFadeUpdate();
        duplicate.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
        duplicate.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
        assertThrows(IllegalStateException.class,
                () -> duplicate.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL));
        duplicate.finish();
    }

    @Test
    void replayedIterationReplacesOnlyAnUnclaimedOuterFrame() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));

        coordinator.runLogicalIteration(() -> events.add("outer-fade"), outer ->
                coordinator.runReplayedLogicalIteration(
                        () -> events.add("replay-fade"), replay -> {
                            replay.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
                            replay.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
                            return null;
                        }));

        assertEquals(List.of(
                "outer-fade", "replay-fade",
                "service:ORDINARY_LEVEL", "prepare:ORDINARY_LEVEL"), events);
    }

    @Test
    void replayedIterationRetainsTheGuardForAClaimedOuterFrame() {
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(new ArrayList<>()));

        assertThrows(IllegalStateException.class, () ->
                coordinator.runLogicalIteration(() -> { }, outer -> {
                    outer.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
                    return coordinator.runReplayedLogicalIteration(() -> { }, replay -> null);
                }));
    }

    @Test
    void replayedIterationCannotReplaceAnOuterFrameThatDispatchedGameVblank() {
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(new ArrayList<>()));

        assertThrows(IllegalStateException.class, () ->
                coordinator.runLogicalIteration(() -> { }, outer -> {
                    outer.dispatchGameVBlank(() -> { });
                    return coordinator.runReplayedLogicalIteration(
                            () -> { }, replay -> null);
                }));
    }

    @Test
    void preFadeVblankCannotChargeWorkArmedByTheFadeCompletionCallback() {
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(new ArrayList<>()));
        java.util.concurrent.atomic.AtomicBoolean pending =
                new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger services =
                new java.util.concurrent.atomic.AtomicInteger();

        coordinator.runLogicalIteration(
                frame -> frame.dispatchGameVBlank(() -> {
                    if (pending.get()) services.incrementAndGet();
                }),
                () -> pending.set(true),
                frame -> {
                    frame.dispatchGameVBlank(services::incrementAndGet);
                    return null;
                });

        assertTrue(pending.get());
        assertEquals(0, services.get(),
                "work armed after the physical VBlank waits for the next token");
    }

    @Test
    void preparationValidationDoesNotMaskThePrimaryIterationFailure() {
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(new ArrayList<>()));
        IllegalArgumentException primary = new IllegalArgumentException("primary");

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> coordinator.runLogicalIteration(() -> { }, frame -> {
                    frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
                    throw primary;
                }));

        assertSame(primary, thrown);
        org.junit.jupiter.api.Assertions.assertEquals(1, thrown.getSuppressed().length);
        assertTrue(thrown.getSuppressed()[0].getMessage()
                .contains("missing PLC preparation for ORDINARY_LEVEL"));
    }

    /**
     * S1's staged Sonic gfx transfer is dispatched only by the
     * f_sonframechg-gated {@code writeVRAM v_sgfx_buffer,...} inside the
     * per-mode VBlank handlers (docs/s1disasm/sonic.asm:829-833). A lag frame
     * branches to VBlank_Lag before any of them (sonic.asm:652-655), which runs
     * the sound driver only (sonic.asm:709-715, 678-684), so the preparation
     * survives to the next real VBlank and its edges carry that row's logical
     * frame -- not the lag row's.
     */
    @Test
    void sonic1LagClaimDefersTheStagedSonicGfxTransfer() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.observePlayerDplc(
                com.openggf.game.GameId.S1, "sonic", 0x32,
                new com.openggf.level.render.SpriteDplcFrame(List.of(
                        new com.openggf.level.render.TileLoadRequest(0, 12))));
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt,
                        DynamicArtDmaServiceModel.SONIC_1_VBLANK_SONIC_GFX);

        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.LAG);
            return null;
        });

        assertTrue(dynamicArt.latestSnapshot().edges().isEmpty(),
                "VBlank_Lag dispatches no Sonic gfx transfer");

        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });

        DynamicArtDiagnosticsSnapshot published = dynamicArt.latestSnapshot();
        assertEquals(2, published.edges().size());
        for (DynamicArtDiagnosticsSnapshot.Edge edge : published.edges()) {
            assertEquals(published.frame(), edge.logicalFrame());
            assertEquals(published.frame(), edge.publicationFrame());
        }
    }

    /**
     * A represented iteration on which no V-blank elapsed at all is a different
     * ROM shape from a lag V-blank. {@code Vint_runcount} is bumped once per
     * V-blank at {@code VintRet} (docs/s2disasm/s2.asm:507-508) whichever handler
     * ran, so a row with no V-blank tick means the main loop iteration overran
     * its V-blank: the following iteration is still mid-flight at the sample
     * boundary and its queue-add (s2.asm:1705) publishes on the boundary after
     * it. {@code ProcessDMAQueue} (s2.asm:1770) is reached only from the real
     * V-int handlers (s2.asm:781, 899, 1000, 1046, 1083, 1138), never from
     * {@code Vint_Lag} (s2.asm:529-580).
     */
    @Test
    void aRepresentedIterationWithoutAVblankDefersTheNextRowsPublication() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt,
                        DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE);

        // Row N: no V-blank elapsed for this iteration.
        coordinator.markRepresentedIterationWithoutVblank();
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.LAG);
            return null;
        });
        assertTrue(dynamicArt.latestSnapshot().edges().isEmpty());

        // Row N+1: the overrunning iteration completes and queues a DPLC. Its
        // publication rolls into row N+2 with the ledger still empty here.
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            dynamicArt.observePlayerDplc(
                    com.openggf.game.GameId.S2, "sonic", 0x0F,
                    new com.openggf.level.render.SpriteDplcFrame(List.of(
                            new com.openggf.level.render.TileLoadRequest(0, 12))));
            return null;
        });
        assertTrue(dynamicArt.latestSnapshot().edges().isEmpty(),
                "the overrunning iteration reached no publication boundary");

        // Row N+2: the carry is a one-shot -- this ordinary row publishes.
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        DynamicArtDiagnosticsSnapshot published = dynamicArt.latestSnapshot();
        assertFalse(published.edges().isEmpty());
        // The publication boundary is what this test pins down. The buffered
        // edge's logical-frame attribution is owned by
        // DynamicArtLifecycleService's movie clock, which does not tick on a
        // boundary the iteration never reached, so it is deliberately not
        // asserted here.
        for (DynamicArtDiagnosticsSnapshot.Edge edge : published.edges()) {
            assertEquals(published.frame(), edge.publicationFrame());
        }
    }

    /**
     * The mid-V-int sample is itself a dynamic-art publication boundary: the
     * real per-mode handler calls {@code ProcessDMAQueue} (docs/s2disasm/
     * s2.asm:781, routine at s2.asm:1770) before {@code VintRet} bumps
     * {@code Vint_runcount} (s2.asm:507-508). Only the successor -- the
     * iteration that overran -- is withheld.
     */
    @Test
    void aVblankStarvedRowPublishesItsOwnRowAndOnlyItsSuccessorIsCarried() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt,
                        DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE);

        coordinator.markRepresentedIterationWithoutVblank();
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.LAG);
            observeSonicDplc(dynamicArt, 0x0F);
            return null;
        });
        assertFalse(dynamicArt.latestSnapshot().edges().isEmpty(),
                "the mid-V-int row already ran ProcessDMAQueue, so it publishes");

        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            observeSonicDplc(dynamicArt, 0x10);
            return null;
        });
        assertTrue(dynamicArt.latestSnapshot().edges().isEmpty(),
                "only the overrunning successor is withheld");
    }

    /**
     * Back-to-back mid-V-int samples: each ran its own {@code ProcessDMAQueue}
     * (s2.asm:781), so neither is carried. The guard is the row's own shape,
     * not a one-shot carry.
     */
    @Test
    void consecutiveVblankStarvedRowsEachPublishTheirOwnRow() {
        DynamicArtLifecycleService dynamicArt =
                new DynamicArtLifecycleService();
        dynamicArt.beginRun();
        dynamicArt.openComparisonSegment();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(
                        recording(new ArrayList<>()), dynamicArt,
                        DynamicArtDmaServiceModel.SONIC_2_PROCESS_DMA_QUEUE);

        for (int index = 0; index < 2; index++) {
            final int mappingFrame = 0x20 + index;
            coordinator.markRepresentedIterationWithoutVblank();
            coordinator.runLogicalIteration(() -> { }, frame -> {
                frame.claim(PlcLifecyclePhase.LAG);
                observeSonicDplc(dynamicArt, mappingFrame);
                return null;
            });
            assertFalse(dynamicArt.latestSnapshot().edges().isEmpty(),
                    "starved row " + index + " publishes its own row");
        }
    }

    /**
     * S1 {@code RunPLC} (docs/s1disasm/sonic.asm:3032) sits at the tail of
     * {@code Level_MainLoop}, after every expensive call in it. An iteration
     * still in flight when the next V-blank fires takes {@code VBlank_Lag}
     * (sonic.asm:709) and runs its {@code RunPLC} on that lag closure, so the
     * held tail belongs to the lag row rather than to the row that closed the
     * previous entry.
     */
    @Test
    void aHeldIterationRunsItsLoopTailPreparationOnTheLagClosure() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));

        coordinator.markRepresentedIterationDefersLoopTailPreparation();
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        assertEquals(List.of("service:ORDINARY_LEVEL"), events,
                "the held row services its V-blank but not its loop tail");

        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.LAG);
            return null;
        });
        assertEquals(
                List.of("service:ORDINARY_LEVEL", "service:LAG",
                        "prepare:ORDINARY_LEVEL"),
                events,
                "the lag closure runs the held iteration's RunPLC");
    }

    /** A stall spanning several lag rows keeps the tail until the loop resumes. */
    @Test
    void aTailHeldAcrossConsecutiveLagRowsRunsOnTheLastOfThem() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));

        coordinator.markRepresentedIterationDefersLoopTailPreparation();
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        coordinator.markRepresentedIterationDefersLoopTailPreparation();
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.LAG);
            return null;
        });
        assertEquals(List.of("service:ORDINARY_LEVEL", "service:LAG"), events,
                "a lag row that is itself still mid-iteration keeps the tail");

        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.LAG);
            return null;
        });
        assertEquals(
                List.of("service:ORDINARY_LEVEL", "service:LAG", "service:LAG",
                        "prepare:ORDINARY_LEVEL"),
                events);
    }

    /** The deferral is a one-shot: an ordinary row keeps its own loop tail. */
    @Test
    void anUnheldIterationRunsItsOwnLoopTailPreparation() {
        List<String> events = new ArrayList<>();
        PlcFrameLifecycleCoordinator coordinator =
                new PlcFrameLifecycleCoordinator(recording(events));

        coordinator.markRepresentedIterationDefersLoopTailPreparation();
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });
        coordinator.runLogicalIteration(() -> { }, frame -> {
            frame.claim(PlcLifecyclePhase.ORDINARY_LEVEL);
            frame.prepareAfterLoop(PlcLifecyclePhase.ORDINARY_LEVEL);
            return null;
        });

        assertEquals(
                List.of("service:ORDINARY_LEVEL", "service:ORDINARY_LEVEL",
                        "prepare:ORDINARY_LEVEL", "prepare:ORDINARY_LEVEL"),
                events,
                "the released tail runs before the resuming row's own tail");
    }

    private static void observeSonicDplc(
            DynamicArtLifecycleService dynamicArt, int mappingFrame) {
        dynamicArt.observePlayerDplc(
                com.openggf.game.GameId.S2, "sonic", mappingFrame,
                new com.openggf.level.render.SpriteDplcFrame(List.of(
                        new com.openggf.level.render.TileLoadRequest(0, 12))));
    }

    private static PlcLifecycleService recording(List<String> events) {
        return new PlcLifecycleService() {
            @Override
            public void serviceVBlank(PlcLifecyclePhase phase) {
                events.add("service:" + phase);
            }

            @Override
            public boolean hasPreparationBoundary(PlcLifecyclePhase phase) {
                return phase == PlcLifecyclePhase.PALETTE_FADE
                        || phase == PlcLifecyclePhase.ORDINARY_LEVEL;
            }

            @Override
            public void prepareAfterLoop(PlcLifecyclePhase phase) {
                events.add("prepare:" + phase);
            }
        };
    }
}
