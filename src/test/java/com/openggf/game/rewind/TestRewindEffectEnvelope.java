package com.openggf.game.rewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class TestRewindEffectEnvelope {

    @Test
    public void attackReachesFullIntensityInFourFrames() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        for (int i = 0; i < 3; i++) {
            envelope.frameActive(1.0);
            assertTrue(envelope.intensity() < 1.0f, "should still be ramping at frame " + i);
        }
        envelope.frameActive(1.0);
        assertEquals(1.0f, envelope.intensity(), 1e-6f);
        envelope.frameActive(1.0);
        assertEquals(1.0f, envelope.intensity(), 1e-6f, "must clamp at 1.0");
    }

    @Test
    public void releaseReachesZeroInTenFrames() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        for (int i = 0; i < 4; i++) {
            envelope.frameActive(1.0);
        }
        for (int i = 0; i < 9; i++) {
            envelope.frameInactive();
            assertTrue(envelope.intensity() > 0.0f, "should still be releasing at frame " + i);
        }
        envelope.frameInactive();
        assertEquals(0.0f, envelope.intensity(), 1e-6f);
        envelope.frameInactive();
        assertEquals(0.0f, envelope.intensity(), 1e-6f, "must clamp at 0.0");
    }

    @Test
    public void defaultSpeedIsOne() {
        assertEquals(1.0f, new RewindEffectEnvelope().speed(), 1e-6f);
    }

    @Test
    public void latchedSpeedHeldThroughRelease() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        envelope.frameActive(2.5);
        for (int i = 0; i < 5; i++) {
            envelope.frameInactive();
        }
        assertEquals(2.5f, envelope.speed(), 1e-6f,
                "release tail must keep tape motion at the last held speed");
    }

    @Test
    public void zeroSpeedDuringActiveFrameDoesNotClearLatch() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        envelope.frameActive(2.0);
        envelope.frameActive(0.0);
        assertEquals(2.0f, envelope.speed(), 1e-6f);
    }

    @Test
    public void speedIsClampedToTapeRange() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        envelope.frameActive(9.0);
        assertEquals(4.0f, envelope.speed(), 1e-6f);
        envelope.frameActive(0.01);
        assertEquals(0.25f, envelope.speed(), 1e-6f);
    }

    @Test
    public void resetZeroesIntensityAndRestoresDefaultSpeed() {
        RewindEffectEnvelope envelope = new RewindEffectEnvelope();
        envelope.frameActive(3.0);
        envelope.frameActive(3.0);
        envelope.reset();
        assertEquals(0.0f, envelope.intensity(), 1e-6f);
        assertEquals(1.0f, envelope.speed(), 1e-6f);
    }
}
