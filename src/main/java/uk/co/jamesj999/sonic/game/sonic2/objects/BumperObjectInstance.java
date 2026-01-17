package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Round Bumper Object (Obj44).
 * <p>
 * Bounces the player radially away from the bumper center when contacted.
 * Uses omnidirectional radial bounce physics with a slight wobble effect.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 44604-44706
 * <ul>
 *   <li>Obj44_Init: line 44616</li>
 *   <li>Obj44_Main: line 44632</li>
 *   <li>Obj44_BumpCharacter: line 44649</li>
 * </ul>
 *
 * <h3>ROM Constants</h3>
 * <table border="1">
 *   <tr><th>Property</th><th>Value</th><th>ROM Reference</th></tr>
 *   <tr><td>Object ID</td><td>0x44</td><td>ObjPtr_RoundBumper</td></tr>
 *   <tr><td>Bounce Velocity</td><td>$700 (1792)</td><td>line 44689</td></tr>
 *   <tr><td>Collision Flags</td><td>$D7</td><td>line 44624</td></tr>
 *   <tr><td>Collision Box</td><td>12x24 pixels</td><td>Touch_Sizes[0x17]</td></tr>
 *   <tr><td>width_pixels</td><td>$10 (16 px)</td><td>line 44622</td></tr>
 *   <tr><td>Sound</td><td>SndID_Bumper (0xB4)</td><td>line 44665</td></tr>
 *   <tr><td>Points</td><td>10</td><td>AddPoints2 with d0=1</td></tr>
 * </table>
 *
 * <h3>Art Data</h3>
 * <ul>
 *   <li>Art: ArtNem_CNZRoundBumper (art/nemesis/Round bumper from CNZ.nem)</li>
 *   <li>Mappings: Obj44_MapUnc_1F85A (mappings/sprite/obj44.asm)</li>
 *   <li>Frame 0: 2 pieces, 32x32 px (idle)</li>
 *   <li>Frame 1: 4 pieces, 48x52 px (hit animation)</li>
 * </ul>
 *
 * <h3>Physics (Obj44_BumpCharacter, line 44669)</h3>
 * <pre>
 * ; Calculate angle from bumper to player
 * angle = CalcAngle(bumper_x - player_x, bumper_y - player_y)
 *
 * ; Add wobble based on frame counter
 * angle += (Timer_frames &amp; 3)
 *
 * ; Apply velocity in calculated direction
 * x_vel = -sin(angle) * $700 >> 8
 * y_vel = -cos(angle) * $700 >> 8
 * </pre>
 *
 * <h3>Note on CNZ Map Bumpers</h3>
 * This object is distinct from the CNZ map-level bumper system (Check_CNZ_bumpers
 * at s2.asm line 32146) which uses $A00 velocity and SndID_LargeBumper sound.
 * The map bumpers are baked into level data files, not placed as objects.
 *
 * @see HexBumperObjectInstance Hex bumper with 4-direction quantized physics
 * @see BonusBlockObjectInstance Drop target with hit tracking
 */
public class BumperObjectInstance extends AbstractObjectInstance {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /**
     * Bounce velocity magnitude = $700 (1792 in 8.8 fixed point).
     * <p>
     * ROM Reference: s2.asm line 44689
     * <p>
     * Note: This differs from CNZ map bumpers which use $A00 (2560).
     */
    private static final int BOUNCE_VELOCITY = 0x700;

    /**
     * Collision box half-width = 8 pixels.
     * <p>
     * ROM Reference: Touch_Sizes[0x17] at s2.asm line 84574
     * (collision_flags $D7 -> index 0x17 -> 8,8 = 16x16 total box)
     */
    private static final int COLLISION_HALF_WIDTH = 8;

    /**
     * Collision box half-height = 8 pixels.
     * <p>
     * ROM Reference: Touch_Sizes[0x17] at s2.asm line 84574
     */
    private static final int COLLISION_HALF_HEIGHT = 8;

    // ========================================================================
    // Animation Constants
    // ========================================================================

    /** Frame 0: Idle state */
    private static final int FRAME_IDLE = 0;

    /** Frame 1: Hit/compressed state */
    private static final int FRAME_HIT = 1;

    /** Duration of hit animation in frames */
    private static final int ANIM_DURATION = 8;

    /** Cooldown frames after bounce to prevent repeated hits */
    private static final int BOUNCE_COOLDOWN = 8;

    // ========================================================================
    // Instance State
    // ========================================================================

    private int animFrame = FRAME_IDLE;
    private int animTimer = 0;
    private int bounceCooldown = 0;

