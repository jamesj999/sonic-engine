package com.openggf.game.sonic3k.events;

import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.sonic3k.resources.S3kKosTransitionPreflight;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.sonic3k.objects.HCZ2WallObjectInstance;
import com.openggf.game.sonic3k.objects.HczTransitionBubbleInstance;
import com.openggf.game.sonic3k.scroll.SwScrlHcz;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.scroll.ZoneScrollHandler;
import com.openggf.physics.Direction;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * HCZ (Hydrocity Zone) dynamic level events.
 *
 * <p>ROM: HCZ1_Resize / HCZ2_Resize (sonic3k.asm lines 39244-39320)
 * and HCZ1_BackgroundEvent / HCZ2_BackgroundEvent (sonic3k.asm lines 105702-106121).
 *
 * <h3>Act 1 FG (HCZ1_Resize) — 3 stages:</h3>
 * <ul>
 *   <li>Stage 0: Camera X < $360 AND Camera Y >= $3E0 → write underwater palette mutation, advance to 2</li>
 *   <li>Stage 2: Camera moved back above water → revert to 0; OR Camera Y >= $500 AND X >= $900 → revert, advance to 4</li>
 *   <li>Stage 4: terminal idle</li>
 * </ul>
 *
 * <h3>Act 1 BG (HCZ1_BackgroundEvent) — seamless act transition:</h3>
 * <ul>
 *   <li>Stage 0: normal scrolling; when Events_fg_5 set → queue transition, advance to 4</li>
 *   <li>Stage 4: requestSeamlessTransition to HCZ2 with -$3600 X offset, water $6A0</li>
 * </ul>
 *
 * <h3>Act 2 FG (HCZ2_Resize) — 2 stages:</h3>
 * <ul>
 *   <li>Stage 0: Camera X >= $C00 → set Events_fg_5, advance to 2</li>
 *   <li>Stage 2: terminal idle</li>
 * </ul>
 */
public class Sonic3kHCZEvents extends Sonic3kZoneEvents {
    private static final Logger LOG = Logger.getLogger(Sonic3kHCZEvents.class.getName());

    // =========================================================================
    // FG event state machine stages (stride 4, matching S3K convention)
    // =========================================================================
    private static final int FG_STAGE_0 = 0;
    private static final int FG_STAGE_2 = 4;   // stride 4 → "stage 2" = offset 4
    private static final int FG_STAGE_4 = 8;   // stride 4 → "stage 4" = offset 8

    // =========================================================================
    // BG event state machine stages
    // =========================================================================
    private static final int BG_STAGE_NORMAL = 0;
    private static final int BG_STAGE_DO_TRANSITION = 4;

    // =========================================================================
    // Act 1 palette mutation thresholds (ROM: HCZ1_Resize)
    // =========================================================================
    private static final int PAL_MUT_CAM_X_THRESHOLD = 0x360;
    private static final int PAL_MUT_CAM_Y_UNDERWATER = 0x3E0;
    private static final int PAL_MUT_CAM_Y_PAST = 0x500;
    private static final int PAL_MUT_CAM_X_PAST = 0x900;

    // HCZ1 underwater palette mutation, ROM loc_1C892 (sonic3k.asm:39261-39273).
    //
    // FixBugs conditional (sonic3k.asm:38 -- the shipped ROM assembles with
    // FixBugs = 0, so THIS is the branch the engine implements).
    //   Shipped (FixBugs = 0), implemented here: the first colour written is
    //   $B80 (sonic3k.asm:39270-39271, whose own comment reads "Bug: this should
    //   be $680"). The second and third colours, $240 and $220, are outside the
    //   conditional and are the same either way.
    //   Fixed (FixBugs = 1), NOT implemented: $680 instead of $B80
    //   (sonic3k.asm:39267-39268), a darker first colour in the underwater ramp.
    private static final int[] PALETTE_UNDERWATER = {0x0B80, 0x0240, 0x0220};
    // Revert palette colors: $0CEE, $0ACE, $008A
    private static final int[] PALETTE_NORMAL = {0x0CEE, 0x0ACE, 0x008A};
    // Target: Normal_palette_line_4+$10 = palette line 3 (0-indexed), color offset 8 (3 colors)
    // ROM labels are 1-indexed: Normal_palette_line_4 = palette index 3
    private static final int PALETTE_LINE = 3;
    private static final int PALETTE_COLOR_OFFSET = 8;  // $10 / 2 = 8th color

    // =========================================================================
    // Act 2 FG threshold (ROM: HCZ2_Resize)
    // =========================================================================
    private static final int ACT2_CAM_X_WALL_CHASE_END = 0xC00;

    // ROM: sub_714E -> sub_717C. These are the ten HCZ2 layout bytes
    // immediately preceding byte_7498; every entry drives toward -$800.
    private static final int[] HCZ2_SLIDE_BLOCKS = {
            0x1C, 0x72, 0x83, 0x84, 0x8B, 0x91, 0x9F, 0xA0, 0xA5, 0xA6
    };
    private static final int HCZ2_SLIDE_TARGET_HIGH_BYTE = -8;
    private static final int HCZ2_SLIDE_ACCELERATION = 0x40;
    private static final int HCZ2_SLIDE_ANIMATION = 0x1B;
    private static final int HCZ2_SLIDE_EXIT_MOVE_LOCK = 5;

    // =========================================================================
    // Act 2 BG: Wall-chase event constants (ROM: HCZ2_BackgroundEvent/HCZ2_WallMove)
    // =========================================================================

    /** BG event state 0: init — check activation conditions, spawn wall. */
    private static final int BG_WALL_INIT = 0;
    /** BG event state 4: active wall movement. */
    private static final int BG_WALL_MOVE = 4;
    /** BG event state 8: transition from wall-chase to normal deformation. */
    private static final int BG_WALL_TRANSITION = 8;
    /** BG event state $C: BG tile refresh frame. */
    private static final int BG_WALL_REFRESH = 0xC;
    /** BG event state $10: normal Act 2 deformation. */
    private static final int BG_NORMAL = 0x10;

    /** ROM: Camera_X_pos < $C00 for wall event activation. */
    private static final int WALL_ACTIVATE_CAM_X_MAX = 0xC00;
    /** ROM: Camera_Y_pos >= $500 for wall event activation. */
    private static final int WALL_ACTIVATE_CAM_Y_MIN = 0x500;

    /** ROM: Wall starts moving when player X >= $680. */
    private static final int WALL_TRIGGER_PLAYER_X = 0x680;
    /** ROM: Wall speed increases when player X > $A88. */
    private static final int WALL_SPEED_UP_PLAYER_X = 0xA88;

    /** ROM: Base wall speed $E000 (16.16 fixed-point). */
    private static final int WALL_BASE_SPEED = 0xE000;
    /** ROM: Fast wall speed $14000 (16.16 fixed-point). */
    private static final int WALL_FAST_SPEED = 0x14000;

    /** ROM: Wall stops when offset reaches -$600 (-1536 pixels). */
    private static final int WALL_STOP_OFFSET = -0x600;

    /** ROM: Screen_shake_flag = $0E (14 frames) on wall stop. */
    private static final int WALL_STOP_SHAKE_FRAMES = 0x0E;

    /** ROM: BG collision gating — player X range. */
    private static final int BG_COLL_X_MIN = 0x3F0;
    private static final int BG_COLL_X_MAX = 0xC10;
    /** ROM: BG collision gating — player Y range. */
    private static final int BG_COLL_Y_MIN = 0x600;
    private static final int BG_COLL_Y_MAX = 0x840;

    // =========================================================================
    // Seamless transition offsets (ROM: HCZ1BGE_DoTransition)
    // =========================================================================
    private static final int TRANSITION_OFFSET_X = -0x3600;
    private static final int TRANSITION_WATER_LEVEL = 0x6A0;
    // =========================================================================
    // State
    // =========================================================================
    private int fgRoutine;
    private int bgRoutine;

