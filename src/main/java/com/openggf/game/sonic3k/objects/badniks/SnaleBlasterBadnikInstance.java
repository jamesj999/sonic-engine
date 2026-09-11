package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.game.sonic3k.audio.Sonic3kSfx;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectLifetimeOps;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnAndCoordinateZeroScalarArgsRewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchActorContextPolicy;
import com.openggf.level.objects.TouchAttackBouncePolicy;
import com.openggf.level.objects.TouchCategoryDecodeMode;
import com.openggf.level.objects.TouchOverlapStopPolicy;
import com.openggf.level.objects.TouchResponseProfile;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.objects.TouchResponseResult;
import com.openggf.level.objects.TouchShieldDeflectCapability;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.List;

/**
 * S3K S3KL Obj $BE - SnaleBlaster (LBZ).
 *
 * <p>ROM reference: {@code Obj_SnaleBlaster} at {@code sonic3k.asm:190910}.
 * The parent badnik owns the shell collision/open-close timing and three child
 * pieces: two shooters plus the sliding cover. The children start their raw
 * firing animations from parent status bit 1, and the cover clears that bit
 * when its animation completes.
 */
public final class SnaleBlasterBadnikInstance extends AbstractS3kBadnikInstance
        implements SpawnRewindRecreatable {
    private static final int COLLISION_SIZE_INDEX = 0x1A; // loc_8BFF2 / loc_8C052.
    private static final int PRIORITY_BUCKET = 4;         // ObjDat_SnaleBlaster priority $200.
    private static final int CLOSED_WAIT_FRAMES = 0x20;   // loc_8BFD4: move.w #$20,$2E.
    private static final int OPEN_WAIT_FRAMES = 0x90;     // loc_8C08A: move.w #$90,$2E.
    private static final int VERTICAL_STEP_OPEN = -2;     // loc_8C030: move.w #-2,$40.
    private static final int VERTICAL_TOGGLE_DELAY = 0x0F; // byte_8C2C0 frame delays.
    private static final int VERTICAL_CYCLE_COUNT = 2;     // loc_8C03E: move.b #2,$39.
    private static final int PLAYER_CLOSE_RANGE = 0x30;    // sub_8C23C x/y distance.
    private static final int PLAYER_ROLL_ANIM = 2;         // sub_8C23C: cmpi.b #2,anim(a1).
    private static final int EARLY_REOPEN_WAIT = 60 - 1;   // loc_8C0B8.
    private static final int EARLY_REOPEN_STEP_WAIT = 0x0F; // loc_8C0DE.
    private static final int OPENING_PREP_DELAY = 5;       // byte_8C2B6.
    private static final int[] OPENING_PREP_FRAMES = {0, 1, 2, 3, 4, 4, 4, 4};
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20; // Map_Offscreen width/height.

    enum State {
        INIT,
        CLOSED_WAIT,
        OPENING_PREP,
        OPENING,
        OPEN_WAIT,
        CLOSING,
        CLOSING_FROM_PLAYER,
        EARLY_REOPEN_WAIT,
        EARLY_REOPENING
    }

    private State state = State.INIT;
    private int waitTimer;
    private int verticalStep;
    private int openCyclesRemaining;
    private int verticalAnimTimer;
    private int openingPrepIndex;
    private int openingPrepTimer;
    private boolean collisionEnabled;
    private int collisionProperty;
    private boolean firingWindow;
    private boolean waitingForOnscreen = true;
    private boolean placeholderRenderedOnscreen;
    private transient SnaleBlasterCoverChild cover;
    private final transient List<SnaleBlasterShooterChild> shooters = new ArrayList<>(2);

    public SnaleBlasterBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "SnaleBlaster",
                Sonic3kObjectArtKeys.SNALE_BLASTER, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = 0;
        this.collisionEnabled = true;
        this.collisionProperty = 0;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // Obj_WaitOffscreen installs a $20-by-$20 placeholder. Render_Sprites
        // publishes its on-screen bit after the object pass; the helper sees
        // that bit on the next dispatch, restores Obj_SnaleBlaster, and returns.
        if (waitingForOnscreen) {
            if (!placeholderRenderedOnscreen) {
                return;
            }
            waitingForOnscreen = false;
            placeholderRenderedOnscreen = false;
            return;
        }
        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                ? sprite : null;

        switch (state) {
            case INIT -> initialize();
            case CLOSED_WAIT -> updateClosedWait();
            case OPENING_PREP -> updateOpeningPrep();
            case OPENING -> updateVerticalMotion(player, State.OPEN_WAIT, OPEN_WAIT_FRAMES, true);
            case OPEN_WAIT -> updateOpenWait();
            case CLOSING -> updateVerticalMotion(player, State.OPEN_WAIT, OPEN_WAIT_FRAMES, true);
            case CLOSING_FROM_PLAYER -> updateEarlyClose();
            case EARLY_REOPEN_WAIT -> updateEarlyReopenWait();
            case EARLY_REOPENING -> updateEarlyReopen();
        }
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (waitingForOnscreen) {
            placeholderRenderedOnscreen = isWithinRenderSpriteBounds(
                    WAIT_OFFSCREEN_MARGIN, WAIT_OFFSCREEN_MARGIN);
        }
    }

    @Override
    public void onUnload() {
        firingWindow = false;
        if (cover != null) {
            ObjectLifetimeOps.expireDynamic(cover);
        }
        cover = null;
        for (SnaleBlasterShooterChild shooter : shooters) {
            ObjectLifetimeOps.expireDynamic(shooter);
        }
        shooters.clear();
    }

    @Override
    public int getCollisionFlags() {
        return collisionEnabled ? COLLISION_SIZE_INDEX : 0;
    }

    @Override
    public int getCollisionProperty() {
        return collisionProperty;
    }

    @Override
    public String traceDebugDetails() {
        return super.traceDebugDetails()
                + " state=" + state
                + " wait=" + waitTimer
                + " step=" + verticalStep
                + " cycles=" + openCyclesRemaining
                + " animTimer=" + verticalAnimTimer
                + " prep=" + openingPrepIndex + "/" + openingPrepTimer
                + " fire=" + firingWindow
                + " colProp=" + String.format("%02X", collisionProperty & 0xFF);
    }

    @Override
    public void onPlayerAttack(PlayableEntity playerEntity, TouchResponseResult result) {
        // ROM Touch_Enemy treats nonzero collision_property as a special enemy hit:
        // SnaleBlaster writes $7F outside the brief open window, so the shared
        // touch controller reflects the player and this object must stay alive.
        if ((collisionProperty & 0xFF) != 0) {
            // Touch_Enemy's boss/special-enemy branch saves then clears
            // collision_flags before decrementing boss_hitcount2. Routines
            // 8/A do not restore the flag, preventing repeated overlap hits.
            collisionEnabled = false;
            collisionProperty = (collisionProperty - 1) & 0xFF;
            return;
        }
        super.onPlayerAttack(playerEntity, result);
    }

    private void initialize() {
        collisionProperty = 0x7F;
        waitTimer = CLOSED_WAIT_FRAMES;
        state = State.CLOSED_WAIT;
        shooters.add(spawnChild(() -> new SnaleBlasterShooterChild(this, -8, 0, false)));
        // ChildObjDat_8C28A assigns subtype 2 to the lower shooter itself.
        // loc_8C1B2 uses that child subtype for the longer firing delay and
        // loc_8C212 uses it to invert the projectile's Y velocity; the placed
        // parent subtype is unrelated.
        shooters.add(spawnChild(() -> new SnaleBlasterShooterChild(this, -8, 7, true)));
        cover = spawnChild(() -> new SnaleBlasterCoverChild(this, -8, 4));
    }

    private void updateClosedWait() {
        collisionEnabled = true;
        collisionProperty = firingWindow ? 0 : 0x7F;
        waitTimer--;
        if (waitTimer < 0) {
            beginOpening();
        }
    }

    private void beginOpening() {
        firingWindow = false;
        state = State.OPENING_PREP;
        mappingFrame = OPENING_PREP_FRAMES[0];
        openingPrepIndex = 0;
        openingPrepTimer = 0;
    }

    private void updateOpeningPrep() {
        collisionEnabled = true;
        openingPrepTimer--;
        if (openingPrepTimer >= 0) {
            return;
        }
        openingPrepIndex++;
        if (openingPrepIndex >= OPENING_PREP_FRAMES.length) {
            verticalStep = VERTICAL_STEP_OPEN;
            beginVerticalMotion(State.OPENING);
            return;
        }
        mappingFrame = OPENING_PREP_FRAMES[openingPrepIndex];
        openingPrepTimer = OPENING_PREP_DELAY;
    }

    private void updateOpenWait() {
        collisionEnabled = true;
        collisionProperty = firingWindow ? 0 : 0x7F;
        waitTimer--;
        if (waitTimer < 0) {
            beginTimedVerticalMotion();
        }
    }

    private void beginTimedVerticalMotion() {
        firingWindow = false;
        // loc_8C03E resumes byte_8C2C0 without resetting its raw-animation
        // cursor. loc_8C08A negates $40 after every completed three-step pass,
        // so the sign selects whether this pass opens or closes the shell.
        state = verticalStep < 0 ? State.OPENING : State.CLOSING;
        openCyclesRemaining = VERTICAL_CYCLE_COUNT;
    }

    private void beginVerticalMotion(State nextState) {
        state = nextState;
        openCyclesRemaining = VERTICAL_CYCLE_COUNT;
        verticalAnimTimer = 0;
        mappingFrame = verticalStep < 0 ? 4 : 3;
    }

    private void updateVerticalMotion(AbstractPlayableSprite player,
            State completionState, int completionWait, boolean armsFiringWindow) {
        collisionEnabled = true;
        collisionProperty = 0x7F;
        if (playerCanForceClose(player)) {
            state = State.CLOSING_FROM_PLAYER;
            mappingFrame = 3;
            return;
        }

        verticalAnimTimer--;
        if (verticalAnimTimer >= 0) {
            return;
        }
        verticalAnimTimer = VERTICAL_TOGGLE_DELAY;
        mappingFrame = mappingFrame == 3 ? 4 : 3;

        int triggerFrame = verticalStep < 0 ? 3 : 4;
        if (mappingFrame != triggerFrame) {
            return;
        }
        currentY += verticalStep;
        openCyclesRemaining--;
        if (openCyclesRemaining < 0) {
            state = completionState;
            waitTimer = completionWait;
            firingWindow = armsFiringWindow;
            verticalStep = -verticalStep;
        }
    }

    private boolean playerCanForceClose(AbstractPlayableSprite player) {
        if (player == null || player.getDead()) {
            return false;
        }
        int dx = Math.abs((short) (currentX - player.getCentreX()));
        int dy = Math.abs((short) (currentY - player.getCentreY()));
        return dx < PLAYER_CLOSE_RANGE && dy < PLAYER_CLOSE_RANGE && player.getAnimationId() == PLAYER_ROLL_ANIM;
    }

    private void updateEarlyClose() {
        collisionProperty = 0x7F;
        mappingFrame--;
        if (mappingFrame == 0) {
            state = State.EARLY_REOPEN_WAIT;
            waitTimer = EARLY_REOPEN_WAIT;
        }
    }

    private void updateEarlyReopenWait() {
        collisionProperty = 0x7F;
        waitTimer--;
        if (waitTimer < 0) {
            state = State.EARLY_REOPENING;
            waitTimer = EARLY_REOPEN_STEP_WAIT;
        }
    }

    private void updateEarlyReopen() {
        collisionProperty = 0x7F;
        waitTimer--;
        if (waitTimer >= 0) {
            return;
        }
        // loc_8C0F8 is Obj_Wait's callback and explicitly rewrites $1A.
        collisionEnabled = true;
        waitTimer = 2;
        mappingFrame++;
        if (mappingFrame >= 3) {
            beginTimedVerticalMotion();
        }
    }

    private boolean firingWindow() {
        return firingWindow;
    }

    private void clearFiringWindow() {
        firingWindow = false;
    }

    private void attachShooterForRewind(SnaleBlasterShooterChild shooter) {
        if (!shooters.contains(shooter)) {
            shooters.add(shooter);
        }
    }

    private void attachCoverForRewind(SnaleBlasterCoverChild restoredCover) {
        cover = restoredCover;
    }

    private ObjectSpawn childSpawnAt(int x, int y) {
        return buildSpawnAt(x, y);
    }

    private static SnaleBlasterBadnikInstance findLiveParentForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.spawn() == null || ctx.objectServices() == null
                || ctx.objectServices().objectManager() == null) {
            return null;
        }
        SnaleBlasterBadnikInstance best = null;
        long bestDistance = Long.MAX_VALUE;
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(instance instanceof SnaleBlasterBadnikInstance parent) || parent.isDestroyed()) {
                continue;
            }
            long dx = parent.getX() - ctx.spawn().x();
            long dy = parent.getY() - ctx.spawn().y();
            long distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = parent;
            }
        }
        return best;
    }

    private static final class SnaleBlasterShooterChild extends AbstractObjectInstance
            implements RewindRecreatable {
        private static final int PRIORITY_BUCKET = 4; // word_8C278 priority $200.
        private static final int REST_FRAME = 7;
        private static final int PROJECTILE_ANIM_INDEX = 2;
        private static final int[] FRAMES = {7, 7, 8, 7};
        private static final int[] DELAYS_NORMAL = {2, 0x1F, 3, 0};
        private static final int[] DELAYS_ALT = {2, 0x2F, 3, 0};

        enum State {
            WAIT_PARENT,
            FIRING,
            WAIT_PARENT_RESET
        }

        private final transient SnaleBlasterBadnikInstance parent;
        private int xOffset;
        private int yOffset;
        private boolean verticalFlipShot;
        private State state = State.WAIT_PARENT;
        private int currentX;
        private int currentY;
        private int mappingFrame = REST_FRAME;
        private int animIndex = -1;
        private int animTimer;
        private boolean shotFired;

        private SnaleBlasterShooterChild(SnaleBlasterBadnikInstance parent,
                int xOffset, int yOffset, boolean verticalFlipShot) {
            super(parent.childSpawnAt(parent.getX() + xOffset, parent.getY() + yOffset), "SnaleBlasterShooter");
            this.parent = parent;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.verticalFlipShot = verticalFlipShot;
            refreshPosition();
        }

        @Override
        public SnaleBlasterShooterChild recreateForRewind(RewindRecreateContext ctx) {
            SnaleBlasterBadnikInstance liveParent = findLiveParentForRewind(ctx);
            if (liveParent == null) {
                return null;
            }
            SnaleBlasterShooterChild restored =
                    new SnaleBlasterShooterChild(liveParent, 0, 0, false);
            liveParent.attachShooterForRewind(restored);
            return restored;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed() || parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            refreshPosition();
            switch (state) {
                case WAIT_PARENT -> updateWaitParent();
                case FIRING -> updateFiring();
                case WAIT_PARENT_RESET -> updateWaitParentReset();
            }
            updateDynamicSpawn(currentX, currentY);
        }

        private void updateWaitParent() {
            if (!parent.firingWindow()) {
                return;
            }
            state = State.FIRING;
            // loc_8C1B2 installs byte_8C2D0 without calling Set_Raw_Animation.
            // The child retains anim_frame=0 from setup, so the first
            // Animate_RawMultiDelay dispatch adds two and reads script offset
            // two (table index 1). Offset zero is the already-installed setup
            // frame.
            animIndex = 0;
            animTimer = 0;
            shotFired = false;
        }

        private void updateFiring() {
            animTimer--;
            if (animTimer >= 0) {
                return;
            }

            animIndex++;
            if (animIndex >= FRAMES.length) {
                mappingFrame = REST_FRAME;
                state = State.WAIT_PARENT_RESET;
                return;
            }

            mappingFrame = FRAMES[animIndex];
            animTimer = delays()[animIndex];
            if (animIndex == PROJECTILE_ANIM_INDEX && !shotFired) {
                spawnProjectile();
                shotFired = true;
            }
        }

        private int[] delays() {
            return verticalFlipShot ? DELAYS_ALT : DELAYS_NORMAL;
        }

        private void spawnProjectile() {
            services().playSfx(Sonic3kSfx.PROJECTILE.id);
            int xVelocity = parent.badnikFacingLeft() ? -0x200 : 0x200;
            int yVelocity = verticalFlipShot ? 0x100 : -0x100;
            spawnChild(() -> new SnaleBlasterProjectile(
                    buildSpawnAt(currentX, currentY), currentX, currentY, xVelocity, yVelocity,
                    !parent.badnikFacingLeft()));
        }

        private void updateWaitParentReset() {
            if (parent.firingWindow()) {
                return;
            }
            state = State.WAIT_PARENT;
            mappingFrame = REST_FRAME;
        }

        private void refreshPosition() {
            currentX = parent.getX() + xOffset;
            currentY = parent.getY() + yOffset;
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
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.SNALE_BLASTER);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, currentX, currentY, !parent.badnikFacingLeft(), false);
            }
        }
    }

    private static final class SnaleBlasterCoverChild extends AbstractObjectInstance
            implements RewindRecreatable {
        private static final int PRIORITY_BUCKET = 3; // word_8C272 priority $180.
        private static final int[] FRAMES = {5, 6, 10, 6, 5};
        private static final int[] DELAYS = {2, 2, 0x5F, 2, 2};

        enum State {
            WAIT_PARENT,
            FIRING
        }

        private final transient SnaleBlasterBadnikInstance parent;
        private int xOffset;
        private int yOffset;
        private State state = State.WAIT_PARENT;
        private int currentX;
        private int currentY;
        private int mappingFrame = 5;
        private int animIndex = -1;
        private int animTimer;

        private SnaleBlasterCoverChild(SnaleBlasterBadnikInstance parent, int xOffset, int yOffset) {
            super(parent.childSpawnAt(parent.getX() + xOffset, parent.getY() + yOffset), "SnaleBlasterCover");
            this.parent = parent;
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            refreshPosition();
        }

        @Override
        public SnaleBlasterCoverChild recreateForRewind(RewindRecreateContext ctx) {
            SnaleBlasterBadnikInstance liveParent = findLiveParentForRewind(ctx);
            if (liveParent == null) {
                return null;
            }
            SnaleBlasterCoverChild restored = new SnaleBlasterCoverChild(liveParent, 0, 0);
            liveParent.attachCoverForRewind(restored);
            return restored;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed() || parent.isDestroyed()) {
                ObjectLifetimeOps.expireDynamic(this);
                return;
            }
            refreshPosition();
            switch (state) {
                case WAIT_PARENT -> updateWaitParent();
                case FIRING -> updateFiring();
            }
            updateDynamicSpawn(currentX, currentY);
        }

        private void updateWaitParent() {
            if (!parent.firingWindow()) {
                return;
            }
            state = State.FIRING;
            animIndex = -1;
            animTimer = 0;
        }

        private void updateFiring() {
            animTimer--;
            if (animTimer >= 0) {
                return;
            }
            animIndex++;
            if (animIndex >= FRAMES.length) {
                state = State.WAIT_PARENT;
                mappingFrame = 5;
                parent.clearFiringWindow();
                return;
            }
            mappingFrame = FRAMES[animIndex];
            animTimer = DELAYS[animIndex];
        }

        private void refreshPosition() {
            currentX = parent.getX() + xOffset;
            currentY = parent.getY() + yOffset;
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
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.SNALE_BLASTER);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(mappingFrame, currentX, currentY, !parent.badnikFacingLeft(), false);
            }
        }
    }

    private static final class SnaleBlasterProjectile extends AbstractObjectInstance
            implements TouchResponseProvider, SpawnAndCoordinateZeroScalarArgsRewindRecreatable {
        private static final int COLLISION_FLAGS = 0x98; // ObjDat3_8C27E.
        private static final int PRIORITY_BUCKET = 4;    // ObjDat3_8C27E priority $200.
        private static final int MAPPING_FRAME = 9;
        private static final int SHIELD_REACTION_BOUNCE = 1 << 3;
        private static final int DEFLECT_SPEED = 0x800;
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

        private SnaleBlasterProjectile() {
            this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), 0, 0, 0, 0, false);
        }

        private SnaleBlasterProjectile(ObjectSpawn spawn, int x, int y,
                int xVelocity, int yVelocity, boolean hFlip) {
            super(spawn, "SnaleBlasterProjectile");
            this.currentX = x;
            this.currentY = y;
            this.xVelocity = xVelocity;
            this.yVelocity = yVelocity;
            this.hFlip = hFlip;
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            int xPos24 = (currentX << 8) | (xSubpixel & 0xFF);
            int yPos24 = (currentY << 8) | (ySubpixel & 0xFF);
            xPos24 += xVelocity;
            yPos24 += yVelocity;
            currentX = xPos24 >> 8;
            currentY = yPos24 >> 8;
            xSubpixel = xPos24 & 0xFF;
            ySubpixel = yPos24 & 0xFF;
            updateDynamicSpawn(currentX, currentY);
            if (!isOnScreen(48)) {
                ObjectLifetimeOps.expireDynamic(this);
            }
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
        public int getShieldReactionFlags() {
            return SHIELD_REACTION_BOUNCE;
        }

        @Override
        public boolean onShieldDeflect(PlayableEntity playerEntity) {
            if (!(playerEntity instanceof AbstractPlayableSprite player)) {
                return false;
            }
            int angle = TrigLookupTable.calcAngle(
                    saturateToShort(player.getCentreX() - currentX),
                    saturateToShort(player.getCentreY() - currentY));
            xVelocity = -((TrigLookupTable.cosHex(angle) * DEFLECT_SPEED) >> 8);
            yVelocity = -((TrigLookupTable.sinHex(angle) * DEFLECT_SPEED) >> 8);
            return true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            ObjectRenderManager renderManager = services().renderManager();
            if (renderManager == null) {
                return;
            }
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic3kObjectArtKeys.SNALE_BLASTER);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(MAPPING_FRAME, currentX, currentY, hFlip, false);
            }
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
