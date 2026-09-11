package com.openggf.game.rewind;

import com.openggf.debug.playback.Bk2FrameInput;
import com.openggf.trace.TraceData;
import com.openggf.trace.TraceFrame;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

class TestTraceInputSource {

    @Test
    void readsFrameCountFromTrace() {
        TraceData trace = Mockito.mock(TraceData.class);
        Mockito.when(trace.frameCount()).thenReturn(2);
        TraceInputSource src = new TraceInputSource(trace);
        assertEquals(2, src.frameCount());
    }

    @Test
    void convertsTraceFrameToInputMask() {
        TraceData trace = Mockito.mock(TraceData.class);
        Mockito.when(trace.frameCount()).thenReturn(2);
        TraceFrame frame0 = TraceFrame.of(0, 0x05, (short) 0, (short) 0, (short) 0,
                (short) 0, (short) 0, (byte) 0, false, false, 0);
        TraceFrame frame1 = TraceFrame.of(1, 0x04, (short) 0, (short) 0, (short) 0,
                (short) 0, (short) 0, (byte) 0, false, false, 0);
        Mockito.when(trace.getFrame(0)).thenReturn(frame0);
        Mockito.when(trace.getFrame(1)).thenReturn(frame1);

        TraceInputSource src = new TraceInputSource(trace);

        Bk2FrameInput read0 = src.read(0);
        assertEquals(0, read0.frameIndex());
        assertEquals(0x05, read0.p1InputMask());
        assertEquals(0, read0.p1ActionMask());
        assertFalse(read0.p1StartPressed());

        Bk2FrameInput read1 = src.read(1);
        assertEquals(1, read1.frameIndex());
        assertEquals(0x04, read1.p1InputMask());
    }

    @Test
    void rejectsNullTrace() {
        assertThrows(NullPointerException.class, () -> new TraceInputSource(null));
    }
}
