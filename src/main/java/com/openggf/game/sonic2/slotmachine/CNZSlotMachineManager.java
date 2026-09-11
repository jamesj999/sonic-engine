package com.openggf.game.sonic2.slotmachine;

import com.openggf.audio.AudioManager;

import java.util.logging.Logger;
import com.openggf.game.GameServices;

/**
 * Manages the CNZ slot machine state machine.
 * <p>
 * Based on disassembly (s2.asm lines 58802-59315):
 * <ul>
 *   <li>Routine 0x00: Initialize - clear variables, set random seed</li>
 *   <li>Routine 0x04: Initial roll - draw slots once, then go to inactive</li>
 *   <li>Routine 0x08: Setup targets - select target faces from random seed</li>
 *   <li>Routine 0x0C: Main rolling - increase speed, random timer</li>
 *   <li>Routine 0x10: Fine-tune - slow down each slot to hit target</li>
 *   <li>Routine 0x14: Determine reward - calculate match bonuses</li>
 *   <li>Routine 0x18: Inactive (null routine)</li>
 * </ul>
 * <p>
 * Slot face values (per disassembly SlotRingRewards and s2.asm:59268):
 * <ul>
 *   <li>0 = Sonic (30 rings)</li>
 *   <li>1 = Tails (25 rings)</li>
 *   <li>2 = Eggman (-1 = bombs)</li>
 *   <li>3 = Jackpot (150 rings)</li>
 *   <li>4 = Ring (10 rings)</li>
 *   <li>5 = Bar (20 rings) - confirmed as Bar at s2.asm:59268</li>
 * </ul>
 */
public class CNZSlotMachineManager {
    private static final Logger LOGGER = Logger.getLogger(CNZSlotMachineManager.class.getName());

    // Routine state values
    private static final int ROUTINE_INIT = 0x00;
    private static final int ROUTINE_INITIAL_ROLL = 0x04;
    private static final int ROUTINE_SETUP_TARGETS = 0x08;
    private static final int ROUTINE_MAIN_ROLLING = 0x0C;
    private static final int ROUTINE_FINE_TUNE = 0x10;
    private static final int ROUTINE_DETERMINE_REWARD = 0x14;
    private static final int ROUTINE_INACTIVE = 0x18;

    // Slot subroutine states
    private static final int SLOT_SUB_WAIT = 0x00;
    private static final int SLOT_SUB_APPROACH = 0x04;
    private static final int SLOT_SUB_REVERSE = 0x08;
    private static final int SLOT_SUB_DONE = 0x0C;

    // Slot face values (from disassembly - face 5 is bar per line 59268)
    // Art order in Slot pictures.bin: Sonic, Tails, Eggman, Jackpot, Ring, Bar
    public static final int FACE_SONIC = 0;
    public static final int FACE_TAILS = 1;
    public static final int FACE_EGGMAN = 2;
    public static final int FACE_JACKPOT = 3;
    public static final int FACE_RING = 4;
    public static final int FACE_BAR = 5;

    // Ring rewards per face (from SlotRingRewards in disassembly)
    // -1 means bombs instead of rings
    // Index: 0=Sonic(30), 1=Tails(25), 2=Eggman(-1), 3=Jackpot(150), 4=Ring(10), 5=Bar(20)
    private static final int[] RING_REWARDS = {30, 25, -1, 150, 10, 20};

    // Slot sequences (from disassembly)
    private static final int[] SLOT_SEQUENCE_1 = {3, 0, 1, 4, 2, 5, 4, 1};
    private static final int[] SLOT_SEQUENCE_2 = {3, 0, 1, 4, 2, 5, 0, 2};
    private static final int[] SLOT_SEQUENCE_3 = {3, 0, 1, 4, 2, 5, 4, 1};
    private static final int[] SLOT_SEQUENCE_ROM_WINDOW = {
            3, 0, 1, 4, 2, 5, 4, 1,
            3, 0, 1, 4, 2, 5, 0, 2,
            3, 0, 1, 4, 2, 5, 4, 1
    };
    private static final int SLOT_SEQUENCE_1_ROM_ADDR = 0x2C401;

