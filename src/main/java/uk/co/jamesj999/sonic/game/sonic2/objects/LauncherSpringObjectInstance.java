package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.sonic2.Sonic2ObjectArtKeys;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.graphics.RenderPriority;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.level.objects.*;
import uk.co.jamesj999.sonic.level.render.PatternSpriteRenderer;
import uk.co.jamesj999.sonic.physics.Direction;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;

import java.util.List;

/**
 * CNZ LauncherSpring Object (Obj85).
 * <p>
 * An interactive pressure spring that launches the player with variable force based on compression.
 * Unlike regular springs, the player lands on this spring, is locked in place (rolling state),
 * and can hold the jump button to compress it. Releasing launches with velocity based on compression.
 * <p>
 * Subtypes:
 * <ul>
 *   <li><b>0x00</b>: Vertical spring (launches straight up)</li>
 *   <li><b>0x81</b>: Diagonal spring (launches up-left, bit 7 indicates diagonal mode)</li>
 * </ul>
 * <p>
 * Launch velocity formula: (compression + base) * 128
 * <ul>
 *   <li>Vertical: base = 0x10 (16), max compression = 0x20 (32)</li>
 *   <li>Diagonal: base = 0x04 (4), max compression = 0x1C (28)</li>
 * </ul>
 * <p>
 * <b>Disassembly Reference:</b> s2.asm obj85 (loc_2AD26 - loc_2AE76)
 */
