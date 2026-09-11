package com.openggf.tools;

import com.openggf.LevelFrameContext;
import com.openggf.LevelFrameResult;
import com.openggf.LevelFrameStep;
import com.openggf.FrameAdmission;
import com.openggf.InputBindingFactory;
import com.openggf.control.InputActionMasks;
import com.openggf.control.InputHandler;
import com.openggf.control.LogicalInputSnapshot;
import com.openggf.control.PlayerInputState;
import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.debug.playback.Bk2Movie;
import com.openggf.debug.playback.RecordedInputSnapshots;
import com.openggf.game.GameServices;
import com.openggf.game.InLevelTitleCardCoordinator;
import com.openggf.game.TitleCardProvider;
import com.openggf.game.session.SessionManager;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.trace.replay.TraceSuppressedRowClosure;
import com.openggf.trace.replay.runs.TraceRunReplayWalker.DynamicArtSegmentWindow;
import com.openggf.trace.timing.TraceHardwareTimingBoundaryObserver;

import java.io.IOException;

/**
 * Deterministic per-frame gameplay drive shared by headless trace tests and the
 * headless trace-capture tool. Delegates frame-level ordering to
 * {@link LevelFrameStep} and team updates to {@code SpriteManager.update(...)},
 * driving input through logical snapshots on an {@link InputHandler} so both
 * consumers use the exact same production update path.
 *
 * <p>This is the engine-side extraction of the BK2-recording playback +
 * {@code stepFrame} logic that lived in the test-only {@code HeadlessTestRunner};
 * the runner now delegates to this driver so capture and tests cannot drift.
 *
 * <p>All replay state (logical snapshots, BK2 cursor, frame counter) lives here
 * so the capture tool reproduces the trace-faithful trajectory the
 * trace-replay tests validate, rather than the live-playback {@code GameLoop}
 * path which omits P2/sidekick input plumbing and the explicit phase loop.
 */
public final class RecordingFrameDriver implements DynamicArtSegmentWindow {

    private final AbstractPlayableSprite sprite;
    private final LevelManager levelManager;
    private final InputHandler inputHandler = new InputHandler(InputBindingFactory.supplier(GameServices.configuration()));

    private int frameCounter = 0;
    private LogicalInputSnapshot previousDriverSnapshot = LogicalInputSnapshot.neutral();
    /** Last BK2 row the engine polled; the ROM press-edge baseline. See {@link #lastPolledBk2Input()}. */
    private Bk2FrameInput lastPolledBk2Input;
    private LevelFrameResult lastFrameResult = LevelFrameResult.GAMEPLAY_FRAME;
    private boolean lastFrameRanGameplay = true;
    private boolean pendingSeamlessBoundaryCompletion;
    private boolean normalTitleCardActive;
    private boolean freshLevelLoadedThisIteration;
    private boolean freshLevelTransitionLoopObserved;
    private TraceHardwareTimingBoundaryObserver hardwareTimingReplayObserver;

    /**
     * Wraps each canonical level-frame step. Defaults to the direct (unwrapped)
     * runner so tests and capture pay nothing; the offline benchmark harness
     * swaps in a profiling wrapper so a headless replay reports the same
     * per-step section names ({@code physics}, {@code objects}, {@code level},
     * {@code camera-scroll}, …) that {@code GameLoop} reports in a live run.
     */
    private LevelFrameStep.StepWrapper stepWrapper = LevelFrameStep.DIRECT_WRAPPER;

    // BK2 recording playback fields
    private Bk2Movie bk2Movie;
    private int bk2StartIndex;
    private int currentBk2Index;

    public RecordingFrameDriver(AbstractPlayableSprite sprite) {
        this.sprite = sprite;
        this.levelManager = GameServices.level();
    }

    public AbstractPlayableSprite getSprite() {
        return sprite;
    }

    /**
     * Installs the per-step wrapper used by {@link LevelFrameStep}. Passing
     * {@code null} restores the direct runner. The wrapper must not alter step
     * ordering or skip steps — it exists for cross-cutting observation only.
     */
    public void setStepWrapper(LevelFrameStep.StepWrapper wrapper) {
        this.stepWrapper = wrapper != null ? wrapper : LevelFrameStep.DIRECT_WRAPPER;
    }

    public int getFrameCounter() {
        return frameCounter;
    }

