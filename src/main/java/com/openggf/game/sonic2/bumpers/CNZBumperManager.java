package com.openggf.game.sonic2.bumpers;

import com.openggf.audio.AudioManager;
import com.openggf.audio.GameSound;
import com.openggf.level.spawn.AbstractPlacementManager;
import com.openggf.physics.TrigLookupTable;
import com.openggf.sprites.playable.AbstractPlayableSprite;

import java.util.List;
import com.openggf.game.GameServices;

/**
 * Handles CNZ bumper windowing and collision in one system.
 * <p>
 * Disassembly References:
 * <ul>
 *   <li>CNZ_Visible_bumpers_start/end (s2.asm line 32176-32187)</li>
 *   <li>Check_CNZ_bumpers (s2.asm line 32277)</li>
 * </ul>
 */
public class CNZBumperManager {
    /**
     * Bounce velocity magnitude = $A00 (2560 in 8.8 fixed point).
     * Stronger than the round bumper's $700.
     */
    private static final int BOUNCE_VELOCITY = 0xA00;

    /**
     * Zone index for Casino Night Zone.
     * ROM Reference: s2.constants.asm - casino_night_zone = $0C
     */
    public static final int ZONE_CNZ = 0x0C;

    /**
     * Player collision box half-width approximation (push radius).
     */
    private static final int PLAYER_HALF_WIDTH = 9;

    /**
     * Threshold for diagonal collision checks (ROM uses $20).
     */
    private static final int DIAGONAL_THRESHOLD = 0x20;

    /**
     * Threshold for narrow bumper edge detection (ROM uses $40).
     */
    private static final int NARROW_THRESHOLD = 0x40;

    /**
     * Small threshold for narrow bumper primary axis (ROM uses 8).
     */
    private static final int NARROW_SMALL_THRESHOLD = 8;

    private final Placement placement;
    private final AudioManager audioManager;

    public CNZBumperManager(List<CNZBumperSpawn> bumpers) {
        this.placement = new Placement(bumpers);
        this.audioManager = GameServices.audio();
    }

    public void reset(int cameraX) {
        placement.reset(cameraX);
    }

    public void update(AbstractPlayableSprite player, int cameraX, int currentZone) {
        if (currentZone != ZONE_CNZ) {
            return;
        }
        if (player == null || player.getDead() || player.isDebugMode()) {
            return;
        }
        if (player.isHurt()) {
            return;
        }

        placement.update(cameraX);
        for (CNZBumperSpawn bumper : placement.getActiveSpawns()) {
            if (checkCollision(player, bumper)) {
                applyBounce(player, bumper);
                break;
            }
        }
    }

    private boolean checkCollision(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        CNZBumperType type = CNZBumperType.fromId(bumper.type());
        if (type == null) {
            return false;
        }

        int bumperHalfWidth = type.getHalfWidth();
        int bumperHalfHeight = type.getHalfHeight();

        int playerCenterX = player.getCentreX();
        int playerCenterY = player.getCentreY();
        int playerLeft = playerCenterX - PLAYER_HALF_WIDTH;

        int yRadiusAdj = player.getYRadius() - 3;
        int playerTop = playerCenterY - yRadiusAdj;

        int playerWidth = 0x12;
        int playerHeight = yRadiusAdj * 2;

        int dx = bumper.x() - bumperHalfWidth - playerLeft;

        if (dx < 0) {
            dx += bumperHalfWidth * 2;
            if (dx < 0) {
                return false;
            }
        } else {
            if (dx > playerWidth) {
                return false;
            }
        }

        int dy = bumper.y() - bumperHalfHeight - playerTop;

        if (dy < 0) {
            dy += bumperHalfHeight * 2;
            if (dy < 0) {
                return false;
            }
        } else {
            if (dy > playerHeight) {
                return false;
            }
        }

        return true;
    }

    /**
     * Apply bounce to player based on bumper type.
     * <p>
     * ROM Reference: off_1757A handler table at s2.asm line 32377
     *
     * @param player The player sprite
     * @param bumper The bumper that was hit
     */
    private void applyBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        CNZBumperType type = CNZBumperType.fromId(bumper.type());
        if (type == null) {
            return;
        }

        switch (type) {
            case DIAGONAL_DOWN_RIGHT -> applyDiagonalDownRightBounce(player, bumper);
            case DIAGONAL_DOWN_LEFT -> applyDiagonalDownLeftBounce(player, bumper);
            case NARROW_TOP -> applyNarrowTopBounce(player, bumper);
            case NARROW_BOTTOM -> applyNarrowBottomBounce(player, bumper);
            case NARROW_LEFT -> applyNarrowLeftBounce(player, bumper);
            case NARROW_RIGHT -> applyNarrowRightBounce(player, bumper);
        }

