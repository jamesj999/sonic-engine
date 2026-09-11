package com.openggf.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TestWidescreenAspect {

    @Test
    void presetWidthsAreCorrectAndHeight224Fixed() {
        assertEquals(320, WidescreenAspect.NATIVE_4_3.pixelWidth());
        assertEquals(352, WidescreenAspect.WIDE_16_10.pixelWidth());
        assertEquals(400, WidescreenAspect.WIDE_16_9.pixelWidth());
        assertEquals(528, WidescreenAspect.ULTRA_21_9.pixelWidth());
        assertEquals(800, WidescreenAspect.SUPER_32_9.pixelWidth());
    }

    @Test
    void everyPresetWidthIsMultipleOf16() {
        for (WidescreenAspect a : WidescreenAspect.values()) {
            assertEquals(0, a.pixelWidth() % 16, a.name() + " width must be a multiple of 16");
        }
    }

    @Test
    void parseIsCaseInsensitiveAndFallsBackToNative() {
        assertEquals(WidescreenAspect.WIDE_16_9, WidescreenAspect.parse("wide_16_9"));
        assertEquals(WidescreenAspect.WIDE_16_9, WidescreenAspect.parse("  WIDE_16_9 "));
        assertEquals(WidescreenAspect.NATIVE_4_3, WidescreenAspect.parse(null));
        assertEquals(WidescreenAspect.NATIVE_4_3, WidescreenAspect.parse("garbage"));
        assertEquals(WidescreenAspect.NATIVE_4_3, WidescreenAspect.parse(""));
        assertEquals(WidescreenAspect.NATIVE_4_3, WidescreenAspect.parse("   "));
    }
}
