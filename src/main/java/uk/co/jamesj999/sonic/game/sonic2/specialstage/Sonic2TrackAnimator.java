package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import java.io.IOException;
import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Manages track animation state for Sonic 2 Special Stage.
 *
 * The track animation is driven by:
 * 1. A stage layout that defines the sequence of segment types
 * 2. Animation sequences that define the frame indices for each segment type
 * 3. A duration table that controls animation speed based on the current speed factor
 *
 * Each segment type has an associated animation sequence:
 * - SEGMENT_TURN_THEN_RISE (0): 7 turning frames + 17 rise frames
 * - SEGMENT_TURN_THEN_DROP (1): 7 turning frames + 17 drop frames
 * - SEGMENT_TURN_THEN_STRAIGHT (2): 7 turning frames + 5 unturn frames
 * - SEGMENT_STRAIGHT (3): 4 straight frames repeated 4 times
 * - SEGMENT_STRAIGHT_THEN_TURN (4): 4 straight frames + 7 begin-turn frames
 *
 * The animator manages:
 * - Current segment index within the stage layout
 * - Current frame index within the segment animation
 * - Frame delay counter for timing
 * - Track orientation (flipped/unflipped) based on layout flags
 */
public class Sonic2TrackAnimator {
    private static final Logger LOGGER = Logger.getLogger(Sonic2TrackAnimator.class.getName());

    private final Sonic2SpecialStageDataLoader dataLoader;

    private byte[] stageLayout;
    private int layoutLength;

    private int currentSegmentIndex;
    private int currentFrameInSegment;
    private int frameDelayCounter;
    private int currentSegmentType;
    private boolean currentSegmentFlipped;

    private int speedFactor = 12;

    private boolean stageComplete = false;

    /**
     * Persistent orientation state, matching SSTrack_Orientation in the original game.
     * Only updated at specific trigger frames (Straight2, Rise14, Drop6).
     */
    private boolean orientationFlipped = false;
    private int lastOrientationFrame = -1;  // Track which frame last set orientation

    public Sonic2TrackAnimator(Sonic2SpecialStageDataLoader dataLoader) {
        this.dataLoader = dataLoader;
    }

    /**
     * Initializes the animator with the specified stage's layout.
     *
     * @param stageIndex The stage index (0-6)
     */
    public void initialize(int stageIndex) throws IOException {
        byte[] allLayouts = dataLoader.getLevelLayouts();

        loadStageLayout(allLayouts, stageIndex);

        currentSegmentIndex = 0;
        currentFrameInSegment = 0;
        frameDelayCounter = 0;
        stageComplete = false;
        resetOrientation();

        if (layoutLength > 0) {
            parseCurrentSegment();
        } else {
            currentSegmentType = SEGMENT_STRAIGHT;
            currentSegmentFlipped = false;
        }

        LOGGER.info("Track animator initialized for stage " + (stageIndex + 1) +
                    ", layout length: " + layoutLength);
    }

    /**
     * Initializes the animator with a mock layout for testing.
     */
    public void initializeWithMockLayout() {
        stageLayout = new byte[] {
            SEGMENT_STRAIGHT_THEN_TURN,
            SEGMENT_TURN_THEN_RISE,
            SEGMENT_TURN_THEN_STRAIGHT,
            SEGMENT_STRAIGHT,
            SEGMENT_STRAIGHT_THEN_TURN,
            SEGMENT_TURN_THEN_DROP,
            (byte)(SEGMENT_TURN_THEN_STRAIGHT | 0x80),
            SEGMENT_STRAIGHT,
        };
        layoutLength = stageLayout.length;

        currentSegmentIndex = 0;
        currentFrameInSegment = 0;
        frameDelayCounter = 0;
        stageComplete = false;
        resetOrientation();

        parseCurrentSegment();

        LOGGER.info("Track animator initialized with mock layout, " + layoutLength + " segments");
    }

    /**
     * Updates the animation state for one frame.
     * Returns true if the track frame changed.
     */
    public boolean update() {
        if (stageComplete || layoutLength == 0) {
            return false;
        }

        frameDelayCounter++;

        int duration = getFrameDuration();

        if (frameDelayCounter >= duration) {
            frameDelayCounter = 0;
            return advanceFrame();
        }

        return false;
    }

    /**
     * Advances to the next animation frame.
     * Returns true if the frame changed.
     */
    private boolean advanceFrame() {
        int[] animation = SEGMENT_ANIMATIONS[currentSegmentType];

        currentFrameInSegment++;
        if (currentFrameInSegment >= animation.length) {
            currentFrameInSegment = 0;
            advanceSegment();
        }

        return true;
    }

    /**
     * Advances to the next segment in the layout.
     */
    private void advanceSegment() {
        currentSegmentIndex++;

        if (currentSegmentIndex >= layoutLength) {
            currentSegmentIndex = 0;
            stageComplete = true;
            LOGGER.info("Stage layout complete, looping");
        }

        parseCurrentSegment();
        LOGGER.fine("Advanced to segment " + currentSegmentIndex +
                    ", type " + getSegmentTypeName(currentSegmentType) +
                    ", flipped " + currentSegmentFlipped);
    }

