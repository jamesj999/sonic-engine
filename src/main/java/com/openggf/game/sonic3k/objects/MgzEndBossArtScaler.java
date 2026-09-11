package com.openggf.game.sonic3k.objects;

import com.openggf.level.Pattern;
import com.openggf.level.render.SpriteMappingFrame;
import com.openggf.level.render.SpriteMappingPiece;

/** Pixel-exact Java translation of {@code sub_246DA} for MGZ's 128x64 source. */
final class MgzEndBossArtScaler {
    static final int SOURCE_WIDTH = 0x80;
    static final int SOURCE_HEIGHT = 0x40;
    private static final int SOURCE_ROW_BYTES = SOURCE_WIDTH / 2;

    private MgzEndBossArtScaler() { }

    static void scale(byte[] source, int scaleStep, SpriteMappingFrame mapping, Pattern[] target) {
        int modifiedScale = (scaleStep & 0x7F) + 4;
        Extents extents = extents(mapping);
        for (Pattern pattern : target) if (pattern != null) pattern.clear();
        for (SpriteMappingPiece piece : mapping.pieces()) {
            for (int tileX = 0; tileX < piece.widthTiles(); tileX++) {
                for (int tileY = 0; tileY < piece.heightTiles(); tileY++) {
                    int tile = piece.tileIndex() + tileX * piece.heightTiles() + tileY;
                    if (tile < 0 || tile >= target.length || target[tile] == null) continue;
                    int outputX = piece.xOffset() - extents.minX + tileX * 8;
                    int outputY = piece.yOffset() - extents.minY + tileY * 8;
                    scaleTile(source, modifiedScale, outputX, outputY, target[tile]);
                }
            }
        }
    }

    private static void scaleTile(byte[] source, int scale, int outputX, int outputY, Pattern target) {
        for (int y = 0; y < 8; y++) {
            // sub_246DA advances source rows by (scale/8), carrying the three
            // fractional bits in d5. Keeping the multiplication here produces
            // the identical add/addx sequence without its scale special cases.
            int sourceY = ((outputY + y) * scale) >> 3;
            for (int x = 0; x < 8; x++) {
                // Each output nibble advances by scale/4 source pixels.
                int sourceX = ((outputX + x) * scale) >> 2;
                target.setPixel(x, y, pixel(source, sourceX, sourceY));
            }
        }
    }

    private static byte pixel(byte[] source, int x, int y) {
        if (x < 0 || x >= SOURCE_WIDTH || y < 0 || y >= SOURCE_HEIGHT) return 0;
        int packed = Byte.toUnsignedInt(source[y * SOURCE_ROW_BYTES + x / 2]);
        return (byte) ((x & 1) == 0 ? packed >>> 4 : packed & 0xF);
    }

    private static Extents extents(SpriteMappingFrame frame) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        for (SpriteMappingPiece piece : frame.pieces()) {
            minX = Math.min(minX, piece.xOffset());
            minY = Math.min(minY, piece.yOffset());
        }
        return new Extents(minX == Integer.MAX_VALUE ? 0 : minX, minY == Integer.MAX_VALUE ? 0 : minY);
    }

    private record Extents(int minX, int minY) { }
}
