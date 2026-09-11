package com.openggf.game.sonic2.objects;
import com.openggf.game.PlayableEntity;
import com.openggf.level.objects.ObjectAnimationState;

import com.openggf.audio.GameSound;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic2.constants.Sonic2AnimationIds;
import com.openggf.game.sonic2.Sonic2ObjectArtKeys;
import com.openggf.graphics.GLCommand;
import com.openggf.graphics.RenderPriority;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.RewindRecreateContext;
import com.openggf.level.objects.RewindRecreatable;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.sprites.animation.SpriteAnimationEndAction;
import com.openggf.sprites.animation.SpriteAnimationScript;
import com.openggf.sprites.animation.SpriteAnimationSet;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.playable.ObjectControlState;

import com.openggf.debug.DebugColor;
import java.util.List;
import java.util.logging.Logger;

/**
 * MTZ Spin Tube (Object 0x67).
 * Tube transport system in Metropolis Zone that captures the player,
 * oscillates them vertically with a sinusoidal motion, then launches
 * them through a waypoint path.
 * <p>
 * Handles both Sonic and Tails independently via separate per-character state.
 * <p>
 * Subtype interpretation:
 * <ul>
 *   <li>Bit 7: path direction (0=forward, 1=reverse)</li>
 *   <li>Bits 3:0: path index into off_273F2 (0-12)</li>
 *   <li>Bit 4: if set, preserve player velocity on exit</li>
 * </ul>
 * <p>
 * Render flags bit 0 (x_flip): shifts the entry collision zone right by 0xA pixels.
 * <p>
 * Disassembly reference: s2.asm lines 52943-53215 (Obj67), misc/obj67.asm (path data)
 */
public class MTZSpinTubeObjectInstance extends AbstractObjectInstance implements RewindRecreatable {
    private static final Logger LOGGER = Logger.getLogger(MTZSpinTubeObjectInstance.class.getName());

    // Path traversal speed (0x1000 in ROM at loc_27368/loc_27374)
    private static final int PATH_SPEED = 0x1000;

    // Rolling speed set on player capture (move.w #$800,inertia(a1))
    private static final int ROLLING_INERTIA = 0x800;

    // Sine angle increment per frame (addq.b #2,1(a4))
    private static final int SINE_ANGLE_INCREMENT = 2;

    // Sine angle threshold to transition from oscillation to path (cmpi.b #$80,1(a4))
    private static final int SINE_TRANSITION_ANGLE = 0x80;

    // Entry collision X range (cmpi.w #$10,d0)
    private static final int ENTRY_X_RANGE = 0x10;

    // Entry collision X offset (addq.w #3,d0)
    private static final int ENTRY_X_OFFSET = 3;

    // Additional X offset when x_flipped (addi.w #$A,d0)
    private static final int ENTRY_X_FLIP_OFFSET = 0xA;

    // Entry collision Y range (cmpi.w #$40,d1)
    private static final int ENTRY_Y_RANGE = 0x40;

    // Entry collision Y offset (addi.w #$20,d1)
    private static final int ENTRY_Y_OFFSET = 0x20;