public class LauncherSpringObjectInstance extends BoxObjectInstance
        implements SolidObjectProvider, SolidObjectListener {

    // Subtype constants
    private static final int SUBTYPE_DIAGONAL_FLAG = 0x80;

    // Physics constants from disassembly
    private static final int VERTICAL_BASE_VELOCITY = 0x10;      // Base for vertical launch
    private static final int VERTICAL_MAX_COMPRESSION = 0x20;    // Max compression (32)
    private static final int DIAGONAL_BASE_VELOCITY = 0x04;      // Base for diagonal launch
    private static final int DIAGONAL_MAX_COMPRESSION = 0x1C;    // Max compression (28)
    private static final int COMPRESSION_FRAME_INTERVAL = 4;     // Frames between compression increments

    // Vertical spring position offsets
    private static final int VERTICAL_PLAYER_Y_OFFSET = 0x2E;    // 46 pixels above spring Y

    // Diagonal spring position offsets
    private static final int DIAGONAL_PLAYER_X_OFFSET = 0x13;    // 19 pixels from spring X
    private static final int DIAGONAL_PLAYER_Y_OFFSET = 0x13;    // 19 pixels above spring Y

    // State tracking
    private int compression = 0;
    private int playerState = 0;  // 0 = not standing, 1 = standing/compressing
    private int compressionFrameCounter = 0;
    private int animationFrame = 0;
    private int baseX;  // Original X position (for diagonal movement)
    private int baseY;  // Original Y position (for spring movement)
    private boolean wasJumpPressedLastFrame = false;  // For button release detection

    // Rendering state
    private int currentSpriteX;
    private int currentSpriteY;

    public LauncherSpringObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name, 16, 16, 0.8f, 0.4f, 0.6f, false);
        this.baseX = spawn.x();
        this.baseY = spawn.y();
        this.currentSpriteX = spawn.x();
        this.currentSpriteY = spawn.y();
    }

    /**
     * Checks if this is a diagonal spring (subtype bit 7 set).
     */
    private boolean isDiagonal() {
        return (spawn.subtype() & SUBTYPE_DIAGONAL_FLAG) != 0;
    }

    /**
     * Checks if this is a left-facing spring (for diagonal mode, uses render flags).
     */
    private boolean isLeftFacing() {
        return (spawn.renderFlags() & 0x1) != 0;
    }

    /**
     * Gets the maximum compression for this spring type.
     */
    private int getMaxCompression() {
        return isDiagonal() ? DIAGONAL_MAX_COMPRESSION : VERTICAL_MAX_COMPRESSION;
    }

    /**
     * Gets the base velocity for this spring type.
     */
    private int getBaseVelocity() {
        return isDiagonal() ? DIAGONAL_BASE_VELOCITY : VERTICAL_BASE_VELOCITY;
    }

    @Override
    public void onSolidContact(AbstractPlayableSprite player, SolidContact contact, int frameCounter) {
        if (player == null) {
            return;
        }

        // If player entered debug mode while on the spring, reset spring state
        if (player.isDebugMode() && playerState != 0) {
            resetSpringState();
            return;
        }

        if (contact.standing() && player.getYSpeed() >= 0) {
            // Player is standing on the spring
            if (playerState == 0) {
                // First frame standing - lock player controls, set rolling
                enterSpring(player);
            } else {
                // Already on spring - handle compression
                handleCompression(player);
            }
        } else if (playerState != 0) {
            // Player left the spring without releasing
            releasePlayer(player);
        }
    }

    /**
     * Called when player first lands on the spring.
     * Locks controls and sets rolling state (ROM: loc_2AD26).
     */
    private void enterSpring(AbstractPlayableSprite player) {
        // Lock player controls (obj_control = 0x81 in ROM)
        player.setControlLocked(true);
        player.setPinballMode(true);

        // Snap player to center of spring
        if (isDiagonal()) {
            player.setX((short) (currentSpriteX + DIAGONAL_PLAYER_X_OFFSET));
            player.setY((short) (currentSpriteY - DIAGONAL_PLAYER_Y_OFFSET));
        } else {
            player.setX((short) currentSpriteX);
            player.setY((short) (currentSpriteY - VERTICAL_PLAYER_Y_OFFSET));
        }

        // Clear velocities
        player.setXSpeed((short) 0);
        player.setYSpeed((short) 0);
        player.setGSpeed((short) 0);

        // Set rolling animation
        boolean wasRolling = player.getRolling();
        player.setRolling(true);
        if (!wasRolling) {
            player.setY((short) (player.getY() + 5));
        }

        playerState = 1;
        compression = 0;
        compressionFrameCounter = 0;
    }

    /**
     * Handles compression while player is standing on spring.
     * Compression increases every 4 frames while jump is held (ROM: loc_2AD96).
     *
     * ROM behavior: Only launches when jump transitions from pressed to not-pressed.
     * If player never presses jump, they just stand on the spring without launching.
     */
    private void handleCompression(AbstractPlayableSprite player) {
        boolean jumpPressed = player.isJumpPressed();

        if (jumpPressed) {
            // Holding jump - increase compression
            compressionFrameCounter++;
            if (compressionFrameCounter >= COMPRESSION_FRAME_INTERVAL) {
                compressionFrameCounter = 0;
                if (compression < getMaxCompression()) {
                    compression++;
                }
            }
        } else if (wasJumpPressedLastFrame && compression > 0) {
            // Jump RELEASED (was pressed, now not) - launch only if compressed
            launchPlayer(player);
            wasJumpPressedLastFrame = false;
            return;
        }
        // If jump never pressed, player just stands on spring (no launch)

        wasJumpPressedLastFrame = jumpPressed;

        // Update spring position based on compression
        updateSpringPosition();

        // Update player position to follow spring
        // Note: currentSpriteY already includes compression from updateSpringPosition()
        if (isDiagonal()) {
            player.setX((short) (currentSpriteX + DIAGONAL_PLAYER_X_OFFSET));
            player.setY((short) (currentSpriteY - DIAGONAL_PLAYER_Y_OFFSET));
        } else {
            player.setX((short) currentSpriteX);
            // ROM: y_pos already includes compression, just subtract the offset
            player.setY((short) (currentSpriteY - VERTICAL_PLAYER_Y_OFFSET));
        }

        // Update animation frame based on compression level
        updateAnimationFrame();
    }

    /**
     * Updates the spring's visual position based on compression.
     * ROM: Diagonal spring always moves left (subtracts from X) regardless of facing.
     * The facing direction only affects launch direction, not compression movement.
     */
    private void updateSpringPosition() {
        if (isDiagonal()) {
            // Diagonal spring moves diagonally when compressed
            // ROM always subtracts from X (moves left) regardless of facing
            int xOffset = compression / 2;
            int yOffset = compression / 2;
            currentSpriteX = baseX - xOffset;  // Always subtract (ROM: sub.w d1,d0)
            currentSpriteY = baseY + yOffset;
        } else {
            // Vertical spring moves down when compressed
            currentSpriteX = baseX;
            currentSpriteY = baseY + compression;
        }
    }

    /**
     * Updates the animation frame based on compression level.
     * Maps compression to visual frames (0=relaxed, higher=more compressed).
     */
    private void updateAnimationFrame() {
        int maxComp = getMaxCompression();
        if (compression == 0) {
            animationFrame = 0;  // Fully extended
        } else if (compression < maxComp / 4) {
            animationFrame = 1;  // Slightly compressed
        } else if (compression < maxComp / 2) {
            animationFrame = 2;  // Mid compression
        } else if (compression < maxComp * 3 / 4) {
            animationFrame = 3;  // More compressed
        } else {
            animationFrame = 4;  // Fully compressed
        }
    }

    /**
     * Launches the player with velocity based on compression.
     * ROM: loc_2AE1C (vertical) / loc_2AE76 (diagonal)
     *
     * Diagonal springs attach the player to a 45-degree slope (not airborne).
     * Vertical springs launch straight up (airborne).
     */
    private void launchPlayer(AbstractPlayableSprite player) {
        int launchMagnitude = (compression + getBaseVelocity()) << 7;

        if (isDiagonal()) {
            // Diagonal launch - player runs on 45-degree slope
            // ROM: loc_2B068 - sets angle, clears in_air, sets inertia
            int xVel = launchMagnitude;
            int yVel = -launchMagnitude;

            // Direction based on facing
            if (isLeftFacing()) {
                xVel = -xVel;
            }

            player.setXSpeed((short) xVel);
            player.setYSpeed((short) yVel);
            // Set gSpeed/inertia for slope running
            player.setGSpeed((short) (isLeftFacing() ? -launchMagnitude : launchMagnitude));

            // ROM: Player is NOT airborne after diagonal launch
            // They run along a 45-degree slope (angle = 0xE0 / 224 / -32 signed)
            player.setAir(false);
            player.setAngle((byte) 0xE0);  // 45-degree slope angle

            // Set facing direction
            player.setDirection(isLeftFacing() ? Direction.LEFT : Direction.RIGHT);
        } else {
            // Vertical launch - straight up, airborne
            player.setYSpeed((short) -launchMagnitude);
            player.setXSpeed((short) 0);
            player.setAir(true);
            player.setGSpeed((short) 0);
        }

        // Play launch sound
        playLaunchSound();

        // Release player controls
        releasePlayer(player);
    }

    /**
     * Releases player controls and resets spring state.
     */
    private void releasePlayer(AbstractPlayableSprite player) {
        player.setControlLocked(false);
        player.setPinballMode(false);
        resetSpringState();
    }

    /**
     * Resets spring internal state without modifying player.
     * Used when player enters debug mode or otherwise leaves unexpectedly.
     */
    private void resetSpringState() {
        playerState = 0;
        compression = 0;
        compressionFrameCounter = 0;
        animationFrame = 0;
        wasJumpPressedLastFrame = false;
        currentSpriteX = baseX;
        currentSpriteY = baseY;
    }

    /**
     * Plays the CNZ launch sound effect.
     */
    private void playLaunchSound() {
        try {
            if (AudioManager.getInstance() != null) {
                AudioManager.getInstance().playSfx(GameSound.CNZ_LAUNCH);
            }
        } catch (Exception e) {
            // Prevent audio failure from breaking game logic
        }
    }

    @Override
    public boolean isSolidFor(AbstractPlayableSprite player) {
        return true;
    }

    @Override
    public SolidObjectParams getSolidParams() {
        // ROM values from disassembly:
        // Vertical: halfWidth=0x23(35), halfHeightTop=0x20(32), halfHeightBottom=0x1D(29)
        // Diagonal: halfWidth=0x23(35), halfHeightTop=0x08(8), halfHeightBottom=0x05(5)
        if (isDiagonal()) {
            return new SolidObjectParams(35, 8, 5);
        }
        return new SolidObjectParams(35, 32, 29);
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        // Update spring position (in case compression changed)
        updateSpringPosition();
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        ObjectRenderManager renderManager = LevelManager.getInstance().getObjectRenderManager();
        if (renderManager == null) {
            super.appendRenderCommands(commands);
            return;
        }

        String artKey = isDiagonal() ? Sonic2ObjectArtKeys.LAUNCHER_SPRING_DIAG
                                     : Sonic2ObjectArtKeys.LAUNCHER_SPRING_VERT;
        PatternSpriteRenderer renderer = renderManager.getRenderer(artKey);
        if (renderer == null || !renderer.isReady()) {
            super.appendRenderCommands(commands);
            return;
        }

        boolean hFlip = isLeftFacing();
        boolean vFlip = (spawn.renderFlags() & 0x2) != 0;

        renderer.drawFrameIndex(animationFrame, currentSpriteX, currentSpriteY, hFlip, vFlip);
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
