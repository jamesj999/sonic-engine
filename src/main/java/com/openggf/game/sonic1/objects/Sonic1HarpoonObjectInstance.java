package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Object 0x16 - Harpoon (LZ).
 * <p>
 * An extending/retracting spike trap found in Labyrinth Zone. Comes in two
 * orientations: horizontal (subtype 0) and vertical (subtype 2). The harpoon
 * extends outward for a few frames, pauses for 1 second (60 frames), then
 * retracts, pauses again, and repeats.
 * <p>
 * The collision type changes per frame to match the harpoon's current extent:
 * <ul>
 *   <li>Horizontal frames 0-2: collision sizes $1B/$1C/$1D (short/medium/long horizontal)</li>
 *   <li>Vertical frames 3-5: collision sizes $1E/$1F/$20 (short/medium/long vertical)</li>
 * </ul>
 * All collision types use HURT category ($80).
 * <p>
 * <b>Subtypes:</b>
 * <ul>
 *   <li>0: Horizontal harpoon (animations 0/1: extending/retracting)</li>
 *   <li>2: Vertical harpoon (animations 2/3: extending/retracting)</li>
 * </ul>
 * <p>
 * <b>State machine:</b>
 * <ol>
 *   <li>Routine 0 (Init): Set up art, copy subtype to obAnim, set timer to 60</li>
 *   <li>Routine 2 (Animate): Play current animation (2 frames + afRoutine).
 *       When animation ends (afRoutine), advance to routine 4.</li>
 *   <li>Routine 4 (Wait): Count down timer. When 0, reset timer, go back to
 *       routine 2, and flip bit 0 of obAnim (toggle extend/retract).</li>
 * </ol>
 * <p>
 * Reference: docs/s1disasm/_incObj/16 Harpoon.asm
 */
