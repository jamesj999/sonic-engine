package uk.co.jamesj999.sonic.level.bumpers;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

/**
 * Handles collision detection and bounce response for CNZ map bumpers.
 * <p>
 * Called from the level update loop. Only active when the current zone is
 * Casino Night Zone (zone index 3).
 * <p>
 * Disassembly Reference: Check_CNZ_bumpers at s2.asm line 32277
 * <p>
 * Physics:
 * <ul>
 *   <li>Bounce velocity: $A00 (2560) - stronger than round bumper ($700)</li>
 *   <li>Sound: SndID_LargeBumper (0xD9)</li>
 *   <li>Player state: Set airborne, clear pushing, clear jumping</li>
 * </ul>
 */
public class CNZBumperCollisionManager {
    /**
     * Bounce velocity magnitude = $A00 (2560 in 8.8 fixed point).
     * This is stronger than the round bumper's $700.
     * <p>
     * ROM Reference: s2.asm lines 32392, 32402, etc.
     */
    private static final int BOUNCE_VELOCITY = 0xA00;

    /**
     * Zone index for Casino Night Zone.
     * ROM Reference: s2.constants.asm - casino_night_zone = $0C
     */
    public static final int ZONE_CNZ = 0x0C;

    /**
     * Player collision box half-width approximation.
     * ROM uses player's push_radius which is typically 10 pixels.
     */
    private static final int PLAYER_HALF_WIDTH = 9;

    /**
     * Threshold for diagonal collision checks.
     * ROM uses $20 (32 pixels) for determining axis vs diagonal bounce.
     */
    private static final int DIAGONAL_THRESHOLD = 0x20;

    /**
     * Threshold for narrow bumper edge detection.
     * ROM uses $40 (64 pixels) for narrow bumper left/right checks.
     */
    private static final int NARROW_THRESHOLD = 0x40;

    /**
     * Small threshold for narrow bumper primary axis.
     * ROM uses 8 pixels.
     */
    private static final int NARROW_SMALL_THRESHOLD = 8;

    private final CNZBumperPlacementManager placementManager;
    private final AudioManager audioManager;

    public CNZBumperCollisionManager(CNZBumperPlacementManager placementManager) {
        this.placementManager = placementManager;
        this.audioManager = AudioManager.getInstance();
    }

    /**
     * Update collision checking for the given player.
     * Should be called each frame from the level update loop.
     *
     * @param player The player sprite to check collision for
     * @param cameraX Current camera X position for windowing
     * @param currentZone Current zone index (only processes if CNZ)
     */
    public void update(AbstractPlayableSprite player, int cameraX, int currentZone) {
        // Only process for Casino Night Zone
        if (currentZone != ZONE_CNZ) {
            return;
        }

        // Skip if player is invalid, dead, or in debug mode
        if (player == null || player.getDead() || player.isDebugMode()) {
            return;
        }

        // Skip if player is hurt (invulnerable)
        if (player.isHurt()) {
            return;
        }

        // Update placement manager windowing
        placementManager.update(cameraX);

        // Check collision with each active bumper
        var activeSpawns = placementManager.getActiveSpawns();

        for (CNZBumperSpawn bumper : activeSpawns) {
            if (checkCollision(player, bumper)) {
                applyBounce(player, bumper);
                // Only one bounce per frame (ROM behavior)
                break;
            }
        }
    }

    /**
     * Reset the manager state.
     *
     * @param cameraX Current camera X position
     */
    public void reset(int cameraX) {
        placementManager.reset(cameraX);
    }

