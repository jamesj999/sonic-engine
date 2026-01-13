package uk.co.jamesj999.sonic.game.sonic2.specialstage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static uk.co.jamesj999.sonic.game.sonic2.specialstage.Sonic2SpecialStageConstants.*;

/**
 * Manages spawning of rings, bombs, and checkpoint markers in Sonic 2 Special Stages.
 *
 * This class parses the object location stream from ROM and spawns objects at segment
 * transitions. The format is documented in the source of truth document.
 *
 * Object stream format (from SSObjectsManager in disassembly):
 * - Each record starts with a byte (d0):
 *   - If d0 >= 0 (non-negative): object entry followed by angle byte
 *     - Bits 0-5: distance index (0-63)
 *     - Bit 6 ($40): object type (0=ring, 1=bomb)
 *   - If d0 < 0 (negative): marker byte
 *     - $FF: end of segment's objects
 *     - $FE: checkpoint marker
 *     - $FD: emerald marker (1P mode)
 *     - $FC and below: no-checkpoint marker
 *
 * Depth calculation:
 *   objoff_30 = (distanceIndex * 4) + (segmentLength * 4)
 * where segmentLength is from Ani_SSTrack_Len (24, 24, 12, 16, 11 for types 0-4)
 */
public class Sonic2SpecialStageObjectManager {
    private static final Logger LOGGER = Logger.getLogger(Sonic2SpecialStageObjectManager.class.getName());

    /** Marker bytes */
    public static final int MARKER_END = 0xFF;
    public static final int MARKER_CHECKPOINT = 0xFE;
    public static final int MARKER_EMERALD = 0xFD;

    /** Object type bit in first byte */
    public static final int OBJECT_TYPE_BOMB_BIT = 0x40;

    /** Distance mask for first byte */
    public static final int DISTANCE_MASK = 0x3F;

    /** Animation lengths per segment type (from Ani_SSTrack_Len) */
    private static final int[] SEGMENT_ANIM_LENGTHS = { 24, 24, 12, 16, 11 };

    private final Sonic2SpecialStageDataLoader dataLoader;

    /** Raw object location data (decompressed from ROM) */
    private byte[] objectLocationData;

    /** Per-stage offsets into the object data */
    private int[] stageOffsets;

    /** Current read position in object data */
    private int currentPosition;

    /** Current stage index */
    private int currentStage;

    /** Last segment that had objects spawned */
    private int lastProcessedSegment = -1;

    /** Active special stage objects (rings and bombs) */
    private final List<Sonic2SpecialStageObject> activeObjects = new ArrayList<>();

    /** Ring counter */
    private int ringsCollected = 0;

    /** Total rings for "perfect" tracking */
    private int perfectRingsTotal = 0;

    /** Current special act (checkpoint number 0-3) */
    private int currentSpecialAct = 0;

    /** Flags for checkpoint handling */
    private boolean noCheckpointFlag = false;
    private boolean noCheckpointMsgFlag = false;

    /** Whether an emerald was spawned */
    private boolean emeraldSpawned = false;

    public Sonic2SpecialStageObjectManager(Sonic2SpecialStageDataLoader dataLoader) {
        this.dataLoader = dataLoader;
    }

    /**
     * Initializes the object manager for the specified stage.
     */
    public void initialize(int stageIndex) throws IOException {
        this.currentStage = stageIndex;
        this.currentPosition = 0;
        this.lastProcessedSegment = -1;
        this.ringsCollected = 0;
        this.perfectRingsTotal = 0;
        this.currentSpecialAct = 0;
        this.noCheckpointFlag = false;
        this.noCheckpointMsgFlag = false;
        this.emeraldSpawned = false;
        activeObjects.clear();

        // Load object location data
        objectLocationData = dataLoader.getObjectLocations();

        // Parse stage offsets from the data
        parseStageOffsets();

        // Set read position to current stage
        if (stageIndex >= 0 && stageIndex < SPECIAL_STAGE_COUNT && stageOffsets != null) {
            currentPosition = stageOffsets[stageIndex];
        }

        LOGGER.info("Object manager initialized for stage " + (stageIndex + 1) +
                   ", data offset: " + currentPosition);
    }

