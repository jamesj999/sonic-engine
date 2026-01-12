package uk.co.jamesj999.sonic.level.scroll;

/**
 * Motorola 68000 arithmetic helpers for pixel-perfect scroll calculations.
 * These methods replicate the exact behavior of 68k instructions to match
 * the original Sonic 2 scroll routines.
 */
public final class M68KMath {

    public static final int VISIBLE_LINES = 224;

    private M68KMath() {
        // Utility class
    }

    /**
     * Extract low 16 bits as signed word.
     * Equivalent to treating a value as a 68k word register.
     */
    public static short wordOf(int value) {
        return (short) value;
    }

    /**
     * Negate a value and return as 16-bit word.
     * Equivalent to: neg.w d0
     */
    public static short negWord(int value) {
        return (short) (-value);
    }

    /**
     * Arithmetic shift right of a 16-bit signed value.
     * Equivalent to: asr.w #n,d0
     */
    public static short asrWord(int value, int shift) {
        short word = (short) value;
        return (short) (word >> shift);
    }

    /**
     * Signed 16-bit division.
     * Equivalent to: divs.w #divisor,d0
     * Returns quotient in low word (matches 68k).
     */
    public static short divsWord(int dividend, int divisor) {
        if (divisor == 0)
            return 0;
        short signedDividend = (short) dividend;
        short signedDivisor = (short) divisor;
        return (short) (signedDividend / signedDivisor);
    }

    /**
     * Unsigned 16-bit division.
     * Equivalent to: divu.w #divisor,d0
     * Returns quotient in low word.
     */
    public static short divuWord(int dividend, int divisor) {
        if (divisor == 0)
            return 0;
        int unsignedDividend = dividend & 0xFFFF;
        int unsignedDivisor = divisor & 0xFFFF;
        return (short) (unsignedDividend / unsignedDivisor);
    }

    /**
     * Pack FG and BG scroll words into a single int.
     * Format matches VDP HScroll table: FG in high word, BG in low word.
     * This matches: move.l d0,(a1)+ where d0 contains both words.
     */
    public static int packScrollWords(short fg, short bg) {
        return ((fg & 0xFFFF) << 16) | (bg & 0xFFFF);
    }

    /**
     * Extract FG scroll from packed value.
     */
    public static short unpackFG(int packed) {
        return (short) (packed >> 16);
    }

    /**
     * Extract BG scroll from packed value.
     */
    public static short unpackBG(int packed) {
        return (short) packed;
    }
}
