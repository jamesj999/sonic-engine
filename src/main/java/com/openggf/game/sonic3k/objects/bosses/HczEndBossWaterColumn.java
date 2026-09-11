package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RomObjectCodePointerProvider;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import java.util.List;
import java.util.logging.Logger;

/**
 * HCZ end boss water column platform (ROM: loc_6B26E).
 *
 * <p>Rises from the water surface to the turbine, acts as a solid-top platform,
 * and renders both the platform frame and the whirlpool/spray body.
 *
 * <h3>ROM state machine (6 routines, stride 2):</h3>
 * <ol start="0">
 *   <li>INIT (0, loc_6B2AA): place at Water_level - 8, set animation to byte_6BE0C
 *       (spin-up), spawn water surface children and bubble particles.</li>
 *   <li>ANIMATE (2, loc_6B2F2): Animate_Raw on byte_6BE0C. On completion ($F4),
 *       callback loc_6B2F8 sets routine 4, y_vel=-$100, animation byte_6BE15,
 *       and spawns spray child (loc_6B3DE).</li>
 *   <li>RISE (4, loc_6B31E): MoveSprite2 + Animate_Raw + sub_6BC8A height check.
 *       When segment_count >= 5, callback loc_6B32E sets routine 6 (HOLD).</li>
 *   <li>HOLD (6, loc_6B336): Animate_Raw, wait for boss bit 3 of $38 to clear
 *       (propellerActive goes false). Then transition to DESCEND.</li>
 *   <li>DESCEND (8, loc_6B31E): same handler as RISE, y_vel=$80, x_vel=$80
 *       (sign matches boss direction). When y_pos crosses $3A (water level),
 *       callback loc_6B37A sets up PLATFORM phase.</li>
 *   <li>PLATFORM (10, loc_6B394): SolidObjectTop + Animate_Raw + Obj_Wait ($F frames),
 *       then callback loc_6B3BC displaces player off and deletes sprite.</li>
 *   <li>DETACHED_FALL (engine parity for {@code sub_6BC42 -> loc_6B3C8}): when the
 *       boss defeat bit is set, the column detaches, displaces riders, falls with
 *       {@code y_vel=$100}, and deletes once it drops past the stored water base.</li>
 * </ol>
 *
 * <h3>sub_6BC8A — height tracking:</h3>
 * <pre>
 *   segment_count = ($3A - y_pos) AND $F0, >> 4
 *   Store in $39 (read by spray child for visual height)
 *   If rising and segment_count >= 5 → fire callback
 *   If y_pos > $3A (overshot) → fire callback
 * </pre>
 *
 * <h3>Rendering:</h3>
 * The platform object draws ONE mapping frame via Draw_Sprite. The growing
 * whirlpool body is drawn by a separate spray child (loc_6B3DE) which selects
 * progressively taller mapping frames based on the parent's $39 segment count.
 * The spray retains its own object-loop slot, while this object renders that
 * child's visual inline using the ROM's frame tables and Y-offset tables.
 *
 * <h3>Animation scripts (Animate_Raw format: delay, frames..., command):</h3>
 * <ul>
 *   <li>byte_6BE0C (spin-up): 3, $17,$17,$22,$16,$21,$15,$20, $F4</li>
 *   <li>byte_6BE15 (rise/hold): 3, $15,$20, $FC (loop)</li>
 *   <li>Spray animations per segment count 0-5 (sub_6B916 / off_6B954):</li>
 *   <li>  0: byte_6BE19: 3, $0D,$0F,$11, $FC</li>
 *   <li>  1: byte_6BE1E: 3, $24,$25,$26, $FC</li>
 *   <li>  2: byte_6BE23: 3, $27,$28,$29, $FC</li>
 *   <li>  3: byte_6BE28: 3, $2A,$2B,$2C, $FC</li>
 *   <li>  4: byte_6BE2D: 3, $2D,$2E,$2F, $FC</li>
 *   <li>  5: byte_6BE2D: 3, $2D,$2E,$2F, $FC</li>
 * </ul>
 *
 * <p>Solid top: ROM uses SolidObjectTop with d1=0x1F, d2=0x0C, d3=0x0C.
 *
 * <p>Suction (sub_6B9AC): horizontal push at 0x20000 subpixels when player is
 * below water level + 8 and near column X. Applied by spray child, replicated here.
 *
 * <p>Grab (sub_6B9E2): player within Y-zone table (word_6BAC2) and 32px H —
 * lock object_control, forced float animation.
 */
public class HczEndBossWaterColumn extends AbstractBossChild implements SolidObjectProvider, RewindRecreatable, RomObjectCodePointerProvider {

    /**
     * Word 0 of this object's S3K SST holds its live ROM code pointer.
     * ROM {@code loc_6B26E} is the routine this object runs, i.e. address
     * {@code $0006B26E} (docs/skdisasm/sonic3k.asm).
     * Its whole code block lies in one bank, so the HIGH word that
     * {@code sub_13EFC} latches into {@code Tails_CPU_interact} and compares
     * on the next off-screen on-object frame is {@code $0006}
     * (docs/skdisasm/sonic3k.asm:26816-26843).
     */
    @Override
    public int romObjectCodePointerHighWord() {
        return 0x0006;
    }


    private static final Logger LOG = Logger.getLogger(HczEndBossWaterColumn.class.getName());