    /**
     * Parses the stage offset table from the beginning of the object location data.
     * Format: 7 words (big-endian) pointing to each stage's object stream.
     */
    private void parseStageOffsets() {
        if (objectLocationData == null || objectLocationData.length < SPECIAL_STAGE_COUNT * 2) {
            LOGGER.warning("Invalid object location data");
            stageOffsets = null;
            return;
        }

        stageOffsets = new int[SPECIAL_STAGE_COUNT];
        for (int i = 0; i < SPECIAL_STAGE_COUNT; i++) {
            int offset = ((objectLocationData[i * 2] & 0xFF) << 8) |
                        (objectLocationData[i * 2 + 1] & 0xFF);
            stageOffsets[i] = offset;
            LOGGER.fine("Stage " + (i + 1) + " object data offset: 0x" + Integer.toHexString(offset));
        }
    }

    /**
     * Processes objects for a segment transition.
     * This should be called when SSTrack_drawing_index == 4 and a new segment begins.
     *
     * @param segmentIndex The current segment index
     * @param segmentType The segment animation type (0-4)
     * @return List of newly spawned objects
     */
    public List<Sonic2SpecialStageObject> processSegment(int segmentIndex, int segmentType) {
        List<Sonic2SpecialStageObject> newObjects = new ArrayList<>();

        // Only process once per segment
        if (segmentIndex == lastProcessedSegment) {
            return newObjects;
        }
        lastProcessedSegment = segmentIndex;

        if (objectLocationData == null || currentPosition >= objectLocationData.length) {
            return newObjects;
        }

        // Get segment animation length for depth calculation
        int segmentAnimLength = getSegmentAnimLength(segmentType);
        int depthOffset = segmentAnimLength * 4;

        LOGGER.fine("Processing segment " + segmentIndex +
                   " (type=" + segmentType + ", depthOffset=" + depthOffset + ")");

        // Read objects until we hit a marker
        while (currentPosition < objectLocationData.length) {
            int firstByte = objectLocationData[currentPosition] & 0xFF;

            // Check for negative value (marker)
            if ((firstByte & 0x80) != 0) {
                // This is a marker byte
                currentPosition++;
                handleMarker(firstByte, newObjects);
                break; // Exit after processing marker
            }

            // Regular object entry
            currentPosition++;
            if (currentPosition >= objectLocationData.length) break;

            int angleByte = objectLocationData[currentPosition] & 0xFF;
            currentPosition++;

            // Parse object type and distance
            boolean isBomb = (firstByte & OBJECT_TYPE_BOMB_BIT) != 0;
            int distanceIndex = firstByte & DISTANCE_MASK;

            // Calculate depth value (objoff_30)
            int depth = (distanceIndex * 4) + depthOffset;

            // Create and add the object
            Sonic2SpecialStageObject obj;
            if (isBomb) {
                obj = new Sonic2SpecialStageBomb();
            } else {
                obj = new Sonic2SpecialStageRing();
                perfectRingsTotal++;
            }

            obj.initialize(depth, angleByte);
            activeObjects.add(obj);
            newObjects.add(obj);

            LOGGER.fine("Spawned " + (isBomb ? "bomb" : "ring") +
                       " at angle=" + angleByte + ", depth=" + depth);
        }

        return newObjects;
    }