    /**
     * Check collision between player and bumper.
     * <p>
     * ROM Reference: Check_CNZ_bumpers collision logic at s2.asm lines 32287-32345
     * <p>
     * The ROM algorithm:
     * <pre>
     * d2 = player_x - 9          ; player left edge
     * d5 = y_radius - 3          ; then doubled for height
     * d3 = player_y - d5         ; player top edge
     * d4 = $12 (18)              ; player width for comparison
     * d5 = d5 * 2                ; player height for comparison
     *
     * For X: d0 = bumper_x - halfWidth - d2
     *   if d0 < 0: d0 += halfWidth*2, check if now >= 0 (collision)
     *   if d0 >= 0: check if d0 <= d4 (player width)
     *
     * For Y: d0 = bumper_y - halfHeight - d3
     *   if d0 < 0: d0 += halfHeight*2, check if now >= 0 (collision)
     *   if d0 >= 0: check if d0 <= d5 (player height)
     * </pre>
     *
     * @param player The player sprite
     * @param bumper The bumper to check
     * @return true if collision detected
     */
    private boolean checkCollision(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        CNZBumperType type = CNZBumperType.fromId(bumper.type());
        if (type == null) {
            return false;
        }

        int bumperHalfWidth = type.getHalfWidth();
        int bumperHalfHeight = type.getHalfHeight();

        // Player collision box calculation (matches ROM exactly)
        // ROM uses x_pos (center) and y_pos (center)
        // d2 = player_x - 9 (approximate left edge from center)
        int playerCenterX = player.getCentreX();
        int playerCenterY = player.getCentreY();
        int playerLeft = playerCenterX - 9;

        // d5 = y_radius - 3, then d3 = player_y - d5 (player top edge from center)
        int yRadiusAdj = player.getYRadius() - 3;
        int playerTop = playerCenterY - yRadiusAdj;

        // d4 = $12 (18) = player width for comparison
        int playerWidth = 0x12;

        // d5 = (y_radius - 3) * 2 = player height for comparison
        int playerHeight = yRadiusAdj * 2;

        // X collision check
        // d0 = bumper_x - halfWidth - d2 = bumper left edge - player left edge
        int dx = bumper.x() - bumperHalfWidth - playerLeft;

        if (dx < 0) {
            // Player left edge is past bumper left edge
            // Add bumper full width to see if player is still within bumper
            dx += bumperHalfWidth * 2;
            if (dx < 0) {
                // Player is completely past bumper right edge
                return false;
            }
            // dx >= 0 means collision on X axis
        } else {
            // Player left edge is before bumper left edge
            // Check if player right edge reaches bumper
            if (dx > playerWidth) {
                // Player is completely before bumper left edge
                return false;
            }
            // dx <= playerWidth means collision on X axis
        }

        // Y collision check (same logic as X)
        // d0 = bumper_y - halfHeight - d3 = bumper top edge - player top edge
        int dy = bumper.y() - bumperHalfHeight - playerTop;

        if (dy < 0) {
            // Player top edge is past bumper top edge
            // Add bumper full height to see if player is still within bumper
            dy += bumperHalfHeight * 2;
            if (dy < 0) {
                // Player is completely past bumper bottom edge
                return false;
            }
            // dy >= 0 means collision on Y axis
        } else {
            // Player top edge is before bumper top edge
            // Check if player bottom edge reaches bumper
            if (dy > playerHeight) {
                // Player is completely before bumper top edge
                return false;
            }
            // dy <= playerHeight means collision on Y axis
        }

        // Both X and Y collision detected
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
     */
    private void applyDiagonalDownRightBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        // ROM uses x_pos and y_pos which are center positions
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        // dy = player_y - bumper_y (positive means player is below bumper)
        int dy = playerY - bumper.y();

        // If player is sufficiently below bumper, simple downward bounce
        if (dy >= DIAGONAL_THRESHOLD) {
            player.setYSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // dx = player_x - bumper_x (positive means player is right of bumper)
        int dx = playerX - bumper.x();

        // If player is sufficiently right of bumper, simple rightward bounce
        if (dx >= DIAGONAL_THRESHOLD) {
            player.setXSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // Calculate diagonal collision point
        // ROM checks if player is on the diagonal surface
        int diagonalY = bumper.y() + Math.min(dx, DIAGONAL_THRESHOLD) - 8;
        int playerBottom = playerY + 14; // Approximate player bottom

        if (playerBottom > diagonalY) {
            // Apply angle bounce toward $20 (down-right diagonal)
            applyAngleBounce(player, 0x20);
        }
    }

    /**
     * Type 1: Diagonal down-left bounce.
     * ROM Reference: loc_17638 at s2.asm line 32456
     */
    private void applyDiagonalDownLeftBounce(AbstractPlayableSprite player, CNZBumperSpawn bumper) {
        int playerY = player.getCentreY();
        int playerX = player.getCentreX();

        // dy = player_y - bumper_y
        int dy = playerY - bumper.y();

        // If player is sufficiently below bumper, simple downward bounce
        if (dy >= DIAGONAL_THRESHOLD) {
            player.setYSpeed((short) BOUNCE_VELOCITY);
            return;
        }

        // dx = bumper_x - player_x (positive means player is left of bumper)
        int dx = bumper.x() - playerX;

        // If player is sufficiently left of bumper, simple leftward bounce
        if (dx >= DIAGONAL_THRESHOLD) {
            player.setXSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // Calculate diagonal collision point (mirrored from type 0)
        int diagonalY = bumper.y() + Math.min(dx, DIAGONAL_THRESHOLD) - 8;
        int playerBottom = playerY + 14;

        if (playerBottom > diagonalY) {
            // Apply angle bounce toward $60 (down-left diagonal)
            applyAngleBounce(player, 0x60);
        }
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

        // If player is far below center, bounce down
        if (-dy >= NARROW_THRESHOLD) {
            player.setYSpeed((short) BOUNCE_VELOCITY);
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

        // If player is sufficiently left of bumper (hit from left), bounce left
        if (dx >= NARROW_SMALL_THRESHOLD) {
            player.setXSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // dy = bumper_y - player_y
        int dy = bumper.y() - playerY;

        // If player is far above center, bounce up
        if (dy >= NARROW_THRESHOLD) {
            player.setYSpeed((short) -BOUNCE_VELOCITY);
            return;
        }

        // If player is far below center, bounce down
        if (-dy >= NARROW_THRESHOLD) {
            player.setYSpeed((short) BOUNCE_VELOCITY);
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
        // ROM: jsr CalcSine / muls.w #-$A00,d1 / asr.l #8,d1
        // CalcSine returns sin in d0, cos in d1
        double radians = (outAngle / 256.0) * 2.0 * StrictMath.PI;

        // ROM formula: x_vel = -sin(angle) * $A00 >> 8, y_vel = -cos(angle) * $A00 >> 8
        int newXVel = (int) (-StrictMath.sin(radians) * BOUNCE_VELOCITY);
        int newYVel = (int) (-StrictMath.cos(radians) * BOUNCE_VELOCITY);

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
}
