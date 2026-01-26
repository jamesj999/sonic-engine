package uk.co.jamesj999.sonic.game.sonic2.objects;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.configuration.SonicConfiguration;
import uk.co.jamesj999.sonic.configuration.SonicConfigurationService;
import uk.co.jamesj999.sonic.graphics.GLCommand;
import uk.co.jamesj999.sonic.level.objects.ObjectSpawn;
import uk.co.jamesj999.sonic.sprites.animation.SpriteAnimationProfile;
import uk.co.jamesj999.sonic.sprites.animation.ScriptedVelocityAnimationProfile;
import uk.co.jamesj999.sonic.sprites.playable.AbstractPlayableSprite;
import uk.co.jamesj999.sonic.sprites.managers.SpriteManager;

import java.util.List;

/**
 * ForcedSpin / Pinball Mode object (Object 0x84).
 * Used in Casino Night Zone and Hill Top Zone.
 * <p>
 * This is an invisible trigger object that forces Sonic into/out of rolling state
 * when he crosses the trigger zone. It enables "pinball mode" where rolling cannot
 * be cleared on landing.
 * <p>
 * Based on Obj84 from the Sonic 2 disassembly.
 *
 * <h3>Subtype Encoding:</h3>
 * <ul>
 *   <li>Bit 2 (0x04): Direction flag - 0=horizontal trigger, 1=vertical trigger</li>
 *   <li>Bits 0-1 (0x03): Width index into table: 0=32px, 1=64px, 2=128px, 3=256px</li>
 * </ul>
 *
 * <h3>X_flip Behavior (render_flags bit):</h3>
 * <ul>
 *   <li>X_flip = 0 (unflipped): Crossing trigger line enables pinball mode</li>
 *   <li>X_flip = 1 (flipped): Crossing trigger line disables pinball mode</li>
 * </ul>
 *
 * <h3>Trigger Logic:</h3>
 * <p>
 * The object tracks per-character crossing state. When player crosses the trigger line
 * in one direction, the action is applied and state is set. When player crosses back
 * in the opposite direction, state resets and the OPPOSITE action is applied.
 * </p>
 */
public class ForcedSpinObjectInstance extends BoxObjectInstance {

    // Width lookup table from disassembly word_211E8
    private static final int[] WIDTH_TABLE = {0x20, 0x40, 0x80, 0x100};

    // Debug colors
    private static final float ENABLE_R = 0.0f;
    private static final float ENABLE_G = 1.0f;
    private static final float ENABLE_B = 0.0f;
    private static final float DISABLE_R = 1.0f;
    private static final float DISABLE_G = 0.0f;
    private static final float DISABLE_B = 0.0f;

    private final boolean verticalMode;    // bit 2 of subtype: 0=horizontal, 1=vertical
    private final int triggerWidth;        // half-width from WIDTH_TABLE
    private final boolean xFlipped;        // x_flip bit from spawn (determines action direction)

    // Per-character crossing state (true = player is past the trigger line)
    // Matches objoff_34 (Sonic) and objoff_35 (Tails) from disassembly
    private boolean sonicPastTrigger;
    private boolean tailsPastTrigger;

    // Track initialization state
    private boolean initialized;

    public ForcedSpinObjectInstance(ObjectSpawn spawn, String name) {
        super(spawn, name,
                WIDTH_TABLE[spawn.subtype() & 0x03],  // halfWidth from width table
                WIDTH_TABLE[spawn.subtype() & 0x03],  // halfHeight same as width (square trigger zone)
                // Color based on x_flip: green for enable, red for disable
                (spawn.renderFlags() & 0x01) == 0 ? ENABLE_R : DISABLE_R,
                (spawn.renderFlags() & 0x01) == 0 ? ENABLE_G : DISABLE_G,
                (spawn.renderFlags() & 0x01) == 0 ? ENABLE_B : DISABLE_B,
                false  // not high priority (invisible in normal gameplay)
        );

        this.verticalMode = (spawn.subtype() & 0x04) != 0;
        this.triggerWidth = WIDTH_TABLE[spawn.subtype() & 0x03];
        this.xFlipped = (spawn.renderFlags() & 0x01) != 0;
    }

