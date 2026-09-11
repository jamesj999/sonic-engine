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
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * S3K S3KL Obj $BF - Ribot (LBZ).
 *
 * <p>ROM reference: {@code Obj_Ribot} at {@code sonic3k.asm:191254}. The
 * parent is the defeatable body; subtype chooses the child layout:
 * downward legs ({@code 0}), side legs ({@code 2}), or a single top appendage
 * ({@code >= 4}). Children use the ROM hurt collision byte {@code $97}.
 */
public final class RibotBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {
    private static final int WAIT_OFFSCREEN_HALF_SIZE = 0x20;
    private static final int COLLISION_SIZE_INDEX = 0x0B; // ObjDat_Ribot collision_flags.
    private static final int PRIORITY_BUCKET = 5;         // ObjDat_Ribot priority $280.
    private static final int ANIM_DELAY = 7;              // byte_8C626 / byte_8C62C.
    private static final int[] LOWER_BODY_FRAMES = {0, 1, 2, 1};
    private static final int[] UPPER_BODY_FRAMES = {3, 4, 5, 4};

    private boolean initialized;
    private boolean initializedThisFrame;
    private boolean childTrigger;
    private boolean childPhase;
    boolean childGateA;
    boolean childGateB;
    private int animIndex;
    private int animTimer;
    private boolean waitingForOnscreen = true;
    private boolean placeholderRenderedOnscreen;

    public RibotBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Ribot", Sonic3kObjectArtKeys.RIBOT, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // Obj_WaitOffscreen installs a $20-by-$20 placeholder. Render_Sprites
        // publishes its on-screen bit after the object pass; the next dispatch
        // restores Obj_Ribot and returns before initialization resumes.
        if (waitingForOnscreen) {
            if (!placeholderRenderedOnscreen) {
                return;
            }
            waitingForOnscreen = false;
            placeholderRenderedOnscreen = false;
            return;
        }

        if (!initialized) {
            initialize();
            return;
        }