        // Common state changes after bounce
        // ROM Reference: loc_177FA at s2.asm lines 32647-32657
        player.setAir(true);
        player.setPushing(false);
        // Clear jumping flag (ROM: clr.b jumping(a0))
        // Note: AbstractPlayableSprite may not have setJumping, using setAir handles airborne state

        // Play sound
        audioManager.playSfx(GameSound.LARGE_BUMPER);
    }

    /**
     * Type 0: Diagonal down-right bounce.
     * ROM Reference: loc_17586 at s2.asm line 32386
     * <p>
     * This bumper is shaped like: ◢ (solid in bottom-right, slope faces up-left)
     * Hitting from below bounces down, from right bounces right,
     * hitting the slope bounces at angle $20 (up-left direction).
     */
    private void applyDiagonalDownRightBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // ROM: d0 = bumper_y - player_y; neg d0 → d0 = player_y - bumper_y
        int dy = playerY - bumper.y();

        // If player is sufficiently below bumper center, simple downward bounce
        // ROM: cmpi.w #$20,d0 / blt.s loc_175A0 / move.w #$A00,y_vel
        if (dy >= DIAGONAL_THRESHOLD) {
            player.setYSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // ROM: d0 = bumper_x - player_x; neg d0 → d0 = player_x - bumper_x
        int dx = playerX - bumper.x();

        // If player is sufficiently right of bumper center, simple rightward bounce
        // ROM: cmpi.w #$20,d0 / blt.s loc_175BA / move.w #$A00,x_vel
        if (dx >= DIAGONAL_THRESHOLD) {
            player.setXSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // Complex diagonal check at loc_175BA:
        // ROM: d0 = bumper_x - player_x (NO neg this time - different from above!)
        int diagDx = bumper.x() - playerX;

        // ROM: cmpi.w #$20,d0 / blt.s loc_175CC / move.w #$20,d0 (clamp to $20)
        if (diagDx >= DIAGONAL_THRESHOLD) {
            diagDx = DIAGONAL_THRESHOLD;
        }

        // ROM: add.w bumper_y(a1),d0 / subq.w #8,d0
        // This calculates the Y position of the diagonal line at player's X
        int diagonalY = diagDx + bumper.y() - 8;

        // ROM: d1 = player_y + $E (player bottom offset)
        int playerBottom = playerY + 0x0E;

        // ROM: sub.w d1,d0 / bcc.s return_175E8
        // If diagonalY - playerBottom >= 0 (diagonal above player bottom), no collision
        if (diagonalY >= playerBottom) {
            return;
        }

        // Apply angle bounce toward $20 (up-left direction from down-right surface)
        applyAngleBounce(player, 0x20);
    }

    /**
     * Type 1: Diagonal down-left bounce.
     * ROM Reference: loc_17638 at s2.asm line 32456
     * <p>
     * This bumper is shaped like: ◣ (solid in bottom-left, slope faces up-right)
     * Hitting from below bounces down, from left bounces left,
     * hitting the slope bounces at angle $60 (up-right direction).
     */
    private void applyDiagonalDownLeftBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        int playerY = player.getCentreY();
        int playerX = player.getCentreX();

        // ROM: d0 = bumper_y - player_y; neg d0 → d0 = player_y - bumper_y
        int dy = playerY - bumper.y();

        // If player is sufficiently below bumper center, simple downward bounce
        if (dy >= DIAGONAL_THRESHOLD) {
            player.setYSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // ROM at loc_17652: d0 = bumper_x - player_x (NO neg - different from type 0!)
        int dx = bumper.x() - playerX;

        // If player is sufficiently left of bumper center, simple leftward bounce
        // ROM: cmpi.w #$20,d0 / blt.s loc_1766A / move.w #-$A00,x_vel
        if (dx >= DIAGONAL_THRESHOLD) {
            player.setXSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // Complex diagonal check at loc_1766A:
        // ROM: d0 = bumper_x - player_x; neg d0 → d0 = player_x - bumper_x
        int diagDx = playerX - bumper.x();

        // ROM: cmpi.w #$20,d0 / blt.s loc_1767E / move.w #$20,d0 (clamp to $20)
        if (diagDx >= DIAGONAL_THRESHOLD) {
            diagDx = DIAGONAL_THRESHOLD;
        }

        // ROM: add.w bumper_y(a1),d0 / subq.w #8,d0
        int diagonalY = diagDx + bumper.y() - 8;

        // ROM: d1 = player_y + $E (player bottom offset)
        int playerBottom = playerY + 0x0E;

        // ROM: sub.w d1,d0 / bcc.s return_1769C
        if (diagonalY >= playerBottom) {
            return;
        }

        // Apply angle bounce toward $60 (up-right direction from down-left surface)
        applyAngleBounce(player, 0x60);
    }

    /**
     * Type 2: Narrow top horizontal bar bounce.
     * ROM Reference: loc_1769E at s2.asm line 32499
     */
    private void applyNarrowTopBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        int playerY = player.getCentreY();
        int playerX = player.getCentreX();

        // dy = player_y - bumper_y
        int dy = playerY - bumper.y();

        // If player is sufficiently below bumper (hit from below), bounce down
        if (dy >= NARROW_SMALL_THRESHOLD) {
            player.setYSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // dx = bumper_x - player_x
        int dx = bumper.x() - playerX;

        // If player is far left of center, bounce left
        if (dx >= NARROW_THRESHOLD) {
            player.setXSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // If player is far right of center, bounce right
        if (-dx >= NARROW_THRESHOLD) {
            player.setXSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // Near center - diagonal bounce based on which side
        int angle = (dx < 0) ? 0x48 : 0x38; // Angle toward corner
        applyAngleBounce(player, angle);
    }

    /**
     * Type 3: Narrow bottom horizontal bar bounce.
     * ROM Reference: loc_176F6 at s2.asm line 32537
     */
    private void applyNarrowBottomBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        int playerY = player.getCentreY();
        int playerX = player.getCentreX();

        // dy = bumper_y - player_y (positive means player is above)
        int dy = bumper.y() - playerY;

        // If player is sufficiently above bumper (hit from above), bounce up
        if (dy >= NARROW_SMALL_THRESHOLD) {
            player.setYSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // dx = bumper_x - player_x
        int dx = bumper.x() - playerX;

        // If player is far left of center, bounce left
        if (dx >= NARROW_THRESHOLD) {
            player.setXSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // If player is far right of center, bounce right
        if (-dx >= NARROW_THRESHOLD) {
            player.setXSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // Near center - diagonal bounce based on which side
        int angle = (dx < 0) ? 0xB8 : 0xC8; // Angle toward corner (upward)
        applyAngleBounce(player, angle);
    }

    /**
     * Type 4: Narrow left vertical bar bounce.
     * ROM Reference: loc_1774C at s2.asm line 32574
     */
    private void applyNarrowLeftBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        int playerY = player.getCentreY();
        int playerX = player.getCentreX();

        // dx = player_x - bumper_x (positive means player is right of bumper)
        int dx = playerX - bumper.x();

        // If player is sufficiently right of bumper (hit from right), bounce right
        if (dx >= NARROW_SMALL_THRESHOLD) {
            player.setXSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // dy = bumper_y - player_y
        int dy = bumper.y() - playerY;

        // If player is far above center, bounce up
        if (dy >= NARROW_THRESHOLD) {
            player.setYSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // If player is far below center, bounce RIGHT (not down)
        // ROM: loc_1777E sets x_vel = $A00
        if (-dy >= NARROW_THRESHOLD) {
            player.setXSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // Near center - diagonal bounce based on which side
        int angle = (dy < 0) ? 0xF8 : 0x08; // Angle toward corner
        applyAngleBounce(player, angle);
    }

    /**
     * Type 5: Narrow right vertical bar bounce.
     * ROM Reference: loc_177A4 at s2.asm line 32612
     */
    private void applyNarrowRightBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        int playerY = player.getCentreY();
        int playerX = player.getCentreX();

        // dx = bumper_x - player_x (positive means player is left of bumper)
        int dx = bumper.x() - playerX;

        // If player is sufficiently left of bumper, bounce RIGHT (not left)
        // ROM: loc_177A4 sets x_vel = $A00
        if (dx >= NARROW_SMALL_THRESHOLD) {
            player.setXSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // dy = bumper_y - player_y
        int dy = bumper.y() - playerY;

        // If player is far above center, bounce up
        if (dy >= NARROW_THRESHOLD) {
            player.setYSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // If player is far below center, bounce RIGHT (not down)
        // ROM: loc_177D4 sets x_vel = $A00
        if (-dy >= NARROW_THRESHOLD) {
            player.setXSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // Near center - diagonal bounce based on which side
        int angle = (dy < 0) ? 0x88 : 0x78; // Angle toward corner
        applyAngleBounce(player, angle);
    }

    /**
     * Apply velocity based on reflection physics in Mega Drive format.
     * <p>
     * ROM Reference: loc_175EA at s2.asm lines 32429-32453
     * <p>
     * The ROM algorithm:
     * <ol>
     *   <li>Calculate incoming angle from player's current velocity</li>
     *   <li>Compute delta = incomingAngle - surfaceAngle</li>
     *   <li>If |delta| < $38 (56): reflect around surface (outAngle = -delta + surfaceAngle)</li>
     *   <li>If |delta| >= $38: force redirect to surface angle</li>
     *   <li>Apply velocity using CalcSine with magnitude $A00</li>
     * </ol>
     * <p>
     * Mega Drive angle convention:
     * <ul>
     *   <li>0x00 = right</li>
     *   <li>0x40 = down</li>
     *   <li>0x80 = left</li>
     *   <li>0xC0 = up</li>
     * </ul>
     *
     * @param player The player sprite
     * @param surfaceAngle The target surface/bounce angle (0-255)
     */
    private void applyAngleBounce(AbstractPlayableSprite player, int surfaceAngle) {
        // Step 1: Calculate incoming angle from player velocity.
        // ROM: move.w x_vel(a0),d1 / move.w y_vel(a0),d2 / jsr CalcAngle
        int xVel = player.getXSpeed();
        int yVel = player.getYSpeed();

        int incomingAngle = TrigLookupTable.calcAngle((short) xVel, (short) yVel);

        // Step 2/3: Resolve the outgoing angle with the ROM's word-sized
        // subtract/absolute-value semantics.
        int outAngle = resolveAngleBounceOutAngle(incomingAngle, surfaceAngle);

        // Step 4: Apply velocity using CalcSine
        // ROM: jsr CalcSine returns sin in d0, cos in d1
        // Then: muls.w #-$A00,d1 / asr.l #8,d1 / move.w d1,x_vel(a0)
        //       muls.w #-$A00,d0 / asr.l #8,d0 / move.w d0,y_vel(a0)
        // So: x_vel = -cos(angle) * $A00 >> 8, y_vel = -sin(angle) * $A00 >> 8
        int newXVel = (TrigLookupTable.cosHex(outAngle) * -BOUNCE_VELOCITY) >> 8;
        int newYVel = (TrigLookupTable.sinHex(outAngle) * -BOUNCE_VELOCITY) >> 8;

        player.setXSpeed((short) newXVel);
        player.setYSpeed((short) newYVel);
    }

    static int resolveAngleBounceOutAngle(int incomingAngle, int surfaceAngle) {
        int deltaWord = toSignedWord((incomingAngle & 0xFF) - (surfaceAngle & 0xFF));
        int absDeltaWord = StrictMath.abs(deltaWord);

        // ROM: cmpi.b #$38,d1 / blo.s loc_17618. The compare uses the low byte
        // of the word absolute value as an unsigned byte, not a signed 8-bit delta.
        if ((absDeltaWord & 0xFF) < 0x38) {
            return toSignedWord(-deltaWord + (surfaceAngle & 0xFF)) & 0xFF;
        }

        return surfaceAngle & 0xFF;
    }

    private static int toSignedWord(int value) {
        value &= 0xFFFF;
        return value >= 0x8000 ? value - 0x10000 : value;
    }

    static int romWindowStartForCamera(int cameraX) {
        return Placement.romWindowStart(cameraX);
    }

    static int romWindowEndExclusiveForCamera(int cameraX) {
        return Placement.romWindowEndExclusive(cameraX);
    }

    private static final class Placement extends AbstractPlacementManager<CNZBumperSpawn> {
        private static final int ROM_LEFT_OFFSET = 8;
        private static final int ROM_WINDOW_WIDTH = 0x150;

        private int lastWindowStart = -1;
        private int lastWindowEnd = -1;

        private Placement(List<CNZBumperSpawn> bumpers) {
            super(bumpers, 0, 0);
        }

        private void update(int cameraX) {
            int windowStart = romWindowStart(cameraX);
            int windowEnd = romWindowEndExclusive(cameraX);

            if (windowStart == lastWindowStart && windowEnd == lastWindowEnd) {
                return;
            }

            lastWindowStart = windowStart;
            lastWindowEnd = windowEnd;

            active.clear();

            int startIdx = lowerBound(windowStart);
            int endIdx = lowerBound(windowEnd);

            for (int i = startIdx; i < endIdx && i < spawns.size(); i++) {
                CNZBumperSpawn bumper = spawns.get(i);
                if (bumper.x() >= windowStart && bumper.x() < windowEnd) {
                    active.add(bumper);
                }
            }
        }

        private void reset(int cameraX) {
            active.clear();
            lastWindowStart = -1;
            lastWindowEnd = -1;
            update(cameraX);
        }

        static int romWindowStart(int cameraX) {
            return cameraX > ROM_LEFT_OFFSET ? cameraX - ROM_LEFT_OFFSET : 1;
        }

        static int romWindowEndExclusive(int cameraX) {
            return romWindowStart(cameraX) + ROM_WINDOW_WIDTH;
        }
    }
}