    // Path data from misc/obj67.asm (off_273F2)
    // Each path is pairs of (X, Y) absolute coordinates
    private static final int[][] PATHS = {
            // Path 0 (word_2740C): 6 waypoints
            {0x7A8, 0x270, 0x750, 0x270, 0x740, 0x280, 0x740, 0x3E0, 0x750, 0x3F0, 0x7A8, 0x3F0},
            // Path 1 (word_27426): 2 waypoints
            {0xC58, 0x5F0, 0xE28, 0x5F0},
            // Path 2 (word_27430): 6 waypoints
            {0x1828, 0x6B0, 0x17D0, 0x6B0, 0x17C0, 0x6C0, 0x17C0, 0x7E0, 0x17B0, 0x7F0, 0x1758, 0x7F0},
            // Path 3 (word_2744A): 2 waypoints
            {0x5D8, 0x370, 0x780, 0x370},
            // Path 4 (word_27454): 2 waypoints
            {0x5D8, 0x5F0, 0x700, 0x5F0},
            // Path 5 (word_2745E): 6 waypoints
            {0xBD8, 0x1F0, 0xC30, 0x1F0, 0xC40, 0x1E0, 0xC40, 0xC0, 0xC50, 0xB0, 0xCA8, 0xB0},
            // Path 6 (word_27478): 6 waypoints
            {0x1728, 0x330, 0x15D0, 0x330, 0x15C0, 0x320, 0x15C0, 0x240, 0x15D0, 0x230, 0x1628, 0x230},
            // Path 7 (word_27492): 6 waypoints
            {0x6D8, 0x1F0, 0x730, 0x1F0, 0x740, 0x1E0, 0x740, 0x100, 0x750, 0xF0, 0x7A8, 0xF0},
            // Path 8 (word_274AC): 6 waypoints
            {0x7D8, 0x330, 0x828, 0x330, 0x840, 0x340, 0x840, 0x458, 0x828, 0x470, 0x7D8, 0x470},
            // Path 9 (word_274C6): 6 waypoints
            {0xFD8, 0x3B0, 0x1028, 0x3B0, 0x1040, 0x398, 0x1040, 0x2C4, 0x1058, 0x2B0, 0x10A8, 0x2B0},
            // Path 10 (word_274E0): 6 waypoints
            {0xFD8, 0x4B0, 0x1028, 0x4B0, 0x1040, 0x4C0, 0x1040, 0x5D8, 0x1058, 0x5F0, 0x10A8, 0x5F0},
            // Path 11 (word_274FA): 6 waypoints
            {0x2058, 0x430, 0x20A8, 0x430, 0x20C0, 0x418, 0x20C0, 0x2C0, 0x20D0, 0x2B0, 0x2128, 0x2B0},
            // Path 12 (word_27514): 6 waypoints
            {0x2328, 0x5B0, 0x22D0, 0x5B0, 0x22C0, 0x5A0, 0x22C0, 0x4C0, 0x22D0, 0x4B0, 0x2328, 0x4B0}
    };

    // Animation scripts from Ani_obj67 (s2.asm lines 53203-53211)
    private static final SpriteAnimationSet ANIMATIONS;

    static {
        ANIMATIONS = new SpriteAnimationSet();
        // Script 0 (byte_27532): dc.b $1F, 0, $FF - frame 0 forever (invisible)
        ANIMATIONS.addScript(0, new SpriteAnimationScript(
                0x1F, List.of(0), SpriteAnimationEndAction.LOOP, 0));
        // Script 1 (byte_27535): dc.b 1, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, $FE, 2
        // $FE = SWITCH to anim 2 (which is back to script 0 since anim 2 doesn't exist)
        // But in practice the ROM uses this with the anim field directly:
        // move.w #(1<<8)|(0<<0),anim(a0) sets anim=1, anim_frame=0
        // When the flash sequence completes, it switches to anim 2 which resets to 0 (invisible)
        ANIMATIONS.addScript(1, new SpriteAnimationScript(
                1, List.of(1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0),
                SpriteAnimationEndAction.SWITCH, 0));
    }

    // Per-character state holder. ROM Obj67_Main (s2.asm:52978-52994) runs the
    // per-character routine TWICE — once for MainCharacter using state byte
    // objoff_2C, once for Sidekick using state byte objoff_36 — each with the
    // character pointer (a1) of its own player. Each holder mirrors that
    // per-character storage:
    //   state: 0=idle, 2=sine oscillation, 4=path following (the routine selector
    //          at off_271CA, indexed by the (a4) byte)
    //   sineAngle: angle for sinusoidal Y oscillation (0-255, byte; 1(a4))
    //   duration: frames remaining in current path segment (word; 2(a4))
    //   pathRemaining: bytes of path data remaining (word; 4(a4))
    //   pathIndex: current index into path data array
    //   pathReverse: traversing path backwards (mirrors subtype bit 7)
    private static final class CharacterState {
        int state = 0;
        int sineAngle = 0;
        int duration = 0;
        int pathRemaining = 0;
        int pathIndex = 0;
        int[] path = null;
        boolean pathReverse = false;
    }

    // objoff_2C (MainCharacter) and objoff_36 (Sidekick).
    private final CharacterState mainCharState = new CharacterState();
    private final CharacterState sidekickCharState = new CharacterState();

    // Whether object is x-flipped (from render_flags bit 0)
    private boolean xFlipped;

    // Animation state for the flash effect
    private final ObjectAnimationState animationState;

    public MTZSpinTubeObjectInstance(ObjectSpawn spawn) {
        super(spawn, "MTZSpinTube");
        // ROM: btst #status.npc.x_flip,status(a0) - render_flags bit 0
        this.xFlipped = (spawn.renderFlags() & 0x1) != 0;
        this.animationState = new ObjectAnimationState(ANIMATIONS, 0, 0);
    }

