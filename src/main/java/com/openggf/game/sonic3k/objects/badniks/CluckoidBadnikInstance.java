package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.rewind.RewindTransient;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.game.sonic3k.runtime.MhzZoneRuntimeState;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SpawnTrailingZeroIntsRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K SKL Obj $90 - Cluckoid.
 *
 * <p>ROM reference: {@code Obj_Cluckoid} at {@code sonic3k.asm:194045}. The
 * Cluckoid idles until the player is within {@code $80} X and {@code $40} Y,
 * then runs the raw breath animation. Wind pressure starts once the mapping
 * frame reaches 7 and breath child particles are emitted every 8 frames.
 */
public final class CluckoidBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {

    private static final int COLLISION_SIZE_INDEX = 0x1A;
    private static final int PRIORITY_BUCKET = 5;
    private static final int RENDER_HALF_WIDTH = 0x14;
    private static final int RENDER_HALF_HEIGHT = 0x10;
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;
    private static final int ARROW_CHILD_Y_OFFSET = 0x1C;
    private static final int ACTIVATION_X_RANGE = 0x80;
    private static final int ACTIVATION_Y_RANGE = 0x40;
    private static final int WIND_X_RANGE = 0x100;
    private static final int DUCK_ANIMATION_ID = 8;
    private static final int COOLDOWN_FRAMES = 0x60;
    private static final int BREATH_CHILD_X_ACCEL_LEFT = 0x20;
    private static final int BREATH_CHILD_Y_ACCELERATION = 8;
    private static final int[] BREATH_CHILD_X_SPEEDS = {0x600, 0x700, 0x800, 0x900};
    private static final int[] BREATH_CHILD_Y_SPEEDS = {-0x180, -0x200, -0x280, -0x300};
    private static final int[][] BREATH_CHILD_OFFSETS = {
            {0x10, 0x28}, {0x20, 0x28}, {0x30, 0x28}, {0x40, 0x28},
            {0x50, 0x28}, {0x60, 0x28}, {0x70, 0x28}, {0x80, 0x28},
            {0x10, 0x28}, {0x18, 0x28}, {0x30, 0x48}, {0x40, 0x48},
            {0x50, 0x48}, {0x60, 0x48}, {0x70, 0x48}, {0x80, 0x48},
            {0x10, 0x28}, {0x14, 0x28}, {0x18, 0x28}, {0x1C, 0x28},
            {0x20, 0x28}, {0, 0}, {0, 0}, {0, 0},
            {0x10, 0x28}, {0x18, 0x28}, {0x20, 0x28}, {0x30, 0x48},
            {0x40, 0x48}, {0x50, 0x48}, {0x60, 0x48}, {0x80, 0x68}
    };
    private static final int[] BREATH_SCRIPT = {
            0, 7,
            1, 7,
            2, 7,
            3, 7,
            4, 0x2F,
            5, 2,
            6, 2,
            7, 2,
            8, 2,
            9, 7,
            0x0A, 7,
            0x0B, 7,
            0x0C, 7,
            0x0D, 0x1F,
            0, 0x0F,
            0xF4
    };

    private enum State {
        IDLE,
        BREATHING,
        COOLDOWN
    }

    private State state = State.IDLE;
    private int timer;
    private int breathAnimationFrame;
    private int breathAnimationTimer;
    private boolean windActive;
    private int breathProjectileCount;
    private boolean waitingForOnscreen = true;
    private boolean initialized;

