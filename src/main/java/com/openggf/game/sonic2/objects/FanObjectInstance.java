package com.openggf.game.sonic2.objects;

import com.openggf.game.OscillationManager;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.objects.ObjectPlayerParticipationPolicy;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.ArrayList;

/**
 * OOZ Fan (Object 0x3F).
 * Wind-blowing object that pushes the player horizontally or vertically.
 * The vertical variant causes the player to tumble in the air.
 * <p>
 * Based on Obj3F from the Sonic 2 disassembly (s2.asm lines 57155-57381).
 * <p>
 * Subtype encoding:
 * - Bit 7 (0x80): Vertical fan (upward blowing) if set, horizontal if clear
 * - Bit 0 (0x01): Reverse direction (affects mapping frame offset)
 * <p>
 * Render flag bit 0 controls X-flip for horizontal fans.
 */
public class FanObjectInstance extends AbstractObjectInstance implements RewindRecreatable {

    // Timer durations (in frames)
    private static final int ACTIVE_DURATION = 0xB4;  // 180 frames
    private static final int IDLE_DURATION = 0x78;    // 120 frames

    // Number of mapping frames per variant
    private static final int MAPPING_FRAME_COUNT = 11;

    private boolean isVertical;
    private boolean reverseDirection;
    private boolean alwaysOn;
    private boolean xFlipped;

    // Timer state machine
    // NOTE: 'spinUp' maps to objoff_32 != 0 in the disassembly.
    // ROM Obj3F_Vertical/Horizontal (s2.asm:57606-57615, 57704-57713) reloads
    // objoff_30 = #$78 by default, then BCHG #0,objoff_32 (which sets Z from the
    // ORIGINAL bit) and BEQ skips the #$B4 override when the new objoff_32 bit0 == 1.
    // Result: objoff_32 -> 1 (spinUp=true, NO push) keeps #$78 = 120 = IDLE_DURATION;
    // objoff_32 -> 0 (spinUp=false, BLOW/push) overrides to #$B4 = 180 = ACTIVE_DURATION.
    // When spinUp=true (120 frames): fan accelerates animation, no wind push.
    // When spinUp=false (180 frames): fan blows at full speed, pushes player.
    private int timer;
    private boolean spinUp; // objoff_32: true = spinning up, false = blowing

    // Animation state
    private int accumulator;       // objoff_34: ramps 0..0x400 during spin-up
    private int animFrameDuration; // anim_frame_duration
    private int animFrame;         // anim_frame: cycles 0-5
    private int mappingFrame;      // mapping_frame: animFrame + base offset

    public FanObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
        this.isVertical = (spawn.subtype() & 0x80) != 0;
        this.reverseDirection = (spawn.subtype() & 0x01) != 0;
        this.alwaysOn = (spawn.subtype() & 0x02) != 0;
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;

