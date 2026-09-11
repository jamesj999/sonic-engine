package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj $AE - Star Pointer (ICZ).
 *
 * <p>ROM reference: {@code Obj_StarPointer} (sonic3k.asm:190757-190899).
 * The parent is an attackable badnik body that drifts horizontally toward the
 * player using a subtype-selected speed. Four child points orbit the body, then
 * launch horizontally after the parent passes the player.
 */
public final class StarPointerBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {

    // ObjDat_StarPointer: collision_flags = $0B.
    private static final int COLLISION_SIZE_INDEX = 0x0B;
    // ObjDat_StarPointer: priority $280.
    private static final int PRIORITY_BUCKET = 5;
    // word_8BE6C selected by subtype & 6: -$40, -$60, -$80, -$100.
    private static final int[] TRACK_SPEEDS = {0x40, 0x60, 0x80, 0x100};
    private static final int RELEASE_X_RANGE = 0x80;
    private static final int CHILD_COUNT = 4;
    private static final int WAIT_OFFSCREEN_HALF_SIZE = 0x20;

    private boolean initialized;
    private boolean activeRoutineReady;
    private boolean releaseChildren;

    public StarPointerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "StarPointer",
                Sonic3kObjectArtKeys.ICZ_STAR_POINTER, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                ? sprite : null;

        if (!initialized) {
            if (!isWithinRenderSpriteBounds(WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE)) {
                return;
            }
            initializeVelocity(player);
            spawnOrbitingPoints();
            initialized = true;
            return;
        }

        /*
         * Obj_WaitOffscreen installs Obj_StarPointer's active address, but
         * Process_Sprites does not dispatch loc_8BE74 until the following
         * object pass. Preserve that admission phase before applying velocity.
         */
        if (!activeRoutineReady) {
            activeRoutineReady = true;
            return;
        }

