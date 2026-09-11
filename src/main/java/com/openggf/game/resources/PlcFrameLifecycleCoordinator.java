package com.openggf.game.resources;

import com.openggf.game.GameModule;
import com.openggf.game.rules.GameRules;
import com.openggf.game.rules.DynamicArtDmaServiceModel;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Assigns exactly one semantic PLC owner to each represented VBlank.
 */
public final class PlcFrameLifecycleCoordinator implements NativeFadeLifecycle {
    private final Supplier<PlcLifecycleService> serviceSupplier;
    private final DynamicArtLifecycleService dynamicArtLifecycle;
    private final DynamicArtDmaServiceModel dynamicArtDmaService;
    private NativeBlockingFadeImpl activeFade;
    private PlcLifecycleFrame activeFrame;
    private boolean comparisonSegmentsExternallyManaged;
    private boolean externalComparisonSegmentOpenDeferred;
    private boolean representedIterationWithoutVblank;
    private boolean vblankOverrunCarry;
    private boolean nextVblankServicesDmaQueue;
    private boolean representedIterationDefersLoopTailPreparation;
    private PlcLifecyclePhase heldLoopTailPreparation;

    public PlcFrameLifecycleCoordinator(GameModule module) {
        this(() -> module.getGameService(PlcLifecycleService.class), null,
                resolveDynamicArtDmaService(module));
    }

    public PlcFrameLifecycleCoordinator(
            GameModule module,
            DynamicArtLifecycleService dynamicArtLifecycle) {
        this(() -> module.getGameService(PlcLifecycleService.class),
                dynamicArtLifecycle, resolveDynamicArtDmaService(module));
    }

    public PlcFrameLifecycleCoordinator(
            GameModule module,
            DynamicArtLifecycleService dynamicArtLifecycle,
            DynamicArtDmaServiceModel dynamicArtDmaService) {
        this(() -> module.getGameService(PlcLifecycleService.class),
                dynamicArtLifecycle, dynamicArtDmaService);
    }

    public PlcFrameLifecycleCoordinator(PlcLifecycleService service) {
        this(() -> service, null, DynamicArtDmaServiceModel.EVERY_CLAIM);
    }

    public PlcFrameLifecycleCoordinator(
            PlcLifecycleService service,
            DynamicArtLifecycleService dynamicArtLifecycle) {
        this(() -> service, dynamicArtLifecycle,
                DynamicArtDmaServiceModel.EVERY_CLAIM);
    }

    public PlcFrameLifecycleCoordinator(
            PlcLifecycleService service,
            DynamicArtLifecycleService dynamicArtLifecycle,
            DynamicArtDmaServiceModel dynamicArtDmaService) {
        this(() -> service, dynamicArtLifecycle, dynamicArtDmaService);
    }

    private PlcFrameLifecycleCoordinator(
            Supplier<PlcLifecycleService> serviceSupplier,
            DynamicArtLifecycleService dynamicArtLifecycle,
            DynamicArtDmaServiceModel dynamicArtDmaService) {
        this.serviceSupplier = Objects.requireNonNull(serviceSupplier, "serviceSupplier");
        this.dynamicArtLifecycle = dynamicArtLifecycle;
        this.dynamicArtDmaService = Objects.requireNonNull(
                dynamicArtDmaService, "dynamicArtDmaService");
    }

    private static DynamicArtDmaServiceModel resolveDynamicArtDmaService(
            GameModule module) {
        GameRules rules = Objects.requireNonNull(module, "module").getRules();
        return rules != null
                ? rules.dynamicArtDmaService()
                : DynamicArtDmaServiceModel.EVERY_CLAIM;
    }