    public void installHardwareTimingReplayObserver(
            TraceHardwareTimingBoundaryObserver observer) {
        hardwareTimingReplayObserver = observer;
    }

    public void clearHardwareTimingReplayObserver() {
        hardwareTimingReplayObserver = null;
    }

    /** Selects structural run-replay segment ownership without accepting trace facts. */
    public void useExternalDynamicArtComparisonSegments() {
        SessionManager.getCurrentGameplayMode().plcFrameLifecycle()
                .setComparisonSegmentsExternallyManaged(true);
    }

    public void openDynamicArtComparisonSegment() {
        SessionManager.getCurrentGameplayMode().dynamicArtLifecycle()
                .openComparisonSegment();
    }

    public void closeDynamicArtComparisonSegment() {
        SessionManager.getCurrentGameplayMode().dynamicArtLifecycle()
                .closeComparisonSegment();
    }

    @Override
    public void open() {
        useExternalDynamicArtComparisonSegments();
        SessionManager.getCurrentGameplayMode().dynamicArtLifecycle()
                .serviceProductionVBlank();
        openDynamicArtComparisonSegment();
    }

    @Override
    public void close() {
        closeDynamicArtComparisonSegment();
    }

    public void beginTraceRow(int traceIndex, int rawFrame) {
        if (traceIndex < 0) {
            throw new IllegalArgumentException(
                    "traceIndex must be non-negative: " + traceIndex);
        }
        if (hardwareTimingReplayObserver != null) {
            hardwareTimingReplayObserver.beginRawFrame(rawFrame);
        }
    }

    public void enterHardwareTimingGap() {
        if (hardwareTimingReplayObserver != null) {
            hardwareTimingReplayObserver.enterUnrepresentedGap();
        }
    }

    // ---- core frame step ----

    public LevelFrameResult stepFrame(
            boolean up, boolean down, boolean left, boolean right, boolean jump) {
        return stepFrame(up, down, left, right, jump,
                /* p2Mask */ 0, /* p2Start */ false, /* p1Start */ false);
    }

    public LevelFrameResult stepFrame(
            boolean up, boolean down, boolean left, boolean right, boolean jump,
            boolean p1Start) {
        return stepFrame(up, down, left, right, jump,
                /* p2Mask */ 0, /* p2Start */ false, p1Start);
    }

    public LevelFrameResult stepFrame(
            boolean up, boolean down, boolean left, boolean right, boolean jump,
            int p2Mask, boolean p2Start) {
        return stepFrame(up, down, left, right, jump,
                p2Mask, p2Start, /* p1Start */ false);
    }

    public LevelFrameResult stepFrame(
            boolean up, boolean down, boolean left, boolean right, boolean jump,
            int p2Mask, boolean p2Start, boolean p1Start) {
        return stepFrame(driverSnapshot(
                up, down, left, right, jump, p2Mask, p2Start, p1Start));
    }

    private LevelFrameResult stepFrame(LogicalInputSnapshot snapshot) {
        return stepFrame(snapshot, () -> { });
    }

    private LevelFrameResult stepFrame(
            LogicalInputSnapshot snapshot, Runnable beforeGameplay) {
        return stepFrame(snapshot, beforeGameplay, false);
    }

    private LevelFrameResult stepFrame(
            LogicalInputSnapshot snapshot, Runnable beforeGameplay,
            boolean deferRecordedPauseEntry) {
        var gameplayMode = SessionManager.getCurrentGameplayMode();
        return gameplayMode.plcFrameLifecycle().runLogicalIteration(
                frame -> {
                    if (frame.isOwnedBy(PlcLifecyclePhase.PALETTE_FADE)) {
                        LevelFrameStep.dispatchGameVBlank(
                                LevelFrameContext.from(gameplayMode), frame);
                    }
                },
                gameplayMode.getFadeManager()::update,
                frame -> stepFrame(snapshot, beforeGameplay, frame, deferRecordedPauseEntry));
    }

