package com.openggf.game.sonic3k.bonusstage.slots;

import com.openggf.game.GameRng;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TestS3kSlotOptionCycleSystem {

    @Test
    void bootstrapSeedsRomShapedOptionCycleState() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Object system = systemClass.getConstructor().newInstance();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        systemClass.getMethod("bootstrap", S3kSlotStageState.class).invoke(system, state);

        assertEquals(0, readIntField(state, "optionCycleState"));
        assertEquals(0, readIntField(state, "optionCycleCountdown"));
        assertEquals(0, readIntField(state, "optionCycleTargetReelA"));
        assertEquals(0, readIntField(state, "optionCycleTargetPackedBC"));
        assertEquals(Integer.MIN_VALUE, readIntField(state, "optionCycleLastPrize"));
        assertEquals(0, readIntField(state, "optionCycleCompletedCycles"));
        int[] displaySymbols = (int[]) readField(state, "optionCycleDisplaySymbols");
        assertNotNull(displaySymbols);
        assertEquals(3, displaySymbols.length);
        assertEquals(0, displaySymbols[0]);
        assertEquals(0, displaySymbols[1]);
        assertEquals(0, displaySymbols[2]);
    }

    @Test
    void sourceOffsetMatchesSub_4C77C() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Method sourceOffsetFor = systemClass.getMethod("sourceOffsetFor", int.class, int.class);

        assertEquals(0x0000, sourceOffsetFor.invoke(null, 0, 0x0000));
        assertEquals(0x0240, sourceOffsetFor.invoke(null, 1, 0x0080));
        assertEquals(0x0C7C, sourceOffsetFor.invoke(null, 6, 0x00F8));
    }

    @Test
    void firstTickTransitionsIntoPassiveSettleState() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Object system = systemClass.getConstructor().newInstance();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        systemClass.getMethod("bootstrap", S3kSlotStageState.class).invoke(system, state);
        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 0);

        assertEquals(4, readIntField(state, "optionCycleState"));
        assertEquals(1, readIntField(state, "optionCycleCountdown"));
        assertEquals(0, readIntField(state, "optionCycleActiveReelIndex"));
    }

    @Test
    void firstTickSeedsPassiveDisplayWithValidReelFaces() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Object system = systemClass.getConstructor().newInstance();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        systemClass.getMethod("bootstrap", S3kSlotStageState.class).invoke(system, state);
        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 0);

        S3kSlotMachineDisplayState displayState = S3kSlotMachineDisplayState.fromState(state, 0x460, 0x430);

        assertEquals(3, displayState.faces().length);
        assertTrue(displayState.faces()[0] >= 0 && displayState.faces()[0] <= 6);
        assertTrue(displayState.faces()[1] >= 0 && displayState.faces()[1] <= 6);
        assertTrue(displayState.faces()[2] >= 0 && displayState.faces()[2] <= 6);
        assertTrue(displayState.offsets()[0] > 0f);
        assertTrue(displayState.offsets()[1] > 0f);
        assertTrue(displayState.offsets()[2] > 0f);
    }

    @Test
    void passiveSettleRequiresThreeReelAdvancesBeforeIdle() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Object system = systemClass.getConstructor().newInstance();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        systemClass.getMethod("bootstrap", S3kSlotStageState.class).invoke(system, state);
        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 0);
        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 1);
        assertEquals(4, readIntField(state, "optionCycleState"));
        assertEquals(1, readIntField(state, "optionCycleCountdown"));

        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 2);
        assertEquals(4, readIntField(state, "optionCycleState"));
        assertEquals(1, readIntField(state, "optionCycleCountdown"));

        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 3);
        assertEquals(24, readIntField(state, "optionCycleState"));
        assertEquals(0, readIntField(state, "optionCycleCountdown"));
    }

    @Test
    void idleStateDoesNotRestartCycleWithoutCapture() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Object system = systemClass.getConstructor().newInstance();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        systemClass.getMethod("bootstrap", S3kSlotStageState.class).invoke(system, state);
        state.setOptionCycleState(24);
        state.setOptionCycleResolvedDisplayTimer(0);
        state.setOptionCycleDisplaySymbols(1, 2, 3);
        state.setOptionCycleOffsets(0, 0, 0);

        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 120);

        assertEquals(24, readIntField(state, "optionCycleState"));
        int[] displaySymbols = (int[]) readField(state, "optionCycleDisplaySymbols");
        assertEquals(1, displaySymbols[0]);
        assertEquals(2, displaySymbols[1]);
        assertEquals(3, displaySymbols[2]);
    }

    @Test
    void captureSpinLifecycleDoesNotCollapseToAwardInOnlyAFewFrames() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Object system = systemClass.getConstructor().newInstance();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        systemClass.getMethod("bootstrap", S3kSlotStageState.class).invoke(system, state);
        state.setOptionCycleState(0x08);

        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 100);
        assertEquals(0x0C, readIntField(state, "optionCycleState"));

        for (int i = 0; i < 5; i++) {
            systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 101 + i);
        }
        assertEquals(0x0C, readIntField(state, "optionCycleState"));

        systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 106);
        assertEquals(0x10, readIntField(state, "optionCycleState"));

        for (int i = 0; i < 18; i++) {
            systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 107 + i);
        }

        assertEquals(0x10, readIntField(state, "optionCycleState"));
        assertNotEquals(3, readIntField(state, "optionCycleLockProgress"));
    }

    @Test
    void resolvedCycleKeepsReelSubstatesAndVelocitiesCleared() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Object system = systemClass.getConstructor().newInstance();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        systemClass.getMethod("bootstrap", S3kSlotStageState.class).invoke(system, state);
        for (int frame = 0; frame < 4; frame++) {
            systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, frame);
        }

        int[] reelVelocities = (int[]) readField(state, "optionCycleReelVelocities");
        int[] reelSubstates = (int[]) readField(state, "optionCycleReelSubstates");
        assertEquals(0, reelVelocities[0]);
        assertEquals(0, reelVelocities[1]);
        assertEquals(0, reelVelocities[2]);
        assertEquals(0, reelSubstates[0]);
        assertEquals(0, reelSubstates[1]);
        assertEquals(0, reelSubstates[2]);
    }

    @Test
    void captureSpinDoesNotEnterAwardImmediatelyAfterLockStageStarts() throws Exception {
        Class<?> systemClass = Class.forName(
                "com.openggf.game.sonic3k.bonusstage.slots.S3kSlotOptionCycleSystem");
        Object system = systemClass.getConstructor().newInstance();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();

        systemClass.getMethod("bootstrap", S3kSlotStageState.class).invoke(system, state);
        state.setOptionCycleState(0x08);

        for (int i = 0; i < 7; i++) {
            systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 200 + i);
        }

        assertEquals(0x10, readIntField(state, "optionCycleState"));

        for (int i = 0; i < 9; i++) {
            systemClass.getMethod("tick", S3kSlotStageState.class, int.class).invoke(system, state, 207 + i);
        }
        assertEquals(0x10, readIntField(state, "optionCycleState"));
    }

    @Test
    void randomTargetSelectionUsesS3kRandomNumberAndFrameCounter() {
        S3kSlotOptionCycleSystem system = new S3kSlotOptionCycleSystem();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        GameRng rng = new GameRng(GameRng.Flavour.S3K);

        state.setOptionCycleState(0x08);
        system.tick(state, 0x00FF, rng);

        assertEquals(0x0C, state.optionCycleState());
        assertEquals(0, state.optionCycleTargetReelA());
        assertEquals(0x20, state.optionCycleTargetPackedBC());
    }

    @Test
    void resolvedPrizeMatchesFinalDisplayedSymbols() {
        S3kSlotOptionCycleSystem system = new S3kSlotOptionCycleSystem();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        GameRng rng = new GameRng(GameRng.Flavour.S3K);

        state.setOptionCycleState(0x08);
        for (int frame = 0; frame < 400 && state.optionCycleState() != 0x18; frame++) {
            system.tick(state, 0x0100 + frame, rng);
        }

        int[] displaySymbols = S3kSlotMachineDisplayState.fromState(state, 0, 0).faces();
        int displayedPrize = S3kSlotPrizeCalculator.calculate(displaySymbols[2],
                (byte) ((displaySymbols[1] << 4) | displaySymbols[0]));

        assertEquals(displayedPrize, state.optionCycleLastPrize());
    }

    @Test
    void settledJackpotSonicRingRemainsDisplayedAndAwardsNothing() {
        S3kSlotOptionCycleSystem system = new S3kSlotOptionCycleSystem();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        GameRng rng = new GameRng(GameRng.Flavour.S3K);

        state.setOptionCycleState(0x08);
        for (int frame = 0; frame < 600 && state.optionCycleState() != 0x18; frame++) {
            system.tick(state, 162 + frame, rng);
        }

        S3kSlotMachineDisplayState displayState = S3kSlotMachineDisplayState.fromState(state, 0, 0);

        assertEquals(0, state.optionCycleLastPrize());
        assertEquals(5, state.optionCycleTargetReelA());
        assertEquals(0x10, state.optionCycleTargetPackedBC());
        assertArrayEquals(new int[] {0, 1, 5}, displayState.faces());
        assertArrayEquals(new float[] {0f, 0f, 0f}, displayState.offsets());
    }

    @Test
    void displayDoesNotJumpWhenLockedReelsEnterResolvedIdle() {
        S3kSlotOptionCycleSystem system = new S3kSlotOptionCycleSystem();
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        GameRng rng = new GameRng(GameRng.Flavour.S3K);

        state.setOptionCycleState(0x08);
        for (int frame = 0; frame < 600 && state.optionCycleState() != 0x18; frame++) {
            system.tick(state, 162 + frame, rng);
            if (state.optionCycleState() == 0x10
                    && state.optionCycleReelSubstates()[0] == 0x0C
                    && state.optionCycleReelSubstates()[1] == 0x0C
                    && state.optionCycleReelSubstates()[2] == 0x0C) {
                break;
            }
        }

        int[] lastLockedFaces = S3kSlotMachineDisplayState.fromState(state, 0, 0).faces();
        system.tick(state, 0x400, rng);
        int[] resolvedFaces = S3kSlotMachineDisplayState.fromState(state, 0, 0).faces();

        assertEquals(0x18, state.optionCycleState());
        assertArrayEquals(lastLockedFaces, resolvedFaces);
    }

    // ROM sonic3k.asm:99684-99686 (loc_4C480): move.b (V_int_run_count+3).w,d0 /
    // rol.b #4,d0 / andi.b #7,d0 -- an 8-bit ROTATE of the low byte. Commit
    // 683c84993 fixed reel1Offset from Integer.rotateLeft (a 32-bit rotate
    // that never wraps a set low bit back into bits 0-2 after a left-shift
    // of 4, so the andi.b #7,d0 mask always read 0 and reel1Offset was a
    // frame-invariant 0x2C) to an 8-bit rotateRight8 helper that reproduces
    // rol.b #4,d0 exactly. Pin both claims here so a regression to the wide
    // rotate is caught even if it doesn't move the trace frontier.
    @Test
    void pickTargetsReel1OffsetVariesWithFrameCounterInsteadOfBeingFrameInvariant() {
        S3kSlotOptionCycleSystem system = new S3kSlotOptionCycleSystem();
        GameRng rng = new GameRng(GameRng.Flavour.S3K);

        S3kSlotStageState frame0 = S3kSlotStageState.bootstrap();
        frame0.setOptionCycleState(0x08);
        system.tick(frame0, 0x0000, rng);
        assertEquals(0x2C, frame0.optionCycleReelVelocities()[1],
                "frameCounter=0x0000 is the one input where the old and new formulas coincide");

        S3kSlotStageState frameFF = S3kSlotStageState.bootstrap();
        frameFF.setOptionCycleState(0x08);
        system.tick(frameFF, 0x00FF, rng);
        assertEquals(0x33, frameFF.optionCycleReelVelocities()[1],
                "rotateRight8(0xFF, 4) & 7 == 7, giving reel1Offset (7-4+0x30)&0xFF == 0x33");
        assertNotEquals(0x2C, frameFF.optionCycleReelVelocities()[1],
                "the pre-fix Integer.rotateLeft path always produced the frame-invariant 0x2C here");
    }

    private static Object readField(S3kSlotStageState state, String fieldName) throws Exception {
        Field field = S3kSlotStageState.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(state);
    }

    private static int readIntField(S3kSlotStageState state, String fieldName) throws Exception {
        return (int) readField(state, fieldName);
    }
}


