package com.openggf.game.sonic2.objects.badniks;

import com.openggf.level.objects.AbstractBadnikInstance;

import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;

import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectPlayerQuery;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Crawlton (0x9E) - Snake badnik from MCZ (Mystic Cave Zone).
 * Based on disassembly Obj9E (s2.asm:75213-75385).
 *
 * Behavior:
 * - Hides in wall, detects player within range
 * - Lunges toward player with calculated velocity
 * - Head + 7 trailing body segments
 * - After lunging, pauses, then reverses back
 * - Returns to detection state for next attack
 *
 * The ROM uses a multi-sprite child object with 7 sub-sprites whose positions
 * persist between frames and accumulate velocity offsets during the lunge.
 * We simulate this by tracking persistent segment positions in arrays.
 */
public class CrawltonBadnikInstance extends AbstractBadnikInstance implements RewindRecreatable {

    // ROM Obj9E_Init allocates a second SST slot for the multi-sprite body child
    // (see reserveMultiSpriteChildSlot). Tracked so it is allocated exactly once.
    private boolean childSlotReserved;
    // Collision from Obj9E_SubObjData (s2.asm:75380): collision_flags = $0B
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // Detection range: add $80 to distance, check < $100 (s2.asm:75240-75245)
    private static final int DETECTION_OFFSET = 0x80;
    private static final int DETECTION_RANGE = 0x100;

    // Timer values from disassembly
    private static final int INITIAL_DELAY_FRAMES = 0x10;  // objoff_3A = $10 (s2.asm:75252)
    private static final int LUNGE_DURATION = 0x1C;         // objoff_3A = $1C (s2.asm:75277)
    private static final int REVERSE_PAUSE = 0x20;           // objoff_3A = $20 (s2.asm:75289)

    // Number of trailing body segments (s2.asm:75360: mainspr_childsprites = 7)
    private static final int NUM_BODY_SEGMENTS = 7;

    // Render priority from Obj9E_SubObjData: priority 4
    private static final int RENDER_PRIORITY = 4;

    // Obj_GetOrientationToPlayer compares Sonic and Tails by horizontal distance
    // in native S2 mode (s2.asm:72755-72796).
    private static final ObjectPlayerParticipationPolicy TARGET_PARTICIPATION =
            ObjectPlayerParticipationPolicy.NATIVE_P1_P2;

    // Body segment trailing: d1 starts at $18, each segment threshold decreases by 4
    // Segment trails when parent timer < d1 for that segment (s2.asm:75327-75342)
    private static final int TRAIL_START_THRESHOLD = 0x18;
    private static final int TRAIL_STEP = 4;

    // State machine states matching routine indices (s2.asm:75220-75226)
    private enum State {
        DETECT_PLAYER,  // Routine 2: Check player distance
        INITIAL_DELAY,  // Routine 4: Pre-lunge delay
        MOVING,         // Routine 6: Lunge/reverse movement
        PAUSED          // Routine 8: Post-lunge pause before reverse
    }

    private State state;
    private int timer;           // objoff_3A - multi-purpose timer
    private int nextRoutine;     // objoff_39 - routine to jump to after MOVING completes

    // Head position in 8.8 fixed point for ObjectMove
    private int xPos16;
    private int yPos16;

    // Persistent body segment positions in world coordinates.
    // These are NOT reset each frame - they accumulate velocity offsets during lunge.
    private final int[] segX = new int[NUM_BODY_SEGMENTS];
    private final int[] segY = new int[NUM_BODY_SEGMENTS];

    public CrawltonBadnikInstance(ObjectSpawn spawn) {
        super(spawn, "Crawlton", Sonic2BadnikConfig.DESTRUCTION);
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xPos16 = currentX << 8;
        this.yPos16 = currentY << 8;
        this.state = State.DETECT_PLAYER;
        this.timer = 0;
        this.nextRoutine = 0;

        // Initialize all body segments at head position (s2.asm:75370-75375)
        for (int i = 0; i < NUM_BODY_SEGMENTS; i++) {
            segX[i] = currentX;
            segY[i] = currentY;
        }
    }

    @Override
    public CrawltonBadnikInstance recreateForRewind(RewindRecreateContext ctx) {
        return new CrawltonBadnikInstance(ctx.spawn());
    }