    /** ROM: Events_fg_5 — set by Obj_LevelResultsCreate to trigger BG act transition. */
    private boolean eventsFg5;

    /** Prevents requesting the transition more than once. */
    private boolean transitionRequested;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinal")
    private S3kKosModuleQueue transitionKosQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle transitionKosHandle;
    private long transitionKosOrdinal = -1;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinals")
    private S3kKosDecompressionQueue transitionDirectQueue;
    @RewindTransient(reason = "handles are rebound to the restored session ledger by captured ordinals")
    private HardwareWorkHandle transitionChunkHandle;
    @RewindTransient(reason = "handles are rebound to the restored session ledger by captured ordinals")
    private HardwareWorkHandle transitionBlockHandle;
    private long transitionChunkOrdinal = -1;
    private long transitionBlockOrdinal = -1;

    /**
     * ROM: Boss_flag — set by the boss object when the fight begins.
     * Gates FG events during boss fights (prevents boundary changes
     * from interfering with the arena lock).
     */
    private boolean bossFlag;

    // =========================================================================
    // Act 2 BG wall-chase state
    // =========================================================================

    /** Act 2 BG event routine counter (stride 4). */
    private int act2BgRoutine;

    /**
     * Wall movement offset accumulator (ROM: Events_bg+$00).
     * 16.16 fixed-point. Negative values mean the wall has advanced leftward.
     */
    private int wallOffsetFixed;

    /** Integer pixel offset extracted from wallOffsetFixed. */
    private int wallOffsetPixels;

    /** Whether the wall has started moving (player crossed trigger X). */
    private boolean wallMoving;

    /** Whether the wall has reached its stop position (prevents restart loop). */
    private boolean wallStopped;

    /** Timed screen shake countdown (frames remaining, 0 = inactive). */
    private int shakeTimer;

    /** Reference to the spawned wall collision object. */
    @RewindTransient(reason = "cached live object reference; object lifetime and state "
            + "are owned by ObjectManager rewind")
    private HCZ2WallObjectInstance wallObject;

    /** Prevents Act 2 BG logic from double-advancing when pre-physics already ran it. */
    private boolean act2BgUpdatedPrePhysics;

    /**
     * BG high-priority wall-chase overlay flag. Drives an extra BG pass
     * (high-priority tiles only) rendered after sprites so the approaching
     * water wall covers FG terrain and gameplay objects, matching VDP layer
     * order (BG-low -&gt; FG-low -&gt; BG-high -&gt; FG-high). Set when the
     * wall-chase activation conditions are met (HCZ2BGE_WallMoveInit) and
     * cleared on transition back to normal deformation
     * (HCZ2BGE_NormalTransition). Owned exclusively by {@link Sonic3kHCZEvents};
     * consumers read it via
     * {@link com.openggf.game.sonic3k.runtime.HczZoneRuntimeState#wallChaseBgOverlayActive()}.
     */
    private boolean wallChaseBgOverlayActive;

    // =========================================================================
    // Post-transition HCZ miniboss carrier children
    // =========================================================================

    /** Whether either retained loc_6A7C4 carrier child is active. */
    private boolean cutsceneActive;
    /** The init dispatch installs loc_6A872; movement begins on the next object pass. */
    private boolean carrierMovementPending;
    /** Prevents the later dynamic-event pass from dispatching a carrier twice. */
    private boolean carrierUpdatedBeforeDynamicObjects;
    private boolean carrierP1Active;
    private boolean carrierP2Active;
    private int carrierP1XFixed;
    private int carrierP1YFixed;
    private int carrierP1XVelocity;
    private int carrierP2XFixed;
    private int carrierP2YFixed;
    private int carrierP2XVelocity;
    private boolean carrierP1TargetSide;
    private boolean carrierP2TargetSide;
    private int carrierP1BoundsYOffset;
    private int carrierP2BoundsYOffset;
    /** Child1_Act2LevelSize objects created when Player 1's carrier releases. */
    private boolean levelSizeTransitionActive;
    private int levelSizeMaxXGradient;
    private int levelSizeMinYGradient;
    private int levelSizeMaxYGradient;

    /** ROM loc_6A80C/loc_6A872: target is Camera_X_pos+$A0; release at y_pos $828. */
    private static final int CARRIER_TARGET_CAMERA_OFFSET = 0xA0;
    private static final int CARRIER_RELEASE_Y = 0x828;
    private static final int CARRIER_Y_VELOCITY = 0x200;
    private static final int CARRIER_X_ACCELERATION = 0x100;
    /** Obj_Wait starts at $2F, so loc_6A2A0 creates bubbles for $30 dispatches. */
    private static final int TRANSITION_BUBBLE_SPAWN_FRAMES = 0x30;
    private static final int TRANSITION_BUBBLE_AXIS_CAMERA_OFFSET = 0xA0;
    private static final int TRANSITION_BUBBLE_WATER_OFFSET = 8;

    /** Remaining loc_6A2A0 controller dispatches that create loc_6A710 children. */
    private int transitionBubbleSpawnFrames;
    private final IntSupplier randomWordSource;

    public Sonic3kHCZEvents(IntSupplier randomWordSource) {
        super();
        this.randomWordSource = Objects.requireNonNull(randomWordSource, "randomWordSource");
    }

    @Override
    public void init(int act) {
        super.init(act);
        fgRoutine = FG_STAGE_0;
        bgRoutine = BG_STAGE_NORMAL;
        eventsFg5 = false;
        transitionRequested = false;
        transitionKosQueue = null;
        transitionKosHandle = null;
        transitionKosOrdinal = -1;
        transitionDirectQueue = null;
        transitionChunkHandle = null;
        transitionBlockHandle = null;
        transitionChunkOrdinal = -1;
        transitionBlockOrdinal = -1;
        bossFlag = false;
        cutsceneActive = false;
        carrierMovementPending = false;
        carrierUpdatedBeforeDynamicObjects = false;
        carrierP1Active = false;
        carrierP2Active = false;
        transitionBubbleSpawnFrames = 0;
        levelSizeTransitionActive = false;
        levelSizeMaxXGradient = 0;
        levelSizeMinYGradient = 0;
        levelSizeMaxYGradient = 0;

        // Act 2 BG wall-chase state
        act2BgRoutine = BG_WALL_INIT;
        wallOffsetFixed = 0;
        wallOffsetPixels = 0;
        wallMoving = false;
        wallStopped = false;
        shakeTimer = 0;
        wallObject = null;
        act2BgUpdatedPrePhysics = false;
        wallChaseBgOverlayActive = false;
    }

    @Override
    public void update(int act, int frameCounter) {
        if (transitionKosOrdinal >= 0 && transitionKosQueue == null) {
            rebindHardwareWorkAfterRewind();
        }
        // Retained miniboss carrier children run independently of act-specific logic.
        if (cutsceneActive) {
            if (!carrierUpdatedBeforeDynamicObjects) {
                updateCutscene();
            }
            carrierUpdatedBeforeDynamicObjects = false;
            return;
        }
        if (carrierUpdatedBeforeDynamicObjects) {
            carrierUpdatedBeforeDynamicObjects = false;
            return;
        }

        if (act == 0) {
            updateAct1Fg();
            updateAct1Bg();
        } else {
            updateAct2Fg();
            if (!act2BgUpdatedPrePhysics) {
                updateAct2Bg(frameCounter);
            } else if (act2BgRoutine == BG_WALL_MOVE && eventsFg5) {
                // Preserve the ROM-style same-frame FG -> BG handoff at the end
                // of the wall chase without running the full BG move logic twice.
                act2BgWallMove(frameCounter);
            }
            act2BgUpdatedPrePhysics = false;
        }
    }

