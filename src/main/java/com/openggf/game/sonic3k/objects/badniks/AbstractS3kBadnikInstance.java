package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.objects.Sonic3kPointsObjectInstance;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractBadnikInstance;
import com.openggf.level.objects.AnimalObjectInstance;
import com.openggf.level.objects.DestructionEffects;
import com.openggf.level.objects.DestructionEffects.DestructionConfig;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.TouchResponseAttackable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Shared S3K badnik behavior: touch response, defeat handling, dynamic
 * position/collision, and sprite rendering.
 */
abstract class AbstractS3kBadnikInstance extends AbstractBadnikInstance
        implements TouchResponseProvider, TouchResponseAttackable {

    private final String rendererKey;
    private int collisionSizeIndex;
    private int priorityBucket;
    private boolean planePriority;

    protected int xSubpixel;
    protected int ySubpixel;
    protected int mappingFrame;

    protected AbstractS3kBadnikInstance(ObjectSpawn spawn, String name,
            String rendererKey, int collisionSizeIndex, int priorityBucket) {
        this(spawn, name, rendererKey, collisionSizeIndex, priorityBucket, false);
    }

    protected AbstractS3kBadnikInstance(ObjectSpawn spawn, String name,
            String rendererKey, int collisionSizeIndex, int priorityBucket,
            boolean planePriority) {
        super(spawn, name, S3K_DESTRUCTION_CONFIG);
        this.rendererKey = rendererKey;
        this.collisionSizeIndex = collisionSizeIndex;
        this.priorityBucket = priorityBucket;
        this.planePriority = planePriority;
        // S3K render_flags bit 0 mirrors horizontally. Clear = face left, set = face right.
        this.facingLeft = (spawn.renderFlags() & 0x01) == 0;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return collisionSizeIndex;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        defeat(player);
    }

    /** S3K destruction config: spawn animal + points popup, no respawn tracking, S3K break SFX. */
    private static final DestructionConfig S3K_DESTRUCTION_CONFIG = new DestructionConfig(
            Sonic3kSfx.BREAK.id,
            (spawn, services) -> AnimalObjectInstance.deferredArtVariant(spawn, services, null),
            false,  // useRespawnTracking (S3K always removeFromActiveSpawns)
            (spawn, svc, pts) -> new Sonic3kPointsObjectInstance(spawn, svc, pts),
            null,
            false
    );

    protected final void defeat(AbstractPlayableSprite player) {
        destroyBadnik(player);
    }

    @Override
    protected void destroyBadnik(PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }
        // ROM parity: badnik destruction rewrites the current SST slot to
        // Obj_Explosion; child animal/points objects are allocated after it.
        int mySlot = ObjectLifetimeOps.detachSlotForTransfer(this);
        setDestroyed(true);

        DestructionEffects.destroyBadnik(
                getBodyAnchorX(), getBodyAnchorY(), spawn, mySlot, player, services(),
                S3K_DESTRUCTION_CONFIG);
    }

    protected final void spawnProjectile(S3kBadnikProjectileInstance projectile) {
        if (tryServices() != null && services().objectManager() != null) {
            // CreateChild2_Complex uses AllocateObjectAfterCurrent, so a free
            // higher SST slot executes the projectile callback later in the
            // same object pass.
            spawnChild(() -> projectile);
        }
    }

    protected final PlayableEntity closestNativePlayerByHorizontalDistance(PlayableEntity updatePlayer) {
        PlayableEntity closest = updatePlayer;
        int closestDistance = updatePlayer == null
                ? Integer.MAX_VALUE
                : findSonicTailsHorizontalDistance(updatePlayer);
        ObjectServices svc = tryServices();
        PlayableEntity nativeP2 = svc == null ? null : svc.playerQuery().nativeP2OrNull();
        if (nativeP2 != null) {
            int sidekickDistance = findSonicTailsHorizontalDistance(nativeP2);
            if (sidekickDistance < closestDistance) {
                closest = nativeP2;
            }
        }
        return closest;
    }

    protected final int findSonicTailsHorizontalDistance(PlayableEntity target) {
        int delta = findSonicTailsHorizontalDelta(target);
        return delta < 0 ? -delta : delta;
    }

    protected final boolean findSonicTailsTargetIsRight(PlayableEntity target) {
        return findSonicTailsHorizontalDelta(target) < 0;
    }

    private int findSonicTailsHorizontalDelta(PlayableEntity target) {
        return (short) (getBodyAnchorX() - target.getCentreX());
    }

    protected final void moveWithVelocity() {
        int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
        int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
        xPos24 += xVelocity;
        yPos24 += yVelocity;
        currentX = xPos24 >> 8;
        currentY = yPos24 >> 8;
        xSubpixel = xPos24 & 0xFF;
        ySubpixel = yPos24 & 0xFF;
    }

    @Override
    public ObjectSpawn getSpawn() {
        int bodyX = getBodyAnchorX();
        int bodyY = getBodyAnchorY();
        return buildSpawnAt(bodyX, bodyY);
    }

    @Override
    public int getX() {
        return getBodyAnchorX();
    }

    @Override
    public int getY() {
        return getBodyAnchorY();
    }

    @Override
    protected int resolveDynamicSpawnX() {
        return getBodyAnchorX();
    }

    @Override
    protected int resolveDynamicSpawnY() {
        return getBodyAnchorY();
    }

    @Override
    public int getPriorityBucket() {
        return priorityBucket;
    }

    @Override
    public boolean isHighPriority() {
        return planePriority;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }
        PatternSpriteRenderer renderer = renderManager.getRenderer(rendererKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        if (planePriority) {
            renderer.drawFrameIndexForcedPriority(
                    mappingFrame, getRenderAnchorX(), getRenderAnchorY(), !facingLeft, false, -1, true);
        } else {
            renderer.drawFrameIndex(mappingFrame, getRenderAnchorX(), getRenderAnchorY(), !facingLeft, false);
        }
    }

    /**
     * Body anchor used by touch collision and all gameplay interactions.
     * Subclasses can override when the logical center differs from raw {@code currentX/currentY}.
     */
    protected int getBodyAnchorX() {
        return currentX;
    }

    protected int getBodyAnchorY() {
        return currentY;
    }

    /**
     * Render anchor for sprite drawing. Defaults to body anchor.
     * Subclasses can override for visual-only offsets.
     */
    protected int getRenderAnchorX() {
        return getBodyAnchorX();
    }

    protected int getRenderAnchorY() {
        return getBodyAnchorY();
    }

    final boolean badnikFacingLeft() {
        return facingLeft;
    }
}