    public BumperObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Return to idle frame after animation
        if (animTimer > 0) {
            animTimer--;
            if (animTimer == 0) {
                animFrame = FRAME_IDLE;
            }
        }

        // Update bounce cooldown
        if (bounceCooldown > 0) {
            bounceCooldown--;
        }

        // Check collision with player (only if not on cooldown)
        if (player != null && !player.isHurt() && !player.getDead() && bounceCooldown == 0) {
            if (checkCollision(player)) {
                applyBounce(player, frameCounter);
            }
        }
    }

    /**
     * Check collision using rectangular hitbox.
     * <p>
     * ROM uses collision_flags $D7 which maps to Touch_Sizes[0x17].
     * Touch_Sizes values are half-width, half-height = 8, 8 = 16x16 total box.
     * <p>
     * Note: Player getX()/getY() returns top-left corner, so we use getCentreX()/getCentreY()
     * to compare with the bumper's center position (spawn.x(), spawn.y()).
     */
    private boolean checkCollision(AbstractPlayableSprite player) {
        int dx = Math.abs(player.getCentreX() - spawn.x());
        int dy = Math.abs(player.getCentreY() - spawn.y());

        int playerHalfWidth = 8; // Approximate player half-width
        int playerHalfHeight = player.getYRadius();

        return dx < (COLLISION_HALF_WIDTH + playerHalfWidth) &&
               dy < (COLLISION_HALF_HEIGHT + playerHalfHeight);
    }

    /**
     * Apply radial bounce to player with wobble effect.
     * <p>
     * ROM Reference: Obj44_BumpCharacter at s2.asm line 44669
     * <p>
     * Physics:
     * <ol>
     *   <li>Calculate angle from bumper center to player center</li>
     *   <li>Add (frameCounter &amp; 3) for wobble variation</li>
     *   <li>Apply velocity in that direction with magnitude $700</li>
     *   <li>Set player to airborne state</li>
     * </ol>
     *
     * @param player The player sprite to bounce
     * @param frameCounter Current frame counter for wobble calculation
     */
    private void applyBounce(AbstractPlayableSprite player, int frameCounter) {
        // Calculate direction from bumper to player center
        // ROM: CalcAngle(bumper_x - player_x, bumper_y - player_y)
        int dx = spawn.x() - player.getCentreX();
        int dy = spawn.y() - player.getCentreY();

        // Calculate angle in radians
        double angle = Math.atan2(dy, dx);

        // If player is exactly at center, push them up
        if (dx == 0 && dy == 0) {
            angle = Math.PI / 2; // 90 degrees up
        }

        // Add wobble: ROM adds (Timer_frames & 3) to the 0-255 angle
        // Convert to radians: (frameCounter & 3) * (2*PI/256)
        double wobble = (frameCounter & 3) * (2.0 * Math.PI / 256.0);
        angle += wobble;

        // Calculate velocity components
        // ROM: x_vel = -sin(angle) * $700 >> 8
        // ROM: y_vel = -cos(angle) * $700 >> 8
        // Note: ROM uses CalcSine which returns (sin, cos) in (d0, d1)
        // We negate because we want to push AWAY from bumper
        int xVel = (int) (-Math.cos(angle) * BOUNCE_VELOCITY);
        int yVel = (int) (-Math.sin(angle) * BOUNCE_VELOCITY);

        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);

        // ROM: Set player state
        // bset #status.player.in_air,status(a0)
        // bclr #status.player.rolljumping,status(a0)
        // bclr #status.player.pushing,status(a0)
        // clr.b jumping(a0)
        player.setAir(true);
        player.setPushing(false);
        player.setGSpeed((short) 0);

        // Trigger animation and cooldown
        animFrame = FRAME_HIT;
        animTimer = ANIM_DURATION;
        bounceCooldown = BOUNCE_COOLDOWN;

        // Play sound
        AudioManager.getInstance().playSfx(GameSound.BUMPER);

        // TODO: Award points (10 points via AddPoints2 with d0=1)
        // TODO: Increment respawn table counter
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager rm = LevelManager.getInstance().getObjectRenderManager();
        if (rm == null) {
            return;
        }

        PatternSpriteRenderer renderer = rm.getBumperRenderer();
        if (renderer != null && renderer.isReady()) {
            boolean hFlip = (spawn.renderFlags() & 0x1) != 0;
            boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
            renderer.drawFrameIndex(animFrame, spawn.x(), spawn.y(), hFlip, vFlip);
        }
    }
}
