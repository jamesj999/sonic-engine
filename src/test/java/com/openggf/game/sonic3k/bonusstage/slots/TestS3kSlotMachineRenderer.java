package com.openggf.game.sonic3k.bonusstage.slots;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TestS3kSlotMachineRenderer {

    @Test
    void displayStateUsesCurrentReelFacesAndOffsets() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        S3kSlotOptionCycleSystem cycleSystem = new S3kSlotOptionCycleSystem();
        cycleSystem.bootstrap(state);
        state.setOptionCycleDisplaySymbols(3, 1, 5);
        state.setOptionCycleOffsets(0x20, 0x40, 0x60);

        S3kSlotMachineDisplayState displayState = S3kSlotMachineDisplayState.fromState(state, 0x460, 0x430);

        assertEquals(0x460, displayState.worldX());
        assertEquals(0x430, displayState.worldY());
        assertArrayEquals(new int[] {3, 1, 5}, displayState.faces());
        assertArrayEquals(new float[] {0x20 / 256f, 0x40 / 256f, 0x60 / 256f}, displayState.offsets());
        assertArrayEquals(new int[] {0, 2, 4}, displayState.nextFaces());
    }

    @Test
    void reelWordDisplayOrderMatchesPhysicalLeftToRightSequenceTables() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        state.setOptionCycleReelWords(
                (6 << 8) | 0x20,
                (6 << 8) | 0x40,
                (6 << 8) | 0x60);

        S3kSlotMachineDisplayState displayState = S3kSlotMachineDisplayState.fromState(state, 0x460, 0x430);

        assertArrayEquals(new int[] {
                S3kSlotRomData.REEL_SEQUENCE_A[6] & 0xFF,
                S3kSlotRomData.REEL_SEQUENCE_B[6] & 0xFF,
                S3kSlotRomData.REEL_SEQUENCE_C[6] & 0xFF
        }, displayState.faces());
        assertArrayEquals(new int[] {
                S3kSlotRomData.REEL_SEQUENCE_A[7] & 0xFF,
                S3kSlotRomData.REEL_SEQUENCE_B[7] & 0xFF,
                S3kSlotRomData.REEL_SEQUENCE_C[7] & 0xFF
        }, displayState.nextFaces());
        assertArrayEquals(new float[] {0x20 / 256f, 0x40 / 256f, 0x60 / 256f}, displayState.offsets());
    }

    @Test
    void displayedResolvedFacesMatchPrizeWordOrdering() {
        S3kSlotStageState state = S3kSlotStageState.bootstrap();
        state.setOptionCycleTargetReelA(3);
        state.setOptionCycleTargetPackedBC(0x15);
        state.setOptionCycleReelWords(
                (6 << 8) | 0x20,
                (6 << 8) | 0x40,
                (6 << 8) | 0x60);

        S3kSlotMachineDisplayState displayState = S3kSlotMachineDisplayState.fromState(state, 0x460, 0x430);

        assertArrayEquals(new int[] {5, 1, 3}, displayState.faces());
        assertEquals(S3kSlotPrizeCalculator.calculate(3, (byte) 0x15),
                S3kSlotPrizeCalculator.calculate(displayState.faces()[2],
                        (byte) ((displayState.faces()[1] << 4) | displayState.faces()[0])));
    }

    @Test
    void displayScreenAnchorUsesSlotScreenOriginInsteadOfHiddenCageWorldAnchor() {
        int cameraX = S3kSlotRomData.SLOT_BONUS_PLAYER_START_X - 0xA0;
        int cameraY = S3kSlotRomData.SLOT_BONUS_PLAYER_START_Y - 0x70;
        int eventsBgX = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_X;
        int eventsBgY = S3kSlotRomData.SLOT_BONUS_CAGE_CENTER_Y;

        assertEquals(0x30,
                S3kSlotMachineRenderer.computeDisplayScreenX(eventsBgX, cameraX));
        assertEquals(0x108,
                S3kSlotMachineRenderer.computeDisplayScreenY(eventsBgY, cameraY));
    }

    @Test
    void reelDisplayUsesSlotPanelPaletteLine() {
        assertEquals(0.0f, S3kSlotMachineRenderer.paletteLineForTest());
    }
}