    /** Segment type names for debugging */
    private static final String[] SEGMENT_TYPE_NAMES = {
        "TURN_THEN_RISE",
        "TURN_THEN_DROP",
        "TURN_THEN_STRAIGHT",
        "STRAIGHT",
        "STRAIGHT_THEN_TURN"
    };

    private static String getSegmentTypeName(int type) {
        if (type >= 0 && type < SEGMENT_TYPE_NAMES.length) {
            return SEGMENT_TYPE_NAMES[type];
        }
        return "UNKNOWN(" + type + ")";
    }

    /**
     * Parses the current segment byte from the layout.
     */
    private void parseCurrentSegment() {
        if (stageLayout == null || currentSegmentIndex >= layoutLength) {
            currentSegmentType = SEGMENT_STRAIGHT;
            currentSegmentFlipped = false;
            return;
        }

        int segmentByte = stageLayout[currentSegmentIndex] & 0xFF;
        int[] parsed = Sonic2SpecialStageDataLoader.parseSegmentByte(segmentByte);
        currentSegmentType = parsed[0];
        currentSegmentFlipped = parsed[1] != 0;

        // Log segment transitions with flip state (FINE level to reduce noise)
        LOGGER.fine(String.format("Segment %d: %s, flipped=%b (raw byte=0x%02X)",
                currentSegmentIndex, getSegmentTypeName(currentSegmentType), currentSegmentFlipped, segmentByte));

        if (currentSegmentType >= SEGMENT_ANIMATIONS.length) {
            LOGGER.warning("Invalid segment type " + currentSegmentType + ", defaulting to STRAIGHT");
            currentSegmentType = SEGMENT_STRAIGHT;
        }
    }

    /**
     * Gets the current track frame index (0-55).
     */
    public int getCurrentTrackFrameIndex() {
        if (currentSegmentType >= SEGMENT_ANIMATIONS.length) {
            return 0x11;
        }
        int[] animation = SEGMENT_ANIMATIONS[currentSegmentType];
        if (currentFrameInSegment >= animation.length) {
            return animation[0];
        }
        return animation[currentFrameInSegment];
    }

    /**
     * Returns true if the current segment is flipped (mirrored horizontally).
     */
    public boolean isCurrentSegmentFlipped() {
        return currentSegmentFlipped;
    }

    // Trigger frames where orientation is updated (from SSTrackSetOrientation in disassembly)
    private static final int TRIGGER_STRAIGHT2 = 0x12;  // MapSpec_Straight2
    private static final int TRIGGER_RISE14 = 0x0E;     // MapSpec_Rise14
    private static final int TRIGGER_DROP6 = 0x1A;      // MapSpec_Drop6

    /**
     * Gets the effective flip state for rendering, matching the original game's
     * SSTrack_Orientation behavior.
     *
     * The original game maintains a persistent orientation state that only updates
     * at specific trigger frames:
     * - Straight frame 2 (0x12): set orientation = current segment's flip
     * - Rise frame 14 (0x0E): set orientation = current segment's flip
     * - Drop frame 6 (0x1A): set orientation = current segment's flip
     *
     * Between trigger frames, the orientation persists unchanged.
     *
     * @return The effective flip state for the current frame
     */
    public boolean getEffectiveFlipState() {
        int trackFrame = getCurrentTrackFrameIndex();

        // Check if we're at a trigger frame and need to update orientation
        boolean isTriggerFrame = (trackFrame == TRIGGER_STRAIGHT2) ||
                                 (trackFrame == TRIGGER_RISE14) ||
                                 (trackFrame == TRIGGER_DROP6);

        if (isTriggerFrame && trackFrame != lastOrientationFrame) {
            // Update orientation based on current segment's flip bit
            boolean newOrientation = currentSegmentFlipped;
            if (newOrientation != orientationFlipped) {
                LOGGER.fine(String.format("ORIENTATION TRIGGER at frame 0x%02X: %b -> %b (segment %d: %s)",
                        trackFrame, orientationFlipped, newOrientation,
                        currentSegmentIndex, getSegmentTypeName(currentSegmentType)));
            }
            orientationFlipped = newOrientation;
            lastOrientationFrame = trackFrame;
        }

        return orientationFlipped;
    }

    /**
     * Resets the orientation state (called when initializing a new stage).
     */
    private void resetOrientation() {
        orientationFlipped = false;
        lastOrientationFrame = -1;
    }

    /**
     * Gets the flip state of the next segment in the layout.
     * Returns false if there is no next segment.
     */
    private boolean getNextSegmentFlipped() {
        int nextIndex = currentSegmentIndex + 1;
        if (stageLayout == null || nextIndex >= layoutLength) {
            return false;  // Default to unflipped if at end
        }
        int segmentByte = stageLayout[nextIndex] & 0xFF;
        return (segmentByte & 0x80) != 0;
    }

