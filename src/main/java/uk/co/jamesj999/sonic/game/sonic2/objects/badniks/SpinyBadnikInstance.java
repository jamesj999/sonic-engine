package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Spiny (0xA5) - Crawling caterpillar Badnik from CPZ.
 * Patrols back and forth and fires spike projectiles at the player.
 *
 * Behavior from s2.asm disassembly:
 * - Patrol: Moves at x_vel = -0x40, reverses every 0x80 frames (128)
 * - Detection: Checks angle to player, attacks if within 0x60-0xC0 range
 * - Attack: Timer 0x28 (40 frames), fires at 0x14 (20 remaining), lockout 0x40 (64)
 */
public class SpinyBadnikInstance extends AbstractBadnikInstance {
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // Movement constants
    private static final int X_VEL = 0x40;        // Movement speed (subpixels)
    private static final int MOVE_TIMER = 0x80;   // Frames before reversing (128)

    // Attack constants
    private static final int ATTACK_TIMER = 0x28;   // Attack duration (40 frames)
    private static final int FIRE_FRAME = 0x14;     // Fire at this remaining (20 frames)
    private static final int ATTACK_LOCKOUT = 0x40; // Cooldown after attack (64 frames)

    // Detection range (simplified from angle-based detection)
    private static final int DETECT_X_RANGE = 0x80; // Horizontal detection range
    private static final int DETECT_Y_RANGE = 0x40; // Vertical detection range

    // Projectile constants
    private static final int SPIKE_X_VEL = 0x100;   // +/-0x100 toward player
    private static final int SPIKE_Y_VEL = -0x300;  // Upward initial velocity

    // Animation constants
    private static final int CRAWL_ANIM_DELAY = 9;  // 9-frame delay between crawl frames

    private enum State {
        PATROLLING,
        ATTACKING
    }

    private State state;
    private int moveCounter;      // Frames until direction reversal
    private int attackTimer;      // Attack state timer
    private int attackLockout;    // Frames until can attack again
    private boolean movingLeft;   // Current movement direction
    private boolean hasFired;     // Whether spike has been fired this attack
    private int subPixelX;        // Subpixel accumulator for smooth movement

    public SpinyBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "Spiny");
        this.state = State.PATROLLING;
        this.moveCounter = MOVE_TIMER;
        this.attackTimer = 0;
        this.attackLockout = 0;
        this.movingLeft = true;      // Start moving left (like disassembly)
        this.hasFired = false;
        this.facingLeft = true;
        this.subPixelX = 0;          // Start with no subpixel offset
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        switch (state) {
            case PATROLLING -> updatePatrolling(player);
            case ATTACKING -> updateAttacking(player);
        }
    }

    private void updatePatrolling(AbstractPlayableSprite player) {
        // Apply movement velocity using subpixel accumulation
        // X_VEL (0x40) is in 8.8 fixed point format (0.25 pixels per frame)
        if (movingLeft) {
            subPixelX -= X_VEL;
        } else {
            subPixelX += X_VEL;
        }

        // Convert subpixel overflow to pixel movement
        while (subPixelX >= 0x100) {
            currentX++;
            subPixelX -= 0x100;
        }
        while (subPixelX <= -0x100) {
            currentX--;
            subPixelX += 0x100;
        }
        facingLeft = movingLeft;

        // Decrement move counter
        moveCounter--;
        if (moveCounter <= 0) {
            // Reverse direction
            movingLeft = !movingLeft;
            moveCounter = MOVE_TIMER;
        }

        // Decrement attack lockout
        if (attackLockout > 0) {
            attackLockout--;
        }

        // Check for player in attack range
        if (player != null && attackLockout == 0 && isPlayerInRange(player)) {
            startAttack(player);
        }
    }

    private void updateAttacking(AbstractPlayableSprite player) {
        attackTimer--;

        // Fire spike at the right moment
        if (!hasFired && attackTimer <= FIRE_FRAME) {
            fireSpike(player);
            hasFired = true;
        }

        // End attack when timer expires
        if (attackTimer <= 0) {
            state = State.PATROLLING;
            attackLockout = ATTACK_LOCKOUT;
            hasFired = false;
        }
    }

    /**
     * Checks if player is within attack range.
     * The original uses angle-based detection (0x60-0xC0 range).
     * We simplify to horizontal/vertical distance check.
     */
    private boolean isPlayerInRange(AbstractPlayableSprite player) {
        int dx = Math.abs(player.getCentreX() - currentX);
        int dy = Math.abs(player.getCentreY() - currentY);

        // Player must be within detection range
        if (dx > DETECT_X_RANGE || dy > DETECT_Y_RANGE) {
            return false;
        }

        // Player must be roughly in front of Spiny (not behind)
        boolean playerIsLeft = player.getCentreX() < currentX;
        return playerIsLeft == facingLeft;
    }

    private void startAttack(AbstractPlayableSprite player) {
        state = State.ATTACKING;
        attackTimer = ATTACK_TIMER;
        hasFired = false;

        // Face the player
        if (player != null) {
            facingLeft = player.getCentreX() < currentX;
        }
    }

    private void fireSpike(AbstractPlayableSprite player) {
        int xVel;
        if (player != null) {
            // Fire toward player
            xVel = (player.getCentreX() < currentX) ? -SPIKE_X_VEL : SPIKE_X_VEL;
        } else {
            // Default to facing direction
            xVel = facingLeft ? -SPIKE_X_VEL : SPIKE_X_VEL;
        }

        BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.SPINY_SPIKE,
                currentX,
                currentY - 8,  // Fire from top of Spiny
                xVel,
                SPIKE_Y_VEL,
                true,  // Apply gravity
                false  // No initial flip
        );

        LevelManager.getInstance().getObjectManager().addDynamicObject(projectile);
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        if (state == State.ATTACKING) {
            // Attack pose (frame 2)
            animFrame = 2;
        } else {
            // Crawling animation (frames 0-1, 9-frame delay)
            animFrame = ((frameCounter / CRAWL_ANIM_DELAY) & 1);
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(5);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.SPINY);
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Spiny is symmetrical, but we flip based on facing direction
        boolean hFlip = !facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
