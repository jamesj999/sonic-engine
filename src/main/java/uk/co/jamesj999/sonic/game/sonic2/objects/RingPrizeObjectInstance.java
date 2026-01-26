package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.level.rings.RingManager;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ Ring Prize Object (ObjDC).
 * <p>
 * A ring prize spawned by the slot machine that spirals inward to the cage,
 * then shows a sparkle animation and is destroyed.
 * <p>
 * <b>Behavior (from s2.asm lines 25275-25328):</b>
 * <ol>
 *   <li>Spawned at cage position with initial offset</li>
 *   <li>Spirals inward using delta/16 movement toward machine center</li>
 *   <li>After display delay expires (26 frames), calls CollectRing() and transitions to state 2</li>
 *   <li>State 2: Shows sparkle animation (Ani_Ring), then deletes</li>
 * </ol>
 * <p>
 * <b>Key insight:</b> ObjDC does NOT use collision detection. Rings are awarded
 * automatically when displayDelay expires.
 */
public class RingPrizeObjectInstance extends AbstractObjectInstance {

    // State machine constants (from s2.asm ObjDC state routines)
    private static final int STATE_SPIRAL = 0;    // Moving toward center (ObjDC_Main)
    private static final int STATE_COLLECTED = 2; // Sparkle animation (ObjDC_Animate)

    // Animation constants
    private static final int ANIM_DELAY = 1;  // From Ani_objDC: frame delay
    private static final int ANIM_FRAMES = 4; // From Ani_objDC: 4 frames (0, 1, 2, 3)

    // Position tracking (16.16 fixed point for precision)
    private int currentX;      // 16.16 fixed point X
    private int currentY;      // 16.16 fixed point Y
    private final int machineX; // Machine center X
    private final int machineY; // Machine center Y

    // Display delay before becoming collectible
    private int displayDelay;

    // Reference to parent counter (for decrementing)
    private final int[] prizeCounter;

    // Animation state
    private int animTimer = 0;
    private int animFrame = 0;

    // State machine
    private int state = STATE_SPIRAL;
    private boolean ringCollected = false;
    private boolean destroyed = false;

    // Sparkle animation tracking
    private int sparkleStartFrame = -1;

    // Reference to LevelManager for ring renderer
    private final LevelManager levelManager;

    // Frame counter for animation (stored from update for use in render)
    private int lastFrameCounter = 0;

    /**
     * Creates a ring prize object.
     *
     * @param x Initial X position
     * @param y Initial Y position
     * @param machineX Machine center X (target)
     * @param machineY Machine center Y (target)
     * @param displayDelay Frames before ring becomes collectible
     * @param prizeCounter Reference to counter to decrement when collected
     * @param levelManager Level manager for rendering
     */
    public RingPrizeObjectInstance(int x, int y, int machineX, int machineY,
                                   int displayDelay, int[] prizeCounter, LevelManager levelManager) {
        super(new ObjectSpawn(x, y, 0xDC, 0, 0, false, 0), "RingPrize");
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

        // Store frame counter for animation in render
        lastFrameCounter = frameCounter;

        switch (state) {
            case STATE_SPIRAL:
                updateSpiral(frameCounter, player);
                break;
            case STATE_COLLECTED:
                updateCollected(frameCounter);
                break;
            default:
                break;
        }

        // Check if off-screen for cleanup (only in spiral state)
        if (state == STATE_SPIRAL && !isOnScreen(64)) {
            destroyed = true;
        }
    }

    /**
     * STATE_SPIRAL: Move toward machine center and count down display delay.
     * When delay reaches 0, collect ring and transition to STATE_COLLECTED.
     */
    private void updateSpiral(int frameCounter, AbstractPlayableSprite player) {
        // Move position toward machine center (1/16th of distance per frame)
        // Based on ObjDC_Main in s2.asm lines 25288-25307
        long machineX32 = (long) machineX << 16;
        long machineY32 = (long) machineY << 16;

        long deltaX = currentX - machineX32;
        deltaX = deltaX >> 4;  // Divide by 16
        currentX -= (int) deltaX;

        long deltaY = currentY - machineY32;
        deltaY = deltaY >> 4;  // Divide by 16
        currentY -= (int) deltaY;

        // Update ring spin animation
        animTimer++;
        if (animTimer >= ANIM_DELAY) {
            animTimer = 0;
            animFrame++;
            if (animFrame >= ANIM_FRAMES) {
                animFrame = 0;
            }
        }

        // Check display delay - when it expires, collect ring and start sparkle
        if (displayDelay > 0) {
            displayDelay--;
            if (displayDelay == 0) {
                collectRing(player);
                // Transition to sparkle state
                sparkleStartFrame = frameCounter;
                state = STATE_COLLECTED;
            }
        }
    }

