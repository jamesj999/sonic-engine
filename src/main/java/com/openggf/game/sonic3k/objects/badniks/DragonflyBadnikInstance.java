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
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.SwingMotion;

import java.util.List;

/**
 * S3K SKL Obj $8E - Dragonfly.
 *
 * <p>ROM reference: {@code Obj_Dragonfly} at {@code sonic3k.asm:193742}.
 * This parent owns the collidable body and executes the shared
 * {@code Swing_LeftAndRight}, {@code Swing_UpAndDown}, and {@code MoveSprite2}
 * path before pausing at the vertical midpoint.
 */
public final class DragonflyBadnikInstance extends AbstractS3kBadnikInstance
        implements SpawnRewindRecreatable, DragonflyHoverReturnGate {

    private static final int COLLISION_SIZE_INDEX = 0x17;
    private static final int PRIORITY_BUCKET = 5;
    private static final int RENDER_HALF_WIDTH = 0x08;
    private static final int RENDER_HALF_HEIGHT = 0x08;
    private static final int WAIT_OFFSCREEN_MARGIN = 0x20;
    private static final int INITIAL_SPEED = 0x200;
    private static final int X_SWING_ACCELERATION = 0x20;
    private static final int Y_SWING_ACCELERATION = 0x08;
    private static final int HOVER_WAIT_FRAMES = 0x0F;
    private static final int LINKED_CHILD_COUNT = 7;
    private static final int[] LINKED_CHILD_Y_OFFSETS = {-0x0C, -5, -5, -5, -5, -5, -5};
    private static final int[] WING_ANIMATION_FRAMES = {7, 9, 8, 9};
    private static final int[] BODY_ANIM_UP_SCRIPT = {
            3, 4, 3, 2, 1, 0, 0xF8, 8, 0x7F, 0, 0, 0xFC
    };
    private static final int[] BODY_ANIM_DOWN_SCRIPT = {
            3, 0, 1, 2, 3, 4, 0xF8, 8, 0x7F, 4, 4, 0xFC
    };

    private enum State {
        PATROLLING,
        WAITING
    }

    private State state = State.PATROLLING;
    private boolean horizontalDirectionPositive;
    private boolean verticalDirectionPositive;
    private boolean useDownAnimation;
    private int[] bodyAnimationScript = BODY_ANIM_UP_SCRIPT;
    private int bodyAnimationScriptOffset;
    private int bodyAnimationFrame;
    private int bodyAnimationTimer;
    private int waitTimer;
    private boolean waitingForOnscreen = true;
    private boolean initialized;

    public DragonflyBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Dragonfly", Sonic3kObjectArtKeys.DRAGONFLY,
                COLLISION_SIZE_INDEX, PRIORITY_BUCKET, true);
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
            initializeRomSetupState();
            spawnInitialChildren();
            updateDynamicSpawn(currentX, currentY);
            return;
        }

        switch (state) {
            case PATROLLING -> updatePatrol();
            case WAITING -> updateWait();
        }
        updateDynamicSpawn(currentX, currentY);
    }

    private void initializeRomSetupState() {
        xVelocity = INITIAL_SPEED;
        yVelocity = INITIAL_SPEED;
        mappingFrame = 4;
    }

    @Override
    public int getCollisionFlags() {
        return waitingForOnscreen || !initialized ? 0 : super.getCollisionFlags();
    }

    private void updatePatrol() {
        animateBodyRaw();
        updateHorizontalSwing();
        updateVerticalSwing();
        moveWithVelocity();
        if (yVelocity == 0) {
            state = State.WAITING;
            waitTimer = HOVER_WAIT_FRAMES;
        }
    }

    private void updateWait() {
        updateHorizontalSwing();
        moveWithVelocity();
        waitTimer--;
        if (waitTimer >= 0) {
            return;
        }

        state = State.PATROLLING;
        switchBodyAnimationAfterHover();
    }

    private void updateHorizontalSwing() {
        SwingMotion.Result result = SwingMotion.update(
                X_SWING_ACCELERATION, xVelocity, INITIAL_SPEED, horizontalDirectionPositive);
        xVelocity = result.velocity();
        horizontalDirectionPositive = result.directionDown();
    }

    private void updateVerticalSwing() {
        SwingMotion.Result result = SwingMotion.update(
                Y_SWING_ACCELERATION, yVelocity, INITIAL_SPEED, verticalDirectionPositive);
        yVelocity = result.velocity();
        verticalDirectionPositive = result.directionDown();
    }

    private void animateBodyRaw() {
        bodyAnimationTimer--;
        if (bodyAnimationTimer >= 0) {
            return;
        }

        bodyAnimationFrame++;
        int frameOffset = bodyAnimationScriptOffset + 1 + bodyAnimationFrame;
        int value = bodyAnimationScript[frameOffset];
        if (value < 0x80) {
            bodyAnimationTimer = bodyAnimationScript[bodyAnimationScriptOffset];
            mappingFrame = value;
            return;
        }

        switch (value) {
            case 0xF8 -> {
                int jumpOffset = signedByte(bodyAnimationScript[frameOffset + 1]);
                bodyAnimationScriptOffset += jumpOffset;
                restartBodyAnimationAtCurrentPointer();
            }
            case 0xFC -> restartBodyAnimationAtCurrentPointer();
            default -> throw new IllegalStateException("Unsupported Dragonfly Animate_Raw command: " + value);
        }
    }

    private void switchBodyAnimationAfterHover() {
        boolean previousUseDownAnimation = useDownAnimation;
        useDownAnimation = !useDownAnimation;
        bodyAnimationScript = previousUseDownAnimation ? BODY_ANIM_DOWN_SCRIPT : BODY_ANIM_UP_SCRIPT;
        bodyAnimationScriptOffset = 0;
        bodyAnimationFrame = 0;
        bodyAnimationTimer = 0;
    }

    private void restartBodyAnimationAtCurrentPointer() {
        bodyAnimationFrame = 0;
        mappingFrame = bodyAnimationScript[bodyAnimationScriptOffset + 1];
        bodyAnimationTimer = bodyAnimationScript[bodyAnimationScriptOffset];
    }

    private static int signedByte(int value) {
        return value >= 0x80 ? value - 0x100 : value;
    }

    private void spawnInitialChildren() {
        spawnChild(() -> new WingChild(this));
        AbstractObjectInstance followAnchor = this;
        for (int i = 0; i < LINKED_CHILD_COUNT; i++) {
            int subtype = i << 1;
            int segmentIndex = i;
            AbstractObjectInstance currentAnchor = followAnchor;
            followAnchor = spawnChild(() -> new LinkedBodyChild(this, currentAnchor, subtype, segmentIndex));
        }
    }

    public String getStateName() {
        return state.name();
    }

    public int getWaitTimer() {
        return waitTimer;
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

    boolean isUsingDownBodyAnimation() {
        return bodyAnimationScript == BODY_ANIM_DOWN_SCRIPT;
    }

    @Override
    public int getOnScreenHalfWidth() {
        return RENDER_HALF_WIDTH;
    }

    @Override
    public int getOnScreenHalfHeight() {
        return RENDER_HALF_HEIGHT;
    }

    /**
     * Mirrors ROM {@code $38} bit 2 on the main Dragonfly (set in {@code loc_8DDCA}
     * on entering hover-wait, cleared in {@code loc_8DDF8} on exit). The first
     * linked-body segment (whose {@code parent3} anchor is the Dragonfly itself)
     * reads this directly; unlike the tail-internal hops, the Dragonfly always
     * runs before any child in the ROM object list, so this is visible the same
     * frame it changes -- no one-frame delay needed here.
     */
    @Override
    public boolean isHoverReturnGateOpen() {
        return state == State.WAITING;
    }

    private static DragonflyBadnikInstance findLiveDragonflyParent(RewindRecreateContext ctx) {
        if (ctx == null || ctx.objectServices() == null || ctx.objectServices().objectManager() == null) {
            return null;
        }
        for (ObjectInstance instance : ctx.objectServices().objectManager().getActiveObjects()) {
            if (instance instanceof DragonflyBadnikInstance dragonfly && !dragonfly.isDestroyed()) {
                return dragonfly;
            }
        }
        return null;
    }

    public static final class WingChild extends AbstractObjectInstance implements RewindRecreatable {
        private static final int RENDER_HALF_WIDTH = 0x20;
        private static final int RENDER_HALF_HEIGHT = 0x08;

        private DragonflyBadnikInstance parent;
        private int animationIndex;
        private boolean setupFrame = true;

        WingChild(DragonflyBadnikInstance parent) {
            super(new ObjectSpawn(parent.getX(), parent.getY(), 0, 0, 0, false, 0),
                    "DragonflyWingChild");
            this.parent = parent;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            DragonflyBadnikInstance liveParent = findLiveDragonflyParent(ctx);
            return liveParent == null ? null : new WingChild(liveParent);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }
            if (setupFrame) {
                setupFrame = false;
            } else {
                animationIndex = (animationIndex + 1) & 0x03;
            }
            updateDynamicSpawn(getX(), getY());
        }

        @Override
        public int getX() {
            return parent.getX();
        }

        @Override
        public int getY() {
            return parent.getY();
        }

        @Override
        public int getOutOfRangeReferenceX() {
            return parent.getX();
        }

        @Override
        public int getPriorityBucket() {
            return 5;
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
            if (isDestroyed()) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.DRAGONFLY);
            if (renderer != null) {
                renderer.drawFrameIndexForcedPriority(
                        WING_ANIMATION_FRAMES[animationIndex], getX(), getY(), false, false, -1, true);
            }
        }
    }

    public static final class LinkedBodyChild extends AbstractObjectInstance
            implements TouchResponseProvider, RewindRecreatable, DragonflyHoverReturnGate {
        private static final int PHASE_FOLLOW_PARENT_OFFSET = 0;
        private static final int PHASE_RETURN_TO_PARENT_Y = 1;
        private static final int PHASE_WAIT_FOR_PARENT_TO_REACH_OPPOSITE_OFFSET = 2;
        private static final int COLLISION_FLAGS = 0x98;
        private static final int RENDER_HALF_WIDTH = 0x04;
        private static final int RENDER_HALF_HEIGHT = 0x04;

        private DragonflyBadnikInstance parent;
        private AbstractObjectInstance followAnchor;
        // Un-final so the generic field capturer reapplies the captured values
        // after a rewind recreate with spawn-derived placeholders.
        private int subtype;
        private int segmentIndex;
        private int childX;
        private int childY;
        private int childXSubpixel;
        private int childXVelocity = INITIAL_SPEED;
        private int childDx = 1;
        private int childDy;
        private int countdown;
        private int verticalPhase;
        private boolean horizontalDirectionPositive;
        private boolean verticalRenderFlip = true;
        private boolean setupFrame = true;
        // Mirrors this segment's own ROM $38 bit 2 (set entering the return
        // phase in loc_8DEA8, cleared completing the wait phase in loc_8DF24).
        private boolean returnGateBit;
        // What the NEXT segment in the chain observes. CreateChild4_LinkListRepeated
        // inserts each new segment via AllocateObjectAfterCurrent right after the
        // Dragonfly's own slot, so later segments end up processed BEFORE earlier
        // ones within a frame; a segment therefore only sees its predecessor's
        // returnGateBit as it stood at the end of the PREVIOUS frame, producing
        // the ROM's one-frame ripple down the chain. Promoted once per own update().
        private boolean returnGateVisibleToFollower;

        private LinkedBodyChild() {
            this(new DragonflyBadnikInstance(new ObjectSpawn(0, 0, 0, 0, 0, false, 0)), 0, 0);
        }

        LinkedBodyChild(DragonflyBadnikInstance parent, int subtype, int segmentIndex) {
            this(parent, parent, subtype, segmentIndex);
        }

        LinkedBodyChild(DragonflyBadnikInstance parent, AbstractObjectInstance followAnchor,
                        int subtype, int segmentIndex) {
            super(new ObjectSpawn(parent.getX(), parent.getY(), 0, subtype, 0, false, 0),
                    "DragonflyLinkedBodyChild");
            this.parent = parent;
            this.followAnchor = followAnchor;
            this.subtype = subtype;
            this.segmentIndex = segmentIndex;
            this.childX = parent.getX();
            this.childDy = LINKED_CHILD_Y_OFFSETS[segmentIndex];
            this.childY = parent.getY();
            this.countdown = subtype;
        }

        @Override
        public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
            DragonflyBadnikInstance liveParent = findLiveDragonflyParent(ctx);
            if (liveParent == null) {
                return null;
            }
            ObjectSpawn spawn = ctx.spawn();
            int restoredSubtype = spawn != null ? spawn.subtype() : 0;
            int restoredSegmentIndex = restoredSubtype >> 1;
            return new LinkedBodyChild(liveParent, liveParent, restoredSubtype, restoredSegmentIndex);
        }

        @Override
        public void update(int vIntRunCount, PlayableEntity playerEntity) {
            if (parent.isDestroyed()) {
                setDestroyed(true);
                return;
            }
            returnGateVisibleToFollower = returnGateBit;
            if (setupFrame) {
                setupFrame = false;
                updateDynamicSpawn(childX, childY);
                return;
            }
            if (countdown >= 0) {
                updateParentRelativeY();
                countdown--;
                updateDynamicSpawn(childX, childY);
                return;
            }
            if (verticalPhase == PHASE_FOLLOW_PARENT_OFFSET) {
                updateParentRelativeY();
            }
            updateHorizontalSwing();
            moveHorizontally();
            updateVerticalPhase();
            updateDynamicSpawn(getX(), getY());
        }

        @Override
        public int getX() {
            return childX;
        }

        @Override
        public int getY() {
            return childY;
        }

        @Override
        public int getOutOfRangeReferenceX() {
            return parent.getX();
        }

        boolean isReturningToParentY() {
            return verticalPhase != PHASE_FOLLOW_PARENT_OFFSET;
        }

        @Override
        public boolean isHoverReturnGateOpen() {
            return returnGateVisibleToFollower;
        }

        private void updateParentRelativeY() {
            childY = followAnchor.getY() + childDy;
        }

        private void updateHorizontalSwing() {
            SwingMotion.Result result = SwingMotion.update(
                    X_SWING_ACCELERATION, childXVelocity, INITIAL_SPEED, horizontalDirectionPositive);
            childXVelocity = result.velocity();
            horizontalDirectionPositive = result.directionDown();
        }

        private void moveHorizontally() {
            int nextSubpixel = childXSubpixel + childXVelocity;
            childX += nextSubpixel >> 8;
            childXSubpixel = nextSubpixel & 0xFF;
        }

        private void updateVerticalPhase() {
            switch (verticalPhase) {
                case PHASE_FOLLOW_PARENT_OFFSET -> {
                    if (followAnchor instanceof DragonflyHoverReturnGate gate && gate.isHoverReturnGateOpen()) {
                        verticalPhase = PHASE_RETURN_TO_PARENT_Y;
                        returnGateBit = true;
                    }
                }
                case PHASE_RETURN_TO_PARENT_Y -> moveTowardParentY();
                case PHASE_WAIT_FOR_PARENT_TO_REACH_OPPOSITE_OFFSET -> waitForParentOppositeOffset();
                default -> verticalPhase = PHASE_FOLLOW_PARENT_OFFSET;
            }
        }

        private void moveTowardParentY() {
            int parentY = followAnchor.getY();
            int nextY = childY + childDx;
            if ((childDx > 0 && nextY >= parentY) || (childDx < 0 && nextY <= parentY)) {
                childY = parentY;
                verticalPhase = PHASE_WAIT_FOR_PARENT_TO_REACH_OPPOSITE_OFFSET;
                childDx = -childDx;
                childDy = -childDy;
                return;
            }
            childY = nextY;
        }

        private void waitForParentOppositeOffset() {
            int targetY = followAnchor.getY() + childDy;
            if ((childDy >= 0 && targetY > childY) || (childDy < 0 && targetY < childY)) {
                return;
            }
            childY = targetY;
            verticalPhase = PHASE_FOLLOW_PARENT_OFFSET;
            verticalRenderFlip = !verticalRenderFlip;
            returnGateBit = false;
        }

        @Override
        public int getPriorityBucket() {
            return 5;
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
        public int getOnScreenHalfWidth() {
            return RENDER_HALF_WIDTH;
        }

        @Override
        public int getOnScreenHalfHeight() {
            return RENDER_HALF_HEIGHT;
        }

        @Override
        public void appendRenderCommands(List<GLCommand> commands) {
            if (isDestroyed()) {
                return;
            }
            PatternSpriteRenderer renderer = getRenderer(Sonic3kObjectArtKeys.DRAGONFLY);
            if (renderer != null) {
                int frame = subtype == 0x0C ? 6 : 5;
                renderer.drawFrameIndexForcedPriority(frame, getX(), getY(), false, verticalRenderFlip, -1, true);
            }
        }
    }
}