    // =========================================================================
    // State machine routines (ROM stride 2)
    // =========================================================================
    private static final int ROUTINE_INIT     = 0;
    private static final int ROUTINE_ANIMATE  = 2;
    private static final int ROUTINE_RISE     = 4;
    private static final int ROUTINE_HOLD     = 6;
    private static final int ROUTINE_DESCEND  = 8;
    private static final int ROUTINE_PLATFORM = 10;
    private static final int ROUTINE_DETACHED_FALL = 12;

    // =========================================================================
    // Physics (ROM 8.8 fixed-point: 0x100 = 1 pixel/frame)
    // =========================================================================
    private static final int RISE_YVEL    = -0x100;   // routine 4 y_vel
    private static final int DESCEND_YVEL =  0x80;    // routine 8 y_vel
    private static final int DESCEND_XVEL =  0x80;    // routine 8 x_vel (sign from boss)

    // =========================================================================
    // Solid platform (ROM: d1=0x1F, d2=0x0C, d3=0x0C)
    // =========================================================================
    private static final int SOLID_HALF_WIDTH  = 0x1F;
    private static final int SOLID_HALF_HEIGHT = 0x0C;
    private static final SolidObjectParams SOLID_PARAMS =
            SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_HALF_HEIGHT, SOLID_HALF_HEIGHT);

    // =========================================================================
    // Spin-up animation: byte_6BE0C
    // Delay=3 (4 ticks per frame), 7 frames, end command $F4
    // =========================================================================
    private static final int[] SPINUP_FRAMES = {0x17, 0x17, 0x22, 0x16, 0x21, 0x15, 0x20};
    private static final int SPINUP_DELAY = 3;

    // =========================================================================
    // Rise/hold animation: byte_6BE15
    // Delay=3, frames $15,$20, loop $FC
    // =========================================================================
    private static final int[] COLUMN_FRAMES = {0x15, 0x20};
    private static final int COLUMN_ANIM_DELAY = 3;

    // =========================================================================
    // Spray animation tables (sub_6B916 / off_6B954)
    // Indexed by segment count (0-5). Each is a looping 3-frame animation.
    // =========================================================================
    private static final int[][] SPRAY_FRAMES = {
        {0x0D, 0x0F, 0x11},   // segment 0: byte_6BE19
        {0x24, 0x25, 0x26},   // segment 1: byte_6BE1E
        {0x27, 0x28, 0x29},   // segment 2: byte_6BE23
        {0x2A, 0x2B, 0x2C},   // segment 3: byte_6BE28
        {0x2D, 0x2E, 0x2F},   // segment 4: byte_6BE2D
        {0x2D, 0x2E, 0x2F},   // segment 5: byte_6BE2D (same as 4)
    };
    private static final int SPRAY_ANIM_DELAY = 3;

    // =========================================================================
    // Spray Y offsets from water level (byte_6B948, read at +1 offset)
    // Indexed by segment count (0-5)
    // =========================================================================
    private static final int[] SPRAY_Y_OFFSETS = {
        -8,     // segment 0
        -0x10,  // segment 1
        -0x18,  // segment 2
        -0x20,  // segment 3
        -0x28,  // segment 4
        -0x28,  // segment 5
    };

    // =========================================================================
    // Grab zones per segment count (word_6BAC2, 4 bytes each)
    // Each entry: {y_top_offset, y_height}, relative to spray y_pos
    // =========================================================================
    private static final int[][] GRAB_ZONES = {
        {-0x18, 0x48},  // segment 0
        {-0x20, 0x58},  // segment 1
        {-0x28, 0x68},  // segment 2
        {-0x30, 0x78},  // segment 3
        {-0x38, 0x88},  // segment 4
        {-0x48, 0x88},  // segment 5
    };
    private static final int GRAB_H_DIST = 0x10;  // 16 px half-width (ROM: addi #$10, cmpi #$20)

    // =========================================================================
    // Platform phase wait timer (ROM: $2E = $F)
    // =========================================================================
    private static final int PLATFORM_WAIT = 0x0F;

    // =========================================================================
    // Height check: transition at 5 segments (sub_6BC8A)
    // =========================================================================
    private static final int RISE_SEGMENT_THRESHOLD = 5;
    /** Maximum segment count (ROM table off_6B954 has 6 entries, indices 0-5). */
    private static final int MAX_SEGMENT_COUNT = 5;

    // =========================================================================
    // Carry constants (sub_6BA06 / loc_6BA6C)
    // =========================================================================
    /** Horizontal range for carry continuation: ±$12 (18 px). ROM: cmpi #-$12 / cmpi #$12 */
    private static final int CARRY_H_RANGE = 0x12;
    /** Horizontal centering force applied to player x_vel each frame. ROM: move.w #$80,d2 */
    private static final short CARRY_H_FORCE = 0x80;

    // =========================================================================
    // Floor sentinel
    // =========================================================================
    private static final int FLOOR_Y_LIMIT = 0x1000;

    // =========================================================================
    // Instance state
    // =========================================================================
    private final HczEndBossInstance boss;
    private final HczEndBossTurbine turbine;

    private int routine;

    /** Fixed-point Y (16:8). */
    private int yFixed;
    /** Fixed-point X (16:8) — used during DESCEND for x_vel tracking. */
    private int xFixed;
    /** X velocity in 8.8 fixed-point — used during DESCEND. */
    private int xVel;
    /** Y velocity in 8.8 fixed-point. */
    private int yVel;

    /** ROM $3A: stored water level Y at init (base for height calculation). */
    private int waterBaseY;

    /** ROM $39: current segment count (height / 16). */
    private int segmentCount;
    /** Previous segment count (for spray animation reset). */
    private int prevSegmentCount = -1;

    // -- Column animation state (byte_6BE15: frames $15,$20 looping) --
    private int columnAnimIndex;
    private int columnAnimTimer;

    // -- Spin-up animation state (byte_6BE0C) --
    private int spinupIndex;
    private int spinupTimer;

    // -- Spray animation state (inline — replaces spray child loc_6B3DE) --
    private int sprayAnimIndex;
    private int sprayAnimTimer;
    /**
     * ROM's separately allocated loc_6B3DE spray slot runs after the column
     * parent. When loc_6B34A selects DESCEND, that later slot still completes
     * its final loc_6B410 suction/grab dispatch before observing parent bit 3.
     */
    private boolean pendingSprayTailInteraction;

    /** Platform wait counter for ROUTINE_PLATFORM (ROM $2E). */
    private int platformWait;

    /** Whether solid platform is active for this frame. */
    private boolean solidActive;

    /** ROM $42: per-player grab flag for player 1. True if this column has grabbed player 1. */
    private boolean player1Grabbed;
    /** ROM $43: per-player grab flag for player 2 / sidekick. */
    private boolean player2Grabbed;

    // =========================================================================
    // Constructor
    // =========================================================================

    public HczEndBossWaterColumn(HczEndBossInstance boss, HczEndBossTurbine turbine) {
        super(boss, "HCZEndBossWaterColumn", 3, 0);
        this.boss = boss;
        this.turbine = turbine;
        this.routine = ROUTINE_INIT;
        this.solidActive = false;
        this.player1Grabbed = false;
        this.player2Grabbed = false;
    }

    private HczEndBossWaterColumn(ObjectSpawn spawn, HczEndBossInstance boss) {
        this(boss, null);
    }

    @Override
    public HczEndBossWaterColumn recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossInstance restoredBoss = HczEndBossRewindLinks.nearestBoss(ctx);
        HczEndBossTurbine restoredTurbine = HczEndBossRewindLinks.nearestTurbine(ctx);
        if (restoredBoss == null || restoredTurbine == null) {
            return null;
        }
        HczEndBossWaterColumn restored = new HczEndBossWaterColumn(restoredBoss, restoredTurbine);
        restoredTurbine.attachWaterColumnForRewind(restored);
        return restored;
    }

    @Override
    protected void recreateConstructionChildrenForRewind() {
        // These child slots are produced after the boss root has entered the
        // object loop. Recreate candidates while the rewind pool is active so
        // captured dynamic entries adopt their exact identity and slot.
        spawnWaterChildSlots();
    }

    // =========================================================================
    // Main update
    // =========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) {
            return;
        }

        if (boss.isDefeatSignal() && routine != ROUTINE_DETACHED_FALL) {
            beginDefeatDetach(player);
        }

        switch (routine) {
            case ROUTINE_INIT     -> updateInit();
            case ROUTINE_ANIMATE  -> updateAnimate();
            case ROUTINE_RISE     -> updateRiseDescend(player);
            case ROUTINE_HOLD     -> updateHold(player);
            case ROUTINE_DESCEND  -> updateRiseDescend(player);
            case ROUTINE_PLATFORM -> updatePlatform(player);
            case ROUTINE_DETACHED_FALL -> updateDetachedFall();
            default               -> { }
        }
    }

    // =========================================================================
    // Routine 0: INIT (loc_6B2AA)
    // =========================================================================

    /**
     * ROM loc_6B2AA: SetUp_ObjAttributes2 with word_6BD3E, place at Water_level - 8,
     * store water Y in $3A, set spin-up animation (byte_6BE0C) with callback to
     * loc_6B2F8, spawn 2 water surface children and 20 bubble particles.
     * Then advance to routine 2 (ANIMATE).
     */
    private void updateInit() {
        int waterY = getWaterLevelY();
        // ROM: move.w (Water_level).w,d0; subq.w #8,d0; move.w d0,y_pos; move.w d0,$3A
        currentX = turbine.getCurrentX();
        currentY = waterY - 8;
        yFixed = currentY << 8;
        waterBaseY = currentY;  // $3A — base for height calculation
        xFixed = currentX << 8;
        xVel = 0;
        yVel = 0;
        segmentCount = 0;
        prevSegmentCount = -1;

        solidActive = false;

        // Init spin-up animation (byte_6BE0C)
        spinupIndex = 0;
        // The setup frame already publishes byte_6BE0C's initial mapping.
        // Seed the folded countdown at delay-1 so its pre-decrement cadence
        // reaches the $F4 callback on the same dispatch as Animate_Raw.
        spinupTimer = SPINUP_DELAY - 1;

        // Init column animation (will be used after spin-up)
        columnAnimIndex = 0;
        columnAnimTimer = COLUMN_ANIM_DELAY;

        // Init spray animation
        sprayAnimIndex = 0;
        sprayAnimTimer = SPRAY_ANIM_DELAY;

        updateDynamicSpawn();

        // ChildObjDat_6BDCA / ChildObjDat_6BDE6 allocate real SST children.
        // Their visuals are currently folded into the column renderer, but
        // their Process_Sprites slots and RNG consumption remain gameplay state.
        spawnWaterSurfaceAndBubbleChildren();

        // Transition to ANIMATE immediately
        routine = ROUTINE_ANIMATE;
        LOG.fine("HCZ Water Column: INIT, waterBaseY=" + waterBaseY);
    }

    // =========================================================================
    // Routine 2: ANIMATE — spin-up (loc_6B2F2)
    // =========================================================================

    /**
     * ROM loc_6B2F2: just Animate_Raw on byte_6BE0C.
     * Animate_Raw decrements the delay counter. When it hits 0, advances the
     * frame index and resets delay. When the $F4 end command is reached,
     * the callback at $34 (loc_6B2F8) fires.
     */
    private void updateAnimate() {
        // Track turbine X during spin-up
        currentX = turbine.getCurrentX();

        // Animate_Raw tick
        spinupTimer--;
        if (spinupTimer < 0) {
            spinupTimer = SPINUP_DELAY;
            spinupIndex++;
            if (spinupIndex >= SPINUP_FRAMES.length) {
                // $F4 end command → callback loc_6B2F8
                onSpinupComplete();
                return;
            }
        }
    }

    /**
     * ROM loc_6B2F8: callback when spin-up animation completes.
     * Sets routine 4, animation to byte_6BE15, y_vel = -$100,
     * callback to loc_6B32E (→ routine 6), spawns spray child.
     */
    private void onSpinupComplete() {
        routine = ROUTINE_RISE;
        yVel = RISE_YVEL;
        yFixed = currentY << 8;

        // loc_6B2F8 creates loc_6B3DE as a separately executed child slot.
        spawnWaterSprayChild();

        // Init column animation (byte_6BE15: frames $15, $20 looping)
        columnAnimIndex = 0;
        columnAnimTimer = COLUMN_ANIM_DELAY;

        LOG.fine("HCZ Water Column: spin-up complete, entering RISE");
    }

    private void spawnWaterChildSlots() {
        spawnWaterSurfaceAndBubbleChildren();
        spawnWaterSprayChild();
    }

    private void spawnWaterSurfaceAndBubbleChildren() {
        spawnChild(() -> new HczEndBossWaterSurfaceChild(boss, -4));
        spawnChild(() -> new HczEndBossWaterSurfaceChild(boss, 4));
        for (int i = 0; i < 0x14; i++) {
            spawnChild(() -> new HczEndBossBubbleParticle(boss));
        }
    }

    private void spawnWaterSprayChild() {
        spawnChild(() -> new HczEndBossWaterSprayChild(boss));
    }

    // =========================================================================
    // Routines 4 & 8: RISE / DESCEND (loc_6B31E — shared handler)
    // =========================================================================

    /**
     * ROM loc_6B31E: MoveSprite2 (apply velocities), Animate_Raw, sub_6BC8A.
     * Used for both RISE (routine 4) and DESCEND (routine 8).
     */
    private void updateRiseDescend(PlayableEntity player) {
        boolean risingAtEntry = routine == ROUTINE_RISE;
        // Track turbine X during rise
        if (risingAtEntry) {
            currentX = turbine.getCurrentX();
            xFixed = currentX << 8;
        }

        // MoveSprite2: apply velocities
        yFixed += yVel;
        currentY = yFixed >> 8;
        xFixed += xVel;
        currentX = xFixed >> 8;
        updateDynamicSpawn();

        // Animate_Raw on byte_6BE15 (column platform animation)
        tickColumnAnimation();

        // sub_6BC8A: height tracking and transition check
        doHeightCheck(player);

        // Solid platform active during rise/descend
        solidActive = true;

        // Suction (replicated from spray child sub_6B9AC + sub_6B9E2)
        if (risingAtEntry || pendingSprayTailInteraction) {
            applySuction(player);
            pendingSprayTailInteraction = false;
        }
    }

    /**
     * ROM sub_6BC8A: calculate segment count from height, store in $39.
     * <pre>
     *   d0 = $3A - y_pos
     *   if d0 < 0 (bcs): fire callback ($34)
     *   d0 = (d0 AND $F0) >> 4  — segment count
     *   store $39
     *   if y_vel >= 0: return (descending — no threshold check)
     *   if segment_count >= 5: fire callback
     * </pre>
     */
    private void doHeightCheck(PlayableEntity player) {
        int heightDiff = waterBaseY - currentY;
        if (heightDiff < 0) {
            // y_pos went below water base — fire callback
            fireHeightCallback(player);
            return;
        }

        segmentCount = (heightDiff & 0xF0) >> 4;
        // Cap to valid range — ROM's off_6B954 spray animation table has 6
        // entries (indices 0-5).  With y_vel=-$100 the ROM never overshoots,
        // but our frame timing can occasionally push segmentCount to 6+.
        if (segmentCount > MAX_SEGMENT_COUNT) {
            segmentCount = MAX_SEGMENT_COUNT;
        }

        // Update spray animation if segment count changed (ROM: sub_6B916
        // resets anim_frame_timer and changes animation pointer)
        if (segmentCount != prevSegmentCount) {
            prevSegmentCount = segmentCount;
            sprayAnimTimer = SPRAY_ANIM_DELAY;
            sprayAnimIndex = 0;
        }

        if (yVel >= 0) {
            // Descending — no threshold check
            return;
        }

        // Rising: check if reached 5 segments
        if (segmentCount >= RISE_SEGMENT_THRESHOLD) {
            fireHeightCallback(player);
        }
    }

    /**
     * Fire the appropriate callback based on current routine.
     * RISE → HOLD (loc_6B32E), DESCEND → PLATFORM (loc_6B37A).
     */
    private void fireHeightCallback(PlayableEntity player) {
        if (routine == ROUTINE_RISE) {
            // loc_6B32E: set routine 6 (HOLD)
            routine = ROUTINE_HOLD;
            yVel = 0;
            LOG.fine("HCZ Water Column: reached 5 segments, entering HOLD");
        } else if (routine == ROUTINE_DESCEND) {
            // loc_6B37A: set up PLATFORM phase
            routine = ROUTINE_PLATFORM;
            platformWait = PLATFORM_WAIT;
            xVel = 0;
            yVel = 0;
            currentY = waterBaseY;
            yFixed = currentY << 8;
            solidActive = true;
            LOG.fine("HCZ Water Column: reached water, entering PLATFORM");
        } else if (routine == ROUTINE_DETACHED_FALL) {
            solidActive = false;
            setDestroyed(true);
        }
    }

    // =========================================================================
    // Routine 6: HOLD (loc_6B336)
    // =========================================================================

    /**
     * ROM loc_6B336: Animate_Raw. Check boss $38 bit 3 (propellerActive).
     * If set → hold. If clear → transition to DESCEND (loc_6B34A).
     */
    private void updateHold(PlayableEntity player) {
        // Track turbine position
        currentX = turbine.getCurrentX();
        xFixed = currentX << 8;
        updateDynamicSpawn();

        // Animate column frames
        tickColumnAnimation();

        solidActive = true;

        // Suction/grab during hold
        applySuction(player);

        // Check boss propeller state (ROM: btst #3,$38(a1))
        if (!boss.isPropellerActive()) {
            // loc_6B34A: transition to DESCEND
            routine = ROUTINE_DESCEND;
            pendingSprayTailInteraction = true;

            // ROM: bset #3,$38(a0) — set own flag (not used elsewhere)
            // ROM: y_vel = $80
            yVel = DESCEND_YVEL;

            // ROM: x_vel = $80, negate if boss moving left
            xVel = DESCEND_XVEL;
            if (boss.getState().xVel < 0) {
                xVel = -xVel;
            }

            // ROM loc_6BB1E: release any grabbed players on descent start
            releaseAllGrabbedPlayers(player);

            LOG.fine("HCZ Water Column: propeller stopped, entering DESCEND");
        }
    }

    /** ROM parent flag bit 3 observed by loc_6B598 and loc_6B3DE. */
    boolean isWaterChildShutdownActive() {
        return routine >= ROUTINE_DESCEND;
    }

    // =========================================================================
    // Routine 10: PLATFORM (loc_6B394)
    // =========================================================================

    /**
     * ROM loc_6B394: SolidObjectTop + Animate_Raw + Obj_Wait.
     * Wait timer ($2E) = $F (15 frames). On expiry, callback loc_6B3BC
     * displaces player off object and deletes.
     */
    private void updatePlatform(PlayableEntity player) {
        solidActive = true;
        tickColumnAnimation();

        platformWait--;
        if (platformWait <= 0) {
            // loc_6B3BC: Displace_PlayerOffObject + Go_Delete_Sprite_2
            solidActive = false;
            releaseAllGrabbedPlayers(player);
            setDestroyed(true);
        }
    }

    /**
     * ROM sub_6BC42: on boss defeat, detach into loc_6B3C8, apply y_vel=$100,
     * clear riders, and delete once sub_6BC8A detects the column has dropped
     * below its stored water-base Y.
     */
    private void beginDefeatDetach(PlayableEntity player) {
        routine = ROUTINE_DETACHED_FALL;
        solidActive = false;
        xVel = 0;
        yVel = 0x100;
        xFixed = currentX << 8;
        yFixed = currentY << 8;
        releaseAllGrabbedPlayers(player);
        displaceRidingPlayers(player);
    }

    private void updateDetachedFall() {
        solidActive = false;
        yFixed += yVel;
        currentY = yFixed >> 8;
        xFixed += xVel;
        currentX = xFixed >> 8;
        updateDynamicSpawn();
        tickColumnAnimation();
        doHeightCheck(null);
    }

    // =========================================================================
    // Column animation (byte_6BE15: delay=3, frames $15,$20, $FC loop)
    // =========================================================================

    private void tickColumnAnimation() {
        columnAnimTimer--;
        if (columnAnimTimer < 0) {
            columnAnimTimer = COLUMN_ANIM_DELAY;
            columnAnimIndex = (columnAnimIndex + 1) % COLUMN_FRAMES.length;
        }
    }

    // =========================================================================
    // Spray animation (per-segment-count, all delay=3, 3 frames, $FC loop)
    // =========================================================================

    private void tickSprayAnimation() {
        sprayAnimTimer--;
        if (sprayAnimTimer < 0) {
            sprayAnimTimer = SPRAY_ANIM_DELAY;
            int maxFrames = getSprayFrameCount();
            sprayAnimIndex = (sprayAnimIndex + 1) % maxFrames;
        }
    }

    private int getSprayFrameCount() {
        int idx = Math.min(segmentCount, SPRAY_FRAMES.length - 1);
        return SPRAY_FRAMES[idx].length;
    }

    // =========================================================================
    // Suction (sub_6B9AC) + Grab/Carry (sub_6B9E2 + sub_6BA06)
    // =========================================================================

    /**
     * ROM: the spray child calls sub_6B9E2 (grab/carry), sub_6B9AC (horizontal
     * suction), and sub_6B916 (animation update) each frame.
     * We replicate the grab/carry and suction here inline.
     */
    private void applySuction(PlayableEntity player) {
        // Spray Y position for grab zone calculations
        int sprayY = getSprayY();
        // sub_6B9AC initializes d2 once, then sub_6B9C8 mutates and reuses it
        // across the native P1 -> P2 calls. Two players on the same side of
        // the column therefore receive opposite pushes.
        int suctionDelta = 2;
        // sub_6B9E2 also shares a1 between calls. Reaching loc_6BA6C consumes
        // (a1)+, so P2 can observe the next word in word_6BAC2 after P1 carry.
        int grabZoneWordOffset = 0;

        if (player instanceof AbstractPlayableSprite sprite) {
            if (applyGrabAndCarry(sprite, true, sprayY, grabZoneWordOffset)) {
                grabZoneWordOffset++;
            }
            suctionDelta = applySuctionTo(sprite, suctionDelta);
        }
        for (PlayableEntity candidate : nativeParticipants(player)) {
            if (candidate != player && candidate instanceof AbstractPlayableSprite sprite) {
                if (applyGrabAndCarry(sprite, false, sprayY, grabZoneWordOffset)) {
                    grabZoneWordOffset++;
                }
                suctionDelta = applySuctionTo(sprite, suctionDelta);
            }
        }
    }

    /**
     * sub_6B9AC + sub_6B9C8: horizontal push toward column.
     * ROM: $20000 (2.0 in 16.16) applied to x_pos when player is below
     * water+8 and not object-controlled. This is a gentle positional drag
     * (not velocity-based), so the player can escape by running/swimming
     * in the opposite direction.
     */
    private int applySuctionTo(AbstractPlayableSprite sprite, int suctionDelta) {
        if (sprite.getDead() || sprite.isObjectControlled()) {
            return suctionDelta;
        }

        int waterY = getWaterLevelY();
        int spriteY = sprite.getCentreY();

        // ROM: cmp.w y_pos(a1),d1; bhs locret — skip if water+8 >= player_y
        // (skip when player is above water+8; apply when player is below water+8)
        if (spriteY <= waterY + 8) {
            return suctionDelta;
        }

        int spriteX = sprite.getCentreX();
        // ROM: cmp.w x_pos(a1),d0; bhs.s loc_6B9DC; neg.l d2
        // d2 is shared between P1 and P2, so negate the incoming value rather
        // than selecting an absolute direction independently for each player.
        if (spriteX > currentX) {
            suctionDelta = -suctionDelta;
        }
        sprite.shiftX(suctionDelta);
        return suctionDelta;
    }

    /**
     * ROM sub_6BA06: full grab, carry, and release logic for one player.
     *
     * <p>Flow:
     * <ol>
     *   <li>If boss defeated → release (sub_6BB02)</li>
     *   <li>If player dead/dying or invulnerable → displace off + clear</li>
     *   <li>If player NOT object-controlled → range check → initial grab (sub_6BADA)</li>
     *   <li>If player IS object-controlled AND is grabbed by us → carry (loc_6BA6C)</li>
     *   <li>Carry: check player above top of zone → release. Check ±$12 H → release.
     *       Otherwise push toward center, apply velocity, move up 2px.</li>
     * </ol>
     *
     * @param isPlayer1 true for main player, false for sidekick
     * @param sprayY    precomputed spray Y position for this frame
     */
    private boolean applyGrabAndCarry(AbstractPlayableSprite sprite, boolean isPlayer1,
            int sprayY, int grabZoneWordOffset) {
        boolean isGrabbed = isPlayer1 ? player1Grabbed : player2Grabbed;

        // ROM: btst #7,status(a3) — if boss defeated, release
        if (boss.isDefeatSignal()) {
            if (isGrabbed) {
                releasePlayer(sprite, isPlayer1);
            }
            return false;
        }

        // ROM: cmpi.b #6,routine(a2) — dead/dying → displace off
        if (sprite.getDead()) {
            if (isGrabbed) {
                clearGrabFlag(isPlayer1);
                ObjectControlState.none().applyTo(sprite);
            }
            return false;
        }

        // ROM: tst.b invulnerability_timer(a2) — invulnerable → displace off
        if (sprite.getInvulnerable()) {
            if (isGrabbed) {
                clearGrabFlag(isPlayer1);
                ObjectControlState.none().applyTo(sprite);
            }
            return false;
        }

        // ROM: tst.b object_control(a2) — check if player is controlled
        if (sprite.isObjectControlled()) {
            // ROM: tst.b (a0,d4.w) — is THIS column grabbing this player?
            if (!isGrabbed) {
                // Another object has control. Fall through to range check
                // (ROM falls through to loc_6BA32). But since we don't own
                // this player, just skip.
                return false;
            }
            // We own this player — go to carry logic (loc_6BA6C)
            doCarry(sprite, isPlayer1, sprayY, grabZoneWordOffset);
            return true;
        }

        // Player is NOT object-controlled — range check + initial grab (loc_6BA32)
        int idx = Math.min(segmentCount, GRAB_ZONES.length - 1);

        int spriteY = sprite.getCentreY();
        int zoneWordIndex = idx * 2 + grabZoneWordOffset;
        int yTop = sprayY + grabZoneWord(zoneWordIndex);

        // ROM: add.w (a1),d0; cmp.w d0,d2; blo locret — player above zone top
        if (spriteY < yTop) {
            return false;
        }
        // ROM: add.w 2(a1),d0; cmp.w d0,d2; bhs locret — player below zone bottom
        if (spriteY >= yTop + grabZoneWord(zoneWordIndex + 1)) {
            return false;
        }

        // ROM: x range check: sub.w d2,d0; addi.w #$10,d0; cmpi.w #$20,d0
        int spriteX = sprite.getCentreX();
        int hDist = currentX - spriteX + GRAB_H_DIST;
        if (hDist < 0 || hDist >= (GRAB_H_DIST * 2)) {
            return false;
        }

        // In range — initial grab (sub_6BADA)
        doInitialGrab(sprite, isPlayer1);

        // Immediately enter carry logic this frame (ROM falls through to loc_6BA6C)
        doCarry(sprite, isPlayer1, sprayY, grabZoneWordOffset);
        return true;
    }

    private int grabZoneWord(int wordIndex) {
        if (wordIndex < 0 || wordIndex >= GRAB_ZONES.length * 2) {
            return 0;
        }
        return GRAB_ZONES[wordIndex / 2][wordIndex & 1];
    }

    /**
     * ROM sub_6BADA: initial grab — lock player, set animation, clear velocities.
     * <pre>
     *   st    (a0,d4.w)             ; set per-player grabbed flag
     *   bset  #Status_InAir,status  ; set airborne
     *   move.b #1,object_control    ; lock player
     *   move.b #$18,anim            ; death/tumble animation
     *   clr.b  spin_dash_flag
     *   clr.w  x_vel, y_vel, ground_vel
     * </pre>
     */
    private void doInitialGrab(AbstractPlayableSprite sprite, boolean isPlayer1) {
        if (isPlayer1) {
            player1Grabbed = true;
        } else {
            player2Grabbed = true;
        }
        sprite.setAir(true);
        ObjectControlState.nativeBits0To6CpuAllowedMovementSuppressed().applyTo(sprite);
        // ROM: move.b #$18,anim(a2) — animation $18 = tumbling/death sprite pose.
        // In the vortex context this shows the player spinning in the water column.
        sprite.setAnimationId(Sonic3kAnimationIds.DEATH);
        sprite.setForcedAnimationId(Sonic3kAnimationIds.DEATH.id());
        sprite.setSpindashCounter((short) 0);
        sprite.setXSpeed((short) 0);
        sprite.setYSpeed((short) 0);
        sprite.setGSpeed((short) 0);
    }

    /**
     * ROM loc_6BA6C: per-frame carry logic for a grabbed player.
     *
     * <p>Checks release conditions (above top of zone, outside ±$12 H range),
     * applies horizontal centering force, and moves the player upward 2 px/frame.
     *
     * <pre>
     *   ; Check if player is above top of grab zone
     *   move.w y_pos(a0),d0 / add.w (a1)+,d0 / cmp.w d0,d2; blo sub_6BB02
     *   ; Check horizontal ±$12
     *   sub.w x_pos(a0),d0 / cmpi.w #-$12,d0; ble sub_6BB02
     *   cmpi.w #$12,d0; bge sub_6BB02
     *   ; Apply centering force
     *   move.w #$80,d2 / tst.w d0; bmi loc_6BA9E; neg.w d2
     *   add.w d2,d1 / move.w d1,x_vel / ext.l / lsl.l #8 / add.l x_pos
     *   ; Move up
     *   subq.w #2,y_pos(a2)
     * </pre>
     */
    private void doCarry(AbstractPlayableSprite sprite, boolean isPlayer1,
            int sprayY, int grabZoneWordOffset) {
        int idx = Math.min(segmentCount, GRAB_ZONES.length - 1);
        int spriteY = sprite.getCentreY();

        // ROM: check if player has risen above the top of grab zone → release
        int yTop = sprayY + grabZoneWord(idx * 2 + grabZoneWordOffset);
        if (spriteY < yTop) {
            releasePlayer(sprite, isPlayer1);
            return;
        }

        // ROM: horizontal distance check — release if outside ±$12
        int spriteX = sprite.getCentreX();
        int hDist = spriteX - currentX;  // ROM: sub.w x_pos(a0),d0
        if (hDist <= -CARRY_H_RANGE || hDist >= CARRY_H_RANGE) {
            releasePlayer(sprite, isPlayer1);
            return;
        }

        // ROM: centering force — push toward column center
        // If hDist < 0 (player left of column): force = +$80 (push right)
        // If hDist >= 0 (player right of column): force = -$80 (push left)
        short xVelPlayer = sprite.getXSpeed();
        short force = CARRY_H_FORCE;
        if (hDist >= 0) {
            force = (short) -force;
        }
        xVelPlayer = (short) (xVelPlayer + force);
        sprite.setXSpeed(xVelPlayer);

        // ROM: ext.l d1; lsl.l #8,d1; add.l d1,x_pos(a2)
        // Apply velocity to position (16.16 fixed-point update).
        // AbstractSprite.move() does exactly: xPos += (xSpeed << 8)
        sprite.move(xVelPlayer, (short) 0);

        // ROM: subq.w #2,y_pos(a2) — move player up 2 pixels per frame
        sprite.shiftY(-2);
    }

    /**
     * ROM sub_6BB02: release a grabbed player.
     * <pre>
     *   clr.b  (a0,d4.w)             ; clear per-player grabbed flag
     *   bset   #Status_InAir,status   ; set airborne
     *   clr.b  object_control         ; unlock player
     *   move.b #2,anim               ; ROLL animation
     *   move.w #-$200,y_vel           ; launch upward
     * </pre>
     */
    private void releasePlayer(AbstractPlayableSprite sprite, boolean isPlayer1) {
        clearGrabFlag(isPlayer1);
        sprite.setAir(true);
        ObjectControlState.none().applyTo(sprite);
        // ROM: move.b #2,anim(a2) — roll animation
        sprite.setAnimationId(Sonic3kAnimationIds.ROLL);
        sprite.setForcedAnimationId(Sonic3kAnimationIds.ROLL.id());
        sprite.setYSpeed((short) -0x200);
    }

    private void clearGrabFlag(boolean isPlayer1) {
        if (isPlayer1) {
            player1Grabbed = false;
        } else {
            player2Grabbed = false;
        }
    }

    /**
     * Release all grabbed players (ROM loc_6BB1E equivalent, called on
     * DESCEND transition and object destruction).
     */
    private void releaseAllGrabbedPlayers(PlayableEntity player) {
        if (player1Grabbed && player instanceof AbstractPlayableSprite sprite) {
            releasePlayer(sprite, true);
        }
        if (player2Grabbed) {
            PlayableEntity nativeP2 = services().playerQuery().nativeP2OrNull();
            if (nativeP2 instanceof AbstractPlayableSprite sprite) {
                releasePlayer(sprite, false);
            }
        }
    }

    private void displaceRidingPlayers(PlayableEntity player) {
        var objectManager = services().objectManager();
        if (objectManager == null) {
            return;
        }

        if (player instanceof AbstractPlayableSprite sprite
                && objectManager.isRidingObject(player, this)) {
            objectManager.clearRidingObject(player);
            sprite.setOnObject(false);
            sprite.setAir(true);
        }

        for (PlayableEntity candidate : nativeParticipants(player)) {
            if (candidate != player
                    && candidate instanceof AbstractPlayableSprite sprite
                    && objectManager.isRidingObject(candidate, this)) {
                objectManager.clearRidingObject(candidate);
                sprite.setOnObject(false);
                sprite.setAir(true);
            }
        }
    }

    private List<PlayableEntity> nativeParticipants(PlayableEntity player) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> player, query::sidekicks)
                .playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2);
    }

    /** Compute the spray child's Y position for this frame. */
    private int getSprayY() {
        int waterY = getWaterLevelY();
        int offsetIdx = Math.min(segmentCount, SPRAY_Y_OFFSETS.length - 1);
        return waterY + SPRAY_Y_OFFSETS[offsetIdx];
    }

    // =========================================================================
    // Water level access
    // =========================================================================

    private int getWaterLevelY() {
        try {
            WaterSystem ws = services().waterSystem();
            if (ws == null) {
                return FLOOR_Y_LIMIT;
            }
            int zoneId = services().featureZoneId();
            int actId = services().featureActId();
            if (ws.hasWater(zoneId, actId)) {
                return ws.getWaterLevelY(zoneId, actId);
            }
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossWaterColumn.getWaterLevelY: " + e.getMessage());
        }
        return FLOOR_Y_LIMIT;
    }

    int getWaterSurfaceYForChildren() {
        return waterBaseY + 8;
    }

    // =========================================================================
    // SolidObjectProvider
    // =========================================================================

    @Override
    public SolidObjectParams getSolidParams() {
        return SOLID_PARAMS;
    }

    @Override
    public boolean isTopSolidOnly() {
        return true;
    }

    @Override
    public boolean isSolidFor(PlayableEntity player) {
        return solidActive;
    }

    @Override
    public boolean usesStickyContactBuffer() {
        return true;
    }

    @Override
    public boolean dropOnFloor() {
        return true;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || routine == ROUTINE_INIT) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // ---------------------------------------------------------------
        // Spin-up phase: render single frame from byte_6BE0C
        // ---------------------------------------------------------------
        if (routine == ROUTINE_ANIMATE) {
            int frame = SPINUP_FRAMES[Math.min(spinupIndex, SPINUP_FRAMES.length - 1)];
            renderer.drawFrameIndex(frame, currentX, currentY, false, false);
            return;
        }

        // ---------------------------------------------------------------
        // RISE / HOLD / DESCEND / PLATFORM:
        // 1) Draw the platform frame (byte_6BE15 animation) at column position
        // 2) Draw the spray body at spray Y position (sub_6B916 Y offsets)
        // ---------------------------------------------------------------

        // 1) Column platform frame — ROM Draw_Sprite renders ONE frame
        int columnFrame = COLUMN_FRAMES[columnAnimIndex % COLUMN_FRAMES.length];
        renderer.drawFrameIndex(columnFrame, currentX, currentY, false, false);

        // 2) Spray body — inline rendering of what loc_6B3DE would draw
        //    The spray child picks animation table and Y position based on
        //    parent's $39 (segment count), then Animate_Raw cycles through
        //    the 3 frames. Rendered via Child_Draw_Sprite2.
        if (segmentCount >= 0 && segmentCount <= 5) {
            // Tick spray animation
            tickSprayAnimation();

            int sprayIdx = Math.min(segmentCount, SPRAY_FRAMES.length - 1);
            int[] frames = SPRAY_FRAMES[sprayIdx];
            int sprayFrame = frames[sprayAnimIndex % frames.length];

            // Spray Y = Water_level + SPRAY_Y_OFFSETS[segmentCount]
            int waterY = getWaterLevelY();
            int sprayYOffset = SPRAY_Y_OFFSETS[Math.min(segmentCount,
                    SPRAY_Y_OFFSETS.length - 1)];
            int sprayY = waterY + sprayYOffset;

            renderer.drawFrameIndex(sprayFrame, currentX, sprayY, false, false);
        }
    }
}
