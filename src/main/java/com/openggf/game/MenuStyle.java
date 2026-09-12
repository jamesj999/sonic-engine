package com.openggf.game;

import com.openggf.graphics.MenuPixelFont;
import com.openggf.graphics.PixelFont;

/** Shared host-menu presentation on the native pixel grid. */
public final class MenuStyle {
    public static final float COMPACT = 2f / 3f;
    private MenuStyle() { }

    /** Center a text cell on the native pixel grid; odd spare pixels go below. */
    public static int textY(int rowTop, int rowHeight, float scale) {
        return contentY(rowTop, rowHeight, MenuPixelFont.glyphLineHeight(scale));
    }

    public static int contentY(int rowTop, int rowHeight, int contentHeight) {
        return rowTop + (rowHeight - contentHeight) / 2;
    }

    /** A primary label and its focus share the same ten-pixel cell geometry. */
    public static void focusLabel(PixelFont font, int x, int textY, int width, int height) {
        focusContent(font, x, textY, width, height, MenuPixelFont.glyphLineHeight(1));
    }

    public static void focusContent(PixelFont font, int x, int textY, int width, int height,
                                    int contentHeight) {
        focus(font, x, textY - (height - contentHeight) / 2, width, height);
    }

    public static void page(PixelFont font, int width, String title, String subtitle) {
        fill(font, 0, 0, width, 224, .025f, .065f, .19f, 1);
        checkerboard(font, width);
        fill(font, 0, 0, width, 29, .045f, .14f, .34f, 1);
        fill(font, 0, 28, width, 1, 1, .78f, .23f, 1);
        label(font, title, 9, 7, width - 18, 1, 1, 1);
        if (subtitle != null && !subtitle.isBlank()) text(font, subtitle, 9, 34, width - 18, .7f, .8f, .95f);
    }

    /** Low-contrast Sonic-style checks, aligned to the logical pixel grid. */
    public static void checkerboard(PixelFont font, int width) {
        checkerboard(font, 0, width);
    }

    public static void checkerboard(PixelFont font, int startX, int width) {
        for (int y = 29, row = 0; y < 198; y += 16, row++) {
            for (int x = startX + (row & 1) * 16; x < width; x += 32) {
                fill(font, x, y, Math.min(15, width - x), Math.min(15, 198 - y),
                        .05f, .115f, .28f, 1);
            }
        }
    }

    public static void footer(PixelFont font, int width, String first, String second) {
        fill(font, 0, 198, width, 26, .012f, .025f, .09f, 1);
        if (first != null) text(font, first, 9, 200, width - 18, .5f, .9f, 1);
        if (second != null) label(font, second, 9, 212, width - 18, .8f, .86f, 1);
    }

    public static void panel(PixelFont font, int x, int y, int width, int height) {
        fill(font, x, y, width, height, .05f, .13f, .3f, 1);
    }

    public static void focus(PixelFont font, int x, int y, int width, int height) {
        fill(font, x, y, width, height, .08f, .23f, .43f, 1);
        fill(font, x, y, width, 1, .4f, .88f, 1, 1);
        fill(font, x, y + height - 1, width, 1, .4f, .88f, 1, 1);
        fill(font, x, y, 1, height, .4f, .88f, 1, 1);
        fill(font, x + width - 1, y, 1, height, .4f, .88f, 1, 1);
    }

    public static void fill(PixelFont font, int x, int y, int width, int height,
                            float r, float g, float b, float a) {
        if (font instanceof MenuPixelFont menu) menu.fillRect(x, y, width, height, r, g, b, a);
    }

    public static void text(PixelFont font, String text, int x, int y, int maxWidth,
                            float r, float g, float b) {
        font.drawText(fit(text, maxWidth), x, y, COMPACT, r, g, b, 1);
    }

    /** Primary labels keep the original prototype's full-size 9x10 lettering. */
    public static void label(PixelFont font, String text, int x, int y, int maxWidth,
                            float r, float g, float b) {
        font.drawText(fitLabel(text, maxWidth), x, y, 1f, r, g, b, 1);
    }

    public static String fitLabel(String text, int maxWidth) {
        return fitCells(text, Math.max(0, maxWidth / 9));
    }

    public static String fit(String text, int maxWidth) {
        return fitCells(text, Math.max(0, maxWidth / 6));
    }

    private static String fitCells(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        return limit < 3 ? ".".repeat(limit) : text.substring(0, limit - 3) + "...";
    }
}