    // Target value combinations (prob, slot1, slot2_3 packed).
    // From SlotTargetValues at s2.asm:59288-59289, including the $FF sentinel row.
    private static final int[][] TARGET_VALUES = {
            {0x08, 3, 0x33},  // Triple jackpot (8/256 chance) - 150 rings
            {0x12, 0, 0x00},  // Triple Sonic - 30 rings
            {0x12, 1, 0x11},  // Triple Tails - 25 rings
            {0x24, 2, 0x22},  // Triple Eggman - bombs
            {0x1E, 4, 0x44},  // Triple Ring - 10 rings (face 4 per RING_REWARDS)
            {0x1E, 5, 0x55},  // Triple Bar - 20 rings (face 5 confirmed as Bar at s2.asm:59268)
            {0xFF, 0x0F, 0xFF},
    };

    // State variables
    private int routine = ROUTINE_INIT;
    private int slotTimer = 0;
    private int slotIndex = 0;  // Current slot being processed (0, 4, 8 for slots 1, 2, 3)

    // Per-slot data
    private final int[] slotIndices = new int[3];     // Sequence index
    private final int[] slotOffsets = new int[3];     // Sub-pixel offset
    private final int[] slotSpeeds = new int[3];      // Rotation speed
    private final int[] slotSubroutines = new int[3]; // Per-slot subroutine state

    // Target values
    private int slot1Target = 0;
    private int slot23Target = 0;  // Packed: (slot2 << 4) | slot3

    // Final reward (positive = rings, negative = bombs)
    private int reward = 0;
    private boolean rewardDetermined = false;
    private boolean inUse = false;
    private int lastActivationFrame = -1;
    private int lastCompletionFrame = -1;
    private int lastFineTuneTimerSeed = -1;
    private int lastDispatchedVint = -1;

    private final AudioManager audioManager;
    private int frameCounter;

    public CNZSlotMachineManager() {
        this.audioManager = GameServices.audio();
    }

    /**
     * Read-only view of the ROM's {@code SlotMachineVariables} block, for
     * comparison against a recorded {@code cnz_slot_machine_state} row.
     * Comparison-only: nothing may write engine state back from this.
     */
    public record Snapshot(
            boolean inUse,
            int routine,
            int timer,
            int index,
            int reward,
            java.util.List<Integer> slotPos,
            java.util.List<Integer> slotSpeed,
            java.util.List<Integer> slotRoutine) {}