    private LevelFrameResult stepFrame(
            LogicalInputSnapshot snapshot, Runnable beforeGameplay,
            PlcLifecycleFrame lifecycleFrame, boolean deferRecordedPauseEntry) {
        updateActiveTitleCardOverlay();
        if (applyPendingSeamlessTransition()) {
            pendingSeamlessBoundaryCompletion = true;
        }
        boolean loadedZoneActTransition = applyPendingZoneActTransition();
        if (loadedZoneActTransition) {
            // The native Level routine's cleared/playerless boundary is a
            // represented row of its own. Its title-card owner starts on the
            // following row, so do not admit normal gameplay or a VBlank
            // service for this boundary.
            lastFrameResult = LevelFrameResult.GAMEPLAY_FRAME;
            lastFrameRanGameplay = false;
            inputHandler.update();
            previousDriverSnapshot = snapshot;
            return lastFrameResult;
        }
        // A normal game-loop transition loads the destination from the fade
        // callback, then consumes its title-card request at the next level
        // iteration. Keep those boundaries separate in the recording driver:
        // the destination row is still the load boundary, while the title
        // owner first runs on the following iteration.
        if (!loadedZoneActTransition) {
            startPendingNormalTitleCardIfRequested();
        }
        if (normalTitleCardActive) {
            return stepNormalTitleCard(snapshot, lifecycleFrame);
        }
        // The native title owner publishes the fresh-level art at the first
        // ordinary LevelLoop boundary, but its loaded player slots remain
        // transition-held for that iteration. Release them on the following
        // ordinary iteration, after the native loop has crossed the boundary.
        if (levelManager.hasPendingFreshLevelTransitionBoundary()) {
            if (freshLevelTransitionLoopObserved) {
                levelManager.completeFreshLevelTransitionBoundary();
            } else {
                freshLevelTransitionLoopObserved = true;
                TitleCardProvider provider = GameServices.module().getTitleCardProvider();
                if (provider != null) {
                    provider.completeFreshLevelRuntimeArtHandoff();
                }
                levelManager.publishFreshLevelTransitionInitialBoundary();
                lastFrameResult = LevelFrameResult.GAMEPLAY_FRAME;
                lastFrameRanGameplay = false;
                inputHandler.update();
                previousDriverSnapshot = snapshot;
                return lastFrameResult;
            }
        } else {
            freshLevelTransitionLoopObserved = false;
        }
        startPendingInLevelTitleCardIfRequested();
        LevelFrameContext context =
                LevelFrameContext.from(SessionManager.getCurrentGameplayMode());
        boolean deferPauseEntry = deferRecordedPauseEntry
                && snapshot.player1().startPressed()
                && context.gameStateManager() != null
                && !context.gameStateManager().isGamePaused();
        FrameAdmission admission = LevelFrameStep.admit(
                context, levelManager,
                snapshot.player1().startPressed() && !deferPauseEntry);
        lastFrameResult = admission.result();
        lastFrameRanGameplay = false;
        if (lastFrameResult == LevelFrameResult.SETUP_ONLY) {
            return lastFrameResult;
        }
        frameCounter++;
        if (lastFrameResult == LevelFrameResult.PAUSED) {
            LevelFrameStep.serviceVBlankOnly(
                    context, lifecycleFrame, PlcLifecyclePhase.NORMAL_PAUSE);
            inputHandler.update();
            previousDriverSnapshot = snapshot;
            return lastFrameResult;
        }
        if (pendingSeamlessBoundaryCompletion) {
            pendingSeamlessBoundaryCompletion = false;
            LevelFrameStep.serviceVBlankOnly(
                    context, lifecycleFrame, PlcLifecyclePhase.LAG);
            inputHandler.update();
            previousDriverSnapshot = snapshot;
            return lastFrameResult;
        }
        try {
            if (lastFrameResult == LevelFrameResult.GAMEPLAY_FRAME) {
                beforeGameplay.run();
                LevelFrameStep.updateTimers(context);
                inputHandler.setLogicalOverride(snapshot);
                GameServices.sprites().publishHeldInputForLevelEvents(inputHandler);
                lastFrameResult = LevelFrameStep.execute(
                        context, lifecycleFrame, PlcLifecyclePhase.ORDINARY_LEVEL,
                        levelManager, GameServices.camera(),
                        () -> GameServices.sprites().update(inputHandler),
                        stepWrapper);
                lastFrameRanGameplay =
                        lastFrameResult == LevelFrameResult.GAMEPLAY_FRAME;
                // ROM: a retained Obj_LevelResults that mutates into
                // Obj_TitleCard runs Obj_TitleCardInit and queues its KosM art
                // within the same object pass that observed the cleared
                // End_of_level state (docs/skdisasm/sonic3k.asm:62703-62734,
                // 62120-62166). Poll again after the frame body so a results
                // exit during this pass starts the in-level title card in the
                // same frame rather than the next frame's top.
                startPendingInLevelTitleCardIfRequested();
                if (deferPauseEntry && lastFrameRanGameplay) {
                    // ROM LevelLoop calls Pause_Game before Demo_PlayRecord. A
                    // recorded Start edge therefore supplies the current row's
                    // input and enters Pause_Loop on the following iteration;
                    // keep the edge out of admission but publish the resulting
                    // Game_paused state after this row's body has completed.
                    context.gameStateManager().applyPauseToggle(true);
                }
            }
        } finally {
            inputHandler.clearLogicalOverride();
            inputHandler.update();
            previousDriverSnapshot = snapshot;
        }
        return lastFrameResult;
    }