    /**
     * Reserves the SST slot ROM {@code Obj9E_Init} consumes for Crawlton's
     * multi-sprite body child.
     *
     * <p>ROM (docs/s2disasm/s2.asm:75439-75468): {@code Obj9E_Init} falls into
     * {@code loc_37F74}, which calls {@code AllocateObject} and, on success,
     * writes {@code ObjID_Crawlton} into that slot with
     * {@code render_flags.multi_sprite} set, routine ($3B) = $A and seven
     * sub-sprites. The child is display-only: its routine {@code loc_37EFC}
     * only mirrors the parent's position/flip and calls {@code DisplaySprite3}
     * (docs/s2disasm/s2.asm:75399-75437). It deletes itself once its
     * {@code parent} slot no longer holds a Crawlton.
     *
     * <p>This engine instance draws the body segments itself, so only the slot
     * needs modelling -- but it must be modelled, because every later
     * {@code FindFreeObj} allocation in the level lands one slot lower without
     * it, which changes execution order in the ascending {@code ExecuteObjects}
     * walk.
     *
     * <p>{@code AllocateObject} is {@code FindFreeObj} (lowest free dynamic
     * slot), not {@code AllocateObjectAfterCurrent}, so this uses
     * {@code allocateChildSlots}. Allocation happens on the first movement
     * update because ROM runs {@code Obj9E_Init} inside {@code ExecuteObjects},
     * after {@code ObjPosLoad} placed the parent. A full object RAM yields no
     * slot, matching the ROM's {@code bne.s +} allocation-failure branch.
     */
    private void reserveMultiSpriteChildSlot() {
        if (childSlotReserved) {
            return;
        }
        if (spawn == null) {
            return;
        }
        var svc = tryServices();
        if (svc == null || svc.objectManager() == null) {
            return;
        }
        childSlotReserved = true;
        svc.objectManager().allocateChildSlots(spawn, 1);
    }

    @Override
    protected void updateMovement(int vIntRunCount, PlayableEntity playerEntity) {
        reserveMultiSpriteChildSlot();
        AbstractPlayableSprite player = playerEntity instanceof AbstractPlayableSprite playable ? playable : null;
        switch (state) {
            case DETECT_PLAYER -> updateDetection(player);
            case INITIAL_DELAY -> updateInitialDelay();
            case MOVING -> updateMoving();
            case PAUSED -> updatePaused();
        }
        updateBodySegments();
    }

    /**
     * Routine 2 (s2.asm:75236-75266): Detect player within 128px range, calculate lunge velocity.
     */
    private void updateDetection(AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }
        PlayableEntity target = selectTarget(player);
        if (target == null) {
            return;
        }

        // Obj_GetOrientationToPlayer: d2/d3 = obj - closest native player (s2.asm:72755-72796)
        int d2 = currentX - target.getCentreX();
        int d3 = currentY - target.getCentreY();

        // Range check (s2.asm:75240-75245): add $80, check unsigned < $100
        // This is equivalent to checking |distance| < $80 (128 pixels)
        int checkX = (d2 + DETECTION_OFFSET) & 0xFFFF;
        if (checkX >= DETECTION_RANGE) {
            return;
        }
        int checkY = (d3 + DETECTION_OFFSET) & 0xFFFF;
        if (checkY >= DETECTION_RANGE) {
            return;
        }

        // Player in range - transition to initial delay (s2.asm:75250-75266)
        state = State.INITIAL_DELAY;
        timer = INITIAL_DELAY_FRAMES;

        // Set facing (s2.asm:75253-75256): clear x_flip, then set if player is right (d0 != 0).
        // Obj_GetOrientationToPlayer: d0 = 0 when d2 >= 0 (player LEFT), d0 = 2 when d2 < 0 (player RIGHT).
        // Art faces LEFT by default; x_flip mirrors it to face RIGHT.
        // x_flip set → player is RIGHT → art flipped to face right toward player.
        boolean xFlip = (d2 < 0);  // Player is to the right
        facingLeft = !xFlip;

        // Calculate velocity toward player (s2.asm:75258-75265)
        // d4 = d2 (signed distance, overwritten at line 74777)
        // neg.w d4 → d4 = player_x - obj_x (direction toward player)
        // lsl.w #3 → scale by 8
        // andi.w #$FF00 → round to whole pixels in 8.8 fixed point
        // All operations are 16-bit in the ROM.
        int xVel = (short) ((((short)(-d2)) << 3) & 0xFF00);
        int yVel = (short) ((((short)(-d3)) << 3) & 0xFF00);

        xVelocity = xVel;
        yVelocity = yVel;