public class Sonic1HarpoonObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // ---- Constants from disassembly ----

    // Display priority: move.b #4,obPriority(a0)
    private static final int DISPLAY_PRIORITY = 4;

    // Wait timer duration: move.w #60,harp_time(a0)
    private static final int WAIT_TIMER_DURATION = 60;

    // Animation frame duration: dc.b 3 (first byte of each animation sequence)
    private static final int ANIM_FRAME_DURATION = 3;

    // Collision type table per mapping frame (from .types in disassembly)
    // Frame 0: $9B = HURT($80) | size $1B (width 8, height 4)
    // Frame 1: $9C = HURT($80) | size $1C (width $18, height 4)
    // Frame 2: $9D = HURT($80) | size $1D (width $28, height 4)
    // Frame 3: $9E = HURT($80) | size $1E (width 4, height 8)
    // Frame 4: $9F = HURT($80) | size $1F (width 4, height $18)
    // Frame 5: $A0 = HURT($80) | size $20 (width 4, height $28)
    private static final int[] COLLISION_TYPES = {
            0x9B, 0x9C, 0x9D, 0x9E, 0x9F, 0xA0
    };

    // Animation sequences from Ani_Harp:
    // Anim 0 (h_extending):  frames [1, 2], then afRoutine
    // Anim 1 (h_retracting): frames [1, 0], then afRoutine
    // Anim 2 (v_extending):  frames [4, 5], then afRoutine
    // Anim 3 (v_retracting): frames [4, 3], then afRoutine
    private static final int[][] ANIM_SEQUENCES = {
            {1, 2},  // anim 0: h_extending
            {1, 0},  // anim 1: h_retracting
            {4, 5},  // anim 2: v_extending
            {4, 3},  // anim 3: v_retracting
    };

    // ---- Instance state ----

    // Current routine: 2=animate, 4=wait
    private int routine;

    // Current animation index (0-3, from obAnim)
    private int animIndex;

    // Animation playback state
    private int animFrameIndex;      // Index within current ANIM_SEQUENCES entry
    private int animFrameTimer;      // Countdown timer for current frame display

    // Current mapping frame (0-5)
    private int currentFrame;

    // Wait timer (harp_time, counts down from 60)
    private int waitTimer;

    public Sonic1HarpoonObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Harpoon");

        // Routine 0 (Harp_Main): initialization
        // move.b obSubtype(a0),obAnim(a0) - copy subtype to anim index
        int subtype = spawn.subtype() & 0xFF;
        this.animIndex = subtype; // 0 = horizontal, 2 = vertical

        // move.w #60,harp_time(a0) - set timer to 1 second
        this.waitTimer = WAIT_TIMER_DURATION;

        // addq.b #2,obRoutine(a0) - advance to routine 2 (animate)
        this.routine = 2;

        // Harp_Main falls through into Harp_Move on the spawn frame, so the very
        // first AnimateSprite call runs during init. Because obAnim has just been
        // set, AnimateSprite treats the animation as changed and resets
        // obAniFrame=0 / obTimeFrame=0, then Anim_Run immediately decrements the
        // duration below zero, loads frame[0], reloads obTimeFrame to the duration
        // byte (3), and advances obAniFrame to 1. Replicate that post-init state
        // here so the first update() (the spawn-frame fall-through already happened)
        // matches the ROM's second Harp_Move call.
        int safeAnimIndex = animIndex & 0x03;
        this.currentFrame = ANIM_SEQUENCES[safeAnimIndex][0];
        this.animFrameIndex = 1;
        this.animFrameTimer = ANIM_FRAME_DURATION;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        if (routine == 2) {
            // Routine 2 (Harp_Move): animate the harpoon
            updateAnimation();
        } else if (routine == 4) {
            // Routine 4 (Harp_Wait): wait between extend/retract
            updateWait();
        }
    }

    /**
     * Routine 2: Animate the harpoon extension/retraction.
     * <p>
     * Plays through the 2-frame animation sequence at the configured speed.
     * When the sequence completes (afRoutine), advances to routine 4 (wait).
     * The collision type is updated from the .types table based on current frame.
     * <p>
     * From disassembly:
     * <pre>
     *   lea (Ani_Harp).l,a1
     *   bsr.w AnimateSprite
     *   moveq #0,d0
     *   move.b obFrame(a0),d0
     *   move.b .types(pc,d0.w),obColType(a0)
     *   bra.w RememberState
     * </pre>
     */
    private void updateAnimation() {
        int safeAnimIndex = animIndex & 0x03;
        int[] sequence = ANIM_SEQUENCES[safeAnimIndex];

        // AnimateSprite / Anim_Run: subq.b #1,obTimeFrame; bpl.s Anim_Wait
        // Only advance when the duration counter underflows below zero. When it
        // does, reload the duration byte first (move.b (a1),obTimeFrame), then
        // read the next frame, write it (obFrame), and post-increment obAniFrame.
        animFrameTimer--;
        if (animFrameTimer >= 0) {
            // Time remains on the current frame: do nothing (Anim_Wait).
            return;
        }

        // Anim_LoadNextFrame: reload duration regardless of what the next entry is.
        animFrameTimer = ANIM_FRAME_DURATION;

        if (animFrameIndex >= sequence.length) {
            // Next script byte is afRoutine: advance routine, leave obFrame as-is.
            routine = 4;
            return;
        }

        // Load next frame, then advance the script index (post-increment).
        currentFrame = sequence[animFrameIndex];
        animFrameIndex++;
    }

    /**
     * Routine 4: Wait between extending and retracting.
     * <p>
     * From disassembly:
     * <pre>
     *   subq.w #1,harp_time(a0)        ; decrement timer
     *   bpl.s  .chkdel                 ; branch if time remains
     *   move.w #60,harp_time(a0)       ; reset timer
     *   subq.b #2,obRoutine(a0)        ; run "Harp_Move" subroutine
     *   bchg   #0,obAnim(a0)           ; reverse animation
     * </pre>
     */
    private void updateWait() {
        waitTimer--;
        if (waitTimer < 0) {
            // Timer expired: reset timer, go back to animate, flip direction
            waitTimer = WAIT_TIMER_DURATION;
            routine = 2;
            // bchg #0,obAnim(a0): toggle bit 0 to switch between extend/retract
            animIndex ^= 1;
            // The bchg/routine change happens this (Harp_Wait) frame, but Harp_Move
            // does not run until next frame. There, AnimateSprite sees the changed
            // obAnim and resets obAniFrame=0 / obTimeFrame=0, so the first Anim_Run
            // underflows immediately and loads frame[0]. Mirror that by zeroing the
            // duration counter and script index; the current frame stays as the
            // last frame of the previous animation until that next update advances.
            animFrameIndex = 0;
            animFrameTimer = 0;
        }
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.LZ_HARPOON);
        if (renderer == null) return;

        // Render current frame at object position, respecting level layout flip flags.
        // Downward-pointing harpoons have renderFlags bit 1 (Y-flip) set in the level data.
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(currentFrame, getX(), getY(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(DISPLAY_PRIORITY);
    }

    // ---- TouchResponseProvider ----

    /**
     * Returns the collision type for the current frame.
     * All frames are harmful (HURT category $80) with frame-specific size indices.
     */
    @Override
    public int getCollisionFlags() {
        if (currentFrame >= 0 && currentFrame < COLLISION_TYPES.length) {
            return COLLISION_TYPES[currentFrame];
        }
        return 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // RememberState: check if object is on screen, delete if off screen
        return !isDestroyed() && isOnScreen();
    }

    // ---- Debug rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int objX = getX();
        int objY = getY();

        // Draw cross at object position
        float r = 1.0f;
        float g = 0.5f;
        float b = 0.0f;
        ctx.drawLine(objX - 6, objY, objX + 6, objY, r, g, b);
        ctx.drawLine(objX, objY - 6, objX, objY + 6, r, g, b);

        // Draw collision bounds based on current frame
        if (currentFrame >= 0 && currentFrame < COLLISION_TYPES.length) {
            int colType = COLLISION_TYPES[currentFrame] & 0x3F;
            // Width/height from ReactToItem .sizes table (indexed by colType)
            // Indices $1B-$20 used by harpoon:
            // $1B: w=8, h=4    $1C: w=$18, h=4    $1D: w=$28, h=4
            // $1E: w=4, h=8    $1F: w=4, h=$18    $20: w=4, h=$28
            int w = 0;
            int h = 0;
            switch (colType) {
                case 0x1B -> { w = 8;    h = 4; }
                case 0x1C -> { w = 0x18; h = 4; }
                case 0x1D -> { w = 0x28; h = 4; }
                case 0x1E -> { w = 4;    h = 8; }
                case 0x1F -> { w = 4;    h = 0x18; }
                case 0x20 -> { w = 4;    h = 0x28; }
                default -> { return; }
            }
            // Draw collision rectangle
            ctx.drawLine(objX - w, objY - h, objX + w, objY - h, 1.0f, 0.0f, 0.0f);
            ctx.drawLine(objX + w, objY - h, objX + w, objY + h, 1.0f, 0.0f, 0.0f);
            ctx.drawLine(objX + w, objY + h, objX - w, objY + h, 1.0f, 0.0f, 0.0f);
            ctx.drawLine(objX - w, objY + h, objX - w, objY - h, 1.0f, 0.0f, 0.0f);
        }
    }


}
