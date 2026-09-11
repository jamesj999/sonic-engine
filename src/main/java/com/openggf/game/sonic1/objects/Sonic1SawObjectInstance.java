package com.openggf.game.sonic1.objects;

import com.openggf.audio.AudioManager;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.OscillationManager;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.level.objects.SubpixelMotion;
import com.openggf.level.objects.TouchResponseProvider;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Sonic 1 Object 0x6A - Ground Saws and Pizza Cutters (SBZ).
 * <p>
 * Subtypes 0-2 are "pizza cutters" (circular blades on a pole):
 * <ul>
 *   <li>Type 0: Stationary pizza cutter</li>
 *   <li>Type 1: Horizontal oscillation (v_oscillate+$E, amplitude $60)</li>
 *   <li>Type 2: Vertical oscillation (v_oscillate+6, amplitude $30 or $80 for flipped)</li>
 * </ul>
 * Subtypes 3-4 are "ground saws" (blade only, no pole):
 * <ul>
 *   <li>Type 3: Hidden until Sonic approaches from the left, then rushes right at $600 speed</li>
 *   <li>Type 4: Hidden until Sonic approaches from the right, then rushes left at $600 speed</li>
 * </ul>
 * <p>
 * Pizza cutters (types 0-2) have collision type $A2 set in init.
 * Ground saws (types 3-4) only get collision when activated.
 * <p>
 * ROM reference: docs/s1disasm/_incObj/6A Saws and Pizza Cutters.asm
 */
