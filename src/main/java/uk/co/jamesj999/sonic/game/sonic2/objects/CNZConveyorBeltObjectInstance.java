package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Conveyor Belt (Object 0x72).
 * An invisible collision zone that applies horizontal velocity to grounded players.
 * Used in CNZ (Casino Night), MTZ (Metropolis), and WFZ (Wing Fortress).
 *
 * Based on Obj72 from the Sonic 2 disassembly (s2.asm lines 54775-54831).
 *
 * Subtype encoding:
 * - Bits 0-6: Width multiplier. Width = (subtype & 0x7F) * 16 pixels
 * - Bit 7: Height mode. If set, height = 0x70 pixels; otherwise height = 0x30 pixels
 *
 * Direction is controlled by the X-flip render flag:
 * - X-flip clear: velocity = +2 (rightward)
 * - X-flip set: velocity = -2 (leftward)
 */
public class CNZConveyorBeltObjectInstance extends AbstractObjectInstance {

    // From disassembly: move.w #$30,objoff_3C(a0) / move.w #$70,objoff_3C(a0)
    private static final int HEIGHT_NORMAL = 0x30;   // 48 pixels
    private static final int HEIGHT_ENLARGED = 0x70; // 112 pixels

    // From disassembly: move.w #2,objoff_36(a0)
    private static final int VELOCITY = 2;

    // Calculated values from subtype
    private final int widthPixels;    // objoff_38: (subtype & 0x7F) << 4
    private final int heightPixels;   // objoff_3C: 0x30 or 0x70
    private final int velocityX;      // objoff_36: +2 or -2

    public CNZConveyorBeltObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);

        // From disassembly Obj72_Init:
        // move.b subtype(a0),d0
        // bpl.s  +                      ; if bit 7 clear, skip enlarged height
        // move.w #$70,objoff_3C(a0)
        int subtype = spawn.subtype();
        this.heightPixels = (subtype & 0x80) != 0 ? HEIGHT_ENLARGED : HEIGHT_NORMAL;

        // andi.b #$7F,d0
        // lsl.b  #4,d0
        // move.b d0,objoff_38(a0)
        this.widthPixels = (subtype & 0x7F) << 4;

        // move.w #2,objoff_36(a0)
        // btst   #status.npc.x_flip,status(a0)
        // beq.s  Obj72_Main
        // neg.w  objoff_36(a0)
        boolean xFlipped = (spawn.renderFlags() & 0x01) != 0;
        this.velocityX = xFlipped ? -VELOCITY : VELOCITY;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // From disassembly Obj72_Main:
        // lea (MainCharacter).w,a1
        // bsr.s Obj72_Action
        // lea (Sidekick).w,a1
        // bsr.s Obj72_Action
        // jmpto JmpTo5_MarkObjGone3

        // We only have access to the main player here
        if (player != null) {
            applyConveyorAction(player);
        }
    }

    /**
     * Applies conveyor velocity to the player if within bounds and grounded.
     * Based on Obj72_Action from the disassembly.
     */
    private void applyConveyorAction(AbstractPlayableSprite player) {
        // From disassembly Obj72_Action:

        // moveq #0,d2
        // move.b objoff_38(a0),d2     ; d2 = width (half-width for collision)
        int halfWidth = widthPixels;

        // move.w d2,d3
        // add.w  d3,d3               ; d3 = width * 2 (full collision width)
        int fullWidth = halfWidth * 2;

        // move.w x_pos(a1),d0        ; player X (center)
        // sub.w  x_pos(a0),d0        ; deltaX = playerX - beltX
        // add.w  d2,d0               ; deltaX + halfWidth
        int objX = spawn.x();
        int playerX = player.getCentreX();
        int deltaX = playerX - objX + halfWidth;

        // cmp.w  d3,d0
        // bhs.s  +                   ; if deltaX >= fullWidth, exit (unsigned compare)
        // This is an unsigned comparison: if deltaX < 0 or deltaX >= fullWidth, exit
        if (deltaX < 0 || deltaX >= fullWidth) {
            return;
        }

        // move.w y_pos(a1),d1        ; player Y (center)
        // sub.w  y_pos(a0),d1        ; deltaY = playerY - beltY
        // move.w objoff_3C(a0),d0    ; d0 = height
        // add.w  d0,d1               ; deltaY + height
        int objY = spawn.y();
        int playerY = player.getCentreY();
        int deltaY = playerY - objY + heightPixels;

        // cmp.w  d0,d1
        // bhs.s  +                   ; if deltaY >= height, exit (unsigned compare)
        // The collision box is from (beltY - height) to beltY
        // So player must be within [objY - heightPixels, objY]
        if (deltaY < 0 || deltaY >= heightPixels) {
            return;
        }

        // btst   #status.player.in_air,status(a1)
        // bne.s  +                   ; if player in air, exit
        if (player.getAir()) {
            return;
        }

        // move.w objoff_36(a0),d0    ; get velocity
        // add.w  d0,x_pos(a1)        ; apply velocity to player X
        // Note: We add to the pixel position directly
        player.setX((short) (player.getX() + velocityX));
    }

    /**
     * This object is invisible - no rendering required.
     * The conveyor belt effect is purely through collision/movement.
     */
    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Object 0x72 has no visual representation
        // It just affects player movement when they're within its bounds
    }

    @Override
    public int getPriorityBucket() {
        return 0; // No rendering
    }
}
