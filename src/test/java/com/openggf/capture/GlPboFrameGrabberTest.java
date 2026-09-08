package com.openggf.capture;

import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class GlPboFrameGrabberTest {
    private static class Backend implements GlPboFrameGrabber.Backend {
        int created, reads, deleted;
        boolean failCopy;
        final Map<Integer, Byte> data = new HashMap<>();
        public int create(int bytes) { assertEquals(4, bytes); return ++created; }
        public void read(int buffer, CaptureViewport viewport) { data.put(buffer, (byte) ++reads); }
        public void copy(int buffer, byte[] target) {
            if (failCopy) throw new IllegalStateException("mapping failed");
            java.util.Arrays.fill(target, data.get(buffer));
        }
        public void delete(int buffer) { deleted++; data.remove(buffer); }
    }

    @Test void ringRetainsOrderAndLastPendingFrame() {
        Backend backend = new Backend();
        try (GlPboFrameGrabber grabber = new GlPboFrameGrabber(new CaptureViewport(0, 0, 1, 1), backend)) {
            byte[] pixels = new byte[4];
            grabber.beginReadback();
            for (int i = 1; i < 20; i++) {
                grabber.beginReadback();
                grabber.grabInto(pixels);
                assertEquals(i, pixels[0]);
            }
            grabber.grabInto(pixels); // stop flush
            assertEquals(20, pixels[0]);
            assertThrows(IllegalStateException.class, () -> grabber.grabInto(pixels));
            assertEquals(2, backend.created);
        }
        assertEquals(2, backend.deleted);
    }

    @Test void failedMappingAndFullRingDoNotLeakBuffers() {
        Backend backend = new Backend();
        GlPboFrameGrabber grabber = new GlPboFrameGrabber(new CaptureViewport(0, 0, 1, 1), backend);
        grabber.beginReadback();
        grabber.beginReadback();
        assertThrows(IllegalStateException.class, grabber::beginReadback);
        backend.failCopy = true;
        assertThrows(IllegalStateException.class, () -> grabber.grabInto(new byte[4]));
        grabber.close();
        grabber.close();
        assertEquals(2, backend.deleted);
    }
}
