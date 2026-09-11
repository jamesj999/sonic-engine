package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.GameRng;
import com.openggf.game.GameServices;

public final class S3kSlotOptionCycleSystem {
    private static final int STATE_INIT = 0;
    private static final int STATE_SPIN = 4;
    private static final int STATE_PICK_TARGETS = 8;
    private static final int STATE_DECELERATE = 12;
    private static final int STATE_LOCK_REELS = 16;
    private static final int STATE_AWARD = 20;
    private static final int STATE_IDLE = 24;

    public void bootstrap(S3kSlotStageState state) {
        if (state == null) {
            return;
        }
        state.resetOptionCycleState();
    }

    public void tick(S3kSlotStageState state, int frameCounter) {
        GameRng rng;
        try {
            rng = GameServices.rng();
        } catch (IllegalStateException e) {
            rng = new GameRng(GameRng.Flavour.S3K);
        }
        tick(state, frameCounter, rng);
    }

    public void tick(S3kSlotStageState state, int frameCounter, GameRng rng) {
        if (state == null) {
            return;
        }
        switch (state.optionCycleState()) {
            case STATE_INIT -> tickInit(state, frameCounter);
            case STATE_SPIN -> tickSpin(state);
            case STATE_PICK_TARGETS -> tickPickTargets(state, frameCounter, rng);
            case STATE_DECELERATE -> tickDecelerate(state, frameCounter);
            case STATE_LOCK_REELS -> tickLockReels(state);
            case STATE_AWARD -> tickAward(state);
            case STATE_IDLE -> tickIdle(state);
            default -> {
                state.setOptionCycleState(STATE_INIT);
            }
        }
    }

    public boolean isResolved(S3kSlotStageState state) {
        return state != null && state.optionCycleState() == STATE_IDLE;
    }

    public int lastPrizeResult(S3kSlotStageState state) {
        return state != null ? state.optionCycleLastPrize() : Integer.MIN_VALUE;
    }

    public int completedCycles(S3kSlotStageState state) {
        return state != null ? state.optionCycleCompletedCycles() : 0;
    }

    public int[] displaySymbols(S3kSlotStageState state) {
        return state != null ? state.optionCycleDisplaySymbols() : new int[0];
    }

    public static int sourceOffsetFor(int symbol, int reelWord) {
        return ((symbol & 0x07) << 9) + ((reelWord & 0x00F8) >> 1);
    }

    private void tickInit(S3kSlotStageState state, int frameCounter) {
        int reelWordA = ((frameCounter & 0xFF) << 8) | 0x08;
        int rotated = rotateRight8(frameCounter, 3);
        int reelWordB = (rotated << 8) | 0x08;
        int reelWordC = (rotateRight8(rotated, 3) << 8) | 0x08;
        state.setOptionCycleReelWords(reelWordA, reelWordB, reelWordC);
        state.setOptionCycleReelVelocities(0x08, 0x08, 0x08);
        state.setOptionCycleReelSubstates(0, 0, 0);
        syncDisplayFromReelWords(state);
        state.setOptionCycleState(STATE_SPIN);
        state.setOptionCycleCountdown(1);
        state.setOptionCycleActiveReelIndex(0);
        state.setOptionCycleLockProgress(0);
        state.setOptionCycleResolvedDisplayTimer(0);
        state.setOptionCycleSpinCycleCounter(0);
    }

    private void tickSpin(S3kSlotStageState state) {
        advanceCurrentReelWord(state);
        if (state.optionCycleCountdown() == 0) {
            enterResolvedIdle(state);
        }
    }

