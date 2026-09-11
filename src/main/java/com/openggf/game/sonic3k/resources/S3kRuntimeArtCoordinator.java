package com.openggf.game.sonic3k.resources;

import com.openggf.data.Rom;
import com.openggf.game.RuntimeArtCoordinator;
import com.openggf.game.GameServices;
import com.openggf.game.rewind.RewindSnapshottable;
import com.openggf.game.rewind.RewindRegistry;
import com.openggf.game.resources.QueueDiagnosticSnapshot;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.game.timing.HardwareTimingService;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.level.objects.ObjectServices;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Logger;

/** S3K-owned facade for the direct Kosinski FIFO and KosM parent queue. */
public final class S3kRuntimeArtCoordinator implements RuntimeArtCoordinator,
        RewindSnapshottable<S3kRuntimeArtCoordinator.Snapshot> {
    public static final String REWIND_KEY = "s3k-runtime-art-coordinator";

    private static final Logger LOG =
            Logger.getLogger(S3kRuntimeArtCoordinator.class.getName());

    private final S3kKosDecompressionQueue directQueue;
    private final S3kKosModuleQueue moduleQueue;
    private FreshLevelRuntimeArtRequest deferredFreshLevelRuntimeArt;
    private boolean deferredFreshLevelPublicationBlockedReported;

    private record FreshLevelRuntimeArtRequest(
            Rom rom, int primarySource, int secondarySource) {
    }

    public record Snapshot(
            Rom deferredRom,
            int deferredPrimarySource,
            int deferredSecondarySource,
            List<HardwareWorkHandle> freshLevelHandoffHandles) {
        public Snapshot {
            freshLevelHandoffHandles = List.copyOf(freshLevelHandoffHandles);
        }
    }

    public S3kRuntimeArtCoordinator(HardwareTimingService timing) {
        directQueue = new S3kKosDecompressionQueue(
                Objects.requireNonNull(timing, "timing"));
        moduleQueue = new S3kKosModuleQueue(timing, directQueue);
    }

    public static S3kRuntimeArtCoordinator from(
            RuntimeArtCoordinator coordinator) {
        if (coordinator instanceof S3kRuntimeArtCoordinator s3k) {
            return s3k;
        }
        throw new IllegalStateException(
                "S3K runtime art requires the S3K game-owned coordinator");
    }

    public static S3kRuntimeArtCoordinator current() {
        return from(GameServices.runtimeArtCoordinator());
    }

    public static S3kRuntimeArtCoordinator from(ObjectServices services) {
        return from(services.runtimeArtCoordinator());
    }

    public S3kKosDecompressionQueue directQueue() {
        return directQueue;
    }

    public S3kKosModuleQueue moduleQueue() {
        return moduleQueue;
    }

    /**
     * Defers a fresh-level terrain submission until the current loop tail has
     * serviced PRE_MAIN_LOOP.
     *
     * <p>Used only when the caller is still ahead of the ROM's
     * {@code Kos_modules_left} gate; see
     * {@link #freshLevelArtWaitsForModuleQueue}. Once that gate is clear,
     * {@code LoadLevelLoadBlock} queues both parents at the call and only then
     * blocks (docs/skdisasm/sonic3k.asm:9727, 9734, 9736-9743), so deferring
     * there would invert the ROM's order against everything that queues later:
     * the deferred batch releases its slots and the following frames' object
     * art takes them, starving the terrain art behind a full FIFO.
     */
    public void deferFreshLevelRuntimeArt(
            Rom rom, int primarySource, int secondarySource) {
        // A newer fresh load supersedes a handoff that has not yet reached the
        // publication tail. No production work for the older request exists.
        deferredFreshLevelRuntimeArt = new FreshLevelRuntimeArtRequest(
                Objects.requireNonNull(rom, "rom"), primarySource, secondarySource);
        deferredFreshLevelPublicationBlockedReported = false;
    }

    /**
     * Publishes a fresh-level KosM batch before the first post-title loop row.
     * The ROM's {@code LoadLevelLoadBlock} call queues the parent before the
     * held transition boundary; the first child is then exposed by the next
     * loop's VBlank services.
     */
    /**
     * Whether the module queue is still draining a previous producer's
     * parents, which is the ROM's own precondition for reaching
     * {@code LoadLevelLoadBlock} at all.
     *
     * <p>{@code Obj_TitleCardCreate} holds the card's routine on
     * {@code tst.b (Kos_modules_left).w} (docs/skdisasm/sonic3k.asm:62169-62171)
     * until the archives {@code Obj_TitleCardInit} queued have finished, and
     * only then does the locked loop release and {@code Level:} run
     * {@code LoadLevelLoadBlock} (:7761). So a caller arriving while modules
     * are still outstanding is ahead of the ROM's control flow, not short of
     * FIFO capacity.
     */
    public boolean freshLevelArtWaitsForModuleQueue() {
        return moduleQueue.hasPendingPhysicalModules();
    }

    public void submitFreshLevelRuntimeArt(
            Rom rom, int primarySource, int secondarySource) {
        Objects.requireNonNull(rom, "rom");
        List<Integer> sources = new ArrayList<>();
        sources.add(primarySource);
        if (secondarySource != primarySource && secondarySource > 0) {
            sources.add(secondarySource);
        }
        if (!moduleQueue.hasCapacityFor(sources.size())) {
            throw new IllegalStateException(
                    "S3K fresh-level KosM batch cannot fit in the module FIFO");
        }
        try {
            List<HardwareWorkHandle> handles =
                    moduleQueue.queueSequentialBatch(rom, sources, 0);
            for (HardwareWorkHandle handle : handles) {
                moduleQueue.claimAfterFreshLevelHandoff(handle);
            }
        } catch (java.io.IOException exception) {
            throw new IllegalStateException(
                    "Unable to submit fresh-level runtime art", exception);
        }
    }

    /**
     * ROM {@code LevelLoop} runs {@code Process_Kos_Module_Queue} in the loop
     * tail (sonic3k.asm:7908) and {@code Process_Kos_Queue} (7887) directly
     * after it, still ahead of {@code Wait_VSync} (7888) and the
     * {@code Level_frame_counter} increment (7889). Both are tail work for the
     * frame whose objects just ran, so the module step lands at
     * {@code POST_OBJECTS} and the direct FIFO service at {@code PRE_MAIN_LOOP},
     * in that order, at the end of the same frame.
     */
    @Override
    public void beforeTimingService(HardwareServiceBoundary boundary) {
        moduleQueue.beforeTimingService(boundary);
    }

    @Override
    public void afterTimingService(HardwareServiceBoundary boundary) {
        directQueue.afterTimingService(boundary);
        moduleQueue.afterTimingService(boundary);
        if (boundary == HardwareServiceBoundary.PRE_MAIN_LOOP) {
            moduleQueue.stepDeferredChildAfterDirectTail();
        }
        moduleQueue.claimReadyFreshLevelHandoffs();
        if (boundary == HardwareServiceBoundary.PRE_MAIN_LOOP
                && deferredFreshLevelRuntimeArt != null) {
            FreshLevelRuntimeArtRequest request = deferredFreshLevelRuntimeArt;
            List<Integer> sources = new ArrayList<>();
            sources.add(request.primarySource());
            if (request.secondarySource() != request.primarySource()
                    && request.secondarySource() > 0) {
                sources.add(request.secondarySource());
            }
            if (!moduleQueue.hasCapacityFor(sources.size())) {
                // The retry is per-service and silent by design: the ROM's own
                // producer simply finds no free slot this frame and comes back.
                // A queue that never drains turns that into an unbounded silent
                // stall, though, and the request is then never published at all.
                // Say so once per request rather than once per attempt.
                if (!deferredFreshLevelPublicationBlockedReported) {
                    deferredFreshLevelPublicationBlockedReported = true;
                    LOG.warning(String.format(
                            "S3K fresh-level KosM batch cannot publish: needs %d"
                                    + " slots, module FIFO holds %d of %d"
                                    + " (primary source 0x%X). Retrying every"
                                    + " service; this is silent from here on.",
                            sources.size(),
                            moduleQueue.physicalQueueSize(),
                            S3kKosModuleQueue.MAX_QUEUE_DEPTH,
                            request.primarySource()));
                }
                return;
            }
            try {
                List<HardwareWorkHandle> handles =
                        moduleQueue.queueSequentialBatch(request.rom(), sources, 0);
                for (HardwareWorkHandle handle : handles) {
                    moduleQueue.claimAfterFreshLevelHandoff(handle);
                }
                deferredFreshLevelRuntimeArt = null;
                deferredFreshLevelPublicationBlockedReported = false;
            } catch (java.io.IOException exception) {
                throw new IllegalStateException(
                        "Unable to publish deferred fresh-level runtime art", exception);
            }
        }
    }

    @Override
    public void deferProductionSubmissionForHeldLoopTail() {
        moduleQueue.deferChildSubmissionForHeldLoopTail();
        directQueue.deferNewHeadPreparationVisibilityForHeldLoopTail();
    }

    @Override
    public void deferProductionFirstChildForLateProducer() {
        moduleQueue.deferFirstChildForLateProducer();
        directQueue.deferNewHeadPreparationVisibilityForHeldLoopTail();
    }

    @Override
    public void deferProductionSubmissionForHeldLoopTailClosure() {
        moduleQueue.deferChildSubmissionForHeldLoopTailClosure();
    }

    @Override
    public void finishHeldLoopTailClosure() {
        moduleQueue.finishHeldLoopTailClosure();
    }

    @Override
    public boolean ownsHeldLevelCounterHardwareTail() {
        return true;
    }

    @Override
    public String key() {
        return REWIND_KEY;
    }

    @Override
    public Snapshot capture() {
        FreshLevelRuntimeArtRequest request = deferredFreshLevelRuntimeArt;
        return new Snapshot(
                request == null ? null : request.rom(),
                request == null ? -1 : request.primarySource(),
                request == null ? -1 : request.secondarySource(),
                moduleQueue.captureFreshLevelHandoffHandles());
    }

    @Override
    public void restore(Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        moduleQueue.restoreFreshLevelHandoffHandles(
                snapshot.freshLevelHandoffHandles());
        if (snapshot.deferredPrimarySource() < 0) {
            if (snapshot.deferredRom() != null
                    || snapshot.deferredSecondarySource() >= 0) {
                throw new IllegalStateException(
                        "invalid empty fresh-level runtime-art snapshot");
            }
            deferredFreshLevelRuntimeArt = null;
            deferredFreshLevelPublicationBlockedReported = false;
            return;
        }
        if (snapshot.deferredRom() == null
                || snapshot.deferredSecondarySource() < 0) {
            throw new IllegalStateException(
                    "invalid deferred fresh-level runtime-art snapshot");
        }
        deferredFreshLevelRuntimeArt = new FreshLevelRuntimeArtRequest(
                snapshot.deferredRom(), snapshot.deferredPrimarySource(),
                snapshot.deferredSecondarySource());
        deferredFreshLevelPublicationBlockedReported = false;
    }

    @Override
    public List<QueueDiagnosticSnapshot> captureQueueDiagnostics() {
        return List.of(
                directQueue.captureDiagnostics(List.of()),
                moduleQueue.captureDiagnostics(List.of()));
    }

    @Override
    public void registerRewindAdapters(RewindRegistry registry) {
        registry.register(directQueue);
        registry.register(this);
    }

    @Override
    public void deregisterRewindAdapters(RewindRegistry registry) {
        registry.deregister(REWIND_KEY);
        registry.deregister(S3kKosDecompressionQueue.REWIND_KEY);
    }

    @Override
    public void resetForMissingSnapshot() {
        directQueue.resetForMissingSnapshot();
        moduleQueue.resetForMissingSnapshot();
        deferredFreshLevelRuntimeArt = null;
        deferredFreshLevelPublicationBlockedReported = false;
    }
}
