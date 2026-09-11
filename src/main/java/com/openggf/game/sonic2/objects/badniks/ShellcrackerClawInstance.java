package com.openggf.game.sonic2.objects.badniks;

import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.ObjectServices;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Shellcracker's claw piece (ObjA0) - spawned dynamically by the Shellcracker body.
 * 8 pieces are spawned to form the extending arm.
 *
 * Based on disassembly ObjA0 (s2.asm:75106).
 *
 * Piece index (objoff_2E): 0 = first piece (closest to body), 2,4,...14 = successive pieces.
 * The claw extends outward and retracts. Piece 0 signals the parent when done.
 *
 * State machine:
 *   Routine 0 (INIT): Set frame, position adjustment based on piece index
 *   Routine 2 (ACTIVE): Sub-state machine:
 *     Sub 0: Initial delay (staggered by piece index from byte_381A4)
 *     Sub 2: Extend outward (x_vel = +-$400, timed by byte_38222)
 *     Sub 4: Pause at full extension (8 frames)
 *     Sub 6: Retract (reverse velocity), then signal parent and delete
 *   Routine 4 (FALLING): Parent died - fall with gravity and delete after timer
 */
public class ShellcrackerClawInstance extends AbstractObjectInstance
        implements TouchResponseProvider, RewindRecreatable {
    // From ObjA0_SubObjData (s2.asm:75308): collision_flags = $9A
    // = 0x80 (HURT category) | $1A (x_radius=$C → size index $1A).
    // The claw hurts the player every frame it exists (ENEMY/HURT poll rule —
    // no consumed-once "already hit" latch).
    private static final int COLLISION_SIZE_INDEX = 0x1A;
    // Staggered initial delays per piece index (byte_381A4)
    // Index is pieceIndex/2: 0→0, 1→3, 2→5, 3→7, 4→9, 5→11, 6→13, 7→15
    private static final int[] INITIAL_DELAYS = {0, 3, 5, 7, 9, 11, 13, 15};

    // Extension duration per piece (byte_38222)
    // Index is pieceIndex/2: 0→$D, 1→$C, 2→$A, 3→8, 4→6, 5→4, 6→2, 7→0
    private static final int[] EXTENSION_DURATIONS = {0x0D, 0x0C, 0x0A, 8, 6, 4, 2, 0};

    // Extension velocity from disassembly: move.w #-$400,d2
    private static final int EXTEND_VELOCITY = 0x400;

    // Pause duration at full extension: move.b #8,objoff_2A(a0)
    private static final int EXTENSION_PAUSE = 8;

    // Threshold for skipping extension (piece 7+): cmpi.w #$E,d0 / bhs.s
    private static final int MAX_EXTENDING_INDEX = 0x0E;

    // Fall timer for detached claw: move.w #$40,objoff_2A(a0)
    private static final int FALL_TIMER = 0x40;

    // Gravity for falling claw (ObjectMoveAndFall standard): $38 subpixels/frame
    private static final int GRAVITY = 0x38;

    // Position adjustment for non-first pieces: addq.w #6,x_pos / addq.w #6,y_pos
    private static final int NON_FIRST_POS_ADJUST = 6;

    private enum State {
        INITIAL_DELAY,   // Sub 0: wait staggered delay
        EXTENDING,       // Sub 2: move outward
        EXTENDED_PAUSE,  // Sub 4: pause at full extension
        RETRACTING,      // Sub 6: move back
        FALLING          // Routine 4: parent dead, falling
    }
    private final ShellcrackerBadnikInstance parent;
    // Un-final for rewind: GenericFieldCapturer reapplies these captured non-spawn
    // scalars after the parent-relink recreate hook rebuilds the claw with placeholders.
    private int pieceIndex; // 0, 2, 4, 6, 8, 10, 12, 14
    private boolean facingRight;
    private int currentX;
    private int currentY;
    private final SubpixelMotion.State motionState = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);
    private int xVelocity;
    private int yVelocity;
    private State state;
    private int timer;
    private int retractTimer; // objoff_2B - separate timer for retraction
    private int animFrame;
    private boolean initRoutinePending;

    public ShellcrackerClawInstance(ObjectSpawn parentSpawn, ShellcrackerBadnikInstance parent,
                                    int x, int y, int pieceIndex, boolean facingRight) {
        super(new ObjectSpawn(x, y, 0xA0, 0x26, parentSpawn.renderFlags(),
                false, parentSpawn.rawYWord()), "ShellcrackerClaw");
        this.parent = parent;
        this.pieceIndex = pieceIndex;
        this.facingRight = facingRight;
        this.currentX = x;
        this.currentY = y;

        int index = pieceIndex / 2;

        // ROM: move.w objoff_2E(a0),d0 / beq.s loc_38198 → piece 0 keeps frame 5
        // ROM: move.b #4,mapping_frame(a0) → non-zero pieces get frame 4 (small joint)
        // ROM: addq.w #6,x_pos(a0) / addq.w #6,y_pos(a0)
        if (pieceIndex != 0) {
            this.animFrame = 4; // Small joint piece
            this.currentX += NON_FIRST_POS_ADJUST;
            this.currentY += NON_FIRST_POS_ADJUST;
        } else {
            this.animFrame = 5; // Claw arm segment
        }

        // Set initial delay from table
        this.timer = INITIAL_DELAYS[index];
        this.state = State.INITIAL_DELAY;
        this.initRoutinePending = true;
    }

    private ShellcrackerClawInstance() {
        this(new ObjectSpawn(0, 0, 0, 0, 0, false, 0), null, 0, 0, 0, false);
    }

    @Override
    public AbstractObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        ObjectSpawn spawn = ctx.spawn();
        ShellcrackerBadnikInstance parent = Sonic2BadnikChildRewindLinks.nearestShellcracker(ctx);
        return parent != null
                ? new ShellcrackerClawInstance(spawn, parent, spawn.x(), spawn.y(), 0, false)
                : null;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (initRoutinePending) {
            // ROM ObjA0_Init performs setup and ends at JmpTo39_MarkObjGone
            // (s2.asm:75123-75142). Same-frame spawned child slots therefore do
            // not enter loc_381AC active logic until the next ExecuteObjects pass.
            initRoutinePending = false;
            return;
        }
        // ROM: loc_381AC checks parent alive before processing sub-states
        if (state != State.FALLING && !checkParentAlive()) {
            // Parent died - now in FALLING state, process it
        }
        switch (state) {
            case INITIAL_DELAY -> updateInitialDelay();
            case EXTENDING -> updateExtending();
            case EXTENDED_PAUSE -> updateExtendedPause();
            case RETRACTING -> updateRetracting();
            case FALLING -> updateFalling();
        }
        // ROM ObjA0 paths end in JmpTo39_MarkObjGone (off-screen despawn by spawn-X
        // windowing, s2.asm:75138). Dynamic child objects are culled centrally by
        // ObjectManager.Placement off-screen windowing, so no per-object despawn is
        // added here. The claw also self-deletes in every state path (retract done,
        // fall-timer expiry), so it cannot leak even outside the cull window.
    }

    /**
     * Sub 0 (loc_381E0): Count down initial staggered delay.
     * When done, set up extension velocity based on piece index.
     */
    private void updateInitialDelay() {
        timer--;
        if (timer <= 0) {
            int index = pieceIndex / 2;

            // ROM: cmpi.w #$E,d0 / bhs.s loc_3821A → pieces with index >= $E skip extension
            if (pieceIndex >= MAX_EXTENDING_INDEX) {
                // ROM: move.w #$B,objoff_2A(a0) → just wait, no movement
                timer = 0x0B;
                state = State.EXTENDING;
                return;
            }

            // Set extension velocity
            // ROM: move.w #-$400,d2 / btst x_flip / beq.s + / neg.w d2
            xVelocity = facingRight ? EXTEND_VELOCITY : -EXTEND_VELOCITY;

            // Set extension duration from table
            int duration = EXTENSION_DURATIONS[index];
            timer = duration;
            retractTimer = duration; // objoff_2B = same value for retraction

            state = State.EXTENDING;
        }
    }

    /**
     * Sub 2 (loc_3822A): Move outward, decrement timer.
     */
    private void updateExtending() {
        // ObjectMove
        applyVelocity();

        timer--;
        if (timer <= 0) {
            // ROM: move.b #8,objoff_2A(a0)
            timer = EXTENSION_PAUSE;
            state = State.EXTENDED_PAUSE;
        }
    }

    /**
     * Sub 4 (loc_38244): Pause at full extension.
     */
    private void updateExtendedPause() {
        timer--;
        if (timer <= 0) {
            // ROM: neg.w x_vel(a0) → reverse direction
            xVelocity = -xVelocity;
            state = State.RETRACTING;
        }
    }

    /**
     * Sub 6 (loc_38258): Retract back, then delete.
     * Piece 0 signals the parent and deletes.
     * Other pieces just delete.
     */
    private void updateRetracting() {
        // ObjectMove
        applyVelocity();

        retractTimer--;
        if (retractTimer <= 0) {
            // ROM: tst.w objoff_2E(a0) / bne.s loc_3827A → non-zero pieces just delete
            if (pieceIndex == 0) {
                // ROM: move.b #0,mapping_frame(a1) → reset parent frame
                // ROM: st.b objoff_2C(a1) → signal parent that claw is done
                parent.signalClawDone();
            }
            // Delete self
            setDestroyed(true);
        }
    }

    /**
     * Routine 4 (loc_38280): Parent died - fall with gravity and delete.
     */
    private void updateFalling() {
        // ObjectMoveAndFall
        yVelocity += GRAVITY;
        applyVelocity();

        timer--;
        if (timer < 0) {
            setDestroyed(true);
        }
    }

    private void applyVelocity() {
        motionState.x = currentX;
        motionState.y = currentY;
        motionState.xVel = xVelocity;
        motionState.yVel = yVelocity;
        SubpixelMotion.moveSprite2(motionState);
        currentX = motionState.x;
        currentY = motionState.y;
    }

    /**
     * Check if parent is still alive. ROM checks:
     * cmpi.b #ObjID_Shellcracker,id(a1) / bne.s loc_381D0
     * If parent is destroyed, transition to falling state.
     */
    private boolean checkParentAlive() {
        if (parent.isDestroyed()) {
            state = State.FALLING;
            timer = FALL_TIMER;
            return false;
        }
        return true;
    }

    @Override
    public int getCollisionFlags() {
        // HURT category (0x80) + size index ($1A) → $9A (ObjA0_SubObjData, s2.asm:75308)
        return 0x80 | (COLLISION_SIZE_INDEX & 0x3F);
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
        // From ObjA0_SubObjData: priority = 4
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectServices svc = tryServices();
        ObjectRenderManager renderManager = svc != null ? svc.renderManager() : null;
        if (renderManager == null) return;

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.SHELLCRACKER);
        if (renderer == null || !renderer.isReady()) return;

        renderer.drawFrameIndex(animFrame, currentX, currentY, facingRight, false);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        ctx.drawRect(currentX, currentY, 4, 4, 1f, 0.5f, 0f);
        String label = "Claw[" + (pieceIndex / 2) + "] " + state;
        ctx.drawWorldLabel(currentX, currentY, -2, label, DebugColor.ORANGE);
    }
}
