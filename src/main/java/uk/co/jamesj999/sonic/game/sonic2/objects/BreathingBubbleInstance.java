package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.camera.Camera;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.WaterSystem;
import uk.co.jamesj999.sonic.level.objects.AbstractObjectInstance;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Small breathing bubble that rises from the player's mouth while underwater.
 * <p>
 * The bubble moves upward with a sine wave horizontal oscillation. The sine movement
 * is adjusted based on the direction the player was facing to ensure the bubble begins
 * by moving away from the player's mouth.
 * <p>
 * For countdown bubbles (showing numbers 5-0), the bubble locks its position on screen
 * when the number forms, staying visible until the animation ends.
 */
public class BreathingBubbleInstance extends AbstractObjectInstance {
    /** Upward velocity in pixels per frame */
    private static final int RISE_SPEED = 1;

    /** Amplitude of horizontal sine wave oscillation in pixels */
    private static final int SINE_AMPLITUDE = 2;

    /** Period of sine wave in frames */
    private static final int SINE_PERIOD = 32;

    /** Frame delay per animation sub-frame for countdown bubbles */
    private static final int COUNTDOWN_FRAME_DELAY = 5;

    /** Number of times the formed number appears before animation ends */
    private static final int COUNTDOWN_NUMBER_REPEATS = 4;

    /** Total frames for countdown bubble animation before number forms */
    private static final int COUNTDOWN_BUBBLE_FRAMES = 15;

    /** Current X position (float for smooth movement) */
    private float currentX;

    /** Current Y position */
    private int currentY;

    /** Base X position for sine calculation */
    private int baseX;

    /** Frame counter for sine oscillation */
    private int sineFrame;

    /** Whether sine wave starts moving left (away from player facing right) */
    private boolean startMovingLeft;

    /** Countdown number to display (-1 for regular bubble) */
    private int countdownNumber;

    /** Frame counter for countdown animation */
    private int countdownFrame;

    /** Whether the countdown number has formed (position locks) */
    private boolean numberFormed;

    /** Screen-space offset from camera when number forms (for position locking) */
    private int lockedScreenX;
    private int lockedScreenY;

    /** Total lifetime in frames */
    private int lifetime;

    /**
     * Creates a breathing bubble.
     *
     * @param x                World X coordinate (player's mouth position)
     * @param y                World Y coordinate (player's Y position)
     * @param startMovingLeft  True if sine wave should start moving left
     * @param countdownNumber  Countdown number to display (-1 for regular bubble)
     */
    public BreathingBubbleInstance(int x, int y, boolean startMovingLeft, int countdownNumber) {
        super(new ObjectSpawn(x, y, 0x0A, 0, 0, false, 0), "BreathingBubble");
        this.currentX = x;
        this.currentY = y;
        this.baseX = x;
        this.sineFrame = 0;
        this.startMovingLeft = startMovingLeft;
        this.countdownNumber = countdownNumber;
        this.countdownFrame = 0;
        this.numberFormed = false;
        this.lifetime = 0;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        lifetime++;

        // Check if we've exited water (bubble pops)
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager != null && levelManager.getCurrentLevel() != null) {
            WaterSystem waterSystem = WaterSystem.getInstance();
            int zoneId = levelManager.getCurrentLevel().getZoneIndex();
            int actId = levelManager.getCurrentAct();

            if (waterSystem.hasWater(zoneId, actId)) {
                int waterY = waterSystem.getVisualWaterLevelY(zoneId, actId);
                if (currentY <= waterY) {
                    // Bubble has reached the water surface - destroy it
                    setDestroyed(true);
                    return;
                }
            }
        }

        // Handle countdown bubble animation
        if (countdownNumber >= 0) {
            countdownFrame++;

            // Check if number is about to form (one frame before)
            int formFrame = COUNTDOWN_BUBBLE_FRAMES * COUNTDOWN_FRAME_DELAY;
            if (countdownFrame >= formFrame - 1 && !numberFormed) {
                // Lock position relative to camera
                Camera camera = Camera.getInstance();
                lockedScreenX = (int) currentX - camera.getX();
                lockedScreenY = currentY - camera.getY();
                numberFormed = true;
            }

            // Check if animation is complete
            int totalAnimFrames = formFrame + (COUNTDOWN_NUMBER_REPEATS * COUNTDOWN_FRAME_DELAY);
            if (countdownFrame >= totalAnimFrames) {
                setDestroyed(true);
                return;
            }
        }

