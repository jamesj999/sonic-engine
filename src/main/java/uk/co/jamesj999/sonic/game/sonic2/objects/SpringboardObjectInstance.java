package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * Springboard / Lever Spring (Object 0x40).
 * A red diving-board style springboard found in CPZ, ARZ, and MCZ.
 * <p>
 * When the player stands on it, the platform compresses and launches them
 * upward with position-dependent velocity. Standing on the higher end
 * (right side for unflipped) gives a stronger launch.
 * <p>
 * Based on Sonic 2 disassembly s2.asm lines 51757-51971.
 */
public class SpringboardObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener, SlopedSolidProvider {

    // Animation IDs
    private static final int ANIM_IDLE = 0;
    private static final int ANIM_TRIGGERED = 1;

    /**
     * Diagonal slope data (idle state - frame 0).
     * From s2.asm byte_26598 (40 bytes).
     * These values define the height at each X position relative to the center.
     */
    private static final byte[] SLOPE_DIAG_UP = {
            0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x09,
            0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x10,
            0x11, 0x12, 0x13, 0x14, 0x14, 0x15, 0x15, 0x16,
            0x17, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18,
            0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18, 0x18
    };

    /**
     * Straight slope data (compressed state - frame 1).
     * From s2.asm byte_265C0 (40 bytes).
     * A flatter slope when the springboard is pressed down.
     */
    private static final byte[] SLOPE_STRAIGHT = {
            0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x08, 0x09,
            0x0A, 0x0B, 0x0C, 0x0C, 0x0C, 0x0C, 0x0D, 0x0D,
            0x0D, 0x0D, 0x0D, 0x0D, 0x0E, 0x0E, 0x0F, 0x0F,
            0x10, 0x10, 0x10, 0x10, 0x0F, 0x0F, 0x0E, 0x0E,
            0x0D, 0x0D, 0x0D, 0x0D, 0x0D, 0x0D, 0x0D, 0x0D
    };