    @Override
    public MTZSpinTubeObjectInstance recreateForRewind(RewindRecreateContext ctx) {
        return new MTZSpinTubeObjectInstance(ctx.spawn());
    }

    @Override
    public int getPriorityBucket() {
        // ROM: move.b #5,priority(a0)
        return RenderPriority.clamp(5);
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        // ROM Obj67_Main (s2.asm:52978-52994): run the per-character routine once
        // for MainCharacter (a1 = MainCharacter, a4 = objoff_2C) and once for the
        // Sidekick (a1 = Sidekick, a4 = objoff_36). Resolve both engine players via
        // the player-query API; the participation policy NATIVE_P1_P2 yields the
        // native P1 (main) first and native P2 (sidekick) second, matching the ROM.
        AbstractPlayableSprite mainPlayer = resolveMainCharacter(playerEntity);
        AbstractPlayableSprite sidekickPlayer = resolveSidekick();

        if (mainPlayer != null) {
            updateCharacter(mainCharState, mainPlayer);
        }
        if (sidekickPlayer != null) {
            updateCharacter(sidekickCharState, sidekickPlayer);
        }

        // ROM: move.b objoff_2C(a0),d0 / add.b objoff_36(a0),d0 / beq.w MarkObjGone3
        // (s2.asm:52952-52954). Only animate (and render) when at least one
        // character is actively in the tube.
        if (mainCharState.state != 0 || sidekickCharState.state != 0) {
            animationState.update();
        }
    }

    /**
     * Resolves the native main character (ROM MainCharacter / a1 in the first
     * Obj67_Main pass). Falls back to the {@code playerEntity} supplied by the
     * object update loop when the query layer is unavailable (e.g. test fixtures).
     */
    private AbstractPlayableSprite resolveMainCharacter(PlayableEntity fallback) {
        try {
            PlayableEntity main = services().playerQuery().mainPlayerOrNull();
            if (main instanceof AbstractPlayableSprite sprite) {
                return sprite;
            }
        } catch (RuntimeException e) {
            // Query layer unavailable - fall back below.
        }
        return (fallback instanceof AbstractPlayableSprite sprite) ? sprite : null;
    }

    /**
     * Resolves the native sidekick (ROM Sidekick / a1 in the second Obj67_Main
     * pass), or {@code null} when the active session has no sidekick.
     */
    private AbstractPlayableSprite resolveSidekick() {
        try {
            PlayableEntity p2 = services().playerQuery().nativeP2OrNull();
            if (p2 instanceof AbstractPlayableSprite sprite) {
                return sprite;
            }
        } catch (RuntimeException e) {
            // Query layer unavailable - no sidekick.
        }
        return null;
    }

    private void updateCharacter(CharacterState cs, AbstractPlayableSprite player) {
        switch (cs.state) {
            case 0 -> checkEntry(cs, player);
            case 2 -> updateSineOscillation(cs, player);
            case 4 -> updatePathFollow(cs, player);
        }
    }

    /**
     * State 0: Check if the player enters the tube activation zone.
     * ROM: loc_271D0
     */
    private void checkEntry(CharacterState cs, AbstractPlayableSprite player) {
        // ROM: tst.w (Debug_placement_mode).w / bne.w return
        if (player.isDebugMode()) {
            return;
        }

        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // ROM: move.w x_pos(a1),d0 / sub.w x_pos(a0),d0 / addq.w #3,d0
        int dx = playerX - objX + ENTRY_X_OFFSET;

        // ROM: btst #status.npc.x_flip,status(a0) / beq.s + / addi.w #$A,d0
        if (xFlipped) {
            dx += ENTRY_X_FLIP_OFFSET;
        }

        // ROM: cmpi.w #$10,d0 / bhs.s return
        if (dx < 0 || dx >= ENTRY_X_RANGE) {
            return;
        }

        // ROM: move.w y_pos(a1),d1 / sub.w y_pos(a0),d1 / addi.w #$20,d1
        int dy = playerY - objY + ENTRY_Y_OFFSET;

        // ROM: cmpi.w #$40,d1 / bhs.s return
        if (dy < 0 || dy >= ENTRY_Y_RANGE) {
            return;
        }

        // ROM: tst.b obj_control(a1) / bne.s return
        if (player.isObjectControlled()) {
            return;
        }

        // Capture player
        // ROM: addq.b #2,(a4)
        cs.state = 2;

        // ROM: move.b #$81,obj_control(a1)
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setControlLocked(true);

        // ROM: move.b #AniIDSonAni_Roll,anim(a1). Obj67 does not set the
        // status.player.rolling bit; it only changes the animation.
        player.setAnimationId(Sonic2AnimationIds.ROLL);

        // ROM: move.w #$800,inertia(a1)
        player.setGSpeed((short) ROLLING_INERTIA);

        // ROM: move.w #0,x_vel(a1) / move.w #0,y_vel(a1)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        // ROM: bclr #status.player.pushing,status(a1)
        player.setPushing(false);

        // ROM: bset #status.player.in_air,status(a1)
        player.setAir(true);

        // ROM: move.w x_pos(a0),x_pos(a1) / move.w y_pos(a0),y_pos(a1)
        NativePositionOps.writeXPosPreserveSubpixel(player, objX);
        NativePositionOps.writeYPosPreserveSubpixel(player, objY);

        // ROM: bclr #high_priority_bit,art_tile(a1)
        player.setHighPriority(false);
        player.setPriorityBucket(RenderPriority.MIN);

        // ROM: clr.b 1(a4) - reset sine angle
        cs.sineAngle = 0;

        // ROM: move.w #SndID_Roll,d0 / jsr (PlaySound).l
        playSound(GameSound.ROLLING);

        // ROM: move.w #(1<<8)|(0<<0),anim(a0) - start flash animation
        animationState.setAnimId(1);
        animationState.resetFrameIndex();
    }

