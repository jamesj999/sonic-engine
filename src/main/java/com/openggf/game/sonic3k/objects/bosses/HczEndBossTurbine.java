package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * HCZ end boss propeller turbine child (ROM: loc_6B1A8).
 *
 * <p>Sits below the boss at a fixed Y offset. When the boss sets
 * {@code propellerActive}, the turbine spins up, becomes a collision hazard
 * (collision_flags = 0xA6), and keeps the continuous sfx_FanBig alive while spinning.
 * When {@code propellerActive} is cleared it winds down, losing collision.
 * When {@code defeatSignal} is set the turbine destroys itself.
 *
 * <p>Spawned by the boss as:
 * {@code spawnChild(() -> new HczEndBossTurbine(this, 0, 0x24))}
 *
 * <p>State machine (5 routines, stride 2):
 * <ol start="0">
 *   <li>INIT (0): apply attributes from word_6BD32</li>
 *   <li>WAIT (2): idle until parent bit3 of $38 (propellerActive) goes high</li>
 *   <li>ACTIVE (4): play sfx_FanBig, animate fast spin, collision_flags=0xA6</li>
 *   <li>WIND_DOWN (6): animate slower, check parent propellerActive</li>
 *   <li>STOPPING (8): animate even slower, clear collision_flags on completion</li>
 * </ol>
 *
 * <p>Animation frames (Map_HCZEndBoss indices 2–5): fast spin cycles frames 2,3,4,5
 * at increasing speeds as the routine changes.
 */