    private void tickPickTargets(S3kSlotStageState state, int frameCounter, GameRng rng) {
        int reel0Offset = (((frameCounter & 0x07) - 4) + 0x30) & 0xFF;
        // ROM sonic3k.asm:99684-99686: move.b (V_int_run_count+3).w,d0 / rol.b
        // #4,d0 / andi.b #7,d0 -- an 8-bit ROTATE of the low byte, not a plain
        // shift. java.lang.Integer.rotateLeft operates on the full 32-bit
        // register: since (frameCounter & 0xFF) only ever populates bits 0-7,
        // rotating it left by 4 within a 32-bit word never wraps any set bit
        // back around to the low 3 bits that andi.b #7,d0 then reads, so the
        // masked low byte's bits 0-2 were always 0 -- reel1Offset was a
        // frame-invariant constant (0x2C) instead of tracking V_int_run_count.
        // rotateRight8(value, 4) reproduces rol.b #4 exactly for an 8-bit
        // operand (a 4-bit rotate is its own inverse on an 8-bit word).
        int reel1Offset = (((rotateRight8(frameCounter, 4) & 0x07) - 4) + 0x30) & 0xFF;
        int reel2Offset = ((((frameCounter >>> 8) & 0x07) - 4) + 0x30) & 0xFF;
        state.setOptionCycleReelVelocities(reel0Offset, reel1Offset, reel2Offset);
        state.setOptionCycleReelSubstates(0, 0, 0);

        int seed = frameCounter & 0xFF;
        int rotated = ((seed >>> 3) | (seed << 5)) & 0xFF;

        int fixedRow = S3kSlotRewardResolver.pickFixedRow(rotated);
        if (fixedRow >= 0) {
            int rowBase = fixedRow * 3;
            state.setOptionCycleTargetReelA(S3kSlotRomData.TARGET_ROWS[rowBase + 1] & 0xFF);
            state.setOptionCycleTargetPackedBC(S3kSlotRomData.TARGET_ROWS[rowBase + 2] & 0xFF);
        } else {
            pickRandomTargets(state, frameCounter, rng);
        }

        state.setOptionCycleCountdown(0x02);
        state.setOptionCycleLockProgress(0);
        state.setOptionCycleActiveReelIndex(0);
        state.setOptionCycleState(STATE_DECELERATE);
    }

    private void pickRandomTargets(S3kSlotStageState state, int frameCounter, GameRng rng) {
        int rnd = rng != null ? rng.nextWord() : 0;
        rnd = (rnd + frameCounter) & 0xFFFF;
        rnd = rotateRight16(rnd, 4);

        int idxA = rnd & 7;
        int targetA = S3kSlotRomData.REEL_SEQUENCE_A[idxA];

        int idxB = (rnd >>> 3) & 7;
        int targetB = S3kSlotRomData.REEL_SEQUENCE_B[idxB];

        int idxC = (rnd >>> 6) & 7;
        int targetC = S3kSlotRomData.REEL_SEQUENCE_C[idxC];

        state.setOptionCycleTargetReelA(targetA);
        state.setOptionCycleTargetPackedBC((targetB << 4) | targetC);
    }

    private void tickDecelerate(S3kSlotStageState state, int frameCounter) {
        advanceCurrentReelWord(state);
        if (state.optionCycleCountdown() != 0) {
            return;
        }
        state.setOptionCycleReelVelocities(
                (state.optionCycleReelVelocities()[0] + 0x30) & 0xFF,
                (state.optionCycleReelVelocities()[1] + 0x30) & 0xFF,
                (state.optionCycleReelVelocities()[2] + 0x30) & 0xFF);
        state.setOptionCycleCountdown(0x0C + (frameCounter & 0x0F));
        state.setOptionCycleLockProgress(0);
        state.setOptionCycleState(STATE_LOCK_REELS);
    }

    private void tickLockReels(S3kSlotStageState state) {
        advanceCurrentReelWord(state);
        if (allReelsLocked(state)) {
            enterResolvedIdle(state);
            return;
        }
        processCurrentReelLock(state);
        state.setOptionCycleLockProgress(countLockedReels(state));
    }

    private void tickAward(S3kSlotStageState state) {
        enterResolvedIdle(state);
    }

    private void tickIdle(S3kSlotStageState state) {
        int timer = state.optionCycleResolvedDisplayTimer();
        if (timer > 0) {
            state.setOptionCycleResolvedDisplayTimer(timer - 1);
        }
    }

    private void advanceCurrentReelWord(S3kSlotStageState state) {
        int activeOffset = state.optionCycleActiveReelIndex() & 0x0C;
        int reelIndex = activeOffset >> 2;
        int reelWord = state.optionCycleReelWords()[reelIndex];
        int speed = signedByte(state.optionCycleReelVelocities()[reelIndex]);
        state.setOptionCycleReelWord(reelIndex, (reelWord - speed) & 0xFFFF);
        state.setOptionCycleSpinCycleCounter(state.optionCycleSpinCycleCounter() + 1);
        switch (activeOffset) {
            case 0 -> state.setOptionCycleActiveReelIndex(4);
            case 4 -> state.setOptionCycleActiveReelIndex(8);
            default -> {
                state.setOptionCycleActiveReelIndex(0);
                state.setOptionCycleCountdown((state.optionCycleCountdown() - 1) & 0xFF);
            }
        }
        syncDisplayFromReelWords(state);
    }

