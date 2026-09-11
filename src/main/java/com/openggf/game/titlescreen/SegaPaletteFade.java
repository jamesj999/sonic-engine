package com.openggf.game.titlescreen;

import com.openggf.graphics.PaletteFadePresentation;
import com.openggf.level.Palette;

/**
 * Mega Drive palette fade helpers matching the Sonic 1/2 Pal_FadeFromBlack and
 * Pal_FadeToBlack channel order, producing whole faded {@link Palette} copies.
 * The per-colour channel arithmetic lives in {@link PaletteFadePresentation}
 * so the CRAM upload path can apply the same steps without a {@link Palette}.
 */
public final class SegaPaletteFade {
    public enum Mode {
        NONE,
        FROM_BLACK,
        TO_BLACK
    }

    public static final int ROM_FADE_FRAMES = PaletteFadePresentation.ROM_FADE_FRAMES;

    private SegaPaletteFade() {
    }

    public static Palette apply(Palette target, Mode mode, int steps) {
        if (target == null || mode == null || mode == Mode.NONE) {
            return target;
        }
        return switch (mode) {
            case FROM_BLACK -> fromBlack(target, steps);
            case TO_BLACK -> toBlack(target, steps);
            case NONE -> target;
        };
    }

    public static Palette fromBlack(Palette target, int steps) {
        return applyEach(target, PaletteFadePresentation.Mode.FROM_BLACK, steps);
    }

    public static Palette toBlack(Palette target, int steps) {
        return applyEach(target, PaletteFadePresentation.Mode.TO_BLACK, steps);
    }

    private static Palette applyEach(Palette target, PaletteFadePresentation.Mode mode, int steps) {
        Palette faded = new Palette();
        for (int i = 0; i < Palette.PALETTE_SIZE; i++) {
            Palette.Color source = target.colors[i];
            int rgb = PaletteFadePresentation.fadeRgb(
                    Byte.toUnsignedInt(source.r),
                    Byte.toUnsignedInt(source.g),
                    Byte.toUnsignedInt(source.b),
                    mode, steps);
            Palette.Color out = faded.colors[i];
            out.r = (byte) (rgb >>> 16);
            out.g = (byte) (rgb >>> 8);
            out.b = (byte) rgb;
        }
        return faded;
    }
}