        // Reset segments to head position for new attack
        for (int i = 0; i < NUM_BODY_SEGMENTS; i++) {
            segX[i] = currentX;
            segY[i] = currentY;
        }
    }

    /**
     * Routine 4 (s2.asm:74808-74817): Wait before lunging.
     * Decrements timer, then transitions to moving state.
     */
    private void updateInitialDelay() {
        timer--;
        if (timer < 0) {
            // Transition to moving (s2.asm:74814-74816)
            state = State.MOVING;
            nextRoutine = 8; // objoff_39 = 8: after lunge, go to PAUSED (routine 8)
            timer = LUNGE_DURATION;
        }
    }

    /**
     * Routine 6 (s2.asm:74820-74829): Move with velocity for timer duration.
     * When timer expires, transition to routine stored in nextRoutine (objoff_39).
     */
    private void updateMoving() {
        timer--;
        if (timer == 0) {
            // Timer expired (s2.asm:74827-74828)
            // objoff_3B = objoff_39: jump to stored routine
            // objoff_3A = $20: set pause timer
            if (nextRoutine == 8) {
                state = State.PAUSED;
            } else {
                // nextRoutine == 2: return to detection
                state = State.DETECT_PLAYER;
            }
            timer = REVERSE_PAUSE;
            return;
        }

        // ObjectMove: apply velocity (8.8 fixed point, s2.asm:74823)
        xPos16 += xVelocity;
        yPos16 += yVelocity;
        currentX = xPos16 >> 8;
        currentY = yPos16 >> 8;
    }

    /**
     * Routine 8 (s2.asm:74832-74843): Pause, then reverse direction.
     * After pause, negates velocity and returns to moving state.
     * The moving state will then transition to detection when done.
     */
    private void updatePaused() {
        timer--;
        if (timer == 0) {
            // Reverse and resume movement (s2.asm:74838-74842)
            state = State.MOVING;
            nextRoutine = 2; // objoff_39 = 2: after reverse, return to detection
            timer = LUNGE_DURATION;
            xVelocity = -xVelocity;
            yVelocity = -yVelocity;
        }
    }

    /**
     * Update body segment positions.
     * Based on routine 0xA logic (s2.asm:74846-74881).
     *
     * The sub-sprite positions persist between frames. During the MOVING state,
     * a velocity offset (velocity >> 8, i.e. integer pixels per frame) is
     * accumulated into each segment's position.
     *
     * Each segment has a different activation threshold: d1 starts at $18 and
     * decreases by 4 for each segment. A segment only starts trailing when the
     * parent's timer drops below its threshold. This creates a staggered
     * "uncoiling" effect where segments closest to the head start moving first.
     *
     * When NOT in MOVING state, segments stay at their current positions.
     */
    private void updateBodySegments() {
        // Only accumulate during MOVING state (s2.asm:74858: cmpi.b #6,objoff_3B(a1))
        if (state != State.MOVING) {
            return;
        }

        // Velocity offset in pixels per frame (s2.asm:74860-74863)
        int velOffsetX = xVelocity >> 8;
        int velOffsetY = yVelocity >> 8;

        // d1 threshold counter (s2.asm:74866): starts at $18, decrements by 4
        int d1 = TRAIL_START_THRESHOLD;

        for (int i = 0; i < NUM_BODY_SEGMENTS; i++) {
            // Segment trails when timer < d1 (s2.asm:74871-74872: cmp.b d1,d0; bhs.s +)
            if (timer < d1) {
                segX[i] += velOffsetX;
                segY[i] += velOffsetY;
            }

            d1 -= TRAIL_STEP;
            if (d1 < 0) {
                break; // subi.b #4,d1; bcs.s (s2.asm:74878-74879)
            }
        }
    }

    @Override
    protected void updateAnimation(int vIntRunCount) {
        // Head uses frame 0 (from SubObjData init via LoadSubObject)
        // Body segments use frame 2 (s2.asm:74912: move.w #2,(a2)+)
        animFrame = 0;
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(RENDER_PRIORITY);
    }

    private PlayableEntity selectTarget(AbstractPlayableSprite fallbackPlayer) {
        ObjectPlayerQuery.NearestPlayerX nearest =
                services().playerQuery().nearestByRomX(TARGET_PARTICIPATION, currentX);
        return nearest.player() != null ? nearest.player() : fallbackPlayer;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.CRAWLTON);
        if (renderer == null) return;

        // x_flip in ROM: default art faces LEFT, x_flip flips to face RIGHT
        // facingLeft = true → no flip (art default), facingLeft = false → flip
        boolean hFlip = !facingLeft;

        // Render body segments first (behind head), using frame 2
        for (int i = NUM_BODY_SEGMENTS - 1; i >= 0; i--) {
            renderer.drawFrameIndex(2, segX[i], segY[i], hFlip, false);
        }

        // Render head on top, using frame 0
        renderer.drawFrameIndex(0, currentX, currentY, hFlip, false);
    }

}
