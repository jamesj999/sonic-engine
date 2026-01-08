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
 * Coconuts (0x9D) - Monkey Badnik from EHZ.
 * Climbs up and down a tree and throws coconut projectiles at the player.
 */
public class CoconutsBadnikInstance extends AbstractBadnikInstance {
    private static final int COLLISION_SIZE_INDEX = 0x0B;
    private static final int CLIMB_SPEED = 1; // Pixels per frame
    private static final int CLIMB_RANGE = 64; // Distance to climb up/down
    private static final int THROW_INTERVAL = 120; // Frames between throws
    private static final int ANIM_SPEED = 8;

    private final int baseY;
    private int climbDirection; // 1 = down, -1 = up
    private int throwTimer;

    public CoconutsBadnikInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, levelManager, "Coconuts");
        this.baseY = spawn.y();
        this.currentY = baseY;
        this.currentX = spawn.x();
        this.climbDirection = -1; // Start climbing up
        this.throwTimer = THROW_INTERVAL;
    }

    @Override
    protected void updateMovement(int frameCounter, AbstractPlayableSprite player) {
        // Climb up and down
        currentY += climbDirection * CLIMB_SPEED;

        // Check if we've reached the climbing limits
        if (climbDirection < 0 && currentY <= baseY - CLIMB_RANGE) {
            // Reached top, start going down
            climbDirection = 1;
        } else if (climbDirection > 0 && currentY >= baseY) {
            // Reached bottom, start going up
            climbDirection = -1;
        }

        // Throw projectiles periodically
        throwTimer--;
        if (throwTimer <= 0 && player != null) {
            throwCoconut(player);
            throwTimer = THROW_INTERVAL;
        }

        // X position stays fixed
        currentX = spawn.x();
    }

    private void throwCoconut(AbstractPlayableSprite player) {
        // Coconut arcs toward player
        int xDiff = player.getCentreX() - currentX;
        int xVel = xDiff > 0 ? 0x100 : -0x100; // Throw toward player
        int yVel = -0x200; // Throw upward, gravity will arc it down
        boolean hFlip = xDiff < 0;

        BadnikProjectileInstance projectile = new BadnikProjectileInstance(
                spawn,
                BadnikProjectileInstance.ProjectileType.COCONUT,
                currentX + (hFlip ? -8 : 8),
                currentY - 8,
                xVel,
                yVel,
                true, // Gravity affects coconuts
                hFlip);

        LevelManager.getInstance().getObjectManager().addDynamicObject(projectile);
    }

    @Override
    protected void updateAnimation(int frameCounter) {
        // Alternate between climbing frames 0 and 1
        // Frame 2 is used when throwing
        if (throwTimer > THROW_INTERVAL - 10) {
            animFrame = 2; // Throwing frame
        } else {
            animFrame = ((frameCounter / ANIM_SPEED) & 1);
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

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getCoconutsRenderer();
        if (renderer == null || !renderer.isReady()) {
            return;
        }

        // Coconuts always faces right (toward player typically)
        boolean hFlip = false;
        boolean vFlip = false;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, vFlip);
    }
}