    /**
     * State 2: Sinusoidal Y oscillation during tube entry.
     * ROM: loc_27260
     * The player bobs up and down at the tube's X position while descending
     * into the tube, then launches into the path.
     */
    private void updateSineOscillation(CharacterState cs, AbstractPlayableSprite player) {
        // ROM: move.b 1(a4),d0 / addq.b #2,1(a4)
        int angle = cs.sineAngle;
        cs.sineAngle = (cs.sineAngle + SINE_ANGLE_INCREMENT) & 0xFF;

        // ROM: jsr (CalcSine).l - returns sine in d0
        int sineValue = TrigLookupTable.sinHex(angle);

        // ROM: asr.w #5,d0 - divide by 32 (arithmetic shift right 5)
        sineValue >>= 5;

        // ROM: move.w y_pos(a0),d2 / sub.w d0,d2 / move.w d2,y_pos(a1)
        int objY = spawn.y();
        NativePositionOps.writeYPosPreserveSubpixel(player, objY - sineValue);

        // ROM: cmpi.b #$80,1(a4) / bne.s +
        if (cs.sineAngle != SINE_TRANSITION_ANGLE) {
            return;
        }

        // Transition to path mode
        setupPath(cs, player);
        cs.state = 4;

        // ROM: move.w #SndID_SpindashRelease,d0 / jsr (PlaySound).l
        playSound(GameSound.SPINDASH_RELEASE);
    }

    /**
     * Setup path data for traversal.
     * ROM: loc_27310 (forward) / loc_27344 (reverse)
     */
    private void setupPath(CharacterState cs, AbstractPlayableSprite player) {
        int subtype = spawn.subtype();
        boolean reverse = (subtype & 0x80) != 0;

        // ROM: andi.w #$F,d0 - extract path index (low 4 bits)
        int pathIndex;
        if (reverse) {
            // ROM: neg.b d0 / andi.w #$F,d0
            pathIndex = (-subtype) & 0xF;
        } else {
            pathIndex = subtype & 0xF;
        }

        if (pathIndex >= PATHS.length) {
            LOGGER.warning("MTZSpinTube: path index " + pathIndex + " out of range");
            exitTube(cs, player);
            return;
        }

        cs.path = PATHS[pathIndex];
        cs.pathReverse = reverse;

        // The path data size word in ROM counts bytes: each waypoint = 4 bytes (2 words)
        // ROM: move.w (a2)+,d0 then subq.w #4,d0 for the size
        // In our array: pathRemaining = (waypointCount - 1) * 4 bytes
        // This counts down by 4 for each waypoint consumed
        int waypointCount = cs.path.length / 2;
        cs.pathRemaining = (waypointCount - 1) * 4;

        if (reverse) {
            // ROM: lea (a2,d0.w),a2 - jump to end of path data
            // Position player at last waypoint
            int lastIdx = cs.path.length - 2;
            NativePositionOps.writeXPosPreserveSubpixel(player, cs.path[lastIdx]);
            NativePositionOps.writeYPosPreserveSubpixel(player, cs.path[lastIdx + 1]);

            // ROM: subq.w #8,a2 - back up one waypoint
            cs.pathIndex = lastIdx - 2;
        } else {
            // ROM: move.w (a2)+,d4 / move.w d4,x_pos(a1) / move.w (a2)+,d5 / move.w d5,y_pos(a1)
            // Position player at first waypoint
            NativePositionOps.writeXPosPreserveSubpixel(player, cs.path[0]);
            NativePositionOps.writeYPosPreserveSubpixel(player, cs.path[1]);
            cs.pathIndex = 2;
        }

        // Calculate velocity to next waypoint
        int targetX = cs.path[cs.pathIndex];
        int targetY = cs.path[cs.pathIndex + 1];
        calculateVelocity(cs, player, targetX, targetY);
    }

