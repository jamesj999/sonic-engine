package uk.co.jamesj999.sonic.sprites.playable;

import uk.co.jamesj999.sonic.audio.AudioManager;
import uk.co.jamesj999.sonic.audio.GameSound;
import uk.co.jamesj999.sonic.game.sonic2.constants.Sonic2AudioConstants;
import uk.co.jamesj999.sonic.game.sonic2.objects.BreathingBubbleInstance;
import uk.co.jamesj999.sonic.level.LevelManager;
import uk.co.jamesj999.sonic.physics.Direction;

import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Manages the drowning mechanic for playable sprites while underwater.
 * <p>
 * The player can last 30 seconds underwater before drowning. A timer counts down
 * from 60 frames each second. When the timer reaches 0, an "air event" occurs:
 * <ul>
 *   <li>At air levels [25, 20, 15]: Warning chime is sounded</li>
 *   <li>At air level [12]: Drowning countdown music begins</li>
 *   <li>At air levels [12, 10, 8, 6, 4, 2]: Countdown number bubble spawns (5, 4, 3, 2, 1, 0)</li>
 *   <li>At air level less than 0: Player drowns</li>
 * </ul>
 * <p>
 * Small breathing bubbles are also spawned during each air event.
 */
public class DrowningManager {
    private static final Logger LOGGER = Logger.getLogger(DrowningManager.class.getName());

    /** Initial air value when entering water (seconds) */
    private static final int INITIAL_AIR = 30;

    /** Frames per second (timer counts down from this each second) */
    private static final int FRAMES_PER_SECOND = 60;

    /** Air values that trigger a warning chime */
    private static final Set<Integer> WARNING_CHIME_LEVELS = Set.of(25, 20, 15);

    /** Air value that triggers drowning music */
    private static final int DROWNING_MUSIC_LEVEL = 12;

    /** Air values that trigger countdown number bubbles (maps air -> countdown number) */
    private static final int[] COUNTDOWN_AIR_LEVELS = { 12, 10, 8, 6, 4, 2 };
    private static final int[] COUNTDOWN_NUMBERS = { 5, 4, 3, 2, 1, 0 };

    /** X offset for bubble spawn (from player center) */
    private static final int BUBBLE_X_OFFSET = 6;

    /** Maximum delay before second bubble spawns */
    private static final int SECOND_BUBBLE_MAX_DELAY = 16;

    private final AbstractPlayableSprite player;
    private final Random random = new Random();

    /** Remaining air in seconds */
    private int remainingAir;

    /** Frame timer (counts down from 60 each second) */
    private int frameTimer;

    /** Whether the drowning music has started */
    private boolean drowningMusicStarted;

    /** Delay counter for spawning the second bubble */
    private int secondBubbleDelay;

    /** Whether we need to spawn a second bubble */
    private boolean pendingSecondBubble;

    /** Countdown number for pending second bubble (-1 if regular bubble) */
    private int pendingCountdownNumber;

    public DrowningManager(AbstractPlayableSprite player) {
        this.player = player;
        reset();
    }

    /**
     * Resets the drowning state. Called when entering water.
     */
    public void reset() {
        remainingAir = INITIAL_AIR;
        frameTimer = FRAMES_PER_SECOND;
        drowningMusicStarted = false;
        secondBubbleDelay = 0;
        pendingSecondBubble = false;
        pendingCountdownNumber = -1;
    }

    /**
     * Updates the drowning manager. Should be called once per frame while underwater.
     *
     * @return true if the player should drown (air depleted)
     */
    public boolean update() {
        // Handle pending second bubble spawn
        if (pendingSecondBubble) {
            secondBubbleDelay--;
            if (secondBubbleDelay <= 0) {
                spawnBubble(pendingCountdownNumber);
                pendingSecondBubble = false;
                pendingCountdownNumber = -1;
            }
        }

        // Decrement frame timer
        frameTimer--;

        if (frameTimer <= 0) {
            // Air event occurs
            return performAirEvent();
        }

        return false;
    }

