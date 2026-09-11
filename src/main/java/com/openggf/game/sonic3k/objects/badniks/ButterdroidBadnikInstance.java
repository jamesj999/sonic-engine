package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;

/**
 * S3K SKL Obj $8F - Butterdroid.
 *
 * <p>ROM reference: {@code Obj_Butterdroid} at {@code sonic3k.asm:193990}.
 * The main routine faces the nearest player, calls {@code Chase_Object} with
 * max speed {@code $100} and acceleration {@code 4}, then runs the raw
 * eight-step wing animation.
 */
public final class ButterdroidBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {

    private static final int COLLISION_SIZE_INDEX = 0x17;
    private static final int PRIORITY_BUCKET = 5;
    private static final int RENDER_HALF_WIDTH = 0x0C;
    private static final int RENDER_HALF_HEIGHT = 0x0C;
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;
    private static final int CHASE_MAX_SPEED = 0x100;
    private static final int CHASE_ACCELERATION = 4;
    private static final int[] ANIMATION_SCRIPT = {7, 0, 1, 2, 3, 4, 3, 2, 1, 0xFC};
    private static final int ANIMATION_DELAY = 7;

    private int animationFrame;
    private int animationTimer;
    private boolean waitingForOnscreen = true;
    private boolean initialized;

    public ButterdroidBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Butterdroid", Sonic3kObjectArtKeys.BUTTERDROID,
                COLLISION_SIZE_INDEX, PRIORITY_BUCKET, true);
        mappingFrame = 0;
        animationTimer = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (waitingForOnscreen) {
            if (!isOnScreen(WAIT_OFFSCREEN_MARGIN)) {
                updateDynamicSpawn(currentX, currentY);
                return;
            }
            waitingForOnscreen = false;
            updateDynamicSpawn(currentX, currentY);
            return;
        }
        if (!initialized) {
            initialized = true;
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        PlayableEntity target = closestNativePlayerByHorizontalDistance(playerEntity);
        if (target != null) {
            facingLeft = !findSonicTailsTargetIsRight(target);
            chase(target.getCentreX(), target.getCentreY());
        }

        moveWithVelocity();
        animateRaw();
        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public int getCollisionFlags() {
        return waitingForOnscreen || !initialized ? 0 : super.getCollisionFlags();
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }

    private void chase(int targetX, int targetY) {
        int currentXWord = currentX & 0xFFFF;
        int targetXWord = targetX & 0xFFFF;
        boolean xEqual = currentXWord == targetXWord;
        if (!xEqual) {
            int xAccel = currentXWord > targetXWord ? -CHASE_ACCELERATION : CHASE_ACCELERATION;
            int nextXVelocity = xVelocity + xAccel;
            if (nextXVelocity >= -CHASE_MAX_SPEED && nextXVelocity <= CHASE_MAX_SPEED) {
                xVelocity = nextXVelocity;
            }
        }

        int currentYWord = currentY & 0xFFFF;
        int targetYWord = targetY & 0xFFFF;
        boolean yEqual = currentYWord == targetYWord;
        if (yEqual) {
            if (xEqual) {
                xVelocity = 0;
                yVelocity = 0;
            }
            return;
        }

        int yAccel = currentYWord > targetYWord ? -CHASE_ACCELERATION : CHASE_ACCELERATION;
        int nextYVelocity = yVelocity + yAccel;
        if (nextYVelocity >= -CHASE_MAX_SPEED && nextYVelocity <= CHASE_MAX_SPEED) {
            yVelocity = nextYVelocity;
        }
    }

    private void animateRaw() {
        animationTimer--;
        if (animationTimer >= 0) {
            return;
        }

        animationFrame++;
        int value = ANIMATION_SCRIPT[1 + animationFrame];
        if (value < 0x80) {
            animationTimer = ANIMATION_DELAY;
            mappingFrame = value;
            return;
        }

        if (value == 0xFC) {
            animationFrame = 0;
            animationTimer = ANIMATION_DELAY;
            mappingFrame = ANIMATION_SCRIPT[1];
            return;
        }

        throw new IllegalStateException("Unsupported Butterdroid Animate_Raw command: " + value);
    }

    public int getXVelocity() {
        return xVelocity;
    }

    public int getYVelocity() {
        return yVelocity;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    public boolean isFacingLeft() {
        return facingLeft;
    }
}
