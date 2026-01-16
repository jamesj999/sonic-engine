package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.objects.TouchResponseProvider;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Projectile fired by Badniks (Buzzer stinger, Coconuts coconut).
 * Moves with configurable velocity and optional gravity.
 */
public class BadnikProjectileInstance extends AbstractObjectInstance
        implements TouchResponseProvider {

    public enum ProjectileType {
        BUZZER_STINGER,
        COCONUT
    }

    private static final int COLLISION_SIZE_STINGER = 0x18; // From disassembly $98 & 0x3F
    private static final int COLLISION_SIZE_COCONUT = 0x0B; // From disassembly $8B & 0x3F
    private static final int GRAVITY_COCONUT = 0x20; // Obj98_CoconutFall

    private final ProjectileType type;
    private int currentX;
    private int currentY;
    private int xVelocity; // In subpixels
    private int yVelocity; // In subpixels
    private boolean applyGravity;
    private int gravity;
    private int collisionSizeIndex;
    private int animFrame;
    private boolean hFlip;

    /**
     * Create a new projectile.
     * 
     * @param spawn   Original spawn data
     * @param type    Type of projectile (determines graphics)
     * @param x       Starting X position
     * @param y       Starting Y position
     * @param xVel    X velocity in subpixels (positive = right)
     * @param yVel    Y velocity in subpixels (positive = down)
     * @param gravity Whether to apply gravity
     * @param hFlip   Horizontal flip for sprite
     */
    public BadnikProjectileInstance(ObjectSpawn spawn, ProjectileType type,
            int x, int y, int xVel, int yVel, boolean gravity, boolean hFlip) {
        super(spawn, "Projectile");
        this.type = type;
        this.currentX = x;
        this.currentY = y;
        this.xVelocity = xVel;
        this.yVelocity = yVel;
        this.applyGravity = gravity;
        this.animFrame = 0;
        this.hFlip = hFlip;
        switch (type) {
            case BUZZER_STINGER -> {
                this.gravity = 0;
                this.collisionSizeIndex = COLLISION_SIZE_STINGER;
            }
            case COCONUT -> {
                this.gravity = GRAVITY_COCONUT;
                this.collisionSizeIndex = COLLISION_SIZE_COCONUT;
            }
        }
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Apply gravity if enabled
        if (applyGravity) {
            yVelocity += gravity;
        }

        // Update position (velocities are in subpixels, shift by 8)
        currentX += (xVelocity >> 8);
        currentY += (yVelocity >> 8);

        // Check if off-screen and destroy
        if (!isOnScreen()) {
            setDestroyed(true);
        }

        // Simple animation cycling
        animFrame = ((frameCounter >> 2) & 1);
    }

    private boolean isOnScreen() {
        uk.co.jamesj999.sonic.camera.Camera camera = uk.co.jamesj999.sonic.camera.Camera.getInstance();
        int camX = camera.getX();
        int camY = camera.getY();
        int width = camera.getWidth();
        int height = camera.getHeight();
        int margin = 32;
        return currentX >= camX - margin
                && currentX <= camX + width + margin
                && currentY >= camY - margin
                && currentY <= camY + height + margin;
    }

    @Override
    public int getCollisionFlags() {
        // HURT category (0x80) + size index
        return 0x80 | (collisionSizeIndex & 0x3F);
    }

    @Override
    public int getCollisionProperty() {
        return 0;
    }

    @Override
    public ObjectSpawn getSpawn() {
        // Return dynamic spawn with current position
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
    public int getX() {
        return currentX;
    }

    @Override
    public int getY() {
        return currentY;
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        PatternSpriteRenderer renderer;
        int frame;

        switch (type) {
            case BUZZER_STINGER:
                renderer = renderManager.getBuzzerRenderer();
                // Buzzer projectile uses frames 5-6 (animation 2 in disassembly)
                frame = 5 + animFrame;
                break;
            case COCONUT:
                renderer = renderManager.getCoconutsRenderer();
                // Coconut uses frame 3
                frame = 3;
                break;
            default:
                return;
        }

        if (renderer == null || !renderer.isReady()) {
            return;
        }

        renderer.drawFrameIndex(frame, currentX, currentY, hFlip, false);
    }
}
