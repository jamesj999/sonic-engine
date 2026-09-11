package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.GameServices;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.WaterSystem;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;
import java.util.logging.Logger;

/**
 * HCZ end boss propeller blade child (ROM: loc_6B5C4).
 *
 * <p>Three blade segments sit at different Y offsets below the boss and
 * counter-rotate as the propeller spins. The bottom blade (subtype 0)
 * fires when the boss sets {@code bladeFireSignal}. After it detaches,
 * the remaining blades shift down one position (subtype -= 2) and a
 * replacement blade is spawned at the top (subtype 4), creating a
 * conveyor belt of blades.
 *
 * <p>Spawned by the boss as:
 * <pre>
 *   spawnChild(() -> new HczEndBossBlade(this, 0, 0x23, 0x12)); // bottom, fires
 *   spawnChild(() -> new HczEndBossBlade(this, 2, 0x1B, 0x0A)); // middle
 *   spawnChild(() -> new HczEndBossBlade(this, 4, 0x13, 0x0A)); // top
 * </pre>
 *
 * <p>State machine (7 routines, ROM stride 2):
 * <ol start="0">
 *   <li>ATTACHED (0): Follow parent at fixed offset; static frame.</li>
 *   <li>WAIT_FIRE (2): Wait for {@code boss.isBladeFireSignal()}.
 *       When set, subtype 0 transitions to PRE_LAUNCH; others to WAIT_CLEAR.</li>
 *   <li>WAIT_CLEAR (4): Non-firing blades wait for fire signal to clear,
 *       then shift down (subtype -= 2) and return to WAIT_FIRE.
 *       The blade that becomes subtype 0 spawns a replacement at subtype 4.</li>
 *   <li>PRE_LAUNCH (6): Briefly spin attached for 3 frames before detaching.</li>
 *   <li>FALL (8): Horizontal + gravity fall with water level check.</li>
 *   <li>UNDERWATER_FALL (A): Continue falling underwater with floor check.</li>
 *   <li>SPIN_DOWN (C): Animate_RawGetFaster to rest, then explode + delete.</li>
 * </ol>
 *
 * <p>Animation: Map_HCZEndBoss frames 6–7 (attached spin), frames 8/6/7
 * (spin-down via Animate_RawGetFaster byte_6BE07).
 *
 * <p>Collision when flying: collision_flags = 0 (visual only; blade does not hurt player).
 */
