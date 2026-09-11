package com.openggf.game.sonic1.objects;

import com.openggf.audio.AudioManager;
import com.openggf.configuration.SonicConfiguration;
import com.openggf.debug.DebugRenderContext;
import com.openggf.game.sonic1.audio.Sonic1Sfx;
import com.openggf.game.PlayableEntity;
import com.openggf.game.sonic1.constants.Sonic1AnimationIds;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.sprites.NativePositionOps;
import com.openggf.sprites.playable.ObjectControlState;

import com.openggf.debug.DebugColor;
import java.util.List;

/**
 * Sonic 1 Teleporter (Object ID 0x72) - SBZ teleporter tubes.
 * <p>
 * Invisible objects placed in SBZ2 that transport Sonic through a series of
 * waypoints when he enters the trigger zone. There are 8 subtypes (0-7),
 * each defining a different waypoint path.
 * <p>
 * Behavior:
 * <ol>
 *   <li>Routine 0 (Init): Parse subtype to load waypoint data table</li>
 *   <li>Routine 2 (Wait): Detect player within 16px wide x 64px tall trigger zone.
 *       Subtype 7 requires 50+ rings to activate.</li>
 *   <li>Routine 4 (Rise): Lock player controls, set rolling animation,
 *       oscillate player upward using CalcSine. At peak ($80), calculate
 *       velocity toward next waypoint and play teleport SFX.</li>
 *   <li>Routine 6 (Travel): Move player along velocity vector toward current
 *       waypoint. When countdown expires, advance to next waypoint or
 *       release player with downward velocity ($200) when path is complete.</li>
 * </ol>
 * <p>
 * Reference: docs/s1disasm/_incObj/72 Teleporter.asm
 */
