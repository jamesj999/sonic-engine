package com.openggf.game.sonic3k.events;

import com.openggf.camera.Camera;
import com.openggf.data.Rom;
import com.openggf.game.CheckpointState;
import com.openggf.game.PlayerCharacter;
import com.openggf.game.RespawnState;
import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.save.SaveReason;
import com.openggf.game.save.SessionSaveRequests;
import com.openggf.game.sonic3k.S3kPaletteOwners;
import com.openggf.game.sonic3k.S3kPaletteWriteSupport;
import com.openggf.game.sonic3k.Sonic3kLevelEventManager;
import com.openggf.game.sonic3k.Sonic3kLoadBootstrap;
import com.openggf.game.sonic3k.Sonic3kLevel;
import com.openggf.game.sonic3k.Sonic3kObjectArtProvider;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.game.sonic3k.constants.Sonic3kConstants;
import com.openggf.game.sonic3k.constants.Sonic3kObjectIds;
import com.openggf.game.sonic3k.constants.Sonic3kZoneIds;
import com.openggf.game.sonic3k.resources.S3kKosModuleQueue;
import com.openggf.game.sonic3k.resources.S3kKosDecompressionQueue;
import com.openggf.game.sonic3k.resources.S3kKosRamDestinations;
import com.openggf.game.timing.HardwareWorkHandle;
import com.openggf.game.timing.HardwareWorkKind;
import com.openggf.game.sonic3k.objects.AizBattleshipInstance;
import com.openggf.game.sonic3k.objects.AizBgTreeSpawnerInstance;
import com.openggf.game.sonic3k.objects.AizBombExplosionInstance;
import com.openggf.game.sonic3k.objects.AizShipBombInstance;
import com.openggf.game.sonic3k.objects.AizBossSmallInstance;
import com.openggf.game.sonic3k.objects.AizEndBossInstance;
import com.openggf.game.sonic3k.objects.AizHollowTreeObjectInstance;
import com.openggf.game.sonic3k.objects.AizIntroTerrainSwap;
import com.openggf.game.sonic3k.objects.AizMinibossInstance;
import com.openggf.game.sonic3k.objects.AizPlaneIntroInstance;
import com.openggf.level.LevelConstants;
import com.openggf.level.Level;
import com.openggf.level.LevelManager;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.SeamlessLevelTransitionRequest;
import com.openggf.game.RuntimeArtAdmissionPolicy;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.resources.LoadOp;
import com.openggf.level.resources.ResourceLoader;
import com.openggf.level.WaterSystem;
import com.openggf.sprites.managers.SpriteManager;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.SidekickCpuController;

import java.io.IOException;
import java.util.List;
import java.util.function.IntSupplier;
import java.util.logging.Logger;

/**
 * Angel Island Zone dynamic level events.
 * ROM: AIZ1_Resize / AIZ2_Resize (s3.asm)
 *
 * <p>Act 1 state machine (Dynamic_resize_routine):
 * <ul>
 *   <li>Routine 0: At camera X >= $1308 → load main AIZ palette (PalPointers #$2A)</li>
 *   <li>Routine 2: At camera X >= $1400 → queue terrain swap overlays</li>
 *   <li>Routine 4: Unlock Y boundaries, apply dynamic max Y from table</li>
 * </ul>
 *
 * Act 2: boss arena and scroll/deformation state are handled by the act's own tables.
 */
public class Sonic3kAIZEvents extends Sonic3kZoneEvents {
    private static final Logger LOG = Logger.getLogger(Sonic3kAIZEvents.class.getName());

    // --- ROM constants (s3.asm AIZ1_Resize) ---

    /** Camera X threshold to begin tracking minX (routine 0). */
    private static final int MIN_X_TRACK_START = 0x1000;

    /** Camera X threshold for palette swap and minX lock (routine 0 → 1). */
    private static final int PALETTE_SWAP_X = 0x1308;

    /** PalPointers index for Pal_AIZ (main AIZ palette, 3 lines → palette 1-3). */
    private static final int PAL_AIZ_INDEX = 0x2A;

    /** PalPointers index for Pal_AIZFire (fire palette, 3 lines → palette 1-3). */
    private static final int PAL_POINTER_AIZ_FIRE_INDEX = 0x0B;

    /** PalPointers index for Pal_AIZBoss (boss-area palette, 3 lines → palette 1-3). */
    private static final int PAL_AIZ_BOSS_INDEX = 0x30;

    /** Camera X threshold for terrain swap (routine 2). Already handled by AizPlaneIntroInstance. */
    private static final int TERRAIN_SWAP_X = 0x1400;

