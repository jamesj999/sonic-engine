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
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * S3K Obj $A4 - Sparkle.
 *
 * <p>ROM reference: {@code Obj_Sparkle} (sonic3k.asm:186053-186253).
 * The parent is an attackable CNZ badnik that charges when a player is within
 * $80 pixels, then alternates firing lightning vertically up/down while
 * releasing two diagonal spark children.
 */
public final class SparkleBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x0B;
    private static final int PRIORITY_BUCKET = 5;
    private static final int ACTIVATION_RANGE = 0x80;
    private static final int WARNING_WAIT = 4;
    private static final int FIRE_WAIT = 0x20;
    private static final int FIRE_Y_OFFSET = 0x68;
    private static final int CHARGE_INITIAL_DELAY = 9;
    private static final int CHARGE_TERMINAL_LOOPS = 0x10;
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;
    private static final int[] CHARGE_FRAMES = {0, 1};

    private enum State { WAIT, CHARGE, WARNING_WAIT, FIRE_WAIT }

    private State state = State.WAIT;
    private int timer;
    private int chargeDelay = CHARGE_INITIAL_DELAY;
    private int chargeTimer = CHARGE_INITIAL_DELAY;
    private int chargeFrameIndex;
    private int chargeCycles;
    private boolean chargeRawActive;
    private boolean verticalPhaseDown;
    private boolean waitingForOnscreen = true;
    /**
     * routine 0. Obj_WaitOffscreen's release pass (loc_85B02) returns without
     * dispatching and leaves routine at 0, so the first dispatch after the gate
     * releases is loc_891BA (Init), not the wait state.
     */
    private boolean initPending = true;

    public SparkleBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Sparkle", Sonic3kObjectArtKeys.CNZ_SPARKLE,
                COLLISION_SIZE_INDEX, PRIORITY_BUCKET, true);
        this.mappingFrame = 0;
        this.verticalPhaseDown = (spawn.renderFlags() & 0x02) != 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (waitingForOnscreen) {
            // Obj_WaitOffscreen owns a $20-by-$20 placeholder until Render_Sprites
            // has brought it onscreen. Once the saved Obj_Sparkle operation is
            // restored, later offscreen frames must not freeze an in-progress
            // charge (sonic3k.asm:180266-180297,186058-186066).
            if (hasCameraContext()) {
                if (!isOnScreen(WAIT_OFFSCREEN_MARGIN)) {
                    return;
                }
                waitingForOnscreen = false;
                return;
            }
            waitingForOnscreen = false;
        }

        if (initPending) {
            // loc_891BA (sonic3k.asm:186074-186076), Sparkle's routine-0 entry, is
            // `lea ObjDat_Sparkle,a1 / jmp SetUp_ObjAttributes`; that subroutine
            // ends `addq.b #2,routine(a0)` then `rts`
            // (sonic3k.asm:176901-176919). Init RETURNS instead of falling through
            // to loc_891C2, so this dispatch runs no Find_SonicTails proximity
            // test and cannot start the charge; routine 2 runs next dispatch.
            initPending = false;
            return;
        }

        switch (state) {
            case WAIT -> updateWait(playerEntity);
            case CHARGE -> updateCharge();
            case WARNING_WAIT -> updateWarningWait();
            case FIRE_WAIT -> updateFireWait();
        }
    }

    private void updateWait(PlayableEntity playerEntity) {
        // Find_SonicTails leaves the nearest native player's horizontal
        // distance in d2. A CPU Tails can therefore start the charge while
        // Player 1 is still outside the $80-pixel window.
        PlayableEntity activationTarget = closestNativePlayerByHorizontalDistance(playerEntity);
        if (activationTarget == null || activationTarget.getDead()) {
            return;
        }
        if (findSonicTailsHorizontalDistance(activationTarget) >= ACTIVATION_RANGE) {
            return;
        }

        state = State.CHARGE;
        mappingFrame = CHARGE_FRAMES[0];
        chargeFrameIndex = 0;
        chargeDelay = CHARGE_INITIAL_DELAY;
        chargeTimer = 0;
        chargeCycles = 0;
        chargeRawActive = false;
    }

    private void updateCharge() {
        // Animate_RawGetFaster: the first call primes $2E/$2F, then every
        // terminator pass reduces the delay until it reaches zero. Only the
        // zero-delay passes count toward the script's byte-1 terminal count.
        if (!chargeRawActive) {
            chargeRawActive = true;
            chargeDelay = CHARGE_INITIAL_DELAY;
            chargeCycles = 0;
        }

        if (--chargeTimer >= 0) {
            return;
        }

        chargeFrameIndex++;
        if (chargeFrameIndex >= CHARGE_FRAMES.length) {
            chargeFrameIndex = 0;
            if (chargeDelay == 0) {
                chargeCycles++;
                mappingFrame = CHARGE_FRAMES[chargeFrameIndex];
                chargeTimer = chargeDelay;
                if (chargeCycles >= CHARGE_TERMINAL_LOOPS) {
                    chargeRawActive = false;
                    chargeCycles = 0;
                    state = State.WARNING_WAIT;
                    timer = WARNING_WAIT;
                    spawnChild(() -> new SparkleLightningWarningChild(spawn, this));
                }
                return;
            }
            chargeDelay--;
        }

        mappingFrame = CHARGE_FRAMES[chargeFrameIndex];
        chargeTimer = chargeDelay;
    }

    private void updateWarningWait() {
        if (timer-- > 0) {
            return;
        }

        boolean previousPhaseDown = verticalPhaseDown;
        verticalPhaseDown = !verticalPhaseDown;
        currentY += previousPhaseDown ? FIRE_Y_OFFSET : -FIRE_Y_OFFSET;
        state = State.FIRE_WAIT;
        timer = FIRE_WAIT;
        services().playSfx(Sonic3kSfx.LIGHTNING.id);
        spawnChild(() -> new SparkleProjectileChild(spawn, this, false));
        spawnChild(() -> new SparkleProjectileChild(spawn, this, true));
    }

    private void updateFireWait() {
        if (timer-- > 0) {
            return;
        }
        state = State.WAIT;
    }

    /**
     * Test setup only: put the badnik in its running state by consuming the
     * Obj_WaitOffscreen release dispatch and the routine-0 Init dispatch, the two
     * frames the ROM burns before loc_891C2 first runs.
     */
    void testReleaseOffscreenWait() {
        waitingForOnscreen = false;
        initPending = false;
    }

    @Override
    public int getCollisionFlags() {
        // collision_flags is written by SetUp_ObjAttributes inside loc_891BA
        // (sonic3k.asm:176910), so the freshly allocated SST slot still reads
        // zero for the whole Init dispatch: the frame's touch scan runs at the
        // player slot before this object's routine.
        return initPending ? 0 : super.getCollisionFlags();
    }

    boolean isFiringDown() {
        return verticalPhaseDown;
    }

    private boolean warningBelowParent() {
        return verticalPhaseDown;
    }

    private boolean hasCameraContext() {
        ObjectServices ctx = tryServices();
        return ctx == null || ctx.camera() != null;
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
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_SPARKLE);
        if (renderer == null || !renderer.isReady()) {
            return;
        }
        // ROM render_flags bit 1 is both Sparkle's floor/ceiling phase and the sprite v-flip bit.
        renderer.drawFrameIndex(mappingFrame, getRenderAnchorX(), getRenderAnchorY(),
                !badnikFacingLeft(), verticalPhaseDown);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s timer=%d charge=%d/%d verticalPhaseDown=%s waiting=%s",
                state, timer, chargeCycles, chargeDelay, verticalPhaseDown, waitingForOnscreen);
    }

    private abstract static class SparkleHazardChild extends AbstractObjectInstance
            implements TouchResponseProvider {
        private static final int SHIELD_REACTION_LIGHTNING = 1 << 5;
        private static final TouchResponseProfile TOUCH_RESPONSE_PROFILE = new TouchResponseProfile(
                TouchCategoryDecodeMode.NORMAL,
                false,
                true,
                false,
                TouchShieldDeflectCapability.SHIELD_DEFLECT,
                SHIELD_REACTION_LIGHTNING,
                TouchAttackBouncePolicy.STANDARD_ENEMY_KILL,
                TouchActorContextPolicy.MAIN_FULL_SIDEKICK_HURT_ONLY,
                TouchOverlapStopPolicy.STOP_AFTER_FIRST_OVERLAP_FOR_ALL_ACTORS);

        SparkleHazardChild(ObjectSpawn spawn, String name) {
            super(spawn, name);
        }

        @Override
        public boolean isHighPriority() {
            // Child setup retains Obj_Sparkle's high-priority art tile.
            return true;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public int getShieldReactionFlags() {
            return SHIELD_REACTION_LIGHTNING;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile() {
            return TOUCH_RESPONSE_PROFILE;
        }

        @Override
        public TouchResponseProfile getTouchResponseProfile(boolean multiRegionSource) {
            return TOUCH_RESPONSE_PROFILE;
        }
    }

    private static final class SparkleLightningWarningChild extends SparkleHazardChild
            implements SpawnRewindRecreatable {
        private static final int COLLISION_FLAGS = 0xAB;
        private static final int PRIORITY_BUCKET = 4;
        private static final int Y_OFFSET = 0x34;
        private static final int[] FRAMES = {2, 8, 3, 8, 4, 8, 5, 8, 2};
        private static final int FRAME_DELAY = 0;

        private int currentX;
        private int currentY;
        private int frameIndex;
        private int frameTimer;
        private int mappingFrame = FRAMES[0];

        SparkleLightningWarningChild(ObjectSpawn spawn, SparkleBadnikInstance parent) {
            super(spawn, "SparkleLightningWarning");
            currentX = parent.getX();
            currentY = parent.getY() + (parent.warningBelowParent() ? Y_OFFSET : -Y_OFFSET);
        }

        private SparkleLightningWarningChild(ObjectSpawn spawn) {
            super(spawn, "SparkleLightningWarning");
            this.currentX = spawn.x();
            this.currentY = spawn.y();
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (frameTimer-- > 0) {
                return;
            }
            frameIndex++;
            if (frameIndex >= FRAMES.length) {
                setDestroyed(true);
                return;
            }
            mappingFrame = FRAMES[frameIndex];
            frameTimer = FRAME_DELAY;
        }

        @Override
        public int getCollisionFlags() {
            return isDestroyed() ? 0 : COLLISION_FLAGS;
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
            PatternSpriteRenderer renderer = renderManager != null
                    ? renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_SPARKLE)
                    : null;
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
            }
        }
    }

    private static final class SparkleProjectileChild extends SparkleHazardChild
            implements SpawnRewindRecreatable {
        private static final int COLLISION_FLAGS = 0x98;
        private static final int PRIORITY_BUCKET = 5;
        private static final int INITIAL_SPEED = 0x600;
        private static final int DECELERATION = 0x40;
        private static final int MIN_SPEED = 0x100;
        private static final int[] FRAMES = {6, 7};
        private static final int FRAME_DELAY = 3;

        private int currentX;
        private int currentY;
        private int xSubpixel;
        private int ySubpixel;
        private int xVelocity;
        private int yVelocity;
        private int mappingFrame = FRAMES[0];
        private int frameIndex;
        private int frameTimer = FRAME_DELAY;
        private boolean drifting;

        SparkleProjectileChild(ObjectSpawn spawn, SparkleBadnikInstance parent, boolean right) {
            super(spawn, "SparkleProjectile");
            this.currentX = parent.getX();
            this.currentY = parent.getY();
            this.yVelocity = parent.isFiringDown() ? INITIAL_SPEED : -INITIAL_SPEED;
            this.xVelocity = right ? INITIAL_SPEED : -INITIAL_SPEED;
        }

        private SparkleProjectileChild(ObjectSpawn spawn) {
            super(spawn, "SparkleProjectile");
            this.currentX = spawn.x();
            this.currentY = spawn.y();
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            animate();
            if (!drifting) {
                decelerate();
            }
            move();
            if (!isOnScreen(64)) {
                setDestroyed(true);
            }
        }

        private void animate() {
            if (frameTimer-- > 0) {
                return;
            }
            frameIndex = (frameIndex + 1) % FRAMES.length;
            mappingFrame = FRAMES[frameIndex];
            frameTimer = FRAME_DELAY;
        }

        private void decelerate() {
            xVelocity = decelerateTowardZero(xVelocity);
            yVelocity = decelerateTowardZero(yVelocity);
            if (Math.abs(xVelocity) <= MIN_SPEED) {
                drifting = true;
            }
        }

        private int decelerateTowardZero(int velocity) {
            if (velocity < 0) {
                return velocity + DECELERATION;
            }
            if (velocity > 0) {
                return velocity - DECELERATION;
            }
            return 0;
        }

        private void move() {
            int xFixed = (currentX << 8) | (xSubpixel & 0xFF);
            int yFixed = (currentY << 8) | (ySubpixel & 0xFF);
            xFixed += xVelocity;
            yFixed += yVelocity;
            currentX = xFixed >> 8;
            currentY = yFixed >> 8;
            xSubpixel = xFixed & 0xFF;
            ySubpixel = yFixed & 0xFF;
        }

        @Override
        public int getCollisionFlags() {
            return isDestroyed() ? 0 : COLLISION_FLAGS;
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
            PatternSpriteRenderer renderer = renderManager != null
                    ? renderManager.getRenderer(Sonic3kObjectArtKeys.CNZ_SPARKLE)
                    : null;
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
            }
        }
    }
}
