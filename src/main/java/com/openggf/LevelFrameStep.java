package com.openggf;

import com.openggf.camera.Camera;
import com.openggf.game.BonusStageProvider;
import com.openggf.game.GameStateManager;
import com.openggf.game.HardwareBoundaryDispatch;
import com.openggf.game.InLevelTitleCardCoordinator;
import com.openggf.game.LevelEventProvider;
import com.openggf.game.palette.PaletteOwnershipRegistry;
import com.openggf.game.resources.PlcFrameLifecycleCoordinator.PlcLifecycleFrame;
import com.openggf.game.resources.PlcLifecyclePhase;
import com.openggf.game.timing.HardwareServiceBoundary;
import com.openggf.level.LevelManager;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.Objects;

/**
 * Canonical level-mode frame update sequence.
 * <p>
 * This is the <b>single source of truth</b> for level tick ordering.
 * Both {@link GameLoop} and the headless test runner ({@code HeadlessTestRunner})
 * MUST delegate to this class rather than duplicating the step sequence.
 * <p>
 * Order mirrors the Mega Drive ROM.
 * Inline solid-resolution modules run player physics first, then ExecuteObjects
 * with per-object solid checkpoints so object code sees the post-physics player
 * state during its own update. Legacy compatibility modules keep the older
 * objects-before-physics ordering.
 * <p>
 * ROM reference (sonic.asm:3042-3044): {@code LZWaterFeatures} runs before
 * {@code ExecuteObjects} so that wind tunnel / water slide state is visible
 * to objects and to the player physics that follows.
 */
public final class LevelFrameStep {

    /**
     * Optional wrapper around individual steps, allowing callers to add
     * profiling, logging, or other cross-cutting concerns without altering
     * the canonical ordering.
     */
    @FunctionalInterface
    public interface StepWrapper {
        void wrap(String sectionName, Runnable step);
    }

    private static final StepWrapper DIRECT = (name, step) -> step.run();

    /**
     * Public no-op step wrapper for callers (e.g. the headless test runner) that
     * route through {@link #executeWithPause} but do not need profiling.
     */
    public static final StepWrapper DIRECT_WRAPPER = DIRECT;

    private LevelFrameStep() {
        // Utility class
    }

    public static FrameAdmission admit(
            LevelFrameContext context, LevelManager levelManager,
            boolean startEdgePressed) {
        GameStateManager gameState = context.gameStateManager();
        if (gameState != null && gameState.applyPauseToggle(startEdgePressed)) {
            return new FrameAdmission(LevelFrameResult.PAUSED);
        }
        if (levelManager.consumePendingInitialProcessSpritesPass()) {
            return new FrameAdmission(LevelFrameResult.SETUP_ONLY);
        }
        return new FrameAdmission(LevelFrameResult.GAMEPLAY_FRAME);
    }

    /**
     * Executes one frame of level-mode updates in the canonical production order,
     * without any step wrapping.
     *
     * @param levelManager the level manager
     * @param camera       the camera
     * @param spriteUpdate callback that runs the sprite/player physics update
     *                     (e.g. {@code SpriteManager.update()} or headless equivalent)
     */
    /**
     * Executes one frame of level-mode updates, applying ROM in-game pause first.
     * <p>
     * This is the gameplay-loop entry point that honours {@code Game_paused}. The
     * Start-press edge is supplied by the caller from the live/replay input stream
     * (never from trace data) and toggles {@link GameStateManager#applyPauseToggle}.
     * When the game is paused after the toggle, the entire level update body is
     * skipped for this frame — exactly as ROM {@code Pause_Loop} runs only the
     * V-int (the caller still advances its frame counter and consumes the input
     * row), so a paused window stays frame-aligned.
     *
     * @param startEdgePressed true only on the leading edge of a Start press
     * @return {@link LevelFrameResult#PAUSED} when pause owns the row,
     *         {@link LevelFrameResult#SETUP_ONLY} for the one-shot setup pass,
     *         otherwise {@link LevelFrameResult#GAMEPLAY_FRAME}
     */
    public static LevelFrameResult executeWithPause(
            LevelFrameContext context, PlcLifecycleFrame frame,
            PlcLifecyclePhase activePhase, PlcLifecyclePhase pausePhase,
            LevelManager levelManager, Camera camera, Runnable spriteUpdate,
            boolean startEdgePressed, StepWrapper wrapper) {
        GameStateManager gameState = context.gameStateManager();
        if (gameState != null && gameState.applyPauseToggle(startEdgePressed)) {
            serviceVBlankOnly(context, frame, pausePhase);
            return LevelFrameResult.PAUSED;
        }
        return execute(context, frame, activePhase, levelManager, camera, spriteUpdate, wrapper);
    }