    // --- ROM: loc_1A9EC palette[2][15] per-frame mutation (s3.asm:32171-32195) ---
    // Cascading overwrite: $020E → $0004 at $2B00 → $0C02 at $2D80.
    private static final int PALETTE_MUT_THRESHOLD_DARK = 0x2B00;
    private static final int PALETTE_MUT_THRESHOLD_FIRE = 0x2D80;
    private static final int RAISED_MIN_Y_THRESHOLD = 0x2C00;
    private static final int RAISED_MIN_Y = 0x02E0;
    private static final int FIRE_MIN_X_LOCK = 0x2D80;
    private static final int PALETTE_MUT_COLOR_RED = 0x020E;
    private static final int PALETTE_MUT_COLOR_DARK = 0x0004;
    private static final int PALETTE_MUT_COLOR_FIRE = 0x0C02;
    // AIZ1_ScreenEvent hollow-tree reveal thresholds/chunk columns.
    private static final int TREE_REVEAL_CLEAR_CAMERA_X = 0x2D30;
    private static final int TREE_REVEAL_CLEAR_COUNTER = 0x39;
    private static final int TREE_REVEAL_STEP2_COUNTER = 0x34;
    private static final int TREE_REVEAL_STEP3_COUNTER = 0x24;
    private static final int TREE_REVEAL_STEP4_COUNTER = 0x14;
    private static final int TREE_REVEAL_COL_A = 0x59;
    private static final int TREE_REVEAL_COL_B = 0x5A;
    private static final int TREE_REVEAL_SRC_COL_A = 0x00;
    private static final int TREE_REVEAL_SRC_COL_B = 0x01;
    private static final int[] TREE_REVEAL_COLUMNS = {TREE_REVEAL_COL_A, TREE_REVEAL_COL_B};
    private static final int[] TREE_REVEAL_SOURCE_COLUMNS = {TREE_REVEAL_SRC_COL_A, TREE_REVEAL_SRC_COL_B};
    private static final int TREE_REVEAL_SOURCE_X = 0x0000; // ROM call uses d1=0 for source row reads.
    private static final int TREE_REVEAL_DEST_X = 0x2C80;
    private static final int TREE_REVEAL_SOURCE_Y_OFFSET = 0x280;
    private static final int TREE_REVEAL_INITIAL_DEST_Y = 0x470;
    private static final int TREE_REVEAL_MASK_STRIDE = 0x10;
    private static final int TREE_REVEAL_MASK_ROW_ADVANCE = 0x20;
    private static final int TREE_REVEAL_MAX_ROW_WINDOW = 2;
    private static final int TREE_REVEAL_BLOCK_SIZE = 0x80;
    private static final int TILE_SIZE = 8;
    private static final int TREE_REVEAL_ROW_CHUNKS = 16;
    // {sourceRow, targetRow} from AIZ1SE_ChangeChunk4/3/2/1 pointer math.
    private static final int[][] TREE_REVEAL_ROW_COPIES = {
            {3, 8},
            {2, 7},
            {1, 6},
            {0, 5},
    };
    // ROM: AIZ_TreeRevealArray (s3.asm / sonic3k.asm)
    private static final byte[] TREE_REVEAL_MASKS = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0,
            0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0,
            0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0,
            0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    };

    /**
     * Dynamic max Y resize table (word_1AA84 in s3.asm).
     * Format: {maxY, triggerX}. Bit 15 in ROM ($8xxx) = set immediately; all entries have it.
     * Scanned until cameraX <= triggerX. Last entry uses 0xFFFF as catch-all.
     */
    private static final int[][] AIZ1_RESIZE_TABLE = {
            {0x0390, 0x1650},
            {0x03B0, 0x1B00},
            {0x0430, 0x2000},
            {0x04C0, 0x2B00},
            {0x03B0, 0x2D80},
            {0x02E0, 0xFFFF},
    };

    // --- AIZ2 resize state machine (sonic3k.asm AIZ2_Resize) ---
    // Sonic path thresholds
    private static final int AIZ2_SONIC_RESIZE1_TRIGGER_X = 0x02E0;
    private static final int AIZ2_SONIC_RESIZE2_BOSS_MAX_Y = 0x02B8;
    private static final int AIZ2_SONIC_RESIZE2_BOSS_TRIGGER_X = 0x0ED0;
    private static final int AIZ2_SONIC_RESIZE2_LOCK_X = 0x0F50;
    private static final int AIZ2_SONIC_RESIZE3_TRIGGER_X = 0x1500;
    private static final int AIZ2_SONIC_RESIZE3_MAX_Y = 0x0630;
    private static final int AIZ2_SONIC_RESIZE4_TRIGGER_X = 0x3C00;
    private static final int AIZ2_SONIC_RESIZE5_TRIGGER_X = 0x3F00;
    private static final int AIZ2_SONIC_RESIZE5_MIN_Y = 0x015A;
    private static final int AIZ2_SONIC_RESIZE6_TRIGGER_X = 0x4000;
    private static final int AIZ2_SONIC_RESIZE7_TRIGGER_X = 0x4160;
    private static final int AIZ2_SONIC_BOSS_X = 0x11F0;
    private static final int AIZ2_SONIC_BOSS_Y = 0x0289;
    // Knuckles path thresholds
    private static final int AIZ2_KNUX_RESIZE1_TRIGGER_X = 0x02E0;
    private static final int AIZ2_KNUX_RESIZE2_BOSS_MAX_Y = 0x0450;
    private static final int AIZ2_KNUX_RESIZE2_BOSS_TRIGGER_X = 0x0E80;
    private static final int AIZ2_KNUX_RESIZE2_LOCK_X = 0x1040;
    private static final int AIZ2_KNUX_RESIZE3_TRIGGER_X = 0x11A0;
    private static final int AIZ2_KNUX_RESIZE3_TARGET_MAX_Y = 0x0820;
    private static final int AIZ2_KNUX_RESIZE4_TRIGGER_X = 0x3B80;
    private static final int AIZ2_KNUX_RESIZE4_TARGET_MAX_Y = 0x05DA;
    private static final int AIZ2_KNUX_RESIZE5_TRIGGER_X = 0x3F80;
    private static final int AIZ2_KNUX_BOSS_X = 0x11D0;
    private static final int AIZ2_KNUX_BOSS_Y = 0x0420;
    private static final int AIZ2_KNUX_WATER_LEVEL = 0x0F80;
    // Shared
    private static final int AIZ2_DEFAULT_MAX_Y = 0x0590;

    // --- Battleship bombing sequence constants (sonic3k.asm AIZ2_ScreenEvent) ---
    /** Auto-scroll speed during the bombing loop: 4 pixels/frame. */
    private static final int BATTLESHIP_SCROLL_SPEED = 4;
    /** Wrap boundary during bombing: camera X wraps back at $4440. ROM: Events_bg+$02 initial. */
    private static final int BATTLESHIP_WRAP_X_BOMBING = 0x4440;
    /**
     * Wrap boundary after bombing. The ROM sets Events_bg+$02=$46C0 and subtracts
     * $200, with HInt split rendering hiding the seam.
     */
    private static final int BATTLESHIP_WRAP_X_POST_BOMBING = 0x46C0;
    /** Forest mask becomes visible once the bombship redraw reaches this camera X. */
    private static final int BATTLESHIP_FOREST_FRONT_START_X = 0x4380;
    /** ROM: Sonic's AIZ end-boss arena camera lock. Forest-front override ends here. */
    private static final int AIZ_END_BOSS_LOCK_X = 0x4880;
    private static final int AIZ_END_BOSS_SONIC_LAYOUT_X = 0x48A0;
    private static final int AIZ_END_BOSS_SONIC_LAYOUT_Y = 0x01C0;
    private static final int AIZ_END_BOSS_KNUX_LOCK_X = 0x4100;
    private static final int AIZ_END_BOSS_KNUX_LAYOUT_X = 0x4120;
    private static final int AIZ_END_BOSS_KNUX_LAYOUT_Y = 0x0640;
    private static final int BATTLESHIP_WRAP_DIST_POST_BOMBING = 0x200;
    /** Wrap distance: ROM subtracts $200 from all positions on ship-loop wrap. */
    private static final int BATTLESHIP_WRAP_DIST = 0x200;
    /** Left clamp: player X must be >= camera X + $18 during auto-scroll. */
    private static final int PLAYER_LEFT_MARGIN = 0x18;
    /** Right clamp: player X must be <= camera X + $A0 during auto-scroll. */
    private static final int PLAYER_RIGHT_MARGIN = 0xA0;
    /** Camera max X to set when the small boss exits (end of sequence). */
    private static final int BATTLESHIP_END_CAMERA_MAX_X = 0x6000;
    /** Camera X at which parallax trees delete themselves. ROM: cmpi.w #$4880,(Camera_X_pos).w. */
    private static final int BATTLESHIP_TREE_DELETE_CAMERA_X = 0x4880;

    @RewindTransient(reason = "level-load bootstrap configuration is structural; mutable transition state is captured separately")
    private final Sonic3kLoadBootstrap bootstrap;
    @RewindTransient(reason = "read-only timing source; production resolves the rewind-captured ObjectManager VBlank counter")
    private final IntSupplier vblankCounterSource;
    private boolean introSpawned;
    /** One-shot guard: once AIZ intro minX is locked at $1308, stop rewriting minX each frame. */
    private boolean introMinXLocked;
    /** True once the AIZ intro has successfully released dormant CPU Tails. */
    private boolean introSidekickMarkerReleased;
    /** True while the intro->main-level refresh is holding raw Events_fg_5 high. */
    private boolean introNormalRefreshPending;
    private boolean paletteSwapped;
    private boolean boundariesUnlocked;
    private boolean fireMinXLockReached;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinal")
    private S3kKosDecompressionQueue mainLevelBlockKosQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle mainLevelBlockHandle;
    private long mainLevelBlockOrdinal = -1;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinal")
    private S3kKosModuleQueue mainLevelArtKosQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle mainLevelArtHandle;
    private long mainLevelArtOrdinal = -1;
    // Tracks one-shot application of AIZ1SE_ChangeChunk4/3/2/1.
    private int appliedTreeRevealChunkCopiesMask;

    // --- AIZ2 Dynamic_resize_routine state ---
    /** ROM: Dynamic_resize_routine equivalent for act 2. */
    private int aiz2ResizeRoutine;

    // --- Boss / fire transition state ---
    /** One-shot guard for AIZ2 resize boss spawn. */
    private boolean minibossSpawned;
    /** ROM: (Events_fg_4).w - set by AIZ2 resize stage $0E to start the bombing ScreenEvent. */
    private boolean eventsFg4;
    /** ROM: (Events_fg_5).w - set by boss exit sequence to trigger fire transition. */
    private boolean eventsFg5;
    /** Boss_flag equivalent - set when boss is present, cleared on cleanup. */
    private boolean bossFlag;
    // --- Battleship bombing sequence state ---
    /** True while the battleship auto-scroll loop is active. */
    private boolean battleshipAutoScrollActive;
    /** True once AIZ2_DoShipLoop has run in this frame's pre-physics phase. */
    private boolean battleshipAutoScrollRanPrePhysics;
    /** True when this event temporarily froze camera following for Scroll_lock. */
    private boolean battleshipCameraFrozenForScrollLock;
    /** Camera frozen state before applying the temporary Scroll_lock freeze. */
    private boolean battleshipCameraWasFrozen;
    /** True once the battleship object has been spawned (one-shot guard). */
    private boolean battleshipSpawned;
    /** Remaining ShipRefresh plane passes before AllocateObject creates the ship. */
    private int battleshipSpawnRefreshPasses;
    /** True once the AIZ2 end boss has been handed off to the object system. */
    private boolean endBossSpawned;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinals")
    private S3kKosModuleQueue battleshipKosQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle battleshipTerrainKosHandle;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle battleshipTerrainArtHandle;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle battleshipObjectArtHandle;
    private long battleshipTerrainKosOrdinal = -1;
    private long battleshipTerrainArtOrdinal = -1;
    private long battleshipObjectArtOrdinal = -1;
    /** True once the AIZ2 bombership 8x8/16x16 terrain overlays have been applied. */
    private boolean battleshipTerrainLoaded;
    /** Current wrap boundary for auto-scroll (changes after bombing completes). */
    private int battleshipWrapX;
    /**
     * ROM: Screen_shake_flag — timed screen shake countdown. When positive,
     * decrements each frame and applies Y offset from ScreenShakeArray.
     * Set to $10 (16) on bomb impact.
     */
    private int screenShakeTimer;
    /**
     * ROM: Level_repeat_offset — set to $200 during a wrap frame, 0 otherwise.
     * Active objects subtract this from their X each frame to stay in sync.
     */
    private int levelRepeatOffset;
    /**
     * ROM: AIZ2BGE_Normal BG camera Y adjustment applied when eventsFg5 triggers.
     * Value is $A8 if Camera_Y_pos &lt; $400, or -$198 otherwise.
     * Added to the BG vertical scroll factor by the scroll handler.
     */
    private int battleshipBgYOffset;
    /**
     * Cumulative scroll distance during the battleship sequence (never wraps).
     * Used by the parallax scroll handler to compute smooth BG deformation
     * even when the camera X wraps back by $200.
     * ROM equivalent: Events_fg_1 — accumulated via Adjust_BGDuringLoop every frame.
     */
    private int battleshipSmoothScrollX;
    /**
     * Camera X snapshot for post-auto-scroll smooth tracking.
     * ROM: Adjust_BGDuringLoop uses Events_fg_0 to track previous Camera_X_pos_copy
     * and accumulate deltas into Events_fg_1 every frame — even after the auto-scroll
     * loop stops. This field mirrors Events_fg_0 for the post-scroll phase so that
     * battleshipSmoothScrollX continues to increment as the camera follows Sonic,
     * allowing parallax trees to scroll off-screen naturally.
     * Set to -1 when not active.
     */
    private int battleshipPostScrollCameraX;
    /** Current vertical shake offset produced by {@link #screenShakeTimer}. */
    private int screenShakeOffsetY;
    /** Offset consumed by the current ScreenEvents pass before the next value is prepared. */
    private int screenShakeAppliedOffsetY;
    /** True after the act switch request has been sent to LevelManager. */
    private boolean act2TransitionRequested;
    /** True after in-place mutation stage has been requested. */
    private boolean fireTransitionMutationRequested;
    /** True once the queued AIZ2 block/chunk Kosinski streams are visible. */
    private boolean fireTerrainTablesLoaded;
    /** True once the post-burn fine haze phase should be active on FG. */
    private boolean postFireHazeActive;
    /** One-shot guard for AIZ1 fire-overlay 8x8 art staging at x >= $2E00. */
    private boolean fireOverlayTilesLoaded;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinal")
    private S3kKosModuleQueue fireOverlayKosQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle fireOverlayKosHandle;
    private long fireOverlayKosOrdinal = -1;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinals")
    private S3kKosDecompressionQueue act2TerrainKosQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle act2BlockHandle;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle act2PrimaryChunkHandle;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle act2SecondaryChunkHandle;
    private long act2BlockOrdinal = -1;
    private long act2PrimaryChunkOrdinal = -1;
    private long act2SecondaryChunkOrdinal = -1;
    @RewindTransient(reason = "queue facade is rebound to the restored session ledger by captured ordinals")
    private S3kKosModuleQueue act2ArtKosQueue;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle act2PrimaryArtHandle;
    @RewindTransient(reason = "handle is rebound to the restored session ledger by captured ordinal")
    private HardwareWorkHandle act2SecondaryArtHandle;
    private long act2PrimaryArtOrdinal = -1;
    private long act2SecondaryArtOrdinal = -1;
    /** Fixed-point Camera_Y_pos_BG_copy used by AIZ1_FireRise (16.16). */
    private int fireBgCopyFixed;
    /** Events_bg+$02 equivalent: rising-fire speed. */
    private int fireRiseSpeed;
    /** _unkEE8E equivalent used by AIZTrans_WavyFlame. */
    private int fireWavePhase;
    /** Total fake-out fire frame counter across act 1 and the resumed act 2 continuation. */
    private int fireTransitionFrames;
    /** Per-phase frame counter used for the redraw phases. */
    private int firePhaseFrames;
    /** Countdown copied from AIZMinibossCutscene_Escape's object timer. */
    private int fireMusicRestoreTimer;
    /** True once AIZ2 WaitFire has snapped to the dedicated $200 source strip. */
    private boolean act2WaitFireDrawActive;
    /** Current fake-out fire phase derived from the AIZ1/AIZ2 background event routines. */
    private FireSequencePhase fireSequencePhase = FireSequencePhase.INACTIVE;

    /** BG Y coordinate where fire tiles begin in the AIZ BG layout. */
    private static final int FIRE_TILE_START_Y = 0x0100;
    private static final int FIRE_BG_FIXED_START = 0x0020_0000;
    private static final int FIRE_BG_TARGET = 0x0068_0000;
    private static final int FIRE_BG_LERP_SHIFT = 5;
    private static final int FIRE_BG_LERP_MIN_DELTA = 0x1400;
    private static final int FIRE_RISE_ACCEL = 0x0280;
    private static final int FIRE_RISE_MAX_SPEED = 0xA000;
    private static final int FIRE_BG_FINISH_Y = 0x0310;
    private static final int AIZ2_POST_FIRE_CAMERA_MAX_X = 0x6000;
    /** Height of the fire tile zone in the BG layout (0x310 - 0x100 = 0x210). */
    private static final int FIRE_TILE_HEIGHT = FIRE_BG_FINISH_Y - FIRE_TILE_START_Y;
    // ROM parity: AIZ1BGE_FireTransition switches to the fire-stage overlays at
    // Camera_Y_pos_BG_copy >= $190 before entering refresh/finish routines.
    private static final int FIRE_BG_MUTATION_Y = 0x0190;
    private static final int FIRE_BG_X_BASE = 0x1000;
    private static final int FIRE_SOURCE_X_AIZ1 = 0x1000;
    private static final int FIRE_SOURCE_X_AIZ2 = 0x0200;
    private static final int FIRE_BG_X_PHASE_MASK = 0x0060;
    private static final int FIRE_WAVE_PHASE_STEP = 6;
    private static final int FIRE_TRANSITION_FALLBACK_FRAMES = 240;
    /**
     * ROM: AIZMinibossCutscene_StartEscape writes #$120 to $2E(a0) for AIZ1
     * (sonic3k.asm:136869-136885). AIZMinibossCutscene_Escape restores the
     * level music after its per-frame decrement makes this timer negative.
     */
    private static final int FIRE_MUSIC_RESTORE_TIME = 0x120;
    // ROM AIZ1BGE_FireTransition, the Camera_Y_pos_BG_copy >= $190 branch
    // (sonic3k.asm:104674-104716): loc_4FD10 seeds
    // `move.w #$F,(Draw_delayed_rowcount).w`, bumps Events_routine_bg to
    // AIZ1BGE_FireRefresh, and then falls through `bra.s loc_4FD32` so the FIRST
    // Draw_PlaneVertBottomUp call happens on that same frame. Each call drains TWO
    // rows (Draw_PlaneVertSingleBottomUp runs once, then again while the counter is
    // still non-negative; sonic3k.asm:103429-103457), and the routine advances to
    // AIZ1BGE_Finish on the call that takes the counter negative. The seed and the
    // drain rate give the pass count outright; it is not a measured budget. Same
    // shape, and the same two ROM facts, as AIZ2_FIRE_REDRAW_ROWCOUNT below.
    /** ROM: {@code move.w #$F,(Draw_delayed_rowcount).w} (sonic3k.asm:104711). */
    private static final int AIZ1_FIRE_REFRESH_ROWCOUNT = 0x0F;
    /** Draw_PlaneVertBottomUp drains two rows per call (sonic3k.asm:103429-103457). */
    private static final int FIRE_REDRAW_ROWS_PER_CALL = 2;
    /**
     * Passes spent in AIZ1_FIRE_REFRESH after the transition frame, which already
     * consumed the first drain call.
     */
    private static final int FIRE_REDRAW_FRAMES =
            ((AIZ1_FIRE_REFRESH_ROWCOUNT + 1) / FIRE_REDRAW_ROWS_PER_CALL) - 1;
    /**
     * KNOWN INVENTED CONSTANT -- not ROM-derived, and deliberately left in place.
     *
     * <p>The ROM has no such duration. AIZ2's chunk and block tables go live when
     * the three plain {@code Queue_Kos} entries queued at
     * sonic3k.asm:104678-104688 drain inside {@code Process_Kos_Queue}, which
     * decompresses straight over {@code RAM_start} / {@code Block_table}; there is
     * no separate apply step, and no apply <em>instant</em> either.
     *
     * <p>The drain rate is NOT the defect, and the earlier note here saying so was
     * wrong on both counts. Under trace replay {@code KOS_DECOMPRESSION_QUEUE} is
     * {@code RECORDED} (HardwareTimingSchedule), so these handles' readiness is the
     * ROM's own measured drain, matched by ordinal and submission fingerprint. And
     * {@code Process_Kos_Queue} (sonic3k.asm:2833-2860) carries no work budget at
     * all -- it runs the whole archive in one unbounded loop, stopped only by
     * whichever V-int lands inside it and resumed by {@code Set_Kos_Bookmark} -- so
     * no frame-granularity drain model can exist. Re-gating on {@code isReady()}
     * releases the apply *later*, not earlier: recorded completions sit at
     * queue+41/+45/+49 in {@code traces/s3k/aiz_completerun} (rows 6257/6261/6265,
     * queue row 6216) and queue+39/+43/+47 in {@code aiz1_to_hcz_fullrun}
     * (5453/5457/5461, queue 5414).
     *
     * <p>The real defect is atomicity. The decompressor writes in place over live
     * tables, so for those 39-49 frames the terrain is partly AIZ1 and partly AIZ2
     * in the archive's output order. In {@code aiz_completerun} the ROM's new wall
     * stops Tails on row 6255 -- queue+39, two frames before the first archive even
     * retires. This constant lands the atomic swap on queue+39 and therefore
     * matches; the handle re-gating lands it on queue+49 and produces the 4030
     * errors at frame 6255 {@code tails_x_speed} recorded by two rounds. Neither is
     * right for an arbitrary movie: which tile flips on which frame depends on that
     * tile's offset in the stream. Closing this needs per-frame decompression
     * progress in the v5 timing stream plus a recapture, not a different number.
     * See the 2026-08-17 atomicity entry in docs/status/trace-frontier-log.md.
     * Comparison-only evidence; never read it into engine state.
     */
    private static final int FIRE_TERRAIN_DECOMPRESS_FRAMES = 20;
    // ROM: after the AIZ1BGE_Finish reload (Events_routine_bg cleared, act 0->1),
    // the AIZ2 background event chain re-draws the fire plane before releasing the
    // post-reload Camera_max_X_pos lock. Two routines run in sequence:
    //   - Events_routine_bg $00 = AIZ2BGE_FireRedraw (sonic3k.asm:105036-105050)
    //   - Events_routine_bg $04 = AIZ2BGE_WaitFire   (sonic3k.asm:105052-105105)
    // AIZ1BGE_Finish seeds Draw_delayed_rowcount = $F immediately before clearing
    // Events_routine_bg (sonic3k.asm:104774-104775). Each AIZ2BGE_FireRedraw pass
    // calls Draw_PlaneVertBottomUp, which drains TWO rows per call
    // (Draw_PlaneVertSingleBottomUp runs once, then again while the counter is
    // still non-negative; sonic3k.asm:103429-103457) and advances the routine when
    // that counter goes negative. The pass count therefore falls out of the seed
    // and the drain rate; it is not a measured budget.
    /** ROM: {@code move.w #$F,(Draw_delayed_rowcount).w} (sonic3k.asm:104774). */
    private static final int AIZ2_FIRE_REDRAW_ROWCOUNT = 0x0F;
    // ROM AIZ2BGE_WaitFire (sonic3k.asm:105052-105084): while Events_bg+$00 is
    // clear, the routine waits for the continuous AIZ1_FireRise ramp to put
    // (Camera_Y_pos_BG_copy & $7F) inside [$20,$30); on that pass it re-seats the
    // BG copy to $180 + that residue and latches Events_bg+$00. From then on the
    // ramp runs on until `cmpi.w #$310,(Camera_Y_pos_BG_copy)` stops branching,
    // which is what releases Camera_max_X_pos. Both bounds are ROM immediates.
    private static final int FIRE_BG_WAIT_WINDOW_LOW = 0x20;
    private static final int FIRE_BG_WAIT_WINDOW_HIGH = 0x30;
    private static final int FIRE_BG_WAIT_RESEAT_BASE = 0x180;
    private static final int FIRE_BG_WAIT_RESIDUE_MASK = 0x7F;
    private static final int FIRE_OVERLAY_STAGE_X = 0x2E00;
    // SpawnLevelMainSprites writes Obj_AIZPlaneIntro to
    // Dynamic_object_RAM+(object_size*2): absolute S3K SST slot 3+2 = 5.
    private static final int AIZ_PLANE_INTRO_SST_SLOT = 5;
    private static final int FIRE_OVERLAY_TILE_DEST = 0x500;
    private static final int FIRE_OVERLAY_PLC = 0x0C;
    public static final int FIRE_WAVE_COLUMN_COUNT = 0x14;
    private static final byte[] FIRE_COLUMN_WAVE = {
            0, -1, -2, -5, -8, -10, -13, -14,
            -15, -14, -13, -10, -7, -5, -2, -1
    };
    // ROM: AIZ1_AIZ2_Transition writes these 6 words to Normal_palette_line_4+$2.
    private static final int[] FIRE_TRANSITION_LINE4_WORDS = {
            0x004E, 0x006E, 0x00AE, 0x00CE, 0x02EE, 0x0AEE
    };
    // ROM: AIZ2BGE_WaitFire rewrites line 4 once the fire finally clears.
    // move.l #$8EE00AA,(a1)+ / move.l #$8E004E,(a1)+ / move.l #$2E000C,(a1)
    private static final int[] POST_FIRE_LINE4_WORDS = {
            0x08EE, 0x00AA, 0x008E, 0x004E, 0x002E, 0x000C
    };
    private static volatile PendingFireSequence pendingFireSequence;

    /**
     * Resets all static/global state held by this class.
     * Called from {@link Sonic3kLevelEventManager#resetState()} to prevent
     * fire wall handoff data from leaking across level loads and test iterations.
     */
    public static void resetGlobalState() {
        pendingFireSequence = null;
    }

    private int fireOverlayTileCount;

    private enum FireSequencePhase {
        INACTIVE,
        AIZ1_FIRE_TRANSITION,
        AIZ1_FIRE_REFRESH,
        AIZ1_FINISH,
        AIZ2_FIRE_REDRAW,
        AIZ2_WAIT_FIRE,
        AIZ2_BG_REDRAW,
        COMPLETE;

        boolean curtainActive() {
            return switch (this) {
                case AIZ1_FIRE_TRANSITION, AIZ1_FIRE_REFRESH, AIZ1_FINISH,
                     AIZ2_FIRE_REDRAW, AIZ2_WAIT_FIRE, AIZ2_BG_REDRAW -> true;
                default -> false;
            };
        }

        /**
         * True while the ROM is still drawing the fire plane as part of the
         * continuation.  AIZ2BGE_FireRedraw and the pre-latch branch of
         * AIZ2BGE_WaitFire continue the AIZ1 fire rise and draw rows
         * (sonic3k.asm:105036-105078).  The VDP plane wraps during that
         * interval, including when exact art-loading timing leaves the carried
         * fire position beyond the original $310 fire-zone boundary.  After
         * Events_bg+$00 is latched, WaitFire draws the real rows while the rise
         * approaches $310 (sonic3k.asm:105079-105105), so wrapping must stop
         * and the trailing fire band can scroll off naturally.  The subsequent
         * AIZ2_BG_REDRAW phase remains unwrapped because the ROM no longer
         * calls the fire-rise or fire-draw routines (sonic3k.asm:105128-105138).
         */
        boolean wrapFireTiles(boolean waitFireDrawActive) {
            return switch (this) {
                case AIZ1_FIRE_TRANSITION, AIZ1_FIRE_REFRESH, AIZ1_FINISH,
                     AIZ2_FIRE_REDRAW -> true;
                case AIZ2_WAIT_FIRE -> !waitFireDrawActive;
                default -> false;
            };
        }

        boolean usesFireScrollMode() {
            return switch (this) {
                case AIZ1_FIRE_TRANSITION, AIZ1_FIRE_REFRESH, AIZ1_FINISH, AIZ2_FIRE_REDRAW, AIZ2_WAIT_FIRE -> true;
                default -> false;
            };
        }
    }

    private record PendingFireSequence(
            FireSequencePhase phase,
            int fireBgCopyFixed,
            int fireRiseSpeed,
            int fireWavePhase,
            int fireTransitionFrames,
            int firePhaseFrames,
            int fireMusicRestoreTimer,
            boolean mutationRequested,
            boolean act2WaitFireDrawActive) {
    }

    public Sonic3kAIZEvents(Sonic3kLoadBootstrap bootstrap) {
        this(bootstrap, null);
    }

    public Sonic3kAIZEvents(Sonic3kLoadBootstrap bootstrap, IntSupplier vblankCounterSource) {
        this.bootstrap = bootstrap;
        this.vblankCounterSource = vblankCounterSource;
    }

    @Override
    public void init(int act) {
        super.init(act);
        introSpawned = false;
        introMinXLocked = false;
        introSidekickMarkerReleased = false;
        introNormalRefreshPending = false;
        paletteSwapped = false;
        boundariesUnlocked = false;
        fireMinXLockReached = false;
        mainLevelBlockKosQueue = null;
        mainLevelBlockHandle = null;
        mainLevelBlockOrdinal = -1;
        mainLevelArtKosQueue = null;
        mainLevelArtHandle = null;
        mainLevelArtOrdinal = -1;
        appliedTreeRevealChunkCopiesMask = 0;
        minibossSpawned = false;
        aiz2ResizeRoutine = 0;
        eventsFg4 = false;
        eventsFg5 = false;
        bossFlag = false;
        battleshipAutoScrollActive = false;
        battleshipAutoScrollRanPrePhysics = false;
        battleshipCameraFrozenForScrollLock = false;
        battleshipCameraWasFrozen = false;
        battleshipSpawned = false;
        battleshipSpawnRefreshPasses = 0;
        endBossSpawned = false;
        battleshipKosQueue = null;
        battleshipTerrainKosHandle = null;
        battleshipTerrainArtHandle = null;
        battleshipObjectArtHandle = null;
        battleshipTerrainKosOrdinal = -1;
        battleshipTerrainArtOrdinal = -1;
        battleshipObjectArtOrdinal = -1;
        battleshipTerrainLoaded = false;
        battleshipWrapX = BATTLESHIP_WRAP_X_BOMBING;
        levelRepeatOffset = 0;
        battleshipBgYOffset = 0;
        battleshipSmoothScrollX = 0;
        battleshipPostScrollCameraX = -1;
        screenShakeTimer = 0;
        screenShakeOffsetY = 0;
        screenShakeAppliedOffsetY = 0;
        act2TransitionRequested = false;
        fireTransitionMutationRequested = false;
        fireTerrainTablesLoaded = false;
        postFireHazeActive = false;
        fireOverlayTilesLoaded = false;
        fireOverlayKosQueue = null;
        fireOverlayKosHandle = null;
        fireOverlayKosOrdinal = -1;
        act2TerrainKosQueue = null;
        act2BlockHandle = null;
        act2PrimaryChunkHandle = null;
        act2SecondaryChunkHandle = null;
        act2BlockOrdinal = -1;
        act2PrimaryChunkOrdinal = -1;
        act2SecondaryChunkOrdinal = -1;
        act2ArtKosQueue = null;
        act2PrimaryArtHandle = null;
        act2SecondaryArtHandle = null;
        act2PrimaryArtOrdinal = -1;
        act2SecondaryArtOrdinal = -1;
        fireBgCopyFixed = FIRE_BG_FIXED_START;
        fireRiseSpeed = 0;
        fireWavePhase = 0;
        fireTransitionFrames = 0;
        firePhaseFrames = 0;
        fireMusicRestoreTimer = -1;
        act2WaitFireDrawActive = false;
        fireSequencePhase = FireSequencePhase.INACTIVE;
        fireOverlayTileCount = 0;
        if (act == 0) {
            pendingFireSequence = null;
        } else {
            setTransitionControlLock(false);
        }
        restorePendingFireSequenceIfPresent(act);
        if (act == 0) {
            AizPlaneIntroInstance.resetIntroPhaseState();
            AizHollowTreeObjectInstance.resetTreeRevealCounter();
        }
        if (shouldSpawnIntro(act)) {
            // ROM: SpawnLevelMainSprites clears Level_started_flag as part of the
            // intro bootstrap, before Obj_intPlane executes its first update.
            // It does not dispatch Player_2 here; Tails_Control owns the later
            // AIZ dormant marker (sonic3k.asm:8111-8128,26389-26397).
            camera().setLevelStarted(false);
            introSpawned = spawnIntroObject();
            precomputeIntroTransitionTilemaps();
        } else if (act == 0) {
            // Skip-intro level loading already selected the main AIZ1 terrain
            // profile, so only publish the matching palette/semantic phase.
            if (!paletteSwapped) {
                loadPaletteFromPalPointers(PAL_AIZ_INDEX);
                paletteSwapped = true;
            }
            AizPlaneIntroInstance.setMainLevelPhaseActive(true);
        }
    }

    @Override
    public void update(int act, int frameCounter) {
        if ((mainLevelBlockOrdinal >= 0 && mainLevelBlockKosQueue == null)
                || (mainLevelArtOrdinal >= 0 && mainLevelArtKosQueue == null)
                || (battleshipTerrainKosOrdinal >= 0
                        || battleshipTerrainArtOrdinal >= 0
                        || battleshipObjectArtOrdinal >= 0)
                        && battleshipKosQueue == null
                || (fireOverlayKosOrdinal >= 0 && fireOverlayKosQueue == null)
                || ((act2BlockOrdinal >= 0
                        || act2PrimaryChunkOrdinal >= 0
                        || act2SecondaryChunkOrdinal >= 0)
                        && act2TerrainKosQueue == null)
                || ((act2PrimaryArtOrdinal >= 0 || act2SecondaryArtOrdinal >= 0)
                        && act2ArtKosQueue == null)) {
            rebindHardwareWorkAfterRewind();
        }
        advanceFireMusicRestore();
        if (act == 0) {
            updateAct1(frameCounter);
        } else {
            updateAct2Continuation(frameCounter);
        }
    }

    /**
     * Mirrors the AIZ miniboss escape object's {@code $2E(a0)} countdown.
     * This runs at the start of the event pass because the ROM decrements the
     * object timer during {@code Process_Sprites}; the fire transition itself
     * begins later in this pass, after the object has raised Events_fg_5.
     */
    private void advanceFireMusicRestore() {
        if (fireMusicRestoreTimer < 0) {
            return;
        }
        fireMusicRestoreTimer--;
        if (fireMusicRestoreTimer < 0) {
            int levelMusicId = levelManager().getApparentLevelMusicId();
            if (levelMusicId >= 0) {
                // ROM AIZMinibossCutscene_Escape calls Restore_LevelMusic,
                // which derives the track from Apparent_zone_and_act;
                // restoreMusic() only unwinds a temporary saved-song slot.
                audio().playMusic(levelMusicId);
            }
            fireMusicRestoreTimer = -1;
        }
    }

    /**
     * ROM: {@code SpecialEvents} runs before {@code Process_Sprites}
     * (docs/skdisasm/sonic3k.asm:7888-7894). During the AIZ2 battleship
     * sequence it dispatches {@code AIZ2_DoShipLoop}, which advances camera X
     * by 4 and clamps {@code x_pos(a1)} for Player_1 then Player_2 before
     * {@code MoveSprite2} applies velocity
     * (docs/skdisasm/sonic3k.asm:104082-104091, 105200-105253).
     */
    public void updatePrePhysics(int act) {
        if (act == 0) {
            releaseAizIntroSidekickMarkerPrePhysics();
            return;
        }
        if (act != 1 || !battleshipAutoScrollActive) {
            battleshipAutoScrollRanPrePhysics = false;
            return;
        }
        updateBattleshipAutoScroll(true);
        Camera cam = camera();
        if (!battleshipCameraFrozenForScrollLock) {
            battleshipCameraWasFrozen = cam.getFrozen();
            battleshipCameraFrozenForScrollLock = true;
        }
        cam.setScrollLocked(true);
        battleshipAutoScrollRanPrePhysics = true;
    }

    private void releaseAizIntroSidekickMarkerPrePhysics() {
        int cameraX = camera().getX() & 0xFFFF;
        if (introSidekickMarkerReleased || cameraX < PALETTE_SWAP_X) {
            return;
        }
        // AIZ1_Resize writes Tails_CPU_routine after MoveCamera/Do_ResizeEvents,
        // i.e. after the current Process_Sprites slot but before the next one
        // (sonic3k.asm:38873-38900). This bridge exposes only a prior committed
        // resize write; a preview-only crossing still belongs to this frame's
        // later resize step.
        releaseAizIntroSidekickMarker();
    }

    /**
     * ROM: Dynamic_resize_routine — for AIZ Act 2 this is the separate
     * {@link #aiz2ResizeRoutine} state machine counter, not the base
     * {@link #eventRoutine}. Saved/restored during big ring transitions.
     */
    @Override
    public int getDynamicResizeRoutine() {
        return aiz2ResizeRoutine;
    }

    @Override
    public void setDynamicResizeRoutine(int routine) {
        aiz2ResizeRoutine = routine;
    }

    private void updateAct1(int frameCounter) {
        // Spawn intro object (one-shot)
        if (shouldSpawnIntro(0)
                && (!introSpawned
                        || (!camera().isLevelStarted()
                                && camera().getX() < MIN_X_TRACK_START
                                && !hasLiveIntroObject()))) {
            introSpawned = spawnIntroObject();
        }

        int cameraX = camera().getX();
        // ROM Do_ResizeEvents runs inside DeformBgLayer AFTER MoveCameraX commits
        // the frame's camera position. LevelFrameStep now runs the zone event handler
        // AFTER camera.updatePosition() (matching ROM ScrollHoriz -> DynamicLevelEvents
        // / DeformBgLayer order), so camera().getX() here is already this frame's
        // post-scroll camera X — no end-of-frame prediction needed.
        int frameEndCameraX = camera().getX() & 0xFFFF;
        applyHollowTreeScreenEvent(cameraX);

        // --- Routine 0→1: MinX tracking during intro panning ---
        // ROM (s3.asm AIZ1_Resize Stage 0): Once camera X >= $1000,
        // Camera_min_X_pos tracks camera X to prevent backtracking.
        // At camera X >= $1308 (Stage 1): lock Camera_min_X_pos = $1308.
        if (shouldSpawnIntro(0) && !introMinXLocked) {
            // ROM stage 0->1 lock: track minX until $1308, then freeze at $1308 once.
            if (cameraX >= PALETTE_SWAP_X) {
                camera().setMinX((short) PALETTE_SWAP_X);
                introMinXLocked = true;
            } else if (cameraX >= MIN_X_TRACK_START) {
                camera().setMinX((short) cameraX);
            }
        }

        // --- Routine 0: Palette swap at camera X >= $1308 ---
        // ROM runs Do_ResizeEvents after MoveCameraX has committed the frame's
        // camera position. The engine event step runs before the camera step, so
        // use the predicted end-of-frame X for this threshold just like the
        // later AIZ1 resize and terrain-swap thresholds below.
        if (!paletteSwapped && frameEndCameraX >= PALETTE_SWAP_X) {
            loadPaletteFromPalPointers(PAL_AIZ_INDEX);
            releaseAizIntroSidekickMarker();
            paletteSwapped = true;
            LOG.info("AIZ1: loaded main palette (PalPointers #0x2A) at cameraX=0x"
                    + Integer.toHexString(frameEndCameraX));
        }

        // --- Routine 2: Terrain swap at camera X >= $1400 ---
        // For skip-intro bootstrap, camera starts past this point and still requires
        // the same main-level overlay activation before tree reveal chunk staging.
        // The trace recorder samples checkpoints from end-of-frame state after the
        // camera step, so use the current frame's predicted camera X for these
        // threshold-triggered intro transition checks.
        serviceAiz1MainLevelArt(frameEndCameraX);
        updateIntroNormalRefreshFlag(frameEndCameraX);
        if (frameEndCameraX >= FIRE_OVERLAY_STAGE_X) {
            // Keep the fire overlay staging after the intro/main-level terrain swap.
            // Both paths patch shared level-art VRAM ranges in this engine, and
            // staging flames first lets the terrain swap clobber the curtain bank.
            ensureFireOverlayTilesLoaded();
        }

        // --- Routine 4: Y boundary unlock + dynamic max Y ---
        // Also re-apply main palette here to overwrite cutscene residue:
        // applyEmeraldPalette() (at player X=$13D0 during routine26Explode)
        // writes directly to GPU line 3, bypassing level.setPalette(), which
        // overwrites the Pal_AIZ loaded at $1308. Re-applying at $1400
        // ensures lines 1-3 are correct for the main level.
        if (AizPlaneIntroInstance.isMainLevelPhaseActive() && !boundariesUnlocked) {
            // Skip palette reload if the fire transition is already active — the fire
            // palette takes precedence.  In skip-intro or teleport scenarios, the
            // simulated decompression countdown may expire after the fire has started.
            if (!isFireTransitionActive()) {
                loadPaletteFromPalPointers(PAL_AIZ_INDEX);
            }
            camera().setMinY((short) 0);
            boundariesUnlocked = true;
            LOG.info("AIZ1: unlocked Y boundaries (minY=0)"
                    + (isFireTransitionActive() ? ", skipped palette (fire active)" : ", re-applied main palette"));
        }
        if (boundariesUnlocked) {
            // ROM: Do_ResizeEvents runs *inside* DeformBgLayer (sonic3k.asm:38303-38316),
            // AFTER MoveCameraX/MoveCameraY have committed the new Camera_X_pos. So the
            // resize threshold scan sees the same Camera_X_pos that Process_Sprites will
            // observe on the *next* main-loop iteration.
            //
            // Our LevelFrameStep runs events (step 4) BEFORE the camera step (step 5),
            // so camera().getX() here is the previous frame's value. Use the predicted
            // end-of-frame camera X so resize thresholds fire on the same trace frame
            // ROM does — otherwise Camera_max_Y_pos lags by one frame, which delays the
            // sidekick kill-plane fire by one frame at AIZ1 cam_x crossing $2D80.
            resizeMaxYFromX(frameEndCameraX);
            applyResizeMinYFromX(frameEndCameraX);
            applyResizePaletteMutation(frameEndCameraX);
            applyAct1FireMinXResize(frameEndCameraX);
        }

        updateFireTransition();
    }

    /**
     * Builds the post-$1400 foreground/background tilemaps while the level is
     * still loading so {@link AizIntroTerrainSwap#applyMainLevelBlockOverlay}
     * can swap them in instead of rebuilding both full-level tilemaps on the
     * terrain-swap frame. The ROM pays no such cost: {@code Events_fg_5} only
     * redraws Plane A as the camera scrolls. If the pre-built data is missing
     * (for example after a rewind past the swap consumed it) the swap frame
     * still falls back to the full rebuild.
     *
     * <p>An unavailable ROM is one more way for the pre-built data to be
     * missing, so it takes the same fallback rather than failing the level
     * load. This used to throw, which turned a skipped optimisation into a
     * dead AIZ1 init wherever RomManager had nothing to open. The sibling
     * entry point {@link AizIntroTerrainSwap#precomputeTransitionTilemaps(
     * com.openggf.level.objects.ObjectServices)} already logs and returns on
     * the same IOException; the two call paths now agree.
     *
     * <p>No ROM read is lost by skipping: the $1400 swap frame reads the same
     * bytes itself through {@link AizIntroTerrainSwap#applyMainLevelBlockOverlay},
     * and a genuinely broken ROM still fails there.
     */
    private void precomputeIntroTransitionTilemaps() {
        LevelManager levelManager = levelManager();
        if (levelManager == null || levelManager.hasPrebuiltTilemaps()) {
            return;
        }
        try {
            AizIntroTerrainSwap.precomputeTransitionTilemaps(rom(), levelManager);
        } catch (IOException e) {
            LOG.warning("AIZ intro transition tilemap pre-build skipped: " + e.getMessage());
        }
    }

    private void releaseAizIntroSidekickMarker() {
        // ROM AIZ1_Resize loc_1C4C4 (sonic3k.asm:38898-38900):
        // after the main AIZ palette handoff, Tails_CPU_routine is set to 2.
        SpriteManager sm = spriteManager();
        if (sm == null) {
            return;
        }
        boolean released = false;
        for (AbstractPlayableSprite sidekick : sm.getRegisteredSidekicks()) {
            if (sidekick.getCpuController() != null) {
                released |= sidekick.getCpuController().releaseDormantMarkerForLevelEvent();
            }
        }
        introSidekickMarkerReleased |= released;
    }

    private void serviceAiz1MainLevelArt(int cameraX) {
        if (cameraX < TERRAIN_SWAP_X) {
            return;
        }
        try {
            if (!AizPlaneIntroInstance.isMainLevelPhaseActive()
                    && mainLevelBlockHandle == null
                    && mainLevelArtHandle == null
                    && mainLevelBlockOrdinal < 0
                    && mainLevelArtOrdinal < 0) {
                int introEntry = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                        + Sonic3kConstants.LEVEL_LOAD_BLOCK_AIZ1_INTRO_INDEX
                        * Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
                int blockSource = rom().read32BitAddr(introEntry + 12)
                        & 0x00FF_FFFF;
                int artSource = rom().read32BitAddr(introEntry + 4)
                        & 0x00FF_FFFF;
                mainLevelBlockKosQueue =
                        directKosQueue();
                mainLevelBlockHandle = mainLevelBlockKosQueue.queueStandardKos(
                        rom(), blockSource,
                        S3kKosRamDestinations.blockTableOffset(0x268));
                mainLevelBlockOrdinal = mainLevelBlockHandle.ordinal();
                mainLevelArtKosQueue =
                        moduleKosQueue();
                mainLevelArtHandle = mainLevelArtKosQueue.queue(
                        rom(), artSource, 0x0BE);
                mainLevelArtOrdinal = mainLevelArtHandle.ordinal();
                return;
            }

            if (mainLevelBlockHandle != null
                    && !mainLevelBlockKosQueue.decompressionsPending()) {
                if (!mainLevelBlockKosQueue.isReady(mainLevelBlockHandle)) {
                    throw new IllegalStateException(
                            "AIZ1 direct queue emptied before its block payload became ready");
                }
                byte[] preparedBlocks =
                        mainLevelBlockKosQueue.claim(mainLevelBlockHandle);
                if (!AizIntroTerrainSwap.applyMainLevelBlockOverlay(
                        objectServices(), preparedBlocks)) {
                    throw new IllegalStateException(
                            "AIZ1 main-level block owner has no active S3K level");
                }
                mainLevelBlockHandle = null;
                mainLevelBlockOrdinal = -1;
                mainLevelBlockKosQueue = null;
                AizPlaneIntroInstance.setMainLevelPhaseActive(true);
                LOG.info("AIZ1: main-level block overlay published after direct FIFO retirement");
            }

            if (mainLevelArtHandle != null
                    && mainLevelArtKosQueue.isReady(mainLevelArtHandle)) {
                byte[] preparedTiles =
                        mainLevelArtKosQueue.claim(mainLevelArtHandle);
                if (!AizIntroTerrainSwap.applyMainLevelPatternOverlay(
                        objectServices(), preparedTiles)) {
                    throw new IllegalStateException(
                            "AIZ1 main-level pattern owner has no active S3K level");
                }
                mainLevelArtHandle = null;
                mainLevelArtOrdinal = -1;
                mainLevelArtKosQueue = null;
                LOG.info("AIZ1: main-level pattern overlay published from prepared KosM payload");
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Unable to queue AIZ1 main-level Kosinski work", e);
        }
    }

    /**
     * ROM: Resize_MaxYFromX with word_1AA84 table.
     * Scans table entries until cameraX <= triggerX, then sets max Y.
     * All entries have bit 15 set (immediate, not eased).
     */
    private void resizeMaxYFromX(int cameraX) {
        for (int[] entry : AIZ1_RESIZE_TABLE) {
            int maxY = entry[0];
            int triggerX = entry[1];
            if (triggerX == 0xFFFF || cameraX <= triggerX) {
                camera().setMaxY((short) maxY);
                return;
            }
        }
    }

    private void applyResizeMinYFromX(int cameraX) {
        // ROM AIZ1_Resize loc_1C550 writes Camera_min_Y_pos=0, then raises it
        // to $02E0 once Camera_X_pos >= $2C00 (sonic3k.asm:38939-38958).
        camera().setMinY((short) (cameraX >= RAISED_MIN_Y_THRESHOLD ? RAISED_MIN_Y : 0));
    }

    private void applyAct1FireMinXResize(int cameraX) {
        if (cameraX < FIRE_MIN_X_LOCK) {
            return;
        }
        if (!fireMinXLockReached) {
            // ROM AIZ1_Resize loc_1C594 writes Camera_min_X_pos=$2D80
            // on the threshold frame, then advances Dynamic_resize_routine
            // (sonic3k.asm:38961-38974). Subsequent routines track
            // Camera_X_pos into Camera_min_X_pos (sonic3k.asm:38980-39000).
            camera().setMinX((short) FIRE_MIN_X_LOCK);
            fireMinXLockReached = true;
            return;
        }
        camera().setMinX((short) cameraX);
    }

    /**
     * ROM: loc_1A9EC per-frame palette[2][15] mutation (s3.asm:32171-32195).
     *
     * Every frame while routine 4 is active, the ROM writes a cascading color:
     * <ul>
     *   <li>Unconditional: $020E (bright red)</li>
     *   <li>Camera X >= $2B00: $0004 (nearly black — darkens hollow tree interior)</li>
     *   <li>Camera X >= $2D80: $0C02 (amber — pre-fire zone)</li>
     * </ul>
     * Skipped when the fire transition is active (fire palette takes precedence).
     */
    private void applyResizePaletteMutation(int cameraX) {
        if (isFireTransitionActive()) {
            return;
        }
        LevelManager levelManager = levelManager();
        if (levelManager == null || levelManager.getCurrentLevel() == null) {
            return;
        }
        Level level = levelManager.getCurrentLevel();
        if (level.getPaletteCount() <= 2) {
            return;
        }
        Palette pal2 = level.getPalette(2);

        int segaColor = PALETTE_MUT_COLOR_RED;
        if (cameraX >= PALETTE_MUT_THRESHOLD_FIRE) {
            segaColor = PALETTE_MUT_COLOR_FIRE;
        } else if (cameraX >= PALETTE_MUT_THRESHOLD_DARK) {
            segaColor = PALETTE_MUT_COLOR_DARK;
        }

        S3kPaletteWriteSupport.applyColors(
                paletteRegistryOrNull(),
                level,
                graphics(),
                S3kPaletteOwners.AIZ_RESIZE_MUTATION,
                S3kPaletteOwners.PRIORITY_ZONE_EVENT,
                2,
                new int[] {15},
                new int[] {segaColor});
    }

    /**
     * Returns whether the intro cinematic should be spawned for the given act.
     * The intro only runs on Act 1 (act==0) when the bootstrap is not
     * skipping the intro (i.e., a fresh game start, not an intro-skip scenario).
     *
     * Package-private for test access.
     */
    boolean shouldSpawnIntro(int act) {
        return act == 0 && !bootstrap.isSkipIntro();
    }

    /**
     * ROM {@code loc_13A10}, the AIZ1 intro branch of {@code Tails_CPU_Control}
     * (docs/skdisasm/sonic3k.asm:26389-26397):
     *
     * <pre>
     * loc_13A10:
     *         tst.b   (Tails_CPU_star_post_flag).w
     *         bne.w   loc_13AF4                 ; entered from a star post -- fly in instead
     *         cmpi.w  #0,(Current_zone_and_act).w
     *         bne.s   loc_13A32                 ; not AIZ act 1 -- ordinary CPU follow
     *         bsr.w   sub_13ECA                 ; park Tails at the (0x7F00, 0) marker
     *         move.w  #$A,(Tails_CPU_routine).w
     *         move.b  #$83,object_control(a0)
     *         rts
     * </pre>
     *
     * <p>Both of the ROM's tests were missing here. The zone/act test was
     * evaluated against a hardcoded {@code 0} rather than the live
     * {@code Current_zone_and_act}, so every AIZ act reached this branch, and
     * the {@code Tails_CPU_star_post_flag} test had no counterpart at all.
     * {@code Tails_CPU_star_post_flag} is written exactly once, by
     * {@code Tails_Init}'s {@code move.b (Last_star_post_hit).w,
     * (Tails_CPU_star_post_flag).w} (sonic3k.asm:26155), and read exactly once,
     * here -- its whole purpose is to stop the intro marker on a level entered
     * from a star post or the special-stage return that follows one.
     *
     * <p>Without them a special-stage return into AIZ act 2 parked Tails at the
     * despawn marker on its first CPU tick and left him there for the rest of
     * the act.
     *
     * <p>{@code Last_star_post_hit} is read live rather than latched at the
     * engine's {@code Tails_Init} equivalent. The ROM latch and a live read can
     * only differ if the value changes between the level load and the
     * sidekick's first CPU tick, which requires touching a star post -- itself
     * gameplay that cannot happen before that tick.
     */
    public boolean shouldEnterIntroSidekickDormantMarker(AbstractPlayableSprite sidekick) {
        // The ROM branch is selected by Tails_CPU_Control's Current_zone_and_act
        // and Tails_CPU_star_post_flag tests. It does not re-resolve the active
        // team from Player_mode. The existence of this Player_2 object is the
        // corresponding engine boundary; keeping a configuration/team check
        // here lets a stale session roster metadata value expose a live Tails
        // object that the ROM would park at sub_13ECA instead.
        return sidekick != null
                && shouldSpawnIntro(currentActOrIntro())
                && romLastStarPostHit() == 0;
    }

    /**
     * The live act for the ROM's {@code cmpi.w #0,(Current_zone_and_act).w}. The
     * zone half is implicit: this predicate only runs from the AIZ zone-event
     * owner.
     */
    private int currentActOrIntro() {
        LevelManager lm = levelManager();
        return lm == null ? 0 : lm.getCurrentAct();
    }

    /**
     * The engine's model of {@code Last_star_post_hit} masked as
     * {@code Tails_CPU_star_post_flag} sees it: zero when no star post has been
     * reached. The persistent activation mark is the same value the star post's
     * own already-hit comparison consumes (sonic3k.asm:61606-61610), and it is
     * -1 rather than 0 when no post has been reached.
     */
    private int romLastStarPostHit() {
        LevelManager lm = levelManager();
        if (lm == null) {
            return 0;
        }
        RespawnState checkpoint = lm.getCheckpointState();
        if (checkpoint == null) {
            return 0;
        }
        return Math.max(0, checkpoint.getStarPostActivationMark());
    }

    private boolean spawnIntroObject() {
        AizPlaneIntroInstance existing = findLiveIntroObject();
        if (existing != null) {
            // ROM SpawnLevelMainSprites installs Obj_AIZPlaneIntro in a fixed
            // dynamic-object slot before the first Process_Sprites call
            // (sonic3k.asm:7849-7853, 8111-8126). A duplicate engine event init
            // must re-adopt that live object, not allocate a second parent.
            AizPlaneIntroInstance.adoptActiveIntroInstance(existing);
            return true;
        }
        LevelManager lm = levelManager();
        if (lm == null || lm.getObjectManager() == null) {
            return false;
        }

        // ROM SpawnLevelMainSprites installs Obj_AIZPlaneIntro into one fixed object slot.
        // The event fallback may run through a separate AIZ event instance during bootstrap,
        // so reuse the existing parent instead of allocating a second scroll controller.
        ObjectSpawn spawn = new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0);
        AizPlaneIntroInstance intro = lm.getObjectManager().createDynamicObjectAtSlot(
                () -> new AizPlaneIntroInstance(spawn), AIZ_PLANE_INTRO_SST_SLOT);
        if (intro == null) {
            return false;
        }
        LOG.info("AIZ1 intro: spawned plane intro object");
        return true;
    }

    public void restoreIntroObjectAfterPreludeReset() {
        if (!shouldSpawnIntro(0)) {
            return;
        }
        AizPlaneIntroInstance existing = findLiveIntroObject();
        if (existing != null) {
            AizPlaneIntroInstance.adoptActiveIntroInstance(existing);
            introSpawned = true;
            return;
        }
        LevelManager lm = levelManager();
        if (lm == null || lm.getObjectManager() == null) {
            return;
        }
        ObjectSpawn spawn = new ObjectSpawn(0x60, 0x30, 0, 0, 0, false, 0);
        AizPlaneIntroInstance intro = lm.getObjectManager().createDynamicObjectAtSlot(
                () -> new AizPlaneIntroInstance(spawn), AIZ_PLANE_INTRO_SST_SLOT);
        introSpawned = intro != null;
        if (introSpawned) {
            LOG.info("AIZ1 intro: restored plane intro object for setup prelude");
        }
    }

    private boolean hasLiveIntroObject() {
        return findLiveIntroObject() != null;
    }

    private AizPlaneIntroInstance findLiveIntroObject() {
        LevelManager lm = levelManager();
        if (lm == null || lm.getObjectManager() == null) {
            return null;
        }
        return lm.getObjectManager().getActiveObjects().stream()
                .filter(object -> object instanceof AizPlaneIntroInstance intro && !intro.isDestroyed())
                .map(AizPlaneIntroInstance.class::cast)
                .findFirst()
                .orElse(null);
    }

    private void applyHollowTreeScreenEvent(int cameraX) {
        int eventsFg4 = AizHollowTreeObjectInstance.getTreeRevealCounter();
        if (eventsFg4 == 0) {
            return;
        }

        LevelManager levelManager = levelManager();
        boolean changed = false;
        if (cameraX >= TREE_REVEAL_CLEAR_CAMERA_X || eventsFg4 >= TREE_REVEAL_CLEAR_COUNTER) {
            changed |= applyChunkCopyAndSync(levelManager, 3);
            AizHollowTreeObjectInstance.setTreeRevealCounter(0);
            if (changed) {
                levelManager.uploadForegroundTilemap();
            }
            return;
        } else if (eventsFg4 >= TREE_REVEAL_STEP2_COUNTER) {
            changed |= applyChunkCopyAndSync(levelManager, 2);
        } else if (eventsFg4 >= TREE_REVEAL_STEP3_COUNTER) {
            changed |= applyChunkCopyAndSync(levelManager, 1);
        } else if (eventsFg4 >= TREE_REVEAL_STEP4_COUNTER) {
            changed |= applyChunkCopyAndSync(levelManager, 0);
        }

        TreeRevealStepResult revealStep = applyTreeRevealMaskedRows(levelManager, eventsFg4);
        changed |= revealStep.changed();
        if (revealStep.reachedEnd()) {
            if (eventsFg4 < TREE_REVEAL_STEP2_COUNTER) {
                // In this renderer, early end-of-window can occur before the upper staged
                // chunk swap has been applied, which leaves the top reveal incomplete.
                if (changed) {
                    levelManager.uploadForegroundTilemap();
                }
                return;
            }
            // ROM flow branches to AIZ1SE_ChangeChunk1 when the reveal window is above
            // camera; keep that clear path here once the upper threshold has occurred.
            changed |= applyChunkCopyAndSync(levelManager, 3);
            AizHollowTreeObjectInstance.setTreeRevealCounter(0);
        }

        if (changed) {
            levelManager.uploadForegroundTilemap();
        }
    }

    private TreeRevealStepResult applyTreeRevealMaskedRows(LevelManager levelManager, int eventsFg4) {
        int maskOffset = ((eventsFg4 & 1) == 0) ? TREE_REVEAL_MASK_STRIDE : 0;
        int d0 = (eventsFg4 - 1) >>> 1;
        int rowsRemaining = Math.min(d0, TREE_REVEAL_MAX_ROW_WINDOW);
        int rowY = TREE_REVEAL_INITIAL_DEST_Y - (d0 << 4);
        int cameraYRounded = camera().getY() & ~0xF;
        boolean changed = false;
        boolean drewAny = false;

        while (rowsRemaining >= 0) {
            if (rowY < cameraYRounded) {
                maskOffset += TREE_REVEAL_MASK_ROW_ADVANCE;
                rowY += 0x10;
                rowsRemaining--;
                continue;
            }

            drewAny = true;
            changed |= applyTreeRevealMaskedRowPair(levelManager, rowY, maskOffset);
            // ROM flow after each row pair:
            // lea $10(a6),a6 / addi #$290,d0 after subi #$280 before call => net +$10.
            maskOffset += TREE_REVEAL_MASK_STRIDE;
            rowY += 0x10;
            rowsRemaining--;
        }

        // ROM parity: if all candidate rows are already above the rounded camera Y,
        // the routine branches to AIZ1SE_ChangeChunk1 and ends the reveal event.
        return new TreeRevealStepResult(changed, !drewAny);
    }

    private boolean applyTreeRevealMaskedRowPair(LevelManager levelManager, int destinationY, int maskOffset) {
        if (maskOffset < 0
                || maskOffset + TREE_REVEAL_MASK_STRIDE + TREE_REVEAL_ROW_CHUNKS > TREE_REVEAL_MASKS.length) {
            return false;
        }
        int sourceY = destinationY - TREE_REVEAL_SOURCE_Y_OFFSET;
        boolean changed = false;
        for (int chunkIndex = 0; chunkIndex < TREE_REVEAL_ROW_CHUNKS; chunkIndex++) {
            if (TREE_REVEAL_MASKS[maskOffset + chunkIndex] != 0) {
                changed |= applyMaskedRevealChunk(levelManager, chunkIndex, sourceY, destinationY);
            }
            if (TREE_REVEAL_MASKS[maskOffset + TREE_REVEAL_MASK_STRIDE + chunkIndex] != 0) {
                changed |= applyMaskedRevealChunk(levelManager, chunkIndex, sourceY + TILE_SIZE, destinationY + TILE_SIZE);
            }
        }
        return changed;
    }

    private boolean applyMaskedRevealChunk(LevelManager levelManager, int chunkIndex, int sourceY, int destinationY) {
        int sourceChunkX = TREE_REVEAL_SOURCE_X + chunkIndex * LevelConstants.CHUNK_WIDTH;
        int destinationChunkX = TREE_REVEAL_DEST_X + chunkIndex * LevelConstants.CHUNK_WIDTH;

        int sourceLeft = levelManager.getForegroundTileDescriptorAtWorld(sourceChunkX, sourceY);
        int sourceRight = levelManager.getForegroundTileDescriptorAtWorld(sourceChunkX + TILE_SIZE, sourceY);
        boolean changed = false;
        changed |= levelManager.setForegroundTileDescriptorAtWorld(destinationChunkX, destinationY, sourceLeft);
        changed |= levelManager.setForegroundTileDescriptorAtWorld(destinationChunkX + TILE_SIZE, destinationY, sourceRight);
        return changed;
    }

    private boolean applyChunkCopyAndSync(LevelManager levelManager, int tableIndex) {
        if (tableIndex < 0 || tableIndex >= TREE_REVEAL_ROW_COPIES.length) {
            return false;
        }

        int bit = 1 << tableIndex;
        if ((appliedTreeRevealChunkCopiesMask & bit) != 0) {
            return false;
        }
        appliedTreeRevealChunkCopiesMask |= bit;

        int sourceRow = TREE_REVEAL_ROW_COPIES[tableIndex][0];
        int targetRow = TREE_REVEAL_ROW_COPIES[tableIndex][1];
        int sourceBaseY = sourceRow * TREE_REVEAL_BLOCK_SIZE;
        int targetBaseY = targetRow * TREE_REVEAL_BLOCK_SIZE;

        boolean changed = false;
        for (int columnIndex = 0; columnIndex < TREE_REVEAL_COLUMNS.length; columnIndex++) {
            int sourceBaseX = TREE_REVEAL_SOURCE_COLUMNS[columnIndex] * TREE_REVEAL_BLOCK_SIZE;
            int targetBaseX = TREE_REVEAL_COLUMNS[columnIndex] * TREE_REVEAL_BLOCK_SIZE;
            for (int y = 0; y < TREE_REVEAL_BLOCK_SIZE; y += TILE_SIZE) {
                int sourceY = sourceBaseY + y;
                int targetY = targetBaseY + y;
                for (int x = 0; x < TREE_REVEAL_BLOCK_SIZE; x += TILE_SIZE) {
                    int sourceX = sourceBaseX + x;
                    int targetX = targetBaseX + x;
                    int descriptor = levelManager.getForegroundTileDescriptorFromTilemapAtWorld(sourceX, sourceY);
                    changed |= levelManager.setForegroundTileDescriptorAtWorld(targetX, targetY, descriptor);
                }
            }
        }
        return changed;
    }

    private record TreeRevealStepResult(boolean changed, boolean reachedEnd) {
    }

    // --- Boss / fire transition accessors ---

    /** Called by the boss object to signal that the fire transition should begin. */
    public void setEventsFg5(boolean flag) {
        this.eventsFg5 = flag;
        if (flag) {
            promoteIntroToMainLevelForExplicitFireSignal();
            LOG.info("AIZ1: Events_fg_5 set - fire transition signaled");
        }
    }

    public boolean isEventsFg5() {
        return eventsFg5;
    }

    public boolean isEventsFg4() {
        return eventsFg4;
    }

    public void setBossFlag(boolean flag) {
        this.bossFlag = flag;
    }

    public boolean isBossFlag() {
        return bossFlag;
    }

    /**
     * The act 1 intro and the fire fake-out both reuse Events_fg_5 in the ROM,
     * but tests and trace bootstrap paths can jump straight to the late act 1
     * fire trigger without running the full intro object lifecycle first.
     *
     * When that happens at camera X >= $1400, promote the intro state to the
     * post-swap main-level phase immediately so the explicit fire trigger is not
     * consumed by intro refresh bookkeeping on the next update.
     */
    private void promoteIntroToMainLevelForExplicitFireSignal() {
        if (!shouldSpawnIntro(0) || fireSequencePhase != FireSequencePhase.INACTIVE) {
            return;
        }
        if (!AizPlaneIntroInstance.isMainLevelPhaseActive()) {
            // By the time the act 1 fake-out fire trigger can be raised, the ROM
            // is already in post-intro gameplay. Some tests and replay/bootstrap
            // paths do not advance the singleton camera to that late-camera state
            // before signaling the fire, so promote using at least the $1400
            // terrain-swap threshold instead of requiring the camera singleton to
            // already be there.
            AizPlaneIntroInstance.setMainLevelPhaseActive(true);
            LOG.info("AIZ1: promoted intro state to main-level phase for explicit fire signal");
        }
        introNormalRefreshPending = false;
    }

    public boolean isFireTransitionActive() {
        return fireSequencePhase.curtainActive();
    }

    public boolean isAct2TransitionRequested() {
        return act2TransitionRequested;
    }

    public boolean isFireTransitionScrollActive() {
        return fireSequencePhase.usesFireScrollMode();
    }

    public boolean isPostFireHazeActive() {
        return postFireHazeActive;
    }

    /**
     * Equivalent to Camera_Y_pos_BG_copy during AIZ1 fire transition.
     */
    public int getFireTransitionBgY() {
        return fireBgCopyWord();
    }

    /**
     * Equivalent to Camera_X_pos_BG_copy updates in AIZTrans_WavyFlame.
     */
    public int getFireTransitionBgX() {
        return FIRE_BG_X_BASE + (fireWavePhase & FIRE_BG_X_PHASE_MASK);
    }

    /**
     * Deterministic, bottom-anchored fire curtain state for the AIZ transition overlay.
     */
    public FireCurtainRenderState getFireCurtainRenderState(int screenHeight) {
        if (screenHeight <= 0) {
            return FireCurtainRenderState.inactive();
        }

        if (!fireSequencePhase.curtainActive()) {
            return FireCurtainRenderState.inactive();
        }

        boolean wrapActive = fireSequencePhase.wrapFireTiles(act2WaitFireDrawActive);

        return new FireCurtainRenderState(
                true,
                resolveCoverHeight(screenHeight),
                fireWavePhase,
                fireTransitionFrames,
                resolveFireCurtainSourceX(),
                getFireTransitionBgY(),
                buildFireColumnWaveOffsets(fireTransitionFrames),
                mapCurtainStage(fireSequencePhase),
                FIRE_OVERLAY_TILE_DEST,
                fireOverlayTileCount,
                wrapActive);
    }

    private int resolveCoverHeight(int screenHeight) {
        if (screenHeight <= 0) {
            return 0;
        }
        if (fireSequencePhase == FireSequencePhase.AIZ1_FIRE_TRANSITION) {
            return getFireWallCoverHeightPx(screenHeight);
        }
        // Post-rising phases: full-screen coverage.  The fire EXIT is handled
        // by the renderer: tiles outside the fire zone [0x100..0x310) are not
        // drawn, so the fire naturally scrolls off the top as bgY advances.
        return screenHeight;
    }

    private int resolveFireCurtainSourceX() {
        if (fireSequencePhase == FireSequencePhase.AIZ2_WAIT_FIRE && act2WaitFireDrawActive) {
            return FIRE_SOURCE_X_AIZ2;
        }
        // Use cycling BG X position matching ROM's Camera_X_pos_BG_copy during fire transition
        return getFireTransitionBgX();
    }

    private static FireCurtainStage mapCurtainStage(FireSequencePhase phase) {
        return switch (phase) {
            case AIZ1_FIRE_TRANSITION -> FireCurtainStage.AIZ1_RISING;
            case AIZ1_FIRE_REFRESH -> FireCurtainStage.AIZ1_REFRESH;
            case AIZ1_FINISH -> FireCurtainStage.AIZ1_FINISH;
            case AIZ2_FIRE_REDRAW -> FireCurtainStage.AIZ2_REDRAW;
            case AIZ2_WAIT_FIRE -> FireCurtainStage.AIZ2_WAIT_FIRE;
            case AIZ2_BG_REDRAW -> FireCurtainStage.AIZ2_BG_REDRAW;
            default -> FireCurtainStage.INACTIVE;
        };
    }

    private int[] buildFireColumnWaveOffsets(int animationFrameCounter) {
        int[] waveOffsets = new int[FIRE_WAVE_COLUMN_COUNT];
        int phase = (animationFrameCounter >> 2) & 0xF;
        for (int i = 0; i < FIRE_WAVE_COLUMN_COUNT; i++) {
            phase = (phase + 2) & 0xF;
            waveOffsets[i] = FIRE_COLUMN_WAVE[phase];
        }
        return waveOffsets;
    }

    private void updateAct2Continuation(int frameCounter) {
        if (fireOverlayKosHandle != null) {
            ensureFireOverlayTilesLoaded();
        }
        boolean battleshipAutoScrollActiveAtEntry = battleshipAutoScrollActive;
        int battleshipSpawnRefreshPassesAtEntry = battleshipSpawnRefreshPasses;
        // ROM order inside LevelLoop is DeformBgLayer -> Do_ResizeEvents,
        // then ScreenEvents. The AIZ2 resize stage at camera X >= $4160 sets
        // Events_fg_4, and AIZ2_ScreenEvent consumes it in the same frame to
        // begin the battleship scroll. Run the resize state machine before the
        // screen-event handoff so the trigger is not delayed by one engine tick.
        updateAiz2Resize();

        // ROM: AIZ2_ScreenEvent consumes Events_fg_4 and starts the battleship
        // refresh/draw chain. Keep this separate from AIZ2_Resize so the trigger
        // remains observable and follows the same event handoff as the ROM.
        updateAiz2ScreenEvent();

        if (battleshipSpawnRefreshPassesAtEntry > 0) {
            battleshipSpawnRefreshPasses--;
            if (battleshipSpawnRefreshPasses == 0) {
                spawnBattleshipObject();
            }
        }
        if (battleshipAutoScrollActive
                && !battleshipSpawned
                && battleshipSpawnRefreshPasses == 0) {
            spawnBattleshipObject();
        }
        retireBattleshipKosArtIfReady();

        if (fireSequencePhase.curtainActive() || fireSequencePhase == FireSequencePhase.AIZ2_BG_REDRAW) {
            switch (fireSequencePhase) {
                case AIZ2_FIRE_REDRAW -> runAiz2FireRedraw();
                case AIZ2_WAIT_FIRE -> runAiz2WaitFire();
                case AIZ2_BG_REDRAW -> {
                    firePhaseFrames++;
                    if (firePhaseFrames >= FIRE_REDRAW_FRAMES) {
                        fireSequencePhase = FireSequencePhase.COMPLETE;
                        postFireHazeActive = true;
                        pendingFireSequence = null;
                        setTransitionControlLock(false);
                    }
                }
                default -> {
                    // Act 1 phases are handled by updateFireTransition().
                }
            }
        }

        // Battleship auto-scroll loop
        if (battleshipAutoScrollActiveAtEntry && !battleshipAutoScrollRanPrePhysics) {
            updateBattleshipAutoScroll(false);
        }
        battleshipAutoScrollRanPrePhysics = false;

        // ROM: Adjust_BGDuringLoop runs every frame unconditionally at the top of
        // AIZ2_BackgroundEvent, accumulating camera deltas into Events_fg_1 even
        // after the auto-scroll loop has stopped. This keeps the parallax trees
        // scrolling left as the camera follows Sonic through the forest.
        if (battleshipPostScrollCameraX >= 0) {
            int cameraX = camera().getX();
            int delta = cameraX - battleshipPostScrollCameraX;
            battleshipSmoothScrollX += delta;
            battleshipPostScrollCameraX = cameraX;
            // Stop tracking once trees are cleaned up
            if (cameraX >= BATTLESHIP_TREE_DELETE_CAMERA_X) {
                battleshipPostScrollCameraX = -1;
            }
        }
        // ROM: ShakeScreen_Setup — timed (bomb) and constant (water trigger) modes
        tickScreenShake();

        // ROM: AIZ2_Resize — dynamic boundary state machine (sonic3k.asm:39012)
        updateAiz2EndBossSpawn();
    }

    /**
     * Advances nothing in the AIZ fire chain on a VBlank-only row.
     *
     * <p>Every routine in that chain — {@code AIZ1BGE_FireTransition},
     * {@code AIZ1BGE_FireRefresh}, {@code AIZ1BGE_Finish},
     * {@code AIZ2BGE_FireRedraw} and {@code AIZ2BGE_WaitFire} — is reached only
     * through {@code ScreenEvents}, which the level main loop calls after
     * {@code Wait_VSync} (sonic3k.asm:7889-7899 and :102233-102254, dispatching
     * via :104557-104558 and :105018-105019). A lag row is a main-loop pass that
     * never completed, so {@code Level_frame_counter} does not advance and none
     * of those routines run — neither their {@code AIZ1_FireRise} calls nor
     * their {@code Draw_PlaneVertBottomUp} drains, both of which are main-loop
     * work rather than V-int work. The AIZ1 half of the chain was already
     * frozen here; the AIZ2 half is main-loop work for exactly the same reason,
     * so it freezes too and {@code Camera_Y_pos_BG_copy} simply holds.
     */
    public void advanceVblankOnlyState() {
        // ScreenEvents-owned state stays frozen on VBlank-only rows.
    }

    /**
     * ROM {@code AIZ2BGE_FireRedraw} (Events_routine_bg $00, sonic3k.asm:105036-105050).
     *
     * <p>Each pass runs {@code Draw_PlaneVertBottomUp}, which drains two rows of
     * {@code Draw_delayed_rowcount} per call (sonic3k.asm:103429-103457). While the
     * counter stays non-negative the routine falls through to {@code AIZ1_FireRise}
     * and the plain deformation; when it goes negative the routine clears
     * {@code Events_bg+$00}, advances {@code Events_routine_bg} and FALLS THROUGH
     * into {@code AIZ2BGE_WaitFire} in the SAME pass (loc_50110 has no branch), so
     * that pass runs {@code AIZ1_FireRise} exactly once — from WaitFire's own head.
     */
    private void runAiz2FireRedraw() {
        // Draw_PlaneVertSingleBottomUp: subq #1 always, then again while non-negative.
        int rowsLeft = firePhaseFrames - 1;
        if (rowsLeft >= 0) {
            rowsLeft--;
        }
        firePhaseFrames = rowsLeft;
        if (rowsLeft >= 0) {
            advanceFireRise(false);
            return;
        }
        fireSequencePhase = FireSequencePhase.AIZ2_WAIT_FIRE;
        // ROM clr.w (Events_bg+$00).w at loc_50110 (sonic3k.asm:105049);
        // AIZ2BGE_WaitFire re-latches it on the re-seat pass.
        act2WaitFireDrawActive = false;
        firePhaseFrames = 0;
        runAiz2WaitFire();
    }

    /**
     * ROM {@code AIZ2BGE_WaitFire} (Events_routine_bg $04, sonic3k.asm:105052-105105).
     *
     * <p>Structure, verbatim: {@code AIZ1_FireRise} advances the continuous ramp;
     * while {@code Events_bg+$00} is clear the routine tests
     * {@code (Camera_Y_pos_BG_copy & $7F)} against {@code [$20,$30)} and jumps to
     * {@code PlainDeformation} until the ramp lands inside that window. On the pass
     * that does, it re-seats {@code Camera_Y_pos_BG_copy} to {@code $180 + residue}
     * (word write only — the fractional low word carries over), latches
     * {@code Events_bg+$00} and falls through to the row draw. From then on every
     * pass reaches {@code cmpi.w #$310,(Camera_Y_pos_BG_copy)}; the first pass that
     * does not branch low performs {@code Load_PLC}/{@code LoadEnemyArt}, the
     * palette-line-4 writes and finally {@code Camera_max_X_pos = $6000}.
     *
     * <p>The release is therefore the ramp reaching $310 from the re-seat, not a
     * frame budget: with the ramp at its {@code AIZ1_FireRise} cap of $A000 (i.e.
     * exactly 10px/pass, s3.asm:70383-70399) the duration follows from the residue
     * the ramp happens to land on, which is what makes it hold for any recording.
     */
    private void runAiz2WaitFire() {
        advanceFireRise(false);
        if (!act2WaitFireDrawActive) {
            int residue = fireBgCopyWord() & FIRE_BG_WAIT_RESIDUE_MASK;
            if (residue < FIRE_BG_WAIT_WINDOW_LOW || residue >= FIRE_BG_WAIT_WINDOW_HIGH) {
                // loc_50144: jmp PlainDeformation — no row draw, no release test.
                return;
            }
            setFireBgCopyWord(FIRE_BG_WAIT_RESEAT_BASE + residue);
            // st (Events_bg+$00).w (sonic3k.asm:105076), then fall through.
            act2WaitFireDrawActive = true;
        }
        // loc_50160: Draw_TileRow, then the unsigned `blo` against $310.
        if (Integer.compareUnsigned(fireBgCopyWord(), FIRE_BG_FINISH_Y) < 0) {
            return;
        }
        // ROM order inside the completed branch is Load_PLC, LoadEnemyArt, the
        // palette-line-4 writes and only then Camera_max_X_pos
        // (sonic3k.asm:105086-105096), so the enemy batch is admitted on the very
        // pass that releases the clamp, not the one before it.
        admitAct2EnemyArt();
        // Camera_min_X_pos remains at $0010 so Sonic cannot scroll back into the
        // transition. The handler runs after camera.updatePosition() this frame, so
        // the released bound is consumed by NEXT frame's scroll — matching ROM,
        // where AIZ2BGE_WaitFire runs in ScreenEvents AFTER that frame's
        // MoveCameraX (DeformBgLayer).
        camera().setMaxX((short) AIZ2_POST_FIRE_CAMERA_MAX_X);
        applyPostFireContinuationPaletteLine4(levelManager());
        fireSequencePhase = FireSequencePhase.AIZ2_BG_REDRAW;
        firePhaseFrames = 0;
    }

    /** ROM {@code Camera_Y_pos_BG_copy} — the high word of the 16.16 fire ramp. */
    private int fireBgCopyWord() {
        return (fireBgCopyFixed >>> 16) & 0xFFFF;
    }

    /**
     * ROM {@code move.w d0,(Camera_Y_pos_BG_copy).w} — a word write that leaves the
     * fractional low word (the target of {@code AIZ1_FireRise}'s {@code add.l})
     * untouched.
     */
    private void setFireBgCopyWord(int value) {
        fireBgCopyFixed = (fireBgCopyFixed & 0xFFFF) | ((value & 0xFFFF) << 16);
    }

    /**
     * AIZ2 dynamic resize state machine.
     * ROM: AIZ2_Resize (sonic3k.asm:39012-39241)
     *
     * <p>Routes to Sonic or Knuckles path based on player character.
     * Adjusts maxY/minY/minX dynamically as camera moves through the zone,
     * spawns the miniboss, and sets up the battleship sequence boundaries.
     */
    private void updateAiz2Resize() {
        PlayerCharacter character = playerCharacter();
        boolean isKnuckles = (character == PlayerCharacter.KNUCKLES);

        switch (aiz2ResizeRoutine) {
            // --- Routine 0: Route to Sonic or Knuckles path ---
            case 0 -> {
                if (isKnuckles) {
                    aiz2ResizeRoutine = 0x12;
                    updateAiz2KnuxResize1();
                } else {
                    aiz2ResizeRoutine = 2;
                    updateAiz2SonicResize1();
                }
            }
            // --- Sonic path ---
            case 2 -> updateAiz2SonicResize1();
            case 4 -> updateAiz2SonicResize2();
            case 6 -> updateAiz2SonicResize3();
            case 8 -> updateAiz2SonicResize4();
            case 0xA -> updateAiz2SonicResize5();
            case 0xC -> updateAiz2SonicResize6();
            case 0xE -> updateAiz2SonicResize7();
            // case 0x10: SonicResizeEnd — no-op
            // --- Knuckles path ---
            case 0x12 -> updateAiz2KnuxResize1();
            case 0x14 -> updateAiz2KnuxResize2();
            case 0x16 -> updateAiz2KnuxResize3();
            case 0x18 -> updateAiz2KnuxResize4();
            case 0x1A -> updateAiz2KnuxResize5();
            // case 0x1C: KnuxResizeEnd — no-op
            default -> { /* end state */ }
        }
    }

    /**
     * Mirrors ROM {@code cmpi.w #1, (Apparent_zone_and_act).w}.
     *
     * <p>{@code Apparent_zone_and_act} packs the apparent zone into the high
     * byte and the apparent act into the low byte.  In AIZ event code the
     * apparent zone is implicitly AIZ (zone 0), so equality with $0001 is
     * equivalent to checking that {@link LevelManager#getApparentAct()} is 1.
     * The engine's seamless AIZ1 -> AIZ2 fire transition (and the trace
     * reload-resume path) preserves apparentAct, matching ROM where
     * {@code AIZ1_AIZ2_Transition} (sonic3k.asm:104627) does not write
     * {@code Apparent_zone_and_act}.  Direct AIZ2 entry (level select,
     * starpost respawn from a saved AIZ2 starpost) sets it to $0001 via
     * {@code LevelSelect_StartZone} (sonic3k.asm:10222) /
     * {@code Load_Starpost_Settings} (sonic3k.asm:61760), which the engine
     * mirrors through {@link LevelManager#loadZoneAndAct(int, int)} and the
     * results-screen handoff that calls {@link ObjectServices#setApparentAct(int)}.
     */
    private boolean isApparentAct2() {
        return levelManager().getApparentAct() == 1;
    }

    // --- Sonic resize routines (sonic3k.asm:39046-39153) ---

    /** ROM: AIZ2_SonicResize1 — set maxY=$590 at camera X >= $2E0. */
    private void updateAiz2SonicResize1() {
        if (camera().getX() < AIZ2_SONIC_RESIZE1_TRIGGER_X) {
            return;
        }
        camera().setMaxY((short) AIZ2_DEFAULT_MAX_Y);
        aiz2ResizeRoutine = 4;
        // ROM (sonic3k.asm:39053): cmpi.w #1, (Apparent_zone_and_act).w
        //   bne.s locret_1C68E
        // Only skip the miniboss area when ROM's Apparent_zone_and_act equals
        // AIZ2 (zone=0, act=1). The seamless AIZ1 -> AIZ2 fire transition
        // (sonic3k.asm:104627 AIZ1_AIZ2_Transition) does NOT update
        // Apparent_zone_and_act, so it stays at AIZ1=0x0000 across the
        // continuation; the same applies to the engine's reload-resume path
        // because the seamless transition coordinator preserves apparentAct.
        // Direct AIZ2 entry from level select / starpost respawn / save load
        // sets Apparent_zone_and_act = $0001 (sonic3k.asm:10222, :61760), so
        // the miniboss-skip path activates only there.
        if (isApparentAct2()) {
            camera().setMinX((short) AIZ2_SONIC_RESIZE2_LOCK_X);
            aiz2ResizeRoutine = 6; // skip SonicResize2 (miniboss area)
        }
    }

    /** ROM: AIZ2_SonicResize2 — continuous maxY + miniboss spawn. */
    private void updateAiz2SonicResize2() {
        // ROM: Do_ResizeEvents runs *inside* DeformBgLayer (sonic3k.asm:38303-38316)
        // AFTER MoveCameraX has committed the new Camera_X_pos. LevelFrameStep now
        // runs the zone event handler AFTER camera.updatePosition() (matching that ROM
        // order), so camera().getX() here is already this frame's post-scroll camera X
        // — the maxY narrow at $ED0 fires on the same trace frame ROM does without
        // end-of-frame prediction.
        int cameraX = camera().getX() & 0xFFFF;
        int maxY = AIZ2_DEFAULT_MAX_Y;
        if (cameraX >= AIZ2_SONIC_RESIZE2_BOSS_TRIGGER_X) {
            maxY = AIZ2_SONIC_RESIZE2_BOSS_MAX_Y;
        }
        camera().setMaxY((short) maxY);

        if (cameraX >= AIZ2_SONIC_RESIZE2_LOCK_X) {
            camera().setMinX((short) AIZ2_SONIC_RESIZE2_LOCK_X);
            if (!minibossSpawned) {
                spawnAiz2Miniboss(AIZ2_SONIC_BOSS_X, AIZ2_SONIC_BOSS_Y);
            }
            aiz2ResizeRoutine = 6;
        }
    }

    /** ROM: AIZ2_SonicResize3 — maxY=$630 at camera X >= $1500. */
    private void updateAiz2SonicResize3() {
        if (camera().getX() < AIZ2_SONIC_RESIZE3_TRIGGER_X) {
            return;
        }
        camera().setMaxY((short) AIZ2_SONIC_RESIZE3_MAX_Y);
        aiz2ResizeRoutine = 8;
    }

    /** ROM: AIZ2_SonicResize4 — battleship art load at camera X >= $3C00. */
    private void updateAiz2SonicResize4() {
        if (camera().getX() < AIZ2_SONIC_RESIZE4_TRIGGER_X) {
            return;
        }
        queueBattleshipKosArt();
        eventsFg5 = true; // Signal to background event
        // ROM: AIZ2BGE_Normal applies a one-time BG camera Y adjustment when eventsFg5 fires.
        // If Camera_Y_pos < $400: add $A8; otherwise: add -$198.
        // This shifts the background to show more sky before the bombing sequence.
        int cameraY = camera().getY() & 0xFFFF;
        battleshipBgYOffset = (cameraY < 0x400) ? 0xA8 : -0x198;
        aiz2ResizeRoutine = 0xA;
        LOG.info("AIZ2 Sonic resize4: battleship art trigger at X=0x"
                + Integer.toHexString(camera().getX())
                + ", bgYOffset=" + battleshipBgYOffset);
    }

    /** ROM: AIZ2_SonicResize5 — minY=$15A at camera X >= $3F00. */
    private void updateAiz2SonicResize5() {
        if (camera().getX() < AIZ2_SONIC_RESIZE5_TRIGGER_X) {
            return;
        }
        camera().setMinY((short) AIZ2_SONIC_RESIZE5_MIN_Y);
        aiz2ResizeRoutine = 0xC;
    }

    /** ROM: AIZ2_SonicResize6 — maxY=$15A at camera X >= $4000. */
    private void updateAiz2SonicResize6() {
        if (camera().getX() < AIZ2_SONIC_RESIZE6_TRIGGER_X) {
            return;
        }
        camera().setMaxY((short) AIZ2_SONIC_RESIZE5_MIN_Y);
        aiz2ResizeRoutine = 0xE;
    }

    /** ROM: AIZ2_SonicResize7 — signal battleship sequence at camera X >= $4160. */
    private void updateAiz2SonicResize7() {
        if (camera().getX() < AIZ2_SONIC_RESIZE7_TRIGGER_X) {
            return;
        }
        eventsFg4 = true;
        aiz2ResizeRoutine = 0x10; // SonicResizeEnd
    }

    private void updateAiz2ScreenEvent() {
        if (!eventsFg4 || battleshipAutoScrollActive || battleshipSpawned) {
            return;
        }
        eventsFg4 = false;
        startBattleshipSequence();
    }

    // --- Knuckles resize routines (sonic3k.asm:39157-39241) ---

    /** ROM: AIZ2_KnuxResize1 — set maxY=$590 at camera X >= $2E0. */
    private void updateAiz2KnuxResize1() {
        if (camera().getX() < AIZ2_KNUX_RESIZE1_TRIGGER_X) {
            return;
        }
        camera().setMaxY((short) AIZ2_DEFAULT_MAX_Y);
        aiz2ResizeRoutine = 0x14;
        // ROM (sonic3k.asm:39164): cmpi.w #1, (Apparent_zone_and_act).w —
        // same gate as SonicResize1. Only skip the miniboss area when
        // Apparent_zone_and_act equals AIZ2 (direct entry); the AIZ1 fire
        // transition leaves Apparent_zone_and_act at AIZ1, so the miniboss
        // path stays active for that arrival case.
        if (isApparentAct2()) {
            camera().setMinX((short) AIZ2_KNUX_RESIZE2_LOCK_X);
            aiz2ResizeRoutine = 0x16; // skip KnuxResize2 (miniboss area)
        }
    }

    /** ROM: AIZ2_KnuxResize2 — continuous maxY + miniboss spawn. */
    private void updateAiz2KnuxResize2() {
        int cameraX = camera().getX();
        int maxY = AIZ2_DEFAULT_MAX_Y;
        if (cameraX >= AIZ2_KNUX_RESIZE2_BOSS_TRIGGER_X) {
            maxY = AIZ2_KNUX_RESIZE2_BOSS_MAX_Y;
        }
        camera().setMaxY((short) maxY);

        if (cameraX >= AIZ2_KNUX_RESIZE2_LOCK_X) {
            camera().setMinX((short) AIZ2_KNUX_RESIZE2_LOCK_X);
            if (!minibossSpawned) {
                spawnAiz2Miniboss(AIZ2_KNUX_BOSS_X, AIZ2_KNUX_BOSS_Y);
            }
            // ROM: set Target_water_level = $F80
            WaterSystem waterSystem = waterSystem();
            waterSystem.setWaterLevelTarget(0, 1, AIZ2_KNUX_WATER_LEVEL);
            aiz2ResizeRoutine = 0x16;
        }
    }

    /** ROM: AIZ2_KnuxResize3 — target maxY=$820 at camera X >= $11A0. */
    private void updateAiz2KnuxResize3() {
        if (camera().getX() < AIZ2_KNUX_RESIZE3_TRIGGER_X) {
            return;
        }
        camera().setMaxYTarget((short) AIZ2_KNUX_RESIZE3_TARGET_MAX_Y);
        aiz2ResizeRoutine = 0x18;
    }

    /** ROM: AIZ2_KnuxResize4 — battleship art load at camera X >= $3B80. */
    private void updateAiz2KnuxResize4() {
        if (camera().getX() < AIZ2_KNUX_RESIZE4_TRIGGER_X) {
            return;
        }
        queueBattleshipKosArt();
        camera().setMinX((short) AIZ2_KNUX_RESIZE4_TRIGGER_X);
        camera().setMaxYTarget((short) AIZ2_KNUX_RESIZE4_TARGET_MAX_Y);
        eventsFg5 = true;
        // ROM: AIZ2BGE_Normal BG Y adjustment (same logic as Sonic path)
        int cameraY = camera().getY() & 0xFFFF;
        battleshipBgYOffset = (cameraY < 0x400) ? 0xA8 : -0x198;
        aiz2ResizeRoutine = 0x1A;
        LOG.info("AIZ2 Knux resize4: battleship art trigger at X=0x"
                + Integer.toHexString(camera().getX())
                + ", bgYOffset=" + battleshipBgYOffset);
    }

    /** ROM: AIZ2_KnuxResize5 — lock minX=$3F80 at camera X >= $3F80. */
    private void updateAiz2KnuxResize5() {
        if (camera().getX() < AIZ2_KNUX_RESIZE5_TRIGGER_X) {
            return;
        }
        camera().setMinX((short) AIZ2_KNUX_RESIZE5_TRIGGER_X);
        aiz2ResizeRoutine = 0x1C; // KnuxResizeEnd
    }

    /** Spawn the AIZ2 miniboss at the given position. */
    private void spawnAiz2Miniboss(int bossX, int bossY) {
        minibossSpawned = true;
        ObjectSpawn bossSpawn = new ObjectSpawn(bossX, bossY, 0x91, 0, 0, false, bossY);
        AizMinibossInstance boss = new AizMinibossInstance(bossSpawn);
        var objManager = levelManager().getObjectManager();
        if (objManager != null) {
            objManager.addDynamicObject(boss);
        }
        LOG.info("AIZ2 resize: spawned miniboss at (0x" + Integer.toHexString(bossX)
                + ", 0x" + Integer.toHexString(bossY) + ")");
    }

    // ===== Battleship bombing sequence =====

    /**
     * Starts the battleship bombing sequence: locks the camera, begins auto-scroll,
     * and spawns the battleship object.
     */
    private void startBattleshipSequence() {
        battleshipAutoScrollActive = true;
        // ROM writes Pal_AIZBattleship to Normal_palette_line_2, which maps to
        // engine palette index 1 (palette 0 is the character line).
        loadPalette(1, Sonic3kConstants.PAL_AIZ_BATTLESHIP_ADDR);
        // Lock camera to current X (player can only move within the visible screen)
        int cameraX = camera().getX();
        // Initialize smooth scroll counter at current camera position (never wraps)
        battleshipSmoothScrollX = cameraX;
        camera().setMinX((short) cameraX);
        camera().setMaxX((short) cameraX);
        // Lock player control during the bombing sequence
        setTransitionControlLock(false); // Player can still run left/right

        // AIZ2SE_Normal falls through to AIZ2SE_ShipRefresh. The plane redraw
        // must report complete on the following ScreenEvents pass before ROM calls
        // AllocateObject for Obj_AIZBattleship (sonic3k.asm:104885-104925).
        // Snapshot-at-entry dispatch above prevents this newly armed work from
        // being consumed in the same pass.
        battleshipSpawnRefreshPasses = 1;
    }

    private void spawnBattleshipObject() {
        if (battleshipSpawned) {
            return;
        }
        battleshipSpawned = true;
        // AIZ2SE_ShipRefresh clears Water_flag on the redraw pass that owns
        // the battleship allocation (sonic3k.asm:104911-104934).
        waterSystem().setWaterEnabled(Sonic3kZoneIds.ZONE_AIZ, 1, false);
        int cameraX = camera().getX();
        int baseSecondaryY = (camera().getY() + 0x08F0) & 0x0FF0;
        ObjectSpawn shipSpawn = new ObjectSpawn(cameraX, baseSecondaryY, 0, 0, 0, false, 0);
        AizBattleshipInstance ship = new AizBattleshipInstance(shipSpawn, baseSecondaryY);
        var objManager = levelManager().getObjectManager();
        if (objManager != null) {
            objManager.addDynamicObject(ship);
        }
        LOG.info("AIZ2 battleship: spawned at cameraX=0x" + Integer.toHexString(cameraX));
    }

    private void retireBattleshipKosArtIfReady() {
        if ((battleshipTerrainKosHandle == null) != (battleshipTerrainArtHandle == null)) {
            throw new IllegalStateException(
                    "AIZ battleship terrain owner lost one half of its Kos/KosM pair");
        }
        if (battleshipTerrainKosHandle == null
                && battleshipTerrainArtHandle == null
                && battleshipObjectArtHandle == null) {
            battleshipKosQueue = null;
            return;
        }
        if (battleshipKosQueue == null) {
            throw new IllegalStateException(
                    "AIZ battleship KosM owner has live handles without its rebound queue");
        }
        if (battleshipTerrainKosHandle != null
                && battleshipTerrainArtHandle != null
                && directKosQueue().isReady(battleshipTerrainKosHandle)
                && battleshipKosQueue.isReady(battleshipTerrainArtHandle)) {
            byte[] preparedTerrain = directKosQueue().claim(battleshipTerrainKosHandle);
            byte[] preparedTiles = battleshipKosQueue.claim(battleshipTerrainArtHandle);
            applyBattleshipTerrain(preparedTerrain, preparedTiles);
            battleshipTerrainKosHandle = null;
            battleshipTerrainKosOrdinal = -1;
            battleshipTerrainArtHandle = null;
            battleshipTerrainArtOrdinal = -1;
        }
        if (battleshipObjectArtHandle != null
                && battleshipKosQueue.isReady(battleshipObjectArtHandle)) {
            battleshipKosQueue.claim(battleshipObjectArtHandle);
            battleshipObjectArtHandle = null;
            battleshipObjectArtOrdinal = -1;
        }
        if (battleshipTerrainKosHandle == null
                && battleshipTerrainArtHandle == null
                && battleshipObjectArtHandle == null) {
            battleshipKosQueue = null;
        }
    }

    private void queueBattleshipKosArt() {
        if (battleshipTerrainKosHandle != null
                || battleshipTerrainArtHandle != null
                || battleshipObjectArtHandle != null) {
            return;
        }
        try {
            battleshipTerrainKosHandle = directKosQueue().queueStandardKos(
                    rom(),
                    Sonic3kConstants.AIZ2_16X16_BOMBERSHIP_ADDR,
                    S3kKosRamDestinations.blockTableOffset(
                            Sonic3kConstants.AIZ2_16X16_BOMBERSHIP_DEST_OFFSET));
            battleshipTerrainKosOrdinal = battleshipTerrainKosHandle.ordinal();
            battleshipKosQueue =
                    moduleKosQueue();
            battleshipTerrainArtHandle = battleshipKosQueue.queue(
                    rom(),
                    Sonic3kConstants.AIZ2_8X8_BOMBERSHIP_ADDR,
                    Sonic3kConstants.AIZ2_8X8_BOMBERSHIP_DEST_TILE);
            battleshipTerrainArtOrdinal = battleshipTerrainArtHandle.ordinal();
            battleshipObjectArtHandle = battleshipKosQueue.queue(
                    rom(),
                    Sonic3kConstants.ART_KOSM_AIZ2_BOMBERSHIP_ADDR,
                    Sonic3kConstants.ART_TILE_AIZ2_BOMBERSHIP);
            battleshipObjectArtOrdinal = battleshipObjectArtHandle.ordinal();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to queue AIZ2 battleship KosM art", e);
        }
    }

    private void updateAiz2EndBossSpawn() {
        if (endBossSpawned || bossFlag || hasLiveAizEndBoss()) {
            return;
        }

        PlayerCharacter character = playerCharacter();
        boolean isKnuckles = character == PlayerCharacter.KNUCKLES;
        int triggerX = isKnuckles ? AIZ_END_BOSS_KNUX_LOCK_X : AIZ_END_BOSS_LOCK_X;
        if (camera().getX() < triggerX) {
            return;
        }
        applyEndBossPlayerPriority();
        endBossSpawned = true;
        int spawnX = isKnuckles ? AIZ_END_BOSS_KNUX_LAYOUT_X : AIZ_END_BOSS_SONIC_LAYOUT_X;
        int spawnY = isKnuckles ? AIZ_END_BOSS_KNUX_LAYOUT_Y : AIZ_END_BOSS_SONIC_LAYOUT_Y;
        ObjectSpawn bossSpawn = new ObjectSpawn(
                spawnX, spawnY, Sonic3kObjectIds.AIZ_END_BOSS, 0, 0, false, spawnY);
        spawnObject(() -> new AizEndBossInstance(bossSpawn));
        LOG.info("AIZ2 end boss: spawned at cameraX=0x" + Integer.toHexString(camera().getX()));
    }

    private void applyEndBossPlayerPriority() {
        ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                () -> camera().getFocusedSprite() instanceof AbstractPlayableSprite player ? player : null,
                this::eventSidekicks);
        for (PlayableEntity participant : playerQuery.playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            // Obj_PathSwap subtype $22 at x=$3F68 sets art_tile bit 7 before
            // the waterfall arena. Re-publish that native handoff here so a
            // direct/rewound event entry cannot leave the player behind the
            // high-priority waterfall tiles (sonic3k.asm:39780-39850).
            participant.setHighPriority(true);
        }
    }

    private boolean hasLiveAizEndBoss() {
        var objManager = levelManager().getObjectManager();
        if (objManager == null) {
            return false;
        }
        return objManager.getActiveObjects().stream()
                .anyMatch(object -> object instanceof AizEndBossInstance boss && !boss.isDestroyed());
    }

    /**
     * Post-rewind-restore reconciliation of the AIZ2 ship-loop / boss sequence.
     *
     * <p>The one-shot spawn guards ({@code minibossSpawned}, {@code battleshipSpawned},
     * {@code endBossSpawned}) and the auto-scroll camera lock are reflectively captured
     * by the AIZ event sidecar, but the dynamic objects they gate are recreated by the
     * object-manager rewind restore path. If a sequence-driving object failed to be recreated,
     * a restore can leave an impossible state: a guard marked spawned with no live object,
     * or {@code battleshipAutoScrollActive} true with no battleship/small-boss left to
     * call {@link #onBattleshipComplete()}/{@link #onBossSmallComplete()} — which would
     * force-lock the camera every frame forever (softlock).
     *
     * <p>This is a live-object predicate, not a zone/frame/route carve-out: with the
     * restore paths in place the driver objects are present and nothing here fires. It is a
     * defense-in-depth backstop guaranteeing the camera lock is never left orphaned.
     */
    public void reconcileSequenceAfterRewindRestore() {
        // End-boss/miniboss latches: clear when their instance is gone so the normal
        // spawn path can re-fire (updateAiz2EndBossSpawn already re-checks live presence).
        if (endBossSpawned && !hasLiveAizEndBoss()) {
            endBossSpawned = false;
        }
        if (minibossSpawned
                && !anyLiveObject(o -> o instanceof AizMinibossInstance b && !b.isDestroyed())) {
            minibossSpawned = false;
        }

        // Battleship auto-scroll loop: ended only by the battleship (pre-bombing) or the
        // small boss craft (post-bombing). If that driver is gone, the loop can never end,
        // so release it the same way onBossSmallComplete() does (unlock camera bounds).
        if (battleshipAutoScrollActive) {
            boolean driverLive = (battleshipWrapX == BATTLESHIP_WRAP_X_POST_BOMBING)
                    ? anyLiveObject(o -> o instanceof AizBossSmallInstance b && !b.isDestroyed())
                    : anyLiveObject(o -> o instanceof AizBattleshipInstance b && !b.isDestroyed());
            if (!driverLive) {
                onBossSmallComplete();
            }
        }

        // Rebind the boss-endgame Knuckles cutscene pointer to the restored
        // object (or null). Runs after object-manager restore so it overrides
        // the stale reference dropped by Aiz2BossEndSequenceState.restore().
        rebindCutsceneKnucklesAfterRestore();
    }

    private void rebindCutsceneKnucklesAfterRestore() {
        var objManager = levelManager() != null ? levelManager().getObjectManager() : null;
        com.openggf.game.sonic3k.objects.CutsceneKnucklesAiz2Instance live = null;
        if (objManager != null) {
            for (var obj : objManager.getActiveObjects()) {
                if (obj instanceof com.openggf.game.sonic3k.objects.CutsceneKnucklesAiz2Instance k
                        && !k.isDestroyed()) {
                    live = k;
                    break;
                }
            }
        }
        com.openggf.game.sonic3k.objects.Aiz2BossEndSequenceState.setActiveKnuckles(live);
    }

    private boolean anyLiveObject(java.util.function.Predicate<Object> predicate) {
        var objManager = levelManager() != null ? levelManager().getObjectManager() : null;
        if (objManager == null) {
            return false;
        }
        return objManager.getActiveObjects().stream().anyMatch(predicate);
    }

    private void applyBattleshipTerrain(
            byte[] preparedBlocks16x16, byte[] preparedTiles8x8) {
        if (battleshipTerrainLoaded) {
            return;
        }
        if (preparedBlocks16x16 == null || preparedTiles8x8 == null) {
            throw new IllegalArgumentException("prepared battleship terrain payloads");
        }
        Level level = levelManager().getCurrentLevel();
        if (!(level instanceof Sonic3kLevel sonic3kLevel)) {
            throw new IllegalStateException(
                    "AIZ2 battleship terrain owner has no active S3K level");
        }

        sonic3kLevel.applyChunkOverlay(
                preparedBlocks16x16,
                Sonic3kConstants.AIZ2_16X16_BOMBERSHIP_DEST_OFFSET,
                false);
        sonic3kLevel.applyPatternOverlay(
                preparedTiles8x8,
                Sonic3kConstants.AIZ2_8X8_BOMBERSHIP_DEST_BYTES,
                false);
        loadPaletteFromPalPointers(PAL_AIZ_BOSS_INDEX);
        levelManager().invalidateAllTilemaps();
        battleshipTerrainLoaded = true;

        LOG.info("AIZ2 battleship: loaded terrain overlays (16x16="
                + preparedBlocks16x16.length + " bytes, 8x8=" + preparedTiles8x8.length
                + " bytes) and boss palette");
    }


    /**
     * Per-frame auto-scroll logic during the battleship bombing loop.
     * ROM: AIZ2_ScreenEvent auto-scroll handler.
     * Scrolls the camera right by {@link #BATTLESHIP_SCROLL_SPEED} pixels per frame
     * and wraps everything back by {@link #BATTLESHIP_WRAP_DIST} when the camera
     * reaches the wrap boundary.
     */
    private void updateBattleshipAutoScroll(boolean useCentreCoordinates) {
        Camera cam = camera();
        int cameraX = cam.getX();

        // Clear per-frame wrap offset (ROM: Level_repeat_offset)
        levelRepeatOffset = 0;

        // Smooth scroll counter increments every frame without wrapping.
        // Used by the parallax scroll handler for continuous BG deformation.
        battleshipSmoothScrollX += BATTLESHIP_SCROLL_SPEED;

        // Auto-scroll right
        int newCameraX = cameraX + BATTLESHIP_SCROLL_SPEED;
        cam.setX((short) newCameraX);
        cam.setMinX((short) newCameraX);
        cam.setMaxX((short) newCameraX);

        // Wrap-back: when camera reaches the wrap boundary, subtract the active
        // repeat distance from all positions for seamless looping.
        if (newCameraX >= battleshipWrapX) {
            int wrapDelta = battleshipWrapX == BATTLESHIP_WRAP_X_BOMBING
                    ? BATTLESHIP_WRAP_DIST
                    : BATTLESHIP_WRAP_DIST_POST_BOMBING;
            levelRepeatOffset = wrapDelta;

            cam.setX((short) (newCameraX - wrapDelta));
            cam.setMinX((short) (newCameraX - wrapDelta));
            cam.setMaxX((short) (newCameraX - wrapDelta));

            ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                    () -> cam.getFocusedSprite() instanceof AbstractPlayableSprite player ? player : null,
                    this::eventSidekicks);
            for (PlayableEntity participant : playerQuery.playersFor(
                    ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
                if (participant instanceof AbstractPlayableSprite player) {
                    if (useCentreCoordinates) {
                        player.setCentreXPreserveSubpixel((short) (player.getCentreX() - wrapDelta));
                    } else {
                        player.setX((short) (player.getX() - wrapDelta));
                    }
                }
            }

            // Wrap all active bombing-sequence objects (ROM: Level_repeat_offset)
            var objManager = levelManager().getObjectManager();
            if (objManager != null) {
                for (var obj : objManager.getActiveObjects()) {
                    if (obj instanceof AizShipBombInstance bomb) {
                        bomb.applyWrapOffset(wrapDelta);
                    } else if (obj instanceof AizBombExplosionInstance explosion) {
                        explosion.applyWrapOffset(wrapDelta);
                    }
                }
                // ObjPosLoad has no Level_repeat_offset special case; after
                // AIZ2_DoShipLoop lowers Camera_X_pos, the normal backward
                // cursor path retreats Object_load_addr_front (sonic3k.asm:
                // loc_1B8D2 -> loc_1B8F2). Do not hide the wrap from placement,
                // or offscreen-cleared entries cannot be reprocessed on the
                // next loop through the same screen section.
            }

            LOG.fine("AIZ2 battleship: wrap-back at cameraX=0x"
                    + Integer.toHexString(newCameraX));
        }

        // ROM: sub_50318 — clamp X within camera margins for BOTH players.
        // Called for Player_1 then Player_2 in AIZ2_DoShipLoop.
        int camX = cam.getX();
        ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                () -> cam.getFocusedSprite() instanceof AbstractPlayableSprite player ? player : null,
                this::eventSidekicks);
        for (PlayableEntity participant : playerQuery.playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (participant instanceof AbstractPlayableSprite player) {
                clampPlayerDuringAutoScroll(player, camX, useCentreCoordinates);
            }
        }
        syncSidekickBoundsToLiveCamera(cam);
    }

    private List<AbstractPlayableSprite> eventSidekicks() {
        SpriteManager sm = spriteManager();
        return sm != null ? sm.getSidekicks() : List.of();
    }

    private void syncSidekickBoundsToLiveCamera(Camera cam) {
        int minX = cam.getMinX();
        int maxX = cam.getMaxX();
        int maxY = Math.max(cam.getMaxY(), cam.getMaxYTarget());
        for (AbstractPlayableSprite sidekick : spriteManager().getSidekicks()) {
            if (sidekick.getCpuController() != null) {
                // ROM Tails_Check_Screen_Boundaries reads Camera_min/max directly
                // during Process_Sprites (sonic3k.asm:28407-28452). The engine's
                // sidekick CPU carries a mirrored bound override, so refresh it
                // when AIZ2_DoShipLoop rewrites camera bounds before physics
                // (sonic3k.asm:105200-105253).
                sidekick.getCpuController().setLevelBounds(minX, maxX, maxY);
            }
        }
    }

    /**
     * ROM: sub_50318 — clamp a player's X position within the auto-scroll camera margins.
     * If pushed rightward from the left edge, ground velocity is set to $400.
     */
    private static void clampPlayerDuringAutoScroll(AbstractPlayableSprite sprite, int camX,
                                                   boolean useCentreCoordinates) {
        // ROM sub_50318 clears anim from Wait ($05) to Walk ($00) before its
        // position clamps. This is independent of whether either edge clamp
        // fires: the battleship scroll makes an idle player resume the walk
        // script before normal movement applies the frame's friction.
        if (sprite.getAnimationId() == Sonic3kAnimationIds.WAIT.id()) {
            sprite.setAnimationId(Sonic3kAnimationIds.WALK);
        }
        int minPlayerX = camX + PLAYER_LEFT_MARGIN;
        int maxPlayerX = camX + PLAYER_RIGHT_MARGIN;
        short playerX = useCentreCoordinates ? sprite.getCentreX() : sprite.getX();
        if (playerX < minPlayerX) {
            if (useCentreCoordinates) {
                sprite.setCentreXPreserveSubpixel((short) minPlayerX);
            } else {
                sprite.setX((short) minPlayerX);
            }
            // ROM: move.w #$400,ground_vel(a1) — push player rightward with the scroll
            sprite.setGSpeed((short) 0x400);
        } else if (playerX >= maxPlayerX) {
            if (useCentreCoordinates) {
                sprite.setCentreXPreserveSubpixel((short) maxPlayerX);
            } else {
                sprite.setX((short) maxPlayerX);
            }
        }
    }

    /** ROM: Level_repeat_offset — non-zero on wrap frames, objects subtract this from X. */
    public int getLevelRepeatOffset() {
        return levelRepeatOffset;
    }

    /** True when the battleship auto-scroll loop is active. */
    public boolean isBattleshipAutoScrollActive() {
        return battleshipAutoScrollActive;
    }

    /**
     * Releases the temporary camera freeze used to mirror ROM {@code Scroll_lock}
     * after the frame's normal camera step has had a chance to skip scrolling.
     */
    public void releaseBattleshipScrollLockCamera() {
        if (!battleshipCameraFrozenForScrollLock) {
            return;
        }
        if (battleshipCameraWasFrozen) {
            camera().setFrozen(true);
        } else {
            camera().setScrollLocked(false);
        }
        battleshipCameraFrozenForScrollLock = false;
        battleshipCameraWasFrozen = false;
    }

    /**
     * Forest handoff after the bombship exits and before the small craft clears.
     * In this phase Sonic should stay in front of the decorative forest mask.
     */
    public boolean isBattleshipForestFrontPhaseActive() {
        int cameraX = camera().getX();
        return battleshipSpawned
                && !bossFlag
                && cameraX >= BATTLESHIP_FOREST_FRONT_START_X
                && cameraX <= AIZ_END_BOSS_LOCK_X;
    }

    /**
     * True while the post-bombing ship loop is repeating the forest section
     * ({@code AIZ2_DoShipLoop} with {@code Events_bg+$02 = $46C0}, s3.asm:70569,
     * 70956-70971). ROM state only: the auto-scroll loop is active and its wrap
     * boundary is the post-bombing forest boundary. Drives the FG Plane A {@code $200}
     * horizontal wrap that keeps the looped forest canopy continuous across the
     * camera wrap (the engine analog of the ROM's {@code $200} Plane A nametable
     * ring, since the {@code $200} wrap distance equals the nametable width).
     */
    public boolean isBattleshipForestLoopActive() {
        return battleshipAutoScrollActive
                && battleshipWrapX == BATTLESHIP_WRAP_X_POST_BOMBING;
    }

    /**
     * ROM: AIZ2BGE_Normal one-time BG Y adjustment ($A8 or -$198).
     * Applied when eventsFg5 fires (resize4). The scroll handler adds this to vscrollFactorBG.
     */
    public int getBattleshipBgYOffset() {
        return battleshipBgYOffset;
    }

    // ROM: ScreenShakeArray — signed byte offsets indexed by countdown value (15→0).
    // Amplitude increases then decreases: ±1, ±1, ±2, ±2, ±3, ±3, ±4, ±4, ±5, ±5
    // Used for timed/positive Screen_shake_flag (bomb impacts).
    private static final int[] SCREEN_SHAKE_ARRAY = {
            1, -1, 1, -1, 2, -2, 2, -2, 3, -3, 3, -3, 4, -4, 4, -4, 5, -5, 5, -5
    };

    // ROM: ScreenShakeArray2 — 64-byte pseudo-random offsets (0–3 pixels) indexed
    // by (Level_frame_counter & $3F). Used for constant/negative Screen_shake_flag
    // (e.g. AIZ2 water-level trigger shake, set to -1 by DynamicWaterHeight_AIZ2).
    private static final int[] SCREEN_SHAKE_ARRAY_CONSTANT = {
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3,
            1, 2, 1, 3, 1, 2, 2, 1, 2, 3, 1, 2, 1, 2, 0, 0,
            2, 0, 3, 2, 2, 3, 2, 2, 1, 3, 0, 0, 1, 0, 1, 3
    };

    /**
     * ROM: move.w #$10,(Screen_shake_flag).w — trigger timed screen shake.
     * Called by bomb impact. Countdown applies Y offsets from ScreenShakeArray.
     */
    public void triggerScreenShake(int frames) {
        screenShakeTimer = frames;
    }

    public int getScreenShakeOffsetY() {
        return screenShakeAppliedOffsetY;
    }

    /**
     * Ticks the screen shake. Called each frame from {@link #updateAct2Continuation(int)}.
     * <p>
     * ROM: ShakeScreen_Setup (sonic3k.asm:104183) supports two modes:
     * <ul>
     *   <li>Positive Screen_shake_flag → timed countdown with ScreenShakeArray (bomb impacts)</li>
     *   <li>Negative Screen_shake_flag (-1) → constant shake with ScreenShakeArray2,
     *       cleared externally by Obj_6E6E after 180 frames (water-level trigger)</li>
     * </ul>
     * The water system's shake timer drives the constant mode; the local
     * {@link #screenShakeTimer} drives the timed mode.
     *
     */
    private void tickScreenShake() {
        // AIZ2_ScreenEvent consumes Screen_shake_offset before the background
        // event calls ShakeScreen_Setup to prepare the value for the next frame
        // (sonic3k.asm:104870-104875,105132-105165). Preserve both registers:
        // the scroll handler applies the old value while this method computes
        // the new one.
        screenShakeAppliedOffsetY = screenShakeOffsetY;
        // Constant-mode shake: driven by WaterSystem's shake timer (ROM: Screen_shake_flag = -1).
        // DynamicWaterHeight_AIZ2 sets it to 180; WaterSystem decrements it each frame.
        // ROM: ShakeScreen_Setup loc_4F3FA — uses ScreenShakeArray2[(Level_frame_counter & $3F)]
        WaterSystem ws = waterSystem();
        if (ws != null && ws.getShakeTimer(0, 1) > 0) { // AIZ act 2
            // LevelManager stores the previous completed frame until its later
            // level-update phase. At ScreenEvents the ROM counter has already
            // advanced, so +1 recovers the value ShakeScreen_Setup reads.
            int romFrameCounter = levelManager().getFrameCounter();
            screenShakeOffsetY = SCREEN_SHAKE_ARRAY_CONSTANT[romFrameCounter & 0x3F];
            return;
        }

        // Timed-mode shake: local countdown with ScreenShakeArray (bomb impacts).
        if (screenShakeTimer <= 0) {
            screenShakeOffsetY = 0;
            return;
        }
        screenShakeTimer--;
        screenShakeOffsetY = 0;
        if (screenShakeTimer < SCREEN_SHAKE_ARRAY.length) {
            screenShakeOffsetY = SCREEN_SHAKE_ARRAY[screenShakeTimer];
        }
    }

    /**
     * Returns the smooth (never-wrapping) scroll X for parallax during the bombing sequence.
     * The parallax scroll handler should use this instead of camera.getX() to avoid
     * visible background jumps when the camera wraps back by $200.
     */
    public int getBattleshipSmoothScrollX() {
        return battleshipSmoothScrollX;
    }

    /**
     * Called by {@link AizBattleshipInstance} when the ship has crossed the screen
     * and all bombs have been dropped. Spawns the small Eggman craft.
     */
    public void onBattleshipComplete() {
        // ROM: AIZ2SE_EndRefresh sets Events_bg+$02 = $46C0.
        // This moves the wrap into the forested area right before the boss arena.
        // In the ROM, the HInt screen-split hides the terrain seam at the wrap point;
        // without it there's a slight visual discontinuity, but the loop location is correct.
        battleshipWrapX = BATTLESHIP_WRAP_X_POST_BOMBING;

        var objManager = levelManager().getObjectManager();
        if (objManager != null) {
            AizBossSmallInstance smallBoss = new AizBossSmallInstance();
            objManager.addDynamicObject(smallBoss);

            // ROM: Obj_AIZ2MakeTree - spawner for parallax background trees
            AizBgTreeSpawnerInstance treeSpawner = new AizBgTreeSpawnerInstance();
            objManager.addDynamicObject(treeSpawner);
        }
        LOG.info("AIZ2 battleship: bombing complete, wrap boundary now 0x"
                + Integer.toHexString(battleshipWrapX)
                + ", spawned small boss craft and tree spawner");
    }

    /**
     * Called by {@link AizBossSmallInstance} when the small craft exits the screen.
     * Stops auto-scroll, unlocks camera boundaries, allows the player to proceed
     * to the end boss arena.
     */
    public void onBossSmallComplete() {
        battleshipAutoScrollActive = false;
        levelRepeatOffset = 0;

        // ROM Obj_AIZ2BossSmall loc_5071A clears Scroll_lock before
        // loc_50720 writes Camera_max_X_pos=$6000 (docs/skdisasm/sonic3k.asm:105607-105619).
        releaseBattleshipScrollLockCamera();

        // ROM: Adjust_BGDuringLoop continues to track camera deltas into Events_fg_1
        // after the auto-scroll loop ends, so parallax trees scroll off naturally.
        // Snapshot current camera X as the baseline for post-scroll delta tracking.
        battleshipPostScrollCameraX = camera().getX();

        // Unlock camera: set maxX to end of level / boss arena
        camera().setMaxX((short) BATTLESHIP_END_CAMERA_MAX_X);
        // Release minX — keep it where it is so player can't go backwards,
        // but don't force it to the current scroll position anymore.

        LOG.info("AIZ2 battleship: small boss exited, camera unlocked to maxX=0x"
                + Integer.toHexString(BATTLESHIP_END_CAMERA_MAX_X));
    }

    private void advanceFireRise(boolean allowInitialLerp) {
        boolean fireRiseEnabled = true;
        if (allowInitialLerp && fireRiseSpeed == 0) {
            int delta = (FIRE_BG_TARGET - fireBgCopyFixed) >> FIRE_BG_LERP_SHIFT;
            fireBgCopyFixed += delta;
            fireRiseEnabled = Integer.compareUnsigned(delta, FIRE_BG_LERP_MIN_DELTA) < 0;
            if (fireRiseEnabled) {
                LOG.info("AIZ1 fire: lerp complete, fire rise starting at frame " + fireTransitionFrames);
            }
        }

        if (fireRiseEnabled) {
            fireRiseSpeed = Math.min(FIRE_RISE_MAX_SPEED, fireRiseSpeed + FIRE_RISE_ACCEL);
            fireBgCopyFixed += (fireRiseSpeed << 4);
        }

        fireWavePhase = (fireWavePhase + FIRE_WAVE_PHASE_STEP) & 0xFFFF;
        fireTransitionFrames++;

        if (fireTransitionFrames % 60 == 0) {
            LOG.info("AIZ fire: phase=" + fireSequencePhase
                    + " frame=" + fireTransitionFrames
                    + " bgY=0x" + Integer.toHexString(getFireTransitionBgY())
                    + " speed=0x" + Integer.toHexString(fireRiseSpeed)
                    + " coverPx=" + getFireWallCoverHeightPx(224));
        }
    }

    private void updateFireTransition() {
        if (fireSequencePhase == FireSequencePhase.INACTIVE) {
            if (eventsFg5 && !introNormalRefreshPending) {
                beginFireTransition();
            } else {
                return;
            }
        }

        switch (fireSequencePhase) {
            case AIZ1_FIRE_TRANSITION -> {
                advanceFireRise(true);
                int fireBgY = getFireTransitionBgY();
                if (!fireTransitionMutationRequested && fireBgY >= FIRE_BG_MUTATION_Y) {
                    applyFireTransitionMutation();
                    fireSequencePhase = FireSequencePhase.AIZ1_FIRE_REFRESH;
                    firePhaseFrames = 0;
                } else if (fireTransitionFrames >= FIRE_TRANSITION_FALLBACK_FRAMES) {
                    applyFireTransitionMutation();
                    fireSequencePhase = FireSequencePhase.AIZ1_FIRE_REFRESH;
                    firePhaseFrames = 0;
                }
            }
            case AIZ1_FIRE_REFRESH -> {
                advanceFireRise(false);
                firePhaseFrames++;
                if (firePhaseFrames >= FIRE_REDRAW_FRAMES) {
                    fireSequencePhase = FireSequencePhase.AIZ1_FINISH;
                    firePhaseFrames = 0;
                }
            }
            case AIZ1_FINISH -> {
                // Fire covers the screen while the level transitions behind it.
                // Linger with looping fire, then request transition mid-fire.
                // The fire persists through the reload (deactivateLevelNow=false)
                // and scrolls off in act 2 to reveal the new terrain.
                advanceFireRise(false);
                firePhaseFrames++;
                // ROM: the AIZ2 chunk and block tables become live exactly when
                // their three plain `Queue_Kos` entries drain in Process_Kos_Queue
                // (sonic3k.asm:104678-104688) -- the decompressor writes straight
                // over RAM_start / Block_table, so there is no separate "apply"
                // step and no waiting period of its own. AIZ1BGE_Finish's own wait,
                // `tst.b (Kos_modules_left).w` (sonic3k.asm:104725-104726), gates
                // the LEVEL RELOAD on the two Queue_Kos_Module art jobs only, which
                // is what act2KosArtReady() below covers. The terrain tables land
                // first and independently, and progressively: the tables are half
                // AIZ1 and half AIZ2 for the whole drain. This atomic swap is an
                // approximation of that, and FIRE_TERRAIN_DECOMPRESS_FRAMES picks
                // its instant -- see that constant's javadoc for the measured
                // recorded completions, why handle readiness is later rather than
                // earlier, and what closing this properly would require.
                if (!fireTerrainTablesLoaded
                        && firePhaseFrames >= FIRE_TERRAIN_DECOMPRESS_FRAMES) {
                    S3kSeamlessMutationExecutor.apply(
                            levelManager(),
                            S3kSeamlessMutationExecutor.MUTATION_AIZ1_FIRE_TERRAIN_READY);
                    fireTerrainTablesLoaded = true;
                }
                if (fireOverlayTilesLoaded
                        && act2KosArtReady()
                        && !act2TransitionRequested) {
                    LevelManager levelManager = levelManager();
                    if (!(levelManager.getCurrentLevel() instanceof Sonic3kLevel)) {
                        throw new IllegalStateException(
                                "AIZ2 transition art became ready without a live Sonic3kLevel");
                    }
                    act2TerrainKosQueue.claim(act2BlockHandle);
                    act2TerrainKosQueue.claim(act2PrimaryChunkHandle);
                    act2TerrainKosQueue.claim(act2SecondaryChunkHandle);
                    byte[] primaryTiles8x8 =
                            act2ArtKosQueue.claim(act2PrimaryArtHandle);
                    byte[] secondaryTiles8x8 =
                            act2ArtKosQueue.claim(act2SecondaryArtHandle);
                    S3kSeamlessMutationExecutor.applyAiz1FireTransitionPreparedArt(
                            levelManager, primaryTiles8x8, secondaryTiles8x8);
                    act2BlockHandle = null;
                    act2PrimaryChunkHandle = null;
                    act2SecondaryChunkHandle = null;
                    act2BlockOrdinal = -1;
                    act2PrimaryChunkOrdinal = -1;
                    act2SecondaryChunkOrdinal = -1;
                    act2TerrainKosQueue = null;
                    act2PrimaryArtHandle = null;
                    act2SecondaryArtHandle = null;
                    act2PrimaryArtOrdinal = -1;
                    act2SecondaryArtOrdinal = -1;
                    act2ArtKosQueue = null;
                    requestAct2Transition();
                }
            }
            default -> {
                // Act 2 continuation is advanced by updateAct2Continuation().
            }
        }
    }

    private void updateIntroNormalRefreshFlag(int cameraX) {
        if (!shouldSpawnIntro(0)) {
            return;
        }
        if (!introNormalRefreshPending && !AizPlaneIntroInstance.isMainLevelPhaseActive() && cameraX >= TERRAIN_SWAP_X) {
            eventsFg5 = true;
            introNormalRefreshPending = true;
            LOG.info("AIZ1 intro: Events_fg_5 set for main-level refresh at cameraX=0x"
                    + Integer.toHexString(cameraX));
        }
        if (introNormalRefreshPending && AizPlaneIntroInstance.isMainLevelPhaseActive()) {
            eventsFg5 = false;
            introNormalRefreshPending = false;
            LOG.info("AIZ1 intro: Events_fg_5 cleared after main-level refresh");
        }
    }

    private void beginFireTransition() {
        eventsFg5 = false;
        fireSequencePhase = FireSequencePhase.AIZ1_FIRE_TRANSITION;
        fireBgCopyFixed = FIRE_BG_FIXED_START;
        fireRiseSpeed = 0;
        fireWavePhase = 0;
        fireTransitionFrames = 0;
        firePhaseFrames = 0;
        fireMusicRestoreTimer = FIRE_MUSIC_RESTORE_TIME;
        act2WaitFireDrawActive = false;

        fireTransitionMutationRequested = false;
        fireTerrainTablesLoaded = false;
        act2TransitionRequested = false;
        postFireHazeActive = false;
        // The fire event ends in AIZ1BGE_Finish's Load_Level of act 2 after
        // AIZ1BGE_FireTransition has queued the act 2 Kos work and waited on
        // Kos_modules_left (sonic3k.asm:104664-104746). The host-side act 2
        // level build (decode, art sheets, tilemaps) has no ROM counterpart,
        // so it runs across this event's own rise-and-wait instead of on the
        // reload frame; the reload joins the build and never waits for it.
        LevelManager fireLevelManager = levelManager();
        fireLevelManager.prepareActTransitionLevelLoad(
                fireLevelManager.getCurrentZone(), 1,
                S3kSeamlessMutationExecutor.MUTATION_AIZ1_POST_RELOAD_ACT2);
        // ROM: AIZ1/AIZ2 background fire routines do not write Ctrl_1_locked;
        // player physics keeps running behind the fire curtain.
        // ROM: AIZ1_AIZ2_Transition writes 6 fire words to Normal_palette_line_4+$2
        // at the START of the fire transition. The full fire palette (PalPointers #$0B)
        // is loaded later by the mutation executor when bgY >= $190.
        applyFireTransitionPaletteLine4(levelManager());
        ensureFireOverlayTilesLoaded();
        LOG.info("AIZ1: fire transition started");
    }

    /**
     * Apply fire transition art overlays directly (in-place).
     * ROM: AIZ1BGE_FireTransition at Camera_Y_pos_BG_copy >= $190 queues
     * the AIZ2 art overlays via Queue_Kos / Queue_Kos_Module.  The ROM does
     * this purely through the DMA queue without touching camera bounds.
     * We apply the mutation directly to avoid the side-effects of routing
     * through requestSeamlessTransition(MUTATE_ONLY), which calls
     * restoreCameraBoundsForCurrentLevel() and camera.updatePosition(true),
     * undoing the boss arena camera lock.
     */
    private void applyFireTransitionMutation() {
        fireTransitionMutationRequested = true;
        queueAct2KosArt();
        S3kSeamlessMutationExecutor.apply(
                levelManager(),
                S3kSeamlessMutationExecutor.MUTATION_AIZ1_FIRE_TRANSITION_STAGE);
        LOG.info("AIZ1: applied in-place fire mutation stage (direct)");
    }

    private void queueAct2KosArt() {
        if (act2BlockHandle != null || act2PrimaryArtHandle != null) {
            return;
        }
        try {
            Rom rom = rom();
            int entry = Sonic3kConstants.LEVEL_LOAD_BLOCK_ADDR
                    + Sonic3kConstants.LEVEL_LOAD_BLOCK_ENTRY_SIZE;
            int primarySource = rom.read32BitAddr(entry) & 0x00FF_FFFF;
            int secondarySource = rom.read32BitAddr(entry + 4) & 0x00FF_FFFF;
            int primaryChunkSource =
                    rom.read32BitAddr(entry + 8) & 0x00FF_FFFF;
            int secondaryChunkSource =
                    rom.read32BitAddr(entry + 12) & 0x00FF_FFFF;
            int blockSource =
                    rom.read32BitAddr(entry + 16) & 0x00FF_FFFF;

            act2TerrainKosQueue = directKosQueue();
            act2BlockHandle = act2TerrainKosQueue.queueStandardKos(
                    rom, blockSource, S3kKosRamDestinations.RAM_START);
            act2BlockOrdinal = act2BlockHandle.ordinal();
            act2PrimaryChunkHandle = act2TerrainKosQueue.queueStandardKos(
                    rom, primaryChunkSource, S3kKosRamDestinations.BLOCK_TABLE);
            act2PrimaryChunkOrdinal = act2PrimaryChunkHandle.ordinal();
            act2SecondaryChunkHandle = act2TerrainKosQueue.queueStandardKos(
                    rom, secondaryChunkSource,
                    S3kKosRamDestinations.blockTableOffset(0x0AB8));
            act2SecondaryChunkOrdinal = act2SecondaryChunkHandle.ordinal();

            act2ArtKosQueue =
                    moduleKosQueue();
            act2PrimaryArtHandle =
                    act2ArtKosQueue.queue(rom, primarySource, 0x000);
            act2PrimaryArtOrdinal = act2PrimaryArtHandle.ordinal();
            act2SecondaryArtHandle =
                    act2ArtKosQueue.queue(rom, secondarySource, 0x1FC);
            act2SecondaryArtOrdinal = act2SecondaryArtHandle.ordinal();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Unable to queue AIZ2 KosM transition art", e);
        }
    }

    private boolean act2KosArtReady() {
        return act2BlockHandle != null
                && act2PrimaryChunkHandle != null
                && act2SecondaryChunkHandle != null
                && act2PrimaryArtHandle != null
                && act2SecondaryArtHandle != null
                && act2TerrainKosQueue.isReady(act2BlockHandle)
                && act2TerrainKosQueue.isReady(act2PrimaryChunkHandle)
                && act2TerrainKosQueue.isReady(act2SecondaryChunkHandle)
                && act2ArtKosQueue.isReady(act2PrimaryArtHandle)
                && act2ArtKosQueue.isReady(act2SecondaryArtHandle);
    }

    /**
     * Rebuilds transient queue facades after the session timing ledger and
     * scalar zone-event sidecar have both been restored.
     */
    public void rebindHardwareWorkAfterRewind() {
        var timing = hardwareTiming();
        mainLevelBlockHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                mainLevelBlockOrdinal, "AIZ1 main-level blocks");
        mainLevelBlockKosQueue = mainLevelBlockHandle != null
                ? directKosQueue()
                : null;

        mainLevelArtHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_MODULE_QUEUE,
                mainLevelArtOrdinal, "AIZ1 main-level art");
        mainLevelArtKosQueue = mainLevelArtHandle != null
                ? moduleKosQueue()
                : null;

        battleshipTerrainKosHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                battleshipTerrainKosOrdinal, "AIZ battleship terrain");
        battleshipTerrainArtHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_MODULE_QUEUE,
                battleshipTerrainArtOrdinal, "AIZ battleship terrain");
        battleshipObjectArtHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_MODULE_QUEUE,
                battleshipObjectArtOrdinal, "AIZ battleship object");
        battleshipKosQueue = battleshipTerrainKosHandle != null
                || battleshipTerrainArtHandle != null
                || battleshipObjectArtHandle != null
                        ? moduleKosQueue()
                        : null;

        fireOverlayKosHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_MODULE_QUEUE,
                fireOverlayKosOrdinal, "AIZ fire overlay");
        fireOverlayKosQueue = fireOverlayKosHandle != null
                ? moduleKosQueue()
                : null;

        act2BlockHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                act2BlockOrdinal, "AIZ2 transition blocks");
        act2PrimaryChunkHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                act2PrimaryChunkOrdinal, "AIZ2 transition primary chunks");
        act2SecondaryChunkHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_DECOMPRESSION_QUEUE,
                act2SecondaryChunkOrdinal, "AIZ2 transition secondary chunks");
        act2TerrainKosQueue = act2BlockHandle != null
                || act2PrimaryChunkHandle != null
                || act2SecondaryChunkHandle != null
                        ? directKosQueue()
                        : null;

        act2PrimaryArtHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_MODULE_QUEUE,
                act2PrimaryArtOrdinal, "AIZ2 primary transition art");
        act2SecondaryArtHandle = restoredKosHandle(
                timing, HardwareWorkKind.KOS_MODULE_QUEUE,
                act2SecondaryArtOrdinal, "AIZ2 secondary transition art");
        act2ArtKosQueue = act2PrimaryArtHandle != null
                || act2SecondaryArtHandle != null
                        ? moduleKosQueue()
                        : null;
    }

    /** Drops only derived facades; captured ordinals remain authoritative. */
    public void discardHardwareWorkFacadesAfterRewind() {
        mainLevelBlockKosQueue = null;
        mainLevelBlockHandle = null;
        mainLevelArtKosQueue = null;
        mainLevelArtHandle = null;
        battleshipKosQueue = null;
        battleshipTerrainKosHandle = null;
        battleshipTerrainArtHandle = null;
        battleshipObjectArtHandle = null;
        fireOverlayKosQueue = null;
        fireOverlayKosHandle = null;
        act2TerrainKosQueue = null;
        act2BlockHandle = null;
        act2PrimaryChunkHandle = null;
        act2SecondaryChunkHandle = null;
        act2ArtKosQueue = null;
        act2PrimaryArtHandle = null;
        act2SecondaryArtHandle = null;
    }

    private static HardwareWorkHandle restoredKosHandle(
            com.openggf.game.timing.HardwareTimingService timing,
            HardwareWorkKind kind,
            long ordinal,
            String owner) {
        if (ordinal < 0) {
            return null;
        }
        return timing.pendingHandle(kind, ordinal)
                .orElseThrow(() -> new IllegalStateException(
                        "restored " + owner + " owner cannot find "
                                + kind + " ordinal " + ordinal));
    }

    private void requestAct2Transition() {
        act2TransitionRequested = true;
        eventsFg5 = false;
        bossFlag = false;
        postFireHazeActive = false;
        gameState().setCurrentBossId(0);
        if (!fireTransitionMutationRequested) {
            applyFireTransitionMutation();
        }
        // ROM AIZ1BGE_Finish does NOT touch Camera_Y_pos_BG_copy across the reload:
        // AIZ1_FireRise keeps ramping it through the Kos wait and straight into the
        // AIZ2 background chain (sonic3k.asm:104727-104775). AIZ2BGE_WaitFire's
        // re-seat to $180 + (bgY & $7F) is the only thing that brings it back into
        // the fire zone, so the ramp must stay continuous here — the residue it
        // carries across the reload is exactly what decides the release pass.
        // ROM also seeds Draw_delayed_rowcount = $F immediately before clearing
        // Events_routine_bg (sonic3k.asm:104774-104775); firePhaseFrames carries
        // that counter through AIZ2BGE_FireRedraw.
        pendingFireSequence = new PendingFireSequence(
                FireSequencePhase.AIZ2_FIRE_REDRAW,
                fireBgCopyFixed,
                fireRiseSpeed,
                fireWavePhase,
                fireTransitionFrames,
                AIZ2_FIRE_REDRAW_ROWCOUNT,
                fireMusicRestoreTimer,
                fireTransitionMutationRequested,
                false);
        persistTransitionCheckpoint();
        SessionSaveRequests.requestCurrentSessionSave(SaveReason.PROGRESSION_SAVE);
        LevelManager levelManager = levelManager();
        SeamlessLevelTransitionRequest request =
                SeamlessLevelTransitionRequest.builder(
                                SeamlessLevelTransitionRequest.TransitionType.RELOAD_TARGET_LEVEL)
                        // ROM loads AIZ act 2 resources here, but this is presented as
                        // a seamless continuation (no title card transition). The
                        // target act's LoadEnemyArt call belongs to the later
                        // AIZ2BGE_WaitFire release, after the fire-plane redraw;
                        // keep the act reload itself from admitting that batch early.
                        .targetZoneAct(levelManager.getCurrentZone(), 1)
                        .runtimeArtAdmissionPolicy(
                                RuntimeArtAdmissionPolicy.PRESERVE_CURRENT)
                        // ROM: level stays active during the Kos decompression
                        // wait in AIZ1BGE_Finish — fire keeps rising and rendering.
                        // deactivateLevelNow(true) freezes the game loop, killing all
                        // fire state machine updates.  Keep the level active so the
                        // fire overlay continues rendering through the transition.
                        .deactivateLevelNow(false)
                        .preserveMusic(false)
                        // AIZ1 -> AIZ2 fire transition is the same timed run.
                        // ROM does not clear Timer or Ring_count here; clearing
                        // them inflates the AIZ2 results bonus and delays exit.
                        .preserveLevelGamestate(true)
                        .showInLevelTitleCard(false)
                        .forceAirOnStaleObjectSupportLoss(true)
                        .mutationKey(S3kSeamlessMutationExecutor.MUTATION_AIZ1_POST_RELOAD_ACT2)
                        .playerOffset(-0x2F00, -0x80)
                        .cameraOffset(-0x2F00, -0x80)
                        // ROM: AIZ1BGE_Finish subtracts the same offsets from
                        // Camera_X/Y_pos, writes long #$00100010 at Camera_min_X_pos,
                        // then writes long #$00000260 at Camera_min_Y_pos and word
                        // $260 to Camera_target_max_Y_pos (sonic3k.asm:104747-104762).
                        // That locks camera X at $10 and snaps current maxY to $260;
                        // the camera is not recentered from the player.
                        .preserveOffsetCameraPosition(true)
                        .postTransitionMinX(0x10)
                        .postTransitionMaxX(0x10)
                        .postTransitionMinY(0)
                        .postTransitionMaxY(0x260)
                        .postTransitionMaxYTarget(0x260)
                        .build();
        try {
            // AIZ1BGE_Finish performs Load_Level and the coordinate
            // subtractions inside this background-event dispatch. Deferring
            // through the outer frame driver leaves one unshifted comparison
            // row before the AIZ2 continuation becomes visible.
            levelManager.executeActTransition(request);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to apply AIZ act transition", e);
        }
        LOG.info("AIZ1: requested seamless in-place post-miniboss reload");
    }

    /**
     * ROM: the AIZ2 background continuation falls through to {@code Load_PLC}
     * and {@code jsr (LoadEnemyArt).l} once the fire-plane redraw has drained
     * ({@code docs/skdisasm/sonic3k.asm:105084-105091}). That call site is an
     * ordinary synchronous mid-level {@code LoadEnemyArt}: it runs inside the
     * background-event dispatch, which {@code LevelLoop} reaches
     * ({@code DeformBgLayer}/{@code ScreenEvents}, sonic3k.asm:7896-7898) ahead
     * of the same iteration's {@code Process_Kos_Module_Queue} (7908). The
     * {@code Queue_Kos_Module} calls in {@code LoadEnemyArt}
     * ({@code sonic3k.asm:64281-64313}) therefore happen during this frame's
     * event pass, exactly like {@code HCZGeyser_ReloadEnemyArtAndDelete}
     * ({@code sonic3k.asm:65002-65004}), and the loop-tail module step sees them.
     */
    private void admitAct2EnemyArt() {
        if (!(module().getObjectArtProvider()
                instanceof Sonic3kObjectArtProvider provider)) {
            return;
        }
        provider.reloadEnemyKosArt();
    }

    public int getFireWallCoverHeightPx(int screenHeight) {
        int fireBgY = getFireTransitionBgY();
        // Fire tiles in the BG layout start at Y = FIRE_TILE_START_Y (0x100).
        // The VDP screen shows BG from bgY to bgY + screenHeight.  Fire is visible
        // at the bottom: cover = bgY + screenHeight - FIRE_TILE_START_Y.
        int cover = fireBgY + screenHeight - FIRE_TILE_START_Y;
        if (cover <= 0) {
            return 0;
        }
        return Math.min(cover, screenHeight);
    }

    private void setTransitionControlLock(boolean locked) {
        // Guard: skip when called outside an active gameplay session (e.g. snapshot-restore
        // or unit tests that call initLevel() without live gameplay managers).
        if (!hasRuntime()) {
            return;
        }
        ObjectPlayerQuery playerQuery = new ObjectPlayerQuery(
                () -> camera().getFocusedSprite() instanceof AbstractPlayableSprite player ? player : null,
                this::eventSidekicks);
        for (PlayableEntity participant : playerQuery.playersFor(
                ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (participant instanceof AbstractPlayableSprite player) {
                player.setControlLocked(locked);
            }
        }
    }

    private void ensureFireOverlayTilesLoaded() {
        if (fireOverlayTilesLoaded) {
            return;
        }
        LevelManager levelManager = levelManager();
        if (!(levelManager.getCurrentLevel() instanceof Sonic3kLevel sonic3kLevel)) {
            return;
        }
        try {
            Rom rom = rom();
            if (rom == null) {
                return;
            }
            if (fireOverlayKosHandle == null) {
                fireOverlayKosQueue =
                        moduleKosQueue();
                fireOverlayKosHandle = fireOverlayKosQueue.queue(
                        rom,
                        Sonic3kConstants.ART_KOSM_AIZ1_FIRE_OVERLAY_ADDR,
                        FIRE_OVERLAY_TILE_DEST);
                fireOverlayKosOrdinal = fireOverlayKosHandle.ordinal();
                return;
            }
            if (!fireOverlayKosQueue.isReady(fireOverlayKosHandle)) {
                return;
            }
            byte[] fireOverlay8x8 =
                    fireOverlayKosQueue.claim(fireOverlayKosHandle);
            fireOverlayKosHandle = null;
            fireOverlayKosQueue = null;
            fireOverlayKosOrdinal = -1;
            fireOverlayTileCount =
                    S3kSeamlessMutationExecutor.applyAiz1FireOverlayPreparedArt(
                            levelManager, fireOverlay8x8);
            preparedTransitionArtBridge()
                    .retainAizFireOverlay(fireOverlay8x8);
            applyPlc(FIRE_OVERLAY_PLC);
            // Both writes above replace 8x8 pattern data only; the tilemap cells
            // still reference the same pattern indices, so only the pattern
            // atlas lookup needs refreshing (a full FG+BG rebuild here cost a
            // visible frame hitch at x>=$2E00).
            levelManager.invalidatePatternLookup();
            fireOverlayTilesLoaded = true;
            LOG.info("AIZ1: loaded fire overlay 8x8 tiles at x>=0x2E00");
        } catch (Exception e) {
            LOG.warning("AIZ1: failed to load fire overlay tiles: " + e.getMessage());
        }
    }

    private void restorePendingFireSequenceIfPresent(int act) {
        if (act != 1) {
            return;
        }
        PendingFireSequence pending = pendingFireSequence;
        if (pending == null) {
            // No pending fire sequence: AIZ2 was loaded without a queued fire
            // continuation.  This covers direct AIZ2 entry (level select,
            // starpost respawn from a saved AIZ2 starpost) AND the trace's
            // reload-resume path.  ROM does NOT mark all of these as
            // post-miniboss — only the ones that set Apparent_zone_and_act = $0001
            // (sonic3k.asm:10222 LevelSelect_StartZone, :61760 Load_Starpost_Settings).
            // The miniboss-skip gate now reads LevelManager.getApparentAct(),
            // which mirrors ROM's Apparent_zone_and_act, so we no longer
            // need a heuristic boolean here.  postFireHazeActive stays true
            // because the visual haze is the same in both direct-entry and
            // post-fire-transition cases.
            postFireHazeActive = true;
            return;
        }

        fireSequencePhase = pending.phase();
        fireBgCopyFixed = pending.fireBgCopyFixed();
        fireRiseSpeed = pending.fireRiseSpeed();
        fireWavePhase = pending.fireWavePhase();
        fireTransitionFrames = pending.fireTransitionFrames();
        firePhaseFrames = pending.firePhaseFrames();
        fireMusicRestoreTimer = pending.fireMusicRestoreTimer();
        fireTransitionMutationRequested = pending.mutationRequested();
        act2WaitFireDrawActive = pending.act2WaitFireDrawActive();
        postFireHazeActive = false;
        act2TransitionRequested = false;
        setTransitionControlLock(false);
        // The ROM keeps the fire tiles resident in VDP memory across Load_Level.
        // The engine's host-level recreation reapplies the already-prepared ROM
        // payload in the seamless mutation; it must not enqueue another job.
        fireOverlayTileCount =
                preparedTransitionArtBridge()
                        .aizFireOverlayTileCount();
        if (fireOverlayTileCount == 0) {
            throw new IllegalStateException(
                    "AIZ fire continuation restored without its prepared overlay");
        }
        fireOverlayTilesLoaded = true;
        // Re-apply fire palette after act 2 reload so palette line 3 has fire colors.
        // The level reload loads the normal AIZ2 palette which may not match the
        // fire transition state; PalPointers #$0B + fire line 4 words restore it.
        loadPaletteFromPalPointers(PAL_POINTER_AIZ_FIRE_INDEX);
        applyFireTransitionPaletteLine4(levelManager());
        LOG.info("AIZ2 fake-out: resumed fire continuation phase " + fireSequencePhase);
    }

    static void applyFireTransitionPaletteLine4(LevelManager levelManager) {
        applyPaletteLine4Words(levelManager, FIRE_TRANSITION_LINE4_WORDS);
    }

    static void applyPostFireContinuationPaletteLine4(LevelManager levelManager) {
        applyPaletteLine4Words(levelManager, POST_FIRE_LINE4_WORDS);
    }

    private static void applyPaletteLine4Words(LevelManager levelManager, int[] line4Words) {
        if (levelManager == null || line4Words == null) {
            return;
        }
        Level currentLevel = levelManager.getCurrentLevel();
        if (currentLevel == null) {
            return;
        }
        Palette line4 = currentLevel.getPalette(3);
        if (line4 == null) {
            return;
        }

        byte[] lineData = new byte[Palette.PALETTE_SIZE_IN_ROM];
        for (int i = 0; i < Palette.PALETTE_SIZE; i++) {
            int sega = toSegaColorWord(line4.getColor(i));
            int offset = i * 2;
            lineData[offset] = (byte) ((sega >>> 8) & 0xFF);
            lineData[offset + 1] = (byte) (sega & 0xFF);
        }

        for (int i = 0; i < line4Words.length; i++) {
            int offset = (i + 1) * 2;
            int word = line4Words[i];
            lineData[offset] = (byte) ((word >>> 8) & 0xFF);
            lineData[offset + 1] = (byte) (word & 0xFF);
        }
        S3kPaletteWriteSupport.applyLine(
                paletteRegistryOrNullStatic(),
                currentLevel,
                graphicsStatic(),
                S3kPaletteOwners.AIZ_FIRE_TRANSITION,
                S3kPaletteOwners.PRIORITY_CUTSCENE_OVERRIDE,
                3,
                lineData,
                true);
    }

    private static int toSegaColorWord(Palette.Color color) {
        if (color == null) {
            return 0;
        }
        int r3 = ((color.r & 0xFF) * 7 + 127) / 255;
        int g3 = ((color.g & 0xFF) * 7 + 127) / 255;
        int b3 = ((color.b & 0xFF) * 7 + 127) / 255;
        return ((b3 & 0x7) << 9) | ((g3 & 0x7) << 5) | ((r3 & 0x7) << 1);
    }

    private void persistTransitionCheckpoint() {
        if (!(levelManager().getCheckpointState() instanceof CheckpointState checkpoint)) {
            return;
        }
        Camera cam = camera();
        if (cam.getFocusedSprite() == null) {
            return;
        }
        int x = cam.getFocusedSprite().getCentreX() & 0xFFFF;
        int y = cam.getFocusedSprite().getCentreY() & 0xFFFF;
        checkpoint.saveCheckpoint(0, x, y, false);
    }

    // =========================================================================
    // Rewind accessors (C.4)
    // =========================================================================

    public boolean isIntroSpawned()                         { return introSpawned; }
    public void    setIntroSpawned(boolean v)               { introSpawned = v; }
    public boolean isIntroMinXLocked()                      { return introMinXLocked; }
    public void    setIntroMinXLocked(boolean v)            { introMinXLocked = v; }
    public boolean isIntroSidekickMarkerReleased()          { return introSidekickMarkerReleased; }
    public void    setIntroSidekickMarkerReleased(boolean v){ introSidekickMarkerReleased = v; }
    public boolean isIntroNormalRefreshPending()             { return introNormalRefreshPending; }
    public void    setIntroNormalRefreshPending(boolean v)  { introNormalRefreshPending = v; }
    public boolean isPaletteSwapped()                       { return paletteSwapped; }
    public void    setPaletteSwapped(boolean v)             { paletteSwapped = v; }
    public boolean isBoundariesUnlocked()                   { return boundariesUnlocked; }
    public void    setBoundariesUnlocked(boolean v)         { boundariesUnlocked = v; }
    public boolean isFireMinXLockReached()                  { return fireMinXLockReached; }
    public void    setFireMinXLockReached(boolean v)        { fireMinXLockReached = v; }
    public int     getAppliedTreeRevealChunkCopiesMask()    { return appliedTreeRevealChunkCopiesMask; }
    public void    setAppliedTreeRevealChunkCopiesMask(int v){ appliedTreeRevealChunkCopiesMask = v; }
    public int     getAiz2ResizeRoutine()                   { return aiz2ResizeRoutine; }
    public void    setAiz2ResizeRoutine(int v)              { aiz2ResizeRoutine = v; }
    public boolean isMinibossSpawned()                      { return minibossSpawned; }
    public void    setMinibossSpawned(boolean v)            { minibossSpawned = v; }
    public boolean isEventsFg4Raw()                         { return eventsFg4; }
    public void    setEventsFg4Raw(boolean v)               { eventsFg4 = v; }
    public boolean isBattleshipAutoScrollActiveRaw()        { return battleshipAutoScrollActive; }
    public void    setBattleshipAutoScrollActiveRaw(boolean v){ battleshipAutoScrollActive = v; }
    public boolean isBattleshipSpawned()                    { return battleshipSpawned; }
    public void    setBattleshipSpawned(boolean v)          { battleshipSpawned = v; }
    public boolean isEndBossSpawned()                       { return endBossSpawned; }
    public void    setEndBossSpawned(boolean v)             { endBossSpawned = v; }
    public boolean isBattleshipTerrainLoaded()              { return battleshipTerrainLoaded; }
    public void    setBattleshipTerrainLoaded(boolean v)    { battleshipTerrainLoaded = v; }
    public int     getBattleshipWrapX()                     { return battleshipWrapX; }
    public void    setBattleshipWrapX(int v)                { battleshipWrapX = v; }
    public int     getScreenShakeTimer()                    { return screenShakeTimer; }
    public void    setScreenShakeTimer(int v)               { screenShakeTimer = v; }
    public int     getLevelRepeatOffsetRaw()                { return levelRepeatOffset; }
    public void    setLevelRepeatOffsetRaw(int v)           { levelRepeatOffset = v; }
    public int     getBattleshipBgYOffsetRaw()              { return battleshipBgYOffset; }
    public void    setBattleshipBgYOffsetRaw(int v)         { battleshipBgYOffset = v; }
    public int     getBattleshipSmoothScrollXRaw()          { return battleshipSmoothScrollX; }
    public void    setBattleshipSmoothScrollXRaw(int v)     { battleshipSmoothScrollX = v; }
    public int     getBattleshipPostScrollCameraX()         { return battleshipPostScrollCameraX; }
    public void    setBattleshipPostScrollCameraX(int v)    { battleshipPostScrollCameraX = v; }
    public int     getScreenShakeOffsetYRaw()               { return screenShakeOffsetY; }
    public void    setScreenShakeOffsetYRaw(int v)          { screenShakeOffsetY = v; }
    public int     getScreenShakeAppliedOffsetYRaw()        { return screenShakeAppliedOffsetY; }
    public void    setScreenShakeAppliedOffsetYRaw(int v)   { screenShakeAppliedOffsetY = v; }
    public boolean isAct2TransitionRequestedRaw()           { return act2TransitionRequested; }
    public void    setAct2TransitionRequestedRaw(boolean v) { act2TransitionRequested = v; }
    public boolean isFireTransitionMutationRequested()      { return fireTransitionMutationRequested; }
    public void    setFireTransitionMutationRequested(boolean v){ fireTransitionMutationRequested = v; }
    public boolean isPostFireHazeActiveRaw()                { return postFireHazeActive; }
    public void    setPostFireHazeActiveRaw(boolean v)      { postFireHazeActive = v; }
    public boolean isFireOverlayTilesLoaded()               { return fireOverlayTilesLoaded; }
    public void    setFireOverlayTilesLoaded(boolean v)     { fireOverlayTilesLoaded = v; }
    public int     getFireBgCopyFixed()                     { return fireBgCopyFixed; }
    public void    setFireBgCopyFixed(int v)                { fireBgCopyFixed = v; }
    public int     getFireRiseSpeed()                       { return fireRiseSpeed; }
    public void    setFireRiseSpeed(int v)                  { fireRiseSpeed = v; }
    public int     getFireWavePhase()                       { return fireWavePhase; }
    public void    setFireWavePhase(int v)                  { fireWavePhase = v; }
    public int     getFireTransitionFrames()                { return fireTransitionFrames; }
    public void    setFireTransitionFrames(int v)           { fireTransitionFrames = v; }
    public int     getFirePhaseFrames()                     { return firePhaseFrames; }
    public void    setFirePhaseFrames(int v)                { firePhaseFrames = v; }
    public boolean isAct2WaitFireDrawActive()               { return act2WaitFireDrawActive; }
    public void    setAct2WaitFireDrawActive(boolean v)     { act2WaitFireDrawActive = v; }
    public int     getFireSequencePhaseOrdinal()            { return fireSequencePhase.ordinal(); }
    public void    setFireSequencePhaseOrdinal(int ordinal) {
        FireSequencePhase[] values = FireSequencePhase.values();
        fireSequencePhase = (ordinal >= 0 && ordinal < values.length)
                ? values[ordinal] : FireSequencePhase.INACTIVE;
    }
    public int     getFireOverlayTileCount()                { return fireOverlayTileCount; }
    public void    setFireOverlayTileCount(int v)           { fireOverlayTileCount = v; }
}
