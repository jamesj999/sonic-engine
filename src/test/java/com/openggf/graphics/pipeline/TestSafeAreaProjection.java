package com.openggf.graphics.pipeline;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestSafeAreaProjection {

    @Test
    void padIsZeroAtNative() {
        assertEquals(0, SafeAreaProjection.pad(320));
    }

    @Test
    void padCentersNative320InWiderViewport() {
        assertEquals(40, SafeAreaProjection.pad(400));
        assertEquals(104, SafeAreaProjection.pad(528));
        assertEquals(240, SafeAreaProjection.pad(800));
    }

    @Test
    void orthoLeftRightFrameTheCenteredRegion() {
        assertEquals(-40f, SafeAreaProjection.orthoLeft(400), 0.001f);
        assertEquals(360f, SafeAreaProjection.orthoRight(400), 0.001f);
        assertEquals(0f, SafeAreaProjection.orthoLeft(320), 0.001f);
        assertEquals(320f, SafeAreaProjection.orthoRight(320), 0.001f);
    }
}
