package com.openggf.game.sonic1.objects;

import com.openggf.audio.AudioManager;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Object 0x6D - Flame Thrower (SBZ).
 * <p>
 * A stationary hazard that periodically shoots a column of flame upward.
 * Two visual variants selected by obStatus bit 1 (spawn renderFlags bit 1):
 * <ul>
 *   <li><b>Pipe style</b> (bit 1 clear): Broken pipe flamethrower (anims 0/1, danger frame 0x0A)</li>
 *   <li><b>Valve style</b> (bit 1 set): Valve flamethrower (anims 2/3, danger frame 0x15)</li>
 * </ul>
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Upper nybble * 2 = flaming duration (in frames)</li>
 *   <li>Lower nybble * 0x20 = pause duration (in frames)</li>
 * </ul>
 * <p>
 * The object alternates between flaming and paused states. During flaming,
 * the flame grows upward (animation plays forward); during pause, no flame
 * is visible. Collision (obColType=$A3) is only active when the current
 * animation frame matches the "danger frame" for that variant.
 * <p>
 * <b>Animation sequences</b> (from Ani_Flame):
 * <ul>
 *   <li>Anim 0 (.pipe1): speed 3, {0,1,2,3,4,5,6,7,8,9,$A}, afBack to frame 2</li>
 *   <li>Anim 1 (.pipe2): speed 0, {9,7,5,3,1,0}, afBack to frame 1</li>
 *   <li>Anim 2 (.valve1): speed 3, {$B,$C,$D,$E,$F,$10,$11,$12,$13,$14,$15}, afBack to frame 2</li>
 *   <li>Anim 3 (.valve2): speed 0, {$14,$12,$11,$F,$D,$B}, afBack to frame 1</li>
 * </ul>
 * <p>
 * Reference: docs/s1disasm/_incObj/6D Flamethrower.asm
 */
