package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K SKL Obj $8D - Mushmeanie.
 *
 * <p>ROM reference: {@code Obj_Mushmeanie} at {@code sonic3k.asm:193522}.
 * The parent body uses collision flag {@code $D7} and takes two attacks: the
 * first clears collision for {@code $20} frames and pops the shell, the second
 * runs the normal badnik defeat path.
 */
public final class MushmeanieBadnikInstance extends AbstractS3kBadnikInstance
        implements SpawnRewindRecreatable {

    private static final int COLLISION_FLAGS = 0xD7;
    private static final int COLLISION_SIZE_INDEX = COLLISION_FLAGS & 0x3F;
    private static final int INITIAL_MAPPING_FRAME = 1;
    private static final int PRIORITY_BUCKET = 5;
    private static final int RENDER_HALF_WIDTH = 0x08;
    private static final int RENDER_HALF_HEIGHT = 0x08;
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;
    private static final int ACTIVATION_RANGE = 0x80;
    private static final int SHELL_RECOVER_FRAMES = 0x20;
    private static final int JUMP_Y_VELOCITY = -0x300;
    private static final int JUMP_X_SPEED = 0x100;
    private static final int LIGHT_GRAVITY = 0x20;
    private static final int SHELL_LIGHT_GRAVITY = 0x20;
    private static final int SHELL_LAUNCH_X_VELOCITY = 0x200;
    private static final int SHELL_LAUNCH_Y_VELOCITY = -0x200;
    private static final int FLOOR_Y_RADIUS = 0x12;
    private static final int[] WAKE_ANIM_FRAMES = {1, 2, 3};
    private static final int[] WAKE_ANIM_DELAYS = {2, 2, 0};
    private static final int[] LANDING_ANIM_FRAMES = {3, 2, 1, 2, 3};
    private static final int[] LANDING_ANIM_DELAYS = {2, 2, 2, 2, 0};
    private static final int[] LANDING_Y_OFFSETS = {-3, 3, 3, -3, -3, -3};

    private enum State {
        SLEEPING,
        WAKING,
        JUMPING,
        LANDING
    }

    private State state = State.SLEEPING;
    private int wakeAnimOffset;
    private int wakeAnimTimer;
    private int shellHitsRemaining = 2;
    private int shellRecoverTimer;
    private int currentCollisionSizeIndex = COLLISION_SIZE_INDEX;
    private int landingAnimIndex;
    private int landingAnimTimer;
    private int shellReleasePlayerXVelocity;
    private ShellChild shellChild;
    private boolean shellChildCreated;
    private boolean shellReleased;
    private boolean waitingForOnscreen = true;
    private boolean initialized;

    public MushmeanieBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Mushmeanie", Sonic3kObjectArtKeys.MUSHMEANIE,
                COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        mappingFrame = INITIAL_MAPPING_FRAME;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return currentCollisionSizeIndex;
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        if (isDestroyed() || currentCollisionSizeIndex == 0) {
            return;
        }

        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        shellHitsRemaining--;
        if (shellHitsRemaining <= 0) {
            defeat(player);
            return;
        }

        currentCollisionSizeIndex = 0;
        shellRecoverTimer = SHELL_RECOVER_FRAMES;
        if (player != null) {
            player.setXSpeed((short) -player.getXSpeed());
            player.setYSpeed((short) -player.getYSpeed());
            player.setGSpeed((short) -player.getGSpeed());
            shellReleasePlayerXVelocity = player.getXSpeed();
        }
        shellReleased = true;
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
            createShellChildOnce();
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        createShellChildOnce();
        updateShellRecovery();

        switch (state) {
            case SLEEPING -> updateSleeping(playerEntity);
            case WAKING -> updateWaking(playerEntity);
            case JUMPING -> updateJumping();
            case LANDING -> updateLanding(playerEntity);
        }

        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public int getCollisionFlags() {
        if (waitingForOnscreen || !initialized || currentCollisionSizeIndex == 0) {
            return 0;
        }
        return COLLISION_FLAGS;
    }

    @Override
    public boolean usesEnemyTouchCategoryOverride() {
        return true;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }

    private void createShellChildOnce() {
        if (shellChildCreated) {
            return;
        }
        shellChildCreated = true;
        shellChild = spawnChild(() -> new ShellChild(this));
    }

    private void updateShellRecovery() {
        if (shellRecoverTimer <= 0) {
            return;
        }
        shellRecoverTimer--;
        if (shellRecoverTimer == 0) {
            currentCollisionSizeIndex = COLLISION_SIZE_INDEX;
        }
    }

    private void updateSleeping(PlayableEntity playerEntity) {
        int dx = closestNativePlayerHorizontalDistance(playerEntity);
        if (dx == Integer.MAX_VALUE) {
            return;
        }
        if (dx >= ACTIVATION_RANGE) {
            return;
        }
        state = State.WAKING;
        wakeAnimOffset = 0;
        wakeAnimTimer = 0;
        mappingFrame = 1;
    }

    private int closestNativePlayerHorizontalDistance(PlayableEntity updatePlayer) {
        int closest = updatePlayer == null ? Integer.MAX_VALUE
                : findSonicTailsHorizontalDistance(updatePlayer);
        PlayableEntity nativeP2 = tryServices() != null
                ? tryServices().playerQuery().nativeP2OrNull()
                : null;
        if (nativeP2 != null) {
            int sidekickDistance = findSonicTailsHorizontalDistance(nativeP2);
            if (sidekickDistance < closest) {
                closest = sidekickDistance;
            }
        }
        return closest;
    }

    private void updateWaking(PlayableEntity playerEntity) {
        wakeAnimTimer--;
        if (wakeAnimTimer >= 0) {
            return;
        }

        wakeAnimOffset += 2;
        int pairIndex = wakeAnimOffset >> 1;
        if (pairIndex >= WAKE_ANIM_FRAMES.length) {
            wakeAnimOffset = 0;
            startJump(playerEntity);
            return;
        }

        mappingFrame = WAKE_ANIM_FRAMES[pairIndex];
        wakeAnimTimer = WAKE_ANIM_DELAYS[pairIndex];
        currentY -= 3;
    }

    private void startJump(PlayableEntity playerEntity) {
        state = State.JUMPING;
        yVelocity = JUMP_Y_VELOCITY;
        PlayableEntity target = playerEntity;
        if (tryServices() != null) {
            PlayableEntity mainPlayer = tryServices().playerQuery().mainPlayerOrNull();
            if (mainPlayer != null) {
                target = mainPlayer;
            }
        }
        xVelocity = target != null && target.getCentreX() < currentX
                ? -JUMP_X_SPEED : JUMP_X_SPEED;
    }

    private void updateJumping() {
        moveWithVelocity();
        yVelocity += LIGHT_GRAVITY;
        checkSideWallBounce();
        if (yVelocity < 0) {
            return;
        }

        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, FLOOR_Y_RADIUS);
        if (!floor.hasCollision()) {
            return;
        }
        currentY += floor.distance();
        state = State.LANDING;
        mappingFrame = 3;
        landingAnimIndex = 0;
        landingAnimTimer = 0;
    }

    private void checkSideWallBounce() {
        TerrainCheckResult wall = xVelocity >= 0
                ? ObjectTerrainUtils.checkRightWallDist(currentX + 8, currentY)
                : ObjectTerrainUtils.checkLeftWallDist(currentX - 8, currentY);
        if (!wall.hasCollision() || wall.distance() >= 0) {
            return;
        }
        currentX += wall.distance();
        xVelocity = -xVelocity;
        facingLeft = xVelocity < 0;
    }

    private void updateLanding(PlayableEntity playerEntity) {
        landingAnimTimer--;
        if (landingAnimTimer >= 0) {
            return;
        }

        landingAnimIndex += 2;
        int pairIndex = landingAnimIndex >> 1;
        if (pairIndex >= LANDING_ANIM_FRAMES.length) {
            startJump(playerEntity);
            return;
        }

        mappingFrame = LANDING_ANIM_FRAMES[pairIndex];
        landingAnimTimer = LANDING_ANIM_DELAYS[pairIndex];
        currentY += LANDING_Y_OFFSETS[pairIndex];
    }

    public String getStateName() {
        return state.name();
    }

    public int getXVelocity() {
        return xVelocity;
    }

    public int getYVelocity() {
        return yVelocity;
    }

    public int getShellRecoverTimer() {
        return shellRecoverTimer;
    }

    public int getCollisionSizeIndexForTest() {
        return getCollisionSizeIndex();
    }

    private static MushmeanieBadnikInstance findLiveMushmeanieParentForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        MushmeanieBadnikInstance nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(instance instanceof MushmeanieBadnikInstance mushmeanie) || mushmeanie.isDestroyed()) {
                continue;
            }
            ObjectSpawn candidateSpawn = mushmeanie.getSpawn();
            if (spawn.layoutIndex() >= 0 && candidateSpawn.layoutIndex() == spawn.layoutIndex()) {
                return mushmeanie;
            }
            int distance = Math.abs(candidateSpawn.x() - spawn.x())
                    + Math.abs(candidateSpawn.y() - spawn.y());
            if (distance < nearestDistance) {
                nearest = mushmeanie;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    public static final class ShellChild extends AbstractObjectInstance implements RewindRecreatable {
        private static final int RENDER_HALF_WIDTH = 0x0C;
        private static final int RENDER_HALF_HEIGHT = 0x08;
        private static final int PRIORITY_BUCKET = 4;

        private MushmeanieBadnikInstance parent;
        private int x;
        private int y;
        private int xSubpixel;
        private int ySubpixel;
        private int xVelocity;
        private int yVelocity;
        private boolean launched;

        ShellChild(MushmeanieBadnikInstance parent) {
            super(new ObjectSpawn(parent.currentX, parent.currentY, 0, 0, 0, false, 0),
                    "MushmeanieShell");
            this.parent = parent;
            this.x = parent.currentX;
            this.y = parent.currentY;
        }

        @Override
        public ShellChild recreateForRewind(RewindRecreateContext ctx) {
            MushmeanieBadnikInstance liveParent = findLiveMushmeanieParentForRewind(ctx);
            return liveParent == null ? null : new ShellChild(liveParent);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (!launched) {
                if (parent.isDestroyed()) {
                    setDestroyed(true);
                    return;
                }
                x = parent.currentX;
                y = parent.currentY;
                if (!parent.shellReleased) {
                    updateDynamicSpawn(x, y);
                    return;
                }
                launch(playerEntity);
            }
            moveWithLightGravity();
            checkDeleteXY();
            updateDynamicSpawn(x, y);
        }

        private void launch(PlayableEntity playerEntity) {
            launched = true;
            xVelocity = SHELL_LAUNCH_X_VELOCITY;
            if (parent.shellReleasePlayerXVelocity >= 0) {
                xVelocity = -xVelocity;
            }
            yVelocity = SHELL_LAUNCH_Y_VELOCITY;
        }

        private void moveWithLightGravity() {
            int xPos24 = (x << 8) | (xSubpixel & 0xFF);
            int yPos24 = (y << 8) | (ySubpixel & 0xFF);
            xPos24 += xVelocity;
            yPos24 += yVelocity;
            x = xPos24 >> 8;
            y = yPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
            ySubpixel = yPos24 & 0xFF;
            yVelocity += SHELL_LIGHT_GRAVITY;
        }

        private void checkDeleteXY() {
            if (!isOnScreen(0)) {
                setDestroyedByOffscreen();
            }
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
        public int getOnScreenHalfWidth() {
            return RENDER_HALF_WIDTH;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return RENDER_HALF_HEIGHT;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MUSHMEANIE);
            if (renderer != null) {
                renderer.drawFrameIndex(0, x, y, false, false, 2);
            }
        }

        public int getXVelocity() {
            return xVelocity;
        }

        public int getYVelocity() {
            return yVelocity;
        }
    }
}
