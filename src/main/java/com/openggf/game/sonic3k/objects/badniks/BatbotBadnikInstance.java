package com.openggf.game.sonic3k.objects.badniks;

import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic3k.Sonic3kObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;

import java.util.List;

/**
 * S3K Obj $A5 - Batbot.
 *
 * <p>ROM reference: {@code Obj_Batbot} in {@code docs/skdisasm/sonic3k.asm}
 * around {@code loc_89394}. The parent is the only collidable part, but the two
 * visual children created by {@code ChildObjDat_8946C} still occupy real SST
 * slots and therefore participate in native allocation timing.
 */
public final class BatbotBadnikInstance extends AbstractS3kBadnikInstance implements SpawnRewindRecreatable {

    private static final int COLLISION_SIZE = 0x0D;
    private static final int PRIORITY_BUCKET = 5;
    private static final int ACTIVATION_RANGE = 0x40;
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;
    private static final int CHASE_MAX_SPEED = 0x200;
    private static final int CHASE_ACCELERATION = 8;
    private static final int INITIAL_ACTIVE_X_SPEED = 0x200;
    private static final int INITIAL_MAPPING_FRAME = 2;
    private static final int BODY_CHILD_FRAME = 3;
    private static final int BODY_CHILD_Y_OFFSET = 0x10;
    private static final int LAMP_CHILD_FRAME = 5;
    private static final int LAMP_CHILD_Y_OFFSET = 0x03;
    private static final int PARENT_ANIM_DELAY = 2;
    private static final int[] PARENT_ANIM_FRAMES = {0, 1, 2, 1};
    private static final int[] BODY_ANIM_FRAMES = {3, 4, 3, 4, 3, 4};
    private static final int[] BODY_ANIM_DELAYS = {0x1D, 2, 1, 2, 0x0E, 2};

    private enum State { INIT, WAIT, CHASE }

    private State state = State.INIT;
    private boolean waitingForOnscreen = true;
    private boolean deleteCurrentSpriteMarker;
    private int parentAnimIndex;
    private int parentAnimTimer;