public class HczEndBossTurbine extends AbstractBossChild implements TouchResponseProvider, RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(HczEndBossTurbine.class.getName());

    // =========================================================================
    // State machine routines (ROM: stride 2)
    // =========================================================================
    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_WAIT = 2;
    private static final int ROUTINE_ACTIVE = 4;
    private static final int ROUTINE_WIND_DOWN = 6;
    private static final int ROUTINE_STOPPING = 8;

    // =========================================================================
    // Collision flags
    // =========================================================================
    /**
     * ROM: collision_flags when active — touch type $A (hurts player), size 6.
     * Bit pattern: upper nibble $A = touch-enemy, lower nibble 6 = size index.
     */
    private static final int ACTIVE_COLLISION_FLAGS = 0xA6;

    // =========================================================================
    // Animation: spin frames from Map_HCZEndBoss (indices 2–5)
    // =========================================================================
    private static final int SPIN_FRAME_A = 2;
    private static final int SPIN_FRAME_B = 3;
    private static final int SPIN_FRAME_C = 4;
    private static final int SPIN_FRAME_D = 5;
    private static final int[] SPIN_FRAMES = {SPIN_FRAME_A, SPIN_FRAME_B, SPIN_FRAME_C, SPIN_FRAME_D};

    /** Animation speed (frames per step) for the simplified steady/stop phases. */
    private static final int ANIM_SPEED_ACTIVE = 2;
    private static final int ANIM_SPEED_WIND_DOWN = 4;
    private static final int ANIM_SPEED_STOPPING = 8;

    /** Continuous SFX refresh cadence used by other HCZ fans in-engine. */
    private static final int FAN_SFX_INTERVAL = 16;

    // =========================================================================
    // Instance state
    // =========================================================================
    private final HczEndBossInstance boss;
    /** X offset from boss center (0 — turbine is directly below). */
    private int xOffset;
    /** Y offset from boss center (0x24 pixels below). */
    private int yOffset;

    private int routine;
    private int animFrame;
    private int animCounter;
    private int animSpeed;
    /** ROM byte_6BDF4 initial delay and zero-delay revolution counter. */
    private int accelerationDelay;
    private int accelerationLoops;
    /** Water column child — spawned by the acceleration-complete callback. */
    private HczEndBossWaterColumn waterColumn;

    // =========================================================================
    // Constructor
    // =========================================================================

    /**
     * @param boss    Parent boss instance
     * @param xOffset Horizontal offset from boss center
     * @param yOffset Vertical offset from boss center
     */
    public HczEndBossTurbine(HczEndBossInstance boss, int xOffset, int yOffset) {
        // priority=3, objectId=0 (dynamic child — no ROM object slot)
        super(boss, "HCZEndBossTurbine", 3, 0);
        this.boss = boss;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
        this.routine = ROUTINE_INIT;
        this.animFrame = 0;
        this.animCounter = 0;
        this.animSpeed = ANIM_SPEED_ACTIVE;
    }

    private HczEndBossTurbine(ObjectSpawn spawn, HczEndBossInstance boss, int ignored) {
        this(boss, 0, 0x24);
    }

    @Override
    public HczEndBossTurbine recreateForRewind(RewindRecreateContext ctx) {
        HczEndBossInstance restoredBoss = HczEndBossRewindLinks.nearestBoss(ctx);
        return restoredBoss == null ? null : new HczEndBossTurbine(restoredBoss, 0, 0x24);
    }

    void attachWaterColumnForRewind(HczEndBossWaterColumn restoredWaterColumn) {
        this.waterColumn = restoredWaterColumn;
    }

    // =========================================================================
    // BossChildComponent: update
    // =========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) {
            return;
        }

        // Track boss position
        currentX = boss.getState().x + xOffset;
        currentY = boss.getState().y + yOffset;
        updateDynamicSpawn();

        // Self-destruct on defeat
        if (boss.isDefeatSignal()) {
            collisionFlags = 0;
            setDestroyed(true);
            return;
        }

        switch (routine) {
            case ROUTINE_INIT -> updateInit();
            case ROUTINE_WAIT -> updateWait();
            case ROUTINE_ACTIVE -> updateActive(vIntRunCount);
            case ROUTINE_WIND_DOWN -> updateWindDown(vIntRunCount);
            case ROUTINE_STOPPING -> updateStopping();
            default -> { }
        }
    }

    // =========================================================================
    // Routine handlers
    // =========================================================================

    /**
     * ROM routine 0: apply attributes from word_6BD32 (subtype/priority/flags),
     * then immediately proceed to WAIT.
     */
    private void updateInit() {
        collisionFlags = 0;
        animFrame = 0;
        animCounter = 0;
        animSpeed = ANIM_SPEED_ACTIVE;
        accelerationDelay = 7;
        accelerationLoops = 0;
        routine = ROUTINE_WAIT;
    }

    /**
     * ROM routine 2: wait for parent bit 3 of $38 (propellerActive).
     * No collision, no animation.
     */
    private void updateWait() {
        if (boss.isPropellerActive()) {
            // Spin up: set collision, play SFX immediately, transition to ACTIVE
            collisionFlags = ACTIVE_COLLISION_FLAGS;
            accelerationDelay = 7;
            accelerationLoops = 0;
            services().playSfx(Sonic3kSfx.FAN_BIG.id);
            routine = ROUTINE_ACTIVE;
        }
    }

    /**
     * ROM routine 4: accelerate with Animate_RawGetFaster over byte_6BDF4.
     * Its loc_6B212 callback, not entry to routine 4, creates the water column
     * and advances to routine 6 (sonic3k.asm:141040-141058, 177749-177792).
     */
    private void updateActive(int vIntRunCount) {
        if ((vIntRunCount & (FAN_SFX_INTERVAL - 1)) == 0 && isOnScreen()) {
            services().playSfx(Sonic3kSfx.FAN_BIG.id);
        }

        if (tickAccelerationAnimation()) {
            routine = ROUTINE_WIND_DOWN;
            animCounter = 0;
            if (waterColumn == null || waterColumn.isDestroyed()) {
                spawnWaterColumn();
            }
        }
    }

    /**
     * Spawns the water column child via the boss's public spawn delegate.
     * ROM: loc_6B212 creates the water column after spin-up completes.
     */
    private void spawnWaterColumn() {
        waterColumn = new HczEndBossWaterColumn(boss, this);
        boss.spawnDynamicChild(() -> waterColumn);
        LOG.fine("HCZ Turbine: spawned water column");
    }

    /**
     * ROM routine 6: steady full-speed animation while propellerActive stays
     * set; clearing it advances to routine 8 (loc_6B22A/loc_6B244).
     */
    private void updateWindDown(int vIntRunCount) {
        animFrame = (animFrame + 1) % SPIN_FRAMES.length;

        if (boss.isPropellerActive()) {
            collisionFlags = ACTIVE_COLLISION_FLAGS;
            if ((vIntRunCount & (FAN_SFX_INTERVAL - 1)) == 0 && isOnScreen()) {
                services().playSfx(Sonic3kSfx.FAN_BIG.id);
            }
            return;
        }
        animSpeed = ANIM_SPEED_STOPPING;
        animCounter = 0;
        accelerationDelay = 0;
        accelerationLoops = 0;
        routine = ROUTINE_STOPPING;
    }

    /**
     * ROM routine 8: nearly stopped, collision cleared once fully wound down.
     * Returns to WAIT when animation rate hits zero.
     */
    private void updateStopping() {
        animCounter--;
        if (animCounter >= 0) {
            return;
        }

        animFrame++;
        if (animFrame >= SPIN_FRAMES.length) {
            animFrame = 0;
            accelerationDelay++;
            if (accelerationDelay >= 7) {
                accelerationDelay = 7;
                accelerationLoops++;
                if (accelerationLoops >= 2) {
                    collisionFlags = 0;
                    routine = ROUTINE_WAIT;
                    accelerationLoops = 0;
                    return;
                }
            }
        }
        animCounter = accelerationDelay;
    }

    // =========================================================================
    // Animation helpers
    // =========================================================================

    /**
     * Advance the spin animation by one counter tick.
     * Cycles through SPIN_FRAMES at the current animSpeed rate.
     */
    private void tickAnimation() {
        animCounter++;
        if (animCounter >= animSpeed) {
            animCounter = 0;
            animFrame = (animFrame + 1) % SPIN_FRAMES.length;
        }
    }

    /**
     * Exact counter shape of Animate_RawGetFaster for byte_6BDF4
     * ({@code 7,8,2,3,4,5,$FC}). Each revolution reduces the frame delay;
     * after delay zero, eight more revolutions fire the callback.
     */
    private boolean tickAccelerationAnimation() {
        animCounter--;
        if (animCounter >= 0) {
            return false;
        }

        animFrame++;
        if (animFrame >= SPIN_FRAMES.length) {
            animFrame = 0;
            if (accelerationDelay == 0) {
                accelerationLoops++;
                if (accelerationLoops >= 8) {
                    return true;
                }
            } else {
                accelerationDelay--;
            }
        }
        animCounter = accelerationDelay;
        return false;
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
        // ROM: collision_property not used for enemy-touch hazards — return 0
        return 0;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // loc_6B1A8 refreshes this child's position immediately before
        // Child_DrawTouch_Sprite2_FlickerMove adds its pointer to the response
        // list, so the next player pass observes that refreshed coordinate.
        // Once loc_6B244 selects routine 8, the player-side response pass
        // observes the frame-start child coordinate before this slot moves.
        return routine != ROUTINE_STOPPING;
    }

    // =========================================================================
    // Public accessors
    // =========================================================================

    /**
     * Returns true when the turbine is in ACTIVE or WIND_DOWN state with collision on.
     * Used by the water column child (Task 5) to determine when to spawn.
     */
    public boolean isSpinning() {
        return routine == ROUTINE_ACTIVE || routine == ROUTINE_WIND_DOWN;
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

        // Turbine is centered below the boss — no horizontal flip needed
        int frameIndex = (routine == ROUTINE_WAIT || routine == ROUTINE_INIT)
                ? SPIN_FRAME_A
                : SPIN_FRAMES[animFrame];

        renderer.drawFrameIndex(frameIndex, currentX, currentY, false, false);
    }
}
