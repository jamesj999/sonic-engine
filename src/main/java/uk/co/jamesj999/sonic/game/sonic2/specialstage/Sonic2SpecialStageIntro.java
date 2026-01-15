package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import java.util.ArrayList;
import java.util.List;
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

    /**
     * Represents a single letter/character in the message.
     * Each letter has its own position and can fly off independently during flyout.
     */
    public static class MessageLetter {
        public int x;
        public int y;
        public int tileOffset;
        public double flyoutAngle;
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

    // Tile offsets for letters in SpecialMessages art (same as Sonic2SpecialStageRenderer)
    private static final int TILE_G = 0x04;
    private static final int TILE_E = 0x02;
    private static final int TILE_T = 0x14;
    private static final int TILE_R = 0x10;
    private static final int TILE_I = 0x08;
    private static final int TILE_N = 0x0C;
    private static final int TILE_S = 0x12;

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

    // Message letters for angle-based flyout (matching original Obj5A behavior)
    private final List<MessageLetter> messageLetters = new ArrayList<>();
    private boolean messageFlyoutInitialized = false;

    // Banner letters for START banner flyout (matching original Obj5F behavior)
    // From obj5F_a.asm: frames 2-8 are the individual pieces that fly off
    private final List<BannerLetter> bannerLetters = new ArrayList<>();
    private boolean bannerFlyoutInitialized = false;

    /**
     * Represents a piece of the START banner that flies off during WAIT1.
     * Based on Obj5F frames 2-8 from obj5F_a.asm.
     */
    public static class BannerLetter {
        public int x;           // Current X position (relative to banner center)
        public int y;           // Current Y position (relative to banner center)
        public int frame;       // Mapping frame (2-8)
        public double flyoutAngle;
        public int flyoutSpeed;
        public boolean visible;

        public BannerLetter(int x, int y, int frame) {
            this.x = x;
            this.y = y;
            this.frame = frame;
            this.flyoutAngle = 0;
            this.flyoutSpeed = 8;
            this.visible = true;
        }
    }

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

        messageLetters.clear();
        messageFlyoutInitialized = false;

        bannerLetters.clear();
        bannerFlyoutInitialized = false;

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

    // From s2.asm: objoff_2A = $F (15 frames) delay before letters fly off
    private static final int BANNER_LAND_DELAY_FRAMES = 15;

    private void updateWait1Phase() {
        phaseTimer++;

        // From s2.asm loc_71B4: wait 15 frames after landing before spawning flying letters
        // The banner stays intact during this delay
        if (phaseTimer < BANNER_LAND_DELAY_FRAMES) {
            // Still in delay period - banner is visible but not flying yet
            return;
        }

        // Initialize banner flyout after the delay
        if (!bannerFlyoutInitialized) {
            createBannerLetters();
            initializeBannerFlyout();
            bannerFlyoutInitialized = true;
            lettersFlying = true;
            letterFlyoutProgress = 0;
        }

        // Update banner letter flyout - each piece flies off independently
        letterFlyoutProgress++;
        boolean allGone = true;
        for (BannerLetter letter : bannerLetters) {
            if (!letter.visible) continue;

            // Move letter based on angle (same as Obj5A flyout)
            letter.x += (int) (Math.cos(letter.flyoutAngle) * letter.flyoutSpeed);
            letter.y += (int) (Math.sin(letter.flyoutAngle) * letter.flyoutSpeed);

            // Check if off screen using actual screen position
            // Banner center is at INTRO_BANNER_X (128), letter.x/y are offsets from center
            int screenX = INTRO_BANNER_X + letter.x;
            int screenY = INTRO_BANNER_END_Y + letter.y;
            // Use generous bounds since pieces are up to 32 pixels wide (4 tiles)
            if (screenX < -32 || screenX > 288 || screenY < -32 || screenY > 256) {
                letter.visible = false;
            } else {
                allGone = false;
            }
        }

        // Transition to WAIT2 when all banner pieces are gone
        // Original waits $1E (30) more frames after spawning children, but we transition
        // when animation is visually complete
        if (allGone || phaseTimer >= BANNER_LAND_DELAY_FRAMES + 45) {
            currentPhase = Phase.WAIT2;
            phaseTimer = 0;
            bannerVisible = false;  // Banner disappears after letters fly off
            messageVisible = true;  // Ring requirement message appears
            LOGGER.fine("Intro: WAIT1 complete, entering WAIT2, showing ring requirement");
        }
    }

    /**
     * Creates the banner letter pieces for flyout animation.
     * From obj5F_a.asm frames 2-8:
     * - Frame 2: Left checkered piece at x=-$48
     * - Frame 3: "S" at x=-$28
     * - Frame 4: "T" at x=-$18
     * - Frame 5: "A" at x=-8
     * - Frame 6: "R" at x=8
     * - Frame 7: "T" at x=$18
     * - Frame 8: Right checkered piece at x=$28
     */
    private void createBannerLetters() {
        bannerLetters.clear();

        // Pieces are created at banner position, which is at y=$48 (72) when stopped
        // X positions are relative to banner center at x=$80 (128)
        // Frame 2-8 with their X offsets from obj5F_a.asm
        bannerLetters.add(new BannerLetter(-0x48, 0, 2));  // Left checkered
        bannerLetters.add(new BannerLetter(-0x28, 0, 3));  // S
        bannerLetters.add(new BannerLetter(-0x18, 0, 4));  // T
        bannerLetters.add(new BannerLetter(-0x08, 0, 5));  // A
        bannerLetters.add(new BannerLetter( 0x08, 0, 6));  // R
        bannerLetters.add(new BannerLetter( 0x18, 0, 7));  // T
        bannerLetters.add(new BannerLetter( 0x28, 0, 8));  // Right checkered
    }

    /**
     * Initialize flyout angles for banner letters.
     * From s2.asm loc_71B4: CalcAngle is called with d2=-$28 (-40), d1=x_position
     * This makes pieces fly upward (-40 Y offset) and outward based on X position.
     */
    private void initializeBannerFlyout() {
        for (BannerLetter letter : bannerLetters) {
            // From disassembly: d2 = -$28 (-40), d1 = piece x position
            // CalcAngle(d1=dx, d2=dy) gives the angle
            int dx = letter.x;
            int dy = -0x28;  // -40 - always fly upward
            letter.flyoutAngle = Math.atan2(dy, dx);
            letter.flyoutSpeed = 8;
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
            // Initialize flyout on first frame
            if (!messageFlyoutInitialized) {
                createMessageLetters();
                initializeFlyout();
                messageFlyoutInitialized = true;
            }

            // Move each letter based on its angle (matching Obj5A_TextFlyout behavior)
            boolean allGone = true;
            for (MessageLetter letter : messageLetters) {
                if (!letter.visible) continue;

                // Move letter based on angle
                letter.x += (int) (Math.cos(letter.flyoutAngle) * letter.flyoutSpeed);
                letter.y += (int) (Math.sin(letter.flyoutAngle) * letter.flyoutSpeed);

                // Check if off screen
                if (letter.x < 0 || letter.x > 256 || letter.y < 0 || letter.y > 224) {
                    letter.visible = false;
                } else {
                    allGone = false;
                }
            }

            if (allGone) {
                messageVisible = false;
                currentPhase = Phase.GAMEPLAY;
                LOGGER.info("Intro: Complete, gameplay started at frame " + frameCounter);
            }
        }
    }

    /**
     * Creates the message letters for "GET XX RINGS" with their positions.
     * Positions match the corrected digit positioning from s2.asm.
     */
    private void createMessageLetters() {
        messageLetters.clear();
        final int baseY = 0x6C;

        // "GET" at x=$54
        int x = 0x54;
        messageLetters.add(new MessageLetter(x, baseY, TILE_G));
        messageLetters.add(new MessageLetter(x + 8, baseY, TILE_E));
        messageLetters.add(new MessageLetter(x + 16, baseY, TILE_T));

        // Digits at corrected positions
        // From s2.asm: digits always start at x=$70 for tens/hundreds position
        int digitX;
        if (ringRequirement >= 100) {
            digitX = 0x70;  // 3 digits: hundreds at 0x70
        } else if (ringRequirement >= 10) {
            digitX = 0x70;  // 2 digits: tens at 0x70
        } else {
            digitX = 0x78;  // 1 digit: at 0x78
        }

        // For digits, we use a special marker (negative tileOffset) that the renderer interprets
        // Hundreds digit
        if (ringRequirement >= 100) {
            int hundreds = (ringRequirement / 100) % 10;
            messageLetters.add(new MessageLetter(digitX, baseY, -1 - hundreds));  // -1 to -10 for digits
            digitX += 8;
        }

        // Tens digit
        if (ringRequirement >= 10) {
            int tens = (ringRequirement / 10) % 10;
            messageLetters.add(new MessageLetter(digitX, baseY, -1 - tens));
            digitX += 8;
        }

        // Ones digit
        int ones = ringRequirement % 10;
        messageLetters.add(new MessageLetter(digitX, baseY, -1 - ones));

        // "RINGS" position: x=$84 for 2-digit, x=$8C for 3-digit
        int ringsX = (ringRequirement >= 100) ? 0x8C : 0x84;
        messageLetters.add(new MessageLetter(ringsX, baseY, TILE_R));
        messageLetters.add(new MessageLetter(ringsX + 8, baseY, TILE_I));
        messageLetters.add(new MessageLetter(ringsX + 16, baseY, TILE_N));
        messageLetters.add(new MessageLetter(ringsX + 24, baseY, TILE_G));
        messageLetters.add(new MessageLetter(ringsX + 32, baseY, TILE_S));
    }

    /**
     * Initialize flyout angles for all message letters.
     * From s2.asm Obj5A_TextFlyoutInit: angle is from center (0x80, 0x70) to letter,
     * so letters fly AWAY from center.
     */
    private void initializeFlyout() {
        for (MessageLetter letter : messageLetters) {
            // Calculate vector from center to letter position
            int dx = letter.x - 0x80;  // letter pos - center
            int dy = letter.y - 0x70;  // letter pos - center
            // Use atan2 to get angle (letters fly in direction away from center)
            letter.flyoutAngle = Math.atan2(dy, dx);
            letter.flyoutSpeed = 8;
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

    /**
     * Checks if the message is currently in the flyout animation phase.
     * When true, the renderer should use per-letter positions instead of static rendering.
     */
    public boolean isInFlyoutPhase() {
        return currentPhase == Phase.MESSAGE_FLYOUT &&
               phaseTimer >= MESSAGE_DISPLAY_FRAMES &&
               messageFlyoutInitialized;
    }

    /**
     * Gets the message letters for rendering during flyout.
     * Only valid when isInFlyoutPhase() returns true.
     */
    public List<MessageLetter> getMessageLetters() {
        return messageLetters;
    }

    /**
     * Checks if the banner is currently in the flyout animation phase (WAIT1 after delay).
     * When true, the renderer should render individual banner pieces instead of the full banner.
     * Returns false during the 15-frame delay after landing.
     */
    public boolean isBannerInFlyoutPhase() {
        return currentPhase == Phase.WAIT1 &&
               phaseTimer >= BANNER_LAND_DELAY_FRAMES &&
               bannerFlyoutInitialized;
    }

    /**
     * Gets the banner letters for rendering during flyout.
     * Only valid when isBannerInFlyoutPhase() returns true.
     */
    public List<BannerLetter> getBannerLetters() {
        return bannerLetters;
    }

    /**
     * Shows just the "GET XX RINGS" message without the full intro sequence.
     * Used after passing a checkpoint to display the next ring requirement.
     *
     * @param ringRequirement Number of rings required for the next checkpoint
     */
    public void showRingRequirementMessage(int ringRequirement) {
        this.ringRequirement = ringRequirement;

        // Skip directly to WAIT2 (message display phase)
        currentPhase = Phase.WAIT2;
        phaseTimer = 0;

        bannerVisible = false;  // No banner
        messageVisible = true;  // Show the message
        messageX = 128;
        messageY = 108;

        lettersFlying = false;
        letterFlyoutProgress = 0;

        // Reset flyout state for new message
        messageLetters.clear();
        messageFlyoutInitialized = false;

        LOGGER.info("Showing ring requirement message: GET " + ringRequirement + " RINGS");
    }
}
