package com.openggf.game.sonic2.specialstage;

import com.openggf.audio.GameMusic;

import java.util.logging.Logger;
import com.openggf.game.GameServices;

/**
 * Special Stage emerald object (Obj59 in disassembly).
 *
 * The emerald appears at the end of a special stage when the $FD marker is
 * encountered in the object data. It approaches the player using perspective
 * animation, and if the player has collected enough rings, awards the chaos
 * emerald with a special music jingle.
 *
 * From Obj59 in s2.asm:
 * - Initial depth: $36 (54)
 * - Initial angle: $40 (64 = bottom center)
 * - Animation frames 0-9 for perspective sizes
 * - At anim 3+: Fade out music
 * - At anim 6+: Check ring requirement
 * - At anim 9: Award emerald if rings met, play jingle
 */
public class Sonic2SpecialStageEmerald extends Sonic2SpecialStageObject {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStageEmerald.class.getName());

    /**
     * Emerald-specific states.
     */
    public enum EmeraldPhase {
        /** Waiting for initialization delay (60 frames) */
        INITIALIZING,
        /** Approaching player with perspective animation */
        APPROACHING,
        /** Not enough rings, stage will fail */
        FAILED,
        /** Emerald collected, bobbing animation */
        COLLECTED
    }

    /** Initial depth value from disassembly ($36 = 54) */
    private static final int INITIAL_DEPTH = 54;

    /** Initial angle (bottom center of track) */
    private static final int INITIAL_ANGLE = 0x40;

    /** Initialization delay in frames (-$3C = 60) */
    private static final int INIT_DELAY_FRAMES = 60;

    /** Timer for collection countdown after award */
    private static final int COLLECT_COUNTDOWN = 99;  // $63 from disassembly

    /** Timer for fail state before ending stage */
    private static final int FAIL_COUNTDOWN = 79;  // $4F from disassembly

    private EmeraldPhase phase = EmeraldPhase.INITIALIZING;
    private int phaseTimer = 0;

    /** Y offset for bobbing animation during COLLECTED phase */
    private int bobbingOffset = 0;
    private int bobbingCounter = 0;

    /** Ring requirement for this stage */
    private int ringRequirement = 0;

    /** Reference to manager for ring count and state updates */
    private Sonic2SpecialStageManager manager;

    /** Whether music has been faded */
    private boolean musicFaded = false;

    /** Whether emerald has been awarded */
    private boolean emeraldAwarded = false;

    /**
     * Initializes the emerald with default depth and angle.
     */
    @Override
    public void initialize(int depth, int angle) {
        // Use fixed initial values from disassembly
        super.initialize(INITIAL_DEPTH, INITIAL_ANGLE);
        this.phase = EmeraldPhase.INITIALIZING;
        this.phaseTimer = 0;
        this.musicFaded = false;
        this.emeraldAwarded = false;
        this.bobbingOffset = 0;
        this.bobbingCounter = 0;
    }

    /**
     * Sets the ring requirement for emerald collection check.
     */
    public void setRingRequirement(int requirement) {
        this.ringRequirement = requirement;
    }

    /**
     * Sets the manager reference for ring count access.
     */
    public void setManager(Sonic2SpecialStageManager manager) {
        this.manager = manager;
    }

    /**
     * Override screen position calculation to use emerald-specific 0.75 radius scaling.
     *
     * The emerald uses loc_3603C in the original game which scales x_radius and y_radius
     * by 3/4 (0.75) before calculating screen position. This differs from regular objects
     * which use the full radius values.
     */
    @Override
    public void updateScreenPosition(Sonic2PerspectiveData perspectiveData, int currentTrackFrame, boolean trackFlipped) {
        int depth = getDepth();
        if (depth <= 0) {
            onScreen = false;
            return;
        }

        // Get perspective entry for current frame and depth
        Sonic2PerspectiveData.PerspectiveEntry entry =
            perspectiveData.getEntry(currentTrackFrame, depth);

        if (entry == null) {
            onScreen = false;
            return;
        }

        // Check visibility angle range
        if (!entry.isAngleVisible(angle, trackFlipped)) {
            onScreen = false;
            return;
        }

        // Calculate screen position with 0.75 radius scaling (from loc_36088)
        // This matches the original: d4 = d4 * 3 / 4, d5 = d5 * 3 / 4
        int[] pos = calculateEmeraldScreenPosition(entry, angle, trackFlipped);
        screenX = pos[0];
        screenY = pos[1];
        onScreen = true;

        // Calculate track floor Y position (bottom of perspective ellipse, UNSCALED)
        // This is where the emerald's shadow should be rendered.
        // The emerald floats above the track (due to 0.75 radius scaling),
        // but the shadow goes on the actual track surface.
        trackFloorY = entry.yBase + entry.yRadius;

        // Determine animation index based on depth
        animIndex = calculateAnimIndex();
    }

    /**
     * Calculates emerald screen position with 0.75 radius scaling.
     * This matches the original loc_36088 code which scales x_radius and y_radius
     * to 75% of their values before the sine/cosine multiplication.
     */
    private int[] calculateEmeraldScreenPosition(Sonic2PerspectiveData.PerspectiveEntry entry,
                                                  int angle, boolean trackFlipped) {
        // Cosine/Sine tables matching the Mega Drive CalcSine
        double radians = (angle / 256.0) * 2 * Math.PI;
        int cos = (int) Math.round(Math.cos(radians) * 256);
        int sin = (int) Math.round(Math.sin(radians) * 256);

        // Scale radius by 0.75 (multiply by 3, divide by 4)
        int scaledXRadius = (entry.xRadius * 3) / 4;
        int scaledYRadius = (entry.yRadius * 3) / 4;

        // Calculate position offsets using scaled radius
        int xOffset = (cos * scaledXRadius) >> 8;
        int yOffset = (sin * scaledYRadius) >> 8;

        // Calculate base X (flipped if track is flipped)
        int effectiveXBase = entry.xBase;
        if (trackFlipped) {
            effectiveXBase = 0x100 - entry.xBase;
        }

        // Final screen position (no VDP offset - emerald uses raw coordinates)
        int x = effectiveXBase + xOffset;
        int y = entry.yBase + yOffset;

        return new int[] { x, y };
    }

    @Override
    public void update(int currentTrackFrame, boolean trackFlipped, int speedFactor, boolean drawingIndex4) {
        if (state == State.REMOVED) {
            return;
        }

        // Note: phaseTimer is managed by each phase handler individually
        // INITIALIZING increments it to count up to delay
        // COLLECTED/FAILED decrement it to count down to completion

        switch (phase) {
            case INITIALIZING:
                if (updateInitializing()) {
                    // Obj59_Init does not return on the -$3C tick: after
                    // selecting routine 2 it falls through to loc_36022 and
                    // performs the first approach step in the same RunObjects
                    // pass (s2.asm:72279-72306).
                    updateApproaching(speedFactor, drawingIndex4);
                }
                break;
            case APPROACHING:
                updateApproaching(speedFactor, drawingIndex4);
                break;
            case FAILED:
                updateFailed();
                break;
            case COLLECTED:
                updateCollected();
                break;
        }
    }

    private boolean updateInitializing() {
        // Count up until initialization delay is reached
        phaseTimer++;
        if (phaseTimer >= INIT_DELAY_FRAMES) {
            phase = EmeraldPhase.APPROACHING;
            phaseTimer = 0;
            LOGGER.fine("Emerald initialization complete, starting approach");
            return true;
        }
        return false;
    }

    private void updateApproaching(int speedFactor, boolean drawingIndex4) {
        // loc_36022 checks the animation published by the preceding projection
        // before loc_3512A advances depth for this pass (s2.asm:72306-72312).
        int anim = animIndex;

        // At anim 3+: Fade out music
        if (anim >= 3 && !musicFaded) {
            GameServices.audio().fadeOutMusic();
            musicFaded = true;
            LOGGER.fine("Emerald at anim " + anim + ", fading music");
        }

        // At anim 6+: Check ring requirement
        if (anim >= 6) {
            int ringsCollected = (manager != null) ? manager.getRingsCollected() : 0;

            if (ringsCollected < ringRequirement) {
                // loc_36142 writes SS_New_Speed_Factor=0 for the following
                // Vint, selects routine 6/$4F, then returns through loc_36022's
                // movement tail in this same pass (s2.asm:72306-72312,
                // 72417-72423).
                if (manager != null) {
                    manager.requestSpeedFactor(0);
                }
                phase = EmeraldPhase.FAILED;
                phaseTimer = FAIL_COUNTDOWN;
                LOGGER.info("Emerald check failed: " + ringsCollected + " < " + ringRequirement);
            } else if (anim >= 9) {
                awardEmerald();
            }
        }

        // Even when loc_360F0 selects routine 8, loc_36022 continues through
        // the movement and projection tail in the same object pass.
        decrementDepth(drawingIndex4, speedFactor);

        // Check if emerald has passed the player
        if (getDepth() <= 0 && phase == EmeraldPhase.APPROACHING) {
            // Should not happen - emerald stops at closest position
            markForRemoval();
        }
    }

    private void awardEmerald() {
        if (!emeraldAwarded) {
            GameServices.audio().playMusic(GameMusic.EMERALD);
            emeraldAwarded = true;
            LOGGER.info("Emerald awarded! Playing jingle.");
        }
        phase = EmeraldPhase.COLLECTED;
        phaseTimer = COLLECT_COUNTDOWN;
    }

    private void updateFailed() {
        // Count down then signal stage end
        phaseTimer--;
        if (phaseTimer < 0) {
            // Signal to manager that stage should end (failed)
            if (manager != null) {
                manager.markFailed();
            }
            markForRemoval();
            LOGGER.info("Emerald fail countdown complete, ending stage");
        }
    }

    private void updateCollected() {
        // Bobbing animation (+1/-1 pixel on Y)
        // From byte_361C8: $FF, $00, $01, $00 (cycle every 4 frames)
        bobbingCounter++;
        int bobIndex = (bobbingCounter >> 2) & 0x3;
        int[] bobOffsets = { -1, 0, 1, 0 };
        bobbingOffset = bobOffsets[bobIndex];

        // Count down then signal stage end (success)
        phaseTimer--;
        if (phaseTimer < 0) {
            // Signal to manager that stage is complete with emerald
            if (manager != null) {
                manager.markCompleted(true);
            }
            markForRemoval();
            LOGGER.info("Emerald collection complete, ending stage");
        }
    }

    /**
     * Gets the Y offset for bobbing animation.
     */
    public int getBobbingOffset() {
        return bobbingOffset;
    }

    /**
     * Gets the current emerald phase.
     */
    public EmeraldPhase getPhase() {
        return phase;
    }

    /**
     * Checks if the emerald has been awarded.
     */
    public boolean isEmeraldAwarded() {
        return emeraldAwarded;
    }

    /**
     * Obj59 sets {@code SS_Pause_Only_flag} at the start of its first routine-0
     * execution, before advancing its 60-pass initialization delay. The marker
     * allocation itself happens earlier and must not lock the controls yet
     * (s2.asm:6679-6688, 72279-72291).
     */
    boolean restrictsControlsToStart() {
        return phase != EmeraldPhase.INITIALIZING || phaseTimer > 0;
    }

    /**
     * Gets the mapping frame index for the current animation state.
     * Emerald uses frames 0-9 based on perspective size.
     */
    public int getMappingFrame() {
        return Math.min(animIndex, 9);
    }

    @Override
    public boolean isRing() {
        return false;
    }

    @Override
    public boolean isBomb() {
        return false;
    }

    /**
     * Returns true - this is an emerald.
     */
    public boolean isEmerald() {
        return true;
    }

    /**
     * Emeralds are not collidable - they don't use the standard collision system.
     * Collection is handled automatically based on animation index.
     */
    @Override
    public boolean isCollidable() {
        return false;
    }

    @Override
    Sonic2SpecialStageSnapshot.ObjectSnapshot captureRewindSnapshot() {
        return new Sonic2SpecialStageSnapshot.ObjectSnapshot(
                Sonic2SpecialStageSnapshot.SpecialStageObjectType.EMERALD,
                captureBaseRewindSnapshot(),
                null,
                phase,
                phaseTimer,
                bobbingOffset,
                bobbingCounter,
                ringRequirement,
                musicFaded,
                emeraldAwarded);
    }

    void restoreRewindSnapshot(Sonic2SpecialStageSnapshot.ObjectSnapshot snapshot,
                               Sonic2SpecialStageManager manager) {
        restoreBaseRewindSnapshot(snapshot.base());
        phase = snapshot.emeraldPhase();
        phaseTimer = snapshot.emeraldPhaseTimer();
        bobbingOffset = snapshot.emeraldBobbingOffset();
        bobbingCounter = snapshot.emeraldBobbingCounter();
        ringRequirement = snapshot.emeraldRingRequirement();
        musicFaded = snapshot.emeraldMusicFaded();
        emeraldAwarded = snapshot.emeraldAwarded();
        this.manager = manager;
    }
}
