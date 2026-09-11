package com.openggf.game.titlescreen;

import com.openggf.level.Palette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TestSegaPaletteFade {
    @Test
    void fadeFromBlackUsesMegaDriveBlueGreenRedChannelOrder() {
        Palette target = singleColor(7, 7, 7);

        Palette step7 = SegaPaletteFade.fromBlack(target, 7);
        assertColor(step7, 0, 0, 0, 7);

        Palette step14 = SegaPaletteFade.fromBlack(target, 14);
        assertColor(step14, 0, 0, 7, 7);

        Palette step21 = SegaPaletteFade.fromBlack(target, 21);
        assertColor(step21, 0, 7, 7, 7);
    }

    @Test
    void fadeToBlackUsesMegaDriveRedGreenBlueChannelOrder() {
        Palette target = singleColor(7, 7, 7);

        Palette step7 = SegaPaletteFade.toBlack(target, 7);
        assertColor(step7, 0, 0, 7, 7);

        Palette step14 = SegaPaletteFade.toBlack(target, 14);
        assertColor(step14, 0, 0, 0, 7);

        Palette step21 = SegaPaletteFade.toBlack(target, 21);
        assertColor(step21, 0, 0, 0, 0);
    }

    private static Palette singleColor(int r, int g, int b) {
        Palette palette = new Palette();
        palette.colors[0].r = toRgb(r);
        palette.colors[0].g = toRgb(g);
        palette.colors[0].b = toRgb(b);
        return palette;
    }

    private static void assertColor(Palette palette, int index, int r, int g, int b) {
        Palette.Color color = palette.getColor(index);
        assertEquals(toRgb(r), color.r);
        assertEquals(toRgb(g), color.g);
        assertEquals(toRgb(b), color.b);
    }

    private static byte toRgb(int channel) {
        return (byte) ((channel * 255 + 3) / 7);
    }
}
