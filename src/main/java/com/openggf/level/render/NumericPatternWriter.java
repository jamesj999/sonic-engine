package com.openggf.level.render;

import com.openggf.level.Pattern;

/** Decimal presentation using column-major, two-tile ROM digits. */
public final class NumericPatternWriter {
    private NumericPatternWriter() {
    }

    /**
     * Writes four bonus digits, blanking leading zeroes but retaining the ones digit.
     * An oversized leading digit or incomplete destination pair is left untouched.
     * Leading blank pairs require allocated destination tiles, as in the results screens.
     */
    public static void writeBonus(Pattern[] dest, int startIndex, int value,
                                  Pattern[] digits, Pattern blank) {
        int divisor = 1000;
        boolean hasDigit = false;
        for (int i = 0; i < 4; i++) {
            int digit = value / divisor;
            value %= divisor;
            int tileIndex = startIndex + i * 2;
            if (digit != 0 || hasDigit || i == 3) {
                hasDigit = true;
                copyDigit(dest, tileIndex, digit, digits);
            } else {
                dest[tileIndex].copyFrom(blank);
                dest[tileIndex + 1].copyFrom(blank);
            }
            divisor /= 10;
        }
    }

    public static void copyDigit(Pattern[] dest, int destIndex, int digit, Pattern[] digits) {
        int srcIndex = digit * 2;
        if (srcIndex + 1 >= digits.length || destIndex + 1 >= dest.length) {
            return;
        }
        dest[destIndex].copyFrom(digits[srcIndex]);
        dest[destIndex + 1].copyFrom(digits[srcIndex + 1]);
    }
}
