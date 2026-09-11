package com.openggf.game.sonic2.objects.bosses;

import com.openggf.game.PlayableEntity;
import com.openggf.camera.Camera;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2ObjectIds;
import com.openggf.level.objects.ObjectAnimationState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.boss.AbstractBossChild;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * EHZ Boss - Wheel component.
 * ROM Reference: s2.asm:63267-63412 (loc_2F664 - Obj56_Wheel)
 */
public class EHZBossWheel extends AbstractBossChild implements RewindRecreatable {
    private static final int CAMERA_GATE_X = 0x28F0;
    private static final int BOUNDARY_LEFT = 0x28A0;
    private static final int BOUNDARY_RIGHT = 0x2B08;
    private static final int GRAVITY = 0x38;
    private static final int Y_RADIUS = 0x10;
    private static final int VEHICLE_FRAME_OFFSET = 7;

    private int subtype;
    private final ObjectAnimationState animationState;
    private int routineSecondary;
    private int timer;
    private int xVel;
    private int yVel;
    private int xFixed;
    private int yFixed;
    private int renderFlags;

    public EHZBossWheel(Sonic2EHZBossInstance parent, int subtype, int xOffset, int priority) {
        super(parent, "EHZ Boss Wheel", priority, Sonic2ObjectIds.EHZ_BOSS);
        this.subtype = subtype;
        this.routineSecondary = 0;
        this.timer = 0x0A;
        this.xVel = 0;
        this.yVel = 0;
        this.renderFlags = 0;
        // Animation selection based on wheel type, not render priority:
        // - Subtypes 0 and 1: foreground wheel art (larger, near-side)
        // - Subtype 2: background wheel art (smaller, far-side)
        boolean useBackgroundArt = (subtype == 2);
        this.animationState = new ObjectAnimationState(
                EHZBossAnimations.getVehicleAnimations(),
                useBackgroundArt ? 2 : 1,
                useBackgroundArt ? 6 : 4);

        this.currentX = 0x2AF0 + xOffset;
        this.currentY = parent.getInitialY() + 0x0C;
        this.xFixed = currentX << 16;
        this.yFixed = currentY << 16;
    }

    private EHZBossWheel(Sonic2EHZBossInstance parent) {
        this(parent, 0, 0, 3);
    }

    @Override
    public EHZBossWheel recreateForRewind(RewindRecreateContext ctx) {
        Sonic2EHZBossInstance boss = EhzBossRewindLinks.requireNearestBoss(ctx, "EHZ boss wheel");
        return new EHZBossWheel(boss);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed() || !shouldUpdate(vIntRunCount)) {
            return;
        }
        if (parent == null) {
            setDestroyed(true);
            return;
        }
        // Don't check parent.isDestroyed() - wheels manage their own lifecycle
        // They transition to defeat states (6, 8) and self-destruct when off-screen

        switch (routineSecondary) {
            case 0 -> updateApproach();
            case 2 -> updateIdle();
            case 4 -> updateActive();
            case 6 -> updateDefeatDelay();
            case 8 -> updateDefeatBounce();
            default -> updateActive();
        }

        updateDynamicSpawn();
    }

    private void updateApproach() {
        Camera camera = services().camera();
        if (camera != null && camera.getMinX() < CAMERA_GATE_X) {
            return;
        }

        yVel = 0x100;
        int targetX = switch (subtype) {
            case 0 -> 0x29EC;
            case 1 -> 0x29C4;
            default -> 0x29A4;
        };
        if (currentX > targetX) {
            currentX -= 1;
        } else {
            currentX = targetX;
            routineSecondary = 2;
        }
        xFixed = currentX << 16;

        objectMoveAndFall();
        applyFloorCheck();

        if (routineSecondary != 0) {
            xVel = -0x200;
        }

        animationState.update();
    }

    private void updateIdle() {
        Sonic2EHZBossInstance ehzParent = (Sonic2EHZBossInstance) parent;
        int flags = ehzParent.getCustomFlag(0x2D);
        boolean active = (flags & 0x02) != 0;
        if (!active) {
            return;
        }
        routineSecondary = 4;

        if (subtype < 2) {
            // Only foreground wheels (subtypes 0 and 1) contribute to Y calculation
            ehzParent.addToWheelYAccumulator(currentY);
        }
    }

    private void updateActive() {
        Sonic2EHZBossInstance ehzParent = (Sonic2EHZBossInstance) parent;
        renderFlags = ehzParent.getState().renderFlags;
        if (ehzParent.getState().defeated) {
            routineSecondary = 6;
            return;
        }

        if (currentX <= BOUNDARY_LEFT || currentX >= BOUNDARY_RIGHT) {
            renderFlags ^= 1;
            xVel = -xVel;
        }

        objectMoveAndFall();
        applyFloorCheck();
        yVel = 0x100;

        if (subtype < 2) {
            // Only foreground wheels (subtypes 0 and 1) contribute to Y calculation
            ehzParent.addToWheelYAccumulator(currentY);
        }

        animationState.update();
    }

    private void updateDefeatDelay() {
        timer--;
        if (timer >= 0) {
            return;
        }
        routineSecondary = 8;
        timer = 0x0A;
        yVel = -0x300;
        if (subtype == 2) {
            // Background wheel (subtype 2) bounces in opposite direction
            xVel = -xVel;
        }
    }

    private void updateDefeatBounce() {
        timer--;
        if (timer >= 0) {
            return;
        }

        objectMoveAndFall();
        TerrainCheckResult floor = applyFloorCheck();
        if (floor != null && floor.hasCollision() && floor.distance() < 0) {
            yVel = -0x200;
        }

        if (!isOnScreen(64)) {
            setDestroyed(true);
        }
    }

    private void objectMoveAndFall() {
        xFixed += (xVel << 8);
        yFixed += (yVel << 8);
        yVel += GRAVITY;
        currentX = xFixed >> 16;
        currentY = yFixed >> 16;
    }

    private TerrainCheckResult applyFloorCheck() {
        TerrainCheckResult result = ObjectTerrainUtils.checkFloorDist(currentX, currentY, Y_RADIUS);
        if (result.hasCollision() && result.distance() < 0) {
            currentY += result.distance();
            yFixed = currentY << 16;
        }
        return result;
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
        if (renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS) == null || !renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS).isReady()) {
            return;
        }

        // Wheel rotation uses V-flip (frames 5/7), vehicle direction uses H-flip
        boolean flipped = (renderFlags & 1) != 0;
        int frameIndex = VEHICLE_FRAME_OFFSET + animationState.getMappingFrame();
        renderManager.getRenderer(Sonic2ObjectArtKeys.EHZ_BOSS).drawFrameIndex(frameIndex, currentX, currentY, flipped, false);
    }
}
