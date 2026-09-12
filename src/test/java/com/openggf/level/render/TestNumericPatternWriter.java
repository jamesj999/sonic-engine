package com.openggf.level.render;

import com.openggf.level.Pattern;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class TestNumericPatternWriter {
    private Pattern[] patterns(int count) {
        Pattern[] result = new Pattern[count];
        for (int i = 0; i < count; i++) {
            result[i] = new Pattern();
            result[i].setPixel(0, 0, (byte) (i + 1));
        }
        return result;
    }

    private void assertTiles(Pattern[] actual, int... expected) {
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i].getPixel(0, 0), "tile " + i);
        }
    }

    @Test
    void zeroAndLeadingBlanksPreserveDestinationOutsideRange() {
        Pattern[] dest = patterns(10);
        NumericPatternWriter.writeBonus(dest, 1, 0, patterns(20), new Pattern());
        assertTiles(dest, 1, 0, 0, 0, 0, 0, 0, 1, 2, 10);
        NumericPatternWriter.writeBonus(dest, 1, 42, patterns(20), new Pattern());
        assertTiles(dest, 1, 0, 0, 0, 0, 9, 10, 5, 6, 10);
    }

    @Test
    void internalZeroesAndOversizedLeadingDigitRetainExistingPolicy() {
        Pattern[] dest = patterns(8);
        NumericPatternWriter.writeBonus(dest, 0, 9001, patterns(20), new Pattern());
        assertTiles(dest, 19, 20, 1, 2, 1, 2, 3, 4);
        NumericPatternWriter.writeBonus(dest, 0, 12345, patterns(20), new Pattern());
        assertTiles(dest, 19, 20, 7, 8, 9, 10, 11, 12);
    }

    @Test
    void partialDigitPairsAreUntouchedButBlankingStillRequiresFullPairs() {
        Pattern[] dest = patterns(3);
        NumericPatternWriter.writeBonus(dest, 0, 1234, patterns(20), new Pattern());
        assertTiles(dest, 3, 4, 3);
        assertThrows(ArrayIndexOutOfBoundsException.class,
                () -> NumericPatternWriter.writeBonus(dest, 0, 0, patterns(20), new Pattern()));
        Pattern[] full = patterns(8);
        NumericPatternWriter.writeBonus(full, 0, 9999, patterns(19), new Pattern());
        assertTiles(full, 1, 2, 3, 4, 5, 6, 7, 8);
    }
}
