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

    // State constants (match ROM objoff_36 values)
    private static final int STATE_EMPTY = 0;
    private static final int STATE_STANDING = 1;
    private static final int STATE_LAUNCH_PENDING = 2;

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
    private int playerState = STATE_EMPTY;  // STATE_EMPTY, STATE_STANDING, or STATE_LAUNCH_PENDING
    private int compressionFrameCounter = 0;
    private int mainSpriteFrame = 1;  // Main sprite frame (1 or 5 for vibration toggle)
    private int animationTimer = 0;  // For frame toggling animation
    private int baseX;  // Original X position (for diagonal movement)
    private int baseY;  // Original Y position (for spring movement)
    private AbstractPlayableSprite trackedPlayer = null;  // Player currently on the spring
    private int launchCooldown = 0;  // Prevents re-capture immediately after launch

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
        if (player == null || launchCooldown > 0) {
            return;
        }

        // Only handle initial landing - compression is handled in update()
        // This avoids issues where moving spring causes inconsistent contact callbacks
        if (playerState == STATE_EMPTY && contact.standing() && player.getYSpeed() >= 0) {
            enterSpring(player);
        }
    }

    /**
     * Called when player first lands on the spring.
     * Locks controls and sets rolling state (ROM: loc_2AD26).
     */
    private void enterSpring(AbstractPlayableSprite player) {
        // Store player reference for update() to use
        trackedPlayer = player;

        // Lock player controls (obj_control = 0x81 in ROM)
        player.setControlLocked(true);
        player.setPinballMode(true);

        // Snap player to center of spring (use setCentreX/Y for ROM-compatible center coords)
        if (isDiagonal()) {
            player.setCentreX((short) (currentSpriteX + DIAGONAL_PLAYER_X_OFFSET));
            player.setCentreY((short) (currentSpriteY - DIAGONAL_PLAYER_Y_OFFSET));
        } else {
            player.setCentreX((short) currentSpriteX);
            player.setCentreY((short) (currentSpriteY - VERTICAL_PLAYER_Y_OFFSET));
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

        playerState = STATE_STANDING;
        compression = 0;
        compressionFrameCounter = 0;
        animationTimer = getMaxCompression() / 2;  // Initialize timer for animation
    }

    /**
     * Handles compression while player is standing on spring.
     * Compression increases every 4 frames while jump is held (ROM: loc_2AD96).
     *
     * ROM behavior (lines 57477-57492, 57636-57652):
     * - If compression > 0 AND no jump button pressed, set state to 2 (launch pending)
     * - The actual launch happens on the next frame when the per-player routine
     *   sees state 2, decrements it, and branches to launch code.
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
        } else if (compression > 0) {
            // ROM: Button NOT pressed AND compression > 0 -> set state to 2
            // The ROM doesn't require a button release transition, just checks
            // if button is currently not pressed while compression exists.
            playerState = STATE_LAUNCH_PENDING;
        }
        // If compression == 0 and button not pressed, player just stands there
        // Player position is updated in update() after updateSpringPosition()
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
     *
     * ROM behavior (s2.asm lines 57564-57577):
     * <pre>
     * loc_2ADDA:
     *     subq.b  #1,objoff_33(a0)          ; Decrement timer
     *     bpl.s   loc_2ADF8                 ; If >= 0, go to reset frame 1
     *     ; Timer underflowed - toggle and set new interval
     *     bchg    #2,mainspr_mapframe(a0)   ; Toggle frame 1 <-> 5
     *     ...
     * loc_2ADF8:
     *     move.b  #1,mainspr_mapframe(a0)   ; ALWAYS reset to frame 1
     * </pre>
     *
     * The ROM ALWAYS resets to frame 1 UNLESS the timer just underflowed.
     * Frame 5 only appears for ONE single frame before being reset.
     * This creates a quick "flash/twitch" vibration effect.
     */
    private void updateAnimationFrame() {
        if (compression == 0) {
            mainSpriteFrame = 1;
            animationTimer = getMaxCompression() / 2;  // Initialize timer
        } else {
            animationTimer--;
            if (animationTimer < 0) {
                // Timer underflow - toggle frame and reset interval
                // ROM: bchg #2,mainspr_mapframe toggles bit 2 (1 <-> 5)
                mainSpriteFrame = (mainSpriteFrame == 1) ? 5 : 1;
                animationTimer = Math.max(0, (getMaxCompression() - compression) / 2);
            } else {
                // ROM: move.b #1,mainspr_mapframe - always reset to frame 1
                // This means frame 5 only shows for ONE frame (the toggle frame)
                mainSpriteFrame = 1;
            }
        }
    }

    /**
     * Launches the player with velocity based on compression.
     * ROM: loc_2AE0C (vertical) / loc_2B018 (diagonal)
     *
     * ROM behavior: Both vertical and diagonal springs set in_air initially.
     * Diagonal springs then clear in_air and set angle 0xE0 for slope running.
     * However, our physics engine doesn't handle "imaginary slope" running,
     * so we keep the player airborne for both types to ensure correct trajectory.
     */
    private void launchPlayer(AbstractPlayableSprite player) {
        int launchMagnitude = (compression + getBaseVelocity()) << 7;

        if (isDiagonal()) {
            // Diagonal launch - ROM always launches UP-RIGHT (positive X, negative Y)
            // ROM: loc_2B018 - x_vel = +magnitude, y_vel = -magnitude
            // Note: ROM doesn't vary direction based on render flags - all diagonal springs go right
            int xVel = launchMagnitude;
            int yVel = -launchMagnitude;

            // Check render flags for horizontal flip - this flips the launch direction
            if (isLeftFacing()) {
                xVel = -xVel;
            }

            player.setXSpeed((short) xVel);
            player.setYSpeed((short) yVel);

            // ROM sets inertia and clears in_air for diagonal springs, but our
            // physics doesn't handle imaginary slope running, so keep airborne
            player.setAir(true);
            player.setGSpeed((short) 0);

            // Set facing direction based on launch direction
            player.setDirection(isLeftFacing() ? Direction.LEFT : Direction.RIGHT);
        } else {
            // Vertical launch - straight up, airborne
            // ROM: loc_2AE0C - sets y_vel negative, x_vel=0, sets in_air
            player.setYSpeed((short) -launchMagnitude);
            player.setXSpeed((short) 0);
            player.setAir(true);
            player.setGSpeed((short) 0);
        }

        // ROM: bclr #status.player.on_object,status(a1) - clear "standing on object" flag
        // This is essential to prevent the solid object system from re-capturing the player
        player.setOnObject(false);

        // Clear solid object riding state to prevent the object system from
        // continuing to track the player's position relative to the spring.
        var objectManager = LevelManager.getInstance().getObjectManager();
        if (objectManager != null) {
            objectManager.clearRidingObject();
        }

        // ROM keeps player rolling after launch (rolling flag is set in enterSpring, never cleared)
        // Ensure rolling state is preserved for proper "rolling jump" animation
        player.setRolling(true);

        // Play launch sound
        playLaunchSound();

        // Set cooldown to prevent immediate re-capture
        launchCooldown = 16;

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
        playerState = STATE_EMPTY;
        compression = 0;
        compressionFrameCounter = 0;
        mainSpriteFrame = 1;
        animationTimer = getMaxCompression() / 2;
        trackedPlayer = null;
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
        // Decrement launch cooldown
        if (launchCooldown > 0) {
            launchCooldown--;
        }

        // ROM state machine: Check for launch pending state FIRST (state 2 -> launch)
        // This ensures the launch happens on the frame AFTER button release was detected
        if (trackedPlayer != null && playerState == STATE_LAUNCH_PENDING) {
            // State 2: Launch pending - ROM triggers launch when it sees this state
            // ROM: loc_2AD7A decrements state, state 2->1 means D0!=0, branches to launch
            launchPlayer(trackedPlayer);
            return;  // Don't process anything else after launch
        }

        // Handle compression logic if player is standing on the spring (state 1)
        if (trackedPlayer != null && playerState == STATE_STANDING) {
            // Check if player entered debug mode
            if (trackedPlayer.isDebugMode()) {
                releasePlayer(trackedPlayer);
                return;
            }

            // Update compression state (may transition to STATE_LAUNCH_PENDING)
            handleCompression(trackedPlayer);
        }

        // Update spring position based on current compression
        // This must happen after handleCompression so player position matches spring
        updateSpringPosition();

        // If player is on spring (state 1 or 2), update their position to match the spring
        if (trackedPlayer != null && playerState != STATE_EMPTY) {
            if (isDiagonal()) {
                trackedPlayer.setCentreX((short) (currentSpriteX + DIAGONAL_PLAYER_X_OFFSET));
                trackedPlayer.setCentreY((short) (currentSpriteY - DIAGONAL_PLAYER_Y_OFFSET));
            } else {
                trackedPlayer.setCentreX((short) currentSpriteX);
                trackedPlayer.setCentreY((short) (currentSpriteY - VERTICAL_PLAYER_Y_OFFSET));
            }
        }

        // Update animation
        updateAnimationFrame();
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

        // ROM uses multi-sprite rendering:
        // Main sprite: body (frame 1 or 5 for vibration toggle)
        // Sub-sprite: plunger base (frame 2 or 3 based on compression)
        renderer.drawFrameIndex(mainSpriteFrame, currentSpriteX, currentSpriteY, hFlip, vFlip);

        // Sub-sprite: plunger base (frame 2 if compression < 0x10, frame 3 otherwise)
        int subFrame = (compression >= 0x10) ? 3 : 2;
        if (isDiagonal()) {
            // Diagonal: sub-sprite at same position as main
            renderer.drawFrameIndex(subFrame, currentSpriteX, currentSpriteY, hFlip, vFlip);
        } else {
            // Vertical: sub-sprite 32 pixels below main (ROM: spring Y + $20)
            renderer.drawFrameIndex(subFrame, currentSpriteX, currentSpriteY + 0x20, hFlip, vFlip);
        }
    }

    @Override
    public int getPriorityBucket() {
        return RenderPriority.clamp(4);
    }
}
