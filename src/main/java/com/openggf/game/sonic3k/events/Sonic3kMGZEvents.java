package com.openggf.game.sonic3k.events;

import com.openggf.camera.Camera;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.game.GameServices;
import com.openggf.game.PlayableEntity;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.mutation.LayoutMutationContext;
import com.openggf.game.mutation.LayoutMutationIntent;
import com.openggf.game.mutation.LevelMutationSurface;
import com.openggf.game.mutation.MutationEffects;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.game.session.ActiveGameplayTeamResolver;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.audio.Sonic3kMusic;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.objects.MgzEndBossInstance;
import com.openggf.game.sonic3k.objects.MgzEndBossKnuxInstance;
import com.openggf.game.sonic3k.objects.MgzDrillingRobotnikInstance;
import com.openggf.game.sonic3k.objects.Mgz2LevelCollapseSolidInstance;
import com.openggf.game.sonic3k.runtime.MgzZoneRuntimeState;
import com.openggf.game.sonic3k.runtime.S3kRuntimeStates;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.sonic3k.resources.S3kKosTransitionPreflight;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.NativePlayableRoutine;
import com.openggf.sprites.playable.ObjectControlState;
import com.openggf.sprites.playable.SidekickCarryTrigger;
import com.openggf.sprites.playable.SidekickCpuController;
import com.openggf.sprites.playable.Sonic;
import com.openggf.sprites.playable.Tails;

import java.io.IOException;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.logging.Logger;

/**
 * Marble Garden Zone dynamic level events.
 *
 * <p>ROM: MGZ1_BackgroundEvent (sonic3k.asm lines 106269-106345),
 * MGZ2_QuakeEvent (sonic3k.asm lines 106579-106786),
 * MGZ2_QuakeEventArray (Lockon S3/Screen Events.asm lines 1027-1030).
 *
 * <h3>Act 1 BG (MGZ1_BackgroundEvent) — seamless act transition:</h3>
 * <ul>
 *   <li>Stage 0 (MGZ1BGE_Normal): normal scrolling; when Events_fg_5 set →
 *       queue MGZ2 art, advance to stage 4</li>
 *   <li>Stage 4 (MGZ1BGE_Transition): wait for Kos queue (engine: for
 *       endOfLevelFlag), then change zone to $201 (MGZ Act 2), reload level,
 *       offset player/camera/objects by (-$2E00, -$600)</li>
 * </ul>
 *
 * <h3>Act 2 FG (MGZ2_QuakeEvent) — Drilling Robotnik mini-events:</h3>
 * Three one-shot appearances of the drilling Robotnik, triggered by the
 * player entering specific boxes. Each appearance locks the camera into
 * a mini-arena, spawns Robotnik with screen shake, then releases when
 * the player moves past a release threshold. The third appearance's
 * release fires {@link #onMgz2BossArenaReached()} as a route-transition
 * marker. The end-boss spawn itself is owned by the MGZ2_Resize path.
 *
 */
public class Sonic3kMGZEvents extends Sonic3kZoneEvents {
    private static final Logger LOG = Logger.getLogger(Sonic3kMGZEvents.class.getName());

    private static final int BG_STAGE_NORMAL = 0;
    private static final int BG_STAGE_DO_TRANSITION = 4;

    /** ROM: MGZ1BGE_Transition applies (-$2E00, -$600) to player/camera/objects. */
    private static final int TRANSITION_OFFSET_X = -0x2E00;
    private static final int TRANSITION_OFFSET_Y = -0x600;

    // ========================================================================
    // Act 2 quake-event state machine (MGZ2_QuakeEvent)
    // ========================================================================

    /** ROM: Events_bg+$10 == 0 — scan MGZ2_QuakeEventArray for a matching player box. */
    private static final int QUAKE_CHECK = 0;
    /** ROM: Events_bg+$10 == 4 — first appearance active, waiting to spawn Robotnik. */
    private static final int QUAKE_EVENT_1 = 4;
    /** ROM: Events_bg+$10 == 8 — second appearance active. */
    private static final int QUAKE_EVENT_2 = 8;
    /** ROM: Events_bg+$10 == 12 — third appearance active. */
    private static final int QUAKE_EVENT_3 = 12;
    /** ROM: Events_bg+$10 == 16 — first appearance flee, waiting for release threshold. */
    private static final int QUAKE_EVENT_1_CONT = 16;
    /** ROM: Events_bg+$10 == 20 — second appearance flee. */
    private static final int QUAKE_EVENT_2_CONT = 20;
    /** ROM: Events_bg+$10 == 24 — third appearance flee (fires boss-arena hook). */
    private static final int QUAKE_EVENT_3_CONT = 24;

    /**
     * ROM: MGZ2_QuakeEventArray (Lockon S3/Screen Events.asm:1027-1030).
     * Each row: {minX, maxX, minY, maxY, cameraMaxY, cameraLockX}.
     * cameraLockX is a max-X lock for entry 0 (forces player right), and a
     * min-X lock for entries 1-2 (forces player left).
     */
    private static final int[][] QUAKE_EVENT_ARRAY = {
            {0x0780, 0x07C0, 0x0580, 0x0600, 0x05A0, 0x07E0},
            {0x31C0, 0x3200, 0x01C0, 0x0280, 0x01E0, 0x2F60},
            {0x3440, 0x3480, 0x0680, 0x0700, 0x06A0, 0x32C0},
    };

    /** ROM: QuakeEvent1 waits for player X >= $780 before locking. */
    private static final int EVENT1_PLAYER_X_THRESHOLD = 0x780;
    /** ROM: QuakeEvent2 cancels when player X >= $3200 (retreat). */
    private static final int EVENT2_PLAYER_X_RETREAT = 0x3200;
    /** ROM: QuakeEvent3 cancels when player X >= $3480 (retreat). */
    private static final int EVENT3_PLAYER_X_RETREAT = 0x3480;

    /** ROM: Robotnik spawn positions (x_pos, y_pos, flipX). */
    private static final int[] ROBOTNIK_SPAWN_X = {0x08E0, 0x2FA0, 0x3300};
    private static final int[] ROBOTNIK_SPAWN_Y = {0x0690, 0x02D0, 0x0790};

    /** ROM: QuakeEvent1Cont release when player X >= $980. */
    private static final int EVENT1_CONT_RELEASE_X = 0x980;
    /** ROM: QuakeEvent2Cont release when player Y < $100 AND X >= $2F80. */
    private static final int EVENT2_CONT_RELEASE_Y_MAX = 0x0100;
    private static final int EVENT2_CONT_RELEASE_X_MIN = 0x2F80;
    /** ROM: QuakeEvent3Cont release when player X < $3200. */
    private static final int EVENT3_CONT_RELEASE_X = 0x3200;

    /** ROM: loc_51656 / loc_516A2 — level-size defaults restored on release. */
    private static final int DEFAULT_CAMERA_MAX_Y = 0x1000;
    /** ROM: QuakeEvent2Cont resets Camera_max_X to $6000 when player escapes upward. */
    private static final int DEFAULT_CAMERA_MAX_X = 0x6000;
    /** ROM: loc_51656 resets Camera_min_X to $6000 high (open left bound). */
    private static final int DEFAULT_CAMERA_MIN_X = 0x0000;
    /** Match the standard player right-boundary margin against the live viewport while quake locks are active. */
    private static final int PLAYER_RIGHT_SCREEN_MARGIN = 24;

    // ========================================================================
    // Act 2 chunk-event state machine (MGZ2_ChunkEvent)
    // ========================================================================

    private static final int CHUNK_EVENT_CHECK = 0;
    private static final int CHUNK_EVENT_1 = 4;
    private static final int CHUNK_EVENT_2 = 8;
    private static final int CHUNK_EVENT_3 = 12;
    private static final int CHUNK_EVENT_RESET = 16;
    private static final int CHUNK_EVENT_DONE = 20;
    private static final int CHUNK_EVENT_FINAL_REPLACE_INDEX = 0x5C;
    private static final int CHUNK_EVENT_DELAY_RESET = 6;
    private static final int MGZ_QUAKE_BLOCK_LEFT_INDEX = 0xB1;  // $FF5880 -> Chunk_table[$B1]
    private static final int MGZ_QUAKE_BLOCK_RIGHT_INDEX = 0xEA; // $FF7500 -> Chunk_table[$EA]
    /** ROM: MGZ2_QuakeChunks in the combined S3&K ROM. */
    private static final int MGZ_QUAKE_CHUNK_ROM_ADDR = 0x3CBBB4;

    /**
     * ROM: MGZ2_ChunkEventArray (Lockon S3/Screen Events.asm:1031-1033).
     * Each row: {minX, maxX, minY, maxY, redrawX, redrawY}. The redraw
     * origin is kept with the ROM table for auditability; the engine redraws
     * through mutation effects instead of the ROM's row-draw queue.
     */
    private static final int[][] CHUNK_EVENT_ARRAY = {
            {0x0F68, 0x0F78, 0x0500, 0x0580, 0x0F00, 0x0500},
            {0x3680, 0x3700, 0x02F0, 0x0380, 0x3700, 0x0280},
            {0x3000, 0x3080, 0x0770, 0x0800, 0x3080, 0x0700},
    };

    /** ROM: MGZ2_ChunkReplaceArray (Lockon S3/Screen Events.asm:1059-1082). */
    private static final int[] CHUNK_REPLACE_ARRAY = {
            0x0100, 0x0500, 0x0180, 0x0580, 0x0200, 0x0600, 0x0280, 0x0680,
            0x0300, 0x0700, 0x0380, 0x0780, 0x0000, 0x0800, 0x0000, 0x0880,
            0x0000, 0x0900, 0x0000, 0x0980, 0x0000, 0x0A00, 0x0000, 0x0A80,
            0x0000, 0x0B00, 0x0000, 0x0B80, 0x0000, 0x0C00, 0x0000, 0x0C80,
            0x0000, 0x0D00, 0x0000, 0x0D80, 0x0000, 0x0E00, 0x0000, 0x0E80,
            0x0000, 0x0F00, 0x0000, 0x0F80, 0x0000, 0x1000, 0x0080, 0x0480,
    };

    @RewindTransient(reason = "ROM-backed quake chunk cache; bytes are immutable and recomputed from the ROM on demand")
    private volatile byte[] cachedMgzQuakeChunkData;

    // ========================================================================
    // Act 2 collapse / screen-event state
    // ========================================================================

    private static final int SCREEN_EVENT_NORMAL = 0;
    private static final int SCREEN_EVENT_COLLAPSE = 4;
    private static final int SCREEN_EVENT_MOVE_BG = 8;
    private static final int RUMBLE_SFX_INTERVAL_MASK = 0x0F;
    private static final int COLLAPSE_REGION_X = 121; // $3C80 / $80
    private static final int COLLAPSE_OPENING_Y = 14; // $700 / $80
    private static final int COLLAPSE_FINAL_Y = 11;   // $580 / $80
    private static final int COLLAPSE_REGION_WIDTH = 3;
    private static final int COLLAPSE_REGION_HEIGHT = 3;
    private static final int COLLAPSE_COLUMN_COUNT = 10;
    private static final int COLLAPSE_SOLID_COUNT = COLLAPSE_COLUMN_COUNT * 2;
    private static final int COLLAPSE_SOLID_START_X = 0x3C90;
    private static final int COLLAPSE_SOLID_STEP_X = 0x20;
    private static final int COLLAPSE_SOLID_HIGH_BASE_Y = 0x0790;
    private static final int COLLAPSE_SOLID_LOW_BASE_Y = 0x05C0;
    private static final int COLLAPSE_STARTUP_SHAKE_FRAMES = 0x14;
    private static final int COLLAPSE_MAX_SCROLL = 0x2E0;
    private static final int COLLAPSE_SCROLL_ACCEL = 0x500;
    private static final int[] COLLAPSE_SCROLL_DELAYS = {0x0A, 0x10, 0x02, 0x08, 0x0E, 0x06, 0x00, 0x0C, 0x12, 0x04};
    private static final int BOSS_BG_SCROLL_ACCEL = 0x800;
    private static final int BOSS_BG_SCROLL_MAX = 0x50000;
    private static final int BOSS_TRANSITION_WAIT_FRAMES = 0x168;
    private static final int BOSS_TRANSITION_SPAWN_OFFSET_X = 0x40;
    private static final int BOSS_TRANSITION_SPAWN_OFFSET_Y = 0x100;
    private static final String BOSS_TRANSITION_TEMP_TAILS_CODE = "mgz2_boss_tails";
    private static final int BOSS_TRANSITION_TAILS_ALONE_HOLD_OFFSET_Y = 8;
    private static final int BOSS_TRANSITION_TAILS_INPUT_MASK = 0x07;
    private static final short PIT_DEATH_BOUNCE_Y_SPEED = (short) -0x0700;

    // ========================================================================
    // Act 2 BG-rise state machine (MGZ2_BGEventTrigger + Obj_MGZ2BGMoveSonic)
    // ROM: sonic3k.asm:107117-107323. This is the "outrun the terrain as it
    // scrolls up" sequence. HCZ2 parallel: uses Background_collision_flag +
    // dual-path FindFloor in GroundSensor; BG plane Y offset drives the visual.
    // ========================================================================

    /** ROM: Events_bg+$00 == 0 — trigger check, BG collision off. */
    private static final int BG_RISE_NORMAL = 0;
    /** ROM: Events_bg+$00 == 8 — BG plane rising, BG collision on, player lifted in lockstep. */
    private static final int BG_RISE_SONIC = 8;
    /** ROM: Events_bg+$00 == 0xC — after BG rise completes, collision off, can re-enter. */
    private static final int BG_RISE_AFTER_MOVE = 0xC;
    /** ROM: MGZ2_BackgroundInit late-load path: Y in [$500,$800) and X >= $3900 enters state C. */
    private static final int BG_RISE_LATE_LOAD_AFTER_MOVE_Y_MIN = 0x0500;
    private static final int BG_RISE_LATE_LOAD_AFTER_MOVE_Y_MAX = 0x0800;
    private static final int BG_RISE_LATE_LOAD_AFTER_MOVE_X_MIN = 0x3900;
    /** ROM: MGZ2_BackgroundInit preloads the fully-raised offset once X >= $3800 in state 8. */
    private static final int BG_RISE_LATE_LOAD_FINISHED_X_MIN = 0x3800;

