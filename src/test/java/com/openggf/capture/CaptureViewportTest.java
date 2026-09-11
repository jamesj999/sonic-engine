package com.openggf.capture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaptureViewportTest {

    @Test
    void rejectsNonPositiveDimensions() {
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureViewport(0, 0, 0, 224));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureViewport(0, 0, -1, 224));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureViewport(0, 0, 320, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new CaptureViewport(0, 0, 320, -1));
    }

    @Test
    void reportsRgbaByteSize() {
        assertEquals(286_720, new CaptureViewport(0, 0, 320, 224).rgbaByteSize());
    }

    @Test
    void reportsOverflowClearly() {
        ArithmeticException error = assertThrows(ArithmeticException.class,
                () -> new CaptureViewport(0, 0, Integer.MAX_VALUE, 2).rgbaByteSize());
        assertTrue(error.getMessage().toLowerCase().contains("overflow"));
    }
}