        // Movement logic
        if (numberFormed) {
            // Position locked relative to camera
            Camera camera = Camera.getInstance();
            currentX = camera.getX() + lockedScreenX;
            currentY = camera.getY() + lockedScreenY;
        } else {
            // Normal bubble movement
            // Move upward
            currentY -= RISE_SPEED;

            // Apply sine wave horizontal oscillation
            sineFrame++;
            double angle = (2.0 * Math.PI * sineFrame) / SINE_PERIOD;

            // Adjust starting direction based on player facing
            if (startMovingLeft) {
                // Start moving left (negative direction)
                currentX = baseX - (float) (SINE_AMPLITUDE * Math.sin(angle));
            } else {
                // Start moving right (positive direction)
                currentX = baseX + (float) (SINE_AMPLITUDE * Math.sin(angle));
            }
        }

        // Destroy if bubble has been alive too long (failsafe)
        if (lifetime > 600) { // 10 seconds max
            setDestroyed(true);
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        if (isDestroyed()) {
            return;
        }

        Camera camera = Camera.getInstance();
        int screenX = (int) currentX - camera.getX();
        int screenY = currentY - camera.getY();

        // Only render if on screen
        if (screenX < -16 || screenX > camera.getWidth() + 16 ||
            screenY < -16 || screenY > camera.getHeight() + 16) {
            return;
        }

        // Render the bubble
        // For now, render as a simple circle. This can be replaced with proper sprite
        // rendering when bubble art is loaded.
        if (countdownNumber >= 0 && numberFormed) {
            // Render countdown number
            // TODO: Replace with actual number sprite rendering
            renderCountdownNumber(commands, screenX, screenY);
        } else {
            // Render small bubble
            // TODO: Replace with actual bubble sprite rendering
            renderSmallBubble(commands, screenX, screenY);
        }
    }

    /**
     * Renders a small breathing bubble.
     * This is a placeholder that draws a simple circle.
     */
    private void renderSmallBubble(List<GLCommand> commands, int screenX, int screenY) {
        // Draw a small white/blue circle as placeholder
        // Size varies slightly based on lifetime for visual interest
        int size = 3 + (lifetime / 20) % 2;

        // Convert to world coordinates for GLCommand
        Camera camera = Camera.getInstance();
        int worldX = screenX + camera.getX();
        int worldY = screenY + camera.getY();

        // Create a simple point/circle command
        // Using VERTEX2I for a point marker - this is a placeholder
        commands.add(new GLCommand(
            GLCommand.CommandType.VERTEX2I,
            -1,
            GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
            0.7f, 0.9f, 1.0f, // Light blue color
            worldX, worldY,
            size, size
        ));
    }

    /**
     * Renders the countdown number.
     * This is a placeholder that will be replaced with sprite rendering.
     */
    private void renderCountdownNumber(List<GLCommand> commands, int screenX, int screenY) {
        // Draw a larger bubble with number indication
        // TODO: Replace with actual number sprite from ROM

        Camera camera = Camera.getInstance();
        int worldX = screenX + camera.getX();
        int worldY = screenY + camera.getY();

        // Placeholder: larger bubble for countdown
        commands.add(new GLCommand(
            GLCommand.CommandType.VERTEX2I,
            -1,
            GLCommand.BlendType.ONE_MINUS_SRC_ALPHA,
            1.0f, 0.5f, 0.5f, // Reddish to indicate countdown
            worldX, worldY,
            8, 8
        ));
    }

    /**
     * Gets the current X position (integer for ObjectInstance interface).
     */
    @Override
    public int getX() {
        return (int) currentX;
    }

    /**
     * Gets the current Y position.
     */
    public int getY() {
        return currentY;
    }

    /**
     * Returns whether this is a countdown bubble.
     */
    public boolean isCountdownBubble() {
        return countdownNumber >= 0;
    }

    /**
     * Returns the countdown number (-1 if regular bubble).
     */
    public int getCountdownNumber() {
        return countdownNumber;
    }
}
