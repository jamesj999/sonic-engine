package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages checkpoint state and messages in Sonic 2 Special Stage.
 *
 * When the player reaches a checkpoint (marker 0xFE in object data):
 * 1. Rainbow checkpoint animation plays (checkpoint wings + hand)
 * 2. Ring count is compared to the requirement for this checkpoint
 * 3. If rings >= requirement: "COOL!" message displays, player continues
 * 4. If rings < requirement: "NOT ENOUGH RINGS..." displays, stage fails
 *
 * At the final checkpoint (checkpoint 3), the stage ends and the emerald is awarded
 * if the player met all ring requirements.
 *
 * Based on Obj5A implementation in s2.asm (lines 70839-71689).
 */
public class Sonic2SpecialStageCheckpoint {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStageCheckpoint.class.getName());

    /**
     * Checkpoint result after ring check.
     */
    public enum Result {
        /** Player has enough rings - continue */
        PASSED,
        /** Player doesn't have enough rings - stage fails */
        FAILED,
        /** Final checkpoint passed - stage complete, award emerald */
        STAGE_COMPLETE
    }

    /**
     * Message display state phases.
     */
    public enum MessagePhase {
        /** No message displaying */
        NONE,
        /** Rainbow animation playing (checkpoint wings coming in) */
        RAINBOW_ANIM,
        /** Main message displaying (COOL! or NOT ENOUGH RINGS...) */
        MESSAGE_DISPLAY,
        /** Message flying off screen */
        MESSAGE_FLYOUT,
        /** Fade out music (for failed) */
        FADE_OUT,
        /** Complete - ready for next state */
        COMPLETE
    }

    /**
     * Represents a single letter/character in the message.
     * Each letter has its own position and can fly off independently.
     */
    public static class MessageLetter {
        public int x;
        public int y;
        public int tileOffset;
        public int flyoutAngle;
        public int flyoutSpeed;
        public boolean visible;

        public MessageLetter(int x, int y, int tileOffset) {
            this.x = x;
            this.y = y;
            this.tileOffset = tileOffset;
            this.flyoutAngle = 0;
            this.flyoutSpeed = 8;
            this.visible = true;
        }
    }

    // Timing constants (from disassembly)
    // Original uses objoff_2A = $46 (70 decimal) for most timers
    private static final int RAINBOW_ANIM_FRAMES = 70;   // Hand/wings fly in
    private static final int MESSAGE_DISPLAY_FRAMES = 120; // Message visible (longer for readability)
    private static final int FADE_OUT_FRAMES = 48;       // $30 = 48 frames for music fade (from Obj5A_RingCheckTrigger)

    // State
    private MessagePhase phase = MessagePhase.NONE;
    private int phaseTimer = 0;
    private Result lastResult = Result.PASSED;
    private int currentCheckpoint = 0;
    private int ringRequirement = 0;
    private int ringsCollected = 0;

    // Message content
    private final List<MessageLetter> messageLetters = new ArrayList<>();
    private boolean showCheckpointHand = false;
    private int handX = 128;
    private int handY = 72;
    private int handTargetY = 72;
    private boolean handThumbsUp = true;
    private boolean handMovingDown = false;

    // Wing animation
    private int wingFrame = 0;

    // Music fade callback
    private Runnable onMusicFadeRequested;

    /**
     * Sets a callback for when music fade is requested.
     */
    public void setOnMusicFadeRequested(Runnable callback) {
        this.onMusicFadeRequested = callback;
    }

    /**
     * Triggers a checkpoint with ring requirement check.
     *
     * @param checkpointNumber The checkpoint number (0-3)
     * @param ringRequirement Required rings for this checkpoint
     * @param ringsCollected Rings the player has collected
     * @param isFinalCheckpoint True if this is checkpoint 3 (final)
     * @return The result of the checkpoint check
     */
    public Result triggerCheckpoint(int checkpointNumber, int ringRequirement,
                                    int ringsCollected, boolean isFinalCheckpoint) {
        this.currentCheckpoint = checkpointNumber;
        this.ringRequirement = ringRequirement;
        this.ringsCollected = ringsCollected;

        // Determine result
        if (ringsCollected >= ringRequirement) {
            if (isFinalCheckpoint) {
                lastResult = Result.STAGE_COMPLETE;
            } else {
                lastResult = Result.PASSED;
            }
        } else {
            lastResult = Result.FAILED;
        }

        // Start the checkpoint animation
        phase = MessagePhase.RAINBOW_ANIM;
        phaseTimer = 0;
        showCheckpointHand = true;
        handThumbsUp = (lastResult != Result.FAILED);
        handMovingDown = false;
        handY = 72;  // $48 in original
        handTargetY = 72;

        // Create the appropriate message
        createCheckpointMessage();

        LOGGER.info("Checkpoint " + checkpointNumber + " triggered: " +
                   "required=" + ringRequirement + ", collected=" + ringsCollected +
                   ", result=" + lastResult);

        return lastResult;
    }

    /**
     * Creates the message letters for the current checkpoint result.
     */
    private void createCheckpointMessage() {
        messageLetters.clear();

        if (lastResult == Result.FAILED) {
            // "NOT ENOUGH RINGS..." - multi-line
            // Line 1: "NOT ENOUGH" at y=$68
            createWordLetters("NOT", 0x5E, 0x68);
            createWordLetters("ENOUGH", 0x5E + 32, 0x68);
            // Line 2: "RINGS..." at y=$7E
            createWordLetters("RINGS", 0x5E, 0x7E);
            createWordLetters("...", 0x5E + 48, 0x7E);
        } else {
            // "COOL!" - single line centered at x=$74, y=$68
            createWordLetters("COOL!", 0x74, 0x68);
        }
    }

    /**
     * Creates MessageLetter objects for a word at the given position.
     */
    private void createWordLetters(String word, int startX, int y) {
        int x = startX;
        for (char c : word.toCharArray()) {
            int tileOffset = charToTileOffset(c);
            if (tileOffset >= 0) {
                messageLetters.add(new MessageLetter(x, y, tileOffset));
            }
            x += 8;  // Each letter is 8 pixels wide
        }
    }

    /**
     * Converts a character to its tile offset in SpecialMessages art.
     *
     * The charset mapping in s2.asm gives FRAME numbers, which then map to
     * actual tile offsets via obj5A.asm sprite mappings. This method returns
     * the actual tile offsets directly.
     *
     * Frame-to-tile mappings from obj5A.asm (same as used for intro messages):
     * - Frame 0 (G): tile 0x04
     * - Frame 1 (E): tile 0x02
     * - Frame 2 (T): tile 0x14
     * - Frame 3 (R): tile 0x10
     * - Frame 4 (I): tile 0x08
     * - Frame 5 (N): tile 0x0C
     * - Frame 6 (S): tile 0x12
     * - Frame 7 (C): tile 0x00
     * - Frame 8 (O): tile 0x6A
     * - Frame 9 (L): tile 0x0A
     * - Frame 10 (U): tile 0x16
     * - Frame 11 (H): tile 0x06
     * - Frame 17 (!): tile 0x18
     * - Frame 18 (.): tile 0x0E
     */
    private int charToTileOffset(char c) {
        return switch (Character.toUpperCase(c)) {
            case 'C' -> 0x00;  // Frame 7
            case 'E' -> 0x02;  // Frame 1
            case 'G' -> 0x04;  // Frame 0
            case 'H' -> 0x06;  // Frame 11
            case 'I' -> 0x08;  // Frame 4
            case 'L' -> 0x0A;  // Frame 9
            case 'N' -> 0x0C;  // Frame 5
            case 'O' -> 0x6A;  // Frame 8 - Note: outside main message art range
            case 'R' -> 0x10;  // Frame 3
            case 'S' -> 0x12;  // Frame 6
            case 'T' -> 0x14;  // Frame 2
            case 'U' -> 0x16;  // Frame 10
            case '!' -> 0x18;  // Frame 17
            case '.' -> 0x0E;  // Frame 18
            default -> -1;    // Unknown character
        };
    }

    /**
     * Updates the checkpoint animation state.
     *
     * @return true if the checkpoint sequence is complete
     */
    public boolean update() {
        phaseTimer++;

        switch (phase) {
            case RAINBOW_ANIM:
                updateRainbowPhase();
                break;
            case MESSAGE_DISPLAY:
                updateMessageDisplayPhase();
                break;
            case MESSAGE_FLYOUT:
                updateMessageFlyoutPhase();
                break;
            case FADE_OUT:
                updateFadeOutPhase();
                break;
            case COMPLETE:
                return true;
            default:
                break;
        }

        // Update hand wave animation
        if (showCheckpointHand) {
            updateHandAnimation();
        }

        return phase == MessagePhase.COMPLETE;
    }

    private void updateRainbowPhase() {
        // Rainbow animation: checkpoint wings fly in
        wingFrame++;

        if (phaseTimer >= RAINBOW_ANIM_FRAMES) {
            phase = MessagePhase.MESSAGE_DISPLAY;
            phaseTimer = 0;
        }
    }

    private void updateMessageDisplayPhase() {
        // Message displaying with hand waving
        if (phaseTimer >= MESSAGE_DISPLAY_FRAMES) {
            if (lastResult == Result.FAILED) {
                // For failed checkpoint, start music fade then complete
                phase = MessagePhase.FADE_OUT;
                phaseTimer = 0;
                // Request music fade
                if (onMusicFadeRequested != null) {
                    onMusicFadeRequested.run();
                }
                LOGGER.fine("Starting fade out phase for failed checkpoint");
            } else {
                // Start flyout for success
                phase = MessagePhase.MESSAGE_FLYOUT;
                phaseTimer = 0;
                initializeFlyout();
            }
        }
    }

    private void updateFadeOutPhase() {
        // Wait for music to fade before completing
        if (phaseTimer >= FADE_OUT_FRAMES) {
            phase = MessagePhase.COMPLETE;
            showCheckpointHand = false;
            LOGGER.fine("Fade out complete, checkpoint animation finished");
        }
    }

    private void initializeFlyout() {
        // Calculate flyout angles for each letter
        // Original: CalcAngle from letter position to center-top of screen
        for (MessageLetter letter : messageLetters) {
            // Simplified angle calculation
            int dx = 0x80 - letter.x;  // Distance to center
            int dy = 0x70 - letter.y;  // Distance to top-center
            // Use simple angle approximation
            letter.flyoutAngle = (int) (Math.atan2(dy, dx) * 128 / Math.PI);
            letter.flyoutSpeed = 8;
        }
    }

    private void updateMessageFlyoutPhase() {
        // Move letters off screen
        boolean allGone = true;
        for (MessageLetter letter : messageLetters) {
            if (!letter.visible) continue;

            // Move letter based on angle
            double angle = letter.flyoutAngle * Math.PI / 128.0;
            letter.x += (int) (Math.cos(angle) * letter.flyoutSpeed);
            letter.y += (int) (Math.sin(angle) * letter.flyoutSpeed);

            // Check if off screen
            if (letter.x < 0 || letter.x > 256 || letter.y < 0 || letter.y > 224) {
                letter.visible = false;
            } else {
                allGone = false;
            }
        }

        if (allGone) {
            phase = MessagePhase.COMPLETE;
            showCheckpointHand = false;
        }
    }

    private void updateHandAnimation() {
        // Bobbing hand animation from Obj5A_Handshake
        if (!handMovingDown) {
            handY--;
            if (handY <= handTargetY - 4) {
                handMovingDown = true;
            }
        } else {
            handY++;
            if (handY >= handTargetY + 4) {
                handMovingDown = false;
            }
        }
    }

    /**
     * Resets the checkpoint state.
     */
    public void reset() {
        phase = MessagePhase.NONE;
        phaseTimer = 0;
        messageLetters.clear();
        showCheckpointHand = false;
        currentCheckpoint = 0;
    }

    // ========== Getters for rendering ==========

    public MessagePhase getPhase() {
        return phase;
    }

    public boolean isActive() {
        return phase != MessagePhase.NONE && phase != MessagePhase.COMPLETE;
    }

    public boolean isMessageVisible() {
        // Message should appear during RAINBOW_ANIM phase (WITH the thumbs up, not after)
        // as well as MESSAGE_DISPLAY, MESSAGE_FLYOUT, and FADE_OUT
        return phase == MessagePhase.RAINBOW_ANIM ||
               phase == MessagePhase.MESSAGE_DISPLAY || phase == MessagePhase.MESSAGE_FLYOUT ||
               phase == MessagePhase.FADE_OUT;
    }

    public List<MessageLetter> getMessageLetters() {
        return messageLetters;
    }

    public boolean isHandVisible() {
        return showCheckpointHand && (phase == MessagePhase.RAINBOW_ANIM ||
               phase == MessagePhase.MESSAGE_DISPLAY || phase == MessagePhase.MESSAGE_FLYOUT ||
               phase == MessagePhase.FADE_OUT);
    }

    public int getHandX() {
        return handX;
    }

    public int getHandY() {
        return handY;
    }

    public boolean isHandThumbsUp() {
        return handThumbsUp;
    }

    public Result getLastResult() {
        return lastResult;
    }

    public int getCurrentCheckpoint() {
        return currentCheckpoint;
    }

    public int getRingRequirement() {
        return ringRequirement;
    }

    public int getRingsCollected() {
        return ringsCollected;
    }
}
