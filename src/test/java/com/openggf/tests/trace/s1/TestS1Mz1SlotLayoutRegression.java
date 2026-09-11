package com.openggf.tests.trace.s1;

import com.openggf.tests.rules.RequiresRom;
import com.openggf.tests.rules.SonicGame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

@RequiresRom(SonicGame.SONIC_1)
class TestS1Mz1SlotLayoutRegression {

    private final S1Mz1SlotLayoutHarness harness = new S1Mz1SlotLayoutHarness();

    @Test
    void canonicalBootstrapIncludesNativeS1ObjectPrelude() throws Exception {
        assertDoesNotThrow(() -> harness.canonicalBootstrapIncludesNativeS1ObjectPrelude());
    }

    @Test
    void lowSlotLayoutStillMatchesRecordedRomAtHurtFrame() throws Exception {
        assertDoesNotThrow(() -> harness.lowSlotLayoutStillMatchesRecordedRomAtHurtFrame());
    }

    @Test
    void badnikAnimalStillTransitionsToRecordedRoutineAtLandingFrame() throws Exception {
        assertDoesNotThrow(() -> harness.badnikAnimalStillTransitionsToRecordedRoutineAtLandingFrame());
    }

    @Test
    void lavaBallMakerSpawnsSlot33BallAtFrame1204() throws Exception {
        assertDoesNotThrow(() -> harness.lavaBallMakerSpawnsSlot33BallAtFrame1204());
    }

    @Test
    void ringPairNear0798AppearsAtRecordedFrames() throws Exception {
        assertDoesNotThrow(() -> harness.ringPairNear0798AppearsAtRecordedFrames());
    }

    @Test
    void buttonAt0ad0FirstAppearsAtRecordedFrame() throws Exception {
        assertDoesNotThrow(() -> harness.buttonAt0ad0FirstAppearsAtRecordedFrame());
    }

    @Test
    void buttonAt0ad0UnloadsAtRecordedRemovalFrame() throws Exception {
        assertDoesNotThrow(() -> harness.buttonAt0ad0UnloadsAtRecordedRemovalFrame());
    }

    @Test
    void buttonAt0ad0ReappearsAtRecordedFrameAndSlot() throws Exception {
        assertDoesNotThrow(() -> harness.buttonAt0ad0ReappearsAtRecordedFrameAndSlot());
    }

    @Test
    void chainedStomperAt10c0UnloadsByRecordedFrame() throws Exception {
        assertDoesNotThrow(() -> harness.chainedStomperAt10c0UnloadsByRecordedFrame());
    }

    @Test
    void lavaTagAt0d80AppearsAtRecordedFrameAndSlot() throws Exception {
        assertDoesNotThrow(() -> harness.lavaTagAt0d80AppearsAtRecordedFrameAndSlot());
    }

    @Test
    void burningGrassWalkerAt0bc1AppearsAtRecordedFrameAndSlot() throws Exception {
        assertDoesNotThrow(() -> harness.burningGrassWalkerAt0bc1AppearsAtRecordedFrameAndSlot());
    }

    @Test
    void mzBrickAt0e30FirstAppearsAtRecordedFrameAndSlot() throws Exception {
        assertDoesNotThrow(() -> harness.mzBrickAt0e30FirstAppearsAtRecordedFrameAndSlot());
    }

    @Test
    void caterkillerBodySegmentsStillOccupySlotsDuringDeleteRoutineFrame() throws Exception {
        assertDoesNotThrow(() -> harness.caterkillerBodySegmentsStillOccupySlotsDuringDeleteRoutineFrame());
    }

    @Test
    void slotSuffixStillMatchesMissileAnimalPointsSequenceDuringDeleteRoutineFrame() throws Exception {
        assertDoesNotThrow(() -> harness.slotSuffixStillMatchesMissileAnimalPointsSequenceDuringDeleteRoutineFrame());
    }
}
