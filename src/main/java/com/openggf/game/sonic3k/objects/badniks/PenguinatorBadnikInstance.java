package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;

import java.util.List;

/**
 * S3K Obj $AD - Penguinator (ICZ).
 *
 * <p>ROM reference: {@code Obj_Penguinator} (sonic3k.asm:190431-190742).
 * The badnik accelerates in place while animating, hops forward, lands into a
 * belly slide, emits snow dust every four slide frames, then brakes and flips.
 */
public final class PenguinatorBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x1A; // ObjSlot_Penguinator collision_flags.
    private static final int PRIORITY_BUCKET = 5;         // ObjSlot_Penguinator priority $280.
    private static final int WAIT_OFFSCREEN_HALF_SIZE = 0x20;

    private static final int INITIAL_Y_RADIUS = 0x0F;
    private static final int SLIDE_Y_RADIUS = 0x0B;
    private static final int PATROL_ACCEL = 2;            // loc_8BB24 writes +/-2 to $40.
    private static final int JUMP_X_SPEED = 0x200;        // loc_8BBAC.
    private static final int SLIDE_ACCEL = 0x40;          // loc_8BBAC writes +/-$40 to $40.
    private static final int LIGHT_GRAVITY = 0x20;        // MoveSprite_LightGravity.
    private static final int WAIT_BEFORE_JUMP = 0x40;     // loc_8BBE0.
    private static final int SLIDE_WAIT = 0x20;           // loc_8BC52.
    private static final int FLOOR_MIN_PATROL = -1;       // ObjHitFloor2_DoRoutine lower bound.
    private static final int FLOOR_MIN_SLIDE = -2;        // loc_8BC6C/loc_8BCBA lower bound.
    private static final int FLOOR_MAX = 0x0C;
    private static final int SNOWDUST_Y_OFFSET = 0x0C;    // ChildObjDat_8BDFA.

    private static final int[] START_FRAMES = {0, 1, 0, 2}; // byte_8BE0A, after delay/loop bytes.
    private static final int START_INITIAL_DELAY = 7;
    private static final int START_LOOP_COUNT = 0x10;
    private static final int[] HOP_FRAMES = {3, 3, 4};    // byte_8BE11 after delay byte.
    private static final int[] SLIDE_RECOVER_FRAMES = {8, 8, 7, 6, 5, 4, 3}; // byte_8BE16.
    private static final int RAW_DELAY = 3;
    private static final int[] FLOOR_ANGLE_FRAMES = {
            4, 5, 6, 6, 7, 7, 8, 8, 8, 8, 7, 7, 6, 6, 5, 4
    };
    private static final int[] SNOWDUST_FRAMES = {0, 1, 2, 3, 4, 5, 4, 3, 2, 1, 0};

    private enum State {
        INIT,
        PATROL,
        HOP,
        FALL,
        SLIDE_WAIT,
        SLIDE_RECOVER,
        DECELERATE,
        WAIT
    }

    private enum WaitCallback {
        JUMP,
        RESTART_PATROL
    }

    private State state = State.INIT;
    private WaitCallback waitCallback = WaitCallback.JUMP;
    private int yRadius = INITIAL_Y_RADIUS;
    private int acceleration;
    private int routineTimer;
    private int rawDelay;
    private int rawLoopCounter;
    private boolean rawGetFasterPrimed;

    public PenguinatorBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Penguinator",
                Sonic3kObjectArtKeys.ICZ_PENGUINATOR, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity player) {
        if (isDestroyed()) {
            return;
        }
        // Obj_WaitOffscreen publishes a temporary $20-by-$20 sprite and starts
        // the real routine when Render_Sprites marks that placeholder visible.
        // Its width_pixels/height_pixels values are half extents, so activation
        // begins before the object's centre enters the viewport.
        if (state == State.INIT
                && !isWithinRenderSpriteBounds(WAIT_OFFSCREEN_HALF_SIZE, WAIT_OFFSCREEN_HALF_SIZE)) {
            return;
        }

        switch (state) {
            case INIT -> initializePatrol();
            case PATROL -> updatePatrol();
            case HOP -> updateHop();
            case FALL -> updateFall();
            case SLIDE_WAIT -> updateSlideWait();
            case SLIDE_RECOVER -> updateSlideRecover();
            case DECELERATE -> updateDecelerate();
            case WAIT -> updateWait();
        }
    }

    private void initializePatrol() {
        state = State.PATROL;
        yRadius = INITIAL_Y_RADIUS;
        rawDelay = START_INITIAL_DELAY;
        rawLoopCounter = 0;
        rawGetFasterPrimed = false;
        animFrame = 0;
        animTimer = 0;
        routineTimer = 0;
        acceleration = facingLeft ? -PATROL_ACCEL : PATROL_ACCEL;
    }

    private void updatePatrol() {
        boolean animationTick = animateRawGetFaster();
        if (state != State.PATROL) {
            return;
        }
        if (animationTick && rawDelay <= 2) {
            maybeStartHopFromGroundAngle();
            return;
        }

        xVelocity += acceleration;
        moveWithVelocity();
        snapToPredictedFloorOrReverse(FLOOR_MIN_PATROL);
    }

    private boolean animateRawGetFaster() {
        if (!rawGetFasterPrimed) {
            rawDelay = START_INITIAL_DELAY;
            rawLoopCounter = 0;
            rawGetFasterPrimed = true;
        }

        animTimer--;
        if (animTimer >= 0) {
            return false;
        }

        int nextFrame = animFrame + 1;
        if (nextFrame >= START_FRAMES.length) {
            nextFrame = 0;
            if (rawDelay == 0) {
                rawLoopCounter++;
                mappingFrame = START_FRAMES[nextFrame];
                animFrame = nextFrame;
                animTimer = rawDelay;
                if (rawLoopCounter >= START_LOOP_COUNT) {
                    rawLoopCounter = 0;
                    rawGetFasterPrimed = false;
                    enterHop();
                }
                return true;
            }
            rawDelay--;
        }

        animFrame = nextFrame;
        mappingFrame = START_FRAMES[nextFrame];
        animTimer = rawDelay;
        return true;
    }

    private void maybeStartHopFromGroundAngle() {
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDistWithFlipAwareAngle(
                currentX, currentY, yRadius);
        byte angle = floor.foundSurface() ? floor.angle() : 0;
        // ObjCheckFloorDist clears the angle byte when FindFloor publishes its
        // odd-angle sentinel rather than a usable surface direction.
        if ((angle & 1) != 0) {
            angle = 0;
        }
        if (angle == 0) {
            enterHop();
            return;
        }

        int adjusted = angle & 0xFF;
        if (!facingLeft) {
            adjusted ^= 0x40;
        }
        if ((adjusted & 0x40) != 0) {
            enterHop();
            return;
        }

        state = State.WAIT;
        routineTimer = WAIT_BEFORE_JUMP;
        waitCallback = WaitCallback.JUMP;
    }

    private void enterHop() {
        state = State.HOP;
        xVelocity = facingLeft ? -JUMP_X_SPEED : JUMP_X_SPEED;
        acceleration = facingLeft ? SLIDE_ACCEL : -SLIDE_ACCEL;
        // loc_8BBAC only replaces $30/$34 with the hop script and callback.
        // Animate_RawGetFaster's anim_frame, timer, and visible mapping carry
        // through both the direct and Obj_Wait callback paths.
    }

    private void updateHop() {
        moveWithVelocity();
        if (animateRaw(HOP_FRAMES, RAW_DELAY)) {
            enterFall();
        }
    }

    private void enterFall() {
        state = State.FALL;
        yRadius = SLIDE_Y_RADIUS;
        yVelocity = 0;
    }

    private void updateFall() {
        moveWithVelocity();
        yVelocity += LIGHT_GRAVITY;

        if (yVelocity < 0) {
            return;
        }

        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, yRadius);
        if (!floor.foundSurface() || floor.distance() > 0) {
            return;
        }

        currentY += floor.distance();
        enterSlideWait(floor.angle());
    }

    private void enterSlideWait(byte floorAngle) {
        int angle = floorAngle;
        if (angle < 0) {
            angle = -angle;
        }
        if ((angle & 0xF8) != 0) {
            int adjusted = floorAngle & 0xFF;
            if (xVelocity >= 0) {
                adjusted ^= 0x40;
            }
            if ((adjusted & 0x40) == 0) {
                reverseDirection();
            }
        }

        state = State.SLIDE_WAIT;
        routineTimer = SLIDE_WAIT;
        yVelocity = 0;
    }

    private void updateSlideWait() {
        TerrainCheckResult floor = checkPredictedFloor();
        if (!floor.foundSurface() || floor.distance() >= FLOOR_MAX) {
            enterFall();
            return;
        }
        if (floor.distance() < FLOOR_MIN_SLIDE) {
            reverseDirection();
            return;
        }

        currentY += floor.distance();
        updateMappingFrameFromAngle(floor.angle());
        emitSlideDustIfDue();
        moveWithVelocity();
        routineTimer--;
        if (routineTimer < 0) {
            enterSlideRecover();
        }
    }

    private void enterSlideRecover() {
        state = State.SLIDE_RECOVER;
        animFrame = 8 - mappingFrame; // loc_8BC94: moveq #8,d0; sub.b mapping_frame,d0.
        animTimer = 0;
    }

    private void updateSlideRecover() {
        TerrainCheckResult floor = checkPredictedFloor();
        if (!floor.foundSurface() || floor.distance() >= FLOOR_MAX) {
            enterFall();
            return;
        }
        if (floor.distance() < FLOOR_MIN_SLIDE) {
            reverseDirection();
            return;
        }

        currentY += floor.distance();
        moveWithVelocity();
        if (animateRaw(SLIDE_RECOVER_FRAMES, RAW_DELAY)) {
            enterDecelerate();
        }
    }

    private void enterDecelerate() {
        state = State.DECELERATE;
        mappingFrame = 0;
        yRadius = INITIAL_Y_RADIUS;
        currentY -= 4; // loc_8BCDC: subq.w #4,y_pos(a0)
    }

    private void updateDecelerate() {
        xVelocity += acceleration;
        if (xVelocity != 0) {
            TerrainCheckResult floor = checkPredictedFloor();
            if (floor.foundSurface()
                    && floor.distance() >= FLOOR_MIN_SLIDE
                    && floor.distance() < FLOOR_MAX) {
                currentY += floor.distance();
                moveWithVelocity();
                return;
            }
        }

        state = State.WAIT;
        routineTimer = spawn.subtype() & 0xFF;
        waitCallback = WaitCallback.RESTART_PATROL;
        facingLeft = !facingLeft;
    }

    private void updateWait() {
        routineTimer--;
        if (routineTimer >= 0) {
            return;
        }

        if (waitCallback == WaitCallback.JUMP) {
            enterHop();
        } else {
            initializePatrol();
        }
    }

    private boolean snapToPredictedFloorOrReverse(int minDistance) {
        TerrainCheckResult floor = checkPredictedFloor();
        if (!floor.foundSurface()
                || floor.distance() < minDistance
                || floor.distance() >= FLOOR_MAX) {
            reverseDirection();
            return false;
        }
        currentY += floor.distance();
        return true;
    }

    private TerrainCheckResult checkPredictedFloor() {
        int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
        int predictedX = (xPos24 + xVelocity) >> 8;
        return ObjectTerrainUtils.checkFloorDist(predictedX, currentY, yRadius);
    }

    private void reverseDirection() {
        xVelocity = -xVelocity;
        acceleration = -acceleration;
        facingLeft = !facingLeft;
    }

    private void updateMappingFrameFromAngle(byte floorAngle) {
        int index = ((floorAngle & 0xFF) >>> 3) & 0x0F;
        mappingFrame = FLOOR_ANGLE_FRAMES[index];
    }

    private boolean animateRaw(int[] frames, int delay) {
        animTimer--;
        if (animTimer >= 0) {
            return false;
        }

        animFrame++;
        if (animFrame >= frames.length) {
            animFrame = 0;
            animTimer = 0;
            return true;
        }

        mappingFrame = frames[animFrame];
        animTimer = delay;
        return false;
    }

    private void emitSlideDustIfDue() {
        if ((routineTimer & 3) != 0) {
            return;
        }

        ObjectServices svc = tryServices();
        if (svc != null) {
            svc.playSfx(Sonic3kSfx.SLIDE_SKID_QUIET.id);
            spawnChild(() -> new PenguinatorSnowdustInstance(spawn, currentX, currentY + SNOWDUST_Y_OFFSET));
        }
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s vx=%04X vy=%04X acc=%04X wait=%04X raw=%02X/%02X anim=%02X/%02X map=%02X face=%s",
                state, xVelocity & 0xFFFF, yVelocity & 0xFFFF, acceleration & 0xFFFF,
                routineTimer & 0xFFFF, rawDelay & 0xFF, rawLoopCounter & 0xFF,
                animFrame & 0xFF, animTimer & 0xFF, mappingFrame & 0xFF,
                facingLeft ? "L" : "R");
    }

    private static final class PenguinatorSnowdustInstance extends AbstractObjectInstance
            implements SpawnRewindRecreatable {
        private static final int PRIORITY_BUCKET = 5;
        private int x;
        private int y;
        private int frameIndex;
        private int frameTimer;
        private int mappingFrame;

        private PenguinatorSnowdustInstance(ObjectSpawn ownerSpawn, int x, int y) {
            super(ownerSpawn, "PenguinatorSnowdust");
            this.x = x;
            this.y = y;
        }

        private PenguinatorSnowdustInstance(ObjectSpawn spawn) {
            super(spawn, "PenguinatorSnowdust");
            this.x = spawn.x();
            this.y = spawn.y();
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity player) {
            frameTimer--;
            if (frameTimer >= 0) {
                return;
            }

            frameIndex++;
            if (frameIndex >= SNOWDUST_FRAMES.length) {
                setDestroyed(true);
                return;
            }

            mappingFrame = SNOWDUST_FRAMES[frameIndex];
            frameTimer = 0;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(x, y);
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
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.ICZ_SNOWDUST);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, x, y, false, false);
            }
        }
    }
}
