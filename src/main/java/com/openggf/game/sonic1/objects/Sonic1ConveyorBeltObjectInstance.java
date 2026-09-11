package com.openggf.game.sonic1.objects;

import com.openggf.debug.DebugRenderContext;
import com.openggf.graphics.GLCommand;
import com.openggf.level.objects.AbstractObjectInstance;
import com.openggf.level.objects.ObjectSpawn;
import com.openggf.level.objects.SpawnRewindRecreatable;
import com.openggf.sprites.playable.AbstractPlayableSprite;
import com.openggf.game.PlayableEntity;

import java.util.List;

/**
 * Object 68 - Conveyor belts (SBZ).
 * <p>
 * An invisible zone that pushes Sonic horizontally when he is on the ground
 * within the conveyor's activation area. Used in Scrap Brain Zone (SBZ2).
 * <p>
 * <b>Subtype encoding:</b>
 * <ul>
 *   <li>Upper nibble (bits 7-4): Speed and direction. Treated as a signed nibble
 *       via sign extension (ext.w d1, asr.w #4,d1). Values -8 to +7 pixels/frame.
 *       Positive = push right, negative = push left.</li>
 *   <li>Lower nibble (bits 3-0): Width selection. 0 = 128px half-width,
 *       non-zero = 56px half-width.</li>
 * </ul>
 * <p>
 * <b>Activation check (Conv_Action -> .movesonic):</b>
 * <ol>
 *   <li>Player X must be within conv_width pixels of the object X
 *       (unsigned: 0 <= (playerX - objX + width) < 2*width).</li>
 *   <li>Player Y must be within $30 pixels above the object Y
 *       (signed: 0 <= (playerY - objY + $30) < $30).</li>
 *   <li>Player must not be in the air (btst #1,obStatus(a1) must be clear).</li>
 * </ol>
 * If all conditions are met, conv_speed is added to the player's X position.
 * <p>
 * The object has no visual representation and uses standard out_of_range
 * deletion based on its own X position.
 * <p>
 * Reference: docs/s1disasm/_incObj/68 Conveyor Belt.asm
 */
public class Sonic1ConveyorBeltObjectInstance extends AbstractObjectInstance implements SpawnRewindRecreatable {

    /** Belt speed in pixels/frame, signed. From subtype upper nibble via ext.w/asr.w #4. */
    private int convSpeed;

    /** Half-width of the activation zone in pixels. 128 or 56. */
    private int convWidth;

    public Sonic1ConveyorBeltObjectInstance(ObjectSpawn spawn) {
        super(spawn, "ConveyorBelt");

        // Parse subtype
        int subtype = spawn.subtype() & 0xFF;

        // Lower nibble selects width:
        // andi.b #$F,d1 / beq.s .typeis0
        // If zero: conv_width = 128; else: conv_width = 56
        int lowerNibble = subtype & 0x0F;
        this.convWidth = (lowerNibble == 0) ? 128 : 56;

        // Upper nibble selects speed:
        // andi.b #$F0,d1 / ext.w d1 / asr.w #4,d1
        // The byte value $F0 mask gives us the upper nibble in place (e.g. $D0).
        // ext.w sign-extends the byte to a word: $D0 -> $FFD0
        // asr.w #4 arithmetic shifts right by 4: $FFD0 -> $FFFD = -3
        byte upperByte = (byte) (subtype & 0xF0);
        // ext.w sign-extends byte to word, then asr.w #4 divides by 16
        this.convSpeed = upperByte >> 4;
    }

    @Override
    public void update(int vIntRunCount, PlayableEntity playerEntity) {
        AbstractPlayableSprite player = (AbstractPlayableSprite) playerEntity;
        if (player == null) {
            return;
        }
        moveSonic(player);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Conveyor belt is invisible - no rendering.
    }

    /**
     * Checks if the player is within the conveyor's activation zone and on the
     * ground, then pushes them horizontally.
     * <p>
     * From disassembly .movesonic:
     * <pre>
     *   moveq   #0,d2
     *   move.b  conv_width(a0),d2    ; d2 = half-width
     *   move.w  d2,d3
     *   add.w   d3,d3                ; d3 = full width (2 * half-width)
     *   lea     (v_player).w,a1
     *   move.w  obX(a1),d0
     *   sub.w   obX(a0),d0           ; d0 = playerX - objX
     *   add.w   d2,d0                ; d0 = playerX - objX + half-width
     *   cmp.w   d3,d0                ; unsigned: is d0 < full width?
     *   bhs.s   .notonconveyor       ; no -> skip
     *   move.w  obY(a1),d1
     *   sub.w   obY(a0),d1           ; d1 = playerY - objY
     *   addi.w  #$30,d1              ; d1 = playerY - objY + $30
     *   cmpi.w  #$30,d1              ; unsigned: is d1 < $30?
     *   bhs.s   .notonconveyor       ; no -> skip
     *   btst    #1,obStatus(a1)      ; is player in the air?
     *   bne.s   .notonconveyor       ; yes -> skip
     *   move.w  conv_speed(a0),d0
     *   add.w   d0,obX(a1)           ; push player X
     * </pre>
     */
    private void moveSonic(AbstractPlayableSprite player) {
        int halfW = convWidth;
        int fullW = halfW * 2;

        // ROM uses centre coordinates for player position (obX)
        int playerX = player.getCentreX();
        int objX = getX();

        // Horizontal range check (unsigned comparison)
        int dx = playerX - objX + halfW;
        if (dx < 0 || dx >= fullW) {
            return;
        }

        // Vertical range check
        int playerY = player.getCentreY();
        int objY = getY();
        int dy = playerY - objY + 0x30;
        if (dy < 0 || dy >= 0x30) {
            return;
        }

        // Player must be on the ground (obStatus bit 1 clear)
        if (player.getAir()) {
            return;
        }

        // Push player horizontally.
        // ROM: `add.w conv_speed(a0),obX(a1)` adds the conveyor speed to the
        // player's x_pos PIXEL word only -- x_sub is untouched, so the player's
        // accumulated sub-pixel fraction survives the conveyor push. The engine's
        // setCentreX() ZEROES x_sub, which discarded up to ~1px of fraction every
        // frame the player rode a conveyor, putting him progressively behind ROM
        // (SBZ2 f2168: x_sub 0x2800 dropped to 0, seeding the constant 0x4C00
        // offset that read as the "subpixel RAM-gated" f2224 frontier). shiftX adds
        // an integer pixel delta and preserves x_sub, matching the ROM add.w.
        player.shiftX(convSpeed);
    }

    @Override
    public void appendDebugRenderCommands(DebugRenderContext ctx) {
        int x = getX();
        int y = getY();

        // Draw the activation zone
        // X range: [x - convWidth, x + convWidth)
        // Y range: [y - 0x30, y)
        int yCenter = y - 0x18; // centre of the Y range
        ctx.drawRect(x, yCenter, convWidth, 0x18, 0.8f, 0.5f, 0.2f);

        // Label with speed info
        String label = String.format("CONV spd=%d w=%d", convSpeed, convWidth);
        ctx.drawWorldLabel(x, yCenter - 0x18 - 8, 0, label, com.openggf.debug.DebugColor.ORANGE);
    }
}