    /**
     * Position-to-boost lookup table (72 bytes).
     * From s2.asm byte_26550.
     * Maps relative X position to a boost value (0-4).
     * Higher values at the right (higher) end give stronger launches.
     */
    private static final byte[] VELOCITY_BOOST_TABLE = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1,
            1, 1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 2,
            3, 3, 3, 3, 3, 3, 4, 4, 0, 0, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0
    };

    // Collision dimensions from ROM
    // Width: 0x27 (39) pixels half-width
    // Height: 8 pixels
    private static final int COLLISION_WIDTH = 0x27;
    private static final int COLLISION_HEIGHT = 8;

    // Position threshold for launch trigger (0x10 pixels from center)
    private static final int LAUNCH_THRESHOLD = 0x10;

    private final ObjectAnimationState animationState;
    private int mappingFrame;
    private int prevMappingFrame;
    private boolean compressed;
    private int launchCooldown;

    public SpringboardObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, COLLISION_WIDTH, COLLISION_HEIGHT, 1.0f, 0.85f, 0.1f, false);

        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        this.animationState = new ObjectAnimationState(
                renderManager != null ? renderManager.getSpringboardAnimations() : null,
                ANIM_IDLE,
                0);
        this.mappingFrame = 0;
        this.prevMappingFrame = 0;
        this.compressed = false;
        this.launchCooldown = 0;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null || !contact.standing()) {
            return;
        }

        // Prevent re-triggering while player is still springing or during cooldown
        if (player.getSpringing() || launchCooldown > 0) {
            return;
        }

        // Check if player is on the "high" side of the springboard
        // ROM: loc_2641E - position threshold check
        boolean onHighSide = checkPlayerOnHighSide(player);
        if (!onHighSide) {
            return;
        }

        // ROM logic: Launch when animation is compressed (anim 1) AND frame cycles back to 0
        // First time on high side: set animation to compressed
        if (!compressed) {
            animationState.setAnimId(ANIM_TRIGGERED);
            compressed = true;
            return;
        }

        // If compressed and frame just cycled to 0, trigger launch
        // (prevMappingFrame was 1, now mappingFrame is 0)
        if (compressed && prevMappingFrame == 1 && mappingFrame == 0) {
            applyLaunch(player);
        }
    }

    /**
     * Checks if the player is on the "high" side of the springboard (the launch zone).
     * ROM: loc_2641E - unflipped: player.x > springboard.x - 0x10
     *      loc_26436 - flipped: player.x <= springboard.x + 0x10
     */
    private boolean checkPlayerOnHighSide(AbstractPlayableSprite player) {
        int playerX = player.getX();
        int springboardX = spawn.x();

        if (isFlippedHorizontal()) {
            // Flipped: high side is on the left
            return playerX <= springboardX + LAUNCH_THRESHOLD;
        } else {
            // Unflipped: high side is on the right
            return playerX > springboardX - LAUNCH_THRESHOLD;
        }
    }

    /**
     * Calculates and applies launch velocity based on player position on the springboard.
     * Based on ROM loc_2645E.
     */
    private void applyLaunch(AbstractPlayableSprite player) {
        // Calculate relative X position: dx = player.x - spawn.x + 0x1C
        int dx = player.getX() - spawn.x() + 0x1C;

        // If flipped horizontally, invert the position
        // ROM bug preserved: uses 0x27 instead of 0x38
        boolean flipped = isFlippedHorizontal();
        if (flipped) {
            dx = ~dx + 0x27;
        }

        // Clamp to valid table range
        if (dx < 0) dx = 0;
        if (dx >= VELOCITY_BOOST_TABLE.length) dx = VELOCITY_BOOST_TABLE.length - 1;

        // Look up boost value (0-4)
        int boost = VELOCITY_BOOST_TABLE[dx] & 0xFF;

        // Calculate Y velocity: -0x400 - (boost << 8)
        // Base velocity of -0x400 (-1024), plus up to -0x400 more for max boost
        int yVelocity = -0x400 - (boost << 8);

        // Apply Y velocity
        player.setYSpeed((short) yVelocity);

        // Set player facing direction based on springboard orientation
        // ROM: bset #status.player.x_flip (face right if flipped, left if not)
        if (flipped) {
            player.setDirection(Direction.LEFT);
        } else {
            player.setDirection(Direction.RIGHT);
        }

        // Add X velocity boost if player is moving fast
        // ROM: if |x_vel| >= 0x400, subtract boost from x_vel high byte
        // This is equivalent to x_vel -= boost << 8
        int xVel = player.getXSpeed();
        if (Math.abs(xVel) >= 0x400) {
            // ROM negates boost if springboard is NOT flipped
            int xBoost = boost << 8;
            if (!flipped) {
                xBoost = -xBoost;
            }
            // ROM uses sub.b which subtracts from high byte
            player.setXSpeed((short) (xVel - xBoost));
        }

        // Set player to air state
        player.setAir(true);
        player.setGSpeed((short) 0);
        player.setSpringing(15);

        // Play spring sound
        try {
            if (AudioManager.getInstance() != null) {
                AudioManager.getInstance().playSfx(GameSound.SPRING);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }

        // Set cooldown to prevent immediate re-triggering
        launchCooldown = 30;
        compressed = false;
    }

    private boolean isFlippedHorizontal() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    /**
     * Make springboard non-solid when player is springing.
     * Prevents collision issues immediately after launch.
     */
    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        if (player != null && player.getSpringing()) {
            return false;
        }
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // Width: 39 pixels (0x27), height based on slope
        return new SolidObjectParams(COLLISION_WIDTH, COLLISION_HEIGHT, COLLISION_HEIGHT);
    }

    @Override
    public byte[] getSlopeData() {
        // Return slope based on current animation frame
        // Frame 0 (idle): diagonal slope
        // Frame 1 (compressed): flatter slope
        return mappingFrame == 0 ? SLOPE_DIAG_UP : SLOPE_STRAIGHT;
    }

    @Override
    public boolean isSlopeFlipped() {
        return isFlippedHorizontal();
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Track previous frame for launch trigger detection
        prevMappingFrame = mappingFrame;

        // Update animation state
        animationState.update();
        mappingFrame = animationState.getMappingFrame();

        // Decrement cooldown
        if (launchCooldown > 0) {
            launchCooldown--;
        }

        // Reset compressed flag when animation returns to idle and cooldown expires
        if (compressed && mappingFrame == 0 && launchCooldown == 0) {
            compressed = false;
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }

        PatternSpriteRenderer renderer = renderManager.getSpringboardRenderer();
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }

        boolean hFlip = isFlippedHorizontal();
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;
        renderer.drawFrameIndex(mappingFrame, spawn.x(), spawn.y(), hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