    /** ROM: loc_51A8A — BG_RISE_NORMAL trigger box for Sonic path (Y in [$800,$900), X >= $34C0). */
    private static final int BG_RISE_TRIGGER_Y_MIN = 0x0800;
    private static final int BG_RISE_TRIGGER_Y_MAX = 0x0900;
    private static final int BG_RISE_TRIGGER_X_MIN = 0x34C0;
    /** ROM: Obj_MGZ2BGMoveSonic — d1=$A80, d2=$36D0 thresholds to actually begin rising. */
    private static final int BG_RISE_MOTION_X_MIN = 0x36D0;
    private static final int BG_RISE_MOTION_Y_MIN = 0x0A80;
    /** ROM: loc_51B6C — accel latch kicks in at X >= $3D50. */
    private static final int BG_RISE_ACCEL_X_MIN = 0x3D50;
    /** ROM: Obj_MGZ2BGMoveSonic — d3=$1D0 target offset. */
    private static final int BG_RISE_TARGET_SONIC = 0x01D0;
    /** ROM: Obj_MGZ2BGMoveSonic — d4=$6000 subpixel velocity per frame (16:16 fixed-point). */
    private static final int BG_RISE_SUBPIXEL_VELOCITY = 0x6000;
    /** ROM: state 8 exit to state 0 — BG_RISE_SONIC Y still in [$800,$900) but X dropped below $34C0. */
    private static final int BG_RISE_EXIT_BACKWARD_X_MAX = 0x34C0;
    /** ROM: loc_51A04 — BG_RISE_SONIC Y below $800 AND X >= $3900: transition to AFTER_MOVE. */
    private static final int BG_RISE_EXIT_FORWARD_X_MIN = 0x3900;
    /** ROM: state C jump-table — AFTER_MOVE Y >= $800 AND X >= $3A40: return to SONIC_RISE. */
    private static final int BG_RISE_REENTRY_Y_MIN = 0x0800;
    private static final int BG_RISE_REENTRY_X_MIN = 0x3A40;
    /** ROM: loc_51B1C — target reached: Screen_shake_flag = $E (timed countdown). */
    private static final int BG_RISE_FINAL_SHAKE_FRAMES = 0x0E;
    /**
     * ROM: state 0 -> 8 branches through MGZ2BGE_Refresh with
     * Draw_delayed_rowcount=$F. Draw_PlaneVertBottomUpComplex consumes two rows
     * per ScreenEvents call, so seven further ScreenEvents refresh calls remain
     * after the trigger frame's first pair. This bridge runs before player
     * physics, while the normal ScreenEvents call publishes the flag afterward,
     * so eight following player passes must still observe the cleared flag.
     */
    private static final int BG_RISE_REFRESH_FOLLOWUP_FRAMES = 8;
    private int bgRoutine;

    /** ROM: Events_fg_5 — set by Obj_LevelResultsCreate to trigger BG act transition. */
    private boolean eventsFg5;

    /** Prevents requesting the transition more than once. */
    private boolean transitionRequested;
    @RewindTransient(reason = "queue facades are rebound from captured ordinals")
    private S3kKosDecompressionQueue transitionDirectQueue;
    @RewindTransient(reason = "queue facades are rebound from captured ordinals")
    private S3kKosModuleQueue transitionModuleQueue;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle transitionChunkHandle;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle transitionBlockHandle;
    @RewindTransient(reason = "handles are rebound from captured ordinals")
    private HardwareWorkHandle transitionArtHandle;
    private long transitionChunkOrdinal = -1;
    private long transitionBlockOrdinal = -1;
    private long transitionArtOrdinal = -1;

    /**
     * Retained Obj_EndSignControlDoStart ownership after the Act 1 reload.
     * The native object waits for the in-level title card to publish
     * End_of_level_flag before calling Change_Act2Sizes.
     */
    private boolean act2SizeChangeArmed;
    private boolean act2SizeChangeActive;
    private int act2SizeMaxXAccumulator;
    private int act2SizeMinYAccumulator;
    private int act2SizeMaxYAccumulator;

    /** ROM: Events_bg+$10 — MGZ2 quake event state machine counter. */
    private int quakeEventRoutine;

    /** ROM: Events_bg+$04 / +$06 / +$08 / +$0A / +$0C. */
    private int chunkEventRoutine;
    private int chunkReplaceIndex;
    private int chunkEventDelay;
    private int screenEventRoutine;
    private boolean collapseRequested;
    /** Events_fg_4 is consumed by the screen-event pass after the boss SST publishes it. */
    private boolean collapseRequestObserved;
    private boolean collapseInitialized;
    private boolean collapseFinished;
    private int collapseMutationCount;
    private int collapseFrameCounter;
    private int collapseStartupShakeTimer;
    private final int[] collapseScrollVelocity = new int[COLLAPSE_COLUMN_COUNT];
    private final int[] collapseScrollFixedPosition = new int[COLLAPSE_COLUMN_COUNT];
    private final int[] collapseScrollPosition = new int[COLLAPSE_COLUMN_COUNT];
    @RewindTransient(reason = "live collapse-solid object references; object lifetime/state is captured by ObjectManager rewind")
    private final Mgz2LevelCollapseSolidInstance[] collapseSolids =
            new Mgz2LevelCollapseSolidInstance[COLLAPSE_SOLID_COUNT];
    /** ROM: Events_bg+$08 — MGZ2SE_MoveBG 16:16 velocity accumulator. */
    private int bossBgScrollVelocity;
    /** ROM: Events_bg+$0C — BG camera copy advanced during the air boss. */
    private int bossBgScrollOffset;
    /** The later boss SST allocates this transition SST; setup executes on its next object pass. */
    private boolean bossTransitionInitializationPending;
    private boolean bossTransitionActive;
    private boolean bossTransitionDeathPlaneDisabled;
    private int bossTransitionTimer;
    private int bossTransitionX;
    private int bossTransitionY;
    private int bossTransitionCameraX;
    private int bossTransitionCameraY;

    /** ROM: Events_bg+$00 — MGZ2 BG-rise state (0 / 8 / 0xC). */
    private int bgRiseRoutine;
    /** ROM: Events_bg+$02 — current BG Y offset, clamped to BG_RISE_TARGET_SONIC. */
    private int bgRiseOffset;
    /** ROM: Obj_MGZ2BGMoveSonic $34(a0) — subpixel accumulator (16:16 fixed-point). */
    private int bgRiseSubpixelAccum;
    /** ROM: Obj_MGZ2BGMoveSonic control-flow latch — motion only starts once player passes both thresholds. */
    private boolean bgRiseMotionStarted;
    /** ROM: Obj_MGZ2BGMoveSonic $39(a0) — accel latch; once true, +1 pixel/frame. */
    private boolean bgRiseAccelLatched;
    /** ROM: Screen_shake_flag timed countdown ($E frames) armed when target reached. */
    private int bgRiseFinalShakeTimer;
    /** ROM: Events_routine_bg=8 / Draw_delayed_rowcount refresh after entering state 8. */
    private int bgRiseRefreshFramesRemaining;
    /** One-shot MGZ2_BackgroundInit parity path for late checkpoint/death loads. */
    private boolean bgRiseLoadStateInitialised;
    /** ROM: Dynamic_resize_routine for the MGZ2 end-boss arena gate. */
    private int bossArenaRoutine;
    private boolean bossSpawned;

    /** ROM: Events_bg+$12, +$13, +$14 — one-shot flags per appearance. */
    private boolean appearance1Complete;
    private boolean appearance2Complete;
    private boolean appearance3Complete;

    /**
     * Active drilling-Robotnik instance for the current mini-event. Cleared
     * when the Robotnik object destroys itself at the end of its flee.
     */
    @RewindTransient(reason = "live drilling-Robotnik reference skipped by the legacy sidecar; object state is captured separately")
    private MgzDrillingRobotnikInstance activeRobotnik;
    /** Set once activeRobotnik's destruction has been observed and bounds restored. */
    private boolean postFleeUnlockDone;
    private static final int GRADUAL_UNLOCK_ACCEL = 0x4000;
    private static final int GRADUAL_UNLOCK_NONE = 0;
    private static final int GRADUAL_UNLOCK_MAX_X = 1;
    private static final int GRADUAL_UNLOCK_MIN_X = -1;
    private int gradualUnlockDirection;
    /** ROM Obj_IncLevEndXGradual / Obj_DecLevStartXGradual longword at $30. */
    private int gradualUnlockAccumulator;

    public Sonic3kMGZEvents() {
        super();
    }

    @Override
    public void init(int act) {
        super.init(act);
        bgRoutine = BG_STAGE_NORMAL;
        eventsFg5 = false;
        transitionRequested = false;
        clearTransitionKosOwnership();
        act2SizeChangeArmed = false;
        act2SizeChangeActive = false;
        act2SizeMaxXAccumulator = 0;
        act2SizeMinYAccumulator = 0;
        act2SizeMaxYAccumulator = 0;
        quakeEventRoutine = QUAKE_CHECK;
        appearance1Complete = false;
        appearance2Complete = false;
        appearance3Complete = false;
        screenShakeActive = false;
        chunkEventRoutine = CHUNK_EVENT_CHECK;
        chunkReplaceIndex = 0;
        chunkEventDelay = 0;
        screenEventRoutine = SCREEN_EVENT_NORMAL;
        collapseRequested = false;
        collapseRequestObserved = false;
        collapseInitialized = false;
        collapseFinished = false;
        collapseMutationCount = 0;
        collapseFrameCounter = 0;
        collapseStartupShakeTimer = 0;
        bossBgScrollVelocity = 0;
        bossBgScrollOffset = 0;
        for (int i = 0; i < COLLAPSE_COLUMN_COUNT; i++) {
            collapseScrollVelocity[i] = 0;
            collapseScrollFixedPosition[i] = 0;
            collapseScrollPosition[i] = 0;
        }
        Arrays.fill(collapseSolids, null);
        bossTransitionInitializationPending = false;
        bossTransitionActive = false;
        bossTransitionDeathPlaneDisabled = false;
        bossTransitionTimer = 0;
        bossTransitionX = 0;
        bossTransitionY = 0;
        bossTransitionCameraX = 0;
        bossTransitionCameraY = 0;
        bgRiseRoutine = BG_RISE_NORMAL;
        bgRiseOffset = 0;
        bgRiseSubpixelAccum = 0;
        bgRiseMotionStarted = false;
        bgRiseAccelLatched = false;
        bgRiseFinalShakeTimer = 0;
        bgRiseRefreshFramesRemaining = 0;
        bgRiseLoadStateInitialised = false;
        bossArenaRoutine = 0;
        bossSpawned = false;
        activeRobotnik = null;
        postFleeUnlockDone = false;
        gradualUnlockDirection = GRADUAL_UNLOCK_NONE;
        gradualUnlockAccumulator = 0;
    }

    public void setEventsFg5(boolean value) {
        this.eventsFg5 = value;
    }

    @Override
    public void update(int act, int frameCounter) {
        update(act, frameCounter, true);
    }

    /**
     * Production post-object ScreenEvents pass. {@link #update(int, int)} keeps
     * the transition object inline for focused callers, while the level frame
     * bridge executes that native SST owner before later dynamic objects.
     */
    public void updateAfterDynamicObjects(int act, int frameCounter) {
        update(act, frameCounter, false);
    }

    /*
     * FixBugs audit (docs/skdisasm/sonic3k.asm:38, assembled as 0 in the shipped
     * ROM). MGZ1_Resize (sonic3k.asm:39336-39342) is an EMPTY label that falls
     * straight through into MGZ2_Resize; the `rts` that would make Act 1 a no-op
     * exists only under FixBugs=1 ("Bug: MGZ1 uses a dynamic resize routine meant
     * for MGZ2. This causes the act 2 boss to spawn in out-of-bounds act 1").
     * This dispatcher gates updateAct2BossArena() to act == 1, i.e. it implements
     * the FIXED branch. Matching the shipped ROM would mean running the MGZ2
     * boss-arena resize state machine in Act 1 too, which can lock the Act 1
     * camera and allocate Obj_MGZEndBoss out of bounds. Left as-is deliberately:
     * adopting it changes Act 1 camera bounds and object allocation on the active
     * release route and needs its own measured change.
     */
    private void update(int act, int frameCounter, boolean includeBossTransitionObject) {
        if (act == 0) {
            updateAct1Bg();
            // ROM: MGZ1_Resize (sonic3k.asm:39334-39343).
            //
            // FixBugs conditional (sonic3k.asm:38 -- the shipped ROM assembles
            // with FixBugs = 0, so THIS is the branch the engine implements).
            //   Shipped (FixBugs = 0), implemented here: MGZ1_Resize has NO body
            //   and NO rts, so it falls straight through into MGZ2_Resize. Act 1
            //   therefore runs Act 2's end-boss dynamic-resize gate every frame,
            //   and the disassembly's own comment records the consequence: "This
            //   causes the act 2 boss to spawn in out-of-bounds act 1"
            //   (sonic3k.asm:39339-39340). The gate is state-driven, not
            //   act-driven -- it fires only if the camera actually reaches
            //   Y $600..$700 and X >= $3A00 -- so on layouts where act 1's camera
            //   never gets there this is latent, exactly as on hardware.
            //   Fixed (FixBugs = 1), NOT implemented: a bare `rts`
            //   (sonic3k.asm:39335-39336), making act 1's resize handler a no-op.
            updateAct2BossArena();
        } else if (act == 1) {
            // MGZ2_ScreenEvent polls Do_ShakeSound before dispatching any of
            // its foreground screen-event routines.
            updateAct2ContinuousRumble(frameCounter);
            updateAct2QuakeEvent();
            updateAct2ChunkEvent();
            updateAct2BossBgScroll();
            updateAct2Collapse(frameCounter);
            if (includeBossTransitionObject) {
                updateBossTransition();
            }
            updateAct2BossArena();
            applyScreenShake(frameCounter);
        }
    }

    /**
     * Executes the native {@code Obj_MGZ2_BossTransition} SST pass after the
     * player slots but before later dynamic objects such as the collapse solids.
     */
    public void updateBossTransitionObjectBeforeDynamicObjects(int act) {
        if (act == 1) {
            updateBossTransition();
        }
    }