    public LevelFrameResult getLastFrameResult() {
        return lastFrameResult;
    }

    public boolean didLastFrameRunGameplay() {
        return lastFrameRanGameplay;
    }

    public void primeInputState(Bk2FrameInput frameInput) {
        if (frameInput == null) {
            return;
        }
        previousDriverSnapshot = RecordedInputSnapshots.fromBk2(frameInput, null);
        inputHandler.setLogicalOverride(previousDriverSnapshot);
        inputHandler.update();
        inputHandler.clearLogicalOverride();
    }

    private void updateActiveTitleCardOverlay() {
        if (normalTitleCardActive) {
            // stepNormalTitleCard owns this row's single title-object dispatch.
            return;
        }
        TitleCardProvider titleCardProvider = GameServices.module().getTitleCardProvider();
        if (titleCardProvider != null && titleCardProvider.isOverlayActive()) {
            titleCardProvider.update();
            if (titleCardProvider.ownsInLevelPlayerControlLock()) {
                applyInLevelTitleCardControlLock(
                        titleCardProvider.shouldLockPlayerControlForInLevelOverlay());
            }
        }
    }

    private boolean applyPendingSeamlessTransition() {
        SeamlessLevelTransitionRequest seamlessRequest = levelManager.consumeSeamlessTransitionRequest();
        if (seamlessRequest == null) {
            return false;
        }
        levelManager.applySeamlessTransition(seamlessRequest);
        startPendingInLevelTitleCardIfRequested();
        return true;
    }

    /**
     * Completes a fade-coordinated destination request in the headless
     * production driver. The live loop performs this in its fade callback;
     * the recording driver owns the same level-load boundary directly.
     */
    private boolean applyPendingZoneActTransition() {
        if (freshLevelLoadedThisIteration) {
            freshLevelLoadedThisIteration = false;
            return true;
        }
        var gameplayMode = SessionManager.getCurrentGameplayMode();
        if (gameplayMode.getFadeManager().isActive()) {
            return false;
        }
        if (!levelManager.consumeZoneActRequest()) {
            return false;
        }
        int zone = levelManager.getRequestedZone();
        int act = levelManager.getRequestedAct();
        var blockingFade = gameplayMode.plcFrameLifecycle().beginNativeBlockingFade();
        gameplayMode.getFadeManager().startFadeToBlack(
                blockingFade.wrapCompletion(() -> {
                    try {
                        levelManager.loadZoneAndActAtFreshTitleCardBoundary(zone, act);
                        freshLevelLoadedThisIteration = true;
                    } catch (IOException exception) {
                        throw new IllegalStateException(
                                "Failed to load requested zone " + zone + " act " + act,
                                exception);
                    }
                }));
        return true;
    }

    private void startPendingNormalTitleCardIfRequested() {
        if (normalTitleCardActive
                || !levelManager.consumeTitleCardRequest()) {
            return;
        }
        TitleCardProvider provider = GameServices.module().getTitleCardProvider();
        if (provider == null) {
            return;
        }
        int titleCardZone = levelManager.getTitleCardZone();
        int titleCardAct = levelManager.getTitleCardAct();
        if (levelManager.hasPendingFreshLevelTransitionBoundary()) {
            provider.initializeFreshLevelTransition(titleCardZone, titleCardAct);
        } else {
            provider.initialize(titleCardZone, titleCardAct);
        }
        normalTitleCardActive = true;
        applyInLevelTitleCardControlLock(true);
    }

