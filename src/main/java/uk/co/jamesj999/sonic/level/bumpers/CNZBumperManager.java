package uk.co.jamesj999.sonic.level.bumpers;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.level.spawn.AbstractPlacementManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

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
        this.audioManager = AudioManager.getInstance();
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
        // Step 1: Calculate incoming angle from player velocity
        // ROM: move.w x_vel(a0),d1 / move.w y_vel(a0),d2 / jsr CalcAngle
        int xVel = player.getXSpeed();
        int yVel = player.getYSpeed();

        // Convert velocity to MD angle (0-255)
        // CalcAngle returns angle where 0=right, 0x40=down, 0x80=left, 0xC0=up
        int incomingAngle = calcAngleMD(xVel, yVel);

        // Step 2: Calculate delta from surface angle
        // ROM: sub.w d3,d0  (d0 = incomingAngle - surfaceAngle)
        int delta = (incomingAngle - surfaceAngle) & 0xFF;

        // Handle signed comparison (convert to signed -128..127 range)
        int signedDelta = (delta > 127) ? delta - 256 : delta;
        int absDelta = StrictMath.abs(signedDelta);

        // Step 3: Determine output angle
        // ROM: cmpi.b #$38,d1 / blo.s loc_17618 / move.w d3,d0
        int outAngle;
        if (absDelta < 0x38) {
            // Reflect: outAngle = -delta + surfaceAngle
            // ROM: neg.w d0 / add.w d3,d0
            outAngle = (-signedDelta + surfaceAngle) & 0xFF;
        } else {
            // Too steep - force redirect to surface angle
            outAngle = surfaceAngle & 0xFF;
        }

        // Step 4: Apply velocity using CalcSine
        // ROM: jsr CalcSine returns sin in d0, cos in d1
        // Then: muls.w #-$A00,d1 / asr.l #8,d1 / move.w d1,x_vel(a0)
        //       muls.w #-$A00,d0 / asr.l #8,d0 / move.w d0,y_vel(a0)
        // So: x_vel = -cos(angle) * $A00 >> 8, y_vel = -sin(angle) * $A00 >> 8
        double radians = (outAngle / 256.0) * 2.0 * StrictMath.PI;

        // ROM formula: x_vel = -cos * $A00, y_vel = -sin * $A00
        int newXVel = (int) (-StrictMath.cos(radians) * BOUNCE_VELOCITY);
        int newYVel = (int) (-StrictMath.sin(radians) * BOUNCE_VELOCITY);

        player.setXSpeed((short) newXVel);
        player.setYSpeed((short) newYVel);
    }

    /**
     * Calculate Mega Drive-style angle from velocity components.
     * <p>
     * Approximates ROM CalcAngle behavior:
     * <ul>
     *   <li>0x00 = right (+X)</li>
     *   <li>0x40 = down (+Y)</li>
     *   <li>0x80 = left (-X)</li>
     *   <li>0xC0 = up (-Y)</li>
     * </ul>
     *
     * @param dx X component (positive = right)
     * @param dy Y component (positive = down)
     * @return Angle in 0-255 range
     */
    private int calcAngleMD(int dx, int dy) {
        if (dx == 0 && dy == 0) {
            return 0;
        }

        // atan2 returns radians where 0=right, PI/2=up, PI=left, -PI/2=down
        // MD convention: 0=right, 0x40=down, 0x80=left, 0xC0=up
        // So we need to negate Y for the MD y-down coordinate system
        double radians = StrictMath.atan2(dy, dx);

        // Convert to 0-255 range
        int angle = (int) ((radians / (2.0 * StrictMath.PI)) * 256.0);

        // Normalize to 0-255
        return angle & 0xFF;
    }

    private static final class Placement extends AbstractPlacementManager<CNZBumperSpawn> {
        private static final int LOAD_AHEAD = 640;
        private static final int UNLOAD_BEHIND = 768;

        private int lastWindowStart = -1;
        private int lastWindowEnd = -1;

        private Placement(List<CNZBumperSpawn> bumpers) {
            super(bumpers, LOAD_AHEAD, UNLOAD_BEHIND);
        }

        private void update(int cameraX) {
            int windowStart = getWindowStart(cameraX);
            int windowEnd = getWindowEnd(cameraX);

            if (windowStart == lastWindowStart && windowEnd == lastWindowEnd) {
                return;
            }

            lastWindowStart = windowStart;
            lastWindowEnd = windowEnd;

            active.clear();

            int startIdx = lowerBound(windowStart);
            int endIdx = upperBound(windowEnd);

            for (int i = startIdx; i < endIdx && i < spawns.size(); i++) {
                CNZBumperSpawn bumper = spawns.get(i);
                if (bumper.x() >= windowStart && bumper.x() <= windowEnd) {
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
    }
}
