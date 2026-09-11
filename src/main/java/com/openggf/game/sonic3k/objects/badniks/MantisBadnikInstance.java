package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectInstance;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.ObjectTerrainUtils;
import com.openggf.physics.TerrainCheckResult;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * S3K Obj $9D - Mantis (MGZ Act 2).
 *
 * <p>ROM reference: {@code Obj_Mantis} (sonic3k.asm:185695-185840).
 * The enemy waits on-screen, turns to face the nearest player, and if the
 * player gets within 64 pixels horizontally it runs a short prep animation,
 * leaps upward, then plays a return animation before resuming idle.
 *
 * <p>Collision: {@code $1A} (ENEMY, size $1A). Art is loaded from
 * {@code ArtKosM_Mantis} / {@code Map_Mantis}.
 */
public final class MantisBadnikInstance extends AbstractS3kBadnikInstance
        implements SpawnRewindRecreatable {

    private static final int COLLISION_SIZE_INDEX = 0x1A;
    private static final int PRIORITY_BUCKET = 5;
    private static final int DETECT_RANGE = 0x40;
    private static final int LAUNCH_Y_VELOCITY = -0x600;
    private static final int GRAVITY = 0x38;
    private static final int FLOOR_Y_RADIUS = 0x29;
    private static final int WAIT_PLACEHOLDER_X_MARGIN = 0x20;
    private static final int WAIT_PLACEHOLDER_Y_MARGIN = 0x22;

    private static final int[] PREP_FRAMES = {0, 1, 2};
    private static final int[] PREP_DELAYS = {0, 2, 0};
    private static final int[] PREP_Y_OFFSETS = {0, -5, -0x13};

    private static final int[] LAND_FRAMES = {2, 1, 3, 0};
    private static final int[] LAND_DELAYS = {0, 2, 2, 0x1F};
    private static final int[] LAND_Y_OFFSETS = {0, 0x12, 6, -1};

    private static final int WAIT_FRAME = 0;
    private static final int CHILD_IDLE_FRAME = 4;
    private static final int CHILD_RISING_FRAME = 5;

    private enum State {
        WAIT,
        PREPARE,
        LAUNCH,
        LAND
    }

    private State state = State.WAIT;
    private boolean initialized;
    private int animIndex = -1;
    private int animTimer;
    private MantisChild child;
    private boolean waitPlaceholderRenderFlag;
    private boolean waitingForOnscreen = true;

    public MantisBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Mantis",
                Sonic3kObjectArtKeys.MGZ_MANTIS, COLLISION_SIZE_INDEX, PRIORITY_BUCKET);
        this.mappingFrame = WAIT_FRAME;
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }

        // Obj_WaitOffscreen keeps the operation pointer at loc_85AD2 while its
        // $20-by-$20 placeholder remains outside Render_Sprites bounds. Engine
        // placement already bridges the visible restore dispatch, so initialize
        // on the first live pass inside those bounds. Once restored, Obj_Mantis
        // dispatches every SST pass; Sprite_CheckDeleteTouch only owns the later
        // unload/touch work and is not a strict viewport update gate.
        if (!initialized && waitingForOnscreen) {
            if (!waitPlaceholderRenderFlag) {
                return;
            }
            // Obj_WaitOffscreen restores the saved operation and returns. The
            // restored Mantis initializer runs on this SST's following pass.
            waitingForOnscreen = false;
            return;
        }

        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite sprite
                ? sprite : null;

        if (!initialized) {
            initialize();
            initialized = true;
            // Obj_Mantis creates ChildObjDat_88F9C from loc_88E82, after
            // Obj_WaitOffscreen has restored the normal operation. The child
            // must therefore be allocated from this first visible initializer,
            // not while the placeholder is still waiting off-screen; its slot
            // participates in the subsequent FindNextFreeObj order.
            ensureVisualChild();
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        switch (state) {
            case WAIT -> updateWait(player);
            case PREPARE -> updatePrep();
            case LAUNCH -> updateLaunch();
            case LAND -> updateLand();
        }

        updateDynamicSpawn(currentX, currentY);
    }

    @Override
    public void onUnload() {
        destroyChild(child);
        child = null;
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // S3K Collision_response_list retains this Mantis SST pointer after its
        // MoveSprite update. The following player slot reads the live y_pos,
        // not the engine's older pre-update coordinate copy.
        return true;
    }

    @Override
    public int getCollisionFlags() {
        // Obj_WaitOffscreen has not reached SetUp_ObjAttributes yet, so the
        // freshly loaded SST's collision_flags byte remains zero until the
        // first visible Obj_Mantis initialization pass.
        return initialized ? super.getCollisionFlags() : 0;
    }

    private void initialize() {
        mappingFrame = WAIT_FRAME;
        animIndex = -1;
        animTimer = 0;
        currentY = spawn.y();
        yVelocity = 0;
    }

    private void ensureVisualChild() {
        if (child == null) {
            child = spawnChild(() -> new MantisChild(this));
        }
    }

    @Override
    protected void recreateConstructionChildrenForRewind() {
        // The child is created on Mantis's first update rather than in its Java
        // constructor. Recreate it while ObjectManager's reconstruction pool is
        // active so the captured child entry adopts this exact parent-owned
        // instance instead of using the legacy geometric fallback below.
        ensureVisualChild();
    }

    @Override
    public void refreshPostCameraRenderState() {
        if (!initialized) {
            waitPlaceholderRenderFlag = isWithinRenderSpriteBounds(
                    WAIT_PLACEHOLDER_X_MARGIN, WAIT_PLACEHOLDER_Y_MARGIN);
        }
    }

    private void updateWait(AbstractPlayableSprite player) {
        AbstractPlayableSprite target = findNearestTarget(player);
        if (target != null) {
            facingLeft = target.getCentreX() < currentX;
            if (Math.abs(target.getCentreX() - currentX) < DETECT_RANGE) {
                beginPrep();
            }
        }
    }

    private AbstractPlayableSprite findNearestTarget(AbstractPlayableSprite mainPlayer) {
        ObjectServices svc = tryServices();
        ObjectPlayerQuery query = new ObjectPlayerQuery(
                () -> mainPlayer,
                () -> svc != null ? svc.playerQuery().sidekicks() : List.of());
        PlayableEntity nearest = query.nearestByRomX(
                ObjectPlayerParticipationPolicy.NATIVE_P1_P2,
                currentX,
                MantisBadnikInstance::isLivePlayable).player();
        return nearest instanceof AbstractPlayableSprite sprite ? sprite : null;
    }

    private static boolean isLivePlayable(PlayableEntity player) {
        return player instanceof AbstractPlayableSprite sprite && !sprite.getDead();
    }

    private void updatePrep() {
        if (advanceScript(PREP_FRAMES, PREP_DELAYS, PREP_Y_OFFSETS, this::beginLaunch)) {
            updateDynamicSpawn(currentX, currentY);
        }
    }

    private void updateLaunch() {
        moveWithVelocity();
        yVelocity += GRAVITY;
        if (yVelocity < 0) {
            updateDynamicSpawn(currentX, currentY);
            return;
        }
        TerrainCheckResult floor = ObjectTerrainUtils.checkFloorDist(currentX, currentY, FLOOR_Y_RADIUS);
        if (floor.hasCollision()) {
            currentY += floor.distance();
            ySubpixel = 0;
            beginLand();
        }
        updateDynamicSpawn(currentX, currentY);
    }

    private void updateLand() {
        if (advanceScript(LAND_FRAMES, LAND_DELAYS, LAND_Y_OFFSETS, this::finishCycle)) {
            updateDynamicSpawn(currentX, currentY);
        }
    }

    private void beginPrep() {
        state = State.PREPARE;
        // Animate_RawNoSSTMultiDelay starts with frame 0 already visible and
        // advances to entry 1 on the first timer underflow.
        animIndex = 0;
        animTimer = 0;
        mappingFrame = PREP_FRAMES[0];
    }

    private void beginLaunch() {
        state = State.LAUNCH;
        yVelocity = LAUNCH_Y_VELOCITY;
    }

    private void beginLand() {
        state = State.LAND;
        yVelocity = 0;
        // Same raw animation semantics as the prep script: frame 0 is the
        // already-displayed landing pose, so the first tick advances to entry 1.
        animIndex = 0;
        animTimer = 0;
        mappingFrame = LAND_FRAMES[0];
    }

    private void finishCycle() {
        state = State.WAIT;
        yVelocity = 0;
        ySubpixel = 0;
        animIndex = -1;
        animTimer = 0;
        mappingFrame = WAIT_FRAME;
    }

    private boolean advanceScript(int[] frames, int[] delays, int[] yOffsets, Runnable onComplete) {
        animTimer--;
        if (animTimer >= 0) {
            return false;
        }

        animIndex++;
        if (animIndex >= frames.length) {
            if (onComplete != null) {
                onComplete.run();
            }
            return false;
        }

        mappingFrame = frames[animIndex];
        animTimer = delays[animIndex];
        currentY += yOffsets[animIndex];
        return true;
    }

    private static void destroyChild(AbstractObjectInstance child) {
        if (child != null) {
            child.setDestroyed(true);
        }
    }

    private static MantisBadnikInstance findLiveMantisParentForRewind(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        ObjectSpawn spawn = ctx.spawn();
        MantisBadnikInstance nearest = null;
        int nearestDistance = Integer.MAX_VALUE;
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (!(instance instanceof MantisBadnikInstance mantis) || mantis.isDestroyed()) {
                continue;
            }
            ObjectSpawn candidateSpawn = mantis.getSpawn();
            if (spawn.layoutIndex() >= 0 && candidateSpawn.layoutIndex() == spawn.layoutIndex()) {
                return mantis;
            }
            int distance = Math.abs(candidateSpawn.x() - spawn.x())
                    + Math.abs(candidateSpawn.y() - spawn.y());
            if (distance < nearestDistance) {
                nearest = mantis;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private static final class MantisChild extends AbstractObjectInstance implements RewindRecreatable {
        private MantisBadnikInstance parent;
        private int currentX;
        private int currentY;
        private int mappingFrame = CHILD_IDLE_FRAME;

        private MantisChild(MantisBadnikInstance parent) {
            super(parent.spawn, "MantisChild");
            this.parent = parent;
            syncFromParent();
        }

        @Override
        public MantisChild recreateForRewind(RewindRecreateContext ctx) {
            MantisBadnikInstance liveParent = findLiveMantisParentForRewind(ctx);
            return liveParent == null ? null : new MantisChild(liveParent);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }
            syncFromParent();
        }

        @Override
        public void onUnload() {
            detachFromParent();
        }

        @Override
        protected void onDroppedAsUnmatchedRewindReconstructionChild() {
            detachFromParent();
        }

        private void detachFromParent() {
            // The child has its own dynamic SST lifetime. If it is retired
            // before the parent, remove the parent's structural back-reference
            // before ObjectManager unregisters this child's rewind identity.
            if (parent != null && parent.child == this) {
                parent.child = null;
            }
            parent = null;
        }

        private void syncFromParent() {
            currentX = parent.getX() + (parent.badnikFacingLeft() ? -9 : 9);
            currentY = parent.getY() - 0x0B;
            mappingFrame = parent.yVelocity < 0 ? CHILD_RISING_FRAME : CHILD_IDLE_FRAME;
            updateDynamicSpawn(currentX, currentY);
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
            if (parent.isDestroyed()) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.MGZ_MANTIS);
            if (renderer == null) {
                return;
            }
            renderer.drawFrameIndex(mappingFrame, currentX, currentY, !parent.badnikFacingLeft(), false);
        }
    }
}
