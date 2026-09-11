package com.openggf.trace.replay;

import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameStep;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.level.LevelManager;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;

import java.util.Objects;
import java.util.function.Consumer;

/** Production closure for one trace row whose ordinary gameplay body is held. */
public final class TraceSuppressedRowClosure {

    private TraceSuppressedRowClosure() {
    }

    /** Runs title-card work not owned by a represented suppressed closure. */
    public static void executeUnownedTitleCardWork(
            boolean rowSuppressed,
            TitleCardProvider provider,
            Runnable startPendingInLevelTitleCard,
            Consumer<Boolean> applyInLevelTitleCardControlLock) {
        executeUnownedTitleCardWork(rowSuppressed, false, provider,
                startPendingInLevelTitleCard, applyInLevelTitleCardControlLock);
    }

    /**
     * Runs title-card work not owned by a represented closure on this row.
     *
     * <p>{@code overlayDispatchedByRepresentedOwner} is the caller's statement
     * that it already dispatches this provider itself during the same
     * iteration -- the normal (fresh-level) title owner is such a caller. The
     * title card is an ordinary object: {@code Obj_TitleCard}
     * (docs/skdisasm/sonic3k.asm:62095) and S2 {@code Obj34}
     * (docs/s2disasm/s2.asm:27307) are each reached once per object scan, so
     * a second dispatch inside one represented iteration has no ROM
     * counterpart.
     * This overload therefore stands down entirely when the row's overlay
     * already has a represented owner; the ownership question is asked of the
     * caller that owns the dispatch, not of the provider's identity.
     */
    public static void executeUnownedTitleCardWork(
            boolean rowSuppressed,
            boolean overlayDispatchedByRepresentedOwner,
            TitleCardProvider provider,
            Runnable startPendingInLevelTitleCard,
            Consumer<Boolean> applyInLevelTitleCardControlLock) {
        // A suppressed row is a ROM lag frame: V_int ran, but the loop that
        // owns the title card did not complete an iteration, so its object
        // scan never dispatched. That holds for every title-card phase in all
        // three games, not just an in-level tail:
        //
        //   * the title card is an ordinary object in each game -- S1
        //     id_TitleCard (docs/s1disasm/sonic.asm:2811), S2 Obj34
        //     (docs/s2disasm/s2.asm:27307), S3K Obj_TitleCard
        //     (docs/skdisasm/sonic3k.asm:62095) -- so it advances only from an
        //     object scan;
        //   * both loops that can be running reach that scan once per completed
        //     iteration and never otherwise: the fresh-level title loops
        //     (sonic.asm:2814-2821 ExecuteObjects, s2.asm:4914-4924 RunObjects,
        //     sonic3k.asm:7737-7747 Process_Sprites) and the main level loops
        //     (s2.asm:5088-5105, sonic3k.asm:7884-7898);
        //   * a lag frame reaches neither. The V-int handler zeroes its own
        //     routine selector before dispatching (sonic.asm:674-675,
        //     s2.asm:500-501, sonic3k.asm:535-536), so a second V-int inside one
        //     unfinished iteration takes routine 0 -- VBlank_Lag
        //     (sonic.asm:712), Vint_Lag (s2.asm:529) and VInt_0
        //     (sonic3k.asm:566) -- and no path out of any of the three reaches
        //     an object scan.
        //
        // So no provider's overlay may advance from this path on a suppressed
        // row. The one owner that genuinely does advance while the level
        // counter is held is dispatched by the represented closure instead, so
        // standing down here loses nothing.
        boolean deferOverlay =
                overlayDispatchedByRepresentedOwner || rowSuppressed;
        if (provider != null && provider.isOverlayActive() && !deferOverlay) {
            provider.update();
            if (provider.ownsInLevelPlayerControlLock()) {
                applyInLevelTitleCardControlLock.accept(
                        provider.shouldLockPlayerControlForInLevelOverlay());
            }
        }
        if (!rowSuppressed) {
            startPendingInLevelTitleCard.run();
        }
    }

    /** Executes the represented closure, rejecting an impossible multi-close row. */
    public static void executeRepresented(
            int closureCount,
            LevelFrameContext context,
            PlcLifecycleFrame lifecycleFrame,
            LevelManager levelManager,
            Runnable startPendingInLevelTitleCard,
            Consumer<Boolean> applyInLevelTitleCardControlLock) {
        if (closureCount < 0 || closureCount > 1) {
            throw new IllegalArgumentException(
                    "one stored trace row cannot own " + closureCount
                            + " VBlank closures");
        }
        if (closureCount == 1) {
            execute(context, lifecycleFrame, levelManager,
                    startPendingInLevelTitleCard,
                    applyInLevelTitleCardControlLock);
        }
    }

