package com.openggf.sprites.playable;

import com.openggf.audio.AudioManager;
import com.openggf.audio.GameAudioProfile;
import com.openggf.audio.GameSound;
import com.openggf.game.rules.DrowningBubbleRules;
import com.openggf.sprites.playable.PlayableSpriteRuntimeServices;
import com.openggf.game.rules.GameRules;
import com.openggf.level.objects.BreathingBubbleInstance;
import com.openggf.level.LevelManager;
import com.openggf.game.AbstractLevelEventManager;
import com.openggf.level.objects.ObjectArtKeys;
import com.openggf.level.objects.ObjectRenderManager;
import com.openggf.level.render.PatternSpriteRenderer;
import com.openggf.physics.Direction;

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
public class DrowningController {
    private static final Logger LOGGER = Logger.getLogger(DrowningController.class.getName());

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

    /**
     * Sonic 2 countdown frame mapping: countdownNumber (0-5) -> art frame index.
     * S2 Ani_obj0A selects mapping frames 8-13 for numbers 0-5.
     */
    private static final int[] S2_COUNTDOWN_FRAMES = {8, 9, 10, 11, 12, 13};

    /**
     * Sonic 1 countdown frame mapping: countdownNumber (0-5) -> art frame index.
     * S1 bubble art: frames 13-18 are full countdown numbers (13="0", 14="5", 15="4", 16="3", 17="2", 18="1").
     */
    private static final int[] S1_COUNTDOWN_FRAMES = {13, 18, 17, 16, 15, 14};

    /** Max bubble growth frame for S2 art (frames 0-3) */
    private static final int S2_MAX_BUBBLE_FRAME = 3;

    /** Max bubble growth frame for S1 art (frames 0-5) */
    private static final int S1_MAX_BUBBLE_FRAME = 5;

    private final AbstractPlayableSprite player;
    private final AudioManager audioManager;

    /** Remaining air in seconds */
    private int remainingAir;

    /** Frame timer (counts down from 60 each second) */
    private int frameTimer;

    /** Whether the drowning music has started */
    private boolean drowningMusicStarted;

    /** ROM Obj0A objoff_36 / obj0a_flags: active bubble burst and number flags. */
    private int bubbleFlags;

    /** ROM Obj0A objoff_34 / obj0a_total_bubbles_to_spawn. */
    private int bubblesRemainingInBurst;

    /** ROM Obj0A objoff_3A / obj0a_next_bubble_timer. */
    private int nextBubbleTimer;

    /** ROM Obj0A objoff_32: seconds-between-number-bubbles timer. */
    private int numberBubbleTimer;

    /** ROM Obj0A objoff_33: seconds-between-number-bubbles frequency. */
    private int numberBubbleFrequency;

    /** Resolved bubble art key (lazily determined from available renderers) */
    private String bubbleArtKey;

    /** Resolved countdown frame mapping (lazily determined) */
    private int[] bubbleCountdownFrames;

    /** Resolved max bubble growth frame (lazily determined) */
    private int bubbleMaxFrame;

    /** Whether bubble config has been resolved */
    private boolean bubbleConfigResolved;

    public record FixedCountdownAirEvent(int airBefore, int airAfter, int countdownNumber, boolean drowned) {
    }

    public DrowningController(AbstractPlayableSprite player) {
        this.player = player;
        this.audioManager = player.currentAudioManager();
        reset();
    }

    /**
     * Resets the drowning state. Called when entering water.
     */
    public void reset() {
        remainingAir = INITIAL_AIR;
        DrowningBubbleRules rules = drowningBubbleRulesOrNull();
        frameTimer = rules != null ? rules.initialDrowningCountdownFrameTimer() : FRAMES_PER_SECOND;
        drowningMusicStarted = false;
        bubbleFlags = 0;
        bubblesRemainingInBurst = -1;
        nextBubbleTimer = 0;
        numberBubbleTimer = 0;
        numberBubbleFrequency = 1;
        // Reset bubble config so it re-resolves for the current zone's art
        bubbleConfigResolved = false;
    }