    @Override
    public void update(int frameCounter, AbstractPlayableSprite player) {
        if (player == null) {
            return;
        }

        // Initialize crossing state based on player's current position
        if (!initialized) {
            initializeCrossingState(player);
            initialized = true;
        }

        // Check for Sonic (main player)
        checkPlayerCrossing(player, true);

        // Check for Tails (sidekick) if present
        AbstractPlayableSprite sidekick = SpriteManager.getInstance().getSidekick();
        if (sidekick != null) {
            checkPlayerCrossing(sidekick, false);
        }
    }

    /**
     * Initializes the crossing state based on the player's current position relative
     * to the trigger line. This ensures correct behavior if player is already past
     * the trigger when the object loads.
     */
    private void initializeCrossingState(AbstractPlayableSprite player) {
        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        if (verticalMode) {
            // Vertical mode: check if player is below trigger line
            // Disassembly uses strictly greater (bhs branch skips if objY >= playerY)
            sonicPastTrigger = playerY > objY;
        } else {
            // Horizontal mode: check if player is to the right of trigger line
            // Disassembly uses strictly greater (bhs branch skips if objX >= playerX)
            sonicPastTrigger = playerX > objX;
        }

        // Initialize Tails state similarly if present
        AbstractPlayableSprite sidekick = SpriteManager.getInstance().getSidekick();
        if (sidekick != null) {
            int sidekickX = sidekick.getCentreX();
            int sidekickY = sidekick.getCentreY();
            if (verticalMode) {
                tailsPastTrigger = sidekickY > objY;
            } else {
                tailsPastTrigger = sidekickX > objX;
            }
        }
    }

    /**
     * Checks if a player has crossed the trigger line and applies the appropriate action.
     *
     * @param player the player to check
     * @param isSonic true for Sonic (main character), false for Tails
     */
    private void checkPlayerCrossing(AbstractPlayableSprite player, boolean isSonic) {
        int objX = spawn.x();
        int objY = spawn.y();
        int playerX = player.getCentreX();
        int playerY = player.getCentreY();

        boolean pastTrigger = isSonic ? sonicPastTrigger : tailsPastTrigger;

        if (verticalMode) {
            // Vertical mode: trigger line is horizontal at objY
            if (!pastTrigger) {
                // Player was above trigger line
                if (playerY >= objY) {
                    // Crossed to below - set flag and check range
                    if (isSonic) {
                        sonicPastTrigger = true;
                    } else {
                        tailsPastTrigger = true;
                    }
                    // Check if player X is within range
                    if (isWithinRange(playerX, objX, triggerWidth)) {
                        applyAction(player, !xFlipped);  // Primary direction: enable if not flipped
                    }
                }
            } else {
                // Player was below trigger line
                if (playerY < objY) {
                    // Crossed back to above - reset flag and apply opposite action
                    if (isSonic) {
                        sonicPastTrigger = false;
                    } else {
                        tailsPastTrigger = false;
                    }
                    // Check if player X is within range
                    if (isWithinRange(playerX, objX, triggerWidth)) {
                        applyAction(player, xFlipped);  // Opposite direction: disable if not flipped
                    }
                }
            }
        } else {
            // Horizontal mode: trigger line is vertical at objX
            if (!pastTrigger) {
                // Player was to the left of trigger line
                if (playerX >= objX) {
                    // Crossed to right - set flag and check range
                    if (isSonic) {
                        sonicPastTrigger = true;
                    } else {
                        tailsPastTrigger = true;
                    }
                    // Check if player Y is within range
                    if (isWithinRange(playerY, objY, triggerWidth)) {
                        applyAction(player, !xFlipped);  // Primary direction: enable if not flipped
                    }
                }
            } else {
                // Player was to the right of trigger line
                if (playerX < objX) {
                    // Crossed back to left - reset flag and apply opposite action
                    if (isSonic) {
                        sonicPastTrigger = false;
                    } else {
                        tailsPastTrigger = false;
                    }
                    // Check if player Y is within range
                    if (isWithinRange(playerY, objY, triggerWidth)) {
                        applyAction(player, xFlipped);  // Opposite direction: disable if not flipped
                    }
                }
            }
        }
    }