    private void processCurrentReelLock(S3kSlotStageState state) {
        int activeOffset = state.optionCycleActiveReelIndex() & 0x0C;
        int reelIndex = activeOffset >> 2;
        int reelSubstate = state.optionCycleReelSubstates()[reelIndex] & 0xFF;
        switch (reelSubstate) {
            case 0 -> processLockSearch(state, reelIndex);
            case 4 -> processLockDeceleration(state, reelIndex);
            case 8 -> processLockSettle(state, reelIndex);
            default -> {
            }
        }
    }

    private void processLockSearch(S3kSlotStageState state, int reelIndex) {
        if (reelIndex == 0) {
            if (signedByte(state.optionCycleCountdown()) >= 0) {
                return;
            }
        } else if ((state.optionCycleReelSubstates()[reelIndex - 1] & 0xFF) < 8) {
            return;
        }

        int targetSymbol = desiredTargetSymbol(state);
        int currentSymbol = sequenceForReelWordCentered(state.optionCycleReelWords()[reelIndex], sequenceForReel(reelIndex));
        if (currentSymbol != targetSymbol) {
            return;
        }
        state.setOptionCycleReelSubstate(reelIndex, 4);
        state.setOptionCycleReelVelocity(reelIndex, 0x60);
    }

    private void processLockDeceleration(S3kSlotStageState state, int reelIndex) {
        int targetSymbol = desiredTargetSymbol(state);
        int reelWord = state.optionCycleReelWords()[reelIndex];
        int[] reelVelocities = state.optionCycleReelVelocities();
        if (sequenceForReelWordApproach(reelWord, sequenceForReel(reelIndex)) != targetSymbol) {
            int speed = reelVelocities[reelIndex] & 0xFF;
            if (speed > 0x20) {
                speed = (speed - 0x0C) & 0xFF;
            }
            if (speed <= 0x18) {
                reelVelocities[reelIndex] = speed;
                return;
            }
            if ((reelWord & 0xFF) <= 0x80) {
                speed = (speed - 0x02) & 0xFF;
            }
            reelVelocities[reelIndex] = speed;
            return;
        }

        int adjustedWord = (reelWord + 0x80) & 0xFFFF;
        int snappedWord = (((adjustedWord & 0x0700) - 0x0010) & 0xFFFF);
        state.setOptionCycleReelWord(reelIndex, snappedWord);
        int landedSymbol = sequenceForReel(reelIndex)[(adjustedWord >>> 8) & 0x07] & 0xFF;
        writeResolvedTargetSymbol(state, landedSymbol);
        state.setOptionCycleReelVelocity(reelIndex, 0xF8);
        state.setOptionCycleReelSubstate(reelIndex, 8);
        syncDisplayFromReelWords(state);
    }

    private void processLockSettle(S3kSlotStageState state, int reelIndex) {
        if ((state.optionCycleReelWords()[reelIndex] & 0xFF) != 0) {
            return;
        }
        state.setOptionCycleReelVelocity(reelIndex, 0);
        state.setOptionCycleReelSubstate(reelIndex, 0x0C);
    }

    private void enterResolvedIdle(S3kSlotStageState state) {
        state.setOptionCycleReelVelocities(0, 0, 0);
        state.setOptionCycleReelSubstates(0, 0, 0);
        state.setOptionCycleState(STATE_IDLE);
        state.setOptionCycleCountdown(0);
        state.setOptionCycleActiveReelIndex(0);
        state.setOptionCycleLockProgress(3);
        state.setOptionCycleResolvedDisplayTimer(0x20);
        state.setOptionCycleCompletedCycles(state.optionCycleCompletedCycles() + 1);
        state.setOptionCycleLastPrize(S3kSlotPrizeCalculator.calculate(
                state.optionCycleTargetReelA(),
                (byte) state.optionCycleTargetPackedBC()));
        syncDisplayFromReelWords(state);
    }