    /**
     * State 4: Following path waypoints.
     * ROM: loc_27294
     */
    private void updatePathFollow(CharacterState cs, AbstractPlayableSprite player) {
        // ROM: subq.b #1,2(a4) / bpl.s Obj67_MoveCharacter
        cs.duration--;
        if (cs.duration >= 0) {
            moveCharacter(player);
            return;
        }

        // Reached waypoint - snap to position
        // ROM: movea.l 6(a4),a2 / move.w (a2)+,d4 / move.w d4,x_pos(a1) / move.w (a2)+,d5 / move.w d5,y_pos(a1)
        int waypointX = cs.path[cs.pathIndex];
        int waypointY = cs.path[cs.pathIndex + 1];
        NativePositionOps.writeXPosPreserveSubpixel(player, waypointX);
        NativePositionOps.writeYPosPreserveSubpixel(player, waypointY);

        // ROM: tst.b subtype(a0) / bpl.s + / subq.w #8,a2
        if (cs.pathReverse) {
            cs.pathIndex -= 2;
        } else {
            cs.pathIndex += 2;
        }

        // ROM: subq.w #4,4(a4) / beq.s loc_272EE
        cs.pathRemaining -= 4;
        if (cs.pathRemaining <= 0) {
            exitTube(cs, player);
            return;
        }

        // Check bounds
        if (cs.pathIndex < 0 || cs.pathIndex + 1 >= cs.path.length) {
            exitTube(cs, player);
            return;
        }

        // Calculate velocity to next waypoint
        int targetX = cs.path[cs.pathIndex];
        int targetY = cs.path[cs.pathIndex + 1];
        calculateVelocity(cs, player, targetX, targetY);
    }

    /**
     * Move character by current velocity.
     * ROM: Obj67_MoveCharacter (loc_272C8)
     * Uses 16.16 fixed point position math with 8.8 velocity.
     */
    private void moveCharacter(AbstractPlayableSprite player) {
        player.move(player.getXSpeed(), player.getYSpeed());
    }

    /**
     * Exit the tube and restore player control.
     * ROM: loc_272EE
     */
    private void exitTube(CharacterState cs, AbstractPlayableSprite player) {
        // ROM: andi.w #$7FF,y_pos(a1)
        int y = player.getCentreY() & 0x7FF;
        NativePositionOps.writeYPosPreserveSubpixel(player, y);

        // ROM: clr.b (a4)
        cs.state = 0;
        cs.path = null;

        // ROM: clr.b obj_control(a1)
        ObjectControlState.none().applyTo(player);
        player.setControlLocked(false);

        // Restore normal render priority
        player.setPriorityBucket(RenderPriority.PLAYER_DEFAULT);

        // ROM: btst #4,subtype(a0) / bne.s + (skip velocity zeroing if bit 4 set)
        if ((spawn.subtype() & 0x10) == 0) {
            // ROM: move.w #0,x_vel(a1) / move.w #0,y_vel(a1)
            player.setXSpeed((short) 0);
            player.setYSpeed((short) 0);
        }

        // Reset animation to invisible only when BOTH characters have left the
        // tube. ROM gates visibility/animation on objoff_2C + objoff_36 at the top
        // of Obj67 (s2.asm:52952-52954); if the other character is still riding,
        // its flash animation must keep running.
        if (mainCharState.state == 0 && sidekickCharState.state == 0) {
            animationState.setAnimId(0);
        }
    }