    /**
     * Latches the represented V-blank's PLC token, pre-assigning it to an
     * active native blocking fade unless the V-blank about to run is a declared
     * DMA-queue-only handler.
     *
     * <p>A DMA-queue-only V-blank is a lag row by construction. The ROM arms
     * {@code Vint_CtrlDMA} (docs/s2disasm/s2.asm:6665) and that handler is
     * nothing but {@code stopZ80 / jsr ProcessDMAQueue / startZ80 / rts}
     * (s2.asm:998-1002); it never reaches {@code ReadJoypads}, which only the
     * ordinary per-mode handlers run ({@code Vint_Level}, s2.asm:701, reached
     * from s2.asm:698). The represented row it closes therefore carries no
     * joypad poll and is a lag row for publication purposes.
     *
     * <p>It is equally not an iteration of the blocking fade around it: the ROM
     * waits on this V-int <em>before</em> entering {@code Pal_FadeFromWhite}
     * (s2.asm:6665-6672, routine at s2.asm:3460), whose own iterations arm
     * {@code VintID_Fade} instead (s2.asm:3477). So the fade must not own this
     * represented V-blank, and the fade's per-iteration {@code RunPLC_RAM}
     * (s2.asm:3480) does not run on it either.
     *
     * <p>The token is left unclaimed for the row's own owner: a represented lag
     * closure claims {@link PlcLifecyclePhase#LAG} and defers its dynamic-art
     * publication to the next boundary, exactly as any other lag row does.
     * Whichever phase claims, the queue is still serviced, because
     * {@link PlcLifecycleFrame#claim} consumes the same one-shot declaration --
     * both facts are true of {@code Vint_CtrlDMA} at once: it drains the DMA
     * queue, and it is a lag row. Nothing here keys on a frame index, row,
     * route, zone or game name.
     */
    public PlcLifecycleFrame latchBeforeFadeUpdate() {
        if (activeFrame != null && !activeFrame.finished) {
            throw new IllegalStateException("previous PLC lifecycle frame is still active");
        }
        activeFrame = new PlcLifecycleFrame(serviceSupplier.get());
        if (activeFade != null && !activeFade.closed && !nextVblankServicesDmaQueue) {
            activeFrame.claim(PlcLifecyclePhase.PALETTE_FADE);
        }
        return activeFrame;
    }

    /**
     * Runs one represented logical iteration through the shared live/headless
     * lifecycle. Fade advancement deliberately precedes mode work so a
     * completion callback cannot transfer the already-latched VBlank token to
     * the incoming mode.
     */
    public <T> T runLogicalIteration(
            Runnable fadeUpdate, Function<PlcLifecycleFrame, T> iteration) {
        return runLogicalIteration(frame -> { }, fadeUpdate, iteration);
    }

