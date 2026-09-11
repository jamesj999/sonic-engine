package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;
import java.util.logging.Logger;

/**
 * AIZ end boss flamethrower projectile (ROM: loc_69844).
 *
 * <p>Spawned by each propeller at full extension. Animates flame burst
 * then spawns a bomb projectile that falls with gravity.
 *
 * <p>ROM attributes: word_69CEC — priority $100, size 8x4, frame $B,
 * collision $97. Shield reaction: fire shield immune (bit 4).
 *
 * <p>Animation selects by angle:
 * - Angles 0/$C: byte_69DC9 (frames 7,8,9,$A)
 * - Angles 4/8: byte_69DF3 (frames $B,$C,$D,$E)
 */
public class AizEndBossFlameChild extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final Logger LOG = Logger.getLogger(AizEndBossFlameChild.class.getName());
    private static final int SHIELD_REACTION_FIRE = 1 << 4;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            true,
            false,
            TouchShieldDeflectCapability.NONE,
            SHIELD_REACTION_FIRE,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    private static final int COLLISION_FLAGS = 0x97; // Hurts player, size index $17
    /**
     * Frames the flame runs before it spawns its bomb.
     *
     * <p>Not approximate: it is the length of the flame's own animation script.
     * {@code AIZEndBossFlame_Init} stores the by-angle script in {@code $30(a0)}
     * and {@code AIZEndBossFlame_SpawnBomb} in {@code $34(a0)}
     * (docs/skdisasm/sonic3k.asm:138579-138591), and
     * {@code AIZEndBossFlame_Main} steps it with {@code Animate_Raw}
     * (:138606-138611). That animator is the shared-delay form
     * ({@code Animate_RawNoSST}, :177333-177352): the script's FIRST byte is one
     * delay for the whole script and the rest is a flat frame list, walked one
     * byte per advance.
     *
     * <p>Both scripts -- {@code AniRaw_AIZEndBossFlame_Diagonal} and
     * {@code _Vertical} (:139123-139168) -- open with a delay byte of {@code 0},
     * so each entry lasts one frame, and each carries exactly 20 pairs, i.e.
     * <b>40 frame bytes</b>, before its {@code $F4} terminator. The terminator
     * invokes {@code $34}, which is what spawns the bomb.
     *
     * <p>So 40 is the script's own length rather than a chosen number, and the
     * flame's end is a script terminator invoking a stored callback -- the same
     * structure as the boss's emerge.
     */
    private static final int FLAME_DURATION = 40;
    private static final int[][] FLAME_OFFSETS = {
            {0x03, 0x05},
            {0x00, 0x07},
            {0x00, 0x07},
            {-0x03, 0x05}
    };
    private final AizEndBossInstance boss;
    private final AizEndBossPropellerChild propeller;
    // angle and its derived offsets are non-final so the rewind field capturer
    // reapplies them after the recreate hook rebuilds the flame with placeholder angle 0.
    private int angle;
    private int offsetX;
    private int offsetY;
    private int currentX;
    private int currentY;
    private int animTimer;
    private int mappingFrame;
    private boolean faceRight;

    public AizEndBossFlameChild(AizEndBossInstance boss,
                                AizEndBossPropellerChild propeller, int angle) {
        super(buildSpawnAt(propeller.getX(), propeller.getY(), boss), "AIZEndBossFlame");
        this.boss = boss;
        this.propeller = propeller;
        this.angle = angle;
        int angleIndex = angle / 4;
        this.offsetX = FLAME_OFFSETS[angleIndex][0];
        this.offsetY = FLAME_OFFSETS[angleIndex][1];
        this.currentX = propeller.getX() + offsetX;
        this.currentY = propeller.getY() + offsetY;
        this.animTimer = 0;
        this.faceRight = (angle < 8);

        // Select initial frame based on angle
        boolean vertical = (angle == 4 || angle == 8);
        this.mappingFrame = vertical ? 0x0B : 0x07;
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        AizEndBossInstance restoredBoss = AizEndBossRewindLinks.nearestBoss(ctx);
        AizEndBossPropellerChild restoredPropeller = AizEndBossRewindLinks.nearestPropeller(ctx);
        if (restoredBoss == null || restoredPropeller == null) {
            return null;
        }
        AizEndBossFlameChild restored = new AizEndBossFlameChild(restoredBoss, restoredPropeller, 0);
        AizEndBossRewindLinks.seedCapturedScalars(restored, ctx);
        return restored;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) return;

        // Check if parent boss is defeated
        if (boss.isDefeatSignal()) {
            setDestroyed(true);
            return;
        }

        animTimer++;

        if (!propeller.isDestroyed()) {
            currentX = propeller.getX() + offsetX;
            currentY = propeller.getY() + offsetY;
        }
        updateDynamicSpawn(currentX, currentY);

        // Animate flame burst (ROM: byte_69DC9 / byte_69DF3)
        boolean vertical = (angle == 4 || angle == 8);
        if (vertical) {
            // Frames: $B, $C, $D, $E cycling
            int phase = (animTimer / 4) % 4;
            mappingFrame = 0x0B + phase;
        } else {
            // Frames: 7, 8, 9, $A cycling
            int phase = (animTimer / 4) % 4;
            mappingFrame = 0x07 + phase;
        }

        // After flame duration, spawn bomb and delete (ROM: loc_698BC)
        if (animTimer >= FLAME_DURATION) {
            spawnBomb();
            setDestroyed(true);
        }
    }

    /** ROM: ChildObjDat_69D56 — Spawn bomb projectile at flame position. */
    private void spawnBomb() {
        if (services().objectManager() == null) return;

        spawnChild(() -> new AizEndBossBombChild(boss, currentX, currentY, angle));
    }

    @Override
    public int getCollisionFlags() {
        return COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public int getShieldReactionFlags() {
        return SHIELD_REACTION_FIRE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public int getX() { return currentX; }

    @Override
    public int getY() { return currentY; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) return;

        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.AIZ_END_BOSS);
        if (renderer == null || !renderer.isReady()) return;

        renderer.drawFrameIndex(mappingFrame, currentX, currentY, faceRight, false);
    }

    @Override
    public boolean isHighPriority() { return true; }

    @Override
    public int getPriorityBucket() { return 2; }

    private static ObjectSpawn buildSpawnAt(int x, int y, AizEndBossInstance boss) {
        int objectId = boss != null && boss.getSpawn() != null ? boss.getSpawn().objectId() : 0x92;
        return new ObjectSpawn(x, y, objectId, 0, 0, false, 0);
    }
}