public class HczEndBossBlade extends AbstractBossChild implements TouchResponseProvider, RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(HczEndBossBlade.class.getName());

    // =========================================================================
    // State machine routines (ROM stride 2)
    // =========================================================================
    private static final int ROUTINE_ATTACHED       = 0;
    private static final int ROUTINE_WAIT_FIRE      = 2;
    private static final int ROUTINE_WAIT_CLEAR     = 4;
    private static final int ROUTINE_PRE_LAUNCH     = 6;
    private static final int ROUTINE_FALL           = 8;
    private static final int ROUTINE_UNDERWATER_FALL = 10;
    private static final int ROUTINE_SPIN_DOWN      = 12;

    // =========================================================================
    // Collision
    // =========================================================================
    // ROM: collision_flags = 0 (blade is visual only; no player collision)

    // =========================================================================
    // Animation — Map_HCZEndBoss frames 6 and 7
    // =========================================================================
    private static final int BLADE_FRAME_A = 6;
    private static final int BLADE_FRAME_B = 7;
    /** Frames per animation step while attached or spinning down. */
    private static final int ANIM_SPEED = 4;

    // =========================================================================
    // Flight physics (ROM: fixed-point 8.8, 0x100 = 1 pixel/frame)
    // =========================================================================
    /** Horizontal velocity when fired (magnitude; direction is boss-facing-dependent). */
    private static final int FLY_XVEL = 0x100;
    /** Gravity applied each frame during FALL and UNDERWATER_FALL (subpixels). */
    private static final int GRAVITY = 0x20;
    /** Floor Y boundary below which the blade self-destructs (pixels). */
    private static final int FLOOR_Y_LIMIT = 0x1000;
    /** Off-screen margin for deletion while flying. */
    private static final int OFFSCREEN_MARGIN = 32;
    /** Y radius for floor collision checks (ROM: move.b #8,y_radius(a0) in loc_6B5E6). */
    private static final int Y_RADIUS = 8;

    // =========================================================================
    // PRE_LAUNCH timing (ROM: loc_6B622, $2E = 3)
    // =========================================================================
    /** Frames to remain attached (spinning) before launching. */
    private static final int PRE_LAUNCH_WAIT = 3;

    // =========================================================================
    // WAIT_CLEAR shift timing (ROM: loc_6B658, $2E = 7)
    // =========================================================================
    /** Frames the non-firing blades wait during shift animation. */
    private static final int SHIFT_WAIT = 7;

    // =========================================================================
    // Spin-down animation (ROM: byte_6BE07 via Animate_RawGetFaster)
    // byte_6BE07: dc.b 5, 8, 6, 7, $FC
    //   Animate_RawGetFaster format: [initial_delay, loop_count, frame..., $FC]
    //   Byte 0 = initial delay (5): timer ticks per frame, decremented each loop
    //   Byte 1 = loop count (8): number of full loops before firing $34 callback
    //   Bytes 2+ = frame sequence: 6, 7 ($FC = loop marker)
    //   When delay counter reaches 0 after 8 loops, calls $34 callback (loc_6B73A).
    // =========================================================================
    private static final int[] SPIN_DOWN_FRAMES = {6, 7};
    private static final int SPIN_DOWN_INITIAL_DELAY = 5;
    /** Number of complete loops before the spin-down callback fires. */
    private static final int SPIN_DOWN_LOOP_COUNT = 8;

    // =========================================================================
    // Instance state
    // =========================================================================
    private final HczEndBossInstance boss;
    /**
     * ROM subtype: 0 = bottom (fires), 2 = middle, 4 = top.
     * Mutable — shifts down by 2 after each blade fires.
     */
    private int subtype;
    /** X offset from boss center when attached (pixels, signed). */
    private int xOffset;
    /** Y offset from boss center when attached (pixels). */
    private int yOffset;

    private int routine;
    private int animFrame;       // 0 = BLADE_FRAME_A, 1 = BLADE_FRAME_B
    private int animCounter;

    // Fixed-point position for projectile (16:8 — integer pixel * 256)
    private int xFixed;
    private int yFixed;
    // Integer velocity (sub-pixel units: 0x100 = 1 px/frame)
    private int xVel;
    private int yVel;

    // PRE_LAUNCH / SHIFT wait timer
    private int waitTimer;

    // Spin-down state (Animate_RawGetFaster emulation)
    private int spinDownDelayCounter;  // starts at 5, decrements each loop
    private int spinDownLoopCounter;   // starts at 0, incremented each loop; callback at 8
    private int spinDownFrameIndex;    // index into SPIN_DOWN_FRAMES
    private int spinDownFrameTimer;    // ticks remaining for current frame
    private boolean spinDownInitialized;

    /** Current mapping frame for rendering. */
    private int mappingFrame;

    // =========================================================================
    // Subtype-to-offset mapping (ROM: ChildObjDat_6BD8A)
    // =========================================================================
    private static final int[][] SUBTYPE_OFFSETS = {
        {0x23, 0x12},  // subtype 0 (bottom)
        {0x1B, 0x0A},  // subtype 2 (middle)
        {0x13, 0x0A},  // subtype 4 (top)
    };

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * @param boss    Parent boss instance.
     * @param subtype ROM subtype: 0 = bottom (fires), 2 = middle, 4 = top.
     * @param xOffset Horizontal offset from boss center (pixels, positive = right of boss).
     * @param yOffset Vertical offset from boss center (pixels, positive = below boss).
     */
    public HczEndBossBlade(HczEndBossInstance boss, int subtype, int xOffset, int yOffset) {
        super(boss, "HCZEndBossBlade[st" + subtype + "]", 3, 0);
        this.boss = boss;
        this.subtype = subtype;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.routine = ROUTINE_ATTACHED;
        this.animFrame = 0;
        this.animCounter = 0;
        this.xVel = 0;
        this.yVel = 0;
        this.waitTimer = -1;
        this.mappingFrame = BLADE_FRAME_A;
    }

    private HczEndBossBlade(ObjectSpawn spawn, HczEndBossInstance boss, int ignored) {
        this(boss, 0, 0, 0);
    }

    @Override
    public HczEndBossBlade recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossInstance restoredBoss = HczEndBossRewindLinks.nearestBoss(ctx);
        return restoredBoss == null ? null : new HczEndBossBlade(restoredBoss, 0, 0, 0);
    }

    // =========================================================================
    // Main update
    // =========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) {
            return;
        }

        // Children always follow defeat signal
        if (boss.isDefeatSignal()) {
            collisionFlags = 0;
            setDestroyed(true);
            return;
        }

        switch (routine) {
            case ROUTINE_ATTACHED       -> updateAttached();
            case ROUTINE_WAIT_FIRE      -> updateWaitFire();
            case ROUTINE_WAIT_CLEAR     -> updateWaitClear();
            case ROUTINE_PRE_LAUNCH     -> updatePreLaunch();
            case ROUTINE_FALL           -> updateFall();
            case ROUTINE_UNDERWATER_FALL -> updateUnderwaterFall();
            case ROUTINE_SPIN_DOWN      -> updateSpinDown();
            default                     -> { }
        }
    }

    // =========================================================================
    // Routine handlers
    // =========================================================================

    /**
     * ROM routine 0 (ATTACHED): track parent position with static frame.
     * ROM: SetUp_ObjAttributes3 sets mapping_frame = 6 from ObjDat word_6BD38.
     * No animation cycling occurs — blade stays at its initial frame.
     * Transitions immediately to WAIT_FIRE.
     */
    private void updateAttached() {
        // Track boss position with offset (ROM: Refresh_ChildPositionAdjusted
        // negates child_dx when parent render_flags bit 0 is set)
        int dx = boss.isFacingRight() ? -xOffset : xOffset;
        currentX = boss.getState().x + dx;
        currentY = boss.getState().y + yOffset;
        updateDynamicSpawn();
        collisionFlags = 0;
        mappingFrame = BLADE_FRAME_A;
        // ROM: all blades proceed to WAIT_FIRE after init
        routine = ROUTINE_WAIT_FIRE;
    }

    /**
     * ROM routine 2 (WAIT_FIRE): all blades follow parent and wait for fire signal.
     * ROM (loc_6B600): Refresh_ChildPositionAdjusted + check bit 1 of parent $38.
     * When fire signal is set:
     *   - subtype 0 (bottom): transitions to PRE_LAUNCH (routine 6)
     *   - subtype != 0: transitions to WAIT_CLEAR (routine 4)
     */
    private void updateWaitFire() {
        int dx = boss.isFacingRight() ? -xOffset : xOffset;
        currentX = boss.getState().x + dx;
        currentY = boss.getState().y + yOffset;
        updateDynamicSpawn();
        collisionFlags = 0;
        // ROM: no animation call — static frame
        mappingFrame = BLADE_FRAME_A;

        if (boss.isBladeFireSignal()) {
            // ROM: loc_6B614 — all blades see the signal
            routine = ROUTINE_WAIT_CLEAR;  // default for non-firing blades
            if (subtype == 0) {
                // Bottom blade: transition to PRE_LAUNCH (ROM: loc_6B622)
                routine = ROUTINE_PRE_LAUNCH;
                waitTimer = PRE_LAUNCH_WAIT;
                // ROM: $40=1, $41=0 (rotation speed — cosmetic, we just tick animation)
            }
        }
    }

    /**
     * ROM routine 4 (WAIT_CLEAR): non-firing blades wait for fire signal to clear.
     * ROM (loc_6B644): Refresh_ChildPositionAdjusted + wait for bit 1 cleared.
     * When cleared (loc_6B658):
     *   - subtype -= 2 (shift down one slot)
     *   - Wait 7 frames, then transition back to WAIT_FIRE
     *   - If the blade became subtype 0 (new bottom): spawn replacement at top
     */
    private void updateWaitClear() {
        int dx = boss.isFacingRight() ? -xOffset : xOffset;
        currentX = boss.getState().x + dx;
        currentY = boss.getState().y + yOffset;
        updateDynamicSpawn();

        if (boss.isBladeFireSignal()) {
            // Signal still active — keep waiting (ROM: btst+rts)
            return;
        }

        // Signal cleared — shift down (ROM: loc_6B658)
        subtype -= 2;
        // Update offsets to match new position
        int slotIndex = subtype / 2;
        if (slotIndex >= 0 && slotIndex < SUBTYPE_OFFSETS.length) {
            xOffset = SUBTYPE_OFFSETS[slotIndex][0];
            yOffset = SUBTYPE_OFFSETS[slotIndex][1];
        }

        // ROM: $2E = 7 (wait 7 frames), $34 = loc_6B694, then routine = 6
        // We use a simple timer in the same routine since the visual behavior
        // during the shift is just following parent position.
        waitTimer = SHIFT_WAIT;
        routine = ROUTINE_PRE_LAUNCH;  // reuse PRE_LAUNCH for the wait, but with shift callback
    }

    /**
     * ROM routine 6 (PRE_LAUNCH / shift wait): position-adjusting wait state.
     * ROM (loc_6B678): adds rotation increments, Refresh_ChildPositionAdjusted, Obj_Wait.
     *
     * <p>Used for two purposes:
     * <ul>
     *   <li>Bottom blade pre-launch: 3-frame wait before launching</li>
     *   <li>Non-firing blade shift: 7-frame wait after shifting down</li>
     * </ul>
     *
     * <p>The callback differs based on which path entered this state:
     * <ul>
     *   <li>subtype 0 entering from WAIT_FIRE: launches blade (loc_6B6BA)</li>
     *   <li>subtype 0/2 entering from WAIT_CLEAR: goes back to WAIT_FIRE,
     *       and if now subtype 0, spawns replacement (loc_6B694)</li>
     * </ul>
     */
    private void updatePreLaunch() {
        // loc_6B678 adds $40 (1 for the firing blade) to child_dx before
        // Refresh_ChildPositionAdjusted on every Obj_Wait dispatch.
        if (subtype == 0 && boss.isBladeFireSignal()) {
            xOffset++;
        }
        // Track boss position (ROM: Refresh_ChildPositionAdjusted)
        int dx = boss.isFacingRight() ? -xOffset : xOffset;
        currentX = boss.getState().x + dx;
        currentY = boss.getState().y + yOffset;
        updateDynamicSpawn();

        // Decrement wait timer
        waitTimer--;
        if (waitTimer >= 0) {
            return;
        }

        // Timer expired — determine callback based on context
        if (subtype == 0 && boss.isBladeFireSignal()) {
            // This is the bottom blade launching (ROM: loc_6B6BA callback)
            launchBlade();
        } else {
            // This is a shifted blade returning to WAIT_FIRE (ROM: loc_6B694 callback)
            onShiftComplete();
        }
    }

    /**
     * ROM callback loc_6B694: blade shift complete.
     * Returns to WAIT_FIRE. If this blade is now subtype 0 (new bottom),
     * spawns a replacement blade at the top (subtype 4).
     */
    private void onShiftComplete() {
        routine = ROUTINE_WAIT_FIRE;

        if (subtype == 0) {
            // Spawn replacement blade at top position (ROM: ChildObjDat_6BDAA)
            // ChildObjDat_6BDAA: loc_6B5C4, xOffset=0x13, yOffset=0x0A
            // ROM sets subtype=4 on the new child
            try {
                boss.spawnDynamicChild(() ->
                        new HczEndBossBlade(boss, 4, 0x13, 0x0A));
                LOG.fine("HCZ End Boss Blade: spawned replacement blade at top (subtype 4)");
            } catch (Exception e) {
                LOG.fine(() -> "HczEndBossBlade.onShiftComplete: failed to spawn replacement: "
                        + e.getMessage());
            }
        }
    }

    /**
     * ROM routine 8 (FALL): gravity-accelerated fall with horizontal velocity.
     * ROM (loc_6B6E6): first checks water level — if at/below water, transitions
     * to UNDERWATER_FALL and spawns splash. Otherwise applies MoveSprite_LightGravity
     * then ObjHitFloor_DoRoutine (floor collision with snap + transition to SPIN_DOWN).
     */
    private void updateFall() {
        // ROM: check water level first
        int waterY = getWaterLevelY();
        if (currentY >= waterY) {
            // At or below water level — transition to UNDERWATER_FALL
            // ROM: spawns splash child (ChildObjDat_6BDD8 → loc_6B4A2)
            spawnSplash();
            routine = ROUTINE_UNDERWATER_FALL;
            return;
        }

        moveWithLightGravity();
        currentX = xFixed >> 8;
        currentY = yFixed >> 8;
        updateDynamicSpawn();

        // Off-screen deletion
        if (!isOnScreen(OFFSCREEN_MARGIN)) {
            setDestroyed(true);
            return;
        }

        // ROM: ObjHitFloor_DoRoutine — check floor only when falling (yVel >= 0)
        if (yVel >= 0) {
            checkFloorAndSnap();
        }
    }

    /**
     * ROM routine A (UNDERWATER_FALL): continue falling underwater with floor check.
     * ROM (loc_6B70E): MoveSprite_LightGravity + ObjHitFloor_DoRoutine.
     * When floor is hit, callback (loc_6B71C) transitions to SPIN_DOWN.
     */
    private void updateUnderwaterFall() {
        moveWithLightGravity();
        currentX = xFixed >> 8;
        currentY = yFixed >> 8;
        updateDynamicSpawn();

        // Off-screen / floor boundary safety net
        if (currentY >= FLOOR_Y_LIMIT) {
            setDestroyed(true);
            return;
        }
        if (!isOnScreen(OFFSCREEN_MARGIN)) {
            setDestroyed(true);
            return;
        }

        // ROM: ObjHitFloor_DoRoutine — check floor only when falling (yVel >= 0)
        if (yVel >= 0) {
            checkFloorAndSnap();
        }
    }

    /**
     * ROM routine C (SPIN_DOWN): Animate_RawGetFaster animation to rest.
     *
     * <p>ROM (loc_6B734): {@code jmp (Animate_RawGetFaster).l}
     *
     * <p>Animation script byte_6BE07: {@code dc.b 5, 8, 6, 7, $FC}
     * <ul>
     *   <li>Initial delay = 5 (frames per animation step)</li>
     *   <li>Frame sequence: 8 → 6 → 7, then loop</li>
     *   <li>Each complete loop decrements the delay counter by 1</li>
     *   <li>When delay counter reaches 0 after a loop: calls $34 callback</li>
     * </ul>
     *
     * <p>$34 callback = loc_6B73A:
     * <ol>
     *   <li>Spawn 5 debris children via ChildObjDat_6BDE0 (CreateChild6_Simple)</li>
     *   <li>Play sfx_MissileExplode</li>
     *   <li>Spawn explosion child via ChildObjDat_6BDB2 (loc_6B77C)</li>
     *   <li>Delete self (Go_Delete_Sprite)</li>
     * </ol>
     */
    private void updateSpinDown() {
        collisionFlags = 0;

        // Animate_RawGetFaster initializes $2E/$2F on its first dispatch but
        // deliberately retains anim_frame_timer=0 and anim_frame=0. Thus the
        // first subq expires immediately and selects script frame 7 (index 1).
        if (!spinDownInitialized) {
            spinDownInitialized = true;
            spinDownDelayCounter = SPIN_DOWN_INITIAL_DELAY;
            spinDownLoopCounter = 0;
        }
        spinDownFrameTimer--;
        if (spinDownFrameTimer >= 0) {
            return;
        }

        spinDownFrameIndex++;
        if (spinDownFrameIndex < SPIN_DOWN_FRAMES.length) {
            mappingFrame = SPIN_DOWN_FRAMES[spinDownFrameIndex];
            spinDownFrameTimer = spinDownDelayCounter;
            return;
        }

        spinDownFrameIndex = 0;
        mappingFrame = SPIN_DOWN_FRAMES[0];
        if (spinDownDelayCounter == 0) {
            spinDownLoopCounter++;
            if (spinDownLoopCounter >= SPIN_DOWN_LOOP_COUNT) {
                onSpinDownComplete();
                return;
            }
        } else {
            spinDownDelayCounter--;
        }
        spinDownFrameTimer = spinDownDelayCounter;
    }

    /** ROM MoveSprite_LightGravity: move with old velocity, then add $20 Y gravity. */
    private void moveWithLightGravity() {
        yFixed += yVel;
        xFixed += xVel;
        yVel += GRAVITY;
    }

    /**
     * ROM: loc_6B73A — spin-down animation complete callback.
     * Spawns water chute children, plays SFX, spawns explosion, deletes self.
     */
    private void onSpinDownComplete() {
        // 1. Spawn water chute children (ROM: ChildObjDat_6BDE0, 5 children via CreateChild6_Simple)
        //    These are NOT debris — they form a vertical water column that launches the player!
        //    ROM: loc_6B4C4, subtypes 0/2/4/6/8, positioned at water level + Y offsets.
        spawnWaterChute();

        // 2. Play sfx_MissileExplode
        try {
            services().playSfx(Sonic3kSfx.MISSILE_EXPLODE.id);
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossBlade.onSpinDownComplete: SFX failed: " + e.getMessage());
        }

        // 3. Spawn the damaging loc_6B77C impact explosion.
        try {
            boss.spawnDynamicChild(() ->
                    new HczEndBossBladeImpactExplosion(boss, currentX, currentY));
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossBlade.onSpinDownComplete: explosion spawn failed: "
                    + e.getMessage());
        }

        // 4. Delete self (ROM: Go_Delete_Sprite)
        setDestroyed(true);

        LOG.fine(() -> "HCZ End Boss Blade: spin-down complete at x=" + currentX
                + " y=" + currentY + ", water chute + explosion spawned");
    }

    // =========================================================================
    // Fire launch helper
    // =========================================================================

    /**
     * Detaches the blade from the boss and fires it horizontally.
     * ROM (loc_6B6BA): sets routine=8, x_vel=0x100, clears fire signal.
     * ROM: x_vel negated if parent render_flags bit 0 set (facing right).
     */
    private void launchBlade() {
        // Snap fixed-point position to current world coordinates
        xFixed = currentX << 8;
        yFixed = currentY << 8;

        // ROM: blade fires in the direction the boss is facing
        // loc_6B6BA: x_vel = $100, negated if render_flags bit 0 (facing right)
        xVel = boss.isFacingRight() ? -FLY_XVEL : FLY_XVEL;
        yVel = 0;

        // ROM: bclr #1,$38(a1) — clear fire signal on parent
        boss.clearBladeFireSignal();

        // collision_flags stays 0 — blade is visual only (ROM: collision_flags = 0)
        routine = ROUTINE_FALL;

        LOG.fine(() -> "HCZ End Boss Blade[st0]: launched xVel=" + xVel
                + " from x=" + currentX + " y=" + currentY);
    }

    // =========================================================================
    // Water chute / splash spawning
    // =========================================================================

    /**
     * ROM: ChildObjDat_6BDD8 → loc_6B4A2 (splash at water surface).
     * Spawned when the blade crosses the water level during FALL.
     * Single child at the blade's current X, positioned at water_level - 4.
     */
    private void spawnSplash() {
        try {
            final int splashX = currentX;
            spawnChild(() -> new HczEndBossBladeSplash(boss, splashX));
            LOG.fine(() -> "HCZ End Boss Blade: spawned water splash at x=" + splashX);
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossBlade.spawnSplash: " + e.getMessage());
        }
    }

    /**
     * ROM: ChildObjDat_6BDE0 → loc_6B4C4 (water chute segments).
     * Spawned when the blade's spin-down animation completes ({@code loc_6B73A}).
     * Creates 5 children via CreateChild6_Simple with incrementing subtypes
     * (0, 2, 4, 6, 8). Each child positions at a different Y offset above
     * the water surface, together forming a vertical column that can launch
     * the player upward with y_vel = -$800.
     */
    private void spawnWaterChute() {
        final int chuteX = currentX;
        for (int i = 0; i < 5; i++) {
            final int slotIndex = i;
            try {
                spawnChild(() ->
                        new HczEndBossBladeWaterChute(boss, chuteX, slotIndex));
            } catch (Exception e) {
                LOG.fine(() -> "HczEndBossBlade.spawnWaterChute[" + slotIndex + "]: "
                        + e.getMessage());
            }
        }
        LOG.fine(() -> "HCZ End Boss Blade: spawned 5 water chute segments at x=" + chuteX);
    }

    // =========================================================================
    // Floor collision helper (ROM: ObjHitFloor_DoRoutine)
    // =========================================================================

    /**
     * ROM: ObjHitFloor_DoRoutine (loc_848A0).
     * Checks floor distance via ObjCheckFloorDist. If distance <= 0 (on or below
     * floor), snaps Y position to floor surface and transitions to SPIN_DOWN
     * (the callback at $34, which is loc_6B71C).
     */
    private void checkFloorAndSnap() {
        try {
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(
                    currentX, currentY, Y_RADIUS);
            if (floor.hasCollision()) {
                // ROM: add.w d1,y_pos(a0) — snap to floor surface
                currentY += floor.distance();
                yFixed = currentY << 8;
                yVel = 0;
                // ROM callback loc_6B71C: transition to SPIN_DOWN (routine $C)
                transitionToSpinDown();
            }
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossBlade.checkFloorAndSnap: " + e.getMessage());
        }
    }

    /**
     * ROM: loc_6B71C — callback when blade hits floor.
     * Sets routine to SPIN_DOWN ($C), initializes Animate_RawGetFaster state.
     * ROM: $30 = byte_6BE07, $34 = loc_6B73A
     */
    private void transitionToSpinDown() {
        routine = ROUTINE_SPIN_DOWN;
        // loc_6B71C only swaps routine/script/callback pointers. The first
        // Animate_RawGetFaster dispatch initializes its own counters.
        spinDownDelayCounter = 0;
        spinDownLoopCounter = 0;
        spinDownFrameIndex = 0;
        // The floor-hit callback changes the operation pointer after the
        // current Child_DrawTouch dispatch; retain that dispatch's single
        // animation-timer tick before the new script's first expiry.
        spinDownFrameTimer = 1;
        spinDownInitialized = false;
        mappingFrame = SPIN_DOWN_FRAMES[0];  // frame 6
        LOG.fine(() -> "HCZ End Boss Blade: hit floor at y=" + currentY + ", transitioning to SPIN_DOWN");
    }

    // =========================================================================
    // Animation helper
    // =========================================================================

    /**
     * Advance the blade animation by one counter tick.
     * Cycles between BLADE_FRAME_A and BLADE_FRAME_B at ANIM_SPEED rate.
     * Used during attached/pre-launch/fall states.
     */
    private void tickAnimation() {
        animCounter++;
        if (animCounter >= ANIM_SPEED) {
            animCounter = 0;
            animFrame = 1 - animFrame; // toggle 0 <-> 1
            mappingFrame = (animFrame == 0) ? BLADE_FRAME_A : BLADE_FRAME_B;
        }
    }

    // =========================================================================
    // Water level access
    // =========================================================================

    /**
     * Returns the current gameplay water level Y coordinate in world space.
     * Uses {@code services().waterSystem()} via ObjectServices (same pattern as
     * {@code BreathingBubbleInstance} and {@code BuggernautBadnikInstance}).
     * Falls back to a large sentinel value if water system is unavailable.
     */
    private int getWaterLevelY() {
        try {
            WaterSystem ws = services().waterSystem();
            if (ws == null) {
                return FLOOR_Y_LIMIT;
            }
            int zoneId = services().featureZoneId();
            int actId  = services().featureActId();
            if (ws.hasWater(zoneId, actId)) {
                return ws.getWaterLevelY(zoneId, actId);
            }
        } catch (Exception e) {
            LOG.fine(() -> "HczEndBossBlade.getWaterLevelY: " + e.getMessage());
        }
        return FLOOR_Y_LIMIT;
    }

    // =========================================================================
    // Collision (TouchResponseProvider)
    // =========================================================================

    @Override
    public int getCollisionFlags() {
        return collisionFlags;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // =========================================================================
    // Rendering
    // =========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.HCZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Mirror around boss center: blades on the left side flip when boss faces right
        boolean hFlip = boss.isFacingRight();
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, hFlip, false);
    }
}