    /**
     * Runs the physical-VBlank dispatch before fade callbacks may arm work.
     * Work created by a completion callback therefore cannot consume the
     * boundary that completed the fade.
     */
    public <T> T runLogicalIteration(
            Consumer<PlcLifecycleFrame> beforeFadeVBlank,
            Runnable fadeUpdate, Function<PlcLifecycleFrame, T> iteration) {
        Objects.requireNonNull(beforeFadeVBlank, "beforeFadeVBlank");
        Objects.requireNonNull(fadeUpdate, "fadeUpdate");
        Objects.requireNonNull(iteration, "iteration");
        PlcLifecycleFrame frame = latchBeforeFadeUpdate();
        Throwable primaryFailure = null;
        try {
            beforeFadeVBlank.accept(frame);
            fadeUpdate.run();
            if (frame.isOwnedBy(PlcLifecyclePhase.PALETTE_FADE)) {
                frame.prepareAfterLoop(PlcLifecyclePhase.PALETTE_FADE);
            }
            return iteration.apply(frame);
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                // A live rewind can replace the unclaimed outer frame with a
                // replay frame while the iteration callback is running. The
                // outer wrapper still owns the Java finally, but its token was
                // deliberately finished before the replay token was latched.
                if (!frame.finished) {
                    frame.finish();
                }
            } catch (RuntimeException | Error validationFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(validationFailure);
                } else {
                    throw validationFailure;
                }
            }
        }
    }

    /**
     * Runs one logical iteration while an outer live iteration is still on the
     * Java call stack. Live rewind is checked before the outer frame claims a
     * phase, so that unclaimed token is only a placeholder for the visual
     * frame; replay must own the represented logical VBlank instead. A claimed
     * frame is never replaceable and retains the normal lifecycle guard.
     *
     * <p>The method is also used by visual trace rewind, whose replay callback
     * has the same nesting shape. It is intentionally separate from
     * {@link #runLogicalIteration(Runnable, Function)} so ordinary lifecycle
     * callers cannot silently mask a real phase-ownership error.</p>
     */
    public <T> T runReplayedLogicalIteration(
            Runnable fadeUpdate, Function<PlcLifecycleFrame, T> iteration) {
        Objects.requireNonNull(fadeUpdate, "fadeUpdate");
        Objects.requireNonNull(iteration, "iteration");
        if (activeFrame != null && !activeFrame.finished) {
            if (activeFrame.owner != null || activeFrame.gameVblankDispatched) {
                throw new IllegalStateException(
                        "a replayed PLC lifecycle frame cannot replace a consumed frame");
            }
            // The outer runLogicalIteration() finally remains responsible for
            // its token, so it observes the finished state and does not finish
            // the placeholder a second time after replay returns.
            activeFrame.finish();
        }
        return runLogicalIteration(fadeUpdate, iteration);
    }

    /**
     * Runs a represented lag closure without advancing or assigning ownership
     * to an active native blocking fade. A ROM lag handler leaves that fade's
     * mode-specific PLC work paused until the next ordinary VBlank.
     */
    public <T> T runSuppressedLagIteration(
            Function<PlcLifecycleFrame, T> iteration) {
        Objects.requireNonNull(iteration, "iteration");
        if (activeFrame != null && !activeFrame.finished) {
            throw new IllegalStateException(
                    "previous PLC lifecycle frame is still active");
        }
        PlcLifecycleFrame frame = new PlcLifecycleFrame(serviceSupplier.get());
        activeFrame = frame;
        Throwable primaryFailure = null;
        try {
            T result = iteration.apply(frame);
            if (!frame.isOwnedBy(PlcLifecyclePhase.LAG)) {
                throw new IllegalStateException(
                        "suppressed lag iteration did not claim LAG");
            }
            return result;
        } catch (RuntimeException | Error failure) {
            primaryFailure = failure;
            throw failure;
        } finally {
            try {
                if (!frame.finished) {
                    frame.finish();
                }
            } catch (RuntimeException | Error validationFailure) {
                if (primaryFailure != null) {
                    primaryFailure.addSuppressed(validationFailure);
                } else {
                    throw validationFailure;
                }
            }
        }
    }

    @Override
    public NativeBlockingFade beginNativeBlockingFade() {
        if (activeFade != null && !activeFade.closed) {
            throw new IllegalStateException("a native blocking fade is already active");
        }
        activeFade = new NativeBlockingFadeImpl();
        return activeFade;
    }

    /**
     * Declares that the iteration about to run is represented without any
     * V-blank having elapsed for it. Live play never sets this: every live
     * iteration is closed by a real V-blank. It is a hardware-timing
     * classification only -- no gameplay value, queue readiness or transfer
     * identity crosses this call.
     *
     * <p>ROM basis: {@code Vint_runcount} is bumped once per V-blank at
     * {@code VintRet} (docs/s2disasm/s2.asm:507-508) regardless of which
     * handler ran, and {@code ProcessDMAQueue} (s2.asm:1770) is reached only
     * from the real per-mode V-int handlers (the {@code Vint_Level} call at
     * s2.asm:781, reached from s2.asm:698), never from {@code Vint_Lag}
     * (s2.asm:529-580) -- S1's {@code VBlank_Lag}
     * (docs/s1disasm/sonic.asm:709-730) has the same shape.
     *
     * <p>Crucially the real handler calls {@code ProcessDMAQueue} <em>before</em>
     * {@code VintRet} bumps the counter, so a sample taken with neither counter
     * advanced still sits after the queue was drained: this iteration's own
     * closure IS a dynamic-art publication boundary. What is still mid-flight is
     * the <em>gameplay</em> iteration, so only physics stays suppressed, and it
     * is the following iteration -- the one that overran its V-blank and
     * consumes two {@code Vint_runcount} ticks -- that publishes on a later
     * boundary instead of its own.
     */
    public void markRepresentedIterationWithoutVblank() {
        representedIterationWithoutVblank = true;
    }

    /**
     * Declares that the V-blank opening the next represented iteration runs a
     * DMA-queue-only V-int instead of the mode's ordinary handler, so it
     * services the dynamic-art queue whatever phase that row carries.
     *
     * <p>ROM basis: the S2 special stage's startup sequence queues the player
     * objects' art from its one-off {@code RunObjects} pass
     * (docs/s2disasm/s2.asm:6662) and then explicitly asks for
     * {@code VintID_CtrlDMA} before waiting (s2.asm:6665-6668).
     * {@code Vint_CtrlDMA} is nothing but {@code ProcessDMAQueue}
     * (s2.asm:998-1001, routine at s2.asm:1770), so that V-blank retires the
     * queued transfers even though the main loop it belongs to
     * ({@code Pal_FadeFromWhite}, s2.asm:3460-3482) never polls the joypad and
     * therefore reads as a lag frame.
     *
     * <p>This carries no gameplay value and no transfer identity -- it only
     * classifies which V-blank ran a real DMA handler. It is a one-shot
     * consumed by the very next claim.
     */
    public void markNextVblankServicesDmaQueue() {
        nextVblankServicesDmaQueue = true;
    }

    /**
     * Declares that the represented iteration about to run is still in flight
     * when its row is sampled, so its loop tail belongs to a later closure.
     *
     * <p>ROM basis: the loop-tail PLC preparation ({@code RunPLC},
     * docs/s1disasm/sonic.asm:3032) sits after every expensive call in
     * {@code Level_MainLoop} -- {@code ExecuteObjects} (3010),
     * {@code DeformLayers} (3025), {@code BuildSprites} (3028),
     * {@code ObjPosLoad} (3029), {@code PaletteCycle} (3031). An iteration that
     * has not reached the loop top's {@code move.b #id_VBlank_Levels,
     * (v_vblank_routine).w} (3000) by the next V-blank takes {@code VBlank_Lag}
     * (sonic.asm:709) instead of the level handler, and has essentially always
     * not reached {@code RunPLC} either: only {@code OscillateNumDo},
     * {@code SynchroAnimate} and {@code SignpostArtLoad} separate 3032 from the
     * re-arm. So the ROM's own {@code RunPLC} for that iteration executes
     * during the closure that consumes the lag V-blank, and the
     * still-decompressing head is what the previous row's sample observes.
     *
     * <p>This only moves <em>when</em> the engine's own already-submitted queue
     * head is armed between two represented closures of one ROM iteration. It
     * carries no gameplay value, no queue identity, and creates no work.
     */
    public void markRepresentedIterationDefersLoopTailPreparation() {
        representedIterationDefersLoopTailPreparation = true;
    }

    public void reset() {
        if (externalComparisonSegmentOpenDeferred
                && dynamicArtLifecycle != null
                && dynamicArtLifecycle.isComparisonSegmentReserved()) {
            dynamicArtLifecycle.cancelReservedComparisonSegment();
        }
        activeFrame = null;
        representedIterationWithoutVblank = false;
        vblankOverrunCarry = false;
        nextVblankServicesDmaQueue = false;
        representedIterationDefersLoopTailPreparation = false;
        heldLoopTailPreparation = null;
        externalComparisonSegmentOpenDeferred = false;
        if (activeFade != null) {
            activeFade.closed = true;
            activeFade = null;
        }
    }

    /**
     * Run replay uses explicit structural segment boundaries. This switch does
     * not accept expected events or any gameplay value.
     */
    public void setComparisonSegmentsExternallyManaged(boolean externallyManaged) {
        if (!externallyManaged && externalComparisonSegmentOpenDeferred
                && dynamicArtLifecycle != null
                && dynamicArtLifecycle.isComparisonSegmentReserved()) {
            dynamicArtLifecycle.cancelReservedComparisonSegment();
        }
        comparisonSegmentsExternallyManaged = externallyManaged;
        if (!externallyManaged) {
            externalComparisonSegmentOpenDeferred = false;
        }
    }

    /**
     * Transfers a completed automatically managed diagnostics window to an
     * external run-segment owner. The caller opens the new external window
     * after this operation returns.
     */
    public void acquireExternalComparisonSegmentOwnership() {
        acquireExternalComparisonSegmentOwnership(false);
    }

    /**
     * Transfers comparison ownership now but opens the first external window
     * only after the next production service decision. This matches automatic
     * ownership's service-before-open ordering while still guaranteeing that
     * the same logical iteration publishes external row zero.
     */
    public void acquireExternalComparisonSegmentOwnershipAfterNextService() {
        acquireExternalComparisonSegmentOwnership(true);
    }

    private void acquireExternalComparisonSegmentOwnership(
            boolean deferWindowUntilAfterService) {
        if (comparisonSegmentsExternallyManaged) {
            throw new IllegalStateException(
                    "dynamic-art comparison segments are already externally managed");
        }
        if (dynamicArtLifecycle != null
                && dynamicArtLifecycle.isComparisonSegmentOpen()
                && !dynamicArtLifecycle.latestSnapshot().published()) {
            throw new IllegalStateException(
                    "automatic dynamic-art comparison segment has not published a row");
        }
        comparisonSegmentsExternallyManaged = true;
        externalComparisonSegmentOpenDeferred = deferWindowUntilAfterService;
        try {
            if (dynamicArtLifecycle != null
                    && dynamicArtLifecycle.isComparisonSegmentOpen()) {
                dynamicArtLifecycle.closeComparisonSegment();
            }
            if (deferWindowUntilAfterService
                    && dynamicArtLifecycle != null) {
                dynamicArtLifecycle.reserveComparisonSegment();
            }
        } catch (RuntimeException | Error failure) {
            comparisonSegmentsExternallyManaged = false;
            externalComparisonSegmentOpenDeferred = false;
            throw failure;
        }
    }

    /**
     * Closes the current externally managed comparison window, or cancels its
     * not-yet-serviced initial open. External ownership itself remains active
     * so a run gap cannot fall back to automatic windows.
     */
    public void closeExternallyManagedComparisonSegment() {
        if (!comparisonSegmentsExternallyManaged) {
            throw new IllegalStateException(
                    "dynamic-art comparison segments are not externally managed");
        }
        if (externalComparisonSegmentOpenDeferred) {
            externalComparisonSegmentOpenDeferred = false;
            if (dynamicArtLifecycle != null
                    && dynamicArtLifecycle.isComparisonSegmentReserved()) {
                dynamicArtLifecycle.cancelReservedComparisonSegment();
            }
            return;
        }
        if (dynamicArtLifecycle != null
                && dynamicArtLifecycle.isComparisonSegmentOpen()) {
            // The window can already have closed itself on the ROM iteration
            // that wrote the special-stage game mode (Obj79_Star,
            // docs/s2disasm/s2.asm:44877); that iteration still completes, so
            // this structural close arrives after it.
            dynamicArtLifecycle.closeComparisonSegment();
        }
    }

    public final class PlcLifecycleFrame {
        private final PlcLifecycleService service;
        private PlcLifecyclePhase owner;
        private boolean prepared;
        private boolean finished;
        private boolean consumedHeldLoopTailPreparation;
        private boolean gameVblankDispatched;

        private PlcLifecycleFrame(PlcLifecycleService service) {
            this.service = service;
        }

        /**
         * Assigns this represented V-blank's single semantic PLC owner.
         *
         * <p>Returns {@code false} when the token is already owned. That is a
         * real precedence rule, not a no-op: an active native blocking fade
         * pre-claims the token in {@link #latchBeforeFadeUpdate()} and keeps it
         * for the whole iteration, including across its completion callback. A
         * losing claim's <em>publication</em> semantics are therefore dropped
         * with it -- notably a {@link PlcLifecyclePhase#LAG} row's deferral of
         * its dynamic-art edges to the following boundary. Callers that own a
         * row's phase outright must not ignore the result; see
         * {@code LevelFrameStep.serviceVBlankOnly}.
         *
         * @return true when this call assigned the owner
         */
        public boolean claim(PlcLifecyclePhase phase) {
            requireOpen();
            Objects.requireNonNull(phase, "phase");
            if (owner != null) {
                return false;
            }
            owner = phase;
            if (service != null) {
                service.serviceVBlank(phase);
            }
            // The loop tail held by an in-flight iteration runs on the closure
            // that consumed its lag V-blank, after that V-blank's own service
            // -- which for a lag handler decompresses nothing. A closure that
            // is itself still mid-iteration keeps holding it.
            if (heldLoopTailPreparation != null
                    && !representedIterationDefersLoopTailPreparation) {
                PlcLifecyclePhase held = heldLoopTailPreparation;
                heldLoopTailPreparation = null;
                consumedHeldLoopTailPreparation = true;
                if (service != null) {
                    service.prepareAfterLoop(held);
                }
            }
            // The DMA-queue-only declaration is a one-shot consumed by the very
            // next claim whether or not a run comparison is active, so it can
            // never leak into a later represented V-blank.
            boolean declaredDmaVblank = nextVblankServicesDmaQueue;
            nextVblankServicesDmaQueue = false;
            if (dynamicArtLifecycle != null
                    && dynamicArtLifecycle.isRunActive()) {
                // A represented iteration whose V-blank had not yet bumped
                // Vint_runcount still ran a real per-mode handler, and that
                // handler calls ProcessDMAQueue (docs/s2disasm/s2.asm:781,
                // routine at s2.asm:1770) long before VintRet increments the
                // counter (s2.asm:507-508). So the DMA queue was serviced for
                // the row being closed even though the phase handed here had to
                // be LAG to keep gameplay suppressed. A genuine Vint_Lag row
                // (Vint_routine still 0, s2.asm:483-484) never reaches
                // ProcessDMAQueue and keeps the per-game phase policy's answer.
                if (dynamicArtDmaService.services(phase)
                        || representedIterationWithoutVblank
                        || declaredDmaVblank) {
                    dynamicArtLifecycle.serviceProductionVBlank();
                }
                if (externalComparisonSegmentOpenDeferred) {
                    dynamicArtLifecycle.activateReservedComparisonSegment();
                    externalComparisonSegmentOpenDeferred = false;
                } else if (!comparisonSegmentsExternallyManaged
                        && !dynamicArtLifecycle.isComparisonSegmentOpen()) {
                    dynamicArtLifecycle.openComparisonSegment();
                }
            }
            return true;
        }

        public void prepareAfterLoop(PlcLifecyclePhase phase) {
            requireOpen();
            if (owner != phase) {
                throw new IllegalStateException("PLC lifecycle frame is not owned by " + phase);
            }
            if (prepared) {
                throw new IllegalStateException("PLC lifecycle frame was already prepared");
            }
            // A service whose loop-tail arm is itself submitted hardware work
            // decides its own visibility from that job's readiness, so the
            // row-shape hold does not also apply to it. Every other owner --
            // including a runtime-art coordinator with no PLC service at all --
            // still holds the tail for a later closure.
            boolean timedArm = service != null && service.ownsTimedLoopTailArm();
            boolean held = representedIterationDefersLoopTailPreparation && !timedArm;
            if (held) {
                heldLoopTailPreparation = phase;
            }
            if (service != null && service.hasPreparationBoundary(phase)) {
                if (!held) {
                    service.prepareAfterLoop(phase);
                }
                prepared = true;
            }
        }

        public boolean isOwnedBy(PlcLifecyclePhase phase) {
            return owner == phase;
        }

        /** Runs game-owned VBlank work once, independently of PLC ownership. */
        public boolean dispatchGameVBlank(Runnable dispatch) {
            requireOpen();
            Objects.requireNonNull(dispatch, "dispatch");
            if (gameVblankDispatched) {
                return false;
            }
            gameVblankDispatched = true;
            dispatch.run();
            return true;
        }

        /** Whether this represented iteration's loop-tail work belongs to a later closure. */
        public boolean defersLoopTailPreparation() {
            return representedIterationDefersLoopTailPreparation;
        }

        /** Whether this closure consumed a loop tail held by the preceding row. */
        public boolean consumedHeldLoopTailPreparation() {
            return consumedHeldLoopTailPreparation;
        }

        public void finish() {
            requireOpen();
            if (service != null && owner != null
                    && service.hasPreparationBoundary(owner) && !prepared) {
                throw new IllegalStateException("missing PLC preparation for " + owner);
            }
            // A dynamic-art edge publishes on the V-blank that closes the
            // iteration which produced it. Two shapes reach no such boundary:
            //   * a lag V-blank -- V_Int branched to Vint_Lag because
            //     Vint_routine was still 0 (docs/s2disasm/s2.asm:483-484, 529;
            //     docs/s1disasm/sonic.asm:709 VBlank_Lag), and Vint_Lag never
            //     calls ProcessDMAQueue (only the real handlers do:
            //     s2.asm:781, 899, 1000, 1046, 1083, 1138); and
            //   * the iteration that overran its V-blank. Its predecessor was
            //     sampled mid-V-int -- after ProcessDMAQueue (s2.asm:781) but
            //     before VintRet bumped Vint_runcount (s2.asm:507-508) -- so
            //     that predecessor did reach a publication boundary and must
            //     not be carried, while this iteration is
            //     still mid-flight at the boundary and its queue-add
            //     (s2.asm:1705 "to be issued the next time ProcessDMAQueue is
            //     called") lands after it. The carry is a one-shot consumed by
            //     the very next closure, never a frame number or route.
            boolean withoutVblank = representedIterationWithoutVblank;
            representedIterationWithoutVblank = false;
            representedIterationDefersLoopTailPreparation = false;
            // A row sampled mid-V-int is never carried: its real handler already
            // ran ProcessDMAQueue. That holds however many such rows occur in a
            // row, so the guard is on the row's own shape, not on a one-shot.
            boolean carried = (vblankOverrunCarry
                    || owner == PlcLifecyclePhase.LAG) && !withoutVblank;
            vblankOverrunCarry = withoutVblank;
            if (dynamicArtLifecycle != null && owner != null
                    && dynamicArtLifecycle.isRunActive()) {
                dynamicArtLifecycle.finishProductionIteration(carried);
            }
            finished = true;
        }

        private void requireOpen() {
            if (finished) {
                throw new IllegalStateException("PLC lifecycle frame is finished");
            }
        }
    }

    private final class NativeBlockingFadeImpl implements NativeBlockingFade {
        private boolean closed;

        @Override
        public Runnable wrapCompletion(Runnable completion) {
            Runnable callback = completion != null ? completion : () -> { };
            return () -> {
                close();
                callback.run();
            };
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                if (activeFade == this) {
                    activeFade = null;
                }
            }
        }
    }
}
