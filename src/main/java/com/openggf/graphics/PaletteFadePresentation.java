package com.openggf.graphics;

/**
 * A Mega Drive palette fade applied at CRAM upload time.
 *
 * <p>The Sonic 1/2 {@code PaletteFadeIn} / {@code PaletteFadeOut} loops keep the
 * target colours in {@code v_palette_fading} and rebuild the CRAM-bound
 * {@code v_palette} one channel step per frame for the lines named by
 * {@code v_pfade_start} / {@code v_pfade_size}: {@code FadeIn_AddColor} raises
 * blue, then green, then red by one 3-bit level per pass, and
 * {@code FadeOut_DecColor} lowers red, then green, then blue. The engine's
 * owners keep writing their live palettes as usual; this state transforms every
 * upload of a masked line on its way to the GPU, so an active fade covers writes
 * made by any owner during the frame (palette cycles, hit flashes, water swaps).
 * Unmasked lines pass through untouched, which is how Sonic 1 keeps line 0
 * (Sonic, the HUD and the title card) at full colour while the level fades in.
 */
@com.openggf.game.ModApi
public final class PaletteFadePresentation {
    @com.openggf.game.ModApi
    public enum Mode {
        NONE,
        FROM_BLACK,
        TO_BLACK
    }

    /** {@code move.w #22-1,d4}: VBlank periods in one blocking fade. */
    public static final int ROM_FADE_FRAMES = 22;
    /** Seven levels per channel, three channels: passes after which every colour has arrived. */
    public static final int COLOR_STEPS = 21;
    /** {@code move.w #$202F,(v_pfade_start).w}: palette lines 1, 2 and 3. */
    public static final int LINES_1_TO_3 = 0b1110;

    private Mode mode = Mode.NONE;
    private int steps;
    private int lineMask;

    /** Returns true when the visible state changed. */
    public boolean set(Mode mode, int steps, int lineMask) {
        Mode resolvedMode = mode == null ? Mode.NONE : mode;
        int resolvedSteps = Math.max(0, steps);
        if (resolvedMode == Mode.NONE) {
            return clear();
        }
        boolean changed = this.mode != resolvedMode || this.steps != resolvedSteps || this.lineMask != lineMask;
        this.mode = resolvedMode;
        this.steps = resolvedSteps;
        this.lineMask = lineMask;
        return changed;
    }

    /** Returns true when a fade was active. */
    public boolean clear() {
        boolean wasActive = isActive();
        mode = Mode.NONE;
        steps = 0;
        lineMask = 0;
        return wasActive;
    }

    public boolean isActive() {
        return mode != Mode.NONE && lineMask != 0;
    }

    public Mode mode() {
        return mode;
    }

    public int steps() {
        return steps;
    }

    public int lineMask() {
        return lineMask;
    }

    public boolean affects(int paletteLine) {
        return isActive() && paletteLine >= 0 && paletteLine < Integer.SIZE && (lineMask & (1 << paletteLine)) != 0;
    }

    /**
     * The 8-bit colour to upload for a colour on a line the caller has checked with
     * {@link #affects}, packed as {@code 0xRRGGBB}.
     */
    public int fadeRgb(int red, int green, int blue) {
        return fadeRgb(red, green, blue, mode, steps);
    }

    /**
     * One 8-bit-per-channel colour after {@code steps} passes of
     * {@code FadeIn_AddColor} ({@link Mode#FROM_BLACK}, rising from black) or
     * {@code FadeOut_DecColor} ({@link Mode#TO_BLACK}, dropping towards black),
     * packed as {@code 0xRRGGBB}. {@link Mode#NONE} passes the colour through the
     * same Mega Drive 3-bit quantisation.
     */
    public static int fadeRgb(int red, int green, int blue, Mode mode, int steps) {
        int targetR = toGenesisChannel(red);
        int targetG = toGenesisChannel(green);
        int targetB = toGenesisChannel(blue);
        int r = targetR;
        int g = targetG;
        int b = targetB;
        int clampedSteps = Math.max(0, Math.min(COLOR_STEPS, steps));
        if (mode == Mode.FROM_BLACK) {
            r = 0;
            g = 0;
            b = 0;
            for (int step = 0; step < clampedSteps; step++) {
                if (r == targetR && g == targetG && b == targetB) {
                    break;
                }
                if (b < targetB) {
                    b++;
                } else if (g < targetG) {
                    g++;
                } else if (r < targetR) {
                    r++;
                }
            }
        } else if (mode == Mode.TO_BLACK) {
            for (int step = 0; step < clampedSteps; step++) {
                if (r == 0 && g == 0 && b == 0) {
                    break;
                }
                if (r > 0) {
                    r--;
                } else if (g > 0) {
                    g--;
                } else if (b > 0) {
                    b--;
                }
            }
        }
        return (toRgb(r) << 16) | (toRgb(g) << 8) | toRgb(b);
    }

    static int toGenesisChannel(int rgb8) {
        return ((rgb8 & 0xFF) * 7 + 127) / 255;
    }

    static int toRgb(int channel) {
        return (channel * 255 + 3) / 7;
    }
}