        this.mappingFrame = 0;
        this.animFrame = 0;
        this.timer = 0;
        this.spinUp = false;
        this.accumulator = 0;
        this.animFrameDuration = 0;
    }

    @Override
    public FanObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new FanObjectInstance(ctx.spawn(), getName());
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        // Timer state machine (skipped for always-on fans)
        if (!alwaysOn) {
            timer--;
            if (timer < 0) {
                accumulator = 0;
                spinUp = !spinUp;
                // ROM s2.asm:57710-57713 / 57612-57615: default #$78 (IDLE) kept when
                // objoff_32 toggles to 1 (spinUp, no push); overridden to #$B4 (ACTIVE)
                // when objoff_32 toggles to 0 (blow/push). BCHG sets Z from the original bit.
                timer = spinUp ? IDLE_DURATION : ACTIVE_DURATION;
            }
        }

        if (spinUp) {
            // Spin-up phase (objoff_32 != 0): animation ramps up, no push
            updateSpinUpAnimation();
        } else {
            // Blowing phase (objoff_32 == 0): push players, fast animation
            List<PlayableEntity> participants = services().playerQuery().playersFor(
                    ObjectPlayerParticipationPolicy.MAIN_PLUS_ENGINE_SIDEKICKS_AS_NATIVE_P2_EXTENDED);
            if (player != null && !participants.contains(player)) {
                ArrayList<PlayableEntity> withUpdatePlayer = new ArrayList<>(participants.size() + 1);
                withUpdatePlayer.add(player);
                withUpdatePlayer.addAll(participants);
                participants = withUpdatePlayer;
            }
            for (PlayableEntity participant : participants) {
                AbstractPlayableSprite playable = (AbstractPlayableSprite) participant;
                if (isVertical) {
                    applyVerticalPush(playable);
                } else {
                    applyHorizontalPush(playable);
                }
            }
            updateBlowingAnimation();
        }
    }

    /**
     * Animation during spin-up phase (objoff_32 != 0).
     * Accumulator ramps from 0 to 0x400. High byte reloads anim_frame_duration.
     * Once accumulator reaches max, animation freezes.
     */
    private void updateSpinUpAnimation() {
        animFrameDuration--;
        if (animFrameDuration < 0) {
            if (accumulator >= 0x400) {
                // Max reached: don't advance frame (bhs.s MarkObjGone)
                return;
            }
            accumulator += 0x2A;
            animFrameDuration = (accumulator >> 8) & 0xFF;
            advanceAnimFrame();
        }
    }

    /**
     * Animation during blowing phase (objoff_32 == 0).
     * Duration fixed at 0 (fastest cycling).
     */
    private void updateBlowingAnimation() {
        animFrameDuration--;
        if (animFrameDuration < 0) {
            animFrameDuration = 0;
            advanceAnimFrame();
        }
    }

    private void advanceAnimFrame() {
        animFrame++;
        if (animFrame >= 6) {
            animFrame = 0;
        }
        // mapping_frame = base offset (0 or 5) + anim_frame
        mappingFrame = (reverseDirection ? 5 : 0) + animFrame;
    }

    /**
     * Horizontal fan push subroutine.
     * Based on Obj3F_PushPlayerHoriz from the disassembly.
     */
    private void applyHorizontalPush(AbstractPlayableSprite player) {
        if (player.isHurt() || player.isObjectControlled()) {
            return;
        }

        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // X range check
        int dx = playerX - objX;
        if (!xFlipped) {
            dx = -dx;
        }
        dx += 0x50;
        if (dx < 0 || dx >= 0xF0) {
            return;
        }

        // Y range check
        int dy = playerY + 0x60 - objY;
        if (dy < 0 || dy >= 0x70) {
            return;
        }

        // Push calculation - all operations must be 16-bit to match ROM
        // ROM: subi.w #$50,d0 / bcc.s + / not.w d0 / add.w d0,d0
        dx -= 0x50;
        if (dx < 0) {
            dx = ((~dx & 0xFFFF) << 1) & 0xFFFF; // not.w d0; add.w d0,d0 (16-bit)
        }
        dx = (dx + 0x60) & 0xFFFF; // addi.w #$60,d0 (16-bit)
        // ROM: btst x_flip / bne (skip neg) / neg.w d0 -- negates when NOT flipped
        if (!xFlipped) {
            dx = (-dx) & 0xFFFF; // neg.w d0 (16-bit)
        }
        // ROM: neg.b d0 / asr.w #4,d0 -- negate low byte, preserve high byte, then shift word
        int highByte = dx & 0xFF00;
        int negLow = (-dx) & 0xFF;
        dx = highByte | negLow;
        int push = ((short) (dx & 0xFFFF)) >> 4; // asr.w #4,d0
        // ROM: btst #0,subtype / beq + / neg.w d0
        if (reverseDirection) {
            push = -push;
        }

        // ROM: add.w d0,x_pos(a1) (Obj3F_Horizontal, s2.asm:57698). As with the
        // vertical push, the ROM adds to the x_pos PIXEL word and leaves x_sub
        // untouched; shiftX() preserves the sub-pixel where setCentreX() would
        // zero it.
        player.shiftX(push);
    }

    /**
     * Vertical fan push subroutine.
     * Based on Obj3F_PushPlayerVert from the disassembly.
     */
    private void applyVerticalPush(AbstractPlayableSprite player) {
        if (player.isHurt() || player.isObjectControlled()) {
            return;
        }

        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // X range check
        int dx = playerX - objX + 0x40;
        if (dx < 0 || dx >= 0x80) {
            return;
        }

        // Y range check with oscillation
        int oscillation = (byte) OscillationManager.getByte(0x14); // sign-extend
        int dy = playerY + oscillation + 0x60 - objY;
        if (dy < 0 || dy >= 0x90) {
            return;
        }

        // Push calculation - all operations must be 16-bit to match ROM
        // ROM: subi.w #$60,d1 / bcs.s + / not.w d1 / add.w d1,d1 / + addi.w #$60,d1
        // bcs branches (skips not/double) when dy < 0x60 (result negative)
        // Fall-through (not/double) when dy >= 0x60 (result non-negative)
        dy -= 0x60;
        if (dy >= 0) {
            dy = ((~dy & 0xFFFF) << 1) & 0xFFFF; // not.w d1; add.w d1,d1 (16-bit)
        }
        dy = (dy + 0x60) & 0xFFFF; // addi.w #$60,d1 (16-bit)
        // ROM: neg.w d1 / asr.w #4,d1 / add.w d1,y_pos(a1) (Obj3F_Vertical,
        // s2.asm:57775-57780). The ROM adds the push directly to the y_pos
        // PIXEL word, leaving the y_sub fraction untouched. shiftY() mirrors
        // that (yPixel += push, y_sub preserved); setCentreY() would wipe the
        // sub-pixel to 0 each frame the fan pushes, dropping ~0x9C00 of
        // accumulated y_sub and producing a 1-pixel-Y carry divergence one
        // frame later (OOZ1 trace f756: ROM y_sub 9C00 vs engine 0000).
        int push = ((short) (-dy & 0xFFFF)) >> 4;
        player.shiftY(push);

        // Set player airborne state and tumble
        player.setAir(true);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 1);
        if (player.getFlipAngle() == 0) {
            player.setFlipAngle(1);
            player.setAnimationId(Sonic2AnimationIds.WALK);
            player.setFlipsRemaining(0x7F);
            player.setFlipSpeed(8);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = services().renderManager();
        if (renderManager == null) {
            return;
        }

        String artKey = isVertical ? Sonic2ObjectArtKeys.OOZ_FAN_VERT : Sonic2ObjectArtKeys.OOZ_FAN_HORIZ;
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        boolean vFlip = (spawn.renderFlags() & 0x02) != 0;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), xFlipped, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
