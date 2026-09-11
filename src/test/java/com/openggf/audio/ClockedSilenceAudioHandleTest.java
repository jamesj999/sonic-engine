package com.openggf.audio;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ClockedSilenceAudioHandleTest {
    @Test void sixtyFramesAt48000ProduceExactly48000SilentStereoFrames() {
        ClockedSilenceAudioHandle handle = new ClockedSilenceAudioHandle(48_000, 60);
        short[] target = new short[handle.maxStereoFramesPerPacket() * 2];
        Arrays.fill(target, (short) 17);
        long total = 0;
        for (int frame = 0; frame < 60; frame++) {
            int frames = handle.drainPresentationFrame(target);
            assertEquals(800, frames);
            assertAllZero(target, frames * 2);
            total += frames;
        }
        assertEquals(48_000, total);
        assertEquals(48_000, handle.totalStereoFrames());
    }

    @Test void fiftyNineFramesAt44100Use747And748AndTotal44100() {
        ClockedSilenceAudioHandle handle = new ClockedSilenceAudioHandle(44_100, 59);
        short[] target = new short[handle.maxStereoFramesPerPacket() * 2];
        long total = 0;
        boolean saw747 = false;
        boolean saw748 = false;
        for (int frame = 0; frame < 59; frame++) {
            int frames = handle.drainPresentationFrame(target);
            saw747 |= frames == 747;
            saw748 |= frames == 748;
            total += frames;
        }
        assertTrue(saw747);
        assertTrue(saw748);
        assertEquals(44_100, total);
    }

    @Test void atPhaseContinuesTheNextClockPacketWithoutReset() {
        ClockedSilenceAudioHandle uninterrupted = new ClockedSilenceAudioHandle(44_100, 59);
        short[] target = new short[1_600];
        for (int frame = 0; frame < 17; frame++) {
            uninterrupted.drainPresentationFrame(target);
        }
        ClockedSilenceAudioHandle resumed =
                ClockedSilenceAudioHandle.atPhase(uninterrupted.clockSnapshot());
        for (int frame = 17; frame < 60; frame++) {
            assertEquals(uninterrupted.drainPresentationFrame(target),
                    resumed.drainPresentationFrame(target), "packet " + frame);
        }
        assertEquals(uninterrupted.clockSnapshot(), resumed.clockSnapshot());
    }

    @Test void everyDrainClearsTheRequestedRange() {
        ClockedSilenceAudioHandle handle = new ClockedSilenceAudioHandle(5, 2);
        short[] target = {9, 9, 9, 9, 9, 9, 23, 23};
        assertEquals(2, handle.drainPresentationFrame(target));
        assertArrayEquals(new short[]{0, 0, 0, 0, 9, 9, 23, 23}, target);
        Arrays.fill(target, 0, 6, (short) 11);
        assertEquals(3, handle.drainPresentationFrame(target));
        assertArrayEquals(new short[]{0, 0, 0, 0, 0, 0, 23, 23}, target);
    }

    @Test void closeIsIdempotentAndFutureDrainsStaySilent() {
        ClockedSilenceAudioHandle handle = new ClockedSilenceAudioHandle(5, 2);
        handle.close();
        handle.close();
        short[] target = {7, 7, 7, 7, 7, 7};
        assertEquals(2, handle.drainPresentationFrame(target));
        assertArrayEquals(new short[]{0, 0, 0, 0, 7, 7}, target);
    }

    private static void assertAllZero(short[] target, int length) {
        for (int i = 0; i < length; i++) {
            assertEquals(0, target[i], "sample " + i);
        }
    }
}