    /**
     * Calculate velocity to move from current position to target waypoint.
     * ROM: loc_27374 - velocity calculation with speed 0x1000.
     * <p>
     * The ROM algorithm:
     * - d2 = speed along dominant axis, d3 = speed along cross axis (initially both = 0x1000)
     * - If Y distance >= X distance: Y is dominant
     *   - yVel = d3 (signed), duration = abs((dy << 16) / d3)
     *   - xVel = (dx << 16) / duration
     * - If X distance > Y distance: X is dominant
     *   - xVel = d2 (signed), duration = abs((dx << 16) / d2)
     *   - yVel = (dy << 16) / duration
     */
    private void calculateVelocity(CharacterState cs, AbstractPlayableSprite player, int targetX, int targetY) {
        int currentX = player.getCentreX();
        int currentY = player.getCentreY();
        int dx = targetX - currentX;
        int dy = targetY - currentY;
        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);

        int speed = PATH_SPEED;
        int xVel, yVel, duration;

        if (absDy >= absDx) {
            // Y is dominant axis
            // ROM: move.w d3,y_vel(a1) - d3 = speed, negated if dy < 0
            yVel = (dy >= 0) ? speed : -speed;

            // ROM: divs.w d3,d1 → duration = (dy << 16) / yVel
            // Then: divs.w d1,d0 → xVel = (dx << 16) / duration
            if (dy != 0) {
                // Duration is the quotient of (dy << 16) / speed
                duration = (int) (((long) dy << 16) / yVel);
            } else {
                duration = 0;
            }

            if (duration != 0) {
                xVel = (int) (((long) dx << 16) / duration);
            } else {
                xVel = 0;
            }

            // ROM: abs.w d1 / move.w d1,2(a4) then subq.b #1,2(a4) uses HIGH byte
            // The stored word's high byte is the actual frame counter
            cs.duration = (Math.abs(duration) >> 8) & 0xFF;
        } else {
            // X is dominant axis
            // ROM: move.w d2,x_vel(a1) - d2 = speed, negated if dx < 0
            xVel = (dx >= 0) ? speed : -speed;

            // ROM: divs.w d2,d0 → duration = (dx << 16) / xVel
            // Then: divs.w d0,d1 → yVel = (dy << 16) / duration
            if (dx != 0) {
                duration = (int) (((long) dx << 16) / xVel);
            } else {
                duration = 0;
            }

            if (duration != 0) {
                yVel = (int) (((long) dy << 16) / duration);
            } else {
                yVel = 0;
            }

            // ROM: abs.w d0 / move.w d0,2(a4) then subq.b #1,2(a4) uses HIGH byte
            // The stored word's high byte is the actual frame counter
            cs.duration = (Math.abs(duration) >> 8) & 0xFF;
        }

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);
    }

    private void playSound(GameSound sound) {
        try {
            services().playSfx(sound);
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // ROM: only render when a character is in the tube (objoff_2C + objoff_36 != 0)
        if (mainCharState.state == 0 && sidekickCharState.state == 0) {
            return;
        }

        PatternSpriteRenderer renderer = getRenderer(Sonic2ObjectArtKeys.MTZ_SPIN_TUBE_FLASH);
        if (renderer == null) return;

        int frame = animationState.getMappingFrame();
        // Frame 0 is empty (invisible), frame 1 is the flash sprite
        if (frame > 0) {
            renderer.drawFrameIndex(frame, spawn.x(), spawn.y(), false, false);
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int objX = spawn.x();
        int objY = spawn.y();

        // Draw entry collision zone (centered on detection area)
        int cx = objX - ENTRY_X_OFFSET + ENTRY_X_RANGE / 2;
        if (xFlipped) {
            cx -= ENTRY_X_FLIP_OFFSET;
        }
        int cy = objY - ENTRY_Y_OFFSET + ENTRY_Y_RANGE / 2;
        ctx.drawRect(cx, cy, ENTRY_X_RANGE / 2, ENTRY_Y_RANGE / 2, 0.0f, 1.0f, 1.0f);

        // Draw spawn position cross
        ctx.drawCross(objX, objY, 4, 1.0f, 1.0f, 0.0f);

        // Show state info
        int pathIdx = spawn.subtype() & 0xF;
        boolean rev = (spawn.subtype() & 0x80) != 0;
        ctx.drawWorldLabel(objX, objY, -1,
                String.format("67 p%d%s s%d/%d", pathIdx, rev ? "R" : "",
                        mainCharState.state, sidekickCharState.state),
                DebugColor.CYAN);
    }

    @Override
    public boolean isPersistent() {
        // Keep active while controlling either player.
        return mainCharState.state == 2 || mainCharState.state == 4
                || sidekickCharState.state == 2 || sidekickCharState.state == 4;
    }
}