public class Sonic1TeleporterObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    // ---- Trigger detection constants ----
    // From disassembly: cmpi.w #$10,d0 (X range check)
    private static final int TRIGGER_WIDTH = 0x10;
    // From disassembly: addi.w #$20,d1 / cmpi.w #$40,d1 (Y range: -$20 to +$20)
    private static final int TRIGGER_Y_OFFSET = 0x20;
    private static final int TRIGGER_HEIGHT = 0x40;
    // From disassembly: addi.w #$F,d0 (X offset when facing left)
    private static final int TRIGGER_X_FLIP_OFFSET = 0x0F;

    // ---- Player capture constants ----
    // From disassembly: move.b #$81,(f_playerctrl).w
    // Bit 0 = control lock, bit 7 = disable object interaction
    // From disassembly: move.b #id_Roll,obAnim(a1)
    private static final int CAPTURE_ANIMATION = Sonic1AnimationIds.ROLL.id();
    // From disassembly: move.w #$800,obInertia(a1)
    private static final int CAPTURE_INERTIA = 0x800;

    // ---- Rise phase constants ----
    // From disassembly: addq.b #2,objoff_32(a0) (sine angle increment per frame)
    private static final int SINE_ANGLE_INCREMENT = 2;
    // From disassembly: asr.w #5,d0 (shift right 5 = divide by 32)
    private static final int SINE_SHIFT = 5;
    // From disassembly: cmpi.b #$80,objoff_32(a0) (rise completion at 180 degrees)
    private static final int SINE_COMPLETE_ANGLE = 0x80;

    // ---- Travel phase constants ----
    // From disassembly: move.w #$1000,d2 / move.w #$1000,d3 (travel speed)
    private static final int TRAVEL_SPEED = 0x1000;
    // From disassembly: move.w #0,obVelX(a1) / move.w #$200,obVelY(a1) (release velocity)
    private static final int RELEASE_Y_VELOCITY = 0x200;
    // From disassembly: andi.w #$7FF,obY(a1) (Y coordinate mask on release)
    private static final int Y_COORDINATE_MASK = 0x7FF;

    // ---- Subtype 7 ring requirement ----
    // From disassembly: cmpi.w #50,(v_rings).w / blo.s locret_1675C
    private static final int SUBTYPE_7_RING_REQUIREMENT = 50;

    // ---- Waypoint data for all 8 subtypes (from Tele_Data in disassembly) ----
    // Each entry: first word = total byte length of waypoint pairs,
    // then pairs of (X, Y) words.
    // Note: the first word (data length) is stored in objoff_3A as a word,
    // but only the low byte (objoff_3A) tracks the waypoint index and
    // the high byte (objoff_3B) stores the total count for comparison.

    // .type00: dc.w 4, $794, $98C
    private static final int[] TYPE00 = {0x0004, 0x0794, 0x098C};
    // .type01: dc.w 4, $94, $38C
    private static final int[] TYPE01 = {0x0004, 0x0094, 0x038C};
    // .type02: dc.w $1C, $794,$2E8, $7A4,$2C0, $7D0,$2AC, $858,$2AC, $884,$298, $894,$270, $894,$190
    private static final int[] TYPE02 = {0x001C,
            0x0794, 0x02E8, 0x07A4, 0x02C0, 0x07D0, 0x02AC,
            0x0858, 0x02AC, 0x0884, 0x0298, 0x0894, 0x0270,
            0x0894, 0x0190};
    // .type03: dc.w 4, $894, $690
    private static final int[] TYPE03 = {0x0004, 0x0894, 0x0690};
    // .type04: dc.w $1C, $1194,$470, $1184,$498, $1158,$4AC, $FD0,$4AC, $FA4,$4C0, $F94,$4E8, $F94,$590
    private static final int[] TYPE04 = {0x001C,
            0x1194, 0x0470, 0x1184, 0x0498, 0x1158, 0x04AC,
            0x0FD0, 0x04AC, 0x0FA4, 0x04C0, 0x0F94, 0x04E8,
            0x0F94, 0x0590};
    // .type05: dc.w 4, $1294, $490
    private static final int[] TYPE05 = {0x0004, 0x1294, 0x0490};
    // .type06: dc.w $1C, $1594,$FFE8, $1584,$FFC0, $1560,$FFAC, $14D0,$FFAC, $14A4,$FF98, $1494,$FF70, $1494,$FD90
    private static final int[] TYPE06 = {0x001C,
            0x1594, 0xFFE8, 0x1584, 0xFFC0, 0x1560, 0xFFAC,
            0x14D0, 0xFFAC, 0x14A4, 0xFF98, 0x1494, 0xFF70,
            0x1494, 0xFD90};
    // .type07: dc.w 4, $894, $90
    private static final int[] TYPE07 = {0x0004, 0x0894, 0x0090};

    private static final int[][] ALL_TYPES = {TYPE00, TYPE01, TYPE02, TYPE03, TYPE04, TYPE05, TYPE06, TYPE07};

    // ---- Routine state ----
    private enum Routine {
        WAIT,    // Routine 2 (after init)
        RISE,    // Routine 4
        TRAVEL   // Routine 6
    }

    private Routine routine = Routine.WAIT;
    private int subtype;

    // ---- Waypoint tracking (objoff_3A, objoff_3B, objoff_3C, objoff_36, objoff_38) ----
    // waypointIndex corresponds to objoff_3A (byte) - current offset into waypoint data
    // waypointLimit corresponds to objoff_3B (byte) - total length from first word
    private int waypointIndex;
    private int waypointLimit;
    // waypointData corresponds to objoff_3C (longword pointer to data after first word)
    private final int[] waypointData;
    // targetX/targetY correspond to objoff_36/objoff_38 - current waypoint destination
    private int targetX;
    private int targetY;

    // ---- Rise phase state (objoff_32) ----
    private int sineAngle;

    // ---- Travel phase state (objoff_2E) ----
    // travelCountdown corresponds to objoff_2E (byte) - frames until reaching current waypoint.
    // NOTE: The ROM stores the divs result as a word at objoff_2E but decrements only the
    // BYTE at objoff_2E (subq.b #1), so the effective countdown is the HIGH byte of the word.
    private int travelCountdown;
    // Player velocity during travel (set by sub_1681C / calculateTravelVelocity)
    // These are in the ROM's velocity format: 1 unit = 1/256th of a pixel per frame.
    private int playerVelX;
    private int playerVelY;
    private AbstractPlayableSprite controlledPlayer;

    public Sonic1TeleporterObjectInstance(ObjectSpawn spawn) {
        super(spawn, "Teleporter");

        this.subtype = spawn.subtype() & 0xFF;

        // From disassembly Tele_Main:
        // move.b obSubtype(a0),d0
        // add.w d0,d0
        // andi.w #$1E,d0
        // This effectively masks to lower 4 bits then * 2, giving indices 0-14
        // But there are only 8 entries (0-7), so effectively subtype & 0x0F capped at 7
        int typeIndex = subtype & 0x0F;
        if (typeIndex >= ALL_TYPES.length) {
            typeIndex = ALL_TYPES.length - 1;
        }

        int[] typeData = ALL_TYPES[typeIndex];

        // move.w (a2)+,objoff_3A(a0) - stores data length word
        // On 68000 big-endian: objoff_3A = high byte, objoff_3B = low byte
        // Routine 6 reads objoff_3A as waypointIndex and objoff_3B as the limit.
        // For data length $001C: objoff_3A = 0 (initial index), objoff_3B = $1C (limit)
        int dataLength = typeData[0] & 0xFFFF;
        this.waypointIndex = 0;
        this.waypointLimit = dataLength & 0xFF; // objoff_3B = low byte of length word

        // objoff_3C = pointer to start of waypoint pairs (after the first word)
        this.waypointData = new int[typeData.length - 1];
        System.arraycopy(typeData, 1, waypointData, 0, waypointData.length);

        // Load first waypoint: move.w (a2)+,objoff_36(a0) / move.w (a2)+,objoff_38(a0)
        // a2 was advanced past the first word, so first pair is at index 0,1
        this.targetX = signExtend16(waypointData[0]);
        this.targetY = signExtend16(waypointData[1]);
    }

    @Override
    public boolean isPersistent() {
        // While transporting the player, the object must remain active even if
        // the spawn position goes off-screen (player may be far from the object origin).
        return routine != Routine.WAIT;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }

        switch (routine) {
            case WAIT -> updateWait(player);
            case RISE -> updateRise(player);
            case TRAVEL -> updateTravel(player);
        }
    }

    // ---- Routine 2: Wait for player to enter trigger zone ----

    private void updateWait(AbstractPlayableSprite player) {
        // From disassembly loc_166C8:
        // lea (v_player).w,a1
        // move.w obX(a1),d0
        // sub.w obX(a0),d0

        int dx = player.getCentreX() - getX();

        // btst #0,obStatus(a0) / beq.s loc_166E0
        // addi.w #$F,d0
        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        if (hFlip) {
            dx += TRIGGER_X_FLIP_OFFSET;
        }

        // cmpi.w #$10,d0 / bhs.s locret_1675C
        // Unsigned comparison: dx must be in range [0, $10)
        if (dx < 0 || dx >= TRIGGER_WIDTH) {
            return;
        }

        // move.w obY(a1),d1 / sub.w obY(a0),d1
        // addi.w #$20,d1 / cmpi.w #$40,d1 / bhs.s locret_1675C
        int dy = player.getCentreY() - getY() + TRIGGER_Y_OFFSET;
        if (dy < 0 || dy >= TRIGGER_HEIGHT) {
            return;
        }

        // tst.b (f_playerctrl).w / bne.s locret_1675C
        if (player.isObjectControlled()) {
            return;
        }

        // cmpi.b #7,obSubtype(a0) / bne.s loc_1670E
        if (subtype == 7) {
            // cmpi.w #50,(v_rings).w / blo.s locret_1675C
            if (player.getRingCount() < SUBTYPE_7_RING_REQUIREMENT) {
                return;
            }
        }

        // loc_1670E: Capture the player
        capturePlayer(player);
    }

    private void capturePlayer(AbstractPlayableSprite player) {
        // addq.b #2,obRoutine(a0)
        routine = Routine.RISE;

        // move.b #$81,(f_playerctrl).w
        // Bit 0 = control lock, bit 7 = disable object interaction
        ObjectControlState.nativeBit7FullControl().applyTo(player);
        player.setControlLocked(true);

        // move.b #id_Roll,obAnim(a1)
        player.setAnimationId(CAPTURE_ANIMATION);
        player.setForcedAnimationId(CAPTURE_ANIMATION);

        // move.w #$800,obInertia(a1)
        player.setGSpeed((short) CAPTURE_INERTIA);

        // move.w #0,obVelX(a1) / move.w #0,obVelY(a1)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);

        // bclr #5,obStatus(a0) - clear object interaction bit on teleporter
        // bclr #5,obStatus(a1) - clear object interaction bit on player
        player.setOnObject(false);

        // bset #1,obStatus(a1) - set player airborne
        player.setAir(true);

        // ROM SBZ teleporter capture writes `move.w obX(a0),obX(a1)` /
        // `move.w obY(a0),obY(a1)` (docs/s1disasm/_incObj/72 SBZ Teleporter.asm:
        // 73-74): it sets only the player's x_pos/y_pos PIXEL words to the
        // teleporter's pixel position -- the sub-pixel words (x_sub/y_sub) are
        // UNTOUCHED. setCentreX/setCentreY zero the sub-pixel; use the
        // preserve-subpixel setters so the captured player keeps his accumulated
        // fraction, matching the ROM move.w.
        NativePositionOps.writeXPosPreserveSubpixel(player, getX());
        NativePositionOps.writeYPosPreserveSubpixel(player, getY());

        // clr.b objoff_32(a0)
        sineAngle = 0;
        controlledPlayer = player;

        // move.w #sfx_Roll,d0 / jsr (QueueSound2).l
        services().playSfx(Sonic1Sfx.ROLL.id);
    }

    // ---- Routine 4: Rise phase (sine oscillation upward) ----

    private void updateRise(AbstractPlayableSprite player) {
        // From disassembly loc_1675E:
        // move.b objoff_32(a0),d0
        // addq.b #2,objoff_32(a0)
        int currentAngle = sineAngle;
        sineAngle += SINE_ANGLE_INCREMENT;

        // jsr (CalcSine).l
        // asr.w #5,d0
        int sineValue = calcSine(currentAngle);
        int yOffset = sineValue >> SINE_SHIFT;

        // move.w obY(a0),d2
        // sub.w d0,d2
        // move.w d2,obY(a1)
        // ROM writes only the y_pos PIXEL word (move.w) -- the 16-bit y_sub is
        // left untouched, so the player keeps the sub-pixel fraction he carried
        // into the teleporter. Use the preserve-subpixel setter (setCentreY would
        // zero y_sub, dropping the carried fraction and shifting the eventual
        // travel landing by up to 1px -- SBZ2 trace f2920).
        int newY = getY() - yOffset;
        NativePositionOps.writeYPosPreserveSubpixel(player, newY);

        // cmpi.b #$80,objoff_32(a0) / bne.s locret_16796
        if ((sineAngle & 0xFF) != SINE_COMPLETE_ANGLE) {
            return;
        }

        // bsr.w sub_1681C - calculate travel velocity to first waypoint
        calculateTravelVelocity(player);

        // addq.b #2,obRoutine(a0)
        routine = Routine.TRAVEL;

        // move.w #sfx_Teleport,d0 / jsr (QueueSound2).l
        services().playSfx(Sonic1Sfx.TELEPORT.id);
    }

    // ---- Routine 6: Travel phase (move through waypoints) ----

    private void updateTravel(AbstractPlayableSprite player) {
        // From disassembly loc_16798:
        // addq.l #4,sp  -- This pops the return address, meaning the rts from the
        // main Teleport routine is skipped (no out_of_range check during travel).
        // In our engine this is handled by isPersistent() returning true.

        // subq.b #1,objoff_2E(a0) / bpl.s loc_167DA
        travelCountdown--;
        if (travelCountdown >= 0) {
            // loc_167DA: Move player by velocity
            movePlayerByVelocity(player);
            return;
        }

        // Waypoint reached - snap player to target and advance.
        // ROM loc_167C2: move.w objoff_36(a0),obX(a1) / move.w objoff_38(a0),obY(a1)
        // snaps only the x_pos/y_pos PIXEL words, preserving the 16-bit sub-pixels
        // (which Tele_Move then reads as pixel words, but loc_167DA keeps accumulating
        // through the next segment). setCentreX/Y would zero the sub-pixel here.
        NativePositionOps.writeXPosPreserveSubpixel(player, targetX);
        NativePositionOps.writeYPosPreserveSubpixel(player, targetY);

        // moveq #0,d1
        // move.b objoff_3A(a0),d1
        // addq.b #4,d1
        int nextIndex = waypointIndex + 4;

        // cmp.b objoff_3B(a0),d1 / blo.s loc_167C2
        if (nextIndex < waypointLimit) {
            // loc_167C2: Advance to next waypoint
            waypointIndex = nextIndex;

            // movea.l objoff_3C(a0),a2
            // move.w (a2,d1.w),objoff_36(a0)
            // move.w 2(a2,d1.w),objoff_38(a0)
            // d1 is the byte offset into the waypoint data; each word is 2 bytes,
            // so d1/2 gives the array index for the X coordinate
            int arrayIndex = nextIndex / 2;
            targetX = signExtend16(waypointData[arrayIndex]);
            targetY = signExtend16(waypointData[arrayIndex + 1]);

            // bra.w sub_1681C
            calculateTravelVelocity(player);
        } else {
            // moveq #0,d1 / bra.s loc_16800 - path complete, release player
            releasePlayer(player);
        }
    }

    /**
     * Moves the player by velocity each frame during travel using 16.8 fixed-point math.
     * From disassembly loc_167DA:
     * <pre>
     *   move.l obX(a1),d2          ; read 32-bit position (16.8 + junk byte)
     *   move.l obY(a1),d3
     *   move.w obVelX(a1),d0       ; 16-bit velocity
     *   ext.l  d0                  ; sign-extend to 32-bit
     *   asl.l  #8,d0              ; shift left 8 (vel << 8)
     *   add.l  d0,d2              ; pos_32 += vel << 8
     *   ...
     *   move.l d2,obX(a1)         ; store back
     * </pre>
     * The ROM's 32-bit position format: [pixel_hi:pixel_lo:subpixel:junk].
     * Adding vel << 8 effectively adds vel to the 24-bit (16.8) position.
     * vel = $1000 means 16 pixels per frame ($10.00 in 8.8 format).
     */
    private void movePlayerByVelocity(AbstractPlayableSprite player) {
        // ROM loc_167DA performs the standard 16.16 fixed-point position update on the
        // player's FULL position words (pixel:16 | sub:16), reading obVelX/obVelY and
        // adding vel<<8. AbstractSprite.move() implements exactly that 32-bit add, so it
        // preserves and accumulates the player's 16-bit sub-pixel fraction. A separate
        // 16.8 accumulator seeded from the pixel-only position silently dropped the
        // carried sub-pixel and landed the player 1px off (SBZ2 trace f2920).
        player.move((short) playerVelX, (short) playerVelY);
    }

    /**
     * Releases the player at the end of the teleport path.
     * From disassembly loc_16800:
     * <pre>
     *   andi.w #$7FF,obY(a1)
     *   clr.b  obRoutine(a0)
     *   clr.b  (f_playerctrl).w
     *   move.w #0,obVelX(a1)
     *   move.w #$200,obVelY(a1)
     * </pre>
     */
    private void releasePlayer(AbstractPlayableSprite player) {
        // andi.w #$7FF,obY(a1)
        // Masks only the y_pos PIXEL word, preserving the 16-bit y_sub.
        int maskedY = player.getCentreY() & Y_COORDINATE_MASK;
        NativePositionOps.writeYPosPreserveSubpixel(player, maskedY);

        // clr.b obRoutine(a0) - reset to wait state
        routine = Routine.WAIT;
        // Reset waypoint tracking for potential re-use
        waypointIndex = 0;
        targetX = signExtend16(waypointData[0]);
        targetY = signExtend16(waypointData[1]);

        // clr.b (f_playerctrl).w
        ObjectControlState.none().applyTo(player);
        player.setControlLocked(false);
        player.setForcedAnimationId(-1);
        controlledPlayer = null;

        // move.w #0,obVelX(a1) / move.w #$200,obVelY(a1)
        player.setXSpeed((short) 0);
        player.setYSpeed((short) RELEASE_Y_VELOCITY);
    }

    /**
     * Calculates travel velocity from the player's current position to the current
     * target waypoint. Determines which axis has the greater distance and uses that
     * as the primary travel axis at TRAVEL_SPEED, scaling the other axis proportionally.
     * <p>
     * From disassembly sub_1681C:
     * <pre>
     *   Calculates velocity and travel countdown (objoff_2E) to move the player
     *   from current position to (objoff_36, objoff_38) at speed $1000.
     *   Uses the longer axis at $1000 and proportionally scales the shorter axis.
     *   The divs result is stored as a word at objoff_2E. The travel loop uses
     *   subq.b #1,objoff_2E - decrementing only the HIGH byte of the word.
     *   So the effective frame countdown is (divs_result >> 8) & 0xFF.
     * </pre>
     */
    private void calculateTravelVelocity(AbstractPlayableSprite player) {
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // moveq #0,d0
        // move.w #$1000,d2
        // move.w objoff_36(a0),d0
        // sub.w obX(a1),d0
        int dx = signExtend16(targetX - playerX);
        int xSpeed = TRAVEL_SPEED;
        // bge.s loc_16830 / neg.w d0 / neg.w d2
        if (dx < 0) {
            dx = -dx;
            xSpeed = -xSpeed;
        }

        // moveq #0,d1
        // move.w #$1000,d3
        // move.w objoff_38(a0),d1
        // sub.w obY(a1),d1
        int dy = signExtend16(targetY - playerY);
        int ySpeed = TRAVEL_SPEED;
        // bge.s loc_16844 / neg.w d1 / neg.w d3
        if (dy < 0) {
            dy = -dy;
            ySpeed = -ySpeed;
        }

        // loc_16844: cmp.w d0,d1 / blo.s loc_1687A
        if (dy >= dx) {
            // Y distance is greater or equal - use Y as primary axis

            // moveq #0,d1
            // move.w objoff_38(a0),d1
            // sub.w obY(a1),d1
            int dyFull = signExtend16(targetY - playerY);
            // swap d1 / divs.w d3,d1
            int divsResult = 0;
            if (ySpeed != 0) {
                divsResult = (dyFull << 16) / ySpeed;
            }
            int d1_word = signExtend16(divsResult);

            // Calculate proportional X velocity
            // moveq #0,d0 / move.w objoff_36-obX -> d0
            // beq.s loc_16866 (if dxFull == 0, velX = 0)
            // swap d0 / divs.w d1,d0
            int dxFull = signExtend16(targetX - playerX);
            int calcVelX = 0;
            if (dxFull != 0 && d1_word != 0) {
                calcVelX = (dxFull << 16) / d1_word;
                calcVelX = signExtend16(calcVelX);
            }

            // move.w d0,obVelX(a1) / move.w d3,obVelY(a1)
            playerVelX = calcVelX;
            playerVelY = ySpeed;

            // tst.w d1 / bpl.s loc_16874 / neg.w d1
            int countdown = d1_word;
            if (countdown < 0) {
                countdown = -countdown;
            }
            // move.w d1,objoff_2E(a0) - stores word, but subq.b reads high byte
            // The effective countdown is the HIGH byte of the word
            travelCountdown = (countdown >> 8) & 0xFF;
        } else {
            // X distance is greater - use X as primary axis

            // moveq #0,d0 / move.w objoff_36-obX -> d0
            // swap d0 / divs.w d2,d0
            int dxFull = signExtend16(targetX - playerX);
            int divsResult = 0;
            if (xSpeed != 0) {
                divsResult = (dxFull << 16) / xSpeed;
            }
            int d0_word = signExtend16(divsResult);

            // Calculate proportional Y velocity
            int dyFull = signExtend16(targetY - playerY);
            int calcVelY = 0;
            if (dyFull != 0 && d0_word != 0) {
                calcVelY = (dyFull << 16) / d0_word;
                calcVelY = signExtend16(calcVelY);
            }

            // move.w d1,obVelY(a1) / move.w d2,obVelX(a1)
            playerVelY = calcVelY;
            playerVelX = xSpeed;

            // tst.w d0 / bpl.s loc_168A6 / neg.w d0
            int countdown = d0_word;
            if (countdown < 0) {
                countdown = -countdown;
            }
            // move.w d0,objoff_2E(a0)
            travelCountdown = (countdown >> 8) & 0xFF;
        }

        // Set velocities on player for animation/display purposes
        player.setXSpeed((short) playerVelX);
        player.setYSpeed((short) playerVelY);
    }

    /**
     * Mega Drive CalcSine for 256-entry angle table.
     * Returns 8.8 fixed-point sine value (signed 16-bit).
     */
    private static int calcSine(int angle) {
        double radians = (angle & 0xFF) * Math.PI * 2.0 / 256.0;
        int value = (int) (Math.sin(radians) * 256);
        return value & 0xFFFF;
    }

    /**
     * Sign-extends a 16-bit value to a 32-bit int.
     */
    private static int signExtend16(int value) {
        int masked = value & 0xFFFF;
        if ((masked & 0x8000) != 0) {
            return masked | 0xFFFF0000;
        }
        return masked;
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Teleporter is invisible - no visual representation during normal gameplay.
        // The ROM object has no mappings, art, or render code.
    }

    @Override
    public void onUnload() {
        // If the teleporter is unloaded while transporting the player,
        // we should not leave the player locked. However, isPersistent()
        // prevents this during active transport. This is a safety net.
        if (routine != Routine.WAIT) {
            if (controlledPlayer != null) {
                ObjectControlState.none().applyTo(controlledPlayer);
                controlledPlayer.setControlLocked(false);
                controlledPlayer.setForcedAnimationId(-1);
            }
            routine = Routine.WAIT;
            controlledPlayer = null;
        }
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        if (!services().configuration().getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED)) {
            return;
        }

        int x = getX();
        int y = getY();

        // Draw trigger zone
        boolean hFlip = (spawn.renderFlags() & 0x01) != 0;
        int triggerX = hFlip ? x - TRIGGER_X_FLIP_OFFSET : x;
        ctx.drawRect(triggerX + TRIGGER_WIDTH / 2, y, TRIGGER_WIDTH / 2, TRIGGER_Y_OFFSET,
                0.8f, 0.2f, 1.0f);

        // Draw center cross
        ctx.drawCross(x, y, 4, 0.8f, 0.2f, 1.0f);

        // Draw waypoint path
        for (int i = 0; i < waypointData.length - 1; i += 2) {
            int wx = signExtend16(waypointData[i]);
            int wy = signExtend16(waypointData[i + 1]);
            ctx.drawCross(wx, wy, 3, 1.0f, 0.5f, 0.0f);
        }

        // Draw current target
        if (routine != Routine.WAIT) {
            ctx.drawCross(targetX, targetY, 5, 1.0f, 1.0f, 0.0f);
        }

        String stateStr = String.format("Tele t%d r=%s wp=%d/%d",
                subtype, routine, waypointIndex, waypointLimit);
        ctx.drawWorldLabel(x, y, -2, stateStr, DebugColor.MAGENTA);
    }
}