    /**
     * Handles a marker byte in the object stream.
     */
    private void handleMarker(int marker, List<Sonic2SpecialStageObject> newObjects) {
        // Convert to signed for comparison (matching assembly's bmi check)
        if (marker == MARKER_END) {
            // $FF: End of segment's objects - just return
            LOGGER.fine("End marker ($FF) at segment");
            return;
        }

        if (marker == MARKER_CHECKPOINT) {
            // $FE: Checkpoint marker
            LOGGER.info("Checkpoint marker ($FE) - act " + currentSpecialAct);
            handleCheckpoint();
            return;
        }

        if (marker == MARKER_EMERALD) {
            // $FD: Emerald marker
            LOGGER.info("Emerald marker ($FD)");
            handleEmerald(newObjects);
            return;
        }

        // $FC and below: No-checkpoint marker
        LOGGER.fine("No-checkpoint marker (0x" + Integer.toHexString(marker) + ")");
        noCheckpointFlag = true;
        noCheckpointMsgFlag = false;
        // Then behave like $FE (spawn message object)
        handleCheckpoint();
    }

    /**
     * Handles checkpoint marker processing.
     */
    private void handleCheckpoint() {
        // Increment the special act counter
        currentSpecialAct++;

        // TODO: Spawn checkpoint message object (Obj5A)
        // TODO: Check ring requirements
    }

    /**
     * Handles emerald marker processing.
     */
    private void handleEmerald(List<Sonic2SpecialStageObject> newObjects) {
        // TODO: Implement emerald spawning
        // In 1P mode, spawn ObjID_SSEmerald
        // In 2P mode, use different handling
        emeraldSpawned = true;
    }

    /**
     * Gets the animation length for a segment type.
     */
    private int getSegmentAnimLength(int segmentType) {
        if (segmentType >= 0 && segmentType < SEGMENT_ANIM_LENGTHS.length) {
            return SEGMENT_ANIM_LENGTHS[segmentType];
        }
        return 16; // Default to STRAIGHT length
    }

    /**
     * Updates all active objects.
     * Called each frame to update object positions and animations.
     *
     * @param currentTrackFrame Current track mapping frame (0-55)
     * @param trackFlipped Whether the track is flipped (left turn)
     * @param speedFactor Current speed factor from track animator (affects depth decrement rate)
     */
    public void update(int currentTrackFrame, boolean trackFlipped, int speedFactor) {
        // Update each active object
        for (int i = activeObjects.size() - 1; i >= 0; i--) {
            Sonic2SpecialStageObject obj = activeObjects.get(i);
            obj.update(currentTrackFrame, trackFlipped, speedFactor);

            // Remove objects that are done (collected or off-screen)
            if (obj.shouldRemove()) {
                activeObjects.remove(i);
            }
        }
    }

    /**
     * Gets all active objects for rendering.
     */
    public List<Sonic2SpecialStageObject> getActiveObjects() {
        return activeObjects;
    }

    /**
     * Collects a ring and increments the counter.
     */
    public void collectRing() {
        ringsCollected++;
    }

    /**
     * Loses rings from a bomb hit (BCD-style subtraction).
     * Returns the number of rings lost.
     */
    public int loseRingsFromBombHit() {
        if (ringsCollected == 0) {
            return 0;
        }

        int ringsLost;
        if (ringsCollected >= 10) {
            // Lose exactly 10 rings
            ringsLost = 10;
            ringsCollected -= 10;
        } else {
            // Lose all remaining rings
            ringsLost = ringsCollected;
            ringsCollected = 0;
        }

        return ringsLost;
    }

    /**
     * Gets the current ring count.
     */
    public int getRingsCollected() {
        return ringsCollected;
    }

    /**
     * Gets the total rings spawned (for perfect bonus tracking).
     */
    public int getPerfectRingsTotal() {
        return perfectRingsTotal;
    }

    /**
     * Gets the current special act (checkpoint number).
     */
    public int getCurrentSpecialAct() {
        return currentSpecialAct;
    }

    /**
     * Checks if an emerald was spawned in this stage.
     */
    public boolean isEmeraldSpawned() {
        return emeraldSpawned;
    }

    /**
     * Resets the manager state.
     */
    public void reset() {
        currentPosition = 0;
        lastProcessedSegment = -1;
        ringsCollected = 0;
        perfectRingsTotal = 0;
        currentSpecialAct = 0;
        noCheckpointFlag = false;
        noCheckpointMsgFlag = false;
        emeraldSpawned = false;
        activeObjects.clear();
    }
}
