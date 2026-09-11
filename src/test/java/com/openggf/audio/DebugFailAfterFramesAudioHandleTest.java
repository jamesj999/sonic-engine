package com.openggf.audio;

import com.openggf.audio.runtime.AudioFrameClock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DebugFailAfterFramesAudioHandleTest {
    @Test void absentAndMinusOneReturnTheOriginalHandle() {
        LiveCaptureAudioHandle handle = new ClockedSilenceAudioHandle(48_000, 60);
        assertSame(handle, DebugFailAfterFramesAudioHandle.maybeWrap(handle, -1));
    }

    @Test void zeroFailsBeforeTheFirstDrainWithoutAdvancingPhase() {
        LiveCaptureAudioHandle delegate = new ClockedSilenceAudioHandle(48_000, 60);
        LiveCaptureAudioHandle wrapped = DebugFailAfterFramesAudioHandle.maybeWrap(delegate, 0);
        AudioFrameClock.Snapshot before = wrapped.clockSnapshot();
        assertThrows(IllegalStateException.class,
                () -> wrapped.drainPresentationFrame(new short[1_600]));
        assertEquals(before, wrapped.clockSnapshot());
    }

    @Test void threeAllowsExactlyThreeDrainsThenFailsBeforeTheFourth() {
        LiveCaptureAudioHandle wrapped = DebugFailAfterFramesAudioHandle.maybeWrap(
                new ClockedSilenceAudioHandle(48_000, 60), 3);
        short[] target = new short[1_600];
        for (int i = 0; i < 3; i++) {
            assertEquals(800, wrapped.drainPresentationFrame(target));
        }
        AudioFrameClock.Snapshot before = wrapped.clockSnapshot();
        assertThrows(IllegalStateException.class, () -> wrapped.drainPresentationFrame(target));
        assertEquals(before, wrapped.clockSnapshot());
        assertEquals(2_400, wrapped.totalStereoFrames());
    }

    @Test void wrapperDelegatesMetadataPhaseTotalAndClose() {
        TrackingHandle delegate = new TrackingHandle();
        LiveCaptureAudioHandle wrapped = DebugFailAfterFramesAudioHandle.maybeWrap(delegate, 5);
        assertEquals(delegate.sampleRate(), wrapped.sampleRate());
        assertEquals(delegate.frameRate(), wrapped.frameRate());
        assertEquals(delegate.maxStereoFramesPerPacket(), wrapped.maxStereoFramesPerPacket());
        wrapped.drainPresentationFrame(new short[1_600]);
        assertEquals(delegate.clockSnapshot(), wrapped.clockSnapshot());
        assertEquals(delegate.totalStereoFrames(), wrapped.totalStereoFrames());
        wrapped.close();
        wrapped.close();
        assertEquals(2, delegate.closeCalls);
    }

    private static final class TrackingHandle extends ClockedSilenceAudioHandle {
        private int closeCalls;

        private TrackingHandle() {
            super(48_000, 60);
        }

        @Override public void close() {
            closeCalls++;
            super.close();
        }
    }
}
