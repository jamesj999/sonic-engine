package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Buzzer (0x4B) - Flying wasp/bee Badnik from EHZ.
 * Flies horizontally, checks for player in firing range, fires projectiles.
 * Based on disassembly Obj4B.
 */
public class BuzzerBadnikInstance extends AbstractBadnikInstance {
    private static final int COLLISION_SIZE_INDEX = 0x0A; // collision_flags from disassembly

    // Movement constants from disassembly
    private static final int X_VEL = 0x100; // x_vel = -$100 or +$100
    private static final int MOVE_TIMER_INIT = 0x100; // move_timer initial value
    private static final int TURN_DELAY = 0x1E; // turn delay frames (30)

    // Shooting constants
    private static final int SHOOT_DISTANCE_MIN = 0x28; // ~40 pixels
    private static final int SHOOT_DISTANCE_MAX = 0x30; // ~48 pixels
    private static final int SHOT_TIMER_INIT = 0x32; // 50 frames total
    private static final int SHOT_FIRE_FRAME = 0x14; // Fire at 20 frames remaining

    private enum State {
        ROAMING,
        SHOOTING
    }

    private State state;
    private int moveTimer;
    private int turnDelay;
    private int shotTimer;
    private boolean shootingDisabled;

    public BuzzerBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "Buzzer");
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        // Initial direction based on render flags (like disassembly)
        this.facingLeft = (spawn.renderFlags() & 0x01) != 0;
        // facingLeft = true means moving left (negative X), so xVelocity should be
        // negative
        this.xVelocity = facingLeft ? -X_VEL : X_VEL;

        this.state = State.ROAMING;
        this.moveTimer = MOVE_TIMER_INIT;
        this.turnDelay = -1; // Not turning
        this.shotTimer = 0;
        this.shootingDisabled = false;
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        switch (state) {
            case ROAMING:
                updateRoaming(player);
                break;
            case SHOOTING:
                updateShooting();
                break;
        }
    }

    private void updateRoaming(AbstractPlayableSprite player) {
        // Check if player is in shooting range
        checkPlayerForShooting(player);

        // Handle turn delay
        turnDelay--;
        if (turnDelay == 0x0F) {
            // Turn around
            shootingDisabled = false;
            xVelocity = -xVelocity;
            facingLeft = !facingLeft;
            moveTimer = MOVE_TIMER_INIT;
        } else if (turnDelay < 0) {
            // Normal movement
            moveTimer--;
            if (moveTimer > 0) {
                // Move horizontally
                currentX += (xVelocity >> 8);
            } else {
                // Start turn delay
                turnDelay = TURN_DELAY;
            }
        }
    }

    private void checkPlayerForShooting(AbstractPlayableSprite player) {
        if (shootingDisabled || player == null) {
            return;
        }

        int distance = currentX - player.getCentreX();
        int absDistance = Math.abs(distance);

        // Player must be in narrow strip (40-48 pixels away)
        if (absDistance < SHOOT_DISTANCE_MIN || absDistance > SHOOT_DISTANCE_MAX) {
            return;
        }

        // Check if we're facing the player
        // If distance > 0, player is to the left, so we need to be facing left
        // If distance < 0, player is to the right, so we need to be facing right
        boolean playerIsLeft = distance > 0;
        if (playerIsLeft != facingLeft) {
            return; // Not facing player, don't shoot
        }

        // Ready to shoot!
        shootingDisabled = true;
        state = State.SHOOTING;
        shotTimer = SHOT_TIMER_INIT;
    }

    private void updateShooting() {
        shotTimer--;

        if (shotTimer < 0) {
            // Done shooting, return to roaming
            state = State.ROAMING;
        } else if (shotTimer == SHOT_FIRE_FRAME) {
            // Fire the projectile
            fireProjectile();
        }
    }

    private void fireProjectile() {
        // From disassembly: y_vel = $180, x_vel = -$180 (or +$180 if facing left)
        // Y offset: +$18, X offset: +/-$0D
        int xOffset = facingLeft ? -0x0D : 0x0D;
        int yOffset = 0x18;

        // Velocity in subpixels (shift left 8 for subpixel units)
        int yVel = 0x180;
        int xVel = facingLeft ? -0x180 : 0x180;

        BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.BUZZER_STINGER,
                currentX + xOffset,
                currentY + yOffset,
                xVel,
                yVel,
                false, // No gravity for Buzzer stinger
                !facingLeft);

        LevelManager.getInstance().getObjectManager().addDynamicObject(projectile);
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        // Animation from disassembly:
        // anim 0 = Flying (frame 0, slow)
        // anim 3 = Shooting (frame 1 repeated)
        if (state == State.SHOOTING) {
            animFrame = 1; // Shooting frame
        } else {
            animFrame = 0; // Flying frame
        }
    }

    @Override
    protected int getCollisionSizeIndex() {
        return COLLISION_SIZE_INDEX;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
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

        PatternSpriteRenderer renderer = renderManager.getBuzzerRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Sprite art faces right by default, so flip when NOT facing left (inverted)
        renderer.drawFrameIndex(animFrame, currentX, currentY, !facingLeft, false);
    }
}
