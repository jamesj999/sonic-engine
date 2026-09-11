package com.openggf.game.sonic3k.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/** One of the four {@code ChildObjDat_6EDD4 -> loc_6E95A} orbiting arms. */
final class CnzEndBossArmChild extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = TouchResponseProfile.fromCanonical(
            com.openggf.game.profiles.touchresponse.TouchResponseProfile.standardEnemy(true));
    private final CnzEndBossInstance boss;
    private int centreX;
    private int centreY;
    private int angle;
    private int angularStep = 1;
    private int speedTimer = 0x40;
    private int frame = 1;
    private int animTimer;
    private int animIndex;
    private int armSubtype;
    private boolean collisionEnabled = true;
    private boolean scattered;
    private int xFixed;
    private int yFixed;
    private int xVelocity;
    private int yVelocity;
    private int flickerCounter;
    private int savedAngle;
    private MotionPhase motionPhase = MotionPhase.INACTIVE;
    private enum MotionPhase { INACTIVE, SPIN, WIND_DOWN, REALIGN, WAIT_CLEAR }
    private static final int[][] SCATTER_VELOCITIES = {
            {-0x100, -0x100},
            {0x100, -0x100},
            {-0x200, -0x200},
            {0x200, -0x200}
    };
    // ROM byte_6EE0E, including the long trailing frame-1 hold.
    private static final int[] ANIMATION_FRAMES = {1, 3, 1, 3, 1, 3, 1};
    private static final int[] ANIMATION_DELAYS = {0, 0, 0, 0, 4, 0, 9};
    private static final int[] ANGLE_X = {
            0,1,2,3,4,5,6,8,9,10,11,12,13,14,15,16,
            17,18,19,20,21,22,23,24,24,25,26,27,28,29,30,30,
            31,32,33,33,34,35,35,36,37,37,38,38,39,39,40,40,
            41,41,41,42,42,42,43,43,43,43,44,44,44,44,44,44
    };

    CnzEndBossArmChild(CnzEndBossInstance boss, int phase) {
        super(new ObjectSpawn(boss.getCentreX(), boss.getCentreY() + 8, 0,
                        phase >>> 5 & 7, 0, false, 0),
                "CNZEndBossArm");
        this.boss = boss;
        this.angle = phase & 0xFF;
        this.armSubtype = phase >>> 6 & 3;
    }

    @Override public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        CnzEndBossInstance restoredBoss = CnzEndBossRewindLinks.boss(ctx);
        return restoredBoss == null ? null
                : new CnzEndBossArmChild(restoredBoss, (ctx.spawn().subtype() & 7) << 5);
    }
    @Override public int getX() { return centreX - 8; }
    @Override public int getY() { return centreY - 0x10; }
    public int getCentreX() { return centreX; }
    public int getCentreY() { return centreY; }

    @Override
    public void update(int vIntRunCount, PlayableEntity player) {
        if (scattered) {
            updateScatter();
            return;
        }
        if (boss.isDestroyed()) {
            ObjectLifetimeOps.expireDynamic(this);
            return;
        }
        if (boss.defeatStarted()) {
            collisionEnabled = false;
            if (boss.defeatScatterStarted()) {
                beginDefeatScatter();
            }
            return;
        }
        CnzEndBossInstance.Routine routine = boss.nativeRoutine();
        updateOrbitMotion(routine);
        boolean active = motionPhase != MotionPhase.INACTIVE;
        updateArmAnimation(active);
        int offsetX = angleXOffset(angle);
        if (boss.facingRight()) offsetX = -offsetX;
        centreX = boss.getCentreX() + offsetX;
        centreY = boss.getCentreY() + 8;
        // ROM sub_6EBF0 deliberately preserves byte_6EE0E's frame 3.
        if (frame != 3) frame = frameForAngle(angle);
        updateDynamicSpawn(centreX, centreY);
    }

    private void updateOrbitMotion(CnzEndBossInstance.Routine routine) {
        if (motionPhase == MotionPhase.INACTIVE) {
            if (routine != CnzEndBossInstance.Routine.CHARGE) {
                resetInactiveMotion();
                return;
            }
            savedAngle = angle;
            motionPhase = MotionPhase.SPIN;
        }

        if (motionPhase == MotionPhase.SPIN
                && routine == CnzEndBossInstance.Routine.WIND_DOWN) {
            motionPhase = MotionPhase.WIND_DOWN;
            speedTimer = 0x40;
        }

        switch (motionPhase) {
            case SPIN -> {
                angle = (angle + angularStep) & 0xFF;
                if (!boss.magneticFieldActive() && --speedTimer < 0) {
                    speedTimer = 0x40;
                    angularStep = Math.min(4, angularStep + 1);
                }
            }
            case WIND_DOWN -> {
                angle = (angle + angularStep) & 0xFF;
                if (--speedTimer < 0) {
                    speedTimer = 0x40;
                    if (angularStep == 1) {
                        motionPhase = MotionPhase.REALIGN;
                    } else {
                        angularStep--;
                    }
                }
            }
            case REALIGN -> {
                angle = (angle + 1) & 0xFF;
                if (angle == savedAngle) motionPhase = MotionPhase.WAIT_CLEAR;
            }
            case WAIT_CLEAR -> {
                if (!isParentBitThreeInterval(routine)) resetInactiveMotion();
            }
            case INACTIVE -> { }
        }
    }

    private void resetInactiveMotion() {
        motionPhase = MotionPhase.INACTIVE;
        angularStep = 1;
        speedTimer = 0x40;
    }

    /** ROM parent-bit-4 signal: convert this same arm slot into flickering debris. */
    void beginDefeatScatter() {
        if (scattered) return;
        scattered = true;
        collisionEnabled = false;
        frame = 1;
        xFixed = centreX << 8;
        yFixed = centreY << 8;
        xVelocity = SCATTER_VELOCITIES[armSubtype][0];
        yVelocity = SCATTER_VELOCITIES[armSubtype][1];
        flickerCounter = 0;
    }

    private void updateScatter() {
        xFixed = S3kBossFlickerMove.integrate(xFixed, xVelocity);
        yFixed = S3kBossFlickerMove.integrate(yFixed, yVelocity);
        yVelocity += 0x38;
        centreX = xFixed >> 8;
        centreY = yFixed >> 8;
        flickerCounter++;
        var objectServices = tryServices();
        if (objectServices != null && objectServices.camera() != null) {
            int cameraX = Short.toUnsignedInt(objectServices.camera().getX());
            int cameraY = Short.toUnsignedInt(objectServices.camera().getY());
            if (S3kBossFlickerMove.isOutsideNativeBounds(centreX, centreY, cameraX, cameraY)) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
        }
        updateDynamicSpawn(centreX, centreY);
    }

    private static boolean isParentBitThreeInterval(CnzEndBossInstance.Routine routine) {
        return routine == CnzEndBossInstance.Routine.CHARGE
                || routine == CnzEndBossInstance.Routine.WIND_DOWN;
    }

    private void updateArmAnimation(boolean active) {
        if (!active) {
            frame = 1;
            animIndex = 0;
            animTimer = ANIMATION_DELAYS[0];
            return;
        }
        if (--animTimer >= 0) return;
        animIndex++;
        if (animIndex >= ANIMATION_FRAMES.length) animIndex = 0;
        frame = ANIMATION_FRAMES[animIndex];
        animTimer = ANIMATION_DELAYS[animIndex];
    }

    private static int angleXOffset(int angle) {
        int a = angle & 0xFF;
        int quadrant = a >>> 6;
        return switch (quadrant) {
            case 0 -> ANGLE_X[a];
            case 1 -> ANGLE_X[0x7F - a];
            case 2 -> -ANGLE_X[a & 0x3F];
            default -> -ANGLE_X[0xFF - a];
        };
    }

    private static int frameForAngle(int angle) {
        int a = angle & 0xFF;
        if (a < 0x30) return 1;
        if (a < 0x58) return 8;
        if (a < 0xA8) return 2;
        if (a < 0xD0) return 8;
        return 1;
    }

    int frameForTest() { return frame; }
    int angleForTest() { return angle; }
    int angularStepForTest() { return angularStep; }
    boolean isRealigningForTest() { return motionPhase == MotionPhase.REALIGN; }
    int xVelocityForTest() { return xVelocity; }
    int yVelocityForTest() { return yVelocity; }
    boolean visibleForTest() { return !scattered || S3kBossFlickerMove.isVisible(flickerCounter); }

    @Override public int getCollisionFlags() { return collisionEnabled ? 0x9E : 0; }
    @Override public int getCollisionProperty() { return 0; }
    @Override public TouchRegion[] getMultiTouchRegions() {
        return new TouchRegion[] { new TouchRegion(centreX, centreY, getCollisionFlags()) };
    }
    @Override public TouchResponseProfile getTouchResponseProfile() { return TOUCH_RESPONSE_PROFILE; }
    @Override public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
        return TOUCH_RESPONSE_PROFILE;
    }
    @Override public boolean isPersistent() { return true; }
    @Override public int getPriorityBucket() { return (angle + 0x40 & 0x80) == 0 ? 4 : 5; }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (!visibleForTest()) return;
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_END_BOSS);
        if (renderer != null && renderer.isReady()) renderer.drawFrameIndex(frame, centreX, centreY, false, false);
    }
}