    /**
     * Updates the drowning manager. Should be called once per frame while underwater.
     *
     * @return true if the player should drown (air depleted)
     */
    public boolean update() {
        // ROM order checks the one-second air timer before the pending
        // mouth-bubble timer, so same-frame expirations use the fresh air-event
        // burst state. See S1 Drown_Countdown, S2 Obj0A_Countdown, and S3K
        // AirCountdown_Countdown.
        frameTimer--;
        if (frameTimer <= 0) {
            return performAirEvent();
        }

        // ROM Obj0A_MakeBubbleMaybe: while obj0a_flags is nonzero, the
        // mouth-bubble object decrements its per-bubble timer every frame and
        // allocates the next bubble when it underflows.
        if (bubbleFlags != 0) {
            nextBubbleTimer--;
            if (nextBubbleTimer < 0) {
                spawnRomMouthBubble();
            }
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
            audioManager.playSfx(GameSound.AIR_DING);
        }

        // Check for drowning music
        if (remainingAir == DROWNING_MUSIC_LEVEL && !drowningMusicStarted) {
            GameAudioProfile audioProfile = audioManager.getAudioProfile();
            if (audioProfile != null) {
                audioManager.playMusic(audioProfile.getDrowningMusicId());
                drowningMusicStarted = true;
            }
        }

        // ROM randomly chooses whether this burst contains one or two bubbles.
        bubbleFlags = 1;
        bubblesRemainingInBurst = player.currentRng().nextBits(1);

        // 2. Decrease air
        int airBefore = remainingAir;
        if (airBefore <= DROWNING_MUSIC_LEVEL) {
            numberBubbleTimer--;
            if (numberBubbleTimer < 0) {
                numberBubbleTimer = numberBubbleFrequency;
                bubbleFlags |= 0x80;
            }
        }
        remainingAir--;

        // 3. Spawn the first small breathing bubble immediately.
        spawnRomMouthBubble();

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

    private void spawnRomMouthBubble() {
        com.openggf.game.GameRng rng = player.currentRng();
        // S2/S3K Obj0A_Animate biases the next mouth-bubble delay by +8
        // (RandomNumber&$F)+8 (s2.asm:42201-42204); S1 LZ Obj64 air bubbles use a
        // different bubble-maker structure with no such bias. Drive this from the
        // per-game drowning bubble rules rather than the loaded bubble art key.
        DrowningBubbleRules rules = drowningBubbleRulesOrNull();
        int timerBias = rules != null ? rules.mouthBubbleTimerBias() : 8;
        nextBubbleTimer = rng.nextBits(0x0F) + timerBias;

        int countdownNumber = -1;
        if ((bubbleFlags & 0x80) != 0 && remainingAir < DROWNING_MUSIC_LEVEL) {
            int candidateNumber = remainingAir >> 1;
            if (rng.nextBits(3) == 0) {
                if ((bubbleFlags & 0x40) == 0) {
                    bubbleFlags |= 0x40;
                    countdownNumber = candidateNumber;
                }
            }
            if (bubblesRemainingInBurst == 0 && (bubbleFlags & 0x40) == 0) {
                bubbleFlags |= 0x40;
                countdownNumber = candidateNumber;
            }
        }

        spawnBubble(countdownNumber);

        bubblesRemainingInBurst--;
        if (bubblesRemainingInBurst < 0) {
            bubbleFlags = 0;
        }
    }

    /**
     * Spawns a breathing bubble at the player's mouth position.
     *
     * @param countdownNumber Countdown number (-1 for regular bubble)
     */
    private void spawnBubble(int countdownNumber) {
        spawnBubble(countdownNumber, true);
    }

    private void spawnBubble(int countdownNumber, boolean skipFirstUpdate) {
        LevelManager levelManager = player.currentLevelManagerIfAvailable();
        if (levelManager == null || levelManager.getObjectManager() == null) {
            return;
        }

        // Resolve bubble art config if not yet determined
        if (!bubbleConfigResolved) {
            resolveBubbleConfig(levelManager);
        }

        // No bubble art available for this zone
        if (bubbleArtKey == null) {
            return;
        }

        // Calculate spawn position at player's mouth
        int xOffset = player.getDirection() == Direction.LEFT ? -BUBBLE_X_OFFSET : BUBBLE_X_OFFSET;
        int bubbleX = player.getCentreX() + xOffset;
        int bubbleY = player.getCentreY(); // Player's centre Y (ROM uses centre coordinates for obY)

        DrowningBubbleRules rules = drowningBubbleRulesOrNull();
        int riseVelocity = rules != null ? rules.mouthBubbleRiseVelocity() : -0x88;
        boolean deferFirstPass = rules == null || rules.breathingBubbleDefersFirstObjectPass();
        boolean startsFacingLeft = player.getDirection() == Direction.LEFT;

        // Create bubble with game-specific art configuration
        BreathingBubbleInstance bubble = new BreathingBubbleInstance(
            bubbleX, bubbleY, startsFacingLeft, countdownNumber,
            bubbleArtKey, bubbleCountdownFrames, bubbleMaxFrame, riseVelocity,
            skipFirstUpdate && deferFirstPass
        );

        if (deferFirstPass) {
            levelManager.getObjectManager().addDynamicObjectNextFrame(bubble);
        } else {
            levelManager.getObjectManager().addDynamicObject(bubble);
        }
    }

    /**
     * Fixed object-RAM countdown sidecars own their own timers and RNG cadence,
     * but their visible child bubble is the same Obj0A dynamic object.
     */
    public void spawnFixedCountdownBubble(int countdownNumber) {
        spawnBubble(countdownNumber);
    }

    /**
     * S2's fixed Obj0A sidecars run after dynamic SST slots. Their visible
     * child bubble therefore reaches its first dynamic Obj0A pass on the next
     * RunObjects scan, without the extra skip needed by player-side early
     * allocation paths.
     */
    public void spawnFixedCountdownBubble(int countdownNumber, boolean skipFirstUpdate) {
        spawnBubble(countdownNumber, skipFirstUpdate);
    }

    /**
     * Resolves the bubble art configuration by checking which renderer is available.
     * This allows breathing bubbles to work with both S1 (LZ_BUBBLES) and S2 (BUBBLES) art.
     */
    private void resolveBubbleConfig(LevelManager levelManager) {
        bubbleConfigResolved = true;
        ObjectRenderManager renderManager = levelManager.getObjectRenderManager();
        if (renderManager == null) {
            return;
        }

        // Check for S1 bubble art (LZ_BUBBLES). Obj0A allocation is ROM object
        // state, so it is keyed on loaded art metadata, not on GPU cache readiness.
        PatternSpriteRenderer s1Renderer = renderManager.getRenderer(ObjectArtKeys.LZ_BUBBLES);
        if (s1Renderer != null) {
            bubbleArtKey = ObjectArtKeys.LZ_BUBBLES;
            bubbleCountdownFrames = S1_COUNTDOWN_FRAMES;
            bubbleMaxFrame = S1_MAX_BUBBLE_FRAME;
            return;
        }

        // Check for S2 bubble art (BUBBLES)
        PatternSpriteRenderer s2Renderer = renderManager.getRenderer(ObjectArtKeys.BUBBLES);
        if (s2Renderer != null) {
            bubbleArtKey = ObjectArtKeys.BUBBLES;
            bubbleCountdownFrames = S2_COUNTDOWN_FRAMES;
            bubbleMaxFrame = S2_MAX_BUBBLE_FRAME;
            return;
        }

        // No bubble art available
        LOGGER.fine("No bubble art renderer available for breathing bubbles");
        bubbleArtKey = null;
    }

    private DrowningBubbleRules drowningBubbleRulesOrNull() {
        GameRules gameRules = player.getGameRules();
        if (gameRules != null && gameRules.drowningBubble() != null) {
            return gameRules.drowningBubble();
        }
        return null;
    }

    /**
     * Called when the player exits water. Resets state and restarts zone music if drowning music was playing.
     */
    public void onExitWater() {
        if (drowningMusicStarted) {
            // Restart the level music from the beginning (original Sonic 2 behavior)
            restartZoneMusic();
            drowningMusicStarted = false;
        }
        reset();
    }

    /**
     * Restarts the music the player should hear now that the countdown is over.
     *
     * <p>Which track that is belongs to the game. S3K's
     * {@code Player_ResetAirTimer} (sonic3k.asm:33663-33686) loads the level
     * track and then overrides it for an invincible player, a Super or Hyper
     * player, and a boss fight; S1 and S2 keep the level track. The choice is
     * made by the active audio profile, which already owns the music ids, so
     * this method stays free of any per-game branch.
     */
    private void restartZoneMusic() {
        LevelManager levelManager = player.currentLevelManagerIfAvailable();
        if (levelManager == null) {
            return;
        }
        int musicId = levelManager.getCurrentLevelMusicId();
        if (musicId < 0) {
            return;
        }
        GameAudioProfile profile = audioManager.getAudioProfile();
        if (profile != null) {
            musicId = profile.resolveAirResetMusic(musicId,
                    player.getInvincibleFrames() > 0,
                    player.isSuperSonic(),
                    bossOwnsMusic());
        }
        audioManager.playMusic(musicId);
    }

    /** ROM: {@code tst.b (Boss_flag).w} at sonic3k.asm:33681. */
    private boolean bossOwnsMusic() {
        return PlayableSpriteRuntimeServices.levelEventsOrNull()
                instanceof AbstractLevelEventManager events
                && events.isBossActive();
    }

    /**
     * Called when the player drowns. Resumes zone music if drowning countdown music was playing.
     */
    public void onDrown() {
        if (drowningMusicStarted) {
            restartZoneMusic();
            drowningMusicStarted = false;
        }
    }

    /**
     * Replenishes air (e.g., from collecting an air bubble).
     */
    public void replenishAir() {
        remainingAir = INITIAL_AIR;
        frameTimer = FRAMES_PER_SECOND;

        if (drowningMusicStarted) {
            // Restart the level music from the beginning (original Sonic 2 behavior)
            restartZoneMusic();
            drowningMusicStarted = false;
        }
    }

    public int getRemainingAir() {
        return remainingAir;
    }

    /**
     * Returns true while the drowning countdown owns music playback, so a
     * power-up ending must not replace it.
     *
     * <p>ROM: {@code cmpi.b #12,air_left(a0)} / {@code blo} in
     * {@code Sonic_ChkInvin} — the same air level at which the countdown music
     * starts.
     */
    boolean isCountdownOwningMusic() {
        return remainingAir < DROWNING_MUSIC_LEVEL;
    }

    /**
     * A fixed level-event air-countdown object may own bubble allocation and
     * RNG cadence, but the air-left side effects are the shared ROM path:
     * warning ding at 25/20/15, drowning music at 12, then decrement air_left.
     */
    public FixedCountdownAirEvent performFixedCountdownAirEvent(boolean allowAudio) {
        frameTimer = FRAMES_PER_SECOND;
        int airBefore = remainingAir;
        if (allowAudio && WARNING_CHIME_LEVELS.contains(airBefore)) {
            audioManager.playSfx(GameSound.AIR_DING);
        }
        if (allowAudio && airBefore == DROWNING_MUSIC_LEVEL && !drowningMusicStarted) {
            GameAudioProfile audioProfile = audioManager.getAudioProfile();
            if (audioProfile != null) {
                audioManager.playMusic(audioProfile.getDrowningMusicId());
                drowningMusicStarted = true;
            }
        }
        int countdownNumber = getCountdownNumber(airBefore);
        remainingAir--;
        return new FixedCountdownAirEvent(airBefore, remainingAir, countdownNumber, remainingAir < 0);
    }

    public void setRemainingAirFromFixedCountdown(int remainingAir) {
        this.remainingAir = remainingAir;
    }

    public void resetAirTimerFromFixedCountdownDeath() {
        remainingAir = INITIAL_AIR;
        frameTimer = FRAMES_PER_SECOND;
    }

    public int countdownNumberForFixedCountdown(int airLevel) {
        return getCountdownNumber(airLevel);
    }

    public boolean isDrowningMusicPlaying() {
        return drowningMusicStarted;
    }

    /**
     * Captures the mutable breath-timer/countdown/audio-cue state for rewind
     * restore. Without this, a rewind seek leaves the underwater breath timer,
     * countdown-bubble cadence, and drowning-music cue phase unrestored even
     * though the player's own physics fields roll back correctly.
     */
    public RewindState captureRewindState() {
        return new RewindState(remainingAir, frameTimer, drowningMusicStarted,
                bubbleFlags, bubblesRemainingInBurst, nextBubbleTimer,
                numberBubbleTimer, numberBubbleFrequency);
    }

    public void restoreRewindState(RewindState state) {
        if (state == null) {
            reset();
            return;
        }
        remainingAir = state.remainingAir();
        frameTimer = state.frameTimer();
        drowningMusicStarted = state.drowningMusicStarted();
        bubbleFlags = state.bubbleFlags();
        bubblesRemainingInBurst = state.bubblesRemainingInBurst();
        nextBubbleTimer = state.nextBubbleTimer();
        numberBubbleTimer = state.numberBubbleTimer();
        numberBubbleFrequency = state.numberBubbleFrequency();
    }

    public record RewindState(
            int remainingAir,
            int frameTimer,
            boolean drowningMusicStarted,
            int bubbleFlags,
            int bubblesRemainingInBurst,
            int nextBubbleTimer,
            int numberBubbleTimer,
            int numberBubbleFrequency
    ) {}
}
