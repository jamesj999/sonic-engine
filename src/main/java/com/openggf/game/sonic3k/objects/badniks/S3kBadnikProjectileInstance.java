package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Shared projectile object for S3K badniks that use Map_Bloominator / Map_MonkeyDude
 * projectile frames and hurt-category touch flags.
 */
public final class S3kBadnikProjectileInstance
        extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final int SHIELD_REACTION_BOUNCE = 1 << 3;
    private static final int DEFLECT_SPEED = 0x800;
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
            new com.openggf.game.profiles.touchresponse.TouchResponseProfile(
                    com.openggf.game.profiles.touchresponse.TouchCategoryDecodeMode.NORMAL,
                    false,
                    true,
                    false,
                    com.openggf.game.profiles.touchresponse.TouchShieldDeflectCapability.SHIELD_DEFLECT,
                    SHIELD_REACTION_BOUNCE,
                    com.openggf.game.profiles.touchresponse.TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                    com.openggf.game.profiles.touchresponse.TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                    com.openggf.game.profiles.touchresponse.TouchOverlapStopPolicy
                            .STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS));

    // These fields are made non-final (not derivable from the captured
    // ObjectSpawn, which only carries position + the parent badnik's
    // id/subtype/render flags) so GenericFieldCapturer reapplies their exact
    // values after a rewind recreate from forRewindRecreate(...) placeholders.
    private String rendererKey;
    private int mappingFrame;
    private int collisionSizeIndex;
    private int priorityBucket;
    private boolean hFlip;
    private int gravity;

    private int currentX;
    private int currentY;
    private int xVelocity;
    private int yVelocity;
    private int xSubpixel;
    private int ySubpixel;
    private boolean collisionEnabled = true;

    S3kBadnikProjectileInstance(ObjectSpawn ownerSpawn,
            String rendererKey,
            int mappingFrame,
            int x,
            int y,
            int xVelocity,
            int yVelocity,
            int gravity,
            int collisionSizeIndex,
            int priorityBucket,
            boolean hFlip) {
        super(ownerSpawn, "S3kBadnikProjectile");
        this.rendererKey = rendererKey;
        this.mappingFrame = mappingFrame;
        this.currentX = x;
        this.currentY = y;
        this.xVelocity = xVelocity;
        this.yVelocity = yVelocity;
        this.gravity = gravity;
        this.collisionSizeIndex = collisionSizeIndex;
        this.priorityBucket = priorityBucket;
        this.hFlip = hFlip;
    }

    private S3kBadnikProjectileInstance(ObjectSpawn spawn) {
        this(spawn, "", 0, spawn.x(), spawn.y(), 0, 0, 0, 0, 0, false);
    }

    /**
     * Rewind recreate factory. Generic recreate only needs a structurally-valid
     * instance positioned at the captured spawn; the non-final differentiator
     * fields (rendererKey, mappingFrame, gravity, collisionSizeIndex,
     * priorityBucket, hFlip) and the in-flight motion scalars are reapplied by
     * the generic field capturer immediately after recreate, so placeholders
     * are passed here. {@code appendRenderCommands} tolerates the empty
     * rendererKey for the single pre-reapply frame (getRenderer returns null).
     */
    public static S3kBadnikProjectileInstance forRewindRecreate(ObjectSpawn spawn) {
        return new S3kBadnikProjectileInstance(spawn, "", 0, spawn.x(), spawn.y(), 0, 0, 0, 0, 0, false);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn spawn = ctx.spawn() != null
                ? ctx.spawn()
                : new ObjectSpawn(0, 0, 0, 0, 0, false, 0);
        return forRewindRecreate(spawn);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // MoveSprite / MoveSprite_LightGravity order:
        // use old y_vel for movement this frame, then apply gravity.
        int oldYVel = yVelocity;
        yVelocity += gravity;

        int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
        int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
        xPos24 += xVelocity;
        yPos24 += oldYVel;
        currentX = xPos24 >> 8;
        currentY = yPos24 >> 8;
        xSubpixel = xPos24 & 0xFF;
        ySubpixel = yPos24 & 0xFF;

        if (!isOnScreen(48)) {
            setDestroyed(true);
        }
    }

    @Override
    public int getCollisionFlags() {
        if (!collisionEnabled) {
            return 0;
        }
        // HURT category + collision size index (ObjDat3 flags: $98).
        return 0x80 | (collisionSizeIndex & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // loc_86D5E runs the child motion callback before
        // Sprite_CheckDeleteTouchXY adds the pointer to the collision-response
        // list. The next player slot therefore observes that post-movement
        // position, as with Obj37 bouncing rings.
        return true;
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
    public ObjectSpawn getSpawn() {
        return buildSpawnAt(currentX, currentY);
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    @Override
    public int getShieldReactionFlags() {
        return SHIELD_REACTION_BOUNCE;
    }

    @Override
    public boolean onShieldDeflect(PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return false;
        }

        int dx = player.getCentreX() - currentX;
        int dy = player.getCentreY() - currentY;
        int angle = TrigLookupTable.calcAngle(saturateToShort(dx), saturateToShort(dy));

        // ROM: Touch_ChkHurt_Bounce_Projectile / ShieldTouchResponse
        // d1 -> x_vel, d0 -> y_vel after GetSineCosine.
        xVelocity = -((TrigLookupTable.cosHex(angle) * DEFLECT_SPEED) >> 8);
        yVelocity = -((TrigLookupTable.sinHex(angle) * DEFLECT_SPEED) >> 8);
        collisionEnabled = false;
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(rendererKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        renderer.drawFrameIndex(mappingFrame, currentX, currentY, hFlip, false);
    }

    private static short saturateToShort(int value) {
        if (value > Short.MAX_VALUE) {
            return Short.MAX_VALUE;
        }
        if (value < Short.MIN_VALUE) {
            return Short.MIN_VALUE;
        }
        return (short) value;
    }
}