    /**
     * Performs the air event that occurs every second.
     *
     * @return true if the player should drown
     */
    private boolean performAirEvent() {
        // Reset frame timer for next second
        frameTimer = FRAMES_PER_SECOND;

        // 1. Air Check - handle warnings and drowning
        if (remainingAir < 0) {
            // Player drowns
            LOGGER.info("Player drowning - air depleted");
            return true;
        }

        // Check for warning chime
        if (WARNING_CHIME_LEVELS.contains(remainingAir)) {
            AudioManager.getInstance().playSfx(GameSound.AIR_DING);
        }

        // Check for drowning music
        if (remainingAir == DROWNING_MUSIC_LEVEL && !drowningMusicStarted) {
            AudioManager.getInstance().playMusic(Sonic2AudioConstants.MUS_UNDERWATER);
            drowningMusicStarted = true;
        }

        // Check for countdown bubble
        int countdownNumber = getCountdownNumber(remainingAir);

        // 2. Decrease air
        remainingAir--;

        // 3. Spawn small breathing bubbles
        spawnBreathingBubbles(countdownNumber);

        return false;
    }

    /**
     * Gets the countdown number to display for the given air level.
     *
     * @param airLevel Current air level
     * @return Countdown number (5-0), or -1 if no countdown at this level
     */
    private int getCountdownNumber(int airLevel) {
        for (int i = 0; i < COUNTDOWN_AIR_LEVELS.length; i++) {
            if (airLevel == COUNTDOWN_AIR_LEVELS[i]) {
                return COUNTDOWN_NUMBERS[i];
            }
        }
        return -1;
    }

    /**
     * Spawns small breathing bubbles from the player's mouth.
     *
     * @param countdownNumber Countdown number to display (-1 for regular bubble)
     */
    private void spawnBreathingBubbles(int countdownNumber) {
        // Determine number of bubbles (1 or 2, equal chance)
        int bubbleCount = random.nextBoolean() ? 1 : 2;

        if (countdownNumber >= 0) {
            // Countdown bubble logic
            if (bubbleCount == 1) {
                // Only 1 bubble - it must be the countdown bubble
                spawnBubble(countdownNumber);
            } else {
                // 2 bubbles - 25% chance first is countdown, otherwise second is countdown
                boolean firstIsCountdown = random.nextInt(4) == 0;

                if (firstIsCountdown) {
                    spawnBubble(countdownNumber);
                    scheduleSecondBubble(-1); // Second is regular
                } else {
                    spawnBubble(-1); // First is regular
                    scheduleSecondBubble(countdownNumber); // Second is countdown
                }
            }
        } else {
            // No countdown - just spawn regular bubbles
            spawnBubble(-1);
            if (bubbleCount == 2) {
                scheduleSecondBubble(-1);
            }
        }
    }

    /**
     * Spawns a breathing bubble at the player's mouth position.
     *
     * @param countdownNumber Countdown number (-1 for regular bubble)
     */
    private void spawnBubble(int countdownNumber) {
        LevelManager levelManager = LevelManager.getInstance();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }

        // Calculate spawn position at player's mouth
        int xOffset = player.getDirection() == Direction.LEFT ? -BUBBLE_X_OFFSET : BUBBLE_X_OFFSET;
        int bubbleX = player.getCentreX() + xOffset;
        int bubbleY = player.getY(); // Player's Y position (top of sprite, approximately mouth level)

        // Determine if sine wave should start moving away from player
        boolean startMovingLeft = player.getDirection() == Direction.RIGHT;

        // Create bubble
        BreathingBubbleInstance bubble = new BreathingBubbleInstance(
            bubbleX, bubbleY, startMovingLeft, countdownNumber
        );

        levelManager.getObjectManager().addDynamicObject(bubble);
    }

    /**
     * Schedules a second bubble to spawn after a random delay.
     *
     * @param countdownNumber Countdown number for the second bubble (-1 for regular)
     */
    private void scheduleSecondBubble(int countdownNumber) {
        pendingSecondBubble = true;
        pendingCountdownNumber = countdownNumber;
        secondBubbleDelay = 1 + random.nextInt(SECOND_BUBBLE_MAX_DELAY);
    }

    /**
     * Called when the player exits water. Resets state and stops drowning music if playing.
     */
    public void onExitWater() {
        if (drowningMusicStarted) {
            // End the drowning music override and return to level music
            AudioManager.getInstance().endMusicOverride(Sonic2AudioConstants.MUS_UNDERWATER);
            drowningMusicStarted = false;
        }
        reset();
    }

    /**
     * Replenishes air (e.g., from collecting an air bubble).
     */
    public void replenishAir() {
        remainingAir = INITIAL_AIR;
        frameTimer = FRAMES_PER_SECOND;

        if (drowningMusicStarted) {
            AudioManager.getInstance().endMusicOverride(Sonic2AudioConstants.MUS_UNDERWATER);
            drowningMusicStarted = false;
        }
    }

    public int getRemainingAir() {
        return remainingAir;
    }

    public boolean isDrowningMusicPlaying() {
        return drowningMusicStarted;
    }
}
