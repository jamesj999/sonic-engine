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
 * AIZ end boss arm/shoulder child (ROM: loc_69622).
 *
 * <p>Two instances spawned: left arm (subtype 0, offset +$14,-4) and right arm
 * (subtype 1, offset -$14,-4). Each arm spawns a propeller child that extends
 * and fires flamethrower projectiles when signaled by the parent boss.
 *
 * <p>State machine (6 routines):
 * <ol start="0">
 *   <li>Init: set attributes, spawn propeller child</li>
 *   <li>Wait for parent reveal (bit 3 of parent $38)</li>
 *   <li>Short delay before arm opens</li>
 *   <li>Animate arm opening (ROM: byte_69DBE)</li>
 *   <li>Wait for fire signal (parent bit 1 of $38)</li>
 *   <li>Wait for retract signal (parent bit 1 cleared)</li>
 * </ol>
 */
public class AizEndBossArmChild extends AbstractBossChild implements RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizEndBossArmChild.class.getName());

    private static final int ROUTINE_INIT = 0;
    private static final int ROUTINE_WAIT_REVEAL = 2;
    private static final int ROUTINE_SHORT_DELAY = 4;
    private static final int ROUTINE_ANIMATE_OPEN = 6;
    private static final int ROUTINE_WAIT_FIRE = 8;
    private static final int ROUTINE_WAIT_RETRACT = 10;
    private final AizEndBossInstance boss;
    // Non-final so the generic rewind field capturer reapplies them after the
    // recreate hook passes placeholder 0/0/0.
    private int offsetX;
    private int offsetY;
    private int subtype;
    private int routine;
    private int mappingFrame;
    private int waitTimer;

    // Propeller child
    private AizEndBossPropellerChild propeller;

    public AizEndBossArmChild(AizEndBossInstance boss, int offsetX, int offsetY, int subtype) {
        super(boss, "AIZEndBossArm", 4, 0);
        this.boss = boss;
        this.offsetX = offsetX;
        this.offsetY = offsetY;
        this.subtype = subtype;
        this.routine = ROUTINE_INIT;
        this.mappingFrame = (subtype == 0) ? 1 : 0x2A;
        this.waitTimer = -1;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizEndBossInstance restoredBoss = AizEndBossRewindLinks.nearestBoss(ctx);
        if (restoredBoss == null) {
            return null;
        }
        AizEndBossArmChild restored = new AizEndBossArmChild(restoredBoss, 0, 0, 0);
        if (ctx.spawn() != null) {
            restored.setPosition(ctx.spawn().x(), ctx.spawn().y());
            restored.updateDynamicSpawn();
        }
        AizEndBossRewindLinks.seedCapturedScalars(restored, ctx);
        restoredBoss.rewindAttachArmChild(restored);
        return restored;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (!shouldUpdate(vIntRunCount)) return;

        // ROM Refresh_ChildPositionAdjusted negates child_dx when the parent is X-flipped.
        int signedOffsetX = boss.isFacingRight() ? -offsetX : offsetX;
        setPosition(boss.getX() + signedOffsetX, boss.getY() + offsetY);

        // Check parent defeat
        if (boss.isDefeatSignal()) {
            setDestroyed(true);
            if (propeller != null) {
                propeller.setDestroyed(true);
            }
            return;
        }

        // If parent is hidden, don't process further
        if (boss.isHidden()) return;

        switch (routine) {
            case ROUTINE_INIT -> {
                // Spawn propeller child (ROM: ChildObjDat_69D26, offset -$1C, 0)
                if (services().objectManager() != null) {
                    propeller = spawnChild(() -> new AizEndBossPropellerChild(boss, this, subtype));
                }
                routine = ROUTINE_WAIT_REVEAL;
            }
            case ROUTINE_WAIT_REVEAL -> {
                // ROM checks parent bit 3, which is latched when the emerge cycle starts.
                if (boss.hasEmergeStarted()) {
                    if (subtype == 0) {
                        routine = ROUTINE_SHORT_DELAY;
                        waitTimer = 4;
                    } else {
                        routine = ROUTINE_WAIT_FIRE;
                    }
                }
            }
            case ROUTINE_SHORT_DELAY -> {
                if (waitTimer > 0) {
                    waitTimer--;
                } else {
                    routine = ROUTINE_ANIMATE_OPEN;
                }
            }
            case ROUTINE_ANIMATE_OPEN -> {
                // ROM: byte_69DBE — animate arm opening
                // Simplified: show open frame then transition
                mappingFrame = 1;
                routine = ROUTINE_WAIT_FIRE;
            }
            case ROUTINE_WAIT_FIRE -> {
                // Wait for parent's fire signal
                if (boss.isPropellerFireRequested()) {
                    routine = ROUTINE_WAIT_RETRACT;
                }
            }
            case ROUTINE_WAIT_RETRACT -> {
                // Wait for parent to clear fire signal
                if (!boss.isPropellerFireRequested()) {
                    routine = ROUTINE_WAIT_REVEAL;
                }
            }
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
        return 0; // Arms are not collidable
    }

    @Override
    public boolean isHighPriority() {
        return boss.isHighPriority();
    }

    public AizEndBossPropellerChild getPropeller() {
        return propeller;
    }

    void rewindAttachPropeller(AizEndBossPropellerChild propeller) {
        this.propeller = propeller;
    }

    int rewindSubtype() {
        return subtype;
    }
}