    /**
     * Run the HCZ2 wall-chase BG event before player physics.
     *
     * <p>ROM: HCZ2_BackgroundEvent updates Camera_X/Y_pos_BG_copy before the
     * background-collision FindFloor/FindWall path uses Camera_X/Y_diff. The
     * engine's normal scroll update happens later in the frame, so HCZ2 needs
     * the same pre-physics priming pattern used by MGZ2's BG-rise sequence.
     */
    public void updatePrePhysics(int act, int frameCounter) {
        if (act != 1 || cutsceneActive) {
            return;
        }
        updateAct2Bg(frameCounter);
        act2BgUpdatedPrePhysics = true;
    }

    /**
     * Dispatches retained miniboss carrier children after the P1/P2 slots and
     * before later object slots, matching RunObjects slot order. The camera pass
     * follows this hook, so it observes the carrier's current y_pos.
     */
    public void updateRetainedCarrierObjectPass(int act) {
        if (act != 1 || (!cutsceneActive
                && transitionBubbleSpawnFrames == 0
                && !levelSizeTransitionActive)) {
            return;
        }
        boolean carrierWasPending = carrierMovementPending;
        if (cutsceneActive) {
            updateCutscene();
            carrierUpdatedBeforeDynamicObjects = true;
        }
        if (!carrierWasPending && transitionBubbleSpawnFrames > 0) {
            spawnTransitionBubble();
        }
        if (levelSizeTransitionActive) {
            updateAct2LevelSizeChildren();
        }
    }

    // =========================================================================
    // Post-transition miniboss carrier children
    // =========================================================================

    /**
     * Starts the two retained {@code loc_6A7C4} children created by the HCZ
     * miniboss's end-sign controller. In the ROM they survive {@code Load_Level},
     * then receive their first active dispatch when the in-level title card
     * finishes. Each child owns one native player slot, writes
     * {@code object_control=1}, and carries that player on its own fixed-point
     * arc (sonic3k.asm:139998-140077).
     *
     * <p>The bridge method keeps its historical name because transition event
     * providers already expose that signal; the behavior is an object-routine
     * port, not a synthetic cutscene.
     */
    public void startPostTransitionCutscene() {
        module().getTitleCardProvider().releaseInLevelPlayerControlLockOwnership();
        Level currentLevel = levelManager().getCurrentLevel();
        if (currentLevel != null) {
            // Change_Act2Sizes writes HCZ2's LevelSizes yend to
            // Camera_target_max_Y_pos but deliberately leaves the live bottom
            // boundary locked for the retained carrier sequence.
            camera().setMaxYTarget((short) currentLevel.getMaxY());
        }
        List<AbstractPlayableSprite> participants = nativeCarrierParticipants();
        if (!participants.isEmpty()) {
            carrierP1Active = captureCarrier(participants.get(0), true);
        }
        if (participants.size() > 1) {
            carrierP2Active = captureCarrier(participants.get(1), false);
        }
        cutsceneActive = carrierP1Active || carrierP2Active;
        carrierMovementPending = cutsceneActive;
        transitionBubbleSpawnFrames = cutsceneActive ? TRANSITION_BUBBLE_SPAWN_FRAMES : 0;
        if (cutsceneActive) {
            // loc_6A7C4 clears _unkFAA2 as soon as a retained underwater
            // carrier initializes. DynamicWaterHeight_HCZ2 can then resume its
            // ordinary camera-X threshold target while the players descend.
            waterSystem().setDynamicWaterLocked(Sonic3kZoneIds.ZONE_HCZ, 1, false);
        }
    }

    /**
     * Retained {@code Obj_EndSignControlAwaitStart}: once the results owner
     * clears {@code _unkFAA8}, restore both native player slots. The controller
     * executes after their slots, so this publishes the raw standing byte while
     * leaving the previous victory mapping visible until the next frame.
     */
    public void restorePostResultsPlayerControl() {
        for (AbstractPlayableSprite player : nativeCarrierParticipants()) {
            // Obj_EndSignControlAwaitStart may have already restored this slot
            // immediately before Obj_LevelResultsWait2 publishes its next
            // routine. The later retained results owner repeats the control
            // clear, but must not clear anim_frame/anim_frame_timer a second
            // time or the first WAIT cycle is delayed by one object frame.
            boolean animationAlreadyRestored = !player.isObjectControlled()
                    && player.getAnimationId() == Sonic3kAnimationIds.WAIT.id();
            ObjectControlState.none().applyTo(player);
            player.setAir(false);
            player.setForcedAnimationId(-1);
            if (!animationAlreadyRestored) {
                player.setAnimationId(Sonic3kAnimationIds.WAIT);
                player.getAnimationManager().publishPreviousAnimationId(
                        Sonic3kAnimationIds.WAIT.id());
                player.setAnimationFrameIndex(0);
                player.setAnimationTick(0);
            }
        }
    }

    private void updateCutscene() {
        if (carrierMovementPending) {
            carrierMovementPending = false;
            return;
        }
        List<AbstractPlayableSprite> participants = nativeCarrierParticipants();
        carrierP1Active = carrierP1Active && !participants.isEmpty()
                && updateCarrier(participants.get(0), true);
        carrierP2Active = carrierP2Active && participants.size() > 1
                && updateCarrier(participants.get(1), false);
        cutsceneActive = carrierP1Active || carrierP2Active;
    }