public class Sonic1SawObjectInstance extends AbstractObjectInstance
        implements TouchResponseProvider, SpawnRewindRecreatable {

    // ---- Collision ----
    // obColType for active saws: $A2 = HURT($80) | size index $22
    private static final int COLLISION_TYPE_HURT = 0xA2;

    // obActWid from ROM: $20 (32 pixels half-width for display culling)
    private static final int ACT_WIDTH = 0x20;

    // ---- Oscillation offsets ----
    // v_oscillate+$E -> OscillationManager byte offset 12 (oscillator 3)
    private static final int OSC_TYPE01_OFFSET = 12;
    // v_oscillate+6 -> OscillationManager byte offset 4 (oscillator 1)
    private static final int OSC_TYPE02_OFFSET = 4;

    // ---- Type 01 amplitude ----
    // move.w #$60,d1
    private static final int TYPE01_AMPLITUDE = 0x60;

    // ---- Type 02 flip offset ----
    // addi.w #$80,d0 (flipped); note: the move.w #$30,d1 in the ROM is dead code
    // (d1 is overwritten by saw_origY before use)
    private static final int TYPE02_FLIP_ADD = 0x80;

    // ---- Ground saw velocity ----
    // move.w #$600,obVelX(a0) / move.w #-$600,obVelX(a0)
    private static final int GROUND_SAW_VELOCITY = 0x600;

    // ---- Type 03 player proximity checks ----
    // subi.w #$C0,d0 (type 3: player must be $C0 pixels left of saw)
    private static final int TYPE03_TRIGGER_OFFSET = 0xC0;
    // addi.w #$E0,d0 (type 4: player must be $E0 pixels right of saw)
    private static final int TYPE04_TRIGGER_OFFSET = 0xE0;
    // Y proximity: $80 above, $80 below = $100 total window
    private static final int GROUND_SAW_Y_RANGE_ABOVE = 0x80;
    private static final int GROUND_SAW_Y_RANGE_TOTAL = 0x100;

    // ---- Frame animation ----
    // obTimeFrame: 2 frames between frame changes (from ROM: move.b #2,obTimeFrame(a0))
    private static final int FRAME_CHANGE_DELAY = 2;

    // ---- Sound timing ----
    // Type 01: play every 16 frames (andi.w #$F,d0)
    private static final int TYPE01_SOUND_MASK = 0x0F;
    // Type 02: play when oscillation value == $18
    private static final int TYPE02_SOUND_TRIGGER = 0x18;

    /** Debug colors. */
    private static final DebugColor DEBUG_COLOR_ACTIVE = new DebugColor(255, 100, 50);
    private static final DebugColor DEBUG_COLOR_INACTIVE = new DebugColor(180, 180, 100);

    // ---- Instance state ----
    private int sawType;           // subtype & 7 (0-4)
    private boolean xFlipped;      // obStatus bit 0

    // Dynamic position
    private int x;
    private int y;

    // Original spawn position (saw_origX = objoff_3A, saw_origY = objoff_38)
    private int origX;
    private int origY;

    // Ground saw activation flag (saw_here = objoff_3D)
    private boolean sawHere;

    // X velocity (subpixel, 16-bit signed)
    private int velX;

    /** Subpixel accumulators (xSub / ySub) for ROM-accurate 16:8 fixed-point integration. */
    private final SubpixelMotion.State motion = new SubpixelMotion.State(0, 0, 0, 0, 0, 0);

    // Animation state
    private int mappingFrame;     // 0 or 1 for pizza cutters; 2 or 3 for ground saws
    private int frameTimer;       // countdown for frame toggle

    // Collision active flag
    private boolean collisionActive;

    // ROM parity: a not-yet-appeared ground saw (type 3/4, saw_here=0) that fails
    // its player-X proximity test — or that activates this frame — runs
    // `addq.l #4,sp` to POP Saw_Action's return address, SKIPPING the trailing
    // `out_of_range.s .delete` check entirely (docs/s1disasm/_incObj/6A SBZ Saws
    // and Pizza Cutters.asm:127-131,150-152,165-170). So a dormant ground saw
    // waiting off to the right does NOT delete itself while the player is still
    // approaching — only the player-Y failure path (.nosawNNy) falls through to
    // out_of_range. The engine's out_of_range runs in a separate post-execute
    // pass, so model the pop as a one-frame "skip out_of_range" flag set by the
    // X-fail/activation paths and consumed by isPersistent(). Without it the
    // engine deleted a dormant ground saw that ROM kept, freeing its slot early
    // (SBZ2 f3085: ground saw slot 0x45 deleted vs ROM-retained, cascading
    // FindFreeObj into the f6839 bomb-slot error).
    private boolean skipOutOfRangeThisFrame;

    public Sonic1SawObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Saw");

        // From Saw_Main:
        //   move.b obSubtype(a0),d0
        //   andi.w #7,d0
        this.sawType = spawn.subtype() & 7;
        this.xFlipped = (spawn.renderFlags() & 0x1) != 0;

        // Store original position
        this.x = spawn.x();
        this.y = spawn.y();
        this.origX = spawn.x();
        this.origY = spawn.y();

        // From Saw_Main:
        //   cmpi.b #3,obSubtype(a0) ; is object a ground saw?
        //   bhs.s  Saw_Action       ; if yes, branch (skip collision setup)
        //   move.b #$A2,obColType(a0)
        if (sawType < 3) {
            this.collisionActive = true;
        }

        // Pizza cutters use frames 0/1; ground saws use frames 2/3
        // Ground saws start with frame 2 (set to frame 2 when activated, but
        // for types 0-2 they cycle frames 0/1)
        this.mappingFrame = (sawType < 3) ? 0 : 2;
        this.frameTimer = 0;
    }

    @Override
    public int getX() {
        return x;
    }

    @Override
    public int getY() {
        return y;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Reset the ROM `addq.l #4,sp` out_of_range-skip each frame; only the
        // dormant ground-saw X-fail/activation paths re-arm it below.
        skipOutOfRangeThisFrame = false;
        switch (sawType) {
            case 0 -> updateType00();
            case 1 -> updateType01(vIntRunCount);
            case 2 -> updateType02();
            case 3 -> updateType03(player);
            case 4 -> updateType04(player);
            default -> { /* Unknown subtypes: do nothing */ }
        }
    }

    // ---- Subtype handlers ----

    /**
     * Type 0: Stationary pizza cutter - doesn't move, no animation.
     * From disassembly: .type00: rts
     */
    private void updateType00() {
        // No movement, no animation - just rts
    }

    /**
     * Type 1: Horizontal oscillation pizza cutter.
     * Uses v_oscillate+$E (oscillator 3) for horizontal movement.
     * <p>
     * From disassembly:
     * <pre>
     * .type01:
     *     move.w  #$60,d1
     *     moveq   #0,d0
     *     move.b  (v_oscillate+$E).w,d0
     *     btst    #0,obStatus(a0)
     *     beq.s   .noflip01
     *     neg.w   d0
     *     add.w   d1,d0
     * .noflip01:
     *     move.w  saw_origX(a0),d1
     *     sub.w   d0,d1
     *     move.w  d1,obX(a0)
     * </pre>
     */
    private void updateType01(int vIntRunCount) {
        int amplitude = TYPE01_AMPLITUDE;
        int d0 = OscillationManager.getByte(OSC_TYPE01_OFFSET) & 0xFF;

        if (xFlipped) {
            // neg.w d0 / add.w d1,d0
            d0 = (-d0 + amplitude) & 0xFFFF;
        }

        // move.w saw_origX(a0),d1 / sub.w d0,d1 / move.w d1,obX(a0)
        x = (origX - d0) & 0xFFFF;

        // Animate frame toggle
        animateFrames(0);

        // Sound: play every 16 frames when on screen
        // tst.b obRender(a0) / bpl.s .nosound01
        // move.w (v_framecount).w,d0 / andi.w #$F,d0 / bne.s .nosound01
        if (isOnScreen() && (vIntRunCount & TYPE01_SOUND_MASK) == 0) {
            services().playSfx(Sonic1Sfx.SAW.id);
        }
    }

    /**
     * Type 2: Vertical oscillation pizza cutter.
     * Uses v_oscillate+6 (oscillator 1) for vertical movement.
     * <p>
     * From disassembly:
     * <pre>
     * .type02:
     *     move.w  #$30,d1
     *     moveq   #0,d0
     *     move.b  (v_oscillate+6).w,d0
     *     btst    #0,obStatus(a0)
     *     beq.s   .noflip02
     *     neg.w   d0
     *     addi.w  #$80,d0
     * .noflip02:
     *     move.w  saw_origY(a0),d1
     *     sub.w   d0,d1
     *     move.w  d1,obY(a0)
     * </pre>
     */
    private void updateType02() {
        int d0 = OscillationManager.getByte(OSC_TYPE02_OFFSET) & 0xFF;

        if (xFlipped) {
            // neg.w d0 / addi.w #$80,d0
            d0 = (-d0 + TYPE02_FLIP_ADD) & 0xFFFF;
        }

        // move.w saw_origY(a0),d1 / sub.w d0,d1 / move.w d1,obY(a0)
        y = (origY - d0) & 0xFFFF;

        // Animate frame toggle
        animateFrames(0);

        // Sound: play when oscillation byte == $18
        // tst.b obRender(a0) / bpl.s .nosound02
        // move.b (v_oscillate+6).w,d0 / cmpi.b #$18,d0 / bne.s .nosound02
        if (isOnScreen()) {
            int oscCheck = OscillationManager.getByte(OSC_TYPE02_OFFSET) & 0xFF;
            if (oscCheck == TYPE02_SOUND_TRIGGER) {
                services().playSfx(Sonic1Sfx.SAW.id);
            }
        }
    }

    /**
     * Type 3: Ground saw - appears from left when Sonic passes.
     * <p>
     * Activation check: Sonic must be more than $C0 pixels to the right of the
     * saw's X position, AND within a $100-pixel Y window centred $80 below the saw.
     * Once activated, moves right at speed $600.
     * <p>
     * From disassembly:
     * <pre>
     * .type03:
     *     tst.b   saw_here(a0)
     *     bne.s   .here03
     *     move.w  (v_player+obX).w,d0
     *     subi.w  #$C0,d0
     *     bcs.s   .nosaw03x       ; branch if underflow (player too far left)
     *     sub.w   obX(a0),d0
     *     bcs.s   .nosaw03x       ; branch if saw is to the right of (player - $C0)
     *     [Y check]
     *     move.b  #1,saw_here(a0)
     *     move.w  #$600,obVelX(a0)
     *     move.b  #$A2,obColType(a0)
     *     move.b  #2,obFrame(a0)
     *     [play sound]
     * .nosaw03x:
     *     addq.l  #4,sp           ; pop return address (skip out_of_range + DisplaySprite)
     * </pre>
     */
    private void updateType03(AbstractPlayableSprite player) {
        if (!sawHere) {
            int playerX = player.getCentreX();
            int playerY = player.getCentreY();

            // subi.w #$C0,d0 / bcs.s .nosaw03x (.nosaw03x runs addq.l #4,sp)
            int d0 = playerX - TYPE03_TRIGGER_OFFSET;
            if (d0 < 0) {
                skipOutOfRangeThisFrame = true; // .nosaw03x: skip out_of_range
                return; // Player too far left
            }

            // sub.w obX(a0),d0 / bcs.s .nosaw03x
            d0 = d0 - x;
            if (d0 < 0) {
                skipOutOfRangeThisFrame = true; // .nosaw03x: skip out_of_range
                return; // Saw is to the right of trigger point
            }

            // Y proximity check:
            // subi.w #$80,d0 [uses player Y] / cmp.w obY(a0),d0 / bhs.s .nosaw03y
            // addi.w #$100,d0 / cmp.w obY(a0),d0 / blo.s .nosaw03y
            // The .nosaw03y path does NOT pop the return address, so out_of_range
            // STILL runs on a player-Y failure (leave skipOutOfRangeThisFrame false).
            int yCheck = playerY - GROUND_SAW_Y_RANGE_ABOVE;
            if (yCheck >= y) {
                return; // Player too far below (.nosaw03y: out_of_range runs)
            }
            yCheck += GROUND_SAW_Y_RANGE_TOTAL;
            if (yCheck < y) {
                return; // Player too far above (.nosaw03y: out_of_range runs)
            }

            // Activate the ground saw, then fall through to .nosaw03x (addq.l #4,sp).
            sawHere = true;
            velX = GROUND_SAW_VELOCITY;
            collisionActive = true;
            mappingFrame = 2; // move.b #2,obFrame(a0) -> ground saw frame

            services().playSfx(Sonic1Sfx.SAW.id);
            skipOutOfRangeThisFrame = true; // activation falls through to .nosaw03x
        } else {
            // .here03: Apply velocity and animate
            applyVelocity();
            origX = x; // move.w obX(a0),saw_origX(a0)
            animateFrames(2);
        }
    }

    /**
     * Type 4: Ground saw - appears from right when Sonic passes.
     * <p>
     * Activation check: Sonic must be more than $E0 pixels to the left of the
     * saw's X position, AND within a $100-pixel Y window.
     * Once activated, moves left at speed -$600.
     * <p>
     * From disassembly:
     * <pre>
     * .type04:
     *     tst.b   saw_here(a0)
     *     bne.s   .here04
     *     move.w  (v_player+obX).w,d0
     *     addi.w  #$E0,d0
     *     sub.w   obX(a0),d0
     *     bcc.s   .nosaw04x       ; branch if no borrow (player not far enough left)
     *     [Y check]
     *     move.b  #1,saw_here(a0)
     *     move.w  #-$600,obVelX(a0)
     *     move.b  #$A2,obColType(a0)
     *     move.b  #2,obFrame(a0)
     *     [play sound]
     * .nosaw04x:
     *     addq.l  #4,sp           ; pop return address
     * </pre>
     */
    private void updateType04(AbstractPlayableSprite player) {
        if (!sawHere) {
            int playerX = player.getCentreX();
            int playerY = player.getCentreY();

            // addi.w #$E0,d0 / sub.w obX(a0),d0 / bcc.s .nosaw04x (runs addq.l #4,sp)
            int d0 = (playerX + TYPE04_TRIGGER_OFFSET) - x;
            if (d0 >= 0) {
                skipOutOfRangeThisFrame = true; // .nosaw04x: skip out_of_range
                return; // Player not far enough to the left
            }

            // Y proximity check (same as type 3). .nosaw04y does NOT pop the
            // return address, so out_of_range STILL runs on a player-Y failure.
            int yCheck = playerY - GROUND_SAW_Y_RANGE_ABOVE;
            if (yCheck >= y) {
                return; // (.nosaw04y: out_of_range runs)
            }
            yCheck += GROUND_SAW_Y_RANGE_TOTAL;
            if (yCheck < y) {
                return; // (.nosaw04y: out_of_range runs)
            }

            // Activate the ground saw, then fall through to .nosaw04x (addq.l #4,sp).
            sawHere = true;
            velX = -GROUND_SAW_VELOCITY;
            collisionActive = true;
            mappingFrame = 2;

            services().playSfx(Sonic1Sfx.SAW.id);
            skipOutOfRangeThisFrame = true; // activation falls through to .nosaw04x
        } else {
            // .here04: Apply velocity and animate
            applyVelocity();
            origX = x;
            animateFrames(2);
        }
    }

    // ---- Helpers ----

    /**
     * Applies X velocity to position (SpeedToPos equivalent for X only).
     * velocity is in 1/256th pixel units per frame.
     */
    private void applyVelocity() {
        // SpeedToPos: obX += obVelX (16.16 fixed point, but S1 uses 16.8 subpixel)
        // In practice for this object, velX is whole pixels at 8-bit subpixel scale.
        // $600 = 6.0 pixels per frame
        motion.x = x;
        motion.xVel = velX;
        SubpixelMotion.moveX(motion);
        x = motion.x;
    }

    /**
     * Toggles between two consecutive mapping frames on a timer.
     * <p>
     * From disassembly:
     * <pre>
     *     subq.b  #1,obTimeFrame(a0)
     *     bpl.s   .sameframe
     *     move.b  #2,obTimeFrame(a0)
     *     bchg    #0,obFrame(a0)
     * </pre>
     *
     * @param baseFrame the first frame of the pair (0 for pizza cutters, 2 for ground saws)
     */
    private void animateFrames(int baseFrame) {
        frameTimer--;
        if (frameTimer < 0) {
            frameTimer = FRAME_CHANGE_DELAY;
            // bchg #0,obFrame(a0) - toggle bit 0 of frame index
            mappingFrame = baseFrame + ((mappingFrame - baseFrame) ^ 1);
        }
    }

    // ---- Rendering ----

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Ground saws that haven't appeared yet are invisible
        // (types 3/4 skip DisplaySprite via addq.l #4,sp when not activated)
        if (sawType >= 3 && !sawHere) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(ObjectArtKeys.SBZ_SAW);
        if (renderer == null) return;

        // obRender = 4 (use screen coordinates, no flipping applied at render level)
        // The sprite mappings already contain the mirrored quadrants.
        renderer.drawFrameIndex(mappingFrame, x, y, false, false);
    }

    // ---- TouchResponseProvider ----

    /**
     * Returns collision flags.
     * Pizza cutters (types 0-2) always have collision $A2.
     * Ground saws (types 3-4) only have collision once activated.
     */
    @Override
    public int getCollisionFlags() {
        return collisionActive ? COLLISION_TYPE_HURT : 0;
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    // ---- Persistence ----

    @Override
    public boolean isPersistent() {
        // ROM Saw_Action ends with `out_of_range.s .delete, saw_origX(a0)`
        // (docs/s1disasm/_incObj/6A SBZ Saws and Pizza Cutters.asm:39): the saw
        // deletes when its ORIGIN anchor (saw_origX) leaves the camera window.
        // Let the shared counter-based out_of_range unload run (against the
        // saw_origX reference returned by getOutOfRangeReferenceX()); a blanket
        // persistent=true kept off-screen saws alive that ROM had unloaded,
        // shifting later slot occupancy (SBZ2 trace f239: two saws @0298,@02A0
        // sat ~239px left of camera but the engine retained them, pushing rings
        // down two slots).
        //
        // EXCEPT: a dormant ground saw that ran `addq.l #4,sp` this frame
        // (X-fail or activation) skipped its out_of_range check entirely, so it
        // must NOT unload this frame even if saw_origX is off-window. The flag is
        // set during update() and consumed here in the post-execute unload pass.
        return skipOutOfRangeThisFrame;
    }

    /**
     * ROM out_of_range tests {@code saw_origX} (objoff_3A), not the live
     * {@code obX} — {@code out_of_range.s .delete, saw_origX(a0)}
     * (docs/s1disasm/_incObj/6A SBZ Saws and Pizza Cutters.asm:39). Ground saws
     * update {@code saw_origX} to the moved position each frame (lines 338/400
     * = ROM {@code move.w obX(a0),saw_origX(a0)}), while oscillating pizza
     * cutters keep it at the spawn origin, so {@code origX} is the correct
     * reference for both.
     */
    @Override
    public int getOutOfRangeReferenceX() {
        return origX;
    }

    /**
     * ROM checks {@code out_of_range} at the END of {@code Saw_Action}
     * (line 39), AFTER the type subroutine has run (and, for ground saws,
     * updated {@code saw_origX}). Run the unload post-execute so the check sees
     * the same anchor ROM does.
     */
    @Override
    public boolean checksOutOfRangeAfterRoutine() {
        return true;
    }

    // ---- Debug Rendering ----

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        DebugColor color = collisionActive ? DEBUG_COLOR_ACTIVE : DEBUG_COLOR_INACTIVE;
        boolean isGroundSaw = sawType >= 3;
        float r = color.getRed() / 255f;
        float g = color.getGreen() / 255f;
        float b = color.getBlue() / 255f;
        ctx.drawRect(x, y, ACT_WIDTH, ACT_WIDTH, r, g, b);
        ctx.drawWorldLabel(x, y, -1,
                String.format("Saw t%d frm=%d %s%s",
                        sawType, mappingFrame,
                        isGroundSaw ? (sawHere ? "ACTIVE" : "HIDDEN") : "PIZZA",
                        xFlipped ? " FLIP" : ""),
                color);
    }
}