        moveWithVelocity();
        updateReleaseLatch(player);
    }

    boolean shouldReleaseChildren() {
        return releaseChildren;
    }

    boolean isFacingRight() {
        return !facingLeft;
    }

    /**
     * ROM {@code loc_8BE74} moves the parent before publishing its SST pointer
     * to {@code Collision_response_list}. {@code Touch_Loop} later dereferences
     * that pointer, so parent contact uses the live post-movement position.
     */
    @Override
    public boolean usesCurrentTouchResponseState() {
        return true;
    }

    private void initializeVelocity(AbstractPlayableSprite player) {
        int speedIndex = ((spawn.subtype() & 0x06) >> 1) & 0x03;
        int speed = TRACK_SPEEDS[speedIndex];

        if (player == null || player.getDead()) {
            xVelocity = facingLeft ? -speed : speed;
            return;
        }

        if (currentX - player.getCentreX() >= 0) {
            xVelocity = -speed;
            facingLeft = true;
        } else {
            xVelocity = speed;
            facingLeft = false;
        }
    }

    private void spawnOrbitingPoints() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }

        for (int i = 0; i < CHILD_COUNT; i++) {
            final int index = i;
            spawnChild(() -> new OrbitingPointInstance(spawn, this, index));
        }
    }

    /**
     * ROM loc_8BE74: after {@code Find_SonicTails}, latch bit 1 of $38 when
     * the nearest player is within $80 px and on the side the parent faces.
     */
    private void updateReleaseLatch(AbstractPlayableSprite player) {
        PlayableEntity target = closestNativePlayerByHorizontalDistance(player);
        if (releaseChildren || target == null || target.getDead()) {
            return;
        }

        int dx = currentX - target.getCentreX();
        if (Math.abs(dx) >= RELEASE_X_RANGE) {
            return;
        }

        int side = dx < 0 ? 2 : 0; // Find_SonicTails d0: 0=left/same, 2=right.
        if (isFacingRight()) {
            side -= 2;
        }
        if (side == 0) {
            releaseChildren = true;
        }
    }

    /**
     * Child point for {@link StarPointerBadnikInstance}.
     *
     * <p>ROM reference: loc_8BEB0-loc_8BF8C. The point has hurt-category
     * collision ($8B), shield bounce reaction, circular parent-relative motion
     * with d2=4, and a three-frame break animation after collision is disabled.
     */
    static final class OrbitingPointInstance extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        private static final int COLLISION_FLAGS = 0x8B;
        private static final int SHIELD_REACTION_BOUNCE = 1 << 3;
        private static final int PRIORITY_BUCKET = 5;
        private static final int ORBIT_RADIUS = 16; // MoveSprite_CircularSimple with d2 = 4.
        private static final int WALL_SENSOR_OFFSET = 8; // $44(a0) = +/-8 before ObjHitWall*_DoRoutine.
        private static final int BREAK_DELAY = 3;
        private static final int[] BREAK_FRAMES = {1, 2, 3};
        private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.SHIELD_DEFLECT,
                SHIELD_REACTION_BOUNCE,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        @RewindTransient(reason = "structural parent link; child captures its own motion state")
        private final StarPointerBadnikInstance parent;
        private boolean initialized;
        private boolean touchListPublishedThisFrame;
        private int currentX;
        private int currentY;
        private int xVelocity;
        private int xSubpixel;
        private int ySubpixel;
        private int angle;
        private int mappingFrame = 1;
        private boolean collisionEnabled = true;
        private boolean launched;
        private boolean breaking;
        private int breakIndex;
        private int breakTimer = BREAK_DELAY;

        OrbitingPointInstance(ObjectSpawn ownerSpawn, StarPointerBadnikInstance parent, int index) {
            super(ownerSpawn, "StarPointerPoint");
            this.parent = parent;
            this.angle = (index * 0x40) & 0xFF; // byte_8BEE2: 0, $40, $80, $C0.
            // CreateChild3 copies the parent's full position into the child SST.
            // loc_8BEB0 only installs attributes/angle on the child's first
            // execution and returns without circular movement or touch-list
            // publication; loc_8BEE6 starts on its next execution.
            this.currentX = parent.getX();
            this.currentY = parent.getY();
            this.xSubpixel = parent.xSubpixel;
            this.ySubpixel = parent.ySubpixel;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
                return null;
            }
            StarPointerBadnikInstance liveParent =
                    findNearestLiveParentForRewind(ctx.objectServices().objectManager(), ctx.spawn());
            if (liveParent == null) {
                return null;
            }
            return new OrbitingPointInstance(ctx.spawn(), liveParent, 0);
        }

        private static StarPointerBadnikInstance findNearestLiveParentForRewind(
                ObjectManager objectManager,
                ObjectSpawn spawn) {
            if (objectManager == null || spawn == null) {
                return null;
            }
            StarPointerBadnikInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                if (!(instance instanceof StarPointerBadnikInstance parent) || parent.isDestroyed()) {
                    continue;
                }
                long dx = parent.getX() - spawn.x();
                long dy = parent.getY() - spawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = parent;
                }
            }
            return best;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            touchListPublishedThisFrame = false;
            if (!initialized) {
                initialized = true;
                return;
            }
            touchListPublishedThisFrame = true;
            if (breaking) {
                advanceBreakAnimation();
                return;
            }

            if (launched) {
                moveWithVelocity();
                checkWallImpact();
                if (!isOnScreen(48)) {
                    setDestroyed(true);
                }
                return;
            }

            if ((vIntRunCount & 1) == 0) {
                angle = parent.isFacingRight() ? (angle + 1) & 0xFF : (angle - 1) & 0xFF;
                if (angle == 0 && parent.shouldReleaseChildren()) {
                    launchFromParent();
                }
            }

            updateOrbitPosition();

            // ROM loc_8BEE6's tail is `jmp Child_DrawTouch_Sprite`
            // (sonic3k.asm:190853-190858, :178053-178058), which runs
            // `movea.w parent3(a0),a1 / btst #7,status(a1) / bne.w
            // Go_Delete_Sprite` BEFORE Add_SpriteToCollisionResponseList.
            // Touch_EnemyNormal sets that bit on the badnik it destroys
            // (sonic3k.asm:20952-20953), so every still-orbiting point is
            // deleted on the pass after the parent is destroyed and never
            // publishes another touch-response entry. Only this routine ends in
            // Child_DrawTouch_Sprite: once launched (loc_8BF4C) or breaking
            // (loc_8BF74) the tail is Sprite_CheckDeleteTouchXY
            // (sonic3k.asm:190860-190866, :190873-190876), which does not test
            // the parent -- so the check stays inside the orbit branch. Same
            // ROM contract as OrbinautBadnikInstance.OrbinautOrbInstance.
            if (parent.isDestroyed()) {
                touchListPublishedThisFrame = false;
                setDestroyed(true);
            }
        }

        @Override
        public boolean publishesTouchResponseListEntryThisFrame() {
            return touchListPublishedThisFrame;
        }

        @Override
        public int getCollisionFlags() {
            return collisionEnabled ? COLLISION_FLAGS : 0;
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
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public int getShieldReactionFlags() {
            return SHIELD_REACTION_BOUNCE;
        }

        @Override
        public boolean onShieldDeflect(PlayableEntity player) {
            beginBreakAnimation();
            return true;
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
            return PRIORITY_BUCKET;
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
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.ICZ_STAR_POINTER);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
        }

        private void launchFromParent() {
            launched = true;
            xVelocity = parent.xVelocity << 1;
        }

        private void updateOrbitPosition() {
            int xFixed = ((parent.getX() << 8) | (parent.xSubpixel & 0xFF))
                    + TrigLookupTable.sinHex(angle) * ORBIT_RADIUS;
            int yFixed = ((parent.getY() << 8) | (parent.ySubpixel & 0xFF))
                    + TrigLookupTable.cosHex(angle) * ORBIT_RADIUS;
            currentX = xFixed >> 8;
            currentY = yFixed >> 8;
            xSubpixel = xFixed & 0xFF;
            ySubpixel = yFixed & 0xFF;
        }

        private void moveWithVelocity() {
            int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
            xPos24 += xVelocity;
            currentX = xPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
        }

        private void checkWallImpact() {
            TerrainCheckResult result = xVelocity < 0
                    ? ObjectTerrainUtils.checkLeftWallDist(currentX - WALL_SENSOR_OFFSET, currentY)
                    : ObjectTerrainUtils.checkRightWallDist(currentX + WALL_SENSOR_OFFSET, currentY);
            if (result.foundSurface() && result.distance() < 0) {
                beginBreakAnimation();
            }
        }

        private void beginBreakAnimation() {
            if (breaking) {
                return;
            }
            collisionEnabled = false;
            launched = false;
            breaking = true;
            breakIndex = 0;
            breakTimer = BREAK_DELAY;
            mappingFrame = BREAK_FRAMES[breakIndex];
        }

        private void advanceBreakAnimation() {
            if (breakTimer-- > 0) {
                return;
            }
            breakTimer = BREAK_DELAY;
            breakIndex++;
            if (breakIndex >= BREAK_FRAMES.length) {
                setDestroyed(true);
                return;
            }
            mappingFrame = BREAK_FRAMES[breakIndex];
        }
    }
}
