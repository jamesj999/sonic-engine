package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.SolidObjectManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import java.util.logging.Logger;

/**
 * Object 06 - Spiral pathway from EHZ.
 * <p>
 * Invisible object that modifies the player's Y position and rotation angle as
 * they traverse it.
 * Effectively creates a "wave" motion and sprite twisting effect.
 */
public class SpiralObjectInstance extends AbstractObjectInstance {
    private static final Logger LOGGER = Logger.getLogger(SpiralObjectInstance.class.getName());

    // Obj06_FlipAngleTable (sloopdirtbl)
    private static final byte[] FLIP_ANGLE_TABLE = {
            0x00, 0x00, 0x01, 0x01, 0x16, 0x16, 0x16, 0x16, 0x2C, 0x2C,
            0x2C, 0x2C, 0x42, 0x42, 0x42, 0x42, 0x58, 0x58, 0x58, 0x58,
            0x6E, 0x6E, 0x6E, 0x6E, (byte) 0x84, (byte) 0x84, (byte) 0x84, (byte) 0x84, (byte) 0x9A, (byte) 0x9A,
            (byte) 0x9A, (byte) 0x9A, (byte) 0xB0, (byte) 0xB0, (byte) 0xB0, (byte) 0xB0, (byte) 0xC6, (byte) 0xC6,
            (byte) 0xC6, (byte) 0xC6, (byte) 0xDC, (byte) 0xDC, (byte) 0xDC, (byte) 0xDC, (byte) 0xF2, (byte) 0xF2,
            (byte) 0xF2, (byte) 0xF2, 0x01, 0x01, 0x00, 0x00
    };

    // Obj06_CosineTable (slooptbl)
    private static final byte[] COSINE_TABLE = {
            32, 32, 32, 32, 32, 32, 32, 32,
            32, 32, 32, 32, 32, 32, 32, 32,
            32, 32, 32, 32, 32, 32, 32, 32,
            32, 32, 32, 32, 32, 32, 31, 31,
            31, 31, 31, 31, 31, 31, 31, 31,
            31, 31, 31, 31, 31, 30, 30, 30,
            30, 30, 30, 30, 30, 30, 29, 29,
            29, 29, 29, 28, 28, 28, 28, 27,
            27, 27, 27, 26, 26, 26, 25, 25,
            25, 24, 24, 24, 23, 23, 22, 22,
            21, 21, 20, 20, 19, 18, 18, 17,
            16, 16, 15, 14, 14, 13, 12, 12,
            11, 10, 10, 9, 8, 8, 7, 6,
            6, 5, 4, 4, 3, 2, 2, 1,
            0, -1, -2, -2, -3, -4, -4, -5,
            -6, -7, -7, -8, -9, -9, -10, -10,
            -11, -11, -12, -12, -13, -14, -14, -15,
            -15, -16, -16, -17, -17, -18, -18, -19,
            -19, -19, -20, -21, -21, -22, -22, -23,
            -23, -24, -24, -25, -25, -26, -26, -27,
            -27, -28, -28, -28, -29, -29, -30, -30,
            -30, -31, -31, -31, -32, -32, -32, -33,
            -33, -33, -33, -34, -34, -34, -35, -35,
            -35, -35, -35, -35, -35, -35, -36, -36,
            -36, -36, -36, -36, -36, -36, -36, -37,
            -37, -37, -37, -37, -37, -37, -37, -37,
            -37, -37, -37, -37, -37, -37, -37, -37,
            -37, -37, -37, -37, -37, -37, -37, -37,
            -37, -37, -37, -37, -36, -36, -36, -36,
            -36, -36, -36, -35, -35, -35, -35, -35,
            -35, -35, -35, -34, -34, -34, -33, -33,
            -33, -33, -32, -32, -32, -31, -31, -31,
            -30, -30, -30, -29, -29, -28, -28, -28,
            -27, -27, -26, -26, -25, -25, -24, -24,
            -23, -23, -22, -22, -21, -21, -21, -19,
            -19, -18, -18, -17, -16, -16, -15, -14,
            -14, -13, -12, -11, -11, -10, -9, -8,
            -7, -7, -6, -5, -4, -3, -2, -1,
            0, 1, 2, 3, 4, 5, 6, 7,
            8, 8, 9, 10, 10, 11, 12, 13,
            13, 14, 14, 15, 15, 16, 16, 17,
            17, 18, 18, 19, 19, 20, 20, 21,
            21, 22, 22, 23, 23, 24, 24, 24,
            25, 25, 25, 25, 26, 26, 26, 26,
            27, 27, 27, 27, 28, 28, 28, 28,
            28, 28, 29, 29, 29, 29, 29, 29,
            29, 30, 30, 30, 30, 30, 30, 30,
            31, 31, 31, 31, 31, 31, 31, 31,
            31, 31, 32, 32, 32, 32, 32, 32,
            32, 32, 32, 32, 32, 32, 32, 32,
            32, 32, 32, 32, 32, 32, 32, 32,
            32, 32, 32, 32, 32, 32, 32, 32
    };

