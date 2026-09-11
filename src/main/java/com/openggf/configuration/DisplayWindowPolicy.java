package com.openggf.configuration;

/**
 * Pure window-sizing policy for display aspect presets.
 * When autosize is on AND a widescreen preset is selected, the window is derived
 * at the 2x baseline (width = pixelWidth*2, height = native height * 2). For NATIVE_4_3, or
 * when autosize is off, the caller's current window size is preserved so a
 * user's custom window is never stomped.
 */
public final class DisplayWindowPolicy {

    /** Existing default window scale relative to native pixels (640/320 = 448/224 = 2). */
    private static final int BASELINE_SCALE = 2;
    /** Native visible pixel height of the Mega Drive display. */
    private static final int PIXEL_HEIGHT = 224;

    private DisplayWindowPolicy() {
    }

    public record Resolved(int pixelWidth, int windowWidth, int windowHeight) {
    }

    public static Resolved resolve(WidescreenAspect aspect, boolean autosize,
            int currentWindowWidth, int currentWindowHeight) {
        int pixelWidth = aspect.pixelWidth();
        // Derive the window only when opting into a WIDESCREEN preset with
        // autosize on. NATIVE_4_3 (and autosize-off) preserve the caller's
        // current window, so a user's custom window size is never stomped by
        // the default-true autosize flag.
        if (autosize && aspect != WidescreenAspect.NATIVE_4_3) {
            return new Resolved(pixelWidth, pixelWidth * BASELINE_SCALE, PIXEL_HEIGHT * BASELINE_SCALE);
        }
        return new Resolved(pixelWidth, currentWindowWidth, currentWindowHeight);
    }
}
