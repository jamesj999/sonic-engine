package com.openggf.graphics;

import com.openggf.tests.TestTempFiles;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestRgbaImageIO {

    @Test
    void saveAndLoadPng_roundTripsPixels() throws Exception {
        RgbaImage image = new RgbaImage(2, 1, new int[]{
                0xFFFF0000,
                0xFF00FF00
        });
        Path png = TestTempFiles.createTempFile("rgba-image", ".png");

        ScreenshotCapture.savePNG(image, png);
        RgbaImage loaded = ScreenshotCapture.loadPNG(png);

        assertEquals(2, loaded.width());
        assertEquals(1, loaded.height());
        assertEquals(0xFFFF0000, loaded.argb(0, 0));
        assertEquals(0xFF00FF00, loaded.argb(1, 0));
        assertTrue(ScreenshotCapture.imagesMatch(image, loaded, 0).matched());
    }
}