    // Tracks current state per frame logic
    private boolean active;

    public SpiralObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        if (!active && player != null) {
            checkActivation(frameCounter, player);
        } else if (active) {
            // Bounds check for falling off
            // loc_215C0
            // logic normally uses inertia, but since we force air state, gSpeed isn't
            // updated.
            // xSpeed is active, so we use that.
            int inertia = Math.abs(player.getGSpeed());
            if (inertia == 0) {
                inertia = Math.abs(player.getXSpeed());
            }
            // ROM uses inertia threshold 0x600
            if (inertia < 0x600) {
                fallOff(player, frameCounter);
                return;
            }

            // X range check relative to spiral start
            // move.w x_pos(a1),d0
            // sub.w x_pos(a0),d0
            // addi.w #$D0,d0 -> this centers the range check.
            // Range in ROM logic: -0xD0 to 0x1A0 (relative to start)

            int dx = player.getCentreX() - spawn.x();
            int offset = dx + 0xD0;

            if (offset < 0 || offset >= 0x1A0) {
                fallOff(player, frameCounter);
                return;
            }

            updateMovement(player, frameCounter);
        }
    }

    private void checkActivation(int frameCounter, AbstractPlayableSprite player) {
        int dx = player.getCentreX() - spawn.x();

        // loc_21512 checks:
        boolean wasOnSpiral = player.wasSpiralActive(frameCounter);
        if (player.getAir() && !wasOnSpiral) {
            return;
        }

        // Initial range checks for "locking on"
        int vx = player.getXSpeed();
        SolidObjectManager solidManager = LevelManager.getInstance().getSolidObjectManager();
        boolean onObject = (solidManager != null && solidManager.isRidingObject()) || wasOnSpiral;

        // Debug range
        if (Math.abs(dx) < 250 && frameCounter % 30 == 0) {
            LOGGER.fine("Spiral candidate: dx=" + dx + " vx=" + vx + " air=" + player.getAir());
        }

        // Ranges vary by approach direction:
        // Moving Right (vx >= 0): range -0xD0..-0xC0 (or -0xC0..-0xB0 if on object)
        boolean inXRange = false;
        if (vx >= 0) {
            int min = onObject ? -0xC0 : -0xD0;
            int max = onObject ? -0xB0 : -0xC0;
            if (dx >= min && dx <= max) {
                inXRange = true;
            }
        } else {
            // Moving Left (vx < 0): range 0xC0..0xD0 (or 0xB0..0xC0 if on object)
            int min = onObject ? 0xB0 : 0xC0;
            int max = onObject ? 0xC0 : 0xD0;
            if (dx >= min && dx <= max) {
                inXRange = true;
            }
        }

        if (!inXRange) {
            return;
        }

        // Y check
        // Range: $10 <= (player Y - object Y) < $40
        int dy = player.getCentreY() - spawn.y();
        int diff = dy - 0x10;
        if (diff < 0 || diff >= 0x30) {
            return;
        }

        // Engage
        active = true;
        // Logic similar to RideObject_SetRide
        // Start by "floating" (air=true) but correcting Y manually.
        // We do NOT set air=false because that causes the ground sensor check to fail
        // and reset state.
        player.setAir(true);
        // Match RideObject_SetRide behavior: zero vertical speed and keep inertia.
        player.setYSpeed((short) 0);
        player.setGSpeed(player.getXSpeed());
        player.markSpiralActive(frameCounter);
        LOGGER.fine("Spiral Activated: Player engaged at dx=" + (player.getCentreX() - spawn.x()));
    }

    private void updateMovement(AbstractPlayableSprite player, int frameCounter) {
        // Obj06_Spiral_MoveCharacter:

        // Index into cosine table is based on X position relative to object
        // move.w x_pos(a1),d0
        // sub.w x_pos(a0),d0
        int dx = player.getCentreX() - spawn.x();

        // Ensure positive index access for table if needed, but Java arrays need
        // correct index.
        // ROM: move.b Obj06_CosineTable(pc,d0.w),d1 -> d0 is signed relative offset.
        // But wait, d0 was offset by $D0 in the check.
        // Actually, let's look at table access.
        // "move.b Obj06_CosineTable(pc,d0.w),d1"
        // This suggests the table covers the entire range of X offsets that are valid.

        // We need to map dx to table index.
        // If dx starts at -0xD0 (approx -208), accessing index -208 is invalid in Java.
        // The table size is roughly 416 bytes (0x1A0).
        // It matches the 416 (0x1A0) range.
        // This strongly suggests the table assumes the index is `dx + 0xD0`.
        // But the ASM instruction uses raw `d0`.
        // Unless... does `Obj06_CosineTable` point to the MIDDLE (offset 208) of the
        // data?
        // `dc.b 32, 32...` - those look like height values.

        // Let's assume for Java implementation that we index by `dx + 0xD0`.

        int tableIndex = dx + 0xD0;
        if (tableIndex < 0 || tableIndex >= COSINE_TABLE.length) {
            // Out of bounds
            return;
        }

        byte offsetY = COSINE_TABLE[tableIndex];
        // d1 extended to word.

        // move.w y_pos(a0),d2
        // add.w d1,d2
        // sub.w (y_radius - $13), d2
        // move.w d2,y_pos(a1)

        // Update Y position
        int targetCenterY = spawn.y() + offsetY;
        int radiusOffset = player.getYRadius() - 0x13;
        targetCenterY -= radiusOffset;
        int targetTopY = targetCenterY - (player.getHeight() / 2);
        player.setY((short) targetTopY);
        player.setYSpeed((short) 0);
        player.setAir(true); // Ensure engine doesn't try to "land" us on non-existent ground

        // Flip Angle Logic
        // lsr.w #3,d0 -> d0 / 8
        // andi.w #$3F,d0 -> mask 63
        // move.b Obj06_FlipAngleTable(pc,d0.w),flip_angle(a1)

        // Again, assuming `d0` here effectively means `dx + 0xD0` for the index?
        // Actually, with `lsr #3`, it scales down.
        // 416 / 8 = 52.
        // The Flip Angle Table is exactly 52 bytes.
        // This confirms that we should use `(dx + 0xD0) >> 3` as the index.

        int angleIndex = ((dx + 0xD0) >> 3) & 0x3F;
        if (angleIndex >= 0 && angleIndex < FLIP_ANGLE_TABLE.length) {
            player.setFlipAngle(FLIP_ANGLE_TABLE[angleIndex] & 0xFF);
        }
        player.markSpiralActive(frameCounter);
    }

    private void fallOff(AbstractPlayableSprite player, int frameCounter) {
        active = false;
        player.setAir(true);
        // Reset angle if no other spiral updated this frame.
        if (!player.isSpiralActiveThisFrame(frameCounter)) {
            player.setAngle((byte) 0);
        }
        player.setFlipsRemaining(0);
        player.setFlipSpeed(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Invisible object
    }
}