    private boolean allReelsLocked(S3kSlotStageState state) {
        int[] substates = state.optionCycleReelSubstates();
        return (substates[0] & 0xFF) == 0x0C
                && (substates[1] & 0xFF) == 0x0C
                && (substates[2] & 0xFF) == 0x0C;
    }

    private int countLockedReels(S3kSlotStageState state) {
        int locked = 0;
        for (int substate : state.optionCycleReelSubstates()) {
            if ((substate & 0xFF) == 0x0C) {
                locked++;
            }
        }
        return locked;
    }

    private int desiredTargetSymbol(S3kSlotStageState state) {
        int targetWord = ((state.optionCycleTargetReelA() & 0xFF) << 8) | (state.optionCycleTargetPackedBC() & 0xFF);
        int shift = state.optionCycleActiveReelIndex() & 0x0F;
        if (shift != 0) {
            targetWord >>>= shift;
        }
        return targetWord & 0x07;
    }

    private void writeResolvedTargetSymbol(S3kSlotStageState state, int symbol) {
        int targetWord = ((state.optionCycleTargetReelA() & 0xFF) << 8) | (state.optionCycleTargetPackedBC() & 0xFF);
        int shiftedSymbol = symbol & 0x0F;
        int shift = state.optionCycleActiveReelIndex() & 0x0F;
        int mask = 0xFFF0;
        if (shift != 0) {
            shiftedSymbol <<= shift;
            mask = rotateLeft16(mask, shift);
        }
        targetWord = ((targetWord & mask) | shiftedSymbol) & 0x0777;
        state.setOptionCycleTargetReelA((targetWord >>> 8) & 0xFF);
        state.setOptionCycleTargetPackedBC(targetWord & 0xFF);
    }

    private static void syncDisplayFromReelWords(S3kSlotStageState state) {
        int[] reelWords = state.optionCycleReelWords();
        state.setOptionCycleDisplaySymbols(
                symbolForReelWord(reelWords[0], S3kSlotRomData.REEL_SEQUENCE_A),
                symbolForReelWord(reelWords[1], S3kSlotRomData.REEL_SEQUENCE_B),
                symbolForReelWord(reelWords[2], S3kSlotRomData.REEL_SEQUENCE_C));
        state.setOptionCycleOffsets(reelWords[0] & 0xFF, reelWords[1] & 0xFF, reelWords[2] & 0xFF);
    }

    private static int symbolForReelWord(int reelWord, byte[] sequence) {
        int phase = (reelWord >>> 8) & 0x07;
        return sequence[phase] & 0xFF;
    }

    private static int reelWordForDisplaySymbol(int symbol, int offset, byte[] sequence) {
        int phase = 0;
        for (int i = 0; i < sequence.length; i++) {
            if ((sequence[i] & 0xFF) == (symbol & 0x0F)) {
                phase = i;
                break;
            }
        }
        return ((phase & 0x07) << 8) | (offset & 0xF8);
    }

    private static int rotateRight8(int value, int shift) {
        int masked = value & 0xFF;
        return ((masked >>> shift) | (masked << (8 - shift))) & 0xFF;
    }

    private static int rotateRight16(int value, int shift) {
        int masked = value & 0xFFFF;
        return ((masked >>> shift) | (masked << (16 - shift))) & 0xFFFF;
    }

    private static int rotateLeft16(int value, int shift) {
        int masked = value & 0xFFFF;
        return ((masked << shift) | (masked >>> (16 - shift))) & 0xFFFF;
    }

    private static int signedByte(int value) {
        return (byte) (value & 0xFF);
    }

    private static byte[] sequenceForReel(int reelIndex) {
        return switch (reelIndex) {
            case 0 -> S3kSlotRomData.REEL_SEQUENCE_A;
            case 1 -> S3kSlotRomData.REEL_SEQUENCE_B;
            default -> S3kSlotRomData.REEL_SEQUENCE_C;
        };
    }

    private static int sequenceForReelWordCentered(int reelWord, byte[] sequence) {
        return sequence[(((reelWord - 0x00A0) >>> 8) & 0x07)] & 0xFF;
    }

    private static int sequenceForReelWordApproach(int reelWord, byte[] sequence) {
        return sequence[(((reelWord + 0x00F0) & 0x0700) >>> 8)] & 0xFF;
    }
}