    public BatbotBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Batbot", Sonic3kObjectArtKeys.CNZ_BATBOT,
                COLLISION_SIZE, PRIORITY_BUCKET, true);
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        if (isDestroyed()) {
            return;
        }
        if (deleteCurrentSpriteMarker) {
            setDestroyedByOffscreen();
            return;
        }

        if (waitingForOnscreen) {
            // Obj_WaitOffscreen installs Map_Offscreen with width/height $20
            // and restores Obj_Batbot only after the temporary sprite has been
            // drawn onscreen; the restored object op runs next frame
            // (sonic3k.asm:180266-180297, 186266-186272).
            if (isDeleteSpriteIfNotInRange()) {
                // loc_85AD2 still owns the ordinary coarse-X deletion path
                // while the placeholder waits. Without it, vertically distant
                // Batbots leak their SST slots after the camera passes them.
                setDestroyedByOffscreen();
                return;
            }
            if (!isOnScreen(WAIT_OFFSCREEN_MARGIN)) {
                updateDynamicSpawn(currentX, currentY);
                return;
            }
            waitingForOnscreen = false;
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        switch (state) {
            case INIT -> initialize();
            case WAIT -> updateWait(playerEntity);
            case CHASE -> updateChase(playerEntity);
        }

        updateDynamicSpawn(currentX, currentY);
        if (isDeleteSpriteIfNotInRange()) {
            // ROM Sprite_CheckDeleteTouch branches through loc_85088/Go_Delete_Sprite:
            // set status bit 7 and install Delete_Current_Sprite, leaving the SST
            // slot occupied until the next ExecuteObjects pass (sonic3k.asm:
            // 179058-179134). CNZ2 slot-pressure guards depend on this marker
            // remaining occupied through the next low-slot allocation window.
            deleteCurrentSpriteMarker = true;
        }
    }

    @Override
    public boolean usesCustomOutOfRangeCheck() {
        return true;
    }

    @Override
    public boolean isCustomOutOfRange(int cameraX) {
        return false;
    }

    private void initialize() {
        mappingFrame = INITIAL_MAPPING_FRAME;
        state = State.WAIT;
        // CreateChild1_Normal allocates both visual pieces as genuine objects
        // after the parent. Even though their collision byte is zero, their SST
        // occupancy changes later AllocateObjectAfterCurrent results
        // (sonic3k.asm:176914-176943,186283-186397).
        spawnVisualChildren();
    }

    private void spawnVisualChildren() {
        spawnChild(() -> new BatbotVisualChild(this, ChildKind.BODY));
        spawnChild(() -> new BatbotVisualChild(this, ChildKind.LAMP));
    }

    @Override
    protected void recreateConstructionChildrenForRewind() {
        // Obj_Batbot creates this fixed two-slot graph on its first normal
        // dispatch. Rebuild candidates so rewind adopts the exact captured
        // BODY/LAMP identities and restores their scalar animation state.
        spawnVisualChildren();
    }

    private void updateWait(PlayableEntity playerEntity) {
        // loc_893A4 calls Find_SonicTails and tests d2, so Player 2 can wake
        // the Batbot even though the later Chase_Object routine is hard-wired
        // to Player 1.
        PlayableEntity activationTarget = closestNativePlayerByHorizontalDistance(playerEntity);
        if (!isPlayerWithinActivationRange(activationTarget)) {
            return;
        }
        state = State.CHASE;
        xVelocity = INITIAL_ACTIVE_X_SPEED;
    }

    private boolean isPlayerWithinActivationRange(PlayableEntity playerEntity) {
        if (playerEntity == null || playerEntity.getDead()) {
            return false;
        }
        int dx = Math.abs(currentX - playerEntity.getCentreX());
        return dx < ACTIVATION_RANGE;
    }

    private void updateChase(PlayableEntity playerEntity) {
        if (playerEntity != null && !playerEntity.getDead()) {
            chase(playerEntity.getCentreX(), playerEntity.getCentreY());
        }
        moveWithVelocity();
        updateParentAnimation();
    }

    private void updateParentAnimation() {
        parentAnimTimer--;
        if (parentAnimTimer >= 0) {
            return;
        }
        parentAnimIndex++;
        if (parentAnimIndex >= PARENT_ANIM_FRAMES.length) {
            parentAnimIndex = 0;
        }
        mappingFrame = PARENT_ANIM_FRAMES[parentAnimIndex];
        parentAnimTimer = PARENT_ANIM_DELAY;
    }

    /**
     * Port of shared ROM helper {@code Chase_Object}: accelerate toward the
     * target on each axis only when the new velocity remains inside +/- max.
     */
    private void chase(int targetX, int targetY) {
        boolean xEqual = currentX == targetX;
        if (!xEqual) {
            int xAccel = currentX > targetX ? -CHASE_ACCELERATION : CHASE_ACCELERATION;
            int nextXVelocity = xVelocity + xAccel;
            if (nextXVelocity >= -CHASE_MAX_SPEED && nextXVelocity <= CHASE_MAX_SPEED) {
                xVelocity = nextXVelocity;
            }
        }

        boolean yEqual = currentY == targetY;
        if (yEqual) {
            if (xEqual) {
                xVelocity = 0;
                yVelocity = 0;
            }
            return;
        }

        int yAccel = currentY > targetY ? -CHASE_ACCELERATION : CHASE_ACCELERATION;
        int nextYVelocity = yVelocity + yAccel;
        if (nextYVelocity >= -CHASE_MAX_SPEED && nextYVelocity <= CHASE_MAX_SPEED) {
            yVelocity = nextYVelocity;
        }
    }

    @Override
    public int getCollisionFlags() {
        return deleteCurrentSpriteMarker || waitingForOnscreen || state == State.INIT ? 0 : super.getCollisionFlags();
    }

    @Override
    public boolean usesCurrentTouchResponseState() {
        // Obj_Batbot publishes itself after Chase_Object + MoveSprite2, and
        // S3K's Collision_response_list stores its SST pointer. Touch_Loop
        // therefore reads the Batbot's live frame-start x_pos/y_pos rather
        // than a copied coordinate (sonic3k.asm:186312-186319,20656-20710).
        return true;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }
        PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_BATBOT);
        if (renderer == null) {
            return;
        }
        boolean hFlip = !facingLeft;
        int x = getRenderAnchorX();
        int y = getRenderAnchorY();
        renderer.drawFrameIndex(mappingFrame, x, y, hFlip, false);
    }

    private boolean isDeleteSpriteIfNotInRange() {
        ObjectServices svc = tryServices();
        if (svc == null || svc.camera() == null) {
            return false;
        }
        int objectCoarse = currentX & 0xFF80;
        int cameraCoarseBack = (svc.camera().getX() - 0x80) & 0xFF80;
        return ((objectCoarse - cameraCoarseBack) & 0xFFFF) > 0x280;
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s waitOn=%s delMark=%s vx=%04X vy=%04X spawn=%04X,%04X",
                state, waitingForOnscreen, deleteCurrentSpriteMarker, xVelocity & 0xFFFF, yVelocity & 0xFFFF,
                spawn.x() & 0xFFFF, spawn.y() & 0xFFFF);
    }

    private boolean childrenMustDelete() {
        // Child_Draw_Sprite tests parent status bit 7. Sprite_CheckDeleteTouch
        // sets that bit when it installs the parent's one-dispatch delete marker.
        // Child_Draw_Sprite then installs the child's own Delete_Current_Sprite
        // marker; the child slot is cleared on its following dispatch
        // (sonic3k.asm:178046-178052,179058-179139).
        return isDestroyed() || deleteCurrentSpriteMarker;
    }

    private enum ChildKind {
        BODY(BODY_CHILD_Y_OFFSET, BODY_CHILD_FRAME),
        LAMP(LAMP_CHILD_Y_OFFSET, LAMP_CHILD_FRAME);

        private final int yOffset;
        private final int initialFrame;

        ChildKind(int yOffset, int initialFrame) {
            this.yOffset = yOffset;
            this.initialFrame = initialFrame;
        }
    }

    private enum ChildState { INIT, WAIT, ACTIVE }

    static final class BatbotVisualChild extends com.openggf.level.objects.AbstractObjectInstance
            implements SpawnRewindRecreatable {
        private static final int CHILD_PRIORITY_BUCKET = 4; // word_89460/word_89466: priority $200.

        private BatbotBadnikInstance parent;
        private ChildKind kind;
        private ChildState childState = ChildState.INIT;
        private boolean deleteCurrentSpriteMarker;
        private int currentX;
        private int currentY;
        private int mappingFrame;
        private int bodyAnimIndex;
        private int bodyAnimTimer;

        private BatbotVisualChild(BatbotBadnikInstance parent, ChildKind kind) {
            super(parent.buildSpawnAt(parent.currentX, parent.currentY + kind.yOffset),
                    kind == ChildKind.BODY ? "BatbotBody" : "BatbotLamp");
            this.parent = parent;
            this.kind = kind;
            this.currentX = parent.currentX;
            this.currentY = parent.currentY + kind.yOffset;
            this.mappingFrame = kind.initialFrame;
        }

        private BatbotVisualChild(ObjectSpawn spawn) {
            super(spawn, "BatbotVisual");
            this.kind = ChildKind.BODY;
            this.currentX = spawn.x();
            this.currentY = spawn.y();
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (isDestroyed()) {
                return;
            }
            if (deleteCurrentSpriteMarker) {
                setDestroyedByOffscreen();
                return;
            }
            if (parent.childrenMustDelete()) {
                deleteCurrentSpriteMarker = true;
                return;
            }

            // Both child operations begin with Refresh_ChildPosition, so a
            // higher-slot child observes movement performed by the parent earlier
            // in this same ExecuteObjects pass (sonic3k.asm:186320-186374).
            currentX = parent.currentX;
            currentY = parent.currentY + kind.yOffset;
            switch (childState) {
                case INIT -> childState = ChildState.WAIT;
                case WAIT -> {
                    if (parent.state == State.CHASE) {
                        childState = ChildState.ACTIVE;
                        mappingFrame++;
                    }
                }
                case ACTIVE -> {
                    if (kind == ChildKind.BODY) {
                        updateBodyAnimation();
                    }
                }
            }
            updateDynamicSpawn(currentX, currentY);
        }

        private void updateBodyAnimation() {
            bodyAnimTimer--;
            if (bodyAnimTimer >= 0) {
                return;
            }
            bodyAnimIndex++;
            if (bodyAnimIndex >= BODY_ANIM_FRAMES.length) {
                bodyAnimIndex = 0;
            }
            mappingFrame = BODY_ANIM_FRAMES[bodyAnimIndex];
            bodyAnimTimer = BODY_ANIM_DELAYS[bodyAnimIndex];
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
        public int getOutOfRangeReferenceX() {
            return parent.currentX;
        }

        @Override
        public int getPriorityBucket() {
            return CHILD_PRIORITY_BUCKET;
        }

        @Override
        public boolean isHighPriority() {
            // CreateChild1_Normal copies the parent's art_tile, including its
            // high-priority plane bit (sonic3k.asm:176925-176927).
            return true;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed() || deleteCurrentSpriteMarker) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.CNZ_BATBOT);
            if (renderer != null) {
                // CreateChild1_Normal copies mappings and art_tile, but not the
                // parent's render_flags; SetUp_ObjAttributes3 only sets bit 2.
                // Batbot visual children therefore never inherit parent X flip
                // (sonic3k.asm:176907-176947).
                renderer.drawFrameIndex(mappingFrame, currentX, currentY, false, false);
            }
        }
    }
}
