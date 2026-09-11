package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.SolidContact;
import com.openggf.level.objects.SolidObjectListener;
import com.openggf.level.objects.SolidObjectParams;
import com.openggf.level.objects.SolidObjectProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.Sprite;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Rexon (0x94/0x96) - Lava snake badnik from HTZ.
 * This is the body/platform that patrols and spawns head segments.
 * Based on disassembly Obj94.
 *
 * Behavior:
 * - Patrols left/right with -0x20 velocity
 * - Checks for player in angular range
 * - When player detected, spawns 5 head segments that rise up
 * - Body stays stationary as anchor after spawning heads
 */
public class RexonBadnikInstance extends AbstractBadnikInstance
        implements SolidObjectProvider, SolidObjectListener, RewindRecreatable {
    // Collision size from Obj94_SubObjData (s2.asm:74061)
    // Body has collision 0, not 0x0B - heads have their own collision
    private static final int COLLISION_SIZE_INDEX = 0x00;

    // Solid collision dimensions from s2.asm:73743-73748 (Obj94_SolidCollision)
    private static final int SOLID_HALF_WIDTH = 0x1B;      // 27 pixels
    private static final int SOLID_AIR_HALF_HEIGHT = 8;    // 8 pixels when jumping
    private static final int SOLID_GROUND_HALF_HEIGHT = 8; // 8 pixels when walking

    // Movement constants from disassembly
    private static final int X_VELOCITY = -0x20;  // Patrol velocity (8.8 fixed)
    private static final int PATROL_TIMER = 128;  // Frames before reversing direction

    // Detection constants
    private static final int DETECT_ANGLE_OFFSET = 0x60;  // Added to signed delta before unsigned-window compare
    private static final int DETECT_ANGLE_RANGE = 0x100;  // ROM cmpi.w #$100,d2 / bhs out-of-range

    private enum State {
        WAIT_FOR_PLAYER,    // Routine 2 - Patrol and detect
        READY_TO_CREATE,    // Routine 4 - About to spawn heads
        POST_CREATE_HEAD    // Routine 6 - Heads spawned, act as platform
    }

    private State state;
    private int patrolTimer;
    private final SubpixelMotion.State motionState;
    private boolean xFlipFlag;
    private final List<RexonHeadObjectInstance> heads = new RewindRelinkingHeadList();
    private int lastTargetX;
    private int lastTargetDistance;

    public RexonBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Rexon", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xVelocity = X_VELOCITY;
        this.patrolTimer = PATROL_TIMER;
        this.motionState = new SubpixelMotion.State(spawn.x(), spawn.y(), 0, 0, 0, 0);
        this.state = State.WAIT_FOR_PLAYER;

        // Initial flip from spawn
        this.xFlipFlag = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlipFlag;
        this.lastTargetX = 0;
        this.lastTargetDistance = 0;
    }

    @Override
    public RexonBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new RexonBadnikInstance(ctx.spawn());
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        switch (state) {
            case WAIT_FOR_PLAYER -> updatePatrol(player);
            case READY_TO_CREATE -> createHeads();
            case POST_CREATE_HEAD -> updateAsAnchor();
        }
    }

    private void updatePatrol(AbstractPlayableSprite player) {
        AbstractPlayableSprite target = getClosestPlayerByHorizontalDistance();
        // Obj94_WaitForPlayer calls Obj94_CreateHead immediately after the range check
        // succeeds; it does not defer head creation to a later frame (docs/s2disasm/s2.asm:73716-73725).
        if (checkPlayerInRange(target)) {
            createHeads(target);
            return;
        }

        // Patrol movement
        patrolTimer--;
        if (patrolTimer < 0) {
            // Reverse direction
            xVelocity = -xVelocity;
            xFlipFlag = !xFlipFlag;
            facingLeft = !facingLeft;
            patrolTimer = PATROL_TIMER;
        }

        // Apply velocity (8.8 fixed point)
        motionState.x = currentX;
        motionState.xVel = xVelocity;
        SubpixelMotion.moveX(motionState);
        currentX = motionState.x;
    }

    /**
     * Check if player is within detection range using Obj_GetOrientationToPlayer's
     * horizontal distance result. ROM Obj94_WaitForPlayer (docs/s2disasm/s2.asm:73716-73722):
     *   bsr.w  Obj_GetOrientationToPlayer
     *   addi.w #$60,d2          ; d2 = (obj.x - player.x) + 0x60 (signed-word arithmetic)
     *   cmpi.w #$100,d2
     *   bhs.s  loc_37362        ; out of range when (unsigned word) >= 0x100
     *   bsr.w  Obj94_CreateHead ; otherwise attack
     *
     * The unsigned-word comparison after `addi.w #$60` makes the window
     * ASYMMETRIC around the body: signed `obj.x - player.x` must lie in
     * [-0x60, +0xA0). A symmetric `Math.abs(...) + 0x60 < 0x100` window
     * matches ROM only on the right side; on the left it widens to
     * (-0xA0, -0x60) where ROM still says "out of range", firing detection
     * up to ~64 px earlier and causing the body to stop several pixels
     * right of ROM (P12-class divergence).
     */
    private boolean checkPlayerInRange(AbstractPlayableSprite target) {
        if (target == null) {
            return false;
        }
        // Emulate ROM 16-bit signed-then-unsigned semantics literally.
        int signedDelta = (short) (currentX - target.getCentreX());
        int windowed = (signedDelta + DETECT_ANGLE_OFFSET) & 0xFFFF;
        return windowed < DETECT_ANGLE_RANGE;
    }

    private void createHeads() {
        createHeads(getClosestPlayerByHorizontalDistance());
    }

    private void createHeads(AbstractPlayableSprite target) {
        // Obj94_CreateHead uses d0 from Obj_GetOrientationToPlayer, which selects
        // the horizontally closest character before setting x_flip (docs/s2disasm/s2.asm:72295-72321, 74019-74025).
        if (target != null) {
            lastTargetX = target.getCentreX();
            lastTargetDistance = Math.abs(currentX - lastTargetX);
            xFlipFlag = target.getCentreX() > currentX;
            facingLeft = !xFlipFlag;
        }

        // Spawn 5 head segments with indices 0, 2, 4, 6, 8
        for (int i = 0; i < 5; i++) {
            int headIndex = i * 2;  // 0, 2, 4, 6, 8
            RexonHeadObjectInstance head = spawnChild(() -> new RexonHeadObjectInstance(
                    spawn,
                    this,
                    currentX,
                    currentY,
                    headIndex,
                    xFlipFlag
            ));
            heads.add(head);
        }

        // Set up head chain linking (s2.asm:73786-73795)
        // In original: each head's objoff_30 points to the NEXT head toward the tip
        //
        // Body creates heads in order: index 0, 2, 4, 6, 8
        // Body stores addresses at: objoff_2C+0, +2, +4, +6, +8
        // Each head reads from body[0x2E + headIndex] to get its link:
        //   - Head 0 (index 0): reads body[0x2E] = head 1's address
        //   - Head 1 (index 2): reads body[0x30] = head 2's address
        //   - Head 2 (index 4): reads body[0x32] = head 3's address
        //   - Head 3 (index 6): reads body[0x34] = head 4's address
        //   - Head 4 (index 8): no link (skipped in init due to cmpi.w #8,d0)
        //
        // During oscillation, each head moves its LINKED head:
        // - Head 0 (anchor, not moved by anyone) moves head 1
        // - Head 1 (moved by head 0) moves head 2
        // - Head 2 (moved by head 1) moves head 3
        // - Head 3 (moved by head 2) moves head 4
        // - Head 4 (tip, moved by head 3) has no link
        //
        // The oscillation ripples OUTWARD from body to tip.
        // Head 0 stays at its base position (the anchor point).
        for (int i = 0; i < heads.size() - 1; i++) {
            heads.get(i).setLinkedHead(heads.get(i + 1));
        }

        state = State.POST_CREATE_HEAD;
    }

    private void updateAsAnchor() {
        // Body stays still, acts as platform for heads
        // Check if all heads are destroyed
        boolean allDestroyed = true;
        for (RexonHeadObjectInstance head : heads) {
            if (!head.isDestroyed()) {
                allDestroyed = false;
                break;
            }
        }

        // If all heads destroyed, body can be destroyed too
        // (In original, body stays but this keeps behavior simple)
    }

    private AbstractPlayableSprite getClosestPlayerByHorizontalDistance() {
        var spriteManager = services().spriteManager();
        if (spriteManager == null) {
            return null;
        }
        AbstractPlayableSprite closest = null;
        int closestDistance = Integer.MAX_VALUE;
        for (Sprite sprite : spriteManager.getAllSprites()) {
            if (sprite instanceof AbstractPlayableSprite) {
                AbstractPlayableSprite candidate = (AbstractPlayableSprite) sprite;
                int distance = Math.abs(currentX - candidate.getCentreX());
                if (distance < closestDistance) {
                    closest = candidate;
                    closestDistance = distance;
                }
            }
        }
        return closest;
    }

    /**
     * Called by head segments when they are destroyed.
     * Triggers "death drop" for remaining heads.
     */
    public void onHeadDestroyed(RexonHeadObjectInstance destroyedHead) {
        for (RexonHeadObjectInstance head : heads) {
            if (head != destroyedHead && !head.isDestroyed()) {
                head.triggerDeathDrop();
            }
        }
    }

    void attachHeadForRewind(RexonHeadObjectInstance head) {
        if (head != null && !heads.contains(head)) {
            heads.add(head);
        }
    }

    void relinkHeadsForRewind() {
        heads.removeIf(head -> head == null || head.isDestroyed());
        heads.sort(Comparator.comparingInt(RexonHeadObjectInstance::rewindHeadIndex));
        relinkHeadsInListOrder(heads);
    }

    private static void relinkHeadsInListOrder(List<RexonHeadObjectInstance> heads) {
        for (int i = 0; i < heads.size(); i++) {
            RexonHeadObjectInstance current = heads.get(i);
            if (current == null) {
                continue;
            }
            RexonHeadObjectInstance next = i + 1 < heads.size() ? heads.get(i + 1) : null;
            current.setLinkedHead(next);
        }
    }

    private static final class RewindRelinkingHeadList extends ArrayList<RexonHeadObjectInstance> {
        @Override
        public boolean add(RexonHeadObjectInstance head) {
            boolean added = super.add(head);
            relinkHeadsInListOrder(this);
            return added;
        }

        @Override
        public void add(int index, RexonHeadObjectInstance element) {
            super.add(index, element);
            relinkHeadsInListOrder(this);
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Body uses frame 2 (s2.asm:73691: move.b #2,mapping_frame(a0))
        animFrame = 2;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        return SolidObjectParams.of(SOLID_HALF_WIDTH, SOLID_AIR_HALF_HEIGHT, SOLID_GROUND_HALF_HEIGHT);
    }

    @Override
    public void onSolidContact(PlayableEntity playerEntity, SolidContact contact, int frameCounter) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Standard solid collision - no special behavior needed
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.REXON);
        if (renderer == null) return;

        // Body uses frame 2 (s2.asm:73691)
        renderer.drawFrameIndex(2, currentX, currentY, xFlipFlag, false);
    }

    @Override
    public String traceDebugDetails() {
        return String.format("state=%s xflip=%d xv=%04X timer=%02X heads=%d target=%04X dist=%04X",
                state,
                xFlipFlag ? 1 : 0,
                xVelocity & 0xFFFF,
                patrolTimer & 0xFF,
                heads.size(),
                lastTargetX & 0xFFFF,
                lastTargetDistance & 0xFFFF);
    }
}
