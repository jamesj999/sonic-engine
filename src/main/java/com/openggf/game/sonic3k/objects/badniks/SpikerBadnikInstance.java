package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnAndCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseListener;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.NativePositionOps;

import java.util.List;

/**
 * S3K Obj $9C - Spiker (MGZ).
 *
 * <p>ROM reference: {@code Obj_Spiker} (sonic3k.asm:185372-185672). The main
 * body rises when Sonic/Tails are within $40 pixels, exposing two side launchers
 * and a spring-loaded top spike. Touching the top spike compresses the shell,
 * then launches the player upward at {@code -$600}. The side launchers animate
 * and fire one gravity-affected spike projectile from the player's side.
 */
public final class SpikerBadnikInstance extends AbstractS3kBadnikInstance
        implements SpawnRewindRecreatable {

    private static final int COLLISION_SIZE_INDEX = 0x0A;
    private static final int PRIORITY_BUCKET = 5;
    private static final int TOP_SPIKE_PRIORITY_BUCKET = 4;

    private static final int DETECT_RANGE = 0x40;
    private static final int RISE_DESCEND_FRAMES = 7;
    private static final int RISE_STEP = 1;
    private static final int SIDE_LAUNCHER_OFFSET_X = 0x10;
    private static final int SIDE_LAUNCHER_OFFSET_Y = 0x0C;
    private static final int TOP_SPIKE_OFFSET_Y = -0x0C;

    private static final int LAUNCH_Y_VELOCITY = -0x600;
    private static final int TOP_SPIKE_TOUCH_NUDGE_Y = 6;
    private static final int TOP_SPIKE_COOLDOWN = 0x10;

    // Obj_Spiker begins with Obj_WaitOffscreen before sub_88DCE/routine
    // dispatch (sonic3k.asm:185372-185381). The wrapper uses Map_Offscreen
    // width/height $20 until render_flags bit 7 is set (sonic3k.asm:180266-180298).
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;

    private static final int[] LAUNCH_ANIM_FRAMES = {1, 2, 1, 0};
    private static final int[] LAUNCH_ANIM_DELAYS = {0, 1, 0, 5};

    private enum State {
        DETECT,
        RISE,
        OPEN,
        DESCEND,
        LAUNCH_ANIM
    }

    private boolean initialized;
    private State state = State.DETECT;
    private State resumeState = State.DETECT;
    private int stateTimer;
    private int launchAnimIndex = -1;
    private int launchAnimTimer;
    private boolean launchOnNextAnimationTick;
    private boolean collisionEnabled = true;
    private boolean spikesExtended;
    private AbstractPlayableSprite pendingLaunchPlayer;
    private SpikerSideLauncherChild leftLauncher;
    private SpikerSideLauncherChild rightLauncher;
    private SpikerTopSpikeChild topSpike;
    private boolean waitingForOnscreen = true;

    public SpikerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Spiker",
                Sonic3kObjectArtKeys.MGZ_SPIKER, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (waitingForOnscreen) {
            if (!isOnScreen(WAIT_OFFSCREEN_MARGIN)) {
                return;
            }
            // loc_85B02 restores the saved Spiker operation and returns, so
            // SetUp_ObjAttributes/CreateChild1_Normal run on the next object pass.
            waitingForOnscreen = false;
            return;
        }

        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                ? sprite : null;
        if (!initialized) {
            initializeChildren();
            initialized = true;
            return;
        }
        pruneDestroyedChildReferences();

        switch (state) {
            case DETECT -> updateDetect(player);
            case RISE -> updateRise();
            case OPEN -> updateOpen(player);
            case DESCEND -> updateDescend();
            case LAUNCH_ANIM -> updateLaunchAnim();
        }
    }

    @Override
    public void onUnload() {
        spikesExtended = false;
        pendingLaunchPlayer = null;
        collisionEnabled = true;
        destroyChild(leftLauncher);
        destroyChild(rightLauncher);
        destroyChild(topSpike);
        leftLauncher = null;
        rightLauncher = null;
        topSpike = null;
    }

    @Override
    public int getCollisionFlags() {
        return collisionEnabled ? super.getCollisionFlags() : 0;
    }

    private void initializeChildren() {
        leftLauncher = spawnChild(() -> new SpikerSideLauncherChild(this, true));
        rightLauncher = spawnChild(() -> new SpikerSideLauncherChild(this, false));
        topSpike = spawnChild(() -> new SpikerTopSpikeChild(this));
        // FindNextFreeObj can fail when the SST is full. spawnChild returns
        // the rejected (destroyed) instance so callers can stay allocation-
        // neutral; do not retain an unmanaged reference in the parent's
        // rewind graph when that happens.
        if (leftLauncher.isDestroyed()) leftLauncher = null;
        if (rightLauncher.isDestroyed()) rightLauncher = null;
        if (topSpike.isDestroyed()) topSpike = null;
    }

    private void pruneDestroyedChildReferences() {
        if (leftLauncher != null && leftLauncher.isDestroyed()) leftLauncher = null;
        if (rightLauncher != null && rightLauncher.isDestroyed()) rightLauncher = null;
        if (topSpike != null && topSpike.isDestroyed()) topSpike = null;
    }

    @Override
    protected void afterRewindRestoreSettled() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }
        if (leftLauncher != null && !svc.objectManager().getActiveObjects().contains(leftLauncher)) {
            leftLauncher = null;
        }
        if (rightLauncher != null && !svc.objectManager().getActiveObjects().contains(rightLauncher)) {
            rightLauncher = null;
        }
        if (topSpike != null && !svc.objectManager().getActiveObjects().contains(topSpike)) {
            topSpike = null;
        }
    }

    private void detachDestroyedChild(AbstractObjectInstance child) {
        if (child == leftLauncher) leftLauncher = null;
        if (child == rightLauncher) rightLauncher = null;
        if (child == topSpike) topSpike = null;
    }

    private void updateDetect(AbstractPlayableSprite mainPlayer) {
        if (nearestPlayerDistance(mainPlayer) >= DETECT_RANGE) {
            return;
        }
        state = State.RISE;
        stateTimer = RISE_DESCEND_FRAMES;
    }

    private void updateRise() {
        currentY -= RISE_STEP;
        stateTimer--;
        if (stateTimer < 0) {
            state = State.OPEN;
            spikesExtended = true;
        }
    }

    private void updateOpen(AbstractPlayableSprite mainPlayer) {
        if (nearestPlayerDistance(mainPlayer) < DETECT_RANGE) {
            return;
        }
        state = State.DESCEND;
        stateTimer = RISE_DESCEND_FRAMES;
        spikesExtended = false;
    }

    private void updateDescend() {
        currentY += RISE_STEP;
        stateTimer--;
        if (stateTimer < 0) {
            state = State.DETECT;
        }
    }

    private void updateLaunchAnim() {
        if (launchOnNextAnimationTick && pendingLaunchPlayer != null) {
            launchOnNextAnimationTick = false;
            launchPlayer(pendingLaunchPlayer);
            // loc_88C38 observes anim_frame == 4 only after the raw animator
            // has returned. Launching the player does not consume the next
            // frame/delay pair; that happens on the following object pass.
            return;
        }
        launchAnimTimer = (launchAnimTimer - 1) & 0xFF;
        if (launchAnimTimer < 0x80) {
            return;
        }

        launchAnimIndex++;
        if (launchAnimIndex >= LAUNCH_ANIM_FRAMES.length) {
            finishLaunchAnim();
            return;
        }

        mappingFrame = LAUNCH_ANIM_FRAMES[launchAnimIndex];
        launchAnimTimer = LAUNCH_ANIM_DELAYS[launchAnimIndex];

        // ROM tests anim_frame == 4 after Animate_RawNoSSTMultiDelay. The
        // byte offset 4 is the third frame/delay pair, not the final pair.
        if (launchAnimIndex == 2 && pendingLaunchPlayer != null) {
            launchOnNextAnimationTick = true;
        }
    }

    private void beginTopSpikeCompression(AbstractPlayableSprite player) {
        if (state == State.LAUNCH_ANIM) {
            return;
        }
        pendingLaunchPlayer = player;
        resumeState = state;
        state = State.LAUNCH_ANIM;
        collisionEnabled = false;
        mappingFrame = 1;
        // Pair zero (frame 1) is already visible. The first animation tick
        // increments anim_frame by two and reads pair one.
        launchAnimIndex = 0;
        launchAnimTimer = 0;
        launchOnNextAnimationTick = false;
    }

    private void finishLaunchAnim() {
        state = resumeState;
        collisionEnabled = true;
        mappingFrame = 0;
        pendingLaunchPlayer = null;
        launchAnimIndex = -1;
        launchAnimTimer = 0;
        launchOnNextAnimationTick = false;
    }

    private void launchPlayer(AbstractPlayableSprite player) {
        player.setYSpeed((short) LAUNCH_Y_VELOCITY);
        player.setAir(true);
        player.setOnObject(false);
        player.setJumping(false);
    }

    private AbstractPlayableSprite findNearestTarget(AbstractPlayableSprite mainPlayer, int referenceX) {
        AbstractPlayableSprite nearest = null;
        int nearestDistance = Integer.MAX_VALUE;

        if (mainPlayer != null && !mainPlayer.getDead()) {
            nearest = mainPlayer;
            nearestDistance = Math.abs(referenceX - mainPlayer.getCentreX());
        }

        ObjectServices svc = tryServices();
        if (svc == null) {
            return nearest;
        }
        ObjectPlayerQuery query = svc.playerQuery();
        for (PlayableEntity candidate : query.playersFor(ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS)) {
            if (!(candidate instanceof AbstractPlayableSprite sprite) || sprite.getDead()) {
                continue;
            }
            int distance = Math.abs(referenceX - sprite.getCentreX());
            if (distance < nearestDistance) {
                nearest = sprite;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private int nearestPlayerDistance(AbstractPlayableSprite mainPlayer) {
        AbstractPlayableSprite target = findNearestTarget(mainPlayer, currentX);
        return target == null ? Integer.MAX_VALUE : Math.abs(currentX - target.getCentreX());
    }

    private static boolean playerIsOnLeft(AbstractPlayableSprite player, int objectX) {
        return player != null && !player.getDead() && player.getCentreX() <= objectX;
    }

    private static boolean playerIsOnRight(AbstractPlayableSprite player, int objectX) {
        return player != null && !player.getDead() && player.getCentreX() > objectX;
    }

    private static void destroyChild(AbstractObjectInstance child) {
        if (child != null) {
            child.setDestroyed(true);
        }
    }

    private static SpikerBadnikInstance findLiveSpikerParent(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (instance instanceof SpikerBadnikInstance spiker && !spiker.isDestroyed()) {
                return spiker;
            }
        }
        return null;
    }

    private static final class SpikerSideLauncherChild extends AbstractObjectInstance
            implements RewindRecreatable {

        private static final int IDLE_FRAME = 3;
        private static final int[] ATTACK_FRAMES = {3, 3, 4, 3};
        private static final int[] ATTACK_DELAYS = {1, 0x0F, 7, 0x3F};

        private enum State {
            WAIT_FOR_OPEN,
            ARMED,
            ATTACK
        }
        private SpikerBadnikInstance parent;
        private boolean leftSide;

        private State state = State.WAIT_FOR_OPEN;
        private int mappingFrame = IDLE_FRAME;
        private int attackIndex = -1;
        private int attackTimer;
        private boolean projectileFired;

        private SpikerSideLauncherChild(SpikerBadnikInstance parent, boolean leftSide) {
            super(parent.getSpawn(), leftSide ? "SpikerLeftLauncher" : "SpikerRightLauncher");
            this.parent = parent;
            this.leftSide = leftSide;
        }

        private SpikerSideLauncherChild(SpikerBadnikInstance parent) {
            this(parent, false);
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            SpikerBadnikInstance liveParent = findLiveSpikerParent(ctx);
            return liveParent != null ? new SpikerSideLauncherChild(liveParent) : null;
        }

        @Override
        protected void onDroppedAsUnmatchedRewindReconstructionChild() {
            parent.detachDestroyedChild(this);
        }

        @Override
        public void onUnload() {
            parent.detachDestroyedChild(this);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed()) {
                return;
            }
            if (parent.isDestroyed()) {
                parent.detachDestroyedChild(this);
                setDestroyed(true);
                return;
            }
            switch (state) {
                case WAIT_FOR_OPEN -> updateWaitForOpen();
                case ARMED -> updateArmed(playerEntity);
                case ATTACK -> updateAttack();
            }
        }

        @Override
        public int getX() {
            return parent.getX() + (leftSide ? -SIDE_LAUNCHER_OFFSET_X : SIDE_LAUNCHER_OFFSET_X);
        }

        @Override
        public int getY() {
            return parent.getY() + SIDE_LAUNCHER_OFFSET_Y;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(getX(), getY());
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
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_SPIKER);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, getX(), getY(), leftSide, false);
        }

        private void spawnProjectile() {
            int xVelocity = leftSide ? -0x200 : 0x200;
            int xOffset = leftSide ? -4 : 4;
            // CreateChild5_ComplexAdjusted allocates after the launcher, so the
            // projectile's higher SST slot executes loc_86D4A/loc_86D5E later
            // in this same object loop and owns the allocation-frame movement.
            spawnChild(() -> new SpikerSpikeProjectile(
                    parent,
                    getX() + xOffset,
                    getY(),
                    xVelocity,
                    -0x200,
                    leftSide));
        }

        private void playProjectileSfx() {
            try {
                services().playSfx(Sonic3kSfx.PROJECTILE.id);
            } catch (Exception ignored) {
                // Keep gameplay logic independent from audio state.
            }
        }

        private void updateWaitForOpen() {
            mappingFrame = IDLE_FRAME;
            if (parent.spikesExtended) {
                state = State.ARMED;
            }
        }

        private void updateArmed(PlayableEntity playerEntity) {
            mappingFrame = IDLE_FRAME;
            if (!parent.spikesExtended) {
                state = State.WAIT_FOR_OPEN;
                return;
            }

            AbstractPlayableSprite target = playerEntity instanceof AbstractPlayableSprite sprite
                    ? parent.findNearestTarget(sprite, getX()) : null;
            int launcherX = getX();
            if (target == null || Math.abs(launcherX - target.getCentreX()) >= DETECT_RANGE) {
                return;
            }
            boolean matchingSide = leftSide
                    ? playerIsOnLeft(target, launcherX)
                    : playerIsOnRight(target, launcherX);
            if (!matchingSide) {
                return;
            }
            // ROM parity: loc_88CC6 only switches the child to the attack routine.
            // The animation itself begins on the following frame in loc_88D02.
            state = State.ATTACK;
            // Animate_RawNoSSTMultiDelay adds two to anim_frame before reading
            // byte_88E53. The initial zero cursor therefore skips pair zero
            // (frame 3, delay 1) and starts at pair one (frame 3, delay $0F).
            attackIndex = 0;
            attackTimer = 0;
            projectileFired = false;
        }

        private void updateAttack() {
            attackTimer = (attackTimer - 1) & 0xFF;
            if (attackTimer < 0x80) {
                return;
            }

            attackIndex++;
            if (attackIndex >= ATTACK_FRAMES.length) {
                state = State.ARMED;
                attackIndex = -1;
                attackTimer = 0;
                mappingFrame = IDLE_FRAME;
                return;
            }

            mappingFrame = ATTACK_FRAMES[attackIndex];
            attackTimer = ATTACK_DELAYS[attackIndex];

            if (!projectileFired && mappingFrame == 4) {
                projectileFired = true;
                playProjectileSfx();
                spawnProjectile();
            }
        }
    }

    private static final class SpikerTopSpikeChild extends AbstractObjectInstance
            implements TouchResponseProvider, TouchResponseListener, RewindRecreatable {

        private static final int COLLISION_FLAGS = 0xC0 | COLLISION_SIZE_INDEX;
        private SpikerBadnikInstance parent;
        /** ROM $2E wait word; negative means loc_88D98 has restored collision. */
        private int cooldown = -1;
        /** Engine touch runs before this child slot; ROM installs Obj_Wait in that slot. */
        private boolean cooldownSetupPass;

        private SpikerTopSpikeChild(SpikerBadnikInstance parent) {
            super(parent.getSpawn(), "SpikerTopSpike");
            this.parent = parent;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            SpikerBadnikInstance liveParent = findLiveSpikerParent(ctx);
            return liveParent != null ? new SpikerTopSpikeChild(liveParent) : null;
        }

        @Override
        protected void onDroppedAsUnmatchedRewindReconstructionChild() {
            parent.detachDestroyedChild(this);
        }

        @Override
        public void onUnload() {
            parent.detachDestroyedChild(this);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed()) {
                return;
            }
            if (parent.isDestroyed()) {
                parent.detachDestroyedChild(this);
                setDestroyed(true);
                return;
            }
            if (cooldownSetupPass) {
                cooldownSetupPass = false;
            } else if (cooldown >= 0) {
                cooldown--;
            }
        }

        @Override
        public int getCollisionFlags() {
            return cooldown >= 0 || parent.isDestroyed() ? 0 : COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public boolean usesS3kTouchSpecialPropertyResponse() {
            // ObjDat child collision_flags is $CA. S3K Touch_Special turns
            // size index $0A into Check_PlayerCollision's player selector.
            return true;
        }

        @Override
        public boolean usesCurrentTouchResponseState() {
            // This child calls Refresh_ChildPosition followed immediately by
            // Check_PlayerCollision in its own object routine. It does not use
            // the frame-start Collision_response_list position.
            return true;
        }

        @Override
        public void onTouchResponse(PlayableEntity playerEntity, TouchResponseResult result, int frameCounter) {
            if (cooldown >= 0 || !(playerEntity instanceof AbstractPlayableSprite player) || parent.isDestroyed()) {
                return;
            }

            player.setYSpeed((short) 0);
            player.setAir(true);
            player.setOnObject(false);
            // ROM addq.w #6,y_pos changes the pixel word only and preserves
            // the 16-bit subpixel fraction.
            NativePositionOps.writeYPosPreserveSubpixel(
                    player, player.getCentreY() + TOP_SPIKE_TOUCH_NUDGE_Y);
            player.setJumping(false);
            player.setRollingJump(false);

            try {
                services().playSfx(Sonic3kSfx.SPRING.id);
            } catch (Exception ignored) {
                // Keep gameplay logic independent from audio state.
            }

            cooldown = TOP_SPIKE_COOLDOWN;
            cooldownSetupPass = true;
            parent.beginTopSpikeCompression(player);
        }

        @Override
        public int getX() {
            return parent.getX();
        }

        @Override
        public int getY() {
            return parent.getY() + TOP_SPIKE_OFFSET_Y;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(getX(), getY());
        }

        @Override
        public int getPriorityBucket() {
            return TOP_SPIKE_PRIORITY_BUCKET;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            // ROM parity: ObjDat child uses mapping frame 7, which is an empty frame.
            // This child only provides the spring/touch region above the body art.
        }
    }

    private static final class SpikerSpikeProjectile extends AbstractObjectInstance
            implements TouchResponseProvider, SpawnAndCoordinateZeroScalarArgsRewindRecreatable {

        private static final int COLLISION_FLAGS = 0x98;
        private static final int PRIORITY_BUCKET = 5;
        private static final int SHIELD_REACTION_BOUNCE = 1 << 3;
        private static final int DEFLECT_SPEED = 0x800;
        private static final int ANIM_DELAY = 1;
        private static final int[] ANIM_FRAMES = {5, 6};
        private static final int GRAVITY = 0x20;
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

        private int currentX;
        private int currentY;
        private int xVelocity;
        private int yVelocity;
        private int xSubpixel;
        private int ySubpixel;
        // Un-final so the generic field capturer reapplies it after a rewind
        // recreate (not spawn-derivable; generic recreate uses a placeholder).
        private boolean hFlip;
        private int animFrame;
        private int animTimer;
        private boolean collisionEnabled = true;
        private boolean deleteNextFrame;

        private SpikerSpikeProjectile() {
            this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), 0, 0, 0, 0, false);
        }

        private SpikerSpikeProjectile(SpikerBadnikInstance parent, int x, int y,
                int xVelocity, int yVelocity, boolean hFlip) {
            this(parent.getSpawn(), x, y, xVelocity, yVelocity, hFlip);
        }

        private SpikerSpikeProjectile(ObjectSpawn spawn, int x, int y,
                int xVelocity, int yVelocity, boolean hFlip) {
            super(spawn, "SpikerSpikeProjectile");
            this.currentX = x;
            this.currentY = y;
            this.xVelocity = xVelocity;
            this.yVelocity = yVelocity;
            this.hFlip = hFlip;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (deleteNextFrame) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            advanceMovementAndAnimation();

            if (!spriteCheckDeleteTouchXYKeepsAlive()) {
                // Go_Delete_Sprite installs Delete_Current_Sprite and removes
                // collision immediately, but the SST slot is freed only on its
                // next execution (sonic3k.asm:179032-179047,179131-179134).
                deleteNextFrame = true;
            }
        }

        private boolean spriteCheckDeleteTouchXYKeepsAlive() {
            ObjectServices svc = tryServices();
            int cameraX = svc != null && svc.camera() != null ? svc.camera().getX() : 0;
            int cameraY = svc != null && svc.camera() != null ? svc.camera().getY() : 0;

            int xAligned = currentX & 0xFF80;
            int coarseBack = (cameraX - 0x80) & 0xFF80;
            int xDistance = (xAligned - coarseBack) & 0xFFFF;
            if (xDistance > 0x280) {
                return false;
            }
            int yDistance = (currentY - cameraY + 0x80) & 0xFFFF;
            return yDistance <= 0x200;
        }

        private void advanceMovementAndAnimation() {
            int oldYVelocity = yVelocity;
            yVelocity += GRAVITY;

            int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
            int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
            xPos24 += xVelocity;
            yPos24 += oldYVelocity;
            currentX = xPos24 >> 8;
            currentY = yPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
            ySubpixel = yPos24 & 0xFF;

            animTimer--;
            if (animTimer < 0) {
                animTimer = ANIM_DELAY;
                animFrame = (animFrame + 1) & 1;
            }
        }

        @Override
        public int getCollisionFlags() {
            return collisionEnabled && !deleteNextFrame ? COLLISION_FLAGS : 0;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
        }

        @Override
        public boolean usesCurrentTouchResponseState() {
            // loc_86D5E runs MoveSlowFall_AnimateRaw before
            // Sprite_CheckDeleteTouchXY adds the projectile to the collision
            // response list, so players observe its post-movement position.
            return true;
        }

        @Override
        public boolean isPersistent() {
            // loc_86D5E owns this dynamic child's lifetime through
            // Sprite_CheckDeleteTouchXY; the generic object window must not
            // release it first.
            return true;
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
        public boolean onShieldDeflect(PlayableEntity playerEntity) {
            if (!(playerEntity instanceof AbstractPlayableSprite player)) {
                return false;
            }
            int dx = player.getCentreX() - currentX;
            int dy = player.getCentreY() - currentY;
            int angle = TrigLookupTable.calcAngle(saturateToShort(dx), saturateToShort(dy));
            xVelocity = -((TrigLookupTable.cosHex(angle) * DEFLECT_SPEED) >> 8);
            yVelocity = -((TrigLookupTable.sinHex(angle) * DEFLECT_SPEED) >> 8);
            collisionEnabled = false;
            return true;
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
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(currentX, currentY);
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
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.MGZ_SPIKER);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(ANIM_FRAMES[animFrame], currentX, currentY, hFlip, false);
        }

        private static short saturateToShort(int value) {
            if (value > Short.MAX_VALUE) {
                return Short.MAX_VALUE;
            }
            if (value < Short.MIN_VALUE) {
                return Short.MIN_VALUE;
            }
            return (short) value;
        }
    }
}
