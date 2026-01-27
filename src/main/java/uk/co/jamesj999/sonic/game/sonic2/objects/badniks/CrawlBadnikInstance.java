package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.TouchResponseResult;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Crawl (0xC8) - Bouncer Badnik from Casino Night Zone.
 * <p>
 * A unique "bouncer badnik" that acts as a mobile bumper. It walks back and forth,
 * and when the player rolls into its shield (on the SAME side as facing direction),
 * it bounces them away like a bumper. When hit from behind (the opposite direction),
 * it's vulnerable and can be destroyed.
 * <p>
 * <b>Disassembly Reference:</b> s2.asm lines 81770-81967
 * <ul>
 *   <li>ObjC8_Init (Routine 0): line 81804 - initialization</li>
 *   <li>ObjC8_Walking (Routine 2): line 81836 - walking state</li>
 *   <li>ObjC8_Pausing (Routine 4): line 81882 - pause state</li>
 *   <li>ObjC8_Attacking (Routine 6): line 81896 - bumper collision mode</li>
 * </ul>
 *
 * <h3>ROM Constants</h3>
 * <table border="1">
 *   <tr><th>Property</th><th>Value</th><th>ROM Reference</th></tr>
 *   <tr><td>Object ID</td><td>0xC8</td><td>ObjPtr_Crawl</td></tr>
 *   <tr><td>Walk Velocity</td><td>$20 (32)</td><td>line 81826</td></tr>
 *   <tr><td>Walk Duration</td><td>$200 (512 frames)</td><td>line 81828</td></tr>
 *   <tr><td>Pause Duration</td><td>$3B (59 frames)</td><td>line 81874</td></tr>
 *   <tr><td>Bounce Velocity</td><td>$700 (1792)</td><td>line 81944</td></tr>
 *   <tr><td>Collision Flags</td><td>$D7 (attack) / $17 (vulnerable)</td><td>lines 81820, 81960</td></tr>
 *   <tr><td>y_radius</td><td>$0F (15 px)</td><td>line 81812</td></tr>
 *   <tr><td>x_radius</td><td>$10 (16 px)</td><td>line 81814</td></tr>
 *   <tr><td>Sound</td><td>SndID_Bumper (0xB4)</td><td>line 81938</td></tr>
 * </table>
 *
 * <h3>Shield/Bumper Mechanics</h3>
 * Crawl has a shield on its FRONT. Collision behavior depends on attack direction:
 * <ul>
 *   <li>Player rolling into FRONT (shield): Bumper bounce, plays 0xB4</li>
 *   <li>Player rolling into BACK: Vulnerable, destroyed like normal badnik</li>
 *   <li>Player in air (rolling jump): Always bounces (shield active all directions)</li>
 *   <li>Player not rolling: Standard enemy collision (can hurt player)</li>
 * </ul>
 *
 * <h3>Animation Frames</h3>
 * <ul>
 *   <li>Frame 0: Walking pose 1</li>
 *   <li>Frame 1: Walking pose 2</li>
 *   <li>Frame 2: Impact (player on ground)</li>
 *   <li>Frame 3: Impact (player in air)</li>
 * </ul>
 */
public class CrawlBadnikInstance extends AbstractBadnikInstance {

    // ========================================================================
    // ROM Constants
    // ========================================================================

    /** Walk velocity = $20 (32 subpixels/frame) */
    private static final int WALK_VELOCITY = 0x20;

    /** Walk duration = $200 (512 frames, ~8.5 seconds) */
    private static final int WALK_DURATION = 0x200;

    /** Pause duration = $3B (59 frames, ~1 second) */
    private static final int PAUSE_DURATION = 0x3B;

    /** Bounce velocity magnitude = $700 (1792 in 8.8 fixed point) */
    private static final int BOUNCE_VELOCITY = 0x700;

    /** Collision size index 0x09 (from disassembly pattern) */
    private static final int COLLISION_SIZE_INDEX = 0x09;

    /** Animation delay for walking (frames per animation tick) */
    private static final int ANIM_DELAY = 0x13; // 19 frames

    // ========================================================================
    // Animation Frames
    // ========================================================================

    private static final int FRAME_WALK_1 = 0;
    private static final int FRAME_WALK_2 = 1;
    private static final int FRAME_IMPACT_GROUND = 2;
    private static final int FRAME_IMPACT_AIR = 3;