        if (childTrigger) {
            childTrigger = false;
            childGateA = false;
            childGateB = false;
            childPhase = !childPhase;
            if (childPhase) {
                childGateA = true;
            } else {
                childGateB = true;
            }
        }
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (waitingForOnscreen) {
            placeholderRenderedOnscreen = isWithinRenderSpriteBounds(
                    WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE);
        }
    }

    private void initialize() {
        initialized = true;
        initializedThisFrame = true;
        childTrigger = true;
        animTimer = ANIM_DELAY;
        animIndex = 0;
        mappingFrame = animationFrames()[0];
        spawnActiveChildren();
    }

    private void spawnActiveChildren() {
        int subtype = spawn.subtype();
        if (subtype == 2) {
            spawnActiveChild(0, -0x18, 0);
            spawnActiveChild(1, 0x18, 0);
        } else if (subtype > 2) {
            spawnActiveChild(0, 0, -0x10);
        } else {
            spawnActiveChild(0, -0x0C, 0x0C);
            spawnActiveChild(1, 0x0C, 0x0C);
        }
    }

    private void spawnActiveChild(int childIndex, int dx, int dy) {
        int childX = (currentX + dx) & 0xFFFF;
        int childY = (currentY + dy) & 0xFFFF;
        spawnChild(() -> new RibotActiveChild(buildSpawnAt(childX, childY), this, childIndex, childX, childY));
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        if (!initialized || initializedThisFrame) {
            initializedThisFrame = false;
            return;
        }
        if (animTimer > 0) {
            animTimer--;
            return;
        }
        animTimer = ANIM_DELAY;
        int[] frames = animationFrames();
        animIndex = (animIndex + 1) % frames.length;
        mappingFrame = frames[animIndex];
    }

    private int[] animationFrames() {
        return spawn.subtype() > 2 ? UPPER_BODY_FRAMES : LOWER_BODY_FRAMES;
    }

    private int subtypeForChildren() {
        if (spawn.subtype() == 2) {
            return 2;
        }
        return spawn.subtype() > 2 ? 4 : 0;
    }

    private boolean consumeGateForChild(int childIndex) {
        if (childIndex == 0) {
            return childGateB;
        }
        return childGateA;
    }

    private void armNextChildPhase() {
        childTrigger = true;
    }

    private static final class RibotActiveChild extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        private static final int COLLISION_FLAGS = 0x97; // word_8C5D6 collision_flags.
        private static final int PRIORITY_BUCKET = 4;    // word_8C5D6 priority $200.
        private static final int BODY_FRAME = 7;
        private static final int Y_RETURN_STEP = 2;      // loc_8C4C8: subq.w #2,y_pos.
        private static final int SIDE_X_SPEED = 0x400;   // loc_8C3EC.
        private static final int FALL_GRAVITY = 0x38;    // MoveSprite.
        private static final int WAIT_FRAMES = 0x10;     // loc_8C46A.
        private static final int RETURN_WAIT = 0x0F;     // loc_8C4A0.
        private static final int CIRCLE_RADIUS = 64;      // loc_8C41E: MoveSprite_CircularSimpleOffset with d2=2.
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

        private enum State {
            WAIT_GATE,
            FALL_OR_SWING,
            WAIT,
            RETURN
        }

        private final transient RibotBadnikInstance parent;
        // Un-final so the generic field capturer reapplies the captured values
        // after a rewind recreate with placeholders.
        private int childIndex;
        private int originX;
        private int originY;
        private State state = State.WAIT_GATE;
        private int currentX;
        private int currentY;
        private int xVelocity;
        private int yVelocity;
        private int xSubpixel;
        private int ySubpixel;
        private int waitTimer;
        private int circularAngle;
        private boolean visualChildrenSpawned;

        private RibotActiveChild(ObjectSpawn spawn, RibotBadnikInstance parent,
                int childIndex, int originX, int originY) {
            super(spawn, "RibotChild");
            this.parent = parent;
            this.childIndex = childIndex;
            this.originX = originX;
            this.originY = originY;
            this.currentX = originX;
            this.currentY = originY;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
                return null;
            }
            RibotBadnikInstance liveParent =
                    findNearestLiveParentForRewind(ctx.objectServices().objectManager(), ctx.spawn());
            if (liveParent == null) {
                return null;
            }
            ObjectSpawn capturedSpawn = ctx.spawn();
            return new RibotActiveChild(
                    capturedSpawn, liveParent, 0, capturedSpawn.x(), capturedSpawn.y());
        }

        private static RibotBadnikInstance findNearestLiveParentForRewind(
                ObjectManager objectManager,
                ObjectSpawn spawn) {
            if (objectManager == null || spawn == null) {
                return null;
            }
            RibotBadnikInstance best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                if (!(instance instanceof RibotBadnikInstance parent) || parent.isDestroyed()) {
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
            if (!visualChildrenSpawned) {
                spawnVisualChildren();
                visualChildrenSpawned = true;
            }

            switch (state) {
                case WAIT_GATE -> updateWaitGate();
                case FALL_OR_SWING -> updateFallOrSwing();
                case WAIT -> updateWait();
                case RETURN -> updateReturn();
            }
            updateDynamicSpawn(currentX, currentY);
        }

        private void updateWaitGate() {
            int subtype = parent.subtypeForChildren();
            if (subtype == 4) {
                circularAngle = (circularAngle + 2) & 0xFF;
                int dx = (TrigLookupTable.sinHex(circularAngle) * CIRCLE_RADIUS) >> 8;
                int dy = (TrigLookupTable.cosHex(circularAngle) * CIRCLE_RADIUS) >> 8;
                currentX = (originX + dx) & 0xFFFF;
                currentY = (originY + dy) & 0xFFFF;
                return;
            }
            if (!parent.consumeGateForChild(childIndex)) {
                return;
            }
            state = State.FALL_OR_SWING;
            if (subtype == 2) {
                xVelocity = childIndex == 0 ? -SIDE_X_SPEED : SIDE_X_SPEED;
                waitTimer = RETURN_WAIT;
            }
        }

        private void updateFallOrSwing() {
            if (parent.subtypeForChildren() == 2) {
                moveWithVelocity();
                waitTimer--;
                if (waitTimer < 0) {
                    enterWait();
                }
                return;
            }

            if (((currentY - originY) & 0xFFFF) >= 0x80) {
                enterWait();
                return;
            }
            moveWithGravity();
            if (yVelocity >= 0) {
                resolveFloorContact();
            }
        }

        private void enterWait() {
            state = State.WAIT;
            waitTimer = WAIT_FRAMES;
            yVelocity = 0;
        }

        private void updateWait() {
            waitTimer--;
            if (waitTimer < 0) {
                state = State.RETURN;
                waitTimer = RETURN_WAIT;
                xVelocity = -xVelocity;
            }
        }

        private void updateReturn() {
            if (parent.subtypeForChildren() == 2) {
                moveWithVelocity();
                waitTimer--;
                if (waitTimer >= 0) {
                    return;
                }
            } else if (currentY > originY) {
                int nextY = currentY - Y_RETURN_STEP;
                if (nextY > originY) {
                    currentY = nextY;
                    return;
                }
            }
            currentX = originX;
            currentY = originY;
            state = State.WAIT_GATE;
            parent.armNextChildPhase();
        }

        private void moveWithVelocity() {
            int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
            int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
            xPos24 += xVelocity;
            yPos24 += yVelocity;
            currentX = xPos24 >> 8;
            currentY = yPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
            ySubpixel = yPos24 & 0xFF;
        }

        private void moveWithGravity() {
            moveWithVelocity();
            yVelocity = (short) (yVelocity + FALL_GRAVITY);
        }

        private void resolveFloorContact() {
            TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(
                    currentX, currentY, 8);
            if (!floor.hasCollision()) {
                return;
            }
            currentY += floor.distance();
            if (yVelocity >= 0x100) {
                yVelocity = -(yVelocity >> 2);
            } else {
                enterWait();
            }
        }

        private void spawnVisualChildren() {
            int[][] offsets = visualChildOffsets();
            for (int i = 0; i < 3; i++) {
                final int index = i;
                int visualX = (currentX + offsets[i][0]) & 0xFFFF;
                int visualY = (currentY + offsets[i][1]) & 0xFFFF;
                spawnChild(() -> new RibotVisualChild(buildSpawnAt(visualX, visualY), this, index, visualX, visualY));
            }
        }

        private int[][] visualChildOffsets() {
            int subtype = parent.subtypeForChildren();
            if (subtype == 2) {
                int xOffset = childIndex == 0 ? -0x0C : 0x0C;
                return new int[][] {{xOffset, 0}, {xOffset, 0}, {xOffset, 0}};
            }
            if (subtype == 4) {
                return new int[][] {{0, 0}, {0, 0}, {0, 0}};
            }
            return new int[][] {{0, -0x0C}, {0, -0x0C}, {0, -0x0C}};
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
            // The falling/swinging appendages run movement before
            // Child_DrawTouch_Sprite publishes them (sonic3k.asm:191391-191399).
            // The subtype-$04 head instead publishes through its circular-offset
            // helper while retaining the prior player-slot touch coordinate.
            return parent.subtypeForChildren() != 4;
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
            drawFrame(BODY_FRAME, currentX, currentY, false, false);
        }

        private void drawFrame(int frame, int x, int y, boolean flipX, boolean flipY) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.RIBOT);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(frame, x, y, flipX, flipY);
        }
    }

    private static final class RibotVisualChild extends AbstractObjectInstance
            implements RewindRecreatable {
        private static final int PRIORITY_BUCKET = 4; // word_8C5DC priority $200.
        private static final int VISUAL_FRAME = 6;

        private final transient RibotActiveChild parent;
        private int visualIndex;
        private int originX;
        private int originY;
        private int currentX;
        private int currentY;

        private RibotVisualChild(ObjectSpawn spawn, RibotActiveChild parent,
                int visualIndex, int originX, int originY) {
            super(spawn, "RibotTrail");
            this.parent = parent;
            this.visualIndex = visualIndex;
            this.originX = originX;
            this.originY = originY;
            this.currentX = originX;
            this.currentY = originY;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null) {
                return null;
            }
            RibotActiveChild liveParent =
                    findNearestLiveActiveChildForRewind(ctx.objectServices().objectManager(), ctx.spawn());
            if (liveParent == null) {
                return null;
            }
            ObjectSpawn capturedSpawn = ctx.spawn();
            return new RibotVisualChild(capturedSpawn, liveParent, 0, capturedSpawn.x(), capturedSpawn.y());
        }

        private static RibotActiveChild findNearestLiveActiveChildForRewind(
                ObjectManager objectManager,
                ObjectSpawn spawn) {
            if (objectManager == null || spawn == null) {
                return null;
            }
            RibotActiveChild best = null;
            long bestDistance = Long.MAX_VALUE;
            for (ObjectInstance instance : objectManager.getActiveObjects()) {
                if (!(instance instanceof RibotActiveChild activeChild) || activeChild.isDestroyed()) {
                    continue;
                }
                long dx = activeChild.getX() - spawn.x();
                long dy = activeChild.getY() - spawn.y();
                long distance = dx * dx + dy * dy;
                if (distance < bestDistance) {
                    bestDistance = distance;
                    best = activeChild;
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
            int dx = parent.currentX - parent.originX;
            int dy = parent.currentY - parent.originY;
            switch (visualIndex) {
                case 0 -> {
                    currentX = parent.currentX - (dx >> 2);
                    currentY = parent.currentY - (dy >> 2);
                }
                case 1 -> {
                    currentX = originX + (dx >> 1);
                    currentY = originY + (dy >> 1);
                }
                default -> {
                    currentX = originX + (dx >> 2);
                    currentY = originY + (dy >> 2);
                }
            }
            updateDynamicSpawn(currentX, currentY);
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
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.RIBOT);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(VISUAL_FRAME, currentX, currentY, false, false);
        }
    }
}