    private LevelFrameResult stepNormalTitleCard(
            LogicalInputSnapshot snapshot, PlcLifecycleFrame lifecycleFrame) {
        lastFrameResult = LevelFrameResult.GAMEPLAY_FRAME;
        lastFrameRanGameplay = false;
        frameCounter++;
        LevelFrameContext context =
                LevelFrameContext.from(SessionManager.getCurrentGameplayMode());
        TitleCardProvider provider = GameServices.module().getTitleCardProvider();
        if (provider == null) {
            normalTitleCardActive = false;
            applyInLevelTitleCardControlLock(false);
            lastFrameRanGameplay = true;
            inputHandler.update();
            previousDriverSnapshot = snapshot;
            return LevelFrameResult.GAMEPLAY_FRAME;
        }

        if (provider.shouldAdvanceVblankClockDuringLockedPhase()) {
            LevelFrameStep.executeHardwareTimedObjectScan(
                    context,
                    lifecycleFrame,
                    PlcLifecyclePhase.LEVEL_TITLE_CARD,
                    provider::update);
            levelManager.advanceTitleCardVblankOnly();
        } else {
            provider.update();
            LevelFrameStep.serviceVBlankOnly(
                    context, lifecycleFrame, PlcLifecyclePhase.LAG);
        }

        if (provider.shouldCompleteFreshLevelTransitionBoundary()) {
            levelManager.publishFreshLevelTransitionInitialBoundary();
            normalTitleCardActive = false;
            applyInLevelTitleCardControlLock(false);
        }
        inputHandler.update();
        previousDriverSnapshot = snapshot;
        return LevelFrameResult.GAMEPLAY_FRAME;
    }

    private void startPendingInLevelTitleCardIfRequested() {
        InLevelTitleCardCoordinator.startIfRequested(
                levelManager, GameServices.module().getTitleCardProvider(),
                GameServices.gameState().isEndOfLevelActive(),
                this::applyInLevelTitleCardControlLock);
    }

