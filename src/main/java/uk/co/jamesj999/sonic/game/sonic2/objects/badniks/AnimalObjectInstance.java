package uk.co.jamesj999.sonic.game.sonic2.objects.badniks;

import uk.co.jamesj999.sonic.camera.Camera;

import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.ObjectRenderManager;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

public class AnimalObjectInstance extends uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance {
    private static final int GRAVITY = 0x38;
    private static final int JUMP_VELOCITY = -0x400; // -4.0
    private static final int MOVE_VELOCITY = 0x200; // 2.0 (Direction dependant)

    private final PatternSpriteRenderer renderer;
    private final LevelManager levelManager;
    private int currentX;
    private int currentY;
    private short xVelocity;
    private short yVelocity;
    private int animTimer;
    private int animFrame;

    public AnimalObjectInstance(ObjectSpawn spawn, LevelManager levelManager) {
        super(spawn, "Animal");
        this.levelManager = levelManager;
        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        this.renderer = renderManager.getAnimalRenderer();
        this.currentX = spawn.x();
        this.currentY = spawn.y();

        // Initial setup
        this.yVelocity = -0x400; // Initial pop up
        this.xVelocity = (short) (Math.random() > 0.5 ? MOVE_VELOCITY : -MOVE_VELOCITY);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Physics
        yVelocity += GRAVITY;

        // Apply movement
        currentX += (xVelocity >> 8);
        currentY += (yVelocity >> 8);

        // Floor Collision
        if (yVelocity >= 0) { // Only check when falling
            checkFloorCollision();
        }

        // Animation
        animTimer--;
        if (animTimer <= 0) {
            animTimer = 4; // Fast cycle
            animFrame = (animFrame + 1) % 2; // Cycle 0, 1
        }

        // Despawn
        if (!onScreen(64)) {
            setDestroyed(true);
        }
    }

    private void checkFloorCollision() {
        // Simple check at bottom center
        int checkX = currentX;
        int checkY = currentY + 12; // Radius approx 12 (offset from center)

        // Access Level Data
        uk.co.jamesj999.sonic.level.ChunkDesc chunk = levelManager.getChunkDescAt((byte) 0, checkX, checkY);
        if (chunk == null)
            return;

        // Check for solid tile (using generic index 0 for top solidity)
        // This is a simplification. Real animals check specific solidity bits.
        // But for visual effect, standard top-solidity is enough.
        int solidityBit = 0; // Top solidity
        if (chunk.isSolidityBitSet(solidityBit)) {
            uk.co.jamesj999.sonic.level.SolidTile tile = levelManager.getSolidTileForChunkDesc(chunk, solidityBit);
            if (tile != null) {
                // Found solid ground! Bounce!
                // Reset Y to align with block?
                // (Simplified: Just bounce and adjust slightly up to avoid sticking)

                // Get block top Y
                int blockTop = (checkY & ~15); // Round down to 16x16 grid
                // Adjust Y relative to tile height would be better, but simple bounce works

                // Check if actually inside the solid part (height map)
                int indexInBlock = checkX & 15;
                if (chunk.getHFlip())
                    indexInBlock = 15 - indexInBlock;
                int height = tile.getHeightAt((byte) indexInBlock);

                if (height > 0) {
                    int surfaceY = (blockTop + 16 - height);
                    if (checkY >= surfaceY) {
                        // Landed
                        currentY = surfaceY - 12; // Reposition
                        yVelocity = JUMP_VELOCITY; // Bounce
                        // Update X velocity (hacky friction/randomness or standard bounce?)
                        // Standard animals just keep X velocity usually, or stop if wall.
                        // We keep X velocity.
                    }
                }
            }
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed())
            return;
        boolean hFlip = xVelocity < 0;
        renderer.drawFrameIndex(animFrame, currentX, currentY, hFlip, false);
    }

    private boolean onScreen(int margin) {
        Camera camera = Camera.getInstance();
        int cameraX = camera.getX();
        int screenWidth = 320;
        if (currentX < cameraX - margin || currentX > cameraX + screenWidth + margin)
            return false;
        return true;
    }

    public int getX() {
        return currentX;
    }

    public int getY() {
        return currentY;
    }

    public void setX(int x) {
        this.currentX = x;
    }

    public void setY(int y) {
        this.currentY = y;
    }
}