    public CluckoidBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Cluckoid", Sonic3kObjectArtKeys.CLUCKOID,
                COLLISION_SIZE_INDEX, PRIORITY_BUCKET, true);
        mappingFrame = 0;
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
            spawnChild(() -> new ArrowChild(this));
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        switch (state) {
            case IDLE -> updateIdle(playerEntity);
            case BREATHING -> updateBreathing(vIntRunCount, playerEntity);
            case COOLDOWN -> updateCooldown();
        }

        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public int getCollisionFlags() {
        return waitingForOnscreen || !initialized ? 0 : super.getCollisionFlags();
    }

    private void updateIdle(PlayableEntity playerEntity) {
        mappingFrame = 0;
        windActive = false;
        resetBreathAnimation();
        PlayableEntity target = closestNativePlayerByHorizontalDistance(playerEntity);
        if (target == null) {
            return;
        }

        int dx = findSonicTailsHorizontalDistance(target);
        int dy = Math.abs(currentY - target.getCentreY());
        if (dx >= ACTIVATION_X_RANGE || dy >= ACTIVATION_Y_RANGE) {
            return;
        }

        state = State.BREATHING;
    }

    private void updateBreathing(int vIntRunCount, PlayableEntity playerEntity) {
        if (mappingFrame >= 7) {
            if (!windActive) {
                services().playSfx(Sonic3kSfx.ENEMY_BREATH.id);
            }
            windActive = true;
            applyWindPressure(playerEntity);
            PlayableEntity sidekick = tryServices() != null ? tryServices().playerQuery().nativeP2OrNull() : null;
            applyWindPressure(sidekick);
            if (((vIntRunCount + 3) & 0x07) == 0) {
                spawnBreathDebris();
                breathProjectileCount++;
            }
        }

        animateBreathRawNoSstMultiDelay();
    }

    private void applyWindPressure(PlayableEntity playerEntity) {
        if (!(playerEntity instanceof AbstractPlayableSprite player)) {
            return;
        }
        if (player.getAnimationId() == DUCK_ANIMATION_ID || player.getSpindash() || player.isObjectControlled()) {
            return;
        }

        int dx = player.getCentreX() - currentX;
        int dy = Math.abs(player.getCentreY() - currentY);
        if (Math.abs(dx) >= WIND_X_RANGE || dy >= ACTIVATION_Y_RANGE) {
            return;
        }
        if (facingLeft ? dx > 0 : dx <= 0) {
            return;
        }

        int push = 0x10 - Math.min(0x0F, Math.abs(dx) >> 4);
        if (facingLeft) {
            push = -push;
        }
        NativePositionOps.addXPosPreserveSubpixel(player, push);
    }

    private void updateCooldown() {
        timer--;
        // ROM loc_8E21A exits only after subq.w #1,$2E makes the word negative.
        if (timer >= 0) {
            return;
        }

        timer = 0;
        mappingFrame = 0;
        windActive = false;
        resetBreathAnimation();
        state = State.IDLE;
    }

    private void animateBreathRawNoSstMultiDelay() {
        breathAnimationTimer--;
        if (breathAnimationTimer >= 0) {
            return;
        }

        breathAnimationFrame += 2;
        int scriptValue = BREATH_SCRIPT[breathAnimationFrame];
        if (scriptValue < 0x80) {
            mappingFrame = scriptValue;
            breathAnimationTimer = BREATH_SCRIPT[breathAnimationFrame + 1];
            return;
        }

        if (scriptValue == 0xF4) {
            breathAnimationTimer = 0;
            breathAnimationFrame = 0;
            state = State.COOLDOWN;
            timer = COOLDOWN_FRAMES;
            return;
        }

        throw new IllegalStateException("Unsupported Cluckoid Animate_RawNoSSTMultiDelay command: " + scriptValue);
    }

    private void resetBreathAnimation() {
        breathAnimationFrame = 0;
        breathAnimationTimer = 0;
    }

    private void spawnBreathDebris() {
        if (tryServices() == null || tryServices().rng() == null) {
            return;
        }
        int random = tryServices().rng().nextRaw();
        int tableBase = (spawn.subtype() & 0xFF) << 3;
        int tableIndex = tableBase + ((random & 0x1C) >> 2);
        if (tableIndex < 0 || tableIndex >= BREATH_CHILD_OFFSETS.length) {
            return;
        }

        int offsetX = BREATH_CHILD_OFFSETS[tableIndex][0];
        int offsetY = BREATH_CHILD_OFFSETS[tableIndex][1];
        if (offsetX == 0 && offsetY == 0) {
            return;
        }

        int xVelocity = BREATH_CHILD_X_SPEEDS[(random & 0x06) >> 1];
        int xAcceleration = -BREATH_CHILD_X_ACCEL_LEFT;
        boolean hFlip = false;
        if (facingLeft) {
            hFlip = true;
            xVelocity = -xVelocity;
            offsetX = -offsetX;
            xAcceleration = -xAcceleration;
        }

        int yVelocity = BREATH_CHILD_Y_SPEEDS[((random >>> 16) & 0x06) >> 1];
        ObjectSpawn ownerSpawn = getSpawn();
        int childX = currentX + offsetX;
        int childY = currentY + offsetY;
        int childXVelocity = xVelocity;
        int childYVelocity = yVelocity;
        int childXAcceleration = xAcceleration;
        boolean childHFlip = hFlip;
        boolean childBigLeaf = ((random >>> 16) & 0x8000) != 0;
        spawnChild(() -> new BreathDebrisChild(
                ownerSpawn,
                childX,
                childY,
                childXVelocity,
                childYVelocity,
                childXAcceleration,
                childHFlip,
                childBigLeaf));
    }

    static final class BreathDebrisChild extends AbstractObjectInstance
            implements SpawnTrailingZeroIntsRewindRecreatable {
        private static final int RENDER_HALF_WIDTH = 0x08;
        private static final int RENDER_HALF_HEIGHT = 0x08;

        private boolean bigLeaf;
        private boolean hFlip;
        private int x;
        private int y;
        private int xSubpixel;
        private int ySubpixel;
        private int xVelocity;
        private int yVelocity;
        private int xAcceleration;
        private boolean floating;
        private int angle;
        private int mappingFrame;
        private int animFrameTimer;
        private boolean releasedPollenCounter;

        BreathDebrisChild(ObjectSpawn ownerSpawn, int x, int y, int xVelocity, int yVelocity,
                int xAcceleration, boolean hFlip, boolean bigLeaf) {
            super(new ObjectSpawn(x, y, ownerSpawn.objectId(), ownerSpawn.subtype(),
                    (hFlip ? 1 : 0) | (bigLeaf ? 2 : 0), false, y), "CluckoidBreathDebris");
            this.x = x;
            this.y = y;
            this.xVelocity = xVelocity;
            this.yVelocity = yVelocity;
            this.xAcceleration = xAcceleration;
            this.hFlip = hFlip;
            this.bigLeaf = bigLeaf;
        }

        BreathDebrisChild(ObjectSpawn spawn, int ignored) {
            super(spawn, "CluckoidBreathDebris");
            this.x = spawn.x();
            this.y = spawn.y();
            this.hFlip = (spawn.renderFlags() & 0x01) != 0;
            this.bigLeaf = (spawn.renderFlags() & 0x02) != 0;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (floating) {
                xVelocity = TrigLookupTable.sinHex(angle);
                angle = (angle + 4) & 0xFF;
                move();
                yVelocity += 2;
                if (!isOnScreen(0x20)) {
                    x = 0x7F00;
                    releasePollenCounter();
                    setDestroyedByOffscreen();
                }
                animatePollenMappingFrame();
                return;
            }

            if (!isOnScreen(0x20)) {
                setDestroyedByOffscreen();
                return;
            }

            int nextXVelocity = xVelocity + xAcceleration;
            boolean xSettled = nextXVelocity >= -0x80 && nextXVelocity <= 0x80;
            if (!xSettled) {
                xVelocity = nextXVelocity;
            }

            int nextYVelocity = yVelocity + BREATH_CHILD_Y_ACCELERATION;
            boolean ySettled = nextYVelocity >= 0x80;
            if (!ySettled) {
                yVelocity = nextYVelocity;
            }

            if (xSettled && ySettled) {
                floating = true;
            }
            animatePollenMappingFrame();
            move();
            if (!isOnScreen(0x20)) {
                setDestroyedByOffscreen();
            }
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(bigLeaf
                    ? Sonic3kObjectArtKeys.MHZ_BIG_LEAVES
                    : Sonic3kObjectArtKeys.MHZ_POLLEN_SPRING);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndexForcedPriority(mappingFrame, x, y, hFlip, false, -1, true);
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
        public int getOnScreenHalfWidth() {
            return RENDER_HALF_WIDTH;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return RENDER_HALF_HEIGHT;
        }

        int getXVelocity() {
            return xVelocity;
        }

        int getYVelocity() {
            return yVelocity;
        }

        int getXAcceleration() {
            return xAcceleration;
        }

        boolean isFloatingRoutineForTest() {
            return floating;
        }

        private void move() {
            int xPos24 = (x << 8) | (xSubpixel & 0xFF);
            int yPos24 = (y << 8) | (ySubpixel & 0xFF);
            xPos24 += xVelocity;
            yPos24 += yVelocity;
            x = xPos24 >> 8;
            y = yPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
            ySubpixel = yPos24 & 0xFF;
        }

        private void animatePollenMappingFrame() {
            animFrameTimer--;
            if (animFrameTimer >= 0) {
                return;
            }
            animFrameTimer = 7;
            mappingFrame = (mappingFrame + 1) & 1;
            if (xVelocity < 0) {
                mappingFrame += 2;
            }
        }

        private void releasePollenCounter() {
            if (releasedPollenCounter) {
                return;
            }
            if (services().zoneRuntimeState() instanceof MhzZoneRuntimeState state) {
                state.releasePollenParticle();
            }
            releasedPollenCounter = true;
        }
    }

    private static CluckoidBadnikInstance findLiveCluckoidParentForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        CluckoidBadnikInstance nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(instance instanceof CluckoidBadnikInstance cluckoid) || cluckoid.isDestroyed()) {
                continue;
            }
            ObjectSpawn candidateSpawn = cluckoid.getSpawn();
            if (spawn.layoutIndex() >= 0 && candidateSpawn.layoutIndex() == spawn.layoutIndex()) {
                return cluckoid;
            }
            int distance = Math.abs(candidateSpawn.x() - spawn.x())
                    + Math.abs(candidateSpawn.y() - spawn.y());
            if (distance < nearestDistance) {
                nearest = cluckoid;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    static final class ArrowChild extends AbstractObjectInstance implements RewindRecreatable {
        private static final int RENDER_HALF_WIDTH = 0x10;
        private static final int RENDER_HALF_HEIGHT = 0x0C;

        @RewindTransient(reason = "Structural parent link; arrow position and lifetime derive from the Cluckoid parent.")
        private final CluckoidBadnikInstance parent;

        ArrowChild(CluckoidBadnikInstance parent) {
            super(parent.getSpawn(), "CluckoidArrow");
            this.parent = parent;
        }

        @Override
        public ArrowChild recreateForRewind(RewindRecreateContext ctx) {
            CluckoidBadnikInstance liveParent = findLiveCluckoidParentForRewind(ctx);
            return liveParent == null ? null : new ArrowChild(liveParent);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
            }
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
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.CLUCKOID_ARROW);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndexForcedPriority(0, getX(), getY(), !parent.facingLeft, false, -1, true);
        }

        @Override
        public int getX() {
            return parent.getBodyAnchorX();
        }

        @Override
        public int getY() {
            return parent.getBodyAnchorY() + ARROW_CHILD_Y_OFFSET;
        }

        @Override
        public int getOnScreenHalfWidth() {
            return RENDER_HALF_WIDTH;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return RENDER_HALF_HEIGHT;
        }
    }

    public String getStateName() {
        return state.name();
    }

    public int getTimer() {
        return timer;
    }

    public int getMappingFrame() {
        return mappingFrame;
    }

    public boolean isWindActive() {
        return windActive;
    }

    public int getBreathProjectileCount() {
        return breathProjectileCount;
    }
}
