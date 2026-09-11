package com.openggf.game.sonic3k.bonusstage.slots;


import com.openggf.game.session.EngineServices;
import com.openggf.audio.GameSound;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.data.RomByteReader;
import com.openggf.game.GameServices;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.session.GameplayModeContext;
import com.openggf.game.session.SessionManager;
import com.openggf.game.sonic3k.S3kSpriteDataLoader;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.objects.S3kSlotBonusCageObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSlotRingRewardObjectInstance;
import com.openggf.game.sonic3k.objects.S3kSlotSpikeRewardObjectInstance;
import com.openggf.level.LevelManager;
import com.openggf.level.objects.DefaultObjectServices;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class S3kSlotBonusStageRuntime {
    private static final int TILE_SIZE = 8;
    private static final int GLASS_PRIORITY_HALF_EXTENT_X = 0x30;
    private static final int GLASS_PRIORITY_HALF_EXTENT_Y = 0x30;
    private static final int PRIORITY_BIT = 0x8000;

    private boolean initialized;
    private GameplayModeContext bootstrapGameplayMode;
    private AbstractPlayableSprite originalPlayer;
    private S3kSlotStageState slotStageState;
    private S3kSlotRenderBuffers slotRenderBuffers;
    private final S3kSlotOptionCycleSystem optionCycleSystem = new S3kSlotOptionCycleSystem();
    private S3kSlotPlayerRuntime slotPlayerRuntime;
    private S3kSlotCollisionSystem slotCollisionSystem;
    private S3kSlotStageController slotStageController;
    private final S3kSlotLayoutRenderer slotLayoutRenderer = new S3kSlotLayoutRenderer();
    private final S3kSlotLayoutAnimator layoutAnimator = new S3kSlotLayoutAnimator();
    private boolean exitTriggered;
    private AbstractPlayableSprite slotPlayer;
    private S3kSlotBonusCageObjectInstance slotCage;
    private boolean continueAwarded;
    private boolean exitFadeStarted;
    // ROM loc_4BC1E's goal-fade trigger executes `move.w #60,$38(a0)`
    // (sonic3k.asm:98971). $38 is the character_id offset
    // (sonic3k.constants.asm:66), so the high byte of 60 (=$003C) overwrites
    // character_id with 0. From that frame on, the shared animate dispatcher
    // sub_4B99E (sonic3k.asm:98683) reads character_id==0 and jumps to
    // Animate_Sonic instead of Animate_Knuckles/Tails, so the frozen player's
    // roll animation switches from AniKnux02/AniTails02 to AniSonic02. Latched
    // once when the fade begins.
    private boolean goalFadeRollOverrideApplied;
    private final List<S3kSlotRingRewardObjectInstance> slotRingRewards = new ArrayList<>();
    private final List<S3kSlotSpikeRewardObjectInstance> slotSpikeRewards = new ArrayList<>();
    private short[] pointGrid;
    private S3kSlotRenderBuffers.VisibleCells visibleCells = S3kSlotRenderBuffers.VisibleCells.empty();
    private int lastFrameCounter = -1;
    private final List<SuppressedSidekick> suppressedSidekicks = new ArrayList<>();
    // Trace-replay-only bootstrap seed for globalVIntRunCounter() -- see
    // primeVIntRunCountForReplay(). null on the live path, where
    // globalVIntRunCounter() falls back to the raw ObjectManager.vblaCounter
    // approximation documented on that method.
    private Long vIntRunCountBase;
    private int vIntRunCountBaseVbla;

    public void bootstrap() {
        initialized = false;
        originalPlayer = null;
        slotPlayer = null;
        slotCage = null;
        continueAwarded = false;
        exitFadeStarted = false;
        goalFadeRollOverrideApplied = false;
        slotRingRewards.clear();
        slotSpikeRewards.clear();
        pointGrid = null;
        visibleCells = S3kSlotRenderBuffers.VisibleCells.empty();
        lastFrameCounter = -1;
        suppressedSidekicks.clear();
        vIntRunCountBase = null;
        vIntRunCountBaseVbla = 0;
        slotStageState = S3kSlotStageState.bootstrap();
        slotRenderBuffers = S3kSlotRenderBuffers.fromRomData();
        optionCycleSystem.bootstrap(slotStageState);
        slotCollisionSystem = new S3kSlotCollisionSystem(slotRenderBuffers, slotStageState);
        slotPlayerRuntime = new S3kSlotPlayerRuntime(slotStageState, slotCollisionSystem);
        // ROM runs sub_4BDCA (ring) + sub_4BE3A (tile dispatch, incl. bumper launch)
        // inside the player object tick, before MoveSprite2 (sonic3k.asm:98776-98780).
        // Splice that work into the player runtime's movement branch so a bumper's
        // launch velocity advances the player's position on the firing frame instead
        // of lagging one frame (slots trace f446 bumper: y_pos was one MoveSprite2
        // step behind the ROM because the dispatch ran after applyVelocityStep).
        slotPlayerRuntime.setPreMoveInteractionHook(this::runPreMovePlayerInteractions);
        exitTriggered = false;
        slotStageController = new S3kSlotStageController(slotStageState);
        slotStageController.bootstrap();
        slotStageController.setActiveLayout(slotRenderBuffers.layout());
        bootstrapGameplayMode = SessionManager.getCurrentGameplayMode();
        if (bootstrapGameplayMode == null) {
            return;
        }

        suppressCpuSidekicks();

        String mainCode = ActiveGameplayTeamResolver.resolveMainCharacterCode(GameServices.configuration());
        if (bootstrapGameplayMode.getSpriteManager().getSprite(mainCode) instanceof AbstractPlayableSprite mainPlayer) {
            originalPlayer = mainPlayer;
            short slotStartX = S3kSlotRomData.SLOT_BONUS_PLAYER_START_X;
            short slotStartY = S3kSlotRomData.SLOT_BONUS_PLAYER_START_Y;
            short centerX = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X;
            short centerY = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y;
            slotPlayer = S3kSlotBonusPlayer.create(mainCode, slotStartX, slotStartY, slotPlayerRuntime);
            copyLivePlayerState(mainPlayer, slotPlayer);
            slotPlayer.setCentreX(slotStartX);
            slotPlayer.setCentreY(slotStartY);
            slotPlayerRuntime.initialize(slotPlayer);
            slotPlayerRuntime.resetSlotOrigin(slotPlayer);
            bootstrapGameplayMode.getSpriteManager().addSprite(slotPlayer);
            bootstrapGameplayMode.getCamera().setFocusedSprite(slotPlayer);
            bootstrapGameplayMode.getCamera().setX((short) (slotPlayer.getCentreX() - 0xA0));
            bootstrapGameplayMode.getCamera().setY((short) (slotPlayer.getCentreY() - 0x70));
            slotCage = new S3kSlotBonusCageObjectInstance(
                    new ObjectSpawn(centerX, centerY, 0, 0, 0, false, 0),
                    slotStageController);
            slotCage.setServices(new DefaultObjectServices(
                    bootstrapGameplayMode, EngineServices.current()));
            slotCage.suppressObjectManagerUpdate();
            registerDynamicSlotObject(slotCage);
            slotCage.suppressInitialCaptureOnce();
            ensureForegroundGlassPriority();
            initialized = true;
        }
    }

    public void update(int frameCounter) {
        lastFrameCounter = frameCounter;

        if (slotPlayerRuntime != null && slotPlayerRuntime.isExiting()) {
            slotPlayerRuntime.tickExitFrame(slotPlayer);
            // ROM loc_4BC1E (sonic3k.asm:98964-98972): when the goal-exit
            // rotation wind-down reaches SStage_scalar_index_1 == $1800, the
            // trigger runs `move.w #60,$38(a0)` (sonic3k.asm:98971). $38 is the
            // character_id field (sonic3k.constants.asm:66), so 60 (=$003C)'s
            // high byte clobbers character_id to 0, and the shared animate
            // dispatch sub_4B99E (sonic3k.asm:98683) thereafter runs
            // Animate_Sonic for the frozen player -- switching the roll mapping
            // sequence to AniSonic02 for the remaining fade frames. Latched at
            // the same frame the fade begins.
            if (slotPlayerRuntime.isExitFading() && !goalFadeRollOverrideApplied) {
                applyGoalFadeSonicRollOverride();
                goalFadeRollOverrideApplied = true;
            }
            var fade = GameServices.fadeOrNull();
            if (slotPlayerRuntime.isExitFading() && !exitFadeStarted && fade != null) {
                fade.startFadeToBlack(null, 0, S3kSlotExitSequence.FADE_FRAMES);
                exitFadeStarted = true;
            }
            if (slotPlayerRuntime.isExitComplete()) {
                exitTriggered = true;
            }
            // ROM's per-frame object dispatch always falls through to the shared
            // loc_4B97C tail -- Animate, DPLC, sub_4BBF4 (camera track), Draw_Sprite
            // -- no matter which routine(a0) branch ran, including the goal-exit
            // handler (loc_4BC1E, sonic3k.asm:98964-99005) and its self-modified
            // palette-fade successor (loc_4BC54, sonic3k.asm:98981-99005, which
            // itself ends with `bra.w loc_4B97C`). Skipping updateCamera() here
            // left the engine's camera frozen at whatever value it held the frame
            // before the goal fired, instead of the ROM's actively-recomputed (but
            // numerically stable, since the player's position is frozen too) value
            // (TestS3kSlotsBonusTraceReplay frame 868: expected camera=(04D2,02C8)
            // vs the engine's stale (04D3,02C9) held from frame 867).
            updateCamera();
            updateVisuals();
            return;
        }

        // ROM line 98745: move.b #0,$30(a0) — clear collision tile at start of frame

        // Option cycle system
        if (!slotStageController.isReelsFrozen()) {
            optionCycleSystem.tick(slotStageState, globalVIntRunCounter());
            if (optionCycleSystem.isResolved(slotStageState)) {
                slotStageController.latchResolvedPrize(
                        optionCycleSystem.lastPrizeResult(slotStageState),
                        optionCycleSystem.completedCycles(slotStageState));
            }
        }

        // Cage object
        if (slotCage != null && slotPlayer != null) {
            slotCage.tickSlotRuntime(frameCounter, slotPlayer,
                    currentPlayerOriginX(), currentPlayerOriginY());
            slotStageState.setEventsBg(slotCage.getCurrentX(), slotCage.getCurrentY());
        }

        // Reward objects
        updateRewards(frameCounter);

        // Ring pickup (sub_4BDCA) + tile dispatch (sub_4BE3A) + throttle tick now
        // run inside the player runtime's movement branch via the pre-move hook
        // (runPreMovePlayerInteractions), which fires between air-gravity collision
        // and MoveSprite2 (sonic3k.asm:98776-98780) -- so a bumper's launch velocity
        // advances the player's position on the firing frame. When the player is
        // object-controlled (cage grab) or in debug/placement mode, that movement
        // branch (and thus the hook) never runs -- mirroring ROM loc_4BA80, which
        // skips the whole sub_4BABC..sub_4BE3A chain. sub_4BE3A is the ONLY place
        // the $36/$37 throttles bleed down (sonic3k.asm:99194-99206), so on these
        // frames the ROM does not decrement them at all; only the engine-local
        // slot-wall contact latch is reset here.
        if (slotStageState != null && (slotPlayer == null || slotPlayer.isDebugMode()
                || slotPlayer.isObjectControlled())) {
            slotStageState.clearSlotWallContact();
        }

        // ROM sub_4BBF4: custom camera tracking centered on player
        updateCamera();

        // Visuals
        updateVisuals();
    }

    public void queueRingReward() {
        slotStageController.queueRingReward();
    }

    public void queueSpikeReward() {
        slotStageController.queueSpikeReward();
    }

    public void shutdown() {
        for (S3kSlotRingRewardObjectInstance reward : slotRingRewards) {
            unregisterDynamicSlotObject(reward);
        }
        for (S3kSlotSpikeRewardObjectInstance reward : slotSpikeRewards) {
            unregisterDynamicSlotObject(reward);
        }
        unregisterDynamicSlotObject(slotCage);
        if (slotPlayer != null && bootstrapGameplayMode != null) {
            bootstrapGameplayMode.getSpriteManager().removeSprite(slotPlayer.getCode());
            if (originalPlayer != null) {
                bootstrapGameplayMode.getSpriteManager().addSprite(originalPlayer);
                bootstrapGameplayMode.getCamera().setFocusedSprite(originalPlayer);
            }
        }
        restoreSuppressedSidekicks();
        slotPlayer = null;
        slotCage = null;
        continueAwarded = false;
        exitFadeStarted = false;
        goalFadeRollOverrideApplied = false;
        slotRingRewards.clear();
        slotSpikeRewards.clear();
        pointGrid = null;
        visibleCells = S3kSlotRenderBuffers.VisibleCells.empty();
        lastFrameCounter = -1;
        slotStageState = null;
        slotRenderBuffers = null;
        slotPlayerRuntime = null;
        slotCollisionSystem = null;
        slotStageController = null;
        exitTriggered = false;
        originalPlayer = null;
        bootstrapGameplayMode = null;
        initialized = false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public S3kSlotBonusCageObjectInstance activeSlotCageForTest() {
        return slotCage;
    }

    public S3kSlotStageState stageStateForTest() {
        return slotStageState;
    }

    public SlotVisualState slotVisualState() {
        if (slotStageState == null) {
            return new SlotVisualState(S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X,
                    S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y, 0x40, false);
        }
        return new SlotVisualState(slotStageState.eventsBgX(), slotStageState.eventsBgY(),
                slotStageState.scalarIndex1(), slotStageState.paletteCycleEnabled());
    }

    public int paletteCycleMode() {
        return slotStageState != null && slotStageState.paletteCycleEnabled() ? 1 : 0;
    }

    public void setPaletteCycleEnabledForTest(boolean enabled) {
        if (slotStageController != null) {
            slotStageController.setPaletteCycleEnabled(enabled);
        }
    }

    public S3kSlotRenderBuffers renderBuffersForTest() {
        return slotRenderBuffers;
    }

    public S3kSlotPlayerRuntime slotPlayerRuntimeForTest() {
        return slotPlayerRuntime;
    }

    /**
     * Runs the movement-branch ring/tile-dispatch hook (ROM sub_4BDCA + sub_4BE3A)
     * directly, without driving a full player physics tick. Tests that inject a
     * ground-projected origin and a grid tile use this to exercise ring pickup /
     * tile dispatch in isolation, matching the point where the player runtime
     * invokes it in production (between air collision and MoveSprite2).
     */
    public void runPreMovePlayerInteractionsForTest() {
        runPreMovePlayerInteractions();
    }

    public S3kSlotOptionCycleSystem optionCycleSystemForTest() {
        return optionCycleSystem;
    }

    public S3kSlotRingRewardObjectInstance activeSlotRingRewardForTest() {
        return slotRingRewards.isEmpty() ? null : slotRingRewards.get(0);
    }

    public S3kSlotSpikeRewardObjectInstance activeSlotSpikeRewardForTest() {
        return slotSpikeRewards.isEmpty() ? null : slotSpikeRewards.get(0);
    }

    public List<S3kSlotRingRewardObjectInstance> activeSlotRingRewardsForTest() {
        return List.copyOf(slotRingRewards);
    }

    public List<S3kSlotSpikeRewardObjectInstance> activeSlotSpikeRewardsForTest() {
        return List.copyOf(slotSpikeRewards);
    }

    public short[] activePointGridForTest() {
        return pointGrid;
    }

    public byte[] activeLayoutForTest() {
        return slotRenderBuffers != null ? slotRenderBuffers.layout() : null;
    }

    S3kSlotRenderBuffers.VisibleCells activeVisibleCellsForTest() {
        return visibleCells;
    }

    public int lastFrameCounterForTest() {
        return lastFrameCounter;
    }

    public boolean isExitTriggered() {
        return exitTriggered;
    }

    public boolean hasCompletedExitFadeToBlack() {
        return exitFadeStarted && slotPlayerRuntime != null && slotPlayerRuntime.isExitComplete();
    }

    public S3kSlotLayoutAnimator activeLayoutAnimatorForTest() {
        return layoutAnimator;
    }

    public S3kSlotExitSequence activeExitSequenceForTest() {
        return slotPlayerRuntime != null ? slotPlayerRuntime.activeExitSequence() : null;
    }

    public S3kSlotStageController stageController() {
        return slotStageController;
    }

    public S3kSlotMachineDisplayState slotMachineDisplayStateForTest() {
        return slotMachineDisplayState();
    }

    public S3kSlotMachineDisplayState slotMachineDisplayState() {
        S3kSlotLayoutRenderer.TransformedStagePoint anchor = currentMachineAnchor();
        return S3kSlotMachineDisplayState.fromState(slotStageState, anchor.worldX(), anchor.worldY());
    }

    public void syncSlotMachinePanel(S3kSlotMachinePanelAnimator animator) {
        if (animator != null) {
            animator.syncPanelPatterns(slotStageState);
        }
    }

    public void startGoalExitForTest() {
        if (slotPlayerRuntime != null && slotPlayer != null) {
            slotPlayerRuntime.startGoalExit(slotPlayer);
        }
    }

    public void render(com.openggf.camera.Camera camera) {
        renderSlotLayout(camera);
    }

    public void renderSlotLayout(com.openggf.camera.Camera camera) {
        LevelManager levelManager = GameServices.levelOrNull();
        if (camera == null || levelManager == null) {
            return;
        }
        // Only the slot layout pass is rendered here. Cage/reward objects stay on
        // the normal object pipeline so this hook matches the ROM's post-sprite pass.
        slotLayoutRenderer.renderVisibleCells(visibleCells, camera, levelManager.getObjectRenderManager());
    }

    public void renderSlotMachineFaceForeground() {
        // The visible machine panel is part of the slot-machine foreground tiles.
        // Only the reel window is overlaid in renderAfterForeground().
    }

    public int ensureForegroundGlassPriority() {
        LevelManager levelManager = GameServices.levelOrNull();
        if (levelManager == null) {
            return 0;
        }

        int changed = 0;
        for (int y = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y - GLASS_PRIORITY_HALF_EXTENT_Y;
             y <= S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y + GLASS_PRIORITY_HALF_EXTENT_Y;
             y += TILE_SIZE) {
            for (int x = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X - GLASS_PRIORITY_HALF_EXTENT_X;
                 x <= S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X + GLASS_PRIORITY_HALF_EXTENT_X;
                 x += TILE_SIZE) {
                int descriptor = levelManager.getForegroundTileDescriptorFromTilemapAtWorld(x, y);
                if ((descriptor & 0x7FF) == 0 || (descriptor & PRIORITY_BIT) != 0) {
                    continue;
                }
                if (levelManager.setForegroundTileDescriptorAtWorld(x, y, descriptor | PRIORITY_BIT)) {
                    changed++;
                }
            }
        }
        if (changed > 0) {
            levelManager.uploadForegroundTilemap();
        }
        return changed;
    }

    private S3kSlotLayoutRenderer.TransformedStagePoint currentMachineAnchor() {
        int panelX = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X + S3kSlotRomData.SLOT_MACHINE_PANEL_CENTER_OFFSET_X;
        int panelY = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y + S3kSlotRomData.SLOT_MACHINE_PANEL_CENTER_OFFSET_Y;
        if (bootstrapGameplayMode != null) {
            int cameraX = bootstrapGameplayMode.getCamera().getX();
            int cameraY = bootstrapGameplayMode.getCamera().getY();
            return new S3kSlotLayoutRenderer.TransformedStagePoint(
                    panelX,
                    panelY,
                    S3kSlotMachineRenderer.computeDisplayScreenX(panelX, cameraX),
                    S3kSlotMachineRenderer.computeDisplayScreenY(panelY, cameraY));
        }
        return new S3kSlotLayoutRenderer.TransformedStagePoint(
                panelX,
                panelY,
                S3kSlotMachineRenderer.computeDisplayScreenX(panelX, 0),
                S3kSlotMachineRenderer.computeDisplayScreenY(panelY, 0));
    }

    /**
     * ROM movement-path tail (loc_4BA98, sonic3k.asm:98776-98780): sub_4BDCA (ring
     * pickup) then sub_4BE3A (tile dispatch + throttle bleed-down) run here, before
     * {@code jsr MoveSprite2} folds the just-updated x_vel/y_vel into x_pos/y_pos.
     * Registered as the player runtime's pre-move hook so a bumper launch
     * (loc_4BE5A) overwrites x_vel/y_vel in time for this frame's velocity step.
     * Only reached on free-movement frames; object-controlled (cage grab) and debug
     * frames skip the movement branch entirely and are covered by the throttle/
     * slot-wall fallback in {@link #update}, mirroring ROM loc_4BA80.
     */
    private void runPreMovePlayerInteractions() {
        // ROM sub_4BE3A (sonic3k.asm:99194-99206) reads $30(a0) -- the special tile
        // id the corner scan stored this frame -- and branches:
        //
        //     move.b  $30(a0),d0
        //     bne.s   loc_4BE5A      ; a special tile is under the player: dispatch,
        //                            ; and DO NOT touch the throttles
        //     subq.b  #1,$36(a0)     ; otherwise bleed both throttles down
        //     ...
        //     subq.b  #1,$37(a0)
        //
        // The decrement is therefore gated on "no special tile this frame", not
        // unconditional. While the player rests in continuous contact with a
        // reversal tile (id 6) $30(a0) is non-zero every frame, $37(a0) stays
        // pinned at the $1E loaded at sonic3k.asm:99261, and the
        // `tst.b $37(a0) / bne` gate at :99259 suppresses every further
        // `neg.w (SStage_scalar_index_1)` for as long as the contact lasts.
        // Ticking unconditionally let the reversal re-fire exactly $1E frames
        // after the first one, flipping the stage rotation a second time the ROM
        // never performs.
        if (slotCollisionSystem != null && slotStageState != null
                && slotStageState.lastCollisionTileId() == 0) {
            slotCollisionSystem.tickFrameState();
        }
        if (slotRenderBuffers != null && slotPlayer != null && !slotPlayer.isDebugMode()
                && !slotPlayer.isObjectControlled()) {
            checkRingPickup();
        }
        if (slotPlayer != null && slotStageState != null && !slotPlayer.isDebugMode()
                && slotStageState.lastCollisionTileId() > 0) {
            dispatchTileInteraction();
            int collisionTileId = slotStageState.lastCollisionTileId();
            if (collisionTileId < 1 || collisionTileId > 3) {
                slotStageState.clearSlotWallContact();
            }
        } else if (slotStageState != null) {
            slotStageState.clearSlotWallContact();
        }
    }

    private void checkRingPickup() {
        // ROM sub_4BDCA reads x_pos(a0)/y_pos(a0) as they stand right after
        // sub_4BABC's ground-velocity projection (sonic3k.asm:98776-98778), before
        // MoveSprite2 folds air x_vel/y_vel into position (sonic3k.asm:98780). Using
        // the fully-stepped position here (currentPlayerOriginX/Y, which reflects
        // this frame's air-velocity step too) lets the engine reach a ring cell one
        // step early/late relative to ROM. See S3kSlotPlayerRuntime.groundProjectedOriginX/Y.
        S3kSlotCollisionSystem.RingCheck ring = slotCollisionSystem.checkRingPickup(
                slotPlayerRuntime.groundProjectedOriginX(), slotPlayerRuntime.groundProjectedOriginY());
        if (ring.foundRing()) {
            slotCollisionSystem.consumeRing(ring);
            slotRenderBuffers.startRingAnimationAt(ring.layoutIndex());
            slotPlayer.addRings(1);
            // Track on coordinator so rings persist after exit (ROM: GiveRing)
            var bonusStage = GameServices.bonusStageOrNull();
            if (bonusStage != null) {
                bonusStage.addRings(1);
            }
            if (GameServices.audio() != null) {
                GameServices.audio().playSfx(GameSound.RING);
            }
            // ROM lines 99168-99174: 50-ring continue bonus (once per stage)
            if (slotPlayer.getRingCount() >= 50 && !continueAwarded) {
                continueAwarded = true;
                bonusStage = GameServices.bonusStageOrNull();
                if (bonusStage != null) {
                    bonusStage.addLife();
                }
                if (GameServices.audio() != null) {
                    GameServices.audio().playSfx(Sonic3kSfx.CONTINUE.id);
                }
            }
        }
    }

    /**
     * Dispatch tile interaction based on collision detected during player physics.
     * ROM sub_4BE3A reads $30(a0) which was set by sub_4BDA2 during collision, and
     * (for the bumper-launch branch) reads x_pos(a0)/y_pos(a0) directly at
     * sonic3k.asm:99224-99225 -- the same pre-MoveSprite2 snapshot sub_4BDCA uses
     * (see checkRingPickup and S3kSlotPlayerRuntime.groundProjectedOriginX/Y).
     */
    private void dispatchTileInteraction() {
        int tileId = slotStageState.lastCollisionTileId();
        int layoutIndex = slotStageState.lastCollisionIndex();
        if (tileId <= 0 || tileId > 6 || layoutIndex < 0) return;

        int expandedIndex = slotRenderBuffers != null ? slotRenderBuffers.compactToExpandedIndex(layoutIndex) : -1;
        if (expandedIndex < 0) {
            return;
        }
        short tileAnchorX = S3kSlotCollisionSystem.tileResponseAnchorX(expandedIndex);
        short tileAnchorY = S3kSlotCollisionSystem.tileResponseAnchorY(expandedIndex);

        S3kSlotCollisionSystem.TileResponse response = slotCollisionSystem.resolveTileResponse(
                tileId, (short) slotPlayerRuntime.groundProjectedOriginX(),
                (short) slotPlayerRuntime.groundProjectedOriginY(), tileAnchorX, tileAnchorY);

        handleTileResponse(response, layoutIndex, tileId);
        slotStageController.setScalarIndex(slotStageState.scalarIndex1());
    }

    private void handleTileResponse(S3kSlotCollisionSystem.TileResponse response, int layoutIndex, int tileId) {
        switch (response.effect()) {
            case BUMPER_LAUNCH -> {
                slotPlayer.setXSpeed(response.launchXVel());
                slotPlayer.setYSpeed(response.launchYVel());
                slotPlayer.setAir(true);
                if (GameServices.audio() != null) GameServices.audio().playSfx(Sonic3kSfx.BUMPER.id);
                slotRenderBuffers.startBumperAnimationAt(layoutIndex);
            }
            case GOAL_EXIT -> {
                if (GameServices.audio() != null) GameServices.audio().playSfx(Sonic3kSfx.GOAL.id);
                slotPlayerRuntime.startGoalExit(slotPlayer);
                exitFadeStarted = false;
                goalFadeRollOverrideApplied = false;
            }
            case SPIKE_REVERSAL -> {
                if (GameServices.audio() != null) GameServices.audio().playSfx(Sonic3kSfx.LAUNCH_GO.id);
                slotRenderBuffers.startSpikeAnimationAt(layoutIndex);
            }
            case SLOT_REEL_INCREMENT -> {
                if (slotStageState.shouldTriggerSlotWall(layoutIndex)) {
                    slotStageState.incrementSlotValue();
                    if (tileId >= 1 && tileId <= 3) {
                        slotRenderBuffers.startSlotWallAnimationAt(layoutIndex, tileId + 1);
                    }
                    if (GameServices.audio() != null) GameServices.audio().playSfx(Sonic3kSfx.FLIPPER.id);
                }
            }
            default -> {}
        }
    }

    /**
     * ROM sub_4BBF4 (lines 98937-98954): Custom camera tracking.
     * Centers the player at screen offset (0xA0, 0x70). Only adjusts when
     * player moves beyond those offsets.
     */
    private void updateCamera() {
        if (slotPlayer == null || bootstrapGameplayMode == null) {
            return;
        }
        int playerX = currentPlayerOriginX();
        int playerY = currentPlayerOriginY();
        int camX = bootstrapGameplayMode.getCamera().getX();
        int camY = bootstrapGameplayMode.getCamera().getY();
        int targetX = playerX - 0xA0;
        if (targetX >= 0) {
            camX = targetX;
        }
        int targetY = playerY - 0x70;
        if (targetY >= 0) {
            camY = targetY;
        }
        bootstrapGameplayMode.getCamera().setX((short) camX);
        bootstrapGameplayMode.getCamera().setY((short) camY);
    }

    private int currentPlayerOriginX() {
        if (slotPlayerRuntime != null) {
            return slotPlayerRuntime.slotOriginX() >> 16;
        }
        return slotPlayer != null ? slotPlayer.getCentreX() : 0;
    }

    private int currentPlayerOriginY() {
        if (slotPlayerRuntime != null) {
            return slotPlayerRuntime.slotOriginY() >> 16;
        }
        return slotPlayer != null ? slotPlayer.getCentreY() : 0;
    }

    private void updateVisuals() {
        if (bootstrapGameplayMode != null) {
            int stageCameraX = bootstrapGameplayMode.getCamera().getX();
            int stageCameraY = bootstrapGameplayMode.getCamera().getY();
            if (slotRenderBuffers != null) {
                if (pointGrid == null || pointGrid.length < 16 * 16 * 2) {
                    pointGrid = new short[16 * 16 * 2];
                }
                slotLayoutRenderer.updateAnimations(slotStageState.angle());
                slotLayoutRenderer.tickTransientAnimations(slotRenderBuffers);
                slotLayoutRenderer.buildPointGridInto(pointGrid, slotStageState.angle(),
                        stageCameraX, stageCameraY);
                slotRenderBuffers.stageViewport(stageCameraX, stageCameraY);
                slotRenderBuffers.stagePointGrid(pointGrid);
                visibleCells = slotLayoutRenderer.buildVisibleCells(slotRenderBuffers);
            } else {
                visibleCells = S3kSlotRenderBuffers.VisibleCells.empty();
            }
        }
    }

    private void updateRewards(int frameCounter) {
        if (bootstrapGameplayMode == null || slotPlayer == null) {
            return;
        }
        // Tick already-active rewards BEFORE draining newly queued ones, so an
        // object spawned by this call is not ticked twice in one pass. Whether
        // it is ticked ONCE on its spawn frame is then decided by slot order in
        // drainPending*Rewards below, which is what the ROM's single
        // ascending-index object dispatch does.
        updateActiveRewards(slotRingRewards, frameCounter);
        updateActiveRewards(slotSpikeRewards, frameCounter);
        drainPendingRingRewards(frameCounter);
        drainPendingSpikeRewards(frameCounter);
    }

    private void drainPendingRingRewards(int frameCounter) {
        int[] ringPos;
        while ((ringPos = slotStageController.consumePendingRingReward()) != null) {
            S3kSlotRingRewardObjectInstance reward = new S3kSlotRingRewardObjectInstance(
                    new ObjectSpawn(S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X, S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y,
                            0, 0, 0, false, 0),
                    slotStageController);
            reward.setServices(slotObjectServices());
            reward.suppressObjectManagerUpdate();
            if (ringPos.length == 4) {
                reward.activate(ringPos[0], ringPos[1], ringPos[2], ringPos[3]);
            } else {
                reward.activate();
            }
            registerDynamicSlotObject(reward);
            slotStageController.onRewardSpawned();
            slotRingRewards.add(reward);
            runSpawnFrameDispatchIfSlotNotYetExecuted(reward, frameCounter);
        }
    }

    private void drainPendingSpikeRewards(int frameCounter) {
        int[] spikePos;
        while ((spikePos = slotStageController.consumePendingSpikeReward()) != null) {
            S3kSlotSpikeRewardObjectInstance reward = new S3kSlotSpikeRewardObjectInstance(
                    new ObjectSpawn(S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X, S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y,
                            0, 0, 0, false, 0),
                    slotStageController);
            reward.setServices(slotObjectServices());
            reward.suppressObjectManagerUpdate();
            if (spikePos.length == 4) {
                reward.activate(spikePos[0], spikePos[1], spikePos[2], spikePos[3]);
            } else {
                reward.activate();
            }
            registerDynamicSlotObject(reward);
            slotStageController.onRewardSpawned();
            slotSpikeRewards.add(reward);
            runSpawnFrameDispatchIfSlotNotYetExecuted(reward, frameCounter);
        }
    }

    /**
     * Runs a just-spawned reward's own routine on its spawn frame when the ROM's
     * object dispatch would still reach it this pass.
     *
     * <p>The cage spawns its rewards with {@code jsr (AllocateObject).l}
     * ({@code sonic3k.asm:99474} for {@code Obj_SlotRing},
     * {@code :99429} for {@code Obj_SlotSpike}), which scans
     * {@code Dynamic_object_RAM} forward from its first slot
     * ({@code sonic3k.asm:37911-37914}) -- so the child can land either below or
     * above the cage's own slot. The main object pass walks slots in ascending
     * index order, so a child allocated ABOVE the cage's slot is still ahead of
     * the walk and runs its routine 0 in the very same frame it was created;
     * one allocated BELOW it has already been passed and does not run until the
     * next frame. That single tick is what makes {@code Obj_SlotRing}'s
     * {@code $40} countdown -- seeded to {@code $1A} at {@code sonic3k.asm:99482}
     * and decremented once per routine-0 tick with
     * {@code subq.w #1,$40(a0) / bne.w Draw_Sprite}
     * ({@code sonic3k.asm:35883-35884}) -- reach zero on spawn frame + 25 rather
     * than + 26.
     *
     * <p>The slot indices compared here are the engine's own ROM-modelled
     * allocation, not an assumption about which side the child lands on.
     */
    private void runSpawnFrameDispatchIfSlotNotYetExecuted(
            com.openggf.level.objects.AbstractObjectInstance reward, int frameCounter) {
        if (slotCage == null || slotPlayer == null) {
            return;
        }
        int cageSlot = slotCage.getSlotIndex();
        int rewardSlot = reward.getSlotIndex();
        if (cageSlot < 0 || rewardSlot < 0 || rewardSlot <= cageSlot) {
            return;
        }
        if (reward instanceof S3kSlotRingRewardObjectInstance ringReward) {
            ringReward.tickSlotRuntime(frameCounter, slotPlayer);
        } else if (reward instanceof S3kSlotSpikeRewardObjectInstance spikeReward) {
            spikeReward.tickSlotRuntime(frameCounter, slotPlayer);
        }
    }

    private <T extends com.openggf.level.objects.AbstractObjectInstance> void updateActiveRewards(
            List<T> rewards, int frameCounter) {
        Iterator<T> iterator = rewards.iterator();
        while (iterator.hasNext()) {
            T reward = iterator.next();
            if (reward instanceof S3kSlotRingRewardObjectInstance ringReward) {
                ringReward.tickSlotRuntime(frameCounter, slotPlayer);
            } else if (reward instanceof S3kSlotSpikeRewardObjectInstance spikeReward) {
                spikeReward.tickSlotRuntime(frameCounter, slotPlayer);
            }
            boolean inactive = reward instanceof S3kSlotRingRewardObjectInstance ring && !ring.isActive()
                    || reward instanceof S3kSlotSpikeRewardObjectInstance spike && !spike.isActive();
            if (reward.isDestroyed() || inactive) {
                // Obj_SlotSpike (sonic3k.asm:99568-99604) decrements the cage's
                // active count at the same instant it destroys itself -- no
                // separate sparkle phase -- so this call still applies here for
                // spikes. Ring rewards already reported their active-count
                // decrement at grant time (see S3kSlotRingRewardObjectInstance
                // .tickSlotRuntime); calling onRewardExpired() again here on
                // eventual sparkle-end destruction would double-decrement.
                if (!(reward instanceof S3kSlotRingRewardObjectInstance)) {
                    slotStageController.onRewardExpired();
                }
                unregisterDynamicSlotObject(reward);
                iterator.remove();
            }
        }
    }

    private DefaultObjectServices slotObjectServices() {
        return new DefaultObjectServices(bootstrapGameplayMode, EngineServices.current());
    }

    /**
     * ROM {@code Slots_CycleOptions} (sonic3k.asm:99614-99946) reads {@code
     * V_int_run_count} -- a longword that counts every VBlank since power-on and
     * never resets on level load -- for every reel-spin/target/RNG-mix
     * computation: {@code loc_4C416}'s reel-word seeds (line 99646 {@code
     * move.b (V_int_run_count+3).w,d0}), {@code loc_4C480}'s per-reel velocity
     * offsets and fixed-row scan seed (lines 99679-99702, same byte), and {@code
     * loc_4C4F8}'s random-target draw (line 99722 {@code add.w
     * (V_int_run_count+2).w,d0}) and {@code loc_4C54C}'s post-decelerate
     * countdown extension (lines 99753-99755, same byte). This is a distinct
     * ROM variable from {@code Level_frame_counter} (the level-local counter
     * the reward-spawn cadence gate in {@link
     * com.openggf.game.sonic3k.objects.S3kSlotBonusCageObjectInstance} correctly
     * reads via this method's {@code frameCounter} parameter). Passing the
     * level-local {@code frameCounter} into {@link S3kSlotOptionCycleSystem}
     * instead -- reset to whatever {@code LevelManager.getFrameCounter()} held
     * when the bonus stage loaded, rather than a persistent run count -- fed
     * the wrong seed into the reel-target selection, resolving a different
     * (and differently-timed) prize than ROM and producing a rings-count
     * divergence that starts well before any position/velocity field diverges
     * (TestS3kSlotsBonusTraceReplay frame 269: expected rings=75, actual=76).
     * {@code ObjectManager.vblaCounter} is at least the right *shape* of
     * approximation for {@code V_int_run_count} -- unlike {@code
     * Level_frame_counter} it does not reset on level load -- and it is the
     * same proxy every ordinary object's {@code update(int frameCounter, ...)}
     * dispatch already receives (see {@code ObjectManager.update}), so route
     * the option-cycle system to it explicitly here, since the slots runtime
     * suppresses ObjectManager's own dispatch and drives its objects with a
     * bespoke, level-local counter instead. It is <strong>not</strong> a
     * validated match for {@code V_int_run_count}: {@code
     * GumballMachineObjectInstance}'s frame-0 RNG-reseed comment and the
     * "Gumball Machine Frame-0 RNG Reseed" entry in {@code
     * docs/S3K_KNOWN_DISCREPANCIES.md} document {@code vblaCounter} as a
     * per-gameplay-session counter that resets far more often, and starts from
     * a materially smaller range, than the hardware counter a trace reflects --
     * a disclosed approximation gap, not a proven-accurate substitute. Routing
     * Slots' reel-word seed, per-reel velocity offsets, fixed-row scan seed,
     * random-target draw, and post-decelerate countdown extension through this
     * same proxy inherits that same disclosed gap (see the Slots paragraph
     * added to that discrepancies entry); this is a like-for-like ROM-shape
     * fix relative to the previous {@code Level_frame_counter} bug, not a
     * claim of exact parity with real hardware.
     */
    private int globalVIntRunCounter() {
        int rawVbla = rawVblaCounter();
        if (vIntRunCountBase != null) {
            // Trace replay only: recordedBase + ticks elapsed since the
            // priming call (bonus-stage entry) -- see
            // primeVIntRunCountForReplay(). Mirrors the ROM's free-running
            // V_int_run_count from the recorded entry value instead of the
            // raw per-session vblaCounter approximation below.
            long delta = rawVbla - vIntRunCountBaseVbla;
            return (int) ((vIntRunCountBase.longValue() + delta) & 0xFFFFFFFFL);
        }
        return rawVbla;
    }

    private int rawVblaCounter() {
        if (bootstrapGameplayMode == null) {
            return lastFrameCounter;
        }
        ObjectManager objectManager = bootstrapGameplayMode.getLevelManager().getObjectManager();
        return objectManager != null ? objectManager.getVblaCounter() : lastFrameCounter;
    }

    /**
     * Trace-replay-only bootstrap seam (comparison-bootstrap pattern, same
     * shape as {@code TraceReplaySessionBootstrap.applyInitialRngSeedForReplay}
     * / {@code metadata.rng_seed}). Called once from {@code
     * TraceReplaySessionBootstrap.applyBonusStageEntry} when the trace's
     * {@code metadata.v_int_run_count} is present (recorder v6.32-s3k+,
     * bonus segments only). Primes {@link #globalVIntRunCounter()} so it
     * returns {@code recordedBase + ticks-since-entry} rather than the raw
     * {@code ObjectManager.vblaCounter} value, letting {@link
     * S3kSlotOptionCycleSystem#tick} reproduce the recorded ROM
     * {@code V_int_run_count}-seeded reel outcomes. No effect on live play,
     * which never calls this method.
     *
     * <p>{@code initialVblankCounter} must be the trace's own {@code
     * TraceData.initialVblankCounter()} (trace frame 0's recorded {@code
     * vblank_counter}), NOT a live read of {@code rawVblaCounter()} taken at
     * priming time. {@code applyBonusStageEntry} runs from {@code
     * afterFixtureBuild}, which fires <em>before</em> {@code
     * TraceReplaySessionBootstrap.applyBootstrap} seeds {@code
     * ObjectManager.vblaCounter} via {@code initVblaCounter(trace.initialVblankCounter()
     * - objectPreludeFrames - 1)} (see {@code AbstractTraceReplayTest} steps 4-4b:
     * {@code afterFixtureBuild(trace)} precedes {@code applyBootstrap(trace, ...)}).
     * A live read at priming time therefore captured the pre-seed counter (0 on a
     * freshly loaded bonus-zone level) instead of the trace's real starting value
     * (e.g. 1024 for {@code bonus_slots}), baking a large constant error into every
     * later {@link #globalVIntRunCounter()} read. Because most {@code V_int_run_count}
     * consumers in {@code Slots_CycleOptions} only read the low byte ({@code
     * move.b (V_int_run_count+3).w,d0}, sonic3k.asm:99646/99679/99684/99701/99753 --
     * bits 0-7), a constant error confined to bit 8 and above (1024 = 0x400 = bit 10)
     * left those reads untouched, which is why the first spin's reel *symbols*
     * already matched (044cf51fd). It corrupted the bit-8-and-up consumers instead:
     * the reel-2 velocity offset ({@code move.b (V_int_run_count+2).w,d0 / andi.b
     * #7,d0}, sonic3k.asm:99690-99694, reads bits 8-10) and the random-target draw's
     * word addend ({@code add.w (V_int_run_count+2).w,d0}, sonic3k.asm:99722, reads
     * bits 0-15), which feed the DECELERATE/LOCK_REELS reel-search timing and so
     * shifted when the reels actually finish locking (TestS3kSlotsBonusTraceReplay
     * frame 301: engine's first ring grant at local frame 275+26=301 vs the
     * recorded 282+26=308).
     */
    public void primeVIntRunCountForReplay(long recordedVIntRunCount, int initialVblankCounter) {
        vIntRunCountBase = recordedVIntRunCount;
        vIntRunCountBaseVbla = initialVblankCounter;
    }

    private void registerDynamicSlotObject(com.openggf.level.objects.ObjectInstance object) {
        if (bootstrapGameplayMode == null || object == null) {
            return;
        }
        ObjectManager objectManager = bootstrapGameplayMode.getLevelManager().getObjectManager();
        if (objectManager != null) {
            objectManager.addDynamicObject(object);
        }
    }

    private void unregisterDynamicSlotObject(com.openggf.level.objects.ObjectInstance object) {
        if (bootstrapGameplayMode == null || object == null) {
            return;
        }
        ObjectManager objectManager = bootstrapGameplayMode.getLevelManager().getObjectManager();
        if (objectManager != null) {
            objectManager.removeDynamicObject(object);
        }
    }

    private void copyLivePlayerState(AbstractPlayableSprite source, AbstractPlayableSprite target) {
        target.setTopSolidBit(source.getTopSolidBit());
        target.setLrbSolidBit(source.getLrbSolidBit());
        target.setSpriteRenderer(source.getSpriteRenderer());
        target.setMappingFrame(source.getMappingFrame());
        target.setAnimationFrameCount(source.getAnimationFrameCount());
        target.setAnimationProfile(source.getAnimationProfile());
        target.setAnimationSet(source.getAnimationSet());
        target.setAnimationId(source.getAnimationId());
        target.setAnimationFrameIndex(source.getAnimationFrameIndex());
        target.setAnimationTick(source.getAnimationTick());
        target.setSpindashDustController(source.getSpindashDustController());
        target.setTailsTailsController(source.getTailsTailsController());
        target.setSuperStateController(source.getSuperStateController());
        target.setPowerUpSpawner(source.getPowerUpSpawner());
        target.setDirection(source.getDirection());
        // ROM Obj_Sonic_RotatingSlotBonus uses make_art_tile(...,0,0);
        // the runtime promotes the slot-machine glass FG tilemap cells above the player.
        target.setHighPriority(false);
        target.setAir(source.getAir());
        target.setRolling(source.getRolling());
        target.setControlLocked(false);
        ObjectControlState.none().applyTo(target);
        target.setOnObject(source.isOnObject());
    }

    /**
     * ROM loc_4BC1E's goal-fade trigger overwrites {@code character_id} with 0
     * via {@code move.w #60,$38(a0)} (sonic3k.asm:98971; {@code $38} is the
     * character_id offset, sonic3k.constants.asm:66), so the shared animate
     * dispatcher sub_4B99E (sonic3k.asm:98683) switches the frozen player's
     * per-frame animate call from {@code Animate_Knuckles}/{@code Animate_Tails}
     * to {@code Animate_Sonic}. The object keeps its own {@code mappings}
     * (Map_Knuckles etc.), so only the mapping-frame index sequence changes:
     * the roll script it reads becomes AniSonic02/03 instead of AniKnux02/03
     * (which interleave {@code $9A} as the rest frame) or AniTails02/03. Model
     * that by swapping the ROLL and ROLL2 scripts in the slot player's animation
     * set to the Sonic-half scripts parsed from ROM, leaving anim_frame/timer
     * (the sprite's own state, untouched by the character_id write) in phase.
     * Runs for every character, matching the ROM's unconditional write (a no-op
     * when the player is already Sonic).
     */
    private void applyGoalFadeSonicRollOverride() {
        if (slotPlayer == null) {
            return;
        }
        SpriteAnimationSet currentSet = slotPlayer.getAnimationSet();
        if (currentSet == null) {
            return;
        }
        try {
            RomByteReader reader = RomByteReader.fromRom(GameServices.rom().getRom());
            int base = Sonic3kConstants.SONIC_ANIM_DATA_ADDR;
            int rollId = Sonic3kAnimationIds.ROLL.id();
            int roll2Id = Sonic3kAnimationIds.ROLL2.id();
            SpriteAnimationScript sonicRoll = S3kSpriteDataLoader.parseAnimationScript(
                    reader, base + reader.readU16BE(base + rollId * 2));
            SpriteAnimationScript sonicRoll2 = S3kSpriteDataLoader.parseAnimationScript(
                    reader, base + reader.readU16BE(base + roll2Id * 2));
            // Isolated copy: the live (non-slot) player, restored when the bonus
            // ends, shares its animation set here -- keep its own roll frames.
            SpriteAnimationSet overriddenSet = new SpriteAnimationSet();
            for (var entry : currentSet.getAllScripts().entrySet()) {
                overriddenSet.addScript(entry.getKey(), entry.getValue());
            }
            overriddenSet.addScript(rollId, sonicRoll);
            overriddenSet.addScript(roll2Id, sonicRoll2);
            slotPlayer.setAnimationSet(overriddenSet);
        } catch (Exception ignored) {
            // ROM unavailable in some harnesses: leave the character's own roll.
        }
    }

    private void suppressCpuSidekicks() {
        if (bootstrapGameplayMode == null) {
            return;
        }
        SpriteManager spriteManager = bootstrapGameplayMode.getSpriteManager();
        List<AbstractPlayableSprite> liveSidekicks = List.copyOf(spriteManager.getSidekicks());
        for (AbstractPlayableSprite sidekick : liveSidekicks) {
            String characterName = spriteManager.getSidekickCharacterName(sidekick);
            suppressedSidekicks.add(new SuppressedSidekick(sidekick, characterName));
            spriteManager.removeSprite(sidekick.getCode());
        }
    }

    private void restoreSuppressedSidekicks() {
        if (bootstrapGameplayMode == null || suppressedSidekicks.isEmpty()) {
            suppressedSidekicks.clear();
            return;
        }
        SpriteManager spriteManager = bootstrapGameplayMode.getSpriteManager();
        for (SuppressedSidekick entry : suppressedSidekicks) {
            if (entry.characterName() != null) {
                spriteManager.addSprite(entry.sidekick(), entry.characterName());
            } else {
                spriteManager.addSprite(entry.sidekick());
            }
        }
        suppressedSidekicks.clear();
    }

    private record SuppressedSidekick(AbstractPlayableSprite sidekick, String characterName) {
    }

    public record SlotVisualState(int eventsBgX, int eventsBgY, int scalarIndex1, boolean paletteCycleEnabled) {
    }
}