public class Sonic1FlamethrowerObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /** Display priority: move.b #1,obPriority(a0). */
    private static final int DISPLAY_PRIORITY = 1;

    /**
     * Collision type when flame is at danger frame: move.b #$A3,obColType(a0).
     * $A3 = HURT ($80) | size index $23.
     */
    private static final int COLLISION_FLAGS_ACTIVE = 0xA3;

    /** Danger frame for pipe style: move.b #$A,objoff_36(a0). */
    private static final int DANGER_FRAME_PIPE = 0x0A;

    /** Danger frame for valve style: move.b #$15,objoff_36(a0). */
    private static final int DANGER_FRAME_VALVE = 0x15;

    /**
     * Animation sequences from Ani_Flame.
     * Each entry: [speed, frame0, frame1, ..., afBack_target_index].
     * afBack means loop back to the frame at the given index.
     * We store just the frames; speed and loop target are handled separately.
     */
    private static final int[][] ANIM_FRAMES = {
            // Anim 0 (.pipe1): speed 3, frames, afBack to index 2
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 0x0A},
            // Anim 1 (.pipe2): speed 0, frames, afBack to index 1
            {9, 7, 5, 3, 1, 0},
            // Anim 2 (.valve1): speed 3, frames, afBack to index 2
            {0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15},
            // Anim 3 (.valve2): speed 0, frames, afBack to index 1
            {0x14, 0x12, 0x11, 0x0F, 0x0D, 0x0B},
    };

    /** Animation speeds (first byte of each Ani_Flame entry). */
    private static final int[] ANIM_SPEEDS = {3, 0, 3, 0};

    /**
     * afBack loop targets: when animation reaches end, loop back to this frame index.
     * afBack uses a RELATIVE offset (subtracted from current position), not an absolute index.
     * Anim 0: afBack at pos 11, arg 2 -> 11-2 = index 9
     * Anim 1: afBack at pos 6, arg 1 -> 6-1 = index 5
     * Anim 2: afBack at pos 11, arg 2 -> 11-2 = index 9
     * Anim 3: afBack at pos 6, arg 1 -> 6-1 = index 5
     */
    private static final int[] ANIM_LOOP_TARGETS = {9, 5, 9, 5};

    /** Debug color (orange for flame). */
    private static final DebugColor DEBUG_COLOR = new DebugColor(255, 120, 0);

    // ========================================================================
    // Instance State
    // ========================================================================

    /** Whether this is a valve-style flamethrower (obStatus bit 1). */
    private boolean isValve;

    /** The danger frame index for this variant. */
    private int dangerFrame;

    /** Timer (objoff_30): counts down each frame, triggers state toggle at 0. */
    private int timer;

    /** Flaming duration (objoff_32): stored from subtype upper nybble * 2. */
    private int flamingDuration;

    /** Pause duration (objoff_34): stored from subtype lower nybble * 0x20. */
    private int pauseDuration;

    /** Current animation index (0-3). Toggles bit 0 on state changes. */
    private int animIndex;

    /**
     * Previous animation index, for change detection.
     * Mirrors obPrevAni in the disassembly's AnimateSprite routine.
     * Initialized to -1 so the first call always triggers animation reset.
     */
    private int prevAnimIndex = -1;

    /** Current frame index within the animation sequence (obAniFrame). */
    private int animFrameIndex;

    /** Animation frame timer (obTimeFrame). */
    private int animTimer;

    /** Current display frame from the mapping (obFrame). */
    private int displayFrame;

    public Sonic1FlamethrowerObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Flamethrower");

        // Flame_Main (Routine 0):
        // btst #1,obStatus(a0) / beq.s Flame_Action
        // Bit 1 of obStatus comes from renderFlags bit 1
        this.isValve = (spawn.renderFlags() & 0x02) != 0;

        // move.b #$A,objoff_36(a0) (pipe) or move.b #$15,objoff_36(a0) (valve)
        this.dangerFrame = isValve ? DANGER_FRAME_VALVE : DANGER_FRAME_PIPE;

        // Subtype upper nybble: flaming time
        // andi.w #$F0,d0 / add.w d0,d0
        int subtype = spawn.subtype() & 0xFF;
        int upperNybble = subtype & 0xF0;
        this.flamingDuration = upperNybble * 2;

        // Subtype lower nybble: pause time
        // andi.w #$F,d0 / lsl.w #5,d0
        int lowerNybble = subtype & 0x0F;
        this.pauseDuration = lowerNybble << 5; // * 32

        // move.w d0,objoff_30(a0) / move.w d0,objoff_32(a0)
        // Initial timer = flaming duration
        this.timer = flamingDuration;

        // Initial animation: valve uses anim 2, pipe uses anim 0
        // btst #1,obStatus(a0) / beq.s Flame_Action / move.b #2,obAnim(a0)
        this.animIndex = isValve ? 2 : 0;

        // Animation state: prevAnimIndex=-1 ensures AnimateSprite detects
        // an animation change on the first update, matching disassembly
        // behavior where obPrevAni is uninitialized (0) and obAnim is
        // set to 0 or 2 by Flame_Main.
        this.animFrameIndex = 0;
        this.animTimer = 0;
        this.displayFrame = ANIM_FRAMES[animIndex][0];
    }

    // ========================================================================
    // Update Logic
    // ========================================================================

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (isDestroyed()) {
            return;
        }

        // Flame_Action (Routine 2):
        // subq.w #1,objoff_30(a0)
        timer--;

        // bpl.s loc_E57A (if timer >= 0, skip toggle)
        if (timer < 0) {
            // Timer expired: toggle between flaming and pausing.
            // Disassembly first sets timer = pause_duration, then toggles.
            // If toggle result means we're going BACK to flaming, timer is
            // overwritten with flaming_duration.
            //
            // move.w objoff_34(a0),objoff_30(a0)  ; begin pause time
            timer = pauseDuration;

            // bchg #0,obAnim(a0)  ; toggle animation bit 0
            // beq.s loc_E57A      ; Z flag = old bit 0 value
            int oldBit0 = animIndex & 1;
            animIndex ^= 1;

            if (oldBit0 != 0) {
                // Old bit 0 was 1 → switched from retract to flaming anim.
                // move.w objoff_32(a0),objoff_30(a0)  ; begin flaming time
                timer = flamingDuration;
                // move.w #sfx_Flamethrower,d0 / jsr (QueueSound2).l
                if (isOnScreen()) {
                    services().playSfx(Sonic1Sfx.FLAMETHROWER.id);
                }
            }
            // else: old bit 0 was 0 → switched from flaming to retract.
            // Timer already set to pauseDuration above. No sound.
        }

        // loc_E57A: AnimateSprite
        updateAnimation();
    }

    /**
     * Animates the flamethrower using the S1 AnimateSprite routine.
     * <p>
     * Matches the disassembly behavior precisely:
     * <ol>
     *   <li>If obAnim != obPrevAni (animation changed): reset obAniFrame=0, obTimeFrame=0,
     *       store obPrevAni. This causes immediate frame processing below.</li>
     *   <li>Decrement obTimeFrame. If still >= 0, return (display current frame).</li>
     *   <li>Load speed from animation data into obTimeFrame.</li>
     *   <li>Read frame at current obAniFrame. If it's afBack ($FE), loop back.</li>
     *   <li>Set obFrame = frame value, increment obAniFrame.</li>
     * </ol>
     */
    private void updateAnimation() {
        int[] frames = ANIM_FRAMES[animIndex];

        // AnimateSprite: check for animation change (obAnim vs obPrevAni)
        if (animIndex != prevAnimIndex) {
            prevAnimIndex = animIndex;
            animFrameIndex = 0;
            animTimer = 0; // Will be decremented to -1 below, triggering immediate processing
        }

        // subq.b #1,obTimeFrame(a0)
        animTimer--;

        // bpl.s Anim_Wait
        if (animTimer >= 0) {
            // Timer still running, keep current display frame
            return;
        }

        // Timer expired: load speed, process next frame
        // move.b (a1),obTimeFrame(a0)  ; load frame duration (speed)
        animTimer = ANIM_SPEEDS[animIndex];

        // Check if we've reached the end of the frame array (afBack point)
        if (animFrameIndex >= frames.length) {
            // afBack: loop back to target index
            // sub.b d0,obAniFrame(a0)
            animFrameIndex = ANIM_LOOP_TARGETS[animIndex];
        }

        // move.b 1(a1,d1.w),d0  ; read sprite number from script
        // move.b d0,obFrame(a0)  ; set display frame
        displayFrame = frames[animFrameIndex];

        // addq.b #1,obAniFrame(a0)  ; advance to next frame
        animFrameIndex++;
    }

    // ========================================================================
    // TouchResponseProvider Implementation
    // ========================================================================

    @Override
    public int getCollisionFlags() {
        // move.b #0,obColType(a0)
        // move.b objoff_36(a0),d0
        // cmp.b obFrame(a0),d0
        // bne.s Flame_ChkDel
        // move.b #$A3,obColType(a0)
        if (displayFrame == dangerFrame) {
            return COLLISION_FLAGS_ACTIVE;
        }
        return 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ========================================================================
    // Rendering
    // ========================================================================

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SBZ_FLAMETHROWER);
        if (renderer == null) return;

        // OPL_MakeItem seeds obRender bits 0-1 from placement flags, and Flame_Main
        // preserves them via ori.b #4,obRender(a0). DisplaySprite therefore applies
        // spawn flips to the whole flamethrower mapping.
        boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(displayFrame, getX(), getY(), hFlip, vFlip);
    }

    @Override
    public boolean isHighPriority() {
        // make_art_tile(ArtTile_SBZ_Flamethrower,0,1) - VDP priority bit = 1
        return true;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(DISPLAY_PRIORITY);
    }

    // ========================================================================
    // Persistence
    // ========================================================================

    @Override
    public boolean isPersistent() {
        // Flame_ChkDel: out_of_range.w DeleteObject / bra.w DisplaySprite
        return !isDestroyed() && isOnScreen();
    }

    // ========================================================================
    // Debug Rendering
    // ========================================================================

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int objX = getX();
        int objY = getY();
        ctx.drawRect(objX, objY, 0x0C, 0x20,
                1.0f, 0.47f, 0.0f);
        ctx.drawWorldLabel(objX, objY, -1,
                String.format("Flame[%s] frm=%d tmr=%d anim=%d",
                        isValve ? "valve" : "pipe", displayFrame, timer, animIndex),
                DEBUG_COLOR);
    }
}