    public static void updateTimers(LevelFrameContext context) {
        context.timerManager().update();
    }

    /**
     * Services the sole production boundary traversed by a VBlank-only row.
     */
    public static void serviceVBlankOnly(
            LevelFrameContext context, PlcLifecycleFrame frame, PlcLifecyclePhase phase) {
        if (phase != PlcLifecyclePhase.LAG
                && phase != PlcLifecyclePhase.NORMAL_PAUSE
                && phase != PlcLifecyclePhase.SPECIAL_STAGE_PAUSE) {
            throw new IllegalArgumentException("not a VBlank-only PLC phase: " + phase);
        }
        // A V-blank-only row has no body of its own, so this phase IS the row.
        // claim() can still lose the token to an active native blocking fade
        // that pre-claimed it. Making that fatal here was measured on the full
        // trace profile and errors 8 previously green S3K classes, all with the
        // same owner=PALETTE_FADE shape.
        //
        // For S3K that loss is ROM-correct, not an open defect. Both blocking
        // fades rewrite V_int_routine = $12 on every loop iteration before
        // Wait_VSync (Pal_FadeToBlack sonic3k.asm:5045-5050, Pal_FadeFromBlack
        // :4906-4911), so a V-blank inside an S3K fade always dispatches VInt_12
        // (:849-852) and can never reach VInt_0_Main -- the lag path, taken only
        // when V_int_routine is 0 (:519-520) and the sole bump of Lag_frame_count
        // (:570). VInt_12 does the frame's art work (bra.w Process_Nem_Queue,
        // :852) while deliberately omitting Set_Kos_Bookmark, which VInt_14
        // (:672) and VInt_16 (:888) do perform: the fade genuinely owns the
        // frame and leaves mode-specific PLC work paused. The LAG label the
        // replay closure passes here is the structural "this row has no gameplay
        // body" classification, not a claim that the ROM ran its lag handler --
        // the two predicates coincide nearly everywhere and diverge exactly
        // inside a blocking fade.
        //
        // The S2 Vint_CtrlDMA case is the genuinely different one: a real lag
        // V-blank that still reaches ProcessDMAQueue. It is resolved
        // structurally in PlcFrameLifecycleCoordinator#latchBeforeFadeUpdate.
        frame.claim(phase);
        if (frame.consumedHeldLoopTailPreparation()) {
            context.runtimeArtCoordinator()
                    .deferProductionSubmissionForHeldLoopTailClosure();
        }
        dispatchGameVBlank(context, frame);
        serviceBoundary(context, HardwareServiceBoundary.VINT_SERVICE);
    }

    public static void serviceHardwareVBlankOnly(
            LevelFrameContext context, PlcLifecycleFrame frame) {
        dispatchGameVBlank(context, frame);
        serviceBoundary(context, HardwareServiceBoundary.VINT_SERVICE);
    }

    /**
     * Services only the module-queue boundary represented by a suppressed
     * held-counter row. The ROM still runs this loop-tail boundary even though
     * it does not run the ordinary object/physics body for that row.
     */
    public static void serviceHardwarePostObjectsOnly(LevelFrameContext context) {
        serviceBoundary(context, HardwareServiceBoundary.POST_OBJECTS);
    }

    /** Services only the direct-FIFO boundary represented by a held row tail. */
    public static void serviceHardwarePreMainLoopOnly(LevelFrameContext context) {
        serviceBoundary(context, HardwareServiceBoundary.PRE_MAIN_LOOP);
        context.runtimeArtCoordinator().finishHeldLoopTailClosure();
    }

