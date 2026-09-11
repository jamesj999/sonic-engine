package com.openggf.game.sonic1.dataselect;

import com.openggf.graphics.RgbaImage;
import com.openggf.level.Palette;
import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts cached Sonic 1 preview PNGs into the pattern, palette, and mapping-frame contract used
 * by the donated S3K selected-slot renderer.
 *
 * <p>The generated preview frame intentionally renders on palette line 2 so it can coexist with the
 * dedicated S2 purple-emerald path on line 3.</p>
 */
public final class S1SelectedSlotPreviewLoader {
    private static final int TILE_COLUMNS = S1DataSelectImageGenerator.PREVIEW_WIDTH / Pattern.PATTERN_WIDTH;
    private static final int TILE_ROWS = S1DataSelectImageGenerator.PREVIEW_HEIGHT / Pattern.PATTERN_HEIGHT;
    private static final int MAX_VISIBLE_COLORS = 15;
    private static final int SELECTED_ICON_TILE_OFFSET = 0x31B;
    private static final int PALETTE_INDEX = 2;
    private static final SpriteMappingFrame FRAME = buildFrame();

    /**
     * Converts a whole preview map keyed by host zone ID.
     */
    public Map<Integer, LoadedPreview> loadAll(Map<Integer, RgbaImage> previews) {
        if (previews == null || previews.isEmpty()) {
            return Map.of();
        }
        Map<Integer, LoadedPreview> loaded = new LinkedHashMap<>();
        for (Map.Entry<Integer, RgbaImage> entry : previews.entrySet()) {
            loaded.put(entry.getKey(), load(entry.getValue()));
        }
        return Map.copyOf(loaded);
    }

    /**
     * Quantizes a single 80x56 RGBA preview into 4bpp tile patterns plus an attached palette.
     */
    public LoadedPreview load(RgbaImage image) {
        if (image == null
                || image.width() != S1DataSelectImageGenerator.PREVIEW_WIDTH
                || image.height() != S1DataSelectImageGenerator.PREVIEW_HEIGHT) {
            throw new IllegalArgumentException("S1 selected preview images must be 80x56");
        }
        QuantizedPreview quantized = quantize(image);
        Pattern[] patterns = new Pattern[TILE_COLUMNS * TILE_ROWS];
        for (int tileY = 0; tileY < TILE_ROWS; tileY++) {
            for (int tileX = 0; tileX < TILE_COLUMNS; tileX++) {
                Pattern pattern = new Pattern();
                int tileIndex = tileY * TILE_COLUMNS + tileX;
                for (int py = 0; py < Pattern.PATTERN_HEIGHT; py++) {
                    for (int px = 0; px < Pattern.PATTERN_WIDTH; px++) {
                        int imageX = tileX * Pattern.PATTERN_WIDTH + px;
                        int imageY = tileY * Pattern.PATTERN_HEIGHT + py;
                        pattern.setPixel(px, py, (byte) quantized.indices()[imageY][imageX]);
                    }
                }
                patterns[tileIndex] = pattern;
            }
        }
        return new LoadedPreview(patterns, quantized.palette(), FRAME);
    }

    /**
     * Returns the shared mapping frame used by all runtime-generated host previews.
     */
    public static SpriteMappingFrame frame() {
        return FRAME;
    }

    private static QuantizedPreview quantize(RgbaImage image) {
        int[] counts = new int[512];
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int argb = image.argb(x, y);
                if (((argb >>> 24) & 0xFF) < 0x80) {
                    continue;
                }
                counts[quantizeKey(argb)]++;
            }
        }

        List<Integer> paletteKeys = new ArrayList<>();
        for (int key = 0; key < counts.length; key++) {
            if (counts[key] > 0) {
                paletteKeys.add(key);
            }
        }
        paletteKeys.sort(Comparator.<Integer>comparingInt(key -> counts[key]).reversed()
                .thenComparingInt(Integer::intValue));
        if (paletteKeys.size() > MAX_VISIBLE_COLORS) {
            paletteKeys = new ArrayList<>(paletteKeys.subList(0, MAX_VISIBLE_COLORS));
        }
        if (paletteKeys.isEmpty()) {
            paletteKeys.add(0);
        }

        Palette palette = new Palette();
        for (int i = 0; i < paletteKeys.size(); i++) {
            palette.setColor(i + 1, toPaletteColor(paletteKeys.get(i)));
        }

        int[][] indices = new int[image.height()][image.width()];
        for (int y = 0; y < image.height(); y++) {
            for (int x = 0; x < image.width(); x++) {
                int argb = image.argb(x, y);
                if (((argb >>> 24) & 0xFF) < 0x80) {
                    indices[y][x] = 0;
                    continue;
                }
                int pixelKey = quantizeKey(argb);
                indices[y][x] = nearestPaletteIndex(pixelKey, paletteKeys) + 1;
            }
        }
        return new QuantizedPreview(palette, indices);
    }

    private static int nearestPaletteIndex(int pixelKey, List<Integer> paletteKeys) {
        int pixelR = (pixelKey >> 6) & 0x7;
        int pixelG = (pixelKey >> 3) & 0x7;
        int pixelB = pixelKey & 0x7;
        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int i = 0; i < paletteKeys.size(); i++) {
            int paletteKey = paletteKeys.get(i);
            int paletteR = (paletteKey >> 6) & 0x7;
            int paletteG = (paletteKey >> 3) & 0x7;
            int paletteB = paletteKey & 0x7;
            int dr = pixelR - paletteR;
            int dg = pixelG - paletteG;
            int db = pixelB - paletteB;
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    private static int quantizeKey(int argb) {
        int r = ((argb >> 16) & 0xFF) * 7 / 255;
        int g = ((argb >> 8) & 0xFF) * 7 / 255;
        int b = (argb & 0xFF) * 7 / 255;
        return (r << 6) | (g << 3) | b;
    }

    private static Palette.Color toPaletteColor(int key) {
        int r = (key >> 6) & 0x7;
        int g = (key >> 3) & 0x7;
        int b = key & 0x7;
        return new Palette.Color(toByte(r), toByte(g), toByte(b));
    }

    private static byte toByte(int component3Bit) {
        return (byte) ((component3Bit * 255 + 3) / 7);
    }

    private static SpriteMappingFrame buildFrame() {
        List<SpriteMappingPiece> pieces = new ArrayList<>(TILE_COLUMNS * TILE_ROWS);
        int tileIndex = 0;
        int yOffsetBase = -(S1DataSelectImageGenerator.PREVIEW_HEIGHT / 2);
        int xOffsetBase = -(S1DataSelectImageGenerator.PREVIEW_WIDTH / 2);
        for (int tileY = 0; tileY < TILE_ROWS; tileY++) {
            for (int tileX = 0; tileX < TILE_COLUMNS; tileX++) {
                pieces.add(new SpriteMappingPiece(
                        xOffsetBase + (tileX * Pattern.PATTERN_WIDTH),
                        yOffsetBase + (tileY * Pattern.PATTERN_HEIGHT),
                        1,
                        1,
                        SELECTED_ICON_TILE_OFFSET + tileIndex,
                        false,
                        false,
                        PALETTE_INDEX,
                        false));
                tileIndex++;
            }
        }
        return new SpriteMappingFrame(List.copyOf(pieces));
    }

    /**
     * Pattern/palette/frame bundle consumed by the donated S3K selected-slot renderer.
     */
    public record LoadedPreview(Pattern[] patterns, Palette palette, SpriteMappingFrame frame) {
    }

    private record QuantizedPreview(Palette palette, int[][] indices) {
    }
}
