package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectManager;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj $C0 - Orbinaut (LBZ).
 *
 * <p>ROM reference: {@code Obj_Orbinaut} (sonic3k.asm:191626-191725). The
 * parent badnik tracks P1's side, but only advances when the shared ROM helper
 * returns a nonzero branch result. Four child orbs orbit as hurt-category
 * hazards.
 */
public final class OrbinautBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x0B; // ObjDat_Orbinaut collision_flags.
    private static final int PRIORITY_BUCKET = 5;         // ObjDat_Orbinaut priority $280.
    private static final int X_SPEED = 0x80;              // loc_8C662: move.w #-$80,d1.
    private static final int CHILD_COUNT = 4;
    private static final int WAIT_OFFSCREEN_HALF_SIZE = 0x20;
    private static final int ORBIT_ANGLE_STEP = 8;
    private static final int WAIT_OFFSCREEN_RESTORE_PASSES = 2;

    private boolean initialized;
    private boolean movementEnabled;
    private int movementEnableDelay = -1;
    private boolean deferredWaitOffscreenActive;
    private boolean waitPlaceholderPassedAboveCamera;
    private int deferredWaitOffscreenDelay = WAIT_OFFSCREEN_RESTORE_PASSES;
    private int deferredMovementCount;
    private int deferredOrbitAngle;

    public OrbinautBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Orbinaut",
                Sonic3kObjectArtKeys.ORBINAUT, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                ? sprite : null;
        if (!initialized && !deferredWaitOffscreenActive && isWaitPlaceholderOutsideCoarseRange()) {
            // loc_85AD2 deletes a placeholder which never reached
            // Render_Sprites once its coarse X falls outside the live window.
            setDestroyedByOffscreen();
            return;
        }
        if (!initialized) {
            boolean placeholderRendered = isWithinRenderSpriteBounds(
                    WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE);
            if (waitPlaceholderPassedAboveCamera && !placeholderRendered) {
                return;
            }
            if (placeholderRendered) {
                waitPlaceholderPassedAboveCamera = false;
            } else if (!deferredWaitOffscreenActive
                    && isWaitPlaceholderAboveCamera()
                    && (player == null || player.getYSpeed() >= 0)) {
                // The engine materializes placements before the ROM
                // placeholder's render pass. Once the player is travelling
                // downward away from an already-above-camera placeholder, do
                // not fabricate its child graph unless the camera later
                // actually renders that placeholder.
                waitPlaceholderPassedAboveCamera = true;
                return;
            }
        }
        if (!initialized && !isOnScreenX()) {
            if (isWithinRenderSpriteBounds(WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE)) {
                // Obj_WaitOffscreen has observed render_flags bit 7 and restored
                // the saved callback. Keep advancing that ROM state while child
                // slot allocation remains deferred to the engine's centre window.
                deferredWaitOffscreenActive = true;
                advanceDeferredWaitOffscreen(player);
            }
            return;
        }

        if (!initialized) {
            // Reserve the native child graph as soon as the coarse placement
            // window admits the parent. Obj_WaitOffscreen's saved continuation
            // still keeps motion and orbit cadence dormant until its $20
            // placeholder has actually reached Render_Sprites.
            if (deferredWaitOffscreenActive) {
                advanceDeferredWaitOffscreen(player);
            }
            spawnOrbitingOrbs();
            initialized = true;
            movementEnabled = isWithinRenderSpriteBounds(
                    WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE);
            return;
        }

        updateFacingAndVelocity(player);
        if (!movementEnabled) {
            if (movementEnableDelay < 0) {
                if (!isWithinRenderSpriteBounds(WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE)) {
                    return;
                }
                movementEnableDelay = 2;
            }
            if (movementEnableDelay > 0) {
                movementEnableDelay--;
                return;
            }
            movementEnabled = true;
        }
        if (canMoveThisFrame(player)) {
            moveWithVelocity();
        }
    }

    private boolean isWaitPlaceholderOutsideCoarseRange() {
        if (tryServices() == null || services().camera() == null) {
            return false;
        }
        int objectCoarse = currentX & 0xFF80;
        int cameraCoarseBack = (services().camera().getX() - 0x80) & 0xFF80;
        boolean isBehindCamera = (short) (currentX - services().camera().getX()) < 0;
        return isBehindCamera && ((objectCoarse - cameraCoarseBack) & 0xFFFF) > 0x280;
    }

    private boolean isWaitPlaceholderAboveCamera() {
        if (tryServices() == null || services().camera() == null) {
            return false;
        }
        return currentY + WAIT_OFFSCREEN_HALF_SIZE < services().camera().getY();
    }

    private void advanceDeferredWaitOffscreen(AbstractPlayableSprite player) {
        // The restored callback and the parent's setup/child-creation callback
        // each consume a pass without movement before loc_8C662 can run.
        if (deferredWaitOffscreenDelay > 0) {
            deferredWaitOffscreenDelay--;
            return;
        }
        advanceDeferredOperation(player);
    }

    private void advanceDeferredOperation(AbstractPlayableSprite player) {
        updateFacingAndVelocity(player);
        if (!canMoveThisFrame(player)) {
            return;
        }
        moveWithVelocity();
        deferredMovementCount++;
        int angleStep = isFacingRight() ? -ORBIT_ANGLE_STEP : ORBIT_ANGLE_STEP;
        deferredOrbitAngle = (deferredOrbitAngle + angleStep) & 0xFF;
    }

    private void spawnOrbitingOrbs() {
        for (int i = 0; i < CHILD_COUNT; i++) {
            final int index = i;
            // When the engine delays slot materialisation, reconstruct both the
            // accumulated circular phase and whether loc_8C692's setup callback
            // has already yielded to loc_8C6B0. This keeps the ROM lifecycle
            // without reserving four live SST slots outside the centre window.
            spawnChild(() -> new OrbinautOrbInstance(
                    spawn, this, index, deferredOrbitAngle,
                    deferredMovementCount > WAIT_OFFSCREEN_RESTORE_PASSES));
        }
    }

    private void updateFacingAndVelocity(AbstractPlayableSprite player) {
        boolean playerOnRight = player != null && !player.getDead() && player.getCentreX() > currentX;
        facingLeft = !playerOnRight;
        xVelocity = playerOnRight ? X_SPEED : -X_SPEED;
    }

    /**
     * ROM sub_8C6D4 returns with the zero flag set for airborne or stationary
     * P1. A nonzero player x/y velocity leaves the zero flag clear, so the
     * caller advances in whichever direction loc_8C662 already selected.
     */
    private boolean canMoveThisFrame(AbstractPlayableSprite player) {
        if (player == null || player.getDead()) {
            return false;
        }
        return !player.getAir() && (player.getXSpeed() != 0 || player.getYSpeed() != 0);
    }

    boolean shouldRotateOrbs(AbstractPlayableSprite player) {
        return movementEnabled && canMoveThisFrame(player);
    }

    boolean isFacingRight() {
        return !facingLeft;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("init=%s move=%s delay=%d render=%s sub=%02X",
                initialized, movementEnabled, movementEnableDelay,
                isWithinRenderSpriteBounds(WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE),
                xSubpixel & 0xFF);
    }

    static final class OrbinautOrbInstance extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        private static final int COLLISION_FLAGS = 0x8B; // word_8C6FE collision_flags.
        private static final int PRIORITY_BUCKET = 5;
        private static final int ORBIT_RADIUS = 16;      // MoveSprite_CircularSimple with d2 = 4.
        private static final int ANGLE_STEP = 8;         // loc_8C6B0 adds +/-8.
        private static final int MAPPING_FRAME = 1;
        private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.NONE,
                0,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        private final transient OrbinautBadnikInstance parent;
        private boolean initialized;
        private int currentX;
        private int currentY;
        private int angle;

        OrbinautOrbInstance(ObjectSpawn ownerSpawn, OrbinautBadnikInstance parent, int index,
                int initialAngleOffset, boolean nativeRoutineActive) {
            super(ownerSpawn, "OrbinautOrb");
            this.parent = parent;
            this.initialized = nativeRoutineActive;
            this.angle = (index * 0x40 + initialAngleOffset) & 0xFF;
            updateOrbitPosition();
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
                return null;
            }
            OrbinautBadnikInstance liveParent =
                    findNearestLiveParentForRewind(ctx.objectServices().objectManager(), ctx.spawn());
            if (liveParent == null) {
                return null;
            }
            return new OrbinautOrbInstance(ctx.spawn(), liveParent, 0, 0, false);
        }

        private static OrbinautBadnikInstance findNearestLiveParentForRewind(
                ObjectManager objectManager,
                ObjectSpawn spawn) {
            if (objectManager == null || spawn == null) {
                return null;
            }
            OrbinautBadnikInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                if (!(instance instanceof OrbinautBadnikInstance parent) || parent.isDestroyed()) {
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
            if (isDestroyed() || parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }

            if (!initialized) {
                initialized = true;
                updateOrbitPosition();
                return;
            }

            AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                    ? sprite : null;
            if (parent.shouldRotateOrbs(player)) {
                angle = (angle + (parent.isFacingRight() ? -ANGLE_STEP : ANGLE_STEP)) & 0xFF;
            }
            updateOrbitPosition();
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
        public TouchResponseProfile getTouchResponseProfile() {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public boolean usesCurrentTouchResponseState() {
            // Obj_Orbinaut's child routine updates its circular position, then
            // calls Child_DrawTouch_Sprite, which adds the current SST pointer
            // to Collision_response_list before drawing (sonic3k.asm:
            // 191685-191688, 178048-178053, 21200-21207).
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
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.ORBINAUT);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(MAPPING_FRAME, currentX, currentY, false, false);
        }

        private void updateOrbitPosition() {
            int dx = (TrigLookupTable.sinHex(angle) * ORBIT_RADIUS) >> 8;
            int dy = (TrigLookupTable.cosHex(angle) * ORBIT_RADIUS) >> 8;
            currentX = parent.getX() + dx;
            currentY = parent.getY() + dy;
        }
    }
}
