package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * Flame child spawned by {@link LbzFlameThrowerObjectInstance}.
 *
 * <p>ROM reference: {@code Obj_AutoSpin460} ({@code sonic3k.asm:52089-52104})
 * plus {@code Ani_LBZFlameThrower}. The child animates the single ROM sequence
 * and uses {@code collision_flags=$9D} with fire-shield reaction bit 4 set.
 */
public final class LbzFlameThrowerFlameInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {
    private static final int COLLISION_FLAGS = 0x9D;
    private static final int SHIELD_REACTION_FIRE = 1 << 4;
    private static final int PRIORITY_BUCKET = 3; // sub_263AA: priority=$200
    private static final int ANIMATION_DELAY = 4; // Ani_LBZFlameThrower starts with dc.b 3.
    private static final int[] ANIMATION_FRAMES = {1, 2, 3, 4, 5, 3, 4, 6, 7, 8};
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

    private int x;
    private int y;
    private boolean hFlip;
    private int animationIndex;
    private int animationTicks = ANIMATION_DELAY;

    public LbzFlameThrowerFlameInstance(ObjectSpawn spawn) {
        super(spawn, "LBZFlameThrowerFlame");
        this.x = spawn.x();
        this.y = spawn.y();
        this.hFlip = (spawn.renderFlags() & 0x01) != 0;
        updateDynamicSpawn(x, y);
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }

        animationTicks--;
        if (animationTicks > 0) {
            return;
        }

        animationTicks = ANIMATION_DELAY;
        animationIndex++;
        if (animationIndex >= ANIMATION_FRAMES.length) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_FLAME_THROWER);
        if (renderer != null) {
            renderer.drawFrameIndex(getMappingFrameForTesting(), x, y, hFlip, false);
        }
    }

    @Override
    public int getCollisionFlags() {
        return isDestroyed() ? 0 : COLLISION_FLAGS;
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
    public int getPriorityBucket() {
        return RenderPriority.clamp(PRIORITY_BUCKET);
    }

    public int getCentreX() {
        return x;
    }

    public int getCentreY() {
        return y;
    }

    public int getMappingFrameForTesting() {
        if (animationIndex >= ANIMATION_FRAMES.length) {
            return ANIMATION_FRAMES[ANIMATION_FRAMES.length - 1];
        }
        return ANIMATION_FRAMES[animationIndex];
    }
}