    // ========================================================================
    // States
    // ========================================================================

    private enum State {
        WALKING,    // Routine 2 - normal walking
        PAUSING,    // Routine 4 - stopped, waiting to reverse
        ATTACKING   // Routine 6 - bumper collision mode (when player approaches)
    }

    // ========================================================================
    // Instance State
    // ========================================================================

    private State state;
    private State previousState;     // Saved state for restoration (ROM: objoff_2C)
    private int walkTimer;           // Frames until pause
    private int pauseTimer;          // Frames until resume walking
    private int impactTimer;         // Frames showing impact animation
    private int xSubpixel;           // Subpixel accumulator for smooth movement
    private boolean playerApproaching; // True when player is near and rolling
    private boolean vulnerable;      // True when hit from back (collision_flags = $17)

    public CrawlBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "Crawl");
        this.state = State.WALKING;
        this.previousState = State.WALKING;
        this.walkTimer = WALK_DURATION;
        this.pauseTimer = 0;
        this.impactTimer = 0;
        this.xSubpixel = 0;
        this.playerApproaching = false;
        this.vulnerable = false;

        // Initial facing based on x_flip spawn flag
        // x_flip=1 (bit 0 set) means facing RIGHT
        boolean xFlip = (spawn.renderFlags() & 0x01) != 0;
        this.facingLeft = !xFlip;

        // Set initial velocity based on facing direction
        xVelocity = facingLeft ? -WALK_VELOCITY : WALK_VELOCITY;
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        // Check if player is approaching (to switch to attack mode)
        checkPlayerProximity(player);

        switch (state) {
            case WALKING -> updateWalking(frameCounter);
            case PAUSING -> updatePausing();
            case ATTACKING -> updateAttacking(frameCounter, player);
        }

        // Decrement impact timer
        if (impactTimer > 0) {
            impactTimer--;
        }
    }

    /**
     * Check if player is approaching and should trigger attack mode.
     * ROM: loc_3D416 in ObjC8_Walking - saves current routine before switching to attack.
     * ROM: loc_3D39A in ObjC8_Attacking - restores previous routine when out of range.
     */
    private void checkPlayerProximity(AbstractPlayableSprite player) {
        if (player == null) {
            playerApproaching = false;
            return;
        }

        // Simple proximity check - if player is within reasonable range
        int dx = Math.abs(player.getCentreX() - currentX);
        int dy = Math.abs(player.getCentreY() - currentY);

        // Player must be close and rolling (or in air rolling)
        boolean isRolling = player.getRolling() || player.getRollingJump();
        playerApproaching = dx < 64 && dy < 48 && isRolling;

        // Switch to attacking mode when player approaches
        // ROM: move.b routine(a0),objoff_2C(a0) - save current routine
        if (playerApproaching && state != State.ATTACKING) {
            previousState = state;  // Save current state (WALKING or PAUSING)
            state = State.ATTACKING;
        } else if (!playerApproaching && state == State.ATTACKING) {
            // ROM: move.b objoff_2C(a0),routine(a0) - restore previous routine
            state = previousState;  // Restore saved state
        }
    }

    /**
     * Walking state (Routine 2):
     * - Move horizontally at walk speed
     * - Count down until pause
     */
    private void updateWalking(int frameCounter) {
        // Apply velocity using subpixel accumulator for smooth movement
        xSubpixel += Math.abs(xVelocity);
        if (xSubpixel >= 0x100) {
            int pixels = xSubpixel >> 8;
            xSubpixel &= 0xFF;
            if (facingLeft) {
                currentX -= pixels;
            } else {
                currentX += pixels;
            }
        }

        // Decrement walk timer
        walkTimer--;
        if (walkTimer <= 0) {
            // Transition to pause state
            state = State.PAUSING;
            pauseTimer = PAUSE_DURATION;
            xVelocity = 0;
        }
    }

    /**
     * Pausing state (Routine 4):
     * - Stop movement
     * - Wait for pause duration
     * - Then reverse direction
     */
    private void updatePausing() {
        pauseTimer--;
        if (pauseTimer <= 0) {
            // Reverse direction
            facingLeft = !facingLeft;
            xVelocity = facingLeft ? -WALK_VELOCITY : WALK_VELOCITY;
            walkTimer = WALK_DURATION;
            state = State.WALKING;
        }
    }

    /**
     * Attacking state (Routine 6):
     * - Stop movement (stay in place)
     * - Collision handling will bounce or destroy based on direction
     */
    private void updateAttacking(int frameCounter, AbstractPlayableSprite player) {
        // In attack mode, Crawl stops and waits
        // Collision handling is done through onPlayerAttack
    }

    @Override
    public void onPlayerAttack(AbstractPlayableSprite player, TouchResponseResult result) {
        if (destroyed || player == null) {
            return;
        }

        // ROM Reference: lines 81847-81876
        // Player must be in Roll animation for any special interaction
        boolean isRolling = player.getRolling() || player.getRollingJump();
        boolean inAir = player.getAir();

        if (!isRolling) {
            // Not rolling - standard enemy collision ($97)
            // Touch response system handles hurting the player
            return;
        }

        // ROM lines 81849-81850: If rolling AND in air, ALWAYS bounce (no direction check)
        // This includes jumping on top, jump attacks from any direction, etc.
        if (inAir) {
            applyBounce(player, FRAME_IMPACT_AIR);
            return;
        }

        // ROM lines 81851-81857: Rolling on ground - direction check determines outcome
        // Obj_GetOrientationToPlayer returns d0=0 if player LEFT, d0=2 if RIGHT
        // If x_flip set (facing RIGHT), subtract 2 from d0
        // If d0=0 after adjustment → BOUNCE (shield side)
        // If d0≠0 → VULNERABLE (back side, collision_flags = $17)
        boolean playerToLeft = player.getCentreX() < currentX;

        // Shield is on the SAME side as the facing direction:
        // - facingLeft=true (x_flip=0): shield is on LEFT, player to left = bounce
        // - facingLeft=false (x_flip=1): shield is on RIGHT, player to right = bounce
        boolean hittingFromFront;
        if (facingLeft) {
            hittingFromFront = playerToLeft;
        } else {
            hittingFromFront = !playerToLeft;
        }

        if (hittingFromFront) {
            // Shield side - bounce player away (radial bounce)
            applyBounce(player, FRAME_IMPACT_GROUND);
        } else {
            // Back side - vulnerable, destroy like normal badnik
            vulnerable = true;
            destroyBadnik(player);
        }
    }

    /**
     * Apply radial bounce to player (similar to BumperObjectInstance).
     * ROM Reference: lines 81930-81954
     */
    private void applyBounce(AbstractPlayableSprite player, int impactFrame) {
        // Calculate direction from Crawl to player
        int dx = currentX - player.getCentreX();
        int dy = currentY - player.getCentreY();

        // Calculate angle
        double angle = StrictMath.atan2(dy, dx);

        // If player is exactly at center, push them away from front
        if (dx == 0 && dy == 0) {
            angle = facingLeft ? Math.PI : 0;
        }

        // Add wobble based on frame counter (ROM: add (Timer_frames & 3) to angle)
        // Using a simple counter here
        double wobble = (animTimer & 3) * (2.0 * StrictMath.PI / 256.0);
        angle += wobble;

        // Calculate velocity components
        // ROM: vel = -sin/cos(angle) * $700 >> 8
        int xVel = (int) (-StrictMath.sin(angle) * BOUNCE_VELOCITY);
        int yVel = (int) (-StrictMath.cos(angle) * BOUNCE_VELOCITY);

        // Apply to player
        player.setXSpeed((short) xVel);
        player.setYSpeed((short) yVel);

        // Set player to airborne state
        player.setAir(true);
        player.setPushing(false);
        player.setGSpeed((short) 0);

        // Show impact animation
        animFrame = impactFrame;
        impactTimer = 16;

        // Play bumper sound
        AudioManager.getInstance().playSfx(Sonic2AudioConstants.SFX_BUMPER);
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        // If showing impact animation, keep that frame
        if (impactTimer > 0) {
            return; // animFrame already set by applyBounce
        }

        // Walking animation - alternate between frames 0 and 1
        animTimer++;
        if (animTimer >= ANIM_DELAY) {
            animTimer = 0;
            animFrame = (animFrame == FRAME_WALK_1) ? FRAME_WALK_2 : FRAME_WALK_1;
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CRAWL);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Art faces left by default; flip when facing right
        boolean hFlip = !facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
