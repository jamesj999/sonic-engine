package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * CNZ miniboss open-coil spark child.
 *
 * <p>Created by Obj_CNZMinibossOpenGo via Child1_CNZCoilOpenSparks
 * (sonic3k.asm:144950-144951,145672-145692). The three children are
 * hurt-category touch objects while the boss remains open.
 */
public final class CnzMinibossSparkInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {
    private static final int COLLISION_FLAGS = 0x92;
    private static final int SHIELD_REACTION_LIGHTNING_IMMUNITY = 1 << 5;
    private static final int[][] SPARK_FRAMES = {
            {0x0A, 0x11, 0x0B, 0x11},
            {0x11, 0x0F, 0x11, 0x10, 0x11},
            {0x0A, 0x11, 0x0B, 0x11}
    };
    private static final int[][] SPARK_DELAYS = {
            {0, 7, 0, 7},
            {0, 0, 7, 0, 0x09},
            {0, 7, 0, 0x0B}
    };
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
            TouchCategoryDecodeMode.NORMAL,
            false,
            false,
            false,
            TouchShieldDeflectCapability.NONE,
            SHIELD_REACTION_LIGHTNING_IMMUNITY,
            TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
            TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
            TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

    private CnzMinibossInstance boss;
    private int parentOffsetX;
    private int parentOffsetY;
    private final int[] frames;
    private final int[] delays;
    private int mappingFrame;
    private int rawAnimPairIndex;
    private int rawAnimTimer;
    private boolean firstAnimationTick = true;

    public CnzMinibossSparkInstance(ObjectSpawn spawn) {
        super(spawn, "CNZMinibossSpark");
        int scriptIndex = Math.min((spawn.subtype() & 0xFF) >> 1, SPARK_FRAMES.length - 1);
        frames = SPARK_FRAMES[scriptIndex];
        delays = SPARK_DELAYS[scriptIndex];
        mappingFrame = frames[0];
        rawAnimTimer = delays[0];
    }

    public void attachBossForTest(CnzMinibossInstance boss) {
        this.boss = boss;
        if (boss != null) {
            parentOffsetX = getX() - boss.getCentreX();
            parentOffsetY = getY() - boss.getCentreY();
        }
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (boss != null && (boss.isDefeatedForChild() || !boss.isOpenForTopHit())) {
            setDestroyed(true);
            return;
        }
        refreshChildPosition();
        animateRawMultiDelay();
    }

    private void refreshChildPosition() {
        if (boss == null) {
            return;
        }
        // ROM Obj_CNZMinibossSparksMain refreshes parent-relative position
        // before Animate_RawMultiDelay and Child_DrawTouch_Sprite.
        updateDynamicSpawn(boss.getCentreX() + parentOffsetX, boss.getCentreY() + parentOffsetY);
    }

    @Override
    public int getCollisionFlags() {
        // ObjDat3_CNZMinibossSpark collision byte is $92 (sonic3k.asm:145660-145663).
        return isDestroyed() ? 0 : COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return TOUCH_RESPONSE_PROFILE;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_MINIBOSS);
        if (renderer == null) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, getX(), getY(), false, false);
    }

    @Override
    public boolean isHighPriority() {
        return true;
    }

    @Override
    public int getPriorityBucket() {
        // ObjDat3_CNZMinibossSpark priority word $0200.
        return 4;
    }

    private void animateRawMultiDelay() {
        if (firstAnimationTick) {
            firstAnimationTick = false;
            return;
        }
        rawAnimTimer--;
        if (rawAnimTimer >= 0) {
            return;
        }

        rawAnimPairIndex++;
        if (rawAnimPairIndex >= frames.length) {
            rawAnimPairIndex = 0;
        }
        mappingFrame = frames[rawAnimPairIndex];
        rawAnimTimer = delays[rawAnimPairIndex];
    }

    @Override
    public String traceDebugDetails() {
        return String.format("sub=%02X off=%04X,%04X col=%02X",
                getSpawn().subtype(), parentOffsetX & 0xFFFF, parentOffsetY & 0xFFFF,
                getCollisionFlags());
    }
}
