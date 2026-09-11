package com.openggf.camera;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.openggf.configuration.DeadzoneMode;
import org.junit.jupiter.api.Test;

class TestDeadzoneGeometry {

    @Test
    void nativeMatchesRomConstantsForBothModes() {
        for (DeadzoneMode mode : DeadzoneMode.values()) {
            // rightEdge is both the moving-right scroll trigger and the snap rest point.
            assertEquals(160, DeadzoneGeometry.rightEdge(320), mode.name());
            assertEquals(16, DeadzoneGeometry.bandWidth(320, mode), mode.name());
            assertEquals(144, DeadzoneGeometry.leftEdge(320, mode), mode.name());
        }
    }

    @Test
    void centerScaledKeeps16pxBandAtWidescreen() {
        assertEquals(200, DeadzoneGeometry.rightEdge(400));
        assertEquals(16, DeadzoneGeometry.bandWidth(400, DeadzoneMode.CENTER_SCALED));
        assertEquals(184, DeadzoneGeometry.leftEdge(400, DeadzoneMode.CENTER_SCALED));
    }

    @Test
    void proportionalScalesBandWithWidth() {
        // 16 * 400 / 320 = 20
        assertEquals(20, DeadzoneGeometry.bandWidth(400, DeadzoneMode.PROPORTIONAL));
        assertEquals(180, DeadzoneGeometry.leftEdge(400, DeadzoneMode.PROPORTIONAL));
        // 16 * 800 / 320 = 40
        assertEquals(40, DeadzoneGeometry.bandWidth(800, DeadzoneMode.PROPORTIONAL));
    }
}