    private void applyInLevelTitleCardControlLock(boolean locked) {
        for (var sprite : GameServices.sprites().getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite playable) {
                playable.setControlLocked(locked);
            }
        }
    }

    public void stepIdleFrames(int frames) {
        for (int i = 0; i < frames; i++) {
            stepFrame(false, false, false, false, false);
        }
    }

    // ---- BK2 recording playback ----

    public void setBk2Movie(Bk2Movie movie, int bk2FrameOffset) {
        this.bk2Movie = movie;
        this.bk2StartIndex = bk2FrameOffset;
        this.currentBk2Index = bk2StartIndex;
        // The ROM has been polling this movie since its own frame 0 -- the replay
        // simply joins it at bk2FrameOffset -- so Ctrl_1_Held is NOT neutral here.
        // Seed the press-edge baseline from the row before the join point, which is
        // the last row the ROM polled before the engine takes over.
        this.lastPolledBk2Input =
                bk2StartIndex > 0 ? movie.getFrame(bk2StartIndex - 1) : null;
    }

    public boolean hasBk2Movie() {
        return bk2Movie != null;
    }

    public int stepFrameFromRecording() {
        return stepFrameFromRecording(() -> { });
    }

    public int stepFrameFromRecording(Runnable beforeGameplay) {
        requireMovie();
        if (currentBk2Index >= bk2Movie.getFrameCount()) {
            throw new IllegalStateException(
                    "BK2 movie exhausted at index " + currentBk2Index
                    + " (movie has " + bk2Movie.getFrameCount() + " frames)");
        }

        Bk2FrameInput frameInput = bk2Movie.getFrame(currentBk2Index);
        Bk2FrameInput previousInput = lastPolledBk2Input();
        int mask = inputMask(frameInput);

        LevelFrameResult result =
                stepFrame(RecordedInputSnapshots.fromBk2(frameInput, previousInput), () -> {
                    applyP1ActionPressEdge(currentBk2Index);
                    beforeGameplay.run();
                }, true);
        if (result != LevelFrameResult.SETUP_ONLY) {
            // SETUP_ONLY re-drives this same row next call, so the poll baseline
            // advances only once the row is actually consumed.
            markBk2RowPolled(currentBk2Index);
            currentBk2Index++;
        }

        return mask;
    }

    public int stepFrameFromRecordingUsingPreviousInput() {
        return stepFrameFromRecordingUsingPreviousInput(() -> { });
    }

    public int stepFrameFromRecordingUsingPreviousInput(Runnable beforeGameplay) {
        requireMovie();
        if (currentBk2Index >= bk2Movie.getFrameCount()) {
            throw new IllegalStateException(
                    "BK2 movie exhausted at index " + currentBk2Index
                    + " (movie has " + bk2Movie.getFrameCount() + " frames)");
        }
        if (currentBk2Index <= 0) {
            return stepFrameFromRecording(beforeGameplay);
        }

        Bk2FrameInput validationInput = bk2Movie.getFrame(currentBk2Index);
        Bk2FrameInput driveInput = bk2Movie.getFrame(currentBk2Index - 1);
        int validationMask = inputMask(validationInput);
        Bk2FrameInput pollBaseline = lastPolledBk2Input();

        LevelFrameResult result = stepFrame(
                RecordedInputSnapshots.fromBk2(
                        driveInput, pollBaseline), () -> {
                    applyP1ActionPressEdge(currentBk2Index - 1);
                    beforeGameplay.run();
                }, true);
        if (result != LevelFrameResult.SETUP_ONLY) {
            markBk2RowPolled(currentBk2Index - 1);
            currentBk2Index++;
        }

        return validationMask;
    }

    private void applyP1ActionPressEdge(int bk2Index) {
        if (hasNewP1ActionPressForLogicalInput(bk2Movie, bk2Index, sprite.isControlLocked())) {
            sprite.setForcedJumpPress(true);
        }
    }

    public static boolean hasNewP1ActionPressForLogicalInput(Bk2Movie movie, int bk2Index, boolean controlLocked) {
        return !controlLocked && hasNewP1ActionPress(movie, bk2Index);
    }

    public static boolean hasNewP1ActionPress(Bk2Movie movie, int bk2Index) {
        if (movie == null || bk2Index < 0 || bk2Index >= movie.getFrameCount()) {
            return false;
        }
        int currentAction = movie.getFrame(bk2Index).p1ActionMask();
        int previousAction = bk2Index > 0
                ? movie.getFrame(bk2Index - 1).p1ActionMask()
                : 0;
        return (currentAction & ~previousAction) != 0;
    }

    public int skipFrameFromRecording() {
        var gameplayMode = SessionManager.getCurrentGameplayMode();
        return gameplayMode.plcFrameLifecycle().runLogicalIteration(
                gameplayMode.getFadeManager()::update,
                this::skipFrameFromRecording);
    }

    private int skipFrameFromRecording(PlcLifecycleFrame lifecycleFrame) {
        requireMovie();
        if (currentBk2Index >= bk2Movie.getFrameCount()) {
            throw new IllegalStateException(
                    "BK2 movie exhausted at index " + currentBk2Index
                    + " (movie has " + bk2Movie.getFrameCount() + " frames)");
        }

        Bk2FrameInput frameInput = bk2Movie.getFrame(currentBk2Index);
        int mask = frameInput.p1InputMask();
        if (frameInput.p1ActionMask() != 0) {
            mask |= AbstractPlayableSprite.INPUT_JUMP;
        }

        // An ordinary lag row did not reach Joypad_Read, so its press-edge
        // baseline stays at the last polled movie row. Pause_Loop is different:
        // VInt_10 polls controllers on every Wait_VSync before checking Start
        // again (sonic3k.asm:1572-1607,719-725). While already paused, compare
        // against the immediately preceding movie row and retain this row as
        // the next poll baseline so a held Start is not repeatedly re-read as
        // an edge and the later unpause press remains visible.
        var gameState = GameServices.gameStateOrNull();
        boolean pauseLoopPolled = gameState != null && gameState.isGamePaused();
        Bk2FrameInput previousInput = pauseLoopPolled && currentBk2Index > 0
                ? bk2Movie.getFrame(currentBk2Index - 1)
                : lastPolledBk2Input();
        LogicalInputSnapshot snapshot =
                RecordedInputSnapshots.fromBk2(frameInput, previousInput);
        previousDriverSnapshot = snapshot;
        if (pauseLoopPolled) {
            markBk2RowPolled(currentBk2Index);
        }
        currentBk2Index++;
        applyPauseToggleForSuppressedRow(snapshot);
        LevelFrameContext context =
                LevelFrameContext.from(SessionManager.getCurrentGameplayMode());
        TraceSuppressedRowClosure.executeUnownedTitleCardWork(
                true,
                // The normal title owner still standing from a previous row is
                // dispatched by stepNormalTitleCard below, inside this row's
                // represented object scan. The overlay is owned here, so the
                // unowned path must not dispatch it a second time.
                normalTitleCardActive,
                GameServices.module().getTitleCardProvider(),
                this::startPendingInLevelTitleCardIfRequested,
                this::applyInLevelTitleCardControlLock);
        applyPendingSeamlessTransition();
        boolean loadedZoneActTransition = applyPendingZoneActTransition();
        // A normal title card is still a ROM-owned object during a suppressed
        // trace row. Start its art queue before the row's hardware closure so
        // the next VBlank can admit the same production work as the visible
        // path.
        if (!loadedZoneActTransition) {
            boolean titleCardWasActive = normalTitleCardActive;
            startPendingNormalTitleCardIfRequested();
            if (normalTitleCardActive && titleCardWasActive) {
                // Suppressed trace rows still represent a VBlank while the
                // fresh-level title owner is locked. Run its hardware-timed
                // object dispatch here; the row that installed the owner is
                // left to the generic closure above so its first dispatch
                // starts on the following VBlank, matching Obj_TitleCardInit.
                stepNormalTitleCard(previousDriverSnapshot, lifecycleFrame);
                return mask;
            }
        }
        TraceSuppressedRowClosure.execute(
                context,
                lifecycleFrame,
                levelManager,
                this::startPendingInLevelTitleCardIfRequested,
                this::applyInLevelTitleCardControlLock);
        return mask;
    }

    public void advancePlayableAnimationsOnly() {
        GameServices.sprites().advancePlayableSlotPrefix();
    }

    public void advancePlayableFixedSlotsOnly() {
        GameServices.sprites().advanceTailsTailsAfterObjectExecution();
    }

    public void suppressFirstSidekickAnimationOnce() {
        var sprites = GameServices.sprites();
        if (!sprites.getSidekicks().isEmpty()) {
            sprites.getSidekicks().getFirst().getAnimationManager().suppressNextUpdate();
        }
    }

    private static int inputMask(Bk2FrameInput frameInput) {
        int mask = frameInput.p1InputMask();
        if (frameInput.p1ActionMask() != 0) {
            mask |= AbstractPlayableSprite.INPUT_JUMP;
        }
        return mask;
    }

    public int consumeRecordingFrameInputOnly() {
        requireMovie();
        if (currentBk2Index >= bk2Movie.getFrameCount()) {
            throw new IllegalStateException(
                    "BK2 movie exhausted at index " + currentBk2Index
                    + " (movie has " + bk2Movie.getFrameCount() + " frames)");
        }

        Bk2FrameInput frameInput = bk2Movie.getFrame(currentBk2Index);
        int mask = inputMask(frameInput);
        applyP1ActionPressEdge(currentBk2Index);
        LogicalInputSnapshot snapshot =
                RecordedInputSnapshots.fromBk2(frameInput, lastPolledBk2Input());
        previousDriverSnapshot = snapshot;
        markBk2RowPolled(currentBk2Index);
        currentBk2Index++;
        applyPauseToggleForSuppressedRow(snapshot);
        return mask;
    }

    public int peekRecordingInputAt(int offset) {
        if (bk2Movie == null) {
            return -1;
        }
        int targetIndex = currentBk2Index + offset;
        if (targetIndex < 0 || targetIndex >= bk2Movie.getFrameCount()) {
            return -1;
        }
        Bk2FrameInput frameInput = bk2Movie.getFrame(targetIndex);
        int mask = frameInput.p1InputMask();
        if (frameInput.p1ActionMask() != 0) {
            mask |= AbstractPlayableSprite.INPUT_JUMP;
        }
        return mask;
    }

    public int getRecordingFramesRemaining() {
        if (bk2Movie == null) {
            return 0;
        }
        return bk2Movie.getFrameCount() - currentBk2Index;
    }

    public void advanceRecordingCursor(int frameCount) {
        if (frameCount <= 0) {
            return;
        }
        requireMovie();
        int targetIndex = currentBk2Index + frameCount;
        if (targetIndex > bk2Movie.getFrameCount()) {
            throw new IllegalStateException(
                    "BK2 movie exhausted while advancing cursor to index " + targetIndex
                            + " (movie has " + bk2Movie.getFrameCount() + " frames)");
        }
        currentBk2Index = targetIndex;
    }

    private void requireMovie() {
        if (bk2Movie == null) {
            throw new IllegalStateException("No BK2 movie loaded. Call setBk2Movie() first.");
        }
    }

    /**
     * Pause_Game still consumes Start edges on rows whose main level body is
     * suppressed. In particular, the ROM's unpause press is a VBlank/advance
     * row and must clear Game_paused before the following full LevelLoop row.
     */
    private static void applyPauseToggleForSuppressedRow(LogicalInputSnapshot snapshot) {
        if (snapshot == null) {
            return;
        }
        var gameState = GameServices.gameStateOrNull();
        if (gameState != null) {
            gameState.applyPauseToggle(snapshot.player1().startPressed());
        }
    }

    private LogicalInputSnapshot driverSnapshot(boolean up, boolean down, boolean left, boolean right, boolean jump,
                                                int p2Mask, boolean p2Start, boolean p1Start) {
        int p1DirectionHeld = directionMask(up, down, left, right);
        int p1ActionHeld = jump ? InputActionMasks.ACTION_A : 0;
        int p2DirectionHeld = p2Mask & directionBits();
        int p2ActionHeld = (p2Mask & AbstractPlayableSprite.INPUT_JUMP) != 0
                ? InputActionMasks.ACTION_A
                : 0;
        PlayerInputState previousP1 = previousDriverSnapshot.player1();
        PlayerInputState previousP2 = previousDriverSnapshot.player2();
        PlayerInputState p1 = PlayerInputState.of(
                p1DirectionHeld,
                p1DirectionHeld & ~previousP1.heldMask(),
                p1ActionHeld,
                p1ActionHeld & ~previousP1.actionHeldMask(),
                p1Start,
                p1Start && !previousP1.startHeld());
        PlayerInputState p2 = PlayerInputState.of(
                p2DirectionHeld,
                p2DirectionHeld & ~previousP2.heldMask(),
                p2ActionHeld,
                p2ActionHeld & ~previousP2.actionHeldMask(),
                p2Start,
                p2Start && !previousP2.startHeld());
        return LogicalInputSnapshot.ofPlayers(p1, p2);
    }

    /**
     * ROM {@code Joypad_Read} computes {@code pressed = new & ~stored_held} and only
     * then overwrites {@code stored_held} (s2.asm:1361-1387, sonic.asm:1088-1119). It
     * is reached only from the polling V-int routines: {@code Vint_Level} calls
     * {@code ReadJoypads} (s2.asm:706) while {@code Vint_Lag}/{@code VintSub0} does not
     * (s2.asm:528-543), and identically {@code VBlank_Lag} in S1 (sonic.asm:711-712).
     * So on a lag frame {@code Ctrl_1_Held} is not refreshed, and the next polled frame
     * computes its press edge against the older held byte — the press survives the lag
     * frame rather than being swallowed.
     * <p>
     * The baseline is therefore the last row the engine actually <em>polled</em>, not
     * the immediately preceding BK2 row. A VBLANK_ONLY row is consumed 1:1 by
     * {@link #skipFrameFromRecording()} (the recorder's {@code input} column is the raw
     * BK2 row even on lag rows, and the binder compares it), but it must not become the
     * edge baseline. This is a cursor-independent change: only the baseline moves.
     */
    private Bk2FrameInput lastPolledBk2Input() {
        return lastPolledBk2Input;
    }

    /** Records that {@code index} was polled, so it becomes the next press-edge baseline. */
    private void markBk2RowPolled(int index) {
        if (index >= 0 && index < bk2Movie.getFrameCount()) {
            lastPolledBk2Input = bk2Movie.getFrame(index);
        }
    }

    private static int directionMask(boolean up, boolean down, boolean left, boolean right) {
        int mask = 0;
        if (up) {
            mask |= AbstractPlayableSprite.INPUT_UP;
        }
        if (down) {
            mask |= AbstractPlayableSprite.INPUT_DOWN;
        }
        if (left) {
            mask |= AbstractPlayableSprite.INPUT_LEFT;
        }
        if (right) {
            mask |= AbstractPlayableSprite.INPUT_RIGHT;
        }
        return mask;
    }

    private static int directionBits() {
        return AbstractPlayableSprite.INPUT_UP
                | AbstractPlayableSprite.INPUT_DOWN
                | AbstractPlayableSprite.INPUT_LEFT
                | AbstractPlayableSprite.INPUT_RIGHT;
    }
}
