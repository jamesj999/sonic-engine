package com.openggf.graphics;

import java.util.Arrays;

public record RgbaImage(int width, int height, int[] pixels) {
    public RgbaImage {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        if (pixels == null || pixels.length != width * height) {
            throw new IllegalArgumentException("Pixel array length must match width * height");
        }
    }

    public int argb(int x, int y) {
        return pixels[y * width + x];
    }

    public void setArgb(int x, int y, int argb) {
        pixels[y * width + x] = argb;
    }

    public RgbaImage copy() {
        return new RgbaImage(width, height, Arrays.copyOf(pixels, pixels.length));
    }
}
