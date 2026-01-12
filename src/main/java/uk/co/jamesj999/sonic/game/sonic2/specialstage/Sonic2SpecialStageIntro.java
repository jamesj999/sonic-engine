package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Manages the intro sequence for Sonic 2 Special Stage.
 *
 * The intro sequence matches the original game's Obj5F (START banner) behavior:
 * 1. DROP phase: Banner drops from y=-64 to y=72 (136 frames at 1 pixel/frame)
 * 2. WAIT1 phase: Pause while letters "explode" off banner (15 frames)
 * 3. WAIT2 phase: Display ring requirement message (31 frames)
 * 4. GAMEPLAY: Normal gameplay begins (SpecialStage_Started is set)
 *
 * The "GET X RINGS" message appears at the start of GAMEPLAY and displays
 * for 70 frames before flying off screen.
 *
 * During the intro sequence:
 * - Track animation runs normally
 * - Player input is ignored
 * - Banner and message sprites are rendered on top
 */
public class Sonic2SpecialStageIntro {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStageIntro.class.getName());

    /**
     * Intro sequence phases.
     */
    public enum Phase {
        /** Banner dropping from top of screen */
        DROP,
        /** Pause after banner lands, letters fly off */
        WAIT1,
        /** Ring requirement message displayed */
        WAIT2,
        /** Message flying off screen */
        MESSAGE_FLYOUT,
        /** Intro complete, normal gameplay */
        GAMEPLAY
    }

    private Phase currentPhase = Phase.DROP;
    private int phaseTimer = 0;
    private int frameCounter = 0;

    // Banner state
    private int bannerX = INTRO_BANNER_X;
    private int bannerY = INTRO_BANNER_START_Y;
    private boolean bannerVisible = true;

    // Message state
    private int messageX = 128;  // Center of H32 screen
    private int messageY = 108;  // Below center (y=$6C in original)
    private boolean messageVisible = false;
    private int ringRequirement = 0;

    // Letter flyout state (simplified - original spawns 7 child objects)
    private boolean lettersFlying = false;
    private int letterFlyoutProgress = 0;

    /**
     * Initializes the intro sequence for the specified stage.
     *
     * @param stageIndex Stage index (0-6)
     * @param ringRequirement Number of rings required for this stage/checkpoint
     */
    public void initialize(int stageIndex, int ringRequirement) {
        this.ringRequirement = ringRequirement;
        currentPhase = Phase.DROP;
        phaseTimer = 0;
        frameCounter = 0;

        bannerX = INTRO_BANNER_X;
        bannerY = INTRO_BANNER_START_Y;
        bannerVisible = true;

        messageX = 128;
        messageY = 108;
        messageVisible = false;

        lettersFlying = false;
        letterFlyoutProgress = 0;

        LOGGER.info("Intro sequence initialized for stage " + (stageIndex + 1) +
                    ", ring requirement: " + ringRequirement);
    }

    /**
     * Updates the intro sequence for one frame.
     *
     * @return true if gameplay can begin (intro complete or in gameplay phase)
     */
    public boolean update() {
        frameCounter++;

        switch (currentPhase) {
            case DROP:
                updateDropPhase();
                break;
            case WAIT1:
                updateWait1Phase();
                break;
            case WAIT2:
                updateWait2Phase();
                break;
            case MESSAGE_FLYOUT:
                updateMessageFlyoutPhase();
                break;
            case GAMEPLAY:
                // Intro complete, gameplay is active
                return true;
        }

        return currentPhase == Phase.GAMEPLAY;
    }

    private void updateDropPhase() {
        // Move banner down at 1 pixel per frame
        bannerY += INTRO_BANNER_VELOCITY;
        phaseTimer++;

        // Check if banner has reached final position
        if (bannerY >= INTRO_BANNER_END_Y) {
            bannerY = INTRO_BANNER_END_Y;
            currentPhase = Phase.WAIT1;
            phaseTimer = 0;
            LOGGER.fine("Intro: DROP complete, entering WAIT1");
        }
    }

    private void updateWait1Phase() {
        phaseTimer++;

        // Start letter flyout animation
        if (!lettersFlying) {
            lettersFlying = true;
            letterFlyoutProgress = 0;
        }

        // Update letter flyout (simplified - original uses 7 child sprites)
        letterFlyoutProgress++;

        // After WAIT1_FRAMES, transition to WAIT2.
        // Original: counter starts at $F (15), decrements with bne (branch if != 0).
        // Counts 15->14->...->1->0, exiting at 0. That's 15 frames total.
        // Java: counter starts at 0, increments to 15. Same 15 frames.
        if (phaseTimer >= INTRO_WAIT1_FRAMES) {
            currentPhase = Phase.WAIT2;
            phaseTimer = 0;
            bannerVisible = false;  // Banner disappears after letters fly off
            messageVisible = true;  // Ring requirement message appears
            LOGGER.fine("Intro: WAIT1 complete, entering WAIT2, showing ring requirement");
        }
    }

    private void updateWait2Phase() {
        phaseTimer++;

        // After WAIT2_FRAMES, transition to message flyout phase.
        // Original: counter starts at $1E (30), decrements with bpl (branch if >= 0).
        // Counts 30->29->...->0->-1, exiting at -1. That's 31 frames total.
        // Java: counter starts at 0, increments to 31. Same 31 frames.
        if (phaseTimer >= INTRO_WAIT2_FRAMES) {
            currentPhase = Phase.MESSAGE_FLYOUT;
            phaseTimer = 0;
            LOGGER.fine("Intro: WAIT2 complete, entering MESSAGE_FLYOUT");
        }
    }

    private void updateMessageFlyoutPhase() {
        phaseTimer++;

        // Message displays for MESSAGE_DISPLAY_FRAMES then flies off
        if (phaseTimer >= MESSAGE_DISPLAY_FRAMES) {
            // Message flyout animation
            int flyoutFrame = phaseTimer - MESSAGE_DISPLAY_FRAMES;

            // Move message off screen (simplified - original uses angle-based movement)
            messageY -= 4;  // Move up
            messageX += 2;  // Move right slightly

            if (flyoutFrame >= MESSAGE_FLYOUT_FRAMES) {
                messageVisible = false;
                currentPhase = Phase.GAMEPLAY;
                LOGGER.info("Intro: Complete, gameplay started at frame " + frameCounter);
            }
        }
    }

    /**
     * Gets the current intro phase.
     */
    public Phase getCurrentPhase() {
        return currentPhase;
    }

    /**
     * Checks if the intro sequence is complete.
     */
    public boolean isComplete() {
        return currentPhase == Phase.GAMEPLAY;
    }

    /**
     * Checks if player input should be processed.
     * Input is ignored during DROP, WAIT1, and WAIT2 phases.
     */
    public boolean isInputEnabled() {
        return currentPhase == Phase.GAMEPLAY || currentPhase == Phase.MESSAGE_FLYOUT;
    }

    /**
     * Gets the total frame count since intro started.
     */
    public int getFrameCounter() {
        return frameCounter;
    }

    // ========== Rendering state getters ==========

    /**
     * Checks if the START banner should be rendered.
     */
    public boolean isBannerVisible() {
        return bannerVisible;
    }

    /**
     * Gets the banner X position (center of banner).
     */
    public int getBannerX() {
        return bannerX;
    }

    /**
     * Gets the banner Y position (center of banner).
     */
    public int getBannerY() {
        return bannerY;
    }

    /**
     * Checks if the ring requirement message should be rendered.
     */
    public boolean isMessageVisible() {
        return messageVisible;
    }

    /**
     * Gets the message X position.
     */
    public int getMessageX() {
        return messageX;
    }

    /**
     * Gets the message Y position.
     */
    public int getMessageY() {
        return messageY;
    }

    /**
     * Gets the ring requirement to display.
     */
    public int getRingRequirement() {
        return ringRequirement;
    }

    /**
     * Checks if letters are currently flying off the banner.
     */
    public boolean areLettersFlying() {
        return lettersFlying && currentPhase == Phase.WAIT1;
    }

    /**
     * Gets the letter flyout progress (0 = just started, WAIT1_FRAMES = complete).
     */
    public int getLetterFlyoutProgress() {
        return letterFlyoutProgress;
    }
}