    /**
     * STATE_COLLECTED: Ring stays at current position showing sparkle animation.
     * When sparkle animation completes, destroy the object.
     */
    private void updateCollected(int frameCounter) {
        // Ring no longer moves - stays at current position

        // Check if sparkle animation is complete
        RingManager ringManager = levelManager.getRingManager();
        if (ringManager == null || sparkleStartFrame < 0) {
            // No sparkle available, destroy immediately
            destroyed = true;
            return;
        }

        int sparkleFrameCount = ringManager.getSparkleFrameCount();
        int frameDelay = ringManager.getFrameDelay();

        if (sparkleFrameCount <= 0) {
            // No sparkle animation, destroy immediately
            destroyed = true;
            return;
        }

        int elapsed = frameCounter - sparkleStartFrame;
        if (elapsed < 0) {
            elapsed = 0;
        }
        int sparkleFrameOffset = elapsed / frameDelay;
        if (sparkleFrameOffset >= sparkleFrameCount) {
            // Sparkle animation complete, destroy the ring
            destroyed = true;
        }
    }

    /**
     * Collect the ring (add to player).
     */
    private void collectRing(AbstractPlayableSprite player) {
        if (ringCollected) {
            return;
        }
        ringCollected = true;

        // Decrement prize counter
        if (prizeCounter != null && prizeCounter.length > 0) {
            prizeCounter[0]--;
        }

        // Add ring to player
        if (player != null) {
            player.addRings(1);
        }

        // Play ring sound
        AudioManager.getInstance().playSfx(GameSound.RING);
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
    public void appendRenderCommands(List<GLCommand> commands) {
        if (destroyed) {
            return;
        }

        // Get the ring renderer from RingManager
        RingManager ringManager = levelManager.getRingManager();
        if (ringManager == null || !ringManager.canRenderRings()) {
            // Fallback to debug box if ring renderer not available
            appendDebugBox(commands, currentX >> 16, currentY >> 16);
            return;
        }

        int screenX = currentX >> 16;
        int screenY = currentY >> 16;

        switch (state) {
            case STATE_SPIRAL:
                // Draw animated spinning ring
                ringManager.drawRingAt(screenX, screenY, lastFrameCounter);
                break;
            case STATE_COLLECTED:
                // Draw sparkle animation
                drawSparkle(ringManager, screenX, screenY);
                break;
            default:
                break;
        }
    }

    /**
     * Draw the sparkle animation at the current position.
     */
    private void drawSparkle(RingManager ringManager, int x, int y) {
        if (sparkleStartFrame < 0) {
            return;
        }

        int sparkleFrameCount = ringManager.getSparkleFrameCount();
        int frameDelay = ringManager.getFrameDelay();
        int sparkleStartIndex = ringManager.getSparkleStartIndex();

        if (sparkleFrameCount <= 0) {
            return;
        }

        int elapsed = lastFrameCounter - sparkleStartFrame;
        if (elapsed < 0) {
            elapsed = 0;
        }
        int sparkleFrameOffset = elapsed / frameDelay;
        if (sparkleFrameOffset >= sparkleFrameCount) {
            return; // Animation complete
        }

        int frameIndex = sparkleStartIndex + sparkleFrameOffset;
        ringManager.drawFrameIndex(frameIndex, x, y);
    }

    private void appendDebugBox(List<GLCommand> commands, int x, int y) {
        // Render a small yellow box representing the ring prize
        float r = 1.0f, g = 1.0f, b = 0.0f;
        int halfSize = 4;

        int left = x - halfSize;
        int right = x + halfSize;
        int top = y - halfSize;
        int bottom = y + halfSize;

        appendLine(commands, left, top, right, top, r, g, b);
        appendLine(commands, right, top, right, bottom, r, g, b);
        appendLine(commands, right, bottom, left, bottom, r, g, b);
        appendLine(commands, left, bottom, left, top, r, g, b);
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