    private boolean captureCarrier(AbstractPlayableSprite player, boolean p1) {
        // ROM loc_6A7C4 deletes a carrier whose assigned player is absent or
        // not underwater. The title-card dispatch has already refreshed this
        // status mirror before the retained child starts.
        if (!player.isInWater()) {
            return false;
        }
        int x = player.getCentreX();
        int y = player.getCentreY();
        int boundsYOffset = Math.max(0, player.getStandYRadius() - player.getYRadius());
        int targetX = (camera().getX() & 0xFFFF) + CARRIER_TARGET_CAMERA_OFFSET;
        boolean targetSide = x < targetX;
        int velocity = initialCarrierXVelocity(x - targetX);
        if (p1) {
            carrierP1XFixed = x << 8;
            carrierP1YFixed = y << 8;
            carrierP1XVelocity = velocity;
            carrierP1TargetSide = targetSide;
            carrierP1BoundsYOffset = boundsYOffset;
        } else {
            carrierP2XFixed = x << 8;
            carrierP2YFixed = y << 8;
            carrierP2XVelocity = velocity;
            carrierP2TargetSide = targetSide;
            carrierP2BoundsYOffset = boundsYOffset;
        }
        player.setControlLocked(false);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(player);
        player.setAir(true);
        player.setRolling(false);
        player.setSpindash(false);
        // loc_6A7C4 publishes anim=$0F after the player slot has already run.
        player.setAnimationId(Sonic3kAnimationIds.FLOAT2);
        player.setForcedAnimationId(Sonic3kAnimationIds.FLOAT2.id());
        // Engine sprites store top-left bounds while the ROM stores x_pos/y_pos.
        // FLOAT2 changes the rendered bounds immediately; preserve the native
        // centre words around that animation write just as move.b anim does.
        NativePositionOps.writeXPosPreserveSubpixel(player, x);
        NativePositionOps.writeYPosPreserveSubpixel(player, y - carrierBoundsYOffset(p1));
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);
        return true;
    }

    private boolean updateCarrier(AbstractPlayableSprite player, boolean p1) {
        int xFixed = p1 ? carrierP1XFixed : carrierP2XFixed;
        int yFixed = p1 ? carrierP1YFixed : carrierP2YFixed;
        int xVelocity = p1 ? carrierP1XVelocity : carrierP2XVelocity;
        boolean previousSide = p1 ? carrierP1TargetSide : carrierP2TargetSide;
        int targetX = (camera().getX() & 0xFFFF) + CARRIER_TARGET_CAMERA_OFFSET;
        int x = xFixed >> 8;
        boolean currentSide = x < targetX;
        int acceleration = currentSide ? CARRIER_X_ACCELERATION : -CARRIER_X_ACCELERATION;
        xVelocity += acceleration;
        if (currentSide != previousSide) {
            xVelocity += acceleration;
        }
        xFixed += xVelocity;
        yFixed += CARRIER_Y_VELOCITY;
        int y = yFixed >> 8;
        NativePositionOps.writeXPosPreserveSubpixel(player, xFixed >> 8);
        NativePositionOps.writeYPosPreserveSubpixel(player, y - carrierBoundsYOffset(p1));
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        if (p1) {
            carrierP1XFixed = xFixed;
            carrierP1YFixed = yFixed;
            carrierP1XVelocity = xVelocity;
            carrierP1TargetSide = currentSide;
        } else {
            carrierP2XFixed = xFixed;
            carrierP2YFixed = yFixed;
            carrierP2XVelocity = xVelocity;
            carrierP2TargetSide = currentSide;
        }
        if (y < CARRIER_RELEASE_Y) {
            return true;
        }
        // Restore_PlayerControl/Restore_PlayerControl2: the carrier itself
        // clears Status_InAir and installs the standing animation before its
        // slot is deleted. This happens after the native player/CPU slots, so
        // their normal movement does not resume until the following frame.
        ObjectControlState.none().applyTo(player);
        player.setAir(false);
        player.setForcedAnimationId(-1);
        player.setAnimationId(5);
        player.setAnimationFrameIndex(0);
        player.setAnimationTick(0);
        player.forceAnimationRestart();
        if (p1) {
            // loc_6A8AE clears Camera_stored_min_Y_pos and creates the three
            // Child1_Act2LevelSize gradient objects after restoring Player 1.
            levelSizeTransitionActive = true;
            levelSizeMaxXGradient = 0;
            levelSizeMinYGradient = 0;
            levelSizeMaxYGradient = 0;
        }
        return false;
    }

    /**
     * Ports the retained end-sign controller's {@code loc_6A2A0} child spawn.
     * One {@code Random_Number} result supplies all X/Y/frame/phase choices,
     * preserving the ROM RNG call count and word usage.
     */
    private void spawnTransitionBubble() {
        transitionBubbleSpawnFrames--;
        spawnObject(this::createTransitionBubble);
    }

    private HczTransitionBubbleInstance createTransitionBubble() {
        int random = randomWordSource.getAsInt();
        int axisX = (camera().getX() & 0xFFFF) + TRANSITION_BUBBLE_AXIS_CAMERA_OFFSET;
        int x = axisX + (byte) random;
        int randomHighWord = (random >>> 16) & 0xFFFF;
        int waterY = waterSystem().getWaterLevelY(Sonic3kZoneIds.ZONE_HCZ, 1);
        int y = waterY + TRANSITION_BUBBLE_WATER_OFFSET + (randomHighWord & 0x1F);
        int mappingFrame = randomHighWord & 0x03;
        boolean secondAnimationStep = (random & 1) != 0;
        return new HczTransitionBubbleInstance(
                x, y, axisX, mappingFrame, secondAnimationStep);
    }

    /**
     * Ports Child1_Act2LevelSize: Obj_IncLevEndXGradual,
     * Obj_DecLevStartYGradual, and Obj_IncLevEndYGradual. The child slots are
     * later than the retained carrier, so their first dispatch occurs on the
     * same RunObjects pass that releases Player 1.
     */
    private void updateAct2LevelSizeChildren() {
        Level level = levelManager().getCurrentLevel();
        if (level == null) {
            levelSizeTransitionActive = false;
            return;
        }

        levelSizeMaxXGradient += 0x4000;
        int maxXStep = levelSizeMaxXGradient >> 16;
        int maxX = camera().getMaxX() & 0xFFFF;
        int targetMaxX = level.getMaxX();
        boolean maxXDone = maxX >= targetMaxX;
        if (!maxXDone && maxXStep != 0) {
            int next = maxX + maxXStep;
            maxXDone = next >= targetMaxX;
            camera().setMaxX((short) Math.min(next, targetMaxX));
        }

        levelSizeMinYGradient += 0x4000;
        int minYStep = levelSizeMinYGradient >> 16;
        int minY = camera().getMinY() & 0xFFFF;
        boolean minYDone = minY == 0;
        if (!minYDone && minYStep != 0) {
            int next = minY - minYStep;
            minYDone = next <= 0;
            camera().setMinY((short) Math.max(next, 0));
        }

        levelSizeMaxYGradient += 0x8000;
        int maxYStep = levelSizeMaxYGradient >> 16;
        int maxY = camera().getMaxY() & 0xFFFF;
        int targetMaxY = level.getMaxY();
        boolean maxYDone = maxY >= targetMaxY;
        if (!maxYDone && maxYStep != 0) {
            int next = maxY + maxYStep;
            maxYDone = next > targetMaxY;
            // Obj_IncLevEndYGradual writes Camera_max_Y_pos only. Preserve the
            // Change_Act2Sizes target so the DynamicLevelEvents tail applies its
            // independent +2 step after this object pass.
            short dynamicTarget = camera().getMaxYTarget();
            camera().setMaxY((short) Math.min(next, targetMaxY));
            camera().setMaxYTarget(dynamicTarget);
        }

        levelSizeTransitionActive = !(maxXDone && minYDone && maxYDone);
    }

    private static int initialCarrierXVelocity(int targetDelta) {
        int doubledDistance = Math.abs(targetDelta * 2);
        int magnitude = Math.max(0, 0x100 - doubledDistance) << 4;
        return targetDelta < 0 ? -magnitude : magnitude;
    }

    private int carrierBoundsYOffset(boolean p1) {
        return p1 ? carrierP1BoundsYOffset : carrierP2BoundsYOffset;
    }

    private List<AbstractPlayableSprite> nativeCarrierParticipants() {
        ObjectPlayerQuery query = ObjectPlayerQuery.from(
                spriteManager(), camera().getFocusedSprite());
        return query.playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2).stream()
                .filter(AbstractPlayableSprite.class::isInstance)
                .map(AbstractPlayableSprite.class::cast)
                .toList();
    }

    // =========================================================================
    // Act 1 FG: Palette mutations (HCZ1_Resize)
    // =========================================================================

    private void updateAct1Fg() {
        int camX = camera().getX();
        int camY = camera().getY();

        switch (fgRoutine) {
            case FG_STAGE_0 -> {
                // ROM: loc_1C892 — underwater palette correction
                if (camX < PAL_MUT_CAM_X_THRESHOLD && camY >= PAL_MUT_CAM_Y_UNDERWATER) {
                    writePaletteColors(PALETTE_UNDERWATER);
                    fgRoutine = FG_STAGE_2;
                    LOG.fine("HCZ1 FG: underwater palette applied, advancing to stage 2");
                }
            }
            case FG_STAGE_2 -> {
                // ROM: loc_1C8B8 — two exit conditions
                if (camX < PAL_MUT_CAM_X_THRESHOLD && camY < PAL_MUT_CAM_Y_UNDERWATER) {
                    // Player moved back above water — revert palette, go back to stage 0
                    writePaletteColors(PALETTE_NORMAL);
                    fgRoutine = FG_STAGE_0;
                    LOG.fine("HCZ1 FG: above water, reverting palette to stage 0");
                } else if (camY >= PAL_MUT_CAM_Y_PAST && camX >= PAL_MUT_CAM_X_PAST) {
                    // Player progressed past the initial underwater area
                    writePaletteColors(PALETTE_NORMAL);
                    fgRoutine = FG_STAGE_4;
                    LOG.fine("HCZ1 FG: past underwater area, advancing to terminal stage 4");
                }
            }
            case FG_STAGE_4 -> {
                // Terminal — idle
            }
        }
    }

    /**
     * Writes 3 Mega Drive palette colors to Normal_palette_line_4 at color offset 8.
     * ROM: move.w #$xxxx,(Normal_palette_line_4+$10).w (and +$12, +$14)
     */
    private void writePaletteColors(int[] mdColors) {
        LevelManager lm = levelManager();
        if (lm == null) return;
        Level level = lm.getCurrentLevel();
        if (level == null) return;
        S3kPaletteWriteSupport.applyColors(
                paletteRegistryOrNull(),
                level,
                graphics(),
                S3kPaletteOwners.HCZ_EVENT_PALETTE,
                S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                PALETTE_LINE,
                new int[] {
                        PALETTE_COLOR_OFFSET,
                        PALETTE_COLOR_OFFSET + 1,
                        PALETTE_COLOR_OFFSET + 2
                },
                mdColors);
        S3kPaletteWriteSupport.resolvePendingWritesNow(paletteRegistryOrNull(), level, graphics());
    }

    // =========================================================================
    // Act 1 BG: Seamless act transition (HCZ1_BackgroundEvent)
    // =========================================================================

    private void updateAct1Bg() {
        switch (bgRoutine) {
            case BG_STAGE_NORMAL -> {
                // ROM: HCZ1BGE_Normal — check Events_fg_5 flag
                // Events_fg_5 is set by Obj_LevelResultsCreate at results creation time.
                if (eventsFg5) {
                    eventsFg5 = false;
                    bgRoutine = BG_STAGE_DO_TRANSITION;
                    try {
                        transitionDirectQueue = directKosQueue();
                        transitionKosQueue = moduleKosQueue();
                        S3kKosTransitionPreflight.validate(
                                rom(), transitionDirectQueue, transitionKosQueue,
                                Sonic3kConstants.KOS_HCZ2_SECONDARY_CHUNK_ADDR,
                                Sonic3kConstants.KOS_HCZ2_SECONDARY_BLOCK_ADDR,
                                Sonic3kConstants.ART_KOSM_HCZ2_SECONDARY_ADDR);
                        transitionChunkHandle = transitionDirectQueue.queueStandardKos(
                                rom(),
                                Sonic3kConstants.KOS_HCZ2_SECONDARY_CHUNK_ADDR,
                                S3kKosRamDestinations.RAM_START + 0xA00);
                        transitionChunkOrdinal = transitionChunkHandle.ordinal();
                        transitionBlockHandle = transitionDirectQueue.queueStandardKos(
                                rom(),
                                Sonic3kConstants.KOS_HCZ2_SECONDARY_BLOCK_ADDR,
                                S3kKosRamDestinations.blockTableOffset(0x558));
                        transitionBlockOrdinal = transitionBlockHandle.ordinal();
                        transitionKosHandle = transitionKosQueue.queue(
                                rom(),
                                Sonic3kConstants.ART_KOSM_HCZ2_SECONDARY_ADDR,
                                0x11B);
                        transitionKosOrdinal = transitionKosHandle.ordinal();
                    } catch (IOException e) {
                        throw new IllegalStateException(
                                "Unable to queue HCZ2 transition resources", e);
                    }
                    LOG.info("HCZ1 BG: Events_fg_5 detected, advancing to transition stage");
                }
                // Normal scrolling handled by SwScrlHcz
            }
            case BG_STAGE_DO_TRANSITION -> {
                // ROM waits only for the HCZ2 Kosinski module workload queued by
                // HCZ1BGE_Normal. Results continue running across the reload; the
                // later End_of_level_flag is not this transition's owner.
                if (!transitionRequested
                        && transitionKosHandle != null
                        && !transitionKosQueue.modulesLeft()) {
                    if (!transitionDirectQueue.isReady(transitionChunkHandle)
                            || !transitionDirectQueue.isReady(transitionBlockHandle)
                            || !transitionKosQueue.isReady(transitionKosHandle)) {
                        throw new IllegalStateException(
                                "HCZ2 transition queue emptied before owned payloads were ready");
                    }
                    transitionDirectQueue.claim(transitionChunkHandle);
                    transitionDirectQueue.claim(transitionBlockHandle);
                    transitionKosQueue.claim(transitionKosHandle);
                    transitionChunkHandle = null;
                    transitionBlockHandle = null;
                    transitionDirectQueue = null;
                    transitionChunkOrdinal = -1;
                    transitionBlockOrdinal = -1;
                    transitionKosHandle = null;
                    transitionKosQueue = null;
                    transitionKosOrdinal = -1;
                    requestHcz2Transition();
                }
            }
        }
    }

    /** Rebinds the transient queue facade to the restored session ledger. */
    public void rebindHardwareWorkAfterRewind() {
        if (transitionKosOrdinal < 0) {
            transitionKosQueue = null;
            transitionKosHandle = null;
            return;
        }
        var timing = hardwareTiming();
        transitionKosHandle = timing.pendingHandle(
                        HardwareWorkKind.KOS_MODULE_QUEUE,
                        transitionKosOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "restored HCZ transition owner cannot find KosM ordinal "
                                + transitionKosOrdinal));
        transitionKosQueue = moduleKosQueue();
        transitionChunkHandle = timing.pendingHandle(
                        HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                        transitionChunkOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "restored HCZ transition owner cannot find chunk ordinal "
                                + transitionChunkOrdinal));
        transitionBlockHandle = timing.pendingHandle(
                        HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                        transitionBlockOrdinal)
                .orElseThrow(() -> new IllegalStateException(
                        "restored HCZ transition owner cannot find block ordinal "
                                + transitionBlockOrdinal));
        transitionDirectQueue = directKosQueue();
    }

    /** Drops only derived facades; captured ordinal remains authoritative. */
    public void discardHardwareWorkFacadesAfterRewind() {
        transitionKosQueue = null;
        transitionKosHandle = null;
        transitionDirectQueue = null;
        transitionChunkHandle = null;
        transitionBlockHandle = null;
    }

    /**
     * Requests the seamless transition from HCZ Act 1 to HCZ Act 2.
     * ROM: HCZ1BGE_DoTransition (sonic3k.asm lines 105747-105780).
     *
     * <p>Actions in the ROM:
     * <ul>
     *   <li>Change zone to $101 (HCZ Act 2)</li>
     *   <li>Clear Dynamic_resize_routine, Object_load_routine, etc.</li>
     *   <li>Load_Level (HCZ2 layout), LoadSolids, CheckLevelForWater</li>
     *   <li>Set water to $6A0</li>
     *   <li>Load HCZ2 palette (PalPointers #$D)</li>
     *   <li>Offset all objects and camera by -$3600 X</li>
     * </ul>
     */
    private void requestHcz2Transition() {
        transitionRequested = true;

        // The surviving Obj_EndSignControl starts HCZ's post-title-card
        // carrier children only after Obj_TitleCardWait2 finishes.
        S3kTransitionWriteSupport.requestHczPostTransitionCutscene(
                module().getLevelEventProvider());

        LevelManager lm = levelManager();
        int postTransitionMinX = offsetWord(camera().getMinX(), TRANSITION_OFFSET_X);
        int postTransitionMaxX = offsetWord(camera().getMaxX(), TRANSITION_OFFSET_X);
        int postTransitionMinY = offsetWord(camera().getMinY(), 0);
        int postTransitionMaxY = offsetWord(camera().getMaxY(), 0);
        int postTransitionMaxYTarget = offsetWord(camera().getMaxYTarget(), 0);
        SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
        SeamlessLevelTransitionRequest request =
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                        .targetZoneAct(Sonic3kZoneIds.ZONE_HCZ, 1)
                        .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.TITLE_OWNER)
                        .deactivateLevelNow(false)
                        // Results screen already started act 2 music
                        .preserveMusic(true)
                        // Load_Level swaps HCZ resources without clearing the
                        // running act-results ring/time globals.
                        .preserveLevelGamestate(true)
                        // _unkFAA8 / End_of_level_flag are global RAM, and the
                        // carried results/end-sign objects continue to own them
                        // after HCZ1BGE_DoTransition calls Load_Level.
                        .preserveEndOfLevelState(true)
                        // HCZ's retained Obj_LevelResults mutates into the act 2
                        // title-card owner after the reload. HCZ1BGE_DoTransition
                        // itself does not create a separate title owner.
                        .showInLevelTitleCard(false)
                        .resetLevelGamestateAtInLevelTitleCardDisplay(true)
                        // The carried Obj_LevelResults parent becomes
                        // Obj_TitleCard before its four children receive their
                        // native create/render dispatches. The retained owner
                        // reaches Obj_TitleCardWait's reset on its next title
                        // dispatch.
                        .inLevelTitleCardResetAdditionalDispatches(0)
                        .lockPlayerControlForInLevelTitleCard(true)
                        .inLevelTitleCardExitAdditionalDispatches(0)
                        .carriedResultsRetireDispatches(1)
                        // ROM subtracts $3600 from the live camera and its
                        // bounds; it does not recenter from Player_1 afterward.
                        .preserveOffsetCameraPosition(true)
                        .postTransitionMinX(postTransitionMinX)
                        .postTransitionMaxX(postTransitionMaxX)
                        .postTransitionMinY(postTransitionMinY)
                        .postTransitionMaxY(postTransitionMaxY)
                        .postTransitionMaxYTarget(postTransitionMaxYTarget)
                        .playerOffset(TRANSITION_OFFSET_X, 0)
                        .cameraOffset(TRANSITION_OFFSET_X, 0)
                        .build();

        if (lm.getCurrentLevel() == null) {
            lm.requestSeamlessTransition(request);
        } else {
            try {
                // HCZ1BGE_DoTransition performs Load_Level and every coordinate
                // subtraction inside this background-event dispatch. Deferring
                // through the outer game loop leaves one unshifted comparison
                // frame (sonic3k.asm:105747-105780).
                lm.executeActTransition(request);
                // _unkFAA2 is a global word and survives Load_Level. The engine
                // stores dynamic-water state per act, so carry the lock onto the
                // freshly initialized HCZ2 state explicitly.
                waterSystem().setDynamicWaterLocked(Sonic3kZoneIds.ZONE_HCZ, 1, true);
                waterSystem().setWaterLevelDirect(
                        Sonic3kZoneIds.ZONE_HCZ, 1, TRANSITION_WATER_LEVEL);
                waterSystem().setWaterLevelTarget(
                        Sonic3kZoneIds.ZONE_HCZ, 1, TRANSITION_WATER_LEVEL);
                lm.updatePlayableWaterStatesForCurrentLevel();
            } catch (IOException e) {
                throw new IllegalStateException("Failed to apply HCZ act transition", e);
            }
        }

        LOG.info("HCZ1: requested seamless transition to Act 2 (offset X=" +
                Integer.toHexString(TRANSITION_OFFSET_X) + ")");
    }

    private static int offsetWord(short value, int offset) {
        return ((value & 0xFFFF) + offset) & 0xFFFF;
    }

    // =========================================================================
    // Act 2 FG: Wall chase end signal (HCZ2_Resize)
    // =========================================================================

    private void updateAct2Fg() {
        switch (fgRoutine) {
            case FG_STAGE_0 -> {
                // ROM: loc_1C908 — signals end of wall-chase section
                if (camera().getX() >= ACT2_CAM_X_WALL_CHASE_END) {
                    eventsFg5 = true;
                    fgRoutine = FG_STAGE_2;
                    LOG.fine("HCZ2 FG: Camera X >= $C00, Events_fg_5 set");
                }
            }
            case FG_STAGE_2 -> {
                // Terminal — idle
            }
        }
    }

    // =========================================================================
    // Act 2 BG: Wall-chase event (HCZ2_BackgroundEvent)
    //
    // ROM: sonic3k.asm lines 106023-106170.
    // 5-state dispatch: init → wall move → transition → refresh → normal.
    // The wall-chase drives a moving solid collision wall from the left side,
    // with screen shake, speed ramping, and BG collision gating.
    // =========================================================================

    private void updateAct2Bg(int frameCounter) {
        // Timed screen shake countdown (ROM: ShakeScreen_Setup countdown)
        if (shakeTimer > 0) {
            shakeTimer--;
            updateScreenShakeFromTimer();
        }

        switch (act2BgRoutine) {
            case BG_WALL_INIT -> {
                act2BgInit();
                // ROM HCZ2BGE_WallMoveInit adds four to the routine and falls
                // straight through into HCZ2BGE_WallMove on the same dispatch.
                if (act2BgRoutine == BG_WALL_MOVE) {
                    act2BgWallMove(frameCounter);
                }
            }
            case BG_WALL_MOVE -> act2BgWallMove(frameCounter);
            case BG_WALL_TRANSITION -> act2BgTransition();
            case BG_WALL_REFRESH -> act2BgRefresh();
            case BG_NORMAL -> {
                // Normal deformation — scroll handler handles everything
            }
        }
    }

    /**
     * BG state 0: HCZ2BGE_WallMoveInit.
     * ROM: sonic3k.asm line ~105995.
     * Check activation conditions and either spawn the wall or skip to normal.
     */
    private void act2BgInit() {
        int camX = camera().getX();
        int camY = camera().getY();

        if (camX < WALL_ACTIVATE_CAM_X_MAX && camY >= WALL_ACTIVATE_CAM_Y_MIN) {
            // Activation conditions met — start wall chase
            SwScrlHcz scrollHandler = resolveHczScrollHandler();
            if (scrollHandler != null) {
                scrollHandler.setHcz2BgPhase(SwScrlHcz.Hcz2BgPhase.WALL_CHASE);
                scrollHandler.setWallChaseOffsetX(wallOffsetPixels);
                scrollHandler.primeBgCollisionState(camX, camY);
            }

            // Enable BG high-priority overlay so wall tiles render in front of FG
            setWallChaseBgOverlayActive(true);

            // Spawn wall collision object
            wallObject = new HCZ2WallObjectInstance();
            spawnObject(wallObject);

            act2BgRoutine = BG_WALL_MOVE;
            LOG.info("HCZ2 BG: wall-chase activated, wall spawned");
        } else {
            // Conditions not met — skip to normal deformation
            act2BgRoutine = BG_NORMAL;
            LOG.info("HCZ2 BG: wall-chase conditions not met (camX=0x"
                    + Integer.toHexString(camX) + " camY=0x"
                    + Integer.toHexString(camY) + "), skipping to normal");
        }
    }

    /**
     * BG state 4: HCZ2BGE_WallMove.
     * ROM: sonic3k.asm lines 106048-106070 (dispatch) and 106129-106170 (HCZ2_WallMove).
     * Runs wall movement logic each frame and gates BG collision.
     */
    private void act2BgWallMove(int frameCounter) {
        // Check if the wall chase has ended (Events_fg_5 set by FG routine)
        if (eventsFg5) {
            // Clean up wall
            if (wallObject != null) {
                wallObject.deactivate();
                wallObject = null;
            }
            gameState().setBackgroundCollisionFlag(false);
            act2BgRoutine = BG_WALL_TRANSITION;
            LOG.info("HCZ2 BG: wall-chase ended (Events_fg_5), transitioning");
            return;
        }

        // Gate Background_collision_flag by player position
        // ROM: sonic3k.asm lines 106051-106067
        updateBgCollisionGating();

        // Run wall movement logic
        updateWallMove(frameCounter);

        // Update scroll handler with current wall offset
        SwScrlHcz scrollHandler = resolveHczScrollHandler();
        if (scrollHandler != null) {
            scrollHandler.setWallChaseOffsetX(wallOffsetPixels);
            scrollHandler.setScreenShakeOffset(
                    wallMoving ? getShakeOffset(frameCounter) : 0);
            scrollHandler.primeBgCollisionState(camera().getX(), camera().getY());
        }

        // Update wall object position
        if (wallObject != null) {
            wallObject.updateWallPosition(wallOffsetPixels);
        }
    }

    /**
     * BG state 8: HCZ2BGE_NormalTransition.
     * ROM: sonic3k.asm line ~106080.
     * Switch scroll handler to normal mode, clear BG collision.
     */
    private void act2BgTransition() {
        SwScrlHcz scrollHandler = resolveHczScrollHandler();
        if (scrollHandler != null) {
            scrollHandler.setHcz2BgPhase(SwScrlHcz.Hcz2BgPhase.NORMAL);
            scrollHandler.setScreenShakeOffset(0);
            scrollHandler.setWallChaseOffsetX(0);
            scrollHandler.primeBgCollisionState(camera().getX(), camera().getY());
        }
        gameState().setBackgroundCollisionFlag(false);
        setWallChaseBgOverlayActive(false);
        act2BgRoutine = BG_WALL_REFRESH;
        LOG.fine("HCZ2 BG: transitioning to normal deformation");
    }

    /**
     * BG state $C: HCZ2BGE_NormalRefresh.
     * ROM: BG tile refresh frame, then advance to normal.
     */
    private void act2BgRefresh() {
        act2BgRoutine = BG_NORMAL;
        LOG.fine("HCZ2 BG: normal deformation active");
    }

    /**
     * HCZ2_WallMove — core wall movement logic.
     * ROM: sonic3k.asm lines 106129-106170.
     *
     * <p>Wall movement sequence:
     * <ol>
     *   <li>Wait until player X >= $680 to start</li>
     *   <li>Base speed $E000, increases to $14000 when player X > $A88</li>
     *   <li>Subtract speed from offset accumulator each frame</li>
     *   <li>Play sfx_Rumble2 every 16 frames</li>
     *   <li>Stop at offset -$600, play sfx_Crash, set timed shake</li>
     * </ol>
     */
    private void updateWallMove(int frameCounter) {
        // Once the wall has reached its stop position, don't restart
        if (wallStopped) {
            return;
        }

        if (!wallMoving) {
            // ROM: Wall only starts moving when player X >= $680
            AbstractPlayableSprite player = camera().getFocusedSprite();
            if (player == null || player.getCentreX() < WALL_TRIGGER_PLAYER_X) {
                return;
            }
            wallMoving = true;
            LOG.info("HCZ2: wall started moving (player X >= 0x"
                    + Integer.toHexString(WALL_TRIGGER_PLAYER_X) + ")");
            // ROM loc_5102E sets Screen_shake_flag and falls through to
            // loc_5103A, subtracting the first wall step immediately.
        }

        // Check if wall has reached its stop position
        if (wallOffsetPixels <= WALL_STOP_OFFSET) {
            // Wall has stopped — permanently
            wallMoving = false;
            wallStopped = true;
            shakeTimer = WALL_STOP_SHAKE_FRAMES;

            // Play crash sound
            var audioManager = audio();
            if (audioManager != null) {
                audioManager.playSfx(Sonic3kSfx.CRASH.id);
            }
            LOG.info("HCZ2: wall stopped at offset " + wallOffsetPixels);
            return;
        }

        // Calculate speed — ROM: base $E000, fast $14000 when player X > $A88
        int speed = WALL_BASE_SPEED;
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player != null && player.getCentreX() >= WALL_SPEED_UP_PLAYER_X) {
            speed = WALL_FAST_SPEED;
        }

        // Advance wall (subtract speed from offset — wall moves leftward)
        wallOffsetFixed -= speed;
        wallOffsetPixels = wallOffsetFixed >> 16;

        // Clamp to stop position
        if (wallOffsetPixels < WALL_STOP_OFFSET) {
            wallOffsetPixels = WALL_STOP_OFFSET;
            wallOffsetFixed = WALL_STOP_OFFSET << 16;
        }

        // Play rumble sound every 16 frames
        // ROM: move.w (Level_frame_counter).w,d0 / andi.w #$F,d0 / bne.s + / move.w #sfx_Rumble2,...
        if ((frameCounter & 0x0F) == 0) {
            var audioManager = audio();
            if (audioManager != null) {
                audioManager.playSfx(Sonic3kSfx.RUMBLE_2.id);
            }
        }
    }

    /**
     * Gate Background_collision_flag based on player position.
     * ROM: sonic3k.asm lines 106051-106067.
     * BG collision is only enabled when the player is within the wall-chase corridor.
     */
    private void updateBgCollisionGating() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            gameState().setBackgroundCollisionFlag(false);
            return;
        }

        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // ROM: cmpi.w #$3F0,d0 / blo.s clr / cmpi.w #$C10,d0 / bhs.s clr
        //      cmpi.w #$600,d1 / blo.s clr / cmpi.w #$840,d1 / bhs.s clr
        //      st (Background_collision_flag).w
        boolean inRange = playerX >= BG_COLL_X_MIN && playerX < BG_COLL_X_MAX
                && playerY >= BG_COLL_Y_MIN && playerY < BG_COLL_Y_MAX;

        gameState().setBackgroundCollisionFlag(inRange);
    }

    /**
     * ROM: ScreenShakeArray2 (sonic3k.asm line ~104229).
     * 64-entry table used for continuous shake (Screen_shake_flag = -1).
     * Indexed by {@code Level_frame_counter & 0x3F}. Values are unsigned (0-3px).
     */
    private static final byte[] SCREEN_SHAKE_ARRAY_CONTINUOUS = {
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
    };

    /**
     * ROM: ScreenShakeArray (sonic3k.asm line ~104226).
     * 20-entry table used for timed shake (Screen_shake_flag > 0).
     * Indexed by countdown value. Values are signed — amplitude increases
     * with index, so shake starts strong (high countdown) and weakens.
     */
    private static final byte[] SCREEN_SHAKE_ARRAY_TIMED = {
            1, -1, 1, -1, 2, -2, 2, -2, 3, -3, 3, -3, 4, -4, 4, -4,
            5, -5, 5, -5
    };

    /**
     * Get continuous screen shake offset based on frame counter.
     * ROM: ShakeScreen_Setup with Screen_shake_flag = -1 (bmi.s branch).
     * Uses ScreenShakeArray2 indexed by {@code frameCounter & 0x3F}.
     */
    private int getShakeOffset(int frameCounter) {
        return SCREEN_SHAKE_ARRAY_CONTINUOUS[frameCounter & 0x3F];
    }

    /**
     * Apply timed screen shake offset during the countdown.
     * ROM: ShakeScreen_Setup with Screen_shake_flag > 0.
     * Decrements flag, reads ScreenShakeArray[flag] as signed offset.
     */
    private void updateScreenShakeFromTimer() {
        SwScrlHcz scrollHandler = resolveHczScrollHandler();
        if (scrollHandler != null) {
            if (shakeTimer > 0 && shakeTimer <= SCREEN_SHAKE_ARRAY_TIMED.length) {
                // ROM: move.b ScreenShakeArray(pc,d0.w),d1 / ext.w d1
                scrollHandler.setScreenShakeOffset(SCREEN_SHAKE_ARRAY_TIMED[shakeTimer - 1]);
            } else {
                scrollHandler.setScreenShakeOffset(0);
            }
        }
    }

    /**
     * Resolve the SwScrlHcz scroll handler from the current game module.
     */
    private SwScrlHcz resolveHczScrollHandler() {
        try {
            if (!hasRuntime()) return null;
            var parallax = parallaxOrNull();
            if (parallax == null) return null;
            ZoneScrollHandler handler = parallax.getHandler(Sonic3kZoneIds.ZONE_HCZ);
            return (handler instanceof SwScrlHcz hcz) ? hcz : null;
        } catch (Exception e) {
            return null;
        }
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    /** Set the Events_fg_5 flag (called by results screen to trigger act transition). */
    public void setEventsFg5(boolean flag) {
        this.eventsFg5 = flag;
        if (flag) {
            LOG.info("HCZ: Events_fg_5 set externally (results screen trigger)");
        }
    }

    public boolean isEventsFg5() {
        return eventsFg5;
    }

    /**
     * ROM: Boss_flag — set by boss objects to gate FG events during fights.
     * When true, FG dynamic resize events are suppressed so the boss arena
     * camera lock is not interfered with.
     */
    public boolean isBossFlag() {
        return bossFlag;
    }

    /** Sets Boss_flag. Called by boss objects on arena entry/exit. */
    public void setBossFlag(boolean flag) {
        this.bossFlag = flag;
    }

    @Override
    public int getDynamicResizeRoutine() {
        return fgRoutine;
    }

    @Override
    public void setDynamicResizeRoutine(int routine) {
        this.fgRoutine = routine;
    }

    /**
     * Whether the HCZ2 wall-chase BG high-priority overlay is currently active.
     * Drives {@link com.openggf.game.sonic3k.runtime.HczZoneRuntimeState#wallChaseBgOverlayActive()}
     * and the registered {@code HczWallChaseBgOverlayEffect}.
     */
    public boolean isWallChaseBgOverlayActive() {
        return wallChaseBgOverlayActive;
    }

    /**
     * Publishes HCZ2 slide-terrain state after the current playable slot has
     * moved, matching {@code sub_714E -> sub_717C}. The status bit is therefore
     * consumed by the next frame's movement routine.
     */
    public void updateSlideTerrainAfterPlayablePhysics(int act, AbstractPlayableSprite player) {
        if (act != 1 || player == null || player.getDead() || player.isDebugMode()) {
            return;
        }
        LevelManager manager = levelManager();
        int blockId = manager != null
                ? manager.getBlockIdAt(player.getCentreX(), player.getCentreY())
                : -1;
        applyHcz2SlideTerrainForBlock(player, blockId);
    }

    static void applyHcz2SlideTerrainForBlock(AbstractPlayableSprite player, int blockId) {
        if (player == null) {
            return;
        }
        if (player.getAir()
                || (player.getTopSolidBit() & 0xFF) == 0x0C
                || !isHcz2SlideBlock(blockId)) {
            exitHcz2Slide(player);
            return;
        }

        int inertia = player.getGSpeed();
        int inertiaHighByte = (byte) (inertia >> 8);
        if (inertiaHighByte > HCZ2_SLIDE_TARGET_HIGH_BYTE) {
            inertia -= HCZ2_SLIDE_ACCELERATION;
            player.setGSpeed((short) inertia);
        }
        player.setDirection(inertiaHighByte < 0 ? Direction.LEFT : Direction.RIGHT);
        player.setAnimationId(HCZ2_SLIDE_ANIMATION);
        player.setSliding(true);
    }

    static boolean isHcz2SlideBlock(int blockId) {
        int layoutByte = blockId & 0xFF;
        for (int slideBlock : HCZ2_SLIDE_BLOCKS) {
            if (layoutByte == slideBlock) {
                return true;
            }
        }
        return false;
    }

    private static void exitHcz2Slide(AbstractPlayableSprite player) {
        if (player.isSliding()) {
            player.setMoveLockTimer(HCZ2_SLIDE_EXIT_MOVE_LOCK);
            player.setSliding(false);
        }
    }

    /**
     * Sets the HCZ2 wall-chase BG high-priority overlay flag.
     * Encapsulates the activation/deactivation of the staged BG overlay so
     * external code reads it through {@link com.openggf.game.sonic3k.runtime.HczZoneRuntimeState} rather than
     * touching shared global state.
     */
    private void setWallChaseBgOverlayActive(boolean active) {
        this.wallChaseBgOverlayActive = active;
    }

    // =========================================================================
    // Rewind accessors (C.4)
    // =========================================================================

    public int     getBgRoutine()                  { return bgRoutine; }
    public void    setBgRoutine(int v)             { bgRoutine = v; }
    public boolean isTransitionRequested()         { return transitionRequested; }
    public void    setTransitionRequested(boolean v){ transitionRequested = v; }
    public int     getAct2BgRoutine()              { return act2BgRoutine; }
    public void    setAct2BgRoutine(int v)         { act2BgRoutine = v; }
    public int     getWallOffsetFixed()            { return wallOffsetFixed; }
    public void    setWallOffsetFixed(int v)       { wallOffsetFixed = v; }
    public int     getWallOffsetPixels()           { return wallOffsetPixels; }
    public void    setWallOffsetPixels(int v)      { wallOffsetPixels = v; }
    public boolean isWallMoving()                  { return wallMoving; }
    public void    setWallMoving(boolean v)        { wallMoving = v; }
    public boolean isWallStopped()                 { return wallStopped; }
    public void    setWallStopped(boolean v)       { wallStopped = v; }
    public int     getShakeTimer()                 { return shakeTimer; }
    public void    setShakeTimer(int v)            { shakeTimer = v; }
    public boolean isCutsceneActive()              { return cutsceneActive; }
    public void    setCutsceneActive(boolean v)    { cutsceneActive = v; }
    public boolean isCarrierP1Active()              { return carrierP1Active; }
    public void    setCarrierP1Active(boolean v)    { carrierP1Active = v; }
    public boolean isCarrierP2Active()              { return carrierP2Active; }
    public void    setCarrierP2Active(boolean v)    { carrierP2Active = v; }
    public int     getCarrierP1XFixed()             { return carrierP1XFixed; }
    public void    setCarrierP1XFixed(int v)        { carrierP1XFixed = v; }
    public int     getCarrierP1YFixed()             { return carrierP1YFixed; }
    public void    setCarrierP1YFixed(int v)        { carrierP1YFixed = v; }
    public int     getCarrierP1XVelocity()          { return carrierP1XVelocity; }
    public void    setCarrierP1XVelocity(int v)     { carrierP1XVelocity = v; }
    public int     getCarrierP2XFixed()             { return carrierP2XFixed; }
    public void    setCarrierP2XFixed(int v)        { carrierP2XFixed = v; }
    public int     getCarrierP2YFixed()             { return carrierP2YFixed; }
    public void    setCarrierP2YFixed(int v)        { carrierP2YFixed = v; }
    public int     getCarrierP2XVelocity()          { return carrierP2XVelocity; }
    public void    setCarrierP2XVelocity(int v)     { carrierP2XVelocity = v; }
    public boolean isCarrierP1TargetSide()          { return carrierP1TargetSide; }
    public void    setCarrierP1TargetSide(boolean v){ carrierP1TargetSide = v; }
    public boolean isCarrierP2TargetSide()          { return carrierP2TargetSide; }
    public void    setCarrierP2TargetSide(boolean v){ carrierP2TargetSide = v; }
    public boolean isCarrierMovementPending()       { return carrierMovementPending; }
    public void    setCarrierMovementPending(boolean v){ carrierMovementPending = v; }
    public int     getCarrierP1BoundsYOffset()      { return carrierP1BoundsYOffset; }
    public void    setCarrierP1BoundsYOffset(int v) { carrierP1BoundsYOffset = v; }
    public int     getCarrierP2BoundsYOffset()      { return carrierP2BoundsYOffset; }
    public void    setCarrierP2BoundsYOffset(int v) { carrierP2BoundsYOffset = v; }
    public boolean isLevelSizeTransitionActive()    { return levelSizeTransitionActive; }
    public void    setLevelSizeTransitionActive(boolean v){ levelSizeTransitionActive = v; }
    public int     getLevelSizeMaxXGradient()        { return levelSizeMaxXGradient; }
    public void    setLevelSizeMaxXGradient(int v)   { levelSizeMaxXGradient = v; }
    public int     getLevelSizeMinYGradient()        { return levelSizeMinYGradient; }
    public void    setLevelSizeMinYGradient(int v)   { levelSizeMinYGradient = v; }
    public int     getLevelSizeMaxYGradient()        { return levelSizeMaxYGradient; }
    public void    setLevelSizeMaxYGradient(int v)   { levelSizeMaxYGradient = v; }
    public int     getTransitionBubbleSpawnFrames()  { return transitionBubbleSpawnFrames; }
    public void    setTransitionBubbleSpawnFrames(int v){ transitionBubbleSpawnFrames = v; }
    public void    setWallChaseBgOverlayActiveRaw(boolean v){ wallChaseBgOverlayActive = v; }
}
