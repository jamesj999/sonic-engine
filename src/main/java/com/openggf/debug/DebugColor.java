package com.openggf.debug;

/**
 * Lightweight color class for debug rendering that doesn't depend on AWT.
 * This avoids AWT native library loading issues in GraalVM native images.
 */
public final class DebugColor {
    private final int r, g, b, a;

    // Common colors as static constants
    public static final DebugColor WHITE = new DebugColor(255, 255, 255);
    public static final DebugColor BLACK = new DebugColor(0, 0, 0);
    public static final DebugColor RED = new DebugColor(255, 0, 0);
    public static final DebugColor GREEN = new DebugColor(0, 255, 0);
    public static final DebugColor BLUE = new DebugColor(0, 0, 255);
    public static final DebugColor YELLOW = new DebugColor(255, 255, 0);
    public static final DebugColor CYAN = new DebugColor(0, 255, 255);
    public static final DebugColor MAGENTA = new DebugColor(255, 0, 255);
    public static final DebugColor ORANGE = new DebugColor(255, 200, 0);
    public static final DebugColor GRAY = new DebugColor(128, 128, 128);
    public static final DebugColor LIGHT_GRAY = new DebugColor(192, 192, 192);
    public static final DebugColor DARK_GRAY = new DebugColor(64, 64, 64);

    public DebugColor(int r, int g, int b, int a) {
        this.r = r & 0xFF;
        this.g = g & 0xFF;
        this.b = b & 0xFF;
        this.a = a & 0xFF;
    }

    public DebugColor(int r, int g, int b) {
        this(r, g, b, 255);
    }

    public DebugColor(int rgb) {
        this((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, (rgb >> 24) & 0xFF);
    }

    public int getRed() {
        return r;
    }

    public int getGreen() {
        return g;
    }

    public int getBlue() {
        return b;
    }

    public int getAlpha() {
        return a;
    }

    public int getRGB() {
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DebugColor that = (DebugColor) o;
        return r == that.r && g == that.g && b == that.b && a == that.a;
    }

    @Override
    public int hashCode() {
        return getRGB();
    }

    @Override
    public String toString() {
        return String.format("DebugColor[r=%d,g=%d,b=%d,a=%d]", r, g, b, a);
    }
}
