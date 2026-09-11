package com.openggf.graphics;

import java.util.Objects;

/**
 * Typed pixel font resource selection for overlay text renderers.
 */
public enum PixelFontVariant {
    PIXEL_FONT("pixel-font.png", true),
    PIXEL_FONT_NO_SHADOW("pixel-font-ns.png", false);

    private final String resourcePath;
    private final boolean drawsShadow;

    PixelFontVariant(String resourcePath, boolean drawsShadow) {
        this.resourcePath = Objects.requireNonNull(resourcePath, "resourcePath");
        this.drawsShadow = drawsShadow;
    }

    public String resourcePath() {
        return resourcePath;
    }

    public boolean drawsShadow() {
        return drawsShadow;
    }
}
