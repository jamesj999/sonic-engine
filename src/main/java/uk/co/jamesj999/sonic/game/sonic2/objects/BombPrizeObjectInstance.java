package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Bomb Prize Object (ObjD3).
 * <p>
 * A spike ball prize spawned by the slot machine that spirals inward toward the cage.
 * Subtracts 1 ring from the player when display delay expires (no knockback/invincibility).
 * <p>
 * <b>Behavior (from s2.asm lines 58208-58275):</b>
 * <ol>
 *   <li>Spawned at edge with initial angle</li>
 *   <li>Spirals inward toward cage center (1/16th distance per frame)</li>
 *   <li>When display delay expires, subtract 1 ring (Ring_Reduction)</li>
 *   <li>Plays spike sound every 5 bombs (from s2.asm line 58246)</li>
 *   <li>Destroyed when off-screen or display delay expires</li>
 * </ol>
 */
public class BombPrizeObjectInstance extends AbstractObjectInstance {

    // Sound throttle counter (shared across all bomb instances)
    // Plays spike sound every 5 bombs per disassembly
    private static int soundThrottleCounter = 0;
    private static final int SOUND_THROTTLE_INTERVAL = 5;

    // Position tracking (16.16 fixed point for precision)
    private int currentX;      // 16.16 fixed point X
    private int currentY;      // 16.16 fixed point Y
    private final int machineX; // Machine center X
    private final int machineY; // Machine center Y

    // Display delay before active
    private int displayDelay;

    // Reference to parent counter (for decrementing)
    private final int[] prizeCounter;

    // State
    private boolean destroyed = false;

    // Reference to LevelManager for rendering
    private final LevelManager levelManager;

    /**
     * Creates a bomb prize object.
     *
     * @param x Initial X position
     * @param y Initial Y position
     * @param machineX Machine center X (target)
     * @param machineY Machine center Y (target)
     * @param displayDelay Frames before bomb becomes active
     * @param prizeCounter Reference to counter to decrement when destroyed
     * @param levelManager Level manager for rendering
     */
    public BombPrizeObjectInstance(int x, int y, int machineX, int machineY,
                                   int displayDelay, int[] prizeCounter, LevelManager levelManager) {
        super(new ObjectSpawn(x, y, 0xD3, 0, 0, false, 0), "BombPrize");
        this.currentX = x << 16;  // Convert to 16.16 fixed point
        this.currentY = y << 16;
        this.machineX = machineX;
        this.machineY = machineY;
        this.displayDelay = displayDelay;
        this.prizeCounter = prizeCounter;
        this.levelManager = levelManager;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (destroyed) {
            return;
        }

        // Move position toward machine center (1/16th of distance per frame)
        // Based on ObjD3 movement in s2.asm
        long machineX32 = (long) machineX << 16;
        long machineY32 = (long) machineY << 16;

        long deltaX = currentX - machineX32;
        deltaX = deltaX >> 4;  // Divide by 16
        currentX -= (int) deltaX;

        long deltaY = currentY - machineY32;
        deltaY = deltaY >> 4;  // Divide by 16
        currentY -= (int) deltaY;

        // Check display delay
        if (displayDelay > 0) {
            displayDelay--;
            if (displayDelay == 0) {
                // Bomb display delay expired - remove a ring from player
                // (from s2.asm lines 58230-58253: subq.w #1,... then Ring_Reduction)
                if (player != null && player.getRingCount() > 0) {
                    player.addRings(-1);
                }

                // Play spike sound every 5 bombs (from s2.asm line 58246)
                // Sound plays regardless of whether player has rings
                soundThrottleCounter++;
                if (soundThrottleCounter >= SOUND_THROTTLE_INTERVAL) {
                    soundThrottleCounter = 0;
                    playSpikeSound();
                }

                // Decrement prize counter
                if (prizeCounter != null && prizeCounter.length > 0) {
                    prizeCounter[0]--;
                }
                destroyed = true;
            }
        }

        // Check if off-screen for cleanup
        if (!isOnScreen(64)) {
            destroyed = true;
        }
    }

    @Override
    public int getX() {
        return currentX >> 16;
    }

    @Override
    public int getY() {
        return currentY >> 16;
    }

    @Override
    public ObjectSpawn getSpawn() {
        // Return dynamic spawn with current position for collision detection
        return new ObjectSpawn(
                currentX >> 16,
                currentY >> 16,
                0xD3,
                spawn.subtype(),
                spawn.renderFlags(),
                false,
                spawn.rawYWord()
        );
    }

    /**
     * Play spike sound effect for bomb impact.
     */
    private void playSpikeSound() {
        try {
            AudioManager audioManager = AudioManager.getInstance();
            if (audioManager != null) {
                audioManager.playSfx(GameSound.HURT_SPIKE);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        int screenX = currentX >> 16;
        int screenY = currentY >> 16;

        // Try to use the CNZ bonus spike renderer
        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager != null) {
            PatternSpriteRenderer renderer = renderManager.getRenderer(Sonic2ObjectArtKeys.CNZ_BONUS_SPIKE);
            if (renderer != null && renderer.isReady()) {
                renderer.drawFrameIndex(0, screenX, screenY, false, false);
                return;
            }
        }

        // Fallback: render a debug box
        appendDebugBox(commands, screenX, screenY);
    }

    private void appendDebugBox(List<GLCommand> commands, int x, int y) {
        // Render a small red box representing the bomb prize
        float r = 1.0f, g = 0.0f, b = 0.0f;
        int halfSize = 8;

        int left = x - halfSize;
        int right = x + halfSize;
        int top = y - halfSize;
        int bottom = y + halfSize;

        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);

        // Draw X to indicate danger
        appendLine(commands, left, top, right, bottom, r, g, b);
        appendLine(commands, right, top, left, bottom, r, g, b);
    }

    private void appendLine(List<GLCommand> commands, int x1, int y1, int x2, int y2,
                            float r, float g, float b) {
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x1, y1, 0, 0));
        commands.add(new GLCommand(GLCommand.CommandType.VERTEX2I, -1, GLCommand.BlendType.SOLID,
                r, g, b, x2, y2, 0, 0));
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(3);
    }

    @Override
    public boolean isDestroyed() {
        return destroyed;
    }
}
