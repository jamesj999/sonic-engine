package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * AIZ end boss propeller child (ROM: loc_69738).
 *
 * <p>Spawned by each arm child. Extends outward when fire signal is active,
 * then spawns flamethrower projectile at full extension. Retracts after firing.
 *
 * <p>State machine (4 routines):
 * <ol start="0">
 *   <li>Init: set attributes (ROM: word_69CE6)</li>
 *   <li>Wait for parent arm's fire signal</li>
 *   <li>Extend animation (step through 4 positions)</li>
 *   <li>Wait/retract delay</li>
 * </ol>
 *
 * <p>Extension positions vary by angle (ROM: byte_69B0E, sub_69AD8).
 * ROM XORs angle with $C to select one of two tables:
 * angles 0/$C share the diagonal table, angles 4/8 share the vertical table.
 */
public class AizEndBossPropellerChild extends AbstractBossChild implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizEndBossPropellerChild.class.getName());

    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_WAIT_FIRE = 2;
    /** ROM routine 4: shared animate state — counts down anim steps with 3-frame delays. */
    private static final int ROUTINE_ANIMATE = 4;
    private static final int ROUTINE_WAIT = 6;

    /**
     * ROM: byte_69B0E — per-angle extension/retraction position table.
     * Indexed by (angle + stepCounter). Each entry: {child_dx, child_dy, mapping_frame}.
     * <p>
     * During extension the step counter advances from 0 through 2 entries
     * (anim_frame starts at 1 → 2 calls to sub_69AD8 before callback).
     * After firing and the $5F wait, 3 more entries are consumed for retraction.
     */
    /**
     * ROM: byte_69B0E — position table at offset 0 (angles 0 and $C).
     * ROM sub_69AD8 XORs angle with $C: result 0 or $C → offset 0.
     */
    private static final int[][] POS_TABLE_DIAGONAL = {
            {-0x18,    8, 5},  // step 0
            {-0x18,    8, 5},  // step 1
            {-0x1C,    0, 4},  // step 2 (fully extended → fire here)
            {-0x1C,    0, 4},  // step 3 (retract start)
            {-0x18,    8, 5},  // step 4
            {-0x10, 0x10, 6},  // step 5 (retract end)
    };
    /**
     * ROM: byte_69B0E+$10 — position table at offset $10 (angles 4 and 8).
     * ROM sub_69AD8 XORs angle with $C: result neither 0 nor $C → offset $10.
     */
    private static final int[][] POS_TABLE_VERTICAL = {
            {-0x18,    8, 5},
            {-0x10, 0x10, 6},
            {-0x18,    8, 5},
            {-0x1C,    0, 4},
    };
    /** ROM: sub_69AD8 — angle→table mapping via XOR $C. Angles 0/$C share one table, 4/8 share another. */
    private static final int[][][] POS_TABLES = {
            POS_TABLE_DIAGONAL, POS_TABLE_VERTICAL, POS_TABLE_VERTICAL, POS_TABLE_DIAGONAL
    };
    private final AizEndBossInstance boss;
    private final AizEndBossArmChild arm;
    // Non-final so the rewind field capturer reapplies it after the recreate
    // hook rebuilds the propeller with a placeholder subtype.
    private int subtype;
    private int routine;
    private int mappingFrame;
    /**
     * ROM: $39(a0) — position table step counter.
     * Incremented by each call to advancePosition() (ROM: sub_69AD8).
     * Reset to 0 when returning to idle.
     */
    private int stepCounter;
    /**
     * ROM: anim_frame — number of animate steps remaining.
     * When this goes below 0, the current callback is invoked.
     */
    private int animStepsRemaining;
    /** ROM: $2E(a0) — frame countdown within the animate state. */
    private int waitTimer;
    private int childDx;
    private int childDy;
    private boolean fireWindowConsumed;
    /**
     * ROM: $3A(a0) — Knuckles extra-fire counter.
     * Knuckles fires multiple times; Sonic fires once.
     */
    private int knuxFireCounter;
    /** Current callback to invoke when animStepsRemaining goes below 0. */
    private AnimCallback animCallback;

    private enum AnimCallback {
        NONE,
        ON_EXTEND_COMPLETE,
        ON_FIRE_TIMER_COMPLETE,
        ON_RETRACT_COMPLETE
    }

    public AizEndBossPropellerChild(AizEndBossInstance boss, AizEndBossArmChild arm, int subtype) {
        super(boss, "AIZEndBossPropeller", 3, 0);
        this.boss = boss;
        this.arm = arm;
        this.subtype = subtype;
        this.routine = ROUTINE_INIT;
        this.mappingFrame = 4;
        this.stepCounter = 0;
        this.animStepsRemaining = 0;
        this.waitTimer = -1;
        this.childDx = -0x1C;
        this.childDy = 0;
        this.fireWindowConsumed = false;
        this.knuxFireCounter = 0;
        this.animCallback = AnimCallback.NONE;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizEndBossInstance restoredBoss = AizEndBossRewindLinks.nearestBoss(ctx);
        AizEndBossArmChild nearestArm = AizEndBossRewindLinks.nearestArm(ctx);
        int capturedSubtype = AizEndBossRewindLinks.capturedPropellerSubtype(ctx, restoredBoss, nearestArm);
        AizEndBossArmChild restoredArm = AizEndBossRewindLinks.nearestArm(ctx, capturedSubtype);
        if (restoredBoss == null || restoredArm == null) {
            return null;
        }
        AizEndBossPropellerChild restored =
                new AizEndBossPropellerChild(restoredBoss, restoredArm, capturedSubtype);
        if (ctx.spawn() != null) {
            restored.setPosition(ctx.spawn().x(), ctx.spawn().y());
            restored.updateDynamicSpawn();
        }
        AizEndBossRewindLinks.seedCapturedScalars(restored, ctx);
        restoredArm.rewindAttachPropeller(restored);
        return restored;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!beginUpdate(vIntRunCount)) return;

        // ROM Refresh_ChildPositionAdjusted negates child_dx when the parent is X-flipped.
        int signedDx = boss.isFacingRight() ? -childDx : childDx;
        setPosition(arm.getX() + signedDx, arm.getY() + childDy);

        if (boss.isDefeatSignal()) {
            setDestroyed(true);
            return;
        }
        if (boss.isHidden()) return;

        switch (routine) {
            case ROUTINE_INIT -> {
                routine = ROUTINE_WAIT_FIRE;
            }
            case ROUTINE_WAIT_FIRE -> {
                // ROM routine 2: wait for boss flag #1 (propeller fire requested)
                if (!boss.isPropellerFireRequested()) {
                    fireWindowConsumed = false;
                } else if (!fireWindowConsumed) {
                    fireWindowConsumed = true;
                    beginExtend();
                }
            }
            case ROUTINE_ANIMATE -> {
                // ROM routine 4 (loc_697BA): shared animate state.
                // Count down waitTimer; when expired, advance position and
                // decrement animStepsRemaining. When that goes below 0, fire callback.
                if (waitTimer >= 0) {
                    waitTimer--;
                    return;
                }
                // Timer expired — advance one step
                waitTimer = 3; // ROM: move.w #3,$2E(a0)
                advancePosition();
                animStepsRemaining--;
                if (animStepsRemaining < 0) {
                    runAnimCallback();
                }
            }
            case ROUTINE_WAIT -> {
                // ROM routine 6: Obj_Wait — count down $2E, then invoke callback.
                // Unlike ROUTINE_ANIMATE, this does NOT call advancePosition().
                if (waitTimer >= 0) {
                    waitTimer--;
                } else {
                    runAnimCallback();
                }
            }
        }
    }

    /**
     * ROM: loc_69782 — Begin extend sequence.
     * Sets up routine 4 with anim_frame=1 (2 sub_69AD8 calls before callback).
     */
    private void beginExtend() {
        routine = ROUTINE_ANIMATE;
        animStepsRemaining = 1;   // ROM: move.b #1,anim_frame(a0)
        knuxFireCounter = 1;      // ROM: move.b #1,$3A(a0) — ALWAYS 1
        // ROM: initial delay depends on parent ARM's subtype, not character.
        // subtype 0 (left arm) = no delay, subtype != 0 (right arm) = $4F delay.
        waitTimer = (subtype != 0) ? 0x4F : 0;
        animCallback = AnimCallback.ON_EXTEND_COMPLETE;
    }

    /**
     * ROM: loc_697D8 — Extension/fire-cycle callback.
     * <p>
     * Called when the animate routine's anim_frame goes negative, AND when
     * the Obj_Wait countdown expires during a Knuckles re-fire cycle.
     * <p>
     * ROM flow:
     * <ol>
     *   <li>Set routine to 6 (WAIT) — always, before the character check.</li>
     *   <li>If Knuckles: decrement $3A. If still ≥ 0, set $2E=$2F, spawn
     *       projectile, and leave callback pointing HERE (loop). If $3A went
     *       negative, fall through to the Sonic retract path.</li>
     *   <li>Sonic / final Knuckles fire: clear parent flag, set $2E=$5F,
     *       callback = loc_69824, spawn projectile.</li>
     * </ol>
     */
    private void onExtendComplete() {
        // ROM: loc_697D8 — ALWAYS sets routine 6 first
        routine = ROUTINE_WAIT;  // ROM: move.b #6,routine(a0)

        // ROM: cmpi.b #2,(Player_1+character_id).w  /  beq.s loc_69802
        // The multi-fire check is based on PLAYER CHARACTER, not arm subtype.
        boolean isKnuckles = (boss.getPlayerCharacter() == com.openggf.game.PlayerCharacter.KNUCKLES);
        if (isKnuckles) {
            // Knuckles path (loc_69802)
            knuxFireCounter--;  // ROM: subq.b #1,$3A(a0)
            if (knuxFireCounter >= 0) {
                // $3A still non-negative: fire again after $2F wait
                waitTimer = 0x2F;               // ROM: move.w #$2F,$2E(a0)
                animCallback = AnimCallback.ON_EXTEND_COMPLETE; // callback stays at loc_697D8
                spawnFlameProjectile();          // ROM: bra.w loc_69B5E
                return;
            }
            // $3A went negative (bmi): fall through to Sonic/retract path
        }

        // Sonic path / final Knuckles fire (loc_697E6)
        boss.clearPropellerFire();               // ROM: bclr #1,$38(a1)
        waitTimer = 0x5F;                        // ROM: move.w #$5F,$2E(a0)
        animCallback = AnimCallback.ON_FIRE_TIMER_COMPLETE; // ROM: move.l #loc_69824,$34(a0)
        spawnFlameProjectile();                   // ROM: bra.w loc_69B5E
    }

    /**
     * ROM: loc_69824 — Fire timer complete, begin retraction animation.
     * Returns to routine 4 with anim_frame=1 (2 more position steps before idle).
     */
    private void onFireTimerComplete() {
        routine = ROUTINE_ANIMATE;
        animStepsRemaining = 1; // ROM: move.b #1,anim_frame(a0)
        waitTimer = 0; // ROM: clr.w $2E(a0)
        animCallback = AnimCallback.ON_RETRACT_COMPLETE;
    }

    /**
     * ROM: loc_69812 — Retraction complete, return to idle.
     * Resets step counter and child position to idle defaults so the propeller
     * doesn't retain a stale diagonal offset during the submerge/emerge cycle.
     */
    private void onRetractComplete() {
        routine = ROUTINE_WAIT_FIRE;
        stepCounter = 0; // ROM: clr.b $39(a0)
        // Restore idle position — prevents visible diagonal offset between
        // submerge and the next fire cycle (ROM: child_dx/child_dy persist but
        // the boss is hidden, so the stale values are never seen).
        childDx = -0x1C;
        childDy = 0;
        mappingFrame = 4;
    }

    private void runAnimCallback() {
        if (animCallback == AnimCallback.NONE) {
            return;
        }
        AnimCallback callback = animCallback;
        animCallback = AnimCallback.NONE;
        switch (callback) {
            case ON_EXTEND_COMPLETE -> onExtendComplete();
            case ON_FIRE_TIMER_COMPLETE -> onFireTimerComplete();
            case ON_RETRACT_COMPLETE -> onRetractComplete();
            case NONE -> {}
        }
    }

    /** ROM: sub_69AD8 — Advance position from the angle-based table. */
    private void advancePosition() {
        int angleIndex = boss.getAngle() / 4;
        if (angleIndex < 0 || angleIndex >= POS_TABLES.length) angleIndex = 0;
        int[][] table = POS_TABLES[angleIndex];
        int step = stepCounter;
        if (step >= 0 && step < table.length) {
            childDx = table[step][0];
            childDy = table[step][1];
            mappingFrame = table[step][2];
        }
        stepCounter++;
    }

    /** Spawn a flame projectile child. */
    private void spawnFlameProjectile() {
        if (services().objectManager() != null) {
            spawnChild(() -> new AizEndBossFlameChild(boss, this, boss.getAngle()));
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed() || boss.isHidden()) return;

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) return;

        boolean hFlip = boss.isFacingRight();
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), hFlip, false);
    }

    public int getCollisionFlags() {
        return 0; // Propellers are not collidable
    }

    @Override
    public boolean isHighPriority() {
        return boss.isHighPriority();
    }

    int rewindSubtype() {
        return subtype;
    }
}
