package com.openggf.game.sonic3k.objects;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kLevelTriggerManager;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.constants.Sonic3kAnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Object 0x13 - LBZ Exploding Trigger.
 *
 * <p>ROM: {@code Obj_LBZExplodingTrigger} (sonic3k.asm:51421-51456).
 * Touch_Special sets {@code collision_property}; the object consumes P1/P2
 * bits, checks {@code anim(a1) == 2}, negates both velocity components,
 * toggles {@code Level_trigger_array[subtype & $F]} bit 0, then rewrites this
 * same slot to {@code Obj_Explosion}.
 */
public final class LbzExplodingTriggerInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseListener, SpawnRewindRecreatable {

    // ROM: collision_flags(a0) = $C6. In S3K, $C0 + size $06 is a Touch_Special
    // property latch, not boss collision.
    private static final int COLLISION_FLAGS = 0xC6;
    private static final int EXPLOSION_RELOAD_DURATION = 7;
    private static final int EXPLOSION_FINAL_MAPPING_FRAME = 5;
    private static final int PRIORITY_BUCKET = 5; // ROM priority(a0) = $280

    private int triggerIndex;
    private int collisionProperty;
    private boolean exploding;
    private int explosionFrame;
    private int explosionFrameDuration = -1;

    public LbzExplodingTriggerInstance(ObjectSpawn spawn) {
        super(spawn, "LBZExplodingTrigger");
        this.triggerIndex = spawn.subtype() & 0x0F;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (exploding) {
            updateExplosion();
            return;
        }
        int pending = collisionProperty;
        if (pending == 0) {
            return;
        }

        // ROM loc_25CF0 consumes each player bit with bclr before testing anim.
        collisionProperty = 0;
        if ((pending & 0x01) != 0 && playerEntity instanceof AbstractPlayableSprite player) {
            tryTriggerFrom(player);
        }
        if (!exploding && (pending & 0x02) != 0) {
            List<PlayableEntity> participants = playerQuery(playerEntity)
                    .playersFor(ObjectPlayerParticipationPolicy.NATIVE_P1_P2);
            for (int i = 1; i < participants.size(); i++) {
                if (participants.get(i) instanceof AbstractPlayableSprite sprite) {
                    tryTriggerFrom(sprite);
                    if (exploding) {
                        break;
                    }
                }
            }
        }
    }

    @Override
    public int getCollisionFlags() {
        return exploding ? 0 : COLLISION_FLAGS;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile() {
        return getTouchResponseProfile(false);
    }

    @Override
    public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return new TouchResponseProfile(
                TouchCategoryDecodeMode.S3K_SPECIAL_PROPERTY,
                false,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);
    }

    @Override
    public void onTouchResponse(PlayableEntity player, TouchResponseResult result, int frameCounter) {
        if (exploding || player == null) {
            return;
        }
        // ROM Touch_Special sets bit 0 for Player_1 and bit 1 for native Player_2.
        collisionProperty |= isNativeP2(player) ? 0x02 : 0x01;
    }

    @Override
    public int getPriorityBucket() {
        return PRIORITY_BUCKET;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        if (exploding) {
            var renderManager = tryServices() != null ? tryServices().renderManager() : null;
            if (renderManager != null && renderManager.getExplosionRenderer() != null) {
                renderManager.getExplosionRenderer().drawFrameIndex(explosionFrame, spawn.x(), spawn.y(), false, false);
            }
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.LBZ_EXPLODING_TRIGGER);
        if (renderer != null) {
            boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
            boolean vFlip = (spawn.renderFlags() & 0x02) != 0;
            renderer.drawFrameIndex(0, spawn.x(), spawn.y(), hFlip, vFlip);
        }
    }

    boolean isExplodingForTest() {
        return exploding;
    }

    private void tryTriggerFrom(AbstractPlayableSprite player) {
        // ROM sub_25D2C: cmpi.b #2,anim(a1)
        if (player == null || player.getAnimationId() != Sonic3kAnimationIds.ROLL.id()) {
            return;
        }

        player.setXSpeed((short) -player.getXSpeed());
        player.setYSpeed((short) -player.getYSpeed());
        toggleTriggerBit();
        exploding = true;
        collisionProperty = 0;
        explosionFrame = 0;
        explosionFrameDuration = -1;
    }

    private void toggleTriggerBit() {
        if (Sonic3kLevelTriggerManager.testBit(triggerIndex, 0)) {
            Sonic3kLevelTriggerManager.clearBit(triggerIndex, 0);
        } else {
            Sonic3kLevelTriggerManager.setBit(triggerIndex, 0);
        }
    }

    private void updateExplosion() {
        if (explosionFrameDuration < 0) {
            explosionFrameDuration = resolveExplosionInitialDuration();
        }
        explosionFrameDuration--;
        if (explosionFrameDuration >= 0) {
            return;
        }
        explosionFrameDuration = EXPLOSION_RELOAD_DURATION;
        explosionFrame++;
        if (explosionFrame >= EXPLOSION_FINAL_MAPPING_FRAME) {
            ObjectLifetimeOps.expireDynamic(this);
        }
    }

    private ObjectPlayerQuery playerQuery(PlayableEntity primary) {
        ObjectPlayerQuery query = services().playerQuery();
        return new ObjectPlayerQuery(() -> primary, query::sidekicks);
    }

    private int resolveExplosionInitialDuration() {
        try {
            var ctx = tryServices();
            if (ctx != null && ctx.gameModule() != null) {
                return ctx.gameModule().explosionInitialAnimDuration();
            }
        } catch (Exception ignored) {
            // Tests can instantiate the object without a module; S2/S3K default is 3.
        }
        return 3;
    }

    private boolean isNativeP2(PlayableEntity player) {
        try {
            var ctx = tryServices();
            if (ctx != null) {
                PlayableEntity nativeP2 = ctx.playerQuery().nativeP2OrNull();
                return nativeP2 == player;
            }
        } catch (Exception ignored) {
            // Isolated tests may construct the object without full runtime services.
        }
        return player.isCpuControlled();
    }
}
