package com.openggf.graphics;

/**
 * Original, hand-authored five-column menu lettering. Each hexadecimal pair is
 * one top-to-bottom row of five pixels, most-significant bit at the left.
 * These masks are native glyph artwork, not samples of the nine-pixel font.
 * Capitals end on row six; lowercase descenders may use row seven within the
 * same eight-pixel cell. The sixth column remains transparent spacing.
 */
final class MenuGlyphs {
    static final int ADVANCE = 6;
    static final int HEIGHT = 8;
    static final int COLUMNS = 16;
    static final int ATLAS_WIDTH = COLUMNS * ADVANCE;
    static final int ATLAS_HEIGHT = 6 * HEIGHT;
    private static final byte[][] ROWS = new byte[95][];

    static {
        glyph(' ', "00000000000000");
        glyph('!', "04040404040004");
        glyph('"', "0A0A0A00000000");
        glyph('#', "0A0A1F0A1F0A0A");
        glyph('$', "040F140E051E04");
        glyph('%', "18190204081303");
        glyph('&', "0C12140815120D");
        glyph('\'', "04040800000000");
        glyph('(', "02040808080402");
        glyph(')', "08040202020408");
        glyph('*', "00150E1F0E1500");
        glyph('+', "0004041F040400");
        glyph(',', "00000000000408");
        glyph('-', "0000001F000000");
        glyph('.', "00000000000C0C");
        glyph('/', "01010204081010");
        glyph('0', "0E11131519110E");
        glyph('1', "040C040404040E");
        glyph('2', "0E11010204081F");
        glyph('3', "1E01010E01011E");
        glyph('4', "02060A121F0202");
        glyph('5', "1F10101E01011E");
        glyph('6', "0E10101E11110E");
        glyph('7', "1F010204080808");
        glyph('8', "0E11110E11110E");
        glyph('9', "0E11110F01010E");
        glyph(':', "000C0C000C0C00");
        glyph(';', "000C0C00040408");
        glyph('<', "01020408040201");
        glyph('=', "00001F001F0000");
        glyph('>', "10080402040810");
        glyph('?', "0E110102040004");
        glyph('@', "0E11171517100F");
        glyph('A', "0E11111F111111");
        glyph('B', "1E11111E11111E");
        glyph('C', "0F10101010100F");
        glyph('D', "1E11111111111E");
        glyph('E', "1F10101E10101F");
        glyph('F', "1F10101E101010");
        glyph('G', "0F10101711110F");
        glyph('H', "1111111F111111");
        glyph('I', "0E04040404040E");
        glyph('J', "0702020202120C");
        glyph('K', "11121418141211");
        glyph('L', "1010101010101F");
        glyph('M', "111B1515111111");
        glyph('N', "11191915131311");
        glyph('O', "0E11111111110E");
        glyph('P', "1E11111E101010");
        glyph('Q', "0E11111115120D");
        glyph('R', "1E11111E141211");
        glyph('S', "0F10100E01011E");
        glyph('T', "1F040404040404");
        glyph('U', "1111111111110E");
        glyph('V', "11111111110A04");
        glyph('W', "11111115151B11");
        glyph('X', "11110A040A1111");
        glyph('Y', "11110A04040404");
        glyph('Z', "1F01020408101F");
        glyph('[', "0E08080808080E");
        glyph('\\', "10100804020101");
        glyph(']', "0E02020202020E");
        glyph('^', "040A1100000000");
        glyph('_', "0000000000001F");
        glyph('`', "08040200000000");
        glyph('a', "00000E010F110F");
        glyph('b', "1010161911111E");
        glyph('c', "00000F1010100F");
        glyph('d', "01010D1311110F");
        glyph('e', "00000E111F100E");
        glyph('f', "0609091C080808");
        glyph('g', "00000F11110F010E");
        glyph('h', "10101619111111");
        glyph('i', "04000C0404040E");
        glyph('j', "020006020202120C");
        glyph('k', "10101214181412");
        glyph('l', "0C04040404040E");
        glyph('m', "00001A15151515");
        glyph('n', "00001619111111");
        glyph('o', "00000E1111110E");
        glyph('p', "00001E11111E1010");
        glyph('q', "00000F11110F0101");
        glyph('r', "00001619101010");
        glyph('s', "00000F100E011E");
        glyph('t', "08081E08080906");
        glyph('u', "0000111111130D");
        glyph('v', "00001111110A04");
        glyph('w', "0000111115150A");
        glyph('x', "0000110A040A11");
        glyph('y', "00001111110F010E");
        glyph('z', "00001F0204081F");
        glyph('{', "03040408040403");
        glyph('|', "04040404040404");
        glyph('}', "18040402040418");
        glyph('~', "00000815020000");
    }

    private MenuGlyphs() { }

    private static void glyph(char character, String hexadecimalRows) {
        if (hexadecimalRows.length() != 14 && hexadecimalRows.length() != 16) {
            throw new IllegalArgumentException("Seven or eight glyph rows required");
        }
        byte[] rows = new byte[HEIGHT];
        for (int i = 0; i < hexadecimalRows.length() / 2; i++) {
            rows[i] = (byte) Integer.parseInt(hexadecimalRows.substring(i * 2, i * 2 + 2), 16);
            if ((rows[i] & ~31) != 0) throw new IllegalArgumentException("Five glyph columns required");
        }
        ROWS[character - 32] = rows;
    }

    static char normalized(char character) {
        return switch (character) {
            case '\u2018', '\u2019' -> '\'';
            case '\u201c', '\u201d' -> '"';
            case '\u2013', '\u2014' -> '-';
            case '\u2190' -> '<';
            case '\u2192' -> '>';
            case '\u2191' -> '^';
            case '\u2193' -> 'v';
            default -> character >= 32 && character <= 126 ? character : '?';
        };
    }

    static int row(char character, int row) {
        return ROWS[normalized(character) - 32][row];
    }

    /** Bottom-left-origin, opaque white glyphs on transparent cells for GL upload. */
    static byte[] atlasRgba() {
        byte[] rgba = new byte[ATLAS_WIDTH * ATLAS_HEIGHT * 4];
        for (int index = 0; index < ROWS.length; index++) {
            int originX = (index % COLUMNS) * ADVANCE;
            int originY = (index / COLUMNS) * HEIGHT;
            for (int y = 0; y < HEIGHT; y++) {
                for (int x = 0; x < 5; x++) {
                    if ((ROWS[index][y] & (1 << (4 - x))) == 0) continue;
                    int offset = ((ATLAS_HEIGHT - 1 - originY - y) * ATLAS_WIDTH + originX + x) * 4;
                    for (int channel = 0; channel < 4; channel++) rgba[offset + channel] = (byte) 255;
                }
            }
        }
        return rgba;
    }
}