    /**
     * Services one admitted ROM loop whose object/sprite scan is owned outside
     * the normal level-frame executor.
     *
     * <p>S3K's special-stage and locked title-card loops both run direct queue
     * work around VBlank, scan their own sprites, then run module-queue service.
     * Keeping that sequence here prevents alternate loops from inventing their
     * own boundary dispatch.
     */
    public static void executeHardwareTimedObjectScan(
            LevelFrameContext context, PlcLifecycleFrame frame,
            PlcLifecyclePhase phase, Runnable objectScan) {
        Objects.requireNonNull(objectScan, "objectScan");
        frame.claim(phase);
        if (frame.consumedHeldLoopTailPreparation()) {
            context.runtimeArtCoordinator()
                    .deferProductionSubmissionForHeldLoopTailClosure();
        }
        dispatchGameVBlank(context, frame);
        serviceBoundary(context, HardwareServiceBoundary.VINT_SERVICE);
        objectScan.run();
        serviceBoundary(context, HardwareServiceBoundary.POST_OBJECTS);
        serviceBoundary(context, HardwareServiceBoundary.PRE_MAIN_LOOP);
        context.runtimeArtCoordinator().finishHeldLoopTailClosure();
        if (frame.isOwnedBy(phase)) {
            frame.prepareAfterLoop(phase);
        }
    }

