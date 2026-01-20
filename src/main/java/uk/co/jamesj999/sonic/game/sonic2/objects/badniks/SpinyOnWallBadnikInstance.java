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
 * SpinyOnWall (0xA6) - Wall-climbing variant of Spiny Badnik from CPZ.
 * Patrols vertically on walls and fires spike projectiles horizontally.
 *
 * Behavior from s2.asm disassembly (ObjA6_WallType):
 * - Patrol: Moves at y_vel = -0x40, reverses every 0x80 frames (128)
 * - Detection: Checks angle to player, attacks if within 0x60-0xC0 range
 * - Attack: Timer 0x28 (40 frames), fires at 0x14 (20 remaining), lockout 0x40 (64)
 * - Spike fire: Horizontal (x_vel = 0x300 or -0x300) based on facing direction
 *
 * Uses the same art as Spiny (ArtNem_Spiny) but different animation frames:
 * - Frames 3-4: Wall climbing (patrol)
 * - Frame 5: Attack pose
 * - Frames 6-7: Spike projectile
 */
public class SpinyOnWallBadnikInstance extends AbstractBadnikInstance {
    private static final int COLLISION_SIZE_INDEX = 0x0B;

    // Movement constants
    private static final int Y_VEL = 0x40;        // Movement speed (subpixels)
    private static final int MOVE_TIMER = 0x80;   // Frames before reversing (128)

    // Attack constants
    private static final int ATTACK_TIMER = 0x28;   // Attack duration (40 frames)
    private static final int FIRE_FRAME = 0x14;     // Fire at this remaining (20 frames)
    private static final int ATTACK_LOCKOUT = 0x40; // Cooldown after attack (64 frames)

    // Detection range (simplified from angle-based detection)
    private static final int DETECT_X_RANGE = 0x80; // Horizontal detection range
    private static final int DETECT_Y_RANGE = 0x40; // Vertical detection range

    // Projectile constants - horizontal only for wall variant
    private static final int SPIKE_X_VEL = 0x300;   // Horizontal spike velocity
    private static final int SPIKE_Y_VEL = 0;       // No vertical component

    // Animation constants
    private static final int CLIMB_ANIM_DELAY = 9;  // 9-frame delay between climbing frames

    private enum State {
        PATROLLING,
        ATTACKING
    }

    private State state;
    private int moveCounter;      // Frames until direction reversal
    private int attackTimer;      // Attack state timer
    private int attackLockout;    // Frames until can attack again
    private boolean movingUp;     // Current movement direction (vertical)
    private boolean hasFired;     // Whether spike has been fired this attack
    private int ySubpixel;        // Subpixel accumulator for smooth movement

    public SpinyOnWallBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "SpinyOnWall");
        this.state = State.PATROLLING;
        this.moveCounter = MOVE_TIMER;
        this.attackTimer = 0;
        this.attackLockout = 0;
        this.movingUp = true;      // Start moving up (negative Y)
        this.hasFired = false;
        this.ySubpixel = 0;
        // Preserve spawn's render_flags for initial facing direction (x_flip bit)
        this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
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
        // Y_VEL = 0x40 = 64 subpixels per frame = 0.25 pixels per frame
        ySubpixel += Y_VEL;
        while (ySubpixel >= 256) {
            ySubpixel -= 256;
            if (movingUp) {
                currentY--;
            } else {
                currentY++;
            }
        }

        // Decrement move counter
        moveCounter--;
        if (moveCounter <= 0) {
            // Reverse direction
            movingUp = !movingUp;
            moveCounter = MOVE_TIMER;
        }

        // Decrement attack lockout
        if (attackLockout > 0) {
            attackLockout--;
        }

        // Check for player in attack range
        if (player != null && attackLockout == 0 && isPlayerInRange(player)) {
            startAttack();
        }
    }

    private void updateAttacking(AbstractPlayableSprite player) {
        attackTimer--;

        // Fire spike at the right moment
        if (!hasFired && attackTimer <= FIRE_FRAME) {
            fireSpike();
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

        // Player must be roughly in front of SpinyOnWall (not behind)
        boolean playerIsLeft = player.getCentreX() < currentX;
        return playerIsLeft == facingLeft;
    }

    private void startAttack() {
        state = State.ATTACKING;
        attackTimer = ATTACK_TIMER;
        hasFired = false;
        // Unlike regular Spiny, SpinyOnWall does NOT turn to face player
        // It fires in its current facing direction
    }

    private void fireSpike() {
        // Fire based on facing direction, not player position
        int xVel = facingLeft ? -SPIKE_X_VEL : SPIKE_X_VEL;

        BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.SPINY_SPIKE,
                currentX + (facingLeft ? -8 : 8),  // Offset from body in firing direction
                currentY,
                xVel,
                SPIKE_Y_VEL,
                true,   // Apply gravity after firing
                false   // No initial flip
        );

        LevelManager.getInstance().getObjectManager().addDynamicObject(projectile);
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        if (state == State.ATTACKING) {
            // Attack pose (frame 5) for wall variant
            animFrame = 5;
        } else {
            // Wall-climbing animation (frames 3-4, 9-frame delay)
            animFrame = 3 + ((frameCounter / CLIMB_ANIM_DELAY) & 1);
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

        // Flip sprite based on facing direction
        // Default sprite has body on left, legs on right (for LEFT wall, facing RIGHT)
        // When facingLeft=true (on RIGHT wall, facing LEFT), we need to H-flip
        boolean hFlip = facingLeft;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
