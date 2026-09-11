package com.openggf.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestDisplayWindowPolicy {

    @Test
    void autosizeWidescreenDerivesWindowFromPixelWidth() {
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.WIDE_16_9, true, 640, 448);
        assertEquals(400, r.pixelWidth());
        assertEquals(800, r.windowWidth());
        assertEquals(448, r.windowHeight());
    }

    @Test
    void autosizeNativeIsUnchangedFromToday() {
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.NATIVE_4_3, true, 640, 448);
        assertEquals(320, r.pixelWidth());
        assertEquals(640, r.windowWidth());
        assertEquals(448, r.windowHeight());
    }

    @Test
    void autosizeAtNativePreservesCustomWindow() {
        // A user with a custom 1280x768 window at NATIVE_4_3 must NOT be stomped.
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.NATIVE_4_3, true, 1280, 768);
        assertEquals(320, r.pixelWidth());
        assertEquals(1280, r.windowWidth());
        assertEquals(768, r.windowHeight());
    }

    @Test
    void autosizeOffPreservesProvidedWindowButStillSetsPixelWidth() {
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.WIDE_16_9, false, 1024, 768);
        assertEquals(400, r.pixelWidth());
        assertEquals(1024, r.windowWidth());
        assertEquals(768, r.windowHeight());
    }

    @Test
    void autosizeWidescreenIsDataDrivenAcrossPresets() {
        DisplayWindowPolicy.Resolved r =
                DisplayWindowPolicy.resolve(WidescreenAspect.ULTRA_21_9, true, 640, 448);
        assertEquals(528, r.pixelWidth());
        assertEquals(1056, r.windowWidth());
        assertEquals(448, r.windowHeight());
    }
}