    /**
     * Executes one frame of level-mode updates in the canonical production order.
     * <p>
     * Steps are wrapped with the provided {@link StepWrapper}, which receives a
     * section name suitable for profiler labels.
     *
     * @param levelManager the level manager
     * @param camera       the camera
     * @param spriteUpdate callback that runs the sprite/player physics update
     * @param wrapper      wraps individual steps (e.g. for profiling)
     */
    public static LevelFrameResult execute(
            LevelFrameContext context, PlcLifecycleFrame frame, PlcLifecyclePhase phase,
            LevelManager levelManager, Camera camera,
            Runnable spriteUpdate, StepWrapper wrapper) {
        if (context == null) {
            throw new NullPointerException("context");
        }

        // S3K fresh-level assembly runs Load_Sprites then Process_Sprites once
        // before entering LevelLoop (docs/skdisasm/sonic3k.asm:7849-7855,
        // 7889-7906). executeWithPause reaches this body only after its pause
        // gate, so a paused first frame retains the one-shot authority.
        if (levelManager.consumePendingInitialProcessSpritesPass()) {
            return LevelFrameResult.SETUP_ONLY;
        }

        frame.claim(phase);
        if (frame.consumedHeldLoopTailPreparation()) {
            context.runtimeArtCoordinator()
                    .deferProductionSubmissionForHeldLoopTailClosure();
        }
        dispatchGameVBlank(context, frame);
        serviceBoundary(context, HardwareServiceBoundary.VINT_SERVICE);

        // ROM: the level loop increments Level_frame_counter immediately after
        // the V-blank wait and before the object pass, in all three games
        // (docs/s1disasm/sonic.asm:3001-3006, docs/s2disasm/s2.asm:5090-5094,
        // docs/skdisasm/sonic3k.asm:7919-7925). Every routine that runs this
        // frame therefore reads the already-incremented value.
        levelManager.advanceLevelFrameCounter();

        // 0a. Drain the per-frame palette-write accumulator at frame top, before
        //     any submitter (object palette writes in steps 2-3, zone palette
        //     cyclers in step 6) runs this frame. This is the canonical per-frame
        //     drain for BOTH paths. GameLoop.update() also calls beginFrame() at
        //     its own frame top (idempotent here — nothing submits between the two
        //     calls), but the headless/replay pipeline bypasses GameLoop, which
        //     left PaletteOwnershipRegistry.writes growing unbounded: resolveInto()
        //     re-sorts an ever-larger list every frame (O(n^2) within a run) and
        //     the list compounds across warm reset-to-snapshot sessions. Owning the
        //     drain in the shared frame step fixes the headless leak.
        PaletteOwnershipRegistry paletteRegistry = context.paletteOwnershipRegistry();
        if (paletteRegistry != null) {
            paletteRegistry.beginFrame();
        }

        // 0. Process dirty regions from MutableLevel (editor mutations).
        //    No-op when the level is not a MutableLevel — zero impact on gameplay.
        levelManager.processDirtyRegions();

        // 1. Zone features pre-physics — wind tunnels, water slides set
        //    f_slidemode / obInertia before ExecuteObjects and Sonic_Move.
        //    ROM: LZWaterFeatures runs before ExecuteObjects (sonic.asm:3042).
        levelManager.updateZoneFeaturesPrePhysics();

        // 1b. Pre-physics level-event routines that the ROM runs in WaterEffects
        //     immediately before RunObjects (docs/s2disasm/s2.asm:5094-5095).
        //     OOZ OilSlides reads the previous-frame player position and sets the
        //     sliding status bit before the player's friction/move code runs the
        //     same frame; running it post-physics applied oil friction one frame
        //     early (OOZ1 trace f563). Default no-op for other games/zones.
        LevelEventProvider prePhysicsEvents = context.levelEventProvider();
        if (prePhysicsEvents != null) {
            prePhysicsEvents.updatePrePhysics();
        }

        // 1c. Dynamic water level move — ROM S1/S2 advance the water level
        //     (LZWaterFeatures / WaterEffects) BEFORE ExecuteObjects/RunObjects,
        //     so the player's same-frame Sonic_Water underwater check reads the
        //     just-moved level. S3K runs Handle_Onscreen_Water_Height AFTER the
        //     object loop, so it keeps the move in step 6 (LevelManager.update).
        //     Gated by CollisionRules.advanceWaterLevelBeforePlayerPhysics().
        if (levelManager.advanceWaterLevelBeforePlayerPhysics()) {
            wrapper.wrap("water-move", levelManager::advanceDynamicWaterLevel);
        }

        boolean inlineSolidResolution = levelManager.objectsExecuteAfterPlayerPhysics();
        SpriteManager spriteManager = context.spriteManager();
        // ROM RunObjects covers the level-only fixed slots (Tails' tails, dust,
        // shields, bubbles, stars) only while the game mode carries no title-card
        // flag; see TitleCardProvider#shouldRunLevelOnlyFixedSlotsDuringLockedPhase
        // for the S2/S3K citations. A locked title-card frame must therefore skip
        // that half of the dispatch in games whose ROM excludes it.
        SpriteManager levelOnlyFixedSlots = spriteManager != null
                && (phase != PlcLifecyclePhase.LEVEL_TITLE_CARD
                        || context.gameModule().getTitleCardProvider()
                                .shouldRunLevelOnlyFixedSlotsDuringLockedPhase())
                ? spriteManager
                : null;
        if (inlineSolidResolution) {
            // 2. Inline-order modules need a frame-start snapshot of object touch
            //    state because player-slot ReactToItem runs before ExecuteObjects.
            levelManager.prepareTouchResponseSnapshots();

            // 2. Inline solid-resolution path: player physics first. Touch responses
            //    run per-player inside tickPlayablePhysics after movement, matching
            //    the player-slot-first ROM ordering.
            wrapper.wrap("physics", spriteUpdate);

            LevelEventProvider fixedSlotEvents = context.levelEventProvider();
            if (fixedSlotEvents != null) {
                wrapper.wrap("fixed-objects-pre", fixedSlotEvents::updateFixedInLevelObjectsBeforeDynamicObjects);
            }

            // 3. Object execution after player physics, with inline solid checkpoints
            //    so later objects see earlier contact adjustments.
            Runnable afterExecBeforePlacement = levelOnlyFixedSlots != null
                    ? () -> {
                        levelOnlyFixedSlots.advancePlayableFixedSlotsAfterObjectExecution();
                        levelManager.updateZoneFeaturesAfterObjectExecution();
                    }
                    : levelManager::updateZoneFeaturesAfterObjectExecution;
            wrapper.wrap("objects",
                    () -> levelManager.updateObjectPositionsPostPhysicsWithoutTouches(
                            afterExecBeforePlacement, false));
        } else {
            LevelEventProvider fixedSlotEvents = context.levelEventProvider();
            if (fixedSlotEvents != null) {
                wrapper.wrap("fixed-objects-pre", fixedSlotEvents::updateFixedInLevelObjectsBeforeDynamicObjects);
            }

            // 2. Legacy compatibility path keeps objects before physics. Touch
            //    responses are still deferred to tickPlayablePhysics after movement.
            wrapper.wrap("objects", () -> levelManager.updateObjectPositionsWithoutTouches(false));

            // 3. Sprite / player physics update (caller-provided).
            wrapper.wrap("physics", spriteUpdate);

            // 3b. Some later SST-slot scripts read Sonic after his movement has
            //     completed and only write globals for the next frame. Legacy
            //     object-order modules need an explicit post-player hook for
            //     those cases because regular object updates already ran.
            wrapper.wrap("post-player-hooks", levelManager::updateObjectPostPlayerHooks);
        }

        // 4. Camera scroll + dynamic level events, in ROM order.
        //
        //    ROM DeformLayers (s1disasm DeformLayers (REV01).asm:16-18) runs
        //    ScrollHoriz + ScrollVertical (camera move + clamp to the bottom
        //    boundary left by the PREVIOUS frame's DynamicLevelEvents) BEFORE
        //    DynamicLevelEvents. DynamicLevelEvents (DynamicLevelEvents.asm:5-49)
        //    then runs the zone-specific handler (DLE_Index) FIRST and only after
        //    that eases the bottom boundary, reading the POST-scroll camera
        //    (v_screenposy) and the player's airborne bit to choose the +2 vs +8
        //    step, and writing v_limitbtm2 / f_bgscrollvert for the NEXT frame.
        //
        //    So per frame: (4a) camera move/clamp, (4b) zone event handler reading
        //    the post-scroll camera, (4c) flush layout mutations queued by those
        //    events, (4d) boundary easing. This keeps gameplay mutations visible
        //    before post-camera placement/level scroll without moving event writes
        //    ahead of the ROM camera scroll.
        LevelEventProvider levelEvents = context.levelEventProvider();
        boolean cameraDrivenScroll = levelManager.advanceCameraDrivenScrollForFrame();

        BonusStageProvider bonusStageProvider = context.bonusStageProvider();
        boolean integratedBonusStageUpdate = bonusStageProvider != null
                && bonusStageProvider.updateDuringLevelFrame();
        boolean suppressDefaultCamera = bonusStageProvider != null
                && bonusStageProvider.suppressesDefaultCameraStep();
        // ROM: LevelLoop (sonic3k.asm:7895-7896) checks Restart_level_flag
        // immediately after Process_Sprites (object execution) and branches
        // away to `Level` (skipping DeformBgLayer, the camera-scroll routine)
        // whenever an object set the flag during that same object pass —
        // e.g. Obj_PachinkoEnergyTrap's exit trigger (sonic3k.asm:96682) when
        // Sonic escapes through the top of the Glowing Spheres board. Mirror
        // that skip: once this frame's object pass has flagged bonus-stage
        // completion, the camera must not advance further this same frame
        // (S3K Pachinko trace frame 2903: ROM camera_x holds at the prior
        // frame's value while Sonic's position still updated one last time).
        boolean bonusStageExitRequestedThisFrame = bonusStageProvider != null
                && bonusStageProvider.isStageComplete();
        // A StartNewLevel-style object request sets the engine's inactive flag
        // from inside Process_Sprites. ROM observes Restart_level_flag
        // immediately after that object pass and branches back to Level before
        // DeformBgLayer, leaving the camera at its prior position even though
        // the final player/object updates remain visible on the boundary row.
        boolean levelExitRequestedDuringObjects = levelManager.isLevelInactiveForTransition();

        // Some ROM object workers publish camera boundaries before DeformBgLayer
        // consumes them. Keep that ordering at the provider boundary so the
        // shared camera step remains unaware of game- or zone-specific state.
        if (levelEvents != null && !levelExitRequestedDuringObjects) {
            wrapper.wrap("events-pre-camera", levelEvents::updateAfterObjectsBeforeCamera);
        }

        // 4a. Camera scroll (ROM ScrollHoriz + ScrollVertical): move + clamp to the
        //     prior-frame bottom boundary, BEFORE the zone event handler runs.
        if (!suppressDefaultCamera && !cameraDrivenScroll
                && !bonusStageExitRequestedThisFrame
                && !levelExitRequestedDuringObjects) {
            wrapper.wrap("camera-scroll", camera::updatePosition);
        }
        // 4b. Dynamic level events — boss arenas, boundary changes, zone
        //     transitions. ROM runs the zone handler (DLE_Index) here, after the
        //     scroll, so camera-X gates and the left-boundary lock see the
        //     post-scroll camera. fixed-in-level objects run alongside.
        if (levelEvents != null && !levelExitRequestedDuringObjects) {
            wrapper.wrap("fixed-objects", levelEvents::updateFixedInLevelObjects);
        }
        if (levelOnlyFixedSlots != null && !inlineSolidResolution) {
            wrapper.wrap("fixed-dust", levelOnlyFixedSlots::advancePlayableFixedSlotsAfterObjectExecution);
        }
		// ROM ScreenEvents publishes Camera_*_pos_copy before the zone event
		// handlers. Event-owned camera motion after this point must not move the
		// render/visibility camera until the next loop publication. A restart
		// request branches to Level immediately after Process_Sprites, so that
		// loop does not reach ScreenEvents at all.
		if (!levelExitRequestedDuringObjects) {
		    camera.captureRenderCopy();
		}
		if (levelEvents != null && !levelExitRequestedDuringObjects) {
            levelEvents.update();
        }

        // OscillateNumDo is the loop-tail update after ScreenEvents. An
        // in-loop act reload has already replaced the level data, but the ROM
        // still reaches this loop tail on that row unless the transition's
        // provider already performed the ROM-owned oscillator dispatch. The
        // latter is the CNZ/LBZ-style transition contract; AIZ/HCZ/MGZ retain
        // the ordinary loop tail. Outer frame-boundary reloads consume their
        // own dispatch in LevelSeamlessTransitionExecutor before this method
        // is entered.
        boolean actTransitionExecutedDuringFrame =
                levelManager.consumeActTransitionExecutedDuringFrame();
        boolean actTransitionOscillationAdvancedDuringFrame =
                levelManager.consumeActTransitionOscillationAdvancedDuringFrame();
        if (!bonusStageExitRequestedThisFrame
                && !levelManager.isLevelInactiveForTransition()
                && !(actTransitionExecutedDuringFrame
                        && actTransitionOscillationAdvancedDuringFrame)) {
            levelManager.advanceGlobalOscillationAtLevelLoopTail();
        }

        // ROM LevelLoop runs ScreenEvents before Process_Kos_Module_Queue
        // (docs/skdisasm/sonic3k.asm:7898-7908). A completion retired here is
        // therefore first observable by object/event consumers on their next
        // dispatch, never by this frame's ScreenEvents pass.
        // Runtime art queues are consumed once per active gameplay frame. S3K
        // uses this for Queue_Kos_Module workloads whose object routines poll
        // Kos_modules_left on later frames. It runs with the object pass because
        // ROM LevelLoop reaches its producers in ExecuteObjects
        // (docs/skdisasm/sonic3k.asm:7900-7906), ahead of the
        // Process_Kos_Module_Queue state step in the loop tail (7908).
        // A results owner can publish an in-level title-card request during
        // ExecuteObjects. Obj_TitleCardInit queues its KosM parents before the
        // same frame's Process_Kos_Module_Queue, so consume that request here,
        // after object/event producers and immediately before the art pump.
        startPendingInLevelTitleCard(context, levelManager, spriteManager);
        if (context.gameModule().getObjectArtProvider() != null) {
            context.gameModule().getObjectArtProvider().processRuntimeArtQueue();
        }

        if (frame.defersLoopTailPreparation()) {
            context.runtimeArtCoordinator()
                    .deferProductionSubmissionForHeldLoopTail();
        }
        serviceBoundary(context, HardwareServiceBoundary.POST_OBJECTS);

        // ROM LevelLoop's Process_Kos_Queue (docs/skdisasm/sonic3k.asm:7887)
        // runs after this iteration's Process_Kos_Module_Queue (7908) and before
        // Wait_VSync (7888) increments Level_frame_counter (7889), so the direct
        // FIFO service is the tail of THIS frame. Servicing it here lets a
        // Queue_Kos_Module issued by an object during this frame's pass have its
        // first child decompressed on the same frame, which the frame-top
        // placement could not represent.
        serviceBoundary(context, HardwareServiceBoundary.PRE_MAIN_LOOP);
        context.runtimeArtCoordinator().finishHeldLoopTailClosure();
        if (frame.isOwnedBy(phase)) {
            frame.prepareAfterLoop(phase);
        }

        // 4c. Flush gameplay layout mutations queued by zone events before
        //     boundary easing and post-camera systems observe the changed level.
        levelManager.flushQueuedLayoutMutations();

        // 4d. Boundary easing (ROM DynamicLevelEvents boundary tail): ease the
        //     bottom boundary toward target reading the post-scroll camera, and
        //     record the boundary state for the NEXT frame's scroll clamp.
        if (!suppressDefaultCamera && !cameraDrivenScroll
                && !bonusStageExitRequestedThisFrame
                && !levelExitRequestedDuringObjects) {
            wrapper.wrap("camera-boundary", camera::updateBoundaryEasing);
            if (levelEvents != null) {
                levelEvents.updateAfterCameraBoundaryEasing();
            }
        }

        if (levelManager.isLevelInactiveForTransition()) {
            // The native frame still reaches BuildSprites while a results/fade
            // transition owns the later level work. Keep playable render_flags
            // current so fixed objects that read the prior frame's on-screen
            // bit (for example Obj_MGZ2_BossTransition) do not observe the last
            // pre-transition value indefinitely.
            if (spriteManager != null) {
                spriteManager.refreshPlayableRenderFlags(camera);
            }
            return LevelFrameResult.GAMEPLAY_FRAME;
        }

        if (integratedBonusStageUpdate) {
            bonusStageProvider.onFrameUpdate();
        }

        // 5b. Post-camera placement catch-up — extend the spawn window with the
        //     post-camera position. ROM: ObjPosLoad runs after DeformLayers
        //     (camera update), so spawns see the updated camera. When the camera
        //     crosses a chunk boundary between step 2 and step 5, this catches
        //     spawns that the pre-camera placement missed. No-op when the camera
        //     chunk hasn't changed.
        levelManager.postCameraObjectPlacementSync();

        // 6. Level scroll / parallax / animation update.
        wrapper.wrap("level", levelManager::update);

        // 6b. ROM Level_MainLoop tail slot, after SynchroAnimate
        //     (docs/s1disasm/sonic.asm:3028-3032). S1 runs SignpostArtLoad here;
        //     other games default to a no-op. Position matters: this runs after
        //     RunPLC in the same frame, so work queued here is only serviced
        //     from the next frame.
        LevelEventProvider loopTailEvents = context.levelEventProvider();
        if (loopTailEvents != null) {
            wrapper.wrap("level-loop-tail", loopTailEvents::updateAtLevelLoopTail);
        }

        // 7. Cache BuildSprites on-screen results for next frame's logic.
        levelManager.refreshObjectPostCameraRenderState();
        if (spriteManager != null) {
            spriteManager.refreshPlayableRenderFlags(camera);
        }
        levelManager.clearSidekickRomVisibleReloadFrameCounterBridge();
        return LevelFrameResult.GAMEPLAY_FRAME;
    }