    /**
     * ROM {@code MGZ2BGE_Normal}: after objects and the BG deform pass, a live
     * {@code Background_collision_flag} dispatches {@code Go_CheckPlayerRelease}
     * for both player slots. The engine calls this from the post-object level
     * event phase, using the same ROM-derived collision flag published before
     * player physics.
     */
    public void updateBackgroundCollisionObjectRelease(int act) {
        if (act != 1 || !gameState().isBackgroundCollisionFlag()) {
            return;
        }
        LevelManager levelManager = GameServices.levelOrNull();
        ObjectManager objectManager = levelManager != null ? levelManager.getObjectManager() : null;
        AbstractPlayableSprite mainPlayer = camera().getFocusedSprite();
        if (objectManager == null || mainPlayer == null) {
            return;
        }
        List<AbstractPlayableSprite> sidekicks = List.copyOf(GameServices.sprites().getSidekicks());
        ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                () -> mainPlayer,
                () -> sidekicks);
        for (PlayableEntity player : playerQuery.playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            objectManager.checkPlayerReleaseFromObjectFloor(player);
        }
    }

    /**
     * Carries the native EndSignControl owner across the engine's manager
     * rebuild. Its DoStart routine is driven by End_of_level_flag, which the
     * in-level title card sets only after its timer and children have finished.
     */
    public void armAct2LevelSizeChange() {
        act2SizeChangeArmed = true;
        LOG.info("MGZ2: armed retained Change_Act2Sizes owner; end flag="
                + gameState().isEndOfLevelFlag());
    }

    /** Advances only the retained title-card/end-sign object work on a held level-counter row. */
    public void advanceInLevelTitleCardState() {
        updateAct2LevelSizeChange();
    }

    /**
     * Runs the retained Change_Act2Sizes workers in the object-loop slot before
     * DeformBgLayer consumes the published camera bounds.
     */
    public void updateAct2LevelSizeChangeBeforeCamera(int act) {
        if (act == 1) {
            updateAct2LevelSizeChange();
        }
    }

    /**
     * Ports Change_Act2Sizes and Child1_Act2LevelSize for the MGZ1 -> MGZ2
     * reload: publish Act 2's bottom target immediately, then run the three
     * independent gradual boundary workers at their native 16:16 rates.
     */
    private void updateAct2LevelSizeChange() {
        if (act2SizeChangeArmed && gameState().isEndOfLevelFlag()) {
            act2SizeChangeArmed = false;
            act2SizeChangeActive = true;
            // Each child executes its create entry before joining the shared
            // gradual-worker dispatch later in the same object pass.
            act2SizeMaxXAccumulator = 0x4000;
            act2SizeMinYAccumulator = 0x4000;
            // Child1 creation reaches the later Obj_IncLevEndYGradual
            // slot after its create entry in the same Process_Sprites
            // pass. Seed that create-side half step before the shared
            // worker dispatch below.
            act2SizeMaxYAccumulator = 0x8000;

            Level level = levelManager().getCurrentLevel();
            if (level != null) {
                // Change_Act2Sizes hands its Child1_Act2LevelSize workers a
                // max-Y boundary that already contains the native two-pixel
                // carry at this owner handoff. Preserve that carry before the
                // first Obj_IncLevEndYGradual dispatch; otherwise the camera's
                // first downward clamp starts two pixels low.
                camera().setMaxY((short) (camera().getMaxY() + 2));
                camera().setMaxYTarget((short) level.getMaxY());
            }
            LOG.info("MGZ2: title card completed; starting Change_Act2Sizes workers");
        }
        if (!act2SizeChangeActive) {
            return;
        }

        Level level = levelManager().getCurrentLevel();
        if (level == null) {
            act2SizeChangeActive = false;
            return;
        }

        Camera camera = camera();
        act2SizeMaxXAccumulator += 0x4000;
        int maxXStep = act2SizeMaxXAccumulator >> 16;
        int maxX = camera.getMaxX() & 0xFFFF;
        int targetMaxX = level.getMaxX();
        boolean maxXDone = maxX >= targetMaxX;
        if (!maxXDone && maxXStep != 0) {
            int next = maxX + maxXStep;
            maxXDone = next >= targetMaxX;
            camera.setMaxX((short) Math.min(next, targetMaxX));
        }

        act2SizeMinYAccumulator += 0x4000;
        int minYStep = act2SizeMinYAccumulator >> 16;
        int minY = camera.getMinY();
        int targetMinY = level.getMinY();
        boolean minYDone = minY <= targetMinY;
        if (!minYDone && minYStep != 0) {
            int next = minY - minYStep;
            minYDone = next <= targetMinY;
            camera.setMinY((short) Math.max(next, targetMinY));
        }

        act2SizeMaxYAccumulator += 0x8000;
        int maxYStep = act2SizeMaxYAccumulator >> 16;
        int maxY = camera.getMaxY() & 0xFFFF;
        int targetMaxY = level.getMaxY();
        boolean maxYDone = maxY >= targetMaxY;
        if (!maxYDone && maxYStep != 0) {
            int next = maxY + maxYStep;
            maxYDone = next > targetMaxY;
            short dynamicTarget = camera.getMaxYTarget();
            camera.setMaxY((short) Math.min(next, targetMaxY));
            camera.setMaxYTarget(dynamicTarget);
        }

        act2SizeChangeActive = !(maxXDone && minYDone && maxYDone);
    }

    /**
     * ROM: MGZ2_Resize (sonic3k.asm:39343-39418). This is the end-boss
     * dynamic-resize gate: lock the vertical camera to the boss corridor,
     * clamp the right edge to $3C80, then spawn Obj_MGZEndBoss when the camera
     * reaches that clamp.
     */
    private void updateAct2BossArena() {
        if (bossTransitionActive) {
            return;
        }
        Camera camera = camera();
        int cameraX = camera.getX() & 0xFFFF;
        int cameraY = camera.getY() & 0xFFFF;
        boolean knucklesArena = resolveBossTransitionPlayerCharacter() == PlayerCharacter.KNUCKLES;
        int arenaBandBaseY = knucklesArena ? 0 : 0x0600;
        switch (bossArenaRoutine) {
            case 0 -> {
                if (cameraY < arenaBandBaseY || cameraY >= arenaBandBaseY + 0x0100
                        || cameraX < 0x3A00) {
                    return;
                }
                lockBossApproachCamera(camera, arenaBandBaseY + 0x00A0);
                bossArenaRoutine = 2;
            }
            case 2 -> {
                if (cameraX < 0x3A00) {
                    restoreBossApproachCamera(camera);
                    bossArenaRoutine = 0;
                    return;
                }
                if (cameraX < 0x3C80) {
                    return;
                }
                camera.setMinX((short) 0x3C80);
                camera.setMinXTarget((short) 0x3C80);
                spawnMgzEndBoss();
                bossArenaRoutine = 4;
            }
            default -> {
            }
        }
    }

    private void lockBossApproachCamera(Camera camera, int arenaY) {
        camera.setMinY((short) arenaY);
        camera.setMinYTarget((short) arenaY);
        camera.setMaxY((short) arenaY);
        camera.setMaxYTarget((short) arenaY);
        camera.setMaxX((short) 0x3C80);
        camera.setMaxXTarget((short) 0x3C80);
    }

    private void restoreBossApproachCamera(Camera camera) {
        camera.setMinY((short) 0);
        camera.setMinYTarget((short) 0);
        camera.setMaxY((short) 0x1000);
        camera.setMaxYTarget((short) 0x1000);
        camera.setMaxX((short) DEFAULT_CAMERA_MAX_X);
        camera.setMaxXTarget((short) DEFAULT_CAMERA_MAX_X);
    }

    private void spawnMgzEndBoss() {
        if (bossSpawned) {
            return;
        }
        bossSpawned = true;
        setGenericBossFlag(true);
        if (resolveBossTransitionPlayerCharacter() == PlayerCharacter.KNUCKLES) {
            gameState().setCurrentBossId(Sonic3kObjectIds.MGZ_END_BOSS_KNUX);
            audio().playMusic(Sonic3kMusic.MINIBOSS.id);
            spawnObject(() -> new MgzEndBossKnuxInstance(
                    new ObjectSpawn(0x3D20, 0x0068, Sonic3kObjectIds.MGZ_END_BOSS_KNUX,
                            0, 0, false, 0)));
            return;
        }
        gameState().setCurrentBossId(Sonic3kObjectIds.MGZ_END_BOSS);
        spawnObject(() -> new MgzEndBossInstance(
                new ObjectSpawn(0x3D20, 0x0668, Sonic3kObjectIds.MGZ_END_BOSS, 0, 0, false, 0)));
    }

    // ========================================================================
    // Act 2 quake-event state machine
    // ========================================================================

    /**
     * ROM: MGZ2_QuakeEvent (sonic3k.asm:106579-106786). Reads player position
     * and dispatches on {@link #quakeEventRoutine}.
     */
    private void updateAct2QuakeEvent() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        updateGradualCameraUnlock();
        maybeStartUnlockAfterRobotnikDestroyed();

        switch (quakeEventRoutine) {
            case QUAKE_CHECK -> quakeEventCheck(playerX, playerY);
            case QUAKE_EVENT_1 -> quakeEvent1(playerX);
            case QUAKE_EVENT_2 -> quakeEvent2(playerX);
            case QUAKE_EVENT_3 -> quakeEvent3(playerX);
            case QUAKE_EVENT_1_CONT -> quakeEvent1Cont(playerX);
            case QUAKE_EVENT_2_CONT -> quakeEvent2Cont(playerX, playerY);
            case QUAKE_EVENT_3_CONT -> quakeEvent3Cont(playerX);
            default -> {
            }
        }

        clampPlayerToCurrentViewportRightEdge(player);
    }

    /**
     * ROM: MGZ2_QuakeEventCheck (sonic3k.asm:106625-106663). Scans
     * {@link #QUAKE_EVENT_ARRAY} for a matching player position; on the first
     * incomplete entry that contains the player, locks camera bounds and
     * transitions to {@link #QUAKE_EVENT_1}, {@link #QUAKE_EVENT_2}, or
     * {@link #QUAKE_EVENT_3}.
     */
    private void quakeEventCheck(int playerX, int playerY) {
        Camera camera = camera();
        for (int i = 0; i < QUAKE_EVENT_ARRAY.length; i++) {
            if (isAppearanceComplete(i)) {
                continue;
            }
            int[] entry = QUAKE_EVENT_ARRAY[i];
            int minX = entry[0];
            int maxX = entry[1];
            int minY = entry[2];
            int maxY = entry[3];
            if (playerX < minX || playerX >= maxX || playerY < minY || playerY >= maxY) {
                continue;
            }
            int cameraMaxY = entry[4];
            int cameraLockX = entry[5];
            quakeEventRoutine = QUAKE_EVENT_1 + (i * 4);
            camera.setMaxY((short) cameraMaxY);
            camera.setMaxYTarget((short) cameraMaxY);
            if (i == 0) {
                camera.setMaxX((short) cameraLockX);
            } else {
                camera.setMinX((short) cameraLockX);
            }
            return;
        }
    }

    private boolean isAppearanceComplete(int index) {
        return switch (index) {
            case 0 -> appearance1Complete;
            case 1 -> appearance2Complete;
            case 2 -> appearance3Complete;
            default -> false;
        };
    }

    private void clampPlayerToCurrentViewportRightEdge(AbstractPlayableSprite player) {
        if (!isQuakeSequenceActive() || player.isObjectControlled()) {
            return;
        }
        int rightBoundary = (camera().getX() & 0xFFFF) + camera().getWidth() - PLAYER_RIGHT_SCREEN_MARGIN;
        if ((player.getCentreX() & 0xFFFF) <= rightBoundary) {
            return;
        }
        player.setCentreX((short) rightBoundary);
        player.setXSpeed((short) 0);
        player.setGSpeed((short) 0);
    }

    private boolean isQuakeSequenceActive() {
        return quakeEventRoutine == QUAKE_EVENT_1
                || quakeEventRoutine == QUAKE_EVENT_2
                || quakeEventRoutine == QUAKE_EVENT_3
                || quakeEventRoutine == QUAKE_EVENT_1_CONT
                || quakeEventRoutine == QUAKE_EVENT_2_CONT
                || quakeEventRoutine == QUAKE_EVENT_3_CONT;
    }

    /**
     * ROM: MGZ2_QuakeEvent1 (sonic3k.asm:106666-106684). Waits for camera X
     * to reach {@link Camera#getMaxX()} (the lock). On arrival: freezes the
     * screen, spawns Robotnik, triggers shake, advances to QuakeEvent1Cont.
     * Retreat (player X &lt; {@link #EVENT1_PLAYER_X_THRESHOLD}) reverts to
     * {@link #QUAKE_CHECK} via {@link #resetBoundsAndState()}.
     */
    private void quakeEvent1(int playerX) {
        if (playerX < EVENT1_PLAYER_X_THRESHOLD) {
            resetBoundsAndState();
            return;
        }
        Camera camera = camera();
        int camMaxX = camera.getMaxX() & 0xFFFF;
        if ((camera.getX() & 0xFFFF) < camMaxX) {
            return; // camera hasn't reached the lock yet
        }
        camera.setMinX((short) camMaxX);
        appearance1Complete = true;
        quakeEventRoutine = QUAKE_EVENT_1_CONT;
        spawnDrillingRobotnik(0);
    }

    /**
     * ROM: MGZ2_QuakeEvent2 (sonic3k.asm:106687-106720). Waits for camera X
     * to reach {@link Camera#getMinX()} (the forced-left lock). Retreat
     * (player X &gt;= {@link #EVENT2_PLAYER_X_RETREAT}) reverts.
     */
    private void quakeEvent2(int playerX) {
        if (playerX >= EVENT2_PLAYER_X_RETREAT) {
            camera().setMinY((short) 0x01DF);
            resetBoundsAndState();
            return;
        }
        Camera camera = camera();
        int camMaxY = camera.getMaxY() & 0xFFFF;
        if ((camera.getY() & 0xFFFF) == camMaxY && (camera.getMinY() & 0xFFFF) != camMaxY) {
            camera.setMinY((short) camMaxY);
        }
        int camMinX = camera.getMinX() & 0xFFFF;
        if ((camera.getX() & 0xFFFF) > camMinX) {
            return;
        }
        camera.setMaxX((short) camMinX);
        appearance2Complete = true;
        quakeEventRoutine = QUAKE_EVENT_2_CONT;
        spawnDrillingRobotnik(1);
    }

    /**
     * ROM: MGZ2_QuakeEvent3 (sonic3k.asm:106723-106742). Retreat threshold is
     * {@link #EVENT3_PLAYER_X_RETREAT}.
     */
    private void quakeEvent3(int playerX) {
        if (playerX >= EVENT3_PLAYER_X_RETREAT) {
            resetBoundsAndState();
            return;
        }
        Camera camera = camera();
        int camMinX = camera.getMinX() & 0xFFFF;
        if ((camera.getX() & 0xFFFF) > camMinX) {
            return;
        }
        camera.setMaxX((short) camMinX);
        appearance3Complete = true;
        quakeEventRoutine = QUAKE_EVENT_3_CONT;
        spawnDrillingRobotnik(2);
    }

    /**
     * ROM: MGZ2_QuakeEvent1Cont (sonic3k.asm:106755-106759). Once the player
     * passes {@link #EVENT1_CONT_RELEASE_X}, restore default camera_max_Y and
     * return to {@link #QUAKE_CHECK} so the remaining quakes can still trigger.
     */
    private void quakeEvent1Cont(int playerX) {
        if (playerX < EVENT1_CONT_RELEASE_X) {
            return;
        }
        restoreBoundsAfterFlee();
        quakeEventRoutine = QUAKE_CHECK;
    }

    /**
     * ROM: MGZ2_QuakeEvent2Cont (sonic3k.asm:106761-106769). Release requires
     * player Y &lt; $100 AND X &gt;= $2F80; additionally resets
     * Camera_max_X to $6000.
     */
    private void quakeEvent2Cont(int playerX, int playerY) {
        if (playerY >= EVENT2_CONT_RELEASE_Y_MAX || playerX < EVENT2_CONT_RELEASE_X_MIN) {
            return;
        }
        camera().setMaxX((short) DEFAULT_CAMERA_MAX_X);
        restoreBoundsAfterFlee();
        quakeEventRoutine = QUAKE_CHECK;
    }

    /**
     * ROM: MGZ2_QuakeEvent3Cont (sonic3k.asm:106775-106778). Release when the
     * player moves back past {@link #EVENT3_CONT_RELEASE_X}. In the ROM this
     * is where the end-of-act boss route continues; the engine fires
     * {@link #onMgz2BossArenaReached()} as a route-transition marker while
     * the later MGZ2_Resize path owns the actual boss spawn.
     */
    private void quakeEvent3Cont(int playerX) {
        if (playerX >= EVENT3_CONT_RELEASE_X) {
            return;
        }
        restoreBoundsAfterFlee();
        quakeEventRoutine = QUAKE_CHECK;
        onMgz2BossArenaReached();
    }

    /**
     * ROM: loc_51656 — retreat cancels the pending mini-event and restores
     * level-wide boundary defaults before returning to state 0.
     */
    private void resetBoundsAndState() {
        Camera camera = camera();
        camera.setMaxY((short) DEFAULT_CAMERA_MAX_Y);
        camera.setMaxYTarget((short) DEFAULT_CAMERA_MAX_Y);
        camera.setMinX((short) DEFAULT_CAMERA_MIN_X);
        camera.setMaxX((short) DEFAULT_CAMERA_MAX_X);
        quakeEventRoutine = QUAKE_CHECK;
        screenShakeActive = false;
        setGenericBossFlag(false);
        activeRobotnik = null;
        postFleeUnlockDone = false;
        gradualUnlockDirection = GRADUAL_UNLOCK_NONE;
        gradualUnlockAccumulator = 0;
    }

    /**
     * ROM: loc_516A2 — restore Camera_max_Y to $1000 after a flee completes.
     * Called from each QuakeEventNCont state once its release condition fires.
     * Also restores the camera X bounds (the ROM does this gradually via
     * Obj_IncLevEndXGradual / Obj_DecLevStartXGradual spawned by the drilling
     * Robotnik's flee routine).
     */
    private void restoreBoundsAfterFlee() {
        Camera camera = camera();
        camera.setMaxY((short) DEFAULT_CAMERA_MAX_Y);
        camera.setMaxYTarget((short) DEFAULT_CAMERA_MAX_Y);
        setGenericBossFlag(false);
        activeRobotnik = null;
        postFleeUnlockDone = false;
        gradualUnlockAccumulator = 0;
        // Screen shake is NOT cleared here — the quake-continuous shake persists
        // until MGZ2_ChunkEvent owns the shutdown later in the sequence (ROM:
        // Events_fg_0 stays set across the mini-event release).
    }

    /**
     * Spawns the Drilling Robotnik for the given appearance index.
     * ROM: QuakeEvent{1,2,3} {@code move.l #Obj_MGZ2DrillingRobotnik,(a1)}.
     * Also raises {@code Screen_shake_flag} / {@code Events_fg_0} so the
     * scroll handler applies the continuous-shake offset table each frame.
     */
    private void spawnDrillingRobotnik(int appearanceIndex) {
        int spawnX = ROBOTNIK_SPAWN_X[appearanceIndex];
        int spawnY = ROBOTNIK_SPAWN_Y[appearanceIndex];
        boolean flipX = (appearanceIndex != 0); // entries 2 and 3 face left
        ObjectSpawn spawn = new ObjectSpawn(spawnX, spawnY, 0, 0, 0, false, 0);
        activeRobotnik = spawnObject(() -> new MgzDrillingRobotnikInstance(spawn, flipX));
        postFleeUnlockDone = false;
        gradualUnlockAccumulator = 0;
        screenShakeActive = true;
        setGenericBossFlag(true);
        LOG.info("MGZ2 drilling Robotnik appearance " + (appearanceIndex + 1)
                + " spawned at (0x" + Integer.toHexString(spawnX)
                + ", 0x" + Integer.toHexString(spawnY) + ")");
    }

    private void maybeStartUnlockAfterRobotnikDestroyed() {
        if (postFleeUnlockDone || activeRobotnik == null || !activeRobotnik.isDestroyed()) {
            return;
        }
        gradualUnlockDirection = switch (quakeEventRoutine) {
            case QUAKE_EVENT_1_CONT -> GRADUAL_UNLOCK_MAX_X;
            case QUAKE_EVENT_2_CONT, QUAKE_EVENT_3_CONT -> GRADUAL_UNLOCK_MIN_X;
            default -> GRADUAL_UNLOCK_NONE;
        };
        gradualUnlockAccumulator = 0;
        setGenericBossFlag(false);
        activeRobotnik = null;
        postFleeUnlockDone = true;
    }

    /**
     * Called by the native-style cleanup path after it has allocated the
     * gradual camera worker. The worker now owns the boundary accumulator;
     * this event-side acknowledgement only retires the boss reference and
     * prevents the legacy destruction fallback above from duplicating it.
     */
    public void completeDrillingRobotnikFlee() {
        setGenericBossFlag(false);
        activeRobotnik = null;
        postFleeUnlockDone = true;
        gradualUnlockDirection = GRADUAL_UNLOCK_NONE;
        gradualUnlockAccumulator = 0;
    }

    private void updateGradualCameraUnlock() {
        if (gradualUnlockDirection == GRADUAL_UNLOCK_NONE) {
            return;
        }
        gradualUnlockAccumulator += GRADUAL_UNLOCK_ACCEL;
        int delta = gradualUnlockAccumulator >>> 16;
        if (delta == 0) {
            return;
        }
        Camera camera = camera();
        if (gradualUnlockDirection == GRADUAL_UNLOCK_MAX_X) {
            int current = camera.getMaxX() & 0xFFFF;
            if (current >= DEFAULT_CAMERA_MAX_X) {
                camera.setMaxX((short) DEFAULT_CAMERA_MAX_X);
                gradualUnlockDirection = GRADUAL_UNLOCK_NONE;
                gradualUnlockAccumulator = 0;
                return;
            }
            camera.setMaxX((short) Math.min(DEFAULT_CAMERA_MAX_X, current + delta));
            return;
        }
        int current = camera.getMinX() & 0xFFFF;
        if (current <= DEFAULT_CAMERA_MIN_X) {
            camera.setMinX((short) DEFAULT_CAMERA_MIN_X);
            gradualUnlockDirection = GRADUAL_UNLOCK_NONE;
            gradualUnlockAccumulator = 0;
            return;
        }
        camera.setMinX((short) Math.max(DEFAULT_CAMERA_MIN_X, current - delta));
    }

    private void setGenericBossFlag(boolean active) {
        try {
            if (module().getLevelEventProvider() instanceof AbstractLevelEventManager manager) {
                manager.setBossActive(active);
            }
        } catch (RuntimeException e) {
            LOG.log(java.util.logging.Level.WARNING, "Sonic3kMGZEvents.setGenericBossFlag failed", e);
        }
    }

    /** ROM: Events_fg_0 / Screen_shake_flag active while Robotnik is on-screen. */
    private boolean screenShakeActive;

    /** ROM: MGZ2_ScreenEvent calls Do_ShakeSound before its routine dispatch. */
    private void updateAct2ContinuousRumble(int frameCounter) {
        var audioManager = audio();
        if (audioManager == null) {
            return;
        }
        if (((frameCounter - 1) & RUMBLE_SFX_INTERVAL_MASK) != 0) {
            return;
        }
        if (screenEventRoutine == SCREEN_EVENT_NORMAL && screenShakeActive) {
            audioManager.playSfx(Sonic3kSfx.RUMBLE_2.id);
        }
    }

    /** ROM: {@code MGZ2_LevelCollapse} emits BigRumble from its scrolling path. */
    private void playCollapseRumble(int frameCounter) {
        var audioManager = audio();
        if (audioManager != null && ((frameCounter - 1) & RUMBLE_SFX_INTERVAL_MASK) == 0) {
            audioManager.playSfx(Sonic3kSfx.BIG_RUMBLE.id);
        }
    }

    private void applyScreenShake(int frameCounter) {
        MgzZoneRuntimeState state = currentMgzRuntimeState();
        if (state == null) {
            return;
        }
        // ROM MGZ2_ScreenEvent raises Screen_shake_flag (st, sonic3k.asm:106395)
        // and ShakeScreen_Setup samples ScreenShakeArray2 from
        // Level_frame_counter at the background event's tail
        // (sonic3k.asm:104200-104209, :106308). Keep both in that single owner.
        if (isVisualShakeActive()) {
            state.requestContinuousScreenShake();
        }
    }

    private boolean isVisualShakeActive() {
        return screenShakeActive || bgRiseFinalShakeTimer > 0;
    }

    private MgzZoneRuntimeState currentMgzRuntimeState() {
        try {
            if (!GameServices.hasRuntime()) {
                return null;
            }
            return S3kRuntimeStates.currentMgz(GameServices.zoneRuntimeRegistry()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    public int getQuakeEventRoutine() {
        return quakeEventRoutine;
    }

    public boolean isAppearance1Complete() {
        return appearance1Complete;
    }

    public boolean isAppearance2Complete() {
        return appearance2Complete;
    }

    public boolean isAppearance3Complete() {
        return appearance3Complete;
    }

    /**
     * ROM: Events_fg_0 / Screen_shake_flag — true while Robotnik is on-screen
     * (continuous) or while the BG-rise final timed shake ($E frames) is counting down.
     */
    public boolean isScreenShakeActive() {
        return isVisualShakeActive();
    }

    public int getBgRiseRoutine() {
        return bgRiseRoutine;
    }

    public int getBgRiseOffset() {
        return bgRiseOffset;
    }

    /**
     * ROM: MGZ2_BGEventTrigger (sonic3k.asm:107117-107222) + Obj_MGZ2BGMoveSonic
     * (sonic3k.asm:107241-107323). The engine's shared frame step calls this
     * bridge before player physics so the state published by the preceding ROM
     * object/background-event cadence is visible to the corresponding terrain
     * probes. The delayed-refresh counter below preserves the interval where
     * MGZ2_BackgroundEvent deliberately skips MGZ2_BGEventTrigger.
     */
    public void updatePrePhysics(int act) {
        if (act != 1) {
            return;
        }
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }
        primeBgRiseFromLoadPosition(player.getCentreX(), player.getCentreY());
        if (bgRiseFinalShakeTimer > 0) {
            bgRiseFinalShakeTimer--;
        }
        // ROM MGZ2_LevelCollapse sets _unkEEA2 to $FFFF when its special
        // per-column VScroll mode starts (sonic3k.asm:106555). From then on,
        // MGZ2_BGEventTrigger tests that word and returns immediately
        // (sonic3k.asm:107164-107167). In particular, a falling player in the
        // boss pit must not make the completed state-C terrain event re-enter
        // state 8 and expose the raised-terrain Plane B for a frame.
        if (collapseInitialized) {
            return;
        }
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();
        if (bgRiseRoutine == BG_RISE_SONIC && bgRiseRefreshFramesRemaining > 0) {
            // MGZ2BGE_Refresh does not call MGZ2_BGEventTrigger, so the flag
            // remains in the clear state written by the state-0 trigger. The
            // separately allocated Obj_MGZ2BGMoveSonic still executes from its
            // SST slot while the plane refresh is in progress.
            gameState().setBackgroundCollisionFlag(false);
            bgRiseRefreshFramesRemaining--;
        } else {
            switch (bgRiseRoutine) {
                case BG_RISE_NORMAL -> bgRiseNormal(playerX, playerY);
                case BG_RISE_SONIC -> bgRiseSonic(playerX, playerY);
                case BG_RISE_AFTER_MOVE -> bgRiseAfterMove(playerX, playerY);
                default -> {
                }
            }
        }
        MgzZoneRuntimeState runtimeState = currentMgzRuntimeState();
        if (runtimeState == null) {
            if (bgRiseRoutine == BG_RISE_SONIC) {
                LOG.fine("MGZ BG-rise: state SONIC armed with no runtime scroll bridge; "
                        + "headless BG formula sync skipped");
            }
            return;
        }
        // ROM: Events_bg is mutated in the object phase and read by MGZ2_BGDeform
        // during the deform pass; that pass runs as part of the per-frame render.
        // Headless paths skip parallax draw, so ask the runtime state to sync the
        // registered scroll handler's cached BG-rise fields now so collision
        // probes and tests that inspect the handler between event tick and render
        // see the post-transition state.
        runtimeState.syncBgRiseToScrollHandler();
    }

    /**
     * ROM: MGZ2_BackgroundInit reconstructs the BG-rise route from the loaded
     * player position. That means late starpost/death reloads skip straight to
     * the finished raised-terrain state instead of replaying the whole lift.
     */
    private void primeBgRiseFromLoadPosition(int playerX, int playerY) {
        if (bgRiseLoadStateInitialised) {
            return;
        }
        bgRiseLoadStateInitialised = true;

        if (playerY >= BG_RISE_LATE_LOAD_AFTER_MOVE_Y_MIN
                && playerY < BG_RISE_LATE_LOAD_AFTER_MOVE_Y_MAX
                && playerX >= BG_RISE_LATE_LOAD_AFTER_MOVE_X_MIN) {
            bgRiseRoutine = BG_RISE_AFTER_MOVE;
            bgRiseOffset = BG_RISE_TARGET_SONIC;
            bgRiseSubpixelAccum = 0;
            bgRiseMotionStarted = false;
            bgRiseAccelLatched = false;
            gameState().setBackgroundCollisionFlag(false);
            LOG.info(String.format(
                    "MGZ BG-rise: late-load init -> AFTER_MOVE at player (0x%04X, 0x%04X)",
                    playerX, playerY));
            return;
        }

        if (playerY >= BG_RISE_TRIGGER_Y_MIN && playerX >= BG_RISE_TRIGGER_X_MIN) {
            bgRiseRoutine = BG_RISE_SONIC;
            bgRiseOffset = playerX >= BG_RISE_LATE_LOAD_FINISHED_X_MIN ? BG_RISE_TARGET_SONIC : 0;
            bgRiseSubpixelAccum = 0;
            bgRiseMotionStarted = false;
            bgRiseAccelLatched = false;
            gameState().setBackgroundCollisionFlag(true);
            LOG.info(String.format(
                    "MGZ BG-rise: late-load init -> SONIC offset=0x%03X at player (0x%04X, 0x%04X)",
                    bgRiseOffset, playerX, playerY));
            return;
        }

        gameState().setBackgroundCollisionFlag(false);
    }

    /**
     * ROM: loc_51A6A — BG collision off; Sonic trigger box is Y in [$800,$900)
     * AND X >= $34C0. Knuckles variant (Y in [$80,$180)) is not ported; the
     * engine currently runs the Sonic/Tails route here.
     */
    private void bgRiseNormal(int playerX, int playerY) {
        gameState().setBackgroundCollisionFlag(false);
        if (playerY >= BG_RISE_TRIGGER_Y_MIN && playerY < BG_RISE_TRIGGER_Y_MAX
                && playerX >= BG_RISE_TRIGGER_X_MIN) {
            bgRiseRoutine = BG_RISE_SONIC;
            bgRiseOffset = 0;
            bgRiseSubpixelAccum = 0;
            bgRiseMotionStarted = false;
            bgRiseAccelLatched = false;
            bgRiseRefreshFramesRemaining = BG_RISE_REFRESH_FOLLOWUP_FRAMES;
            // MGZ2_BGEventTrigger state 0 clears the flag before changing
            // Events_bg+$00 to 8, then the background event enters its delayed
            // plane refresh without visiting the state-8 flag write.
            gameState().setBackgroundCollisionFlag(false);
            LOG.info(String.format(
                    "MGZ BG-rise: state 0 -> SONIC at player (0x%04X, 0x%04X)",
                    playerX, playerY));
        }
    }

    /**
     * ROM: loc_51A04 ({@code MGZ2_BGEventTrigger} state 8) + {@code Obj_MGZ2BGMoveSonic}
     * per-frame body. The trigger unconditionally sets
     * {@code Background_collision_flag}=ON, then decides state transitions.
     * The object runs its motion body regardless of the trigger's return:
     * once motion has started (player past X>$36D0 AND Y>$A80) it advances
     * every frame until the offset reaches {@code $1D0}. Accel latch fires
     * at X>=$3D50 (ROM: loc_51B6C) and flips from subpixel accumulator to
     * integer +1 pixel/frame.
     */
    private void bgRiseSonic(int playerX, int playerY) {
        gameState().setBackgroundCollisionFlag(true);
        // MGZ2_BGEventTrigger state-8 transition logic:
        //   Y < $800 AND X >= $3900 → state C
        //   Y in [$800,$900) AND X < $34C0 → state 0
        //   otherwise: stay in state 8 (motion keeps running via the object)
        if (playerY < BG_RISE_TRIGGER_Y_MIN) {
            if (playerX >= BG_RISE_EXIT_FORWARD_X_MIN) {
                bgRiseRoutine = BG_RISE_AFTER_MOVE;
                gameState().setBackgroundCollisionFlag(false);
                return;
            }
        } else if (playerY < BG_RISE_TRIGGER_Y_MAX
                && playerX < BG_RISE_EXIT_BACKWARD_X_MAX) {
            bgRiseRoutine = BG_RISE_NORMAL;
            gameState().setBackgroundCollisionFlag(false);
            return;
        }
    }

    /**
     * Runs the independently allocated {@code Obj_MGZ2BGMoveSonic} after both
     * player slots and before dynamic level objects. Unlike the background
     * event's collision-flag publication, this object consumes the positions
     * produced by the current frame's player movement. Its threshold-entry
     * path falls straight through to the first {@code $6000} accumulator step.
     */
    public void updateBgRiseObjectAfterPlayerPhysics(int act) {
        if (act != 1 || bgRiseRoutine != BG_RISE_SONIC) {
            return;
        }
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }
        updateBgRiseSonicObject(player, player.getCentreX(), player.getCentreY());
        MgzZoneRuntimeState runtimeState = currentMgzRuntimeState();
        if (runtimeState != null) {
            runtimeState.syncBgRiseToScrollHandler();
        }
    }

    /** ROM: independently allocated Obj_MGZ2BGMoveSonic SST body. */
    private void updateBgRiseSonicObject(AbstractPlayableSprite player, int playerX, int playerY) {
        // Obj_MGZ2BGMoveSonic body runs while state remains 8, decoupled from
        // the trigger's transition check. Motion start requires crossing both
        // BG_RISE_MOTION_X_MIN and BG_RISE_MOTION_Y_MIN (ROM: loc_51AF2).
        if (!bgRiseMotionStarted) {
            if (playerX <= BG_RISE_MOTION_X_MIN || playerY <= BG_RISE_MOTION_Y_MIN) {
                return;
            }
            bgRiseMotionStarted = true;
            camera().setMinX(camera().getX());
        }
        advanceBgRise(player, playerX);
    }

    /**
     * ROM: MGZ2_BGEventTrigger_Index state C — clear
     * {@code Background_collision_flag}; re-enter state 8 when the player
     * drops back into Y >= $800 AND X >= $3A40.
     */
    private void bgRiseAfterMove(int playerX, int playerY) {
        gameState().setBackgroundCollisionFlag(false);
        if (playerY >= BG_RISE_REENTRY_Y_MIN && playerX >= BG_RISE_REENTRY_X_MIN) {
            bgRiseRoutine = BG_RISE_SONIC;
            gameState().setBackgroundCollisionFlag(true);
        }
    }

    /**
     * ROM: Obj_MGZ2BGMoveSonic loc_51B1C-loc_51B84.
     *
     * <p>Per-frame: if accel latch active, newOffset = current + 1. Otherwise
     * add $6000 to the 16:16 subpixel accumulator and take its upper word.
     * If the new offset crosses the target, clamp at $1D0, play CRASH, arm the
     * $E-frame timed shake. Lift the focused player by the exact delta
     * (ROM: {@code sub.w d1,(Player_1+y_pos).w}).
     */
    private void advanceBgRise(AbstractPlayableSprite player, int playerX) {
        if (bgRiseOffset >= BG_RISE_TARGET_SONIC) {
            return;
        }
        boolean accelWasLatched = bgRiseAccelLatched;
        if (!accelWasLatched && playerX >= BG_RISE_ACCEL_X_MIN) {
            bgRiseAccelLatched = true;
        }
        int newOffset;
        // ROM loc_51B44 tests $39 before loc_51B6C sets it, so the threshold
        // crossing still consumes one final $6000 accumulator step. The
        // one-pixel path begins on the following object dispatch.
        if (accelWasLatched) {
            newOffset = bgRiseOffset + 1;
        } else {
            bgRiseSubpixelAccum += BG_RISE_SUBPIXEL_VELOCITY;
            newOffset = bgRiseSubpixelAccum >>> 16;
        }
        if (newOffset >= BG_RISE_TARGET_SONIC) {
            newOffset = BG_RISE_TARGET_SONIC;
            var audioManager = audio();
            if (audioManager != null) {
                audioManager.playSfx(Sonic3kSfx.CRASH.id);
            }
            bgRiseFinalShakeTimer = BG_RISE_FINAL_SHAKE_FRAMES;
            screenShakeActive = false;
        }
        int delta = newOffset - bgRiseOffset;
        if (delta <= 0) {
            return;
        }
        bgRiseOffset = newOffset;
        liftBgRisePassengers(player, delta);
    }

    private void liftBgRisePassengers(AbstractPlayableSprite player, int delta) {
        List<AbstractPlayableSprite> sidekicks = List.copyOf(GameServices.sprites().getSidekicks());
        ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                () -> player,
                () -> sidekicks);
        for (PlayableEntity passenger : playerQuery.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (passenger instanceof AbstractPlayableSprite playable) {
                NativePositionOps.addYPosPreserveSubpixel(playable, -delta);
            }
        }
    }

    public int getChunkEventRoutine() {
        return chunkEventRoutine;
    }

    void requestLevelCollapse() {
        collapseRequested = true;
    }

    /**
     * ROM: Obj_MGZ2_BossTransition. The boss floor impact starts the level
     * collapse and ensures Tails is present for Sonic's rescue/carry sequence,
     * even if the run was configured as Sonic alone.
     */
    public void triggerBossCollapseHandoff() {
        requestLevelCollapse();
        bossTransitionInitializationPending = true;
        bossTransitionDeathPlaneDisabled = true;
    }

    private void initializeBossTransition() {
        bossTransitionInitializationPending = false;
        Camera camera = camera();
        bossTransitionCameraX = camera.getX() & 0xFFFF;
        bossTransitionCameraY = camera.getY() & 0xFFFF;
        bossTransitionX = bossTransitionCameraX + BOSS_TRANSITION_SPAWN_OFFSET_X;
        bossTransitionY = bossTransitionCameraY + BOSS_TRANSITION_SPAWN_OFFSET_Y;
        bossTransitionTimer = BOSS_TRANSITION_WAIT_FRAMES;
        bossTransitionActive = true;
        cancelSameFrameBossTransitionPitDeath(camera.getFocusedSprite());
        ensureBossTransitionTails(bossTransitionX, bossTransitionY);
    }

    private void cancelSameFrameBossTransitionPitDeath(AbstractPlayableSprite player) {
        if (player == null || !player.getDead()) {
            return;
        }
        restoreBossTransitionPlayerRoutine(player);
        // The engine can already have applied its death-camera freeze before
        // the later transition SST cancels that same-frame pit death. Release
        // only that cancelled-death freeze; ordinary routine restoration must
        // not clear the post-results Scroll_lock owned by loc_6C8F4.
        camera().setFrozen(false);
        if (player.getYSpeed() == PIT_DEATH_BOUNCE_Y_SPEED) {
            player.setYSpeed((short) 0);
        }
    }

    private void ensureBossTransitionTails(int spawnX, int spawnY) {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (!usesSonicBossTransitionPath(player, resolveBossTransitionPlayerCharacter())) {
            return;
        }

        AbstractPlayableSprite existingTails = spriteManager().getSidekicks().stream()
                .filter(this::isBossTransitionTails)
                .findFirst()
                .orElse(null);
        if (existingTails != null) {
            // ROM Obj_MGZ2_BossTransition tests Player_2.render_flags before it
            // writes the transition position or CPU routine $12. A visible
            // Tails keeps running his current routine until he naturally
            // leaves the screen; only an off-screen slot is reinitialised.
            boolean tailsOnScreen = existingTails.hasRenderFlagOnScreenState()
                    ? existingTails.isRenderFlagOnScreen()
                    : camera().isVisibleForRenderFlag(existingTails);
            if (tailsOnScreen) {
                return;
            }
            prepareBossTransitionTails(existingTails, player, spawnX, spawnY);
            return;
        }

        Tails tails = new Tails(BOSS_TRANSITION_TEMP_TAILS_CODE, (short) spawnX, (short) spawnY);
        prepareBossTransitionTails(tails, player, spawnX, spawnY);
        spriteManager().addTemporarySidekick(tails, "tails");
        refreshRuntimeTailsArt();
    }

    private void prepareBossTransitionTails(AbstractPlayableSprite tails, AbstractPlayableSprite player,
                                            int spawnX, int spawnY) {
        tails.setCentreX((short) spawnX);
        tails.setCentreY((short) (spawnY - 1));
        tails.setDead(false);
        tails.setAir(true);
        tails.setXSpeed((short) 0);
        tails.setYSpeed((short) 0);
        tails.setGSpeed((short) 0);
        tails.setSpindash(false);
        tails.setControlLocked(false);
        ObjectControlState.none().applyTo(tails);
        tails.setCpuControlled(true);
        SidekickCpuController controller = new SidekickCpuController(tails, player);
        controller.setInitialState(SidekickCpuController.State.MGZ_RESCUE_WAIT);
        tails.setCpuController(controller);
        refreshRuntimeTailsArt();
    }

    private void updateBossTransition() {
        if (bossTransitionInitializationPending) {
            initializeBossTransition();
            return;
        }
        if (!bossTransitionActive) {
            return;
        }
        if (bossTransitionTimer > 0) {
            bossTransitionTimer--;
        }

        AbstractPlayableSprite player = camera().getFocusedSprite();
        PlayerCharacter character = resolveBossTransitionPlayerCharacter();
        if (isFocusedTailsPlayer(player)) {
            updateTailsAloneBossTransition(player);
            return;
        }
        if (!usesSonicBossTransitionPath(player, character)) {
            return;
        }
        if (player.getDead()) {
            bossTransitionActive = false;
            bossTransitionDeathPlaneDisabled = false;
            return;
        }

        AbstractPlayableSprite tails = findBossTransitionTails();
        if (tails == null) {
            ensureBossTransitionTails(bossTransitionX, bossTransitionY);
            return;
        }
        SidekickCpuController controller = tails != null ? tails.getCpuController() : null;
        boolean carrying = controller != null && controller.isFlyingCarrying();
        boolean playerBelowTransition = bossTransitionY < (player.getCentreY() & 0xFFFF);

        if (!carrying && playerBelowTransition) {
            // Obj_MGZ2_BossTransition uses move.w d0,y_pos(a1), preserving
            // the fractional word (sonic3k.asm:30225-30231).
            NativePositionOps.writeYPosPreserveSubpixel(player, bossTransitionY);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
            player.setSpindash(false);
            restoreBossTransitionPlayerRoutine(player);
            if (isBossTransitionCarryRoutine(controller)) {
                bossTransitionX = player.getCentreX() & 0xFFFF;
            }
        }

        boolean tailsBelowTransition = bossTransitionY < (tails.getCentreY() & 0xFFFF);
        // loc_16384 is independent of Flying_carrying_Sonic_flag: once Sonic
        // is off-screen and Tails falls below the transition SST, it writes
        // CPU routine $14 even if the flag is still set. The earlier loc_16340
        // player clamp is the only branch gated by the carry flag.
        if (tailsBelowTransition) {
            if (!isBossTransitionPlayerReadyForCarry(player)) {
                return;
            }
            // loc_16384 publishes Tails_CPU_routine=$12 before testing the
            // transition object's $30 wait timer (sonic3k.asm:30247-30259).
            if (controller == null) {
                controller = new SidekickCpuController(tails, player);
                tails.setCpuController(controller);
            }
            if (controller.getState() != SidekickCpuController.State.MGZ_RESCUE_WAIT) {
                controller.setInitialState(SidekickCpuController.State.MGZ_RESCUE_WAIT);
            }
            if (bossTransitionTimer > 0) {
                return;
            }
            startBossTransitionCarry(player, tails);
        }
    }

    private boolean isBossTransitionPlayerReadyForCarry(AbstractPlayableSprite player) {
        if (player == null || !isNativePlayerRoutine2(player)) {
            return false;
        }
        boolean playerOnScreen = player.hasRenderFlagOnScreenState()
                ? player.isRenderFlagOnScreen()
                : camera().isVisibleForRenderFlag(player);
        return !playerOnScreen;
    }

    /** ROM loc_16384 requires Player_1's native routine byte to equal $02 exactly. */
    private boolean isNativePlayerRoutine2(AbstractPlayableSprite player) {
        return NativePlayableRoutine.resolve(player) == NativePlayableRoutine.CONTROL;
    }

    private boolean isBossTransitionCarryRoutine(SidekickCpuController controller) {
        if (controller == null) {
            return false;
        }
        SidekickCpuController.State state = controller.getState();
        return state == SidekickCpuController.State.CARRY_INIT
                || state == SidekickCpuController.State.CARRYING;
    }

    private PlayerCharacter resolveBossTransitionPlayerCharacter() {
        return ActiveGameplayTeamResolver.resolvePlayerCharacter(GameServices.configuration());
    }

    private boolean usesSonicBossTransitionPath(AbstractPlayableSprite player, PlayerCharacter character) {
        if (player == null || isFocusedTailsPlayer(player)) {
            return false;
        }
        return isFocusedSonicPlayer(player)
                || character == PlayerCharacter.SONIC_ALONE
                || character == PlayerCharacter.SONIC_AND_TAILS;
    }

    private boolean isFocusedSonicPlayer(AbstractPlayableSprite player) {
        return player instanceof Sonic
                || player.getCode().toLowerCase(java.util.Locale.ROOT).startsWith("sonic");
    }

    private boolean isFocusedTailsPlayer(AbstractPlayableSprite player) {
        return player instanceof Tails
                || player.getCode().toLowerCase(java.util.Locale.ROOT).startsWith("tails");
    }

    private void updateTailsAloneBossTransition(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        if (bossTransitionY < (player.getCentreY() & 0xFFFF)) {
            if (bossTransitionTimer > 0) {
                player.setCentreY((short) (bossTransitionY + BOSS_TRANSITION_TAILS_ALONE_HOLD_OFFSET_Y));
                bossTransitionX = player.getCentreX() & 0xFFFF;
                return;
            }
            player.setCentreY((short) bossTransitionY);
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
            player.setGSpeed((short) 0);
            player.setSpindash(false);
            player.setAir(true);
            ObjectControlState.none().applyTo(player);
            restoreBossTransitionPlayerRoutine(player);
            applyTailsAlonePostTransitionCpuRoutine(player);
        }
        bossTransitionX = player.getCentreX() & 0xFFFF;
    }

    private void applyTailsAlonePostTransitionCpuRoutine(AbstractPlayableSprite player) {
        // ROM loc_163F4 writes Tails_CPU_routine=$1A. The following Tails CPU
        // dispatch enters loc_141F2, primes flight, zeros velocity, then advances
        // to routine $1C. Focused-player Tails does not use SidekickCpuController,
        // so mirror the one-shot object fields the routine establishes here.
        player.setDoubleJumpFlag(1);
        player.setDoubleJumpProperty((byte) 0xF0);
        player.setAir(true);
    }

    public boolean isBossTransitionDeathPlaneDisabled() {
        return bossTransitionDeathPlaneDisabled;
    }

    private AbstractPlayableSprite findBossTransitionTails() {
        return spriteManager().getSidekicks().stream()
                .filter(this::isBossTransitionTails)
                .findFirst()
                .orElse(null);
    }

    private boolean isBossTransitionTails(AbstractPlayableSprite sidekick) {
        String characterName = spriteManager().getSidekickCharacterName(sidekick);
        return "tails".equalsIgnoreCase(characterName)
                || sidekick instanceof Tails
                || sidekick.getCode().toLowerCase(java.util.Locale.ROOT).startsWith("tails");
    }

    private void startBossTransitionCarry(AbstractPlayableSprite player, AbstractPlayableSprite tails) {
        NativePositionOps.writeXPosPreserveSubpixel(tails, bossTransitionX);
        NativePositionOps.writeYPosPreserveSubpixel(tails, bossTransitionY);
        ObjectControlState.none().applyTo(tails);
        tails.setSpindash(false);
        // Obj_MGZ2_BossTransition writes Player_2 routine=2 before CPU routine $14,
        // which exits Tails's hurt routine even if he was hit during the rescue.
        tails.setHurt(false);
        tails.setDead(false);
        tails.setCpuControlled(true);

        SidekickCpuController controller = tails.getCpuController();
        if (controller == null) {
            controller = new SidekickCpuController(tails, player);
            tails.setCpuController(controller);
        }
        controller.setCarryTrigger(mgzBossTransitionCarryTrigger());
        // The transition SST only publishes CPU routine $14 here. loc_140CE
        // attaches Sonic and sets Flying_carrying_Sonic_flag on the following
        // Player_2 pass; doing that from this later object slot moves both
        // players one frame early.
        controller.setInitialState(SidekickCpuController.State.CARRY_INIT);
    }

    private void restoreBossTransitionPlayerRoutine(AbstractPlayableSprite player) {
        // Obj_MGZ2_BossTransition writes Player_1 routine=2 after pulling Sonic
        // back to its y_pos, so any hurt/death routine entered while falling is
        // cancelled before Tails_Carry_Sonic tests routine >= 4.
        player.setHurt(false);
        player.setDead(false);
        player.setDeathCountdown(0);
        player.setForcedAnimationId(-1);
        player.setHighPriority(false);
    }

    private SidekickCarryTrigger mgzBossTransitionCarryTrigger() {
        return new SidekickCarryTrigger() {
            @Override
            public boolean shouldEnterCarry(int zoneId, int actId, PlayerCharacter playerMode) {
                return false;
            }

            @Override
            public void applyInitialPlacement(AbstractPlayableSprite carrier, AbstractPlayableSprite cargo) {
                carrier.setCentreXPreserveSubpixel((short) bossTransitionX);
                carrier.setCentreYPreserveSubpixel((short) bossTransitionY);
            }

            @Override
            public int carryDescendOffsetY() {
                return Sonic3kConstants.CARRY_DESCEND_OFFSET_Y;
            }

            @Override
            public short carryInitXVel() {
                return 0;
            }

            @Override
            public int carryInputInjectMask() {
                return BOSS_TRANSITION_TAILS_INPUT_MASK;
            }

            @Override
            public boolean carryInjectsJump() {
                return true;
            }

            @Override
            public boolean usesMgzBossTransitionControl() {
                return true;
            }

            @Override
            public int carryJumpReleaseCooldownFrames() {
                return Sonic3kConstants.CARRY_COOLDOWN_JUMP_RELEASE;
            }

            @Override
            public int carryLatchReleaseCooldownFrames() {
                return Sonic3kConstants.CARRY_COOLDOWN_LATCH_RELEASE;
            }

            @Override
            public short carryReleaseJumpYVel() {
                return Sonic3kConstants.CARRY_RELEASE_JUMP_Y_VEL;
            }

            @Override
            public short carryReleaseJumpXVel() {
                return Sonic3kConstants.CARRY_RELEASE_JUMP_X_VEL;
            }
        };
    }

    private void refreshRuntimeTailsArt() {
        LevelManager manager = levelManager();
        if (manager != null) {
            manager.refreshPlayableSpriteArt();
        }
    }

    boolean isCollapseActive() {
        return screenEventRoutine == SCREEN_EVENT_COLLAPSE && !collapseFinished;
    }

    /**
     * Builds the foreground per-column VScroll values for the MGZ2 boss floor
     * collapse. ROM uses cell-based VScroll for Scroll A while the chunks melt
     * away; the engine feeds the same ten accumulated scroll values into the
     * 20 visible 16px columns, repeating each 32px collapse block twice.
     */
    public short[] buildCollapseForegroundVScrollOverride(int cameraX) {
        if (!isCollapseActive() || !collapseInitialized) {
            return null;
        }

        short[] override = new short[20];

        int firstScreenColumn = Math.floorDiv((COLLAPSE_REGION_X * 0x80) - cameraX, 16);
        boolean anyVisible = false;
        boolean anyScrolled = false;
        for (int i = 0; i < COLLAPSE_COLUMN_COUNT; i++) {
            int screenColumn = firstScreenColumn + i * 2;
            if (screenColumn >= override.length || screenColumn + 1 < 0) {
                continue;
            }

            int scroll = collapseScrollPosition[i];
            short vScroll = (short) -scroll;
            for (int repeat = 0; repeat < 2; repeat++) {
                int column = screenColumn + repeat;
                if (column >= 0 && column < override.length) {
                    override[column] = vScroll;
                    anyVisible = true;
                }
            }
            anyScrolled |= scroll != 0;
        }

        return anyVisible && anyScrolled ? override : null;
    }

    public boolean isCollapseFinished() {
        return collapseFinished;
    }

    public int getCollapseMutationCount() {
        return collapseMutationCount;
    }

    int getCollapseSolidCountForTest() {
        int count = 0;
        for (Mgz2LevelCollapseSolidInstance solid : collapseSolids) {
            if (solid != null) {
                count++;
            }
        }
        return count;
    }

    int getBossBgScrollVelocityForTest() {
        return bossBgScrollVelocity;
    }

    int getBossBgScrollOffsetForTest() {
        return bossBgScrollOffset;
    }

    public int getScreenEventRoutine() {
        return screenEventRoutine;
    }

    @Override
    public int getDynamicResizeRoutine() {
        return screenEventRoutine;
    }

    @Override
    public void setDynamicResizeRoutine(int routine) {
        screenEventRoutine = routine;
    }

    private void updateAct2ChunkEvent() {
        AbstractPlayableSprite player = camera().getFocusedSprite();
        if (player == null) {
            return;
        }
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();
        switch (chunkEventRoutine) {
            case CHUNK_EVENT_CHECK -> {
                chunkEventCheck(playerX, playerY);
                if (chunkEventRoutine != CHUNK_EVENT_CHECK) {
                    dispatchChunkEventRoutine(playerX);
                }
            }
            case CHUNK_EVENT_1, CHUNK_EVENT_2, CHUNK_EVENT_3, CHUNK_EVENT_RESET ->
                    dispatchChunkEventRoutine(playerX);
            default -> {
            }
        }
    }

    private void dispatchChunkEventRoutine(int playerX) {
        switch (chunkEventRoutine) {
            case CHUNK_EVENT_1 -> runChunkEvent1();
            case CHUNK_EVENT_2, CHUNK_EVENT_3 -> runChunkEvent2Or3();
            case CHUNK_EVENT_RESET -> runChunkEventReset(playerX);
            default -> {
            }
        }
    }

    private void chunkEventCheck(int playerX, int playerY) {
        int nextRoutine = CHUNK_EVENT_1;
        for (int[] entry : CHUNK_EVENT_ARRAY) {
            if (playerX >= entry[0] && playerX < entry[1]
                    && playerY >= entry[2] && playerY < entry[3]) {
                if (nextRoutine == CHUNK_EVENT_1 && !screenShakeActive) {
                    return;
                }
                chunkEventRoutine = nextRoutine;
                chunkReplaceIndex = 0;
                chunkEventDelay = 0;
                return;
            }
            nextRoutine += 4;
        }
    }

    private void runChunkEvent1() {
        if (chunkReplaceIndex < CHUNK_EVENT_FINAL_REPLACE_INDEX) {
            advanceChunkEventMutation();
            return;
        }
        screenShakeActive = false;
        quakeEventRoutine = QUAKE_CHECK;
        camera().setMinX((short) DEFAULT_CAMERA_MIN_X);
        chunkEventRoutine = CHUNK_EVENT_RESET;
    }

    private void runChunkEvent2Or3() {
        if (chunkReplaceIndex < CHUNK_EVENT_FINAL_REPLACE_INDEX) {
            advanceChunkEventMutation();
            return;
        }
        camera().setMinX((short) DEFAULT_CAMERA_MIN_X);
        camera().setMinXTarget((short) DEFAULT_CAMERA_MIN_X);
        camera().setMaxX((short) DEFAULT_CAMERA_MAX_X);
        camera().setMaxXTarget((short) DEFAULT_CAMERA_MAX_X);
        gradualUnlockDirection = GRADUAL_UNLOCK_NONE;
        gradualUnlockAccumulator = 0;
        chunkEventRoutine = CHUNK_EVENT_DONE;
    }

    private void runChunkEventReset(int playerX) {
        if (playerX < 0x2A00) {
            return;
        }
        chunkEventRoutine = CHUNK_EVENT_CHECK;
        applyChunkMutationPair(CHUNK_EVENT_FINAL_REPLACE_INDEX);
        chunkReplaceIndex = CHUNK_EVENT_FINAL_REPLACE_INDEX;
    }

    private void advanceChunkEventMutation() {
        chunkEventDelay--;
        if (chunkEventDelay >= 0) {
            return;
        }
        chunkEventDelay = CHUNK_EVENT_DELAY_RESET;
        applyChunkMutationPair(chunkReplaceIndex);
        chunkReplaceIndex += 4;
    }

    private void applyChunkMutationPair(int replaceIndex) {
        int entryIndex = replaceIndex / 2;
        if (entryIndex < 0 || entryIndex + 1 >= CHUNK_REPLACE_ARRAY.length) {
            return;
        }
        int leftOffset = CHUNK_REPLACE_ARRAY[entryIndex];
        int rightOffset = CHUNK_REPLACE_ARRAY[entryIndex + 1];
        int[] leftState = readQuakeBlockState(leftOffset);
        int[] rightState = readQuakeBlockState(rightOffset);
        if (leftState == null || rightState == null) {
            return;
        }
        applyImmediateMgzMutation(context -> mergeEffects(
                context.surface().restoreBlockState(MGZ_QUAKE_BLOCK_LEFT_INDEX, leftState),
                context.surface().restoreBlockState(MGZ_QUAKE_BLOCK_RIGHT_INDEX, rightState)));
    }

    private int[] readQuakeBlockState(int offset) {
        byte[] data = loadMgzQuakeChunkData();
        if (data == null || offset < 0 || offset + 0x80 > data.length) {
            return null;
        }
        int[] state = new int[64];
        for (int i = 0; i < state.length; i++) {
            int byteIndex = offset + i * 2;
            state[i] = ((data[byteIndex] & 0xFF) << 8) | (data[byteIndex + 1] & 0xFF);
        }
        return state;
    }

    protected byte[] loadMgzQuakeChunkData() {
        byte[] cached = cachedMgzQuakeChunkData;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cachedMgzQuakeChunkData != null) {
                return cachedMgzQuakeChunkData;
            }
            try {
                byte[] romBytes = rom().readBytes(MGZ_QUAKE_CHUNK_ROM_ADDR, 0x1080);
                cachedMgzQuakeChunkData = romBytes;
                return romBytes;
            } catch (Exception romFailure) {
                LOG.warning("Failed to load MGZ2 quake chunk data from ROM: " + romFailure.getMessage());
                return null;
            }
        }
    }

    private void applyImmediateMgzMutation(LayoutMutationIntent intent) {
        LevelManager levelManager = levelManager();
        Level level = levelManager != null ? levelManager.getCurrentLevel() : null;
        if (level == null) {
            return;
        }
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager::applyMutationEffects);
        if (hasRuntime()) {
            zoneLayoutMutationPipeline().applyImmediately(intent, context);
            return;
        }
        levelManager.applyMutationEffects(intent.apply(context));
    }

    /**
     * Like {@link #applyImmediateMgzMutation} but strips all redraw hints from the
     * published {@link MutationEffects}.  Use for snapshot-then-clear effects where
     * the cleared tiles must remain invisible until an explicit redraw is triggered.
     */
    private void applyImmediateMgzMutationWithoutRedraw(LayoutMutationIntent intent) {
        LevelManager levelManager = levelManager();
        Level level = levelManager != null ? levelManager.getCurrentLevel() : null;
        if (level == null) {
            return;
        }
        LayoutMutationContext context = new LayoutMutationContext(
                LevelMutationSurface.forLevel(level),
                levelManager::applyMutationEffects);
        if (hasRuntime()) {
            zoneLayoutMutationPipeline().applyImmediatelyWithoutRedraw(intent, context);
            return;
        }
        levelManager.applyMutationEffects(intent.apply(context).withoutRedrawHints());
    }

    private static MutationEffects mergeEffects(MutationEffects... effects) {
        BitSet dirtyPatterns = new BitSet();
        boolean dirtyRegions = false;
        boolean redraw = false;
        boolean redrawAll = false;
        boolean patternLookupRefresh = false;
        boolean objectResync = false;
        boolean ringResync = false;
        if (effects != null) {
            for (MutationEffects effect : effects) {
                if (effect == null) {
                    continue;
                }
                dirtyPatterns.or(effect.dirtyPatterns());
                dirtyRegions |= effect.dirtyRegionProcessingRequired();
                redraw |= effect.foregroundRedrawRequired();
                redrawAll |= effect.allTilemapsRedrawRequired();
                patternLookupRefresh |= effect.patternLookupRefreshRequired();
                objectResync |= effect.objectResyncRequired();
                ringResync |= effect.ringResyncRequired();
            }
        }
        return new MutationEffects(dirtyPatterns, dirtyRegions, redraw, redrawAll,
                patternLookupRefresh, objectResync, ringResync);
    }

    private void updateAct2Collapse(int frameCounter) {
        if (collapseRequested && screenEventRoutine == SCREEN_EVENT_NORMAL) {
            // Obj_MGZEndBoss publishes Events_fg_4 from its SST. MGZ2SE_Normal
            // consumes that write on the following screen-event dispatch; it
            // then loads Screen_shake_flag=$14 after ShakeScreen_Setup has
            // already run, so the new positive countdown is not decremented
            // on its arm frame (sonic3k.asm:106412-106427,142844-142866).
            if (!collapseRequestObserved) {
                collapseRequestObserved = true;
                return;
            }
            screenEventRoutine = SCREEN_EVENT_COLLAPSE;
            screenShakeActive = true;
            collapseStartupShakeTimer = COLLAPSE_STARTUP_SHAKE_FRAMES;
            return;
        }
        if (screenEventRoutine != SCREEN_EVENT_COLLAPSE || collapseFinished) {
            return;
        }
        if (collapseStartupShakeTimer > 0) {
            collapseStartupShakeTimer--;
            // Keep the zero written by the timed-shake owner invisible to the
            // collapse dispatch until its next ScreenEvents observation. This
            // preserves the separate ShakeScreen_Setup / MGZ2SE_Collapse phase
            // instead of falling through in the same Java call.
            return;
        }
        if (!collapseInitialized) {
            snapshotForegroundTilemapBeforeCollapseClear();
            clearForegroundRegionWithoutRedraw(COLLAPSE_REGION_X, COLLAPSE_OPENING_Y,
                    COLLAPSE_REGION_WIDTH, COLLAPSE_REGION_HEIGHT);
            collapseMutationCount++;
            createCollapseSolids();
            collapseInitialized = true;
            // ROM falls through from initialization into the scrolling path
            // in this same screen-event dispatch.
            playCollapseRumble(frameCounter);
            return;
        }

        int collapseDelayCounter = collapseFrameCounter;
        collapseFrameCounter++;
        boolean allColumnsFinished = true;
        for (int i = 0; i < COLLAPSE_COLUMN_COUNT; i++) {
            if (collapseDelayCounter >= COLLAPSE_SCROLL_DELAYS[i]) {
                collapseScrollVelocity[i] += COLLAPSE_SCROLL_ACCEL;
                collapseScrollFixedPosition[i] += collapseScrollVelocity[i];
                collapseScrollPosition[i] = Math.min(COLLAPSE_MAX_SCROLL,
                        collapseScrollFixedPosition[i] >>> 16);
            }
            if (collapseScrollPosition[i] < COLLAPSE_MAX_SCROLL) {
                allColumnsFinished = false;
            }
        }
        playCollapseRumble(frameCounter);
        if (!allColumnsFinished) {
            return;
        }

        clearForegroundRegion(COLLAPSE_REGION_X, COLLAPSE_FINAL_Y,
                COLLAPSE_REGION_WIDTH, COLLAPSE_REGION_HEIGHT);
        collapseMutationCount++;
        collapseFinished = true;
        collapseRequested = false;
        screenShakeActive = false;
        bossBgScrollVelocity = 0;
        bossBgScrollOffset = camera().getX() & 0xFFFF;
        publishBossBgScrollOffset();
        screenEventRoutine = SCREEN_EVENT_MOVE_BG;
    }

    private void updateAct2BossBgScroll() {
        if (screenEventRoutine != SCREEN_EVENT_MOVE_BG) {
            return;
        }
        if (bossBgScrollVelocity < BOSS_BG_SCROLL_MAX) {
            bossBgScrollVelocity = Math.min(BOSS_BG_SCROLL_MAX,
                    bossBgScrollVelocity + BOSS_BG_SCROLL_ACCEL);
        }
        bossBgScrollOffset = (bossBgScrollOffset + (bossBgScrollVelocity >>> 16)) & 0xFFFF;
        publishBossBgScrollOffset();
    }

    private void publishBossBgScrollOffset() {
        MgzZoneRuntimeState state = currentMgzRuntimeState();
        if (state != null) {
            state.publishBossBgScrollOffset(bossBgScrollOffset);
        }
    }

    private void createCollapseSolids() {
        if (collapseSolids[0] != null) {
            return;
        }
        int solidIndex = 0;
        int x = COLLAPSE_SOLID_START_X;
        for (int column = 0; column < COLLAPSE_COLUMN_COUNT; column++) {
            final int scrollColumn = column;
            collapseSolids[solidIndex] = new Mgz2LevelCollapseSolidInstance(
                    x,
                    COLLAPSE_SOLID_HIGH_BASE_Y,
                    () -> collapseSolidObjectPassScroll(scrollColumn),
                    this::isCollapseSolidDeleteState);
            spawnObject(collapseSolids[solidIndex]);
            solidIndex++;

            collapseSolids[solidIndex] = new Mgz2LevelCollapseSolidInstance(
                    x,
                    COLLAPSE_SOLID_LOW_BASE_Y,
                    () -> collapseSolidObjectPassScroll(scrollColumn),
                    this::isCollapseSolidDeleteState);
            spawnObject(collapseSolids[solidIndex]);
            solidIndex++;

            x += COLLAPSE_SOLID_STEP_X;
        }
    }

    private boolean isCollapseSolidDeleteState() {
        return screenEventRoutine == SCREEN_EVENT_MOVE_BG
                || collapseFinished
                || collapseFinishesOnPendingEventStep();
    }

    /**
     * The engine's ScreenEvents bridge publishes after dynamic objects, while
     * the native next object pass already observes the routine selected by the
     * preceding collapse dispatch. Project the same pending all-columns test as
     * the scroll supplier so a carrier deletes instead of applying one final
     * ride snap on the transition frame.
     */
    private boolean collapseFinishesOnPendingEventStep() {
        if (screenEventRoutine != SCREEN_EVENT_COLLAPSE
                || !collapseInitialized
                || collapseFinished) {
            return false;
        }
        for (int i = 0; i < COLLAPSE_COLUMN_COUNT; i++) {
            int projectedFixedPosition = collapseScrollFixedPosition[i];
            if (collapseFrameCounter >= COLLAPSE_SCROLL_DELAYS[i]) {
                projectedFixedPosition += collapseScrollVelocity[i] + COLLAPSE_SCROLL_ACCEL;
            }
            if ((projectedFixedPosition >>> 16) < COLLAPSE_MAX_SCROLL) {
                return false;
            }
        }
        return true;
    }

    /**
     * Scroll word visible when Obj_MGZ2LevelCollapseSolid executes this frame.
     * The ROM's ScreenEvents owner advances the HScroll-table longword before
     * the carrier reads it. The engine's canonical event update is later than
     * dynamic objects, so project exactly that pending accumulator step without
     * publishing it early to the event/render state.
     */
    private int collapseSolidObjectPassScroll(int column) {
        int current = collapseScrollFixedPosition[column] >>> 16;
        if (screenEventRoutine != SCREEN_EVENT_COLLAPSE
                || !collapseInitialized
                || collapseFinished
                || collapseFrameCounter < COLLAPSE_SCROLL_DELAYS[column]) {
            return current;
        }
        int nextVelocity = collapseScrollVelocity[column] + COLLAPSE_SCROLL_ACCEL;
        int nextFixedPosition = collapseScrollFixedPosition[column] + nextVelocity;
        // loc_51436 clamps only d3, the temporary displacement used by the
        // VScroll draw and completion count. The HScroll-table longword itself
        // keeps its overshooting 16:16 accumulator, and the later collapse-solid
        // SST reads that raw high word at loc_51818. On the terminal pass this
        // can place a carrier below the visual $2E0 cap (for example $2E5).
        return nextFixedPosition >>> 16;
    }

    int getCollapseSolidObjectPassScrollForTest(int column) {
        return collapseSolidObjectPassScroll(column);
    }

    boolean isCollapseSolidDeleteStateForTest() {
        return isCollapseSolidDeleteState();
    }

    /**
     * Rewind recreate factory for a single MGZ2 level-collapse solid. The solid's
     * two functional-interface constructor args (scroll supplier + delete supplier)
     * are bound to this live event manager and cannot be reconstructed from the
     * captured {@link ObjectSpawn} alone, so the recreate hook relinks
     * through here. anchorX/baseY come from the spawn; the column index is recovered
     * from anchorX. The recreated solid is re-registered into the (transient)
     * tracking array so the manager's per-frame updates and SCREEN_EVENT_MOVE_BG
     * teardown continue to drive/destroy it after rewind. Returns {@code null} if
     * the anchor is out of range.
     */
    public Mgz2LevelCollapseSolidInstance recreateCollapseSolidForRewind(ObjectSpawn spawn) {
        int anchorX = spawn.x();
        int baseY = spawn.y();
        int column = (anchorX - COLLAPSE_SOLID_START_X) / COLLAPSE_SOLID_STEP_X;
        if (column < 0 || column >= COLLAPSE_COLUMN_COUNT) {
            return null;
        }
        final int scrollColumn = column;
        Mgz2LevelCollapseSolidInstance solid = new Mgz2LevelCollapseSolidInstance(
                anchorX,
                baseY,
                () -> collapseSolidObjectPassScroll(scrollColumn),
                this::isCollapseSolidDeleteState);
        // createCollapseSolids() registers the high solid first (index even) then
        // the low solid (index odd) per column; mirror that mapping here.
        int solidIndex = scrollColumn * 2
                + (baseY == COLLAPSE_SOLID_LOW_BASE_Y ? 1 : 0);
        if (solidIndex >= 0 && solidIndex < collapseSolids.length) {
            collapseSolids[solidIndex] = solid;
        }
        return solid;
    }

    private void snapshotForegroundTilemapBeforeCollapseClear() {
        LevelManager levelManager = levelManager();
        if (levelManager != null) {
            levelManager.snapshotForegroundTilemapBeforeRuntimeLayoutMutation();
        }
    }

    private void clearForegroundRegionWithoutRedraw(int startX, int startY, int width, int height) {
        applyImmediateMgzMutationWithoutRedraw(context -> {
            MutationEffects combined = MutationEffects.NONE;
            for (int y = startY; y < startY + height; y++) {
                for (int x = startX; x < startX + width; x++) {
                    combined = mergeEffects(combined,
                            context.surface().setBlockInMapWithoutRedraw(0, x, y, 0));
                }
            }
            return combined;
        });
    }

    private void clearForegroundRegion(int startX, int startY, int width, int height) {
        applyImmediateMgzMutation(context -> {
            MutationEffects combined = MutationEffects.NONE;
            for (int y = startY; y < startY + height; y++) {
                for (int x = startX; x < startX + width; x++) {
                    combined = mergeEffects(combined, context.surface().setBlockInMap(0, x, y, 0));
                }
            }
            return combined;
        });
    }

    /**
     * Hook fired when the third drilling Robotnik mini-event completes its
     * flee sequence (ROM: QuakeEvent3Cont). The default implementation logs
     * this route transition; the MGZ2_Resize path owns the end-boss spawn and
     * arena setup.
     */
    protected void onMgz2BossArenaReached() {
        LOG.info("MGZ2 drilling Robotnik third release reached; MGZ2_Resize owns end-boss spawn");
    }

    private void updateAct1Bg() {
        switch (bgRoutine) {
            case BG_STAGE_NORMAL -> {
                if (eventsFg5) {
                    eventsFg5 = false;
                    bgRoutine = BG_STAGE_DO_TRANSITION;
                    queueMgz2TransitionResources();
                    LOG.info("MGZ1 BG: Events_fg_5 detected, advancing to transition stage");
                }
            }
            case BG_STAGE_DO_TRANSITION -> {
                // ROM waits only for the three MGZ2 secondary Kos/KosM streams
                // queued by MGZ1BGE_Normal. Obj_LevelResults remains alive and
                // continues its tally across Load_Level; End_of_level_flag is
                // therefore not this transition's owner.
                rebindTransitionKosAfterRewind();
                if (!transitionRequested && transitionArtHandle != null
                        && !transitionModuleQueue.modulesLeft()) {
                    claimTransitionKos("MGZ2");
                    requestMgz2Transition();
                }
            }
            default -> { }
        }
    }

    private void queueMgz2TransitionResources() {
        try {
            transitionDirectQueue = directKosQueue();
            transitionModuleQueue = moduleKosQueue();
            S3kKosTransitionPreflight.validate(
                    rom(), transitionDirectQueue, transitionModuleQueue,
                    Sonic3kConstants.KOS_MGZ2_SECONDARY_CHUNK_ADDR,
                    Sonic3kConstants.KOS_MGZ2_SECONDARY_BLOCK_ADDR,
                    Sonic3kConstants.KOSM_MGZ2_SECONDARY_ART_ADDR);
            transitionChunkHandle = transitionDirectQueue.queueStandardKos(
                    rom(), Sonic3kConstants.KOS_MGZ2_SECONDARY_CHUNK_ADDR,
                    S3kKosRamDestinations.RAM_START + 0x6B00);
            transitionChunkOrdinal = transitionChunkHandle.ordinal();
            transitionBlockHandle = transitionDirectQueue.queueStandardKos(
                    rom(), Sonic3kConstants.KOS_MGZ2_SECONDARY_BLOCK_ADDR,
                    S3kKosRamDestinations.blockTableOffset(0xC60));
            transitionBlockOrdinal = transitionBlockHandle.ordinal();
            transitionArtHandle = transitionModuleQueue.queue(
                    rom(), Sonic3kConstants.KOSM_MGZ2_SECONDARY_ART_ADDR, 0x252);
            transitionArtOrdinal = transitionArtHandle.ordinal();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to queue MGZ2 transition resources", e);
        }
    }

    private void claimTransitionKos(String zone) {
        if (!transitionDirectQueue.isReady(transitionChunkHandle)
                || !transitionDirectQueue.isReady(transitionBlockHandle)
                || !transitionModuleQueue.isReady(transitionArtHandle)) {
            throw new IllegalStateException(zone + " queue emptied before owned payloads were ready");
        }
        transitionDirectQueue.claim(transitionChunkHandle);
        transitionDirectQueue.claim(transitionBlockHandle);
        transitionModuleQueue.claim(transitionArtHandle);
        clearTransitionKosOwnership();
    }

    private void rebindTransitionKosAfterRewind() {
        if (transitionArtOrdinal < 0 || transitionModuleQueue != null) {
            return;
        }
        var timing = hardwareTiming();
        transitionChunkHandle = timing.pendingHandle(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, transitionChunkOrdinal).orElseThrow();
        transitionBlockHandle = timing.pendingHandle(
                HardwareWorkKind.KOS_DECOMPRESSION_QUEUE, transitionBlockOrdinal).orElseThrow();
        transitionArtHandle = timing.pendingHandle(
                HardwareWorkKind.KOS_MODULE_QUEUE, transitionArtOrdinal).orElseThrow();
        transitionDirectQueue = directKosQueue();
        transitionModuleQueue = moduleKosQueue();
    }

    public void discardHardwareWorkFacadesAfterRewind() {
        transitionDirectQueue = null;
        transitionModuleQueue = null;
        transitionChunkHandle = null;
        transitionBlockHandle = null;
        transitionArtHandle = null;
    }

    private void clearTransitionKosOwnership() {
        discardHardwareWorkFacadesAfterRewind();
        transitionChunkOrdinal = -1;
        transitionBlockOrdinal = -1;
        transitionArtOrdinal = -1;
    }

    /**
     * Requests the seamless transition from MGZ Act 1 to MGZ Act 2.
     * ROM: MGZ1BGE_Transition (sonic3k.asm lines 106307-106345).
     */
    private void requestMgz2Transition() {
        transitionRequested = true;

        // The player is still in the signpost victory pose (objectControlled).
        // After the seamless reload lands in MGZ Act 2, the level event manager
        // releases them so normal play can resume.
        S3kTransitionWriteSupport.requestMgzPostTransitionRelease(
                module().getLevelEventProvider());

        LevelManager lm = levelManager();
        int postTransitionMinX = offsetWord(camera().getMinX(), TRANSITION_OFFSET_X);
        int postTransitionMaxX = offsetWord(camera().getMaxX(), TRANSITION_OFFSET_X);
        int postTransitionMinY = offsetWord(camera().getMinY(), TRANSITION_OFFSET_Y);
        int postTransitionMaxY = offsetWord(camera().getMaxY(), TRANSITION_OFFSET_Y);
        int postTransitionMaxYTarget = offsetWord(camera().getMaxYTarget(), TRANSITION_OFFSET_Y);
        SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
        SeamlessLevelTransitionRequest request =
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                        .targetZoneAct(Sonic3kZoneIds.ZONE_MGZ, 1)
                        .runtimeArtAdmissionPolicy(RuntimeArtAdmissionPolicy.TITLE_OWNER)
                        .deactivateLevelNow(false)
                        // Results screen already started act 2 music.
                        .preserveMusic(true)
                        // Obj_LevelResults and its ring/time globals remain live
                        // while MGZ1BGE_Transition reloads the act behind them.
                        .preserveLevelGamestate(true)
                        // The live results owner keeps Level_end_flag, while
                        // Load_Level clears the old End_of_level_flag before
                        // Obj_TitleCardWait2 publishes the new completion edge.
                        .preserveEndOfLevelActive(true)
                        // The carried Obj_LevelResults mutates into Obj_TitleCard
                        // and is the sole publisher after the reload.
                        .showInLevelTitleCard(false)
                        .resetLevelGamestateAtInLevelTitleCardDisplay(true)
                        // The carried results parent mutates into Obj_TitleCard;
                        // its six remaining child SST create/render entries precede
                        // Obj_TitleCardWait's display-time gamestate reset. At
                        // module phase 1, the title-card manager already owns
                        // the final six-entry create/render handoff.
                        .inLevelTitleCardResetAdditionalDispatches(6)
                        .inLevelTitleCardResetPhaseOneDispatchOverlap(6)
                        // The retained Obj_EndSignControl parent occupies an
                        // earlier SST slot than Obj_TitleCardWait2. Three parent
                        // dispatches remain after the visual children finish
                        // before the completion flag reaches DoStart.
                        .inLevelTitleCardExitAdditionalDispatches(3)
                        .inLevelTitleCardExitPhaseOneDispatchOverlap(5)
                        // The embedded results children have already retired
                        // by the time the retained Obj_LevelResults slot
                        // mutates into Obj_TitleCard.  Do not add the generic
                        // carried-results parent tail before that mutation;
                        // Obj_TitleCardInit runs on the following owner pass.
                        .carriedResultsRetireDispatches(1)
                        // Native code subtracts the offsets from the live camera
                        // and all four bounds; it does not recenter after Load_Level.
                        .preserveOffsetCameraPosition(true)
                        .postTransitionMinX(postTransitionMinX)
                        .postTransitionMaxX(postTransitionMaxX)
                        .postTransitionMinY(postTransitionMinY)
                        .postTransitionMaxY(postTransitionMaxY)
                        .postTransitionMaxYTarget(postTransitionMaxYTarget)
                        .playerOffset(TRANSITION_OFFSET_X, TRANSITION_OFFSET_Y)
                        .cameraOffset(TRANSITION_OFFSET_X, TRANSITION_OFFSET_Y)
                        .build();

        if (lm.getCurrentLevel() == null) {
            lm.requestSeamlessTransition(request);
        } else {
            try {
                // MGZ1BGE_Transition performs Load_Level and both coordinate
                // subtractions inside this background-event dispatch. Deferring
                // through the outer frame driver leaves one unshifted comparison
                // frame, unlike the native ScreenEvents path.
                lm.executeActTransition(request);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to apply MGZ act transition", e);
            }
        }

        LOG.info("MGZ1: requested seamless transition to Act 2 (offset X="
                + Integer.toHexString(TRANSITION_OFFSET_X) + " Y="
                + Integer.toHexString(TRANSITION_OFFSET_Y) + ")");
    }

    private static int offsetWord(short value, int offset) {
        return ((value & 0xFFFF) + offset) & 0xFFFF;
    }

    public boolean isTransitionRequested() {
        return transitionRequested;
    }

    // =========================================================================
    // Rewind accessors (C.4)
    // =========================================================================

    public int     getBgRoutine()                        { return bgRoutine; }
    public void    setBgRoutine(int v)                   { bgRoutine = v; }
    public boolean isEventsFg5Raw()                      { return eventsFg5; }
    public void    setEventsFg5Raw(boolean v)            { eventsFg5 = v; }
    public void    setTransitionRequestedRaw(boolean v)  { transitionRequested = v; }
    public void    setQuakeEventRoutine(int v)           { quakeEventRoutine = v; }
    public int     getChunkReplaceIndex()                { return chunkReplaceIndex; }
    public void    setChunkReplaceIndex(int v)           { chunkReplaceIndex = v; }
    public void    setChunkEventRoutine(int v)           { chunkEventRoutine = v; }
    public int     getChunkEventDelay()                  { return chunkEventDelay; }
    public void    setChunkEventDelay(int v)             { chunkEventDelay = v; }
    public void    setScreenEventRoutine(int v)          { screenEventRoutine = v; }
    public boolean isCollapseRequested()                 { return collapseRequested; }
    public void    setCollapseRequested(boolean v)       { collapseRequested = v; }
    public boolean isCollapseInitialized()               { return collapseInitialized; }
    public void    setCollapseInitialized(boolean v)     { collapseInitialized = v; }
    public void    setCollapseFinished(boolean v)        { collapseFinished = v; }
    public void    setCollapseMutationCount(int v)       { collapseMutationCount = v; }
    public int     getCollapseFrameCounter()             { return collapseFrameCounter; }
    public void    setCollapseFrameCounter(int v)        { collapseFrameCounter = v; }
    public int     getCollapseStartupShakeTimer()        { return collapseStartupShakeTimer; }
    public void    setCollapseStartupShakeTimer(int v)   { collapseStartupShakeTimer = v; }
    public int[]   getCollapseScrollVelocityCopy()       { return java.util.Arrays.copyOf(collapseScrollVelocity, COLLAPSE_COLUMN_COUNT); }
    public void    setCollapseScrollVelocity(int[] src)  { System.arraycopy(src, 0, collapseScrollVelocity, 0, COLLAPSE_COLUMN_COUNT); }
    public int[]   getCollapseScrollFixedPositionCopy()  { return java.util.Arrays.copyOf(collapseScrollFixedPosition, COLLAPSE_COLUMN_COUNT); }
    public void    setCollapseScrollFixedPosition(int[] src){ System.arraycopy(src, 0, collapseScrollFixedPosition, 0, COLLAPSE_COLUMN_COUNT); }
    public int[]   getCollapseScrollPositionCopy()       { return java.util.Arrays.copyOf(collapseScrollPosition, COLLAPSE_COLUMN_COUNT); }
    public void    setCollapseScrollPosition(int[] src)  { System.arraycopy(src, 0, collapseScrollPosition, 0, COLLAPSE_COLUMN_COUNT); }
    public int     getBossBgScrollVelocity()             { return bossBgScrollVelocity; }
    public void    setBossBgScrollVelocity(int v)        { bossBgScrollVelocity = v; }
    public int     getBossBgScrollOffset()               { return bossBgScrollOffset; }
    public void    setBossBgScrollOffset(int v)          { bossBgScrollOffset = v; }
    public boolean isBossTransitionActiveRaw()           { return bossTransitionActive; }
    public void    setBossTransitionActiveRaw(boolean v) { bossTransitionActive = v; }
    public void    setBossTransitionDeathPlaneDisabled(boolean v){ bossTransitionDeathPlaneDisabled = v; }
    public int     getBossTransitionTimer()              { return bossTransitionTimer; }
    public void    setBossTransitionTimer(int v)         { bossTransitionTimer = v; }
    public int     getBossTransitionX()                  { return bossTransitionX; }
    public void    setBossTransitionX(int v)             { bossTransitionX = v; }
    public int     getBossTransitionY()                  { return bossTransitionY; }
    public void    setBossTransitionY(int v)             { bossTransitionY = v; }
    public int     getBossTransitionCameraX()            { return bossTransitionCameraX; }
    public void    setBossTransitionCameraX(int v)       { bossTransitionCameraX = v; }
    public int     getBossTransitionCameraY()            { return bossTransitionCameraY; }
    public void    setBossTransitionCameraY(int v)       { bossTransitionCameraY = v; }
    public void    setBgRiseRoutine(int v)               { bgRiseRoutine = v; }
    public void    setBgRiseOffset(int v)                { bgRiseOffset = v; }
    public int     getBgRiseSubpixelAccum()              { return bgRiseSubpixelAccum; }
    public void    setBgRiseSubpixelAccum(int v)         { bgRiseSubpixelAccum = v; }
    public boolean isBgRiseMotionStarted()               { return bgRiseMotionStarted; }
    public void    setBgRiseMotionStarted(boolean v)     { bgRiseMotionStarted = v; }
    public boolean isBgRiseAccelLatched()                { return bgRiseAccelLatched; }
    public void    setBgRiseAccelLatched(boolean v)      { bgRiseAccelLatched = v; }
    public int     getBgRiseFinalShakeTimerRaw()         { return bgRiseFinalShakeTimer; }
    public void    setBgRiseFinalShakeTimer(int v)       { bgRiseFinalShakeTimer = v; }
    public int     getBgRiseRefreshFramesRemaining()     { return bgRiseRefreshFramesRemaining; }
    public void    setBgRiseRefreshFramesRemaining(int v){ bgRiseRefreshFramesRemaining = v; }
    public boolean isBgRiseLoadStateInitialised()        { return bgRiseLoadStateInitialised; }
    public void    setBgRiseLoadStateInitialised(boolean v){ bgRiseLoadStateInitialised = v; }
    public int     getBossArenaRoutine()                 { return bossArenaRoutine; }
    public void    setBossArenaRoutine(int v)            { bossArenaRoutine = v; }
    public boolean isBossSpawned()                       { return bossSpawned; }
    public void    setBossSpawned(boolean v)             { bossSpawned = v; }
    public void    setAppearance1Complete(boolean v)     { appearance1Complete = v; }
    public void    setAppearance2Complete(boolean v)     { appearance2Complete = v; }
    public void    setAppearance3Complete(boolean v)     { appearance3Complete = v; }
    public boolean isScreenShakeActiveRaw()              { return screenShakeActive; }
    public void    setScreenShakeActiveRaw(boolean v)    { screenShakeActive = v; }
    public boolean isPostFleeUnlockDone()                { return postFleeUnlockDone; }
    public void    setPostFleeUnlockDone(boolean v)      { postFleeUnlockDone = v; }
    public int     getGradualUnlockDirection()           { return gradualUnlockDirection; }
    public void    setGradualUnlockDirection(int v)      { gradualUnlockDirection = v; }
    public int     getGradualUnlockAccumulator()         { return gradualUnlockAccumulator; }
    public void    setGradualUnlockAccumulator(int v)    { gradualUnlockAccumulator = v; }
}