    /**
     * Executes the one hardware/VBlank closure represented by a suppressed
     * stored row. Expected trace state never crosses this boundary.
     */
    public static void execute(
            LevelFrameContext context,
            PlcLifecycleFrame lifecycleFrame,
            LevelManager levelManager,
            Runnable startPendingInLevelTitleCard,
            Consumer<Boolean> applyInLevelTitleCardControlLock) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(lifecycleFrame, "lifecycleFrame");
        Objects.requireNonNull(levelManager, "levelManager");
        Objects.requireNonNull(startPendingInLevelTitleCard,
                "startPendingInLevelTitleCard");
        Objects.requireNonNull(applyInLevelTitleCardControlLock,
                "applyInLevelTitleCardControlLock");

        TitleCardProvider titleCardProvider =
                context.gameModule().getTitleCardProvider();
        LevelEventProvider levelEvents = context.levelEventProvider();
        if (titleCardProvider != null
                && titleCardProvider.ownsOmittedFreshLevelPresentation()) {
            // An omitted presentation still has the owner Level: installed at
            // sonic3k.asm:7735, and loc_62CC (7736-7748) dispatches it once per
            // V-int. The boundary set of that loop is the same triple this row
            // already services -- Process_Kos_Queue ahead of Wait_VSync, then
            // Process_Kos_Module_Queue -- so the owner's dispatch goes in the
            // object-scan position and nothing else about the row changes.
            LevelFrameStep.executeHardwareTimedObjectScan(
                    context,
                    lifecycleFrame,
                    PlcLifecyclePhase.LEVEL_TITLE_CARD,
                    titleCardProvider::updateOmittedFreshLevelOwner);
            if (levelEvents != null) {
                levelEvents.advanceVblankOnlyState();
            }
            levelManager.getObjectManager().advanceVblaCounter();
            return;
        }
        if (titleCardProvider != null
                && titleCardProvider.advancesOnHeldLevelCounter()) {
            LevelFrameStep.executeHardwareTimedObjectScan(
                    context,
                    lifecycleFrame,
                    PlcLifecyclePhase.LEVEL_TITLE_CARD,
                    () -> {
                        titleCardProvider.update();
                        if (titleCardProvider
                                .ownsRetainedResultsHeldLevelCounter()
                                && levelEvents != null) {
                            levelEvents.updateFixedInLevelObjects();
                        }
                        if (titleCardProvider
                                .ownsRetainedResultsHeldLevelCounter()
                                && context.gameModule().getObjectArtProvider() != null) {
                            // Retained Obj_TitleCardWait2 reaches LoadEnemyArt
                            // during this held object dispatch. Pump the
                            // production ROM-backed provider before the loop
                            // tail services the admitted queue work.
                            context.gameModule().getObjectArtProvider()
                                    .processRuntimeArtQueue();
                        }
                    });
            if (titleCardProvider.ownsInLevelPlayerControlLock()) {
                applyInLevelTitleCardControlLock.accept(
                        titleCardProvider
                                .shouldLockPlayerControlForInLevelOverlay());
            }
        } else {
            LevelFrameStep.serviceVBlankOnly(
                    context, lifecycleFrame, PlcLifecyclePhase.LAG);
            if (context.runtimeArtCoordinator().ownsHeldLevelCounterHardwareTail()) {
                LevelFrameStep.serviceHardwarePostObjectsOnly(context);
                LevelFrameStep.serviceHardwarePreMainLoopOnly(context);
            } else if (context.hardwareTimingBoundaryObserver()
                    instanceof TraceHardwareTimingBoundaryObserver replayObserver) {
                if (replayObserver.applySuppressedRowCompletion()) {
                    context.runtimeArtCoordinator().afterTimingService(
                            HardwareServiceBoundary.PRE_MAIN_LOOP);
                }
            }
            // The absorbed lag V-blank is behind this row, so the iteration that
            // absorbed it reaches its own loop tail here, before the row is
            // sampled (docs/s1disasm/sonic.asm:709 VBlank_Lag, :3032 RunPLC).
            // The tail arms only what its own readiness allows.
            context.runtimeArtCoordinator().runHeldIterationLoopTail();
        }

        if (levelManager.hasPendingInLevelTitleCardHeldCounterDispatch()) {
            startPendingInLevelTitleCard.run();
        }
        if (levelEvents != null) {
            levelEvents.advanceVblankOnlyState();
        }
        levelManager.getObjectManager().advanceVblaCounter();
    }
}