    /**
     * Checks if a position is within range of the center point.
     */
    private boolean isWithinRange(int pos, int center, int halfWidth) {
        int delta = pos - center;
        return delta >= -halfWidth && delta < halfWidth;
    }

    /**
     * Applies the pinball mode action to the player.
     *
     * @param player the player to modify
     * @param enablePinball true to enable pinball mode, false to disable
     */
    private void applyAction(AbstractPlayableSprite player, boolean enablePinball) {
        if (enablePinball) {
            enablePinballMode(player);
        } else {
            disablePinballMode(player);
        }
    }

    /**
     * Enables pinball mode on the player.
     * Based on loc_212C4 from the disassembly:
     * 1. If already rolling, return early (no sound/adjustment needed)
     * 2. Set rolling = true (which handles hitbox and Y adjustment)
     * 3. Set animation to Roll
     * 4. Play roll sound
     */
    private void enablePinballMode(AbstractPlayableSprite player) {
        // Enable pinball mode flag - this prevents rolling from being cleared
        player.setPinballMode(true);

        // If already rolling, no need to do anything else
        if (player.getRolling()) {
            return;
        }

        // Force into rolling state
        // setRolling(true) handles:
        // - Setting y_radius to 14, x_radius to 7 (via applyRollingRadii)
        // - Adjusting Y position by +5 pixels
        // - Setting height to roll height
        player.setRolling(true);

        // Set roll animation
        forceRollAnimation(player);

        // Play roll sound
        playRollSound();
    }

    /**
     * Disables pinball mode on the player.
     * The player will continue rolling until speed reaches 0, at which point
     * rolling will clear naturally (since pinballMode is now false).
     */
    private void disablePinballMode(AbstractPlayableSprite player) {
        player.setPinballMode(false);
    }

    /**
     * Forces the roll animation on the player.
     */
    private void forceRollAnimation(AbstractPlayableSprite player) {
        SpriteAnimationProfile profile = player.getAnimationProfile();
        if (profile instanceof ScriptedVelocityAnimationProfile velocityProfile) {
            int rollId = velocityProfile.getRollAnimId();
            player.setAnimationId(rollId);
            player.setAnimationFrameIndex(0);
            player.setAnimationTick(0);
        }
    }

    /**
     * Plays the roll sound effect.
     */
    private void playRollSound() {
        try {
            AudioManager audioManager = AudioManager.getInstance();
            if (audioManager != null) {
                audioManager.playSfx(GameSound.ROLLING);
            }
        } catch (Exception e) {
            // Don't let audio failure break game logic
        }
    }

    @Override
    public void appendRenderCommands(List<GLCommand> commands) {
        // Only render in debug mode
        if (!isDebugViewEnabled()) {
            return;
        }

        // Render the trigger zone box using parent class
        super.appendRenderCommands(commands);

        // Draw a line indicating the trigger direction
        int centerX = spawn.x();
        int centerY = spawn.y();

        if (verticalMode) {
            // Vertical mode: horizontal trigger line
            appendLine(commands, centerX - triggerWidth, centerY, centerX + triggerWidth, centerY);
        } else {
            // Horizontal mode: vertical trigger line
            appendLine(commands, centerX, centerY - triggerWidth, centerX, centerY + triggerWidth);
        }
    }

    private boolean isDebugViewEnabled() {
        return SonicConfigurationService.getInstance()
                .getBoolean(SonicConfiguration.DEBUG_VIEW_ENABLED);
    }
}