    private static void serviceBoundary(
            LevelFrameContext context, HardwareServiceBoundary boundary) {
        if (context == null) {
            throw new NullPointerException("context");
        }
        HardwareBoundaryDispatch.serviceBoundary(
                boundary,
                context.runtimeArtCoordinator(),
                context.hardwareTiming(),
                context.hardwareTimingBoundaryObserver());
    }

    /** Dispatches game-owned VBlank work once for this physical lifecycle token. */
    public static void dispatchGameVBlank(
            LevelFrameContext context, PlcLifecycleFrame frame) {
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(frame, "frame");
        frame.dispatchGameVBlank(() -> {
            var module = context.gameModule();
            var profile = module != null ? module.getLevelInitProfile() : null;
            if (profile != null) {
                profile.serviceLevelLoadVBlank();
            }
        });
    }

    private static void startPendingInLevelTitleCard(
            LevelFrameContext context, LevelManager levelManager, SpriteManager spriteManager) {
        InLevelTitleCardCoordinator.startIfRequested(
                levelManager,
                context.gameModule().getTitleCardProvider(),
                context.gameStateManager() != null
                        && context.gameStateManager().isEndOfLevelActive(),
                locked -> {
                    if (spriteManager == null) {
                        return;
                    }
                    for (var sprite : spriteManager.getAllSprites()) {
                        if (sprite instanceof AbstractPlayableSprite playable) {
                            playable.setControlLocked(locked);
                        }
                    }
                });
    }

}