    /**
     * Gets the flip state of the previous segment in the layout.
     * Returns false if there is no previous segment.
     */
    private boolean getPreviousSegmentFlipped() {
        int prevIndex = currentSegmentIndex - 1;
        if (stageLayout == null || prevIndex < 0) {
            return false;  // Default to unflipped if at start
        }
        int segmentByte = stageLayout[prevIndex] & 0xFF;
        return (segmentByte & 0x80) != 0;
    }

    /**
     * Gets the frame duration based on the current speed factor.
     * This is used for track animation timing.
     */
    private int getFrameDuration() {
        int index = (speedFactor >> 1) & 0x7;
        if (index < ANIM_BASE_DURATIONS.length) {
            return Math.max(1, ANIM_BASE_DURATIONS[index]);
        }
        return 15;
    }

    /**
     * Gets the player animation frame timer value.
     * This is SS_player_anim_frame_timer from the original game.
     * The player animation uses this value divided by 2.
     * @return The current player animation timer value
     */
    public int getPlayerAnimFrameTimer() {
        // In the original game, this decrements each frame from the base duration.
        // For simplicity, return the base duration - 1 (matching line 69975: subq.b #1)
        return getFrameDuration() - 1;
    }

    /**
     * Sets the speed factor (affects animation timing).
     * Higher values = faster animation.
     */
    public void setSpeedFactor(int speedFactor) {
        this.speedFactor = Math.max(0, Math.min(14, speedFactor));
    }

    public int getSpeedFactor() {
        return speedFactor;
    }

    public int getCurrentSegmentIndex() {
        return currentSegmentIndex;
    }

    public int getCurrentSegmentType() {
        return currentSegmentType;
    }

    public int getCurrentFrameInSegment() {
        return currentFrameInSegment;
    }

    public boolean isStageComplete() {
        return stageComplete;
    }

    /**
     * Resets the stage complete flag to allow the layout to loop.
     */
    public void resetStageComplete() {
        stageComplete = false;
    }

    /**
     * Loads the stage layout for the specified stage index.
     * The layout data format is: offset table (7 words) + layout bytes for each stage.
     */
    private void loadStageLayout(byte[] allLayouts, int stageIndex) {
        if (allLayouts == null || allLayouts.length < 14) {
            LOGGER.warning("Invalid layout data");
            stageLayout = new byte[0];
            layoutLength = 0;
            return;
        }

        if (stageIndex < 0 || stageIndex >= SPECIAL_STAGE_COUNT) {
            LOGGER.warning("Invalid stage index: " + stageIndex);
            stageLayout = new byte[0];
            layoutLength = 0;
            return;
        }

        int offset = ((allLayouts[stageIndex * 2] & 0xFF) << 8) | (allLayouts[stageIndex * 2 + 1] & 0xFF);

        int nextOffset;
        if (stageIndex < SPECIAL_STAGE_COUNT - 1) {
            nextOffset = ((allLayouts[(stageIndex + 1) * 2] & 0xFF) << 8) | (allLayouts[(stageIndex + 1) * 2 + 1] & 0xFF);
        } else {
            nextOffset = allLayouts.length;
        }

        layoutLength = nextOffset - offset;
        if (layoutLength <= 0 || offset >= allLayouts.length) {
            LOGGER.warning("Invalid layout offset for stage " + stageIndex);
            stageLayout = new byte[0];
            layoutLength = 0;
            return;
        }

        layoutLength = Math.min(layoutLength, allLayouts.length - offset);
        stageLayout = new byte[layoutLength];
        System.arraycopy(allLayouts, offset, stageLayout, 0, layoutLength);

        // Log first 10 segments at FINE level
        if (LOGGER.isLoggable(java.util.logging.Level.FINE)) {
            StringBuilder sb = new StringBuilder("Stage " + (stageIndex + 1) + " layout (first 10 segments): ");
            for (int i = 0; i < Math.min(10, layoutLength); i++) {
                int segByte = stageLayout[i] & 0xFF;
                int type = segByte & 0x7F;
                boolean flip = (segByte & 0x80) != 0;
                sb.append(String.format("[%d:%s%s] ", i, getSegmentTypeName(type), flip ? "(F)" : ""));
            }
            LOGGER.fine(sb.toString());
        }
    }

    /**
     * Returns the total number of straight animation steps at the start of the current stage.
     * Useful for calculating startup timing.
     */
    public int countInitialStraightSteps() {
        int steps = 0;
        for (int i = 0; i < layoutLength; i++) {
            int segByte = stageLayout[i] & 0xFF;
            int type = segByte & 0x7F;
            if (type == SEGMENT_STRAIGHT) {
                steps += 16; // STRAIGHT = 16 animation steps
            } else if (type == SEGMENT_STRAIGHT_THEN_TURN) {
                steps += 4; // Only the first 4 frames are straight
                break; // Turn begins after this
            } else {
                break; // Any other segment type = no more straight
            }
        }
        return steps;
    }
}
