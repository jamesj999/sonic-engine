package com.openggf.trace.live;

import com.openggf.trace.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MismatchRingBufferTest {

    @Test
    void pushFifoEvictsOldest() {
        MismatchRingBuffer b = new MismatchRingBuffer(3);
        for (int i = 0; i < 5; i++) {
            b.push(new MismatchEntry(i, "x" + i, "a", "b", "1", Severity.ERROR, 1));
        }
        List<MismatchEntry> recent = b.recent();
        assertEquals(3, recent.size());
        assertEquals(4, recent.get(0).frame()); // newest first
        assertEquals(2, recent.get(2).frame());
    }

    @Test
    void duplicateHeadIncrementsRepeat() {
        MismatchRingBuffer b = new MismatchRingBuffer(3);
        b.push(new MismatchEntry(10, "x", "a", "b", "1", Severity.ERROR, 1));
        b.push(new MismatchEntry(11, "x", "a", "b", "1", Severity.ERROR, 1));
        b.push(new MismatchEntry(12, "x", "a", "b", "1", Severity.ERROR, 1));
        assertEquals(1, b.recent().size());
        assertEquals(3, b.recent().get(0).repeatCount());
    }

    @Test
    void differentFieldFlushesRepeatCounter() {
        MismatchRingBuffer b = new MismatchRingBuffer(3);
        b.push(new MismatchEntry(10, "x", "a", "b", "1", Severity.ERROR, 1));
        b.push(new MismatchEntry(11, "x", "a", "b", "1", Severity.ERROR, 1));
        b.push(new MismatchEntry(12, "y", "c", "d", "1", Severity.WARNING, 1));
        b.push(new MismatchEntry(13, "x", "a", "b", "1", Severity.ERROR, 1));
        assertEquals(3, b.recent().size());
        assertEquals(1, b.recent().get(0).repeatCount());
    }
}
