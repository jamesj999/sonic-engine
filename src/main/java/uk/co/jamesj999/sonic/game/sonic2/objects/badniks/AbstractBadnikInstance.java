package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.TouchResponseAttackable;
import uk.co.jamesj999.sonic.level.objects.TouchResponseProvider;
import uk.co.jamesj999.sonic.level.objects.TouchResponseResult;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.game.sonic2.objects.ExplosionObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.AnimalObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.objects.badniks.PointsObjectInstance;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2Constants;

/**
 * Abstract base class for all Badnik enemies.
 * Provides common collision handling, destruction behavior, and helper methods
 * for AI.
 */
public abstract class AbstractBadnikInstance extends AbstractObjectInstance
        implements TouchResponseProvider, TouchResponseAttackable {

    protected int currentX;
    protected int currentY;
    protected int xVelocity;
    protected int yVelocity;
    protected int animTimer;
    protected int animFrame;
    protected boolean facingLeft;
    protected boolean destroyed;

    protected final LevelManager levelManager;

    protected AbstractBadnikInstance(ObjectSpawn spawn, LevelManager levelManager, String name) {
        super(spawn, name);
        this.levelManager = levelManager;
        this.currentX = spawn.x();
        this.currentY = spawn.y();
        this.xVelocity = 0;
        this.yVelocity = 0;
        this.animTimer = 0;
        this.animFrame = 0;
        this.facingLeft = false;
        this.destroyed = false;
    }

    @Override
    public final void update(int frameCounter, AbstractPlayableSprite player) {
        if (destroyed) {
            return;
        }
        updateMovement(frameCounter, player);
        updateAnimation(frameCounter);
    }

    /**
     * Subclasses implement their specific movement and AI logic.
     */
    protected abstract void updateMovement(int frameCounter, AbstractPlayableSprite player);

    /**
     * Subclasses can override to implement custom animation logic.
     * Default implementation is a simple frame timer.
     */
    protected void updateAnimation(int frameCounter) {
        // Default: no animation. Subclasses override.
    }

    /**
     * Returns the collision size index for touch response.
     */
    protected abstract int getCollisionSizeIndex();

    @Override
    public int getCollisionFlags() {
        // Category 0x00 = ENEMY, plus size index
        return 0x00 | (getCollisionSizeIndex() & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public void onPlayerAttack(AbstractPlayableSprite player, TouchResponseResult result) {
        if (destroyed) {
            return;
        }
        destroyBadnik(player);
    }

    /**
     * Handles Badnik destruction: spawn explosion, animal, points, award score.
     */
    protected void destroyBadnik(AbstractPlayableSprite player) {
        destroyed = true;
        setDestroyed(true);

        // Spawn explosion
        ExplosionObjectInstance explosion = new ExplosionObjectInstance(0x27, currentX, currentY,
                levelManager.getObjectRenderManager());
        levelManager.getObjectManager().addDynamicObject(explosion);

        // Spawn animal
        AnimalObjectInstance animal = new AnimalObjectInstance(
                new ObjectSpawn(currentX, currentY, 0x28, 0, 0, false, 0), levelManager);
        levelManager.getObjectManager().addDynamicObject(animal);

        // Spawn points (100)
        PointsObjectInstance points = new PointsObjectInstance(
                new ObjectSpawn(currentX, currentY, 0x29, 0, 0, false, 0), levelManager, 100);
        levelManager.getObjectManager().addDynamicObject(points);

        // Play explosion SFX
        uk.co.jamesj999.sonic.audio.AudioManager.getInstance().playSfx(Sonic2Constants.SndID_Explosion);

        // Award 100 points
        if (player != null) {
            // player.addScore(100); // Need to check if available
        }

        // Remove self
        // Remove self (handled by update loop via destroyed flag)
    }

    /**
     * Returns a dynamic spawn with the current position for collision detection.
     * This is critical because TouchResponseManager uses getSpawn() position.
     */
    @Override
    public ObjectSpawn getSpawn() {
        return new ObjectSpawn(
                currentX,
                currentY,
                spawn.objectId(),
                spawn.subtype(),
                spawn.renderFlags(),
                spawn.respawnTracked(),
                spawn.rawYWord());
    }

    @Override
    public boolean isHighPriority() {
        return super.isHighPriority();
    }

    @Override
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    /**
     * Helper: Check if player is to the left of this Badnik.
     */
    protected boolean isPlayerLeft(AbstractPlayableSprite player) {
        if (player == null) {
            return facingLeft;
        }
        return player.getCentreX() < currentX;
    }

    /**
     * Helper: Simple oscillation for vertical movement.
     */
    protected int oscillateVertical(int baseY, int amplitude, int period, int frameCounter) {
        double angle = (frameCounter % period) * (2.0 * Math.PI / period);
        return baseY + (int) (amplitude * Math.sin(angle));
    }
}
