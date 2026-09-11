package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj $96 - TurboSpiker (HCZ).
 *
 * <p>Implements the HCZ crab badnik's full ROM state flow:
 * patrol toward the player, periodically pause-turn at subtype-defined
 * intervals, back away and launch its shell when the player approaches from the
 * facing side, and optionally hide behind a waterfall overlay on Y-flipped
 * placements before emerging in a burst of splash particles.
 *
 * <p>ROM reference: {@code Obj_TurboSpiker} (sonic3k.asm:183861-184226).
 */
public final class TurboSpikerBadnikInstance extends AbstractS3kBadnikInstance
        implements SpawnRewindRecreatable {

    private static final int COLLISION_SIZE_INDEX = 0x1A;      // ObjDat_TurboSpiker flags $1A
    private static final int PRIORITY_BUCKET_NORMAL = 5;       // ObjDat_TurboSpiker priority $280
    private static final int PRIORITY_BUCKET_WATERFALL = 3;    // ObjDat3_87EDA priority $180

    private static final int DETECT_RANGE = 0x60;
    private static final int INITIAL_TRACK_SPEED = 0x80;
    private static final int RETREAT_SPEED = 0x200;

    private static final int FLOOR_MIN_DIST = -1;
    private static final int FLOOR_MAX_DIST = 0x0C;
    private static final int Y_RADIUS = 0x0F;

    private static final int TURN_DELAY = 0x0F;
    private static final int WATERFALL_EMERGE_DELAY = 3;
    private static final int WATERFALL_PRIORITY_DELAY = 0x0F;

    private static final int WALK_ANIM_DELAY = 5;
    private static final int SHELLLESS_ANIM_DELAY = 1;
    private static final int[] WALK_FRAMES = {0, 1, 2};

    private static final int SHELL_OFFSET_X = 4;
    private static final int SHELL_LAUNCH_SPEED_X = 0x100;
    private static final int SHELL_LAUNCH_SPEED_Y = -0x400;
    private static final int SHELL_COLLISION_FLAGS = 0x9E;
    private static final int SHELL_FRAME = 3;
    private static final int SHELL_TRAIL_FRAME = 4;
    private static final int SHELL_PRIORITY_BUCKET = 5;

    private static final int[] SHELL_DRIP_FRAMES = {5, 5, 5, 6, 7};
    private static final int[] WATER_SPLASH_FRAMES = {8, 9, 10, 11, 12, 13};
    private static final int PARTICLE_KIND_SHELL_DRIP = 0;
    private static final int PARTICLE_KIND_WATER_SPLASH = 1;
    private static final int[] WATER_SPLASH_OFFSETS_X = {4, -6, 6, -8, 8};
    private static final int[] WATER_SPLASH_OFFSETS_Y = {-8, 0, 0, 0, 0};

    private enum State {
        HIDDEN_WAIT,
        EMERGE_DELAY,
        EMERGE_WATERFALL,
        PATROL,
        TURN_PAUSE,
        LAUNCH_PREP,
        SHELLLESS_RUN
    }

    private static final ObjectPlayerParticipationPolicy TARGET_PARTICIPATION =
            ObjectPlayerParticipationPolicy.ALL_ENGINE_PLAYERS;

    private record TargetSelection(AbstractPlayableSprite player, int distance) {
    }

    private boolean hiddenVariant;
    private int turnResetTimer;

    private State state;
    private State resumeState;
    private int stateTimer;
    private int turnTimer;
    private int currentPriorityBucket = PRIORITY_BUCKET_NORMAL;

    private boolean initialized;
    private boolean waitingForOnscreen = true;
    private boolean waterfallOverlayVisible;
    private int animIndex;
    private int animTimer;
    private TurboSpikerShellChild shellChild;
    private TurboSpikerWaterfallOverlayChild waterfallOverlayChild;

    public TurboSpikerBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "TurboSpiker",
                Sonic3kObjectArtKeys.HCZ_TURBO_SPIKER, COLLISION_SIZE_INDEX, PRIORITY_BUCKET_NORMAL);
        this.hiddenVariant = (spawn.renderFlags() & 0x02) != 0;
        this.state = hiddenVariant ? State.HIDDEN_WAIT : State.PATROL;
        this.turnTimer = (spawn.subtype() & 0xFF) << 1;
        this.turnResetTimer = this.turnTimer << 1;
        this.mappingFrame = WALK_FRAMES[0];
        this.waterfallOverlayVisible = hiddenVariant;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // Obj_WaitOffscreen owns a $20-by-$20 placeholder and restores the
        // saved Obj_TurboSpiker operation only after that placeholder has been
        // rendered. The real initializer runs on the following dispatch.
        if (waitingForOnscreen) {
            if (!isOnScreen(0x20)) {
                return;
            }
            waitingForOnscreen = false;
            return;
        }

        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (!initialized) {
            initialize(player);
            return;
        }

        switch (state) {
            case HIDDEN_WAIT -> updateHiddenWait(player);
            case EMERGE_DELAY -> updateEmergeDelay();
            case EMERGE_WATERFALL -> updateEmergeWaterfall();
            case PATROL -> updatePatrol(player);
            case TURN_PAUSE -> updateTurnPause();
            case LAUNCH_PREP -> updateLaunchPrep();
            case SHELLLESS_RUN -> updateShelllessRun();
        }
    }

    @Override
    public int getPriorityBucket() {
        return currentPriorityBucket;
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
        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.HCZ_TURBO_SPIKER);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean hFlip = !facingLeft;
        if (shellChild != null && shellChild.isAttached()) {
            renderer.drawFrameIndex(SHELL_FRAME,
                    currentX + adjustedOffsetX(SHELL_OFFSET_X),
                    currentY,
                    hFlip, false);
        }
        renderer.drawFrameIndex(mappingFrame, getRenderAnchorX(), getRenderAnchorY(), hFlip, false);
    }

    private void initialize(AbstractPlayableSprite player) {
        trackInitialFacing(player);
        shellChild = spawnChild(() -> new TurboSpikerShellChild(this));
        if (shellChild.isDestroyed()) {
            shellChild = null;
        }
        if (hiddenVariant) {
            waterfallOverlayChild = spawnChild(() -> new TurboSpikerWaterfallOverlayChild(this));
            if (waterfallOverlayChild.isDestroyed()) {
                waterfallOverlayChild = null;
            }
        }
        initialized = true;
    }

    private void detachShell(TurboSpikerShellChild shell) {
        if (shellChild == shell) {
            shellChild = null;
        }
    }

    private void detachWaterfallOverlay(TurboSpikerWaterfallOverlayChild overlay) {
        if (waterfallOverlayChild == overlay) {
            waterfallOverlayChild = null;
        }
    }

    @Override
    public void onUnload() {
        if (shellChild != null) {
            shellChild.detachFromParentOnUnload(this);
            shellChild = null;
        }
        if (waterfallOverlayChild != null) {
            waterfallOverlayChild.detachFromParentOnUnload(this);
            waterfallOverlayChild = null;
        }
    }

    private void updateHiddenWait(AbstractPlayableSprite mainPlayer) {
        TargetSelection target = findNearestTarget(mainPlayer);
        if (target.player() == null || target.distance() >= DETECT_RANGE) {
            return;
        }

        waterfallOverlayVisible = false;
        state = State.EMERGE_DELAY;
        stateTimer = WATERFALL_EMERGE_DELAY;
        spawnWaterSplashBurst();
    }

    private void updateEmergeDelay() {
        stateTimer--;
        if (stateTimer >= 0) {
            return;
        }

        state = State.EMERGE_WATERFALL;
        stateTimer = WATERFALL_PRIORITY_DELAY;
        currentPriorityBucket = PRIORITY_BUCKET_WATERFALL;
    }

    private void updateEmergeWaterfall() {
        stateTimer--;
        if (stateTimer >= 0) {
            return;
        }

        state = State.PATROL;
        currentPriorityBucket = PRIORITY_BUCKET_NORMAL;
    }

    private void updatePatrol(AbstractPlayableSprite mainPlayer) {
        TargetSelection target = findNearestTarget(mainPlayer);
        if (shouldLaunchShell(target)) {
            enterLaunchPrep();
            return;
        }

        animateWalking(WALK_ANIM_DELAY);
        moveWithVelocity();
        if (!snapToFloorOrPause(State.PATROL)) {
            return;
        }

        turnTimer--;
        if (turnTimer < 0) {
            enterTurnPause(State.PATROL);
        }
    }

    private void updateTurnPause() {
        stateTimer--;
        if (stateTimer >= 0) {
            return;
        }

        state = resumeState;
        xVelocity = -xVelocity;
        facingLeft = !facingLeft;
        turnTimer = turnResetTimer;
        currentPriorityBucket = PRIORITY_BUCKET_NORMAL;
    }

    private void updateLaunchPrep() {
        stateTimer--;
        if (stateTimer >= 0) {
            return;
        }

        state = State.SHELLLESS_RUN;
        if (shellChild != null) {
            shellChild.launch();
        }
    }

    private void updateShelllessRun() {
        animateWalking(SHELLLESS_ANIM_DELAY);
        moveWithVelocity();
        snapToFloorOrPause(State.SHELLLESS_RUN);
    }

    private void enterLaunchPrep() {
        state = State.LAUNCH_PREP;
        stateTimer = TURN_DELAY;
        facingLeft = !facingLeft;
        xVelocity = facingLeft ? -RETREAT_SPEED : RETREAT_SPEED;
    }

    private void enterTurnPause(State previousState) {
        if (state == State.TURN_PAUSE) {
            return;
        }
        resumeState = previousState;
        state = State.TURN_PAUSE;
        stateTimer = TURN_DELAY;
    }

    private boolean snapToFloorOrPause(State previousState) {
        int probeX = currentX + (xVelocity >> 8);
        TerrainCheckResult floor;
        try {
            floor = ObjectTerrainUtils.checkFloorDist(probeX, currentY, Y_RADIUS);
        } catch (IllegalStateException e) {
            return true;
        }
        if (!floor.foundSurface() || floor.distance() < FLOOR_MIN_DIST || floor.distance() >= FLOOR_MAX_DIST) {
            enterTurnPause(previousState);
            return false;
        }
        currentY += floor.distance();
        return true;
    }

    private void animateWalking(int delay) {
        animTimer--;
        if (animTimer >= 0) {
            return;
        }
        animIndex = (animIndex + 1) % WALK_FRAMES.length;
        mappingFrame = WALK_FRAMES[animIndex];
        animTimer = delay;
    }

    private void trackInitialFacing(AbstractPlayableSprite mainPlayer) {
        AbstractPlayableSprite target = findNearestTarget(mainPlayer).player();
        if (target == null) {
            xVelocity = facingLeft ? -INITIAL_TRACK_SPEED : INITIAL_TRACK_SPEED;
            return;
        }

        if (romSignedXDelta(target) >= 0) {
            facingLeft = true;
            xVelocity = -INITIAL_TRACK_SPEED;
        } else {
            facingLeft = false;
            xVelocity = INITIAL_TRACK_SPEED;
        }
    }

    private boolean shouldLaunchShell(TargetSelection target) {
        AbstractPlayableSprite player = target.player();
        if (player == null) {
            return false;
        }
        if (target.distance() >= DETECT_RANGE) {
            return false;
        }

        int directionCode = romSignedXDelta(player) >= 0 ? 0 : 2;
        if (!facingLeft) {
            directionCode -= 2;
        }
        return directionCode == 0;
    }

    private TargetSelection findNearestTarget(AbstractPlayableSprite mainPlayer) {
        ObjectServices svc = tryServices();
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> mainPlayer,
                () -> svc != null ? svc.playerQuery().sidekicks() : List.of());
        ObjectPlayerQuery.NearestPlayerX nearest = query.nearestByRomX(
                TARGET_PARTICIPATION,
                currentX,
                TurboSpikerBadnikInstance::isLivePlayable);
        return new TargetSelection((AbstractPlayableSprite) nearest.player(), nearest.distance());
    }

    private static boolean isLivePlayable(PlayableEntity player) {
        return player instanceof AbstractPlayableSprite sprite && !sprite.getDead();
    }

    private int romSignedXDelta(AbstractPlayableSprite target) {
        return (short) ((currentX - target.getCentreX()) & 0xFFFF);
    }

    private boolean shouldShowWaterfallOverlay() {
        return waterfallOverlayVisible && !isDestroyed();
    }

    private int adjustedOffsetX(int baseOffset) {
        return adjustedOffsetX(baseOffset, facingLeft);
    }

    private static int adjustedOffsetX(int baseOffset, boolean facingLeft) {
        return facingLeft ? baseOffset : -baseOffset;
    }

    private void spawnWaterSplashBurst() {
        services().playSfx(Sonic3kSfx.SPLASH.id);
        for (int i = 0; i < WATER_SPLASH_OFFSETS_X.length; i++) {
            int index = i;
            spawnChild(() -> new TurboSpikerWaterSplashParticle(
                    this,
                    currentX + adjustedOffsetX(WATER_SPLASH_OFFSETS_X[index]),
                    currentY + WATER_SPLASH_OFFSETS_Y[index],
                    false));
        }
    }

    private static TurboSpikerBadnikInstance findLiveTurboSpikerParent(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (instance instanceof TurboSpikerBadnikInstance turboSpiker && !turboSpiker.isDestroyed()) {
                return turboSpiker;
            }
        }
        return null;
    }

    private static TurboSpikerShellChild findLiveTurboSpikerShell(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (instance instanceof TurboSpikerShellChild shell && !shell.isDestroyed()) {
                return shell;
            }
        }
        return null;
    }

    private static final class TurboSpikerShellChild extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable {
        private TurboSpikerBadnikInstance parent;

        private int currentX;
        private int currentY;
        private int xVelocity;
        private int yVelocity;
        private int xSubpixel;
        private int ySubpixel;
        private boolean attached = true;
        /**
         * False from detach until the launch dispatch has been consumed. ROM
         * {@code TurboSpiker_SpikeChild_Launch} (loc_87D72,
         * docs/skdisasm/sonic3k.asm:184042-184053) writes
         * {@code TurboSpiker_SpikeChild_Move} into the child's own {@code (a0)}
         * code pointer and then falls through to {@code Sprite_CheckDeleteTouchXY}
         * -- it never calls {@code MoveSprite2} itself. The dispatcher has already
         * jumped through the old pointer for this frame, so the installed
         * {@code TurboSpiker_SpikeChild_Move} (loc_87DA4, :184056-184058), whose
         * only work is {@code MoveSprite2}, first executes on the FOLLOWING frame.
         */
        private boolean moveRoutineInstalled;
        private boolean facingLeft;
        private boolean deleteNextFrame;
        private TurboSpikerTrailEmitter trailEmitter;

        TurboSpikerShellChild(TurboSpikerBadnikInstance parent) {
            super(parent.getSpawn(), "TurboSpikerShell");
            this.parent = parent;
            this.facingLeft = parent.badnikFacingLeft();
            this.currentX = parent.getX() + adjustedOffsetX(SHELL_OFFSET_X, facingLeft);
            this.currentY = parent.getY();
        }

        private TurboSpikerShellChild(ObjectSpawn spawn) {
            super(spawn, "TurboSpikerShell");
            this.currentX = spawn.x();
            this.currentY = spawn.y();
            this.attached = false;
            this.moveRoutineInstalled = true;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            // Generic rewind invokes this hook on a zero-spawn probe. The
            // captured spawn in the context is the only authoritative source
            // for immutable object metadata; attached/parent are restored in
            // the compact-state reference phase.
            return ctx != null && ctx.spawn() != null
                    ? new TurboSpikerShellChild(ctx.spawn())
                    : null;
        }

        void launch() {
            TurboSpikerBadnikInstance launchParent = parent;
            if (!attached || launchParent == null || launchParent.isDestroyed()) {
                return;
            }
            attached = false;
            facingLeft = launchParent.badnikFacingLeft();
            currentX = launchParent.getX() + adjustedOffsetX(SHELL_OFFSET_X, facingLeft);
            currentY = launchParent.getY();
            // Child loc_87D72 inherits the parent's post-retreat render bit.
            // The parent moves in that facing direction, while the detached
            // shell tests the same bit and launches in the opposite direction.
            xVelocity = facingLeft ? SHELL_LAUNCH_SPEED_X : -SHELL_LAUNCH_SPEED_X;
            yVelocity = SHELL_LAUNCH_SPEED_Y;
            // loc_87D72 replaces the child operation with loc_87DA4, whose only
            // work is MoveSprite2 + Sprite_CheckDeleteTouchXY. The launched
            // shell no longer reads parent3 and can outlive an unloaded badnik
            // (docs/skdisasm/sonic3k.asm:184042-184061).
            launchParent.detachShell(this);
            parent = null;
            trailEmitter = spawnChild(() -> new TurboSpikerTrailEmitter(this));
            services().playSfx(Sonic3kSfx.FLOOR_LAUNCHER.id);
        }

        boolean isAttached() {
            return attached;
        }

        private void detachFromParentOnUnload(TurboSpikerBadnikInstance owner) {
            if (attached && parent == owner) {
                parent = null;
                ObjectLifetimeOps.destroyLatched(this);
            }
        }

        @Override
        public void onUnload() {
            if (trailEmitter != null) {
                trailEmitter.detachFromShell(this);
                trailEmitter = null;
            }
            if (parent != null) {
                parent.detachShell(this);
                parent = null;
            }
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (deleteNextFrame) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            if (attached) {
                if (parent == null || parent.isDestroyed()) {
                    setDestroyed(true);
                    return;
                }
                facingLeft = parent.badnikFacingLeft();
                currentX = parent.getX() + adjustedOffsetX(SHELL_OFFSET_X, facingLeft);
                currentY = parent.getY();
                return;
            }

            if (!moveRoutineInstalled) {
                // Launch dispatch: velocities, the trail child and the SFX were
                // all done in launch(); loc_87D72's own frame ends at
                // Sprite_CheckDeleteTouchXY with the shell still at the parent's
                // position. Movement belongs to the just-installed loc_87DA4.
                moveRoutineInstalled = true;
                if (!spriteCheckDeleteTouchXYKeepsAlive()) {
                    deleteNextFrame = true;
                }
                return;
            }

            int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
            int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
            xPos24 += xVelocity;
            yPos24 += yVelocity;
            currentX = xPos24 >> 8;
            currentY = yPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
            ySubpixel = yPos24 & 0xFF;

            if (!spriteCheckDeleteTouchXYKeepsAlive()) {
                // Go_Delete_Sprite installs Delete_Current_Sprite and sets
                // status bit 7. The SST slot is cleared on its next execution,
                // and loc_87DC0 can observe the marker in this object pass.
                deleteNextFrame = true;
            }
        }

        private boolean spriteCheckDeleteTouchXYKeepsAlive() {
            ObjectServices svc = tryServices();
            int cameraX = svc != null && svc.camera() != null ? svc.camera().getX() : 0;
            int cameraY = svc != null && svc.camera() != null ? svc.camera().getY() : 0;

            // Sprite_CheckDeleteTouchXY uses Camera_X_pos_coarse_back and
            // unsigned comparisons, not a symmetric screen-space margin
            // (docs/skdisasm/sonic3k.asm:179032-179047).
            int xAligned = currentX & 0xFF80;
            int coarseBack = (cameraX - 0x80) & 0xFF80;
            int xDistance = (xAligned - coarseBack) & 0xFFFF;
            if (xDistance > 0x280) {
                return false;
            }
            int yDistance = (currentY - cameraY + 0x80) & 0xFFFF;
            return yDistance <= 0x200;
        }

        private boolean isDeletePending() {
            return deleteNextFrame;
        }

        @Override
        public int getCollisionFlags() {
            if (isDestroyed() || deleteNextFrame) {
                return 0;
            }
            return SHELL_COLLISION_FLAGS;
        }

        @Override
        public int getCollisionProperty() {
            return 0;
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
            return SHELL_PRIORITY_BUCKET;
        }

        @Override
        public boolean isPersistent() {
            // loc_87DA4 owns lifetime through Sprite_CheckDeleteTouchXY. Do not
            // let ObjectManager's generic dynamic-object window free the slot
            // before that routine installs the ROM delete marker. The attached
            // loc_87D5E shell remains owned by the placed parent/load window.
            return !attached;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (attached || deleteNextFrame || isDestroyed()) {
                return;
            }
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.HCZ_TURBO_SPIKER);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(SHELL_FRAME, currentX, currentY, !facingLeft, false);
        }
    }

    private static final class TurboSpikerTrailEmitter extends AbstractObjectInstance
            implements RewindRecreatable {

        private static final int SPAWN_INTERVAL_MASK = 0x03;
        private static final int OFFSET_X = -4;
        private static final int OFFSET_Y = 0x14;
        private TurboSpikerShellChild shell;
        private int mappingFrame = SHELL_TRAIL_FRAME;
        private boolean deleteNextFrame;

        TurboSpikerTrailEmitter(TurboSpikerShellChild shell) {
            super(shell.getSpawn(), "TurboSpikerTrailEmitter");
            this.shell = shell;
        }

        private void detachFromShell(TurboSpikerShellChild owner) {
            if (shell == owner) {
                shell = null;
                ObjectLifetimeOps.expireDynamic(this);
            }
        }

        @Override
        public TurboSpikerTrailEmitter recreateForRewind(RewindRecreateContext ctx) {
            TurboSpikerShellChild liveShell = findLiveTurboSpikerShell(ctx);
            return liveShell != null ? new TurboSpikerTrailEmitter(liveShell) : null;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (deleteNextFrame) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            if (shell == null || shell.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            if (shell.isDeletePending()) {
                // loc_87DC0 observes parent3 status bit 7 and installs its own
                // Go_Delete_Sprite operation for the following execution.
                deleteNextFrame = true;
                return;
            }

            if ((vIntRunCount & SPAWN_INTERVAL_MASK) == 0) {
                int xJitter = ((vIntRunCount >> 2) & 7) - 3;
                int yJitter = ((vIntRunCount >> 3) & 7) - 3;
                spawnChild(() -> new TurboSpikerShellDripParticle(
                        getX() + xJitter,
                        getY() + yJitter + 4,
                        shell.getSpawn()));
            }

            mappingFrame ^= 1;
        }

        @Override
        public ObjectSpawn getSpawn() {
            return buildSpawnAt(getX(), getY());
        }

        @Override
        public int getX() {
            return shell == null ? super.getSpawn().x()
                    : shell.getX() + adjustedOffsetX(OFFSET_X, shell.facingLeft);
        }

        @Override
        public int getY() {
            return shell == null ? super.getSpawn().y() : shell.getY() + OFFSET_Y;
        }

        @Override
        public int getPriorityBucket() {
            return 4;
        }

        @Override
        public boolean isPersistent() {
            // loc_87DC0 has no independent out-of-range tail. It follows the
            // launched shell until that shell sets status bit 7.
            return true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || deleteNextFrame || shell == null || (mappingFrame & 1) == 0) {
                return;
            }
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.HCZ_TURBO_SPIKER);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(SHELL_TRAIL_FRAME, getX(), getY(), !shell.facingLeft, false);
        }
    }

    private static final class TurboSpikerShellDripParticle extends TurboSpikerAnimatedParticle {
        TurboSpikerShellDripParticle(int x, int y, ObjectSpawn ownerSpawn) {
            this(TurboSpikerAnimatedParticle.particleSpawn(
                    ownerSpawn, x, y, PARTICLE_KIND_SHELL_DRIP, false));
        }

        private TurboSpikerShellDripParticle(ObjectSpawn spawn) {
            super(spawn, "TurboSpikerShellDrip", spawn.x(), spawn.y(), SHELL_DRIP_FRAMES, 1, 5, false);
        }
    }

    private static final class TurboSpikerWaterSplashParticle extends TurboSpikerAnimatedParticle {
        TurboSpikerWaterSplashParticle(TurboSpikerBadnikInstance parent, int x, int y, boolean playSound) {
            this(TurboSpikerAnimatedParticle.particleSpawn(
                    parent.getSpawn(), x, y, PARTICLE_KIND_WATER_SPLASH, playSound));
        }

        private TurboSpikerWaterSplashParticle(ObjectSpawn spawn) {
            super(spawn, "TurboSpikerWaterSplash", spawn.x(), spawn.y(), WATER_SPLASH_FRAMES, 1, 4,
                    (spawn.renderFlags() & 1) != 0);
        }
    }

    private static class TurboSpikerAnimatedParticle extends AbstractObjectInstance implements SpawnRewindRecreatable {

        private int currentX;
        private int currentY;
        private final int[] frames;
        private int frameDelay;
        private int priorityBucket;
        private boolean playSound;

        private int frameIndex;
        private int frameTimer;
        private int mappingFrame;
        private boolean soundPlayed;

        private TurboSpikerAnimatedParticle(ObjectSpawn spawn) {
            this(spawn,
                    particleName(spawn),
                    spawn.x(),
                    spawn.y(),
                    particleFrames(spawn),
                    1,
                    particlePriority(spawn),
                    (spawn.renderFlags() & 1) != 0);
        }

        TurboSpikerAnimatedParticle(ObjectSpawn ownerSpawn, String name, int x, int y,
                int[] frames, int frameDelay, int priorityBucket, boolean playSound) {
            super(ownerSpawn, name);
            this.currentX = x;
            this.currentY = y;
            this.frames = frames;
            this.frameDelay = frameDelay;
            this.priorityBucket = priorityBucket;
            this.mappingFrame = frames[0];
            this.playSound = playSound;
        }

        private static ObjectSpawn particleSpawn(ObjectSpawn ownerSpawn, int x, int y, int particleKind,
                boolean playSound) {
            int objectId = ownerSpawn == null ? 0 : ownerSpawn.objectId();
            return new ObjectSpawn(x, y, objectId, particleKind, playSound ? 1 : 0, false, y);
        }

        private static String particleName(ObjectSpawn spawn) {
            return (spawn.subtype() & 0xFF) == PARTICLE_KIND_WATER_SPLASH
                    ? "TurboSpikerWaterSplash"
                    : "TurboSpikerShellDrip";
        }

        private static int[] particleFrames(ObjectSpawn spawn) {
            return (spawn.subtype() & 0xFF) == PARTICLE_KIND_WATER_SPLASH
                    ? WATER_SPLASH_FRAMES
                    : SHELL_DRIP_FRAMES;
        }

        private static int particlePriority(ObjectSpawn spawn) {
            return (spawn.subtype() & 0xFF) == PARTICLE_KIND_WATER_SPLASH ? 4 : 5;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (playSound && !soundPlayed) {
                services().playSfx(Sonic3kSfx.SPLASH.id);
                soundPlayed = true;
            }
            frameTimer--;
            if (frameTimer >= 0) {
                return;
            }
            frameTimer = frameDelay;
            frameIndex++;
            if (frameIndex >= frames.length) {
                setDestroyed(true);
                return;
            }
            mappingFrame = frames[frameIndex];
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
            return priorityBucket;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.HCZ_TURBO_SPIKER);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
        }
    }

    private static final class TurboSpikerWaterfallOverlayChild extends AbstractObjectInstance
            implements RewindRecreatable {
        private TurboSpikerBadnikInstance parent;
        private int currentX;
        private int currentY;

        TurboSpikerWaterfallOverlayChild(TurboSpikerBadnikInstance parent) {
            super(parent.getSpawn(), "TurboSpikerWaterfallOverlay");
            this.parent = parent;
            this.currentX = parent.getX();
            this.currentY = parent.getY();
        }

        @Override
        public TurboSpikerWaterfallOverlayChild recreateForRewind(RewindRecreateContext ctx) {
            TurboSpikerBadnikInstance liveParent = findLiveTurboSpikerParent(ctx);
            return liveParent != null ? new TurboSpikerWaterfallOverlayChild(liveParent) : null;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent == null || parent.isDestroyed()) {
                ObjectLifetimeOps.destroyLatched(this);
                return;
            }
            currentX = parent.getX();
            currentY = parent.getY();
            if (!parent.shouldShowWaterfallOverlay()) {
                setDestroyed(true);
            }
        }

        private void detachFromParentOnUnload(TurboSpikerBadnikInstance owner) {
            if (parent == owner) {
                currentX = owner.getX();
                currentY = owner.getY();
                parent = null;
                ObjectLifetimeOps.destroyLatched(this);
            }
        }

        @Override
        public void onUnload() {
            if (parent != null) {
                parent.detachWaterfallOverlay(this);
                parent = null;
            }
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
            return PRIORITY_BUCKET_WATERFALL;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.HCZ_TURBO_SPIKER_HIDDEN);
            if (renderer == null || !renderer.isReady()) {
                return;
            }
            renderer.drawFrameIndex(0, currentX, currentY, false, false);
        }
    }
}