    /**
     * Captures the current slot variables in ROM layout. {@code slotPos} packs
     * the reel index in the high byte and the sub-tile offset in the low byte,
     * matching the ROM's 16-bit {@code (index, offset)} word.
     */
    public Snapshot snapshot() {
        java.util.List<Integer> pos = new java.util.ArrayList<>(3);
        java.util.List<Integer> speed = new java.util.ArrayList<>(3);
        java.util.List<Integer> rout = new java.util.ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            pos.add((((slotIndices[i] & 0xFF) << 8) | (slotOffsets[i] & 0xFF)) & 0xFFFF);
            speed.add(slotSpeeds[i] & 0xFF);
            rout.add(slotSubroutines[i] & 0xFF);
        }
        return new Snapshot(inUse, routine & 0xFF, slotTimer & 0xFF, slotIndex & 0xFF,
                reward & 0xFFFF, pos, speed, rout);
    }

    /**
     * Check if slot machine is available (inactive).
     */
    public boolean isAvailable() {
        return routine == ROUTINE_INACTIVE && !inUse;
    }

    /**
     * Activate the slot machine from a PointPokey cage.
     */
    public void activate() {
        if (routine != ROUTINE_INACTIVE) {
            LOGGER.warning("Attempted to activate slot machine while busy");
            return;
        }
        inUse = true;
        lastActivationFrame = frameCounter;
        routine = ROUTINE_SETUP_TARGETS;
        rewardDetermined = false;
        reward = 0;
    }

    /**
     * Check if reward has been determined.
     */
    public boolean isComplete() {
        return rewardDetermined;
    }

    /**
     * Get the final reward.
     * @return Positive value for rings, negative for bombs.
     */
    public int getReward() {
        return reward;
    }

    /**
     * Reset the slot machine for next use.
     */
    public void reset() {
        routine = ROUTINE_INIT;
        rewardDetermined = false;
        reward = 0;
        inUse = false;
        slotTimer = 0;
        slotIndex = 0;
        lastDispatchedVint = -1;
        for (int i = 0; i < 3; i++) {
            slotIndices[i] = 0;
            slotOffsets[i] = 0;
            slotSpeeds[i] = 0;
            slotSubroutines[i] = SLOT_SUB_WAIT;
        }
    }

    /**
     * Deactivate the slot machine without resetting visual state.
     * Called when player exits the cage to preserve reel positions.
     */
    public void deactivate() {
        routine = ROUTINE_INACTIVE;
        rewardDetermined = false;
        reward = 0;
        inUse = false;
        slotTimer = 0;
        slotIndex = 0;
        for (int i = 0; i < 3; i++) {
            slotSpeeds[i] = 0;
            slotSubroutines[i] = SLOT_SUB_WAIT;
        }
        // DO NOT reset slotIndices or slotOffsets - preserve visual state
    }

    /**
     * Clears the cage ownership latch without stopping the reels.
     * <p>
     * ROM ObjD6 clears {@code SlotMachineInUse} when a linked PointPokey exits,
     * but {@code SlotMachine_Routine} continues to run from {@code LevEvents_CNZ}
     * until the reels naturally complete.
     */
    public void releaseUse() {
        inUse = false;
    }

    /**
     * Update the slot machine state.
     * Called each frame while slot machine is active.
     */
    public void update() {
        int currentFrame = 0;
        var levelManager = GameServices.levelOrNull();
        if (levelManager != null) {
            var objectManager = levelManager.getObjectManager();
            currentFrame = objectManager != null
                    ? objectManager.getVblaCounter()
                    : levelManager.getFrameCounter();
        }
        int currentVint = currentFrame & 0xFFFF;
        if (lastDispatchedVint == currentVint) {
            return;
        }
        lastDispatchedVint = currentVint;
        update(currentFrame);
    }

    /**
     * Update the slot machine state using the ROM-equivalent V-int frame counter.
     */
    public void update(int frameCounter) {
        this.frameCounter = frameCounter;
        switch (routine) {
            case ROUTINE_INIT -> routineInit();
            case ROUTINE_INITIAL_ROLL -> routineInitialRoll();
            case ROUTINE_SETUP_TARGETS -> routineSetupTargets();
            case ROUTINE_MAIN_ROLLING -> routineMainRolling();
            case ROUTINE_FINE_TUNE -> routineFineTune();
            case ROUTINE_DETERMINE_REWARD -> routineDetermineReward();
            case ROUTINE_INACTIVE -> { /* Do nothing */ }
        }
    }

    /**
     * Routine 0: Initialize variables.
     */
    private void routineInit() {
        // Clear all slot data
        int seed = vintLowByte();
        for (int i = 0; i < 3; i++) {
            slotOffsets[i] = 8;  // Start at offset 8 (1 pixel line)
            slotSpeeds[i] = 8;   // Initial rolling speed
            slotSubroutines[i] = SLOT_SUB_WAIT;
        }
        slotIndices[0] = seed & 0xFF;
        seed = rotateRightByte(seed, 3);
        slotIndices[1] = seed & 0xFF;
        seed = rotateRightByte(seed, 3);
        slotIndices[2] = seed & 0xFF;
        slotTimer = 1;
        slotIndex = 0;
        routine = ROUTINE_INITIAL_ROLL;
    }

    /**
     * Routine 1: Initial roll (draw slots once).
     */
    private void routineInitialRoll() {
        updateSlotPositions();
        if ((slotTimer & 0xFF) == 0) {
            // Stop all slots
            for (int i = 0; i < 3; i++) {
                slotSpeeds[i] = 0;
            }
            routine = ROUTINE_INACTIVE;
        }
    }

    /**
     * Routine 2: Setup target values from random seed.
     */
    private void routineSetupTargets() {
        // Generate starting speeds from Vint_runcount bytes.
        int lowSeed = vintLowByte();
        slotSpeeds[0] = ((lowSeed & 0x07) - 4) + 0x30;
        slotSpeeds[1] = ((rotateLeftByte(lowSeed, 4) & 0x07) - 4) + 0x30;
        slotSpeeds[2] = ((vintHighByte() & 0x07) - 4) + 0x30;

        slotTimer = 2;  // Roll each slot twice
        slotIndex = 0;

        // Reset subroutines
        for (int i = 0; i < 3; i++) {
            slotSubroutines[i] = SLOT_SUB_WAIT;
        }

        // Determine target values
        selectTargetValues();

        routine = ROUTINE_MAIN_ROLLING;
    }

    /**
     * Select target values based on random probability.
     */
    private void selectTargetValues() {
        int seed = rotateRightByte(vintLowByte(), 3);

        // ROM SlotMachine_Routine3 subtracts each table byte from the
        // already-mutated seed and branches on 68k byte borrow, rather than
        // comparing against a cumulative sum (s2.asm:58903-58914).
        for (int[] targetValue : TARGET_VALUES) {
            int threshold = targetValue[0] & 0xFF;
            int nextSeed = seed - threshold;
            if (nextSeed < 0) {
                if (threshold == 0xFF) {
                    // The 68k branch tests carry after sub.b, but d0 already
                    // contains the wrapped subtraction result (s2.asm:58908-58924).
                    selectFallbackTargetValues(nextSeed);
                    return;
                }
                slot1Target = targetValue[1];
                slot23Target = targetValue[2];
                return;
            }
            seed = nextSeed & 0xFF;
        }

        selectFallbackTargetValues(seed);
    }

    private void selectFallbackTargetValues(int seed) {
        // The ROM masks into d1, but slot 1 accidentally indexes SlotSequence1
        // with the unmasked d0 byte (s2.asm:58915-58923). This can read past
        // SlotSequence1 into the following SlotSequence2/3 bytes; do not wrap.
        int fallbackSeed = seed & 0xFF;
        slot1Target = readSlotSequence1Unmasked(fallbackSeed);
        fallbackSeed = rotateRightByte(fallbackSeed, 3);
        int slot2Target = SLOT_SEQUENCE_2[fallbackSeed & 7];
        fallbackSeed = rotateRightByte(fallbackSeed, 3);
        slot23Target = (slot2Target << 4) | SLOT_SEQUENCE_3[fallbackSeed & 7];
    }

    private int readSlotSequence1Unmasked(int index) {
        try {
            var rom = GameServices.rom().getRom();
            return rom.readByte(SLOT_SEQUENCE_1_ROM_ADDR + index) & 0xFF;
        } catch (Exception ignored) {
            // Unit tests can exercise the manager without a ROM; fall back below.
        }
        if (index >= 0 && index < SLOT_SEQUENCE_ROM_WINDOW.length) {
            return SLOT_SEQUENCE_ROM_WINDOW[index];
        }
        return SLOT_SEQUENCE_1[index & 7];
    }

    /**
     * Routine 3: Main rolling phase.
     */
    private void routineMainRolling() {
        updateSlotPositions();

        if ((slotTimer & 0xFF) == 0) {
            // Increase speeds for final phase
            for (int i = 0; i < 3; i++) {
                slotSpeeds[i] += 0x30;
            }

            slotTimer = (vintLowByte() & 0x0F) + 0x0C;
            lastFineTuneTimerSeed = vintLowByte();
            slotIndex = 0;
            routine = ROUTINE_FINE_TUNE;
        }
    }

    /**
     * Routine 4: Fine-tune each slot to hit target.
     * Per disassembly, updateSlotPositions processes one slot per call and advances slotIndex.
     * The subroutine is processed for the slot that was just updated.
     */
    private void routineFineTune() {
        updateSlotPositions();

        // Check if all slots are done
        boolean allDone = true;
        for (int i = 0; i < 3; i++) {
            if (slotSubroutines[i] != SLOT_SUB_DONE) {
                allDone = false;
                break;
            }
        }

        if (allDone) {
            routineDetermineReward();
            return;
        }

        // SlotMachine_DrawSlot advances slot_index before Routine5 dispatches
        // the fine-tune subroutine, so the ROM processes the next slot after
        // the drawn one (s2.asm:58969-58993, 59152-59173).
        int processingSlot = slotIndex / 4;
        if (processingSlot < 3) {
            processSlotSubroutine(processingSlot);
        }
    }

    /**
     * Process fine-tuning subroutine for a slot.
     */
    private void processSlotSubroutine(int slot) {
        int target = getTargetForSlot(slot);
        int[] sequence = getSequenceForSlot(slot);

        switch (slotSubroutines[slot]) {
            case SLOT_SUB_WAIT -> {
                // Wait for previous slot to be in REVERSE or DONE state
                if (slot == 0) {
                    if (signedByte(slotTimer) >= 0) {
                        return;
                    }
                } else {
                    if (slotSubroutines[slot - 1] < SLOT_SUB_REVERSE) {
                        return;
                    }
                }

                // Check if approaching target
                int pos = (slotIndices[slot] << 8) | (slotOffsets[slot] & 0xFF);
                int idx = (((pos - 0xA0) & 0xFFFF) >> 8) & 0x07;
                if (sequence[idx] == target) {
                    slotSubroutines[slot] = SLOT_SUB_APPROACH;
                    slotSpeeds[slot] = 0x60;  // Decrease speed
                }
            }
            case SLOT_SUB_APPROACH -> {
                // Per disassembly SlotMachine_Routine5_2 (s2.asm:59021-59061):
                // Check if we're close to target (add 0xF0 = ~4 tiles lookahead)
                int pos = (slotIndices[slot] << 8) | (slotOffsets[slot] & 0xFF);
                int lookAheadPos = (pos + 0xF0) & 0x700;  // Mask to face boundary
                int lookAheadIdx = (lookAheadPos >> 8) & 0x07;
                int faceAtLookAhead = sequence[lookAheadIdx];

                if (faceAtLookAhead == target) {
                    // Target detected! Adjust position, rewrite this slot's
                    // target to the actual stopped face, and reverse
                    // (SlotMachine_ChangeTarget, s2.asm:59075-59085).
                    int alignedIdx = ((pos + 0x80) >> 8) & 0x07;
                    setTargetForSlot(slot, sequence[alignedIdx]);
                    // The ROM masks the face boundary, then performs a 16-bit
                    // subtraction. Boundary zero therefore underflows to
                    // $FFF0; it is not re-masked to the eight-face window
                    // (SlotMachine_Routine5_2, s2.asm:59550-59558).
                    int newPos = (((pos + 0x80) & 0x700) - 0x10) & 0xFFFF;
                    slotIndices[slot] = (newPos >> 8) & 0xFF;
                    slotOffsets[slot] = newPos & 0xFF;
                    // Negative speed to reverse slowly (-8 in original)
                    slotSpeeds[slot] = -8;
                    slotSubroutines[slot] = SLOT_SUB_REVERSE;
                } else {
                    // Slow down as we approach (s2.asm:59030-59044)
                    if (slotSpeeds[slot] > 0x20) {
                        slotSpeeds[slot] -= 0x0C;  // Reduce by 12
                    }
                    // Additional deceleration when speed is still above
                    // $18 and offset <= $80 (s2.asm:59059-59070).
                    if (slotSpeeds[slot] > 0x18) {
                        if ((slotOffsets[slot] & 0xFF) <= 0x80) {
                            slotSpeeds[slot] -= 2;
                        }
                    }
                }
            }
            case SLOT_SUB_REVERSE -> {
                // Per disassembly SlotMachine_Routine5_3 (s2.asm:59064-59072):
                // Position was set to (face_boundary - 0x10), e.g., 0x2F0.
                // With speed -8, each frame pos increases: 0x2F0 -> 0x2F8 -> 0x300.
                // When offset byte reaches 0x00, we're aligned and stop.
                if ((slotOffsets[slot] & 0xFF) == 0) {
                    slotSpeeds[slot] = 0;
                    slotSubroutines[slot] = SLOT_SUB_DONE;
                }
            }
            case SLOT_SUB_DONE -> {
                // Nothing to do
            }
        }
    }

    private void setTargetForSlot(int slot, int targetFace) {
        int shift = targetShiftForSlot(slot);
        if (shift < 0) {
            return;
        }
        int face = targetFace & 0x0F;
        int mask = ~(0x0F << shift) & 0x0FFF;
        int packed = packedTargets();
        packed = (packed & mask) | ((face << shift) & 0xFFFF);
        unpackTargets(packed & 0x0777);
    }

    /**
     * Get target face value for a slot.
     */
    private int getTargetForSlot(int slot) {
        int shift = targetShiftForSlot(slot);
        if (shift < 0) {
            return 0;
        }
        int target = (packedTargets() >> shift) & 0x07;
        return target > FACE_BAR ? target - 2 : target;
    }

    private int targetShiftForSlot(int slot) {
        return switch (slot) {
            // ROM SlotMachine_GetTargetForSlot shifts slots_targ by the current
            // slot_index byte value: 0, 4, or 8 (s2.asm:59460-59467).
            case 0 -> 0;
            case 1 -> 4; // slot2_targ is the high nibble of slot23_targ
            case 2 -> 8; // slot1_targ is the high byte of slots_targ
            default -> -1;
        };
    }

    private int packedTargets() {
        return ((slot1Target & 0xFF) << 8) | (slot23Target & 0xFF);
    }

    private void unpackTargets(int packed) {
        slot1Target = (packed >> 8) & 0xFF;
        slot23Target = packed & 0xFF;
    }

    /**
     * Get sequence array for a slot.
     */
    private int[] getSequenceForSlot(int slot) {
        return switch (slot) {
            case 0 -> SLOT_SEQUENCE_1;
            case 1 -> SLOT_SEQUENCE_2;
            case 2 -> SLOT_SEQUENCE_3;
            default -> SLOT_SEQUENCE_1;
        };
    }

    /**
     * Find the sequence index that displays the target face.
     */
    private int findSequenceIndexForFace(int[] sequence, int targetFace) {
        for (int i = 0; i < sequence.length; i++) {
            if (sequence[i] == targetFace) {
                return i;
            }
        }
        return 0;
    }

    /**
     * Routine 5: Determine reward based on final slot positions.
     */
    private void routineDetermineReward() {
        // Stop all slots
        for (int i = 0; i < 3; i++) {
            // ROM Routine6 uses clr.w at slotN_speed, clearing both the speed
            // byte and its adjacent slotN_rout byte (s2.asm:59579-59581).
            slotSpeeds[i] = 0;
            slotSubroutines[i] = SLOT_SUB_WAIT;
        }
        slotTimer = 0;

        // Calculate reward
        reward = calculateReward();
        rewardDetermined = true;
        lastCompletionFrame = frameCounter;
        routine = ROUTINE_INACTIVE;

        // Debug output showing reel results
        String face1 = getFaceName(getSlotFace(0));
        String face2 = getFaceName(getSlotFace(1));
        String face3 = getFaceName(getSlotFace(2));
        String outcome = (reward < 0) ? "100 Bombs" : (reward == 0) ? "No Match" : reward + " Rings";
        LOGGER.info(String.format("Reel 1: %s | Reel 2: %s | Reel 3: %s | Outcome: %s",
                face1, face2, face3, outcome));
    }

    /**
     * Get the display name for a face value.
     */
    private static String getFaceName(int face) {
        return switch (face) {
            case FACE_SONIC -> "Sonic";
            case FACE_TAILS -> "Tails";
            case FACE_EGGMAN -> "Eggman";
            case FACE_JACKPOT -> "Jackpot";
            case FACE_RING -> "Ring";
            case FACE_BAR -> "Bar";
            default -> "???";
        };
    }

    /**
     * Calculate reward based on slot results.
     */
    private int calculateReward() {
        int slot1 = slot1Target & 0x07;
        int slot2 = (slot23Target >> 4) & 0x07;
        int slot3 = slot23Target & 0x07;

        // Check for matches
        boolean match12 = (slot1 == slot2);
        boolean match13 = (slot1 == slot3);
        boolean match23 = (slot2 == slot3);

        if (match12 && match13) {
            // Triple match
            return RING_REWARDS[slot1];
        }

        if (match13) {
            // Slots 1 and 3 match
            if (slot3 == FACE_JACKPOT) {
                // Two jackpots + slot2: 4x slot2 reward
                return RING_REWARDS[slot2] * 4;
            } else if (slot2 == FACE_JACKPOT) {
                // Two matching + jackpot: 2x reward
                return RING_REWARDS[slot3] * 2;
            }
        }

        if (match12) {
            // Slots 1 and 2 match
            if (slot2 == FACE_JACKPOT) {
                // Two jackpots + slot3: 4x slot3 reward
                return RING_REWARDS[slot3] * 4;
            } else if (slot3 == FACE_JACKPOT) {
                // Two matching + jackpot: 2x reward
                return RING_REWARDS[slot2] * 2;
            }
        }

        if (match23) {
            // Slots 2 and 3 match
            if (slot2 == FACE_JACKPOT) {
                // Two jackpots + slot1: 4x slot1 reward
                return RING_REWARDS[slot1] * 4;
            } else if (slot1 == FACE_JACKPOT) {
                // Two matching + jackpot: 2x reward
                return RING_REWARDS[slot2] * 2;
            }
        }

        // Check for bars - per disassembly SlotMachine_CheckBars (s2.asm:59265-59280):
        // Awards 2 rings per BAR found, regardless of position or count.
        // Even a single BAR awards 2 rings.
        int barReward = 0;
        if (slot1 == FACE_BAR) barReward += 2;
        if (slot2 == FACE_BAR) barReward += 2;
        if (slot3 == FACE_BAR) barReward += 2;

        return barReward;  // 0 if no bars
    }

    /**
     * Update slot positions based on speed.
     * Per disassembly, only ONE slot is updated per frame (cycles through 0, 1, 2).
     * Timer only decrements when cycling back to slot 0.
     *
     * The 68k code treats (index, offset) as a single 16-bit word and does:
     *   sub.w speed, position
     * This means subtracting a negative speed ADDS to position, with natural carry.
     */
    private void updateSlotPositions() {
        // Get current slot from slotIndex (0, 4, 8 -> slot 0, 1, 2)
        int currentSlot = slotIndex / 4;
        if (currentSlot < 3) {
            // Combine into 16-bit position like the 68k does
            int pos = (slotIndices[currentSlot] << 8) | (slotOffsets[currentSlot] & 0xFF);

            // Subtract speed (negative speed adds, positive speed subtracts)
            pos = (pos - slotSpeeds[currentSlot]) & 0xFFFF;

            // Split back into index and offset
            slotIndices[currentSlot] = (pos >> 8) & 0xFF;
            slotOffsets[currentSlot] = pos & 0xFF;
        }

        // Advance to next slot
        slotIndex = (slotIndex + 4) % 12;

        // SlotMachine_Subroutine3 decrements the byte timer whenever drawing
        // wraps from slot 3 back to slot 1, allowing Routine5_1 to observe the
        // negative $FF state after zero (s2.asm:59158-59166, 58995-59005).
        if (slotIndex == 0) {
            slotTimer = (slotTimer - 1) & 0xFF;
        }
    }

    /**
     * Get the current face showing on a slot (for rendering).
     */
    public int getSlotFace(int slot) {
        if (slot < 0 || slot > 2) {
            return 0;
        }
        int[] sequence = getSequenceForSlot(slot);
        return sequence[slotIndices[slot] & 0x07];
    }

    /**
     * Get the NEXT face in the sequence for a slot (for rendering scroll wrap).
     * This is NOT simply faceIndex+1, but the actual next face in the slot sequence.
     */
    public int getSlotNextFace(int slot) {
        if (slot < 0 || slot > 2) {
            return 0;
        }
        int[] sequence = getSequenceForSlot(slot);
        int nextIndex = (slotIndices[slot] + 1) & 0x07;
        return sequence[nextIndex];
    }

    /**
     * Get the current offset within a face (for rendering).
     */
    public int getSlotOffset(int slot) {
        if (slot < 0 || slot > 2) {
            return 0;
        }
        return slotOffsets[slot];
    }

    /**
     * Check if the slot machine is currently running (not inactive).
     */
    public boolean isRunning() {
        return routine != ROUTINE_INACTIVE;
    }

    public String traceDebugState() {
        return String.format(
                "fc=%04X inUse=%d act=%04X doneAt=%04X ftSeed=%02X rt=%02X timer=%02X idx=%d targ=%03X sub=%02X/%02X/%02X pos=%02X.%02X/%02X.%02X/%02X.%02X sp=%02X/%02X/%02X reward=%d done=%d",
                frameCounter & 0xFFFF,
                inUse ? 1 : 0,
                lastActivationFrame & 0xFFFF,
                lastCompletionFrame & 0xFFFF,
                lastFineTuneTimerSeed & 0xFF,
                routine & 0xFF,
                slotTimer & 0xFF,
                slotIndex & 0xFF,
                packedTargets() & 0xFFF,
                slotSubroutines[0] & 0xFF,
                slotSubroutines[1] & 0xFF,
                slotSubroutines[2] & 0xFF,
                slotIndices[0] & 0xFF,
                slotOffsets[0] & 0xFF,
                slotIndices[1] & 0xFF,
                slotOffsets[1] & 0xFF,
                slotIndices[2] & 0xFF,
                slotOffsets[2] & 0xFF,
                slotSpeeds[0] & 0xFF,
                slotSpeeds[1] & 0xFF,
                slotSpeeds[2] & 0xFF,
                reward,
                rewardDetermined ? 1 : 0);
    }

    private int vintLowByte() {
        return frameCounter & 0xFF;
    }

    private int vintHighByte() {
        return (frameCounter >>> 8) & 0xFF;
    }

    private static int rotateLeftByte(int value, int amount) {
        return ((value << amount) | (value >>> (8 - amount))) & 0xFF;
    }

    private static int rotateRightByte(int value, int amount) {
        return ((value >>> amount) | (value << (8 - amount))) & 0xFF;
    }

    private static int signedByte(int value) {
        int b = value & 0xFF;
        return b >= 0x80 ? b - 0x100 : b;
    }
}
