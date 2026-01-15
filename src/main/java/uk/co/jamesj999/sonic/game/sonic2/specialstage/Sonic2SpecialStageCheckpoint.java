package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Manages checkpoint state and messages in Sonic 2 Special Stage.
 *
 * When the player reaches a checkpoint (marker 0xFE in object data):
 * 1. Rainbow ring animation plays at the top of the track
 * 2. Ring count is compared to the requirement for this checkpoint
 * 3. Winged hand + "COOL!" message displays if rings >= requirement
 * 4. "NOT ENOUGH RINGS..." displays if rings < requirement, stage fails
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
        /** Rainbow ring animation playing at top of track */
        RAINBOW_RINGS,
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
     * Callback when the checkpoint result is resolved (after rainbow completes).
     */
    public interface CheckpointResolvedCallback {
        void onCheckpointResolved(Result result, int checkpointNumber,
                                  int ringRequirement, int ringsCollected, boolean isFinalCheckpoint);
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

    /**
     * Represents a single rainbow ring in the checkpoint animation.
     */
    public static class RainbowRing {
        private final int baseIndex;
        private int frameIndex;
        private int positionOffset;
        private int mappingFrame;
        private int x;
        private int y;
        private boolean active;

        private RainbowRing(int baseIndex) {
            this.baseIndex = baseIndex;
            this.frameIndex = 0;
            this.positionOffset = 0;
            this.mappingFrame = -1;
            this.active = true;
        }

        public int getMappingFrame() {
            return mappingFrame;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public boolean isVisible() {
            return active && mappingFrame >= 0;
        }
    }

    // Timing constants (from disassembly)
    // Original uses objoff_2A = $46 (70 decimal) for most timers
    private static final int MESSAGE_DISPLAY_FRAMES = 120; // Message visible (longer for readability)
    private static final int FADE_OUT_FRAMES = 48;       // $30 = 48 frames for music fade (from Obj5A_RingCheckTrigger)

    // Checkpoint rainbow animation data (Obj5A_Rainbow_Frames / Obj5A_Rainbow_Positions)
    private static final int[] RAINBOW_FRAMES = {
        0, 1, 1, 1, 2, 4, 6, 8, 9, -1
    };
    private static final int[] RAINBOW_POSITIONS = {
        0xF6, 0xF6, 0x70, 0x5E, 0x76, 0x58, 0x7E, 0x56, 0x88, 0x58, 0x8E, 0x5E, 0xF6, 0xF6,
        0xF6, 0xF6, 0x6D, 0x5A, 0x74, 0x54, 0x7E, 0x50, 0x8A, 0x54, 0x92, 0x5A, 0xF6, 0xF6,
        0xF6, 0xF6, 0x6A, 0x58, 0x72, 0x50, 0x7E, 0x4C, 0x8C, 0x50, 0x94, 0x58, 0xF6, 0xF6,
        0xF6, 0xF6, 0x68, 0x56, 0x70, 0x4C, 0x7E, 0x48, 0x8E, 0x4C, 0x96, 0x56, 0xF6, 0xF6,
        0x62, 0x5E, 0x66, 0x50, 0x70, 0x46, 0x7E, 0x42, 0x8E, 0x46, 0x98, 0x50, 0x9C, 0x5E,
        0x5C, 0x5A, 0x62, 0x4A, 0x70, 0x3E, 0x7E, 0x38, 0x8E, 0x3E, 0x9C, 0x4A, 0xA2, 0x5A,
        0x54, 0x54, 0x5A, 0x3E, 0x6A, 0x30, 0x7E, 0x2A, 0x94, 0x30, 0xA4, 0x3E, 0xAA, 0x54,
        0x42, 0x4A, 0x4C, 0x28, 0x62, 0x12, 0x7E, 0x0A, 0x9C, 0x12, 0xB2, 0x28, 0xBC, 0x4A,
        0x16, 0x26, 0x28, 0xFC, 0xEC, 0xEC, 0xEC, 0xEC, 0xEC, 0xEC, 0xD6, 0xFC, 0xE8, 0x26
    };
    private static final int RAINBOW_RING_COUNT = 7;
    private static final int RAINBOW_POSITION_STRIDE = 0x0E;
    private static final int RAINBOW_COMPLETE_TRIGGER_X = 0xE8;

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

    // Checkpoint rainbow rings
    private final List<RainbowRing> rainbowRings = new ArrayList<>();

    // Pending checkpoint info (resolved after rainbow completes)
    private int pendingRingRequirement = 0;
    private int pendingRingsCollected = 0;
    private boolean pendingFinalCheckpoint = false;

    // Callbacks
    private CheckpointResolvedCallback onCheckpointResolved;
    private boolean rainbowOnly = false;

    // Music fade callback
    private Runnable onMusicFadeRequested;

    /**
     * Sets a callback for when music fade is requested.
     */
    public void setOnMusicFadeRequested(Runnable callback) {
        this.onMusicFadeRequested = callback;
    }

    /**
     * Sets a callback for when a checkpoint result is resolved (after rainbow).
     */
    public void setOnCheckpointResolved(CheckpointResolvedCallback callback) {
        this.onCheckpointResolved = callback;
    }

    /**
     * Starts the checkpoint sequence (rainbow first, then ring check + message).
     *
     * @param checkpointNumber The checkpoint number (1-4)
     * @param ringRequirement Required rings for this checkpoint
     * @param ringsCollected Rings the player has collected
     * @param isFinalCheckpoint True if this is checkpoint 4 (final)
     */
    public void beginCheckpoint(int checkpointNumber, int ringRequirement,
                                int ringsCollected, boolean isFinalCheckpoint) {
        rainbowOnly = false;
        this.currentCheckpoint = checkpointNumber;
        this.pendingRingRequirement = ringRequirement;
        this.pendingRingsCollected = ringsCollected;
        this.pendingFinalCheckpoint = isFinalCheckpoint;

        // Start the checkpoint rainbow animation
        phase = MessagePhase.RAINBOW_RINGS;
        phaseTimer = 0;
        showCheckpointHand = false;
        messageLetters.clear();
        startRainbowRings();

        LOGGER.info("Checkpoint " + checkpointNumber + " triggered: " +
                   "required=" + ringRequirement + ", collected=" + ringsCollected);
    }

    /**
     * Starts a rainbow-only checkpoint animation (visual only).
     */
    public void beginRainbowOnly() {
        rainbowOnly = true;
        currentCheckpoint = 0;
        pendingRingRequirement = 0;
        pendingRingsCollected = 0;
        pendingFinalCheckpoint = false;
        ringRequirement = 0;
        ringsCollected = 0;
        lastResult = Result.PASSED;

        phase = MessagePhase.RAINBOW_RINGS;
        phaseTimer = 0;
        showCheckpointHand = false;
        messageLetters.clear();
        startRainbowRings();
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
    public boolean update(boolean drawingIndex4) {
        switch (phase) {
            case RAINBOW_RINGS:
                updateRainbowPhase(drawingIndex4);
                break;
            case MESSAGE_DISPLAY:
                phaseTimer++;
                updateMessageDisplayPhase();
                break;
            case MESSAGE_FLYOUT:
                phaseTimer++;
                updateMessageFlyoutPhase();
                break;
            case FADE_OUT:
                phaseTimer++;
                updateFadeOutPhase();
                break;
            case COMPLETE:
                return true;
            default:
                break;
        }

        // Update hand wave animation
        if (showCheckpointHand && phase != MessagePhase.RAINBOW_RINGS) {
            updateHandAnimation();
        }

        return phase == MessagePhase.COMPLETE;
    }

    private void updateRainbowPhase(boolean drawingIndex4) {
        // Rainbow animation: checkpoint rings fly across track top
        if (!updateRainbowRingsPhase(drawingIndex4)) {
            return;
        }

        if (rainbowOnly) {
            phase = MessagePhase.COMPLETE;
            showCheckpointHand = false;
            return;
        }

        resolveCheckpointResult();
        beginMessagePhase();
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

    private void beginMessagePhase() {
        phase = MessagePhase.MESSAGE_DISPLAY;
        phaseTimer = 0;
        showCheckpointHand = true;
        handThumbsUp = (lastResult != Result.FAILED);
        handMovingDown = false;
        handY = 72;  // $48 in original
        handTargetY = 72;

        // Create the appropriate message
        createCheckpointMessage();

        if (onCheckpointResolved != null) {
            onCheckpointResolved.onCheckpointResolved(lastResult, currentCheckpoint,
                    ringRequirement, ringsCollected, pendingFinalCheckpoint);
        }
    }

    private void resolveCheckpointResult() {
        ringRequirement = pendingRingRequirement;
        ringsCollected = pendingRingsCollected;

        if (ringsCollected >= ringRequirement) {
            if (pendingFinalCheckpoint) {
                lastResult = Result.STAGE_COMPLETE;
            } else {
                lastResult = Result.PASSED;
            }
        } else {
            lastResult = Result.FAILED;
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
        // From s2.asm Obj5A_TextFlyoutInit (line 71447-71450):
        // The angle is calculated FROM center (0x80, 0x70) TO letter position,
        // so letters fly AWAY from the center point.
        // For COOL! at y=0x68 (above center 0x70), dy is negative → letters fly UP
        for (MessageLetter letter : messageLetters) {
            // Calculate vector from center to letter position
            int dx = letter.x - 0x80;  // letter pos - center (positive = right of center)
            int dy = letter.y - 0x70;  // letter pos - center (negative = above center)
            // Use atan2 to get angle (letters fly in direction away from center)
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

    private void startRainbowRings() {
        rainbowRings.clear();
        for (int i = 0; i < RAINBOW_RING_COUNT; i++) {
            rainbowRings.add(new RainbowRing(i));
        }
    }

    private boolean updateRainbowRingsPhase(boolean drawingIndex4) {
        // If no rings have been spawned yet, treat as complete
        if (rainbowRings.isEmpty()) {
            return true;
        }

        boolean triggerComplete = false;

        if (drawingIndex4) {
            for (RainbowRing ring : rainbowRings) {
                if (!ring.active) {
                    continue;
                }

                if (ring.frameIndex < 0 || ring.frameIndex >= RAINBOW_FRAMES.length) {
                    ring.active = false;
                    ring.mappingFrame = -1;
                    continue;
                }

                int frame = RAINBOW_FRAMES[ring.frameIndex];
                if (frame < 0) {
                    ring.active = false;
                    ring.mappingFrame = -1;
                    if (ring.x == RAINBOW_COMPLETE_TRIGGER_X) {
                        triggerComplete = true;
                    }
                    continue;
                }

                ring.mappingFrame = frame;
                ring.frameIndex++;

                int posIndex = ring.baseIndex * 2 + ring.positionOffset;
                if (posIndex + 1 >= RAINBOW_POSITIONS.length) {
                    ring.active = false;
                    ring.mappingFrame = -1;
                    continue;
                }

                ring.x = RAINBOW_POSITIONS[posIndex];
                ring.y = RAINBOW_POSITIONS[posIndex + 1];
                ring.positionOffset += RAINBOW_POSITION_STRIDE;
            }
        }

        if (triggerComplete) {
            return true;
        }

        for (RainbowRing ring : rainbowRings) {
            if (ring.active) {
                return false;
            }
        }

        return true;
    }

    /**
     * Resets the checkpoint state.
     */
    public void reset() {
        phase = MessagePhase.NONE;
        phaseTimer = 0;
        messageLetters.clear();
        rainbowRings.clear();
        showCheckpointHand = false;
        currentCheckpoint = 0;
        ringRequirement = 0;
        ringsCollected = 0;
        pendingRingRequirement = 0;
        pendingRingsCollected = 0;
        pendingFinalCheckpoint = false;
        lastResult = Result.PASSED;
        rainbowOnly = false;
    }

    // ========== Getters for rendering ==========

    public MessagePhase getPhase() {
        return phase;
    }

    public boolean isActive() {
        return phase != MessagePhase.NONE && phase != MessagePhase.COMPLETE;
    }

    public boolean isMessageVisible() {
        // Message should appear during MESSAGE_DISPLAY, MESSAGE_FLYOUT, and FADE_OUT
        return phase == MessagePhase.MESSAGE_DISPLAY || phase == MessagePhase.MESSAGE_FLYOUT ||
               phase == MessagePhase.FADE_OUT;
    }

    public List<MessageLetter> getMessageLetters() {
        return messageLetters;
    }

    public boolean isRainbowActive() {
        return phase == MessagePhase.RAINBOW_RINGS;
    }

    public List<RainbowRing> getRainbowRings() {
        return rainbowRings;
    }

    public boolean isHandVisible() {
        return showCheckpointHand && (phase == MessagePhase.MESSAGE_DISPLAY ||
               phase == MessagePhase.MESSAGE_FLYOUT || phase == MessagePhase.FADE_OUT);
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
